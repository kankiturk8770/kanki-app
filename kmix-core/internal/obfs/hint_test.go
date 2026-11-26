package obfs

import (
	"bytes"
	"net"
	"testing"
	"time"

	"golang.org/x/crypto/chacha20poly1305"
)

// sink a net.PacketConn that keeps what is written
type sink struct {
	net.PacketConn
	got [][]byte
}

func (s *sink) WriteTo(b []byte, _ net.Addr) (int, error) {
	s.got = append(s.got, append([]byte(nil), b...))
	return len(b), nil
}

func openWire(t *testing.T, key, w []byte) (byte, []byte) {
	t.Helper()
	a, err := chacha20poly1305.New(key)
	if err != nil {
		t.Fatal(err)
	}
	p, err := a.Open(nil, w[:nonceSize], w[nonceSize:], nil)
	if err != nil {
		t.Fatalf("does not open: %v", err)
	}
	n := int(p[1])<<8 | int(p[2])
	return p[0], p[hdrSize : hdrSize+n]
}

// a hinted packet: the hint in front, the rest exactly the old format; never on KCP, never on a bare write,
// never past MaxWire, never after it ended
func TestHintOnTheWire(t *testing.T) {
	key := bytes.Repeat([]byte{7}, 32)
	s := &sink{}
	c, err := New(s, key, nil)
	if err != nil {
		t.Fatal(err)
	}
	dst := &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 9}
	h := [HintLen]byte{1, 2, 3, 4, 5, 6, 7, 8}
	c.SetHint(h, time.Now().Add(time.Minute))

	if err := c.WritePacket(0x03, []byte("game"), dst); err != nil {
		t.Fatal(err)
	}
	w := s.got[len(s.got)-1]
	if !bytes.Equal(w[:HintLen], h[:]) {
		t.Fatal("no hint in front")
	}
	if k, p := openWire(t, key, w[HintLen:]); k != 0x03 || string(p) != "game" {
		t.Fatalf("after the hint: kind %x %q", k, p)
	}

	for _, kind := range []byte{KindKCP, KindKCPPlain} {
		if err := c.WritePacket(kind, []byte("kcp"), dst); err != nil {
			t.Fatal(err)
		}
		openWire(t, key, s.got[len(s.got)-1]) // opens with no hint in front
	}
	if err := c.WritePacketBare(0x09, []byte("hello"), dst); err != nil {
		t.Fatal(err)
	}
	openWire(t, key, s.got[len(s.got)-1])

	// the longest payload: no room for the hint, it goes without one and stays inside MaxWire
	big := bytes.Repeat([]byte{9}, MaxPayload)
	for i := 0; i < 50; i++ {
		if err := c.WritePacket(0x03, big, dst); err != nil {
			t.Fatal(err)
		}
		w := s.got[len(s.got)-1]
		if len(w) > MaxWire {
			t.Fatalf("wire %d > %d", len(w), MaxWire)
		}
		openWire(t, key, w)
	}
	// just fits with the hint: never past MaxWire whatever the padding
	fit := bytes.Repeat([]byte{9}, MaxPayload-HintLen)
	for i := 0; i < 50; i++ {
		if err := c.WritePacket(0x03, fit, dst); err != nil {
			t.Fatal(err)
		}
		if w := s.got[len(s.got)-1]; len(w) > MaxWire || !bytes.Equal(w[:HintLen], h[:]) {
			t.Fatalf("wire %d, hint %v", len(w), w[:HintLen])
		}
	}

	c.SetHint(h, time.Now().Add(-time.Second))
	if c.Hinted() {
		t.Fatal("an ended hint is still used")
	}
	if err := c.WritePacket(0x03, []byte("x"), dst); err != nil {
		t.Fatal(err)
	}
	openWire(t, key, s.got[len(s.got)-1])
	c.SetHint(h, time.Now().Add(time.Minute))
	c.ClearHint()
	if c.Hinted() {
		t.Fatal("cleared hint still used")
	}
}
