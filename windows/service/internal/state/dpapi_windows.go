package state

import (
	"bytes"
	"unsafe"

	"golang.org/x/sys/windows"
)

// The state file is sealed with DPAPI under the service's own account (SYSTEM):
// only a process running as SYSTEM on this computer can open it, and a copy of
// the file taken to another computer is useless.
var (
	magic   = []byte("KDPAPI1\n")
	entropy = []byte("Kanki VPN service state v1")
)

func blob(b []byte) *windows.DataBlob {
	if len(b) == 0 {
		return &windows.DataBlob{}
	}
	return &windows.DataBlob{Size: uint32(len(b)), Data: &b[0]}
}

func protect(plain []byte) ([]byte, error) {
	var out windows.DataBlob
	desc, _ := windows.UTF16PtrFromString("Kanki VPN")
	if err := windows.CryptProtectData(blob(plain), desc, blob(entropy), 0, nil, windows.CRYPTPROTECT_UI_FORBIDDEN, &out); err != nil {
		return nil, err
	}
	defer windows.LocalFree(windows.Handle(unsafe.Pointer(out.Data)))
	res := append([]byte{}, magic...)
	return append(res, unsafe.Slice(out.Data, out.Size)...), nil
}

func unprotect(b []byte) ([]byte, error) {
	if !bytes.HasPrefix(b, magic) {
		return b, nil // a plain file from version 1.0.0: read once, sealed on the next save
	}
	var out windows.DataBlob
	if err := windows.CryptUnprotectData(blob(b[len(magic):]), nil, blob(entropy), 0, nil, windows.CRYPTPROTECT_UI_FORBIDDEN, &out); err != nil {
		return nil, err
	}
	defer windows.LocalFree(windows.Handle(unsafe.Pointer(out.Data)))
	return append([]byte{}, unsafe.Slice(out.Data, out.Size)...), nil
}
