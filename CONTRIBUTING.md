# Contributing to lightstop

Thank you for helping improve lightstop.

## Before opening a change

- Search existing issues before reporting a duplicate.
- Keep bug reports focused and include the Android version, device model,
  selected camera, RAW availability, and exact reproduction steps when relevant.
- Do not upload photographs, calibration data, logs, or device identifiers that
  you do not have permission to share.

## Building and testing

Use JDK 17 and the checked-in Gradle Wrapper. Android Studio is optional.


```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Changes to camera transforms, Zone tracking, exposure math, ABI packaging, or
OpenCV must also be tested on a physical device. Release changes should pass:


```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease :app:bundleRelease
```

Never commit signing keystores, passwords, `keystore.properties`, local SDK
paths, device captures, or generated build directories.

## Code and dependency changes

- Keep Camera2 lifecycle work separate from pure geometry and exposure math.
- Add or update unit tests for pure logic changes.
- Preserve the existing application ID unless a deliberately incompatible app
  fork is being created.
- Do not replace the pinned OpenCV AAR without updating its checksum, build
  recipe, ABI audit, license bundle, and physical-device regression results.
- Every new dependency or asset must have a license compatible with commercial
  redistribution and Apache-2.0 source distribution.

Unless explicitly stated otherwise, a contribution intentionally submitted to
this repository is provided under the project's Apache License 2.0, consistent
with section 5 of that license.
