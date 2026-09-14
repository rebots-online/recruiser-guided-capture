#!/usr/bin/env bash
set -euo pipefail
capture_repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$capture_repo"
if [[ -f .env.android-signing ]]; then
    set -a
    source .env.android-signing
    set +a
fi
: "${RECRUISER_KEYSTORE_PATH:?Set the existing production keystore path}"
: "${RECRUISER_KEYSTORE_PASSWORD:?Set the production keystore password}"
: "${RECRUISER_KEY_PASSWORD:?Set the production key password}"
: "${RECRUISER_KEY_ALIAS:?Set the production key alias}"
export RECRUISER_KEYSTORE_PATH RECRUISER_KEYSTORE_PASSWORD RECRUISER_KEY_PASSWORD RECRUISER_KEY_ALIAS
test -f "$RECRUISER_KEYSTORE_PATH"
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
android/gradlew -p android :core:test :app:testReleaseUnitTest :app:assembleRelease :app:bundleRelease "$@"
capture_tools="$ANDROID_HOME/build-tools/35.0.0"
capture_output=android/app/build/outputs
"$capture_tools/apksigner" verify --verbose --print-certs "$capture_output/apk/release/app-release.apk"
"$capture_tools/zipalign" -c -P 16 4 "$capture_output/apk/release/app-release.apk"
mkdir -p dist/android
capture_stem="dist/android/recruiser-v${capture_version}-android-arm64"
cp -- "$capture_output/apk/release/app-release.apk" "${capture_stem}-release.apk"
cp -- "$capture_output/bundle/release/app-release.aab" "${capture_stem}-release.aab"
cp -- "$capture_output/native-debug-symbols/release/native-debug-symbols.zip" "${capture_stem}-native-symbols.zip"
sha256sum "${capture_stem}-release.apk" "${capture_stem}-release.aab" "${capture_stem}-native-symbols.zip" > "${capture_stem}-SHA256SUMS.txt"
printf 'Built production-signed %s (POC; device acceptance remains separate)\n' "${capture_stem}-release.apk"
