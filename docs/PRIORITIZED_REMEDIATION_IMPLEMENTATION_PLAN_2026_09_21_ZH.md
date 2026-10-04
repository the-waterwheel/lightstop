# 光档：按优先级实施的修改方案

编写日期：2026-09-21。核对基线：`518df05`（`feat(ui): 完善校准、参数记录与自动测距`），版本 `0.4.0` / versionCode `9`。

本文是后续代码修改的实施方案，本次只新增文档。各任务均为待实施或待补充验证，不表示已经修复。开始实施时先核对 HEAD 和工作区差异；以下函数名是主要定位依据，行号仅对应上述基线。

## 1. 目标、范围与实施原则

优先解决可能产生错误测光、错误校准和陈旧位置记录的问题，然后处理主线程 I/O、几何适配、RAW 质量和兼容优化，最后继续拆分核心类、完善无障碍和发布验证。

本轮不把架构重写、引入新相机框架、引入 ARCore 或替换 OpenCV 作为前置条件。修复应沿现有组件边界逐步接入，每个提交可以单独审查、测试和回退。

路径约定：下文表格中的 Kotlin 主文件均位于 `app/src/main/java/com/lightmeter/rawmeter/`；JVM 测试位于 `app/src/test/java/com/lightmeter/rawmeter/`。标记“新增”的文件目前不存在，名称为建议；其余路径以现有代码为依据。

### 1.1 必须保留的长期约束

| 编号 | 约束 | 对本方案的直接限制 |
| --- | --- | --- |
| C01 | 厂商 HAL 隔离与自动降级 | 保留现有会话拆分、输出组合隔离、资源释放顺序、超时和降级。不得为了降低代码量合并 RAW、YUV、预览常驻输出。 |
| C02 | CameraController 按职责拆分 | 沿会话管理、预览请求、RAW 捕获、距离、元数据和恢复拆分；迁移所有权时不得产生第二个 session 管理者。 |
| C03 | 页面切换保留模式与镜头 | Normal、Zone、设置和摄像头管理切换不得改写用户模式或默认回自动主摄。只有实际设备/HAL 故障及有界验证失败进入现有恢复流程。 |
| C04 | 逻辑流和物理坐标分离 | 预览变换覆盖 sensor orientation、显示旋转、镜像、裁切和缩放。活动物理元数据更新不能直接覆盖逻辑预览方向。 |
| C05 | Normal/Zone 共享稳定传输几何 | 保持同一个稳定的 TextureView/SurfaceTexture 传输区域，模式变化主要影响上层裁切与控件。 |
| C06 | 自动对焦测距资格 | 继续只接受 CALIBRATED/APPROXIMATE、有效距离字段、可确认镜头及稳定 AF；不得将 UNCALIBRATED 或固定焦距伪装为米制精确距离。 |
| C07 | 多源测距长期路线 | 保留 ARCore Raw Depth/Depth、Camera2 CALIBRATED/APPROXIMATE 按能力、距离、置信度降级或融合的扩展点，优先改善 10 米内精度。新增 Provider 单独实施。 |
| C08 | 多摄视差与物体尺寸 | 只有基线和裁切后的有效焦距可靠时才启用视差；物体尺寸测距不作为默认方案。 |
| C09 | RAW 健康闭环与成本 | 先检查身份、时间戳和布局，再评估目标 ROI 质量；新增质量计算开销必须小于另拍一张 RAW 的成本。 |
| C10 | 不以饱和比例作为强制质量条件 | ROI 外高光不参与判断；ROI 内少量高光也不能仅凭比例强制重拍，只判断实际用于计算的统计量是否失真。 |
| C11 | Zone 普通测光范围 | 普通拍摄/单点测光只更新请求目标，不自动重写其他跟踪点的 EV。 |
| C12 | 显式“全部重新测光” | 同一张或同一组共享 RAW，逐点预览到 RAW 特征匹配；越界、匹配失败或身份过期的点跳过并计入未更新。 |
| C13 | 跟踪技术可替换 | 保留比较可商用开源方案与混合跟踪器的可能性；不能以替换库为由降低现有光流、锚点、重识别等功能。 |
| C14 | 校准作用域与校准分离 | 不增加全局固定 EV；设备/镜头/来源校准独立，测光校准、曝光预览校准和暗角校准不能混用或叠加两次。 |
| C15 | 参数记录完整性 | 闪光及距离快照继续写入，GN 原始数值与参考 ISO 分开保存；闪光工具临时 ISO 不改主转盘。 |
| C16 | APK 体积真实可比 | 以相同 ABI、功能和压缩配置比较 APK/安装占用；不得通过隐藏 ABI 或删除功能制造下降。OpenCV 裁剪必须保留实际及 JNI 依赖。 |
| C17 | 发布源集边界 | 规划、截图、测试数据、开发日志放在打包源集外；发布检查 APK/AAB 内容。运行时需要的许可证继续保留。 |
| C18 | Git 与发布一致性 | 不覆盖既有历史；源码、README、CHANGELOG、版本号、发布产物和 Release 对应同一提交。 |

**关于“拆分进程”的现状：**当前 Manifest 没有独立 `android:process` 相机服务，实际隔离主要是 Camera2 会话和输出组合拆分，旧 RAW 计划也如此解释。本方案保留这些边界，不将其误写为已存在 Android 多进程；如果后续分支新增进程隔离，实施时一并保留，不能擅自合并。

### 1.2 与已有代码和规划的差异处理

| 已有内容 | 本次核对结果 | 本方案处理 |
| --- | --- | --- |
| Android 16 大屏方向 | `MainActivity.canControlWindowOrientation()` 已处理 target 36 / sw600dp 限制 | 保留现有分支，补自由窗口和折叠测试，不重复新增方向锁定方案。 |
| Predictive back | 已注册 `OnBackInvokedCallback` | 不再列为缺失功能，只纳入页面状态回归。 |
| Zone RAW 提前发布结果 | 已有 `deliverZoneRawResult()`、`deliverZoneRawBatchResult()`、`resultDelivered` 和阶段计时 | 保留并补迟到回调/恢复失败测试，不按旧计划重复实现。 |
| 预览方向保持 | `CameraRuntimeMetadataCoordinator` 有意保留逻辑流 orientation | 将折叠适配列为需真机验证的坐标契约问题，不直接改成活动物理 orientation。 |
| 校准身份 | 已有运行时物理 ID、固定校准路由、短暂 null 容忍和算法 schema 3 | 在现有机制上增加签名，不能退回仅 selection ID，也不能把 session 重开视为换镜头。 |
| 闪光/距离记录 | 已有 `RecordedFlashSnapshot`、`RecordedDistanceSnapshot` 和 JSON version 3 | 扩展位置及异步保存时完整保留这些字段和旧记录兼容逻辑。 |
| 16 KB native 对齐 | 上次审查的 release 产物静态检查通过 | 列为持续发布检查，不列为当前已确认缺陷；仍需设备运行验证。 |
| 旧 RAW 计划“不在一张 RAW 上更新全部点” | 与现有用户主动批量重测功能的适用范围容易混淆 | 本文明确：禁止普通单点操作隐式全量刷新；显式批量重测必须共享 RAW。 |
| 旧跨设备/整改计划 | 包含历史现状和部分已完成事项 | 作为背景与设计依据，实施以当前代码、用户长期约束及本文差异说明为准。 |

相关已有文档：[跨设备审计](CROSS_DEVICE_CAMERA_COMPATIBILITY_AUDIT_ZH.md)、[整改与兼容计划](REMEDIATION_AND_CAMERA_COMPATIBILITY_PLAN_ZH.md)、[RAW 延迟与质量计划](RAW_METERING_LATENCY_AND_ADAPTIVE_QUALITY_PLAN_ZH.md)、[自动测距计划](DISTANCE_MEASUREMENT_IMPLEMENTATION_PLAN_ZH.md)、[闪光与距离记录计划](PARAMETER_RECORD_FLASH_DISTANCE_PLAN_ZH.md)。本次保留这些历史文档，不覆写其历史描述。

## 2. 优先级与依赖顺序

P1 表示优先修复正确性/数据问题；P2 表示性能、条件性兼容和交互改进；P3 表示后续演进。没有把尚未复现的设备问题标为普遍故障。

| 顺序 | 任务 | 优先级 | 依赖与交付边界 |
| --- | --- | --- | --- |
| 0 | 固定回归基线和状态不变量 | 前置 | 记录当前提交、输出组合、设备和数据样本；不更改运行策略。 |
| 1 | P1-01：统一物理结果解析、生产/探测一致 | P1 | 第一项代码修复；包括有界失败、Image 释放与既有恢复入口。 |
| 2 | P1-02：校准签名与兼容迁移 | P1 | 使用 P1-01 的已确认身份；独立数据版本，不连带更改测光公式。 |
| 3 | P1-03：位置时效与生命周期 | P1 | 可独立于相机签名实现；与记录模型迁移顺序保持一致。 |
| 4 | P1-04：记录 I/O 异步化与快照一致性 | P1 | 在位置模型稳定后接入；所有索引写入统一串行。 |
| 5 | P1-05：去除饱和比例单独触发重拍 | P1 | 直接对应 C10；先最小修复，再扩展自适应质量，不删除全部曝光保护。 |
| 6 | P2-01：明确预览/RAW 几何契约与折叠适配 | P2，高风险路径优先验证 | 依赖帧身份；先数据模型和测试，再由证据决定设备切换行为。 |
| 7 | P2-02：流选择和 AF 请求兼容优化 | P2 | 签名先落地，避免新尺寸/处理管线继续套用旧校准。 |
| 8 | P2-03：RAW 自适应帧数 | P2 | 依赖 P1-01/P1-05，先只记录质量结果，再启用决策。 |
| 9 | P2-04：备份、日志和 native/供应链加固 | P2 | 独立小提交；保持数据迁移和换机恢复可测试。 |
| 10 | P2-05：无障碍与界面性能 | P2 | 沿现有 Canvas UI 补能力，不重写相机层。 |
| 11 | P3-01：持续拆分与后续算法研究 | P3 | 每次只迁移一个职责，功能修复与纯重构分提交。 |

第一批完成标准是 P1 项目的行为验收通过，而非“改完某几个文件”。新硬门控带来的可用性变化必须有真实设备数据；设备不再能提供可信 RAW 时应明确降级或失败，不能恢复错误元数据 fallback。

## 3. 实施前的状态和回归基线

### 3.1 基线记录

在修改前保留以下信息到 `docs` 或项目外的开发产物目录，不放入 `app/src/main/assets`、`res`：

1. HEAD、工作区差异、versionCode/versionName、构建命令和产物哈希。
2. 至少一台当前稳定设备的路由、profile、preview/RAW/YUV 尺寸、RAW 请求次数、测光/恢复耗时。
3. 同一灰卡、固定光源和取景范围的 RAW/YUV/ISP 重复读数，记录来源和校准状态。
4. 旧 schema 校准、当前 schema 3 校准、JSON v3 参数记录、含闪光/距离/RAW 网格的恢复样本。
5. Normal → Zone → 设置 → 镜头管理 → 返回，以及后台恢复时的用户镜头、模式、曝光、闪光和标点快照。

上次审查基线为 273 个 JVM 测试通过、Lint 0 error / 65 warning、release 构建通过。这是上一轮结果，不等于后续修改已经验证；本文创建不重复执行 Android 构建。

### 3.2 所有任务共同遵守的状态规则

| 状态/资源 | 所有权与规则 |
| --- | --- |
| 用户选择 | 主线程的现有应用状态持有；后台任务只能返回结果，不能顺手重置模式/镜头。 |
| Camera2 资源 | 沿用 camera handler 和 `CameraSessionCoordinator`；新增解析器/策略不得自行开关相机。 |
| 回调身份 | 使用现有 camera generation、session revision、transaction identity；到主线程执行时也必须复核仍属当前操作。 |
| 图像和 Bitmap | 一次明确交接、一个释放者；成功、超时、取消、解析失败、session 替换均恰好释放一次。 |
| 校准写入 | 只提交本次来源成功、身份一致且签名有效的结果；失败来源不伪造成功。 |
| 短暂 physical ID = null | 同一已锁定校准操作内是未知状态，不直接认定切镜头；但不能凭上一帧身份给新物理帧补测光元数据。 |
| 参数快照 | 请求被接受时冻结镜头、模式、曝光、闪光、距离、选项、裁切和时间；异步完成后不读取新的全局值补齐旧记录。 |
| 已发布 Zone 读数 | 恢复预览失败只改变可继续操作状态，不重新发布或无条件抹掉已完成的可信测量。 |

## 4. P1-01：统一物理 capture result 解析

### 4.1 当前问题与修改文件

固定物理输出缺少对应结果时，多处 `effectiveCaptureResult()` 使用逻辑 `TotalCaptureResult` 整体兜底；工作流 RAW 探测又直接以逻辑结果配对。时间戳相同不能证明曝光、ISO、颜色信息属于相同物理输出。

| 修改文件 | 修改位置 | 修改方法 |
| --- | --- | --- |
| 新增 `CaptureMetadataSelectionPolicy.kt` | 纯选择规则和失败原因 | 输入请求路由、可用结果身份及字段有效性；输出可用、缺失物理结果、缺失字段、身份冲突等判定。 |
| 新增 `PhysicalCaptureResultResolver.kt` | Android Camera2 适配器 | 封装 API 分支，返回匹配的 CaptureResult、配对时间戳、metadata camera ID、路由类别；不持有 Image，不执行重试。 |
| `CameraController.kt` | `previewCaptureCallback`、`effectiveCaptureResult()`，约 2933/2963 行 | 使用统一解析器；无效测光元数据不得进入 latestResult、曝光基线、YUV/ISP 结果缓存和自动测距。预览是否有新帧由独立预览信号判断。 |
| `RawLightMeter.kt` | `onCaptureCompleted()`、`effectiveCaptureResult()`、配对和结束路径 | 删除局部逻辑结果 fallback；按本次请求身份校验，失败进入既有事务错误/重试入口。 |
| `RawRecordCaptureCoordinator.kt` | 结果选择、配对、DNG 创建 | DNG 使用与 RAW 路由一致的 characteristics/result；所需字段不可用时失败，保留 JPEG/其余参数的现有保存能力。 |
| `RawColorTemperatureEstimator.kt` | 结果选择及颜色字段校验 | 颜色能力单独判定；不能借用逻辑 AWB/颜色矩阵包装成物理色温。 |
| `VignettingCalibrationCaptureCoordinator.kt` | 结果选择、黑白电平和写入前校验 | 无效元数据不生成或写入暗角图。 |
| `CameraCombinationWorkflowProbe.kt` | `captureRawFrame()` / `finishRawFrame()`，约 183 行 | 注入本次 route 的不可变上下文，与生产捕获使用相同结果解析和硬性字段检查。 |
| `RawProbeFrameHealth.kt` | `RawProbeFrameHealthPolicy.evaluate()` | 增加身份解析结果，不增加全图像素扫描或高光阈值。 |
| `ActivePhysicalCameraTracker.kt`、`CameraRuntimeMetadataCoordinator.kt` | 结果存在性、当前日志分支 | 区分请求物理 ID、HAL 报告活动 ID、实际物理元数据是否存在；缺失告警限频。 |
| `TimestampedResultPairer.kt` 及上述调用点 | 拒绝帧清理、取消、超时 | 保持正式测量零容差；必要时增加按时间戳拒绝/移除能力，防止坏结果留下 Image 堵塞单帧管线。 |

### 4.2 解析规则

1. **固定物理输出：**只接受 requested physical ID 对应的结果。API 31+ 使用 `physicalCameraTotalResults`，API 28–30 使用 `physicalCameraResults`；同一 ID 的旧接口兼容可由适配器明确处理，但任何分支都不能转用整个逻辑结果。
2. **公开直接 camera ID：**该设备本身的 TotalCaptureResult 是合法来源，不要求它有 physical result map。
3. **逻辑自动输出：**使用逻辑输出自身的结果；活动物理 ID 用于描述和约束校准身份，不能因为知道活动 ID 就假定逻辑输出必须带 physical result。
4. 只有已取得同次捕获、同一指定物理结果而其中缺少 timestamp 时，才允许借用该次 TotalCaptureResult 的 timestamp 用于配对。曝光、ISO、光圈、AWB 和颜色矩阵不跨结果拼接。
5. 时间戳缺失/无效、Image timestamp 不相等、路由身份变化或本次必要字段缺失时拒绝该帧。不得用 `latestResult`、邻帧、时间容差放宽来挽救正式读数。
6. 不把所有可选 Camera2 字段都升级成强制条件。保留同一物理 characteristics 提供静态黑白电平等已验证回退；RAW 测光、DNG、色温、暗角各自声明所需字段。
7. 不因为整幅图暗、纯绿、曝光不足或少数高光而将元数据完整的设备判为 HAL 故障。

官方依据：[TotalCaptureResult](https://developer.android.com/reference/android/hardware/camera2/TotalCaptureResult)。以上“缺失时拒绝/重试”的处理是本项目对测量可信度的策略，不是声称所有厂商都会缺失该字段。

### 4.3 与资源和恢复流程的接法

- Resolver 只返回结构化状态，重试由当前 capture/事务所有者管理，不引入第二套恢复循环。
- 每个操作设置统一的请求数和总超时预算；元数据拒绝、质量追加、曝光重拍都计入预算，不能相乘扩大请求次数。
- 若结果先到且已被拒绝，记录有限大小的拒绝 timestamp 集合，随后同 timestamp Image 到达立即关闭；若 Image 已在 pairer，直接移除并关闭。拒绝集合随 session/事务结束清空。
- timestamp 缺失到无法定点清理时，终止该次捕获、清空它拥有的配对缓存，走现有恢复。不能只 `return` 后永远占用 RAW reader。
- 无效结果不能仅跳过赋值而留下可被误当成当前值的旧 `latestResult`。给曝光基线、ISP/YUV 元数据缓存、AF 距离样本附带 route/generation/timestamp 有效性；按消费用途使旧状态失效，保留历史展示但禁止参与新的正式计算。
- 单次丢帧不切镜头。达到有界失败条件后由现有 `CameraRecoveryStateMachine` / workflow policy 降级当前 route/profile；不直接改设置页或用户选择。
- 校准期间遵循既有“该来源失败、继续支持的下一来源、恢复原配置”规则，不在中途换镜头后继续同一轮校准。
- 预览帧还在更新但测量元数据不可用时，允许继续取景并提示测量不可用；不能把两者混成一个健康状态。

### 4.4 测试与验收

新增 `CaptureMetadataSelectionPolicyTest.kt`，扩展 `CameraCombinationWorkflowProbeTest.kt`、`RawProbeFrameHealthPolicyTest.kt`、`TimestampedResultPairerTest.kt`、`ActivePhysicalCameraSelectorTest.kt`。纯策略用普通数据测试，Android API 适配用仪器化测试或明确的 Camera2 测试替身验证，不能依靠 android.jar 的空实现。

必须覆盖：物理 map 缺失/错误 ID；物理 timestamp 缺失；两个结果曝光不同；逻辑自动路由 map 为空仍合法；Image/结果两种到达顺序；拒绝后迟到 Image；超时/关闭/session 更换；每个 Image 只释放一次；无效帧不会写校准、DNG、距离或标点。

验收：生产与探测对同一输入给出相同可用性结论；正常设备 EV 无无故变化；异常设备有界结束、可恢复预览；不存在“把逻辑曝光赋给指定物理 RAW”的路径。

## 5. P1-02：校准签名与数据迁移

### 5.1 当前状态及文件

现有 schema 3 已隔离 RAW/YUV/ISP 算法变更，`CalibrationEnvironmentStore` 也能识别换机恢复和部分相机目录变化，应保留。缺口是 OTA、处理管线和完整 route 变化覆盖不足；此外 `CameraUiInfo.calibrationCameraId` 会把逻辑自动当前物理镜头与固定物理输出都表示为 `logical@physical`，完整签名需要区分这两种输出。

| 修改文件 | 修改位置 | 修改方法 |
| --- | --- | --- |
| 新增 `CalibrationSignature.kt` | 签名、适用状态、比较策略 | 用纯不可变模型定义身份与兼容性，稳定序列化后生成 hash。 |
| `CameraCalibrationModels.kt` | `StreamCalibration` / `CameraCalibrationRecord` | 每个来源保存签名、算法版本、有效状态；保持原始 correction/reference/history。数据 schema 与算法版本分开。 |
| `CalibrationEnvironmentStore.kt` | `evaluate()` / 环境签名 | 保留 noBackup 安装身份检测；记录 build fingerprint hash 与可选 INFO_VERSION，区分换机、OTA、目录暂不可用。 |
| `CameraOpenConfigurationResolver.kt` | 已解析的 route、stream characteristics、尺寸 | 构建实际输出上下文；按来源记录实际 RAW/YUV/ISP 参数，不用“预期配置”冒充已成功配置。 |
| `CalibrationRouteIdentity.kt`、`MeteringCalibrationCoordinator.kt` | 运行中锁定身份、onReading/completion | 保留 null 容忍；将每个来源测得时的签名随结果传递，不在全部完成后从最后一个 session 反推。 |
| `CameraController.kt` | `rawMeteringContext()`、校准入口、校准读取、更新/恢复 | 分开存储身份和帧的实际元数据身份；不得再通过清空或改写 runtime 字段来表达存储绑定。用明确的 calibration context。 |
| `CameraCalibrationStore.kt` | `userCorrection()`、`readActiveRecord()`、`updateUserCorrections()`、`restore()` | 只应用与当前上下文匹配且有效的来源记录；写入版本化存储，保留旧 key 的只读迁移入口。 |
| `CameraCalibrationCoverage.kt`、`CalibrationView.kt` | 校准覆盖、历史展示 | “有历史数据”与“当前可使用”分开，不能仅根据 correction 非空显示已校准。 |
| `ExposurePreviewCalibrationCoordinator.kt` 与对应 store 方法 | 预览校准保存/读取 | 使用独立域和独立版本，不能套用正式 RAW/YUV/ISP 签名或修正。 |
| `VignettingCalibrationStore.kt` | `save/load/restore/gainAt` | 暗角签名单独包含 RAW route、尺寸和坐标域；不匹配时不应用增益，保留历史文件。 |

### 5.2 签名内容与兼容判定

建议字段：`schemaVersion`、`algorithmVersion`、`calibrationDomain`、`source`、设备类别信息、`buildFingerprintHash`、可选 `cameraInfoVersion`、`selectionRouteId`、`logicalCameraId`、`configuredPhysicalCameraId`、`confirmedPhysicalCameraId`、`routeKind`、输出宽高/格式、已知颜色空间/动态范围/处理配置标识。

`routeKind` 明确区分直接相机、逻辑自动、固定物理输出。来源签名按本来源实际处理路径生成，不把所有 session 标志简单拼进 hash。首次按严格匹配应用；以后只有具备数据证明的等价配置才加入明确兼容表。

不将 Normal/Zone 页面、菜单开关、手持旋转、布局尺寸、帧率选择、普通 ROI 或显示缩放直接当作全局测光校准失效因素。ISP 路径如果确实受输出变换影响，记录实际采样/颜色管线版本，而非每次 UI 旋转都强制重校准。

### 5.3 迁移规则

| 旧数据/变化 | 行为 |
| --- | --- |
| 当前算法不兼容的旧 YUV/ISP schema | 延续当前拒绝应用规则，不能借迁移重新启用。 |
| 有 correction 但没有新签名 | 导入为 `LEGACY_UNVERIFIED`，保留可见历史；不能将当前 fingerprint 反填成历史采集证据。 |
| 首次切换新签名体系 | 校准页明确提示需验证；未验证来源停止自动套用旧修正，读数标为未校准。用户取消验证时保留数据，不伪装已校准。 |
| OTA / INFO_VERSION / 测光算法变化 | 标为 `NEEDS_REVALIDATION`，清除当前实时读数对旧修正的依赖，不改写历史参数记录。 |
| 同一校准轮次短暂 physical ID 为 null | 保持已锁定存储身份，等待有效帧；不清空签名、不认定换镜头、不凭空确认物理元数据。 |
| 已确认 physical A 变为 B | 中止当前轮次或当前操作，不将跨镜头均值写入。 |
| 固定物理输出失败后逻辑回退 | 使用逻辑 route 自己的签名，不能复用同名物理 correction。 |
| 某来源不支持或捕获失败 | 不创建成功项；原记录保留为历史，并按当前签名决定其是否仍适用。 |
| 恢复旧历史 | 检查签名和算法版本；不匹配仅能用于重新验证参考，不能直接激活。 |
| 换机恢复 | 保留现有安装身份检测；相机目录暂为空不能触发全局清空。 |

用版本化 key/独立版本文件迁移并保留旧存储，写入成功后才更新迁移标记，支持重复执行和中途退出。首次升级不自动“制造当前有效签名”。三种测光来源、曝光预览和暗角各有有效性，某一来源变化不能粗暴删除全部校准。

旧版应用可能忽略新签名，因此降级运行本身不具备完整安全保证。回退应发布保留签名校验的修复版本；不要通过恢复旧代码重新启用未验证修正。

### 5.4 测试与验收

新增 `CalibrationSignatureTest.kt`、`CalibrationMigrationPolicyTest.kt`；扩展 `CalibrationRouteIdentityTest.kt`、`MeteringCalibrationCoordinatorTest.kt`、`CameraCalibrationCoverageTest.kt`。存储迁移另做设备测试。

覆盖：同机 OTA；同物理镜头的逻辑与固定路由；source/尺寸改变；历史恢复；旧 schema；迁移中断；同轮次 null → A → null → A；A → B；仅变 UI 方向和模式；部分来源失败；预览校准不影响正式测光；历史记录中的 EV 和闪光快照不被重新计算。

验收：无法证明适用的校准不会被自动应用；历史可查看；用户看到的有效状态与算法实际读取一致。

## 6. P1-03：位置时效、取消与快照

### 6.1 修改文件

| 修改文件 | 修改位置 | 修改方法 |
| --- | --- | --- |
| 新增 `ParameterLocationPolicy.kt` | 纯时效/精度策略 | 校验坐标范围、有限数值、精度、单调时钟年龄及权限级别。 |
| 新增 `ParameterLocationCoordinator.kt` | 定位请求生命周期 | 封装 LocationManager、request generation、CancellationSignal、超时及缓存；只持有 application context。 |
| `MainActivity.kt` | `enableParameterGps()`、`refreshParameterLocation()`、`rememberParameterLocation()`、`onPause()`、记录入口 | 移交定位职责；监听结果只更新当前有效请求。保存时重新检查位置年龄。 |
| `ParameterRecordModels.kt` | `RecordedLocation`、草稿快照 | 以可空默认字段增加 provider、ageMsAtCapture、精度/权限质量；运行时 fix 保留 elapsedRealtimeNs。 |
| `ParameterRecordRepository.kt` | `RecordedLocation.toJson()` / `toLocation()` | 兼容 v3 缺失字段；位置年龄未知的旧记录原样可看，不可当成当前缓存。 |
| `ParameterRecordToolView.kt`、`ParameterRecordEditorView.kt`、`ParameterHistoryView.kt` | GPS 状态及记录显示 | 区分用户启用意图、正在定位、可用粗略位置、无有效位置；不因暂时无 fix 改写长期选项。 |

### 6.2 实现方法

- API 30+ 使用带取消能力的 `getCurrentLocation()`；API 28–29 使用现有单次请求加明确超时/移除。所有位置调用捕获权限撤销导致的 SecurityException。
- 可先读取 last-known，但仅通过策略的才成为候选。定位关闭、权限撤销、页面关闭/后台暂停和新请求替换时取消旧请求并清理当前 fix。
- 用 `SystemClock.elapsedRealtimeNanos()` 与 `Location.elapsedRealtimeNanos` 判断时效，不与 Camera2 SENSOR_TIMESTAMP 或墙上时钟混算。
- 初始建议：60 秒以内为新鲜；请求等待上限 8 秒；精确位置精度参考 100 米。以上是待设备测试冻结的工程起始值，不是精度承诺。
- 用户只授予粗略位置时允许明确标为“粗略位置”，不强制重复申请 fine 权限，不把粗略数据冒充精确 GPS；异常精度和时间戳仍拒绝。
- 记录开始时冻结有效候选及其年龄。没有有效 fix 时本条不保存位置并提示，不无限阻塞拍摄，也不事后把下一地点的新 fix 填入已开始的记录。
- GPS 开关状态和本条是否包含位置分离。开关保持用户选择；没有有效位置不意味着自动关闭用户设置。
- `recordTime=false` 时不要通过新增 location wall-clock 字段偷偷保存精确时间。优先持久化年龄和质量；若将来保存定位 UTC 时间，也应遵守记录时间选项和隐私说明。
- listener 取消只取消自身 request generation，避免旧 listener 回调清除新 listener。

### 6.3 验收

新增 `ParameterLocationPolicyTest.kt`；设备覆盖 stale last-known、后台恢复、定位关闭、权限撤销、粗略授权、超时、连续记录和迟到回调。扩展 `ParameterRecordModelsTest.kt` 验证旧 JSON 和时间关闭语义。

验收：上次会话或已过期位置不能进入新记录；无定位不阻止保存 JPEG/曝光/闪光/距离；旧记录不因新增字段丢失。

## 7. P1-04：记录异步 I/O 与快照一致性

### 7.1 修改文件

| 修改文件 | 修改位置 | 修改方法 |
| --- | --- | --- |
| 新增 `ParameterCaptureCoordinator.kt` | 一次记录操作、状态与取消 | 持有 draftId、冻结输入、Bitmap 所有权、I/O 作业与 UI 回调 token。 |
| 新增 `ParameterRecordIoDispatcher.kt` | 串行 I/O | 单一有界 executor 执行仓库初始化、压缩、索引和文件操作；向 UI 返回不可变快照。 |
| `MeterLayout.kt` | `startParameterCapture()`、`captureParameterPreview()`、`onSaveRequested()` | 主线程只读取必要 View 状态并取得 Bitmap；裁切/编码/写文件移入任务；保存按钮防重复提交。 |
| `MainActivity.kt` | `captureParameterRecord()` 与 RAW completion | 使用冻结 options/route/zoom/frame/snapshot，不从完成时的新 UI 状态补值；Camera2 仍走原 handler。 |
| `ParameterRecordRepository.kt` | 构造/init、save/discard、start/finish/delete/updateZonePoints、load/saveIndex | 全部可变仓库状态和写入由一个 executor 串行拥有；保留 AtomicFile、路径检查、事务回滚与隔离删除。 |
| `ParameterRecordTransaction.kt` | 回滚与恢复入口 | 保持同一所有者；初始化恢复完成之前不接受新的写入。 |
| `ParameterRecordEditorView.kt`、`ParameterRecordToolView.kt`、`ParameterHistoryView.kt` | 保存/删除/列表刷新 | 明确 loading/saving 状态；主线程仅显示快照，UI 取消不直接删除后台正在使用的文件。 |

### 7.2 正确的操作顺序

1. 主线程接受记录请求，冻结主曝光、闪光 GN 数值/参考 ISO/工具 ISO、距离来源/质量、标点、选项、镜头和裁切。使用本次位置策略快照。
2. 主线程获取 `TextureView.getBitmap()` 后明确交给后台；原 Bitmap 只由当前作业 finally 回收。获取失败也结束本次状态，不保留“正在保存”。
3. 后台做裁切、JPEG 编码和 pending 文件写入。执行期间不读取 View、不持有 Activity、不修改全局相机选择。
4. DNG 捕获仍在现有 capture coordinator；本方案第一版保留当前 JPEG → 可选 RAW 的顺序，不为加速同时打开其他相机或扩大输出组合。
5. 得到完整草稿后由主线程显示编辑；RAW 失败继续允许保存其余已冻结字段。
6. 点击保存，将当前编辑结果形成不可变草稿交给单一仓库队列；事务提交成功后才显示已保存。
7. 取消尚未提交的草稿时标记取消，等待使用中的压缩/写入退出，再由该作业删除所属 pending 文件。已经提交的记录不能因旧 UI 关闭而被误删。
8. Activity 退出后不向旧 View 回调。已进入不可分割提交阶段的应用级作业允许完成并由仓库留下结果，不能为了取消 UI 而从另一个线程强删文件。

不要只给 `save()` 加线程而把 delete/update/init 留在主线程，否则会新增状态竞争。不要在 UI 上阻塞 `Future.get()`。第一版继续使用现有 JSON/AtomicFile，不同时迁移 Room；索引分页或数据库另立任务。

### 7.3 数据与一致性

- 当前 JSON v3 的 flash/distance/rawGrid/notes/location 全量 round-trip；位置新增字段使用显式兼容版本迁移。
- JPEG 是取景快照，DNG 是后续正式捕获，两者不是同一帧。异步化不能强化为“同帧”声明；记录必要的采集顺序信息，不把后续测光结果覆盖冻结曝光。
- 所有保存/删除/标点修改保留 categoryId、recordId 及版本/token，旧任务不得作用于刚切换的分类或新草稿。
- 现有路径安全规则、删除 quarantine 和事务 marker 不因性能优化移除。
- 大量历史记录加载也放到相同 I/O 层；首屏显示加载状态，不在 View 构造中全量解析/清理。

### 7.4 验收

复用 `ParameterRecordTransactionTest.kt`、`ParameterRecordPathPolicyTest.kt`、`ParameterRecordDistanceSnapshotTest.kt`；新增调度与取消策略测试。设备上用 StrictMode 和主线程 trace 检查记录链路的压缩/磁盘写入，区分必须主线程的 Bitmap 获取。

覆盖慢存储、大 DNG、低空间、连续点击保存、保存中后台切换、压缩中取消、索引失败、初始化恢复、删除和保存交错、改变闪光工具 ISO 后旧草稿仍保留原值。验收无数据丢失、无重复保存、无 Bitmap/Image 泄漏，主线程不执行文件复制和完整索引写入。

## 8. P1-05：修正高光重拍触发条件

当前 `RawHighlightProtectionPolicy.nextStage()` 以 `clippedFraction > 0.01` 触发降曝光重拍，即使只统计目标 ROI，少量亮点也可能未影响通道中位数。这与 C10 不一致，不能继续作为默认强制质量门槛。

| 修改文件 | 修改位置 | 修改方法 |
| --- | --- | --- |
| `RawHighlightProtection.kt` | `nextStage()` 与策略输入 | 迁移为 `RawExposureRetryPolicy`；输入实际采用通道/亮度统计是否失真，饱和比例仅作诊断。保留曝光边界计算的独立数学函数。 |
| `RawLightMeter.kt` | `retryForClippedHighlights()`、`processPair()`、批量路径 | 只有目标统计确实无效才申请有界曝光重拍；失败不覆盖旧 Zone EV。 |
| `MeteringAnalysis.kt`、`RawMeterBridge.kt`、`app/src/main/cpp/raw_meter.cpp` | 已有 ROI 统计出口 | 优先复用已有四通道中位数和白电平；确需增加字段时使用版本化固定输出，在原有循环累计，不二次扫描。 |
| `RawHighlightProtectionTest.kt` | 旧 1% 阈值断言 | 替换为“统计量受污染才采取动作”用例，不能保留旧阈值测试迫使新实现回归。 |

先实施最小策略修复：少量饱和且最终中位数有效时直接接受；真正接近上限、影响所用通道的统计量才重拍或报低可信。具体白电平余量阈值需结合 black/white、噪声与设备数据确定，不能随意改成另一个全局像素比例。

批量重测时为每个目标独立判定，但 RAW 请求仍是共享请求。若追加不同曝光帧，所有目标都按同一共享采集集和各自 ROI 处理，不为每点单独拍摄；ROI 匹配失败只跳过该点。不得为了一个无关点高光重拍普通单点任务。

验收用例：目标外强光、ROI 内少量反光点、目标中位数饱和、黑场、彩色目标、越界点、共享批量重测。实测正常目标的请求数不再由 1% 饱和比例单独增加。

## 9. P2-01：预览几何、折叠屏和大屏回归

### 9.1 修改边界

上一轮审查把保留旧 orientation 直接归为风险；进一步核对后必须保留该代码解决逻辑流宽高比跳变的意图。需要修正的是坐标域表达不清，不能简单将一行赋值改成 physical orientation。

Android 指南指出折叠状态可能改变活动传感器方向和 facing，应重新查询并适配；具体如何作用于本项目的逻辑预览与 RAW，必须结合实际输出验证：[折叠设备相机指南](https://developer.android.com/media/camera/camera2/foldable-devices)。

| 修改文件 | 修改位置 | 修改方法 |
| --- | --- | --- |
| 新增 `CameraGeometryContext.kt` | 不可变几何域 | 分开 `PreviewStreamGeometry` 和 `RawSensorGeometry`，均带所属 route、generation、orientation/facing/activeArray/crop。 |
| `CameraRuntimeMetadataCoordinator.kt` | `observeActivePhysicalCamera()` | 更新活动物理元数据域；不直接覆盖预览域，尤其不混合物理 activeArray 与逻辑 orientation。 |
| `CameraOpenConfigurationResolver.kt`、`CameraSessionCoordinator.kt` | 输出配置成功位置 | 发布实际预览输出的几何上下文，以 session 输出为依据。 |
| `CameraPreviewTransform.kt`、`PreviewSurfaceCoordinator.kt` | 变换计算/首帧几何校验 | 只读取 preview 域、View 所在 display rotation 及显示裁切；保留稳定传输矩形。 |
| `RawPreviewRegistration.kt`、`MeteringAnalysis.kt` | 屏幕到 RAW 和局部匹配 | 使用明确的 preview→sensor 映射以及本次 RAW 物理域；镜像只撤销一次。 |
| `MainActivity.kt`、`MeterLayout.kt`、`ZoneSystemView.kt`、`ZoneTrackingEngine.kt` | 配置变化/跟踪坐标版本 | 几何变化递增 epoch，旧 epoch 的跟踪结果不能覆盖新坐标；存量 EV 不因坐标暂不可用丢失。 |
| `ParameterRecordModels.kt`、`RecordedRawGridSampler.kt` | 历史网格方向和镜像 | 保存采集时的坐标变换，旧历史沿旧版本读取，不使用当前设备方向重新解释。 |

### 9.2 行为规则

- 只有镜头元数据变化、预览输出坐标域未变：保持预览矩阵和传输尺寸，更新物理测量域并使过期测距失效。
- 折叠/display/输出确实改变：等待现有窗口稳定机制，原子更新几何上下文与裁切；必要重建交给既有 session owner，不能在每帧回调中重建。
- 无法确认 preview→RAW 映射时暂拒绝点测或要求重新匹配；不输出错误坐标的可信读数，也不因此自动切回主摄。
- Normal/Zone 切换、设置返回不更改底层传输比例。用户选择的镜头与测光模式单独保存，不随几何上下文重置。
- 保留 `canControlWindowOrientation()` 和 predictive back 现有实现。Android 16 大屏限制需要回归而非重复修复：[官方大屏行为](https://developer.android.com/develop/adaptive-apps/guides/app-orientation-aspect-ratio-resizability)。

### 9.3 验收

扩展 `CameraPreviewTransformTest.kt`、`PreviewOutputGeometryTest.kt`、`ZoneCoordinateMapperTest.kt`、`ModeTransitionDirectionTest.kt`、`CameraOutputAspectIdentityTest.kt`，新增几何上下文契约测试。

覆盖 sensor orientation 0/90/180/270、显示旋转四方向、前后摄、镜像、非 4:3、Normal↔Zone 往返、180° 旋转、分屏拖动、DeX 外接屏、折叠内外屏切换。检查圆形标靶不拉伸、固定点 ROI 命中、历史 RAW 网格查询位置一致。未测机型记录为待验证，不能宣布全厂商修复。

## 10. P2-02：输出流排序与 AF 兼容

### 10.1 输出配置

修改 `CameraStreamSelector.kt` 的尺寸排序、`CameraOpenConfigurationResolver.kt` 的候选输入、`CameraSessionCoordinator.kt` 的 OutputConfiguration、`CameraCombinationSelectionStore.kt` 的配置缓存；扩展 `CameraStreamSelectorTest.kt` 和 `CameraCombinationPolicyTest.kt`。

- API 29+ 读取推荐输出，作为排序参考；推荐表缺失或没有当前用途时使用现有完整表。[推荐配置文档](https://developer.android.com/reference/android/hardware/camera2/params/RecommendedStreamConfigurationMap)
- 保留不超过现有预览预算的 4:3 优先策略、帧时长检查和用户明确选择，不自动升级至最大分辨率。
- API 33+ 只有设备声明支持且组合用途满足约束时设置 stream use case；不能把 RAW/跟踪 surface 全标为 PREVIEW。默认关闭的新优化经真机比较后启用。[用途说明](https://developer.android.com/media/camera/camera2/capture-sessions-requests)
- 测试优化配置失败时，先恢复同 route/profile/尺寸的未标注默认配置，再走现有降级，避免一次提示 API 失败污染镜头可用性。
- 缓存键包括实际 route、profile、尺寸、format、用途、OS build 和兼容策略版本；人工确认结果不得跨变更静默沿用。
- 保留 `isSessionConfigurationSupported()` 为预检提示、真实创建及新帧为最终证据的现有处理。

### 10.2 自动对焦距离

修改 `CameraDistanceCaptureCoordinator.kt` 的 `triggerAutoFocus()` / `stop()`，配合 `CameraPreviewRequestCoordinator.kt` 和 `Camera2FocusDistanceProvider.kt`；复用现有距离策略测试。

现有一次 START 可能留下连续 AF 的锁定状态，应明确本轮 AF 的 CANCEL → START → IDLE 请求与结束解除流程，按支持的模式实现，不对 AF_OFF 镜头提交无意义触发。正式预览 builder 不携带持续 START。参考 [小米 3A 指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1598)。

本轮完成/超时/取消时仅释放自己发起的 AF 锁；scene generation 或 session 已改变时不能向旧 session 提交请求。保留 CALIBRATED/APPROXIMATE 资格、样本稳定性与时效，失败保留手动距离能力，不把错误距离用于闪光补偿。

验收包含连续多次测距、近远切换、AF_UNCALIBRATED、固定焦镜头、预览恢复和 RAW-isolated 切换。ARCore/DEPTH16 不随本任务直接接入，避免引入相机资源竞争。

## 11. P2-03：RAW 自适应质量和来源可信度

沿用已有 RAW 质量计划的分阶段思路，修正其中已过时的“结果提前发布尚未完成”描述和对批量重测的歧义。

| 修改文件 | 修改位置 | 修改方法 |
| --- | --- | --- |
| 新增 `RawMeteringQuality.kt` | 质量模型和动作 | 区分 ACCEPT、追加同曝光、改变曝光、REJECT；系统误差与随机误差分开。 |
| `RawMeterBridge.kt`、`raw_meter.cpp`、`MeteringAnalysis.kt` | 原 ROI 循环及统计出口 | 复用采样，增加有界噪声/黑白余量统计，优先读取对应镜头 SENSOR_NOISE_PROFILE；无二次全图扫描。 |
| `RawLightMeter.kt` | `RawMeteringPolicy.frameCount()`、accumulator、`fillPipeline()`、融合 | 首帧后决定是否追加；保留单张 RAW 在途、总预算、Image 及时关闭。 |
| `MeteringFusion.kt` | 有效帧融合 | 只融合身份/坐标/曝光解释有效的测量，不用更多帧掩盖系统偏差。 |
| `ZoneRawTransaction.kt`、`CameraController.kt` | 质量摘要与既有阶段计时 | 扩展既有事务，保留提前发布结果及恢复锁；不再重复实现一套事务。 |
| `MeterModels.kt`、`InstrumentPresentation.kt`、`ZoneSystemView.kt` | 读数来源/质量呈现 | 显示有意义的来源与低可信原因；避免给未经设备标定的误差估计冠以绝对精度保证。 |

先运行“只统计不改变决策”阶段，比较当前固定 1/2/3 帧、强制单帧和建议策略。质量计算预算采用已有计划的 `P95(新增质量计算) < P50(额外一张 RAW)`，在低端和高像素设备分别测量。固定 ISO 仅作信息不足时的辅助信号。

批量重测共享同一捕获集，每点独立配准/判定；坐标失败不追加一张作为万能修复。已越界点不进入有效目标集合，保留旧值并计未更新。

YUV/ISP 多点响应校准列为后续子任务：修改 `ProcessedLumaMath.kt`、`CompatibleLightMeter.kt`、`MeteringCalibrationPlan.kt` 和来源校准模型，保存亮度范围/残差。不得宣称单 EV 偏移可消除所有厂商局部 HDR 或 tone mapping 差异，也不得连带改动已验证 RAW 公式。

## 12. P2-04：安全、备份、日志和依赖

| 修改文件 | 修改位置 | 修改方法与兼容边界 |
| --- | --- | --- |
| `app/src/main/res/xml/backup_rules.xml` | Android 9–11 敏感文件 include | 云备份至少要求客户端加密；先明确旧规则表达限制，不能写 `clientSideEncryption\|deviceToDeviceTransfer` 误以为 OR。两个 flag 同列要求同时满足；如需只保留 D2D，应选择明确的单独策略。 |
| `app/src/main/res/xml/data_extraction_rules.xml` | cloud-backup/device-transfer | 保留 Android 12+ 分通道规则与无加密能力禁云备份设置；测试新校准存储路径是否正确纳入。 |
| `PRIVACY.md`、`MeterLayout.kt` 隐私说明 | 备份/位置行为 | 与实际规则和位置质量字段一致；说明大 DNG 配额限制，不承诺系统一定备份或恢复。 |
| 新增 `MeterDiagnostics.kt` | 诊断级别、限频、脱敏 | 对 RAW/路由摘要设置 Debug 或用户显式本地诊断开关。 |
| `MeteringAnalysis.kt`、`CameraController.kt`、`RawLightMeter.kt` 等 | 高频 `Log.e` 和字符串构建 | 正常流程改正确级别；关闭诊断时不构造大字符串。保留必要错误原因，不输出位置/备注/图像。 |
| `app/src/main/cpp/raw_meter.cpp` | JNI 数组分配、ROI 加法、参数检查 | 检查 NewDoubleArray/异常；使用足够宽整数限制 ROI，验证有限 black/white 数据。对预期有效输入统计语义不变。 |
| `app/build.gradle.kts`、`tools/opencv-slim/` | 本地 AAR 输入与构建说明 | 增加校验和验证、来源/工具链/裁剪模块记录和依赖清单；核实构建可复现程度，不把有脚本等同于逐字节可复现。 |

备份实现依据：[Android Auto Backup](https://developer.android.com/identity/data/autobackup)。这些是隐私加固项，不意味着应用当前存在网络上传或已确认备份泄露。

OpenCV 已有精简构建及 r2 裁剪记录，继续沿 `tools/opencv-slim/README.md` 审核。`imgcodecs`/`videoio` 等还可能由 Java JNI 胶水引用，不能仅搜索业务调用为空就删除。当前 arm64/armeabi-v7a/x86_64 支持保持；AAB 分发节省应与 universal APK 优化分开报告。

native 加固配合 host/native 边界测试或 sanitizer 测试：非 direct buffer、容量不足、stride padding、极端 ROI、四种 Bayer 排列、非法黑白电平。正常输出与基线一致。

## 13. P2-05：无障碍、绘制开销和交互

优先修改 `InstrumentView.kt`、`ZoneSystemView.kt`、`SettingsView.kt`、`CameraManagementView.kt`、`ParameterRecordToolView.kt`、`ParameterRecordEditorView.kt`，再覆盖其余工具。

- 为一个 Canvas 内的多个按钮/拨盘建立独立虚拟节点、稳定 ID、bounds、选中/锁止状态、点击/增减 action 和键盘焦点。
- 项目当前 `android.useAndroidX=false`。第一版可使用平台 `AccessibilityNodeProvider` 并抽出公共 helper；不能直接加入 `ExploreByTouchHelper` 而忽略 AndroidX 构建依赖。若决定引入 AndroidX，单独提交并测量体积/兼容影响。
- `performClick()` 保留，但不能当作完整无障碍实现；让鼠标、键盘与触摸走同一业务 action，减少交互分叉。
- 绘制中缓存 Paint、Rect、Path、格式化结果等，按实际 profiler 结果处理 Lint DrawAllocation；不为消警改变仪表样式或缩小命中区域。
- 大字体不只替换 deprecated scaledDensity；应结合平台字体缩放 API 重算文本、控件和滚动范围，验证 1.0/1.3/1.5/2.0 字体。
- 页面打开/返回仍保持模式、镜头、Zone 点、左右手和曝光锁，不把无障碍焦点恢复与业务状态重置混为一谈。

验收：TalkBack 可独立操作测光、锁止、镜头选择、保存和删除；DeX 键鼠可操作；最大字体无关键按钮不可达；普通触摸行为和视觉截图无非预期变化。

## 14. P3-01：拆分与后续研究

### 14.1 拆分顺序

| 当前文件 | 建议逐步迁出职责 | 约束 |
| --- | --- | --- |
| `CameraController.kt` | 本文 resolver、校准上下文、RAW 事务编排，再整理恢复接线 | 已有 Session/PreviewRequest/Distance/RuntimeMetadata coordinator 优先复用，不另造同名管理层。 |
| `MainActivity.kt` | 位置 coordinator、参数记录 coordinator、纯 UI 状态转换 | Activity 仍负责平台生命周期和权限 UI，后台类不持有 Activity。 |
| `MeterLayout.kt` | 记录流程、页面导航、各工具装配 | 页面转换前后显式保留状态，不因拆分重建 TextureView。 |
| `ZoneTrackingEngine.kt` | 帧调度、特征恢复、陀螺仪传播、质量判定 | 坐标 epoch 和特征所有权明确，先保持算法输出与时序。 |
| `MeterModels.kt` | 按 camera/metering/calibration/records/flash/distance 拆模型 | 初期不改变序列化字段和 package；大规模移动目录放在独立提交。 |

先以“新增责任是否独立、状态是否只有一个所有者”验收，再逐渐接近旧计划的 CameraController 500 行目标。不能只为满足行数把耦合状态搬入一个无边界 helper。

### 14.2 后续单独立项

- 多源测距：沿已有 DistanceCoordinator/Provider 结构实现能力查询、精度与时效，不绕过 Camera2 资源隔离；ARCore 同时占用相机的问题必须先验证。
- 跟踪器比较：建立固定视频/传感器轨迹集，测相对画面延迟、漂移、重入恢复、CPU/内存和商用许可，再比较 OpenCV 与候选方案。
- 多摄视差：只有基线、有效焦距、重叠视野和同步证据完整时实验启用；不可验证时保持关闭。
- 应用级加密：需同时解决密钥恢复、换机和文件导出，不能仓促加密后导致用户历史不可恢复。

## 15. 厂商、系统和场景测试矩阵

表中设备族是测试选择方向，不是已验证支持名单。厂商官方资料只能解释接口边界，不能替代本项目真机记录。

| 维度 | 覆盖范围 | 必测行为 |
| --- | --- | --- |
| Android 9 / API 28 | 最低版本、旧物理结果 API | 无 API29 active ID 时保持未知；合法直接相机可测；旧定位和备份分支。 |
| Android 10–11 | 多摄、推荐流可用设备 | 真实 session、物理元数据、API30 定位取消、旧备份策略。 |
| Android 12–12L | 分屏、近似位置、物理 total result API | 结果解析新 API、权限变化、D2D/云备份分流、窗口变化。 |
| Android 13–14 | stream use case 支持/不支持各一类 | 默认配置回退、预测返回、前后台恢复。 |
| Android 15–16 | 4 KB 与 16 KB、target36 行为 | native 加载、边到边/系统栏、sw600dp 方向限制、任意窗口比例。 |
| Pixel/AOSP | 手机和 Fold/Tablet | 标准行为参考、物理切换、固定标靶 ROI 和 180° 旋转。 |
| Samsung | Snapdragon/Exynos、Z Fold/Flip、DeX | 外接显示旋转、内外屏、鼠标键盘、窗口调整和相机被抢占。 |
| Xiaomi/Redmi/POCO | Qualcomm/MediaTek | 输出尺寸、AF 重触发/解除、RAW-only 切换和热后稳定性。 |
| vivo/iQOO | 已有可复现场景与不同平台 | 预检否定但实际可配置、RAW 后预览恢复、迟到回调。 |
| OPPO/OnePlus | 逻辑多摄、不同 HAL 能力 | 固定物理输出、逻辑回退、读取缺失字段和生命周期。 |
| Huawei/Honor | 具备 Android 应用运行能力的测试环境 | 公开 Camera2 能力及实际输出；厂商 Camera Engine 不等于本 APK 的能力承诺。原生 HarmonyOS 独立 API 不纳入本 Android APK 适配承诺。 |
| 低内存/慢闪存 | 至少一台中低端设备 | 记录压缩、RAW 内存峰值、低空间失败、索引恢复和热限制。 |

厂商资料对应检查点：

- [Samsung DeX 相机旋转](https://developer.samsung.com/samsung-dex/modify-optional.html)：TextureView 变换与外接显示。
- [小米预览比例](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1641)：支持尺寸、buffer size 与显示比例。
- [小米 3A](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1598)：AF 请求与预览状态。
- [Huawei Camera Engine](https://developer.huawei.com/consumer/en/CameraKit)：独立厂商 SDK 和能力查询边界。
- [AOSP 多摄](https://source.android.com/docs/core/camera/multi-camera)：物理流组合并非普遍保证。
- [Camera ITS](https://source.android.com/docs/compatibility/cts/camera-its-tests)：借鉴曝光、裁切、RAW/YUV 和多摄测试场景，不把应用测试称为通过整机 CTS。

每台设备记录实际型号、SoC、Android/厂商版本、build hash、camera IDs、路由/尺寸/profile、结果来源、失败原因、结果与恢复 P50/P95、循环次数。用户照片/位置不进入默认诊断包。

至少执行：冷启动/后台恢复各 20 次；Normal↔Zone 与菜单返回 20 轮；单点、显式全点、越界点；前后摄；权限撤销；相机被其他应用占用；10 分钟持续使用后的恢复；记录保存中取消/后台/低空间；OTA 或模拟签名变化。循环数是起始门槛，出现偶发故障应增加针对性循环。

## 16. 提交拆分、检查与回退

### 16.1 建议提交批次

| 提交 | 内容 | 完成条件 |
| --- | --- | --- |
| A1 | capture metadata 纯策略 + 适配器 + 必要测试 | 固定/逻辑/直接路线规则明确。 |
| A2 | 所有生产消费者及 workflow probe 接入 | Image 生命周期和现有恢复回归通过；删除重复 fallback。 |
| B1 | 校准签名模型、存储迁移、旧数据状态 | 迁移幂等、历史可读。 |
| B2 | 校准运行上下文与 UI 状态接入 | 正式/预览/暗角域独立，null 和换镜头行为通过。 |
| C1 | 位置 coordinator、快照字段和兼容读取 | 过期/取消/粗略授权/时间关闭测试通过。 |
| C2 | 记录 I/O 串行化与异步 UI | 慢存储和取消恢复通过，闪光/距离快照无回归。 |
| D1 | 饱和比例重拍最小修复 | 正常 ROI 不因少量高光重拍，真实统计失真仍有处理。 |
| E1 | 几何上下文与契约测试 | 保留现有画面稳定性；设备行为变更另有证据。 |
| E2 | 推荐流、stream use case、AF 各自小提交 | 同路由默认配置可回退，已校准状态正确变化。 |
| F1 | RAW 质量只统计、性能预算验证 | 额外开销显著小于另拍 RAW，估计与重复测量相符。 |
| F2 | 自适应采集启用 | 精度/失败率/请求上限达标，共享批量语义不变。 |
| G | 备份、日志、native、无障碍、纯拆分分别提交 | 各自边界验证通过，不用一个大提交打包全部。 |

### 16.2 检查方法

每批运行受影响的 JVM 测试并在集成点运行：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleRelease
git diff --check
git status --short
```

新增 `app/src/androidTest/java/com/lightmeter/rawmeter/` 设备测试时，在 `app/build.gradle.kts` 显式配置 runner/test-only 依赖，验证对当前非 AndroidX 应用配置的影响；不要在未配置 runner 前把 `connectedDebugAndroidTest` 当成有效门禁。关键 Camera2、权限、备份和存储行为必须真机验证，JVM 通过不能替代。

发布 native 变更后检查所有已支持 ABI 的依赖闭包、64 位 ELF LOAD 对齐和 APK zip alignment，再在 16 KB 环境启动并运行 Zone。参考 [Android 16 KB 文档](https://developer.android.com/guide/practices/page-sizes)。

检查 APK/AAB 内容不含 docs、测试截图、设备日志、原始测试帧和开发脚本；保留运行时资源与许可证。源码 README/CHANGELOG 只在对应行为真正落地后更新完成状态，发布版本号在发布提交中推进。

### 16.3 回退规则

- 使用独立 revert 或修复提交，保留历史，不覆盖既有发布 tag。
- 不通过重新启用逻辑元数据混用、全局 EV 修正或无界重试来恢复“表面可用”。
- RAW 质量策略可退回上个已验证策略，但 C10 的“饱和比例不能单独强制重拍”继续成立；不能为回退恢复已确认不合要求的旧判据。
- 输出优化先回退同 route 的默认 use case/尺寸，再由既有恢复策略决定后续动作。
- 几何变更出问题时保留已验证的稳定 preview 域；暂时禁用无法证明映射正确的点测能力，并明确提示。
- 校准新数据不删除，降级版本必须理解其有效性；不得将新算法校准伪装成旧 schema。
- 参数记录迁移保留老样本和失败恢复路径；界面作业取消不能回滚用户已经成功保存的记录。

## 17. 第一批交付验收清单

- [ ] A1/A2：所有正式物理 RAW、DNG、色温、暗角、YUV/ISP 元数据消费者与探测使用一致解析规则。
- [ ] 无效物理结果不会写入校准、记录测量值、自动距离或 Zone EV；Image 在所有结束路径释放。
- [ ] B1/B2：OTA、实际 route、来源和算法变化不会静默沿用不匹配校准，旧数据可见且可验证。
- [ ] 短暂 physical ID 未知不误判换镜头；已确认 A→B 不跨镜头完成校准。
- [ ] C1：陈旧/撤销/迟到位置不进入新记录，粗略权限和关闭时间选项语义正确。
- [ ] C2：编码/文件复制/完整索引写入离开主线程，保存取消及进程重启恢复不丢记录。
- [ ] 闪光 GN 原值、参考 ISO、工具 ISO、距离快照和 RAW 网格完整保留，工具 ISO 不改主转盘。
- [ ] D1：饱和比例不再单独强制 RAW 重拍，实际目标统计失真有有界处理。
- [ ] Normal/Zone/设置/镜头管理往返保留模式与镜头，稳定 TextureView 传输几何不变。
- [ ] 普通测光只更新当前目标；显式全点重测共享 RAW、逐点匹配、越界跳过并计数。
- [ ] 已有提前发布结果与恢复锁正确配合，迟到回调不污染新事务。
- [ ] 至少一台现有稳定设备和一台差异 HAL 设备通过第一批测试；未测厂商/版本如实标明。
- [ ] 文档、测试证据与真实实现对应；本清单只在代码与验证完成后勾选。
