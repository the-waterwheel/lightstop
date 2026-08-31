# Changelog

All notable user-facing changes are recorded here. The project follows
[Semantic Versioning](https://semver.org/) for public releases.

## Unreleased

- Added a General viewfinder-frame-rate preference. Low remains the default
  and preserves the existing 30 fps ceiling; High can try advertised regular
  session ranges up to 60 fps when current stream durations permit it. FPS
  rejection falls back through 30/24 fps and the HAL default without changing
  the selected RAW/YUV/ISP workflow.

## 0.2.2 - 2026-08-31

- Added per-camera Camera2 workflow-matrix search with ordered RAW split,
  fully isolated RAW, full multi-stream, Stable YUV, and Compatibility ISP
  candidates. Unknown mandatory-matrix results still receive a real session
  test; vendor preflight `false` results are advisory.
- Added System and Manual metering-combination selection. Manual selection runs
  every required session stage and lets the user judge flicker, stalls,
  black/green frames, and stripes; accepted results expire after an OS build
  or camera-route change.
- Defined Stable as preview + YUV without RAW and Compatibility as displayed
  ISP preview without RAW/YUV. High accuracy now falls back through those
  classes only after all RAW workflows fail.
- Added transient isolated RAW capture for Zone and constrained ordinary
  metering, restoring the resident preview + YUV workflow afterward.
- Made one calibration run cover every hardware-supported RAW, YUV, and ISP
  source independently of the selected user mode.
- Relaxed automatic preview-health classification so green, near-black, and
  frozen scenes remain suspicious rather than immediate failures; added manual
  safe-preview selection and a setting to disable automatic health detection.
- Fixed a vivo/MediaTek interoperability failure where
  `isSessionConfigurationSupported()` reported false after isolated RAW even
  though recreating the same processed session succeeded.

- Rebuilt processed-stream luminance around the Camera2 output model: YUV
  metering now reconstructs sRGB from all Y/U/V planes, honors reported
  full/limited range and color matrices, inverts usable per-frame tonemap
  curves, and uses the same linear-luminance median statistic as ISP/RAW.
  Older processed-stream calibration offsets are invalidated while RAW
  calibration remains valid.
- Corrected public camera/backup disclosures to match optional GPS records,
  JPEG/DNG storage, and Android or vendor backup/device-transfer behavior.
- Made formal metering and recording use exact sensor-timestamp pairs; stale
  displayed-preview metadata is rejected.
- Allowed fixed physical-lens routes independent of logical-camera
  `APPROXIMATE` sync, tracked active physical lenses where Android reports them,
  and rejected unsupported RAW CFA layouts from Bayer processing.
- Added bounded preview stripe recovery with safe-preview confirmation, plus
  front-camera mirror-aware RAW coordinate mapping and crash-recoverable
  parameter-record commits.

- Added the Tools panel framework: a button beside Settings opens a
  scrollable three-column tools grid (depth of field, latitude, parameter
  log, reciprocity, color temperature)
  that replaces the parameter area in Normal and Zone modes while the
  viewfinder stays interactive. Flash-index and exposure-correction identifiers
  remain reserved for later versions, without exposing unfinished entries.
- Implemented the depth-of-field tool with format and circle-of-confusion
  presets/custom values, metering-step aperture and logarithmic focus dials,
  animated near/focus/far markers, hyperfocal distance, and matching
  portrait/landscape, Normal/Zone, and left-handed panel transitions.
- Keep the active Tools page open underneath Settings and restore it on Back;
  Normal/Zone mode handles are disabled while the Tools panel is open.
- Added a settings button to the Zone overlay at the viewfinder panel's
  bottom-left corner; calibration, vignetting, and camera-management actions
  exit Zone first.
- Anchored the focal-length readout to the viewfinder's bottom-right corner
  in every mode and show only the selected frame format's equivalent focal
  length.
- Required exact sensor-timestamp image/result pairing for formal metering and
  recording; unmatched frames are rejected instead of borrowing adjacent
  exposure metadata.
- Attempt fixed physical-camera routes even when the logical camera reports
  APPROXIMATE synchronization, because that declaration describes concurrent
  sensors rather than prohibiting one physical output.
- Treat RAW as unavailable on LEGACY hardware-level devices even when they
  advertise RAW output.
- Use the advertised RAW minimum frame duration for RAW capture requests
  instead of reusing the preview frame duration, which some HALs reject or
  clamp silently.
- Renamed Zone marking methods to **Button and touch** (按键与触屏) and
  **Button only** (仅按键). Both methods keep the mark button; Button and
  touch additionally places points by tapping the preview, reusing the
  RAW-side template matching for touch points.
- Removed Zone marker tap selection and double-tap deletion; marker dots are
  inert and are removed only from the record list.
- Rebuilt the pinned OpenCV runtime without the unused IPP, TBB, KleidiCV,
  ITT, and optional codec backends, shrinking the universal APK from about
  72.5 MiB to about 39.8 MiB with no behavior change.
- Matched YUV buffers to Camera2 exposure metadata by identical sensor timestamps.
- Added a one-time device/camera-environment change warning; old metering and
  vignetting corrections remain stored but become unavailable until recalibrated.
- Added recoverable camera-permission denial handling and a system-settings route.
- Made OpenCV initialization fail safely into fixed Zone markers before any native
  tracker object is created.
- Added conservative Camera2 mandatory-stream/output-count prefiltering and
  real workflow configuration probes while continuing to prefer every camera
  that advertises a usable RAW stream.
- Updated the app build to API 36.1 / target 36 while retaining min API 28, and
  updated native build inputs for 16 KB page-size compatibility.
- Renamed the user modes to **High accuracy (recommended)**, **Stable**, and
  **Compatibility mode**, without changing persisted enum values or old preference
  migration.
- Split calibration results into **RAW sensor**, **YUV compatible stream**, and
  **ISP display preview**; every hardware-supported source is calibrated
  sequentially regardless of the current metering mode.
- Added a bilingual Compatibility-mode RAW notice with **OK** and a persistent
  **Don't show again** action.
- Reduced RAW sampling to one frame below ISO 500, two below ISO 1200, and three
  at ISO 1200 or above to reduce measurement delay and motion error.
- Added 6×12, 6×17, 4×5, 5×7, and 8×10 formats, selected-format focal-length
  equivalence, and wrapped format menus that keep every option readable.
- Added source-complete sequential calibration; cameras without RAW skip only
  the RAW stage while retaining independent YUV and ISP corrections.
- Added bilingual compatibility mode settings and camera-error recovery across
  full, RAW-only, YUV-compatible, preview-only, and logical-camera routes.
- Reworked metering into three user modes: RAW-first high accuracy, RAW-free
  Stable YUV, and minimum-output Compatibility ISP. Existing compatible-mode
  preferences migrate to the current Compatibility mode.
- Fixed vignetting-calibration geometry refresh so its camera preview, action
  button, and correction history remain aligned and visible after camera/session
  or orientation changes; simplified the bilingual guide text.
- Unified automatic logical cameras and fixed physical lenses on a common 4:3
  preview stream when advertised, preventing aspect changes or apparent stretching
  when Automatic camera and Main camera are backed by the same lens.
- Limited explicit preview requests to safe advertised ranges at or below
  30 fps, with automatic 24 fps and system-default fallback when rejected.
- Made resident outputs workflow-specific: Stable and high-accuracy Zone paths
  keep YUV, constrained RAW paths use a short isolated capture, and repeating
  requests pause while resident RAW or vignetting captures are in progress.
- Added an automatic logical-camera entry as the default on multi-camera phones;
  fixed physical lenses remain selectable and monochrome/NIR sensors are hidden.
- Changed compatible metering to one ISP-processed frame, with a maximum
  three-frame/250 ms YUV attempt before a single preview fallback.
- Limited RAW capture to one full-size image in flight and explicitly cancel
  completed RAW, YUV, and vignetting timeout callbacks.
- Fixed background interruption so an in-progress measurement or calibration
  does not remain permanently displayed as measuring.
- Added half-frame naming/layout corrections and expanded bilingual UI text.
- Split `CameraController` into a session coordinator, RAW meter, compatible
  meter, timestamp result pairer, and recovery state machine, with documented
  thread and resource ownership.
- Documented camera stream profiles, resource ownership, vendor boundaries,
  and the resulting controller component boundaries.

## 0.2.1 - 2026-08-12

- Added a compact **About** entry under General settings.
- Added bilingual application, AI-assisted development, OpenCV 4.12.0, and
  metering-result notices to the About screen.
- Added an in-app open-source license browser as a child page at the end of
  About, with the complete bundled third-party license and attribution texts.
- Added explicit guidance to verify important or non-repeatable work with a
  calibrated professional light meter or another independent method.
- Kept large license documents lazy-loaded and released their cached text and
  layouts when the information screen closes.

## 0.2.0 - 2026-08-11

- Renamed the application to **光档** in Chinese and **lightstop** in English.
- Replaced the branded panoramic format name with the generic **65:24** ratio.
- Switched to the pinned, locally built OpenCV 4.12.0 runtime.
- Fixed Zone marker rotation during layout-only portrait/landscape transitions.
- Deferred OpenCV tracker creation until Zone mode is first opened.
- Added backpressure and a reusable three-buffer pool to the Zone YUV pipeline.
- Added selectable 1/6, 1/3, 1/2, and 1 EV exposure-compensation steps.
- Enabled R8 code shrinking and Android resource shrinking for release builds.
- Added Apache-2.0 project licensing and bundled third-party notices.
- Sanitized user-profile paths from OpenCV's embedded build-information string.

## 0.1.2

- Last internal prototype release under the previous working title.
