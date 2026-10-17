//go:build tcp

package mobile

// پشتیبانی TCP: بسته‌های TCP که از TUN می‌آیند به یک پشته‌ی TCP/IP نرم‌افزاری (gVisor netstack)
// داده می‌شوند؛ هر اتصال TCP که اپ‌ها باز کنند اینجا تمام می‌شود و روی یک استریم smux
// با فرمان CmdTCP به سرور می‌رود. سرور از قبل CmdTCP را پشتیبانی می‌کند، پس سمت سرور
// و پروتکل هیچ تغییری نکرده‌اند.

import (
	"fmt"
	"io"
	"net"
	"os"
	"sync/atomic"
	"time"

	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/waiter"

	"kanki-tunnel/internal/proto"
)

const (
	nsNIC            = 1
	tcpOpenTimeout   = 15 * time.Second // سرور تا ۱۰ ثانیه برای dial صبر می‌کند
	tcpHalfCloseWait = 30 * time.Second
)

var (
	nsStack atomic.Pointer[stack.Stack]
	nsEP    atomic.Pointer[channel.Endpoint]
)

// HasTCP نشان می‌دهد این بیلد TCP هم دارد.
func HasTCP() bool { return true }

// tunWriter بسته‌های خروجی پشته (SYN-ACK، داده، ...) را در TUN می‌نویسد.
type tunWriter struct {
	ep *channel.Endpoint
	tf *os.File
}

func (w *tunWriter) WriteNotify() {
	for {
		pkt := w.ep.Read()
		if pkt == nil {
			return
		}
		view := pkt.ToView()
		pkt.DecRef()
		b := make([]byte, view.Size())
		n, _ := view.Read(b)
		if m, err := w.tf.Write(b[:n]); err == nil {
			rxBytes.Add(int64(m))
		}
	}
}

func startTCP(tf *os.File, mtu int) error {
	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol},
	})
	ep := channel.New(1024, uint32(mtu), "")
	if err := s.CreateNIC(nsNIC, ep); err != nil {
		return fmt.Errorf("netstack NIC: %v", err)
	}
	// هر مقصدی را بپذیر (ما جای «همه‌ی سرورهای اینترنت» جواب می‌دهیم)
	if err := s.SetPromiscuousMode(nsNIC, true); err != nil {
		return fmt.Errorf("netstack promiscuous: %v", err)
	}
	if err := s.SetSpoofing(nsNIC, true); err != nil {
		return fmt.Errorf("netstack spoofing: %v", err)
	}
	s.SetRouteTable([]tcpip.Route{{Destination: header.IPv4EmptySubnet, NIC: nsNIC}})

	sack := tcpip.TCPSACKEnabled(true)
	s.SetTransportProtocolOption(tcp.ProtocolNumber, &sack)

	fwd := tcp.NewForwarder(s, 0, 2048, handleTCPRequest)
	s.SetTransportProtocolHandler(tcp.ProtocolNumber, fwd.HandlePacket)

	ep.AddNotify(&tunWriter{ep: ep, tf: tf})
	nsStack.Store(s)
	nsEP.Store(ep)
	return nil
}

func stopTCP() {
	s := nsStack.Swap(nil)
	ep := nsEP.Swap(nil)
	if s != nil {
		s.RemoveNIC(nsNIC)
	}
	if ep != nil {
		ep.Close()
	}
}

// handleTCPPacket یک بسته‌ی خام IPv4/TCP را به پشته تزریق می‌کند.
func handleTCPPacket(raw []byte) {
	ep := nsEP.Load()
	if ep == nil {
		return
	}
	pkb := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(raw)})
	ep.InjectInbound(header.IPv4ProtocolNumber, pkb)
	pkb.DecRef()
}

// handleTCPRequest برای هر اتصال TCP جدید اپ‌ها صدا زده می‌شود.
func handleTCPRequest(r *tcp.ForwarderRequest) {
	id := r.ID()
	var wq waiter.Queue
	ep, err := r.CreateEndpoint(&wq)
	if err != nil {
		r.Complete(true) // RST
		return
	}
	r.Complete(false)
	conn := gonet.NewTCPConn(&wq, ep)
	go relayTCP(conn, id.LocalAddress.String(), id.LocalPort)
}

func relayTCP(conn net.Conn, host string, port uint16) {
	defer conn.Close()
	m, err := getMux()
	if err != nil {
		return
	}
	st, err := openStream(m, proto.CmdTCP, host, port, tcpOpenTimeout)
	if err != nil {
		return
	}
	defer st.Close()

	up := make(chan struct{})   // اپ → تونل تمام شد
	down := make(chan struct{}) // تونل → اپ تمام شد
	go func() { io.Copy(st, conn); close(up) }()
	go func() { io.Copy(conn, st); close(down) }()

	select {
	case <-down: // مقصد بسته شد → همین الان تمام
	case <-up: // اپ دیگر چیزی نمی‌فرستد؛ به مقصد وقت بده پاسخ را کامل کند
		select {
		case <-down:
		case <-time.After(tcpHalfCloseWait):
		}
	}
}
