# lightstop 精简 OpenCV 构建

应用当前采用与公开 0.5.0 相同的 **r2 compact** AAR，版本固定为 OpenCV 4.12.0；日常 Android Studio 构建使用仓库内文件，无需重编 OpenCV。0.6.0 使用过的 r4-perf 配置保留，供后续性能对照。

## 运行库与模块

- AAR：`app/libs/opencv-slim-4.12.0-r2.aar`，33,514,507 字节。
- SHA-256：`321c84621fe818e35cc7b6401953039bc784e4fe4cfb9d35690b4dbedf4d57cd`；Gradle 构建前强制校验。
- ABI：arm64-v8a、armeabi-v7a、x86_64；所有 ELF LOAD 段满足 16 KB 对齐。
- Java 类 148 个；每个 ABI 的 JNI 导出 1,865 项。与 r4 的 Java 类内容和 JNI 导出相同。

保留模块：

```text
core,imgproc,imgcodecs,video,videoio,features2d,calib3d,java
```

flann 是自动依赖。业务使用 core/imgproc、LK 光流、ORB/Hamming 与仿射估计；官方 Android Java 包装还引用 imgcodecs/videoio，因此在保持完整官方接口的方案中保留它们。未包含 dnn、ml、photo、stitching、objdetect、gapi、highgui。

compact 保留 `-O3`、原 CPU baseline/dispatch、ARM Carotene 和 pthreads；关闭 TBB、KleidiCV、IPP、ITT、ADE，以及 OpenJPEG、TIFF、WebP、OpenEXR、AVIF、Jasper。关闭可选加速后端不等于关闭全部 SIMD 或多线程。性能影响以同机实测为准。

[当前业务调用、兼容核验及进一步裁剪评估](../../docs/OPENCV_CURRENT_USAGE_AND_COMPACT_AUDIT_ZH.md)。

## 固定工具链

OpenCV 4.12.0、NDK 27.0.12077973、CMake 3.22.1、Java 17、Gradle 8.13-bin。OpenCV AAR 的 SDK 为 34、minSdk 28；应用使用 API 36.1。

```text
Source: https://codeload.github.com/opencv/opencv/zip/refs/tags/4.12.0
SHA-256: FA3FAF7581F1FA943C9E670CF57DD6BA1C5B4178F363A188A2C8BFF1EB28B7E4
```

脚本核验归档再解压，使用独立 Gradle 缓存并复用已校验发行包。SDK、源码和下载默认位于 `<项目父目录>/opencv-lightstop-slim`；可用 `-OpenCvRoot`、`-AndroidSdk`、`-PythonExecutable` 覆盖。仓库不固定个人路径。

## 重编与包装

```powershell
.\tools\opencv-slim\build-opencv-slim.bat -BuildProfile compact -InstallIntoProject
.\tools\opencv-slim\build-opencv-slim.bat -BuildProfile performance
```

默认 profile 为 compact，配置在 `lightmeter-android.config.py`，输出修订 r2；performance 在 `lightmeter-android-performance.config.py`，输出 r4-perf，并下载其固定的 oneTBB/KleidiCV/IPP 依赖。两份 profile 使用不同 SDK/AAR 构建目录，避免缓存混入另一份配置。

使用 `-SkipSdkBuild` 或 `-SkipAarBuild` 需已有对应 profile 的完整输出。`-InstallIntoProject` 只复制产物及 NDK 许可，不自动更改应用的文件名或固定 SHA-256。重新生成的归档受工具链与元数据影响，散列有变化时必须重新审计，再有意更新 Gradle pin。

构建驱动仅对官方生成器作必要适配：关闭 tests 后不请求测试目标、Windows Gradle 启动、状态报告脱敏。`sanitize-aar-paths.py` 进一步处理 `.rodata` 内 ASCII 诊断路径的等长替换，保持指令、偏移及符号，异常区段会停止处理。当前复用的原 r2 AAR 已通过隐私扫描，不作二进制改写。

### 可选的 Java/JNI-only 包装

```powershell
python tools/opencv-slim/runtime-aar.py app/libs/opencv-slim-4.12.0-r2.aar build/opencv-r2-java.aar
```

构建脚本也支持 `-RuntimeOnly`，额外输出 `r2-java` 或 `r4-perf-java`。该工具只去掉 Prefab C++ 开发包，逐项核验其他 ZIP 条目的解包字节完全相同。

实测 r2 的 AAR 从 33,514,507 缩至 16,929,831 字节，约减半；所有 Java/JNI 运行内容相同。**这只缩小 AAR，不会继续缩小 APK。** 当前正式依赖仍使用原完整 r2。将来 C++ 直接调用 OpenCV 时应使用完整 AAR 并启用 Prefab；所有 Java 功能仍可使用。

## 接入核验

```powershell
python tools/opencv-slim/verify-opencv-runtime.py app/libs/opencv-slim-4.12.0-r2.aar --baseline <已验证AAR> --profile compact
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
.\gradlew.bat :app:bundleRelease
```

核验 Java 类、JNI 导出、模块、CPU dispatch、编解码配置、16 KB 对齐及 compact/performance 后端。检查 r4 时明确传 `--profile performance`。

APK 默认按三个 ABI 分开输出，并保留通用包。bundle 任务自动关闭 APK 分包以满足当前 AGP 资源压缩器；得到各架构 APK 时单独执行 assembleRelease。

`benchmark-zone.cpp` 使用固定纹理测试 resize、角点/双向 LK、仿射、ORB/Hamming。以同一 NDK 可执行文件、交替运行库和统一设备状态比较均值/P50/P95及质量。源代码审计和 JVM 测试不替代 Android JNI 执行及实机性能测量。

## 升级规则

升级时更新固定源码与散列，审计 Java/native 引用、全部 ABI、许可证，并验证 Zone 跟踪、ORB 恢复、仿射估计和运动视差估距。仅删除本机不用的后端不是性能证明；修改官方 Java 包装需要额外维护接口边界。
