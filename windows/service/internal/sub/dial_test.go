package sub

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"testing"
)

func TestFilterAddressIsSkipped(t *testing.T) {
	for _, s := range []string{"10.10.34.35", "10.10.34.36", "2001:4188:2:600:10:10:34:35"} {
		if !filtered(netip.MustParseAddr(s)) {
			t.Errorf("%s should be the filter address", s)
		}
	}
	for _, s := range []string{"1.1.1.1", "10.10.35.1", "2001:4860::1"} {
		if filtered(netip.MustParseAddr(s)) {
			t.Errorf("%s is a normal address", s)
		}
	}
}

func TestDialByNameAndEveryHostInTheError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { fmt.Fprint(w, "ok") }))
	defer srv.Close()
	c := NewClient()
	byName := strings.Replace(srv.URL, "127.0.0.1", "localhost", 1)
	if b, err := c.get(context.Background(), byName+"/x"); err != nil || b != "ok" {
		t.Fatalf("by name: %q %v", b, err)
	}
	_, _, err := c.race(context.Background(), []string{"http://127.0.0.1:1/sub/kanki-1-12345", "http://127.0.0.1:2/sub/kanki-1-12345"})
	if err == nil || !strings.Contains(err.Error(), "127.0.0.1:1") || !strings.Contains(err.Error(), "127.0.0.1:2") || strings.Contains(err.Error(), "kanki-1-12345") {
		t.Fatalf("error should name every host and hide the code: %v", err)
	}
	t.Log(err)
}

func TestOnlyTheConfiguredSubHosts(t *testing.T) {
	defer withHosts("https://a.example:2053, https://b.example:2053/ ,http://plain.example,https://u@c.example")()
	if got := configured(); len(got) != 2 || got[0] != "https://a.example:2053" || got[1] != "https://b.example:2053" {
		t.Fatalf("configured: %v", got)
	}
	c := NewClient()
	for _, base := range []string{"https://a.example:2053", "https://b.example:2053"} {
		got := c.alternates(base, "/sub/kanki-1-abcd")
		if len(got) != 2 {
			t.Fatalf("%s: %v", base, got)
		}
		for _, u := range got {
			if !strings.HasPrefix(u, "https://a.example:2053/") && !strings.HasPrefix(u, "https://b.example:2053/") {
				t.Fatalf("%s: unexpected host in %v", base, got)
			}
		}
	}
	// another panel: only itself, the code never goes to the configured hosts
	if got := c.alternates("https://other.example", "/sub/kanki-1-abcd"); len(got) != 1 || got[0] != "https://other.example/sub/kanki-1-abcd" {
		t.Fatalf("other panel: %v", got)
	}
}

// withHosts sets the build's panel addresses for one test
func withHosts(spec string) func() {
	was := PanelHosts
	PanelHosts = spec
	return func() { PanelHosts = was }
}
