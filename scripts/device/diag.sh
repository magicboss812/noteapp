#!/usr/bin/env bash
# Read-only diagnostics: layers (our SurfaceFlinger layers), refresh (active display mode), meminfo (our process).
source "$(dirname "$0")/_common.sh"
init_device
case "${1:-}" in
  layers)  dshell dumpsys SurfaceFlinger --list | grep -i "folio" || echo "no folio layers" ;;
  refresh) dshell dumpsys display | grep -E 'mActiveSfDisplayMode|mActiveRenderFrameRate|renderFrameRate|mRefreshRateSetting' | head -n 6 || true ;;
  meminfo) dshell dumpsys meminfo "$PKG" | grep -E 'TOTAL PSS|TOTAL RSS|Native Heap|Java Heap|Graphics|Private Other' | head -n 12 || true ;;
  *) die "usage: diag.sh layers|refresh|meminfo" 2 ;;
esac
