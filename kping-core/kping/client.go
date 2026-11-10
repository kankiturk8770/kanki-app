package kping

import (
	"encoding/binary"
	"errors"
	"io"
	"math/rand"
	"net"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Link is a kping:// link of the panel: kping://<key>@host:port?uid=&obfs=&ip=&mtu=&fec=#name
type Link struct {
	Key, Obfs, Host, Name string
	Port                  int
	UID                   uint32
	IP                    net.IP
	MTU, Fec              int
}

func ParseLink(s string) (Link, error) {
	u, err := url.Parse(strings.TrimSpace(s))
	if err != nil || u.Scheme != "kping" || u.User == nil {
		return Link{}, errors.New("not a kping link")
	}
	q := u.Query()
	uid, _ := strconv.Atoi(q.Get("uid"))
	port, _ := strconv.Atoi(u.Port())
	mtu, _ := strconv.Atoi(q.Get("mtu"))
	fec, _ := strconv.Atoi(q.Get("fec"))
	l := Link{Key: u.User.Username(), Obfs: q.Get("obfs"), Host: u.Hostname(), Port: port, UID: uint32(uid), IP: net.ParseIP(q.Get("ip")).To4(), MTU: mtu, Fec: fec, Name: u.Fragment}
	if l.UID == 0 || l.IP == nil || l.Port == 0 {
		return Link{}, errors.New("kping link without uid, ip or port")
	}
	if l.MTU < 1200 || l.MTU > 1500 {
		l.MTU = 1332
	}
	return l, nil
}

// Settings: how the copies go. Auto lets the client choose them from what it measures.
type Settings struct {
	Auto  bool `json:"auto"`
	DupMs int  `json:"dup"`   // one path: the second copy this many ms after the first (0 = none)
	Fec   int  `json:"fec"`   // a repair packet per this many packets (0 = none, 2-8)
	Every bool `json:"every"` // a delayed copy on every path, not only when there is one
}

// Default: what the lab found best for a game on an ordinary line
var Default = Settings{Auto: true, DupMs: 10, Fec: 2}

// one way out of the phone (Wi-Fi or mobile data): its own socket, bound to that network by the app
type path struct {
	name string
	id   byte
	conn net.PacketConn
	to   net.Addr

	// measured
	pings   map[uint32]time.Time
	sent    []bool // recent pings: answered or not (last 20)
	rtt     float64
	jit     float64
	lastAns time.Time
	added   time.Time
	// the relay's word (PONG): its uplink copies that came first / all of them
	upFirst, upTotal uint32
	// downlink: copies that came first on this path / all of them / the ones only this path brought
	downFirst, downTotal, rescued uint64
}

func (p *path) alive() bool {
	return time.Since(p.lastAns) < 3*time.Second || time.Since(p.added) < 2*time.Second
}

func (p *path) loss() float64 {
	if len(p.sent) == 0 {
		return 0
	}
	n := 0
	for _, a := range p.sent {
		if !a {
			n++
		}
	}
	return 100 * float64(n) / float64(len(p.sent))
}

// Client: the phone's end of KPING
type Client struct {
	l    Link
	w    *wire
	sess uint16
	to   *net.UDPAddr

	mu      sync.Mutex
	paths   []*path
	nextID  byte
	tun     io.ReadWriteCloser
	tunWait chan struct{}
	want    Settings // what the user chose
	cur     Settings // what runs now
	pend    Settings // an auto decision waiting to hold
	pendAt  time.Time

	seq, pseq, pingSeq uint32
	tx                 *fecTx
	win                window
	rx                 fecRx
	// downlink delivery per sequence: which paths brought it (for «rescued by»), when it came
	marks map[uint32]downMark
	top   uint32
	any   bool

	upBytes, downBytes uint64
	fixed, lost, got   uint64
	closed             chan struct{}
	once               sync.Once
}

type downMark struct {
	paths byte
	at    time.Time
}

func New(l Link) (*Client, error) {
	w, err := newWire(l.Obfs, l.Key)
	if err != nil {
		return nil, err
	}
	ips, err := net.LookupIP(l.Host)
	if err != nil || len(ips) == 0 {
		return nil, errors.New("kping: cannot find " + l.Host)
	}
	ip := ips[0]
	for _, x := range ips {
		if x.To4() != nil {
			ip = x
			break
		}
	}
	c := &Client{l: l, w: w, sess: uint16(rand.Intn(65534) + 1), to: &net.UDPAddr{IP: ip, Port: l.Port}, nextID: 1, tunWait: make(chan struct{}),
		want: Default, cur: Default, marks: map[uint32]downMark{}, closed: make(chan struct{})}
	if l.Fec >= 2 {
		c.cur.Fec = l.Fec
	}
	c.tx = &fecTx{k: c.cur.Fec}
	go c.pinger()
	go c.ticker()
	return c, nil
}

// Relay: the address the paths send to (the app keeps it out of the VPN)
func (c *Client) Relay() *net.UDPAddr { return c.to }

// Set: the user's choice (Auto, or fixed values)
func (c *Client) Set(s Settings) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if s.Fec == 1 || s.Fec > maxGroup {
		s.Fec = 2
	}
	if s.DupMs > 50 {
		s.DupMs = 50
	}
	c.want = s
	if !s.Auto {
		c.apply(s)
	}
}

func (c *Client) apply(s Settings) {
	s.Auto = c.want.Auto
	if s.Fec != c.cur.Fec {
		c.tx = &fecTx{k: s.Fec}
	}
	c.cur = s
}

// AddPath: a socket on one network (named "wifi", "cell", …); a path of the same name is replaced
func (c *Client) AddPath(name string, conn net.PacketConn) { c.AddPathTo(name, conn, c.to) }

// AddPathTo: the same, to another address of the relay (the lab puts a bad line in front of it)
func (c *Client) AddPathTo(name string, conn net.PacketConn, to net.Addr) {
	c.mu.Lock()
	for i, p := range c.paths {
		if p.name == name {
			p.conn.Close()
			c.paths = append(c.paths[:i], c.paths[i+1:]...)
			break
		}
	}
	p := &path{name: name, id: c.nextID, conn: conn, to: to, pings: map[uint32]time.Time{}, added: time.Now()}
	c.nextID = c.nextID%15 + 1
	c.paths = append(c.paths, p)
	c.mu.Unlock()
	go c.reader(p)
	// a burst of game-sized PINGs: the first measure of this path
	go func() {
		for i := 0; i < 20; i++ {
			c.ping(p, 120)
			time.Sleep(25 * time.Millisecond)
		}
	}()
}

// DropPath: that network went away
func (c *Client) DropPath(name string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	for i, p := range c.paths {
		if p.name == name {
			p.conn.Close()
			c.paths = append(c.paths[:i], c.paths[i+1:]...)
			return
		}
	}
}

// SetTUN: the VPN's interface (only PUBG and what it needs is sent into it by the app)
func (c *Client) SetTUN(t io.ReadWriteCloser) {
	c.mu.Lock()
	old := c.tun
	c.tun = t
	first := old == nil
	c.mu.Unlock()
	if first {
		close(c.tunWait)
	}
	if old != nil {
		old.Close()
	}
	go c.tunLoop(t)
}

func (c *Client) Close() {
	c.once.Do(func() {
		close(c.closed)
		c.mu.Lock()
		for _, p := range c.paths {
			p.conn.Close()
		}
		if c.tun != nil {
			c.tun.Close()
		}
		c.mu.Unlock()
	})
}

// ------------------------------------------------------------------ up: TUN → every path

func (c *Client) tunLoop(t io.ReadWriteCloser) {
	b := make([]byte, 65536)
	for {
		n, err := t.Read(b)
		if err != nil {
			return
		}
		if n < 20 || b[0]>>4 != 4 {
			continue // IPv4 only: the server gives no IPv6
		}
		c.Send(b[:n])
	}
}

// Send: one IP packet out, on every path (or twice on one), with its parity
func (c *Client) Send(p []byte) {
	c.mu.Lock()
	seq := c.seq
	c.seq++
	c.upBytes += uint64(len(p))
	paths, cur := c.live(), c.cur
	fecWant := byte(cur.Fec)
	if cur.Fec == 0 {
		fecWant = 1
	}
	n, par := c.tx.add(seq, p)
	var pseq uint32
	if par != nil {
		pseq = c.pseq
		c.pseq++
	}
	c.mu.Unlock()
	for _, x := range paths {
		pkt := c.w.seal(hdr{c.l.UID, c.sess, seq, kData, x.id | fecWant<<4}, p)
		c.out(x, pkt, len(paths), cur)
	}
	if par != nil {
		c.sendParity(paths, pseq, n, par, cur)
	}
}

func (c *Client) sendParity(paths []*path, pseq uint32, n byte, par []byte, cur Settings) {
	pkt := c.w.seal(hdr{c.l.UID, c.sess, pseq, kParity, n}, par)
	for _, x := range paths {
		c.out(x, pkt, len(paths), cur)
	}
}

// out: now, and the second copy later when there is one path (or always, with Every)
func (c *Client) out(x *path, pkt []byte, npaths int, cur Settings) {
	x.conn.WriteTo(pkt, x.to)
	if cur.DupMs > 0 && (npaths == 1 || cur.Every) {
		time.AfterFunc(time.Duration(cur.DupMs)*time.Millisecond, func() { x.conn.WriteTo(pkt, x.to) })
	}
}

// the paths to send on: the live ones; none live (all just went quiet): every path, a copy may still pass
func (c *Client) live() []*path {
	var l []*path
	for _, p := range c.paths {
		if p.alive() {
			l = append(l, p)
		}
	}
	if len(l) == 0 {
		l = append(l, c.paths...)
	}
	return l
}

// ------------------------------------------------------------------ down: every path → TUN

func (c *Client) reader(p *path) {
	b := make([]byte, 4096)
	for {
		n, _, err := p.conn.ReadFrom(b)
		if err != nil {
			return
		}
		h, pl, ok := c.w.open(b[:n])
		if !ok || h.kind&down == 0 || h.sess != c.sess || h.uid != c.l.UID {
			continue
		}
		switch h.kind &^ down {
		case kPong:
			c.pong(p, h, pl)
		case kData:
			c.data(p, h.seq, pl)
		case kParity:
			c.mu.Lock()
			s, rp, ok := c.rx.parity(h.aux, pl, &c.win)
			if ok {
				c.win.take(s)
				c.fixed++
			}
			t := c.tun
			c.mu.Unlock()
			if ok && t != nil {
				c.deliver(t, rp)
			}
		}
	}
}

func (c *Client) data(p *path, seq uint32, pl []byte) {
	c.mu.Lock()
	p.downTotal++
	m := c.marks[seq]
	m.paths |= 1 << (p.id % 8)
	first := c.win.take(seq)
	if first {
		m.at = time.Now()
		p.downFirst++
		c.got++
		if !c.any || seq > c.top {
			c.top, c.any = seq, true
		}
	}
	c.marks[seq] = m
	var rs uint32
	var rp []byte
	ok := false
	if first {
		rs, rp, ok = c.rx.data(seq, pl, &c.win)
		if ok {
			c.win.take(rs)
			c.fixed++
		}
	}
	t := c.tun
	c.mu.Unlock()
	if first && t != nil {
		c.deliver(t, pl)
		if ok {
			c.deliver(t, rp)
		}
	}
}

func (c *Client) deliver(t io.ReadWriteCloser, p []byte) {
	c.mu.Lock()
	c.downBytes += uint64(len(p))
	c.mu.Unlock()
	t.Write(p)
}

// ------------------------------------------------------------------ PING / PONG

func (c *Client) ping(p *path, size int) {
	c.mu.Lock()
	s := c.pingSeq
	c.pingSeq++
	p.pings[s] = time.Now()
	cur := c.cur
	c.mu.Unlock()
	pl := make([]byte, 10, max(size, 10))
	binary.BigEndian.PutUint64(pl, uint64(time.Now().UnixMilli()))
	pl[8] = byte(cur.DupMs)
	if cur.Every {
		pl[9] = 1
	}
	for len(pl) < size {
		pl = append(pl, byte(rand.Intn(256)))
	}
	p.conn.WriteTo(c.w.seal(hdr{c.l.UID, c.sess, s, kPing, p.id}, pl), p.to)
}

func (c *Client) pong(p *path, h hdr, pl []byte) {
	c.mu.Lock()
	defer c.mu.Unlock()
	at, ok := p.pings[h.seq]
	if !ok {
		return
	}
	delete(p.pings, h.seq)
	rtt := float64(time.Since(at)) / 1e6
	if p.rtt == 0 {
		p.rtt = rtt
	} else {
		d := rtt - p.rtt
		if d < 0 {
			d = -d
		}
		p.jit += (d - p.jit) / 8
		p.rtt += (rtt - p.rtt) / 8
	}
	p.lastAns = time.Now()
	p.mark(true)
	// [clock 8 | n | n × (id, first 4, total 4)]
	if len(pl) >= 9 {
		n := int(pl[8])
		for i := 0; i < n && len(pl) >= 9+(i+1)*9; i++ {
			e := pl[9+i*9:]
			if e[0] == p.id {
				p.upFirst, p.upTotal = binary.BigEndian.Uint32(e[1:]), binary.BigEndian.Uint32(e[5:])
			}
		}
	}
}

func (p *path) mark(ok bool) {
	p.sent = append(p.sent, ok)
	if len(p.sent) > 20 {
		p.sent = p.sent[len(p.sent)-20:]
	}
}

// a PING on every path each second (keeps the NAT open and measures); an unanswered one counts as lost
func (c *Client) pinger() {
	t := time.NewTicker(time.Second)
	defer t.Stop()
	for {
		select {
		case <-c.closed:
			return
		case <-t.C:
		}
		c.mu.Lock()
		ps := append([]*path(nil), c.paths...)
		for _, p := range ps {
			for s, at := range p.pings {
				if time.Since(at) > 1500*time.Millisecond {
					delete(p.pings, s)
					p.mark(false)
				}
			}
		}
		c.mu.Unlock()
		for _, p := range ps {
			c.ping(p, 120)
		}
	}
}

// ------------------------------------------------------------------ parity flush, the books and the automatic choice

func (c *Client) ticker() {
	t := time.NewTicker(2 * time.Millisecond)
	defer t.Stop()
	lastBook := time.Now()
	for {
		select {
		case <-c.closed:
			return
		case <-t.C:
		}
		c.mu.Lock()
		n, par := c.tx.flush()
		var pseq uint32
		if par != nil {
			pseq = c.pseq
			c.pseq++
		}
		paths, cur := c.live(), c.cur
		c.mu.Unlock()
		if par != nil {
			c.sendParity(paths, pseq, n, par, cur)
		}
		if time.Since(lastBook) >= time.Second {
			lastBook = time.Now()
			c.book()
			c.tune()
		}
	}
}

// book: packets older than half a second are settled: lost, or brought by one path only
func (c *Client) book() {
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.any {
		return
	}
	n := len(c.paths)
	for s, m := range c.marks {
		if c.top-s < 64 && time.Since(m.at) < 500*time.Millisecond {
			continue
		}
		if n >= 2 && m.paths != 0 && m.paths&(m.paths-1) == 0 {
			for _, p := range c.paths {
				if m.paths == 1<<(p.id%8) {
					p.rescued++
				}
			}
		}
		delete(c.marks, s)
	}
}

// tune: the automatic choice, from what each path shows. More protection at once when the line gets worse,
// less only after it has held for 10 s, so a short dip changes nothing back and forth.
func (c *Client) tune() {
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.want.Auto {
		return
	}
	alive := 0
	worst, best := 0.0, 100.0
	for _, p := range c.paths {
		if !p.alive() {
			continue
		}
		alive++
		l := p.loss()
		if l > worst {
			worst = l
		}
		if l < best {
			best = l
		}
	}
	if alive == 0 {
		return
	}
	s := Settings{Auto: true, DupMs: 10, Fec: 2}
	switch {
	case alive == 1 && worst >= 5:
		s.DupMs = 20 // long outages: a copy farther away survives them
	case alive == 1 && worst < 0.5:
		s.DupMs = 5
	}
	if worst < 0.5 {
		s.Fec = 4
	}
	// both paths bad at once: a delayed copy on each too
	s.Every = alive >= 2 && best >= 3
	more := s.Fec < c.cur.Fec && s.Fec != 0 || s.DupMs > c.cur.DupMs || (s.Every && !c.cur.Every)
	if s == c.cur {
		c.pendAt = time.Time{}
		return
	}
	if more {
		c.apply(s)
		c.pendAt = time.Time{}
		return
	}
	if s != c.pend || c.pendAt.IsZero() {
		c.pend, c.pendAt = s, time.Now()
		return
	}
	if time.Since(c.pendAt) >= 10*time.Second {
		c.apply(s)
		c.pendAt = time.Time{}
	}
}

// ------------------------------------------------------------------ for the app's screen

type PathStat struct {
	Name      string  `json:"name"`
	Alive     bool    `json:"alive"`
	RTT       float64 `json:"rtt"`
	Jitter    float64 `json:"jit"`
	Loss      float64 `json:"loss"`
	UpFirst   uint32  `json:"up_first"`
	UpTotal   uint32  `json:"up_total"`
	DownFirst uint64  `json:"down_first"`
	Rescued   uint64  `json:"rescued"`
}

type Stat struct {
	Up       bool       `json:"up"`
	Ping     float64    `json:"ping"` // the best live path's round trip (ms)
	Jitter   float64    `json:"jit"`
	Paths    []PathStat `json:"paths"`
	Rx       uint64     `json:"rx"`
	Tx       uint64     `json:"tx"`
	Got      uint64     `json:"got"`     // game packets that came back
	Fixed    uint64     `json:"fixed"`   // rebuilt with parity
	Rescued  uint64     `json:"rescued"` // brought by one path only (the other lost it)
	Settings Settings   `json:"settings"`
}

func (c *Client) Stat() Stat {
	c.mu.Lock()
	defer c.mu.Unlock()
	s := Stat{Rx: c.downBytes, Tx: c.upBytes, Got: c.got, Fixed: c.fixed, Settings: c.cur}
	best := 0.0
	for _, p := range c.paths {
		ps := PathStat{Name: p.name, Alive: p.alive() && !p.lastAns.IsZero(), RTT: p.rtt, Jitter: p.jit, Loss: p.loss(), UpFirst: p.upFirst, UpTotal: p.upTotal, DownFirst: p.downFirst, Rescued: p.rescued}
		s.Paths = append(s.Paths, ps)
		s.Rescued += p.rescued
		if ps.Alive {
			s.Up = true
			if best == 0 || p.rtt < best {
				best, s.Jitter = p.rtt, p.jit
			}
		}
	}
	s.Ping = best
	sort.Slice(s.Paths, func(i, j int) bool { return s.Paths[i].Name > s.Paths[j].Name })
	return s
}

// Ping: a few PINGs straight to the relay from a plain socket (no VPN yet); the best round trip in ms.
// The relay answers only a PING made with this user's key, so an answer also means the key is right.
func Ping(l Link) (int64, error) {
	w, err := newWire(l.Obfs, l.Key)
	if err != nil {
		return 0, err
	}
	ra, err := net.ResolveUDPAddr("udp", net.JoinHostPort(l.Host, strconv.Itoa(l.Port)))
	if err != nil {
		return 0, err
	}
	conn, err := net.DialUDP("udp", nil, ra)
	if err != nil {
		return 0, err
	}
	defer conn.Close()
	sess := uint16(rand.Intn(65534) + 1)
	best := int64(-1)
	b := make([]byte, 2048)
	for i := uint32(0); i < 3; i++ {
		pl := make([]byte, 10)
		binary.BigEndian.PutUint64(pl, uint64(time.Now().UnixMilli()))
		pl[8] = 0xff
		t0 := time.Now()
		conn.Write(w.seal(hdr{l.UID, sess, i, kPing, 1}, pl))
		_ = conn.SetReadDeadline(time.Now().Add(1500 * time.Millisecond))
		for {
			n, err := conn.Read(b)
			if err != nil {
				break
			}
			h, _, ok := w.open(b[:n])
			if ok && h.kind == kPong|down && h.sess == sess && h.seq == i {
				if ms := time.Since(t0).Milliseconds(); best < 0 || ms < best {
					best = max(ms, 1)
				}
				break
			}
		}
	}
	if best < 0 {
		return 0, errors.New("the relay did not answer")
	}
	return best, nil
}
