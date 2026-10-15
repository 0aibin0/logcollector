#!/usr/bin/env bash
# Syntax-check every shell command string the app hands to `sh -c` / `su -c`.
#
# A malformed command string is a silent failure mode: the collector records the
# artifact as "unavailable" and nobody notices the quoting was wrong. This runs each
# command through `bash -n` (parse only, no execution) so quoting errors surface here.
#
# The commands below are extracted verbatim from the Java sources.

pass=0
fail=0

check() {
  local name="$1"
  local cmd="$2"
  if bash -n -c "$cmd" 2>/dev/null; then
    pass=$((pass+1))
    printf '  [OK]   %s\n' "$name"
  else
    fail=$((fail+1))
    printf '  [FAIL] %s\n' "$name"
    printf '         cmd: %s\n' "$cmd"
    bash -n -c "$cmd" 2>&1 | head -3 | sed 's/^/         /'
  fi
}

echo "=== Collector: dmesg ==="
check "dmesg"                 'dmesg'
check "dmesg -t"              'dmesg -t'
check "cat /dev/kmsg"         'cat /dev/kmsg'
check "dmesg -T"              'dmesg -T'
check "dmesg levels"          'dmesg | sed -n '"'"'s/^<\\?\([0-9]\)\\?>\\?.*/\1/p'"'"' | sort | uniq -c | sort -rn'

echo
echo "=== Collector: logcat ==="
check "logcat -g"             'logcat -g'
check "buffer single"         'logcat -b main -d -v threadtime -t 500'
check "buffer tail fallback"  'logcat -b main -d -v threadtime | tail -n 500'
check "merged multi -b"       'logcat -b main -b system -b crash -d -v threadtime -t 500'

echo
echo "=== Collector: dumpsys ==="
check "dumpsys -l"            'dumpsys -l'
check "dumpsys svc"           'dumpsys battery'
check "dumpsys all"           'dumpsys'
check "SurfaceFlinger --list" 'dumpsys SurfaceFlinger --list'

echo
echo "=== Collector: prop ==="
check "getprop"               'getprop'
check "getprop sorted"        'getprop | sed -n '"'"'s/^\[\(.*\)\]: \[\(.*\)\]/\1=\2/p'"'"' | sort'
check "build.prop loop"       'for f in /system/build.prop /vendor/build.prop /odm/etc/build.prop; do echo "### $f"; cat $f 2>/dev/null; done'

echo
echo "=== Collector: kernel ==="
check "thermal loop"          'for z in /sys/class/thermal/thermal_zone*; do [ -d "$z" ] || continue; echo "### $z  type=$(cat $z/type 2>/dev/null)  temp=$(cat $z/temp 2>/dev/null)"; done'
check "cooling loop"          'for c in /sys/class/thermal/cooling_device*; do [ -d "$c" ] || continue; echo "### $c  type=$(cat $c/type 2>/dev/null)  state=$(cat $c/cur_state 2>/dev/null)/$(cat $c/max_state 2>/dev/null)"; done'
check "regulator loop"        'for r in /sys/class/regulator/regulator.*; do [ -d "$r" ] || continue; echo "### $r  name=$(cat $r/name 2>/dev/null)  state=$(cat $r/state 2>/dev/null) microvolts=$(cat $r/microvolts 2>/dev/null)  mode=$(cat $r/opmode 2>/dev/null)"; done'
check "debugfs ls"            'ls /sys/kernel/debug 2>&1 | head -n 60'

echo
echo "=== Collector: bugreport extra ==="
check "dropbox"               'ls -la /data/system/dropbox 2>&1 | head -n 100'
check "kernel warnings"       'dmesg 2>&1 | grep -iE '"'"'warn|error|fail|panic|bug|oops'"'"' | tail -n 300'
check "du_data"               'du -sh /data/* 2>&1 | sort -h | tail -n 40'
check "reboot reason"         'cat /sys/module/kernel/parameters/panic 2>&1; getprop ro.boot.bootreason'

echo
echo "=== DisplayCollector ==="
check "backlight loop"        'for d in /sys/class/backlight/*; do   [ -d "$d" ] || continue;   echo "########## $d";   echo "--- resolve: $(ls -l $d 2>/dev/null | tail -n +2 | head -n 3)";   for a in "$d"/*; do     [ -f "$a" ] || continue;     n=$(basename "$a");     case "$n" in       uevent|subsystem|*_link) continue;;     esac;     v=$(cat "$a" 2>&1 | head -c 4000);     echo "  $n = $v";   done; done'
check "leds loop"             'for d in /sys/class/leds/*; do   [ -d "$d" ] || continue;   echo "########## $d";   for a in brightness max_brightness actual_brightness device_name led_mode; do     [ -f "$d/$a" ] || continue;     echo "  $a = $(cat $d/$a 2>&1 | head -c 2000)";   done; done'

# The SHIPPED deep brightness scan. Replaced `find /sys/devices -maxdepth 6 ...`, which is not
# portable (toybox only gained -maxdepth in Android 8; busybox rejects it) and never reached a
# real brightness node anyway - backlight entries are symlinks, so the node sits 7-8 levels down.
check "deep brightness scan (shipped)" 'scan() { d=$1; n=$2; [ $n -gt 0 ] || return 0; for p in "$d"/*; do [ -e "$p" ] || continue;   b=$(basename "$p");   case "$b" in brightness|bl_level|*backlight*|disp_*)     if [ -f "$p" ]; then echo "$p = $(cat "$p" 2>&1 | head -c 200)";     else echo "$p/ (dir)"; fi ;; esac;   [ -d "$p" ] && scan "$p" $((n-1)); done; }; scan /sys/devices 12 2>/dev/null | head -n 200'

# The SHIPPED debugfs dsi/panel scan. Same shell-recursive replacement.
check "disp_debug scan (shipped)" 'scan() { d=$1; n=$2; [ $n -gt 0 ] || return 0; for p in "$d"/*; do [ -e "$p" ] || continue;   b=$(basename "$p");   case "$b" in *dsi*|*panel*) echo "$p" ;; esac;   [ -d "$p" ] && scan "$p" $((n-1)); done; }; scan /sys/kernel/debug 4 2>/dev/null | head -n 60'

# The brightness timeline sample command. The trailing `echo` per node is load-bearing: a sysfs
# attribute need not end in a newline, and without it the whole sample collapses onto one line.
check "timeline sample (shipped)" 'printf '"'"'%s\t'"'"' '"'"'/sys/class/backlight/panel0/brightness'"'"'; cat '"'"'/sys/class/backlight/panel0/brightness'"'"' 2>/dev/null || echo '"'"'-'"'"'; echo; printf '"'"'%s\t'"'"' '"'"'/sys/class/backlight/panel0/max_brightness'"'"'; cat '"'"'/sys/class/backlight/panel0/max_brightness'"'"' 2>/dev/null || echo '"'"'-'"'"'; echo'

# cmdline sources (new feature).
check "cmdline: /proc/cmdline"     'cat /proc/cmdline'
check "cmdline: /proc/bootconfig"  'cat /proc/bootconfig'
check "cmdline: device-tree"       'cat /proc/device-tree/chosen/bootargs 2>&1; cat /sys/firmware/devicetree/base/chosen/bootargs 2>&1'
check "cmdline: ro.boot.* filter"  'getprop'

# kernel log channel (new: the working substitute for dmesg).
check "kernel log: -t form"        'logcat -b kernel -d -v threadtime -t 500'
check "kernel log: tail fallback"  'logcat -b kernel -d -v threadtime | tail -n 500'
check "logcat unbounded (3rd form)" 'logcat -b main -b system -b crash -d -v threadtime'

check "displayconfig cat"     'for f in /vendor/etc/displayconfig/*.xml /vendor/etc/displayconfig/*.conf /vendor/etc/displayconfig/*.json; do   [ -f "$f" ] || continue;   echo "########## $f";   cat "$f" 2>&1;   echo; done'
check "brightness map grep"   'grep -n -A 60 -i '"'"'screenBrightnessMap\|brightnessMap\|highBrightnessMode\|transitionPoint\|nits'"'"' /vendor/etc/displayconfig/*.xml /odm/etc/displayconfig/*.xml 2>/dev/null | head -n 400'
check "drm status"           'for f in /sys/class/drm/*/status /sys/class/drm/*/modes /sys/class/drm/*/enabled; do [ -f "$f" ] || continue; echo "$f = $(cat $f 2>&1 | head -c 2000)"; done'
check "graphics modes"        'for d in /sys/class/graphics/*; do [ -d "$d" ] || continue; echo "### $d"; for a in modes mode blank state name; do [ -f "$d/$a" ] || continue; echo "  $a = $(cat $d/$a 2>&1 | head -c 1000)"; done; done'
check "brightness nodes find" 'for p in /sys/class/backlight/*/brightness /sys/class/backlight/*/actual_brightness /sys/class/leds/*/brightness /sys/class/leds/*/actual_brightness; do   [ -f "$p" ] && echo "$p"; done'
check "dt panel"              'for f in /proc/device-tree/*panel*/*; do echo "$f = $(cat $f 2>/dev/null | tr -d '"'"'\0'"'"' | head -c 200)"; done 2>/dev/null | head -n 100'
check "prop_sorted transform" 'getprop | sed -n '"'"'s/^\[\(.*\)\]: \[\(.*\)\]/\1=\2/p'"'"' | sort'
check "du by size (no sort -h)" 'du -sk /data/* 2>/dev/null | sort -n | tail -n 40'

echo
echo "=== Shell / Capabilities / Media / Storage ==="
check "id probe"              'id'
check "echo ROOTOK"           'echo ROOTOK'
check "dmesg head"            'dmesg -t 2>&1 | head -n 1'
check "screenrecord test"     'test -x /system/bin/screenrecord && echo YES || echo NO'
check "dumpsys count"         'dumpsys -l 2>/dev/null | wc -l'
check "logcat all probe"      'logcat -b all -d -t 1 >/dev/null 2>&1 && echo ALL_OK'
check "logcat buf probe"      'logcat -b main -d -t 1 >/dev/null 2>&1 && echo B_OK'
check "command -v screenrec"  'command -v screenrecord || ls /system/bin/screenrecord 2>&1'

# The escaped path form: Storage.esc turns /a'b into /a'\''b, so the generated
# command must still parse after substitution.
echo
echo "=== Escaped path interpolation (Storage.esc output) ==="
check "mkdir+escaped path"    "mkdir -p '/sdcard/log/20260101-120000' && touch '/sdcard/log/20260101-120000/.probe' && rm -f '/sdcard/log/20260101-120000/.probe' && echo PROBE_OK"
check "su cp escaped"         "mkdir -p '/sdcard/log/run' && cp '/data/data/x/cache/stage/dmesg.txt' '/sdcard/log/run/dmesg.txt' && chmod 666 '/sdcard/log/run/dmesg.txt' && echo COPIED"
check "path with quote"       "mkdir -p '/sdcard/it'\\''s log' && echo SHELL_OK"
check "path with spaces"      "mkdir -p '/sdcard/my log dir' && echo SHELL_OK"
check "su symlink"            "rm -rf '/sdcard/log/latest' && ln -s '/sdcard/log/20260101-120000' '/sdcard/log/latest' && echo LINKED"
check "rm -rf escaped"        "rm -rf '/sdcard/log/old run'"
check "pkill dumpsys"         "pkill -f '^dumpsys' 2>/dev/null"

echo
echo "============================================"
printf 'PASS %d   FAIL %d\n' "$pass" "$fail"
echo "============================================"
[ "$fail" -eq 0 ]
