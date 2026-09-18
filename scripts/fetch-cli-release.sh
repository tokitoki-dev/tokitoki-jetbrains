#!/bin/sh
# Downloads the pinned tokitoki-cli release for every bundled platform into
# <out>/<os>-<arch>/tokitoki[.exe], verifying each digest. Release builds only.
#
#   scripts/fetch-cli-release.sh build/cli

set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
OUT="${1:?usage: fetch-cli-release.sh <out-dir>}"

# The pin and its digests are reviewed together; no environment overrides.
. "$SCRIPT_DIR/cli-release-pins.sh"

if ! printf '%s\n' "$TOKITOKI_CLI_TAG" | grep -Eq '^v[0-9]+\.[0-9]+\.[0-9]+$'; then
  echo "error: invalid pinned CLI tag: $TOKITOKI_CLI_TAG" >&2
  exit 1
fi

BASE_URL="https://github.com/tokitoki-dev/tokitoki-cli/releases/download/$TOKITOKI_CLI_TAG"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/tokitoki-cli-release.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

sha256_check() {
  if command -v shasum >/dev/null 2>&1; then
    printf '%s  %s\n' "$2" "$1" | shasum -a 256 -c -
  else
    printf '%s  %s\n' "$2" "$1" | sha256sum -c -
  fi
}

fetch_and_verify() {
  asset="$1"
  expected="$2"
  platform="$3"
  name="$4"
  if ! printf '%s\n' "$expected" | grep -Eq '^[0-9a-f]{64}$'; then
    echo "error: invalid pinned CLI SHA-256 for $asset" >&2
    exit 1
  fi
  curl --fail --location --silent --show-error --retry 3 \
    --proto '=https' --proto-redir '=https' \
    --output "$TMP/$asset" "$BASE_URL/$asset"
  sha256_check "$TMP/$asset" "$expected"
  mkdir -p "$OUT/$platform"
  cp "$TMP/$asset" "$OUT/$platform/$name"
  chmod 755 "$OUT/$platform/$name"
}

rm -rf "$OUT"
fetch_and_verify tokitoki-darwin-amd64      "$TOKITOKI_CLI_DARWIN_AMD64_SHA256"  darwin-amd64  tokitoki
fetch_and_verify tokitoki-darwin-arm64      "$TOKITOKI_CLI_DARWIN_ARM64_SHA256"  darwin-arm64  tokitoki
fetch_and_verify tokitoki-linux-amd64       "$TOKITOKI_CLI_LINUX_AMD64_SHA256"   linux-amd64   tokitoki
fetch_and_verify tokitoki-linux-arm64       "$TOKITOKI_CLI_LINUX_ARM64_SHA256"   linux-arm64   tokitoki
fetch_and_verify tokitoki-windows-amd64.exe "$TOKITOKI_CLI_WINDOWS_AMD64_SHA256" windows-amd64 tokitoki.exe
fetch_and_verify tokitoki-windows-arm64.exe "$TOKITOKI_CLI_WINDOWS_ARM64_SHA256" windows-arm64 tokitoki.exe

# The host-native binary must report the pinned version: a second, independent
# check that the tag really is the release the digests describe.
case "$(uname -s)-$(uname -m)" in
  Darwin-arm64) native="$OUT/darwin-arm64/tokitoki" ;;
  Darwin-x86_64) native="$OUT/darwin-amd64/tokitoki" ;;
  Linux-x86_64) native="$OUT/linux-amd64/tokitoki" ;;
  Linux-aarch64 | Linux-arm64) native="$OUT/linux-arm64/tokitoki" ;;
  *) echo "error: unsupported build host: $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac
reported="$("$native" version)"
if [ "v$reported" != "$TOKITOKI_CLI_TAG" ]; then
  echo "error: pinned CLI reports version $reported, expected $TOKITOKI_CLI_TAG" >&2
  exit 1
fi
echo "Fetched Tokitoki CLI $reported for all bundled platforms."
