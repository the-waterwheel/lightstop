# Changelog

All notable user-facing changes are recorded here. The project follows
[Semantic Versioning](https://semver.org/) for public releases.

## Unreleased

- Added 6×12, 6×17, 4×5, 5×7, and 8×10 formats, selected-format focal-length
  equivalence, and wrapped format menus that keep every option readable.
- Added sequential RAW and compatible-preview calibration for RAW-capable
  cameras, while compatible-only cameras skip RAW calibration.
- Added bilingual compatibility mode settings and camera-error recovery across
  full, RAW-only, YUV-compatible, preview-only, and logical-camera routes.
- Reworked metering into three user modes: recommended high accuracy, strictly
  isolated compatibility, and fast ISP metering. Existing compatible-mode
  preferences migrate to fast mode so upgrades retain their previous behavior.
- Limited explicit preview requests to safe advertised ranges at or below
  30 fps, with automatic 24 fps and system-default fallback when rejected.
- Stopped targeting YUV continuously: it is now attached only for Zone tracking
  or one compatible sample, and repeating preview/YUV requests pause while RAW
  or vignetting captures are in progress.
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
