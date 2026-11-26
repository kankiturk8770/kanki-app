package obfs

// Routing hints, client side (protocol 3, kanki-mix-server 3.67). The server gives an 8-byte hint inside its
// encrypted answer to a hello (proto.KindHelloAck); the client puts it in front of its packets:
//
//	[ 8B hint ][ 12B random nonce ][ AEAD( [1B kind][2B length][payload] + random padding ) ]
//
// so a packet from an address the server has not seen yet (Wi-Fi ⇄ mobile data) finds its user in O(1). The
// hint is never put on KCP packets, never on a hello, and never when the packet would then be longer than
// MaxWire: those go exactly as before. A server from before never gives a hint, so nothing changes for it.

import "time"

// HintLen bytes in front of a hinted packet
const HintLen = 8

type hintState struct {
	h     [HintLen]byte
	until int64 // UnixNano
}

// SetHint the hint the server gave, good until `until` (the shorter of its life and the client's own limit)
func (c *PacketConn) SetHint(h [HintLen]byte, until time.Time) {
	c.hint.Store(&hintState{h: h, until: until.UnixNano()})
}

// ClearHint: back to packets without a hint (the server forgot it, or a new session)
func (c *PacketConn) ClearHint() { c.hint.Store(nil) }

// Hinted: a hint is set and still good
func (c *PacketConn) Hinted() bool { return c.liveHint(0) != nil }

// liveHint the hint to put in front of a packet of n bytes (nil = none, ended, or the packet would not fit)
func (c *PacketConn) liveHint(n int) *hintState {
	hs := c.hint.Load()
	if hs == nil || time.Now().UnixNano() >= hs.until || n > MaxPayload-HintLen {
		return nil
	}
	return hs
}
