# 光档多源自动测距优化规划

## 1. 文档目的

本文档用于指导光档（lightstop）建立统一、多来源、可自动降级的物理距离测量系统，主要服务于闪光曝光计算，也为后续景深计算、Zone 目标距离记录等功能预留统一接口。

本文档是实施规划，不代表当前代码已经实现其中能力。

## 2. 目标

- 尽可能覆盖更多 Android 设备。
- 优先提高 0.5～10 米内的测距准确度。
- 支持 Camera2、ARCore Depth 和满足严格条件的多摄立体测距。
- 根据静态能力、实时数据质量和运行失败状态自动降级。
- 支持多来源采集，但不对相关或低质量数据盲目平均。
- 明确显示距离来源、质量、时效性和不支持原因。
- 永远保留手动距离作为最终回退。
- 不引入需要用户输入物体实际尺寸的测距方式。

## 3. 非目标

- 不承诺所有设备都能在 10 米内达到同一精度。
- 不通过厂商私有或隐藏 API 获取双像素、激光或 ToF 数据。
- 不根据机型名称猜测相机间距、内参或外参。
- 不把没有绝对尺度的单目深度神经网络作为米制距离来源。
- 不使用默认物体尺寸、人脸尺寸或身高假设建立绝对尺度。

## 4. 当前实现与问题

当前自动距离来自 [`CameraController.updateDynamicLensInfo()`](../app/src/main/java/com/lightmeter/rawmeter/CameraController.kt)，核心换算为：

```kotlin
distanceMeters = 1f / lensFocusDistanceDiopters
```

可用性由 [`CameraUiInfo.metricFocusDistanceAvailable`](../app/src/main/java/com/lightmeter/rawmeter/MeterModels.kt) 判断：

```kotlin
minimumFocusDistanceDiopters > 0f &&
    focusDistanceCalibration !=
    CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED
```

该实现存在以下问题：

1. `focusDistanceCalibration == null` 时仍会通过判断。
2. 没有验证 `LENS_FOCUS_DISTANCE` 是否存在于实际 CaptureResult。
3. 没有结合 `CONTROL_AF_STATE` 和 `LENS_STATE` 判断 AF 是否成功且稳定。
4. 直接逐帧发布换算后的米值，没有在屈光度空间做鲁棒时间滤波。
5. 距离没有来源、置信度、误差范围、目标位置和时间戳。
6. 距离只绑定到 `CameraUiInfo`，不能表达 ARCore、深度传感器或双目来源。
7. 活动物理镜头切换后只能清空焦距值，缺少统一的估计失效机制。
8. 闪光曝光直接读取 `cameraInfo.focusDistanceMeters`，无法判断估计是否过期或低质量。

## 5. 核心设计原则

### 5.1 严格区分“对焦估距”和“自动测距”

“Camera2 对焦估距”只是自动测距系统中的一个 Provider。

- 固定焦距、UNCALIBRATED 或校准字段缺失的镜头，应显示“此镜头不支持对焦估距”。
- 如果 ARCore 或 Camera2 Depth 仍可用，整体自动测距功能可以继续工作。
- 只有所有自动 Provider 都不可用时，才显示“自动测距不支持，请手动设置”。

### 5.2 质量优先于固定来源顺序

系统不应无条件认为某一来源永远优于另一来源。每个 Provider 必须返回可比较的质量信息，由统一策略决定采用、融合或降级。

### 5.3 不把相关数据重复计权

- ARCore Raw Depth 和 Full Depth 使用相同的底层环境观测，Full Depth 还包含平滑和插值；本方案将二者视为相关结果。
- Camera2 DEPTH16 和同一设备输出的 DEPTH_POINT_CLOUD 也可能来自同一深度系统。
- 同源结果只能作为主结果和补洞/校验结果，不能作为两个独立传感器投票。

### 5.4 宁可拒绝，也不输出虚假精度

当数据过期、来源冲突、目标跨越前后景、距离超过有效范围或预测误差过高时，应降低质量等级、显示范围，或直接要求重新测距。

## 6. 统一架构

```text
DistanceCapabilityCatalog
        │
        ▼
DistanceCoordinator
  ├─ Camera2FocusDistanceProvider
  ├─ Camera2DepthProvider
  ├─ ArCoreDepthProvider
  └─ StereoDistanceProvider
        │
        ▼
DistanceFusionEngine
        │
        ▼
DistanceState / DistanceEstimate
        │
        ├─ FlashExposureMath
        ├─ 距离拨盘与状态提示
        └─ 后续景深、Zone 与记录功能
```

建议新增以下模型：

```kotlin
enum class DistanceSource {
    CAMERA2_DEPTH16,
    CAMERA2_POINT_CLOUD,
    ARCORE_RAW_DEPTH,
    ARCORE_FULL_DEPTH,
    STEREO_CALIBRATED,
    STEREO_APPROXIMATE,
    FOCUS_CALIBRATED,
    FOCUS_APPROXIMATE,
    MANUAL,
}

enum class DistanceQuality {
    HIGH,
    MEDIUM,
    LOW,
}

data class DistanceEstimate(
    val meters: Double,
    val lowerMeters: Double?,
    val upperMeters: Double?,
    val confidence: Double,
    val quality: DistanceQuality,
    val source: DistanceSource,
    val timestampNs: Long,
    val cameraIdentity: String,
    val target: NormalizedPoint,
    val sampleCount: Int,
    val isFresh: Boolean,
    val diagnosticReason: String?,
)
```

每个 Provider 还应提供：

- 静态能力：支持、不支持及原因。
- 运行状态：未初始化、等待数据、可用、暂时失败、冷却。
- 源特定质量数据和误差估计。
- 是否产生真正的新观测，而非历史结果重投影。
- 绑定的相机路线、物理镜头、裁切、目标和 Session generation。

## 7. Camera2 对焦距离 Provider

### 7.1 强制启用条件

只有同时满足以下条件，才能启用 Camera2 自动对焦估距：

```kotlin
minimumFocusDistance > 0f &&
focusDistanceCalibration in setOf(
    CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED,
    CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE,
) &&
availableCaptureResultKeys.contains(CaptureResult.LENS_FOCUS_DISTANCE)
```

必须拒绝：

- `UNCALIBRATED`
- 校准等级为 `null`
- `LENS_INFO_MINIMUM_FOCUS_DISTANCE == 0` 的固定焦距镜头
- CaptureResult 不返回 `LENS_FOCUS_DISTANCE`
- 当前活动物理镜头身份无法确定
- AF 未成功或镜头仍在移动
- `LENS_FOCUS_DISTANCE == 0`，即无穷远
- 非有限值、负值或明显越界值

Android 只保证 `APPROXIMATE` 和 `CALIBRATED` 的相关焦距元数据使用屈光度；`UNCALIBRATED` 的数值不对应物理单位，校准字段也可能为空。参见 [CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#LENS_INFO_FOCUS_DISTANCE_CALIBRATION)。

### 7.2 采集流程

1. 将 AF 区域设置到当前测距目标；设备不支持 AF 区域时使用胶片框中心。
2. 发送一次 `CONTROL_AF_TRIGGER_START`，后续请求恢复 `IDLE`。
3. 只接受以下 AF 状态：
   - `CONTROL_AF_STATE_FOCUSED_LOCKED`
   - `CONTROL_AF_STATE_PASSIVE_FOCUSED`
4. 如果 `LENS_STATE` 存在，要求其为 `LENS_STATE_STATIONARY`。
5. 连续收集 5～10 个合格 CaptureResult，设置约 0.8～1.2 秒的有界超时。
6. 在屈光度空间计算中位数和 MAD。
7. 通过稳定性门槛后再转换为米：

```text
distanceMeters = 1 / medianDiopters
```

禁止先把每帧换算为米再取平均。远距离下，小屈光度误差会被平方放大：

```text
σdistance ≈ σdiopter / diopter²
```

因此即使是 `CALIBRATED`，接近 10 米时也不能默认标记为高精度。

### 7.3 质量等级

- `CALIBRATED`：中等来源先验，可通过实测升级为高质量。
- `APPROXIMATE`：低来源先验，默认不能标记为高质量。
- `LENS_FOCUS_RANGE` 是景深范围，不是统计误差区间，只能用于合理性检查。
- 距离超过设备实测可靠范围时，显示低质量或“>10 m”，不要输出虚假的小数精度。

## 8. Camera2 DEPTH16 Provider

### 8.1 能力判定

启用条件：

- `REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT`
- `StreamConfigurationMap` 包含 `ImageFormat.DEPTH16`
- 深度 Session 实际配置成功
- 能取得有效距离和置信度

当前 [`CameraCatalog`](../app/src/main/java/com/lightmeter/rawmeter/CameraCatalog.kt) 会过滤纯深度摄像头。深度能力发现应放入独立 `DistanceCapabilityCatalog`，不能让深度或 NIR 摄像头重新出现在普通可见光镜头列表中。

### 8.2 数据解码

Camera2 `DEPTH16`：

- 低 13 位是毫米距离。
- 高 3 位是置信度编码。
- 最大可表示距离为 8191 毫米，不能完整覆盖 10 米。

置信度编码不是简单线性整数：

```text
编码 0 → 100%
编码 1 → 0%
编码 2 → 1/7
...
编码 7 → 6/7
```

参见 [Android ImageFormat.DEPTH16](https://developer.android.com/reference/android/graphics/ImageFormat#DEPTH16)。

### 8.3 Session 策略

读取 `CameraCharacteristics.DEPTH_DEPTH_IS_EXCLUSIVE`：

- `false`：可以尝试让深度与颜色输出出现在同一 CaptureRequest。
- `true`：必须交错发送颜色请求和深度请求。
- 字段缺失或 Session 组合失败：进入独立的 `DEPTH_ONLY` 临时 Session。

深度输出通常帧率较低并带有 stall。应根据 `getOutputMinFrameDuration()` 和 `getOutputStallDuration()` 控制深度请求频率，不能把 DEPTH16 无条件放入每帧重复请求。参见 [Camera2 DEPTH_OUTPUT 能力说明](https://developer.android.com/reference/android/hardware/camera2/CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT)。

### 8.4 目标提取

- 不读取单像素，使用目标周围的小型圆形或椭圆形 ROI。
- 排除零距离和低置信度像素。
- 在逆深度空间进行聚类，分离前景和背景。
- 选择最接近目标中心、空间连续且支持度足够的深度簇。
- 使用空间和置信度加权中位数。
- 通过 MAD、有效像素比例和簇内跨度估算误差。
- 多帧只融合不同时间戳的新数据。
- 超过 8.191 米时必须降级到 ARCore、双目、AF 或手动距离。

如果支持 `DEPTH_POINT_CLOUD`，可以投影稀疏三维点到可见光目标附近作为补充，但不能与同源 DEPTH16 重复计权。

## 9. ARCore Depth Provider

### 9.1 配置

应用应保持 AR Optional：

- 不使用 `android.hardware.camera.ar` 限制应用安装范围。
- Manifest 使用 `com.google.ar.core=optional`。
- 只在用户进入需要高精度自动测距的功能时检查 ARCore 支持与安装状态。
- 不支持 ARCore、缺少 Google Play Services for AR 或用户拒绝安装时，正常降级。

参见 [ARCore Optional 配置](https://developers.google.com/ar/develop/java/enable-arcore)。

推荐使用 `Config.DepthMode.AUTOMATIC`：

- 主数据读取 `Frame.acquireRawDepthImage16Bits()`。
- 同时读取 `Frame.acquireRawDepthConfidenceImage()`。
- Raw Depth 在目标区域没有可靠数据时，才使用 `acquireDepthImage16Bits()` 的 Full Depth 填洞。

### 9.2 Raw Depth 处理

ARCore Raw Depth：

- 深度和置信度图分离。
- 置信度为 0～255。
- 官方建议过滤低于约 128 的像素，可将其作为初始阈值，最终以真机数据调优。
- 原始深度图稀疏，目标位置不一定有有效值。
- 新 16 位 API 以毫米表示深度，官方给出的较优工作区间约为 0.5～15 米，但误差会随距离近似二次增加。

参见 [ARCore Raw Depth](https://developers.google.com/ar/develop/java/depth/raw-depth) 和 [`Frame.acquireRawDepthImage16Bits()`](https://developers.google.com/ar/reference/java/com/google/ar/core/Frame#acquireRawDepthImage16Bits())。

Raw Depth 通常约每秒产生十次新深度观测。中间帧可能只是历史深度根据当前相机 Pose 进行的三维重投影，因此必须比较 `Image.timestamp`，只把新时间戳加入时间融合。

建议采集过程：

1. 最多等待约 1.5～2 秒，或取得 3～5 个新深度时间戳。
2. 跟踪状态必须为 `TRACKING`。
3. 引导用户缓慢横向移动手机，提高运动深度质量。
4. Raw Depth 高置信度像素作为主结果。
5. Raw Depth 无覆盖时，Full Depth 只能作为低一级的补洞结果。
6. Full Depth 在物体边缘处需要更强的腐蚀、离群点和深度跳变过滤。

### 9.3 距离语义

ARCore 深度值是目标点到相机主轴方向的 Z 投影，不是相机到目标的射线长度。中央小区域差异很小；非中央测距点需要结合相机内参将 Z 值转换成三维点，再计算射线距离。参见 [ARCore 深度值说明](https://developers.google.com/ar/develop/java/depth/developer-guide#understand-depth-values)。

### 9.4 与现有 Camera2 的集成

第一版推荐使用短时独占 ARCore 测距 Session：

1. 保存当前 Camera2 路线、物理镜头和工作流状态。
2. 使用现有 close barrier 有序关闭 Camera2 Session。
3. 启动普通 ARCore Session。
4. 取得足够的有效深度样本并固化结果。
5. 关闭 ARCore Session。
6. 按原 Camera2 路线恢复预览和测光工作流。

不建议第一版使用 `SharedCamera`。官方明确说明 SharedCamera 模式下，即使设备拥有硬件深度传感器，ARCore 也不会使用该传感器；同时 SharedCamera 会增加现有 RAW、YUV 和 Preview Session 的组合风险。参见 [ARCore SharedCamera](https://developers.google.com/ar/reference/java/com/google/ar/core/SharedCamera)。

## 10. 多摄立体测距 Provider

### 10.1 启用硬门槛

只有全部满足以下条件才能启用：

1. 逻辑相机公开至少两个可同时输出的物理摄像头。
2. 两个摄像头方向一致且具有足够的共同视野。
3. `LENS_POSE_REFERENCE` 相同且不为 `UNDEFINED`。
4. 两边都提供有限、有效的：
   - `LENS_POSE_TRANSLATION`
   - `LENS_POSE_ROTATION`
   - `LENS_INTRINSIC_CALIBRATION`
   - `LENS_DISTORTION`
5. 两台相机的 `LENS_POSE_TRANSLATION` 差值能得到非零且足够大的 baseline。
6. 存在共同可用的 YUV 输出尺寸。
7. 双物理输出 Session 实际配置成功。
8. 可以确定每帧对应的物理摄像头身份和裁切。
9. 预测视差和理论深度误差能满足当前距离要求。

任意必需元数据缺失、非法或参考系不一致时，不允许猜测或启用双目测距。

`LENS_POSE_TRANSLATION` 的单位是米，两台相机平移向量之差可以得到实际基线。Camera2 也规定了使用内参、旋转、平移和畸变进行跨相机坐标变换的顺序。参见 [CameraCharacteristics.LENS_POSE_TRANSLATION](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#LENS_POSE_TRANSLATION)。

### 10.2 同步等级

- `LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE_CALIBRATED`：允许正式启用。
- `APPROXIMATE`：只允许作为实验性、低先验质量来源，要求设备和目标基本静止。
- 字段缺失：不启用。

即使两个物理输出携带相同时间戳，`APPROXIMATE` 仍可能存在实际曝光起始时间偏移。参见 [Camera2 多摄同步等级](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE)。

### 10.3 裁切和焦距

双目三角测量禁止使用 35mm 等效焦距。应使用：

- `LENS_INTRINSIC_CALIBRATION` 中以像素为单位的 `fx`、`fy`、`cx`、`cy` 和 skew。
- `SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE`。
- `LENS_DISTORTION`。
- 每路物理流实际返回的 `SCALER_CROP_REGION`。
- `CONTROL_ZOOM_RATIO`。
- 输出宽高比导致的额外中心裁切。
- API 35 及以上可用时的 `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_SENSOR_CROP_REGION`。

Camera2 明确说明，输出流可能在 CropRegion 基础上继续按输出宽高比裁切并缩放。参见 [CaptureRequest.SCALER_CROP_REGION](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#SCALER_CROP_REGION)。

为了降低首版复杂度，双目测距 Session 建议：

- 固定请求两个明确的物理摄像头。
- `CONTROL_ZOOM_RATIO = 1`。
- 关闭视频防抖。
- 条件允许时关闭 OIS。
- 使用实际 CaptureResult 中的 CropRegion。
- 应用现有软件显示缩放时，只改变目标坐标映射，不改变相机内参本身。

### 10.4 算法建议

1. 同步取得两路 YUV 灰度图。
2. 根据 K、D、R、T 进行去畸变和双目校正。
3. 使用 Census 或梯度图减小不同传感器曝光、色彩和 ISP 差异。
4. 使用 OpenCV StereoSGBM 计算亚像素视差。
5. 执行左右一致性、唯一性、重投影误差和 speckle 过滤。
6. 在测距目标 ROI 内进行逆深度聚类。
7. 使用主要连续簇的中位深度作为本帧结果。
8. 根据 baseline、有效像素焦距和视差误差估算理论误差：

```text
Z ≈ fx × baseline / disparity

σZ ≈ Z² × σdisparity / (fx × baseline)
```

9. 当距离过远、baseline 太短、共同视野不足或预测误差过高时自动拒绝。

## 11. 统一 ROI 深度估计

所有空间深度来源使用相同的目标区域处理流程：

```text
预览/胶片框目标
  → 映射到来源坐标
  → 目标中心小型 ROI
  → 无效值和低置信度过滤
  → 逆深度聚类
  → 选择靠近目标中心的连续深度簇
  → 空间加权中位数
  → MAD、覆盖率和误差估计
  → 时间融合
```

不得：

- 只读取中心单像素。
- 对 ROI 所有深度直接普通平均。
- 无条件选择 ROI 内最近物体。
- 在前景/背景边缘处输出未经聚类的结果。

建议质量权重：

```text
weight =
    sourcePrior
    × pixelConfidence
    × spatialWeight
    × freshness
    × trackingQuality
    × calibrationQuality
    × rangeQuality
```

目标默认采用胶片框中心。后续可以复用 Zone 或点测光坐标，但必须在界面上明确显示当前测距目标。

## 12. 多来源融合

### 12.1 融合域

建议在逆深度域处理：

```text
rho = 1 / distance
```

逆深度更接近双目视差和屈光度的线性表达，也更容易处理随距离快速扩大的误差。

### 12.2 融合过程

1. 上下文门控：相机、物理镜头、目标、裁切、Session generation 必须一致。
2. 来源内部先完成像素和时间鲁棒估计。
3. 去除过期、越界或来源状态失效的结果。
4. 对不同来源执行置信区间或归一化残差一致性检查。
5. 一致时，使用带 Huber 限幅的置信度加权融合。
6. 明显冲突时不平均，选择质量最高的来源并报告冲突。
7. 高质量空间深度存在时，AF 距离只用于异常检查，不应明显拉动最终结果。

### 12.3 混合采集边界

- Camera2 DEPTH16 与 Camera2 AF 在同 Session 可用时允许联合判断。
- 双目测距与两路镜头 AF 可以互相验证。
- ARCore Raw 与 Full Depth 不作为两个独立来源。
- DEPTH16 与同源 Point Cloud 不重复计权。
- 跨 Session 数据只有在目标持续跟踪、相机 Pose 可恢复且数据仍新鲜时才允许融合。
- 无法证明跨 Session 目标一致时，使用质量最高的新结果，不做融合。
- 活动物理镜头变化时，立即清空所有镜头绑定的结果。

## 13. 自动降级策略

概念上的降级链为：

```text
当前 Camera2 Session 可用的高质量 Depth
    ↓ 不可用、超范围或低质量
ARCore Raw Depth
    ↓ 目标区域没有足够 Raw 数据
ARCore Full Depth
    ↓ 不支持或失败
严格校准的多摄立体测距
    ↓ 不支持或质量不足
Camera2 CALIBRATED 对焦距离
    ↓
实验性多摄 APPROXIMATE
    ↓
Camera2 APPROXIMATE 对焦距离
    ↓
手动距离
```

该链表示来源先验，不是绝对覆盖实时质量评分。高稳定性的 CALIBRATED AF 可以优于边缘插值严重的 Full Depth；高重投影误差的双目结果必须被更低层但稳定的结果替代。

建议运行策略：

1. 优先尝试不切换当前 Session 的 Provider，以降低首次结果延迟。
2. 当前来源达到 `HIGH` 或 `MEDIUM` 的产品门槛后立即发布。
3. 用户进入高精度测距或当前结果质量不足时，再升级到短时 ARCore/Depth/双目 Session。
4. 高级 Provider 超时后自动恢复原 Session 并降级。
5. 连续失败的 Provider 进入短暂冷却，避免反复重建相机。
6. 可参考现有 Camera Combination 缓存，以制造商、型号、OS、相机路线和应用版本缓存运行验证结果。

## 14. UI 和交互

自动距离不应只显示一个数值。建议显示：

```text
2.34 m · AR Raw · 高
3.1 m · Camera AF · 近似
2.4–2.8 m · 低置信度
```

状态提示建议包括：

- “请将中心对准目标”
- “请缓慢横向移动手机”
- “正在等待深度数据”
- “目标区域深度不足”
- “此镜头不支持对焦估距”
- “自动测距不可用，请手动设置”
- “距离超过当前来源的可靠范围”
- “不同测距来源结果不一致”
- “测距结果已过期”

用于闪光计算时，应采用最近一次已经锁定的有效距离，避免实时抖动使曝光参数持续跳动。

建议状态模型：

- 进入 Auto 后显示实时预览值。
- 达到稳定门槛后发布 `lockedEstimate`。
- 新结果只有超过迟滞阈值并再次稳定后才替换锁定值。
- 超过 TTL、目标明显移动或物理镜头切换后，锁定结果失效。
- 失效后停止自动闪光补偿，不能静默沿用旧距离。

## 15. 建议代码落点

建议新增：

- `DistanceModels.kt`
- `DistanceCapabilityCatalog.kt`
- `DistanceProvider.kt`
- `DistanceCoordinator.kt`
- `Camera2FocusDistanceProvider.kt`
- `Camera2DepthProvider.kt`
- `ArCoreDepthProvider.kt`
- `StereoDistanceProvider.kt`
- `DepthPatchEstimator.kt`
- `StereoGeometry.kt`
- `DistanceFusionEngine.kt`
- `DistanceSessionPolicy.kt`

现有代码调整方向：

- `CameraController` 只转发 CaptureResult、相机身份和 Session 生命周期事件，不再负责业务层距离换算。
- `CameraUiInfo.focusDistanceMeters` 逐步退化为底层诊断字段，业务改读 `DistanceEstimate`。
- [`FlashExposureMath`](../app/src/main/java/com/lightmeter/rawmeter/FlashExposureModels.kt) 接收解析后的有效距离，不直接了解 autofocus。
- 保留 `FlashConfiguration.distanceMeters == null` 表示 Auto，以兼容已有 SharedPreferences。
- ARCore、DEPTH16 和双目 Session 继续使用现有 generation/revision 与 close barrier 防止迟到回调。
- 高级测距失败不能破坏现有 Preview、RAW、YUV 测光工作流。

## 16. 实施阶段

### 阶段一：统一模型和 AF 快速修正

- 建立 `DistanceEstimate`、Provider 和 Coordinator 接口。
- 修正校准等级为 `null` 时误判可用的问题。
- 加入 AF state、lens state、多帧屈光度滤波、TTL 和镜头身份。
- 闪光计算改为读取统一距离状态。

### 阶段二：Camera2 原生深度

- 建立独立深度能力目录。
- 实现 DEPTH16 解码、置信度和 ROI 估计。
- 增加深度 Session profile、独占/交错策略和实际组合探测。
- 可选实现 Point Cloud 投影验证。

### 阶段三：ARCore Optional

- 增加 AR Optional 配置和运行时能力检查。
- 实现短时独占 ARCore Session。
- 实现 Raw Depth、Confidence、Full Depth 补洞和新时间戳检测。
- 完成 Camera2 状态保存与恢复。

### 阶段四：融合和调优

- 实现统一 ROI、逆深度滤波和误差估计。
- 实现来源一致性门控和 Huber 融合。
- 建立设备级诊断和失败冷却缓存。

### 阶段五：实验性双目

- 实现严格能力判定和有效内参变换。
- 实现双路 YUV、校正、StereoSGBM 和理论误差门控。
- 先放入实验开关，不默认启用。
- 真机覆盖足够且误差达到门槛后，再决定是否进入默认 Auto 策略。

## 17. 测试计划

### 17.1 JVM 单元测试

新增或扩展：

- `CameraFocusDistancePolicyTest`
  - `null` 校准等级拒绝
  - UNCALIBRATED 拒绝
  - 固定焦距拒绝
  - APPROXIMATE/CALIBRATED 接受
- `Depth16DecoderTest`
  - 13 位距离解码
  - 特殊置信度编码
  - 无符号值、stride 和边界
- `DepthPatchEstimatorTest`
  - 前景/背景边缘
  - 无效像素
  - 稀疏覆盖
  - 逆深度聚类和 MAD
- `DistanceFusionEngineTest`
  - 来源一致
  - 来源冲突
  - 相关来源不重复计权
  - 高质量来源压制低质量来源
- `DistanceFreshnessPolicyTest`
  - 时间戳去重
  - TTL
  - Session generation 和镜头切换失效
- `StereoEligibilityPolicyTest`
  - 内参、外参、参考系或同步字段缺失时拒绝
  - baseline 太短时拒绝
- `StereoGeometryTest`
  - CropRegion、宽高比裁切和输出缩放后的内参
  - 已知相机模型的三角化结果

### 17.2 真机测试矩阵

距离点：

- 0.5 m
- 1 m
- 2 m
- 3 m
- 5 m
- 7 m
- 10 m

场景：

- 高纹理目标
- 白墙和低纹理目标
- 玻璃、反光和黑色物体
- 室内、室外、低照度和逆光
- 静态目标和移动目标
- 手持、三脚架和轻微横向运动

设备能力：

- ARCore 有/无硬件深度传感器
- Camera2 Depth 独占/非独占
- 主摄、超广角和长焦
- 多摄同步 CALIBRATED/APPROXIMATE
- Camera2 AF CALIBRATED/APPROXIMATE/UNCALIBRATED/null
- 固定焦距摄像头

## 18. 验收指标

建议初始产品门槛：

- “高质量”：
  - 0.5～5 米误差不超过 `max(5 cm, 5%)`
  - 5～10 米误差不超过 10%
- 未达到高质量门槛的来源仍可回退使用，但必须标记为中或低质量。
- APPROXIMATE AF 默认永远不标记为高质量，除非以后具有设备级实测校准。
- 每个来源统计：
  - 有效结果覆盖率
  - P50/P90 绝对误差
  - P50/P90 相对误差
  - 首次结果延迟
  - 超时率
  - Session 恢复成功率
  - 结果过期和冲突率
- 低置信度结果只显示一位小数或范围，不显示厘米级伪精度。

这些门槛需要通过真机数据验证。ARCore 和 Camera2 规范只描述数据语义与推荐工作范围，并不保证所有设备达到上述产品精度。

## 19. 诊断与可观测性

建议诊断页展示：

- 所有 Provider 的支持状态和拒绝原因。
- 当前逻辑/物理相机 ID。
- Focus calibration 等级。
- Depth capability、输出尺寸、stall 和 exclusive 状态。
- ARCore 支持、安装和 DepthMode 状态。
- 多摄 baseline、sync type、共同输出尺寸和理论最大可靠距离。
- 当前来源、距离、置信度、误差范围、样本数和年龄。
- 最近一次降级或冲突原因。

日志不得保存相机图像或深度图，只记录数值摘要、能力和状态迁移。

## 20. 不建议纳入的方案

- **已知物体大小测距**：需要输入，与本项目交互目标不符。
- **普通单目 ML 深度**：缺少绝对尺度，只适合辅助分割前景、背景或物体边缘。
- **对焦扫描/散焦测距**：慢、强依赖镜头模型，而且会干扰预览和正式测光。
- **ARCore PointCloud 作为主来源**：官方将其定位为可视化和调试用途，行为可能随 SDK 变化。
- **缺少公开外参时猜测双摄间距**：禁止。
- **仅凭等效焦距计算双目深度**：无法正确处理裁切、畸变和输出缩放。
- **按机型硬编码相机间距**：除非未来建立可版本化、可验证并能按摄像头硬件版本区分的校准数据库。

## 21. 实施结论

可采用“自动降级 + 有条件混合采集”的总体方案，但混合不等于所有来源同时运行或直接求平均。

推荐最终形态是：

1. Camera2 AF 作为低成本、广覆盖的持续回退。
2. Camera2 DEPTH16 作为少数设备上的直接深度来源，但注意 8.191 米表示上限。
3. ARCore Raw Depth 作为较广覆盖的高质量空间深度来源，Full Depth 只负责补洞。
4. 只有公开完整内外参、实际裁切和同步信息的设备才启用双目测距。
5. 使用统一的目标、时间戳、置信度、误差模型和逆深度融合。
6. 无法证明结果可靠时自动降级或要求手动输入，不能输出伪精度。

