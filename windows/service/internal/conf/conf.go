// Package conf reads a WireGuard / AmneziaWG config file (the text the panel's
// /api/config/... link gives) and turns it into the device's UAPI settings.
//
// Only the keys a tunnel needs are accepted. Script keys (PreUp, PostUp, ...)
// are refused: the service runs as SYSTEM and must never run a command that
// came from a downloaded file.
package conf

import (
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"strings"
)

// Key is a Curve25519 key (private, public or preshared).
type Key [32]byte

func (k Key) Hex() string { return hex.EncodeToString(k[:]) }

func (k Key) IsZero() bool { return k == Key{} }

func parseKey(s string) (Key, error) {
	var k Key
	b, err := base64.StdEncoding.DecodeString(strings.TrimSpace(s))
	if err != nil || len(b) != len(k) {
		return k, errors.New("not a 32-byte base64 key")
	}
	copy(k[:], b)
	return k, nil
}

// Peer is the [Peer] part. The panel always gives exactly one.
type Peer struct {
	PublicKey    Key
	PresharedKey Key
	// Host and Port of Endpoint; Host may be a name, resolved before connecting.
	Host       string
	Port       uint16
	AllowedIPs []netip.Prefix
	Keepalive  int
}

// Config is one tunnel.
type Config struct {
	PrivateKey Key
	Addresses  []netip.Prefix
	DNS        []netip.Addr
	MTU        int
	// Obfuscation of AmneziaWG (Jc, S1, H1, I1, ...), lower-case UAPI key -> value.
	// Empty means plain WireGuard.
	AWG  [][2]string
	Peer Peer
}

// IsAWG says whether the config uses AmneziaWG obfuscation.
func (c *Config) IsAWG() bool { return len(c.AWG) > 0 }

// HasIPv6 says whether the tunnel itself carries IPv6 (it has an IPv6 address).
func (c *Config) HasIPv6() bool {
	for _, a := range c.Addresses {
		if a.Addr().Is6() {
			return true
		}
	}
	return false
}

// AmneziaWG keys, in the order the panel writes them.
var awgKeys = map[string]bool{
	"jc": true, "jmin": true, "jmax": true,
	"s1": true, "s2": true, "s3": true, "s4": true,
	"h1": true, "h2": true, "h3": true, "h4": true,
	"i1": true, "i2": true, "i3": true, "i4": true, "i5": true,
}

var scriptKeys = map[string]bool{"preup": true, "postup": true, "predown": true, "postdown": true}

// Parse reads the config text.
func Parse(text string) (*Config, error) {
	c := &Config{}
	section := ""
	peers := 0
	for n, raw := range strings.Split(text, "\n") {
		line := strings.TrimSpace(raw)
		if line == "" || line[0] == '#' || line[0] == ';' {
			continue
		}
		if strings.HasPrefix(line, "[") {
			section = strings.ToLower(strings.Trim(line, "[] \t"))
			if section == "peer" {
				peers++
			}
			if section != "interface" && section != "peer" {
				return nil, fmt.Errorf("line %d: unknown section [%s]", n+1, section)
			}
			continue
		}
		k, v, ok := strings.Cut(line, "=")
		if !ok {
			return nil, fmt.Errorf("line %d: expected key = value", n+1)
		}
		key := strings.ToLower(strings.TrimSpace(k))
		val := strings.TrimSpace(v)
		if scriptKeys[key] {
			return nil, fmt.Errorf("line %d: %s is not allowed (scripts never run)", n+1, k)
		}
		if strings.ContainsAny(val, "\r\n") {
			return nil, fmt.Errorf("line %d: bad value", n+1)
		}
		var err error
		switch section {
		case "interface":
			err = c.setInterface(key, val)
		case "peer":
			if peers > 1 {
				return nil, errors.New("only one [Peer] is supported")
			}
			err = c.Peer.set(key, val)
		default:
			err = errors.New("key outside a section")
		}
		if err != nil {
			return nil, fmt.Errorf("line %d (%s): %w", n+1, strings.TrimSpace(k), err)
		}
	}
	return c, c.check(peers)
}

func (c *Config) setInterface(key, val string) error {
	var err error
	switch key {
	case "privatekey":
		c.PrivateKey, err = parseKey(val)
	case "address":
		for _, s := range list(val) {
			p, e := netip.ParsePrefix(s)
			if e != nil {
				a, e2 := netip.ParseAddr(s)
				if e2 != nil {
					return e
				}
				p = netip.PrefixFrom(a, a.BitLen())
			}
			c.Addresses = append(c.Addresses, p)
		}
	case "dns":
		for _, s := range list(val) {
			a, e := netip.ParseAddr(s)
			if e != nil {
				// search domains are not used by this client
				continue
			}
			c.DNS = append(c.DNS, a.Unmap())
		}
	case "mtu":
		c.MTU, err = strconv.Atoi(val)
		if err == nil && (c.MTU < 576 || c.MTU > 65535) {
			err = errors.New("MTU out of range")
		}
	case "listenport", "table", "fwmark", "saveconfig":
		// not used on Windows
	default:
		if awgKeys[key] {
			if val == "" {
				return nil
			}
			c.AWG = append(c.AWG, [2]string{key, val})
			return nil
		}
		return errors.New("unknown key")
	}
	return err
}

func (p *Peer) set(key, val string) error {
	var err error
	switch key {
	case "publickey":
		p.PublicKey, err = parseKey(val)
	case "presharedkey":
		p.PresharedKey, err = parseKey(val)
	case "endpoint":
		host, port, e := net.SplitHostPort(val)
		if e != nil {
			return e
		}
		n, e := strconv.ParseUint(port, 10, 16)
		if e != nil || n == 0 {
			return errors.New("bad port")
		}
		if host == "" || len(host) > 253 {
			return errors.New("bad host")
		}
		p.Host, p.Port = host, uint16(n)
	case "allowedips":
		for _, s := range list(val) {
			pf, e := netip.ParsePrefix(s)
			if e != nil {
				return e
			}
			p.AllowedIPs = append(p.AllowedIPs, pf.Masked())
		}
	case "persistentkeepalive":
		if val == "off" {
			return nil
		}
		p.Keepalive, err = strconv.Atoi(val)
		if err == nil && (p.Keepalive < 0 || p.Keepalive > 65535) {
			err = errors.New("out of range")
		}
	default:
		return errors.New("unknown key")
	}
	return err
}

func (c *Config) check(peers int) error {
	switch {
	case c.PrivateKey.IsZero():
		return errors.New("no PrivateKey")
	case len(c.Addresses) == 0:
		return errors.New("no Address")
	case peers != 1:
		return errors.New("need exactly one [Peer]")
	case c.Peer.PublicKey.IsZero():
		return errors.New("no PublicKey in [Peer]")
	case c.Peer.Host == "":
		return errors.New("no Endpoint in [Peer]")
	case len(c.Peer.AllowedIPs) == 0:
		return errors.New("no AllowedIPs in [Peer]")
	}
	return nil
}

func list(v string) []string {
	var out []string
	for _, s := range strings.Split(v, ",") {
		if s = strings.TrimSpace(s); s != "" {
			out = append(out, s)
		}
	}
	return out
}

// UAPI is the text for device.IpcSet. endpoint is the resolved address of
// Peer.Host (the service resolves it before the firewall goes up).
func (c *Config) UAPI(endpoint netip.AddrPort) string {
	var b strings.Builder
	fmt.Fprintf(&b, "private_key=%s\nreplace_peers=true\n", c.PrivateKey.Hex())
	for _, kv := range c.AWG {
		fmt.Fprintf(&b, "%s=%s\n", kv[0], kv[1])
	}
	fmt.Fprintf(&b, "public_key=%s\n", c.Peer.PublicKey.Hex())
	if !c.Peer.PresharedKey.IsZero() {
		fmt.Fprintf(&b, "preshared_key=%s\n", c.Peer.PresharedKey.Hex())
	}
	fmt.Fprintf(&b, "endpoint=%s\n", endpoint)
	if c.Peer.Keepalive > 0 {
		fmt.Fprintf(&b, "persistent_keepalive_interval=%d\n", c.Peer.Keepalive)
	}
	b.WriteString("replace_allowed_ips=true\n")
	for _, p := range c.Peer.AllowedIPs {
		fmt.Fprintf(&b, "allowed_ip=%s\n", p)
	}
	return b.String()
}
