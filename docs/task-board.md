# 📋 任务看板

> 用途：让作者和 AI 在开始工作前快速确认“现在做什么、接下来做什么、哪些还没验证”，不替代设计文档和验收标准。
> 证据口径：“已完成”必须有可核对的交付物或提交；“待办”和“等待验证”只代表计划或待验证条件，不代表已经取得结果。

## 📖 图例

| 类别 | 图标 | 含义 |
| --- | --- | --- |
| 状态 | 📋 待办 | 已进入路线图，尚未开始 |
| 状态 | 🚧 进行中 | 已有明确执行者与范围，正在实施 |
| 状态 | ✅ 已完成 | 交付物已落地，且写明了可核对的验证证据 |
| 状态 | 🔶 部分完成 | 范围的一部分已落地并通过验证，未完成部分逐项列明 |
| 状态 | ⏳ 等待验证 | 方案或需求尚未被实测/产品验证，不能当作结论 |
| 状态 | ⏸️ 暂缓 | 当前不排期，保留触发条件 |
| 优先级 | 🔴 P0 | 先做：建立基线，或消除正确性、资源与稳定性风险 |
| 优先级 | 🟠 P1 | 核心改造：媒体路径、安全前提、窗口连续性 |
| 优先级 | 🟡 P2 | 增强能力：音频、反控、客户端画质与工程配套 |
| 优先级 | ⚪ P3 | 条件触发：没有证据或产品确认前不排期 |
| 优先级 | — | 文档类条目，不设优先级 |

## 🎯 当前焦点

- 2026-10-21 **配对/授权落地（[TB-SEC-001](#tb-sec-001)，代码与 JVM 验证完成，设备验证待棒）**：两层分离——TOFU 管「身份是不是真的它」（已有），授权管「准不准看」。**默认拒绝**：未授权身份的媒体流请求不直接拒绝，而是停在待批准（不进 `StreamHub`、不发一帧、音频不扇出），服务端弹审批（设备名 + id 短码 + 指纹短码，允许一次 / 总是允许 / 拒绝，60 s 无回答即 `authorization_timeout`），拒绝与超时都带明确原因。**持久授权**按 id **且** 指纹落盘到独立文件 `lss-authorization`（与 `lss-trust` 分开），只有「总是允许」写盘、「允许一次」仅当次。**撤销即时生效**：直播中的会话每次 drain 复核授权，撤销后 ≤5 ms 断流（原因 `authorization_revoked`）。**开关默认开**，关闭回到旧行为并在设置里明示风险；音频沿用 D11「能看就能听」，不单独设许可。协议只做纯增量（`Hello.device_name=6`、`ServerMessage.authorization=6`），旧端忽略。提交 `d690592`（计划）、`7416f9f`（存储/策略/闸门）、`8c3cf1d`（服务端审批流）、`3cc5ef8`（客户端文案）、`a7840d2`（设置与已授权设备）及本条目；全量 **1367 tests / 132 classes / 0 failures / 0 errors / 0 skipped**（基线 1322/128，+45 条 / +4 类，只增不减），`assembleDebug`、`:app:lintDebug`（0 errors / 146 warnings）与三项 checks 通过。**本棒不碰设备**，点位见 [TB-SEC-001](#tb-sec-001)。
- 2026-10-21 **音频水位与口径修复（R1–R3，[TB-AUDIO-003](#tb-audio-003) 的两个 ❌ 与重连瞬态）**（来源 handoff `device-test-2026-10-21-dual/audio-fix-verify/report.md` §1.4/§4 的 N1/N2/N4）：**R1 水位** `MAX_DEPTH_MS 250→400`、`RECOVER_DEPTH_MS 150→250` —— 稳态深度是轨道 128 ms + 预填 ~106 ms ≈ **234–248 ms**，与旧的 250 ms 只差几毫秒，任何视频突发都会顶破并按 150 ms 一次砍 6 帧（128 ms 音频，每 13–18 s 一次，`audio.dropped` 0.294 次/s）；同时把丢弃改成**渐进**（新增 `MAX_DROP_FRAMES_PER_PLAN=2`，单次 ≤43 ms；丢弃速率仍高于实时，积压照常排空）。**R2 口径** `AudioPlayhead.audiblePtsUs()` 增加 `pendingFrames` 入参，可闻位置同时扣掉「轨道内未播帧」与「等写线程的帧」，`ClientAudioSession` 用 `pendingSamplesLocked()` 供数 —— 过去深预填被算成"音频落后/超前"的固定偏置（实测 70–91 ms）。**R3 重连瞬态** 播放开始时重锚漂移基准（`referenceOffsetUs` + 检查时刻），新音频会话在已被视频锚定的时间线上重播时，预填期的测量不再被当作基准（上一轮 `drift guard dropped one frame, offset = -185 ms`）。提交 `0b0f8cb`；全量 **1322 tests / 128 classes / 0 failures / 0 errors / 0 skipped**（基线 1315/128，只增不减），`assembleDebug`、`:app:lintDebug`（0 errors / 146 warnings）与三项 checks 通过。**本棒不碰设备**，判据见 [TB-AUDIO-003](#tb-audio-003)。
- 2026-10-21 **双真机首测的 onboarding/身份三问（N3/N2/N1）代码修复**（来源 handoff `device-test-2026-10-21-dual/report.md` §1.1/§5 与派发方诊断 F1–F4）：**N3（P0）** 扫描取消用 `shutdownNow()` 打断 keystore 读 → 被 `catch (GeneralSecurityException) → null` 误判为密钥不可读 → 静默删键重建（1.4 s 内 14 次），叠加公告/`ServerInfo`/握手证书三份快照，双向 `identity_mismatch`；修法为纯 JVM `KeyEntryFailurePolicy`（瞬时故障保留键）+ `IdentityKeyEntryLoader`（二次确认、每进程一次、被中断不碰 keystore）+ `KeystoreExecutor`（keystore 单线程）+ 身份统一由 `TlsMaterialHolder` 派生并播发变更事件，`ACTION_IDENTITY_RESET` 一并重建 `ServerInfo`，`AddressScanner` 改温和取消，观看端 HELLO 改用建通道时的材料（`8bdd113`、`76ba8a9`）。**N2（P1）** 公告不带指纹时点行直接报"证书与设备 ID 不一致"（把信息不足说成疑似攻击）；改为先直连握手取指纹再进既有首连确认，失败按原因分流、确认记在 `MULTICAST` 不 pin 条目（`3b5ad60`、`f43a89f`）。**N1（P1）** 公告 `sec` 把「画面私密」当成传输能力，导致默认配置下任何跨机连接都被拒；`secure`/`auth` 改回恒表示传输能力，私密开关只管 `Descriptor.secure`/FLAG_SECURE，文案全面校订（`27f991a`）。全量 **1315 tests / 128 classes / 0 failures / 0 errors / 0 skipped**（基线 1283/124，只增不减），`assembleDebug`、`:app:lintDebug`（0 errors / 146 warnings）与 checks 三件套通过。**本棒不碰设备**，判据与点位见 [TB-SEC-002](#tb-sec-002)、[TB-DISC-013](#tb-disc-013)、[TB-SEC-003](#tb-sec-003)。
- 2026-10-21 **观看端音频周期性「咔」修复（[TB-AUDIO-003](#tb-audio-003)，P0×3 + P1×2 + P2×1）**（来源 handoff `device-test-2026-10-21-dual/audio-noise/report.md` 的诊断，238 s 真机数据 + 代码级定位）：确认脉冲不是网络抖动、不是漂移守卫、不是 jitter buffer 丢帧，而是**播放缓冲永远填不满**导致的反复欠载，每次欠载被应用补成一整帧 21.3 ms 数字静音（`audio.underruns` **2.60 次/s ⇒ 每 0.38 s 一次**，与用户听感吻合；平台侧 AudioFlinger `tallyUnderrunFrames` **6.03 次/s**、`FrmRdy` 常年 2.7–42 ms、`Server FrmCnt=3072`）。根因四条：① `ClientAudioSession` 传给 `plan()` 的是**累计收帧数**而非队列深度（第 3 帧即开播、第 8 帧起每帧都请求丢弃，三档水位成死代码）；② 写线程 20 ms 轮询且入队不 `notify`；③ 音频与 1080p60 视频同一条解码线程（`pres.drop` r=0.94）；④ 轨道 64 ms + 低时延模式。修法：深度改为「已写媒体时间 − 已呈现时间 + 待写帧」（120/250/150 ms，预填到轨道真有 120 ms 才 `play()`）、入队 `notifyAll` 唤醒（仅欠载计时短 wait）、音频改走独立 `lss-client-audio` 单线程 + 32 帧小队列、轨道 64→128 ms 去低时延、欠载按实测缺口补静音且 >100 ms 改「暂停 + 重新锚定」、计数拆 `audio.starved/driftInsert/driftDrop` 且 0 值也打印、新增 `core/audio/AudioLog` 给音频路径补限流日志与平台欠载增量。提交 `76a3be4`、`5343d07`、`c364d9d`、`6ced8e0`、`a520545`、`4cfb75f`；全量 **1283 tests / 124 classes / 0 failures / 0 errors / 0 skipped**（基线 1275/124，只增不减），`assembleDebug`、`:app:lintDebug` 与三项 checks 通过。**本棒不碰设备**，判据与点位见 [TB-AUDIO-003](#tb-audio-003)。
- 2026-10-21 **第 8 问剩余部分：无观看者懒停 + 热降档（[TB-POWER-001](#tb-power-001)）**：核查确认视频侧此前**完全不看观看者数量**（服务一端启动即持续合成 + 编码，0 观看者与有人观看功耗相同；音频侧早已按 `AudioCapturePolicy` 懒停）。新增纯决策 `core/stream/ViewerCapturePolicy`（0 观看者且运行时 `READY` → 摘下 `VirtualDisplay` 输出面，编码器保持分配；首个观看者 → 重新绑面 + 请求关键帧；未 `READY` 不暂停，否则 H.264 的 SPS/PPS 永远发不出、客户端只会收到 `NOT_READY`），服务端用独立 1 s 轮询执行；新增纯决策 `core/stream/ThermalThrottlePolicy`（`LIGHT`/`MODERATE` 不动 → `SEVERE` 帧率 30 → `CRITICAL`+ 帧率 30 + 采集 50%，未知值按不动）与 `service/ThermalMonitor`（API 29 显式门控，注册后补发当前状态），降档只改**生效值**、不写偏好，回落/关开关即恢复；通知副标题显示「过热降档」，视频设置 sheet 新增「过热时自动降档」开关（默认开，中英双语）。提交 `377e581`（懒停）、`00ee006`（档位策略）、`68bfd5e`（接线 + 开关 + 通知）、`45ed888`（启动前已过热时同步应用）及本条目的文档登记；全量 **1275 tests / 124 classes / 0 failures / 0 errors / 0 skipped**（基线 1262/122，只增不减），`assembleDebug` 与 `:app:lintDebug`（0 errors / 146 warnings）通过，三项 checks 通过。**设备验证未执行**，点位见 [TB-POWER-001](#tb-power-001)。
- 2026-10-21 **Build CI 红灯修复（`5d186c6`，lint `NewApi`）**（[TB-AUDIO-001](#tb-audio-001)）：`6210a0b` 把 `startAudioPipeline` 的 API 29 显式门控换成 `AudioAvailability.forCaptureStart(SDK_INT, ...)` 后，lint 无法穿透该分类器判断 SDK 版本，`:app:lintDebug` 重新报 `ScreenShareService.java:1682` 的 `new PlaybackCaptureSource(...)`「Call requires API level 29 (current min is 25): PlaybackCaptureSource [NewApi]」；`f968b5f` 的 Build run [39586027806](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39586027806) 因此在 `Build with Gradle` 失败（Checks 一直 success）。修法：构造采集源之前恢复 `Build.VERSION.SDK_INT < Q` 提前返回，降级原因仍由 `AudioAvailability.forApiLevel` 产出，权限与 app-op 判定不变。全量 **1262 tests / 122 classes / 0 failures / 0 errors / 0 skipped**；`assembleDebug`、`lintDebug`（0 errors / 146 warnings）与三项 checks 通过；`5d186c6` 的 Build run [39586878963](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39586878963) 与 Checks run [39586878970](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39586878970) 双绿。
- 2026-10-21 **音频 N6-1 修复（`5799139`，`AV_OFFSET` 量纲 + 漂移守卫）**（[TB-AUDIO-001](#tb-audio-001)/[TB-AUDIO-003](#tb-audio-003)，来源 `device-test-2026-10-21-audio/fix-n1n2/`）：`AudioSink.getPlayheadNs()`（音轨内纳秒）与 `SessionClock.map()`（开机单调时钟）相减的量纲错误，改为采样侧回报**帧计数**（`getPresentedFrames(nowNs)`，用 `AudioTrack` 时间戳外推）、会话侧换算**可闻位置的流时间**（末端 Pts − 排队帧时长，输出缓冲 64 ms 因此被抵消），两步算术抽到纯 JVM 的 `core/audio/AudioPlayhead`；守卫阈值/冷却维持 40 ms / 2 s，但改为只对**偏移的变化量**动作 —— 稳定偏差（实测 160–447 ms，来自解码首帧锚点与观看端画面滞后）不能让一帧媒体消掉，追它只会每 2 s 补一帧静音直到流结束，固定偏差交给 `AV_OFFSET` 与 A8 校准。全量 **1262 tests / 122 classes / 0 failures / 0 errors**；`assembleDebug` 与三项 checks 通过；真机服务端 + 模拟器观看 60 s：`av.offset.p50/p95` 由 **76 289 206 ms（≈21 h）** 降到 **160–168 ms / 170–200 ms**（p95），`audio.dropped = 0`，N1/N2 路径回归通过。**N3 听感**与 **N6-3 平台权限边角**仍遗留。
- 2026-10-21 **音频设备验证 N1/N2/N4 修复**（[TB-AUDIO-001](#tb-audio-001)/[TB-AUDIO-003](#tb-audio-003)，来源 `device-test-2026-10-21-audio/report.md`）：N1 把已埋点但从未输出的客户端音频指标接到 `lss.metrics role=client` 与 HUD（`av.offset.p50/p95`、`audio.frames/dropped/underruns`，HUD 第 4 行）；N2 按 D9 实现无观看者懒停采（新增纯决策 `core/audio/AudioCapturePolicy`，`ScreenShareService` 用 1 s 观看者轮询驱动，0 观看者 → `ACTIVE→OFF` + `AudioAvailability.NO_VIEWERS`，首个观看者 ≈0.5 s 内恢复）；N4 采集启动前补 `AppOpsManager.unsafeCheckOpNoThrow` 只读检查（不调 `noteOp`），`adb revoke`+`grant` 留下的 uid-mode `ignore` 归为 `PERMISSION_DENIED`。全量 **1248 tests / 121 classes / 0 failures / 0 errors**（`6210a0b`）；`assembleDebug` 与三项 checks 通过；真机 + 模拟器快验通过（停采/恢复、日志与 HUD 音频行、app-op 降级）。**N3 仍待真机听感复核**；**N6** 已由上一行修复（N6-1/N6-2 关闭，N6-3 遗留）。
- 2026-10-21 **服务端声音传输 A1–A7 落地**（[TB-AUDIO-001](#tb-audio-001)，按[音频计划](superpowers/plans/2026-10-21-audio.md)与第 10.1 章裁决）：新增 `core/audio` 并列契约（描述符/帧/能力/采集/编码/扇出/jitter buffer/会话时钟/状态机），协议只做纯增量（`Capabilities.audio_codecs=2`、`ServerMessage.audio_descriptor=4`/`audio_access_unit=5`），**不改 `StreamHub`、不新增 RPC/端口、不新增依赖**；服务端内录 → AAC-LC → 每客户端独立有界队列 → 同一 `Stream` RPC，客户端解码 → jitter buffer → `AudioTrack`，音视频共用 `SessionClock` 单锚点（目标 |Δ|≤40 ms，帧级丢/补，2 s 冷却）。音频默认关、API 25–28 纯无声（D1 麦克风降级不做，`TB-AUDIO-002` 未激活）。全量 **1236 tests / 120 classes / 0 failures / 0 errors**；`assembleDebug` 与三项 checks 通过；**设备验证未执行**，短期功能点位见 [TB-AUDIO-003](#tb-audio-003)，长时/压力项并入 [TB-ENG-013](#tb-eng-013)。[DB-AUDIO-001](decision-board.md#db-audio-001) 转为“已裁决·代码已实施”，[DV-005](decision-board.md) 补范围结论。

- 2026-10-21 **测试分层裁决**：功能做完只跑对应的短小功能测试；稳定性/长时/耐力类一律先登记、不逐棒穿插，统一到全部功能完成后执行。被延后的项（TB-WIN-001 ⑨ 连切 20 次与全项点位、Q-2 1080p 观看端终验、DV-001 全量基线、N1 过载掉线、N2 线程尖峰、TLS D9 30 min 功耗/D10 备份恢复、API 25 出画）已收口到 [TB-ENG-013](#tb-eng-013)，各项写明来源与阻塞条件。
- 2026-10-21 T6 复测（r2）报告的三个缺陷已修复：**N-r2-1**（客户端对信任失败完全静默，与既有 R5-01 同源）新增 `client/StreamFailure` 把终止原因归一化（gRPC `Status` code/description + 原因链），`ScreenShareClient` 对每个终止记结构化日志，`ClientObserver.onDisconnected(StreamFailure)` 让观看端把身份类拒绝（`identity_mismatch`/`identity_changed`/`pin_unknown`/`invalid_chain`）显示为"设备身份校验失败"专属文案而不是"是否重试"；**N-r2-3** 冷启动首个 TLS 探针单独放宽到 5 s（`MANUAL_PROBE_FIRST_TIMEOUT_MS`）、探测失败补 `Log.w` 与"无法连接该地址"toast、并修掉探测任务抛异常时回调永不触发的静默路径；**N-r2-2** 把计划 9.7 的 D5 判据改写为实测语义（同地址新设备 + 提示 + 显式信任恢复），DV-022 补注。顺带登记 API 25 模拟器软解出画失败（N-r2-5，环境项）与 `insecure_transport` 分支不可达的评估结论。详见 [TB-DISC-010](#tb-disc-010)。
- 2026-10-21 第 12 问“窗口化 ↔ 界面化”已落地代码层面（[TB-WIN-001](#tb-win-001)，方案 B）：悬浮窗与全屏 `ViewerActivity` 是同一会话的两个显示宿主，切换只把解码器输出面换到新宿主并按最新 descriptor 重建解码器（新增 `ClientMediaSession.rebindSurface()` 与 `render/SwitchableRenderTarget`），网络会话、认证与 Service 均不重建；末帧快照 + 200 ms 缩放过渡、悬浮窗“展开全屏”与 Activity“收起小窗/停止观看”入口、`ViewerPanelView`/`ViewerMenuFactory` 两个宿主共用渲染与菜单。全量 **1098 tests / 103 classes / 0 failures / 0 errors / 0 skipped**，`assembleDebug` 与 `lintDebug` 通过；**设备功能冒烟已通过（r2，2026-10-21）**：出画 → 双击菜单「展开全屏」→ 全屏出画 → 返回收起回悬浮窗 → 停止观看，`Unable to instantiate`/`FATAL` 计数 0；裁决前那一轮复验的 10 条点位读数与耗时表见条目，其中连切 20 次等长时/稳定性项按当日裁决并入 [TB-ENG-013](#tb-eng-013) 统一回归。首轮设备验证暴露的 P0（`ViewerActivity` 构造期 `new GestureDetector(this, …)` → 展开即 FATAL）已修复，并把该反模式升级为[命名规范第 8 章](naming-conventions.md#8-android-组件构造禁令强制)。[TB-CORE-002](#tb-core-002) 因此转为部分完成（“关窗不等于停会话”仍待 [DV-008](decision-board.md)），[DB-WIN-003](decision-board.md#db-win-003) 改写为方案 B。
- 2026-10-21 T6 设备报告的 TLS 阻塞项已修复并取得设备证据：**N-t6-1**（AndroidKeyStore 私钥只授权 SHA-256，Conscrypt 握手要 raw digest → `INCOMPATIBLE_DIGEST`，双向双版本全败）改为授权 `{SHA-256, NONE}` + 旧密钥一次性探针重建；**N-t6-2**（服务启动在主线程做 keystore 工作，失败路径 5.4 s 触发 ANR）把 `ScreenShareServer.start()` 移到 `lss-server-control` 线程并用纯 JVM 状态机裁定启停所有权；**N-t6-3**（重置身份后通知栏指纹不刷新）在重置广播里刷新通知。顺带补上手动探测通道缺失的客户端证书（服务端 `clientAuth=REQUIRE` 下探测必然失败）。全量 **1055 tests / 97 classes / 0 failures / 0 errors / 0 skipped**；真机（API 29）服务端 → 模拟器（API 36）观看端 TLS 1.3 双向握手出画通过，详见 [TB-DISC-010](#tb-disc-010) 与 [TB-DISC-012](#tb-disc-012)。
- 基线参考：`65a34dc`（TB-ENG-004 完成标记）。
- 编码框架隔离核心批次（Task 1–9，代码范围 `9f650dc..2a95ab2`）见 [TB-DOC-008](#tb-doc-008) 与 [TB-FW-001](#tb-fw-001)；最近完成的条目见 [TB-ENG-004](#tb-eng-004) 与 [TB-ENG-003](#tb-eng-003)。
- 媒体路径生产接线（批次 A–H）已完成代码层面：双向流协议、StreamRuntime/StreamHub 服务端接线、H.264 采集直通、客户端 ClientMediaSession/Surface 渲染、JPEG 回退、能力选档与切换编排均已落地；仅经 JVM/fake 与编译验证，详见 [TB-FW-003](#tb-fw-003)。
- 问题 3 代码层面已收官（批次 A–H），边角项 [TB-ENG-005](#tb-eng-005) 已完成（视频设置界面），[TB-ENG-007](#tb-eng-007) 已随可观测性 O3 完成（2026-10-21），[TB-ENG-006](#tb-eng-006) 仍为遗留；下一步等用户准备好设备后进入设备实测（设备验证未解禁）；问题 3 仍未关闭。
- 未做设备/真机/模拟器验证：全程未运行 adb、`connectedAndroidTest`、安装或启动 App；编码切换、Surface 恢复与多客户端行为均无实测数据。
- 编码框架隔离核心（契约、StreamHub、有界队列、六状态机、JPEG 参考适配）已完成并通过 JVM 验证，见 [TB-FW-001](#tb-fw-001)、[TB-FW-004](#tb-fw-004)、[TB-FW-005](#tb-fw-005)、[TB-FW-002](#tb-fw-002)。
- 问题 3 尚未由用户明确关闭，不能标记完成；代码完成 ≠ 问题关闭，API 25 只是兼容下限，能力按运行时探测分档。
- 编码框架待收尾：TB-FW-003、TB-FW-006、TB-MEDIA-001、TB-MEDIA-003 等待设备验证；TB-FW-002 仍部分完成；TB-FW-007、TB-FW-008 未完成。
- 设备发现功能（[TB-DISC-001](#tb-disc-001)..[TB-DISC-009](#tb-disc-009)）B1–B5 已全部完成：B1（[TB-DISC-002](#tb-disc-002)、[TB-DISC-003](#tb-disc-003)）、B2（[TB-DISC-004](#tb-disc-004)）、B3（[TB-DISC-005](#tb-disc-005)、[TB-DISC-006](#tb-disc-006)）、B4（[TB-DISC-007](#tb-disc-007)、[TB-DISC-008](#tb-disc-008)）与 B5（[TB-DISC-009](#tb-disc-009)）；全量 **670 tests / 0 failures / 0 errors / 0 skipped**（58 个测试类），各批 Build 与 Checks CI 均 success（最终 HEAD 的 Build run [39537903021](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39537903021)）。[TB-DISC-010](#tb-disc-010)（自签 TLS + TOFU pinning）已于 2026-10-21 完成 T1–T5 与 JVM 证据，设备验证项并入 [TB-DISC-012](#tb-disc-012)；设备验证仍未解禁。
- 代码结构梳理（[TB-ENG-011](#tb-eng-011)）已完成 R0–R5：命名规范 [naming-conventions.md](naming-conventions.md) 自 2026-10-20 生效，六个批次均为纯移动/改名（R5 为主题名去平台词与组播工厂改名），行为零变化核验通过，全量仍为 **670 tests / 0 failures**（58 个测试类）；新代码与后续批次按该规范执行，遗留观察（`ClientObserver` 同名、`service` 上帝包、`core.codec` 包环）见条目详情。
- B3 已落地 UDP 组播公告（启动 3 连发、5/15 s 心跳、停服告别、`MulticastLock` 引用计数）与 Register 双向握手（成功才确认、`announces` 单跳防回环、限速与限包长）；仅经 JVM/fake 与编译验证，同网段互见、真实组播与 Reverse Register 未做设备实测。
- B4 已落地分级扫描（按接口前缀生成候选、1 s grace 零确认才升级、50 并发/500 ms 探测/8 s 预算、可取消）、收藏持久化与上次成功地址优先探测、手动 IP 常驻入口与 8 s 空列表提示、连接前指纹校验；仅经 JVM/fake 与编译验证，真实网段扫描、组播被过滤场景与手动探测未做设备实测。
- B5 已落地发现设置（组播开关/组地址/端口、三档刷新、四档可见性、仅 mDNS 兼容模式、广播设备名）、`DeviceStore` 统一列表与共享屏/观看者分区、状态徽标与 STALE/UNREACHABLE 灰显、`LssDeviceInfo` 的 `Intent` 契约迁移；仅经 JVM/fake、编译与 lint 验证，真实设置生效、可见性对公告/Register 的影响与列表状态未做设备实测。
- 查看器画质治理与视频设置（Q1–Q5）已落地代码层面：[TB-CLIENT-003](#tb-client-003)（宽高比与缩放档位）、[TB-CLIENT-004](#tb-client-004)（呈现调度与关键帧恢复）、[TB-ENG-012](#tb-eng-012)（帧率/IDR 生效编排）与改写范围后的 [TB-ENG-005](#tb-eng-005)（视频设置界面，编码格式只读）；全量 **758 tests / 0 failures / 0 errors / 0 skipped**（65 个测试类），各批 Build 与 Checks CI success。设备实测未解禁，变形、锯齿、重建停顿时长与关键帧恢复时延均无实测数据；GL 降采样管线的触发条件见决策看板 DV-020。
- 2026-10-21 查看器画质与设置真机验证（真机服务端 + 模拟器查看器；几何、三档缩放、181 s 呈现调度通过）发现 2 个 P1 与 4 个次级问题，已在本轮修复：流中切换编码器因「持状态锁 stop、drain 线程等同一把锁」互等至 1 s join 超时，且失败后采集永久损坏（`dequeueBuffer -19` 刷屏、0 B/s），改为锁外预热/停止 + 切换锁串行化 + 失败提交自愈 + 重建显示输出面；会话中分辨率变化后查看器只剩左下窄带，改为 `RenderTarget.setContentSize` → `SurfaceTexture.setDefaultBufferSize` 并在旋转时 resize VirtualDisplay；另有缩放菜单档位去重与比例标注、设置摘要随参数生效刷新、拖拽越过 touch slop 即生效。全量 **774 tests / 0 failures / 0 errors / 0 skipped**（67 个测试类）。缺陷根因与修复分别补记在 [TB-ENG-012](#tb-eng-012)、[TB-CLIENT-003](#tb-client-003)、[TB-ENG-005](#tb-eng-005)；修复后的设备复测未执行。
- 2026-10-21 设备复测（r2）新发现 3 个问题，已在代码层面修复：**Q-07（P1）** 画面大小落到奇数编码尺寸时硬编必拒绝、失败后画面被挤压出黑带 — 改为采集尺寸按编码器对齐步长向下取偶（与能力探测同源）、尺寸切换先编码器后显示（失败零副作用）、失败回写实际生效比例并提示（提交 `08ae680`，详见 [TB-ENG-012](#tb-eng-012)）；**Q-08（P2）** 缩放菜单选中态只存在于无障碍树 — 当前档位文案前置勾选标记；**Q-09（P3）** 观看比例无 UI 入口 — 双击菜单新增「显示大小」子菜单（60%/80%/100% 与当前值）（后两项提交 `3fb9d9e`，详见 [TB-CLIENT-003](#tb-client-003)）。全量 **790 tests / 0 failures / 0 errors / 0 skipped**（70 个测试类）。三项均**未做设备实测**，复测点位见条目详情。
- 2026-10-21 设备复测（r3）留下的两个 P3 尾巴已修复：**Q-10** 失败回写只改偏好、开着的视频 sheet 滑杆不刷新 — 投影偏好变化时同步全部控件（拖动中的滑杆跳过），映射抽成纯 JVM `ProjectionControlValues`；**Q-11** 画面大小下限 5% 低于本机硬编最小尺寸 — 新增 `EncoderSizeLimitProbe` + 纯 JVM `ProjectionScalePolicy`，按下限与窗口较短边动态抬高滑杆 `valueFrom`（能力未知仍为 5%）。全量 **812 tests / 0 failures / 0 errors / 0 skipped**（72 个测试类）。模拟器实测两项均生效（证据见 [TB-ENG-005](#tb-eng-005)）；真机只读了下限（176×144 → 17%）并还原偏好，画面回归留给下一轮。
- 2026-10-21 传输与管线可观测性（[TB-OBS-001](#tb-obs-001) 的 O1–O6）已落地代码层面：指标内核（`core/metrics`）、服务端编码/扇出/发送探针、服务端统计聚合与「观看者 · 队列 · 丢弃 · 重同步」摘要行（并入 [TB-ENG-007](#tb-eng-007)）、客户端到达/解码/呈现探针、Ping/Pong 应用层 RTT 探针、悬浮窗三行调试叠加层与每 10 s 设备状态行；两个开关（`KEY_METRICS_ENABLED`、`KEY_VIEWER_HUD_ENABLED`）默认关闭。全量 **899 tests / 0 failures / 0 errors / 0 skipped**（79 个测试类），各批 Build 与 Checks CI 均 success（最终 HEAD 的 Build run [39537903021](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39537903021)）。O7（基线执行与 [DV-001](decision-board.md) 裁决）仍是后续设备棒，设备实测未执行。
- 2026-10-21 该轮设备验证首轮被 **D-1（P0，阻断）** 挡住并已修复：两个服务把设备采样器写在字段初始化器里，`Class.newInstance` 期间 `attachBaseContext` 尚未调用 → 采样器构造读 `getApplicationContext()` 得到 null base → `Unable to instantiate service` → App 每次启动 FATAL（真机 API 29 与模拟器 API 36 同栈，与 `metrics_enabled` 无关）。改为首次采样惰性创建（`service/LazyContextValue` + 3 条契约用例），两处服务同步改；全量 **902 tests / 0 failures / 0 errors / 0 skipped**（80 个测试类）。设备复验：两机冷启动进主界面、真机开共享 1080×2340、模拟器手动添加后出画一轮，两机 crash buffer 为空。D-2（滑杆下限 17% vs 推算 14%）已核清为口径差异（声明下限 176×144 与硬编实拒阈 144×144），代码无算错，结论与证据见 [TB-OBS-001](#tb-obs-001)。同类反模式禁令已写入[本计划](superpowers/plans/2026-10-21-transport-observability.md) 8.1。
- 2026-10-21 设备验证 r2 报告的 3 个仪表缺陷已修复（[TB-OBS-001](#tb-obs-001)）：**I-1** 速率字段改为「差分窗口 ÷ 实测间隔」（新增 `MetricsRate`/`RateSampler`，删掉 `MetricsSnapshot.perSecond`/`bitrateBps`，服务端 5 s 行、观看端 5 s 行与 1 s HUD 各持独立基线）；**I-2** 观看端在 `ClientMediaReceiver` 按收到的 AU 累加 `ACCESS_UNITS`/`ACCESS_UNIT_BYTES`（复用到达侧已反序列化的数据，无新增打点），HUD 首行与新增的 `rx.auRate`/`rx.bitrate` 有数；**I-3** 新增 `StreamHub.snapshotSessionMetrics()` + `MetricsFormatter.toSessionLogLine()`，服务端每 5 s 为每个会话说一行 `sid=… tx.send/tx.qResidency/tx.qDepth`（会话 scope 只读、不注册，热路径锁纪律不变）。**Q-1** 按调研结论实现：`max-fps-to-encoder` 私有/公开键 + 失败回退（`new EncoderFormat(...)` 单构造 + `configureWithFallback`），帧率设置从此进入编码器限帧路径；设备是否真的限帧仍待验证棒实测。**Q-2/Q-3** 登记为 [TB-CLIENT-005](#tb-client-005)（解码队列不收敛）与 [TB-CLIENT-006](#tb-client-006)（小窗 HUD 折行），本棒不修。全量 **928 tests / 0 failures / 0 errors / 0 skipped**（82 个测试类），`assembleDebug` 成功；设备复测未执行。
- 阶段 0 的仪表部分已就位，但基线报告与 DV-001/DV-018 的实测数值仍缺；阶段 1 之后的代码优化尚未开始，当前没有进行中的代码任务。
- 2026-10-21 **Q-2 落地（[TB-CLIENT-005](#tb-client-005)）**：观看端新增解码追赶（catch-up）——积压持续 2 s 即作废已入队的 AU、flush 解码器，只从下一个校验过的 IDR 恢复；冷却 5 s，连续 3 次不收敛上报 `rx.converge.fail` 并停 30 s，不自动降服务端参数（属 DV-012）。新增 `rx.catchup`/`rx.catchup.shed`/`rx.catchup.lat`/`rx.catchup.why`/`rx.converge.fail` 与 HUD `cu`。全量 **946 tests / 0 failures / 0 errors / 0 skipped**（84 个测试类），`assembleDebug` 成功。**设备实测不在本棒**：[DV-001](decision-board.md) 全量基线待下一次设备棒随该策略一起执行。
- 下一步：按 [渐进优化路线图](optimization-roadmap.md) 的阶段 0 启动可观测性与基线，落地顺序以 [编码框架](codec-framework.md) 为准。
- 前置约束：拿到基线前，不预设任何性能、发热或延迟收益。
- 设备验证未解禁：双语资源与系统语言跟随仅经构建、单测与代码审查确认。
- 2026-10-20 双机联机验证（真机↔模拟器）已执行：媒体链路双向跑通且稳定（硬编↔软解两方向，0 崩溃 0 ANR）；发现通道在 NAT 组合下不可用，跨机互见需第二台真机（[TB-DISC-012](#tb-disc-012)）。DV-001（TCP 是否升级 RTP/UDP）本轮因无传输仪表（RTT/重传/帧间隔/解码截止）无法裁决，维持 gRPC 双向流现状，待 [TB-OBS-001](#tb-obs-001) 仪表后复评。

> 本轮裁决记录见 [决策看板](decision-board.md)；“已关闭”与“待确认”描述讨论与验证状态，不是实施状态，对应任务仍在待办或等待验证。

## 🚧 进行中

当前无进行中任务。

任务进入“进行中”时，应同时写清范围、交付物和验证方式；完成后先补验证证据，再移入“已完成”。

## 📋 待办

| ID | 状态 | 优先级 | 标题 | 创建 | 完成 | 关联 |
| --- | --- | --- | --- | --- | --- | --- |
| TB-SEC-002 | 🔶 部分完成 | 🔴 P0 | 身份密钥瞬时故障静默删键与三份身份快照（N3；代码完成，设备复测待棒） | 2026-10-21 | — | [决策 DB-SEC-003](decision-board.md#db-sec-003) |
| TB-DISC-013 | 🔶 部分完成 | 🟠 P1 | 发现行首连的证书指纹引导（N2；代码完成，设备复测待棒） | 2026-10-21 | — | [TB-DISC-012](#tb-disc-012) |
| TB-SEC-003 | 🔶 部分完成 | 🟠 P1 | 公告传输安全字段与「画面私密」分离（N1；代码完成，设备复测待棒） | 2026-10-21 | — | [决策 DB-SEC-002](decision-board.md#db-sec-002) |
| TB-POWER-001 | 🔶 部分完成 | 🟠 P1 | 无观看者懒停与热降档（代码完成，设备验证待棒） | 2026-10-21 | — | [决策 DB-POWER-001](decision-board.md#db-power-001) |
| TB-ENG-013 | 📋 待办 | 🟡 P2 | 统一回归：稳定性/长时测试清单（功能全部完成后执行） | 2026-10-21 | — | [TB-WIN-001](#tb-win-001) |
| TB-DISC-012 | 📋 待办 | 🟡 P2 | 双真机联机验证 | 2026-10-20 | — | [发现调研](discovery-research.md) |
| TB-DISC-011 | 📋 待办 | 🟡 P3 | 手动添加条目跨重启持久化 | 2026-10-20 | — | [发现调研](discovery-research.md) |
| TB-MEDIA-004 | 📋 待办 | 🟡 P3 | 首帧绿色花屏自愈 | 2026-10-20 | — | [媒体架构](media-architecture.md) |
| TB-MEDIA-005 | 📋 待办 | 🟢 P3 | 停止共享时编码器输入面 abandoned 噪声（良性） | 2026-10-20 | — | [媒体架构](media-architecture.md) |
| TB-ENG-006 | 📋 待办 | 🟡 P2 | JPEG 降级客户端查看器接回 | 2026-10-19 | — | [问题3接线计划](superpowers/plans/2026-10-19-media-path-wiring.md) |
| TB-CORE-001 | 📋 待办 | 🔴 P0 | 正确性与资源清理 | 2026-10-17 | — | [路线图阶段 1](optimization-roadmap.md) |
| TB-CORE-002 | 🔶 部分完成 | 🔴 P0 | 会话与渲染目标解耦（解耦已落地，关窗不停会话待 DV-008） | 2026-10-17 | — | [窗口与会话设计](windowing-session-design.md) |
| TB-WIN-001 | 🔶 部分完成 | 🟠 P1 | 窗口切换（方案 B；功能冒烟通过，全项回归待统一棒） | 2026-10-17 | — | [窗口与会话设计](windowing-session-design.md) |
| TB-OBS-001 | 🔶 部分完成 | 🔴 P0 | 可观测性与基线（O1–O6 已落地，O7 待设备） | 2026-10-17 | — | [本计划](superpowers/plans/2026-10-21-transport-observability.md) |
| TB-FW-002 | 🔶 部分完成 | 🟠 P1 | JPEG 适配 | 2026-10-17 | — | [编码框架设计](codec-framework.md) |
| TB-SEC-001 | 🔶 部分完成 | 🟠 P1 | 配对与授权（默认拒绝 + 审批 + 持久授权；代码完成，设备验证待棒） | 2026-10-17 | — | [实施计划](superpowers/plans/2026-10-21-sec-pairing.md) |
| TB-AUDIO-001 | 🔶 部分完成 | 🟡 P2 | 音频链路（A1–A7 代码完成，设备验证待 TB-AUDIO-003） | 2026-10-17 | — | [音频计划](superpowers/plans/2026-10-21-audio.md) |
| TB-AUDIO-002 | ⏸ 待裁决 | 🟡 P2 | 麦克风降级（D1=不做，未激活） | 2026-10-21 | — | [音频计划 6.9](superpowers/plans/2026-10-21-audio.md) |
| TB-AUDIO-003 | ⏳ 等待设备验证 | 🟡 P2 | 音频设备验证清单（并入 TB-ENG-013 统一回归） | 2026-10-21 | — | [音频计划 6.10](superpowers/plans/2026-10-21-audio.md) |
| TB-CLIENT-001 | 📋 待办 | 🟡 P2 | 客户端画质与多客户端 | 2026-10-17 | — | [媒体架构](media-architecture.md) |
| TB-CLIENT-002 | 📋 待办 | 🟡 P2 | 单流与多档流策略 | 2026-10-17 | — | [媒体架构](media-architecture.md) |
| TB-CLIENT-006 | 📋 待办 | ⚪ P3 | 小窗 HUD 折行与字号自适应 | 2026-10-21 | — | [TB-OBS-001](#tb-obs-001) |
| TB-CTRL-001 | 📋 待办 | 🟡 P2 | 单客户端反控 | 2026-10-17 | — | [媒体架构](media-architecture.md) |
| TB-ENG-001 | 📋 待办 | 🟡 P2 | 测试与发布工程 | 2026-10-17 | — | [项目现状](project-assessment.md) |
| TB-ENG-002 | 📋 待办 | 🟡 P2 | 发布签名加固 | 2026-10-17 | — | [启动与签名清单](startup-checklist.md) |
| TB-FW-007 | 📋 待办 | 🟡 P2 | HEVC 扩展验证 | 2026-10-17 | — | [编码框架设计](codec-framework.md) |
| TB-FW-008 | 📋 待办 | 🟡 P2 | 框架测试与证据 | 2026-10-17 | — | [编码框架设计](codec-framework.md) |

> 说明：`TB-FW-001` 至 `TB-FW-008` 是 `TB-MEDIA-001`、`TB-CLIENT-001` 与 `TB-CLIENT-002` 的框架分解，实施时合并跟踪，不重复计算完成率。`TB-FW-001`、`TB-FW-004`、`TB-FW-005` 已完成（隔离契约、Hub/队列与客户端状态机的 JVM 验证）；`TB-FW-002` 部分完成（JPEG 参考适配与 JVM E2E，批次 D 已接入生产降级路径）；`TB-FW-003`、`TB-FW-006`、`TB-MEDIA-001`、`TB-MEDIA-003` 已转「⏳ 等待验证」（批次 A–H 完成生产接线、切换编排、H.264 硬编硬解与能力选档，仅 JVM/fake 与编译验证）；`TB-FW-007`、`TB-FW-008` 仍未完成。代码完成不代表设备验证、RTP 或问题关闭已经存在。

## ✅ 已完成

| ID | 状态 | 优先级 | 标题 | 创建 | 完成 | 关联 |
| --- | --- | --- | --- | --- | --- | --- |
| TB-DISC-010 | ✅ 已完成 | 🟠 P1 | 自签 TLS 与 TOFU 身份 pinning | 2026-10-20 | 2026-10-21 | [实施计划](superpowers/plans/2026-10-21-tls-tofu.md) |
| TB-CLIENT-003 | ✅ 已完成 | 🟠 P1 | 查看器宽高比与缩放质量 | 2026-10-20 | 2026-10-20 | [实施计划](superpowers/plans/2026-10-21-viewer-quality-and-settings.md) |
| TB-OBS-002 | ✅ 已完成 | 🟠 P1 | RTT 探针（Ping/Pong）与估计器 | 2026-10-21 | 2026-10-21 | [本计划](superpowers/plans/2026-10-21-transport-observability.md) |
| TB-CLIENT-005 | ✅ 已完成 | 🟡 P2 | 观看端解码队列背压与追赶（catch-up） | 2026-10-21 | 2026-10-21 | [TB-OBS-001](#tb-obs-001) |
| TB-ENG-007 | ✅ 已完成 | 🟡 P2 | 客户端发送统计并入服务端统计（已并入 TB-OBS-001 O3） | 2026-10-19 | 2026-10-21 | [本计划](superpowers/plans/2026-10-21-transport-observability.md) |
| TB-CLIENT-004 | ✅ 已完成 | 🟡 P2 | 客户端呈现调度与关键帧恢复 | 2026-10-20 | 2026-10-20 | [实施计划](superpowers/plans/2026-10-21-viewer-quality-and-settings.md) |
| TB-ENG-005 | ✅ 已完成 | 🟡 P2 | 新视频设置界面（码率/IDR 可写，编码格式只读） | 2026-10-19 | 2026-10-20 | [实施计划](superpowers/plans/2026-10-21-viewer-quality-and-settings.md) |
| TB-ENG-012 | ✅ 已完成 | 🟡 P2 | 编码参数生效编排（帧率/IDR 重建） | 2026-10-20 | 2026-10-20 | [实施计划](superpowers/plans/2026-10-21-viewer-quality-and-settings.md) |
| TB-DISC-001 | ✅ 已完成 | 🟡 P2 | 设备发现调研沉淀 | 2026-10-20 | 2026-10-20 | [发现调研](discovery-research.md) |
| TB-DISC-002 | ✅ 已完成 | 🟠 P1 | 设备模型与 DeviceStore 抽象 | 2026-10-20 | 2026-10-20 | [实施计划](superpowers/plans/2026-10-20-device-discovery.md) |
| TB-DISC-003 | ✅ 已完成 | 🟠 P1 | NSD 修正与加固 | 2026-10-20 | 2026-10-20 | [实施计划](superpowers/plans/2026-10-20-device-discovery.md) |
| TB-DISC-004 | ✅ 已完成 | 🟠 P1 | 设备身份（密钥派生 ID） | 2026-10-20 | 2026-10-20 | [实施计划](superpowers/plans/2026-10-20-device-discovery.md) |
| TB-DISC-005 | ✅ 已完成 | 🟠 P1 | UDP 组播发现通道 | 2026-10-20 | 2026-10-20 | [实施计划](superpowers/plans/2026-10-20-device-discovery.md) |
| TB-DISC-006 | ✅ 已完成 | 🟠 P1 | Register 双向发现 RPC | 2026-10-20 | 2026-10-20 | [实施计划](superpowers/plans/2026-10-20-device-discovery.md) |
| TB-DISC-007 | ✅ 已完成 | 🟡 P2 | 分级扫描 | 2026-10-20 | 2026-10-20 | [实施计划](superpowers/plans/2026-10-20-device-discovery.md) |
| TB-DISC-009 | ✅ 已完成 | 🟡 P2 | 发现设置与 UI 集成 | 2026-10-20 | 2026-10-20 | [实施计划](superpowers/plans/2026-10-20-device-discovery.md) |
| TB-DISC-008 | ✅ 已完成 | 🟡 P2 | 手动 IP 与收藏 | 2026-10-20 | 2026-10-20 | [实施计划](superpowers/plans/2026-10-20-device-discovery.md) |
| TB-ENG-011 | ✅ 已完成 | 🟡 P2 | 代码结构梳理与命名统一 | 2026-10-20 | 2026-10-20 | [命名规范](naming-conventions.md) |
| TB-ENG-003 | ✅ 已完成 | 🟡 P2 | 代码文档与英文注释 | 2026-10-18 | 2026-10-19 | [实施计划](superpowers/plans/2026-10-19-code-docs-i18n.md) |
| TB-ENG-004 | ✅ 已完成 | 🟡 P2 | 中英双语资源 | 2026-10-18 | 2026-10-19 | [决策看板](decision-board.md) |
| TB-DOC-008 | ✅ 已完成 | 🔴 P0 | 框架实施计划 | 2026-10-17 | 2026-10-18 | [实施计划](superpowers/plans/2026-10-17-codec-framework.md) |
| TB-FW-001 | ✅ 已完成 | 🟠 P1 | 编码契约骨架 | 2026-10-17 | 2026-10-18 | [编码框架设计](codec-framework.md) |
| TB-FW-004 | ✅ 已完成 | 🟠 P1 | StreamHub 与队列 | 2026-10-17 | 2026-10-18 | [编码框架设计](codec-framework.md) |
| TB-FW-005 | ✅ 已完成 | 🟠 P1 | 客户端解码状态机 | 2026-10-17 | 2026-10-18 | [编码框架设计](codec-framework.md) |
| TB-CI-001 | ✅ 已完成 | 🔴 P0 | CI 构建稳定性 | 2026-10-17 | 2026-10-17 | [项目现状](project-assessment.md) |
| TB-DOC-001 | ✅ 已完成 | — | 文档体系 | 2026-10-17 | 2026-10-17 | [文档索引](README.md) |
| TB-DOC-002 | ✅ 已完成 | — | 任务与决策看板 | 2026-10-17 | 2026-10-17 | [决策看板](decision-board.md) |
| TB-DOC-003 | ✅ 已完成 | — | 采集与多客户端裁决 | 2026-10-17 | 2026-10-17 | [决策看板](decision-board.md) |
| TB-DOC-004 | ✅ 已完成 | — | 设备能力分档修订 | 2026-10-17 | 2026-10-17 | [决策看板](decision-board.md) |
| TB-DOC-005 | ✅ 已完成 | — | 编码框架设计 | 2026-10-17 | 2026-10-17 | [编码框架设计](codec-framework.md) |
| TB-DOC-006 | ✅ 已完成 | — | 启动与签名清单 | 2026-10-17 | 2026-10-17 | [启动与签名清单](startup-checklist.md) |
| TB-DOC-007 | ✅ 已完成 | — | 签名包与三设备安装 | 2026-10-17 | 2026-10-17 | [启动与签名清单](startup-checklist.md) |

> 说明：编码框架的隔离核心契约、静态注册、单活动流 Runtime、StreamHub、每客户端有界队列、客户端六状态机与 JPEG 独立帧参考适配已完成并通过 JVM 验证（Task 1–9）；这些条目完成时尚未接入生产投屏，后续批次 A–H 已把框架接入 `LssService`/`LssServer`/`LssClient`（H.264 采集直通、客户端 Surface 渲染、JPEG 降级、能力选档与切换编排，仅经 JVM/fake 及编译验证），设备验证仍未执行；RTP/HEVC/AV1 未实现。其余代码优化任务（TB-CORE、TB-WIN、TB-CLIENT、TB-AUDIO、TB-CTRL 等）仍未开始。

## ⏳ 等待验证

| ID | 状态 | 优先级 | 标题 | 创建 | 完成 | 关联 |
| --- | --- | --- | --- | --- | --- | --- |
| TB-FW-003 | ⏳ 等待验证 | 🟠 P1 | H.264 单流接入 | 2026-10-17 | — | [H.264 实施计划](superpowers/plans/2026-10-18-h264-adapter.md) |
| TB-FW-006 | ⏳ 等待验证 | 🟠 P1 | 编码切换编排 | 2026-10-17 | — | [编码框架设计](codec-framework.md) |
| TB-MEDIA-001 | ⏳ 等待验证 | 🟠 P1 | H.264 硬编硬解 | 2026-10-17 | — | [媒体架构](media-architecture.md) |
| TB-MEDIA-003 | ⏳ 等待验证 | 🟠 P1 | 运行时能力探测 | 2026-10-17 | — | [媒体架构](media-architecture.md) |
| TB-CODEC-001 | ⏳ 等待验证 | ⚪ P3 | HEVC 可选档位 | 2026-10-17 | — | [媒体架构](media-architecture.md) |
| TB-NET-001 | ⏳ 等待验证 | ⚪ P3 | RTP/UDP 传输 | 2026-10-17 | — | [路线图阶段 5](optimization-roadmap.md) |
| TB-NET-002 | ⏳ 等待验证 | ⚪ P3 | WebRTC 互操作 | 2026-10-17 | — | [路线图阶段 5](optimization-roadmap.md) |
| TB-PROD-001 | ⏳ 等待验证 | ⚪ P3 | 场景边界 | 2026-10-17 | — | [产品语境](product-context.md) |
| TB-WIN-002 | ⏳ 等待验证 | ⚪ P3 | PiP 可选增强 | 2026-10-17 | — | [窗口与会话设计](windowing-session-design.md) |

## ⏸️ 暂缓

| ID | 状态 | 优先级 | 标题 | 创建 | 完成 | 关联 |
| --- | --- | --- | --- | --- | --- | --- |
| TB-CLOUD-001 | ⏸️ 暂缓 | ⚪ P3 | 云端中继与账号 | 2026-10-17 | — | [路线图](optimization-roadmap.md) |
| TB-CTRL-002 | ⏸️ 暂缓 | ⚪ P3 | 多客户端并发控制 | 2026-10-17 | — | [媒体架构](media-architecture.md) |
| TB-DISPLAY-001 | ⏸️ 暂缓 | ⚪ P3 | 4K/120/HDR 默认目标 | 2026-10-17 | — | [路线图](optimization-roadmap.md) |
| TB-MEDIA-002 | ⏸️ 暂缓 | ⚪ P3 | 每客户端转码 | 2026-10-17 | — | [媒体架构](media-architecture.md) |
| TB-REWRITE-001 | ⏸️ 暂缓 | ⚪ P3 | Kotlin 重写 | 2026-10-17 | — | [路线图](optimization-roadmap.md) |
| TB-ROOT-001 | ⏸️ 暂缓 | ⚪ P3 | root 输入注入 | 2026-10-17 | — | [媒体架构](media-architecture.md) |

## 📎 条目详情

> 每条含原始任务、交付物与全部证据链接；概览表只做导航，提交哈希、CI run 与测试数字只在本节维护。

### 🚧 进行中条目

当前无进行中任务。

### 📋 待办条目



<a id="tb-sec-002"></a>
<details><summary>TB-SEC-002 身份密钥瞬时故障静默删键与三份身份快照（🔶 部分完成 · N3）</summary>

- **状态 / 优先级 / 创建 / 完成**：🔶 部分完成（代码 + JVM 用例完成，设备复测待棒）/ 🔴 P0 / 2026-10-21 / —
- **来源**：双真机首测交接 `device-test-2026-10-21-dual/report.md` §1.1 与 §5 的 **N3**（仓库外交接目录，2026-10-21），以及派发方转述的权威诊断结论 **F1–F4**。
- **现场**：两台真机能互相发现（组播 0.336 s 互见），但双向手动添加都被拒：`RegisterAddressProbe: probe: address did not answer, reason = identity_mismatch`（232 ms / 152 ms），而 TLS 握手本身成功（`transportReady: securityLevel=PRIVACY_AND_INTEGRITY`）。服务端公告与设置页身份 `8GW5VX7BPW1ED9FDAQ1XHZM0ZR` / `44385DF4…CFCB33C6` 彼此自洽（可用指纹反算 id），但都不是握手证书的那把密钥；只能手动「重置设备身份」恢复。
- **根因 A（瞬时错误 → 静默删键）**：`AddressScanner.scan()` 在 finally 里用 `executor.shutdownNow()` 取消，打断在飞探测线程；keystore 读被 `InterruptedException` 打断后，`AndroidKeystoreKeyStore.readKeyPair()` 的 `catch (GeneralSecurityException) → null` 把"这次读失败"与"密钥不可读"混为一谈，`loadOrCreate()` 随即 `deleteEntry` 重建密钥（实测 1.4 s 内 14 次）。已运行的共享会话仍按旧身份对外。
- **根因 B（三份快照）**：公告 id 取自 `IdentityManager` 的内存缓存、`ServerInfo` 在 `start()` 时另取一次、握手证书来自 `TlsMaterialHolder`；三者互不同步，一旦密钥被替换就永久错位，对端 `GrpcRegisterStub.withObservedFingerprint` 判 `self.device_id != cert.device_id`。
- **修法**（提交 `8bdd113` 与 `76ba8a9`，F1–F4）：
  - **F1 替换策略**：新增纯 JVM `identity/KeyEntryFailurePolicy`（`TRANSIENT`：中断 / IO / 无法归类 → 保留键；`UNUSABLE`：`KeyPermanentlyInvalidatedException`、`UnrecoverableKeyException`、证书缺失 → 才可替换）；新增 `identity/IdentityKeyEntryLoader`（删除前二次读取确认、每进程最多替换一次、线程已中断时不碰 keystore、回报替换原因与新旧指纹）；新增 `identity/KeystoreExecutor`（全部 keystore 操作含证书自签串到单线程 `lss-keystore`，被中断的等待者只放弃等待、不打断正在执行的 keystore 操作）；`AndroidKeystoreKeyStore` 改为委托 loader，替换记 `Log.e`（原因 + 新旧指纹）。
  - **F2 身份口径**：`TlsMaterial.toDeviceIdentity()`；公告、`ServerInfo`、`Register` self summary、观看端 `HELLO`（改用建通道时保留的材料，保证与本次握手证书同源）、通知与设置页指纹全部改从 `TlsMaterialHolder` 当前材料派生（对外口径恒等于握手证书）；材料随密钥重建时发出身份变更事件，`ScreenShareService` 据此刷新 `ServerInfo`、公告与通知。
  - **F3 重置身份**：`ACTION_IDENTITY_RESET` 分支改为在 `lss-server-control` 线程重读材料并触发上述事件；`ScreenShareServer.onIdentityChanged()` 重建 `ServerInfo` 并重发 NSD 记录（实例名与 TXT 里的 id 都跟着变）；修正 `MainActivity` 里说反的注释（原写"重置后共享会一直播旧身份直到重启"）。
  - **F4 温和取消**：`AddressScanner` 取消改为 token → `shutdown()` → 有界等待（`WORKER_DRAIN_TIMEOUT_MS`），不再 `shutdownNow()` 打断在飞探测；探测发起前再查 token 与中断标记；`RegisterAddressProbe` 在已中断线程上直接失败、不读身份。
- **JVM 证据**：`KeyEntryFailurePolicyTest`（9 例）、`IdentityKeyEntryLoaderTest`（10 例，`aTransientReadFailureNeverDeletesTheKey` 与 `aTransientFailureDuringTheSecondConfirmationAlsoKeepsTheKey` 为"瞬时故障零删键"锁定用例，`aSecondReplacementInTheSameProcessIsRefused` 锁"每进程一次"）、`TlsMaterialHolderTest`（7 例，含换钥后材料/证书/事件）、`IdentityCoherenceTest`（3 例：公告 id == 证书 id == register self id，且换钥后三者一起跟随）、`AddressScannerTest` +1（取消不打断在飞探测）。全量 **1315 tests / 128 classes / 0 failures / 0 errors / 0 skipped**。
- **设备判据（待验证棒）**：① 共享进行中触发一次扫描 / 网络切换重扫，`adb logcat | grep -c "del USRPKEY"` 与 `logcat -b events | grep -c USRPKEY` 均为 **0**，且无 `identity key replaced` 的 error 行；② 同一会话内四处口径逐字符一致：服务端通知 `比对指纹`、设置页「密钥指纹」、对端首连弹框指纹、`Register` 应答里的 `self.device_id`；③ 手动「重置设备身份」后**不重启共享**，对端应收到身份变更而不是 `identity_mismatch`，且服务端通知里的指纹随即更新；④ 扫描取消期间 `keystore` 无 `InterruptedException` 相关告警。
- **遗留风险**：`AndroidKeystoreKeyStore.sVerifiedFingerprint` 仍是进程内记忆（同进程内不会再探针）；"每进程最多一次替换"意味着同进程内第二次真实不可用会持续报 `identity_unavailable`，需要用户手动重置——这是刻意的（避免反复换身份）。`KeystoreExecutor` 是单线程，keystore 卡住时所有握手都会排队等待其失败。
- **关联文档**：[决策 DB-SEC-003](decision-board.md#db-sec-003)、[TB-DISC-010](#tb-disc-010)（身份与 pin 的既有合同）、[TB-DISC-012](#tb-disc-012)

</details>

<a id="tb-disc-013"></a>
<details><summary>TB-DISC-013 发现行首连的证书指纹引导（🔶 部分完成 · N2）</summary>

- **状态 / 优先级 / 创建 / 完成**：🔶 部分完成（代码完成，设备复测待棒）/ 🟠 P1 / 2026-10-21 / —
- **来源**：双真机首测交接 `device-test-2026-10-21-dual/report.md` §1.1(b) 与 §5 的 **N2**。
- **现场**：组播发现到的行点击后直接弹「该设备出示的证书与其设备 ID 不一致…这是安全检查，无法绕过」。公告按设计不带指纹，未做过 `Register` 探测的行因此没有可校验值，`MainActivity` 落到 `identity_mismatch` 分支——把"信息不足"说成了"疑似攻击"，而且首次使用只能手动输入 IP，与"不再依赖手动 IP"的目标相反。
- **修法**（提交 `3b5ad60`、`f43a89f`）：新增 `DiscoveryManager.probeIdentity(DeviceInfo, callback)` 与 `ManualAddressProvider.probeIdentity(host, port, callback)`，对已发现的行做**一次直连 `Register`**（TLS 握手本身就能拿到证书指纹），成功后按既有确认路径写回 store（该行照常可用）；写回时记 `DiscoveryChannel.MULTICAST` 而不是 `MANUAL`，否则 `DeviceStore.setPinned` 会把"只是点了一下"的设备钉成常驻条目（停止广播后一直显示不可达），也不把地址记进手动目标表；`MainActivity.startScreenShareViewer()` 在指纹缺失时改走 `resolveDeviceIdentityThenConnect()`：先弹"正在读取该设备的证书…"说明框，成功后用 store 里的新行重新进入既有 TOFU/pin 判定（首连确认、变更确认、静默放行）。用 `mFingerprintProbeId` 丢弃取消或换选设备后的迟到回调。
- **失败分流**：真正的 `identity_mismatch`（证书不属于所报 id）仍走既有安全文案；超时 / 不可达 / 无地址走新的中性文案 `tls_identity_probe_failed`（"未能读取该设备的证书…它的身份没有得到任何确认"）。新增双语 `tls_identity_probe_message` / `tls_identity_probe_failed`。
- **JVM 证据**：`ManualAddressProviderTest` +2（身份探测的确认记在 `MULTICAST` 且不产生手动目标；探测失败只回调原因、不写确认）。
- **设备判据（待验证棒）**：① 全新安装、只靠组播发现，点对方行 → 先出现"正在读取证书"，随后弹首连确认框，指纹与对端通知栏一致 → 点「信任此设备」出画；② 未被发现的设备（关机）点行 → 中性失败文案，不再出现"身份不一致"；③ 真实不一致（同地址另一把密钥的端点）仍报 `identity_mismatch`；④ 探测期间按返回/取消，不弹任何后续框。
- **关联文档**：[TB-DISC-012](#tb-disc-012)、[TB-DISC-010](#tb-disc-010)

</details>

<a id="tb-sec-003"></a>
<details><summary>TB-SEC-003 公告传输安全字段与「画面私密」分离（🔶 部分完成 · N1）</summary>

- **状态 / 优先级 / 创建 / 完成**：🔶 部分完成（代码完成，设备复测待棒）/ 🟠 P1 / 2026-10-21 / —
- **来源**：双真机首测交接 `device-test-2026-10-21-dual/report.md` §1.1(a) 与 §5 的 **N1**。
- **现场**：全新设备默认配置（画面私密关）下，观看端点任何跨机行都弹「该设备不支持加密连接。」；把被看方「画面私密」打开后公告 `sec` 由 `false` 变 `true`，拦截才消失。而传输层一直是 mTLS。
- **根因**：`ScreenShareService` 把「画面私密」（`projection_secure`，FLAG_SECURE 语义）写进公告的 `sec`，观看端 `MainActivity` 又用它判断"对端是否支持加密传输"，服务端 `Register` 摘要与握手证书用的却是 `mTransportSecure`——同一字段三种含义。
- **设计意图（用户已确认）**：「画面私密」= 观看端窗口 `FLAG_SECURE`（客户端截图 / 录屏截不到服务端画面），纯显示保护，不是连接门槛。
- **修法**（提交 `27f991a`）：公告的 `secure` / `auth` 恒表示传输能力（TLS 上线后恒 `true`，只有隐藏的 `tls_allow_plaintext` 才 `false`），与私密开关彻底解耦；私密开关只影响 `Descriptor.secure` / FLAG_SECURE 路径；连接拒绝逻辑仍然只认传输安全字段（`DeviceInfo.secure`，语义现在正确）。文案：`tls_device_not_encrypted` 改为行内短标签「未加密传输」，新增 `tls_device_not_encrypted_message` 说明真实原因（该设备只提供未加密连接，由隐藏的明文调试开关启用），「画面私密」开关下新增 `projection_secure_setting_note`（只保护显示、连接本身始终加密）；`Announcement.secure` 的 Javadoc 写明该字段只描述传输。中英双语同步。
- **设备判据（待验证棒）**：① 两台全新设备都保持默认（画面私密关）→ 点行不再被拒，首连确认后可出画；② 打开被看方「画面私密」→ 观看端窗口仍是 `FLAG_SECURE`（`adb exec-out screencap` 为空即证），且不影响连接；③ 组播报文里 `sec` 恒为 `true`（明文调试开关关闭时），与私密开关无关；④ 打开隐藏的明文调试开关后，`sec=false` 且对端按 `tls_device_not_encrypted_message` 文案拒绝。
- **关联文档**：[决策 DB-SEC-002](decision-board.md#db-sec-002)、[TB-SEC-001](#tb-sec-001)、[TB-DISC-010](#tb-disc-010)

</details>

<a id="tb-disc-011"></a>
<details><summary>TB-DISC-011 手动添加条目跨重启持久化（📋 待办）</summary>

- **状态 / 优先级 / 创建 / 完成**：📋 待办 / 🟡 P3 / 2026-10-20 / —
- **任务**：手动添加（pinned）条目目前只存于内存 `DeviceStore`，进程重启后清空，需重新手动添加。若产品期望跨进程保留，需把 pinned 条目持久化（类似 `FavoriteStore` 的路径）；否则保持现状并记录该限制。
- **交付物 / 验证证据**：交付：手动条目持久化与恢复、相关单测。验证：重启后手动条目仍在列表；当前未实施，仅登记设计观察。
- **关联文档**：[发现调研](discovery-research.md)、[TB-DISC-008](#tb-disc-008)

</details>

<a id="tb-media-004"></a>
<details><summary>TB-MEDIA-004 首帧绿色花屏自愈（📋 待办）</summary>

- **状态 / 优先级 / 创建 / 完成**：📋 待办 / 🟡 P3 / 2026-10-20 / —
- **任务**：首次连接约 3 s 时首帧为绿色花屏（首个 GOP 未就绪的典型形态），约 10 s 内自行恢复；重连未复现。若双机验收要求首帧即正确，需检查 join point/首个关键帧与 SPS/PPS 的应用时机（`ClientMediaSession` 侧）。
- **交付物 / 验证证据**：交付：首帧正确性排查与修复、相关单测。验证：首帧即正确、无花屏；当前未实施，仅登记观察。
- **关联文档**：[媒体架构](media-architecture.md)

</details>

<a id="tb-disc-012"></a>
<details><summary>TB-DISC-012 双真机联机验证（📋 待办）</summary>

- **状态 / 优先级 / 创建 / 完成**：📋 待办 / 🟡 P2 / 2026-10-20 / —
- **任务**：需第二台真机才能验证的事项：跨机 NSD/组播/分级扫描互见（模拟器 NAT 不转发组播，已实测确认）、双真机完整联机行为（真实 LAN RTT/丢包、双端互操作）、收藏与身份跨机行为、多观看者（依赖 [TB-CLIENT-001](#tb-client-001)）。**TLS 专项**（[计划 9.7 D1–D10](superpowers/plans/2026-10-21-tls-tofu.md)）：AndroidKeyStore 私钥握手、API 25 的 TLS 1.2、证书字段解析、首次确认流程、指纹变更拒绝、首连 MITM 演练、TLS 下的扫描预算、性能 A/B、功耗/温升、备份恢复后身份变化。
- **交付物 / 验证证据**：交付：第二台真机下的互见与联机证据。验证：待用户提供第二台真机后执行；当前仅真机↔模拟器联机（媒体链路双向跑通，发现跨 NAT 不可用）。
  - **2026-10-21 进展**：T6 设备棒报出的阻塞项 N-t6-1（AndroidKeyStore 私钥无法签 TLS 摘要）、P1 N-t6-2（主线程 keystore/服务启动触发 ANR）、P3 N-t6-3（重置身份后通知指纹不刷新）已修复并取得设备证据，详见 [TB-DISC-010](#tb-disc-010)：D1（私钥握手）在 API 29 服务端 → API 36 观看端以 TLS 1.3 双向握手出画通过，D4 首次确认框与管理探测链路一并打通。仍待复测：API 25 的 TLS 1.2（D2）、D5 观看端半程、同二层伪造公告拒绝（D6）、TLS 下扫描预算（D7）、TLS 侧性能 A/B（D8）、备份恢复（D10），以及 T6 报告的 B 轮（观看端单帧成本、`rx.catchup` 判据）与 N2 线程尖峰。
  - **2026-10-21 复测（r2）结果**：✅ D1（API 29 服务端 → API 36 观看端 mTLS 出画）、D4（首连确认全流程：64 hex 与真机逐字节一致、可复制、"这不是配对"、取消不可看、信任后出画）、D6（同机伪造公告 + 持另一把密钥的端点 → `identity_mismatch`，两条路径都无绕过入口）、D8（TLS vs 明文各 60 s：服务端 `enc.cap2out.p50` 17.6 vs 17.1 ms、`tx.send.p95` 2.3 vs 4.4 ms，无可测回退）、B 回归（TLS 1080p30 2 min：`dec.lat.p50=99.6 ms`、`rx.catchup +6`、无掉线、FATAL/ANR 0）；⚠️ D5 行为通过、判据改写（见 [TB-DISC-010](#tb-disc-010) 的 N-r2-2）；⚠️ D2 手动添加与 mTLS 握手通过、出画受 API 25 模拟器软解 `0x80001019` 限制（N-r2-5）；⚠️ D7 手动 IP 通过、NSD/组播在模拟器 NAT 后不可达（需同二层双真机）；⚠️ D9 仅缩短版（36→39 ℃、`thermal=0`）。剩余覆盖缺口：API 25 出画、双真机 NSD/组播、30 min 功耗基线、D10 备份/恢复，以及本批 N-r2-1/N-r2-3 的修复后设备复验。
- **关联文档**：[发现调研](discovery-research.md)

</details>

<a id="tb-media-005"></a>
<details><summary>TB-MEDIA-005 停止共享时编码器输入面 abandoned 噪声（📋 待办）</summary>

- **状态 / 优先级 / 创建 / 完成**：📋 待办 / 🟢 P3 / 2026-10-20 / —
- **任务**：停止共享瞬间，编码器输入面（`GraphicBufferSource`）出现 1–2 条 `BufferQueue has been abandoned` 日志；无崩溃、无后续异常，属良性噪声。N3 修复后查看器纹理侧 abandoned 已为 0，编码器输入面仍余少量。
- **交付物 / 验证证据**：交付：无（良性观察，暂不实现）。验证：如后续出现停止瞬间偶发 Surface 异常可优先怀疑此段时序。
- **关联文档**：[媒体架构](media-architecture.md)

</details>

- **关联文档**：[问题3接线计划](superpowers/plans/2026-10-19-media-path-wiring.md)

</details>

<a id="tb-eng-006"></a>
<details><summary>TB-ENG-006 JPEG 降级客户端查看器接回（📋 待办）</summary>

- **状态 / 优先级 / 创建 / 完成**：📋 待办 / 🟡 P2 / 2026-10-19 / —
- **任务**：批次 C 后生产客户端只接 `SurfaceRenderTarget`（H.264 路径），`BitmapRenderTarget` 无生产引用；JPEG 降级模式下客户端无可显示查看器。需在 JPEG 模式接回 Bitmap 查看器（可参考批次 C 前的 `ScreenShareViewer` 实现）。
- **交付物 / 验证证据**：交付：JPEG 模式查看器接线与降级路径单测。验证：JPEG 降级会话在客户端可见画面；当前未实施，设备验证未解禁。
- **关联文档**：[问题3接线计划](superpowers/plans/2026-10-19-media-path-wiring.md)

</details>

<a id="tb-eng-007"></a>
<details><summary>TB-ENG-007 客户端发送统计并入服务端统计（已并入 TB-OBS-001 O3，见已完成条目）</summary>

本条目已按 [本计划](superpowers/plans/2026-10-21-transport-observability.md) 8.4 并入 [TB-OBS-001](#tb-obs-001) 的 O3 并已完成；详情见「✅ 已完成条目」中的同名块。

</details>

<a id="tb-core-001"></a>
<details><summary>TB-CORE-001 正确性与资源清理（📋 待办）</summary>

- **状态 / 优先级 / 创建 / 完成**：📋 待办 / 🔴 P0 / 2026-10-17 / —
- **任务**：正确性与资源清理：幂等停止、单一生命周期所有者、线程池终止、缩短临界区、媒体路径移除 `CallerRunsPolicy`、resize 代次隔离、权限撤销降级、重连退避、IPv6 地址与 NSD 服务类型修正、服务名隐私。
- **交付物 / 验证证据**：交付：代码改动与单元/仪器/压力测试。验证：阶段 1 的启动停止、旋转尺寸、网络、慢客户端、权限、回归各项通过；当前未执行。
- **已观察实例**：`StreamRuntime.start()` 中 `registry.require(...)` 在 try 之外，未注册 codecId 会使 attempt 停在 `CONFIGURING`，且 runtime 无法重试 `start()`（只能 `close()`）；2026-10-19 验收抽审时记录，未改行为。
- **关联文档**：[路线图阶段 1](optimization-roadmap.md)、[项目现状风险清单](project-assessment.md)

</details>

<a id="tb-core-002"></a>
<details><summary>TB-CORE-002 会话与渲染目标解耦（🔶 部分完成 · 2026-10-21）</summary>

- **状态 / 优先级 / 创建 / 完成**：🔶 部分完成 / 🔴 P0 / 2026-10-17 / —
- **任务**：`ClientSession` 与 `RenderTarget` 解耦：Service 持有会话，窗口只 attach/detach；关闭窗口不等于停止会话；先用现有 JPEG 与悬浮窗接入。
- **交付物 / 验证证据（2026-10-21，随 [TB-WIN-001](#tb-win-001)）**：Service 持有 `ClientMediaSession` 与固定的 `SwitchableRenderTarget`；悬浮窗与全屏 Activity 均以 `render/ViewerHost` 身份 attach/detach，切换只重建解码器（`ClientMediaSession.rebindSurface()`），网络会话/认证/descriptor 不变；几何、安全标志与调试行由 Service 统一下发，宿主可延迟接入。JVM 证据见 TB-WIN-001 的 12 条用例；窗口切换的设备验证未执行。
- **未完成部分**：`关闭窗口不等于停止会话` —— 关窗/断开仍结束会话（保持现状），待 [DV-008](decision-board.md) 产品裁决；JPEG 降级查看器（[TB-ENG-006](#tb-eng-006)）未接回。
- **关联文档**：[路线图阶段 1](optimization-roadmap.md)、[窗口与会话设计](windowing-session-design.md)

</details>

<a id="tb-power-001"></a>
<details><summary>TB-POWER-001 无观看者懒停与热降档（🔶 部分完成 · 代码与 JVM 验证完成，设备验证待棒）</summary>

- **状态 / 优先级 / 创建 / 完成**：🔶 部分完成 / 🟠 P1 / 2026-10-21 / —（代码完成 2026-10-21；设备验证未执行）
- **任务**：第 8 问（发烫/功耗）的剩余部分。问答给出的四条根因里，「没有客户端时也持续编码」与「默认 30fps」两条此前只做了音频侧（`AudioCapturePolicy` 懒停采），视频侧未核查；本条目补齐视频侧懒停与热降档，不做多档编码器实例/多档流（DV-012/015 服务端部分）与功耗数值建模。
- **核查结论（懒停）**：视频侧此前**完全不参考观看者数量**——`StreamRuntime.start()` 创建编码器后 `VirtualDisplay` 立刻开始合成，`onImageAvailable`/编码输出一路走到 `StreamHub`，0 观看者时扇出列表为空但合成与编码照常，因此「无人观看」与「有人观看」的功耗相同。音频侧已有 `AudioCapturePolicy`（0 观看者 → `ACTIVE→OFF`），本条目按同一规则补视频侧。
- **实施范围（提交 `377e581`、`00ee006`、`68bfd5e`、`45ed888`）**：
  - `core/stream/ViewerCapturePolicy`（纯决策，6 条 JVM 用例）：0 观看者且运行时 `READY` → `PAUSE`；有观看者且已暂停 → `RESUME`；未 `READY` 或观看者数量未知 → `NONE`。
  - `ScreenShareService`：独立的 1 s 观看者轮询（与音频轮询分开，因为视频要覆盖所有 API 等级且与声音开关无关）。`PAUSE` 用 `VirtualDisplay.setSurface(null)` 摘下输出面（合成与编码同时停止，编码器保持分配、已发布描述符继续有效）；`RESUME` 重新绑定当前编码器面（JPEG 路径绑定 `ImageReader` 面）并 `requestJoinPoint()`；暂停期间的分辨率切换/编码器重建不再把显示面接回去；`stopService()` 先停轮询再复位状态。不暂停「未 READY」的运行时是硬约束：H.264 的 SPS/PPS 来自首个输出格式，没有帧就永远发不出配置，客户端会一直 `NOT_READY`。
  - `core/stream/ThermalThrottlePolicy`（纯决策，7 条 JVM 用例）：`NONE`/`LIGHT`/`MODERATE` → `NONE`；`SEVERE` → `REDUCED`（帧率 ≤30）；`CRITICAL`/`EMERGENCY`/`SHUTDOWN` → `MINIMAL`（帧率 ≤30 且采集 ≤50%）；未知/未来值按 `NONE`；上限只降不升。
  - `service/ThermalMonitor`（`@RequiresApi(29)` 封装 `addThermalStatusListener`/`getCurrentThermalStatus`，注册后补发当前状态）；`ScreenShareService` 在 `onCreate` 起监听、`onDestroy` 停监听，`applyThermalTier` 只改**生效值**（`effectiveProjectionFrameRate`/`effectiveProjectionScale`）：尺寸变化走既有「先换编码器再改显示」编排，仅帧率变化走 300 ms 防抖重建（`switchDescriptor`），回落/关开关立即恢复用户设定；用户偏好全程不被改写。
  - 可观测：档位变化打 `thermal: event=throttle|recover|disabled status=… tier=… frameRate=生效/用户 scale=生效/用户`；懒停/恢复打 `capture: event=pause|resume reason=…`；通知副标题在降档期间显示「过热降档：30 fps（，50% 画面）」，与指纹、音频原因合并；`lss.device` 行继续复用既有 `thermal` 字段。
  - 设置：视频设置 sheet 新增「过热时自动降档」开关（`KEY_THERMAL_THROTTLE`，默认开，中英双语 + 说明文案）。
- **验证证据（JVM）**：`rm -rf app/build/test-results/testDebugUnitTest` 后 `./gradlew :app:testDebugUnitTest` 全量 **1275 tests / 124 classes / 0 failures / 0 errors / 0 skipped**（基线 1262/122，只增不减，新增 `ViewerCapturePolicyTest` 6 条与 `ThermalThrottlePolicyTest` 7 条）；`./gradlew :app:assembleDebug` 通过；`:app:lintDebug` **0 errors / 146 warnings**（Thermal API 的 `NewApi` 由 `ThermalMonitor` 的 `@RequiresApi(29)` + `ScreenShareService` 中的显式 `SDK_INT` 门控满足，`stopThermalMonitor`/`onThermalThrottleSettingChanged` 也各有一处显式门控）；`check_strings.py`（144 键双一致）、`check_cjk.py`、`check_docs.py` 通过。
- **设备验证点位（短小口径，未执行）**：
  1. **懒停生效**：开共享、不连接任何观看端 → 10 s 内 logcat 出现 `capture: event=pause reason=no_viewer sessions=0`，随后 `lss.metrics` 行不再有 `enc.auRate`/`enc.bitrate`、`enc.cap2out` 不再出新样本；对照前后 CPU 占用与机身温度。
  2. **恢复**：连接观看端 → ≤1 s 内 `capture: event=resume reason=viewer_joined`，首帧出现且画面正常；断开再连重复 3 次（记录「加入 → 首帧」时延）。
  3. **热降档（SEVERE）**：`adb shell cmd thermalservice override-status 3`（该命令走 shell 权限；机型不支持时改看 `adb shell dumpsys thermalservice` 的当前状态，或临时用代码桩把 status 固定为 3）→ 期望 `thermal: event=throttle status=3 tier=REDUCED frameRate=30/<用户值>`，`enc.outInterval.p50 ≈ 33 ms`（或至少明显上升），通知副标题出现「过热降档」。
  4. **热降档（CRITICAL）**：`override-status 4` → `tier=MINIMAL … scale=50/<用户值>`，采集尺寸按 50% 生效（日志/描述符或 `dumpsys` 可见），画面仍是同一内容只是更小。
  5. **恢复与不覆盖偏好**：`override-status 0` → `thermal: event=recover status=0 tier=NONE`，尺寸与帧率回到用户值，通知副标题的降档文案消失；视频设置里的滑杆数值**从未改变**。
  6. **开关**：关闭「过热时自动降档」→ `event=disabled` 且立即恢复；重新打开 → 按当前热状态重新降档。
  7. **切换停顿**：降档/恢复时观看端黑屏时长 ≤209 ms（棒一已知 `switchDescriptor` 口径）。
- **未覆盖 / 遗留**：① **设备验证未执行**，以上 7 条全部待棒；② `setSurface(null)` 在个别 OEM 上重新绑定后可能黑屏，若出现则改为「暂停即关闭并重建运行时」并重测首帧时延（DV-025）；③ 暂停期间编码器仍占用硬件实例，未做「暂停即释放编码器」的评估（会推进 `configEpoch` 并让客户端重新同步）；④ 多档编码器实例/多档流（DV-012/015 服务端部分）与功耗数值建模按范围不做；⑤ API 25–28 无热状态，热降档在这些版本恒为不动；⑥ 首帧 30 fps 默认值未改（`ProjectionSettings.DEFAULT_FRAME_RATE` 仍为 60，用户可调；是否改默认属于产品决策，未在本棒裁决）。
- **关联文档**：[决策 DB-POWER-001](decision-board.md#db-power-001)、[决策 DB-POWER-002](decision-board.md#db-power-002)、[DV-024](decision-board.md)、[DV-025](decision-board.md)、[DV-015](decision-board.md)、[媒体架构](media-architecture.md)

</details>

<a id="tb-obs-001"></a>
<details><summary>TB-OBS-001 可观测性与基线（🔶 部分完成 · O1–O6 已落地，O7 待设备）</summary>

- **状态 / 优先级 / 创建 / 完成**：🔶 部分完成 / 🔴 P0 / 2026-10-17 / —
- **任务**：可观测性与基线：统一指标接口，覆盖服务端采集/编码/发送、客户端接收/解码/渲染、网络与设备状态；建立测试矩阵并记录基线。范围按 [本计划](superpowers/plans/2026-10-21-transport-observability.md) 第 10 章细化为 O1–O7，交付物为「指标定义 + 结构化日志 + HUD + 基线报告」，验收沿用阶段 0 五项并补「关闭态零开销」。
- **交付物 / 验证证据**：
  - **O1 指标内核**（`74eb2b6`）：`core/util/NanoClock`、`core/metrics`（`LatencyWindow` 无锁环形缓冲 + 最近秩分位、`PipelineMetrics` 关闭态零样本零分配、`MetricsSnapshot`、`MetricsFormatter` 单行 KV 与 HUD 三行、按域拆分的四组键名常量类）。
  - **O2 服务端探针**（`c6ab4dd`）：`enc.cap2out`（自校准，连续 3 个越界即停用并置抑制标记）、`enc.outInterval`、`enc.auBytes`、`enc.auRate`、`enc.bitrate`、`kf.request`、`kf.joins`、`tx.send`（锁外计时）、`tx.qDepth`、`tx.qResidency`（`QueuePollResult` 带入队时刻）、`StreamHub.snapshotSessions()`。
  - **O3 服务端聚合**（`8b4c72e`）：`ServerStatsMapper` + `ServerStats.TransportStats` 客户端块 + 摘要行，见 [TB-ENG-007](#tb-eng-007)。
  - **O4 客户端探针**（`fcce4b8`）：`ArrivalTracker`（`rx.arr`/`rx.rowd`/`rx.stall`/`rx.seqGap`）、`dec.lat`/`dec.merged`/`dec.shed`/`dec.deadlineMiss`、`pres.interval`/`pres.late`/`pres.drop`/`freeze`、`switch.blackout`、`rx.qDepth`/`rx.qWait`/`rx.shed`、重同步原因（解码/配置/未来 epoch/无 Surface）、`ClientStats` 新增四项派生值。
  - **O5 RTT 探针**（`1ac3a9c`）：见 [TB-OBS-002](#tb-obs-002)。
  - **O6 呈现与开关**（`433fde9`）：`SurfaceShareViewer` 改为 `FrameLayout` 根 + `TextureView` + 叠加层（outline/clip/transform 仍在 `TextureView`，窗口参数移到根视图），叠加层 1 s 三行、默认关闭且关闭时不取快照；双击菜单新增「显示调试信息」；`KEY_METRICS_ENABLED`/`KEY_VIEWER_HUD_ENABLED` 默认关；两端每 10 s 输出一行 `lss.device`（热状态 API 29+、电池温度、堆、线程数，缺失值 `n/a`）。
  - **验证**：全量 **899 tests / 0 failures / 0 errors / 0 skipped**（79 个测试类，基线 812，只增不减）；`./gradlew :app:assembleDebug --offline` 成功；`check_strings.py`/`check_cjk.py`/`check_docs.py` 全绿；六批 Build 与 Checks CI 均 success。
  - **2026-10-21 设备验证首轮（D-1，P0，阻断）服务构造期 NPE**：设备报告 `device-test-2026-10-21-metrics/report.md` 显示两台设备每次启动都在 1 s 内 FATAL。根因：`ScreenShareService` 的字段初始化器 `new AndroidDeviceProfileSampler(this)`（第二处为 `ScreenViewerService` 的同款字段）在 `Class.newInstance` 期间执行，此时 `attachBaseContext` 尚未调用 → `ContextWrapper.mBase == null` → 采样器构造函数的 `getApplicationContext()` 抛 NPE → 框架报 `Unable to instantiate service`；由 `MainActivity` 的 `bindService` 触发，所以连主界面都进不去，与 `metrics_enabled` 开关无关。修法（`b6cc960`）：新增 `service/LazyContextValue`（构造期只保存上下文供应器与工厂，首次 `get()` 才解析上下文并创建值），两个服务的字段改为 holder、采样点改为 `get().sample()`；`LazyContextValueTest` 3 条守“构造不触上下文”契约（框架实例化路径在 JVM 无法复现，这是可测的那一半）。同类反模式全仓 grep（字段级 `= new Xxx(this)`）只命中这两处，`App` 与 `MainActivity` 的字段初始化器都是 Handler／集合／匿名类，不触 Context；禁令写入[本计划](superpowers/plans/2026-10-21-transport-observability.md) 8.1 的 Review Focus。设备实证（真机 Android 10 + emulator-5554 Android 16）：`installDebug` 后 `am start -W` 冷启动 `LaunchState: COLD`（0.9 s／2.2 s），日志有 `ScreenShareService.onCreate: service created` 与 `onBind`，主界面可达；真机开共享（1080×2340，`VENC_DRV_CreateChn ... Create Channel Success`），模拟器手动添加真机地址:8080 后卡片显示「直播中」，点开出画（浮窗内可见真机界面，录制计时 01:26→01:37 递增、码率 21.89→1.38 KB/s），`ScreenViewerService.onCreate: service created` 首次拿到设备证据（此前只是代码级预测）；两机 crash buffer 为空、无 `Unable to instantiate`。指标开启时设备块正常产出（`lss.metrics` 5 行、`lss.device` 2 行：`role=server thermal=0 battTempC=34.0 heapMB=71.9 threads=633`），确认惰性创建在真实上下文中取数正常。证据：`d1-fix-*.png`、`d1-fix-*-logcat*.txt`、`d1-fix-prefs-*.xml` 与 `d1-fix-evidence.md`（handoff 目录 `device-test-2026-10-21-metrics/`）。清理：两机偏好逐字节还原、force-stop、模拟器 IME 还原、无 adb forward、真机恢复熄屏。CI：Build [39524308921](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39524308921)/Checks [39524308915](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39524308915) success。
  - **2026-10-21 D-2（P3，口径）滑杆下限 17% 与推算 14% 的差异**：报告按 VENC 的 `should between [144 X 144]` 推算 14%，本机日志是 `17% (encoder minimum 176x144, window 1080x2340)`。取值链核对：`EncoderSizeLimitProbe` 取 `getSupportedWidths().getLower()`=176 与 `getSupportedHeights().getLower()`=144（各按对齐向上取），`ProjectionScalePolicy` 取两边百分比的较大者 = `max(ceil(100·176/1080)=17, ceil(100·144/2340)=7)` = 17，宽边主导；报告的 14% 把高度下限 144 代入宽边，属口径错误，代码没有算错。补充实测：把 `projection_scale` 强写 14%（151 → 对齐 148×324）后编码器直接 `Create Channel Success`、无 `should between` 拒绝，说明本机硬编实拒阈确为 144×144、声明下限 176 更严；策略仍用声明下限（平台 API 唯一可移植来源，超出声明范围的尺寸按 API 定义不受支持），口径写进 `ProjectionScalePolicy` 的 Javadoc（`c10fed9`）并在此登记。
  - **2026-10-21 设备验证 r2 的三个仪表缺陷（I-1/I-2/I-3）修复**：
    - **I-1（P2）速率类指标口径错**：根因是 `MetricsSnapshot.perSecond()`/`bitrateBps()` 把**自启动累计**的计数器除以本周期（`PipelineMetrics.snapshot()` 从不清零），于是 `enc.auRate`/`enc.bitrate` 每 25 s 单调膨胀，实测打到 151 Mbps（真实码流 0.1–2 Mbps）。修法：新增 `core/metrics/MetricsRate`（一个已关闭区间的帧率/码率值，`hasInterval()` 区分「未测量」与「真的 0」）与 `core/metrics/RateSampler`（读者自持基线，用 scope 的 `NanoClock` 量**实测**间隔而不是名义周期）；`MetricsSnapshot` 只保留累计计数器并删掉两个派生方法，Javadoc 写死「计数器是累计值、`period=` 只是行框架」。服务端 5 s 行、观看端 5 s 行、1 s HUD 各自持有 sampler——同一 scope 被两个读者以不同节奏读取时，基线不能共享。`enc.auRate`/`enc.bitrate` 在没有测量区间时整字段省略；HUD 首行在无区间时打 `n/a` 而不是伪造 `0.0`。
    - **I-2（P2）观看端 HUD 首行恒 0**：根因是全仓只有 `StreamRuntime`（服务端）累加 `ACCESS_UNITS`/`ACCESS_UNIT_BYTES`，观看端从不累加。修法：`ClientMediaReceiver` 增加 `PipelineMetrics` 依赖，在 `ACCESS_UNIT` 分支用**已反序列化**的 `EncodedAccessUnit.getPayloadLength()` 计数（无新增时钟读取、无重复反序列化；关闭态只是一次 volatile 读）；观看端 5 s 行同时新增 `rx.auRate`/`rx.bitrate`（`ClientMetricNames`），HUD 首行与日志从此同源。
    - **I-3（P1，取证缺口）`tx.send`/`tx.qResidency`/`tx.qDepth` 不进服务端日志**：根因是 `StreamHub.join()` 给每个 `ClientSendSession` 发的是 `metrics.newScope()` 返回的**独立实例**，服务端 5 s 行只快照服务自己那份 scope。修法：新增 `core/stream/SessionMetricsSnapshot` 与 `StreamHub.snapshotSessionMetrics(periodMs)`（会话表在锁内拷贝、每个 scope 锁外读取，与既有 `snapshotSessions()` 同一模式），`ClientSendSession.snapshotMetrics()` 直接读自己的 scope；新增 `MetricsFormatter.toSessionLogLine()` 复用同一套键输出 `role=server period=5000 sid=… tx.send.p50/p95/max tx.qResidency.p95/max tx.qDepth.max tx.sent tx.resync`，服务端 5 s 循环为每个会话各打一行（无样本的会话跳过）。会话 scope 的 `SENT_UNITS`/`RESYNCS` 在既有计数点补写，`tx.drop` 仍无来源（丢弃数在队列内部、读取需进同步块，按热路径纪律本棒不加探针）。`sid` 口径抽到 `ClientSessionKey.tailOf()`，两端同一实现。
    - **验证**：新增 `RateSamplerTest`（差分口径、实测周期、零帧、无区间、关闭态、双读者独立基线）与 `AndroidMediaCodecEncoderBridgeTest`（Q-1 回退）；`MetricsFormatterTest` 覆盖速率省略/零速率/会话行/客户端速率，`StreamHubTest` 覆盖会话指标导出与排序，`ClientMediaReceiverTest` 覆盖计数与关闭态，`ClientSessionKeyTest` 覆盖 sid 尾截取；全量 **928 tests / 0 failures / 0 errors / 0 skipped**（82 个测试类），`:app:assembleDebug` 与 `:app:compileDebugAndroidTestJavaWithJavac` 成功。**设备复测未执行**：真机/模拟器上「速率读数是否回到 <2 Mbps 量级」「HUD 首行是否有数」「服务端日志是否出现 sid 行」三个点位留给验证棒。
  - **未完成（O7）**：基线执行、注入拥塞对照实验、基线报告与 [DV-001](decision-board.md) 裁决全部未做；本节所有指标都没有设备数值。**DV-001 全量基线待 Q-2（[TB-CLIENT-005](#tb-client-005)）落地后执行**：其代码已于 2026-10-21 落地（追赶 + `rx.catchup*` 指标），但设备未验证，因此基线必须等该条通过设备棒、并在 1080p 读数里同时记录 `rx.catchup`/`rx.catchup.lat`/`rx.converge.fail`，否则观看端积压会冒充传输结论。
- **边界与风险**：`enc.cap2out` 依赖「采集 PTS 与单调钟同基」的假设（已自校准 + 越界停用）；`rx.rowd` 依赖 30 s 滑动最小值抵消时钟差；TCP 上没有丢帧只有晚到，因此 DV-001 的判据是卡顿时长与频率而不是丢包率；`ServerStats` 的 `Parcel` 往返只能在设备上验证。
- **关联文档**：[本计划](superpowers/plans/2026-10-21-transport-observability.md)、[路线图阶段 0](optimization-roadmap.md)、[项目现状](project-assessment.md)、[DV-001](decision-board.md)、[DV-018](decision-board.md)

</details>

<a id="tb-fw-002"></a>
<details><summary>TB-FW-002 JPEG 适配（🔶 部分完成）</summary>

- **状态 / 优先级 / 创建 / 完成**：🔶 部分完成 / 🟠 P1 / 2026-10-17 / —
- **任务**：JPEG 适配：独立像素路径，只有完整帧可加入；慢客户端只保留最新完整帧；把现有 JPEG 路径接入框架。
- **交付物 / 验证证据**：已完成：`core/codec/jpeg` 的独立帧参考适配（完整性校验、完整帧才可加入、`KEEP_LATEST_FRAME`）、`JpegCodec` 静态注册与 JPEG 空配置端到端；批次 D `8a9c921` 新增 `FrameInputEncoder` 与 `StreamRuntime.submitFrame`，JPEG 回退经框架运行，Service/UI 参数贯通，旧 JPEG RPC 移除。验证：`StreamFrameworkEndToEndTest.jpegEmptyConfig_worksEndToEnd`、`JpegCodecTest`、`StreamRuntimeSubmitFrameTest` 通过，全量 JVM **396 tests / 0 failures / 0 errors / 0 skipped**。未完成：真实设备验证；JPEG 降级模式仍走 ImageReader→Bitmap→压缩（设计路径）。**不得宣称已完成设备验证或关闭问题 3。**
- **关联文档**：[编码框架设计](codec-framework.md)、[DB-CODEC-009](decision-board.md)

</details>

<a id="tb-sec-001"></a>
<details><summary>TB-SEC-001 配对与授权（🔶 部分完成 · 代码与 JVM 验证完成，设备验证待棒）</summary>

- **状态 / 优先级 / 创建 / 完成**：🔶 部分完成（代码 + JVM 用例完成，设备复测待棒）/ 🟠 P1 / 2026-10-17 / —（代码完成 2026-10-21）
- **任务（原始）**：配对、会话令牌与授权：默认拒绝未配对客户端；观看、音频、反控分级授权；发现默认不广播设备名。
- **与 TB-DISC-010 的边界**：传输加密与设备身份（SPKI 指纹 + pin）已由 [TB-DISC-010](#tb-disc-010) 提供；TOFU pin 只回答“是不是同一台机器”，**不回答“是否允许观看”**。本棒交付「默认拒绝 + 审批 + 持久授权 + 撤销」，不交付 PIN/扫码配对与会话令牌。
- **设计（派发方 2026-10-21 裁决）**：两层分离——身份由 TOFU 负责，许可由授权负责；未授权身份进入**待批准**而不是直接拒绝；审批超时（60 s）即拒绝；「总是允许」才落盘，「允许一次」仅当次；撤销即时断流；**已 pin 不等于已授权**（服务端静默 pin 行为保留、客户端 TOFU 流程不变）；开关默认**开**，关闭回旧行为并明示风险；音频沿用 D11「能看就能听」。计划见[实施计划](superpowers/plans/2026-10-21-sec-pairing.md)。
- **交付物**（提交 `7416f9f`、`8c3cf1d`、`3cc5ef8`、`a7840d2`）：
  - **授权集合与闸门**（`authorization` 包）：`AuthorizationEntry`（id + 指纹 + 名称 + 授权时间）、`AuthorizationStore`（独立文件 `lss-authorization`，坏文档按空表 fail-closed，写盘失败不影响内存）、`AuthorizationPolicy`（默认拒绝；只有「总是允许」且身份可验证才落盘；三个拒绝原因常量）、`AuthorizationGate`（待批准注册表 + 60 s 窗口 + 开关短路 + 取消）。
  - **服务端审批流**：`StreamCallHandler` 在 HELLO 与证书身份绑定之后、`hub.join` 之前进闸门；待批准期间**不进 `StreamHub`**（无会话、无帧、无音频），drain 轮询（≤5 ms）判定结果；拒绝 `authorization_denied`、超时 `authorization_timeout`、撤销 `authorization_revoked` 都以 `PERMISSION_DENIED + description` 结束。「允许一次」的会话不参与撤销复核。
  - **服务端 UI**：独立高优先级通道 `lss-approval` 的审批通知（标题「<设备名> 请求观看」，正文 id 短码 + 指纹短码 + 超时秒数，三键：允许一次 / 总是允许 / 拒绝，点正文进主界面）；主界面收到 `MSG_AUTHORIZATION_REQUEST` 弹同选项对话框；决定经广播（通知按钮）或 Messenger（对话框）回同一个 `AuthorizationGate.resolve`；任何结局都撤销通知与对话框。
  - **客户端**：`ServerMessage.authorization(PENDING)` 在解码线程之前分流，加载提示改为「等待对方批准（最多 N 秒）」；`Hello.device_name` 仅在启用「共享设备名」且存在别名时发送（**发现默认不广播设备名**维持不变）；`StreamFailure` 新增三种授权拒绝并各自给文案（拒绝 / 未在限时内批准 / 已撤销授权），按不可重试的终止处理。
  - **设置**：「观看前需经我批准」开关（默认开）+ 说明，关闭时文案改为风险提示；「已授权设备（N）」对话框列出名称 / id 短码 / 授权时间，逐条确认撤销并即时刷新计数。撤销只删授权集合，不动身份 pin。
  - **协议增量（纯增量）**：`Hello.device_name = 6`、`ServerMessage.authorization = 6`（`AuthorizationStatus{state, timeout_seconds}`）；旧服务端忽略 `device_name`（审批显示「未知设备」+ id 短码），旧客户端把 `authorization` 当 `PAYLOAD_NOT_SET` 忽略（只是等待更久）。不新增 RPC/端口/依赖。
- **JVM 证据**：`AuthorizationPolicyTest` 8、`AuthorizationStoreTest` 10、`AuthorizationGateTest` 13、`StreamCallHandlerAuthorizationTest` 10（挂起不进会话 / 允许一次不落盘 / 总是允许落盘 / 拒绝 / 超时 / 取消撤销提示 / 撤销断流 / 一次授权不受撤销影响 / 开关关闭直连 / 通知失败关闭调用）、`GrpcMediaTransportTest.sendApprovalPendingEmitsOneNoticeAndFailsAfterClose`、`FingerprintFormatterTest.theShortDisplayKeepsTwoGroups`、`StreamFailureTest` +2。全量 **1367 tests / 132 classes / 0 failures / 0 errors / 0 skipped**（基线 1322/128，+45 条 / +4 类，只增不减）。
- **构建证据**：`./gradlew :app:assembleDebug --offline` 通过；`:app:lintDebug` **0 errors / 146 warnings**（与基线同数；新增计数文案的 `PluralsCandidate` 已按 lint 口径改写）；`check_strings.py`（174 键双一致）、`check_cjk.py`、`check_docs.py` 通过。
- **设备判据（待验证棒）**：① 未授权设备请求观看 → 服务端 ≤1 s 出现审批通知（名称 + id 短码 + 指纹短码），客户端显示「等待对方批准」，`ServerStats` 会话数与 `lss.metrics` 的 `enc.*` 不因等待而变化；②「允许一次」→ 出画，断开重连再次审批；③「总是允许」→ 出画，重连不再审批，`shared_prefs/lss-authorization.xml` 出现该 id；④「拒绝」→ 客户端弹出「对方拒绝了这次观看请求」并结束会话（无重试入口）；⑤ 60 s 不操作 → 客户端弹出超时文案，服务端通知消失；⑥ 设置 →「已授权设备」撤销正在观看的设备 → 客户端 ≤1 s 内断流并显示「对方已撤销观看授权」（`logcat` 有 `reason = authorization_revoked`）；⑦ 关闭开关 → 新设备直接出画（旧行为），设置里出现风险说明，重新打开后既有授权仍生效；⑧ 客户端未开启「共享设备名」→ 审批显示「未知设备」+ id 短码。
- **已知取舍 / 遗留**：① **PIN/扫码配对与会话令牌未实施**（DV-022 仍维持人工比对口径，DV-026 登记审批体验参数）；② 反控（TB-CTRL-001）未实施，因此「观看/音频/反控分级授权」只剩观看一项落地，**音频按 D11 不单设许可**，不得写成「分级授权已完成」；③ 待批准期间占用一条 gRPC 流与一个 5 ms 轮询的 drain 线程（不占编码器与会话），最多 60 s；④ 会话进行中把开关从关改为开，未授权的当前观看者会按 `authorization_revoked` 断开并需重新批准（刻意保守，未单独设「即时生效」提示）；⑤ 明文调试开关下的无身份对端每次都只能「允许一次」（无可持久化的身份）；⑥ 「已授权设备」计数只在设置 sheet 显示时与撤销后刷新，不订阅存储变更。
- **关联文档**：[实施计划](superpowers/plans/2026-10-21-sec-pairing.md)、[决策 DB-SEC-001](decision-board.md#db-sec-001)、[DB-SEC-004](decision-board.md#db-sec-004)、[DV-026](decision-board.md)、[媒体架构安全模型](media-architecture.md)、[TB-DISC-010 实施计划](superpowers/plans/2026-10-21-tls-tofu.md)、[音频计划第 8 章](superpowers/plans/2026-10-21-audio.md)、[项目现状安全边界](project-assessment.md)

</details>

<a id="tb-win-001"></a>
<details><summary>TB-WIN-001 窗口切换（🔶 部分完成 · 功能冒烟通过，全项回归待统一棒 · 方案 B）</summary>

- **状态 / 优先级 / 创建 / 完成**：🔶 部分完成 / 🟠 P1 / 2026-10-17 / —（代码与修复已落地；⑨ 连切 20 次等长时项按 2026-10-21 裁决并入 [TB-ENG-013](#tb-eng-013)）
- **任务**：窗口切换：悬浮窗与全屏 `ViewerActivity` 之间 attach/detach 显示宿主，不重建连接；末帧快照 + 缩放过渡；Surface 切换按现有 `SurfaceRenderTarget`/重配置路径重建解码器恢复。
- **裁决依据**：本棒按用户 2026-10-20 提供的原始设计回答（“窗口化 ↔ 界面化”一节，外部参考未入库）执行**方案 B**（悬浮窗 ↔ 全屏 `ViewerActivity`，经末帧快照过渡），其余范围（PiP、方案 A 直接展开、分屏/freeform）不做。[DB-WIN-003](decision-board.md#db-win-003) 已同步改写。
- **交付物 / 验证证据**：
  - **B1（`8325a4b`，会话解耦）**：新增 `render/SwitchableRenderTarget`（会话持稳定目标、切换只换 delegate）与 `ClientMediaSession.rebindSurface()`（任意非关闭状态按最新 descriptor 重建解码器；新宿主无 Surface 时进入 `RESYNCING(MISSING_SURFACE)` 而不是结束会话）；新增 `ClientMediaSessionRebindTest` 6 条、`SwitchableRenderTargetTest` 6 条。
  - **B2（本批，宿主切换）**：`render/ViewerPanelView`（纹理 + 末帧快照层 + 调试叠加 + 内容变换，悬浮窗与 Activity 共用）、`render/ViewerContentTransform` 与 `render/ViewerTransitionGeometry`（纯几何，各 8 / 6 条用例）、`render/ViewerMenuFactory`（显示模式/显示大小菜单共用）、`render/ViewerHost`（宿主契约）、`ViewerActivity`（沉浸式全屏宿主，本地 Binder attach，`configChanges` 自行处理旋转，`OnBackPressedDispatcher` 收起）；`ScreenViewerService` 改为会话 + 两个宿主，新增 `ViewerBinder`、末帧快照、`attach/detachHost`、`removeOverlayIfTransitionFinished`（动画结束 + 首帧到达才移除悬浮窗）；悬浮窗双击菜单末行新增“展开全屏”，Activity 双击菜单末行新增“收起小窗”，控制条单点切换、含“停止观看”。
  - **验证**：`rm -rf app/build/test-results/testDebugUnitTest` 后全量 **1095 tests / 102 classes / 0 failures / 0 errors / 0 skipped**；`assembleDebug` 成功；`lintDebug` 无错误；`check_strings.py` / `check_cjk.py` / `check_docs.py` 通过；HEAD `0058593` 的 Build run [39556254140](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39556254140) 与 Checks run [39556254167](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39556254167) 均 success（W1 的 HEAD `8325a4b`：Build [39554587726](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39554587726)、Checks [39554587701](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39554587701)）。**设备验证见下条**。
- **未覆盖 / 遗留**：关窗仍结束会话（[DV-008](decision-board.md) 未裁决，本棒未改语义）；PiP 与方案 A 不做。
- **设备验证（r2，2026-10-21）**：上一轮 10 条点位被 P0 阻断（`ViewerActivity` 字段初始化器 `new GestureDetector(this, …)` 在构造期读 Context → 展开即进程 FATAL），修复后复验；下面两条按“裁决后”与“裁决前”分开记：
  - **裁决后的功能冒烟（2026-10-21 15:46–16:10，HEAD `bef6e21`）**：两机 `installDebug` → 出画 → 双击菜单「展开全屏」→ 全屏出画（`attachActivityHost`）→ 返回收起（`detachActivityHost`，悬浮窗恢复出画）→ 停止观看（服务 `onDestroy`，悬浮窗与前台通知消失，App 进程存活）。全程 `Unable to instantiate` 0、`FATAL EXCEPTION` 0、`ANR in com.obby…` 0；展开成功但慢（模拟器 `Displayed +8.8 s`）只触发 `onActivityAttachSlow` 诊断行，未产生误报。证据：`r2/smoke-functional.md`、`r2/shots/r2-01..17*.png`、`r2/logs/emu-{expand,collapse,stop}*.txt`。
  - **裁决前的一轮复验（15:18–16:10，读数只作参考）**：修复四个提交 `971b6c1`（`onCreate()` 创建手势检测器）、`dad69ae`（失败回落 + 双语提示 + 两条看门狗）、`42066ff`（契约用例 3 条）、`bef6e21`（禁令升级为[第 8 章](naming-conventions.md#8-android-组件构造禁令强制)）；`N-w1-2` 的“2000 ms 未 attach 即提示”在该机实测（成功切换需 4.3 s 才 attach）**误报**，改为 `ViewerActivity.onCreate()` 回送创建信号后取消看门狗。
  - **点位判定**：①✅（`attachActivityHost` 后立即抓到的帧是末帧快照，非黑屏；悬浮窗在首帧后移除）②✅（`detachActivityHost`，窗口 864×1872 恢复出画）③✅（Home 后窗口接管 8.1 fps；从最近任务回来重新 attach）④✅（横屏 2400×1080 同 token 不重建、比例正确）⑤✅（两宿主行集/顺序一致，仅首行百分比与末行动作不同；Activity 侧 60% 与调试开关生效）⑥✅（`onSessionEnded` → 服务 `onDestroy`，通知消失）⑦⚠️ **需手测**（`RECEIVER_NOT_EXPORTED` + 悬浮窗覆盖通知栏，`am broadcast` 无法代按）⑧✅ 对话框可用（点「确定」的重连链路未跑完）⑨⏸️ **延后**（裁决前跑过 20/20：`FATAL` 0、`ANR` 0、解码器错误 0，PSS 94.5→107.8 MB、线程 56→65；按 2026-10-21 裁决不作完成判据，归 [TB-ENG-013](#tb-eng-013) 复跑）⑩✅（Activity 宿主 `rx.catchup` 照常；60% 小窗圆角 6× 放大可见）。
  - **切换耗时（模拟器）**：点击 → attach 均值 3.37 s（1.8–5.4），attach → 悬浮窗移除均值 1.22 s（0.5–2.4），单次合计均值 4.58 s；该模拟器 `Displayed +2.8~5.1 s`、`Skipped 153 frames`，真机预期低一个量级（本轮无真机观看端）。
  - **新问题**：N-w2-1 创建信号依赖同进程（若将来拆 `:viewer` 进程需改 IPC）；N-w2-2 循环后出现瞬时 `grpc-okhttp-*` 线程尖峰（139–147 → 10 s 内回落 3，非泄漏）；N-w2-3 `removeOverlayIfTransitionFinished` 双次触发只造成日志重复；N-w2-4 悬浮窗层级高于最近任务/通知栏；N-w2-5 `uiautomator dump` 需先杀残留进程。报告：`device-test-2026-10-21-windowing/r2/report.md`（该报告的 `STATUS: PASS` 是裁决前口径，本条状态以裁决后口径为准：功能冒烟通过、全项归 [TB-ENG-013](#tb-eng-013)）。
- **设备验证点位（原始清单，①–⑩ 见上条判定）**：① 展开：200 ms 动画、无黑屏、首帧后移除悬浮窗；② 返回收起；③ Home 接管与回来重展开；④ 旋转；⑤ 双宿主菜单一致；⑥ 停止观看；⑦ 通知栏“停止查看”（**不可脚本化，需手测**：接收器 `RECEIVER_NOT_EXPORTED`，`am broadcast` 无效，通知栏又被悬浮窗覆盖）；⑧ 展开态重试对话框；⑨ 连续 20 次切换；⑩ API 25 设备（本轮按用户给的 10 条执行，未启用 API 25 镜像）。
- **关联文档**：[窗口与会话设计](windowing-session-design.md)、[路线图阶段 3](optimization-roadmap.md)

</details>

<a id="tb-audio-001"></a>
<details><summary>TB-AUDIO-001 音频链路（🔶 部分完成 · A1–A7 代码与 JVM 验证完成，设备验证待棒）</summary>

- **状态 / 优先级 / 创建 / 完成**：🔶 部分完成 / 🟡 P2 / 2026-10-17 / —（代码完成 2026-10-21；设备验证未执行）
- **任务**：音频：API 29+ 使用 `AudioPlaybackCapture`；API 25–28 默认无声（D1=不做麦克风）；AAC-LC 兼容优先；统一会话单调时钟与客户端 jitter buffer。
- **实施范围（A1–A7，按 [音频计划](superpowers/plans/2026-10-21-audio.md)）**：A1 音频契约与 proto 增量（`audio_descriptor=4`/`audio_access_unit=5`/`Capabilities.audio_codecs=2`）；A2 服务端内录 + AAC-LC 编码水线；A3 `AudioHub` 每客户端有界队列与同 RPC 传输；A4 客户端 AAC 解码、jitter buffer 与 `AudioTrack`；A5 `SessionClock` 音画同轴 + 漂移守卫；A6 开关/权限/通知/静音/HUD；A7 状态机与降级矩阵。**A7b 麦克风降级取消**（D1）。**不改 `StreamHub`、不新增 RPC/端口**。
- **验证证据（JVM）**：`rm -rf app/build/test-results/testDebugUnitTest` 后 `./gradlew :app:testDebugUnitTest --offline` **1236 tests / 120 classes / 0 failures / 0 errors**（基线 1098/103，只增不减）；`./gradlew :app:assembleDebug --offline` 通过；`check_strings.py`、`check_cjk.py`、`check_docs.py` 通过。协议兼容：旧客户端把新消息当 `PAYLOAD_NOT_SET` 忽略，新客户端对旧服务端永不收到音频。
- **设备验证修复（N1/N2/N4，2026-10-21，`6210a0b`）**：N1 客户端日志行补音频区块（`av.offset.p50/p95` + `audio.frames/dropped/underruns`），HUD 增第 4 行 `av … | af … | ad … | au …`（`MetricsFormatter`/`ClientMetricNames`）；N2 新增纯决策 `core/audio/AudioCapturePolicy`（`START`/`STOP`/`PAUSED`/`NONE`），`ScreenShareService` 以 1 s 观看者轮询执行「0 观看者停采、首个观看者恢复」，D9 文案 `audio_status_no_viewers` 首次可达且与行为一致；N4 新增 `AudioAvailability.forCaptureStart`（API 门槛 + 权限 + app-op 三合一）与 `AppOpsManager.unsafeCheckOpNoThrow` 只读检查。全量 **1248 tests / 121 classes / 0 failures / 0 errors / 0 skipped**；`assembleDebug` 与三项 checks 通过；设备快验见 [TB-AUDIO-003](#tb-audio-003)。
- **N6-1 修复（2026-10-21，`5799139`，`AV_OFFSET` 量纲 + 漂移守卫）**：`AudioSink.getPlayheadNs()` 返回音轨内纳秒而 `SessionClock.map()` 返回开机单调时钟，相减后 `AV_OFFSET` 恒为「−开机时长」（实测 76 289 206 ms），守卫因此每 2 s 补一帧静音。改为：采样侧 `getPresentedFrames(nowNs)` 回报帧计数（`AudioTrack.getTimestamp` 的 `(framePosition, nanoTime)` 外推到 `nowNs`，退回 `getPlaybackHeadPosition()`），会话侧 `可闻位置 = 最后写入缓冲区末端 Pts − 排队帧时长`（输出缓冲计入并抵消，修掉 N6-2），算术抽到纯 JVM `core/audio/AudioPlayhead`；阈值/冷却维持 40 ms / 2 s，但改为只对**偏移的变化量**动作（稳定偏差只上报，交给 A8 校准），并修正丢/补方向（前移→补、后移→丢）。全量 **1262 tests / 122 classes / 0 failures / 0 errors**；`assembleDebug` 与三项 checks 通过；设备快验见 [TB-AUDIO-003](#tb-audio-003)。
- **Build CI `NewApi` 修复（2026-10-21，`5d186c6`）**：`6210a0b` 用 `AudioAvailability.forCaptureStart` 取代 `56f6fa4` 的 `Build.VERSION.SDK_INT < Q` 提前返回，lint 看不到分类器内部的 SDK 判断，`:app:lintDebug` 重报 `ScreenShareService.java:1682` 的 `new PlaybackCaptureSource(...)`（minSdk 25／需 API 29），`f968b5f` 的 Build run 39586027806 因此在 `Build with Gradle` 失败。恢复显式门控（降级原因仍走 `forApiLevel`，权限与 app-op 判定不变）；1262/122/0/0/0 不变，`lintDebug` 0 errors / 146 warnings；Build run [39586878963](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39586878963) 与 Checks run [39586878970](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39586878970) 双绿。
- **未覆盖 / 遗留**：N3（观看端 `AudioTrack` 欠载，原报告 12.5%，本轮模拟器 `audio.underruns` 仍以 ≈1.7–4.6/s 增长）仍待真机 + 听感复核；N6-3（`pm grant` 后 uid-mode 仍为 `default` 时平台仍拒绝录音、应用落 `CAPTURE_FAILED`，仅 adb 可达）遗留；AV 固定偏差（本轮 160–447 ms）未做自动校正，归入 A8 校准与 D10 后续选项 B（重采样）。
- **设备验证**：见 [TB-AUDIO-003](#tb-audio-003) 与计划 6.10；本条目在设备点位通过前不转 ✅。
- **关联文档**：[音频计划](superpowers/plans/2026-10-21-audio.md)、[路线图阶段 4](optimization-roadmap.md)、[媒体架构音频](media-architecture.md)、[DB-AUDIO-001](decision-board.md#db-audio-001)

</details>

<a id="tb-audio-002"></a>
<details><summary>TB-AUDIO-002 麦克风降级（⏸ 待裁决 · 未激活）</summary>

- **状态 / 优先级 / 创建 / 完成**：⏸ 待裁决 / 🟡 P2 / 2026-10-21 / —
- **任务**：以显式音源用 `VOICE_COMMUNICATION` + `AcousticEchoCanceler` 采集环境声，持续可见指示，不伪装内录。
- **当前口径**：用户裁决 D1=**不做**，本条目保持未激活；API 25–28 维持纯无声 + UI 明示。若未来重新裁决为“做”，按[计划 6.9](superpowers/plans/2026-10-21-audio.md) 单开一批，包含 `FOREGROUND_SERVICE_MICROPHONE` 与 `microphone` 类型。
- **关联文档**：[音频计划 6.9](superpowers/plans/2026-10-21-audio.md)、[DB-AUDIO-001](decision-board.md#db-audio-001)

</details>

<a id="tb-audio-003"></a>
<details><summary>TB-AUDIO-003 音频设备验证清单（⏳ 等待设备验证）</summary>

- **状态 / 优先级 / 创建 / 完成**：⏳ 等待设备验证 / 🟡 P2 / 2026-10-21 / —
- **任务**：短小功能口径：能采、能传、能播、音画同步观感、降级路径（计划 6.10 的 1–3、5、7 项）。长时/压力/功耗/回声项并入 [TB-ENG-013](#tb-eng-013)。
- **前置**：设备验证解禁；一台 API 29+ 服务端与一台观看端。
- **判据**：开启后 5 s 内出声且通知显示采集；关闭/撤销投影后立即停止；拒绝录音权限时开关回弹且视频继续；API 28 设备开关置灰、无声音、无崩溃；观看端静音/取消静音不重启音频。
- **设备快验（N1/N2 修复后，2026-10-21）**：真机 API 29 服务端 + 模拟器 API 36 观看端，两机 `adb install -r`。① **N2 停采 ✅**：共享启动即 `audio: state = OFF, reason = NO_VIEWERS`、AudioFlinger 三条 Input thread `No active record clients`、通知副标题「当前没有观看者，音频采集已暂停。」；观看端断开后 `18:05:34.950 ACTIVE -> OFF`（录音轨随即拆除）；观看端回来 `18:06:38.997 OFF -> STARTING`、`18:06:39.327 STARTING -> ACTIVE`（≈0.52 s）。② **N1 输出 ✅**：`lss.metrics role=client` 行出现 `av.offset.p50/p95`、`audio.frames`、`audio.underruns`，HUD 第 4 行为 `av … ms (p95 …) | af … | ad … | au …`。③ **N4 ✅**：uid-mode `ignore` 时开关回弹为 `PERMISSION_DENIED`（1 ms 内，无 `playback capture record is not initialized`），`cmd appops set --uid … allow` 后恢复 `ACTIVE`。证据：handoff 目录 `device-test-2026-10-21-audio/fix-n1n2/`（`README.md`、`logs/`、`shots/`）。
- **N6-1 修复后快验（2026-10-21）**：同一套设备（真机 API 29 服务端 `:8080` + 模拟器 API 36 观看端），音频开关开、真机播放 40 s 节拍素材。① **量纲 ✅**：`av.offset.p50` 由 **76 289 206.9 ms** 降到 **160–168 ms**、`p95` **170–200 ms**（75 s 窗口，读数稳定不随时间增长；同期模拟器开机时长 76 409 s，已不再出现在读数里）。② **守卫不追固定偏差 ✅**：`audio.dropped = 0`，HUD `ad 0`；剩余偏差与观看端画面滞后同量级（`pres.late.p95 ≈ 580–640 ms`、`freeze.max ≈ 886 ms`、`rx.stall.max ≈ 872 ms`），按计划 6.6「漂移守卫（修订）」固定偏差只上报不追。③ **N1/N2 回归 ✅**：观看端加入后 `18:33:54.107 OFF -> STARTING`、`18:33:54.482 STARTING -> ACTIVE`（≈0.38 s，`capturing at 48000 Hz, 2 ch`）；观看端断开后 `18:35:16.692 ACTIVE -> OFF` + `syncAudioCapture: audio capture paused, no viewer is connected`，AudioFlinger `No active record clients`；HUD 第 4 行 `av 168.2 ms (p95 201.0) | af 3764 | ad 0 | au 157`，第 5 行 `声音: 接收中`。证据：handoff 目录 `device-test-2026-10-21-audio/fix-n6/`（`README.md`、`logs/`、`shots/`）。
- **脉冲杂音根因（2026-10-21，handoff `device-test-2026-10-21-dual/audio-noise/report.md`）**：真机 238 s（A=YAL-AL10/API 29 共享屏幕+共享设备声音播 `loop30.wav`，B=ALN-AL00/API 31 悬浮窗观看）双端 logcat + `dumpsys media.audio_flinger`。听感「每 ~0.4 s 一次咔」= **应用层欠载补静音**：`audio.underruns` 2.60 次/s（平均 0.38 s 一次，48 个 5 s 窗口区间 1.6–8.0 次/s）；底层是平台侧 **6.03 次/s** `tallyUnderrunFrames`（缺口中位 704 帧=14.7 ms、累计 7.9% 墙钟、`set last volume to zero because underrun` 6.20 次/s），轨道 `Server FrmCnt=3072` 而 `FrmRdy` 只在 128–2048 帧锯齿、从未填满。触发源是**视频**不是网络：应用级欠载与 `pres.drop` **r=0.94**、与 `dec.merged` r=0.65、与 `rx.arr.p95` **r=-0.14**；服务端干净（46.9 帧/s、`dropped=0`）。代码根因：`plan()` 第三参数传的是累计收帧数（水位数成死代码 + 潜伏的灾难性丢帧路径）、写线程 20 ms 轮询且入队不 `notify`、音频与 1080p60 视频同一条 `lss-client-decode` 线程（`dec.lat.p50≈18.2 ms` vs 16.7 ms 预算）、轨道 64 ms + `PERFORMANCE_MODE_LOW_LATENCY`、欠载只补一帧且静音门限 40 ms 大于补的量。**排除项**：漂移守卫（丢帧方向 `audio.dropped` 全程 0、`av.offset.p50` 恒定 26.9–29.5 ms）、jitter buffer 丢帧（同为 0）、AAC 帧边界/重采样（帧率精确 46.9/s，2.6 Hz 与 46.9 Hz 无关）。
- **脉冲杂音修复（代码棒，2026-10-21，`76a3be4`/`5343d07`/`c364d9d`/`6ced8e0`/`a520545`/`4cfb75f`）**：① **深度模型**：`AudioJitterBuffer.plan()` 改收微秒级「已写媒体时间 − 已呈现时间 + 待写帧」（`ClientAudioSession.bufferedUsLocked()` 用 `AudioSink.getPresentedFrames()` 实测），TARGET/MAX/RECOVER 按 **120/250/150 ms** 生效，预填到轨道真有 120 ms 才 `play()`，预填等待上限 1500 ms（只对低于实时的发送方可达）；平台报不出播放位置时按「一帧都没呈现」处理（最多高估一个轨道缓冲）。② **入队唤醒**：`enqueueLocked()` 末尾 `notifyAll`，写线程空队列时无限 `wait()`，仅「欠载计时中」保留 20 ms 短 wait；空队列判定与 `wait()` 同临界区。③ **音频离开视频线程**：新增 `lss-client-audio` 单线程（`THREAD_PRIORITY_AUDIO`，队列 32 帧≈680 ms），`AUDIO_DESCRIPTOR`/`AUDIO_ACCESS_UNIT` 直接投递，满队列丢弃计 `audio.dropped`，`stop()` 同批 `shutdownNow()`；音频不参与 catch-up。④ **轨道**：`BUFFER_MS` 64→128、去低时延模式、`open()` 打实际 `bufferFrames/bufferMs`、按 `getUnderrunCount()` 1 s 限流上报平台欠载增量。⑤ **欠载恢复**：按实测缺口向上取整补 N 帧静音（≤100 ms），缺口 >100 ms（`RESTART_GAP_MS`）改为 `pause` + 清队列 + `buffer.reset()` 重新锚定；`UNDERRUN_QUIET_MS` 40→20 ms。⑥ **可观测**：`AUDIO_UNDERRUNS` 拆成 `audio.starved`/`audio.driftInsert`/`audio.driftDrop`（`audio.dropped` 只表示队列丢帧），客户端日志行在音轨收帧后连 0 值一起打印，HUD `au`→`as`；新增纯 JVM `core/audio/AudioLog`（`info`/`warn`，默认 `NONE`），`ClientAudioSession`/`AudioJitterBuffer` 上报开播、欠载（gap/filled/pts/pending/written/suppressed，1 s 限流）、长缺口重启、漂移补/丢、PTS 缺口、丢帧，`ScreenViewerService` 装 `Log.i/Log.w` 适配器。JVM 单测 **1283 tests / 124 classes / 0 failures / 0 errors / 0 skipped**（基线 1275/124）；新增/改写：jitter buffer 深度与长会话不丢帧、轨道内排队计入深度、「帧数不是深度」、64 ms 缺口补 4 帧、长缺口重启、空闲写线程 `WAITING`、断供 100 ms 后写出帧数超过收帧数。
- **给验证棒的判据（同一场景 240 s，A 共享屏幕+设备声音、B 悬浮窗观看）**：① `lss.metrics role=client` 的 **`audio.starved` < 0.2 次/s**（本轮基线 2.60 次/s）；② 平台 **`tallyUnderrunFrames` 事件 < 1 次/s**（基线 6.03 次/s），且应用侧 `AudioTrackSink` 的 `track underrun: +N` 新日志与之同量级；③ `dumpsys media.audio_flinger` 的 **`FrmRdy` 中位 > 2048 帧**（基线 2.7–42 ms 锯齿）；④ **`av.offset.p50` 仍 < 40 ms**（回归 N6-1，基线 26.9–29.5 ms）；⑤ **听感无咔/噗**（40 s 以上连续试听，`画面私密` 开启时截图取不到 HUD，取证只能靠 logcat）；⑥ 旁证：`audio.dropped`/`audio.driftInsert`/`audio.driftDrop` 接近 0，`playback started, buffered = N ms` 的 N ≈ 120，首帧出声延迟允许 +120 ms。**若 ③ 未达标但 ① 达标**，说明应用侧已不再断供、剩余欠载来自平台调度，按 N5 登记为华为 EMUI 下的放大因素，不要继续加缓冲。
- **水位与口径修复（代码棒，2026-10-21，R1–R3；来源 handoff `device-test-2026-10-21-dual/audio-fix-verify/report.md`）**：上一轮真机复验 6 ✅ / 3 ❌，残留两项都指向「缓冲水位与测量口径」。① **R1 水位与阈值撞车**：实测稳态深度 = 轨道 128 ms + 预填 ~106 ms ≈ **234–248 ms**，而 `MAX_DEPTH_MS=250` 只高几毫秒，任何视频突发都会顶破并按 `RECOVER_DEPTH_MS=150` 一次砍 6 帧 —— 就是用户听到的「每 13–18 s 一次 128 ms 跳音」（`audio.dropped` 0.294 次/s、`dropping 6 buffered frames, depth = 251–269 ms`）。改为 **`MAX_DEPTH_MS 250→400`、`RECOVER_DEPTH_MS 150→250`**（400 ms 比稳态高约 150 ms，250 ms 落在稳态上沿，排空后不会来回震荡；**不再加缓冲**，预填目标仍 120 ms），并把丢弃改成**渐进**：新增 `MAX_DROP_FRAMES_PER_PLAN=2`，单次最多丢 2 帧（≤43 ms，听不出来），日志改为 `dropping N buffered frames, excess = M frames, depth = D ms`；丢弃速率（≈94 帧/s）仍远高于实时的 46.9 帧/s，900 ms 的积压十几帧就排空。② **R2 `av.offset` 口径**：`AudioPlayhead.audiblePtsUs()` 增加 `pendingFrames` 入参，可闻位置同时扣掉「轨道内未播帧」与「等写线程的帧」（`ClientAudioSession.pendingSamplesLocked()` 供数，深度计算复用同一函数），深预填不再被算成固定偏置（上一轮 `av.offset.p50` 70.0/91.4 ms，基线 26.9–29.5 ms）；`av.offset` 的语义因此明确为「可闻位置 − 时间轴应有位置」，两侧都不含未播媒体。③ **R3 重连瞬态**：`play()` 时重锚漂移基准（`reanchorDriftBaselineLocked()`：`referenceOffsetUs` 清空 + 检查时刻复位），新音频会话在已被视频锚定的时间线上重播时，预填期的测量（轨道尚未播放，全体已写帧都算"未呈现"）不再被当作基准 —— 上一轮 `drift guard dropped one frame, offset = -185 ms` 正是守卫去纠这个预填跳变。新增/改写 JVM 用例：`theSteadyStateDepthOfAHealthySessionNeverDrops`（稳态 234–277 ms 连跑 5000 帧不丢）、`aDeepBacklogIsDrainedTwoFramesAtATime`、`anOverflowDrainsTowardsTheRecoverDepthWithoutCuttingIntoIt`、`theDepthBoundsLeaveRoomForTheTrackAndThePrefill`、`audiblePositionAlsoSubtractsTheMediaWaitingForTheWriter`、`audiblePositionIgnoresANegativeQueue`、`mediaWaitingForTheWriterIsNotAudibleYet`、`playbackStartReanchorsTheDriftBaseline`；漂移用例按「基准在播放开始时才建立」重排循环（2 s 冷却因此推后一拍）。提交 `0b0f8cb`；全量 **1322 tests / 128 classes / 0 failures / 0 errors / 0 skipped**（基线 1315/128，只增不减）。
- **下一轮设备判据（R1–R3 后，同一 240 s 场景）**：① **`audio.dropped` 事件率 ≈ 0**（上一轮 0.294 次/s、每 13–18 s 一次 128 ms；日志形态应是 `excess = N frames` 的渐进丢弃，正常情况下压根不出现）；② **`av.offset.p50` 回到 ~30 ms 量级**（上一轮 70.0 ms 静态 / 91.4 ms 60 fps 运动；口径修正后 pending 预填不再计入偏置）；③ **重连后不再出现 `drift guard dropped one frame`**（上一轮 `offset = -185 ms` 一次）；④ 沿用：`audio.starved` < 0.2 次/s、平台欠载 0、`FrmRdy` > 2048 帧、`playback started, buffered ≈ 120 ms`、无明显咔/噗与跳音；⑤ 新增旁证：若仍见 `dropping N buffered frames`，读 `excess` 判断是「水位真的过高」还是「轨道与预填算重了」，不要再动阈值。
- **未覆盖 / 遗留**：① **听感未验**（N3）：模拟器观看端 `audio.underruns` 仍以 ≈1.7–4.6/s 增长（`au 157`/75 s），是本机的音频欠载而非守卫补帧，真机听感仍待复核；② **AV 固定偏差未自动校正**（本轮 160–447 ms，随会话与观看端负载变化）：一帧媒体消不掉固定偏差，计划口径是先上报、由 A8 校准与 D10 选项 B（轻微重采样）处理；③ **N6-3 遗留**：`pm grant` 后 uid-mode 仍为 `default` 时平台 `AudioPolicyService` 依旧拒绝录音，应用仍落 `CAPTURE_FAILED`（仅 adb 可达）；④ 试听/听感与真机双边（真机↔真机）同步未做。
- **关联文档**：[音频计划 6.10](superpowers/plans/2026-10-21-audio.md)、[DB-AUDIO-001](decision-board.md#db-audio-001)

</details>

<a id="tb-client-001"></a>
<details><summary>TB-CLIENT-001 客户端画质与多客户端（📋 待办）</summary>

- **状态 / 优先级 / 创建 / 完成**：📋 待办 / 🟡 P2 / 2026-10-17 / —
- **任务**：客户端画质与多客户端：分辨率、帧率、码率档位；`configEpoch` 与 IDR 刷新；一次编码按客户端扇出；每客户端有界队列与统计；慢客户端隔离。
- **交付物 / 验证证据**：交付：档位协商、扇出队列、客户端统计。验证：阶段 3 的画质、多客户端、慢客户端项通过；当前未执行。
- **关联文档**：[路线图阶段 3](optimization-roadmap.md)、[媒体架构多客户端](media-architecture.md)

</details>

<a id="tb-client-002"></a>
<details><summary>TB-CLIENT-002 单流与多档流策略（📋 待办）</summary>

- **状态 / 优先级 / 创建 / 完成**：📋 待办 / 🟡 P2 / 2026-10-17 / —
- **任务**：兼容单流与多档流策略：默认共同能力交集；多档流让高能力客户端用高规格、其他客户端保留 H.264 档；受编码器实例、功耗与热限制；新客户端不得迫使已有客户端无提示降级；小窗/全屏可选不同档位。
- **交付物 / 验证证据**：交付：选流策略、档位切换与可见回退、多客户端影响评估。验证：多客户端组合下无静默降级；档位切换 configEpoch/IDR 正确；编码器实例与功耗在预算内；无实测收益时保持单流；当前未执行。
- **关联文档**：[媒体架构兼容单流与多档编码](media-architecture.md)、[决策 DB-MULTI-004](decision-board.md)、[路线图阶段 3](optimization-roadmap.md)

</details>

<a id="tb-client-005"></a>
<details><summary>TB-CLIENT-005 观看端解码队列背压与追赶（✅ 2026-10-21，详情见已完成条目）</summary>

本条目已实现解码追赶（catch-up）并完成 JVM 验证；交付物、指标与设备点位见「✅ 已完成条目」中的同名块。设备实测不在本棒。

</details>

<a id="tb-client-006"></a>
<details><summary>TB-CLIENT-006 小窗 HUD 折行与字号自适应（📋 待办 · 设备验证 Q-3 登记）</summary>

- **状态 / 优先级 / 创建 / 完成**：📋 待办 / ⚪ P3 / 2026-10-21 / —
- **任务**：观看窗缩到 630×1364（显示大小 60%）时，`SurfaceShareViewer` 的 HUD（`11sp` 等宽字体、`WRAP_CONTENT`、内边距 12/8）第三行 `rowd +… | stall … | drop … | kf …` 超出窗口宽度，TextView 自行折行，三行变五行。做**字号按窗口宽度自适应**（或等价的换行策略），保证窗口 ≥60% 时仍是固定三行。
- **交付物 / 验证证据**：交付：HUD 文本尺寸/布局随窗口宽度的确定性规则（例如可用宽度不足时在 8–11sp 之间降字号，仍不足再退化为允许折行），以及「行数 = 3」的判定方式。验证：在 1080p 与 60% 小窗两档下截图对照，三行完整可读、不遮挡单击/双击/拖拽手势，关闭态仍不刷新。
- **已观察实例**：`device-test-2026-10-21-metrics-r2/report.md` §7（Q-3），证据 `shots/o6-hud-smallwindow-60.png`。
- **2026-10-21 新增一行**：HUD 增加第 4 行音频指标（`av … ms (p95 …) | af … | ad … | au …`，见 [TB-AUDIO-001](#tb-audio-001)），60% 小窗下该行同样受本条目约束；`av.offset` 读数异常偏大时（见 [TB-AUDIO-003](#tb-audio-003) N6）这一行会先折行，修 N6 前不要用截图判定「行数」。
- **边界**：纯呈现问题，不影响指标数值与媒体路径；因需要肉眼对照截图，应排在一次设备棒里做，不与媒体路径改动混批。
- **关联文档**：[TB-OBS-001](#tb-obs-001)、[本计划](superpowers/plans/2026-10-21-transport-observability.md) 6.6

</details>

<a id="tb-ctrl-001"></a>
<details><summary>TB-CTRL-001 单客户端反控（📋 待办）</summary>

- **状态 / 优先级 / 创建 / 完成**：📋 待办 / 🟡 P2 / 2026-10-17 / —
- **任务**：单客户端反控：`AccessibilityService.dispatchGesture`；默认关闭、用户手动开启；单客户端租约、心跳、epoch fencing；归一化坐标；敏感界面不注入。
- **交付物 / 验证证据**：交付：反控授权、租约、坐标与可见指示。验证：阶段 4 的反控授权、租约、坐标、安全项通过；当前未执行。
- **关联文档**：[路线图阶段 4](optimization-roadmap.md)、[媒体架构反控](media-architecture.md)

</details>

<a id="tb-eng-001"></a>
<details><summary>TB-ENG-001 测试与发布工程（📋 待办）</summary>

- **状态 / 优先级 / 创建 / 完成**：📋 待办 / 🟡 P2 / 2026-10-17 / —
- **任务**：测试与发布工程：补齐状态机、停止幂等、队列、协议、重连与坐标测试；仪器与压力测试；R8/keep 规则；CI 增加独立测试步骤。
- **交付物 / 验证证据**：交付：测试用例、CI 配置、混淆规则。验证：测试可重复运行并阻断回归；当前仅模板测试，未执行新增测试。
- **关联文档**：[项目现状工程审查](project-assessment.md)、[路线图阶段 1](optimization-roadmap.md)

</details>

<a id="tb-eng-002"></a>
<details><summary>TB-ENG-002 发布签名加固（📋 待办）</summary>

- **状态 / 优先级 / 创建 / 完成**：📋 待办 / 🟡 P2 / 2026-10-17 / —
- **任务**：正式发布签名加固：把示例签名回退与正式签名隔离；使用私有 keystore + 本地 `signing.properties` 或 CI secrets；明确保管、备份、轮换与升级一致性；验证同 `applicationId` 的覆盖安装和不同签名的失败提示。
- **交付物 / 验证证据**：交付：签名配置说明、本地/CI 发布流程检查、升级安装验证记录。验证：正式包只由私有签名构建；仓库不含真实材料；同签名可覆盖安装，不同签名失败时明确告知用户卸载会清数据；当前未执行。
- **关联文档**：[启动与签名检查清单](startup-checklist.md)、[项目现状安全边界](project-assessment.md)

</details>

<a id="tb-eng-013"></a>
<details><summary>TB-ENG-013 统一回归：稳定性/长时测试清单（📋 待办 · 功能全部完成后执行）</summary>

- **状态 / 优先级 / 创建 / 完成**：📋 待办 / 🟡 P2 / 2026-10-21 / —
- **来源**：2026-10-21 用户裁决——**功能做完只做对应的短小功能测试；稳定性/长时/耐力类测试一律先登记看板，统一到全部功能完成后回归执行**。该条已写入两份交接提示词 §1（`2026-10-21-quality-metrics-tls-done.md`、`2026-10-20-discovery-refactor-done.md` 的「测试分层」）。本条目是被延后测试的唯一收口处：任何一棒都不再为这些项单独排设备时间，也不把它们写成“已通过”或“未通过”。
- **待执行清单（按依赖排序；每项写明来源与阻塞条件）**：
  1. **TB-WIN-001 全项 10 点位**（来源 [TB-WIN-001](#tb-win-001) 点位清单）：①–⑧、⑩ 随统一棒复跑；**⑨ 连续切换 20 次**属长时类，须与 5 分钟窗口一起跑，逐次记录 `attach`/`detach` 时刻、`FATAL`/`ANR` 计数、`PSS`、线程数与解码器错误；⑦ 通知栏“停止查看”仍需人手点（`RECEIVER_NOT_EXPORTED`）。**阻塞：无**（功能已冒烟通过）。
  2. **Q-2 1080p 观看端终验**（来源 [TB-CLIENT-005](#tb-client-005) 的追赶策略）：1080p30 下 `rx.catchup`/`rx.catchup.shed`/`rx.catchup.lat`/`rx.converge.fail` 与 `dec.lat`/`pres.drop` 的收敛行为、停帧恢复时延。**阻塞：需要第二台真机**——模拟器软解 + QEMU NAT 排队会把解码端积压混进传输结论。
  3. **DV-001 全量基线**（来源 [DV-001](decision-board.md)、[TB-OBS-001](#tb-obs-001) O7）：同设备同网络的 TCP 基线（`rtt.p50/p95`、`rx.arr.p95/p99`、`rx.rowd.p95`、`rx.stall`、`pres.late.p95`、`dec.deadlineMiss`、`tx.send.p95`、`enc.cap2out.p95`）与 5 分钟窗口，读数必须同时带 `rx.catchup*`。**阻塞：功能冻结（避免基线口径随代码漂移）+ 仪表读数稳定**。
  4. **N1 过载掉线排查**（来源 `device-test-2026-10-21-tls-t6/report.md` §5.4）：该轮未复现自发掉线，但 `rx.stall.max=34.7 s`、`rtt.lost=317`、1 次 `DECODE_FAILURE` 重同步说明触发条件仍在。统一棒用可控过载（限速/弱网）复现并按同一采样口径取证。**阻塞：需可复现的弱网注入手段**。
  5. **N2 线程尖峰**（来源同上）：观看端 `threads` 56→359→526→215、服务端 671，均出现在会话重建/编码器重启瞬间；**“尖峰是否回落、是否泄漏”仍未回答**。统一棒在尖峰时刻抓 `dumpsys meminfo` 与线程名快照。**阻塞：无**（需与 ⑨ 的循环同一轮做）。
  6. **TLS D9 30 min 功耗 / D10 备份恢复**（来源 [TB-DISC-010](#tb-disc-010) 与 `2026-10-21-tls-tofu.md` §9.7）：D9 只做了短版（35 min 1080p30：电池 36→39 ℃、`thermal=0`），需补 30 min 全量；D10 备份/恢复（导出→重装→恢复→同 id/指纹与信任库一致）未测。**阻塞：D10 需明确备份格式与范围**（是否含 keystore 私钥）。
  7. **API 25 出画兼容性**（来源 `N-r2-5`）：API 25 模拟器软解 `0x80001019` 导致握手正常、有数据无画面；**阻塞：需要 API 25 真机或带硬解镜像**，否则只能验证“握手 + 有数据”，出画判据不闭环。
  8. **音频长时/压力项**（来源 [TB-AUDIO-003](#tb-audio-003) 与[音频计划 6.10](superpowers/plans/2026-10-21-audio.md) 的 A8-4/5/6/8）：10 分钟漂移量（`AV_OFFSET` P50/P95）、3 台观看端同时播放与慢客户端音频丢弃、同一 TCP 上音频被视频大帧阻塞的可感知度（`tx.send.p95` 与主观卡顿）、开关音频各 30 分钟的电池/温度/CPU 对比。**阻塞：音频功能设备冒烟通过后**；判据为 `|Δ|` 观感 ≤100 ms、无视频回归。
- **执行口径**：一次统一棒只装一个已推送 HEAD、仓库零改动；每项记录取数窗口、宿主负载与 guest `top`；不得把“没测到”写成“没问题”；运行前后偏好/输入法/方向逐字节还原；不得放宽判据，判据不可达时须写明机制理由。
- **完成判据**：以上 7 项均有可核对证据（截图/日志/读数）并按[维护方式](#-维护方式)写回各自条目；阻塞未解除的项保持 📋 待办并注明阻塞原因。
- **关联文档**：[TB-WIN-001](#tb-win-001)、[TB-OBS-001](#tb-obs-001)、[TB-CLIENT-005](#tb-client-005)、[TB-DISC-010](#tb-disc-010)、[DV-001](decision-board.md)、[路线图](optimization-roadmap.md)

</details>

<a id="tb-fw-007"></a>
<details><summary>TB-FW-007 HEVC 扩展验证（📋 待办）</summary>

- **状态 / 优先级 / 创建 / 完成**：📋 待办 / 🟡 P2 / 2026-10-17 / —
- **任务**：HEVC 扩展验证：复用 `MediaCodec` 骨架，只接受明确安全 IDR；CRA/BLA/RASL/RADL 不作为可加入点。
- **交付物 / 验证证据**：交付：HEVC 描述符、工厂、策略与验证记录。验证：新增 HEVC 不改公共层；CRA/RASL 不被接受；当前未实施。
- **关联文档**：[编码框架设计](codec-framework.md)、[DB-CODEC-013](decision-board.md)、[DB-CODEC-009](decision-board.md)

</details>

<a id="tb-fw-008"></a>
<details><summary>TB-FW-008 框架测试与证据（📋 待办）</summary>

- **状态 / 优先级 / 创建 / 完成**：📋 待办 / 🟡 P2 / 2026-10-17 / —
- **任务**：编码框架测试与证据：契约测试、可加入点、epoch 语义、队列溢出、切换、传输适配与回退；补充设备与网络验证记录。
- **交付物 / 验证证据**：已具备 JVM 证据：`StreamFrameworkEndToEndTest`、`StreamHubTest`、`StreamRuntimeTest`、`ClientSendSessionTest`、`ClientMediaSessionTest`、`JpegCodecTest` 等 13 类 155 tests / 0 failures；H.264 最终集中修复（`9cb8af9`）后全量 364 tests / 0 failures，覆盖 H.264 JVM E2E，`:app:assembleDebug` 与 `:app:assembleDebugAndroidTest` 成功（androidTest 未运行），Build CI 通过；批次 A–H 后全量 **396 tests / 0 failures**（新增 32），覆盖协议映射、服务端接线、客户端接收、能力选档与 deferred 切换，各批 Build CI success；设备与网络验证记录仍未执行，因此本任务仍未完成。
- **关联文档**：[编码框架设计](codec-framework.md)、[路线图阶段 2](optimization-roadmap.md)、[路线图阶段 4](optimization-roadmap.md)

</details>

### ✅ 已完成条目

<a id="tb-disc-010"></a>
<details><summary>TB-DISC-010 传输加密与身份 pinning（✅ 2026-10-21）</summary>

- **状态 / 优先级 / 创建 / 完成**：✅ 已完成 / 🟠 P1（由 P2 上调）/ 2026-10-20 / 2026-10-21
- **任务**：自签 TLS + TOFU 指纹确认；Register 与媒体流加密；与 [TB-SEC-001](#tb-sec-001) 协同。九项用户裁决与落地口径见计划第 13 章；`Descriptor.secure` 保持私密屏幕语义，传输能力只写入 `DeviceInfo.secure`/`auth`（计划 2.3）。
- **交付物 / 验证证据**：
  - 交付：身份密钥自签证书（SPKI 即身份，证书只是容器）、自定义 `X509ExtendedKeyManager`/`X509ExtendedTrustManager`、pin 存储与三态判定、mTLS 与 HELLO/Register 交叉校验、TOFU 首次确认/变更告警/忘记信任、`secure`/`auth` 公告与明文拒绝。
  - 计划：`docs/superpowers/plans/2026-10-21-tls-tofu.md`（`6d747f3`）。
  - 批次：T1 `0f6e96c`、`9adb207`；T2 `c7cb676`、`503663e`；T3 `7995bcb`；T5 `02261d5`（先于 T4 合入，避免调用尚未实现的签名）；T4 `a2f43b5`、`afa4a9e`；`e5fc715` 修正一处既有的手动探测用例竞态并消除 Lombok 告警。
  - JVM：`./gradlew :app:testDebugUnitTest --offline` = **1041 tests / 95 classes / 0 failures / 0 errors / 0 skipped**；`TlsHandshakeEndToEndTest` 用 OkHttp server/channel 在 loopback 跑真实 TLS 握手，覆盖 pin 正确/缺失/不匹配/id 不一致/无客户端证书/缺 `overrideAuthority` 六类；`:app:assembleDebug --offline` 通过；各批 Build 与 Checks CI 均 success（最终 HEAD 的 Build run [39537903021](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39537903021)）。
  - **2026-10-21 T6 设备报告缺陷修复（N-t6-1/2/3）**：设备报告把 TB-DISC-010 的全部用户可见能力判为「设备上一次都没真正发生」，根因与修复如下；JVM 全量升至 **1055 tests / 97 classes / 0 failures / 0 errors / 0 skipped**（新增 `IdentityKeyPolicyTest` 6 例、`ServerStartCoordinatorTest` 8 例）。
    - **N-t6-1（P0，阻断）keystore 私钥无法签 TLS 摘要**：根因是身份密钥只授权 `setDigests(SHA256)`，而 Conscrypt 对非原生 EC 私钥走 `CryptoUpcalls` → `Signature("NONEwithECDSA")`（raw digest，digest id 0），keymaster 在 `begin()` 以 `INCOMPATIBLE_DIGEST` 拒绝（API 29 `-65530`、API 36 `Incompatible digest`），双向、双 API 全败。修复：新增纯 JVM `identity/IdentityKeyPolicy`（授权集 `{SHA-256, NONE}` + raw-digest 探针 + 迁移判据），`AndroidKeystoreKeyStore` 以该授权集生成密钥；读到已有密钥时先探测它能否签 raw digest，不能签的一次性替换。**兼容策略**：授权集生成后不可扩展，旧密钥只能重建；旧密钥在 T6 报告里从未完成过任何一次握手，因此不存在已被 pin 的指纹，重建只改变本机 id/指纹（收藏需重新确认，与既有 `reset()` 语义一致），触发点是进程内首次身份加载而不是随机时刻。**并发纪律**：探针按 SPKI 指纹同进程记忆、密钥加载进程内串行、失败重试一次（100 ms）；首版「每次加载都探测」在扫描的 20 个线程并发下触发 keystore「操作重叠」并把密钥连锁删建（日志可见 20 条 `replacing it` 与 3 次 `del USRPKEY`），该缺陷已在本批修掉。
    - **N-t6-1 连带（D4/D7 不可达的第二个原因）**：`discovery/register/GrpcRegisterStub` 的 TLS 通道没挂身份 `keyManager`，而服务端 `clientAuth=REQUIRE`，手动添加与扫描探测必然以 `PEER_DID_NOT_RETURN_A_CERTIFICATE` 失败并被上层报成 `UNAVAILABLE`；补挂后探测成功、设备行出现，D4 首次确认框才可达。
    - **N-t6-2（P1）主线程 keystore/服务启动卡 5.4 s 触发 ANR**：修复是把 `ScreenShareServer.start()` 移到单线程 `lss-server-control`，主线程只保留 `startForeground`、`MediaProjection`、虚拟显示与悬浮条；新增纯 JVM `service/ServerStartCoordinator`（`IDLE/STARTING/RUNNING` + 代次令牌）裁定「谁停服务器」——`STARTING` 期间到达的 stop 由启动任务在 `start()` 返回后自行停止（既不重复 stop 也不漏 stop），启动未完成时到达的 `Register`/`stream` 一律拒绝并记日志，`serverInfo` 回调改为回主线程投递，可见启动顺序与同步启动一致。
    - **N-t6-3（P3）重置身份后通知栏指纹不刷新**：`ACTION_IDENTITY_RESET` 广播在 `restartAnnouncements()` 之后补 `updateNotification()`。
    - **设备实证（真机 YAL-AL10 / API 29 服务端 → emulator-5554 / API 36 观看端；两机 logcat 中 `INCOMPATIBLE_DIGEST` 与 `Socket failed to handshake` 计数均为 0）**：① 迁移：用旧构建生成的密钥装新构建后 `isUsableForTls: the stored key cannot sign a raw digest, replacing it` → `keystore: del USRPKEY_lss-device-identity` → `device identity changed`，再次冷启动只有 `device identity loaded`（不重复替换）；② D1：TLS 1.3 双向握手成功（`transportReady … securityLevel=PRIVACY_AND_INTEGRITY`），观看端 `mClientState=STREAMING`、`mOutboundDataRate≈23 KB/s` 并显示对方屏幕，服务端 `1 个连接 | 23.26 KB/s`；③ D4：首次确认框显示完整 32 字节指纹，与服务端通知 `比对指纹：36841B44 … 4C5BB31F` 逐组一致；④ N-t6-3：重置前后 `dumpsys notification` 的 `android.subText` 由 `… 36841B44 …` 变为 `… 7053BB23 …`，与设置页 `device_fingerprint_setting` 同步，未重启共享；⑤ N-t6-2：`onStartCommand`（main）后 79 ms 内 `Registration.register` 已落在 `lss-server-cont` 线程、`startAnnouncements` 回到 main，最终构建上「停止后重启共享」与「重置身份后启动」各一次，`Davey!`/`ANR in` 均为 0；本批 Build run [39543356831](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39543356831) 与 Checks run [39543356815](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39543356815) 均 success。
  - **2026-10-21 T6 复测报告（r2）缺陷修复（N-r2-1/2/3）**：r2 报告把 D5 判为「行为正确、失败路径不可观测」并给出「D5 判据不可达」的机制结论，三项处理如下；JVM 全量升至 **1069 tests / 98 classes / 0 failures / 0 errors / 0 skipped**（新增 `StreamFailureTest` 9 例，`ManualAddressProviderTest` +3 例，`TlsHandshakeEndToEndTest` +2 例）；`./gradlew :app:assembleDebug --offline` 通过，`check_strings`/`check_cjk`/`check_docs` 三件套通过；本批 Build run [39550288577](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39550288577) 与 Checks run [39550288575](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39550288575) 均 success。
    - **N-r2-1（P2，可观测性）客户端对信任失败完全静默**：根因是 `ScreenShareClient.onError` 丢掉 `Throwable`（即既有 R5-01：客户端不区分终止原因），`ClientObserver` 只暴露无参 `onDisconnected()`，UI 因此只能弹通用的「无法连接…是否重试？」。修复：新增纯 JVM `client/StreamFailure`，把终止原因归一化为一条记录——`Status.fromThrowable` 的 code/description，加上原因链匹配（`PinningTrustManager` 的 `identity_mismatch`/`identity_changed`/`pin_unknown`/`invalid_chain` 由 trust manager 以 `CertificateException` 抛出，只出现在原因链里；服务端拒绝则写在 description 里），其它原因保留服务端 description、缺失时退回小写 status code；`onError`/`onCompleted` 各记一条结构化日志（`reason/status/description/cause`）；`ClientObserver.onDisconnected(StreamFailure)` 透传到 `ScreenViewerService`：身份类拒绝弹「设备身份校验失败…」专属文案（中英双语、只有「确定」并结束会话，不再提供重试），普通断开维持「已断开/无法连接，是否重连」。服务端既有 reason 透传链经 JVM 真实 TLS 握手验证（`ABORTED` + description 原样到达客户端并被解析为 `getReason()`）。**设备冒烟（emulator-5554 观看端 → 真机 API 29 服务端，重置真机身份后点旧行）**：弹出专属框（标题为设备名、正文「设备身份校验失败，连接已被拒绝。…」、唯一「确定」），logcat `onError: stream terminated, reason = identity_mismatch, status = UNAVAILABLE, cause = StatusRuntimeException <- SSLHandshakeException <- CertificateException: identity_mismatch` 与 `onDisconnected: session ended, identityRejected = true`；同一轮里真机停止共享仍是「已断开…是否重新连接？」（`identityRejected = false, reason = Cancelled`）；本地主动停止改为 `mIsStopped` volatile 后不再产生虚假 W 日志（计数 0）。证据：T6 复测交接目录 `fix-nr2/`（`logcat-final.txt`、`07-identity-rejected-final2.png`、`README.md`）。
    - **N-r2-3（P3）冷启动首个 TLS 探针偶发 `DEADLINE_EXCEEDED` 且静默**：① 判定 2 s 的首探针超时偏短（冷启动要加载 AndroidKeyStore 身份密钥、初始化 TLS 栈，且 TLS 比明文多一个握手往返），新增 `ScanConstants.MANUAL_PROBE_FIRST_TIMEOUT_MS = 5 s`（Javadoc 注明理由），`ManualAddressProvider` 在「本进程尚无一次探测被应答」前使用宽超时、确认一次后回到 2 s 快速失败；② 失败可观测：`RegisterAddressProbe` 对**非扫描**通道（手动/收藏）的失败记 `Log.w`（地址、通道、reason、超时值、耗时；扫描逐地址失败不记，避免刷屏），`DiscoveryManager.probeManualAddress` 的失败日志补 `elapsed`；③ 找到并修掉一条真正的静默路径——`ManualAddressProvider.probeManual` 的探测任务若抛 `RuntimeException`（探测实现违约），回调永不触发，既无 toast 也无日志，现捕获后回调 `probe_failed`；UI 对 `DEADLINE_EXCEEDED`/`timeout` 给出专属 toast「无法连接该地址：设备在超时前没有应答」。
    - **N-r2-2（P2，文档契约）D5 判据不可达**：`device_id` 与指纹同源（`id = f(SPKI)`），重置身份必然换 id，「同 id、旧/新指纹并列」不可能出现。计划 9.7 的 D5 改写为实测语义（旧行连接被拒且有日志/专属文案 → 刷新后同地址新设备 + 「该地址上次是另一台设备」提示 → 不自动信任 → 显式信任后恢复），D10、6.6 与 6.8 同步；[DV-022](decision-board.md) 补注。
    - **顺带登记（不修）**：**N-r2-5（P2，环境）** API 25 模拟器软解 `0x80001019` 导致出画失败（`OMX.google.h264.decoder` 限制，握手正常、有数据无画面），「API 25 出画」判据仍未闭环，需 API 25 真机或带硬解镜像；**`insecure_transport` 分支对合法客户端不可达**（只在调用方于 TLS 通道上自报 `secure=false` 时产生，明文客户端只会拿到 `UNAVAILABLE`）：让它可达需要服务端在 TLS 握手失败后回落到明文通道再返回结构化拒绝，等于把「明文可信」带回主路径，收益（客户端已能用 `UNAVAILABLE` + 日志读出现状）低于风险，登记为已知限制，维持「不降级明文」。
  - 边界：D1 已有设备证据；API 25（TLS 1.2）、D4 之后的首连流程在真实网络下的完整复测、D5 观看端半程、D6 同二层伪造公告、D7 TLS 扫描预算、D8 性能 A/B、D10 备份恢复仍未验证（计划 9.7 D1–D10），完整 T6 复测由下一轮设备棒执行；TOFU 不是配对，是否允许观看仍归 [TB-SEC-001](#tb-sec-001)。
- **关联文档**：[实施计划](superpowers/plans/2026-10-21-tls-tofu.md)、[TB-SEC-001](#tb-sec-001)、[TB-DISC-012](#tb-disc-012)、[决策看板](decision-board.md)

</details>

<a id="tb-client-005"></a>
<details><summary>TB-CLIENT-005 观看端解码队列背压与追赶（✅ 2026-10-21）</summary>

- **状态 / 优先级 / 创建 / 完成**：✅ 已完成 / 🟡 P2 / 2026-10-21 / 2026-10-21
- **任务**：1080p 30 fps 观看时解码跟不上，`ScreenShareClient` 的 `LinkedBlockingQueue(256)` 只做「满则丢弃新消息」，已入队的帧不会作废，画面可长期滞后 20–26 s（`rx.qWait.p50` 12–22 s、HUD `q 256`、`rx.shed` 数千；断开重连后立刻回到 0.3–0.6 ms，说明不清空队列就永远追不上）。要求：解码积压时丢弃到最新可解码点（等 IDR/join point）、队列水位驱动的追赶状态机，以及可观测手段。
- **交付物 / 验证证据**：交付（`acde892` 策略与 flush 原语、`f86f758` 队列接线与指标）：
  - **检测**：`core/client/CatchUpPolicy` 以解码线程解码每条消息时已有的两个量作为输入——该条的排队等待时间（同一个值记入 `rx.qWait`）与解码队列深度；等待 ≥ 1 s **或**深度 ≥ 64，且**持续 2 s** 才判定积压（单帧抖动不触发）。阈值全部是公开常量，Javadoc 标注「待设备校准」。
  - **触发**：追赶代次 +1 → 已入队 AU 全部作废（不反序列化、不进解码器，计入 `rx.catchup.shed`；描述符例外，永不丢，否则会话会等一个再也收不到的 epoch）→ `ClientMediaSession.onCatchUp()` flush 解码器并转 `RESYNCING`（新原因 `CATCH_UP`）→ 主线程发关键帧请求。此后只接受经 `H264CodecPolicy.isJoinPoint` 校验的 IDR，P 帧进不了解码器，恢复从完整图像开始。
  - **防抖 / 升级**：两次追赶至少间隔 5 s；连续 3 次仍不收敛 → 计 `rx.converge.fail` 并停 30 s（流畅优先，不无限追）；追赶 3 s 内等不到 join point 自动释放（避免一次关键帧丢失锁死机制）；队列清零满 5 s 计为收敛并清零连续计数。**不自动降服务端参数**：单流扇出会影响所有观看者，降档属 [DV-012](decision-board.md) 范围。
  - **可观测**：`rx.catchup`、`rx.catchup.shed`、`rx.catchup.lat.p50/p95`（追赶开始到 join point，即收敛时间）、`rx.catchup.why=qWait|qDepth`、`rx.converge.fail`；HUD 第三行追加 `cu` 计数；logcat 有 `startCatchUp` / `finishCatchUp` / `reportConvergenceFailure` 三条结构化行；会话重同步仍走既有 `RESYNCS` 与原因行。
  - **顺带收口**：`Decoder` / `MediaCodecDecoderBridge` 各加 `flush()`（H.264 清 codec 缓冲、待测码表与时间轴锚点并回到“只认 IDR”，JPEG 无操作）；媒体线程不再直接写 gRPC `StreamObserver`（关键帧请求统一 post 到主线程），队列满路径原有的跨线程 `onNext` 一并收口；`requestKeyframe()` 计入既有 `KEYFRAME_REQUESTS`，HUD 的 `kf` 从此有数。
- **验证**：`CatchUpPolicyTest` 12 例（阈值 / 持续 / 冷却 / 升级与保持 / 超时 / 代次判定）、`ClientMediaSessionCatchUpTest` 3 例（flush 一次、P 帧被拒、IDR 恢复、无解码器返回 false）、`H264DecoderTest` 新增 2 例（flush 后只认 IDR、未 configure 不触碰桥接）、`MetricsFormatterTest` 断言新键与 HUD 形状；全量 **946 tests / 0 failures / 0 errors / 0 skipped**（84 个测试类；基线 928 / 82），`assembleDebug` 成功。**设备实测未执行**，点位见下。
- **给验证棒的点位**：① 1080p 30 fps 连播 3 min：`rx.qWait.p50` 应在数百毫秒内、`rx.qDepth` 不再长期贴 256、`rx.catchup` 有值且 `rx.catchup.shed` 只在追赶瞬间跳变；② 同步抓帧对照滞后 < 1 s，断开重连语义不变；③ 每次 `startCatchUp` 后允许一次短黑/短暂花屏候选，但若 IDR 之后仍持续花屏即为关键帧边界缺陷；④ 若 3 min 内出现 `rx.converge.fail`，结合 `rx.catchup.lat` 与此时 `dec.lat` 判断“解码能力确实低于码流”——这是 [DV-012](decision-board.md) 降档的判据，不是本策略失败；⑤ 干净链路（720p 或低码率）连播时 `rx.catchup` 必须为 0，`rx.seqGap`、`pres.drop` 不因策略上升。
- **边界与风险**：追赶把“持续滞后”换成一次可见的短停顿/黑帧，这是 H.264 不能丢中间 P 帧的必然代价；作废的 AU 会让 `rx.seqGap` 同步增加（与既有本地 shed 同源），将来按 `rx.seqGap − rx.catchup.shed` 读“真实丢帧”；阈值与冷却全部未经设备校准；不收敛时选择“继续播放旧滞后画面”而非反复重置，30 s 后重试一次。
- **关联文档**：[TB-OBS-001](#tb-obs-001)、[媒体架构](media-architecture.md)、[决策 DV-001](decision-board.md)、[决策 DB-OBS-002](decision-board.md)

</details>

<a id="tb-obs-002"></a>
<details><summary>TB-OBS-002 RTT 探针（Ping/Pong）与估计器（✅ 2026-10-21）</summary>

- **状态 / 优先级 / 创建 / 完成**：✅ 已完成 / 🟠 P1 / 2026-10-21 / 2026-10-21
- **任务**：补上 [DV-001](decision-board.md) 判据里唯一缺失的量：应用层往返时延。gRPC 不向应用暴露 HTTP/2 PING 的 RTT，因此新增一对最小协议消息，客户端每秒发一次探针，服务端原样回显，客户端据本地时间戳算往返。
- **交付物 / 验证证据**：交付（O5 `1ac3a9c`）：
  - proto 向后兼容增量：`ClientMessage.ping = 4`、`ServerMessage.pong = 3`，消息体只有 `seq` 与回显的 `client_uptime_ms`；不回传服务端时间，因此不需要时钟同步。旧对端把未知 oneof 字段当作 `PAYLOAD_NOT_SET` 忽略，两端退化为「没有 RTT 样本」而不报错。
  - `server/PongTarget`（窄回调，`NO_OP` 默认）避免扩宽 `MediaTransport`；`GrpcMediaTransport.sendPong` 与媒体发送共用 `sendLock` 且**不经过** `ClientSendSession` 队列，测到的是路径而不是队列。
  - `StreamCallHandler` 在处理器监视器外回显，关闭后不再应答；`core/metrics/RttEstimator` 维护容量 8 的发送时刻表、2 s 超时判丢、1/8 权重的 EWMA，输出 `rtt.p50/p95/ewma/lost` 与 `rtt.kind=ping_pong`。
  - `ScreenShareClient` 每秒一次探针（估算 < 40 B/s），回包在 gRPC 回调线程直接喂估计器，不进解码队列。
- **验证**：`RttEstimatorTest`（匹配、去重、超时、EWMA、序号回绕、关闭态零样本）、`StreamCallHandlerTest`（回显不改变 attach 状态、关闭后静默）、`ScreenStreamProtoMapperTest`（Ping/Pong 线格式往返、未知 payload 仍被忽略）、`GrpcMediaTransportTest`（回显字段与关闭态静默）；全量 **899 tests / 0 failures**，Build 与 Checks CI success。设备实测未执行：真实 RTT 数值、丢样率与「是否 1 s 足够灵敏」都还没有数据。
- **关联文档**：[本计划](superpowers/plans/2026-10-21-transport-observability.md) 5.6、8.6；[DV-001](decision-board.md)

</details>

<a id="tb-eng-007"></a>
<details><summary>TB-ENG-007 客户端发送统计并入服务端统计（✅ 2026-10-21，已并入 TB-OBS-001 O3）</summary>

- **状态 / 优先级 / 创建 / 完成**：✅ 已完成 / 🟡 P2 / 2026-10-19 / 2026-10-21
- **任务**：`ClientSendSession.getStats()` 产出队列深度、丢弃与重同步计数，但没有出口。按 [本计划](superpowers/plans/2026-10-21-transport-observability.md) 8.4 的决定，本条目并入 TB-OBS-001 的 O3 一起做，避免重复实现。
- **交付物 / 验证证据**：交付（O3 `8b4c72e`）：
  - `server/ServerStatsMapper` 纯函数把「每地址字节」与「每会话快照」合成一行；`ServerStats.TransportStats` 增加客户端块（clientId、状态、队列深度/字节、丢弃、重同步、已发送、发送 p95），`writeToParcel`/读取构造与比较器同步更新。
  - `StreamCallHandler` 接收并暴露调用来源地址，`ScreenShareServer` 维护存活调用列表做会话↔地址关联并在采集时剪除已关闭调用。
  - `MainActivity` 摘要增加一行「观看者 · 队列 · 丢弃 · 重同步」，`strings` 同步 `values-zh`。
  - 同一地址多会话时字节只落在地址首行，其余会话行不编造分配；地址缺失的会话单独成行。
- **验证**：`ServerStatsMapperTest`（零/单/双会话、地址缺失、字节归属、排序、速率）、`ServerStatsTest`（字段与 Parcel 顺序护栏）；全量 JVM 通过，Build 与 Checks CI success。**未做设备实测**：`Parcel` 真机往返与界面刷新仍待设备棒（JVM 单测环境为 mockable android.jar，`Parcel.obtain()` 直接抛异常，无法在此验证）。
- **关联文档**：[本计划](superpowers/plans/2026-10-21-transport-observability.md) 8.4；[TB-OBS-001](#tb-obs-001)

</details>

<a id="tb-client-003"></a>
<details><summary>TB-CLIENT-003 查看器宽高比与缩放质量（✅ 2026-10-20）</summary>

- **状态 / 优先级 / 创建 / 完成**：✅ 已完成 / 🟠 P1 / 2026-10-20 / 2026-10-20
- **任务**：修 R1/R5/R2：客户端窗口尺寸改为由 `StreamDescriptor` 的可见尺寸与旋转推导，保持内容宽高比；服务端一旦下发非 0 旋转也要正确显示；并在悬浮窗上提供“适应窗口 / 原始大小(1:1) / 整数缩放”三档，让“缩小就发毛、想看清文字”有可操作入口。范围是本地渲染几何与档位，不做 GL 管线、不做编码协商。
- **交付物 / 验证证据**：交付（Q1 `6ef878c`、Q2 `f554de4`、Q3 `2e1dbe8`）：
  - `render/ViewerFitPolicy`（纯 JVM 决策）+ `render/ZoomMode`：输入可见尺寸、旋转、可用显示区、观看比例、最小尺寸与档位，输出窗口像素、生效比例、`snapped`/`clamped`/`fallback`；旋转 90/270 交换内容宽高，比例钳到 1–100%，最小尺寸按同一比例放大两边，非法输入回退“bounds × 比例”的旧行为。
  - `client/StreamGeometryListener` + `ClientMediaReceiver` 4 参构造：DESCRIPTOR 分支先回调可见尺寸/旋转，再交给会话应用；`ScreenViewerService` 把回调切到主线程后交给 `SurfaceShareViewer`。
  - `SurfaceShareViewer`：窗口尺寸改用 fit policy；90/270 时对纹理施加“先按交换比例缩放、再旋转”的变换，180 只旋转；配置变化（旋转、多窗口）后重新拟合；位置钳制与圆角半径随新尺寸重算；双击打开三档缩放菜单，1:1 在内容超出显示区时按 bounds 等比钳制并在菜单文案标注。
  - `Preferences.KEY_VIEWER_ZOOM_MODE`：档位与观看比例分开存，空值即适应窗口；`ZoomMode.wire()/fromWire()` 稳定解析，未知值回退适应窗口。
  - 验证：`ViewerFitPolicyTest` 22 条（同尺寸恒等 1080×2340→864×1872、横屏码流进竖屏 bounds → 864×399、旋转互换、bounds 小于内容、1:1 钳制、整数档 80%→1、60%→1/2、33%→1/3、等距向上、最小尺寸、非法输入回退、比例误差 ≤1 px 的遍历断言）、`ZoomModeTest` 5 条、`ClientMediaReceiverTest` 追加 5 条几何回调（顺序、旋转、epoch 重复回调、AU 不回调、关闭后不回调）；全量 **726 tests / 0 failures / 0 errors / 0 skipped**（62 个测试类，较 694 基线 +32）；`:app:testDebugUnitTest --offline`、`:app:assembleDebug --offline` 与三件套检查通过；Q1 Build [39447109279](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39447109279)/Checks [39447109129](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39447109129)、Q2 Build [39447532109](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39447532109)/Checks [39447531974](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39447531974) success；Q3 的 Checks [39448619331](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39448619331) success，其 Build run 被下一次推送按并发策略取消，Q3 代码由 Q4 的 Build [39448720986](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39448720986) 覆盖通过。
  - 边界：未做设备实测；纹理旋转矩阵、跨朝向/跨机型组合的比例与锯齿观感、1:1 与整数档的实际清晰度均需真机复核（同型号同朝向组合原本只有约 2.5% 比例差，不能据此判定 R1 不存在）。
  - **2026-10-21 真机复测（缺陷 Q-02/Q-04/Q-06）**：几何策略本身通过（竖屏窗口 840×1819 vs 内容比例误差 0.05%，横屏 864×399 误差 0.06%，齿轮为正圆、无拉伸）。但有三个新问题：
    ① **Q-02（P1）会话中分辨率变化后查看器黑屏窄带**：真机竖屏→横屏后编码器重建成功、窗口已变 864×399，画面却是黑底 + 左下约 330×40 的压扁画面，等待 17 s 不变，断开重连立即正常。根因：观看侧 `SurfaceRenderTarget` 全程复用同一个 `SurfaceTexture`，全仓库没有 `setDefaultBufferSize`，纹理缓冲尺寸停留在创建时的视图尺寸，新分辨率的帧仍按旧缓冲几何呈现；`updateContentTransform` 只处理旋转。修复（提交 `ad15408`）：`RenderTarget.setContentSize(codedWidth, codedHeight)` 新契约，`ClientMediaSession` 在关闭旧解码器之后、创建新解码器之前上报（顺序必要：旧解码器必须先退出几何才能改），`SurfaceRenderTarget` 转成 `SurfaceTexture.setDefaultBufferSize` 并记住请求值以便新纹理重新施加；服务端同时在旋转/改缩放时 `resize` VirtualDisplay，避免以旧分辨率合成再被编码面拉伸。回归：`ClientMediaSessionSurfaceTest` 两条（新 epoch 换尺寸按新 coded 尺寸上报且早于新解码器创建、同尺寸新 epoch 如实再报）。纹理侧无法在 JVM 验证，**仍需设备复测确认画面完整填充**。
    ② **Q-04（P3）缩放菜单档位重复**：INTEGER 档在默认 80% 比例吸附到 1，与“原始大小”同为 1050×2274，而菜单只写“1/2、1/3、1/4”，无比例提示。修复（提交 `b516af8`）：新增纯 JVM `render/ViewerZoomMenuModel` 投影菜单行，每行标注实际比例（适应窗口 78%、原始大小 97% 并保留“超出屏幕已缩放”说明），整数档与原始大小同尺寸时不再列出并把等尺寸行标为当前档位；文案改为四种带百分比标签。测试 `ViewerZoomMenuModelTest` 9 条。
    ③ **Q-06（测试手法/体验）拖拽需先原地长按**：`GestureDetector.onLongPress` 要求先按住 ~500 ms，普通滑动拖不动。修复（提交 `b516af8`）：触摸移动超过平台 touch slop 即进入拖拽，长按仍可用；点按/双击位移小于阈值，断开确认与缩放菜单不受影响。
    ④ **Q-08（P2）缩放菜单没有可见的当前档位**：`setSingleChoiceItems` 的 `checked` 索引正确（无障碍树 `checked="true"` 落在当前行），但当前主题的单选 item 不渲染指示器，界面上看不到勾选，用户无法判断当前是哪一档；对照上一轮截图同样如此，不是本轮回归。修复（提交 `3fb9d9e`）：当前档位行文案前置勾选标记（`viewer_menu_current` = `✓ %1$s`，中英双语同形），`checked` 语义与无障碍树保留；不改主题、不自定义 item 布局。复测点位：任一比例下双击窗口，当前档行应以「✓」开头，切换后再开菜单勾选应随档位移动。
    ⑤ **Q-09（P3）观看比例没有 UI 入口**：带「不透明度 / 显示大小 / 窗口圆角」的 `ScreenShareViewer` 没有任何实例化点（死代码），用户无法在界面上改 `viewer_scale`，本轮「60% 观看比例」只能用 `run-as` 改偏好复现。修复（提交 `3fb9d9e`）：双击菜单末行新增「显示大小（当前 %）」，进入子菜单提供 60% / 80% / 100% 三档；当前值不是预设时按升序插入（如 75% 落在 60% 与 80% 之间），当前行同样带勾选标记——不把相邻预设假装成当前值。选中即写 `viewer_scale` 并在原地重拟合窗口，接的是实际使用的 `SurfaceShareViewer`；`ScreenShareViewer` 不复活，仍留 [TB-ENG-006](#tb-eng-006)。档位投影抽成纯 JVM `render/ViewerScaleMenuModel`（升序、去重、钳到 1–100、当前行标记），`ViewerScaleMenuModelTest` 6 条。复测点位：60% / 80% / 100% 各选一次，窗口尺寸按几何重算（如 60% 下窗口应小于 80%），再次打开菜单当前档带勾选；另可用 75% 验证非预设值的插入行。
  - 复测点位：旋转竖→横、横→竖各一次（窗口尺寸、画面完整填充、无黑边、无重连），缩放菜单在 80%/60%/33% 三档下逐档查看比例与去重，`input draganddrop` 与普通 `input swipe` 均可拖动。
  - Q-08/Q-09 的 CI 与测试：`ViewerScaleMenuModelTest` 6 条，全量 **790 tests / 0 failures**（70 个测试类）；Build [39462172014](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39462172014)/Checks [39462172116](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39462172116) success（`f30249f`）。
- **关联文档**：[实施计划](superpowers/plans/2026-10-21-viewer-quality-and-settings.md)、[决策 DB-RENDER-001](decision-board.md#db-render-001)、[决策 DB-RENDER-002](decision-board.md#db-render-002)

</details>

<a id="tb-client-004"></a>
<details><summary>TB-CLIENT-004 客户端呈现调度与关键帧恢复（✅ 2026-10-20）</summary>

- **状态 / 优先级 / 创建 / 完成**：✅ 已完成 / 🟡 P2 / 2026-10-20 / 2026-10-20
- **任务**：修 R4：解码输出不再“到达即显示”，改为按时间戳呈现、只保留最新帧、过期帧不追赶；已经发生的丢弃（解码器输入预算、解码队列溢出、会话重同步）必须转成“请求关键帧”，把损坏窗口从“等最多一个 IDR 周期”缩短到“一次往返”。不丢编码帧。
- **交付物 / 验证证据**：交付（`b501df5`）：
  - `core/render/PresentationPacer`（纯 JVM）：`PRESENT_NOW / SCHEDULE_AT / DROP`，规则为 PTS 回退判过期、最小间隔限帧（0 = 不限）、未来时间戳交给平台调度、时钟回退视为新基准。
  - `MediaCodecDecoderBridge` 契约拆分：`dequeueOutput` 不再隐式渲染，返回输出索引；新增 `present(index, renderTimestampNs)` 与 `discard(index)`；`DecodedOutput` 增加 `bufferIndex`/`hasBuffer()`；Android 实现对应 `releaseOutputBuffer(index, timestampNs|false)`。
  - `H264Decoder`：每次 `decode` 末尾取尽可用输出，只呈现最新、其余丢弃；EOS 先释放缓冲再失败；呈现/丢弃失败统一收敛到 `DecoderException`；首帧锚定流时间戳到单调时钟；输入预算丢帧时调用 `KeyFrameRequester`。
  - `KeyFrameRequester` + `H264Codec/H264DecoderFactory` 重载把回调随注册带入解码器，会话无需依赖客户端端点。
  - `client/KeyFrameRequestThrottle`（1 s 冷却，线程安全）：`ScreenShareClient` 队列溢出与 `ScreenViewerService` 的解码丢帧/`RESYNCING` 三条路径都经它调用 `requestKeyframe()`。
  - 验证：`PresentationPacerTest` 10 条、`KeyFrameRequestThrottleTest` 5 条、`H264DecoderTest` 追加 6 条（多输出只呈现最新、格式变更忽略、EOS 释放后失败、PTS 回退丢弃、输入预算丢帧请求关键帧、释放无泄漏）、`MediaCodecBridgeContractTest` 追加 present/discard 释放与重复释放契约；全量 **747 tests / 0 failures / 0 errors / 0 skipped**（64 个测试类，较 726 +21）；`:app:testDebugUnitTest --offline`、`:app:assembleDebug --offline` 与三件套检查通过；Build [39448720986](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39448720986)/Checks [39448721211](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39448721211) success。
  - 边界：本地限帧默认关闭（最小间隔 0），收益未实测；`releaseOutputBuffer(index, timestampNs)` 的真实调度行为、丢帧后到新 IDR 的恢复时延、弱网下的花屏时长均需真机验证。
- **关联文档**：[实施计划](superpowers/plans/2026-10-21-viewer-quality-and-settings.md)、[决策 DB-RENDER-003](decision-board.md#db-render-003)

</details>

<a id="tb-eng-005"></a>
<details><summary>TB-ENG-005 新视频设置界面（✅ 2026-10-20）</summary>

- **状态 / 优先级 / 创建 / 完成**：✅ 已完成 / 🟡 P2 / 2026-10-19 / 2026-10-20
- **任务**（本轮改写范围）：把码率与关键帧间隔补上设置 UI 并贯通到 `StreamParameters`；编码格式在本轮只读展示，可切换能力依赖 [TB-ENG-006](#tb-eng-006)。原条目“码率、IDR 间隔与编码模式三个偏好…需补设置 UI 并贯通到 `StreamParameters` 与 codec 选择”里的编码模式切换被有意收窄。
- **交付物 / 验证证据**：交付（`6f8d744`）：新增 `res/layout/widget_screen_share_video_settings.xml`（画质、画面大小、帧率、码率、关键帧间隔与只读编码格式，顶部重建提示，帧率 >60 需设备支持的说明）；`MainActivity.createVideoSettingsDialog()`，服务端设置面板移除已迁移的三个控件并改为带当前值摘要（`video_settings_summary`）的“视频编码设置”入口行，滑块松手才落盘；`settings/ProjectionSettings`（纯 JVM）收敛取值：码率钳到 100 kbps–50 Mbps、帧率 1–120、IDR 1000–30000 ms 且向上取整到秒、比例 5–100%、质量 0–100、编码格式非法回退 H.264，并提供 `toStreamParameters()`；`ScreenShareService` 启动与热应用均改用该快照。验证：`ProjectionSettingsTest` 10 条、`StreamRuntimeTest` 追加“同尺寸换 epoch 后新参数进入新编码器”；全量 **758 tests / 0 failures / 0 errors / 0 skipped**（65 个测试类，较 747 +11）；`:app:testDebugUnitTest --offline`、`:app:assembleDebug --offline` 与 `check_strings.py`（97 键）/`check_cjk.py`/`check_docs.py` 通过；Build [39449413331](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39449413331)/Checks [39449413522](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39449413522) success。
  - 断点证据（原条目缺少的三条生效通路）：帧率观察者原本只写 `mProjectionFrameRate` 且该字段只被 JPEG 采样循环使用；IDR 间隔原本只在码率键变化时被顺带打包，单独改不触发任何动作；编码格式观察者原本没有分支，且客户端能力固定为 H.264。
  - 边界：编码格式切换仍未交付（缺 JPEG 客户端查看器）；滑块范围、菜单可点区域与只读行的可读性未做真机复核；`KEY_PROJECTION_QUALITY` 在 H.264 下不再显示控件，但键与默认值保留。
  - **2026-10-21 真机复测（缺陷 Q-05，P3）**：入口行摘要只在视频 sheet 关闭时刷新，用户在 sheet 内改完看不到当前值；摘要字段（编码 · 帧率 · 码率）本身与实测一致，未含关键帧间隔与画面大小属设计取舍。修复（提交 `b516af8`）：`MainActivity` 在 `onStart/onStop` 订阅 `Preferences`，投影相关键落盘即刷新摘要（切主线程），sheet 关闭时保留一次兜底刷新。复测点位：sheet 内拖动帧率与码率后关闭，入口行应已是新值，无需二次打开。
  - **2026-10-21 设备复测 r3（缺陷 Q-10，P3）失败回写不刷新已打开的滑杆**：编码器拒绝尺寸时服务端回写 `projection_scale`，Toast 正确，但开着的 sheet 滑杆仍停在用户拖到的位置，关掉重开才归位。根因：sheet 的控件值只在 `setOnShowListener` 里写一次，`Preferences` 观察者只刷新入口行摘要，回写事件到不了滑杆。修复：`MainActivity` 持有 sheet 各控件的引用，投影键变化时在主线程把控件同步到偏好值（拖动中的滑杆用 `mTrackingSliders` 跳过，避免和“松手落盘”抢值）；映射抽成纯 JVM `settings/ProjectionControlValues`（比例钳到可用下限、码率吸附到 0.5 Mbps 步长并钳进滑杆范围、IDR 转整秒），保证同步写入的值一定在滑杆范围与步长上，不会抛 `IllegalStateException`。`ProjectionControlValuesTest` 9 条。设备证据（emulator-5554）：构造 100% 尺寸 1080×3000 超过软件编码器可配置上限（`app_process` 实测 1080×2400 可配置、1080×3000 抛 `IllegalArgumentException`），共享停在 82%（884×2460）时把滑杆拖到 100%（`q10-q11-drag-100.png`，标签 100%）松手，Toast「该画面大小无法应用，共享画面保持 884×2460。」出现的同时滑杆在 sheet 内立即回到 82%（`q10-q11-snapped-82.png`），Toast 消失后仍是 82%（`q10-q11-settled-82.png`）；`logcat` 里拒绝后 68 ms 与 75 ms 各有一条 `getMinimumProjectionScalePercent`，即观察者触发的两次控件刷新（`q10-q11-logcat.txt`）。
  - **2026-10-21 设备复测 r3（缺陷 Q-11，P3）画面大小下限低于本机硬编最小尺寸**：滑杆下限固定 5%，1080×2340 下 11%/10%/5% 都落到 144×144 以下被拒；失败路径兜住了，但界面提供了不可用档位。修复：新增 `core/codec/mediacodec/EncoderSizeLimitProbe`（遍历 `MediaCodecList.REGULAR_CODECS` 取首个支持 MIME 的编码器的 `getSupportedWidths()/getSupportedHeights()` 下限，并按 `EncoderSizeAlignment` 向上对齐；取不到或平台异常返回 `null`）与纯 JVM `settings/ProjectionScalePolicy`（下限 = `max(ceil(100·最小宽/窗口宽), ceil(100·最小高/窗口高))`，即绑定较短边，旋转前后同一结果；再按滑杆步长向上取整，钳到 5%–100%，能力未知时保持 5%）。`MainActivity` 在打开/刷新 sheet 时用 `WindowUtils.getMaximumWindowBounds` 作参考边界把该值设为滑杆 `valueFrom`，并打一条 `getMinimumProjectionScalePercent` 日志。`EncoderSizeAlignment` 追加 `alignWidthUp/alignHeightUp`（+1 条用例），`ProjectionScalePolicyTest` 11 条。设备证据：emulator-5554（`c2.android.avc.encoder` 最低 16×16）日志 `5% (encoder minimum 16x16, window 1080x2400)`——本机 2% 即够用，5% 兜底生效，属于预期而非回归；YAL-AL10（最低 176×144）日志 `17% (encoder minimum 176x144, window 1080x2340)`，把滑杆拖到最左，标签显示 17%（`q10-q11-phone-slider-min-17.png`）且 `projection_scale` 落盘 17，说明 1080 宽下 17% = 183 px 已高于 176，5%–16% 不再可选。
  - Q-10/Q-11 的测试与边界：全量 **812 tests / 0 failures / 0 errors / 0 skipped**（72 个测试类，较 790 +22）；`:app:lintDebug` 无新增告警；`check_strings.py`（101 键）/`check_cjk.py`/`check_docs.py` 通过。提交 `d6ad7d7`（代码与测试）、`e1fd170`（本条目）。CI：Build [39468447552](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39468447552)/Checks [39468447542](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39468447542) success。边界：`ProjectionSettings.MIN_SCALE_PERCENT` 仍是 5（服务端与旧版本写入值的兜底），只有界面下限被抬高；模拟器编码器下限太小，抬高分支只能在真机观察（真机仅打开 sheet 读下限并还原偏好，画面回归仍留给下一轮）；Q-10 的拒绝在模拟器上只能用“超过编码器上限”构造，因为抬高下限后滑杆已无法构造低于下限的请求——这本身就是 Q-11 的预期效果。
- **关联文档**：[实施计划](superpowers/plans/2026-10-21-viewer-quality-and-settings.md)、[TB-ENG-006](#tb-eng-006)、[TB-ENG-012](#tb-eng-012)

</details>

<a id="tb-eng-012"></a>
<details><summary>TB-ENG-012 编码参数生效编排（帧率/IDR 重建）（✅ 2026-10-20）</summary>

- **状态 / 优先级 / 创建 / 完成**：✅ 已完成 / 🟡 P2 / 2026-10-20 / 2026-10-20
- **任务**：帧率与关键帧间隔只在编码器创建时生效，因此改这两个参数必须走“写偏好 → 更新运行参数 → 推进 configEpoch → 重建编码器 → 重指采集输出面”，并与尺寸变化共用同一条串行路径，避免两次重建撞 epoch。
- **交付物 / 验证证据**：交付（`6f8d744`）：`ScreenShareService.scheduleEncoderRebuild()` 主线程 300 ms 防抖合并同批变化，`rebuildEncoder()` 固定顺序 `updateParameters → switchDescriptor(epoch+1) → setDisplaySurface(getInputSurface())`，失败只记日志并保持旧编码器（码率可能已热应用，帧率/IDR 仍是旧值）；`KEY_PROJECTION_FRAME_RATE`、`KEY_PROJECTION_IDR_INTERVAL` 与 null-key 全量刷新接入该路径；`KEY_PROJECTION_BITRATE` 保持热应用，`KEY_PROJECTION_CODEC` 明确不处理（另记日志）。`StreamRuntime` 无需改核心 API：`switchDescriptor` 已用当前参数预热新编码器。验证：`StreamRuntimeTest.switchDescriptor_afterUpdateParameters_rebuildsWithNewParameters` 断言新参数进入新编码器且 epoch +1；全量 758 tests 通过（见 [TB-ENG-005](#tb-eng-005) 的 CI 证据）。
  - 边界：未做设备实测；重建导致的画面停顿、客户端重配解码器的耗时与失败回退路径均需真机验证。
  - **2026-10-21 真机复测（缺陷 Q-01/Q-03，P1）**：真机服务端上流中改帧率 6 次 4 败、改 IDR 触发即败，`10 次切换 7 次失败`；失败时 `EncoderException: drain thread did not terminate within 1000ms`，随后 `ANativeWindow::dequeueBuffer failed … error: -19` 共 2219 条、客户端 `mInboundDataRate=0`，**采集永久损坏**，只能停止共享或再改一次才恢复。根因（代码级已定位）：`switchDescriptor` 持 `StreamRuntime` 状态锁调用旧编码器 `stop()`，旧编码器 drain 线程每帧进 `onAccessUnit` 要同一把锁 → 主线程等 join、drain 等锁，互等到 1 s 超时；`stopRequested` 已置位、旧编码器实际已死，运行时却仍把它当活动编码器，VirtualDisplay 继续指向死 surface（`-19` 的来源）。失败后再改一次能成功，正因为此时旧编码器已停止、`stop()` 立即返回。修复（提交 `8ee63e4`）：`switchDescriptor` 改为三阶段（锁内校验/预留代际 → 锁外预热 → 锁内提交 → 锁外停止旧编码器），新增独立 switch 锁串行化切换，锁序固定 switch 锁 → 状态锁，任何编码器生命周期调用都不在状态锁内；旧编码器 `stop()` 失败不再中止切换（保留它等于把运行时绑在死编码器上），切换照常提交、旧编码器走退役关闭；`ScreenShareService` 抽出 `switchEncoderToCurrentSettings()`（固定顺序 + 失败重试一次 + 无论成败都重指 VirtualDisplay 到当前输入面）、旋转/改缩放先 `resize` VirtualDisplay 再切换编码器。回归证据：新增 `StreamRuntimeSwitchTest`（真实 `H264Encoder` + fake bridge，drain 线程持续送帧时切换、旧输入面在新 epoch 首个可加入点后才释放、竞态后第二次切换仍推进 epoch）与 `StreamRuntimeTest` 的“stop 期间不得持状态锁”探针；把旧实现换回后这 5 条用例精确复现 `drain thread did not terminate within 1000ms`，修复后全量 **774 tests / 0 failures**。Q-03（旋转回竖屏冻结、窗口停在横向尺寸）经代码判定与 Q-01 同源（同一条 `updateProjectionSize` 失败路径），修复后由同一批改动覆盖，**仍需设备复测确认**。
  - **2026-10-21 设备复测（缺陷 Q-07，P1）**：把「画面大小」改到 82%（真机 1080×2340 → **885×1918**）与 79%（→ **853×1848**）共 4 次尝试全部失败：`Picture Width(885) … invalid, should N*2` → `configureCodec error -22` → `EncoderException: failed to initialize H.264 encoder`；且 `updateProjectionSize` 当时**先 `resize` 显示、再切编码器**，失败后显示已缩小、编码器仍在旧尺寸 → 查看侧画面被挤压到窗口左侧约 2/3、右侧黑带（黑像素 32–38%），而 `projection_scale` 已按 82/79 落盘且 UI 无任何提示。根因两条：① 采集尺寸没有按编码器步长对齐，百分比可以直接算出奇数宽高；② 尺寸生效顺序把显示改在了不可回退的一侧，失败即留下「显示新、编码器旧」的错配。修复（提交 `08ae680`）：
    ① **对齐**：新增纯 JVM `core/codec/EncoderSizeAlignment`（按步长**向下**取整，小于一步取一步；向下保证不超过用户要求与可用窗口）与 `core/codec/mediacodec/EncoderAlignmentProbe`（遍历 `MediaCodecList.REGULAR_CODECS` 的首个支持 MIME 的编码器，取其 `VideoCapabilities.getWidthAlignment()/getHeightAlignment()`，取不到或平台异常回落偶数）。`getProjectionSize()` 在尺寸源头统一对齐，于是 VirtualDisplay、descriptor 的 coded/visible 尺寸与编码器共用同一几何；**只对齐 coded 尺寸是不够的**——显示会按另一个宽度合成、仍被挤压。对齐策略与能力探测同源，不写死 2 或 16（部分厂商要求 4/8/16）。885×1918 → 884×1918、853×1848 → 852×1848。
    ② **顺序（失败=零副作用）**：新增 `service/ProjectionSizeCoordinator`（纯 JVM）固定「先切编码器、成功后才 resize 显示」；编码器拒绝时显示与编码器都留在旧几何。选择这个顺序而不是「先 resize 再失败回滚」的理由：回滚方案在同一个主线程帧内仍会出现短时错配，且需要第二次 resize 才能恢复，而编码器优先在最坏情况下也是零可见变化；代价是切换与 resize 之间最多一两帧按旧尺寸被编码面拉伸，量级远小于整段会话的挤压。`switchEncoderToCurrentSettings()` 拆出带显式尺寸的重载（`buildRequestedDescriptor(epoch, size)`），因为 `mProjectionSize` 在切换成功前仍是旧值。
    ③ **可感知**：拒绝时 `Log.e` 记录请求尺寸/保持尺寸/生效比例，Toast 告知实际保持的尺寸，并把 `projection_scale` 回写为实际生效值（`mAppliedProjectionScale`），不再停留在「已落盘但未生效」。回写的取舍：瞬时资源失败也会让滑杆回到生效值，但比「值看着改了、画面没变」更诚实；切换本身已带一次重试。
    回归：`EncoderSizeAlignmentTest` 6 条（885→884、853→852、16/8 步长、小于一步、非法步长）与 `ProjectionSizeCoordinatorTest` 4 条（先编码器后显示、拒绝时显示不被触碰、无编码器路径、缺失显示端）；全量 **790 tests / 0 failures / 0 errors / 0 skipped**（70 个测试类）。CI：Build [39462172014](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39462172014)/Checks [39462172116](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39462172116) success（`f30249f`）。**仍需设备复测**：82%/79% 应正常出画、画面完整无黑带，且失败路径不再产生错配。
  - **2026-10-21 「最大帧率」只写 `KEY_FRAME_RATE`、没有提交侧节流（Q-1，P3）**：设备实测把帧率设到 15 后 `enc.outInterval.p50` 仍是 33 ms（≈30 fps），设 40 也一样；`KEY_FRAME_RATE` 对硬编只是码控目标，不构成上限。调研结论（[DV-011](decision-board.md)，含 scrcpy 源码与平台文档）：本项目是 VirtualDisplay → 编码器输入面直通，采集侧没有可插帧的节点，`ImageReader` 中转会把 1080p 变成 CPU 路径；平台自 API 29 起公开 `MediaFormat.KEY_MAX_FPS_TO_ENCODER`（值 `"max-fps-to-encoder"`，float），语义正是「surface-input 编码器丢弃超过该帧率的源帧」，scrcpy 也是唯一用这一把键（其 `SurfaceEncoder` 同时把 `KEY_FRAME_RATE` 固定为 60 并注明它不影响实际帧率）。**低风险 → 本棒实现**：`EncoderFormat` 增加 `maxFpsToEncoder`（单构造，0 = 不请求）并由 `H264Encoder` 传入用户设置的帧率；`AndroidMediaCodecEncoderBridge.configure()` 在请求上限时写入该键，若编码器拒绝（`IllegalArgumentException`/`IllegalStateException`）则 `reset()` 后**去掉该键重配一次**（`configureWithFallback`，包内可见、JVM 可测），因此不认识的厂商编码器最多退化为旧行为，不会让流起不来。测试：`AndroidMediaCodecEncoderBridgeTest` 6 条（接受、回退一次、非上限原因照旧抛出、去键后其余字段不变、0 表示不请求、null 断言）与 `H264EncoderTest` 2 条（提示值即上限、15 fps 传入上限 15）。边界：键名用字符串字面量以兼容 minSdk 25（API 25–28 上该键是私有的，行为取决于厂商）；**编码器是否真的限帧只能设备验证**——真机 YAL-AL10 上 `enc.outInterval.p50` 应从 33 ms 升到约 67 ms（15 fps），若不变则该编码器忽略此键，[DV-011](decision-board.md) 仍维持「不做严格保证」，届时再评估 GPU 上游限帧。
  - 实现细节同步到 [媒体架构 §4.4](media-architecture.md)（对齐在尺寸源头、先编码器后显示、失败可感知）。
- **关联文档**：[实施计划](superpowers/plans/2026-10-21-viewer-quality-and-settings.md)、[TB-ENG-005](#tb-eng-005)、[决策 DB-CODEC-003](decision-board.md#db-codec-003)

</details>

<a id="tb-eng-011"></a>
<details><summary>TB-ENG-011 代码结构梳理与命名统一（✅ 2026-10-20）</summary>

- **状态 / 优先级 / 创建 / 完成**：✅ 已完成 / 🟡 P2 / 2026-10-20 / 2026-10-20
- **任务**：梳理项目代码结构，检查包路径的合理性与命名规范性。根包 `com.obby.android.localscreenshare` 限定不可改，只调整其子包；命名范围覆盖包名、类名、方法名、字段名与资源命名。先盘点现状并给出调整方案，经用户确认后分批做纯结构性重构（移动/改名），不得夹带行为变化；每批需编译、全量 JVM 测试与 CI 绿。
- **交付物 / 验证证据**：交付（批次提交 `b6e12bc` R0 规范、`3fe7953` R1 包结构、`890cbab` R2 命名、`51080cf` R3 常量拆分、`59589fe` R4 去 `Lss` 前缀与桥类归位、`3877dd6` R5 主题名去平台词与组播工厂改名，Javadoc 常量引用补正 `5b1525d`）：R0 落地 `docs/naming-conventions.md`（包/类/方法/字段/资源/常量规则与依赖方向、例外清单）。R1 取消 `utils` 包（`IntentUtils`/`NetUtils`/`NsdResolver`/`ResourceUtils`/`ThreadUtils`/`WindowUtils` 并入 `support`），新建 `settings`、`discovery/legacy`、`discovery/ui`、`core/codec/mediacodec`、`core/util`。R2 统一端点与角色命名（`LssService`→`ScreenShareService`、`LssClientService`→`ScreenViewerService`、`LssServer`→`ScreenShareServer`、`LssClient`→`ScreenShareClient`、`LssDeviceInfo`→`DeviceInfo`、`DeviceListModel`→`DiscoveryListModel`），`discoverServices`/`stopServiceDiscovery` 对称化为 `startDiscovery`/`stopDiscovery`，资源键与主题改为领域前置与 `Theme.<App>.*` 形式。R3 把 `Constants` 的 55 个常量按域拆为 `ServiceProtocol`（12）、`NsdConstants`（17）、`MulticastConstants`（10）、`RegisterConstants`（8）、`ScanConstants`（4）、`GrpcConstants`（2），`support/Constants` 仅余 `CPU_COUNT` 与 `UNKNOWN_HOST_ADDRESS`（2），逐项比对名称、类型与取值 1:1，无遗漏、无重复、无取值变化。R4 去掉 13 个 `Lss*` 类型的前缀，`core/codec/h264` 中 11 个 MIME 通用桥迁入 `core/codec/mediacodec`，`TimeSource` 迁入 `core/util`。R5 把 `Theme.LocalScreenShareAndroid` 与 `ThemeOverlay.LocalScreenShareAndroid.*` 改为 `Theme.ScreenShare`/`ThemeOverlay.ScreenShare.*`（含 SplashScreen、Chip、MediumContrast、HighContrast），Manifest 与两个 Service 的 `R.style` 引用同步；`discovery/udp/AndroidMulticastSocketFactory` 改为 `PerInterfaceMulticastSocketFactory`（仅使用 `java.net.MulticastSocket`，不含 Android API，原名暗示了不存在的平台绑定），`DiscoveryManager`（2 处）与 `ScreenShareService`（2 处）引用同步；`AndroidMediaCodec*`、`AndroidKeystoreKeyStore`、`AndroidInterfaceEnumerator` 属平台绑定，按规范例外保留。R5 为 1 个类文件改名与主题名 1:1 替换，无逻辑、颜色与样式属性变化。验证：**行为零变化**——`git diff --stat 067b7f3..59589fe` 共 112 个文件（46 处 rename、59 处修改、6 个新增常量类），对 258 个在 HEAD 仍存在的 Java 文件逐对剥离 package/import/注释后比对 token 流：251 对完全一致，其余 7 个文件的差异全部是改名映射（类名、方法名、常量持有类、资源键、`toString` 标签）与 R3 常量拆分，无语句、控制流、表达式或字面值变化；`*.proto` 无改动，Manifest 仅主题名与两个 Service 类名（`CHANGE_WIFI_MULTICAST_STATE` 属 TB-DISC-005，早于本任务）。**结构目标**——`support` 对 `com.obby.*` 的 import 为 0（叶子；基线曾依赖 `core.codec`、`core.stream`、`discovery`），`utils` 目录不存在，`core/codec/h264` 仅 13 个 H.264 专属文件，`core/codec/mediacodec` 11 个桥类，`core/util/TimeSource`、`settings`（3 类）、`discovery/legacy`（2 类）、`discovery/ui`（1 类）就位，`git grep "Lss" -- 'app/src/**/*.java'` 为 0；包级强连通分量从 15 个包缩小到 13 个，`support` 已移出环，`settings` 只依赖 `App`、`discovery.model.JoinPolicy` 与 3 个 `*Constants`（规范 §7.2 例外）。**全量与 CI**——`./gradlew :app:testDebugUnitTest --offline` **670 tests / 0 failures / 0 errors / 0 skipped（58 个测试类）**，与重构前基线一致（R5 后仍为 670 tests / 58 个测试类）；`:app:assembleDebug --offline` 成功；`check_strings.py`（79 键双语言一致）、`check_cjk.py`、`check_docs.py` 通过；六批 Build 与 Checks 均 success：R0 Build [39382205518](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39382205518)/Checks [39382205525](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39382205525)、R1 Build [39382923189](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39382923189)/Checks [39382923187](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39382923187)、R2 Build [39383896097](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39383896097)/Checks [39383896058](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39383896058)、R3 Build [39384709405](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39384709405)/Checks [39384709353](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39384709353)、R4 Build [39385566774](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39385566774)/Checks [39385566832](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39385566832)、R5 Build [39387048432](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39387048432)/Checks [39387048449](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39387048449)。
- **备注（遗留观察）**：① `server/ScreenShareServer` 的私有嵌套类 `ClientObserver` 与 `client/ClientObserver` 简单名相同，作用域不同无冲突，后续可评估改名；② `settings/VisibilityPolicy` 仍依赖 `discovery.model.JoinPolicy`（纯模型包，不反向依赖 `settings`，无环；`settings` 对 feature `*Constants` 的依赖属规范 §7.2 例外）；③ `service` 仍是上帝包，两个 1.3k+ 行 Service 内含私有嵌套视图类，拆分会牵动可见性与文件归属之外的改动，非纯移动，建议另立任务；④ `core.codec` 硬编码注册 `JpegCodec` 形成的 `core.codec ↔ core.codec.jpeg` 包环未修，需改注册逻辑，另行处理。
- **关联文档**：[命名规范](naming-conventions.md)、[发现实施计划](superpowers/plans/2026-10-20-device-discovery.md)

</details>

<a id="tb-disc-009"></a>
<details><summary>TB-DISC-009 发现设置与 UI 集成（✅ 2026-10-20）</summary>

- **状态 / 优先级 / 创建 / 完成**：✅ 已完成 / 🟡 P2 / 2026-10-20 / 2026-10-20
- **任务**：发现设置（组播开关/组地址/端口、发现超时与心跳档位、可见性、仅用兼容模式）；设备列表统一从 `DeviceStore` 渲染；服务端/客户端分区与状态展示；`LssServiceInfo` 契约迁移到 `LssDeviceInfo`。
- **交付物 / 验证证据**：交付（提交 `10b835e`，设置热生效修正 `5d20b12`）：`discovery/DiscoverySettings` 不可变快照（组播开关、224.0.0.0/4 字面量校验的组地址、1024–65535 端口、`RefreshTier` 三档把 Register 超时 500 ms 映射为 250/500/1000 ms、心跳 5/15 s 映射为 3/10、5/15、10/30 s，可见性、`compatOnly`；兼容模式只保留 legacy NSD 类型、能力位去掉 JPEG、停用组播与扫描；非法值逐项回退默认，不开放任意数值）；`discovery/VisibilityPolicy`（`HIDDEN`/`FAVORITES`/`VISIBLE`/`VISIBLE_10_MIN` 的公告、`join` 与 Register 接受规则，10 分钟窗口按持久化的绝对到期时间回退为隐藏）；`discovery/DeviceListModel`（共享屏/观看者分区、`CONFIRMED`→收藏→IPv4→最近确认排序、`LIVE`/`IDLE`/需确认/不可加入/不可达状态、STALE/UNREACHABLE 灰显、三种空状态）；`MainActivity` 改为 `ListAdapter<DeviceListModel.Row>`（`DiffUtil` 以 id 判定）并新增分区标题、状态徽标、灰显与设置面板的组播开关/组地址/端口/刷新档位/可见性/兼容模式/广播设备名控件，组地址在失焦或关闭面板时校验提交；`LssDeviceInfo`/`DeviceAddress` 改为 `Parcelable`，`Constants.EXTRA_SERVICE_INFO` 承载 `LssDeviceInfo`，`LssClientService` 改用 `primaryAddress()`，`LssServiceInfo` 标注 `@Deprecated` 仅服务 legacy 投影；`LssServiceDiscoveryManager` 新增 `addDeviceListener`/`removeDeviceListener`，设置变化 200 ms 去抖后重启 provider；`LssService` 在公告设置变化与可见性到期时重启/停止公告，并让 `LssServer` 重建 NSD 注册（兼容模式、join 与广播名即时生效，投屏不重启）；`LssServer` 在兼容模式下只注册 legacy 类型并按可见性拒绝 Register（`not_visible`）。B3 遗留同批处理：30 s 内已确认地址不再重复 Register（`Constants.REGISTER_RECONFIRM_INTERVAL_MS` + `DeviceStore.isConfirmedWithin`），避免心跳耗尽每地址配额；`StreamCallHandler` 记录并暴露 HELLO 的 `device_id`/`fingerprint`。中英文文案 79 键一致。验证：`DiscoverySettingsTest`（13）、`VisibilityPolicyTest`（7）、`DeviceListModelTest`（11）与 `DeviceStoreMergeTest`（+1）、`RegisterCallHandlerTest`（+2）、`StreamCallHandlerTest`（+2）覆盖档位映射与非法值回退、四档可见性与窗口到期、兼容模式 provider/类型/能力映射、分区与排序、状态投影与灰显、可见性对 Register 白名单的影响、已确认地址的去重窗口、HELLO 身份捕获；全量 **670 tests / 0 failures / 0 errors / 0 skipped**（58 个测试类，较 634 基线增加 36）；`./gradlew :app:testDebugUnitTest --offline`、`./gradlew :app:assembleDebug --offline`、`./gradlew :app:assembleDebugAndroidTest --offline`、`:app:lintDebug --offline` 与 `python scripts/checks/check_strings.py` 全部通过；Build CI run [39373857133](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39373857133)、Checks run [39373857069](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39373857069) 与修正提交的 Build run [39374671716](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39374671716)、Checks run [39374671682](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39374671682) 均 success。偏差与边界：`HIDDEN` 只停止公告与 Register，仍保留 NSD 注册（全停会让设备无法被发现）；HELLO 的设备身份目前只捕获并暴露，尚未写入 store 做会话与发现条目的绑定。未执行 adb、模拟器、实机或 `connectedAndroidTest`；真实设置生效、组播/NSD 可见性、Register 白名单与列表状态未实测，设备验证未解禁。
- **关联文档**：[发现调研](discovery-research.md)、[实施计划](superpowers/plans/2026-10-20-device-discovery.md)

</details>

<a id="tb-disc-008"></a>
<details><summary>TB-DISC-008 手动 IP 与收藏（✅ 2026-10-20）</summary>

- **状态 / 优先级 / 创建 / 完成**：✅ 已完成 / 🟡 P2 / 2026-10-20 / 2026-10-20
- **任务**：手动 IP 常驻入口（自动发现 8–12 s 无结果时亮出）；收藏持久化 `{id, fp, ip, port, alias, customAlias}`；上次成功地址优先探测。
- **交付物 / 验证证据**：交付（提交 `0bb9a14`）：`discovery/favorite` 的 `FavoriteDevice`（`id`/`fp`/`ip`/`port`/`alias`/`customAlias`，另记 `identityChanged` 与 `lastSuccessMs`）、`FavoriteStorage`/`SharedPreferencesFavoriteStorage`（独立 `lss-favorites` 文件，与设置文件分离）、`FavoriteStore`（增删改查与持久化、`customAlias=false` 时远端改名自动更新、指纹不一致置 `identityChanged` 且不覆盖、`confirmIdentity` 在用户确认后重绑、`probeTargets` 按上次成功地址排序、旧格式收藏一次性迁移并清除源键）；`discovery/manual` 的 `ManualAddress`（IPv4、带/不带端口、方括号或裸 IPv6，拒绝域名）与 `ManualAddressProvider`（发现启动时并行探测收藏与上次地址，2 s 超时；手动入口探测走同一 Register 判据）；`LssServiceDiscoveryManager` 增加收藏播种（不可达收藏仍以别名与收藏标记留在列表）、`setFavorite`/`confirmFavoriteIdentity`/`probeManualAddress`/`refreshDiscovery`；`MainActivity` 增加“手动添加”常驻按钮与 8 s 空列表提示、长按收藏/取消收藏、收藏与“身份已变更”标识、连接前指纹校验并要求确认；`LssClientService` 在 `onConnected` 回写上次成功地址；中英文文案同步（`check_strings.py` 55 个键一致）。验证：`FavoriteStoreTest`（19）、`ManualAddressTest`（9）、`ManualAddressProviderTest`（7）与 `DeviceStoreTest` 新增 3 覆盖收藏往返与损坏 JSON 回退、改名联动、身份变更标记与确认重绑、上次成功地址优先、旧收藏迁移、IPv4/IPv6 端口解析、收藏探测与手动探测的成功/失败/停止路径、身份变更标记与确认重绑；全量 **634 tests / 0 failures / 0 errors / 0 skipped**（55 个测试类）；两条构建命令与 `:app:lintDebug` 成功；CI 同上。边界：未执行 adb、模拟器、实机或 `connectedAndroidTest`；真实 gRPC 手动探测、收藏跨重启与身份变更提示未在设备上实测，设备验证未解禁。
- **关联文档**：[发现调研](discovery-research.md)、[实施计划](superpowers/plans/2026-10-20-device-discovery.md)

</details>

<a id="tb-disc-007"></a>
<details><summary>TB-DISC-007 分级扫描（✅ 2026-10-20）</summary>

- **状态 / 优先级 / 创建 / 完成**：✅ 已完成 / 🟡 P2 / 2026-10-20 / 2026-10-20
- **任务**：分级扫描：公告与收藏并行，经 1 s grace 仍零确认再扫描；按接口 prefix 扫描（不写死 `/24`，并发 ≤50，单请求 500 ms）；不扫 VPN/蜂窝接口。
- **交付物 / 验证证据**：交付（提交 `0bb9a14`）：`discovery/scan` 的 `NetworkPrefix`（由本机地址与前缀计算网络/广播地址，跳过自身、网络与广播地址；每接口上限 1024，超出退到本机 `/24` 并标 `partial`）、`LocalInterface`/`InterfaceEnumerator`/`InterfaceFilter`/`AndroidInterfaceEnumerator`（只接受 Wi-Fi/以太网，排除 VPN/蜂窝/回环/点对点以及 `tun*`/`ppp*`/`rmnet*`/`dummy*`/`p2p*`，只枚举 IPv4）、`AddressProbe`/`ProbeResult`/`RegisterAddressProbe`（单次 Register、`announces=false`、无重试）、`AddressScanner`（50 线程 + 有界队列 128、500 ms/请求、8 s/接口预算、`cancel()` 幂等、结果带 `scanned`/`confirmed`/`cancelled`/`partial`、接口之间串行）、`ScanCoordinator`（`IDLE → GRACE(1 s) → SCANNING → DONE`，grace 内确认数大于 0 不扫描，重复触发合并、网络变化重启、取消丢弃在途结果）；`LssServiceDiscoveryManager` 把扫描命中走既有 observe+confirm 路径（通道 `SCAN`），并在发现启动、手动刷新、列表为空（8 s）与网络变化时触发。验证：`NetworkPrefixTest`（8）、`InterfaceFilterTest`（7）、`AddressScannerTest`（8）、`ScanCoordinatorTest`（10）覆盖 `/24`/`/22`/`/16` 候选数、非标准掩码、上限截断与 `partial`、VPN/蜂窝/回环/点对点及接口名过滤、并发不超过 50、预算耗尽终止、取消后不再发新探测、grace 零确认才升级、网络变化重启与重复触发合并；全量 **634 tests / 0 failures / 0 errors / 0 skipped**（55 个测试类，较 563 基线增加 71）；`:app:assembleDebug`、`:app:assembleDebugAndroidTest` 与 `:app:lintDebug` 成功；Build CI run [39372297965](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39372297965)、Checks run [39372297959](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39372297959) success。边界：未执行 adb、模拟器、实机或 `connectedAndroidTest`；`/22`、`/16`、热点与双栈网络下的扫描时延和准确率未实测，扫描端口固定取本机配置端口（默认 8080），设备验证未解禁。
- **关联文档**：[发现调研](discovery-research.md)、[实施计划](superpowers/plans/2026-10-20-device-discovery.md)

</details>

<a id="tb-disc-006"></a>
<details><summary>TB-DISC-006 Register 双向发现 RPC（✅ 2026-10-20）</summary>

- **状态 / 优先级 / 创建 / 完成**：✅ 已完成 / 🟠 P1 / 2026-10-20 / 2026-10-20
- **任务**：proto 增加 unary `Register`（复用现有 gRPC 端口）；请求与响应双向携带设备信息；`announces` 标志防止无限回打；Register 成功才写入 `DeviceStore`。
- **交付物 / 验证证据**：交付（提交 `e0b3a5f`）：`screen_stream.proto` 新增 `Register` 与 `DeviceSummary`/`DeviceAddress`，`Hello` 追加 `device_id`/`fingerprint`（`client_id` 仍为 StreamHub 会话键，协议版本保持 1）；`RegisterProtoMapper` 纯 JVM 映射与成功判据（`accepted`、版本兼容、`self.device_id` 非空）；`RegisterStub`/`GrpcRegisterStub` 与 `RegisterClient`（500 ms deadline、200/500 ms 两次重试 + 20% jitter、单地址在途去重、回调主线程、拒绝不重试）；`RegisterCallHandler` 完成校验（身份、26 字符 ID、名称/指纹/地址上限、可打印字符）、每地址 10 次/分钟与全局 100 次/分钟限速、成功写入共享 `DeviceStore` 并确认、`announces=true` 时最多一次 `announces=false` 反向 Register（0–200 ms jitter、30 次/分钟配额），`announces=false` 终止链路；`LssServer` 在同一端口提供 `register` 并通过拦截器取得远端地址，`LssService` 把发现管理器的 store 交给服务端，`LssClient` 的 HELLO 携带新身份字段。验证：`RegisterProtoMapperTest`（9）、`RegisterClientTest`（9）、`RegisterCallHandlerTest`（12）覆盖摘要/观测往返、接受与拒绝判据、版本 0 归一与更高版本拒绝、超时重试次数与终端拒绝、在途去重、取消、限速、反向回打次数与地址、无 store 时仍应答；全量 `./gradlew :app:testDebugUnitTest --offline` **563 tests / 0 failures / 0 errors / 0 skipped**（48 个测试类，较 505 基线增加 58）；`./gradlew :app:assembleDebug --offline`、`./gradlew :app:assembleDebugAndroidTest --offline`（含 `--rerun-tasks`）成功；`:app:lintDebug` 通过；Build CI run [39370781081](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39370781081)、Checks run [39370781130](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39370781130) success。边界：未执行 adb、模拟器、实机或 `connectedAndroidTest`；真实 gRPC 调用、Reverse Register 与限速在设备上的表现未实测，设备验证未解禁。
- **关联文档**：[发现调研](discovery-research.md)、[实施计划](superpowers/plans/2026-10-20-device-discovery.md)、[媒体架构](media-architecture.md)

</details>

<a id="tb-disc-005"></a>
<details><summary>TB-DISC-005 UDP 组播发现通道（✅ 2026-10-20）</summary>

- **状态 / 优先级 / 创建 / 完成**：✅ 已完成 / 🟠 P1 / 2026-10-20 / 2026-10-20
- **任务**：UDP 组播公告：组 `224.0.0.167`、端口可配、TTL=1、启动 3 连发（100/500/2000 ms）+ 周期心跳 + 停服告别 `announces:false`；`MulticastLock` 生命周期管理。
- **交付物 / 验证证据**：交付（提交 `e0b3a5f`）：`discovery/udp` 的 `Announcement`（发现层最小字段与 `boot`/`seq`/`ttl` 信封）、`AnnouncementCodec`（JSON UTF-8、≤512 字节、≤24 字段、字符串上限 64、未知字段忽略、缺省 `announces=true`）、`MulticastConfig`/`MulticastEndpoint`/`MulticastSocketFactory`/`AndroidMulticastSocketFactory`（每个可用接口一个 socket、`joinGroup`、TTL=1、最多 4 个、失败接口跳过）、`MulticastTransport`/`SocketMulticastTransport`（每 socket 接收线程、65536 缓冲、连续 10 次错误后按退避重建）、`MulticastAnnouncer`（100/500/2000 ms 三连发、活跃 5 s/空闲 15 s 心跳、0/100/300 ms 三次 `announces=false` 告别）、`MulticastAnnouncementFilter`（按 `id` 自过滤、`(id,boot,seq)` 去重与重启基线、同一地址 300 ms 合并 Register）、`MulticastLockManager`（进程级引用计数）、`UdpMulticastDiscoveryProvider`（观测投递主线程，公告从不升级为 `CONFIRMED`）；`Constants` 与 `Preferences` 增加组/端口/开关；Manifest 增加 `CHANGE_WIFI_MULTICAST_STATE`；`LssService` 随服务启停公告并在身份重置后以新身份重启公告；`LssServiceDiscoveryManager` 与 NSD 并行接入同一 `DeviceStore`。验证：`AnnouncementCodecTest`（10）、`MulticastAnnouncerTest`（6）、`MulticastProviderDedupTest`（10）、`MulticastLockManagerTest`（2）覆盖编解码与超限/截断/未知字段、三连发与心跳切换及告别关闭、去重与重启、自过滤、300 ms 合并、Register 成功/拒绝路径、锁引用计数与解除；全量 **563 tests / 0 failures / 0 errors / 0 skipped**；两条 Gradle 命令成功，`:app:lintDebug` 通过；CI 同上。边界：未执行 adb、模拟器、实机或 `connectedAndroidTest`；真实组播收发、多网卡接口选择、`MulticastLock` 与同网段互见未实测，设备验证未解禁。
- **关联文档**：[发现调研](discovery-research.md)、[实施计划](superpowers/plans/2026-10-20-device-discovery.md)

</details>

<a id="tb-disc-004"></a>
<details><summary>TB-DISC-004 设备身份（密钥派生 ID）（✅ 2026-10-20）</summary>

- **状态 / 优先级 / 创建 / 完成**：✅ 已完成 / 🟠 P1 / 2026-10-20 / 2026-10-20
- **任务**：设备身份：AndroidKeyStore 生成 EC P-256 长期密钥；`fp=SHA-256(SPKI)`，短 ID 取 26 字符；重装即新身份；设置内提供“重置设备身份”；当前无 TLS，指纹先作声明值，为后续 pinning 铺路。
- **交付物 / 验证证据**：交付（提交 `f899906`）：`identity` 包的 `DeviceIdentity`/`SpkiId`/`IdentityProvider`/`IdentityKeyStore`/`KeystoreIdentityProvider`/`AndroidKeystoreIdentityProvider`/`IdentityManager`/`IdentityUnavailableException` 与 `support/Base32Crockford`；别名 `lss-device-identity` 的 EC P-256 密钥（`SIGN|VERIFY`、`DIGEST_SHA256`、不要求用户认证），`fp` 为 SPKI 的 SHA-256 base64url（43 字符），短 ID 为摘要前 16 字节的 Crockford base32（26 字符）；启动时始终从密钥重算，`Preferences` 只保存比对记录，密钥缺失或比对不一致时报“身份已变更”；`LssServer` 的 NSD 实例名与 TXT `id` 改用短 ID，`LssServerInfo` 新增 `deviceId`/`fingerprint`（`id` 同值并标注 `@Deprecated`），`LssServiceInfo` 新增可空 `fingerprint`，发现自身过滤改用设备短 ID；设置面板显示设备 ID 并提供“重置设备身份”（确认后删除别名并重建、清空发现列表、提示重新开始共享以公告新身份）。验证：`Base32CrockfordTest`（8）、`SpkiIdTest`（7）、`DeviceIdentityTest`（9）、`IdentityProviderContractTest`（9）覆盖已知向量与字母表、长度与字符集、同密钥派生稳定、重置与备份恢复后的身份变更检测、Keystore 不可用上抛；全量 `./gradlew :app:testDebugUnitTest --offline` **505 tests / 0 failures / 0 errors / 0 skipped**（41 个测试类，较 472 基线增加 33）；`./gradlew :app:assembleDebug --offline` 成功；Build CI run [39369212044](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39369212044)、Checks run [39369211992](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39369211992) success。边界：未执行 adb、模拟器、实机或 `connectedAndroidTest`；AndroidKeyStore 的真实生成/读取/重置与备份恢复后的身份变化未实测，设备验证未解禁。
- **关联文档**：[发现调研](discovery-research.md)、[实施计划](superpowers/plans/2026-10-20-device-discovery.md)、[TB-DISC-010](#tb-disc-010)

</details>

<a id="tb-disc-002"></a>
<details><summary>TB-DISC-002 设备模型与 DeviceStore 抽象（✅ 2026-10-20）</summary>

- **状态 / 优先级 / 创建 / 完成**：✅ 已完成 / 🟠 P1 / 2026-10-20 / 2026-10-20
- **任务**：`LssDeviceInfo` 设备模型（`id`、`fp`、`name?`、`role`、`state`、`join`、`secure`、`auth`、`pv`、`mv`、`caps`、`addresses[]`、`lastSeen`）；`DeviceStore` 作为设备状态单一真相源（TTL 过期、多通道合并、`Available` 仅以应用层确认为准）；`DiscoveryProvider` 接口，NSD、组播、手动为并列 provider。
- **交付物 / 验证证据**：交付（提交 `a90358a`，停止/重启加固 `99cbc03`）：`discovery/model` 的 `LssDeviceInfo`/`DeviceAddress` 与 6 个枚举；`discovery/store` 的 `DeviceStore`/`DeviceEntry`/`DeviceStoreListener`（短 ID 主键、`HEARD`/`CONFIRMED`/`STALE`/`UNREACHABLE`、TTL 15/45/90 s、容量 256、告别立即移除、`TimeSource` 注入、不可变快照）；`discovery/provider` 的 `DiscoveryProvider`/`DiscoveryProviderListener`/`DeviceObservation`；`LssServiceDiscoveryManager` 改为 store 门面，用 `legacySnapshot()` 维持 `List<LssServiceInfo>` 契约，UI 零改动。验证：`DeviceStoreTest`（13）、`DeviceStoreMergeTest`（12）、`LssDeviceInfoTest`（11）覆盖多通道合并为一条、TTL 降级与移除、收藏保留、容量淘汰、发现层上报不足以升级为 `CONFIRMED`；全量 `./gradlew :app:testDebugUnitTest --offline` **472 tests / 0 failures / 0 errors / 0 skipped**（37 个测试类，较 396 基线增加 76）；`./gradlew :app:assembleDebug --offline` 成功；Build CI run [39367453590](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39367453590)、Checks run [39367453781](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39367453781) 与加固提交的 Build run [39367831378](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39367831378)、Checks run [39367831393](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39367831393) 均 success。边界：未执行 adb、模拟器、实机或 `connectedAndroidTest`，发现真实行为待设备实测，设备验证未解禁。
- **关联文档**：[发现调研](discovery-research.md)、[实施计划](superpowers/plans/2026-10-20-device-discovery.md)

</details>

<a id="tb-disc-003"></a>
<details><summary>TB-DISC-003 NSD 修正与加固（✅ 2026-10-20）</summary>

- **状态 / 优先级 / 创建 / 完成**：✅ 已完成 / 🟠 P1 / 2026-10-20 / 2026-10-20
- **任务**：服务类型 `_localscreenshare._udp` 改为 `_lss._tcp`（原标签 16 字符超 RFC 6763 的 15 字符上限，且 `_udp` 与实际 TCP gRPC 承载不符）；实例名改为 `LSS-<id 前 8 位>`；TXT 最小化（`name` 默认不广播，原默认取系统设备名是隐私泄露点）；`resolve` 增加超时；发现启动失败复位；重试退避；`NetworkCallback` 在网络切换后重新发现。关联 [TB-CORE-001](#tb-core-001) 的 NSD 服务类型修正、服务名隐私等部分项。
- **交付物 / 验证证据**：交付（提交 `a90358a`，停止/重启加固 `99cbc03`）：`Constants` 新增 `_lss._tcp` 与 legacy 类型、TXT 键与能力位；`NsdTxtRecord` 新命名空间编解码（`v/id/r/s/j/sec/auth/pv/mv/caps`，`n` 仅用户开启时写入），缺 `name` 仍可摄入；`NSD` 服务端双注册（新类型 + legacy）各自持有 listener 与 `BackoffPolicy`，失败只重试本类型；`NsdUtils.resolveService` 增加 2 s 超时与单次投递保护；`NsdDiscoveryCoordinator` 负责每类型独立退避与网络切换去抖重发现（停止/重启总是先停目标类型，避免异步回调导致状态滞后）；`NsdDiscoveryProvider` 双类型发现、自身按 id 过滤、`NetworkCallback` 重发现；`Preferences` 新增 `getDeviceAlias`/`isShareNameEnabled`（默认关闭）。验证：`NsdTxtRecordTest`（17）、`NsdDiscoveryCoordinatorTest`（14）、`BackoffPolicyTest`（9）覆盖 TXT 往返与缺省 name、未知键与未知版本丢弃、退避序列与重置、网络切换去抖只重启一次且取消挂起重试、双类型互不影响、运行标记滞后时仍能停止；全量 **472 tests / 0 failures**；两条 Gradle 命令成功；CI 同上。边界：mDNS 双注册、超时与网络切换的真实行为未实测，设备验证未解禁。
- **关联文档**：[发现调研](discovery-research.md)、[实施计划](superpowers/plans/2026-10-20-device-discovery.md)、[TB-CORE-001](#tb-core-001)

</details>

<a id="tb-disc-001"></a>
<details><summary>TB-DISC-001 设备发现调研沉淀（✅ 2026-10-20）</summary>

- **状态 / 优先级 / 创建 / 完成**：✅ 已完成 / 🟡 P2 / 2026-10-20 / 2026-10-20
- **任务**：沉淀局域网设备发现调研：业界发现机制（mDNS/DNS-SD、SSDP/UPnP、Cast/AirPlay、KDE Connect、Open Screen、LocalSend 等）与平台矩阵、LocalSend 源码深挖、设备身份与字段的三层分配、可靠性参数、方案对比与分阶段落地建议，并标注“已验证证据/建议/推测”的证据边界。
- **交付物 / 验证证据**：交付：`docs/discovery-research.md`，并在 [文档索引](README.md) 的阅读顺序与文档索引中登记。验证：提交 `99c3a50`；`python scripts/checks/check_docs.py` 全绿（16 个 Markdown 文件、384 条相对链接、95 个表格，围栏/路径/凭据检查通过）。内容为源码与协议文档调研，未做设备或网络实测；设备验证仍未解禁。
- **关联文档**：[发现调研](discovery-research.md)、[文档索引](README.md)

</details>

<a id="tb-eng-003"></a>
<details><summary>TB-ENG-003 代码文档与英文注释（✅ 2026-10-19）</summary>

- **状态 / 优先级 / 创建 / 完成**：✅ 已完成 / 🟡 P2 / 2026-10-18 / 2026-10-19
- **任务**：代码文档与英文注释规范：历史代码与后续新增代码都补齐 Javadoc 与必要注释，覆盖公共契约、生命周期、并发状态机与非显然算法；Javadoc、代码注释与日志打印统一使用英文；避免复述代码、过期说明等无意义注释。
- **交付物 / 验证证据**：交付：按 [实施计划](superpowers/plans/2026-10-19-code-docs-i18n.md) 完成 B1–B6 六个仅注释批次，覆盖 93 个 `.java`（计划 `72f87f8`、`317f41a`；B1 `6541794`、B2 `fd1d407`、B3 `62f4800`、B4 `7eda4fb`、B5 `fdc990a`、B6 `b5dfa5d`，范围 `6541794..b5dfa5d`）。验证：`./gradlew :app:testDebugUnitTest --offline --rerun-tasks` 通过 **364 tests / 0 failures / 0 errors / 0 skipped**；`./gradlew :app:assembleDebug --offline` 成功；`git diff 11096a4..HEAD -- '*.java'` 的新增/删除行全部为注释或注释块内空行，剥离注释后语义等价；142 个受控 `.java` 零 CJK、64 处 `Log.*` 全英文；B1–B6 各批 Build CI 成功（run [39199630125](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39199630125)、[39200178531](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39200178531)、[39200943243](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39200943243)、[39201568803](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39201568803)、[39202266591](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39202266591)、[39202787980](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39202787980)）。验收抽审发现 2 处注释与代码不符（`StreamHub` 锁说明、`LssService` 启动失败范围），已由 `2852c5d` 修正并重跑门禁与 CI（run [39203370502](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39203370502)）。边界：未执行 adb、模拟器、实机或 `connectedAndroidTest`。
- **关联文档**：[文档索引](README.md)、[决策 DB-DOC-002](decision-board.md)、[路线图阶段 1](optimization-roadmap.md)

</details>

<a id="tb-eng-004"></a>
<details><summary>TB-ENG-004 中英双语资源（✅ 2026-10-19）</summary>

- **状态 / 优先级 / 创建 / 完成**：✅ 已完成 / 🟡 P2 / 2026-10-18 / 2026-10-19
- **任务**：中英双语与系统语言跟随：提供英文与中文资源；`values/` 使用英文作为默认与回退，中文放入 `values-zh`；界面语言跟随系统语言；暂不提供应用内语言开关；系统语言为中文时用中文，其他系统语言或无法匹配时回退英文。
- **交付物 / 验证证据**：交付：提交 `595dbfd`，仅涉及 `app/src/main/res/values/strings.xml`（英文默认 35 键）与新增 `app/src/main/res/values-zh/strings.xml`（中文 35 键），未改 `.java`、布局、Manifest 或构建文件。验证：两份资源键集合相同、无重复键、XML 可解析；每键占位符（编号/类型/数量）、`xliff:g` 与 `<b>` 标签双语一致；`values/` 零 CJK，中文文本与迁移前一致（仅 4 处引号规范化）；`./gradlew :app:assembleDebug --offline --rerun-tasks` 与 `./gradlew :app:testDebugUnitTest --offline --rerun-tasks` 通过（**364 tests / 0 failures / 0 errors / 0 skipped**）；Build CI run [39204294556](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39204294556) success。语言跟随与回退经资源限定符和代码审查确认（无 `resConfigs`/locale 过滤、无其他 `values-<语言>` 目录、代码无 locale 硬编码）；**不含设备实测，设备验证未解禁**。
- **关联文档**：[媒体架构](media-architecture.md)、[决策 DB-PLATFORM-002](decision-board.md)、[文档索引](README.md)

</details>

<a id="tb-doc-008"></a>
<details><summary>TB-DOC-008 框架实施计划（✅ 2026-10-18）</summary>

- **状态 / 优先级 / 创建 / 完成**：✅ 已完成 / 🔴 P0 / 2026-10-17 / 2026-10-18
- **任务**：编码框架实施计划（最终局部修订）：落实慢客户端先 drain 初始 descriptor+join、JoinDecision 嵌套 Status、Surface 白名单计数、visible size 推进 epoch、基线 diff/未跟踪检查、JPEG 适配器测试边界、参考帧单个 AU 超限 resync、transport close 归属与活动 runtime 描述符过滤等复审项；拆分 StreamRuntime/StreamHub 任务。
- **交付物 / 验证证据**：交付：[实施计划](superpowers/plans/2026-10-17-codec-framework.md)；按计划执行 Task 1–9（`5304a53..c5bff15`），任务 brief、RED/GREEN 日志、报告与 review package 见 `.superpowers/sdd/2026-10-17-codec-framework/`。验证：`./gradlew :app:testDebugUnitTest --offline` 通过 13 类 **155 tests / 0 failures / 0 errors / 0 skipped**；`./gradlew :app:assembleDebug --offline` 成功；`FRAMEWORK_BASE=9f650dc..HEAD` 旧路径 diff/status/未跟踪检查为空；Android API token 与 Surface 白名单检查通过。**隔离框架与 JVM 验证，不接现有投屏；未做设备验证。**
- **关联文档**：[编码框架设计](codec-framework.md)、[决策看板](decision-board.md)、[任务看板](task-board.md)

</details>

<a id="tb-fw-001"></a>
<details><summary>TB-FW-001 编码契约骨架（✅ 2026-10-18）</summary>

- **状态 / 优先级 / 创建 / 完成**：✅ 已完成 / 🟠 P1 / 2026-10-17 / 2026-10-18
- **任务**：编码契约骨架与静态注册：`CodecDescriptor`、`EncoderFactory`、`DecoderFactory`、`CodecPolicy`、`StreamDescriptor`/`StreamParameters`、`EncodedAccessUnit`、客户端六状态；编译期静态表（`PacketizerFactory`、协议版本/HELLO/STREAM_INFO 与 RTP 契约未实现）。
- **交付物 / 验证证据**：交付：`core/codec`、`core/model`、`core/render`、`core/stream`、`core/client` 的契约与静态注册；Task 1–8 提交 `5304a53..86e14a3`，Task 9 E2E/支持提交 `c5bff15`。验证：`./gradlew :app:testDebugUnitTest --offline` 通过 13 类 **155 tests / 0 failures / 0 errors / 0 skipped**（含 `StreamFrameworkEndToEndTest` 6 tests）；`./gradlew :app:assembleDebug --offline` 通过；公共层无 NAL/OBU/JPEG 结构解析与 codec 名称分支，JPEG 差异只在 `core/codec/jpeg`；core 无 Android 媒体/图形/Log 调用，Surface 白名单 4 文件 4 签名，无 Java 9+ 便捷 API。**仅隔离框架与 JVM 验证，不接现有投屏；无 H.264/MediaCodec/RTP 实现、无协议接线，未做设备验证。**
- **关联文档**：[编码框架设计](codec-framework.md)、[DB-CODEC-009](decision-board.md)、[DB-CODEC-010](decision-board.md)、[DB-CODEC-011](decision-board.md)、[DB-CODEC-012](decision-board.md)、[DB-CODEC-013](decision-board.md)

</details>

<a id="tb-fw-004"></a>
<details><summary>TB-FW-004 StreamHub 与队列（✅ 2026-10-18）</summary>

- **状态 / 优先级 / 创建 / 完成**：✅ 已完成 / 🟠 P1 / 2026-10-17 / 2026-10-18
- **任务**：StreamHub 与每客户端有界队列：单活动流注册与查找、订阅关系、独立队列、背压与统计；单流不兼容时只做快照、显式兼容降级或拒绝。
- **交付物 / 验证证据**：交付：`StreamHub`、`ClientSendSession`、`BoundedAccessUnitQueue`、`StreamQueueLimits`、`JoinDecision`、`ClientCapabilities`；提交 `86ec42b..922f00d` 与修复 `1e8cac3`，Task 9 E2E 提交 `c5bff15`。验证：`StreamHubTest` 26、`ClientSendSessionTest` 16、`BoundedAccessUnitQueueTest` 13 与 E2E 慢客户端/显式 fallback 用例通过，全量 155 tests；已有客户端不受新客户端影响、join 请求合并、不兼容返回 `REQUIRES_EXPLICIT_FALLBACK`/`REJECTED`。**仅 fake transport/JVM，不接现有投屏或真实网络。**
- **关联文档**：[编码框架设计](codec-framework.md)、[DB-CODEC-009](decision-board.md)、[DB-MULTI-005](decision-board.md)

</details>

<a id="tb-fw-005"></a>
<details><summary>TB-FW-005 客户端解码状态机（✅ 2026-10-18）</summary>

- **状态 / 优先级 / 创建 / 完成**：✅ 已完成 / 🟠 P1 / 2026-10-17 / 2026-10-18
- **任务**：客户端解码状态机：`NEGOTIATING`、`WAIT_CONFIG`、`WAIT_RANDOM_ACCESS`、`STREAMING`、`RESYNCING`、`CLOSED`；描述符应用、解码器重建、epoch 切换与统计。
- **交付物 / 验证证据**：交付：`ClientMediaSession`、`ClientStateListener`；提交 `25fb141` 与注释修正 `86e14a3`，Task 9 E2E 提交 `c5bff15`。验证：`ClientMediaSessionTest` 15 tests 与 E2E 双客户端/epoch 切换用例通过，全量 155 tests；六状态转换完整、同 epoch 可加入点恢复、跨 epoch 先进入 `WAIT_CONFIG`。**仅 fake decoder/RenderTarget/JVM，未接真实解码器或设备。**
- **关联文档**：[编码框架设计](codec-framework.md)、[DB-CODEC-013](decision-board.md)

</details>

<a id="tb-ci-001"></a>
<details><summary>TB-CI-001 CI 构建稳定性（✅ 2026-10-17）</summary>

- **状态 / 优先级 / 创建 / 完成**：✅ 已完成 / 🔴 P0 / 2026-10-17 / 2026-10-17
- **任务**：CI 构建稳定性：修复依赖仓库顺序。
- **交付物 / 验证证据**：根因：失败 run [39030949452](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39030949452) 在解析 `io.grpc:grpc-context:1.72.0` 时收到阿里云镜像 HTTP 502，Gradle 未回退到后续官方仓库。交付：`settings.gradle` 将 `google()`、`mavenCentral()` 置于镜像之前，镜像保留为后备。验证：本地 `./gradlew build` 成功（113 tasks）；`--refresh-dependencies` 解析日志为 0 次阿里云、70 次 Maven Central、67 次 Google；修复提交 `302e604` 触发的 run [39032521963](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39032521963) 成功。证据记录提交 `2aa5bbf`。
- **关联文档**：[项目现状构建基线](project-assessment.md)

</details>

<a id="tb-doc-001"></a>
<details><summary>TB-DOC-001 文档体系（✅ 2026-10-17）</summary>

- **状态 / 优先级 / 创建 / 完成**：✅ 已完成 / — / 2026-10-17 / 2026-10-17
- **任务**：文档体系：根 README、文档索引、项目现状、产品语境、媒体架构、窗口会话设计与优化路线图。
- **交付物 / 验证证据**：交付：提交 `7a8a05d` 中的 7 个文档文件。验证：该提交为静态审查与设计整理，未执行构建、单元测试或实机验证。
- **关联文档**：[文档索引](README.md)

</details>

<a id="tb-doc-002"></a>
<details><summary>TB-DOC-002 任务与决策看板（✅ 2026-10-17）</summary>

- **状态 / 优先级 / 创建 / 完成**：✅ 已完成 / — / 2026-10-17 / 2026-10-17
- **任务**：任务与决策看板：建立稳定 ID、状态、证据与关联文档；接入文档索引和根 README 文档列表；补充看板维护规则。
- **交付物 / 验证证据**：交付：`docs/task-board.md`、`docs/decision-board.md`、`docs/README.md` 入口与维护规则；根 README 链接。验证：链接、围栏、Mermaid、禁用角色词、敏感信息与行尾空白检查随本次文档提交执行；未运行 Android 构建。
- **关联文档**：[决策看板](decision-board.md)、[文档索引](README.md)

</details>

<a id="tb-doc-003"></a>
<details><summary>TB-DOC-003 采集与多客户端裁决（✅ 2026-10-17）</summary>

- **状态 / 优先级 / 创建 / 完成**：✅ 已完成 / — / 2026-10-17 / 2026-10-17
- **任务**：本轮裁决同步：记录问题 1（已关闭）与问题 3（待确认/待实机验证）的采集、编码参数与多客户端裁决，补充协议状态、参数语义与多档编码边界。
- **交付物 / 验证证据**：交付：本次提交 `docs: 记录采集与多客户端裁决` 中的 `docs/media-architecture.md`、`docs/decision-board.md`、`docs/task-board.md`、`docs/README.md`。验证：链接、围栏、Mermaid、角色词、敏感信息与表格列数检查；未修改代码或 CI，未运行 Android 构建，未实机验证。证据记录提交：`f7c7a5b`。
- **关联文档**：[决策看板](decision-board.md)、[媒体架构](media-architecture.md)

</details>

<a id="tb-doc-004"></a>
<details><summary>TB-DOC-004 设备能力分档修订（✅ 2026-10-17）</summary>

- **状态 / 优先级 / 创建 / 完成**：✅ 已完成 / — / 2026-10-17 / 2026-10-17
- **任务**：设备能力分档裁决修订：修正“自动永远只选 H.264”“按老车机能力限制全产品”“API 25 决定能力上限”等保守表述；补充服务端/客户端运行时能力探测、HEVC/AV1 边界、分辨率与帧率场景分档、兼容单流与多档流策略；在决策看板保留裁决演进痕迹。
- **交付物 / 验证证据**：交付：本次提交 `docs: 修订设备能力分档裁决` 中的根 `README.md`、`docs/README.md`、`docs/media-architecture.md`、`docs/decision-board.md`、`docs/task-board.md`、`docs/product-context.md`、`docs/project-assessment.md`、`docs/optimization-roadmap.md`、`docs/windowing-session-design.md`。验证：链接、围栏、Mermaid、角色词、敏感信息、表格列数与 `git diff` 检查；未修改代码或 CI，未运行 Android 构建，未实机验证。问题 3 本身仍未由用户关闭，不因本文档任务完成而标记关闭。证据记录提交：`02b2d9f`。
- **关联文档**：[决策看板](decision-board.md)、[媒体架构](media-architecture.md)、[产品语境](product-context.md)

</details>

<a id="tb-doc-005"></a>
<details><summary>TB-DOC-005 编码框架设计（✅ 2026-10-17）</summary>

- **状态 / 优先级 / 创建 / 完成**：✅ 已完成 / — / 2026-10-17 / 2026-10-17
- **任务**：可扩展编码框架设计：记录用户已批准的“静态 Codec SPI + 单活动流核心 + 可扩展 StreamHub”；明确责任边界、扩展点、描述符与运行参数、访问单元、可加入点与六状态客户端、慢客户端、非无缝编码切换、传输解耦、落地顺序、验收标准与非目标；同步文档入口与两个看板。
- **交付物 / 验证证据**：交付：`docs/codec-framework.md`，以及根 `README.md`、`docs/README.md`、`docs/media-architecture.md`、`docs/optimization-roadmap.md`、`docs/decision-board.md`、`docs/task-board.md` 的入口与裁决更新（提交 `b4fad88`）。验证：链接、围栏、Mermaid、角色词、敏感信息、表格列数、`git diff --check` 与变更范围检查通过；未修改产品代码或构建配置；本地未执行 Android 构建；推送后远端 Build CI 成功（run [39036309808](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39036309808)）；未实机验证。框架裁决为“已批准，待实施/验证”，所有实现任务仍在待办。
- **关联文档**：[编码框架设计](codec-framework.md)、[决策看板](decision-board.md)

</details>

<a id="tb-doc-006"></a>
<details><summary>TB-DOC-006 启动与签名清单（✅ 2026-10-17）</summary>

- **状态 / 优先级 / 创建 / 完成**：✅ 已完成 / — / 2026-10-17 / 2026-10-17
- **任务**：启动与签名检查清单：区分当前环境编译/安装缺项、设备与运行时条件、Debug/Release/正式发布签名要求；记录真实权限与未知项；同步根 README 与文档索引；新增正式发布签名加固待办。
- **交付物 / 验证证据**：交付：`docs/startup-checklist.md`、根 `README.md`、`docs/README.md`、`docs/task-board.md`。验证：本地 `./gradlew :app:assembleDebug --offline` 与 `:app:assembleRelease --offline` 成功；未安装 APK、未连接或修改设备；未执行服务端/客户端实机联调。示例签名只做概括，文档不含任何真实签名值、口令或密钥内容。
- **关联文档**：[启动与签名检查清单](startup-checklist.md)

</details>

<a id="tb-doc-007"></a>
<details><summary>TB-DOC-007 签名包与三设备安装（✅ 2026-10-17）</summary>

- **状态 / 优先级 / 创建 / 完成**：✅ 已完成 / — / 2026-10-17 / 2026-10-17
- **任务**：Release 签名包构建与三设备安装：使用私有签名材料完成 Release 构建，并将同一 APK 覆盖安装到两台虚拟机和一台实机。
- **交付物 / 验证证据**：交付：`app/build/outputs/apk/release/app-release.apk`。验证（最新一次执行）：`./gradlew :app:assembleRelease` 成功；APK Signature Scheme v2 校验有效、单签名者；三台设备 `adb install -r` 均返回 Success，`pm path` 均确认目标 `applicationId` 已安装（版本 `1.0`）；安装后检查时三台均在线；无签名冲突或其他阻塞。未启动应用，未授予运行时权限，未卸载、清除数据或恢复出厂。
- **关联文档**：[启动与签名检查清单](startup-checklist.md)

</details>

### ⏳ 等待验证条目

<a id="tb-fw-003"></a>
<details><summary>TB-FW-003 H.264 单流接入（⏳ 等待验证）</summary>

- **状态 / 优先级 / 创建 / 完成**：⏳ 等待验证 / 🟠 P1 / 2026-10-17 / —
- **任务**：H.264 单流接入与生产接线：`MediaCodec` 骨架、Surface 输入、明确 IDR 才可加入、`configEpoch`、配置缓存、有界队列与 `RESYNCING`；`StreamRuntime`/`StreamHub` 接入 `LssService`/`LssServer`，`VirtualDisplay` 直通编码器输入 Surface，媒体经统一 gRPC 双向流承载完整访问单元，移除旧 JPEG RPC。
- **交付物 / 验证证据**：已完成（代码层面）：计划 `4a3e3da`；批次 A `4ceab10`（双向流协议 + `GrpcMediaTransport`/`ScreenStreamProtoMapper`）、B `c027651`（服务端接入 StreamHub 与 H.264 采集直通）、D `8a9c921`（参数贯通、JPEG 回退经框架、移除旧 JPEG RPC）、F `a4d4bc8`（deferred 编码切换编排）；连同此前 H.264 适配器提交范围 `8161673..9cb8af9`。验证：JVM **396 tests / 0 failures / 0 errors / 0 skipped**（较基线 364 新增 32）；`./gradlew :app:assembleDebug --offline` 通过；各批 Build CI success（A run [39218611718](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39218611718)、B run [39219935934](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39219935934)、D run [39222180985](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39222180985)、F run [39223625683](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39223625683)）。边界：**仅 JVM/fake 与编译验证，未做设备/真机/模拟器验证**；全程未运行 adb、`connectedAndroidTest`、安装或启动 App。问题 3 仍未关闭，代码完成不等于问题关闭。
- **关联文档**：[H.264 实施计划](superpowers/plans/2026-10-18-h264-adapter.md)、[媒体路径接线实施计划](superpowers/plans/2026-10-19-media-path-wiring.md)、[编码框架设计](codec-framework.md)、[DB-CODEC-013](decision-board.md)、[DB-MULTI-005](decision-board.md)、[路线图阶段 2](optimization-roadmap.md)

</details>

<a id="tb-fw-006"></a>
<details><summary>TB-FW-006 编码切换编排（⏳ 等待验证）</summary>

- **状态 / 优先级 / 创建 / 完成**：⏳ 等待验证 / 🟠 P1 / 2026-10-17 / —
- **任务**：编码切换编排：新编码器预热、客户端保持最后一帧、切换采集输出 Surface、发布新描述符/配置/可加入点、客户端重配或重建解码器、释放旧编码器；网络、认证与控制会话不重建。客户端会话与 `RenderTarget` 接线（批次 C/H）是切换与 Surface 恢复的运行前提。
- **交付物 / 验证证据**：已完成（代码层面）：批次 F `a4d4bc8` 修订 `StreamRuntime.switchDescriptor` 支持 deferred 编码器：同步预热成功后才切换、失败保留旧编码器与 descriptor/epoch、新 descriptor/config/可加入点先于新 epoch 首个 AU、旧编码器在新 epoch 首个 join point 转发后释放；客户端重配与 Surface 恢复由批次 C `9bc7f1b`（`ClientMediaSession` + `SurfaceRenderTarget` + 单线程解码）与 H `37b2095`（Surface 失效进入 `RESYNCING`，恢复只经 `retryConfigure()`）落地。验证：JVM **396 tests / 0 failures / 0 errors / 0 skipped**；`./gradlew :app:assembleDebug --offline` 通过；Build CI success（F run [39223625683](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39223625683)、C run [39221385701](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39221385701)、H run [39224616320](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39224616320)）。边界：**仅 JVM/fake 与编译验证，未做设备/真机/模拟器验证**；切换黑帧时长、预热失败回退时延与 Surface 恢复时间均未实测；问题 3 仍未关闭。
- **关联文档**：[编码框架设计](codec-framework.md)、[媒体路径接线实施计划](superpowers/plans/2026-10-19-media-path-wiring.md)、[DB-CODEC-014](decision-board.md)

</details>

<a id="tb-media-001"></a>
<details><summary>TB-MEDIA-001 H.264 硬编硬解（⏳ 等待验证）</summary>

- **状态 / 优先级 / 创建 / 完成**：⏳ 等待验证 / 🟠 P1 / 2026-10-17 / —
- **任务**：H.264 硬编硬解（先 gRPC 双向流）：`VirtualDisplay` 直接输出到 `MediaCodec` 输入 Surface；H.264 Baseline 为兼容基线与回退档；缓存 config 与 IDR；媒体经统一 gRPC 双向流承载完整访问单元（已实现接线，批次 A `4ceab10`）；JPEG 仅保留快照与降级。
- **交付物 / 验证证据**：已完成（代码层面）：批次 B `c027651`（`VirtualDisplay` 直通 `MediaCodec` 输入 Surface、StreamHub 每客户端会话）、C `9bc7f1b`（客户端 `ClientMediaSession` 解码与 Surface 渲染）、D `8a9c921`（码率/IDR 间隔参数贯通、JPEG 回退经框架、旧 JPEG RPC 移除）、G `101f685`（多客户端按共同兼容能力加入）。验证：JVM **396 tests / 0 failures / 0 errors / 0 skipped**；`./gradlew :app:assembleDebug --offline` 通过；Build CI success（B run [39219935934](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39219935934)、C run [39221385701](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39221385701)、D run [39222180985](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39222180985)、G run [39224142850](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39224142850)）。未执行：编码器/解码器设备矩阵、稳态无全尺寸 Bitmap/JPEG 的实测、新客户端从 config+IDR 加入与慢客户端隔离的真机验证。**仅 JVM/fake 与编译验证，未做设备/真机/模拟器验证。**
- **关联文档**：[路线图阶段 2](optimization-roadmap.md)、[媒体架构](media-architecture.md)、[媒体路径接线实施计划](superpowers/plans/2026-10-19-media-path-wiring.md)

</details>

<a id="tb-media-003"></a>
<details><summary>TB-MEDIA-003 运行时能力探测（⏳ 等待验证）</summary>

- **状态 / 优先级 / 创建 / 完成**：⏳ 等待验证 / 🟠 P1 / 2026-10-17 / —
- **任务**：运行时能力探测：服务端探测编码器 codec/profile/level/宽高/帧率/码率/硬件实例与热状态；客户端探测解码能力与 adaptive playback；经 HELLO/STREAM_INFO 交换，字段可缺省；旧客户端按 H.264 兼容档处理。
- **交付物 / 验证证据**：已落地（代码层面，服务端编码器探测尚未接入选档）：批次 E `a7f93e8` 新增 `CodecCapability` 能力模型、`ClientCapabilities` 能力映射与 `support/CodecCapabilityProbe`（基于 `MediaCodecList`/`VideoCapabilities` 的运行时探测：客户端解码器宽高/帧率/码率/adaptive playback，服务端编码器可用性探测入口；探测失败按不可用处理，**不按 API 等级或设备形态推断**）；客户端已把探测结果写入 HELLO，服务端解析缺省字段并按 H.264 兼容档处理。批次 G `101f685` 在 `StreamHub.join` 按共同兼容能力选择候选 codec（profile/level 声明不一致即不兼容，宽高/帧率/码率取共同下界），无共同档返回 `REQUIRES_EXPLICIT_FALLBACK`。验证：JVM **396 tests / 0 failures / 0 errors / 0 skipped**；`./gradlew :app:assembleDebug --offline` 通过；Build CI success（E run [39222721914](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39222721914)、G run [39224142850](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39224142850)）。边界：JVM 不触 `android.media.*`，探测结果与实际设备能力的一致性未验证；热/功耗状态未覆盖；**未做设备/真机/模拟器验证**。
- **关联文档**：[媒体架构能力探测](media-architecture.md)、[媒体路径接线实施计划](superpowers/plans/2026-10-19-media-path-wiring.md)、[决策 DB-CODEC-006](decision-board.md)、[路线图阶段 2](optimization-roadmap.md)

</details>

<a id="tb-codec-001"></a>
<details><summary>TB-CODEC-001 HEVC 可选档位（⏳ 等待验证）</summary>

- **状态 / 优先级 / 创建 / 完成**：⏳ 等待验证 / ⚪ P3 / 2026-10-17 / —
- **事项**：HEVC 作为可选高档位启用。
- **触发条件 / 所需证据**：服务端与客户端均探测支持，且同场景实测相比 H.264 有明确码率/画质收益；否则保持 H.264 兼容档、HEVC 仅作为可选项。当前未实测。
- **关联文档**：[媒体架构编码格式](media-architecture.md)

</details>

<a id="tb-net-001"></a>
<details><summary>TB-NET-001 RTP/UDP 传输（⏳ 等待验证）</summary>

- **状态 / 优先级 / 创建 / 完成**：⏳ 等待验证 / ⚪ P3 / 2026-10-17 / —
- **事项**：RTP/UDP 媒体传输：RTP 封装、jitter buffer、NACK、PLI/FIR、pacing、拥塞控制、SRTP/DTLS。
- **触发条件 / 所需证据**：阶段 2/3 的 TCP 实测出现不可接受的重传拖尾或帧到达尖峰，且产品需要更低延迟；需同设备同网络的 RTT p50/p95、重传率、帧间隔 p95/p99、解码截止错过数据。当前未测量。
- **关联文档**：[路线图阶段 5](optimization-roadmap.md)、[媒体架构传输](media-architecture.md)

</details>

<a id="tb-net-002"></a>
<details><summary>TB-NET-002 WebRTC 互操作（⏳ 等待验证）</summary>

- **状态 / 优先级 / 创建 / 完成**：⏳ 等待验证 / ⚪ P3 / 2026-10-17 / —
- **事项**：WebRTC：浏览器互操作、跨网/NAT、完整音视频栈。
- **触发条件 / 所需证据**：产品确认需要浏览器客户端、跨网或完整栈；或 TCP/RTP 方案无法满足目标场景。当前未决策。
- **关联文档**：[路线图阶段 5](optimization-roadmap.md)、[产品语境](product-context.md)

</details>

<a id="tb-prod-001"></a>
<details><summary>TB-PROD-001 场景边界（⏳ 等待验证）</summary>

- **状态 / 优先级 / 创建 / 完成**：⏳ 等待验证 / ⚪ P3 / 2026-10-17 / —
- **事项**：场景边界：手机、平板、大屏设备与车机，以及多显示器。
- **触发条件 / 所需证据**：目标机型/系统版本上的悬浮窗权限、网络、休眠、音频路由、屏幕布局与合规实测通过；车机只是候选场景之一，不是能力基准；旗舰平板/手机可能具备更强编解码能力，不能按车机下限限制全产品。当前只是设计压力测试，不是既定场景。
- **关联文档**：[产品语境场景判断](product-context.md)

</details>

<a id="tb-win-002"></a>
<details><summary>TB-WIN-002 PiP 可选增强（⏳ 等待验证）</summary>

- **状态 / 优先级 / 创建 / 完成**：⏳ 等待验证 / ⚪ P3 / 2026-10-17 / —
- **事项**：PiP 作为可选增强。
- **触发条件 / 所需证据**：API 26+ 用户需要系统级小窗或后台显示，且确认不替代跨应用悬浮窗；API 25 不可用。当前未验证。
- **关联文档**：[窗口与会话设计窗口选型](windowing-session-design.md)

</details>

### ⏸️ 暂缓条目

<a id="tb-cloud-001"></a>
<details><summary>TB-CLOUD-001 云端中继与账号（⏸️ 暂缓）</summary>

- **状态 / 优先级 / 创建 / 完成**：⏸️ 暂缓 / ⚪ P3 / 2026-10-17 / —
- **事项**：云端中继与账号体系。
- **暂缓理由 / 重新评估条件**：产品定位未验证，会改变安全与成本模型；只有确认跨网/多用户需求后再评估。
- **关联文档**：[路线图不建议项](optimization-roadmap.md)、[产品语境](product-context.md)

</details>

<a id="tb-ctrl-002"></a>
<details><summary>TB-CTRL-002 多客户端并发控制（⏸️ 暂缓）</summary>

- **状态 / 优先级 / 创建 / 完成**：⏸️ 暂缓 / ⚪ P3 / 2026-10-17 / —
- **事项**：多客户端并发控制。
- **暂缓理由 / 重新评估条件**：冲突、权限与审计复杂；先落地单客户端控制租约，再评估是否放开。
- **关联文档**：[媒体架构反控](media-architecture.md)

</details>

<a id="tb-display-001"></a>
<details><summary>TB-DISPLAY-001 4K/120/HDR 默认目标（⏸️ 暂缓）</summary>

- **状态 / 优先级 / 创建 / 完成**：⏸️ 暂缓 / ⚪ P3 / 2026-10-17 / —
- **事项**：把 4K、120 fps 或 HDR 设为默认目标。
- **暂缓理由 / 重新评估条件**：不设全产品默认目标；设备明确支持且同场景实测稳定时，可作为可选高档位，不因此限制其他设备。当前未实测。
- **关联文档**：[路线图不建议项](optimization-roadmap.md)

</details>

<a id="tb-media-002"></a>
<details><summary>TB-MEDIA-002 每客户端转码（⏸️ 暂缓）</summary>

- **状态 / 优先级 / 创建 / 完成**：⏸️ 暂缓 / ⚪ P3 / 2026-10-17 / —
- **事项**：每客户端独立转码或完整 simulcast 栈。
- **暂缓理由 / 重新评估条件**：默认兼容单流 + 受限多档流（TB-CLIENT-002）已能覆盖高/低能力客户端并存；每客户端转码会放大编码器实例、功耗与实现复杂度，只有明确需求与实测收益才引入。
- **关联文档**：[媒体架构多客户端](media-architecture.md)

</details>

<a id="tb-rewrite-001"></a>
<details><summary>TB-REWRITE-001 Kotlin 重写（⏸️ 暂缓）</summary>

- **状态 / 优先级 / 创建 / 完成**：⏸️ 暂缓 / ⚪ P3 / 2026-10-17 / —
- **事项**：全面改写 Kotlin/Compose。
- **暂缓理由 / 重新评估条件**：当前代码量小、结构可改；重写会同时引入语言、框架与媒体风险。
- **关联文档**：[路线图不建议项](optimization-roadmap.md)

</details>

<a id="tb-root-001"></a>
<details><summary>TB-ROOT-001 root 输入注入（⏸️ 暂缓）</summary>

- **状态 / 优先级 / 创建 / 完成**：⏸️ 暂缓 / ⚪ P3 / 2026-10-17 / —
- **事项**：root/shell/系统签名输入注入。
- **暂缓理由 / 重新评估条件**：普通 App 不可用，安全与合规风险高；反控基线使用无障碍能力。
- **关联文档**：[媒体架构反控能力](media-architecture.md)

</details>

## 🔧 维护方式

- 新增条目：先在对应状态节加一行概览（7 列窄列），再在“条目详情”补 `<details>` 块；两处 ID 必须一致。
- 日期口径：创建=该 ID 首次出现在本文件的提交日期（`git log --reverse --format=%ad --date=short -S "<ID>" -- docs/task-board.md` 最早命中）；完成=状态变为“已完成”的提交日期或证据提交日期（以 git 证据为准），未完成留 `—`；无法考证时用首次出现日期。
- 排序：每个状态节内按日期降序（有完成日期用完成日期，否则用创建日期；同日期按 P0→P1→P2→P3→—，再按 ID）。
- 状态与优先级使用“图例”中的 emoji+文字；不使用纯文字或自造图标。
- 证据只维护一份：提交哈希、CI run 链接与测试数字只写在条目详情块，不在概览表重复；“关联”列只放 1–2 个关键链接。
- 状态变化时只更新对应条目的状态、完成日期与详情证据，不手工维护任务总数或完成率。
- 开始实施前补充范围与验证方式；完成时补充可核对的提交、测试输出或实测记录。
- 未实测的收益、未执行的验证和产品推测不得写成已完成或已验证。
- 问题讨论的“关闭”与代码任务的“完成”分开记录；裁决关闭不改变对应实现任务的状态。
- 决策记录只在 [决策看板](decision-board.md) 维护；本页“当前焦点”只保留一行引用，不复制裁决正文。
- 新增或删除任务时同步检查 [决策看板](decision-board.md) 是否需要新增裁决或复审条件。
- 任务状态或技术裁决变化时，同步更新本页、[决策看板](decision-board.md) 和 [文档索引](README.md)。
