#!/usr/bin/env bash
source "$(dirname "$0")/_common.sh"
init_device
dshell am force-stop "$PKG" && echo "stopped $PKG"
