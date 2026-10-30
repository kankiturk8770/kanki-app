package state

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"
)

func TestDPAPIRoundTrip(t *testing.T) {
	sealed, err := protect([]byte("kanki-12-secret"))
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(sealed, []byte("secret")) || !bytes.HasPrefix(sealed, magic) {
		t.Fatal("not sealed")
	}
	plain, err := unprotect(sealed)
	if err != nil || string(plain) != "kanki-12-secret" {
		t.Fatal(string(plain), err)
	}
	dir := t.TempDir()
	st := &Store{path: filepath.Join(dir, "state.json")}
	v := st.Load()
	v.LastServer = "secret-server"
	if err := st.Save(v); err != nil {
		t.Fatal(err)
	}
	raw, _ := os.ReadFile(st.path)
	if bytes.Contains(raw, []byte("secret-server")) {
		t.Fatal("state written in the clear")
	}
	if st.Load().LastServer != "secret-server" {
		t.Fatal("not read back")
	}
}
