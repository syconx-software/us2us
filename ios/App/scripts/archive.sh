#!/usr/bin/env bash
#
# Archives the us2us app (Release, generic iOS device) and exports an App
# Store Connect .ipa — it does NOT upload. Signing is automatic with the Xcode
# account signed in on this Mac (team C8QW8MBYQX, SyconX LLC).
#
#   ios/App/scripts/archive.sh                          # → ios/App/build/release/
#   ios/App/scripts/archive.sh --out /path/to/dir       # archive + ipa there
#   ios/App/scripts/archive.sh --build-number 7         # override CURRENT_PROJECT_VERSION
#   ios/App/scripts/archive.sh --derived-data /path     # own DerivedData folder
#
# Then upload <out>/export/us2us.ipa with Xcode › Organizer or Transporter
# (README › Releasing). Requires the vendored libsignal
# (ios/scripts/fetch-libsignal.sh) and XcodeGen for the project.

set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly APP_DIR="$(dirname "$SCRIPT_DIR")"

log() { printf '==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

out_dir="$APP_DIR/build/release"
build_number=""
derived_data=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --out) [[ $# -ge 2 ]] || die "--out needs a directory"; out_dir="$2"; shift 2 ;;
    --build-number) [[ $# -ge 2 ]] || die "--build-number needs a number"; build_number="$2"; shift 2 ;;
    --derived-data) [[ $# -ge 2 ]] || die "--derived-data needs a directory"; derived_data="$2"; shift 2 ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done
if [[ -n "$build_number" && ! "$build_number" =~ ^[0-9]+(\.[0-9]+){0,2}$ ]]; then
  die "--build-number must be a number such as 7 or 1.0.7"
fi

archive_path="$out_dir/us2us.xcarchive"
export_path="$out_dir/export"
mkdir -p "$out_dir"
rm -rf "$archive_path" "$export_path"

archive_args=(
  -project "$APP_DIR/us2us.xcodeproj"
  -scheme us2us
  -configuration Release
  -destination "generic/platform=iOS"
  -archivePath "$archive_path"
  -allowProvisioningUpdates
)
[[ -n "$derived_data" ]] && archive_args+=(-derivedDataPath "$derived_data")
[[ -n "$build_number" ]] && archive_args+=("CURRENT_PROJECT_VERSION=$build_number")

log "archiving (Release, generic iOS device)"
xcodebuild "${archive_args[@]}" archive

log "exporting for App Store Connect (no upload)"
xcodebuild -exportArchive \
  -archivePath "$archive_path" \
  -exportOptionsPlist "$APP_DIR/ExportOptions.plist" \
  -exportPath "$export_path" \
  -allowProvisioningUpdates

info="$archive_path/Info.plist"
version="$(/usr/libexec/PlistBuddy -c 'Print :ApplicationProperties:CFBundleShortVersionString' "$info")"
build="$(/usr/libexec/PlistBuddy -c 'Print :ApplicationProperties:CFBundleVersion' "$info")"
log "done: us2us $version ($build)"
log "archive: $archive_path"
log "ipa:     $(ls "$export_path"/*.ipa)"
