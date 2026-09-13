# Recruiser Android capture

The native Android app captures original ARCore datasets and explicit observations
locally. Build 3D runs afterward on that device. The shared web workspace imports
`.recruiser-capture.zip` archives and opens their derived coloured PLY scenes.
Neither local workflow requires a reconstruction server or microphone permission.

## Build

Install JDK 17, Android SDK platform 35 and NDK 27.0.12077973. The checked-in
Gradle wrapper pins Gradle 8.14.3; dependencies are pinned in the Gradle files.
Set `ANDROID_HOME` to the SDK directory, then run from the repository root:

```sh
bash scripts/build-android.sh
```

Each invocation increments MINOR and BUILD in `android/version.properties`, even
if compilation fails. The helper runs core and host serialization tests and creates a stamped
arm64 development APK in `dist/android`, printing its SHA-256. This is a debug-signed
development build, not a production release. To repeat verification without a
new build identity, use `android/gradlew -p android :core:test :app:testDebugUnitTest :app:assembleDebug`.

## Capture and revisit

Install the APK on an ARCore-capable Android device. Open Recruiser Capture,
allow camera access, and install/update Google Play Services for AR if requested.
Use Start, Pause/Resume and Finish; recordings stay in the app's private storage.
Do not uninstall or clear application data before exporting captures.

Select a saved session to replay its original dataset or export a capture package.
Build 3D on this device fuses valid depth observations from a selected world frame
into a bounded coloured point cloud. Interrupted jobs resume from a checkpoint
bound to the original observations. Revisit opens the saved PLY with orbit/zoom.
Independent tracking origins remain separate; this release does not automatically
register segments after a reset. Without usable depth, recordings remain available
for replay/export, but this depth-fusion algorithm cannot reconstruct them.

The browser's Capture surroundings entry explains native capture and opens a
capture package. It does not promise native ARCore through browser permissions.
Record fly-through records the displayed 3D scene and is available only with
rendered layers. Capture packages are distinct from existing scene project ZIPs.

## Evidence and limits

The exact portable schema and timestamp conventions are in CHECKLIST N1–N4.
Camera/IMU clock alignment is declared only when the device exposes a verified
shared timestamp source. Missing calibration, stale depth, tracking loss and gaps
remain explicit. Image and overlap cues are provisional heuristics, not final
surface-completeness measurements. Location is optional and not collected in this
build; audio and Gaussian-splat refinement are deferred.

Vulkan accelerates depth unprojection. Kotlin performs bounded voxel fusion and
checkpointing; the tested CPU unprojector handles unavailable or failed Vulkan.
The displayed backend reports the path actually exercised. A compiled shader does
not establish Fold5 performance. Host Vulkan/reference parity can be run with:

```sh
cmake -S android/app/src/main/cpp -B android/app/build/host-vulkan
cmake --build android/app/build/host-vulkan
ctest --test-dir android/app/build/host-vulkan --output-on-failure
```

The same standalone parity test can run on an attached Android device without
camera access. This is an operator verification protocol, not a repeatable CI gate:

```sh
cmake -S android/app/src/main/cpp -B android/build/vulkan-android-test -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_HOME/ndk/27.0.12077973/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static \
  -DRECRUISER_PARITY_TEST=ON
cmake --build android/build/vulkan-android-test
adb shell mkdir -p /data/local/tmp/recruiser-verification
adb push android/build/vulkan-android-test/recruiser_depth /data/local/tmp/recruiser-verification/vulkan-parity
adb shell /data/local/tmp/recruiser-verification/vulkan-parity
```

Observed 2026-09-13: this test passed on the connected Galaxy Z Fold5 (SM-F946W,
Android 16/API 36), reporting **Adreno (TM) 740**. All 1,073 synthetic samples
matched within 0.00001 metres over two dispatches, and invalid dimensions were
rejected. The same test passed on the workstation's NVIDIA RTX 4090 Laptop GPU.
This verifies unprojection correctness, not reconstruction throughput, room
accuracy, sustained temperature or energy consumption.

Device verification must cover preview orientation, actual saved resolution,
timestamp associations, pause/background/recovery, exported package inspection,
local reconstruction/revisit, fold/rotation controls and sustained thermal load.
The capture originals are never replaced with computed geometry or replayed poses.
