package mobile

import (
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
	sid := sessionID
	var seen []int
	cur := 4000
	for i := 0; i < 5; i++ {
		hopMu.Lock()
		l := hopPorts
		hopMu.Unlock()
		cur = nextPort(4000, cur, l)
		seen = append(seen, cur)
	}
	want := []int{4001, 4002, 4003, 4000, 4001}
	for i := range want {
		if seen[i] != want[i] {
			t.Fatalf("ports %v, want %v", seen, want)
		}
	}
	if sessionID != sid {
		t.Fatal("the session changed")
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
