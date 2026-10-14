# 显示帧时间戳修复与 YUV 分析优化

2026-10-14。基线为 `ecfdf89`，在上轮控制器拆分之后实施。版本仍为 0.6.1。

## 依据与范围

参考了以下已有记录：

- [控制器拆分与维护边界](CAMERA_CONTROLLER_BOUNDARIES_ZH.md)：615 项单元/Robolectric 测试，Lint 零错误、65 条警告。
- [0.6.1 发布验收](RELEASE_0_6_1_VALIDATION_ZH.md)：发布包及 ABI 验证范围。
- [负片预览测试](FILM_NEGATIVE_PREVIEW_TEST_2026_10_02_ZH.md)：GL 线程、资源释放顺序及尚待实机验收的边界。
- [2026-09-22 设备测试](DEVICE_TEST_RESULTS_AND_REMAINING_ISSUES_2026_09_22_ZH.md)：RAW/YUV 隔离、测光来源和阶段延迟的区别。该历史设备数据不作为本次修复的实机通过证据。

本次只改显示生产者的帧时间戳，以及 YUV 区域分析内部实现。保持 RAW 帧数、RAW/YUV 会话策略、颜色解码、全部 ROI 像素、裁剪阈值、中央重点权重、曝光/校准公式和精确元数据匹配规则。

## 显示帧时间戳

`FilmNegativeRenderer` 同时服务普通预览和负片工具。此前它向 TextureView 的输出 Surface 提交 GL 缓冲时仅调用 `eglSwapBuffers`，没有把已锁存的相机输入帧时间戳传给新生产者。`CompatibleLightMeter` 却使用输出 SurfaceTexture 的时间戳精确查询相机元数据，因此 GL 输出缺少正确时间戳时，显示预览测光无法可靠配对。

现在每次提交输出前，调用 `EGLExt.eglPresentationTimeANDROID` 写入当前输入 SurfaceTexture 的时间戳，再执行 swap。参数变化引起的重绘保留同一相机帧的时间戳。零值/负值不提交、不宣布首帧就绪；时间戳设置或 swap 失败走既有渲染器错误处理。继续禁止用系统时间、最新 CaptureResult 或邻近帧代替精确配对。

依据：[Android SurfaceTexture.getTimestamp](https://developer.android.com/reference/android/graphics/SurfaceTexture#getTimestamp()) 说明 EGL 生产者的时间戳来自 presentation time；[EGL_ANDROID_presentation_time](https://registry.khronos.org/EGL/extensions/ANDROID/EGL_ANDROID_presentation_time.txt) 定义了设置时间戳与随后提交缓冲的关系。

新增 5 项测试覆盖相同时间戳命中正确曝光结果、重复重绘、无效时间戳、绑定失败及 swap 失败。测试验证调用顺序和身份契约，没有模拟真实驱动或宣称已经验证 TextureView 的实际显示时间戳。

## YUV 分析

- 新增 `YuvRegionAnalyzer`，只在一次同步帧分析期间持有平面读取器和采样缓冲。spot/wide 复用按较大 ROI 分配的一个 DoubleArray；不跨帧缓存、不共享给其他线程、不延长 Image 生命周期。
- YUV → RGB 保留相同运算顺序，通过内联回调传递三个标量，避免逐像素创建 `EncodedRgb`。平面越界使用 -1 标记，避免 nullable Int 的逐像素装箱；仍使用绝对读取和 Long 偏移计算，保留 position、limit、rowStride、pixelStride 的规则。
- 精确中位数改为原地三路选择，偶数样本仍取中间两值的平均。小区间直接排序，分区次数达到预算时对剩余区间排序，限制不利输入的开销。不使用直方图近似或减少采样点。Double 的 NaN、正负零等排序语义保持一致。
- ISP Bitmap 分析和传感器 RAW 分析的采样/排序实现保持原样。

新增 8 项区域测试，将旧转换与完整排序实现作为独立参照，比较亮度和裁剪比例的 Double 位表示。覆盖 FULL/LIMITED、601/709/2020 系数、五种传递函数、ROI 重用、交错 UV、只读直接缓冲、初始偏移、行填充、平面截断、空平面、无效步长和矩阵、RGB 裁剪。新增 4 项中位数测试覆盖奇偶数量、随机/有序/逆序/重复输入、有效前缀、尾部不变、非有限值及正负零。

## 本机 JVM 对照

环境：OpenJDK 17.0.15、64 位 Server VM；固定随机 YUV 平面，FULL/601/sRGB，spot=0.08、wide=0.30，权重 0.7/0.3。两条路径各预热 200 帧，再交替执行 31 组、每组每条路径 8 帧；下表 p50/p95 是每组平均单帧耗时的分位数，并非单次帧尾延迟。用 ThreadMXBean 记录当前线程分配字节数。对照前检查最终加权亮度逐位相等。

旧路径来自测试中的冻结实现（两次区域分配、逐像素 RGB、完整排序）；新路径每帧构造读取器及 analyzer 并复用 ROI 缓冲。共享已构造的解码器和输入数据；不含元数据/LUT 构造、Camera2、取帧、GL、Image 关闭或会话切换。旧路径通过反射调用测试参照，有少量额外调用开销。JIT 可能消除旧实现的部分对象分配。

| 输入尺寸 | 旧/新 p50（ms） | 旧/新 p95（ms） | 旧/新分配（字节/帧） |
| --- | --- | --- | --- |
| 320×240 | 0.4204 / 0.1545 | 0.4386 / 0.1834 | 59424 / 55736 |
| 640×480 | 1.9949 / 0.9678 | 2.0367 / 1.0130 | 236864 / 221624 |

这些数据仅支持本机固定输入的分析路径收益，不能解释为手机端或点击到读数的提速比例。编译后字节码另确认分析热循环没有 `EncodedRgb` 构造或 Integer 装箱。临时对照代码、字节码和原始输出位于被忽略的 `app/build/yuv-benchmark-20261014/`，不进入 APK；清理 build 会移除它们。

## 验证结果与剩余验收

执行沿用上一轮配置：

```powershell
.\gradlew.bat ':app:testDebugUnitTest' ':app:assembleDebug' ':app:lintDebug' '-PuiPreview=true' '-Pandroid.useAndroidX=true' --offline --console=plain
git diff --check
```

- 632 项测试通过，零失败、错误或跳过；本轮新增 17 项。
- Debug 三个 ABI 与通用 APK 构建通过；Lint 零错误、65 条既有警告。
- 本次 adb 未发现连接设备，未覆盖安装到手机，未修改用户数据或校准。真实 EGL、HAL 与手机性能仍待验证。
- 实机重点：普通预览 ISP 测光能连续完成，显示时间戳命中同一相机结果；参数重绘、旋转、前后台和负片工具进出保持正常；YUV 点测/中央重点/角度测光与旧版在固定光源下结果一致；分别记录分析耗时与点击到读数耗时。
