package mobile

import (
	"bytes"
	"encoding/binary"
	"net"
	"sync/atomic"
	"testing"
	"time"

	"kanki-tunnel/internal/obfs"
	"kanki-tunnel/internal/proto"
)

// fakeSrv a UDP port that answers pings (and hellos) like the server, while `on`
type fakeSrv struct {
	pc    *obfs.PacketConn
	addr  *net.UDPAddr
	on    atomic.Bool
	pings atomic.Int32
	hello atomic.Pointer[proto.Hello]
}

func newFakeSrv(t *testing.T, key []byte) *fakeSrv {
	t.Helper()
	raw, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	pc, err := obfs.New(raw, key, nil)
	if err != nil {
		t.Fatal(err)
	}
	f := &fakeSrv{pc: pc, addr: raw.LocalAddr().(*net.UDPAddr)}
	t.Cleanup(func() { raw.Close() })
	go func() {
		b := make([]byte, 2048)
		for {
			kind, n, from, err := pc.ReadPacket(b)
			if err != nil {
				return
			}
			f.pings.Add(1)
			if !f.on.Load() {
				continue
			}
			switch kind {
			case proto.KindPing:
				if err := pc.WritePacket(proto.KindPong, b[:n], from); err != nil {
					return
				}
			case proto.KindHello:
				h, err := proto.ParseHello(b[:n])
				if err != nil {
					continue
				}
				f.hello.Store(&h)
			}
		}
	}()
	return f
}

// withPaths: the core's state for one test, a socket per name, the main path on the first, aimed at srv
func withPaths(t *testing.T, srv *net.UDPAddr, names ...string) []*dgramConn {
	t.Helper()
	psk = bytes.Repeat([]byte{0x5a}, 32)
	serverMu.Lock()
	server = srv.String()
	serverMu.Unlock()
	homePort = srv.Port
	sessionID = 0x1122334455667788
	smartReset()
	helloReset()
	dualOn.Store(false)
	var out []*dgramConn
	for _, n := range names {
		raw, err := net.ListenPacket("udp", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		ns, err := newSock(n, raw)
		if err != nil {
			t.Fatal(err)
		}
		pathsMu.Lock()
		socks = append(socks, ns)
		pathsMu.Unlock()
		go sockReadLoop(ns)
		out = append(out, ns.paths[0])
	}
	dg.Store(out[0])
	t.Cleanup(func() {
		closeDgram()
		SetPorts("")
		gaming.Store(false)
		lossUp.Store(0)
		lossDown.Store(0)
	})
	return out
}

func pingNow(now time.Time) []byte {
	var b [proto.PingLen]byte
	binary.BigEndian.PutUint64(b[0:8], sessionID)
	binary.BigEndian.PutUint64(b[8:16], uint64(now.UnixNano()))
	return b[:]
}

func waitUntil(t *testing.T, d time.Duration, ok func() bool) bool {
	t.Helper()
	end := time.Now().Add(d)
	for time.Now().Before(end) {
		if ok() {
			return true
		}
		time.Sleep(5 * time.Millisecond)
	}
	return ok()
}

func stall(p *dgramConn, ago time.Duration) {
	now := time.Now().UnixNano()
	p.mu.Lock()
	p.samples, p.srtt, p.rttvar = 20, 40, 3
	p.lastPong = now - int64(ago)
	p.waitFrom = now - int64(ago) + int64(50*time.Millisecond)
	p.mu.Unlock()
}

// make before break: the main port is silent for 400 ms, the next port is tried while the old one stays the
// path; only once the new one answers does it become the path's port
func TestHopMakeBeforeBreak(t *testing.T) {
	key := bytes.Repeat([]byte{0x5a}, 32)
	a, b := newFakeSrv(t, key), newFakeSrv(t, key)
	b.on.Store(true)
	ps := withPaths(t, a.addr, "wifi")
	p := ps[0]
	SetPorts(itoa(b.addr.Port))

	stall(p, 300*time.Millisecond)
	hopTick(time.Now(), pingNow(time.Now()))
	if p.probe.Load() != nil {
		t.Fatal("tried another port before 400 ms of silence")
	}
	stall(p, 450*time.Millisecond)
	hopTick(time.Now(), pingNow(time.Now()))
	if p.probe.Load() == nil {
		t.Fatal("no try after 450 ms of silence")
	}
	if !waitUntil(t, 2*time.Second, func() bool { return p.dst().Port == b.addr.Port }) {
		t.Fatalf("the answering port was not taken: %d", p.dst().Port)
	}
	if p.probe.Load() != nil || getServer() != b.addr.String() {
		t.Fatalf("after the switch: probe %v server %s", p.probe.Load(), getServer())
	}
}

// the old port answers before the new one: the try is dropped and a late answer of the new port changes nothing
func TestHopOldPortRecovers(t *testing.T) {
	key := bytes.Repeat([]byte{0x5a}, 32)
	a, b := newFakeSrv(t, key), newFakeSrv(t, key)
	ps := withPaths(t, a.addr, "wifi")
	p := ps[0]
	SetPorts(itoa(b.addr.Port))
	stall(p, 500*time.Millisecond)
	hopTick(time.Now(), pingNow(time.Now()))
	if p.probe.Load() == nil {
		t.Fatal("no try")
	}
	// the old port answers a ping
	now := time.Now()
	p.ping(now, pingNow(now))
	if p.pong(now.UnixNano(), now.Add(40*time.Millisecond).UnixNano()) < 0 {
		t.Fatal("pong not taken")
	}
	if p.probe.Load() != nil {
		t.Fatal("the try stayed after the old port answered")
	}
	if p.probed(b.addr, time.Now().UnixNano()) {
		t.Fatal("a late answer of the tried port moved the path")
	}
	if p.dst().Port != a.addr.Port {
		t.Fatal("moved")
	}
	// a stalled port with no other port: never tries
	SetPorts("")
	stall(p, time.Second)
	hopTick(time.Now(), pingNow(time.Now()))
	if p.probe.Load() != nil {
		t.Fatal("tried with no other port")
	}
}

// the second network: silent (not even pinged) while the main path is fine; used only on loss above 0.4 %, a
// jitter spike or silence, and silent again at once when the main path is fine
func TestDualOnlyWhenNeeded(t *testing.T) {
	key := bytes.Repeat([]byte{0x5a}, 32)
	a := newFakeSrv(t, key)
	a.on.Store(true)
	ps := withPaths(t, a.addr, "wifi", "cell")
	main, second := ps[0], ps[1]
	now := time.Now()
	for _, p := range ps {
		p.mu.Lock()
		p.samples, p.srtt, p.rttvar, p.lastRTT = 20, 40, 3, 41
		p.lastPong = now.UnixNano()
		p.mu.Unlock()
	}
	srvCaps.Store(uint32(proto.CapMulti | proto.CapCopy | proto.CapRepair))
	gaming.Store(true)
	lastGame.Store(now.UnixNano())

	dualTick(now)
	if dualOn.Load() || altPath(main) != nil {
		t.Fatal("second network used on a clean line")
	}
	pingAll(now, pingNow(now))
	second.mu.Lock()
	sent := second.lastSent
	second.mu.Unlock()
	if sent != 0 {
		t.Fatal("the silent network was pinged")
	}

	lossUp.Store(4) // exactly 0.4 %: not more
	dualTick(now)
	if dualOn.Load() {
		t.Fatal("dual at 0.4 %")
	}
	lossUp.Store(6)
	dualTick(now)
	if !dualOn.Load() || altPath(main) != second {
		t.Fatal("no second network at 0.6 % loss")
	}
	pingAll(now, pingNow(now))
	second.mu.Lock()
	sent = second.lastSent
	second.mu.Unlock()
	if sent == 0 {
		t.Fatal("the second network is not pinged while in use")
	}
	lossUp.Store(1)
	dualTick(now)
	if dualOn.Load() {
		t.Fatal("still dual after the line became clean")
	}

	// loss counts only while a game plays
	lossDown.Store(50)
	lastGame.Store(now.Add(-10 * time.Second).UnixNano())
	dualTick(now)
	if dualOn.Load() {
		t.Fatal("dual for an old loss with no game")
	}
	lossDown.Store(0)

	// a jitter spike
	main.mu.Lock()
	main.lastRTT = 140
	main.mu.Unlock()
	dualTick(now)
	if !dualOn.Load() {
		t.Fatal("no second network on a jitter spike")
	}
	main.mu.Lock()
	main.lastRTT = 42
	main.mu.Unlock()
	dualTick(now)
	if dualOn.Load() {
		t.Fatal("still dual after the spike")
	}

	// one network only: never dual, whatever the loss
	DropSocket("cell")
	lossUp.Store(300)
	lastGame.Store(now.UnixNano())
	dualTick(now)
	if dualOn.Load() {
		t.Fatal("dual with one network")
	}
}

// hello: an answer only to a hello of ours, of this session, once; the hint goes on every socket and is
// dropped when the server goes silent with it
func TestHelloAndHint(t *testing.T) {
	key := bytes.Repeat([]byte{0x5a}, 32)
	a := newFakeSrv(t, key)
	a.on.Store(true)
	ps := withPaths(t, a.addr, "wifi", "cell")
	gaming.Store(true)
	now := time.Now()
	lastPong.Store(now.UnixNano())
	helloTick(now)
	if !waitUntil(t, 2*time.Second, func() bool { return a.hello.Load() != nil }) {
		t.Fatal("no hello reached the server")
	}
	h := a.hello.Load()
	if h.Session != sessionID || h.Caps&proto.HelloCapHint == 0 || h.Caps&proto.HelloCapGaming == 0 || !h.Fresh(time.Now()) {
		t.Fatalf("hello %+v", *h)
	}
	ack := proto.HelloAck{Session: sessionID, Version: proto.HelloVersion, Caps: proto.HelloCapHint | proto.HelloCapGaming,
		SentAt: h.SentAt, Hint: [8]byte{9, 8, 7, 6, 5, 4, 3, 2}, LifeSec: 65535}

	bad := ack
	bad.Session++
	gotHelloAck(proto.AppendHelloAck(nil, &bad), now)
	bad = ack
	bad.SentAt++
	gotHelloAck(proto.AppendHelloAck(nil, &bad), now)
	gotHelloAck(proto.AppendHelloAck(nil, &ack)[:proto.HelloAckLen-1], now)
	if HintActive() || ps[0].pc.Hinted() {
		t.Fatal("hint from a foreign, unknown or short answer")
	}

	gotHelloAck(proto.AppendHelloAck(nil, &ack), now)
	if !HintActive() || !ps[0].pc.Hinted() || !ps[1].pc.Hinted() || !gamingTaken.Load() {
		t.Fatal("hint not set on every socket")
	}
	if hc := curHint(); hc.until.Sub(now) > hintMaxLife {
		t.Fatalf("hint life %v above %v", hc.until.Sub(now), hintMaxLife)
	}
	// the same answer again (replayed): nothing (the hint was dropped meanwhile)
	setHint(nil)
	gotHelloAck(proto.AppendHelloAck(nil, &ack), now)
	if HintActive() {
		t.Fatal("a replayed answer set the hint")
	}

	// the server silent with a hint: dropped, a new hello asked
	helloSoon()
	now = now.Add(time.Second)
	lastPong.Store(now.UnixNano())
	helloTick(now)
	if !waitUntil(t, 2*time.Second, func() bool { return a.hello.Load().SentAt == now.UnixNano() }) {
		t.Fatal("no new hello")
	}
	ack.SentAt = now.UnixNano()
	gotHelloAck(proto.AppendHelloAck(nil, &ack), now)
	if !HintActive() {
		t.Fatal("no hint after a fresh answer")
	}
	lastPong.Store(now.Add(-2 * time.Second).UnixNano())
	helloTick(now)
	if HintActive() || ps[0].pc.Hinted() {
		t.Fatal("hint kept while the server is silent")
	}
}

// an old server never answers a hello: asked again ever more rarely, never more than every 3 s
func TestHelloBackoffOnOldServer(t *testing.T) {
	key := bytes.Repeat([]byte{0x5a}, 32)
	a := newFakeSrv(t, key)
	withPaths(t, a.addr, "wifi")
	now := time.Now()
	lastPong.Store(now.UnixNano())
	sent := 0
	for i := 0; i < 600; i++ { // a minute of ticks
		at := now.Add(time.Duration(i) * tickEvery)
		before := helloTries
		helloTick(at)
		if helloTries != before {
			sent++
		}
	}
	if sent < 2 || sent > 8 {
		t.Fatalf("%d hellos in a minute to a server that never answers", sent)
	}
}

func itoa(n int) string { return string(appendInt(nil, n)) }

func appendInt(b []byte, n int) []byte {
	if n >= 10 {
		b = appendInt(b, n/10)
	}
	return append(b, byte('0'+n%10))
}
