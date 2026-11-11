#!/usr/bin/env bash
# JVM unit tests for the pure simulation logic (app/src/test). Device behaviour is checked with the
# GeoProbe app (tests/probe) on an emulator with an Xposed framework.
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$(cd .. && pwd)/android/sdk}"
./gradlew --no-daemon -q testDebugUnitTest
for f in app/build/test-results/testDebugUnitTest/*.xml; do
  grep -o 'testsuite name="[^"]*" tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$f"
done
