#!/usr/bin/env bash
# Captures the iOS app in every harness state (-U2Route, ios/App/README.md › Launch arguments)
# as reference PNGs for the states the design does not draw:
#   android/build/ios-reference/<theme>-<lang>/<feature>_<target>_<state>.png
# named like the Android JVM shots (ShotPaths.fileName: ':' and '/' become '_'), so
# scripts/compare_shots.py pairs them one to one.
#
# Usage:  android/scripts/capture_ios_refs.sh [--no-build] [--only <substring>]
#   SIM=us2us-dev            simulator name or UDID (an iPhone 17 Pro, 402 × 874 pt)
#   DERIVED=/tmp/us2us-dd    DerivedData to build into / reuse
#   SETTLE=2.5               seconds to wait after launch before the screenshot
#
# Every launch passes -U2Fixtures YES -U2FrozenClock YES and the status bar is frozen at 9:41,
# as the design's references are. Turkish in dark and light for every state; English in dark and
# light for the 9.x set (chat list, chat, requests, settings).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$(dirname "$SCRIPT_DIR")"
REPO_DIR="$(dirname "$ANDROID_DIR")"
OUT_DIR="$ANDROID_DIR/build/ios-reference"
SIM="${SIM:-us2us-dev}"
DERIVED="${DERIVED:-/tmp/us2us-dd}"
SETTLE="${SETTLE:-2.5}"
BUNDLE_ID="com.syconx.us2us"
BUILD=1
ONLY=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-build) BUILD=0; shift ;;
    --only) ONLY="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

ROUTES=(
  # Onboarding
  onboarding:1.1 onboarding:1.2 onboarding:1.3
  onboarding:2.1 onboarding:2.1-blurred onboarding:2.1-rules onboarding:2.1-empty onboarding:2.1-checking
  onboarding:2.1-taken onboarding:2.1-short onboarding:2.1-invalid onboarding:2.1-turkish onboarding:2.1-firstLetter
  onboarding:2.2 onboarding:2.2-empty onboarding:2.2-confirm onboarding:2.2-weak onboarding:2.2-mismatch
  onboarding:2.3 onboarding:2.3-unchecked onboarding:2.3-error onboarding:2.3-kept onboarding:2.3-taken
  onboarding:7.3 onboarding:restore-2.2 onboarding:restore-2.3 onboarding:restore-2.3-gone
  onboarding:restore-2.3-damaged onboarding:restore-2.3-lock onboarding:restore-2.3-toLock
  # Lock
  lock:faceid lock:touchid lock:code lock:typing lock:wrong lock:lastchance lock:throttled
  lock:unavailable lock:unusable lock:wiped lock:forgot lock:erase lock:restore lock:cover lock:switcher
  # Chats, new chat, requests, invite
  chats:4.1 chats:4.1-connecting chats:4.1-closed chats:4.1-empty chats:4.1-qr chats:4.1-mycode
  chats:4.2 chats:4.2-keyboard chats:4.2-empty chats:4.2-searching chats:4.2-notfound chats:4.2-existing
  chats:4.2-self chats:4.2-mycode chats:4.3 chats:4.3-empty chats:4.3-block chats:4.4 chats:4.4-media chats:4.4-link
  chats:4.4-report chats:4.1-blocked
  newChat newChat:deniz requests request:kuzey_dev request:n_aksoy request:hediye_kazan
  invite invite:edit invite:edited invite:copied invite:sent invite:link invite:link-unknown
  # Conversation: deniz
  conversation:deniz/5.1 conversation:deniz/5.1-failed conversation:deniz/5.1-reply conversation:deniz/5.1-attach
  conversation:deniz/5.1-stickers conversation:deniz/5.1-blocked conversation:deniz/5.1-viewer conversation:deniz/5.1-viewer-zoomed
  conversation:deniz/5.3 conversation:deniz/5.3-incoming conversation:deniz/5.3-confirm
  conversation:deniz/5.4 conversation:deniz/5.4-pending conversation:deniz/5.4-failed
  conversation:deniz/5.5 conversation:deniz/5.6 conversation:deniz/5.6-clean conversation:deniz/5.6-captured
  conversation:deniz/5.1-save-notice
  conversation:deniz/5.1-voice-hold conversation:deniz/5.1-voice-locked conversation:deniz/5.1-voice-review
  conversation:deniz/5.1-voice-hint conversation:deniz/5.1-voice-permission conversation:deniz/5.1-voice-unplayed
  # Conversation: mert_k
  conversation:mert_k/5.2 conversation:mert_k/5.2-ended conversation:mert_k/5.2-opened conversation:mert_k/5.2-player
  conversation:mert_k/5.2-player-loading conversation:mert_k/5.2-file conversation:mert_k/5.2-file-actions
  conversation:mert_k/5.2-remote conversation:mert_k/5.2-downloading conversation:mert_k/5.2-unavailable
  conversation:mert_k/5.2-preparing conversation:mert_k/5.2-prepare-failed conversation:mert_k/5.2-file-disappearing
  conversation:mert_k/5.2-saved conversation:mert_k/5.4-saved conversation:mert_k/5.2-place conversation:mert_k/5.2-map
  conversation:mert_k/5.2-map-peer conversation:mert_k/5.2-map-ask conversation:mert_k/5.2-map-denied conversation:mert_k/5.5
  # Safety number, contact screen, QR
  safety:deniz safety:deniz/photos safety:deniz/documents safety:deniz/safety-verified safety:deniz/changed
  safety:deniz/menu safety:deniz/block safety:deniz/blocked safety:deniz/report safety:deniz/report-address
  safety:deniz/confirm-accept safety:deniz/confirm safety:deniz/confirm-remove
  safety:deniz/scan safety:deniz/mismatch safety:deniz/mycode safety:deniz/denied safety:deniz/torch
  safety:deniz/verified safety:deniz/verified-manual
  safety:deniz/contact-scan safety:deniz/contact-unknown safety:deniz/contact-own safety:deniz/contact-mycode
  safety:deniz/contact-verified
  # Settings
  settings:7.1 settings:7.1-end settings:7.1-access settings:7.1-access-checking settings:7.1-access-unanswered
  settings:qr settings:erase settings:erase-failed settings:avatar settings:avatar-crop
  settings:presence settings:autolock settings:ghost settings:disappearing settings:theme settings:language
  settings:7.2 settings:7.2-empty settings:7.2-weak settings:7.2-generated settings:7.2-revealed settings:7.2-copied
  settings:7.2-confirm settings:7.2-mismatch settings:7.2-exporting
  settings:7.3 settings:7.3-pick settings:7.3-password settings:7.3-wrong settings:7.3-done settings:7.3-cancel
  settings:code settings:code-wrong settings:code-new settings:code-weak settings:code-confirm settings:code-mismatch
  settings:faceid settings:about backup
)

# The English set (section 09 of the design and its neighbours).
EN_ROUTES=(chats:4.1 chats:4.2 chats:4.3 conversation:deniz/5.1 conversation:deniz/5.3 conversation:mert_k/5.2 settings:7.1 lock:code)

# Gallery pages: -U2Gallery <value> → file name.
GALLERIES=("YES:gallery" "stickers:gallery_stickers" "avatars:gallery_avatars")

file_name() {
  # ShotPaths.fileName: ':' and '/' become '_', anything else outside [A-Za-z0-9._+-] too.
  printf '%s' "$1" | tr ':/' '__' | sed -E 's/[^A-Za-z0-9._+-]/_/g'
}

if [[ $BUILD -eq 1 ]]; then
  xcodebuild -project "$REPO_DIR/ios/App/us2us.xcodeproj" -scheme us2us -configuration Debug \
    -destination "platform=iOS Simulator,name=$SIM" -derivedDataPath "$DERIVED" build -quiet
fi
APP="$(find "$DERIVED/Build/Products/Debug-iphonesimulator" -maxdepth 1 -name 'us2us.app' -print -quit)"
[[ -n "$APP" ]] || { echo "no us2us.app under $DERIVED (build first or drop --no-build)" >&2; exit 1; }

xcrun simctl boot "$SIM" 2>/dev/null || true
xcrun simctl bootstatus "$SIM" -b >/dev/null
xcrun simctl install "$SIM" "$APP"
xcrun simctl status_bar "$SIM" override --time 9:41 --batteryState charged --batteryLevel 100 \
  --cellularMode active --cellularBars 4 --wifiBars 3 --dataNetwork wifi

capture() { # <name> <theme> <lang> <launch args…>
  local name="$1" theme="$2" lang="$3"
  shift 3
  local dir="$OUT_DIR/$theme-$lang"
  mkdir -p "$dir"
  xcrun simctl terminate "$SIM" "$BUNDLE_ID" >/dev/null 2>&1 || true
  xcrun simctl launch "$SIM" "$BUNDLE_ID" -U2Fixtures YES -U2FrozenClock YES -U2Theme "$theme" -U2Language "$lang" "$@" >/dev/null
  sleep "$SETTLE"
  xcrun simctl io "$SIM" screenshot --type=png "$dir/$name.png" >/dev/null 2>&1
  echo "$theme-$lang/$name.png"
}

count=0
for theme in dark light; do
  for route in "${ROUTES[@]}"; do
    [[ -z "$ONLY" || "$route" == *"$ONLY"* ]] || continue
    capture "$(file_name "$route")" "$theme" tr -U2Route "$route"
    count=$((count + 1))
  done
  for route in "${EN_ROUTES[@]}"; do
    [[ -z "$ONLY" || "$route" == *"$ONLY"* ]] || continue
    capture "$(file_name "$route")" "$theme" en -U2Route "$route"
    count=$((count + 1))
  done
  for entry in "${GALLERIES[@]}"; do
    value="${entry%%:*}"
    name="${entry#*:}"
    [[ -z "$ONLY" || "$name" == *"$ONLY"* ]] || continue
    capture "$name" "$theme" tr -U2Gallery "$value"
    count=$((count + 1))
  done
done

xcrun simctl terminate "$SIM" "$BUNDLE_ID" >/dev/null 2>&1 || true
xcrun simctl status_bar "$SIM" clear
echo "captured $count screens into ${OUT_DIR#"$REPO_DIR"/}"
