package sub

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hkdf"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"strings"
)

// Sealed configs (end-to-end, on top of TLS): a panel that sees the request
// header "X-Kanki-Seal: kseal1" may answer with
//
//	kseal1.<base64url(nonce(12) | AES-256-GCM ciphertext+tag)>
//
// key   = HKDF-SHA256(secret = subscription code, salt = X-Kanki-Device, info = "kanki sealed config v1")
// aad   = "kseal1"
//
// Only the panel and this device know both inputs, so a config cached or
// logged anywhere on the way (CDN, Iran relay, proxy) is unreadable. The text
// is opened only in memory; a panel without this feature answers in plain text.
const sealVersion = "kseal1"

func isSealed(b []byte) bool { return strings.HasPrefix(string(b), sealVersion+".") }

func sealKey(code, device string) ([]byte, error) {
	return hkdf.Key(sha256.New, []byte(code), []byte(device), "kanki sealed config v1", 32)
}

func open(body []byte, code, device string) (string, error) {
	raw, err := base64.RawURLEncoding.DecodeString(strings.TrimSpace(string(body[len(sealVersion)+1:])))
	if err != nil || len(raw) < 12+16 {
		return "", errors.New("sealed config: bad encoding")
	}
	key, err := sealKey(code, device)
	if err != nil {
		return "", err
	}
	defer clear(key)
	block, err := aes.NewCipher(key)
	if err != nil {
		return "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	plain, err := gcm.Open(nil, raw[:12], raw[12:], []byte(sealVersion))
	if err != nil {
		return "", errors.New("sealed config: cannot open (wrong device or code)")
	}
	defer clear(plain)
	return string(plain), nil
}

// Seal is the panel side, here for tests and as the reference for the panel.
func Seal(plain []byte, code, device string, nonce []byte) (string, error) {
	key, err := sealKey(code, device)
	if err != nil {
		return "", err
	}
	defer clear(key)
	block, _ := aes.NewCipher(key)
	gcm, _ := cipher.NewGCM(block)
	out := gcm.Seal(append([]byte{}, nonce...), nonce, plain, []byte(sealVersion))
	return sealVersion + "." + base64.RawURLEncoding.EncodeToString(out), nil
}
