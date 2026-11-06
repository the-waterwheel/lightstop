# 0.7.0 发布准备与模拟器截图

2026-11-06。版本 `0.7.0` / `13`，包名 `com.lightmeter.rawmeter`，最低 Android 9。所有现有命名分支已合入本机 main。

## 模型核验

应用继续使用 epoch 219 的 `global_metering_epoch219.bin`，SHA-256：

```text
1d4f4b5528878f78093e34ba9fb9b10b57d6a7b6e862917a49d1cd15dd5f2dbd
```

模型的输入映射与曝光输出遵循应用内置设计，未增加面向用户的模型偏置设置。

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

使用项目专用 Android 9 x86_64 AVD，原生分辨率 720×1440，密度 280，软件图形后端并关闭 Vulkan。为避开本机虚拟相机与软件图形冲突，关闭虚拟相机；取景区为空白。

```powershell
.\gradlew.bat :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-x86_64-debug.apk
adb -s emulator-5554 shell pm grant com.lightmeter.rawmeter android.permission.CAMERA
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell settings put secure immersive_mode_confirmations confirmed
adb -s emulator-5554 shell am instrument -w -e suite release-screenshots com.lightmeter.rawmeter.test/com.lightmeter.rawmeter.DeviceMeteringInstrumentation
```

仅对专用模拟器运行此入口。入口会关闭该模拟器的首次校准提醒并切换页面；不写入虚构校准或模拟测光值。PNG 保存在模拟器应用私有目录 `files/release-screenshots/`。

覆盖 Normal 的中央重点、点测光、角度测光、全局测光；Clear；Zone；工具列表；景深、宽容度、倒易率、闪光曝光、参数记录、色温、负片预览；设置，共 15 张。它们用于界面检查，不代表真实手机的 RAW／YUV 精度、性能或组合兼容性已验收。

原图与总览见 [截图目录](screenshots/v0.7.0/README.md)。

## 正式签名与上传

用户自行在 Android Studio 打开 main 项目，使用 **Generate Signed Bundle / APK → APK → release**，沿用原发布密钥。正式 APK 输出目录由用户提供。

收到正式包后，再检查版本、包名、四个 ABI、模型散列、构建内容与证书连续性，并生成最终 SHA256SUMS。0.6.1 的发布证书 SHA-256 应为：

```text
94d693638fbb0a55bc1c11e81fa5916d67572ee8e298dd4b08b315787a7f7a7a
```

发布说明草稿见 [v0.7.0.md](releases/v0.7.0.md)。本轮生成的未签名 Release APK 仅用于构建检查，不能作为正式可安装附件上传；现有 GitHub 发布不作覆盖。

## 用户正式包核验结果

用户已提供四个正式 APK。包名、版本 `0.7.0` / `13`、ABI、epoch 219 模型散列和上述发布证书全部匹配；四个包均通过签名与 16 KB ZIP 对齐检查。

与本轮已测试的未签名 Release 构建逐项比对，DEX、清单、资源及 OpenCV 库内容一致。允许差异仅为文本资产的 CRLF／LF，以及 `librawmeter.so` 的 `.note.gnu.build-id`；其他 ELF 节一致。

| 发布文件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `lightstop-v0.7.0-arm64-v8a.apk` | 17854343 | `825a8b6fda01a66a87fea8e7a18c9e9a802f5be389a17fe2663d38340f79c45d` |
| `lightstop-v0.7.0-armeabi-v7a.apk` | 13564149 | `2d8cf01f35ab910cc19995c90ddb9096461499dea9542b1820271a0824a401d9` |
| `lightstop-v0.7.0-x86_64.apk` | 22381238 | `98ef24b65796cfdd6680ed47fdb3fd226d142ab43bfd3535dd2f4691c4e3f3e5` |
| `lightstop-v0.7.0-universal.apk` | 45024388 | `89e90512fb63f796ace6b8159f4e0108a410143c931fa5e41b66d49a9daced25` |
