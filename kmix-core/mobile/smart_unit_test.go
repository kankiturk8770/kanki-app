package mobile

import (
	"net"
	"testing"
	"time"

	"kanki-tunnel/internal/game"
	"kanki-tunnel/internal/proto"
)

type wnd struct{ snd, rcv int }

// the port moves round the server's ports, the session stays
func TestHopPorts(t *testing.T) {
	SetPorts("4000,4001-4003")
	defer SetPorts("")
	serverMu.Lock()
	server = "203.0.113.5:4000"
	serverMu.Unlock()
	dc := &dgramConn{home: 4000}
	dc.srv.Store(&net.UDPAddr{IP: net.IPv4(203, 0, 113, 5), Port: 4000})
	sid := sessionID
	var seen []int
	for i := 0; i < 5; i++ {
		hopMu.Lock()
		l := hopPorts
		hopMu.Unlock()
		dc.hop(l)
		seen = append(seen, dc.dst().Port)
	}
	want := []int{4001, 4002, 4003, 4000, 4001}
	for i := range want {
		if seen[i] != want[i] {
			t.Fatalf("ports %v, want %v", seen, want)
		}
	}
	if sessionID != sid || getServer() != "203.0.113.5:4001" {
		t.Fatalf("session or server address: %v %s", sessionID == sid, getServer())
	}
	// no ports given: never moves
	SetPorts("")
	hopMu.Lock()
	n := len(hopPorts)
	hopMu.Unlock()
	if n != 0 {
		t.Fatal("empty spec gave ports")
	}
	SetPorts("9-3,abc,70000")
	hopMu.Lock()
	n = len(hopPorts)
	hopMu.Unlock()
	if n != 0 {
		t.Fatal("bad specs gave ports")
	}
}

// silence: statTick moves the port; a server that answers does not
func TestHopOnSilence(t *testing.T) {
	smartReset()
	SetPorts("5001-5002")
	defer SetPorts("")
	dc := &dgramConn{home: 5000}
	dc.srv.Store(&net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 5000})
	now := time.Now()
	statTick(nil, now, 10*time.Second) // no socket: nothing
	if dc.dst().Port != 5000 {
		t.Fatal("moved without a socket")
	}
	hopMu.Lock()
	hopAt = time.Time{}
	hopMu.Unlock()
	silentHop(dc, now, time.Second)
	if dc.dst().Port != 5000 {
		t.Fatal("moved while the server answers")
	}
	silentHop(dc, now, 3*time.Second)
	if dc.dst().Port != 5001 {
		t.Fatalf("did not move after silence: %d", dc.dst().Port)
	}
	silentHop(dc, now.Add(time.Second), 4*time.Second)
	if dc.dst().Port != 5001 {
		t.Fatal("moved again at once")
	}
}

// the KCP window: small while a game plays, full again after
func TestKCPWindowWhilePlaying(t *testing.T) {
	smartReset()
	gaming.Store(true)
	w := &wnd{}
	setWin = func(s, r int) { w.snd, w.rcv = s, r }
	defer func() { setWin = nil }()
	now := time.Now()
	lastGame.Store(now.UnixNano())
	kcpTick(now)
	if w.snd != kcpWndPlay || w.rcv != kcpWndPlay {
		t.Fatalf("while playing: %+v", *w)
	}
	kcpTick(now.Add(5 * time.Second))
	if w.snd != kcpWndFree || w.rcv != kcpWndFree {
		t.Fatalf("after the game: %+v", *w)
	}
	gaming.Store(false)
	lastGame.Store(now.Add(5 * time.Second).UnixNano())
	kcpTick(now.Add(5 * time.Second))
	if w.snd != kcpWndFree {
		t.Fatal("gaming off but the window is small")
	}
}

// what goes up: a game packet once on a clean line (no copy, no repair), DNS and QUIC never copied
func TestUpOnlyGameIsProtected(t *testing.T) {
	var c game.Class
	now := time.Now()
	if c.Note(60, 53, now) != game.KindCtl {
		t.Fatal("DNS")
	}
	var q game.Class
	if q.Note(1200, 443, now) != game.KindBulk {
		t.Fatal("QUIC")
	}
	if proto.FlagCopy&proto.FlagDup != 0 || proto.FlagGame&(proto.FlagDup|proto.FlagCopy) != 0 {
		t.Fatal("flags overlap")
	}
}
