# 当前 OpenCV 使用与精简版兼容审计

本次以公开 0.5.0 的 r2 为基线，审查加入负片预览、界面和距离功能后的项目。候选版本为 0.6.1 / 12，采用原 r2 AAR，并默认按架构输出 APK。已发布的 0.6.0 保留原有内容。

## 当前实际调用

| 路径 | 主要调用 | 所需模块 |
| --- | --- | --- |
| `ZoneOpenCvFramePreprocessor` | Mat 写入/复制、旋转、resize | core、imgproc |
| `ZoneTrackingEngine` | Bitmap→Mat、灰度、均值、角点、双向 LK、RANSAC 仿射、ORB、Hamming 匹配 | core、imgproc、video、calib3d、features2d |
| `MotionParallaxDistanceProvider` | Mat/掩膜、角点、矩形掩膜、双向 LK | core、imgproc、video |
| 初始化与坐标/陀螺仪映射 | OpenCVLoader、Point | Java/Android 包装、core |
| 负片预览 | 选区、片基和校色为 Kotlin；实时反相为 OpenGL | 没有直接新增 OpenCV 模块 |

相对 0.5.0，新增消费路径是运动视差估距；静态函数调用集合新增 `Imgproc.rectangle`，其余上述静态调用已在 Zone 中存在。`rectangle` 属于旧 r2 已有的 imgproc。这里的静态集合不替代对实例方法、重载及 JNI 的核验。

没有发现业务对 dnn、ml、photo、stitching、objdetect、gapi、highgui 的引用；这些模块本来就没有纳入 r2。

## r2 是否仍满足当前项目

- 从公开 0.5.0 对应源码取回原 AAR，SHA-256 为 `321c84621fe818e35cc7b6401953039bc784e4fe4cfb9d35690b4dbedf4d57cd`，33,514,507 字节。
- r2 与 r4 的 148 个 Java 类内容逐字节一致，不只类名相同。
- 三个 ABI 各有 1,865 项 Java/JNI 导出，集合完全一致。
- 都包含 `calib3d core features2d flann imgcodecs imgproc java video videoio`，全部 ELF LOAD 段满足 16 KB 对齐。
- r2 保留 ARM NEON / Carotene、x86 SIMD dispatch 和 pthreads 并行后端；关闭 TBB、KleidiCV、IPP、ITT。它不是没有 SIMD 或多线程的构建。
- 旧 AAR 的解包扫描未检出个人用户目录、已知设备序列号或常见凭据模式。

结论：当前代码没有要求 r4 新增的可选加速后端或额外模块；r2 的接口覆盖当前项目。功能与性能仍分别验证，接口一致不代表 r2 与 r4 在所有设备上的耗时一致。

## 能否进一步缩减

| 方向 | 结论及维护成本 |
| --- | --- |
| 分架构 APK | 已实现。每包只携带对应 ABI，库和算法不改。直接减少用户下载的安装包。 |
| 删除 AAR 的 Prefab 包装 | 已做隔离包装验证。Prefab 主要是 C++ 头文件与重复库；当前应用未启用 Prefab，自有 CMake 仅链接 log。AAR 从 33,514,507 缩至 16,929,831 字节（约减半），Java/JNI 的全部条目逐字节保留，完整接口/对齐核验通过；只缩小依赖 AAR，APK 不会因此变小。当前正式依赖仍使用原 r2。 |
| 再删 imgcodecs / videoio | 业务没有直接调用，但官方 Android Java 包装仍引用：Utils→Imgcodecs、NativeCameraView→Videoio/VideoCapture/VideoWriter。删除需要调整官方 Java 包装并缩小完整 JNI 接口，增加升级与后续算法修改成本。 |
| 再删 flann / calib3d / features2d | calib3d 的官方 CMake 依赖 features2d 与 flann；业务还使用仿射估计、ORB 与匹配。无法作为无功能影响的整模块裁剪。 |
| 去掉 PNG/JPEG | 影响保留的 imgcodecs 能力与官方包装；收益需与接口维护成本一起衡量。 |
| 关闭 CV_TRACE | 可通过官方编译选项关闭内建诊断追踪，不改算法参数；当前 r2 为内建 Trace。收益需要重编量化，性能需要同设备比较后才能采用。 |
| 关闭更多 SIMD / 线程后端，改用 -Os/-Oz | 可能减少字节，但无法满足不影响性能的要求，当前保持原 r2 的 CPU dispatch、pthreads 与 -O3。 |

Prefab 包装工具为 `tools/opencv-slim/runtime-aar.py`；构建脚本可用 `-RuntimeOnly` 生成额外的 `r2-java` AAR。默认仍生成完整 r2，后续 C++ 代码需要直接使用 OpenCV 时可继续使用完整 Prefab 包。没有维护 OpenCV 私有源码分叉。

构建脚本保留 `compact` 与 `performance` 两份配置；默认 compact 对应 r2，performance 对应 r4。运行库核验工具用 `--profile compact` / `--profile performance` 检查相应配置。

当前没有连接可用的 USB 设备，尚未完成 r2/r4 的同机实测或额外编译裁剪的性能证明。基于完整 Java/JNI 接口和原运行库的复用进行此次切换，进一步影响原生代码的裁剪需要补做性能验证。

## 验证结果

- r2 profile、完整 Java/JNI、CPU dispatch、PNG/JPEG、16 KB ELF 对齐核验通过。
- 默认测试 572 项；加入 Robolectric 界面测试后 588 项，均无失败、错误或跳过；lint 0 错误、65 条既有警告。
- Debug / 各架构 Release APK / AAB 构建通过；所有四个 Release APK 的 16 KB ZIP 对齐通过。
- 各架构 APK 的非原生条目相同，APK 内 OpenCV 库与原 r2 AAR 逐字节一致；各包 ABI 与元数据匹配。
- 版本号已核对为 0.6.1 / 12，解包隐私扫描无个人路径、设备序列号及常见凭据命中。
- 发现 AGP 8.13.2 的 bundle 资源压缩器无法同时处理 ABI APK 输出，已对 bundle 任务自动关闭分包，验证默认 bundleRelease 和单独 assembleRelease 均成功。

| 产物（未正式签名） | 字节 | MiB |
| --- | ---: | ---: |
| app-universal-release-unsigned.apk | 44,661,885 | 42.59 |
| app-armeabi-v7a-release-unsigned.apk | 13,238,114 | 12.62 |
| app-arm64-v8a-release-unsigned.apk | 17,526,848 | 16.71 |
| app-x86_64-release-unsigned.apk | 22,051,503 | 21.03 |
| Release AAB | 21,325,117 | 20.34 |

以上为未签名构建；维护者应在 Android Studio 用原发布密钥生成签名 APK，再核验上传。库切换没有替换已发布的 0.6.0 文件。

源码依据：[Java Utils](https://github.com/opencv/opencv/blob/4.12.0/modules/java/generator/android/java/org/opencv/android/Utils.java)、[NativeCameraView](https://github.com/opencv/opencv/blob/4.12.0/modules/java/generator/android-24/java/org/opencv/android/NativeCameraView.java)、[calib3d 模块](https://github.com/opencv/opencv/blob/4.12.0/modules/calib3d/CMakeLists.txt)、[构建选项](https://github.com/opencv/opencv/blob/4.12.0/CMakeLists.txt)。
