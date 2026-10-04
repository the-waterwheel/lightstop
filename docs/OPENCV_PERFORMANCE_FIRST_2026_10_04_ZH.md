# OpenCV 保守裁剪与工作树合并验证（2026-10-04）

本次按“实时性能影响小、方便维护业务代码、再缩减体积”的顺序实现。应用接入固定版本 `opencv-slim-4.12.0-r4-perf.aar`，构建时校验 SHA-256。裁剪只涉及 OpenCV 构建配置，应用的跟踪与测光算法无需适配。

## 构建选择

- 保留 `core,imgproc,imgcodecs,video,videoio,features2d,calib3d,java`，以及自动依赖的 `flann`。
- 保留完整 Android Java/JNI 接口、PNG/JPEG、`-O3` 和原来的 CPU baseline/dispatch。
- 保留 oneTBB 2022.1、Carotene、arm64 KleidiCV 0.5、x86_64 IPP 2022.1 与默认 ITT。
- 关闭未使用的 OpenJPEG、TIFF、WebP、OpenEXR、AVIF、Jasper 文件格式后端。
- 使用 NDK r27，并为三个 ABI 的 OpenCV 库启用 16 KB ELF 对齐。
- 继续使用官方 SDK/AAR 生成器；构建驱动的小补丁只处理诊断路径脱敏、关闭测试目标后的驱动冲突及 Windows Gradle 启动方式。

加速后端可能由 OpenCV 内部算子调用，不能根据业务源码没有直接引用就判断为无用。旧 r2 关闭了这些后端，本次恢复它们。因此 r4-perf 的体积大于 r2，换取加速能力与原来的模块边界；尚未通过实机耗时测量证明性能不退化。

## 实测体积

| `libopencv_java4.so` | r1 bytes | r4-perf bytes | 减少 |
|---|---:|---:|---:|
| arm64-v8a | 14,515,536 | 13,074,352 | 9.93% |
| armeabi-v7a | 10,171,176 | 8,388,832 | 17.52% |
| x86_64 | 47,063,912 | 44,316,600 | 5.84% |
| 合计 | 71,750,624 | 65,779,784 | 8.32% |

原生库总量减少 `5,970,840` bytes（5.69 MiB）。这同时包含编解码后端裁剪与 NDK r25→r27 的工具链变化，不能把全部差值归因于单一选项。

AAR 从 r1 的 `63,882,947` bytes 变为 `59,634,187` bytes。AAR 包含头文件、Prefab 与发布元数据，并压缩了原生库；其文件大小不等于 APK 的运行库占用。

合并后的 0.5.0（versionCode 10）产物：

| 产物 | bytes | MiB |
|---|---:|---:|
| 通用 release APK | 73,989,377 | 70.56 |
| arm64-v8a release APK | 18,690,244 | 17.82 |
| armeabi-v7a release APK | 13,500,390 | 12.87 |
| x86_64 release APK | 49,888,051 | 47.58 |
| release AAB 文件 | 34,333,615 | 32.74 |

release 产物需要签名后发布。当前 APK 中原生库为不压缩条目，便于直接加载；AAB 压缩文件大小不能用于宣称商店下载量。旧 r2 的通用 APK 约 42.42 MiB，但它关闭了运行时加速。历史 r1 APK 也包含不同的业务代码，不是当前合并功能下的同代码对照。

各架构包使用现有 `-PsplitApks=true` 开关生成；默认仍为通用 APK。分架构发布保留相同业务代码与匹配设备的原生库，是后续优先采用的体积优化。

## 合并范围

集成分支为 `codex/opencv-conservative-slim`。

| 工作树分支 | 纳入的提交 |
|---|---|
| `codex/camera-controller-components` | `66dbf50` |
| `codex/film-preview-fix` | `b23d1ef` |
| `codex/film-area-preview` | `8712362` |
| `codex/negative-preview-robust` | `fa6c5ef`，包含单帧负片校正及相机线程关闭修复 |
| `codex/film-negative-realtime-docs` | `32970e6` |

前四者的功能提交通过 `009452f` 合并，实时路线文档通过 `1a12af7` 合并。OpenCV 构建配置冲突采用本次性能优先配置，并接入最新工具链支持。其他工作树的未提交内容仍保留在原位置；“负片预览优化”的实验性 OpenCV 草稿未作为正式依赖使用。

## 已完成验证

- OpenCV 三个 ABI 的 SDK 构建、AAR 打包和发布生成器成功完成。
- `verify-opencv-runtime.py` 对照 r1：148 个 Java 类列表相同，每个 ABI 的 1,865 个 JNI 导出相同；模块、CPU baseline/dispatch、PNG/JPEG、性能优化级别和加速后端校验通过。
- 三个 OpenCV ELF 的所有 LOAD 段对齐均为 16,384 bytes。
- 最终 APK 的 arm64/x86_64 库（OpenCV、RAW JNI 和 libc++）ELF 及 ZIP 条目均符合 16 KB 对齐；四个 release APK 的官方 `zipalign -c -P 16 4` 检查通过。32 位 NDK libc++ 的 4 KB ELF 对齐符合该架构的页大小；Android 大页支持针对 64 位 ABI，见 [NDK Build System Maintainers Guide](https://android.googlesource.com/platform/ndk/+/master/docs/BuildSystemMaintainers.md)。
- 合并后 `testDebugUnitTest`：513 tests，0 failures，0 errors，0 skipped。
- `lintDebug`：0 errors，66 warnings，与已合并功能工作树记录的既有警告数量一致。
- debug APK、release APK、release AAB 及三个分架构 release APK 均构建成功。
- 更新了实际保留的 TBB/KleidiCV/IPP/ITT 许可索引，并核对 APK/AAB 的许可索引与源码一致。

验证命令：

```powershell
.\gradlew.bat --offline --no-daemon --max-workers=4 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:bundleRelease
.\gradlew.bat --offline --no-daemon --max-workers=4 -PsplitApks=true :app:assembleRelease
python tools/opencv-slim/verify-opencv-runtime.py app/libs/opencv-slim-4.12.0-r4-perf.aar --baseline <r1-AAR路径>
```

AAR SHA-256：

```text
0CAC465278897A22BDC1D26BCAAA53E06D20B5C295DE18992334146330F43848
```

## 稍后进行的实机测试

测试手机断开后，用户选择先完成构建，实机性能测试稍后进行。没有报告帧率、光流耗时或 ORB 性能改善，也没有把单元测试通过解释为实时性能已验证。

`tools/opencv-slim/benchmark-zone.cpp` 已准备，并编译了 Android arm64 基准程序。它在固定纹理上测量预处理、前后向 LK 加仿射估计、ORB/Hamming 匹配，输出均值、P50、P95 和跟踪质量。手机重连后，以 r1/r2/r4 交替运行 160/400 特征点用例，记录温度与设备状态，再回归真实场景的 Zone 跟踪、移出画面后恢复、负片校正和相机切换。

该基准覆盖代表性原生工作负载，端到端相机延迟和实际画面中的跟踪质量仍需要独立测量。
