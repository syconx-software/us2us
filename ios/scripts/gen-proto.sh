#!/usr/bin/env bash
#
# Regenerates the committed Swift sources derived from spec/:
#
#   spec/proto/brp.proto           -> Us2usKit/Sources/Us2usKit/Proto/brp.pb.swift
#   spec/common-secret-codes.txt   -> Us2usKit/Sources/Us2usKit/Resources/common-secret-codes.txt
#
# The secret-code list is copied (not symlinked) because SwiftPM resources must
# live inside the package; a unit test fails if the copy drifts from spec/.
#
# Tools (first match wins):
#   protoc            $PROTOC, then `protoc` on PATH, then protoc $PROTOC_VERSION
#                     downloaded from Maven Central into the cache dir
#   protoc-gen-swift  $PROTOC_GEN_SWIFT, then `protoc-gen-swift` on PATH, then built
#                     from apple/swift-protobuf at the version pinned in Package.resolved
#                     (generator and runtime versions must match)
#
# Cache dir: $US2US_TOOLS_CACHE (default ~/Library/Caches/us2us-tools).

set -euo pipefail

readonly PROTOC_VERSION="4.36.2"   # Maven version; reports itself as "libprotoc 36.2"
readonly PROTOC_SHA1="c1be31be420612fe3418ae88a1730426238b8ec5"   # protoc-4.36.2-osx-aarch_64.exe

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly IOS_DIR="$(dirname "$SCRIPT_DIR")"
readonly REPO_DIR="$(dirname "$IOS_DIR")"
readonly PACKAGE_DIR="$IOS_DIR/Us2usKit"
readonly SOURCES_DIR="$PACKAGE_DIR/Sources/Us2usKit"
readonly CACHE_DIR="${US2US_TOOLS_CACHE:-$HOME/Library/Caches/us2us-tools}"

log() { printf '==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

swift_protobuf_version() {
  python3 - "$PACKAGE_DIR/Package.resolved" <<'PY'
import json, sys
pins = json.load(open(sys.argv[1]))["pins"]
print(next(p["state"]["version"] for p in pins if p["identity"] == "swift-protobuf"))
PY
}

find_protoc() {
  if [[ -n "${PROTOC:-}" ]]; then echo "$PROTOC"; return; fi
  if command -v protoc >/dev/null; then command -v protoc; return; fi
  local dest="$CACHE_DIR/protoc-$PROTOC_VERSION"
  if [[ ! -x "$dest" ]]; then
    [[ "$(uname -m)" == "arm64" ]] || die "no protoc found; install it (brew install protobuf) or set PROTOC"
    log "downloading protoc $PROTOC_VERSION from Maven Central" >&2
    mkdir -p "$CACHE_DIR"
    python3 - "$PROTOC_VERSION" "$PROTOC_SHA1" "$dest" <<'PY'
import hashlib, sys, urllib.request
version, sha1, dest = sys.argv[1:]
url = (f"https://repo1.maven.org/maven2/com/google/protobuf/protoc/{version}/"
       f"protoc-{version}-osx-aarch_64.exe")
data = urllib.request.urlopen(url, timeout=120).read()
if hashlib.sha1(data).hexdigest() != sha1:
    sys.exit(f"checksum mismatch for {url}")
open(dest, "wb").write(data)
PY
    chmod +x "$dest"
  fi
  echo "$dest"
}

find_protoc_gen_swift() {
  if [[ -n "${PROTOC_GEN_SWIFT:-}" ]]; then echo "$PROTOC_GEN_SWIFT"; return; fi
  if command -v protoc-gen-swift >/dev/null; then command -v protoc-gen-swift; return; fi
  local version checkout
  version="$(swift_protobuf_version)"
  checkout="$CACHE_DIR/swift-protobuf-$version"
  if [[ ! -x "$checkout/.build/release/protoc-gen-swift" ]]; then
    log "building protoc-gen-swift $version" >&2
    mkdir -p "$CACHE_DIR"
    [[ -d "$checkout" ]] || git clone --quiet --depth 1 --branch "$version" \
      https://github.com/apple/swift-protobuf.git "$checkout"
    (cd "$checkout" && swift build -c release --product protoc-gen-swift >&2)
  fi
  echo "$checkout/.build/release/protoc-gen-swift"
}

protoc_bin="$(find_protoc)"
plugin_bin="$(find_protoc_gen_swift)"
log "protoc: $("$protoc_bin" --version), protoc-gen-swift: $("$plugin_bin" --version)"

mkdir -p "$SOURCES_DIR/Proto" "$SOURCES_DIR/Resources"
"$protoc_bin" \
  --plugin=protoc-gen-swift="$plugin_bin" \
  --proto_path="$REPO_DIR/spec/proto" \
  --swift_out="$SOURCES_DIR/Proto" \
  --swift_opt=Visibility=Public \
  "$REPO_DIR/spec/proto/brp.proto"
log "generated Proto/brp.pb.swift"

cp "$REPO_DIR/spec/common-secret-codes.txt" "$SOURCES_DIR/Resources/common-secret-codes.txt"
log "copied Resources/common-secret-codes.txt"
