#!/usr/bin/env bash
set -euo pipefail
capture_repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$capture_repo"
capture_version_tmp=$(mktemp "$capture_repo/android/.version-XXXXXX")
trap 'test ! -f "$capture_version_tmp" || rm -- "$capture_version_tmp"' EXIT
awk -F= 'BEGIN { OFS="=" } $1=="minor" || $1=="build" { $2=$2+1 } { print }' \
    android/version.properties > "$capture_version_tmp"
mv -- "$capture_version_tmp" android/version.properties
capture_version=$(awk -F= '/^major=/{a=$2}/^minor=/{b=$2}/^build=/{c=$2}END{print a"."b"."c}' android/version.properties)
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
if [[ ! -d "$ANDROID_HOME/platforms/android-35" ]]; then
    printf 'Android SDK 35 is required at ANDROID_HOME: %s\n' "$ANDROID_HOME" >&2
    exit 1
fi
android/gradlew -p android :core:test :app:testDebugUnitTest :app:assembleDebug "$@"
mkdir -p dist/android
capture_apk="dist/android/recruiser-capture-${capture_version}-arm64-debug.apk"
cp -- android/app/build/outputs/apk/debug/app-debug.apk "$capture_apk"
sha256sum "$capture_apk"
printf 'Built %s (development signing; device validation required)\n' "$capture_apk"
