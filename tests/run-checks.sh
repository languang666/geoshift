#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
CHECK_OUT=$(mktemp -d "${TMPDIR:-/tmp}/geoshift-checks.XXXXXX")
trap 'rm -rf "$CHECK_OUT"' EXIT
rg --files tests/bridge -g '*.java' > "$CHECK_OUT/sources.txt"
ANDROID_JAR="${ANDROID_HOME:-$(cd .. && pwd)/android/sdk}/platforms/android-37.0/android.jar"
# android.jar only resolves Android types referenced by FakeEnvironment; test stubs come first on the classpath.
javac -cp "$ANDROID_JAR" -d "$CHECK_OUT/classes" app/src/main/java/app/geoshift/ConfigCache.java app/src/main/java/app/geoshift/FakeEnvironment.java app/src/main/java/app/geoshift/Geo.java app/src/main/java/app/geoshift/FakeLocation.java app/src/main/java/app/geoshift/LocationPacket.java app/src/main/java/app/geoshift/DeliveryTargets.java app/src/main/java/app/geoshift/ProviderDiscovery.java app/src/main/java/app/geoshift/WifiPrivacyHooks.java app/src/main/java/app/geoshift/ReplyFactory.java app/src/main/java/app/geoshift/AppScope.java app/src/main/java/app/geoshift/CallbackOwners.java app/src/main/java/app/geoshift/SystemSpoofHooks.java app/src/main/java/app/geoshift/RoutePlan.java app/src/main/java/app/geoshift/FakeNmea.java @"$CHECK_OUT/sources.txt"
java -cp "$CHECK_OUT/classes" app.geoshift.ConfigCacheChecks
java -cp "$CHECK_OUT/classes" app.geoshift.SystemLocationChecks
java -cp "$CHECK_OUT/classes:$ANDROID_JAR" app.geoshift.WifiPrivacyChecks
java -cp "$CHECK_OUT/classes" app.geoshift.ReplyFactoryChecks
java -cp "$CHECK_OUT/classes" app.geoshift.RouteNmeaChecks
java -cp "$CHECK_OUT/classes:$ANDROID_JAR" app.geoshift.SystemSpoofChecks
# Coordinate maths and map-layer conversions (plain Java, no Android).
javac -d "$CHECK_OUT/geo" app/src/main/java/app/geoshift/Geo.java app/src/main/java/app/geoshift/MapSource.java tests/GeoChecks.java
java -cp "$CHECK_OUT/geo" GeoChecks
