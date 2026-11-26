package mobile

// Make-before-break port hopping (app 1.45): when the server's port stops answering for stallAfter (or the
// line on it loses badly for a few seconds), the next of the server's ports is tried with an encrypted ping
// while everything still goes to the old one. Only when that port answers does it become the path's port;
// if the old one answers first, the try is dropped. A try that gets no answer moves on to the next port every
// probeEvery. The session, the flows and the key stay the same.

import (
	"net"
	"time"

	"kanki-tunnel/internal/proto"
)

const (
	stallAfter = 400 * time.Millisecond
	probeEvery = 400 * time.Millisecond
)

// hopTick from pingLoop; b is a ping of this moment
func hopTick(now time.Time, b []byte) {
	hopMu.Lock()
	ports := hopPorts
	lossy := badLoss >= 3 && now.Sub(hopAt) >= hopEvery
	hopMu.Unlock()
	p := dg.Load()
	if p == nil || len(ports) == 0 {
		return
	}
	t := now.UnixNano()
	p.mu.Lock()
	last := p.lastPong
	if last == 0 {
		last = p.born
	}
	stalled := p.waitFrom != 0 && t-p.waitFrom >= int64(stallAfter) && t-last >= int64(stallAfter)
	if !stalled && !lossy {
		p.probe.Store(nil)
		p.mu.Unlock()
		return
	}
	pr := p.probe.Load()
	if pr != nil && t-p.probeAt < int64(probeEvery) {
		p.mu.Unlock()
		return
	}
	cur := p.dst()
	from := cur.Port
	if pr != nil {
		from = pr.Port
	}
	next := nextPort(p.home, from, ports)
	if next == cur.Port {
		next = nextPort(p.home, next, ports)
	}
	if next == 0 || next == cur.Port {
		p.mu.Unlock()
		return
	}
	a := *cur
	a.Port = next
	p.probe.Store(&a)
	p.probeAt = t
	p.probeStall = stalled
	p.mu.Unlock()
	noteSendErr(p.pc.WritePacket(proto.KindPing, b, &a))
}

// probed an answer from ua; true when it was the port being tried (it is the path's port now)
func (p *dgramConn) probed(ua *net.UDPAddr, now int64) bool {
	pr := p.probe.Load()
	if ua == nil || pr == nil || pr.Port != ua.Port || !pr.IP.Equal(ua.IP) {
		return false
	}
	p.mu.Lock()
	if !p.probe.CompareAndSwap(pr, nil) {
		p.mu.Unlock()
		return true
	}
	p.srv.Store(pr)
	p.probeStall = false
	p.waitFrom = 0
	p.lastPong = now
	p.sent = [pingRing]int64{} // the pings of the old port are not losses of this one
	p.got = [pingRing]bool{}
	p.mu.Unlock()
	lastPong.Store(now)
	// the port is the server's: the other networks' paths go to it too
	for _, q := range allPaths() {
		if q == p {
			continue
		}
		if d := q.dst(); d != nil {
			a := *d
			a.Port = pr.Port
			q.srv.Store(&a)
		}
	}
	if dg.Load() == p {
		setServerPort(pr.Port)
	}
	hopMu.Lock()
	hopAt, badLoss = time.Unix(0, now), 0
	hopMu.Unlock()
	return true
}
