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
//	{"server":"1.2.3.4:4000","key":"<hex 64>","fec_data":10,"fec_parity":3,"gaming":true}
//
// جواب: "ok" یا "err <پیام>". بعد فرمان‌ها، هر کدام یک خط:
//
//	stats        → "stats <rx> <tx> <ping>"
//	gaming 0|1   → "ok"
//	reconnect    → "ok"   (شبکه‌ی گوشی عوض شد)
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
	if err := mobile.Start(fd, m.Server, m.Key, m.FecData, m.FecParity, m.Gaming); err != nil {
		reply("err " + err.Error())
		return 1
	}
	reply("ok")
	defer mobile.Stop()

	r := bufio.NewReader(&prefixReader{rest: []byte(rest), c: uc})
	for {
		cmd, err := r.ReadString('\n')
		if err != nil {
			return 0 // اپ رفت: تونل بسته می‌شود
		}
		f := strings.Fields(cmd)
		if len(f) == 0 {
			continue
		}
		switch f[0] {
		case "stats":
			reply(fmt.Sprintf("stats %d %d %d", mobile.RxBytes(), mobile.TxBytes(), mobile.GetPing()))
		case "gaming":
			mobile.SetGaming(len(f) > 1 && f[1] == "1")
			reply("ok")
		case "reconnect":
			mobile.Reconnect()
			reply("ok")
		case "stop":
			reply("ok")
			return 0
		default:
			reply("err unknown command")
		}
	}
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

// prefixReader هر چیزی را که بعد از خط اول در همان پیام آمده، قبل از بقیه‌ی سوکت می‌دهد.
type prefixReader struct {
	rest []byte
	c    *net.UnixConn
}

func (p *prefixReader) Read(b []byte) (int, error) {
	if len(p.rest) > 0 {
		n := copy(b, p.rest)
		p.rest = p.rest[n:]
		return n, nil
	}
	return p.c.Read(b)
}
