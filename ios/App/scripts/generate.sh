#!/usr/bin/env bash
#
# Regenerates ios/App/us2us.xcodeproj from ios/App/project.yml (XcodeGen).
#
# Sources/, Resources/ and Tests/* are synchronized folders, so adding or
# removing files there never needs a regeneration — run this only after
# editing project.yml.
#
#   ios/App/scripts/generate.sh            # generate the project
#   ios/App/scripts/generate.sh --tokens   # also regenerate the design tokens
#                                          # and icon assets from ios/design/
#
# Requires XcodeGen ≥ 2.45 (brew install xcodegen) and, for --tokens, python3.

set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly APP_DIR="$(dirname "$SCRIPT_DIR")"
readonly IOS_DIR="$(dirname "$APP_DIR")"

log() { printf '==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

with_tokens=0
for arg in "$@"; do
  case "$arg" in
    --tokens) with_tokens=1 ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *) die "unknown argument: $arg" ;;
  esac
done

xcodegen_bin="$(command -v xcodegen || true)"
[[ -z "$xcodegen_bin" && -x /opt/homebrew/bin/xcodegen ]] && xcodegen_bin=/opt/homebrew/bin/xcodegen
[[ -n "$xcodegen_bin" ]] || die "xcodegen not found (brew install xcodegen)"

if [[ ! -d "$IOS_DIR/Vendor/SignalFfi.xcframework" || ! -d "$IOS_DIR/Vendor/LibSignalClient" ]]; then
  printf 'warning: libsignal is not vendored yet — run ios/scripts/fetch-libsignal.sh before building\n' >&2
fi

if [[ $with_tokens -eq 1 ]]; then
  log "generating design tokens from ios/design/tokens.json"
  python3 "$SCRIPT_DIR/generate-tokens.py"
  log "generating icon assets from ios/design/icons"
  python3 "$SCRIPT_DIR/generate-icons.py"
fi

log "generating us2us.xcodeproj"
cd "$APP_DIR"
"$xcodegen_bin" generate --spec project.yml --project . --quiet
log "done: $APP_DIR/us2us.xcodeproj"
