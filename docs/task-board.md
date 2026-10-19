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

- 基线参考：`65a34dc`（TB-ENG-004 完成标记）。
- 编码框架隔离核心批次（Task 1–9，代码范围 `9f650dc..2a95ab2`）见 [TB-DOC-008](#tb-doc-008) 与 [TB-FW-001](#tb-fw-001)；最近完成的条目见 [TB-ENG-004](#tb-eng-004) 与 [TB-ENG-003](#tb-eng-003)。
- H.264：适配器与两阶段配置发布已落地，仅经 JVM/fake 与编译级 androidTest 验证，详见 [TB-FW-003](#tb-fw-003)。
- H.264 仍是隔离实现：**未接现有投屏**，无编码切换编排、无 TCP 或设备验证。
- 编码框架隔离核心（契约、StreamHub、有界队列、六状态机、JPEG 参考适配）已完成并通过 JVM 验证，见 [TB-FW-001](#tb-fw-001)、[TB-FW-004](#tb-fw-004)、[TB-FW-005](#tb-fw-005)、[TB-FW-002](#tb-fw-002)。
- 问题 3 尚未由用户明确关闭，不能标记完成；API 25 只是兼容下限，能力按运行时探测分档。
- 编码框架待收尾：TB-FW-002 与 TB-FW-003 部分完成，TB-FW-006、TB-FW-007、TB-FW-008 未完成。
- 阶段 0/1 的代码优化尚未开始，当前没有进行中的代码任务。
- 下一步：按 [渐进优化路线图](optimization-roadmap.md) 的阶段 0 启动可观测性与基线，落地顺序以 [编码框架](codec-framework.md) 为准。
- 前置约束：拿到基线前，不预设任何性能、发热或延迟收益。
- 设备验证未解禁：双语资源与系统语言跟随仅经构建、单测与代码审查确认。

> 本轮裁决记录见 [决策看板](decision-board.md)；“已关闭”与“待确认”描述讨论与验证状态，不是实施状态，对应任务仍在待办或等待验证。

## 🚧 进行中

当前无进行中任务。

任务进入“进行中”时，应同时写清范围、交付物和验证方式；完成后先补验证证据，再移入“已完成”。

## 📋 待办

| ID | 状态 | 优先级 | 标题 | 创建 | 完成 | 关联 |
| --- | --- | --- | --- | --- | --- | --- |
| TB-CORE-001 | 📋 待办 | 🔴 P0 | 正确性与资源清理 | 2026-10-17 | — | [路线图阶段 1](optimization-roadmap.md) |
| TB-CORE-002 | 📋 待办 | 🔴 P0 | 会话与渲染目标解耦 | 2026-10-17 | — | [窗口与会话设计](windowing-session-design.md) |
| TB-OBS-001 | 📋 待办 | 🔴 P0 | 可观测性与基线 | 2026-10-17 | — | [路线图阶段 0](optimization-roadmap.md) |
| TB-FW-002 | 🔶 部分完成 | 🟠 P1 | JPEG 适配 | 2026-10-17 | — | [编码框架设计](codec-framework.md) |
| TB-FW-003 | 🔶 部分完成 | 🟠 P1 | H.264 单流 TCP | 2026-10-17 | — | [H.264 实施计划](superpowers/plans/2026-10-18-h264-adapter.md) |
| TB-FW-006 | 📋 待办 | 🟠 P1 | 编码切换编排 | 2026-10-17 | — | [编码框架设计](codec-framework.md) |
| TB-MEDIA-001 | 📋 待办 | 🟠 P1 | H.264 硬编硬解 | 2026-10-17 | — | [媒体架构](media-architecture.md) |
| TB-MEDIA-003 | 📋 待办 | 🟠 P1 | 运行时能力探测 | 2026-10-17 | — | [媒体架构](media-architecture.md) |
| TB-SEC-001 | 📋 待办 | 🟠 P1 | 配对与传输加密 | 2026-10-17 | — | [媒体架构](media-architecture.md) |
| TB-WIN-001 | 📋 待办 | 🟠 P1 | 窗口切换 | 2026-10-17 | — | [窗口与会话设计](windowing-session-design.md) |
| TB-AUDIO-001 | 📋 待办 | 🟡 P2 | 音频链路 | 2026-10-17 | — | [媒体架构](media-architecture.md) |
| TB-CLIENT-001 | 📋 待办 | 🟡 P2 | 客户端画质与多客户端 | 2026-10-17 | — | [媒体架构](media-architecture.md) |
| TB-CLIENT-002 | 📋 待办 | 🟡 P2 | 单流与多档流策略 | 2026-10-17 | — | [媒体架构](media-architecture.md) |
| TB-CTRL-001 | 📋 待办 | 🟡 P2 | 单客户端反控 | 2026-10-17 | — | [媒体架构](media-architecture.md) |
| TB-ENG-001 | 📋 待办 | 🟡 P2 | 测试与发布工程 | 2026-10-17 | — | [项目现状](project-assessment.md) |
| TB-ENG-002 | 📋 待办 | 🟡 P2 | 发布签名加固 | 2026-10-17 | — | [启动与签名清单](startup-checklist.md) |
| TB-FW-007 | 📋 待办 | 🟡 P2 | HEVC 扩展验证 | 2026-10-17 | — | [编码框架设计](codec-framework.md) |
| TB-FW-008 | 📋 待办 | 🟡 P2 | 框架测试与证据 | 2026-10-17 | — | [编码框架设计](codec-framework.md) |

> 说明：`TB-FW-001` 至 `TB-FW-008` 是 `TB-MEDIA-001`、`TB-CLIENT-001` 与 `TB-CLIENT-002` 的框架分解，实施时合并跟踪，不重复计算完成率。`TB-FW-001`、`TB-FW-004`、`TB-FW-005` 已完成（隔离契约、Hub/队列与客户端状态机的 JVM 验证）；`TB-FW-002` 部分完成（JPEG 参考适配与 JVM E2E）；`TB-FW-003` 部分完成（H.264 适配器与两阶段配置发布、JVM/fake 验证；未接现有投屏、未做切换编排与 TCP/设备验证）；`TB-FW-006`、`TB-FW-007`、`TB-FW-008` 仍未完成，不代表 H.264 投屏接入、RTP 或设备验证已经存在。

## ✅ 已完成

| ID | 状态 | 优先级 | 标题 | 创建 | 完成 | 关联 |
| --- | --- | --- | --- | --- | --- | --- |
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

> 说明：编码框架的隔离核心契约、静态注册、单活动流 Runtime、StreamHub、每客户端有界队列、客户端六状态机与 JPEG 独立帧参考适配已完成并通过 JVM 验证（Task 1–9）；已完成项不接现有投屏。另有 H.264 适配器与两阶段配置发布已落地（仅经 JVM/fake 及编译验证），同样未接现有投屏、未做切换编排与设备验证；RTP/HEVC/AV1 未实现。其余代码优化任务（TB-CORE、TB-MEDIA、TB-WIN、TB-CLIENT、TB-AUDIO、TB-CTRL 等）仍未开始。

## ⏳ 等待验证

| ID | 状态 | 优先级 | 标题 | 创建 | 完成 | 关联 |
| --- | --- | --- | --- | --- | --- | --- |
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

<a id="tb-core-001"></a>
<details><summary>TB-CORE-001 正确性与资源清理（📋 待办）</summary>

- **状态 / 优先级 / 创建 / 完成**：📋 待办 / 🔴 P0 / 2026-10-17 / —
- **任务**：正确性与资源清理：幂等停止、单一生命周期所有者、线程池终止、缩短临界区、媒体路径移除 `CallerRunsPolicy`、resize 代次隔离、权限撤销降级、重连退避、IPv6 地址与 NSD 服务类型修正、服务名隐私。
- **交付物 / 验证证据**：交付：代码改动与单元/仪器/压力测试。验证：阶段 1 的启动停止、旋转尺寸、网络、慢客户端、权限、回归各项通过；当前未执行。
- **关联文档**：[路线图阶段 1](optimization-roadmap.md)、[项目现状风险清单](project-assessment.md)

</details>

<a id="tb-core-002"></a>
<details><summary>TB-CORE-002 会话与渲染目标解耦（📋 待办）</summary>

- **状态 / 优先级 / 创建 / 完成**：📋 待办 / 🔴 P0 / 2026-10-17 / —
- **任务**：`ClientSession` 与 `RenderTarget` 解耦：Service 持有会话，窗口只 attach/detach；关闭窗口不等于停止会话；先用现有 JPEG 与悬浮窗接入。
- **交付物 / 验证证据**：交付：会话抽象、悬浮窗接入、状态查询。验证：阶段 1“会话”项与窗口设计验收中的“切换不重连、不停止 Service”；当前未执行。
- **关联文档**：[路线图阶段 1](optimization-roadmap.md)、[窗口与会话设计](windowing-session-design.md)

</details>

<a id="tb-obs-001"></a>
<details><summary>TB-OBS-001 可观测性与基线（📋 待办）</summary>

- **状态 / 优先级 / 创建 / 完成**：📋 待办 / 🔴 P0 / 2026-10-17 / —
- **任务**：可观测性与基线：统一指标接口，覆盖服务端采集/编码/发送、客户端接收/解码/渲染、网络与设备状态；建立测试矩阵并记录当前 JPEG 路径基线。
- **交付物 / 验证证据**：交付：指标定义、调试导出、基线报告。验证：阶段 0 的“指标可用、隐私、开销可控、基线完整、无行为变化”全部满足；当前未执行。
- **关联文档**：[路线图阶段 0](optimization-roadmap.md)、[项目现状](project-assessment.md)

</details>

<a id="tb-fw-002"></a>
<details><summary>TB-FW-002 JPEG 适配（🔶 部分完成）</summary>

- **状态 / 优先级 / 创建 / 完成**：🔶 部分完成 / 🟠 P1 / 2026-10-17 / —
- **任务**：JPEG 适配：独立像素路径，只有完整帧可加入；慢客户端只保留最新完整帧；把现有 JPEG 路径接入框架。
- **交付物 / 验证证据**：已完成：`core/codec/jpeg` 的独立帧参考适配（完整性校验、完整帧才可加入、`KEEP_LATEST_FRAME`）、`JpegCodec` 静态注册与 JPEG 空配置端到端（descriptor 先于 AU、客户端 `STREAMING`、`FakeRenderTarget` 收到完整帧）；`StreamFrameworkEndToEndTest.jpegEmptyConfig_worksEndToEnd` 与 `JpegCodecTest` 通过，全量 155 tests。未完成：像素获取迁移、Service/UI 接线、旧 JPEG 路径切换、真实设备验证。**不得宣称已接入现有投屏。**
- **关联文档**：[编码框架设计](codec-framework.md)、[DB-CODEC-009](decision-board.md)

</details>

<a id="tb-fw-003"></a>
<details><summary>TB-FW-003 H.264 单流 TCP（🔶 部分完成）</summary>

- **状态 / 优先级 / 创建 / 完成**：🔶 部分完成 / 🟠 P1 / 2026-10-17 / —
- **任务**：H.264 单流 TCP：`MediaCodec` 骨架、Surface 输入、明确 IDR 才可加入、`configEpoch`、配置缓存、有界队列与 `RESYNCING`。
- **交付物 / 验证证据**：已完成：H.264 适配器与两阶段配置发布，覆盖 Annex B 解析、MediaCodec bridge 契约/fake、Runtime 两阶段配置、StreamHub 就绪门、H.264 encoder/decoder、Android bridge 与 JVM E2E；代码提交范围 `8161673..9cb8af9`，关键提交 `8161673`、`fccc955`、`8beb8f1`、`f8b70c7`、`2764fec`、`f8a83bf`、`9ea81fa`、`e607249`、`a4d94ea`、`4f98d0a`、`9cb8af9`（最终集中修复）。验证：JVM **364 tests / 0 failures / 0 errors / 0 skipped** 通过；`:app:assembleDebug` 与 `:app:assembleDebugAndroidTest` 成功，androidTest **仅编译、未运行**；各提交 Build CI 通过。未完成：未接现有投屏、未做切换编排、未做 TCP/设备验证。**不得宣称已接入现有投屏或已完成问题 3。**
- **关联文档**：[H.264 实施计划](superpowers/plans/2026-10-18-h264-adapter.md)、[编码框架设计](codec-framework.md)、[DB-CODEC-013](decision-board.md)、[DB-MULTI-005](decision-board.md)、[路线图阶段 2](optimization-roadmap.md)

</details>

<a id="tb-fw-006"></a>
<details><summary>TB-FW-006 编码切换编排（📋 待办）</summary>

- **状态 / 优先级 / 创建 / 完成**：📋 待办 / 🟠 P1 / 2026-10-17 / —
- **任务**：编码切换编排：新编码器预热、客户端保持最后一帧、切换采集输出 Surface、发布新描述符/配置/可加入点、客户端重配或重建解码器、释放旧编码器；网络、认证与控制会话不重建。
- **交付物 / 验证证据**：交付：切换编排与失败回退。验证：切换不保证零间隙但无花屏；预热失败保留旧编码器；当前未实施。
- **关联文档**：[编码框架设计](codec-framework.md)、[DB-CODEC-014](decision-board.md)

</details>

<a id="tb-media-001"></a>
<details><summary>TB-MEDIA-001 H.264 硬编硬解（📋 待办）</summary>

- **状态 / 优先级 / 创建 / 完成**：📋 待办 / 🟠 P1 / 2026-10-17 / —
- **任务**：H.264 硬编硬解（先 TCP）：`VirtualDisplay` 直接输出到 `MediaCodec` 输入 Surface；H.264 Baseline 为兼容基线与回退档；缓存 config 与 IDR；媒体走独立 TCP 帧协议；JPEG 仅保留快照与降级。
- **交付物 / 验证证据**：交付：采集/编码/封装/解码/渲染链路与降级开关。验证：编码器与解码器矩阵、稳态无全尺寸 Bitmap/JPEG、新客户端从 config+IDR 加入、慢客户端隔离；当前未执行。
- **关联文档**：[路线图阶段 2](optimization-roadmap.md)、[媒体架构](media-architecture.md)

</details>

<a id="tb-media-003"></a>
<details><summary>TB-MEDIA-003 运行时能力探测（📋 待办）</summary>

- **状态 / 优先级 / 创建 / 完成**：📋 待办 / 🟠 P1 / 2026-10-17 / —
- **任务**：运行时能力探测：服务端探测编码器 codec/profile/level/宽高/帧率/码率/硬件实例与热状态；客户端探测解码能力与 adaptive playback；经 HELLO/STREAM_INFO 交换，字段可缺省；旧客户端按 H.264 兼容档处理。
- **交付物 / 验证证据**：交付：能力探测模块、协议字段与降级规则。验证：服务端与客户端能力分别上报且与实际一致；不凭 API 等级或设备形态推断；不可用/未实测组合回退 H.264 且用户可见；当前未执行。
- **关联文档**：[媒体架构能力探测](media-architecture.md)、[决策 DB-CODEC-006](decision-board.md)、[路线图阶段 2](optimization-roadmap.md)

</details>

<a id="tb-sec-001"></a>
<details><summary>TB-SEC-001 配对与传输加密（📋 待办）</summary>

- **状态 / 优先级 / 创建 / 完成**：📋 待办 / 🟠 P1 / 2026-10-17 / —
- **任务**：配对、会话令牌与传输加密：默认拒绝未配对客户端；控制面 TLS；媒体面 TLS 或 DTLS-SRTP；观看、音频、反控分级授权；发现默认不广播设备名。
- **交付物 / 验证证据**：交付：配对流程、令牌校验、加密配置、安全提示。验证：未配对连接被拒绝、传输加密生效、日志脱敏；当前未执行。
- **关联文档**：[项目现状安全边界](project-assessment.md)、[媒体架构安全模型](media-architecture.md)、[路线图阶段 2](optimization-roadmap.md)

</details>

<a id="tb-win-001"></a>
<details><summary>TB-WIN-001 窗口切换（📋 待办）</summary>

- **状态 / 优先级 / 创建 / 完成**：📋 待办 / 🟠 P1 / 2026-10-17 / —
- **任务**：窗口切换：第一版用同一悬浮窗展开/收起；`ViewerActivity` 作为需要沉浸式、返回键或输入法时的增强；最后帧快照过渡；`setOutputSurface` 失败只重建解码器。
- **交付物 / 验证证据**：交付：方案 A、方案 B、快照与失败回退。验证：切换前后网络/会话/认证不重建、Service 不停、无长时间黑屏；当前未执行。
- **关联文档**：[路线图阶段 3](optimization-roadmap.md)、[窗口与会话设计](windowing-session-design.md)

</details>

<a id="tb-audio-001"></a>
<details><summary>TB-AUDIO-001 音频链路（📋 待办）</summary>

- **状态 / 优先级 / 创建 / 完成**：📋 待办 / 🟡 P2 / 2026-10-17 / —
- **任务**：音频：API 29+ 使用 `AudioPlaybackCapture`；API 25–28 默认无声或显式麦克风模式；AAC-LC 兼容优先；统一会话单调时钟与客户端 jitter buffer。
- **交付物 / 验证证据**：交付：可选音频链路、权限与拒绝提示、同步策略。验证：阶段 4 的权限、版本降级、音画同步、编码项通过；当前未执行。
- **关联文档**：[路线图阶段 4](optimization-roadmap.md)、[媒体架构音频](media-architecture.md)

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
- **交付物 / 验证证据**：已具备 JVM 证据：`StreamFrameworkEndToEndTest`、`StreamHubTest`、`StreamRuntimeTest`、`ClientSendSessionTest`、`ClientMediaSessionTest`、`JpegCodecTest` 等 13 类 155 tests / 0 failures；H.264 最终集中修复（`9cb8af9`）后全量 364 tests / 0 failures，覆盖 H.264 JVM E2E，`:app:assembleDebug` 与 `:app:assembleDebugAndroidTest` 成功（androidTest 未运行），Build CI 通过；设备与网络验证记录仍未执行，因此本任务仍未完成。
- **关联文档**：[编码框架设计](codec-framework.md)、[路线图阶段 2](optimization-roadmap.md)、[路线图阶段 4](optimization-roadmap.md)

</details>

### ✅ 已完成条目

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
