package mobile

// بازی هوشمند (اپ ۱.۴۳، سرور پنل 3.59): هر جریان UDP جدا سنجیده می‌شود (بازی یا نه)، افت خام خط در هر دو جهت
// اندازه گرفته می‌شود و هر طرف به طرف دیگر گزارش می‌دهد (KindStat). از روی همین عدد:
//   - خط تمیز: هیچ چیز اضافه (بسته‌ی بازی یک بار، همان‌طور که هست)
//   - افت: ترمیم پنجره‌ای RLC (internal/game)، به اندازه‌ی افت، چند میلی‌ثانیه بعد از بسته
//   - افت پشت هم: نسخه‌ی دوم هم، ۱۲ میلی‌ثانیه بعد (نه چسبیده به اولی)
// بسته‌ی اصلی هیچ‌وقت صبر نمی‌کند؛ فقط ترمیم و نسخه‌ی دوم دیرتر می‌روند. DNS، QUIC و بسته‌ی بزرگ هیچ‌وقت کپی یا
// ترمیم نمی‌شوند. وقتی بازی جریان دارد، پنجره‌ی KCP (دانلودها) کوچک می‌شود تا صف خط گوشی پر نشود.
// سرور قدیمی KindStat را جواب نمی‌دهد: آن‌وقت همان رفتار قبل (FlagDup)، فقط نسخه‌ی دوم ۱۲ms بعد.

import (
	"net"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/xtaci/kcp-go/v5"

	"kanki-tunnel/internal/game"
	"kanki-tunnel/internal/proto"
)

const (
	// playingFor: بعد از آخرین بسته‌ی بازی این‌قدر «در حال بازی» حساب می‌شود
	playingFor = 3 * time.Second
	// kcpWndPlay / kcpWndFree: پنجره‌ی KCP وقت بازی و بقیه‌ی وقت
	kcpWndPlay = 128
	kcpWndFree = 512
	// hopAfter: این‌قدر بی جواب پینگ = پورت دیگر (خیلی زودتر از deadAfter)
	hopAfter = 2500 * time.Millisecond
	// hopLoss: این‌قدر افت پایین‌رو چند ثانیه پشت هم = این پورت کند شده، پورت دیگر
	hopLoss  = 300
	hopEvery = 4 * time.Second
)

var (
	// srvCaps: توانایی‌های سرور (از KindStat خودش)؛ ۰ = سرور قدیمی یا هنوز معلوم نیست
	srvCaps  atomic.Uint32
	srvKnown atomic.Bool
	upPol    game.Policy
	later    *game.Later
	lastGame atomic.Int64 // UnixNano آخرین بسته‌ی بازی
	kcpSess  atomic.Pointer[kcp.UDPSession]
	kcpWnd   atomic.Int32
	downStep atomic.Int32 // پله‌ی سرور برای پایین‌رو (از گزارش او)

	// آمار برای اپ
	lossUp, lossDown atomic.Int32 // ‰
	fixedDown        atomic.Uint64

	hopMu    sync.Mutex
	hopPorts []int
	hopAt    time.Time
	badLoss  int
)

// smartState بخش‌های تازه‌ی هر جریان (زیر f.mu)
type smartState struct {
	cls      game.Class
	upTx     *game.Tx
	sinceRep int
	downM    game.Meter
	downRx   *game.Rx
}

func smartReset() {
	srvCaps.Store(0)
	srvKnown.Store(false)
	upPol = game.Policy{}
	lastGame.Store(0)
	kcpWnd.Store(kcpWndFree)
	downStep.Store(0)
	lossUp.Store(0)
	lossDown.Store(0)
	fixedDown.Store(0)
	hopMu.Lock()
	hopAt, badLoss = time.Time{}, 0
	hopMu.Unlock()
}

// SetPorts پورت‌های دیگر همین سرور («4000,4001-4010»؛ خالی = ندارد). اگر پورت فعلی کند یا بسته شد،
// اپ بی قطع شدن جلسه به پورت بعدی می‌رود.
func SetPorts(spec string) {
	var l []int
	seen := map[int]bool{}
	add := func(p int) {
		if p > 0 && p <= 65535 && !seen[p] && len(l) < 1000 {
			seen[p] = true
			l = append(l, p)
		}
	}
	for _, it := range strings.Split(spec, ",") {
		it = strings.TrimSpace(it)
		if a, b, ok := strings.Cut(it, "-"); ok {
			x, e1 := strconv.Atoi(strings.TrimSpace(a))
			y, e2 := strconv.Atoi(strings.TrimSpace(b))
			if e1 == nil && e2 == nil && x <= y && y-x < 1000 {
				for p := x; p <= y; p++ {
					add(p)
				}
			}
		} else if x, err := strconv.Atoi(it); err == nil {
			add(x)
		}
	}
	hopMu.Lock()
	hopPorts = l
	hopMu.Unlock()
}

// LossUp / LossDown افت خام خط بالا و پایین (‰، قبل از ترمیم)؛ FixedDown بسته‌های پایین‌روی نجات‌یافته
func LossUp() int32     { return lossUp.Load() }
func LossDown() int32   { return lossDown.Load() }
func FixedDown() uint64 { return fixedDown.Load() }

// smartOn: سرور تازه است و حالت بازی روشن
func smartOn() bool { return srvKnown.Load() && gaming.Load() }

// sendUp یک بسته‌ی بالارو (out = بسته‌ی آماده با سرآیند) با محافظت هوشمند
func sendUp(dc *dgramConn, f *udpFlow, h *proto.UDPUp, payload, out []byte, kind int, now time.Time) {
	dst := dc.dst()
	isGame := kind == game.KindGame && len(payload) <= game.MaxProtect
	if isGame {
		lastGame.Store(now.UnixNano())
	}
	dc.pc.WritePacket(proto.KindUDPUp, out, dst)
	if !gaming.Load() || !isGame {
		return
	}
	if !smartOn() {
		// سرور قدیمی: نسخه‌ی دوم مثل قبل، فقط با فاصله
		out[16] |= proto.FlagCopy
		later.Push(game.CopyDelay, out, func(b []byte) { dc.pc.WritePacket(proto.KindUDPUp, b, dc.dst()) })
		return
	}
	upPol.Start(now)
	pl := upPol.Plan(now)
	if pl.Copy {
		out[16] |= proto.FlagCopy
		later.Push(game.CopyDelay, out, func(b []byte) { dc.pc.WritePacket(proto.KindUDPUp, b, dc.dst()) })
	}
	if srvCaps.Load()&uint32(proto.CapRepair) == 0 {
		return
	}
	f.mu.Lock()
	if f.s.upTx == nil {
		f.s.upTx = new(game.Tx)
	}
	f.s.upTx.Add(h.Seq, payload, now.Add(game.Deadline))
	f.s.sinceRep++
	var rep []byte
	if pl.Every > 0 && f.s.sinceRep >= pl.Every {
		f.s.sinceRep = 0
		if r := f.s.upTx.Repair(now, pl.Win); r != nil {
			rep = proto.AppendRepairUp(nil, sessionID, f.id, r)
		}
	}
	f.mu.Unlock()
	if rep != nil {
		later.Push(game.RepairDelay, rep, func(b []byte) { dc.pc.WritePacket(proto.KindRepair, b, dc.dst()) })
	}
	if pl.Every > 0 {
		// اگر بعد از این بسته‌ای نیامد، یک ترمیم هم برای آخرین‌های این لحظه
		seq, win := h.Seq, pl.Win
		later.Push(game.TailDelay, nil, func([]byte) {
			f.mu.Lock()
			var rep []byte
			if last, ok := f.s.upTx.Last(); ok && last == seq {
				if r := f.s.upTx.Repair(time.Now(), win); r != nil {
					rep = proto.AppendRepairUp(nil, sessionID, f.id, r)
				}
			}
			f.mu.Unlock()
			if rep != nil {
				dc.pc.WritePacket(proto.KindRepair, rep, dc.dst())
			}
		})
	}
}

// gotDown یک بسته‌ی پایین‌رو (اصلی یا نسخه‌ی دوم) یا ترمیم؛ هر بسته‌ی تازه به TUN می‌رود
func gotDown(f *udpFlow, write func(*udpFlow, []byte), kind byte, seq uint32, payload []byte, now time.Time) {
	if kind == proto.KindUDPDown && srvCaps.Load()&uint32(proto.CapCopy) != 0 {
		f.s.downM.Seen(seq) // افت خام: فقط نسخه‌ی اصلی
	}
	if f.s.cls.Note(len(payload), f.key.dstPort, now) == game.KindGame {
		lastGame.Store(now.UnixNano())
	}
	f.mu.Lock()
	fresh := f.win.Accept(seq)
	var rb []game.Rebuilt
	if fresh && smartOn() && f.s.cls.Kind() == game.KindGame && len(payload) <= game.MaxProtect {
		if f.s.downRx == nil {
			f.s.downRx = new(game.Rx)
		}
		rb = keep(f.s.downRx.Data(seq, payload, now))
	}
	if !fresh {
		f.mu.Unlock()
		return
	}
	f.mu.Unlock()
	write(f, payload)
	deliver(f, write, rb)
}

func gotRepair(f *udpFlow, write func(*udpFlow, []byte), rep []byte, now time.Time) {
	f.mu.Lock()
	if f.s.downRx == nil {
		f.s.downRx = new(game.Rx)
	}
	rb := keep(f.s.downRx.Repair(rep, now))
	f.mu.Unlock()
	deliver(f, write, rb)
}

// keep: rebuilt packets live in the decoder until its next call; copy them out under the lock
func keep(l []game.Rebuilt) []game.Rebuilt {
	if len(l) == 0 {
		return nil
	}
	o := make([]game.Rebuilt, len(l))
	for i, r := range l {
		o[i] = game.Rebuilt{Seq: r.Seq, Payload: append([]byte(nil), r.Payload...)}
	}
	return o
}

func deliver(f *udpFlow, write func(*udpFlow, []byte), rb []game.Rebuilt) {
	for _, r := range rb {
		f.mu.Lock()
		fresh := f.win.Accept(r.Seq)
		f.mu.Unlock()
		if fresh {
			fixedDown.Add(1)
			write(f, r.Payload)
		}
	}
}

// gotStat گزارش سرور: این سرور تازه است و افت بالارو را این‌قدر دیده
func gotStat(b []byte) {
	s, err := proto.ParseStat(b)
	if err != nil || s.Session != sessionID {
		return
	}
	srvCaps.Store(uint32(s.Caps))
	srvKnown.Store(true)
	downStep.Store(int32(s.Step))
	lossUp.Store(int32(s.Loss))
	upPol.Report(uint32(s.Loss), uint32(s.Burst), uint32(s.Count), time.Now())
}

// statTick هر ثانیه از pingLoop: گزارش افت پایین‌رو به سرور، پنجره‌ی KCP، عوض کردن پورت
func statTick(dc *dgramConn, now time.Time, silent time.Duration) {
	var sum game.Sum
	flowsMu.Lock()
	for _, f := range flowsByID {
		if f.s.cls.Kind() == game.KindGame {
			sum.Add(f.s.downM.Take())
		}
	}
	flowsMu.Unlock()
	pm, burst, n := sum.Result()
	if n > 0 {
		lossDown.Store(int32(pm))
	}
	if dc != nil {
		st := proto.Stat{Session: sessionID, Version: proto.StatVersion, Caps: proto.CapRepair | proto.CapCopy,
			Loss: uint16(pm), Burst: byte(min(burst, 255)), Step: byte(upPol.Step()), Count: uint16(min(n, 65535))}
		dc.pc.WritePacket(proto.KindStat, proto.AppendStat(nil, &st), dc.dst())
	}
	kcpTick(now)
	hopMu.Lock()
	if n >= 20 && pm >= hopLoss {
		badLoss++
	} else {
		badLoss = 0
	}
	hopMu.Unlock()
	if dc != nil {
		silentHop(dc, now, silent)
	}
}

// setWin: for the tests (nil = the KCP session of the app)
var setWin func(snd, rcv int)

// kcpTick: downloads during play must not fill the queue of the phone's line in front of the game
func kcpTick(now time.Time) {
	want := int32(kcpWndFree)
	if gaming.Load() && now.UnixNano()-lastGame.Load() < int64(playingFor) {
		want = kcpWndPlay
	}
	if kcpWnd.Swap(want) == want {
		return
	}
	if setWin != nil {
		setWin(int(want), int(want))
	} else if k := kcpSess.Load(); k != nil {
		k.SetWindowSize(int(want), int(want))
	}
}

// silentHop: the port gave no answer for a while, or the line on it is badly cut: the next port, with
// the same session (much sooner than a reconnect)
func silentHop(dc *dgramConn, now time.Time, silent time.Duration) {
	hopMu.Lock()
	defer hopMu.Unlock()
	if len(hopPorts) == 0 || now.Sub(hopAt) < hopEvery {
		return
	}
	if silent >= hopAfter || badLoss >= 3 {
		hopAt, badLoss = now, 0
		dc.hop(hopPorts)
	}
}

// dst آدرس فعلی سرور مسیر بازی
func (dc *dgramConn) dst() *net.UDPAddr { return dc.srv.Load() }

// hop پورت بعدی (پورت اصلی هم جزو دور است)؛ جلسه، جریان‌ها و کلید همان می‌مانند
func (dc *dgramConn) hop(ports []int) {
	cur := dc.dst()
	all := []int{dc.home}
	for _, p := range ports {
		if p != dc.home {
			all = append(all, p)
		}
	}
	next := all[0]
	for i, p := range all {
		if p == cur.Port {
			next = all[(i+1)%len(all)]
			break
		}
	}
	if next == cur.Port {
		return
	}
	a := *cur
	a.Port = next
	dc.srv.Store(&a)
	setServerPort(next)
}
