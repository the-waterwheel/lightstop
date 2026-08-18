# Third-party notices

lightstop is licensed under the Apache License, Version 2.0. The project license
applies only to code and documentation owned by the lightstop contributors.
Bundled third-party software remains under its own license.

The release application contains a locally built OpenCV 4.12.0 runtime AAR for
`arm64-v8a`, `armeabi-v7a`, and `x86_64`. The AAR is not licensed as one
single Apache-2.0 work: it combines OpenCV with the components listed below.
The corresponding license texts are distributed in
`app/src/main/assets/licenses/` and are packaged into every APK/AAB.

## Application runtime

| Component | Version | License | License location |
|---|---:|---|---|
| Kotlin standard library | 2.1.20 | Apache-2.0 | `LICENSE` |
| JetBrains annotations | 13.0 | Apache-2.0 | `LICENSE` |
| Android NDK C++ runtime (`libc++_shared.so`) | r27 | Apache-2.0 WITH LLVM-exception and bundled third-party terms | `app/src/main/assets/licenses/third-party/android-ndk-r27-NOTICE.toolchain.txt` |

## OpenCV runtime

| Component | Version | License | License location |
|---|---:|---|---|
| OpenCV | 4.12.0 | Apache-2.0 | `app/src/main/assets/licenses/OpenCV-LICENSE-2.0.txt` and `OpenCV-NOTICE.txt` |
| Android cpu_features | OpenCV-pinned source | Apache-2.0 | `third-party/android-cpufeatures-Apache-2.0.txt` |
| Carotene ARM HAL | OpenCV-pinned source | BSD-3-Clause | `third-party/carotene-BSD-3-Clause.txt` |
| libjpeg-turbo / IJG JPEG | OpenCV-pinned source | BSD-style, IJG, and zlib terms | `third-party/libjpeg-turbo-LICENSE.md` and `libjpeg-turbo-README.ijg` |
| libpng | OpenCV-pinned source | PNG Reference Library License | `third-party/libpng-LICENSE.txt` |
| Berkeley SoftFloat / fdlibm | OpenCV-pinned source | BSD-3-Clause and Sun permissive notices | `third-party/softfloat-LICENSE.txt` |
| MSCR chi table | OpenCV-pinned source | BSD-style | `third-party/mscr-chi-table-LICENSE.txt` |

The OpenCV SDK license bundle lists a superset of components used by its build
and packaging pipeline. We retain the relevant generated notices, including
some conservative extra notices, so a future compatible rebuild cannot silently
drop required attribution.

## Build and test tooling

The repository includes the Gradle Wrapper 8.13 under Apache-2.0; its license is
also embedded in `gradle/wrapper/gradle-wrapper.jar`. Android Gradle Plugin,
Kotlin Gradle Plugin, Android SDK/NDK build tools, CMake, and JUnit are resolved
or installed as development tools and are not relicensed as part of lightstop.

## Trademarks

Product and project names mentioned above are trademarks of their respective
owners. Their names identify software origin only and do not imply endorsement.
