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
#
# Nothing is archived unless the vendored libsignal matches the committed
# manifest (fetch-libsignal.sh --verify), and Swift packages resolve only to
# the versions in the committed Package.resolved. <out>/provenance.txt records
# what went into the build; keep it with the archive.

set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly APP_DIR="$(dirname "$SCRIPT_DIR")"
readonly IOS_DIR="$(dirname "$APP_DIR")"
readonly REPO_DIR="$(dirname "$IOS_DIR")"
readonly FETCH_LIBSIGNAL="$IOS_DIR/scripts/fetch-libsignal.sh"
readonly LIBSIGNAL_MANIFEST_FILE="$IOS_DIR/scripts/libsignal-vendor.sha256"
readonly PACKAGE_RESOLVED="$APP_DIR/us2us.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved"

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
    -h|--help) sed -n '2,/^$/p' "$0"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done
if [[ -n "$build_number" && ! "$build_number" =~ ^[0-9]+(\.[0-9]+){0,2}$ ]]; then
  die "--build-number must be a number such as 7 or 1.0.7"
fi

# --- Release inputs ----------------------------------------------------------------
# ios/Vendor is not in git: archive only what the committed manifest pins. The
# build reads it through the committed symlink ios/Us2usKit/Vendor (and the
# licence build phase through ../Vendor), so that must lead to the checked copy.
vendor_dir="$(cd "$IOS_DIR/Vendor" 2>/dev/null && pwd -P)" || vendor_dir=""
kit_vendor_dir="$(cd "$IOS_DIR/Us2usKit/Vendor" 2>/dev/null && pwd -P)" || kit_vendor_dir=""
[[ -n "$vendor_dir" && "$kit_vendor_dir" == "$vendor_dir" ]] \
  || die "ios/Us2usKit/Vendor does not lead to the vendored libsignal in ios/Vendor (run ios/scripts/fetch-libsignal.sh)"
log "checking the vendored libsignal against $(basename "$LIBSIGNAL_MANIFEST_FILE")"
# Its overrides are for checking copies; a release checks what Xcode builds.
env -u LIBSIGNAL_VENDOR_DIR -u LIBSIGNAL_MANIFEST "$FETCH_LIBSIGNAL" --verify \
  || die "the vendored libsignal does not match ios/scripts/libsignal-vendor.sha256; run ios/scripts/fetch-libsignal.sh --force, then archive again"
[[ -f "$PACKAGE_RESOLVED" ]] || die "missing $PACKAGE_RESOLVED (the pinned Swift package versions)"

# Provenance, taken before the build so it describes what the build read.
archived_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
git_head="$(git -C "$REPO_DIR" rev-parse HEAD 2>/dev/null)" || git_head="unknown"
if [[ "$git_head" == "unknown" ]]; then
  git_tree="unknown"
else
  git_changes="$(git -C "$REPO_DIR" status --porcelain 2>/dev/null | wc -l | tr -d '[:space:]')" || git_changes="?"
  if [[ "$git_changes" == "0" ]]; then
    git_tree="clean"
  else
    git_tree="NOT clean: $git_changes changed or untracked paths (git status)"
  fi
fi
libsignal_stamp="$(cat "$vendor_dir/libsignal.version")"
libsignal_commit="$(sed -n 's/^readonly LIBSIGNAL_COMMIT="\([0-9a-f]*\)"$/\1/p' "$FETCH_LIBSIGNAL")"
manifest_sha="$(shasum -a 256 "$LIBSIGNAL_MANIFEST_FILE" | awk '{print $1}')"
resolved_origin="$(sed -n 's/^[[:space:]]*"originHash"[[:space:]]*:[[:space:]]*"\([0-9a-f]*\)".*$/\1/p' "$PACKAGE_RESOLVED")"
resolved_pins="$(awk -F'"' '/"identity"/ { id = $4 } /"revision"/ { rev = $4 } /"version" *:/ { if (id != "") { printf "  %s %s %s\n", id, $4, rev; id = "" } }' "$PACKAGE_RESOLVED")"
xcode_version="$(xcodebuild -version | tr '\n' ' ' | sed 's/ *$//')"

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
  # Swift packages only at the versions in the committed Package.resolved.
  -onlyUsePackageVersionsFromResolvedFile
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
ipa="$(ls "$export_path"/*.ipa)"
provenance="$out_dir/provenance.txt"
{
  printf 'us2us %s (%s)\n' "$version" "$build"
  printf 'archived:          %s\n' "$archived_at"
  printf 'git HEAD:          %s\n' "$git_head"
  printf 'git working tree:  %s\n' "$git_tree"
  printf 'libsignal:         %s (version, tarball SHA-256)\n' "$libsignal_stamp"
  printf 'libsignal commit:  %s\n' "$libsignal_commit"
  printf 'vendor manifest:   %s  ios/scripts/libsignal-vendor.sha256 (verified before the build)\n' "$manifest_sha"
  printf 'Package.resolved:  originHash %s\n' "$resolved_origin"
  printf '%s\n' "$resolved_pins"
  printf 'Xcode:             %s\n' "$xcode_version"
  printf 'ipa:               %s  %s\n' "$(shasum -a 256 "$ipa" | awk '{print $1}')" "$(basename "$ipa")"
} > "$provenance"

log "done: us2us $version ($build)"
log "archive:    $archive_path"
log "ipa:        $ipa"
log "provenance: $provenance (keep it with the archive)"
