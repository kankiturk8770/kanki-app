// libmtun.so: the microtun client of the Kanki app, a program of its own like libkmix.so (its Go
// runtime never shares the app's process). The client itself is the mtun package (also used by
// the Windows service).
//
//	libmtun.so client --url 'mtun://…' --device ID --sock PATH
//	    shakes hands with the server first (the VPN is not up yet, so this goes out directly),
//	    then prints "ADDR <ipv4/bits> <ipv6|->": the address the server gave this device. The
//	    app opens the VPN with that address and hands over its TUN through the unix socket PATH
//	    (SCM_RIGHTS). A later ADDR line (the server moved this device) asks for a new TUN; the
//	    old one keeps working until it comes. Every 2 s: "STAT {"up":…,"rtt":…,"rx":…,"tx":…}".
//	libmtun.so ping --url 'mtun://…'
//	    one handshake; prints its round trip in ms (exit 1 and the reason when it fails)
package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"sync"
	"syscall"
	"time"

	"mtuncore/mtun"
)

var out sync.Mutex // stdout lines

func say(line string) {
	out.Lock()
	fmt.Println(line)
	out.Unlock()
}

// takeTUNs: each connection to the socket brings a TUN (SCM_RIGHTS) that replaces the one before
func takeTUNs(c *mtun.Client, path string) error {
	_ = os.Remove(path)
	l, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		return err
	}
	_ = os.Chmod(path, 0o600)
	for {
		conn, err := l.AcceptUnix()
		if err != nil {
			return err
		}
		f := recvFD(conn)
		conn.Close()
		if f != nil {
			c.SetTUN(f)
		}
	}
}

func recvFD(conn *net.UnixConn) *os.File {
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	b, oob := make([]byte, 16), make([]byte, syscall.CmsgSpace(4*4))
	_, oobn, _, _, err := conn.ReadMsgUnix(b, oob)
	if err != nil {
		return nil
	}
	msgs, err := syscall.ParseSocketControlMessage(oob[:oobn])
	if err != nil {
		return nil
	}
	for _, m := range msgs {
		if fds, err := syscall.ParseUnixRights(&m); err == nil && len(fds) > 0 {
			for _, extra := range fds[1:] {
				syscall.Close(extra)
			}
			return os.NewFile(uintptr(fds[0]), "tun")
		}
	}
	return nil
}

func status(c *mtun.Client) {
	for range time.Tick(2 * time.Second) {
		up, rtt, rx, tx := c.Stats()
		b, _ := json.Marshal(map[string]any{"up": up, "rtt": rtt, "rx": rx, "tx": tx})
		say("STAT " + string(b))
	}
}

func flags(args []string) map[string]string {
	m := map[string]string{}
	for i := 0; i < len(args); i++ {
		if k, ok := cut(args[i]); ok && i+1 < len(args) {
			m[k] = args[i+1]
			i++
		}
	}
	return m
}

func cut(a string) (string, bool) {
	for _, p := range []string{"--", "-"} {
		if len(a) > len(p) && a[:len(p)] == p {
			return a[len(p):], true
		}
	}
	return "", false
}

func fail(err error) {
	fmt.Println("error:", err)
	os.Exit(1)
}

func main() {
	if len(os.Args) < 2 {
		fail(errors.New("usage: libmtun.so client|ping --url 'mtun://…'"))
	}
	f := flags(os.Args[2:])
	if _, err := mtun.ParseLink(f["url"]); err != nil {
		fail(err)
	}
	switch os.Args[1] {
	case "ping":
		rtt, err := mtun.Ping(f["url"])
		if err != nil {
			fail(err)
		}
		fmt.Println(rtt)
	case "client":
		if f["sock"] == "" {
			fail(errors.New("--sock is missing"))
		}
		c, err := mtun.Dial(f["url"], f["device"], say)
		if err != nil {
			fail(err)
		}
		go status(c)
		fail(takeTUNs(c, f["sock"]))
	default:
		fail(errors.New("unknown command " + os.Args[1]))
	}
}
