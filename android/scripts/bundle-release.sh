#!/usr/bin/env bash
# Builds the signed release bundle for Google Play with the upload key from create-upload-key.sh.
#
# The password is asked for here and handed to one Gradle process only: it is never exported into
# this shell, the build runs without a Gradle daemon and with the Kotlin compiler in-process (so no
# long-lived process keeps it after the build), and every dependency is checked against
# gradle/verification-metadata.xml (strict) before anything of it runs.
set -euo pipefail

key="${US2US_UPLOAD_KEYSTORE:-$HOME/.us2us/us2us-upload.jks}"
if [[ ! -f "$key" ]]; then
  echo "error: $key not found; run android/scripts/create-upload-key.sh first" >&2
  exit 1
fi

cd "$(dirname "$0")/.."

if [[ ! -f gradle/verification-metadata.xml ]]; then
  echo "error: gradle/verification-metadata.xml is missing; refusing to build a release without dependency verification" >&2
  exit 1
fi

# Record (not refuse) a tree with uncommitted changes, as the iOS release script does.
if [[ -n "$(git status --porcelain -- . 2>/dev/null)" ]]; then
  echo "warning: android/ has uncommitted changes (release built from a NOT clean tree)" >&2
fi

password=""
cleanup() { password=""; }
trap cleanup EXIT

read -r -s -p "Upload key password: " password
echo

# Only this command's environment holds the password; the shell variable is cleared right after.
US2US_UPLOAD_KEY_PASSWORD="$password" ./gradlew \
  --no-daemon \
  --dependency-verification=strict \
  -Pkotlin.compiler.execution.strategy=in-process \
  :app:bundleRelease :app:assembleRelease
password=""

out="app/build/outputs/bundle/release/app-release.aab"
jarsigner -verify "$out" >/dev/null && echo "Signed bundle: $PWD/$out"
