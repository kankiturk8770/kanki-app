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
	"sync"
	"time"

	"kankivpn/internal/conf"
	"kankivpn/internal/logx"
	"kankivpn/internal/route"
	"kankivpn/internal/state"
	"kankivpn/internal/sub"
)

// Tunnel is everything the engine needs to bring one tunnel up.
type Tunnel struct {
	Config     *conf.Config
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
		c.Servers[i].URL = ""
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
	pub.URL = ""
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

// prepare downloads the config and makes the tunnel plan.
func (m *Manager) prepare(ctx context.Context, a *sub.Account, srv sub.Server, set state.Settings) (*Tunnel, error) {
	fctx, cancel := context.WithTimeout(ctx, fetchTimeout)
	text, err := m.sub.Config(fctx, a, srv)
	cancel()
	cfg, perr := conf.Parse(text)
	m.mu.Lock()
	if err == nil && perr == nil {
		m.lastConf[srv.ID] = text
	} else if old, ok := m.lastConf[srv.ID]; ok {
		m.log.Printf("config download failed (%v); using the last good one", firstErr(err, perr))
		err = nil
		cfg, perr = conf.Parse(old)
	}
	m.mu.Unlock()
	if err != nil {
		return nil, fmt.Errorf("could not download the config: %w", err)
	}
	if perr != nil {
		return nil, fmt.Errorf("bad config from the panel: %w", perr)
	}
	ep, err := resolve(ctx, cfg.Peer.Host, cfg.Peer.Port)
	m.mu.Lock()
	if err == nil {
		m.lastIP[cfg.Peer.Host] = ep.Addr()
	} else if old, ok := m.lastIP[cfg.Peer.Host]; ok {
		m.log.Printf("%v; using the last address", err)
		ep, err = netip.AddrPortFrom(old, cfg.Peer.Port), nil
	}
	m.mu.Unlock()
	if err != nil {
		return nil, err
	}
	return Build(cfg, ep, set)
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
