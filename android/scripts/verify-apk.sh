#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "usage: $0 <apk> [expected-four-part-version]" >&2
  exit 2
fi

apk="$1"
expected_version="${2:-}"
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
build_tools_version="${ANDROID_BUILD_TOOLS_VERSION:-35.0.0}"
if command -v cygpath >/dev/null 2>&1; then
  sdk_root="$(cygpath -u "$sdk_root")"
fi
build_tools="$sdk_root/build-tools/$build_tools_version"
aapt2="$build_tools/aapt2"
apksigner="$build_tools/apksigner"

if [[ ! -x "$aapt2" && -x "$aapt2.exe" ]]; then
  aapt2="$aapt2.exe"
fi
if [[ ! -x "$apksigner" && -f "$apksigner.bat" ]]; then
  apksigner="$apksigner.bat"
fi

[[ -f "$apk" ]] || { echo "APK not found: $apk" >&2; exit 1; }
[[ -x "$aapt2" ]] || { echo "aapt2 not found: $aapt2" >&2; exit 1; }
[[ -f "$apksigner" ]] || { echo "apksigner not found: $apksigner" >&2; exit 1; }

badging="$($aapt2 dump badging "$apk")"
manifest="$($aapt2 dump xmltree "$apk" --file AndroidManifest.xml)"

grep -Fq "package: name='com.teknogods.rpcs3x6'" <<<"$badging"
grep -Eq "versionName='[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+'" <<<"$badging"
if [[ -n "$expected_version" ]]; then
  grep -Fq "versionName='$expected_version'" <<<"$badging"
fi
grep -Fq "native-code: 'arm64-v8a'" <<<"$badging"

if grep -Fq "launchable-activity:" <<<"$badging" ||
   grep -Fq "android.intent.category.LAUNCHER" <<<"$manifest" ||
   grep -Fq "android.intent.category.LEANBACK_LAUNCHER" <<<"$manifest"; then
  echo "RPCS3X6 must remain a hidden TPUI companion" >&2
  exit 1
fi

for required in \
  "android.permission.MANAGE_EXTERNAL_STORAGE" \
  "com.teknoparrot.permission.BIND_BRIDGE" \
  "com.teknoparrot.rpcs3x6.action.LAUNCH_GAME" \
  "com.teknoparrot.rpcs3x6.action.SETUP" \
  "com.teknoparrot.rpcs3x6.action.QUERY_SESSION" \
  "com.teknoparrot.rpcs3x6.action.STOP_GAME" \
  "com.teknoparrot.rpcs3x6.action.QUERY_CATALOG" \
  "com.teknoparrot.rpcs3x6.action.IMPORT_ARCADE_ROOT" \
  "com.teknoparrot.rpcs3x6.action.QUERY_FIRMWARE"; do
  grep -Fq "$required" <<<"$manifest" || {
    echo "Missing manifest contract value: $required" >&2
    exit 1
  }
done

mapfile -t native_entries < <(unzip -Z1 "$apk" | grep '^lib/' || true)
[[ ${#native_entries[@]} -gt 0 ]] || { echo "APK contains no native libraries" >&2; exit 1; }
if printf '%s\n' "${native_entries[@]}" | grep -Evq '^lib/arm64-v8a/[^/]+\.so$'; then
  echo "APK contains a native entry outside the arm64-v8a envelope" >&2
  printf '%s\n' "${native_entries[@]}" >&2
  exit 1
fi

apk_entries="$(unzip -Z1 "$apk")"
for required_asset in \
  assets/teknoparrot/vfs/akb48_vfs.yml \
  assets/teknoparrot/vfs/darkescape4d_vfs.yml \
  assets/teknoparrot/vfs/dbzenkai_vfs.yml \
  assets/teknoparrot/vfs/dsps_vfs.yml \
  assets/teknoparrot/vfs/RazingStorm_vfs.yml \
  assets/teknoparrot/vfs/taikogreen_vfs.yml \
  assets/teknoparrot/vfs/taikoyellow_vfs.yml \
  assets/teknoparrot/vfs/Tekken6_vfs.yml \
  assets/teknoparrot/vfs/Tekken6BR_vfs.yml \
  assets/teknoparrot/vfs/ttt2_vfs.yml \
  assets/teknoparrot/vfs/ttt2u_vfs.yml \
  assets/teknoparrot/patch_config.yml \
  assets/teknoparrot/patches/imported_patch.yml; do
  grep -Fxq "$required_asset" <<<"$apk_entries" || {
    echo "Missing TeknoParrot RPCS3 config asset: $required_asset" >&2
    exit 1
  }
done

unzip -tqq "$apk"
signature="$($apksigner verify --verbose --print-certs "$apk")"
printf '%s\n' "$signature"
if [[ "${REQUIRE_PRODUCTION_SIGNATURE:-0}" == "1" ]]; then
  expected="${EXPECTED_CERT_SHA256:-}"
  [[ -n "$expected" ]] || { echo "EXPECTED_CERT_SHA256 is required" >&2; exit 1; }
  normalized="$(tr '[:upper:]' '[:lower:]' <<<"$signature")"
  grep -Fq "certificate sha-256 digest: ${expected,,}" <<<"$normalized" || {
    echo "RPCS3X6 is not signed with the TeknoParrot production certificate" >&2
    exit 1
  }
fi

if [[ "${REQUIRE_RELEASE_APK:-0}" == "1" ]] && grep -Fq "application-debuggable" <<<"$badging"; then
  echo "Release APK is marked debuggable" >&2
  exit 1
fi

echo "Verified RPCS3X6 APK: package=com.teknogods.rpcs3x6 version=${expected_version:-manifest} abi=arm64-v8a"
