// KPING v3: what each packet of the game is, from the IP and UDP/TCP headers only (the game's payload is
// encrypted and never looked into, so nothing an anti-cheat could mind).
//
// Every packet belongs to a flow (protocol, local port, server address, server port). Each flow keeps its
// rate, its sizes and the regularity of its timing. From those:
//   - the game's main flow: the UDP flow to a game server with the most packets per second (10+), small
//     packets — movement, aim, shots and their acks. Its rate against its own baseline tells a fight
//     (more packets: shooting, running, driving) from quiet moments.
//   - voice: another UDP flow that ticks like a clock (20-60 ms, small spread) with steady sizes
//   - DNS, and TCP control packets (SYN, FIN, RST): tiny, and a lost one costs a whole retransmission
//     timeout (a second at login)
//   - bulk: TCP data and its bare ACKs (the next ACK says the same, so a lost one costs nothing), big UDP
//     datagrams, fast UDP flows (downloads, updates, QUIC): TCP resends on its own and copies would only
//     crowd the line
package kping

import (
	"encoding/binary"
	"math"
	"time"
)

type class byte

const (
	clsGame  class = iota // the main game flow: copies on every path, repairs, the shortest deadline
	clsState              // game flows' bigger packets and other game flows: repairs
	clsVoice              // voice chat: repairs, fewer
	clsCtl                // DNS, ICMP and TCP SYN/FIN/RST: copies on a lossy line (cheap, a loss costs seconds)
	clsBulk               // TCP data, big or fast UDP: as it is
	nClass
)

var classNames = [nClass]string{"game", "state", "voice", "ctl", "bulk"}

// how long a packet of each class is worth sending, copying or rebuilding
var classTTL = [nClass]time.Duration{150 * time.Millisecond, 200 * time.Millisecond, 160 * time.Millisecond, 1500 * time.Millisecond, 0}

type flowKey struct {
	proto byte
	lport uint16
	dst   [4]byte
	dport uint16
}

type flow struct {
	first, last time.Time
	pkts        uint64
	// per-second rate: this second's count, and the averages
	winStart   time.Time
	winPkts    int
	pps        float64 // last full second
	base       float64 // slow average of pps (about 10 s)
	burst      float64 // packets in the last 250 ms × 4
	burstStart time.Time
	burstPkts  int
	// timing and sizes (exponential averages)
	iat, iatVar   float64 // ms
	size, sizeVar float64
	bytesPS       float64
}

type classifier struct {
	flows   map[flowKey]*flow
	main    flowKey
	hasMain bool
	mainAt  time.Time // last packet of the main flow
	intense time.Time // a fight was seen: until this time
	cleanAt time.Time
	counts  [nClass]uint64
}

func newClassifier() *classifier { return &classifier{flows: map[flowKey]*flow{}} }

func ipKey(p []byte) (flowKey, int, byte, bool) {
	if len(p) < 20 || p[0]>>4 != 4 {
		return flowKey{}, 0, 0, false
	}
	ihl := int(p[0]&0x0f) * 4
	proto := p[9]
	var k flowKey
	k.proto = proto
	copy(k.dst[:], p[16:20])
	flags := byte(0)
	if (proto == 6 || proto == 17) && len(p) >= ihl+4 {
		k.lport = binary.BigEndian.Uint16(p[ihl:])
		k.dport = binary.BigEndian.Uint16(p[ihl+2:])
	}
	payload := 0
	switch proto {
	case 17:
		payload = len(p) - ihl - 8
	case 6:
		if len(p) >= ihl+14 {
			off := int(p[ihl+12]>>4) * 4
			payload = len(p) - ihl - off
			flags = p[ihl+13]
		}
	}
	return k, payload, flags, true
}

// classify: one packet going up
func (c *classifier) classify(p []byte, now time.Time) class {
	k, payload, tcpFlags, ok := ipKey(p)
	if !ok {
		return clsBulk
	}
	f := c.flows[k]
	if f == nil {
		if len(c.flows) > 512 {
			c.clean(now, true)
		}
		f = &flow{first: now, winStart: now, burstStart: now, size: float64(len(p))}
		c.flows[k] = f
	}
	c.note(f, len(p), now)
	if now.Sub(c.cleanAt) > 5*time.Second {
		c.clean(now, false)
	}
	cl := c.decide(k, f, len(p), payload, tcpFlags, now)
	c.counts[cl]++
	return cl
}

func (c *classifier) note(f *flow, size int, now time.Time) {
	if f.pkts > 0 {
		d := float64(now.Sub(f.last)) / 1e6
		if d < 1000 {
			e := d - f.iat
			f.iat += e / 8
			f.iatVar += (math.Abs(e) - f.iatVar) / 8
		}
		s := float64(size) - f.size
		f.size += s / 8
		f.sizeVar += (math.Abs(s) - f.sizeVar) / 8
	}
	f.pkts++
	f.last = now
	f.winPkts++
	if el := now.Sub(f.winStart); el >= time.Second {
		f.pps = float64(f.winPkts) / el.Seconds()
		if f.base == 0 {
			f.base = f.pps
		} else {
			f.base += (f.pps - f.base) / 10
		}
		f.bytesPS = f.pps * f.size
		f.winStart, f.winPkts = now, 0
	}
	f.burstPkts++
	if el := now.Sub(f.burstStart); el >= 250*time.Millisecond {
		f.burst = float64(f.burstPkts) / el.Seconds()
		f.burstStart, f.burstPkts = now, 0
	}
}

func (c *classifier) decide(k flowKey, f *flow, size, payload int, tcpFlags byte, now time.Time) class {
	switch k.proto {
	case 6:
		// SYN, FIN, RST: control. A bare ACK is not: the next one acknowledges the same and more
		if tcpFlags&0x07 != 0 {
			return clsCtl
		}
		return clsBulk
	case 17:
	case 1:
		return clsCtl // ICMP (ping): tiny
	default:
		return clsBulk
	}
	if k.dport == 53 || k.dport == 853 {
		return clsCtl
	}
	if size > 1000 || f.bytesPS > 60_000 {
		return clsBulk // big datagrams or a fast flow: a download, an update, video
	}
	// the main game flow: the busiest small UDP flow, at least 10 packets a second
	if !c.hasMain || k != c.main {
		if rate := max(f.pps, f.burst); rate >= 10 && f.size <= 500 {
			m := c.flows[c.main]
			if !c.hasMain || m == nil || now.Sub(m.last) > 2*time.Second || rate > max(m.pps, m.burst)*1.5 {
				c.main, c.hasMain = k, true
			}
		}
	}
	if c.hasMain && k == c.main {
		c.mainAt = now
		// a fight: the rate jumps well over its own baseline
		if f.base > 0 && f.burst > f.base*1.3 && f.burst > f.base+8 {
			c.intense = now.Add(2 * time.Second)
		}
		if size > 700 {
			return clsState
		}
		return clsGame
	}
	// voice: a steady clock (20-60 ms) and steady sizes
	if f.pkts > 25 && f.iat >= 15 && f.iat <= 70 && f.iatVar < f.iat*0.35 && f.sizeVar < f.size*0.3 && f.size <= 400 {
		return clsVoice
	}
	return clsState
}

// gaming: the main flow ran in the last second
func (c *classifier) gaming(now time.Time) bool {
	return c.hasMain && now.Sub(c.mainAt) < time.Second
}

func (c *classifier) fight(now time.Time) bool { return now.Before(c.intense) }

func (c *classifier) mainPPS() float64 {
	if f := c.flows[c.main]; c.hasMain && f != nil {
		return f.pps
	}
	return 0
}

func (c *classifier) clean(now time.Time, hard bool) {
	c.cleanAt = now
	idle := 30 * time.Second
	if hard {
		idle = 3 * time.Second
	}
	for k, f := range c.flows {
		if now.Sub(f.last) > idle {
			delete(c.flows, k)
		}
	}
}
