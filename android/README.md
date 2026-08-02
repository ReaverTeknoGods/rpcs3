# RPCS3X6 Android companion

RPCS3X6 is the ARM64 Android companion for the TeknoParrot Arcade RPCS3 fork.
It is packaged as `com.teknogods.rpcs3x6` and intentionally has no Android
launcher entry. TeknoParrotUi owns catalog, search, launch, and session UX.

The frontend is based on the GPL-2.0 RPCS3-Android frontend and builds the
current source tree directly; there is no nested or separately pinned RPCS3
core. The imported frontend history is available at
<https://github.com/RPCS3-Android/rpcs3-android>.

## Host requirements

- JDK 17
- Android SDK platform 35 and build-tools 35.0.0
- Android NDK `28.2.13676358` (Clang 19)
- CMake `3.31.6`
- An ARM64 host is not required; the NDK cross-compiles `arm64-v8a`

The build downloads a pinned ARM64 FFmpeg 5.1 archive and rejects it if its
SHA-256 does not match `CMakeLists.txt`. LLVM 22.1 is built from RPCS3's pinned
recursive submodule so the Android JIT stays on the same LLVM revision as the
merged desktop core.

From the repository root on Windows:

```powershell
$env:ANDROID_HOME = "C:\path\to\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:JAVA_HOME = "C:\path\to\jdk-17"
Set-Location android
.\gradlew.bat --no-daemon :app:assembleDebug
```

On Linux/macOS:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_HOME=/path/to/jdk-17
cd android
./gradlew --no-daemon :app:assembleDebug
```

The default development version is `0.0.0.1`. Override both Android version
fields for automation or releases:

```bash
./gradlew --no-daemon \
  -Prpcs3x6VersionName=0.0.42.1 \
  -Prpcs3x6VersionCode=4201 \
  :app:assembleDebug
```

Release signing uses `KEYSTORE_PATH`, `KEYSTORE_ALIAS`, and
`KEYSTORE_PASSWORD`. The release key must be compatible with TPUI's
`com.teknoparrot.permission.BIND_BRIDGE` signature permission. Debug APKs are
host/CI qualification artifacts and are not production TPUI companions.

## TeknoParrotUi contract

TPUI launches the exported `net.rpcs3.RPCS3Activity`, protected by
`com.teknoparrot.permission.BIND_BRIDGE`, with:

- action `com.teknoparrot.rpcs3x6.action.LAUNCH_GAME`
- `...extra.GAME_PATH`: an absolute or relative path already imported beneath
  RPCS3X6's external-files directory
- `...extra.PROFILE_NAME`: TPUI profile display name
- `...extra.CALLBACK_PACKAGE`: exactly `com.teknoparrot.ui`
- `...extra.SESSION_TOKEN`: a 32-128 character per-session token

The same token and callback package authenticate `QUERY_SESSION`, `STOP_GAME`,
`QUERY_CATALOG`, and `QUERY_FIRMWARE` broadcasts. Replies are package-targeted
to TPUI. The hidden setup activity is available through
`com.teknoparrot.rpcs3x6.action.SETUP` for firmware and content maintenance.

Physical controllers map Android A/B/X/Y to PS3 Cross/Circle/Square/Triangle,
plus D-pad, shoulders, triggers, Start/Select, L3/R3, PS, both sticks, HAT axes,
and common trigger/right-stick axis fallbacks. The existing editable touch
overlay feeds the same virtual PS3 pad.

## Validation boundary

`:app:assembleDebug` proves host compilation and APK packaging. The verification
script checks package/version/ABI, hidden-launcher policy, bridge permission,
signature, and native library envelope. It does not by itself prove firmware
installation, game compatibility, sustained play, audio, or physical-controller
acceptance on a device.

No Android install is part of the Gradle build or GitHub Actions workflow.

## License

RPCS3X6 and the imported Android frontend are GPL-2.0, except for dependencies
or files that carry their own license notices. See `LICENSE` and the repository's
third-party license files.
