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
//   - TCP: روی KCP + FEC + smux (قابل اطمینان). حتی اگر اینجا دانلود سنگینی باشد،
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
	"sync"
	"sync/atomic"
	"time"

	"github.com/xtaci/kcp-go/v5"
	"github.com/xtaci/smux"

	"kanki-tunnel/internal/obfs"
	"kanki-tunnel/internal/proto"
)

const (
	tunMTU       = 1280 // باید با setMtu در KankiVpnService یکی باشد
	kcpMTU       = 1200
	udpIdle      = 90 * time.Second
	pingInterval = time.Second
	deadAfter    = 6 * time.Second // این‌قدر پینگ جواب ندهد = قطع → اتصال مجدد
)

var (
	tunFile *os.File

	// connMu از mux و rawConn (مسیر TCP) محافظت می‌کند
	connMu     sync.Mutex
	mux        *smux.Session
	rawConn    net.PacketConn
	muxDialing atomic.Bool

	dg atomic.Pointer[dgramConn] // مسیر سریع UDP

	psk       []byte
	server    string
	fecData   int
	fecPty    int
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
}

type dgramConn struct {
	raw net.PacketConn
	pc  *obfs.PacketConn
	srv *net.UDPAddr
}

// Start تونل را راه‌اندازی می‌کند. tunFd فایل‌دیسکریپتور TUN ساخته‌شده توسط VpnService است.
// این تابع تا ۱۰ ثانیه ممکن است بلاک شود؛ از ترد اصلی صدا نزن.
func Start(tunFd int, serverAddr, keyHex string, fecShards, fecParity int, gamingMode bool) error {
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
	psk, server, fecData, fecPty = k, serverAddr, fecShards, fecParity
	gaming.Store(gamingMode)
	var sid [8]byte
	rand.Read(sid[:])
	sessionID = binary.BigEndian.Uint64(sid[:])
	lastPing.Store(-1)
	txBytes.Store(0)
	rxBytes.Store(0)

	tf := os.NewFile(uintptr(tunFd), "tun")
	if tf == nil {
		return errors.New("TUN fd نامعتبر")
	}
	if err := openDgram(tf); err != nil {
		tf.Close()
		return err
	}

	// اتصال KCP هم کلید را واقعاً امتحان می‌کند (کلید غلط = سرور هیچ جوابی نمی‌دهد)
	connMu.Lock()
	err = connectLocked()
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
	stop := make(chan struct{})
	stopCh = stop
	lastPong.Store(time.Now().UnixNano())
	atomic.StoreInt32(&running, 1)
	go readTUNLoop(tf)
	go cleanerLoop(stop)
	go pingLoop(stop)
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
func SetGaming(on bool) { gaming.Store(on) }

// Reconnect سوکت‌ها را از نو می‌سازد؛ وقتی شبکه‌ی گوشی عوض می‌شود صدا بزن.
// جریان‌های UDP بازی حفظ می‌شوند (سرور آدرس جدید را از بسته‌ی بعدی یاد می‌گیرد).
func Reconnect() {
	if atomic.LoadInt32(&running) != 1 {
		return
	}
	lastReconnect.Store(time.Now().UnixNano())
	if tf := tunFile; tf != nil {
		openDgram(tf)
	}
	// closeConn ممکن است پشت یک اتصال در حال انجام منتظر بماند؛ نباید pingLoop را نگه دارد
	go func() {
		closeConn()
		ensureMux()
	}()
}

// ---- مسیر سریع UDP ----

func openDgram(tf *os.File) error {
	srv, err := net.ResolveUDPAddr("udp", server)
	if err != nil {
		return fmt.Errorf("آدرس سرور نامعتبر: %w", err)
	}
	raw, err := net.ListenPacket("udp", "")
	if err != nil {
		return err
	}
	pc, err := obfs.New(raw, psk, nil)
	if err != nil {
		raw.Close()
		return err
	}
	dc := &dgramConn{raw: raw, pc: pc, srv: srv}
	if old := dg.Swap(dc); old != nil {
		old.raw.Close()
	}
	go dgramReadLoop(dc, tf)
	return nil
}

func closeDgram() {
	if old := dg.Swap(nil); old != nil {
		old.raw.Close()
	}
}

func dgramReadLoop(dc *dgramConn, tf *os.File) {
	buf := make([]byte, 65535)
	for {
		kind, n, _, err := dc.pc.ReadPacket(buf)
		if err != nil {
			return // سوکت بسته شد (Stop یا Reconnect)
		}
		switch kind {
		case proto.KindPong:
			if n == proto.PingLen && binary.BigEndian.Uint64(buf[0:8]) == sessionID {
				sent := int64(binary.BigEndian.Uint64(buf[8:16]))
				now := time.Now().UnixNano()
				if rtt := now - sent; rtt >= 0 && rtt < int64(10*time.Second) {
					lastPing.Store(rtt / int64(time.Millisecond))
					lastPong.Store(now)
				}
			}
		case proto.KindUDPDown:
			id, seq, payload, err := proto.ParseUDPDown(buf[:n])
			if err != nil {
				continue
			}
			flowsMu.Lock()
			f := flowsByID[id]
			flowsMu.Unlock()
			if f == nil {
				continue
			}
			f.mu.Lock()
			fresh := f.win.Accept(seq)
			f.mu.Unlock()
			if !fresh {
				continue // نسخه‌ی دوم یک بسته‌ی تکراری (حالت گیمینگ)
			}
			f.lastSeen.Store(time.Now().Unix())
			// خطای نوشتن (مثلاً بسته‌ی بزرگ‌تر از MTU) فقط همین بسته را از دست می‌دهد
			if n, err := tf.Write(BuildUDPPacket(f.dstIP, f.srcIP, f.key.dstPort, f.key.srcPort, payload)); err == nil {
				rxBytes.Add(int64(n))
			}
		}
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
	f.lastSeen.Store(time.Now().Unix())
	dc := dg.Load()
	if dc == nil {
		return out
	}
	h := proto.UDPUp{Session: sessionID, Flow: f.id, Seq: f.upSeq.Add(1), DstPort: pkt.DstPort}
	copy(h.DstIP[:], pkt.DstIP.To4())
	dup := gaming.Load()
	if dup {
		h.Flags |= proto.FlagDup
	}
	out = proto.AppendUDPUp(out[:0], &h, pkt.Payload)
	if len(out) > obfs.MaxPayload {
		return out
	}
	dc.pc.WritePacket(proto.KindUDPUp, out, dc.srv)
	if dup {
		dc.pc.WritePacket(proto.KindUDPUp, out, dc.srv)
	}
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

// pingLoop هر ثانیه از همان مسیر بسته‌های بازی پینگ می‌گیرد (پس عدد پینگ همان تأخیر
// واقعی بسته‌های بازی تا سرور است) و اگر سرور جواب ندهد، خودکار دوباره وصل می‌شود.
func pingLoop(stop chan struct{}) {
	t := time.NewTicker(pingInterval)
	defer t.Stop()
	var b [proto.PingLen]byte
	for {
		select {
		case <-stop:
			return
		case <-t.C:
		}
		now := time.Now()
		if dc := dg.Load(); dc != nil {
			binary.BigEndian.PutUint64(b[0:8], sessionID)
			binary.BigEndian.PutUint64(b[8:16], uint64(now.UnixNano()))
			dc.pc.WritePacket(proto.KindPing, b[:], dc.srv)
		}
		silent := now.Sub(time.Unix(0, lastPong.Load()))
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

// connectLocked باید با connMu قفل‌شده صدا زده شود.
func connectLocked() error {
	if mux != nil {
		mux.Close()
		mux = nil
	}
	if rawConn != nil {
		rawConn.Close()
		rawConn = nil
	}

	srv, err := net.ResolveUDPAddr("udp", server)
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
	sess, err := kcp.NewConn2(srv, nil, fecData, fecPty, oc)
	if err != nil {
		raw.Close()
		return err
	}
	sess.SetNoDelay(1, 10, 2, 1)
	sess.SetWindowSize(512, 512)
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
	st.SetReadDeadline(time.Now().Add(10 * time.Second))
	var ok [1]byte
	if _, err := io.ReadFull(st, ok[:]); err != nil {
		// سرور به بسته‌های نامعتبر جواب نمی‌دهد؛ پس کلید اشتباه هم همین شکل دیده می‌شود
		return fail(errors.New("پاسخی از سرور نیامد — آدرس/پورت UDP، فایروال، کلید و نسخه‌ی سرور را بررسی کن"))
	}
	if ok[0] != 0x01 {
		return fail(errors.New("احراز هویت ناموفق"))
	}
	st.Close()
	mux, rawConn = m, raw
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

func readTUNLoop(tf *os.File) {
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
