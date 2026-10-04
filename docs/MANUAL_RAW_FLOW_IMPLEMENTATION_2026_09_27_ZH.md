# 手动 RAW 重验与接受恢复流程：实施记录

更新日期：2026-09-27。基于 `7ff0254` 后的既有未提交改动继续实现，保留用户和其他模型的工作。此前审阅报告描述的是修复前状态，本文记录本次实际实现，不重写旧测试结论。

## 1. 本次修改范围

本次处理旧组合缺失时的误重验、接受后的会话恢复、失败重试、通知所有者与实际探测路径绑定。不修改曝光算法、AE 等待策略、校准值、预览几何、HAL 隔离进程或会话划分；C/D 性能优化不属于本次修复。

### 1.1 旧组合缺失不再借用系统候选

- 新增 `ManualCombinationIntentPolicy.kt`，分开原记录、可用原 plan、显式新探测和待重新选择状态。
- `CameraController.openCamera` 仅在原 plan 确实存在时发起原选择重验。未来 schema 或已删除 plan 不选择其他系统候选冒充原组合。
- 无法映射原选择时保留记录和手动模式，使用 PREVIEW_ONLY 作为安全预览，阻止测光并提示主动重新选择。desired resident 同样保持安全预览，不在 session configured 后偷偷切回 RAW 候选。
- 显式新探测与旧失效记录分离；新探测的候选在实际打开后再次核对，不存在就失败，不自动替换。
- `CameraCombinationSelectionStore` 的存储依赖可以在单测中注入，实际生产仍使用原 SharedPreferences 和环境标识。没有更改存储键或清旧数据。

### 1.2 探测路径在真实打开时绑定，而非成功通知时补签

- `ManualCombinationVerificationCoordinator.bindRoute` 在实际 open configuration 确定后绑定选择镜头、路径和 camera generation。
- 允许一次有意的关闭旧相机再启动新探测；探测开始后外部 close 取消该探测，不继续沿新路径签发旧成功。
- `CameraCombinationWorkflowProbeRunner` 为手动探测提供相机线程终态回调，阶段配置和阶段推进时核对 owner/route/generation。UI 只接收已经建立好的证据。
- 保留原 RAW 时间戳配对、元数据和轻量质量检查，没有增加全图检测或饱和比例否决。
- 系统探测回调也核对探测起始的选择、路径、模式和 generation，避免迟到成功写入另一路径的系统缓存。

### 1.3 接受、保存与恢复分别处理

- 新增 `ManualAcceptanceTransaction.kt`，明确 Accepted、RetryRequired、Cancelled，失败/取消结果携带 approvalSaved。
- 在相机线程核对证据、保存用户批准，再等待 resident 会话可用。保存使用凭证内的选择键和路径，不使用 UI 当前镜头临时拼装证据。
- APPLYING 期间不允许新捕获；完成时再次校验证据、目标会话和归属。预览请求提交失败不会报告接受成功。
- 恢复失败保留已保存的人工批准，不转换成 YUV 工作流；另行标记当前相机会话不可用。
- 接受恢复设 8 秒上限，整体手动探测设 20 秒上限；这些是挂起操作的终止保护，不是普通测光 AE 超时，也不会改变测量精度。
- 相机错误、预览错误、配置失败、关闭、停止、缺设备和调度拒绝均进入终结处理。事务取走后移除 timeout，终态只投递一次，迟到成功不能覆盖失败。
- SharedPreferences.apply 仍是异步落盘；approvalSaved 表示保存调用已接受，并非新增加了同步磁盘耐久性保证。

### 1.4 重试不再重复提交已失效凭证

- `MainActivity` 在接受失败后清除旧 evidence，关闭旧接受按钮，提供“重新验证”。它重新启动同一个候选，产生新的 probeId，再次检测并由用户确认。
- 已保存批准但恢复失败时明确说明选择仍保留；暂时关闭不会把该选择改回系统模式。
- 对接受回调、重试按钮和取消按钮核对 UI 代次和前台状态；下一候选不能在接受处理中被点击切走。

### 1.5 通知与生命周期归属

- 重验 request 在投递前构造成不可变对象，携带 selectionCameraId、ownerEpoch、planId 和 canStart。
- 控制器在投递、UI 开始和相机线程真正执行时核对当前 request；相同镜头 ID 不再足以复用旧弹窗。
- 暂停时关闭验证弹窗；旧回调不能关闭新选择器或修改新操作的 accepting 状态。
- 9 月 27 日复核补充：owner 代次改用 AtomicLong；协调器忽略迟到的更旧 invalidation，避免主线程和相机线程交错时丢失递增或取消新 owner。

## 2. 新增/扩展的测试

| 测试文件 | 验证内容 |
| --- | --- |
| `ManualCombinationIntentPolicyTest.kt` | 缺失原 plan、可重验原 plan、显式新选择、未来记录、空候选、同镜头旧通知 |
| `CameraCombinationSelectionStoreTest.kt` | 实际生产序列化读写、环境变化、未来 schema 不覆写、缺 origin/route；原记录→探测→人工保存→恢复失败→新探测；取消后原记录不变 |
| `ManualAcceptanceTransactionTest.kt` | timeout/error/close/迟到 success 仅一次终态；未保存的取消不能声称已保存 |
| `ManualCombinationVerificationCoordinatorTest.kt` | 完成前必须绑定路径；不能重绑定另一条路径/代次；接受与恢复归属；旧 probe/旧 invalidation 不破坏新操作 |

测试使用内存 SharedPreferences 替身和纯协调组件，不读取或改写用户设备数据。它们覆盖生产策略、序列化和状态组合，不等同于模拟了完整 Android Camera2 HAL；实际厂商回调和设备故障仍需要真机验证。

## 3. 验证记录与明确边界

- 9 月 23 日完成版本：83 个套件、393 项测试，全部通过；Lint 0 错误、65 警告，assembleDebug 成功。
- 当时 APK SHA-256：`1594befc12f34f8010785768b608aa2835c6f908a087185bcbd5d332c3a8bd60`。
- 当时覆盖安装因工具自动授权服务异常被拒绝执行，**不是安装成功或安装失败的设备结果**；手机未因此次命令更新。
- 9 月 27 日继续时，该旧测试包哈希未变；ADB 服务可正常启动，但 `adb devices -l` 没有列出设备。尚不能进行本版本实机验收。
- 9 月 27 日包含 AtomicLong/乱序 invalidation 修复的最新构建：`testDebugUnitTest lintDebug assembleDebug` 成功；83 个套件、394 项测试，失败和错误均为 0；Lint 0 错误、65 警告；`git diff --check` 通过。Lint 警告尚未在本次修复中全部处理。
- 最新 APK：`app/build/outputs/apk/debug/app-debug.apk`；SHA-256：`3f2eba92ed0c278b6e4c2726375703af101d17d8a8ff9210bb1c07232c4bb93f`。
- 9 月 28 日手机接通后，以上 APK 在 vivo V2405A（PD2405，Android 16）使用 `adb install -r` 覆盖安装成功，设备显示应用更新时间为 2026-09-28 18:54:01，原应用数据未清除。设备 `base.apk` 的 SHA-256 与本地包完全一致。
- 启动后主摄 `0` 的 `raw_split_v1` 候选使用 `RAW_ONLY`，没有观察到启动异常。自动模式下 Normal 连续两次 RAW 测光均为 `quality=ACCEPT`，RAW 捕获日志耗时分别为 223.8 ms、177.6 ms；两次之后预览仍为 `RAW_ONLY`。
- 切换 Zone 后，常驻跟踪预览为 `COMPATIBLE` 且 `yuv=true`；点测时临时切到 RAW，完成后恢复跟踪预览。第一次 `tapToResultMs=300`、`restoreMs=211`、`tapToReadyMs=512`；回桌面再打开后，保留 Zone 和主摄，第二次 `tapToResultMs=302`、`restoreMs=198`、`tapToReadyMs=500`。两次 RAW 质量均为 `ACCEPT`，没有记录到 RAW 不可用、自动降级、AndroidRuntime 崩溃或 ANR。
- 手动重验由用户观察画面并确认正常：19:18:54.383 开始 `probe=1`、`plan=raw_split_v1`、`owner=7`；19:18:54.427 绑定实际 `LOGICAL_AUTO` 路由和 `generation=5`；19:18:56.464 探测成功；19:18:58.366 记录 `Manual acceptance restored`，常驻会话为 `RAW_ONLY`。用户反馈没有重复验证、卡住或 RAW 不可用。
- 接受后 Normal RAW 测光成功，随后 Zone 多次点测均记录 `RawLightMeter ... completed`、`quality=ACCEPT` 及 `Zone RAW latency ... resultDelivered=true`，每次都恢复 `COMPATIBLE` 跟踪预览。再次回桌面打开后未出现新的手动 probe，主摄和 Zone 保持；19:21:30.933 又完成一次 RAW 捕获，`tapToReadyMs=381`。
- 随后尝试探测中回桌面：`probe=2` 于 19:23:01 启动并绑定路径，19:23:03 完成但没有接受记录；重新打开后 `probe=3` 于 19:23:16 独立启动并绑定相同路径，19:23:18 成功，19:23:22 人工接受后恢复 `RAW_ONLY`。其后的 Zone 多次 RAW 点测成功。用户反馈没有旧弹窗覆盖、重复接受、持续恢复或 RAW 不可用。由于 `probe=2` 已在约 2 秒内完成，本次没有证明“HAL 探测仍在进行时被取消”的精确竞态；只验证了旧完成结果不干扰后续新探测。
- 本机已验证手动组合的正常探测、人工接受、保存后会话恢复与重开复用。接受恢复失败、超时、缺失原 plan、未知 schema 等故障分支只有单测覆盖，未在用户手机上人为制造故障。曝光预览开启时的响应速度也未在本轮重新测量。
- 此前 9 月 23 日的 13 次 RAW、11 次 Zone 成功日志属于上一份异步接受测试版 `396b5129…`，不能算作本次完整流程修复的实机证据。

## 4. 实机验收步骤与剩余项目

1. 已完成：核对当前包，使用 install -r 保留数据覆盖安装，并核对设备实际 APK SHA-256。没有卸载或清数据。
2. 已完成自动组合基线：Normal 2 次、Zone 临时点 2 次（含一次回桌面再打开），均成功走 RAW。
3. 已完成手动组合正常路径：重新检测原 RAW 组合，用户确认画面，接受后 Normal 与 Zone 均成功测光；回桌面再打开后 Zone 仍能使用 RAW，未重复探测。
4. 已尝试检测后回桌面并重新检测同一候选；新探测成功且可测光。尚未实机命中 HAL 探测仍在运行时恰好中断的竞态。
5. 原 plan 缺失、未知 schema、保存后恢复失败和精确竞态先使用测试夹具或专用测试设备；不为制造输入擅自修改用户手机偏好。
6. 若未来出现挂起，应核对 20 秒探测终态、8 秒接受终态和明确重试入口，不能以自动清缓存/换 YUV 掩盖问题。

## 5. Git 与材料边界

源码、测试和本记录随本次变更在当前分支新增独立 Git 提交；具体提交号以 Git 日志为准，未推送。用户新增的 `REVIEW_ASYNC_ACCEPTANCE_FOLLOWUP_2026_09_23_ZH.html` 保持原样，不覆盖、不删除；该 HTML 是此前报告副本，不代表本次实施后状态。

本记录位于 docs，不放入 APK 的 assets/res。当前设备验收只覆盖上文列出的场景；未知 schema、缺失 plan 和恢复失败等分支仍需专用测试设备或测试夹具验证，不能标注“所有设备/故障场景全部通过”或据此直接发布。
