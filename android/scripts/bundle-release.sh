#!/usr/bin/env bash
# Builds the signed release bundle for Google Play with the upload key from create-upload-key.sh.
# The password is asked for here and passed to Gradle only through this process's environment.
set -euo pipefail

key="$HOME/.us2us/us2us-upload.jks"
if [[ ! -f "$key" ]]; then
  echo "error: $key not found; run android/scripts/create-upload-key.sh first" >&2
  exit 1
fi

read -r -s -p "Upload key password: " US2US_UPLOAD_KEY_PASSWORD
echo
export US2US_UPLOAD_KEY_PASSWORD

cd "$(dirname "$0")/.."
./gradlew :app:bundleRelease

out="app/build/outputs/bundle/release/app-release.aab"
jarsigner -verify "$out" >/dev/null && echo "Signed bundle: $PWD/$out"
