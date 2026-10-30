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

// Off Windows (development and tests) the file is plain; the folder is 0700.
func protect(b []byte) ([]byte, error)   { return b, nil }
func unprotect(b []byte) ([]byte, error) { return b, nil }
