//go:build linux || android

package kping

import (
	"net"
	"syscall"
)

// tune: a game socket asks for the voice queue — DSCP EF (46) in the IP header, which Wi-Fi (WMM) puts in
// its fastest access category on the phone and on most home routers, and socket priority 6 for the
// phone's own queues — and a small send buffer, so the phone itself never holds a pile of old packets.
func tune(conn net.PacketConn) {
	sc, ok := conn.(syscall.Conn)
	if !ok {
		return
	}
	raw, err := sc.SyscallConn()
	if err != nil {
		return
	}
	_ = raw.Control(func(fd uintptr) {
		f := int(fd)
		_ = syscall.SetsockoptInt(f, syscall.IPPROTO_IP, syscall.IP_TOS, 0xb8)
		_ = syscall.SetsockoptInt(f, syscall.IPPROTO_IPV6, syscall.IPV6_TCLASS, 0xb8)
		_ = syscall.SetsockoptInt(f, syscall.SOL_SOCKET, syscall.SO_PRIORITY, 6)
		_ = syscall.SetsockoptInt(f, syscall.SOL_SOCKET, syscall.SO_SNDBUF, 64*1024)
	})
}
