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
	"sync/atomic"
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
	Fec   int  `json:"fec"`   // v2 servers: a repair packet per this many packets (0 = none, 2-8)
	Every bool `json:"every"` // a delayed copy on every path, not only when there is one
	// keep the mobile radio awake while a match runs (a tiny packet when the game is silent 45 ms)
	Radio *bool `json:"radio,omitempty"`
}

func (s Settings) radio() bool { return s.Radio == nil || *s.Radio }

// Default: what the lab found best for a game on an ordinary line
var Default = Settings{Auto: true, DupMs: 10, Fec: 2}

const (
	capRLC     = 1 // this end speaks KPING v3 (sliding-window repairs, kind 5)
	darkMin    = 80 * time.Millisecond
	darkMax    = 400 * time.Millisecond
	fillGap    = 45 * time.Millisecond
	fastPing   = 60 * time.Millisecond
	outageKeep = 32
	maxCopyMs  = 40
	busyTick   = 2 * time.Millisecond
	idleTick   = 50 * time.Millisecond
	busyFor    = time.Second // the clock runs fast this long after the last packet up
	// an outage counts for a minute: a line that had one then and none since is clean again
	outageMemory = time.Minute
	minCopyMs    = 6
	maxRatio     = 2.0
	bloatMargin  = 80.0 // ms over the path's best round trip: its queue is filling, add nothing
)

// one way out of the phone (Wi-Fi or mobile data): its own socket, bound to that network by the app
type path struct {
	name string
	id   byte
	conn net.PacketConn
	to   net.Addr

	// measured by PINGs (and how many DATA copies had left on this path when each PING left)
	pings   map[uint32]pingRec
	sent    []bool // recent pings: answered or not (last 20)
	rtt     float64
	minRTT  float64
	minAt   time.Time
	jit     float64
	lastAns time.Time
	added   time.Time
	// the relay's word (PONG): its uplink copies that came first / all of them
	upFirst, upTotal uint32
	// uplink loss: DATA copies sent on this path before a PING against those the relay had counted when that
	// PING came (the same moment on both ends: the packets still on their way are not counted as lost)
	sentData           uint32
	pongSent, pongSeen uint32
	pongAny            bool
	upLoss             float64 // %, may dip a little below 0 (noise); shown and used as 0 or more
	// downlink: copies that came first on this path / all of them / the ones only this path brought
	downFirst, downTotal, rescued uint64
	lastDown                      time.Time
	downIAT                       float64 // ms between packets that come down this path
	dark                          bool
	darkAt                        time.Time
	lastFast                      time.Time
	lastUp                        time.Time
	outages                       uint64
}

type pingRec struct {
	at   time.Time
	sent uint32
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

// the loss to plan with: what the relay counted, or the PINGs when there is no count yet
func (p *path) lossNow() float64 {
	if p.pongAny {
		return max(p.upLoss, p.loss()/2, 0)
	}
	return p.loss()
}

// how long this path may stay silent while the game runs before it counts as dark
func (p *path) darkAfter() time.Duration {
	d := time.Duration(4*p.downIAT) * time.Millisecond
	return min(max(d, darkMin), darkMax)
}

func (p *path) score() float64 {
	return p.rtt + 2*p.jit + 3*p.lossNow()
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

	seq, pseq, rseq, pingSeq uint32
	tx                       *fecTx
	win                      window
	rx                       fecRx
	// v3
	cls      *classifier
	caps     bool // the relay speaks v3
	exitV3   bool // the exit sent a v3 repair: it rebuilds ours too
	rtx      rlcTx
	rrx      rlcRx
	credit   float64
	ratio    float64 // repairs per protected packet, up
	pending  int     // protected packets since the last repair
	lastData time.Time
	gap      float64 // ms between protected packets
	rot      int
	outage   [outageKeep]float64
	outAt    [outageKeep]time.Time
	outN     int
	tunRTT   float64
	tunLoss  float64
	// downlink loss before repairs (what the line does) and the request to the exit
	downLoss          float64
	dWinTop, dWinGot  uint32
	dWinAny           bool
	dWinStart         time.Time
	downLevel         byte
	fills, stale      uint64
	sentCls           [nClass]uint64
	allDarkAt         time.Time
	allDark           bool
	repairsUp, copies uint64
	copyAt            time.Time
	later             []out // delayed copies, drained by the clock
	kick              chan struct{}
	lastSend          time.Time
	txPkts            atomic.Uint64 // every packet written to a path (copies, repairs and PINGs too)
	// downlink delivery per sequence: which paths brought it (for «rescued by»), when it came — a ring,
	// no map entry made and dropped for every packet
	marks [markRing]downMark
	top   uint32
	any   bool

	upBytes, downBytes uint64
	fixed, lost, got   uint64
	closed             chan struct{}
	once               sync.Once
}

type downMark struct {
	seq   uint32
	paths byte
	used  bool
	at    time.Time
}

const markRing = 1024

// settle: a packet's delivery is final: brought by one path only while two ran = rescued by that path
func (c *Client) settle(m *downMark) {
	if len(c.paths) >= 2 && m.paths != 0 && m.paths&(m.paths-1) == 0 {
		for _, p := range c.paths {
			if m.paths == 1<<(p.id%8) {
				p.rescued++
			}
		}
	}
	*m = downMark{}
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
		want: Default, cur: Default, closed: make(chan struct{}), cls: newClassifier(), ratio: 0.5, kick: make(chan struct{}, 1)}
	if l.Fec >= 2 {
		c.cur.Fec = l.Fec
	}
	c.tx = &fecTx{k: c.cur.Fec}
	c.rrx.init()
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
	} else {
		c.cur.Radio = s.Radio
	}
}

func (c *Client) apply(s Settings) {
	s.Auto = c.want.Auto
	s.Radio = c.want.Radio
	if s.Fec != c.cur.Fec {
		c.tx = &fecTx{k: s.Fec}
	}
	c.cur = s
}

// AddPath: a socket on one network (named "wifi", "cell", …); a path of the same name is replaced
func (c *Client) AddPath(name string, conn net.PacketConn) { c.AddPathTo(name, conn, c.to) }

// AddPathTo: the same, to another address of the relay (the lab puts a bad line in front of it)
func (c *Client) AddPathTo(name string, conn net.PacketConn, to net.Addr) {
	tune(conn)
	c.mu.Lock()
	for i, p := range c.paths {
		if p.name == name {
			p.conn.Close()
			c.paths = append(c.paths[:i], c.paths[i+1:]...)
			break
		}
	}
	p := &path{name: name, id: c.nextID, conn: conn, to: to, pings: map[uint32]pingRec{}, added: time.Now()}
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

// ------------------------------------------------------------------ up: TUN → the paths

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

// usable: the paths to send on — alive and not dark; none (all dark or quiet): every path, a copy may pass
func (c *Client) usable() []*path {
	var l []*path
	for _, p := range c.paths {
		if p.alive() && !p.dark {
			l = append(l, p)
		}
	}
	if len(l) == 0 {
		return c.live()
	}
	sort.SliceStable(l, func(i, j int) bool { return l[i].score() < l[j].score() })
	return l
}

// the paths alive at all; none: every path
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

type out struct {
	p     *path
	pkt   []byte
	delay time.Duration
	until time.Time
	due   time.Time
	data  bool // a DATA copy: counted for the uplink loss when it leaves
}

// Send: one IP packet out. Its class chooses the paths, a delayed copy and its share of the repairs.
func (c *Client) Send(p []byte) {
	now := time.Now()
	c.mu.Lock()
	cl := c.cls.classify(p, now)
	idle := now.Sub(c.lastSend) > busyFor
	c.lastSend = now
	seq := c.seq
	c.seq++
	c.upBytes += uint64(len(p))
	c.sentCls[cl]++
	cur := c.cur
	ps := c.usable()
	all := c.live()
	ttl := classTTL[cl]
	deadline := now.Add(ttl)
	fight := c.cls.fight(now)
	v3 := c.caps && c.exitV3
	var targets []*path
	copyOn := (*path)(nil)
	if len(ps) > 0 {
		best := ps[0]
		switch cl {
		case clsGame, clsCtl:
			// on every path that is alive at all, a dark one too: it may already be back before we hear it,
			// and a tiny packet there costs nothing (a dark path only gets no copies and no repairs)
			targets = ps
			if len(all) > len(ps) {
				targets = all
			}
		case clsState:
			targets = ps[:1]
			if len(ps) >= 2 && (best.lossNow() >= 1 || fight) {
				targets = ps[:2]
			}
		case clsVoice:
			targets = ps[:1]
			if len(ps) >= 2 && best.lossNow() >= 3 {
				targets = ps[:2]
			}
		default:
			targets = ps[:1]
		}
		// a delayed copy: when one path carries it and this line shows it needs one, or every path is bad at
		// once. A clean line gets none: the repairs rebuild a stray loss, a copy would only double the packets.
		if cl != clsBulk && cur.DupMs > 0 && !c.allDark {
			switch {
			case len(targets) == 1 && c.needCopy(best, cl, fight, now, v3):
				copyOn = best
			case len(targets) >= 2 && cur.Every:
				copyOn = best
			}
		}
	}
	// v2 parity (XOR groups of every packet), or v3 repairs over the packets worth saving
	var n byte
	var par []byte
	var pseq uint32
	if !v3 {
		n, par = c.tx.add(seq, p)
		if par != nil {
			pseq = c.pseq
			c.pseq++
		}
	} else if cl != clsBulk && !c.allDark {
		c.rtx.add(seq, p, deadline)
		w := [nClass]float64{1, 0.8, 0.5, 0.5, 0}[cl]
		c.credit = min(c.credit+c.ratio*w, maxRatio*2)
		c.pending++
		if !c.lastData.IsZero() {
			c.gap += (float64(now.Sub(c.lastData))/1e6 - c.gap) / 8
		}
		c.lastData = now
	}
	want := c.wantDown()
	copyMs := c.copyDelay(cur)
	var outs []out
	for _, x := range targets {
		pkt := c.w.seal(hdr{c.l.UID, c.sess, seq, kData, x.id | want<<4}, p)
		outs = append(outs, out{p: x, pkt: pkt})
		x.sentData++
		x.lastUp = now
	}
	if copyOn != nil && len(outs) > 0 {
		pkt := outs[0].pkt
		for _, o := range outs {
			if o.p == copyOn {
				pkt = o.pkt
			}
		}
		outs = append(outs, out{p: copyOn, pkt: pkt, delay: time.Duration(copyMs) * time.Millisecond, until: deadline, data: true})
		c.copies++
		c.copyAt = now
	}
	paths := ps
	c.mu.Unlock()
	if idle {
		c.wake()
	}
	c.emit(outs)
	if par != nil {
		c.sendParity(paths, pseq, n, par, cur)
	}
}

// emit: now, or later — a later copy waits in the queue the clock drains (no timer per packet)
func (c *Client) emit(outs []out) {
	var later bool
	for _, o := range outs {
		if o.delay == 0 {
			c.write(o.p, o.pkt)
			continue
		}
		c.mu.Lock()
		c.later = append(c.later, o)
		c.later[len(c.later)-1].due = time.Now().Add(o.delay)
		c.mu.Unlock()
		later = true
	}
	if later {
		c.wake()
	}
}

func (c *Client) write(p *path, pkt []byte) {
	p.conn.WriteTo(pkt, p.to)
	c.txPkts.Add(1)
}

// dueLater: the queued copies whose time came — only while still worth something and their path is not dark
func (c *Client) dueLater(now time.Time) []out {
	var go_ []out
	k := 0
	for _, o := range c.later {
		if now.Before(o.due) {
			c.later[k] = o
			k++
			continue
		}
		if now.Before(o.until) && !o.p.dark {
			if o.data {
				o.p.sentData++
			}
			o.delay = 0
			go_ = append(go_, o)
		}
	}
	clear(c.later[k:])
	c.later = c.later[:k]
	return go_
}

// wake: the clock sleeps long while nothing runs; a packet to send later wakes it
func (c *Client) wake() {
	select {
	case c.kick <- struct{}{}:
	default:
	}
}

func (c *Client) sendParity(paths []*path, pseq uint32, n byte, par []byte, cur Settings) {
	pkt := c.w.seal(hdr{c.l.UID, c.sess, pseq, kParity, n}, par)
	var outs []out
	for _, x := range paths {
		outs = append(outs, out{p: x, pkt: pkt})
		if cur.DupMs > 0 && (len(paths) == 1 || cur.Every) {
			outs = append(outs, out{p: x, pkt: pkt, delay: time.Duration(cur.DupMs) * time.Millisecond, until: time.Now().Add(time.Second)})
		}
	}
	c.emit(outs)
}

// needCopy: does a packet of this class on this one path get a delayed copy? Only on a line that loses
// packets now (counted by the relay), or whose game packets stopped coming for a moment lately (outages a
// repair cannot fill in time); in a fight a little sooner. Fixed settings (not automatic) and v2 relays
// keep the old rule: a copy for every game packet.
func (c *Client) needCopy(best *path, cl class, fight bool, now time.Time, v3 bool) bool {
	l, ro := best.lossNow(), c.recentOutages(now)
	if !c.want.Auto || !v3 {
		return cl == clsGame || cl == clsCtl || l >= 0.5 || fight || ro > 0
	}
	switch cl {
	case clsGame, clsCtl:
		return l >= 1 || ro >= 2 || (fight && (l >= 0.3 || ro >= 1))
	default:
		return l >= 3 || ro >= 3
	}
}

// copyDelay: the second copy goes after the outages this line usually has, so one outage cannot take both
func (c *Client) copyDelay(cur Settings) int {
	if !c.want.Auto {
		return cur.DupMs
	}
	var v []float64
	now := time.Now()
	for i := 0; i < min(c.outN, outageKeep); i++ {
		if now.Sub(c.outAt[i]) < outageMemory {
			v = append(v, c.outage[i])
		}
	}
	if len(v) < 4 {
		return cur.DupMs
	}
	sort.Float64s(v)
	p75 := v[(len(v)*3)/4]
	return int(min(max(p75+4, minCopyMs), maxCopyMs))
}

// recentOutages: how many times the game's packets stopped coming down in the last minute
func (c *Client) recentOutages(now time.Time) int {
	n := 0
	for i := 0; i < min(c.outN, outageKeep); i++ {
		if now.Sub(c.outAt[i]) < outageMemory {
			n++
		}
	}
	return n
}

// wantDown: what the phone asks the exit for, in the high bits of DATA's aux. v2: a parity group
// (0 = the server's, 1 = none, 2-8); v3: 9-15, a repair rate the exit's v3 code reads (a v2 exit takes
// it as a group of 8, which still works)
func (c *Client) wantDown() byte {
	if !c.caps {
		if c.cur.Fec == 0 {
			return 1
		}
		return byte(c.cur.Fec)
	}
	return c.downLevel
}

// levels 9-15 → repairs per packet (the panel's kpingd uses the same table)
var downRatios = [7]float64{0.25, 0.5, 0.75, 1, 1.5, 2, 3}

func levelFor(loss float64, fight bool) byte {
	var r float64
	switch {
	case loss < 0.3:
		r = 0.25
	case loss < 1:
		r = 0.5
	case loss < 3:
		r = 0.75
	case loss < 6:
		r = 1
	case loss < 10:
		r = 1.5
	case loss < 18:
		r = 2
	default:
		r = 3
	}
	if fight && r < 1 {
		r += 0.25
	}
	l := byte(9)
	for i, x := range downRatios {
		if x <= r {
			l = byte(9 + i)
		}
	}
	return l
}

// ------------------------------------------------------------------ down: the paths → TUN

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
		c.heard(p, h.kind&^down == kData)
		switch h.kind &^ down {
		case kPong:
			c.pong(p, h, pl)
		case kData:
			c.data(p, h.seq, pl)
		case kParity:
			c.mu.Lock()
			s, rp, ok := c.rx.parity(h.aux, pl, &c.win)
			var more []rebuilt
			if ok {
				c.win.take(s)
				c.fixed++
				if c.caps {
					more = c.rrx.data(s, rp, time.Now())
				}
			}
			more = c.takeRebuilt(more)
			t := c.tun
			c.mu.Unlock()
			if ok && t != nil {
				c.deliver(t, rp)
			}
			c.deliverAll(t, more)
		case kRepair:
			c.mu.Lock()
			c.exitV3 = true
			more := c.takeRebuilt(c.rrx.repair(pl, time.Now()))
			t := c.tun
			c.mu.Unlock()
			c.deliverAll(t, more)
		}
	}
}

// heard: a packet came down this path — it is not dark, and a silence it ends was an outage. Only a game
// packet ends one, and only while the phone kept sending: a pause of the game itself (a round ends, a
// loading screen) is quiet both ways and is no fault of the line.
func (c *Client) heard(p *path, data bool) {
	now := time.Now()
	c.mu.Lock()
	defer c.mu.Unlock()
	if !p.lastDown.IsZero() && data {
		gap := float64(now.Sub(p.lastDown)) / 1e6
		sending := now.Sub(c.lastSend) < 100*time.Millisecond && c.cls.gaming(now)
		// the outage itself: the silence minus the usual spacing of the game's packets
		if lost := gap - p.downIAT; sending && (p.dark || (lost > 15 && p.downIAT > 0 && gap > 3*p.downIAT)) {
			c.outage[c.outN%outageKeep] = max(lost, 0)
			c.outAt[c.outN%outageKeep] = now
			c.outN++
			p.outages++
		}
		if gap < 200 {
			p.downIAT += (gap - p.downIAT) / 16
		}
	}
	// a PONG does not end a silence of the game's packets: dark means the game's packets stopped
	if data || p.lastDown.IsZero() {
		p.lastDown = now
		p.dark = false
	}
}

// takeRebuilt: the packets the repairs gave back that are new to the window
func (c *Client) takeRebuilt(r []rebuilt) []rebuilt {
	var out []rebuilt
	for _, x := range r {
		if c.win.take(x.seq) {
			c.fixed++
			out = append(out, x)
		}
	}
	return out
}

func (c *Client) deliverAll(t io.ReadWriteCloser, r []rebuilt) {
	if t == nil {
		return
	}
	for _, x := range r {
		c.deliver(t, x.p)
	}
}

func (c *Client) data(p *path, seq uint32, pl []byte) {
	now := time.Now()
	c.mu.Lock()
	p.downTotal++
	m := &c.marks[seq%markRing]
	if m.used && m.seq != seq {
		if int32(seq-m.seq) < 0 {
			m = &downMark{} // older than the ring: only the window matters for it
		} else {
			c.settle(m)
		}
	}
	m.seq, m.used = seq, true
	m.paths |= 1 << (p.id % 8)
	first := c.win.take(seq)
	if first {
		m.at = now
		p.downFirst++
		c.got++
		if !c.any || seq > c.top {
			c.top, c.any = seq, true
		}
		if c.dWinAny && int32(seq-c.dWinTop) > 0 {
			c.dWinGot++
		}
	}
	var rs uint32
	var rp []byte
	ok := false
	var more []rebuilt
	if first {
		rs, rp, ok = c.rx.data(seq, pl, &c.win)
		if ok {
			c.win.take(rs)
			c.fixed++
		}
		if c.caps {
			more = c.rrx.data(seq, pl, now)
			if ok {
				more = append(more, c.rrx.data(rs, rp, now)...)
			}
			more = c.takeRebuilt(more)
		}
	}
	t := c.tun
	c.mu.Unlock()
	if first && t != nil {
		c.deliver(t, pl)
		if ok {
			c.deliver(t, rp)
		}
		c.deliverAll(t, more)
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
	p.pings[s] = pingRec{time.Now(), p.sentData}
	cur := c.cur
	copyMs := c.downCopy(c.copyDelay(cur), time.Now())
	c.mu.Unlock()
	pl := make([]byte, 11, max(size, 11))
	binary.BigEndian.PutUint64(pl, uint64(time.Now().UnixMilli()))
	pl[8] = byte(copyMs)
	if cur.Every {
		pl[9] = 1
	}
	pl[10] = capRLC
	for len(pl) < size {
		pl = append(pl, byte(rand.Intn(256)))
	}
	c.write(p, c.w.seal(hdr{c.l.UID, c.sess, s, kPing, p.id}, pl))
}

func (c *Client) pong(p *path, h hdr, pl []byte) {
	c.mu.Lock()
	defer c.mu.Unlock()
	rec, ok := p.pings[h.seq]
	if !ok {
		return
	}
	at := rec.at
	delete(p.pings, h.seq)
	now := time.Now()
	rtt := float64(now.Sub(at)) / 1e6
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
	if p.minRTT == 0 || rtt < p.minRTT || now.Sub(p.minAt) > 30*time.Second {
		p.minRTT, p.minAt = rtt, now
	}
	p.lastAns = now
	p.mark(true)
	// [clock 8 | n | n × (id, first 4, total 4) | v3: caps 1 | tunnel rtt ms 2 | tunnel loss ‰ 2]
	if len(pl) >= 9 {
		n := int(pl[8])
		for i := 0; i < n && len(pl) >= 9+(i+1)*9; i++ {
			e := pl[9+i*9:]
			if e[0] == p.id {
				first, total := binary.BigEndian.Uint32(e[1:]), binary.BigEndian.Uint32(e[5:])
				p.upFirst, p.upTotal = first, total
				// uplink loss: what left this path before the PING against what the relay had when the PING came,
				// over at least 40 packets; a small negative is noise and stays in the average (cutting it at 0
				// would turn the noise into a loss that is not there)
				switch {
				case !p.pongAny || total < p.pongSeen || rec.sent < p.pongSent:
					p.pongSent, p.pongSeen, p.pongAny = rec.sent, total, true
				case rec.sent-p.pongSent >= 40:
					sent := float64(rec.sent - p.pongSent)
					seen := float64(total - p.pongSeen)
					l := min(100, 100*(1-seen/sent))
					p.upLoss += (l - p.upLoss) / 3
					p.pongSent, p.pongSeen = rec.sent, total
				}
			}
		}
		rest := pl[min(len(pl), 9+n*9):]
		if len(rest) >= 1 {
			c.caps = rest[0]&capRLC != 0
		}
		if len(rest) >= 5 {
			c.tunRTT = float64(binary.BigEndian.Uint16(rest[1:]))
			c.tunLoss = float64(binary.BigEndian.Uint16(rest[3:])) / 10
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
			for s, r := range p.pings {
				if time.Since(r.at) > 1500*time.Millisecond {
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

// ------------------------------------------------------------------ the clock: repairs, dark paths, radio, books

func (c *Client) ticker() {
	t := time.NewTimer(idleTick)
	defer t.Stop()
	lastBook := time.Now()
	for {
		select {
		case <-c.closed:
			return
		case <-t.C:
		case <-c.kick:
			if !t.Stop() {
				select {
				case <-t.C:
				default:
				}
			}
		}
		now := time.Now()
		c.mu.Lock()
		n, par := c.tx.flush()
		var pseq uint32
		if par != nil {
			pseq = c.pseq
			c.pseq++
		}
		paths, cur := c.live(), c.cur
		c.watch(now)
		due := c.dueLater(now)
		reps := c.repairs(now)
		fills, fast := c.radio(now)
		next := c.tick(now)
		c.mu.Unlock()
		if par != nil {
			c.sendParity(paths, pseq, n, par, cur)
		}
		c.emit(due)
		c.emit(reps)
		c.emit(fills)
		for _, p := range fast {
			c.ping(p, 40)
		}
		if now.Sub(lastBook) >= time.Second {
			lastBook = now
			c.book()
			c.tune()
		}
		t.Reset(next)
	}
}

// tick: how soon the clock runs again — every 2 ms while packets flow (repairs, copies and dark paths
// need it), and only 20 times a second when the game is quiet, so an idle phone is not kept awake
func (c *Client) tick(now time.Time) time.Duration {
	if now.Sub(c.lastSend) < busyFor || len(c.later) > 0 || c.tx.waiting() {
		return busyTick
	}
	for _, p := range c.paths {
		if p.dark {
			return busyTick
		}
	}
	return idleTick
}

// watch: a path the game's packets stopped coming down is dark: nothing more is put on it but quick
// PINGs until it speaks again. All dark at once: only the newest packets go, no copies and no repairs,
// so the phone does not stack up old packets that would all leave at once when the line comes back.
func (c *Client) watch(now time.Time) {
	if !c.cls.gaming(now) || now.Sub(c.lastSend) > 100*time.Millisecond {
		for _, p := range c.paths {
			p.dark = false
		}
		c.allDark = false
		return
	}
	dark := 0
	for _, p := range c.paths {
		if p.lastDown.IsZero() {
			continue
		}
		if !p.dark && now.Sub(p.lastDown) > p.darkAfter() {
			p.dark, p.darkAt = true, now
		}
		if p.dark {
			dark++
		}
	}
	all := dark > 0 && dark == len(c.paths)
	if all && !c.allDark {
		c.allDarkAt = now
		c.credit = 0
	}
	c.allDark = all
}

// repairs: v3 repairs over the newest packets still worth saving, spread between the game's packets
// (one right behind its packet would be taken by the same burst), on the paths in turn
func (c *Client) repairs(now time.Time) []out {
	if !c.caps || !c.exitV3 || c.allDark || c.credit <= 0 || c.lastData.IsZero() {
		return nil
	}
	since := float64(now.Sub(c.lastData)) / 1e6
	spread := min(max(c.gap/3, 2), 8)
	tail := c.pending > 0 && since >= max(c.gap*0.75, 8) // the stream paused: protect its tail now
	if !(c.credit >= 2 || (c.credit >= 1 && since >= spread) || tail) {
		return nil
	}
	ps := c.usable()
	if len(ps) == 0 {
		return nil
	}
	rep := c.rtx.repair(now, maxWin)
	if rep == nil {
		c.credit = 0
		return nil
	}
	x := ps[c.rot%len(ps)]
	c.rot++
	seq := c.rseq
	c.rseq++
	c.credit--
	c.pending = 0
	c.repairsUp++
	return []out{{p: x, pkt: c.w.seal(hdr{c.l.UID, c.sess, seq, kRepair, x.id}, rep)}}
}

// radio: a match runs and the game went quiet on the mobile path: a tiny packet keeps its radio awake (a
// 4G/5G radio idle for a moment drops to a slower state and the next packet waits for it to wake). Dark
// paths get a quick PING instead, to see them come back.
func (c *Client) radio(now time.Time) (fills []out, fast []*path) {
	gaming := c.cls.gaming(now)
	for _, p := range c.paths {
		if p.dark {
			if now.Sub(p.lastFast) >= fastPing {
				p.lastFast = now
				fast = append(fast, p)
			}
			continue
		}
		if !gaming || !c.cur.radio() || p.name != "cell" || p.lastUp.IsZero() || now.Sub(p.lastUp) < fillGap {
			continue
		}
		p.lastUp = now
		c.fills++
		fills = append(fills, out{p: p, pkt: c.w.seal(hdr{c.l.UID, c.sess, 0, kFill, p.id}, []byte{0})})
	}
	return fills, fast
}

// book: packets older than half a second are settled: lost, or brought by one path only
func (c *Client) book() {
	c.mu.Lock()
	defer c.mu.Unlock()
	now := time.Now()
	if !c.any {
		return
	}
	for i := range c.marks {
		m := &c.marks[i]
		if !m.used || (c.top-m.seq < 64 && now.Sub(m.at) < 500*time.Millisecond) {
			continue
		}
		c.settle(m)
	}
	// the line's own downlink loss this second (before repairs), and what to ask the exit for
	switch {
	case !c.dWinAny:
		c.dWinAny, c.dWinTop, c.dWinGot, c.dWinStart = true, c.top, 0, now
	case now.Sub(c.dWinStart) >= time.Second:
		if exp := c.top - c.dWinTop; exp >= 10 {
			l := max(0, 100*(1-float64(c.dWinGot)/float64(exp)))
			if l > c.downLoss {
				c.downLoss = l
			} else {
				c.downLoss += (l - c.downLoss) / 4
			}
		}
		c.dWinTop, c.dWinGot, c.dWinStart = c.top, 0, now
	}
}

// tune: the automatic choice, from what each path shows. More protection at once when the line gets worse,
// less only after it has held for 10 s, so a short dip changes nothing back and forth.
func (c *Client) tune() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.tuneV3()
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
		l := p.lossNow()
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
	s := Settings{Auto: true, DupMs: 10, Fec: 2, Radio: c.want.Radio}
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
	if s.Every == c.cur.Every && s.DupMs == c.cur.DupMs && s.Fec == c.cur.Fec {
		c.pendAt = time.Time{}
		return
	}
	if more {
		c.apply(s)
		c.pendAt = time.Time{}
		return
	}
	if s.DupMs != c.pend.DupMs || s.Fec != c.pend.Fec || s.Every != c.pend.Every || c.pendAt.IsZero() {
		c.pend, c.pendAt = s, time.Now()
		return
	}
	if time.Since(c.pendAt) >= 10*time.Second {
		c.apply(s)
		c.pendAt = time.Time{}
	}
}

// tuneV3: the repair rate up, from the loss the relay counts on the paths that carry the game (two paths:
// the chance both lose the same packet) — up at once, down slowly. A clean line keeps only enough for one
// repair about every 100 ms of play, so a stray loss is still rebuilt in time; nothing more is sent for
// nothing. Nothing extra while a path's queue is swelling (bufferbloat: more packets would make it worse).
// And the rate the exit should use down.
func (c *Client) tuneV3() {
	now := time.Now()
	ps := c.usable()
	if len(ps) == 0 {
		return
	}
	fight := c.cls.fight(now)
	ro := c.recentOutages(now)
	floor := 0.2
	if pps := c.cls.mainPPS(); pps > 0 {
		floor = min(max(10/pps, 0.12), 0.34)
	}
	var r float64
	if len(ps) == 1 {
		l := ps[0].lossNow()
		switch {
		case l < 0.3:
			r = floor
		case l < 1:
			r = max(floor, 0.3)
		case l < 3:
			r = 0.5
		case l < 6:
			r = 0.75
		case l < 10:
			r = 1.25
		default:
			r = 1.75
		}
		if ro > 0 {
			r = max(r, 0.4) // this line had outages lately: enough to fill one
		}
	} else {
		j := ps[0].lossNow() * ps[1].lossNow() / 100
		switch {
		case j < 0.05:
			r = floor / 2
		case j < 0.5:
			r = floor
		case j < 2:
			r = 0.4
		default:
			r = 0.75
		}
	}
	if fight && r >= 0.3 {
		r += 0.15 // a fight on a line that loses: a little more
	}
	if b := ps[0]; b.minRTT > 0 && b.rtt > b.minRTT+bloatMargin {
		r = min(r, floor)
	}
	r = min(r, maxRatio)
	if r > c.ratio {
		c.ratio = r
	} else {
		c.ratio += (r - c.ratio) / 5
	}
	// down: the line's loss before repairs
	c.downLevel = levelFor(c.downLoss, fight && c.downLoss >= 0.3)
}

// downCopy: the relay's delayed copy of what comes down a single path, only when the downlink loses packets
// or had outages lately (0 = none; v2 relays and fixed settings keep the copy)
func (c *Client) downCopy(copyMs int, now time.Time) int {
	if !c.caps || !c.want.Auto {
		return copyMs
	}
	if c.downLoss >= 1 || c.recentOutages(now) >= 2 {
		return copyMs
	}
	return 0
}

// ------------------------------------------------------------------ for the app's screen

type PathStat struct {
	Name      string  `json:"name"`
	Alive     bool    `json:"alive"`
	RTT       float64 `json:"rtt"`
	Jitter    float64 `json:"jit"`
	Loss      float64 `json:"loss"`
	UpLoss    float64 `json:"up_loss"`
	UpFirst   uint32  `json:"up_first"`
	UpTotal   uint32  `json:"up_total"`
	DownFirst uint64  `json:"down_first"`
	Rescued   uint64  `json:"rescued"`
	Dark      bool    `json:"dark"`
	Outages   uint64  `json:"outages"`
}

type Stat struct {
	Up       bool       `json:"up"`
	Ping     float64    `json:"ping"` // the best live path's round trip (ms)
	Jitter   float64    `json:"jit"`
	Paths    []PathStat `json:"paths"`
	Rx       uint64     `json:"rx"`
	Tx       uint64     `json:"tx"`
	Got      uint64     `json:"got"`     // game packets that came back
	Fixed    uint64     `json:"fixed"`   // rebuilt with parity or repairs
	Rescued  uint64     `json:"rescued"` // brought by one path only (the other lost it)
	Settings Settings   `json:"settings"`
	// v3
	V3       bool              `json:"v3"`
	Fight    bool              `json:"fight"`
	MainPPS  float64           `json:"main_pps"`
	Classes  map[string]uint64 `json:"classes"`
	UpLoss   float64           `json:"up_loss"`   // % on the best path, before copies and repairs
	DownLoss float64           `json:"down_loss"` // %, before copies and repairs
	Repair   float64           `json:"repair"`    // repairs per protected packet, up
	CopyMs   int               `json:"copy_ms"`   // the delayed copy, 0 = none now (the line does not need it)
	TxPkts   uint64            `json:"tx_pkts"`   // every packet sent up (copies, repairs and PINGs too)
	DownCopy bool              `json:"down_copy"`
	Stale    uint64            `json:"stale"` // rebuilt too late to be any use (not delivered)
	Fills    uint64            `json:"fills"`
	TunRTT   float64           `json:"tun_rtt"` // Iran ↔ abroad
	TunLoss  float64           `json:"tun_loss"`
	Total    float64           `json:"total"` // phone → game server side (ping + tunnel)
}

func (c *Client) Stat() Stat {
	c.mu.Lock()
	defer c.mu.Unlock()
	s := Stat{Rx: c.downBytes, Tx: c.upBytes, Got: c.got, Fixed: c.fixed, Settings: c.cur,
		V3: c.caps && c.exitV3, Fight: c.cls.fight(time.Now()), MainPPS: c.cls.mainPPS(), Classes: map[string]uint64{},
		DownLoss: c.downLoss, Repair: c.ratio, Stale: c.rrx.stale, Fills: c.fills, TunRTT: c.tunRTT, TunLoss: c.tunLoss, TxPkts: c.txPkts.Load()}
	now := time.Now()
	if now.Sub(c.copyAt) < 2*time.Second {
		s.CopyMs = c.copyDelay(c.cur)
	}
	s.DownCopy = c.downCopy(1, now) > 0
	for i, n := range c.sentCls {
		s.Classes[classNames[i]] = n
	}
	best := 0.0
	for _, p := range c.paths {
		ps := PathStat{Name: p.name, Alive: p.alive() && !p.lastAns.IsZero(), RTT: p.rtt, Jitter: p.jit, Loss: p.loss(), UpLoss: max(p.upLoss, 0), UpFirst: p.upFirst, UpTotal: p.upTotal,
			DownFirst: p.downFirst, Rescued: p.rescued, Dark: p.dark, Outages: p.outages}
		s.Paths = append(s.Paths, ps)
		s.Rescued += p.rescued
		if ps.Alive {
			s.Up = true
			if best == 0 || p.rtt < best {
				best, s.Jitter, s.UpLoss = p.rtt, p.jit, p.lossNow()
			}
		}
	}
	s.Ping = best
	if best > 0 {
		s.Total = best + c.tunRTT
	}
	sort.Slice(s.Paths, func(i, j int) bool { return s.Paths[i].Name > s.Paths[j].Name })
	return s
}

// Ping: a few PINGs straight to the relay from a plain socket (no VPN yet); the best round trip in ms.
// The relay answers only a PING made with this user's key, so an answer also means the key is right.
func Ping(l Link) (int64, error) {
	ms, _, err := PingFull(l)
	return ms, err
}

// PingFull: the same, and the relay's own round trip to the exit abroad (0 when a v2 relay does not say)
func PingFull(l Link) (int64, int64, error) {
	w, err := newWire(l.Obfs, l.Key)
	if err != nil {
		return 0, 0, err
	}
	ra, err := net.ResolveUDPAddr("udp", net.JoinHostPort(l.Host, strconv.Itoa(l.Port)))
	if err != nil {
		return 0, 0, err
	}
	conn, err := net.DialUDP("udp", nil, ra)
	if err != nil {
		return 0, 0, err
	}
	defer conn.Close()
	sess := uint16(rand.Intn(65534) + 1)
	best, tun := int64(-1), int64(0)
	b := make([]byte, 2048)
	for i := uint32(0); i < 3; i++ {
		pl := make([]byte, 11)
		binary.BigEndian.PutUint64(pl, uint64(time.Now().UnixMilli()))
		pl[8] = 0xff
		pl[10] = capRLC
		t0 := time.Now()
		conn.Write(w.seal(hdr{l.UID, sess, i, kPing, 1}, pl))
		_ = conn.SetReadDeadline(time.Now().Add(1500 * time.Millisecond))
		for {
			n, err := conn.Read(b)
			if err != nil {
				break
			}
			h, body, ok := w.open(b[:n])
			if ok && h.kind == kPong|down && h.sess == sess && h.seq == i {
				if ms := time.Since(t0).Milliseconds(); best < 0 || ms < best {
					best = max(ms, 1)
				}
				if len(body) >= 9 {
					rest := body[min(len(body), 9+int(body[8])*9):]
					if len(rest) >= 5 {
						tun = int64(binary.BigEndian.Uint16(rest[1:]))
					}
				}
				break
			}
		}
	}
	if best < 0 {
		return 0, 0, errors.New("the relay did not answer")
	}
	return best, tun, nil
}
