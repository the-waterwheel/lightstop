# 0.6.0 发布准备与验证（2026-10-06）

应用版本 `0.6.0`，`versionCode 11`，包名保持 `com.lightmeter.rawmeter`。此前公开版本为 `0.5.0`（10）。

## 合并范围

- 负片预览：单帧自动识别与反相、片基采样、独立镜头选择、自然取景影调、拖边/对角缩放/框外旋转选区，以及自由点 RGB 曲线。
- 界面：测光工具、记录与历史抽屉的样式和动画，闪光配置恢复，距离记录保留。
- 自动距离：兼容近似对焦元数据、质量/不可用反馈和选择 Auto 时的采样通知。
- OpenCV：合入另一会话完成的 r4-perf 固定 AAR、加速配置、构建工具和许可证。
- 矩阵测光与实时负片分析仅纳入研究文档，不作为已实现功能。

合并时补齐了闪光恢复测试桩的 `onDistanceSelectionChanged` 回调。生产接口保持完整，测试覆盖原来的恢复行为和新 Auto 通知。

## 验证

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:bundleRelease --offline
.\gradlew.bat '-PuiPreview=true' '-Pandroid.useAndroidX=true' '-PpreviewSdkDir=build/preview-deps' :app:testDebugUnitTest --offline
```

第二条需要本地准备 Robolectric 测试 SDK；生产构建默认关闭该选项。本轮复用已有缓存。

- 默认测试：572 项；启用界面测试后：588 项。两轮均无失败、错误或跳过。
- lint：0 错误、65 条既有警告。
- Debug APK、未签名 Release APK 和 Release AAB 均成功构建。
- Release APK 核对版本 0.6.0 / 11、最低 API 28、目标 API 36、三个 ABI；权限仅相机和可选位置，没有网络权限。
- Release APK 通过 `zipalign -c -P 16 -v 4` 检查。
- 生成亮/暗色、紧凑/常规/横屏/大字体界面截图；抽查负片界面布局。截图来自模拟运行，不代替相机实机测试。

本机未签名构建的通用 Release APK 为 74,022,145 字节，AAB 为 34,385,086 字节。正式签名后的文件大小和散列需以维护者提交的 APK 为准。通用 APK 包含三个 ABI；AAB 可按设备分发。

## 隐私清理

- 将当前文档中的个人 Windows 用户目录、工作树路径及已知 USB 设备序列号改为占位符。
- 本地截图、诊断目录、构建日志、签名密钥和 SDK 配置不纳入发布。
- OpenCV 原构建信息已脱敏，但断言 `__FILE__` 仍含本机用户目录。本轮使用 `sanitize-aar-paths.py` 对 `.rodata` 内 ASCII 路径前缀作等长替换，同时处理 JNI / Prefab 重复库。
- 逐字节核验仅路径前缀发生变化，其他 ZIP 条目相同；再次执行不产生变化，遇到只读区段外路径会拒绝处理。
- OpenCV Java 接口、JNI 导出、CPU dispatch、TBB/KleidiCV/IPP 等配置及 ELF 对齐核验通过。固定散列更新为 `35e3b7df14f1b304a0eba7c0d6d15dd03900315e1d62d366df734243d7601662`。
- 当前源码、AAR 解包内容、APK 和 AAB 未检出个人用户目录、已知设备序列号及常见凭据模式。此检查不等同于证明不存在任何形式的敏感数据。

发布 main 使用整理后的内容提交，不将未公开开发分支的中间提交推送到 GitHub。本地分支与备份保留。此前已经公开的历史中存在旧路径记录，本轮没有强制重写既有历史。

## 正式发布仍需完成

维护者使用原发布密钥生成签名 Release APK；收到文件后核对签名连续性、版本、权限、ABI 和散列，再上传 GitHub Release。

已有单台设备验证记录保留；自由点曲线、最新选区手势、历史抽屉等的完整跨设备实测以及 OpenCV 实机性能比较尚未完成。不能据此宣称所有画幅、弯曲/反光底片、运动场景或全部手机均达到某个准确率。
