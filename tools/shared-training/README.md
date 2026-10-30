# 两种模型共享数据与串行 GPU 队列

此工具只协调共享原图和各自的训练进度，不修改 Android 测光/RAW/预览。
原图池与唯一来源台账为 `D:/Project/film2/training-data/liu4k-v2-pool/continuous-registry.sqlite3`。
在原数据库追加 `shared_*` 表；保留旧 `originals/settings/batches` 及其历史。
两种模型各自拥有标签、合成方法、manifest、checkpoint、优化器、随机状态和消费游标。

- `audit.py` 检查可读原图的 SHA-256、原始 phash、解码、来源/许可字段、EXIF，并生成质检联系表。
- `prepare_shared.py` 固定本次人工质检与同场景分组，冻结两套清单。它是本次审计的构建步骤，不应在训练启动后直接覆盖旧审计。
- `validate.py` 在独立分支验证完整恢复、小批更新和固定验证复现；不提升分支为正式断点。
- `publish.py --apply` 是一次性、暂停期间的追加迁移；已完成后再次调用会拒绝覆盖。
- `ledger.py` 拒绝固定验证/测试进入训练。只有两种消费者的全部引用都已提交并消费完成，才可满足回收资格。本轮不启用实际自动回收。
- `freeze_runtime.py` 冻结本项目训练/调度代码及配置。另一个项目的 GPL V8 快照只校验和引用，不复制进来。
- `coordinator.py` 持有旧控制器锁，通过同一个 GPU 锁串行调度。除尘从原 `last.pt` 继续；后续原图批次通过原项目的完整状态续训 API 衔接。保持 V1 修复架构，不切换未训练的 V2。
- `metering_job.py` 从第 64 epoch 完整断点开始共享数据续训，保留标准化、AdamW、学习率日程和 RNG。每个共享数据版本最多 10 epoch，5 epoch 无验证改善结束此阶段；总预算仍为 200。中途暂停会保留同一阶段及其精确父断点，不新建一次训练。固定测试集不反复用于调参。
- `collector.py` 调用现有单一采集器；新原图先进入 pending 审查队列，最多约 32 张加在途请求。许可/去重后的下载也不能绕过场景及质检审核直接训练。
- `monitor.py` / `start-monitors.ps1` 打开用户要求的两个独立终端，仅观察，关闭终端不停止训练。

监视窗口显示中文状态与进度条：除尘分别展示已提交的批次访问进度、累计独立原图目标、检测/修复步数、最近损失、实时合成/优化/验证阶段和粗略剩余时间；测光展示累计轮数预算、当前阶段轮数、验证 MAE 和提前停止/等待新数据的原因。两个窗口均显示 GPU 占用方、最近断点时间、采集等待原因、缓存用量与待审数量，按北京时间每 3 秒刷新。监视器只读取小型 JSON 和最近最多 128 KiB 日志，不加载模型权重，不使用 GPU，不读取全图。旧 `stream-summary.json` 不用于判断实时运行状态。

Windows 桌面上用 `start-monitors.ps1 -ReplaceExisting -Python <已有Python> -Root <控制根目录>` 更新窗口。它只结束本脚本创建的监视器及其子进程，使用 Windows Terminal 的两个独立新窗口；不会重启训练或改写冻结训练源码。查看静态快照可用 `monitor.py --root <控制根目录> --consumer dust --once`（或 `metering`）。

复核命令（使用现有环境，不升级包）：

```powershell
& $python -B tools/shared-training/test_shared.py
& $python -B tools/shared-training/freeze_runtime.py --root outputs/shared-training-20261025
```

用 `start-queue.ps1` 显式提供已有环境、审计根目录、原图池、除尘项目根目录、GPL V8 快照、测光完整父断点和已有 ROI 缓存。控制器要求 `authorization.json` 及全部通过的审计/恢复报告，先备份两个正式父断点，再解除两个已有暂停标记。不会删除原图或共享缓存。

如需暂停，在控制根目录写入 `PAUSE.json`（任意 JSON 对象）。训练会在可提交边界保存；不要以杀进程代替暂停。恢复前检查暂停原因，保留/移走该控制文件，再启动原队列。磁盘剩余不足 4 GiB 或工作进程失败会暂停。下载仍沿用原 8 GiB 缓存上限，空间不足时等待，不为继续下载而删图。

代码文件由本项目独立编写，采用 Apache-2.0。外部除尘实现保持 GPL-3.0-only；调用或与其组合分发时须遵守适用的 GPL 条件，不能因代码未复制就宣称组合可仅按 Apache 分发。Apache-2.0 与 GPLv3 的兼容方向见 [Apache 官方说明](https://www.apache.org/licenses/GPL-compatibility)。Android 工程许可证本轮未改变。原图和模型来源记录独立于代码许可证；CC BY 原图保留逐文件来源、作者和许可信息。

`outputs/` 包含忽略的原图派生缓存、审计证据、备份、权重和正在运行的队列状态，不能依靠 Git 提交保留；不要在这些产物仍使用时归档/清理当前工作树。
