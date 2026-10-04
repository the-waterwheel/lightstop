# 光档：当前改动复审后的修复交接文档

编写日期：2026-09-22。项目根目录：`D:\Project\lightmeter a`。

审阅基线：分支 `codex/camera-controller-components`，HEAD `44b544cad04fc69588c13c76edd22aac5a1543bf`，**加当前工作区的 26 个已跟踪文件修改**。仅检出该提交不能复现本文审阅的代码，必须同时保留当前未提交 diff。

本文供接手模型 / agent 实施修复。本文不是已完成修复的声明；本次只新增文档，没有修改源码、安装 APK、清空设备数据或提交 Git。

## 1. 接手前必须明确的边界

### 1.1 本轮目标

修复当前实现中 4 个 P1 问题和 2 个 P2 问题，完成相应自动化测试后，再进行以日志为主的设备验收。不要借机重写整个相机框架、调整界面尺寸或改动无关业务。

用户最新要求优先于旧方案中确认后的自动降级策略：

> 先通过组合矩阵初筛，后续要求不要过严；RAW 组合确认以后，不再自动降级为 YUV/ISP。

这里的“不降级”包括单次测量来源、后续默认组合、持久化选择和镜头路由。不能只隐藏错误提示，或只禁止修改缓存，却仍返回一份 YUV 替代读数。

### 1.2 与已有文档的关系

建议按以下顺序阅读：

1. 本文：当前 diff 的复审结论、已经取得的设备证据和具体剩余修复任务。
2. [RAW 确认与禁止自动降级方案](RAW_WORKFLOW_CONFIRMATION_AND_NO_AUTO_DOWNGRADE_PLAN_2026_09_22_ZH.md)：完整的 RAW 行为边界和入口清单。
3. [此前审阅修复实施文档](REVIEW_FIX_IMPLEMENTATION_PLAN_2026_09_22_ZH.md)：校准、RAW 补帧、记录事务、定位和无障碍的背景。

注意：前两份旧文档分别记录过“22 个修改文件”和“干净工作区”，这都不是当前工作区状态。旧 RAW 文档中“尚未取得 logcat”的描述也只适用于它的编写时刻，最新证据见本文第 2 节。

本文是增量整改，不要求撤销当前改动重新实施。旧文档中的待实现项，有些已部分实现，应先检查现状。

### 1.3 不可破坏的设计

- 保留厂商 HAL 的进程/会话隔离、资源关闭顺序、初筛候选搜索和安全停止机制；不能通过合并 RAW/YUV/预览会话规避问题。
- 已确认 RAW 遇到真实设备断开、权限撤销或服务故障时，可以关闭资源、有限次重开原工作流，或者暂停等待用户操作；不得继续向已关闭的会话提交请求。
- Normal、Zone、设置页、摄像头管理和前后台切换不得擅自更换用户镜头、测光模式或已确认组合。
- 不改变稳定的 TextureView/SurfaceTexture 传输几何，不混用逻辑预览与物理 RAW 坐标，不因运行时物理元数据变化重新定义预览方向。
- RAW 缓冲区布局、边界、资源寿命和必要物理身份检查必须保留。“放宽”指不把场景质量或偶发失败等同于硬件不支持，不是接受危险或错配数据。
- 不以整幅高光饱和比例作为强制 RAW 质量条件；健康检测成本不能超过重新捕获一张 RAW。
- Zone 全部重测继续使用共享 RAW/共享 burst 和逐点匹配；失败或越界点保留旧值并计入未更新，不逐点另拍 RAW，不在普通拍摄时刷新全部点。
- 不增加全局 EV 修正；设备/镜头测光校准与曝光预览辅助校准保持独立，不能重复叠加。
- 参数记录保留闪光、距离、GN 数值及制式/参考 ISO；不让工具中的临时 ISO 回写主转盘。
- 不改变测距资格、镜头基线验证规则，不引入物体尺寸测距作为默认方案。
- 不移除 ABI、OpenCV 现有功能或其他功能来制造 APK 体积下降；文档与测试日志不得加入生产 assets/res。
- 保留既有 Git 历史，不 reset/覆盖用户改动；没有额外授权不提交、不推送、不发布。

## 2. 本轮已经验证的事实

### 2.1 本地构建

上一轮执行：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

结果：构建成功；测试报告为 72 个套件、337 项测试、0 失败、0 错误；Lint 为 0 错误、65 项警告。

该命令包含 UP-TO-DATE 任务，不应表述为全部测试都被强制重新执行。修复完成后应实际重跑测试。现有测试通过不代表 CameraController 回调链、缓存迁移、编辑器生命周期或厂商 HAL 已正确集成。

### 2.2 设备与 APK 对应关系

已通过 ADB 连接 vivo `V2405A`，设备标识 `PD2405`，Android 16。设备中应用为 `com.lightmeter.rawmeter`，versionName `0.4.0`，versionCode `9`，可调试版本。

本轮比较得到：

| 对象 | SHA-256 |
| --- | --- |
| 设备已安装 base.apk | `2a72454cc08b19622eb71e9272f1c0c225a2bfdd8d830c12790106755dadbac9` |
| 当时本地 app-debug.apk | `c769d0cc635cc531a1a01b05981185a1b334c5bf2716d487cee5ff4e3646e07b` |

两者不同。因此下述日志是**现有安装版本的现场诊断证据**，不能当作当前工作区修改的真机验收。下次构建后哈希可能改变，需重新核对，不要把上述本地哈希写死为发布标准。

### 2.3 与 RAW 问题直接相关的日志

2026-09-22 14:24:21 左右，应用进程启动日志包含：

```text
Stream matrix candidates mode=AUTO selected=stable_yuv_v1:
  raw_split_v1:GUARANTEED
  raw_fully_isolated_v1:GUARANTEED
  raw_full_v1:GUARANTEED
  stable_yuv_v1:GUARANTEED
  compatibility_isp_v1:GUARANTEED

Camera status: 正在打开摄像头;
  id=0@2; logical=0; physical=2;
  rawHardware=true; rawSession=false

Opening selection=0@2, logical=0, openId=0,
  route=FIXED_PHYSICAL, physical=2, profile=COMPATIBLE, raw=false

Camera status: 1440×1080 · 预览流测光
Preview health state=HEALTHY reason=null
```

为了便于阅读，上述内容按字段换行，非逐行原样日志文件。相机缓存文件 `shared_prefs/camera_combination_selection.xml` 的只读结果还包含：

```xml
<string name="combination_30_system_auto_plan">stable_yuv_v1</string>
<string name="combination_bc22_system_auto_plan">stable_yuv_v1</string>
<int name="combination_30_system_auto_schema" value="1" />
<int name="combination_bc22_system_auto_schema" value="1" />
```

按现有 Java/Kotlin 字符串 hashCode 键规则，`30` 对应 `0`，`bc22` 对应 `0@2`。两项均在 **system_auto** 命名空间中，不是手动组合记录。缓存还带有当前设备系统 fingerprint 和策略版本 `|1`。

可得出的结论：

- 设备报告该镜头具有 RAW 能力，矩阵将三个 RAW 候选标为 GUARANTEED。
- 本次启动实际直接选择并使用了缓存的 YUV 组合，没有在这段启动日志中看到重新验证 RAW 的过程。
- 当前源码同样允许这个旧缓存跳过系统探测，所以仅增加 confirmedRawPlanId 保护不能恢复这一现场状态。

不能得出的结论：

- 不能凭矩阵 GUARANTEED 断言完整 RAW 工作流一定能在该设备成功。
- 没有捕获最初发生降级的事件，不能断言是某个具体 HAL 错误或健康指标触发。
- 日志中的 `manual=true` 不是“用户手动选择组合”的充分证据，不应据此忽略 system_auto 缓存。
- 不应把厂商库的普通 E/W 级日志直接当作本应用崩溃。

设备缓存未被删除，应用未被覆盖安装；后续仍可保留现场做迁移验证。

## 3. 修复清单与实施顺序

下文源码文件均位于 `app/src/main/java/com/lightmeter/rawmeter/`，测试文件均位于 `app/src/test/java/com/lightmeter/rawmeter/`，除非另有说明。行号是当前工作区定位提示，实施时以函数名为准。

| 编号 | 优先级 | 问题 | 核心文件 | 建议批次 |
| --- | --- | --- | --- | --- |
| R01 | P1 | 旧自动 YUV 缓存绕过 RAW 复测 | CameraCombinationSelectionStore、CameraController | A |
| R02 | P1 | 手动确认 RAW 未进入保护状态 | CameraController、组合确认状态/持久化 | A |
| R03 | P1 | 关闭参数编辑器后持续吞返回键 | MeterLayout、ParameterRecordEditorView、ParameterCaptureGuard | B |
| R04 | P1 | 校准样本与融合读数不一致 | MeteringFusion、CameraCalibrationStore、相关样本链 | C |
| R05 | P2 | 预览健康恢复绕过确认后的策略 | CameraPreviewHealthCoordinator、CameraController | A |
| R06 | P2 | 保存态未阻止滑动和动画修改草稿 | ParameterRecordEditorView、MeterLayout | B |

R01/R02 与 R05 必须一起验收；R03 与 R06 适合同批修复。优先级表示影响，不要求为了严格顺序拆开彼此依赖的状态机。

## 4. R01：旧 YUV 缓存迁移与重新验证

### 4.1 修改位置及根因

- `CameraCombinationSelectionStore.kt`：`selectedSystemPlanId()`、`saveSystem()`、`environmentKey()`，当前 `SCHEMA_VERSION` 和 `COMPATIBILITY_POLICY_VERSION` 均为 1。
- `CameraController.kt`：`openCamera()` 内系统缓存选择，约 2436—2469 行；`pendingSystemWorkflowProbePlanId` 的赋值。
- `CameraController.kt`：系统工作流探测成功/失败回调、`advanceSystemCombination()`、稳定预览缓存写入位置。

当前代码将“缓存 planId 可用”直接等同于“不必再次探测”。旧版本自动降级写入的 stable_yuv_v1 因而被长期复用；新的 RAW 保护只有进入 RAW 后才有效。

### 4.2 实施方法

1. 将缓存读取结果从单一 planId 扩展为可区分来源和验证状态的结果；建议抽取纯策略类型，而非在 openCamera 中继续堆叠布尔条件。
2. 至少区分：用户主动手动选择、当前策略的系统验证结果、旧版来源不明的自动选择、待复测、已确认 RAW。
3. 对当前模式为 AUTO 且具有 RAW 候选的旧版 system_auto 低精度缓存，标记为迁移待复测，不直接当作本轮已验证结果。
4. 复测先用已有矩阵排除明确不支持的配置，保留 UNKNOWN 进行真实尝试，再走最小实际工作流验证。不要仅凭 GUARANTEED 设置 confirmed。
5. 首次选择期间所有 RAW 候选确实未通过时，可按产品已有初筛流程使用兼容候选，但必须保存本次策略版本、来源与失败分类；这不等于确认后运行时允许再次自动降级。
6. 用户主动选择 YUV/ISP 的结果必须保留；用户主动选择稳定/快速模式也不能被迁移强行改回 AUTO。
7. 对旧版 RAW 缓存，要明确采用“已有证据可迁移”还是“需要一次验证”的策略，不能将仅由旧预览稳定性写入的 planId 无条件包装成新确认结果。
8. 保存迁移完成/待复测状态，避免每次前后台切换都触发完整组合搜索。若进程中途退出，下次可有界恢复未完成复测，不形成启动循环。
9. 保留 UI 中“重新测试组合”的显式入口；如当前入口不能覆盖系统旧缓存，补充对应行为和状态提示。

建议记录的语义字段：schema、策略版本、routeId、系统 fingerprint、pipelineMode、planId、selectionOrigin、validationState，以及实际验证所依赖的输出配置身份。字段名称可调整，必须与会话实际路由及配置一致。

**不能简单统一增加版本号后丢弃所有记录。** 当前手动与系统记录共享部分版本常量，粗暴失效可能让用户手动选择回退为系统模式。需要独立迁移或兼容读取，并给出单测。

### 4.3 必补测试

- 旧 system_auto stable_yuv + RAW 候选存在：触发迁移复测，不立即当作已验证 YUV。
- 旧手动 stable_yuv：保留用户意图，不自动抢回 RAW。
- 用户主动的稳定/快速模式：保持模式，不走 AUTO 的 RAW 迁移。
- 无 RAW 硬件/无 RAW 候选：不无意义重试。
- 矩阵 UNKNOWN：不被等同 UNSUPPORTED。
- 迁移成功后重启：复用新结果，不重复完整探测。
- 迁移未完成时退出：下一次可恢复，不把中间失败写成永久硬件不支持。
- 只有 route/系统环境/相关输出配置改变时，才按定义使确认失效；不因页面切换失效。

建议新增 `CameraCombinationSelectionMigrationTest.kt`。若使用 Android SharedPreferences 难以 JVM 测试，抽取纯迁移策略，存储适配另做集成测试。

## 5. R02：统一系统与手动 RAW 确认

### 5.1 修改位置及根因

- `CameraController.kt`：字段 `confirmedRawPlanId`，约 428 行。
- `probeManualCombination()` 与 `acceptManualCombination()`，约 684—736 行。
- 系统探测成功回调，约 1390 行。
- `openCamera()` 中确认状态恢复，约 2460—2463 行。
- `isConfirmedRawWorkflow()`、`resetRecoveryState()`、镜头/模式更换入口。

当前只有系统探测成功和 cachedSystemPlanId 命中会赋确认值。手动路径使 cachedSystemPlanId 为 null；接受手动结果时也没有设置确认。因此手动探测通过的 RAW 不享受新增保护。

### 5.2 实施方法

1. 引入统一的确认上下文/记录，系统探测和手动验证共享同一判定与恢复逻辑，不各自维护不一致的锁。
2. 手动路径必须绑定“本次探测成功的 plan + 路由/配置 + 探测代次”。用户接受的必须是这个成功结果，不能在 acceptManualCombination 中无条件把任意候选确认。
3. 在相机拥有状态的线程上串行更新确认；UI 接受动作不能与关闭/重开/取消探测交叉产生旧 plan 确认新相机的竞态。
4. 对手动接受与系统成功分别持久化来源，但保护语义一致。
5. 普通 reopen、前后台恢复及 Normal/Zone 常驻会话切换，从有效上下文恢复确认。临时会话不含 RAW Surface 不等于 RAW 工作流失去资格。
6. 更换实际镜头/路由/相关输出配置时不能沿用仅 planId 相同的旧确认。用户取消尚未接受的探测，也不能污染原确认。
7. 失败时保护以下入口：普通 RAW、隔离 RAW、Zone 批量、session configure 失败、CameraDevice 错误、预览健康恢复、自动 route 切换和系统缓存清除。
8. 业务校准可以显式请求 YUV/ISP，但不能修改主工作流的确认或成为普通 RAW 失败后的隐式替代。

不要把 `rawAvailable` 直接当作确认状态；它目前混有当下会话/Surface 可用性的语义。需要区分硬件支持、工作流确认与当前资源是否就绪。检查 `startMeasurement()` 的源选择和 `ParameterRecordToolView` 的 RAW 选项恢复逻辑，防止因临时 rawAvailable=false 清掉用户偏好。

### 5.3 最小行为表

| 状态/事件 | 允许行为 | 禁止行为 |
| --- | --- | --- |
| 尚未确认，矩阵/实际探测失败 | 有界尝试其他初筛候选 | 无限探测或接受错误身份缓冲区 |
| 已确认，单次 RAW 数据不可用 | 本次失败、保持旧结果、下次仍用 RAW | 返回 YUV/ISP 替代读数 |
| 已确认，真实会话/设备错误 | 原工作流有界重开，耗尽后暂停 | 自动切换 plan/镜头，删除确认 |
| 已确认，软预览健康告警 | 告警或按定义有限恢复原工作流 | 将画面指标当作 RAW 不支持 |
| 用户明确改用低精度组合 | 执行用户选择并保存来源 | 后台又自动抢回 RAW |
| 环境身份改变 | 提示需重新验证，进入有界验证 | 假装旧确认有效或静默换 YUV |

### 5.4 必补测试

- 手动成功 → 接受 → RAW 单次失败：没有 compatible metering 调用，没有缓存清除。
- 手动成功 → 接受 → 重开/后台恢复 → RAW 失败：保护仍在。
- 手动失败/取消/过期完成回调：不得设置确认。
- 同 planId、不同 route/config：不能继承旧确认。
- Normal 与 Zone 相互切换，包括 RAW_ISOLATED 临时事务：确认保留。
- 连续真实恢复失败：次数有上限，结束时资源释放，plan/镜头选择未变。
- 明确请求校准 YUV：可运行，但后续普通测光仍遵守 RAW 确认。

已有 `CameraRecoveryPolicyTest` 中 confirmedRawWorkflow 返回 RETRY 的测试不足以证明这些调用链。建议补充 `ConfirmedRawWorkflowPolicyTest.kt` 及可注入动作的控制器/协调器测试。

## 6. R03：修复参数编辑器吞返回键

### 6.1 修改位置及复现路径

- `MeterLayout.kt`：`closeParameterEditorFromBack()`，约 1865—1869 行；`closeParameterEditor()`；保存/取消监听，约 825—869 行。
- `ParameterRecordEditorView.kt`：`open()`、`currentDraft()`，目前关闭后没有相应清理草稿。
- `ParameterCaptureGuard.kt`：`cancel(token, draftId)`、`complete()`、`isSaving`。
- `MainActivity.kt`：`handleBackNavigation()`，参数编辑器检查在设置/工具/Zone 关闭之前。

复现逻辑：打开一份草稿 → 保存成功或取消 → guard 已清除活动草稿，但 editor 仍保留 draft → 再按返回 → guard.cancel 返回 false → closeParameterEditorFromBack 返回 true → 后续导航分支永远不执行。

### 6.2 实施方法

1. `closeParameterEditorFromBack()` 第一层先判断 `isParameterEditorOpen`；未打开必须返回 false。
2. 区分“当前草稿正在保存”与“草稿已失效”。只有前者需要消费返回以保护保存事务，不能把所有 cancel=false 都当作保存中。
3. 当前打开但 draft 缺失或已过期时，应关闭这个过期编辑器或走明确清理分支，不能继续占用导航；不得删除不属于它的 pending 文件。
4. 当前有效且非保存草稿：捕获局部不可变引用，用 token+draftId 取消，关闭编辑器，后台 discard 仅处理该草稿。
5. 增加明确的 editor 关闭/重置方法，清理 draft、触摸状态和动画。若关闭动画需保留画面，延迟清理必须绑定关闭代次，不能清掉动画期间新打开的草稿。
6. 保存期间不 discard。已完成保存不再调用删除 pending 的取消逻辑。保留旧回调不能影响新 token 的现有保护。

### 6.3 必补测试

- 编辑器从未打开：返回 false。
- 取消后按返回：继续交给设置/工具/Zone 导航。
- 保存成功关闭后按返回：不被旧 draft 拦截。
- 保存中按返回：消费事件但不取消保存、不删除文件。
- 保存失败后按返回：可以正常取消一次。
- 关闭动画期间打开新草稿：旧动画/回调不能清理新草稿。
- 过期草稿的取消不能影响新的 active token。

除扩展 `ParameterCaptureGuardTest.kt`，还必须测试 `MeterLayout` 的分发语义。可抽取纯 `ParameterEditorBackPolicy`（建议新增）测试返回决策，配合实际导航集成验证，不能只测 guard。

## 7. R04：校准前 EV 必须与最终融合结果一致

### 7.1 修改位置及根因

- `MeteringFusion.kt`：`fuse()`，第 12 行 representative 按到达顺序取中间元素；第 22—28 行新增 calibrationSample 使用该帧。
- `MeteringAnalysis.kt` / `MeterModels.kt`：每帧 `ev100BeforeUserCalibration`、`appliedUserCorrectionEv` 的定义和产生位置。
- `CalibrationMeasurementSample.kt`：样本语义及有效性判断。
- `CameraCalibrationStore.kt`：`updatedStream()`，有效样本优先用于计算 correction。
- `CameraController.kt`：`freezeCalibrationSample()`；校准协调器/计划中样本保存与传递。

显示的 sceneEv100 为多帧 EV 中位数，但校准样本取未排序列表的中间一帧，破坏了鲁棒融合与校准的一致性。

确定性例子：当前修正为 0，帧 EV 依次为 `[9, 10, 12, 9]`。显示值为 9.5，样本基准却为 12；参考 EV=9.5 时，新算法写入 -2.5 EV，正确值应为 0。

### 7.2 实施方法

1. 对 RAW 的校准前 EV 使用与显示 EV 一致的融合规则，即对有效帧基准 EV 求中位数，偶数帧取中间两值均值。
2. 定义融合后 appliedUserCorrectionEv 的语义。RAW 同一测量内应使用冻结的同一用户修正；此时应满足 `displayEv = fusedBeforeUserEv + appliedCorrection`。若测量过程中校准上下文发生变化，应使样本不可用于保存或重采，不能拼接不同校准状态。
3. 代表帧的 ISO/曝光时间/光圈可以保留明确的展示策略，但不能因此继续用它替代融合的校准前 EV。
4. 对 processed stream，不要分别对 luma、EV 和 correction 任意求中位数后拼成“同一帧”。输入 luma 与该点校准前 EV 必须对应同一帧或同一明确定义的估计量。当前若只采一帧应保持单帧语义；将来扩为多帧前必须明确曲线锚点策略。
5. 无效/非有限统计的剔除应保持字段成组，不能一组数组剔除而另一组仍保留该帧。
6. 保留“保存时使用测量快照”的改进，不回退到当前活动会话读取校准。检查 freezeCalibrationSample 在异步主线程回调前后是否可能读到新的 route/generation；若会跨代，需在拥有该测量上下文的位置冻结或拒绝过期结果。
7. 对校准业务流中的缺失/不匹配样本明确失败或重采策略。现有兼容 fallback 不应悄悄掩盖新样本链断裂；确有旧调用方时保留有测试的兼容入口，而非随意删改公共行为。

### 7.3 必补测试

- `[9,10,12,9]`：显示 9.5，RAW 校准前值 9.5，参考 9.5 时修正为 0。
- 上述输入的不同排列：校准结果不变。
- 奇数帧、偶数帧、单帧及异常帧。
- 已有固定修正 +1 EV：校准前值不含此修正，重复校准不累计漂移。
- 非有限字段：拒绝/过滤策略一致，无 NaN 被持久化。
- RAW → YUV → ISP 校准阶段切换后保存：各来源使用自己的冻结样本。
- 不同 cameraId/签名的样本：不写入当前镜头校准。
- processed response 曲线：inputLuma 与基准 EV 同源，重复校准不二次叠加响应。

建议新增 `MeteringFusionTest.kt`，扩展 `ProcessedResponseCalibrationTest.kt`、校准协调器及存储集成测试。只验证 CalibrationMeasurementSample 可构造，不算完成本项。

## 8. R05：预览健康恢复必须理解“禁止自动降级”

### 8.1 修改位置及根因

- `CameraPreviewHealthCoordinator.kt`：`requestRecovery()`。
- `CameraController.kt`：`scheduleSafePreviewRecovery` 绑定约 603—611 行，仍请求 PREVIEW_ONLY；`failSafePreview` / `failPreview` 仍调用 finishCameraFailure。
- `CameraController.kt`：`advanceSystemCombination()` 与 `tryNextCameraRoute()` 的 confirmedRaw 拦截。
- `CameraRecoveryStateMachine.kt`：恢复预算；`PreviewHealthSampler.kt`：采样窗口与软指标。

当前 `advanceSystemCombination()` 在确认 RAW 后返回 false，但调用方将 false 理解为“继续尝试旧的安全预览恢复”。新保护没有终止旧链路。

必须准确区分：这条路径会请求 PREVIEW_ONLY，并可能最终关闭相机；但 openCamera 随后可能根据缓存重新选择 RAW，所以**不能声称它一定造成永久 YUV 降级**。问题是恢复决策绕过锁定语义，软画面异常仍可触发不符合约定的恢复/终止。

### 8.2 实施方法

1. 不再用一个 Boolean 同时表达“下一候选不存在”“策略禁止切换”“切换已执行”。建议返回明确决策，或在协调器入口先处理已确认 RAW 状态。
2. 已确认 RAW 的软画面指标只告警，或按明确策略有限次恢复原工作流，不进入 PREVIEW_ONLY 安全组合搜索和备用 route 选择。
3. 高帧率回退到标准帧率只有在不改变已确认工作流关键身份的条件下允许，并记录原因；若修改了验证所依赖的配置，应走明确重新验证语义。
4. 对真实 Camera2 错误保留有界资源恢复与安全停止，不把“禁止降级”实现为无限 retry。
5. 恢复预算不能仅因短暂预览健康就无限重置。测试一轮实际故障期间的总预算，以及正常稳定后重置预算的明确条件。
6. 明确用户提示：区分“本次测量未更新”“RAW 暂不可用，保持原组合”“需要用户重试”。不显示已经发生精度切换的误导文字。

### 8.3 必补测试

- confirmed RAW + 软健康失败：advance/route switch/cache clear/低精度 fallback 均未执行。
- 未确认状态：原有初筛搜索仍可执行。
- 相机真实不可用：有限恢复后暂停，保留确认记录和用户选择。
- stale generation 的健康回调：无副作用。
- 恢复后再次软告警：不落入旧 failSafePreview 分支错误关闭确认工作流。

建议新增 `CameraPreviewHealthRecoveryPolicyTest.kt`，覆盖动作分发；不要只测画面指标识别算法。

## 9. R06：保存态冻结所有可修改入口

### 9.1 修改位置及根因

- `ParameterRecordEditorView.kt`：`setSaving()`，约 41—44 行；`onTouchEvent()`，约 250 行；`updateSelector()`，约 330 行；惯性动画更新、备注对话框、胶片回调和无障碍点击。
- `MeterLayout.kt`：`onSaveRequested()`、完成/失败/任务拒绝回调。

目前只有 handleTap 判断 saving。保存期间拖动光圈、快门、EI，或先前启动的 selectorAnimator，仍可修改 draft；后台保存的是点击时捕获的旧对象，界面可能显示新参数，产生记录与所见不一致。

### 9.2 实施方法

1. 进入 saving=true 时，停止 selectorAnimator，释放 VelocityTracker，清理当前手势目标；不要让旧手势在保存态结束后继续生效。
2. 在统一修改入口检查 saving：至少覆盖 updateSelector、selectFilm、备注提交、无障碍动作及所有异步 UI 编辑回调。只屏蔽点击不够。
3. 对已打开的备注对话框、延迟胶片选择结果，绑定 draftId/代次，保存中或草稿已换时拒绝写回。
4. 后台使用进入保存态时的不可变快照；确保 notes 等集合不会被别处原地修改。
5. UI 明确显示保存中，保存及编辑动作不可用；同步更新虚拟节点 enabled 状态，而非仅改变颜色。
6. 失败或任务未提交时恢复同一有效草稿的编辑；成功则由 R03 的关闭逻辑释放草稿。过期完成回调不能解锁新草稿。

### 9.3 必补测试

- 保存期间拖动三个参数选择器：草稿不变。
- 惯性滚动未结束时触发保存：动画停止，落盘与快照一致。
- 旧备注/胶片回调晚到：不能修改保存中的草稿或新草稿。
- 无障碍触发编辑/保存：保存中不重复执行。
- 保存失败：同一草稿可以继续编辑并再次保存。
- 执行器拒绝提交：退出保存态，无孤立“永久保存中”状态。

## 10. 已有改动应保留，以及不应过度宣称完成的部分

### 10.1 RAW 帧数修正

当前 `RawLightMeter.kt` 已将补帧目标修正为：

```kotlin
minOf(active.maxFrames, maxOf(active.expectedFrames, active.completedFrames + 1))
```

这避免了高 ISO 原目标为 3 帧时，被第一帧完成路径缩小成 2 帧。保留该修正，以及 baseFrames 有效帧门槛、Zone 共享补帧和 EV 域噪声处理。

不过新增的 frameCount(ISO) 断言不足以覆盖异步状态转换。需补测：首帧有效不缩小目标、无效统计仅占提交预算、baseFrames 未满足时终结为错误、补帧不越过 maxFrames、所有 Zone 目标共用追加请求、旧 generation 回调不会重复完成。

### 10.2 无障碍余项（P2，独立小批次）

`CanvasAccessibilityHelper.update()` 先替换 nodes，再清除已删除节点焦点；sendEvent 又通过新节点表创建节点，旧节点已不存在时 FOCUS_CLEARED 事件可能直接被跳过。`clearFocusForHostExit()` 当前搜索仅发现定义，没有调用。

建议在本轮六项主修复后单独处理：保留旧节点事件信息再发送清理事件，在宿主退出/隐藏时接入清理，补节点移除和跨页焦点测试。不要因为已经增加 focus API 就宣称 TalkBack 生命周期已完整验收。

本项是源码复核余项，未进行 TalkBack 真机复现。其他定位/无障碍改动也不能因本轮构建通过而全部标记验证完成。

## 11. 日志设计与真机验收

### 11.1 新日志应覆盖哪些状态

建议在状态转移/操作终态记录结构化字段，而不是逐帧输出：

- selectionOrigin、validationState、planId、requestedRoute、实际 logical/physical route。
- cameraGeneration、workflow/probe generation、相关输出配置标识。
- rawHardwareSupported、rawWorkflowConfirmed、rawSurfaceReady，避免合成一个 rawAvailable。
- 初筛矩阵结果、实际探测阶段和失败类别；不能把 UNKNOWN 写成 UNSUPPORTED。
- cacheRead/migration/retest/cacheWrite 的原因和策略版本。
- measureOperationId、requestedSource、deliveredSource、成功/未更新/失败。
- recoverAction、attempt/limit、旧/新 plan 与 route；确认后有任何变化都要说明是用户操作还是策略事件。

失败分类至少区分：用户取消/旧代回调、资源暂未就绪、RAW 数据不可用、必要元数据缺失、真实设备/会话错误、软预览健康告警。日志不要包含完整位置、用户备注、照片内容等无关私人数据。

### 11.2 验收前置步骤

1. 重新检查 git status/diff，确认没有覆盖其他任务的改动。
2. 执行单元测试、Lint、构建；运行新增集成测试，记录实际执行而非仅 UP-TO-DATE 的结果。
3. 核对设备、安装包版本、签名与构建身份。versionName 相同不代表代码相同。
4. 保留当前组合缓存作为迁移样本，不用清空应用数据/重装来绕过 R01。若需保留证据，只保存与相机组合相关的脱敏片段。
5. 修复较少且无阻断后，可在用户授权的测试流程内覆盖安装匹配签名的调试包。签名不匹配时停止，不卸载、不清数据；不得破坏参数记录和校准。
6. 界面大小和布局由用户观察，不要求截图；日志检查与交互行为验证仍需完成。

建议命令（路径和序列号必须重新发现，不照搬旧进程号）：

```powershell
.\gradlew.bat testDebugUnitTest --rerun-tasks
.\gradlew.bat lintDebug assembleDebug
& 'C:\Users\15449\AppData\Local\Android\Sdk\platform-tools\adb.exe' devices -l
& 'C:\Users\15449\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s <当前设备序列号> shell pidof com.lightmeter.rawmeter
& 'C:\Users\15449\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s <当前设备序列号> logcat -d --pid=<本次进程号> -v threadtime
```

占位符需替换后执行。不要 logcat -c 清除首次故障现场；进程重启后重新取 PID。只查看短尾部可能遗漏启动选择和初次降级，采集窗口必须覆盖目标动作。SDK/ADB 若不在上述位置，用 local.properties/本机 SDK 配置发现。

### 11.3 必做真机用例

| 用例 | 操作 | 日志与行为验收 |
| --- | --- | --- |
| 旧缓存迁移 | 保留现有 AUTO/YUV 缓存启动修复包 | 有迁移原因；有界验证 RAW；不能直接把旧 YUV 当作新确认 |
| 系统确认 | 完成系统 RAW 组合探测 | plan/route/config 与确认一致；后续启动复用有效确认 |
| 手动确认 | 手动探测 RAW 并接受 | 来源为手动，确认有效；重开后不丢失 |
| 普通失败 | 暗场/低纹理或可控测试故障 | 不要求每次成功；要求失败不返回 YUV 替代、不清缓存 |
| 隔离路径 | Normal/Zone 测量及全部重测 | 瞬时 RAW 后恢复常驻会话；失败点未覆盖；无逐点独立 RAW |
| 页面与生命周期 | 设置/镜头管理进出、Normal/Zone、后台恢复 | 用户选择与有效确认保留，不无故重复探测 |
| 真实故障 | 可控断开/权限变化或测试替身 | 资源正确释放、有界恢复/暂停，不自动换镜头或精度 |
| 主动低精度 | 用户明确选择 YUV/ISP | 选择被尊重，不被旧缓存迁移抢回 RAW |
| 返回导航 | 记录保存/取消后进入工具/设置再返回 | 不被已关闭 editor 吞掉返回 |
| 保存冻结 | 保存较慢时滑动参数、惯性滚动 | 草稿不可再变更；落盘内容等于保存快照 |
| 校准 | 多帧含波动，连续两次同参考校准 | 校准前融合值一致，结果不因帧序改变或重复操作漂移 |

软失败不一定能稳定由真实环境复现，必须配合测试替身。不要为制造故障加入生产无界重试，或启用会污染真实数据的常驻测试开关。

vivo/Android 16 是当前唯一已连接的现场设备，不代表其他厂商已验证。后续跨设备至少覆盖逻辑主摄、固定物理超广角/长焦、无 RAW 前摄，以及 API 28 和较新系统的恢复行为；缺少设备时明确标注未测，不推断兼容。

## 12. 推荐交付批次与完成标准

### 批次 A：组合缓存和确认策略

完成 R01、R02、R05；保留隔离结构。交付迁移策略、统一确认上下文、动作分发测试和缓存现场的迁移日志。

### 批次 B：参数记录生命周期

完成 R03、R06；保留后台文件事务和 token 隔离。交付返回导航、保存冻结、失败恢复、过期回调测试。

### 批次 C：融合校准

完成 R04；保留测量快照和来源分离。交付异常帧/乱序/重复校准测试，以及未应用用户修正的融合值验证。

### 批次 D：补充集成与设备验收

补齐 RAW 补帧状态转换测试；可独立修复第 10.2 节无障碍余项。执行第 11 节设备矩阵，列出已测、未测及阻断条件。

提交主题可以按上述批次拆分，但这里只给组织建议，不授权自动提交。每批需审查 diff，避免将已有用户改动当成本次新实现一起回退或覆盖。

### 最终交付必须说明

- 修改的实际文件与函数，以及各问题如何关闭。
- 各新增测试覆盖哪条调用链，哪些仍只能在真机验证。
- 构建、测试、Lint 的实际结果，不把已有 65 项警告误写成新增 65 项。
- 设备安装包是否对应本次构建；日志能否证明 requestedSource/deliveredSource 一致。
- 旧配置、参数记录、设备/镜头校准是否保持；是否有迁移及迁移原因。
- 所有未验证的厂商/系统版本和仍存在的风险。

**完成判据：不仅能在一台设备拍到一张 RAW，还必须证明确认后失败不会偷偷产生低精度替代、旧 YUV 缓存有可验证的恢复路径、手动确认与系统确认受同等保护，并且校准与参数记录没有引入新的数据错误。**
