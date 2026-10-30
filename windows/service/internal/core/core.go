// Package core is the service's brain: it holds the subscription and the
// settings, connects and disconnects, watches the tunnel and keeps the kill
// switch closed while the user wants the VPN on. The Windows parts (adapter,
// routes, firewall) sit behind Engine so all of this is tested anywhere.
package core

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"sync"
	"time"

	"kankivpn/internal/conf"
	"kankivpn/internal/cores"
	"kankivpn/internal/logx"
	"kankivpn/internal/route"
	"kankivpn/internal/state"
	"kankivpn/internal/sub"

	"mtuncore/mtun"
)

// Tunnel is everything the engine needs to bring one tunnel up.
type Tunnel struct {
	Proto      string       // cores.WireGuard, cores.KankiMix, ...
	Config     *conf.Config // WireGuard / AmneziaWG
	Text       string       // the link of the other protocols
	DeviceID   string       // microtun keeps one device per install
	Endpoint   netip.AddrPort
	Plan       route.Plan
	DNS        []netip.Addr
	MTU        int
	KillSwitch bool // block everything outside the tunnel (and Plan.Bypass)
	BlockIPv6  bool // block IPv6 outside the tunnel even without the kill switch
	AllowLAN   bool
}

// Stats of a running tunnel.
type Stats struct {
	Rx, Tx        uint64
	LastHandshake time.Time
}

// Engine is the platform part (internal/tunnel on Windows).
type Engine interface {
	Up(t Tunnel) error
	// Down removes the tunnel. With lockdown the firewall keeps blocking all
	// traffic (kill switch while reconnecting).
	Down(lockdown bool, allowLAN bool)
	Stats() Stats
}

// States shown in the app.
const (
	Disconnected = "disconnected"
	Connecting   = "connecting"
	Connected    = "connected"
	Reconnecting = "reconnecting"
	Failed       = "error"
)

// Status is what the app shows.
type Status struct {
	State     string      `json:"state"`
	Server    *sub.Server `json:"server,omitempty"`
	Since     int64       `json:"since,omitempty"`
	Rx        uint64      `json:"rx"`
	Tx        uint64      `json:"tx"`
	Handshake int64       `json:"handshake,omitempty"` // seconds since the last handshake
	Error     string      `json:"error,omitempty"`
	// Blocked: the kill switch is holding all traffic until the tunnel is back.
	Blocked bool `json:"blocked"`
	// KillSwitch actually in force for this tunnel (off in Include mode).
	KillSwitch bool   `json:"kill_switch"`
	Endpoint   string `json:"endpoint,omitempty"`
	Proto      string `json:"proto,omitempty"`
}

// Timings, variables so tests can shorten them.
var (
	watchEvery     = 5 * time.Second
	staleAfter     = 3 * time.Minute // no handshake for this long = reconnect
	firstHandshake = 40 * time.Second
	retryEvery     = 10 * time.Second
	resolveTimeout = 8 * time.Second
	fetchTimeout   = 40 * time.Second
	lookupIP       = func(ctx context.Context, host string) ([]netip.Addr, error) {
		// the Go resolver sends the query from this process, which the kill
		// switch lets through; the system resolver (DNS Client service) is
		// blocked while the kill switch holds traffic
		a, err := sub.Resolver.LookupNetIP(ctx, "ip", host)
		if err != nil || len(a) == 0 {
			a, err = net.DefaultResolver.LookupNetIP(ctx, "ip", host)
		}
		return a, err
	}
)

var errStale = errors.New("replaced by a newer request")

type Manager struct {
	eng   Engine
	store *state.Store
	log   *logx.Log
	sub   *sub.Client

	mu     sync.Mutex
	saved  state.Saved
	st     Status
	cancel context.CancelFunc // of the running connection
	gen    int                // bumps on every connect/disconnect
	// last good config text and address of each server, used when the panel or
	// DNS cannot be reached while reconnecting
	lastConf map[string]string
	lastIP   map[string]netip.Addr

	// engMu orders the engine calls: a request that was replaced (older gen)
	// never touches the engine after the newer one did.
	engMu sync.Mutex
}

func New(eng Engine, store *state.Store, log *logx.Log, sc *sub.Client) *Manager {
	m := &Manager{eng: eng, store: store, log: log, sub: sc, lastConf: map[string]string{}, lastIP: map[string]netip.Addr{}}
	m.saved = store.Load()
	m.st = Status{State: Disconnected}
	sc.DeviceID = m.saved.DeviceID
	m.save() // keeps a newly made device id
	return m
}

// Start runs when the service starts: reconnect if the VPN was on (the
// service was restarted or the computer rebooted) or auto connect is on.
func (m *Manager) Start() {
	m.mu.Lock()
	want := m.saved.WasConnected || m.saved.Settings.AutoConnect
	id := m.saved.LastServer
	m.mu.Unlock()
	if want && id != "" {
		m.log.Printf("start: connecting again to %s", id)
		_ = m.Connect(id)
	}
}

// Stop runs when the service stops. The wish to be connected is kept so the
// next start reconnects; the firewall goes with the process (dynamic WFP
// session), so a stopped service never leaves the computer offline.
func (m *Manager) Stop() {
	m.mu.Lock()
	if m.cancel != nil {
		m.cancel()
		m.cancel = nil
	}
	m.gen++
	m.mu.Unlock()
	m.engMu.Lock()
	m.eng.Down(false, false)
	m.engMu.Unlock()
}

func (m *Manager) save() {
	if err := m.store.Save(m.saved); err != nil {
		m.log.Printf("save: %v", err)
	}
}

// Status with fresh traffic numbers.
func (m *Manager) Status() Status {
	m.mu.Lock()
	s := m.st
	m.mu.Unlock()
	if s.State == Connected || s.State == Reconnecting {
		st := m.eng.Stats()
		s.Rx, s.Tx = st.Rx, st.Tx
		if !st.LastHandshake.IsZero() {
			s.Handshake = int64(time.Since(st.LastHandshake).Seconds())
		}
	}
	return s
}

// Account without the config links and code (they never leave the service).
func (m *Manager) Account() *sub.Account {
	m.mu.Lock()
	defer m.mu.Unlock()
	return public(m.saved.Account)
}

func public(a *sub.Account) *sub.Account {
	if a == nil {
		return nil
	}
	c := *a
	c.Code = ""
	c.Servers = append([]sub.Server(nil), a.Servers...)
	for i := range c.Servers {
		c.Servers[i].URL, c.Servers[i].Link = "", ""
	}
	return &c
}

// SetSubscription takes a link or code, loads it and keeps it.
func (m *Manager) SetSubscription(input string) (*sub.Account, error) {
	base, code, err := sub.Normalize(input)
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), fetchTimeout)
	defer cancel()
	a, err := m.sub.Load(ctx, base, code)
	if err != nil {
		m.log.Printf("subscription: %v", err)
		return nil, fmt.Errorf("could not load the subscription: %w", err)
	}
	m.mu.Lock()
	m.saved.Account = a
	m.save()
	m.mu.Unlock()
	m.log.Printf("subscription loaded: %s, %d servers", a.Username, len(a.Servers))
	return public(a), nil
}

// Refresh loads the saved subscription again.
func (m *Manager) Refresh() (*sub.Account, error) {
	m.mu.Lock()
	a := m.saved.Account
	m.mu.Unlock()
	if a == nil {
		return nil, errors.New("no subscription yet")
	}
	ctx, cancel := context.WithTimeout(context.Background(), fetchTimeout)
	defer cancel()
	n, err := m.sub.Load(ctx, a.Base, a.Code)
	if err != nil {
		return public(a), fmt.Errorf("could not refresh: %w", err)
	}
	m.mu.Lock()
	m.saved.Account = n
	m.save()
	m.mu.Unlock()
	return public(n), nil
}

// SubLink is the user's own subscription link (the account sheet copies it).
func (m *Manager) SubLink() (string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	a := m.saved.Account
	if a == nil || a.Code == "" {
		return "", errors.New("no subscription yet")
	}
	base := a.Base
	if a.SubBase != "" {
		base = a.SubBase
	}
	return base + "/sub/" + a.Code, nil
}

// Logout forgets the subscription and disconnects.
func (m *Manager) Logout() {
	m.Disconnect()
	m.mu.Lock()
	m.saved.Account = nil
	m.saved.LastServer = ""
	m.save()
	m.mu.Unlock()
}

func (m *Manager) Settings() state.Settings {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.saved.Settings
}

// SetSettings saves the settings; a running tunnel is rebuilt to use them.
func (m *Manager) SetSettings(s state.Settings) error {
	if err := s.Check(); err != nil {
		return err
	}
	m.mu.Lock()
	m.saved.Settings = s
	m.save()
	active := m.cancel != nil
	id := m.saved.LastServer
	m.mu.Unlock()
	m.log.Printf("settings: kill switch %v, IPv6 block %v, LAN %v, split %s (%d), dns %v",
		s.KillSwitch, s.BlockIPv6, s.AllowLAN, s.SplitMode, len(s.SplitList), s.DNS)
	if active && id != "" {
		return m.Connect(id)
	}
	return nil
}

// Connect starts connecting to one server; it returns at once and the app
// follows the progress with Status.
func (m *Manager) Connect(id string) error {
	m.mu.Lock()
	a := m.saved.Account
	if a == nil {
		m.mu.Unlock()
		return errors.New("add your subscription first")
	}
	var srv *sub.Server
	for i := range a.Servers {
		if a.Servers[i].ID == id {
			s := a.Servers[i]
			srv = &s
		}
	}
	if srv == nil {
		m.mu.Unlock()
		return errors.New("this server is not in your subscription")
	}
	if m.cancel != nil {
		m.cancel()
	}
	m.gen++
	gen := m.gen
	ctx, cancel := context.WithCancel(context.Background())
	m.cancel = cancel
	m.saved.LastServer = id
	m.saved.WasConnected = true
	m.save()
	pub := *srv
	pub.URL, pub.Link = "", ""
	m.st = Status{State: Connecting, Server: &pub, Proto: srv.Proto}
	acc := *a
	set := m.saved.Settings
	m.mu.Unlock()
	go m.run(ctx, gen, &acc, *srv, set)
	return nil
}

// Disconnect stops the tunnel and opens the firewall again.
func (m *Manager) Disconnect() {
	m.mu.Lock()
	if m.cancel != nil {
		m.cancel()
		m.cancel = nil
	}
	m.gen++
	m.saved.WasConnected = false
	m.save()
	m.st = Status{State: Disconnected}
	m.mu.Unlock()
	m.engMu.Lock()
	m.eng.Down(false, false)
	m.engMu.Unlock()
	m.log.Printf("disconnected")
}

func (m *Manager) current(gen int) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return gen == m.gen
}

func (m *Manager) engUp(gen int, t Tunnel) error {
	m.engMu.Lock()
	defer m.engMu.Unlock()
	if !m.current(gen) {
		return errStale
	}
	return m.eng.Up(t)
}

func (m *Manager) engDown(gen int, lockdown, allowLAN bool) {
	m.engMu.Lock()
	defer m.engMu.Unlock()
	if m.current(gen) {
		m.eng.Down(lockdown, allowLAN)
	}
}

// update changes the status only if this connection is still the current one.
func (m *Manager) update(gen int, f func(s *Status)) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	if gen != m.gen {
		return false
	}
	f(&m.st)
	return true
}

// run connects, watches, and reconnects until ctx ends.
func (m *Manager) run(ctx context.Context, gen int, a *sub.Account, srv sub.Server, set state.Settings) {
	lock := set.KillSwitch && set.SplitMode != route.Include
	m.engDown(gen, lock, set.AllowLAN)
	for ctx.Err() == nil {
		t, err := m.prepare(ctx, a, srv, set)
		if err == nil {
			err = m.engUp(gen, *t)
		}
		if ctx.Err() != nil || errors.Is(err, errStale) {
			return
		}
		if err != nil {
			m.log.Printf("connect to %s failed: %v", srv.Name, err)
			m.engDown(gen, lock, set.AllowLAN)
			m.update(gen, func(s *Status) {
				s.State, s.Error, s.Blocked = Failed, err.Error(), lock
			})
			if !sleep(ctx, retryEvery) {
				return
			}
			m.update(gen, func(s *Status) { s.State = Reconnecting })
			continue
		}
		m.log.Printf("tunnel up: %s (%s) via %s, %d routes, kill switch %v", srv.Name, srv.Proto, t.Endpoint, len(t.Plan.Routes), t.KillSwitch)
		up := time.Now()
		m.update(gen, func(s *Status) {
			s.State, s.Error, s.Blocked = Connecting, "", false
			s.KillSwitch, s.Endpoint, s.Since = t.KillSwitch, t.Endpoint.String(), up.Unix()
		})
		reason := m.watch(ctx, gen, up)
		if ctx.Err() != nil {
			return
		}
		m.log.Printf("reconnecting: %s", reason)
		m.engDown(gen, t.KillSwitch, set.AllowLAN)
		m.update(gen, func(s *Status) { s.State, s.Blocked = Reconnecting, t.KillSwitch })
	}
}

// watch returns when the tunnel looks dead.
func (m *Manager) watch(ctx context.Context, gen int, up time.Time) string {
	for sleep(ctx, watchEvery) {
		st := m.eng.Stats()
		switch {
		case st.LastHandshake.IsZero():
			if time.Since(up) > firstHandshake {
				return "the server did not answer"
			}
		case time.Since(st.LastHandshake) > staleAfter:
			return "no answer from the server for " + staleAfter.String()
		default:
			m.update(gen, func(s *Status) {
				if s.State != Connected {
					m.log.Printf("connected (first handshake)")
				}
				s.State = Connected
			})
		}
	}
	return ""
}

// configText is the server's config: the link inside the page (microtun,
// VLESS) or the text the panel gives for it, with the last good one as a
// fallback when the panel cannot be reached.
func (m *Manager) configText(ctx context.Context, a *sub.Account, srv sub.Server) (string, error) {
	if srv.Link != "" {
		return srv.Link, nil
	}
	fctx, cancel := context.WithTimeout(ctx, fetchTimeout)
	text, err := m.sub.Config(fctx, a, srv)
	cancel()
	if err == nil {
		_, _, err = endpointOf(srv.Proto, text)
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if err == nil {
		m.lastConf[srv.ID] = text
		return text, nil
	}
	if old, ok := m.lastConf[srv.ID]; ok {
		m.log.Printf("config of %s: %v; using the last good one", srv.Name, err)
		return old, nil
	}
	return "", fmt.Errorf("could not get the config: %w", err)
}

// endpointOf reads the server address out of a config of any protocol.
func endpointOf(proto, text string) (string, uint16, error) {
	switch proto {
	case cores.WireGuard, cores.AmneziaWG:
		c, err := conf.Parse(text)
		if err != nil {
			return "", 0, err
		}
		return c.Peer.Host, c.Peer.Port, nil
	case cores.KankiMix:
		k, err := cores.ParseKmix(text)
		if err != nil {
			return "", 0, err
		}
		return k.Host, uint16(k.PickPort()), nil
	case cores.Hysteria2:
		h, err := cores.ParseHy2(text)
		if err != nil {
			return "", 0, err
		}
		return h.Host, uint16(h.Port), nil
	case cores.VLESS:
		v, err := cores.ParseVless(text)
		if err != nil {
			return "", 0, err
		}
		return v.Host, uint16(v.Port), nil
	case cores.Microtun:
		l, err := mtun.ParseLink(text)
		if err != nil {
			return "", 0, err
		}
		h, p, err := net.SplitHostPort(l.Server)
		if err != nil {
			return "", 0, err
		}
		n, err := strconv.ParseUint(p, 10, 16)
		return h, uint16(n), err
	}
	return "", 0, fmt.Errorf("protocol %q does not run on Windows", proto)
}

// resolveCached resolves, falling back to the last good address of the host.
func (m *Manager) resolveCached(ctx context.Context, host string, port uint16) (netip.AddrPort, error) {
	ep, err := resolve(ctx, host, port)
	m.mu.Lock()
	defer m.mu.Unlock()
	if err == nil {
		m.lastIP[host] = ep.Addr()
	} else if old, ok := m.lastIP[host]; ok {
		m.log.Printf("%v; using the last address", err)
		ep, err = netip.AddrPortFrom(old, port), nil
	}
	return ep, err
}

// prepare gets the config and makes the tunnel plan.
func (m *Manager) prepare(ctx context.Context, a *sub.Account, srv sub.Server, set state.Settings) (*Tunnel, error) {
	text, err := m.configText(ctx, a, srv)
	if err != nil {
		return nil, err
	}
	host, port, err := endpointOf(srv.Proto, text)
	if err != nil {
		return nil, fmt.Errorf("bad config from the panel: %w", err)
	}
	ep, err := m.resolveCached(ctx, host, port)
	if err != nil {
		return nil, err
	}
	var t *Tunnel
	if srv.Proto == cores.WireGuard || srv.Proto == cores.AmneziaWG {
		cfg, err := conf.Parse(text)
		if err != nil {
			return nil, fmt.Errorf("bad config from the panel: %w", err)
		}
		t, err = Build(cfg, ep, set)
		if err != nil {
			return nil, err
		}
	} else if t, err = BuildLink(srv.Proto, text, ep, set); err != nil {
		return nil, err
	}
	t.Proto, t.DeviceID = srv.Proto, m.saved.DeviceID
	return t, nil
}

// BuildLink is Build for the protocols that come as a link: everything IPv4
// goes into the tunnel (they carry no IPv6, so IPv6 is blocked, never leaked).
func BuildLink(proto, text string, ep netip.AddrPort, set state.Settings) (*Tunnel, error) {
	cfg := &conf.Config{Peer: conf.Peer{AllowedIPs: []netip.Prefix{netip.MustParsePrefix("0.0.0.0/0")}}}
	t, err := Build(cfg, ep, set)
	if err != nil {
		return nil, err
	}
	t.Config, t.Text, t.BlockIPv6 = nil, text, true
	switch proto {
	case cores.KankiMix:
		t.MTU = 1280 // the KANKI MIX core's own packet size
	case cores.Microtun:
		if l, err := mtun.ParseLink(text); err == nil && set.MTU == 0 {
			t.MTU = l.MTU
		}
	default:
		if set.MTU == 0 {
			t.MTU = 1500 // tun2socks: the adapter stays inside this computer
		}
	}
	return t, nil
}

// Ping measures the servers' real round trips (each protocol's own hello),
// several at a time; -1 = no answer.
func (m *Manager) Ping(ids []string) map[string]int64 {
	m.mu.Lock()
	a := m.saved.Account
	m.mu.Unlock()
	out := map[string]int64{}
	if a == nil {
		return out
	}
	want := map[string]bool{}
	for _, id := range ids {
		want[id] = true
	}
	var mu sync.Mutex
	var wg sync.WaitGroup
	gate := make(chan struct{}, 8)
	for _, srv := range a.Servers {
		if len(ids) > 0 && !want[srv.ID] {
			continue
		}
		wg.Add(1)
		go func(srv sub.Server) {
			defer wg.Done()
			gate <- struct{}{}
			defer func() { <-gate }()
			ms, err := m.ping(a, srv)
			if err != nil {
				ms = -1
			}
			mu.Lock()
			out[srv.ID] = ms
			mu.Unlock()
		}(srv)
	}
	wg.Wait()
	return out
}

func (m *Manager) ping(a *sub.Account, srv sub.Server) (int64, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	text, err := m.configText(ctx, a, srv)
	if err != nil {
		return 0, err
	}
	host, port, err := endpointOf(srv.Proto, text)
	if err != nil {
		return 0, err
	}
	ep, err := m.resolveCached(ctx, host, port)
	if err != nil {
		return 0, err
	}
	switch srv.Proto {
	case cores.WireGuard, cores.AmneziaWG:
		w, err := cores.NewWgHello(text)
		if err != nil {
			return 0, err
		}
		return w.Ping(ep)
	case cores.KankiMix:
		k, _ := cores.ParseKmix(text)
		return cores.KmixPing(k, netip.AddrPortFrom(ep.Addr(), uint16(k.Port)))
	case cores.Microtun:
		return mtun.Ping(text)
	case cores.VLESS:
		return cores.TCP(ctx, ep)
	case cores.Hysteria2:
		if p, ok := m.eng.(ProgramPinger); ok {
			return p.PingProgram(ctx, srv.Proto, text, ep)
		}
	}
	return 0, errors.New("no ping for this protocol")
}

// ProgramPinger is an engine that can ping through a protocol's own program
// (Hysteria2 has no hello of its own; its program connects and measures).
type ProgramPinger interface {
	PingProgram(ctx context.Context, proto, text string, ep netip.AddrPort) (int64, error)
}

// Build turns a config and the settings into a tunnel.
func Build(cfg *conf.Config, ep netip.AddrPort, set state.Settings) (*Tunnel, error) {
	dns := cfg.DNS
	if len(set.DNS) > 0 {
		dns = nil
		for _, d := range set.DNS {
			a, err := netip.ParseAddr(d)
			if err != nil {
				return nil, err
			}
			dns = append(dns, a.Unmap())
		}
	}
	if len(dns) == 0 {
		// without a DNS server inside the tunnel, queries would go to the
		// network's own resolver (a leak)
		dns = []netip.Addr{netip.MustParseAddr("1.1.1.1"), netip.MustParseAddr("1.0.0.1")}
	}
	var list []netip.Prefix
	for _, v := range set.SplitList {
		p, err := state.ParsePrefix(v)
		if err != nil {
			return nil, err
		}
		list = append(list, p)
	}
	plan := route.Make(route.Input{
		AllowedIPs: cfg.Peer.AllowedIPs, Endpoint: ep.Addr(), DNS: dns,
		Mode: set.SplitMode, List: list, AllowLAN: set.AllowLAN, TunnelIPv6: cfg.HasIPv6(),
	})
	if len(plan.Routes) == 0 {
		return nil, errors.New("nothing would go through the VPN with these split tunnel settings")
	}
	mtu := cfg.MTU
	if set.MTU != 0 {
		mtu = set.MTU
	}
	if mtu == 0 {
		mtu = 1280
	}
	return &Tunnel{
		Config: cfg, Endpoint: ep, Plan: plan, DNS: dns, MTU: mtu,
		KillSwitch: set.KillSwitch && plan.KillSwitchPossible,
		BlockIPv6:  set.BlockIPv6 || set.KillSwitch,
		AllowLAN:   set.AllowLAN,
	}, nil
}

// resolve finds the server's address, IPv4 first (it works on every network).
func resolve(ctx context.Context, host string, port uint16) (netip.AddrPort, error) {
	if a, err := netip.ParseAddr(host); err == nil {
		return netip.AddrPortFrom(a.Unmap(), port), nil
	}
	rctx, cancel := context.WithTimeout(ctx, resolveTimeout)
	defer cancel()
	addrs, err := lookupIP(rctx, host)
	if err != nil || len(addrs) == 0 {
		return netip.AddrPort{}, fmt.Errorf("could not find the server address %s: %v", host, err)
	}
	pick := addrs[0].Unmap()
	for _, a := range addrs {
		if a.Unmap().Is4() {
			pick = a.Unmap()
			break
		}
	}
	return netip.AddrPortFrom(pick, port), nil
}

func firstErr(errs ...error) error {
	for _, e := range errs {
		if e != nil {
			return e
		}
	}
	return nil
}

func sleep(ctx context.Context, d time.Duration) bool {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-t.C:
		return true
	case <-ctx.Done():
		return false
	}
}
