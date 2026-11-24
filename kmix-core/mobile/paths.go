package mobile

// چند مسیر (اپ ۱.۴۴، سرور پنل 3.60): هر شبکه‌ی گوشی (وای‌فای، دیتا) سوکت UDP خودش را دارد و هر نشانی سرور (خود سرور،
// ورودی تانل ایران) یک مقصد است؛ هر جفت «شبکه × نشانی» یک مسیر. هر مسیر پینگ خودش را دارد (رفت‌وبرگشت، لرزش، افت).
//
//   - بسته‌ی اصلی فقط روی بهترین مسیر می‌رود (امتیاز = پینگ + ۳×لرزش + ۲۰×درصد افت)؛ هیچ بسته‌ای بی‌دلیل دو بار نمی‌رود.
//   - مسیر بهتر فقط وقتی جای قبلی را می‌گیرد که ۳ ثانیه پشت هم دست‌کم ۱۵٪ بهتر باشد (برای یک لحظه‌ی بد عوض نمی‌شود)؛
//     ولی مسیری که جواب نمی‌دهد فوراً کنار می‌رود.
//   - همان نسخه‌ی دوم و ترمیمی که خط با افتش لازم دارد (smart.go) روی مسیر دیگر می‌رود، بی صبر: افت یک مسیر به مسیر
//     دیگر نمی‌رسد، پس همان مقدار بسته بیشتر نجات می‌دهد.
//   - وقتی مسیر اصلی همین الان جواب نمی‌دهد (چند برابر پینگش گذشته)، بسته‌ی بازی تا معلوم شدن روی مسیر دیگر هم می‌رود
//     (FlagMirror) و سرور هم جواب‌ها را همان‌جا می‌فرستد؛ بعد یکی می‌ماند.
//
// سرور قدیمی (بی CapMulti): فقط بهترین مسیر، بی نسخه یا ترمیم روی مسیر دیگر (مثل عوض شدن شبکه).

import (
	"errors"
	"net"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"kanki-tunnel/internal/obfs"
	"kanki-tunnel/internal/proto"
)

const (
	pingIdle   = time.Second            // پینگ هر مسیر وقتی بازی نیست
	pingPlay   = 250 * time.Millisecond // وقت بازی (۱۶ بایت؛ چند بایت در ثانیه)
	pathDead   = 2500 * time.Millisecond
	switchHold = 3 * time.Second
	mirrorHold = time.Second
	pingRing   = 16
	maxSocks   = 3
	maxEnds    = 3
)

// netSock یک سوکت روی یک شبکه‌ی گوشی ("" = شبکه‌ی پیش‌فرض، وقتی اپ سوکت شبکه‌ها را نداده)
type netSock struct {
	name  string
	raw   net.PacketConn
	pc    *obfs.PacketConn
	paths []*dgramConn
}

type dgramConn struct {
	raw  net.PacketConn
	pc   *obfs.PacketConn
	ns   *netSock
	ep   int // ۰ = خود سرور (با عوض کردن پورت)، بقیه = نشانی‌های دیگر (تانل ایران)
	srv  atomic.Pointer[net.UDPAddr]
	home int // پورت اصلی سرور (دور عوض کردن پورت از آن شروع می‌شود)

	mu       sync.Mutex
	srtt     float64 // ms
	rttvar   float64
	samples  int
	sent     [pingRing]int64 // UnixNano پینگ‌های اخیر
	got      [pingRing]bool
	next     int
	lastPong int64
	waitFrom int64 // اولین پینگی که بعد از آخرین جواب رفت (۰ = منتظر چیزی نیست)
	lastSent int64
	born     int64
}

var (
	pathsMu  sync.Mutex
	socks    []*netSock
	ends     []string // نشانی‌های دیگر سرور («میزبان:پورت»)؛ نشانی اصلی getServer است
	switchAt atomic.Int64
	better   time.Time
)

// SetAlt نشانی‌های دیگر همین سرور («میزبان:پورت»، با ویرگول؛ مثلاً ورودی تانل ایران). قبل از Start صدا بزن.
func SetAlt(spec string) {
	var l []string
	for _, it := range strings.Split(spec, ",") {
		it = strings.TrimSpace(it)
		if it == "" || len(l) >= maxEnds-1 {
			continue
		}
		if _, err := net.ResolveUDPAddr("udp", it); err == nil {
			l = append(l, it)
		}
	}
	pathsMu.Lock()
	ends = l
	pathsMu.Unlock()
}

// AddSocket یک سوکت UDP که اپ روی یک شبکه‌ی گوشی ساخته (بیرون از VPN، بسته به همان شبکه) با اسم آن شبکه
// («wifi»، «cell»). سوکت قبلی همان اسم بسته می‌شود. تا وقتی دست‌کم یک سوکت شبکه هست، سوکت پیش‌فرض کنار می‌رود.
func AddSocket(name string, fd int) error {
	f := os.NewFile(uintptr(fd), "kmix-"+name)
	if f == nil {
		return errors.New("bad fd")
	}
	raw, err := net.FilePacketConn(f)
	f.Close()
	if err != nil {
		return err
	}
	if atomic.LoadInt32(&running) != 1 {
		raw.Close()
		return errors.New("not running")
	}
	ns, err := newSock(name, raw)
	if err != nil {
		raw.Close()
		return err
	}
	pathsMu.Lock()
	var gone []*netSock
	keep := socks[:0:0]
	for _, s := range socks {
		if s.name == name || s.name == "" {
			gone = append(gone, s)
		} else {
			keep = append(keep, s)
		}
	}
	if len(keep) >= maxSocks {
		pathsMu.Unlock()
		ns.raw.Close()
		return errors.New("too many sockets")
	}
	socks = append(keep, ns)
	pathsMu.Unlock()
	go sockReadLoop(ns)
	for _, s := range gone {
		s.raw.Close()
	}
	pickPrimary(time.Now(), true)
	return nil
}

// DropSocket شبکه‌ی name رفت؛ اگر هیچ سوکت شبکه‌ای نماند، سوکت پیش‌فرض دوباره ساخته می‌شود.
func DropSocket(name string) {
	pathsMu.Lock()
	var gone *netSock
	keep := socks[:0:0]
	for _, s := range socks {
		if s.name == name && name != "" {
			gone = s
		} else {
			keep = append(keep, s)
		}
	}
	socks = keep
	empty := len(keep) == 0
	pathsMu.Unlock()
	if gone == nil {
		return
	}
	gone.raw.Close()
	if empty && atomic.LoadInt32(&running) == 1 {
		openDgram()
	} else {
		pickPrimary(time.Now(), true)
	}
}

// newSock یک سوکت و مسیرهایش (یکی برای هر نشانی سرور)
func newSock(name string, raw net.PacketConn) (*netSock, error) {
	srv, err := net.ResolveUDPAddr("udp", getServer())
	if err != nil {
		return nil, err
	}
	pc, err := obfs.New(raw, psk, nil)
	if err != nil {
		return nil, err
	}
	ns := &netSock{name: name, raw: raw, pc: pc}
	now := time.Now().UnixNano()
	home := homePort
	if home == 0 {
		home = srv.Port
	}
	mk := func(ep int, a *net.UDPAddr) {
		p := &dgramConn{raw: raw, pc: pc, ns: ns, ep: ep, home: home, born: now}
		p.srv.Store(a)
		ns.paths = append(ns.paths, p)
	}
	mk(0, srv)
	pathsMu.Lock()
	alt := append([]string(nil), ends...)
	pathsMu.Unlock()
	for i, e := range alt {
		if a, err := net.ResolveUDPAddr("udp", e); err == nil {
			mk(i+1, a)
		}
	}
	return ns, nil
}

// openDgram سوکت پیش‌فرض (اولین بار، یا وقتی شبکه‌ی گوشی عوض شد و اپ سوکت شبکه‌ها را نمی‌دهد)
func openDgram() error {
	pathsMu.Lock()
	named := false
	for _, s := range socks {
		if s.name != "" {
			named = true
		}
	}
	pathsMu.Unlock()
	if named {
		return nil // اپ شبکه‌ها را خودش می‌دهد (AddSocket)
	}
	raw, err := net.ListenPacket("udp", "")
	if err != nil {
		return err
	}
	ns, err := newSock("", raw)
	if err != nil {
		raw.Close()
		return err
	}
	pathsMu.Lock()
	var gone []*netSock
	keep := socks[:0:0]
	for _, s := range socks {
		if s.name == "" {
			gone = append(gone, s)
		} else {
			keep = append(keep, s)
		}
	}
	socks = append(keep, ns)
	pathsMu.Unlock()
	go sockReadLoop(ns)
	for _, s := range gone {
		s.raw.Close()
	}
	// سوکت تازه تا اولین جواب پینگ امتیازی ندارد؛ تا آن موقع همین مسیر اصلی است
	dg.Store(ns.paths[0])
	switchAt.Store(time.Now().UnixNano())
	return nil
}

func closeDgram() {
	pathsMu.Lock()
	l := socks
	socks = nil
	pathsMu.Unlock()
	for _, s := range l {
		s.raw.Close()
	}
	dg.Store(nil)
}

// allPaths همه‌ی مسیرهای زنده (کپی)
func allPaths() []*dgramConn {
	pathsMu.Lock()
	defer pathsMu.Unlock()
	var l []*dgramConn
	for _, s := range socks {
		l = append(l, s.paths...)
	}
	return l
}

// sockReadLoop یک سوکت؛ جواب پینگ به مسیر خودش (از روی نشانی فرستنده) می‌رسد، بقیه مثل یک مسیر
func sockReadLoop(ns *netSock) {
	buf := make([]byte, 65535)
	for {
		kind, n, from, err := ns.pc.ReadPacket(buf)
		if err != nil {
			return // سوکت بسته شد
		}
		if kind == proto.KindPong {
			if n == proto.PingLen {
				gotPong(ns, from, buf[:n])
			}
			continue
		}
		handleDown(tunFile, kind, buf[:n])
	}
}

func gotPong(ns *netSock, from net.Addr, b []byte) {
	if beUint64(b[0:8]) != sessionID {
		return
	}
	ua, _ := from.(*net.UDPAddr)
	sent := int64(beUint64(b[8:16]))
	now := time.Now().UnixNano()
	for _, p := range ns.paths {
		if a := p.srv.Load(); ua != nil && a != nil && a.Port == ua.Port && a.IP.Equal(ua.IP) {
			if rtt := p.pong(sent, now); rtt >= 0 {
				lastPong.Store(now)
				if dg.Load() == p {
					lastPing.Store(rtt)
				}
			}
			return
		}
	}
}

func beUint64(b []byte) uint64 {
	_ = b[7]
	return uint64(b[0])<<56 | uint64(b[1])<<48 | uint64(b[2])<<40 | uint64(b[3])<<32 | uint64(b[4])<<24 | uint64(b[5])<<16 | uint64(b[6])<<8 | uint64(b[7])
}

// ping: یک پینگ روی این مسیر (زمان را نگه می‌دارد)
func (p *dgramConn) ping(now time.Time, b []byte) {
	t := now.UnixNano()
	p.mu.Lock()
	p.sent[p.next] = t
	p.got[p.next] = false
	p.next = (p.next + 1) % pingRing
	p.lastSent = t
	if p.waitFrom == 0 {
		p.waitFrom = t
	}
	p.mu.Unlock()
	p.pc.WritePacket(proto.KindPing, b, p.dst())
}

// pong: جواب پینگی که at رفته بود؛ رفت‌وبرگشت (ms) یا -1 اگر مال این مسیر نبود
func (p *dgramConn) pong(at, now int64) int64 {
	p.mu.Lock()
	defer p.mu.Unlock()
	for i := range p.sent {
		if p.sent[i] == at && !p.got[i] {
			p.got[i] = true
			rtt := float64(now-at) / 1e6
			if rtt < 0 || rtt > 10000 {
				return -1
			}
			if p.samples == 0 {
				p.srtt, p.rttvar = rtt, rtt/2
			} else {
				d := rtt - p.srtt
				if d < 0 {
					d = -d
				}
				p.rttvar += (d - p.rttvar) / 4
				p.srtt += (rtt - p.srtt) / 8
			}
			p.samples++
			p.lastPong = now
			p.waitFrom = 0
			return int64(rtt)
		}
	}
	return -1
}

// quality: امتیاز مسیر (ms؛ کمتر بهتر)، جواب نمی‌دهد (suspect)، مرده
func (p *dgramConn) quality(now int64) (score float64, suspect, dead bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.samples == 0 {
		return 1e9, true, now-p.born > int64(pathDead)
	}
	wait := 3*p.srtt + 4*p.rttvar
	if wait < 250 {
		wait = 250
	}
	suspect = p.waitFrom != 0 && float64(now-p.waitFrom)/1e6 > wait
	dead = now-p.lastPong > int64(pathDead)
	// افت پینگ‌هایی که دیگر باید جوابشان آمده باشد
	var n, lost int
	for i := range p.sent {
		if p.sent[i] == 0 || float64(now-p.sent[i])/1e6 < wait {
			continue
		}
		n++
		if !p.got[i] {
			lost++
		}
	}
	loss := 0.0
	if n > 0 {
		loss = 100 * float64(lost) / float64(n)
	}
	score = p.srtt + 3*p.rttvar + 20*loss
	if suspect {
		score += 1000
	}
	return
}

// multiOK: سرور چند مسیر را می‌شناسد (نسخه‌ی دوم و ترمیم روی مسیر دیگر)
func multiOK() bool { return srvCaps.Load()&uint32(proto.CapMulti) != 0 }

// pickPrimary بهترین مسیر را مسیر اصلی می‌کند (force = همین الان، بی صبر ۳ ثانیه)
func pickPrimary(t time.Time, force bool) {
	now := t.UnixNano()
	cur := dg.Load()
	var best *dgramConn
	bestS := 0.0
	curS, curSus, curDead := 1e18, true, true
	alive := false
	for _, p := range allPaths() {
		s, sus, dead := p.quality(now)
		if p == cur {
			curS, curSus, curDead = s, sus, dead
			alive = true
		}
		if dead || sus {
			continue
		}
		if best == nil || s < bestS {
			best, bestS = p, s
		}
	}
	if !alive {
		cur = nil
	}
	if best == nil {
		if cur == nil {
			// هیچ مسیری هنوز جواب نداده: اولی
			if l := allPaths(); len(l) > 0 {
				dg.Store(l[0])
			}
		}
		better = time.Time{}
		return
	}
	switch {
	case cur == nil || curDead || curSus:
		if best != cur {
			setPrimary(best, now)
		}
		better = time.Time{}
	case best != cur && bestS < curS*0.85-3:
		if force {
			setPrimary(best, now)
			better = time.Time{}
		} else if better.IsZero() {
			better = t
		} else if t.Sub(better) >= switchHold {
			setPrimary(best, now)
			better = time.Time{}
		}
	default:
		better = time.Time{}
	}
}

func setPrimary(p *dgramConn, now int64) {
	dg.Store(p)
	switchAt.Store(now)
	if p.ep == 0 {
		if a := p.dst(); a != nil {
			setServerPort(a.Port)
		}
	}
}

// altPath: بهترین مسیر زنده‌ی دیگر (nil = نیست، یا سرور چند مسیر را نمی‌شناسد)
func altPath(cur *dgramConn) *dgramConn {
	if cur == nil || !multiOK() {
		return nil
	}
	now := time.Now().UnixNano()
	var best *dgramConn
	bestS := 0.0
	for _, p := range allPaths() {
		if p == cur {
			continue
		}
		s, sus, dead := p.quality(now)
		if dead || sus {
			continue
		}
		// مسیر روی شبکه‌ی دیگر بهتر است (افت یک شبکه به دیگری نمی‌رسد)
		if p.ns != cur.ns {
			s -= 50
		}
		if best == nil || s < bestS {
			best, bestS = p, s
		}
	}
	return best
}

// mirroring: مسیر اصلی همین الان جواب نمی‌دهد یا تازه عوض شده؛ بسته‌ی بازی روی مسیر دیگر هم برود
func mirroring(cur *dgramConn, now int64) bool {
	if cur == nil {
		return false
	}
	if now-switchAt.Load() < int64(mirrorHold) {
		return true
	}
	_, sus, _ := cur.quality(now)
	return sus
}

// pingAll پینگ هر مسیری که وقتش رسیده؛ وقت بازی یا وقتی مسیر جواب نمی‌دهد تندتر
func pingAll(now time.Time, b []byte) {
	t := now.UnixNano()
	fast := gaming.Load() && t-lastGame.Load() < int64(playingFor)
	for _, p := range allPaths() {
		p.mu.Lock()
		every := pingIdle
		if fast || p.waitFrom != 0 || p.samples == 0 {
			every = pingPlay
		}
		due := t-p.lastSent >= int64(every)
		p.mu.Unlock()
		if due {
			p.ping(now, b)
		}
	}
}

// PathInfo برای اپ: اسم مسیر اصلی («wifi»، «cell» یا «» برای پیش‌فرض، با «+tunnel» اگر از تانل است) و تعداد مسیرهای زنده
func PathInfo() (string, int) {
	cur := dg.Load()
	name := ""
	if cur != nil {
		name = cur.ns.name
		if name == "" {
			name = "net"
		}
		if cur.ep > 0 {
			name += "+tunnel"
		}
	}
	n := 0
	now := time.Now().UnixNano()
	for _, p := range allPaths() {
		if _, _, dead := p.quality(now); !dead {
			if p.samples > 0 {
				n++
			}
		}
	}
	return name, n
}
