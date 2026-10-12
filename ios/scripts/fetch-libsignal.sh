#!/usr/bin/env bash
#
# Vendors libsignal for Us2usKit without a Rust toolchain, and checks that the
# vendored copy is exactly the pinned one.
#
# Produces (git-ignored):
#   ios/Vendor/SignalFfi.xcframework   official iOS prebuild of libsignal_ffi.a
#                                      (device + merged arm64/x86_64 simulator slice)
#                                      with the SignalFfi headers and module map
#   ios/Vendor/LibSignalClient/        the LibSignalClient Swift sources of the SAME commit
#   ios/Vendor/libsignal.version       "<version> <tarball sha256>" stamp
#
# Pinned: the release version, the SHA-256 of Signal's prebuilt tarball, the
# git commit the release tag points to, and, committed next to this script,
# libsignal-vendor.sha256: the SHA-256 of every vendored file a device build
# compiles, links or bundles. ios/Vendor is not in git, so that manifest is what
# a release is checked against (ios/App/scripts/archive.sh runs --verify).
#
# Flags:
#   (none)               vendor if needed; an existing copy is kept only if it
#                        matches the manifest, otherwise it is rebuilt
#   --force              rebuild even if the existing copy matches
#   --verify             only check ios/Vendor against the pins and the manifest:
#                        downloads nothing, changes nothing, exits non-zero on any
#                        difference
#   --write-manifest     vendor if needed (with --force: always rebuild), then
#                        write libsignal-vendor.sha256 from ios/Vendor. Only when
#                        upgrading libsignal; review the diff before committing
#
# Environment overrides:
#   LIBSIGNAL_TARBALL     path to an already downloaded libsignal-client-ios-build-v<ver>.tar.gz
#   LIBSIGNAL_SRC         path to a libsignal git checkout at LIBSIGNAL_COMMIT with no local
#                         changes in the vendored files (skips git clone)
#   LIBSIGNAL_CACHE_DIR   download cache (default: ~/Library/Caches/org.signal.libsignal,
#                         the same cache the official CocoaPods integration uses)
#   LIBSIGNAL_VENDOR_DIR  vendor directory (default: ios/Vendor), e.g. to check a copy
#   LIBSIGNAL_MANIFEST    manifest file (default: ios/scripts/libsignal-vendor.sha256)
#
# To upgrade libsignal, change LIBSIGNAL_VERSION, LIBSIGNAL_TARBALL_SHA256 and
# LIBSIGNAL_COMMIT below, then run with --force --write-manifest (see
# ios/README.md, "Upgrading libsignal").

set -euo pipefail

readonly LIBSIGNAL_VERSION="0.105.0"
# First field of the release asset libsignal-client-ios-build-v<ver>.tar.gz.sha256.
readonly LIBSIGNAL_TARBALL_SHA256="556a82414b545490085a0e21d94990788bdcbee4d769043f7889397872443faf"
# The commit the tag v<ver> points to (`git ls-remote <repo> 'refs/tags/v<ver>^{}'`).
# The tag is unsigned and could be moved; a commit id cannot.
readonly LIBSIGNAL_COMMIT="4beb029d8a941f81e7d9c6d8af1ed25a677569a8"
readonly LIBSIGNAL_REPO="https://github.com/signalapp/libsignal.git"
readonly TARBALL_NAME="libsignal-client-ios-build-v${LIBSIGNAL_VERSION}.tar.gz"
readonly TARBALL_URL="https://build-artifacts.signal.org/libraries/${TARBALL_NAME}"

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly IOS_DIR="$(dirname "$SCRIPT_DIR")"
readonly VENDOR_DIR="${LIBSIGNAL_VENDOR_DIR:-$IOS_DIR/Vendor}"
readonly STAMP_FILE="$VENDOR_DIR/libsignal.version"
readonly MANIFEST="${LIBSIGNAL_MANIFEST:-$SCRIPT_DIR/libsignal-vendor.sha256}"
readonly CACHE_DIR="${LIBSIGNAL_CACHE_DIR:-$HOME/Library/Caches/org.signal.libsignal}"

force=0
verify_only=0
write_manifest=0
for arg in "$@"; do
  case "$arg" in
    --force) force=1 ;;
    --verify) verify_only=1 ;;
    --write-manifest) write_manifest=1 ;;
    -h|--help) sed -n '2,/^$/p' "$0"; exit 0 ;;
    *) echo "error: unknown argument: $arg" >&2; exit 2 ;;
  esac
done
if [[ $verify_only -eq 1 && ( $force -eq 1 || $write_manifest -eq 1 ) ]]; then
  echo "error: --verify cannot be combined with --force or --write-manifest" >&2
  exit 2
fi

log() { printf '==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

sha256_of() { shasum -a 256 "$1" | awk '{print $1}'; }

expected_stamp="$LIBSIGNAL_VERSION $LIBSIGNAL_TARBALL_SHA256"
# The first line of the manifest names the pins it was written for.
manifest_pins="# libsignal $LIBSIGNAL_VERSION commit $LIBSIGNAL_COMMIT tarball $LIBSIGNAL_TARBALL_SHA256"

# What the manifest covers, relative to a vendor directory: everything a device
# build compiles, links or bundles, and the stamp. The simulator archive is left
# out because `lipo` writes it here and its bytes may differ between Xcode
# versions; it never ships in an App Store build.
manifest_roots=(
  libsignal.version
  LICENSE.libsignal
  acknowledgments-ios.plist
  SignalFfi.xcframework/Info.plist
  SignalFfi.xcframework/ios-arm64
  SignalFfi.xcframework/ios-arm64_x86_64-simulator/Headers
  LibSignalClient
)

# Prints "<sha256>  <path>" for every covered file under $1, sorted bytewise.
# A covered path that does not exist prints a line no manifest contains, so it
# shows up as a difference. Fails if any file cannot be read.
hash_tree() { # <vendor dir>
  local dir="$1" root
  local present=()
  for root in "${manifest_roots[@]}"; do
    if [[ -e "$dir/$root" || -L "$dir/$root" ]]; then
      present+=("$root")
    else
      printf 'missing  %s\n' "$root"
    fi
  done
  [[ ${#present[@]} -gt 0 ]] || return 0
  (
    cd "$dir" || exit 1
    find "${present[@]}" \( -type f -o -type l \) ! -name .DS_Store -print0 \
      | LC_ALL=C sort -z \
      | xargs -0 shasum -a 256
  )
}

# The manifest must exist and be written for the pins above.
manifest_matches_pins() {
  if [[ ! -f "$MANIFEST" ]]; then
    printf 'error: no manifest at %s\n' "$MANIFEST" >&2
    return 1
  fi
  if [[ "$(head -n 1 "$MANIFEST")" != "$manifest_pins" ]]; then
    printf 'error: %s was not written for libsignal v%s, commit %s;\n' "$MANIFEST" "$LIBSIGNAL_VERSION" "$LIBSIGNAL_COMMIT" >&2
    printf '       when upgrading libsignal, run %s --force --write-manifest and review the diff\n' "$0" >&2
    return 1
  fi
}

# Compares the covered files under $1 with the manifest and prints what differs.
verify_tree() { # <vendor dir>
  local expected actual
  expected="$(sed -e '/^#/d' -e '/^[[:space:]]*$/d' "$MANIFEST")"
  if ! actual="$(hash_tree "$1")"; then
    printf 'error: could not read every vendored file under %s\n' "$1" >&2
    return 1
  fi
  [[ "$expected" == "$actual" ]] && return 0
  printf 'error: %s does not match %s (- manifest, + found):\n' "$1" "$MANIFEST" >&2
  diff -U0 <(printf '%s\n' "$expected") <(printf '%s\n' "$actual") \
    | sed -e '/^---/d' -e '/^+++/d' -e '/^@@/d' | head -n 40 >&2 || true
  return 1
}

write_manifest_from() { # <vendor dir>
  local dir="$1" root body
  for root in "${manifest_roots[@]}"; do
    [[ -e "$dir/$root" ]] || die "$dir/$root is missing; nothing to write a manifest from"
  done
  [[ -f "$dir/libsignal.version" && "$(cat "$dir/libsignal.version")" == "$expected_stamp" ]] \
    || die "$dir is not libsignal v$LIBSIGNAL_VERSION (stamp); run with --force --write-manifest"
  body="$(hash_tree "$dir")" || die "could not read every vendored file under $dir"
  {
    printf '%s\n' "$manifest_pins"
    printf '%s\n' "# SHA-256 of every file in ios/Vendor that a device build compiles, links or"
    printf '%s\n' "# bundles. Written by \`ios/scripts/fetch-libsignal.sh --write-manifest\` and"
    printf '%s\n' "# checked by \`--verify\` and before every release archive. Review the diff"
    printf '%s\n' "# whenever it changes: it should only ever change with a libsignal upgrade."
    printf '%s\n' "$body"
  } > "$MANIFEST.tmp"
  mv "$MANIFEST.tmp" "$MANIFEST"
}

# --- Verify only -------------------------------------------------------------------
if [[ $verify_only -eq 1 ]]; then
  [[ -d "$VENDOR_DIR" ]] || die "no vendored libsignal at $VENDOR_DIR (run $0)"
  manifest_matches_pins || exit 1
  [[ -f "$STAMP_FILE" && "$(cat "$STAMP_FILE")" == "$expected_stamp" ]] \
    || die "$VENDOR_DIR is not libsignal v$LIBSIGNAL_VERSION (stamp); run $0 --force"
  verify_tree "$VENDOR_DIR" || exit 1
  log "$VENDOR_DIR is libsignal v$LIBSIGNAL_VERSION ($LIBSIGNAL_COMMIT) and matches $(basename "$MANIFEST")"
  exit 0
fi

if [[ $write_manifest -eq 0 ]]; then
  manifest_matches_pins || exit 1
fi

if [[ $force -eq 0 && -f "$STAMP_FILE" && "$(cat "$STAMP_FILE")" == "$expected_stamp" \
      && -d "$VENDOR_DIR/SignalFfi.xcframework" && -d "$VENDOR_DIR/LibSignalClient" ]]; then
  if [[ $write_manifest -eq 1 ]]; then
    write_manifest_from "$VENDOR_DIR"
    log "wrote $MANIFEST from $VENDOR_DIR; review the diff before committing"
    exit 0
  fi
  if verify_tree "$VENDOR_DIR"; then
    log "libsignal v$LIBSIGNAL_VERSION is already vendored and matches $(basename "$MANIFEST") (use --force to rebuild)"
    exit 0
  fi
  log "the vendored copy differs from the manifest; rebuilding it"
fi

for tool in shasum tar xcrun plutil git; do
  command -v "$tool" >/dev/null || die "'$tool' is required"
done

work="$(mktemp -d "${TMPDIR:-/tmp}/us2us-libsignal.XXXXXX")"
trap 'rm -rf "$work"' EXIT

# --- 1. Prebuilt FFI tarball -------------------------------------------------
if [[ -n "${LIBSIGNAL_TARBALL:-}" ]]; then
  tarball="$LIBSIGNAL_TARBALL"
  [[ -f "$tarball" ]] || die "LIBSIGNAL_TARBALL does not exist: $tarball"
else
  mkdir -p "$CACHE_DIR"
  tarball="$CACHE_DIR/$TARBALL_NAME"
  if [[ ! -f "$tarball" || "$(sha256_of "$tarball")" != "$LIBSIGNAL_TARBALL_SHA256" ]]; then
    command -v curl >/dev/null || die "'curl' is required to download $TARBALL_URL"
    log "downloading $TARBALL_URL"
    curl --fail --location --retry 3 --output "$tarball.unverified" "$TARBALL_URL"
    mv "$tarball.unverified" "$tarball"
  fi
fi

log "verifying SHA-256 of $(basename "$tarball")"
actual_sha="$(sha256_of "$tarball")"
[[ "$actual_sha" == "$LIBSIGNAL_TARBALL_SHA256" ]] \
  || die "checksum mismatch for $tarball: expected $LIBSIGNAL_TARBALL_SHA256, got $actual_sha"

# --- 2. Matching libsignal source tree ---------------------------------------
if [[ -n "${LIBSIGNAL_SRC:-}" ]]; then
  src="$LIBSIGNAL_SRC"
  [[ -d "$src/swift/Sources/LibSignalClient" ]] || die "LIBSIGNAL_SRC is not a libsignal checkout: $src"
else
  log "cloning libsignal v$LIBSIGNAL_VERSION"
  git clone --quiet --depth 1 --branch "v$LIBSIGNAL_VERSION" "$LIBSIGNAL_REPO" "$work/libsignal"
  src="$work/libsignal"
fi

# Everything copied from the source tree is compiled into the app or shipped
# with it, so it must be exactly the pinned commit: the tag alone is not
# enough, and nothing may be modified, added or ignored in the copied paths.
head_commit="$(git -C "$src" rev-parse --verify HEAD 2>/dev/null)" \
  || die "source tree is not a git checkout: $src"
[[ "$head_commit" == "$LIBSIGNAL_COMMIT" ]] \
  || die "source tree is not libsignal commit $LIBSIGNAL_COMMIT (v$LIBSIGNAL_VERSION) but $head_commit"
copied_paths=(
  LibSignalClient.podspec
  LICENSE
  acknowledgments/acknowledgments-ios.plist
  swift/Sources/SignalFfi
  swift/Sources/LibSignalClient
)
changes="$(git --no-optional-locks -C "$src" status --porcelain --ignored --untracked-files=all -- "${copied_paths[@]}")" \
  || die "could not check the source tree for local changes: $src"
if [[ -n "$changes" ]]; then
  printf '%s\n' "$changes" >&2
  die "source tree has local changes in files that would be vendored: $src"
fi

# The Swift sources and the FFI binary must come from the same release; a
# mismatch compiles but crashes or misbehaves at run time.
podspec_version="$(sed -n "s/^[[:space:]]*s\.version[[:space:]]*=[[:space:]]*'\([^']*\)'.*/\1/p" "$src/LibSignalClient.podspec")"
[[ "$podspec_version" == "$LIBSIGNAL_VERSION" ]] \
  || die "source tree is libsignal $podspec_version, expected $LIBSIGNAL_VERSION"

# --- 3. Slices -----------------------------------------------------------------
log "extracting static libraries"
mkdir -p "$work/ffi" "$work/device" "$work/simulator" "$work/headers"
tar -x -f "$tarball" -C "$work/ffi"
readonly target_dir="$work/ffi/target"
for triple in aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios; do
  [[ -f "$target_dir/$triple/release/libsignal_ffi.a" ]] || die "tarball lacks the $triple slice"
done

cp "$target_dir/aarch64-apple-ios/release/libsignal_ffi.a" "$work/device/libsignal_ffi.a"
log "merging simulator slices (arm64 + x86_64)"
xcrun lipo -create \
  "$target_dir/aarch64-apple-ios-sim/release/libsignal_ffi.a" \
  "$target_dir/x86_64-apple-ios/release/libsignal_ffi.a" \
  -output "$work/simulator/libsignal_ffi.a"

# The module map declares `module SignalFfi`, which LibSignalClient imports.
cp "$src/swift/Sources/SignalFfi/module.modulemap" \
   "$src/swift/Sources/SignalFfi/signal_ffi.h" \
   "$src/swift/Sources/SignalFfi/signal_ffi_testing.h" \
   "$work/headers/"

# --- 4. XCFramework ------------------------------------------------------------
# `xcodebuild -create-xcframework` cannot be used: the archives contain LLVM
# bitcode members (build_ffi.sh compiles BoringSSL with -flto=full), and
# xcodebuild refuses to read their architecture. The layout is simple enough to
# assemble directly.
log "creating SignalFfi.xcframework"
stage="$work/stage"
mkdir -p "$stage"
xcf="$stage/SignalFfi.xcframework"
add_slice() { # <library identifier> <archive>
  mkdir -p "$xcf/$1"
  cp "$2" "$xcf/$1/libsignal_ffi.a"
  cp -R "$work/headers" "$xcf/$1/Headers"
}
add_slice ios-arm64 "$work/device/libsignal_ffi.a"
add_slice ios-arm64_x86_64-simulator "$work/simulator/libsignal_ffi.a"
cat > "$xcf/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>AvailableLibraries</key>
	<array>
		<dict>
			<key>BinaryPath</key>
			<string>libsignal_ffi.a</string>
			<key>HeadersPath</key>
			<string>Headers</string>
			<key>LibraryIdentifier</key>
			<string>ios-arm64</string>
			<key>LibraryPath</key>
			<string>libsignal_ffi.a</string>
			<key>SupportedArchitectures</key>
			<array>
				<string>arm64</string>
			</array>
			<key>SupportedPlatform</key>
			<string>ios</string>
		</dict>
		<dict>
			<key>BinaryPath</key>
			<string>libsignal_ffi.a</string>
			<key>HeadersPath</key>
			<string>Headers</string>
			<key>LibraryIdentifier</key>
			<string>ios-arm64_x86_64-simulator</string>
			<key>LibraryPath</key>
			<string>libsignal_ffi.a</string>
			<key>SupportedArchitectures</key>
			<array>
				<string>arm64</string>
				<string>x86_64</string>
			</array>
			<key>SupportedPlatform</key>
			<string>ios</string>
			<key>SupportedPlatformVariant</key>
			<string>simulator</string>
		</dict>
	</array>
	<key>CFBundlePackageType</key>
	<string>XFWK</string>
	<key>XCFrameworkFormatVersion</key>
	<string>1.0</string>
</dict>
</plist>
PLIST
plutil -lint "$xcf/Info.plist" >/dev/null || die "generated an invalid XCFramework Info.plist"

# --- 5. Swift sources and licences ----------------------------------------------
log "copying LibSignalClient Swift sources"
cp -R "$src/swift/Sources/LibSignalClient" "$stage/LibSignalClient"
# Licence texts the app must ship (AGPL-3.0-only plus libsignal's dependencies).
cp "$src/LICENSE" "$stage/LICENSE.libsignal"
cp "$src/acknowledgments/acknowledgments-ios.plist" "$stage/acknowledgments-ios.plist"
printf '%s\n' "$expected_stamp" > "$stage/libsignal.version"

# --- 6. Check, then install -----------------------------------------------------
# A rebuilt copy must match the committed manifest before it replaces anything.
if [[ $write_manifest -eq 0 ]]; then
  log "checking the new copy against $(basename "$MANIFEST")"
  verify_tree "$stage" || die "the rebuilt libsignal does not match $MANIFEST; $VENDOR_DIR was left unchanged"
fi

mkdir -p "$VENDOR_DIR"
# Without the stamp, an interrupted install is never taken for a complete one.
rm -f "$STAMP_FILE"
rm -rf "$VENDOR_DIR/SignalFfi.xcframework" "$VENDOR_DIR/LibSignalClient"
mv "$stage/SignalFfi.xcframework" "$stage/LibSignalClient" "$VENDOR_DIR/"
mv "$stage/LICENSE.libsignal" "$stage/acknowledgments-ios.plist" "$VENDOR_DIR/"
mv "$stage/libsignal.version" "$STAMP_FILE"

log "vendored libsignal v$LIBSIGNAL_VERSION ($LIBSIGNAL_COMMIT) into $VENDOR_DIR"

if [[ $write_manifest -eq 1 ]]; then
  write_manifest_from "$VENDOR_DIR"
  log "wrote $MANIFEST; review the diff before committing"
fi
