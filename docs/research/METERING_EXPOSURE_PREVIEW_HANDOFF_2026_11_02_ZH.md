# 测光与曝光预览工作交接

交接日期：2026-11-02，Asia/Shanghai。

**当前应用已实装第219轮全局测光模型；Clear自动曝光平滑错误已修复并进入main。最新只读检查确认了五项其他问题，尚未修复。用户尚未反馈修复包在原故障设备上的实际效果。**

本次只整理交接文档，不修改生产代码、模型、训练、校准或设备设置。接手会话先只读核验并报告；后续修复按用户的新指令执行。

## 1. 工作区和基线

| 项目 | 位置／状态 |
|---|---|
| 本会话工作区 | `C:\Users\15449\.codex\worktrees\5b24\lightmeter a` |
| 本会话分支 | `codex/integrate-worktrees-20261024` |
| main主工作区 | `D:\Project\lightmeter a` |
| 当前生产基线 | `8154bacc0926515a9ca6fa5db35fc19120660b14` |
| 模型接入提交 | `3a94ccc` |
| Clear自动预览修复提交 | `8154bac` |

10月30日已合并当时全部本地分支及已知远端引用，包括独立测光、取景框、连续测光、曝光渐变和界面更新。独立测光工作树原有的RAW兼容修改先保存后合并。旧0.6.0源码已由等价整理提交进入main，经源码树比较确认后补齐祖先关系，保留新版实现。11月1日修复也已快进main。

本次文档生成前，本会话工作树干净。主工作区此前有用户自己的未跟踪胶片资料／临时文件，保留不动。接手时重新核对Git状态，不以本文替代实时检查。

项目为Android原生摄影测光应用，包含Normal、Clear连续测光、Zone、局部／角度测光、校准及曝光预览。实现为Kotlin、Camera2、自绘View、OpenGL、JNI/C++；神经网络作为全局测光的摄影曝光建议层。

## 2. 应用目前实际执行什么

### 全局模型及输入

- 全局模式保存标识为 `MeteringMode.AVERAGE`，界面显示“全局”。模型仅进入这一模式。
- Normal全局、Clear处理流连续全局及可用的RAW全局点击测光使用同一模型。
- Clear自动采样使用YUV，必要时回退显示预览。没有改成常驻RAW每秒1／3次。点击测光按硬件与“平滑测光”等设置选择RAW或处理流，并可锁定读数。
- 局部、角度、中央、Zone和测光校准保留各自原入口。预览曝光校准与RAW／YUV／ISP测光校准是独立存储域。
- 模型或必要映射元数据不可用时回退现有全局统计策略；该策略仍受亮度／截断检查限制，见问题B。

RAW按完整2×2 Bayer单元采样，复用黑／白电平、四通道白平衡、阴影校正、Camera2颜色矩阵和设备EV校准。YUV／预览复用颜色解码、逆曲线与来源响应。

模型输入为96×64个RGB采样位置，12×8区，每区8×8点；每区8项加全局8项，共776维。使用 `log2(max(Y,1e-6)/0.18)`，保留画面整体曝光位置，标准化参数冻结在模型内，不逐帧归一化亮度。原全局统计另有自己的采样预算，不能说完整流程总共只读6,144个像素。

显式 `sensorCrop` 优先决定范围，否则按预览视口、画幅和变焦推导；网格按显示方向排列，裁切、旋转与镜像分别处理。RAW矩阵为Camera2列主序，与Python接口互转时须注意矩阵表示。

契约版本为 `global-input-mapping-v1`、`scene-linear-sparse-pairs-v2`、`relative-image-ev-grid-v1`。

### 曝光坐标与补偿

```text
相机曝光坐标 cameraEV100 = log2(N² / t × 100 / ISO)
模型输出 C：正值增加曝光
模型建议EV100 = cameraEV100 − C
应用最终曝光EV100 = 模型建议EV100 − 用户曝光补偿
```

模型特征中的EV是当前图像的相对亮度，不是绝对场景EV100。`MeterReading.sceneEv100`保留场景统计；模型建议通过有符号的 `exposureReductionEv` 接入参数和预览。这一字段在神经网络路径可为负，不能只按“减少曝光”理解。

使用模型时不再叠加旧策略最多半档的高光修正。RAW多帧先结合各帧自己的曝光元数据换算建议EV100，再融合；摄影建议不作为设备校准锚点。处理后数字增益尚未正确纳入，见问题A。

关键入口：`GlobalMeteringInputMapping.kt`、`GlobalMeteringNetwork.kt`、`GlobalMeteringModel.kt`、`EvaluativeFrameAnalyzer.kt`、`EvaluativeMetering.kt`、`AutoMeterSession.kt`、`ExposurePreviewStateCoordinator.kt`。

## 3. 指定模型及训练结果

用户指定的“219步”对应断点字段 **epoch=219**：

```text
outputs/metering-training/raw-adaptation-v3-epoch200-to260-20261030-v2-io/best.pt
SHA256: c548b23435c5bb7f383020a731da96abf1835d13098274988db36d066913b0db
```

同目录 `last.pt` 是第234轮，不能自动用它替代219轮。

APK权重：

```text
app/src/main/assets/global_metering_epoch219.bin
213,740字节，约209 KiB
SHA256: 1d4f4b5528878f78093e34ba9fb9b10b57d6a7b6e862917a49d1cd15dd5f2dbd
```

MLP为776→64→32→2，51,874参数、51,776 MAC；应用采用FP32本地推理，无新增通用推理引擎依赖。权重包含标准化与误差尺度因子，构建／加载时核验身份。误差尺度不当作准确概率。

导出目录为 `outputs/global-model-epoch219-20261030/export/`，含ONNX opset 17、safetensors、特征、标准化、来源及许可记录。复现工具为 `tools/metering-training/export_android_model.py`。

### 已有验证，不等于实拍验收

固定验证为16张原图×16 ROI×9曝光，共2,304个相关样本；MAE按原图先汇总再宏平均。第219轮全量记录：

| 验证域 | MAE／EV | 样本P90／EV | 最大误差／EV |
|---|---:|---:|---:|
| 旧JPEG配方 | 0.6205 | 1.5228 | 5.9651 |
| 模拟RAW | 0.4363 | 0.9826 | 4.2599 |
| 模拟预览 | 0.4444 | 1.0052 | 3.6654 |

同曝光RAW／预览预测差MAE约0.0640EV。它们是参考照片监督、虚拟ISP上的合成验证；没有完成原定有效样本MAE≤0.25、P90≤0.5的完整精度验收，也没有证明跨设备实拍精度。

桌面JVM标准化＋网络平均约0.035ms，不含相机读出、取样、特征或完整全局统计，也不是手机功耗／延迟实测。

## 4. 用户反馈与已修复部分

用户确认：

> 曝光预览打开后偏暗，关闭后正常。问题在Clear连续测光；校准滑块能改变并保存亮度，Normal预览及Clear手动点击测光均有正确的亮度对应；自动连续测光时偏暗。

### `8154bac`已修复

旧 `AutoMeterSession.accept`只平滑基础EV，却直接加新一帧相对于新基础的偏移，混合不同时间的数据。例如：

| 帧 | 基础EV | 模型完整建议EV | 相对偏移 |
|---|---:|---:|---:|
| 第一帧 | 12 | 10 | −2 |
| 第二帧 | 10 | 10 | 0 |

旧代码将第二帧基础平滑成10.9，再加0，得到10.9EV，额外压暗0.9档。此为自动测试反例，不是用户设备的实测偏差。

修复后独立平滑完整模型建议，再重算相对偏移；完整建议变化也受平滑／死区限制。连续采样增加了渐变完成及元数据匹配条件，手动曝光容差0.1EV，AE模式须匹配当前补偿。

**门控仍缺少问题C中的超时出口，不能将整条连续测光链视为已验收。** 当时试验性的校准页面改动已经撤回；最终提交没有更改预览校准存储／页面或219轮权重。用户尚未反馈修复包的原设备效果。

### 最近完整自动验证与修复包

- 809项测试、139套件通过，零失败／错误／跳过；Debug构建成功，Lint零错误、69条警告。
- 18组本地网络／PyTorch补偿差最大约0.0000023EV，ONNX约0.0000012EV；六组映射特征经219轮模型计算的差异最大约0.0000128EV。
- 产物目录：`outputs/clear-continuous-preview-fix-20261101/`，详见 `verification.json`。

| 包 | SHA256 |
|---|---|
| `lightstop-clear-preview-fix-arm64.apk` | `220e9f50537846ab4fe0f29dab7d078038b60a82b4cdb8adc2655a14d7a195ab` |
| `lightstop-clear-preview-fix-universal.apk` | `19779624ffbbddbbbde17fdcbab7b935751f9a8272c306ce7743f6421ca4a314` |

11月1日曾连接V2445A，但它未安装本应用，未把它当作用户原故障设备验证。11月2日只读审计没有操作手机、重新构建或新增测试文件。

## 5. 五项尚未修复的问题

以下基于8154bac的调用链、内存公式反例和官方API核对。代码路径已确认，设备实际触发范围仍需验证。

### A．P1：处理后流遗漏逐帧RAW后数字增益

位置：`MeteringAnalysis.kt:100,176`、`EvaluativeFrameAnalyzer.kt:208`、`CameraPreviewRequestCoordinator.kt:79–87`。

YUV／预览使用 `SENSOR_SENSITIVITY`计算EV，没有纳入 `CONTROL_POST_RAW_SENSITIVITY_BOOST`。像素已经被提亮，曝光公式却没有抵消增益。在线性、未截断且其余条件相同的反例中，boost由100%到400%、亮度由0.04到0.16，结果偏移+2EV。固定校准不能普遍抵消变化的增益。

Android定义处理后输出总灵敏度为传感器ISO×boost/100，RAW仍只用传感器ISO；从AE切手动时保持处理后亮度也须考虑boost。预览请求目前没有显式保持／抵消它，可能发生切换亮度变化。

官方依据：[CONTROL_POST_RAW_SENSITIVITY_BOOST](https://developer.android.com/reference/android/hardware/camera2/CaptureResult#CONTROL_POST_RAW_SENSITIVITY_BOOST)、[SENSOR_SENSITIVITY](https://developer.android.com/reference/android/hardware/camera2/CaptureResult#SENSOR_SENSITIVITY)。

建议统一处理后曝光／增益定义，同时核对统计EV、模型输入和预览执行，防止同一增益抵消两次。RAW继续使用传感器ISO。旧YUV／ISP校准可能吸收旧误差，须设计版本兼容并保留原记录。

### B．P1：严重截断／全黑的恢复信号在上游丢失

位置：`EvaluativeMetering.kt:50,101–102`、`CompatibleLightMeter.kt:226`及显示预览分析、`AutoMeterSession.kt:38`、`MainActivity.kt:1617`。

全局基础统计在饱和或低信号面积超过约5%时可能失效；无可靠基础时融合返回null。Clear却要先收到 `MeterReading`，才执行“70%以上饱和／亮度过低则调整曝光”的恢复。

例如80%饱和在上游已被拒绝，到不了恢复分支，显示预览回退也可能同样拒绝。错误回调仅显示错误，可能维持旧曝光反复失败；全黑也有此路径。

建议分开传递“有效测光建议”与“恢复所需的质量／截断证据”。恢复使用实际曝光元数据和有界步长，不把截断样本当作有效场景测量。

### C．P2：采样门控无超时／降级出口

位置：`ExposurePreviewStateCoordinator.kt:53–63`、`CameraController.kt:547`、`ContinuousPreviewMeter.kt`的canSample重试。

11月1日门控要求实际曝光与请求相差≤0.1EV。若HAL量化、约束或未完整执行请求，差异一直大于阈值，每次采样都会跳过。目前没有累计超时、降级或明确错误，可能预览仍刷新、测光却一直不更新。

条件反例：请求10EV、实际持续9.8EV，差0.2大于0.1，所有重试均不通过；未证明用户设备实际返回这组值。

建议保留渐变／旧帧保护，同时加入有界等待、基于实际曝光继续采样或可见错误。与A的曝光坐标定义一起核对。

### D．P2：校准准备超时仍可能报告就绪

位置：`ExposurePreviewCalibrationCoordinator.kt:69,151`。

正常路径等待新请求的稳定AE零补偿帧；1.8秒超时却直接使用 `baselineFrom(latestResult())`，没有重新检查帧代次、请求序号、AE模式和补偿。只要能生成手动请求或支持AE补偿，finishPreparation仍可能ready。

可能使用旧手动／不稳定帧，甚至无有效基准但支持AE补偿就启用校准。建议明确新鲜、中性基准的证据条件和失败处理。

它独立于已修复的Clear平滑错误，不应直接认定为用户原先Clear偏暗的根因，也不据此扩大修改全部校准流程。

### E．P2：预览达到模拟上限，界面仍按可用处理

位置：`ExposurePreview.kt:103,110,127`、`ExposurePreviewStateCoordinator.kt:162,200`。

预览采用约1/30秒优先、约1/15秒硬限制和设备ISO范围。无法达到目标时已设置 `clamped=true`，但supported把“能生成一个手动曝光”视为支持，限幅主要留在日志。参数继续改变，画面可能已不跟随，也没有明确说明模拟范围不足。

数值示例：N=2、最高ISO800、快门限制1/15秒，目标−2EV只能实现约2.9069EV，差4.9069档。这是模拟范围反例，不是设备测光精度实测。

建议暴露目标／实际曝光差和限幅状态，区分测量有效与预览无法模拟，防止将限幅误当成可用校准消除的偏差。

## 6. 建议接手顺序与验证

1. 先只读核验分支、基线、用户安装包与设备，取得11月1日修复包反馈。当前连接手机不自动视作原故障设备。
2. 收到修复指令后，先建立A／B独立反例，处理增益定义和质量恢复通道，再处理C，避免用“永远不采样”掩盖故障。
3. 再完善D的校准新鲜度和E的限幅反馈。保留独立校准域、用户记录和219轮权重。
4. 最小修复后运行适当回归，生成独立命名的包并记录哈希；在实际设备对比自动与点击测光。

| 维度 | 至少覆盖 |
|---|---|
| 路径 | Normal全局、Clear自动／点击RAW及处理流、局部／角度、Zone |
| 控制 | 预览关／开、手动／仅AE补偿、正负用户补偿、非零预览校准 |
| 场景 | 稳定中性、逆光、高光、80%饱和、全黑、明暗突变、超出模拟范围 |
| 元数据 | boost100/200/400、缺失可选键、延迟旧帧、曝光误差0.05/0.2EV |
| 状态 | 锁定／解除、切镜头／来源、选区／数码变焦、旋转镜像、后台恢复 |

可复用构建环境，后续获准修复／测试时运行，本次未执行：

```powershell
$env:JAVA_HOME = 'D:\新建文件夹 (7)'
$env:ANDROID_HOME = 'C:\Users\15449\AppData\Local\Android\Sdk'
& 'C:\Users\15449\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat' `
  :app:testDebugUnitTest :app:assembleDebug :app:lintDebug `
  '-PuiPreview=true' '-Pandroid.useAndroidX=true' --offline --console=plain --no-daemon --max-workers=2
```

现有测试入口包括 `AutoMeterSessionTest`、`ExposurePreviewTransitionTest`、`GlobalMeteringNetworkTest`、`GlobalMeteringModelIntegrationTest`、`EvaluativeMeteringIntegrationTest`及原生 `evaluative_raw_sampler_test.cpp`。已有809项通过不代表覆盖了本轮五项问题。

## 7. 共享训练与许可约束

当前任务是应用交接，不需要重新训练。用户确认训练已完成；以后确需训练时另行核验实际运行状态及用户指令。

- 共享原图池：`D:\Project\film2\training-data\liu4k-v2-pool`；唯一登记簿为同目录 `continuous-registry.sqlite3`。
- 现有Python：`C:\Users\15449\.codex\worktrees\dust-training\film2\.venv-training\Scripts\python.exe`，不升级共享环境。
- 训练操作复用 `C:\Users\15449\.codex\skills\film-training-ops\SKILL.md`、工作区 `outputs/shared-training-20261025/training-ops/WORKFLOW.md`及既有ops入口，不重复创建监视器／采集器／定时任务。
- 原图只下载、存储和去重一次，保留来源／作者／许可／SHA256／phash。两模型的标签、合成、权重、优化器和游标独立，固定验证／测试全局隔离，GPU串行。
- 当前模型清单为648训练／16验证。固定16张测试图未进入此次适配准备与小试。新图在版本化阶段边界纳入，不热改manifest。
- 保留共享原图、派生缓存、父断点及best／last。回收须满足所有消费者提交和消费完成等原规范；RAW适配记录不能冒充旧队列消费完成。
- 代码仍为Apache-2.0，没有转GPL，也未导入另一个任务的GPL训练代码。共享图源为CC0-1.0／CC-BY-4.0，保留署名与来源。

准备指针 `metering-raw-prepared.json` 的 `state=ready-for-explicit-training` 不是实时训练状态。早期文档“第134轮、603张、尚未启动”等已过时，不据此恢复旧训练。

## 8. 相关资料

- [第219轮模型接入及测试](<C:/Users/15449/.codex/worktrees/5b24/lightmeter a/docs/research/GLOBAL_MODEL_EPOCH219_DEVICE_TEST_2026_10_30_ZH.md>)。
- [11月1日Clear平滑修复](<C:/Users/15449/.codex/worktrees/5b24/lightmeter a/docs/research/CLEAR_CONTINUOUS_PREVIEW_FIX_2026_11_01_ZH.md>)。
- [模型结构说明，主体为早期配方](<C:/Users/15449/.codex/worktrees/5b24/lightmeter a/docs/research/CURRENT_METERING_MODEL_ARCHITECTURE_ZH.md>)。
- [历史共享训练交接，注意顶部更新](<C:/Users/15449/.codex/worktrees/5b24/lightmeter a/docs/research/GLOBAL_METERING_TRAINING_HANDOFF_2026_10_28_ZH.md>)。
- [10月21日旧审计，部分几何／饱和／时间戳问题已由后续分支修复](<C:/Users/15449/.codex/worktrees/5b24/lightmeter a/docs/METERING_CODE_AUDIT_2026_10_21_ZH.md>)；不能照单重复列为现存问题。

## 9. 可直接复制给接手会话

> 请接手lightstop测光与曝光预览工作。先读 `C:\Users\15449\.codex\worktrees\5b24\lightmeter a\docs\research\METERING_EXPOSURE_PREVIEW_HANDOFF_2026_11_02_ZH.md`。当前生产基线main为8154bac，已实装指定第219轮模型，并修复Clear连续测光把固定10EV建议错算成10.9EV的平滑错误，809项测试通过；用户尚未确认修复包的原设备效果。最新只读审计确认A数字增益遗漏、B截断恢复信号上游丢失、C门控无超时、D校准超时误判就绪、E预览限幅无明确反馈五项未修问题。先只读核验并报告，不自动修改代码／设备、重置校准或恢复训练。后续获准修复后按A/B、C、D/E顺序建立反例与最小修复，保留219轮权重和已有校准。共享图池及两模型独立状态沿用原规范，不删除缓存，不启动旧JPEG队列或重复监视任务。
