# RAW 测光延迟优化与低开销质量评估详细实施规划

> 文档状态：实施规划稿，本次仅新增文档，不修改应用代码  
> 适用项目：Raw Light Meter / `com.lightmeter.rawmeter`  
> 编写日期：2026-09-09  
> 关联文档：[测距能力与算法实施规划](DISTANCE_MEASUREMENT_IMPLEMENTATION_PLAN_ZH.md)

## 1. 规划结论

Zone 模式的 RAW 测光延迟可以优化，但必须保留当前为规避厂商 Camera HAL 输出组合冲突而建立的会话隔离设计：

```text
预览 + YUV 跟踪会话
        ↓ 互斥切换
RAW-only 测光会话
        ↓ 互斥切换
预览 + YUV 跟踪会话
```

本文把“拆分进程”按当前代码的实际结构理解为 **Camera2 会话和输出组合拆分**。不建议为了加速而默认配置预览、YUV、RAW 三路常驻，也不建议引入 Android 多进程来包装 Camera HAL。

在保留拆分会话的前提下，优化重点调整为：

1. RAW 结果一旦可靠就立即提交给 Zone UI，不等待预览会话恢复完成。
2. 将按 ISO 固定拍摄 1～3 张，改为“单张优先、质量不足时追加”。
3. RAW 质量只评价 **本次待测目标 ROI**，不检查或响应画面其他区域的高光。
4. 少量没有参与最终稳健亮度统计的饱和像素不触发重拍；只有实际使用的亮度统计已经失真时才允许改变曝光重拍。
5. 一次 RAW 只完成当前待测点，不更新已经保存的其他 Zone 点。
6. 质量评估复用当前 native ROI 采样循环，不进行第二次全图扫描，不使用 ML，不创建 Bitmap，不允许其耗时达到重新拍摄一张 RAW 的耗时。
7. 每一种“质量不足”必须对应正确动作：随机噪声才追加同曝光帧，欠曝应改变曝光，坐标或元数据错误应拒绝，不能一律多拍。

预期收益主要来自两部分：

- **感知延迟**：RAW 计算完成后立即显示，省去用户等待会话恢复的时间。
- **实际采集延迟**：普通场景从固定 2～3 张减少为 1 张；只有质量不足时才追加。

本文不承诺消除 Camera2 会话重建耗时，因为这是既定的 HAL 兼容性边界。

## 2. 明确约束与非目标

### 2.1 必须保留

- Zone 常驻预览/YUV 与 RAW-only 测光会话互斥。
- 切换期间保持同一 `CameraDevice` 打开，但当前 `CameraCaptureSession`、输出目标和 `ImageReader` 生命周期仍由协调器安全管理。
- RAW 会话失败后必须能够恢复兼容预览。
- 物理摄像头身份变化时丢弃 RAW 结果。
- RAW Image 和 CaptureResult 必须按传感器时间戳匹配。
- Zone 只把跟踪坐标用于本次待测点的定位，不用一次 RAW 重算历史测光记录。
- RAW、YUV、ISP 三条测光来源继续使用相互独立的校准数据。

### 2.2 本规划明确不做

- 不把 `FULL`（预览 + RAW + YUV）提升为默认会话。
- 不用常驻多输出绕过 RAW-only 会话切换。
- 不在切换 RAW 前使用全画面高光预测来决定减曝光。
- 不因为目标 ROI 外存在饱和高光而重拍。
- 不因为 ROI 内存在少量高光点而自动重拍；只判断它们是否污染最终使用的统计量。
- 不在一张 RAW 上更新全部已有 Zone 点。
- 不依靠当前跟踪器推算其他历史点在 RAW 中的位置。
- 不对 RAW 全图做清晰度、纹理、直方图或噪声扫描。
- 不引入神经网络做 RAW 质量分类。
- 不把 ISO 作为质量结论，只允许把 ISO 作为缺少噪声信息时的保守辅助信号。
- 不把照片观感、锐度或彩色噪点多少直接等同于测光质量。

## 3. 当前实现与延迟来源

### 3.1 当前 Zone RAW 事务

高精度 Zone 测光当前依次执行：

1. 在主线程从 `TextureView` 获取最长边 384 像素的参考图，或使用最近的 YUV 跟踪帧建立局部参考。
2. 如果曝光预览正在使用手动曝光或曝光补偿，恢复中性 AE，等待 2 张稳定结果；超时上限 1,200 ms。
3. 把常驻 `COMPATIBLE` 会话切换为 `RAW_ISOLATED`。
4. RAW 会话配置完成后才开始真正测光。
5. 按最近预览 ISO 固定决定 RAW 数量：
   - ISO < 500：1 张；
   - ISO 500～1199：2 张；
   - ISO ≥ 1200：3 张。
6. RAW `ImageReader` 和提交管线深度均为 1，因此每张 RAW 都是串行采集、配对、分析和关闭。
7. 第一张 RAW 为当前 Zone target 解析预览到传感器的坐标；后续帧复用该坐标。
8. 每张 RAW 在 native 层读取目标 ROI，按 Bayer 通道计算中位数。
9. 多帧完成后对逐帧 EV 和 luma 取中位数。
10. RAW 结果先保存在 `ZoneRawTransaction`。
11. 关闭 RAW-only 会话，恢复预览/YUV 会话。
12. 预览重复请求成功启动后，才把测光结果提交给 UI。

### 3.2 关键代码位置

- `app/src/main/java/com/lightmeter/rawmeter/CameraController.kt`
  - `startMeteringPlan()`：中性 AE 与测光路线入口。
  - `beginZoneRawTransaction()`：启动隔离 RAW 事务。
  - `restoreZoneResidentSession()`：恢复 Zone 常驻会话。
  - `completeZoneRawTransaction()`：当前在恢复完成后才发布读数。
  - `prepareMeteringPreviewBaseline()`：等待中性 AE。
  - `captureDisplayedPreviewReference()`：同步取得小尺寸显示参考。
- `app/src/main/java/com/lightmeter/rawmeter/RawLightMeter.kt`
  - `RawMeteringPolicy.frameCount()`：按 ISO 固定帧数。
  - `MeasurementAccumulator`：固定 `expectedFrames`。
  - `fillPipeline()`：深度为 1 的 RAW 串行管线。
  - `processPair()`：配对后分析、累计和结束。
  - `retryForClippedHighlights()`：当前按 ROI 裁切比例触发减曝光重拍。
- `app/src/main/java/com/lightmeter/rawmeter/MeteringAnalysis.kt`
  - `resolveRawMeteringPoint()`：只在第一张 RAW 定位当前待测点。
  - `analyzeRaw()`：RAW ROI 到单帧 EV。
  - `analyzeRawRegion()`：调用 native 采样。
- `app/src/main/java/com/lightmeter/rawmeter/RawMeterBridge.kt`
  - 当前 native 返回 R/G1/G2/B 中位数、合并裁切比例和总样本数。
- `app/src/main/cpp/raw_meter.cpp`
  - 当前在目标 ROI 中按完整 2×2 Bayer cell 采样。
  - 每通道最多保留 65,536 个样本。
  - 使用 `nth_element` 计算每通道中位数。
- `app/src/main/java/com/lightmeter/rawmeter/MeteringFusion.kt`
  - 当前对逐帧 EV/luma 取中位数。

### 3.3 当前缺少的时间信息

`RawLightMeter` 已记录 RAW 测光内部的 `elapsedMs`，但这个计时从 RAW 测量开始，不完整覆盖：

- 点击到中性 AE 稳定；
- RAW 会话配置；
- RAW 完成到预览恢复；
- 预览恢复到跟踪重新稳定；
- 点击到 Zone 数值第一次显示。

因此不能仅凭当前日志判断某台设备到底慢在采集、分析还是会话切换。实施优化前必须先补齐分阶段计时。

## 4. “RAW 质量”的项目定义

本项目评价的不是整张照片质量，而是：

> 当前 RAW 帧中，本次待测目标 ROI 是否能在已知曝光参数和校准模型下，产生具有足够低随机误差且没有统计失真的 EV100。

质量具有以下边界：

- 必须是 **每个目标 ROI 独立评价**，不能用全画面质量代替。
- 一张 RAW 对当前点可能高质量，对另一位置可能低质量；但本规划不利用它评价其他点。
- 轻微运动模糊不必然降低均匀 ROI 的测光质量。
- 高 ISO 不必然低质量；信号充足时单帧仍可可靠。
- 画面其他区域饱和与当前点无关。
- 当前 ROI 内少量饱和高光也可能与最终中位亮度无关。
- 多帧只能降低随机误差，不能修复错误坐标、错误元数据、错误镜头、校准误差或已经饱和的统计量。

## 5. 低开销质量模型

### 5.1 必须拆分“可用性”和“随机质量”

质量评估输出不使用一个无法解释的总分，而是两级结果：

1. **硬性可用性**：该帧能否用于测光。
2. **随机 EV 不确定度**：若能使用，是否值得再拍一张来降低随机误差。

建议模型：

```kotlin
internal data class RawFrameQuality(
    val validity: RawFrameValidity,
    val action: RawQualityAction,
    val randomErrorEv: Double?,
    val effectiveSnr: Double?,
    val targetStatisticClipped: Boolean,
    val targetStatisticNearBlack: Boolean,
    val validBayerCellCount: Int,
    val noiseSource: RawNoiseEstimateSource,
    val flags: Set<RawQualityFlag>,
)

internal enum class RawFrameValidity {
    VALID,
    INVALID_METADATA,
    INVALID_BUFFER,
    INVALID_TARGET,
    PHYSICAL_CAMERA_CHANGED,
    INSUFFICIENT_SAMPLES,
}

internal enum class RawQualityAction {
    ACCEPT_SINGLE,
    ADD_CONFIRMATION_FRAME,
    RETRY_LONGER_EXPOSURE,
    RETRY_SHORTER_EXPOSURE,
    REJECT,
}
```

`randomErrorEv` 只表示通过追加独立帧可能降低的随机部分。暗角校准、RAW 基线校准、目标映射等系统误差应独立记录，不能伪装成可通过多帧降低的噪声。

### 5.2 硬性可用性检查

以下检查沿用或强化当前已有逻辑，开销为 O(1)：

- Image 与 CaptureResult 时间戳成功配对。
- 捕获期间逻辑/物理相机身份没有改变。
- `SENSOR_EXPOSURE_TIME`、`SENSOR_SENSITIVITY` 和光圈有效。
- 动态或静态黑电平有效。
- 白电平有效且高于所有黑电平。
- CFA 是当前 native 采样支持的 Bayer 排列。
- buffer 地址、capacity、rowStride 和 pixelStride 合法。
- 当前待测 ROI 落在实际有效传感器/裁切范围内。
- 采集到足够的完整 2×2 Bayer cells。
- 当前 `ZoneMeteringTarget` 和坐标 revision 未失效。

任一硬性检查失败时不允许用“再拍同样一张”掩盖。应根据原因重新定位、恢复会话、回退预览流或直接报告失败。

### 5.3 ROI 内永远收集的轻量统计

在 `raw_meter.cpp` 当前采样每个 2×2 Bayer cell 的同一循环中增加：

- 每通道有效样本数；
- 每通道中位数，保持当前输出语义；
- 每通道接近黑电平的样本数；
- 每通道接近白电平的样本数，仅用于判断最终统计是否失真；
- G1/G2 中位数差异，用作黑电平、行列模式或局部异常的廉价提示；
- 有界数量的同色相邻样本差值，用于缺少 Camera2 noise profile 时估算噪声。

禁止增加：

- 第二次 RAW 全图遍历；
- 对 ROI 之外像素的任何读取；
- 额外 Bitmap 或颜色空间全图转换；
- 对所有样本再次完整排序；
- 无上限的临时数组；
- Kotlin/Java 层逐像素循环；
- 神经网络推理。

### 5.4 噪声估计优先级

#### 路径 A：Camera2 噪声模型

当对应物理摄像头的 `SENSOR_NOISE_PROFILE` 存在、长度和数值有效时，优先使用它估算当前 R/G1/G2/B 信号处的随机方差。

优点：

- 只读取元数据和进行少量算术；
- 不需要第二次像素分析；
- 不会把真实物体纹理误判为噪声。

注意：

- 必须绑定实际物理相机身份；
- 无效、缺失或明显异常的 vendor metadata 不能强行使用；
- 需要通过真机重复拍摄标定一个保守修正系数；
- 不能把模型计算结果描述为绝对保证。

#### 路径 B：有界相邻差值估计

Noise Profile 不可用时，在 native ROI 采样过程中对同 CFA 通道、相隔固定 Bayer cell 的邻近样本计算绝对差值。

要求：

- 每通道最多保留固定数量，例如 512～2,048 个差值；
- 使用确定性步长采样，不使用随机数；
- 使用中位绝对差或低分位差，避免少量边缘主导；
- 纹理可能让噪声被高估，但不应让噪声被低估；
- 如果 ROI 纹理太强导致估计不可解释，回退保守策略，而不是扫描更多像素。

对于相互独立、同方差的高斯噪声，邻接差值可近似换算为单像素噪声。实现时不应把理论常数写成不可调真理，应通过设备重复拍摄确定保守系数。

#### 路径 C：元数据不足的保守回退

当 noise profile 和有界经验估计都不可用时：

- 不宣布单帧“高质量”；
- 使用目标 ROI 的黑电平余量、有效样本数和实际 ISO形成保守判断；
- 普通信号可接受一张；
- 接近黑电平或来源不稳定时最多追加一张确认；
- 不恢复旧的“所有 ISO ≥ 1200 固定三张”逻辑。

### 5.5 从信号噪声换算为 EV 不确定度

设目标 ROI 的稳健亮度为 `L`，该亮度估计的随机标准差为 `σL`：

```text
σEV ≈ σL / (L × ln 2)
```

当前每通道使用空间中位数。高斯近似下，中位数的标准误差可从单像素噪声、有效独立样本数和一个保守效率系数估计。

不能直接把最多 65,536 个像素全部视为独立样本。行列噪声、读出相关性和降采样结构会降低有效样本数。建议：

- 使用 `effectiveSampleCount = min(actualCount, calibratedCap)`；
- 第一版使用保守上限；
- 根据灰卡重复拍摄结果按摄像头路由校准修正系数；
- 不按机型硬编码理论 SNR。

建议初始质量区间，最终门槛必须由真机数据冻结：

- `randomErrorEv ≤ 0.03 EV`：允许单帧直接接受；
- `0.03 EV < randomErrorEv ≤ 0.10 EV`：一般可接受，可结合模式偏好决定是否追加；
- `randomErrorEv > 0.10 EV`：追加或改变曝光；
- `randomErrorEv > 0.20 EV`：不输出高置信度测量。

这些是工程起始值，不是现有设备已经达到的承诺。

### 5.6 黑电平附近的判断

单纯追加同曝光帧只能按平方根规律改善随机噪声，无法有效挽救严重欠曝。建议区分：

- **轻度随机噪声偏高**：追加一张同曝光确认。
- **最终使用的通道中位数接近黑电平**：在 RAW-only 会话内使用更长曝光或更合适的 ISO重拍。
- **曝光已经受最大时长、运动或会话限制**：保留单帧结果但标记低置信度，或回退经过校准的预览流。

“接近黑电平”应由中位信号与噪声标准差的比值判断，不使用固定 RAW code 值跨设备判断。

### 5.7 饱和判断的重新定义

本规划取消“ROI 中超过 1% 样本饱和就减曝光重拍”的普遍规则。

原因：当前亮度由每通道中位数等稳健统计产生，少量镜面高光、灯点或非目标亮部不一定参与最终统计。为了保护未使用的高光而重拍会增加延迟，并可能降低目标主体的信噪比。

新的判断原则：

1. ROI 外的饱和完全忽略。
2. ROI 内的饱和比例只作为诊断，不直接触发重拍。
3. 只有以下情况允许 `RETRY_SHORTER_EXPOSURE`：
   - 一个或多个实际参与亮度换算的通道中位数已经接近白电平；
   - 饱和样本已经多到使选定的稳健统计量无法代表目标；
   - 颜色变换所需通道失真，无法可靠恢复 luminance。
4. 如果最终中位数仍有足够白电平余量，则接受结果，不保护未参与测光的高光。

建议把现有 `RawHighlightProtectionPolicy` 重构为更准确的 `RawExposureRetryPolicy`，避免继续以“高光保护照片”的语义指导测光。

## 6. 自适应 RAW 帧决策

### 6.1 第一张 RAW

第一张完成后立即生成 `MeteringFrameStat + RawFrameQuality`：

```text
第一张 RAW
   │
   ├─ 硬性无效 ──────────────→ REJECT / 回退
   │
   ├─ 使用的统计量已饱和 ────→ 缩短曝光重拍
   │
   ├─ 使用的统计量严重欠曝 ──→ 延长曝光重拍
   │
   ├─ 仅随机误差偏高 ─────────→ 追加同曝光确认帧
   │
   └─ 随机误差足够低 ─────────→ 立即接受
```

不得因为：

- ISO 超过固定阈值；
- RAW 看起来有彩噪；
- 全画面存在高光；
- ROI 内有少量未参与中位数的饱和点；

而直接要求三张 RAW。

### 6.2 第二张 RAW

仅在第一张显示为随机误差偏高或需要确认时拍摄。第二张必须：

- 使用相同物理摄像头；
- 使用相同或明确规划的新曝光参数；
- 独立进行时间戳配对和质量检查；
- 仍然只测当前 pending target；
- 不借机更新其他 Zone 点。

若两张的 EV 差值满足：

```text
abs(EV1 - EV2) <= max(minimumAgreementEv, agreementSigma × combinedRandomError)
```

则融合并结束。初始可把 `minimumAgreementEv` 设为 0.05 EV附近，具体值由重复拍摄数据确定。

### 6.3 是否需要第三张

第三张不是 ISO 触发，而是解决“两张无法区分哪张异常”的问题。只在以下条件同时满足时允许：

- 两张均通过硬性检查；
- 目标坐标和物理相机身份没有失效；
- 两张 EV 明显不一致；
- 随机噪声、灯光闪烁或短暂异常仍是合理解释；
- 当前 RAW-only 会话仍健康；
- 总测光时间未超过软预算。

如果不一致更可能来自移动目标、坐标错位或场景变化，第三张不能解决，应停止并返回不确定状态。

### 6.4 最大帧数

- 普通测光：目标 1 张。
- 需要确认：2 张。
- 两张冲突且条件允许：最多 3 张。
- 改变曝光的重拍计入总请求预算，防止“噪声追加 + 欠曝重拍 + 饱和重拍”无限组合。
- 一个 Zone RAW 事务建议仍保持最多 3 次有效 RAW 请求；超过后回退或失败。

## 7. 多帧融合修改

### 7.1 只融合通过质量门控的帧

无效元数据、错误镜头、统计量饱和、严重欠曝或坐标失效的帧不能进入 `MeteringFusion`。

### 7.2 一张

质量达到门槛时直接使用该帧，不执行伪融合。

### 7.3 两张

先做一致性检查，再融合。可以在 EV 域依据 `randomErrorEv` 做逆方差加权；当两帧预计误差接近时等价于平均。

不能在两帧明显冲突时直接取平均，因为平均值可能两边都不正确。

### 7.4 三张

三张均有效且属于同一稳定目标时，使用稳健中位数或有权重的稳健估计。质量字段应反映：

- 单帧随机误差；
- 帧间实测离散；
- 最终融合后的保守误差上限。

### 7.5 输出精度

如果最终预计误差大于 UI 展示精度：

- 不显示超出可信度的小数；
- Zone 内部计算仍可保留 Double；
- 诊断页显示 `±EV` 和追加帧原因；
- 不用多拍来追求 UI 并不展示的微小精度。

## 8. RAW 结果与会话恢复解耦

### 8.1 当前问题

当前 `ZoneRawTransaction.reading` 要等 resident session 重新配置并成功启动预览后才通过 `callback.onMeterReading()` 发布。这使用户等待时间包含后置恢复。

### 8.2 推荐状态

把“测量结果生命周期”和“相机会话生命周期”拆开：

```text
SWITCHING_TO_RAW
      ↓
CAPTURING
      ↓
RESULT_READY ─────→ UI 完成当前 Zone 点并显示数值
      ↓
RESTORING_SESSION ─→ UI 保持结果，但暂时禁用下一次测量
      ↓
READY ────────────→ 恢复跟踪和交互
```

建议 `ZoneRawTransaction` 增加：

```kotlin
var resultDelivered: Boolean = false
var restoreStartedAtNs: Long? = null
var restoreCompletedAtNs: Long? = null
```

不要在结果发布时把 `meteringOperationActive` 设为 `false`。否则用户会在 resident session 尚未恢复时发起下一次测量。

### 8.3 Callback/UI 修改建议

在 `CameraControllerCallback` 中增加明确的恢复状态通知，例如：

```kotlin
fun onMeteringResultReady(reading: MeterReading, cameraRestoring: Boolean)
fun onMeteringSessionRestored()
```

或者保留 `onMeterReading()`，另外增加：

```kotlin
fun onMeteringRestoreStateChanged(restoring: Boolean)
```

UI 需要把两个状态分开：

- `zoneMeasurementPending`：还没有测光数值。
- `cameraRestorePending`：数值已有，但相机尚未恢复可交互状态。

行为要求：

- `onMeteringResultReady` 立即调用 `completeZoneMeasurement(reading)`。
- 当前 marker 从 pending 变为已测量，不再显示无限等待。
- 恢复期间保留最后预览纹理和 Zone 数值。
- 禁用新增点、清空、镜头切换和可能重建会话的操作。
- 恢复完成后再恢复跟踪、标记按钮和触屏放点。
- 恢复失败时不能删除已经可靠产生的读数；应显示“结果已保存，但预览恢复失败”，再进入相机恢复流程。

普通模式和校准模式可以保留原语义；第一阶段仅对 `ZoneRawTransaction` 应用提前发布。

## 9. 中性 AE 等待的后续优化

中性 AE 等待最多可能增加 1,200 ms，但不能在没有证据时直接删除。

建议第二阶段引入 `MeteringBaselinePolicy`：

- 当前预览已经是中性 AE：立即继续，保持现状。
- 曝光预览参数仍能让目标 ROI 落在安全信号范围：直接构建明确的 RAW 曝光请求。
- 当前曝光预览可能导致严重欠曝或实际测光统计饱和：恢复中性 AE。
- 元数据不完整、设备手动曝光不可靠：维持现有两帧稳定等待。

安全信号判断只使用：

- 当前同步预览目标 ROI；
- 实际曝光时间和 ISO；
- 当前曝光补偿/手动预览状态；
- 已有相机校准。

它不能扫描全画面，也不能为了未测量高光延迟 Zone 点测。

该阶段风险高于自适应 RAW 和提前发布，应在两者验证后实施。

## 10. 质量评估性能预算

### 10.1 定义“增量质量开销”

区分：

- `baseAnalysisTime`：当前读取 ROI 和计算通道中位数所需时间。
- `qualityIncrementTime`：为了质量判断新增的统计、噪声估算和决策时间。
- `additionalRawTime`：从提交下一张 RAW 到 Image/Result 配对成功的时间，不含会话切换。

用户约束对应硬性验收条件：

```text
P95(qualityIncrementTime) < P50(additionalRawTime)
```

质量判断如果比追加一张 RAW 还慢，就失去自适应价值。

进一步建议的目标预算：

- 中端设备：增量质量计算 P90 不超过 5 ms。
- 低端支持 RAW 的设备：增量质量计算 P90 不超过 10 ms。
- 增量内存固定且有界，目标不超过数十 KB。
- 不增加 full-frame copy。
- 不增加 JNI 往返次数；质量字段和亮度字段一次返回。

绝对数字需要由真机基线验证。如果某设备 RAW 采集异常快速，则仍以“低于追加 RAW 时间”的相对条件为最终门槛。

### 10.2 实现约束

- 在现有 C++ ROI 循环中累加所有质量统计。
- 使用固定大小 `std::array` 或预先 `reserve` 的小型 reservoir。
- 不对四个完整样本数组再次复制并排序。
- 如果需要分位数，使用小型固定 histogram 或 bounded reservoir。
- `SENSOR_NOISE_PROFILE` 的解析在 Kotlin 层每帧只执行一次。
- 相机静态 noise metadata 按物理 camera id 缓存。
- 日志只输出最终摘要，不输出每通道每块明细。

### 10.3 运行时预算保护

建议增加开发期 `RawQualityBudgetMonitor`：

- 记录 quality 增量耗时滑动分位数；
- 若经验噪声估计连续超预算，当前会话关闭可选经验估计；
- 退回 Camera2 noise profile 或保守轻量规则；
- 不因质量评估超时阻塞 Image.close() 和下一步会话恢复。

正式版可只保留低成本计数和诊断开关，不需要持续输出日志。

## 11. 详细代码修改清单

本节描述后续代码修改，本次不执行。

### 11.1 新增 `RawMeteringQuality.kt`

建议位置：

`app/src/main/java/com/lightmeter/rawmeter/RawMeteringQuality.kt`

包含：

- `RawFrameQuality`
- `RawFrameValidity`
- `RawQualityAction`
- `RawQualityFlag`
- `RawNoiseEstimateSource`
- `RawRegionStatistics`
- `RawMeteringQualityEvaluator`
- `RawAdaptiveCapturePolicy`

职责：

- 只处理纯数据和数学决策；
- 不持有 CameraDevice、Image 或 Handler；
- 能在 JVM 单元测试中使用合成数据；
- 所有门槛集中定义，不散落在 CameraController/native/UI。

### 11.2 修改 `RawMeterBridge.kt`

建议保留旧接口一段迁移期，新增结构化版本，例如：

```kotlin
external fun analyzeRawRegionV2(...): DoubleArray
```

Kotlin wrapper 立即把固定数组转换为 `RawRegionStatistics`，并校验：

- 数组版本；
- 长度；
- 所有计数和比例范围；
- NaN/Infinity；
- 通道顺序。

不要让业务层直接使用魔法下标。可以由 companion constants 或单独 decoder 管理 native ABI。

### 11.3 修改 `raw_meter.cpp`

在当前 2×2 Bayer cell 循环中：

- 保持现有四通道中位数计算，防止测光结果在迁移时发生无意变化；
- 将总 `clipped` 改为每通道计数，同时保留合并诊断值；
- 增加每通道 near-black 计数；
- 增加有界相邻同色差值 reservoir；
- 返回每通道有效样本数；
- 不读取 ROI 之外像素；
- 不增加第二个完整 ROI pass；
- 不改变 CFA 映射；
- 不增加未对齐读取或 buffer 越界风险。

第一版不要实现复杂 FFT、行列频谱、全尺寸直方图或 OpenCV 处理。

### 11.4 修改 `MeteringAnalysis.kt`

把 `analyzeRaw()` 的内部结果扩展为：

```kotlin
internal data class RawFrameMeasurement(
    val stat: MeteringFrameStat,
    val quality: RawFrameQuality,
)
```

修改内容：

- `analyzeRawRegion()` 返回 `RawRegionStatistics`，而不是裸 `DoubleArray`。
- 保持现有黑电平、白电平、CFA、色彩增益、颜色矩阵、暗角校准和 EV 公式。
- 从 native 统计和 noise metadata 计算 `randomErrorEv`。
- 饱和判断只检查当前目标 ROI 和实际使用的通道统计。
- `RawMeterPoint.matchScore` 作为坐标诊断输入，但不为质量评估重复执行额外位置扫描。
- 几何回退仍允许均匀低纹理目标测光，不能因为没有外观相关性就一律拒绝。
- quality flags 进入单行诊断日志。

### 11.5 修改 `RawLightMeter.kt`

#### 删除固定启动帧数

- `RawMeteringPolicy.frameCount()` 不再决定最终采集数。
- `MeasurementAccumulator.expectedFrames` 改为动态状态，例如：
  - `submittedFrames`
  - `completedFrames`
  - `maxRequests`
  - `acceptedMeasurements`
  - `currentExposureStage`
  - `decisionHistory`

#### 修改 `processPair()`

新顺序：

1. 解析当前 target 的 RAW 坐标，仅第一帧执行现有匹配。
2. 一次 native 调用得到亮度与质量统计。
3. 关闭 Image，不能让策略逻辑延迟 buffer 释放。
4. 调用 `RawAdaptiveCapturePolicy.decide()`。
5. `ACCEPT_SINGLE`：立即完成。
6. `ADD_CONFIRMATION_FRAME`：保持曝光，提交下一张。
7. `RETRY_LONGER_EXPOSURE`：更新 request 后提交。
8. `RETRY_SHORTER_EXPOSURE`：仅在实际统计量饱和时更新 request。
9. `REJECT`：回退或错误。

#### 重构高光策略

- 移除 `stat.clipped > 0.01` 即重拍的直接路径。
- 将 `retryForClippedHighlights()` 替换为通用的 `applyQualityDecision()`。
- 不再使用全局“保护高光”命名。
- 旧 `RawHighlightProtectionPolicy` 在迁移完成后删除或只保留曝光缩短数学函数。

#### 进度回调

启动时帧数未知，`onRawMeteringStarted(frameCount)` 不能继续假装有固定总数。建议改为：

```kotlin
fun onRawMeteringStarted()
fun onRawMeteringProgress(captured: Int, reason: RawCaptureReason?)
```

UI 可显示：

- “正在读取 RAW”；
- “正在确认暗部信号”；
- “正在复核不稳定读数”；

不必显示可能变化的“共 3 张”。

### 11.6 修改 `MeteringFusion.kt`

- 输入从 `List<MeteringFrameStat>` 改为只包含已通过门控的 `List<RawFrameMeasurement>`，或由调用方传入过滤后的统计与误差。
- 一张直接返回。
- 两张先做一致性门控，再按不确定度融合。
- 三张使用稳健估计。
- `MeterReading` 增加可选：
  - `estimatedErrorEv`
  - `qualityLevel`
  - `captureDecisionSummary`
- YUV/ISP 路径可以暂时把这些字段设为 null，避免一次重写所有来源。

### 11.7 修改 `ZoneRawTransaction.kt` 与状态机

- 为结果已发布和会话已恢复建立独立状态。
- 事务只有 resident session 恢复后才能从 coordinator 清除。
- 结果只能发布一次。
- 迟到 callback 不能重复完成 marker。
- 恢复失败不能覆盖已经发布的可靠读数。
- 保存阶段时间戳用于延迟报告。

### 11.8 修改 `CameraController.kt`

- `onRawMeteringReading()`：Zone 事务中立即发布读数，再开始恢复。
- `completeZoneRawTransaction()`：只完成相机会话恢复和解锁，不再次发布读数。
- `beginZoneRawTransaction()`：记录点击/切换起始时间。
- `onSessionConfigured()`：记录 RAW 配置完成和 resident 配置完成时间。
- `prepareMeteringPreviewBaseline()`：保留第一阶段行为，并记录实际等待。
- 后续再引入有条件 baseline policy，不与第一阶段同时大改。
- 不改变 `RAW_ISOLATED` 输出组合。
- 不向 RAW 请求添加其他 Zone marker。

### 11.9 修改 `CameraControllerCallback.kt`、`MainActivity.kt` 和 Zone UI

- 分离“数值已得到”与“相机恢复完成”。
- Zone marker 在数值到达时立即完成。
- 会话恢复期间保持捕获控件禁用。
- 不把恢复中的状态重新显示成“还在测量亮度”。
- 增加简短的“正在恢复预览”状态，但不遮挡已经得到的 Zone 值。
- 跟踪器只在 resident session 和坐标映射稳定后恢复。

### 11.10 诊断字段

每次 Zone RAW 事务建议记录：

```text
baselineWaitMs
rawSessionConfigureMs
firstRawAcquireMs
rawAnalysisMs
qualityIncrementMs
extraRawCount
extraRawReasons
rawResultReadyMs
residentRestoreMs
tapToResultMs
tapToReadyMs
estimatedErrorEv
noiseSource
qualityFlags
```

日志不保存 RAW 图像、ROI 像素或可识别内容。

## 12. 测试规划

### 12.1 纯单元测试

新增：

- `RawMeteringQualityEvaluatorTest.kt`
- `RawAdaptiveCapturePolicyTest.kt`
- `RawFrameAgreementTest.kt`
- `MeteringFusionTest.kt`
- `ZoneRawResultDeliveryTest.kt`

覆盖：

- 高 ISO 但信号充分时单帧接受；
- 低 ISO 但接近黑电平时不错误判高质量；
- ROI 外饱和不进入质量输入；
- ROI 内少量饱和但通道中位数安全时不重拍；
- 实际通道中位数接近白电平时缩短曝光；
- 随机误差偏高时只追加一张确认；
- 两张一致时融合；
- 两张冲突时不直接平均；
- 第三张总请求上限；
- metadata 缺失和物理相机变化硬拒绝；
- 结果发布一次，恢复完成不重复发布；
- 一个 RAW 事务不会修改其他 Zone marker。

### 12.2 Native 统计测试

使用人工构造的 RAW16 Bayer buffer 覆盖：

- RGGB/GRBG/GBRG/BGGR；
- rowStride padding 和非零 buffer position；
- 均匀信号；
- 已知高斯噪声；
- 少量 hot pixels；
- 少量饱和点但中位数不变；
- 超过一半目标样本饱和；
- 接近黑电平；
- buffer 截断和非法 stride；
- 最大样本上限和 deterministic stepping。

验证新统计不改变旧版四通道中位数的允许误差。

### 12.3 真机重复测量

每台代表设备对固定灰卡重复采集至少三组：

- 明亮低 ISO；
- 室内中 ISO；
- 低照度高 ISO；
- 均匀目标；
- 有纹理目标；
- ROI 内含少量反光点；
- 画面其他区域存在强高光但目标 ROI 正常；
- 目标统计量真实饱和；
- 50 Hz/60 Hz 人工照明；
- 手持与三脚架。

对比：

1. 当前固定 1/2/3 帧。
2. 强制单帧。
3. 新自适应策略。
4. 三帧离线参考结果。

### 12.4 延迟测试

必须分别测量：

- 点击到 RAW 会话配置完成；
- 第一张 RAW request 到 Image/Result 配对；
- 原始 ROI 分析；
- 增量质量评估；
- 追加一张 RAW；
- RAW 完成到数值显示；
- RAW 完成到预览恢复；
- 点击到数值显示；
- 点击到可再次操作。

只报告总时长不足以验证优化是否生效。

## 13. 验收标准

### 13.1 架构安全

- Zone 仍使用 `RAW_ISOLATED` 互斥会话路线。
- 不增加默认并发输出数。
- HAL 配置失败率不得高于当前版本。
- RAW 失败后 resident preview 恢复能力无回归。
- 物理镜头切换保护无回归。

### 13.2 行为正确

- RAW 结果可靠后立即显示，不等待 resident session 完成。
- 恢复期间不能开始新的相机事务。
- 画面/ROI 外高光永不触发重拍。
- ROI 内未影响最终亮度统计的少量饱和样本不触发重拍。
- 每次事务只写当前 pending Zone marker。
- 历史 marker 的 EV、位置和权重不因本次 RAW 重新计算。

### 13.3 质量

初始建议门槛，需由真机数据确认：

- 普通光照下，自适应结果相对固定三帧参考的 P90 差异不超过 0.05 EV。
- 低照度下 P90 差异不超过 0.10 EV，超过时必须降低质量等级。
- 自适应策略不得增加明显的错误跳变或系统偏差。
- 同一灰卡重复测量的离散应与预计 `randomErrorEv` 同量级，不能长期低估。
- 单帧接受率不是独立 KPI；不能为了提高单帧比例接受不可靠结果。

### 13.4 性能

- `P95(qualityIncrementTime) < P50(additionalRawTime)`。
- 中端设备增量质量评估 P90 目标 ≤5 ms。
- 低端 RAW 设备增量质量评估 P90 目标 ≤10 ms。
- 不新增 full-frame copy、Bitmap 或第二次 ROI 全遍历。
- 增量内存固定有界。
- RAW Image 必须在分析结束后立即关闭。

### 13.5 用户体验

- `tapToResultMs` 显著小于 `tapToReadyMs`，用户先得到结果、后恢复相机。
- 恢复提示不遮挡测量值。
- 追加帧必须给出准确原因，而不是固定显示“读取三张”。
- 恢复失败时已得到的可靠 Zone 读数不丢失。

## 14. 分阶段实施顺序

### Phase 0：只增加计时，不改变行为

1. 为 Zone RAW 事务添加完整阶段时间戳。
2. 记录 RAW 采集、基础分析和 resident 恢复耗时。
3. 在低、中、高档设备建立现状基线。
4. 确认延迟主要分布，避免盲目优化 native 计算。

验收：数值和会话行为完全不变。

### Phase 1：提前发布结果

1. 分离“结果已得到”和“会话已恢复”。
2. RAW 读数产生后立即完成当前 Zone marker。
3. 恢复期间保持相机操作锁定。
4. 补充 callback 顺序测试和恢复失败测试。

验收：测光数值与当前版本一致，HAL 流程不变，感知延迟减少 resident restore 时长。

### Phase 2：新增低开销 RAW ROI 统计

1. 扩展 native 单次 ROI 遍历输出。
2. 增加 noise profile 解析和有界经验回退。
3. 先以 shadow 模式运行：计算质量但不影响帧数。
4. 对比预计 EV 误差和真实重复测量离散。
5. 验证质量增量耗时预算。

验收：旧测光结果无显著数值变化，质量估计不过度乐观，开销小于追加 RAW。

### Phase 3：启用单帧优先

1. 第一张质量高时直接结束。
2. 随机误差不足时追加第二张。
3. 两张冲突时有条件第三张。
4. 总 RAW 请求数严格封顶。
5. 删除按 ISO 固定三张的行为。

验收：精度门槛满足，普通场景实际 RAW 数下降。

### Phase 4：重构曝光重拍

1. 移除 1% ROI 裁切即重拍。
2. 只有最终使用统计量失真时才缩短曝光。
3. 严重欠曝时优先优化曝光，而不是盲目叠加同曝光帧。
4. 保留请求总数和事务超时限制。

验收：少量目标高光和 ROI 外高光不增加延迟，真实统计饱和仍能安全处理。

### Phase 5：有条件减少中性 AE 等待

1. 建立目标 ROI 信号预测。
2. 在明确安全时直接提交显式 RAW 曝光。
3. 不可靠设备继续使用现有两帧 AE 稳定流程。
4. 单独 A/B 验证，不能与自适应帧数同时首次上线。

验收：不增加欠曝、饱和和厂商曝光不一致问题。

## 15. 风险与回退

### 15.1 质量估计低估噪声

风险：单帧被错误接受，读数抖动。

措施：

- noise profile 使用保守修正系数；
- 用真机重复测量校准；
- 第一版门槛偏保守；
- 提供开发开关恢复固定多帧。

### 15.2 质量估计把纹理当噪声

风险：不必要地追加第二张。

措施：

- 优先 noise profile；
- 经验估计使用同色近邻和稳健低分位；
- 严格限制 reservoir；
- 不扩大扫描范围。

### 15.3 提前显示后用户立即再次操作

风险：resident session 尚未恢复，触发新事务。

措施：

- 分离结果状态和相机 ready 状态；
- 恢复完成前保持 CameraController 操作锁；
- UI 明确显示恢复状态。

### 15.4 两帧期间目标移动

风险：固定 RAW sensor point 测到不同内容。

措施：

- 高质量第一帧立即结束；
- 追加帧总数和时间受限；
- 两帧 EV 冲突不直接平均；
- 明显运动时不通过第三张掩盖问题；
- 不借助当前跟踪精度更新其他点。

### 15.5 Native ABI 错误

风险：数组下标或版本不一致导致错误亮度。

措施：

- 新接口带版本字段；
- Kotlin 立即结构化解析和完整校验；
- 保留旧接口用于对照；
- 合成 Bayer buffer 测试覆盖全部 CFA。

## 16. 最终推荐方案

保持 Camera HAL 安全架构不变：

```text
Zone 预览/YUV
      ↓
隔离 RAW-only 会话
      ↓
只测当前 pending target
      ↓
同一次 ROI 遍历计算亮度 + 低开销质量
      ↓
质量足够：单帧立即发布
随机误差高：最多追加确认帧
实际统计失真：改变曝光重拍
      ↓
后台恢复 Zone 预览/YUV
```

最先实施的两项应是：

1. **RAW 结果发布与 resident session 恢复解耦**，直接缩短用户等待。
2. **低开销质量 shadow 评估**，先验证单帧在真实设备上的可接受比例和误差预测能力。

只有 shadow 数据证明质量模型可靠且计算成本远小于新拍 RAW 后，才启用自适应帧数。不要直接从固定多帧切换为无条件单帧。

本规划不会利用未测量区域的高光触发重拍，不会用一张 RAW 更新全部 Zone 点，也不会以牺牲 HAL 兼容性换取表面上的速度。

