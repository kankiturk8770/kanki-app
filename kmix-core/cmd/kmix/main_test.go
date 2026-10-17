package main

// برنامه‌ی kmix را همان‌طور که اپ اجرا می‌کند امتحان می‌کند: سوکت کنترل انتزاعی، fd کارت شبکه با
// SCM_RIGHTS، فرمان‌ها. به سرور واقعی نیاز دارد: SRVBIN و HOSTIP (مثل تست‌های پوشه‌ی mobile).

import (
	"bufio"
	"encoding/binary"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"
)

const testKey = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"

func TestExecutable(t *testing.T) {
	srvBin, host := os.Getenv("SRVBIN"), os.Getenv("HOSTIP")
	if srvBin == "" || host == "" {
		t.Skip("SRVBIN و HOSTIP تنظیم نشده‌اند")
	}
	bin := filepath.Join(t.TempDir(), "kmix")
	if out, err := exec.Command("go", "build", "-o", bin, ".").CombinedOutput(); err != nil {
		t.Fatalf("build: %v\n%s", err, out)
	}
	srv := exec.Command(srvBin, "-listen", "127.0.0.1:4900", "-key", testKey)
	if err := srv.Start(); err != nil {
		t.Fatal(err)
	}
	defer srv.Process.Kill()
	echo, _ := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP(host)})
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
	time.Sleep(300 * time.Millisecond)

	// اپ: سوکت کنترل انتزاعی و کارت شبکه‌ی ساختگی
	name := fmt.Sprintf("kmix-test-%d", os.Getpid())
	ln, err := net.ListenUnix("unix", &net.UnixAddr{Name: "@" + name, Net: "unix"})
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	fds, _ := syscall.Socketpair(syscall.AF_UNIX, syscall.SOCK_DGRAM, 0)
	peer := os.NewFile(uintptr(fds[1]), "peer")
	defer peer.Close()

	child := exec.Command(bin, "-ctl", name)
	child.Stderr = os.Stderr
	if err := child.Start(); err != nil {
		t.Fatal(err)
	}
	ln.SetDeadline(time.Now().Add(5 * time.Second))
	c, err := ln.AcceptUnix()
	if err != nil {
		t.Fatal(err)
	}
	msg := fmt.Sprintf(`{"server":"127.0.0.1:4900","key":"%s","fec_data":10,"fec_parity":3,"gaming":true}`+"\n", testKey)
	if _, _, err := c.WriteMsgUnix([]byte(msg), syscall.UnixRights(fds[0]), nil); err != nil {
		t.Fatal(err)
	}
	syscall.Close(fds[0]) // حالا مال برنامه است
	r := bufio.NewReader(c)
	line := func() string {
		c.SetReadDeadline(time.Now().Add(12 * time.Second))
		s, _ := r.ReadString('\n')
		return strings.TrimSpace(s)
	}
	if got := line(); got != "ok" {
		t.Fatalf("start: %q", got)
	}

	// یک بسته‌ی UDP از «بازی» از راه کارت شبکه و برگشتش
	pkt := udpPacket(net.IPv4(10, 0, 0, 2).To4(), net.ParseIP(host).To4(), 5555, uint16(echo.LocalAddr().(*net.UDPAddr).Port), []byte("hello-kmix"))
	got := make(chan bool, 1)
	go func() {
		b := make([]byte, 2000)
		for {
			n, err := peer.Read(b)
			if err != nil {
				return
			}
			if strings.Contains(string(b[:n]), "hello-kmix") {
				got <- true
				return
			}
		}
	}()
	peer.Write(pkt)
	select {
	case <-got:
	case <-time.After(4 * time.Second):
		t.Fatal("بسته‌ی بازی برنگشت")
	}

	time.Sleep(1500 * time.Millisecond)
	c.Write([]byte("stats\n"))
	var rx, tx, ping int64
	if _, err := fmt.Sscanf(line(), "stats %d %d %d", &rx, &tx, &ping); err != nil || rx <= 0 || tx <= 0 || ping < 0 {
		t.Fatalf("stats: rx=%d tx=%d ping=%d err=%v", rx, tx, ping, err)
	}
	for _, cmd := range []string{"gaming 0", "reconnect"} {
		c.Write([]byte(cmd + "\n"))
		if got := line(); got != "ok" {
			t.Fatalf("%s: %q", cmd, got)
		}
	}
	c.Write([]byte("stop\n"))
	if got := line(); got != "ok" {
		t.Fatalf("stop: %q", got)
	}
	done := make(chan error, 1)
	go func() { done <- child.Wait() }()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("برنامه بعد از stop بسته نشد")
	}
}

// اپ مرد (سوکت بسته شد): برنامه هم باید برود
func TestExitsWhenAppGoes(t *testing.T) {
	bin := filepath.Join(t.TempDir(), "kmix")
	if out, err := exec.Command("go", "build", "-o", bin, ".").CombinedOutput(); err != nil {
		t.Fatalf("build: %v\n%s", err, out)
	}
	name := fmt.Sprintf("kmix-test2-%d", os.Getpid())
	ln, err := net.ListenUnix("unix", &net.UnixAddr{Name: "@" + name, Net: "unix"})
	if err != nil {
		t.Fatal(err)
	}
	child := exec.Command(bin, "-ctl", name)
	if err := child.Start(); err != nil {
		t.Fatal(err)
	}
	c, err := ln.AcceptUnix()
	if err != nil {
		t.Fatal(err)
	}
	c.Close()
	ln.Close()
	done := make(chan error, 1)
	go func() { done <- child.Wait() }()
	select {
	case <-done:
	case <-time.After(15 * time.Second):
		child.Process.Kill()
		t.Fatal("برنامه بعد از رفتن اپ بسته نشد")
	}
}

func udpPacket(src, dst net.IP, sp, dp uint16, payload []byte) []byte {
	total := 28 + len(payload)
	p := make([]byte, total)
	p[0] = 0x45
	binary.BigEndian.PutUint16(p[2:4], uint16(total))
	p[8] = 64
	p[9] = 17
	copy(p[12:16], src)
	copy(p[16:20], dst)
	var sum uint32
	for i := 0; i < 20; i += 2 {
		sum += uint32(binary.BigEndian.Uint16(p[i : i+2]))
	}
	for sum>>16 != 0 {
		sum = sum&0xffff + sum>>16
	}
	binary.BigEndian.PutUint16(p[10:12], ^uint16(sum))
	binary.BigEndian.PutUint16(p[20:22], sp)
	binary.BigEndian.PutUint16(p[22:24], dp)
	binary.BigEndian.PutUint16(p[24:26], uint16(8+len(payload)))
	copy(p[28:], payload)
	return p
}
