#!/usr/bin/env bash
# Clears app-private data of the debug package only.
source "$(dirname "$0")/_common.sh"
init_device
dshell pm clear "$PKG"
