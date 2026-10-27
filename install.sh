#!/usr/bin/env bash
# Builds jblaze and links the launcher into a bin dir on your PATH (default ~/.local/bin).
# usage: ./install.sh [bin dir]
set -euo pipefail
DIR=$(cd "$(dirname "$0")" && pwd)
BIN=${1:-$HOME/.local/bin}
(cd "$DIR" && ./gradlew -q jar)
mkdir -p "$BIN"
ln -sf "$DIR/jblaze" "$BIN/jblaze"
echo "installed: $BIN/jblaze -> $DIR/jblaze"
case ":$PATH:" in *":$BIN:"*) ;; *) echo "note: $BIN is not on your PATH" ;; esac
