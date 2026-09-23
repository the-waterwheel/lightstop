# 当前工作区复审与保留数据升级测试

日期：2026-09-23。第 1–6 节记录先完成的构建、覆盖安装、设备日志和审阅，当时未修改源码。随后用户授权百行内小修，已执行的有限补丁及其独立验证见第 7 节。

## 1. 精确版本与测试边界

- 基础提交：`fd73329`，加当前未提交修改，不等于单独检出该提交。
- 修改文件：CameraCombinationSelectionStore、CameraController、MainActivity、CameraCombinationSelectionMigrationTest；新增 CombinationRouteMatchPolicy、ManualCombinationRevalidationPolicy。
- 设备：vivo V2405A / PD2405，Android 16；包名 `com.lightmeter.rawmeter`。
- 新 APK SHA-256：`de043fa08449b303a10491da226614e4aa0bf786a6971e92e7a903a32b221662`。已与设备实际 base.apk 核对一致。
- 覆盖前 APK SHA-256：`a9a9f3ca087e2eb065b01f2447786c8abc1c8fca7e979ce45e203a684a4a0759`。
- 新旧 APK 证书 SHA-256 一致：`fb5c582ea2a5fb92f63eb467c35edae9257d6eccb3ff44b05f3b3be8ca557b7e`。
- 已通过 `adb install -r` 保留数据安装；没有卸载、清数据、修改组合选择或写校准。
- 构建、Lint 成功；Lint 0 错误、65 警告。额外强制重跑单测：79 个套件、372 项、0 失败/错误。`git diff --check` 通过。
- 用户配合操作 Normal/Zone 点测和回桌面再进入。没有截图；本轮未测试正式校准写入、参数保存或破坏性故障注入。

## 2. 改动审阅结论

| 项目 | 当前状态 | 验收限制 |
| --- | --- | --- |
| onPause 清理新增资源忙标记 | 已补；后台恢复回调也清理 View 状态 | 正常前后台后可继续点测，但没有精确命中短恢复窗口的证据 |
| 持久化探测时 routeIdentity | 已补读写，确认恢复前比较身份 | 真机当前为旧手动缓存，尚未验证 A 路径写入/B 路径读取全过程 |
| 旧手动 RAW 缓存重新验证 | **未完成，仅打印日志** | 本轮真机确认旧缓存没有迁移，仍没有验证凭证 |
| 手动探测成功凭证 | 仍仅为 planId | 没有补代次、实际路径与异步接线测试 |
| 曝光预览前置 AE 等待 | 未修改 | 本轮再次出现 1.2 秒超时，不能宣称提速完成 |

其中 routeIdentity 持久化与比较是实质改进，不应回退；忙标记正常暂停清理也比上一版完整。剩余问题主要在“识别需要重新验证”之后没有实际状态迁移。

## 3. 真机证据

### 3.1 旧手动缓存：启动只警告，不验证

覆盖前、启动后读取同一组合偏好，主摄仍为：

```text
combination_bc22_plan = raw_split_v1
combination_bc22_origin = MANUAL
combination_bc22_route = 缺失
```

新进程 24529 中：

```text
11:28:17.771 Legacy manual RAW selection needs re-verification route=0@2 plan=raw_split_v1
11:28:17.771 Stream matrix candidates mode=AUTO selected=raw_split_v1 ...
11:28:35.882 Legacy manual RAW selection needs re-verification route=0@2 plan=raw_split_v1
11:29:27.815 Legacy manual RAW selection needs re-verification route=0@2 plan=raw_split_v1
```

该路径继续测 RAW，但源代码不会授予确认锁，也没有启动手动重新验证。`ManualCombinationRevalidationPolicy` 返回 true 后只有 Log.w；随后 pendingSystemWorkflowProbePlanId 又因 manualPlanRequested=true 置空。

本轮没有人为制造 RAW 捕获失败，因此不宣称已经观察到 YUV 降级。能够确定的是：旧选择在缺乏确认保护的状态下继续运行，用户要求的“确认后不自动降级”在升级链上仍不完整。

### 3.2 捕获与前后台恢复

采集窗口约 11:28:17–11:30:01，同一应用进程 24529：

- 19 条 RAW metering completed，其中 13 条对应 Zone 隔离事务完成；所有这 13 条均 resultDelivered=true。
- 0 条 RAW metering failed，0 条 FATAL EXCEPTION，0 条 Unable to release the autofocus trigger。
- 11 次中性 AE 等待超时；这是采集窗口计数，不是长期故障率。
- 用户反馈返回应用后可以继续建点。11:29:27.815 重开之后，11:29:31.458 有新的 RAW 完成，后续多次 Zone 捕获也继续成功。

注意：原先 11:29:23.798 的恢复已完成，之后才出现前后台相关重开日志。此次足以支持“正常前后台后功能可用”，不足以证明 Home 恰好发生在 restore=true 至 restore=false 的短窗口内。不要把它标成精确竞态复现测试通过。

### 3.3 延迟仍在，不能归因于 RAW 本身变慢

代表性样本：

| RAW 结果时间 | 中性 AE | 点击到结果 | 恢复预览 | 点击到可用 |
| --- | --- | ---: | ---: | ---: |
| 11:28:31.815 | 未等待 | 399 ms | 73 ms | 473 ms |
| 11:28:35.022 | 等待 1200 ms 后超时 | 1830 ms | 203 ms | 2033 ms |
| 11:29:20.402 | 未等待 | 500 ms | 201 ms | 701 ms |
| 11:29:23.718 | 等待 1200 ms 后超时 | 1701 ms | 80 ms | 1782 ms |
| 11:29:31.458 | 等待 1201 ms 后超时 | 1603 ms | 200 ms | 1803 ms |

这不是严格同光源 A/B，帧数和场景不同；不可直接比较 EV 或据此宣称精度变化。它再次说明前置基线等待对总时延影响明显。仍缺 firstTaggedResult、AE 演变、UI 停圈绘制与目标曝光实际生效时刻，C 的埋点仍有必要。

## 4. 必须继续交给实现模型的修改

本节于 2026-09-23 展开为实施规格。**以下是待实现方案，不是已经修改或验收通过的功能。** F1 是旧手动选择的重验接线，F2 是成功凭证与异步顺序；两者应一起完成，否则容易出现“重验弹窗有了，但保存的并不是刚才验证过的路径”。

### 4.1 修改边界与执行顺序

1. 先阅读当前工作区差异，不以单独的 `fd73329` 覆盖已有修改。第 7 节的系统缓存重验补丁、routeIdentity 存储及比较、暂停时清理忙状态均须保留。
2. 先做 F2 的凭证与单线程状态提交，再接 F1 的缓存判定和重验入口，最后接 UI 和完整回归。不要先把旧 MANUAL 批量改成 MANUAL_VERIFIED。
3. 本节只完善组合资格与生命周期，不调整 AE 超时、RAW 帧数、曝光补偿、测光校准或预览几何。C/D 性能优化继续独立推进。
4. 不合并隔离会话/进程；不删除用户选择；不因升级重验切回 Normal、主摄或 SYSTEM；重验失败不得自动换到 YUV。
5. 本项涉及控制器、探测回调、存储与界面，预计不是百行内小修。按下列职责完成最小必要改动，不趁机重写整个 CameraController。

### 4.2 修改文件与落点清单

生产文件均位于 `app/src/main/java/com/lightmeter/rawmeter/`。以下以函数名定位，行号会随其他模型修改而变化。

| 文件 | 修改位置 | 要实现的内容 |
| --- | --- | --- |
| `ManualCombinationRevalidationPolicy.kt` | `needsRevalidation` | 增加实际路径与来源判断；覆盖旧 MANUAL、MANUAL_VERIFIED 缺路径/路径不符，不再只看 origin=MANUAL |
| `CameraController.kt` | `openCamera` 读取缓存、恢复确认、设置 pending probe 的分支 | 将“需要重验”接成真实状态；固定旧 plan，发出重验提示，阻止未确认捕获；保留系统分支小修 |
| `CameraController.kt` | `probeManualCombination`、`acceptManualCombination`、`cancelManualCombinationSelection` | 引入探测上下文与不可变凭证；先校验再修改/保存；区分新选择和旧选择重验的取消语义 |
| `CameraController.kt` | `onSessionConfigured` 接线、`resetRecoveryState`、相机关闭/暂停/换镜头路径 | 防止同一次重验重复启动；隔离过时代次回调；保留探测内部有意的 close/open 与会话切换 |
| `CameraCombinationWorkflowProbe.kt` | Runner 的 `begin`、`advance`、`fail` 和完成回调 | 在探测所属的相机线程完成资格提交，再投递 UI；继续复用现有真实输出阶段和轻量 RAW 检查 |
| `CameraCombinationSelectionStore.kt` | `selectedPlan`、`saveVerified`、读写结果 | 接收凭证里的选择键和路径，一次 edit 保存字段；区分无记录与已有但失效的选择，见 4.8 |
| `CameraControllerCallback.kt` | 组合状态回调 | 增加类型化的重验状态/原因通知，不能借用“回退系统模式”回调表达重验 |
| `MainActivity.kt` | 手动选择开始/下一项/接受/取消，以及暂停与回调失效处理 | 区分 NEW_SELECTION 与 REVALIDATE_SAVED；旧组合重验不自动跳下一候选、不取消后恢复 SYSTEM |
| 当前组合选择视图、测光交互状态所属文件 | `showCombinationSelection`、`updateCombinationProbeState` 的实现及 busy 消费处 | 展示重验/失败/待确认状态；等待用户确认不使用普通测光转圈；拒绝捕获时不遗留 Zone 临时点 |
| `app/src/test/java/com/lightmeter/rawmeter/` | 现有迁移测试及新增协调流程测试 | 不只测策略布尔值；覆盖探测、确认、保存、重开和跨代回调，见 4.10 |

若新增协调类，建议命名 `ManualCombinationVerificationCoordinator`，仅管理状态、上下文和事件；Camera2 会话仍由现有控制器/Runner 执行。此名称是建议，不是仓库中已有类。

### 4.3 F1：明确哪些旧选择需要重验

判定先确认“用户处于手动选择模式”，再判断旧 plan 是否仍在当前矩阵候选中、是否 RAW、来源及路径。建议返回原因枚举，而不只返回 Boolean，供日志、UI、测试复用。

| 输入 | 预期处理 |
| --- | --- |
| MANUAL RAW，无论旧记录是否带 route | 需要重验；旧来源只能证明用户选过，不能证明真实工作流已成功 |
| MANUAL_VERIFIED RAW，route 缺失 | 需要重验：MISSING_ROUTE |
| MANUAL_VERIFIED RAW，route 与本次实际配置不符 | 需要重验：ROUTE_CHANGED；不能授予确认锁 |
| MANUAL_VERIFIED RAW，route 匹配，环境/策略有效 | 恢复确认；正常重开不重复完整探测 |
| 手动槽存在旧 RAW 记录，但来源为 LEGACY_AUTO/未知来源 | 不能冒充已验证；保留 plan 作为重验候选，并记录来源不可信 |
| 原 plan 不在当前矩阵候选中 | 显示“原组合当前不可验证”，保留历史选择，允许用户主动重新选择；不自动恢复系统模式 |
| 手动选择为非 RAW | 保持既有非 RAW 使用语义；不授予 RAW 确认锁，不强制套用 RAW 迁移规则 |
| 真正没有任何手动选择记录 | 沿用首次选择流程，不虚构旧 plan |

当前 `confirmationRouteId()` 在没有 activeOpenConfiguration 时会退回 cameraInfo.cameraId。**这个兜底 ID 不得用于签发新探测凭证**：实际配置尚未解析时，应等待或报告尚未就绪。保留现有实际身份中的 cameraIdToOpen、physicalCameraId、route.kind 和各输出尺寸；不要只存逻辑镜头 ID。

不要把每帧 active physical camera 元数据变化都认定为用户换镜头或配置改变。凭证绑定已有稳定 HAL 配置身份，而不是未经区分的逐帧元数据；否则逻辑多摄会无意义地反复要求重验。

### 4.4 F1：推荐交互与状态迁移

采用“**保留旧选择 → 提示重验 → 用户开始同一 plan 的真实探测 → 用户确认画面 → 保存**”流程。不要在启动后默默按未确认路径测量，也不要未经用户确认就把旧记录升为已验证。

建议状态如下；命名可调整，但不能只用 Runner.isActive 表达整个过程：

| 状态 | 进入条件 | 可执行操作 / 退出条件 |
| --- | --- | --- |
| NEEDS_REVALIDATION | 发现旧选择缺少当前路径的有效资格 | 显示原因；可开始原 plan 重验、暂不验证或主动另选组合；拒绝该未确认工作流的捕获 |
| PROBING | 用户开始，控制器确认空闲并建立唯一 probeId | 运行已有阶段；允许取消；成功转 AWAITING_ACCEPT，失败转 FAILED |
| AWAITING_ACCEPT | 全部探测阶段成功，凭证已就绪 | 显示“画面正常，使用此组合”；用户接受、拒绝画面或取消；不再显示普通测光转圈 |
| APPLYING | 相机线程正在校验接受请求、保存并恢复用户所需 resident profile | 防重复点击；恢复完成后才能放开捕获；保存/恢复异常明确区分 |
| CONFIRMED | 凭证匹配、用户已接受，保存完成且当前会话可用 | 正常使用；同路径重开复用；运行时失败保留确认并按既有同组合恢复策略处理 |
| FAILED | 探测、确认前校验或恢复失败 | 明确原因；仅主动重试或主动另选；不无限循环探测、不自动换 YUV |

“暂不验证/取消重验”保留原 MANUAL 模式、镜头、plan 和缓存内容，回到 NEEDS_REVALIDATION；同一前台生命周期不要立即反复弹窗。可关闭提示并保留可点击的状态入口；再次测光应说明需要先验证，而不是无响应。

前后台处理：暂停时使未完成的 probeId 和 UI 代次失效，取消待执行任务，释放资源。恢复后重新评估状态：已持久化且仍匹配的资格可恢复；未完成/仅硬件成功但未人工接受的重验不能直接视为已确认。不要把旧回调接到新一轮流程。

正常预览可继续使用现有安全预览能力，但它不代表 RAW 组合已确认。若当前预览无法安全恢复，应显示相机未就绪状态，不通过修改用户镜头/测光精度来掩盖失败。

### 4.5 F1：接到真实会话流程，避免只是增加一个弹窗

1. `openCamera` 解析当前实际配置和候选后读取旧选择。命中重验时记录原 plan、选中镜头、用户模式和原因，撤销不适用的内存确认；**不要清除持久化旧选择**。
2. 该路径不得进入普通“未确认组合失败则尝试下一个”的分支，也不能调用 `advanceSystemCombination` 完成手动重验。系统自动初筛与手动旧选择重验是不同入口。
3. 用户开始重验后，只使用原 plan 调用现有 Runner。Runner 当前按 normalResident → normalMetering → zoneResident → zoneMetering → normalResident 组织阶段，并去重相邻相同 profile；不要另写只验证一次 RAW 的简化流程。
4. 当前 `probeManualCombination` 会先 begin，再有意 close/open。新流程必须允许这次内部重开，且第一阶段正确从 normalResident 开始；即使用户停留在 Zone，也不能让第一次 session 直接跳到 Zone profile。
5. UI 的 Normal/Zone 模式和镜头不随内部探测阶段改变。完成并接受后恢复 `desiredResidentSessionProfile()`；恢复成功前保持资源忙，结果/选择确认与相机可再次捕获是两个时点。
6. 检查 `onSessionConfigured` 中手动/系统探测的分派顺序：同一 session 只能被当前有效探测消费一次。一个 pending 状态只能启动一个 Runner，重复 open/configured 不得新建并行探测。
7. 沿用现有 RAW 配对、尺寸、元数据和轻量健康检查；不增加高光饱和否决、全图重算法或“每次普通测光再完整探测”。探测只负责初筛/确实失效后的重新资格确认。

**捕获入口保护：**除 `measure` 和 `measureZoneBatch`，还要检查 RAW 记录、色温 RAW 捕获、测光/曝光预览校准等会占用相机会话的入口。当前仅检查 combinationWorkflowProbe.isActive 不足以覆盖 NEEDS_REVALIDATION、AWAITING_ACCEPT、APPLYING。

推荐由控制器提供统一的“是否可开始相机操作及拒绝原因”，UI 用其展示状态，控制器在真正执行的相机线程再检查一次，防止点击后状态已变化。拒绝时立即结束本次请求并回滚临时 Zone 点/忙标记；本轮不新增自动排队测量，避免等待用户确认后对已改变的场景执行过期测光。

**失败与取消不能沿用新选择的自动跳过：**当前 MainActivity 的 showNextManualCombination 在失败后递增候选索引，cancelManualCombinationSelection 会调用 restoreSystemCombinationSelection。为重验增加独立 purpose；REVALIDATE_SAVED 失败停在原 plan，取消保留手动选择。NEW_SELECTION 的候选浏览行为可保留，但接受也必须经过 F2 的凭证校验。

### 4.6 F2：用完整、不可变、单次使用的成功凭证替代 planId 标记

当前存在两个具体问题：

- Runner 的 `advance`/`fail` 把 completion 投递到主线程；控制器的成功 completion 再向相机线程 post 写 manualProbeSucceededPlanId，却立即向 UI 报告成功。UI 因而可能先启用确认按钮。
- `acceptManualCombination` 在调用线程先修改 selectionMode、activeCombinationPlan，再判断 planId；随后用接受时的当前 route 保存。相同 plan 的旧成功不能证明新的路径也通过了探测，且验证不通过时目前仍会保存 MANUAL 并返回 true。

建议增加以下内存数据（示意字段，不要求照搬类名）：

```kotlin
data class ManualProbeEvidence(
    val probeId: Long,               // 每次用户发起的探测递增，不能只用 planId
    val selectionCameraId: String,   // 探测时的用户镜头/存储键
    val planId: String,
    val routeIdentity: String,       // 实际配置解析完成后冻结
    val ownerEpoch: Long,            // 外部取消/换镜头/暂停等使其失效
    val completedCameraGeneration: Int,
)
```

凭证只在内存中代表“本轮探测已完成、尚待人工接受”，不是加密令牌，不需要签名或网络服务。持久化仍使用现有 plan/origin/route/schema/fingerprint。

关键约束：

1. probeId 表示整次工作流，跨越它自己的内部 close/open 和 session 切换；不能简单捕获 begin 之前的 camera generation，然后把有意重开后的成功全判为过期。
2. 建立探测上下文时保存请求的 selectionCameraId 和 planId；实际 open configuration 确定后冻结 route。每阶段仍沿用现有 generation/stageRevision 检查；实际路径发生不兼容变化时终止本轮，不在最终阶段偷偷换 route。
3. 最后阶段完成时记录当前 camera generation。外部暂停、换镜头、重新选择、重新探测或实际配置变化，必须先使 ownerEpoch/probeId 失效，再做取消、资源清理和回调；这些操作不能与内部阶段重配置混为一类。
4. 在相机线程确认“上下文仍属于本轮且所有阶段成功”后生成 evidence，并转换到 AWAITING_ACCEPT，之后才能投递主线程 ready=true。失败不生成凭证；延迟旧失败也不能清空新探测的有效凭证。
5. 接受请求传递 probeId（或明确的不可变句柄），而不是只传 planId。相机线程检查状态、probeId、ownerEpoch、selectionCameraId、planId、route、完成后的相机代次是否仍适用，且确认尚未被消费。
6. 校验失败立即拒绝，不改模式、不改 active plan、不写缓存、不显示“已保存”；给出过期/路径变化/未完成等原因。不能再用 save(MANUAL) 作为 RAW 接受失败的成功兜底。
7. 校验成功使用 evidence 内的 selectionCameraId、planId、routeIdentity 写入，不临时以 cameraInfo/current route 重新构造证据。一次接受只能消费一次，双击不重复保存和切会话。
8. 非 RAW 候选的手动接受也须与刚完成的候选探测相对应，但不授予 RAW 确认锁；不要为了收紧 RAW 凭证顺带删除已有非 RAW 手动选择能力。

### 4.7 F2：线程与回调具体改法

建议 Runner 增加明确的“相机线程完成”接线，让控制器在该线程完成资格判定，控制器再单独通知 UI；若调整 Runner 公共 completion 线程契约，必须同时审查系统 probe 调用方，不能让原有 UI 回调直接落到相机线程。

推荐事件顺序：

```text
相机线程：最后阶段成功 → 核对 probe 上下文 → 保存内存 evidence
         → 状态 AWAITING_ACCEPT → 投递带 probeId/ownerEpoch 的 UI 通知
主线程：  检查 UI 代次与前台状态 → 启用人工确认
主线程：  用户接受 → 禁用重复接受 → 发给相机线程
相机线程：重新校验 evidence → 一次保存 → 消费凭证/建立对应资格
         → 恢复用户所需 resident profile → 回报最终可用/恢复失败
主线程：  仅处理当前代次的结果 → 更新提示、交互和选择器
```

将 `acceptManualCombination(planId): Boolean` 改成带 probeId 的异步结果接口。若保留 Boolean，它只能表示“请求是否成功入队”，不能表示“已验证且已保存”；MainActivity 必须等待完成结果才关闭选择器和提示成功。不要通过主线程等待相机线程锁来模拟同步。

Handler 缺失、post 返回 false、后台停止、Surface 丢失，都必须形成可终止的拒绝/取消结果，不能永久留在 APPLYING。恢复预览失败与保存失败分开报告：已经真正保存的资格不因一次恢复超时被悄悄降为 YUV；可以保留组合并提示重试恢复相机。

当前 Runner.abandon 不调用 completion，而 cancel 会发失败回调。调用方应明确谁负责终结 UI/状态；不能因换用 abandon 丢掉结束通知，也不能在主动取消后让旧 fail 回调触发“自动下一候选”。主线程与相机线程各自都检查其负责的代次，不能只依赖 MainActivity 的 manualCombinationGeneration。

### 4.8 存储与升级处理：不要把“资格失效”当成“用户没选过”

- `saveVerified` 沿用一次 SharedPreferences.edit 同时保存 schema、fingerprint、plan、origin、route，不分多次更新导致中间态。失败/取消不调用 clear、不覆盖旧选择，不改校准存储。
- 当前存储读取会在 schema/fingerprint 不符时直接返回 null，openCamera 又可能将没有有效 manual plan 解释为回到 SYSTEM。实施时必须审查此链；它与“MANUAL 缺 route”不是同一个输入。
- 建议增加类型化读取结果：Missing、Valid、NeedsRevalidation、UnsupportedRecord；把“仍能安全识别的旧 plan”作为未验证用户意图返回。环境 fingerprint 变化只使资格过期，不直接删除意图；不能通过忽略 fingerprint 来恢复 RAW 确认。
- 对未知未来 schema 不擅自解析或覆写：保留原记录并提示需要主动选择。若本轮不能完成该兼容分支，需在交付中明确未覆盖，不能宣称所有升级迁移均完成。
- 无需为增加内存 probeId 整体提高 schema/策略版本并使所有设备失效。只有实际改变存储或兼容判据时才设计版本迁移；已有同路径有效 SYSTEM_PROBE 和 MANUAL_VERIFIED 应继续可用。
- 现有 apply 是异步落盘，不能把调用返回称为“已验证磁盘写入成功”。若实现要求反馈持久化失败，使用不会阻塞主/相机线程的存储完成接口，并在返回时重新检查代次；否则至少保留重开读取验证，不新增无依据的写入成功保证。

### 4.9 最少需要的诊断日志

使用单一标记如 `ManualCombinationVerification`，每次状态转换记录一次；不要每帧输出，不记录完整图像或设备敏感标识。

- `needs_revalidation`：reason、selectionCameraId、planId、storedOrigin、storedRoute、actualRoute。
- `probe_started`：probeId、ownerEpoch、purpose、planId；实际路径解析后补 `route_bound`。
- `probe_stage`：probeId、stage/profile、cameraGeneration、结果；沿用现有错误码/原因，不吞异常。
- `probe_completed`：probeId、route、success、elapsedMs；与 UI ready 通知明确区分。
- `accept_rejected`：probeId、reason（未完成/过期/路径变化/重复等），禁止同时出现保存事件。
- `selection_accepted` / `selection_reloaded`：planId、origin、route、probeId（重载无当前 probeId 可省略）。若未确认落盘，不命名为 disk_saved。
- `cancelled` / `restore_completed`：probeId、reason/result；日志可核对失败后未换 plan、镜头或精度。

日志应能串起“读旧记录 → 为什么重验 → 确实探测 → 人工接受 → 使用哪条路径保存 → 重开复用”，而不是只有一条 needs re-verification 警告。

### 4.10 自动化回归用例：按完整行为验收

建议在现有 `CameraCombinationSelectionMigrationTest` 补判据测试，并增加协调器测试与存储 round-trip 测试。测试名可调整；用可控事件队列/假 Runner/假存储复现时序，不以 sleep 碰运气。相机线程和主线程任务要能分别推进。

| 编号 | 输入/操作 | 必须断言 |
| --- | --- | --- |
| T01 | MANUAL + raw_split_v1 + 缺 route，启动并完成用户确认 | 提示重验；只探测原 plan；写 MANUAL_VERIFIED + 实测 route；同路径重开直接复用 |
| T02 | MANUAL_VERIFIED 缺 route / route A 改为 B | 两者均要求重验，不恢复确认锁，不自动 SYSTEM/YUV |
| T03 | 有效同路径 MANUAL_VERIFIED，多次 open/resume | 无重复 probe；保留用户镜头、Normal/Zone 和 plan |
| T04 | 探测各阶段分别失败、超时、Surface 丢失 | 无新凭证、无保存、无自动下一 plan；原记录不变，忙状态结束 |
| T05 | 探测中取消、成功待确认时取消 | 旧选择与模式保留；迟到成功/失败不能保存或重开选择器 |
| T06 | 同一 plan 的 probe A 结束通知延迟，启动 probe B | A 的成功不能为 B 签发凭证；A 的失败不能清掉 B 的证据 |
| T07 | 路径 A 探测成功，接受前切路径 B | 接受被拒，不能用 B 的 route 保存 A 的成功 |
| T08 | Runner 成功后立刻点击接受；控制线程尚有排队任务 | UI ready 只会发生在 evidence 已建立之后，无旧 planId 标记竞态 |
| T09 | 无凭证调用接受、错误 probeId、连续双击 | 无证据/错误请求不改任何选择；正确凭证最多消费一次 |
| T10 | PROBING/AWAITING_ACCEPT → pause → resume，再投递旧回调 | 不复活旧状态；未人工接受不写 VERIFIED；恢复后可重新验证 |
| T11 | 内部有意 close/open、跨 profile 重配 | 当前 probe 不被自己的重配取消；外部 close/换镜头仍能取消；不会重复启动 |
| T12 | 用户在 Zone 发起重验 | UI 模式不变；内部完整探测，结束恢复 Zone resident；恢复前捕获被拒且不遗留点 |
| T13 | NEEDS_REVALIDATION/AWAITING_ACCEPT 时触发普通/批量/记录/校准 | 统一拒绝且带原因，不排队过期测量，不残留测光转圈/校准写入 |
| T14 | 缺/变 fingerprint、已知旧/未知 schema、候选不存在 | 意图与资格分离；不伪造确认，不静默抹除记录或切系统 |
| T15 | 非 RAW 手动候选正常接受 | 保留功能，但没有 RAW 确认锁；失败凭证不保存 |
| T16 | 新手动选择失败/取消与旧选择重验失败/取消分别执行 | 两类 purpose 行为隔离，不把重验接入自动跳过/恢复系统分支 |
| T17 | 系统 LEGACY_AUTO RAW、缺 route SYSTEM_PROBE、有效匹配系统缓存 | 第 7 节修复继续成立；有效缓存不重复探测，手动重验不进入系统探测 |
| T18 | Zone restore=true → pause → resume → 旧 restore 回调到达 | 旧回调不能解除新操作的 busy；现有暂停清理不回退 |

每个失败/取消用例至少加负向断言：saveVerified 调用数为 0、未调用系统 advance、未修改当前镜头/模式、未写校准。普通成功后模拟一次 RAW 运行失败，断言仍保留已确认 plan 并走既有恢复路径，而不是重新打开初筛并自动降级。

### 4.11 真机验证步骤与交付清单

1. 安装前记录提交、未提交文件、APK SHA-256 和组合偏好摘要；构建 APK 与上次已安装的 de043fa0…、第 7 节未安装的 cf739d4a… 分开标注，不混用测试结果。
2. 对当前保留的 MANUAL/raw_split_v1/缺 route 输入覆盖安装，不卸载、不清缓存、不先重新手动选择。观察启动是否进入可理解的待重验状态，镜头与 Normal/Zone 保持不变。
3. 先做一次“暂不验证”，确认不切到 SYSTEM/YUV；再主动启动原 plan 重验，核对 probe_started、所有阶段完成和实际 route。
4. 待用户观察画面正常并人工接受后，核对 origin=MANUAL_VERIFIED、非空 route 与探测一致；重开应用再次读取，确认不重复探测且测光可用。界面大小仍由用户观察，不为此截图。
5. 进行 Normal 2 次、Zone 临时点 2 次、前后台后再各 1 次；记录 RAW 来源、plan/route、是否恢复、是否出现降级。取消/跨代的毫秒级窗口以自动化测试为主，不把手动“未出现异常”当作竞态覆盖证明。
6. 旧输入一旦确认就会迁移成功；取消、失败等多分支优先用测试夹具或专用测试设备验证。不得为了重复试验擅自回写用户偏好、删除正式点或校准。没有实际测到的厂商/路由切换明确写未测。
7. 执行 `testDebugUnitTest lintDebug assembleDebug` 和 `git diff --check`，检查文档/测试材料仍在 APK 源集之外；交付修改文件列表、关键新测试、日志摘录、实际 APK 标识和未覆盖范围。

完成标准：旧手动 RAW 不再仅打印警告；确认只能来自本轮真实路径的成功凭证；取消和失败不改变用户选择；同路径确认后不反复初筛、不自动降级；Normal/Zone 与前后台恢复无回归。F1/F2 完成不等于 C/D 提速或跨厂商兼容性已完成验证。

## 5. C / D 后续安排

本轮并非 C / D 验收。继续遵循 [C/D 数据计划](REVIEW_FD73329_AND_CD_TEST_DATA_PLAN_2026_09_23_ZH.md)：先实现 C 的可靠调度与有限诊断日志，再收集同场景基线，最后决定 D 的优化策略。

不能因为短时间内 RAW 都成功而取消迁移验证；也不能因为等待慢就直接缩短超时、移除必要 RAW 帧数或合并隔离会话。此次日志可补充性能基线，但不是足以默认开启快路径的正确性证据。

## 6. 材料与交付状态

- 覆盖前 APK：`app/build/review-device-20260923/installed-before.apk`。
- 关键日志：`app/build/review-device-20260923/upgrade-and-zone-key-log.txt`，过滤摘录，非全部 logcat。
- 升级后组合缓存快照：`app/build/review-device-20260923/post-upgrade-combination-cache.xml`，仅组合偏好，不含校准/照片。
- 上述 build 目录可能被 clean 删除，本文已保留核心数字和证据。
- 手机保留本轮新 APK，旧手动组合未人为重新选择；此举为保留迁移证据，不代表该迁移问题已解决。
- 截至该阶段未修改源码，之后的授权小修见第 7 节。未提交 Git、未清用户数据。当前真机结果是“正常测光与一般前后台路径通过；旧手动 RAW 重新验证流程未完成；性能优化待实现”。

## 7. 用户授权后直接完成的小修

用户补充要求：预计百行内的小修改可以由测试方直接处理。本次仅处理影响明确、预计三十行以内的系统缓存漏洞，没有扩展为手动验证状态机重写。

修改文件：

- `CombinationRouteMatchPolicy.kt`：新增 needsSystemRevalidation；非 SYSTEM_PROBE 来源或缺失/不匹配实际路由身份的系统缓存必须重新验证。
- `CameraController.kt`：系统缓存读取分支改为调用统一判据，不再仅对 SYSTEM_PROBE 来源检查路由，因此 LEGACY_AUTO 的旧 RAW 缓存不会漏掉重验。
- `CameraCombinationSelectionMigrationTest.kt`：新增回归用例，覆盖空缓存、正常匹配、路径变化、缺路由、旧来源即使带路由也需要重验。

没有清缓存、改校准、合并 HAL 会话或修改 RAW 已确认后的降级规则。有效 SYSTEM_PROBE 同路径缓存仍直接复用；新建探测沿用已有系统验证流程。保留了用户/其他模型已有未提交改动。

小修后 `testDebugUnitTest lintDebug assembleDebug` 成功，79 个套件、373 项测试、0 失败/错误；`git diff --check` 通过。新 APK SHA-256：`cf739d4a1c348b589de960321ed698f1633d8df843937b826ec0fce1c4748621`。

版本边界：新 APK 尚未再次覆盖安装，手机仍为第 1 节的 `de043fa0…` 测试版本。该版本另存于 `app/build/review-device-20260923/tested-before-small-fix.apk`。第 3 节 19 次捕获和其他真机统计不归入小修后版本；新补丁目前具备构建与纯策略回归证据，尚无故障注入或旧系统 RAW 缓存迁移的真机证据。

旧手动 RAW 重验和手动成功凭证仍需实现，预计涉及超过这次小修范围的探测/回调/持久化/失败处理接线，因此继续按第 4 节交接，不把它们标为已完成。
