#!/system/bin/sh
# Probe every plausible readable source of the panel identity on this device.
# Used to check whether cmdline's msm_drm.dsi_display0 is reachable without root.

echo "=== /proc/cmdline (root only?) ==="
cat /proc/cmdline 2>&1 | tr ' ' '\n' | grep -i 'dsi_display\|panel' | head -5

echo
echo "=== properties mentioning the panel ==="
getprop 2>/dev/null | grep -iE 'vtdr|visionox|ft3519|panel|dsi_display' | head -10

echo
echo "=== /sys/class/graphics ==="
ls /sys/class/graphics/ 2>&1
for f in /sys/class/graphics/fb0/name /sys/class/graphics/fb0/modes; do
  if [ -e "$f" ]; then echo "$f = $(cat "$f" 2>/dev/null | head -c 200)"; fi
done

echo
echo "=== /sys/class/drm ==="
ls /sys/class/drm/ 2>&1
for f in /sys/class/drm/*/status /sys/class/drm/*/modes; do
  if [ -e "$f" ]; then echo "$f = $(cat "$f" 2>/dev/null | head -c 200)"; fi
done

echo
echo "=== device tree panel nodes ==="
ls /proc/device-tree/ 2>&1 | grep -iE 'panel|dsi|display|lcd|mdss' | head -10

echo
echo "=== does the kernel log carry the panel name? ==="
logcat -b kernel -d 2>/dev/null | grep -icE 'vtdr|visionox|ft3519'
logcat -b kernel -d 2>/dev/null | grep -iE 'vtdr|visionox|ft3519|dsi_display' | head -5

echo
echo "=== dmesg if available ==="
dmesg 2>/dev/null | grep -icE 'vtdr|visionox|ft3519|dsi_display'
