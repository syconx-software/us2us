#!/usr/bin/env bash
# Boots the us2us36 emulator headless and returns once Android has finished booting; or stops it.
#
#   scripts/emulator-boot.sh           # boot (the first boot wipes data: the image has no userdata.img)
#   scripts/emulator-boot.sh --wipe    # boot with a factory reset
#   scripts/emulator-boot.sh --stop    # shut it down (adb emu kill)
#
# Only one emulator ever runs: if us2us36 is already up this only waits for it; any other emulator
# is an error. Window, transition and animator animations are turned off after boot.
# A fingerprint for the biometric prompt: adb -e emu finger touch 1.
#
# Environment: ANDROID_HOME (default ~/Library/Android/sdk), ANDROID_AVD_HOME (default ~/.android/avd),
# AVD_NAME (default us2us36), BOOT_TIMEOUT seconds (default 600).
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
AVD_NAME="${AVD_NAME:-us2us36}"
BOOT_TIMEOUT="${BOOT_TIMEOUT:-600}"
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
ANDROID_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="$ANDROID_DIR/build/emulator"

wipe=""
stop=0
for arg in "$@"; do
  case "$arg" in
    --wipe) wipe="-wipe-data" ;;
    --stop) stop=1 ;;
    -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

"$ADB" start-server >/dev/null 2>&1 || true

# The serial and AVD name of every running emulator: "emulator-5554 us2us36".
running() {
  local serial
  for serial in $("$ADB" devices | awk '/^emulator-/ {print $1}'); do
    echo "$serial $("$ADB" -s "$serial" emu avd name 2>/dev/null | head -1 | tr -d '\r')"
  done
}

if [[ $stop == 1 ]]; then
  serial="$(running | awk -v n="$AVD_NAME" '$2 == n {print $1; exit}')"
  if [[ -z "$serial" ]]; then
    echo "$AVD_NAME is not running"
    exit 0
  fi
  "$ADB" -s "$serial" emu kill >/dev/null
  for _ in $(seq 1 60); do
    "$ADB" devices | grep -q "^$serial" || { echo "$AVD_NAME ($serial) stopped"; exit 0; }
    sleep 1
  done
  echo "error: $serial still listed after 60 s" >&2
  exit 1
fi

[[ -f "$AVD_HOME/$AVD_NAME.ini" ]] || { echo "error: no AVD $AVD_NAME — run scripts/avd-create.sh first" >&2; exit 1; }

others="$(running | awk -v n="$AVD_NAME" '$2 != n')"
if [[ -n "$others" ]]; then
  echo "error: another emulator is running ($others); stop it first, only one runs at a time" >&2
  exit 1
fi

serial="$(running | awk -v n="$AVD_NAME" '$2 == n {print $1; exit}')"
if [[ -z "$serial" ]]; then
  # The image ships no userdata.img: the first boot has to create the data partition.
  [[ -f "$AVD_HOME/$AVD_NAME.avd/userdata-qemu.img" ]] || wipe="-wipe-data"
  mkdir -p "$LOG_DIR"
  log="$LOG_DIR/$AVD_NAME.log"
  echo "booting $AVD_NAME headless${wipe:+ (wiping data)} — log: $log"
  nohup "$EMULATOR" -avd "$AVD_NAME" -no-window -no-audio -no-boot-anim -no-snapshot \
    -gpu swiftshader_indirect $wipe >"$log" 2>&1 </dev/null &
  emulator_pid=$!
  for _ in $(seq 1 120); do
    serial="$(running | awk -v n="$AVD_NAME" '$2 == n {print $1; exit}')"
    [[ -n "$serial" ]] && break
    if ! kill -0 "$emulator_pid" 2>/dev/null; then
      echo "error: the emulator exited:" >&2
      tail -20 "$log" >&2
      exit 1
    fi
    sleep 1
  done
  [[ -n "$serial" ]] || { echo "error: $AVD_NAME did not show up in adb devices" >&2; exit 1; }
fi

echo "waiting for $serial to finish booting (up to $BOOT_TIMEOUT s)"
start=$SECONDS
until [[ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
  if (( SECONDS - start > BOOT_TIMEOUT )); then
    echo "error: $serial did not boot within $BOOT_TIMEOUT s" >&2
    exit 1
  fi
  sleep 2
done

"$ADB" -s "$serial" shell settings put global window_animation_scale 0
"$ADB" -s "$serial" shell settings put global transition_animation_scale 0
"$ADB" -s "$serial" shell settings put global animator_duration_scale 0
"$ADB" -s "$serial" shell svc power stayon true >/dev/null 2>&1 || true
"$ADB" -s "$serial" shell wm dismiss-keyguard >/dev/null 2>&1 || true

size="$("$ADB" -s "$serial" shell wm size | tr -d '\r' | awk -F': ' '/Physical/ {print $2}')"
density="$("$ADB" -s "$serial" shell wm density | tr -d '\r' | awk -F': ' '/Physical/ {print $2}')"
echo "$AVD_NAME booted as $serial in $((SECONDS - start)) s — screen $size at $density dpi"
[[ "$size" == "1206x2622" && "$density" == "480" ]] || echo "warning: expected 1206x2622 at 480 dpi" >&2
