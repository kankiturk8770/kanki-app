// Package mobile هسته تونل KANKI MIX برای اندروید (کامپایل با gomobile) — پروتکل ۲.
//
// از کاتلین این‌طوری استفاده می‌شود:
//
//	Mobile.start(tunFd, "server:4000", "کلید hex", 10, 3, gaming)   // در thread پس‌زمینه صدا بزن
//	Mobile.stop()
//	Mobile.getPing()     // پینگ زنده تا سرور (ms)
//	Mobile.reconnect()   // وقتی شبکه‌ی گوشی عوض شد (وای‌فای ↔ دیتا)
//	Mobile.hasTCP()      // آیا این بیلد TCP هم دارد؟
//
// دو مسیر جدا تا سرور:
//   - UDP (بازی، DNS): مسیر سریع دیتاگرام؛ بدون صف، بدون ارسال مجدد، بدون دست‌دادن.
//     در حالت گیمینگ هر بسته دو بار فرستاده می‌شود تا گم شدن یک بسته اثری نداشته باشد.
//   - TCP: روی KCP + smux (قابل اطمینان)؛ بی FEC (TCP خودش دوباره می‌فرستد، FEC فقط پهنای باند هدر می‌داد)،
//     با سرور قدیمی که KCP بی FEC را نمی‌شناسد همان KCP + FEC. حتی اگر اینجا دانلود سنگینی باشد،
//     بسته‌های بازی پشت آن منتظر نمی‌مانند.
package mobile

import (
	"crypto/rand"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/xtaci/kcp-go/v5"
	"github.com/xtaci/smux"

	"kanki-tunnel/internal/game"
	"kanki-tunnel/internal/obfs"
	"kanki-tunnel/internal/proto"
)

const (
	tunMTU       = 1280 // باید با setMtu در KankiVpnService یکی باشد
	kcpMTU       = 1200
	udpIdle      = 90 * time.Second
	pingInterval = time.Second
	deadAfter    = 6 * time.Second // این‌قدر پینگ جواب ندهد = قطع → اتصال مجدد
	// tickEvery pingLoop's beat: a stalled port is seen within stallAfter (hop.go) of the last answer
	tickEvery = 100 * time.Millisecond
)

var (
	tunFile io.ReadWriteCloser

	// connMu از mux و rawConn (مسیر TCP) محافظت می‌کند
	connMu     sync.Mutex
	mux        *smux.Session
	rawConn    net.PacketConn
	muxDialing atomic.Bool

	dg atomic.Pointer[dgramConn] // مسیر سریع UDP

	psk []byte
	// server آدرس سرور («میزبان:پورت»)؛ با عوض شدن پورت (smart.go) پورتش عوض می‌شود
	serverMu sync.Mutex
	server   string
	homePort int
	fecData  int
	fecPty   int
	// kcpMode نوع KCP مسیر TCP: ۰ هنوز معلوم نیست، ۱ بی FEC (سرور تازه)، ۲ با FEC (سرور قدیمی)
	kcpMode   atomic.Int32
	sessionID uint64
	gaming    atomic.Bool

	running int32
	stopCh  chan struct{}

	flowsMu   sync.Mutex
	flows     = make(map[flowKey]*udpFlow)
	flowsByID = make(map[uint32]*udpFlow)
	nextFlow  uint32

	lastPing      atomic.Int64
	lastPong      atomic.Int64 // UnixNano
	lastReconnect atomic.Int64 // UnixNano

	// بایت‌های بسته‌های IP که از TUN خوانده (ارسال) و در آن نوشته شده (دریافت)، برای سرعت و مصرف در اپ
	txBytes atomic.Int64
	rxBytes atomic.Int64
)

type flowKey struct {
	src, dst         [4]byte
	srcPort, dstPort uint16
}

type udpFlow struct {
	id       uint32
	key      flowKey
	srcIP    net.IP
	dstIP    net.IP
	upSeq    atomic.Uint32
	lastSeen atomic.Int64
	mu       sync.Mutex
	win      proto.Window
	s        smartState
}

func getServer() string {
	serverMu.Lock()
	defer serverMu.Unlock()
	return server
}

// setServerPort پورت تازه برای اتصال‌های بعدی (KCP و اتصال دوباره)
func setServerPort(p int) {
	serverMu.Lock()
	defer serverMu.Unlock()
	if h, _, err := net.SplitHostPort(server); err == nil {
		server = net.JoinHostPort(h, strconv.Itoa(p))
	}
}

// Start تونل را راه‌اندازی می‌کند. tunFd فایل‌دیسکریپتور TUN ساخته‌شده توسط VpnService است.
// این تابع تا ۱۰ ثانیه ممکن است بلاک شود؛ از ترد اصلی صدا نزن.
func Start(tunFd int, serverAddr, keyHex string, fecShards, fecParity int, gamingMode bool) error {
	if err := setup(serverAddr, keyHex, fecShards, fecParity, gamingMode); err != nil {
		return err
	}
	tf := os.NewFile(uintptr(tunFd), "tun")
	if tf == nil {
		return errors.New("TUN fd نامعتبر")
	}
	return start(tf)
}

// StartDevice همان Start است برای TUNی که فایل‌دیسکریپتور ندارد (ویندوز: Wintun). هر Read یک بسته‌ی IP
// برمی‌گرداند و هر Write یک بسته می‌نویسد. (gomobile این تابع را برای اندروید رد می‌کند.)
func StartDevice(tf io.ReadWriteCloser, serverAddr, keyHex string, fecShards, fecParity int, gamingMode bool) error {
	if err := setup(serverAddr, keyHex, fecShards, fecParity, gamingMode); err != nil {
		tf.Close()
		return err
	}
	return start(tf)
}

func start(tf io.ReadWriteCloser) error {
	tunFile = tf
	if err := openDgram(); err != nil {
		tf.Close()
		return err
	}

	// اتصال KCP هم کلید را واقعاً امتحان می‌کند (کلید غلط = سرور هیچ جوابی نمی‌دهد)
	connMu.Lock()
	err := connectLocked()
	connMu.Unlock()
	if err != nil {
		closeDgram()
		tf.Close()
		return err
	}

	if err := startTCP(tf, tunMTU); err != nil {
		closeConn()
		closeDgram()
		tf.Close()
		return fmt.Errorf("راه‌اندازی TCP: %w", err)
	}

	tunFile = tf
	later = game.NewLater(4096)
	stop := make(chan struct{})
	stopCh = stop
	lastPong.Store(time.Now().UnixNano())
	atomic.StoreInt32(&running, 1)
	go readTUNLoop(tf)
	go cleanerLoop(stop)
	go pingLoop(stop)
	return nil
}

// setup چک و ذخیره‌ی تنظیمات، مشترک Start و StartDevice
func setup(serverAddr, keyHex string, fecShards, fecParity int, gamingMode bool) error {
	if atomic.LoadInt32(&running) == 1 {
		return errors.New("از قبل روشن است")
	}
	k, err := hex.DecodeString(keyHex)
	if err != nil || len(k) != 32 {
		return errors.New("کلید نامعتبر — باید hex با ۶۴ کاراکتر باشد")
	}
	if fecShards <= 0 || fecParity < 0 {
		return errors.New("تنظیمات FEC نامعتبر")
	}
	serverMu.Lock()
	psk, server, fecData, fecPty = k, serverAddr, fecShards, fecParity
	serverMu.Unlock()
	homePort = 0
	if _, ps, err := net.SplitHostPort(serverAddr); err == nil {
		homePort, _ = strconv.Atoi(ps)
	}
	smartReset()
	helloReset()
	dualOn.Store(false)
	kcpMode.Store(0)
	gaming.Store(gamingMode)
	var sid [8]byte
	rand.Read(sid[:])
	sessionID = binary.BigEndian.Uint64(sid[:])
	lastPing.Store(-1)
	txBytes.Store(0)
	rxBytes.Store(0)

	return nil
}

// Stop تونل را می‌بندد.
func Stop() {
	if atomic.CompareAndSwapInt32(&running, 1, 0) {
		close(stopCh)
		stopTCP()
		closeConn()
		closeDgram()
		closeAllFlows()
		if later != nil {
			later.Stop()
		}
		if tunFile != nil {
			tunFile.Close()
		}
	}
}

// GetPing آخرین پینگ اندازه‌گیری‌شده تا سرور (میلی‌ثانیه). -1 یعنی قطع.
func GetPing() int64 { return lastPing.Load() }

// TxBytes بایت‌های فرستاده‌شده از شروع این اتصال.
func TxBytes() int64 { return txBytes.Load() }

// RxBytes بایت‌های دریافت‌شده از شروع این اتصال.
func RxBytes() int64 { return rxBytes.Load() }

// IsRunning وضعیت اتصال.
func IsRunning() bool { return atomic.LoadInt32(&running) == 1 }

// SetGaming حالت گیمینگ (ارسال دوتایی) را بدون قطع اتصال عوض می‌کند.
func SetGaming(on bool) {
	if gaming.Swap(on) != on {
		helloSoon()
	}
}

// Reconnect سوکت‌ها را از نو می‌سازد؛ وقتی شبکه‌ی گوشی عوض می‌شود صدا بزن.
// جریان‌های UDP بازی حفظ می‌شوند (سرور آدرس جدید را از بسته‌ی بعدی یاد می‌گیرد).
func Reconnect() {
	if atomic.LoadInt32(&running) != 1 {
		return
	}
	lastReconnect.Store(time.Now().UnixNano())
	if err := openDgram(); err != nil {
		logf("kmix: reconnect: %v", err)
	}
	helloSoon()
	// closeConn ممکن است پشت یک اتصال در حال انجام منتظر بماند؛ نباید pingLoop را نگه دارد
	go func() {
		closeConn()
		ensureMux()
	}()
}

// ---- مسیر سریع UDP (سوکت‌ها و مسیرها در paths.go) ----

// handleDown یک بسته‌ی سرور (از هر مسیری که رسید)؛ نسخه‌ی دوم یک بسته یا بسته‌ای که از راه دیگر هم رسید را Window رد می‌کند
func handleDown(tf io.ReadWriteCloser, kind byte, b []byte) {
	if tf == nil {
		return
	}
	// خطای نوشتن (مثلاً بسته‌ی بزرگ‌تر از MTU) فقط همین بسته را از دست می‌دهد
	write := func(f *udpFlow, payload []byte) {
		if n, err := tf.Write(BuildUDPPacket(f.dstIP, f.srcIP, f.key.dstPort, f.key.srcPort, payload)); err == nil {
			rxBytes.Add(int64(n))
		}
	}
	switch kind {
	case proto.KindUDPDown, proto.KindUDPDownCopy:
		id, seq, payload, err := proto.ParseUDPDown(b)
		if err != nil {
			return
		}
		if f := flowByID(id); f != nil {
			f.lastSeen.Store(time.Now().Unix())
			gotDown(f, write, kind, seq, payload, time.Now())
		}
	case proto.KindRepair:
		id, rep, err := proto.ParseRepairDown(b)
		if err != nil {
			return
		}
		if f := flowByID(id); f != nil {
			gotRepair(f, write, rep, time.Now())
		}
	case proto.KindStat:
		gotStat(b)
	}
}

func handleUDPPacket(raw []byte, out []byte) []byte {
	pkt, err := ParseUDPPacket(raw)
	if err != nil {
		return out
	}
	f := getOrCreateFlow(pkt)
	if f == nil {
		return out
	}
	now := time.Now()
	f.lastSeen.Store(now.Unix())
	dc := dg.Load()
	if dc == nil {
		return out
	}
	kind := f.s.cls.Note(len(pkt.Payload), pkt.DstPort, now)
	h := proto.UDPUp{Session: sessionID, Flow: f.id, Seq: f.upSeq.Add(1), DstPort: pkt.DstPort}
	copy(h.DstIP[:], pkt.DstIP.To4())
	if kind == game.KindGame {
		h.Flags |= proto.FlagGame
		// سرور قدیمی (یا تا جواب اولین گزارش): پایین‌رو را خودش دوتایی می‌فرستد، مثل قبل
		if gaming.Load() && !smartOn() {
			h.Flags |= proto.FlagDup
		}
	}
	out = proto.AppendUDPUp(out[:0], &h, pkt.Payload)
	if len(out) > obfs.MaxPayload {
		return out
	}
	sendUp(dc, f, &h, pkt.Payload, out, kind, now)
	return out
}

// getOrCreateFlow هیچ‌وقت منتظر شبکه نمی‌ماند (قبلاً هر جریان جدید، مثلاً یک DNS،
// حلقه‌ی TUN را تا جواب سرور قفل می‌کرد و همه‌ی بسته‌ها، از جمله بازی، معطل می‌شدند).
func getOrCreateFlow(pkt *UDPPacket) *udpFlow {
	var k flowKey
	copy(k.src[:], pkt.SrcIP.To4())
	copy(k.dst[:], pkt.DstIP.To4())
	k.srcPort, k.dstPort = pkt.SrcPort, pkt.DstPort

	flowsMu.Lock()
	defer flowsMu.Unlock()
	if f, ok := flows[k]; ok {
		return f
	}
	if len(flows) >= 1000 {
		return nil
	}
	nextFlow++
	f := &udpFlow{id: nextFlow, key: k, srcIP: pkt.SrcIP, dstIP: pkt.DstIP}
	flows[k] = f
	flowsByID[f.id] = f
	return f
}

func flowByID(id uint32) *udpFlow {
	flowsMu.Lock()
	defer flowsMu.Unlock()
	return flowsByID[id]
}

func closeAllFlows() {
	flowsMu.Lock()
	flows = make(map[flowKey]*udpFlow)
	flowsByID = make(map[uint32]*udpFlow)
	flowsMu.Unlock()
}

func cleanerLoop(stop chan struct{}) {
	t := time.NewTicker(30 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-stop:
			return
		case <-t.C:
			flowsMu.Lock()
			for k, f := range flows {
				if time.Since(time.Unix(f.lastSeen.Load(), 0)) > udpIdle {
					delete(flows, k)
					delete(flowsByID, f.id)
				}
			}
			flowsMu.Unlock()
		}
	}
}

// pingLoop: پینگ هر مسیر (paths.go)، انتخاب بهترین مسیر، و هر ثانیه گزارش افت، پنجره‌ی KCP و عوض کردن پورت؛ اگر هیچ
// مسیری جواب ندهد، خودکار دوباره وصل می‌شود.
func pingLoop(stop chan struct{}) {
	t := time.NewTicker(tickEvery)
	defer t.Stop()
	var b [proto.PingLen]byte
	tick := 0
	for {
		select {
		case <-stop:
			return
		case <-t.C:
		}
		now := time.Now()
		binary.BigEndian.PutUint64(b[0:8], sessionID)
		binary.BigEndian.PutUint64(b[8:16], uint64(now.UnixNano()))
		pingAll(now, b[:])
		pickPrimary(now, false)
		dualTick(now)
		hopTick(now, b[:])
		helloTick(now)
		if tick++; tick%int(time.Second/tickEvery) != 0 {
			if tick%3 == 0 && inFastStart(now) {
				reportDown(dg.Load())
			}
			continue
		}
		dc := dg.Load()
		silent := now.Sub(time.Unix(0, lastPong.Load()))
		statTick(dc, now)
		if silent > deadAfter {
			lastPing.Store(-1)
			if now.Sub(time.Unix(0, lastReconnect.Load())) > deadAfter {
				Reconnect()
			}
		} else if !muxAlive() {
			go ensureMux() // TCP را از قبل آماده نگه دار تا اولین اتصال معطل نشود
		}
	}
}

// ---- مسیر TCP (KCP) ----

func closeConn() {
	connMu.Lock()
	defer connMu.Unlock()
	if mux != nil {
		mux.Close()
		mux = nil
	}
	if rawConn != nil {
		rawConn.Close()
		rawConn = nil
	}
}

// muxAlive هیچ‌وقت منتظر نمی‌ماند؛ اگر الان در حال اتصال باشیم «زنده» حساب می‌شود.
func muxAlive() bool {
	if !connMu.TryLock() {
		return true
	}
	defer connMu.Unlock()
	return mux != nil && !mux.IsClosed()
}

func ensureMux() {
	if !muxDialing.CompareAndSwap(false, true) {
		return
	}
	defer muxDialing.Store(false)
	getMux()
}

// getMux نشست فعلی را برمی‌گرداند؛ اگر قطع شده بود دوباره وصل می‌شود.
func getMux() (*smux.Session, error) {
	connMu.Lock()
	defer connMu.Unlock()
	if mux != nil && !mux.IsClosed() {
		return mux, nil
	}
	if err := connectLocked(); err != nil {
		return nil, err
	}
	return mux, nil
}

func smuxConfig() *smux.Config {
	c := smux.DefaultConfig()
	c.KeepAliveInterval = 3 * time.Second
	c.KeepAliveTimeout = 12 * time.Second
	return c
}

// errNoAnswer سرور به دست دادن جواب نداد (کلید، فایروال یا سرور قدیمی بی KCP ساده)
var errNoAnswer = errors.New("پاسخی از سرور نیامد — آدرس/پورت UDP، فایروال، کلید و نسخه‌ی سرور را بررسی کن")

// plainProbe مهلت جواب KCP بی FEC وقتی هنوز نمی‌دانیم سرور آن را می‌شناسد
const plainProbe = 3 * time.Second

// connectLocked باید با connMu قفل‌شده صدا زده شود.
// اول KCP بی FEC؛ اگر سرور (قدیمی) جواب نداد، همان KCP با FEC و نتیجه تا Start بعدی یادش می‌ماند.
func connectLocked() error {
	if mux != nil {
		mux.Close()
		mux = nil
	}
	if rawConn != nil {
		rawConn.Close()
		rawConn = nil
	}
	switch kcpMode.Load() {
	case 1:
		return dialKCP(true, 10*time.Second)
	case 2:
		return dialKCP(false, 10*time.Second)
	}
	err := dialKCP(true, plainProbe)
	if err == nil {
		kcpMode.Store(1)
		return nil
	}
	if err != errNoAnswer {
		return err
	}
	if err = dialKCP(false, 10*time.Second); err == nil {
		kcpMode.Store(2)
	}
	return err
}

// dialKCP یک اتصال KCP + smux می‌سازد و احراز هویت می‌کند؛ plain = بی FEC (نوع KindKCPPlain)
func dialKCP(plain bool, wait time.Duration) error {
	srv, err := net.ResolveUDPAddr("udp", getServer())
	if err != nil {
		return fmt.Errorf("آدرس سرور نامعتبر: %w", err)
	}
	raw, err := net.ListenPacket("udp", "")
	if err != nil {
		return err
	}
	oc, err := obfs.New(raw, psk, nil)
	if err != nil {
		raw.Close()
		return err
	}
	data, pty := fecData, fecPty
	if plain {
		oc.ClientPlain()
		data, pty = 0, 0
	}
	sess, err := kcp.NewConn2(srv, nil, data, pty, oc)
	if err != nil {
		raw.Close()
		return err
	}
	sess.SetNoDelay(1, 10, 2, 1)
	// وقت بازی پنجره کوچک‌تر است (smart.go)
	w := int(kcpWnd.Load())
	if w <= 0 {
		w = kcpWndFree
	}
	sess.SetWindowSize(w, w)
	sess.SetMtu(kcpMTU)
	sess.SetStreamMode(true)
	sess.SetWriteDelay(false)
	sess.SetACKNoDelay(true)

	m, err := smux.Client(sess, smuxConfig())
	if err != nil {
		sess.Close()
		raw.Close()
		return err
	}
	fail := func(e error) error { m.Close(); raw.Close(); return e }
	st, err := m.OpenStream()
	if err != nil {
		return fail(err)
	}
	if _, err := st.Write(proto.AuthToken(psk)); err != nil {
		return fail(err)
	}
	st.SetReadDeadline(time.Now().Add(wait))
	var ok [1]byte
	if _, err := io.ReadFull(st, ok[:]); err != nil {
		// سرور به بسته‌های نامعتبر جواب نمی‌دهد؛ پس کلید اشتباه هم همین شکل دیده می‌شود
		return fail(errNoAnswer)
	}
	if ok[0] != 0x01 {
		return fail(errors.New("احراز هویت ناموفق"))
	}
	st.Close()
	mux, rawConn = m, raw
	kcpSess.Store(sess)
	return nil
}

// openStream یک استریم جدید روی تونل باز می‌کند، [فرمان][آدرس] را در «یک» نوشتن می‌فرستد
// و منتظر تأیید سرور (۰x۰۱) می‌ماند.
func openStream(m *smux.Session, cmd byte, host string, port uint16, timeout time.Duration) (*smux.Stream, error) {
	st, err := m.OpenStream()
	if err != nil {
		return nil, err
	}
	hdr := []byte{cmd}
	w := &byteWriter{b: hdr}
	if err := proto.WriteAddr(w, host, port); err != nil {
		st.Close()
		return nil, err
	}
	if _, err := st.Write(w.b); err != nil {
		st.Close()
		return nil, err
	}
	st.SetReadDeadline(time.Now().Add(timeout))
	var ok [1]byte
	if _, err := io.ReadFull(st, ok[:]); err != nil || ok[0] != 0x01 {
		st.Close()
		return nil, errors.New("سرور استریم را نپذیرفت")
	}
	st.SetReadDeadline(time.Time{})
	return st, nil
}

type byteWriter struct{ b []byte }

func (w *byteWriter) Write(p []byte) (int, error) { w.b = append(w.b, p...); return len(p), nil }

// ---- خواندن بسته‌ها از TUN ----

func readTUNLoop(tf io.ReadWriteCloser) {
	buf := make([]byte, 65535)
	out := make([]byte, 0, obfs.MaxPayload)
	for atomic.LoadInt32(&running) == 1 {
		n, err := tf.Read(buf)
		if err != nil {
			return
		}
		raw := buf[:n]
		txBytes.Add(int64(n))
		switch ipProtocol(raw) {
		case protoUDP:
			out = handleUDPPacket(raw, out)
		case protoTCP:
			handleTCPPacket(raw) // در بیلد بدون TCP هیچ کاری نمی‌کند
		}
	}
}
