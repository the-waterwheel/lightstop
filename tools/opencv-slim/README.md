# lightstop 精简 OpenCV 构建

该构建固定使用 OpenCV `4.12.0`，与应用当前使用的 Java API 保持一致。

## 固定环境

- OpenCV：`4.12.0`
- Android SDK：`34`
- Android NDK：`25.1.8937393`
- CMake：`3.22.1`
- Java：`17`
- Gradle wrapper：`8.7-bin`
- minSdk：`28`
- ABI：`arm64-v8a`、`armeabi-v7a`、`x86_64`

固定源码归档：

```text
URL: https://codeload.github.com/opencv/opencv/zip/refs/tags/4.12.0
SHA-256: FA3FAF7581F1FA943C9E670CF57DD6BA1C5B4178F363A188A2C8BFF1EB28B7E4
```

固定优化依赖：

```text
oneTBB 2022.1.0 MD5: CCE28E6CB1CEAE14A93848990C98CB6B
KleidiCV 0.5.0 MD5: BA5648F8DF678548F337D19D8AC607D6
ADE v0.1.2e MD5: 962CE79E0B95591F226431F7B5F152CD
```

脚本会在每次构建前验证这些归档，并填充 OpenCV 源码的 `.cache`，避免 CMake 重复联网下载。

OpenCV 会把 CMake 状态报告编译进 `cv::getBuildInformation()`。原始报告包含本机 SDK、NDK、编译器和 Python 的绝对路径，公开 AAR 时可能暴露 Windows 用户名。构建脚本会对 OpenCV `4.12.0` 的 `OpenCVUtils.cmake` 应用一段可重复、带标记的最小补丁，把用户主目录统一替换为 `<USERPROFILE>`；补丁只改变诊断文本，不改变算法、ABI 或第三方二进制。升级 OpenCV 后若补丁位置变化，脚本会立即停止并要求重新审计。

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

## 构建

从项目根目录执行：

```powershell
.\tools\opencv-slim\build-opencv-slim.bat -InstallIntoProject
```

批处理启动器只为当前构建进程绕过本机 PowerShell 的脚本执行限制，不修改系统 Execution Policy。脚本使用 OpenCV 工作目录内独立的 `GRADLE_USER_HOME`，会优先复用本机已经校验过的 Gradle 8.7 分发版和只读依赖缓存；新下载仍写入 `<OpenCvRoot>\gradle-home`。OpenCV 官方 SDK 模板默认使用体积更大的 `gradle-8.7-all.zip`，本构建将其固定替换为功能等价的 `gradle-8.7-bin.zip`。

产物为：

```text
<OpenCvRoot>\outputs\opencv-slim-4.12.0-r1.aar
app\libs\opencv-slim-4.12.0-r1.aar
```

当前已验证产物：

```text
Size: 63,882,947 bytes
SHA-256: 0A5C95F697D63C94F87D0B3CBAC8ACB61046D089BCEBCF25BF307A0F796767D0
```

若 SDK 已生成，只重新打包 AAR：

```powershell
.\tools\opencv-slim\build-opencv-slim.bat -SkipSdkBuild -InstallIntoProject
```

## Gradle 日志判定

本构建固定使用 Gradle `8.7-bin` 并优先复用本机已校验的发行包，避免官方 SDK 模板下载较大的 `gradle-8.7-all.zip` 时超时。若构建失败，应先定位最后一个 `FAILURE`、异常栈或非零退出码，不要把以下已知提示当作失败：

- OpenCV Kotlin 扩展的 `ExperimentalUnsignedTypes` 提示；
- OpenCV 4.12.0 官方 AAR 模板关于 `ndk.dir` 的弃用提示；
- Gradle 9.0 的未来兼容性提醒；
- Dokka 无法下载 Android 在线 `package-list`，但任务最后仍显示 `BUILD SUCCESSFUL`。

OpenCV SDK、共享 AAR 和应用本身必须分别出现成功结果。接入后至少执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

## 升级规则

升级 OpenCV 时必须同时更新脚本中的版本、重新审计项目的 `org.opencv` 引用、重新构建全部 ABI，并执行 Zone 光流跟踪、ORB 恢复和仿射估计真机回归。不要直接用新 AAR 覆盖旧文件；使用新的版本化文件名和修订号。
