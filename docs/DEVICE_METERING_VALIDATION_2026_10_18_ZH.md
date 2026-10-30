# 测光真机验证与 RAW 能力判定修正

2026-10-18。设备为 vivo V2405A，Android 16 / API 36，arm64-v8a。生产代码基线为本分支 `5281609`，叠加本文说明的 RAW 判定修正；工作树为 `codex/independent-metering`。仅操作本对话工作树，未修改其他对话的工作树。

## 代码范围与 0.6.1 对照

测试包包含 main / 0.6.1（`7ff2147`）、`codex/camera-controller-boundaries` 到 `9b74d85` 的职责拆分、预览时间戳、YUV 处理与设置调整，以及本分支的全局测光。未包含 `codex/non-normal-visual-polish` 的 `74df53a`；本轮未再合并其他分支。

按用户要求直接从 GitHub 的 `v0.6.1` 读取了：

- [RawSensorFormatPolicy.kt](https://github.com/the-waterwheel/lightstop/blob/v0.6.1/app/src/main/java/com/lightmeter/rawmeter/RawSensorFormatPolicy.kt)，blob `611320398ee5001592a6375f6afffe209e01f3c1`。
- [CameraRecoveryStateMachine.kt](https://github.com/the-waterwheel/lightstop/blob/v0.6.1/app/src/main/java/com/lightmeter/rawmeter/CameraRecoveryStateMachine.kt)，blob `6e626e4af550e9e88df0aa0124cb2c5a182c9efe`。

两者与本地 0.6.1 标签一致：旧版本也同时要求 RAW 能力标记、RAW 输出、非 Legacy 和 Bayer，并包含未确认工作流连续两次 RAW 测量失败后降级的计数逻辑。因此，不能把这两个规则描述为本分支新引入的回归，也不能仅凭代码对比判定用户所见提示的唯一根因。本轮没有安装旧版做相同场景的实机 A/B 对照。

## RAW 判定修正

- 有 `RAW_SENSOR` 输出尺寸和可读取的 Bayer 排列就允许尝试 RAW。不再强求 `REQUEST_AVAILABLE_CAPABILITIES_RAW` 标记，也不再因硬件级别为 Legacy 而排除。
- 删除“连续两次 RAW 测量失败后长期切换到兼容管线”的计数、标志和降级分支。未确认路径仍可为当次失败使用预览来源，下一次请求继续尝试原 RAW 工作流；已确认 RAW 的失败继续明确报错，保持原来的来源规则。
- 没有把真实会话配置失败伪装为支持，也没有改写图像的 Bayer 格式、曝光元数据或读取布局。原来的实际输出组合探测、相机恢复和用户选择仍由各自现有模块管理。
- `CameraController` 本轮净减少 61 行；新增实机测试放在 `src/androidTest`，不进入生产 APK。

## 真机覆盖

使用实际 Camera2 会话、真实 RAW / YUV / 显示预览和实际 ARM64 JNI 库。测试驱动没有新增依赖，没有写入校准值。修正后基础套件 8 项、过曝套件 4 项、恢复与镜头套件 6 项、能力回归 1 项，共 19 项全部通过；结果及边界见下文。

| 检查 | 证据与结论 |
| --- | --- |
| ARM64 原生采样桥 | 在手机上给实际 JNI 采样器输入确定的 Bayer 缓冲，16 个完整单元的亮度均为 0.5；这是采样器检查，不是场景精度校准 |
| Normal 四种模式 | 点测、中央重点、按角度、全局均获得实际 RAW；只有全局带全局曝光建议 |
| 全局公式与重复测量 | 检查实际读数满足 `delta=clamp(U-B-2.5,0,0.5)` 及 `recommended=B+delta` |
| Normal / Zone 往返 | 全局进入 Zone 默认按角度，Normal 持久化仍为 `AVERAGE`，返回后恢复全局 |
| Zone 两点批量 | 实际建立两个点，批量复测共用 RAW 捕获；没有逐点各拍一轮 |
| 显示变焦 | 同一固定场景按角度测光在 1× / 2× 的物理 ROI 不变；全局使用当前变焦范围 |
| 横竖屏 | 实际旋转后取得全局 RAW，Zone 往返保持正确模式，能恢复竖屏；不把不同画幅的 EV 当成同一范围比较 |
| 处理后来源 | 稳定模式取得 `YUV_PREVIEW`，兼容模式取得 `ISP_PREVIEW` 的全局结果 |
| 后台恢复 | 确认测光请求在途后按 Home，确认窗口失焦，再由主机模拟用户重新打开；预览恢复，下一次全局读数来自 RAW |
| 未确认 RAW 连续失败 | 仅测试代码临时取消工作流确认，注入三次失败回调，每次实际取得 ISP 备用读数；原 `raw_split_v1` 和 RAW 可用状态保留，随后真实 RAW 点测成功，212.24 ms。故障回调为注入，最终 RAW 捕获为真实 |

### 镜头路径

修正后恢复套件 6 项全部通过。各镜头画面与光圈不同，以下只比较路径和完成状态，不用 EV 差推断镜头精度。

| 入口 | 焦距 | 实际来源 | 点击请求到结果 |
| --- | ---: | --- | ---: |
| 后置自动 | 6.54 mm | RAW | 214.50 ms |
| 超广角 | 2.32 mm | RAW | 507.41 ms |
| 主摄 | 6.54 mm | RAW | 253.25 ms |
| 长焦 | 22.48 mm | RAW，基础 3 帧 | 560.14 ms |
| 前置 | 3.09 mm | YUV_PREVIEW | 397.77 ms |

前置 Camera2 未提供 Bayer RAW_SENSOR 输出，不能把这条实际 YUV 路径标成 RAW。回到原来的后置自动选择后，RAW 恢复。

### 连续过曝重拍

为了稳定触发实际饱和，测试先通过已有曝光预览功能请求明显过曝，并等待 CaptureResult 确认 AE 关闭和曝光乘积确实升高。随后直接使用现有 RawLightMeter 的捕获入口及真实会话、RAW 缓冲、元数据；这样避免普通测光入口的中性 AE 准备主动消除测试所设过曝。此部分验证实际 RAW 采集所有者，普通入口由基础套件另行覆盖。

原始采样实际为 66,666,666 ns、ISO 12800：

| 模式 | 观察到的重拍 | 最终状态 | 请求到结果 |
| --- | --- | --- | ---: |
| 点测光 | −5 EV，2,083,333 ns | 测点通道中位数恢复 | 432.88 ms |
| 中央重点 | −5 EV，2,083,333 ns | 测量统计恢复；仍可有少量亮像素，符合局部中位数判据 | 414.44 ms |
| 按角度 | −5 EV，2,083,333 ns | 物理角度区域统计恢复 | 382.12 ms |
| 全局 | −3、−6、−9 EV：8,333,333 / 1,041,667 / 130,208 ns | 第三级截断面积降到 0，亮端确认 | 780.80 ms |

全局第一级后截断面积约 40.62%，第二级约 0.668%，仍高于 0.5% 阈值，因此继续第三级；证明没有在两次后停止。RAW 所有者内部本次约 736.7 ms。最终读数保留较长曝光阶段可信的基准 B，以更短曝光恢复 U；读数中保留的基准曝光不必等于最后一张亮端验证帧。

上述时间是本机特定场景样本，不是所有设备的上界。端到端统计含约 30 ms 测试轮询粒度，不含安装、启动、镜头切换等待和人为设置过曝的时间。

RAW 判定修正后过曝套件 4 项再次全部通过：三种局部模式各一次 −5 EV，全局仍连续 −3 / −6 / −9 EV 三次；请求到结果分别为 414.02 / 436.39 / 440.04 / 675.89 ms。每次会在饱和统计恢复后停止，没有固定拍满三级。

## 重复性与绝对准确度

最初固定场景的 5 次主摄全局 RAW：基准 B 的极差为 0.00186 EV，端到端最小 / 中位 / 最大为 194.88 / 211.33 / 283.68 ms。这仅描述这 5 次采样的一致性，不代表绝对精度。

RAW 判定修正后基础套件 8 项再次全部通过：另一次固定场景 5 次全局 B 极差为 0.00081 EV，端到端最小 / 中位 / 最大为 182.99 / 232.50 / 293.36 ms；按角度测光从 1× 到 2× 的读数差为 −0.00092 EV。两轮场景存在移动与照明变化，不能把时间差解释成此修正的性能提升。

同一初始固定场景未校准的 RAW 全局 B 约 3.21 EV，YUV 约 6.97 EV，显示 ISP 约 6.94 EV，存在约 3.7–3.8 EV 的来源差异。YUV 与显示 ISP 接近不能证明其绝对读数正确；没有标准光源、灰卡加独立参考或外部测光表，尚不能区分设备 RAW 基准、厂商处理响应和其他来源问题。本轮没有用任一路径倒推并写入另一条路径的校准值。

## 失败记录与未覆盖边界

- 一轮中途照明显著变暗，RAW ISO 达到 25600，全局返回“测光数据无效”。用户恢复照明后对应镜头成功。该轮失败保留在本地记录，不算通过；也没有放宽全局算法中“无可信基准不提供曝光建议”的定义。
- 恢复测试早期把 Home 命令已发送误当成窗口已经失焦，并在后台尝试自行拉回前台，产生测试器时序失败。修正为确认失焦并由主机打开后，恢复套件全部通过。未据此修改生产生命周期代码。
- 此设备实拍观察到了全局连续三级与局部一级。局部 −10 / −15 EV、硬件曝光下限仍饱和、AE 补偿下限由既有纯逻辑测试覆盖，尚未在实景强制达到。
- 未验证其他设备、折叠屏或不同系统遮挡；没有实现尚处于讨论阶段的竖幅画框与取景 / 测光分别变焦。
- 没有外部曝光基准或实拍反转片，不能宣称测光绝对精度或某种反转片的曝光表现已通过验证。

## 构建、安装及复现

本轮完整 JVM / Robolectric 回归为 688 项、119 套件，零失败、错误、跳过。比此前 689 项少一项，是删除了要求“两次测量失败后降级”的旧测试；新行为由实机故障注入回归覆盖。Debug 三 ABI 与通用 APK 构建通过；Lint 为 0 错误、67 警告，与本分支修正前相同。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest '-PuiPreview=true' '-Pandroid.useAndroidX=true' --offline --console=plain
```

手机安装的是 arm64-v8a Debug 主 APK，约 24.49 MiB；校验确认与传入的本地文件完全一致。独立测试 APK 约 0.14 MiB。通用 Debug APK 约 50.44 MiB，没有安装到此设备。0.6.1 正式通用 APK 的发布记录为 42.60 MiB；调试包没有正式版的缩减优化，也不是同一个 ABI 打包范围，不能直接比较。用户报告安装确认页显示 100 多 MB，本轮没有取得该字段的证据，尚不能解释安装器该显示的具体含义。

测试驱动为 `DeviceMeteringInstrumentation`，支持 `baseline`、`stress`、`recovery`、`capability`。仅供已授权且解锁的实机测试：安装 Debug 主 APK 与 androidTest APK 后，用明确的设备序列号替换下方占位符；不应向多设备环境直接省略 `-s`。

```powershell
adb -s DEVICE_SERIAL shell am instrument -w -e suite baseline com.lightmeter.rawmeter.test/com.lightmeter.rawmeter.DeviceMeteringInstrumentation
```

本机的安装与 Activity 启动受厂商交互限制：安装身份验证由用户完成；部分 instrumentation 启动需要主机另执行 `am start -W -n com.lightmeter.rawmeter/.MainActivity`。恢复套件输出 `BACKGROUND_READY` 后，由主机再次执行同一启动命令模拟重新打开。没有关闭安装验证或修改系统安全设置。

JSON 报告写在应用私有 `files/device-validation-<suite>.json`，可经 Debug `run-as` 读取。截图、实际相机日志和原始 JSON 只保存在被 Git 忽略的 `app/build/device-tests/2026-10-18/`，不提交设备序列号、图像、校准资料或日志。
