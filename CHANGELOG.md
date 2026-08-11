# Changelog

All notable user-facing changes are recorded here. The project follows
[Semantic Versioning](https://semver.org/) for public releases.

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
