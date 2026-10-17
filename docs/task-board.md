# 任务看板

> 用途：让作者和 AI 在开始工作前快速确认“现在做什么、接下来做什么、哪些还没验证”，不替代设计文档和验收标准。
> 当前基线：文档体系已完成（提交 `7a8a05d`）；CI 依赖仓库顺序问题已修复（提交 `302e604`），CI 修复与验证证据已记录（提交 `2aa5bbf`）；此前已补齐采集与多客户端裁决（提交 `f7c7a5b`），并修订设备能力分档裁决（提交 `02b2d9f`）。编码框架设计已获用户批准（提交 `b4fad88`），状态为“已批准，待实施/验证”。此前补充启动与签名检查清单（提交 `docs: 补充启动与签名检查清单`），记录当前环境、Debug 启动条件与正式发布签名边界；此前未安装 APK、未修改设备、未实机验证。此后完成 Release 签名包构建，并将同一 APK 覆盖安装到两台虚拟机与一台实机；应用未启动，未授予运行时权限，未清除应用数据。代码优化尚未开始，当前没有进行中的代码任务；问题 3 仍未由用户明确关闭。
> 证据口径：“已完成”必须有可核对的交付物或提交；“待办”和“等待验证”只代表计划或待验证条件，不代表已经取得结果。

## 状态与优先级

| 状态 | 含义 |
| --- | --- |
| 待办 | 已进入路线图，尚未开始 |
| 进行中 | 已有明确执行者与范围，正在实施 |
| 已完成 | 交付物已落地，且写明了可核对的验证证据 |
| 暂缓 | 当前不排期，保留触发条件 |
| 等待验证 | 方案或需求尚未被实测/产品验证，不能当作结论 |

| 优先级 | 含义 |
| --- | --- |
| P0 | 先做：建立基线，或消除正确性、资源与稳定性风险 |
| P1 | 核心改造：媒体路径、安全前提、窗口连续性 |
| P2 | 增强能力：音频、反控、客户端画质与工程配套 |
| P3 | 条件触发：没有证据或产品确认前不排期 |

## 当前焦点

文档体系与两个看板已经建立，当前事实与设计裁决有了统一入口。问题 1（采集 API 选择）已由用户关闭；问题 3（采集、编码参数与多客户端）的裁决已记录，并已按“设备能力分档”修订此前过度保守的表述，待确认与实机验证。代码优化尚未开始，当前没有进行中的代码任务。

本轮修订明确：API 25/minSdk 只是最低兼容与回退路径，不是能力上限；服务端编码能力与客户端解码能力分别运行时探测，按实际能力和场景选档；H.264 是兼容基线不是上限；车机只是场景之一，手机、平板与其他高性能设备同样适用。问题 3 仍未由用户明确关闭，不能标记完成。

本轮新增 [可扩展编码框架设计](codec-framework.md)：用户已明确批准“静态 Codec SPI + 单活动流核心 + 可扩展 StreamHub”，第一版用 H.264 与 JPEG 两条差异实现验证契约，HEVC 验证 `MediaCodec` 骨架复用，AV1/HDR/B 帧/时域层只预留契约。裁决状态是“已批准，待实施/验证”，没有代码、构建或实机证据；不能把任何组件写成已实现。

下一步建议按 [渐进优化路线图](optimization-roadmap.md) 的阶段 0 启动可观测性与基线；编码框架的落地顺序以 [编码框架落地顺序](codec-framework.md) 为准，但仍受阶段 0/1 的前置约束。在确认设备、网络与排期后再把对应任务移入“进行中”。在拿到基线前，不预设任何性能、发热或延迟收益。

## 本轮裁决同步（问题 1 / 问题 3 / 编码框架）

“已关闭（裁决完成）”和“裁决已记录，待确认/待实机验证”描述的是讨论与验证状态，不是实施状态。对应实现任务仍在待办或等待验证；裁决不等于实施。

| 议题 | 状态 | 结论 | 证据 / 边界 | 关联 |
| --- | --- | --- | --- | --- |
| 问题 1：采集 API 选择 | 已关闭（裁决完成） | 保留 `MediaProjection`；主链路改为 `VirtualDisplay` 直接输出到 `MediaCodec` 输入 Surface，稳态不经过 `ImageReader`、Bitmap 与软件 JPEG。 | 用户明确关闭，裁决已记录。代码未实施，未实机验证。 | [DB-CAP-001](decision-board.md)、[DB-CAP-002](decision-board.md)、[媒体架构](media-architecture.md) |
| 问题 3：采集、编码参数与多客户端 | 裁决已记录，待确认/待实机验证 | 一个 MediaProjection/VirtualDisplay，通常一路编码；每客户端独立网络会话、有界队列与解码器；分辨率与编码器尺寸匹配；`KEY_FRAME_RATE` 只是目标提示；码率与 IDR 间隔可配置；HELLO/STREAM_INFO/WAIT_IDR/STREAMING；窗口缩放不省网络与解码；队列溢出进入 WAIT_IDR；多档编码不新建 MediaProjection。 | 用户已给出完整技术回答，但尚未明确关闭；本轮只同步文档，未改代码，未实机验证。 | [DB-CAP-002](decision-board.md)、[DB-CODEC-003](decision-board.md)、[DB-CODEC-004](decision-board.md)、[DB-CODEC-005](decision-board.md)、[DB-SESSION-002](decision-board.md)、[DB-MULTI-002](decision-board.md)、[DB-MULTI-003](decision-board.md) |
| 问题 3 修订：设备能力分档 | 裁决已修订记录，待确认/待实机验证 | 修正“自动永远只选 H.264”“按低能力车机限制全产品”“API 25 决定能力上限”等保守表述。API 25/minSdk 只决定最低兼容与回退；服务端编码能力与客户端解码能力分别运行时探测，按实际能力和场景选档；H.264 是兼容基线不是上限，HEVC 双方支持时可用，AV1 仅作设备明确支持时的后续高级选项；多客户端分兼容单流与多档流，新客户端不得迫使已有客户端无提示降级；未实测前可回退 H.264，不做盲目自动切换。 | 用户明确指出 API 25 只是下限、车机只是场景之一、Pad 也可能达到旗舰能力，并裁决修正此前表述。旧保守表述保留在决策看板的裁决演进中；问题仍未关闭，未实机验证。 | [DB-CAP-003](decision-board.md)、[DB-CODEC-006](decision-board.md)、[DB-CODEC-007](decision-board.md)、[DB-CODEC-008](decision-board.md)、[DB-MULTI-004](decision-board.md)、[DB-CODEC-001](decision-board.md)、[DB-MULTI-003](decision-board.md) |
| 编码框架方案 | 已批准，待实施/验证 | 静态 Codec SPI + 单活动流核心 + 可扩展 StreamHub；H.264 与 JPEG 验证契约，HEVC 验证 `MediaCodec` 复用，AV1/HDR/B 帧/时域层仅预留；描述符与运行参数分离；可加入点独立于关键帧标记；第一版编码切换不保证零间隙；传输按 TCP 完整访问单元、RTP 封包器、WebRTC 独立媒体栈解耦。 | 用户已明确“批准”。本轮只新增设计文档并更新看板/入口，未改产品代码或构建配置，未执行构建与实机验证。问题 3 未被用户明确关闭，不因本方案获批而标记关闭。 | [编码框架设计](codec-framework.md)、[DB-CODEC-009](decision-board.md)、[DB-CODEC-010](decision-board.md)、[DB-CODEC-011](decision-board.md)、[DB-CODEC-012](decision-board.md)、[DB-CODEC-013](decision-board.md)、[DB-CODEC-014](decision-board.md)、[DB-MULTI-005](decision-board.md)、[DB-NET-002](decision-board.md) |

## 进行中

当前无进行中任务。

任务进入“进行中”时，应同时写清范围、交付物和验证方式；完成后先补验证证据，再移入“已完成”。

## 待办

| ID | 优先级 | 状态 | 任务 | 交付物 / 验证证据 | 关联文档 |
| --- | --- | --- | --- | --- | --- |
| TB-OBS-001 | P0 | 待办 | 可观测性与基线：统一指标接口，覆盖服务端采集/编码/发送、客户端接收/解码/渲染、网络与设备状态；建立测试矩阵并记录当前 JPEG 路径基线。 | 交付：指标定义、调试导出、基线报告。验证：阶段 0 的“指标可用、隐私、开销可控、基线完整、无行为变化”全部满足；当前未执行。 | [路线图阶段 0](optimization-roadmap.md)、[项目现状](project-assessment.md) |
| TB-CORE-001 | P0 | 待办 | 正确性与资源清理：幂等停止、单一生命周期所有者、线程池终止、缩短临界区、媒体路径移除 `CallerRunsPolicy`、resize 代次隔离、权限撤销降级、重连退避、IPv6 地址与 NSD 服务类型修正、服务名隐私。 | 交付：代码改动与单元/仪器/压力测试。验证：阶段 1 的启动停止、旋转尺寸、网络、慢客户端、权限、回归各项通过；当前未执行。 | [路线图阶段 1](optimization-roadmap.md)、[项目现状风险清单](project-assessment.md) |
| TB-CORE-002 | P0 | 待办 | `ClientSession` 与 `RenderTarget` 解耦：Service 持有会话，窗口只 attach/detach；关闭窗口不等于停止会话；先用现有 JPEG 与悬浮窗接入。 | 交付：会话抽象、悬浮窗接入、状态查询。验证：阶段 1“会话”项与窗口设计验收中的“切换不重连、不停止 Service”；当前未执行。 | [路线图阶段 1](optimization-roadmap.md)、[窗口与会话设计](windowing-session-design.md) |
| TB-SEC-001 | P1 | 待办 | 配对、会话令牌与传输加密：默认拒绝未配对客户端；控制面 TLS；媒体面 TLS 或 DTLS-SRTP；观看、音频、反控分级授权；发现默认不广播设备名。 | 交付：配对流程、令牌校验、加密配置、安全提示。验证：未配对连接被拒绝、传输加密生效、日志脱敏；当前未执行。 | [项目现状安全边界](project-assessment.md)、[媒体架构安全模型](media-architecture.md)、[路线图阶段 2](optimization-roadmap.md) |
| TB-MEDIA-001 | P1 | 待办 | H.264 硬编硬解（先 TCP）：`VirtualDisplay` 直接输出到 `MediaCodec` 输入 Surface；H.264 Baseline 为兼容基线与回退档；缓存 config 与 IDR；媒体走独立 TCP 帧协议；JPEG 仅保留快照与降级。 | 交付：采集/编码/封装/解码/渲染链路与降级开关。验证：编码器与解码器矩阵、稳态无全尺寸 Bitmap/JPEG、新客户端从 config+IDR 加入、慢客户端隔离；当前未执行。 | [路线图阶段 2](optimization-roadmap.md)、[媒体架构](media-architecture.md) |
| TB-MEDIA-003 | P1 | 待办 | 运行时能力探测：服务端探测编码器 codec/profile/level/宽高/帧率/码率/硬件实例与热状态；客户端探测解码能力与 adaptive playback；经 HELLO/STREAM_INFO 交换，字段可缺省；旧客户端按 H.264 兼容档处理。 | 交付：能力探测模块、协议字段与降级规则。验证：服务端与客户端能力分别上报且与实际一致；不凭 API 等级或设备形态推断；不可用/未实测组合回退 H.264 且用户可见；当前未执行。 | [媒体架构能力探测](media-architecture.md)、[决策 DB-CODEC-006](decision-board.md)、[路线图阶段 2](optimization-roadmap.md) |
| TB-FW-001 | P1 | 待办 | 编码契约骨架与静态注册：`CodecDescriptor`、`EncoderFactory`、`DecoderFactory`、`CodecPolicy`、`PacketizerFactory(codec, transport)`；`StreamDescriptor`/`StreamParameters`、`EncodedAccessUnit`、客户端六状态与协议版本；编译期静态表。 | 交付：接口、静态注册、单元测试与契约审查记录。验证：JPEG 与 H.264 共用同一契约；公共层无 NAL/OBU/JPEG 解析、无 codec 特判；当前未实施。 | [编码框架设计](codec-framework.md)、[DB-CODEC-009](decision-board.md)、[DB-CODEC-010](decision-board.md)、[DB-CODEC-011](decision-board.md)、[DB-CODEC-012](decision-board.md)、[DB-CODEC-013](decision-board.md) |
| TB-FW-002 | P1 | 待办 | JPEG 适配：独立像素路径，只有完整帧可加入；慢客户端只保留最新完整帧；把现有 JPEG 路径接入框架。 | 交付：JPEG 编解码适配与端到端链路。验证：每帧可加入；截断帧被丢弃；描述符与运行参数分离；可回退旧路径；当前未实施。 | [编码框架设计](codec-framework.md)、[DB-CODEC-009](decision-board.md) |
| TB-FW-003 | P1 | 待办 | H.264 单流 TCP：`MediaCodec` 骨架、Surface 输入、明确 IDR 才可加入、`configEpoch`、配置缓存、有界队列与 `RESYNCING`。 | 交付：H.264 编解码、TCP 完整访问单元传输、重同步。验证：只从明确 IDR 加入；分辨率/旋转切换可恢复；慢客户端不阻塞编码器；当前未实施。 | [编码框架设计](codec-framework.md)、[DB-CODEC-013](decision-board.md)、[DB-MULTI-005](decision-board.md)、[路线图阶段 2](optimization-roadmap.md) |
| TB-FW-004 | P1 | 待办 | StreamHub 与每客户端有界队列：单活动流注册与查找、订阅关系、独立队列、背压与统计；单流不兼容时只做快照、显式兼容降级或拒绝。 | 交付：`StreamHub`、`ClientSendSession`、队列与策略钩子。验证：已有客户端不受新客户端影响；关键帧请求合并；不兼容有明确结果；当前未实施。 | [编码框架设计](codec-framework.md)、[DB-CODEC-009](decision-board.md)、[DB-MULTI-005](decision-board.md) |
| TB-FW-005 | P1 | 待办 | 客户端解码状态机：`NEGOTIATING`、`WAIT_CONFIG`、`WAIT_RANDOM_ACCESS`、`STREAMING`、`RESYNCING`、`CLOSED`；描述符应用、解码器重建、epoch 切换与统计。 | 交付：`ClientMediaSession` 状态机与测试。验证：六状态转换完整；同 epoch 可加入点恢复；跨 epoch 先进入 `WAIT_CONFIG`；当前未实施。 | [编码框架设计](codec-framework.md)、[DB-CODEC-013](decision-board.md) |
| TB-FW-006 | P1 | 待办 | 编码切换编排：新编码器预热、客户端保持最后一帧、切换采集输出 Surface、发布新描述符/配置/可加入点、客户端重配或重建解码器、释放旧编码器；网络、认证与控制会话不重建。 | 交付：切换编排与失败回退。验证：切换不保证零间隙但无花屏；预热失败保留旧编码器；当前未实施。 | [编码框架设计](codec-framework.md)、[DB-CODEC-014](decision-board.md) |
| TB-FW-007 | P2 | 待办 | HEVC 扩展验证：复用 `MediaCodec` 骨架，只接受明确安全 IDR；CRA/BLA/RASL/RADL 不作为可加入点。 | 交付：HEVC 描述符、工厂、策略与验证记录。验证：新增 HEVC 不改公共层；CRA/RASL 不被接受；当前未实施。 | [编码框架设计](codec-framework.md)、[DB-CODEC-013](decision-board.md)、[DB-CODEC-009](decision-board.md) |
| TB-FW-008 | P2 | 待办 | 编码框架测试与证据：契约测试、可加入点、epoch 语义、队列溢出、切换、传输适配与回退；补充设备与网络验证记录。 | 交付：测试用例与验证记录。验证：测试可重复运行；未实测项保持待验证；当前未实施。 | [编码框架设计](codec-framework.md)、[路线图阶段 2](optimization-roadmap.md)、[路线图阶段 4](optimization-roadmap.md) |
| TB-WIN-001 | P1 | 待办 | 窗口切换：第一版用同一悬浮窗展开/收起；`ViewerActivity` 作为需要沉浸式、返回键或输入法时的增强；最后帧快照过渡；`setOutputSurface` 失败只重建解码器。 | 交付：方案 A、方案 B、快照与失败回退。验证：切换前后网络/会话/认证不重建、Service 不停、无长时间黑屏；当前未执行。 | [路线图阶段 3](optimization-roadmap.md)、[窗口与会话设计](windowing-session-design.md) |
| TB-CLIENT-001 | P2 | 待办 | 客户端画质与多客户端：分辨率、帧率、码率档位；`configEpoch` 与 IDR 刷新；一次编码按客户端扇出；每客户端有界队列与统计；慢客户端隔离。 | 交付：档位协商、扇出队列、客户端统计。验证：阶段 3 的画质、多客户端、慢客户端项通过；当前未执行。 | [路线图阶段 3](optimization-roadmap.md)、[媒体架构多客户端](media-architecture.md) |
| TB-CLIENT-002 | P2 | 待办 | 兼容单流与多档流策略：默认共同能力交集；多档流让高能力客户端用高规格、其他客户端保留 H.264 档；受编码器实例、功耗与热限制；新客户端不得迫使已有客户端无提示降级；小窗/全屏可选不同档位。 | 交付：选流策略、档位切换与可见回退、多客户端影响评估。验证：多客户端组合下无静默降级；档位切换 configEpoch/IDR 正确；编码器实例与功耗在预算内；无实测收益时保持单流；当前未执行。 | [媒体架构兼容单流与多档编码](media-architecture.md)、[决策 DB-MULTI-004](decision-board.md)、[路线图阶段 3](optimization-roadmap.md) |
| TB-AUDIO-001 | P2 | 待办 | 音频：API 29+ 使用 `AudioPlaybackCapture`；API 25–28 默认无声或显式麦克风模式；AAC-LC 兼容优先；统一会话单调时钟与客户端 jitter buffer。 | 交付：可选音频链路、权限与拒绝提示、同步策略。验证：阶段 4 的权限、版本降级、音画同步、编码项通过；当前未执行。 | [路线图阶段 4](optimization-roadmap.md)、[媒体架构音频](media-architecture.md) |
| TB-CTRL-001 | P2 | 待办 | 单客户端反控：`AccessibilityService.dispatchGesture`；默认关闭、用户手动开启；单客户端租约、心跳、epoch fencing；归一化坐标；敏感界面不注入。 | 交付：反控授权、租约、坐标与可见指示。验证：阶段 4 的反控授权、租约、坐标、安全项通过；当前未执行。 | [路线图阶段 4](optimization-roadmap.md)、[媒体架构反控](media-architecture.md) |
| TB-ENG-001 | P2 | 待办 | 测试与发布工程：补齐状态机、停止幂等、队列、协议、重连与坐标测试；仪器与压力测试；R8/keep 规则；CI 增加独立测试步骤。 | 交付：测试用例、CI 配置、混淆规则。验证：测试可重复运行并阻断回归；当前仅模板测试，未执行新增测试。 | [项目现状工程审查](project-assessment.md)、[路线图阶段 1](optimization-roadmap.md) |
| TB-ENG-002 | P2 | 待办 | 正式发布签名加固：把示例签名回退与正式签名隔离；使用私有 keystore + 本地 `signing.properties` 或 CI secrets；明确保管、备份、轮换与升级一致性；验证同 `applicationId` 的覆盖安装和不同签名的失败提示。 | 交付：签名配置说明、本地/CI 发布流程检查、升级安装验证记录。验证：正式包只由私有签名构建；仓库不含真实材料；同签名可覆盖安装，不同签名失败时明确告知用户卸载会清数据；当前未执行。 | [启动与签名检查清单](startup-checklist.md)、[项目现状安全边界](project-assessment.md) |

说明：`TB-FW-001` 至 `TB-FW-008` 是 `TB-MEDIA-001`、`TB-CLIENT-001` 与 `TB-CLIENT-002` 的框架分解，实施时合并跟踪，不重复计算完成率。它们目前都是待办，不代表任何契约或组件已经存在。

## 已完成

| ID | 优先级 | 状态 | 任务 | 交付物 / 验证证据 | 关联文档 |
| --- | --- | --- | --- | --- | --- |
| TB-DOC-001 | — | 已完成 | 文档体系：根 README、文档索引、项目现状、产品语境、媒体架构、窗口会话设计与优化路线图。 | 交付：提交 `7a8a05d` 中的 7 个文档文件。验证：该提交为静态审查与设计整理，未执行构建、单元测试或实机验证。 | [文档索引](README.md) |
| TB-DOC-002 | — | 已完成 | 任务与决策看板：建立稳定 ID、状态、证据与关联文档；接入文档索引和根 README 文档列表；补充看板维护规则。 | 交付：`docs/task-board.md`、`docs/decision-board.md`、`docs/README.md` 入口与维护规则；根 README 链接。验证：链接、围栏、Mermaid、禁用角色词、敏感信息与行尾空白检查随本次文档提交执行；未运行 Android 构建。 | [决策看板](decision-board.md)、[文档索引](README.md) |
| TB-DOC-003 | — | 已完成 | 本轮裁决同步：记录问题 1（已关闭）与问题 3（待确认/待实机验证）的采集、编码参数与多客户端裁决，补充协议状态、参数语义与多档编码边界。 | 交付：本次提交 `docs: 记录采集与多客户端裁决` 中的 `docs/media-architecture.md`、`docs/decision-board.md`、`docs/task-board.md`、`docs/README.md`。验证：链接、围栏、Mermaid、角色词、敏感信息与表格列数检查；未修改代码或 CI，未运行 Android 构建，未实机验证。 | [决策看板](decision-board.md)、[媒体架构](media-architecture.md) |
| TB-DOC-004 | — | 已完成 | 设备能力分档裁决修订：修正“自动永远只选 H.264”“按老车机能力限制全产品”“API 25 决定能力上限”等保守表述；补充服务端/客户端运行时能力探测、HEVC/AV1 边界、分辨率与帧率场景分档、兼容单流与多档流策略；在决策看板保留裁决演进痕迹。 | 交付：本次提交 `docs: 修订设备能力分档裁决` 中的根 `README.md`、`docs/README.md`、`docs/media-architecture.md`、`docs/decision-board.md`、`docs/task-board.md`、`docs/product-context.md`、`docs/project-assessment.md`、`docs/optimization-roadmap.md`、`docs/windowing-session-design.md`。验证：链接、围栏、Mermaid、角色词、敏感信息、表格列数与 `git diff` 检查；未修改代码或 CI，未运行 Android 构建，未实机验证。问题 3 本身仍未由用户关闭，不因本文档任务完成而标记关闭。 | [决策看板](decision-board.md)、[媒体架构](media-architecture.md)、[产品语境](product-context.md) |
| TB-DOC-005 | — | 已完成 | 可扩展编码框架设计：记录用户已批准的“静态 Codec SPI + 单活动流核心 + 可扩展 StreamHub”；明确责任边界、扩展点、描述符与运行参数、访问单元、可加入点与六状态客户端、慢客户端、非无缝编码切换、传输解耦、落地顺序、验收标准与非目标；同步文档入口与两个看板。 | 交付：`docs/codec-framework.md`，以及根 `README.md`、`docs/README.md`、`docs/media-architecture.md`、`docs/optimization-roadmap.md`、`docs/decision-board.md`、`docs/task-board.md` 的入口与裁决更新（提交 `b4fad88`）。验证：链接、围栏、Mermaid、角色词、敏感信息、表格列数、`git diff --check` 与变更范围检查通过；未修改产品代码或构建配置；本地未执行 Android 构建；推送后远端 Build CI 成功（run [39036309808](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39036309808)）；未实机验证。框架裁决为“已批准，待实施/验证”，所有实现任务仍在待办。 | [编码框架设计](codec-framework.md)、[决策看板](decision-board.md) |
| TB-DOC-006 | — | 已完成 | 启动与签名检查清单：区分当前环境编译/安装缺项、设备与运行时条件、Debug/Release/正式发布签名要求；记录真实权限与未知项；同步根 README 与文档索引；新增正式发布签名加固待办。 | 交付：`docs/startup-checklist.md`、根 `README.md`、`docs/README.md`、`docs/task-board.md`。验证：本地 `./gradlew :app:assembleDebug --offline` 与 `:app:assembleRelease --offline` 成功；未安装 APK、未连接或修改设备；未执行服务端/客户端实机联调。示例签名只做概括，文档不含任何真实签名值、口令或密钥内容。 | [启动与签名检查清单](startup-checklist.md) |
| TB-DOC-007 | — | 已完成 | Release 签名包构建与三设备安装：使用私有签名材料完成 Release 构建，并将同一 APK 覆盖安装到两台虚拟机和一台实机。 | 交付：`app/build/outputs/apk/release/app-release.apk`。验证：`./gradlew :app:assembleRelease` 成功；本机签名校验有效（APK Signature Scheme v2，单签名者）；三台设备 `adb install -r` 均返回 Success，`pm path` 均确认目标 `applicationId` 已安装；无签名冲突或其他阻塞。未启动应用，未授予运行时权限，未卸载、清除数据或恢复出厂。 | [启动与签名检查清单](startup-checklist.md) |
| TB-CI-001 | P0 | 已完成 | CI 构建稳定性：修复依赖仓库顺序。 | 根因：失败 run [39030949452](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39030949452) 在解析 `io.grpc:grpc-context:1.72.0` 时收到阿里云镜像 HTTP 502，Gradle 未回退到后续官方仓库。交付：`settings.gradle` 将 `google()`、`mavenCentral()` 置于镜像之前，镜像保留为后备。验证：本地 `./gradlew build` 成功（113 tasks）；`--refresh-dependencies` 解析日志为 0 次阿里云、70 次 Maven Central、67 次 Google；修复提交 `302e604` 触发的 run [39032521963](https://github.com/obby-xiang/LocalScreenShareAndroid/actions/runs/39032521963) 成功。 | [项目现状构建基线](project-assessment.md) |

代码任务完成情况：当前没有已完成的代码优化任务。已完成项涉及文档、看板与构建仓库配置；仓库顺序调整不改变应用行为。编码框架的所有组件与契约仍为待办，未写任何实现代码。

## 暂缓 / 等待验证

### 等待验证（有明确触发条件）

| ID | 优先级 | 状态 | 事项 | 触发条件 / 所需证据 | 关联文档 |
| --- | --- | --- | --- | --- | --- |
| TB-NET-001 | P3 | 等待验证 | RTP/UDP 媒体传输：RTP 封装、jitter buffer、NACK、PLI/FIR、pacing、拥塞控制、SRTP/DTLS。 | 阶段 2/3 的 TCP 实测出现不可接受的重传拖尾或帧到达尖峰，且产品需要更低延迟；需同设备同网络的 RTT p50/p95、重传率、帧间隔 p95/p99、解码截止错过数据。当前未测量。 | [路线图阶段 5](optimization-roadmap.md)、[媒体架构传输](media-architecture.md) |
| TB-NET-002 | P3 | 等待验证 | WebRTC：浏览器互操作、跨网/NAT、完整音视频栈。 | 产品确认需要浏览器客户端、跨网或完整栈；或 TCP/RTP 方案无法满足目标场景。当前未决策。 | [路线图阶段 5](optimization-roadmap.md)、[产品语境](product-context.md) |
| TB-CODEC-001 | P3 | 等待验证 | HEVC 作为可选高档位启用。 | 服务端与客户端均探测支持，且同场景实测相比 H.264 有明确码率/画质收益；否则保持 H.264 兼容档、HEVC 仅作为可选项。当前未实测。 | [媒体架构编码格式](media-architecture.md) |
| TB-WIN-002 | P3 | 等待验证 | PiP 作为可选增强。 | API 26+ 用户需要系统级小窗或后台显示，且确认不替代跨应用悬浮窗；API 25 不可用。当前未验证。 | [窗口与会话设计窗口选型](windowing-session-design.md) |
| TB-PROD-001 | P3 | 等待验证 | 场景边界：手机、平板、大屏设备与车机，以及多显示器。 | 目标机型/系统版本上的悬浮窗权限、网络、休眠、音频路由、屏幕布局与合规实测通过；车机只是候选场景之一，不是能力基准；旗舰平板/手机可能具备更强编解码能力，不能按车机下限限制全产品。当前只是设计压力测试，不是既定场景。 | [产品语境场景判断](product-context.md) |

### 暂缓（当前不排期）

| ID | 优先级 | 状态 | 事项 | 暂缓理由 / 重新评估条件 | 关联文档 |
| --- | --- | --- | --- | --- | --- |
| TB-CLOUD-001 | P3 | 暂缓 | 云端中继与账号体系。 | 产品定位未验证，会改变安全与成本模型；只有确认跨网/多用户需求后再评估。 | [路线图不建议项](optimization-roadmap.md)、[产品语境](product-context.md) |
| TB-CTRL-002 | P3 | 暂缓 | 多客户端并发控制。 | 冲突、权限与审计复杂；先落地单客户端控制租约，再评估是否放开。 | [媒体架构反控](media-architecture.md) |
| TB-MEDIA-002 | P3 | 暂缓 | 每客户端独立转码或完整 simulcast 栈。 | 默认兼容单流 + 受限多档流（TB-CLIENT-002）已能覆盖高/低能力客户端并存；每客户端转码会放大编码器实例、功耗与实现复杂度，只有明确需求与实测收益才引入。 | [媒体架构多客户端](media-architecture.md) |
| TB-DISPLAY-001 | P3 | 暂缓 | 把 4K、120 fps 或 HDR 设为默认目标。 | 不设全产品默认目标；设备明确支持且同场景实测稳定时，可作为可选高档位，不因此限制其他设备。当前未实测。 | [路线图不建议项](optimization-roadmap.md) |
| TB-REWRITE-001 | P3 | 暂缓 | 全面改写 Kotlin/Compose。 | 当前代码量小、结构可改；重写会同时引入语言、框架与媒体风险。 | [路线图不建议项](optimization-roadmap.md) |
| TB-ROOT-001 | P3 | 暂缓 | root/shell/系统签名输入注入。 | 普通 App 不可用，安全与合规风险高；反控基线使用无障碍能力。 | [媒体架构反控能力](media-architecture.md) |

## 维护方式

- 状态变化时只更新对应任务的“状态”和“交付物 / 验证证据”，不手工维护任务总数或完成率。
- 开始实施前补充范围与验证方式；完成时补充可核对的提交、测试输出或实测记录。
- 未实测的收益、未执行的验证和产品推测不得写成已完成或已验证。
- 问题讨论的“关闭”与代码任务的“完成”分开记录；裁决关闭不改变对应实现任务的状态。
- 新增或删除任务时同步检查 [决策看板](decision-board.md) 是否需要新增裁决或复审条件。
- 任务状态或技术裁决变化时，同步更新本页、[决策看板](decision-board.md) 和 [文档索引](README.md)。
