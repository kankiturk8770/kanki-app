// Package sub reads the user's subscription page on the Kanki panel
// (/sub/<code>) the same way the Android app does (SubscriptionApi.kt), and
// downloads the config of one server.
package sub

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"html"
	"io"
	"net"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Kanki panel hosts; each one serves the same page and config links.
const (
	DefaultHost  = "https://sub.kanki.pro:2053"
	FallbackHost = "https://fi.kankipro.cafe:2053"
	IranHost     = "https://s1s.polimax.shop:2053"
)

// Server is one config the Windows client can run.
type Server struct {
	ID      string  `json:"id"`    // stable key: node + protocol
	Node    string  `json:"node"`  // panel node id ("local", "n3", "n3@tunnel"...)
	Name    string  `json:"name"`  // heading on the page, like GERMANY-GAMING
	Proto   string  `json:"proto"` // wireguard | amneziawg
	Country string  `json:"cc,omitempty"`
	City    string  `json:"city,omitempty"`
	Tunnel  bool    `json:"tunnel"` // goes through a server inside Iran
	Lat     float64 `json:"lat,omitempty"`
	Lon     float64 `json:"lon,omitempty"`
	// URL (config download) or Link (mtun:// and vless:// come inside the page):
	// both carry the user's key and are cleared before they go to the UI
	URL  string `json:"url,omitempty"`
	Link string `json:"link,omitempty"`
}

// Account is what the page says about the user.
type Account struct {
	Base     string   `json:"base"`
	Code     string   `json:"code,omitempty"`
	Username string   `json:"username"`
	Status   string   `json:"status"`
	Expires  int64    `json:"expires"` // unix seconds; 0 = never
	LimitGB  float64  `json:"limit_gb"`
	UsedGB   float64  `json:"used_gb"`
	Notice   string   `json:"notice,omitempty"`
	Support  string   `json:"support,omitempty"`
	MaxConn  int      `json:"max_conn,omitempty"`
	Servers  []Server `json:"servers"`
	SubBase  string   `json:"sub_base,omitempty"`
	Fetched  int64    `json:"fetched"`
}

var (
	fullRe = regexp.MustCompile(`(https://[^/\s]+)\S*?/sub/([A-Za-z]{2,12}-\d+-[A-Za-z0-9]{4,})`)
	codeRe = regexp.MustCompile(`\b[A-Za-z]{2,12}-\d+-[A-Za-z0-9]{4,}`)
)

// Normalize takes a full link or just the code and gives (panel address, code).
func Normalize(input string) (string, string, error) {
	s := strings.TrimSpace(input)
	if m := fullRe.FindStringSubmatch(s); m != nil {
		return m[1], m[2], nil
	}
	if c := codeRe.FindString(s); c != "" {
		return DefaultHost, c, nil
	}
	return "", "", errors.New("not a subscription link or code")
}

// Client fetches pages; it races every Kanki host and remembers the winner.
type Client struct {
	HTTP *http.Client
	// DeviceID goes with every request (the panel can count and limit devices)
	DeviceID  string
	mu        sync.Mutex
	preferred string
	subBase   string
}

// Resolver sends DNS queries from this process itself (not through the
// system's DNS Client service), so they still work while the kill switch
// blocks everything else.
var Resolver = &net.Resolver{PreferGo: true}

func NewClient() *Client {
	tr := http.DefaultTransport.(*http.Transport).Clone()
	tr.DialContext = (&net.Dialer{Timeout: 15 * time.Second, Resolver: Resolver}).DialContext
	return &Client{HTTP: &http.Client{Timeout: 25 * time.Second, Transport: tr}}
}

func (c *Client) kankiHosts() []string {
	c.mu.Lock()
	defer c.mu.Unlock()
	hs := []string{FallbackHost, IranHost}
	if c.subBase != "" {
		hs = append(hs, c.subBase)
	}
	hs = append(hs, DefaultHost)
	if c.preferred != "" {
		hs = append([]string{c.preferred}, hs...)
	}
	return dedupe(hs)
}

func dedupe(ss []string) []string {
	seen := map[string]bool{}
	var out []string
	for _, s := range ss {
		if !seen[s] {
			seen[s] = true
			out = append(out, s)
		}
	}
	return out
}

func (c *Client) isKanki(base string) bool {
	for _, h := range c.kankiHosts() {
		if h == base {
			return true
		}
	}
	return false
}

// alternates gives the same path on every Kanki host (only for Kanki hosts).
func (c *Client) alternates(base, path string) []string {
	if !c.isKanki(base) {
		return []string{base + path}
	}
	var out []string
	for _, h := range append([]string{base}, c.kankiHosts()...) {
		out = append(out, h+path)
	}
	return dedupe(out)
}

func (c *Client) get(ctx context.Context, url string) (string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("User-Agent", "KankiVPN-Windows")
	if c.DeviceID != "" {
		req.Header.Set("X-Kanki-Device", c.DeviceID)
	}
	// a panel that knows sealed configs answers with one (see seal.go)
	req.Header.Set("X-Kanki-Seal", sealVersion)
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return "", err
	}
	if code := codeOf(url); code != "" && isSealed(b) {
		return open(b, code, c.DeviceID)
	}
	return string(b), nil
}

// race asks all urls; the first good answer wins. The first url starts 300ms
// early so a healthy preferred host always stays the one in use.
func (c *Client) race(ctx context.Context, urls []string) (string, string, error) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	type res struct {
		url, body string
		err       error
	}
	ch := make(chan res, len(urls))
	for i, u := range urls {
		go func(i int, u string) {
			if i > 0 {
				select {
				case <-time.After(300 * time.Millisecond):
				case <-ctx.Done():
					ch <- res{u, "", ctx.Err()}
					return
				}
			}
			b, err := c.get(ctx, u)
			ch <- res{u, b, err}
		}(i, u)
	}
	var last error
	for range urls {
		r := <-ch
		if r.err == nil {
			for _, h := range c.kankiHosts() {
				if strings.HasPrefix(r.url, h+"/") {
					c.mu.Lock()
					c.preferred = h
					c.mu.Unlock()
				}
			}
			return r.url, r.body, nil
		}
		last = r.err
	}
	return "", "", last
}

// Load fetches and reads the subscription page.
func (c *Client) Load(ctx context.Context, base, code string) (*Account, error) {
	url, body, err := c.race(ctx, c.alternates(base, "/sub/"+code))
	if err != nil {
		return nil, err
	}
	host := url[:strings.Index(url, "/sub/")]
	a, err := Parse(body, host)
	if err != nil {
		return nil, err
	}
	a.Base, a.Code = base, code
	if a.SubBase != "" {
		c.mu.Lock()
		c.subBase = a.SubBase
		c.mu.Unlock()
	}
	return a, nil
}

// Config downloads the config text of one server, trying every host.
func (c *Client) Config(ctx context.Context, a *Account, s Server) (string, error) {
	for _, h := range c.kankiHosts() {
		if strings.HasPrefix(s.URL, h+"/") {
			_, body, err := c.race(ctx, c.alternates(h, strings.TrimPrefix(s.URL, h)))
			return body, err
		}
	}
	return c.get(ctx, s.URL)
}

var (
	scriptRe  = regexp.MustCompile(`(?is)<script.*?</script>|<style.*?</style>`)
	dataRe    = regexp.MustCompile(`(?is)<script[^>]*id="data"[^>]*>(.*?)</script>`)
	tagRe     = regexp.MustCompile(`<[^>]+>`)
	h1Re      = regexp.MustCompile(`(?is)<h1[^>]*>(.*?)</h1>`)
	h3Re      = regexp.MustCompile(`(?is)<h3[^>]*>(.*?)</h3>`)
	linkRe    = regexp.MustCompile(`(?:https?://[^"'\s<>]+)?/api/config/(\d+)/([a-z0-9]+(?:/[a-z0-9]+)?)\?[^"'\s<>]+`)
	nodeRe    = regexp.MustCompile(`[?&]node=([^&]+)`)
	statusSet = map[string]bool{"Active": true, "Expired": true, "Disabled": true, "Limited": true, "Frozen": true, "Inactive": true, "Suspended": true}
)

// suffix of the node id of a config that goes through the server's tunnel
const tunnelSuffix = "@tunnel"

func strip(s string) string {
	return strings.TrimSpace(html.UnescapeString(tagRe.ReplaceAllString(s, "")))
}

func attr(page, name string) string {
	m := regexp.MustCompile(name + `="([^"]*)"`).FindStringSubmatch(page)
	if m == nil {
		return ""
	}
	return strings.TrimSpace(m[1])
}

type pageData struct {
	SubBase string `json:"sub_base"`
	AppMsg  *struct {
		Text string `json:"text"`
	} `json:"app_msg"`
	Support string `json:"support"`
	MaxConn int    `json:"max_conn"`
	Nodes   []struct {
		ID      string   `json:"id"`
		Name    string   `json:"name"`
		ExitCC  string   `json:"exit_cc"`
		ExitCty string   `json:"exit_city"`
		Lat     *float64 `json:"exit_lat"`
		Lon     *float64 `json:"exit_lon"`
		Mtun    *string  `json:"mtun"`
	} `json:"nodes"`
	Xray []struct {
		Name    string   `json:"name"`
		Link    string   `json:"link"`
		ExitCC  string   `json:"exit_cc"`
		ExitCty string   `json:"exit_city"`
		Lat     *float64 `json:"exit_lat"`
		Lon     *float64 `json:"exit_lon"`
	} `json:"xray"`
}

type exit struct {
	cc, city string
	lat, lon float64
}

func exitOf(cc, city string, lat, lon *float64) (exit, bool) {
	cc = strings.ToUpper(strings.TrimSpace(cc))
	if len(cc) != 2 {
		return exit{}, false
	}
	e := exit{cc: cc, city: city}
	if lat != nil && lon != nil {
		e.lat, e.lon = *lat, *lon
	}
	return e, true
}

// Parse reads a subscription page. base is the host it came from: config
// links carry private keys, so only links on that host are kept.
func Parse(page, base string) (*Account, error) {
	clean := scriptRe.ReplaceAllString(page, "")
	a := &Account{Fetched: time.Now().Unix()}
	if m := h1Re.FindStringSubmatch(clean); m != nil {
		a.Username = strip(m[1])
	}
	a.Status = attr(page, "data-status")
	if !statusSet[a.Status] {
		a.Status = ""
		for _, line := range strings.Split(html.UnescapeString(tagRe.ReplaceAllString(clean, "\n")), "\n") {
			if l := strings.TrimSpace(line); statusSet[l] {
				a.Status = l
				break
			}
		}
	}
	if t, err := time.Parse(time.RFC3339, attr(page, "data-expires")); err == nil && t.Year() >= 2000 {
		a.Expires = t.Unix()
	}
	a.LimitGB, _ = strconv.ParseFloat(attr(page, "data-traffic-limit"), 64)
	a.UsedGB, _ = strconv.ParseFloat(attr(page, "data-traffic-used"), 64)

	var data pageData
	if m := dataRe.FindStringSubmatch(page); m != nil {
		_ = json.Unmarshal([]byte(m[1]), &data)
	}
	if b := strings.TrimRight(strings.TrimSpace(data.SubBase), "/"); strings.HasPrefix(b, "https://") && len(b) < 120 && !strings.Contains(b, " ") {
		a.SubBase = b
	}
	if data.AppMsg != nil {
		a.Notice = strings.TrimSpace(data.AppMsg.Text)
	}
	a.Support, a.MaxConn = strings.TrimSpace(data.Support), data.MaxConn
	exits := map[string]exit{}
	for _, n := range data.Nodes {
		if e, ok := exitOf(n.ExitCC, n.ExitCty, n.Lat, n.Lon); ok {
			exits[n.ID] = e
		}
	}

	type heading struct {
		at   int
		name string
	}
	var heads []heading
	for _, m := range h3Re.FindAllStringSubmatchIndex(clean, -1) {
		name := strip(clean[m[2]:m[3]])
		switch strings.ToLower(name) {
		case "openvpn", "wireguard", "amneziawg", "hysteria2":
			continue
		}
		heads = append(heads, heading{m[0], name})
	}
	seen := map[string]bool{}
	for _, m := range linkRe.FindAllStringSubmatchIndex(clean, -1) {
		proto, extra, _ := strings.Cut(clean[m[4]:m[5]], "/")
		raw := html.UnescapeString(clean[m[0]:m[1]])
		switch proto {
		case "wireguard", "amneziawg":
			if extra != "" {
				continue // the QR and URI buttons of the same config
			}
		case "kankimix", "hysteria2":
			// the page only has their QR button; the same address without /qr gives the link text
			raw = strings.Replace(raw, "/"+proto+"/"+extra+"?", "/"+proto+"?", 1)
		default:
			continue // OpenVPN is not part of the Windows client
		}
		url := raw
		if !strings.HasPrefix(raw, "http") {
			url = base + raw
		}
		if !strings.HasPrefix(url, base+"/") || seen[url] {
			continue
		}
		seen[url] = true
		node := "local"
		if nm := nodeRe.FindStringSubmatch(raw); nm != nil {
			node = nm[1]
		}
		name := node
		for _, h := range heads {
			if h.at < m[0] {
				name = h.name
			}
		}
		s := Server{
			ID: node + "/" + proto, Node: node, Name: name, Proto: proto, URL: url,
			Tunnel: strings.HasSuffix(node, tunnelSuffix),
		}
		s.setExit(exits[strings.TrimSuffix(node, tunnelSuffix)])
		a.Servers = append(a.Servers, s)
	}
	// microtun: its link is inside the page data, one per server (null = not for this user)
	for _, n := range data.Nodes {
		if n.Mtun == nil || !strings.HasPrefix(strings.TrimSpace(*n.Mtun), "mtun://") {
			continue
		}
		s := Server{ID: n.ID + "/microtun", Node: n.ID, Name: n.Name, Proto: "microtun", Link: strings.TrimSpace(*n.Mtun),
			Tunnel: strings.HasSuffix(n.ID, tunnelSuffix)}
		if s.Name == "" {
			s.Name = n.ID
		}
		s.setExit(exits[strings.TrimSuffix(n.ID, tunnelSuffix)])
		a.Servers = append(a.Servers, s)
	}
	// Xray: only VLESS runs on Windows
	for i, x := range data.Xray {
		l := strings.TrimSpace(x.Link)
		if !strings.HasPrefix(strings.ToLower(l), "vless://") {
			continue
		}
		s := Server{ID: fmt.Sprintf("xray/%d/%s", i, x.Name), Node: "xray", Name: x.Name, Proto: "vless", Link: l}
		e, _ := exitOf(x.ExitCC, x.ExitCty, x.Lat, x.Lon)
		s.setExit(e)
		a.Servers = append(a.Servers, s)
	}
	if a.Username == "" && len(a.Servers) == 0 {
		return nil, errors.New("this is not a Kanki subscription page")
	}
	return a, nil
}

func (s *Server) setExit(e exit) {
	if e.cc != "" {
		s.Country, s.City, s.Lat, s.Lon = e.cc, e.city, e.lat, e.lon
	}
}

// codeOf finds the subscription code in a panel address (/sub/CODE or ?sub=CODE).
func codeOf(u string) string {
	if i := strings.Index(u, "/sub/"); i >= 0 {
		return strings.SplitN(u[i+5:], "?", 2)[0]
	}
	if m := regexp.MustCompile(`[?&]sub=([^&]+)`).FindStringSubmatch(u); m != nil {
		return m[1]
	}
	return ""
}
