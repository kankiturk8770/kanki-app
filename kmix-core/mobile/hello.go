package mobile

// Hello (protocol 3, kanki-mix-server 3.67): when it starts, every proto.HelloEvery, and at once when the
// phone's network or the gaming mode changes, the core says hello on the main path (encrypted like everything
// else, never with a hint in front). The answer carries an 8-byte routing hint that goes in front of the next
// packets (obfs/hint.go), so the server finds this session in O(1) from whatever address a packet comes, and
// says whether gaming mode was taken. A server from before never answers: then nothing changes, and the hello
// is asked again ever more rarely (18 bytes).

import (
	"fmt"
	"os"
	"sync"
	"sync/atomic"
	"time"

	"kanki-tunnel/internal/obfs"
	"kanki-tunnel/internal/proto"
)

const (
	// helloRetry no answer yet: ask again after this × the hellos already without an answer (at most ×8)
	helloRetry = 3 * time.Second
	// hintMaxLife a hint is never used longer than this, whatever the server says
	hintMaxLife = 10 * time.Minute
	// hintLostAfter hinted and the server silent this long: it may have restarted and forgotten the hint, so the
	// packets go without it (the old way, which every server opens) until a new one comes
	hintLostAfter = 1500 * time.Millisecond
)

type hintNow struct {
	h     [obfs.HintLen]byte
	until time.Time
}

var (
	helloMu    sync.Mutex
	helloSent  [4]int64 // the send times of the latest hellos (an answer must carry one of them, once)
	helloNext  int
	helloLast  time.Time // the last hello went then
	helloGot   time.Time // the last answer came then
	helloTries int       // hellos since the last answer
	helloAsk   atomic.Bool
	hintCur    atomic.Pointer[hintNow]
	// gamingTaken the server said it runs this session in gaming mode (its answer to the last hello)
	gamingTaken atomic.Bool

	sendErrs atomic.Uint64
	lastErr  atomic.Pointer[string]
)

// noteSendErr counts a packet that could not be sent (the network went, the socket closed); the count and the
// last error are in the stats for the app
func noteSendErr(err error) {
	if err == nil {
		return
	}
	sendErrs.Add(1)
	m := err.Error()
	lastErr.Store(&m)
}

// SendErrors packets that could not be sent since the start, and the last reason
func SendErrors() (uint64, string) {
	m := ""
	if p := lastErr.Load(); p != nil {
		m = *p
	}
	return sendErrs.Load(), m
}

func logf(format string, a ...any) { fmt.Fprintf(os.Stderr, format+"\n", a...) }

func helloReset() {
	helloMu.Lock()
	helloSent = [4]int64{}
	helloNext, helloTries = 0, 0
	helloLast, helloGot = time.Time{}, time.Time{}
	helloMu.Unlock()
	hintCur.Store(nil)
	gamingTaken.Store(false)
	helloAsk.Store(true)
	sendErrs.Store(0)
	lastErr.Store(nil)
}

// helloSoon a hello at the next tick (the network or the gaming mode changed)
func helloSoon() { helloAsk.Store(true) }

// curHint the hint in use (nil = none or ended)
func curHint() *hintNow {
	h := hintCur.Load()
	if h == nil || !time.Now().Before(h.until) {
		return nil
	}
	return h
}

func helloCaps() byte {
	c := proto.HelloCapHint
	if gaming.Load() {
		c |= proto.HelloCapGaming
	}
	return c
}

func sendHello(p *dgramConn, now time.Time) {
	h := proto.Hello{Session: sessionID, Version: proto.HelloVersion, Caps: helloCaps(), SentAt: now.UnixNano()}
	helloMu.Lock()
	helloSent[helloNext] = h.SentAt
	helloNext = (helloNext + 1) % len(helloSent)
	helloLast = now
	helloTries++
	helloMu.Unlock()
	noteSendErr(p.pc.WritePacketBare(proto.KindHello, proto.AppendHello(nil, &h), p.dst()))
}

// helloTick from pingLoop: a hello when one is due, and the hint dropped when the server went silent with it
func helloTick(now time.Time) {
	p := dg.Load()
	if p == nil {
		return
	}
	if curHint() != nil && now.Sub(time.Unix(0, lastPong.Load())) >= hintLostAfter {
		setHint(nil)
		helloAsk.Store(true)
	}
	helloMu.Lock()
	every := proto.HelloEvery
	if helloGot.Before(helloLast) || helloGot.IsZero() {
		every = helloRetry * time.Duration(min(max(helloTries, 1), 8))
	}
	due := helloLast.IsZero() || now.Sub(helloLast) >= every
	helloMu.Unlock()
	if helloAsk.Swap(false) || due {
		sendHello(p, now)
	}
}

// gotHelloAck the server's answer; only one to a hello of ours (its send time), and each only once
func gotHelloAck(b []byte, now time.Time) {
	a, err := proto.ParseHelloAck(b)
	if err != nil || a.Session != sessionID || a.SentAt == 0 {
		return
	}
	helloMu.Lock()
	ok := false
	for i, t := range helloSent {
		if t == a.SentAt {
			ok = true
			helloSent[i] = 0
		}
	}
	if ok {
		helloGot, helloTries = now, 0
	}
	helloMu.Unlock()
	if !ok {
		return
	}
	gamingTaken.Store(a.Caps&proto.HelloCapGaming != 0)
	if a.Caps&proto.HelloCapHint == 0 || a.LifeSec == 0 {
		setHint(nil)
		return
	}
	life := min(time.Duration(a.LifeSec)*time.Second, hintMaxLife)
	setHint(&hintNow{h: a.Hint, until: now.Add(life)})
}

// setHint the hint on every socket (nil = none)
func setHint(h *hintNow) {
	hintCur.Store(h)
	pathsMu.Lock()
	l := append([]*netSock(nil), socks...)
	pathsMu.Unlock()
	for _, s := range l {
		if h == nil {
			s.pc.ClearHint()
		} else {
			s.pc.SetHint(h.h, h.until)
		}
	}
}

// HintActive packets go with a routing hint now (for the app)
func HintActive() bool { return curHint() != nil }
