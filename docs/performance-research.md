# 投屏与串流性能优化业界调研

> 状态：调研完成，未落地；调研日期 2026-10-22
> 证据边界：本文整理降低功耗、提升流畅性、减少卡顿掉帧的业界做法，并对本项目做差距分析。文中有出处的结论标注来源；“推测”与“工程判断”明确标记。**未做设备实测、未做功耗测量、未做对照实验**；§4 的收益与工程量评分是工程判断，不是测量结果。
> 相关文档：[渐进优化路线图](optimization-roadmap.md) · [媒体链路目标架构](media-architecture.md) · [采集格式与传输方式业界调研](transport-research.md) · [决策看板](decision-board.md) · [任务看板](task-board.md)

> 一句话结论：业界没有“单点大招”，主流是四件事——**按内容出帧**、**把呈现时机交给 vsync/PTS**、**小队列 + 有界丢弃 + 把积压反馈回编码端**、**用热余量提前降档**。我们方向基本正确，差距主要在“阈值太宽、反馈太晚、客户端配置太保守、传输层是 TCP”。

## 0. 阅读约定

| 标记 | 含义 |
| --- | --- |
| 当前实现 | 可在当前仓库代码或已定裁决中直接核对 |
| 已验证证据 | 来自上游源码、官方文档或协议规范，可复核 |
| 建议 | 本文给出的做法、参数或排序建议，尚未落地 |
| 推测 / 工程判断 | 无公开对照实验，或基于本项目现状的主观评分 |

## 1. 降低功耗

### 1.1 编码侧

| 做法 | 谁在用 | 机制 | 证据 |
| --- | --- | --- | --- |
| 硬编优先，不做软编兜底 | scrcpy、Moonlight、所有商用投屏 | 专用编码硅，CPU 与能耗低一个量级 | 【测评】硬件解码能耗 < 软件解码的 9%（[arXiv 2402.09001](https://arxiv.org/abs/2402.09001)）；编码端方向相同但无同等级公开数字 → 【推测】 |
| 低延迟编码参数：`KEY_LATENCY=1`、`KEY_PRIORITY=0`、`KEY_OPERATING_RATE=fps` | scrcpy（latency/priority）；Moonlight（解码侧 operating rate） | 编码器尽快吐帧、实时调度、声明工作负载 | 【源码】[SurfaceEncoder.java](https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/video/SurfaceEncoder.java)；【官方】[MediaFormat](https://developer.android.com/reference/android/media/MediaFormat) |
| 内容自适应帧率（无变化就不出帧） | scrcpy（VirtualDisplay 路径天然如此） | 合成器只有 damage 才产帧，静态画面 0 帧 0 码率 | 【源码/文档】[scrcpy doc/video.md](https://github.com/Genymobile/scrcpy/blob/master/doc/video.md) |
| 空闲时暂停编码 `PARAMETER_KEY_SUSPEND` | 少见，官方为 surface-input 场景设计 | 保留编码器实例，输入被丢弃；恢复不必重建 | 【官方】[MediaCodec](https://developer.android.com/reference/android/media/MediaCodec)（API 29） |
| 静态补帧保活 `KEY_REPEAT_PREVIOUS_FRAME_AFTER=100ms` | scrcpy | 没新帧时每 100 ms 重复上一帧，避免解码/显示端“饿死”花屏 | 【源码】SurfaceEncoder.java |
| 脏矩形/增量更新 | VNC（RFB）、RDP | 只传变化区域 | 【官方】[RFC 6143 §7.5.3](https://www.rfc-editor.org/rfc/rfc6143)。**对硬编+VirtualDisplay 不适用** |
| Intra refresh 行/片刷新 | Android 编码器、x264 `--intra-refresh` | 每帧强制刷一部分宏块，码率平坦、无 IDR 尖峰、低延迟抗错 | 【官方】`KEY_INTRA_REFRESH_PERIOD`（API 24） |

### 1.2 观看侧

| 做法 | 谁在用 | 机制 | 证据 |
| --- | --- | --- | --- |
| SurfaceView 取代 TextureView | ExoPlayer/Media3、Moonlight Android | 独立 layer 可走 HWC overlay，省内存带宽/电量；帧时序更准 | 【官方】TextureView 在部分机型上**多耗最多 30% 整机功耗**（[Media3 battery](https://developer.android.com/media/media3/exoplayer/battery-consumption)） |
| 解码器低延迟模式 | Moonlight Android | `KEY_LOW_LATENCY=1`（API 30，仅解码器）+ 厂商 key + `KEY_OPERATING_RATE=Short.MAX_VALUE` | 【源码】[MediaCodecHelper.java](https://github.com/moonlight-stream/moonlight-android/blob/master/app/src/main/java/com/limelight/binding/video/MediaCodecHelper.java) |
| 显示刷新率匹配流帧率 | Moonlight Android | 遍历 `Display.Mode`，选 `refresh >= fps && refresh % fps <= 3` 的模式，用 `preferredDisplayModeId`；Android 12+ 改用 `setFrameRate` | 【源码】[Game.java](https://github.com/moonlight-stream/moonlight-android/blob/master/app/src/main/java/com/limelight/Game.java) |
| `Surface.setFrameRate` 告知内容帧率 | Media3/播放器 | 系统据此选刷新率；**不会限帧**，只是 hint | 【官方】[Surface.setFrameRate](https://developer.android.com/reference/android/view/Surface) |
| 降分辨率/降采样 | 所有串流客户端 | 像素率线性下降，编解码与带宽同步下降 | 【推测】工程共识 |

### 1.3 系统侧

| 做法 | 谁在用 | 机制 | 证据 |
| --- | --- | --- | --- |
| 热状态分级降档 | 游戏、投屏 | `getCurrentThermalStatus` + listener（API 29，SEVERE=3） | 【官方】[PowerManager](https://developer.android.com/reference/android/os/PowerManager) |
| 热余量**预测**（10 s） | 游戏/AR | `getThermalHeadroom(forecastSeconds)`（API 30），0~1；官方建议 ≤1 次/秒 | 【官方】PowerManager；另有 `getThermalHeadroomThresholds`（API 35）、`addThermalHeadroomListener`（API 36） |
| ADPF / `PerformanceHintManager` | 游戏引擎 | 上报目标帧时长 + 实际耗时，系统调频/选核 | 【官方】[ADPF 指南](https://developer.android.com/games/optimize/adpf)；串流端未采用（Moonlight/scrcpy 主仓库检索 0 处）→ 【源码检索】 |
| 前台服务 + mediaProjection 类型 | 所有投屏 | 亮屏串流不属于 Doze 场景 | 【官方】FGS service types |

**功耗数据的现实**：没有找到 scrcpy/Moonlight 的严格功耗测评；可引用的是 CPU 占用类二手数据（scrcpy 5.0 硬解“CPU 最多降 10 倍”，[Android Authority](https://www.androidauthority.com/scrcpy-5-0-update-hardware-decoding-3719521/)）与学术解码能耗数据。**不要把解码能耗比例直接外推到编码端或手机 SoC。**

## 2. 提升流畅性

| 做法 | 谁在用 | 机制 | 证据 |
| --- | --- | --- | --- |
| PTS/Choreographer 对齐 vsync | Moonlight Android | 独立 HandlerThread 跑 `Choreographer.postFrameCallback`；距上次呈现 ≥80% 帧间隔才 `releaseOutputBuffer(idx, frameTimeNanos)` | 【源码】[MediaCodecDecoderRenderer.java](https://github.com/moonlight-stream/moonlight-android/blob/master/app/src/main/java/com/limelight/binding/video/MediaCodecDecoderRenderer.java) |
| 多档帧节奏模式 | Moonlight Android（默认最低延迟） | `MIN_LATENCY / BALANCED / CAP_FPS / MAX_SMOOTHNESS` 四档 | 【源码】同上 |
| 显式 vsync 源 + 渲染线程 | Moonlight PC | D3D/Wayland vsync 源驱动 pacer；队列上限 3 帧 | 【源码】[pacer.cpp](https://github.com/moonlight-stream/moonlight-qt/blob/master/app/streaming/video/ffmpeg-renderers/pacer/pacer.cpp) |
| 时钟同步 + 可调缓冲 | scrcpy | `sc_clock` 用 32 帧加权平均估计 `pts + offset`；默认**零缓冲**，`--video-buffer=50` 才加 | 【源码】[clock.c](https://github.com/Genymobile/scrcpy/blob/master/app/src/clock.c)、[video_regulator.c](https://github.com/Genymobile/scrcpy/blob/master/app/src/video_regulator.c) |
| 自适应 jitter buffer | WebRTC | `target = max(min_playout_delay, jitter + estimated_max_decode_time + render_delay)` | 【源码】[timing.cc](https://webrtc.googlesource.com/src/+/refs/heads/main/modules/video_coding/timing/timing.cc) |
| 有界队列 + 滞后丢弃 | Moonlight PC pacer | 硬上限 3 帧；丢弃目标 1 或 3，取决于最近 500 ms 队列历史 | 【源码】pacer.cpp |
| 背压：阻塞生产者 | scrcpy | frame sink `apply_backpressure()`，sink 未就绪时解码线程阻塞 | 【源码】video_regulator.c / decoder.c |
| 背压回传编码端 | WebRTC（frame dropper / quality scaler） | 丢帧、降分辨率、降码率；`PARAMETER_KEY_VIDEO_BITRATE` 可**不重建编码器**改码率 | 【源码概念】WebRTC；【官方】MediaCodec |
| 码控：低延迟用 CBR + 小 VBV | Sunshine/Moonlight 生态 | CBR 让发送节奏平稳 | 【官方/文档】[Sunshine configuration](https://docs.lizardbyte.dev/projects/sunshine/master/md_docs_2configuration.html) |
| 静态码率而非 ABR | Moonlight/Sunshine | 客户端请求码率，Sunshine 只设 `max_bitrate` 上限 | 【源码/文档】Sunshine config |
| 长 GOP + 按需 IDR | scrcpy（I 帧间隔 10 s）、WebRTC（长 GOP + PLI） | 减少 IDR 码率尖峰 | 【源码】SurfaceEncoder.java |
| 独立渲染线程，避开主线程 | Moonlight Android | render 线程优先级 `NORM+2`，Choreographer 单独 HandlerThread | 【源码】MediaCodecDecoderRenderer.java |
| 三缓冲 | 游戏渲染 | 提高吞吐但**增加 1 帧延迟** | 【推测】串流不适用 |

## 3. 减少卡顿掉帧

| 做法 | 谁在用 | 机制 | 证据 |
| --- | --- | --- | --- |
| jank 的官方定义 | Android 平台 | `actualPresentTime > expectedPresentTime` 即 jank；FrameTimeline 给 `jankType`（App/SF 归因） | 【官方】[Perfetto FrameTimeline](https://perfetto.dev/docs/data-sources/frametimeline) |
| JankStats / FrameMetricsAggregator | 通用 App | 按 Window 统计帧时长、jank | 【官方】[JankStats](https://developer.android.com/topic/performance/jankstats) |
| 串流专用指标 | Moonlight overlay | 网络延迟/抖动、网络丢帧、pacer 丢帧、解码耗时、渲染耗时、端到端延迟 | 【源码】MediaCodecDecoderRenderer.java + pacer.cpp |
| 关键帧请求：RTCP PLI / FIR | WebRTC | 接收端发现参考帧丢失 → PLI/FIR | 【官方 RFC】[RFC 4585 §6.3.1](https://www.rfc-editor.org/rfc/rfc4585)、[RFC 5104](https://www.rfc-editor.org/rfc/rfc5104) |
| 连续丢帧达阈值 → 请求 IDR | Moonlight | `CONSECUTIVE_DROP_LIMIT` 次连续丢帧后 `LiRequestIdrFrame()`；未达阈值优先用参考帧失效（RFI）做**非 IDR 恢复** | 【源码】[VideoDepacketizer.c](https://github.com/moonlight-stream/moonlight-common-c/blob/master/src/VideoDepacketizer.c) |
| 追赶策略：丢到 IDR / 丢最旧 | Moonlight、scrcpy | Moonlight 丢到可解码帧并请求 IDR；pacer 直接丢最旧；**没人用倍速播放** | 【源码】同上 |
| UDP + Reed-Solomon FEC | Moonlight/Sunshine | 每帧附带纠错包，`fec_percentage` 0–100 可调 | 【源码】moonlight-common-c（`nanors`） |
| 小重排窗口 | Moonlight | `RTP_QUEUE_DELAY = 10 ms`，只等这么久就判定丢包并继续 | 【源码】[VideoStream.c](https://github.com/moonlight-stream/moonlight-common-c/blob/master/src/VideoStream.c) |
| TCP 队头阻塞 | 反例：所有 UDP 方案都在规避 | 一个丢包阻塞后续所有已到达数据 | 【学术/官方】[CACM: Taking a Long Look at QUIC](https://cacm.acm.org/research/taking-a-long-look-at-quic/) |
| 编码器异常分类恢复 | Android + Moonlight + scrcpy | `CodecException.isRecoverable()` → stop/configure/start；`isTransient()` → 稍后重试；scrcpy 连续 3 次失败后降分辨率重试 | 【官方】[CodecException](https://developer.android.com/reference/android/media/MediaCodec.CodecException)；【源码】MediaCodecDecoderRenderer.java、SurfaceEncoder.java |

## 4. 对本项目的差距分析

### 4.1 已做到（与业界对照）

| 我们已有 | 业界对应 | 评价 |
| --- | --- | --- |
| 无观看者懒停采集 | scrcpy/Moonlight 停止 capture | 对齐，且更彻底 |
| 热状态降档（SEVERE→30fps，CRITICAL→50% 采集） | 游戏/投屏 thermal tier | 方向对，但用的是**滞后指标**（status），没用 headroom 预测 |
| 每客户端独立有界队列 | pacer 有界队列 | 对齐 |
| 编码前限帧 | scrcpy `max-fps-to-encoder` | 对齐 |
| 积压 2 s → 作废 AU → flush → IDR | Moonlight 连续丢帧→IDR | 机制对，**阈值比业界宽一个数量级** |
| PTS 映射 + 槽位呈现 | Moonlight Choreographer / scrcpy clock | 对齐，甚至更精细 |
| 每 5 s 全链路指标 + 零分配内核 | Moonlight overlay | 指标更全；零分配是优势 |
| 无 B 帧、完整 AU | 全行业 | 正确 |

### 4.2 未做且值得做（按预期收益 / 工程量排序）

| # | 做法 | 预期收益 | 工程量 |
| --- | --- | --- | --- |
| 1 | **客户端解码器低延迟参数**：`KEY_LOW_LATENCY=1`、`KEY_OPERATING_RATE=fps`、`KEY_PRIORITY=0`（**部分落地**，2026-10-23：只做 `KEY_LOW_LATENCY` 与 `KEY_PRIORITY`，**未含 `KEY_OPERATING_RATE`**——解码侧目标帧率需要新的描述符契约，会与内容帧率提示重复定义；收益未实测） | 延迟与抖动直接下降；不增加功耗 | S |
| 2 | **SurfaceView 取代 TextureView**（若浮层可控） | 官方口径最多省 30% 观看端功耗，帧时序更准 | M |
| 3 | **收紧追赶阈值 + 自适应队列**：队列 2 帧或 100–150 ms 就丢最旧；持续 500 ms 超阈值才 drop-to-IDR | 卡顿与延迟爬升显著减少 | M |
| 4 | **背压回传编码端**：客户端上报队列深度/迟到帧比例 → 服务端降 fps 或 `PARAMETER_KEY_VIDEO_BITRATE` | 防延迟爬升，比事后追赶更平滑 | M |
| 5 | **显示刷新率/帧率匹配**：`Surface.setFrameRate(fps)`，刷新率非整数倍时选 `Display.Mode`（**部分落地**，2026-10-24：仅 `Surface.setFrameRate` 提示——描述符下发 `frame_rate`，API 30 两参、API 31+ `ONLY_IF_SEAMLESS`；**未含** `Display.Mode` 切换，收益未实测） | 消除 3:2 pulldown 抖动；面板降刷新省电 | S–M |
| 6 | **热余量预测降档**：`getThermalHeadroom(10)` ≥0.7 / ≥0.85 两级，早于 SEVERE（**代码已落地**，2026-10-23：预测与状态取严格档、≤1 Hz、会话级 arm/disarm、`thermalHeadroom` / `thermalTier` 指标；0.70/0.85 与 0.60/0.78 **待设备校准**，收益未实测） | 避免“撞墙式”突然掉帧 | S |
| 7 | **编码器运行时参数**：`PARAMETER_KEY_REQUEST_SYNC_FRAME`、`PARAMETER_KEY_VIDEO_BITRATE`、`KEY_OPERATING_RATE` | IDR/码率调整不重建编码器 | S |
| 8 | **指标补全**：p99、迟到帧率、`OnFrameRenderedListener` 真呈现时刻、JankStats | 把“卡顿”从 p50 里拆出来，能归因 | S–M |
| 9 | **编码器异常恢复**：按 `isRecoverable/isTransient` 分类，静默线程后重建；连续失败降档 | 稳定性 | M |
| 10 | **传输层 UDP + FEC/NACK**（或 QUIC datagram） | Wi-Fi 丢包场景收益最大 | L |
| 11 | **Intra refresh**（`KEY_INTRA_REFRESH_PERIOD`） | 削平 IDR 尖峰、降低周期性 jank | M |
| 12 | **空闲暂停编码**（`PARAMETER_KEY_SUSPEND`） | 若实测静态画面仍在出帧，可省编码功耗 | S |
| 13 | **ADPF 试点**（编码/解码/渲染线程） | 热降频下稳 deadline | M |

### 4.3 未做且不推荐

| 做法 | 理由 |
| --- | --- |
| 应用层脏矩形 / 多 ROI 编码 | VirtualDisplay + 硬编已按 damage 出帧；H.264 没有“局部帧更新”，多路 ROI 破坏参考链 |
| 软件编码兜底 | 能耗高一个数量级，手机端不现实 |
| 大 jitter buffer（>200 ms） | 用延迟换流畅，交互式投屏不可接受；业界上限 2–3 帧 |
| 倍速播放追赶 | 改变时间轴，音画同步/交互全受影响；业界统一选丢帧 |
| 每帧动态改分辨率 | 触发编码器重配 + IDR + 管线重建；降档顺序应是 fps → 码率 → 分辨率 |
| 120 fps 串流 | 带宽/功耗翻倍，投屏场景不划算 |
| 无条件给所有线程上 ADPF | 可能抬高频率与功耗，OEM 差异大 |
| 在 TCP 上做无限 GOP 且无恢复手段 | 转 UDP 必须配 2–5 s IDR 或 intra refresh |

**容易忽略的点**：VirtualDisplay 直通路径上“编码前限帧”只省编码，不省合成/送显；源显示仍是高刷新时 SurfaceFlinger 仍按高刷新合成。要省面板功耗只能客户端 `setFrameRate`/切显示模式。

### 4.4 反馈闭环

```mermaid
flowchart LR
    ENC["编码：按内容出帧<br/>硬编 + 低延迟参数"] --> Q["每客户端有界队列"]
    Q --> NET["传输：当前 TCP"]
    NET --> DEC["解码：低延迟模式"]
    DEC --> REN["呈现：vsync / PTS 槽位"]
    REN --> FB["积压 / 迟到 / 队深反馈"]
    FB --> ENC
    TH["热余量预测"] --> ENC
```

> 图示为 §4.2 的优先级归纳：左侧是已对齐部分，右侧反馈与热余量是当前缺口。

## 5. 不限 minSdk 值得引入的 API

| API | 起始版本 | 成熟度 | 本项目适配点 | 注意 |
| --- | --- | --- | --- | --- |
| `MediaFormat.KEY_LOW_LATENCY` | 30 | 高（Moonlight 已用） | 客户端解码器 | 仅解码器；先查 `FEATURE_LowLatency`，否则厂商 key 回退 |
| `KEY_OPERATING_RATE` / `KEY_PRIORITY` | 23 | 高 | 编码器/解码器 | operating rate 设成目标 fps 即可 |
| `KEY_LATENCY`（编码器） | 26 | 中高（scrcpy 用 1） | 服务端编码器 | 设备可能忽略，读输出 format 校验 |
| `KEY_INTRA_REFRESH_PERIOD` | 24 | 中（依赖 `FEATURE_IntraRefresh`） | 服务端 | 与“从 IDR 恢复”策略冲突，需恢复点 SEI 配合 |
| `PARAMETER_KEY_SUSPEND` | 29 | 中 | 静态画面暂停编码 | 恢复首帧要补 IDR |
| `PARAMETER_KEY_VIDEO_BITRATE` | 19 | 高 | 背压时运行时降码率 | 不触发重建 |
| `PARAMETER_KEY_REQUEST_SYNC_FRAME` | 19 | 高 | 丢包恢复 | 与现有 IDR 请求对齐 |
| `Surface.setFrameRate`（2 参/3 参） | 30 / 31 | 高 | 客户端呈现 surface | hint 不限帧；选 seamless 避免闪 |
| `Display.Mode` / `preferredDisplayModeId` | 23 | 高 | 刷新率非整数倍时切模式 | Android 12+ 优先 `setFrameRate` |
| `PowerManager.getThermalHeadroom` | 30 | 高 | 提前降档 | ≤1 次/秒；NaN = 不支持 |
| `getCurrentThermalStatus` + listener | 29 | 高 | 现有降档逻辑 | 滞后，只作兜底 |
| `getThermalHeadroomThresholds` / `addThermalHeadroomListener` | 35 / 36 | 新 | 更精细分级 | 覆盖率低，做能力探测 |
| `PerformanceHintManager` | 31 | 中（OEM 差异大） | 编码/解码/渲染线程 | 先试点单线程；与 thermal 联动 |
| `MediaCodec.OnFrameRenderedListener` | 23 | 高 | 真呈现时刻指标 | 回调线程非主线程 |
| `CodecException.isRecoverable/isTransient` | 21 | 高 | 异常恢复策略 | 厂商实现不完全可靠，仍要兜底重建 |
| `JankStats` / `FrameMetricsAggregator` | AndroidX / 24 | 高 | 客户端 jank 归因 | 与自有指标对齐时间基准 |
| Game Mode API | 31 | 中 | 用户选“省电模式”时降档 | 只读 |

## 6. 最值得跟踪的 5 个触发条件

1. **静态画面仍有编码输入帧**（encode-input-rate 指标：静止桌面 >5 fps）→ 引入 `PARAMETER_KEY_SUSPEND` 或调整 `KEY_REPEAT_PREVIOUS_FRAME_AFTER`；顺带验证 VirtualDisplay 是否真按 damage 出帧。
2. **客户端端到端 p99 − p50 > 2 个帧间隔，或迟到帧 >1%** → 启用自适应队列（2 帧上限 + 500 ms 滞后判据），并把队列深度回传编码端降 fps/码率；不要再等 2 s。
3. **热余量 `getThermalHeadroom(10) ≥ 0.7`** → 一级降档；**≥0.85** → 二级（再降分辨率/采集比例）。**代码已落地（2026-10-23）**：预测档与状态档经 `stricter` 合并、只收紧不放宽；采样至多 1 次/秒且只在会话运行中 arm；开关关闭立即恢复；0.70 / 0.85 与 0.60 / 0.78 的回差**待设备校准**。
4. **显示刷新率不是流帧率的整数倍**（如 60 fps on 90 Hz），或 jank 呈 3:2 周期性 → `setFrameRate(fps)`；仍不行则切 `Display.Mode` 到整数倍。
5. **Wi-Fi 丢包 >1% 或 RTT p95 抖动 >20 ms，或追赶每分钟触发超过 N 次** → 启动传输层改造评估（UDP + FEC/NACK 或 QUIC datagram）。

可选第 6 条：**解码器输出队列长期 ≥2 或 `dequeueOutputBuffer` 超时增多** → 呈现端是瓶颈，优先查 SurfaceView/刷新率/主线程阻塞。

> `dec.lowLat` 三态口径（2026-10-23 落地）：`0` = 未请求（API < 30，不写也不打印，与“未采样”同值）；`1` = 低延迟生效（含只保留低延迟、优先级被厂商去掉）；`2` = 请求后完全回退到普通配置。值只在 `configure` 与 `start` **整体成功之后**写一次，失败路径不写；解码器 `close` 时把本解码器写过的值清零（同一指标作用域跨解码器复用，H.264 → JPEG 后不得继续打印上一解码器的值），`NOT_REQUESTED` 从未写过、`close` 也不触碰；因此设备判据只用 `1` / `2`，不能拿 `0` 当“回退”判据。

> `thermalHeadroom` / `thermalTier` 口径（2026-10-23 落地）：`thermalHeadroom` 是**热压力千分比**（`Math.round(getThermalHeadroom(10) × 1000)`），`1000` = 平台开始限流、`>1000` = 已限流且**不钳制**，值域 0–2000；`0` 同时表示“未采样或无效样本”，不打印，因此判据不依赖它的 0 值。`thermalTier`：`1` = NONE、`2` = REDUCED、`3` = MINIMAL，`0` = 未采样、不打印。两个键只在服务端行出现，且只有有效样本才更新 `thermalTier`；开关关闭会立即清空两者——日志里继续出现读数就说明采样没有停。设备判据只用可打印的 `thermalHeadroom` / `thermalTier` 与既有 `thermal` 状态，不把 gauge 的 0 当判据。

## 7. 证据强度与局限

- **强证据**：Android 官方 API 语义、Media3 的 SurfaceView/TextureView 功耗结论、Moonlight/scrcpy/WebRTC 的具体实现（可读源码）、RFC 的 PLI/FIR 定义、Perfetto 的 jank 定义。
- **中等证据**：学术解码能耗数据（只覆盖解码）、Sunshine 配置文档（工程惯例非测量）。
- **弱证据/推测**：scrcpy 5.0 的“10x CPU”是厂商/媒体口径；没有 scrcpy/Moonlight 严格功耗测评；“硬编比软编省一个数量级”是从解码数据外推；TCP 队头阻塞对投屏的卡顿幅度无公开对照实验。
- 所有“收益/工程量”评分是基于本项目现状的工程判断，不是测量结果；建议先做 §6 的触发条件测量，再决定投入顺序。

## 8. 引用来源

### 官方文档与规范

- [P1] Android `MediaFormat`: https://developer.android.com/reference/android/media/MediaFormat
- [P2] Android `MediaCodec`: https://developer.android.com/reference/android/media/MediaCodec
- [P3] Android `MediaCodec.CodecException`: https://developer.android.com/reference/android/media/MediaCodec.CodecException
- [P4] Android `PowerManager`: https://developer.android.com/reference/android/os/PowerManager
- [P5] Android `Surface`（`setFrameRate`）: https://developer.android.com/reference/android/view/Surface
- [P6] Media3 ExoPlayer battery consumption: https://developer.android.com/media/media3/exoplayer/battery-consumption
- [P7] Android ADPF 指南: https://developer.android.com/games/optimize/adpf
- [P8] Perfetto FrameTimeline: https://perfetto.dev/docs/data-sources/frametimeline
- [P9] JankStats: https://developer.android.com/topic/performance/jankstats
- [P10] RFC 6143 The Remote Framebuffer Protocol: https://www.rfc-editor.org/rfc/rfc6143
- [P11] RFC 4585 Extended RTP Profile for RTCP Feedback: https://www.rfc-editor.org/rfc/rfc4585
- [P12] RFC 5104 Codec Control Messages: https://www.rfc-editor.org/rfc/rfc5104
- [P13] Sunshine configuration: https://docs.lizardbyte.dev/projects/sunshine/master/md_docs_2configuration.html

### 上游源码

- [P14] scrcpy `SurfaceEncoder.java`: https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/video/SurfaceEncoder.java
- [P15] scrcpy `doc/video.md`: https://github.com/Genymobile/scrcpy/blob/master/doc/video.md
- [P16] scrcpy `clock.c`: https://github.com/Genymobile/scrcpy/blob/master/app/src/clock.c
- [P17] scrcpy `video_regulator.c`: https://github.com/Genymobile/scrcpy/blob/master/app/src/video_regulator.c
- [P18] Moonlight Android `MediaCodecHelper.java`: https://github.com/moonlight-stream/moonlight-android/blob/master/app/src/main/java/com/limelight/binding/video/MediaCodecHelper.java
- [P19] Moonlight Android `Game.java`: https://github.com/moonlight-stream/moonlight-android/blob/master/app/src/main/java/com/limelight/Game.java
- [P20] Moonlight Android `MediaCodecDecoderRenderer.java`: https://github.com/moonlight-stream/moonlight-android/blob/master/app/src/main/java/com/limelight/binding/video/MediaCodecDecoderRenderer.java
- [P21] Moonlight Qt `pacer.cpp`: https://github.com/moonlight-stream/moonlight-qt/blob/master/app/streaming/video/ffmpeg-renderers/pacer/pacer.cpp
- [P22] moonlight-common-c `VideoDepacketizer.c`: https://github.com/moonlight-stream/moonlight-common-c/blob/master/src/VideoDepacketizer.c
- [P23] moonlight-common-c `VideoStream.c`: https://github.com/moonlight-stream/moonlight-common-c/blob/master/src/VideoStream.c
- [P24] WebRTC `video_coding/timing/timing.cc`: https://webrtc.googlesource.com/src/+/refs/heads/main/modules/video_coding/timing/timing.cc

### 学术、测评与二手

- [P25] arXiv 2402.09001（移动端硬解/软解能耗）: https://arxiv.org/abs/2402.09001
- [P26] CACM: Taking a Long Look at QUIC: https://cacm.acm.org/research/taking-a-long-look-at-quic/
- [P27] Android Authority：scrcpy 5.0 硬件解码: https://www.androidauthority.com/scrcpy-5-0-update-hardware-decoding-3719521/

> 二手来源中的百分比与倍数只代表原文口径；本文未复现任何测量。
