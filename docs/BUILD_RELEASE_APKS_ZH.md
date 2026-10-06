# 按架构生成正式 APK

项目默认开启 ABI 分包，同时保留通用包。日常构建无需重编 OpenCV。

1. 在 Android Studio 打开项目并同步 Gradle。
2. 选择 **Build → Generate Signed Bundle / APK → APK**。
3. 选 `app`，使用原发布密钥与别名。
4. 选择 `release` 和输出目录，然后生成安装包。

同一次构建会生成四个 APK（Android Studio 版本不同可能采用略有差异的文件名）：

| 文件 | 用途 |
| --- | --- |
| `app-arm64-v8a-release.apk` | 64 位 ARM；当前实测手机使用此架构 |
| `app-armeabi-v7a-release.apk` | 32 位 ARM |
| `app-x86_64-release.apk` | 对应 x86_64 设备 / 模拟器 |
| `app-universal-release.apk` | 三个架构合一的兼容包，体积最大 |

每个按架构生成的 APK 都是可独立安装的完整应用，不需要再装基础包。文件名中的 ABI 只决定所带原生库，不改变应用功能、包名、版本或签名。

GitHub 直接下载使用这些 APK。AAB 用于支持 App Bundle 的商店，由商店生成设备匹配的 APK；选择 AAB 不会直接输出上述三个可分发安装包。若以后上架 Google Play，按 AAB 的商店流程处理。

命令行验证（不包含正式签名）：

```powershell
.\gradlew.bat :app:assembleRelease
```

产物位于 `app/build/outputs/apk/release/`。仅生成通用 APK 可传 `-PsplitApks=false`；显式开启分包可传 `-PsplitApks=true`。

当前 AGP 的 AAB 资源压缩器要求单输出，项目检测 `bundleRelease` / `bundleDebug` 等 bundle 任务并自动关闭 APK 分包。Android Studio 生成 AAB 仍得到一个商店分发包，商店再完成按设备分发。若同一命令同时含 `assembleRelease` 与 `bundleRelease`，APK 也会使用通用配置；需要各架构 APK 时单独执行 `assembleRelease`。

当前候选版本为 `0.6.1` / `12`，采用与 0.5.0 相同的 r2 OpenCV 运行库。每个架构 APK 使用相同版本号，用于 GitHub 直接分发；不套用旧式 Google Play 多 APK 的架构版本号规则。此前已发布的 0.6.0 保留其原有运行库。

## OpenCV 加速与功能依赖

业务代码使用 OpenCV 的主要路径是 Zone 标记跟踪、重识别和运动视差估距。当前负片选区 / 片基识别与校色是 Kotlin 算法，实时反相走 OpenGL，没有直接调用 OpenCV。

TBB、KleidiCV、IPP 是 OpenCV 内部可选的性能后端，不是上述业务功能必须依赖的接口。保留它们不等于已经证明本项目实机更快；关闭也需要重新编译并比较实际性能，不能直接删掉已链接进 OpenCV 的库内容。先按架构打包，避免 ARM 下载包含大型 x86_64 IPP 库的通用 APK。

参考：[Android APK 分包](https://developer.android.com/build/configure-apk-splits)、[应用签名](https://developer.android.com/studio/publish/app-signing)、[OpenCV 配置](https://docs.opencv.org/4.x/db/d05/tutorial_config_reference.html)。
