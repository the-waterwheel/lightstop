# 光档（lightstop）

[English](README.md) | 中文

光档是一个面向手动曝光与胶片摄影的 Android 反射式测光表。应用通过 Camera2 显示自动曝光预览，优先读取 `RAW_SENSOR`，在 C++ 中完成 Bayer 像素统计，再由 Kotlin 计算 EV100、光圈/快门组合和 Zone System 分区。RAW 不可用时会自动切换到 ISP 预览兼容测光。

项目当前是可在真实设备运行的原型：不拍摄或保存照片，不访问网络，测光、设置和校准数据全部留在应用本地。

## 开发方式

本项目采用 vibe coding 开发流程，在 AI 辅助下完成。AI 参与了方案讨论、功能实现、重构、文档和测试准备。发布候选版本会进行构建、检查并在真实 Android 设备上测试，但贡献者和用户仍应独立审查关键的相机、测光、隐私与安全代码。

## 当前功能

### 相机与预览

- 支持 Android 9（API 28）及以上，使用 Camera2、`TextureView` 和 `ImageReader`。
- 枚举可用的逻辑相机和物理镜头；支持选择、备注、隐藏镜头，并优先选择带 RAW 能力的后置主摄。
- 预览优先选择不超过 1080 级别且接近传感器比例的尺寸；设备允许时请求 60 fps。
- 预览始终保持像素宽高比并中心裁切，不对相机缓冲区做非等比拉伸。
- 电子变焦只改变显示裁切和测光 ROI，不向 Camera2 提交数码变焦或镜头切换请求。
- 支持 135、半格、6×4.5、6×6、6×7、6×9 和通用 65:24 画幅；长边始终沿屏幕水平轴放置。
- 根据传感器方向、屏幕方向、画幅和变焦同步计算 RAW 裁切范围与等效 35 mm 焦距。

### RAW 与兼容测光

- RAW 测光按实际捕获 ISO 融合多帧：ISO ≤ 800 使用 3 帧，ISO > 800 使用 5 帧。
- 完整 RAW 使用最多 2 帧的滚动捕获窗口，让传感器采集与 CPU 分析重叠，同时限制峰值内存。
- `Image` 与 `CaptureResult` 按传感器时间戳配对，分析完成后立即释放完整 RAW。
- C++ 按 Bayer 位置扣除动态/固定黑电平、按白电平归一化，分别统计 R/G1/G2/B 中位数和高光饱和比例。
- Kotlin 使用 Camera2 白平衡增益和颜色矩阵，将四通道统计值转换为线性 sRGB 亮度，无需完整解拜尔。
- EV 以 18% 线性亮度为参考，并叠加设备/镜头基线和用户校准偏移。
- 支持点测光和中央重点测光：点测光读取中心小区域；中央重点按小区域 70%、较大中心区域 30% 融合。
- RAW 不可用或 RAW 输出组合无法建立时，自动读取 3 帧 ISP 预览亮度，并明确标记为兼容测光。

### 曝光仪表

- 黑白机械仪表式界面，只使用少量红色表示基准线、测光按钮和锁止状态。
- 大拨盘默认以 1/6 EV 调整曝光补偿，也可在测光设置中选择 1/3、1/2 或 1 EV 档位，或切换为 ISO 调整。
- 光圈和快门分别支持一档、1/2 档和 1/3 档刻度。
- 可锁定光圈或快门；锁定项只在当前档位网格上移动，另一项保持连续曝光关系。
- 测光、ISO 或曝光补偿变化时，两条曝光标尺平滑联动，不强制把结果舍入成某一组标准刻度。
- 支持中文/English、浅色/深色外观和完整的右手/左手布局。

### Zone System

- Zone 模式支持“按键标点”和“触屏标点”。
- 每个点保存独立 EV，并按 `Zone = V + 点位 EV100 - 当前曝光 EV100` 显示 0–X 分区。
- 多点测光会维持场景点位之间的相对曝光关系；可选择、删除、滑动删除或清空记录。
- 触屏标点会从 ISP 预览取得局部亮度特征，再在几何估算位置附近搜索对应 RAW 绿色通道特征，减小畸变和裁切差异。
- 点位跟踪优先使用低分辨率 YUV 亮度流，无法使用时退回显示预览截图。
- 跟踪结合金字塔 LK 光流、前后向校验、RANSAC 全局仿射运动、局部特征修正、陀螺仪预测和 ORB 重识别。
- 横竖布局按钮只改变应用页面；切换瞬间会按设备固定坐标系反向映射点位，摄像头未移动时不会产生 90° 跳转，切换后的光流方向保持不变。
- RAW 捕获和 ISP 曝光恢复期间会保护点位几何，避免低纹理点因亮度突变发生跳动。
- 点位离开画面后保留虚拟坐标，镜头返回时可尝试重新识别。

### 测光校准

- 测光校准已完整实现，不再是预留入口。
- 可直接输入参考 EV100，也可输入参考相机的光圈/快门/ISO，或输入照射 18% 灰卡的 Lux。
- 校准偏移按“设备厂商 + 型号 + camera ID”独立保存，不影响其他镜头。
- 每个镜头保留最近 3 次历史，可重置当前修正并从历史恢复。
- 用户修正限制在 ±8 EV。
- Vivo V2405A 的 camera `0` 内置 `+1.074 EV` 基线；其他设备和镜头默认基线为 0，应使用灰卡、标准测光表或参考相机分别校准。

### 暗角矫正

- 暗角校准引导用户拍摄亮度均匀的单帧 RAW。
- 以绿色通道生成最长边 64 格的二维亮度图，经宽高斯平滑后，以中央区域作为亮度基准生成增益图。
- 增益图不假设暗角只位于四角，可描述不对称亮度衰减。
- 每个镜头保存独立增益图和最近 3 次历史，支持重置与恢复。
- 暗角增益使用双线性插值，只应用于 Zone 触屏标点的局部 RAW 测光，不改变普通中央测光。
- 无法输出 RAW 的镜头不会执行暗角校准。

## 基本操作

- 转动大拨盘：按测光设置选择的 1/6、1/3、1/2 或 1 EV 档位调整曝光补偿；刻度上方为正、下方为负。
- 点击拨盘旁的 `ISO`：在 ISO 与曝光补偿调整模式之间切换。
- 点击画幅按钮：展开胶片画幅列表并选择画幅。
- 拖动取景区域侧边滑杆：调整电子裁切变焦。
- 拖动曝光锁：向上锁定 `f`，向下锁定 `s`。
- 横向拖动当前锁定的曝光标尺：按当前档位移动锁定参数。
- 点击红色空心测光按钮：读取 RAW 或兼容预览并更新曝光关系。
- 从主界面的 `zone` 把手滑入：进入 Zone System。
- Zone 触屏标点模式下直接点击取景画面；按键标点模式下使用标点按钮。
- 点击齿轮：进入测光、通用、镜头管理和校准设置。

## 代码结构

项目是单 Activity、单 `app` 模块，没有 Compose、Fragment、AndroidX、数据库或网络层。界面由自定义 `ViewGroup`、Canvas 和 `ValueAnimator` 绘制。

包名和 Android application ID 继续保留为 `com.lightmeter.rawmeter`，使现有开发安装可以原位升级并保留校准及设置数据；它只是内部兼容标识，用户可见名称已经改为“光档 / lightstop”。

```text
app/src/main/java/com/lightmeter/rawmeter
├─ MainActivity.kt                  生命周期、权限和模块协调
├─ CameraController.kt              Camera2 会话与采集调度
├─ CameraCatalog.kt                 逻辑/物理镜头发现与选择策略
├─ CameraStreamSelector.kt          预览、YUV 跟踪流和帧率选择
├─ CameraPreviewTransform.kt        TextureView 方向、裁切与变焦矩阵
├─ MeterModels.kt                   状态、曝光刻度和持久化入口
├─ MeteringAnalysis.kt              RAW/ISP 分析、ROI 和 EV 换算
├─ MeteringFusion.kt                3/5 帧稳健融合
├─ RawMeterBridge.kt                JNI 边界
├─ MeterLayout.kt                   页面和相机预览组合
├─ LayoutGeometry.kt                普通仪表布局几何
├─ InstrumentPresentation.kt        普通仪表纯展示决策
├─ InstrumentExposureRenderer.kt    光圈/快门标尺与锁止器绘制
├─ InstrumentView.kt                普通仪表绘制、动画与手势
├─ SettingsCatalog.kt               设置定义
├─ SettingsView.kt                  设置界面
├─ CameraManagementView.kt          镜头选择、备注和隐藏
├─ CalibrationMath.kt               校准参考值换算
├─ CameraCalibrationStore.kt        镜头级测光校准与历史
├─ CalibrationView.kt               测光校准界面
├─ VignettingCalibrationStore.kt    二维暗角增益图与历史
├─ VignettingCalibrationView.kt     暗角校准界面
├─ ZoneSystemModels.kt              Zone 会话、点位和曝光关系
├─ ZoneLayoutGeometry.kt            Zone 页面纯布局几何
├─ ZoneCoordinateMapper.kt          UI/预览/OpenCV 坐标映射
├─ ZoneFrameQuality.kt              YUV 跟踪帧质量检查
├─ ZoneMarkerTracker.kt             可替换的跟踪接口与帧所有权契约
├─ DeferredZoneMarkerTracker.kt     首次进入 Zone 才创建原生跟踪器
├─ OpenCvZoneMarkerTrackerFactory.kt OpenCV 跟踪器工厂与调优参数
├─ ZoneTrackingFrames.kt            Y 平面复制、引用计数与三缓冲池
├─ ZoneCameraFramePipeline.kt       相机线程背压与最新参考帧管理
├─ ZoneOpenCvFramePreprocessor.kt   YUV 方向和跟踪分辨率预处理
├─ ZoneGyroscopeMotion.kt           陀螺仪采样、预测与视觉自校准
└─ ZoneTrackingEngine.kt            光流、RANSAC 和重识别编排

app/src/main/cpp
├─ raw_meter.cpp                    Bayer RAW 中位数统计
└─ CMakeLists.txt                   JNI 动态库构建
```

拆分原则是让 Camera2 生命周期、纯计算、布局几何、坐标变换和 UI 状态相互隔离。核心大类仍负责流程编排，但可独立验证的策略已经移到无 Android 生命周期状态的辅助文件中。

### Zone 跟踪生命周期与内存

- 应用启动和 Normal 模式布局不会创建 `OpenCvZoneMarkerTracker`；只有第一次完整进入 Zone 后才加载 OpenCV、创建原生 Mat、工作线程和陀螺仪监听器。退出 Zone 后停止采样但保留实例，避免重复进入时反复初始化；View 销毁时统一释放。
- Camera2 回调先取得循环缓冲槽并向跟踪器原子预约处理权，预约失败的帧直接关闭，不复制 `width × height` 数据。
- Y 平面使用 3 块固定槽位。分辨率不变时不再逐帧创建大 `ByteArray`；分辨率改变时每个槽位最多重新分配一次。
- OpenCV 工作线程和触屏测光所需的“最新参考帧”通过引用计数共享槽位，最后一个读取者关闭后才能复用，避免异步处理读到下一帧覆盖的数据。
- 离开 Zone 时日志 `Zone YUV summary` 会报告实际复制帧、忙碌丢帧、池耗尽丢帧和累计大缓冲分配数，便于真机核对背压效果。

## UI 适配策略

现有视觉设计不需要改成响应式组件库，也可以继续提高设备适配能力。项目采用或预留以下边界：

- 所有相机画面通过统一宽高比计算和中心裁切显示，不拉伸预览。
- 普通模式和 Zone 模式分别由 `LayoutGeometry`、`ZoneLayoutCalculator` 计算绘制与命中区域，View 不再重复布局公式。
- 横屏、竖屏、左右手和不同画幅均通过同一几何结果驱动绘制、手势和相机坐标映射。
- 小型可见按钮使用至少 48 dp 的隐形触控热区；视觉尺寸和位置不变。
- 固定尺寸使用 dp，主要面板使用屏幕比例，并对关键尺寸使用上下限，避免极端宽高比下无限缩小或放大。

后续适配应继续集中在几何层完成，而不是在每个 `onDraw` 中增加设备判断：

1. 将刘海、挖孔和系统手势区域以 `WindowInsets` 转换为统一安全内容矩形。
2. 为紧凑、标准、宽屏三类最短边宽度建立几何参数集，保持控件的相对结构和视觉语言。
3. 为文本建立统一基线和最大缩放策略，避免系统超大字体与机械刻度互相覆盖。
4. 用固定截图矩阵验证 320/360/411/600 dp、横竖屏、左右手、深浅主题和所有画幅。

以上方式只改变空间约束和命中区域，不改变现有黑白机械仪表设计。

## 构建环境

- JDK 17
- Android SDK 34
- Android Gradle Plugin 8.2.2
- Kotlin 1.9.22
- Gradle 8.7
- NDK 25.1.8937393
- CMake 3.22.1
- OpenCV 4.12.0

```powershell
.\gradlew.bat :app:assembleDebug
```

调试 APK 输出到：

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Release 构建与签名

Release 默认启用 R8 代码压缩和 Android 资源收缩：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease :app:bundleRelease
```

未签名 APK 和 AAB 输出到：

```text
app/build/outputs/apk/release/app-release-unsigned.apk
app/build/outputs/bundle/release/app-release.aab
```

仓库不会保存签名密钥或密码。APK 安装和对外分发前必须使用 Android Studio 的 `Generate Signed Bundle / APK`，或 Android SDK 的 `zipalign` 与 `apksigner` 完成签名。密钥应离线备份，禁止提交 `*.jks`、`*.keystore`、`keystore.properties` 或任何密码。应用商店优先发布 AAB；GitHub Release 应上传签名 APK、SHA-256、`LICENSE`、`NOTICE` 和 `THIRD_PARTY_NOTICES.md`，不要把生成的 release 文件提交到源码仓库。

### 精简 OpenCV

应用固定使用本地版本化 AAR `app/libs/opencv-slim-4.12.0-r1.aar`，不再在应用构建期间从 Maven 动态解析 OpenCV。AAR 由 OpenCV `4.12.0` 官方源码构建，保留应用和官方 Android Java 胶水层所需模块，并包含 `arm64-v8a`、`armeabi-v7a`、`x86_64`。完整的版本矩阵、源码校验值、模块说明、Windows 启动器、构建命令和升级规则见 [精简 OpenCV 构建说明](tools/opencv-slim/README.md)。

当前 AAR 为 `63,882,947` bytes，SHA-256：

```text
0A5C95F697D63C94F87D0B3CBAC8ACB61046D089BCEBCF25BF307A0F796767D0
```

## Release 体积

当前 release 已启用 R8 和资源收缩，并在一个通用 APK 中包含 `arm64-v8a`、`armeabi-v7a`、`x86_64` 三个 ABI。版本 `0.2.0` 的实测 unsigned release APK 为 `76,056,239` bytes（约 72.53 MiB），release AAB 为 `32,959,091` bytes（约 31.43 MiB）。APK 内容大致为：

| 内容 | 大小 |
|---|---:|
| x86_64 原生库 | 46.11 MiB |
| arm64-v8a 原生库 | 15.04 MiB |
| armeabi-v7a 原生库 | 10.39 MiB |
| DEX | 0.71 MiB |
| 第三方许可证资源（APK 压缩后） | 约 0.13 MiB |
| Android 资源 | 约 0.11 MiB |

与原先约 97.5 MiB 的完整 Maven OpenCV 通用 APK 相比，精简 OpenCV 已显著减少原生库占用。原生库仍占绝大多数体积，R8 和资源收缩主要压缩 Java/Kotlin DEX 与 Android 资源。保守目标是：

- 通用三 ABI APK，R8 + 资源收缩：实测约 72.53 MiB。
- 单独 arm64-v8a APK，R8 + 资源收缩：约 17–19 MB。
- 单独 armeabi-v7a APK，R8 + 资源收缩：约 12–14 MB。
- Android App Bundle：当前上传包约 31.43 MiB；商店按 ABI 拆分后，每台设备只接收匹配的原生库。

如需继续明显缩小，应按以下顺序处理：

1. 发布 AAB，或为 ABI 分别生成 APK。
2. 保持 `isMinifyEnabled = true` 和 `isShrinkResources = true`，并验证 OpenCV/JNI 保留规则。
3. 如果不需要在模拟器发布包中保留 x86_64，可用产品风味或 ABI split 将其排除出真机发布变体；不要直接删除调试能力。

体积区间基于当前精简 OpenCV APK 的实际 ZIP 内容估算，正式发布前应以签名 release/AAB 和目标应用商店报告为准。

## 数据与权限

- 只申请相机权限。
- 不申请网络、存储或定位权限。
- 普通设置、镜头选择和测光校准保存在 `SharedPreferences`。
- 暗角增益图保存在应用私有目录的版本化二进制文件中。
- Zone 点位只描述当前场景，退出会话后不持久化。

完整的中英文隐私说明见 [PRIVACY.md](PRIVACY.md)。

## 开源许可证

光档自有源码、文档和原创启动图标采用 [Apache License 2.0](LICENSE)。OpenCV、自编译 AAR 内的第三方组件、Kotlin 运行库和 Android NDK C++ 运行库继续遵循各自许可证，详见 [NOTICE](NOTICE) 与 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。相同的第三方条款会打包到 APK/AAB 的 `assets/licenses/` 中。

自编译 AAR 包含多种宽松开源许可证，以及适用 ABI 下的 Intel IPP 二进制授权，因此不能把整个 AAR 简化宣称为单一 Apache-2.0 组件。`ittnotify` 明确采用其 BSD-3-Clause 双许可证分支，不对光档施加 GPL 开源义务。

## 验证与维护

- 当前源码已通过 `:app:testDebugUnitTest`、`:app:assembleDebug`、开启 R8/资源收缩的 `:app:assembleRelease` 和 `:app:bundleRelease`。
- 当前 16 个自动化单元测试覆盖左右手布局下 Zone/Normal 模式入口拖动方向、OpenCV 延迟创建、三缓冲复用、引用计数、带 stride 的 Y 平面复制、显示方向坐标稳定性、横竖布局点位映射及其往返关系、65:24 通用画幅名称，以及曝光补偿档位换算、左右手拨盘方向和刻度符号；后续应继续为曝光数学、多帧融合、画幅几何和校准文件读写补充纯逻辑测试。Camera2、传感器和设备相关行为仍需真机验证。
- Camera2 和 RAW 行为存在明显厂商差异，正式发布前仍需覆盖不同品牌、RAW/非 RAW、逻辑/物理多摄和横竖屏组合的实机矩阵。
