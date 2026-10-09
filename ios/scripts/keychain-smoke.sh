#!/usr/bin/env bash
#
# Runs KeychainDeviceSecretProvider against the real Simulator keychain.
#
# `xcodebuild test` cannot cover it: hostless package tests run in Apple's
# xctest runner, which has no keychain entitlement (errSecMissingEntitlement,
# -34018), so KeychainDeviceSecretTests is skipped there. This script compiles
# the provider's source with keychain-smoke/main.swift into a minimal app whose
# binary carries a keychain entitlement, installs it on a Simulator, runs it
# and prints the result. Exit status 0 means every check passed.
#
# Usage: ios/scripts/keychain-smoke.sh [simulator name]   (default "iPhone 17")

set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly SOURCES="$(dirname "$SCRIPT_DIR")/Us2usKit/Sources/Us2usKit"
readonly SIMULATOR_NAME="${1:-iPhone 17}"
readonly BUNDLE_ID="com.syconx.us2us.keychain-smoke"

log() { printf '==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

# Newest available runtime that has a device with this name.
udid="$(xcrun simctl list devices available -j | python3 -c '
import json, re, sys
name = sys.argv[1]
devices = json.load(sys.stdin)["devices"]
version = lambda runtime: [int(part) for part in re.findall(r"\d+", runtime)]
matches = [(version(runtime), d["udid"]) for runtime, ds in devices.items() for d in ds if d["name"] == name]
print(max(matches)[1] if matches else "")
' "$SIMULATOR_NAME")"
[[ -n "$udid" ]] || die "no available simulator named '$SIMULATOR_NAME'"

work="$(mktemp -d "${TMPDIR:-/tmp}/us2us-keychain-smoke.XXXXXX")"
trap 'xcrun simctl uninstall "$udid" "$BUNDLE_ID" >/dev/null 2>&1 || true; rm -rf "$work"' EXIT

log "booting $SIMULATOR_NAME ($udid)"
xcrun simctl bootstatus "$udid" -b >/dev/null

app="$work/KeychainSmoke.app"
mkdir -p "$app"
cat > "$work/entitlements.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>application-identifier</key>
	<string>SIMULATOR.$BUNDLE_ID</string>
	<key>keychain-access-groups</key>
	<array>
		<string>SIMULATOR.$BUNDLE_ID</string>
	</array>
</dict>
</plist>
PLIST
cat > "$app/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>CFBundleIdentifier</key>
	<string>$BUNDLE_ID</string>
	<key>CFBundleExecutable</key>
	<string>KeychainSmoke</string>
	<key>CFBundleName</key>
	<string>KeychainSmoke</string>
	<key>CFBundlePackageType</key>
	<string>APPL</string>
	<key>CFBundleVersion</key>
	<string>1</string>
	<key>CFBundleShortVersionString</key>
	<string>1.0</string>
	<key>MinimumOSVersion</key>
	<string>17.0</string>
	<key>CFBundleSupportedPlatforms</key>
	<array>
		<string>iPhoneSimulator</string>
	</array>
</dict>
</plist>
PLIST

log "compiling the provider for the Simulator"
# Simulator binaries are ad-hoc signed; like Xcode's "Sign to Run Locally", the
# entitlements go into the __TEXT,__entitlements section, not the signature.
xcrun --sdk iphonesimulator swiftc -swift-version 6 -O \
  -target "$(uname -m)-apple-ios17.0-simulator" \
  -o "$app/KeychainSmoke" \
  "$SCRIPT_DIR/keychain-smoke/main.swift" \
  "$SOURCES/Vault/DeviceSecretProvider.swift" \
  "$SOURCES/Support/Security.swift" \
  -Xlinker -sectcreate -Xlinker __TEXT -Xlinker __entitlements -Xlinker "$work/entitlements.plist"
codesign --force --sign - "$app" >/dev/null 2>&1

log "running on the Simulator"
xcrun simctl install "$udid" "$app"
# The process exits right away, so `launch` may report a missing process handle.
xcrun simctl launch "$udid" "$BUNDLE_ID" >/dev/null 2>&1 || true
result="$(xcrun simctl get_app_container "$udid" "$BUNDLE_ID" data)/Documents/result.txt"
for _ in $(seq 1 100); do
  [[ -f "$result" ]] && break
  sleep 0.2
done
[[ -f "$result" ]] || die "the smoke app wrote no result"
cat "$result"
[[ "$(tail -n 1 "$result")" == "PASS" ]]
