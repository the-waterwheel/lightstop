# CameraController 职责边界与 RAW 成本说明

2026-10-12。本轮为职责拆分，保留对 MainActivity 的入口、测光数学、帧数策略、相机组合顺序及 RAW/YUV 隔离恢复行为。

## 维护边界

`CameraController` 是 Camera2 生命周期与跨组件调度入口。新增功能不以“已经有相机引用”为理由直接放进控制器。优先明确功能状态、资源与取消的唯一所有者，再增加薄委托入口。

| 职责 | 所有者 | 控制器保留的连接 |
| --- | --- | --- |
| 设备、Session、Surface、ImageReader | CameraSessionCoordinator | 能力/会话选择、配置完成/失败调度 |
| RAW 请求、配对、统计、融合 | RawLightMeter、MeteringAnalysis、MeteringFusion | 操作互斥、会话恢复、结果转发 |
| 临时 RAW 事务、参考冻结、物理镜头证据、提前交付、恢复与失败分流 | CameraZoneRawCoordinator | 只读上下文与明确的 CameraZoneRawHost 会话操作；不共享可写事务 |
| YUV/显示预览测光 | CompatibleLightMeter | 可用性、目标选择、完成后恢复 |
| 负片锁定、片基、冻结帧、参考失效、渲染器生命周期 | CameraFilmPreviewCoordinator | 明确的只读 CameraFilmPreviewHost 与相机操作接口 |
| EGL、纹理、Shader、GL 回读 | FilmNegativeRenderer / CameraFilmPreviewRenderer | 不把 GL 实现放回控制器 |
| 主线程小尺寸预览参考图采样 | CameraPreviewReferenceSampler | 获取参考结果，不持有 Bitmap |
| 状态与恢复提示文案 | CameraStatusFormatter | 提供当前状态，不在文案类中决定降级 |
| 曝光请求、曝光校准、距离、健康检测 | 各自现有 Coordinator | 共享相机会话的有序调度 |

在控制器中新增状态或长流程前，说明它为何属于 Camera2 生命周期或跨组件事务。组件不得持有整个 `CameraController`，不得通过扩大它的可见性来访问所有内部字段。纯数学、图像分析、文案与文件持久化使用独立类。

临时 RAW 事务现由 `CameraZoneRawCoordinator` 完整持有；控制器只路由会话/测光事件并提供会话操作。`ZoneRawTransactionCoordinator` 和 `ZoneRawSessionState` 继续管理事务身份及合法转换，控制器不读取、修改内部事务字段。

手动组合验证与确认、组合选择/路由、独立校准会话目前仍在控制器调度。后续拆分要以完整流程为单位包含证据、超时、取消和失败恢复，不能仅移动几个方法后共享可写字段。Camera2 generation 检查仍在会话入口；本轮没有重新设计上游 RAW 请求回调的归属校验。

## 必须保持的异步与资源契约

- Camera2 与帧证据由 camera handler 管理；TextureView 参考图读取与 UI 回调由 main handler 管理；GL 由 renderer 的线程管理。
- `onCameraClosed()` 在关闭 Camera2 资源前失效负片锁定/采样证据。关闭 producer 后才能释放 renderer；保留 TextureView 的 outputTexture。
- GL 释放可能晚于 Activity stop。完成回调必须重新获取当前 lifecycle 和 handler；旧 renderer 的 ready/failure 回调不得影响替代 renderer。
- 批量参考点只读取一张 Bitmap；在采样边界的 finally 中回收，保持所有点描述同一显示帧。
- RAW Image 与 CaptureResult 仍精确配对，RAW/YUV 隔离与恢复顺序不变。结构拆分不隐含允许并发 RAW 或常驻 FULL。
- 临时 RAW 读数可以先交付，但操作锁必须保持到预览恢复；恢复失败解除锁并启动安全预览恢复。确认过的 RAW 组合和批量测光失败不能自动冒充兼容测光成功，物理镜头改变的 RAW 读数不能应用。
- RAW 参考帧仅在 camera handler 上同步借用；其生命周期归 ZoneCameraFramePipeline。事务保留采样结果，不保留或关闭借用帧。
- 修改相机行为仍需手机回归；JVM/Robolectric 测试不替代真实 HAL、EGL 或传感器验证。

## RAW 质量判断与多帧采集的成本

比较的是新增判断成本，而不是已经必须做的 ROI 测光计算。设额外一帧从请求到关闭的成本为 `T`，复用已有统计的质量判断成本为 `Q`，首次判断后仍需追加一帧的概率为 `p`。固定两帧成本约为 `2T`，单帧优先的期望成本约为 `T + Q + pT`。只有 `Q < (1-p)T` 时后者更快。会话切换等共同成本应另外统计，不能把节省一帧直接解释为总延迟减半。

当前质量规则只处理少量逐帧亮度、样本数和饱和通道数，不重新扫描 RAW。它很轻，但帧间亮度 spread 至少需要两帧；一帧的 spread 为 null，而不是已证明没有随机噪声。因此本轮保留 ISO 驱动的基础帧数，不拿这个模型直接实现单帧提前结束。

后续若加入单帧判据，应优先复用已有 ROI 采样和可靠传感器噪声元数据，并用均匀灰卡/真实场景的 EV 重复性验证。不能把场景纹理造成的空间变化直接当成随机噪声，也不能为判断质量额外做整帧去噪、特征匹配或图像复制。基准分别记录质量计算、单帧采集/分析、会话切换与点击到读数的 p50/p95。

## RAW 点匹配中的临时对象

现有点匹配搜索最多 169 个粗候选和 49 个细候选，每个候选采样 11×11 个坐标。循环为中间坐标构造 Pair、RawMeterPoint，并为候选创建 FloatArray。可以预计算固定坐标变换、复用候选缓冲与参考均值/能量，减少分配与 GC 压力。

这不意味着减少候选、修改匹配阈值或降低采样质量；ART 也可能消除部分分配，实际收益需要设备测量。本轮只拆分控制器，未修改这套匹配算法。

## 本轮验证

- CameraController：4185 → 3742 → 3501 行；新增负片流程、参考采样、状态文案、临时 RAW 事务四个职责所有者，保留对上层的入口。第二轮净移出 241 行，包含控制器中的显式 Host 连接。
- 615 项单元/Robolectric 测试通过，零失败、错误或跳过。两轮新增 27 项测试：原有 13 项覆盖渲染器替换/释放与迟到回调、参考采样线程/Bitmap 回收、状态提示；新 14 项覆盖 RAW 提前交付/恢复锁、批量输出、动态恢复策略、配置/恢复失败、确认组合与批量失败限制、物理镜头切换、取消、参考冻结。
- Debug APK 三个 ABI 与通用包构建通过；Lint 零错误、65 项警告；`git diff --check` 通过。
- 使用现有缓存执行：`.\gradlew.bat ':app:testDebugUnitTest' ':app:assembleDebug' ':app:lintDebug' '-PuiPreview=true' '-Pandroid.useAndroidX=true' --offline --console=plain`。AndroidX 仅作为此次 Robolectric 测试的构建参数，生产依赖未增加。
- 尚未完成真实手机 Camera2/HAL/GL 回归，不据此宣称速度提升。
