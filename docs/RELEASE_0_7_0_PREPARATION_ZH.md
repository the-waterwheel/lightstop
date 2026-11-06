# 0.7.0 发布准备与模拟器截图

2026-11-06。版本 `0.7.0` / `13`，包名 `com.lightmeter.rawmeter`，最低 Android 9。所有现有命名分支已合入本机 main。

## 模型与固定一档提亮

应用继续使用 epoch 219 的 `global_metering_epoch219.bin`，SHA-256：

```text
1d4f4b5528878f78093e34ba9fb9b10b57d6a7b6e862917a49d1cd15dd5f2dbd
```

全局模型建议采用 `targetEv100 = cameraEv100 - modelCompensationEv - 1.0`，即最终曝光固定增加 1 EV。来源校准、场景亮度读数与模型原始补偿分别保留。统计回退不叠加该模型偏置。

四个 Release APK 均已逐一检查 ZIP 内容：唯一模型权重是上述 epoch 219 文件，散列一致；没有 `.pt`、`.pth`、`.onnx`、`.safetensors`、`.ckpt`、训练输出目录或共享图池。当前 A/B/C 训练模型没有被替换进应用。训练进程与缓存没有因发布准备而停止或清理。

## 构建与验证

```powershell
.\gradlew.bat --offline --no-daemon --console=plain --max-workers=2 '-PuiPreview=true' '-Pandroid.useAndroidX=true' :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug
```

- 844 项测试，143 个套件；零失败、零错误。
- Debug 与四种 Release APK 构建通过。
- Lint：零错误、69 条警告。
- 截图入口只位于 `androidTest`，不会打入正式应用；使用真实模拟器界面，不注入虚构测光结果。

## 截图复现

使用项目专用 Android 9 x86_64 AVD，原生分辨率 1080×2160，密度 420，软件图形后端。

```powershell
.\gradlew.bat :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-x86_64-debug.apk
adb -s emulator-5554 shell pm grant com.lightmeter.rawmeter android.permission.CAMERA
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -e suite release-screenshots com.lightmeter.rawmeter.test/com.lightmeter.rawmeter.DeviceMeteringInstrumentation
```

仅对专用模拟器运行此入口。入口会关闭该模拟器的首次校准提醒并切换页面；不写入虚构校准或模拟测光值。PNG 保存在模拟器应用私有目录 `files/release-screenshots/`。

覆盖 Normal 的中央重点、点测光、角度测光、全局测光；Clear；Zone；工具列表；景深、宽容度、倒易率、闪光曝光、参数记录、色温、负片预览；设置，共 15 张。它们用于界面检查，不代表真实手机的 RAW／YUV 精度、性能或组合兼容性已验收。

## 正式签名与上传

用户自行在 Android Studio 打开 main 项目，使用 **Generate Signed Bundle / APK → APK → release**，沿用原发布密钥。正式 APK 输出目录由用户提供。

收到正式包后，再检查版本、包名、四个 ABI、模型散列、固定 +1 EV 对应的构建内容与证书连续性，并生成最终 SHA256SUMS。0.6.1 的发布证书 SHA-256 应为：

```text
94d693638fbb0a55bc1c11e81fa5916d67572ee8e298dd4b08b315787a7f7a7a
```

发布说明草稿见 [v0.7.0.md](releases/v0.7.0.md)。本轮生成的未签名 Release APK 仅用于构建检查，不能作为正式可安装附件上传；现有 GitHub 发布不作覆盖。
