// Package state keeps the service's settings and the user's subscription in
// one JSON file. On Windows the folder is %ProgramData%\KankiVPN and only
// SYSTEM and Administrators can read it (the subscription code is a secret).
package state

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"kankivpn/internal/route"
	"kankivpn/internal/sub"
)

// Settings the user changes in the app.
type Settings struct {
	KillSwitch  bool     `json:"kill_switch"`
	BlockIPv6   bool     `json:"block_ipv6"`
	AllowLAN    bool     `json:"allow_lan"`
	SplitMode   string   `json:"split_mode"`
	SplitList   []string `json:"split_list"`
	DNS         []string `json:"dns"` // empty = the config's own DNS
	AutoConnect bool     `json:"auto_connect"`
	MTU         int      `json:"mtu"` // 0 = the config's own MTU
}

// Defaults: safest choice on, nothing surprising.
func Defaults() Settings {
	return Settings{KillSwitch: true, BlockIPv6: true, AllowLAN: true, SplitMode: route.All}
}

// Check validates and tidies settings that came from the app.
func (s *Settings) Check() error {
	switch s.SplitMode {
	case "":
		s.SplitMode = route.All
	case route.All, route.Exclude, route.Include:
	default:
		return errors.New("unknown split mode")
	}
	if len(s.SplitList) > 500 {
		return errors.New("too many split tunnel ranges (max 500)")
	}
	var list []string
	for _, v := range s.SplitList {
		v = strings.TrimSpace(v)
		if v == "" {
			continue
		}
		if _, err := ParsePrefix(v); err != nil {
			return fmt.Errorf("split tunnel: %q is not an IP or range", v)
		}
		list = append(list, v)
	}
	s.SplitList = list
	if s.SplitMode == route.Include && len(s.SplitList) == 0 {
		return errors.New("split tunnel: add at least one range to send through the VPN")
	}
	if len(s.DNS) > 4 {
		return errors.New("at most 4 DNS servers")
	}
	for _, d := range s.DNS {
		if _, err := netip.ParseAddr(strings.TrimSpace(d)); err != nil {
			return fmt.Errorf("DNS: %q is not an IP address", d)
		}
	}
	if s.MTU != 0 && (s.MTU < 1280 || s.MTU > 1500) {
		return errors.New("MTU must be 0 (automatic) or 1280-1500")
	}
	return nil
}

// ParsePrefix accepts "1.2.3.0/24" or a single address.
func ParsePrefix(v string) (netip.Prefix, error) {
	v = strings.TrimSpace(v)
	if p, err := netip.ParsePrefix(v); err == nil {
		return p.Masked(), nil
	}
	a, err := netip.ParseAddr(v)
	if err != nil {
		return netip.Prefix{}, err
	}
	return netip.PrefixFrom(a, a.BitLen()), nil
}

// Saved is the whole file.
type Saved struct {
	Settings     Settings     `json:"settings"`
	Account      *sub.Account `json:"account,omitempty"`
	LastServer   string       `json:"last_server,omitempty"`
	WasConnected bool         `json:"was_connected"` // the user wanted the VPN on when the service stopped
}

// Store reads and writes the file.
type Store struct {
	mu   sync.Mutex
	path string
}

func Open(dir string) (*Store, error) {
	if err := makeDir(dir); err != nil {
		return nil, err
	}
	return &Store{path: filepath.Join(dir, "state.json")}, nil
}

func (s *Store) Load() Saved {
	s.mu.Lock()
	defer s.mu.Unlock()
	v := Saved{Settings: Defaults()}
	b, err := os.ReadFile(s.path)
	if err == nil {
		_ = json.Unmarshal(b, &v)
	}
	if v.Settings.Check() != nil {
		v.Settings = Defaults()
	}
	return v
}

// Save writes atomically: a half-written file never replaces a good one.
func (s *Store) Save(v Saved) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	b, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}
