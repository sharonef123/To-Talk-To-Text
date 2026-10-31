#!/usr/bin/env bash
# Build the debug APK and copy it to dist/android/Talk-To-Text-(By-Efroni)-Android-App.apk.
# Requires JDK 17+ and the Android SDK (ANDROID_HOME). Uses ./gradlew if present,
# otherwise falls back to a system `gradle` install.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
DIST="$ROOT/dist/android"
APK_NAME="Talk-To-Text-(By-Efroni)-Android-App.apk"

cd "$HERE"
if [[ -x ./gradlew ]]; then
  GRADLE=(./gradlew)
elif command -v gradle >/dev/null 2>&1; then
  GRADLE=(gradle)
else
  echo "ERROR: neither ./gradlew nor 'gradle' found. Install Gradle 8.7+ or run 'gradle wrapper' once." >&2
  exit 1
fi

"${GRADLE[@]}" --no-daemon :app:assembleDebug

mkdir -p "$DIST"
cp app/build/outputs/apk/debug/app-debug.apk "$DIST/$APK_NAME"
echo "Built $DIST/$APK_NAME"
