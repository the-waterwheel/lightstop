# 手动 RAW 重验实现复审、实机验证与剩余修复交接

日期：2026-09-23。本轮审阅用户完成但尚未提交的修改；按既有授权补三个局部小问题，覆盖安装后由用户操作、测试方核对日志。本文与实现一起提交，**属于阶段性保存，不是 F1/F2 全部完成的发布验收**。

## 1. 结论与版本边界

- 原基线为 `fd73329`，本轮包含此前未提交的 routeIdentity/系统缓存重验/暂停忙状态修复，以及新手动重验协调器和 UI 接线。
- 新增协调器和不可变 evidence 有实质价值；真实设备上的旧 MANUAL RAW 迁移、正常测光及一般前后台路径已通过。
- **仍有接受/恢复时序、失败资格保护、存储升级语义等缺口，见第 4 节。** 不能因成功路径通过就宣称所有异常场景或跨厂商兼容性通过。
- APK SHA-256：`f40e85337a49426e5ee2d928e15f52f5c1e584288cc99a4b9d364e3b7632cb67`，已与手机实际 base.apk 的 sha256sum 核对一致。
- 设备：vivo V2405A / PD2405，Android 16；包名 `com.lightmeter.rawmeter`；本轮应用 PID=5323。
- `adb install -r` 返回 Success。没有卸载、清应用数据、人工改偏好、保存校准或删除正式点；本轮人工接受将主摄的旧组合缓存正常迁移，这是测试所预期的业务写入。
- `testDebugUnitTest lintDebug assembleDebug` 成功；80 个测试套件、378 项测试、0 失败/错误；Lint 0 错误、65 警告；`git diff --check` 通过。

本文替代旧报告中“旧手动 RAW 只有日志”的当前状态描述；旧报告的历史测试数据仍有效，但不可与本轮数据合并。完整目标规格见 [原第 4 节](DEVICE_REVIEW_WORKTREE_AND_UPGRADE_2026_09_23_ZH.md#4-必须继续交给实现模型的修改)。

## 2. 本轮审阅方直接补的三个小修

以下是在用户/实现模型改动上的局部补丁，不是重新实现整个方案。

### S1：补全手动 RAW 缓存重验判据

修改文件：`ManualCombinationRevalidationPolicy.kt`、`CameraController.kt`、`CameraCombinationSelectionMigrationTest.kt`。

原判据仍只有 origin=MANUAL；MANUAL_VERIFIED 但缺 route/路径变化会失去 RAW 确认却不重验。现改为：有 RAW 手动记录时，只有 MANUAL_VERIFIED 且实际已解析路径匹配才免重验。无记录、非 RAW 不强行进入此流程。

增加当前实际路径参数与测试，覆盖缺 route、路径 A/B、未解析实际路径、LEGACY_AUTO 来源、空记录及非 RAW。注意：这只处理**已经成功读出的记录**，不解决 fingerprint/schema 被读取层过滤的问题。

### S2：旧失败回调不得破坏新探测

修改文件：`ManualCombinationVerificationCoordinator.kt`、`CameraController.kt`、`ManualCombinationVerificationCoordinatorTest.kt`。

原完成回调在 evidence=null 时调用无条件 fail()，旧 probe 完成通知迟到时可能把新的 PROBING/AWAITING_ACCEPT 清为 FAILED。新增 failProbe(probeId)，仅当失败仍属于当前 PROBING 时才处理；控制器的异步完成使用该入口。

回归测试覆盖：probe A 过期，probe B 正在运行及已成功待接受时，A 的失败都不破坏 B。主动取消仍使用现有 fail()；完整取消/生命周期语义仍见 R2。

### S3：Zone 中发起完整探测使用探测自身的首阶段

修改文件：`CameraController.kt` 的 openCamera profile 选择。

原代码始终按 trackingFramesEnabled 选择 Zone/Normal resident；用户在 Zone 发起探测时，Runner 首阶段要求 normalResident，两者可能不一致而报 Unexpected probe profile。现有 active probe 时优先使用其 currentStage；普通非探测打开仍按用户模式选 profile，不修改 UI 模式。

本轮普通 Zone 测光通过；**本轮没有在 Zone 中重新发起一次新的组合探测**，因此 S3 只有代码路径审阅和构建证据，不宣称已经独立真机复现修复前后对比。

## 3. 真机结果与日志解释

### 3.1 旧选择已经完成迁移

覆盖安装前读取组合偏好：

```text
combination_bc22_plan = raw_split_v1
combination_bc22_origin = MANUAL
combination_bc22_route = 缺失
```

12:57:25.422 与 12:57:25.532 出现重验告警，随后出现 RAW_ONLY、COMPATIBLE 和 RAW 阶段的会话切换；未出现 CombinationProbe failure。用户操作后再次读取：

```text
combination_bc22_plan = raw_split_v1
combination_bc22_origin = MANUAL_VERIFIED
combination_bc22_route = 0|2|FIXED_PHYSICAL|1440|1080|4096|3072|640|480
```

用户按“等待重验、画面正常后接受、Normal/Zone 临时测光、回桌面再打开”的步骤反馈“正常”。12:58:37.126 重开时仍选择 raw_split_v1，没有再次出现旧手动重验告警；其后有新的 RAW 捕获完成。

限定：现有日志还缺少统一的 probeId/route_bound/accept_completed 事件，所以这里由代码、会话日志、偏好迁移和用户反馈共同支持“普通迁移流程通过”，不是完整逐事件证据链验收。

### 3.2 捕获与恢复计数

采集窗口：12:57:25.127–12:58:56.968，同一 PID=5323；按应用标签过滤，不是整台设备所有进程的故障统计。

| 指标 | 结果 |
| --- | ---: |
| RAW metering completed | 20 |
| RAW metering failed | 0 |
| Camera combination probe failed | 0 |
| FATAL EXCEPTION | 0 |
| Zone RAW latency 完成记录 | 11，均 resultDelivered=true |
| 中性 AE 基线完成 | 17，其中 3 次等待 1200 ms 超时 |

计数是窗口内捕获次数，不等同于最初要求用户执行的最小操作次数。没有注入 HAL 错误，也没有验证取消/失败恢复及精确竞态窗口。

**不要把 Zone 的 profile=COMPATIBLE、rawSession=false 当作自动降级。** 本方案的 Zone resident 使用 YUV 跟踪，测光时切到隔离 RAW；12:58:37.127 是 COMPATIBLE resident，后续 12:58:39.885 等仍有 RAW metering completed。判断降级必须结合 plan 和实际测量来源。

### 3.3 延迟尚未解决

| Zone 结果所在时段 | 点击到结果 | 恢复耗时 | 点击到可用 |
| --- | ---: | ---: | ---: |
| 12:58:24 | 408 ms | 214 ms | 623 ms |
| 12:58:27 | 1270 ms | 199 ms | 1470 ms |
| 12:58:32 | 402 ms | 204 ms | 607 ms |
| 12:58:40，重开后 | 1273 ms | 202 ms | 1475 ms |
| 12:58:54 | 1091 ms | 201 ms | 1293 ms |

这不是受控同场景 A/B；不能将差异归因于这次资格修复，更不能据此宣称 EV 精度改善。C/D 的可靠时序埋点与性能优化仍待完成。

## 4. 剩余审阅发现：按优先级修复

生产文件路径前缀为 `app/src/main/java/com/lightmeter/rawmeter/`。以下是代码审阅确认的缺口及可触发条件，不声称本轮真机已复现所有竞态。

### R1 / P1：接受仍同步执行，而且在会话恢复前就解除忙状态

定位：`CameraController.acceptManualCombination`（约 774–816 行）、`ManualCombinationVerificationCoordinator.beginApply/confirm`、`MainActivity.acceptCurrentManualCombination`。

当前调用来自主线程。beginApply 的锁仅保护协调器方法，不覆盖随后对 activeCombinationPlan、rawWorkflowConfirmation、cameraInfo/存储的修改。代码调用 confirm 后才 post 切 resident，会立刻返回 true；UI 随即关闭选择器，blocksRawMetering 已为 false。Zone 接受后到 resident 配置完成之间可以再次操作相机，APPLYING 没有覆盖实际恢复阶段。

同时 completedCameraGeneration 目前只记录，不参与接受校验；保存仍以 cameraInfo.cameraId 为键，而不是凭证中的 selectionCameraId。完整路径在最后 completion 才从当前配置读取，没有在本轮实际配置解析后绑定并贯穿阶段核对。

修复要求：

1. 接受改成异步结果接口，携带 probeId；Boolean 如保留只表示成功入队。
2. 在单一相机线程串行完成“核对 owner/probe/plan/selection/route/generation → 保存 → 消费 evidence”，不能把单个 @Synchronized 当成整个相机事务同步。
3. 实际路径解析完成时绑定本轮 route；内部有意重开允许继续同一 probe，外部路径变化/重开使旧 evidence 失效。不要 begin 前就冻结错误的相机 generation。
4. 保存使用 evidence.selectionCameraId 与 evidence.routeIdentity。资格已接受和 resident 已就绪分开表达；恢复期间仍拒绝新相机操作，直到当前代次 session 成功配置。
5. post 失败、Handler 不存在、停止与恢复失败均给出终结结果。保存已成功但恢复失败不自动撤销为 YUV，而是提示同组合恢复失败。
6. 加可控队列测试：成功待接受后切路径、接受与 close 交错、Zone 接受后立即点击、重复接受；明确断言保存键、保存次数、会话恢复前捕获被拒。

该问题跨线程和会话回调接线，不能仅增加 @Volatile 或单个 generation 比较作为完成修复。

### R2 / P1：失败保护与捕获入口还没有统一，生命周期完成可能丢失

定位：`ManualCombinationVerificationCoordinator.fail/invalidateExternal/blocksRawMetering`；`CameraController.measure/measureZoneBatch/captureRawRecord/estimateColorTemperature/beginExposurePreviewCalibration`；`probeManualCombination` 完成回调。

fail() 会转为 FAILED，而 blocksRawMetering 不包含 FAILED。探测失败通知到 UI 关闭/重新打开会话之间，或其他失败后未重读缓存的路径，可能放行未确认的工作流。仅给 FAILED 全局加阻塞也不正确：外部换到有效镜头时 invalidateExternal 同样产生 FAILED，必须区分“当前选择仍需验证”与“旧操作已取消”。

普通 measure/批量只在入队前检查；相机线程执行时未复核。RAW 记录、色温与曝光预览校准没有统一使用新资格门禁。Runner 完成目前经主线程再 cameraHandler?.post；Handler 消失时不会回送 completion，UI 可能停留在未终结状态。

修复要求：

1. 将当前选择的资格与一次探测操作状态分开；失败/取消不自动授予捕获资格，但切到另一个有效选择也不能被旧失败永久拦住。
2. 控制器提供带拒绝原因的统一门禁；所有占用会话的入口在调度前、实际执行前检查，拒绝时正确回滚 Zone 临时点和忙标记。
3. 重验失败停留原 plan，提供明确重试/暂不验证入口；不只是自动关闭弹窗后让用户看到“请等待”。继续保留用户镜头/模式，不自动 SYSTEM/YUV。
4. 所有完成/取消事件有所有者代次，生命周期清理先失效后清资源。S2 只修复旧异步失败清掉新探测，不代表全部生命周期已经隔离。
5. 补测试：FAILED 后各捕获入口被拒、换到有效选择可恢复、Handler 停止前后 completion 恰好一次、暂停恢复后旧回调不改变新资格。

### R3 / P2：升级失效/候选消失仍被解释为恢复系统模式

定位：`CameraCombinationSelectionStore.readSelection`（约 100–114 行）与 `CameraController.openCamera`（约 2509–2527 行）。

fingerprint/schema 不符直接返回 null；原 plan 不在矩阵候选中又被 takeIf 过滤。手动模式遇到这些 null 时仍自动设置 SYSTEM 并通知 UI。S1 已修复“读得出来但路径不匹配”，没有修复“读入时就被抛弃”的选择意图丢失。

修复要求：增加 Missing / Valid / NeedsRevalidation / UnsupportedRecord 等读取结果；保留可安全识别的旧选择作为未验证意图。环境变化只撤销资格，不静默改用户模式。未知未来 schema 不强行解读或覆盖，提示主动选择。增加存储 round-trip 和 open 协调测试，不能只增加纯 Boolean 断言。

### R4 / P2：重验提示没有所有者身份，也没有明确的主动开始/重试阶段

定位：`CameraControllerCallback.onManualCombinationRevalidationNeeded`、`CameraController.openCamera` 投递通知、`MainActivity.onManualCombinationRevalidationNeeded/showNextManualCombination`。

通知只传 planId。旧镜头的通知排队后用户切到新镜头，两者恰好都有 raw_split_v1 时，UI 只按当前候选查找，可能开启不属于原通知的探测。当前通知直接 showNext 启动探测，没有原规格中的“保留选择，等待用户开始重验”；失败则递增唯一候选索引并关闭，而不是在原 plan 提供重试。

修复要求：通知携带 selectionCameraId、ownerEpoch、原因与稳定状态；主线程检查前台和当前身份，用户确认开始后再 probe。取消保持待验证意图但不立即重新弹窗；原 plan 失败可主动重试。补旧通知跨镜头、取消后点测、后台丢弃通知后重新进入的协调测试。

## 5. 测试与实现交接顺序

1. 先实现 R1/R2，补真实协调时序测试；它们关系到捕获资格与会话互斥，优先于性能调参。
2. 实现 R3/R4，补存储升级与 UI 身份测试；复用现有 HAL 隔离和 Session Runner，不合并会话。
3. 原第 4 节的 T01–T18 并没有全部实现。目前新协调器只有 4 个测试；378 是项目总数，不能替代这些指定场景的覆盖证据。
4. 对已确认 RAW 的运行失败，补“仅同组合恢复，不自动降级”的故障注入；对 restore=true→pause→resume 补可控回调跨代测试。本轮一般前后台成功不代表精确窗口通过。
5. 当前主摄旧缓存已经正常迁移。后续缺 route/旧 MANUAL 的失败分支优先用测试夹具或专用设备，不擅自回写用户手机偏好来复现。
6. 补有限诊断事件 probe_started/route_bound/probe_completed/accept_rejected/selection_accepted/restore_completed，包含 probeId、选择、实际路径和代次；不增加逐帧大日志。
7. C/D 独立执行；不通过削减 RAW 必要检查/帧数、合并 HAL 会话或全局 EV 修正来制造提速。

## 6. Git 与发布边界

本轮按用户授权提交当前实现、局部修复、测试和审阅文档，保留既有历史；不修改版本号，不生成 Release，不推送远端。提交是可追踪的阶段性检查点，R1/R2 等未完成项仍为后续交付阻塞，不能标为“完整修复并发布”。

测试日志摘录见 [本轮日志附件](REVIEW_MANUAL_REVALIDATION_DEVICE_LOG_2026_09_23.txt)，只含选定应用标签；不包含完整设备日志、校准文件或照片。文档位于 docs，未放入 app/src/main/assets 或 res。
