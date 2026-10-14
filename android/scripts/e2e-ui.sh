#!/usr/bin/env bash
#
# The debug app on the live service, driven through its screens on the emulator against the local
# relay of scripts/e2e-relay.sh (never the production relay), with the echo bot on the other side:
#
#   1.1 → 2.1 → 2.2 ×2 → 2.3 (register) → 4.1 → 4.2 (the bot) → 5.1: a text and its echo, a second
#   text (delivered), a photo through the system photo picker, a voice message held on the mic;
#   home and back (the lock, the code); a relaunch (the chat and its last message are back);
#   7.1 › Her şeyi sil → 1.1.
#
# Screenshots of every step go to build/e2e/ui/. The window is captured with the debug extra
# U2AllowCapture (FLAG_SECURE stays on otherwise).
#
#   bash scripts/e2e-relay.sh up && bash scripts/emulator-boot.sh
#   bash scripts/e2e-ui.sh [--no-install]
#
# Environment: E2E_WORK_DIR (default android/build/e2e), ANDROID_HOME, ANDROID_SERIAL.

set -uo pipefail

ANDROID_DIR=$(cd "$(dirname "$0")/.." && pwd)
readonly ANDROID_DIR
readonly WORK=${E2E_WORK_DIR:-$ANDROID_DIR/build/e2e}
readonly ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}
readonly ADB=$ANDROID_HOME/platform-tools/adb
readonly PACKAGE=com.syconx.us2us
readonly APK=$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk
readonly CODE=482916
readonly SHOTS=$WORK/ui
readonly DRIVER=$WORK/ui-driver.py

log() { printf '[e2e-ui] %s\n' "$*" >&2; }

die() {
  log "FAIL: $*"
  exit 1
}

INSTALL=1
for argument in "$@"; do
  case $argument in
    --no-install) INSTALL=0 ;;
    -h | --help)
      sed -n '2,/^$/s/^# \{0,1\}//p' "$0"
      exit 0
      ;;
    *) die "unknown option: $argument" ;;
  esac
done

[ -f "$WORK/state.env" ] || die "no local relay: run scripts/e2e-relay.sh up first"
# shellcheck disable=SC1091
. "$WORK/state.env"
case $RELAY_URL in
  http://127.0.0.1:*) ;;
  *) die "not a loopback relay: $RELAY_URL" ;;
esac
SERIAL=${ANDROID_SERIAL:-$("$ADB" devices | awk '/^emulator-/ && $2 == "device" {print $1; exit}')}
[ -n "$SERIAL" ] || die "no emulator: run scripts/emulator-boot.sh first"
export ANDROID_SERIAL=$SERIAL
mkdir -p "$SHOTS"

# The screen driver: the accessibility tree (texts and descriptions) from uiautomator, taps at the
# centre of a node, text through `input`, screenshots through screencap.
cat >"$DRIVER" <<'PY'
import os, re, subprocess, sys, time

ADB = sys.argv[1]
SHOTS = sys.argv[2]
command, args = sys.argv[3], sys.argv[4:]


def adb(*arguments):
    return subprocess.run([ADB, *arguments], capture_output=True, timeout=120)


def nodes():
    raw = adb("exec-out", "uiautomator", "dump", "/dev/tty").stdout.decode("utf-8", "replace")
    found = []
    for match in re.finditer(r"<node ([^>]*)>", raw):
        attributes = dict(re.findall(r'([\w-]+)="([^"]*)"', match.group(1)))
        bounds = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", attributes.get("bounds", ""))
        if bounds:
            found.append((attributes.get("text", ""), attributes.get("content-desc", ""), tuple(map(int, bounds.groups()))))
    return found


def find(label, exact):
    for text, description, bounds in nodes():
        for value in (text, description):
            if (value == label) if exact else (label in value):
                return bounds
    return None


def wait(label, seconds, exact=False):
    deadline = time.time() + seconds
    while time.time() < deadline:
        bounds = find(label, exact)
        if bounds:
            return bounds
        time.sleep(0.5)
    return None


if command == "tap":
    bounds = wait(args[0], float(args[2]) if len(args) > 2 else 15, exact=args[1] == "exact")
    if not bounds:
        sys.exit(f"not on screen: {args[0]}")
    adb("shell", "input", "tap", str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
elif command == "wait":
    if not wait(args[0], float(args[1])):
        sys.exit(f"timed out waiting for: {args[0]}")
elif command == "gone":
    deadline = time.time() + float(args[1])
    while find(args[0], False):
        if time.time() > deadline:
            sys.exit(f"still on screen: {args[0]}")
        time.sleep(0.5)
elif command == "digits":
    for digit in args[0]:
        bounds = wait(digit, 10, exact=True)
        if not bounds:
            sys.exit(f"no key {digit}")
        adb("shell", "input", "tap", str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
        time.sleep(0.15)
elif command == "hold":
    bounds = wait(args[0], 10)
    if not bounds:
        sys.exit(f"not on screen: {args[0]}")
    x, y = str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2)
    adb("shell", "input", "swipe", x, y, x, y, args[1])
elif command == "shot":
    with open(os.path.join(SHOTS, args[0] + ".png"), "wb") as out:
        out.write(adb("exec-out", "screencap", "-p").stdout)
PY

ui() { python3 "$DRIVER" "$ADB" "$SHOTS" "$@"; }
step() { log "$*"; }
type_text() { "$ADB" shell input text "$1"; }
key() { "$ADB" shell input keyevent "$1"; }

launch() {
  "$ADB" shell am start -W -n "$PACKAGE/.app.MainActivity" --es U2RelayURL "$RELAY_URL" --ez U2AllowCapture true \
    --es U2Language tr --es U2Theme dark >/dev/null
}

if [ "$INSTALL" = 1 ]; then
  [ -f "$APK" ] || die "no debug APK: ./gradlew :app:assembleDebug"
  "$ADB" install -r "$APK" | tail -1
fi
"$ADB" reverse "tcp:$RELAY_PORT" "tcp:$RELAY_PORT" >/dev/null || die "adb reverse failed"
"$ADB" shell pm clear "$PACKAGE" >/dev/null
"$ADB" shell pm grant "$PACKAGE" android.permission.RECORD_AUDIO
USERNAME="l4ui_$(od -An -N3 -tx1 /dev/urandom | tr -d ' \n')"

# A photo in the phone's gallery for the system photo picker.
python3 -c "
import sys
from PIL import Image, ImageDraw
import math
image = Image.new('RGB', (1600, 1200), (20, 24, 40))
draw = ImageDraw.Draw(image)
for i in range(40):
    y = 600 + 200 * math.sin(i / 3)
    draw.ellipse((40 * i - 30, y - 30, 40 * i + 30, y + 30), fill=(240, 180, 90))
image.save(sys.argv[1], quality=90)
" "$WORK/moda.jpg" || die "Pillow is needed for the sample photo"
"$ADB" shell rm -f /sdcard/Pictures/moda.jpg
"$ADB" push "$WORK/moda.jpg" /sdcard/Pictures/moda.jpg >/dev/null
"$ADB" shell content call --method scan_volume --uri content://media --arg external_primary >/dev/null

step "1.1 → 2.3: register $USERNAME"
launch
ui wait "Başla" 20 || die "no welcome screen"
ui shot 01-welcome
ui tap "Başla" exact || die "1.1"
ui tap "Anladım" exact || die "1.2"
ui tap "Hesabımı oluştur" exact || die "1.3"
ui tap "Kullanıcı adı" exact || die "2.1"
type_text "$USERNAME"
sleep 1
ui shot 02-username
key 4
ui tap "Devam" exact || die "2.1 continue"
ui wait "Gizli kodunu belirle" 10 || die "2.2"
ui digits "$CODE" || die "2.2 first"
ui wait "Kodu tekrar gir" 10 || die "2.2 again"
ui digits "$CODE" || die "2.2 second"
ui wait "Kurulumu tamamla" 10 || die "2.3"
ui shot 03-setup
ui tap "Anladım: kodu" partial || die "2.3 acknowledgement"
ui tap "Kurulumu tamamla" exact || die "2.3 finish"
ui wait "Hat açık" 60 || die "4.1: the line did not open"
sleep 1
ui shot 04-chats-empty

step "4.2 → 5.1: the request to $BOT_USERNAME, accepted"
ui tap "Yeni sohbet" exact || die "4.2"
ui tap "Kullanıcı adı" exact || die "4.2 field"
type_text "$BOT_USERNAME"
ui wait "Mesaj yaz" 30 || die "4.2: the bot was not found"
ui shot 05-lookup
ui tap "Mesaj yaz" exact || die "4.2 write"
ui tap "Mesaj" exact || die "5.1 composer"
type_text "merhaba"
ui tap "Gönder" exact || die "5.1 send"
key 4
ui wait "echo: merhaba" 90 || die "no echo"
ui tap "Mesaj" exact || die "5.1 composer"
type_text "nasilsin"
ui tap "Gönder" exact || die "5.1 send"
key 4
ui wait "echo: nasilsin" 90 || die "no second echo"
ui wait "İletildi" 30 || die "not delivered"
sleep 1
ui shot 06-texts

step "5.1: a photo from the system photo picker"
ui tap "Ekle" exact || die "+"
ui tap "Fotoğraf ve video" exact || die "+ › photo"
ui tap "Photo taken on" partial 20 || die "the picker shows no photo"
ui tap "Done" exact || die "the picker's Done"
ui wait "echo-attachment" 120 || die "the bot did not get the photo"
sleep 1
ui shot 07-photo

step "5.1: a voice message held on the mic"
ui hold "Sesli mesaj kaydet" 3500 || die "mic"
ui wait "Sesli mesajı oynat" 30 || die "no voice bubble"
sleep 8
ui shot 08-voice

step "lock: home and back, the code"
key 3
sleep 2
"$ADB" shell am start -n "$PACKAGE/.app.MainActivity" >/dev/null 2>&1
ui wait "Gizli kodunu gir" 20 || die "no lock after the background"
ui shot 09-lock
ui digits "$CODE" || die "lock code"
ui wait "$BOT_USERNAME" 20 || die "no chat after the unlock"

step "relaunch: the chat and its last message are back"
"$ADB" shell am force-stop "$PACKAGE"
launch
ui wait "Gizli kodunu gir" 20 || die "no lock at launch"
ui digits "$CODE" || die "lock code"
ui wait "echo-attachment" 30 || die "the history did not come back"
sleep 1
ui shot 10-relaunch

step "7.1 › Her şeyi sil → 1.1"
ui tap "Ayarlar" exact || die "7.1"
ui wait "GİZLİLİK" 10 || die "7.1 did not open"
"$ADB" shell input swipe 600 2200 600 600 400
"$ADB" shell input swipe 600 2200 600 600 400
ui tap "Her şeyi sil" exact || die "erase row"
ui tap "Hesabı ve her şeyi sil" exact || die "erase confirmation"
ui wait "Başla" 60 || die "not back at 1.1"
ui shot 11-erased
"$ADB" shell rm -f /sdcard/Pictures/moda.jpg

log "PASS ($SHOTS)"
