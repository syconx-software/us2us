#!/usr/bin/env bash
# Creates the us2us36 emulator (AVD) without avdmanager: two ini files pointing at the installed
# android-36.1 Google Play arm64-v8a system image.
#
# The screen is 1206 × 2622 px at 480 dpi = 402 × 874 dp at density 3, the size of the iOS
# references, so device screenshots compare 1:1.
#
#   scripts/avd-create.sh            # writes ~/.android/avd/us2us36.ini and us2us36.avd/config.ini
#   scripts/emulator-boot.sh         # then boots it headless
#
# Re-running rewrites only the two ini files; the emulator's data (userdata, snapshots) is kept.
# Environment: ANDROID_HOME (default ~/Library/Android/sdk), ANDROID_AVD_HOME (default ~/.android/avd),
# AVD_NAME (default us2us36).
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
AVD_NAME="${AVD_NAME:-us2us36}"
TARGET="android-36.1"
IMAGE_SYSDIR="system-images/$TARGET/google_apis_playstore/arm64-v8a/"

if [[ ! -f "$ANDROID_HOME/$IMAGE_SYSDIR/system.img" ]]; then
  echo "error: no system image at $ANDROID_HOME/$IMAGE_SYSDIR" >&2
  echo "install it with: sdkmanager 'system-images;$TARGET;google_apis_playstore;arm64-v8a'" >&2
  exit 1
fi
if [[ "$(uname -m)" != "arm64" ]]; then
  echo "warning: the arm64-v8a image needs an Apple silicon (arm64) host to run fast" >&2
fi

AVD_DIR="$AVD_HOME/$AVD_NAME.avd"
mkdir -p "$AVD_DIR"

cat > "$AVD_HOME/$AVD_NAME.ini" <<EOF
avd.ini.encoding=UTF-8
path=$AVD_DIR
path.rel=avd/$AVD_NAME.avd
target=$TARGET
EOF

cat > "$AVD_DIR/config.ini" <<EOF
AvdId=$AVD_NAME
avd.ini.displayname=us2us 36.1
avd.ini.encoding=UTF-8
target=$TARGET
image.sysdir.1=$IMAGE_SYSDIR
tag.id=google_apis_playstore
tag.display=Google Play
tag.ids=google_apis_playstore
PlayStore.enabled=true
abi.type=arm64-v8a
hw.cpu.arch=arm64
hw.cpu.ncore=4
hw.ramSize=4096
vm.heapSize=512
disk.dataPartition.size=6G
hw.lcd.width=1206
hw.lcd.height=2622
hw.lcd.density=480
hw.initialOrientation=portrait
hw.keyboard=yes
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
hw.camera.back=emulated
hw.camera.front=emulated
hw.audioInput=yes
hw.sensors.orientation=yes
hw.sensors.proximity=yes
fastboot.forceColdBoot=yes
fastboot.forceFastBoot=no
skin.dynamic=yes
skin.name=1206x2622
skin.path=_no_skin
showDeviceFrame=no
EOF

echo "AVD $AVD_NAME: $AVD_DIR (1206 × 2622 px, 480 dpi, $TARGET Google Play arm64-v8a)"
if [[ -x "$ANDROID_HOME/emulator/emulator" ]]; then
  if "$ANDROID_HOME/emulator/emulator" -list-avds 2>/dev/null | grep -qx "$AVD_NAME"; then
    echo "emulator -list-avds: $AVD_NAME"
  else
    echo "error: the emulator does not list $AVD_NAME" >&2
    exit 1
  fi
fi
