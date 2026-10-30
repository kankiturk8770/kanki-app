package cores

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/netip"
	"strings"
	"testing"

	"github.com/amnezia-vpn/amneziawg-go/conn"
	"github.com/amnezia-vpn/amneziawg-go/device"
	"github.com/amnezia-vpn/amneziawg-go/tun/tuntest"
	"golang.org/x/crypto/curve25519"
)

func TestParseKmix(t *testing.T) {
	k, err := ParseKmix("kanki://" + strings.Repeat("ab", 32) + "@de1.kanki.pro:4000?fec=8-2&ports=4001-4010&gaming=1#GERMANY")
	if err != nil {
		t.Fatal(err)
	}
	if k.Host != "de1.kanki.pro" || k.Port != 4000 || k.FecData != 8 || k.FecPrty != 2 || !k.Gaming || k.PortLo != 4001 {
		t.Fatalf("%+v", k)
	}
	for i := 0; i < 50; i++ {
		if p := k.PickPort(); p != 4000 && (p < 4001 || p > 4010) {
			t.Fatal(p)
		}
	}
	if _, err := ParseKmix("kanki://short@h:1"); err == nil {
		t.Fatal("bad key accepted")
	}
}

func TestParseHy2(t *testing.T) {
	h, err := ParseHy2("hysteria2://pa%3Ass@1.2.3.4:8443/?sni=x.example.com&obfs=salamander&obfs-password=zz&insecure=1#n")
	if err != nil {
		t.Fatal(err)
	}
	if h.Auth != "pa:ss" || h.Port != 8443 || h.SNI != "x.example.com" || h.ObfsPassword != "zz" || !h.Insecure {
		t.Fatalf("%+v", h)
	}
	var c map[string]any
	if err := json.Unmarshal([]byte(h.Config("1.2.3.4:8443", 1080)), &c); err != nil || c["server"] != "1.2.3.4:8443" {
		t.Fatal(c, err)
	}
}

func TestVlessRealityConfig(t *testing.T) {
	v, err := ParseVless("vless://11111111-2222-3333-4444-555555555555@de1.kanki.pro:443?type=tcp&security=reality&sni=www.google.com&pbk=PUB&sid=ab&fp=chrome&flow=xtls-rprx-vision#DE")
	if err != nil {
		t.Fatal(err)
	}
	s := v.XrayConfig("203.0.113.9", 10808)
	for _, want := range []string{`"address":"203.0.113.9"`, `"publicKey":"PUB"`, `"serverName":"www.google.com"`, `"flow":"xtls-rprx-vision"`, `"port":10808`, `"udp":true`} {
		if !strings.Contains(s, want) {
			t.Errorf("missing %s in %s", want, s)
		}
	}
	v, _ = ParseVless("vless://id@cdn.example.com:443?type=ws&security=tls&path=%2Fws&host=h.example.com")
	s = v.XrayConfig("1.1.1.1", 1)
	if !strings.Contains(s, `"wsSettings":{"host":"h.example.com","path":"/ws"}`) || !strings.Contains(s, `"serverName":"h.example.com"`) {
		t.Fatal(s)
	}
	if _, err := ParseVless("vmess://x"); err == nil {
		t.Fatal("vmess accepted as vless")
	}
}

func key() ([32]byte, [32]byte) {
	var priv [32]byte
	copy(priv[:], []byte("0123456789abcdef0123456789abcdef"))
	priv[0] &= 248
	pub, _ := curve25519.X25519(priv[:], curve25519.Basepoint)
	var p [32]byte
	copy(p[:], pub)
	return priv, p
}

// A real amneziawg-go server answers the ping handshake (plain and with AmneziaWG obfuscation).
func TestWgPingAgainstRealServer(t *testing.T) {
	for _, awg := range []string{"", "jc=3\njmin=40\njmax=70\ns1=15\ns2=18\nh1=1234567\nh2=2234567\nh3=3234567\nh4=4234567\n"} {
		srvPriv, srvPub := key()
		var cliPriv [32]byte
		copy(cliPriv[:], []byte("fedcba9876543210fedcba9876543210"))
		cliPub, _ := curve25519.X25519(cliPriv[:], curve25519.Basepoint)
		psk := [32]byte{7}
		dev := device.NewDevice(tuntest.NewChannelTUN().TUN(), conn.NewDefaultBind(), device.NewLogger(device.LogLevelSilent, ""))
		hx := func(b []byte) string { return fmt.Sprintf("%x", b) }
		if err := dev.IpcSet("private_key=" + hx(srvPriv[:]) + "\nlisten_port=" + fmt.Sprint(51900+len(awg)) + "\n" + awg +
			"public_key=" + hx(cliPub) + "\npreshared_key=" + hx(psk[:]) + "\nallowed_ip=10.9.0.2/32\n"); err != nil {
			t.Fatal(err)
		}
		if err := dev.Up(); err != nil {
			t.Fatal(err)
		}
		port := fmt.Sprint(51900 + len(awg))
		b64 := base64.StdEncoding.EncodeToString
		cfg := "[Interface]\nPrivateKey = " + b64(cliPriv[:]) + "\n"
		for _, l := range strings.Split(strings.TrimSpace(awg), "\n") {
			if l != "" {
				k, v, _ := strings.Cut(l, "=")
				cfg += strings.ToUpper(k[:1]) + k[1:] + " = " + v + "\n"
			}
		}
		cfg += "[Peer]\nPublicKey = " + b64(srvPub[:]) + "\nPresharedKey = " + b64(psk[:]) + "\nEndpoint = 127.0.0.1:" + port + "\n"
		w, err := NewWgHello(cfg)
		if err != nil {
			t.Fatal(err)
		}
		ms, err := w.Ping(netip.MustParseAddrPort("127.0.0.1:" + port))
		dev.Close()
		if err != nil {
			t.Fatalf("awg=%v: %v", awg != "", err)
		}
		if ms < 1 {
			t.Fatal(ms)
		}
	}
}
