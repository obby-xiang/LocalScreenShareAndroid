# TB-ENG-006 JPEG 降级模式客户端查看器接回 + 死代码清理实施计划（2026-10-22）

> 基线：1424 tests / 140 classes（HEAD `e6baf62`）
> 只含 JVM 短测试；设备/仪器/长时项归 TB-ENG-013。

## 1. 结论摘要

- 客户端接收链路本身 codec 无关：`ClientMediaSession` 按描述符到注册表取 `DecoderFactory`，`JpegDecoder` 把完整帧交给 `RenderTarget.renderEncodedImage(...)`；框架层 JPEG 端到端已有 JVM 用例。
- 断点只有三处，全在 Android 装配层：① `ScreenViewerService` 会话能力硬编码只有 H.264，JPEG 描述符被判不支持并 CLOSED；② HELLO 的 JPEG 能力依赖 `MediaCodecList` 探测，而 JPEG 不经 MediaCodec，`getVideoCapabilities()` 为 null 时被跳过 → 服务端收不到 JPEG 能力；③ 生产渲染目标只构造 `SurfaceRenderTarget`，`BitmapRenderTarget` 零引用且实现不适合生产（解码线程直接 `setImageBitmap`、每帧全尺寸分配）。
- 另有约 500 行死代码：`ScreenViewerService` 内嵌的旧 JPEG 查看器 `ScreenShareViewer` 与从未赋值的字段 `mScreenShareViewer`、只被它引用的 `support/MutableReference`、若干 import 与两个资源。
- 4 个批次：能力声明统一 → JPEG 渲染目标与双宿主接线 → 编码格式选择入口 → 死代码清理。预计新增 3 个测试类 / 19 条用例，基线只增不减。

## 2. 现状核对（要点）

- 双宿主：会话持有稳定的 `SwitchableRenderTarget`，delegate 指向当前宿主（悬浮窗 `SurfaceShareViewer` / 全屏 `ViewerActivity`），切换只 `setDelegate` + `rebindSurface`；两宿主背后是同一个 `ViewerPanelView`（TextureView + 隐藏 snapshotView + HUD）。
- 协议无单独 JPEG 消息：`Descriptor` 带 `codec_id="jpeg"`、`bitstream_format="JPEG_FRAME"`、空 config；每帧都是 join point（保留最新帧策略）。
- 当前 JPEG 帧走不通：HELLO 无 JPEG 能力时服务端按 `REQUIRES_EXPLICIT_FALLBACK` 关闭；HELLO 若含 JPEG，客户端会话能力硬编码 H.264 导致 CLOSED，帧不进解码也不计指标，loading 永不消失。
- 服务端选格式只在开始时读偏好（`mIsH264`），运行时切换不支持（观察者只打日志"applies on the next start"）；设置界面编码格式为只读，`Preferences.setProjectionCodec` 无调用方。
- 死代码（B4 删除，逐条 grep 取证）：内嵌类 `ScreenShareViewer`（约 497 行）、字段 `mScreenShareViewer`、`support/MutableReference`、仅死类使用的 import、`res/color/screen_share_viewer_foreground.xml`、`dimen screen_share_viewer_elevation`。保留 `screen_share_viewer_min_size`（活代码在用）。`BitmapRenderTarget` 复用而非删除（B2 重写）。
- B4 实施时复核发现：`res/layout/widget_screen_share_viewer_settings.xml` 的唯一引用就是该死类，删除类后它会变成新的未使用资源（会让 lint 未使用资源告警 +1，违反「减少或持平」），因此连同只被该布局引用的 `viewer_opacity_setting_label`、`viewer_rounded_setting_label` 一并删除；计划原文「viewer settings 布局（活代码在用）」的判断有误。
- 已有 JVM 覆盖：`JpegCodecTest`、`StreamFrameworkEndToEndTest.jpegEmptyConfig_worksEndToEnd`、`ClientMediaSessionTest.emptyConfigJpegDescriptor_isAccepted`、`ScreenStreamProtoMapperTest`、`SwitchableRenderTargetTest`。

## 3. 目标设计（三个共用决定）

- **D1 能力单一来源**：客户端只探测一次，HELLO 与会话用同一个 `ClientCapabilities`；JPEG 能力不看 MediaCodecList，按"本端用 `BitmapFactory` 显示 JPEG、恒可解"以 `CodecCapability.any(CodecIds.JPEG)` 并入。
- **D2 渲染目标按宿主组合而非按 codec 选择**：每个宿主一个 `HostRenderTarget`，内持 `SurfaceRenderTarget` + `BitmapRenderTarget` 按方法分派；描述符到达无需换 delegate，避免竞态。
- **D3 JPEG 显示层**：`ViewerPanelView` 增加 `frameView`（ImageView，texture 之上、snapshot 之下，H.264 时 GONE）；`BitmapRenderTarget` 在 decode 线程解码（按宿主内容框 `inSampleSize` 降采样）、主线程显示（注入 Executor）、单帧挂起合并（旧 pending 安全 recycle，已显示位图交 GC）；`captureSnapshot()` 优先取 frameView 位图。

## 4. 批次划分

| 批次 | 主题 | 主要文件 | 新增测试 | 风险 |
| --- | --- | --- | --- | --- |
| B1 | 能力声明统一 | `client/CodecCapabilityProbe.java`、新增 `client/ClientCapabilitiesFactory.java`、`client/ScreenShareClient.java`、`service/ScreenViewerService.java` | `ClientCapabilitiesFactoryTest`（4） | 低 |
| B2 | JPEG 渲染目标与双宿主接线 | 新增 `render/HostRenderTarget.java`、`render/BitmapScalePolicy.java`；重写 `render/BitmapRenderTarget.java`；`render/ViewerPanelView.java`、`render/ViewerHost.java`、`ViewerActivity.java`、`service/ScreenViewerService.java` | `BitmapScalePolicyTest`（6）、`HostRenderTargetTest`（5）、`ClientMediaSessionJpegTargetTest`（2） | 中高 |
| B3 | 编码格式选择入口 | `MainActivity.java`、`res/layout/widget_screen_share_video_settings.xml`、`res/values*/strings.xml`、`settings/ProjectionControlValues.java` | `ProjectionControlValuesTest`（+2） | 低 |
| B4 | 死代码清理 | `service/ScreenViewerService.java`、删 `support/MutableReference.java`、资源、`docs/windowing-session-design.md` | 0（用例数必须持平） | 低 |

顺序 B1 → B2 → B3 → B4；B4 与 B2 同文件，必须最后做。

## 5. B1 详细方案（首个实施批）

1. 新增 `client/ClientCapabilitiesFactory`（纯 Java）：`static ClientCapabilities advertised(Map<String, CodecCapability> probedVideo, Map<String, AudioCodecCapability> probedAudio)`——复制探测结果，缺 `CodecIds.JPEG` 则以 `CodecCapability.any(JPEG)` 补上（Javadoc 写明：JPEG 由 `BitmapFactory` 显示，不经 MediaCodec）；null 入参按空处理；音频能力原样保留。
2. `CodecCapabilityProbe.probeClientDecoders()` 改为探测 H.264/AAC 后调用工厂，不再要求 JPEG 有 MediaCodec 视频能力。
3. `ScreenShareClient` 新增构造重载接收 `ClientCapabilities advertised`，`start()` 用它构造 HELLO；旧构造保留并默认探测。
4. `ScreenViewerService.startMediaSession()`：会话开始前探测一次，`mClientCapabilities` 同时传给 `ClientMediaSession`（替换 H.264 硬编码）与两处 `new ScreenShareClient(...)`；重试沿用同一实例。
5. 修正 `ScreenViewerService` 中"resolves the H.264 decoder"的过期注释为"resolves the active codec's decoder"。

测试 `ClientCapabilitiesFactoryTest`（4）：只有 H.264 → 结果含 H.264+JPEG；已含 JPEG → 不重复且保留原细节；音频能力原样保留（含空表）；null 入参 → 空视频表 + 仍补 JPEG（或按约定抛异常，实现时二选一并写进 Javadoc）。

## 6. B2–B4 概要

- **B2**：新增 `BitmapScalePolicy`（`sampleSize(frameW, frameH, boxW, boxH)`：任一 ≤0→1；宽高排序后取 min 比值，向下取 2 的幂，夹 [1,8]，旋转不变）；重写 `BitmapRenderTarget`（构造接 `ViewerHost` + 主线程 `Executor`；decode 线程解码 + 原子替换 pending + 调度主线程 deliver；`clear()` 清 pending）；新增 `HostRenderTarget`（`getSurface`/`setContentSize` → surface 半，`renderEncodedImage` → bitmap 半，`release` 释放两半）；`ViewerPanelView` 加 `frameView`（`ScaleType.MATRIX`、默认 GONE、不可点击，圆角/矩阵与 texture 同步，`captureSnapshot` 优先 frameView）；`ViewerHost` 加 `showEncodedFrame`/`clearEncodedFrame`/`getContentBox`；`ViewerActivity` 转发；`ScreenViewerService` 两个宿主的渲染目标换为 `HostRenderTarget`，teardown/dismiss 清 frame 层。不改 `SurfaceRenderTarget`/`SwitchableRenderTarget`/`ClientMediaSession`/`JpegDecoder`/协议/服务端。测试：`BitmapScalePolicyTest`（6）、`HostRenderTargetTest`（5）、`ClientMediaSessionJpegTargetTest`（2，JPEG 帧到 bitmap 半、H.264 风格帧不到 bitmap 半）。
- **B3**：布局把只读 codec 文本换成 `MaterialButtonToggleGroup`（h264/jpeg 两项，复用现有文案键）；码率行与关键帧行包进带 id 的行容器；`MainActivity` 切换时写 `Preferences.setProjectionCodec`，`applyVideoSettingsControls()` 按 `isBitrateApplicable()`/`isIdrIntervalApplicable()` 显隐；`ProjectionControlValues` 新增这两个方法（都返回 `h264`）；说明文案改为"下次开始共享时生效"，删除旧的"固定 H.264"文案键并同步 `values-zh`。测试：`ProjectionControlValuesTest` +2。
- **B4**：删除 §2 死代码清单全部对象与失效 import（含 B4 复核补充的 `widget_screen_share_viewer_settings.xml` 与两个只被它使用的字符串）；修正 `ScreenViewerService` 类 Javadoc 与 `docs/windowing-session-design.md` 的过期描述；验证用例数与类数与 B3 后**持平**、`git grep` 零命中、`lintDebug` 资源告警减少或持平。

## 7. 编码格式切换的产品口径（已裁决）

- 改为二选一，**只在下次开始共享时生效**；不做运行时 H.264↔JPEG 切换（服务端采集路径不支持）。
- H.264 保持默认；JPEG 标注"兼容模式"，说明编码开销更高。
- JPEG 模式下隐藏码率与关键帧间隔行。
- H.264 编码器失败自动回退 JPEG **另立条目**，本任务不做。

## 8. 裁决记录（2026-10-22，主 K3 定）

1. B3 入口形态用 `MaterialButtonToggleGroup` 两按钮。
2. JPEG 模式下隐藏码率/关键帧行。
3. 降采样按宿主内容框、上限 8×。
4. 自动回退另立条目（登记看板）。

## 9. 风险与边界

| 风险 | 批次 | 缓解 |
| --- | --- | --- |
| `frameView` 影响 H.264 渲染或手势 | B2 | 默认 GONE、不可点击；H.264 用例与手势路径全绿 |
| 解码线程触碰视图 | B2 | 解码与显示分离，显示统一经 Executor 到主线程 |
| 主线程位图积压 | B2 | 单帧挂起合并 + 旧 pending 安全 recycle |
| 内存/GC | B2 | `inSampleSize` 按宿主框降采样（≤8×）；不引入 `inBitmap`；实测归 TB-ENG-013 |
| 能力声明改变服务端回退 | B1 | 客户端恒声明 JPEG；H.264 行为不变；`StreamHubCapabilityJoinTest` 覆盖 |
| 误删活代码 | B4 | 逐个 grep；`lintDebug` 资源告警复核 |

明确不做：H.264 路径任何改动；服务端采集/编码；运行时格式切换与自动回退；JPEG 解码性能工程（`inBitmap` 等）；多档流/RTP/WebRTC；协议变更；`ClientMessageListener` 清理；`EncodedAccessUnit` clone 语义；设备与仪器测试（归 TB-ENG-013）。

## 10. 验证口径

每批：清空 test-results 后全量 `:app:testDebugUnitTest` 0 failures（数字只增不减，1424/140 → 预计 1443/144，B4 持平）；**`./gradlew build :app:assembleDebugAndroidTest`**；`:app:lintDebug` 0 errors；B3 加 `check_strings.py`/`check_cjk.py`/`check_docs.py`。
