//go:build !windows

package state

import (
	"os"
	"path/filepath"
)

// Dir is where the service keeps its files (for development off Windows).
func Dir() string {
	if d := os.Getenv("KANKI_DATA"); d != "" {
		return d
	}
	return filepath.Join(os.TempDir(), "kankivpn")
}

func makeDir(dir string) error { return os.MkdirAll(dir, 0o700) }
