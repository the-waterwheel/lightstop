# 跨设备相机调用、测光与校准兼容性审查及实施规格

> 审查日期：2026-08-29  
> 当前项目版本：0.2.1  
> 文档性质：滚动审查与改造规格；“已落地”小节代表当前代码状态，其余条目仍需逐项验收  
> 适用项目：光档（RawLightMeter）Android Camera2 实现

## 1. 本轮结论

项目已经具备比普通 Camera2 示例更完整的基础：能够识别逻辑多摄、尝试把输出路由到物理摄像头、在会话失败时逐级降低流配置、区分 RAW/YUV/ISP 校准来源，并对 MONO/NIR、全绿、条纹和冻结画面做了初步防护。

但当前实现还不能保证“把设备上所有后置镜头都提供给用户”，也不能在能力不足的旧设备上可靠地区分“可预览”和“可计算绝对 EV”。最需要优先修复的是下面四项：

1. 旧实现只有在一个逻辑相机下至少找到两个 `usablePhysical` 时才加入物理镜头；当前已改为找到一个即可加入。摄像头调用问题继续在管理与选择目录、物理输出路由和逐项会话验证中解决，与电子变焦无关。
2. 目录判断“可兼容测光”时主要依据是否有 YUV 输出，没有把 `READ_SENSOR_SETTINGS`/`MANUAL_SENSOR` 和实际 CaptureResult 元数据作为硬条件。结果是某些 LEGACY/LIMITED/外接摄像头能够显示画面，但测光和校准必然超时或无结果。
3. 固定物理镜头失败后仍会保留用户选择意图，但当前已增加实际运行 identity，逻辑回退后的测光与校准不再写入原副摄 key；诊断 UI 仍可继续增强。
4. 校准 key 尚不足以跨 OTA、相机 HAL 更新、算法版本、流尺寸和物理 route 回退安全复用。管理页中的逻辑自动相机、公开直接摄像头和逻辑物理输出必须分别保存，不能因为属于同一逻辑分组就共享校准。

因此本轮目标不能表述为“强制调用系统相机里显示的每一颗摄像头”。普通第三方应用只能使用 Camera2 公共 API 暴露的摄像头和路由；系统摄像头可能使用私有 API、系统权限或厂商白名单。可验收的目标应是：

- 枚举所有向普通第三方应用公开的直接摄像头；
- 正确使用逻辑多摄的物理输出路由；
- 将逻辑相机允许第三方绑定输出的物理摄像头作为独立选择项，并逐项验证；
- 对无法直接访问或无法确认活动镜头的情况如实显示，不伪装成固定物理镜头；
- 在每台设备上根据真实能力决定预览、YUV 测光、ISP 测光、RAW 测光和各来源校准是否可用；
- 会话失败时安全降级，并给出准确、可诊断的原因。

### 1.1 当前已落地的兼容层

- 可用物理镜头数量由“至少两个”放宽为“至少一个”，但 MONO、NIR、仅深度输出仍被过滤；
- 同一镜头若同时存在公开 ID 与逻辑物理 ID，界面只保留一个条目，内部依次尝试公开直连、固定物理输出和逻辑回退；
- 每条路线继续使用 `FULL -> RAW_ONLY -> COMPATIBLE -> PREVIEW_ONLY` 多流降级链，并以实际会话创建和预览健康结果为准；
- RAW 静态硬件支持与当前会话 RAW 可用性已分开，前台生命周期、重选镜头和模式切换会重新探测，稳定会话内不自动升级；
- 绿屏、黑屏、冻结和条纹检测会先切换 preview-only，确认仍异常后再推进连接路线；
- RAW/YUV/ISP 校准按独立最小会话顺序执行，逻辑回退采用实际运行 identity 保存校准。
- YUV/ISP 已增加曝光元数据“静态提示 + 动态确认”；静态声明缺失不关闭来源，单帧缺失不撤销能力，测量会自动检查连续帧后才判当前阶段失败；
- RAW 的 CFA、黑电平、白电平缺失时拒绝该帧并进入既有失败/降级流程，不再猜测 RGGB、零黑位或 16 位满量程。

## 2. Android API 版本策略

### 2.1 三个 SDK 版本不能混为一谈

当前 `app/build.gradle.kts` 配置为：

```kotlin
compileSdk = 36.1
minSdk = 28
targetSdk = 36
```

建议改造后的最终配置为：

```kotlin
compileSdk = 36.1
minSdk = 26
targetSdk = 36
```

含义如下：

- `minSdk` 决定最低安装系统。把它从 28 降到 26，才是扩大 Android 8/8.1 老设备覆盖面的修改。
- `targetSdk` 决定应用在新系统上采用哪些安全和行为规则。它保持 36 不会阻止 Android 8 安装，反而避免商店发布和新系统兼容问题。
- `compileSdk` 只决定编译时可引用哪些 API。保持 36.1，才能在新设备上通过版本判断使用新能力。

不要为了支持老设备而降低 `targetSdk` 或 `compileSdk`。所有新 API 都必须放在版本隔离类中，并且在运行前检查系统版本和键是否存在。

### 2.2 为什么第一阶段选择 API 26，而不是直接 API 21

Camera2 从 API 21 已存在，但项目当前还包含以下约束：

- `VignettingCalibrationStore` 使用 `java.util.Base64`，该实现的自然下限是 API 26；
- 当前会话代码直接使用 API 28 的 `OutputConfiguration` 和 `SessionConfiguration`；
- Android 5–7 的 Camera2 设备中 LEGACY HAL 比例较高，元数据、会话组合和驱动稳定性显著更差；
- 当前工程还没有 API 21–25 的真机回归矩阵。

因此第一阶段将 API 26 作为最低版本，收益与维护成本更合理。API 21–25 可以作为后续独立工作包，不应与多摄修复一起一次性放开。

### 2.3 按系统版本启用能力

| 系统/API | 核心相机路径 | 多摄与会话能力 | 产品限制 |
|---|---|---|---|
| Android 8/8.1，API 26–27 | Camera2 公共 `cameraIdList`；旧式 `createCaptureSession(List<Surface>, ...)` | 只把公开相机 ID 放入管理页 | 不承诺隐藏副摄；LEGACY 设备可能只能预览，不能可靠绝对测光 |
| Android 9，API 28 | 增加 `SessionConfiguration`、逻辑多摄和物理输出路由 | 可使用公开物理 ID，并在支持时通过逻辑相机路由物理输出 | 会话组合仍需实际创建验证；不要假设每个物理 ID 都能独立打开 |
| Android 10，API 29 | 可查询逻辑相机内隐藏物理摄像头特征；可预检会话组合 | 厂商可以从 `getCameraIdList()` 隐藏物理副摄，只允许逻辑相机路由 | 物理流组合不再普遍保证；必须查询并以创建结果为准 |
| Android 11，API 30 | 增加物理摄像头可用性回调和独立 CameraDevice 并发能力查询 | 用于更新管理页状态和诊断公开相机组合 | 不改变本项目电子变焦定义；隐藏物理 ID 仍不能直接 `openCamera()` |
| API 31 及以上 | 可按需使用推荐流配置、扩展和更新的会话能力 | 仅作为增量增强 | Camera Extensions 不是通用测光或“解锁全部镜头”的替代方案 |

### 2.4 API 26 会话兼容层

不得在现有 `CameraSessionCoordinator` 中堆叠大量 `if (SDK_INT)`。新增小型接口：

```kotlin
interface CameraSessionFactory {
    fun create(request: SessionCreateRequest, callback: SessionCreateCallback)
}
```

实现拆分为：

- `Api26CameraSessionFactory`：API 26–27，使用 surface 列表创建普通会话，不接收物理 ID；
- `Api28CameraSessionFactory`：API 28+，使用 `OutputConfiguration`/`SessionConfiguration`，按需设置物理 ID；
- `Api29SessionSupportProbe`：API 29+ 封装 `isSessionConfigurationSupported`，低版本返回 `UNKNOWN` 而不是错误的 `false`。

每个实现建议控制在 200 行以内。由一个不超过 80 行的工厂按 SDK 版本选择实现。

## 3. Android 官方能力边界

### 3.1 “枚举到物理 ID”不等于“能直接打开”

Android 10 起，厂商可以不把物理副摄放入 `CameraManager.getCameraIdList()`。这些物理摄像头仍可能出现在逻辑相机的 `physicalCameraIds` 中，可读取特征并通过 `OutputConfiguration.setPhysicalCameraId()` 请求输出，但不能把这个物理 ID 直接传给 `openCamera()`。

项目必须明确区分四种路由：

```kotlin
enum class CameraRouteKind {
    PUBLIC_DIRECT,          // cameraIdList 中可直接打开
    LOGICAL_FUSED,          // 逻辑相机自动融合/切镜头
    LOGICAL_PHYSICAL,       // 打开逻辑相机，输出绑定到物理 ID
}
```

不能继续用一个 `cameraId` 同时表达“用户选择项”“实际打开的 CameraDevice”和“实际出图的物理摄像头”。

### 3.2 厂商不保证任意物理流组合

即使物理 ID 和尺寸存在，`Preview + RAW + YUV` 全部绑定到同一物理副摄也可能被 HAL 拒绝。Android 10 以后并不要求所有包含物理流的组合都可用。

能力判断顺序必须是：

1. 静态元数据宣称支持；
2. 流尺寸存在且属于安全交集；
3. API 29+ 调用会话支持查询；
4. 实际创建会话成功；
5. 收到连续有效帧和必要 CaptureResult；
6. 才把该能力标记为本次会话可用。

任何前置检查都不能代替第 4、5 步。

### 3.3 系统相机能用不代表第三方应用能用

厂商系统相机可能具有：

- `SYSTEM_CAMERA` 或其他系统权限；
- 不公开的 camera ID；
- 私有 CaptureRequest vendor tag；
- 厂商签名、白名单或专用 SDK；
- HAL 内部的 SAT/融合切镜头策略。

因此应用可以做到“完整利用普通第三方 Camera2 能力”，但不能承诺和系统相机的镜头列表完全一致。界面应在诊断页明确这一边界。

## 4. 当前代码审查结果

### 4.1 已经正确或基本正确的部分

- `CameraCatalog` 优先保留逻辑相机自动路由，并在 API 29+ 查询逻辑相机内物理特征；
- `CameraCapabilityFilter`/`RawSensorFormatPolicy` 已拒绝 MONO、NIR、深度流和不受支持的 `CFA_RGB`，降低把非可见光传感器当普通摄像头导致绿屏/条纹的概率；
- `CameraSessionCoordinator` 对输出设置物理 ID，并在 API 29+ 尝试会话组合预检；
- 会话计划会从完整流组合降级到 RAW-only、兼容流、preview-only；
- `ActivePhysicalCameraTracker` 会读取 `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID`；
- RAW 读取处理了 Bayer 排列、row/pixel stride、动态/静态黑电平和白电平；
- YUV 读取正确使用第 0 个 Y plane 及其 stride；
- RAW/YUV/ISP 校准已经改成顺序执行，没有为了校准长期并发三路流；
- 校准发生部分失败时能够保留成功来源并显示失败来源；
- 预览健康检测已经覆盖绿色偏置、过黑、冻结和条纹的初步识别。

这些基础应保留，不建议改回 Camera1，也不建议把 CameraX 作为解决副摄访问的替代方案。CameraX 最终仍受 Camera2/HAL 暴露范围约束。

### 4.2 已确认产品定义：摄像头选择与电子变焦相互独立

代码已经明确实现了两条不同路径：

- `CameraManagementView` 为每个 `CameraDescriptor.cameraId` 绘制独立条目，并提供选择、备注和隐藏操作；
- `MeterState.selectCamera()` 按 camera ID 保存独立电子变焦值和按角度测光设置；
- `CameraController.selectCamera()` 重新打开选中的直接或逻辑物理 route；
- `CameraController.updatePreviewTransform()` 与 `CameraPreviewTransform` 只改变 `TextureView` 显示矩阵；
- `displayZoom` 同时进入角度测光和坐标映射，用于让 ROI 对应电子裁切后的可见区域。

因此项目没有设置 `CONTROL_ZOOM_RATIO`/`SCALER_CROP_REGION` 是当前产品定义，而不是漏实现。后续不得为了访问副摄而把电子变焦改成 HAL 光学变焦，也不得让拖动电子变焦拨杆自动切换 `CameraDescriptor`。

真正需要修复的是：

- 管理页是否列出所有普通第三方应用能够直接打开或通过逻辑物理输出 route 使用的摄像头；
- 每个条目能否独立创建 preview/RAW/YUV 会话；
- 条目失败时是否准确显示不可用，而不是悄悄切换成另一个摄像头；
- 每个条目是否有独立 RAW/YUV/ISP 校准和电子变焦偏好。

电子变焦保持以下规则：

- 只做显示裁切，不改变 CameraDevice、物理 route、曝光请求或校准身份；
- 每个 camera ID 单独保存倍率；
- 倍率最小值保持 1x；
- 角度测光、点测光、ZONE 坐标、取景框和暗角校准采样必须使用同一 `displayZoom` 几何；
- 切换摄像头时读取该摄像头自己的电子变焦值；
- 曝光校准 correction 与电子变焦倍率无关，不为 1x/2x/3x 重复保存。

### 4.3 已修复高严重度：预览能力与绝对测光能力的动态区分

`MeteringAnalysis` 的 ISP/YUV/区域分析都要求 CaptureResult 中存在：

- `SENSOR_EXPOSURE_TIME`；
- `SENSOR_SENSITIVITY`；
- 动态或静态光圈。

任一缺失就返回空结果。但 `CameraDescriptor` 当前只记录 `manualSensorAvailable`，目录并未把 `READ_SENSOR_SETTINGS` 作为兼容测光能力，也没有区分“可以手动控制”和“AE 下能读取实际传感器参数”。

当前实现先使用静态能力形成“已保证/尚未确认”的初始状态，判据为：

```kotlin
val absoluteExposureMetadataAdvertised =
    capabilities.contains(READ_SENSOR_SETTINGS) ||
    capabilities.contains(MANUAL_SENSOR) ||
    availableResultKeys.contains(SENSOR_EXPOSURE_TIME) &&
        availableResultKeys.contains(SENSOR_SENSITIVITY)
```

同时检查 `LENS_APERTURE` 结果键或 `LENS_INFO_AVAILABLE_APERTURES` 静态值。静态检查失败不等于不可用：YUV/ISP 仍加入校准计划，应用持续观察真实 `CaptureResult`；任何一帧实际给出有效曝光时间、ISO 和动态/静态光圈后，本次路线即单向确认为可用。后来一帧字段缺失只丢弃该帧，不能撤销已经确认的能力。

静态声明仍不能代替实际帧验证。当前分析器在结果缺少曝光时间、ISO 或光圈时只拒绝该帧：YUV 在约 1.5 秒有界窗口内检查最多 30 个配对帧，ISP 在相同量级的窗口内持续取得同步预览帧。只有连续结果始终不完整才使当前校准阶段失败；下一次建立对应校准会话仍会重新探测，不增加界面重试按钮。后续若要在诊断页细分原因，可继续引入：

```kotlin
data class ExposureMetadataReadiness(
    val exposureTimeSeen: Boolean,
    val sensitivitySeen: Boolean,
    val apertureResolved: Boolean,
    val consecutiveValidResults: Int,
)
```

状态必须分为：

- `PREVIEW_ONLY`：只能显示画面；
- `RELATIVE_LUMA_ONLY`：Y 可以反映相对明暗，但不能输出可信绝对 EV100；
- `ABSOLUTE_PROCESSED_METERING`：YUV/ISP + 完整曝光元数据；
- `ABSOLUTE_RAW_METERING`：RAW Bayer + 完整曝光元数据和 RAW 解释元数据；
- `UNKNOWN_UNTIL_STREAMING`：静态宣称存在，等待实际结果确认。

旧设备和 USB 外接摄像头尤其容易处于前两种状态。应用可以继续允许取景，但“测光”和“校准”按钮必须禁用或显示明确原因，不能等待超时后只显示泛化失败。

### 4.4 高严重度：固定镜头失败后的身份误标

当前恢复逻辑会把物理输出置空并切换逻辑路由，但有意保留用户选择的 `cameraId`。这会造成：

- 镜头选择器仍显示“长焦/超广角”；
- 实际画面可能来自逻辑主摄或融合输出；
- 校准和历史记录可能使用错误的镜头身份；
- 用户无法知道回退发生后本次结果是否还能与该镜头的旧校准比较。

需要将以下身份分离：

```kotlin
data class CameraSelectionIdentity(
    val selectionId: String,
    val requestedRoute: CameraRoute,
)

data class CameraRuntimeIdentity(
    val openedLogicalCameraId: String,
    val configuredPhysicalCameraId: String?,
    val confirmedActivePhysicalCameraId: String?,
    val routeKind: CameraRouteKind,
    val fallbackReason: CameraRouteFailure?,
)
```

一旦固定物理路由失败：

- 选择器可保留用户的原始意图，便于重试；
- 当前状态必须显示“已回退到自动相机”；
- 本次测光和校准使用实际 runtime identity；
- 禁止把回退结果写入原固定镜头的校准 key；
- 固定镜头选择项在本次进程中标记“当前配置不可用”，允许用户手动重试。

### 4.5 高严重度：校准适用范围不足

当前按设备和 camera ID 保存分来源修正已经比单一全局修正安全，但仍不足以处理以下变化：

- 系统 OTA 更新相机 HAL/ISP；
- 应用更新测光算法；
- 固定物理选择项失败后回退到逻辑 route；
- 流尺寸、色彩空间、dynamic range 或会话 profile 改变；
- 固定物理路由失败后发生逻辑回退；
- 管理页 selection ID、configured physical ID 和校准存储 ID 不一致。

新的 key 至少包含：

```kotlin
data class CalibrationSignature(
    val schemaVersion: Int,
    val algorithmVersion: Int,
    val source: MeteringSource,
    val manufacturer: String,
    val model: String,
    val buildFingerprintHash: String,
    val cameraInfoVersion: String?,
    val logicalCameraId: String,
    val configuredPhysicalCameraId: String?,
    val confirmedPhysicalCameraId: String?,
    val streamWidth: Int,
    val streamHeight: Int,
    val streamFormat: Int,
)
```

其中：

- 原始 Build fingerprint 只用于本地 hash，不上传；
- `CameraCharacteristics.INFO_VERSION` 从 API 28 起可选，缺失时允许为空；
- OTA 或算法版本变化后把旧值标记为“需重新验证”，不要静默套用；
- 有确定物理 ID 时按物理镜头存；
- 逻辑自动相机与用户可选的固定物理摄像头分别保存，不共享 correction；
- 校准过程中若活动物理 ID 改变，立即中止当前来源，不把跨镜头平均值写入数据库。

当前 Vivo V2405A 的硬编码 baseline 仅对 camera ID `0` 命中，而物理路由身份可能是 `0@2`，存在已知值失效或错误复用风险。实施时二选一：

1. 将已知 baseline 明确迁移为“经验证的 runtime route 别名”；或
2. 删除设备硬编码 baseline，只保留用户校准。

更推荐第 2 种，避免机型内不同固件、区域版本和传感器批次差异。

### 4.6 中严重度：物理镜头目录规则会漏项或错配

`CameraCatalog` 当前只有在某逻辑相机下至少存在两个 `usablePhysical` 时，才生成固定物理镜头项。问题包括：

- 一个物理 ID 可用、另一个读取失败时，唯一可用的物理路由也被隐藏；
- 全局 `seenPhysicalIds` 可能把共享或重复报告的物理 ID 绑定到遍历顺序中的第一个逻辑相机；
- 等效焦距依赖厂商元数据，缺少焦距/传感器尺寸时可能错误命名主摄、超广角、长焦；
- 枚举只发生在查询时，没有监听外接摄像头、折叠状态或资源占用导致的可用性变化。

改法：

- 每个逻辑相机单独建立 route graph，不使用跨逻辑相机的全局去重作为父子归属依据；
- 每个物理路由独立评估，不能要求同组至少两个有效；
- 名称置信度分为 `CONFIRMED_METADATA`、`INFERRED`、`UNKNOWN`；不确定时显示焦距或“摄像头 ID”，不要强行叫长焦；
- 注册 `CameraManager.AvailabilityCallback`；API 30+ 同时处理物理摄像头 available/unavailable 回调；
- 外接摄像头断开时立即停止 request、关闭 Image，并更新目录；
- 目录状态区分 `DISCOVERED`、`PROBE_PENDING`、`AVAILABLE`、`UNSUPPORTED_COMBINATION`、`TEMPORARILY_BUSY`。

### 4.7 中严重度：YUV 的 Y 能反映亮度，但不是跨厂商绝对光度

回答用户此前的问题：YUV 的 Y 分量当然能够反映画面相对亮度，项目也可以用它做测光。但 Android 只保证 `YUV_420_888` 的三平面顺序、位深和 stride 结构，并不保证所有厂商的 Y 都具有同一套光电转换函数、同一有限/全范围、同一 tone map 或 HDR 处理。

当前算法把 `Y / 255` 当作 sRGB 后做固定 EOTF，跨设备可能受到以下因素影响：

- ISP tone curve；
- 局部 HDR/暗部提亮；
- AE、降噪和锐化策略；
- YUV limited/full range 差异；
- 厂商在不同亮度区间使用不同非线性响应；
- 镜头切换时不同传感器/ISP pipeline 的响应差异。

正确定位是：

- Y 是相对亮度信号；
- Y + 实际曝光时间 + 实际 ISO + 光圈，可以估算 EV；
- 每来源校准可以消除主要常量偏移，但单点 offset 不能保证修正整条非线性曲线；
- 校准应至少增加暗、中、亮三点的响应一致性检查；第一阶段仍可保存 offset，但若三点残差过大，应标记该来源“不适合高精度绝对测光”；
- 检测黑位、白位和裁切比例，过曝/欠曝区域不能参与校准平均；
- 不要把 YUV 校准描述成与 RAW 等价。

### 4.8 中严重度：预览健康检测存在误报和漏报

当前条纹连续多次后会判失败，但全绿/过黑更多停留在 suspect。可能出现：

- 百叶窗、显示器扫描纹或规则建筑纹理被误判为坏条纹；
- 真正全绿/全黑的 vendor buffer 长时间只显示可疑，不自动恢复；
- TextureView `getBitmap()` 在 DeX、外接显示、特殊色彩路径上失败；
- 新会话前几帧未初始化，短暂黑帧触发错误状态。

健康判断应组合多项证据：

- Texture 时间戳持续前进；
- CaptureResult 时间戳和曝光参数持续更新；
- YUV 与 Texture 的平均亮度/色偏是否严重分离；
- 图像异常是否跨多个时间点保持；
- 会话切换后是否只发生在某一物理 route/profile；
- 画面是否存在自然运动或曝光变化，避免只凭静态频谱判断条纹。

任何单一静态图像模式都不应永久拉黑摄像头。兼容记录以“设备 + OS build + route + profile + size”保存，并允许 OTA 后重新探测。

## 5. 摄像头调用的目标架构

### 5.1 能力快照

新增不可变数据结构 `CameraCapabilitySnapshot`，只描述事实，不负责 UI 命名或打开会话：

```kotlin
data class CameraCapabilitySnapshot(
    val publicCameraIds: Set<String>,
    val logicalCameraId: String,
    val physicalCameraIds: Set<String>,
    val hardwareLevel: HardwareLevel,
    val outputCapabilities: Set<OutputCapability>,
    val exposureMetadataCapability: ExposureMetadataCapability,
    val physicalRequestKeysAvailable: Boolean,
    val activePhysicalIdExpected: Boolean,
)
```

静态读取异常必须转为 `UNKNOWN` 并记录原因，不能等同于“不支持”。

### 5.2 路由目录

`CameraRouteCatalog` 输出稳定的选择项：

- 公开直接摄像头；
- 逻辑自动相机；
- 可尝试的逻辑物理输出；

建议 UI 顺序：

1. 后置自动相机；
2. 已验证固定物理镜头；
3. 其他公开的后置摄像头；
4. 前置；
5. 外接。

“全部摄像头”只包含第三方应用实际可请求的项。深度、MONO、NIR、系统相机保持过滤，并在诊断页列出过滤原因。

### 5.3 路由探测

新增 `CameraRouteProbe`，采用最小代价逐层验证：

1. 首次只检查静态能力；
2. 用户打开镜头选择器时可异步探测 `PREVIEW_ONLY`；
3. 固定物理路由先用安全预览尺寸，不直接上 RAW+YUV；
4. 首帧和 CaptureResult 成功后标为 available；
5. 用户选择高精度/稳定模式时，再探测该模式需要的额外流；
6. 临时资源错误不写永久不兼容；
7. 明确的 unsupported combination 只缓存到当前 OS build 和应用算法版本。

探测不能同时打开多个 CameraDevice。它应复用正常生命周期状态机，并支持取消，避免用户快速切换时旧探测回调污染新状态。

### 5.4 电子变焦几何保持现有语义

不新增 Camera2 硬件 zoom request。`displayZoom` 继续只属于显示与 ROI 几何层：

- `CameraPreviewTransform` 负责 TextureView 中心裁切；
- `ZoneCoordinateMapper`、角度测光和标点 ROI 使用相同倍率；
- `DeferredZoneMarkerTracker` 在 View 重建后恢复相同电子倍率；
- 每个 camera ID 独立持久化电子倍率；
- 切换摄像头时先打开新 route，再应用该条目的电子倍率；
- 校准时可以临时以 1x 显示，也可以保持用户倍率，但曝光 correction 的存储 key 不包含电子倍率。

需要增加的测试不是 HAL zoom 测试，而是：电子裁切后的可见区域与 RAW/YUV/ISP 取样 ROI 一致；旋转和切换摄像头后倍率与坐标不串到其他条目。

### 5.5 会话计划

每条 route 建立按风险递增的 profile：

```text
P0  logical preview only
P1  requested route preview only
P2  requested route preview + small YUV
P3  requested route preview + minimum RAW
P4  requested route preview + small YUV + minimum RAW
```

不要默认从 P4 开始再一路失败。普通预览先建立低风险 P0/P1；仅在用户模式需要时升到 P2/P3。校准保持 RAW、YUV、ISP 顺序隔离，不要求三路长期并发。

物理路由尺寸优先级：

1. 逻辑与物理配置图的共同尺寸；
2. API 29+ 支持查询通过的尺寸；
3. 4:3 只是次级偏好，不能高于会话可用性；
4. 预览建议上限 1920×1080，但物理副摄可降到 1280×720 或其最接近安全尺寸；
5. YUV 保持小尺寸测光流；
6. RAW 继续优先最小可用 RAW_SENSOR，降低带宽和功耗。

## 6. 测光兼容性改造

### 6.1 分来源能力，不再用单一布尔值

```kotlin
data class MeteringSourceCapability(
    val source: MeteringSource,
    val availability: CapabilityAvailability,
    val reason: MeteringUnavailableReason?,
    val requiredSessionProfile: CameraSessionProfile?,
    val confidence: MeteringConfidence,
)
```

`CapabilityAvailability`：

- `AVAILABLE`；
- `UNAVAILABLE`；
- `PENDING_RUNTIME_METADATA`；
- `TEMPORARILY_UNAVAILABLE`；
- `DEGRADED`。

`MeteringUnavailableReason` 至少包括：

- 无曝光时间元数据；
- 无 ISO 元数据；
- 无可解析光圈；
- 无 YUV 输出；
- 无 RAW 输出；
- RAW 不是支持的 Bayer；
- 会话组合被拒绝；
- 所选固定物理 route 在会话中发生回退或失效；
- 同帧配对失败；
- 相机暂时被占用或断开。

### 6.2 ISP/预览测光

- TextureView 有画面不代表 CaptureResult 元数据齐全；
- 使用 request tag/时间戳等待与本次测光对应的稳定帧；
- 校准必须严格配对，不允许把旧 bitmap 和新 CaptureResult 拼接；
- 普通测光若超时，可以给用户“重试/切换稳定模式”，不要使用上一帧假装成功；
- 对 DeX、分屏、横竖屏和 View 尺寸变化，必须等待新 transform 应用后的首帧再计算 ROI。

### 6.3 YUV 测光

- 保留当前对 rowStride/pixelStride 的处理；
- Image 获取后必须在 `finally` 中关闭；
- 使用 `acquireLatestImage()` 时明确允许丢旧帧，但严格校准仍按 timestamp 配对；
- 检测 Y 平面直方图的黑位、白位和 clipping；
- 将固定 sRGB EOTF 封装为 `YuvTransferAssumption`，不要散落在分析大类；
- 增加三点响应验证；残差超过阈值时降低来源置信度；
- YUV 不可用时不能影响正常 preview-only 会话。

### 6.4 RAW 测光与 CFA

RAW Bayer 不需要为了测光做完整解拜尔。对每个 Bayer 2×2 单元按 CFA 排列取得 R、两个 G、B，再计算亮度统计即可。当前拒绝 `CFA_RGB` 是正确的：它不是四种 Bayer 排列之一，如果按 Bayer 读取会造成通道位置错误、亮度权重错误，严重时出现绿偏或周期条纹。

但即使是合法 Bayer，不同传感器的光谱响应也不同。固定 RGB 权重只能作为 fallback；用户校准能修正常量偏差，不能完全消除随光源色温变化的误差。后续可在有可靠 color transform 时使用传感器到 XYZ 的矩阵，但不要为此引入完整解拜尔大类。

### 6.5 结果状态

每个测光结果必须携带：

- 实际来源 RAW/YUV/ISP；
- route kind；
- 逻辑 ID；
- 已配置物理 ID；
- 已确认活动物理 ID；
- 当前电子变焦倍率（仅用于复现 ROI 几何，不参与校准 key）；
- 曝光时间、ISO、光圈是否来自 CaptureResult 或静态 fallback；
- 使用的校准 signature；
- 是否发生降级；
- 置信度和警告。

历史记录不需要把全部字段都显示在主卡片上，但必须保存足够信息以诊断错误来源。

## 7. 校准兼容性改造

### 7.1 校准前预检

进入校准页时即展示当前相机每个来源的状态：

```text
RAW：可校准 / 不支持 Bayer RAW / 当前会话失败
YUV：可校准 / 缺少曝光元数据 / 流组合不支持
ISP：可校准 / 等待稳定同帧 / Texture 帧不可读取
```

开始按钮只执行可用或需要运行时确认的来源。完全不支持的来源不进入倒计时，不应让用户等待一个注定失败的阶段。

### 7.2 保持顺序会话，不增加三路并发

高精度与稳定模式都不应为了校准同时请求 RAW+YUV+ISP：

1. 固定当前管理页选择项和实际 camera route；
2. 等待会话、曝光元数据和画面稳定；
3. RAW 单独会话并采样；
4. 关闭 RAW ImageReader/会话；
5. YUV 小流单独会话并采样；
6. 关闭 YUV；
7. ISP preview 会话采样；
8. 分来源保存结果；
9. 恢复用户原模式。

这样能覆盖更多带宽有限或物理流组合严格的旧设备和厂商设备。

### 7.3 严格同镜头、严格同帧

- 每个阶段开始时记录 generation、request tag 和所选 route identity；
- 只接受匹配 generation/tag 的 CaptureResult；
- YUV/RAW 用 sensor timestamp 配对；
- ISP 使用 SurfaceTexture timestamp 与 CaptureResult 的显式等待窗口；
- 固定物理选择项的实际 route 发生回退时，阶段失败并提示“所选摄像头路由已改变”；
- 电子变焦只影响 ROI 几何，不参与曝光 correction 的校准 key。

### 7.4 多点校准

第一阶段可以继续以 18% 灰卡单点 offset 为主，但数据结构预留：

```kotlin
data class CalibrationPoint(
    val referenceEv100: Double,
    val measuredEv100: Double,
    val exposureTimeNs: Long,
    val sensitivity: Int,
    val aperture: Double,
    val clippingFraction: Double,
)
```

推荐后续在约 -3 EV、0 EV、+3 EV 三个亮度区间验证。若 offset 差异过大，UI 显示“该来源响应非线性，仅适合参考”，不要用一个平均 offset 掩盖问题。

### 7.5 多摄校准按管理页选择项隔离，与电子变焦无关

管理页中的每个 `CameraDescriptor.cameraId` 都是独立校准对象。示例：

```text
逻辑自动相机 0       -> calibration key: 0
物理超广角 0@2       -> calibration key: 0@2
物理主摄 0@0         -> calibration key: 0@0
物理长焦 0@3         -> calibration key: 0@3
前置摄像头 1         -> calibration key: 1
```

每个选择项分别保存 RAW、YUV、ISP correction 和历史记录。用户切换选择项后，校准页只展示当前摄像头的记录；管理页则在各自条目下展示各自的校准覆盖。

当前代码存在一个需要修正的身份不一致：`CameraManagementView` 使用 `camera.cameraId` 读取条目校准记录，但 `CameraUiInfo.calibrationCameraId` 在逻辑相机报告 active physical ID 后会改成 `logicalId@physicalId`。这样逻辑自动相机条目可能把校准保存到物理 ID 下，管理页随后仍用逻辑 ID 查询，表现为记录没有更新。目标规则是：

- 持久化 correction/history 使用稳定的管理页 selection ID；
- 显式固定物理条目的 selection ID 本身已经是 `logical@physical`；
- configured/active physical ID 只用于确认本次捕获没有换 route，以及写入诊断信息；
- 逻辑自动相机的记录保存在逻辑 selection ID 下，不因一次 CaptureResult 报告物理 ID 而迁移 key；
- `calibrationStorageCameraId` 应从 `CalibrationRouteIdentity.routeId` 固定，而不是从动态 `CameraUiInfo.calibrationCameraId` 推导。

电子变焦仅裁切当前摄像头的显示和 ROI，不改变曝光时间、ISO、光圈或物理 route，因此：

- 1x、2x、3x 不分别建立曝光校准；
- 同一摄像头在不同电子倍率下共用同一分来源 correction；
- 校准可以强制显示 1x 以便对准灰卡，但结束后恢复该摄像头保存的倍率；
- 若保留用户倍率校准，RAW/YUV/ISP 必须使用电子裁切后同一可见 ROI；
- 暗角校准属于空间校准，使用电子变焦时需要正确裁切/映射暗角网格，但不因此产生另一份曝光 correction。

固定物理摄像头会话失败并回退逻辑相机时，当前校准必须中止，不能把逻辑相机结果写入原 `0@physicalId`。逻辑自动相机本身可以有独立校准，但不得与任何固定物理选择项共享。

### 7.6 每颗物理摄像头必须独立判断 RAW 能力

管理页中的不同摄像头可能出现：

```text
超广角：preview + YUV，无 RAW
主摄：preview + YUV + RAW_SENSOR
长焦：preview + YUV，但物理 RAW 会话组合失败
另一个公开 camera ID：preview-only
```

逻辑相机宣称 RAW 不代表所有物理摄像头都支持 RAW。每条物理 route 必须独立检查：

- 该物理 CameraCharacteristics 是否宣称 `RAW`；
- 是否存在 `RAW_SENSOR` 输出尺寸；
- CFA 是否属于支持的 Bayer 类型；
- black level、white level 和必要解释元数据是否存在；
- `preview + RAW`、`RAW-only calibration` 等具体会话组合是否可建立；
- 收到的 RAW buffer 和物理 CaptureResult 是否确实来自当前选择项的 route。

AOSP 规定逻辑相机和物理摄像头的 RAW/物理流能力并不等同。因此目录必须基于每个 `CameraDescriptor` 对应的 stream characteristics，而不是把逻辑相机的 `rawAvailable` 复制给所有物理条目。

因此 RAW 的选择顺序必须是：

```text
1. 当前管理页选择项自身的 RAW route，且会话实测成功
2. 否则该选择项没有 RAW，跳过 RAW 校准和 RAW 测光
```

严禁以下降级：

- 当前选择长焦，却拿主摄 RAW 计算后标为长焦 RAW；
- 超广角无 RAW，套用主摄或逻辑自动相机的 RAW correction；
- 物理 RAW 会话失败后静默改用另一颗传感器。

当当前镜头没有 RAW 时，高精度模式的正确行为是：

- 明确显示“当前镜头不支持 RAW”；
- 使用该镜头自己校准过的 YUV 或 ISP 来源；
- 如果 YUV/ISP 也未校准，则显示降低的置信度或要求用户先校准；
- 不让其他镜头的 RAW 修正值跨镜头兜底。

RAW correction 按 `selectionId + runtime route + RAW stream configuration + algorithmVersion` 保存。电子变焦倍率不进入 key。YUV/ISP correction 同样按管理页摄像头选择项分别保存，不跨 camera ID 复用。

### 7.7 每颗镜头的流组合能力也必须独立

如果用户所说的“线程支持不同”是指 preview、YUV、RAW 等相机流（stream）支持不同，那么判断是正确的：每颗物理摄像头的输出尺寸、最小帧时长、RAW 能力和可建立的 stream combination 都可能不同。

不能先求一个所有镜头共同的最高配置，再要求每颗镜头都运行它。应维护 route-source 矩阵：

```kotlin
data class RouteStreamCapability(
    val routeIdentity: CameraRuntimeIdentity,
    val previewOnly: ProbeResult,
    val previewAndYuv: ProbeResult,
    val rawOnly: ProbeResult,
    val previewAndRaw: ProbeResult,
    val previewYuvRaw: ProbeResult,
    val supportedPreviewSizes: List<Size>,
    val supportedYuvSizes: List<Size>,
    val supportedRawSizes: List<Size>,
)
```

校准计划按当前镜头生成，而不是按模式写死：

```text
镜头 A：RAW-only -> YUV-only -> ISP preview
镜头 B：跳过 RAW -> YUV-only -> ISP preview
镜头 C：跳过 RAW/YUV -> ISP preview
镜头 D：只有 preview，但缺曝光元数据 -> 不允许绝对校准
```

每个阶段使用当前 route 已探测成功的最小安全配置。某镜头 `preview + RAW` 失败，不代表另一镜头也失败；某镜头 RAW-only 成功，也不代表 `preview + RAW + YUV` 成功。

Android 10+ 使用 `isSessionConfigurationSupported()` 预检物理流组合，但实际创建会话和收到有效帧仍是最终依据。Android 9 的最低保证也不能外推到 Android 10+ 或任意额外流组合。

现有 `MeteringCalibrationPlan` 已经正确做到逐来源串行，并根据当前 `rawAvailable`、`yuvAvailable` 跳过部分来源；需要补足的是：

- 当前只有布尔值，没有“不支持、尚未探测、当前 profile 未建立、临时失败”的原因；
- `yuvAvailable` 取决于当前 reader/会话状态，不能完整代表该选择项能否在独立 YUV 校准 profile 中启动；
- `ispPreviewAvailable` 默认恒为 `true`，但某些 route 可能 Texture 读取失败或缺少绝对测光元数据；
- 能力没有在管理页按 camera ID 持久展示；
- 切换摄像头后应清空上一条目的运行时能力，重新加载/探测当前 selection。

因此保留现有 `MeteringCalibrationPlan` 小状态机，在它之前增加按选择项生成的 `MeteringCalibrationCapabilities` 事实来源，而不是重写成一个多摄大类。

### 7.8 如果“线程”确实指执行线程

设备不会宣称“某颗摄像头支持哪个 Java/Kotlin 线程”。Handler/Executor 是应用给 Camera2 回调选择的执行上下文，真正需要解决的是线程安全、回调顺序和图像处理阻塞。

项目约束：

- 所有 CameraDevice、CaptureSession、CaptureRequest 和 route 状态变更串行进入单一 `CameraControlExecutor`；
- 不为每颗物理镜头各建一个可同时修改控制状态的 HandlerThread；
- 每次打开、会话重建和校准阶段使用 generation/token，旧线程回调不得写入新状态；
- Camera2 回调线程只完成轻量匹配和任务投递，不运行完整 RAW/YUV 统计；
- RAW/YUV 分析进入有界 worker，队列满时丢弃旧普通测光帧，但校准帧按显式任务保留；
- Image 所有权明确，任何成功、取消、超时和异常路径都关闭 Image；
- 分析结果返回控制线程后再次验证 generation、route、physical ID 和 request tag；
- 校准仍严格逐来源、逐镜头串行，线程池不能把它变成同时多路请求。

如果未来确实要同时打开两个独立 CameraDevice，必须在 API 30+ 查询 `getConcurrentCameraIds()` 和 mandatory concurrent stream combinations。当前产品只是让用户在管理页选择一个摄像头，不需要并发打开多个 CameraDevice；选择新摄像头时应完整关闭旧摄像头，再打开新 route。

### 7.9 校准覆盖状态必须在 UI 可见

管理页为每个摄像头增加分来源覆盖摘要，例如：

```text
超广角（0@2）
  RAW：不支持
  YUV：已校准
  ISP：已校准

主摄（0@0）
  RAW：已校准
  YUV：已校准
  ISP：已校准

长焦（0@3）
  RAW：会话不支持
  YUV：已校准
  ISP：待校准
```

普通测光时根据当前 runtime identity 和来源选择 correction。找不到完全匹配项时，优先给出未校准结果和警告，不自动借用另一颗镜头的 correction。

## 8. 全绿、全黑、黑白条纹和冻结画面方案

### 8.1 可能原因分类

1. 选中了 MONO/NIR/深度或厂商内部辅助传感器；
2. 物理输出 route 被枚举，但 stream combination 实际不支持；
3. 厂商 HAL 对特定尺寸、格式或多路流返回损坏 buffer；
4. YUV/RAW stride、CFA、black/white level 被错误解释；
5. 会话重建时旧 Surface、旧 generation 或未初始化首帧泄漏；
6. 设备带宽、热状态或相机资源竞争导致停帧；
7. TextureView 在旋转、分屏、DeX 或外接屏上 transform/尺寸不同步；
8. 系统测试版或厂商固件本身存在相机黑屏问题。

### 8.2 静态阻断

- 继续过滤 MONO、NIR、depth-only；
- 对缺失/未知 CFA 的 RAW 不启动 RAW 测光；
- 不把 `RAW_PRIVATE` 当 `RAW_SENSOR` 解析；
- 不对尚未通过 preview probe 的隐藏物理 route 启动完整会话；
- 不使用系统/私有 camera ID 或反射 vendor tag 绕过权限。

### 8.3 运行时检测

建立 `PreviewHealthEvidence`，每次只保存小型统计而不是 bitmap：

```kotlin
data class PreviewHealthEvidence(
    val timestampNs: Long,
    val meanRgb: FloatArray,
    val darkFraction: Float,
    val clippedFraction: Float,
    val rowPeriodicity: Float,
    val columnPeriodicity: Float,
    val textureTimestampAdvanced: Boolean,
    val captureTimestampAdvanced: Boolean,
    val yuvAgreement: Float?,
)
```

判定原则：

- 首 3–5 帧只 warm-up；
- 绿色、黑色或条纹必须跨时间确认；
- 条纹还需与时间戳异常、YUV/Texture 不一致或 route 切换相关证据之一共同成立；
- 正常静态条纹场景只生成低优先级 suspect，不自动永久禁用；
- 真正全绿/全黑且 metadata 正常推进，达到超时后触发一次恢复；
- 恢复后再次复现才将该 route/profile/size 标记为本次会话不兼容。

### 8.4 恢复顺序

恢复必须确定且有上限：

1. 关闭所有未释放 Image；
2. 停止 repeating request 并 abort capture；
3. 关闭 CaptureSession、ImageReader、Surface，再关闭 CameraDevice；
4. 增加 generation，旧回调全部丢弃；
5. 同 route 降低为 preview-only；
6. 降低尺寸/FPS；
7. 固定物理 route 改为逻辑自动 route，并明确更新 runtime identity；
8. 若仍失败，停止自动恢复，提示用户切换摄像头或重启设备。

每次恢复都记录原因和配置，不得无限循环，也不能在 UI 仍标“长焦”时悄悄回到主摄。

## 9. 厂商适配方案

### 9.1 通用原则

核心功能必须只依赖 Android Camera2 公共 API。厂商适配器只允许：

- 提供额外、经过授权的 camera route 或能力查询；
- 补充能力查询；
- 修正已确认的 vendor quirk；
- 在不支持/未授权时完全退回标准 Camera2。

不得：

- 通过反射调用私有 API；
- 硬编码系统相机的隐藏 ID 并直接 open；
- 因某个厂商 SDK 把整个应用 `minSdk` 提高；
- 把需要签名/白名单的能力描述成所有用户可用。

### 9.2 小米/Redmi/POCO

小米官方 Camera Engine 文档提供 SAT、多摄能力查询等接口，但要求：

- Camera API2；
- Android P/API 28 及以上；
- 指定机型；
- 开发者、应用、签名和测试机型授权；
- 引入厂商 AAR。

因此可设计 `XiaomiCameraAdapter`，但只能放在可选 product flavor 或动态加载模块中：

- 核心 APK 的 minSdk 仍为 26；
- API 26–27 和未授权设备不加载；
- 没有 SDK 时标准 Camera2 行为不受影响；
- 是否真正接入需产品方先完成小米授权，不属于通用修复前置条件。

### 9.3 华为旧 EMUI 与新荣耀设备

华为 Camera Engine 官方页面要求 EMUI 10、Kirin 980 及以上，并列出有限支持机型，还需要注册、协议和 SDK。该方案不能覆盖所有华为/荣耀设备，也不能作为老 Android 通用路径。

新荣耀的官方 Camera Engine 文档给出了另一条标准路径：MagicOS 8 及以上打开后置逻辑 camera，通过 `CONTROL_ZOOM_RATIO_RANGE` 查询范围，再用 Android 标准 `CONTROL_ZOOM_RATIO` 在小于 1x、广角段和长焦段间由 HAL 自动切镜头。它适合“连续光学变焦”产品，但会改变本项目当前“固定镜头选择与仅显示裁切的电子变焦彼此独立”的操作定义，因此本轮不接入。当前实现仍优先列出 Camera2 暴露的固定物理镜头；未来若增加“自动光学变焦”开关，应作为显式、默认关闭的独立能力。

建议：

- 默认标准 Camera2；
- 若后续取得 SDK 和授权，单独实现 `HuaweiCameraAdapter`；
- HarmonyOS/EMUI、旧荣耀和新荣耀不能只按品牌字符串共用 quirk；必须以实际能力和 build 信息判定；
- 厂商适配器失败后必须无副作用地回退 Camera2。

### 9.4 Samsung

本次查到的三星公开官方文档重点是 DeX/外接显示下的 Camera2 + TextureView 方向和 transform 处理，没有找到普通第三方 APK 可通用调用所有隐藏副摄的公开 SDK。

重点测试：

- DeX 双屏、窗口缩放和横竖屏时重新计算 preview transform；
- 不假设 Activity 占满整个屏幕；
- 折叠屏展开/合拢导致 display、窗口尺寸和摄像头 availability 变化；
- Exynos/Qualcomm 同型号变体分别测试，不按型号名直接套硬编码参数。

### 9.5 OPPO/OnePlus 与 vivo/iQOO

OPPO 官方 CameraUnit 开源示例提供依机型支持的超广角拍照、SAT Zoom 和前后多摄，但其文档明确把能力标为“取决于设备型号”，且当前公开支持列表有限。因此它只能作为将来的可选 `OppoCameraAdapter`，启动时先查询 SDK/机型能力，失败后无副作用回到 Camera2；本轮不把它并入通用核心。

本次检索未找到 vivo/iQOO 面向普通第三方 Android APK、可稳定覆盖全部镜头的公开通用 SDK。vivo/iQOO 默认继续使用标准 Camera2 + 运行时 probe + 设备兼容记录。

注意：OPPO Android 开发者预览官方页面曾明确列出特定场景相机黑屏，这只能说明系统/测试固件也可能是故障来源，不能据此推断正式版所有 OPPO 设备都有同一问题。

针对当前 vivo V2405A：

- 保留真实日志中发现的 ISP timestamp 等待需求；
- 不把该等待参数无条件硬编码到所有 vivo；
- 导出 route/profile/size/timestamp 差值后，再决定是否形成精确 quirk；
- 复核 `cameraId=0` 的已知 baseline 与实际 `0@physicalId` 身份不一致问题。

### 9.6 Google/Pixel 与 AOSP 设备

作为标准逻辑多摄和物理输出 route 的基线验证组：

- 检查管理页是否分别列出逻辑自动相机和可用物理摄像头；
- 检查选择每个物理条目后实际 configured/active physical ID 是否一致；
- 检查不能直接打开的 physical ID 是否只通过逻辑 route 使用；
- 仍不能假设 Pixel 的行为代表其他厂商。

### 9.7 外接 USB/UVC 摄像头

外接相机的 `INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL` 能力可能缺少焦距、光圈、传感器尺寸、RAW 和传感器曝光元数据。产品行为：

- 能预览则允许取景；
- 缺少绝对测光元数据时只显示“仅预览/相对亮度”；
- 断开连接时通过 availability callback 立即退出会话；
- 重新接入后 camera ID 可能变化，不能按 ID 永久复用校准；
- 不把 UVC 摄像头的缺省光圈猜成手机主摄光圈。

## 10. 代码拆分约束

当前 `CameraController.kt` 约 2400 行，`MeteringAnalysis.kt` 约 1100 行。后续改造不得继续把新逻辑写进这两个大类。

建议边界和行数目标：

| 组件 | 职责 | 建议上限 |
|---|---|---:|
| `CameraCapabilityReader` | 安全读取静态元数据 | 220 行 |
| `CameraRouteCatalog` | 生成用户可选 route | 300 行 |
| `CameraRouteProbe` | 会话/首帧能力验证 | 260 行 |
| `ElectronicZoomGeometry` | 电子裁切、显示与 ROI 的统一几何 | 180 行 |
| `Api26CameraSessionFactory` | 旧式会话创建 | 180 行 |
| `Api28CameraSessionFactory` | SessionConfiguration/物理输出 | 220 行 |
| `MeteringCapabilityEvaluator` | 分来源能力状态 | 200 行 |
| `RawMeteringAnalyzer` | RAW 统计 | 350 行 |
| `YuvMeteringAnalyzer` | Y plane 统计 | 300 行 |
| `IspMeteringAnalyzer` | 显示帧统计 | 260 行 |
| `CalibrationCompatibilityPolicy` | 校准计划、身份和失败原因 | 240 行 |
| `MultiCameraCalibrationPlanner` | 每个选择项的 RAW/流能力和覆盖计划 | 280 行 |
| `PreviewHealthMonitor` | 多证据健康状态机 | 300 行 |
| `CameraDiagnosticsCollector` | 导出非图像诊断信息 | 250 行 |

重构策略不是一次重写 2400 行控制器，而是每个提交抽出一个有单元测试的纯职责。`CameraController` 最终只负责生命周期编排和把事件交给组件，阶段目标先降到 1200 行以内，最终再评估是否能稳定降到 800 行附近。

依赖方向：

```text
UI
  -> CameraController (orchestrator)
      -> CapabilityReader / RouteCatalog / RouteProbe
      -> SessionFactory / ZoomController
      -> Metering source analyzers
      -> CalibrationCompatibilityPolicy
      -> PreviewHealthMonitor
```

分析器、策略和坐标计算不得反向持有 Activity、View 或 CameraDevice。

## 11. 错误与显示设计

### 11.1 不再用一个“相机失败”覆盖所有问题

错误至少分为：

- 权限未授予/系统禁用；
- 摄像头被其他应用占用；
- 相机服务/设备断开；
- route 不允许第三方访问；
- stream combination 不支持；
- 输出帧损坏；
- 缺少绝对测光元数据；
- RAW 格式不兼容；
- 同帧匹配超时；
- 校准中镜头切换；
- 外接相机已拔出。

每类错误定义：是否自动重试、是否降级、是否禁用本次 route、用户可执行动作、诊断代码。

### 11.2 用户可见文本

示例中文/英文必须成对加入资源或本项目既有本地化层：

```text
该镜头未向第三方应用开放 / This lens is not exposed to third-party apps
固定镜头不可用，已回退到自动相机 / Fixed lens unavailable; using automatic camera
可取景，但设备未提供绝对测光所需的曝光参数 / Preview works, but exposure metadata required for absolute metering is unavailable
校准过程中摄像头路由发生变化，请重新选择后重试 / The camera route changed during calibration; select it again and retry
当前流组合不受此设备支持 / This stream combination is not supported on this device
```

不能使用“主摄”笼统表示逻辑 route；正确用词是“自动相机/逻辑相机”。

### 11.3 诊断导出

新增用户主动触发的纯文本/JSON 诊断，不默认收集或上传：

- 品牌、型号、Android 版本、build fingerprint hash；
- 公开 camera IDs；
- 每个逻辑/物理关系；
- hardware level、capabilities、输出尺寸摘要；
- 物理 request keys、configured/active physical ID 可用性；
- route probe 结果；
- 会话 profile、尺寸、失败阶段和 CameraAccessException reason；
- 最近若干 timestamp 差值与健康统计；
- 每来源测光/校准能力和失败原因。

默认不包含照片、RAW、YUV buffer、灰卡画面、备注或历史记录。导出前显示隐私说明。

## 12. 实施顺序与提交边界

### 提交 1：API 26 基础兼容

- 把 API 28 会话代码移入 `Api28CameraSessionFactory`；
- 新增 `Api26CameraSessionFactory`；
- 处理 `java.util.Base64` 的 API 26 下限；
- 将 `minSdk` 改为 26；
- 运行 lint，确认所有 API 28+ 调用有隔离；
- README/README_ZH/CHANGELOG 写明 Android 8 为最低版本，以及旧设备能力按实际硬件降级。

验收：API 26 构建、安装、公开主摄 preview-only 可启动；API 28+ 原物理路由不回退。

### 提交 2：能力模型和准确错误

- 新增 `CameraCapabilitySnapshot`、`MeteringSourceCapability`；
- 加入 `READ_SENSOR_SETTINGS` 判据；
- 运行时确认 exposure/ISO/aperture；
- 校准页和测光按钮显示准确不可用原因；
- 外接/LEGACY 预览不再被错误标成可绝对测光。

### 提交 3：身份分离和目录修复

- 拆分 selection/runtime/calibration identity；
- 每个物理 route 独立列举；
- 修复全局物理 ID 去重归属；
- 回退时更新实际 route 和 UI；
- 禁止把回退结果写入原固定镜头校准。

### 提交 4：摄像头管理页完整枚举与选择验证

- `cameraIdList` 中所有可见光公共摄像头分别加入管理页；
- 每个逻辑相机下的 usable physical route 独立加入，不再要求同组至少两个；
- 选择项明确记录直接 CameraDevice ID 或逻辑 + 物理输出 ID；
- 选择后验证实际 configured/active route，失败时条目标记不可用；
- 不把逻辑回退继续显示为原固定摄像头；
- 保持电子变焦只做 TextureView/ROI 裁切，并按 camera ID 独立保存。

这是“很多机器无法在管理页选择全部第三方可用摄像头”的关键修复。

### 提交 5：route probe 与会话能力缓存

- preview-only 先探测；
- 模式需要时再探测 YUV/RAW；
- 缓存键包含 OS build、route、profile、size；
- 临时 busy 不写不兼容；
- availability callback 更新状态；
- 外接和折叠状态变化可恢复。

### 提交 6：校准签名和镜头稳定性

- 引入新版 `CalibrationSignature`；
- 引入逐 camera selection/route/source 能力矩阵；
- 每颗物理镜头独立探测 RAW 和会话组合，不继承逻辑相机的 RAW 标记；
- RAW 不可用时只降级到当前镜头自己的 YUV/ISP，不跨镜头借用 correction；
- 迁移旧校准并标记需验证；
- 固定物理 route 的 configured/active physical identity pin；
- OTA/算法变化失效策略；
- 删除或严格迁移 Vivo 硬编码 baseline；
- 校准页显示分来源、分镜头状态。

### 提交 7：YUV 响应与预览健康

- 提取 RAW/YUV/ISP analyzer，缩小 `MeteringAnalysis`；
- YUV clipping/黑白位和三点响应验证；
- 多证据绿屏/黑屏/条纹检测；
- 有限恢复次数和准确 route/profile 记录；
- 增加合成图像单元测试。

### 提交 8：厂商和设备回归

- 标准 Camera2 厂商矩阵；
- Samsung DeX/窗口/折叠；
- USB/UVC 断连；
- 根据真实日志添加最小化 quirk；
- 再决定是否申请和接入小米/华为可选 SDK；
- 同步 README、README_ZH、CHANGELOG、相机管线文档和隐私声明中的诊断导出说明。

每个提交独立、不可覆盖历史提交。不得把上述八个阶段合并成一次大重写。

## 13. 自动测试要求

### 13.1 单元测试

- API 26/28/29/30 session factory 选择；
- 逻辑/物理 route graph 和重复 ID；
- 只有一个 usable physical 时仍可生成 route；
- 电子变焦只改变 View/ROI，不生成 Camera2 zoom request；
- 不同 camera ID 的电子变焦偏好互不串用；
- 电子裁切、奇数尺寸、旋转后的 View/RAW/YUV/ISP ROI 一致；
- 缺 exposure/ISO/aperture 的能力状态；
- fallback 后 runtime/calibration identity；
- 固定物理 route 回退后拒绝写入原摄像头校准；
- 超广角无 RAW、主摄有 RAW、长焦 RAW 会话失败的混合能力矩阵；
- 不同管理页选择项不复用 correction；
- 每次校准阶段重建会话后重新确认 selection/runtime route；
- RAW-only 成功但 preview+RAW 失败时仍能生成正确的顺序校准计划；
- calibration signature 在 OTA、算法、尺寸、来源变化时失效；
- YUV stride、clipping、有限/全范围合成样本；
- Bayer 四种排列与 RGB/MONO/NIR 拒绝；
- 真实条纹与百叶窗场景的误报控制；
- generation 过期回调不更新 UI。

### 13.2 仪器化测试

- 打开/关闭/旋转/后台恢复循环 50 次；
- 快速切换镜头和模式；
- preview-only -> YUV -> RAW -> preview-only；
- 连续拖动电子变焦并确认 Camera2 route 不改变；
- 测光时旋转和电子变焦，确认可见区域与 ROI 一致；
- 校准三来源顺序执行；
- 相机被其他应用占用；
- USB 摄像头拔插；
- 分屏、窗口缩放、DeX/外接屏；
- 低内存和热降频场景。

## 14. 真机最低矩阵

| 维度 | 最低覆盖 |
|---|---|
| Android 版本 | API 26/27、28、29、30、33、35/36 |
| Camera2 等级 | LEGACY、LIMITED、FULL、LEVEL_3、EXTERNAL |
| 厂商 | Google、Samsung、小米/Redmi、华为/荣耀、OPPO/OnePlus、vivo/iQOO |
| 镜头结构 | 单后摄、公开多 ID、逻辑隐藏副摄、潜望长焦、前摄、USB/UVC |
| 形态 | 普通直板、折叠屏、平板、DeX/外接显示 |
| 来源 | ISP、YUV、RAW；以及仅预览设备 |

每台设备采集：

1. 公开 camera ID 和逻辑/物理关系；
2. 每个 route 的 preview probe；
3. 管理页每个条目选择后的 logical/configured physical/active physical ID；
4. 每个 profile 的会话结果；
5. CaptureResult 的 exposure/ISO/aperture 完整率；
6. RAW/YUV/ISP 各 20 次测光成功率和延迟；
7. 各来源校准是否成功、是否严格同镜头；
8. 旋转、后台、连续切换后的绿屏/黑屏/条纹/冻结；
9. 电量、温度和掉帧趋势。

## 15. 最终验收定义

### 老设备

- Android 8/8.1 可以安装并启动；
- 公共主摄能够 preview-only；
- 没有曝光元数据时明确显示“仅预览”，不会无期限校准；
- API 26–27 不触发任何 API 28+ 链接或运行时崩溃。

### 多摄

- `cameraIdList` 中所有可见光公共摄像头均有可解释状态；
- 隐藏物理摄像头只通过合法逻辑 route 使用；
- 所有可建立 preview route 的摄像头都能在管理页独立选择；
- 电子变焦不会改变 Camera2 route，并与测光 ROI 保持一致；
- 固定物理 route 失败时不会继续显示成固定长焦/超广角；
- 系统/私有摄像头不会被承诺为可调用。

### 测光与校准

- 预览、相对亮度、绝对 YUV/ISP、RAW 能力分开；
- 缺元数据时按钮和提示准确；
- RAW/YUV/ISP 校准保持顺序会话；
- 同帧、同 route、同活动镜头可证明；无法证明时明确降级；
- OTA、算法和 route 改变不会静默复用不匹配校准。

### 画面健康

- MONO/NIR/depth 不进入普通取景；
- 绿屏、黑屏、条纹、冻结有有限次数的确定恢复；
- 正常规则纹理不会仅凭单帧被永久禁用；
- 故障报告能定位 route/profile/size 和失败阶段。

### 工程质量

- 新代码按小组件拆分，不继续扩大 2400/1100 行大类；
- 单元测试、lint、debug/release 构建通过；
- README、中文 README、CHANGELOG、相机管线和隐私声明同步；
- 每个阶段独立 Git 提交，不覆盖历史。

## 16. 官方文档依据

Android/AOSP：

- [AOSP Multi-camera support](https://source.android.com/docs/core/camera/multi-camera)
- [Android Developers: Multi-camera API](https://developer.android.com/media/camera/camera2/multi-camera)
- [CameraManager：公开 ID、逻辑/物理摄像头边界](https://developer.android.com/reference/android/hardware/camera2/CameraManager)
- [CameraMetadata：LOGICAL_MULTI_CAMERA、RAW、READ_SENSOR_SETTINGS](https://developer.android.com/reference/android/hardware/camera2/CameraMetadata)
- [DngCreator：RAW 像素布局必须来自设备报告的 CFA](https://developer.android.com/reference/android/hardware/camera2/DngCreator)
- [CameraCharacteristics：硬件等级、输出能力、INFO_VERSION](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics)
- [OutputConfiguration.setPhysicalCameraId](https://developer.android.com/reference/android/hardware/camera2/params/OutputConfiguration#setPhysicalCameraId(java.lang.String))
- [CaptureResult：活动物理摄像头和实际请求结果](https://developer.android.com/reference/android/hardware/camera2/CaptureResult)
- [Camera availability callback](https://developer.android.com/reference/android/hardware/camera2/CameraManager.AvailabilityCallback)
- [CameraManager.getConcurrentCameraIds：独立 CameraDevice 并发能力](https://developer.android.com/reference/android/hardware/camera2/CameraManager#getConcurrentCameraIds())
- [多路 camera stream 组合](https://developer.android.com/media/camera/camera2/multiple-camera-streams-simultaneously)
- [ImageFormat.YUV_420_888](https://developer.android.com/reference/android/graphics/ImageFormat#YUV_420_888)
- [uses-sdk：minSdkVersion 与 targetSdkVersion](https://developer.android.com/guide/topics/manifest/uses-sdk-element)
- [Google Play target API 要求](https://developer.android.com/google/play/requirements/target-sdk)

厂商官方资料：

- [小米澎湃 OS 相机引擎技术接入文档](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1812)
- [小米澎湃 OS 相机引擎能力介绍](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1811)
- [小米 Camera SDK 接入与授权流程](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1868)
- [HUAWEI Camera Engine / Camera Kit](https://developer.huawei.com/consumer/cn/CameraKit)
- [荣耀 Camera Engine：超广角、长焦与 CONTROL_ZOOM_RATIO](https://developer.honor.com/cn/kitdoc?category=Media&docId=introduction.md&kitId=11034&navigation=guides)
- [OPPO CameraUnit 官方示例与支持能力](https://github.com/oppo/CameraUnit)
- [Samsung DeX 相机与 TextureView 适配](https://developer.samsung.com/samsung-dex/modify-optional.html)
- [OPPO Android Developer Preview 已知相机问题示例](https://open.oppomobile.com/android17/activitys/oppoweb17/pc/index.html?lang=en)

## 17. 给后续执行模型的约束

1. 本文是改造规格，不是完成清单；实现前先检查工作树，保留用户已有修改和截图。
2. 从提交 1 开始顺序执行；每阶段先写/更新测试，再改实现，再构建和真机验证。
3. 不得为了“调用全部摄像头”使用隐藏 API、反射 vendor tag、系统签名接口或伪造 camera ID。
4. 不得降低 `targetSdk`/`compileSdk` 来适配老设备；只把 `minSdk` 降到 26，并建立版本兼容层。
5. 厂商 SDK 不进入核心必选依赖，不得使 API 26 设备无法安装。
6. 不得把目录枚举成功当作会话成功，也不得把 preview 成功当作绝对测光成功。
7. 不得在物理路由失败后继续用固定镜头名称保存测光或校准。
8. 不得放宽校准的严格同帧、同 route 要求来掩盖超时。
9. 不得继续向 `CameraController`、`MeteringAnalysis` 写入大段新职责；优先抽取小组件。
10. 每次提交同步中英文文案和相应文档；最终再更新 README/声明中的已实现能力，规划中的能力不得提前宣传为完成。
