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
- 媒体路径生产接线（批次 A–H）已完成代码层面：双向流协议、StreamRuntime/StreamHub 服务端接线、H.264 采集直通、客户端 ClientMediaSession/Surface 渲染、JPEG 回退、能力选档与切换编排均已落地；仅经 JVM/fake 与编译验证，详见 [TB-FW-003](#tb-fw-003)。
- 问题 3 代码层面已收官（批次 A–H），三个边角项 [TB-ENG-005](#tb-eng-005)、[TB-ENG-006](#tb-eng-006)、[TB-ENG-007](#tb-eng-007) 已登记遗留；下一步等用户准备好设备后进入设备实测（设备验证未解禁）；问题 3 仍未关闭。
- 未做设备/真机/模拟器验证：全程未运行 adb、`connectedAndroidTest`、安装或启动 App；编码切换、Surface 恢复与多客户端行为均无实测数据。
- 编码框架隔离核心（契约、StreamHub、有界队列、六状态机、JPEG 参考适配）已完成并通过 JVM 验证，见 [TB-FW-001](#tb-fw-001)、[TB-FW-004](#tb-fw-004)、[TB-FW-005](#tb-fw-005)、[TB-FW-002](#tb-fw-002)。
- 问题 3 尚未由用户明确关闭，不能标记完成；代码完成 ≠ 问题关闭，API 25 只是兼容下限，能力按运行时探测分档。
- 编码框架待收尾：TB-FW-003、TB-FW-006、TB-MEDIA-001、TB-MEDIA-003 等待设备验证；TB-FW-002 仍部分完成；TB-FW-007、TB-FW-008 未完成。
- 设备发现功能（[TB-DISC-001](#tb-disc-001)..[TB-DISC-009](#tb-disc-009)）B1–B5 已全部完成：B1（[TB-DISC-002](#tb-disc-002)、[TB-DISC-003](#tb-disc-003)）、B2（[TB-DISC-004](#tb-disc-004)）、B3（[TB-DISC-005](#tb-disc-005)、[TB-DISC-006](#tb-disc-006)）、B4（[TB-DISC-007](#tb-disc-007)、[TB-DISC-008](#tb-disc-008)）与 B5（[TB-DISC-009](#tb-disc-009)）；全量 **670 tests / 0 failures / 0 errors / 0 skipped**（58 个测试类），各批 Build 与 Checks CI 均 success。后续待办：[TB-DISC-010](#tb-disc-010)（自签 TLS + TOFU pinning）后置；设备验证仍未解禁。
- 代码结构梳理（[TB-ENG-011](#tb-eng-011)）已完成 R0–R5：命名规范 [naming-conventions.md](naming-conventions.md) 自 2026-10-20 生效，六个批次均为纯移动/改名（R5 为主题名去平台词与组播工厂改名），行为零变化核验通过，全量仍为 **670 tests / 0 failures**（58 个测试类）；新代码与后续批次按该规范执行，遗留观察（`ClientObserver` 同名、`service` 上帝包、`core.codec` 包环）见条目详情。
- B3 已落地 UDP 组播公告（启动 3 连发、5/15 s 心跳、停服告别、`MulticastLock` 引用计数）与 Register 双向握手（成功才确认、`announces` 单跳防回环、限速与限包长）；仅经 JVM/fake 与编译验证，同网段互见、真实组播与 Reverse Register 未做设备实测。
- B4 已落地分级扫描（按接口前缀生成候选、1 s grace 零确认才升级、50 并发/500 ms 探测/8 s 预算、可取消）、收藏持久化与上次成功地址优先探测、手动 IP 常驻入口与 8 s 空列表提示、连接前指纹校验；仅经 JVM/fake 与编译验证，真实网段扫描、组播被过滤场景与手动探测未做设备实测。
- B5 已落地发现设置（组播开关/组地址/端口、三档刷新、四档可见性、仅 mDNS 兼容模式、广播设备名）、`DeviceStore` 统一列表与共享屏/观看者分区、状态徽标与 STALE/UNREACHABLE 灰显、`LssDeviceInfo` 的 `Intent` 契约迁移；仅经 JVM/fake、编译与 lint 验证，真实设置生效、可见性对公告/Register 的影响与列表状态未做设备实测。
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
| TB-DISC-010 | 📋 待办 | 🟡 P2 | 传输加密与身份 pinning | 2026-10-20 | — | [发现调研](discovery-research.md) |
| TB-ENG-005 | 📋 待办 | 🟡 P2 | 新偏好设置界面控件 | 2026-10-19 | — | [问题3接线计划](superpowers/plans/2026-10-19-media-path-wiring.md) |
| TB-ENG-006 | 📋 待办 | 🟡 P2 | JPEG 降级客户端查看器接回 | 2026-10-19 | — | [问题3接线计划](superpowers/plans/2026-10-19-media-path-wiring.md) |
| TB-ENG-007 | 📋 待办 | 🟡 P2 | 客户端发送统计并入服务端统计 | 2026-10-19 | — | [问题3接线计划](superpowers/plans/2026-10-19-media-path-wiring.md) |
| TB-CORE-001 | 📋 待办 | 🔴 P0 | 正确性与资源清理 | 2026-10-17 | — | [路线图阶段 1](optimization-roadmap.md) |
| TB-CORE-002 | 📋 待办 | 🔴 P0 | 会话与渲染目标解耦 | 2026-10-17 | — | [窗口与会话设计](windowing-session-design.md) |
| TB-OBS-001 | 📋 待办 | 🔴 P0 | 可观测性与基线 | 2026-10-17 | — | [路线图阶段 0](optimization-roadmap.md) |
| TB-FW-002 | 🔶 部分完成 | 🟠 P1 | JPEG 适配 | 2026-10-17 | — | [编码框架设计](codec-framework.md) |
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

> 说明：`TB-FW-001` 至 `TB-FW-008` 是 `TB-MEDIA-001`、`TB-CLIENT-001` 与 `TB-CLIENT-002` 的框架分解，实施时合并跟踪，不重复计算完成率。`TB-FW-001`、`TB-FW-004`、`TB-FW-005` 已完成（隔离契约、Hub/队列与客户端状态机的 JVM 验证）；`TB-FW-002` 部分完成（JPEG 参考适配与 JVM E2E，批次 D 已接入生产降级路径）；`TB-FW-003`、`TB-FW-006`、`TB-MEDIA-001`、`TB-MEDIA-003` 已转「⏳ 等待验证」（批次 A–H 完成生产接线、切换编排、H.264 硬编硬解与能力选档，仅 JVM/fake 与编译验证）；`TB-FW-007`、`TB-FW-008` 仍未完成。代码完成不代表设备验证、RTP 或问题关闭已经存在。

## ✅ 已完成

| ID | 状态 | 优先级 | 标题 | 创建 | 完成 | 关联 |
| --- | --- | --- | --- | --- | --- | --- |
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

<a id="tb-disc-010"></a>
<details><summary>TB-DISC-010 传输加密与身份 pinning（📋 待办）</summary>

- **状态 / 优先级 / 创建 / 完成**：📋 待办 / 🟡 P2 / 2026-10-20 / —
- **任务**：自签 TLS + TOFU 指纹确认；Register 与媒体流加密；与 [TB-SEC-001](#tb-sec-001) 协同；本项可后置。
- **交付物 / 验证证据**：交付：TLS 配置、指纹确认流程、pinning 校验与测试。验证：首次连接需确认指纹、指纹变化被拒绝、Register 与媒体流均加密；当前未实施，设备验证未解禁。
- **关联文档**：[发现调研](discovery-research.md)、[TB-SEC-001](#tb-sec-001)

</details>

<a id="tb-eng-005"></a>
<details><summary>TB-ENG-005 新偏好设置界面控件（📋 待办）</summary>

- **状态 / 优先级 / 创建 / 完成**：📋 待办 / 🟡 P2 / 2026-10-19 / —
- **任务**：批次 D 已把码率、IDR 间隔与编码模式（H.264/JPEG）三个偏好落入 `Preferences`（`KEY_PROJECTION_BITRATE`、`KEY_PROJECTION_IDR_INTERVAL`、`KEY_PROJECTION_CODEC`），但 `MainActivity` 设置面板与布局无对应控件，当前只能用默认值；需补设置 UI 并贯通到 `StreamParameters` 与 codec 选择。
- **交付物 / 验证证据**：交付：设置控件、偏好读写与参数贯通代码、相关单测。验证：改档后 `StreamParameters` 与 codec 选择按新值生效；当前未实施，设备验证未解禁。
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
<details><summary>TB-ENG-007 客户端发送统计并入服务端统计（📋 待办）</summary>

- **状态 / 优先级 / 创建 / 完成**：📋 待办 / 🟡 P2 / 2026-10-19 / —
- **任务**：`ClientSendSession.getStats()` 产出队列深度、丢弃与重同步计数，但未聚合进 `LssServerStats`，UI 与通知不可见。需把 `ClientSendStats` 并入服务端统计输出。
- **交付物 / 验证证据**：交付：统计聚合、UI/通知展示与单测。验证：服务端统计包含每客户端队列深度、丢弃与重同步计数；当前未实施，设备验证未解禁。
- **关联文档**：[问题3接线计划](superpowers/plans/2026-10-19-media-path-wiring.md)

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
- **交付物 / 验证证据**：已完成：`core/codec/jpeg` 的独立帧参考适配（完整性校验、完整帧才可加入、`KEEP_LATEST_FRAME`）、`JpegCodec` 静态注册与 JPEG 空配置端到端；批次 D `8a9c921` 新增 `FrameInputEncoder` 与 `StreamRuntime.submitFrame`，JPEG 回退经框架运行，Service/UI 参数贯通，旧 JPEG RPC 移除。验证：`StreamFrameworkEndToEndTest.jpegEmptyConfig_worksEndToEnd`、`JpegCodecTest`、`StreamRuntimeSubmitFrameTest` 通过，全量 JVM **396 tests / 0 failures / 0 errors / 0 skipped**。未完成：真实设备验证；JPEG 降级模式仍走 ImageReader→Bitmap→压缩（设计路径）。**不得宣称已完成设备验证或关闭问题 3。**
- **关联文档**：[编码框架设计](codec-framework.md)、[DB-CODEC-009](decision-board.md)

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
- **任务**：窗口切换：第一版用同一悬浮窗展开/收起；`ViewerActivity` 作为需要沉浸式、返回键或输入法时的增强；最后帧快照过渡；Surface 失效后经 `retryConfigure()` 重建解码器恢复。
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
- **交付物 / 验证证据**：已具备 JVM 证据：`StreamFrameworkEndToEndTest`、`StreamHubTest`、`StreamRuntimeTest`、`ClientSendSessionTest`、`ClientMediaSessionTest`、`JpegCodecTest` 等 13 类 155 tests / 0 failures；H.264 最终集中修复（`9cb8af9`）后全量 364 tests / 0 failures，覆盖 H.264 JVM E2E，`:app:assembleDebug` 与 `:app:assembleDebugAndroidTest` 成功（androidTest 未运行），Build CI 通过；批次 A–H 后全量 **396 tests / 0 failures**（新增 32），覆盖协议映射、服务端接线、客户端接收、能力选档与 deferred 切换，各批 Build CI success；设备与网络验证记录仍未执行，因此本任务仍未完成。
- **关联文档**：[编码框架设计](codec-framework.md)、[路线图阶段 2](optimization-roadmap.md)、[路线图阶段 4](optimization-roadmap.md)

</details>

### ✅ 已完成条目

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
