# 测光RAW域适配：实现与恢复训练准备

日期：2026-10-28。当前交付为训练侧适配和可恢复训练包，长期训练尚未启动。

同日追加：全局模式的 Android RAW／YUV／预览输入映射已实现，当前包更新为 `raw-adaptation-v2-global-mapping-20261028`，冻结应用契约及十个输入相关文件散列。小批恢复与启动预检已通过。接手会话请以[完整训练交接规范](<C:/Users/15449/.codex/worktrees/5b24/lightmeter a/docs/research/GLOBAL_METERING_TRAINING_HANDOFF_2026_10_28_ZH.md>)为当前入口；此前未绑定应用的 `raw-adaptation-v2-20261028-ready` 包保留但不用于本次启动。

## 1. 可以直接在现有权重上继续训练

本次从最新第134轮 `metering-shared-stage-0014/last.pt` 建立独立适配阶段。父断点SHA256为：

```text
4c1a9d6d79abd1dc27d19040e451058c07d176c09dda308c4f2a8945d5632dfa
```

网络仍是776→64→32→2，51,874参数。全部权重、原776维均值/标准差、AdamW动量和父随机数状态都载入，没有重新随机初始化网络。

数据配方改变，因此这是带明确父断点的新微调阶段：学习率改为1e-4，预热2轮，余弦降至1e-5；新阶段最多30轮、patience=10，仍遵守累计200轮上限。优化器动量保留，学习率调度从新阶段开始。新域指标独立选择best，不拿旧JPEG配方的历史最佳直接作为早停门槛。

原第39轮最佳模型、首轮ONNX、最新第134轮完整断点和暂停标记保留。

## 2. 数据生成改了什么

新模块 `metering_domains.py` 独立实现了曝光前的近似逆处理、已知虚拟ISP和稀疏输入适配：

```text
共享JPEG采样
  → 逆sRGB编码
  → 近似逆残余色调曲线
  → 线性参考
  → 乘2^delta
  → 模拟传感器增益、截断、噪声和12位量化
       ├→ 统一线性RGB → RAW分支特征
       └→ 已知色调曲线、sRGB编码、8位量化
            → 对应逆处理 → 预览分支特征
```

这里采用可解析逆的smoothstep作为参考图残余色调的近似；虚拟处理链另随机化曲线指数。它是可复现的起步合成模型，不是对所有JPEG原始ISP的估计。后续真实配对数据可继续补充。

同一次模拟捕获的RAW与处理流共用噪声实现；两路的差别包含处理曲线和量化。曝光始终在线性阶段改变，不在最终JPEG亮度上直接乘倍数。

训练每个ROI产生两种连续曝光a、b，并运行RAW-a、预览-a、预览-b三路。主补偿和误差尺度损失三路平均；保留曝光差一致性，新增同曝光RAW/预览补偿一致性，权重均为0.1。25%零扰动、其余[-3,+3]连续采样仍保留。

目前仍沿用“原参考曝光合适，ROI继承原图参考”的标签含义，本轮没有改为新的裁切偏好或主体标注任务。

## 3. 输入适配接口与校准

训练侧已有两个可测试的实拍样本转换接口：

- `raw_rgb_to_linear`：输入稀疏采样得到的相机原生RGB，使用黑/白电平、白平衡、颜色矩阵和已有设备EV校准值转到统一线性RGB。CFA采样器需先正确处理两种绿色位置的黑电平；该函数不直接解码整幅Bayer文件。
- `processed_rgb_to_linear`：使用已逆转的相机曲线LUT和已有响应校准点；没有LUT时可用sRGB回退。提供完整逆曲线时不会再重复做gamma反解。

RAW沿用现有设备线性校准，不增加要求用户逐设备完成的神经网络专用标定。统一坐标保留全局亮度，不按每张图的平均亮度重新归一化。

这些Python接口定义训练/部署应遵守的数值契约。Android全局测光入口已对应实现稀疏RGB映射与776维特征，其他模式保留原入口；目前应用仍用原全局统计策略提供读数，新模型推理接入待适配权重完成后进行。

## 4. 数据与训练包

最终训练包：

```text
outputs/metering-training/raw-adaptation-v2-global-mapping-20261028/
  prepared.json             父断点、版本、预算和文件散列
  parent.pt                 原始完整父断点的保留副本
  config.json               新阶段冻结配置
  app-contract.json         全局测光应用输入契约
  application-sources.json  应用输入相关文件散列
  LICENSE
  code/                     独立训练代码与应用统计核快照
  data/manifest.json        来源、许可、SHA、phash、分组和划分
  data/cache/*.npy           只读派生采样重放缓存
  baseline-anchors.json     仅在训练参考上拟合的统计基线锚点
  smoke/report.json         CPU小批与恢复验证报告
  smoke/resume-test.pt      小试恢复用断点，长期训练不会把它当起点
```

当前训练包含603张已准入训练原图对应的采样、固定16张验证图，共619份采样缓存；27张已隔离训练来源未准入。不读取测试图像/测试缓存，不重复下载，不复制原始图片。复用已核验缓存；缺少采样缓存且原图仍存在时，校验原图SHA后生成同样的16 ROI采样。

为适配阶段保留自己的派生重放缓存，后续共享原图回收不会让这个阶段突然失去旧样本。新下载图在下一阶段生成新版本训练包时纳入，正在训练的manifest不热改。

准备下一阶段时，指定新的 `--adaptation-folder`；若上一适配阶段已有完整epoch断点，自动以其 `last.pt` 接续，而不会重新回到旧JPEG第134轮。未完成的epoch应先在原阶段恢复，保留批次游标。

## 5. 验证及监视

固定验证同时保存三组全量平均误差、P90和最大值：旧JPEG、模拟RAW、模拟预览。两种模拟来源另报告同曝光预测差。每组均有模型、项目全局统计核、平均EV、平均亮度四种方法的同组对照及覆盖率；基线锚点仅由训练参考拟合。

长期微调开始后，每轮写 `metrics.jsonl`、`last.pt`；新最佳写 `best.pt`。批次中途保存模型、优化器、调度器、随机数、当前打乱顺序和游标，最近完整验证保存在断点与状态中。每25个更新步及暂停时保存，epoch结束也保存。

当前已通过16项测试，包括曝光符号与线性关系、黑白电平/颜色增益/旧校准复用、曲线LUT避免重复gamma、分组/phash隔离、与独立全局算法移植的数值对照、AdamW恢复，以及批次中途暂停后与不中断训练完全一致。

真实第134轮权重的CPU小试进行两批更新，再验证保存后第三批更新是否逐项一致。小试权重仅留在smoke目录，未来长期训练从原第134轮父权重起步。

训练前基线约为：旧JPEG 0.5766 EV、模拟RAW 0.5969 EV、模拟预览0.7453 EV；两模拟来源平均预测差0.2417 EV。这些是适配前的起点，不是适配后的收益。当前真实RAW配对数据数量为0，实拍RAW精度在有配对数据后另测。

统一监视器和检测仍复用 `training-ops/ops.py`，增加适配包状态、恢复验证结果和新域平均误差/对照。旧JPEG链记录明确标为旧链，不把模拟RAW数字当作手机RAW实测。

## 6. 恢复入口

在仓库根目录使用现有环境：

```powershell
$meterPython = 'C:\Users\15449\.codex\worktrees\dust-training\film2\.venv-training\Scripts\python.exe'
$meterOps = 'outputs\shared-training-20261025\training-ops\ops.py'

# 只校验是否可恢复，不启动训练
& $meterPython -B $meterOps resume-metering-raw --dry-run

# 用户明确要求开始时，只恢复测光RAW适配
& $meterPython -B $meterOps resume-metering-raw
```

后一命令是独立的测光恢复入口，不使用旧队列的 `resume` 来启动旧JPEG配方。它检查旧控制器/作业已退出、当前父断点、冻结文件、来源准入和缓存身份，再使用同一把 `gpu0.lock` 串行获得GPU。适配阶段另有进程锁，重复启动不会并行训练同一阶段。

旧全局暂停文件保留；显式的测光恢复仅确认启动时已经存在的暂停状态，其他模型保持暂停。启动后新增或改写全局/适配阶段的 `PAUSE.json` 会在批次边界提交断点并退出；不清理原暂停记录。恢复同一阶段会继续其最新 `last.pt`，不会重复从父权重开始。

当前已完成准备及CPU验证，没有执行上述长期恢复命令。

## 7. 代码入口

- `tools/metering-training/metering_domains.py`：输入转换与虚拟处理链。
- `tools/metering-training/raw_adaptation_data.py`：共享准入数据、隔离检查与派生重放缓存。
- `tools/metering-training/raw_adaptation_evaluation.py`：训练集统计锚点和跨域对照。
- `tools/metering-training/raw_adaptation.py`：冻结、CPU小试、训练与精确恢复。
- `tools/metering-training/raw_adaptation_config.json`：新阶段参数。
- `tools/metering-training/test_raw_adaptation.py`：16项契约及恢复测试。
- `tools/shared-training/raw_adaptation_ops.py`：统一ops入口的调度适配。

代码独立编写，继续采用Apache-2.0，未引入外部GPL模块、预训练权重或新的下载来源。
