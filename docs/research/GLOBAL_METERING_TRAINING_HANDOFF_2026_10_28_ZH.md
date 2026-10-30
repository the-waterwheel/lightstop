# 全局测光：应用映射、共享图源与续训交接规范

交接日期：2026-10-28，Asia/Shanghai。适用工作区：`C:\Users\15449\.codex\worktrees\5b24\lightmeter a`。

2026-10-30更新：下文记录的是10月28日准备阶段。后续训练已保存第219轮best和第234轮last；按用户指定，应用现已接入第219轮权重进行设备测试。当前接入、模型身份和验证见[第219轮应用测试说明](GLOBAL_MODEL_EPOCH219_DEVICE_TEST_2026_10_30_ZH.md)，不再按下文的第134轮指针启动旧阶段。

**当前状态：应用全局测光的输入映射已实现，训练包已冻结，小批恢复验证和启动预检通过；长期 RAW 适配训练尚未启动。** 接手会话先执行只读预检并报告，收到明确启动指令后再启动长期训练。共享调度器仍为用户暂停状态，本交接不恢复除尘、采集器或旧 JPEG 训练队列。

## 1. 本次完成的应用改动

映射仅在全局测光入口运行，对应保存的模式名 `MeteringMode.AVERAGE`。局部、角度、中央、Zone 与校准流程保持原有入口。RAW、YUV 和预览图均生成相同定义的线性 RGB 稀疏特征：

```text
可见画面 / 用户选区 / 数码裁切
  → 按实际预览视口、旋转和镜像确定 ROI
  → 96×64 = 6,144 个点
  → 来源对应的线性 RGB 映射，复用已有校准
  → 12×8 个区域 × 8 项 + 8 项全局统计
  → 776 维未标准化特征
```

- RAW：在 ROI 内取完整 2×2 Bayer 单元，处理各 CFA 位置的黑电平、白电平、既有阴影校正与四通道白平衡；两个绿色通道分别校正再合并。随后应用 Camera2 列主序颜色矩阵、已有设备 EV 修正，截断至 `[0,1]`。
- YUV／预览：复用现有颜色解码、逆曲线及来源响应校准，逐点保留 RGB；完整逆曲线不会再重复反解 gamma。
- 数码变焦和范围选择只落实一次，采样不越出 ROI；YUV 使用自身的预览方向信息，预览位图使用显示方向，RAW 使用传感器方向映射。
- 输入保留整幅画面的曝光位置，不逐帧归一化为相同亮度；已有 EV 校准只应用一次。不新增需要用户操作的神经网络专用设备标定。
- RAW 缺少有效白平衡或颜色矩阵时不生成伪造的模型特征，原有统计测光仍可工作。

目前映射会实际生成 `MeteringFrameStat.globalInput`。**应用读数仍来自原有全局统计策略；尚未加载新神经网络权重，也未新增 ONNX 推理运行时。** 原统计核继续使用其原有采样；6,144 点是本次新增模型输入的预算，不能把整个现有全局流程描述为仅处理 6,144 点。

关键实现位于 `app/src/main/java/com/lightmeter/rawmeter/GlobalMeteringInputMapping.kt`、`EvaluativeFrameAnalyzer.kt`、`ProcessedLumaMath.kt`，以及 `app/src/main/cpp/evaluative_raw_sampler.h` 和 `raw_meter.cpp`。

## 2. 必须一致的应用／训练契约

| 项目 | 固定值 |
|---|---|
| 应用映射版本 | `global-input-mapping-v1` |
| 训练域版本 | `scene-linear-sparse-pairs-v2` |
| 特征版本 | `relative-image-ev-grid-v1` |
| 排列 | 显示方向行优先采样位置；RGB 使用平面排列 |
| 采样、网格、特征 | 96×64；12×8，每格 8×8 点；776 维 |
| 亮度及 EV | `Y=0.2126R+0.7152G+0.0722B`；`log2(max(Y,1e-6)/0.18)` |
| 全局基准 B | EV 分布 5%–95% 的面积积分均值 |
| 高光参考 U | EV 分布 98%–99.5% 的面积积分均值 |
| 模型输入 | 未标准化 FP32 特征；冻结的均值／标准差放在模型内 |
| 输出 | 曝光补偿 C，正值增曝；误差尺度 s，单位 EV |

每格八项依次为：相对 B 的平均 EV、EV 标准差、相对 B 的 P10/P90、饱和比例、低信号比例、`log2 R/G`、`log2 B/G`。全局八项为 B、U−B、P90−P10、饱和比例、低信号比例、有效覆盖率、两种全局颜色比。细节以冻结的 `metering_features.py` 和应用契约测试为准。

应用契约保存在 `app/src/main/assets/global_metering_input_contract.json`。训练包同时冻结 `app-contract.json` 和 `application-sources.json`；后者记录十个应用输入相关文件的 SHA256。应用映射变化后，恢复入口会拒绝旧包，应建立新版本并重做数值对照，不原地改散列绕过检查。

## 3. 共享图源规则：两个模型共用原图，独立消费

共享池：`D:\Project\film2\training-data\liu4k-v2-pool`。

唯一登记簿：`D:\Project\film2\training-data\liu4k-v2-pool\continuous-registry.sqlite3`。实际路径也记录在共享控制目录的 `publication.json`。不要另建一套原图目录或重新下载相同原图。

1. 原图只采集、存储、去重一次；保留来源 URL、作者、许可及许可 URL、文件版本、SHA256、phash、分组与全局划分。原文件不重编码。
2. 沿用已批准的 LIU4K 作者来源和 Commons 中明确的 CC0-1.0／CC-BY-4.0 摄影图源。来源许可记录须完整；CC-BY 保留署名。当前自动准入核验许可记录、哈希、可解码性、尺寸、格式、重复和既有分组等，不代表逐图人工视觉验收。
3. 按原图身份、近重复、同场景与连拍组统一划分。任一模型固定验证／测试的原图及其组，其他模型都不能用于训练；新发现跨集合关系时先隔离，不静默移入训练。
4. 本次只纳入登记簿中 `accepted` 的 603 张训练图与 16 张固定验证图，冻结 619 份派生采样缓存。27 张隔离训练来源不纳入；16 张固定测试图保留，本次不读取测试像素或缓存。
5. 图池新增下载可以继续积累，但运行中 manifest 不热更新。新图通过准入后，在下一个版本化阶段纳入；同一阶段恢复沿用原 manifest、缓存和参数。
6. 两模型各自维护训练清单、标签映射、合成流程、checkpoint、优化器状态与消费游标。除尘标签、权重、优化器不并入测光；测光曝光标签也不作为除尘标签。
7. 当前适配阶段使用自己的不可变派生重放缓存，**没有复制原图**。历史原图若已由原共享流程合法回收，可复用身份可追溯的采样缓存，不为此恢复下载，也不清理这些缓存。
8. 原图回收只由原共享调度器执行：所有引用批次均提交并消费完成、没有读取占用后才允许清理；固定保留图与全部断点不清理。新 RAW 阶段的提交记录在其目录内，尚未自动登记为旧共享队列的消费完成记录；不能根据小试完成或适配日志推进，就手动释放共享原图或篡改旧消费游标。

同一 GPU 使用已有 `gpu0.lock` 串行调度。当前 RAW 适配工作进程是独立的测光阶段，尚未替换旧共享控制器的测光作业。恢复入口要求旧控制器／作业已退出；另一模型若需要训练，须先让本阶段在批次边界保存、退出并释放 GPU 锁，再切换，不同时启动两套控制器。

## 4. 从哪个断点继续

共享控制目录，以下简称 `$meterControl`：

```text
C:\Users\15449\.codex\worktrees\5b24\lightmeter a\outputs\shared-training-20261025
```

本次可恢复训练包：

```text
C:\Users\15449\.codex\worktrees\5b24\lightmeter a\outputs\metering-training\raw-adaptation-v2-global-mapping-20261028
```

以 `$meterControl\training-ops\metering-raw-prepared.json` 中的 `folder` 为当前指针。此前 `raw-adaptation-v2-20261028-ready` 等包保留为历史证据，未绑定当前应用映射，不作为本次启动包。

父断点为 `$meterControl\metering\metering-shared-stage-0014\last.pt`，**第 134 轮**，SHA256：

```text
4c1a9d6d79abd1dc27d19040e451058c07d176c09dda308c4f2a8945d5632dfa
```

训练包内 `parent.pt` 是完整保留副本。长期训练第一次从它载入，之后恢复使用本阶段 `last.pt`。`smoke/resume-test.pt` 仅用于恢复测试，不能拿它当长期训练起点。原第 39 轮最佳模型、旧导出、旧运行快照和用户暂停记录均保留。

## 5. 训练配方与继承关系

**网络和特征维度不变，全部 51,874 个参数继续训练；不从头随机初始化。** 继承原权重、776 维标准化、AdamW 动量与父随机数状态。由于输入域改变，本次建立独立微调阶段，重新调度学习率并按新域指标选择 best。

| 配置 | 当前冻结值 |
|---|---|
| 模型 | MLP 776→64→32→2，ReLU；补偿限制 ±4 EV |
| 优化器 | AdamW，weight decay 1e-4；保留父动量 |
| 学习率 | 1e-4，预热 2 轮，余弦降至 1e-5 |
| Batch / 精度 | 128 / FP32；不引入量化、AMP、TF32 |
| 预算 | 本阶段最多 30 轮、patience=10；累计上限 200 轮 |
| 曝光扰动 | 每 ROI 25% 为零，其余在 −3 至 +3 EV **连续**均匀采样 |
| 监督符号 | 模拟乘 `2^delta`，目标补偿 `−delta` |
| 损失 | Smooth L1，beta=0.25；误差尺度、曝光关系、同曝光跨域一致性各权重 0.1 |
| 运行限制 | CPU 两线程，CUDA 显存比例上限 20%；使用现有环境 |
| 保存 | 每 25 更新步、暂停时、完整 epoch 结束保存 |

每张原图固定 16 个 ROI，覆盖完整画面、1.5/2/4 倍中央与偏移裁切，全部继承原图划分。当前每轮共有 `603×16=9,648` 个 ROI 主样本，Batch 128 约 76 次更新；三条训练分支不算三个独立原图。ROI 继承参考曝光的监督含义保持不变。

合成过程为：修正 EXIF／ICC 并统一 sRGB → 逆 sRGB → 近似逆残余色调 → 在线性域乘 `2^delta` → 传感器增益／截断／噪声／12 位量化 → RAW 分支；同一次捕获经已知虚拟曲线、sRGB 和 8 位量化生成处理流分支，再做对应逆映射。同 ROI 另取第二个连续曝光，用于曝光关系约束。

近似逆残余色调使用可解析 smoothstep，它是合成假设。应用端使用实际元数据、现有曲线与校准，不把 smoothstep 当成所有设备的真实 ISP。当前真实 RAW／处理流配对数据为 **0**；后续可增加真实配对验证。训练标签是参考照片的曝光选择，不是绝对照度真值。

具体参数以训练包 `config.json` 为准。恢复同一阶段时不修改包内代码、配置、应用契约或缓存；需要改配方或纳入新图时建立新版本。

## 6. 接手会话的执行顺序

复用 `film-training-ops` 技能和 `$meterControl\training-ops\WORKFLOW.md`。唯一入口是已有 `ops.py`；不新写监视器、采集器或巡检任务，不升级共享 Python 环境。

在 PowerShell 中先定义路径：

```powershell
$meterWorkspace = 'C:\Users\15449\.codex\worktrees\5b24\lightmeter a'
$meterPython = 'C:\Users\15449\.codex\worktrees\dust-training\film2\.venv-training\Scripts\python.exe'
$meterControl = Join-Path $meterWorkspace 'outputs\shared-training-20261025'
$meterOps = Join-Path $meterControl 'training-ops\ops.py'

# 只读状态、记录健康基线、启动条件核验；不启动长期训练
& $meterPython -B $meterOps status
& $meterPython -B $meterOps check --record
& $meterPython -B $meterOps resume-metering-raw --dry-run

# 打开或复用现有统一终端监视器
& $meterPython -B $meterOps open
```

确认指针、父断点、应用散列、缓存和当前登记簿准入仍一致，且没有活跃控制器／其他 GPU 作业。预检如果拒绝，应根据具体错误处理，不删除锁文件或绕过散列。用户未明确授权开始时，到此停止。

**收到明确启动测光适配的指令后**，只执行：

```powershell
& $meterPython -B $meterOps resume-metering-raw
```

这个入口保留原全局 `PAUSE.json`，仅确认本次启动时已经存在的暂停记录，其他模型仍暂停。**不要用 `ops.py resume` 替代**：那个命令恢复的是旧共享队列和旧 JPEG 配方。也不要执行首次三种子实验的 `train.py run` 来恢复当前链。

启动后检查阶段目录的 `launch.json`、`training-status.json`、`last.pt`、`metrics.jsonl` 与 `stderr.log`，确认 PID 和步数实际推进。监视器每 3 秒读状态；“测光 RAW 域适配”显示新阶段，“JPEG 预训练链”仍保留旧第 134 轮记录，不能把旧行误读为新模型停滞或结果。

需要暂停本适配阶段时，写入／更新**阶段目录**的暂停标记，然后等待 `paused-checkpoint-saved` 和进程退出：

```powershell
$meterStage = (Get-Content -Raw -LiteralPath (Join-Path $meterControl 'training-ops\metering-raw-prepared.json') | ConvertFrom-Json).folder
@{ reason = 'user requested pause'; at = [DateTimeOffset]::Now.ToString('o') } |
  ConvertTo-Json | Set-Content -Encoding utf8 -LiteralPath (Join-Path $meterStage 'PAUSE.json')
```

全局或阶段暂停文件在启动后发生新增／变更，会在批次边界保存退出；若恰在完整验证中，等待验证及检查点提交。不要直接杀进程代替正常暂停。再次获得继续指令时使用同一个 `resume-metering-raw`，从该阶段最近 `last.pt` 精确接续模型、优化器、调度器、RNG、打乱顺序和批次游标。

## 7. 图池更新后的下一阶段

只在当前 epoch 完整提交后切换数据版本；如果 `last.pt` 有未完成的批次游标，先恢复同一阶段完成该轮。下一版本使用**尚不存在的新目录名**：

```powershell
# 示例：替换为本次实际的新版本名称，不能覆盖已有目录
$meterNext = Join-Path $meterWorkspace 'outputs\metering-training\raw-adaptation-v2-global-mapping-NEXT'
& $meterPython -B $meterOps prepare-metering-raw --adaptation-folder $meterNext
& $meterPython -B $meterOps smoke-metering-raw
& $meterPython -B $meterOps resume-metering-raw --dry-run
```

准备入口会接续当前适配阶段已保存的完整 epoch `last.pt`；只有尚无适配断点时才使用旧第 134 轮。新的冻结 manifest 重新核验登记簿准入与分组；测试图像仍不读取。新目录准备会更新当前指针，故不要在旧阶段仍运行时切换指针。长期启动仍需要明确指令。

## 8. 评价与接手时的起点

固定验证为 16 张原图 × 16 ROI × 9 曝光，共 2,304 个相关样本；曝光为 −3/−2/−1/−0.5/0/+0.5/+1/+2/+3 EV。MAE 先在每张原图内汇总，再对原图宏平均，不把派生样本数量当成独立场景数。

当前父权重、适配训练前的基线如下，完整报告在阶段 `smoke/report.json`：

| 验证域 | 全量 MAE / EV | 样本 P90 / EV | 最大误差 / EV |
|---|---:|---:|---:|
| 原 JPEG 配方 | 0.5766 | 1.3203 | 3.4933 |
| 模拟 RAW | 0.5969 | 1.3091 | 2.7485 |
| 模拟预览 | 0.7453 | 1.5868 | 5.1071 |

同曝光 RAW／处理流预测差 MAE 为 0.2417 EV。以上是**微调前、合成验证**，不能当作新训练的收益或手机实测精度。

每轮同时保留三个域的全量 MAE、P90、最大值和跨域差；以模拟 RAW／模拟处理流全量 MAE 的均值选择新阶段 best，旧 JPEG 指标用于检查遗忘。另比较模型、项目全局 RGB 单帧统计核、平均 EV、平均亮度再取 log2，在同组可靠样本上显示误差与覆盖率；统计基线只用训练参考拟合固定偏移。训练包的 603 张训练锚点与旧对照的历史锚点不同，比较时不混用两份表。

长期阶段结束后，另做完整验收报告：有效样本 MAE≤0.25 EV、P90≤0.5 EV、原图平均补偿偏差绝对值≤0.1 EV，报告超过 1 EV 比例、饱和／低信号分组、可信度覆盖率及四种算法对照。当前适配运行器每轮尚未输出上述全部验收项，不能用其简表代替最终报告。测试集只在模型与规则固定后使用一次；本次准备和小试没有使用测试像素。

## 9. 已完成验证与产物

- Android：696 项测试通过，120 个套件，零失败；Debug 构建与三个 ABI 的原生库编译通过；Lint 为零错误、68 条警告。未进行实体手机测试。
- 原生稀疏采样：CFA 排列、逐通道黑电平、奇数边界 ROI、旋转／镜像、缓冲区范围测试通过。
- Python：16 项 RAW 域／划分隔离／AdamW 与批次中途恢复测试通过。
- 跨语言：六组实际 Kotlin 特征与 PyTorch 对照通过；最大特征差 `6.1035e-5`，同一父权重下最大补偿差 `0.00000811 EV`。这是输入实现一致性，不是测光准确率。
- 当前训练包 CPU 小试：两批更新、保存后第三批精确重载一致；父权重、标准化、AdamW 动量保持；启动 dry-run 通过。长期训练没有启动。

验证记录：`outputs/global-mapping-20261028/app-verification.json`、`feature-model-parity.json` 及当前阶段的 `smoke/report.json`。可安装包：`app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`，以及同目录 universal 包。

后续新权重完成验收后，再按既有导出要求生成 safetensors、ONNX opset 17、特征／标准化、来源记录与报告，核对 PyTorch／ONNX 补偿差≤0.001 EV。当前适配训练器负责 checkpoint 与验证，尚未自动完成新的适配模型导出和 Android 推理接入。手机端 1／3 Hz 的总延迟、RAW 与预览并流及功耗仍须实机测量。

代码继续 Apache-2.0，独立实现，不导入另一任务的 GPL 训练代码；共享数据按各自许可保存署名和来源。此次没有更改项目许可证，也没有打包原图进入模型。

## 10. 可直接发给接手会话的话

> 请接手本机全局测光模型的共享图源续训准备。先阅读 `C:\Users\15449\.codex\worktrees\5b24\lightmeter a\docs\research\GLOBAL_METERING_TRAINING_HANDOFF_2026_10_28_ZH.md` 和其中引用的统一 WORKFLOW，沿用既有 ops 入口、图池、登记簿和 GPU 锁。当前包是 `raw-adaptation-v2-global-mapping-20261028`，父模型第 134 轮，603 张训练图和 16 张固定验证图；从现有权重及 AdamW 状态继续，不从头训练。原图共用，两个模型的标签、权重、优化器与游标独立。先核验实时指针、冻结散列、固定集隔离和恢复预检，并报告状态；未收到明确启动指令前不要恢复长期训练、除尘或采集器。需要新图时在完整 epoch 边界建立新版本，不删除缓存、不启动旧 JPEG 队列或重复监视器。
