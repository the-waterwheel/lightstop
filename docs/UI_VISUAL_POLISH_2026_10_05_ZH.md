# 界面质感统一与测光控件优化

## 基线与范围

- 在其他项目对话结束、上一轮提交 `68e153b` 完成后，从该提交建立 `codex/ui-visual-polish`。
- 项目是单模块 Android 应用：Kotlin 自绘 Canvas/View 与原生控件、Camera2、C++ RAW 处理和固定版本 OpenCV AAR；没有 Compose 布局。
- 本轮调整绘制、字体和控件外观。布局几何、点击热区、拖动映射、页面顺序、回调、相机控制和图像处理算法未修改。

## 最终视觉处理

- 新增 `InstrumentStyle`，统一黑白底色、暖灰层级、文字、细边框及原有暗红和蓝色强调色。
- 测光页保留黑色光圈条、白色快门条、刻度与圆盘。主数值字号由 15 增至 19，刻度数字由 7.5 增至 10；长数值自动缩小到原有读数区，倒易律补偿标记保留空间。
- 新增共享 `ZoomControlRenderer`：细轨道、单侧短刻度、描边滑块与小型暗红位置标记；普通测光和 Zone 页使用同一外观，端点和交互映射保持原值。
- 设置页使用浅层分组面板、细边框和统一圆角；工具卡片增加图标留白，保留原图标素材，并修正暗色负片图标的可见性。
- 负片页统一标题、返回、抽屉、按钮与选区按钮样式，去掉原生按钮阴影。一键反相改为深灰底白字，保留忙碌、禁用、点击反馈和原按钮尺寸。
- 景深、宽容度、倒易律、闪光、色温、参数记录、校准、摄像头和关于页采用共享颜色层级。摄影语义色阶、伪色、预览图像颜色不受这套界面颜色影响。

## 验证结果

| 项目 | 结果 |
| --- | --- |
| 常规单元测试 | 523 项，0 失败、0 错误、0 跳过 |
| `lintDebug` | 0 错误、66 警告；并未声称警告全部消除 |
| Debug / Release 构建 | 成功；Release 为未签名 APK |
| 原生 Canvas 页面预览 | 120 张，15 个页面/状态 × 4 种窗口配置 × 明暗主题 |
| 长数值读数预览 | 4 张组合图，左右手 × 明暗主题；覆盖 f/128、1/8000、0.33 秒、30 秒、3600 秒 |
| Git 空白检查 | `git diff --check` 通过 |

预览使用实际生产 View 的 measure/layout/draw，由 Robolectric Android 原生图形渲染生成，不是设计稿。相机区域是灰色占位。页面配置包括 420×900dp、360×720dp、900×420dp 左手英文，以及 1.3 倍系统字体。放大字体只影响原本使用 scaledDensity/sp 的控件，仪表刻度保持应用自身缩放规则。

### 实机：vivo V2405A / Android 16

- 使用 `adb install -r` 更新 Debug，未卸载或清除数据。
- 检查浅色/深色、竖屏/横屏、设置及工具页；主读数、变焦、负片按钮和手动参数抽屉已截图查看。
- 拖动变焦从 1.0× 到 2.4×，画面和标签同步；随后恢复 1.0×。
- 实际触发 RAW 测光：3 帧，`quality=ACCEPT`，EV100 约 0.705，日志耗时约 933 ms。
- 负片页检查展开/收起手动参数、一键反相忙碌/恢复、返回页面。当前长焦暗墙场景未取得可靠冻结帧，手动选区页未在这次实机测试中成功进入；完整选区反相和真实负片颜色还原不计作通过，也没有因此改动相机/负片算法。
- 收尾日志未发现 `FATAL EXCEPTION`、`ANR in` 或 `Fatal signal`。这仅覆盖本轮收集的进程日志。
- 收尾恢复浅色、右手、竖屏、原摄像头 `0@3` 与 1.0× 变焦。

## 复现命令

常规验证（JDK 17、项目对应 Android SDK / NDK）：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

可选布局预览：

```powershell
.\gradlew.bat '-PuiPreview=true' '-Pandroid.useAndroidX=true' :app:testDebugUnitTest --tests '*UiPreviewTest'
```

默认输出在 `app/build/ui-preview/`。`-PpreviewOutput=绝对目录` 可覆盖输出位置。如果已预先缓存 Robolectric 对应的 instrumented SDK JAR，可传 `-PpreviewSdkDir=build/preview-deps` 指定相对仓库根目录的离线目录；本轮也验证了这个选项。

`uiPreview` 默认关闭。Robolectric 与 AndroidX 测试依赖仅在显式启用预览时解析，不进入生产 APK。截图是人工视觉检查材料，不是像素基线断言。

本机验证材料在被 Git 忽略的 `build/visual-review/`、`app/build/ui-preview/` 与 `dist/ui-polish/`，不将手机相机照片提交仓库。
