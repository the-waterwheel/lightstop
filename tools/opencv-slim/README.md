# lightstop 精简 OpenCV 构建

该构建固定使用 OpenCV `4.12.0`，与应用当前使用的 Java API 保持一致。

## 固定环境

发布前额外通过 `sanitize-aar-paths.py` 清理 ELF `.rodata` 中编译器留下的 Windows 用户目录（例如断言的 `__FILE__`）。替换保持字节长度，保留所有偏移、指令、重定位和导出符号；遇到其他区段或非 ASCII 字符串会停止，要求重新构建。JNI 与 Prefab 的重复库同时处理。当前脱敏 AAR 的 SHA-256 为 `35e3b7df14f1b304a0eba7c0d6d15dd03900315e1d62d366df734243d7601662`；旧构建报告中的大小与散列仅对应当时产物。

- OpenCV：`4.12.0`
- Android SDK：`34`（OpenCV AAR 自身；应用使用 API 36.1）
- Android NDK：`27.0.12077973`（启用 flexible page sizes）
- CMake：`3.22.1`
- Java：`17`
- Gradle wrapper：`8.13-bin`
- minSdk：`28`
- ABI：`arm64-v8a`、`armeabi-v7a`、`x86_64`

固定源码归档：

```text
URL: https://codeload.github.com/opencv/opencv/zip/refs/tags/4.12.0
SHA-256: FA3FAF7581F1FA943C9E670CF57DD6BA1C5B4178F363A188A2C8BFF1EB28B7E4
```

若本机尚无源码，脚本会下载上述固定源码归档、核对 SHA-256 后再解压。r4-perf 保留 oneTBB 2022.1、KleidiCV 0.5 和 x86_64 IPP；OpenCV 会按其固定 URL 和校验值下载这些依赖，可以复用源码目录的 `.cache`。首次重编需要网络和完整 Android 工具链，应用日常构建只使用已提交的 AAR。

OpenCV 会把 CMake 状态报告编译进 `cv::getBuildInformation()`。原始报告包含本机 SDK、NDK、编译器和 Python 的绝对路径，公开 AAR 时可能暴露 Windows 用户名。构建脚本会对 OpenCV `4.12.0` 的 `OpenCVUtils.cmake` 应用一段可重复、带标记的最小补丁，把用户主目录统一替换为 `<USERPROFILE>`；补丁只改变诊断文本，不改变算法、ABI 或第三方二进制。升级 OpenCV 后若补丁位置变化，脚本会立即停止并要求重新审计。

脚本还会对官方 `build_sdk.py` 应用一个带标记的小补丁：当固定 ABI 配置关闭 `BUILD_TESTS` 时，不再请求不存在的 `opencv_tests` Ninja 目标。它只修正构建驱动与 CMake 配置的冲突，不修改 OpenCV 库代码。

源码、依赖下载、Gradle 缓存、中间文件和原始构建输出默认统一存放在项目同级目录：

```text
<项目父目录>\opencv-lightstop-slim
```

脚本优先使用 `ANDROID_SDK_ROOT`、`ANDROID_HOME` 或当前用户的标准 Android SDK 目录，并从 `PATH` 查找 Python。也可以通过 `-OpenCvRoot`、`-AndroidSdk` 和 `-PythonExecutable` 显式覆盖，仓库内不包含开发者个人路径。

## 保留模块

```text
core,imgproc,imgcodecs,video,videoio,features2d,calib3d,java
```

OpenCV 会自动加入 `flann` 和 `java_bindings_generator` 等必要依赖。`imgcodecs` 和 `videoio` 虽然未被应用业务代码直接调用，但 OpenCV 4.12.0 的完整 Android Java 胶水层分别通过 `Utils.java` 和 `NativeCameraView.java` 引用它们；保留这两个模块可维持官方 AAR 的 Java API 兼容性，避免维护私有 OpenCV 源码补丁。未包含 `dnn`、`photo`、`ml`、`stitching`、`objdetect`、`gapi` 等当前不需要模块。

r4-perf 优先保持实时性能与维护便利：保留 `-O3`、默认 CPU dispatch、TBB、ARM Carotene、arm64 KleidiCV、x86_64 IPP 和默认 ITT 配置，只关闭 OpenJPEG、TIFF、WebP、OpenEXR、AVIF、Jasper 文件格式后端。PNG/JPEG、原有模块和完整 Java/JNI 接口保留。裁剪配置集中在 `lightmeter-android.config.py`，业务代码不需要适配；没有引入 MinSizeRel、LTO 或私有 Java 胶水层。

旧 r2 的加速后端关闭配置体积更小，但本次按性能优先要求恢复它们。不能通过搜索应用是否直接调用 TBB/IPP/KleidiCV 判断是否需要：它们由 OpenCV 内部算子使用。

## 构建

从项目根目录执行：

```powershell
.\tools\opencv-slim\build-opencv-slim.bat -InstallIntoProject
```

批处理启动器只为当前构建进程绕过本机 PowerShell 的脚本执行限制，不修改系统 Execution Policy。脚本使用 OpenCV 工作目录内独立的 `GRADLE_USER_HOME`，会优先复用本机已经校验过的 Gradle 8.13 分发版和只读依赖缓存；新下载仍写入 `<OpenCvRoot>\gradle-home`。Gradle 使用单次进程，避免 Windows 重定向输出后后台 daemon 持有句柄导致脚本无法退出。OpenCV 官方 SDK 模板默认使用体积更大的 `all` 归档，本构建将其固定替换为功能等价的 `bin` 归档。

产物为：

```text
<OpenCvRoot>\outputs\opencv-slim-4.12.0-r4-perf.aar
app\libs\opencv-slim-4.12.0-r4-perf.aar
```

当前已验证产物（r4-perf，NDK r27，16 KB ELF 对齐，保留运行时加速）：

```text
Size: 59,634,171 bytes
SHA-256: 35E3B7DF14F1B304A0EBA7C0D6D15DD03900315E1D62D366DF734243D7601662
```

性能基线产物（r1，NDK r25，仅 4 KB ELF 对齐，含相同加速后端）：

```text
Size: 63,882,947 bytes
SHA-256: 0A5C95F697D63C94F87D0B3CBAC8ACB61046D089BCEBCF25BF307A0F796767D0
```

| `libopencv_java4.so` | r1 bytes | r4-perf bytes | 减少 |
|---|---:|---:|---:|
| arm64-v8a | 14,515,536 | 13,074,352 | 9.93% |
| armeabi-v7a | 10,171,176 | 8,388,832 | 17.52% |
| x86_64 | 47,063,912 | 44,316,600 | 5.84% |
| 合计 | 71,750,624 | 65,779,784 | 8.32% |

该对比同时包含 NDK r25→r27 和编解码裁剪的变化，不能把差值全部归因于某一编译选项。AAR 还包含 Prefab 头文件和发布元数据，AAR 的文件大小不等于 APK 中 OpenCV 的占用。

若 SDK 已生成，只重新打包 AAR：

```powershell
.\tools\opencv-slim\build-opencv-slim.bat -SkipSdkBuild -InstallIntoProject
```

## Gradle 日志判定

本构建固定使用 Gradle `8.13-bin` 并优先复用本机已校验的发行包。NDK r27 构建显式启用 flexible page sizes，并为共享库加入 16 KB ELF 链接参数。若构建失败，应先定位最后一个 `FAILURE`、异常栈或非零退出码，不要把以下已知提示当作失败：

- OpenCV Kotlin 扩展的 `ExperimentalUnsignedTypes` 提示；
- OpenCV 4.12.0 官方 AAR 模板关于 `ndk.dir` 的弃用提示；
- Gradle 9.0 的未来兼容性提醒；
- Dokka 无法下载 Android 在线 `package-list`，但任务最后仍显示 `BUILD SUCCESSFUL`。

OpenCV SDK、共享 AAR 和应用本身必须分别出现成功结果。接入后至少执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

用性能基线 AAR 校验完整 Java 类列表、JNI 导出、加速后端与 ELF 对齐：

```powershell
python tools/opencv-slim/verify-opencv-runtime.py app/libs/opencv-slim-4.12.0-r4-perf.aar --baseline <r1-AAR路径>
```

`benchmark-zone.cpp` 用固定纹理执行 resize、特征提取、前后向 LK、仿射估计及 ORB/Hamming 匹配。用 NDK clang 编译为 Android arm64 可执行程序后，以 `LD_LIBRARY_PATH` 切换各版本库，分别运行 `benchmark-zone 120 160` 和 `benchmark-zone 120 400`。输出均值、P50、P95 和跟踪质量；比较时交替测试版本，记录设备状态。它验证代表性原生算子，不能替代相机端到端与真实场景回归。

## 升级规则

升级 OpenCV 时必须同时更新脚本中的版本、重新审计项目的 `org.opencv` 引用、重新构建全部 ABI，并执行 Zone 光流跟踪、ORB 恢复和仿射估计真机回归。不要直接用新 AAR 覆盖旧文件；使用新的版本化文件名和修订号。
