# 🧭 决策看板

> 用途：集中记录已经形成的技术裁决、当前状态、依据与复审条件，避免把“设计目标”误读成“已经实现”；**裁决不等于已实施**——本页“未实施”项都没有经过设备、网络或性能实测，验证证据必须单独记录，不能用设计推理代替。

## 📖 图例

| 类别 | 图标 | 含义 |
| --- | --- | --- |
| 状态 | ✅ 已验证 | 约定或方案已落地，并有可核对的静态检查、测试、构建与 CI 证据；不代表设备或实机验证 |
| 状态 | 🟢 已批准·待实施/验证 | 用户已明确批准设计，但没有代码、构建或实测证据；不得写成已实施或已验证 |
| 状态 | 📝 已裁决·未实施 | 方向已确定，代码尚未按此实现 |
| 状态 | ✔️ 已裁决·当前实现符合 | 当前代码或构建配置已经满足，无需额外改造 |
| 状态 | 📜 已裁决·文档约定生效 | 文档与协作规范已经生效 |
| 状态 | ⏳ 待验证 | 需要实测或产品确认后才能定稿，不能写成永久结论 |
| 状态 | ❌ 已否决 | 已明确不纳入当前产品前提，除非前提改变 |
| 状态 | ❓ 待决策 | 尚未形成结论，不能在没有证据时写成裁决 |

## 🗳️ 本轮裁决记录

问题编号只用于追溯讨论，不是协议或代码中的概念。

- 依据：`docs/` 静态审查与设计文档、当前仓库代码、用户已确认的采集与多客户端技术裁决，以及本轮由用户明确批准的编码框架方案。
- 状态：问题 1（采集 API 选择）已由用户关闭；问题 3（采集、编码参数与多客户端）的裁决已记录并按设备能力分档修订，但尚未关闭，也未实机验证；编码框架方案已批准（静态 Codec SPI、单活动流核心与可扩展 StreamHub），代码层面已由批次 A–H 接入生产链路，状态为“已实施、待设备验证”，仍无设备实测证据。
- 边界：API 25/minSdk 是兼容下限而非能力上限，车机只是场景之一；AV1/HDR/B 帧/时域层只预留契约；当前代码已切到统一 gRPC 双向流（DB-NET-001 已同步修订），H.264 经框架直通编解码，JPEG 保留为经框架运行的降级模式；问题 3 未关闭；旧的保守表述与旧状态名保留在“裁决演进”中。

| 议题 | 结论一句话 | 日期 |
| --- | --- | --- |
| 问题 1：采集 API 选择 | 保留 `MediaProjection`，采集直通编码输入 Surface | 2026-10-17 |
| 问题 3：采集、编码参数与多客户端 | 单采集单编码，每客户端独立会话与有界队列 | 2026-10-17 |
| 问题 3 修订：设备能力分档 | API 25 只是下限，能力按运行时探测分档 | 2026-10-17 |
| 编码框架方案 | 静态 Codec SPI + 单活动流核心 + StreamHub | 2026-10-17 |
| 问题 8：发烫与功耗 | 无观看者暂停采集；热状态只做临时覆盖，不写回用户设置 | 2026-10-21 |

### 📎 议题全文

<details><summary>问题 1：采集 API 选择（2026-10-17）</summary>

- **状态**：已关闭（裁决完成）
- **裁决**：保留 `MediaProjection`；主链路改为 `VirtualDisplay` 直接输出到 `MediaCodec` 输入 Surface，稳态不经过 `ImageReader`、Bitmap 与软件 JPEG。
- **证据 / 边界**：用户明确关闭。裁决已记录；代码层面已由媒体路径批次 A–H 接线，未实机验证。
- **关联**：DB-CAP-001、DB-CAP-002

</details>

<details><summary>问题 3：采集、编码参数与多客户端（2026-10-17）</summary>

- **状态**：裁决已记录，待确认/待实机验证
- **裁决**：一个 MediaProjection/VirtualDisplay，通常一路编码；每客户端独立网络会话、有界队列与解码器；分辨率与编码器尺寸匹配；`KEY_FRAME_RATE` 只是目标提示；码率与 IDR 间隔可配置；HELLO/STREAM_INFO/WAIT_IDR/STREAMING；窗口缩放不省网络与解码；队列溢出进入 WAIT_IDR；多档编码不新建 MediaProjection。
- **证据 / 边界**：用户已给出完整技术回答，但尚未明确关闭。本轮只记录裁决，未改代码，未实机验证。
- **关联**：DB-CAP-002、DB-CODEC-003、DB-CODEC-004、DB-CODEC-005、DB-SESSION-002、DB-MULTI-002、DB-MULTI-003

</details>

<details><summary>问题 3 修订：设备能力分档（2026-10-17）</summary>

- **状态**：裁决已修订记录，待确认/待实机验证
- **裁决**：修订此前过度保守的表述：API 25/minSdk 只是最低兼容与回退路径，不是能力上限；不得凭 API 等级、手机/Pad/车机形态推断编解码能力；服务端与客户端分别探测 codec、profile/level、宽高、帧率、硬件实例等，按实际能力与场景选档；H.264 是兼容基线不是上限，HEVC 双方支持时可用，AV1 只作为设备明确支持时的后续高级选项；多客户端分“兼容单流”和“多档流”，新客户端不得迫使已有客户端无提示降级；未实测前可回退 H.264，不做盲目自动切换。
- **证据 / 边界**：用户明确指出 API 25 只是下限、车机只是场景之一、Pad 也可能达到旗舰手机能力，并裁定修正保守表述。这是对前一裁决的修订/补充，不是推翻采集与多客户端基本结构；问题仍未由用户明确关闭，未实机验证。
- **关联**：DB-CAP-003、DB-CODEC-006、DB-CODEC-007、DB-CODEC-008、DB-MULTI-004、DB-CODEC-001、DB-MULTI-003

</details>

<details><summary>编码框架方案（2026-10-17）</summary>

- **状态**：已批准；代码层面已落地，待设备验证
- **裁决**：采用“静态 Codec SPI + 单活动流核心 + 可扩展 StreamHub”；第一版用 H.264 与 JPEG 两条差异实现验证契约，HEVC 验证 `MediaCodec` 骨架复用，AV1/HDR/B 帧/时域层只预留契约；描述符与运行参数分离；可加入点独立于关键帧标记；第一版编码切换不保证零间隙；传输按 TCP 完整访问单元、RTP 封包器与 WebRTC 独立媒体栈解耦。
- **证据 / 边界**：用户已明确“批准”。本轮只新增设计文档并更新看板/入口，未改产品代码或构建配置，未执行构建与实机验证；后续批次 A–H 已按本方案完成代码接线，完整实施证据见任务看板 [TB-FW-003](task-board.md#tb-fw-003)、[TB-FW-006](task-board.md#tb-fw-006) 与 [TB-MEDIA-003](task-board.md#tb-media-003)，设备验证仍未执行。问题 3 未被用户明确关闭，不因本方案获批或代码完成而标记关闭。
- **关联**：[可扩展编码框架设计](codec-framework.md)、DB-CODEC-009、DB-CODEC-010、DB-CODEC-011、DB-CODEC-012、DB-CODEC-013、DB-CODEC-014、DB-MULTI-005、DB-NET-002

</details>

## 📊 裁决总览

> 按裁决日期降序，同日期按 ID；状态图例见上节，详情与全部证据链接见下节“裁决详情”。

| ID | 状态 | 主题 | 标题 | 裁决日期 | 验证日期 | 关联 |
| --- | --- | --- | --- | --- | --- | --- |
| DB-OBS-001 | 📝 已裁决·未实施 | 可观测性 | 仪表不得改变媒体路径与锁顺序 | 2026-10-21 | — | [任务看板](task-board.md#tb-obs-001) |
| DB-OBS-002 | 📝 已裁决·未实施 | 可观测性 | TCP 上的丢包以卡顿时长度量 | 2026-10-21 | — | [任务看板](task-board.md#tb-obs-001) |
| DB-OBS-003 | ✔️ 已裁决·当前实现符合 | 可观测性 | 速率只能由已关闭区间导出 | 2026-10-21 | — | [任务看板](task-board.md#tb-obs-001) |
| DB-POWER-001 | 🔶 已裁决·代码已实施 | 功耗与热 | 热降档是临时覆盖，不写回用户设置 | 2026-10-21 | — | [任务看板](task-board.md#tb-power-001) |
| DB-POWER-002 | 🔶 已裁决·代码已实施 | 功耗与热 | 无观看者暂停采集，首个配置前不暂停 | 2026-10-21 | — | [任务看板](task-board.md#tb-power-001) |
| DB-RENDER-001 | ✔️ 已裁决·当前实现符合 | 渲染显示 | 客户端保持内容宽高比 | 2026-10-20 | 2026-10-20 | [任务看板](task-board.md#tb-client-003) |
| DB-RENDER-002 | ✔️ 已裁决·当前实现符合 | 渲染显示 | 抗锯齿上限由渲染路径决定 | 2026-10-20 | 2026-10-20 | [任务看板](task-board.md#tb-client-003) |
| DB-RENDER-003 | ✔️ 已裁决·当前实现符合 | 渲染显示 | 限帧与合并在两端分工 | 2026-10-20 | 2026-10-20 | [任务看板](task-board.md#tb-client-004) |
| DB-DOC-002 | ✅ 已验证 | 文档协作 | Javadoc 与英文注释 | 2026-10-18 | 2026-10-19 | [任务看板](task-board.md) |
| DB-PLATFORM-002 | ✅ 已验证 | 平台安全 | 双语资源跟随系统 | 2026-10-18 | 2026-10-19 | [任务看板](task-board.md) |
| DB-AUDIO-001 | 🔶 已裁决·代码已实施 | 平台安全 | 音频内录与降级策略 | 2026-10-17 | — | [音频计划](superpowers/plans/2026-10-21-audio.md) |
| DB-CAP-001 | ⏳ 待验证 | 采集编码 | 采集直通编码输入 | 2026-10-17 | — | [媒体架构采集与编码](media-architecture.md) |
| DB-CAP-002 | ⏳ 待验证 | 采集编码 | 单采集源多会话扇出 | 2026-10-17 | — | [媒体架构采集与编码](media-architecture.md) |
| DB-CAP-003 | ⏳ 待验证 | 采集编码 | API 25 不是能力上限 | 2026-10-17 | — | [媒体架构能力探测](media-architecture.md) |
| DB-CODEC-001 | ⏳ 待验证 | 采集编码 | H.264 为兼容基线 | 2026-10-17 | — | [媒体架构编码格式](media-architecture.md) |
| DB-CODEC-002 | 📝 已裁决·未实施 | 采集编码 | 缓存配置与最近 IDR | 2026-10-17 | — | [媒体架构编码缓存](media-architecture.md) |
| DB-CODEC-003 | 📝 已裁决·未实施 | 采集编码 | 分辨率需与编码器匹配 | 2026-10-17 | — | [媒体架构参数与配置](media-architecture.md) |
| DB-CODEC-004 | 📝 已裁决·未实施 | 采集编码 | 帧率参数只是目标提示 | 2026-10-17 | — | [媒体架构参数语义](media-architecture.md) |
| DB-CODEC-005 | 📝 已裁决·未实施 | 采集编码 | 码率与 IDR 间隔可配 | 2026-10-17 | — | [媒体架构帧率码率与分辨率](media-architecture.md) |
| DB-CODEC-006 | ⏳ 待验证 | 采集编码 | 编解码能力运行时探测 | 2026-10-17 | — | [媒体架构能力探测](media-architecture.md) |
| DB-CODEC-007 | 📝 已裁决·未实施 | 采集编码 | 编码格式基线与切换 | 2026-10-17 | — | [媒体架构编码格式](media-architecture.md) |
| DB-CODEC-008 | ⏳ 待验证 | 采集编码 | 分辨率帧率按场景分档 | 2026-10-17 | — | [媒体架构帧率码率与分辨率](media-architecture.md) |
| DB-CODEC-009 | 🟢 已批准·待实施/验证 | 编码框架 | 静态 SPI 与单活动流 | 2026-10-17 | — | [编码框架设计](codec-framework.md) |
| DB-CODEC-010 | 🟢 已批准·待实施/验证 | 编码框架 | 固定扩展点与静态注册 | 2026-10-17 | — | [编码框架设计](codec-framework.md) |
| DB-CODEC-011 | 🟢 已批准·待实施/验证 | 编码框架 | 描述符与运行参数分离 | 2026-10-17 | — | [编码框架设计](codec-framework.md) |
| DB-CODEC-012 | 🟢 已批准·待实施/验证 | 编码框架 | 访问单元字段与 epoch 校验 | 2026-10-17 | — | [编码框架设计](codec-framework.md) |
| DB-CODEC-013 | 🟢 已批准·待实施/验证 | 编码框架 | 可加入点独立于关键帧 | 2026-10-17 | — | [编码框架设计](codec-framework.md) |
| DB-CODEC-014 | 🟢 已批准·待实施/验证 | 编码框架 | 编码切换不保证零间隙 | 2026-10-17 | — | [编码框架设计](codec-framework.md) |
| DB-CTRL-001 | 📝 已裁决·未实施 | 平台安全 | 无障碍手势为反控基线 | 2026-10-17 | — | [媒体架构反控](media-architecture.md) |
| DB-DOC-001 | 📜 已裁决·文档约定生效 | 文档协作 | 文档图表与术语规范 | 2026-10-17 | — | [文档索引](README.md) |
| DB-MULTI-001 | 📝 已裁决·未实施 | 传输会话 | 单编码扇出与单控制租约 | 2026-10-17 | — | [媒体架构多客户端](media-architecture.md) |
| DB-MULTI-002 | 📝 已裁决·未实施 | 传输会话 | 缩放只改本地渲染 | 2026-10-17 | — | [媒体架构客户端显示与解码边界](media-architecture.md) |
| DB-MULTI-003 | 📝 已裁决·未实施 | 传输会话 | 兼容单流优先多档 | 2026-10-17 | — | [媒体架构兼容单流与多档编码](media-architecture.md) |
| DB-MULTI-004 | 📝 已裁决·未实施 | 传输会话 | 单流与多档流策略 | 2026-10-17 | — | [媒体架构兼容单流与多档编码](media-architecture.md) |
| DB-MULTI-005 | 🟢 已批准·待实施/验证 | 编码框架 | 异构客户端与队列策略 | 2026-10-17 | — | [编码框架设计](codec-framework.md) |
| DB-NET-001 | ✔️ 已裁决·当前实现符合 | 传输会话 | gRPC 控制面与媒体统一承载 | 2026-10-17 | — | [媒体架构传输](media-architecture.md) |
| DB-NET-002 | 🟢 已批准·待实施/验证 | 编码框架 | 传输与编解码解耦 | 2026-10-17 | — | [编码框架设计](codec-framework.md) |
| DB-PLATFORM-001 | ✔️ 已裁决·当前实现符合 | 平台安全 | minSdk 25 为兼容下限 | 2026-10-17 | — | [媒体架构权限与版本门控](media-architecture.md) |
| DB-SEC-001 | 📝 已裁决·未实施 | 平台安全 | 认证与传输加密目标 | 2026-10-17 | — | [媒体架构安全模型](media-architecture.md) |
| DB-SESSION-001 | ✔️ 已裁决·当前实现符合 | 传输会话 | 会话所有权与窗口解耦 | 2026-10-17 | — | [窗口与会话设计](windowing-session-design.md) |
| DB-SESSION-002 | 📝 已裁决·未实施 | 传输会话 | HELLO 与 IDR 前等待 | 2026-10-17 | — | [媒体架构新客户端加入](media-architecture.md) |
| DB-WIN-001 | 📝 已裁决·未实施 | 窗口显示 | 跨应用悬浮窗为基线 | 2026-10-17 | — | [窗口与会话设计窗口选型](windowing-session-design.md) |
| DB-WIN-002 | 📝 已裁决·未实施 | 窗口显示 | 按场景选择渲染视图 | 2026-10-17 | — | [窗口与会话设计实现要点](windowing-session-design.md) |
| DB-WIN-003 | ✔️ 已裁决·当前实现符合 | 窗口显示 | 方案 B 悬浮窗 ↔ 全屏 Activity（2026-10-21 改写） | 2026-10-17 | — | [窗口与会话设计方案对比](windowing-session-design.md) |

## 📎 裁决详情

> 每条含当前裁决、理由、重新评估条件 / 验证证据与全部关联文档；概览表只做导航。实施证据的完整报告由 [任务看板](task-board.md) 单源持有，本页只保留结论与指针。

### 可观测性

<a id="db-obs-001"></a>
<details><summary>DB-OBS-001 仪表不得改变媒体路径与锁顺序（📝 已裁决·未实施 · 2026-10-21）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：📝 已裁决·未实施 / 可观测性 / 2026-10-21 / —
- **备注**：裁决已落在代码里（O1–O6）；「未实施」指的是关闭态零开销与开启态开销上界都还没有 A/B 实测
- **当前裁决**：统计只能**观测**，不能改变媒体路径、时序或锁顺序。三条硬约束：①热路径每次探针最多一次 `nanoTime()` 与若干无锁计数，禁止字符串拼接、装箱与加锁；②排序、分位与格式化只允许发生在 1 s/5 s 的聚合线程上；③统计必须可关，关闭时每个探针只是一次 `volatile` 读，不分配窗口、不取快照、不启定时器。任何以「估算」得来的量必须带 `approx.` 前缀出现在输出里。
- **理由**：仪表一旦进入临界区或热路径，就会把被测对象改变；一旦关不掉，就成了线上性能回退。把这三条写成裁决，才能让后续批次在评审时有可判定的红线，而不是靠自觉。
- **重新评估条件 / 验证证据**：需要同一场景 A/B（指标关 vs 开）证明 `enc.cap2out.p50`、`dec.lat.p50` 差异 ≤ 5%、`pres.interval` 分布无系统偏移，并证明关闭态不产生任何样本（单测已断言窗口 `count` 恒为 0）；任一不满足即视为未实现并回退。代码证据：`core/metrics/PipelineMetrics.NONE` 关闭态 + 各批探针；实施证据见任务看板 [TB-OBS-001](task-board.md#tb-obs-001)。边界：**A/B 开销与设备行为未实测**。
- **关联文档**：[本计划](superpowers/plans/2026-10-21-transport-observability.md) 5.2、7.2、8.1、[任务看板](task-board.md#tb-obs-001)

</details>

<a id="db-obs-002"></a>
<details><summary>DB-OBS-002 TCP 上的丢包以卡顿时长度量（📝 已裁决·未实施 · 2026-10-21）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：📝 已裁决·未实施 / 可观测性 / 2026-10-21 / —
- **备注**：裁决已成文，判据的阈值待基线数据校准
- **当前裁决**：当前传输是 gRPC 双向流（TCP 承载），**没有 RTP 语义的丢包**：可靠传输只会让帧「晚到」，不会让帧「变少」。因此 [DV-001](#待决策--待验证) 的判据不是丢包率，而是 `rx.rowd`（相对单向延迟）、`rx.stall`（到达空窗次数与时长）、`pres.late` 与 `rtt` 的**联合归因**；`rx.seqGap` 保持为 0 是「拥塞而不是丢帧」的判别条件。
- **理由**：把 RTP 的口径（`packetsLost`）搬到 TCP 上会得到恒为 0 的假指标，从而把「卡顿」误判成「没有问题」。WebRTC 的 `freezeCount` 定义（间隔 ≥ `max(3×均值, 均值+150 ms)`）反而是可直接对照的口径，本项目在 `ArrivalTracker` 与解码呈现侧都采用了同一形式。
- **重新评估条件 / 验证证据**：需要设备基线：同一对设备与同一 AP 下分别在静止/滚动/视频三种画面采集 `rx.rowd.p95`、`rx.stall`、`rx.arr.p99`、`pres.late.p95`、`rtt.p95`、`tx.send.p95`、`enc.cap2out.p95`，并做一次**注入拥塞对照组**：若拥塞注入后三者在时间上同步抬升而 `rx.seqGap` 仍为 0，仪表才算可用；若指标无变化，先修仪表再谈 DV-001。代码证据：`ArrivalTracker`、`H264Decoder` 的呈现探针与 `RttEstimator`；实施证据见任务看板 [TB-OBS-001](task-board.md#tb-obs-001) 与 [TB-OBS-002](task-board.md#tb-obs-002)。边界：**全部为设计裁决，无实测数据**。
- **关联文档**：[本计划](superpowers/plans/2026-10-21-transport-observability.md) 4.2、6.1、6.6、[任务看板](task-board.md#tb-obs-001)

</details>

<a id="db-obs-003"></a>
<details><summary>DB-OBS-003 速率只能由已关闭区间导出（✔️ 已裁决·当前实现符合 · 2026-10-21）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：✔️ 已裁决·当前实现符合 / 可观测性 / 2026-10-21 / —
- **备注**：「当前实现符合」指代码与单测已按本条落地（`MetricsRate`/`RateSampler`）；设备侧的读数合理性仍属 O7 基线，未验证
- **当前裁决**：**任何速率字段都必须是「两个读数之差 ÷ 实测间隔」，不得由累计计数器除以名义周期导出。** 三条推论：①计数器（`Counter`）在快照里保持累计语义，读者要做「本周期增量」自己差分；②区间由读者自己关闭（`RateSampler` 持基线，用 scope 的 `NanoClock` 量实际经过时间），因此同一个 scope 可以被 1 s 与 5 s 两个读者并存读取，互不干扰；③没有测量区间时字段整条省略（日志）或打 `n/a`（HUD），不允许用 `0` 冒充「测过且为 0」。
- **理由**：设备验证 r2 实测 `enc.auRate` 从 8.4 单调膨胀到 8 337、`enc.bitrate` 打到 151 Mbps（真实码流 0.1–2 Mbps），根因就是 `snapshot()` 里累计值除以本周期；这类错误不会报错，只会让「速率」变成「平均值 × 会话时长 / 周期」，任何据此得出的容量、队列或判据结论都失效。把区间与读者绑定还顺带解决了「1 s HUD 与 5 s 日志共用一份基线」的隐性错误——后跑的读者只会看到前一个读者剩下的碎片。
- **重新评估条件 / 验证证据**：若将来引入更高频（<100 ms）读者或需要跨进程合并速率，应重新评估「读者自持基线」是否仍成立（例如改为按时间戳分桶的 `RateWindow`）。验证证据：`RateSamplerTest` 断言第二个区间不因累计值而翻倍、实测周期优先于名义周期、无区间返回 `MetricsRate.EMPTY`；`PipelineMetricsTest` 只断言累计计数器；`MetricsFormatterTest` 断言未测量时省略 `enc.auRate`/`enc.bitrate`、无区间时 HUD 打 `n/a`。实施证据见任务看板 [TB-OBS-001](task-board.md#tb-obs-001)。边界：**设备侧读数合理性未验证**（验证棒点位：`enc.auRate` 应与 `enc.outInterval.p50` 的倒数同量级、`enc.bitrate` 应与设置码率同量级）。
- **关联文档**：[本计划](superpowers/plans/2026-10-21-transport-observability.md) 3.2、5.1、[DB-OBS-001](#db-obs-001)、[任务看板](task-board.md#tb-obs-001)

</details>

### 功耗与热

<a id="db-power-001"></a>
<details><summary>DB-POWER-001 热降档是临时覆盖，不写回用户设置（🔶 已裁决·代码已实施 · 2026-10-21）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：🔶 已裁决·代码已实施 / 功耗与热 / 2026-10-21 / —
- **备注**：代码与 JVM 用例已落地（提交 `377e581`、`00ee006`、`68bfd5e`、`45ed888`，实施证据见任务看板）；设备侧是否真的触发降档与恢复未验证
- **当前裁决**：热状态只改变**当前生效**的帧率与采集比例，绝不写回偏好。档位阶梯：`NONE`/`LIGHT`/`MODERATE` 不动（平台自身仍在正常运行区间）；`SEVERE` 帧率降到 30；`CRITICAL`/`EMERGENCY`/`SHUTDOWN` 帧率 30 且采集比例降到 50%。温度回落到低档、关闭「过热时自动降档」开关、或服务重启时，生效值自动回到用户设定；关闭开关会立即恢复。API 25–28 没有热状态，阶梯恒为 `NONE`；未知或未来状态值同样按 `NONE` 处理。降档复用棒一的 `switchDescriptor` 流中热切换（已知停顿 ≤209 ms），尺寸变化走「先换编码器再改显示」的既有编排，仅帧率变化走防抖重建。
- **理由**：把降档写成偏好会永久改变用户的画质选择，且设备降温后没有任何事件能把它改回来；写成「生效值 = 用户值 × 档位上限」后，恢复只是同一函数的下一次求值，不存在补偿逻辑。只动上半段状态是因为在中低档降质量换不来可测量的收益，却会让用户以为画质无故变差。
- **重新评估条件 / 验证证据**：需要设备实测：模拟 `SEVERE`/`CRITICAL` 后 `enc.outInterval.p50`、`enc.cap2out.p50` 与采集尺寸是否按档位变化，回落/关开关后是否恢复到用户值，以及切换停顿是否与已知 ≤209 ms 一致。若某机型在 `MODERATE` 已出现明显掉帧，才评估把第一档下移。代码证据：`core/stream/ThermalThrottlePolicy`（档位映射，纯 JVM 用例）、`service/ThermalMonitor`（API 29 门控）、`ScreenShareService.applyThermalTier`；实施证据见任务看板 [TB-POWER-001](task-board.md#tb-power-001)。边界：**未做设备验证，功耗数值未建模**。
- **关联文档**：本地录屏与投屏问答第 8 问（发烫与功耗的根因与手段清单）、[任务看板](task-board.md#tb-power-001)、[DV-024](#待决策--待验证)

</details>

<a id="db-power-002"></a>
<details><summary>DB-POWER-002 无观看者暂停采集，首个配置前不暂停（🔶 已裁决·代码已实施 · 2026-10-21）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：🔶 已裁决·代码已实施 / 功耗与热 / 2026-10-21 / —
- **备注**：代码与 JVM 用例已落地；设备侧「0 观看者时确实不再合成/编码」与「首个观看者首帧时延」未验证
- **当前裁决**：观看者数量为 0 且运行时已发布首个配置时，把 `VirtualDisplay` 的输出面摘下（`setSurface(null)`），屏幕合成与编码同时停止；编码器保持分配、已发布的描述符继续有效，首个观看者到达时重新绑定当前编码器面（JPEG 路径绑定 `ImageReader` 面）并请求一个关键帧。运行时尚未 `READY` 时**不得**暂停：H.264 的 SPS/PPS 来自首个输出格式，没有帧就永远发不出配置，客户端会一直收到 `NOT_READY`。观看者数量未知（无 hub 或正在拆卸）时保持现状。音频沿用既有 `AudioCapturePolicy` 的同一条规则。
- **理由**：第 8 问的四条发烫根因里，「没有客户端时也持续编码」是唯一不需要换编码格式就能消除的一条；暂停而不是关闭编码器，是因为重建编码器会推进 `configEpoch`、让客户端重新同步，而暂停/恢复只是一次面绑定。
- **重新评估条件 / 验证证据**：需要设备实测：0 观看者时 `enc.auRate`/`enc.bitrate` 停更、`enc.cap2out` 无样本、CPU/温度曲线下降；首个观看者从加入到首帧的时延（预期 ≤1 s 轮询 + 一次 IDR）。若某机型 `setSurface(null)` 后重新绑定黑屏，则改为「暂停即关闭并重建运行时」并重新评估首帧时延。代码证据：`core/stream/ViewerCapturePolicy`（纯 JVM 用例）、`ScreenShareService.syncVideoCapture`/`pauseCapture`/`resumeCapture`；实施证据见任务看板 [TB-POWER-001](task-board.md#tb-power-001)。边界：**未做设备验证；暂停期间编码器仍占用硬件实例**。
- **关联文档**：本地录屏与投屏问答第 8 问（发烫与功耗的根因与手段清单）、[任务看板](task-board.md#tb-power-001)、[DV-025](#待决策--待验证)、[DB-CAP-002](#db-cap-002)

</details>

### 采集与编码

<a id="db-cap-001"></a>
<details><summary>DB-CAP-001 采集直通编码输入（⏳ 待验证 · 2026-10-17）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：⏳ 待验证 / 采集编码 / 2026-10-17 / —
- **备注**：已实施、待设备验证
- **当前裁决**：保留 `MediaProjection` 作为普通 App 的合规采集入口；`VirtualDisplay` 直接输出到 `MediaCodec` 输入 Surface，稳态不再经过 `ImageReader`、Bitmap 与软件 JPEG。
- **理由**：普通 App 没有更合规的跨应用采集入口；可去掉 RGBA 到 Bitmap 的逐像素拷贝、全尺寸 Bitmap 与软件 JPEG 的 CPU 开销。
- **重新评估条件 / 验证证据**：若目标设备的 `MediaProjection` 与编码器输入 Surface 组合不可用或稳定性不达标，降级到 JPEG 快照或低帧率兼容模式；需 API 25/26/29/33/34/35 与目标厂商实测。代码证据：媒体路径批次 B（`c027651`）已把 `VirtualDisplay` 直接接到 `MediaCodec` 输入 Surface，完整实施证据见任务看板 [TB-MEDIA-003](task-board.md#tb-media-003)；边界：**仅 JVM/fake 与编译验证，未做设备/真机/模拟器验证**。
- **关联文档**：[媒体架构采集与编码](media-architecture.md)、[项目现状](project-assessment.md)、[路线图阶段 2](optimization-roadmap.md)

</details>

<a id="db-cap-002"></a>
<details><summary>DB-CAP-002 单采集源多会话扇出（⏳ 待验证 · 2026-10-17）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：⏳ 待验证 / 采集编码 / 2026-10-17 / —
- **备注**：已实施、待设备验证
- **当前裁决**：一个 `MediaProjection` 与一个 `VirtualDisplay` 构成采集源，通常只跑一路编码（H.264 兼容档保底；高能力场景可按 DB-MULTI-004 增加有限档位）；每个客户端拥有独立网络会话、有界发送队列与解码器，服务端共享采集与编码结果。
- **理由**：采集与编码是设备侧最重的资源；每客户端独立会话才能隔离慢客户端与断线；解码器属于客户端，不应在服务端按客户端复制。
- **重新评估条件 / 验证证据**：若确认需要多档清晰度，按 DB-MULTI-003 / DB-MULTI-004 评估有限数量编码器；需多客户端、慢客户端与断线重连实测。代码证据：媒体路径批次 B（`c027651`）已由 `StreamHub` 为每个客户端建立独立会话与有界队列，批次 G（`101f685`）按共同兼容能力接纳新客户端，完整实施证据见任务看板 [TB-MEDIA-003](task-board.md#tb-media-003) 与 [TB-FW-003](task-board.md#tb-fw-003)；边界：**仅 JVM/fake 与编译验证，未做设备/真机/模拟器验证**。
- **关联文档**：[媒体架构采集与编码](media-architecture.md)、[媒体架构多客户端](media-architecture.md)

</details>

<a id="db-cap-003"></a>
<details><summary>DB-CAP-003 API 25 不是能力上限（⏳ 待验证 · 2026-10-17）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：⏳ 待验证 / 采集编码 / 2026-10-17 / —
- **备注**：已实施、待设备验证；文档约定继续生效
- **当前裁决**：API 25/minSdk 只决定最低兼容与回退路径，不是能力上限。不得仅凭 API 等级、手机/平板/车机形态推断 codec、profile/level、分辨率或帧率能力；高版本系统、旗舰手机/平板或其他高性能设备可按运行时探测使用高级能力。
- **理由**：同一 API 等级的设备编码器差异很大；把最低兼容版本或某一类设备的能力当成全产品上限，会人为压制高能力设备，也会误判低版本设备。
- **重新评估条件 / 验证证据**：每类设备仍需实测；若发现某 API 等级普遍存在能力缺口，只能形成“该版本常见回退档”的经验数据，不能升级为产品硬上限。代码证据：媒体路径接线批次 E 已用 `MediaCodecList`/`VideoCapabilities` 运行时探测（`support/CodecCapabilityProbe`），批次 G 在 `StreamHub.join` 按共同兼容能力选择候选 codec，均不按 API 等级或设备形态推断；完整实施证据见任务看板 [TB-MEDIA-003](task-board.md#tb-media-003) 与 [TB-FW-003](task-board.md#tb-fw-003)。边界：**仅 JVM/fake 与编译验证，未做设备/真机/模拟器验证**。
- **关联文档**：[媒体架构能力探测](media-architecture.md)、[产品语境](product-context.md)、[路线图原则](optimization-roadmap.md)

</details>

<a id="db-codec-001"></a>
<details><summary>DB-CODEC-001 H.264 为兼容基线（⏳ 待验证 · 2026-10-17）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：⏳ 待验证 / 采集编码 / 2026-10-17 / —
- **备注**：已实施、待设备验证
- **当前裁决**：H.264/AVC Baseline（能力允许时再协商 Main/High）为兼容基线与回退档，不是能力上限；HEVC 在服务端与客户端均探测支持且实测收益明确时可选；AV1 只作为设备明确支持时的后续/高级选项，第一版不实现；JPEG/PNG 仅用于快照、缩略图、过渡帧或编码器不可用时的降级。
- **理由**：H.264 硬件支持最广、浏览器与播放器兼容性最好；HEVC 的码率优势伴随专利、授权与支持差异；JPEG 的 CPU 与码率不适合作为稳态视频编码；把 H.264 当上限会浪费高能力设备。
- **重新评估条件 / 验证证据**：目标设备矩阵的编码器/解码器能力、同画质码率、CPU、发热与分发政策；HEVC 未实测前不设为默认，不能仅凭压缩率推断收益；AV1 需设备与工具链明确支持后再评估。代码证据：媒体路径批次 B（`c027651`）接通服务端 H.264 采集直通，批次 C（`9bc7f1b`）接通客户端解码与 Surface 渲染，批次 D（`8a9c921`）保留 JPEG 为经框架运行的降级模式，完整实施证据见任务看板 [TB-FW-003](task-board.md#tb-fw-003)；边界：**仅 JVM/fake 与编译验证，未做设备/真机/模拟器验证**。
- **关联文档**：[媒体架构编码格式](media-architecture.md)、[路线图阶段 2](optimization-roadmap.md)

</details>

<a id="db-codec-002"></a>
<details><summary>DB-CODEC-002 缓存配置与最近 IDR（📝 已裁决·未实施 · 2026-10-17）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：📝 已裁决·未实施 / 采集编码 / 2026-10-17 / —
- **当前裁决**：缓存编码配置、最近 IDR 及其后的编码单元，按字节数与时间设上限；新客户端从 config+IDR 加入；慢客户端丢到下一个 IDR 或断开，不随意丢中间 P 帧。
- **理由**：H.264 的 P 帧依赖前序参考帧；随意丢帧会造成花屏并向后续帧扩散，直到下一个 IDR。
- **重新评估条件 / 验证证据**：编码器支持时间分层或 SVC 时可细化丢弃策略；需慢客户端、重连、新客户端加入的实测；在此之前保持 GOP 感知。
- **关联文档**：[媒体架构编码缓存](media-architecture.md)、[媒体架构多客户端](media-architecture.md)

</details>

<a id="db-codec-003"></a>
<details><summary>DB-CODEC-003 分辨率需与编码器匹配（📝 已裁决·未实施 · 2026-10-17）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：📝 已裁决·未实施 / 采集编码 / 2026-10-17 / —
- **当前裁决**：分辨率由 VirtualDisplay 与编码器尺寸共同决定且必须匹配，按编码器能力与对齐要求取整；只 resize VirtualDisplay 不改变编码输出。分辨率或旋转变化时通常重建编码器与输入 Surface，发布新 `configEpoch`、SPS/PPS 与 IDR，网络会话不断开。
- **理由**：编码输出宽高由 `MediaFormat` 决定；只改采集尺寸会造成隐式缩放或尺寸不一致；重建编码器后旧配置与旧参考帧失效。
- **重新评估条件 / 验证证据**：设备是否支持编码器运行时重配、固定采集尺寸是否只作为回退档；需分辨率与旋转切换、弱网重连实测。
- **关联文档**：[媒体架构参数与配置](media-architecture.md)、[路线图阶段 2](optimization-roadmap.md)

</details>

<a id="db-codec-004"></a>
<details><summary>DB-CODEC-004 帧率参数只是目标提示（📝 已裁决·未实施 · 2026-10-17）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：📝 已裁决·未实施 / 采集编码 / 2026-10-17 / —
- **备注**：严格限帧待验证
- **当前裁决**：`KEY_FRAME_RATE` 是目标提示，不是严格上限；静态画面与 VFR 不保证满帧。严格限帧需在 GPU/采集上游调度，或按系统版本与设备能力逐项验证；minSdk 25 基线不能依赖高版本参数。
- **理由**：编码器可自行决定实际输出帧率；不同系统版本与厂商实现差异大，高版本参数不能作为 25 基线。
- **重新评估条件 / 验证证据**：产品要求固定帧率上限时，需目标设备的实际输出帧率分布、发热与 GPU 上游调度方案验证。
- **关联文档**：[媒体架构参数语义](media-architecture.md)、[路线图阶段 3](optimization-roadmap.md)

</details>

<a id="db-codec-005"></a>
<details><summary>DB-CODEC-005 码率与 IDR 间隔可配（📝 已裁决·未实施 · 2026-10-17）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：📝 已裁决·未实施 / 采集编码 / 2026-10-17 / —
- **当前裁决**：码率可配置，通常可在运行时调整；IDR 间隔可配置。运行中的同步请求（新客户端加入、错误恢复、慢客户端追帧）必须合并并限频，不能每个请求立即触发 IDR。
- **理由**：IDR 显著增大瞬时码率并影响所有客户端；运行中调整的生效粒度依赖设备码率控制实现。
- **重新评估条件 / 验证证据**：码率调整的实际生效时间与步进；IDR 限频阈值需按设备与网络实测确定。
- **关联文档**：[媒体架构帧率码率与分辨率](media-architecture.md)、[媒体架构编码缓存](media-architecture.md)

</details>

<a id="db-codec-006"></a>
<details><summary>DB-CODEC-006 编解码能力运行时探测（⏳ 待验证 · 2026-10-17）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：⏳ 待验证 / 采集编码 / 2026-10-17 / —
- **备注**：已实施、待设备验证
- **当前裁决**：服务端编码能力与客户端解码能力分别做运行时探测：codec、profile/level、宽高与对齐、帧率、码率、硬件实例、Surface 输入/输出、热与功耗状态；探测结果经 HELLO/STREAM_INFO 交换，字段可缺省。服务端“自动”必须是能力驱动：在兼容与可靠性得到保证的前提下，按画质、码率、功耗、网络和悬浮窗/全屏场景选择档位；未实测前保持可回退 H.264，不做盲目自动切换。
- **理由**：静态能力表无法覆盖厂商差异与运行时热/电约束；只看 API 等级或设备形态会误判；能力驱动才能既保兼容又发挥高能力设备。
- **重新评估条件 / 验证证据**：需要编码器/解码器能力矩阵与同场景实测；确认探测字段在旧客户端缺省时的降级行为；验证自动切换的稳定性、回退时延与用户可见性。代码证据：批次 E 新增 `CodecCapability`、`ClientCapabilities` 能力模型与 `support/CodecCapabilityProbe`，以 `MediaCodecList`/`VideoCapabilities` 探测客户端解码器宽高/帧率/码率/adaptive playback，并提供服务端编码器可用性探测入口（`hasEncoder` 尚未接入选档）；探测失败按不可用处理，**不按 API 等级或设备形态推断**。客户端在 HELLO 声明能力，服务端解析缺省字段并按 H.264 兼容档处理。批次 G 在 `StreamHub.join` 按共同兼容能力选择候选 codec（profile/level 声明不一致即不兼容，宽高/帧率/码率取共同下界）。完整实施证据见任务看板 [TB-MEDIA-003](task-board.md#tb-media-003)。边界：JVM 不触 `android.media.*`，探测结果与实际设备能力的一致性、热/功耗状态、自动切换稳定性与回退时延均未实测；**仅 JVM/fake 与编译验证，未做设备/真机/模拟器验证**。
- **关联文档**：[媒体架构能力探测](media-architecture.md)、[媒体架构客户端显示与解码边界](media-architecture.md)、[路线图阶段 2](optimization-roadmap.md)

</details>

<a id="db-codec-007"></a>
<details><summary>DB-CODEC-007 编码格式基线与切换（📝 已裁决·未实施 · 2026-10-17）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：📝 已裁决·未实施 / 采集编码 / 2026-10-17 / —
- **备注**：AV1 不在第一版范围
- **当前裁决**：H.264 是兼容基线而非固定选择；HEVC 在双方支持且实测稳定时启用；AV1 等更高级编码只作为设备明确支持时的后续/高级选项。不得声称 AV1 普遍存在或第一版已实现；codec 切换必须重建编解码配置并发布新 `configEpoch` + IDR。
- **理由**：更高压缩效率依赖双方能力、专利与实时硬件支持；未实测的强切会带来兼容与稳定性风险。
- **重新评估条件 / 验证证据**：HEVC 的同场景码率/画质/功耗收益；AV1 的硬件编码与解码覆盖率、授权与工具链；任一侧能力缺失时必须可回退。
- **关联文档**：[媒体架构编码格式](media-architecture.md)、[路线图阶段 2](optimization-roadmap.md)

</details>

<a id="db-codec-008"></a>
<details><summary>DB-CODEC-008 分辨率帧率按场景分档（⏳ 待验证 · 2026-10-17）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：⏳ 待验证 / 采集编码 / 2026-10-17 / —
- **备注**：能力探测与共同能力选档已实施、待设备验证；小窗/全屏场景分档未实施
- **当前裁决**：分辨率、帧率与码率按能力和场景分档向上扩展，不设“全产品统一低档上限”；悬浮小窗与全屏可以选择不同档位。旗舰手机/平板在编码器、散热、功耗与网络允许时可使用高档位；低端设备或异常编码器回退兼容档。
- **理由**：小窗与全屏的画质需求、功耗预算不同；把某类设备的上限定为全产品上限会浪费硬件能力；统一高档又会压垮低能力设备。
- **重新评估条件 / 验证证据**：各场景的档位边界、温升与功耗、网络承载需实测；确认小窗/全屏切换档位时 `configEpoch` 与 IDR 行为正确。代码证据：批次 E 的 `CodecCapability`/`CodecCapabilityProbe` 已探测客户端解码器的最大宽高、帧率与码率，并提供编码器可用性探测入口；批次 G 在 `StreamHub.join` 按共同兼容能力选择候选 codec；完整实施证据见任务看板 [TB-MEDIA-003](task-board.md#tb-media-003)。边界：只实现了能力驱动的共同档位选择，**小窗/全屏场景分档、温升/功耗阈值与切换行为未实施也未实测**；仅 JVM/fake 与编译验证，未做设备/真机/模拟器验证。
- **关联文档**：[媒体架构帧率码率与分辨率](media-architecture.md)、[窗口与会话设计](windowing-session-design.md)、[路线图阶段 3](optimization-roadmap.md)

</details>

### 编码框架

以下裁决来自本轮已批准的编码框架方案，用于固定设计契约；接口、队列与状态机的实施状态（含批次 A–H 的生产接线）统一记录在 [任务看板](task-board.md)。

<a id="db-codec-009"></a>
<details><summary>DB-CODEC-009 静态 SPI 与单活动流（🟢 已批准·待实施/验证 · 2026-10-17）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：🟢 已批准·待实施/验证 / 编码框架 / 2026-10-17 / —
- **当前裁决**：采用“静态 Codec SPI + 单活动流核心 + 可扩展 StreamHub”。第一版是单采集会话、单 `VirtualDisplay`、单采集 Surface、单活动流；用 H.264 与 JPEG 两条差异最大的实现验证同一契约；HEVC 用于验证 `MediaCodec` 骨架复用；AV1/HDR/B 帧/时域层只预留契约。
- **理由**：两条差异实现能暴露“codec 差异是否泄漏到公共层”；先做单流可以把队列、epoch 与状态机验证清楚，避免用多流掩盖契约缺陷。
- **重新评估条件 / 验证证据**：若新增 codec 必须修改公共层、JPEG 无法复用状态机，或多流需求超出 `StreamHub` 的注册与路由能力，先复审契约，不靠补丁扩展。验证需要契约阶段代码审查与 H.264/JPEG 端到端结果。
- **关联文档**：[可扩展编码框架设计](codec-framework.md)、[媒体架构](media-architecture.md)、[路线图阶段 2](optimization-roadmap.md)

</details>

<a id="db-codec-010"></a>
<details><summary>DB-CODEC-010 固定扩展点与静态注册（🟢 已批准·待实施/验证 · 2026-10-17）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：🟢 已批准·待实施/验证 / 编码框架 / 2026-10-17 / —
- **当前裁决**：扩展点固定为 `CodecDescriptor`、`EncoderFactory`、`DecoderFactory`、`CodecPolicy`、`PacketizerFactory(codec, transport)`，编译期静态注册。`DecoderFactory` 显式接收 `RenderTarget`：MediaCodec 类解码器输出到其 Surface，JPEG 类通过 `renderEncodedImage(EncodedImageFrame)` 输出压缩图像帧（显示侧解码）；解码/渲染错误统一为 `DecoderException`。编码输入 Surface 由 `Encoder.getInputSurface()` 暴露。公共层不解析 NAL/OBU/JPEG，不做动态插件加载或通用多媒体引擎。
- **理由**：把 codec 差异收敛到描述符、工厂与策略，公共层只处理流生命周期；静态注册避免运行时加载带来的安全、兼容与可测试性问题。RenderTarget 明确区分 Surface 渲染与压缩图像渲染，避免把访问单元误当解码帧、把压缩 JPEG 冒充已解码像素；单错误通道避免状态机分裂。
- **重新评估条件 / 验证证据**：若某项能力必须解析码流才能完成（例如按参考依赖丢弃），应下沉到 codec 适配层，不能上移公共层；若确需运行时下发 codec，必须另立安全与兼容裁决。`PacketizerFactory` 与 RTP 封包器只在 RTP 批次实现，不能提前冻结签名。
- **关联文档**：[可扩展编码框架设计](codec-framework.md)

</details>

<a id="db-codec-011"></a>
<details><summary>DB-CODEC-011 描述符与运行参数分离（🟢 已批准·待实施/验证 · 2026-10-17）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：🟢 已批准·待实施/验证 / 编码框架 / 2026-10-17 / —
- **当前裁决**：`StreamDescriptor` 与 `StreamParameters` 分离。描述符包含 `sessionInstanceId`、`streamId`、`configEpoch`、MIME、profile/level、coded/visible size、rotation、bitstreamFormat、`CodecConfigPart`、`ColorInfo`/`configHash`；只有解码关键变化推进 epoch。码率、帧率提示、关键帧间隔属于运行参数，不推进 epoch。
- **理由**：码率或帧率调整不应迫使所有客户端重建解码器；描述符作为解码器配置的唯一来源，避免客户端从 MIME 或分辨率猜测配置。
- **重新评估条件 / 验证证据**：本阶段保守处理：`visible size` 与 rotation 变化都生成新描述符并推进 epoch；**不增加同 epoch 的显示参数更新通道**；只有拿到裁剪/旋转与解码器行为实测证据后才评估放宽。裁决演进：早期设计曾考虑 `visible size` 只做渲染变换、不推进 epoch；两位复审与主 agent 最终裁决本批次也推进 epoch，旧表述不得再作为实施依据。验证：码率变化不产生新 epoch；coded/visible size 或旋转变化产生新 epoch 且客户端可恢复。
- **关联文档**：[可扩展编码框架设计](codec-framework.md)、[媒体架构参数与配置](media-architecture.md)

</details>

<a id="db-codec-012"></a>
<details><summary>DB-CODEC-012 访问单元字段与 epoch 校验（🟢 已批准·待实施/验证 · 2026-10-17）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：🟢 已批准·待实施/验证 / 编码框架 / 2026-10-17 / —
- **当前裁决**：`EncodedAccessUnit` 固定携带 `streamKey`、`configEpoch`、`sequence`、`ptsUs`、可选 `dtsUs`、`flags`、可加入点信息、可选 `temporalLayerId` 与不透明 `payload`；访问单元按解码顺序排列；`sequence` 在 `(streamKey, configEpoch)` 内单调递增，新 epoch 从 0 开始；接收端对每个 AU（不仅是可加入点）校验 `configEpoch`；公共层不解析负载。第一版只预留 B 帧与时域层字段，不实现重排或分层调度。
- **理由**：按解码顺序编号可以在没有 B 帧时保持简单；仅校验可加入点会让旧 epoch 的普通 AU 泄漏到新会话；保留 `dtsUs` 与 `temporalLayerId` 只是为未来契约留位，不应在第一版产生不可达分支。
- **重新评估条件 / 验证证据**：B 帧、时域层或 SVC 实施前必须单独验证重排、丢弃与客户端缓冲；若字段预留导致实现复杂度上升，优先删减而不是提前实现。验证：旧 epoch 普通 AU 被拒；新 epoch sequence 归零。
- **关联文档**：[可扩展编码框架设计](codec-framework.md)

</details>

<a id="db-codec-013"></a>
<details><summary>DB-CODEC-013 可加入点独立于关键帧（🟢 已批准·待实施/验证 · 2026-10-17）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：🟢 已批准·待实施/验证 / 编码框架 / 2026-10-17 / —
- **当前裁决**：可加入点独立于关键帧标记。H.264 第一版只从明确 IDR 加入；HEVC 第一版只从明确安全 IDR 加入，CRA/BLA/RASL/RADL 不作为可加入点；AV1 由未来策略判断；MJPEG 每个完整帧可加入。客户端状态为 `NEGOTIATING`、`WAIT_CONFIG`、`WAIT_RANDOM_ACCESS`、`STREAMING`、`RESYNCING`、`CLOSED`。
- **理由**：关键帧/RAP 不必然是可解码起点：H.264 从非 IDR 加入可能花屏，HEVC CRA 可能依赖前导图像，JPEG 则每帧独立。统一“关键帧即可加入”会把正确性问题藏进设备差异。
- **重新评估条件 / 验证证据**：设备编码器的同步标记可靠性、HEVC CRA 是否未来开放、等待与重同步超时都需实测。验证：非可加入点不会被当作接入点；六个状态转换可观测且可恢复。客户端超时本阶段明确延后，不伪造已实现。
- **关联文档**：[可扩展编码框架设计](codec-framework.md)、DB-SESSION-002

</details>

<a id="db-codec-014"></a>
<details><summary>DB-CODEC-014 编码切换不保证零间隙（🟢 已批准·待实施/验证 · 2026-10-17）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：🟢 已批准·待实施/验证 / 编码框架 / 2026-10-17 / —
- **当前裁决**：编码切换：第一版单 `VirtualDisplay`、无 GPU Fanout，**不保证零间隙**。流程为准备新编码器、客户端保持最后一帧、切换采集输出 Surface、发布新描述符/配置/可加入点、客户端重配或重建解码器、释放旧编码器；网络、认证与控制会话不重建。切换时描述符必须先于新 epoch 首个 AU。
- **理由**：同一采集 Surface 在切换窗口内无法同时稳定供给两个编码器；把“不重建会话”与“零间隙”分开承诺，避免把未实现的能力写成保证。
- **重新评估条件 / 验证证据**：切换耗时、黑帧时长、失败回退与客户端确认超时需实测；若产品要求零间隙，先评估 GPU Fanout 的设备能力、功耗与实现成本。本阶段旧编码器以新 epoch 首个 join point 作为释放信号，不实现客户端 ack/超时。
- **关联文档**：[可扩展编码框架设计](codec-framework.md)、[媒体架构](media-architecture.md)、[路线图阶段 3](optimization-roadmap.md)

</details>

<a id="db-multi-005"></a>
<details><summary>DB-MULTI-005 异构客户端与队列策略（🟢 已批准·待实施/验证 · 2026-10-17）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：🟢 已批准·待实施/验证 / 编码框架 / 2026-10-17 / —
- **当前裁决**：异构客户端策略：第一版单流取共同兼容能力；新客户端不得使已有客户端静默降级；单流不兼容时只能快照、经用户可见提示的全局兼容降级或明确拒绝，多流后续解决。慢客户端使用独立有界队列：参考帧编码在溢出、单个 AU 超限或过期破坏可解码性时清空并进入 `RESYNCING`，只从下一个同 epoch 可加入点恢复；JPEG 只保留最新完整帧并继续，不进入无意义 `RESYNCING`。参考帧编码不得任意丢中间帧。
- **理由**：静默降级会破坏已有客户端已接受的参数与画质预期；参考帧编码丢中间帧会造成花屏扩散；JPEG 的“最新帧覆盖”不能直接套用到 H.264，反之亦然。
- **重新评估条件 / 验证证据**：队列字节/时间上限、重同步与连接超时、用户提示方式需实测；若需要多流，按 DB-MULTI-003 / DB-MULTI-004 另立阶段，不在第一版伪造。验证：JPEG 溢出不 resync；参考帧溢出/过期 resync。
- **关联文档**：[可扩展编码框架设计](codec-framework.md)、DB-MULTI-003、DB-MULTI-004

</details>

<a id="db-net-002"></a>
<details><summary>DB-NET-002 传输与编解码解耦（🟢 已批准·待实施/验证 · 2026-10-17）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：🟢 已批准·待实施/验证 / 编码框架 / 2026-10-17 / —
- **当前裁决**：传输解耦：媒体面 TCP 承载完整访问单元；若以 gRPC 流承载媒体，也按完整访问单元封装；控制面继续使用 gRPC。媒体传输必须能下发描述符；同一客户端同一 `configEpoch` 内，描述符先于首个访问单元。RTP 由 `PacketizerFactory(codec, transport)` 处理分片与时间戳；WebRTC 作为独立媒体栈，不强行套用自定义访问单元管线。公共层不因传输类型解析码流。
- **理由**：把 NAL/OBU 分片放进公共层会让传输与 codec 互相绑定；WebRTC 自带完整媒体栈，强行接入自定义管线只会增加两套状态机；描述符后到会让客户端用旧配置解新帧。
- **重新评估条件 / 验证证据**：RTP 的触发条件沿用 DB-NET-001，仍以 TCP 实测数据为依据；WebRTC 是否纳入仍由产品决定；封包器需要独立测试与协议版本兼容验证。验证：transport 事件序列中 descriptor 先于首 AU；切换时新 descriptor 先于新 AU。
- **关联文档**：[可扩展编码框架设计](codec-framework.md)、DB-NET-001、[媒体架构传输](media-architecture.md)

</details>

### 传输、会话与多客户端

<a id="db-multi-001"></a>
<details><summary>DB-MULTI-001 单编码扇出与单控制租约（📝 已裁决·未实施 · 2026-10-17）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：📝 已裁决·未实施 / 传输会话 / 2026-10-17 / —
- **当前裁决**：一次采集、通常一次编码、按客户端有界队列扇出；默认兼容单流，多档仅在 DB-MULTI-004 条件下启用；第一版不做每客户端转码；慢客户端降到下一个 IDR 或断开；观看可多人，控制默认只授予一个客户端租约，使用 `clientId`、`leaseEpoch`、`expiresAt`、心跳与 epoch fencing。
- **理由**：慢客户端不能阻塞采集、编码或其他客户端；H.264 不能套用 JPEG 的“最新帧覆盖”；并发控制会带来冲突、权限与审计复杂度。
- **重新评估条件 / 验证证据**：若产品确认需要不同清晰度，按 DB-MULTI-004 评估多档流；若确认多人控制，再评估多租约与冲突策略；需多客户端、慢客户端与租约过期实测。
- **关联文档**：[媒体架构多客户端](media-architecture.md)、[媒体架构反控](media-architecture.md)、[路线图阶段 3](optimization-roadmap.md)

</details>

<a id="db-multi-002"></a>
<details><summary>DB-MULTI-002 缩放只改本地渲染（📝 已裁决·未实施 · 2026-10-17）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：📝 已裁决·未实施 / 传输会话 / 2026-10-17 / —
- **当前裁决**：客户端窗口缩放只改本地渲染，不减少网络字节与解码工作量；本地不渲染部分输出帧也不减少解码。分辨率变化通常需重配解码器（除非设备明确支持 adaptive playback）；码率或帧率变化通常不需要。
- **理由**：解码发生在渲染之前，显示尺寸不改变码流；adaptive playback 依赖设备与编码参数。
- **重新评估条件 / 验证证据**：目标设备解码器的 adaptive playback 支持矩阵；分辨率切换的重配耗时与黑屏时长需实测。
- **关联文档**：[媒体架构客户端显示与解码边界](media-architecture.md)、[窗口与会话设计渲染目标](windowing-session-design.md)

</details>

<a id="db-multi-003"></a>
<details><summary>DB-MULTI-003 兼容单流优先多档（📝 已裁决·未实施 · 2026-10-17）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：📝 已裁决·未实施 / 传输会话 / 2026-10-17 / —
- **当前裁决**：多客户端默认选择一条兼容单流（共同能力交集，H.264 保底）。确认需求后可采用多档流：仍只用一个 MediaProjection 与一个 VirtualDisplay，经 GPU 分发到有限数量编码器；不为每档建立新的 MediaProjection 会话。
- **理由**：多个 MediaProjection 会话会重复授权、重复采集并放大资源占用；GPU 分发可以在一次采集上产生有限档位；兼容单流是功耗与稳定性最低的风险路径。
- **重新评估条件 / 验证证据**：多档需求与设备编码器实例数量；需实测多编码器的功耗、发热、帧率与对已有客户端的影响。
- **关联文档**：[媒体架构兼容单流与多档编码](media-architecture.md)、[路线图阶段 3](optimization-roadmap.md)

</details>

<a id="db-multi-004"></a>
<details><summary>DB-MULTI-004 单流与多档流策略（📝 已裁决·未实施 · 2026-10-17）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：📝 已裁决·未实施 / 传输会话 / 2026-10-17 / —
- **当前裁决**：多客户端策略分为“兼容单流”和“多档流”：单流取所有已连接客户端的共同兼容能力；多档流可让高能力客户端使用高规格、其他客户端继续 H.264 档，但受编码器实例、功耗、热与网络限制。新客户端不能迫使已有客户端无提示降级；若无法在不影响他人的前提下增加档位，新客户端接受兼容档。档位变化统一通过新 `configEpoch` + 配置 + IDR 过渡。
- **理由**：既要避免为了一个高能力客户端牺牲所有人，也要避免把所有人锁死在低档；显式策略和“不静默降级”能兼顾能力发挥与兼容性。
- **重新评估条件 / 验证证据**：需要真实多客户端组合、编码器实例上限、功耗/发热与弱网实测；确认多档切换时已有客户端的可见提示与回退行为；没有实测收益时保持单流。
- **关联文档**：[媒体架构兼容单流与多档编码](media-architecture.md)、[路线图阶段 3](optimization-roadmap.md)

</details>

<a id="db-net-001"></a>
<details><summary>DB-NET-001 gRPC 控制面与媒体统一承载（✔️ 已裁决·当前实现符合 · 2026-10-17）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：✔️ 已裁决·当前实现符合 / 传输会话 / 2026-10-17 / —
- **备注**：RTP/UDP 与 WebRTC 部分待验证
- **当前裁决**：保留 gRPC/TCP 作为控制面，负责认证、能力协商、配置、统计与反控；媒体第一版为统一 gRPC 双向流承载完整访问单元（已实现：批次 A `4ceab10`，`GrpcMediaTransport` 实现 `MediaTransport`）；独立 TCP 帧协议降级为备选方案；RTP/UDP 仅在 TCP 实测重传拖尾不可接受且产品需要更低延迟时评估；WebRTC 仅作浏览器、跨网或完整栈需求的可选路径，不作默认基线。
- **理由**：控制消息量小，需要可靠、可重试、可加密；媒体需要低延迟、可背压、可丢弃；先把 TCP 与 H.264 做稳，避免未测量就切换传输。gRPC 媒体变体本属 DB-NET-002 批准范围，第一版少维护一条协议。
- **重新评估条件 / 验证证据**：触发 RTP/UDP：同设备同网络的 TCP 基线显示重传拖尾或帧到达尖峰不可接受，且产品需要更低延迟；触发 WebRTC：确认需要浏览器客户端、跨网/NAT 或完整音视频栈。所需证据包括 RTT p50/p95、重传率、帧间隔 p95/p99、解码截止错过与首帧时间。
- **关联文档**：[媒体架构传输](media-architecture.md)、[路线图阶段 5](optimization-roadmap.md)

</details>

<a id="db-session-001"></a>
<details><summary>DB-SESSION-001 会话所有权与窗口解耦（✔️ 已裁决·当前实现符合 · 2026-10-17）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：✔️ 已裁决·当前实现符合 / 传输会话 / 2026-10-17 / —
- **当前裁决**：前台 Service 持有 `ClientSession`；网络、解码、音频、反控、最后一帧与统计归会话；`RenderTarget` 只负责显示 Surface/View。窗口 attach/detach 不停止 Service、不重建网络、不重新认证。
- **实施情况（2026-10-21，[TB-WIN-001](task-board.md#tb-win-001)）**：`ScreenViewerService` 持有 `ClientMediaSession` 与固定的 `SwitchableRenderTarget`；悬浮窗与全屏 `ViewerActivity` 都实现 `render/ViewerHost`，切换只替换 delegate 并调用 `ClientMediaSession.rebindSurface()` 重建解码器（`retryConfigure()` 仍是 Surface 失效后的唯一恢复入口），网络会话、认证与 descriptor 不变。Device 验证未执行。
- **未覆盖部分**：裁决里“关闭窗口不等于停止会话”仍未实现——关窗/断开的语义保持现状（结束会话），属 DV-008 待产品裁决项；Activity 生命周期与进程死亡下的连续边界也未实测。
- **理由**：当前客户端 Service 同时持有连接与悬浮窗，窗口切换容易退化为“停止服务再重连”；先拆会话所有权，再做窗口切换风险最低。
- **重新评估条件 / 验证证据**：若 Activity 生命周期、进程死亡或权限撤销使会话连续不可行，再定义可接受的恢复边界；需窗口切换、旋转、后台/前台与进程重建实测。
- **关联文档**：[窗口与会话设计](windowing-session-design.md)、[路线图阶段 1](optimization-roadmap.md)

</details>

<a id="db-session-002"></a>
<details><summary>DB-SESSION-002 HELLO 与 IDR 前等待（📝 已裁决·未实施 · 2026-10-17）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：📝 已裁决·未实施 / 传输会话 / 2026-10-17 / —
- **备注**：待实机验证
- **当前裁决**：客户端以 HELLO 声明能力；服务端返回 STREAM_INFO（实际宽高、profile/level、SPS/PPS）。客户端收到 IDR 前处于 WAIT_IDR，不能从任意 P 帧开始；收到 IDR 后进入 STREAMING。新客户端加入不使已有客户端断开或重新授权；并发加入的 IDR 请求合并并限频。
- **理由**：H.264 依赖 config 与 IDR；能力协商避免服务端发出客户端无法解码的流；已有会话不应被新加入者扰动。
- **重新评估条件 / 验证证据**：需多客户端加入、join 风暴、弱网重连实测；确认客户端声明的 profile/level 与实际解码能力一致。
- **关联文档**：[媒体架构新客户端加入](media-architecture.md)、[媒体架构多客户端](media-architecture.md)

</details>

### 窗口与显示

<a id="db-win-001"></a>
<details><summary>DB-WIN-001 跨应用悬浮窗为基线（📝 已裁决·未实施 · 2026-10-17）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：📝 已裁决·未实施 / 窗口显示 / 2026-10-17 / —
- **备注**：悬浮窗为当前实现，PiP 未实施
- **当前裁决**：`WindowManager` 跨应用悬浮窗为基线：API 25 使用 `TYPE_SYSTEM_ALERT`，API 26+ 使用 `TYPE_APPLICATION_OVERLAY`；PiP 仅作为 API 26+ 的可选增强，不替代悬浮窗；应用内浮层、Freeform、Bubbles、桌面窗口化与 `Presentation` 不作基础路径。
- **理由**：悬浮窗是当前产品的差异化形态；PiP 由系统控制尺寸与位置，且 API 25 不可用；其他窗口形态依赖 OEM 或系统版本。
- **重新评估条件 / 验证证据**：目标 OEM、手机/平板/车机形态的悬浮窗权限、最大尺寸、遮挡与系统栏行为需实测；只有 PiP 能覆盖明确场景时才作为可选增强。
- **关联文档**：[窗口与会话设计窗口选型](windowing-session-design.md)、[产品语境](product-context.md)

</details>

<a id="db-win-002"></a>
<details><summary>DB-WIN-002 按场景选择渲染视图（📝 已裁决·未实施 · 2026-10-17）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：📝 已裁决·未实施 / 窗口显示 / 2026-10-17 / —
- **当前裁决**：小窗与窗口动画优先使用 `TextureView`，因其透明度、圆角与变换更可控；全屏稳定播放可用 `SurfaceView`，因其独立层更稳定；两者都只作为 `RenderTarget`，不拥有连接或解码器。
- **理由**：`SurfaceView` 在透明度、圆角与动画上受限；`TextureView` 更灵活但有额外合成开销。取舍应按场景，而不是全局二选一。
- **重新评估条件 / 验证证据**：目标设备上 `TextureView` 的功耗、同步与首帧表现，`SurfaceView` 的 Surface 生命周期与 `RenderTarget.getSurface()` 失效后 `retryConfigure()` 的恢复可靠性；需实测后调整默认选择。
- **关联文档**：[窗口与会话设计实现要点](windowing-session-design.md)

</details>

<a id="db-win-003"></a>
<details><summary>DB-WIN-003 方案 B 悬浮窗 ↔ 全屏 Activity（✔️ 已裁决·当前实现符合 · 2026-10-17，2026-10-21 改写）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：✔️ 已裁决·当前实现符合 / 窗口显示 / 2026-10-17（2026-10-21 改写）/ 2026-10-21（设备复验 r2）
- **当前裁决**：窗口切换用**方案 B**：悬浮窗与全屏 `ViewerActivity` 是同一会话的两个显示宿主，切换时只 attach/detach 宿主并把解码器输出面切到新宿主，经“末帧快照 + 缩放过渡（200 ms）”保持视觉连续；不重建网络会话、不重建认证、不新建客户端。方案 A（同一悬浮窗直接展开）不实现；PiP、分屏/freeform 不在本轮范围。
- **改写原因**：用户 2026-10-20 提供的原始设计回答把方案 B 列为推荐路径（会话与界面解耦、`setOutputSurface` 或重建解码器、TextureView 优先、末帧快照过渡），本棒按该回答执行；原裁决“第一版用方案 A”不再成立。
- **实施情况（2026-10-21，[TB-WIN-001](task-board.md#tb-win-001)）**：`render/SwitchableRenderTarget` + `ClientMediaSession.rebindSurface()` 实现“只重建解码器”；`ViewerActivity` 通过本地 Binder 以 `ViewerHost` 身份 attach，悬浮窗与 Activity 共用 `render/ViewerPanelView`（纹理、快照层、调试叠加）与 `render/ViewerMenuFactory`（显示模式、显示大小菜单）；Surface 切换走现有 `SurfaceRenderTarget` 重建路径，不使用 `MediaCodec.setOutputSurface`（见下）。**设备验证（r2，2026-10-21）**：首轮被 P0 阻断（`ViewerActivity` 字段初始化器 `new GestureDetector(this, …)` 构造期读 Context → 展开即进程 FATAL），修复后 10 条点位 9 条通过、⑦ 通知动作不可脚本化需手测、⑧ 重连链路未跑完；连续 20 次切换无崩溃/ANR/解码器错误，单次切换（模拟器）点击 → attach 均值 3.37 s、全屏稳定 4.58 s。证据：[TB-WIN-001](task-board.md#tb-win-001) 与 `device-test-2026-10-21-windowing/r2/report.md`。
- **Surface 切换方式**：`MediaCodec.setOutputSurface` 需要改动解码器桥接层的既有契约（`Decoder` 目前只有 configure/decode/flush/close），并且在目标厂商解码器上的成功率未知；沿用“新宿主 Surface 就绪后重建解码器 + 取新 IDR”的路径，风险最低且与既有 `retryConfigure()` 恢复路径同源。代价是每次切换有一次解码器重建与一次关键帧往返。
- **重新评估条件 / 验证证据**：切换前后网络/会话/认证不重建、Service 不停、无长时间黑屏；Activity 生命周期、旋转、后台/前台与进程重建实测；目标设备上 `setOutputSurface` 是否值得替换重建路径。
- **关联文档**：[窗口与会话设计方案对比](windowing-session-design.md)、[路线图阶段 3](optimization-roadmap.md)

</details>

### 渲染与呈现

<a id="db-render-001"></a>
<details><summary>DB-RENDER-001 客户端保持内容宽高比（✔️ 已裁决·当前实现符合 · 2026-10-20）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：✔️ 已裁决·当前实现符合 / 渲染显示 / 2026-10-20 / 2026-10-20
- **当前裁决**：客户端渲染必须保持内容宽高比，窗口尺寸由 `StreamDescriptor` 的可见尺寸与旋转推导，而不是由客户端屏幕比例决定；观看比例仍是“占可用显示区的百分比”，语义不变；拉伸只在显式的“铺满”档位下允许（当前未提供该档位）。
- **理由**：窗口比例取自客户端屏幕时，横竖屏或跨机型组合会把横图压进竖框（最坏两轴比例相差数倍），这是可以直接从代码判定的结构性缺陷；`TextureView` 由 GPU 合成，框架不会替应用保持比例，必须由显示侧计算。
- **重新评估条件 / 验证证据**：
  - 结论：Q1 落地纯 JVM 的 `ViewerFitPolicy`（可见尺寸 + 旋转 + bounds + 比例 + 最小尺寸 + 缩放档位 → 窗口像素，旋转 90/270 交换宽高，非法输入回退旧行为），Q2 由 `ClientMediaReceiver` 的几何回调经主线程接入 `SurfaceShareViewer`，配置变化后重新拟合。窗口比例恒等于旋转后内容比例，因此不需要 letterbox。
  - 验证日期：2026-10-20。关键证据见 [TB-CLIENT-003](task-board.md#tb-client-003)。
  - 边界：设备实测未解禁；同型号同朝向组合的比例差仅约 2.5%，跨朝向/跨机型才明显，必须按设备矩阵复核。
- **关联文档**：[任务看板](task-board.md#tb-client-003)、[实施计划](superpowers/plans/2026-10-21-viewer-quality-and-settings.md)

</details>

<a id="db-render-002"></a>
<details><summary>DB-RENDER-002 抗锯齿上限由渲染路径决定（✔️ 已裁决·当前实现符合 · 2026-10-20）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：✔️ 已裁决·当前实现符合 / 渲染显示 / 2026-10-20 / 2026-10-20
- **当前裁决**：`TextureView` 下不做 mipmap，只做几何正确与比例吸附（适应窗口 / 原始大小 / 整数档 `1`、`1/2`、`1/3`、`1/4`）；需要更高降采样质量时按自建 GL 管线（Q6）另立批次，本条不承诺画质数字。
- **理由**：降采样的滤波方式与是否使用 mipmap 由框架决定，应用侧没有 API 打开；可用杠杆只有“让采样比例接近 1”或换渲染路径，而 `SurfaceView` 会丢掉透明、圆角与拖动动画。
- **重新评估条件 / 验证证据**：
  - 结论：Q1/Q3 落地三种缩放档位与吸附规则，Q2 让窗口比例恒等于内容比例；本轮不引入 GL 管线。
  - 验证日期：2026-10-20。关键证据见 [TB-CLIENT-003](task-board.md#tb-client-003)。
  - 边界：锯齿收益未实测；是否启动 Q6 由 [DV-020](#待决策--待验证) 的截图对比决定。
- **关联文档**：[任务看板](task-board.md#tb-client-003)、[任务看板](task-board.md#tb-client-004)

</details>

<a id="db-render-003"></a>
<details><summary>DB-RENDER-003 限帧与合并在两端分工（✔️ 已裁决·当前实现符合 · 2026-10-20）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：✔️ 已裁决·当前实现符合 / 渲染显示 / 2026-10-20 / 2026-10-20
- **当前裁决**：编码前限帧（服务端帧率与码率参数）省带宽、省解码与省电；解码输出侧只做“只保留最新帧 + 过期帧不追赶”，并把已经发生的丢弃转成“请求关键帧”；客户端任何路径都不得随手丢编码帧。
- **理由**：编码帧之间是参考关系，丢一个 P 帧会让画面损坏到下一个 IDR；而平台在 View 表面本来就会丢多余帧，本地再限帧只省合成。合并在解码后是安全的，但必须有恢复动作，否则损坏窗口要等一个 IDR 周期。
- **重新评估条件 / 验证证据**：
  - 结论：Q4 把 bridge 拆成 `present`/`discard`，`H264Decoder` 每次取尽输出只呈现最新帧；`PresentationPacer` 负责立即呈现 / 按时间戳调度 / 丢弃；输入预算丢帧、解码队列溢出与会话重同步三条路径都以 1 s 冷却调用 `requestKeyframe()`。Q5 把帧率与 IDR 间隔变成可配置并走编码器重建。
  - 验证日期：2026-10-20。关键证据见 [TB-CLIENT-004](task-board.md#tb-client-004)、[TB-ENG-012](task-board.md#tb-eng-012)。
  - 边界：本地限帧默认关闭（最小间隔 0），实际收益与恢复时延未实测。
- **关联文档**：[任务看板](task-board.md#tb-client-004)、[实施计划](superpowers/plans/2026-10-21-viewer-quality-and-settings.md)

</details>

### 平台、安全、音频与反控

<a id="db-audio-001"></a>
<details><summary>DB-AUDIO-001 音频内录与降级策略（🔶 已裁决·代码已实施 · 设备未验证 · 2026-10-21 细化）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：🔶 已裁决·代码已实施（设备未验证）/ 平台安全 / 2026-10-17（2026-10-21 细化）/ —
- **当前裁决**：API 29+ 使用 `AudioPlaybackCaptureConfiguration` 与 `AudioRecord` 捕获播放音频，受被采集 App 策略限制；**API 25–28 默认无声且不提供麦克风**（D1=不做，`TB-AUDIO-002` 未激活）；音频默认关闭、显式开关 + `RECORD_AUDIO`；编码 **AAC-LC + AudioSpecificConfig**（不用 ADTS，不引入 Opus 或新依赖）；传输复用**同一条 `Stream` RPC**，新增 `audio_descriptor`/`audio_access_unit` 与 `Capabilities.audio_codecs`，**不新增 RPC/端口**；每客户端独立有界音频队列（32 帧/300 ms，丢最旧），音频优先于视频出队；**音频为主时钟**、与视频共用 `SessionClock` 单锚点，目标 \|Δ\|≤40 ms（v1 验收 ≤100 ms），2 s 冷却的帧级丢/补，不做重采样；API 29 标为**实验支持**，失败即无声；无观看者时采集随会话懒启停。
- **理由**：系统内录受版本与应用策略限制；麦克风涉及隐私、回声与用户预期，不能在用户不知情时开启；AAC-LC 的编解码器在 CDD 中是强制项，是唯一「不必探测就能承诺、且不新增依赖」的选择；音频与视频各走一条独立队列、共用一条时间轴，才能在慢客户端与 TCP 队头阻塞下保持音频延迟有界且画面不受牵连。
- **实施证据**：A1–A7 逐批提交（[音频计划](superpowers/plans/2026-10-21-audio.md) 6.2–6.8、第 10.1 章裁决）；JVM 1236 tests / 120 classes 全绿；`assembleDebug` 与三项 checks 通过。设备点位见 `TB-AUDIO-003`。
- **重新评估条件 / 验证证据**：产品是否确认需要麦克风降级（D1 复议）；目标设备的 AAC 编码/解码、音画同步实测（计划 6.10 的 A8 清单）、音频路由与功耗；若 API 29 实测不可用则收紧为 30+。
- **关联文档**：[音频计划](superpowers/plans/2026-10-21-audio.md)、[媒体架构音频](media-architecture.md)、[路线图阶段 4](optimization-roadmap.md)

</details>

<a id="db-ctrl-001"></a>
<details><summary>DB-CTRL-001 无障碍手势为反控基线（📝 已裁决·未实施 · 2026-10-17）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：📝 已裁决·未实施 / 平台安全 / 2026-10-17 / —
- **当前裁决**：普通 App 的反控基线是 `AccessibilityService.dispatchGesture`，由用户手动开启且默认关闭；文本优先使用无障碍节点操作；不使用 root、shell、系统签名或 `INJECT_EVENTS` 作为普通版本前提；`MediaProjection` 只采集不注入；观看与控制权限分离，控制使用单客户端租约、可见指示且不记录输入内容。
- **理由**：`MediaProjection` 不能注入输入；普通 App 没有签名级 `INJECT_EVENTS`；无障碍是唯一可落地的普通方案，但受目标 App 与系统界面限制。
- **重新评估条件 / 验证证据**：产品是否把反控纳入范围；目标 App 与系统界面的无障碍覆盖、坐标与旋转、租约转移需实测；还需评估合规与分发政策。
- **关联文档**：[媒体架构反控](media-architecture.md)、[路线图阶段 4](optimization-roadmap.md)

</details>

<a id="db-platform-001"></a>
<details><summary>DB-PLATFORM-001 minSdk 25 为兼容下限（✔️ 已裁决·当前实现符合 · 2026-10-17）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：✔️ 已裁决·当前实现符合 / 平台安全 / 2026-10-17 / —
- **备注**：`app/build.gradle` 中 minSdk 为 25
- **当前裁决**：保持 minSdk 25 作为兼容下限；新能力按 API 版本门控；API 25 至少保留基本监看；不为了音频内录、PiP 或新窗口 API 抬高最低版本。minSdk 25 不是能力上限，不得据此限制高版本系统、旗舰手机/平板或其他高性能设备使用高级编解码与分辨率档位。
- **理由**：现有产品定位与设备覆盖需要低版本支持；核心改造依赖的 `PixelCopy`（API 24+）与 `dispatchGesture`（API 24+）在 API 25 可用；解码输出走 `RenderTarget.getSurface()`、Surface 失效后由 `retryConfigure()` 重建解码器，不依赖 `setOutputSurface`；设备能力必须运行时探测。
- **重新评估条件 / 验证证据**：若维护成本、安全更新或目标市场明确放弃 API 25–28，再评估提高门槛；需要设备分布、测试成本与商店政策数据。
- **关联文档**：[媒体架构权限与版本门控](media-architecture.md)、[窗口与会话设计](windowing-session-design.md)、[路线图原则](optimization-roadmap.md)

</details>

<a id="db-platform-002"></a>
<details><summary>DB-PLATFORM-002 双语资源跟随系统（✅ 已验证 · 2026-10-19）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：✅ 已验证 / 平台安全 / 2026-10-18 / 2026-10-19
- **当前裁决**：应用提供英文与中文资源并跟随系统语言：`values/` 为英文默认与回退，中文放入 `values-zh`；系统语言为中文时显示中文，其他系统语言或无法匹配时回退英文；暂不提供应用内语言开关。
- **理由**：改造前 `values/strings.xml` 只有中文，非中文系统没有可用回退；默认英文加中文限定资源是 Android 资源解析的原生方式，无需应用内开关即可跟随系统，也避免自建语言状态。
- **重新评估条件 / 验证证据**：
  - 结论：`values/` 为英文默认（35 键）、`values-zh/` 为中文（35 键），键集合相同、占位符与 `xliff:g`/`<b>` 双语一致、`values/` 零 CJK；语言跟随与回退经资源限定符与代码审查确认（无 `resConfigs`/locale 过滤、无其他 `values-<语言>` 目录、代码无 locale 硬编码）；边界：不含设备实测，设备验证未解禁。
  - 验证日期：2026-10-19。关键证据：提交 `595dbfd` 仅改 `values/strings.xml` 并新增 `values-zh/strings.xml`；`./gradlew :app:assembleDebug --offline --rerun-tasks` 与 `./gradlew :app:testDebugUnitTest --offline --rerun-tasks` 通过（364 tests / 0 failures / 0 errors / 0 skipped）；Build CI run [39204294556](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39204294556) success。
  - 完整证据见 [任务看板](task-board.md) 对应条目详情（TB-ENG-004）。
- **关联文档**：[任务看板](task-board.md)、[媒体架构](media-architecture.md)、[文档索引](README.md)

</details>

<a id="db-sec-001"></a>
<details><summary>DB-SEC-001 认证与传输加密目标（📝 已裁决·未实施 · 2026-10-17）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：📝 已裁决·未实施（传输加密部分已由 TB-DISC-010 实施）/ 平台安全 / 2026-10-17 / —
- **备注**：传输加密与设备身份 pinning 已实施（JVM 证据）；配对、会话令牌与分级授权仍未实施
- **当前裁决**：2026-10-17 时点：无认证与明文传输只允许在可信局域网使用，不能作为跨网或多人产品的前提；目标是配对（PIN、二维码或按钮确认）、控制面 TLS、媒体面 TLS 或 DTLS-SRTP、会话令牌、观看/音频/反控分级授权，并默认拒绝未配对客户端；发现默认不广播设备名；`FLAG_SECURE` 不是加密。2026-10-21 更新：控制面/媒体面的自签 TLS、mTLS 与设备身份 pinning 已由 TB-DISC-010 实施；配对、会话令牌与分级授权仍未实施。
- **理由**：同一网络内可达客户端可尝试拉流，内容可被窃听或篡改；“局域网”不能替代认证与加密；窗口防截取不保护网络。
- **重新评估条件 / 验证证据**：产品是否接受“仅可信局域网”的边界；配对体验与凭据存储方案；TLS/DTLS 在目标设备与网络上的成本、兼容性与性能；需安全评审与渗透测试。传输加密侧证据见 [TB-DISC-010 实施计划](superpowers/plans/2026-10-21-tls-tofu.md) 与任务看板证据（2026-10-21：1069 tests / 98 classes 全绿，含真实 TLS 握手 E2E；设备端 D1/D4/D6/D8 与 D5 实测语义已验证，D2 出画、D7 NSD/组播、D9 30 min 基线、D10 备份恢复仍缺）。
- **关联文档**：[项目现状安全边界](project-assessment.md)、[媒体架构安全模型](media-architecture.md)、[路线图阶段 2](optimization-roadmap.md)

</details>

### 文档与协作

<a id="db-doc-001"></a>
<details><summary>DB-DOC-001 文档图表与术语规范（📜 已裁决·文档约定生效 · 2026-10-17）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：📜 已裁决·文档约定生效 / 文档协作 / 2026-10-17 / —
- **当前裁决**：示意图统一使用 Mermaid，禁止 ASCII 或文字箭头图；角色统一称“服务端/客户端”，不使用别名；不写入本机绝对路径、设备名、账号、口令、密钥或运行环境标识；代码引用使用仓库相对路径；README、docs、任务看板与决策看板在功能、架构、裁决或任务状态变化时同步更新；文档区分当前实现、建议方案、推测与已验证证据。
- **理由**：降低角色与范围歧义，避免敏感信息入库，防止计划被误读为已实施。
- **重新评估条件 / 验证证据**：文档规范调整时复审；发现别名、绝对路径、敏感信息、失效链接或过期行号时立即修正。
- **关联文档**：[文档索引](README.md)、[任务看板](task-board.md)

</details>

<a id="db-doc-002"></a>
<details><summary>DB-DOC-002 Javadoc 与英文注释（✅ 已验证 · 2026-10-19）</summary>

- **状态 / 主题 / 裁决日期 / 验证日期**：✅ 已验证 / 文档协作 / 2026-10-18 / 2026-10-19
- **当前裁决**：历史代码与后续新增代码都必须补齐 Javadoc 与必要注释，覆盖公共契约、生命周期、并发状态机与非显然算法；Javadoc、代码注释与日志打印统一使用英文；不写复述代码、过期说明或噪声注释。实施排在 H264 八 Task 之后，独立提交，不与编解码改动混改。
- **理由**：当前仓库没有统一的注释语言与覆盖规范，关键契约、生命周期与并发语义依赖阅读实现；英文注释与日志便于跨工具审查与长期维护，但无意义注释只会增加噪声。
- **重新评估条件 / 验证证据**：
  - 结论：按 [实施计划](superpowers/plans/2026-10-19-code-docs-i18n.md) 完成 B1–B6 六个仅注释批次，覆盖 93 个 `.java`；剥离注释后语义等价；验收抽审发现 2 处注释与代码不符（`StreamHub` 锁说明、`LssService` 启动失败范围），已由 `2852c5d` 修正并重跑门禁与 CI；边界：未执行 adb、模拟器、实机或 `connectedAndroidTest`。
  - 验证日期：2026-10-19。关键证据：计划 `72f87f8`、`317f41a`；B1 `6541794`、B2 `fd1d407`、B3 `62f4800`、B4 `7eda4fb`、B5 `fdc990a`、B6 `b5dfa5d`（范围 `6541794..b5dfa5d`）；基线 `11096a4`；364 tests / 0 failures / 0 errors / 0 skipped；`./gradlew :app:assembleDebug --offline` 成功；CI run [39203370502](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39203370502)（B1–B6 各批 Build CI 亦成功）。
  - 完整证据见 [任务看板](task-board.md) 对应条目详情（TB-ENG-003）。
- **关联文档**：[任务看板](task-board.md)、[文档索引](README.md)

</details>

## ❓ 待决策 / 待验证

以下事项尚未形成最终结论。它们不是待办任务，不能在没有证据时写成“已裁决”或“已实现”。

| ID | 事项 | 日期 | 当前处理 | 所需证据 / 触发条件 | 关联 |
| --- | --- | --- | --- | --- | --- |
| DV-001 | TCP 是否需要升级为 RTP/UDP。 | 2026-10-17 | 第一版保持统一 gRPC 双向流（TCP 承载）；不做未测量的切换。仪表已就位，判据改为四类联合归因，见 DB-OBS-002。**全量基线待 Q-2 落地后执行**（[TB-CLIENT-005](task-board.md#tb-client-005) 的观看端追赶已落地代码、设备未验证），基线须同时记录 `rx.catchup`/`rx.catchup.lat`/`rx.converge.fail`。 | 同设备同网络的 TCP 基线：`rtt.p50/p95`（Ping/Pong）、`rx.arr.p95/p99`、`rx.rowd.p95`、`rx.stall`、`pres.late.p95`、`dec.deadlineMiss`、`tx.send.p95`、`enc.cap2out.p95`；重传次数不可见，用「到达空窗 + 延迟增长 + 发送阻塞且序号不缺」代替。 | DB-NET-001、DB-OBS-002、[TB-OBS-001](task-board.md#tb-obs-001)、[TB-OBS-002](task-board.md#tb-obs-002)、[TB-CLIENT-005](task-board.md#tb-client-005) |
| DV-002 | 是否需要 WebRTC。 | 2026-10-17 | 不作为默认基线，保留为浏览器/跨网/完整栈的可选路径。 | 产品确认需要浏览器客户端、跨网/NAT 穿透或完整音视频栈；或 TCP/RTP 无法满足目标场景。 | DB-NET-001 |
| DV-003 | HEVC 是否作为默认高档位启用。 | 2026-10-17 | 保持 H.264 兼容档保底，HEVC 仅在双方探测支持且实测有收益时启用。 | 服务端与客户端均探测支持；同场景实测相比 H.264 有明确码率或画质收益；专利与分发政策可接受。 | DB-CODEC-001、DB-CODEC-007 |
| DV-004 | 窗口切换后的 Surface 恢复是否可靠；方案 B 的动画与生命周期在目标设备上是否成立。 | 2026-10-17 | 窗口切换按方案 B 落地（2026-10-21 改写，见 [DB-WIN-003](decision-board.md#db-win-003)）：切换只重建解码器、从新 IDR 恢复，网络会话不变。**2026-10-21 设备复验（r2）**：同上点位的 ①–④ 有实测数据——无黑屏（快照层）、切换后画面恢复、Home/旋转/前后台均正常、20 次连续切换无解码器重建失败；仅模拟器单次切换耗时 3.4 s（attach）+ 1.2 s（首帧与动画），真机数据仍缺。 | 目标设备上切换的主观连续性（黑屏/闪烁时长）、解码器重建失败率与恢复时间、Activity 启动是否被后台启动限制拦截、旋转与前后台切换表现；不再只以车机为测试前提。 | DB-WIN-003 |
| DV-005 | 音频是否纳入产品范围；API 25–28 是否接受麦克风降级。 | 2026-10-17 | **已定范围**：音频纳入 v1（默认关，显式开关），API 25–28 纯无声；**麦克风降级不做**（D1，2026-10-21 裁决）。代码 A1–A7 已实施，设备未验证。 | 目标设备的音频捕获、AAC 编解码与音画同步实测（`TB-AUDIO-003`）；若用户后续要求麦克风，需重新评估隐私/回声成本。 | DB-AUDIO-001、[TB-AUDIO-001](task-board.md#tb-audio-001) |
| DV-006 | 反控是否纳入产品范围；无障碍覆盖是否足够。 | 2026-10-17 | 反控默认关闭，只做设计预留。 | 产品确认远程操作需求；目标 App 与系统界面的手势、文本输入、坐标与旋转实测；合规评估。 | DB-CTRL-001 |
| DV-007 | 手机、平板、车机与多显示器的场景定位分别是什么。 | 2026-10-17 | 车机只是候选场景之一，不是能力基准；设备形态不决定媒体能力。 | 各目标机型与系统版本上的悬浮窗权限、网络、休眠、音频路由、屏幕布局与合规实测；旗舰平板/手机的能力需单独探测。 | DB-WIN-001、DB-CAP-003、[产品语境](product-context.md) |
| DV-008 | 关闭悬浮窗后，会话应继续后台运行、转入通知，还是立即结束。 | 2026-10-17 | 暂未定义产品行为；当前实现关闭窗口会结束客户端 Service。2026-10-21 起新增的窗口切换**不改这一语义**：展开到全屏 Activity 时移除悬浮窗不结束会话（只是换显示宿主）；从悬浮窗断开、从 Activity“停止观看”仍结束会话。 | 产品决策与用户任务测试；同时需要定义保留会话时的通知、资源与安全边界。 | DB-SESSION-001、[窗口与会话设计开放问题](windowing-session-design.md) |
| DV-009 | “仅可信局域网”的安全边界是否可接受。 | 2026-10-17 | 传输加密与设备身份已由 [TB-DISC-010](task-board.md#tb-disc-010) 落地（自签 TLS + TOFU pin，JVM 证据）；配对与授权仍缺，因此维持未裁决；首连强度上限见 DV-022。 | 产品与用户访谈、首次连接风险提示、配对体验测试；若不接受，则先完成配对与授权再扩大使用范围。 | DB-SEC-001、[TB-DISC-010](task-board.md#tb-disc-010) |
| DV-010 | 多客户端是否需要不同画质或多人控制。 | 2026-10-17 | 默认兼容单流；控制默认单客户端租约。 | 真实多客户端场景、慢客户端与不同屏幕尺寸测试；确认需求后再评估多档流、转码或多控制租约。 | DB-MULTI-001、DB-MULTI-004 |
| DV-011 | 是否需要严格帧率上限。 | 2026-10-17 | 以 `KEY_FRAME_RATE` 为目标提示，不做严格保证；2026-10-21 起额外把设置值写入 `max-fps-to-encoder`（API 29 公开、值 `"max-fps-to-encoder"`，float）请求编码器丢帧，编码器拒绝时自动去键重配，因此认出该键的机型可真正限帧，其余机型行为不变。 | 产品明确要求固定帧率；需目标设备的实际输出帧率分布（设 15 后 `enc.outInterval.p50` 是否升到 ≈67 ms），以及 GPU 上游调度或版本化参数的验证结果。 | DB-CODEC-004、DB-OBS-003、[TB-ENG-012](task-board.md#tb-eng-012) |
| DV-012 | 是否需要多档流，以及可行的档位数量。 | 2026-10-17 | 默认兼容单流；多档仅在确认需求、设备能力与实测收益后评估。 | 真实多客户端画质需求；单 VirtualDisplay + GPU 分发到有限编码器的设备能力与实测收益；编码器实例、功耗与热上限。 | DB-MULTI-003、DB-MULTI-004 |
| DV-013 | 解码器是否支持分辨率切换的 adaptive playback。 | 2026-10-17 | 默认按“需要重配解码器”处理。 | 目标设备与系统版本的解码器能力查询与实测；不支持时接受短暂黑屏，并从新 config + IDR 恢复。 | DB-MULTI-002、DB-CODEC-003 |
| DV-014 | 自动选档的判定阈值与回退时延。 | 2026-10-17 | 能力驱动选择；未实测组合保持 H.264 兼容档，不做盲目自动切换。 | 各档位的画质、码率、功耗、网络与首帧数据；切换失败率、回退时延与用户可见提示；热/电约束下的稳定性。 | DB-CODEC-006、DB-CODEC-008 |
| DV-015 | 多档流的编码器实例与功耗上限。 | 2026-10-17 | 多档受硬件编码器实例、功耗、热与网络限制；没有实测收益时保持单流。**补注（2026-10-21）**：单流下的热约束已落地（[DB-POWER-001](#db-power-001)/[DB-POWER-002](#db-power-002)：无观看者暂停、按热状态临时降档），多档流的实例数与功耗上限仍无实测数据。 | 目标设备同时可用编码器数量、双路/多路编码的温升与掉帧、带宽与发热；对已有客户端的影响。 | DB-MULTI-004、DB-CODEC-008 |
| DV-016 | AV1 是否作为后续高级选项引入。 | 2026-10-17 | 第一版不实现；只在设备明确支持时评估。 | 服务端硬件编码与客户端硬件解码覆盖率、授权与工具链、同场景收益；任一侧缺失即不启用。 | DB-CODEC-007 |
| DV-017 | HEVC 的 CRA/RASL 等前导图像接入点是否、何时可作为可加入点。 | 2026-10-17 | 第一版只接受明确安全 IDR，CRA/BLA/RASL/RADL 不作为可加入点。 | 目标设备编码器的 IRAP 类型标记可靠性；带前导图像的接入是否会在不同设备上产生花屏；若开放，必须先有 `CodecPolicy` 与客户端状态机的验证方案。 | DB-CODEC-013、DB-CODEC-009 |
| DV-018 | 有界队列的字节/时间上限、可加入点等待超时、切换确认超时取多少。 | 2026-10-17 | 设计只规定“必须有界与可恢复”，不预设数值；`StreamQueueLimits` 当前 4 MiB / 120 units / 2000 ms 仍标注为临时值。 | 数值来源（本计划 6.5）：发送队列字节上限取 `tx.qBytes` 峰值 + `enc.auBytes.p95`；单元上限取 `tx.qDepth` 峰值 + `enc.auRate`；驻留上限取 `tx.qResidency.p95/max`；可加入点等待取 `join.latency`；切换确认取 `switch.blackout`；重同步频率取 `tx.resync`。 | DB-MULTI-005、DB-CODEC-014、[TB-OBS-001](task-board.md#tb-obs-001) |
| DV-019 | `visible size` 与 rotation 能否只做渲染变换而不推进 `configEpoch`。 | 2026-10-17 | 本阶段 rotation 与 `visible size` 都生成新描述符并推进 epoch；只有拿到实测证据后才评估放宽。 | 目标设备解码器对裁剪与旋转的实际行为；若放宽，必须由 `CodecPolicy` 显式声明，且旧客户端仍能正确显示。 | DB-CODEC-011 |
| DV-021 | `rx.stall` 阈值与「用户可感知卡顿」的对应关系。 | 2026-10-21 | 暂用 `max(3 × 最近 30 帧到达间隔均值, 150 ms)`（与 WebRTC `freezeCount` 同构），仅作为统计口径，不当作产品阈值。 | 需要设备基线与用户主观感受的对照：同一段画面在 150 / 250 / 500 ms 空窗下的观感评价，以及 `rx.stall` 次数与「卡了」描述的相关性；确认前不得据此调整队列或传输。 | DB-OBS-002、[TB-OBS-001](task-board.md#tb-obs-001) |
| DV-020 | 客户端降采样残差是否已经值得自建 GL 管线（mipmap）。 | 2026-10-20 | 先按几何正确 + 比例吸附（适应窗口 / 1:1 / 整数档）交付；`TextureView` 下不做 mipmap，Q6 后置。 | 同一画面在采样比 `s≈1` 与 `s≈0.5` 下的截图对比仍显示明显锯齿；并同时记录 GL 管线的首帧延迟、功耗与目标 GPU 对 `GL_TEXTURE_EXTERNAL_OES` + `glGenerateMipmap` 的兼容性。 | DB-RENDER-002 |
| DV-022 | TOFU 首连的强度上限是否可接受（人工比对完整 64 hex；是否必须提供扫码）。 | 2026-10-21 | 本轮只做人工比对，展示完整 64 hex、不截短；扫码后置（用户裁决 Q2）。首连之后任何指纹变化都会被拒绝。**补注（2026-10-21 T6 复测）**：身份重置必然同时改变 `device_id`（`id = f(SPKI)`），所以合法重置在对端表现为“同地址新设备 + 该地址上次是另一台设备”提示 + 显式信任，而不是“同 id、旧/新指纹并列”的“身份已变更”弹框；后者对合法重置不可达，只由公告 id 与证书自相矛盾的攻击路径触发，且该情形更早被 `identity_mismatch` 拒绝。 | 两台真机同屏比对的可用性测试与用户访谈；若首连误确认率不可接受，再评估扫码或近场确认。 | DB-SEC-001、[TB-DISC-010](task-board.md#tb-disc-010) |
| DV-023 | 是否提供用户可见的“关闭加密”开关。 | 2026-10-21 | 不提供（用户裁决 Q4）：只有隐藏键 `KEY_TLS_ALLOW_PLAINTEXT`，默认关、不自动降级；开启时公告 `secure=false`、主界面常驻警告，公告 `secure=false` 的对端会被明确拒绝。 | 若运维/验证需要长期明文，才评估受控开关、审计与告警；在此之前维持隐藏键。 | DB-SEC-001、[TB-DISC-010](task-board.md#tb-disc-010) |
| DV-024 | 热降档的档位门槛与恢复时延。 | 2026-10-21 | 按平台热状态分三档：`NONE`/`LIGHT`/`MODERATE` 不动、`SEVERE` 帧率 30、`CRITICAL` 及以上帧率 30 + 采集 50%；降档只在档位变化时动作，回落到低档立即恢复用户设定（见 [DB-POWER-001](#db-power-001)）。 | 目标机型在 `MODERATE`/`SEVERE` 的真实触发温度与掉帧表现、降档/恢复后的 `enc.outInterval.p50` 与画面尺寸、切换停顿；若多数机型在 `MODERATE` 已明显掉帧，才评估第一档下移。 | DB-POWER-001、DV-014、DV-015 |
| DV-025 | 无观看者暂停采集的首帧代价是否可接受。 | 2026-10-21 | 0 观看者且运行时已 `READY` 时摘下 `VirtualDisplay` 输出面，编码器保持分配；首个观看者到达后重新绑面并请求关键帧（见 [DB-POWER-002](#db-power-002)）。 | 首个观看者「加入 → 首帧」时延（轮询 ≤1 s + 一次 IDR）与主观等待、暂停/恢复是否在目标机型出现黑屏或 `dequeueBuffer -19` 噪声、长时间暂停后编码器是否被系统回收。 | DB-POWER-002、DB-CAP-002、[TB-POWER-001](task-board.md#tb-power-001) |

## 📜 裁决演进

| 阶段 | 原有表述 | 问题 | 本轮修订 |
| --- | --- | --- | --- |
| 问题 3 初次裁决 | “通常一路 H.264 编码”“H.264 为默认基线”“第一版共享同一编码、固定采集尺寸”“默认只做一条兼容单流” | 容易被读成“自动永远只选 H.264”“按老车机/低版本设备能力限制全产品”“API 25 决定能力上限”，与用户澄清不符。 | 保留 H.264 兼容基线与单流兜底，但明确它是下限而非上限；加入运行时能力探测、场景分档、HEVC 可选、AV1 后续选项、兼容单流/多档流两种策略，以及不做盲目自动切换的边界。 |
| 编码框架状态模型 | 此前用单一 `WAIT_IDR` 表示“等待可解码起点” | JPEG 每帧独立，不存在 IDR；HEVC 的 CRA/RASL 也不能等同于 IDR。单一状态名容易诱导实现把“关键帧”当成“可加入点”。 | 拆为 `WAIT_CONFIG` 与 `WAIT_RANDOM_ACCESS`；可加入点使用独立标记并由 `CodecPolicy` 判定。`WAIT_IDR` 只作为 H.264 特例保留在历史与既有章节中。 |
| 媒体传输第一版 | “媒体第一版走独立 TCP 帧协议，不每帧套 protobuf” | 实施转向 gRPC 双向流后未留修订记录，文档语义漂移。 | 2026-10-19 用户裁决：第一版媒体为统一 gRPC 双向流承载完整 AU；独立 TCP 降级备选；RTP/UDP 与 WebRTC 触发条件不变。 |

以上历史表述只用于追溯，不再作为当前裁决。旧记录不删除，避免把裁决演进伪装成“从未有过保守表述”。

## 🔧 维护方式

- 新增裁决：先在“裁决总览”加一行窄列（7 列），再在“裁决详情”对应主题下补 `<details>` 块；两处 ID 与标题必须一致。
- 日期口径：裁决日期 = 该 ID 首次出现在本文件的提交日期（`git log --reverse --format=%ad --date=short -S "<ID>" -- docs/decision-board.md` 最早命中）；验证日期 = 验证通过的提交或证据日期，未验证留 `—`；无法考证时用首次出现日期。
- 排序：总览与待决策表均按日期降序，同日期按 ID；详情按主题归组。
- 状态只用“图例”中的 8 种口径（emoji+文字）；复合状态的主状态取口径内值，限定语（如“严格限帧待验证”）逐字写进详情块“备注”，不自造新的状态字面值。
- 证据单源：提交哈希、CI run 链接与测试数字等实施证据由 [任务看板](task-board.md) 条目详情单源维护；本页只保留裁决结论、验证边界与指针（如 DB-DOC-002、DB-PLATFORM-002）。
- 只有代码、测试或实测记录能改变“未实施”状态；设计文档更新不等于实现完成。
- 状态变为“已实施/已验证”时，补充对应提交、测试输出或实测证据；没有证据时保持“未实施”或“待验证”。
- 触发条件满足或产品前提变化时，复审相关裁决；被推翻的裁决保留 ID 并标记 ❌ 已否决及原因，不静默删除。
- 新增或删除裁决时，同步检查 [任务看板](task-board.md) 与 [文档索引](README.md) 是否需要更新。
