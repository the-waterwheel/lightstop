# 异步接受与手动组合升级分支复审

日期：2026-09-23。基线为 `7ff0254`，审阅对象是其后的未提交改动，加本轮审阅方的有限小修。本文不将普通测光成功等同于完整异常流程验收。

## 1. 已有改进

- 接受组合改为相机线程异步操作，保存使用 evidence.selectionCameraId，接受前检查完成时的 camera generation。
- 新增 pendingManualAcceptance，试图在 resident 会话恢复之后再完成接受，而不是入队即报成功。
- 普通/批量测光增加执行前资格复核；RAW 记录、色温、曝光预览校准补入手动资格门禁。
- 新增 ManualCombinationSelectionRead，区分 Missing、Valid、NeedsRevalidation、UnsupportedRecord，保留环境变化后的已知手动记录。
- 重验开始前有用户确认；原组合探测失败时提供重试/暂不验证。

以上方向正确，应保留，不退回上个版本的 planId 单标记或同步接受实现。

## 2. 本轮直接修复的局部问题

### S1：预览请求失败后不得继续报告接受成功

文件：`CameraController.kt`，session listener 的 onSessionConfigured，约 118 行。

原代码在 startPreview 返回 false 后仍无条件调用 completeManualAcceptance。现在失败则终结本次接受并返回；只有预览请求成功才继续处理 Zone 恢复和手动接受。此处的成功表示请求提交成功，不等于已经获得首张正确曝光画面的证明。

### S2：resident 完成时复核归属；成功解除旧“必须重新选择”标记

文件：`ManualCombinationVerificationCoordinator.kt` 的 confirm；`CameraController.kt` 的 completeManualAcceptance。

原 confirm 返回 Unit，即使状态已失效也无法告知控制器拒绝；控制器仍可能发布成功。现在 confirm 返回 Boolean，再检查 evidence、本轮状态、选择镜头、路径、ownerEpoch 和 generation；控制器同时检查 started、active plan 和 desired resident profile。不匹配则拒绝成功回报。

成功后清 manualSelectionRequiresChoice，避免“旧组合不存在 → 用户明确选择了新的有效组合 → 已成功但仍一直禁止测光”。这不允许系统静默替换原组合；必须先经过明确的成功接受。

新增 2 项回归测试：恢复完成时镜头/路径/owner/generation 不匹配、重复接受和重复完成；APPLYING 被外部失效后旧证据不能完成新会话。

### S3：接受回调必须属于当前界面操作

文件：`MainActivity.kt` 的 acceptCurrentManualCombination，约 812 行。

接受入队时冻结 manualCombinationGeneration。回调仅在仍为前台、代次匹配且选择器仍打开时更新 UI；避免取消或重新发起选择后，旧回调关闭新选择器、清除新候选或重置新操作的 accepting 状态。

这些修补没有修改 HAL 流组合、测光算法、校准、预览几何、曝光匹配或缓存版本号。

## 3. 构建与实机版本

- `testDebugUnitTest lintDebug assembleDebug` 成功；80 个套件、380 项测试、0 失败/错误；Lint 0 错误、65 警告。
- APK SHA-256：`396b512971c2b80f314b05225b2ef0030ce095f98d563b1131ad7b460dca9803`，已与手机实际 base.apk 的 sha256sum 核对一致。
- vivo V2405A / Android 16，已通过 adb install -r 覆盖安装并冷启动，本轮 PID=17271。
- 不卸载、不清数据、不伪造旧偏好，不保存校准。主摄在上轮已经迁移为 MANUAL_VERIFIED；本次不是缺 route 的首次升级测试。
- 用户完成本轮操作并反馈“正常”；详细证据及边界如下。

### 3.1 实机最终结果

日志窗口 13:28:00.907–13:29:52.807，同一 PID=17271，按 lightstop、RawLightMeter、CombinationProbe、CameraSession、AndroidRuntime 标签筛选：

| 指标 | 结果 |
| --- | ---: |
| RAW metering completed | 13 |
| Zone 事务完成 | 11，均 resultDelivered=true |
| RAW metering failed / probe failed / FATAL EXCEPTION | 均 0 |
| 自动旧手动重验告警 | 0 |
| Manual acceptance restored | 1 |

关键事件：

```text
13:28:01.215 初次打开 selected=raw_split_v1
13:28:41.394 前后台重开 selected=raw_split_v1
13:29:37.280 重新选择过程中 selected=raw_fully_isolated_v1
13:29:41.093 随后 selected=raw_split_v1
13:29:44.169 Manual acceptance restored probe=1 plan=raw_split_v1
             route=0|2|FIXED_PHYSICAL|1440|1080|4096|3072|640|480 profile=RAW_ONLY
13:29:48.005 RAW metering completed frames=3 elapsedMs=393.9
13:29:48.088 Zone resultDelivered=true tapToResultMs=488 tapToReadyMs=571
13:29:52.807 Zone resultDelivered=true tapToResultMs=509 tapToReadyMs=716
```

随后只读核对主摄存储：手动槽仍为 raw_split_v1 / MANUAL_VERIFIED，route 与上面的实际配置一致；系统槽为 raw_fully_isolated_v1 / SYSTEM_PROBE，并带相同 route。用户重新选择期间确有两种 RAW 候选出现，但最终人工接受的是原 raw_split_v1；这不是 RAW→YUV 降级证据，也不能描述成“整个测试从未变动任何组合缓存”。

成功范围：已保存资格复用、普通 Normal/Zone 测光、一般前后台、重新人工接受后继续 Zone 测光。未出现用户报告的重复验证、一直恢复中或不能建点。

重要边界：接受完成日志的 profile=RAW_ONLY，而非 COMPATIBLE。因此本轮验证了相机线程异步接受成功及随后切回 Zone，不足以证明“接受时恰好必须等待一次 Zone resident 重配置”分支通过，更没有覆盖恢复失败/错误注入。该分支仍需可控调度测试。

本窗口没有 Neutral preview baseline finished 记录，预览请求为 manualExposure=null。不可拿本次较短的 Zone 耗时与上一轮开启曝光预览时直接比较，更不可宣称 C/D 提速完成。

## 4. 尚未闭环的问题与修改要求

### R1 / P1：旧组合不在当前候选中时，可能以其他组合发起“原选择重验”

文件：`CameraController.kt`，openCamera 的 manualRead/storedManualPlanId/selectedPlan 和重验通知分支，约 2628–2790 行。

可达输入：读取层返回 NeedsRevalidation，但旧 plan 已从矩阵中移除。此时 storedManualPlanId=null、manualIntentUnresolved=true；manualPlanRequested 又为 false，于是 selectedPlan 可以取系统缓存/系统候选。后面只要 manualRead 是 NeedsRevalidation，就把这个 selectedPlan 当作 revalidationPlan 并发送 canStart=true；紧接着又发送一条 canStart=false 的未解决通知。

结果：可能出现针对不同组合的可开始弹窗和不支持提示，用户以为在确认原选择，实际探测的是另一个 plan。若 selectedPlan=null，该分支的 `?: return` 还会提早退出 openCamera，未完成相机打开。

修改方式：

1. 分开“有可重验的原 plan”和“原意图无法映射当前候选”；只有存在匹配 storedManualPlanId 的原候选才进入旧组合重验。
2. unresolved 只发一种带原因的不可直接重验状态，不读取系统候选作为其重验替代品，也不提前 return 留下半初始化配置。
3. 用户主动开始全新选择时，manualProbePlanId 是独立的显式意图，不应被旧记录的 unresolved/NeedsRevalidation 通知打断；只有成功接受才替换旧记录。
4. 增加完整输入矩阵：环境变化+原 plan 仍存在、环境变化+原 plan 删除、未知 schema、无候选、显式新选择。断言通知次数、plan、canStart、存储内容及是否错误进入系统探测。

当前新增读取类型值得保留，但单独返回 NeedsRevalidation 并不能保证后面的协调流程正确。

### R2 / P1：恢复失败、相机错误和内部重开尚无统一接受终结规则

文件：`CameraController.kt` 的 acceptManualCombination、failManualAcceptance、handleCameraFailure、scheduleRecovery、finishCameraFailure、closeCamera、setMeteringPipelineMode；`MainActivity.kt` 接受失败回调。

当前已处理 session configure failure、主动取消、stop 和部分换镜头，但 pendingManualAcceptance 不是所有失败/关闭路径的统一受管操作。相机错误进入恢复/最终停止时，可能没有及时向接受 UI 发出终结结果。switchResidentSession 在 !started/cameraDevice=null 时直接 return，也没有确保已进入 APPLYING 的请求结束。

另外 failManualAcceptance 清掉内存 evidence，UI 却把 ready=true 打开，继续保留原 manualCombinationProbeEvidence。再次点接受只是提交同一个已经无效的 probeId，会再次失败，不是真正重试。该问题不是按钮文案可以解决的。

修改方式：

1. 明确“已成功写入的用户批准”与“当前 resident 是否可用”；恢复失败不能再伪装成硬件从未验证，也不能自动降级。
2. 为接受操作设计单一终结出口，明确哪些 close 是终止、哪些内部同组合恢复可以延续；所有 STOP/Handler 拒绝/设备丢失路径最终回调一次并释放 APPLYING。
3. 对已持久化资格提供同组合恢复入口；若决定重新探测，必须新建 probeId/evidence，成功后再人工接受。禁止无限重交已清掉的 evidence。
4. 拒绝、已保存但恢复失败、过期、用户取消采用明确结果类型，UI 不把所有 Result.failure 都渲染成“再次点击同一接受按钮”。
5. 加假调度测试：接受后 onError/disconnect、最终 STOP、Handler 消失、无 device、恢复失败后重试成功、回调恰好一次且不污染下一请求。

S1/S2 仅堵住错误报成功和错误归属，不代表这个完整恢复协议已实现。

### R3 / P2：通知中的 ownerEpoch 尚未真正用于拒绝旧请求

文件：`CameraController.kt` 创建 ManualCombinationRevalidationRequest 的两个 mainHandler.post；`MainActivity.kt` 的 onManualCombinationRevalidationNeeded/startManualCombinationRevalidation。

request.ownerEpoch 当前在主线程回调执行时读取控制器的可变字段，不是投递时冻结；UI 只检查 selectionCameraId，没有校验 ownerEpoch。用户离开再回到同一镜头时，旧弹窗仍可发起已过期请求。失败弹窗的按钮回调同样应校验其所属代次。

修改方式：在相机线程构造不可变 request 后再 post；控制器提供 current-request 校验并在真正开始 probe 前验证。开始验证、重试、暂不验证的弹窗动作都绑定代次；onPause/镜头变更时关闭或使旧弹窗无效。不以“镜头 ID 相同”替代 owner 校验。

### R4 / P2：探测的实际 route 仍是在结束回调中读取

文件：`CameraController.probeManualCombination` 与 `ManualCombinationVerificationCoordinator.complete`。

目前 generation 在接受和本轮补的完成点得到核对，但 routeIdentity 仍在 Runner 完成通知经过主线程、再次排到相机线程后，从当前 activeOpenConfiguration 读取。没有先绑定实际打开配置，再贯穿所有阶段校验的上下文。

修改方式：probeId 跨越有意的内部重开；实际配置解析完成后绑定 route/选择身份，每阶段核对；终态产生属于该 probe 的成功结果，控制器不能临时以“此刻的路径”签发成功。外部恢复改变路径或代次时，按明确规则取消而不是重签。

与 R2 一起测试：最后阶段成功通知排队期间发生恢复/换路径、同 plan 不同路径、旧成功和旧失败迟到。不要为了同步而让主线程阻塞等相机线程。

## 5. 验收及工作区约束

- 接受已移到相机线程是实质进展，但当前协调器测试仍未替代存储 round-trip、控制器/Runner/UI 完整流程和故障注入测试。
- 新单测覆盖归属判据，不等于已覆盖真实设备错误或 PreviewRequest 提交失败。
- 当前测试先覆盖正常保存资格复用、Normal/Zone、前后台以及可操作时的重新人工探测；不回写用户偏好制造 schema/plan 失效。
- 已有用户未提交改动保留；本轮源码及报告尚未新增 Git 提交，不发布、不推送。后续提交必须同时说明仍未覆盖的异常分支，不能把 380 项总测试数当作全部 T01–T18 完成。
- C/D 曝光预览与 Zone 延迟仍是独立任务；本轮未调整 AE 超时、RAW 采样数量或曝光匹配算法。
