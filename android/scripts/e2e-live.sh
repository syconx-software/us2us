#!/usr/bin/env bash
#
# The live service end to end on the emulator against the local relay of scripts/e2e-relay.sh
# (never the production relay): register → lock → unlock, the message request accepted by the echo
# bot, text, photo and voice there and back, the history after a relaunch, erase everything
# (live/src/androidTest/.../LiveEndToEndTest.kt).
#
#   bash scripts/e2e-relay.sh up        # first: the local relay and the echo bot
#   bash scripts/emulator-boot.sh       # and the AVD (one emulator only)
#   bash scripts/e2e-live.sh [--no-build] [--class <test class>] [--no-notifications] [--background-minutes <n>]
#
# --class runs another test of the live module's androidTest instead, with the same arguments, e.g.
# com.syconx.us2us.live.LiveSharingDeviceTest (live location in the background, the foreground
# service and its notification). --no-notifications runs it with the notification permission
# refused (a share then stops when the app leaves); --background-minutes keeps its share ticking in
# the background that long.
#
# The test APK carries libsignal for every ABI (about 1 GB); the copy installed here keeps arm64
# only, without libsignal's testing library, and is signed with the local debug key.
#
# Environment: E2E_WORK_DIR (default android/build/e2e), ANDROID_HOME (default ~/Library/Android/sdk),
# ANDROID_SERIAL (default: the only emulator).

set -uo pipefail

ANDROID_DIR=$(cd "$(dirname "$0")/.." && pwd)
readonly ANDROID_DIR
readonly WORK=${E2E_WORK_DIR:-$ANDROID_DIR/build/e2e}
readonly ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}
readonly ADB=$ANDROID_HOME/platform-tools/adb
readonly BUILD_TOOLS=$ANDROID_HOME/build-tools/36.1.0
readonly APK=$ANDROID_DIR/live/build/outputs/apk/androidTest/debug/live-debug-androidTest.apk
readonly TEST_PACKAGE=com.syconx.us2us.live.test
readonly RUNNER=$TEST_PACKAGE/androidx.test.runner.AndroidJUnitRunner

log() { printf '[e2e-live] %s\n' "$*" >&2; }

die() {
  log "$*"
  exit 1
}

BUILD=1
TEST_CLASS=com.syconx.us2us.live.LiveEndToEndTest
NOTIFICATIONS=1
BACKGROUND_MINUTES=1
while [ $# -gt 0 ]; do
  case $1 in
    --no-build) BUILD=0 ;;
    --no-notifications) NOTIFICATIONS=0 ;;
    --background-minutes)
      [ $# -ge 2 ] || die "--background-minutes needs a number"
      BACKGROUND_MINUTES=$2
      shift
      ;;
    --class)
      [ $# -ge 2 ] || die "--class needs a test class"
      TEST_CLASS=$2
      shift
      ;;
    -h | --help)
      sed -n '2,/^$/s/^# \{0,1\}//p' "$0"
      exit 0
      ;;
    *) die "unknown option: $1" ;;
  esac
  shift
done
case $TEST_CLASS in
  com.syconx.us2us.live.*) ;;
  *) die "not a test of the live module: $TEST_CLASS" ;;
esac

[ -f "$WORK/state.env" ] || die "no local relay: run scripts/e2e-relay.sh up first"
# shellcheck disable=SC1091
. "$WORK/state.env"
case $RELAY_URL in
  http://127.0.0.1:*) ;;
  *) die "not a loopback relay: $RELAY_URL" ;;
esac

SERIAL=${ANDROID_SERIAL:-$("$ADB" devices | awk '/^emulator-/ && $2 == "device" {print $1; exit}')}
[ -n "$SERIAL" ] || die "no emulator: run scripts/emulator-boot.sh first"
readonly SERIAL
log "device $SERIAL, relay $RELAY_URL, bot $BOT_USERNAME"

if [ "$BUILD" = 1 ]; then
  (cd "$ANDROID_DIR" && ./gradlew --console=plain -q :live:assembleDebugAndroidTest) >"$WORK/build-live-test.log" 2>&1 ||
    die "build failed, see $WORK/build-live-test.log"
fi
[ -f "$APK" ] || die "no test APK at $APK"

mkdir -p "$WORK/apk"
cp "$APK" "$WORK/apk/slim.apk"
zip -q -d "$WORK/apk/slim.apk" 'lib/armeabi-v7a/*' 'lib/x86/*' 'lib/x86_64/*' 'lib/arm64-v8a/libsignal_jni_testing.so' \
  'META-INF/*.SF' 'META-INF/*.RSA' 'META-INF/MANIFEST.MF' >/dev/null 2>&1
"$BUILD_TOOLS/zipalign" -f -P 16 4 "$WORK/apk/slim.apk" "$WORK/apk/aligned.apk" || die "zipalign failed"
"$BUILD_TOOLS/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --out "$WORK/apk/signed.apk" "$WORK/apk/aligned.apk" || die "signing failed"
rm -f "$WORK/apk/slim.apk" "$WORK/apk/aligned.apk"
log "installing the test APK ($(du -h "$WORK/apk/signed.apk" | cut -f1))"
"$ADB" -s "$SERIAL" uninstall "$TEST_PACKAGE" >/dev/null 2>&1
"$ADB" -s "$SERIAL" install -r -t "$WORK/apk/signed.apk" | tail -1

"$ADB" -s "$SERIAL" reverse "tcp:$RELAY_PORT" "tcp:$RELAY_PORT" >/dev/null || die "adb reverse failed"
# “+” › Konum and live location: allowed for the test, at a fixed place (Moda İskelesi).
LOCATION=0
if "$ADB" -s "$SERIAL" shell pm grant "$TEST_PACKAGE" android.permission.ACCESS_FINE_LOCATION >/dev/null 2>&1; then
  "$ADB" -s "$SERIAL" emu geo fix 29.0249 40.9846 >/dev/null 2>&1 && LOCATION=1
fi
# The live-location notice (Android 13+; older systems have no such permission), or refused.
if [ "$NOTIFICATIONS" = 1 ]; then
  "$ADB" -s "$SERIAL" shell pm grant "$TEST_PACKAGE" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
else
  "$ADB" -s "$SERIAL" shell pm revoke "$TEST_PACKAGE" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
fi
# The tests count the runtime's lifecycle lines of the run: a log large enough to keep them all.
"$ADB" -s "$SERIAL" logcat -G 16M >/dev/null 2>&1
"$ADB" -s "$SERIAL" logcat -c
log "running ${TEST_CLASS##*.}"
"$ADB" -s "$SERIAL" shell am instrument -w -r \
  -e relayUrl "$RELAY_URL" -e botUsername "$BOT_USERNAME" -e location "$LOCATION" \
  -e notifications "$NOTIFICATIONS" -e backgroundMinutes "$BACKGROUND_MINUTES" \
  -e class "$TEST_CLASS" "$RUNNER" | tee "$WORK/instrument.txt"
"$ADB" -s "$SERIAL" logcat -d -s us2us.e2e:I 'us2us.lifecycle:*' 'us2us.relay:*' 'us2us.messaging:*' AndroidRuntime:E \
  >"$WORK/logcat.txt" 2>&1

if grep -q '^OK (' "$WORK/instrument.txt"; then
  log "PASS"
  exit 0
fi
log "FAIL: see $WORK/instrument.txt and $WORK/logcat.txt"
exit 1
