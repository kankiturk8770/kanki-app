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
	lc := net.ListenConfig{}
	if public(to.Addr()) {
		lc.Control = Outside
	}
	pc, err := lc.ListenPacket(context.Background(), network, addr)
	if err != nil {
		return nil, err
	}
	return pc.(*net.UDPConn), nil
}

// public: an internet address. Loopback, the local network and the like are reached the normal
// way (tying them to the network card would send them out of it and they would never answer).
func public(a netip.Addr) bool {
	a = a.Unmap()
	return a.IsGlobalUnicast() && !a.IsPrivate()
}

// outsideFor is the Control of a dial to addr ("host:port"): the outside card for internet addresses only
func outsideFor(network, address string, c syscall.RawConn) error {
	if Outside == nil {
		return nil
	}
	if ap, err := netip.ParseAddrPort(address); err == nil && !public(ap.Addr()) {
		return nil
	}
	return Outside(network, address, c)
}
