# 光档：近期提交审阅问题修复实施文档

编写日期：2026-09-22。实施基线：`6810ac4`，分支 `codex/camera-controller-components`，版本 `0.4.0` / versionCode `9`。

审阅范围：`518df05..6810ac4`，共 29 个提交、66 个文件。本次编写前再次确认 HEAD 未变化、工作区干净。本文只制定方案，不表示代码已经修复，也不授权自动提交或发布。

## 1. 如何使用本文

本文是 2026-09-21 总体整改方案的增量修复清单，针对上一轮审阅确认的 8 项问题，不替代原有长期设计约束。旧方案中已经实现的组件应继续复用，不能把旧计划的“待实现”机械理解为当前仍不存在。

项目根目录为 `D:\Project\lightmeter a`。下文文件表以项目根目录为基准；除特别说明外：

- Kotlin 主文件位于 `app/src/main/java/com/lightmeter/rawmeter/`。
- 已有 JVM 测试位于 `app/src/test/java/com/lightmeter/rawmeter/`。
- 标注“建议新增”的类型和文件目前不是既有接口，名称可以调整，但职责和验收条件不能省略。
- 行号仅适用于 `6810ac4`，实施时以函数名和实际 diff 为准。

### 1.1 当前验证事实与局限

上一轮在该基线执行了以下验证：

| 检查 | 结果 | 不能据此推断的内容 |
| --- | --- | --- |
| `testDebugUnitTest --rerun-tasks` | 332 项通过，72 个测试套件 | 不代表跨会话校准、异步保存、Camera2 回调和 TalkBack 已正确集成 |
| `lintDebug` | 0 错误、65 项警告 | 不代表不存在业务逻辑错误或安全风险 |
| `assembleRelease` | 成功 | 不代表 APK 已签名发布或已经过厂商真机验证 |
| Git 工作区 | 干净 | 后续其他任务仍可能产生新改动，实施前必须重查 |

这 8 项是代码路径可确认的逻辑问题；未把特定品牌、机型上的表现宣称为已经实测。文中的新增测试、设备矩阵与性能标准都是待执行的验收要求。

### 1.2 本轮禁止改变的边界

1. 保留厂商 HAL 会话隔离、输出组合限制、释放顺序、故障恢复和自动降级。不通过合并 RAW/YUV/预览会话修复校准签名问题。
2. 保留 Normal、Zone、设置、摄像头管理之间的模式和镜头选择；正常取消、页面切换和业务校准失败不得触发主摄降级。
3. 不改变稳定的 TextureView/SurfaceTexture 传输几何；不混用逻辑预览与物理 RAW 坐标。
4. RAW 同曝光追加与改变曝光重拍保持两条不同决策路径；不让高光饱和比例单独触发强制重拍。
5. Zone 全部重新测光继续使用共享 RAW/共享 burst、逐点匹配；越界、匹配失败或身份过期的点保留旧值并计入未更新。
6. 不增加全局 EV 补偿。用户测光校准、曝光预览辅助校准、暗角校准保持分离。
7. 参数记录继续保留闪光、距离、GN 数值及其参考 ISO；不得改变临时闪光 ISO 与主转盘隔离的行为。
8. 不更换相机框架、引入 ARCore、移除 OpenCV 功能或减少 ABI；这些不是本轮修复的前置条件。
9. 文档、测试记录放在 `docs/` 或测试源集中，不放入生产 `assets/`、`res/`。不覆盖 Git 历史，不提前修改 Release。

## 2. 优先级、实施顺序与提交边界

| 编号 | 优先级 | 问题 | 核心修改点 | 推荐批次 |
| --- | --- | --- | --- | --- |
| F01 | P1 | 最终保存使用 ISP 会话判断 RAW/YUV 的旧修正，可能将正确校准覆盖为零 | 测量时校准快照、保存时不重读活动会话 | A |
| F02 | P1 | 曲线已应用的修正与更新公式使用的单值修正不同，重复校准发生漂移 | 未应用用户修正的基准 EV、同帧曲线锚点 | A |
| F04 | P2 | 无效 RAW 统计导致不补帧也不结束，等待超时 | 有效帧与提交预算分离、终结条件 | B |
| F03 | P2 | 将线性亮度差误当 EV 差 | EV 域噪声计算和无效输入策略 | C |
| F05 | P2 | 批量测量读取空的单点统计，追加帧永不触发 | 按目标评估、共享追加帧、目标完成条件 | C |
| F06 | P2 | 旧记录保存回调使新捕获令牌失效 | 草稿令牌、保存状态、生命周期边界 | D |
| F07 | P2 | 定位 60 秒过期后不刷新，持续拍摄不再带位置 | 前台按需刷新、请求去重、完整终态 | E |
| F08 | P2 | 虚拟无障碍节点只支持点击，缺少焦点协议 | 焦点状态、动作、事件及跨页清理 | F |

先修 F01/F02。F04 排在 F03/F05 前，是为了先保证所有 RAW 分支都能结束，再启用正确的追加判断。F06 不依赖 RAW 数学修复，可在前述提交完成后单独修改。这里的“可独立”描述代码依赖，不要求多个任务同时编辑文件。

建议使用以下提交主题；本次不执行提交：

1. `fix(calibration): bind updates to captured calibration samples`，覆盖 F01/F02 及对应测试。
2. `fix(raw): bound invalid-frame recovery and terminal states`，覆盖 F04。
3. `fix(raw): evaluate adaptive burst quality in EV per target`，覆盖 F03/F05。
4. `fix(records): bind save callbacks to draft lifecycle`，覆盖 F06。
5. `fix(location): refresh foreground record fixes safely`，覆盖 F07。
6. `fix(a11y): implement virtual-node focus lifecycle`，覆盖 F08。

每个提交应包含生产修改、失败复现测试和对应验收记录。不把半接入的数据模型单独作为可发布提交。

## 3. F01：保存校准时绑定测量上下文，而非最后一个会话

### 3.1 已确认的触发链

1. `MeteringCalibrationPlan.create()` 生成 RAW → YUV → ISP 的顺序。
2. `CalibrationSessionProfilePolicy.profileFor()` 为每个来源选择隔离会话；ISP 使用 `PREVIEW_ONLY`。
3. RAW/YUV 测量时已经应用自己的旧修正。
4. `MainActivity.finishCalibration()` 在 ISP 阶段结束后调用 `updateUserCalibration()`。
5. `CameraCalibrationStore.updatedStream()` 第 299—302 行调用 `userCorrection(cameraId, source)`。
6. 该函数使用此刻的 `activeContext`；没有 RAW/YUV 输出时，`CalibrationCaptureContext.signature()` 构造的输出为 `0×0 / format=0`，旧签名被判断为不匹配，返回 0。

数值复现：RAW 的未应用用户修正 EV 为 9，旧修正 +1，实际读数与参考值均为 10。正确的新修正仍应是 +1；当前保存公式变成 `0 + 10 - 10 = 0`。

### 3.2 修改文件、位置与方法

| 文件 | 修改位置 | 具体修改 |
| --- | --- | --- |
| `MeteringAnalysis.kt` | `MeteringFrameStat`；RAW/YUV/ISP 三类分析函数 | 记录未应用用户测光修正的基准 EV、实际应用的用户修正、来源及采集签名；在同一次计算中获取这些值 |
| `MeterModels.kt` | `MeterReading` | 携带可空的校准采样快照；普通显示继续使用现有 `sceneEv100` |
| `MeteringFusion.kt` | `fuse()` | 保留正常读数融合行为，同时正确构建校准快照；不得用不相关的中位数相减代替逐帧基准 |
| `RawLightMeter.kt`、`CompatibleLightMeter.kt` | 分析入口、融合调用和结果交付 | 透传当前测量的采样上下文；成功/重试/降级路径不能丢失快照或借用下一次测量的数据 |
| `CameraCalibrationStore.kt` | `userCorrection()`、`responseCorrection()`、`updatedStream()`、`updateUserCorrections()` | 增加基于显式采集签名读取修正的入口；保存使用快照中的基准 EV，不再依赖最终活动上下文 |
| `CameraController.kt` | 测量开始/结果发布、`currentCalibrationSignature()`、`updateUserCalibration()` | 在产生测量的相机会话侧冻结身份和输出信息；向上透传，避免 UI 收到结果后再反查 |
| `MeteringCalibrationPlan.kt` | `MeteringCalibrationRun.accept()` | 保存每个来源完整快照，拒绝缺失或身份不一致的数据 |
| `MeteringCalibrationCoordinator.kt` | `onReading()`、`MeteringCalibrationCompletion` | 聚合快照，并保留原有镜头变化和部分成功处理 |
| `MainActivity.kt` | `handleCalibrationReading()`、`finishCalibration()` | 使用 reading 自带快照，不将 `currentCalibrationSignature(reading.source)` 作为保存依据 |
| `CalibrationSignature.kt`、`CameraOpenConfigurationResolver.kt` | 来源输出查找与签名构造 | 采样路径对不存在的输出返回“不可采样”，不能把 `0×0` 占位签名当作有效采集证据 |

签名辅助方法可能被设置页和暗角校准使用。先新增显式采样入口，再逐个迁移调用者；不要直接全局改变返回类型、误伤现有显示和历史浏览。

### 3.3 推荐数据契约

建议新增 `CalibrationMeasurementSample.kt`，定义“一个来源在测量时实际使用的输入”，而不是继续增加数个容易错配的 Map。至少包含：

- 来源 `source`、存储镜头标识 `cameraId`、`CalibrationSignature`。
- 测量令牌/会话 generation，供运行期检查；不把临时 generation 纳入持久化校准适用范围。
- `ev100BeforeUserCalibration`：已经包含现有物理换算和应有基线修正，但尚未应用用户测光偏移/响应曲线的 EV。
- `appliedUserCorrectionEv`：该帧实际应用的用户偏移或插值修正，供诊断和一致性校验。
- 同一采样的 `inputLuma`；RAW 可携带但不能据此变成处理流曲线。
- 有效性/拒绝原因。缺失数据用明确的缺失状态表示，不默认填 `0.0`。

`MeterReading` 可以保持一个可空快照以兼容普通展示及现有构造调用，但校准保存入口必须要求有效快照。普通测光结果不应因为无法生成校准快照而无条件丢弃；二者的失败语义要分开。

### 3.4 保存算法

推荐将公式直接写成：

```text
新用户修正 = clamp(参考 EV100 - 测量时未应用用户校准的 EV100, -8, +8)
```

RAW 使用未应用用户修正的逐帧 EV 进行同样的稳健融合；正常 UI 的校准后 EV 融合维持现状。不要把“校准后 EV 的中位数减去用户修正的中位数”当作严格等价变换。

必须保留以下区分：

- `CameraCalibrationStore.totalCorrection()` 中现有基线与用户修正不是同一个概念。只去掉用户测光修正，不能顺手删掉基础物理换算/现有基线。
- 暗角修正对亮度的作用、曝光预览滑块作用不迁移到用户 EV 偏移中，也不重复叠加。
- 采集签名用于证明旧修正能否应用；最终保存的签名来自该来源本次真实采集，不来自当前会话。
- 确认真正换镜头时仍按现有流程拒绝保存；会话重开的暂时空物理 ID 仍遵守既有兼容规则。
- 一个来源失败时保留该来源旧记录，不能写 0、空签名或别的来源的数据。

### 3.5 测试与验收

修改已有 `MeteringCalibrationCoordinatorTest.kt`、`MeteringCalibrationPlanTest.kt`、`CalibrationSignatureTest.kt`；建议新增 `CalibrationMeasurementSampleTest.kt`、`CalibrationUpdatePolicyTest.kt`。

至少覆盖：

1. RAW 原修正 +1，基准 9、参考 10；最终切换到 ISP 会话后仍保存 +1。
2. RAW/YUV/ISP 分别有不同偏移，连续执行两次完整校准，不互相覆盖，不振荡。
3. 保存前活动会话被关闭、重开或更换输出，不能改变已经形成的合格采样数值；真实身份冲突仍拒绝提交。
4. 无旧校准、旧校准签名失效、部分来源失败、缺失快照和非有限数值分别得到明确处理。
5. 普通 Normal/Zone 读数与修复前的合法输入结果一致，不因新增模型额外叠加修正。

本项与 F02 共用同一数据链路；建议同批交付，避免单值修复通过但曲线更新继续出错。

## 4. F02：响应曲线校准必须使用同一亮度实际应用的修正

### 4.1 触发条件与数值例子

`MeteringAnalysis` 的 YUV/ISP 路径使用 `responseCorrection(inputLuma)`，而 `updatedStream()` 使用 `userCorrection()` 单值。

假设曲线有 `(0.2, 0 EV)`、`(0.6, +1 EV)` 两个锚点，最近保存的单值为 +1 EV。当前亮度 0.4 对应插值 +0.5 EV。未修正读数为 10，参考值为 10.5，显示已经正确：

```text
当前错误更新：1 + 10.5 - 10.5 = 1
正确更新：10.5 - 10 = 0.5
```

### 4.2 修改文件与步骤

| 文件 | 具体位置 | 修改方法 |
| --- | --- | --- |
| `MeteringAnalysis.kt` | `analyzePreview()`、`analyzeYuvPreview()` 内 scene EV 计算处；以实际函数名核对 | 将未应用用户曲线的 EV、当前亮度、实际插值修正作为同一采样传递 |
| `CameraCalibrationStore.kt` | `updatedStream()`、`responseForSource()`，约 288—325 行 | 按 F01 的采样基准计算锚点；单值只作兼容回退，不能作为曲线更新的撤销量 |
| `ProcessedResponseCalibration.kt` | `withAnchor()`、序列化/解析 | 保留现有锚点数量与合并规则；不把整个模型推倒重写来修公式 |
| `MeteringFusion.kt`、`MeteringCalibrationPlan.kt` | 采样选择与保存 | 确保锚点亮度和基准 EV 来自同一采样元组 |
| `ProcessedResponseCalibrationTest.kt` | 新增集成数学用例 | 除插值测试外，验证“应用→再次校准→再次应用”的闭环 |

### 4.3 多帧处理与曲线持久化

处理流校准不能把一个亮度的插值修正拿去抵消另一个亮度的 EV。推荐先保留每帧采样元组，在当前校准阶段内选择一个有效的代表采样作为新锚点，例如按未校准 EV 排序取确定的中间样本。即使偶数帧，也选择真实样本元组，不拼接两个独立中位数。正常测光显示仍可使用现有融合值；校准 UI/日志需要说明使用了代表采样。

如果改为拟合整组采样，须另补拟合和异常值测试，不应在本轮顺手引入未经验证的拟合算法。

实施时一并补以下防回归条件，它们是修复边界检查，不新增为已经完成的审阅结论：

1. 只有旧响应曲线签名与当前来源采样兼容时才合并锚点；路由/输出/算法变化时，不能把旧锚点套上新签名重新激活。
2. 历史记录保留。需要停用疑似受影响曲线时，标记待重新校准，不直接删除历史。
3. 不默认提高全局校准版本使所有用户的 RAW 校准一起失效。若确需版本迁移，优先使用处理流响应模型的独立版本，并测试升级读取及回退行为。
4. 已被错误覆盖的历史校准无法仅凭当前数值可靠反推。不要猜测恢复 +1 EV；保留历史选择和明确的重新校准提示。
5. 不调整曝光预览辅助校准、RAW 基线或设备全局 EV 来掩盖曲线问题。

### 4.4 验收用例

- 上述 0.4 亮度例子必须产生 +0.5 EV 锚点。
- 同一场景连续校准至少 3 次，正确锚点不漂移。
- 分别在低、中、高亮度校准，再回到旧锚点，不被最后一个单值覆盖。
- 序列化后重新加载，插值和修正一致；旧单值记录仍可读取。
- 非兼容签名不继承旧曲线；某来源失败不清空另一个来源。
- RAW 路径不创建响应曲线，曝光预览滑块不改变上述结果。

## 5. F04：保证 RAW 无效帧分支能补拍或结束

### 5.1 根因

`processPair()` 第 465—475 行依赖 `stats.size >= expectedFrames` 或 `completedFrames >= maxFrames` 结束，而 `fillPipeline()` 第 626—640 行只允许提交到 `expectedFrames`。

例如基础帧数 1、最大帧数 2，唯一一帧返回空统计：完成数为 1、有效数为 0，不能结束；提交数已经达到目标 1，也不会再提交，最终空等 8 秒。

### 5.2 修改文件与计数契约

主要修改 `RawLightMeter.kt` 的 `MeasurementAccumulator`、`processPair()`、`processBatchPair()`、`fillPipeline()`、`submitNextFrame()`、超时与失败终结路径。建议新增纯 Kotlin `RawBurstProgressPolicy.kt`，仅作决策，不接管 CameraDevice/Session/ImageReader。

将含混的 `expectedFrames` 拆分或重命名，使以下语义明确：

| 状态 | 含义与限制 |
| --- | --- |
| `baseValidFrames` | 初始期望有效样本数，继续沿用当前 ISO 策略 1/2/3 |
| `submittedFrames` | 已实际发送的捕获请求数 |
| `completedFrames` | 已完成处理的捕获数；不等于有效样本数 |
| `validFrames` | 单目标可融合的有效样本数 |
| `maxCaptureAttempts` | 当前曝光阶段的实际请求上限，初始继续使用基础帧数 + 1 |
| `inFlight` | 尚未结束的请求数，继续维持单请求窗口 |
| `terminal` | 成功/失败/取消之一，一旦终结不得再次补拍或回调 |

新增自适应帧和无效帧补拍共用同一个额外请求预算。不能把它们各自加 1 而无意扩大捕获成本。改变曝光重试仍按现有独立阶段处理，另保留整次操作的超时、恢复和最大阶段约束。

### 5.3 推荐决策顺序

```text
请求/图像处理结束
  → 先校验操作令牌、会话身份与是否已终结
  → 关闭当前 Image，更新完成计数
  → 统计失真：进入既有有界曝光重试，不合并不同曝光样本
  → 有效统计：加入当前目标样本；无效统计：记录拒绝原因
  → 有在途请求：等待现有请求
  → 有效帧不足且还有请求预算：提交一张补拍
  → 有效帧足够且质量策略要求追加、仍有预算：提交一张共享/单点追加
  → 否则立即成功或明确失败，清理超时和配对器
```

第一版采用保守结果策略：单点在预算耗尽但有效帧仍少于基础要求时返回明确错误；达到基础要求则允许结束，追加帧只是改善机会，不是新的最低有效帧门槛。如果未来允许少帧低可信结果，需单独定义质量状态与 UI 行为，不在本次偷偷放宽。

捕获失败、图像丢失、时间戳无法配对不完全等价于“统计返回 null”。保持现有 HAL 故障与配对超时处理，确保每个请求只记账一次；不能为了凑完成数，把迟到回调和失败回调重复相加。

### 5.4 测试与验收

保留 `RawMeteringPolicyTest.kt`、`RawExposureRetryPolicyTest.kt`；建议新增 `RawBurstProgressPolicyTest.kt` 和可注入回调/假捕获提交器的流程测试。

- 基础 1 帧：无效→有效，2 次请求后成功；无效→无效，立即失败而非等 8 秒。
- 基础 2 帧：有效→无效→有效，最多 3 次请求成功。
- 基础 3 帧：有 2 次无效，预算耗尽后明确失败，不死等、不无限补拍。
- 有效帧已够、追加帧无效，不把先前有效结果无条件丢弃。
- 捕获失败/取消/会话关闭后迟到结果到达，Image 关闭且只产生一个终态。
- 超时仍能处理永不到达的结果；不能通过延长 8 秒超时掩盖内部停滞。
- 重拍和自适应判断只读取已有 ROI 统计，不新增全图扫描。

## 6. F03：在 EV 域计算同曝光帧波动

### 6.1 修改位置与公式

修改 `RawMeteringQuality.kt` 第 57—60 行的 `frameNoiseStops()`，以及 `RawLightMeter.kt` 中传入该函数的样本准备。现值 `max(luma) - min(luma)` 的单位不是 EV。

对至少两个同曝光、同目标、同身份的正有限亮度样本：

```text
noiseStops = log2(maxLuma) - log2(minLuma)
```

分开取对数可避免极端输入下直接比值溢出。保留 `RANDOM_NOISE_EV_THRESHOLD = 0.08` 作为当前工程阈值，不在修单位的提交内顺手调参；阈值准确性仍需设备数据验证。

### 6.2 输入与决策边界

1. 0、负数、NaN、无穷值不是正常亮度样本，不喂入对数。其采样无效性由 F04 处理；不足两帧返回“无法估计”，不伪造 0 EV。
2. 基础帧数为 1 时，不能凭一帧估算帧间波动。维持现状不因未知自动追加，也不宣称已经证明稳定；增加诊断上的 unknown 标志即可。
3. 不混合曝光重试前后的样本。若实际 ISO/曝光时间变化，先判定不是同曝光序列，不把曝光变化误当随机噪声。
4. 即使同曝光，场景移动和光源闪烁也可能产生波动。因此字段/日志应称“帧间 EV 波动”，不能宣称是已经分离出的纯随机噪声。
5. 当前最大额外帧为 1，不要求波动一定降低才允许退出；稳定性与上限是不同条件。
6. 不用全画面饱和比例代替该指标，不改变 `RawExposureRetryPolicy` 的统计失真判据。

### 6.3 修改测试

修改 `RawMeteringQualityTest.kt`：现有 `0.0, 0.3` 的“正常噪声”例子应替换成正有限样本，另为零亮度创建无效输入测试。

| 输入 | 期望 |
| --- | --- |
| `0.01, 0.02` | 1 EV；有预算时建议追加 |
| `0.10, 0.20` | 同样 1 EV，证明亮度尺度不影响 EV 波动 |
| `0.20, 0.201` | 低于 0.08 EV，不追加 |
| `0.2, 0.2` | 0 EV |
| 单个合法样本 | 无法估计，不自动追加 |
| 0/负数/NaN/Infinity 与一个合法值 | 不产生有效噪声估计，由进度策略处理缺帧 |
| 有噪声但已耗尽预算 | 立即结束，不再增加请求 |

## 7. F05：Zone 按目标评价质量，但仍共享捕获

### 7.1 修改位置

修改 `RawLightMeter.kt`：`BatchTargetAccumulator`、`processBatchPair()` 第 550—555 行、`shouldAppendAdaptiveFrame()`、`finishWithBatchReadings()` 第 693—698 行。现有批量统计只进入 `target.stats`，不会进入 `active.stats`，因此不能复用读取 `active.stats` 的单点判断。

### 7.2 具体实现方法

1. 为每个有效目标从自己的同曝光 `stats` 计算 F03 的波动，不能把不同目标的明暗差当作帧间噪声。
2. 当前仍有效、身份一致、匹配合格的目标中，只要一个满足追加条件且全局预算未耗尽，就提交一张共享 RAW；所有仍有效的目标从这同一张图提取统计。
3. 全局帧计数走 F04 的进度策略。追加 1 次是整个 batch 加 1，不是每个目标加 1。
4. 每个点仍执行现有预览到 RAW 的匹配。越界、匹配失败和身份失效的目标退出可更新集合，不通过坐标钳制写回错误的边缘值。
5. `finishWithBatchReadings()` 不再用增长后的请求目标数作为所有点的最低有效样本数。使用固定的基础有效帧要求及每个点的有效性状态；额外帧失败不能抹掉已达到基础要求的点。
6. 如当前 API 仅返回成功点列表，继续由请求点数减成功点数统计未更新；内部可记录原因，但避免无必要扩大 UI/数据库协议。
7. 不改变普通单点测光只更新该点的规则。

### 7.3 回归测试

建议新增 `RawBatchQualityPolicyTest.kt`，并扩展现有 `ZoneRawTransactionCoordinatorTest.kt`、`ZoneRawSessionStateTest.kt` 的结果交付断言。

- A 点稳定、B 点波动，整个 batch 仅增加 1 张 RAW，而不是 2 张。
- A/B 各自稳定但亮度差很大，不追加。
- 一个点在追加帧无效、此前有效帧已够，该点仍可更新；从未达到基础要求的点保留旧值。
- 有越界点、匹配失败点时，只更新合格点，未更新数准确。
- 所有点无效，结束而不是为了追求统计继续拍摄。
- 点数量增加不增加 RAW 请求上限；所有输出点属于同一共享 burst。
- 取消、切换页面、恢复常驻预览期间出现旧结果，不重写新操作中的点。

## 8. F06：异步记录保存必须绑定草稿和生命周期

### 8.1 已确认的竞态

`MeterLayout.onSaveRequested()` 的成功回调直接调用 `parameterCaptureGuard.complete()`，没有验证属于哪一份草稿。保存过程仍允许取消编辑；取消后可以产生新的捕获 generation。旧保存回调会完成新的 guard，新 JPEG 回调随后判断自身过期并丢弃结果。

单线程 I/O 只能保证后台任务执行顺序，不能保证用户操作与主线程回调不会交错。`parameterSaveInFlight` 与 `ParameterCaptureGuard.saving` 两份状态也可能不同步；目前 guard 的 `beginSave()` 没有真正成为保存入口的唯一门禁。

### 8.2 推荐产品行为

本轮选择较小且确定的行为变化：一份草稿进入保存后，冻结其编辑、取消和重复保存，显示“正在保存”。返回键暂不关闭正在保存的编辑器；保存成功后关闭，失败后恢复编辑并保留草稿。

这不阻止系统暂停/销毁 Activity。已提交的文件事务可继续完成，但销毁后的 UI 不接收更新。生命周期失效不能被解释成要求删除已成功保存的记录。

若以后需要“保存中返回并继续拍摄”，应单独设计后台保存列表/任务所有权；不能只去掉按钮禁用而仍共用一个无标识的 busy 标志。

### 8.3 文件与修改方法

| 文件 | 修改位置 | 具体方法 |
| --- | --- | --- |
| `ParameterCaptureGuard.kt` | `beginSave()`、`finishSave()`、`complete()`、`cancel()` | 保存和完成都要求 token/draft ID；旧 token 不得改变当前状态，返回是否成功处理 |
| `MeterLayout.kt` | 826—862 行编辑器 listener；`startParameterCapture()`、`completeParameterCapture()`、`closeParameterEditorFromBack()` | 使用唯一 guard 管理保存；在所有异步回调开始处校验草稿及生命周期；移除独立且无归属的 `parameterSaveInFlight` 或让它严格派生 |
| `ParameterRecordEditorView.kt` | 点击入口、绘制状态、无障碍节点 | 增加 `setSaving()` 或等价状态；触摸、TalkBack 点击、返回均遵守同一保存门禁 |
| `ParameterRecordIoDispatcher.kt` | `execute()`、`submit()`、`shutdown()` | 提交拒绝需要可观察结果，不能静默丢任务并让 UI 永久 busy；区分持久化完成与是否投递 UI |
| `MainActivity.kt` | 记录捕获回调及生命周期桥接 | 将草稿令牌一直传到 RAW 成功/失败回调；不要只在 JPEG 阶段校验 |
| `ParameterRecordRepository.kt` | `save()`、`discard()`、事务恢复 | 保持现有安全路径校验、事务和回滚；只回收属于已取消草稿的 pending 文件，不删最终记录 |

推荐令牌为不可变对象，例如 `CaptureToken(generation, draftId)`。如果继续用 Int generation，调用时仍需验证 draft ID，避免只验证一个容易误用的全局数值。

### 8.4 状态转换

| 当前状态 | 事件 | 结果 |
| --- | --- | --- |
| 无草稿 | 开始捕获 | 创建 token，进入捕获中 |
| 捕获中 | 同 token 的 JPEG/RAW 完成 | 打开编辑器 |
| 捕获中/编辑中 | 用户取消 | token 失效，只清理所属 pending 文件 |
| 编辑中 | 保存 | 原子预留保存槽，冻结不可变 draft，进入保存中 |
| 保存中 | 重复保存、修改、取消、返回 | 拒绝/提示正在保存，不另建事务 |
| 保存中 | 同 token 的保存成功 | 完成当前草稿，关闭编辑器，复位滑块 |
| 保存中 | 同 token 的保存失败 | 回到编辑中，保留草稿，允许重试 |
| 任意状态 | 旧 token 的迟到回调 | 不改变当前草稿、busy、滑块或编辑器；只做有所有权证明的资源释放 |
| 任意状态 | View/Activity 生命周期结束 | UI token 失效，停止投递 UI；已开始的持久化事务按自身结果完成/回滚 |

异步完成时先判断 token 再改 busy，不能先将全局 busy 清零后才判断过期。后台 lambda 不读取实时 View/State；保存输入继续使用不可变快照。

`shutdown()` 不等于取消已经提交的保存。不要在 View detach 时一律丢弃正在保存的 draft 文件；否则可能与后台复制/移动竞争。需要独立区分“UI 已失效”与“文件事务已终结”。

### 8.5 回归测试

扩展 `ParameterCaptureGuardTest.kt`；建议新增 `ParameterSaveCoordinatorTest.kt`，使用可手动控制的执行器和 UI 回调队列，不用真实 sleep 制造时序。

1. 保存 A、尝试取消和新捕获，按推荐交互应被明确门禁阻止。
2. 直接模拟旧 A 回调到达新 B（防御性测试），B 的 token、busy、编辑状态均不变。
3. 连点保存只提交 1 次事务；触摸点击和无障碍点击混合也不重复。
4. 磁盘失败后能重新编辑/重试，失败不丢预览或 DNG，不显示假成功。
5. Activity 销毁后完成保存，不重开旧编辑器、不访问已销毁界面；已保存记录可在历史中恢复。
6. 执行器拒绝任务时 UI 可退出 busy；没有静默丢失。
7. 丢弃 A 不能删除 B 的 pending 文件，也不能删除 A 已经提交的最终文件。
8. RAW 迟到回调、JPEG 失败、取消与恢复组合下，图像/文件所有权明确，所有出口复位状态。

保留 `ParameterRecordTransactionTest.kt`、`ParameterRecordPathPolicyTest.kt`、闪光/距离记录相关测试。不得用同步主线程保存作为竞态修复。

## 9. F07：前台记录期间按需刷新定位

### 9.1 根因与现状

`ParameterLocationPolicy.FRESH_MAX_AGE_MS` 为 60 秒，`accept()` 成功后关闭当前请求。新请求仅由 `MainActivity.enableParameterGps()` 触发，主要发生在开启 GPS 和恢复前台。

持续停留应用超过一分钟后，`captureParameterRecord()` 调用 `snapshot()` 得到空值，后续只提示无定位，却不会请求新的 fix。另一个需要随修复封闭的出口是请求超时/null：不能让 UI 长期停在“正在定位”。

### 9.2 文件与接口调整

| 文件 | 修改位置 | 修改方法 |
| --- | --- | --- |
| `ParameterLocationCoordinator.kt` | `requestFix()`、`snapshot()`、`accept()`、`scheduleTimeout()`、`cancel()` | 增加前台可请求状态、单请求去重和刷新入口；统一成功、null、超时、权限/Provider 失败的终态 |
| `ParameterLocationPolicy.kt` | 刷新判断 | 提取纯函数，输入缓存年龄、权限、前台状态、用户偏好、是否有在途请求；保留 60 秒有效期和 8 秒请求超时 |
| `MainActivity.kt` | `onResume()`、`onPause()`、`enableParameterGps()`、`captureParameterRecord()` | 恢复前台及捕获前按需预取；无有效位置时本条照常保存并触发后续刷新，不阻塞拍摄 |
| `MeterLayout.kt` | 捕获开始、GPS 开关事件桥接 | 在 JPEG 异步编码之前取得捕获位置快照；用户关闭 GPS 时通知协调器取消 |
| `ParameterRecordToolView.kt` | `setGpsEnabled()`、GPS 点击和状态显示 | 统一开/关事件到 Activity；把用户意图与暂时不可用状态分开 |
| `ParameterLocationPolicyTest.kt` | 年龄和资格测试 | 覆盖过期、限频、前后台、权限与时钟边界 |

现有 Listener 主要暴露“请求开启”，关闭在 View 内直接改偏好。需要扩展为对称的开关变化事件或增加关闭回调，避免协调器不知道用户已经关闭定位。

### 9.3 刷新策略：先使用事件驱动，不新增后台跟踪

建议第一版提供 `ensureFreshFix(reason)` 或等价接口，规则如下：

1. 只有 Activity 在前台、用户记录 GPS 偏好开启、拥有可用权限时才允许请求。
2. 进入记录流程、恢复前台和每次捕获开始时检查缓存。无缓存、已过期时请求；接近过期可提前刷新。
3. 预刷新阈值可先采用 45 秒，失败重试最小间隔可先采用 10 秒。这是待验证的工程参数，不是 Android 平台保证；独立常量并测试边界。
4. 同时最多一个在途请求。连拍不会反复取消并重启已有请求，避免每次重新计算的 8 秒超时永远到不了。
5. 不新建后台服务、后台定位权限、常驻定位监听或无限定时循环。用户持续拍摄时，由捕获事件推动后续刷新即可。
6. 缓存过期时本条记录可以不带位置，但必须开始一次有界刷新，使后续记录有机会带上新 fix。不等待 GPS 完成才允许拍摄。
7. 迟到的新 fix 只用于未来记录，不补写已经捕获的旧草稿，避免把用户移动后的坐标写回之前的照片。

### 9.4 捕获时刻和生命周期

位置快照应在 `startParameterCapture()` 的捕获意图形成时，通过 Activity/协调器桥接取得，并随草稿快照传递；不能等 JPEG 编码完成再重查实时位置。当前裁切/缩放已有冻结逻辑，应保留；捕获时间和镜头身份目前仍在较晚阶段读取，接入新快照时应一并固定其归属。若 JPEG 与后续 RAW 的镜头身份不同，不能仅改标签伪装为同次同镜头记录，应明确失败或保留不带 RAW 的草稿并提示。

对读取新鲜度使用 `Location.elapsedRealtimeNanos` 与 `SystemClock.elapsedRealtimeNanos()`，不以墙钟时间差判断；该时钟适合单次开机内比较，不跨设备/重启持久化复用。[Android Location 文档](https://developer.android.com/reference/android/location/Location#getElapsedRealtimeNanos())

还需明确：

- `onPause()`、用户关闭 GPS、权限丢失时取消请求并令旧回调失效；关闭后不能再显示新位置。
- 每次请求和快照都重新检查当前权限，不仅信任缓存内的旧权限等级；权限降低时不继续输出旧精确 fix。
- 系统定位暂时关闭只显示不可用，不擅自抹掉用户长期记录偏好。当前 `enableParameterGps()` 的系统关闭分支应一起调整。
- 无权限、Provider 不存在/禁用、`SecurityException`、系统返回 null、8 秒超时均进入可观察终态。
- 有仍新鲜的缓存时刷新失败，可以继续使用该缓存至原始失效时刻；不能重置其采集时间来延长寿命。
- 标准 `LocationManager` 返回值有有效单调时间；遇到异常构造/测试输入时，拒绝未知时间，而不是替换为当前时间伪装为新鲜。
- 保留粗略位置支持。不能把记录开关开启等同于必须精确定位，也不能未经许可增加 Google Play Services 依赖。

API 30+ 继续使用有取消信号的 `getCurrentLocation`；API 28—29 保留受控的旧接口分支和清理。系统可能返回 null，且单次请求不等于持续更新。[Android LocationManager 文档](https://developer.android.com/reference/android/location/LocationManager)

### 9.5 测试与验收

建议新增 `ParameterLocationRefreshPolicyTest.kt`；协调器通过时钟、Provider 和调度器适配层测试，不使用真实 GPS 等待作为 JVM 测试条件。

- t=0 获得 fix；t=61 秒捕获：不附旧坐标，启动一次刷新；新 fix 到达后下一次捕获可附坐标。
- t=46 秒仍有有效缓存：本条可用，同时预刷新，不反向改写旧记录。
- 快速连续拍摄只保留一个在途请求；请求失败后限频，不无限重试。
- 超时/null/禁用 Provider 后，UI 不永久停在 REQUESTING，记录偏好不被自动关闭。
- 前后台切换、精确→粗略、拒绝权限、用户关闭 GPS 时，旧回调不能激活位置。
- GPS 无信号、有网络位置、无网络 Provider 三种情况均正常结束；不要求无网络设备必然返回位置。
- 修改系统时间不影响年龄判断；重启前的单调时间缓存不复用。
- 固定捕获后移动设备再收到位置，旧记录不被修改。

## 10. F08：补全虚拟无障碍节点焦点协议

### 10.1 修改范围

核心文件为 `CanvasAccessibilityHelper.kt`，重点是 `provider.performAction()` 第 121—134 行、节点构建、`update()` 和事件分发。

同时检查五个既有接入页面：`InstrumentView.kt`、`SettingsView.kt`、`CameraManagementView.kt`、`ParameterRecordToolView.kt`、`ParameterRecordEditorView.kt`。本轮先保证这些已经宣称支持的虚拟节点可用，不强制扩展到所有尚未接入的 Canvas 页面。

### 10.2 具体实现

1. 分别维护 `accessibilityFocusedId`、`inputFocusedId`、`hoveredId`；悬停不是焦点，键盘焦点也不等于 TalkBack 焦点。
2. 处理 `ACTION_ACCESSIBILITY_FOCUS` 和 `ACTION_CLEAR_ACCESSIBILITY_FOCUS`，验证节点存在、可见和可用；切换时清理旧焦点并发送对应事件。
3. `createAccessibilityNodeInfo()` 设置真实 `isAccessibilityFocused`，根据状态提供获取或清除焦点动作，不一直只提供 CLICK。
4. 实现 `findFocus(FOCUS_ACCESSIBILITY)`；若提供键盘导航，同步实现输入焦点查询和 FOCUS/CLEAR_FOCUS 动作。
5. 成功点击后发送恰当的点击事件；节点 enabled/clickable 状态与业务门禁一致，尤其是 F06 的保存中状态。
6. `update()` 中对节点删除、隐藏、禁用进行焦点清理；仅在内容/结构真正变化时通知树变化，避免每次绘制发事件造成 TalkBack 循环播报。
7. 页面隐藏或 detach 时清理悬停和焦点。新页面不能继承另一个页面恰好相同的虚拟 ID。
8. 屏幕坐标应考虑页面平移动画与可见裁切；节点不能无条件 `isVisibleToUser = true`。布局前空矩形不暴露为可点击目标。
9. 保持稳定 ID，不因列表排序临时重编号；同一页面不能有重复 ID。
10. 不重新实现业务动作：无障碍激活仍调用与触摸相同的处理入口，并遵守正在保存/相机切换等禁用条件。

`AccessibilityNodeProvider` 将虚拟节点动作交给应用实现，并通过 `findFocus()` 查询对应焦点。仅把 `isFocusable` 设为 true 并不能替代动作和状态管理。[Android AccessibilityNodeProvider 文档](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeProvider)

### 10.3 保持依赖与输入方式边界

项目当前主应用未依赖 AndroidX。优先在现有平台 helper 上补协议；不要为了此项修改同时替换整个 UI 或引入一整套界面框架。

如果继续保留 helper 注释中“键盘用户可操作”的承诺，则宿主还需把 Tab/方向键、Enter/Space 等键事件交给虚拟输入焦点导航。TalkBack 焦点修复本身不自动带来键盘支持。建议抽出 `VirtualNodeFocusState.kt`（新增、纯 Kotlin）进行状态测试，再用页面适配层处理 Android 事件。

若分两步实现，必须明确标注键盘导航仍待完成，不能在第一步验收中将其写成已支持。

### 10.4 测试与验收

建议新增 `VirtualNodeFocusStateTest.kt`，覆盖单焦点、清除、失效 ID、节点消失、禁用节点、页面退出和重复动作。

此外需要 Android 运行环境测试。当前工程只有 JUnit 依赖，不能把调用真实 Android 节点 API 的测试直接塞入 JVM 测试并假定可执行。新增 instrumentation 测试时需显式配置测试 runner 和仅限测试的依赖；或者先执行有记录的真机测试，不能用纯策略测试替代。

逐页使用 TalkBack 验证：

- 左右滑动可依次聚焦，每个虚拟节点可朗读且有可见焦点反馈。
- 双击激活恰好一次，执行与触摸相同的动作。
- 触摸探索移动和退出正常，不因重复绘制不断重新播报。
- 设置展开/折叠、摄像头列表变化、编辑页关闭后，焦点不留在隐藏控件。
- 保存中按钮朗读为不可用或明确状态，无法绕过 F06 门禁。
- 横竖屏、左右手布局、较大字体和显示缩放后，朗读目标与可见控件一致。
- 如交付键盘支持，Tab/Shift+Tab、方向键和 Enter/Space 可用，输入焦点不破坏 TalkBack 焦点。

## 11. 跨问题集成测试与设备矩阵

### 11.1 必须补充的完整流程

这些测试用于填补现有“纯函数通过但流程仍错误”的缺口：

| 流程 | 操作 | 必须观察的结果 |
| --- | --- | --- |
| 完整校准 | 已有修正→RAW→YUV→ISP→保存→再校准 | 签名逐来源正确，正确修正不漂移，失败来源不丢失 |
| RAW 恢复 | 测量开始→空统计→补拍/失败→恢复预览 | 有界结束，不等不存在的请求，镜头/模式不变 |
| Zone 批量 | 多点→一处波动→共享追加→一处失效 | 只加一张共享图，合格点更新、失效点保留 |
| 异步记录 | 保存→重复点击/返回→暂停→保存回调 | 无重复事务、无过期 UI 更新、记录可恢复 |
| 长时间记录 | 前台持续使用超过 2 分钟，多次拍摄 | 新鲜位置可更新，过期位置不写入，拍摄不等待定位 |
| 无障碍联动 | TalkBack 保存→返回→下一页 | 焦点、enabled 状态、保存门禁一致 |

### 11.2 设备覆盖建议

下面是待测组合，不是已经确认某厂商存在特定故障。每次记录实际机型、系统完整版本、相机 HAL 能力、权限状态、应用提交 SHA 和测试结果；品牌名称不能替代能力检查。

| 组合 | 重点 |
| --- | --- |
| Android 9—10（API 28—29），至少一台非 Google 设备 | 旧定位接口清理、Camera2 metadata 缺失、单请求 RAW 结束、旧系统 TalkBack |
| Android 11（API 30） | 新旧定位 API 分界、取消/null/超时路径 |
| Android 12—14，Samsung One UI 或 Xiaomi MIUI/HyperOS Android 机型 | 精确/粗略授权、前后台限制、逻辑多摄隔离校准；记录真实构建版本 |
| OPPO/OnePlus ColorOS/OxygenOS、vivo OriginOS/Funtouch OS 中至少一种 | 多摄路由、RAW/YUV 输出组合、自动降级后校准归属、位置 Provider 差异 |
| Android 15—16 的 Pixel/AOSP 参考设备和一台 OEM 设备 | 新系统回调行为、TalkBack、目标 SDK 36 页面/窗口状态 |
| 可折叠或平板，至少一种可变窗口环境 | 预览几何不变、焦点边界、保存/定位生命周期 |
| 支持多个物理镜头且能输出 RAW 的设备 | 主摄/超广角/长焦分别校准；物理路由不混用 |
| 无 RAW 或某副摄无 RAW 的设备 | YUV/ISP 校准、失败来源保留、降级不伪装为 RAW |
| 无 Google 服务或无网络定位 Provider 的 Android 设备 | 不依赖 Google SDK，Provider 缺失时有界退出 |

本轮不宣称支持非 Android 应用运行环境，也不以厂商品牌推断 RAW 支持。未拿到对应设备时标注“未验证”，不要填“通过”。

### 11.3 性能、安全和隐私检查

- RAW 质量计算复用已有 ROI 统计，计算量随现有帧数/目标数增长；不为了质量判断重新扫描全分辨率 RAW。
- 比较同设备同场景下的请求数、tap-to-result、tap-to-ready、失败耗时、峰值内存；帧数上限不因多个目标叠加。
- 记录保存仍在 I/O 执行器中完成，主线程不能等待 Future、同步复制 DNG 或同步跑事务恢复。
- 定位请求计数可解释；后台请求数应为零，用户关闭后没有迟到位置写回。
- 调试诊断记录 token、来源、拒绝原因和计数即可；发布日志不增加经纬度、备注、图片内容或可还原隐私的明文输出。
- 不放宽文件路径校验、备份边界或权限；不要以增加后台权限来解决定位刷新。
- 不把这 8 项逻辑修复表述为“完成全部安全审计”。

## 12. 执行检查单、验证命令与回退

### 12.1 开始任何代码修改前

在项目根目录执行只读检查：

```powershell
git status --short
git branch --show-current
git log -5 --oneline
git diff --stat 6810ac4..HEAD
```

如果 HEAD 已变化，先检查本项涉及文件的新增提交；如果已有未提交改动，保留并与任务所有者确认重叠部分。不得为了套用本文回退、覆盖或清空他人的修改。

建议先写失败用例，再修改实现。每批完成后检查新测试在旧逻辑确实会失败、在新逻辑通过，避免只测试新增模型而不测试真实调用链。

### 12.2 分批验证

```powershell
.\gradlew.bat testDebugUnitTest --rerun-tasks
.\gradlew.bat lintDebug assembleRelease
git diff --check
git status --short
```

执行命令成功只是第一道门。还应核对测试报告中的 failures/errors/skipped，检查 lint 警告相对基线是否新增，并保存关键设备回归结果。新加 instrumentation runner 后再执行其设备测试任务；当前没有配置好时，不能把未执行的设备任务记作通过。

发布前还需检查生成 APK 的文件清单、现有 ABI、原生库和许可证，确认本文件及测试资料没有进入 APK。文档更新本身不需要改 versionCode；正式发布再统一对应源码、README、更新说明和 Release。

### 12.3 完成定义

- [ ] F01：不再在最终保存时用活动会话读取各来源旧修正。
- [ ] F02：曲线重复校准闭环通过，同帧亮度与基准 EV 不错配。
- [ ] F04：所有统计无效、补拍、预算耗尽路径均有终态，没有无请求空等。
- [ ] F03：EV 单位和尺度不变性测试通过，单帧未知不伪装为稳定。
- [ ] F05：批量自适应真正接入 target.stats，始终共享捕获且不污染未更新点。
- [ ] F06：保存令牌与生命周期闭环，过期回调不能改变新草稿。
- [ ] F07：持续前台记录可按需重新定位，关闭/暂停后无后台跟踪。
- [ ] F08：真实 TalkBack 流程通过，焦点/事件/隐藏控件处理完整。
- [ ] Normal/Zone/设置/摄像头管理切换保持用户模式、镜头和稳定预览几何。
- [ ] 现有单元测试与新增测试通过，lint 无新增未解释问题，release 构建成功。
- [ ] 每项记录为“未开始/实现中/自动测试通过/真机验证通过”，不以一个“完成”掩盖未验证状态。

### 12.4 回退原则

按批次保留独立提交，用新的 revert 提交回退，不重写已经存在的历史。数据模型跨文件修改应整体回退；不得只回退 UI 参数而留下不兼容的持久化读取逻辑。

- 校准：保留历史和来源签名；升级迁移尽量增量、非破坏。回退应用不应删除用户旧校准。
- RAW：如出现性能回归，可临时回退自适应决策提交，但保留无效帧终结修复；不回退 HAL 隔离。
- 记录：持久化事务与 token 修复一起审查，不能以回退线程化恢复主线程磁盘 I/O。
- 定位：回退刷新策略不能恢复写入过期坐标；保持年龄和权限验证。
- 无障碍：保留触摸行为，不为了回退虚拟焦点破坏原始按钮动作。

## 13. 后续实施记录模板

每批完成后在代码审查或独立开发记录中填写，不需要改写本文的原始基线事实：

```text
问题编号 / 提交 SHA：
实际修改文件与接口：
复现测试（修复前失败、修复后通过）：
单元测试 / lint / release 构建结果：
设备型号、系统构建、相机路由、权限条件：
真机操作步骤与结果：
仍未覆盖的条件：
是否修改持久化格式、迁移/回退验证：
性能变化与请求次数：
长期约束核对结论：
```

最终交付应是可逐条证明的修复，不是仅新增策略类型或让测试数量增加。尤其要证明 F01/F02 的快照真正到达保存入口、F03/F05 的策略真正驱动捕获，以及 F06/F07/F08 的状态真正绑定界面和生命周期。
