# Camera pipeline and device compatibility / 相机管线与设备兼容

English | [简体中文](#中文说明)

This document records the camera behavior of the current compatibility-stable
implementation. It is a design contract: later changes must not silently alter
the stream profiles, fallback order, resource ownership, calibration sequence,
or user-visible recovery behavior described here.

## Session profiles

`CameraController` selects the smallest profile that preserves the requested
function. Profiles are capability-driven rather than manufacturer-driven.

| Profile | Preview | RAW | YUV | Purpose |
|---|---:|---:|---:|---|
| `FULL` | yes | yes | yes | high accuracy plus Zone tracking where the HAL accepts it |
| `RAW_ONLY` | yes | yes | no | retain high-accuracy metering when the combined stream set fails |
| `COMPATIBLE` | yes | no | yes | ISP-processed compatible metering without RAW resources |
| `PREVIEW_ONLY` | yes | no | no | lowest common Camera2 path |

The user-facing modes map to these profiles as follows:

- **High accuracy (recommended):** starts at normalized `FULL`, uses RAW when
  possible, and automatically follows the full downgrade chain.
- **Stable:** starts at `RAW_ONLY` when RAW exists and otherwise at
  `PREVIEW_ONLY`. It never configures RAW and YUV in the same session.
- **Compatibility mode:** starts at `COMPATIBLE` and never creates a RAW reader.
  A preference stored as the old `COMPATIBLE` user mode migrates to this mode.

Session creation failure removes optional streams without crossing the selected
mode's isolation boundary. The complete high-accuracy chain is:

```text
FULL -> RAW_ONLY -> COMPATIBLE -> PREVIEW_ONLY
```

Before creating a session, Android 10 / API 29+ is asked whether the complete
output configuration is supported. A definitive rejection enters this same
downgrade chain immediately. Android 9, and HALs that cannot implement the
query, attempt real session creation, which remains the final compatibility
test.

Runtime camera errors are classified before retry or downgrade. A physical
camera route can finally fall back to its logical camera. Permission denial,
camera privacy policy, another application holding the camera, or a broken
vendor preview implementation can still prevent every profile from opening.

On logical multi-camera devices, the catalog exposes the logical route as an
automatic camera and gives every fixed physical lens a separate ID. Default
selection ranks back cameras with a usable advertised RAW stream first
(automatic, then main, then any RAW lens); non-RAW automatic/main routes follow.
Physical routing is preferred only when the logical camera reports CALIBRATED
physical synchronization; APPROXIMATE-sync logical cameras keep the logical
route so RAW buffers and physical results stay in the same timestamp domain.
Preview selection prefers an advertised 4:3 stream for both routes, even when
the logical active-array metadata is 16:9, so an automatic route and its fixed
main-lens route keep the same undistorted viewport. Cameras without 4:3 output
fall back to the aspect closest to their own sensor metadata.
Explicit physical routing is therefore opt-in. Monochrome and NIR physical
sensors are filtered out even if they expose a `SurfaceTexture` output.

## Preview requests and frame rate

- A repeating request normally targets only the displayed preview surface.
- YUV is added only while Zone tracking is enabled or one fast measurement is
  waiting for a frame; it is removed immediately after the measurement.
- Before RAW or vignetting capture, repeating preview/YUV requests stop so the
  last displayed frame stays frozen. They resume on success and every error path.
- The app never synthesizes or forces 60 fps. It chooses a range advertised by
  the active camera at or below 30 fps, considers preview/YUV minimum frame
  durations, then retries at 24 fps and finally without an explicit range if a
  vendor rejects the request.

## RAW metering

- RAW is used only when the selected camera advertises
  `REQUEST_AVAILABLE_CAPABILITIES_RAW` and exposes `RAW_SENSOR` sizes.
  LEGACY hardware-level devices are treated as non-RAW even if they stray
  into advertising RAW output.
- ISO below 500 uses one frame, ISO 500–1199 uses two frames, and ISO 1200 or
  above uses three frames. This favors shorter capture time and lower motion
  error over redundant noise reduction.
- RAW capture requests use the advertised `getOutputMinFrameDuration` for the
  smallest `RAW_SENSOR` size instead of the preview frame duration, because
  the full-size RAW sensor mode may be slower than the preview mode and some
  HALs reject or clamp the preview value.
- Only one full-size RAW request is in flight. The next request is submitted
  after the current `Image` has been analyzed and closed.
- `Image` and `CaptureResult` are paired by sensor timestamp. Unmatched images
  remain owned by the active operation and are closed on success, failure,
  timeout, camera recovery, activity pause, or controller shutdown.
- Bayer layout, dynamic/fixed black level, white level, color gains, color
  transform, exposure, sensitivity, aperture, and post-RAW boost come from the
  selected camera's metadata rather than from manufacturer assumptions.

## Preview-stream metering

Preview-stream metering consumes ISP-processed data and therefore does not perform
multi-frame noise-reduction fusion.

1. Prefer one valid `YUV_420_888` frame when the YUV output is active.
2. Pair the YUV `Image.timestamp` with the `CaptureResult.SENSOR_TIMESTAMP` of
   the same frame; never apply exposure metadata from an unrelated latest frame.
3. Reuse one luminance buffer instead of allocating a frame-sized array for
   every callback.
4. Try at most three YUV frames and never wait longer than 250 ms.
5. If YUV is missing or invalid, immediately analyze one 96 x 96 sample of the
   displayed preview.
6. Remember the YUV failure for the current camera session. In the internal
   YUV profile, reopen as `PREVIEW_ONLY` after delivering the reading so later
   measurements do not repeatedly pay the timeout or keep the unused stream.

This makes YUV an optimization, not a requirement. The practical compatibility
boundary is any device that can provide a basic third-party Camera2 preview.

## Calibration sequence

For a RAW-capable camera, one calibration request uses the same fixed reference
input for two sequential readings:

```text
RAW-stream reading -> close RAW images -> preview-stream reading -> save both corrections
```

There is no fixed delay between the stages. High accuracy and Stable run both
stages; Compatibility mode skips RAW. A camera without RAW support also hides and
skips the RAW stage. RAW and preview corrections remain separate per
manufacturer, model, and camera ID.

An installation token excluded from Android backup is compared with the backed
camera-environment record. After a device restore or a changed camera catalog,
all older metering and vignetting timestamps fall before a new validity cutoff.
The data is retained but cannot be applied or restored. One bilingual prompt
offers Later or opens Settings at the Calibration section; it never starts a
calibration capture directly.

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

## Controller component boundaries

`CameraController` preserves the public API used by `MainActivity` and acts as
the lifecycle/calibration facade. Mutable ownership is split into five internal
components:

1. **Session coordinator** — owns `CameraDevice`, `CameraCaptureSession`, output
   surfaces/readers, open/close generation, and thread-bound cleanup.
2. **RAW meter** — owns RAW request scheduling, one-image capture window,
   timeout, analysis accumulation, and final RAW reading.
3. **Compatible meter** — owns YUV attempts, luminance buffer, preview fallback,
   single-frame result, and the per-session YUV health flag.
4. **Result pairer** — owns timestamp-indexed images/results and closes all
   unmatched images during cancellation.
5. **Recovery state machine** — owns profile, failure counters, retry limits,
   physical/logical route state, and deterministic downgrade decisions.

Each extracted class documents its calling thread, cancellation behavior, and
the resources it owns. `CameraController` joins these owners to the application
lifecycle, calibration stores, stream-profile selection, and user callbacks.

---

## 中文说明

本文记录当前“兼容稳定版”的相机行为，也是后续修改必须保持的设计契约。不应悄悄改变这里定义的输出流档位、降级顺序、资源所有权、校准先后或用户可见的恢复行为。

### 会话档位

应用按设备实际能力选择会话，不按厂商名称写死规则：

| 档位 | 预览 | RAW | YUV | 用途 |
|---|---:|---:|---:|---|
| `FULL` | 有 | 有 | 有 | 厂商允许时同时保留高精度与 Zone 跟踪 |
| `RAW_ONLY` | 有 | 有 | 无 | 组合流失败时保留高精度测光 |
| `COMPATIBLE` | 有 | 无 | 有 | 不占用 RAW 资源的 ISP 兼容测光 |
| `PREVIEW_ONLY` | 有 | 无 | 无 | Camera2 最低共同路径 |

用户可见的三档模式与内部会话对应如下：

- **高精度（推荐）**：从能力归一化后的 `FULL` 开始，优先 RAW，并允许沿完整链路自动降级。
- **稳定模式**：有 RAW 时从 `RAW_ONLY` 开始，否则使用 `PREVIEW_ONLY`；RAW 与 YUV 绝不出现在同一会话。
- **兼容模式**：从 `COMPATIBLE` 开始，绝不创建 RAW reader。旧版本保存的“兼容”设置会迁移到当前兼容模式，保持原行为。

会话失败时只在当前模式允许的范围内减少输出，不会破坏稳定模式的隔离边界。高精度模式的完整降级链为：

```text
FULL -> RAW_ONLY -> COMPATIBLE -> PREVIEW_ONLY
```

Android 10（API 29）及以上会先询问 Camera HAL 是否支持完整输出组合；明确拒绝时立即进入同一降级链。Android 9 以及无法实现该查询的定制 HAL 会直接尝试实际创建会话，以真实结果作为最终判据。

运行错误会先分类，再决定重试或降级；物理镜头最终还能退回逻辑相机。多摄设备会列出自动逻辑相机和各固定物理镜头；默认选择先按“自动 RAW、主摄 RAW、其他 RAW”排序，再考虑不支持 RAW 的自动/主摄，因此厂商声明且实际提供 `RAW_SENSOR` 尺寸时优先 RAW。物理路由只在逻辑相机声明 CALIBRATED 物理同步时优先；APPROXIMATE 同步的逻辑相机保持逻辑路由，避免 RAW 缓冲与物理结果处于不同时间戳域。单色和红外物理传感器不会加入普通镜头列表。逻辑与物理路由优先使用两者都常见的 4:3 预览，即使逻辑相机元数据声明为 16:9，也不会在自动主摄与固定主摄之间切换时改变比例或看起来被拉伸；没有 4:3 输出时才退回最接近传感器元数据的比例。权限被拒、系统隐私策略、其他应用长期占用相机，或厂商连基础预览都实现异常时，仍可能无法打开任何档位。

### 预览请求与帧率

- 重复请求通常只包含屏幕预览；只有 Zone 跟踪或一次快速采样等待图像时才临时加入 YUV，测量完成后立即移除。
- RAW 或暗角捕获开始前停止重复预览/YUV 请求，屏幕保留最后一帧；成功和所有错误路径都会恢复预览。
- 不合成也不强制请求 60 fps。应用从当前相机声明的 30 fps 及以下范围中选择，并同时考虑预览/YUV 的最小帧时长；厂商拒绝时依次尝试 24 fps，最后不指定帧率并交回系统默认。

### RAW 测光

- 只有镜头声明 RAW 能力并提供 `RAW_SENSOR` 尺寸时才启用。LEGACY 硬件级别的设备即使异常声明 RAW 输出，也按不支持 RAW 处理。
- ISO 低于 500 使用 1 张，ISO 500–1199 使用 2 张，ISO 1200 及以上使用 3 张；优先减少捕获等待和手持晃动误差。
- RAW 捕获请求使用最小 `RAW_SENSOR` 尺寸的 `getOutputMinFrameDuration` 声明值，而不是预览帧时长：全尺寸 RAW 传感器模式可能比预览模式更慢，部分 HAL 会拒绝或静默钳制预览值。
- 同一时刻只允许 1 张全尺寸 RAW 在途；分析并关闭当前 `Image` 后才提交下一帧。
- `Image` 与 `CaptureResult` 按传感器时间戳配对。成功、失败、超时、相机恢复、切后台或控制器关闭时，所有未配对图像都必须释放。
- Bayer 排列、黑白电平、曝光、ISO、光圈、白平衡增益和颜色矩阵均读取镜头元数据，不按厂商假设。

### 预览流测光

预览流测光使用 ISP 处理后的数据，不再进行多帧降噪融合：

1. YUV 输出存在时优先读取 1 个有效的 `YUV_420_888` 帧。
2. 仅把 `Image.timestamp` 与相同 `CaptureResult.SENSOR_TIMESTAMP` 的曝光元数据配对，不再套用无关的“最近一帧”结果。
3. 复用同一块亮度缓冲，避免每个回调都分配整帧数组。
4. 最多尝试 3 个 YUV 帧，总等待不超过 250 ms。
5. YUV 缺失或无效时，立即分析 1 个 96 x 96 的显示预览样本。
6. 当前会话会记住 YUV 失败。处于内部 YUV 会话时，在返回本次结果后重开为 `PREVIEW_ONLY`，后续测光不再重复等待，也不再保留无用 YUV 流。

因此 YUV 是快速路径而不是必要条件。实际兼容边界是：设备能够向普通第三方应用提供基础 Camera2 预览。

### 校准顺序

支持 RAW 的镜头使用同一组固定参考输入，依次完成：

```text
RAW 流测量 -> 关闭 RAW 图像 -> 预览流测量 -> 同时保存两种修正
```

阶段之间没有固定等待。高精度与稳定模式依次完成两个阶段；兼容模式跳过 RAW。不支持 RAW 的镜头也会隐藏并跳过 RAW，只保存预览流修正。两种修正继续按厂商、型号和 camera ID 分开保存。

应用把不参与 Android 备份的安装标识与可备份的相机环境记录比较。检测到换机恢复或相机目录变化后，会建立新的有效时间门槛；旧测光和暗角数据继续保留，但不能再应用或回退。中英文提示只出现一次，提供“稍后处理”和“立即校准”；后者只打开设置的“校准”栏目，不直接启动测光或暗角拍摄。

### 线程与资源所有权

- 主线程负责 `TextureView` 截图和所有 UI 回调。
- 相机线程负责设备、会话、reader、拍摄回调、图像/结果配对和超时状态。
- 每个取得的 `Image` 只能有一个明确所有者，并且必须恰好关闭一次。
- RAW、YUV 和暗角校准的超时任务会在结束或关闭相机时移除，操作 ID 还会拒绝过期回调。
- 切后台时先清除界面测量状态，再停止相机线程，避免永久停留在“测量中”。
- Zone 跟踪使用独立的三槽引用计数 Y 平面池；预览流测光使用一块可复用亮度缓冲。

### 厂商差异与真机验证

Camera2 统一了 API，但没有统一所有 HAL 的稳定性和性能。不同型号可能在镜头 RAW 能力、逻辑/物理镜头暴露、输出组合、stride、Bayer 排列、黑白电平、元数据完整度、缓冲压力和错误行为上不同。

除非是有实测依据的校准基线，不应为厂商增加硬编码分支。应优先依赖能力查询、实际元数据、有限缓冲和逐级降级。真机矩阵至少覆盖 RAW/非 RAW、逻辑/物理镜头、首次与连续测光、连续双校准及内存、各阶段切后台、会话错误降级，以及中英文/横竖屏/左右手布局。

### 控制器组件边界

`CameraController` 保持供 `MainActivity` 使用的公开接口，作为生命周期与校准门面；可变资源和操作状态已经拆到五个内部组件：

1. **会话协调器**：拥有设备、会话、输出 surface/reader、打开代次与关闭清理。
2. **RAW 测光器**：拥有 RAW 请求、单图像捕获窗口、超时、统计累积和最终结果。
3. **预览流测光器**：拥有 YUV 尝试、亮度缓冲、预览保底、单帧结果和会话级 YUV 健康状态。
4. **结果配对器**：拥有按时间戳索引的图像/结果，取消时统一关闭未配对图像。
5. **恢复状态机**：拥有会话档位、失败计数、重试上限、物理/逻辑路线和确定性降级决策。

每个组件都注明调用线程、取消行为及其拥有的资源。`CameraController` 负责把这些所有者与应用生命周期、校准存储、会话档位选择和用户回调连接起来。
