#!/usr/bin/env bash
# Capture Play Store screenshots from an emulator. Needs a seeded DB (see seed_db.py) and
# ./v/bin/python or any python3 with Pillow. Usage: SER=emulator-5558 PY=python3 ./capture.sh
set -euo pipefail
SER=${SER:-emulator-5558}
PY=${PY:-python3}
OUT=$(cd "$(dirname "$0")" && pwd)/screenshots
PKG=com.thsvkd.curfew
A() { adb -s "$SER" "$@"; }

tap_text() { # tap the center of the UI node whose text equals $1 (optional dy px offset $2)
  A shell uiautomator dump /sdcard/ui.xml >/dev/null
  A exec-out cat /sdcard/ui.xml | "$PY" -c "
import re,sys,subprocess
x=sys.stdin.read(); t=sys.argv[1]; dy=int(sys.argv[2])
m=re.search(r'text=\"'+re.escape(t)+r'\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',x)
l,tp,r,b=map(int,m.groups()); cx,cy=(l+r)//2,(tp+b)//2+dy
subprocess.run(['adb','-s','$SER','shell','input','tap',str(cx),str(cy)])" "$1" "${2:-0}"
  sleep 1.2
}
launch() { A shell am force-stop $PKG; A shell am start -n $PKG/.MainActivity >/dev/null; sleep 4; }
shoot() { # $1 = output basename
  A exec-out screencap -p > /tmp/_raw.png
  "$PY" -c "
from PIL import Image; import sys
im=Image.open('/tmp/_raw.png').convert('RGB'); assert im.size==(1080,1920), im.size
im.save('$OUT/$1.png')"
}

A shell settings put global sysui_demo_allowed 1
D() { A shell am broadcast -a com.android.systemui.demo "$@" >/dev/null; }
demo() { # status bar: 14:30, wifi only, full battery, no notifications (re-run after a density change)
  D -e command exit; sleep 1; D -e command enter
  D -e command clock -e hhmm 1430; D -e command battery -e level 100 -e plugged false
  D -e command network -e wifi show -e level 4 -e fully true; D -e command network -e mobile hide
  D -e command network -e nosim hide; D -e command notifications -e visible false; sleep 1
}

# prefix:density
for cfg in phone:380 tablet7:320 tablet10:213; do
  prefix=${cfg%%:*}; dens=${cfg##*:}
  A shell wm size 1080x1920; A shell wm density "$dens"; sleep 2; demo
  launch;                       shoot "$prefix-01"                 # main, today, line chart
  tap_text "토" -60;            shoot "$prefix-02"                 # failed night (Sat 9/26)
  tap_text "막대";              shoot "$prefix-03"                 # bar chart mode
  tap_text "선"                                                    # restore line mode
  launch; tap_text "설정";      shoot "$prefix-04"                 # settings
done
A shell wm size reset; A shell wm density reset
D -e command exit
