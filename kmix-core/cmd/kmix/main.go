// kmix: هسته‌ی KANKI MIX به‌صورت برنامه‌ی جدا برای اپ اندروید (libkmix.so).
//
// مثل هسته‌های Hysteria2 و Xray در پروسه‌ی جدا اجرا می‌شود؛ پس هیچ‌وقت دو رانتایم Go
// (این و موتور AmneziaWG) داخل یک پروسه نیستند.
//
//	libkmix.so -ctl <اسم سوکت انتزاعی>
//
// اپ یک سوکت یونیکس انتزاعی باز می‌کند و این برنامه به آن وصل می‌شود. پیام اول اپ یک خط JSON است که
// fd کارت شبکه‌ی VPN با SCM_RIGHTS همراهش می‌آید:
//
//	{"server":"1.2.3.4:4000","key":"<hex 64>","fec_data":10,"fec_parity":3,"gaming":true,"ports":"4001-4010","alt":"5.6.7.8:4000"}
//
// ports (اختیاری): پورت‌های اضافه‌ی سرور؛ اگر پورت فعلی کند یا بسته شد، هسته بی قطع شدن به پورت دیگری می‌رود.
// alt (اختیاری): نشانی‌های دیگر همین سرور (ورودی تانل ایران)؛ هر کدام یک مسیر دیگر (mobile/paths.go).
//
// جواب: "ok" یا "err <پیام>". بعد فرمان‌ها، هر کدام یک خط:
//
//	stats        → "stats <rx> <tx> <ping> <افت بالا ‰> <افت پایین ‰> <نجات‌یافته‌های پایین> <مسیر اصلی> <مسیرهای زنده>"
//	gaming 0|1   → "ok"
//	reconnect    → "ok"   (شبکه‌ی گوشی عوض شد)
//	path <اسم>   → "ok"   با یک fd (SCM_RIGHTS): سوکت UDP اپ روی یک شبکه‌ی گوشی (wifi، cell)، بیرون از VPN
//	drop <اسم>   → "ok"   آن شبکه رفت
//	stop         → "ok" و خروج
//
// اگر سوکت بسته شود (اپ بسته شد یا مرد)، تونل بسته می‌شود و برنامه خارج می‌شود.
package main

import (
	"bufio"
	"encoding/json"
	"flag"
	"fmt"
	"net"
	"os"
	"strings"
	"syscall"
	"time"

	"kanki-tunnel/mobile"
)

type startMsg struct {
	Server    string `json:"server"`
	Key       string `json:"key"`
	FecData   int    `json:"fec_data"`
	FecParity int    `json:"fec_parity"`
	Gaming    bool   `json:"gaming"`
	Ports     string `json:"ports"`
	Alt       string `json:"alt"`
}

func main() {
	ctl := flag.String("ctl", "", "اسم سوکت انتزاعی کنترل (بدون @)")
	flag.Parse()
	if *ctl == "" {
		fmt.Fprintln(os.Stderr, "usage: kmix -ctl <name>")
		os.Exit(2)
	}
	c, err := net.DialTimeout("unix", "@"+*ctl, 5*time.Second)
	if err != nil {
		fmt.Fprintln(os.Stderr, "ctl:", err)
		os.Exit(1)
	}
	uc := c.(*net.UnixConn)
	os.Exit(run(uc))
}

func run(uc *net.UnixConn) int {
	defer uc.Close()
	w := bufio.NewWriter(uc)
	reply := func(s string) {
		w.WriteString(s + "\n")
		w.Flush()
	}

	// پیام اول: تنظیمات + fd کارت شبکه
	buf := make([]byte, 4096)
	oob := make([]byte, syscall.CmsgSpace(4))
	uc.SetReadDeadline(time.Now().Add(10 * time.Second))
	n, oobn, _, _, err := uc.ReadMsgUnix(buf, oob)
	if err != nil {
		fmt.Fprintln(os.Stderr, "ctl read:", err)
		return 1
	}
	uc.SetReadDeadline(time.Time{})
	fd, err := tunFd(oob[:oobn])
	if err != nil {
		reply("err " + err.Error())
		return 1
	}
	var m startMsg
	line, rest, _ := strings.Cut(string(buf[:n]), "\n")
	if err := json.Unmarshal([]byte(line), &m); err != nil {
		syscall.Close(fd)
		reply("err bad start message")
		return 1
	}
	mobile.SetPorts(m.Ports)
	mobile.SetAlt(m.Alt)
	if err := mobile.Start(fd, m.Server, m.Key, m.FecData, m.FecParity, m.Gaming); err != nil {
		reply("err " + err.Error())
		return 1
	}
	reply("ok")
	defer mobile.Stop()

	// commands, one a line; a «path» line comes with the fd of its socket in the same message
	pending := []byte(rest)
	var fds []int
	defer func() {
		for _, x := range fds {
			syscall.Close(x)
		}
	}()
	rb := make([]byte, 4096)
	ob := make([]byte, syscall.CmsgSpace(4*4))
	for {
		i := strings.IndexByte(string(pending), '\n')
		if i < 0 {
			n, oobn, _, _, err := uc.ReadMsgUnix(rb, ob)
			if err != nil || (n == 0 && oobn == 0) {
				return 0 // اپ رفت: تونل بسته می‌شود
			}
			fds = append(fds, rights(ob[:oobn])...)
			pending = append(pending, rb[:n]...)
			if len(pending) > 1<<16 {
				return 1
			}
			continue
		}
		cmd := string(pending[:i])
		pending = pending[i+1:]
		f := strings.Fields(cmd)
		if len(f) == 0 {
			continue
		}
		switch f[0] {
		case "stats":
			name, live := mobile.PathInfo()
			if name == "" {
				name = "-"
			}
			reply(fmt.Sprintf("stats %d %d %d %d %d %d %s %d", mobile.RxBytes(), mobile.TxBytes(), mobile.GetPing(), mobile.LossUp(), mobile.LossDown(), mobile.FixedDown(), name, live))
		case "gaming":
			mobile.SetGaming(len(f) > 1 && f[1] == "1")
			reply("ok")
		case "reconnect":
			mobile.Reconnect()
			reply("ok")
		case "path":
			if len(f) < 2 || len(fds) == 0 || !okName(f[1]) {
				reply("err path needs a name and a socket")
				continue
			}
			fd := fds[0]
			fds = fds[1:]
			if err := mobile.AddSocket(f[1], fd); err != nil {
				reply("err " + err.Error())
				continue
			}
			reply("ok")
		case "drop":
			if len(f) > 1 && okName(f[1]) {
				mobile.DropSocket(f[1])
			}
			reply("ok")
		case "stop":
			reply("ok")
			return 0
		default:
			reply("err unknown command")
		}
	}
}

// okName: the name of a phone network (wifi, cell)
func okName(s string) bool {
	if s == "" || len(s) > 16 {
		return false
	}
	for _, c := range s {
		if !(c >= 'a' && c <= 'z') {
			return false
		}
	}
	return true
}

// rights: every fd of a SCM_RIGHTS message
func rights(oob []byte) []int {
	msgs, err := syscall.ParseSocketControlMessage(oob)
	if err != nil {
		return nil
	}
	var l []int
	for i := range msgs {
		if fds, err := syscall.ParseUnixRights(&msgs[i]); err == nil {
			l = append(l, fds...)
		}
	}
	return l
}

// tunFd fd کارت شبکه را از پیام SCM_RIGHTS بیرون می‌آورد.
func tunFd(oob []byte) (int, error) {
	msgs, err := syscall.ParseSocketControlMessage(oob)
	if err != nil || len(msgs) == 0 {
		return -1, fmt.Errorf("no tun fd")
	}
	fds, err := syscall.ParseUnixRights(&msgs[0])
	if err != nil || len(fds) == 0 {
		return -1, fmt.Errorf("no tun fd")
	}
	for _, x := range fds[1:] {
		syscall.Close(x)
	}
	return fds[0], nil
}
