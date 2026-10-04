# 光档：RAW 组合确认后禁止自动降级——问题分析与修改交接文档

日期：2026-09-22。仓库：`D:\Project\lightmeter a`。HEAD：`44b544c`，分支：`codex/camera-controller-components`。

本文件基于 HEAD **加现有 22 个未提交文件**编写，不是只分析已提交版本。本次只新增文档，不修改运行时代码、不提交、不覆盖已有改动。

## 1. 最新需求与边界

用户反馈：本来支持 RAW 的摄像头/设备，测光过程中突然提示 RAW 不可用，之后自动使用 YUV，无法继续使用 RAW。

用户要求：

> 先用组合矩阵测试初筛，后面的要求不必太严格；确定以后，后续不再自动降级。

### 1.1 本文采用的明确行为

1. **初筛阶段可以搜索组合。** 按能力和组合矩阵排除确实不可能的配置，保留矩阵未覆盖的 UNKNOWN 配置进行实际配置尝试。
2. **确认阶段只要求最小可用性证据。** 对实际会话、RAW 传输、必要的资源/身份安全做有界验证，不要求场景亮度、纹理、帧间波动、预览颜色等满足苛刻质量条件。
3. **RAW 工作流确认后锁定。** 测量失败、单帧异常、噪声、预览健康告警、超时以及连续失败，不得自动换为 YUV/ISP，不得自动淘汰或清除已确认 RAW 组合。
4. **禁止单次隐式降级。** 已确认 RAW 的一次测光失败，也不能偷偷调用 YUV/ISP 产生替代读数。默认本次失败、保留旧读数并标记未更新，下一次仍尝试 RAW。
5. **恢复与降级分离。** 可以关闭失效资源、按原镜头和原 RAW 工作流进行有界重开；重开失败则暂停相机/测量并提示重试，不自动选新镜头或低精度组合。
6. **用户仍可主动切换。** 明确点击改用 YUV/ISP、选择其他镜头、重新测试组合、改变测光模式后，才允许执行对应改变。
7. **确认不是永远忽略环境变化。** 换设备、换实际路由、OTA、输出配置不再存在时，旧确认进入“需要重新验证”，不能假称仍受支持，也不能无提示自动落到 YUV。

本轮将“后续不再自动降级”解释为确认后的整个工作流不被自动替换，包括同一次测光来源、常驻组合、持久化默认组合及镜头路由。不要只禁止某一个 Toast 或仅阻止持久化。

### 1.2 与旧长期约束的关系

旧要求强调保留 HAL 隔离与自动降级设计。本次用户最新要求收紧其中的**确认后运行时降级**：

- 保留 RAW/YUV/预览隔离、资源释放顺序、初筛候选搜索和用户主动切换能力。
- 删除或改造已确认组合的自动精度降级权限，而不是把 HAL 会话合并来“修好”RAW。
- 真实设备断开、服务错误或权限撤销仍必须安全停止/清理；不得继续向已关闭会话提交请求。
- 本文在这项产品行为上优先于旧文档中“连续 RAW 失败后自动改用预览流”的描述。

## 2. 当前代码证据与问题链

以下位置基于编写时工作区。Kotlin 文件除特别说明均位于 `app/src/main/java/com/lightmeter/rawmeter/`，测试位于 `app/src/test/java/com/lightmeter/rawmeter/`。行号用于定位，修改时以函数为准。

尚未取得本次设备故障的 logcat，因此不能断言用户那一次事件具体属于哪条路径。以下是代码中已经确认存在、能够解释该现象的路径。

### 2.1 普通 RAW 失败既立即替代，又累计降级

`CameraController.onRawMeteringError()`，约 299—329 行：

```text
RAW 返回任意测量错误
  → 恢复预览
  → 若预览可用，recordRawMeasurementFailed(AUTO)
  → 立即 measureCompatiblePreview(...)
  → 连续失败达到阈值后置 downgradeAfterCompatibleMeasurement
```

`CameraRecoveryStateMachine.recordRawMeasurementFailed()` 只累计次数，不区分统计无效、代码 bug、帧超时、用户取消、硬件不支持。`RAW_FAILURES_BEFORE_DOWNGRADE` 当前为 2。

因此“相机能够输出 RAW，但这一张不能完成测量”会被升级成“后续应该放弃 RAW”。这不只是探测阈值偏紧，而是错误类别与策略权限混在一起。

### 2.2 隔离 RAW 路径也会静默使用兼容流

`CameraController.completeZoneRawTransaction()`，约 1434—1463 行：恢复常驻会话后，如果本次 RAW 没有交付结果且不是批量请求，会再次累计 RAW 失败并调用 `measureCompatiblePreview()`。

不要仅修普通 `onRawMeteringError()` 而遗漏这条路径。`raw_fully_isolated_v1` 在 Normal 下也使用瞬时 RAW 事务，不能根据函数中的 Zone 命名认定只影响 Zone。

批量路径目前失败后直接报错，不做上述替代；修改时应保持“失败点不覆盖原值”，不能为了统一行为给批量加入逐点 YUV 替代。

### 2.3 组合淘汰会清除缓存，低精度结果又可能被缓存

`afterCompatibleMeasurement()`，约 2203—2296 行：

- `downgradeAfterCompatibleMeasurement` 会触发 `advanceSystemCombination()`。
- 系统搜索失败后还会直接切 `COMPATIBLE/PREVIEW_ONLY`。
- 手动选择模式也有直接把 `activeCombinationPlan` 改成 YUV/ISP 的分支，不能假定“手动选 RAW 就安全”。

`advanceSystemCombination()`，约 2605—2626 行：

```text
rejectedSystemCombinationIds += 当前组合
clearSystem(当前路由, 当前模式)
选择下一个组合并重开
```

`startPreview()`，约 2851—2868 行，又会在预览稳定后调用 `saveSystem()`。这意味着运行时降级后的 YUV/ISP 可以成为之后恢复时的默认组合。“下次重新启动就恢复 RAW”不是可靠保证。

### 2.4 预览健康检查也能独立淘汰 RAW

`CameraPreviewHealthCoordinator.requestRecovery()` 当前可以依次：降低帧率、`advanceSystemCombination()`、`tryNextCameraRoute()`、切安全预览或失败停止。

`PreviewHealthSampler` 当前不是无限持续扫描，而是在重启的 6 秒采样窗口中读取 64×64 图像、每 4 帧采样一次。不能错误描述为永久全帧 RAW 扫描；真正的问题是**预览画面统计告警拥有否定整个 RAW 组合的权限**。

画面纹理、颜色、静止、黑暗、会话切换暂时冻结，都不应成为已确认 RAW 工作流的永久否决证据。

### 2.5 会话失败和 CameraDevice 错误仍有其他降级出口

`handleSessionFailure()`、`handleCameraFailure()`、`CameraRecoveryPolicy.decide()`、`scheduleRecovery()` 仍可选择下一个 profile/route。即便删除“连续两次失败”的分支，也会从这些入口绕过限制。

确认后遇到真实 CameraDevice 错误应关闭失效对象并按原工作流重试或停止。Android 对设备/服务错误的回调有独立定义，不能为了保留 RAW 而吞掉这些回调。[CameraDevice.StateCallback 官方文档](https://developer.android.com/reference/android/hardware/camera2/CameraDevice.StateCallback)

### 2.6 rawAvailable 同时被用来代表能力与当前会话状态

多个 `postInfo()` 使用 `profile.usesRaw && rawHardwareAvailable` 更新 `cameraInfo.rawAvailable`；但隔离 RAW 工作流的常驻预览本来就可以没有 RAW Surface。

`startMeasurement()` 约 1606—1609 行在 `rawAvailable=false` 时直接进入兼容流；`startPreview()` 约 2831—2840 行可能触发 `onRawUnavailable()`。`ParameterRecordToolView.resumePage()` 还会因为此字段为 false 把 `recordRaw` 偏好关掉。

必须区分“硬件声明 RAW”“工作流确认可用”“此刻已创建 RAW Surface”“当前捕获是否就绪”。否则只改恢复策略，UI 和功能开关仍会继续表现为 RAW 消失。

### 2.7 当前未提交代码中的前置 bug 会放大降级

最新审阅确认 `RawLightMeter.processPair()` 约 475—479 行的修复存在回归：基础 3 帧时，第一帧有效后也把 `expectedFrames` 改成 2；第二帧稳定时提前结束，随后因不足 `baseFrames` 报错。

这类应用逻辑错误同样会流入上面的“连续失败→组合淘汰”。必须先修复帧数状态机，不能只禁止降级后留下连续报错的 RAW 路径。

## 3. 目标架构：初筛、确认、运行三个阶段

### 3.1 状态与权限

建议新增纯 Kotlin `CameraWorkflowConfirmationState.kt` 和 `CameraRuntimeFailurePolicy.kt`；名称可调整，不另建相机会话控制器。

| 状态 | 含义 | 是否允许自动尝试其他组合 |
| --- | --- | --- |
| `UNVERIFIED` | 无当前环境有效证据 | 可以进入初筛，不能显示为已确认 RAW |
| `PROBING` | 正在尝试候选组合 | 可以在有界预算内切下一个候选；暂时占用等原因不作为永久黑名单 |
| `CONFIRMED` | 工作流已通过最小实际验证 | 不允许；来源、plan、镜头锁定 |
| `RECOVERING_SAME_WORKFLOW` | 运行错误，重建原工作流 | 不允许；只重建同一配置或使用工作流中已验证的恢复步骤 |
| `TEMPORARILY_UNAVAILABLE` | 本次无法使用，等待重试/外部条件变化 | 不允许；保留确认与用户选择，不制造 YUV 读数 |
| `NEEDS_REVALIDATION` | 环境/实际配置发生变化 | 提示重新测试；不能静默改成低精度组合 |

能力与运行状态分开保存。不要用 `CONFIRMED → PROBING` 作为运行失败后的自动路径，否则会重新获得淘汰权限，实质仍是自动降级。

### 3.2 确认绑定范围

确认记录至少包含：

- 选择路由 ID、实际逻辑/配置物理路由和 route kind。
- 工作流 plan ID、所属用户模式、各阶段输出尺寸/格式等配置签名。
- 系统构建指纹、兼容策略版本及确认模型版本。
- 确认来源：系统初筛成功、手动验证成功、用户明确选择低精度工作流。
- 实际验证过的阶段及成功时间，用于诊断，不靠时间自然过期清除一个稳定组合。

会话 generation 是运行期迟到回调门禁，不是持久化兼容签名。普通页面切换、正常 close/open、进入前后台和 Normal/Zone 切换不能撤销同环境确认。

## 4. 初筛如何放宽，哪些检查不能放宽

### 4.1 组合矩阵继续作为第一道筛选

复用 `CameraCombinationMatrix.inspect()`、`CameraCombinationPolicy.orderedCandidates()`，保留 RAW split/fully-isolated 优先、FULL 次之的现有策略。

- 硬件不提供 RAW 格式、没有对应输出尺寸、输出数量确实不满足：不假装支持。
- mandatory table 包含组合：记为 GUARANTEED。
- 表项缺失/API 不支持：记为 UNKNOWN，仍可实际配置，不当作拒绝名单。
- HAL 查询抛不支持查询或暂时访问异常：不视为硬件不支持。

Android 提供 mandatory stream combinations 描述必需支持的组合；它不是把未知厂商组合全部排除的依据。[MandatoryStreamCombination 官方文档](https://developer.android.com/reference/android/hardware/camera2/params/MandatoryStreamCombination)

当前 `CameraSessionCoordinator` 已在预检返回 false 时继续实际配置，并对部分查询异常保留真实配置机会。应保留该兼容处理，不重复增加更严格的预检拒绝层。代码里关于具体厂商的注释是已有项目经验，本次没有新增真机验证，不扩大为所有同品牌设备的结论。

### 4.2 最小实际确认，而不是苛刻的测光质量考试

复用 `CameraCombinationWorkflowProbeRunner`，但将结果拆成传输、测量条件和可选质量三个维度。

| 检查项 | 阶段/失败处理 | 是否能否决已确认 RAW |
| --- | --- | --- |
| 会话可创建、所需 Surface 存在 | 初次确认必需；运行期失败重建原工作流 | 否，运行期只暂不可用 |
| 能收到 RAW 图像，格式/尺寸/stride/buffer 范围可安全读取 | 初次确认传输必需；非法帧直接丢弃 | 否，不能因此写成硬件永久不支持 |
| 图像与结果属于当前请求/正确镜头，时间戳配对可信 | 每次都保留；不匹配不计算 EV | 否，本次拒绝或有界重试 |
| ISO/曝光时间有效，光圈有合法动态或静态来源 | 精确测光必需；缺失时本次无有效结果 | 否，不因一次缺字段删除传输确认 |
| 黑/白电平、CFA 等分析前提 | 保留解析安全与数值前提；用既有合法静态回退 | 否；持续缺失可提示分析暂不支持，但不暗中换 YUV |
| 高光占比、暗场、低纹理、帧间 EV 波动 | 诊断、置信度、有限追加或提示重测 | 否 |
| 预览绿偏、条纹、静止等启发式结果 | 初筛辅助诊断；确认后只告警，不淘汰组合 | 否 |
| 帧率/延迟不理想 | 记录性能或在原工作流内按既有规则降预览 FPS | 否 |

“放宽”应是移除不必要的能力否决和永久惩罚，不是接受错镜头元数据、越界 buffer、缺失曝光数值后随便计算 EV。不得把逻辑相机结果强塞给物理 RAW 来提高通过率。

建议将 `RawProbeFrameHealth` 拆为 `transportUsable`、`measurementPrerequisitesPresent`、结构化原因。确认可以记录“RAW 传输已验证，但当前测光字段待观察”；此时 UI 不显示已经得到有效测光结果。若一直没有必要字段，保持 RAW 选择并明确失败，允许用户主动选择兼容模式。

### 4.3 探测次数与超时

当前 RAW 探测阶段超时为 3 秒、每阶段主要取一帧。建议允许同一候选的一次有界重试，对暂时占用、热启动和初始化缺帧进行区分。具体超时作为工程参数单独测试，不通过无限延长超时或反复捕获追求成功。

一次占用、用户暂停、序列被主动中止应记为 `INCONCLUSIVE/CANCELLED`，不能把该组合写入持久拒绝名单。确实未成功的候选在本轮初筛中可暂跳过，但“初筛没测成”与“硬件不存在 RAW”使用不同文案。

完成所选工作流的必要阶段后即可锁定。Normal/Zone 有不同 resident profile 是正常工作流步骤，不属于确认后自动降级。

## 5. 确认后的错误处理规则

建议定义结构化 `RawMeasurementFailure`（新增或扩展现有失败模型），不要继续只用本地化 message 字符串驱动策略。

| 错误类别 | 当前操作 | 后续默认 | 禁止动作 |
| --- | --- | --- | --- |
| 用户取消/页面退出/旧 generation | 清理并结束，不报能力失败 | 原组合保留 | 累计 RAW 失败并淘汰 |
| 无效 ROI、太暗、统计缺样本、特征匹配失败 | 有限补拍或本次失败 | 下一次 RAW | 自动改 YUV、覆盖 Zone 原点 |
| RAW 图像或匹配结果超时 | 有界补拍或本次失败，清理配对器 | 下一次 RAW；需要时重建同工作流 | 把超时等同于“不支持” |
| 物理身份不符/非法布局 | 立即拒绝该帧并释放 | 同工作流恢复或停测 | 使用错误 metadata / 放宽 native 边界 |
| 会话失效/捕获提交失败 | 终结当前事务，恢复原工作流 | RAW | 调下一个低精度 plan |
| CameraDevice 被占用/资源不足 | 等待或有限重试 | 原镜头原 RAW | 清除已确认缓存 |
| 设备断开/服务错误/权限撤销 | 关闭对象；重试受权限和可用性约束，失败停止 | 保留选择，提示重试/设置 | 假称 RAW 正在工作、强行提交请求、自动换镜头 |
| 仅预览健康统计异常 | 标记告警；默认不因纯启发式异常自动重开 | RAW | 淘汰组合、降低测光精度 |

重试预算需要按恢复事件有界，而不是依赖“切成 YUV 才能退出错误循环”。预算耗尽必须到 `TEMPORARILY_UNAVAILABLE`，停止自动动作并等待用户重试/外部可用性恢复；不能忙循环。

若保留用户明确选择的“安全预览”，它只是维护画面的动作，不能顺手改变默认测光来源或缓存。确认 RAW 后也不能自行进入一个会把 RAW 路径永久关闭的安全预览模式。

## 6. 修改文件、位置、方法

### 6.1 P1：先修应用自身的 RAW 帧数错误

| 文件 | 修改位置 | 方法 |
| --- | --- | --- |
| `RawLightMeter.kt` | `MeasurementAccumulator`、`processPair()`、`fillPipeline()`、`finishWithReading()` | 分离基础有效帧要求与实际请求预算；目标不因正常收帧缩小；有效 3 帧必须能完成；预算耗尽有终态 |
| `RawMeteringPolicyTest.kt` | ISO 策略边界 | 保留 1/2/3 帧策略，验证 ≥1200 ISO 路径 |
| 建议新增 `RawBurstProgressPolicyTest.kt` | 完整进度决策 | 覆盖稳定 3 帧、无效补帧、预算耗尽，不仅测 frameCount 返回值 |

不得简单把高 ISO 基础帧数改成 2 规避错误。F03 的 EV 对数计算和批量共享 RAW 修复应保留。

### 6.2 P1：阻止单次和累计精度降级

| 文件/函数 | 现有行为 | 修改要求 |
| --- | --- | --- |
| `CameraController.onRawMeteringError()` | 立即 `measureCompatiblePreview()` 并累计失败 | 已确认 RAW 时只结束本次 RAW/有限重试，发布结构化错误；不能产生 YUV 替代结果 |
| `completeZoneRawTransaction()` | 隔离 RAW 失败后调用兼容流 | 恢复已确认工作流的 resident session 后报本次失败；保持 batch 未更新规则 |
| `afterCompatibleMeasurement()` | 累计失败后换组合；手动模式也可能换 plan | 删除确认后降级分支；兼容测量只允许来自初筛或用户选定兼容路径 |
| `CameraRecoveryStateMachine.recordRawMeasurementFailed()` | 次数决定降级 | 改为只统计诊断/恢复预算，不能返回隐含“降级许可” |
| `CameraController.startMeasurement()` | `!cameraInfo.rawAvailable` 自动走兼容流 | 按已选择工作流来源派发；RAW 尚未就绪应准备瞬时 RAW 或报等待/失败，不静默改来源 |
| `CameraController` 各测量入口 | `requestedSource` 与 profile 交织 | 校准显式 YUV/ISP 属于正常来源选择；不能误禁校准顺序，也不能把校准某来源失败当普通 RAW 降级 |

将是否允许改变工作流集中到一个策略入口，例如 `mayReplaceWorkflow(reason, phase, userAction)`。返回值必须能区分“初筛可换”“用户明确可换”“确认后禁止”，不能只在每个调用点随意加一个 Bool。

### 6.3 P1：封住其他降级出口

| 文件/函数 | 修改要求 |
| --- | --- |
| `advanceSystemCombination()` | 只允许 PROBING 阶段调用；确认后禁止写 rejected 集合、清缓存或换 plan；记录被拒绝的策略动作供诊断 |
| `handleSessionFailure()` | 已确认时重试同一工作流/暂不可用；不能走 nextProfile 或自动 tryNextCameraRoute |
| `handleCameraFailure()`、`CameraRecoveryPolicy.decide()` | 增加确认阶段和用户意图输入，确认后仅 RETRY_SAME/STOP；初筛保留候选搜索 |
| `scheduleRecovery()` | 校验请求的 profile 属于已确认工作流的合法阶段；不能把一次“恢复”当作切低精度组合的后门 |
| `CameraPreviewHealthCoordinator.requestRecovery()` | 确认后启发式告警不调用 advance/换镜头/safe-profile 降级；保留诊断、用户重试和真实会话故障恢复 |
| `CameraController` 的 previewHealth 回调绑定 | 与新策略统一，不能只修改 helper 而保留回调中的直接 PREVIEW_ONLY 重开 |
| `tryNextCameraRoute()` 的所有调用者 | 确认后不自动换用户镜头/运输路由；若需要新路由，提示重新验证或用户选择 |

注意：仅让 `advanceSystemCombination()` 返回 false 不够。当前调用者会在 false 后继续执行旧 profile 降级分支，所以需要明确的 `BLOCKED_BY_CONFIRMED_POLICY` 结果，或在调用方先完成阶段分派，不能把“禁止”误当“没有候选，走备用降级”。

### 6.4 P1：持久化确认，防止下次仍使用被污染的低精度缓存

| 文件 | 修改方法 |
| --- | --- |
| `CameraCombinationSelectionStore.kt` | 持久化确认来源、工作流配置签名、策略版本；分别保存用户选择与系统探测证据；运行时错误不 clearSystem |
| `beginPendingSystemWorkflowProbe()` | 只有有效完成当前探测事务才能写确认记录；令牌要包含路由、plan、环境、generation，防旧回调写入新镜头缓存 |
| `startPreview()` 中稳定后 `saveSystem()` | 不凭“预览稳定”将工作流认定为新确认；只允许更新已有同签名确认的诊断时间，或删除该无条件写入 |
| `openCamera()` 选择缓存分支 | 优先恢复同环境确认 plan，不能因运行期 rejected 集合屏蔽已确认 RAW；校准/临时 session 不覆盖它 |
| `resetRecoveryState()` / start/stop | 重置运行时计数与资源，不擦除确认事实；不同路由重新载入各自记录 |
| `acceptManualCombination()` | 用户确认成功后写入同一套确认模型，手动/系统共享运行保护 |

旧缓存只有 planId，没有“初筛确认/运行降级”来源，因此不能可靠推断旧 YUV 是否为误降级。迁移建议：

1. 保留旧值作历史，不直接删除。
2. 旧系统记录标记 `LEGACY_UNVERIFIED`。RAW 硬件存在且旧值为低精度时，提供一次明确的重新初筛机会；不要把旧 YUV 直接盖章为永久正确。
3. 用户明确手动选择的 YUV/ISP 保留意图，不能因为新策略强制切 RAW。
4. 新策略完成一次最小确认后，启动不再重复严格探测；同环境复用确认。
5. OTA/配置变化时提示重验，不能自动清空手动选择并把页面模式改成系统低精度。
6. 不用整包清空 SharedPreferences 解决旧缓存；不要影响设备校准、曝光预览校准、位置及记录数据。

### 6.5 P2：能力、就绪、文案和偏好解耦

建议在 `MeterModels.kt` 的 `CameraUiInfo` 增加清晰字段或状态对象，逐步迁移调用者：

```text
rawHardwareSupported           静态声明/输出能力
selectedWorkflowSource         用户/初筛确认的测光来源
rawWorkflowConfirmed           当前签名下 RAW 工作流已验证
rawSessionReady                此刻 RAW 输出/会话是否就绪
runtimeAvailability            READY / PREPARING / RECOVERING / TEMP_UNAVAILABLE
```

不要一次把旧 `rawAvailable` 全局替换成 true。各调用点必须按意图区分：

- `RawLightMeter`、DNG、色温、暗角校准：是否能安全发起当前请求；未就绪时准备对应工作流或提示，不直接拿空 Surface。
- Normal/Zone 的来源选择：依赖已确认工作流，而非当前 resident session 是否带 RAW。
- `MainActivity.onRawUnavailable()` / `showRawUnavailableDialog()`：仅初筛无 RAW 选择或真正硬件不支持时使用能力文案；暂时错误用“本次 RAW 测光失败，可重试”。
- `ParameterRecordToolView.resumePage()` / RAW 开关：临时不就绪不得反向关闭用户 `recordRaw` 偏好；必要时保留勾选并提示本次未取得 DNG。
- 结果徽标：失败后不把原 RAW 结果改标为 YUV，不伪造新时间；显示“未更新/本次失败”。
- Zone 批量：未成功的点不写回，已离开有效画面或匹配失效的点计入未更新。

建议默认失败提示提供“重试 RAW”“重新测试组合”“手动选择兼容模式”，不要自动执行第三项。若显示“改用 YUV”，必须由用户明确点击后才执行。

## 7. 推荐实施顺序与提交范围

| 批次 | 内容 | 验收后再进入下一批的条件 |
| --- | --- | --- |
| A | 修正当前 RAW 基础 3 帧被缩为 2 帧的问题 | 稳定 3 帧和无效补帧测试通过，没有空等/提前失败 |
| B | 新增确认状态与失败类型，接入普通和隔离 RAW 失败处理 | 已确认 RAW 单次失败不产生 YUV 结果；不清确认 |
| C | 重写恢复策略与全部组合/路由切换权限 | 所有恢复入口无法绕过确认锁，真实设备错误仍安全终结 |
| D | 最小探测结果分层、确认缓存及旧缓存迁移 | 初筛能工作，同环境重启保留 RAW，旧缓存可安全重验 |
| E | UI 能力字段、提示、RAW 记录偏好 | resident 无 RAW 时仍正确展示瞬时 RAW 能力，偏好不自动关闭 |
| F | 完整回归、设备数据与文档更新 | 通过第 8 节矩阵，记录未覆盖机型，未引入资源/生命周期回归 |

每批同时带测试。B/C/D 有行为依赖，不将其中尚未封闭所有降级入口的中间版本作为正式发布版本。不得只提交新增状态枚举、未接入调用链就宣布修复。

建议提交主题：

```text
fix(raw): preserve base valid-frame requirement
fix(camera): separate workflow confirmation from runtime failures
fix(camera): recover confirmed RAW workflows without automatic downgrade
fix(camera): persist minimal workflow confirmation safely
fix(ui): distinguish RAW capability from temporary readiness
test(camera): cover confirmed RAW failure and recovery workflows
```

本文件不要求自动创建这些提交。实施者应遵守用户当时的提交授权和工作区状态。

## 8. 必须执行的测试

### 8.1 已有测试与需要更新的预期

| 文件 | 具体动作 |
| --- | --- |
| `CameraRecoveryStateMachineTest.kt` | 当前 `two consecutive raw failures request compatible pipeline until raw succeeds` 固定了旧行为。拆成未确认探测与已确认运行两类；确认后失败 2 次、10 次都不能请求降级 |
| `CameraRecoveryPolicyTest.kt` | 区分 PROBING/CONFIRMED。保持资源释放/停止测试，修改确认后 DEVICE/SERVICE/CONFIGURE 的降级预期 |
| `CameraCombinationPolicyTest.kt` | 保留 UNKNOWN 候选；初筛可搜索，用户已选低精度也受尊重 |
| `CameraCombinationWorkflowProbeTest.kt` | 阶段顺序、一次 RAW 传输证据、取消不拒绝、字段缺失不永久淘汰、旧回调不确认新路由 |
| `RawProbeFrameHealthPolicyTest.kt` | 将非法布局/身份与暂时测量元数据缺失分层测试；不能简单把所有断言改成通过 |
| `PreviewHealthAnalyzerTest.kt`、`PreviewHealthSamplingWindowTest.kt` | 保留检测与有界采样；检测结果不得直接拥有修改已确认工作流的权限 |
| `ZoneRawTransactionCoordinatorTest.kt`、`ZoneRawSessionStateTest.kt` | 失败恢复 resident 后仍选 RAW；批量点不被兼容流覆盖；仅一次终态 |
| `RawMeteringPolicyTest.kt`、`RawMeteringQualityTest.kt` | 保留 ISO 帧数与正确 EV 波动计算，补实际进度集成验证 |
| 建议新增 `CameraWorkflowConfirmationStateTest.kt` | 状态转移、确认范围、用户切换与环境变化 |
| 建议新增 `CameraRuntimeFailurePolicyTest.kt` | 各类错误与重试/停止决策，不允许确认后精度降级 |
| 建议新增 `CameraCombinationConfirmationStoreTest.kt` | 缓存升级、旧系统 YUV 来源未知、手动选择保留、跨环境不可盲用 |

涉及 Android SharedPreferences/Camera2 的真实代码不能直接假定 JVM 可运行。优先抽纯决策与可注入存储/调度接口；真实 Android 接入另用 instrumentation 或真机回归。不要只新增 mock 测试而漏掉生产调用入口。

### 8.2 场景验收矩阵

| 编号 | 操作/注入条件 | 必须结果 |
| --- | --- | --- |
| T01 | 无缓存，RAW 格式存在，mandatory matrix 未列出该组合 | UNKNOWN 仍尝试；实际配置和传输成功后确认 RAW |
| T02 | 初筛第一 RAW 候选确实无法配置，第二隔离候选可用 | 允许初筛继续，确认后固定第二个 RAW 工作流 |
| T03 | 初筛被其他应用占用、暂停或取消 | 不永久拒绝所有 RAW，不把该事件缓存成“不支持” |
| T04 | 已确认 RAW 后 ISO≥1200，稳定场景 | 正常完成基础 3 帧，不提前在 2 帧报错 |
| T05 | 已确认 RAW 连续 2 次/10 次空统计或超时 | 本次有界失败；plan、默认来源、确认缓存不变；没有 YUV/ISP 测量请求 |
| T06 | Normal 使用 fully-isolated RAW，或 Zone 单点 RAW 失败 | 恢复该工作流 resident 后报失败，下一次仍 RAW |
| T07 | Zone 批量中部分点越界/匹配失败/元数据失效 | 只更新有效点，其他点保持旧值；共享 RAW，不逐点补拍兼容流 |
| T08 | 预览场景是绿叶、条纹、遮挡、暗场、静止画面 | 可有诊断告警，但已确认 plan/镜头不变，缓存不被清空 |
| T09 | 已确认后出现 configure failure/DEVICE/SERVICE 错误 | 清理资源；有限重开原工作流或暂停，不自动调用下一组合/镜头 |
| T10 | DEVICE 断开、权限撤销或进后台 | 不向失效 session 提交；无无限重试；确认记录不被改成 YUV |
| T11 | 同环境启动、返回前台、Normal↔Zone 切换 | 恢复确认选择，不因 resident 不带 RAW 提示永久不支持 |
| T12 | 运行失败后重启应用 | 同一确认 RAW 仍为默认，不能读回运行降级缓存 |
| T13 | 系统升级、物理路由/输出配置变化 | 标记需重验并提示；不盲用旧配置、不静默降级 |
| T14 | 用户明确选择 YUV/ISP 或更换镜头 | 按用户选择执行，不被 RAW 锁强制改回 |
| T15 | RAW 确认失败后用户点击“重新测试组合” | 新探测事务开始；可搜索，旧事务迟到结果无效 |
| T16 | 参数记录启用 RAW，当前是 YUV resident + transient RAW | `recordRaw` 意图保留；拍摄准备 RAW 或明确说明本次失败，不自动关闭开关 |
| T17 | RAW buffer 越界、错误格式、错误物理身份 | 安全拒绝帧，无 native 越界或伪造 EV；后续不自动换 YUV |
| T18 | 旧版本缓存 YUV，但来源不明 | 明确迁移/重验，不能无证据认定用户选择或真实不支持 |

确认后测试不能只检查 `activeCombinationPlan`。至少同时断言：

```text
没有发起 measureCompatiblePreview / measureProcessedPreview 替代请求
没有改变用户模式/镜头
没有写入 rejectedSystemCombinationIds
没有 clearSystem / 保存新的低精度默认组合
没有通过 nextProfile / tryNextCameraRoute / safePreview 绕过限制
没有把 recordRaw 用户偏好关掉
失败结果没有覆盖旧测光值或 Zone 点
所有 Image 和 session 资源得到正确清理
```

### 8.3 真机测试与诊断材料

优先测试用户实际出问题的设备，再覆盖至少一台逻辑多摄 RAW 设备、一台受限副摄/隔离 RAW 设备、API 28—29 和一台较新 Android OEM 系统。不同品牌的静态声明和 HAL 行为可能不同，记录具体机型/系统构建，不把品牌名当作能力证据。

建议每次记录：应用提交及未提交 diff 标识、Android build、所选镜头/路由、plan ID、确认状态、resident/capture profile、失败类别、请求/有效帧数、恢复动作、缓存写入来源。不记录照片内容、经纬度或用户备注。

定位用户本次问题时查找这些已有日志/文案：

```text
RAW metering failed
RAW metering completed
RAW Zone batch completed
Combination search rejected=
Camera combination probe failed
Preview health state=
Recovering from preview health failure
RAW 组合连续失败
RAW 流不可用，已改用预览流
```

需要把“一次测量 ID → 失败原因 → 哪个策略请求变更 → 是否写缓存”串起来，不能仅收集 Toast 截图。取得日志前，只能确认代码存在相关链路，不能宣称已经证实是某厂商 HAL bug。

### 8.4 自动构建与回归命令

在项目根目录执行：

```powershell
.\gradlew.bat testDebugUnitTest --rerun-tasks
.\gradlew.bat lintDebug assembleRelease
git diff --check
git status --short
```

最近一轮审阅对当前代码的结果为 335 项 JVM 测试通过、72 个套件、lint 0 错误/65 警告、release 构建成功。这不是本文方案实现后的验收结果；旧测试甚至明确要求“两次失败后降级”，因此全部通过不能证明满足本次新行为。

## 9. 当前其他审阅问题如何处理

本文件主要解决 RAW 确认和运行策略，不在同一提交中混入所有 UI/校准重构。但交接时不能遗漏当前仍存在的问题：

| 问题 | 与本任务关系 | 建议 |
| --- | --- | --- |
| 高 ISO 3 帧被提前缩为 2 帧 | 直接制造 RAW 失败和错误降级 | 本任务批次 A 先修 |
| 记录编辑器关闭后仍吞返回事件 | 独立 P1，可能干扰手工复测页面导航 | 单独提交：先检查编辑器是否打开，清旧草稿 |
| 校准快照使用按到达顺序中间帧，而非稳健基准 | 独立 P1，RAW 恢复后仍可能错误校准 | 单独提交：RAW 融合未校准 EV，处理流保留同帧元组 |
| 保存中仍可滑动修改曝光参数 | 独立 P2 | 冻结全部编辑手势和动画，不只 handleTap |
| 无障碍节点移除后清除事件未发，退出清理未接入 | 独立 P2 | 保留旧节点发事件，接入页面生命周期 |
| 无效校准快照回退旧保存公式 | 校准完善项 | 缺乏采样证据时拒绝该来源保存，不借用当前会话 |

这些细节见仓库已有 `docs/REVIEW_FIX_IMPLEMENTATION_PLAN_2026_09_22_ZH.md` 及最近审阅记录。实施者先核对现状；不要把两个文档中不同阶段的建议都照搬到同一函数。

## 10. 避免与当前工作区冲突

### 10.1 实施前快照

```powershell
git status --short
git log -5 --oneline
git diff --stat
git diff -- app/src/main/java/com/lightmeter/rawmeter/CameraController.kt
git diff -- app/src/main/java/com/lightmeter/rawmeter/RawLightMeter.kt
```

当前 `CameraController.kt`、`RawLightMeter.kt`、`MeterModels.kt`、`MainActivity.kt`、校准、记录、定位、无障碍等共 22 个文件已有未提交修改。不要 reset、checkout 覆盖、自动 stash 或把它们当作可丢弃的生成文件。

新增状态模型应沿现有职责拆分：纯策略不操作 Camera2；`CameraSessionCoordinator` 继续持有配置过程；Controller 负责路由和任务编排；探测 Runner 管理本次探测图像。不得另造一套与现有 session 生命周期竞争的相机持有者。

### 10.2 不推荐的“快速修复”

- 将失败阈值从 2 改成很大的数：只是延迟触发，仍会清缓存和降级。
- 只隐藏 `onRawUnavailable()` 提示：来源仍可能已变为 YUV。
- 全局将 `rawAvailable` 设为 true：可能对空 Surface 发请求，不能恢复真实工作流。
- 仅在 `advanceSystemCombination()` 返回 false：调用者仍可能执行备用降级。
- 关闭全部健康/元数据/缓冲区检查：可能制造错 EV、错镜头配对或 native 安全问题。
- 强制所有设备使用 preview+RAW+YUV 的 FULL profile：违反厂商隔离策略。
- 将预览 resident 为 YUV 解释为 RAW 不支持：破坏 fully-isolated 和 Zone 路径。
- 遇到真实设备错误继续重复提交：不安全且可能造成错误循环。
- 清空所有配置恢复默认：污染用户镜头、模式、校准和记录偏好。
- 为了“保留 RAW”显示旧读数却标记为本次成功：误导用户，应明确未更新。

## 11. 完成定义与回退

- [ ] 组合矩阵用于初筛，UNKNOWN 不被误当作硬性拒绝。
- [ ] 最小确认与测光质量判断分层，确认后不再反复严格探测。
- [ ] 用户可见模式、工作流来源、RAW 能力与就绪状态分离。
- [ ] RAW 确认后，普通、隔离、批量、预览健康和会话恢复所有入口都无法自动降低精度。
- [ ] 真实错误仍有界恢复或停止，无无限重试，无失效资源访问。
- [ ] 运行失败不会污染确认缓存；重启后仍保留同环境已确认 RAW。
- [ ] 用户主动选择兼容模式、更换镜头、重新测试仍可用。
- [ ] 高 ISO 基础帧数回归已修复，原有统计/共享 RAW/越界点约束保留。
- [ ] 失败不自动关闭 RAW 参数记录偏好，不伪造新的测光结果。
- [ ] 自动测试、真实流程测试和用户问题设备验证有记录；未验证项目明确标注。

回退通过新提交进行，不改写已有历史。确认缓存格式采用非破坏迁移，保留旧记录；需要回退时不能把运行失败重新解释成永久不支持。正式发布时再统一版本号、README、更新说明和 Release，文档和测试日志保持在 APK 打包源集之外。

## 12. 给接手模型 / Agent 的任务摘要

> 先读取本文件与当前 diff。保留 22 个未提交文件中的用户修改。先修 `RawLightMeter` 三帧提前结束，再实现“初筛可以搜索，确认后只同工作流恢复或停止”的状态和策略。必须同时关闭即时 YUV 替代、失败计数降级、预览健康淘汰、会话错误降级和低精度缓存写回等出口。不要削弱 RAW 图像内存、元数据身份和测光必要字段检查。把静态能力、已确认工作流与即时 Surface 就绪分开，保留用户模式/镜头/RAW 记录偏好。按第 8 节逐项写回归测试，在实际问题设备上复现并验证；未取得日志前不要将根因直接归咎于厂商。只在用户授权后提交或发布。

交付报告必须包含：实际修改文件、各降级入口的处理结果、测试失败→通过证据、设备/系统/路由、确认缓存升级行为、未覆盖风险。禁止仅新增策略类却没有生产接入，或仅调高阈值后宣称问题解决。
