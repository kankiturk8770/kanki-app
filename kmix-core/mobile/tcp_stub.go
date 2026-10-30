//go:build !tcp

package mobile

import "io"

// بیلد بدون TCP (فقط UDP). برای فعال‌کردن TCP با تگ «tcp» بیلد کن:
//
//	gomobile bind -tags tcp ...
func HasTCP() bool { return false }

func startTCP(tf io.Writer, mtu int) error { return nil }
func stopTCP()                             {}
func handleTCPPacket(raw []byte)           {}
