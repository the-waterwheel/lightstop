# 项目结构与近期版本测光审计

> 2026-10-24 整合说明：本文保留 2026-10-21 的审计基线与证据，“main”仍指当时的 `7ff2147`。本次已合入后续分支及设备修正，详见 [整合记录](WORKTREE_INTEGRATION_2026_10_24_ZH.md)。原反例源码、日志和测试产物位于原审计工作树的忽略目录，未随本文导入；下文诊断路径只作为历史定位记录。

审计日期：2026-10-21。结论：在特定输入条件下会发生错误。最新代码仍存在处理后流数字增益遗漏、YUV 局部测量范围不一致两类问题；main 另有已在后续分支修复的饱和读数放行、显示帧时间戳问题。

**不同来源的原始读数、校准偏移和响应曲线各自独立，来源间读数差异本身不作为缺陷。** 本次缺陷判断依据相同来源、相同校准状态下的受控输入和代码路径，不要求 RAW、YUV、ISP 的未校准读数相等。

**版本范围与工作区边界**

| 版本 | 读取的提交/状态 | 与本次测光审计的关系 |
| --- | --- | --- |
| main / 0.6.1 | `7ff2147`，2026-10-07 | 当前发布基线；本对话工作树仍停留于此提交 |
| `codex/camera-controller-boundaries` | `9b74d85`，2026-10-15；领先 main 5 个提交 | 提取相机职责、修复 GL 时间戳、等待精确显示帧元数据、优化 YUV 分析 |
| `codex/independent-metering` | `5281609`，2026-10-17；领先 main 6 个提交 | 包含上一行全部提交，新增 Normal 全局测光及连续 RAW 降曝光重拍 |
| 上述分支实际工作树 | `399b` 内尚未提交的 9 个跟踪文件改动，以及 androidTest、设备记录 | 放宽 Bayer RAW 能力判定，取消测量失败导致的长期降级，补充真机验证；也纳入本次阅读及隔离副本 JVM 测试 |
| `codex/non-normal-visual-polish` | `74df53a`，2026-10-08；独立领先 main 1 个提交 | 工具与 Zone 视觉修改，未修改测光数值算法 |
| UI、负片、距离、OpenCV、发布等其他登记工作树 | 读取工作树状态、分支关系及近期差异 | 这些分支的相关功能已进入 main，或停留在较旧版本；未发现比上述分支更新的独立测光实现 |

共检查 13 个登记工作树的状态。`399b` 是唯一存在尚未提交的应用源码改动的其他工作树；另有工作树仅有未跟踪诊断/文档目录。未切换、重置、合并或写入其他工作树。所有测试副本、日志、反例均在本工作树的 `diagnostics/metering-audit-20261021/`，测试使用系统现有 JDK、SDK、Gradle 缓存。没有连接手机、安装应用、修改校准数据或向其他对话发消息。

最新隔离副本由分支归档加其跟踪文件未提交改动构成；未把其他工作树中的构建目录和设备私有数据带入副本。此次没有执行 androidTest 真机测试。

**代码构成**

这是一个单 `app` 模块的 Android 原生项目，主要使用 Kotlin、Camera2、自绘 View/Canvas、OpenGL ES，以及 JNI/C++。main 约有 213 个 Kotlin 生产源文件、5.0 万非空源码行，常规单元测试源文件 101 个，另有 Robolectric/UI 测试源集。`CameraController`、`MeterLayout`、`MainActivity` 仍是较大的编排入口；近期分支已把部分相机职责提取为独立所有者。

| 层次 | 主要代码 | 职责 |
| --- | --- | --- |
| 应用与状态 | `MainActivity`、`MeterModels` / `MeterState`、`MeterLayout`、`InstrumentView` | 页面生命周期、参数状态、测光入口、取景显示、曝光结果 |
| 相机与工作流 | `CameraController`、`CameraCatalog`、`CameraOpenConfigurationResolver`、`CameraSessionCoordinator`、`CameraPreviewRequestCoordinator` | 镜头路由、相机组合选择、RAW/YUV/预览会话、请求和恢复 |
| 采集所有权 | `RawLightMeter`、`CompatibleLightMeter`、`TimestampedResultPairer` | 精确配对图像与元数据、超时、取消、关闭 Image、逐帧推进 |
| 测量与数值计算 | `MeteringAnalysis`、`ProcessedLumaMath`、`MeteringFusion`、`RawMeteringQuality`、`RawHighlightProtection` | 像素解码、ROI、亮度统计、EV100、融合与饱和重拍 |
| 原生分析 | `RawMeterBridge`、`raw_meter.cpp` | 扣黑、白点归一化、Bayer 通道统计、RAW 区域采样 |
| 校准 | `CameraCalibrationStore`、`ProcessedResponseCalibration`、`CalibrationSignature`、`VignettingCalibrationStore` | RAW/YUV/ISP 独立校准、响应曲线、路由/环境签名、暗角 |
| Zone 与跟踪 | `ZoneSystemModels`、`ZoneTrackingEngine`、Zone RAW 协调器、OpenCV | 测点位置、帧间跟踪、局部 RAW 配准、共享捕获批量测点 |
| 摄影工具与记录 | 景深、互易律、闪光、色温、负片、距离、参数记录相关文件及 assets | 与核心测光共享状态或元数据，但有各自的数学与交互模块 |

核心数据流为：

```text
界面/状态 → CameraController 选择采集工作流
          → RAW Image + 同时间戳 CaptureResult → RawLightMeter
          → YUV Image 或显示 Bitmap + 同帧 CaptureResult → CompatibleLightMeter
          → MeteringAnalysis → 来源专属校准 → MeteringFusion
          → MeterReading → MeterState → 曝光参数及界面
```

局部 RAW 使用扣黑、白点归一化后的通道中位数和颜色变换；YUV/ISP 重建 RGB、反解可用的色调曲线，再计算线性亮度中位数。RAW 跨帧融合在 EV 域取中位数。基本换算是 `EV100 = log2(N² / t × 100 / ISO) + log2(L / 0.18) + 来源校准`。

新分支的全局测光使用区域 EV 的 5%–95% 区间均值作为 B、98%–99.5% 区间均值作为 U，自动减曝光 `clamp(U − B − 2.5, 0, 0.5)`。这与用户补偿分开保存，Normal/Zone 选择有独立状态；来源校准继续走局部采样。此次未在这组公式、补偿符号、一次性应用补偿、量化边界或模式切换中发现确定缺陷。

**发现 1：P1，处理后流漏掉逐帧 RAW 后数字增益；main 和最新代码均存在**

位置：main [MeteringAnalysis.kt](../app/src/main/java/com/lightmeter/rawmeter/MeteringAnalysis.kt) 的预览/YUV `cameraEv`（第 88、150 行）；最新副本同文件第 96、163 行，以及 [EvaluativeFrameAnalyzer.kt](../diagnostics/metering-audit-20261021/latest/app/src/main/java/com/lightmeter/rawmeter/EvaluativeFrameAnalyzer.kt) 第 147 行。

处理后图像使用 `SENSOR_SENSITIVITY` 直接换算曝光，但没有计入 `CONTROL_POST_RAW_SENSITIVITY_BOOST`。这个键目前只用于其他证据/诊断，没有进入这些 EV 公式。Android 定义处理后输出的总灵敏度为 `SENSOR_SENSITIVITY × boost / 100`，RAW 则只使用传感器 ISO。参见 [Android CaptureResult 官方定义](https://developer.android.com/reference/android/hardware/camera2/CaptureResult#CONTROL_POST_RAW_SENSITIVITY_BOOST)。

本次用同一来源、相同曝光/ISO/光圈/校准、明确的线性曲线模拟同一场景：boost 从 100% 到 400%，输出中性像素从 32 到 128，均未截断。物理场景亮度不变，但局部 ISP、局部 YUV、全局 ISP、全局 YUV 均从约 **8.12343 EV 变成 10.12343 EV**，误差 **+2 EV**。这是增益没有抵消造成的计算偏差，不是不同流校准不同造成的差异。

固定 boost 的误差可能被一次校准吸收；若校准发生在 b₀、测量发生在 b，残余误差可随 `log2(b/b₀)` 变化。静态偏移不能普遍抵消这个逐帧变量。某台设备始终返回 100% 时不会触发此问题。

建议统一处理后曝光归一化，使用逐帧有效 ISO 或等价的增益抵消，RAW 保持自身定义。实施时需处理既有 YUV/ISP 校准可能吸收旧误差的情况，更新适当的处理后校准版本并要求重校；不要把 RAW 校准或任一路径的偏移直接复制到其他路径。

**发现 2：P2，YUV 局部 ROI 的形状、变焦范围与取景局部范围不一致；main 和最新代码均存在**

main [MeteringAnalysis.kt](../app/src/main/java/com/lightmeter/rawmeter/MeteringAnalysis.kt) 第 554–555 行分别使用 `width × fraction`、`height × fraction`；最新代码迁入 [YuvRegionAnalyzer.kt](../diagnostics/metering-audit-20261021/latest/app/src/main/java/com/lightmeter/rawmeter/YuvRegionAnalyzer.kt) 第 52–53 行，算法含义未改变。

RAW/预览局部采样按短边构造局部方形，而 YUV 直接按原始宽高构造矩形。例如 1000×750、比例 0.08，YUV 取 80×60，短边定义为 60×60。局部 YUV 入口也没有应用显示变焦/可见画幅变换；新加入的视口与几何参数仅在全局分支使用。电子变焦在 GL 显示端实施，因此取景放大不会自动改变原始 YUV 缓冲的局部 ROI。

为排除不同来源校准因素，最新反例直接在**同一 YUV 来源**中测试：保留中心 60×60 内的图案，只改变其外侧、却被 80×60 矩形纳入的条带，读数从 8.12343 跳到 10.12343 EV。2× 变焦反例保留放大后测点覆盖的中心区域、仅改变外部区域，也复现 +2 EV 变化。此数值是构造的高反差反例，不代表任意实景都有 2 EV 偏差。

点测的错误可直接复现；中央重点与按角度也使用同一 YUV 区域构造，需要一并修正并分别验证。全局 YUV 已有独立可见范围映射，不应把这个局部缺陷泛化成全局裁切错误。

建议让局部 ROI 先在明确的传感器/显示坐标域确定，再映射到 YUV，统一短边尺寸、画幅、电子变焦与物理角度语义。各流仍保持各自的光度解码与校准。

**发现 3：P2，main 在 RAW 统计仍饱和时可能返回有效读数；最新分支已修复**

main [RawHighlightProtection.kt](../app/src/main/java/com/lightmeter/rawmeter/RawHighlightProtection.kt) 第 25、32、35 行限制为两次 3 EV 重拍，且至少两个通道中位数饱和才触发。达到第 2 级仍饱和时 `nextStage` 返回 null，调用者继续把统计加入结果。`RawLightMeter.finishWithReading` 第 686 行之后虽然计算质量状态，却只记日志，仍向界面交付读数。单通道饱和也可能偏置颜色亮度，但未进入旧重拍条件。

这是代码路径确认，未在本次连接手机强制重现硬件饱和。`5281609` 已改为局部任一通道饱和时连续每次减 5 EV，全局按亮端判据连续减 3 EV；先检查能否继续减曝光，局部达到控制下限仍饱和则报错。main 仍未包含此修复。

**发现 4：P1，main 的显示预览测光无法可靠配对 GL 输出时间戳；后续分支已修复**

main [FilmNegativeRenderer.kt](../app/src/main/java/com/lightmeter/rawmeter/FilmNegativeRenderer.kt) 第 436 行开始的 `render()` 直接 swap；[CompatibleLightMeter.kt](../app/src/main/java/com/lightmeter/rawmeter/CompatibleLightMeter.kt) 第 275、292 行却按输出 SurfaceTexture 时间戳精确寻找相机元数据。GL 重新提交的输出没有显式继承相机输入时间戳，可能反复找不到精确帧，导致兼容模式或 ISP 备用测光失败。

`c0a3ffb` 已在 swap 前绑定相机时间戳；`cf5eed5` 进一步保留待配对 Bitmap，等待该帧延迟到达的元数据，避免反复追逐新帧。两者均包含在 `9b74d85` 和 `5281609` 中。证据包括实际代码和该分支的 [修复记录](../diagnostics/metering-audit-20261021/latest/docs/PREVIEW_TIMESTAMP_AND_YUV_ANALYSIS_2026_10_14_ZH.md)。本次没有重新做真机 EGL 验证。

**验证结果与复现文件**

| 检查 | 本次结果 |
| --- | --- |
| main 原有 JVM/Robolectric 测试 | 588 项通过；加入 3 个审计反例后总计 591 项，其中仅这 3 项失败 |
| 最新提交加跟踪文件未提交修改，原有 JVM/Robolectric 测试 | 688 项、119 套件，零失败、错误、跳过 |
| 最新代码的审计反例 | 6 项全部按预期失败：4 个数字增益反例、2 个同来源局部几何反例；无执行错误 |
| 真实原生全局采样器宿主测试 | 使用 MinGW g++ 编译现有 C++ 测试并执行通过，覆盖 CFA、偏移、步长、边界、裁切、饱和、暗部、采样预算、暗角插值 |
| 新的手机测光/绝对曝光实测 | 未执行；不把其他分支历史设备记录冒充此次实测 |

测试失败是刻意加入的物理不变量断言揭示现有问题，不是修改了生产代码后造成的回归。生产算法源码未作修改。

- [最新反例源码](../diagnostics/metering-audit-20261021/latest/app/src/uiPreview/java/com/lightmeter/rawmeter/MeteringAuditRegressionTest.kt)
- [main 反例源码](../diagnostics/metering-audit-20261021/main/app/src/uiPreview/java/com/lightmeter/rawmeter/MainMeteringAuditRegressionTest.kt)
- [合成 YUV 输入](../diagnostics/metering-audit-20261021/latest/app/src/uiPreview/java/android/media/AuditYuvImage.java)
- [最新原有测试汇总](../diagnostics/metering-audit-20261021/baseline-results.json)
- [main 测试汇总](../diagnostics/metering-audit-20261021/main-results.json)
- [反例实际输出](../diagnostics/metering-audit-20261021/reproduction-values.txt)
- [最新反例测试 XML](../diagnostics/metering-audit-20261021/latest/app/build/test-results/testDebugUnitTest/TEST-com.lightmeter.rawmeter.MeteringAuditRegressionTest.xml)
- [最新原有测试日志](../diagnostics/metering-audit-20261021/latest-tests.log)
- [main 测试日志](../diagnostics/metering-audit-20261021/main-tests.log)
- [最新反例日志](../diagnostics/metering-audit-20261021/audit-regressions.log)

在隔离副本目录复现最新反例：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*MeteringAuditRegressionTest' '-PuiPreview=true' '-Pandroid.useAndroidX=true' --offline --no-daemon --console=plain
```

优先处理仍在最新代码存在的增益与局部几何问题，再把已经修好的时间戳和饱和控制纳入待发布版本。不同流的独立校准继续保留，不能以强行对齐未校准读数代替这些修复。
