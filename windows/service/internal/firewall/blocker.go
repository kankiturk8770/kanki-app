//go:build windows

/* SPDX-License-Identifier: MIT
 *
 * Copyright (C) 2019-2026 WireGuard LLC. All Rights Reserved.
 * Changes for Kanki VPN: sessions as values (a new rule set goes up before the
 * old one is removed), lockdown without a tunnel, IPv6-only block, and
 * permitted outside ranges for LAN and split tunneling.
 */

// The firewall package is the Windows Filtering Platform part: the kill switch,
// the DNS leak block and the IPv6 leak block. It is WireGuard for Windows'
// tunnel/firewall package (rules.go, helpers.go, types and syscalls are
// unchanged copies, MIT, see LICENSE.WireGuard) with this file rewritten.
//
// Every rule lives in a dynamic WFP session: if the service process dies, the
// rules go with it and the computer is never left without internet.
package firewall

import (
	"encoding/binary"
	"net/netip"
	"runtime"
	"unsafe"

	"golang.org/x/sys/windows"
)

type wfpObjectInstaller func(uintptr) error

// Fundamental WFP objects of one session.
type baseObjects struct {
	provider windows.GUID
	filters  windows.GUID
}

// Options of one rule set.
type Options struct {
	// LUID of the tunnel adapter; 0 = no tunnel (lockdown while reconnecting).
	LUID uint64
	// BlockAll: the kill switch. Nothing leaves except through the tunnel, this
	// service's own packets (the encrypted tunnel itself), loopback, DHCP, NDP
	// and Bypass.
	BlockAll bool
	// BlockIPv6: block IPv6 outside the tunnel (without the full kill switch).
	BlockIPv6 bool
	// DNS: when set, DNS (port 53) may only go to these servers.
	DNS []netip.Addr
	// Bypass: destinations allowed outside the tunnel while blocking.
	Bypass []netip.Prefix
}

// Session is one installed rule set.
type Session struct{ handle uintptr }

func createWfpSession() (uintptr, error) {
	sessionDisplayData, err := createWtFwpmDisplayData0("Kanki VPN", "Kanki VPN dynamic session")
	if err != nil {
		return 0, wrapErr(err)
	}

	session := wtFwpmSession0{
		displayData:          *sessionDisplayData,
		flags:                cFWPM_SESSION_FLAG_DYNAMIC,
		txnWaitTimeoutInMSec: windows.INFINITE,
	}

	sessionHandle := uintptr(0)

	err = fwpmEngineOpen0(nil, cRPC_C_AUTHN_WINNT, nil, &session, unsafe.Pointer(&sessionHandle))
	if err != nil {
		return 0, wrapErr(err)
	}

	return sessionHandle, nil
}

func registerBaseObjects(session uintptr) (*baseObjects, error) {
	bo := &baseObjects{}
	var err error
	bo.provider, err = windows.GenerateGUID()
	if err != nil {
		return nil, wrapErr(err)
	}
	bo.filters, err = windows.GenerateGUID()
	if err != nil {
		return nil, wrapErr(err)
	}

	{
		displayData, err := createWtFwpmDisplayData0("Kanki VPN", "Kanki VPN provider")
		if err != nil {
			return nil, wrapErr(err)
		}
		provider := wtFwpmProvider0{
			providerKey: bo.provider,
			displayData: *displayData,
		}
		err = fwpmProviderAdd0(session, &provider, 0)
		if err != nil {
			return nil, wrapErr(err)
		}
	}

	{
		displayData, err := createWtFwpmDisplayData0("Kanki VPN filters", "Permissive and blocking filters")
		if err != nil {
			return nil, wrapErr(err)
		}
		sublayer := wtFwpmSublayer0{
			subLayerKey: bo.filters,
			displayData: *displayData,
			providerKey: &bo.provider,
			weight:      ^uint16(0),
		}
		err = fwpmSubLayerAdd0(session, &sublayer, 0)
		if err != nil {
			return nil, wrapErr(err)
		}
	}

	return bo, nil
}

// Enable installs a rule set. To change rules, Enable the new set first and
// then Close the old one: while both exist, the stricter one wins, so no
// packet slips out in between.
func Enable(o Options) (*Session, error) {
	session, err := createWfpSession()
	if err != nil {
		return nil, wrapErr(err)
	}

	blocking := o.BlockAll || o.BlockIPv6
	objectInstaller := func(session uintptr) error {
		bo, err := registerBaseObjects(session)
		if err != nil {
			return wrapErr(err)
		}

		// the tunnel's own UDP packets come from this process
		if err = permitWireGuardService(session, bo, 15); err != nil {
			return wrapErr(err)
		}

		if len(o.DNS) > 0 {
			if err = blockDNS(o.DNS, session, bo, 15, 14); err != nil {
				return wrapErr(err)
			}
		}

		if !blocking {
			return nil
		}

		if err = permitLoopback(session, bo, 13); err != nil {
			return wrapErr(err)
		}
		if o.LUID != 0 {
			if err = permitTunInterface(session, bo, 12, o.LUID); err != nil {
				return wrapErr(err)
			}
		}
		if err = permitDHCPIPv4(session, bo, 12); err != nil {
			return wrapErr(err)
		}
		if err = permitDHCPIPv6(session, bo, 12); err != nil {
			return wrapErr(err)
		}
		if err = permitNdp(session, bo, 12); err != nil {
			return wrapErr(err)
		}
		if err = permitRemote(o.Bypass, session, bo, 11); err != nil {
			return wrapErr(err)
		}

		if o.BlockAll {
			return blockAll(session, bo, 0)
		}
		return blockIPv6(session, bo, 0)
	}

	err = runTransaction(session, objectInstaller)
	if err != nil {
		fwpmEngineClose0(session)
		return nil, wrapErr(err)
	}
	return &Session{handle: session}, nil
}

// Close removes the rule set (nil is fine).
func (s *Session) Close() {
	if s != nil && s.handle != 0 {
		fwpmEngineClose0(s.handle)
		s.handle = 0
	}
}

// permitRemote lets traffic to and from the given ranges pass outside the
// tunnel (LAN, split tunnel exclusions).
func permitRemote(ps []netip.Prefix, session uintptr, baseObjects *baseObjects, weight uint8) error {
	var v4, v6 []wtFwpmFilterCondition0
	var keep4 []*wtFwpV4AddrAndMask
	var keep6 []*wtFwpV6AddrAndMask
	for _, p := range ps {
		p = p.Masked()
		if p.Addr().Is4() {
			mask := uint32(0)
			if p.Bits() > 0 {
				mask = ^uint32(0) << (32 - p.Bits())
			}
			am := &wtFwpV4AddrAndMask{addr: binary.BigEndian.Uint32(p.Addr().AsSlice()), mask: mask}
			keep4 = append(keep4, am)
			// the same field repeated = logical OR
			v4 = append(v4, wtFwpmFilterCondition0{
				fieldKey:  cFWPM_CONDITION_IP_REMOTE_ADDRESS,
				matchType: cFWP_MATCH_EQUAL,
				conditionValue: wtFwpConditionValue0{
					_type: cFWP_V4_ADDR_MASK,
					value: uintptr(unsafe.Pointer(am)),
				},
			})
		} else {
			am := &wtFwpV6AddrAndMask{addr: p.Addr().As16(), prefixLength: uint8(p.Bits())}
			keep6 = append(keep6, am)
			v6 = append(v6, wtFwpmFilterCondition0{
				fieldKey:  cFWPM_CONDITION_IP_REMOTE_ADDRESS,
				matchType: cFWP_MATCH_EQUAL,
				conditionValue: wtFwpConditionValue0{
					_type: cFWP_V6_ADDR_MASK,
					value: uintptr(unsafe.Pointer(am)),
				},
			})
		}
	}

	add := func(conds []wtFwpmFilterCondition0, name string, layers ...windows.GUID) error {
		if len(conds) == 0 {
			return nil
		}
		displayData, err := createWtFwpmDisplayData0(name, "")
		if err != nil {
			return wrapErr(err)
		}
		filter := wtFwpmFilter0{
			displayData:         *displayData,
			providerKey:         &baseObjects.provider,
			subLayerKey:         baseObjects.filters,
			weight:              filterWeight(weight),
			numFilterConditions: uint32(len(conds)),
			filterCondition:     (*wtFwpmFilterCondition0)(unsafe.Pointer(&conds[0])),
			action: wtFwpmAction0{
				_type: cFWP_ACTION_PERMIT,
			},
		}
		filterID := uint64(0)
		for _, l := range layers {
			filter.layerKey = l
			if err := fwpmFilterAdd0(session, &filter, 0, &filterID); err != nil {
				return wrapErr(err)
			}
		}
		return nil
	}
	if err := add(v4, "Permit allowed ranges outside the tunnel (IPv4)", cFWPM_LAYER_ALE_AUTH_CONNECT_V4, cFWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V4); err != nil {
		return err
	}
	if err := add(v6, "Permit allowed ranges outside the tunnel (IPv6)", cFWPM_LAYER_ALE_AUTH_CONNECT_V6, cFWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V6); err != nil {
		return err
	}
	runtime.KeepAlive(keep4)
	runtime.KeepAlive(keep6)
	runtime.KeepAlive(v4)
	runtime.KeepAlive(v6)
	return nil
}

// blockIPv6 blocks all IPv6 except what other rules permit.
func blockIPv6(session uintptr, baseObjects *baseObjects, weight uint8) error {
	filter := wtFwpmFilter0{
		providerKey: &baseObjects.provider,
		subLayerKey: baseObjects.filters,
		weight:      filterWeight(weight),
		action: wtFwpmAction0{
			_type: cFWP_ACTION_BLOCK,
		},
	}
	filterID := uint64(0)
	for _, l := range []struct {
		name  string
		layer windows.GUID
	}{
		{"Block all outbound (IPv6)", cFWPM_LAYER_ALE_AUTH_CONNECT_V6},
		{"Block all inbound (IPv6)", cFWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V6},
	} {
		displayData, err := createWtFwpmDisplayData0(l.name, "")
		if err != nil {
			return wrapErr(err)
		}
		filter.displayData = *displayData
		filter.layerKey = l.layer
		if err := fwpmFilterAdd0(session, &filter, 0, &filterID); err != nil {
			return wrapErr(err)
		}
	}
	return nil
}
