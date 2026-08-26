# 历史记录、曝光预览、校准与摄影工具 UI 修改实施规格

状态：仅完成代码审查与修改规划，本文所列业务代码尚未实施
制定日期：2026-08-26
适用工程：`lightmeter a` 当前工作树
关联文档：`docs/REMEDIATION_AND_CAMERA_COMPATIBILITY_PLAN_ZH.md`、`docs/CAMERA_PIPELINE.md`
目标读者：后续接手实施、测试、审查和真机验证的模型或开发者

本文把本轮需求转换为可以直接执行的修改规格。除修正文档中已经确认的歧义外，实施者
不得自行改变产品含义。尤其不得把新状态继续堆进 `CameraController`、`MainActivity`、
`ZoneSystemView` 或其他已经很大的类；所有新算法、状态机和共享绘制逻辑必须按本文定义的
小组件边界拆分。

---

## 1. 本轮结论与问题答复

### 1.1 “快门速度过低”的解释

本文将“预览流的快门速度过低”解释为：曝光时间过长、预览帧率下降、画面拖影或卡顿。
目标是先把预览曝光时间控制在流畅范围，再提高 ISO；只有 ISO 已达到设备上限时才允许
快门小幅变慢。若即使如此仍无法达到目标亮度，应限制预览亮度并明确标记已钳制，不能为
了看起来更亮而把预览拖到几帧每秒。

当前实现与这个目标相反。`ExposurePreviewMath.manualExposure()` 会尽量维持当前预览 ISO，
主要靠延长快门吸收曝光变化；只有碰到传感器曝光时间上下限才改变 ISO。另外，
`CameraController.submitPreviewRepeatingRequest()` 会把上一帧的
`SENSOR_FRAME_DURATION` 当作新请求的下限。由慢快门切回较快快门时，旧的长帧时长可能
继续保留，造成“快门已经缩短但预览仍然慢”的现象。

结论：需要同时修改“ISO/快门分配策略”和“手动帧时长计算”，只改其中一个不完整。

### 1.2 为什么开启曝光预览后测光变慢

当前正式测光不能直接使用曝光预览帧，因为曝光预览使用的是用户选择的摄影参数，而正式
测光需要恢复到中性的相机自动曝光域。现有调用链是：

```text
用户点击测光
-> 清除曝光预览的手动曝光或曝光补偿
-> 重新提交 AE 请求
-> 等待 AE 连续稳定 3 帧，最长 1200 ms
-> 开始 RAW/YUV/ISP 正式测量
-> 测量结束后由界面重新应用曝光预览
```

因此变慢有三个叠加原因：

1. 多了一次“从摄影预览回到中性 AE”的等待；
2. 如果此前手动曝光时间很长，每个回调帧本身就更慢；
3. RAW 仍可能按 ISO 再采 1～3 帧，YUV 也有严格同帧配对和最多 250 ms 的等待。

不能通过直接拿曝光预览画面正式测光来消除等待，那会让读数随用户选择的光圈、快门、ISO
发生偏移。可行的优化是：给请求加 generation/tag，只等待真正属于中性请求的结果；在
满足稳定条件后由 3 帧降到 2 帧；重新计算帧时长；保留 1200 ms 安全超时，并记录分段耗时。

### 1.3 当前 YUV 是否已经参与校准

已经参与，但现有命名和存储会掩盖这一事实：

- 高精度模式的会话若有可用 YUV，RAW 校准结束后，所谓“预览流校准”实际优先取严格时间戳
  配对的 `YUV_PREVIEW`；
- 稳定模式强制 `ISP_PREVIEW`；
- 兼容模式有 YUV 时优先 YUV，YUV 不健康时再用显示预览；
- `CameraCalibrationStore` 把所有非 RAW 来源都写进同一个
  `compatibleCorrectionEv`，`YUV_PREVIEW` 与 `ISP_PREVIEW` 没有真正分开。

所以问题不是“完全没有校准 YUV”，而是“YUV 和 ISP 显示预览共用一项校准，且界面只叫
预览流”。YUV 的 Y 平面受厂商 ISP、tone curve、HDR、gamma、YUV range 和流配置影响；
屏幕 `TextureView` 又可能经过不同的裁切、缩放和显示处理。两者共享一个常量偏移并不严谨。

### 1.4 是否应根据模式校准不同来源

应该。推荐按模式生成明确的、顺序执行的来源计划：

| 用户模式 | 必校准来源 | 说明 |
| --- | --- | --- |
| 高精度 `AUTO` | RAW（可用时）→ YUV（可用时）→ ISP 显示预览 | RAW 是主路径；YUV 是自动降级路径；ISP 是最终保底且承担曝光预览显示域 |
| 稳定 `ISOLATED` | RAW（可用时）→ ISP 显示预览 | 该模式明确隔离 YUV，不为它临时建立 RAW+YUV 组合会话 |
| 兼容 `FAST` | YUV（可用且健康）→ ISP 显示预览 | 不创建 RAW 资源；两个处理后来源分别保存修正 |
| 无 RAW 设备 | YUV（可用时）→ ISP 显示预览 | 不显示虚假的 RAW 校准步骤 |

高精度模式有必要校准 YUV，因为它可能在 RAW 失败后自动降级到 YUV；但这里的“同时校准”
只能表示“一次向导依次完成多个步骤”，不能表示一个请求并发采 RAW 与 YUV。

### 1.5 是否需要同时多路请求

不需要，也不建议。Camera2 允许一个会话配置多个输出，并允许一个请求把帧送给一个或多个
目标 Surface；但是同一个 `CaptureRequest` 的曝光、增益和 ISP 控制是整次请求的设置，不能
给屏幕预览和 YUV 分配两套独立曝光。多流还会增加内存、带宽、CPU/ISP 压力，不同 HAL 对
非最低保证组合的稳定性也不同。

本项目已经有过全绿、黑白条纹和组合流异常历史，因此校准应采用保守策略：每个来源单独
完成，关闭和释放该阶段图像后再切换到下一安全会话。不要为了省几百毫秒重新引入
RAW+YUV 同时采集。

Android 官方依据：

- `CaptureRequest` 定义一次捕获的统一参数和目标 Surface；每个请求只能使用当前会话已配置
  Surface 的子集：<https://developer.android.com/reference/android/hardware/camera2/CaptureRequest>
- 多路流有非平凡性能成本，内存随流数增长：
  <https://developer.android.com/media/camera/camera2/multiple-camera-streams-simultaneously>
- 会话关闭后仍可能完成已经在途的请求，因此跨阶段必须使用 generation 和 operation ID
  拒绝旧回调：<https://developer.android.com/reference/android/hardware/camera2/CameraCaptureSession>

### 1.6 中英文适配审查结论

本轮涉及页面的大部分可见中文已有对应英文，`EV100`、`ISO`、`ZONE`、`NORMAL`、`f`、
`c`、`h`、`min`、`s` 属于通用符号，可在两种语言共用。现有问题主要不是漏翻译，而是：

1. 英文按钮比中文长，当前部分页面采用省略号而不是两行布局，字号增大后会更容易截断；
2. 倒易率结果的无障碍描述只有数字，没有本地化的小时、分钟、秒读法；
3. 校准界面把 YUV 和 ISP 都称为“Preview stream”，来源不清楚；
4. 新增的禁用原因、曝光钳制、AE 恢复状态和分来源校准步骤尚无中英文文案；
5. 自定义 Canvas View 的文字没有统一的适配器，部分页面各自实现缩放、截断和换行，行为
   不一致。

实施时必须同时验证中文/英文、左右手、横/竖屏、明/暗主题和至少 1.3 倍系统字体，不能只
在中文默认字体下调大常数。

---

## 2. 范围与非目标

### 2.1 本轮必须完成

1. 历史参数记录无 RAW 网格时禁用并置灰 ZONE；增大参数、EV100、备注和胶片字号。
2. 曝光预览改为 ISO 优先，并修正手动帧时长；减少正式测光前的中性 AE 等待。
3. 景深页面固定顶部红色指针，删除底部红色指针，增大指定文字。
4. 胶片选择页增大并加深宽容度和 ISO 信息。
5. 宽容度页增大轨道、档位、标记标签和按钮文字。
6. 倒易率页增大快门读数、单位和按钮文字；为结果增加分栏单位；实现动态最大输入。
7. 普通测光与 Zone 标点按钮物理左侧增加 EV100 小方框。
8. 将 RAW、YUV、ISP 显示预览的校准存储、应用和界面说明分开。
9. 完成中英文、无障碍和文档同步。

### 2.2 本轮不做

- 不改变 RAW Bayer 的测光公式，不做解拜尔。
- 不在本轮加入 YUV 五点灰阶 LUT；本轮先把单点 offset 按来源隔离。
- 不新增厂商名称分支或远程黑名单。
- 不迁移 CameraX。
- 不改变胶片倒易率数据库中的厂商数值，只改变允许选择的输入范围和显示方式。
- 不把 EV100 方框做成新的可点击按钮。
- 不增加上千行的新 View、Controller、Repository 或“万能工具类”。

---

## 3. 代码规模与组件边界

### 3.1 硬约束

- 新文件目标 50～250 行，原则上不超过 350 行。
- `CameraController` 只保留门面调用；曝光预览状态机和校准阶段切换不得继续内联扩展。
- `MainActivity` 只接 UI 事件和展示结果；不得继续拥有多来源校准流程状态。
- `ZoneSystemView`、`ReciprocityView` 只调用小型 model/renderer，不内嵌新的业务策略。
- 不建立含糊的 `Utils2`、`CommonHelper`；每个组件名必须反映唯一职责。

### 3.2 推荐的新组件

```text
history/
  RecordedHistoryCapability.kt       # 能否进入可重算 ZONE

camera/preview/
  ExposurePreviewPolicy.kt           # ISO/快门/帧时长纯计算
  MeteringBaselineCoordinator.kt     # 中性 AE 请求、帧确认、超时

calibration/
  MeteringCalibrationPlan.kt         # 按模式/能力产生顺序来源
  MeteringCalibrationCoordinator.kt  # 阶段状态机，不拥有 View
  CameraCalibrationModels.kt         # 分来源记录和迁移状态

ui/meter/
  Ev100Readout.kt                     # 普通/Zone 当前值解析
  Ev100BadgeRenderer.kt               # 两个主测光 View 共用绘制

reciprocity/
  ReciprocityLimitPolicy.kt           # 最终结果 24h 与精确数据截止
  ReciprocityTimeReadout.kt           # h/min/s 结构化字段
  ReciprocityTimeRenderer.kt          # 结果数字和单位绘制
```

项目当前包结构较平，实施者可先留在 `com.lightmeter.rawmeter` 包内，但仍应使用上述文件和
职责边界；不要因为没有建立子 package 就把逻辑塞回旧大类。

---

## 4. 工作包 A：历史参数记录 ZONE 可用性与文字布局

### 4.1 当前行为

- `RecordedMeteringSession.from(record)` 只看原始模式或是否有历史标点，可能默认进入 ZONE。
- `RecordedMeteringRenderer.targetAt()` 总会返回 `ZONE_MODE`。
- `ParameterHistoryView.setPlaybackMode()` 没有校验 RAW 网格。
- 真正增加/删除历史标点时，`editRawPoint()` 已要求 `record.rawGrid != null`，所以 UI 能进入
  ZONE 但点击图片没有效果，形成“看起来可用、实际无效”的设计缺陷。
- 详情参数和备注统一只有约 10sp，RAW 提示只有 8sp；详情面板仍有可利用空间。

### 4.2 ZONE 可用性唯一判据

新增纯函数：

```text
canRecalculateZone(record) = record.rawGrid != null
```

必须使用 `rawGrid`，不能只看 `rawPath`：DNG 文件存在并不代表应用可以快速按触点重算 EV；
历史交互真正依赖的是保存的紧凑 RAW 亮度网格。正常事务会同时保存 DNG 和网格，但旧记录
或异常恢复数据仍可能只剩其中一项。

### 4.3 无 RAW 网格时的 UI 和交互

1. ZONE 按钮使用禁用表面色、禁用描边和禁用文字色，不能只把透明度降低到仍像可点击。
2. `targetAt()` 对禁用 ZONE 返回 `NONE`，不触发触觉反馈。
3. `setPlaybackMode(ZONE)` 再做一次业务层防御并直接拒绝。
4. `RecordedMeteringSession.from()` 在无 `rawGrid` 时必须以 `NORMAL` 开始。
5. 详情翻页或数据恢复后重新计算能力；不能沿用上一张记录的 ZONE 状态。
6. 无障碍描述使用：
   - 中文：`ZONE，需要已记录的 RAW 数据`
   - 英文：`ZONE, requires recorded RAW data`
7. 如果旧记录本身含有已保存的 `zonePoints` 但没有 RAW 网格，不删除这些证据。NORMAL 摘要
   中可显示“已记录 N 个标点 / N recorded points”，图片可保留只读点位覆盖层；但是不得
   允许新增、删除或重新放置，也不得把 ZONE 按钮显示为可用。

### 4.4 文字层级与推荐字号

字号必须使用 `scaledDensity`，并保留宽度测量和省略处理：

| 内容 | 当前约值 | 目标值 | 样式 |
| --- | ---: | ---: | --- |
| 日期/GPS | 10sp | 11.5sp | secondary |
| 拍摄参数 | 10sp | 13sp | normal/medium |
| EV100 | 10sp | 14sp | semibold |
| 胶片 | 10sp | 13sp | semibold |
| “备注/Notes”标题 | 10sp | 12sp | secondary semibold |
| 每条备注 | 10sp | 13sp | normal |
| RAW 操作提示 | 8sp | 9.5sp | secondary |

推荐行高：普通行 22dp，备注行 30dp，段间距 8～10dp。根据实际字段数计算
`detailMaxScroll`；不能通过裁剪掉最后一行来“填满空白”。当字段很少时保留自然留白，
但参数、EV100、胶片和备注的视觉权重应明显提高。

### 4.5 几何调整

- 在 `ParameterHistoryGeometry` 明确增加 metadata viewport，绘制和滚动都使用同一矩形。
- 测光复现面板继续保持 132～205dp 的设备适配范围，不应无条件占据 data 区域 60%。
- 优先按“文字需要高度 + 8dp 间隔 + 测光面板最小高度”分配；空间不足时文字滚动，测光面板
  不压缩到不可操作。
- 横屏窄高度下至少保证 EV100、拍摄参数和胶片三行可见，备注进入滚动。

### 4.6 预计修改文件

- `ParameterHistoryView.kt`
- `ParameterHistoryGeometry.kt`
- `RecordedMeteringRenderer.kt`
- `RecordedMeteringSession.kt`
- `ParameterRecordModels.kt`（仅增加派生能力或只读摘要时）
- 新增 `RecordedHistoryCapability.kt`

### 4.7 单元测试与验收

- 无 `rawGrid`：默认 NORMAL、ZONE 灰色、点击无效、拖动无效。
- 有 `rawGrid`：ZONE 可选，增加/删除点仍工作。
- `rawPath != null`、`rawGrid == null`：仍禁用。
- `rawPath == null`、`rawGrid != null`：允许网格重算，并显示“DNG 文件不可用”而不是禁用。
- 旧 ZONE 记录无网格：点位数据不丢失，但只能只读。
- 中英文长胶片名、3 条长备注、GPS、横屏、1.3 倍字体不互相覆盖。

---

## 5. 工作包 B：曝光预览 ISO 优先与帧时长修复

### 5.1 目标策略

曝光预览是构图辅助，不是正式拍摄。默认以流畅度优先：

```text
优先曝光上限：1/30 s
允许的低光硬上限：1/15 s
优先顺序：缩短/保持快门 -> 提高 ISO -> ISO 到顶后才放慢至 1/15 s -> 再不足则钳制亮度
```

这两个值应放在 `ExposurePreviewPolicy`，不要散落在 View 或 Controller。以后若增加设置项，只
替换 policy 输入，不改算法。

### 5.2 纯算法

输入：

- 目标相机 EV100；
- 手机实际光圈；
- 传感器曝光时间范围；
- 传感器 ISO 范围；
- 当前会话流的最小帧时长；
- preferred 1/30 s 和 hard 1/15 s。

计算：

```text
P = N² * 100 / 2^EV100 = exposureSeconds * ISO
preferredTime = clamp(1/30 s, sensorExposureRange)
requiredIso = ceil(P / preferredTime)

requiredIso <= ISOmax:
    ISO = clamp(requiredIso, ISOmin..ISOmax)
    time = P / ISO

requiredIso > ISOmax:
    ISO = ISOmax
    time = min(P / ISOmax, hardTime)

最后按设备范围钳制 time，重新计算最接近目标的 ISO 一次；
根据实际 time 和 ISO 反算 appliedCameraEv100；差值 > 0.02 EV 时 clamped = true。
```

亮场下 `requiredIso` 低于 ISOmin 时固定 ISOmin，并继续缩短快门。不得为了维持 1/30 s 而
把 ISO 设到设备下限以下。

### 5.3 帧时长必须重新计算

AE OFF 时，`CONTROL_AE_TARGET_FPS_RANGE` 不能替代手动帧时长控制。新的
`SENSOR_FRAME_DURATION` 应为：

```text
max(
  当前请求所有目标流的最小 frame duration,
  本次 exposure time,
  目标预览周期（默认 1/30 s）
)
```

然后钳制到 `SENSOR_INFO_MAX_FRAME_DURATION`。禁止再把
`latestResult.SENSOR_FRAME_DURATION` 无条件作为下限，因为它可能属于上一次慢曝光。

官方说明也明确：AE target FPS 只约束 AE，不约束手动曝光；实际帧率还受各输出流最小帧
时长影响：
<https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#CONTROL_AE_TARGET_FPS_RANGE>

### 5.4 新的返回信息

`ExposurePreviewManualExposure` 增加或由新 policy 返回：

- `exposureTimeNs`
- `sensitivity`
- `frameDurationNs`
- `appliedCameraEv100`
- `clamped`
- `clampReason`: `NONE | ISO_LIMIT | EXPOSURE_LIMIT | FRAME_DURATION_LIMIT`

UI 可在首次出现钳制时显示一次非阻塞提示，不要每帧 Toast：

- 中文：`预览已达到相机 ISO/快门限制，显示亮度可能低于所选曝光`
- 英文：`Preview reached the camera ISO/shutter limit; displayed brightness may be lower than selected`

### 5.5 设备返回值校验

请求值不一定等于实际值。连续结果必须读取：

- `CaptureResult.SENSOR_EXPOSURE_TIME`
- `CaptureResult.SENSOR_SENSITIVITY`
- `CaptureResult.SENSOR_FRAME_DURATION`

若实际曝光时间连续 3 帧比请求长超过 10%，标记该设备手动预览不可靠，降级到 AE exposure
compensation；若补偿也不支持，再提示曝光预览不可用。不要为厂商硬编码型号。

### 5.6 预计修改文件

- `ExposurePreview.kt`：保留数据模型/简单 EV 换算，移出分配策略。
- 新增 `ExposurePreviewPolicy.kt`。
- `CameraController.kt`：只调用 policy 和 preview controller。
- `CameraStreamSelector.kt` 或会话 plan：暴露当前目标流最小帧时长。
- `MainActivity.kt`：展示一次性钳制提示。

### 5.7 测试

- 亮场：ISO 固定下限，快门短于 1/30。
- 普通暗场：快门不长于 1/30，ISO 上升。
- 更暗：ISO 到顶后快门可到 1/15。
- 极暗：不超过 1/15，`clamped=true`。
- 从 1/15 切回 1/500：frame duration 回到约 1/30，不能继承旧长值。
- 传感器最小/最大曝光、ISO 范围异常、无 manual sensor 时安全降级。
- 半档/三分之一档改变只影响目标 EV，不引入超过 0.05 EV 的计算误差。

---

## 6. 工作包 C：曝光预览开启后的测光延迟

### 6.1 不可破坏的正确性边界

- 正式测光必须使用中性 AE 或正式 RAW 捕获参数。
- 不得把曝光预览手动帧直接作为 YUV/ISP 正式读数。
- 不得用 `latestResult` 配任意图像；继续保持严格时间戳配对。
- 不得为了减少等待让 RAW、YUV 并发采集。

### 6.2 抽取 `MeteringBaselineCoordinator`

职责仅包括：

1. 保存“用户想要的曝光预览 selection”，临时将“当前生效 preview exposure”设为 neutral；
2. 提交带唯一 request tag/generation 的中性重复请求；
3. 只接受属于该 generation 的 CaptureResult；
4. 判断 compensation=0、AE 已开启、AE state 可接受；
5. 连续 2 个有效稳定帧后开始正式测量；
6. 1200 ms 硬超时；
7. 成功、失败、相机关闭、切后台时取消 timeout；
8. 测量完成后恢复之前 selection，不依赖 UI 再次“碰巧”推送。

不得让该 coordinator 拥有 CameraDevice、ImageReader 或 View。

### 6.3 等待策略

建议第一版：

- 从手动 AE OFF 恢复：最多 1200 ms，连续 2 帧 request tag 正确且 AE stable；
- 从非零曝光补偿恢复：最多 800 ms，连续 2 帧 compensation=0 且 AE stable；
- 原本已是 neutral：立即进入测量；
- AE state 为 null 的厂商：request tag、AE mode 和实际曝光元数据连续 2 帧有效即可；
- 超时后允许测量，但读数携带 `baselineTimedOut=true`，调试日志和诊断页可见。

不要先把安全超时从 1200 ms 全局缩短。先通过 request tag 消除无效旧帧，再根据真机数据
决定是否缩短。

### 6.4 性能日志

每次测光记录：

```text
previewNeutralRequestAt
firstMatchingResultAt
aeStableAt
measurementStartedAt
firstImageAt
readingCompletedAt
previousExposureTime / previousFrameDuration
source / sessionProfile / physicalCameraId
```

目标值而非所有设备的硬失败线：

- 已是 neutral：额外等待 < 50 ms；
- 从曝光预览恢复：P50 < 350 ms，P95 < 800 ms；
- 任何情况不超过现有 1200 ms baseline 超时再叠加本来源测量超时。

### 6.5 UI 状态

把“恢复 AE”和“读取测光帧”分成两个内部阶段，必要时显示：

- `正在恢复自动曝光 / Restoring auto exposure`
- `正在读取 RAW 流 / Reading RAW stream`
- `正在读取预览流 / Reading preview stream`

避免用户把中性 AE 等待误认为按钮失效。

### 6.6 测试

- 暴露预览 OFF：不新增等待。
- 手动预览 ON：旧 generation 的 3 个结果全部拒绝。
- 第 2 个中性稳定帧到达即开始，不再固定等待 3 帧。
- 相机切换/Activity pause/错误时 continuation 只能执行 0 次。
- 超时与稳定帧同时到达也只能开始一次。
- 完成后恢复原 exposure selection，不能永久关闭曝光预览。
- 慢快门 1/15、ISO 上限、YUV 和 RAW 各做连续 20 次真机测量。

---

## 7. 工作包 D：RAW、YUV、ISP 分来源校准

### 7.1 新的数据模型

把 `CameraCalibrationRecord` 拆为明确字段：

```text
rawCorrectionEv / rawMeasuredEv100
yuvCorrectionEv / yuvMeasuredEv100
ispPreviewCorrectionEv / ispPreviewMeasuredEv100
legacyCompatibleCorrectionEv              # 只用于旧数据迁移
referenceEv100
updatedAtEpochMs
calibrationCount
schemaVersion
```

如果不想让 data class 过宽，可使用三个不可变 `StreamCalibration`，但序列化仍必须明确来源，
不能用“所有非 RAW”分支。

### 7.2 correction 应用规则

```text
RAW          -> raw correction + 已知设备 RAW baseline
YUV_PREVIEW  -> yuv correction
ISP_PREVIEW  -> isp preview correction
```

RAW 的 Vivo 设备 baseline 不能应用到 YUV/ISP。YUV 与 ISP 没有自己的硬编码厂商 baseline，
默认 0，依赖用户校准。

曝光预览显示在 `TextureView`，所以来源转换应使用 ISP 显示预览 correction：

```text
displayDomainEv = measuredEv
                - correction(measuredSource)
                + correction(ISP_PREVIEW)
```

当前 `previewCameraCorrectionEv()` 固定取非 RAW 共享值。分离后必须改为
`ISP_PREVIEW`，不能因为 YUV 更“接近传感器”而拿 YUV correction 校准屏幕显示域。

### 7.3 旧数据迁移

旧 `compatible_user_*` 没有记录当时来自 YUV 还是 ISP，不能根据当前模式倒推。

安全迁移方案：

1. 原值读入 `legacyCompatibleCorrectionEv`；
2. 某来源已有新 correction 时优先使用新值；
3. 某来源尚未重新校准时，可临时回退 legacy 值，避免升级后读数突然变化；
4. 校准 UI 明确显示“旧版共享修正 / Legacy shared correction”；
5. 用户完成 YUV 或 ISP 校准后只替换对应来源，不删除另一来源和历史；
6. 两个处理后来源均有新记录后，legacy 不再参与计算，但历史仍可读；
7. 重置当前校准时保留最近三条历史的现有产品行为。

不得把旧 shared 值无标记地复制成两个“精确”新值。

### 7.4 分模式计划

新增纯函数 `MeteringCalibrationPlan.create(mode, capabilities, health)`，输出按顺序的
`List<MeteringSource>`，规则采用 1.4 节表格。计划生成后，如果物理 camera ID、session
generation 或 Activity 生命周期改变，整次运行取消，不在另一镜头上继续后续阶段。

### 7.5 顺序和会话隔离

保守第一版流程：

```text
保存用户曝光预览状态并进入 neutral

RAW 阶段（若需要）
-> 使用 RAW_ONLY 安全会话
-> 完成当前 RAW 帧数
-> 关闭所有 Image，等待 capture sequence 完成

YUV 阶段（若需要）
-> 切换 COMPATIBLE/PRIVATE+YUV 安全会话
-> 中性 AE 稳定
-> 严格 image timestamp == SENSOR_TIMESTAMP
-> 最多 3 帧/250 ms

ISP 阶段
-> 使用 PREVIEW_ONLY 或已验证的 COMPATIBLE 会话
-> SurfaceTexture timestamp 与 CaptureResult 严格配对
-> 截图前后 timestamp 不变

保存各成功来源
-> 恢复用户原模式的 session profile
-> 恢复曝光预览
```

每个阶段都有独立 operation ID。阶段切换必须等上一个来源的所有图像关闭；会话关闭仍可能
有在途回调，因此 generation 校验不可省略。

### 7.6 部分成功

- RAW 成功、YUV 失败、ISP 成功：保存 RAW 和 ISP，不覆盖旧 YUV。
- RAW 失败：继续当前模式允许的 YUV/ISP；界面说明 RAW 未完成。
- YUV 失败：标记本会话 YUV unhealthy，继续 ISP；不要反复等待 250 ms。
- ISP 失败：如果 RAW/YUV 已成功，保存成功部分；曝光预览显示域标记未校准。
- 任一阶段 physical ID 改变：本次全部不保存，提示镜头发生切换。

### 7.7 校准界面

分别显示：

- `RAW 传感器 / RAW sensor`
- `YUV 兼容流 / YUV compatible stream`
- `ISP 显示预览 / ISP display preview`
- 可选 `旧版共享修正 / Legacy shared correction`

每行显示 correction、最近测量时间、当前/未完成/失败。向导显示 `第 1/3 步` 等真实步骤，
不能在 YUV 阶段仍写“预览流”而不说明来源。

### 7.8 组件与文件

- `CameraCalibrationStore.kt`：改为 facade，持久化细节必要时拆文件。
- `CameraCalibrationModels.kt`（新增）。
- `MeteringCalibrationPlan.kt`（新增）。
- `MeteringCalibrationCoordinator.kt`（新增，从 `MainActivity` 移出流程状态）。
- `CalibrationView.kt`。
- `CompatibleLightMeter.kt`。
- `MeteringAnalysis.kt`。
- `MeterModels.kt` 中的显示域转换。
- `CameraController.kt` 只暴露按来源测量和读取/保存门面。
- `README.md`、`README_ZH.md`、`docs/CAMERA_PIPELINE.md` 同步三来源说明。

### 7.9 测试

- 迁移旧 shared correction，不伪装为精确 YUV/ISP。
- YUV correction 绝不影响 ISP 分析；ISP correction 绝不影响 YUV。
- RAW baseline 只应用到 RAW。
- 高精度有 RAW/YUV：计划为 RAW→YUV→ISP。
- 稳定有 RAW：RAW→ISP，绝不出现 YUV。
- 兼容有健康 YUV：YUV→ISP；无 YUV：仅 ISP。
- 部分成功不覆盖未测来源。
- 校准中切相机、切后台、相机重开：不保存混合来源记录。
- 连续 10 次校准监控 ImageReader、native heap、session generation 和绿屏/条纹健康状态。

---

## 8. 工作包 E：主测光界面 EV100 方框

### 8.1 产品定义

在普通测光按钮和 Zone 标点按钮的物理左侧紧贴一个小方框：

```text
EV100
12.34
```

它只读，不拦截按钮点击，不改变测光按钮的最小触摸区域。

### 8.2 数值来源

- 普通模式：最近一次正式测光成功后的 `state.effectiveEv100`；测量前 `--`；正在测量可显示
  `…`，失败后保留最近稳定值并通过状态信息说明错误。
- Zone 模式：`pendingMarkerId` 存在时显示 `…`；完成后显示本次最新标点的 EV100；没有标点
  时显示 `--`。不要显示加权平均值，因为需求是“当前标点测出来的值”。
- 数字视觉显示保留 2 位小数；无障碍描述可读完整值和状态。

新增 `Ev100Readout.resolveNormal(state)` 与 `resolveZone(session)`，两个 View 不复制判断。

### 8.3 几何

`LayoutGeometry` 和 `ZoneLayoutGeometry` 都增加 `ev100Badge: RectF`。方框推荐 44～58dp，
与测光/标点按钮等高或略小，间距 4～6dp。无论左右手设置如何，方框都位于按钮的物理左边，
符合本次明确需求。

若现有 action 区域不足，应把“EV 方框 + 测光按钮”视为一个整体居中，不能简单覆盖拨盘、
记录列表或屏幕边界。横屏和 Zone 的 action 空间都要由 geometry 计算，不在 View 内临时
偏移。

### 8.4 绘制

共享 `Ev100BadgeRenderer`：

- 与按钮相同 surface，1～1.2dp 边框，圆角 4～6dp；
- 标题 7.5～8sp semibold；
- 数值 11～12sp bold；
- 数值不足空间时最多缩到 9.5sp，不使用横向拉伸；
- measuring 时可用红色省略号，正常值使用 foreground。

### 8.5 文件与测试

- `LayoutGeometry.kt`
- `ZoneLayoutGeometry.kt`
- `InstrumentView.kt`
- `ZoneSystemView.kt`
- 新增 `Ev100Readout.kt`、`Ev100BadgeRenderer.kt`

验证普通/Zone、横竖屏、左右手、长宽比 4:3/65:24、1.3 倍字体；点击 badge 区域不应误触
测光，点击原按钮区域必须完整有效。

---

## 9. 工作包 F：景深计算页面

### 9.1 固定单一顶部指针

当前 `drawDial()` 在 -90° 与 +90° 两组刻度中把 offset=0 画红，而且角度包含
`visualOffset`，所以顶部和底部各有一个随波轮转动的红指针。

修改为两层：

1. 旋转层：所有波轮刻度使用 muted，角度包含 `visualOffset`；
2. 固定层：旋转层之后只在顶部 -90° 画一条红线或小三角，角度绝不包含
   `visualOffset`。

移除当前重复的顶部红点，或把它整合成固定指针的一部分。底部只能有普通灰色刻度，不能
再出现红色中心刻度。

### 9.2 字号

| 内容 | 当前约值 | 推荐目标 |
| --- | ---: | ---: |
| 波轮中光圈/焦距值 | 12～17sp | 15～20sp，自适应半径 |
| 波轮“光圈/Aperture”“焦距/Focal length” | 8.5sp | 10.5～11sp |
| “画幅/Format”和“c” | 8.5sp | 10.5～11sp |
| 画幅/c 数值 | 10.5sp | 12～13sp |
| 近界/对焦/远界 | 9.5sp | 11～12sp |
| 三个距离数值 | 12.5sp | 15～16sp |

`画幅/Format` 和 `c` 使用更强的 secondary 色。浅色主题不再使用接近 190 灰度的过浅颜色，
应建立 `secondaryStrong` 语义色；暗色主题也要保证对比度。

### 9.3 布局适配

- 增大距离值后，将 connector 终点和 summary 基线下移，至少保留 4dp 行间距。
- `focusValue` hit rect 跟随新的文字区域，不能只改绘制位置。
- 英文 `Focal length`、较长画幅名称先按可用宽度缩放到下限，再省略；不要固定截成一半。
- 两个 selector 高度可由约 38dp 增到 42～46dp，但拨盘最小尺寸不得低于现有 64dp。

### 9.4 文件与验收

- `DepthOfFieldView.kt`
- `DepthOfFieldGeometry.kt`
- 如需复用固定指针计算，可增加小型纯函数，不建立新大 renderer。

拖动光圈和焦距各 20 档录屏检查：红指针像机身刻线一样完全不动，只有灰色刻度移动；
中英文、无穷远、0.10m、全画幅和自定义画幅均不重叠。

---

## 10. 工作包 G：胶片选择与宽容度页面

### 10.1 胶片选择页

胶片名称保持 14sp 或增到 15sp；名称下的宽容度和 ISO 从 11sp 增到 12.5～13sp，颜色由
当前 muted 改为 `secondaryStrong`。浅色主题建议视觉亮度约 RGB 90～110，暗色主题约
RGB 155～175，但最终应由语义色统一管理，不在多个 View 复制数值。

详情基线可利用名称与行底之间的空白下移 2～4dp，确保 1.3 倍字体不碰名称。长 ISO/范围
文本使用 ellipsize，但必须优先保留 ISO 和范围数值，不能先保留装饰分隔符。

### 10.2 宽容度轨道和标签

| 元素 | 当前 | 目标 |
| --- | ---: | ---: |
| 灰阶轨道高度 | 28～42dp | 36～52dp |
| 0～X 区域标签 | 8.5sp | 10～10.5sp |
| 红色边界线 | 2dp | 2.5dp |
| `±x档 / ±x EV` 标签 | 40×24dp / 9sp | 48×30dp / 10.5～11sp |

增大视觉标签后也要扩大边界拖动 hit target，最小触摸宽度 44dp。左右边界接近时仍采用距
指针最近的规则，不能因为标签重叠而交换 shadow/highlight。

### 10.3 宽容度按钮

- 选择胶片、记录按钮：11sp 提到 12～12.5sp；
- 应用/取消应用：11.5sp 提到 12.5～13sp；
- 重置：9.5sp 提到 10.5～11sp；
- 中文可单行；英文优先两行平衡换行，禁止单行省略为难以理解的残句。

`LatitudeView` 当前对 apply 文本直接 ellipsize，实施时改用最多两行的测量布局；普通按钮
也使用同一有语义的 `ActionLabelLayout`，不要每个页面再写一套按字符数劈半。

### 10.4 几何

- `LatitudeGeometryCalculator` 增加 rail 和按钮的最小高度预算；
- 宽屏维持横向 action 布局，窄屏允许两列；
- 如果空间冲突，优先保证轨道和 apply 按钮，其次缩短胶片卡高度，不缩小触摸区；
- 画布字号变大后，绘制矩形和 `targetAt()` 必须使用同一 geometry。

### 10.5 文件与验收

- `FilmSelectorView.kt`
- `FilmSelectorGeometry.kt`
- `LatitudeView.kt`
- `LatitudeGeometry.kt`
- 可新增 `ActionLabelLayout.kt`，只负责一/两行文字测量。

验证无胶片、自定义长名称、ISO 缺失、收藏/重置状态、中文/英文、1.3 倍字体、320dp 窄屏
和横屏。滚动时行高与触摸索引必须一致。

---

## 11. 工作包 H：倒易率时间显示和动态上限

### 11.1 当前缺陷

- 现在限制的是“输入快门不能超过 24h”，不是“校正后的最终时间不能超过 24h”。
- 因此输入小于 24h 时，公式可能给出超过 24h 的最终结果。
- 结果一律使用总分钟 `mm:ss`，2 小时会显示 `120:00`。
- 刻度左侧 `s` 为 11sp，数值为 10sp；按钮文字偏小。
- 结果是一个字符串，无法把 h/min/s 放到各数字列下方。

### 11.2 结构化时间模型

新增 `ReciprocityTimeReadout`：

```text
SUBSECOND:  value = 1/x，不显示分栏单位
MIN_SEC:    fields = [MM, SS], units = [min, s]
HOUR_MIN_SEC: fields = [HH, MM, SS], units = [h, min, s]
UNAVAILABLE: --:--
```

规则：

- 0 < t < 1s：继续显示 `1/x`；
- 1s ≤ t < 1h：`MM:SS`，每列下方分别 `min`、`s`；
- t ≥ 1h：`HH:MM:SS`，每列下方分别 `h`、`min`、`s`；
- 使用四舍五入后的总秒数进位，`59.6s` 必须显示 `01:00` 而不是 `00:60`；
- 精确数据若允许超过 24h，小时可超过 24，不转成“天”。

视觉单位按用户指定在中英文都使用 h/min/s；无障碍描述则本地化成“2 小时 3 分 4 秒”或
“2 hours 3 minutes 4 seconds”。

### 11.3 结果绘制

不要用一个字符串猜冒号位置。`ReciprocityTimeRenderer` 根据字段数分列：

- 数字基线占 result 区域约 55%；
- 单位基线位于数字下方 8～12dp；
- 冒号作为独立分隔绘制并与数字垂直居中；
- `HH:MM:SS` 先使用 28～32sp，空间不足按测量结果缩到不低于 18sp；
- unit 使用 9.5～10.5sp secondaryStrong；
- estimate/filter 警告保留在顶部或底部，不能与单位重叠。

### 11.4 动态最大输入的精确定义

普通规则：允许选择的最大输入快门，必须满足
`correctedSeconds <= 24h`。

例外规则：如果当前胶片存在有权威边界的精确数据，并且该精确域内已经给出大于 24h 的
最终时间，则允许滑块继续到该精确数据的输入截止；不能再进入截止之后的估算域。

定义 `exactInputCeiling(method)`：

- `TABLE`：最后一个处于 `officialMaximumSeconds` 内的厂商节点；节点之间的插值属于精确
  数据域，最后节点以后属于估算；
- `POWER`/`FIXED_EV`：只有 `officialMaximumSeconds` 非空时，其以内才算有权威精确域；
  null 表示没有可用于突破 24h 的明确截止；
- `RANGE`/`NONE`：没有可计算的精确补偿域，不触发例外。

计算 `maximumInputSeconds(method, step)`：

1. 在可用快门 tick 中寻找最终结果不超过 24h 的最大 tick；
2. 如果精确域中任何合法结果超过 24h，则改用 `exactInputCeiling`；
3. exact ceiling 不是现有标准 tick 时，为它添加一个 terminal tick；
4. 估算结果永远不能借例外超过 24h；
5. method null、NONE、RANGE 时保留最多 24h 的输入浏览，但结果继续显示不可用/超范围；
6. 胶片或步长改变后立即重算；当前值超界时动画夹回新上限，并触发一次轻触反馈。

当前 2026-08-24 数据集没有最终精确节点超过 24h，但代码和测试必须支持未来数据，不能
因为当前没有样本而省略例外。

### 11.5 数学层与 UI 层职责

- 移除 `ReciprocityMath.calculate()` 对“输入 > 24h”一刀切的拒绝；数学层只验证正数、有限
  数值和 method 规则。
- `ReciprocityLimitPolicy` 负责产品上限和精确域例外。
- `ReciprocityShutterScale.ticks(step, maximumInputSeconds)` 负责动态刻度。
- `ReciprocityView` 的绘制、拖动、snap、nearest index、coordinate conversion 全部使用同一个
  动态 tick 列表，禁止有的函数仍调用旧静态 24h 列表。
- 应用到主测光表的倒易率计算仍使用标准曝光选择输入，不被计算器 UI 当前滑块状态污染。

### 11.6 快门条左侧与按钮字号

- 左侧 title 区由最多 64dp/23% 增到约 76～82dp/26%，最终由文字测量决定；
- `s`：11sp → 13sp；
- 当前快门数值：10sp → 12～13sp；
- 刻度标签：7.5sp → 8.5～9sp，仍做碰撞检测；
- 选择胶片按钮：10.5sp → 12sp，英文允许两行；
- 应用按钮：11sp → 12.5～13sp，英文允许两行。

如果右侧 scale 内容因此变窄，优先减少一次可见 stop 数或略调 `pixelsPerStop`，不要把左侧
数字又缩回原大小。左右手布局分别计算 title 区。

### 11.7 文件

- `ReciprocityModels.kt`：只保留 method/result/math，必要时拆分。
- 新增 `ReciprocityLimitPolicy.kt`。
- 新增 `ReciprocityTimeReadout.kt`。
- 新增 `ReciprocityTimeRenderer.kt`。
- `ReciprocityView.kt`。
- `ReciprocityGeometry.kt`。
- `InstrumentExposureRenderer.kt`、`ZoneSystemView.kt`：继续使用格式化结果时确认 h:mm:ss 兼容。

### 11.8 单元测试

- 0.5s → `1/2`。
- 1s → `00:01`，单位 min/s。
- 59.6s → `01:00`。
- 3599.6s → `01:00:00`，单位 h/min/s。
- 7200s → `02:00:00`，不再是 `120:00`。
- 普通 POWER 曲线在 corrected=24h 前一个 tick 截止。
- 估算 corrected>24h：禁止继续。
- TABLE 精确最后节点 corrected>24h：允许到最后精确 input，之后禁止。
- 切换到更严格胶片后当前滑块自动夹回。
- FULL/HALF/THIRD 三档 terminal tick、nearest、drag clamp 一致。
- 超大值、NaN、Infinity 不溢出、不死循环。

---

## 12. 工作包 I：中英文、无障碍与视觉回归

### 12.1 新增文案表

| 中文 | 英文 |
| --- | --- |
| ZONE 需要已记录的 RAW 数据 | ZONE requires recorded RAW data |
| 正在恢复自动曝光 | Restoring auto exposure |
| 预览已达到相机 ISO/快门限制 | Preview reached the camera ISO/shutter limit |
| RAW 传感器 | RAW sensor |
| YUV 兼容流 | YUV compatible stream |
| ISP 显示预览 | ISP display preview |
| 旧版共享修正 | Legacy shared correction |
| 当前来源未完成校准 | Calibration for this source is incomplete |

可见 UI 继续遵循项目的 `MenuLanguage`；系统权限对话框和资源字符串按 Android locale。不要
在同一页面混用两套语言来源而不刷新。

### 12.2 通用符号

以下不翻译：`EV100`、`ISO`、`ZONE`、`NORMAL`、`f`、`c`、`EV`、`h`、`min`、`s`。
视觉保持短单位，无障碍完整本地化。

### 12.3 英文长度策略

- 按钮最多两行，按单词边界换行；中文按字符边界。
- 先测量再缩放，设最小字号；仍不够才 ellipsize。
- 数值和单位不能省略；优先省略长胶片名或说明文字。
- `Focal length`、`Cancel metering application`、`Save current latitude`、
  `Outside exact range · estimate only` 是必测长文案。

### 12.4 无障碍

- 禁用 ZONE 暴露 disabled 和原因。
- EV100 badge 合并进所属主 View 的描述，读出当前值/测量中/无值。
- 倒易率读出完整时间单位和是否估算。
- 校准逐步骤读出来源和进度。
- 纯装饰红指针不单独成为可访问节点。

### 12.5 视觉矩阵

每个相关页面至少保存以下截图：

```text
中文 / 英文
浅色 / 深色
竖屏 / 横屏
右手 / 左手
字体 1.0x / 1.3x
360dp 常见手机 / 320dp 窄屏 / 大屏
```

不是每个组合都要人工逐像素比较，但每个风险维度至少与另一个维度交叉一次；英文+横屏、
1.3x+窄屏、左手+Zone 必须覆盖。

---

## 13. 推荐实施顺序与提交边界

实施前先保留当前未跟踪的真机 PNG，不删除、不纳入功能提交，除非用户另行要求。

### 提交 1：历史记录能力与文字

```text
fix(history): disable zone replay without recorded raw grid
```

包含工作包 A 和测试，不混入其他页面字号。

### 提交 2：曝光预览策略

```text
fix(preview): prioritize iso and bound manual preview shutter
```

包含工作包 B、纯单测和实际 CaptureResult 日志。

### 提交 3：测光 baseline coordinator

```text
perf(camera): shorten tagged neutral-ae metering transition
```

包含工作包 C；不要与校准迁移同时改状态机。

### 提交 4：分来源校准

```text
fix(calibration): isolate raw yuv and isp preview corrections
```

包含数据迁移、coordinator、UI、README 和 pipeline 文档。迁移测试必须随提交进入。

### 提交 5：主界面 EV100 badge

```text
feat(meter): show current ev100 beside meter actions
```

包含普通/Zone 共用 renderer 和几何测试。

### 提交 6：景深与胶片工具视觉

```text
refine(tools): improve dof film and latitude readability
```

包含工作包 F/G；若 diff 过大，可拆为 DOF 与 film/latitude 两个提交。

### 提交 7：倒易率边界和时间显示

```text
fix(reciprocity): cap corrected time and label time fields
```

包含工作包 H、动态边界测试和截图。

### 提交 8：本地化与回归收尾

```text
docs(i18n): synchronize bilingual ui and implementation notes
```

只处理剩余中英文、无障碍、文档和截图说明，不隐藏业务修复。

每个提交都必须是新提交，不 amend、不覆盖用户已有提交。

---

## 14. 验证命令与真机步骤

### 14.1 自动测试

每个提交至少执行：

```text
gradlew.bat testDebugUnitTest
gradlew.bat assembleDebug
```

分来源校准、Canvas 无障碍或资源变更后再执行 `lintDebug`。若 lint 因网络或 Gradle daemon
问题无法完成，要保留日志并明确说明，不能把“构建成功”写成“lint 成功”。

### 14.2 当前 USB 设备首轮验证

以已连接的 Vivo V2405A 为首轮回归设备：

1. 安装新 debug APK，不清除应用数据，验证旧 shared 校准迁移。
2. 历史记录选择一条有 RAW 和一条无 RAW 的记录，比较 ZONE 状态。
3. 在暗环境开启曝光预览，拖动到更亮曝光，记录请求/实际 shutter、ISO、frame duration。
4. 连续测光 20 次，分别记录 baseline wait 和总耗时。
5. 高精度执行 RAW→YUV→ISP 校准，观察会话切换、内存和取景健康。
6. 稳定模式确认从未配置/请求 YUV 校准。
7. 检查普通/Zone EV100 方框、左右手和旋转。
8. 逐页检查景深、胶片、宽容度、倒易率中文/英文、浅色/深色。
9. 倒易率用测试数据注入“精确结果 >24h”路径；当前正式数据没有该样本，不能只靠手拖。

### 14.3 跨设备最低矩阵

- 一台 RAW FULL/LEVEL_3 设备；
- 一台 LIMITED 或无 RAW 设备；
- 一台多摄且会自动切 physical ID 的设备；
- 至少覆盖 Vivo/OPPO/小米/三星/Pixel 中两个不同厂商 ISP；
- 如可用，覆盖 Android 旧版本和最新版本各一台。

重点记录不是厂商名，而是 hardware level、stream profile、实际 physical ID、YUV 是否健康、
manual sensor 能力和 CaptureResult 实际值。

---

## 15. 最终验收清单

### 历史记录

- [ ] 无 RAW 网格时 ZONE 明显置灰且完全不可交互。
- [ ] 有 RAW 网格时历史触点重算不回归。
- [ ] 参数、EV100、胶片和备注更大，长内容可滚动。

### 曝光预览与速度

- [ ] 常规低光先提高 ISO，预览快门默认不慢于 1/30。
- [ ] ISO 到顶后最多放慢到 1/15，再不足明确钳制。
- [ ] 手动 frame duration 不继承上一帧慢值。
- [ ] 开启曝光预览后的测光只等 2 个 tagged 中性稳定帧。
- [ ] 正式测光仍严格同帧且不使用曝光预览帧冒充。

### 校准

- [ ] RAW/YUV/ISP correction 独立存储和应用。
- [ ] 高精度按顺序校准 RAW、YUV、ISP，不并发采集。
- [ ] 稳定模式不为校准临时引入 YUV。
- [ ] 旧 shared 数据安全迁移并在 UI 标明。
- [ ] 曝光预览转换使用 ISP 显示域 correction。

### 主界面和摄影工具

- [ ] 普通测光和 Zone 标点按钮左侧都有 EV100 方框。
- [ ] Zone 显示最新当前标点，不显示加权平均。
- [ ] 景深波轮只有固定顶部红指针，所有指定文字增大。
- [ ] 胶片详情更大更深，宽容度轨道/标签/按钮更清楚。
- [ ] 倒易率 1h 以上显示 `HH:MM:SS`，每列单位正确。
- [ ] 动态滑块限制基于最终校正时间，精确数据例外只到数据截止。

### 工程质量

- [ ] 没有新上千行大类。
- [ ] 新策略为纯函数或单一状态机，可单测。
- [ ] 中英文、左右手、横竖屏、明暗主题和大字体通过。
- [ ] README 与 `CAMERA_PIPELINE.md` 的校准说明同步。
- [ ] 自动测试、构建、lint（可执行时）和真机日志均有结果。

只有以上项目全部完成，并且真机没有重新出现全绿、黑白条纹、冻结或资源泄漏，才能把本文
状态改为“已实施”。
