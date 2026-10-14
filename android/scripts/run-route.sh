#!/usr/bin/env bash
# Opens one screen of the debug app on the emulator, in one drawn state, and captures it — the
# Android side of `xcrun simctl launch … -U2Route <route>`.
#
#   scripts/run-route.sh <route> [theme] [lang] [-- extra am-start options]
#   scripts/run-route.sh conversation:deniz/5.3
#   scripts/run-route.sh lock:wrong light tr
#   scripts/run-route.sh chats:4.1 dark en -- --es U2GhostUrgent YES
#   NAME=3.2-kod-yanlis scripts/run-route.sh lock:wrong     # name the shot after the drawn screen
#   INSTALL=1 scripts/run-route.sh chats                    # install app-debug.apk first
#   scripts/run-route.sh - dark tr                          # no route: the app's own start
#
# The app gets the design's launch arguments as extras: the fixtures, the frozen clock, the design
# insets and U2AllowCapture (debug builds only: lifts FLAG_SECURE so screencap is not black).
# The shot goes to android/build/shots/device/<theme>-<lang>/<name>.png (name: the route with
# ':' and '/' as '_', or $NAME) with a .json sidecar; scripts/compare_shots.py --source device diffs them.
#
# Environment: ANDROID_HOME, ANDROID_SERIAL (default: the running emulator), SETTLE seconds to wait
# after the launch before capturing (default 2), INSTALL=1, NAME.
set -euo pipefail

usage() { sed -n '2,19p' "$0"; }
[[ $# -ge 1 && "$1" != "-h" && "$1" != "--help" ]] || { usage; exit 2; }

route="$1"; shift
theme="dark"
lang="tr"
if [[ $# -gt 0 && "$1" != "--" ]]; then theme="$1"; shift; fi
if [[ $# -gt 0 && "$1" != "--" ]]; then lang="$1"; shift; fi
[[ $# -gt 0 && "$1" == "--" ]] && shift
extra=("$@")

case "$theme" in dark|light|system) ;; *) echo "theme must be dark, light or system" >&2; exit 2 ;; esac
case "$lang" in tr|en|system) ;; *) echo "lang must be tr, en or system" >&2; exit 2 ;; esac

ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
ANDROID_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PACKAGE="com.syconx.us2us"
ACTIVITY="$PACKAGE/.app.MainActivity"
APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
SETTLE="${SETTLE:-2}"

serial="${ANDROID_SERIAL:-$("$ADB" devices | awk '/^emulator-/ && $2 == "device" {print $1; exit}')}"
[[ -n "$serial" ]] || { echo "error: no emulator running — scripts/emulator-boot.sh" >&2; exit 1; }
adb() { "$ADB" -s "$serial" "$@"; }

if [[ "${INSTALL:-0}" == "1" ]]; then
  [[ -f "$APK" ]] || { echo "error: no $APK — ./gradlew :app:assembleDebug" >&2; exit 1; }
  echo "installing $(basename "$APK") on $serial"
  adb install -r -t "$APK" >/dev/null
fi
adb shell pm path "$PACKAGE" >/dev/null 2>&1 || { echo "error: $PACKAGE is not installed (INSTALL=1)" >&2; exit 1; }

args=(-S -W -n "$ACTIVITY")
if [[ "$route" != "-" ]]; then args+=(--es U2Route "$route"); fi
args+=(--es U2Theme "$theme" --es U2Language "$lang"
  --ez U2Fixtures true --ez U2FrozenClock true --ez U2DesignInsets true --ez U2AllowCapture true)
args+=("${extra[@]+"${extra[@]}"}")

adb shell am start "${args[@]}" | sed 's/^/  /'
sleep "$SETTLE"

if [[ -n "${NAME:-}" ]]; then
  name="$NAME"
elif [[ "$route" == "-" ]]; then
  name="start"
else
  name="$route"
fi
name="$(printf '%s' "$name" | tr ':/' '__' | LC_ALL=C sed 's/[^A-Za-z0-9._+-]/_/g')"
variant="$theme-$lang"
out_dir="$ANDROID_DIR/build/shots/device/$variant"
mkdir -p "$out_dir"
out="$out_dir/$name.png"
adb exec-out screencap -p >"$out"

# A drawn screen's name doubles as its design reference (NAME=3.2-kod-yanlis).
design="null"
if [[ -n "${NAME:-}" ]]; then
  for candidate in "$ANDROID_DIR/../ios/design/reference/"{,light/,urgent/}"$NAME"*.png; do
    if [[ -f "$candidate" ]]; then design="\"$NAME\""; break; fi
  done
fi
cat >"$out_dir/$name.json" <<EOF
{
  "name": "$( [[ "$route" == "-" ]] && echo start || echo "$route" )",
  "theme": "$theme",
  "lang": "$lang",
  "design": $design,
  "designInsets": true,
  "frozenClock": true,
  "module": "device",
  "test": "run-route.sh $serial"
}
EOF

size="$(python3 -c "import struct,sys; d=open(sys.argv[1],'rb').read(24); print('%dx%d' % struct.unpack('>II', d[16:24]))" "$out" 2>/dev/null || echo "?")"
echo "$out ($size)"
