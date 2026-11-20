#!/bin/bash
# (run after the LIVE_SUB secret was added)
# Live test on an Android emulator: installs the released APK, logs in through the UI with a test
# subscription, connects, and checks the exit IP and DNS from the phone itself.
set -u
P=cafe.kankipro.vpn
out=live-out; mkdir -p $out
shot() { adb exec-out screencap -p > "$out/$1.png" 2>/dev/null; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml; }
# center of the first node whose text or desc contains $1
tapon() {
  local b; b=$(dump | python3 -c '
import sys,re
x=sys.stdin.read(); w=sys.argv[1]
for m in re.finditer(r"<node [^>]*>",x):
    n=m.group(0)
    if w in n:
        b=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n)
        if b:
            a=list(map(int,b.groups())); print((a[0]+a[2])//2,(a[1]+a[3])//2); break' "$1")
  [ -n "$b" ] && adb shell input tap $b && return 0; return 1
}
myip() { adb shell 'printf "GET /?format=text HTTP/1.0\r\nHost: api.ipify.org\r\n\r\n" | toybox nc -w 10 api.ipify.org 80' 2>/dev/null | tail -1 | tr -d '\r'; }

adb shell getprop ro.product.cpu.abilist
real=$(myip); echo "emulator IP without VPN: $real"
adb install -r app.apk && echo "install ok" || { echo "install FAILED"; exit 1; }
adb shell appops set $P ACTIVATE_VPN allow
adb shell pm grant $P android.permission.POST_NOTIFICATIONS 2>/dev/null
adb shell monkey -p $P -c android.intent.category.LAUNCHER 1 >/dev/null; sleep 12; shot 01-start
# login: the only text field, type the code, then the login button
tapon 'EditText' || tapon 'class="android.widget.EditText"' || adb shell input tap 540 1200
sleep 1; adb shell input text "$LIVE_SUB"; sleep 1; adb shell input keyevent 66; sleep 2
tapon 'ورود' ; sleep 25; shot 02-after-login
dump > $out/ui-after-login.xml
grep -o 'text="[^"]*"' $out/ui-after-login.xml | sed 's/text=//' | grep -v '^""$' | head -60 | tr '\n' ' '; echo
# connect with the big button (its description or the power text), then wait
tapon 'اتصال' || tapon 'Connect' || true
sleep 35; shot 03-connect
adb shell dumpsys connectivity | grep -i -m3 "VPN" 
vip=$(myip); echo "IP with VPN: $vip -> $([ "$vip" = "$real" ] && echo LEAK || echo ok)"
adb shell 'toybox nc -w 5 -z 1.1.1.1 443' && echo "tcp 1.1.1.1:443 ok"
dump > $out/ui-connected.xml
grep -o 'text="[^"]*"' $out/ui-connected.xml | sed 's/text=//' | grep -v '^""$' | head -60 | tr '\n' ' '; echo
# network change: wifi/data off and on (emulator: svc data), then the app must come back by itself
adb shell svc data disable; adb shell svc wifi disable; sleep 4; adb shell svc data enable; adb shell svc wifi enable; sleep 30
v2=$(myip); echo "IP after the network came back: $v2 -> $([ "$v2" = "$real" ] && echo LEAK || echo ok)"; shot 04-after-network-change
adb logcat -d -t 400 | grep -i -E "kanki|hysteria|xray|kmix|AndroidRuntime" | grep -v -i "$LIVE_SUB" | tail -80
