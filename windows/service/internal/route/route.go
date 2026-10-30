// Package route decides which destinations go into the tunnel (the routes put
// on the tunnel adapter) and which may leave outside it (what the firewall lets
// through while the kill switch is on).
//
// Routes are always "the config's AllowedIPs minus what stays outside", cut
// into exact prefixes. Windows picks the longest matching prefix, so the
// server's own address never loops into the tunnel and no default-route
// watcher is needed when the network changes.
package route

import (
	"net/netip"
	"sort"
)

// Split modes.
const (
	All     = "all"     // everything through the tunnel
	Exclude = "exclude" // everything except the listed ranges
	Include = "include" // only the listed ranges
)

// Input is what the plan is made from.
type Input struct {
	AllowedIPs []netip.Prefix
	Endpoint   netip.Addr
	DNS        []netip.Addr
	Mode       string
	List       []netip.Prefix // ranges for Exclude / Include
	AllowLAN   bool
	TunnelIPv6 bool // the tunnel has an IPv6 address
}

// Plan is the result.
type Plan struct {
	// Routes on the tunnel adapter.
	Routes []netip.Prefix
	// Destinations the firewall lets out of the physical adapters while the
	// kill switch is on (LAN when allowed, excluded ranges).
	Bypass []netip.Prefix
	// KillSwitch can only hold in All and Exclude modes; in Include mode the
	// rest of the traffic is meant to go outside.
	KillSwitchPossible bool
}

// LAN ranges: private, link-local and multicast.
var LAN = []netip.Prefix{
	netip.MustParsePrefix("10.0.0.0/8"),
	netip.MustParsePrefix("172.16.0.0/12"),
	netip.MustParsePrefix("192.168.0.0/16"),
	netip.MustParsePrefix("169.254.0.0/16"),
	netip.MustParsePrefix("224.0.0.0/4"),
	netip.MustParsePrefix("255.255.255.255/32"),
	netip.MustParsePrefix("fe80::/10"),
	netip.MustParsePrefix("fc00::/7"),
	netip.MustParsePrefix("ff00::/8"),
}

// Make builds the plan.
func Make(in Input) Plan {
	var outside []netip.Prefix
	if in.Endpoint.IsValid() {
		outside = append(outside, netip.PrefixFrom(in.Endpoint, in.Endpoint.BitLen()))
	}
	if in.AllowLAN {
		outside = append(outside, LAN...)
	}
	base := in.AllowedIPs
	p := Plan{KillSwitchPossible: in.Mode != Include}
	switch in.Mode {
	case Include:
		base = Intersect(in.AllowedIPs, in.List)
	case Exclude:
		outside = append(outside, in.List...)
		p.Bypass = append(p.Bypass, in.List...)
	}
	if in.AllowLAN {
		p.Bypass = append(p.Bypass, LAN...)
	}
	if !in.TunnelIPv6 {
		// no IPv6 address inside: IPv6 is blocked by the firewall, not routed
		base = only4(base)
	}
	routes := Subtract(base, outside)
	// DNS servers always go into the tunnel, even in Include mode or when an
	// excluded range covers them: a query outside would leak.
	for _, d := range in.DNS {
		if d.Is6() && !in.TunnelIPv6 {
			continue
		}
		if !covered(routes, d) {
			routes = append(routes, netip.PrefixFrom(d, d.BitLen()))
		}
	}
	p.Routes = Merge(routes)
	p.Bypass = Merge(p.Bypass)
	return p
}

func only4(ps []netip.Prefix) []netip.Prefix {
	var out []netip.Prefix
	for _, p := range ps {
		if p.Addr().Is4() {
			out = append(out, p)
		}
	}
	return out
}

func covered(ps []netip.Prefix, a netip.Addr) bool {
	for _, p := range ps {
		if p.Contains(a) {
			return true
		}
	}
	return false
}

// Subtract returns base minus cut, as the fewest exact prefixes.
func Subtract(base, cut []netip.Prefix) []netip.Prefix {
	var out []netip.Prefix
	for _, b := range base {
		out = append(out, subtractOne(b.Masked(), cut)...)
	}
	return Merge(out)
}

func subtractOne(b netip.Prefix, cut []netip.Prefix) []netip.Prefix {
	for _, c := range cut {
		c = c.Masked()
		if c.Addr().Is4() != b.Addr().Is4() {
			continue
		}
		if c.Bits() <= b.Bits() && c.Contains(b.Addr()) {
			return nil // all of b is cut
		}
	}
	touched := false
	for _, c := range cut {
		if c.Addr().Is4() == b.Addr().Is4() && b.Contains(c.Addr()) && c.Bits() > b.Bits() {
			touched = true
			break
		}
	}
	if !touched {
		return []netip.Prefix{b}
	}
	lo, hi := halves(b)
	return append(subtractOne(lo, cut), subtractOne(hi, cut)...)
}

// halves splits a prefix into its two children.
func halves(p netip.Prefix) (netip.Prefix, netip.Prefix) {
	bits := p.Bits() + 1
	lo := netip.PrefixFrom(p.Addr(), bits)
	a := p.Addr().AsSlice()
	i := (bits - 1) / 8
	a[i] |= 0x80 >> ((bits - 1) % 8)
	hiAddr, _ := netip.AddrFromSlice(a)
	return lo, netip.PrefixFrom(hiAddr, bits)
}

// Intersect returns the parts of a that are also in b.
func Intersect(a, b []netip.Prefix) []netip.Prefix {
	var out []netip.Prefix
	for _, x := range a {
		for _, y := range b {
			x, y := x.Masked(), y.Masked()
			if x.Addr().Is4() != y.Addr().Is4() || !x.Overlaps(y) {
				continue
			}
			if x.Bits() >= y.Bits() {
				out = append(out, x)
			} else {
				out = append(out, y)
			}
		}
	}
	return Merge(out)
}

// Merge sorts, drops prefixes inside others and joins sibling halves.
func Merge(ps []netip.Prefix) []netip.Prefix {
	cur := make([]netip.Prefix, 0, len(ps))
	for _, p := range ps {
		if p.IsValid() {
			cur = append(cur, p.Masked())
		}
	}
	for {
		sort.Slice(cur, func(i, j int) bool {
			a, b := cur[i], cur[j]
			if a.Addr().Is4() != b.Addr().Is4() {
				return a.Addr().Is4()
			}
			if c := a.Addr().Compare(b.Addr()); c != 0 {
				return c < 0
			}
			return a.Bits() < b.Bits()
		})
		var out []netip.Prefix
		for _, p := range cur {
			if n := len(out); n > 0 && out[n-1].Overlaps(p) && out[n-1].Bits() <= p.Bits() {
				continue // inside the previous one
			}
			out = append(out, p)
		}
		joined := false
		for i := 0; i+1 < len(out); i++ {
			a, b := out[i], out[i+1]
			if a.Bits() == b.Bits() && a.Bits() > 0 && a.Addr().Is4() == b.Addr().Is4() {
				parent := netip.PrefixFrom(a.Addr(), a.Bits()-1).Masked()
				if lo, hi := halves(parent); lo == a && hi == b {
					out[i] = parent
					out = append(out[:i+1], out[i+2:]...)
					joined = true
				}
			}
		}
		cur = out
		if !joined {
			return cur
		}
	}
}
