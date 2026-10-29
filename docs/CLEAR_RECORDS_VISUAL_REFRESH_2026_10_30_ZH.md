# Clear 与参数记录界面整理（2026-10-30）

这次继续使用黑、白、暖灰与红色强调的配色，把主要读数和操作放在更清楚的位置。

## Clear

- 保持原有大取景区域及横竖屏、惯用手布局，状态与 YUV / ISP / RAW 来源、EV100 分开展示。
- 曝光补偿、光圈、快门使用统一卡片，当前档位有浅色底；固定的参数用主题适配的红色提示，相邻档位提高对比度。
- 参数调节栏使用绘制的滑杆图标、折叠箭头；展开设置使用原生 Canvas 开关与细分隔线。
- 手动测光、锁定、持续测光、曝光模拟、档位与模式切换动画沿用原有逻辑。
- Normal 和 Clear 共用的画幅 / 旋转控件统一细边框、圆角及暗色强调色。

## 参数记录

- 设置入口改为紧凑的选项行与底部操作栏；GPS、时间、RAW 支持点按整行，RAW 帮助入口仍独立。
- 分类卡片标出记录数量；记录网格在竖屏优先使用两列，横屏最多四列，缩略图下直接显示光圈、快门、EI 与时间。
- 详情首先展示拍摄时的光圈、快门、EI，再依次展示 EV100、取景范围、测光方式、变焦、RAW 能力、胶片及其他已保存信息。
- 说明和备注自动换行，长内容可滚动，并显示位置指示。小屏下长数值按可用宽度缩小，避免截掉关键数字。
- 灰框显示、RAW 补点、撤销使用一致的按钮样式；曝光参数抽屉降低大面积黑色和粗线框的比重。
- 保存前的编辑页面同步卡片与按钮样式，深色模式下保存按钮与文字保持对比度。

记录模型、旧索引兼容、图像坐标、灰框几何和 RAW 数据不变。历史页面仍独立接管触摸，避免触发底下的实时记录按钮。

## 验证与预览

原生界面预览来自 `WorkspacePolishUiTest`，使用 Android Canvas 实际绘制和合成风景，不是真机照片。覆盖中文 / 英文、浅色 / 暗色、竖屏 / 横屏、Clear 左手布局与小屏窗口。预览位于 `docs/previews/polish-20261030/`。

交互回归覆盖记录网格的第二列与第二行定位、紧凑窗口下的设置开关与底部按钮，并保留现有灰框、RAW 补点、历史触摸隔离、Clear 档位 / 测光与模式转场检查。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug '-PuiPreview=true' '-Pandroid.useAndroidX=true' '-PpreviewOutput=build/ui-preview/polish-20261030' --offline --console=plain
```

本机没有连接实体手机，交付 APK 用于真机体验确认。

最终验证：793 项检查通过，零失败、零跳过；Debug 与 AndroidTest APK 构建成功；Lint 为零错误、69 条既有警告。

测试包：`lightstop-clear-records-polish-20261030-universal-debug.apk`，53,115,472 字节。

SHA-256：`EEF3B3A34B963A757F9AFCF22892E45BF66287FCA8F2F0B15E00279C71B9E7E2`。
