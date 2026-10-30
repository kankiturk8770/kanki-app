// Package tunnel is the Windows engine. Every protocol gets the same Wintun
// adapter ("Kanki VPN"), the same routes, DNS and WFP rules; only what moves the
// packets differs:
//
//	WireGuard / AmneziaWG  amneziawg-go (with no obfuscation keys it is plain WireGuard)
//	KANKI MIX              the kmix-core Go core, reading and writing the adapter
//	microtun               the mtun-core Go client, reading and writing the adapter
//	VLESS / Hysteria2      the official Xray / Hysteria program (SOCKS on 127.0.0.1)
//	                       and tun2socks between the adapter and that SOCKS port
//
// This is how the Android app runs them too (Hysteria.kt), with Wintun in place
// of the VpnService TUN.
package tunnel

import (
	"bufio"
	"fmt"
	"net/netip"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/conn"
	"github.com/amnezia-vpn/amneziawg-go/device"
	"github.com/amnezia-vpn/amneziawg-go/tun"
	"golang.org/x/sys/windows"
	wgtun "golang.zx2c4.com/wireguard/tun"
	"golang.zx2c4.com/wireguard/windows/tunnel/winipcfg"

	"kankivpn/internal/core"
	"kankivpn/internal/cores"
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
	// two Wintun wrappers (amneziawg-go's and tun2socks'), one adapter identity
	tun.WintunTunnelType, tun.WintunStaticRequestedGUID = "Kanki VPN", &adapterGUID
	wgtun.WintunTunnelType, wgtun.WintunStaticRequestedGUID = "Kanki VPN", &adapterGUID
}

// running is the core of the tunnel that is up.
type running interface {
	stats() (rx, tx uint64, alive bool)
	close()
}

// Engine implements core.Engine.
type Engine struct {
	log *logx.Log
	dir string // where kanki-svc.exe, xray.exe and hysteria.exe are

	mu  sync.Mutex
	run running
	fw  *firewall.Session
}

func New(log *logx.Log) *Engine {
	exe, _ := os.Executable()
	return &Engine{log: log, dir: filepath.Dir(exe)}
}

// programs whose traffic the firewall lets out (only those that exist)
func (e *Engine) apps() []string {
	var out []string
	for _, n := range []string{"xray.exe", "hysteria.exe"} {
		p := filepath.Join(e.dir, n)
		if _, err := os.Stat(p); err == nil {
			out = append(out, p)
		}
	}
	return out
}

// Up builds the tunnel. Any tunnel or lockdown before it is replaced, with the
// new rules installed before the old ones go.
func (e *Engine) Up(t core.Tunnel) (err error) {
	e.mu.Lock()
	defer e.mu.Unlock()

	// the old core goes first (its adapter has the same GUID); the old
	// firewall session stays until the new one is in place
	if e.run != nil {
		e.run.close()
		e.run = nil
	}

	var r running
	var luid winipcfg.LUID
	var addrs []netip.Prefix
	switch t.Proto {
	case cores.WireGuard, cores.AmneziaWG, "":
		r, luid, err = upWireGuard(e.log, t)
		addrs = t.Config.Addresses
	case cores.KankiMix:
		r, luid, err = upKmix(t)
		addrs = []netip.Prefix{netip.MustParsePrefix("10.77.0.2/32")}
	case cores.Microtun:
		r, luid, addrs, err = upMtun(e.log, t)
	case cores.VLESS, cores.Hysteria2:
		r, luid, err = e.upProgram(t)
		addrs = []netip.Prefix{netip.MustParsePrefix("198.18.0.1/32")}
	default:
		return fmt.Errorf("protocol %q does not run on Windows", t.Proto)
	}
	if err != nil {
		return err
	}
	defer func() {
		if err != nil {
			r.close()
		}
	}()

	if err = configure(luid, addrs, t); err != nil {
		return err
	}
	fw, err := firewall.Enable(firewall.Options{
		LUID:      uint64(luid),
		BlockAll:  t.KillSwitch,
		BlockIPv6: t.BlockIPv6,
		DNS:       t.DNS,
		Bypass:    t.Plan.Bypass,
		Apps:      e.apps(),
	})
	if err != nil {
		return fmt.Errorf("could not set the firewall rules: %w", err)
	}
	e.fw.Close()
	e.fw, e.run = fw, r
	return nil
}

// ---------------------------------------------------------------- WireGuard / AmneziaWG

type wgRun struct{ dev *device.Device }

func upWireGuard(log *logx.Log, t core.Tunnel) (running, winipcfg.LUID, error) {
	td, err := tun.CreateTUN(AdapterName, t.MTU)
	if err != nil {
		return nil, 0, adapterErr(err)
	}
	luid := winipcfg.LUID(td.(*tun.NativeTun).LUID())
	dev := device.NewDevice(td, conn.NewDefaultBind(), &device.Logger{
		Verbosef: func(string, ...any) {},
		Errorf:   func(f string, a ...any) { log.Printf("tunnel: "+f, a...) },
	})
	if err := dev.IpcSet(t.Config.UAPI(t.Endpoint)); err != nil {
		dev.Close()
		return nil, 0, fmt.Errorf("tunnel settings refused: %w", err)
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		return nil, 0, fmt.Errorf("could not start the tunnel: %w", err)
	}
	return &wgRun{dev}, luid, nil
}

func (w *wgRun) close() { w.dev.Close() } // also removes the adapter, its routes and DNS

func (w *wgRun) stats() (rx, tx uint64, alive bool) {
	text, err := w.dev.IpcGet()
	if err != nil {
		return 0, 0, false
	}
	var sec int64
	sc := bufio.NewScanner(strings.NewReader(text))
	for sc.Scan() {
		k, v, _ := strings.Cut(sc.Text(), "=")
		n, _ := strconv.ParseInt(v, 10, 64)
		switch k {
		case "rx_bytes":
			rx += uint64(n)
		case "tx_bytes":
			tx += uint64(n)
		case "last_handshake_time_sec":
			sec = n
		}
	}
	// WireGuard rekeys every 2 minutes: a handshake within 3 is a live tunnel
	return rx, tx, sec > 0 && time.Since(time.Unix(sec, 0)) < 3*time.Minute
}

func adapterErr(err error) error {
	return fmt.Errorf("could not create the network adapter (is wintun.dll next to the service?): %w", err)
}

// ---------------------------------------------------------------- adapter settings

// configure puts addresses, routes, DNS and metrics on the adapter.
func configure(luid winipcfg.LUID, addrs []netip.Prefix, t core.Tunnel) error {
	var v4, v6 []netip.Prefix
	for _, a := range addrs {
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
		if p.Addr().Is6() && len(v6) == 0 {
			continue
		}
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
		} else if len(v6) > 0 {
			dns6 = append(dns6, d)
		}
	}
	if err := luid.SetDNS(windows.AF_INET, dns4, nil); err != nil {
		return fmt.Errorf("DNS: %w", err)
	}
	_ = luid.SetDNS(windows.AF_INET6, dns6, nil)
	return nil
}

// adapterCounters: bytes in and out of the adapter (for the cores that keep no count).
func adapterCounters(luid winipcfg.LUID) (rx, tx uint64) {
	row, err := luid.Interface()
	if err != nil {
		return 0, 0
	}
	return row.InOctets, row.OutOctets
}

// Down removes the tunnel. With lockdown, everything except this service,
// DHCP, NDP, loopback (and the LAN when allowed) stays blocked.
func (e *Engine) Down(lockdown, allowLAN bool) {
	e.mu.Lock()
	defer e.mu.Unlock()
	var next *firewall.Session
	if lockdown {
		o := firewall.Options{BlockAll: true, Apps: e.apps()}
		if allowLAN {
			o.Bypass = route.LAN
		}
		var err error
		if next, err = firewall.Enable(o); err != nil {
			e.log.Printf("lockdown: %v", err)
		}
	}
	if e.run != nil {
		e.run.close()
		e.run = nil
	}
	e.fw.Close()
	e.fw = next
}

// Stats of the running core. A core that is alive counts as a fresh handshake.
func (e *Engine) Stats() core.Stats {
	e.mu.Lock()
	r := e.run
	e.mu.Unlock()
	var s core.Stats
	if r == nil {
		return s
	}
	rx, tx, alive := r.stats()
	s.Rx, s.Tx = rx, tx
	if alive {
		s.LastHandshake = time.Now()
	}
	return s
}
