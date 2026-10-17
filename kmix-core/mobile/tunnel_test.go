package mobile

// تست سرتاسری: سرور واقعی + همین هسته، با یک TUN ساختگی (socketpair).
// اجرا (از پوشه‌ی kanki-tunnel):
//
//	go build -o /tmp/ks ../server/cmd/kanki-server   # از پوشه‌ی server
//	SRVBIN=/tmp/ks HOSTIP=<IP غیر loopback این سیستم> go test ./mobile
//
// بدون SRVBIN تست‌ها رد (skip) می‌شوند.

import (
	"bytes"
	"fmt"
	"io"
	"math/rand"
	"net"
	"net/http"
	"os"
	"os/exec"
	"sync"
	"syscall"
	"testing"
	"time"

	"kanki-tunnel/internal/proto"
)

const testKey = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"

type harness struct {
	t      *testing.T
	hostIP net.IP
	echo   *net.UDPAddr
}

// newHarness سرور را روی port بالا می‌آورد و یک UDP echo («سرور بازی») می‌سازد.
func newHarness(t *testing.T, port int) *harness {
	bin, host := os.Getenv("SRVBIN"), os.Getenv("HOSTIP")
	if bin == "" || host == "" {
		t.Skip("SRVBIN و HOSTIP تنظیم نشده‌اند")
	}
	srv := exec.Command(bin, "-listen", fmt.Sprintf("127.0.0.1:%d", port), "-key", testKey)
	srv.Stderr = os.Stderr
	if err := srv.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { srv.Process.Kill(); srv.Wait() })

	hostIP := net.ParseIP(host)
	echo, err := net.ListenUDP("udp", &net.UDPAddr{IP: hostIP})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { echo.Close() })
	go func() {
		b := make([]byte, 2000)
		for {
			n, a, err := echo.ReadFromUDP(b)
			if err != nil {
				return
			}
			echo.WriteToUDP(b[:n], a)
		}
	}()
	time.Sleep(300 * time.Millisecond)
	return &harness{t: t, hostIP: hostIP, echo: echo.LocalAddr().(*net.UDPAddr)}
}

// start تونل را با یک TUN ساختگی روشن می‌کند و طرف دیگر TUN و شمارنده‌ی پاسخ‌ها را برمی‌گرداند.
func (h *harness) start(server string, gamingMode bool) (*os.File, *replies) {
	return h.startWith(server, testKey, gamingMode)
}

func (h *harness) startKey(server, key string) (*os.File, *replies) {
	return h.startWith(server, key, false)
}

func (h *harness) startWith(server, key string, gamingMode bool) (*os.File, *replies) {
	fds, err := syscall.Socketpair(syscall.AF_UNIX, syscall.SOCK_DGRAM, 0)
	if err != nil {
		h.t.Fatal(err)
	}
	peer := os.NewFile(uintptr(fds[1]), "peer")
	for i := 0; ; i++ {
		if err = Start(fds[0], server, key, 10, 3, gamingMode); err == nil {
			break
		}
		if i == 4 {
			h.t.Fatal(err)
		}
	}
	r := &replies{got: map[string]int{}}
	go func() {
		b := make([]byte, 2000)
		for {
			n, err := peer.Read(b)
			if err != nil {
				return
			}
			p, err := ParseUDPPacket(b[:n])
			if err != nil || !p.SrcIP.Equal(h.echo.IP) || p.SrcPort != uint16(h.echo.Port) ||
				!p.DstIP.Equal(net.IPv4(10, 0, 0, 2)) || p.DstPort != 5555 {
				continue
			}
			r.mu.Lock()
			r.got[string(p.Payload)]++
			r.mu.Unlock()
		}
	}()
	h.t.Cleanup(func() { Stop(); peer.Close() })
	return peer, r
}

func (h *harness) send(peer *os.File, payload string) {
	peer.Write(BuildUDPPacket(net.IPv4(10, 0, 0, 2), h.echo.IP, 5555, uint16(h.echo.Port), []byte(payload)))
}

type replies struct {
	mu  sync.Mutex
	got map[string]int
}

func (r *replies) waitFor(n int, d time.Duration) map[string]int {
	deadline := time.Now().Add(d)
	for {
		r.mu.Lock()
		c := len(r.got)
		snap := make(map[string]int, c)
		for k, v := range r.got {
			snap[k] = v
		}
		r.mu.Unlock()
		if c >= n || time.Now().After(deadline) {
			return snap
		}
		time.Sleep(20 * time.Millisecond)
	}
}

func TestTunnel(t *testing.T) {
	h := newHarness(t, 4600)
	peer, r := h.start("127.0.0.1:4600", true)

	// UDP بازی: همه برسند و هیچ‌کدام (با وجود ارسال دوتایی) دو بار نرسند
	for i := 0; i < 50; i++ {
		h.send(peer, fmt.Sprintf("shot-%d", i))
	}
	got := r.waitFor(50, 3*time.Second)
	if len(got) != 50 {
		t.Fatalf("فقط %d پاسخ از ۵۰", len(got))
	}
	time.Sleep(200 * time.Millisecond)
	for k, v := range r.waitFor(50, 0) {
		if v != 1 {
			t.Errorf("%s %d بار رسید", k, v)
		}
	}

	if TxBytes() <= 0 || RxBytes() <= 0 {
		t.Fatalf("شمارنده‌ی ترافیک: tx=%d rx=%d", TxBytes(), RxBytes())
	}
	time.Sleep(1500 * time.Millisecond)
	if GetPing() < 0 {
		t.Fatal("پینگ اندازه‌گیری نشد")
	}

	// TCP روی KCP، با بستن اتصال از سمت مقصد (قبلاً تا ۳۰ ثانیه آویزان می‌ماند)
	const size = 2 << 20
	hl, err := net.Listen("tcp", h.hostIP.String()+":0")
	if err != nil {
		t.Fatal(err)
	}
	hs := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Write(bytes.Repeat([]byte("k"), size))
	})}
	go hs.Serve(hl)
	defer hs.Close()
	m, err := getMux()
	if err != nil {
		t.Fatal(err)
	}
	st, err := openStream(m, proto.CmdTCP, h.hostIP.String(), uint16(hl.Addr().(*net.TCPAddr).Port), 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	st.Write([]byte("GET / HTTP/1.0\r\n\r\n"))
	st.SetReadDeadline(time.Now().Add(10 * time.Second))
	data, _ := io.ReadAll(st)
	if len(data) < size {
		t.Fatalf("TCP: فقط %d بایت", len(data))
	}

	// عوض شدن شبکه: جریان UDP بازی نباید بشکند
	Reconnect()
	r.mu.Lock()
	r.got = map[string]int{}
	r.mu.Unlock()
	for i := 0; i < 50; i++ {
		h.send(peer, fmt.Sprintf("after-%d", i))
	}
	if got := r.waitFor(50, 3*time.Second); len(got) != 50 {
		t.Fatalf("بعد از Reconnect فقط %d پاسخ", len(got))
	}
}

// lossyProxy بین کلاینت و سرور درصدی از بسته‌ها را در هر دو جهت دور می‌ریزد.
func lossyProxy(t *testing.T, listen, target string, loss float64) {
	pc, err := net.ListenPacket("udp", listen)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { pc.Close() })
	ta, _ := net.ResolveUDPAddr("udp", target)
	ups := map[string]*net.UDPConn{}
	go func() {
		b := make([]byte, 65535)
		for {
			n, ca, err := pc.ReadFrom(b)
			if err != nil {
				return
			}
			up, ok := ups[ca.String()]
			if !ok {
				up, _ = net.DialUDP("udp", nil, ta)
				ups[ca.String()] = up
				go func() {
					bb := make([]byte, 65535)
					for {
						m, err := up.Read(bb)
						if err != nil {
							return
						}
						if rand.Float64() >= loss {
							pc.WriteTo(bb[:m], ca)
						}
					}
				}()
			}
			if rand.Float64() >= loss {
				up.Write(b[:n])
			}
		}
	}()
}

func TestGamingUnderLoss(t *testing.T) {
	h := newHarness(t, 4700)
	lossyProxy(t, "127.0.0.1:4701", "127.0.0.1:4700", 0.20)

	rate := map[bool]float64{}
	for _, dup := range []bool{false, true} {
		peer, r := h.start("127.0.0.1:4701", dup)
		const n = 1000
		for i := 0; i < n; i++ {
			h.send(peer, fmt.Sprintf("p%d", i))
			time.Sleep(time.Millisecond)
		}
		rate[dup] = float64(len(r.waitFor(n, 500*time.Millisecond))) / n
		t.Logf("۲۰٪ لاس در هر جهت، گیمینگ=%v: %.1f%% رسید", dup, rate[dup]*100)
		Stop()
	}
	// نظری: بدون گیمینگ ۶۴٪، با گیمینگ ۹۲٪
	if rate[true] < 0.85 || rate[true] <= rate[false] {
		t.Fatalf("ارسال دوتایی اثر نداشت: %v", rate)
	}
}
