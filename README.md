# lightstop（光档）

English | [简体中文](README_ZH.md)

lightstop is an Android reflected-light meter for manual exposure and film
photography. It uses Camera2 for the live preview, prefers `RAW_SENSOR` data,
measures Bayer samples in C++, and calculates EV100, aperture/shutter
relationships, and Zone System placement in Kotlin. When RAW is unavailable,
it automatically falls back to metering the ISP-processed preview.

The app does not take or save photographs and does not request network,
location, or shared-storage access. Metering, settings, and calibration data
remain on the device.

## Development approach

This project was created through a vibe coding workflow with AI assistance.
AI participated in design discussions, implementation, refactoring,
documentation, and test preparation. Release candidates are built, inspected,
and tested on a physical Android device, but contributors and users should
still independently review critical camera, metering, privacy, and security
code.

## Features

### Camera and metering

- Android 9 / API 28 or newer.
- Camera2 logical and physical camera discovery, selection, labels, and hiding.
- RAW-first metering with an explicit ISP-preview compatibility fallback.
- Timestamp pairing between `Image` and `CaptureResult`.
- Bayer black-level subtraction, white-level normalization, per-channel median
  statistics, clipping detection, white-balance/color-matrix conversion, and
  EV100 calculation.
- Three-frame fusion at ISO 800 or below and five-frame fusion above ISO 800.
- Spot and center-weighted metering.
- Per-camera metering calibration and two-dimensional vignetting calibration.
- Electronic preview crop and matching metering ROI without requesting Camera2
  digital zoom or silently switching lenses.

### Exposure instrument

- A custom-drawn black-and-white mechanical meter UI with restrained red
  accents.
- Exposure compensation in selectable 1/6, 1/3, 1/2, or 1 EV steps.
- Aperture and shutter scales in full, half, or third stops.
- Aperture or shutter locking while preserving the continuous exposure
  relationship of the dependent scale.
- Chinese and English menus, light and dark themes, and complete right- or
  left-handed layouts.
- A bilingual About screen with the application version, AI-assisted
  development disclosure, OpenCV attribution, and a clear metering-result
  notice for important or non-repeatable work.
- An in-app open-source license browser under About. Complete bundled license
  and attribution texts remain accessible without extracting the APK.
- 135, half-frame, 6×4.5, 6×6, 6×7, 6×9, and generic 65:24 frame formats. The
  long edge remains horizontal in the viewfinder.

### Zone System

- Button-based or touch-based point placement.
- Independent EV values and Zone 0–X placement for multiple points.
- Low-resolution YUV luminance tracking with preview-screenshot fallback.
- Pyramidal Lucas–Kanade optical flow, forward/backward validation, RANSAC
  affine motion, local feature correction, gyroscope prediction, and ORB
  re-identification.
- Stable point coordinates when the portrait/landscape control changes only
  the page layout and does not rotate the camera stream.
- Off-screen virtual coordinates so points can be recovered when the camera
  returns to the scene.
- Deferred OpenCV initialization: native tracking resources are created only
  when Zone mode is first entered.
- Camera-thread backpressure and a reusable three-slot Y-plane buffer pool to
  avoid allocating a full frame-sized `ByteArray` for every incoming frame.

## Basic operation

- Rotate the large dial to adjust exposure compensation at the configured step.
  Positive ticks are above the reference and negative ticks are below it.
- Tap `ISO` beside the dial to switch between ISO and exposure-compensation
  adjustment.
- Tap the frame-format control to choose a film frame.
- Drag the viewfinder-side control for electronic crop zoom.
- Drag the exposure lock upward for aperture or downward for shutter.
- Drag the locked scale horizontally to change the locked parameter.
- Tap the red outlined meter button to update the exposure reading.
- Drag the `zone` handle into the page to enter Zone System mode.
- Tap the gear for metering, general, camera-management, and calibration
  settings.
- Open `General` → `About` for the metering-result notice; the open-source
  license browser is available after the About text.

## Project structure

The project contains one Android application module and one activity. It does
not use Compose, Fragment, AndroidX, a database, or a network layer. The
interface is rendered with custom `ViewGroup`, Canvas, and `ValueAnimator`
code.

```text
app/src/main/java/com/lightmeter/rawmeter
├─ MainActivity.kt                   lifecycle, permissions, coordination
├─ CameraController.kt               Camera2 sessions and capture scheduling
├─ CameraCatalog.kt                  logical/physical camera discovery
├─ CameraStreamSelector.kt           preview, tracking stream, and FPS choice
├─ CameraPreviewTransform.kt         preview orientation and crop transform
├─ MeterModels.kt                    state, exposure scales, persistence
├─ MeteringAnalysis.kt               RAW/ISP analysis, ROI, EV conversion
├─ MeteringFusion.kt                 robust multi-frame fusion
├─ MeterLayout.kt                    main page and camera-preview composition
├─ LayoutGeometry.kt                 normal-mode geometry
├─ InstrumentPresentation.kt         pure presentation decisions
├─ InstrumentExposureRenderer.kt     exposure scales and locks
├─ InstrumentView.kt                 normal meter drawing and gestures
├─ SettingsCatalog.kt                settings definitions
├─ SettingsView.kt                   settings interface
├─ CameraCalibrationStore.kt         per-camera calibration history
├─ VignettingCalibrationStore.kt     vignetting map persistence
├─ ZoneCoordinateMapper.kt           UI/preview/OpenCV coordinate mapping
├─ DeferredZoneMarkerTracker.kt      lazy native tracker creation
├─ ZoneTrackingFrames.kt             reference-counted three-buffer pool
├─ ZoneCameraFramePipeline.kt        YUV backpressure and frame ownership
├─ ZoneOpenCvFramePreprocessor.kt    YUV orientation and downscaling
├─ ZoneGyroscopeMotion.kt            motion prediction and visual calibration
└─ ZoneTrackingEngine.kt             optical flow and re-identification

app/src/main/cpp
├─ raw_meter.cpp                     Bayer RAW median statistics
└─ CMakeLists.txt                    JNI native-library build
```

The package name and Android application ID remain
`com.lightmeter.rawmeter` so existing development installations can upgrade
without losing their private calibration and settings data. They are internal
compatibility identifiers; the user-facing product name is lightstop / 光档.

## Build requirements

- JDK 17
- Android SDK 34
- Android Gradle Plugin 8.2.2
- Kotlin 1.9.22
- Gradle 8.7 (checked-in wrapper)
- Android NDK 25.1.8937393
- CMake 3.22.1
- OpenCV 4.12.0

Android Studio is optional. From PowerShell:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Release build

Release builds enable R8 code shrinking and Android resource shrinking.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease :app:bundleRelease
```

Outputs:

```text
app/build/outputs/apk/release/app-release-unsigned.apk
app/build/outputs/bundle/release/app-release.aab
```

For version 0.2.0, the verified unsigned universal APK is 76,056,239 bytes
(72.53 MiB), and the release AAB is 32,959,091 bytes (31.43 MiB). Native
libraries dominate the universal APK; R8 reduces the DEX payload to about
0.71 MiB.

The repository deliberately contains no signing key or signing password. The
APK must be signed before installation or distribution. Android Studio's
**Generate Signed Bundle / APK** wizard is optional; the same operation can be
performed with Android SDK `zipalign` and `apksigner`. Keep the release
keystore offline and backed up. Never commit `*.jks`, `*.keystore`, signing
passwords, or `keystore.properties`.

Use an Android App Bundle for an app store so the store can deliver only the
device's ABI. For GitHub Releases, attach a signed APK, its SHA-256 checksum,
`LICENSE`, `NOTICE`, and `THIRD_PARTY_NOTICES.md`. Do not commit generated
release files to the source tree.

## Pinned slim OpenCV

The application uses
`app/libs/opencv-slim-4.12.0-r1.aar`, built from the official OpenCV 4.12.0
source instead of resolving a changing Maven dependency. It retains the
modules needed by lightstop and the official Android Java glue, and includes
`arm64-v8a`, `armeabi-v7a`, and `x86_64`.

```text
Size: 63,882,947 bytes
SHA-256: 0A5C95F697D63C94F87D0B3CBAC8ACB61046D089BCEBCF25BF307A0F796767D0
```

See [the reproducible OpenCV build guide](tools/opencv-slim/README.md) for the
pinned source checksum, build matrix, modules, and upgrade procedure.

The AAR contains third-party components under several permissive licenses and
the Intel Simplified Software License for applicable prebuilt IPP binaries. It
must not be described as a single Apache-2.0 binary. Exact notices are in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and are also packaged inside
the application under `assets/licenses/`.

The AAR is below GitHub's 100 MiB hard per-file limit but is large enough to
make repository clones heavier. A future release may move it to a versioned
GitHub Release asset with a checksum-verified download task. Until that
migration is implemented, keeping it in the repository preserves offline and
reproducible application builds.

## Privacy and permissions

- Camera permission only.
- No Internet, location, or shared-storage permission.
- No analytics or advertising SDK.
- No photo capture or persistence.
- Preferences and calibration data stay in app-private storage.
- Zone points are session-only and are not persisted.

See [PRIVACY.md](PRIVACY.md) for the bilingual privacy statement.

## Testing and device compatibility

Pure logic is covered by unit tests for handedness-sensitive mode transitions,
deferred tracker creation, buffer reuse and ownership, stride-aware Y-plane
copying, layout coordinate stability, and exposure-compensation behavior.

Camera2, RAW streams, logical/physical camera combinations, vendor-specific
sensor metadata, tracking quality, and layout changes must also be verified on
physical devices. Calibration is intentionally per camera because phone
cameras and vendor pipelines differ.

## License

Copyright 2026 lightstop contributors.

Project-owned source code, documentation, and the original launcher icon are
licensed under the [Apache License 2.0](LICENSE). Third-party components retain
their own licenses; see [NOTICE](NOTICE) and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

The same notices are bundled in the APK and can be read from `General` →
`About` → `Open-source licenses`.

Product and dependency names are used only to identify their origin. No
endorsement by their respective owners is implied.
