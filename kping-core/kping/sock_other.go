//go:build !linux && !android

package kping

import "net"

func tune(net.PacketConn) {}
