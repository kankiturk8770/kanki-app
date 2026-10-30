package core

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"kankivpn/internal/conf"
	"kankivpn/internal/logx"
	"kankivpn/internal/state"
	"kankivpn/internal/sub"
)

const cfgText = `[Interface]
PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
Address = 10.8.0.7/32
DNS = 1.1.1.1
MTU = 1280
Jc = 4

[Peer]
PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = 203.0.113.9:51820
PersistentKeepalive = 25
`

const page = `<main data-status="Active" data-expires="" data-traffic-limit="0" data-traffic-used="1">
<h1>ali</h1><section><h3>GERMANY</h3>
<a href="/api/config/1/amneziawg?sub=kanki-1-abcd1234&amp;node=local">x</a></section></main>`

type fakeEngine struct {
	mu        sync.Mutex
	up        *Tunnel
	lockdown  bool
	handshake time.Time
	ups       int
	fail      error
}

func (f *fakeEngine) Up(t Tunnel) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.fail != nil {
		return f.fail
	}
	f.up, f.lockdown = &t, false
	f.ups++
	return nil
}

func (f *fakeEngine) Down(lockdown, _ bool) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.up, f.lockdown = nil, lockdown
}

func (f *fakeEngine) Stats() Stats {
	f.mu.Lock()
	defer f.mu.Unlock()
	return Stats{Rx: 10, Tx: 20, LastHandshake: f.handshake}
}

func (f *fakeEngine) get() (*Tunnel, bool, int) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.up, f.lockdown, f.ups
}

func TestMain(m *testing.M) {
	// set once: goroutines of an earlier test may still read them
	watchEvery, staleAfter, firstHandshake, retryEvery = 10*time.Millisecond, 200*time.Millisecond, 100*time.Millisecond, 30*time.Millisecond
	os.Exit(m.Run())
}

func setup(t *testing.T) (*Manager, *fakeEngine, *httptest.Server) {
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/sub/") {
			fmt.Fprint(w, page)
		} else {
			fmt.Fprint(w, cfgText)
		}
	}))
	t.Cleanup(srv.Close)
	st, err := state.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	sc := sub.NewClient()
	sc.HTTP = srv.Client()
	eng := &fakeEngine{}
	m := New(eng, st, logx.New(""), sc)
	t.Cleanup(m.Stop)
	return m, eng, srv
}

func waitFor(t *testing.T, what string, ok func() bool) {
	t.Helper()
	for i := 0; i < 300; i++ {
		if ok() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", what)
}

func TestConnectFlow(t *testing.T) {
	m, eng, srv := setup(t)
	if err := m.Connect("local/amneziawg"); err == nil {
		t.Fatal("connected without a subscription")
	}
	a, err := m.SetSubscription(srv.URL + "/sub/kanki-1-abcd1234")
	if err != nil {
		t.Fatal(err)
	}
	if len(a.Servers) != 1 || a.Servers[0].URL != "" || a.Code != "" {
		t.Fatalf("account leaks secrets to the app: %+v", a)
	}
	if err := m.Connect(a.Servers[0].ID); err != nil {
		t.Fatal(err)
	}
	waitFor(t, "tunnel up", func() bool { u, _, _ := eng.get(); return u != nil })
	u, _, _ := eng.get()
	if !u.KillSwitch || !u.BlockIPv6 || u.Endpoint.String() != "203.0.113.9:51820" || !u.Config.IsAWG() {
		t.Fatalf("tunnel: %+v", u)
	}
	if m.Status().State != Connecting {
		t.Fatalf("state before handshake: %s", m.Status().State)
	}
	eng.mu.Lock()
	eng.handshake = time.Now()
	eng.mu.Unlock()
	waitFor(t, "connected", func() bool { return m.Status().State == Connected })
	if s := m.Status(); s.Rx != 10 || s.Server.Name != "GERMANY" || !s.KillSwitch {
		t.Fatalf("status: %+v", s)
	}
	m.Disconnect()
	if u, lock, _ := eng.get(); u != nil || lock {
		t.Fatal("disconnect left the tunnel or the block on")
	}
	if m.Status().State != Disconnected {
		t.Fatal(m.Status().State)
	}
}

func TestDeadTunnelReconnectsBehindKillSwitch(t *testing.T) {
	m, eng, srv := setup(t)
	if _, err := m.SetSubscription(srv.URL + "/sub/kanki-1-abcd1234"); err != nil {
		t.Fatal(err)
	}
	_ = m.Connect("local/amneziawg")
	// no handshake ever: after firstHandshake the tunnel is rebuilt
	waitFor(t, "second attempt", func() bool { _, _, n := eng.get(); return n >= 2 })
}

func TestFailureKeepsBlockAndRetries(t *testing.T) {
	m, eng, srv := setup(t)
	if _, err := m.SetSubscription(srv.URL + "/sub/kanki-1-abcd1234"); err != nil {
		t.Fatal(err)
	}
	eng.mu.Lock()
	eng.fail = fmt.Errorf("adapter broke")
	eng.mu.Unlock()
	_ = m.Connect("local/amneziawg")
	waitFor(t, "error state", func() bool { return m.Status().State == Failed })
	if _, lock, _ := eng.get(); !lock || !m.Status().Blocked {
		t.Fatal("kill switch must keep blocking after a failed connect")
	}
	eng.mu.Lock()
	eng.fail = nil
	eng.mu.Unlock()
	waitFor(t, "retry", func() bool { u, _, _ := eng.get(); return u != nil })
}

func TestServiceRestartReconnects(t *testing.T) {
	m, eng, srv := setup(t)
	if _, err := m.SetSubscription(srv.URL + "/sub/kanki-1-abcd1234"); err != nil {
		t.Fatal(err)
	}
	_ = m.Connect("local/amneziawg")
	waitFor(t, "up", func() bool { u, _, _ := eng.get(); return u != nil })
	m.Stop()
	m2 := New(&fakeEngine{}, m.store, logx.New(""), m.sub)
	eng2 := m2.eng.(*fakeEngine)
	m2.Start()
	defer m2.Stop()
	waitFor(t, "up after restart", func() bool { u, _, _ := eng2.get(); return u != nil })
}

func TestInvalidSettingsRefused(t *testing.T) {
	m, _, _ := setup(t)
	s := m.Settings()
	s.SplitMode = "include"
	if m.SetSettings(s) == nil {
		t.Fatal("include mode without ranges accepted")
	}
}

func TestBuild(t *testing.T) {
	cfg, err := conf.Parse(cfgText)
	if err != nil {
		t.Fatal(err)
	}
	ep := netip.MustParseAddrPort("203.0.113.9:51820")
	set := state.Defaults()
	set.SplitMode, set.SplitList = "include", []string{"91.108.0.0/16"}
	tun, err := Build(cfg, ep, set)
	if err != nil {
		t.Fatal(err)
	}
	if tun.KillSwitch {
		t.Fatal("kill switch cannot hold in include mode")
	}
	set = state.Defaults()
	set.DNS = []string{"9.9.9.9"}
	set.MTU = 1400
	tun, _ = Build(cfg, ep, set)
	if tun.DNS[0].String() != "9.9.9.9" || tun.MTU != 1400 {
		t.Fatalf("%+v", tun)
	}
	cfg.DNS = nil
	tun, _ = Build(cfg, ep, state.Defaults())
	if len(tun.DNS) == 0 {
		t.Fatal("no DNS inside the tunnel would leak queries")
	}
}

func TestResolvePrefersIPv4(t *testing.T) {
	old := lookupIP
	defer func() { lookupIP = old }()
	lookupIP = func(context.Context, string) ([]netip.Addr, error) {
		return []netip.Addr{netip.MustParseAddr("2001:db8::1"), netip.MustParseAddr("198.51.100.4")}, nil
	}
	ep, err := resolve(context.Background(), "de1.kanki.pro", 443)
	if err != nil || ep.String() != "198.51.100.4:443" {
		t.Fatal(ep, err)
	}
}
