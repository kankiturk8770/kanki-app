// libawg.so: WireGuard / AmneziaWG of the Kanki app in gaming mode, a program of its own like libmtun.so (its Go
// runtime never shares the app's process). Gaming mode puts every app inside the VPN and a gate in front of the
// core (AppGate): only the chosen games get through. The amnezia library's own engine opens the VPN card itself,
// so the gate cannot sit in front of it; this core takes whatever the app hands it (the gate's socket or the card).
//
//	libawg.so client --uapi FILE --sock PATH --mtu N
//	    reads the device settings (amneziawg UAPI, made by the app from the config) from FILE and removes the file,
//	    waits for the packet end on the unix socket PATH (SCM_RIGHTS), brings the device up and prints "READY".
//	    Every 2 s: "STAT {"rx":…,"tx":…,"hs":…}" (hs: seconds since the last handshake, -1 = none yet).
package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/conn"
	"github.com/amnezia-vpn/amneziawg-go/device"
	"github.com/amnezia-vpn/amneziawg-go/tun"
)

var out sync.Mutex

func say(line string) {
	out.Lock()
	fmt.Println(line)
	out.Unlock()
}

func fail(err error) {
	say("error: " + err.Error())
	os.Exit(1)
}

// pktDev: one packet per read and write on a file (a VPN card or the gate's SEQPACKET socket)
type pktDev struct {
	f      *os.File
	mtu    int
	events chan tun.Event
	once   sync.Once
}

func newPktDev(f *os.File, mtu int) *pktDev {
	d := &pktDev{f: f, mtu: mtu, events: make(chan tun.Event, 1)}
	d.events <- tun.EventUp
	return d
}

func (d *pktDev) File() *os.File { return d.f }

func (d *pktDev) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	n, err := d.f.Read(bufs[0][offset:])
	if err != nil {
		return 0, err
	}
	sizes[0] = n
	return 1, nil
}

func (d *pktDev) Write(bufs [][]byte, offset int) (int, error) {
	for i, b := range bufs {
		if _, err := d.f.Write(b[offset:]); err != nil {
			return i, err
		}
	}
	return len(bufs), nil
}

func (d *pktDev) MTU() (int, error)        { return d.mtu, nil }
func (d *pktDev) Name() (string, error)    { return "kanki", nil }
func (d *pktDev) Events() <-chan tun.Event { return d.events }
func (d *pktDev) BatchSize() int           { return 1 }
func (d *pktDev) Close() error {
	d.once.Do(func() { close(d.events) })
	return d.f.Close()
}

// takeOne: the first connection to the socket brings the packet end (SCM_RIGHTS)
func takeOne(path string) (*os.File, error) {
	_ = os.Remove(path)
	l, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		return nil, err
	}
	defer l.Close()
	_ = os.Chmod(path, 0o600)
	_ = l.SetDeadline(time.Now().Add(20 * time.Second))
	for {
		c, err := l.AcceptUnix()
		if err != nil {
			return nil, err
		}
		f := recvFD(c)
		c.Close()
		if f != nil {
			return f, nil
		}
	}
}

func recvFD(c *net.UnixConn) *os.File {
	_ = c.SetReadDeadline(time.Now().Add(5 * time.Second))
	b, oob := make([]byte, 16), make([]byte, syscall.CmsgSpace(4*4))
	_, oobn, _, _, err := c.ReadMsgUnix(b, oob)
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

// stats from the device's own report: received, sent, seconds since the last handshake (-1: none)
func stats(d *device.Device) (rx, tx, hs int64) {
	s, err := d.IpcGet()
	hs = -1
	if err != nil {
		return
	}
	var sec int64
	sc := bufio.NewScanner(strings.NewReader(s))
	for sc.Scan() {
		k, v, ok := strings.Cut(sc.Text(), "=")
		if !ok {
			continue
		}
		n, _ := strconv.ParseInt(v, 10, 64)
		switch k {
		case "rx_bytes":
			rx += n
		case "tx_bytes":
			tx += n
		case "last_handshake_time_sec":
			if n > sec {
				sec = n
			}
		}
	}
	if sec > 0 {
		hs = time.Now().Unix() - sec
	}
	return
}

func flags(args []string) map[string]string {
	m := map[string]string{}
	for i := 0; i+1 < len(args); i++ {
		if strings.HasPrefix(args[i], "--") {
			m[args[i][2:]] = args[i+1]
			i++
		}
	}
	return m
}

func run(f map[string]string) error {
	if f["uapi"] == "" || f["sock"] == "" {
		return errors.New("--uapi and --sock are needed")
	}
	uapi, err := os.ReadFile(f["uapi"])
	_ = os.Remove(f["uapi"]) // the private key does not stay on disk
	if err != nil {
		return err
	}
	mtu, _ := strconv.Atoi(f["mtu"])
	if mtu < 576 || mtu > 9000 {
		mtu = 1280
	}
	say("WAIT")
	tf, err := takeOne(f["sock"])
	if err != nil {
		return fmt.Errorf("no VPN from the app: %w", err)
	}
	logf := func(string, ...any) {}
	dev := device.NewDevice(newPktDev(tf, mtu), conn.NewDefaultBind(), &device.Logger{Verbosef: logf, Errorf: func(s string, a ...any) { say("log: " + fmt.Sprintf(s, a...)) }})
	if err := dev.IpcSet(string(uapi)); err != nil {
		return fmt.Errorf("settings: %w", err)
	}
	if err := dev.Up(); err != nil {
		return err
	}
	say("READY")
	go func() {
		for range time.Tick(2 * time.Second) {
			rx, tx, hs := stats(dev)
			b, _ := json.Marshal(map[string]int64{"rx": rx, "tx": tx, "hs": hs})
			say("STAT " + string(b))
		}
	}()
	<-dev.Wait()
	return errors.New("device closed")
}

func main() {
	if len(os.Args) < 2 || os.Args[1] != "client" {
		fail(errors.New("usage: libawg.so client --uapi FILE --sock PATH --mtu N"))
	}
	fail(run(flags(os.Args[2:])))
}
