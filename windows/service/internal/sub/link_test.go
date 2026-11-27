package sub

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

const (
	tCode   = "kanki-12-4821930012345678901"
	tDigits = "4821930012345678901"
	tHash   = "0123456789abcdef0123456789abcdef01234567"
)

func TestOriginCutsEveryPath(t *testing.T) {
	for in, want := range map[string]string{
		"https://p.example:2053/admin":  "https://p.example:2053",
		" https://p.example:2053/sub/ ": "https://p.example:2053",
		"https://p.example:2053/sub/me": "https://p.example:2053",
		"p.example/":                    "https://p.example",
		"http://P.Example:443":          "https://p.example",
		"‏https://p.example:8443‎":      "https://p.example:8443",
	} {
		if got, ok := Origin(in); !ok || got != want {
			t.Errorf("%q: %q %v", in, got, ok)
		}
	}
	for _, bad := range []string{"https://u@p.example", "ftp://p.example", "https://p.example:99999", "https://", "", "https://p.example:x"} {
		if got, ok := Origin(bad); ok {
			t.Errorf("%q accepted as %q", bad, got)
		}
	}
	if got := Hosts("https://a.example:2053/admin;\nb.example:2053/sub/ https://a.example:2053"); strings.Join(got, ",") != "https://a.example:2053,https://b.example:2053" {
		t.Fatal(got)
	}
}

func TestEveryInputShape(t *testing.T) {
	for in, want := range map[string]Input{
		tCode:                                 {KindCode, "", tCode},
		"code:\n" + tCode + "‏":               {KindCode, "", tCode},
		tDigits:                               {KindBare, "", tDigits},
		"۴۸۲۱۹۳۰۰۱۲۳۴۵۶۷۸۹۰۱":                 {KindBare, "", tDigits},
		"https://p.example:2053/sub/" + tCode: {KindCode, "https://p.example:2053", tCode},
		"https://p.example:2053/sub/sub/" + tCode + "/":                 {KindCode, "https://p.example:2053", tCode},
		"https://r.example:2053/sub/" + tDigits:                         {KindBare, "https://r.example:2053", tDigits},
		"https://p.example/sub/h/" + tHash + "/v2":                      {KindHash, "https://p.example", tHash},
		"https://p.example/sub/me?sub=" + tCode:                         {KindCode, "https://p.example", tCode},
		"https://p.example/api/config/12/wireguard?node=a&sub=" + tCode: {KindCode, "https://p.example", tCode},
		"https://p.example@evil.example/sub/" + tCode:                   {KindCode, "", tCode},
	} {
		got, ok := ParseInput(in)
		if !ok || got != want {
			t.Errorf("%q: %+v %v", in, got, ok)
		}
	}
	for _, bad := range []string{"https://p.example/sub/h/abc", "https://p.example/sub/me", "hello", "12345"} {
		if got, ok := ParseInput(bad); ok {
			t.Errorf("%q accepted as %+v", bad, got)
		}
	}
}

// a panel of 3.67: /sub/me with the code in the header; an older relay: 405 there, the path works
func TestPageByHeaderThenPath(t *testing.T) {
	for _, old := range []bool{false, true} {
		var paths []string
		srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			paths = append(paths, r.URL.Path)
			switch {
			case r.URL.Path == "/sub/me" && old:
				http.Error(w, "no", http.StatusMethodNotAllowed)
			case r.URL.Path == "/sub/me" && r.Header.Get("X-Kanki-Sub") == tCode:
				fmt.Fprint(w, page)
			case r.URL.Path == "/sub/"+tCode && old:
				fmt.Fprint(w, page)
			default:
				http.NotFound(w, r)
			}
		}))
		c := NewClient()
		c.HTTP = srv.Client()
		if _, err := c.page(context.Background(), srv.URL, tCode); err != nil {
			t.Fatal(old, err, paths)
		}
		if old != (len(paths) == 2) || strings.Contains(paths[0], tCode) {
			t.Fatal(old, paths)
		}
		// a wrong code is told as such, after both tries
		_, err := c.page(context.Background(), srv.URL, "kanki-12-wrongwrong")
		if err == nil || !strings.Contains(err.Error(), "does not know this subscription code") {
			t.Fatal(err)
		}
		srv.Close()
	}
}

func TestBareAndHashFindTheirCode(t *testing.T) {
	plainPage := `<h1>u</h1><a href="/api/config/12/wireguard?sub=` + tCode + `&amp;node=local">x</a>`
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Kanki-Seal") != "" {
			http.Error(w, "the discovery must not ask for a sealed answer", 400)
			return
		}
		switch {
		case r.URL.Path == "/sub/me" && r.Header.Get("X-Kanki-Sub") == tDigits:
			fmt.Fprint(w, plainPage)
		case r.URL.Path == "/sub/h/"+tHash:
			fmt.Fprint(w, plainPage)
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()
	c := NewClient()
	c.HTTP, c.DeviceID = srv.Client(), "dev-1"
	for _, in := range []string{tDigits, srv.URL + "/sub/h/" + tHash, srv.URL + "/sub/" + tDigits} {
		if strings.HasPrefix(in, "4") {
			in = srv.URL + "/sub/" + in
		}
		base, code, err := c.Resolve(context.Background(), in)
		if err != nil || code != tCode || base != srv.URL {
			t.Fatal(in, base, code, err)
		}
	}
	// a short code the panel does not know
	if _, _, err := c.Resolve(context.Background(), srv.URL+"/sub/9999999999999999999"); err == nil || !strings.Contains(err.Error(), "full kanki") {
		t.Fatal(err)
	}
}

func TestRedirectsOnlyOnTheSameOrigin(t *testing.T) {
	other := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Errorf("the code reached another host: %s %q", r.URL.Path, r.Header.Get("X-Kanki-Sub"))
	}))
	defer other.Close()
	var srv *httptest.Server
	srv = httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/sub/me":
			http.Redirect(w, r, "/sub/me/", http.StatusMovedPermanently)
		case "/sub/me/":
			if r.Header.Get("X-Kanki-Sub") != tCode {
				http.Error(w, "lost header", 400)
				return
			}
			fmt.Fprint(w, page)
		case "/away":
			http.Redirect(w, r, other.URL+"/steal", http.StatusFound)
		}
	}))
	defer srv.Close()
	c := NewClient()
	c.HTTP = srv.Client()
	c.HTTP.Transport.(*http.Transport).TLSClientConfig.InsecureSkipVerify = true
	if _, err := c.fetch(context.Background(), srv.URL+"/sub/me", tCode, tCode, true); err != nil {
		t.Fatal(err)
	}
	if _, err := c.fetch(context.Background(), srv.URL+"/away", tCode, tCode, true); err == nil {
		t.Fatal("a cross-origin redirect was followed")
	}
}
