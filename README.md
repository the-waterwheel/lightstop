# lightstop（光档）

English | [简体中文](README_ZH.md)

lightstop is an Android reflected-light meter for manual exposure and film
photography. It uses Camera2 for the live preview, prefers `RAW_SENSOR` data,
measures Bayer samples in C++, and calculates EV100, aperture/shutter
relationships, and Zone System placement in Kotlin. When RAW is unavailable,
it automatically falls back to metering the ISP-processed preview.

The app does not request Internet or shared-storage access and does not include
analytics or advertising SDKs. It can save a user-requested parameter record in
app-private storage, including a viewfinder JPEG, optional DNG and compact RAW
metering grid, exposure data, notes, and an optional authorized location. Android or device-manufacturer
backup and transfer services can copy that private data according to the user's
system settings; the app and its developer do not upload or access those copies.

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
- Camera2 logical and physical camera discovery, selection, labels, and hiding;
  default selection prefers back-camera routes that advertise usable RAW, with
  automatic/main non-RAW routes retained as fallbacks.
- A common advertised 4:3 preview is preferred for logical and physical routes,
  avoiding viewport aspect changes when Automatic camera and Main camera use the same lens.
- RAW-first metering with a capability-driven ISP-preview fallback. LEGACY and
  unsupported RAW CFA devices are treated as non-RAW. A fixed physical lens is
  attempted independently of a logical camera's `APPROXIMATE` sync declaration;
  API 29+ logical routes also track the active physical lens reported per frame.
- Three bilingual metering modes selected through a per-camera workflow matrix:
  **High accuracy (recommended)** tries RAW workflows from highest precision to
  lowest stream pressure, then falls back to Stable YUV and Compatibility ISP;
  **Stable** uses preview + YUV without RAW; **Compatibility mode** uses only
  the displayed ISP preview.
- `Metering combination selection` can use the automatic system probe or a
  full-screen manual check. The manual path tries the same prefiltered workflows
  in precision order and asks the user to confirm that each real preview flow is
  free from flicker, stalls, black/green frames, and stripes. A confirmed result
  is scoped to the same camera route and OS build.
- Stream-combination tables and output-count limits are used only for conservative
  prefiltering. Unknown combinations still reach a real Camera2 configuration;
  a vendor HAL's negative preflight answer is advisory because some vivo/MediaTek
  builds reject the query while successfully creating the same session.
- Formal RAW, YUV, DNG, colour-temperature, and vignetting operations accept
  only exact `Image`/`CaptureResult` sensor-timestamp pairs. Displayed-preview
  fallback verifies a matching `SurfaceTexture` timestamp before and after its
  bitmap capture, otherwise it rejects the sample rather than using stale metadata.
- Bayer black-level subtraction, white-level normalization, per-channel median
  statistics, clipping detection, white-balance/color-matrix conversion, and
  EV100 calculation.
- RAW uses one frame below ISO 500, two frames from ISO 500 through 1199, and
  three frames at ISO 1200 or above. Only one full-size RAW image is in flight
  at a time, reducing delay and motion error.
- Single-frame preview metering: try up to three ISP-processed YUV frames for
  at most 250 ms, then try a strictly timestamp-paired displayed-preview sample.
- `General` → `Viewfinder frame rate` defaults to Low, preserving the existing
  advertised range at or below 30 fps. High tries an advertised regular-session
  range up to 60 fps only when the active preview/YUV stream durations allow it;
  rejection falls back through 30 fps, 24 fps, and the HAL default without
  downgrading the selected RAW/YUV/ISP workflow.
- Manual exposure preview prioritizes a responsive shutter (normally at least
  1/30 s, no slower than 1/15 s in low light) and raises ISO first. Metering
  restores neutral AE before sampling so an exposure-preview frame is never
  mistaken for a formal reading.
- High-accuracy Zone tracking keeps preview + YUV resident, briefly switches to
  an isolated RAW-only session for the 1–3 metering frames, and then restores
  preview + YUV. A second high-accuracy workflow can use the same isolated RAW
  window for ordinary metering on HALs that cannot keep preview + RAW resident.
- Camera-session recovery and workflow search reduce stream pressure from RAW
  workflows to Stable YUV, Compatibility ISP, and finally a logical-camera route
  when appropriate.
- Bounded preview-health detection for repeated periodic green/black-white
  stripe failures. A recovered safe preview must pass three further samples;
  this does not classify real green or dark scenes as camera failures.
- Spot and center-weighted metering.
- Per-camera calibration separately displays **RAW sensor**, **YUV compatible stream**, and
  **ISP display preview** corrections. One run calibrates every source the hardware can
  provide, regardless of the currently selected metering mode: RAW → YUV → ISP when all
  three exist, or the supported subset otherwise. Stages are sequential, never concurrent,
  and the labelled legacy shared correction is only a fallback while a processed source awaits recalibration.
  Each stage uses its smallest safe Camera2 session and restores the user's normal session after it.
  The selected camera route and calibration storage identity are pinned for the whole run. A logical
  camera may temporarily omit its active physical-camera ID while a new session opens; that transient
  omission does not abort calibration, but two different concrete physical IDs still do.
- Switching to Compatibility mode shows a bilingual accuracy notice, with a permanent
  “Don't show again” choice.
- Two-dimensional vignetting calibration for RAW-capable cameras, with an
  uncropped full-stream preview and persistent access to its action/history UI.
- Electronic preview crop and matching metering ROI without requesting Camera2
  digital zoom or silently switching lenses. Front-camera preview mirroring is
  inverted again before RAW touch metering or saved RAW-grid lookup.

### Exposure instrument

- A custom-drawn black-and-white mechanical meter UI with restrained red
  accents.
- Exposure compensation in selectable 1/6, 1/3, 1/2, or 1 EV steps.
- Aperture and shutter scales in full, half, or third stops.
- Aperture or shutter locking while preserving the continuous exposure
  relationship of the dependent scale.
- The reciprocity calculator caps ordinary corrected results at 24 hours,
  retains a manufacturer-defined exact-data cutoff when that data explicitly
  extends further, and labels multi-part results with `h`, `min`, and `s`.
- Chinese and English menus, light and dark themes, and complete right- or
  left-handed layouts.
- A bilingual About screen with the application version, AI-assisted
  development disclosure, OpenCV attribution, and a clear metering-result
  notice for important or non-repeatable work.
- An in-app open-source license browser under About. Complete bundled license
  and attribution texts remain accessible without extracting the APK.
- 135, half-frame, 6×4.5, 6×6, 6×7, 6×9, 6×12, 6×17, 65:24, 4×5, 5×7,
  and 8×10 frame formats. The long edge remains horizontal in the viewfinder.
- Focal-length guidance is converted to the selected film format using its
  representative image diagonal; the selector wraps into rows instead of
  compressing labels, and the preview is never stretched. The readout is
  anchored to the viewfinder's bottom-right corner and shows only the
  equivalent focal length.

### Zone System

- Button-and-touch or button-only point placement; both methods keep the
  mark button, and button-and-touch additionally places points by tapping
  the preview.
- Independent EV values and Zone 0–X placement for multiple points.
- Marker dots ignore preview taps; points are removed from the record list
  with a horizontal swipe or the clear control.
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
- Saved-record Zone replay is enabled only when its compact RAW metering grid
  was recorded; historical point evidence remains visible but read-only without it.
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
  settings; Zone mode has the same gear at its viewfinder's bottom-left corner.
- In `Metering combination selection`, keep `System` for automatic matrix and
  health probing, or choose `Manual` to inspect candidate workflows yourself.
  If no candidate has finished preflight yet, wait for the camera to become
  ready and select Manual again.
- Tap Tools and choose Depth of field to calculate animated near/focus/far
  limits from the current frame, field of view, and metering aperture; frame
  size and circle of confusion can also be selected or entered manually.
- The current Tools grid contains depth of field, latitude, parameter log,
  reciprocity, and color-temperature estimation. Flash-index and exposure-
  correction identifiers remain reserved internally but are hidden until those
  tools are complete.
- Open `General` → `About` for the metering-result notice; the open-source
  license browser is available after the About text.
- High viewfinder frame rate can increase power, heat, and YUV tracking work,
  and may shorten AE exposure in low light. Low remains the compatibility-first
  default; neither option changes metering math or calibration data.

## Project structure

The project contains one Android application module and one activity. It does
not use Compose, Fragment, AndroidX, a database, or a network layer. The
interface is rendered with custom `ViewGroup`, Canvas, and `ValueAnimator`
code.

```text
app/src/main/java/com/lightmeter/rawmeter
├─ MainActivity.kt                   lifecycle, permissions, coordination
├─ CameraController.kt               lifecycle and camera-operation facade
├─ CameraSessionCoordinator.kt       Camera2 resources, sessions, open/close
├─ RawLightMeter.kt                  bounded RAW capture and metering
├─ CompatibleLightMeter.kt           YUV/displayed-preview metering
├─ TimestampedResultPairer.kt        image/result ownership and pairing
├─ CameraRecoveryPolicy.kt           session profiles and recovery decisions
├─ CameraRecoveryStateMachine.kt     retry history and route downgrade state
├─ CameraCombinationPolicy.kt        workflow classes, ordering, and fallback
├─ CameraCombinationMatrix.kt        mandatory-matrix/output-count prefilter
├─ CameraCombinationSelectionStore.kt per-camera/OS accepted-workflow cache
├─ CompatibleMeteringPolicy.kt       single-frame compatibility limits
├─ CameraCatalog.kt                  logical/physical camera discovery
├─ CameraStreamSelector.kt           preview, tracking stream, and FPS choice
├─ CameraPreviewTransform.kt         preview orientation and crop transform
├─ ScreenToSensorCoordinateTransform.kt  shared front-mirror/rotation mapping
├─ PreviewHealthAnalyzer.kt          bounded green/stripe/frozen-frame analysis
├─ PreviewHealthSampler.kt           UI-thread preview health sampling
├─ ParameterRecordTransaction.kt     crash-recovery journal for record commits
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
├─ CameraCombinationSelectionView.kt full-screen human preview check
├─ MeteringCalibrationPlan.kt        source-complete calibration plan
├─ MeteringCalibrationCoordinator.kt sequential calibration state machine
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

See [Camera pipeline and device compatibility](docs/CAMERA_PIPELINE.md) for
the stream profiles, fallback order, resource-ownership rules, known vendor
boundaries, and the component boundaries around `CameraController`.

The package name and Android application ID remain
`com.lightmeter.rawmeter` so existing development installations can upgrade
without losing their private calibration and settings data. They are internal
compatibility identifiers; the user-facing product name is lightstop / 光档.

## Build requirements

- JDK 17
- Android SDK 36.1 (target API 36; min API 28)
- Android Gradle Plugin 8.13.2
- Kotlin 2.1.20
- Gradle 8.13 (checked-in wrapper)
- Android NDK 27.0.12077973
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

For version 0.2.2 with the slimmed r2 OpenCV runtime, the verified unsigned
universal APK is 42,102,352 bytes (40.15 MiB), and the release AAB is
18,660,835 bytes (17.80 MiB). Native libraries dominate the universal APK;
R8 reduces the compressed DEX payload to about 1.07 MiB. The same build with the
previous r1 OpenCV runtime was 76,056,239 bytes (72.53 MiB) for the universal
APK and 32,959,091 bytes (31.43 MiB) for the AAB.

The repository deliberately contains no signing key or signing password. The
APK must be signed before installation or distribution. Android Studio's
**Generate Signed Bundle / APK** wizard is optional; the same operation can be
performed with Android SDK `zipalign` and `apksigner`. Keep the release
keystore offline and backed up. Never commit `*.jks`, `*.keystore`, signing
passwords, or `keystore.properties`.

For a command-line GitHub release on Windows, first build the unsigned artifact,
then align and sign it. Omitting password arguments makes `apksigner` prompt
interactively instead of storing a secret in shell history:

```powershell
$buildTools = (Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" -Directory |
  Sort-Object Name -Descending | Select-Object -First 1).FullName
New-Item -ItemType Directory -Force dist | Out-Null
& "$buildTools\zipalign.exe" -f -p 4 `
  app\build\outputs\apk\release\app-release-unsigned.apk `
  dist\lightstop-v0.2.2-aligned.apk
& "$buildTools\apksigner.bat" sign `
  --ks C:\secure\lightstop-release.jks `
  --ks-key-alias lightstop `
  --out dist\lightstop-v0.2.2-universal.apk `
  dist\lightstop-v0.2.2-aligned.apk
& "$buildTools\apksigner.bat" verify --verbose --print-certs `
  dist\lightstop-v0.2.2-universal.apk
Get-FileHash dist\lightstop-v0.2.2-universal.apk -Algorithm SHA256 |
  Format-List Algorithm, Hash, Path
```

Do not delete or replace the keystore after the first public release: future
APK updates must be signed by the same key. Back up the keystore and its alias
and passwords in separate secure locations. Before publishing, commit the
source, create an annotated tag, and push both commit and tag:

```powershell
git status
git tag -a v0.2.2 -m "lightstop 0.2.2"
git push origin HEAD:main
git push origin v0.2.2
```

On GitHub, create a Release from `v0.2.2`, retain the generated source archives,
and upload the signed universal APK, a text file containing its SHA-256, plus
`LICENSE`, `NOTICE`, and `THIRD_PARTY_NOTICES.md`. Verify the uploaded APK after
downloading it once; a GitHub Release is a distribution record, while the
signed APK and the protected signing key are the continuity record for updates.

Use an Android App Bundle for an app store so the store can deliver only the
device's ABI. An AAB is a publishing format and cannot be installed directly;
the store generates per-device APKs from it, so users download only their own
architecture (about 15 MiB per ABI for this app). For GitHub Releases, attach
the signed universal APK and its SHA-256 checksum, `LICENSE`, `NOTICE`, and
`THIRD_PARTY_NOTICES.md`. The universal APK installs on any supported device
but carries all three ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`); per-ABI
APKs are about a third of that size but must be matched to the device. Do not
commit generated release files to the source tree.

## Pinned slim OpenCV

The application uses
`app/libs/opencv-slim-4.12.0-r2.aar`, built from the official OpenCV 4.12.0
source instead of resolving a changing Maven dependency. It retains the
modules needed by lightstop and the official Android Java glue, and includes
`arm64-v8a`, `armeabi-v7a`, and `x86_64`. Acceleration and codec backends the
application never calls (IPP, TBB, KleidiCV, ITT, OpenJPEG, TIFF, WebP,
OpenEXR, AVIF, Jasper) are disabled in this revision.

```text
Size: 33,514,507 bytes
SHA-256: 321C84621FE818E35CC7B6401953039BC784E4FE4CFB9D35690B4DBEDF4D57CD
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

- Camera permission; location is optional and used only for user-enabled
  parameter records.
- No Internet or shared-storage permission.
- No analytics or advertising SDK.
- User-requested parameter records can retain a viewfinder JPEG, optional DNG,
  exposure data, notes, and an optional authorized location in app-private storage.
- System backup or device-transfer services may copy final records and calibration
  data according to the user's settings; large DNG files are not guaranteed to fit.
- Zone points are session-only and are not persisted.

See [PRIVACY.md](PRIVACY.md) for the bilingual privacy statement.

## Testing and device compatibility

Pure logic is covered by unit tests for handedness-sensitive mode transitions,
camera recovery, strict result pairing, RAW-CFA eligibility, preview-health
analysis, parameter-record recovery, front/back rotation and mirroring,
deferred tracker creation, buffer reuse and ownership, stride-aware Y-plane
copying, layout coordinate stability, and exposure-compensation behavior.

Camera2, RAW streams, logical/physical camera combinations, vendor-specific
sensor metadata, tracking quality, and layout changes must also be verified on
physical devices. Calibration is intentionally per physical camera identity because phone
cameras and vendor pipelines differ. RAW, paired YUV, and displayed ISP-preview corrections
are separate. A legacy shared preview correction remains only as a labelled fallback until the
processed sources have been recalibrated.

The current candidate was exercised on a vivo V2405A running Android 16. Its
logical automatic camera, ultrawide, main, telephoto, and front routes were
enumerated; an isolated RAW workflow completed and restored the resident YUV
session. This is one compatibility data point, not a substitute for a wider
manufacturer/API-level device matrix.

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
