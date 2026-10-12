#!/usr/bin/env bash
# Creates the Google Play upload key once, outside the repo: ~/.us2us/us2us-upload.jks.
# keytool asks for the password itself; it is never stored by this script.
# Keep a backup of the file and the password: a lost upload key has to be reset through Play support.
set -euo pipefail

dir="$HOME/.us2us"
key="$dir/us2us-upload.jks"

if [[ -e "$key" ]]; then
  echo "error: $key already exists; refusing to replace it" >&2
  exit 1
fi

mkdir -p "$dir"
chmod 700 "$dir"

keytool -genkeypair -v \
  -storetype PKCS12 \
  -keystore "$key" \
  -alias us2us-upload \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -dname "CN=SyconX LLC, O=SyconX LLC, C=US"

chmod 600 "$key"
echo
echo "Upload key: $key"
echo "Store the password in your password manager and back up this file."
echo "Next: android/scripts/bundle-release.sh"
