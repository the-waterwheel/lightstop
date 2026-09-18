# 光档（lightstop）

[English](README.md) | 中文

光档是一个面向手动曝光与胶片摄影的 Android 反射式测光表。应用通过 Camera2 显示自动曝光预览，优先读取 `RAW_SENSOR`，在 C++ 中完成 Bayer 像素统计，再由 Kotlin 计算 EV100、光圈/快门组合和 Zone System 分区。RAW 不可用时会自动改用 ISP 处理后的预览画面测光。

项目当前是可在真实设备运行的高级原型：不访问网络，不包含统计或广告 SDK。用户主动保存参数记录时，应用会在私有存储中保存取景 JPEG、可选 DNG、曝光参数、备注和用户授权的位置；Android 或设备厂商的备份/换机服务可能按系统设置复制这些数据，开发者无法访问这些备份副本。

## 开发方式

本项目采用 vibe coding 开发流程，在 AI 辅助下完成。AI 参与了方案讨论、功能实现、重构、文档和测试准备。发布候选版本会进行构建、检查并在真实 Android 设备上测试，但贡献者和用户仍应独立审查关键的相机、测光、隐私与安全代码。

## 当前功能

### 相机与预览

- 支持 Android 9（API 28）及以上，使用 Camera2、`TextureView` 和 `ImageReader`。
- 枚举可用的逻辑相机和物理镜头；默认优先选择声明 RAW 且提供 `RAW_SENSOR` 尺寸的后置相机（自动 RAW、主摄 RAW、其他 RAW），不支持 RAW 时再使用自动/主摄兼容路径；单色、红外等非普通成像传感器不会列入选择器。
- 预览优先选择不超过 1080 级别的通用 4:3 输出；缺少 4:3 时才接近传感器比例选择。这样自动逻辑相机和固定物理镜头不会因元数据比例不同而改变画面比例。“通用设置 → 取景帧率”默认低帧率并沿用设备声明的最高 30 fps 策略；高帧率只在当前预览/YUV 最小帧时长允许时尝试设备声明的最高 60 fps 范围，失败后依次回退 30 fps、24 fps 和系统默认，不会降级 RAW/YUV/ISP 测光组合。
- 预览始终按相机输出比例中心裁切，不主动对相机缓冲区做非等比拉伸。显示矩阵同时读取传感器安装方向与当前屏幕方向，不再假设所有设备都是常见的手机传感器角度。应用从后台恢复时，会等待 `TextureView` 的窗口尺寸与旋转连续稳定，再重开相机；首个新帧会重新提交缓冲尺寸和显示矩阵，并由低频矩阵校验补偿部分厂商合成器丢失的图层状态。
- Normal 与 Zone 共用一个稳定的原生预览传输矩形，各自取景框只在其上层完成裁切；模式切换不会重设 `SurfaceTexture` 图层尺寸，避免部分厂商合成器沿用旧采样几何后拉伸画面。
- 电子变焦只改变显示裁切和测光 ROI，不向 Camera2 提交数码变焦或镜头切换请求。
- 支持 135、半格、6×4.5、6×6、6×7、6×9、6×12、6×17、65:24、4×5、5×7 和 8×10 画幅；长边始终沿屏幕水平轴放置。
- 根据传感器方向、屏幕方向、画幅和变焦同步计算 RAW 裁切范围；焦距提示按所选胶片画幅的代表性成像对角线换算，不再把大画幅误标为 135 等效焦距。
- 画幅选择器会自动换行；增加画幅后不会横向压缩按钮或拉伸取景画面。

### RAW 与预览流测光

- 设置按每个摄像头的工作流矩阵提供三档：**高精度（推荐）**按精度和流压力依次尝试 RAW 组合，全部失败后自动转入稳定 YUV、再转入兼容 ISP；**稳定模式**使用预览 + YUV，不使用 RAW；**兼容模式**只使用屏幕显示的 ISP 预览。旧版本保存的“兼容”设置会迁移到当前兼容模式，保持升级前行为。LEGACY 或 RAW CFA 布局不受支持的设备按不支持 RAW 处理；固定物理镜头不因逻辑相机声明 APPROXIMATE 同步而被禁止，API 29+ 自动逻辑路由还会逐帧记录实际 active physical ID。
- “测光组合选择”可保持“系统设置”，由程序自动完成矩阵初筛、真实会话配置和健康检测；也可选择“手动选择”，按精度从高到低用真实工作流逐个显示预览，由用户确认是否存在闪烁、卡顿、黑屏、绿屏或条纹。人工确认结果只对同一相机路由和同一系统版本有效。
- 自动 RAW 工作流只有在收到真实 `RAW_SENSOR` 图像、与捕获结果严格按时间戳配对，并确认尺寸、缓冲布局、曝光元数据、Bayer 排列及黑白电平可用后才通过。该检查为常数开销，不扫描像素，也不会因为真实暗场或无关高光饱和而误判失败。
- Android 强制流组合表和输出数量上限只用于保守初筛；未被表格保证的组合仍会进入真实 Camera2 配置。部分 vivo/MediaTek HAL 会在查询时返回不支持，但随后能成功创建同一会话，因此查询的否定结果只作提示，真实配置回调才是最终判据。

- RAW 测光按实际捕获 ISO 调整采样量：ISO 低于 500 使用 1 张，ISO 500–1199 使用 2 张，ISO 1200 及以上使用 3 张。这样优先减少等待和手持晃动带来的误差。
- 同一时刻只允许 1 张全尺寸 RAW 在途；当前图像关闭后才提交下一张，以限制原生相机缓冲峰值。
- 正式 RAW、YUV、DNG、色温和暗角操作只接受 `Image` 与 `CaptureResult` 完全相同的传感器时间戳；显示预览保底会在截图前后核验同一个 `SurfaceTexture` 时间戳，无法严格配对时拒绝该样本，绝不使用过期元数据。
- C++ 按 Bayer 位置扣除动态/固定黑电平、按白电平归一化，分别统计 R/G1/G2/B 中位数和高光饱和比例。
- Kotlin 使用 Camera2 白平衡增益和颜色矩阵，将四通道统计值转换为线性 sRGB 亮度，无需完整解拜尔。
- EV 以 18% 线性亮度为参考，并叠加设备/镜头基线和用户校准偏移。
- 支持点测光和中央重点测光：点测光读取中心小区域；中央重点按小区域 70%、较大中心区域 30% 融合。
- 预览流测光不做多帧降噪：优先读取 1 个 ISP 处理后的有效帧；最多尝试 3 个 YUV 帧且总等待不超过 250 ms，随后尝试一帧严格时间戳配对的显示预览。
- 高精度 Zone 跟踪常驻预览 + YUV，测光瞬间切到仅 RAW 会话采集 1–3 帧，随后恢复预览 + YUV。对于无法常驻预览 + RAW 的受限 HAL，另一高精度组合也可在普通测光时使用同样的瞬时仅 RAW 窗口。兼容的 RAW/YUV reader 会以未接入活动会话的休眠资源形式复用，减少重复分配；活动会话仍只连接当前档位的 surface，不改变厂商 HAL 隔离与降级边界。
- 手动曝光预览优先保持流畅快门：通常不慢于 1/30s，低光最多放慢到 1/15s，并优先提高 ISO。正式测光会先恢复中性 AE，绝不把曝光预览帧误当作正式读数。
- 相机工作流搜索与恢复会从 RAW 组合逐步降低为稳定 YUV、兼容 ISP；物理镜头仍不可用时可退回逻辑主摄。
- 预览健康检测会有限识别持续的周期性绿色/黑白条纹；安全预览恢复后还必须连续通过 3 次取样才被接受。真实绿色或黑暗场景只会被标为可疑，不会自动判定相机故障。
- 前摄取景作水平镜像；RAW 触点测光和历史 RAW 网格查询会先撤销该镜像，确保画面所见与传感器取样位置一致。
- “闪光指数”小工具按线性光量叠加环境光与闪光，可分别设置闪光指数数值、该指数对应的参考 ISO（GN100/GN200 等）、本次测光 ISO、分数功率和损失档数。未应用闪光修正时，每次进入工具会从主转盘取得默认 ISO；进入后仍可独立修改，不反向改变主转盘。
- 闪光距离使用带刻度线的快速径向拨盘，只显示 `m`；手动距离与 Auto 位于同一刻度序列。Auto 会同时显示当前有效距离，闪光距离拨盘与测光角度拨盘互斥展开，避免覆盖和误触。
- 自动距离采用保守策略：仅接受已确认物理镜头的 `CALIBRATED` / `APPROXIMATE` Camera2 对焦元数据，要求 AF 与镜头稳定，在屈光度空间对 5–10 个样本做中位数和 MAD 滤波，并显示来源与质量。每次测光都会重新启动一次 AF 测距；结果过期或不可用时绝不静默参与闪光补偿。

### 曝光仪表

- 黑白机械仪表式界面，只使用少量红色表示基准线、测光按钮和锁止状态。
- 测光角度与闪光距离在测光按钮上方紧凑平行排列，参数记录滑块在 Normal 与 Zone 中都固定到按钮下方；任一拨盘展开时会临时置顶，避免与记录控件遮挡。
- 大拨盘默认以 1/6 EV 调整曝光补偿，也可在测光设置中选择 1/3、1/2 或 1 EV 档位，或切换为 ISO 调整。
- 光圈和快门分别支持一档、1/2 档和 1/3 档刻度。
- 可锁定光圈或快门；锁定项只在当前档位网格上移动，另一项保持连续曝光关系。
- 测光、ISO 或曝光补偿变化时，两条曝光标尺平滑联动，不强制把结果舍入成某一组标准刻度。
- 倒易率计算器通常把最终校正时间限制在 24 小时内；若厂商精确数据明确延伸更久，则只允许到该数据截止。超过 1 小时的结果以 `HH:MM:SS` 显示，并在各列下标注 `h`、`min`、`s`。
- 离散倒易率节点先转换到 `log2(测光时间) → log2(校正时间/测光时间)` 的补偿档位空间，再用保形三次曲线拟合；曲线连续通过原始节点，补偿不会在节点间回落或过冲。可为任意内置胶片编辑或补录离散点、幂函数、固定 EV、有限无需补偿范围，也可新增自定义胶片；用户数据独立保存，并可随时重置为应用内置设定。
- 支持中文/English、浅色/深色外观和完整的右手/左手布局。
- 通用设置中提供紧凑的“关于”入口，包含应用版本、AI 辅助开发说明、OpenCV 归属说明与中英文测光结果提示。
- 高取景帧率可能增加耗电、发热和 Zone YUV 跟踪负载，并在暗光下缩短自动曝光时间；低帧率仍是兼容性优先的默认项。两者均不改变测光算法或校准数据。
- “开源许可证”作为“关于”的下级页面置于全部说明文字之后，可在应用内逐项阅读 APK 中随附的完整第三方许可证与版权声明，无需解压 APK。

### Zone System

- Zone 模式支持“按键与触屏”和“仅按键”标点方式；两种方式都保留标点按钮，“按键与触屏”额外支持直接点击取景画面标点。
- 每个点保存独立 EV，并按 `Zone = V + 点位 EV100 - 当前曝光 EV100` 显示 0–X 分区。
- 多点测光会维持场景点位之间的相对曝光关系；预览画面上的标点圆点对点击无反应，删除使用记录列表左滑或清空操作。
- 画面标点在圆形外增加四边中段留空的分段方框；Zone 标点按钮在可用空间内放大。经 Zone 打开的镜头选择与输出比例管理会保留 Zone 页面和当前镜头，不先切回 Normal 或自动主摄。
- 长按重新测光可更新已有可见点；支持的 RAW 工作流只采集一组共享 RAW，再对每个点独立完成预览到 RAW 的特征配准。已经离开有效画面的点会计入“未更新”，不会覆盖旧结果。
- 触屏标点会从 ISP 预览取得局部亮度特征，再在几何估算位置附近搜索对应 RAW 绿色通道特征，减小畸变和裁切差异。
- 点位跟踪优先使用低分辨率 YUV 亮度流，无法使用时退回显示预览截图。
- 跟踪结合金字塔 LK 光流、前后向校验、RANSAC 全局仿射运动、局部特征修正、陀螺仪预测、ORB 重识别和针对单手抖动的快速运动恢复。
- 横竖布局按钮只改变应用页面；切换瞬间会按设备固定坐标系反向映射点位，摄像头未移动时不会产生 90° 跳转，切换后的光流方向保持不变。
- 隔离 RAW 捕获期间，静止预览与可见标点会一起冻结；后台只用陀螺仪推进隐藏场景位置，直到第一帧真实预览/YUV 恢复后才重新显示视觉跟踪结果。
- 点位离开画面后保留特征描述子，并用相机射线与陀螺仪角度传播。预计返回时先在预测位置附近搜索，必要时周期性全画面重识别；未经视觉确认的丢失点不会显示为可靠结果。
- 历史记录只有在保存了紧凑 RAW 测光网格时才允许重新进入 Zone 交互；没有网格时旧标点仍可查看，但为只读。

### 测光校准

- 测光校准已完整实现，不再是预留入口。
- 可直接输入参考 EV100，也可输入参考相机的光圈/快门/ISO，或输入照射 18% 灰卡的 Lux。
- 校准偏移按“设备厂商 + 型号 + camera ID”独立保存，不影响其他镜头。
- 每个镜头保留最近 3 次历史，可重置当前修正并从历史恢复。
- 用户修正限制在 ±8 EV。
- 校准页面分别显示“RAW 传感器”“YUV 兼容流”“ISP 显示预览”，并将旧版“共享预览修正”明确标为兼容回退值。一次校准不受当前测光模式限制，会完成设备实际支持的全部来源：三者都存在时依次 RAW → YUV → ISP，否则校准可用子集；各步骤顺序执行，绝不并发采集，并分别使用最小安全 Camera2 会话，结束后恢复用户原本的会话。一次校准会固定用户选择的相机路由和修正存储身份；逻辑相机在会话重开时短暂不报告实际物理镜头不会中止校准，但若先后明确报告两个不同的物理镜头仍会安全中止。不支持的来源不会显示为虚假的校准成功；尚未重新校准的 YUV/ISP 可暂时使用旧版共享值，避免升级后读数突变。
- 曝光预览校准是按设备、按镜头保存的独立修正，只调整取景器对 0 EV 的视觉呈现，不改变也不叠加 RAW/YUV/ISP 正式测光校准。任一校准变化后会清除旧读数，避免用新修正重复处理旧结果。
- 切换到兼容模式时会显示中英文提示，说明该模式不使用 RAW；可选择“确定”或用“不再提示”永久关闭。

### 暗角矫正

- 暗角校准引导用户拍摄亮度均匀的单帧 RAW。
- 以绿色通道生成最长边 64 格的二维亮度图，经宽高斯平滑后，以中央区域作为亮度基准生成增益图。
- 增益图不假设暗角只位于四角，可描述不对称亮度衰减。
- 每个镜头保存独立增益图和最近 3 次历史，支持重置与恢复。
- 矫正页使用不裁切的完整相机预览；镜头或横竖方向变化后，操作按钮和历史记录会重新布局并保持可见。
- 暗角增益使用双线性插值，只应用于 Zone 触屏标点的局部 RAW 测光，不改变普通中央测光。
- 无法输出 RAW 的镜头不会执行暗角校准。

## 基本操作

- 转动大拨盘：按测光设置选择的 1/6、1/3、1/2 或 1 EV 档位调整曝光补偿；刻度上方为正、下方为负。
- 点击拨盘旁的 `ISO`：在 ISO 与曝光补偿调整模式之间切换。
- 点击画幅按钮：展开胶片画幅列表并选择画幅。
- 拖动取景区域侧边滑杆：调整电子裁切变焦。
- 拖动曝光锁：向上锁定 `f`，向下锁定 `s`。
- 横向拖动当前锁定的曝光标尺：按当前档位移动锁定参数。
- 点击红色空心测光按钮：读取 RAW 或处理后的预览并更新曝光关系。
- 从主界面的 `zone` 把手滑入：进入 Zone System。
- Zone“按键与触屏”模式下可直接点击取景画面，也可使用标点按钮；“仅按键”模式下使用标点按钮。
- 点击齿轮：进入测光、通用、镜头管理和校准设置。
- “更多设置”是“测光设置”底部的子页面入口；预览异常检测、信息栏、手动安全预览和逐镜头传感器输出比例均集中在该页面。
- 在“测光组合选择”中保留“系统设置”即可自动完成矩阵与健康检测；选择“手动选择”可自行检查候选组合。若提示尚未完成初筛，请等待相机就绪后再次选择“手动选择”。
- 点击“小工具”按钮并选择“景深计算”：用当前画幅、取景视角和测光光圈作为默认值；可选择或自定义画幅与弥散圆，并用两个拨盘调整光圈和对焦距离。
- 当前小工具入口包括景深计算、宽容度、参数记录、倒易率计算、闪光指数和色温估算。六个入口使用固定白色位图图标，浅色/深色模式保持一致，说明文字置于图标下方。
- 参数记录会冻结并保存闪光指数原始数值、GN 参考 ISO、测光 ISO、功率、损失、手动/自动距离、距离来源与质量；旧记录仍按 ISO 100 归一值兼容读取。

## 代码结构

项目是单 Activity、单 `app` 模块，没有 Compose、Fragment、AndroidX、数据库或网络层。界面由自定义 `ViewGroup`、Canvas 和 `ValueAnimator` 绘制。

包名和 Android application ID 继续保留为 `com.lightmeter.rawmeter`，使现有开发安装可以原位升级并保留校准及设置数据；它只是内部兼容标识，用户可见名称已经改为“光档 / lightstop”。

```text
app/src/main/java/com/lightmeter/rawmeter
├─ MainActivity.kt                  生命周期、权限和模块协调
├─ ForegroundCameraStartCoordinator.kt 前台窗口几何稳定采样
├─ CameraController.kt              生命周期与相机操作门面
├─ CameraSessionCoordinator.kt      Camera2 资源、会话与开关顺序
├─ CameraOpenConfigurationResolver.kt 路由与流配置解析
├─ CameraPreviewRequestCoordinator.kt 重复预览请求构建
├─ CameraRuntimeMetadataCoordinator.kt 活动物理镜头元数据
├─ CameraDistanceCaptureCoordinator.kt 对焦距离采集生命周期
├─ CameraPreviewHealthCoordinator.kt 有界预览健康与恢复
├─ RawRecordCaptureCoordinator.kt   参数记录 RAW/DNG 采集
├─ VignettingCalibrationCaptureCoordinator.kt 暗角校准 RAW 采集
├─ CameraCombinationWorkflowProbe.kt 多阶段真实工作流验证
├─ RawProbeFrameHealth.kt           常数开销 RAW 探测判定
├─ RawLightMeter.kt                 有界 RAW 采集与测光
├─ CompatibleLightMeter.kt          YUV/显示预览测光
├─ TimestampedResultPairer.kt       图像/结果配对与所有权
├─ CameraRecoveryPolicy.kt          会话档位、错误分类和恢复决策
├─ CameraRecoveryStateMachine.kt    重试历史与路线降级状态
├─ CameraCombinationPolicy.kt       工作流分类、排序与降级
├─ CameraCombinationMatrix.kt       强制矩阵与输出数量初筛
├─ CameraCombinationSelectionStore.kt 镜头/系统版本人工结果缓存
├─ CompatibleMeteringPolicy.kt      单帧预览流测光的尝试与超时上限
├─ CameraCatalog.kt                 逻辑/物理镜头发现与选择策略
├─ CameraStreamSelector.kt          预览、YUV 跟踪流和帧率选择
├─ CameraPreviewTransform.kt        TextureView 方向、裁切与变焦矩阵
├─ PreviewSurfaceCoordinator.kt     预览几何恢复与低频校验
├─ DistanceModels.kt                 带来源与时效性的统一距离状态
├─ DistanceCoordinator.kt            测距 Provider 状态转发
├─ Camera2FocusDistanceProvider.kt   Camera2 AF 稳健测距采样
├─ MeterModels.kt                   状态、曝光刻度和持久化入口
├─ MeteringAnalysis.kt              RAW/ISP 采样、ROI 和 EV 换算
├─ RawPreviewRegistration.kt        预览标点到 RAW 坐标配准
├─ MeteringFusion.kt                RAW 多帧稳健融合
├─ RawMeterBridge.kt                JNI 边界
├─ MeterLayout.kt                   页面和相机预览组合
├─ LayoutGeometry.kt                普通仪表布局几何
├─ InstrumentPresentation.kt        普通仪表纯展示决策
├─ InstrumentExposureRenderer.kt    光圈/快门标尺与锁止器绘制
├─ InstrumentView.kt                普通仪表绘制、动画与手势
├─ SettingsCatalog.kt               设置定义
├─ SettingsView.kt                  设置界面
├─ CameraCombinationSelectionView.kt 全屏人工预览检查
├─ CameraManagementView.kt          镜头选择、备注和隐藏
├─ CalibrationMath.kt               校准参考值换算
├─ MeteringCalibrationPlan.kt       全来源校准计划
├─ MeteringCalibrationCoordinator.kt 顺序校准状态机
├─ CameraCalibrationStore.kt        镜头级测光校准与历史
├─ ExposurePreviewCalibrationCoordinator.kt 曝光预览校准状态
├─ ExposurePreviewCalibrationPanel.kt 曝光预览校准界面
├─ ExposurePreviewStateCoordinator.kt 曝光预览状态隔离
├─ MeteringPreviewBaselineCoordinator.kt 中性测光预览基线
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

相机会话档位、降级顺序、图像资源所有权、厂商差异边界以及组件职责，见[相机管线与设备兼容说明](docs/CAMERA_PIPELINE.md)。

拆分原则是让 Camera2 生命周期、测光实现、纯计算、布局几何、坐标变换和 UI 状态相互隔离。`CameraController` 保留公开接口和跨组件编排，具体资源与测光状态由各自组件负责。

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
- Android SDK 36.1（target API 36；min API 28）
- Android Gradle Plugin 8.13.2
- Kotlin 2.1.20
- Gradle 8.13
- NDK 27.0.12077973
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

Windows 命令行发布时，先构建未签名 APK，再对齐并签名。下面故意不传密码参数，让 `apksigner` 交互式询问，避免密码进入 PowerShell 历史：

```powershell
$buildTools = (Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" -Directory |
  Sort-Object Name -Descending | Select-Object -First 1).FullName
New-Item -ItemType Directory -Force dist | Out-Null
& "$buildTools\zipalign.exe" -f -p 4 `
  app\build\outputs\apk\release\app-release-unsigned.apk `
  dist\lightstop-v0.4.0-aligned.apk
& "$buildTools\apksigner.bat" sign `
  --ks C:\secure\lightstop-release.jks `
  --ks-key-alias lightstop `
  --out dist\lightstop-v0.4.0-universal.apk `
  dist\lightstop-v0.4.0-aligned.apk
& "$buildTools\apksigner.bat" verify --verbose --print-certs `
  dist\lightstop-v0.4.0-universal.apk
Get-FileHash dist\lightstop-v0.4.0-universal.apk -Algorithm SHA256 |
  Format-List Algorithm, Hash, Path
```

第一次公开发布后绝对不能丢失或更换签名密钥，后续 APK 更新必须使用同一个密钥。请把密钥、别名与密码分别保存在两个安全位置。0.3.0/0.3.1 的发布证书 SHA-256 指纹为 `94d693638fbb0a55bc1c11e81fa5916d67572ee8e298dd4b08b315787a7f7a7a`，每次更新都应核对一致。发布前先提交源码，建立带说明的标签并推送提交与标签：

```powershell
git status
git tag -a v0.4.0 -m "lightstop 0.4.0"
git push origin HEAD:main
git push origin v0.4.0
```

然后在 GitHub 从 `v0.4.0` 创建 Release，保留 GitHub 自动生成的源码压缩包，并上传签名通用 APK、写有 SHA-256 的文本文件、`LICENSE`、`NOTICE` 与 `THIRD_PARTY_NOTICES.md`。上传后再下载一次 APK 并复验签名和哈希；GitHub Release 是可追溯的分发记录，签名 APK 与妥善保护的密钥则保证后续更新连续性。

### 精简 OpenCV

应用固定使用本地版本化 AAR `app/libs/opencv-slim-4.12.0-r2.aar`，不再在应用构建期间从 Maven 动态解析 OpenCV。AAR 由 OpenCV `4.12.0` 官方源码构建，保留应用和官方 Android Java 胶水层所需模块，并包含 `arm64-v8a`、`armeabi-v7a`、`x86_64`；本修订关闭了应用不会调用的 IPP、TBB、KleidiCV、ITT 以及 OpenJPEG、TIFF、WebP、OpenEXR、AVIF、Jasper 后端。完整的版本矩阵、源码校验值、模块说明、Windows 启动器、构建命令和升级规则见 [精简 OpenCV 构建说明](tools/opencv-slim/README.md)。

当前 AAR 为 `33,514,507` bytes，SHA-256：

```text
321C84621FE818E35CC7B6401953039BC784E4FE4CFB9D35690B4DBEDF4D57CD
```

## Release 体积

当前 release 已启用 R8 和资源收缩，并在一个通用 APK 中包含 `arm64-v8a`、`armeabi-v7a`、`x86_64` 三个 ABI。0.4.0 使用 r2 精简 OpenCV 后，实测 unsigned release 通用 APK 为 `44,379,873` bytes（约 42.32 MiB），release AAB 为 `20,996,140` bytes（约 20.02 MiB）。相较 0.3.0 的增长主要来自扩展后的跟踪、校准与相机协调代码。APK 内容按 ZIP 压缩后大小大致为：

| 内容 | 大小 |
|---|---:|
| x86_64 原生库（含 libc++） | 17.12 MiB |
| arm64-v8a 原生库（含 libc++） | 12.81 MiB |
| armeabi-v7a 原生库（含 libc++） | 8.71 MiB |
| DEX | 1.07 MiB |
| 第三方许可证资源 | 约 0.22 MiB |
| Android 资源 | 约 0.11 MiB |

与 r1 精简构建（通用 APK 约 72.53 MiB）及原先约 97.5 MiB 的完整 Maven OpenCV 通用 APK 相比，r2 已显著减少原生库占用。原生库仍占绝大多数体积，R8 和资源收缩主要压缩 Java/Kotlin DEX 与 Android 资源。当前水平与可进一步达到的目标：

- 通用三 ABI APK，R8 + 资源收缩：实测约 40.15 MiB。
- 单独 arm64-v8a APK：约 14–15 MiB。
- 单独 armeabi-v7a APK：约 11–12 MiB。
- 单独 x86_64 APK：约 16–17 MiB。
- Android App Bundle：当前上传包约 17.80 MiB；商店按 ABI 拆分后，每台设备只接收匹配的原生库，下载量约为上述单 ABI APK 大小。

ABI 是 CPU 架构标识而不是可安装文件；可安装的只有 APK。通用 APK 任何设备都能装但体积最大，分 ABI APK 体积约为三分之一但必须选对架构，AAB 不能直接安装、仅供商店按设备生成对应的分发 APK。

如需继续明显缩小，应按以下顺序处理：

1. 发布 AAB，或为 ABI 分别生成 APK。
2. 保持 `isMinifyEnabled = true` 和 `isShrinkResources = true`，并验证 OpenCV/JNI 保留规则。
3. 可选：在 OpenCV 构建配置中启用 `ENABLE_LTO=ON` 或改用静态 libc++，再进一步压缩 10% 左右。
4. 如果不需要在模拟器发布包中保留 x86_64，可用产品风味或 ABI split 将其排除出真机发布变体；不要直接删除调试能力。

体积区间基于当前精简 OpenCV APK 的实际 ZIP 内容估算，正式发布前应以签名 release/AAB 和目标应用商店报告为准。

## 数据与权限

- 申请相机权限；位置权限可选，仅在用户为参数记录启用位置后使用。
- 不申请网络或共享存储权限。
- 普通设置、镜头选择和测光校准保存在 `SharedPreferences`。
- 暗角增益图保存在应用私有目录的版本化二进制文件中。
- 用户主动保存参数记录时会保存取景 JPEG、可选 DNG、曝光参数、备注和可选位置；系统或设备厂商的云备份/换机服务可能按用户设置复制这些数据，较大 DNG 不保证会被备份。
- Zone 点位只描述当前场景，退出会话后不持久化。

完整的中英文隐私说明见 [PRIVACY.md](PRIVACY.md)。

对于不可重复的重要拍摄、商业制作或其他对曝光准确性要求较高的场景，请使用经过校准的专业测光表，并通过试拍、包围曝光或其他独立方式复核结果。应用内的完整测光结果说明位于“通用设置 → 关于”。

## 开源许可证

光档自有源码、文档和原创启动图标采用 [Apache License 2.0](LICENSE)。OpenCV、自编译 AAR 内的第三方组件、Kotlin 运行库和 Android NDK C++ 运行库继续遵循各自许可证，详见 [NOTICE](NOTICE) 与 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。相同的第三方条款会打包到 APK/AAB 的 `assets/licenses/` 中。

这些声明也可直接从应用内“通用设置 → 关于 → 开源许可证”逐项查看。

自编译 AAR 包含多种宽松开源许可证，以及适用 ABI 下的 Intel IPP 二进制授权，因此不能把整个 AAR 简化宣称为单一 Apache-2.0 组件。`ittnotify` 明确采用其 BSD-3-Clause 双许可证分支，不对光档施加 GPL 开源义务。

## 验证与维护

- 当前源码已通过 `:app:testDebugUnitTest`、`:app:assembleDebug`、开启 R8/资源收缩的 `:app:assembleRelease` 和 `:app:bundleRelease`。
- 自动化单元测试覆盖相机会话恢复、严格时间戳配对、RAW CFA 可用性、预览健康分析、参数记录恢复、前后摄旋转与镜像、单帧兼容测光限制、左右手布局下 Zone/Normal 模式入口拖动方向、OpenCV 延迟创建、三缓冲复用、引用计数、带 stride 的 Y 平面复制、显示方向坐标稳定性、横竖布局点位映射及其往返关系、65:24 通用画幅名称、曝光补偿档位换算、左右手拨盘方向和刻度符号，以及“关于”入口的中英文设置目录约束；Camera2、传感器和设备相关行为仍需真机验证。
- Camera2 和 RAW 行为存在明显厂商差异，正式发布前仍需覆盖不同品牌、RAW/非 RAW、逻辑/物理多摄和横竖屏组合的实机矩阵。
- 当前候选版本已在 Android 16 的 vivo V2405A 上验证：成功枚举自动逻辑相机、超广角、主摄、长焦与前摄，瞬时仅 RAW 工作流完成后能恢复常驻 YUV 会话。这只是一个兼容性数据点，不能替代更多厂商和 API 版本的实机矩阵。
