package conf

import (
	"net/netip"
	"strings"
	"testing"
)

// The shape the panel writes (src/http/api.rs, config_file).
const awgText = `[Interface]
PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
Address = 10.8.0.7/32
DNS = 1.1.1.1, 1.0.0.1
MTU = 1280
Jc = 4
Jmin = 40
Jmax = 70
S1 = 15
S2 = 68
S3 = 22
S4 = 10
H1 = 1234567-1234600
H2 = 2234567
H3 = 3234567
H4 = 4234567
I1 = <b 0xc2000000011a><r 16>

[Peer]
PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
PresharedKey = FpCyhws9cxwWoV4xELtfJvjJN+zQVRPISllRWgeopVE=
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = de1.kanki.pro:51820
PersistentKeepalive = 25
`

func TestParseAWG(t *testing.T) {
	c, err := Parse(awgText)
	if err != nil {
		t.Fatal(err)
	}
	if !c.IsAWG() || len(c.AWG) != 12 {
		t.Fatalf("awg params: %v", c.AWG)
	}
	if c.Peer.Host != "de1.kanki.pro" || c.Peer.Port != 51820 || c.MTU != 1280 {
		t.Fatalf("peer: %+v", c.Peer)
	}
	if len(c.DNS) != 2 || c.HasIPv6() || len(c.Peer.AllowedIPs) != 2 {
		t.Fatalf("dns/addr: %v %v", c.DNS, c.Addresses)
	}
	u := c.UAPI(netip.MustParseAddrPort("203.0.113.9:51820"))
	for _, want := range []string{
		"private_key=" + c.PrivateKey.Hex() + "\n",
		"jc=4\n", "s3=22\n", "h1=1234567-1234600\n", "i1=<b 0xc2000000011a><r 16>\n",
		"endpoint=203.0.113.9:51820\n", "persistent_keepalive_interval=25\n",
		"allowed_ip=0.0.0.0/0\n", "allowed_ip=::/0\n", "preshared_key=",
	} {
		if !strings.Contains(u, want) {
			t.Errorf("UAPI lacks %q:\n%s", want, u)
		}
	}
	// AWG keys go before the peer, as UAPI wants device keys first
	if strings.Index(u, "jc=") > strings.Index(u, "public_key=") {
		t.Error("device keys after peer keys")
	}
}

func TestPlainWireGuard(t *testing.T) {
	text := strings.Join(strings.Split(awgText, "\n")[:5], "\n") + "\n" + awgText[strings.Index(awgText, "[Peer]"):]
	c, err := Parse(text)
	if err != nil {
		t.Fatal(err)
	}
	if c.IsAWG() {
		t.Fatal("plain WireGuard read as AWG")
	}
}

func TestRefusesScripts(t *testing.T) {
	_, err := Parse(strings.Replace(awgText, "MTU = 1280", "PostUp = calc.exe", 1))
	if err == nil || !strings.Contains(err.Error(), "not allowed") {
		t.Fatalf("PostUp accepted: %v", err)
	}
}

func TestRefusesBroken(t *testing.T) {
	cases := map[string]string{
		"bad key":     strings.Replace(awgText, "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=", "abc", 1),
		"no endpoint": strings.Replace(awgText, "Endpoint = de1.kanki.pro:51820", "", 1),
		"two peers":   awgText + "[Peer]\nPublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=\n",
		"unknown key": strings.Replace(awgText, "MTU = 1280", "Foo = 1", 1),
		"html":        "<html>not found</html>",
		"bad port":    strings.Replace(awgText, ":51820", ":0", 1),
		"tiny mtu":    strings.Replace(awgText, "MTU = 1280", "MTU = 100", 1),
	}
	for name, text := range cases {
		if _, err := Parse(text); err == nil {
			t.Errorf("%s: accepted", name)
		}
	}
}
