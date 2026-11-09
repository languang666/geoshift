#!/usr/bin/env bash
# Builds the release APK and signs it with the existing private key, so it upgrades 1.x in place.
set -euo pipefail
cd "$(dirname "$0")"
# Layout: ~/geoshift/{source,releases,android/sdk,signing}; this script lives in source/.
WORKSPACE="$(cd .. && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$WORKSPACE/android/sdk}"
BT="$ANDROID_HOME/build-tools/37.0.0"
KEYS="${PORTAL_KEYS:-$WORKSPACE/signing}"
VERSION=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
OUT="Geoshift-$VERSION${EMULATOR:+-emulator}.apk"

# EMULATOR=1 also packs x86_64 native code for testing on the x86_64 emulator.
./gradlew --no-daemon -q assembleRelease ${EMULATOR:+-Pemulator}
TMP=$(mktemp -d)
cp app/build/outputs/apk/release/app-release-unsigned.apk "$TMP/unsigned.apk"
"$BT/zipalign" -c -p 4 "$TMP/unsigned.apk"
# signing/ is root-only; the password is passed through the environment, never argv.
sudo env PW="$(sudo cat "$KEYS/password.txt")" "$BT/apksigner" sign \
  --ks "$KEYS/portal-independent.p12" --ks-key-alias portal-independent \
  --ks-pass env:PW --key-pass env:PW --out "$TMP/$OUT" "$TMP/unsigned.apk"
sudo chown "$(id -u):$(id -g)" "$TMP/$OUT"
mkdir -p "$WORKSPACE/releases"
OUT_PATH="$WORKSPACE/releases/$OUT"
mv "$TMP/$OUT" "$OUT_PATH"
rm -rf "$TMP"
OUT="$OUT_PATH"
"$BT/apksigner" verify --print-certs "$OUT" | head -2
sha256sum "$OUT"
