# 89a0f35 复审：补充测试与下一轮实施交接

日期：2026-09-23。本文由测试与规划角色维护，供实现模型处理，不表示下面的修复已经完成。

## 1. 分工与本轮范围

- 当前助手负责审阅、测试、证据整理、修复规划和验收，不修改业务源码，也不代替实现模型提交功能补丁。
- 实现模型负责源码修改、编写回归测试，并交付改动说明和构建产物；测试方再独立检查。
- 用户负责界面观感与设备操作反馈。不要求界面尺寸截图，优先核对日志；不保存测试校准、不覆盖正式 Zone 点。
- 本轮开始时 HEAD 仍为 `89a0f354b635b1859d15a46a0ac440078ea96cca`，工作区干净，没有上次审阅之后的新代码。
- 因此没有重复覆盖安装，没有把 9 月 22 日旧 APK 的真机结果当作此提交的新验收结果。本轮只新增本文。

背景文档：

- [9 月 22 日真机证据与问题记录](DEVICE_TEST_RESULTS_AND_REMAINING_ISSUES_2026_09_22_ZH.md)。
- [曝光预览和 Zone 详细修复指导](EXPOSURE_PREVIEW_AND_ZONE_LATENCY_FIX_GUIDE_2026_09_22_ZH.md)。

本文补充最新提交的遗漏和验收要求，不覆盖历史记录。两份旧文档中已经完成的事项不应机械重做。

## 2. 本轮实际执行的检查

| 检查 | 结果 | 证据范围 |
| --- | --- | --- |
| Git 基线核对 | HEAD 未变化，开始时无未提交改动 | 没有新的实现可供覆盖安装验收 |
| `testDebugUnitTest --rerun-tasks` | 79 个套件、369 项，0 失败、0 错误 | 所有任务重新执行；不证明未覆盖的回调边界正确 |
| 校准模型临时诊断 | 无效 sample 仍被记录为成功 reading | 调用了当前编译的实际模型，未执行 Android 持久化 |
| Zone 模型临时诊断 | 待测点未结束会阻止下一次建点 | 调用了实际 ZoneMeterSession；UI 拒绝链来自代码审阅 |
| 真机测试 / 覆盖安装 | 本轮未执行 | 不改变手机数据或校准 |

Gradle 成功完成，有既存弃用警告及 SDK XML 工具版本警告，没有将这些警告认作业务失败。本轮没有重新执行 Lint；上轮同提交 `lintDebug` 通过的结果仅作背景。

临时诊断通过 JShell 调用已编译类，输入保留在执行记录中，没有向源码目录写入测试脚本。最初尝试受 Java 用户配置权限及 JShell 类路径加载问题影响，未成功；后经授权运行并使用显式 URLClassLoader 才得到以下结果。失败尝试不能算测试通过。

```text
CALIBRATION_CHECK usable=false measurements={RAW=9.8} samples={} failures=false complete=true
ZONE_CHECK pending=2 subsequentBeginReturnedNull=true markerCount=2
MODEL_DIAGNOSTIC_COMPLETE; no Android UI or persistence executed
```

### 2.1 临时诊断输入与解释

校准：构造 RAW sample，cameraId=`review-camera`、signature=null、测光校准前 EV=9.8、appliedUserCorrectionEv=NaN。将带此 sample 的 reading 交给来源列表仅含 RAW 的 `MeteringCalibrationRun.accept()`。结果 sample 不可保存，但 reading 被记录为成功、samples 为空、hasFailures=false、run 完成。

这是一条最小模型用例，不宣称产品默认只校准 RAW。实现方还必须测试多来源流程中“唯一返回 reading 的来源样本无效，其余来源捕获失败”的实际等价边界。

Zone：先创建并完成第 1 点，再创建第 2 点，模拟 Activity 在创建后拒绝测光且没有回滚，不调用完成或取消，然后尝试建第 3 点。实际模型返回 null，pendingMarkerId 留在第 2 点。手机上的恢复窗口与点击时序尚未实测；代码已明确存在创建点早于拒绝检查的顺序。

## 3. 第一优先级：修复 Zone 恢复窗口的待测点残留

优先级 P1。属于本次提前停止转圈改动带来的交互边界，不是要求恢复原来的长时间转圈。

### 3.1 触发链

1. Zone RAW 结果已经交付，Activity 设置 `state.measuring=false`，但 `zoneCameraRestorePending=true`。
2. 用户在恢复期间点击建点。`ZoneSystemView.beginMarker()` / `beginMarkerAt()` 只检查 measuring，因而继续。
3. `ZoneMeterSession.beginMarker()` 添加 marker，并设置 pendingMarkerId。
4. `MeterLayout.onMarkRequested()` 加入跟踪点、暂停跟踪，再通知 Activity。
5. `MainActivity.onZoneMeasureRequested()` 发现 meteringInteractionBusy，直接 return，没有调用拒绝后的撤销路径。
6. 后续恢复完成只更新恢复状态并恢复跟踪，没有清除这个没有实际捕获的待测点。下一次新增点被 pendingMarkerId 拦截；批量重测也会因 pendingMarkerId 非空而无法开始。

### 3.2 修改文件、位置与方法

路径前缀为 `app/src/main/java/com/lightmeter/rawmeter/`，行号按此提交，仅作定位。

| 文件 / 位置 | 实施要求 |
| --- | --- |
| `ZoneSystemView.kt:1390` 的 beginMarker、`:1435` 的 beginMarkerAt | 在调用 session.beginMarker 之前取得统一的 canStartCapture 判据；覆盖点按、触摸取点及对应无障碍入口 |
| `MeterLayout.kt:463` 的 onMarkRequested | 避免在实际拒绝后留下新跟踪点或未配对的暂停操作；明确请求接受/拒绝的返回值或撤销责任 |
| `MainActivity.kt:432` 的忙判断 | 不再静默拒绝已经产生副作用的请求；防线保留，但拒绝必须能回滚本次新增状态 |
| `MeterModels.kt` 或专用交互状态模型 | 将转圈状态和资源忙状态分开提供给 View；不要恢复“相机忙就必须转圈”的旧语义 |
| `ZoneSystemModels.kt` 的 pending 管理 | 原有完整取消路径优先复用；不能只置空 pending ID，却留下无值 marker 和 tracker 条目 |

可以采用“前置禁止 + 拒绝补偿”双层防线。不要让 View 直接持有 Activity 私有状态；通过已有 listener/状态模型传递一致的判据。不要为这项小修大规模重写 CameraController。

资源忙判断还应复查切镜头、手动组合、退出 Zone、参数记录和校准入口：这些路径有的仍只检查 state.measuring。逐项决定禁止、延后或安全取消，不能只补测光按钮而放开其他冲突操作。

### 3.3 实现方必须补的回归测试

- 有效结果已交付、恢复尚未完成：圈停止，但建点不会写入 pendingMarkerId。
- 请求在预检查后仍被控制器拒绝：marker、tracker、pending ID 和暂停状态完整回滚。
- 恢复完成后的下一次点测可成功；全部重测可启动。
- 恢复窗口连续点击、长按、切页面、无障碍触发：无重复捕获、无孤立待测点。
- 旧操作的恢复回调不能解除新操作的忙锁。

纯模型断言之外必须覆盖 View → MeterLayout → Activity 接线。若测试环境缺少 Android UI 支持，先提取可测试交互策略，再用真机日志验证实际入口。不能用一个只检查 Boolean 的单测宣称整条链已覆盖。

## 4. 第一优先级：校准严格区分无效样本与旧接口调用

优先级 P1，涉及可能覆盖用户校准的正确性风险。本轮未写入用户校准。

### 4.1 仍存在的漏洞

`MeteringCalibrationRun.accept()` 先把 EV 放入 successfulMeasurements，再过滤 calibrationSample。`CameraCalibrationStore.updateUserCorrections()` 用 samples.isEmpty() 判断是否采用旧兼容算法。因此新流程全部样本无效时，恰好落入旧接口分支，新增保护失效。

目前“部分样本有效、部分无效”的保存过滤有进展，但不能据此判定“全部无效”已安全。

### 4.2 修改文件、位置与方法

| 文件 / 位置 | 实施要求 |
| --- | --- |
| `MeteringCalibrationPlan.kt:56` 的 accept | 新校准流程先验证 sample，再登记成功来源。无效 sample 应记录失败/重采，不能仍占用 successfulMeasurements |
| `MeteringCalibrationCoordinator.kt` 的 completion | 明确传递可信来源、失败原因及是否允许保存；全部失败不得报告成功完成并进入保存 |
| `CameraCalibrationStore.kt:240` 的 measuredValue | 不能再以空 samples 区分旧调用和新流程。建议新旧入口显式分离，或增加明确的 evidence policy；新流程强制要求样本 |
| 同文件 updatedStream / 历史保存 | 无效来源保留原 correction、signature、response；全部无效不得创建伪成功历史或推进有效校准版本 |
| `MainActivity.kt` 的校准结束展示 | 显示实际保存来源和失败来源，避免保存被跳过却提示“校准成功” |

兼容旧调用可以保留，但必须是显式兼容入口，不允许新链路静默落入。不同设备/镜头按原作用域保存；两类校准保持独立，不新增固定 EV 修正。

### 4.3 测试矩阵

| 输入 | 预期 |
| --- | --- |
| 唯一 sample 缺签名、含 NaN 或缺 cameraId | 不登记为成功样本，不覆盖旧记录 |
| 多来源中全部样本无效 | 明确失败，不写校准，不新增成功历史 |
| RAW 样本无效，其他来源捕获失败 | 不得因 samples 为空进入旧公式 |
| 一个有效、一个无效 | 只按产品既有规则保存有效来源，保留无效来源旧记录并提示部分失败 |
| sample 与来源或 cameraId 不符 | 拒绝保存，不跨镜头写入 |
| 显式旧兼容调用 | 仅该入口按既定兼容规则运行，新流程不可触发 |
| 重复校准 | 不重复叠加旧 offset/response，两类校准不混用 |

建议扩展 MeteringCalibrationPlan/Coordinator 的现有测试，并补保存策略和历史记录的测试。首次设备验证只观测读取与状态；真正写校准需要使用隔离测试数据或另获用户明确同意，不能覆盖用户正式记录。

## 5. 第二阶段：补齐 RAW 确认身份与手动确认边界

优先级 P2，发布前必须复查。保持用户“确认后不因运行时质量波动自动降级”的要求，不能以加强身份为由每次重开都重探测。

### 5.1 实际路由身份

- `CameraController.openCamera()` 使用 descriptor.cameraId 作为 activeCameraId，并将其写进 confirmation.routeId；它不是实际 HAL 连接路径。
- `CameraOpenConfigurationResolver.kt` 已区分 descriptor 与 route；应从实际 route 构造稳定身份，包含打开 ID、配置 physical ID、route kind 和必要输出配置。
- `CameraWorkflowConfirmation.kt`、`CameraCombinationSelectionStore.kt` 同步使用这个稳定身份；缓存迁移明确未知旧身份的处理，不伪称已经验证。
- 不把每次 session generation 纳入永久确认身份，否则正常重开与 Normal/Zone 切换会错误失效。
- 模式间不同会话 profile 应绑定组合定义中的对应阶段，不要求 Normal 与 Zone 的所有 surface 完全相同；两者本来就是已确认工作流的不同阶段。

验收：同一实际路径重开保持确认；不同实际连接方式/关键输出配置不继承旧确认；旧自动 YUV 缓存能按规则重新筛选 RAW；真实探测成功后持久化生效。

### 5.2 手动确认仍需集成测试

`probeManualCombination()` 先异步 post 成功 planId，再通知 UI；`acceptManualCombination()` 即便缺少对应成功凭证也会保存选择，而重开缓存路径会把 RAW 选择视为已确认。正常手动流程通过，不等于取消/过期/快速点击安全。

建议成功凭证同时包含 probe generation、实际路由身份和 plan，先在相机线程确立，再允许 UI 接受。凭证无效时拒绝保存，而不是保存 unverified 选择后在下一次重开自动获得确认。

验收：取消后迟到成功、连续探测同一 plan、切镜头后旧回调、UI 快速接受、未验证选择重开，均不能获得错误确认。此项为静态风险与测试要求，本轮未实际触发手机竞态。

## 6. 性能工作不能被提前停圈替代

当前 `MeteringPreviewBaselineCoordinator` 仍等待两个稳定结果，超时 1200 ms；主要 AE 等待逻辑未改。9 月 22 日约 0.86–1.20 秒新增等待属于旧 APK 测试基线，不宣称在本次提交上再次测得同样数字。

实施顺序仍按旧详细指导：

1. 先完成独立资源锁与结果反馈，修复第 3 节回归。
2. 增加 Normal/Zone 共用阶段计时：请求接受、中性 AE 提交/到达、RAW 配置、结果交付、UI 停圈、恢复完成、曝光目标提交/生效。
3. 分离 desired preview selection 与临时 neutralization；恢复完成显式应用最后目标，不依赖偶然回调。
4. 根据证据优化 AE 条件或限定快路径，先验证测光一致性。不能直接删除等待、减掉必要 RAW 帧数或合并 HAL 隔离会话。

若实现方只完成 UI 修复，交付说明应写“提前反馈完成，捕获前耗时尚未优化”。没有实际 applied-result/纹理关联证据时，不把 request submitted 叫作画面匹配完成。

## 7. 已有正确改动：保留并补验收

- `RawLightMeter` 已过滤批量点的非有限 matchScore，阻止纯 geometry 回退更新；保留这项保护。后续补参考新鲜度、几何身份、完整 ROI 边界和部分失败计数；不要为了减少未更新比例放开回退写入。
- `CameraDistanceCaptureCoordinator` 已检查 device/session/Surface 对象身份，并取消旧释放任务；保留。补同 camera generation 换 session 的调度测试，真机观察旧会话释放警告是否消失。
- `ParameterRecordToolView.resumePage()` 不再因瞬态 rawAvailable=false 清除 recordRaw 偏好；保留，复查 UI 展示及实际捕获可用性是否分离。
- 已确认 RAW 的运行时保护、旧自动 YUV 缓存迁移、补帧上限、有限恢复预算均保留。不因本轮新增问题回退整个提交。

## 8. 分批交付与测试方验收顺序

建议实现方分小批交付，不在一批中同时重写校准、相机会话和曝光策略。

| 批次 | 实现交付 | 测试方关闭条件 |
| --- | --- | --- |
| A | Zone 创建前忙锁与拒绝回滚；校准空证据保护；对应回归测试 | 两个 P1 的正反用例均覆盖，原功能与数据保护通过 |
| B | RAW 实际身份、手动成功凭证、缓存迁移测试 | 同路由不误失效，异路由不继承，迟到回调不误确认 |
| C | 曝光目标调度、阶段埋点 | 结果后停圈与真实资源占用分离，所有阶段可区分 |
| D | 经验证的 AE 优化 | 准确性/重复性不劣化，阶段耗时确实改善，安全慢路径仍可用 |

每批交付必须包含：提交号或明确 diff 范围、改动文件与函数、回归测试名称及实际结果、未完成事项、需要设备验证的条件。若提供 APK，附 SHA-256、签名和构建来源。

测试方先看源码与测试，再判断是否覆盖安装。安装使用保留数据方式，不清缓存、不卸载。界面由用户观察，日志由测试方比对；需要操作时再发送明确且有限的一组步骤。

设备验收重点：恢复期间快速点击、结果交付后停止转圈、关闭/开启预览 A/B、前后台和镜头切换、RAW 确认保持、共享 RAW 批量点匹配。低纹理和越界点必须作为失败输入，不仅测静止高纹理场景。

## 9. 当前结论

当前是“部分修复合理、仍有两个优先正确性问题、主要性能优化未完成”。369 项原测试全通过不等于这些新增边界已经覆盖；模型级诊断已提供补充证据，但尚不能替代真机 UI、持久化及跨厂商验收。

下一步由实现模型先处理批次 A，测试方在收到新改动后继续审阅和回归。本文不授权实现方覆盖用户数据、推送发布或改变既有 HAL/校准设计。
