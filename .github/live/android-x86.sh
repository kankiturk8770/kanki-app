#!/bin/bash
# A debug APK for the emulator only (x86_64): the release is ARM-only and the emulator's ARM translation
# crashes it at start. Same app code, same cores, built for x86_64. Nothing here is published.
set -e
NDK="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-$ANDROID_NDK}}"
if [ ! -d "$NDK" ]; then NDK=$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1); fi
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
A=x86_64; d=$PWD/app/src/main/jniLibs/$A; mkdir -p "$d"

# Hysteria2 (hash from the release's own list)
HY=v2.12.3; base="https://github.com/HyNetworks/hysteria/releases/download/app%2F$HY"
curl -fsSL --retry 3 -o /tmp/hy-hashes.txt "$base/hashes.txt"
curl -fsSL --retry 3 -o "$d/libhysteria.so" "$base/hysteria-android-amd64"
want=$(awk '{ n=$2; sub(/^.*\//,"",n); if (n=="hysteria-android-amd64") print $1 }' /tmp/hy-hashes.txt)
[ "$want" = "$(sha256sum "$d/libhysteria.so" | cut -d' ' -f1)" ] || { echo "hysteria hash"; exit 1; }
chmod +x "$d/libhysteria.so"

# Xray
XR=v26.3.27; xb="https://github.com/XTLS/Xray-core/releases/download/$XR"
curl -fsSL --retry 3 -o /tmp/x.zip "$xb/Xray-android-amd64.zip"; curl -fsSL --retry 3 -o /tmp/x.dgst "$xb/Xray-android-amd64.zip.dgst"
[ "$(grep -m1 '^SHA2-256' /tmp/x.dgst | awk '{print $2}')" = "$(sha256sum /tmp/x.zip | cut -d' ' -f1)" ] || { echo "xray hash"; exit 1; }
rm -rf /tmp/xr && unzip -q -o /tmp/x.zip xray -d /tmp/xr && mv /tmp/xr/xray "$d/libxray.so" && chmod +x "$d/libxray.so"

# hev-socks5-tunnel (same reviewed commit as the release)
rm -rf /tmp/hev && mkdir -p /tmp/hev && cd /tmp/hev
git clone -q --recursive --depth 1 --branch 2.17.1 https://github.com/heiher/hev-socks5-tunnel jni
[ "$(git -C jni rev-parse HEAD)" = "9a06bc6e7989da54e3d32ff701ef7a7ce4995d3a" ] || { echo "hev commit"; exit 1; }
printf '%s\n' 'APP_OPTIM := release' 'APP_PLATFORM := android-26' 'APP_ABI := x86_64' \
  'APP_CFLAGS := -O3 -DPKGNAME=cafe/kankipro/vpn -DCLSNAME=TProxyService' 'NDK_TOOLCHAIN_VERSION := clang' > jni/Application.mk
"$NDK/ndk-build" -j"$(nproc)" >/dev/null
cp libs/$A/libhev-socks5-tunnel.so "$d/"
cd "$GITHUB_WORKSPACE"

# Go cores
gob() { # dir pkg out tags
  (cd "$1" && CGO_ENABLED=1 GOOS=android GOARCH=amd64 CC="$TC/x86_64-linux-android26-clang" \
    go build $4 -trimpath -ldflags "-s -w" -o "$d/$3" "$2")
  chmod +x "$d/$3"
}
gob kmix-core ./cmd/kmix libkmix.so "-tags tcp"
gob mtun-core . libmtun.so ""
gob kping-core . libkping.so ""
gob awg-core . libawg.so ""
ls -la "$d"

# this copy only: build the x86_64 split
sed -i 's/include("arm64-v8a", "armeabi-v7a")/include("x86_64")/' app/build.gradle.kts
gradle -q assembleDebug
cp app/build/outputs/apk/debug/*x86_64*.apk app.apk
ls -la app.apk
