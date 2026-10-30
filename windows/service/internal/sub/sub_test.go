package sub

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// A page shaped like the panel's sub_page (assets/web/sub.html + proto_row).
const page = `<!doctype html><html><head><style>h3{}</style></head><body>
<main class="sub-shell" data-token="kanki-12-abcd1234" data-expires="2026-12-13T05:21:37Z" data-traffic-limit="50.000000" data-traffic-used="12.500000" data-status="Active">
<div class="hi"><h1 id="un">ali</h1></div>
<section class="node" data-node="local"><h3>GERMANY-GAMING<small data-i18n="main_server"></small></h3>
<div class="row proto" data-proto="wireguard"><div class="ph"><b>WireGuard</b></div><div class="acts">
<a class="btn" href="/api/config/12/wireguard?sub=kanki-12-abcd1234&amp;node=local">Download config</a>
<button data-url="/api/config/12/wireguard/qr?sub=kanki-12-abcd1234&amp;node=local">QR Code</button>
<button data-url="/api/config/12/wireguard/uri?sub=kanki-12-abcd1234&amp;node=local">Get URI</button>
</div></div>
<div class="row proto" data-proto="amneziawg"><div class="acts">
<a class="btn" href="/api/config/12/amneziawg?sub=kanki-12-abcd1234&amp;node=local">Download config</a></div></div>
<div class="row proto" data-proto="hysteria2"><button data-url="/api/config/12/hysteria2/qr?sub=kanki-12-abcd1234&amp;node=local">QR</button></div>
</section>
<section class="node" data-node="n3"><h3>FINLAND</h3>
<a class="btn" href="/api/config/12/amneziawg?sub=kanki-12-abcd1234&amp;node=n3@tunnel">Download config</a>
<a class="btn" href="https://evil.example/api/config/12/amneziawg?sub=kanki-12-abcd1234&amp;node=n9">x</a>
</section>
</main>
<script id="data" type="application/json">{"sub_base":"https://s9.example.com:2053/","app_msg":{"id":"1","text":" hello "},
"nodes":[{"id":"local","exit_cc":"de","exit_city":"Frankfurt"},{"id":"n3","exit_cc":"FI"}]}</script>
</body></html>`

func TestParse(t *testing.T) {
	a, err := Parse(page, "https://p.example.com:2053")
	if err != nil {
		t.Fatal(err)
	}
	if a.Username != "ali" || a.Status != "Active" || a.LimitGB != 50 || a.UsedGB != 12.5 || a.Expires != 1797139297 {
		t.Fatalf("account: %+v", a)
	}
	if a.SubBase != "https://s9.example.com:2053" || a.Notice != "hello" {
		t.Fatalf("data json: %+v", a)
	}
	if len(a.Servers) != 3 {
		t.Fatalf("want 3 servers (wg, awg, awg tunnel), got %+v", a.Servers)
	}
	wg, awg, tun := a.Servers[0], a.Servers[1], a.Servers[2]
	if wg.Proto != "wireguard" || wg.Name != "GERMANY-GAMING" || wg.Country != "DE" || wg.City != "Frankfurt" {
		t.Fatalf("wg: %+v", wg)
	}
	if wg.URL != "https://p.example.com:2053/api/config/12/wireguard?sub=kanki-12-abcd1234&node=local" {
		t.Fatalf("url: %s", wg.URL)
	}
	if awg.Proto != "amneziawg" || awg.ID != "local/amneziawg" {
		t.Fatalf("awg: %+v", awg)
	}
	if !tun.Tunnel || tun.Name != "FINLAND" || tun.Country != "FI" {
		t.Fatalf("tunnel: %+v", tun)
	}
	for _, s := range a.Servers {
		if strings.Contains(s.URL, "evil") {
			t.Fatal("a config link on another host was kept (it would carry the private key away)")
		}
	}
}

func TestParseRejectsOtherPages(t *testing.T) {
	if _, err := Parse("<html><body>404</body></html>", "https://x"); err == nil {
		t.Fatal("accepted")
	}
}

func TestNormalize(t *testing.T) {
	b, c, err := Normalize("  https://p.example.com:2053/sub/kanki-12-abcd1234  ")
	if err != nil || b != "https://p.example.com:2053" || c != "kanki-12-abcd1234" {
		t.Fatal(b, c, err)
	}
	b, c, err = Normalize("code: kanki-12-abcd1234")
	if err != nil || b != DefaultHost || c != "kanki-12-abcd1234" {
		t.Fatal(b, c, err)
	}
	if _, _, err = Normalize("hello"); err == nil {
		t.Fatal("accepted junk")
	}
}

func TestLoadAndConfig(t *testing.T) {
	var srv *httptest.Server
	srv = httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/sub/kanki-12-abcd1234":
			fmt.Fprint(w, page)
		case strings.HasPrefix(r.URL.Path, "/api/config/12/"):
			fmt.Fprint(w, "[Interface]\n")
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()
	c := NewClient()
	c.HTTP = srv.Client()
	a, err := c.Load(context.Background(), srv.URL, "kanki-12-abcd1234")
	if err != nil || len(a.Servers) != 3 {
		t.Fatal(a, err)
	}
	text, err := c.Config(context.Background(), a, a.Servers[0])
	if err != nil || text != "[Interface]\n" {
		t.Fatal(text, err)
	}
}
