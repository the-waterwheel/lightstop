# 工作树整合与全局测光基线更新

2026-10-24。按维护者要求，将同项目尚未进入 main 的工作树代码与文档整合到 main。原 main 为 `7ff2147`（0.6.1），不修改应用版本、签名或许可证，不执行远端推送。

## 范围与历史

本次枚举 11 个登记工作树，逐一读取分支、HEAD、未提交/未跟踪文件及相对 main 的变更。保留原工作树、原分支和原有未提交状态；没有执行 reset、clean、stash、删除或归档。

| 工作树/分支 | 原状态 | 处理 |
| --- | --- | --- |
| 主项目 / main | `7ff2147`，干净 | 验证整合分支后快进到同一结果 |
| `399b` / `codex/independent-metering` | `5281609`，领先 6 个提交；9 个跟踪文件修改与 2 个未跟踪文件 | 合入完整提交链；复制 11 个源文件/测试/文档并逐文件 SHA-256 验证，保留源工作树状态 |
| `50e2` / `codex/camera-controller-boundaries` | `9b74d85`，领先 5 个提交 | 已被 `5281609` 包含，随上行合入 |
| `c731` / `codex/non-normal-visual-polish` | `74df53a`，独立领先 1 个提交 | 单独合入工具与 Zone 视觉更新，无冲突 |
| `6489` / detached | `7ff2147`，有未跟踪测光审计文档 | 导入 `METERING_CODE_AUDIT_2026_10_21_ZH.md`，不复制被忽略的诊断目录 |
| `5b24` / 本对话 | `7ff2147`，有新增神经测光方案 | 根据整合后的全局实现重写相关判断与阶段安排 |
| `11aa` / `codex/release-0.6.1` | 与原 main 相同 | 无额外变更 |
| `0e1d` / `codex/opencv-conservative-slim` | `8baf51c`，旧开发分支 | 功能已进入 0.6.0 整理内容；保留 main 后续 compact OpenCV、发布打包与隐私清理结果 |
| `b37c` / `codex/ui-visual-polish` | `0e29fa0`，旧开发分支 | 功能已进入 0.6.0 整理内容，保留 main 后续修正 |
| `545e` / detached | `5f125f6`，main 祖先，无新改动 | 无需重新合并 |
| `dd8f` / detached | `5f125f6`，main 祖先；未跟踪旧研究文档 | 文档与仓库 `research/MATRIX_METERING_RESEARCH_2026_10_05_ZH.md` 在归一化换行后完全相同，不重复导入 |

旧 OpenCV/UI 分支均为 `2c95c7a` 的祖先；`git diff 2c95c7a 21579aa` 为空，证明旧发布准备树与 main 中 0.6.0 内容整理提交的源码树一致。发布记录明确使用内容整理提交而不带入旧开发历史。因此，不重新引入旧 AAR、过时 README 或已清理的中间历史。[0.6.0 验证记录](RELEASE_0_6_0_VALIDATION_ZH.md)

本次合并提交 `9f8bb4a` 和 `f8aec8a` 分别接入 `5281609` 和 `74df53a`。设备修正与历史测试源单独提交为 `4615267`，便于区分已提交分支内容与导入的未提交改动。

## 合入的行为

- Normal 全局测光：可见画幅/变焦内面积等权采样；EV 分布 5%–95% 均值 B 与 98%–99.5% 均值 U；`delta=clamp(U-B-2.5,0,0.5)`，推荐 `B+delta`，与用户补偿分开。
- 全局仅在 Normal 使用；进入 Zone 临时切到按角度/局部模式，返回后恢复全局。保留 `AVERAGE` 作为持久化标识。
- 全局 RAW 亮端截断时连续每级减 3 EV，局部任一通道中位数饱和时每级减 5 EV；按实际统计和相机控制下限停止。
- 相机职责拆分、GL 输出时间戳、等待精确显示帧元数据、YUV 样本中位数与状态设置优化。
- 允许实际声明 Bayer RAW_SENSOR 输出的路由进入原有真实组合探测；测量失败只影响当次允许的兼容回退，不再因两次失败长期降级。
- 非 Normal 工具和 Zone 视觉统一；导入已有显式实机测试驱动，位于 androidTest，不打入生产应用。

实现细节与原测试证据：[全局实现](AVERAGE_METERING_IMPLEMENTATION_2026_10_15_ZH.md)、[设备验证](DEVICE_METERING_VALIDATION_2026_10_18_ZH.md)、[相机职责边界](CAMERA_CONTROLLER_BOUNDARIES_ZH.md)、[视觉调整](NON_NORMAL_VISUAL_POLISH_2026_10_08_ZH.md)。历史设备记录不冒充本轮重新实测。

## 本轮验证

完整 Android 验证在整合工作树执行，使用已有 JDK/SDK/缓存，未连接设备、安装应用或修改校准值：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:bundleRelease :app:assembleDebugAndroidTest '-PuiPreview=true' '-Pandroid.useAndroidX=true' --offline --console=plain
```

包含 bundle 的同次请求按现有 Gradle 规则关闭 APK ABI splits，APK 为通用包；仍编译三个 ABI。这不等同单独请求 assemble 时的分包输出，后续另外验证默认 APK 分包。

- JVM/Robolectric：119 套件、688 项测试，失败、错误和跳过均为 0。包含全局算法、模式切换、饱和重拍、时间戳及工具界面回归。
- Lint：0 错误、68 条警告；全局分支此前记录 67 条，非 Normal 视觉分支在原 65 条基线上增加 1 条，本轮合并后的数量与两边叠加一致。未将警告计数等同完整质量证明。
- Debug、未签名 Release APK、Release AAB 和 Debug androidTest APK 构建成功。首次完整验证约 4 分 3 秒；不是应用运行性能。
- 再单独执行 `:app:assembleDebug :app:assembleRelease --offline --console=plain`，默认 ABI splits 构建成功，得到三个架构与 universal 的 Debug/Release APK；没有重复运行已经通过的测试。
- 使用 MinGW g++ 对实际 `evaluative_raw_sampler_test.cpp` 以 C++17、O2、警告检查编译并执行，通过 CFA、黑电平、偏移、stride、边界、crop、饱和、暗部、预算、暗角插值断言。
- 导入源文件逐项 SHA-256 与源工作树一致；本机复制清单、构建日志、图片和产物只存在被忽略的 build 目录。
- 原主项目干净才执行快进；main 更新后再次核对 HEAD 和状态。
- 本轮未重新执行手机测试，不把 2026-10-18 单机采样时间作为合并后跨设备准确度、持续吞吐或功耗保证。

## 保留的问题与后续神经方案

[历史测光审计](METERING_CODE_AUDIT_2026_10_21_ZH.md) 指出的处理后来源数字增益抵消和局部 YUV ROI/变焦问题仍需专门修正；本轮没有把合并任务扩大成这些算法变更。审计中的“main”指 2026-10-21 的 `7ff2147`，不是本次整合结果。审计反例存于原工作树的忽略目录，没有成为本轮正式回归套件。

神经网络规划已更新为复用现有 B/U/delta 和全画面 Bayer 采样，再增加空间/语义特征；不再从“没有全局测光”的假设起步。输入正确性、独立参考准确度和低端机热态性能仍是接入前置条件。[修订方案](research/NEURAL_MATRIX_METERING_PLAN_2026_10_24_ZH.md)
