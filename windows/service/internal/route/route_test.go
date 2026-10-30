package route

import (
	"net/netip"
	"testing"
)

func pf(ss ...string) []netip.Prefix {
	var out []netip.Prefix
	for _, s := range ss {
		out = append(out, netip.MustParsePrefix(s))
	}
	return out
}

func in(ps []netip.Prefix, a string) bool { return covered(ps, netip.MustParseAddr(a)) }

func TestSubtractKeepsEverythingElse(t *testing.T) {
	r := Subtract(pf("0.0.0.0/0"), pf("203.0.113.9/32"))
	if len(r) != 32 {
		t.Fatalf("want 32 prefixes, got %d: %v", len(r), r)
	}
	if in(r, "203.0.113.9") || !in(r, "203.0.113.8") || !in(r, "8.8.8.8") || !in(r, "255.255.255.254") {
		t.Fatalf("wrong cut: %v", r)
	}
}

func TestSubtractWholeAndNothing(t *testing.T) {
	if r := Subtract(pf("10.0.0.0/8"), pf("0.0.0.0/0")); len(r) != 0 {
		t.Fatalf("all cut, got %v", r)
	}
	if r := Subtract(pf("10.0.0.0/8"), pf("192.168.0.0/16", "::/0")); len(r) != 1 || r[0] != pf("10.0.0.0/8")[0] {
		t.Fatalf("nothing cut, got %v", r)
	}
}

func TestMergeJoinsHalves(t *testing.T) {
	r := Merge(pf("0.0.0.0/1", "128.0.0.0/1", "10.1.0.0/16"))
	if len(r) != 1 || r[0].Bits() != 0 {
		t.Fatalf("got %v", r)
	}
}

func TestPlanFullTunnel(t *testing.T) {
	p := Make(Input{
		AllowedIPs: pf("0.0.0.0/0", "::/0"),
		Endpoint:   netip.MustParseAddr("203.0.113.9"),
		DNS:        []netip.Addr{netip.MustParseAddr("1.1.1.1")},
		Mode:       All,
	})
	if in(p.Routes, "203.0.113.9") {
		t.Fatal("server address routed into its own tunnel")
	}
	if !in(p.Routes, "1.1.1.1") || !in(p.Routes, "192.168.1.1") {
		t.Fatalf("routes: %v", p.Routes)
	}
	for _, r := range p.Routes {
		if r.Addr().Is6() {
			t.Fatal("IPv6 routed into a tunnel without IPv6")
		}
	}
	if !p.KillSwitchPossible || len(p.Bypass) != 0 {
		t.Fatalf("plan: %+v", p)
	}
}

func TestPlanExcludeAndLAN(t *testing.T) {
	p := Make(Input{
		AllowedIPs: pf("0.0.0.0/0", "::/0"),
		Endpoint:   netip.MustParseAddr("203.0.113.9"),
		DNS:        []netip.Addr{netip.MustParseAddr("5.5.5.5")},
		Mode:       Exclude,
		List:       pf("5.0.0.0/8"),
		AllowLAN:   true,
		TunnelIPv6: true,
	})
	if in(p.Routes, "5.1.2.3") || in(p.Routes, "192.168.1.1") || !in(p.Routes, "8.8.8.8") {
		t.Fatalf("routes: %v", p.Routes)
	}
	if !in(p.Routes, "5.5.5.5") {
		t.Fatal("DNS server inside an excluded range must still use the tunnel")
	}
	if !in(p.Routes, "2001:db8::1") || in(p.Routes, "fe80::1") {
		t.Fatal("IPv6 routes wrong")
	}
	if !in(p.Bypass, "5.1.2.3") || !in(p.Bypass, "192.168.1.1") || in(p.Bypass, "8.8.8.8") {
		t.Fatalf("bypass: %v", p.Bypass)
	}
}

func TestPlanInclude(t *testing.T) {
	p := Make(Input{
		AllowedIPs: pf("0.0.0.0/0"),
		DNS:        []netip.Addr{netip.MustParseAddr("1.1.1.1")},
		Mode:       Include,
		List:       pf("91.108.0.0/16"),
	})
	if !in(p.Routes, "91.108.4.5") || in(p.Routes, "8.8.8.8") || !in(p.Routes, "1.1.1.1") {
		t.Fatalf("routes: %v", p.Routes)
	}
	if p.KillSwitchPossible {
		t.Fatal("kill switch in include mode")
	}
}
