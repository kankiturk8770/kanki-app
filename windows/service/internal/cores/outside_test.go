package cores

import (
	"net/netip"
	"testing"
)

// Only internet addresses are tied to the network card; loopback and the local network go the normal way.
func TestOnlyPublicAddressesGoOutside(t *testing.T) {
	for a, want := range map[string]bool{
		"1.1.1.1": true, "203.0.113.9": true, "2606:4700::1111": true,
		"127.0.0.1": false, "::1": false, "192.168.1.5": false, "10.8.0.1": false, "172.16.0.1": false, "fd00::1": false,
	} {
		if got := public(netip.MustParseAddr(a)); got != want {
			t.Errorf("%s: %v", a, got)
		}
	}
}
