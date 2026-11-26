package mobile

// Two networks only when they help (app 1.45): the second network of the phone (Wi-Fi + mobile data) stays
// silent, not even pinged, while the main one is fine. It is used, for the game's copies and repairs and with
// pings of its own, only while the main path
//
//   - loses more than dualLoss ‰ of the game (as the server reports for the way up, or as measured on the way
//     down),
//   - has a jitter spike, or
//   - does not answer,
//
// and it goes silent again at the first tick the main path is fine. The address is the same: only the network
// differs.

import (
	"sync/atomic"
	"time"
)

const (
	// dualLoss ‰ of loss above which the second network helps (0.4 %)
	dualLoss = 4
	// jitterFloor ms: a jitter below this is never a spike
	jitterFloor = 15
	// spikeFloor ms: one answer this much later than usual (and more than 3× the jitter) is a spike
	spikeFloor = 30
)

var dualOn atomic.Bool

// dualTick from pingLoop
func dualTick(now time.Time) { dualOn.Store(dualWanted(now)) }

func dualWanted(now time.Time) bool {
	cur := dg.Load()
	if cur == nil || !hasSecond(cur) {
		return false
	}
	t := now.UnixNano()
	if gaming.Load() && t-lastGame.Load() < int64(playingFor) && (lossUp.Load() > dualLoss || lossDown.Load() > dualLoss) {
		return true
	}
	if _, sus, dead := cur.quality(t); sus || dead {
		return true
	}
	return cur.jittery()
}

// hasSecond the phone has another network than the main path's
func hasSecond(cur *dgramConn) bool {
	pathsMu.Lock()
	defer pathsMu.Unlock()
	for _, s := range socks {
		if s != cur.ns {
			return true
		}
	}
	return false
}

// jittery the path's jitter is high for its ping, or the last answer came far later than usual
func (p *dgramConn) jittery() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.samples < 4 {
		return false
	}
	if p.rttvar >= max(jitterFloor, p.srtt/3) {
		return true
	}
	return p.lastRTT-p.srtt > max(spikeFloor, 3*p.rttvar)
}
