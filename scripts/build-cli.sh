#!/bin/sh
# Compiles the sibling ../tokitoki-cli checkout for every bundled platform
# into <out>/<os>-<arch>/tokitoki[.exe]. Dev builds only.
#
#   scripts/build-cli.sh build/cli http://localhost:9093 .tokitoki-dev
#
# Built through the CLI's own Makefile: that Makefile decides what a locally
# built binary is — its version stamp and, more importantly, the data
# directory it owns. The server and data dir come from the plugin build so the
# binary and the plugin that looks for its shared copy are stamped from one
# source.
#
# Dev builds get a synthetic version that outranks every real release (9999
# major) and every earlier dev build (epoch-seconds patch), so the plugin's
# ordinary seeding comparison installs each runIde build into the shared dev
# slot — no dev-only code path in the plugin. The server never offers a
# release newer than 9999.x, so self-update leaves it alone.

set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
OUT="${1:?usage: build-cli.sh <out-dir> <server-url> <data-dir>}"
# make -C runs in the CLI checkout, so the output must not be relative to here.
case "$OUT" in /*) ;; *) OUT="$(pwd)/$OUT" ;; esac
SERVER_URL="${2:?server url}"
DATA_DIR="${3:?data dir}"
CLI_DIR="$SCRIPT_DIR/../../tokitoki-cli"

if [ ! -f "$CLI_DIR/go.mod" ]; then
  echo "error: tokitoki-cli checkout not found at $CLI_DIR" >&2
  exit 1
fi

VERSION="9999.0.$(date +%s)"
rm -rf "$OUT"

build() {
  goos="$1"; goarch="$2"; platform="$3"; name="$4"
  make -C "$CLI_DIR" -s agent-binary \
    GOOS="$goos" GOARCH="$goarch" \
    OUTPUT="$OUT/$platform/$name" \
    VERSION="$VERSION" SERVER_URL="$SERVER_URL" DATA_DIR="$DATA_DIR"
}

build darwin  amd64 darwin-amd64  tokitoki
build darwin  arm64 darwin-arm64  tokitoki
build linux   amd64 linux-amd64   tokitoki
build linux   arm64 linux-arm64   tokitoki
build windows amd64 windows-amd64 tokitoki.exe
build windows arm64 windows-arm64 tokitoki.exe
echo "Built Tokitoki CLI $VERSION for all bundled platforms (server $SERVER_URL, data dir ~/$DATA_DIR)."
