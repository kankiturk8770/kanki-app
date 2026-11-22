package main

import (
	"os"
	"syscall"
	"testing"
)

// one packet per read and write over a SEQPACKET pair, like the app's gate
func TestPacketEnds(t *testing.T) {
	fds, err := syscall.Socketpair(syscall.AF_UNIX, syscall.SOCK_SEQPACKET, 0)
	if err != nil {
		t.Fatal(err)
	}
	a := os.NewFile(uintptr(fds[0]), "a")
	d := newPktDev(os.NewFile(uintptr(fds[1]), "b"), 1320)
	defer d.Close()
	defer a.Close()
	if _, err := a.Write([]byte("one")); err != nil {
		t.Fatal(err)
	}
	if _, err := a.Write([]byte("second")); err != nil {
		t.Fatal(err)
	}
	buf := [][]byte{make([]byte, 64)}
	sz := []int{0}
	for _, want := range []string{"one", "second"} {
		if n, err := d.Read(buf, sz, 16); err != nil || n != 1 || string(buf[0][16:16+sz[0]]) != want {
			t.Fatalf("read %q: n=%d err=%v got %q", want, n, err, buf[0][16:16+sz[0]])
		}
	}
	p := make([]byte, 16+4)
	copy(p[16:], "back")
	if n, err := d.Write([][]byte{p}, 16); err != nil || n != 1 {
		t.Fatal(n, err)
	}
	b := make([]byte, 64)
	if n, _ := a.Read(b); string(b[:n]) != "back" {
		t.Fatalf("got %q", b[:n])
	}
	if mtu, _ := d.MTU(); mtu != 1320 {
		t.Fatal(mtu)
	}
}

func TestFlags(t *testing.T) {
	f := flags([]string{"--uapi", "x", "--sock", "y", "--mtu", "1300"})
	if f["uapi"] != "x" || f["sock"] != "y" || f["mtu"] != "1300" {
		t.Fatal(f)
	}
}
