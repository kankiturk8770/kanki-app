package mobile

import (
	"testing"

	"kanki-tunnel/internal/obfs"
	"kanki-tunnel/internal/proto"
)

// کارت شبکه‌ی KANKI MIX (tunMTU) و KCP (kcpMTU) باید داخل یک بسته‌ی obfs جا شوند: بزرگ‌ترین UDP
// بازی (tunMTU - 28) با هدر بالارو، و هر بسته‌ی KCP. وگرنه آن بسته به‌جای فرستادن دور ریخته می‌شود.
// سقف کل بسته روی سیم (۱۳۸۰ حتی روی IPv6) را تست obfs چک می‌کند.
func TestEverythingFitsOnePacket(t *testing.T) {
	if kcpMTU > obfs.MaxPayload {
		t.Fatalf("KCP %d > MaxPayload %d", kcpMTU, obfs.MaxPayload)
	}
	if game := tunMTU - 20 - 8; game+proto.UpHdrLen > obfs.MaxPayload || game+proto.DownHdrLen > obfs.MaxPayload {
		t.Fatalf("a %d-byte game datagram does not fit one packet", game)
	}
}
