# Changelog

All notable user-facing changes are recorded here. The project follows
[Semantic Versioning](https://semver.org/) for public releases.

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
