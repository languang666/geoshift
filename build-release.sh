#!/usr/bin/env bash
# Builds the release APK and signs it with the existing private key, so it upgrades 1.x in place.
set -euo pipefail
cd "$(dirname "$0")"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
BT="$ANDROID_HOME/build-tools/35.0.0"
KEYS="${PORTAL_KEYS:-$HOME/.geoshift-signing}"
VERSION=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
OUT="Geoshift-$VERSION.apk"

./gradlew --no-daemon -q assembleRelease
TMP=$(mktemp -d)
cp app/build/outputs/apk/release/app-release-unsigned.apk "$TMP/unsigned.apk"
"$BT/zipalign" -c -p 4 "$TMP/unsigned.apk"
# private-signing/ is root-only; the password is passed through the environment, never argv.
sudo env PW="$(sudo cat "$KEYS/password.txt")" "$BT/apksigner" sign \
  --ks "$KEYS/portal-independent.p12" --ks-key-alias portal-independent \
  --ks-pass env:PW --key-pass env:PW --out "$TMP/$OUT" "$TMP/unsigned.apk"
sudo chown "$(id -u):$(id -g)" "$TMP/$OUT"
mv "$TMP/$OUT" "$OUT"
rm -rf "$TMP"
"$BT/apksigner" verify --print-certs "$OUT" | head -2
sha256sum "$OUT"
