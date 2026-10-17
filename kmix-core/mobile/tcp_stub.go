//go:build !tcp

package mobile

import "os"

// بیلد بدون TCP (فقط UDP). برای فعال‌کردن TCP با تگ «tcp» بیلد کن:
//
//	gomobile bind -tags tcp ...
func HasTCP() bool { return false }

func startTCP(tf *os.File, mtu int) error { return nil }
func stopTCP()                            {}
func handleTCPPacket(raw []byte)          {}
