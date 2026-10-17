package mobile

// توابع کار با بسته‌های IPv4/UDP خام از روی TUN.
// فقط IPv4 پشتیبانی می‌شود (UDP اینجا؛ TCP در tcp.go).

import (
	"encoding/binary"
	"errors"
	"net"
)

const (
	protoTCP   = 6
	protoUDP   = 17
	ipv4MinHdr = 20
	udpHdrLen  = 8
)

// UDPPacket اجزای یک بسته IPv4/UDP استخراج‌شده
type UDPPacket struct {
	SrcIP   net.IP
	DstIP   net.IP
	SrcPort uint16
	DstPort uint16
	Payload []byte
}

// ipProtocol شماره‌ی پروتکل لایه‌ی بالا را برای بسته‌ی IPv4 برمی‌گرداند (غیر IPv4 یا ناقص = 0).
func ipProtocol(pkt []byte) int {
	if len(pkt) < ipv4MinHdr || pkt[0]>>4 != 4 {
		return 0
	}
	return int(pkt[9])
}

// ParseUDPPacket یک بسته خام IP را تجزیه می‌کند؛ اگر UDP نباشد خطا می‌دهد.
// آدرس‌ها کپی می‌شوند، چون بافر خواندن TUN برای بسته‌ی بعدی دوباره استفاده می‌شود
// (قبلاً IPها به همان بافر اشاره می‌کردند و آدرس پاسخ بعداً خراب می‌شد).
func ParseUDPPacket(pkt []byte) (*UDPPacket, error) {
	if len(pkt) < ipv4MinHdr+udpHdrLen || pkt[0]>>4 != 4 {
		return nil, errors.New("نه IPv4")
	}
	ihl := int(pkt[0]&0x0f) * 4
	if ihl < ipv4MinHdr || len(pkt) < ihl+udpHdrLen {
		return nil, errors.New("کوتاه")
	}
	if pkt[9] != protoUDP {
		return nil, errors.New("نه UDP")
	}
	// بسته‌ی فرگمنت‌شده را نمی‌توان درست تجزیه کرد
	if binary.BigEndian.Uint16(pkt[6:8])&0x3fff != 0 {
		return nil, errors.New("فرگمنت")
	}
	u := pkt[ihl:]
	ulen := int(binary.BigEndian.Uint16(u[4:6]))
	if ulen < udpHdrLen || ulen > len(u) {
		return nil, errors.New("طول UDP نامعتبر")
	}
	src := make(net.IP, 4)
	dst := make(net.IP, 4)
	copy(src, pkt[12:16])
	copy(dst, pkt[16:20])
	return &UDPPacket{
		SrcIP:   src,
		DstIP:   dst,
		SrcPort: binary.BigEndian.Uint16(u[0:2]),
		DstPort: binary.BigEndian.Uint16(u[2:4]),
		Payload: u[udpHdrLen:ulen],
	}, nil
}

// BuildUDPPacket پاسخ UDP را به شکل بسته IPv4 کامل می‌سازد (با checksum).
func BuildUDPPacket(srcIP, dstIP net.IP, srcPort, dstPort uint16, payload []byte) []byte {
	total := ipv4MinHdr + udpHdrLen + len(payload)
	pkt := make([]byte, total)

	// IPv4 header
	pkt[0] = 0x45
	pkt[1] = 0x00
	binary.BigEndian.PutUint16(pkt[2:4], uint16(total))
	binary.BigEndian.PutUint16(pkt[4:6], 0x1234) // ID
	binary.BigEndian.PutUint16(pkt[6:8], 0x4000) // Don't fragment
	pkt[8] = 64                                  // TTL
	pkt[9] = protoUDP
	copy(pkt[12:16], srcIP.To4())
	copy(pkt[16:20], dstIP.To4())
	binary.BigEndian.PutUint16(pkt[10:12], checksum(pkt[:ipv4MinHdr]))

	// UDP header
	u := pkt[ipv4MinHdr:]
	binary.BigEndian.PutUint16(u[0:2], srcPort)
	binary.BigEndian.PutUint16(u[2:4], dstPort)
	binary.BigEndian.PutUint16(u[4:6], uint16(udpHdrLen+len(payload)))
	copy(u[udpHdrLen:], payload)
	// UDP checksum (اختیاری در IPv4 ولی محاسبه می‌کنیم)
	binary.BigEndian.PutUint16(u[6:8], 0)
	cs := udpChecksum(srcIP.To4(), dstIP.To4(), u)
	binary.BigEndian.PutUint16(u[6:8], cs)
	return pkt
}

func checksum(b []byte) uint16 {
	var sum uint32
	for i := 0; i+1 < len(b); i += 2 {
		sum += uint32(binary.BigEndian.Uint16(b[i : i+2]))
	}
	if len(b)%2 == 1 {
		sum += uint32(b[len(b)-1]) << 8
	}
	for sum>>16 != 0 {
		sum = (sum & 0xffff) + (sum >> 16)
	}
	return ^uint16(sum)
}

func udpChecksum(src, dst net.IP, udp []byte) uint16 {
	pseudo := make([]byte, 0, 12+len(udp))
	pseudo = append(pseudo, src...)
	pseudo = append(pseudo, dst...)
	pseudo = append(pseudo, 0, protoUDP, byte(len(udp)>>8), byte(len(udp)))
	pseudo = append(pseudo, udp...)
	cs := checksum(pseudo)
	if cs == 0 {
		cs = 0xffff
	}
	return cs
}
