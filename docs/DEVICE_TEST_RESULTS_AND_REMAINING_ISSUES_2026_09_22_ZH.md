# 光档：覆盖安装及继续测试结果

日期：2026-09-22。本文记录实际设备测试及剩余问题，不表示全部修复项或跨厂商兼容性已经验收。

## 1. 测试基线与边界

- 仓库 HEAD：`44b544cad04fc69588c13c76edd22aac5a1543bf`，加当前未提交源码和新增测试。只检出 HEAD 不等于本次 APK。
- 设备：vivo V2405A / PD2405，Android 16。
- 应用：`com.lightmeter.rawmeter`，0.4.0 / versionCode 9，debug。
- APK SHA-256：`a9a9f3ca087e2eb065b01f2447786c8abc1c8fca7e979ce45e203a684a4a0759`。安装后及晚间继续测试前，均与设备文件核对一致。
- 新旧 APK 签名相同，已通过 `adb install -r` 保留数据覆盖安装。未卸载、未清数据、未删除相机组合缓存。
- 强制执行 `testDebugUnitTest --rerun-tasks`：79 个套件、369 项测试、0 失败/错误。`lintDebug`、`assembleDebug` 成功；Lint 65 项警告、0 错误。
- 用户操作镜头、测光、手动组合和 Zone 测试；助手核对日志、缓存与 APK，并执行启动和部分前后台操作。
- 未截图、未评估界面尺寸、未写入校准、未修改运行时代码、未提交 Git。

## 2. 已获得的通过证据

| 用例 | 设备证据 | 能得出的结论 |
| --- | --- | --- |
| 旧 YUV 缓存迁移 | 15:27:26 `Legacy automatic combination cache needs RAW re-validation route=0@2 cached=stable_yuv_v1`，随后选择 raw_split_v1 | 当前设备主摄旧缓存可恢复 RAW，无需清数据 |
| 系统 RAW 持久化 | `combination_bc22_system_auto_plan=raw_split_v1`，origin=SYSTEM_PROBE | 本次探测结果成功保存 |
| 下午 Normal/Zone 测光 | 15 次 RAW 测光完成，其中 9 次走 Zone 隔离事务；全部交付并恢复 | 正常测光和隔离会话基本链路可工作 |
| 晚间冷启动 | 20:58:50 直接选中 raw_split_v1 / RAW_ONLY，没有再次完整探测 | 经进程重新启动仍能恢复主摄 RAW |
| 主摄弱光高 ISO | ISO 25600 的前三次完成 3、4、3 帧，约 408.4、477.5、392.8 ms | 原 3 帧目标没有缩小；额外一帧有界完成 |
| 弱光后恢复 | 紧接着 ISO 128/112 的两次完成 1 帧，约 162.5/163.1 ms | 弱光测量后仍正常使用 RAW |
| 超广角 | `0@4` / FIXED_PHYSICAL，交付 RAW 结果 | 此机该物理镜头本轮正常 |
| 长焦 | `0@3` / FIXED_PHYSICAL，包含 2 帧、3 帧 RAW 完成 | 此机该镜头及多帧策略本轮正常 |
| 自动逻辑镜头 | `0` / LOGICAL_AUTO，交付 RAW 结果，旧 YUV 缓存更新为 RAW | 本轮逻辑路径成功，不等于验证所有内部物理切换 |
| 手动确认 | 主摄手动缓存 raw_split_v1，origin=MANUAL | 手动接受与保存链路可工作 |
| 手动后台恢复 | 21:03:13 重开主摄 RAW_ONLY，随后两次 RAW 完成 | 正常重开后没有回到 YUV |
| Zone 全部重测 | 8 次批量完成，均 frames=1，每批 2 或 3 个点 | 确实共享一张 RAW；匹配资格未完全通过，见 T01 |

下午普通 RAW 处理约 171–193 ms，Zone 从点击到恢复可用约 353–489 ms。晚间曝光预览相关路径明显更慢，不能只用较好数字代表整体性能。

晚间第一阶段关键日志摘录（进程 27913，20:58 冷启动至 21:05，尚不含第 8 节 A/B 测试）统计：

- 普通 RAW 测光完成 37 次，Zone 批量完成 8 次。
- `RAW metering failed` 0 条，兼容流测光 started 0 条，`FATAL EXCEPTION` 0 条。
- AF 释放警告 6 条，中性预览 AE 等待超时 13 条。
- 批量逐点日志中 `match=geometry` 9 条。

以上是所采集日志窗口的统计，不是长期故障率。早期较大日志输出曾被截断，最终数字已使用过滤后的未截断摘录重新计算。

Zone 常驻 COMPATIBLE/YUV 跟踪会话属于保留的隔离设计，不能把该会话本身当作 RAW 测光降级。判断实际来源应看捕获及交付日志。

## 3. T01：P1，批量重测把未匹配点算成成功

### 3.1 设备证据

以下保留关键字段，省略时间后的进程号及不相关字段：

```text
21:04:53.380 RAW Zone batch started: points=3 frames=1 ... references=1
21:04:53.547 RAW Zone point=1 ... match=geometry
21:04:53.549 RAW Zone point=2 ... match=geometry
21:04:53.603 RAW Zone point=3 ... match=0.630
21:04:53.604 RAW Zone batch completed: requested=3 completed=3 frames=1

21:05:19.022 RAW Zone batch started: points=2 frames=1 ... references=0
21:05:19.190 RAW Zone point=1 ... match=geometry
21:05:19.191 RAW Zone point=2 ... match=geometry
21:05:19.192 RAW Zone batch completed: requested=2 completed=2 frames=1
```

用户反馈“更新正常”与 UI 完成状态相符，但不能证明所有点仍对应原被测物。本轮未证明这些 EV 一定错误；已经证明未完成特征匹配的点仍进入成功结果，存在覆盖原测量的风险。

最后一批（21:05:46）三个点均成功匹配，分数为 0.961、0.915、0.964，证明匹配路径可以工作；不能因此忽略前面几何回退的批次。

### 3.2 修改位置

以下主文件路径前缀均为 `app/src/main/java/com/lightmeter/rawmeter/`，行号只供当前工作区定位：

- `RawPreviewRegistration.kt:122`：参考缺失/无效时直接返回 approximate；第 196 行低相关性也回退 approximate，其 matchScore 为 NaN。
- `RawLightMeter.kt:506` 起：解析批量目标后，无论匹配成功还是 geometry 均继续 analyzeRaw 并收集统计。
- `RawLightMeter.kt:716` 起：仅检查统计帧数门槛，没有检查匹配资格，即构造 ZoneMeteringResult。
- `CameraController.freezeZoneReference()`：新参考创建失败时可能沿用旧参考，后续需明确参考时间与几何身份。

### 3.3 修复建议

1. 为批量点返回显式解析状态，例如 MATCHED、NO_REFERENCE、LOW_CONFIDENCE、OUT_OF_VIEW、STALE_REFERENCE，而不是只用 NaN 控制日志文字。
2. 更新既有点时，只有通过匹配、边界和代次校验的目标才写回；其余保留旧 EV，计入“未更新”。失败点不影响同批合格点。
3. 区分普通点测的几何估计和“更新已有跟踪点”的身份要求，不要全局删除几何坐标映射。
4. 保留同一张/同一组 RAW，禁止为失败点逐点重拍。单点匹配失败也不得触发 RAW 能力降级或清除确认。
5. 新增回归测试：无参考、低纹理、低相关性、参考过期、越界、部分通过、全部不通过；断言原值保留及 updated/skipped 计数正确。

本问题在既有代码中已经存在，本轮是真机证据补充，未经版本比对不能声称由当前 diff 新引入。

## 4. T02：P2，延迟 AF 回调访问已关闭会话

重复日志：

```text
Unable to release the autofocus trigger
java.lang.IllegalStateException: Session has been closed; further changes are illegal.
at CameraDistanceCaptureCoordinator.submitAutoFocusRequest(...:131)
at CameraDistanceCaptureCoordinator.scheduleAutoFocusRelease...(...:145)
```

`CameraDistanceCaptureCoordinator.kt:135–159` 的延迟任务仅比较 cameraGeneration，却捕获旧 session/device/preview。Zone 或组合探测可在同一 cameraGeneration 内替换会话，旧任务仍通过检查。

建议：在会话替换/停止时取消待执行 AF 释放任务，并校验 session 身份或会话代次，确认捕获的 device/Surface 仍有效。不要仅压掉警告，也不能合并 HAL 隔离会话来掩盖问题。

补测试：同 camera generation 替换 session、连续快速采样、停止后旧任务到达、新会话不能被旧任务影响。此次异常已被捕获，未引起所观察到的崩溃或 RAW 降级。

## 5. T03：中性 AE 经常等待至上限（追加对照后提升为 P1 体验问题）

```text
21:04:43.122 Preview request submitted ... manualExposure=null ... neutralBaselineGeneration=11
21:04:44.322 Timed out waiting for neutral preview AE; continuing measurement
21:04:44.322 Neutral preview baseline finished: reason=timeout ... waitMs=1201
21:04:44.813 Zone RAW latency tapToRawConfigMs=1304 ... tapToReadyMs=1696
```

还观察到稳定结束但 waitMs 为 1084、1005、924、969 的情况。批量 RAW 自身约 170–225 ms；部分 Zone 事务总耗时约 1.3–1.7 秒，主要等待在中性预览基线恢复，不应直接归咎于 RAW 解码或 HAL 会话配置。

检查 `MeteringPreviewBaselineCoordinator.kt`、`PreviewBaselineState.kt`、预览请求 tag/result 关联及曝光预览恢复链。现有日志只能定位阶段，尚不能证明是 AE 收敛慢、判据过严、回调身份关联遗漏或组合原因。

下一步补充诊断：tagged AE 结果到达时刻、AE 状态、曝光参数稳定性、采用的结束判据。不能只删除等待或盲目缩短超时，以免曝光预览反过来影响测量。

## 6. 上轮静态审阅仍需关闭的边界

- 无效校准样本：`MeteringFusion` 可以标记不可保存，但 `MeteringCalibrationRun.accept()` 仍记录 measurement，`CameraCalibrationStore.updatedStream()` 随后可回落旧公式。应拒绝该来源校准或重采，不要静默写入旧算法结果。本轮没有执行校准写入。
- 确认作用域：`CameraWorkflowConfirmation` 使用的 routeId 实际来自面向用户的 descriptor.cameraId，不能区分该目录项下的 PUBLIC_DIRECT、FIXED_PHYSICAL、LOGICAL_FALLBACK，也未绑定输出配置。需要补齐身份及重开验证，不应只比 planId+目录镜头 ID。
- RAW 记录偏好：`ParameterRecordToolView.resumePage()` 仍会因为当前 rawAvailable=false 把 recordRaw 写为 false；Zone 常驻 YUV 或临时 RAW 工作流不应因此丢失用户偏好。本轮未操作记录设置验证这一项。
- 手动竞态：成功探测信息仍只保存 planId，并在异步 post 后通知 UI；需要代次/路由和取消、过期回调集成测试。正常路径成功不等于竞态路径已通过。

## 7. 未验收项目与收尾状态

未验证：真实 CameraDevice 故障/权限撤销后的有限恢复、RAW 真正失败后绝不替代的设备路径、跨厂商兼容、绝对测光精度、重复校准、参数保存事务及无障碍完整生命周期。软件纯策略测试不能替代这些集成测试。

测试产物：

- 关键日志摘录：`app/build/review-device-20260922/evening-camera-test-excerpt.log`，筛除了逐帧曝光预览等无关高频信息，保留捕获、匹配、恢复、时延和异常行；不是原始 logcat 全量备份。
- 覆盖前 APK：`app/build/review-device-20260922/installed-before.apk`。
- `build/` 中证据可能被 Gradle clean 删除，本文保留核心证据与统计。文档和日志均不在生产打包源集中。
- 当前设备保留测试 APK；用户按步骤把主摄设置为手动 RAW，未擅自改回系统选择。缓存中 0、0@2、0@3、0@4 的系统结果均已是 raw_split_v1。
- 临时 UI 控件树文件已清理，未产生截图，未删除用户记录或修改校准。

建议下一轮优先修 T01 和第 8 节的测量完成状态分离，再处理 T02、带验证地优化 T03；同时关闭第 6 节边界，再安排故障注入与跨设备验收。本轮只测试和记录，不实施源码修复。

结论：RAW 可用性、缓存迁移、高 ISO、多镜头、手动确认及后台恢复的正常路径表现良好；仍有批量点身份正确性和会话回调问题，不能标记为全部修复完成或发布就绪。

## 8. 追加：曝光预览开关 A/B 测试及转圈问题

测试时间：2026-09-22 21:15–21:20。仍为相同 APK、进程 27913。用户按顺序操作 Normal 关闭 3 次、开启 3 次，再操作 Zone 关闭 3 次、开启 3 次。要求尽量保持镜头、场景稳定，但不是实验室恒定光源测试。

### 8.1 用户反馈与需求

- Normal：用户确认开启后耗时明显变长，并反馈“直到画面曝光调整完成，转圈才停止”。
- Zone：用户确认两组存在差距，但主观感觉没有 Normal 的差异明显。
- 新的明确需求：有效测光结果完成后即结束测光转圈，随后单独匹配曝光预览。
- 本轮不依赖截图。日志未记录实际屏幕呈现时刻，不能把用户感受到的动画先后转换为毫秒级 UI 测量。

### 8.2 Normal 实测

| 顺序与完成时间 | 中性 AE 等待 | RAW 处理 | RAW 帧数 | 结果后曝光预览状态应用日志 |
| --- | ---: | ---: | ---: | --- |
| OFF 1，21:15:21.497 | 未进入该等待 | 375.5 ms | 3 | 不适用 |
| OFF 2，21:15:22.230 | 未进入该等待 | 389.8 ms | 3 | 不适用 |
| OFF 3，21:15:23.464 | 未进入该等待 | 376.0 ms | 3 | 不适用 |
| ON 1，21:15:29.535 | 876 ms，stable tagged AE | 314.5 ms | 3 | 21:15:29.544，约晚 9 ms |
| ON 2，21:15:31.137 | 856 ms，stable tagged AE | 320.5 ms | 3 | 21:15:31.147，约晚 10 ms |
| ON 3，21:15:32.971 | 860 ms，stable tagged AE | 316.0 ms | 3 | 21:15:32.979，约晚 8 ms |

关闭时 RAW 处理均值约 380.4 ms；开启时 RAW 处理均值约 317.0 ms，却额外等待平均 864 ms。开启组三次“基线等待 + RAW 处理”合计约 1.177–1.191 秒，还不含其他调度开销。Normal 尚无同等完整的点击到结果埋点，因此不能把 RAW elapsedMs 直接称为完整点击耗时，也不能将阶段合计当作精确 UI 耗时。

结论：这组数据支持“额外中性 AE 等待是主要新增延迟”，不支持“RAW 算法突然慢了三倍”。曝光预览状态应用日志不等于新亮度已经显示；缺少请求生效与 TextureView 消费帧的关联证据。

### 8.3 Zone 实测

| 顺序与 RAW 完成时间 | 中性 AE 等待 | RAW 处理 | 点击到结果 | 恢复预览 | 点击到可用 |
| --- | ---: | ---: | ---: | ---: | ---: |
| OFF 1，21:20:24.289 | 无 | 395.8 ms | 502 ms | 190 ms | 693 ms |
| OFF 2，21:20:25.876 | 无 | 395.0 ms | 508 ms | 203 ms | 711 ms |
| OFF 3，21:20:28.332 | 无 | 394.6 ms | 495 ms | 208 ms | 704 ms |
| ON 1，21:20:33.987 | 无，过渡样本 | 390.2 ms | 496 ms | 196 ms | 693 ms |
| ON 2，21:20:37.396 | 1201 ms，timeout | 393.5 ms | 1706 ms | 199 ms | 1906 ms |
| ON 3，21:20:39.973 | 878 ms，stable tagged AE | 395.7 ms | 1384 ms | 206 ms | 1590 ms |

所有样本均 3 帧。ON 第一笔捕获前没有观察到手动曝光预览已生效，其曝光预览应用日志在恢复完成后才出现，因此不能把它当作与后两次等价的稳定 ON 样本，也不宜只报告三次平均数稀释差异。

ON 后两次分解如下：

```text
ON 2：约 1201 ms 基线等待 + 约 111 ms 其他捕获前开销
      + 约 394 ms RAW = 1706 ms 结果交付
      + 约 199 ms 恢复 = 1906 ms 可用（整数舍入）
ON 3：约 878 ms 基线等待 + 约 110 ms 其他捕获前开销
      + 约 396 ms RAW = 1384 ms 结果交付
      + 约 206 ms 恢复 = 1590 ms 可用
```

这不是“会话创建需要 1.3 秒”的证据：`tapToRawConfigMs` 从点击开始计时，包含前置 AE 等待，必须先拆段。

### 8.4 T04：P1 交互问题，测光转圈与相机占用状态混用

代码证据：

- `MainActivity.onMeterReading()` 的 Normal 分支本来就先 `state.measuring=false`、刷新，再调用 `updateExposurePreviewFromMeter()`。不能只补一遍相同赋值就声称修复。
- `CameraController.deliverZoneRawResult()` / `deliverZoneRawBatchResult()` 先通知 restoring=true 再交付结果；此时有效结果已完成。
- `MainActivity.onMeterReading()` 的 Zone 分支及 `onZoneMeteringBatchResult()` 使用 `state.measuring=zoneCameraRestorePending`，所以结果出来后仍转圈，直到恢复结束。
- `state.measuring` 同时用于 Instrument/Zone 转圈绘制、触摸与无障碍入口拦截、Activity 发起测光判断及曝光预览选择门控。全局提前设 false 会把相机忙锁一起解除。
- Zone 单点恢复完成回调没有显式重新调用 `updateExposurePreviewFromMeter()`；本次日志最终有重新应用，说明其他更新回调可触发，但不应依赖这个隐含顺序。

期望行为：结果计算与所需质量/匹配验证结束后立即显示结果并停止“测光”转圈；相机恢复期间仍保留必要资源锁，以简短的“正在恢复预览/匹配曝光”状态表达；资源可用后显式应用最新曝光目标。曝光目标超时或不可用，不得撤销有效测光结果或把已确认 RAW 降级。

Normal 的用户观感还需通过 UI 提交、绘制和预览帧事件进一步量化。现有证据已足以安排状态分离，但不能断言 Normal 代码显式等待“匹配完成”才赋 false。

### 8.5 追加测试统计及材料

- 本轮 A/B 摘录：12 次普通 RAW 完成、6 次 Zone 隔离事务恢复、5 次中性基线等待结束（其中 1 次超时）、4 次延迟 AF 释放警告。
- 所采集窗口没有 `RAW metering failed` 或 `FATAL EXCEPTION`。本轮观察继续支持正常路径 RAW 保持可用，不替代故障注入测试。
- 摘录保存于 `app/build/review-device-20260922/exposure-preview-ab-test.log`；是过滤日志，不是全量 logcat。与第 2 节第一阶段统计分开，不混报为整个晚间完整统计。
- 21:20:50.910 观察到曝光预览 selection=null/manual=null；不据此保证后续 UI 开关状态。助手未主动改变用户开关和镜头设置。
- 未继续增加设备操作，未再次安装，也未实施源码修复。

实施文件、状态迁移、性能策略、回归测试及优先级见同目录 [详细修复指导](EXPOSURE_PREVIEW_AND_ZONE_LATENCY_FIX_GUIDE_2026_09_22_ZH.md)。第 5 节的阶段判断已被本轮对照进一步支持，但 AE 耗时的厂商内部原因仍未证明。
