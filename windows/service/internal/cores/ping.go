package cores

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"hash"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"time"

	"golang.org/x/crypto/blake2s"
	"golang.org/x/crypto/chacha20poly1305"
	"golang.org/x/crypto/curve25519"
)

// Ping timings: Probe.kt waits about as long.
const (
	tries   = 2
	waitFor = 1500 * time.Millisecond
)

// exchange sends hello and waits for an answer ok() accepts; the best round trip in ms.
func exchange(to netip.AddrPort, hello func() [][]byte, ok func([]byte) bool) (int64, error) {
	c, err := net.ListenUDP("udp", nil)
	if err != nil {
		return 0, err
	}
	defer c.Close()
	best := int64(-1)
	buf := make([]byte, 2048)
	for i := 0; i < tries; i++ {
		t0 := time.Now()
		for _, p := range hello() {
			_, _ = c.WriteToUDPAddrPort(p, to)
		}
		_ = c.SetReadDeadline(t0.Add(waitFor))
		for {
			n, from, err := c.ReadFromUDPAddrPort(buf)
			if err != nil {
				break
			}
			if from.Addr().Unmap() == to.Addr().Unmap() && ok(buf[:n]) {
				if ms := max(1, time.Since(t0).Milliseconds()); best < 0 || ms < best {
					best = ms
				}
				break
			}
		}
	}
	if best < 0 {
		return 0, errors.New("no answer")
	}
	return best, nil
}

// TCP: time to open a TCP connection to the server (VLESS), best of two.
func TCP(ctx context.Context, to netip.AddrPort) (int64, error) {
	best := int64(-1)
	for i := 0; i < tries; i++ {
		t0 := time.Now()
		d := net.Dialer{Timeout: waitFor * 2}
		c, err := d.DialContext(ctx, "tcp", to.String())
		if err != nil {
			continue
		}
		c.Close()
		if ms := max(1, time.Since(t0).Milliseconds()); best < 0 || ms < best {
			best = ms
		}
	}
	if best < 0 {
		return 0, errors.New("no answer")
	}
	return best, nil
}

// ---------------------------------------------------------------- KANKI MIX (KmixHello.kt)

const kmixPing, kmixPong = 0x03, 0x04

// KmixPing: a ping packet sealed with the user's key; the server answers a pong.
func KmixPing(k *Kmix, to netip.AddrPort) (int64, error) {
	key, _ := hex.DecodeString(k.Key)
	aead, err := chacha20poly1305.New(key)
	if err != nil {
		return 0, err
	}
	var payload [16]byte
	return exchange(to, func() [][]byte {
		_, _ = rand.Read(payload[:])
		nonce := make([]byte, 12)
		_, _ = rand.Read(nonce)
		var padb [1]byte
		_, _ = rand.Read(padb[:])
		plain := make([]byte, 3+len(payload)+int(padb[0])%65)
		plain[0], plain[1], plain[2] = kmixPing, 0, byte(len(payload))
		copy(plain[3:], payload[:])
		return [][]byte{aead.Seal(nonce, nonce, plain, nil)}
	}, func(p []byte) bool {
		if len(p) < 12+16+3 {
			return false
		}
		plain, err := aead.Open(nil, p[:12], p[12:], nil)
		if err != nil || len(plain) < 3 {
			return false
		}
		n := int(plain[1])<<8 | int(plain[2])
		return plain[0] == kmixPong && n == len(payload) && len(plain) >= 3+n && string(plain[3:3+n]) == string(payload[:])
	})
}

// ---------------------------------------------------------------- WireGuard / AmneziaWG (WgHello.kt)

// WgHello is one handshake initiation; the server's answer (or its cookie
// reply when it is busy) proves the keys and the obfuscation are right.
type WgHello struct {
	priv, pub, serverPub, psk [32]byte
	Host                      string
	Port                      int
	jc, jmin, jmax, s1, s2    int
	h1, h2, h3                [2]uint32
}

var (
	construction = []byte("Noise_IKpsk2_25519_ChaChaPoly_BLAKE2s")
	identifier   = []byte("WireGuard v1 zx2c4 Jason@zx2c4.com")
	labelMac1    = []byte("mac1----")
)

func b2s(parts ...[]byte) []byte {
	h, _ := blake2s.New256(nil)
	for _, p := range parts {
		h.Write(p)
	}
	return h.Sum(nil)
}

func hmacB2s(key, data []byte) []byte {
	m := hmac.New(func() hash.Hash { h, _ := blake2s.New256(nil); return h }, key)
	m.Write(data)
	return m.Sum(nil)
}

func kdf(key, input []byte, n int) [][]byte {
	t0 := hmacB2s(key, input)
	out := make([][]byte, 0, n)
	var prev []byte
	for i := 1; i <= n; i++ {
		prev = hmacB2s(t0, append(append([]byte{}, prev...), byte(i)))
		out = append(out, prev)
	}
	return out
}

func wgNonce(c uint64) []byte {
	n := make([]byte, 12)
	binary.LittleEndian.PutUint64(n[4:], c)
	return n
}

func seal(key, plain, aad []byte) []byte {
	a, _ := chacha20poly1305.New(key)
	return a.Seal(nil, wgNonce(0), plain, aad)
}

func x25519(priv, pub []byte) []byte {
	out, _ := curve25519.X25519(priv, pub)
	return out
}

func rng(v string, def uint32) [2]uint32 {
	v = strings.TrimSpace(v)
	if v == "" {
		return [2]uint32{def, def}
	}
	a, b, isRange := strings.Cut(v, "-")
	lo, e1 := strconv.ParseUint(strings.TrimSpace(a), 10, 32)
	if !isRange {
		if e1 != nil {
			return [2]uint32{def, def}
		}
		return [2]uint32{uint32(lo), uint32(lo)}
	}
	hi, e2 := strconv.ParseUint(strings.TrimSpace(b), 10, 32)
	if e1 != nil || e2 != nil || lo > hi {
		return [2]uint32{def, def}
	}
	return [2]uint32{uint32(lo), uint32(hi)}
}

func in(r [2]uint32, v uint32) bool { return v >= r[0] && v <= r[1] }

func pick(r [2]uint32) uint32 {
	if r[0] == r[1] {
		return r[0]
	}
	var b [4]byte
	_, _ = rand.Read(b[:])
	return r[0] + binary.LittleEndian.Uint32(b[:])%(r[1]-r[0]+1)
}

// NewWgHello reads a WireGuard / AmneziaWG config (the text the panel gives).
func NewWgHello(cfg string) (*WgHello, error) {
	kv := map[string]string{}
	for _, line := range strings.Split(cfg, "\n") {
		if k, v, ok := strings.Cut(line, "="); ok {
			k = strings.ToLower(strings.TrimSpace(k))
			if _, seen := kv[k]; !seen {
				kv[k] = strings.TrimSpace(v)
			}
		}
	}
	w := &WgHello{}
	key := func(dst *[32]byte, s string, need bool) error {
		if s == "" && !need {
			return nil
		}
		b, err := base64.StdEncoding.DecodeString(s)
		if err != nil || len(b) != 32 {
			return errors.New("bad key")
		}
		copy(dst[:], b)
		return nil
	}
	if err := key(&w.priv, kv["privatekey"], true); err != nil {
		return nil, err
	}
	if err := key(&w.serverPub, kv["publickey"], true); err != nil {
		return nil, err
	}
	if err := key(&w.psk, kv["presharedkey"], false); err != nil {
		return nil, err
	}
	pub, _ := curve25519.X25519(w.priv[:], curve25519.Basepoint)
	copy(w.pub[:], pub)
	host, port, err := net.SplitHostPort(kv["endpoint"])
	if err != nil {
		return nil, errors.New("no endpoint")
	}
	w.Host = host
	if w.Port, err = strconv.Atoi(port); err != nil || w.Port < 1 || w.Port > 65535 {
		return nil, errors.New("bad port")
	}
	num := func(k string, hi int) int {
		n, _ := strconv.Atoi(kv[k])
		return min(max(n, 0), hi)
	}
	w.jc, w.jmin, w.jmax, w.s1, w.s2 = num("jc", 16), num("jmin", 1280), num("jmax", 1280), num("s1", 1024), num("s2", 1024)
	w.h1, w.h2, w.h3 = rng(kv["h1"], 1), rng(kv["h2"], 2), rng(kv["h3"], 3)
	return w, nil
}

// Ping sends one handshake initiation (with AmneziaWG's junk and padding).
func (w *WgHello) Ping(to netip.AddrPort) (int64, error) {
	var sender uint32
	var ePriv, ck, hh []byte
	return exchange(to, func() [][]byte {
		ck = b2s(construction)
		hh = b2s(ck, identifier)
		hh = b2s(hh, w.serverPub[:])
		ePriv = make([]byte, 32)
		_, _ = rand.Read(ePriv)
		ePub, _ := curve25519.X25519(ePriv, curve25519.Basepoint)
		ck = kdf(ck, ePub, 1)[0]
		hh = b2s(hh, ePub)
		t := kdf(ck, x25519(ePriv, w.serverPub[:]), 2)
		ck = t[0]
		encStatic := seal(t[1], w.pub[:], hh)
		hh = b2s(hh, encStatic)
		t = kdf(ck, x25519(w.priv[:], w.serverPub[:]), 2)
		ck = t[0]
		encTime := seal(t[1], tai64n(time.Now()), hh)
		hh = b2s(hh, encTime)
		var sb [4]byte
		_, _ = rand.Read(sb[:])
		sender = binary.LittleEndian.Uint32(sb[:])
		msg := make([]byte, 148)
		binary.LittleEndian.PutUint32(msg[0:], pick(w.h1))
		binary.LittleEndian.PutUint32(msg[4:], sender)
		copy(msg[8:], ePub)
		copy(msg[40:], encStatic)
		copy(msg[88:], encTime)
		mk, _ := blake2s.New128(b2s(labelMac1, w.serverPub[:]))
		mk.Write(msg[:116])
		copy(msg[116:], mk.Sum(nil))
		pad := make([]byte, w.s1)
		_, _ = rand.Read(pad)
		var out [][]byte
		for i := 0; i < w.jc; i++ {
			n := w.jmin
			if w.jmax > w.jmin {
				var b [2]byte
				_, _ = rand.Read(b[:])
				n += int(binary.LittleEndian.Uint16(b[:])) % (w.jmax - w.jmin + 1)
			}
			j := make([]byte, min(max(n, 1), 1280))
			_, _ = rand.Read(j)
			out = append(out, j)
		}
		return append(out, append(pad, msg...))
	}, func(p []byte) bool {
		if len(p) == w.s2+92 {
			m := p[w.s2:]
			if !in(w.h2, binary.LittleEndian.Uint32(m)) || binary.LittleEndian.Uint32(m[8:]) != sender {
				return false
			}
			eR := m[12:44]
			c := kdf(ck, eR, 1)[0]
			h := b2s(hh, eR)
			c = kdf(c, x25519(ePriv, eR), 1)[0]
			c = kdf(c, x25519(w.priv[:], eR), 1)[0]
			t := kdf(c, w.psk[:], 3)
			h = b2s(h, t[1])
			a, _ := chacha20poly1305.New(t[2])
			_, err := a.Open(nil, wgNonce(0), m[44:60], h)
			return err == nil
		}
		if len(p) >= 64 {
			m := p[len(p)-64:]
			return in(w.h3, binary.LittleEndian.Uint32(m)) && binary.LittleEndian.Uint32(m[4:]) == sender
		}
		return false
	})
}

func tai64n(t time.Time) []byte {
	b := make([]byte, 12)
	binary.BigEndian.PutUint64(b, 0x400000000000000a+uint64(t.Unix()))
	binary.BigEndian.PutUint32(b[8:], uint32(t.Nanosecond()/1e6*1e6))
	return b
}
