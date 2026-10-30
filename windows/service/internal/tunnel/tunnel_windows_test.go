package tunnel

import (
	"net/netip"
	"os"
	"testing"
	"time"

	"golang.org/x/sys/windows"
	"golang.zx2c4.com/wireguard/windows/tunnel/winipcfg"

	"kankivpn/internal/conf"
	"kankivpn/internal/core"
	"kankivpn/internal/firewall"
	"kankivpn/internal/logx"
	"kankivpn/internal/state"
)

// These tests change the real network of the machine, so they run only as
// administrator with KANKI_ADMIN_TESTS=1 (the Windows CI job). They never
// route the machine's own traffic: only TEST-NET ranges go into the tunnel.

func admin(t *testing.T) {
	if os.Getenv("KANKI_ADMIN_TESTS") != "1" {
		t.Skip("set KANKI_ADMIN_TESTS=1 and run as administrator")
	}
}

const cfgText = `[Interface]
PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
Address = 10.66.0.7/32
DNS = 198.51.100.53
MTU = 1300
Jc = 4
Jmin = 40
Jmax = 70
S1 = 15
S2 = 68
H1 = 1234567
H2 = 2234567
H3 = 3234567
H4 = 4234567

[Peer]
PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = 203.0.113.9:51820
PersistentKeepalive = 25
`

func TestTunnelUpAndDown(t *testing.T) {
	admin(t)
	cfg, err := conf.Parse(cfgText)
	if err != nil {
		t.Fatal(err)
	}
	set := state.Defaults()
	set.KillSwitch = false
	set.SplitMode = "include"
	set.SplitList = []string{"198.51.100.0/24"}
	tun, err := core.Build(cfg, netip.MustParseAddrPort("203.0.113.9:51820"), set)
	if err != nil {
		t.Fatal(err)
	}
	e := New(logx.New(""))
	if err := e.Up(*tun); err != nil {
		t.Fatal(err)
	}
	luid := winipcfg.LUID(0)
	{
		e.mu.Lock()
		dev := e.dev
		e.mu.Unlock()
		if dev == nil {
			t.Fatal("no device")
		}
	}
	ifs, err := winipcfg.GetIPInterfaceTable(windows.AF_INET)
	if err != nil {
		t.Fatal(err)
	}
	for _, i := range ifs {
		row, err := i.InterfaceLUID.Interface()
		if err == nil && row.Alias() == AdapterName {
			luid = i.InterfaceLUID
		}
	}
	if luid == 0 {
		t.Fatal("adapter not found")
	}
	if _, err := luid.Route(netip.MustParsePrefix("198.51.100.0/24"), netip.IPv4Unspecified()); err != nil {
		t.Fatalf("route missing: %v", err)
	}
	if _, err := luid.IPAddress(netip.MustParseAddr("10.66.0.7")); err != nil {
		t.Fatalf("address missing: %v", err)
	}
	dns, err := luid.DNS()
	if err != nil || len(dns) == 0 || dns[0].String() != "198.51.100.53" {
		t.Fatalf("dns: %v %v", dns, err)
	}
	time.Sleep(time.Second)
	st := e.Stats()
	if st.Tx == 0 {
		t.Log("no handshake bytes sent yet (allowed; the peer does not exist)")
	}
	e.Down(false, false)
	// Windows removes the interface a moment after the adapter is deleted
	for i := 0; ; i++ {
		if _, err := luid.Interface(); err != nil {
			break
		}
		if i == 50 {
			t.Fatal("adapter still there 5 seconds after Down")
		}
		time.Sleep(100 * time.Millisecond)
	}
}

func TestKillSwitchRulesInstall(t *testing.T) {
	admin(t)
	// the full block is on for a moment only: the runner must keep its network
	s, err := firewall.Enable(firewall.Options{
		BlockAll: true,
		DNS:      []netip.Addr{netip.MustParseAddr("1.1.1.1")},
		Bypass:   []netip.Prefix{netip.MustParsePrefix("0.0.0.0/0"), netip.MustParsePrefix("::/0"), netip.MustParsePrefix("10.0.0.0/8"), netip.MustParsePrefix("fe80::/10")},
	})
	if err != nil {
		t.Fatal(err)
	}
	// a second set goes up while the first exists (the swap Up does)
	s2, err := firewall.Enable(firewall.Options{BlockIPv6: true, Bypass: []netip.Prefix{netip.MustParsePrefix("0.0.0.0/0")}})
	if err != nil {
		s.Close()
		t.Fatal(err)
	}
	s.Close()
	s2.Close()
}
