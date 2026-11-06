# 参考曝光监督的轻量测光预训练

2026-11-02最新交接：[测光／曝光预览现状及五项待处理问题](../../docs/research/METERING_EXPOSURE_PREVIEW_HANDOFF_2026_11_02_ZH.md)。当前应用已使用第219轮模型，生产基线为main的8154bac；本阶段先处理应用测光与预览问题，不据下文历史记录自动恢复训练。

2026-10-30：[第219轮模型已接入全局测光设备测试](../../docs/research/GLOBAL_MODEL_EPOCH219_DEVICE_TEST_2026_10_30_ZH.md)。应用采用约209 KiB的FP32本地MLP，保留ONNX导出与PyTorch对照，源码入口为 `export_android_model.py`。以下早期准备状态保留为历史记录。

此工具独立于 Android 构建。把原图作为曝光合适的弱参考，通过线性域的曝光扰动训练正负 EV 补偿；不预测绝对 EV100，不生成图像，不代表已经验证真实 RAW/YUV 的通用性。

当前模型的完整搭建说明见 [从图像到曝光补偿的完整实现](<C:/Users/15449/.codex/worktrees/5b24/lightmeter a/docs/research/CURRENT_METERING_MODEL_ARCHITECTURE_ZH.md>)，包含ROI采样、776维特征、网络、损失、持续训练和推理流程。

**当前交接入口（2026-10-28）**：[全局测光应用映射与共享图源续训规范](<C:/Users/15449/.codex/worktrees/5b24/lightmeter a/docs/research/GLOBAL_METERING_TRAINING_HANDOFF_2026_10_28_ZH.md>)。应用已接入全局模式专用的 RAW／YUV／预览映射与 776 维输入，现有统计策略仍提供读数。当前包为 `raw-adaptation-v2-global-mapping-20261028`；已绑定应用输入散列，通过 CPU 小试和恢复预检，长期训练尚未开始。下面的首轮实验命令属于历史流程，不用于启动当前共享续训。

2026-10-28新增[RAW域适配及恢复准备](<C:/Users/15449/.codex/worktrees/5b24/lightmeter a/docs/research/RAW_ADAPTATION_IMPLEMENTATION_2026_10_28_ZH.md>)：保留第134轮权重与AdamW状态，在线性阶段施加曝光、训练RAW/处理流配对；603张训练原图与16张固定验证图已冻结，长期训练由统一 `ops.py resume-metering-raw` 显式启动。

2026-10-25 已完成三个种子的首轮训练和导出，21 项测试通过；整体/有效测试 MAE 为 0.5339/0.4335 EV，尚未通过精度验收。实现、指标和最终产物入口见 [首轮结果](../../docs/research/REFERENCE_EXPOSURE_PRETRAINING_RESULT_2026_10_25_ZH.md)。最终输出为 `outputs/metering-training/reference-v1-20261025-verified/`，首次运行记录也保留，未删除缓存或原图。

代码为 Apache-2.0，独立实现，没有复制或导入另一项目的 GPL 训练模块。图片依原有 CC0/CC-BY 许可使用，原始来源、作者、许可、版本和散列完整保留在缓存索引及导出 provenance 中。训练权重不包含原图文件。

## 环境与执行

使用现有 Python 环境中的 torch、numpy、Pillow、safetensors、onnx、onnxruntime；工具不安装/升级共享环境。实际版本、CUDA 和 GPU 信息写入每个实验的 environment.json。以下 PowerShell 示例在仓库根目录执行：

```powershell
$meterPython = 'C:\Users\15449\.codex\worktrees\dust-training\film2\.venv-training\Scripts\python.exe'
& $meterPython tools/metering-training/test_training.py
& $meterPython tools/metering-training/train.py run `
  --manifest outputs/metering-research/shared-pool/manifest.jsonl `
  --cache outputs/metering-training/cache-v1 `
  --output outputs/metering-training/reference-v1-20261025
```

`run` 先准备/核验缓存，再依次训练三个种子，按验证集选择模型，固定可信度规则，最后一次性计算测试指标并导出。中断后执行同一命令会从最后完整 epoch 的模型、优化器、调度器和 Python/NumPy/Torch/CUDA RNG 状态恢复；代码、配置或数据改变时拒绝静默续训。已完成的种子只核验并复用，已生成最终报告的实验拒绝重复调参运行。采集任务保持原暂停状态。

单独准备缓存：

```powershell
& $meterPython tools/metering-training/train.py prepare `
  --manifest outputs/metering-research/shared-pool/manifest.jsonl `
  --cache outputs/metering-training/cache-v1
```

独立重现某个 checkpoint 的指标（默认验证集）：

```powershell
& $meterPython tools/metering-training/train.py evaluate `
  --cache outputs/metering-training/cache-v1 `
  --checkpoint outputs/metering-training/reference-v1-20261025/seed-20261025/best.pt `
  --output outputs/metering-training/reproduced-validation.json
```

## 数据和特征契约

原图只读，训练前逐图核验 SHA256。继承既有 231/16/16 原图划分，拒绝跨 split 的 ID、SHA256 或组重复；不跨 split 生成增强。登记簿和采集器不在本工具的修改范围。共享池仍须保持暂停，直到另行接入双消费者回收保护；本工具不会安装保留机制，也不会删除任何原图或派生缓存。

EXIF 方向先修正；有 ICC 时转换为 sRGB，无 ICC 的普通 RGB/灰度图按 sRGB 假设记录，无法解释的颜色模式/配置直接失败。每张图取完整 ROI、1.5/2/4 倍中央及四角裁切，共 16 个。裁切保留源画幅比例，在每个 ROI 的归一化坐标均匀取 96×64 个原尺寸 RGB 点；不创建整幅浮点图，不通过缩略图插值混入 ROI 外像素。采样缓存为 uint8，保持解码后点值，避免额外 JPEG 重压缩。

每次扰动先 sRGB 解码，再乘 `2**deltaEV` 并截断到 [0,1]；目标为 `-deltaEV`。25% 输入不扰动，其余在 [-3,+3] 连续均匀采样。每个训练 ROI 每 epoch 作为一次主样本，并额外生成同参考 ROI 的第二个曝光，用于一致性约束；它不是额外独立场景。

12×8 网格各含 8×8 样本，每格八特征加八全局统计，共 776。亮度为线性 Rec.709 系数，EV 为 `log2(max(Y,1e-6)/0.18)`，保留全局曝光位置。区域 EV 均值不等于对平均亮度取 log；B 用 5%–95% EV 分布的精确面积积分，U 用 98%–99.5%。没有去掉饱和/低信号面积。详见 metering_features.py 和导出的 features.json。

本轮图库不保证全部是未经后期的相机原片；“原图合适”是训练假设，不是对每张图进行过专业曝光审定。8 位图片的反变换不能复原 HDR/局部色调、已丢高光或真实传感器噪声。

## 模型、选择和指标

MLP 为 776→64→32→2，隐藏层 ReLU，51,874 个可训练参数、51,776 MAC。输出 `4*tanh(z0/4)` EV 补偿和 `min(softplus(z1)+0.05,4)` EV 误差尺度。特征固定标准化在图内，统计只来自训练集的 -2/0/+2 EV 样本。用户原本合适的暗调/亮调原图均以零补偿监督，不逐图强制平均灰。

主损失为两路 Smooth L1（beta=0.25）；误差尺度通过停止补偿残差梯度的 Laplace 形式损失学习，权重 0.1；两路均满足有效质量条件时施加补偿差一致性损失，权重 0.1。不能把误差尺度直接视为概率。验证集仅做尺度乘数校准和覆盖率阈值选择，不使用测试集。

全部超参数在 config.json。CUDA 显存预算为设备容量的 20%，CPU 两线程；默认 FP32、不启用 TF32/AMP，采用确定性算子。不会结束、暂停或恢复其他 GPU 进程。

固定评估档数为 -3/-2/-1/-0.5/0/+0.5/+1/+2/+3，每个测试原图有 16×9 个相关样本。MAE、偏差和大于 1 EV 比例先按原图汇总再宏平均；样本 P90 是描述性尾部指标，同时报告逐原图 MAE P90。有效样本定义为饱和面积≤5%、低信号面积≤20%，所有样本及困难分组始终另报，不将其删出数据集。可信度阈值在验证有效样本上寻找满足 MAE≤0.25、P90≤0.5 的最大覆盖（至少 80%）；若无法满足，记录不可行而不隐藏失败。

模型比较零补偿和“训练集原参考 B 中位数减当前 B”的统计基线。按所有验证样本的原图宏平均 MAE 选最佳 epoch/种子，测试只在选择之后运行。验收包含有效样本 MAE≤0.25、P90≤0.5、原图平均补偿偏差绝对值≤0.1、优于简单亮度基线、ONNX 对照≤0.001 EV；未通过仍保留最佳模型和报告。

## 产物与接入边界

实验目录保存冻结配置/源码快照、数据散列、逐 epoch 历史、best/last checkpoint、三个种子的完成记录、selection.json、逐测试样本 CSV、逐原图 JSON，以及 REPORT_ZH.md/report.json。

export/ 保存 safetensors、opset 17 ONNX、特征定义、标准化参数、可信度校准、数据来源和 model-card.json。模型输入是未标准化的 float32 `[batch,776]`，输出 `[batch,2]`；导出模型已包含标准化和误差尺度乘数。仅网络可执行，不包含图像特征提取。

CPU 基准包含网络与归一化，不包含读图/取样/特征计算，更不是 Android 功耗或延迟结论。未来应用适配必须保持这个“当前图像相对曝光”特征域，不能直接喂入绝对场景 EV100；曝光补偿转换为外部相机 EV 仍依赖真实逐帧曝光元数据和来源校准。本次不做该接入。

## 持续训练的同组测光对照

2026-10-27 增加 CPU 离线对照，读取共享控制器当前断点及实际运行时的特征/模型定义，不启动训练，不使用测试集：

```powershell
& $meterPython -B tools/metering-training/test_metering_baselines.py
& $meterPython -B tools/metering-training/compare_metering.py `
  --root outputs/shared-training-20261025 --if-stale
```

产物为共享控制目录的 `metering-comparison/report.json`、按轮数/断点SHA留存的JSON和 `calibration.json`。统一 `training-ops/ops.py check --record` 已接入此命令，监视器只读结果，轮数/代码变化时标记过期。只比较固定验证集；原文的首次训练/共享池暂停说明属于2026-10-25首轮流程，当前持续训练状态以统一 WORKFLOW 和实际控制器为准，不能用首次实验命令重启共享训练。

对照的四种方法为当前MLP、应用全局测光统计核、EV域完全平均、亮度域平均后取log2。基线各自只在冻结训练参考上拟合一个标量EV锚点，验证图不逐图校准；共同可靠子集、覆盖率、模型全量MAE和各方法全量数值诊断均保留。全部方法使用相同6144点/ROI。应用核心固定源码SHA，变化时须检查移植与测试；6项契约测试覆盖百分位边界、亮端限制、补偿符号、可靠性和两种平均的区别。

本对照是共享显示线性RGB代理上的单帧策略比较，饱和判定也统一用线性通道阈值；不等于应用完整RAW/YUV流程、编码域判定、响应校准或绝对EV100精度。参考照片的曝光意图也不等于物理标准灰目标。详细结果与资料核实见 [测光对照与相机研究](../../docs/research/METERING_BASELINE_AND_CAMERA_RESEARCH_2026_10_27_ZH.md)。
