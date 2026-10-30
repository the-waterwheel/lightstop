# 曝光预览的渐变与请求合并

日期：2026-10-27。接续 `254f9ca`，保留画幅灰框、预览缩放与参数记录的既有修改。

## 行为与原因

此前普通曝光预览把最终快门、ISO 或 AE 补偿直接送给 Camera2。测光完成后，恢复的预览可能立即采用目标曝光；界面刷新还会重复向相机线程投递相同选择，因此观感上会有停顿和亮度跳变。

本次让普通曝光预览从当前预览的曝光逐帧接近最终目标。较小变化采用约 300–800 ms 的缓入缓出过程；极大曝光差、低帧率或掉帧时会延长，避免追赶时间造成一次大跳。最终曝光、校准补偿和硬件钳制仍由原有 `ExposurePreviewMath` 决定，参数行直接显示最终选择。

## 实现边界

- `ExposurePreviewRamp` 只处理 EV 时间曲线以及快门、ISO 的对数插值。插值期间保持曝光乘积随 EV 连续变化，避免只改一个参数后在末尾突然切换另一参数。
- `ExposurePreviewStateCoordinator` 持有渐变与取消状态。起点来自当前会话的预览元数据，必要时把 AE 数值转换到合法的手动 ISO、快门范围；不再从可能属于 RAW 的 `latestResult` 推断起点。手动曝光元数据连续缺失三帧时才使用测光域转换得到的有限后备值。
- `CameraController` 只负责当前 Session/generation 的帧路由与相机线程调度。重建预览会清掉旧的执行值，等待新预览帧后开始渐变。RAW 暂停期间不安排后台动画任务，旧渐变不能在画面冻结时自行结束。
- 渐变时每个实际回调最多推进 50 ms，EV 速度上限为 6 EV/s。请求按约 30 次/秒限速，保留时钟相位，避免接近 30 fps 时因取整误差变成隔帧更新。动画时钟统一使用单调时钟，不混用不同设备的传感器时钟域。
- 相同曝光选择直接返回，相机线程上尚未处理的选择更新合并成一次；中途改变目标从当前执行值继续。曝光请求内容相同则不重复提交，渐变期间的请求日志最多每 500 ms 输出一次。
- 关闭普通曝光预览、开始测光或进入辅助校准会取消旧渐变。辅助校准的比较曝光保持即时执行，负片预览继续由原有流程处理。

## 测光安全

测光仍先恢复中性 AE，再等待带本次 baseline 标签的两个稳定结果帧；1200 ms 的原有超时策略不变。恢复中性曝光时由 baseline 所有者提交一次带新标签的请求，渐变不会重复抢先提交。

关闭曝光预览不等于传感器已经恢复中性。仍在管线中的手动曝光或非零补偿帧会保留“需要恢复 baseline”的状态，直到收到中性预览证据。RAW 的请求、精确配对、采集帧数、隔离会话及恢复顺序没有改变。

## 验证与当前限制

- 新增 5 项纯数学测试和 11 项 Robolectric 状态测试，覆盖变亮/变暗、极大 EV 差、暂停/掉帧、反向调整、AE 补偿、重复 UI 更新、30 fps 取整、会话重建、硬件钳制、元数据缺失、关闭后的在途帧，以及测光/校准取消。
- 完整单元/Robolectric 回归共 731 项，零失败、错误或跳过；Debug 三种 ABI 与通用 APK 构建通过，AndroidTest APK 构建通过。Lint 零错误、70 项既有警告；`git diff --check` 通过。
- 设备测试入口新增 `exposure-transition`，读取实际 Camera2 元数据、TextureView 亮度和帧时间戳，记录请求数量，并在渐变中再次测光以检查中性读数。需要固定手机并对准静止场景；运行报告位于应用的 `files/device-validation-exposure-transition.json`。`compareOld=true` 可对旧包运行同一检查，但不要求存在中间曝光值。
- 本机未连接实体手机。专用 Android 28 x86_64 模拟器在运行旧包 `254f9ca` 时三次退出，分别覆盖软件/主机 GPU 与 emulated/virtualscene 相机组合；没有生成有效运行报告。崩溃摘要位于宿主 `qemu-system-x86_64-headless.exe`，因此这些尝试不记作应用运行验证通过，也不能据此推断实体设备表现。
- 尚待实体手机确认实际亮度过渡、帧间停顿、RAW 恢复以及测光一致性；参见 [贡献指南](../CONTRIBUTING.md) 与 [控制器边界](CAMERA_CONTROLLER_BOUNDARIES_ZH.md) 的手机回归要求。自动化结果仅证明曲线、状态和请求限速行为，不宣称测得了实际卡顿时长改善。隔离 RAW 切换自身的预览暂停仍可能存在。

验证命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest '-PuiPreview=true' '-Pandroid.useAndroidX=true' --offline --console=plain
```

实体设备上的可选回归（会操作应用测光界面，先固定手机并对准静止场景）：

```powershell
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e suite exposure-transition com.lightmeter.rawmeter.test/com.lightmeter.rawmeter.DeviceMeteringInstrumentation
```

## 测试包留档

- 当前通用 Debug：`app/build/outputs/apk/debug/lightstop-exposure-smooth-20261027-universal-debug.apk`，53,000,276 bytes，Android 9+，版本 `0.6.1 (12)`。
- SHA-256：`255C524FC31F46E939E176A9C3CBDB06CF50A353873F1C69789B6E7EEE145A57`。
- 旧参数记录版留档：`lightstop-records-20261027-universal-debug.apk`，SHA-256：`FC1C0135235685535BF9FF049BAF6FE23664816AE7D0F3A479FBEDA297DD1DB9`。
- 测试包和模拟器均在忽略的 `app/build` 内，不提交二进制、设备数据或本机 SDK 配置；Git 只追加源码、测试和说明。
