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
| `RAW_ISOLATED` | no | yes | no | short 1–3-frame RAW window while the last preview buffer remains visible |

The user-facing modes search complete workflows rather than mapping one-to-one
to a resident profile:

- **High accuracy (recommended):** searches RAW workflows first: resident
  preview + RAW with preview + YUV for Zone and an isolated Zone RAW capture;
  resident preview + YUV with isolated RAW for all measurements; then `FULL`
  as a lower-stability option. If none succeeds, it searches Stable and then
  Compatibility workflows.
- **Stable:** uses resident preview + YUV and never creates a RAW reader.
- **Compatibility mode:** uses only the displayed preview (`PREVIEW_ONLY`) and
  never creates RAW or YUV readers. A preference stored as the old
  `COMPATIBLE` user mode migrates to this mode.

Candidate order is therefore:

```text
RAW split -> RAW fully isolated -> FULL -> Stable YUV -> Compatibility ISP
```

Advertised formats, output-count limits, and Android 10 / API 29+ mandatory
stream-combination tables conservatively reject impossible candidates. A table
omission remains `UNKNOWN`, not unsupported, and reaches a real session test.
`isSessionConfigurationSupported()` is also advisory: tested vivo/MediaTek
software has returned `false` while successfully recreating the same processed
session after isolated RAW. The actual asynchronous configuration callback is
the final compatibility result on every API level.

`Metering combination selection` offers two selectors over this same candidate
set. `System` performs real configure/workflow probes and caches a successful
plan per camera route, user mode, and OS build. `Manual` runs every required
stage, then shows the real preview and asks the user to mark flicker, stalls,
black/green frames, or stripes as normal or abnormal. A manual acceptance is
also scoped to the camera route and OS build.

Runtime camera errors are classified before retry or downgrade. A fixed-lens
selection advances through its available transport routes before finally
falling back to the logical camera. Permission denial,
camera privacy policy, another application holding the camera, or a broken
vendor preview implementation can still prevent every profile from opening.

On logical multi-camera devices, the catalog exposes the logical route as an
automatic camera and gives every fixed physical lens a separate ID. Default
selection ranks back cameras with a usable advertised RAW stream first
(automatic, then main, then any RAW lens); non-RAW automatic/main routes follow.
When a physical lens also has a public Camera2 ID, the same visible lens entry retains an internal
route ladder: public direct open, logical-camera fixed physical output, then logical fallback. A
hidden physical lens starts at the fixed physical output. This adds compatibility routes without
duplicating the lens in the picker. Fixed physical output is attempted regardless of whether the
logical camera reports CALIBRATED, APPROXIMATE, or no physical synchronization type, because that
value describes simultaneous sensors rather than gating one physical stream. On API 29+, automatic logical routes track the reported
active physical id; API 28 keeps the logical identity because it cannot report one reliably.

Hardware RAW capability and current-session RAW availability are stored separately. A downgrade
therefore disables RAW only for the current controller run. Reselecting a lens, changing metering
mode, or returning from the background starts a fresh high-capability probe; a stable session is
not upgraded in place, avoiding repeated green/striped-preview loops.

校准、兼容测光、RAW 测光、暗角校准和新建参数记录都使用该实际 identity（形式为
`logicalId@physicalId`）；历史参数记录没有该字段时保留为 null，不猜测或篡改旧记录。
Preview selection prefers an advertised 4:3 stream for both routes, even when
the logical active-array metadata is 16:9, so an automatic route and its fixed
main-lens route keep the same undistorted viewport. Cameras without 4:3 output
fall back to the aspect closest to their own sensor metadata.
Explicit physical routing is therefore opt-in. Monochrome and NIR physical
sensors are filtered out even if they expose a `SurfaceTexture` output.

## Preview requests and frame rate

- The resident profile always includes the displayed preview and may keep RAW
  or YUV according to the selected workflow.
- High-accuracy Zone tracking keeps preview + YUV resident. Its 1–3-frame meter
  window reconfigures to `RAW_ISOLATED`, keeps the last TextureView buffer on
  screen, and restores preview + YUV on every completion or error path.
- A constrained HAL can use the fully isolated RAW workflow for ordinary and
  Zone measurements, avoiding resident preview + RAW and `FULL` combinations.
- Before resident RAW or vignetting capture, repeating requests stop and resume
  on success and every error path.
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
- Formal RAW, YUV, DNG, colour-temperature, and vignetting operations require
  exactly equal `Image` and `CaptureResult` sensor timestamps. The reusable
  pairer still supports a caller-supplied tolerance for non-formal future work,
  but production metering supplies zero. Unmatched images
  remain owned by the active operation and are closed on success, failure,
  timeout, camera recovery, activity pause, or controller shutdown.
- Bayer layout, dynamic/fixed black level, white level, color gains, color
  transform, exposure, sensitivity, aperture, and post-RAW boost come from the
  selected camera's metadata rather than from manufacturer assumptions. A RAW
  frame is rejected when its Bayer CFA, black level, or white level cannot be
  resolved; it is never guessed as RGGB, zero black, or 16-bit full scale.

## Preview-stream metering

Preview-stream metering consumes ISP-processed data and therefore does not perform
multi-frame noise-reduction fusion. It does, however, convert every sampled pixel
back into a common linear-light domain before calculating the region median:

- displayed-preview RGB and reconstructed YUV RGB use the same decoder;
- a valid per-frame Camera2 tonemap curve is inverted channel by channel;
- gamma/preset metadata is used when present, otherwise the documented sRGB
  output transfer is used;
- YUV defaults to Camera2's JFIF/Rec.601 full-range encoding, while API 33+
  frame dataspace metadata can select limited range, BT.709, or BT.2020.

`READ_SENSOR_SETTINGS`/`MANUAL_SENSOR` and the advertised result keys provide an
initial exposure-metadata confidence signal, but their absence never permanently
removes YUV or displayed-preview calibration. Live preview results are observed
continuously; the first complete exposure-time, sensitivity and dynamic/static
aperture set confirms the route, and a later incomplete result cannot revoke it.
During a measurement, incomplete results are skipped through a bounded automatic
retry window. Only sustained absence fails that calibration stage; no user retry
control is required.

1. Prefer one valid `YUV_420_888` frame when the YUV output is active.
2. Pair the YUV `Image.timestamp` with the `CaptureResult.SENSOR_TIMESTAMP` of
   the same frame; never apply exposure metadata from an unrelated latest frame.
3. Read all three planes with their independent buffer offsets and row/pixel
   strides, reconstruct nonlinear RGB, invert the reported output response, and
   calculate Rec.709 linear luminance.
4. Use a per-region median, matching displayed-preview and RAW robust sampling,
   rather than averaging encoded Y values.
5. Examine at most 30 paired YUV frames within a 1.5-second bounded window.
6. If YUV is missing or invalid, analyze one synchronized 96 x 96 sample of the
   displayed preview.
7. Remember the sustained YUV failure for the current camera session. In the internal
   YUV profile, reopen as `PREVIEW_ONLY` after delivering the reading so later
   measurements do not repeatedly pay the timeout or keep the unused stream.

This makes YUV an optimization, not a requirement. The practical compatibility
boundary is any device that can provide a basic third-party Camera2 preview.

## Calibration sequence

One calibration request uses the same fixed reference input for a sequential,
source-complete plan. The selected metering mode does not hide a source. It
never performs RAW and YUV calibration captures concurrently:

```text
All supported sources: RAW sensor -> YUV compatible stream -> ISP display preview
```

There is no fixed delay between stages. A source which is unavailable is omitted rather than
shown as calibrated. RAW, YUV, and ISP corrections remain separate per manufacturer, model,
and camera identity. Processed-stream corrections from the previous Y-only/fixed-sRGB algorithm
are not applied after the luminance migration because their scene-dependent error cannot be
converted into a trustworthy constant offset. RAW corrections remain valid; YUV and ISP require
one new sequential calibration run.

Each stage opens its smallest safe profile (`RAW_ONLY`, `COMPATIBLE`, or `PREVIEW_ONLY`), then
closes it before the next source. The normal user-selected profile is restored at the end. Before
every formal measurement, a manual/compensated exposure preview is replaced by a tagged neutral
AE request; only two stable results carrying that exact camera/request generation may start
metering. Stale callbacks, lens changes, and session failures never save a mixed calibration.
The selected logical route and correction-storage camera identity are pinned for the complete run.
Some logical-camera HALs temporarily report no active physical-camera ID after each session reopen;
that unknown-to-known transition is accepted. If both observations provide concrete physical IDs
and the IDs differ, the run is still cancelled as a real lens change.

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
| `RAW_ISOLATED` | 无 | 有 | 无 | 屏幕保留最后一帧时短暂采集 1–3 帧 RAW |

用户可见的三档模式搜索完整工作流，不与某一个常驻会话一一对应：

- **高精度（推荐）**：先搜索 RAW 工作流：普通模式常驻预览 + RAW、Zone 常驻预览 + YUV 并瞬时仅 RAW；所有模式常驻预览 + YUV、测光时瞬时仅 RAW；最后把 `FULL` 作为稳定性较低的候选。RAW 候选全部失败后继续搜索稳定与兼容工作流。
- **稳定模式**：常驻预览 + YUV，不创建 RAW reader。
- **兼容模式**：只使用显示预览（`PREVIEW_ONLY`），不创建 RAW 或 YUV reader。旧版本保存的“兼容”设置会迁移到当前兼容模式，保持原行为。

候选精度顺序为：

```text
RAW 分离 -> RAW 完全瞬时隔离 -> FULL -> 稳定 YUV -> 兼容 ISP
```

输出格式、输出数量上限以及 Android 10（API 29）以上的强制流组合表只会保守排除明确不可能的候选；不在强制表中的组合标为“未知”，仍进入真实会话测试。`isSessionConfigurationSupported()` 的否定结果也只作提示：实测部分 vivo/MediaTek 软件在瞬时 RAW 后重建相同处理流时会返回 `false`，实际创建却成功。所有 API 版本都以异步真实配置回调为最终判据。

“测光组合选择”的“系统设置”和“手动选择”使用同一组候选。系统设置执行真实配置/工作流探测，并按相机路由、用户模式和系统版本缓存成功方案；手动选择先运行组合要求的每个真实阶段，再显示预览，让用户把闪烁、卡顿、黑屏、绿屏或条纹判为正常/不正常。人工确认也只对同一相机路由和系统版本有效。

运行错误会先分类，再决定重试或降级；固定镜头最终还能退回逻辑相机。多摄设备会列出自动逻辑相机和各固定物理镜头；默认选择先按“自动 RAW、主摄 RAW、其他 RAW”排序，再考虑不支持 RAW 的自动/主摄，因此厂商声明且实际提供 `RAW_SENSOR` 尺寸时优先 RAW。同一物理镜头若也出现在公开 `cameraIdList` 中，界面仍只保留一个镜头条目，内部按“公开 ID 直连 → 逻辑相机固定物理输出 → 逻辑相机回退”依次验证；隐藏物理镜头则从固定物理输出开始。固定物理路由会在 CALIBRATED、APPROXIMATE 和未声明同步类型的设备上都实际尝试；同步类型只影响多个传感器同时工作的时间关系，不能用来阻止单个物理输出。硬件 RAW 能力与当前会话是否真的带 RAW 输出分开记录：降级只在本次控制器运行内保持，重新选择镜头、切换测光模式或从后台返回时重新探测高能力档位，同一次稳定运行中不自动升级，避免在绿屏/条纹设备上反复重开。API 29+ 的自动逻辑路由会记录每帧报告的 active physical ID；逻辑回退后的测光和校准采用实际运行 identity，不写入原副摄 key。API 28 保持逻辑复合相机身份，绝不猜测物理镜头。单色和红外物理传感器不会加入普通镜头列表。逻辑与物理路由优先使用两者都常见的 4:3 预览，即使逻辑相机元数据声明为 16:9，也不会在自动主摄与固定主摄之间切换时改变比例或看起来被拉伸；没有 4:3 输出时才退回最接近传感器元数据的比例。权限被拒、系统隐私策略、其他应用长期占用相机，或厂商连基础预览都实现异常时，仍可能无法打开任何档位。

### 预览请求与帧率

- 常驻工作流始终包含屏幕预览，并按选中的组合保留 RAW 或 YUV。
- 高精度 Zone 跟踪常驻预览 + YUV；1–3 帧测光窗口切换到 `RAW_ISOLATED`，屏幕保留最后一个 TextureView 缓冲，成功或错误路径都恢复预览 + YUV。
- 受限 HAL 可让普通测光与 Zone 都采用 RAW 完全瞬时隔离，避免常驻预览 + RAW 和 `FULL` 组合。
- 常驻 RAW 或暗角捕获开始前停止重复请求，成功和所有错误路径都会恢复预览。
- 不合成也不强制请求 60 fps。应用从当前相机声明的 30 fps 及以下范围中选择，并同时考虑预览/YUV 的最小帧时长；厂商拒绝时依次尝试 24 fps，最后不指定帧率并交回系统默认。

### RAW 测光

- 只有镜头声明 RAW 能力并提供 `RAW_SENSOR` 尺寸时才启用。LEGACY 硬件级别的设备即使异常声明 RAW 输出，也按不支持 RAW 处理。
- ISO 低于 500 使用 1 张，ISO 500–1199 使用 2 张，ISO 1200 及以上使用 3 张；优先减少捕获等待和手持晃动误差。
- RAW 捕获请求使用最小 `RAW_SENSOR` 尺寸的 `getOutputMinFrameDuration` 声明值，而不是预览帧时长：全尺寸 RAW 传感器模式可能比预览模式更慢，部分 HAL 会拒绝或静默钳制预览值。
- 同一时刻只允许 1 张全尺寸 RAW 在途；分析并关闭当前 `Image` 后才提交下一帧。
- 正式 RAW、YUV、DNG、色温和暗角操作要求 `Image` 与 `CaptureResult` 的传感器时间戳完全相同。通用 pairer 仍支持由调用方传入容差，供未来的非正式用途使用；生产测光统一传入零容差。成功、失败、超时、相机恢复、切后台或控制器关闭时，所有未配对图像都必须释放。
- Bayer 排列、黑白电平、曝光、ISO、光圈、白平衡增益和颜色矩阵均读取镜头元数据，不按厂商假设；CFA、黑电平或白电平无法解析时拒绝该 RAW 帧，绝不猜测为 RGGB、零黑位或 16 位满量程。

### 预览流测光

预览流测光使用 ISP 处理后的数据，不再进行多帧降噪融合：

`READ_SENSOR_SETTINGS`/`MANUAL_SENSOR` 和结果键只作为曝光元数据的初始可信提示；缺少这些静态声明不会永久移除 YUV 或屏幕预览校准。应用持续观察实际预览结果，首次取得完整曝光时间、ISO 和动态/静态光圈后即确认该路线可用，后来某一帧缺字段也不会撤销。测量过程中会在有界时间窗内自动跳过不完整结果，只有连续多帧仍缺失才判当前阶段失败，不需要增加用户重试操作。

1. YUV 输出存在时优先读取 1 个有效的 `YUV_420_888` 帧。
2. 仅把 `Image.timestamp` 与相同 `CaptureResult.SENSOR_TIMESTAMP` 的曝光元数据配对，不再套用无关的“最近一帧”结果。
3. 复用同一块亮度缓冲，避免每个回调都分配整帧数组。
4. 最多尝试 3 个 YUV 帧，总等待不超过 250 ms。
5. YUV 缺失或无效时，最多尝试 3 次严格时间戳配对的 96 x 96 显示预览样本；截图前后 `SurfaceTexture` 时间戳不一致或没有对应元数据时明确报错。
6. 当前会话会记住 YUV 失败。处于内部 YUV 会话时，在返回本次结果后重开为 `PREVIEW_ONLY`，后续测光不再重复等待，也不再保留无用 YUV 流。

因此 YUV 是快速路径而不是必要条件。实际兼容边界是：设备能够向普通第三方应用提供基础 Camera2 预览。

### 校准顺序

一次校准使用同一组固定参考输入，完成硬件支持的全部来源；当前测光模式不会隐藏任何来源，且绝不并发执行 RAW 与 YUV 校准捕获：

```text
全部可用来源：RAW 传感器 -> YUV 兼容流 -> ISP 显示预览
```

阶段之间没有固定等待。不支持的来源会被跳过，而不会显示为已校准。RAW、YUV 与 ISP 修正继续按厂商、型号和实际 camera identity 分开保存。旧版共享的 `compatible_user_*` 修正仅作为带标签的兼容回退：只在相应处理后来源尚未重新校准时应用，绝不复制为看似精确的新 YUV 或 ISP 校准记录。

每一阶段都打开最小的安全会话（`RAW_ONLY`、`COMPATIBLE` 或 `PREVIEW_ONLY`），再关闭后切换下一个来源；完成后恢复用户原本选择的会话档位。每次正式测光前，手动/补偿曝光预览都会被带标签的中性 AE 请求替代；只有带有完全相同相机代次和请求代次的连续两帧稳定结果才能开始测光。过期回调、镜头切换或会话失败都不会保存混合来源的校准。

一次校准会固定用户选择的逻辑相机路由和修正存储 camera identity。部分逻辑相机 HAL 在每次重开会话后会短暂不报告 active physical camera ID；这种“未知 → 已知”的过渡允许继续。只有前后两次都明确报告了物理镜头且 ID 不同时，才按真实镜头切换安全中止，避免 RAW 完成后误中止 YUV/ISP，同时也避免把不同镜头的数据写进同一份校准。

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
5. **恢复状态机**：拥有会话档位、失败计数、重试上限、候选连接路线和确定性降级决策。

RAW Bayer 测光只接受 RGGB、GRBG、GBRG、BGGR 四种单样本 CFA。`CFA_RGB`、MONO、NIR、
未知 CFA 或 LEGACY HAL 即使声明 RAW 输出，也只能进入预览/YUV 兼容路径；不得把它们传给
Bayer/native 统计器，更不得以 RGGB 作为默认猜测。

所有正式测光、RAW/DNG 记录、色温和暗角帧配对都要求完全相同的传感器时间戳。YUV 和
`TextureView` 兼容测光也不再把任意 `latestResult` 与显示截图组合：显示截图会在读取前后
核验同一 `SurfaceTexture` 时间戳，并仅消费精确匹配的 capture result；三次竞争或无匹配后
明确报错。这样可能在不规范 HAL 上减少可用读数，但不会把相邻帧的曝光、ISO 用于当前画面。

`CameraController.stop()` 不在主线程等待 Camera2 close。它把关闭操作交给现有相机线程，并在
该线程释放 session、device、readers 后回到主线程完成 thread 交接；如果在此期间重新进入前台，
启动请求会延后到旧线程彻底结束，从而避免旧 generation 关闭新会话。

预览健康检测在 UI 线程每四个显示帧取一次 64×64 临时样本，立即分析后回收 bitmap。纯分析器对
绿色、近黑和冻结画面只给出 suspect，避免把真实场景误判为故障；持续六帧的高对比周期性横/竖
黑白条纹才会触发一次 `PREVIEW_ONLY` 安全会话恢复。恢复的安全会话必须再连续通过三次独立健康
采样才被接受；确认期间再次出现条纹时，固定物理镜头退回逻辑相机作对照，逻辑路线则显示最终故障。
单次健康检测不会永久拉黑设备或流尺寸；系统/人工完整工作流成功后会按相机路由和系统版本缓存，
系统升级或相机环境变化后重新筛选。

参数记录在移动 pending JPEG/DNG 前写入只包含 category/record UUID 的事务标记；索引原子提交后
才清除标记。应用启动时，已存在于索引的记录保留其文件并清除残留标记；未提交标记只会清理它
精确对应的 JPEG/DNG。正常异常路径同时回滚内存索引和已移动文件，避免产生无索引记录。

预览变换读取 `TextureView` 所在 Display 的 rotation，而非默认屏幕；Activity 注册 DisplayListener，
因而 180° 旋转即使没有 configuration change 也会重新计算矩阵。前摄预览作水平镜像；独立的
`ScreenToSensorCoordinateTransform` 在触点、RAW 特征匹配和已保存 RAW 网格中统一先撤销镜像、
再按 Camera2 front/back facing 相对旋转映射到传感器坐标。记录 JSON 同时保存该镜像标志，旧记录
缺省为 `false`，保持历史数据兼容。

每个组件都注明调用线程、取消行为及其拥有的资源。`CameraController` 负责把这些所有者与应用生命周期、校准存储、会话档位选择和用户回调连接起来。
