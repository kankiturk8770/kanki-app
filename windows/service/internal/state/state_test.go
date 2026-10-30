package state

import (
	"testing"

	"kankivpn/internal/sub"
)

func TestSettingsCheck(t *testing.T) {
	s := Defaults()
	s.SplitMode = "exclude"
	s.SplitList = []string{" 5.0.0.0/8 ", "", "91.108.4.1"}
	if err := s.Check(); err != nil || len(s.SplitList) != 2 {
		t.Fatal(err, s.SplitList)
	}
	bad := []Settings{
		{SplitMode: "weird"},
		{SplitMode: "include"},
		{SplitList: []string{"not-an-ip"}},
		{DNS: []string{"dns.google"}},
		{MTU: 9000},
	}
	for i, b := range bad {
		if b.Check() == nil {
			t.Errorf("case %d accepted: %+v", i, b)
		}
	}
}

func TestStoreRoundTrip(t *testing.T) {
	st, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if v := st.Load(); !v.Settings.KillSwitch {
		t.Fatal("defaults missing")
	}
	in := Saved{Settings: Defaults(), LastServer: "local/amneziawg", WasConnected: true,
		Account: &sub.Account{Code: "kanki-1-abcd", Servers: []sub.Server{{ID: "local/amneziawg", URL: "https://x/api/config/1/amneziawg?sub=k"}}}}
	if err := st.Save(in); err != nil {
		t.Fatal(err)
	}
	out := st.Load()
	if out.LastServer != in.LastServer || !out.WasConnected || out.Account.Code != "kanki-1-abcd" || out.Account.Servers[0].URL == "" {
		t.Fatalf("got %+v", out)
	}
}
