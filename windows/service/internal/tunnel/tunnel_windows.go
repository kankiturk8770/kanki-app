// Package tunnel is the Windows engine: a Wintun adapter, the WireGuard /
// AmneziaWG device (amneziawg-go speaks both; with no obfuscation keys it is
// plain WireGuard), addresses, routes and DNS on the adapter, and the WFP
// rules from internal/firewall.
package tunnel

import (
	"bufio"
	"fmt"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/conn"
	"github.com/amnezia-vpn/amneziawg-go/device"
	"github.com/amnezia-vpn/amneziawg-go/tun"
	"golang.org/x/sys/windows"
	"golang.zx2c4.com/wireguard/windows/tunnel/winipcfg"

	"kankivpn/internal/core"
	"kankivpn/internal/firewall"
	"kankivpn/internal/logx"
	"kankivpn/internal/route"
)

// AdapterName is the name shown in Windows' network connections.
const AdapterName = "Kanki VPN"

// The same adapter GUID every time, so Windows keeps one network profile for
// it instead of "Kanki VPN 2, 3, ...".
var adapterGUID = windows.GUID{Data1: 0x6b616e6b, Data2: 0x6976, Data3: 0x706e, Data4: [8]byte{0x8a, 0x1f, 0x52, 0x4e, 0x0c, 0x77, 0x31, 0x01}}

func init() {
	tun.WintunTunnelType = "Kanki VPN"
	tun.WintunStaticRequestedGUID = &adapterGUID
}

// Engine implements core.Engine.
type Engine struct {
	log *logx.Log

	mu  sync.Mutex
	dev *device.Device
	fw  *firewall.Session
}

func New(log *logx.Log) *Engine { return &Engine{log: log} }

// Up builds the tunnel. Any tunnel or lockdown before it is replaced, with the
// new rules installed before the old ones go.
func (e *Engine) Up(t core.Tunnel) (err error) {
	e.mu.Lock()
	defer e.mu.Unlock()

	// the old device goes first (its adapter has the same GUID); the old
	// firewall session stays until the new one is in place
	if e.dev != nil {
		e.dev.Close()
		e.dev = nil
	}

	td, err := tun.CreateTUN(AdapterName, t.MTU)
	if err != nil {
		return fmt.Errorf("could not create the network adapter (is wintun.dll next to the service?): %w", err)
	}
	native := td.(*tun.NativeTun)
	luid := winipcfg.LUID(native.LUID())

	dev := device.NewDevice(td, conn.NewDefaultBind(), &device.Logger{
		Verbosef: func(string, ...any) {},
		Errorf: func(f string, a ...any) {
			e.log.Printf("tunnel: "+f, a...)
		},
	})
	defer func() {
		if err != nil {
			dev.Close()
		}
	}()
	if err = dev.IpcSet(t.Config.UAPI(t.Endpoint)); err != nil {
		return fmt.Errorf("tunnel settings refused: %w", err)
	}

	if err = configure(luid, t); err != nil {
		return err
	}

	fw, err := firewall.Enable(firewall.Options{
		LUID:      uint64(luid),
		BlockAll:  t.KillSwitch,
		BlockIPv6: t.BlockIPv6,
		DNS:       t.DNS,
		Bypass:    t.Plan.Bypass,
	})
	if err != nil {
		return fmt.Errorf("could not set the firewall rules: %w", err)
	}

	if err = dev.Up(); err != nil {
		fw.Close()
		return fmt.Errorf("could not start the tunnel: %w", err)
	}
	e.fw.Close()
	e.fw, e.dev = fw, dev
	return nil
}

// configure puts addresses, routes, DNS and metrics on the adapter.
func configure(luid winipcfg.LUID, t core.Tunnel) error {
	var v4, v6 []netip.Prefix
	for _, a := range t.Config.Addresses {
		if a.Addr().Is4() {
			v4 = append(v4, a)
		} else {
			v6 = append(v6, a)
		}
	}
	if err := luid.SetIPAddressesForFamily(windows.AF_INET, v4); err != nil {
		return fmt.Errorf("adapter address: %w", err)
	}
	if len(v6) > 0 {
		if err := luid.SetIPAddressesForFamily(windows.AF_INET6, v6); err != nil {
			return fmt.Errorf("adapter IPv6 address: %w", err)
		}
	}

	var routes []*winipcfg.RouteData
	for _, p := range t.Plan.Routes {
		hop := netip.IPv4Unspecified()
		if p.Addr().Is6() {
			hop = netip.IPv6Unspecified()
		}
		routes = append(routes, &winipcfg.RouteData{Destination: p, NextHop: hop, Metric: 0})
	}
	if err := luid.SetRoutes(routes); err != nil {
		return fmt.Errorf("routes: %w", err)
	}

	for _, fam := range []winipcfg.AddressFamily{windows.AF_INET, windows.AF_INET6} {
		ipif, err := luid.IPInterface(fam)
		if err != nil {
			if fam == windows.AF_INET6 {
				continue // IPv6 turned off on this computer
			}
			return fmt.Errorf("adapter: %w", err)
		}
		// lowest metric: the tunnel's DNS servers are asked first
		ipif.UseAutomaticMetric = false
		ipif.Metric = 0
		ipif.NLMTU = uint32(t.MTU)
		ipif.DadTransmits = 0
		ipif.RouterDiscoveryBehavior = winipcfg.RouterDiscoveryDisabled
		if err := ipif.Set(); err != nil && fam == windows.AF_INET {
			return fmt.Errorf("adapter metric: %w", err)
		}
	}

	var dns4, dns6 []netip.Addr
	for _, d := range t.DNS {
		if d.Is4() {
			dns4 = append(dns4, d)
		} else {
			dns6 = append(dns6, d)
		}
	}
	if err := luid.SetDNS(windows.AF_INET, dns4, nil); err != nil {
		return fmt.Errorf("DNS: %w", err)
	}
	_ = luid.SetDNS(windows.AF_INET6, dns6, nil)
	return nil
}

// Down removes the tunnel. With lockdown, everything except this service,
// DHCP, NDP, loopback (and the LAN when allowed) stays blocked.
func (e *Engine) Down(lockdown, allowLAN bool) {
	e.mu.Lock()
	defer e.mu.Unlock()
	var next *firewall.Session
	if lockdown {
		o := firewall.Options{BlockAll: true}
		if allowLAN {
			o.Bypass = route.LAN
		}
		var err error
		if next, err = firewall.Enable(o); err != nil {
			e.log.Printf("lockdown: %v", err)
		}
	}
	if e.dev != nil {
		e.dev.Close() // also removes the adapter, its routes and DNS
		e.dev = nil
	}
	e.fw.Close()
	e.fw = next
}

// Stats reads the device's counters.
func (e *Engine) Stats() core.Stats {
	e.mu.Lock()
	dev := e.dev
	e.mu.Unlock()
	var s core.Stats
	if dev == nil {
		return s
	}
	text, err := dev.IpcGet()
	if err != nil {
		return s
	}
	var sec, nsec int64
	sc := bufio.NewScanner(strings.NewReader(text))
	for sc.Scan() {
		k, v, _ := strings.Cut(sc.Text(), "=")
		n, _ := strconv.ParseInt(v, 10, 64)
		switch k {
		case "rx_bytes":
			s.Rx += uint64(n)
		case "tx_bytes":
			s.Tx += uint64(n)
		case "last_handshake_time_sec":
			sec = n
		case "last_handshake_time_nsec":
			nsec = n
		}
	}
	if sec > 0 {
		s.LastHandshake = time.Unix(sec, nsec)
	}
	return s
}
