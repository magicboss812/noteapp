#!/usr/bin/env bash
# Read-only device facts for docs/notes/device.md.
source "$(dirname "$0")/_common.sh"
init_device
echo "serial=$SERIAL"
for p in ro.product.manufacturer ro.product.model ro.product.device ro.build.version.release ro.build.version.sdk ro.mi.os.version.name ro.mi.os.version.incremental ro.build.version.incremental; do
  printf '%s=%s\n' "$p" "$(dshell getprop "$p")"
done
echo "wm: $(dshell wm size | tr '\n' ' ') $(dshell wm density | tr '\n' ' ')"
echo "rotation: $(dshell dumpsys input | grep -m1 -Eo 'SurfaceOrientation: [0-9]' || echo unknown)"
echo "display modes (fps):"
dshell dumpsys display | grep -Eo 'fps=[0-9.]+' | sort -u | head -n 10 | tr '\n' ' '; echo
echo "stylus-capable input devices:"
dshell dumpsys input | grep -E '^ *[0-9]+: |Sources:|Name:' | grep -iE -B1 -A1 'stylus|pen' | head -n 30 || true
echo "installed ($PKG): $(dshell pm list packages "$PKG" | tr '\n' ' ')"
