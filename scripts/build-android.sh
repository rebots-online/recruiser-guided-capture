#!/usr/bin/env bash
set -euo pipefail
capture_repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$capture_repo"
source scripts/lib/cc12-artifact-name.sh
for capture_arg in "$@"; do
    case "$capture_arg" in
        --console=plain|--offline|--no-daemon|--rerun-tasks|--stacktrace) ;;
        *) printf 'Unsupported build argument: %s\n' "$capture_arg" >&2; exit 2 ;;
    esac
done
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
capture_expected_cert=56c13674ef22df95deb1e5c468820e8cfa3ea2f522511749ab7b6e5bde3bd943
capture_key_cert=$(keytool -exportcert -keystore "$RECRUISER_KEYSTORE_PATH" \
    -alias "$RECRUISER_KEY_ALIAS" -storepass:env RECRUISER_KEYSTORE_PASSWORD | sha256sum | awk '{print $1}')
if [[ "$capture_key_cert" != "$capture_expected_cert" ]]; then
    printf 'Signing key does not match the established production certificate.\n' >&2
    exit 1
fi
bash scripts/update-version.sh
capture_version=$(tr -d '[:space:]' < version.txt)
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
if [[ ! -d "$ANDROID_HOME/platforms/android-35" ]]; then
    printf 'Android SDK 35 is required at ANDROID_HOME: %s\n' "$ANDROID_HOME" >&2
    exit 1
fi
android/gradlew -p android :core:test :app:testReleaseUnitTest :app:assembleRelease :app:bundleRelease "$@"
capture_tools="$ANDROID_HOME/build-tools/35.0.0"
capture_output=android/app/build/outputs
capture_apk="$capture_output/apk/release/app-release.apk"
capture_aab="$capture_output/bundle/release/app-release.aab"
capture_symbols="$capture_output/native-debug-symbols/release/native-debug-symbols.zip"
capture_analyzer="$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer"
capture_cert=$("$capture_tools/apksigner" verify --print-certs "$capture_apk")
[[ "$(awk '/Signer #1 certificate SHA-256 digest:/ {print $NF}' <<< "$capture_cert")" == "$capture_expected_cert" ]]
[[ "$("$capture_analyzer" manifest debuggable "$capture_apk")" == false ]]
[[ "$("$capture_analyzer" manifest application-id "$capture_apk")" == mba.robin.recruiser ]]
[[ "$("$capture_analyzer" manifest version-name "$capture_apk")" == "$capture_version" ]]
capture_version_code=$(jq -r .versionCode version.json)
[[ "$("$capture_analyzer" manifest version-code "$capture_apk")" == "$capture_version_code" ]]
"$capture_tools/zipalign" -c -P 16 4 "$capture_apk"
capture_verify_dir=$(mktemp -d "$capture_repo/android/app/build/release-verification-${capture_version}-XXXXXX")
jarsigner -verify -strict -keystore "$RECRUISER_KEYSTORE_PATH" \
    -storepass:env RECRUISER_KEYSTORE_PASSWORD "$capture_aab" "$RECRUISER_KEY_ALIAS" \
    > "$capture_verify_dir/aab-signature.txt" 2>&1 || { cat "$capture_verify_dir/aab-signature.txt" >&2; exit 1; }
unzip -tq "$capture_aab"
unzip -tq "$capture_symbols"
unzip -Z1 "$capture_symbols" | awk '/librecruiser_depth\.so/ {found=1} END {exit !found}'
unzip -Z1 "$capture_apk" | awk '/^lib\/.*\.so$/ {print; found=1} END {exit !found}' > "$capture_verify_dir/native-libraries.txt"
while IFS= read -r capture_library; do
    capture_elf="$capture_verify_dir/$(basename "$capture_library")"
    unzip -p "$capture_apk" "$capture_library" > "$capture_elf"
    readelf -lW "$capture_elf" | awk '$1=="LOAD" {print $NF; found=1} END {exit !found}' > "$capture_verify_dir/alignments.txt"
    while IFS= read -r capture_alignment; do
        if (( capture_alignment < 16384 )); then
            printf 'Native library is not 16 KB aligned: %s\n' "$capture_library" >&2
            exit 1
        fi
    done < "$capture_verify_dir/alignments.txt"
done < "$capture_verify_dir/native-libraries.txt"
mkdir -p dist/android
# TC5 staging contract from the established Android recipe: never overwrite a set.
capture_apk_dest=$(cc12_path dist/android "$capture_version" android-arm64-release apk)
capture_aab_dest=$(cc12_path dist/android "$capture_version" android-arm64-release aab)
capture_symbols_dest=$(cc12_path dist/android "$capture_version" android-arm64-native-symbols zip)
capture_hashes_dest=$(cc12_path dist/android "$capture_version" android-arm64-SHA256SUMS txt)
for capture_dest in "$capture_apk_dest" "$capture_aab_dest" "$capture_symbols_dest" "$capture_hashes_dest"; do
    if [[ -e "$capture_dest" ]]; then
        printf 'Refusing to overwrite an existing artifact: %s\n' "$capture_dest" >&2
        exit 1
    fi
done
cp -- "$capture_apk" "$capture_apk_dest"
cp -- "$capture_aab" "$capture_aab_dest"
cp -- "$capture_symbols" "$capture_symbols_dest"
sha256sum "$capture_apk_dest" "$capture_aab_dest" "$capture_symbols_dest" > "$capture_hashes_dest"
sha256sum -c "$capture_hashes_dest"
cc12_assert_dist dist/android "$capture_version"
if [[ -f release.lock ]]; then
    printf 'Post-build bump deferred: release.lock holds the shared platform set; matrix owner performs the post-set bump.\n'
else
    bash scripts/update-version.sh --post-build
fi
printf 'ALL RELEASE GATES PASSED: %s (POC; device acceptance remains separate)\n' "$capture_apk_dest"
