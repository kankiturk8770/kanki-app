// libmtun.so: the microtun client of the Kanki app, a program of its own like libkmix.so (its Go
// runtime never shares the app's process). The wire format is proto.go of the microtun core in the
// Labo repository, unchanged.
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
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/sha256"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"net/url"
	"os"
	"strconv"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

type duration time.Duration

// linkConfig is an mtun:// link as the Kanki panel makes it:
// mtun://KEY@host:port?obfs=HEX&ip=10.79.0.2/16&mtu=1420&burst=300&delay=2#name
type linkConfig struct {
	Server, Key, ObfsKey, Address string
	MTU, BurstMax                 int
	BurstDelay                    duration
}

func parseURL(s string) (*linkConfig, error) {
	u, err := url.Parse(s)
	if err != nil || u.Scheme != "mtun" || u.User == nil || u.Host == "" {
		return nil, fmt.Errorf("not an mtun:// link")
	}
	q := u.Query()
	num := func(k string, def int) int {
		if v, err := strconv.Atoi(q.Get(k)); err == nil {
			return v
		}
		return def
	}
	return &linkConfig{
		Server:     u.Host,
		Key:        u.User.Username(),
		ObfsKey:    q.Get("obfs"),
		Address:    q.Get("ip"),
		MTU:        num("mtu", 1420),
		BurstMax:   num("burst", 300),
		BurstDelay: duration(time.Duration(num("delay", 2)) * time.Millisecond),
	}, nil
}

func beUint32(b []byte) uint32 { return binary.BigEndian.Uint32(b) }

func key32(name, h string) ([]byte, error) {
	b := make([]byte, 32)
	if len(h) != 64 {
		return nil, fmt.Errorf("%s: want 64 hex characters", name)
	}
	for i := range b {
		v, err := strconv.ParseUint(h[2*i:2*i+2], 16, 8)
		if err != nil {
			return nil, fmt.Errorf("%s: not hex", name)
		}
		b[i] = byte(v)
	}
	return b, nil
}

// deviceID: the app keeps one name per install, so a reconnect is the same device; never 0
// (0 is a client without a device id)
func deviceID(name, key string) uint64 {
	h := sha256.Sum256([]byte("microtun device|" + key + "|" + name))
	return binary.BigEndian.Uint64(h[:8]) | 1
}

const keepalive = 10 * time.Second

type client struct {
	cfg    *linkConfig
	obfs   cipher.Block
	keys   *pskKeys
	conn   *net.UDPConn
	server netip.AddrPort
	dev    uint64
	probe  bool       // ping: a handshake only, no session on the server
	out    sync.Mutex // stdout lines

	tun      atomic.Pointer[os.File]
	current  atomic.Pointer[session]
	previous atomic.Pointer[session]
	lastRecv atomic.Int64
	lastSend atomic.Int64
	rx, tx   atomic.Int64
	rtt      atomic.Int64 // ms, of the last handshake

	mu     sync.Mutex // the handshake in flight
	hsEph  *ecdh.PrivateKey
	hsIdx  uint32
	hsSent time.Time
	lastTS uint64
	addr   string // the ADDR line said last ("" = none yet)
	ready  chan struct{}
}

func newClient(cfg *linkConfig, dev uint64) (*client, error) {
	ok, err := key32("obfs", cfg.ObfsKey)
	if err != nil {
		return nil, err
	}
	psk, err := key32("key", cfg.Key)
	if err != nil {
		return nil, err
	}
	c := &client{cfg: cfg, dev: dev, ready: make(chan struct{})}
	if c.obfs, err = newBlock(ok); err != nil {
		return nil, err
	}
	if c.keys, err = newPSKKeys(psk); err != nil {
		return nil, err
	}
	ua, err := net.ResolveUDPAddr("udp", cfg.Server)
	if err != nil {
		return nil, err
	}
	c.server = netip.AddrPortFrom(ua.AddrPort().Addr().Unmap(), ua.AddrPort().Port())
	// unconnected: ICMP "port unreachable" while the server restarts is no read error
	if c.conn, err = net.ListenUDP("udp", nil); err != nil {
		return nil, err
	}
	_ = c.conn.SetReadBuffer(4 << 20)
	_ = c.conn.SetWriteBuffer(4 << 20)
	return c, nil
}

func (c *client) say(format string, a ...any) {
	c.out.Lock()
	fmt.Printf(format+"\n", a...)
	c.out.Unlock()
}

func (c *client) sendInit() {
	eph, err := newEphemeral()
	if err != nil {
		return
	}
	ts := uint64(time.Now().UnixNano())
	if ts <= c.lastTS {
		ts = c.lastTS + 1
	}
	c.lastTS = ts
	c.hsEph, c.hsIdx, c.hsSent = eph, randIdx(), time.Now()
	pkt := buildInit(c.obfs, c.keys, initMsg{eph: eph.PublicKey().Bytes(), ts: ts, idx: c.hsIdx, dev: c.dev})
	_, _ = c.conn.WriteToUDPAddrPort(pkt, c.server)
}

func (c *client) handleResp(pkt []byte) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.hsEph == nil {
		return
	}
	ephC := c.hsEph.PublicKey().Bytes()
	m, ok := parseResp(c.keys, pkt, ephC)
	if !ok || m.clientIdx != c.hsIdx {
		return
	}
	shared, err := dh(c.hsEph, m.eph)
	if err != nil {
		return
	}
	c2s, s2c, err := deriveKeys(shared, c.keys.psk, ephC, m.eph, c.hsIdx, m.serverIdx)
	if err != nil {
		return
	}
	sess, err := newSession(c.hsIdx, m.serverIdx, c2s, s2c)
	if err != nil {
		return
	}
	c.rtt.Store(max(1, time.Since(c.hsSent).Milliseconds()))
	c.hsEph = nil
	c.previous.Store(c.current.Load())
	c.current.Store(sess)
	c.lastRecv.Store(time.Now().UnixNano())
	if !c.probe {
		c.sendKeepalive(sess) // confirms the session on the server right away
	}

	// the address of this device: the server's (v2.1), else the link's own
	link, _ := netip.ParsePrefix(c.cfg.Address)
	v4, v6 := link.Addr(), "-"
	if m.v4.IsValid() {
		v4 = m.v4
		if m.v6.IsValid() {
			v6 = netip.PrefixFrom(m.v6, 64).String()
		}
	}
	line := netip.PrefixFrom(v4, link.Bits()).String() + " " + v6
	if line != c.addr {
		first := c.addr == ""
		c.addr = line
		if !c.probe {
			c.say("ADDR %s", line)
		}
		if first {
			close(c.ready)
		}
	}
}

func (c *client) sendKeepalive(sess *session) {
	buf := make([]byte, overhead)
	if pkt := sess.seal(c.obfs, buf, 0); pkt != nil {
		_, _ = c.conn.WriteToUDPAddrPort(pkt, c.server)
		c.lastSend.Store(time.Now().UnixNano())
	}
}

func (c *client) udpLoop() {
	buf := make([]byte, maxIP+hdrLen+tagLen)
	for {
		n, _, err := c.conn.ReadFromUDPAddrPort(buf)
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return
			}
			continue
		}
		if n < overhead {
			continue
		}
		pkt := buf[:n]
		mask(c.obfs, pkt)
		idx := beUint32(pkt)
		if idx == 0 {
			c.handleResp(pkt)
			continue
		}
		var sess *session
		if s := c.current.Load(); s != nil && s.localIdx == idx {
			sess = s
		} else if s := c.previous.Load(); s != nil && s.localIdx == idx {
			sess = s
		} else {
			continue
		}
		pt, ok := sess.open(pkt)
		if !ok {
			continue
		}
		c.lastRecv.Store(time.Now().UnixNano())
		if len(pt) == 0 {
			continue // keepalive
		}
		if t := c.tun.Load(); t != nil {
			if _, err := t.Write(pt); err == nil {
				c.rx.Add(int64(len(pt)))
			}
		}
	}
}

// tunLoop reads one TUN until it is replaced or closed
func (c *client) tunLoop(t *os.File) {
	b := burster{max: c.cfg.BurstMax, delay: time.Duration(c.cfg.BurstDelay), conn: c.conn}
	buf := make([]byte, hdrLen+maxIP+tagLen)
	for {
		n, err := t.Read(buf[hdrLen : hdrLen+maxIP])
		if err != nil {
			return
		}
		sess := c.current.Load()
		if sess == nil {
			continue
		}
		if pkt := sess.seal(c.obfs, buf, n); pkt != nil {
			b.send(pkt, n, c.server)
			c.tx.Add(int64(n))
			c.lastSend.Store(time.Now().UnixNano())
		}
	}
}

// timers: handshakes, rekeying, keepalives and a dead server (sent but heard nothing)
func (c *client) timers() {
	for now := time.Now(); ; now = <-time.After(250 * time.Millisecond) {
		cur := c.current.Load()
		if prev := c.previous.Load(); prev != nil && prev.expired(now) {
			c.previous.CompareAndSwap(prev, nil)
		}
		if cur != nil && cur.expired(now) {
			c.current.CompareAndSwap(cur, nil)
			cur = nil
		}
		need := cur == nil || now.Sub(cur.born) >= rekeyAfter
		if cur != nil {
			last := c.lastRecv.Load()
			if c.lastSend.Load() > last && now.Sub(time.Unix(0, last)) > deadAfter {
				need = true
			}
		}
		c.mu.Lock()
		if need && (c.hsEph == nil || now.Sub(c.hsSent) >= handshakeRetry) {
			c.sendInit()
		}
		c.mu.Unlock()
		if cur != nil && now.Sub(time.Unix(0, c.lastSend.Load())) >= keepalive {
			c.sendKeepalive(cur)
		}
	}
}

func (c *client) status() {
	for range time.Tick(2 * time.Second) {
		up := c.current.Load() != nil && time.Since(time.Unix(0, c.lastRecv.Load())) < 3*keepalive
		b, _ := json.Marshal(map[string]any{"up": up, "rtt": c.rtt.Load(), "rx": c.rx.Load(), "tx": c.tx.Load()})
		c.say("STAT %s", b)
	}
}

// takeTUNs: each connection to the socket brings a TUN (SCM_RIGHTS) that replaces the one before
func (c *client) takeTUNs(path string) error {
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
		if f == nil {
			continue
		}
		if old := c.tun.Swap(f); old != nil {
			old.Close()
		}
		go c.tunLoop(f)
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

// burster sends each datagram and, for small IP packets, the same datagram again after delay; the
// server's replay window drops the second copy. Big packets go once so downloads do not double.
type burster struct {
	max   int
	delay time.Duration
	conn  *net.UDPConn
}

func (b *burster) send(pkt []byte, ipLen int, to netip.AddrPort) {
	_, _ = b.conn.WriteToUDPAddrPort(pkt, to)
	if b.max <= 0 || ipLen > b.max {
		return
	}
	if b.delay == 0 {
		_, _ = b.conn.WriteToUDPAddrPort(pkt, to)
		return
	}
	dup := append([]byte(nil), pkt...)
	time.AfterFunc(b.delay, func() { _, _ = b.conn.WriteToUDPAddrPort(dup, to) })
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
	cfg, err := parseURL(f["url"])
	if err != nil {
		fail(err)
	}
	switch os.Args[1] {
	case "ping":
		// device 0: a probe is no device of its own
		c, err := newClient(cfg, 0)
		if err != nil {
			fail(err)
		}
		c.probe = true
		go c.udpLoop()
		for i := 0; i < 3; i++ {
			c.mu.Lock()
			c.sendInit()
			c.mu.Unlock()
			select {
			case <-c.ready:
				fmt.Println(c.rtt.Load())
				return
			case <-time.After(2 * time.Second):
			}
		}
		fail(errors.New("no answer from " + cfg.Server))
	case "client":
		if f["sock"] == "" {
			fail(errors.New("--sock is missing"))
		}
		c, err := newClient(cfg, deviceID(f["device"], cfg.Key))
		if err != nil {
			fail(err)
		}
		go c.udpLoop()
		go c.timers()
		select {
		case <-c.ready:
		case <-time.After(15 * time.Second):
			fail(errors.New("no answer from " + cfg.Server))
		}
		go c.status()
		fail(c.takeTUNs(f["sock"]))
	default:
		fail(errors.New("unknown command " + os.Args[1]))
	}
}

// peer is the server side's user (session.peer in proto.go); a client has none
type peer struct{}
