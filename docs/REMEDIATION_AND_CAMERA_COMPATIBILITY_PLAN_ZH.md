# 光档高/中风险修复与相机兼容改造规格

状态：实施中；第一批隐私、物理路由、RAW 格式、严格帧配对与非阻塞关闭已完成
适用基线：当前 `0.2.1` 工程
读者：后续负责实施、审查和真机验证的模型或开发者

本文不是一般性建议，而是后续修改必须遵守的执行规格。除非实施中发现 Android API
事实与本文冲突，否则不得在没有记录原因、测试证据和替代方案的情况下改变这里定义的
安全边界、资源所有权、回退顺序和验收条件。

---

## 1. 范围、目标与不在范围内的事项

### 1.1 本轮必须解决

高严重度问题：

1. 隐私声明与实际的定位、JPEG、DNG 和参数记录行为不一致。
2. 明确告知用户：应用私有目录中的照片、DNG、位置和备注可能由 Android 或设备厂商的
   备份/换机服务复制到系统云备份或新设备。
3. 不能再用逻辑多摄的 `SENSOR_SYNC_TYPE_APPROXIMATE` 禁止单个物理镜头路由。
4. 逐帧跟踪逻辑相机实际使用的 `ACTIVE_PHYSICAL_ID`。
5. 修正预览旋转、镜像、缩放、折叠屏、DeX、多窗口和 180 度旋转。
6. 消除相机关闭、重新打开和慢速厂商 HAL 之间的跨代资源竞争。

中严重度问题：

1. RAW 测光不做解拜尔，但必须正确区分 Bayer RAW 与 `CFA_RGB` 的像素布局。
2. 参数记录保存改成可恢复的事务，不能留下无索引孤儿 JPEG/DNG。
3. Manifest 使用 `camera.any`，避免错误过滤仅前摄或外接相机设备。
4. API 29+ 优先使用厂商推荐的 PREVIEW、YUV 和 RAW 流配置。
5. 兼容测光不得再把 `TextureView.getBitmap()` 与无关的 `latestResult` 组合为一次读数。
6. 为历史全绿、黑白条纹、冻结和异常物理传感器建立完整的检测、隔离和恢复机制。

### 1.2 明确不做

- 不把核心相机迁移到 CameraX。
- 不把华为 Camera Engine、小米相机引擎等私有 SDK 加入核心测光路径。
- 不做全分辨率 RAW 解拜尔、图像重建或美化。
- 不依赖远程服务器下发设备黑名单。
- 不用单个厂商名称作为主要能力判断。
- 不在这次修改中改变测光表的 UI 视觉风格和曝光计算模型。
- 不接受新的上千行控制器、Repository 或 View。

---

## 2. 已确定的产品与技术决策

### 2.1 Camera2 仍是核心

项目需要 RAW、逐帧曝光元数据、物理镜头和可控输出流，Camera2 是正确的底层 API。
厂商私有 SDK 多数带有型号授权、特定输出尺寸或系统算法处理，不适合作为可校准测光的
公共基础。

### 2.2 RAW 测光不需要解拜尔

测光只需要区域统计，不需要生成可观看的 RGB 图像。对普通 Bayer RAW，继续按每个 2×2
Bayer 单元取得 `R/G1/G2/B`，分别扣黑电平、按白电平归一化，再计算亮度即可。不得加入
插值、边缘重建或完整去马赛克处理。

### 2.3 `CFA_RGB` 先安全降级

Android 对 `SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGB` 的定义不是 Bayer：每个像素包含
三个 16-bit 值。当前 C++ 每个像素只读取一个 16-bit 值，再按 Bayer 的奇偶位置分配到
R/G1/G2/B，因此会读取错误的通道和错误的黑电平位置。

首个安全版本必须：

- 发现 `CFA_RGB` 时禁止进入当前 Bayer JNI 分析器。
- 如果 YUV 可用，使用严格时间戳配对的 YUV 兼容测光。
- 如果只有预览，使用后文定义的时间戳配对显示帧；不能回落到 `latestResult`。
- UI 显示“该传感器 RAW 布局不受支持，已使用兼容测光”。

后续若要支持 RGB48，也不需要解拜尔；应增加独立的 `RGB48` reader，按每像素
R/G/B 三个 16-bit 分量直接做区域统计。没有完成真实 RGB CFA 文件测试前不得启用。

### 2.4 YUV 的 Y 可以反映亮度，但不是线性光度值

结论：Y 分量可以用于反射式测光的兼容路径，但需要同帧曝光元数据和流级校准。

Android Camera2 默认 YUV 输出采用 JFIF/Rec.601 full-range 变换，转换后的 RGB 位于 sRGB
色彩空间。Y 是非线性编码后的 luma，不是传感器线性辐照度，也不是严格的物理亮度：

- 它包含 RGB 对视觉亮度的加权，因此比只取某个颜色通道更接近“明暗”。
- 它受 ISP gamma、tone curve、HDR、局部映射、降噪、AWB 和厂商调校影响。
- 在固定设备、相机、流配置和处理管线下，通常与场景亮度单调相关。
- 仅有一个 EV 偏移校准可以修正常量误差，不能修正厂商 tone curve 的斜率变化。

本轮保留 Y-only 快速测光，不强制复制 U/V；但必须做到：

1. YUV `Image.timestamp` 与相同 `SENSOR_TIMESTAMP` 的 CaptureResult 配对。
2. 校准键包含实际物理相机、流类型和必要的配置版本。
3. 将结果标为 ISP/YUV 兼容测光，而不是 RAW 线性测光。
4. 后续可选增加 5 点灰阶校准，在 log2 亮度域拟合单调分段曲线；不得用无界多项式。
5. 当前 `srgbToLinear(Y')` 是可工作的近似，不应表述为严格的 YUV→线性亮度变换。

推荐的未来扩展形式：

```text
EV100 = cameraEV + F_camera_stream(Y_normalized) - F_camera_stream(Y_reference)
        + userOffsetEV
```

`F_camera_stream` 默认保持当前行为；只有完成多点校准后才替换为受限的单调 LUT。

### 2.5 系统备份按“保留但充分告知”处理

按本次产品要求，不直接关闭 `allowBackup`。应用必须明确说明：

- 应用本身没有 INTERNET 权限，也不向开发者上传数据。
- Android、Google、三星、小米、华为等系统/厂商备份或换机服务可能代表用户复制应用私有
  数据。
- 最终是否上传、是否加密、是否跨设备恢复以及保留多久，由设备、账号、系统设置、厂商
  和备份配额决定。
- 开发者无法读取用户系统账号内的备份副本。
- 大型 DNG 可能因系统备份配额而没有被备份，不能承诺一定可恢复。

---

## 3. 代码规模与组件边界硬约束

实施时优先在现有 package 下建立小组件，再逐步缩减 `CameraController`。禁止把新逻辑继续
堆入现有 2084 行控制器。

### 3.1 行数目标

- `CameraController`：最终目标不超过 500 行，只保留应用门面和组件装配。
- 新建相机组件：目标 120～300 行，原则上不得超过 400 行。
- 新建 Repository/Store：目标不超过 300 行。
- 纯数学或不可再拆的表驱动文件可以例外，但超过 500 行必须在审查说明理由。
- 一个文件只拥有一类可变资源或一个状态机。
- 不为了满足行数机械拆出没有语义边界的 `Utils1/Utils2`。

### 3.2 建议组件

```text
camera/catalog/
  CameraCapabilitySnapshot.kt
  CameraRouteResolver.kt
  CameraCapabilityClassifier.kt

camera/session/
  CameraSessionPlan.kt
  CameraSessionPlanner.kt
  CameraSessionLease.kt
  CameraLifecycleReducer.kt
  CameraLifecycleCoordinator.kt

camera/physical/
  ActivePhysicalCameraTracker.kt
  EffectiveCaptureMetadata.kt

camera/preview/
  PreviewGeometry.kt
  PreviewTransformCalculator.kt
  PreviewTransformController.kt
  DisplayedFrameSynchronizer.kt
  PreviewHealthAnalyzer.kt
  PreviewHealthMonitor.kt

camera/recovery/
  CameraFailureClassifier.kt
  CameraRecoveryPlanner.kt
  CameraCompatibilityStore.kt
  CameraDiagnosticSnapshot.kt

metering/raw/
  RawPixelLayout.kt
  BayerMeteringSampler.kt

metering/yuv/
  YuvMeteringCoordinator.kt
  YuvTransferCalibration.kt       # 多点校准可延后

records/
  ParameterRecordRepository.kt    # 门面
  ParameterRecordTransaction.kt
  ParameterRecordIndexStore.kt
  ParameterRecordRecovery.kt
```

现有 `CameraSessionCoordinator`、`RawLightMeter`、`CompatibleLightMeter` 和
`TimestampedResultPairer` 应复用或缩减，不要复制第二套相同所有权逻辑。

### 3.3 依赖方向

- catalog 不依赖 session。
- session 可以依赖 catalog 的不可变快照。
- preview 不拥有 CameraDevice/Session。
- health analyzer 是纯函数，不依赖 Android View。
- health monitor 只负责取样、窗口和状态，不负责直接关闭相机。
- recovery planner 只返回动作，由 lifecycle coordinator 执行。
- UI 只消费不可变状态，不直接持有 Camera2 资源。

---

## 4. 工作包 P0-A：隐私声明、备份规则与用户提示

### 4.1 必改文件

- `PRIVACY.md`
- `README.md`
- `README_ZH.md`
- 应用内“信息/隐私”页面文案和中英文 strings
- `AndroidManifest.xml`
- 新增 `res/xml/data_extraction_rules.xml`（API 31+）
- 新增 `res/xml/backup_rules.xml`（API 28～30）
- 应用商店 Data Safety/隐私标签，由发布者在发版时同步

README 中所有“不请求定位”“不会保存照片”的旧表述必须一次性搜索并更正，不能只改
`PRIVACY.md`。

### 4.2 `PRIVACY.md` 中文替换文本

实施时应以以下事实为准，可在不改变含义的前提下润色：

> 光档在设备本地处理相机画面和运动传感器采样，用于计算曝光、区域制标记和相关摄影
> 工具。应用不申请 INTERNET 权限，不包含广告或统计 SDK，也不会由应用或开发者把相机
> 画面、测光数据、位置、备注或设置上传到开发者服务器或第三方服务。
>
> 应用需要相机权限才能提供取景和测光。位置权限是可选的，仅在用户主动启用参数记录
> 的位置选项并授权后使用。应用可能读取最近位置或请求一次当前位置，并把获得的位置写入
> 用户主动保存的参数记录；应用不在后台持续跟踪位置。
>
> 当用户主动保存参数记录时，应用会在应用私有存储中保存取景 JPEG、曝光参数、时间、
> 备注和用户选择记录的位置；启用 RAW 记录时还可能保存 DNG。普通取景和未保存的测光
> 画面不会作为参数照片长期保留。
>
> Android 或设备厂商提供的系统备份、云备份和换机服务可能按照用户的系统设置，将应用
> 私有目录中的设置、校准数据、JPEG、DNG、位置和备注复制到系统备份服务或另一台设备。
> 这些传输由操作系统或设备厂商执行，并非由光档或开发者上传。开发者无法访问用户系统
> 账号中的备份副本。是否备份、加密、保留或成功恢复取决于设备、系统版本、账号、厂商
> 策略和备份配额；较大的 DNG 不保证能够进入备份。
>
> 用户可以在应用中删除参数记录，也可以在系统设置中关闭本应用的备份。卸载应用通常会
> 删除设备上的应用私有数据，但已经由系统备份服务保存的副本可能按照相应服务的保留策略
> 继续存在。用户可通过系统或厂商账号的备份管理功能删除这些副本。
>
> 项目源代码可公开审计。若发行者加入网络、统计、崩溃上报、其他云同步或新的数据处理方，
> 必须更新本隐私声明和应用商店披露。

### 4.3 `PRIVACY.md` 英文替换文本

> lightstop processes camera frames and motion-sensor samples locally to calculate exposure,
> track Zone markers, and provide related photography tools. The app does not request the
> INTERNET permission, include advertising or analytics SDKs, or send camera frames, meter
> readings, location, notes, or settings to the developer or a third-party server.
>
> Camera permission is required for viewfinding and metering. Location permission is optional
> and is used only after the user enables location for parameter records and grants permission.
> The app may read a recent location or request one current location and attach it to a record
> the user chooses to save. It does not continuously track location in the background.
>
> When the user saves a parameter record, the app stores a viewfinder JPEG, exposure parameters,
> time, notes, and an optional authorized location in app-private storage. If RAW recording is
> enabled, a DNG may also be stored. Ordinary live-preview frames and unsaved meter readings are
> not retained as parameter photographs.
>
> Android or device-manufacturer backup, cloud-backup, and device-transfer services may copy
> app-private settings, calibration data, JPEGs, DNGs, locations, and notes according to the
> user's system settings. Such transfers are performed by the operating system or device vendor,
> not by lightstop or its developer. The developer cannot access copies held in the user's system
> account. Backup, encryption, retention, and restore behavior depend on the device, OS version,
> account, vendor policy, and quota. Large DNG files are not guaranteed to be backed up.
>
> Users can delete parameter records in the app and can disable backup for the app in system
> settings. Uninstalling normally removes app-private data from the device, but copies already
> held by a system backup service may remain subject to that service's retention policy.

### 4.4 首次保存前的强提示

在第一次真正写入参数记录之前显示，不应在刚启动应用时无关弹出。

标题：`参数记录与系统备份`
正文：

> 保存记录会把取景照片、曝光参数、备注，以及你选择记录的位置写入应用私有存储；启用
> RAW 时还会保存 DNG。Android 或设备厂商启用的云备份/换机功能可能复制这些内容。
> 备份由系统或厂商执行，开发者无法访问。你可以在系统设置关闭本应用备份，也可以随时
> 删除记录。

按钮：

- `我已了解并继续`
- `取消保存`
- 可进入 `查看隐私说明`

约束：

- 使用版本化键 `parameter_record_privacy_notice_version`，不能只存一个 boolean。
- 隐私事实变化后提升版本，再次提示。
- 取消不能创建 JPEG、DNG、位置请求或半成品文件。
- 只启用 GPS 时仍需要 Android 运行时权限提示；系统权限提示不能替代这里的数据用途说明。
- 设置/信息页必须能随时重新查看该说明。

### 4.5 显式备份规则

不要继续完全依赖平台默认集合。两个规则文件应表达相同产品意图：

- 允许备份最终提交的 `files/parameter_records/`。
- 允许备份必要 SharedPreferences 和 `files/vignetting-calibration/`。
- 排除 cache、事务临时文件、隔离删除目录、诊断日志和安装标识。
- `noBackupFilesDir` 的安装标识继续留在不可备份位置。
- cloud backup 建议使用 `disableIfNoEncryptionCapabilities="true"`。
- 设备到设备迁移是否包含最终记录，应与隐私声明一致。

实施前应先形成一张“路径—数据类型—是否云备份—是否设备迁移”清单，不能用
`path="."` 粗放包含整个 files 域。

### 4.6 验收

- 全仓库搜索不到“不请求定位”“不保存照片”等错误声明。
- 未同意提示时不生成任何记录临时文件。
- 同意后可保存，提示版本写入 SharedPreferences。
- API 28、30、31、36 的 merged manifest 均引用正确的规则。
- 使用 `adb shell bmgr` 或真机备份检查，临时文件和安装 ID 不进入备份集合。
- 应用内、README、隐私文档和商店披露陈述一致。

---

## 5. 工作包 P0-B：物理镜头路由

### 5.1 删除错误决策

当前 `CameraController.openCamera()` 中“APPROXIMATE 就使用逻辑路由”的判断必须移除。
同步类型只影响两个物理传感器同时工作的时间关系，不应阻止只选择一个物理输出。

### 5.2 `CameraRouteResolver` 输入与输出

输入：

- 用户选择的 `CameraDescriptor`
- 逻辑与物理 characteristics
- API 级别
- 当前 compatibility cache
- 是否已经因该物理路由失败过

输出 `CameraRouteCandidate` 列表，按顺序尝试：

1. 用户请求的单物理路由。
2. 对应逻辑相机自动路由。
3. 同朝向的主逻辑相机。

每个 candidate 必须包含：

- logical ID
- requested physical ID
- characteristics source ID
- calibration identity
- 允许的 session profiles
- 失败后的下一个 route

### 5.3 会话验证

- API 29+：用带 `OutputConfiguration.setPhysicalCameraId()` 的完整配置调用
  `isSessionConfigurationSupported()`。
- Android 9：直接创建真实 session。
- 查询返回支持仍要真实创建并等待有效帧。
- 查询返回不支持只降级该“route + profile + size”组合，不拉黑整个物理镜头。
- 物理请求只能设置 `getAvailablePhysicalCameraRequestKeys()` 中允许的 key。

### 5.4 元数据约束

- 请求固定物理流时，曝光、ISO、光圈、AWB、黑电平等测光元数据优先从对应 physical
  result 获取。
- 物理 result 缺少 `SENSOR_TIMESTAMP` 时，只允许从同一个 TotalCaptureResult 回退时间戳；
  不能从 unrelated latest result 补曝光或 ISO。
- 缺少必需测光字段则丢弃该帧并受限重试，而不是生成部分可信读数。

### 5.5 验收

- APPROXIMATE 设备仍会实际尝试单物理路由。
- 物理路由成功时 UI、校准 key、记录和诊断都使用同一个物理 ID。
- 配置失败能回退逻辑相机，且 UI 明确显示“系统自动选镜头”。
- 单元测试覆盖 CALIBRATED/APPROXIMATE/null 三种同步值；三者都不直接决定单物理流可否使用。

---

## 6. 工作包 P0-C：活动物理相机跟踪

### 6.1 新建 `ActivePhysicalCameraTracker`

状态：

```text
logicalCameraId
requestedPhysicalCameraId?
activePhysicalCameraId?
characteristicsVersion
lastChangedTimestamp
```

每个 `TotalCaptureResult`：

1. 固定物理路由时，实际 ID 以 requested physical 为主，并验证对应 physical result。
2. API 29+ 的逻辑路由读取 `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID`。
3. 字段为 null 时保持 unknown，不把 UI camera ID 猜成 active physical ID。
4. ID 变化后发布不可变的 `ActiveCameraContext`。

Android 9/API 28 没有可依赖的 active physical result key。自动逻辑路由在该版本只能使用
“逻辑复合相机”身份和逻辑校准，不得猜测当前物理镜头，也不得套用固定物理镜头校准。

### 6.2 切换时必须完成

- 重新读取该 physical ID 的 characteristics。
- 更新传感器方向、lens facing、焦距、光圈、physical size 和 active array。
- 更新预览和 ROI 变换。
- 切换校准 identity。
- 取消正在进行的 RAW/YUV/暗角测量，等待新镜头 3A 稳定后重试。
- 参数记录保存实际镜头 ID；不能只保存 UI 选择 ID。
- 兼容性健康状态按实际物理 ID 分开统计。

### 6.3 校准 key 迁移

新 key 至少包含：

```text
manufacturer | model | logicalId | actualPhysicalId-or-logical | sourceType | schemaVersion
```

旧的 `manufacturer + model + cameraId` 校准：

- 不直接删除。
- 如果可以无歧义映射到同一物理镜头，复制为 migrated 记录并要求一次复核。
- 逻辑 ID 曾经可能跨多个传感器时，不自动套用到各 physical ID。

### 6.4 验收

- 逻辑相机缩放换镜时，诊断和 UI 能显示 activePhysicalId 变化。
- 换镜发生在测量窗口内时，不输出混合两个镜头的读数。
- Fold 折叠/展开导致前摄改变时，方向和 characteristics 随之更新。

---

## 7. 工作包 P0-D：预览方向、镜像与 ROI 几何

### 7.1 纯函数 `PreviewTransformCalculator`

输入必须完整：

```text
viewWidth, viewHeight
bufferWidth, bufferHeight
displayRotation
sensorOrientation
lensFacing
scaleType (CENTER_CROP/FIT_CENTER)
displayZoom
mirrorFrontCamera
```

输出：

- sensor/buffer → view Matrix
- view → sensor 的 inverse Matrix
- 实际显示 crop rect
- 是否交换 buffer 宽高
- relative rotation

相对旋转使用 Android 官方 Camera2 规则，不再假定传感器为 90 度。前摄镜像与旋转分开
处理，避免符号叠加错误。

### 7.2 所有 ROI 共用同一几何源

预览点击、区域制、RAW ROI、YUV ROI、暗角采样和保存网格都必须从同一
`PreviewGeometrySnapshot` 推导，禁止每个功能各写一套旋转公式。

### 7.3 事件来源

- TextureView size change
- 当前 View 所属 Display 的 rotation
- `OrientationEventListener`，覆盖不会触发配置回调的 180 度旋转
- WindowMetrics/WindowInsets 变化
- 多窗口、自由窗口、外接显示器和 DeX
- 折叠状态变化
- activePhysicalId 变化
- 前后摄切换

不得继续使用 `windowManager.defaultDisplay` 作为唯一来源。

### 7.4 单元测试矩阵

至少覆盖：

- sensor orientation：0/90/180/270
- display rotation：0/90/180/270
- back/front/external
- 4:3 buffer → 竖屏、横屏、宽屏窗口
- centerCrop/fitCenter
- 镜像前摄
- Fold 展开后 active physical orientation 变化
- view→sensor→view 往返误差小于 1 像素
- 中心点、四角点和边缘 ROI 不越界

### 7.5 验收

- Samsung DeX TextureView 不横置、不拉伸。
- USB 相机 `SENSOR_ORIENTATION=0` 正常。
- 180 度旋转立即更新而不是等待 Activity 重建。
- 取景上的测光点和 RAW 实际 ROI 在测试卡上对应。

---

## 8. 工作包 P0-E：相机生命周期和代次所有权

### 8.1 状态机

使用纯 reducer 表达：

```text
STOPPED
STARTING
OPENING
CONFIGURING
STREAMING
CLOSING
WAITING_FOR_AVAILABILITY
ERROR
```

事件包括：StartRequested、StopRequested、DeviceOpened、SessionConfigured、ConfigureFailed、
DeviceDisconnected、CameraError、CloseCompleted、AvailabilityChanged、SurfaceLost、SurfaceReady。

### 8.2 `CameraSessionLease`

每次打开创建独立 lease：

```text
generation
logical/physical route
HandlerThread/Executor
CameraDevice
CameraCaptureSession
preview Surface
RAW/YUV readers
all timeout tokens
closed flag
```

规则：

- 资源只能由创建它的 lease 关闭。
- 所有 callback 第一行检查 generation/lease identity。
- close 必须幂等。
- 旧 lease 回调不能访问新的全局 session coordinator。
- ImageReader 和 Surface 的释放顺序属于 lease，不属于 Activity。

### 8.3 主线程行为

- `onPause()` 只提交 StopRequested 并清理 UI 状态，不等待 750 ms，不 join 相机线程。
- 如果 `onResume()` 发生在 CLOSING 中，只记录 `desiredRunning=true`。
- 旧 lease 真正关闭后再打开新代；不能并行复用相同逻辑 camera ID。
- 超时表示进入等待/诊断状态，不表示可以让旧资源操作新资源。
- 相机被其他应用占用时监听 AvailabilityCallback，而不是高频循环重开。

### 8.4 错误分类

- IN_USE/MAX_CAMERAS：等待优先级或 availability，不立即降档。
- DISABLED/权限：停止并提示用户，不重试。
- DISCONNECTED：关闭当前 lease，等待设备重新出现。
- CONFIGURE_FAILED：同 route 进入 profile 降级。
- DEVICE/SERVICE：一次完整 reopen；重复后进入安全路由。
- 画面健康失败：进入后文的流/route 恢复，不等同 Camera2 exception。

### 8.5 验收

- 主线程不再出现固定相机关闭等待。
- 慢 HAL 模拟下，旧 generation 的延迟回调不能关闭新 generation。
- pause/resume 100 次后只有一个有效 lease、一个相机线程和预期数量 reader。
- 测量过程中切后台，所有 Image 最终恰好关闭一次。

---

## 9. 工作包 P1-A：RAW 像素布局，不做解拜尔

### 9.1 `RawPixelLayout`

```text
BAYER_RGGB
BAYER_GRBG
BAYER_GBRG
BAYER_BGGR
RGB48_UNSUPPORTED
MONO_UNSUPPORTED
NIR_UNSUPPORTED
UNKNOWN_UNSUPPORTED
```

首个版本 JNI 只接受前四种。Kotlin 在调用 JNI 前验证；C++ 也必须防御性验证 cfa 仅为
0..3，不能用 default 当作 RGGB。

### 9.2 Bayer 测光算法保持简单

- 以 2×2 对齐 ROI。
- 分别收集 R/G1/G2/B 中位数。
- 对应位置扣四项 black level。
- 按 dynamic/fixed white level 归一化。
- 允许用 gains/transform 计算传感器到线性 RGB 的亮度。
- 元数据不足时使用明确的 legacy 权重并标注诊断，而不是解拜尔。
- 不生成 RGB bitmap。

### 9.3 `CFA_RGB` 的影响说明

如果继续按 Bayer 读取，结果会受到明显影响：当前 reader 很可能只取每像素三个分量中的
第一个分量，再把相邻像素错误标为不同颜色；绿色和蓝色贡献可能完全缺失，场景颜色一变，
EV 就会产生颜色相关偏差。它可能给出“看似正常”的数值，因此比直接报错更危险。

### 9.4 测试

- 四种 Bayer 排列使用同一合成 2×2/4×4 数据，得到一致的目标 R/G/B。
- C++ 对 cfa=4/5/6/null 映射拒绝并返回明确状态，不返回伪零数组。
- pixelStride、rowStride、buffer offset 和尾部 padding 单独测试。
- CFA_RGB 设备路径自动选择 YUV，不调用 JNI。

---

## 10. 工作包 P1-B：参数记录事务

### 10.1 问题

当前顺序为“移动 JPEG → 移动 DNG → 更新内存 → AtomicFile 写索引”。任一步失败都可能
留下孤儿文件，AtomicFile 只能保护 index 本身，不能让媒体和 index 成为同一个事务。

### 10.2 采用写前日志和同文件系统 staging

事务目录位于 `files/parameter_records/.transactions/<transactionId>/`，并从备份规则排除。

步骤：

1. 验证记录 ID、源文件 canonical path 和目标路径。
2. 在 transaction 目录写 `manifest.json.part`，包含目标分类、文件名、大小、是否有 DNG、
   index 旧版本和状态 PREPARING。
3. 将 cache 中 JPEG/DNG 复制到 transaction 目录的 `.part` 文件。
4. flush 并调用 `FileDescriptor.sync()`。
5. 写完整 manifest，AtomicFile/临时 rename 后状态 PREPARED。
6. 将媒体从 transaction 目录 rename 到最终分类；因处于同一 filesDir 文件系统，rename
   应为原子目录项操作。
7. 构造新的不可变 index，AtomicFile 提交。
8. manifest 标记 COMMITTED，然后删除 transaction 目录。
9. 任何异常都交给统一 rollback/recovery，不在多个 catch 中零散删除。

### 10.3 启动恢复

`ParameterRecordRecovery` 在 Repository 可见数据前扫描 journal：

- index 已包含记录且媒体完整：视为已提交，清理 journal。
- index 不含记录但最终媒体存在：删除最终媒体并恢复旧状态。
- 只有 `.part`：删除事务目录。
- index 含记录但文件缺失：从 prepared 文件恢复；无法恢复则移除损坏 index entry，并记录
  可诊断错误。
- 不自动删除不认识的普通文件；移到 quarantine 并只在确认路径策略后处理。

### 10.4 并发和 API

- Repository 写操作使用单线程 executor 或 Mutex；不能仅依赖方法级 `@Synchronized` 后在
  不同线程执行文件 IO。
- UI 只收到 Success/Failure，不接触临时路径。
- 事务对象拥有所有 staging 文件。
- 保存失败必须保留原有 categories 内存快照。

### 10.5 故障注入测试

在上述每一步之后模拟异常/进程死亡，重新创建 Repository，验证：

- 不出现 index 指向不存在文件。
- 不出现无 index 的最终 JPEG/DNG。
- 同一事务不会生成重复记录。
- DNG 不存在时 JPEG 事务仍正确。
- 磁盘满、rename 失败、index 写失败均可恢复。

---

## 11. 工作包 P1-C：Manifest 设备过滤

建议声明：

```xml
<uses-feature android:name="android.hardware.camera.any" android:required="true" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
<uses-feature android:name="android.hardware.camera.autofocus" android:required="false" />
<uses-feature android:name="android.hardware.camera.capability.raw" android:required="false" />
<uses-feature android:name="android.hardware.location.gps" android:required="false" />
<uses-feature android:name="android.hardware.location.network" android:required="false" />
```

说明：

- 应用没有任何相机时无法实现主要功能，因此 `camera.any=true`。
- 后置、自动对焦、RAW、GPS 都是可选能力。
- 保留 CAMERA 和可选位置权限；权限与 feature 是不同层次。
- 相机枚举为空时显示明确的不支持页面，不能崩溃。

验收：使用 bundletool/Play Console device catalog 检查 Chromebook、仅前摄平板、普通手机和
RAW 手机的过滤结果。

---

## 12. 工作包 P1-D：推荐流配置与会话计划

### 12.1 `CameraSessionPlanner`

输入是不可变 capability snapshot、route、用户模式和 compatibility history；输出有序的
`CameraSessionPlan`，不得直接创建 Surface。

每个 plan 包含：

- profile
- preview size/source
- YUV size/source
- RAW size/source
- physical ID assignment
- stream use cases
- FPS 策略
- session query 结果
- 降级后的 next plan key

### 12.2 尺寸来源优先级

API 29+：

1. `USECASE_PREVIEW` 推荐 PRIVATE 尺寸。
2. `USECASE_PREVIEW` 推荐 YUV 尺寸。
3. `USECASE_RAW` 推荐 RAW_SENSOR 尺寸。
4. 缺失时才使用完整 `SCALER_STREAM_CONFIGURATION_MAP`。

选择规则：

- 预览优先不超过 1080p 的推荐尺寸，兼顾 view aspect。
- YUV 测光不需要最大尺寸；优先 640×480、720p 或推荐的低开销尺寸。
- RAW 测光继续优先满足统计精度的最小 RAW_SENSOR 尺寸，避免 stall 和内存压力。
- 不要求三种流尺寸相同，但 session 组合必须查询并真实验证。
- 物理流使用对应物理 characteristics 支持的尺寸，并通过逻辑 session 查询组合。

API 33+：只有 `SCALER_AVAILABLE_STREAM_USE_CASES` 声明支持时才设置
PREVIEW/STILL_CAPTURE；不支持则保持 DEFAULT。

### 12.3 首次设备与历史设备

- 未知设备先准备 SAFE_PREVIEW plan；确认有效帧后升级到目标 profile。
- 已验证且 Build.FINGERPRINT、相机签名和 app compatibility schema 未变化，可直接尝试
  历史最高成功 plan。
- 系统升级后保留历史作为排序参考，但至少重新做一次健康验证。

### 12.4 验收

- 推荐 map 存在时选中的尺寸来自推荐集合；不存在时与当前回退一致。
- 会话计划是纯函数，可用伪造 characteristics snapshot 单测。
- 同一 plan 失败只影响精确 plan key，不把整个机型永久降为兼容模式。

---

## 13. 工作包 P1-E：严格同帧的兼容测光

### 13.1 当前事实

现有 YUV 主路径已经通过 `TimestampedResultPairer` 将 `Image.timestamp` 与
`CaptureResult.SENSOR_TIMESTAMP` 配对，这一部分应保留。

不严格的是 YUV 超时/失败后的显示预览路径：它在主线程调用 `TextureView.getBitmap()`，
同时读取当时的 `latestResult`。Camera2 是流水线，屏幕中的 buffer 和最近回调元数据可能
相差一帧或多帧，尤其在 AE 变化、镜头移动和厂商合成管线中。

### 13.2 默认路径：YUV 精确配对

- 测光请求开始时清空旧 pairer。
- 只接受测量 operation ID 之后的帧。
- exact timestamp 优先；容差只用于已知 HAL 小偏差，容差来源应基于当前流最小帧时长且有
  上限。
- paired result 中缺 exposure/ISO/aperture 时丢弃这一帧。
- 最多 3 帧或 250 ms，之后进入显示帧配对，而不是 `latestResult`。

### 13.3 显示帧配对：`DisplayedFrameSynchronizer`

Camera2 官方说明同一次 capture 的输出 buffer 时间戳与 `SENSOR_TIMESTAMP` 相同；
SurfaceTexture 可通过 `getTimestamp()` 取得当前纹理 buffer 时间戳。因此保底路径可以做到
“显示 buffer 时间戳配对”，而不是猜测最近结果。

流程：

1. `onSurfaceTextureUpdated()` 读取 `surfaceTexture.timestamp`。
2. 将 timestamp 放入与 CaptureResult 共用的有界 pairer。
3. 只有 timestamp/result 配对后才允许截图。
4. 主线程截图前再次读取 timestamp，必须仍等于目标值。
5. 调用 `getBitmap(96, 96)`。
6. 截图后再次读取 timestamp；若已变化，回收 bitmap 并等待下一帧。
7. bitmap 只与已配对的 result 一起送入分析。
8. 三次竞争失败或超时后返回“无法取得同步预览帧”，不能偷偷使用 latestResult。

`TextureView` 截图属于 ISP、显示裁切和缩放后的图像，即使时间戳相同也应标记为
`DISPLAY_TIMESTAMP_PAIRED`，与 `YUV_SENSOR_PAIRED` 区分校准。

### 13.4 如果厂商违反时间戳契约

- 记录 YUV image、SurfaceTexture 和 CaptureResult 的最近时间戳序列。
- 不建立跨会话的任意常量偏移猜测。
- 当前 session 标记 display pairing unreliable。
- 尝试重开 `COMPATIBLE` 的 PRIVATE+YUV 安全会话。
- 若 YUV 仍不可用，提示设备只支持预览，当前无法提供可靠兼容测光。
- 可保留一个明确标注“实验性估算”的用户手动选项，但默认关闭，且不得写入正式校准。

### 13.5 数据类型

```text
MeteringFrameIdentity(
  operationId,
  sensorTimestamp,
  logicalCameraId,
  actualPhysicalCameraId,
  sessionGeneration,
  sourceQuality
)

sourceQuality =
  RAW_SENSOR_PAIRED |
  YUV_SENSOR_PAIRED |
  DISPLAY_TIMESTAMP_PAIRED |
  UNPAIRED_REJECTED
```

MeteringFrameStat 和最终 MeterReading 应携带 identity/quality，融合时禁止跨 generation、跨
physical ID 或跨 source calibration 混合。

### 13.6 验收测试

- 人工让 AE 每帧改变曝光，验证使用的 image/texture timestamp 与 result 完全对应。
- 在 30 fps 和低光 5～10 fps 下测试。
- 在显示截图前后注入一次 texture update，旧 bitmap 必须被拒绝。
- YUV 超时时全仓库不再调用 `latestResult()` 完成正式测光。
- 物理镜头切换发生在 pair 窗口时，当前操作取消。
- SurfaceTexture 时间戳为 0、倒退或无法匹配时显示可靠性错误。

---

## 14. 全绿、黑白条纹和冻结画面的完整方案

### 14.1 目标

覆盖两类问题：

1. 相机 ID 错误：厂商把 MONO、NIR、depth 或内部辅助传感器暴露给第三方。
2. HAL/流组合错误：session 创建成功但输出 buffer 全绿、周期条纹、冻结或 RAW 后预览损坏。

不能只依赖 Camera2 的 onConfigureFailed/onError，因为异常像素仍可能被 HAL 当作成功帧。

### 14.2 静态过滤

- 拒绝 MONO/NIR。
- 拒绝 depth-only 且没有 BACKWARD_COMPATIBLE 的相机。
- `CFA_RGB` 不进入 Bayer RAW。
- 物理 ID 必须来自逻辑相机 `physicalCameraIds`。
- characteristics 缺失时把 RAW/固定物理能力视为未知，不自动乐观开启。
- 仍允许用户在“实验性相机”列表查看被过滤项，但默认隐藏，并显示原因。

### 14.3 取样来源

优先级：

1. 已配置 YUV 时，每 3～5 帧下采样一次 Y/U/V，最大 96×96。
2. 只有 PRIVATE 时，使用带 timestamp 的 64×64 TextureView 样本。
3. 健康检测不持有全尺寸 bitmap，不阻塞相机线程。

`PreviewHealthAnalyzer` 是纯函数，输入一个小型 RGB 或 YUV 样本，输出 metrics。

### 14.4 指标

```text
mean/p01/p50/p99 luma
darkFraction / clippedFraction
R/G/B mean and channel dominance
chroma valid fraction
row mean sequence / column mean sequence
odd-even row and column contrast
dominant spatial frequency and stability
64-bit perceptual hash
timestamp delta / duplicate timestamp count
```

判据必须使用连续窗口，不能单帧决定：

- 预热丢弃至少 3 帧。
- 默认观察 12 帧。
- 条纹至少 6 帧保持相同方向和周期才进入 FAILED。
- 绿色通道独占只能进入 SUSPECT；真实绿色场景不能仅凭颜色自动失败。
- 黑帧只能进入 SUSPECT；镜头盖住或真实黑暗不能直接判 HAL 错误。
- 冻结需要“时间戳继续变化 + 图像 hash 不变 + 曝光或陀螺仪显示场景应变化”等组合证据。

建议状态机：

```text
WARMING_UP -> OBSERVING -> HEALTHY
                         -> SUSPECT -> CONFIRMING -> FAILED
FAILED -> RECOVERING -> WARMING_UP
                   -> UNUSABLE
```

### 14.5 二次确认，降低误判

发生绿色或黑帧 SUSPECT 时，不立即拉黑镜头：

1. 保持同一 route，重开 SAFE_PREVIEW 推荐尺寸。
2. 暂时移除 RAW/YUV，只保留 PRIVATE。
3. 再观察一组帧。
4. 如果安全会话恢复正常，只拉黑原 profile/size 组合。
5. 如果固定物理路由仍异常，切换对应逻辑路由进行对照。
6. 逻辑正常而物理异常时，只隔离该物理 route。

周期黑白条纹的证据比单色场景强，可以在连续稳定周期达到阈值后直接进入恢复。

### 14.6 确定性恢复顺序

```text
同 route、同 profile 重建一次
-> 同 route，去掉 YUV
-> 同 route，去掉 RAW
-> 同 route，推荐 720p/安全预览尺寸
-> physical route 切 logical route
-> 同朝向主逻辑相机 PREVIEW_ONLY
-> 标记当前设备相机不可用并展示相机选择/诊断
```

每个动作有最大一次或明确计数，不能形成 reopen 循环。

### 14.7 Compatibility store

记录的 key 必须精确：

```text
Build.FINGERPRINT
appCompatibilitySchema
logicalId / physicalId
profile
preview/YUV/RAW sizes
stream use cases
failure signature
```

记录内容：成功次数、最近失败、失败类型、最后健康时间、采用的回退 plan。

失效条件：

- Build.FINGERPRINT 改变。
- app compatibility schema 改变。
- 相机 characteristics signature 改变。
- 用户点击“重新检测相机兼容性”。

不得按 `manufacturer=Samsung/Xiaomi/...` 永久禁用功能。

### 14.8 用户提示

- 自动恢复期间：`检测到相机输出异常，正在切换到安全预览…`
- 回退成功：`当前镜头已使用兼容配置；RAW/区域追踪能力可能受限。`
- 物理转逻辑：`固定镜头输出异常，已交由系统自动选择镜头。`
- 最终失败：给出重试、选择其他相机、导出诊断三个动作。

提示不得泄露完整文件路径、位置或照片内容。

### 14.9 合成单元测试

为纯 analyzer 构造：

- 正常灰阶/彩色噪声帧。
- 真实绿色渐变场景，不能直接 FAILED。
- 全绿色常量输出。
- 奇偶黑白行。
- 2/4/8 像素周期横向和纵向条纹。
- 全黑但曝光不变。
- 全黑且曝光逐步增加、时间戳变化。
- 冻结画面、重复 timestamp、画面重复但 timestamp 变化。
- 前几帧异常后恢复。

### 14.10 真机复现和验收

- 对历史出现问题的相机/固件保留日志和屏幕录像。
- 逐一测试 FULL、RAW_ONLY、COMPATIBLE、PREVIEW_ONLY。
- 测试首次打开、RAW 测量后、暂停恢复、快速换镜、旋转、分屏。
- 异常后最多经过有限恢复动作进入健康预览或明确失败，不无限黑屏。
- 正常绿墙、草地、低光和镜头盖住不应导致永久拉黑。

---

## 15. 设备与厂商验证矩阵

每类至少选择一台主流和一台资源受限设备：

- Pixel/AOSP：逻辑 RAW、标准元数据基线。
- Samsung Galaxy S/A/Fold：DeX、Fold、分屏、active physical 切换。
- Xiaomi/Redmi/POCO：3A 收敛、推荐尺寸、FULL 与 RAW_ONLY。
- Huawei/Honor：辅助/虚拟相机、元数据缺失、公共 Camera2。
- OPPO/OnePlus/realme：前后台恢复、系统升级、黑屏回退。
- vivo/iQOO：硬编码校准迁移、物理 ID 和固件变化。
- Chromebook/Android 平板：仅前摄、自由窗口。
- USB UVC：外接插拔、orientation=0、缺少光圈/曝光字段。

每台设备执行：

- 冷启动 20 次。
- pause/resume 100 次。
- 四个 session profile 分别创建和测量。
- 物理/逻辑镜头切换 50 次。
- RAW 记录 50 次并监控 native/Java 内存。
- 0/90/180/270 度。
- 支持时执行 Fold、DeX、外接显示和分屏。
- 保存记录时注入磁盘满/写失败。
- 删除/重装/系统备份恢复后验证隐私和校准提示。

测光测试材料：18% 灰卡、ColorChecker、均匀光源、规则条纹卡和可调 EV 光源。

---

## 16. 实施顺序和建议提交边界

禁止一次大改全部内容。建议每个阶段单独构建、测试和审查。

1. `docs/privacy: correct disclosures and add versioned notice`
   - 隐私文档、README、应用内提示、备份规则。
2. `camera/lifecycle: introduce lease generation state machine`
   - 先解决跨代资源竞争，不改变测光数学。
3. `camera/physical: route physical streams by capability`
   - 移除 approximate 错误 gate，增加 route resolver。
4. `camera/physical: track active physical camera per frame`
   - characteristics、校准 key 和中途取消。
5. `camera/preview: centralize orientation and ROI geometry`
   - 纯函数和矩阵测试先行。
6. `camera/streams: use recommended session plans`
   - planner 与现有 profile 状态机整合。
7. `metering: reject unsupported raw pixel layouts`
   - 不做解拜尔；CFA_RGB 安全降级。
8. `metering: timestamp-pair displayed preview fallback`
   - 删除正式测光对 latestResult 的依赖。
9. `camera/health: detect and recover invalid preview streams`
   - analyzer 单测先于 monitor 和恢复动作。
10. `records: make parameter saves recoverable transactions`
11. `manifest: correct camera and optional feature declarations`
12. `refactor: shrink CameraController facade`
   - 只做已验证组件的迁移，不在重构提交混入行为变化。

每个提交必须满足：

- `testDebugUnitTest`
- `lintDebug`
- `assembleDebug`
- 受影响真机烟雾测试
- Git diff 不包含用户截图或无关文件

凡是改变 session profile、物理路由、帧配对、资源所有权或恢复顺序的提交，必须同步更新
`docs/CAMERA_PIPELINE.md`；该文件当前记录的“APPROXIMATE 强制逻辑路由”和旧保底行为将
在对应实现落地时一并更正，不能让设计契约继续描述已经废弃的行为。

生命周期、物理相机、显示帧配对和健康恢复完成后，再跑完整 release build 和全设备矩阵。

---

## 17. 完成定义

只有以下条件全部满足，才能把本计划标为完成：

- 隐私声明、应用行为、系统备份提示和商店披露一致。
- 用户保存第一条参数记录前已经明确知悉照片/DNG/位置可能进入系统备份。
- APPROXIMATE 不再错误阻止单个物理镜头。
- activePhysicalId 贯穿 characteristics、校准、ROI、测光和记录。
- Samsung DeX、Fold、USB orientation=0 和 180 度方向通过。
- 主线程不再同步等待相机线程关闭。
- 旧 generation 永远不能关闭新 generation 资源。
- CFA_RGB 不进入 Bayer parser，且没有增加解拜尔。
- 参数记录进程中断后能恢复到完整提交或完整回滚。
- Manifest 不再错误过滤仅前摄/外接摄像头设备。
- 推荐流存在时优先使用，缺失时正确回退。
- 所有正式兼容测光读数都有 frame identity，不再使用 unpaired latestResult。
- 全绿/条纹/冻结可以有限次恢复或明确失败，不形成循环。
- CameraController 不再是上千行大类，新增组件遵守资源所有权和行数边界。

---

## 18. 官方依据

- Android Camera2 管线说明：同一次请求产生结果元数据和输出 buffers：
  https://developer.android.com/reference/android/hardware/camera2/package-summary
- `SENSOR_TIMESTAMP` 会包含在同次 capture 的所有输出 buffer 中：
  https://developer.android.com/reference/android/hardware/camera2/CaptureResult
- SurfaceTexture 当前图像时间戳：
  https://developer.android.com/reference/android/graphics/SurfaceTexture
- YUV 默认 JFIF/Rec.601 full-range，转换后为 sRGB：
  https://developer.android.com/reference/android/hardware/camera2/package-summary
- `CFA_RGB` 是每像素三个 16-bit 值，不是 Bayer：
  https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics
- 逻辑/物理多摄、active physical ID 和物理流组合：
  https://source.android.com/docs/core/camera/multi-camera
- 推荐流配置：
  https://developer.android.com/reference/android/hardware/camera2/params/RecommendedStreamConfigurationMap
- Camera2 折叠屏与 active physical camera：
  https://developer.android.com/media/camera/camera2/foldable-devices
- Camera2 预览旋转和窗口适配：
  https://developer.android.com/media/camera/camera2/camera-preview
- Samsung DeX TextureView 旋转：
  https://developer.samsung.com/samsung-dex/modify-optional.html
- Android Auto Backup 和数据提取规则：
  https://developer.android.com/identity/data/autobackup
- Manifest `camera.any` 与 Play 设备过滤：
  https://developer.android.com/guide/topics/manifest/uses-feature-element
- Camera ITS 场景和元数据/RAW/YUV/多摄验证：
  https://source.android.com/docs/compatibility/cts/camera-its-tests
