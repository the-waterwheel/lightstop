# 非 Normal 页面视觉统一

## 基线与项目结构

开始前已确认同项目其他对话没有运行中的任务，上一轮工作已提交。当前工作树从 `7ff2147` 建立 `codex/non-normal-visual-polish`，开始时无未提交改动。

项目为单模块 Android 应用：

- `MainActivity` 与 `MeterLayout` 组织相机、页面、模式及工具入口。
- Kotlin 原生 `View` / `Canvas` 绘制仪表和多数工具；负片页组合原生布局、按钮、滑块和自绘选区。
- `*Geometry` 与 `AdaptiveLayout` 提供尺寸和触摸坐标，View 内处理手势及回调。
- `MeterState`、会话与 Repository 保存和传递参数；Camera2、C++ RAW 和固定版本 OpenCV 处理相机与图像。
- `src/uiPreview` 是显式启用的 Robolectric 原生 Canvas 预览源集，不进入生产 APK。

## 视觉调整

延续白／黑底、暖灰、暗红和蓝色提示，保留原有仪表感。

- 工具页抽出 `ToolChromeRenderer`，统一中等字重标题、返回／关闭图形、细分隔线及内收的圆角细边框；标题按可用宽度适配。
- 景深、倒易率、闪光和色温的主要读数区域使用同一轻灰表面层次。景深拨轮的内外层区分更清楚。
- 宽容度、倒易率和闪光的选择卡与操作按钮统一圆角和边框粗细；应用状态仍使用原有红／蓝提示。
- 宽容度边界数值标签支持深色背景，避免原先深色页面上的突兀白块；实际摄影灰阶保持原值。
- 工具入口保留原图标，分开图标底板与文字区域，关闭图形使用正文色以提高可辨识度。
- Zone 使用轻灰标记轨道、柔和记录行和细分区线；浅色主题选中标记使用白色编号，提高其在红色填充上的可读性。
- 负片页补齐居中标题，抽屉把手和校色／基础分组使用同一层次的底色；按钮尺寸、返回区域和抽屉开合方式保持原值。

未修改 Normal 绘制代码、布局几何、触摸热区、事件分发、动画时序、计算算法、相机请求或持久化逻辑。新增标题是非交互文本。

## 验证

- 全部常规及可选预览测试：588 项通过，0 失败、0 错误、0 跳过。包括现有抽屉操作、闪光恢复和参数记录距离保留回归。
- Debug APK 构建成功，输出 arm64-v8a、armeabi-v7a、x86_64 和 universal 包。
- `lintDebug`：0 错误、66 警告。
- 页面预览：27 个页面／状态 × 4 种窗口配置 × 明暗主题，共 216 张；另有读数、自动测距与抽屉动画预览。
- 窗口配置：420×900dp、360×720dp、900×420dp 左手英文、420×900dp 的 1.3 倍字体。
- 新增有内容的 Zone、闪光已应用、色温读数／忙碌、负片忙碌／选区预览，避免只检查空初始状态。样例不使用真实照片或个人记录。
- Normal 模式在上述 4 种窗口配置和明暗主题的 8 张截图均与基线逐像素一致。预览样例隔离修正后已重新生成全部页面截图。
- `git diff --check` 通过。

预览调用真实生产 View 的 measure/layout/draw，由 Android 原生图形渲染。相机区域使用灰色占位，负片选区使用合成灰色帧；这不验证真实相机画面或负片算法效果。当前没有连接 ADB 设备，未进行本轮实机操作。

## 复现

```powershell
.\gradlew.bat '-PuiPreview=true' '-Pandroid.useAndroidX=true' :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

可选 `-PpreviewSdkDir=build/preview-deps` 指定已缓存的 Robolectric SDK JAR 目录，`-PpreviewOutput=绝对目录` 指定截图输出。预览依赖仍仅在 `uiPreview` 开关打开时使用，不增加 APK 依赖。

本轮本机截图位于被 Git 忽略的 `build/visual-review/before`、`build/visual-review/after`，总览图位于 `build/visual-review`。APK 在 `app/build/outputs/apk/debug`。不提交截图、构建产物或本机 SDK 配置。
