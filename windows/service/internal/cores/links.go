// Package cores reads the links of the non-WireGuard protocols (KANKI MIX,
// microtun, VLESS, Hysteria2) the way the Android app does (Kmix.kt, Mtun.kt,
// Xray.kt, Hysteria.kt), builds the configs of the Xray and Hysteria programs,
// and measures each protocol's real ping (WgHello.kt, KmixHello.kt, Probe.kt).
package cores

import (
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"math/rand/v2"
	"net"
	"net/url"
	"strconv"
	"strings"
)

// Protocol names, as the subscription and the app use them.
const (
	WireGuard = "wireguard"
	AmneziaWG = "amneziawg"
	KankiMix  = "kankimix"
	Microtun  = "microtun"
	VLESS     = "vless"
	Hysteria2 = "hysteria2"
)

// Kmix is a kanki:// link: kanki://KEY@host:port?fec=10-3&ports=4000-4100&gaming=1#name
type Kmix struct {
	Key              string
	Host             string
	Port             int
	PortLo, PortHi   int // 0 = one port only
	FecData, FecPrty int
	Gaming           bool
}

// ParseKmix finds the kanki:// link in text (the panel sends the bare link).
func ParseKmix(text string) (*Kmix, error) {
	i := strings.Index(text, "kanki://")
	if i < 0 {
		return nil, errors.New("kanki mix: no link")
	}
	raw := strings.Fields(text[i:])[0]
	u, err := url.Parse(raw)
	if err != nil || u.User == nil {
		return nil, errors.New("kanki mix: bad link")
	}
	key := strings.ToLower(u.User.Username())
	if b, err := hex.DecodeString(key); err != nil || len(b) != 32 {
		return nil, errors.New("kanki mix: bad key")
	}
	port, _ := strconv.Atoi(u.Port())
	if u.Hostname() == "" || port < 1 || port > 65535 {
		return nil, errors.New("kanki mix: no server address")
	}
	k := &Kmix{Key: key, Host: u.Hostname(), Port: port, FecData: 10, FecPrty: 3, Gaming: u.Query().Get("gaming") == "1"}
	if f := strings.Split(u.Query().Get("fec"), "-"); len(f) == 2 {
		if d, err := strconv.Atoi(f[0]); err == nil && d >= 1 && d <= 64 {
			k.FecData = d
		}
		if p, err := strconv.Atoi(f[1]); err == nil && p >= 0 && p <= 64 {
			k.FecPrty = p
		}
	}
	if r := strings.Split(u.Query().Get("ports"), "-"); len(r) == 2 {
		lo, e1 := strconv.Atoi(r[0])
		hi, e2 := strconv.Atoi(r[1])
		if e1 == nil && e2 == nil && lo >= 1 && hi >= lo && hi <= 65535 && hi-lo < 1000 {
			k.PortLo, k.PortHi = lo, hi
		}
	}
	return k, nil
}

// PickPort: one of the range or the main port, like the app (spreads users over ports).
func (k *Kmix) PickPort() int {
	if k.PortLo == 0 {
		return k.Port
	}
	n := k.PortHi - k.PortLo + 2
	if p := k.PortLo + rand.IntN(n); p <= k.PortHi {
		return p
	}
	return k.Port
}

// Hy2 is a hysteria2:// link.
type Hy2 struct {
	Auth, Host, SNI, ObfsPassword string
	Port                          int
	Insecure                      bool
}

func ParseHy2(text string) (*Hy2, error) {
	s := strings.TrimSpace(strings.Replace(strings.TrimSpace(text), "hy2://", "hysteria2://", 1))
	if i := strings.Index(s, "hysteria2://"); i >= 0 {
		s = strings.Fields(s[i:])[0]
	} else {
		return nil, errors.New("not a hysteria2 link")
	}
	u, err := url.Parse(s)
	if err != nil || u.Hostname() == "" {
		return nil, errors.New("hysteria2: no server address")
	}
	q := u.Query()
	h := &Hy2{Host: u.Hostname(), Port: 443, SNI: q.Get("sni"), Insecure: q.Get("insecure") == "1" || q.Get("insecure") == "true"}
	if u.User != nil {
		if p, ok := u.User.Password(); ok {
			h.Auth = u.User.Username() + ":" + p
		} else {
			h.Auth = u.User.Username()
		}
	}
	if p, err := strconv.Atoi(u.Port()); err == nil && p > 0 && p < 65536 {
		h.Port = p
	}
	if h.SNI == "" {
		h.SNI = h.Host
	}
	if q.Get("obfs") == "salamander" {
		h.ObfsPassword = q.Get("obfs-password")
	}
	return h, nil
}

// Config is the client config of the hysteria program (YAML; JSON is valid YAML).
// server is "ip:port" so the program never asks DNS (the kill switch would block it).
func (h *Hy2) Config(server string, socksPort int) string {
	c := map[string]any{
		"server":   server,
		"auth":     h.Auth,
		"tls":      map[string]any{"sni": h.SNI, "insecure": h.Insecure},
		"fastOpen": true,
		"socks5":   map[string]any{"listen": fmt.Sprintf("127.0.0.1:%d", socksPort), "disableUDP": false},
	}
	if h.ObfsPassword != "" {
		c["obfs"] = map[string]any{"type": "salamander", "salamander": map[string]any{"password": h.ObfsPassword}}
	}
	b, _ := json.Marshal(c)
	return string(b)
}

// Vless is a vless:// link.
type Vless struct {
	ID, Host string
	Port     int
	Q        map[string]string
}

func ParseVless(link string) (*Vless, error) {
	l := strings.TrimSpace(link)
	if !strings.HasPrefix(strings.ToLower(l), "vless://") {
		return nil, errors.New("not a vless link")
	}
	rest := l[len("vless://"):]
	if i := strings.Index(rest, "#"); i >= 0 {
		rest = rest[:i]
	}
	at := strings.LastIndex(rest, "@")
	if at < 0 {
		return nil, errors.New("vless: no user id")
	}
	id, _ := url.PathUnescape(rest[:at])
	hostq := rest[at+1:]
	hp, qs, _ := strings.Cut(hostq, "?")
	host, ps, err := net.SplitHostPort(strings.TrimRight(hp, "/"))
	if err != nil {
		return nil, errors.New("vless: bad server address")
	}
	port, err := strconv.Atoi(ps)
	if err != nil || port < 1 || port > 65535 || host == "" || id == "" {
		return nil, errors.New("vless: bad server address")
	}
	q := map[string]string{}
	for _, kv := range strings.Split(qs, "&") {
		if kv == "" {
			continue
		}
		k, v, _ := strings.Cut(kv, "=")
		if d, err := url.QueryUnescape(strings.ReplaceAll(v, "+", "%2B")); err == nil {
			v = d
		}
		q[k] = v
	}
	return &Vless{ID: id, Host: host, Port: port, Q: q}, nil
}

func isName(h string) bool {
	return strings.ContainsAny(h, "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ") && !strings.Contains(h, ":")
}

func csv(s string) []string {
	var out []string
	for _, p := range strings.Split(s, ",") {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

// stream is Xray.kt's stream(): transport and TLS / REALITY of the link.
func (v *Vless) stream() map[string]any {
	g := func(k string) string { return v.Q[k] }
	or := func(a, b string) string {
		if a != "" {
			return a
		}
		return b
	}
	netw := strings.ToLower(or(g("type"), "tcp"))
	switch netw {
	case "splithttp", "h2", "http":
		netw = "xhttp"
	case "mkcp":
		netw = "kcp"
	case "raw":
		netw = "tcp"
	}
	st := map[string]any{"network": netw}
	path := or(g("path"), "/")
	hh := g("host")
	if hh == "" && isName(v.Host) {
		hh = v.Host
	}
	switch netw {
	case "ws":
		st["wsSettings"] = map[string]any{"path": path, "host": hh}
	case "httpupgrade":
		st["httpupgradeSettings"] = map[string]any{"path": path, "host": hh}
	case "xhttp":
		x := map[string]any{"path": path, "host": hh, "mode": or(g("mode"), "auto")}
		var extra map[string]any
		if g("extra") != "" && json.Unmarshal([]byte(g("extra")), &extra) == nil {
			x["extra"] = extra
		}
		st["xhttpSettings"] = x
	case "grpc":
		gs := map[string]any{"serviceName": or(g("serviceName"), strings.Trim(g("path"), "/")), "multiMode": g("mode") == "multi"}
		if g("authority") != "" {
			gs["authority"] = g("authority")
		}
		st["grpcSettings"] = gs
	case "kcp":
		k := map[string]any{"mtu": 1350, "tti": 20, "uplinkCapacity": 12, "downlinkCapacity": 100, "congestion": false,
			"readBufferSize": 2, "writeBufferSize": 2, "header": map[string]any{"type": or(g("headerType"), "none")}}
		if g("seed") != "" {
			k["seed"] = g("seed")
		}
		st["kcpSettings"] = k
	default:
		if g("headerType") == "http" {
			st["tcpSettings"] = map[string]any{"header": map[string]any{"type": "http",
				"request": map[string]any{"path": csv(path), "headers": map[string]any{"Host": csv(g("host"))}}}}
		}
	}
	fp := or(g("fp"), "chrome")
	switch strings.ToLower(g("security")) {
	case "tls":
		sni := g("sni")
		if sni == "" {
			sni = or(strings.Split(g("host"), ",")[0], v.Host)
		}
		t := map[string]any{"serverName": sni, "fingerprint": fp}
		if g("alpn") != "" {
			t["alpn"] = csv(g("alpn"))
		}
		if g("allowInsecure") == "1" || g("allowInsecure") == "true" || g("insecure") == "1" {
			t["allowInsecure"] = true
		}
		st["security"], st["tlsSettings"] = "tls", t
	case "reality":
		sni := g("sni")
		if sni == "" && isName(v.Host) {
			sni = v.Host
		}
		r := map[string]any{"serverName": sni, "fingerprint": fp, "publicKey": g("pbk"), "shortId": g("sid"), "spiderX": or(g("spx"), "/")}
		if g("pqv") != "" {
			r["mldsa65Verify"] = g("pqv")
		}
		st["security"], st["realitySettings"] = "reality", r
	}
	return st
}

// XrayConfig is Xray.kt's config(): a SOCKS inbound on 127.0.0.1 and the link
// as the outbound, aimed at serverIP so Xray never asks DNS for the server.
func (v *Vless) XrayConfig(serverIP string, socksPort int) string {
	user := map[string]any{"id": v.ID, "encryption": "none"}
	if e := v.Q["encryption"]; e != "" {
		user["encryption"] = e
	}
	if f := v.Q["flow"]; f != "" {
		user["flow"] = f
	}
	out := map[string]any{
		"tag": "proxy", "protocol": "vless",
		"settings":       map[string]any{"vnext": []any{map[string]any{"address": serverIP, "port": v.Port, "users": []any{user}}}},
		"streamSettings": v.stream(),
	}
	cfg := map[string]any{
		"log": map[string]any{"loglevel": "warning"},
		"inbounds": []any{map[string]any{
			"tag": "socks", "listen": "127.0.0.1", "port": socksPort, "protocol": "socks",
			"settings": map[string]any{"auth": "noauth", "udp": true}, "sniffing": map[string]any{"enabled": false},
		}},
		"outbounds": []any{out, map[string]any{"protocol": "blackhole", "tag": "block"}},
	}
	b, _ := json.Marshal(cfg)
	return string(b)
}
