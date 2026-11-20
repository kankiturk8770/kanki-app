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
tap orb || tap "آماده"; sleep 40; shot 03-connect
echo "after connect: $(texts 30)"
echo "system VPN networks: $(vpn)"
vip=$(myip); echo "IP with VPN: $vip (without: $real) -> $([ -z "$vip" ] && echo 'no answer' || { [ "$vip" = "$real" ] && echo LEAK || echo 'ok, different'; })"
adb shell am force-stop com.android.chrome
# network change: wifi and data off, then on; the app must come back by itself
adb shell svc wifi disable; adb shell svc data disable; sleep 6; adb shell svc wifi enable; adb shell svc data enable; sleep 40
echo "system VPN networks after the network came back: $(vpn)"
v2=$(myip); echo "IP after the network came back: $v2 -> $([ -z "$v2" ] && echo 'no answer' || { [ "$v2" = "$real" ] && echo LEAK || echo 'ok, different'; })"
front; shot 04-after; echo "app now: $(texts 30)"
adb logcat -d -t 600 | grep -i -E "kanki|hysteria|xray|kmix|AndroidRuntime|FATAL" | grep -v -F "$code" | tail -60
