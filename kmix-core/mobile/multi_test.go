package mobile

import (
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	"kanki-tunnel/internal/obfs"
	"kanki-tunnel/internal/proto"
)

const (
	keyAli  = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"
	keySara = "b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2"
	apiSec  = "s3cret"
)

func writeUsers(t *testing.T, path string, users ...[3]any) {
	var f struct {
		Users []map[string]any `json:"users"`
	}
	for _, u := range users {
		f.Users = append(f.Users, map[string]any{"name": u[0], "key": u[1], "max": u[2]})
	}
	b, _ := json.Marshal(f)
	if err := os.WriteFile(path, b, 0o600); err != nil {
		t.Fatal(err)
	}
}

func apiGet(t *testing.T, path string, out any) {
	req, _ := http.NewRequest(http.MethodGet, "http://127.0.0.1:4811"+path, nil)
	req.Header.Set("Authorization", apiSec)
	r, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer r.Body.Close()
	if err := json.NewDecoder(r.Body).Decode(out); err != nil {
		t.Fatal(err)
	}
}

// pinger یک کلاینت خام: با کلید key و شناسه‌ی نشست sid پینگ می‌فرستد و می‌گوید جواب آمد یا نه.
func pinger(t *testing.T, key string, sid uint64) func() bool {
	raw, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { raw.Close() })
	k, _ := hex.DecodeString(key)
	pc, err := obfs.New(raw, k, nil)
	if err != nil {
		t.Fatal(err)
	}
	srv, _ := net.ResolveUDPAddr("udp", "127.0.0.1:4810")
	return func() bool {
		var b [proto.PingLen]byte
		binary.BigEndian.PutUint64(b[0:8], sid)
		pc.WritePacket(proto.KindPing, b[:], srv)
		raw.SetReadDeadline(time.Now().Add(700 * time.Millisecond))
		buf := make([]byte, 2000)
		kind, _, _, err := pc.ReadPacket(buf)
		return err == nil && kind == proto.KindPong
	}
}

func TestMultiUser(t *testing.T) {
	bin, host := os.Getenv("SRVBIN"), os.Getenv("HOSTIP")
	if bin == "" || host == "" {
		t.Skip("SRVBIN و HOSTIP تنظیم نشده‌اند")
	}
	users := filepath.Join(t.TempDir(), "users.json")
	writeUsers(t, users, [3]any{"ali", keyAli, 1}, [3]any{"sara", keySara, 0})
	srv := exec.Command(bin, "-listen", "127.0.0.1:4810", "-users", users, "-api", "127.0.0.1:4811", "-api-secret", apiSec)
	srv.Stderr = os.Stderr
	if err := srv.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { srv.Process.Kill(); srv.Wait() })
	time.Sleep(400 * time.Millisecond)

	// کلید ناشناس: سرور هیچ جوابی نمی‌دهد
	if pinger(t, testKey, 1)() {
		t.Fatal("کلید ناشناس جواب گرفت")
	}

	// تونل کامل (KCP + UDP) با کلید ali
	h := &harness{t: t, hostIP: net.ParseIP(host)}
	echo, _ := net.ListenUDP("udp", &net.UDPAddr{IP: h.hostIP})
	defer echo.Close()
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
	h.echo = echo.LocalAddr().(*net.UDPAddr)
	peer, r := h.startKey("127.0.0.1:4810", keyAli)
	for i := 0; i < 20; i++ {
		h.send(peer, fmt.Sprintf("m%d", i))
	}
	if got := r.waitFor(20, 3*time.Second); len(got) != 20 {
		t.Fatalf("ali: فقط %d پاسخ", len(got))
	}
	time.Sleep(1200 * time.Millisecond) // حداقل یک پینگ

	var traffic map[string]map[string]int64
	apiGet(t, "/traffic?clear=1", &traffic)
	if traffic["ali"]["rx"] == 0 || traffic["ali"]["tx"] == 0 || traffic["sara"] != nil {
		t.Fatalf("آمار مصرف اشتباه: %v", traffic)
	}
	var online map[string]int
	apiGet(t, "/online", &online)
	if online["ali"] != 1 {
		t.Fatalf("آنلاین اشتباه: %v", online)
	}

	// سقف ۱ دستگاه: دستگاه دوم ali جواب نمی‌گیرد، sara (نامحدود) می‌گیرد
	if pinger(t, keyAli, 999)() {
		t.Fatal("دستگاه دوم ali از سقف رد شد")
	}
	if !pinger(t, keySara, 5)() || !pinger(t, keySara, 6)() {
		t.Fatal("sara جواب نگرفت")
	}

	// اپ بسته و دوباره باز شد: نشست قبلی ساکت است و بعد از چند ثانیه جایش را به دستگاه تازه می‌دهد
	Stop()
	time.Sleep(6 * time.Second)
	if !pinger(t, keyAli, 777)() {
		t.Fatal("ali بعد از بستن و باز کردن اپ وصل نشد (سقف ۱ دستگاه)")
	}
	apiGet(t, "/online", &online)
	if online["ali"] != 1 {
		t.Fatalf("آنلاین بعد از اتصال دوباره: %v", online)
	}
	peer, r = h.startKey("127.0.0.1:4810", keySara)
	_ = peer

	// حذف sara با reload: فوراً قطع می‌شود
	writeUsers(t, users, [3]any{"ali", keyAli, 1})
	req, _ := http.NewRequest(http.MethodPost, "http://127.0.0.1:4811/reload", nil)
	req.Header.Set("Authorization", apiSec)
	if resp, err := http.DefaultClient.Do(req); err != nil || resp.StatusCode != 200 {
		t.Fatalf("reload: %v", err)
	}
	r.mu.Lock()
	r.got = map[string]int{}
	r.mu.Unlock()
	for i := 0; i < 10; i++ {
		h.send(peer, fmt.Sprintf("after%d", i))
	}
	if got := r.waitFor(1, time.Second); len(got) != 0 {
		t.Fatalf("sara بعد از حذف هنوز وصل است (%d پاسخ)", len(got))
	}

	// رمز API اشتباه
	req, _ = http.NewRequest(http.MethodGet, "http://127.0.0.1:4811/online", nil)
	req.Header.Set("Authorization", "wrong")
	if resp, err := http.DefaultClient.Do(req); err != nil || resp.StatusCode != http.StatusUnauthorized {
		t.Fatal("API بدون رمز درست جواب داد")
	}
}
