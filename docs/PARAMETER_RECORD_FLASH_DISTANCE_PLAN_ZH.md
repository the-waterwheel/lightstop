# 参数记录增加闪光灯与距离信息简要实施规划

> 文档状态：实施规划稿，本次仅新增文档，不修改应用代码  
> 编写日期：2026-09-09

## 1. 目标

参数记录保存时，除现有曝光、EI、测光值、Zone 点、胶片、GPS、备注和可选 RAW 外，同时保存：

- 当时实际应用的闪光灯配置；
- 闪光灯补偿计算结果；
- 保存瞬间可用的距离状态和距离估值；
- 手动距离或自动距离的来源、质量和有效性。

历史记录必须使用保存时的快照，不能打开记录时重新读取当前闪光灯设置或当前相机距离。

## 2. 数据语义

### 2.1 闪光灯快照

建议新增：

```kotlin
data class RecordedFlashSnapshot(
    val guideNumberIso100: Double,
    val configuredIso: Int,
    val powerDenominator: Int,
    val lossStops: Double,
    val distanceMode: RecordedFlashDistanceMode,
    val configuredDistanceMeters: Double?,
    val effectiveDistanceMeters: Double?,
    val effectiveGuideNumber: Double?,
    val compensationStops: Double,
    val adjustmentStatus: RecordedFlashAdjustmentStatus,
)

enum class RecordedFlashDistanceMode {
    MANUAL,
    AUTO,
}

enum class RecordedFlashAdjustmentStatus {
    APPLIED,
    DISTANCE_UNAVAILABLE,
    FLASH_DOMINATES,
    INVALID,
}
```

说明：

- `guideNumberIso100` 保存 ISO 100 米制指数原值。
- `configuredDistanceMeters` 只在手动距离模式下存在。
- `effectiveDistanceMeters` 保存本次闪光计算实际使用的距离。
- Auto 模式距离不可用时，`effectiveDistanceMeters = null`，同时保留 `DISTANCE_UNAVAILABLE` 状态。
- `compensationStops` 和 `adjustmentStatus` 保存当时已经得到的计算结果，确保历史记录可解释。
- 没有应用闪光灯时，整个 `flash` 字段为 null，不保存仅在工具页编辑但未应用的配置。

### 2.2 距离快照

建议新增：

```kotlin
data class RecordedDistanceSnapshot(
    val status: DistanceMeasurementStatus,
    val meters: Double?,
    val lowerMeters: Double?,
    val upperMeters: Double?,
    val confidence: Double?,
    val quality: DistanceQuality?,
    val source: DistanceSource?,
    val isFreshAtCapture: Boolean,
    val ageMsAtCapture: Long?,
    val cameraIdentity: String?,
    val targetX: Float?,
    val targetY: Float?,
    val sampleCount: Int?,
)
```

约束：

- 保存米制原始值，不保存经过 UI 四舍五入的文本。
- 不持久化 `timestampNs` 原值，因为它通常属于本次启动的单调时钟或传感器时间域；只在能使用同一 Camera2 时间域计算时保存 `ageMsAtCapture`，否则为 null。
- 过期估值可以作为诊断记录，但不得写入 `effectiveDistanceMeters` 并冒充实际用于闪光计算的距离。
- 即使没有应用闪光灯，只要当时存在距离状态，也可以独立保存 `distance`。
- Zone 多点记录暂不为每个 `RecordedZonePoint` 复制同一距离。本字段表示“保存记录瞬间的活动距离”，不能宣称是每个历史 Zone 点各自的物距。

### 2.3 快照时机

在 `MeterLayout.currentParameterSnapshot()` 创建 `ParameterMeterSnapshot` 时，同时冻结：

- `state.appliedFlashConfiguration`；
- `state.currentFlashAdjustment()`；
- `state.distanceMeasurementState`；
- 距离估值在该时刻的新鲜度和年龄。

不要等预览 JPEG、GPS 或可选 DNG 保存完成后再读取这些状态，否则相机对焦、Auto 距离或闪光设置可能已经变化。

## 3. 模型和存储修改

### `ParameterRecordModels.kt`

- 新增 `RecordedFlashSnapshot`、`RecordedDistanceSnapshot` 和距离模式枚举。
- `ParameterMeterSnapshot` 增加：

```kotlin
val flash: RecordedFlashSnapshot?
val distance: RecordedDistanceSnapshot?
```

- `ParameterRecordEntry` 增加相同字段。
- 新字段提供 null 默认值，避免旧调用点和旧记录立即失效。

### `MeterLayout.kt`

- Normal 和 Zone 两条 `currentParameterSnapshot()` 路径都创建闪光与距离快照。
- 只记录已应用的闪光配置。
- 距离状态独立记录，不依赖是否启用闪光。

### `ParameterRecordRepository.kt`

- JSON 索引版本从 2 升为 3。
- 每条记录增加可选对象：

```json
{
  "flash": {
    "guideNumberIso100": 36.0,
    "configuredIso": 100,
    "powerDenominator": 4,
    "lossStops": 1.0,
    "distanceMode": "AUTO",
    "configuredDistanceMeters": null,
    "effectiveDistanceMeters": 2.43,
    "effectiveGuideNumber": 12.73,
    "compensationStops": 0.72,
    "adjustmentStatus": "APPLIED"
  },
  "distance": {
    "status": "AVAILABLE",
    "meters": 2.43,
    "lowerMeters": 2.20,
    "upperMeters": 2.71,
    "confidence": 0.82,
    "quality": "MEDIUM",
    "source": "FOCUS_APPROXIMATE",
    "isFreshAtCapture": true,
    "ageMsAtCapture": 84,
    "cameraIdentity": "0",
    "targetX": 0.5,
    "targetY": 0.5,
    "sampleCount": 8
  }
}
```

- 旧版记录缺少字段时读取为 null。
- 未知枚举值不得使整个记录加载失败，应回退为 null/未知状态。
- 写入前校验距离、置信度、边界和样本数，拒绝 NaN、Infinity 和非正距离；手动无穷远若未来需要保存，应使用明确枚举，不能写 JSON Infinity。

### 参数记录展示

在 `RecordedMeteringRenderer` 的详情摘要中增加只读信息：

- 闪光：GN、功率、损耗、手动/Auto；
- 实际距离：数值、来源和质量；
- 闪光补偿：补偿 EV 或不可用原因；
- Auto 距离过期时显示“保存时距离已过期”，不显示为有效值。

列表卡片保持简洁，可只显示“Flash”和距离摘要；完整字段放在记录详情中。参数记录编辑器暂不允许修改这些技术快照，避免配置、有效距离和补偿结果互相矛盾。

## 4. 兼容性和隐私

- v2 及更早记录继续正常加载，闪光和距离显示为“未记录”。
- 新字段只增加 JSON 元数据，不改变 JPEG、DNG 和事务文件移动逻辑。
- 距离可能描述拍摄对象与设备的空间关系，参数记录隐私提示应补充“闪光灯与距离信息”。
- 不增加权限，不保存对焦图像、深度图或 Camera2 原始厂商诊断字符串。

## 5. 测试

至少增加：

- 手动距离 + 已应用闪光配置的 JSON round-trip。
- Auto CALIBRATED/APPROXIMATE 距离及质量字段 round-trip。
- Auto 距离过期或不可用时，不产生有效闪光距离。
- 未应用闪光但存在距离时，只保存 distance。
- 无距离、无闪光时保持旧记录行为。
- v2 JSON 缺少新字段时正常迁移。
- 未知 source/quality/status 枚举不破坏整条记录。
- 保存开始后距离状态变化，不改变已经冻结的快照。
- Zone 多点记录不会错误地给每个点写入同一距离。

## 6. 推荐实施顺序

1. 新增记录快照模型和纯转换函数。
2. 在 `currentParameterSnapshot()` 冻结闪光与距离状态。
3. 升级 JSON v3，并完成旧数据兼容测试。
4. 将快照传入 `ParameterRecordEntry`。
5. 在记录详情中增加只读展示。
6. 更新参数记录隐私提示。
