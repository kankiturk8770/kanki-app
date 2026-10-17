//go:build tools

// gomobile bind برای ساختن AAR به golang.org/x/mobile/bind در go.mod نیاز دارد
package tools

import _ "golang.org/x/mobile/bind"
