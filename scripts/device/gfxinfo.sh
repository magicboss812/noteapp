#!/usr/bin/env bash
# Frame statistics for our package. 'reset' clears counters before a measured interaction.
source "$(dirname "$0")/_common.sh"
init_device
if [ "${1:-}" = "reset" ]; then dshell dumpsys gfxinfo "$PKG" reset >/dev/null; echo "gfxinfo reset"; exit 0; fi
dshell dumpsys gfxinfo "$PKG" | grep -E 'Total frames rendered|Janky frames|percentile|Number Missed Vsync|Number High input latency|Number Slow UI thread|Number Slow bitmap uploads|Number Slow issue draw commands|Number Frame deadline missed' || true
