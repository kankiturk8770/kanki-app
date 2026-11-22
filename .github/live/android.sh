#!/bin/bash
# Live test on an Android emulator: install, log in through the UI with the test subscription,
# connect with the big button, then check the system VPN, the exit IP and a network change.
set -u
P=cafe.kankipro.vpn
out=live-out; mkdir -p $out
code=${LIVE_SUB##*/}
shot() { adb exec-out screencap -p > "$out/$1.png" 2>/dev/null; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml; }
texts() { dump | grep -o 'text="[^"]*"' | sed 's/text=//' | grep -v '^""$' | grep -v -F "$code" | head -${1:-40} | tr '\n' ' '; echo; }
front() { adb shell am start -n $P/.MainActivity >/dev/null 2>&1; sleep 3; }
# tap: exact text ($1) or, with "orb", the biggest square clickable node (the connect button)
tap() {
  local b; b=$(dump | python3 -c '
import sys,re
x=sys.stdin.read(); w=sys.argv[1]; best=None
for m in re.finditer(r"<node [^>]*>",x):
    n=m.group(0); b=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n)
    if not b: continue
    a=list(map(int,b.groups())); W,H=a[2]-a[0],a[3]-a[1]
    if w=="orb":
        if W>0 and 0.8<H/W<1.25 and W>80 and W<300 and ("clickable=\"true\"" in n or "android.view.View" in n) and (best is None or W*H>best[0]): best=(W*H,a)
    elif w=="edit":
        if "EditText" in n: best=(0,a); break
    elif ("text=\""+w+"\"") in n: best=(0,a); break
if best: a=best[1]; print((a[0]+a[2])//2,(a[1]+a[3])//2)' "$1")
  [ -n "$b" ] && adb shell input tap $b && echo "tapped $1 at $b" && return 0
  echo "not found: $1"; return 1
}
vpn() { adb shell dumpsys connectivity | grep -c "Transports: VPN"; }
myip() {
  adb shell am start -a android.intent.action.VIEW -d "https://api.ipify.org/?format=text" >/dev/null 2>&1; sleep 15
  local t; t=$(dump | grep -o 'text="[0-9]\{1,3\}\.[0-9]\{1,3\}\.[0-9]\{1,3\}\.[0-9]\{1,3\}"' | head -1 | sed 's/text=//; s/"//g')
  if [ -z "$t" ]; then   # Chrome's first run screens
    tap "Accept &amp; continue" >/dev/null || tap "Accept & continue" >/dev/null; sleep 3; tap "No thanks" >/dev/null; tap "No, thanks" >/dev/null; sleep 3
    adb shell am start -a android.intent.action.VIEW -d "https://api.ipify.org/?format=text" >/dev/null 2>&1; sleep 12
    t=$(dump | grep -o 'text="[0-9]\{1,3\}\.[0-9]\{1,3\}\.[0-9]\{1,3\}\.[0-9]\{1,3\}"' | head -1 | sed 's/text=//; s/"//g')
  fi
  [ -z "$t" ] && { echo "(browser shows: $(texts 8))" >&2; }
  echo "$t"
}

adb install -r app.apk && echo "install ok" || { echo "install FAILED"; exit 1; }
adb shell appops set $P ACTIVATE_VPN allow
adb shell pm grant $P android.permission.POST_NOTIFICATIONS 2>/dev/null
real=$(myip); echo "IP without VPN: $real"
adb shell am force-stop com.android.chrome
adb shell am start -W -n $P/.MainActivity; sleep 15; shot 01-start
echo "screen: $(texts 20)"
tap edit; sleep 1; adb shell input text "$code"; sleep 1; adb shell input keyevent 4; sleep 1
tap "ورود"; sleep 30; shot 02-login
echo "after login: $(texts 40)"
front; tap "بعداً"; sleep 2
echo "clickable/views on home:"; dump | python3 -c '
import sys,re
for m in re.finditer(r"<node [^>]*>",sys.stdin.read()):
    n=m.group(0)
    if "clickable=\"true\"" in n:
        b=re.search(r"bounds=\"([^\"]+)\"",n); t=re.search(r" text=\"([^\"]*)\"",n); c=re.search(r"class=\"([^\"]*)\"",n)
        print(" ",b.group(1) if b else "", c.group(1) if c else "", (t.group(1) if t else "")[:30])' | head -30
# ---- every config, one by one, through the test hook of the debug build (LiveTest in MainActivity.kt).
# One run at a time: the test account allows one device, two emulators at once take each other's place
kk() { # pick mode games nogate
  adb logcat -c
  # single-top: the running activity gets the intent (onNewIntent); an empty value would vanish in adb's shell line
  adb shell am start --activity-single-top --activity-clear-top -n $P/.MainActivity --es kk_pick "'$1'" --es kk_mode "$2" --es kk_games "${3:--}" --es kk_nogate "${4:-0}"
}
st() { adb logcat -d -s KankiLive:I | grep " state " | tail -1 | sed 's/.*KankiLive: //'; }
kk "none|none" game ""; sleep 8
adb logcat -d -s KankiLive:I | grep " cfg " | sed 's/.*KankiLive: //' | sort -u > $out/cfgs.txt
echo "configs:"; cat $out/cfgs.txt
pickof() { grep "^cfg [^|]*|$1 side=$2" $out/cfgs.txt | head -1 | sed 's/^cfg //; s/ side=.*//'; }
GAME=com.android.chrome
run() { # label pick nogate games
  [ -z "$2" ] && { echo "== $1: not in this subscription"; return; }
  kk "$2" game "${4:-$GAME}" "${3:-0}"; sleep 35
  local s; s=$(st); local ip; ip=$(myip); adb shell am force-stop com.android.chrome
  local sh; sh=$(adb shell 'toybox nc -w 5 1.1.1.1 80 </dev/null >/dev/null 2>&1 && echo open || echo closed')
  echo "== $1 [$2] gate=$([ "${3:-0}" = 1 ] && echo off || echo on) games=${4:-$GAME}"
  echo "   state: $s"
  echo "   chrome IP: ${ip:-no answer} (without VPN: $real) -> $([ -z "$ip" ] && echo 'no answer' || { [ "$ip" = "$real" ] && echo LEAK || echo 'through VPN'; })"
  echo "   shell (not a game) TCP 1.1.1.1:80: $sh"
  adb logcat -d | grep -i -E "AmneziaWG|GoBackend|BackendException|KankiAwg|KankiVPN|wireguard" | grep -v -F "$code" | tail -8 | sed 's/^/   log: /'
}
# the tunnel copies first: the test account allows one device, and the server counts the same WireGuard key from two
# addresses (straight and through Iran) as two devices for a while
run "WireGuard tunnel, gaming" "$(pickof wireguard tunnel)"
run "WireGuard direct, gaming (libawg + gate)" "$(pickof wireguard direct)"
run "WireGuard direct, amnezia engine (no gate)" "$(pickof wireguard direct)" 1
run "AmneziaWG direct, gaming" "$(pickof amneziawg-direct direct)"
run "AmneziaWG tunnel, gaming" "$(pickof amneziawg tunnel)"
run "KANKI MIX, gaming" "$(pickof kankimix direct)"
run "microtun, gaming" "$(pickof mtun direct)"
run "KPING, gaming" "$(grep '^cfg [^|]*|kping ' $out/cfgs.txt | head -1 | sed 's/^cfg //; s/ side=.*//')"
run "WireGuard direct, gaming, Chrome NOT a game (must be closed)" "$(pickof wireguard direct)" 0 "com.example.notagame"
shot 03-connect
adb logcat -d -t 800 | grep -i -E "kanki|AndroidRuntime|FATAL" | grep -v -F "$code" | tail -40
exit 0
