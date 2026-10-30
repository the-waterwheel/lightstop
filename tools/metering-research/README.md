# 分区测光研究实验

`grid_benchmark.cpp` 是独立的合成实验，不参与 Android 构建，也不是手机测光准确度或功耗基准。它调用生产 C++ 稀疏 RAW sampler，以宿主 C++ 重现 EV 转换/排序，比较分区 EV 汇总和直方图方案。

输入为 4000×3000、RAW16 容器、中性 Bayer 合成缓冲，曝光、黑白电平和颜色系数固定。分区为 12×8，每区 8×8 或 16×16 个样本。它没有生产路径完整的元数据、AWB/暗角、逐通道颜色剪裁、校准、低光质量或 Android 生命周期处理，不能作为可直接替换的完整算法。

## 编译与运行

使用支持 C++17 的本机 GCC，例如：

```powershell
New-Item -ItemType Directory -Path build/metering-research -Force | Out-Null
g++ -std=c++17 -O3 -Wall -Wextra -pedantic tools/metering-research/grid_benchmark.cpp -o build/metering-research/grid_benchmark.exe
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
./build/metering-research/grid_benchmark.exe
```

Windows MinGW 用户应把对应工具链 DLL 目录加入当前进程 PATH。Linux 可去掉输出文件的 `.exe` 后缀。所有构建产物保存在忽略的 build 目录。

程序先预热，再输出 P50/P95 毫秒、LUT 误差、对稠密同估计器参考的 B/U 差、截断面积和选区隔离检查。输出是带键的变长 CSV 记录，不是统一列宽的表格；`scene` 的误差相对于同场景完整 Bayer 单元统计，不是外部标准表。

LUT 检查只覆盖正数 `2^-20..2^3`，固定原型直方图范围为 −16..16 EV、桶宽 1/32 EV；实际产品需要适合逐帧曝光的桶范围和越界处理。原型的灰度截断以 luma 判断，只适用于这里的中性合成输入；产品仍须检查每个 RAW 通道。

2026-10-24 输出保存在 [研究 CSV](../../docs/research/data/grid_benchmark_2026_10_24.csv)，讨论和边界见 [研究报告](../../docs/research/GRID_EV_METERING_AND_RAW_STREAM_RESEARCH_2026_10_25_ZH.md)。不要用这里的桌面耗时替代 Android 采集、JNI、推理或耗电测试。

## 共享原图库盘点

`shared_image_pool.py` 使用 Python 标准库读取另一个任务的暂停原图池，生成原路径引用和库存摘要，不下载/复制/删除图片，不修改登记簿或安装缓存保护。仅接受存在 `pause-all.json` 且没有 SQLite WAL 的暂停池，通过前后散列检查拒绝有变化的登记簿；临时复制的仅是元数据。示例：

```powershell
python tools/metering-research/shared_image_pool.py 'D:\Project\film2\training-data\liu4k-v2-pool' outputs/metering-research/shared-pool --verify-hashes
```

输出目录由现有 `outputs/` 规则忽略。`source_record` 完整保留来源/署名字段和原 split，`metering_label=null` 明确没有测光标签。`hashes_verified` 仅表示原图完整性，不表示图像具备线性响应、绝对 EV 标签或已完成许可审计。工具会拒绝缺失原图、重复散列、未知许可或未知划分；它不是训练器或连续运行的数据同步服务。恢复共享池前须完成 [双消费者保留方案](../../docs/research/SHARED_TRAINING_IMAGE_POOL_2026_10_25_ZH.md)。
