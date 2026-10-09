#!/usr/bin/env bash
#
# Vendors libsignal for Us2usKit without a Rust toolchain.
#
# Produces (git-ignored):
#   ios/Vendor/SignalFfi.xcframework   official iOS prebuild of libsignal_ffi.a
#                                      (device + merged arm64/x86_64 simulator slice)
#                                      with the SignalFfi headers and module map
#   ios/Vendor/LibSignalClient/        the LibSignalClient Swift sources of the SAME tag
#   ios/Vendor/libsignal.version       "<version> <tarball sha256>" stamp
#
# Environment overrides:
#   LIBSIGNAL_TARBALL    path to an already downloaded libsignal-client-ios-build-v<ver>.tar.gz
#   LIBSIGNAL_SRC        path to a libsignal checkout of the matching tag (skips git clone)
#   LIBSIGNAL_CACHE_DIR  download cache (default: ~/Library/Caches/org.signal.libsignal,
#                        the same cache the official CocoaPods integration uses)
#
# Flags:
#   --force              rebuild even if the version stamp already matches
#
# To upgrade libsignal, change LIBSIGNAL_VERSION and LIBSIGNAL_TARBALL_SHA256 below
# (see ios/README.md, "Upgrading libsignal").

set -euo pipefail

readonly LIBSIGNAL_VERSION="0.105.0"
# First field of the release asset libsignal-client-ios-build-v<ver>.tar.gz.sha256.
readonly LIBSIGNAL_TARBALL_SHA256="556a82414b545490085a0e21d94990788bdcbee4d769043f7889397872443faf"
readonly LIBSIGNAL_REPO="https://github.com/signalapp/libsignal.git"
readonly TARBALL_NAME="libsignal-client-ios-build-v${LIBSIGNAL_VERSION}.tar.gz"
readonly TARBALL_URL="https://build-artifacts.signal.org/libraries/${TARBALL_NAME}"

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly IOS_DIR="$(dirname "$SCRIPT_DIR")"
readonly VENDOR_DIR="$IOS_DIR/Vendor"
readonly STAMP_FILE="$VENDOR_DIR/libsignal.version"
readonly CACHE_DIR="${LIBSIGNAL_CACHE_DIR:-$HOME/Library/Caches/org.signal.libsignal}"

force=0
for arg in "$@"; do
  case "$arg" in
    --force) force=1 ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
    *) echo "error: unknown argument: $arg" >&2; exit 2 ;;
  esac
done

log() { printf '==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

sha256_of() { shasum -a 256 "$1" | awk '{print $1}'; }

expected_stamp="$LIBSIGNAL_VERSION $LIBSIGNAL_TARBALL_SHA256"
if [[ $force -eq 0 && -f "$STAMP_FILE" && "$(cat "$STAMP_FILE")" == "$expected_stamp" \
      && -d "$VENDOR_DIR/SignalFfi.xcframework" && -d "$VENDOR_DIR/LibSignalClient" ]]; then
  log "libsignal v$LIBSIGNAL_VERSION is already vendored (use --force to rebuild)"
  exit 0
fi

for tool in shasum tar xcrun plutil; do
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
  command -v git >/dev/null || die "'git' is required to clone $LIBSIGNAL_REPO"
  log "cloning libsignal v$LIBSIGNAL_VERSION"
  git clone --quiet --depth 1 --branch "v$LIBSIGNAL_VERSION" "$LIBSIGNAL_REPO" "$work/libsignal"
  src="$work/libsignal"
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
xcf="$work/SignalFfi.xcframework"
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

# --- 5. Swift sources ------------------------------------------------------------
log "copying LibSignalClient Swift sources"
cp -R "$src/swift/Sources/LibSignalClient" "$work/LibSignalClient"

# --- 6. Install ------------------------------------------------------------------
mkdir -p "$VENDOR_DIR"
rm -rf "$VENDOR_DIR/SignalFfi.xcframework" "$VENDOR_DIR/LibSignalClient"
mv "$work/SignalFfi.xcframework" "$VENDOR_DIR/SignalFfi.xcframework"
mv "$work/LibSignalClient" "$VENDOR_DIR/LibSignalClient"
# Licence texts the app must ship (AGPL-3.0-only plus libsignal's dependencies).
cp "$src/LICENSE" "$VENDOR_DIR/LICENSE.libsignal"
cp "$src/acknowledgments/acknowledgments-ios.plist" "$VENDOR_DIR/acknowledgments-ios.plist"
printf '%s\n' "$expected_stamp" > "$STAMP_FILE"

log "vendored libsignal v$LIBSIGNAL_VERSION into $VENDOR_DIR"
