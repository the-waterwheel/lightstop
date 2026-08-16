# Camera pipeline and device compatibility / 相机管线与设备兼容

English | [简体中文](#中文说明)

This document records the camera behavior of the current compatibility-stable
snapshot. It is a design contract for later refactoring: moving code between
classes must not silently change the stream profiles, fallback order, resource
ownership, calibration sequence, or user-visible recovery behavior described
here.

## Session profiles

`CameraController` selects the smallest profile that preserves the requested
function. Profiles are capability-driven rather than manufacturer-driven.

| Profile | Preview | RAW | YUV | Purpose |
|---|---:|---:|---:|---|
| `FULL` | yes | yes | yes | RAW metering plus Zone tracking and YUV compatibility |
| `RAW_ONLY` | yes | yes | no | retain high-accuracy metering when the combined stream set fails |
| `COMPATIBLE` | yes | no | yes | ISP-processed compatible metering without RAW resources |
| `PREVIEW_ONLY` | yes | no | no | lowest common Camera2 path |

Automatic mode starts with the normalized `FULL` profile when both RAW and YUV
are advertised. Compatible mode never creates a RAW reader. Session creation
failure removes optional streams in this order:

```text
FULL -> RAW_ONLY -> COMPATIBLE -> PREVIEW_ONLY
```

Runtime camera errors are classified before retry or downgrade. A physical
camera route can finally fall back to its logical camera. Permission denial,
camera privacy policy, another application holding the camera, or a broken
vendor preview implementation can still prevent every profile from opening.

## RAW metering

- RAW is used only when the selected camera advertises
  `REQUEST_AVAILABLE_CAPABILITIES_RAW` and exposes `RAW_SENSOR` sizes.
- ISO 800 or below uses three frames; higher ISO uses five frames.
- Only one full-size RAW request is in flight. The next request is submitted
  after the current `Image` has been analyzed and closed.
- `Image` and `CaptureResult` are paired by sensor timestamp. Unmatched images
  remain owned by the active operation and are closed on success, failure,
  timeout, camera recovery, activity pause, or controller shutdown.
- Bayer layout, dynamic/fixed black level, white level, color gains, color
  transform, exposure, sensitivity, aperture, and post-RAW boost come from the
  selected camera's metadata rather than from manufacturer assumptions.

## Compatible metering

Compatible metering consumes ISP-processed data and therefore does not perform
multi-frame noise-reduction fusion.

1. Prefer one valid `YUV_420_888` frame when the YUV output is active.
2. Reuse one luminance buffer instead of allocating a frame-sized array for
   every callback.
3. Try at most three YUV frames and never wait longer than 250 ms.
4. If YUV is missing or invalid, immediately analyze one 96 x 96 sample of the
   displayed preview.
5. Remember the YUV failure for the current camera session. In compatible
   mode, reopen as `PREVIEW_ONLY` after delivering the reading so later
   measurements do not repeatedly pay the timeout or keep the unused stream.

This makes YUV an optimization, not a requirement. The practical compatibility
boundary is any device that can provide a basic third-party Camera2 preview.

## Calibration sequence

For a RAW-capable camera, one calibration request uses the same fixed reference
input for two sequential readings:

```text
RAW reading -> close RAW images -> compatible reading -> save both corrections
```

There is no fixed delay between the stages. A camera without RAW support skips
the RAW stage and stores only the compatible correction. RAW and compatible
corrections remain separate per manufacturer, model, and camera ID.

## Thread and resource ownership

- The main thread owns `TextureView` bitmap capture and all UI callbacks.
- The camera handler thread owns Camera2 devices, sessions, readers, capture
  callbacks, image/result pairing, and timeout state.
- Every acquired `Image` has one explicit owner and must be closed exactly once.
- RAW, YUV, and vignetting timeout `Runnable`s are removed when their operation
  finishes or the camera closes; an ID check also rejects stale callbacks.
- Activity pause clears UI measurement state before stopping and closing the
  camera thread, preventing a permanent “measuring” display after backgrounding.
- Zone tracking has an independent three-slot reference-counted Y-plane pool;
  compatible metering has one reusable luminance buffer.

## Vendor boundaries and physical-device testing

Camera2 standardizes the API but not every HAL's stability or performance.
Models can differ in RAW availability per lens, logical/physical camera
exposure, supported stream combinations, row stride, Bayer pattern, black and
white levels, metadata completeness, buffer pressure, and error behavior.

Do not add manufacturer branches for these differences unless a measured,
documented calibration baseline is required. Prefer capability queries,
metadata, bounded buffers, and profile downgrade. Physical-device validation
should cover:

- RAW and non-RAW cameras;
- logical main camera and exposed physical lenses;
- first and repeated RAW/compatible measurements;
- repeated dual calibration while monitoring process/native memory;
- activity pause/resume during every measurement stage;
- session errors and fallback to preview-only;
- Chinese/English, portrait/landscape, and left/right-handed layouts.

## Planned controller decomposition

`CameraController` is still the largest coordinator. The next refactor should
preserve its public API while moving ownership into five internal components:

1. **Session coordinator** — owns `CameraDevice`, `CameraCaptureSession`, output
   surfaces/readers, preview request, open/close generation, and thread-bound
   cleanup.
2. **RAW meter** — owns RAW request scheduling, one-image capture window,
   timeout, analysis accumulation, and final RAW reading.
3. **Compatible meter** — owns YUV attempts, luminance buffer, preview fallback,
   single-frame result, and the per-session YUV health flag.
4. **Result pairer** — owns timestamp-indexed images/results and closes all
   unmatched images during cancellation.
5. **Recovery state machine** — owns profile, failure counters, retry limits,
   physical/logical route state, and deterministic downgrade decisions.

`CameraController` should then remain a facade joining application lifecycle,
the five owners, calibration stores, and user callbacks. Each extracted class
must document its calling thread and the resources it owns.

---

## 中文说明

本文记录当前“兼容稳定版”的相机行为，也是后续重构必须保持的设计契约。移动代码时，不应悄悄改变这里定义的输出流档位、降级顺序、资源所有权、校准先后或用户可见的恢复行为。

### 会话档位

应用按设备实际能力选择会话，不按厂商名称写死规则：

| 档位 | 预览 | RAW | YUV | 用途 |
|---|---:|---:|---:|---|
| `FULL` | 有 | 有 | 有 | RAW 测光、Zone 跟踪和 YUV 兼容路径 |
| `RAW_ONLY` | 有 | 有 | 无 | 组合流失败时保留高精度测光 |
| `COMPATIBLE` | 有 | 无 | 有 | 不占用 RAW 资源的 ISP 兼容测光 |
| `PREVIEW_ONLY` | 有 | 无 | 无 | Camera2 最低共同路径 |

自动模式在 RAW 与 YUV 均可用时从 `FULL` 开始；兼容模式绝不创建 RAW reader。会话创建失败时依次减少可选输出：

```text
FULL -> RAW_ONLY -> COMPATIBLE -> PREVIEW_ONLY
```

运行错误会先分类，再决定重试或降级；物理镜头最终还能退回逻辑相机。权限被拒、系统隐私策略、其他应用长期占用相机，或厂商连基础预览都实现异常时，仍可能无法打开任何档位。

### RAW 测光

- 只有镜头声明 RAW 能力并提供 `RAW_SENSOR` 尺寸时才启用。
- ISO 不高于 800 使用 3 帧，高于 800 使用 5 帧。
- 同一时刻只允许 1 张全尺寸 RAW 在途；分析并关闭当前 `Image` 后才提交下一帧。
- `Image` 与 `CaptureResult` 按传感器时间戳配对。成功、失败、超时、相机恢复、切后台或控制器关闭时，所有未配对图像都必须释放。
- Bayer 排列、黑白电平、曝光、ISO、光圈、白平衡增益和颜色矩阵均读取镜头元数据，不按厂商假设。

### 兼容测光

兼容测光使用 ISP 处理后的数据，不再进行多帧降噪融合：

1. YUV 输出存在时优先读取 1 个有效的 `YUV_420_888` 帧。
2. 复用同一块亮度缓冲，避免每个回调都分配整帧数组。
3. 最多尝试 3 个 YUV 帧，总等待不超过 250 ms。
4. YUV 缺失或无效时，立即分析 1 个 96 x 96 的显示预览样本。
5. 当前会话会记住 YUV 失败。处于兼容模式时，在返回本次结果后重开为 `PREVIEW_ONLY`，后续测光不再重复等待，也不再保留无用 YUV 流。

因此 YUV 是快速路径而不是必要条件。实际兼容边界是：设备能够向普通第三方应用提供基础 Camera2 预览。

### 校准顺序

支持 RAW 的镜头使用同一组固定参考输入，依次完成：

```text
RAW 测量 -> 关闭 RAW 图像 -> 兼容测量 -> 同时保存两种修正
```

阶段之间没有固定等待。不支持 RAW 的镜头跳过 RAW，只保存兼容修正。两种修正继续按厂商、型号和 camera ID 分开保存。

### 线程与资源所有权

- 主线程负责 `TextureView` 截图和所有 UI 回调。
- 相机线程负责设备、会话、reader、拍摄回调、图像/结果配对和超时状态。
- 每个取得的 `Image` 只能有一个明确所有者，并且必须恰好关闭一次。
- RAW、YUV 和暗角校准的超时任务会在结束或关闭相机时移除，操作 ID 还会拒绝过期回调。
- 切后台时先清除界面测量状态，再停止相机线程，避免永久停留在“测量中”。
- Zone 跟踪使用独立的三槽引用计数 Y 平面池；兼容测光使用一块可复用亮度缓冲。

### 厂商差异与真机验证

Camera2 统一了 API，但没有统一所有 HAL 的稳定性和性能。不同型号可能在镜头 RAW 能力、逻辑/物理镜头暴露、输出组合、stride、Bayer 排列、黑白电平、元数据完整度、缓冲压力和错误行为上不同。

除非是有实测依据的校准基线，不应为厂商增加硬编码分支。应优先依赖能力查询、实际元数据、有限缓冲和逐级降级。真机矩阵至少覆盖 RAW/非 RAW、逻辑/物理镜头、首次与连续测光、连续双校准及内存、各阶段切后台、会话错误降级，以及中英文/横竖屏/左右手布局。

### 后续拆分计划

`CameraController` 仍然是最大的流程协调类。下一轮在保持其公开接口不变的前提下，拆成：

1. **会话协调器**：拥有设备、会话、输出 surface/reader、预览请求、打开代次与关闭清理。
2. **RAW 测光器**：拥有 RAW 请求、单图像捕获窗口、超时、统计累积和最终结果。
3. **兼容测光器**：拥有 YUV 尝试、亮度缓冲、预览保底、单帧结果和会话级 YUV 健康状态。
4. **结果配对器**：拥有按时间戳索引的图像/结果，取消时统一关闭未配对图像。
5. **恢复状态机**：拥有会话档位、失败计数、重试上限、物理/逻辑路线和确定性降级决策。

拆分后 `CameraController` 只作为应用生命周期、五个资源所有者、校准存储和用户回调之间的门面。每个新类都必须注明调用线程及其拥有的资源。
