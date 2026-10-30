//go:build !windows

package main

import (
	"fmt"
	"os"
)

func main() {
	fmt.Fprintln(os.Stderr, "kanki-svc runs only on Windows")
	os.Exit(2)
}
