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
| Kotlin standard library | 1.9.22 | Apache-2.0 | `LICENSE` |
| JetBrains annotations | 13.0 | Apache-2.0 | `LICENSE` |
| Android NDK C++ runtime (`libc++_shared.so`) | r25b | Apache-2.0 WITH LLVM-exception and bundled third-party terms | `app/src/main/assets/licenses/third-party/android-ndk-r25b-NOTICE.toolchain.txt` |

## OpenCV runtime

| Component | Version | License | License location |
|---|---:|---|---|
| OpenCV | 4.12.0 | Apache-2.0 | `app/src/main/assets/licenses/OpenCV-LICENSE-2.0.txt` and `OpenCV-NOTICE.txt` |
| oneTBB | 2022.1.0 | Apache-2.0 | `third-party/onetbb-Apache-2.0.txt` |
| KleidiCV | 0.5.0 | Apache-2.0 | `third-party/kleidicv-Apache-2.0.txt` |
| Android cpu_features | OpenCV-pinned source | Apache-2.0 | `third-party/android-cpufeatures-Apache-2.0.txt` |
| ITT Notify | OpenCV-pinned source | BSD-3-Clause option | `third-party/ittnotify-BSD-3-Clause.txt` |
| libjpeg-turbo / IJG JPEG | OpenCV-pinned source | BSD-style, IJG, and zlib terms | `third-party/libjpeg-turbo-LICENSE.md` and `libjpeg-turbo-README.ijg` |
| libwebp | OpenCV-pinned source | BSD-3-Clause | `third-party/libwebp-BSD-3-Clause.txt` |
| libpng | OpenCV-pinned source | PNG Reference Library License | `third-party/libpng-LICENSE.txt` |
| libtiff | OpenCV-pinned source | libtiff license | `third-party/libtiff-COPYRIGHT.txt` |
| OpenJPEG | OpenCV-pinned source | BSD-2-Clause | `third-party/openjpeg-BSD-2-Clause.txt` |
| OpenEXR / IlmBase | OpenCV-pinned source | BSD-3-Clause | `third-party/openexr-BSD-3-Clause.txt` |
| Intel IPP ICV and IPP IW | OpenCV 4.12.0 prebuilt binary dependencies for applicable ABIs | Intel Simplified Software License (October 2022) | `third-party/intel-ippicv-EULA.txt`, `intel-ippiw-EULA.txt`, and their third-party program notices |
| ADE | 0.1.2e | Apache-2.0 | `third-party/ade-Apache-2.0.txt` |
| FlatBuffers | OpenCV-pinned source | Apache-2.0 | `third-party/flatbuffers-Apache-2.0.txt` |
| Protocol Buffers | OpenCV-pinned source | BSD-3-Clause | `third-party/protobuf-BSD-3-Clause.txt` |
| Berkeley SoftFloat | OpenCV-pinned source | BSD-style license | `third-party/softfloat-LICENSE.txt` |
| MSCR chi-square table data | OpenCV-pinned source | Attribution notice | `third-party/mscr-chi-table-LICENSE.txt` |

The OpenCV SDK license bundle lists a superset of components used by its build
and packaging pipeline. We retain the relevant generated notices, including
some conservative extra notices, so a future compatible rebuild cannot silently
drop required attribution.

For ITT Notify, this distribution expressly selects the BSD-3-Clause branch of
its `GPL-2.0-only OR BSD-3-Clause` dual license. No GPL license is applied to
lightstop by that component.

Intel IPP is redistributed only as unmodified binary code within the OpenCV
native library. It is not relicensed under Apache-2.0. Do not extract, modify,
reverse engineer, or use Intel's name to endorse lightstop.

## Build and test tooling

The repository includes the Gradle Wrapper 8.7 under Apache-2.0; its license is
also embedded in `gradle/wrapper/gradle-wrapper.jar`. Android Gradle Plugin,
Kotlin Gradle Plugin, Android SDK/NDK build tools, CMake, and JUnit are resolved
or installed as development tools and are not relicensed as part of lightstop.

## Trademarks

Product and project names mentioned above are trademarks of their respective
owners. Their names identify software origin only and do not imply endorsement.
