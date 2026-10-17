# 新增独立测光模式：代码结构、分支快照与接入边界

调研日期：2026-10-15。本文依据本地源码、Git 提交、现有设计文档和同项目对话状态编写；没有重新核查外部算法资料，没有改动应用行为，也没有执行构建或真机测光测试。

本次目标是为后续新增第四种测光算法建立代码地图。新算法的名称、曝光目标和具体规则尚未确定；已有矩阵测光调研作为候选参考，不代表已决定实现矩阵或某个品牌的算法。

## 1. 本对话的工作区域与基线

- 工作树：`C:/Users/15449/.codex/worktrees/399b/lightmeter a`。
- 本对话专用分支：`codex/independent-metering`，从 `main` 的 `7ff2147` 创建。
- `main` 与 `origin/main` 在本次读取时均为 `7ff2147`，对应 0.6.1 发布验证记录。
- 其他工作树仅做只读状态检查；分支源码主要通过当前工作树中的 `git show`、`git diff`、`git log` 阅读。没有在其他工作树切分支、编辑、提交或清理文件。
- 没有合并其他分支。以下比较采用固定提交，避免另一个对话继续工作时，比较对象发生变化。

**用户约束：尽量不要再给 `CameraController` 增添职能和代码。** 新算法的统计、曝光决策、质量判断、融合与专属状态分别由独立模块承担；控制器只保留必要的会话调度与薄委托。优先利用现有入口，目标是无需修改控制器；确实需要传递新的请求上下文时，限制为数据透传。

## 2. 项目组成

项目是单 Gradle 模块 Android 应用，不是多模块工程。生产 Kotlin 文件集中在同一个 `com.lightmeter.rawmeter` 包内，下面的分层是实际职责划分。

| 范围 | 主要文件 | 当前职责 |
| --- | --- | --- |
| 构建与平台 | `app/build.gradle.kts`、`settings.gradle.kts` | Android 9/API 28 起；compile SDK 36.1、target SDK 36；Kotlin、JDK 17、C++17；按 ABI 打包 |
| 应用入口与页面编排 | `MainActivity.kt`、`MeterLayout.kt` | Activity 生命周期、用户操作、Normal/Zone 和工具切换、结果展示、校准交互 |
| 仪表与交互 | `InstrumentView.kt`、`ZoneSystemView.kt`、`SettingsView.kt`、各工具 View | 主要使用原生 View/Canvas；Geometry、AdaptiveLayout 等负责布局与坐标 |
| 用户状态和曝光数学 | `MeterModels.kt`、`ExposureCompensation.kt`、`ExposurePreview*.kt` | MeterState、设置与偏好、EV/快门/光圈、曝光补偿和预览状态 |
| 相机与工作流 | `CameraController.kt`、`CameraSessionCoordinator.kt`、`CameraCombination*.kt`、`CameraRouteResolver.kt` | Camera2 设备/会话、流组合探测、逻辑/物理镜头、恢复与跨功能调度 |
| 实际采集与测量 | `RawLightMeter.kt`、`CompatibleLightMeter.kt`、`MeteringAnalysis.kt`、`MeteringFusion.kt` | RAW 请求与精确配对、YUV/ISP 测光、图像统计、EV 换算与多帧融合 |
| 原生 RAW 统计 | `RawMeterBridge.kt`、`app/src/main/cpp/raw_meter.cpp` | JNI、Bayer 通道统计、stride/黑白电平与稀疏采样 |
| 校准与质量 | `CameraCalibrationStore.kt`、`MeteringCalibrationCoordinator.kt`、`RawMeteringQuality.kt`、`RawHighlightProtection.kt`、`VignettingCalibration*.kt` | 按镜头与来源保存校准、响应校正、质量判断、高光重拍及暗角校准 |
| Zone 跟踪 | `ZoneCameraFramePipeline.kt`、`ZoneTrackingFrames.kt`、`ZoneTrackingEngine.kt`、`ZoneCoordinateMapper.kt` | YUV 帧池、点位跟踪、屏幕/传感器坐标与 Zone 会话 |
| 其他工具和记录 | `FilmNegative*.kt`、`Distance*.kt`、`Flash*.kt`、`Reciprocity*.kt`、`ParameterRecord*.kt` | 负片预览、距离、闪光、倒易率、参数记录与回放 |
| 验证与资源 | `src/test`、`src/uiPreview`、`src/main/assets`、`tools` | JVM 测试、可选 Robolectric 原生预览、胶片/许可资源与 OpenCV 构建工具 |

main 快照包含 213 个生产 Kotlin 文件、101 个常规测试文件和 6 个可选 UI 测试文件。生产依赖以 Android 平台与固定的本地 OpenCV AAR 为主；当前没有 Compose 依赖。RAW JNI 库与 OpenCV AAR 是两套独立用途的原生组件。

几个较大的文件按含空行的实际文本行数统计：`CameraController` 4185 行、`MeterLayout` 2899 行、`MainActivity` 2397 行、`MeteringAnalysis` 826 行。新功能应沿职责边界接入，避免继续集中到这些文件。

## 3. 需要区分的四个维度

| 维度 | 当前表示 | 含义 | 新算法的位置 |
| --- | --- | --- | --- |
| 测光算法/区域 | `MeteringMode` | `CENTER_WEIGHTED`、`SPOT`、`ANGLE` | 新增独立第四项 |
| 采集策略 | `MeteringPipelineMode` | AUTO 高精度、ISOLATED 稳定、FAST 兼容 | 继续选取可用数据源 |
| 流组合选择方式 | `MeteringCombinationSelectionMode` | 系统探测或用户手动确认组合 | 继续负责相机兼容性 |
| 操作页面 | Normal / Zone | 一次曝光建议或人工标点与调子放置 | 首版建议接入 Normal；Zone 的局部读数需明确保留 |

新测光算法不应被实现成第四种相机流组合，也不必增加一个与 Normal/Zone 并列的新主页面。产品展示位置可以调整，但算法与采集策略的含义需要保持分离。

## 4. 当前测光链路

```text
MainActivity 的测光操作
  → CameraController.measure(... meteringMode ...)
  → 现有测光计划、操作互斥、预览基线与会话调度
      ├─ RawLightMeter
      │    → RAW Image 与 CaptureResult 精确配对
      │    → MeteringAnalysis.analyzeRaw
      │    → RawMeterBridge / raw_meter.cpp
      │    → 质量检查、必要的重拍/补帧
      │    → MeteringFusion
      └─ CompatibleLightMeter
           → 已配对的 YUV 或同步显示 Bitmap
           → MeteringAnalysis.analyzeYuvPreview / analyzePreview
           → MeteringFusion
  → MeterReading
  → MainActivity.onMeterReading
  → MeterState / 仪表、曝光预览与参数记录
```

源码入口：`MainActivity.kt:200`、`:463`、`:1377`；`CameraController.kt:1563`；`RawLightMeter.kt:444`；`MeteringAnalysis.kt:47`、`:108`、`:170`。行号对应 main 基线。

### 4.1 现有三种算法的真实行为

- **点测光：** RAW 和 Bitmap 路径使用中心小方形区域；RAW 边长依据当前裁切短边的 `0.08` 计算，并有最小尺寸限制。YUV 路径分别按图像宽、高的比例计算中心矩形，其几何定义不能直接视为与 RAW 完全一致。
- **中央重点：** 分别测量小中心区域与较大的中心区域，再按 `0.7 / 0.3` 融合。RAW 路径先融合各通道统计再换算亮度；处理后路径融合亮度。较大比例为 `0.30`，并不是全画面平均。
- **按角度：** 根据镜头焦距、物理传感器尺寸和画幅换算 ROI，随后使用局部测光。RAW 路径保持物理角度独立于电子裁切变焦；兼容 FAST 策略目前限制选择 ANGLE。

`0.08` 与 `0.30` 是尺寸比例，不能当作画面面积占比。仪表画出的圆形标记也不意味着底层使用圆形像素掩膜。

**关键扩展陷阱：** 现有分析主要按“是否中央重点”“是否按角度”分支，其余值走点测路径。仅增加枚举和菜单，会让新选项在没有报错的情况下执行原有点测算法。仪表覆盖层也会继续画默认中心圈，必须明确处理新模式。

### 4.2 采集、统计和结果契约

- RAW 基础帧数由 `RawMeteringPolicy` 决定：ISO <500 为 1 帧，500–1199 为 2 帧，≥1200 为 3 帧，ISO 未知按 2 帧。
- 同曝光帧间亮度波动超过现有阈值时，可以在基础帧数之上最多再取 1 帧；饱和统计另走降低曝光的重拍流程。不能把基础帧数当作所有情形的总请求数。
- RAW 请求窗口深度为 **1**；Image 分析并关闭后才继续请求。`RawLightMeter` 是这段资源与超时的所有者。
- JNI 当前仅返回 `[R中位数, G1中位数, G2中位数, B中位数, 饱和比例, 有效样本数]`。这些数不包含空间分布，无法事后推导完整分区亮度图。
- JNI 使用完整 2×2 Bayer 单元稀疏采样，以每通道约 65536 个样本作为采样目标上限；不是先生成完整 RGB Bitmap。
- RAW 通道统计结合白平衡/颜色变换得到亮度。YUV/Bitmap 路径使用 `ProcessedLumaMetadata` 和 `ProcessedLumaMath` 恢复公共线性亮度域，并使用来源独立的响应校正。
- `MeteringFusion` 对逐帧 EV 和亮度分别取中位数，饱和比例取平均；它目前融合的是标量，不是网格或场景策略状态。
- 当前 `MeterReading.sceneEv100` 同时进入仪表和预览；MeterState 随后应用用户曝光补偿和闪光调整。场景相关的自动策略补偿必须与测量、用户补偿分开记录。

## 5. 近期其他对话分支

### 5.1 需要优先考虑的未合入工作

| 分支 / 固定快照 | 与 main 的关系 | 已核查内容 | 对本任务的影响 |
| --- | --- | --- | --- |
| `codex/camera-controller-boundaries` / `9b74d85` | main 之后 5 个提交 | 控制器职责拆分、显示预览时间戳、YUV 分析优化、ISP 元数据等待、系统测光组合设置合并 | 建议作为算法实现前的基础工作吸收；避免在旧控制器中重新加入已经拆出的职责 |
| `codex/non-normal-visual-polish` / `74df53a` | main 之后 1 个提交 | `ToolChromeRenderer` 与多个工具页/Zone 的视觉统一 | 与测光数学基本独立；若调整 Zone 或工具入口，需保留其渲染边界 |

相机分支的 5 个提交：

1. `ecfdf89`：提取 `CameraFilmPreviewCoordinator`、`CameraPreviewReferenceSampler`、`CameraStatusFormatter`、`CameraZoneRawCoordinator` 等职责所有者。
2. `c0a3ffb`：传递预览显示帧时间戳；提取 `YuvRegionAnalyzer`，复用临时统计缓冲，减少颜色转换对象分配，并调整中位数实现。
3. `cf5eed5`：ISP 已冻结的画面短暂等待它自己的曝光元数据，仍要求精确时间戳，等待受现有预算限制。
4. `0f4f29f`：补充自动策略、组合选择、手动确认的优先关系说明。
5. `9b74d85`：设置页把系统预设与手动组合选择合为一张卡片；旧枚举与偏好存储键不变。

该分支的 `CameraController` 从 4185 行降到 3501 行；`RawLightMeter` 在上述比较中没有改动。控制器拆分与 CPU 分析优化是不同工作，不宜重新混合。

分支文档记录了 649 项测试通过以及 Debug/Lint 结果；这是该对话的验证记录，本轮没有重新运行，也没有据此认定手机 HAL 已验证。

读取期间该分支从 `0f4f29f` 推进到 `9b74d85`，故本文固定使用后者。真正开始实现前应重新核查 HEAD 与未提交改动，不在另一个对话工作树直接合并或修复。

### 5.2 更早的功能分支

| 分支 | 读取时的提交 | 已核查的历史定位 |
| --- | --- | --- |
| `codex/ui-visual-polish` | `0e29fa0` | Normal 界面、参数记录和测距保留的早期工作 |
| `codex/automatic-distance-estimates` | `51410fd` | 自动距离与旧设备近似支持 |
| `codex/negative-preview-robust` | `5be4aa9` | 稳健负片预览与可编辑曲线 |
| `codex/opencv-conservative-slim` | `8baf51c` | 曾集成性能优先 OpenCV 与若干功能工作 |
| `codex/film-area-preview` | `8712362` | 负片照片选区与局部片基 |
| `codex/film-preview-fix` | `b23d1ef` | 负片、布局、距离与设备验收记录 |
| `codex/film-negative-realtime-docs` | `32970e6` | 负片实时路线与旧 Android 计划 |

这些分支来自较早的 0.5.0 线上，各自仍有 main 不包含的提交身份；这不等于其中的功能现在都缺失。main 的 `21579aa` 已集中整合负片、UI、距离等工作，随后又有 0.6.1 包体和 OpenCV 回调。因而不能依据 ahead 数字整支重合并。

特别是 main 在 `b3937c3` 恢复到固定的 compact OpenCV r2；不能把性能分支曾使用的 AAR 当成当前实际依赖。`codex/release-0.6.1` 与 main 同头，`codex/abi-release-packaging` 则位于 main 历史中。

## 6. 旧矩阵调研仍可用，但代码事实需要更新

已有 `docs/research/MATRIX_METERING_RESEARCH_2026_10_05_ZH.md`，覆盖全画面分区、调子约束、胶片用途和算法候选。

| 旧文档描述 | 本次源码核查 |
| --- | --- |
| 仅 SPOT 与 CENTER_WEIGHTED | 当前还有 ANGLE |
| 固定 3/5 帧 RAW | 当前基础 1/2/3 帧，可按质量有界补帧 |
| RAW 请求窗口深度 2 | 当前为 1 |
| ISP 三次样本、间隔 70 ms | 当前成功读数使用一个有效同步样本；失败会有界重试 |
| Bitmap 路径逆 sRGB；Y 平面不能直接视为线性 RAW | 当前已有 per-frame tonemap/gamma 解码与 YUV 编码元数据选择，仍不能据此保证撤销全部 ISP 局部处理 |
| Zone 跟踪帧使用本地接收时间，实时 EV 需另存传感器时间戳 | 该限制仍存在：它仅携带 Y 数据，`capturedAtNs` 默认是本地单调时钟，不能当成相机传感器时间戳 |

因此旧文档中的采集成本、代码行号和实时数据接入方式不能直接照搬。本文不修改其历史调研记录，也不重新确认其中的第三方资料或性能设想。

## 7. 新算法的建议边界

### 7.1 最小落地形态

新模式使用现有 `RawLightMeter` / `CompatibleLightMeter` 的采集所有权，在分析边界显式分流。先不增加第二套相机、ImageReader、RAW 事务或会话管理。

```text
既有采集所有者：拿到已配对的 Image/Bitmap 与元数据
  → 薄分析分流
      ├─ 原有三种模式 → 现有 MeteringAnalysis
      └─ 新模式       → 独立场景统计 → 独立曝光决策
  → 统一结果适配 → 既有结果回调与界面
```

建议从少量明确模块开始，名称为暂定：

| 模块 | 负责 | 不负责 |
| --- | --- | --- |
| `SceneMeteringModels` | 区域统计、来源、裁切/方向证据、决策与质量模型 | Camera2 请求与 UI |
| `RawSceneStatsAnalyzer` / `ProcessedSceneStatsAnalyzer` | 一帧的数据读取、线性亮度/区域统计；按需要增加一次 JNI 网格接口 | 持有相机、异步保留 Image、保存校准 |
| `SceneMeteringAlgorithm` | 纯数字输入到基础 EV、策略修正、推荐 EV、置信依据 | Handler、Session、Activity、文件写入 |
| 薄结果适配/分析分流 | 明确选择旧 ROI 或新算法，保持统一回调 | 独立复制整套采集生命周期 |

只有确实需要跨帧场景判断时，再增加独立的纯统计会话/融合对象；由已有采集组件调用。首版不预设必须有新 Coordinator，也不要求一次性把所有旧算法改成复杂插件框架。

`CameraController` 不持有网格、直方图、算法阈值、主体候选、胶片策略、跨帧滤波状态或算法专属超时。不能通过让新组件持有整个控制器来间接突破边界。

### 7.2 如果选择全画面评价方向

可以从“当前可见画幅内的分区统计 → 可重复的基础曝光 → 有限、可解释的规则修正”开始；先比较基准与规则效果，再决定主体识别或实时预估。

- 复用黑白电平、CFA、stride、元数据归一化与坐标几何，但新建能输出空间信息的统计入口。
- 一帧完成一次网格扫描，避免每个格子独立 JNI 调用和重复分配。
- 明确先计算局部亮度再统计，还是先统计各颜色通道再合成；两者不等价。新算法应有自己的定义与测试。
- 网格密度、采样预算、直方图桶数和规则阈值均待验证；旧文档的 16×12 等数字仅是候选。
- `RecordedRawGridSampler` 的 32×24 历史网格主要抽样绿色通道并相对中心归一化，服务于记录回放。可以参考坐标思路，不能直接当作已校准的全色测光网格。

### 7.3 测量与曝光策略分离

可以沿用项目现有区域测量关系：

```text
区域 EV100 = log2(N_phone² / t_phone × 100 / ISO_phone)
             + log2(Y_region / 0.18) + 对应来源的校正
推荐曝光 EV100 = 基础测量 EV100 - 自动增加曝光的档数
```

区域测量、自动策略补偿、用户补偿分别保存。不要把策略修正写进镜头校准，也不要让下游把已经偏移的推荐值继续解释为原始场景测量。

现有 `MeterReading` 只有标量 `sceneEv100` 等信息，可能需要附加可选决策结果并明确其含义；已有路径可继续使用原行为。处理后来源的响应校正依赖输入亮度，不能直接假设一个中心校正值适用于所有区域。

## 8. 接入前必须明确的交叉点

1. **Normal、Zone 和校准共用模式参数。** `MainActivity` 普通测光、Zone 单点、Zone 批量和校准均传入 `state.meteringMode`。新算法不能因此把每个 Zone 点都写成整幅推荐值。需要显式区分普通建议、局部读数和校准用途；`requestedSource` 只是选源，不能拿来代替用途。
2. **校准不能带场景偏好。** 新模式选中时，灰卡校准仍应测量来源响应，跳过主体、高光或暗部策略。校准样本的前后校正关系仍需满足现有保存契约。
3. **可见画幅与方向。** RAW、YUV、Bitmap 分别处于不同坐标域；必须映射当前画幅、电子变焦、传感器方向和镜像，只测用户实际选定的画面。
4. **暗角。** 现有 RAW 暗角增益只在带 `rawMeterPoint` 的局部路径中应用。全画面统计需定义逐区修正和有效校准检查，避免漏校正或重复校正。
5. **质量检查的语义。** 旧的通道中位数饱和、标量帧间波动不能自动代表新算法的重要区域质量。新统计要明确哪些已有判据可复用，哪些需要独立证据；不把帧间 spread 缺失解释成零噪声。
6. **图像所有权。** 新分析器同步借用一帧，输出数值后由既有 owner 关闭。若以后增加后台分析，只复制有界的必要统计/低分辨率数据，明确取消和 generation；不额外消费同一个 reader。
7. **实时预览另行实施。** Zone 的帧池预约和 tracker 忙碌丢帧不能直接成为新算法的调度依赖；绝对 EV 也不能用 `capturedAtNs` 配最新曝光参数。首版按键测量可以避免扩张共享帧管线。
8. **界面与历史记录。** 除枚举、偏好和设置选项外，需要新覆盖层语义与状态文案；当前“中央高光/中央信号”文案不适用于整幅统计。参数记录目前保存 NORMAL/ZONE、曝光、点位等，没有独立算法版本/策略字段；若需要可追溯，新增可选字段并兼容旧记录。

## 9. 建议实施顺序与验证

1. 开始编码前固定基线，优先评估把相机分支的拆分与修复合入本分支；UI 分支按涉及页面单独整合。本轮尚未执行这些合并。
2. 确定新模式优先解决的曝光问题与输出定义：全画面均衡、重要暗部、亮部约束或其他目标。名称和规则围绕目标确定。
3. 用独立纯 Kotlin 决策与合成统计建立基准；再接 RAW 分区统计，复用现有捕获和精确时间戳配对。
4. 完成处理后来源的适配、用途区分、结果模型与 UI 设置；明确来源不可用时的行为，避免静默变成点测。
5. 经过对照后再考虑实时预估、人脸/主体模型、不同帧数策略或额外曝光采样。这些会分别改变性能、采集和兼容边界。

有意义的验证应覆盖：均匀灰阶等价；整体亮度变一档时 EV 也变一档；构图外亮区无影响；裁切/旋转/镜像一致；小亮源和大面积亮区的区别；原有三模式数值与资源流程回归；校准不包含策略；Zone 保持局部读数；跨来源和旧记录兼容；取消/后台/镜头切换后旧结果无效。

JNI 增加统计时还需验证四种 Bayer、偏移与 stride、无效元数据、暗场/饱和和采样预算。设备评估分别记录捕获、会话切换、统计、决策及点击到读数的耗时，不能把 CPU 优化直接称为总测光速度提升。

本轮只新增本文。现有应用源码、相机行为、校准数据和其他工作树均未修改；本轮不据静态分析宣称新算法的精度或速度。
