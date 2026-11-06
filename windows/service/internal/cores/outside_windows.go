package cores

import (
	"math/bits"
	"strings"
	"sync"
	"syscall"
	"time"

	"golang.org/x/sys/windows"
	"golang.zx2c4.com/wireguard/windows/tunnel/winipcfg"
)

// the VPN's own adapter (tunnel.AdapterName): never the "outside" card
const vpnAdapter = "Kanki VPN"

// IP_UNICAST_IF / IPV6_UNICAST_IF: send through this interface, whatever the routes say
const unicastIf = 31

func init() { Outside = bindOutside }

var (
	outMu   sync.Mutex
	outAt   time.Time
	outIdx4 uint32
	outIdx6 uint32
)

// outsideIndex is the interface of the best default route that is not the VPN
// (0 = none known), looked up again at most every 5 seconds (Wi-Fi <-> cable).
func outsideIndex(v6 bool) uint32 {
	outMu.Lock()
	defer outMu.Unlock()
	if time.Since(outAt) > 5*time.Second {
		outIdx4, outIdx6 = bestDefault(windows.AF_INET), bestDefault(windows.AF_INET6)
		outAt = time.Now()
	}
	if v6 {
		return outIdx6
	}
	return outIdx4
}

func bestDefault(family winipcfg.AddressFamily) uint32 {
	rows, err := winipcfg.GetIPForwardTable2(family)
	if err != nil {
		return 0
	}
	var best uint32
	bestMetric := ^uint32(0)
	for i := range rows {
		r := &rows[i]
		if r.DestinationPrefix.PrefixLength != 0 {
			continue
		}
		ifc, err := r.InterfaceLUID.Interface()
		if err != nil || strings.EqualFold(ifc.Alias(), vpnAdapter) || ifc.OperStatus != winipcfg.IfOperStatusUp {
			continue
		}
		if r.Metric < bestMetric {
			best, bestMetric = r.InterfaceIndex, r.Metric
		}
	}
	return best
}

// bindOutside is the socket Control of a probe; a socket it cannot bind keeps
// the normal route (the ping is then only a little higher, never wrong).
func bindOutside(network, _ string, c syscall.RawConn) error {
	v6 := strings.HasSuffix(network, "6")
	idx := outsideIndex(v6)
	if idx == 0 {
		return nil
	}
	_ = c.Control(func(fd uintptr) {
		if v6 {
			_ = windows.SetsockoptInt(windows.Handle(fd), windows.IPPROTO_IPV6, unicastIf, int(idx))
		} else {
			// IPv4 takes the index in network byte order
			_ = windows.SetsockoptInt(windows.Handle(fd), windows.IPPROTO_IP, unicastIf, int(bits.ReverseBytes32(idx)))
		}
	})
	return nil
}
