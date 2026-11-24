package mobile

// آزمایشگاه بازی هوشمند: سرور واقعی (SRVBIN) + همین هسته، خط ساختگی با افت پراکنده و رگباری در هر دو جهت،
// «بازی» با ۶۰ بسته در ثانیه که هر بسته را سرور بازی (echo) برمی‌گرداند. معیار: رفت‌وبرگشت به‌موقع (≤ ۱۵۰ms
// بیشتر از تأخیر خط) و حجم روی سیم نسبت به خود بازی. با SRVOLD همان آزمایش روی سرور قبلی هم اجرا می‌شود.
//
//	SRVBIN=/tmp/ks-new SRVOLD=/tmp/ks-old HOSTIP=<IP> go test -run SmartLab -v ./mobile

import (
	"fmt"
	"math/rand"
	"net"
	"os"
	"os/exec"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// lineModel: Gilbert-Elliott — a good state with light random loss and a bad state (bursts) with heavy loss
type lineModel struct {
	name          string
	pGood, pBad   float64 // loss in each state
	toBad, toGood float64 // chance to switch, per packet
	delay         time.Duration
}

type lineDir struct {
	m   lineModel
	bad bool
	rng *rand.Rand
	mu  sync.Mutex
}

func (d *lineDir) drop() bool {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.bad {
		if d.rng.Float64() < d.m.toGood {
			d.bad = false
		}
	} else if d.rng.Float64() < d.m.toBad {
		d.bad = true
	}
	p := d.m.pGood
	if d.bad {
		p = d.m.pBad
	}
	return d.rng.Float64() < p
}

// labLine: a UDP proxy client ↔ server with the model in each direction, delay, and the bytes it carried
type labLine struct {
	wire atomic.Int64
}

func newLabLine(t *testing.T, listen, target string, m lineModel, seed int64) *labLine {
	l := &labLine{}
	pc, err := net.ListenPacket("udp", listen)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { pc.Close() })
	ta, _ := net.ResolveUDPAddr("udp", target)
	up := &lineDir{m: m, rng: rand.New(rand.NewSource(seed))}
	down := &lineDir{m: m, rng: rand.New(rand.NewSource(seed + 1))}
	var mu sync.Mutex
	ups := map[string]*net.UDPConn{}
	go func() {
		b := make([]byte, 65535)
		for {
			n, ca, err := pc.ReadFrom(b)
			if err != nil {
				return
			}
			mu.Lock()
			c, ok := ups[ca.String()]
			if !ok {
				c, _ = net.DialUDP("udp", nil, ta)
				ups[ca.String()] = c
				go func(c *net.UDPConn, ca net.Addr) {
					bb := make([]byte, 65535)
					for {
						m, err := c.Read(bb)
						if err != nil {
							return
						}
						l.wire.Add(int64(m))
						if !down.drop() {
							p := append([]byte(nil), bb[:m]...)
							time.AfterFunc(m0(down.m.delay), func() { pc.WriteTo(p, ca) })
						}
					}
				}(c, ca)
			}
			mu.Unlock()
			l.wire.Add(int64(n))
			if !up.drop() {
				p := append([]byte(nil), b[:n]...)
				time.AfterFunc(m0(up.m.delay), func() { c.Write(p) })
			}
		}
	}()
	return l
}

func m0(d time.Duration) time.Duration { return d }

// labRun: 60 game packets a second for dur; returns the share answered on time and the bytes on the wire
// per byte of game
func labRun(t *testing.T, h *harness, server string, line *labLine, dur time.Duration) (onTime float64, cost float64) {
	peer, r := h.start(server, true)
	sent := map[string]time.Time{}
	var mu sync.Mutex
	r.mu.Lock()
	r.got = map[string]int{}
	r.mu.Unlock()
	// answers with their arrival time
	at := map[string]time.Time{}
	stop := make(chan struct{})
	go func() {
		for {
			select {
			case <-stop:
				return
			case <-time.After(2 * time.Millisecond):
			}
			r.mu.Lock()
			now := time.Now()
			mu.Lock()
			for k := range r.got {
				if _, ok := at[k]; !ok {
					at[k] = now
				}
			}
			mu.Unlock()
			r.mu.Unlock()
		}
	}()
	// warm up (the first reports) before counting
	for i := 0; i < 120; i++ {
		h.send(peer, fmt.Sprintf("w%04d-%s", i, strings.Repeat("x", 80)))
		time.Sleep(16 * time.Millisecond)
	}
	time.Sleep(300 * time.Millisecond)
	w0 := line.wire.Load()
	n := int(dur / (16 * time.Millisecond))
	game := 0
	for i := 0; i < n; i++ {
		// sizes like a real match: mostly small up, some bigger
		pl := fmt.Sprintf("g%05d-%s", i, strings.Repeat("y", 20+rand.Intn(140)))
		mu.Lock()
		sent[pl] = time.Now()
		mu.Unlock()
		game += 2 * len(pl) // it goes up and comes back
		h.send(peer, pl)
		time.Sleep(16 * time.Millisecond)
	}
	time.Sleep(400 * time.Millisecond)
	close(stop)
	wire := line.wire.Load() - w0
	ok := 0
	mu.Lock()
	for k, s := range sent {
		if a, got := at[k]; got && a.Sub(s) <= 150*time.Millisecond+2*line0Delay {
			ok++
		}
	}
	mu.Unlock()
	Stop()
	return float64(ok) / float64(n), float64(wire) / float64(game)
}

var line0Delay = 30 * time.Millisecond

func TestSmartLab(t *testing.T) {
	newBin, oldBin, host := os.Getenv("SRVBIN"), os.Getenv("SRVOLD"), os.Getenv("HOSTIP")
	if newBin == "" || host == "" {
		t.Skip("SRVBIN و HOSTIP تنظیم نشده‌اند")
	}
	models := []lineModel{
		{name: "تمیز", delay: line0Delay},
		{name: "افت پراکنده ۳٪", pGood: 0.03, delay: line0Delay},
		{name: "رگباری (موبایل شلوغ)", pGood: 0.01, pBad: 0.6, toBad: 0.02, toGood: 0.25, delay: line0Delay},
		{name: "خیلی بد ۱۰٪ + رگبار", pGood: 0.08, pBad: 0.7, toBad: 0.03, toGood: 0.2, delay: line0Delay},
	}
	bins := []struct{ name, bin string }{{"سرور تازه", newBin}}
	if oldBin != "" {
		bins = append(bins, struct{ name, bin string }{"سرور قبلی", oldBin})
	}
	port := 5800
	for _, b := range bins {
		for _, m := range models {
			port += 3
			h := labHarness(t, b.bin, port, host)
			line := newLabLine(t, fmt.Sprintf("127.0.0.1:%d", port+1), fmt.Sprintf("127.0.0.1:%d", port), m, int64(port))
			ok, cost := labRun(t, h, fmt.Sprintf("127.0.0.1:%d", port+1), line, 12*time.Second)
			t.Logf("%-10s | %-22s | به‌موقع %.2f%% | حجم روی سیم ×%.2f | افت خام بالا %d‰ پایین %d‰ | نجات پایین %d",
				b.name, m.name, ok*100, cost, LossUp(), LossDown(), FixedDown())
		}
	}
}

// labHarness: like newHarness but with the server binary given
func labHarness(t *testing.T, bin string, port int, host string) *harness {
	srv := exec.Command(bin, "-listen", fmt.Sprintf("127.0.0.1:%d", port), "-key", testKey)
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
