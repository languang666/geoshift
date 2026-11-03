#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
CHECK_OUT=$(mktemp -d /tmp/geoshift-bridge-checks.XXXXXX)
trap 'rm -rf "$CHECK_OUT"' EXIT
rg --files tests/bridge -g '*.java' > "$CHECK_OUT/sources.txt"
ANDROID_JAR="${ANDROID_HOME:-$HOME/android-sdk}/platforms/android-35/android.jar"
# android.jar only resolves Android types referenced by FakeEnvironment; test stubs come first on the classpath.
javac -cp "$ANDROID_JAR" -d "$CHECK_OUT/classes" app/src/main/java/app/geoshift/FakeEnvironment.java app/src/main/java/app/geoshift/Geo.java app/src/main/java/app/geoshift/FakeLocation.java app/src/main/java/app/geoshift/LocationPacket.java app/src/main/java/app/geoshift/DeliveryTargets.java app/src/main/java/app/geoshift/ProviderDiscovery.java app/src/main/java/app/geoshift/WifiPrivacyHooks.java app/src/main/java/app/geoshift/ReplyFactory.java @"$CHECK_OUT/sources.txt"
java -cp "$CHECK_OUT/classes" app.geoshift.SystemLocationChecks
java -cp "$CHECK_OUT/classes:$ANDROID_JAR" app.geoshift.WifiPrivacyChecks
java -cp "$CHECK_OUT/classes" app.geoshift.ReplyFactoryChecks
