package cores

import (
	"context"
	"net"
	"net/netip"
	"syscall"
)

// Outside, when set, ties a probe socket to the computer's real network card
// (on Windows: the card of the default route that is not the VPN), so a ping
// taken while the VPN is on does not go through the tunnel (Probe.kt binds to
// the phone's underlying network the same way). nil = the normal route.
var Outside func(network, address string, c syscall.RawConn) error

// probeUDP is the UDP socket of one ping, for the family of the address it talks to.
func probeUDP(to netip.AddrPort) (*net.UDPConn, error) {
	network, addr := "udp4", "0.0.0.0:0"
	if !to.Addr().Unmap().Is4() {
		network, addr = "udp6", "[::]:0"
	}
	lc := net.ListenConfig{Control: Outside}
	pc, err := lc.ListenPacket(context.Background(), network, addr)
	if err != nil {
		return nil, err
	}
	return pc.(*net.UDPConn), nil
}
