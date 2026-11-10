// libkping.so: the KPING client of the Kanki app, a program of its own like libmtun.so (its Go runtime never
// shares the app's process). The client itself is the kping package.
//
//	libkping.so client --url 'kping://…' --sock PATH
//	    prints "READY <relay ip>" (the app keeps that address out of the VPN), then takes, through the unix
//	    socket PATH, one message per connection: "TUN" + the VPN's fd, "PATH <name>" + a UDP socket the app
//	    bound to one network (Wi-Fi, mobile data), "DROP <name>" (that network went away), "SET {json}"
//	    (the settings: auto, dup, fec, every). Every second: "STAT {json}" (paths, ping, rescued, …).
//	libkping.so ping --url 'kping://…'
//	    three PINGs straight to the relay; prints the best round trip in ms (exit 1 and the reason when none)
package main

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"strings"
	"sync"
	"syscall"
	"time"

	"kpingcore/kping"
)

var out sync.Mutex

func say(line string) {
	out.Lock()
	fmt.Println(line)
	out.Unlock()
}

// one message: its text and the fd that came with it (nil when none)
func recv(conn *net.UnixConn) (string, *os.File) {
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	b, oob := make([]byte, 4096), make([]byte, syscall.CmsgSpace(4*4))
	n, oobn, _, _, err := conn.ReadMsgUnix(b, oob)
	if err != nil {
		return "", nil
	}
	var f *os.File
	if msgs, err := syscall.ParseSocketControlMessage(oob[:oobn]); err == nil {
		for _, m := range msgs {
			if fds, err := syscall.ParseUnixRights(&m); err == nil && len(fds) > 0 {
				for _, extra := range fds[1:] {
					syscall.Close(extra)
				}
				f = os.NewFile(uintptr(fds[0]), "fd")
			}
		}
	}
	return strings.TrimSpace(string(b[:n])), f
}

func serve(c *kping.Client, path string) error {
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
		msg, f := recv(conn)
		conn.Close()
		switch {
		case msg == "TUN" && f != nil:
			c.SetTUN(f)
		case strings.HasPrefix(msg, "PATH ") && f != nil:
			pc, err := net.FilePacketConn(f)
			f.Close()
			if err != nil {
				say("LOG path " + msg + ": " + err.Error())
				continue
			}
			c.AddPath(strings.TrimPrefix(msg, "PATH "), pc)
		case strings.HasPrefix(msg, "DROP "):
			c.DropPath(strings.TrimPrefix(msg, "DROP "))
		case strings.HasPrefix(msg, "SET "):
			var s kping.Settings
			if json.Unmarshal([]byte(strings.TrimPrefix(msg, "SET ")), &s) == nil {
				c.Set(s)
			}
		default:
			if f != nil {
				f.Close()
			}
		}
	}
}

func main() {
	if len(os.Args) < 2 || (os.Args[1] != "client" && os.Args[1] != "ping") {
		fmt.Println("usage: libkping.so client --url URL --sock PATH | ping --url URL")
		os.Exit(2)
	}
	var link, sock string
	for i := 2; i+1 < len(os.Args); i += 2 {
		switch os.Args[i] {
		case "--url":
			link = os.Args[i+1]
		case "--sock":
			sock = os.Args[i+1]
		}
	}
	l, err := kping.ParseLink(link)
	if err != nil {
		fmt.Println("ERR", err)
		os.Exit(1)
	}
	if os.Args[1] == "ping" {
		ms, err := kping.Ping(l)
		if err != nil {
			fmt.Println(err)
			os.Exit(1)
		}
		fmt.Println(ms)
		return
	}
	c, err := kping.New(l)
	if err != nil {
		fmt.Println("ERR", err)
		os.Exit(1)
	}
	go func() {
		for range time.Tick(time.Second) {
			b, _ := json.Marshal(c.Stat())
			say("STAT " + string(b))
		}
	}()
	say("READY " + c.Relay().IP.String())
	if err := serve(c, sock); err != nil {
		fmt.Println("ERR", err)
		os.Exit(1)
	}
}
