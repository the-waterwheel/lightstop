# 第219轮全局测光模型：应用接入与设备测试

日期：2026-10-30。此次将全部本地分支合入main，并将用户指定的第219轮模型接入全局测光。界面中的全局模式继续使用保存标识 `AVERAGE`，RAW点击测光及现有预览／YUV连续全局测光均使用同一模型。

## 模型身份与运行方式

采用 `outputs/metering-training/raw-adaptation-v3-epoch200-to260-20261030-v2-io/best.pt`。断点字段 `epoch=219`，SHA256：

```text
c548b23435c5bb7f383020a731da96abf1835d13098274988db36d066913b0db
```

同目录的 `last.pt` 是第234轮，本次没有用它替代第219轮。此次不启动或修改训练。

网络仍是776→64→32→2，51,874参数、51,776 MAC。应用使用独立实现的FP32全连接推理，不增加通用推理引擎依赖；导出时另生成ONNX opset 17与safetensors供复现。每次测光只运行一次网络，不做在线训练。

APK内 `global_metering_epoch219.bin` 共213,740字节，约209 KiB，包含全部权重、冻结的776维标准化和误差尺度因子。SHA256：

```text
1d4f4b5528878f78093e34ba9fb9b10b57d6a7b6e862917a49d1cd15dd5f2dbd
```

构建任务和运行时均校验权重；加载时检查219轮、特征／输入域版本和完整性。模型只加载一次，预测不读文件。缺失或损坏时记录错误并使用现有全局统计策略；缺少有效RAW颜色元数据时同样使用统计路径。

## RAW映射与裁切

保留已有黑／白电平、四通道白平衡、阴影校正、Camera2颜色矩阵及设备EV校准。映射保留画面整体亮度，已有校准只应用一次。YUV／预览复用现有逆曲线及来源响应，不重复反解gamma。

全局输入仍为96×64采样、12×8分区、776维特征。合并取景框分支后，显式 `sensorCrop` 优先决定RAW和YUV实际测光范围；没有显式范围时沿用预览视口、画幅和变焦推导。模型网格始终按显示方向排列，旋转、镜像与裁切分开处理，避免数码变焦重复应用。

原生采样测试覆盖CFA、逐位置黑电平、奇数边界、缓冲区偏移／步长／范围、旋转镜像、暗场、饱和与阴影校正。Kotlin／PyTorch特征对照继续核验全部776项。

## 曝光补偿如何变成应用读数

模型输出C，正值表示增加曝光。先用该帧实际ISO、快门和光圈计算相机曝光坐标：

```text
cameraEV100 = log2(N² / t × 100 / ISO)
模型建议EV100 = cameraEV100 − C
最终曝光EV100 = 模型建议EV100 − 用户补偿
```

例如当前相机EV100为12，模型输出+1档，建议EV100为11；用户再增加1档补偿，最终为10。不会再叠加原全局算法最多半档的高光修正。高光统计仍保留用于质量评估和RAW重拍。

`sceneEv100`保留原来的场景统计值，模型建议以有符号的自动曝光偏移进入既有曝光计算和预览流程。`EvaluativeMeteringResult.modelEpoch=219`可辨认模型读数；另保留预测补偿与误差尺度。模型误差尺度不当作准确概率。

RAW重拍时，每个模型预测先结合自身那一帧的曝光元数据换算成建议EV100，再在多帧间取中位数；不把不同曝光帧的C直接平均后配上另一帧元数据。短曝光恢复高光时，保留参考帧的模型预测和元数据。模型建议不作为设备校准锚点。

## 数值验证与性能范围

- 合并后的806项测试通过（139套件，零失败／错误／跳过）；Debug四种APK、三个ABI原生库和真机测试APK构建通过，Lint为零错误、69条警告。
- 18组实际网络对照包含黑／白／梯度、RAW颜色映射、处理曲线、校准修正及标准化范围内随机输入。Kotlin与PyTorch最大补偿差约0.0000023 EV；ONNX与PyTorch最大差约0.0000012 EV，均低于0.001 EV。
- 另用第219轮权重核对六组实际Kotlin映射输出与Python特征，最大补偿差约0.0000128 EV，低于0.001 EV。
- 已记录的桌面JVM网络推理平均约0.035 ms；只统计标准化和MLP，不含相机读出、6,144点特征计算或完整现有全局统计流程，也不作为手机实测耗时。
- 自动测试覆盖补偿正负方向、逐帧曝光元数据、多帧融合、不叠加旧高光策略、用户补偿只应用一次，以及真实APK资产加载和全局分析入口使用模型。
- 详细自动验证结果与APK身份见 `outputs/global-model-epoch219-20261030/verification.json`。当前ADB没有连接设备，因此没有声称完成实拍验收。

第219轮已保存的固定验证全量指标为：模拟RAW MAE 0.4363 EV、P90 0.9826 EV；模拟预览MAE 0.4444 EV、P90 1.0052 EV。这里仍是16张独立验证原图的合成输入评价，不是手机实拍误差。

## 安装与测试

Debug APK位于 `app/build/outputs/apk/debug/`，优先选择 `app-arm64-v8a-debug.apk`；不确定ABI时使用universal。连接设备后覆盖安装会保留已有应用数据：

```powershell
adb install -r "app/build/outputs/apk/debug/app-arm64-v8a-debug.apk"
adb logcat -s GlobalMeteringModel
```

启动相机时应看到 `Loaded FP32 metering epoch 219`。选择全局测光，分别检查RAW点击测光、YUV／预览连续测光、1／3次每秒的现有刷新配置、选区与数码裁切。对比均匀墙面、普通明暗场景、逆光与强高光；特别观察切换来源后的读数、用户补偿方向以及RAW重拍后的稳定性。其他测光模式继续走其原有统计流程。

## 导出复现与许可记录

```powershell
& 'C:\Users\15449\.codex\worktrees\dust-training\film2\.venv-training\Scripts\python.exe' -B `
  tools/metering-training/export_android_model.py `
  --checkpoint outputs/metering-training/raw-adaptation-v3-epoch200-to260-20261030-v2-io/best.pt `
  --workspace . --output outputs/global-model-epoch219-20261030/export
```

导出目录包含ONNX、safetensors、标准化、特征说明、导出对照及 `provenance.json`。来源记录继承共享训练清单的作者、许可、URL、SHA256、phash与划分；不打包原图。代码继续Apache-2.0，独立实现，没有引入另一个任务的GPL训练代码。

## 分支整合记录

保存了本工作树的输入映射／训练工具，以及独立测光工作树原有的RAW兼容修复，然后合并取景框、连续测光、曝光预览和界面更新。保留取景框的显式sensorCrop与本任务的RAW旋转／镜像映射，两侧逻辑同时生效。

旧 `codex/release-0.6.0` 及其祖先分支已由整理提交进入main；`git diff 2c95c7a 21579aa`为空，证明两份发布源码树相同。本次补齐其合并祖先关系时保留main后续修复，避免旧版本覆盖新实现。全部分支合并后以 `git branch --no-merged main` 和远端引用核验；原分支及主工作树的其他未跟踪文档保留。
