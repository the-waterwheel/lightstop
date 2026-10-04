# 曝光预览迟钝、Zone 测光与剩余问题：详细修复交接指导

日期：2026-09-22。面向接手修改的模型 / agent。本文是实施方案，不是已经完成的修复说明。

## 1. 先阅读基线，不要覆盖现有改动

当前 HEAD 为 `44b544cad04fc69588c13c76edd22aac5a1543bf`，分支 `codex/camera-controller-components`，工作区已有大量未提交修改、新策略文件和测试。本次设备 APK 包含这些工作区修改，仅检出 HEAD 无法复现。执行前重新检查 `git status --short`、`git diff --stat` 及下文涉及文件的 diff；本文行号仅供定位，优先按函数名查找。

本方案以 [设备测试与问题记录](DEVICE_TEST_RESULTS_AND_REMAINING_ISSUES_2026_09_22_ZH.md) 为事实依据，尤其第 8 节最新 A/B 结果。更早的 `RAW_WORKFLOW_CONFIRMATION_AND_NO_AUTO_DOWNGRADE_PLAN_2026_09_22_ZH.md`、`REVIEW_FOLLOWUP_FIX_HANDOFF_2026_09_22_ZH.md` 用作背景，不能机械重复其中已经落实的修改。

已验证基线：vivo V2405A / Android 16；debug 0.4.0 / 9；APK SHA-256 `a9a9f3ca087e2eb065b01f2447786c8abc1c8fca7e979ce45e203a684a4a0759`。369 项单测通过，Lint 0 错误、65 警告。本轮仅测试和撰写文档，没有修改运行时代码。

### 必须保留的约束

1. 保留现有进程拆分、HAL 隔离会话、组合矩阵初筛及受控恢复。不能为了速度合并 RAW 与常驻 YUV 会话，不能把常驻 YUV 跟踪误判为 RAW 测光降级。
2. 对已确认的 RAW 工作流，测量质量不足、曝光匹配超时、预览异常不能导致自动换成 YUV 或永久关闭 RAW。恢复应有界、保留确认，无法完成则明确报告本次失败；真正设备故障的镜头恢复按既有规则处理，不借机重置用户模式与镜头。
3. Normal/Zone/设置页切换不重置用户镜头和模式；保持 TextureView/SurfaceTexture 传输几何稳定，不能为提速重建不同尺寸的原生预览层。
4. 逻辑流与物理传感器元数据/几何身份分开；方向、裁切、缩放、镜像均沿用可验证映射。
5. 不增加全局固定 EV 补偿。测光校准、曝光预览校准保持分离，不能重复应用；本轮不要触碰已有校准数据。
6. Zone 全部重测使用同一张或同一组共享 RAW，逐点匹配，越界/不可信点保留旧值并计入未更新。普通测光不能自动刷新所有点。
7. 保留高 ISO 的基础多帧策略和已有有界补帧，不拿减少必要帧数当主要优化；RAW 健康检查不能比重拍更贵，不能用全画面高光饱和比例否定整张 RAW。
8. 不清数据、不清组合缓存、不回退工作区，不改发布历史。文档、日志保留在生产源集外，未验收的性能策略不得写成已发布功能。

## 2. 结论及实施顺序

| 顺序 | 优先级 | 问题 / 工作项 | 本次证据 | 完成边界 |
| --- | --- | --- | --- | --- |
| 1 | P1 正确性 | T01：批量重测的 geometry 回退被当作更新成功 | 无参考的点仍 completed | 只写回通过身份与边界验证的点 |
| 2 | P1 体验 | T04：结果完成与相机恢复共用转圈状态 | Zone 结果后仍等待约 0.20 秒 | 结果交付即停测光圈，相机锁独立 |
| 3 | P1 体验，实施风险较高 | T03：曝光预览开启后重复等待中性 AE | Normal 额外 856–876 ms；Zone 后两笔 878/1201 ms | 先补埋点，再有条件优化，不降低测量可信度 |
| 4 | P2 稳定性 | T02：AF 延迟释放访问旧 session | 本轮 A/B 又出现 4 次已关闭会话警告 | 会话代次校验、取消旧任务 |
| 5 | 发布前复审 | 校准无效样本、确认身份、手动竞态、记录偏好 | 前轮静态审阅剩余项 | 各自集成验证，不能被正常路径测试代替 |

推荐按小提交组织：批量正确性；UI 状态拆分；预览最新目标调度和埋点；AF 会话生命周期；基于数据的基线优化。提交只是后续组织建议，本轮没有执行提交。避免一次性大改 CameraController。

## 3. 耗时解释：先明确优化对象

最新 A/B 中，Normal OFF 的 RAW 处理约 375–390 ms，ON 反而约 315–321 ms，但捕获前多了约 0.86 秒 AE 等待。Zone OFF 点击到结果约 495–508 ms，恢复后约 693–711 ms；ON 后两次点击到结果 1384/1706 ms，恢复后 1590/1906 ms。Zone ON 第一笔没有进入基线等待，是过渡样本，不适合混入稳定 ON 平均值。

当前主要顺序：

```text
用户测光
  → 撤掉曝光预览的手动/补偿参数
  → 等待带本次 tag 的中性 AE（2 个稳定结果，最多 1200 ms）
  → 必要时切换隔离 RAW 会话
  → 捕获 / 匹配 / 融合 → 有效结果
  → Zone 恢复常驻会话
  → 再应用曝光预览目标 → 硬件生效 / 新画面消费
```

前置 AE 等待与结果后的预览恢复是两段不同问题。提前停圈最多去掉结果之后的假性等待，不能消除结果之前的 0.86–1.20 秒。

Normal 的 `onMeterReading()` 已先清 measuring 再更新曝光预览。用户仍反馈画面调好才停圈，所以必须补 UI 绘制和预览帧证据，而不是再次添加同样的 false 赋值。已有 `Exposure preview:` 日志只证明状态应用，不能证明画面显示完成。

## 4. 修改 A：批量重测严格保证点身份，保留共享捕获

主代码路径前缀：`app/src/main/java/com/lightmeter/rawmeter/`。

### 4.1 修改文件与位置

| 文件 | 位置 | 修改方法 |
| --- | --- | --- |
| `MeterModels.kt` | `PreviewLumaReference`、`RawMeterPoint`、Zone 结果模型附近 | 添加显式匹配状态及参考身份；避免只用 matchScore=NaN 表达各种失败 |
| `RawPreviewRegistration.kt` | `resolve()`，约 95 行；无参考回退约 122 行；低相关回退约 196 行 | 区分 MATCHED、NO_REFERENCE、LOW_CONFIDENCE、OUT_OF_VIEW、STALE_REFERENCE / INVALID_METADATA；几何估计可以保留，但不能表示已验证原物体 |
| `CameraController.kt` | `freezeZoneReference()`，约 1305 行 | 冻结本次目标、参考时间及几何身份；参考生成失败时不无条件沿用不明来源旧参考 |
| `RawLightMeter.kt` | `processBatchPair()`，约 506 行；`finishWithBatchReadings()`，约 716 行 | 先检查更新资格，再分析/累积；只有足够合格帧才生成可写回 reading；不合格点有明确跳过原因 |
| `MeterLayout.kt`、`ZoneSystemModels.kt`、`MainActivity.kt` | `completeZoneRemeasureBatch()`、`completeRemeasurements()`、完成计数 | 只更新成功列表里的点；保留其余原 EV/source/坐标，计入未更新；全部跳过也结束事务 |

### 4.2 实施细节

1. 将“新点几何点测”和“旧点重测身份验证”作为不同策略入口。不能把前者正常坐标换算全部禁止。
2. 参考至少关联 route/physical ID、几何代次、预览有效 crop 和采样时间。会话切换不等于参考必然作废：Zone 本身就会切换会话；判断的是目标/几何是否仍对应，而不是简单要求 RAW session 与预览 session 相同。
3. 在边界裁切之前检查原始目标、完整采样区域和匹配结果是否位于有效画面。不能先 clamp 到边缘再认为有效。参考是否过期不应只凭任意毫秒阈值，需结合冻结事务与运动/几何变化。
4. 返回值设计优先选择内部 `PointResolution` 加成功结果列表，减少对外回调改动。若增加 `ZoneBatchOutcome`，同步修改 callback、UI 和测试；不要留下两套互相矛盾的计数。
5. 跳过点不进入该点质量融合；合格点继续共享当前 RAW。禁止按失败点分别重拍、禁止失败时自动换流。
6. 不要直接把现有相关性阈值提高到任意数值来掩盖问题。先补失败状态和身份检查，再用低纹理/重复纹理样本验证阈值。

### 4.3 测试要求

扩展 `ZoneMeterSessionTest.kt`、`ZoneRawTransactionCoordinatorTest.kt`，新增建议文件 `RawZoneBatchEligibilityTest.kt` / `RawPreviewRegistrationPolicyTest.kt`（均位于 `app/src/test/java/com/lightmeter/rawmeter/`）。测试无参考、低相关、重复纹理、过期参考、裁切外点、全部跳过、部分通过、多个 RAW 帧中部分失败、用户已删除旧点。

必须断言：原测量没有被覆盖；updated+notUpdated 等于冻结目标总数；无重复 RAW 捕获；所有点失败时也能恢复会话并结束 UI；已确认 RAW 不被取消。原生 Android/图像代码不能在现有 JVM 测试直接运行时，提取纯资格策略，并另加设备集成覆盖实际接线。

## 5. 修改 B：测量完成即停圈，资源占用和预览匹配独立

### 5.1 最小兼容迁移策略

第一步暂时保留 `state.measuring` 的既有“业务忙 / 阻止冲突操作”语义，避免一次更换全部输入判断。新增独立 `measurementPending` 或 `measurementProgress` 供转圈绘制；同时显式表示 `previewRestorePending`、`exposurePreviewStatus`。这些是建议命名，实施时统一命名并写注释。

长期可把 measuring 更名为 cameraBusy 并集中计算，但不要在第一步建立两个可以随意写入、含义又一样的 Boolean。最好用轻量 reducer/coordinator 派生显示状态和可操作状态，CameraController 继续拥有实际资源锁。

| 阶段 | 测光转圈 | 允许新捕获 | 曝光预览 |
| --- | --- | --- | --- |
| 等待中性基线、RAW 捕获、必要分析/匹配 | 是 | 否 | 临时挂起，保留用户目标 |
| 有效结果已交付，Zone 会话尚恢复中 | 否 | 否 | 保留最新目标，短状态提示恢复中 |
| 会话可用，曝光目标提交/等待生效 | 否 | 按控制器资源状态判断 | 独立更新，不重新点亮测光圈 |
| 预览匹配超时或不支持 | 否 | 若相机可用则可测光 | 显示独立提示，保留有效 reading |
| 取消、失败、页面销毁 | 否 | 按真实资源状态判断 | 取消旧代次，不污染下一次操作 |

### 5.2 修改文件与动作

1. `MeterModels.kt:447` 附近：新增结果等待/展示状态；避免持久化短暂工作状态。
2. 建议新增 `MeteringUiProgressCoordinator.kt`：集中处理 started、resultAccepted、restoreStarted/Finished、failed、cancelled。每个事件携带 measurement ID；相同事件幂等，旧事件不能覆盖新测量状态。
3. `MainActivity.kt`：
   - Normal 测光、Zone 点测、批量重测入口均设 measurementPending=true，失败返回也终结。
   - `onMeteringBaselineRestoring()` 表示结果仍未完成，不提前停圈。
   - `onMeterReading()` / `onZoneMeteringBatchResult()` 在有效结果写入 UI 后设 measurementPending=false；保持 Zone 资源恢复锁，不再用这个锁控制结果转圈。
   - `onMeteringRestoreStateChanged(true)` 只更新恢复状态；结果已经交付时不能重新显示测光圈。false 时显式通知曝光预览调度器资源可用。
   - `finishZoneRemeasureBatch()` 仍负责完整清理和 updated/skipped 总结，但不要把结果可见时刻推迟到这里。
   - 所有 error、onPause、取消、销毁路径都按操作 ID 收尾，不能因一个旧失败回调清除新一轮的圈或锁。
4. `InstrumentView.kt:196`、`ZoneSystemView.kt:391`：转圈绘制改用结果等待状态。触摸与无障碍操作判断继续使用资源安全条件；同步无障碍状态文字，避免“无圈但仍朗读正在测光”。
5. `MeterLayout.kt`：保留主线程 refresh；检查刷新/格式化是否存在重计算，禁止将 RAW 处理或等待搬到主线程。
6. `CameraControllerCallback.kt`、`CameraController.kt`：只在需要时扩展携带 operation ID 的回调或协调器入口。真实 `meteringOperationActive` / Zone 事务锁必须到资源恢复或受控失败清理后才能解除。

### 5.3 用户要求的先后顺序

主线程接受结果 → 更新 EV/点和取消测光圈 → 刷新 UI → 把最新曝光目标交给异步调度。Zone 的会话恢复仍立即在相机线程推进，不等 UI 动画。

不要为制造视觉顺序加入固定 sleep。若埋点证明一次 UI 帧尚未绘制而曝光匹配先反馈，可在视图生命周期内用一次 frame/post 回调调度“目标应用”，但不阻塞相机恢复，不因页面暂停丢失资源清理，且新目标只保留最后一个。首先验证现有 Normal 路径为什么用户感知没有分离，再决定是否需要此额外步骤。

## 6. 修改 C：曝光预览独立调度，最新目标优先

### 6.1 当前耦合点

`MainActivity.updateExposurePreviewFromMeter():1186` 在 measuring 时把 selection 变成 null；`ExposurePreviewStateCoordinator.neutralizeForMetering()` 又清空 requestedSelection。临时中性化与用户关闭预览混在一起。`CameraController.applyRequestedExposurePreview():3034` 在 baseline/calibration 期间直接返回，需明确后续恢复应用触发。

`ExposurePreviewMath.manualExposure()` 现有实现已经优先维持较短快门并提高 ISO；不要把“新增短快门策略”当作现成修复，先检查当前实现和 `ExposurePreviewMathTest.kt`。

### 6.2 修改方法

- `ExposurePreviewStateCoordinator.kt`：分开 desiredSelection、appliedExposure、temporarySuspensionReason。用户 OFF 才清除目标；测光暂时挂起保留目标，新的有效 reading/转盘更新替换目标。
- `MainActivity.updateExposurePreviewFromMeter()`：不要以 busy=false 为唯一“是否还需要预览”的依据。可传用户目标与临时挂起状态，或由控制器持有目标；只保留一处权威来源。
- `CameraController.updateExposurePreview():891`、`applyRequestedExposurePreview()`：相机线程统一应用；新增 preview target revision，队列合并只保留最新目标。相同量化后的手动参数/补偿保持现有去重，避免每帧无意义 setRepeatingRequest。
- 会话恢复完成、基线终结、辅助预览校准退出时显式重新评估待应用目标，不依赖偶然的 onCameraInfo/onDistanceMeasurementState 回调。
- 匹配失败、参数被钳制或不支持应只影响预览状态。仍使用原 reading，不回写测光校准、不改主转盘、不自动降级 RAW。
- 用户在恢复中关闭预览、改曝光、切镜头、退后台时，以最后用户意图和当前代次为准；旧目标/旧 session 的回调不能重新点亮预览或改新镜头参数。

可新增 `ExposurePreviewApplyCoordinator.kt` 承担队列、代次、状态确认，原 `ExposurePreviewStateCoordinator` 保留参数计算，避免 CameraController 再次膨胀。不要引入无界重试或为每个目标创建后台线程。

### 6.3 不能把“请求发出”叫“画面匹配完成”

在 `CameraPreviewRequestCoordinator.kt`、`PreviewBaselineState.kt` 的 request tag 中扩展可选 target revision / session identity，保证已有 baseline generation 功能兼容。`CameraController.previewCaptureCallback` 检查对应 total result：

- 手动路径：确认 AE 模式和实际 time/ISO 与本次目标相符，允许设备支持范围内的量化/钳制，并记录原因。
- 补偿路径：确认目标补偿已到达；收敛状态用于说明预览状态，不阻塞已完成的测光结果。
- `onSurfaceTextureUpdated():2366` 可记录纹理消费的帧标记。使用同一实际输出对应的时间戳/帧身份关联，不能把任意一帧更新当成本目标，也不能把它叫精确屏幕扫描呈现时间。
- 无法可靠关联时报告 `appliedResultConfirmed` 与 `textureUpdateUncorrelated`，明确未知；不要用估计延时伪造 matched。

## 7. 修改 D：中性 AE 等待优化，先可观测再选择快路径

### 7.1 先补的埋点

建议新增 `MeteringLatencyTrace.kt`，以单次 measurement ID 关联 Normal、Zone 单点和 batch。统一使用一种主机单调时钟计算本地耗时；传感器 timestamp 只按其 clock domain 做帧关联。

| 事件 | 位置 | 关键字段 |
| --- | --- | --- |
| requestAccepted | 测光入口 | operationId、模式、route、plan、镜头身份 |
| neutralRequestSubmitted | `CameraPreviewRequestCoordinator` | sessionRevision、baselineGeneration、requestSequence、AE 模式/补偿 |
| firstTaggedResult / baselineDecision | `MeteringPreviewBaselineCoordinator.onCaptureResult` | 标签是否符合、逻辑/物理来源、AE state、time/ISO、稳定计数、结束原因 |
| rawSessionConfigureBegin/End | `CameraSessionCoordinator` / Zone 事务 | session 身份、output profile、stream size，不混入基线等待 |
| rawFirstPair / analysisDone / resultPosted | `RawLightMeter` / controller callback | frameCount、timestamp pairing、处理时间、匹配/跳过数量 |
| resultUiAccepted / spinnerOffDrawn | Activity / View | operationId、线程、结果接受与绘制时刻 |
| restoreDone / previewTargetSubmitted / targetResultSeen / textureSeen | 恢复与曝光预览协调器 | targetRevision、session 身份、匹配状态 |

仅 debug 或诊断开关下记录细节；限制逐帧日志量及内存环形缓冲大小，不写照片、定位、用户记录。每次结果汇总一条阶段耗时，避免日志本身影响性能。

### 7.2 基线策略文件与现状

- `MeteringPreviewBaselineCoordinator.kt`：现在只识别本次 tag；0 补偿、非 AE_OFF 且 AE 为 null/CONVERGED/FLASH_REQUIRED/LOCKED，连续 2 次才放行，最多 1200 ms。
- `PreviewBaselineState.kt`：已有 cameraGeneration + neutralBaselineGeneration，没有会话身份；按实际身份扩展，但不能令合法隔离事务永久无法通过。
- `CameraController.prepareMeteringPreviewBaseline():3039`：只有中性化需要改变曝光状态时才等待；`startMeteringPlan():1203` 在临时 RAW 切换之前等待。
- `RawLightMeter.buildCaptureRequest():232`：手动 RAW 曝光取自 latestResult 的 time/ISO；使用 RAW 自身最小帧时长，不能直接拿预览帧时长替换。删等待会改变捕获曝光来源，因此不是纯 UI 修改。

先通过日志区分：请求未到达、结果不属于当前代次、物理元数据缺失、AE 长期 SEARCHING、曝光参数实际仍变化、曝光参数稳定但 AE 标志迟到。现有日志尚不能确定 vivo HAL 内部是哪一种。

### 7.3 第一阶段可做的安全优化

1. 消除重复中性化和重复 start：同一测量只启动一次 baseline；重入应取消旧 timeout，禁止后续 continuation 双执行。退出前后台或切镜头后旧 continuation 不启动新 RAW。
2. 若已有符合当前 route/configuration 的实时中性预览，不再人为增加额外等待；现有 isNeutral 快路径已存在，新增工作重点是证明它不接受“软件状态中性但旧结果仍手动”的情况。
3. 将“标签/实际请求到达”和“曝光足够稳定”分开。若实测 AE 状态不及时，可试验“同身份、零补偿请求已到达且连续曝光参数稳定”的替代结束条件。稳定程度、样本数必须通过不同亮度/闪烁光源测试确定，不在本方案写死未经验证的阈值。
4. 保留 bounded timeout 和失败原因。不要把 UNKNOWN/null 状态当作永久等待，也不要扩散为“任何 result 都立即通过”。超时继续测量时保留诊断/质量信息，仍执行既有 RAW 质量闭环，不修改 RAW 可用性。

### 7.4 第二阶段：条件式跳过手动预览 → 中性 AE 往返

只有第一阶段证据与曝光正确性验证齐备后再实现。建议提取纯策略 `MeteringBaselinePolicy` 或 `RawMeteringExposurePolicy`，不要散落多个品牌 if。

候选方案：已验证可手动控制、元数据完整的 RAW 路径，用明确冻结的 capture exposure 直接建立 RAW 请求，避免为了得到曝光参数而每次恢复预览 AE；其他路径仍走现有中性 AE。此前曝光参数可作为起点，但不能无条件复用旧场景参数。

必须具备：当前实际镜头/传感器模式/裁切身份一致；曝光样本新鲜且可验证；用于分析的是与 RAW 图像正确配对的实际 capture result，而非仅请求值或旧 preview result；t、ISO、光圈、黑白电平等关键字段可靠；当前信号与噪声适于测量。

失败时回到“本次测量中性基线/受控同源重采”的慢路径，不是降级到 YUV，也不清已确认组合。快速策略关闭后必须完全回到当前基线。

不要在第一版对校准、无手动传感器、元数据不全的逻辑多摄开启该路径。曝光预览强烈欠曝/过曝、突然遮挡/移开、暗场、高 ISO、LED 闪烁、换镜头均需证明测量偏差和重复性未恶化。缓存中性 AE 样本仅靠 TTL 不足以证明场景未变，不能以旧样本直接替代当前测量。

该候选不是已被真机验证的结论，不能承诺消除全部 0.86 秒，也不能为赶进度默认开启。用功能开关限定已验证路径，优化判据不改变组合确认和降级规则。

### 7.5 Zone 性能的后续顺序

本轮 RAW 处理约 0.39 秒（3 帧），恢复约 0.20 秒。先解决 AE 往返，再评估会话切换是否还有不必要的重复工作。维持既有 ImageReader 生命周期和隔离边界，不额外常驻冲突流。

匹配分析只在基准表明占比明显时优化：同一 RAW 共享预处理、按目标 ROI 处理、限制候选规模与分配；不得跳过逐点身份检查，也不要将 Camera2 session 操作搬到工作池。若将重计算移出 camera handler，明确 Image 所有权、关闭时机、取消与有界队列，防止 maxImages 耗尽造成新的卡住。

## 8. 修改 E：AF 延迟释放绑定当前会话

文件：`CameraDistanceCaptureCoordinator.kt` 的 `triggerAutoFocus()`、`scheduleAutoFocusRelease():135`、`stop()/invalidate()`；以及 `CameraSessionCoordinator.kt` 的 reconfigure、invalidateCurrentSession、close 接线。

当前只检查 cameraGeneration，而隔离会话可在同一 generation 内更换。`CameraSessionCoordinator` **已经存在** `sessionRevision` 和配置回调过滤，优先复用/暴露受控 session token，不新建互不关联的第二套会话代次。

修改步骤：

1. 保存 pending release runnable，新的 AF 触发先取消旧任务；stop/invalidate/会话关闭前取消并置空。
2. 执行时检查 camera generation、session identity/token、device identity、Surface 有效性；不符时丢弃，不能对新 session 发送旧触发的 CANCEL。
3. 在同 camera generation 的 reconfigure 前使旧 token 失效。取消回调不是唯一防线：runnable 已出队时仍需身份校验。
4. 保留异常处理处理不可避免的关闭竞态；诊断可记录受控取消，但不以屏蔽异常代替生命周期修复。
5. 只取消 AF 触发释放，不改自动测距资格：CALIBRATED/APPROXIMATE 才允许米制自动距离，不能为了绕过警告关闭现有距离功能。

建议新增 `CameraDistanceCaptureLifecycleTest.kt`，使用可控调度器覆盖同代换 session、600 ms 前退出、快速连续 AF、取消后任务已入队、新 session 不受旧回调影响。设备复验应连续 Normal/Zone/镜头切换，不再出现旧会话 release 警告。

## 9. 与已有修改冲突最容易发生的地方

- `MainActivity.kt`、`MeterModels.kt`、`MeterLayout.kt`、`CameraController.kt`、`RawLightMeter.kt` 已有未提交修改；逐函数应用，不用旧分支整文件覆盖。
- 当前 `RawBurstProgressPolicy` 已控制基础帧数和补帧上限；改 batch 跳过逻辑时区分“点无资格”与“本次 RAW 还需要采样”，防止全部跳过导致捕获循环。
- `CameraWorkflowConfirmation` / 组合缓存迁移已能在本机恢复 RAW。不要为了测试再清缓存或每次启动重探测。确认身份不足的补强必须保留已有 origin/迁移含义。
- `PreviewHealthRecoveryBudget` / recovery policy 已加入有限恢复；新预览匹配失败不能重新开启无限重试或绕过确认保护。
- `MeteringFusion` 的校准样本处理已有修改。快路径不得绕开实际曝光元数据检查；无效样本继续保存的边界仍需单独修复。
- 参数记录 RAW 偏好、保存事务、无障碍已有独立修复工作。UI 忙状态变化要复查这些入口，不能因提前停圈允许资源冲突或丢失用户选择。

尚未关闭的发布边界：`MeteringCalibrationRun.accept()` → `CameraCalibrationStore.updatedStream()` 的无效样本回落旧公式；目录镜头 ID 与实际 HAL 路由/配置的确认作用域；手动探测过期成功回调；`ParameterRecordToolView.resumePage()` 因瞬态 rawAvailable=false 写掉 recordRaw 偏好。按测试记录第 6 节及旧交接文档逐项复查，不宣称本次性能方案自动解决它们。

## 10. 厂商 / Android 版本兼容边界

本轮仅测试 vivo V2405A / Android 16，不能推广到所有 vivo、三星、小米、OPPO、Pixel 或所有 Android 16。没有证据支持为某品牌统一写短超时。后续按能力和实际结果选策略，并记录机型、系统构建、硬件级别、RAW/manualSensor 支持、实际路由和流配置。

| 验证维度 | 风险 | 预期处理 |
| --- | --- | --- |
| RAW + MANUAL_SENSOR，直接相机 / 固定物理镜头 | 预览与 RAW 模式不同、请求量化 | 按实际 RAW result 分析；保留 RAW 最小帧时长 |
| RAW 但缺少可靠手动控制/结果字段 | 无法安全构造快路径 | 保留 AE 路径；不推断缺失元数据 |
| 逻辑多摄自动切物理镜头 | 缓存曝光/参考来自旧传感器 | 使对应快路径和目标身份过期；不重置用户逻辑镜头选择 |
| AE 长期 SEARCHING 或 null | 标志与实际稳定程度不一致 | 记录原因，走验证过的稳定策略或有界慢路径 |
| 不同 Android API | 新 metadata API 不一定存在 | API guard；不提高 minSdk 来规避兼容 |
| 请求排队较深 / 帧率较低 | request 提交不等于已生效 | 按本次结果确认；不靠固定延时猜测 |

官方资料核对（2026-09-22）：

- [CaptureRequest](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#CONTROL_AE_MODE)：AE 自动/手动会改变参数控制权；AE_OFF 时 AF/AWB 行为存在设备差异。因此不能只改 AE mode 就假定其他 3A 状态完全保持。此处是优化的兼容风险，不是已确认的 vivo 根因。
- [CameraCharacteristics](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#SYNC_MAX_LATENCY)：请求同步延迟和管线深度需结合设备能力理解，不等同于 AE 收敛时长；不要把 SYNC_MAX_LATENCY 当成所有测光等待的固定上限。
- [TotalCaptureResult](https://developer.android.com/reference/android/hardware/camera2/TotalCaptureResult#getPhysicalCameraTotalResults())：物理 total result API 有版本要求（API 31），逻辑与物理结果来源需区分；较低 API 保留项目已有兼容入口，不无条件调用新方法。
- [CaptureResult 时间戳](https://developer.android.com/reference/android/hardware/camera2/CaptureResult#SENSOR_TIMESTAMP)：传感器时间戳及其来源决定能否跨子系统比较。计时不要直接把 System.nanoTime 与未知时钟源的 sensor timestamp 相减。

这些资料约束通用 Camera2 用法，不证明任何未测厂商一定兼容。后续至少增加另一厂商设备，并覆盖直接镜头/固定物理/逻辑自动、明暗变化和前后台恢复。

## 11. 测试与验收清单

### 11.1 自动化

现有测试继续全跑；新增建议 `MeteringUiProgressPolicyTest.kt`、`ExposurePreviewApplyCoordinatorTest.kt`，扩展 `PreviewBaselinePolicyTest.kt`、`ExposurePreviewMathTest.kt`、`ZoneRawTransactionCoordinatorTest.kt`。

- 结果已交付而 restorePending=true：无测光圈、保留业务锁、结果不消失。
- restore 回调先后变化、重复、旧测量迟到：只改变当前操作；不重复结果、不错误解除新锁。
- 预览 target A→B→C 在忙时只应用 C；关闭/切镜头/退后台后不应用旧目标。
- 预览结果确认超时：有效 EV 留存，测光圈不重启，RAW 确认不清除。
- baseline 的旧 tag、旧 session、AE_OFF、非零补偿、null metadata、超时和取消：行为有界、continuation 最多一次。
- 快路径关、元数据缺失、换 route、暗场失败：回到同源安全慢路径；不能换成 YUV。
- batch 全部跳过、部分跳过、高 ISO 补帧与恢复：共享捕获、有界完成、旧点不变。
- 检查触摸和无障碍均遵守资源锁，不能只验证绘制。

建议命令（仓库根目录）：

```powershell
.\gradlew.bat testDebugUnitTest --rerun-tasks
.\gradlew.bat lintDebug assembleDebug
git diff --check
```

仅纯策略单测通过不足以验收 Android Handler、session、UI 回调接线，必须做设备集成回放。

### 11.2 设备回归

先核对签名和包名，用 `adb install -r` 保留数据覆盖安装，不卸载、不清校准。安装确认由用户处理。正常数据不可用测试点覆盖。

1. 同镜头、稳定场景 Normal OFF/ON 各至少 10 次；Zone OFF/ON 各至少 10 次。单列切换后的第一笔；记录每笔分段时间，再算中位数 / P95。当前每组 3 次只是定位基线，不适合发布级分位数结论。
2. 明亮、暗场、遮住/移开、预览明显 ±EV、LED 灯；记录帧数、ISO、信噪、EV 与相同条件 OFF 参考的差异。先建立基线重复性，再定允许偏差，不添加补偿掩盖差异。
3. Normal→Zone→设置→返回、前后台、主摄/超广/长焦、逻辑镜头实际切换；保留模式镜头及已确认 RAW。
4. Zone 三个以上临时点：全部在画面内、一个移出画面、低纹理、无参考；全部重测共享 RAW，未匹配点明确未更新，旧值不变。
5. 结果后恢复期间快速再次点击、改转盘、关闭预览、退后台；不重复捕获、不旧目标覆盖、不无限圈、不旧 session AF。

用户继续负责界面观感，不要求截图。重点用日志验证阶段和结果，必要时让用户报告“EV 出现、圈停止、画面调整”的顺序。

### 11.3 验收标准

- **确定性要求**：有效结果已进入 UI 后，下一次可绘制的 UI 帧不再显示测光圈；预览恢复/匹配不重新开启该圈。资源锁仍准确，失败和取消有限结束。
- **正确性要求**：不误更新旧点、不丢失偏好/校准、不自动降级已确认 RAW、不改变 HAL 隔离策略；测光重复性不劣于同条件原路径。
- **性能要求**：分别报告基线等待、捕获、分析、结果显示、会话恢复、预览结果生效时间。若只完成状态拆分但前置等待没改善，应明确写“反馈改善，捕获前耗时仍待优化”。
- **探索目标而非承诺**：验证过的快路径应显著减少本机约 0.86–1.20 秒的额外等待；是否能接近 OFF 速度以正确性数据为前提，不设置迫使程序绕过检查的硬性统一毫秒值。

## 12. 给实施 agent 的最终交付要求

1. 列出实际改动文件、入口函数、状态迁移和新增测试；说明哪些只是诊断、哪些改变了采样策略。
2. 对照当前未提交 diff 说明保留了哪些既有修复；不要把旧问题重新引入。
3. 附新旧 APK hash、安装方式、设备构建、分段日志表、功能开关状态；记录仍未验证的厂商/路径。
4. 快路径若证据不足，保持关闭并交付已验收的正确性/状态分离修复；不要将未经验证优化混进默认发布行为。
5. 文档与日志不进 res/assets。用户未要求时不推送、不创建 Release、不改版本历史。没有通过故障和跨设备测试，不能把“正常路径无降级”写成全场景已修复。
