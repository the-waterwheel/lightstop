# 测光设置含义与 ISP 校准修复

2026-10-15。代码基线 `c0a3ffb`。用户反馈来自 0.6.1 正式版：高精度模式校准时 RAW/YUV 成功，ISP 失败。本次没有连接手机，以下区分静态确认、可控测试和待实机验证内容。

## 两个设置的职责

| 设置 | 控制内容 |
| --- | --- |
| 系统测光模式 | App 自动筛选组合的策略。高精度优先 RAW，再尝试 YUV/ISP；稳定模式筛选 YUV/ISP；兼容模式筛选 ISP。这里的“系统”指 App 的自动筛选流程。 |
| 测光组合选择 | 决定由自动流程筛选，还是用户逐一测试、确认某个实际相机输出工作流。手动候选来自完整硬件组合列表，以用户确认的组合为主。 |

组合不只是 RAW/YUV/ISP 的名称，还包括普通测光与 Zone 跟踪分别常驻哪些输出、测光时是否临时切换到独立 RAW 会话，以及恢复哪种会话。两个设置因此分别管理自动策略与组合选择方式。

代码依据：`SettingsCatalog`、`CameraCombinationPolicy.planAllowedByMode`、`CameraOpenConfigurationResolver` 的 matrixCandidates/systemCandidates，以及 `CameraController` 的手动/自动组合分支。

## 高精度模式为何仍校准 ISP

`MeteringCalibrationPlan` 按实际硬件能力依次生成 RAW、YUV、ISP 阶段，与普通测光当前选择的模式独立。每阶段使用最小独立会话，并分别保存修正，供后续切换模式或兼容回退使用。高精度普通测光以 RAW 为主，不代表校准只生成 RAW 修正。

`MeteringCalibrationRun` 与持久化保留已成功来源，失败来源保留旧修正。ISP 失败不会撤销本轮有效的 RAW/YUV 校准，也不会因此自动关闭 RAW。既有各模式校准计划及来源隔离测试继续覆盖这些规则。

## 正式版问题与本次小修复

静态比对 0.6.1 发布基线 `7ff2147`：`FilmNegativeRenderer` 在 GL 输出上仅 swap，没有传递相机输入帧时间戳。ISP 使用 TextureView 画面，需要依时间戳精确配对曝光信息；RAW/YUV 直接配对相机 Image 和结果，不经过这一显示输出边界。这个正式版缺口符合用户报告的“RAW/YUV 可校准而 ISP 失败”。尚无该次手机失败日志，不能排除其他设备因素。

显示时间戳修复已在前一提交 `c0a3ffb` 完成，但尚未进入用户安装的正式版，详见 [上轮修复记录](PREVIEW_TIMESTAMP_AND_YUV_ANALYSIS_2026_10_14_ZH.md)。本轮不改动 GL、测光公式或校准保存规则。

进一步检查发现：ISP 获取到有效 Bitmap 后，如果对应 CaptureResult 暂未到达，会立即回收该 Bitmap 并重新抓取。曝光信息持续稍晚到达时，反复抓新帧可能一直错过可以配对的旧帧。RAW/YUV 原有配对器支持两种到达顺序，ISP 原来仅查询一次缓存。

本次在 `CompatibleLightMeter` 为一张已冻结的显示帧短暂保留 Bitmap，接收随后到达且时间戳完全一致的 CaptureResult。缓存中已经有结果时走原先快速路径。其他时间戳不会触发分析。最多等待三个现有重试槽，即 96 ms；等待消耗原有 48×32 ms 重试预算，不能为每张 Bitmap 累加新的长超时。

成功匹配后取消等待任务并分析、回收 Bitmap；超时回收并按剩余预算重试；取消或关闭时取消任务并回收；已排队但属于旧测量的 Bitmap 在读取元数据前直接回收。仍检查亮度、曝光时间、ISO 和光圈，不使用“最新结果”代替精确配对。

## 验证

新增 7 项 Robolectric 集成测试，直接运行 `CompatibleLightMeter` 和真实 ISP 亮度分析，控制 TextureView Bitmap、时间戳和 CaptureResult：

- 元数据先到，仍走缓存快速路径。
- 元数据晚到，显示已前进时仍使用原 Bitmap 与原曝光；新时间戳不能代替旧帧。
- 元数据始终缺失时在原重试预算内终止，并回收所有 Bitmap。
- 取消时回收等待画面，忽略迟到结果和旧超时。
- 旧测量取消后，新测量不接收旧帧的元数据或超时。
- Bitmap 已取得、相机任务尚未执行时取消，直接回收而不查旧元数据。
- 精确匹配但缺曝光字段时继续重试，不生成无效校准读数。

执行：

```powershell
.\gradlew.bat ':app:testDebugUnitTest' ':app:assembleDebug' ':app:lintDebug' '-PuiPreview=true' '-Pandroid.useAndroidX=true' --offline --console=plain
git diff --check
```

全量 639 项测试通过，零失败、错误、跳过；Debug 三个 ABI 与通用 APK 构建通过；Lint 零错误、65 条既有警告。

测试没有真实 Camera2/HAL/EGL，也没有模拟图像传感器或验证绝对测光精度。手机端需安装包含 `c0a3ffb` 和本次修复的新包，再验证高精度模式完整 RAW → YUV → ISP 校准、连续重测及前后台取消。当前没有发布新正式版，没有修改手机中的校准或设置。
