package sub

import (
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"unicode"
)

// The shapes of a subscription the user may paste, and the build's panel addresses: the same rules as
// PanelLink.kt of the Android app (1.45.1).

// InputKind is what the login box got.
type InputKind int

const (
	// KindCode is kanki-<id>-<code>
	KindCode InputKind = iota + 1
	// KindBare is only the digits of the code (a panel from 3.67.2 finds the account)
	KindBare
	// KindHash is /sub/h/<hash> (good for that panel only; its page names the code)
	KindHash
)

// Input is one parsed subscription; Origin "" means the build's own hosts.
type Input struct {
	Kind   InputKind
	Origin string
	Value  string
}

const maxInput = 4096

var (
	fullCodeRe     = regexp.MustCompile(`[A-Za-z]{2,12}-\d{1,12}-[A-Za-z0-9]{4,64}`)
	fullCodeOnlyRe = regexp.MustCompile(`^[A-Za-z]{2,12}-\d{1,12}-[A-Za-z0-9]{4,64}$`)
	bareOnlyRe     = regexp.MustCompile(`^[0-9]{10,32}$`)
	hashOnlyRe     = regexp.MustCompile(`^[0-9a-f]{40}$`)
	hostRe         = regexp.MustCompile(`^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?)*$`)
	inputLinkRe    = regexp.MustCompile(`(?i)(?:^|[^@\w.-])((?:https?://)?(?:[a-z0-9-]+\.)+[a-z0-9-]+(?::\d{1,5})?/(?:sub|api/config)/[^\s"'<>]*)`)
)

// invisible is an RTL mark, an isolate, a zero-width character, the BOM or a control character.
func invisible(r rune) bool {
	return unicode.IsControl(r) || (r >= 0x200b && r <= 0x200f) || (r >= 0x202a && r <= 0x202e) ||
		(r >= 0x2066 && r <= 0x2069) || r == 0xfeff || r == 0x00ad
}

// Clean drops invisible characters (line breaks become spaces) and turns Persian/Arabic digits into ASCII.
func Clean(s string) string {
	if len(s) > maxInput {
		s = s[:maxInput]
	}
	var b strings.Builder
	for _, r := range s {
		switch {
		case r == '\n' || r == '\r' || r == '\t':
			b.WriteByte(' ')
		case invisible(r):
		case r >= '۰' && r <= '۹':
			b.WriteRune('0' + (r - '۰'))
		case r >= '٠' && r <= '٩':
			b.WriteRune('0' + (r - '٠'))
		default:
			b.WriteRune(r)
		}
	}
	return strings.TrimSpace(b.String())
}

// Origin is "https://host[:port]" of one entry, whatever path follows it (/admin, /panel, /sub/me, a trailing /);
// a missing scheme or http:// becomes https://. False for a user part, another scheme, a bad host or port.
func Origin(entry string) (string, bool) {
	s := strings.Trim(Clean(entry), `"'<> `)
	if s == "" {
		return "", false
	}
	if i := strings.Index(s, "://"); i >= 0 {
		p := strings.ToLower(s[:i])
		if p != "https" && p != "http" {
			return "", false
		}
		s = s[i+3:]
	}
	if i := strings.IndexAny(s, `/?#\`); i >= 0 {
		s = s[:i]
	}
	if s == "" || strings.ContainsAny(s, "@ ") {
		return "", false
	}
	host, port := s, 443
	if i := strings.LastIndexByte(s, ':'); i >= 0 {
		p, err := strconv.Atoi(s[i+1:])
		if err != nil {
			return "", false
		}
		host, port = s[:i], p
	}
	host = strings.TrimRight(strings.ToLower(host), ".")
	if port < 1 || port > 65535 || len(host) > 253 || !hostRe.MatchString(host) {
		return "", false
	}
	if port == 443 {
		return "https://" + host, true
	}
	return "https://" + host + ":" + strconv.Itoa(port), true
}

// Hosts reads a list of panel addresses split by commas, semicolons or white space.
func Hosts(spec string) []string {
	var out []string
	for _, h := range strings.FieldsFunc(spec, func(r rune) bool { return r == ',' || r == ';' || unicode.IsSpace(r) }) {
		if o, ok := Origin(h); ok {
			out = append(out, o)
		}
	}
	return dedupe(out)
}

// ParseInput reads what the user pasted; false when it is none of the shapes.
func ParseInput(raw string) (Input, bool) {
	s := Clean(raw)
	if s == "" {
		return Input{}, false
	}
	if m := inputLinkRe.FindStringSubmatch(s); m != nil {
		if in, ok := fromLink(m[1]); ok {
			return in, true
		}
	}
	if c := fullCodeRe.FindString(s); c != "" {
		return Input{Kind: KindCode, Value: c}, true
	}
	digits := strings.NewReplacer(" ", "", "-", "").Replace(s)
	if bareOnlyRe.MatchString(digits) {
		return Input{Kind: KindBare, Value: digits}, true
	}
	return Input{}, false
}

func fromLink(link string) (Input, bool) {
	o, ok := Origin(link)
	if !ok {
		return Input{}, false
	}
	rest := link
	if i := strings.Index(rest, "://"); i >= 0 {
		rest = rest[i+3:]
	}
	if i := strings.IndexByte(rest, '/'); i >= 0 {
		rest = rest[i:]
	}
	path, query := rest, ""
	if i := strings.IndexByte(path, '?'); i >= 0 {
		path, query = path[:i], path[i+1:]
	}
	if i := strings.IndexByte(query, '#'); i >= 0 {
		query = query[:i]
	}
	if i := strings.IndexByte(path, '#'); i >= 0 {
		path = path[:i]
	}
	if q, err := url.ParseQuery(query); err == nil {
		if v := q.Get("sub"); v != "" {
			if fullCodeOnlyRe.MatchString(v) {
				return Input{Kind: KindCode, Origin: o, Value: v}, true
			}
			if bareOnlyRe.MatchString(v) {
				return Input{Kind: KindBare, Origin: o, Value: v}, true
			}
		}
	}
	var segs []string
	for _, p := range strings.Split(path, "/") {
		if p != "" {
			segs = append(segs, p)
		}
	}
	i := -1
	for j, p := range segs {
		if p == "sub" {
			i = j
			break
		}
	}
	if i < 0 {
		return Input{}, false
	}
	segs = segs[i+1:]
	for len(segs) > 0 && segs[0] == "sub" {
		segs = segs[1:]
	}
	if len(segs) == 0 {
		return Input{}, false
	}
	switch first := segs[0]; {
	case first == "h":
		if len(segs) > 1 && hashOnlyRe.MatchString(strings.ToLower(segs[1])) {
			return Input{Kind: KindHash, Origin: o, Value: strings.ToLower(segs[1])}, true
		}
	case fullCodeOnlyRe.MatchString(first):
		return Input{Kind: KindCode, Origin: o, Value: first}, true
	case bareOnlyRe.MatchString(first):
		return Input{Kind: KindBare, Origin: o, Value: first}, true
	}
	return Input{}, false
}

// codeInPage is the account's code named in its own page; endsWith: the digits the user typed.
func codeInPage(page, endsWith string) string {
	for _, c := range fullCodeRe.FindAllString(page, -1) {
		if endsWith == "" || strings.HasSuffix(c, "-"+endsWith) {
			return c
		}
	}
	return ""
}

// looksLikePage: a Kanki subscription page, not a relay's or CDN's error page served with 200
func looksLikePage(page string) bool {
	return strings.Contains(page, "/api/config/") || dataRe.MatchString(page) || h1Re.MatchString(page)
}
