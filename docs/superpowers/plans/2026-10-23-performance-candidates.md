# 性能候选实施计划：B1–B6（2026-10-23）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> 状态：规划稿。**未执行、未改代码、未跑构建、未做设备操作。** 六个棒次全部待实施。
> 基线：`scripts/checks/test_baseline.json` = **1732 tests / 177 classes**（HEAD 为性能候选登记提交）。每棒必须在**同一个提交**内把该文件更新为当次实测值——`check_test_count.py` 的文档字符串明写 baseline 必须同提交更新，仓库近 30 次测试提交中绝大多数如此。
> 路径简写：`$MAIN` = `app/src/main/java/com/obby/android/localscreenshare`，`$TEST` = `app/src/test/java/com/obby/android/localscreenshare`。所有测试替身一律写全 `$TEST/...` 路径；**禁止在 `$MAIN` 下新建与测试替身同名的类**。
> 上游文档：[设计文档](../specs/2026-10-23-performance-candidates-design.md)（意图、边界、协议、指标与判据的唯一来源）。

**Goal:** 在**不新增依赖、不抬高 minSdk、不改架构**的前提下落地五项已裁决能力——客户端解码低延迟参数、热余量预测降档、媒体通道追赶采样修复、描述符内容帧率提示、单客户端码率背压——每项都有纯 JVM 用例、可打印指标键、独立回退闸门，且**不触碰** `CatchUpPolicy` 阈值、`SurfaceView` 结构、多档流与 `DV-027` 的裁决状态。

**Architecture:** 五项按“本地观测 → 本地呈现 → 控制面反馈”分层。纯决策进 `core`（`CatchUpSampler`、`ThermalHeadroomPolicy`、`BackpressurePolicy`、`FrameRatePolicy`），Android 平台调用藏在 bridge/target 的 seam 之后（`FormatApplier`、`FrameRateApplier`、`ThermalMonitor`）。客户端上报与热余量轮询都改为**事件驱动或会话级**，不引入常驻高频定时器。服务端收到反馈后只把“最新值”交给专用单线程执行器，编码器调用离开 gRPC 回调线程。协议只加字段：`ClientMessage.feedback = 6`、`Descriptor.frame_rate = 17`，`PROTOCOL_VERSION` 保持 1。

**Tech Stack:** Java 11 source/target、Android minSdk 25、compileSdk/targetSdk 35、AGP 8.9.0、Gradle 8.11.1、JUnit 4.13.2（手写 fake，仓库无 Mockito）、protobuf-javalite + grpc-javalite。只使用现有依赖与平台 API：`MediaFormat`、`PowerManager.getThermalHeadroom`（API 30+）、`Surface.setFrameRate`（API 30 / 31）、`PARAMETER_KEY_VIDEO_BITRATE`。

**Spec:** [性能候选设计](../specs/2026-10-23-performance-candidates-design.md) · [任务看板](../../task-board.md#tb-eng-016) · [决策看板](../../decision-board.md) · [媒体链路目标架构](../../media-architecture.md) · [性能优化业界调研](../../performance-research.md) · [命名与包结构规范](../../naming-conventions.md) · [双媒体传输计划](2026-10-22-media-transports.md) · [传输可观测性计划](2026-10-21-transport-observability.md)

## 0. 与看板登记口径的对齐（开工前必须知道）

[TB-ENG-016](../../task-board.md#tb-eng-016) 是**批次总项**，于 2026-10-23 登记五项范围，同时预留五个分棒 ID。登记文本与本计划的裁决有三处必须在落地时对齐，否则实施者会照着旧措辞写错东西：

| 登记文本 | 本计划的裁决 | 处理 |
| --- | --- | --- |
| 第 1 项含 `KEY_OPERATING_RATE` | 本轮只做 `KEY_LOW_LATENCY` 与 `KEY_PRIORITY`；解码侧目标帧率需要新的描述符契约，与 B4 的提示语义重复 | B2 落地时把该条改写为“两键 + 未含项及原因”，并在 [性能优化业界调研](../../performance-research.md) §4.2 第 1 条明写“部分落地” |
| 第 4 项为“收紧追赶阈值 + 自适应队列” | **阈值不收紧**；B1 改为“追赶采样覆盖修复 + 丢片并轨” | B1 落地时按看板自己的“口径约束 ①”改写条目：写机制理由、同步 [DB-OBS-002](../../decision-board.md#db-obs-002) 与 [DV-021](../../decision-board.md) 的指针 |
| 五项各“占独立分棒 ID” | `TB-MEDIA-006/007`、`TB-POWER-002`、`TB-CLIENT-007`、`TB-ENG-015` 五个 ID 已预留；本批总项为 `TB-ENG-016` | 各棒落地时补自己的详情块；总项在五项全部写回后才转完成 |

用例与测试类只能按各棒清单逐条清点：19 + 13 + 18 + 21 + 42 = **113 条新用例**，即 1732 + 113 = **1845 tests**；新测试类 9 个（B1 一个、B2 一个、B3 一个、B4 一个、B5 五个），即 **186 classes**。任何与此不符的合计（例如把 B5 记成 4 个新类、或按旧的 36 条估算）都以本文件各棒清单为准。**这些数字只是清单下限，不得写进 `test_baseline.json`**；每个提交以 `check_test_count.py` 的实测输出为准。

## Global Constraints

以下每条都在每棒评审时逐项打勾；违反任一条即阻断。

- **G1 协议只加字段。** `ClientMessage.feedback = 6`、`Descriptor.frame_rate = 17`；`PROTOCOL_VERSION` 保持 1。旧服务端收到 `feedback` 时 `getPayloadCase()` 为 `PAYLOAD_NOT_SET`，既有 `switch` 的 `default` 无动作；旧服务端不填 `frame_rate` 时新客户端读到 0，等于不提示；新服务端填的值对旧客户端是 proto3 未知字段，被忽略。三档媒体面共用同一 mapper，**只改 mapper 一处**，禁止另开 UDP 描述符通道。
- **G2 编解码口径唯一。** 服务端对 H.264 与 JPEG 都填 `frame_rate`；客户端只按 `isSurfaceOutputRequired()` 决定是否消费。不得出现“JPEG 不填”的第二套说法。
- **G3 热路径零分配、零字符串拼接、不新增锁。** 允许新增 `volatile`；不允许新增锁对象。逐路径约束见设计文档 §6.7。
- **G4 事件驱动优先。** 客户端反馈上报、热余量采样都不得是常驻高频定时器：空闲时必须零定时器。
- **G5 线程边界。** 编码器更新只在 `lss-backpressure` 单线程执行；`onClientMessage` 的同步区内只做 gate 与字段拷贝，同步区外 `offer`；任何 `RuntimeException` 在协调器内吞掉，不冒泡到 gRPC 回调线程。
- **G6 指标常量。** 新建 `DecoderMetricNames`（1 值）与 `FeedbackMetricNames`（2 值）；`ServerMetricNames` 追加 5 个后恰好 20，Javadoc 明写“已达上限，下一条服务端键必须先按域拆类”；`ClientMetricNames` **不再追加**。
- **G7 文档与注释。** 仓库文档中文；Java 注释一律英文多行块（`/**` 独立成行、` * ` 前缀、` */` 独立成行）。注意 `check_cjk.py` 只扫 `$MAIN` 与 `app/src/main/res/values`，**不扫测试源码**——守卫不拦不代表可以写中文注释。
- **G8 测试与提交纪律。** 先写失败用例再实现；只用 JVM 短测试，不新增仪器测试或长时用例；每棒同一提交更新 `test_baseline.json`，`failures/errors/skipped` 必须全 0。`check_test_count.py` 对 `tests` **与** `classes` 都要求 `current >= baseline`，因此两者都只能不减；合并测试类等同于删测试，必须先确认零损失（断言只搬家、不减少）并在同一提交把基线更新为实测值。双变体还要求 `testDebugUnitTest` 与 `testReleaseUnitTest` 两个结果目录的 `tests`/`classes` 完全一致。每棒至少跑 `:app:lintDebug`；**B2、B3、B4、B5 必须同时跑 `:app:lintRelease`**（CI 两个变体都跑；本计划对四个涉及平台门控的棒统一取更严格的要求）。
- **G9 禁止夹带。** 任何一棒都不得修改 `CatchUpPolicy` 的阈值与判定顺序、不得替换 `SurfaceView`、不得做多档流/每客户端转码、不得改 `minSdk`、不得新增依赖、不得改 `DV-027` 的裁决状态。
- **G10 文档不写本机信息。** 不写绝对路径、电脑名、用户名、账号、设备标识、会话或 agent ID，也不写仓库外的输出日志路径。

## Review Focus

以下是最容易咬人的失败模式，每一处都必须在对应 Task 的用例或评审步骤中出现，不能靠注释或“将来会做”代替：

1. **三态指标自相矛盾。** `dec.lowLat` 的 0 与“未采样”同值且 `MetricsFormatter` 不打印 0；若仍按 0 写判据，设备端永远不通过。B2 必须先用 `MetricsFormatterTest` 钉住“0 不打印、1 打印、2 打印”。
2. **热余量轮询生命周期。** arm 只在 `onCreate`、disarm 在每会话 teardown ⇒ 第一个会话之后永久停摆；或者开关关闭只 `applyThermalTier(NONE)` ⇒ 下一秒 tick 又合并回高热档；或者设置打开就 arm 而不看会话 ⇒ 空闲期出现 1 Hz 采样。B3 用会话级 arm/disarm + 关闭立即 reset + “有会话才 arm”三个不变量钉死。
3. **丢片并轨的预算污染。** 若外部追赶计入连续失败预算，高丢包会把 HOLD 推到主路径（`DV-027` 未裁决）。B1 的用例必须断言外部追赶不增加连续计数、不触发收敛失败。
4. **一帧同时是结束点与新起点。** 单值枚举无法同时表达“结束 episode”和“本帧仍积压要开始下一次”。B1 返回两个字段，且顺序固定为先结束再采样。
5. **接口实现漏一个就编译失败。** 解码桥有 3 个实现，其中一个是测试文件内的私有类。B2 文件表必须列全。
6. **seam 暴露平台类型。** 本仓 JVM 单测无 Robolectric、无 `returnDefaultValues`，任何 `new MediaFormat()` 都会 not mocked；seam 只能用 boolean 与纯模型。
7. **gauge 写早了。** `configure` 成功后还有 `start`；`start` 失败会释放解码器，此时写 1 与事实相反。B2 只在整个 try 成功后写。
8. **`frame_rate` 与配置哈希。** 放进 hash 会为纯显示提示付出解码器重建；0 若被当成“忽略”，旧提示会永久残留并继续重放。
9. **平台方法运行期不存在。** `setFrameRate` 两参在 30、三参在 31；只靠 lint 挡不住 `NoSuchMethodError`，必须有纯 JVM 策略用例钉住分支并吞异常。
10. **重放幂等。** 宿主切换与 surface 重建会重复下发同一值；0 表示清除时必须停止重放旧值。
11. **背压的死角。** 未 attach 就解析/下发、多客户端仍动作、外部写入被当成自己写的、编码器拒绝冒泡、失败日志每帧一条、协调器在构造期捕获 `PipelineMetrics.NONE` 导致 `bp.*` 永不打印——B5 逐条有对应用例或装配约束。
12. **上报变常驻定时器。** 恒定 3 s 上报与功耗方向相反；B5 的排程必须在“无积压”时归零，且设备项 ⑨ 复核唤醒次数。

## 棒次总览

| 棒 | 主题 | 主要新增类 | 用例（按清单清点） | 新测试类 | 风险 |
| --- | --- | --- | --- | --- | --- |
| B1 | 追赶采样修复（单一入口 + 丢片并轨） | `CatchUpSampler` | 19 | 1 | 中：把 HOLD 路径推到默认传输 |
| B2 | 解码低延迟（三级回退 + 三态） | `LowLatencyState` | 13 | 1 | 中：厂商拒绝、三个实现 |
| B3 | 热余量预测降档（会话级 arm/disarm） | `ThermalHeadroomPolicy` | 18 | 1 | 中：生命周期与开关冲突 |
| B4 | 内容帧率（描述符 + `setFrameRate`） | `FrameRatePolicy` | 21 | 1 | 中：API 30/31 分支、0 语义 |
| B5 | 单客户端码率背压 | `StreamFeedback`、`BacklogFeedback`、`BackpressurePolicy`、`BitrateTarget`、`RuntimeBitrateTarget`、`BackpressureCoordinator` | 42 | 5 | 高：控制面 + 线程边界 + 功耗口径 |
| B6 | 文档/看板收口与基线定格 | 无 | 0 | 0 | 低 |

> 顺序 B1 → B2 → B3 → B4 → B5 → B6。B1 先做，因为它给 B5 提供“追赶事件”的可靠计数；B5 最后做，因为它跨控制面与执行器，失败面最大。

---

## B1 追赶采样覆盖修复（单一入口 + 丢片并轨）

**目标**：gRPC 路径、媒体通道路径与丢片追赶共用同一个采样入口；丢片 episode 计入 `rx.catchup` 与 `rx.catchup.lat`，但**不占**连续失败预算；`CatchUpPolicy` 阈值一字不改。

**Files:**

- Create: `$MAIN/core/client/CatchUpSampler.java`
- Modify: `$MAIN/core/client/CatchUpPolicy.java` — 新增 `Reason.EXTERNAL` 与 `onExternalCatchUp(long nowNs)`；不改任何阈值、不改 `onSample`/`onJoinPoint` 既有判定顺序
- Modify: `$MAIN/client/ScreenShareClient.java` — `onDecodeTask`、`newMediaChannelListener().onAccessUnit`、`onCatchUp`、`startCatchUp`、`finishCatchUp`、`reportConvergenceFailure`、字段 `mCatchUpPolicy` → `mCatchUpSampler`、reason 映射补外部追赶
- Modify: `$MAIN/core/metrics/PipelineMetrics.java` — `CATCH_UP_REASON_EXTERNAL = 3L`
- Modify: `$MAIN/core/metrics/CatchUpMetricNames.java` — `REASON_EXTERNAL = "ext"`
- Modify: `$MAIN/core/metrics/MetricsFormatter.java` — reason=3 打印 `ext`
- Create: `$TEST/core/client/CatchUpSamplerTest.java`
- Modify: `$TEST/core/client/CatchUpPolicyTest.java`
- Modify: `$TEST/core/metrics/MetricsFormatterTest.java`
- Modify: `scripts/checks/test_baseline.json`
- Modify: `docs/media-architecture.md` §5.3.1、`docs/task-board.md`、`docs/decision-board.md`

**Interfaces:**

```java
package com.obby.android.localscreenshare.core.client;

public final class CatchUpSampler {

    /**
     * One decoded unit: the episode may end and a new sample may still start one.
     */
    public static final class Result {
        public final boolean finishedEpisode;
        public final CatchUpPolicy.Decision decision;
    }

    public CatchUpSampler();

    /**
     * Samples one decoded unit. An accepted join point ends the current episode first, then the
     * backlog is sampled, so the frame that ends a catch-up can still start the next one.
     */
    public Result onDecoded(long nowNs, int queueDepth, long queueWaitNs, boolean streaming);

    /**
     * Starts an episode caused by something other than backlog (a datagram channel lost a whole
     * unit). An open episode is merged rather than counted twice. External episodes never consume
     * the consecutive budget and never start the convergence hold.
     *
     * @return true when this call started a new episode
     */
    public boolean onExternalCatchUp(long nowNs);

    public boolean isCatchingUp();

    /**
     * Start of the current episode, or Long.MIN_VALUE when no episode is open.
     */
    public long getEpisodeStartedNs();

    public CatchUpPolicy.Reason getLastReason();
    public int getConsecutiveCatchUps();

    /**
     * Units sampled; diagnostics only, never used for a decision.
     */
    public long getSampleCount();
}
```

`CatchUpPolicy` 新增：

```java
/**
 * External episode reason (a lost whole access unit), distinct from backlog reasons.
 */
EXTERNAL;

/**
 * Opens an episode that was not caused by backlog. No-op merge when an episode is already open:
 * times are not refreshed, so one episode cannot be prolonged indefinitely by repeated loss.
 * Never increments the consecutive count, never sets the cooldown, never starts convergence.
 */
public void onExternalCatchUp(long nowNs);
```

**Steps:**

- [ ] **Step 1：先写失败用例（19 条）**

`$TEST/core/client/CatchUpSamplerTest.java`（14 条）：

| 用例 | 断言 |
| --- | --- |
| `onDecoded_singleSlowFrameDoesNotStart` | 1 帧轻微抖动 → `finishedEpisode=false`、`decision=NONE` |
| `onDecoded_sustainedQueueWaitStarts` | 持续 2 s、等待 1 s → `START_CATCH_UP` 且 reason 为队列等待 |
| `onDecoded_sustainedQueueDepthStarts` | 深度 64 持续 2 s → `START_CATCH_UP` 且 reason 为队列深度 |
| `onDecoded_finishesThenSamplesInThatOrder` | 追赶中且本帧仍积压 → `finishedEpisode=true` **且** `decision=START_CATCH_UP` |
| `onDecoded_whileCatchingUpAndNotStreamingStaysOpen` | 非 STREAMING 连续喂入 → 全 `NONE`、`isCatchingUp()` 为真 |
| `onDecoded_streamingTransitionFinishesEpisode` | 转 STREAMING 且队列清爽 → `finishedEpisode=true`、`decision=NONE` |
| `onDecoded_thirdCatchUpWithoutConvergenceFails` | 3 次负载追赶 → `CONVERGENCE_FAILED`、连续计数为 3 |
| `onDecoded_withinCooldownDoesNotStart` | 触发后 5 s 内再积压 → `NONE` |
| `onExternalCatchUp_startsEpisodeAndIsVisible` | 空闲时调用 → 返回真、`isCatchingUp()` 真、起点等于传入时间、reason 为 EXTERNAL |
| `onExternalCatchUp_mergesIntoOpenEpisode` | 已追赶时再调 → 返回假、起点不变 |
| `onExternalCatchUp_doesNotConsumeConsecutiveBudget` | 连续 5 次外部追赶 + 结束后再积压 → 仍能 `START_CATCH_UP`，连续计数未因外部追赶增长 |
| `onExternalCatchUp_episodeEndsOnJoinPoint` | 外部追赶后 STREAMING → `finishedEpisode=true`、`isCatchingUp()` 假 |
| `episodeStartedNs_isSentinelWhenIdle` | 空闲时返回哨兵值 |
| `sampleCount_countsEveryOnDecoded` | N 次采样 → 计数为 N |

`$TEST/core/client/CatchUpPolicyTest.java`（+4）：`onExternalCatchUp_startsEpisode`、`onExternalCatchUp_mergesWithoutDoubleCount`、`onExternalCatchUp_doesNotIncrementConsecutive`、`onExternalCatchUp_doesNotTriggerConvergenceHold`。

`$TEST/core/metrics/MetricsFormatterTest.java`（+1）：`catchUpReason_externalIsPrintedAsExt` — reason 为 3 时输出 `rx.catchup.why=ext`。

- [ ] **Step 2：跑红测并记录失败形态**

```bash
./gradlew :app:testDebugUnitTest --tests '*CatchUpSamplerTest*' --tests '*CatchUpPolicyTest*' --tests '*MetricsFormatterTest*'
```

Expected: `CatchUpSamplerTest` 编译失败（类不存在），`CatchUpPolicyTest` 编译失败（方法不存在），`MetricsFormatterTest` 断言失败（3 被打印成 `qWait`）。把输出摘要贴进提交说明前的自检记录，不写进仓库文件。

- [ ] **Step 3：实现**

1. `CatchUpSampler.onDecoded`：先 `if (isCatchingUp() && streaming) { policy.onJoinPoint(nowNs); finished = true; }`，再 `policy.onSample(...)`，两者一起返回。**不要**在结束时跳过采样。
2. `CatchUpSampler.onExternalCatchUp`：若已 `isCatchingUp()` → 返回假且**不刷新任何时间**（合并语义）；否则调 `policy.onExternalCatchUp(nowNs)`、记录 episode 起点、返回真。
3. `getEpisodeStartedNs()` 只在 `isCatchingUp()` 时为真值，否则返回 `Long.MIN_VALUE`；`finishCatchUp` 必须先判 `isCatchingUp()` 再相减。
4. `ScreenShareClient`：两条接收路径调用同一个 `mCatchUpSampler.onDecoded(...)`，`Result` 处理抽成私有方法 `handleCatchUpResult(...)`，两个调用点共用同一段代码；`startCatchUp`/`finishCatchUp`/`reportConvergenceFailure` 改为经 `handleCatchUpResult` 驱动。
5. 丢片路径（`onCatchUp`）：先 `if (mCatchUpSampler.onExternalCatchUp(nowNs)) { mMetrics.increment(CATCH_UP_EPISODES); mMetrics.setGauge(CATCH_UP_REASON, CATCH_UP_REASON_EXTERNAL); mCatchUpStartedNs = nowNs; }`，再 `mCatchUpGeneration.incrementAndGet()` 与 `receiver.getSession().onCatchUp()`。丢片路径**不发** `kf.request`（既有丢包跟踪器负责）。
6. `startCatchUp` 的 reason 映射：队列深度 → 2、外部 → 3、其余 → 1。
7. `MetricsFormatter.appendCatchUpReason` 增加 3 → `ext` 分支；未知值仍落到默认分支。

实现边界：不改 `CatchUpPolicy` 的任何常量与 `onSample` 判定顺序；不改 HOLD 时长；不改 `isSuperseded`。

- [ ] **Step 4：跑绿测（定向）**

```bash
./gradlew :app:testDebugUnitTest --tests '*CatchUpSamplerTest*' --tests '*CatchUpPolicyTest*' --tests '*MetricsFormatterTest*' --tests '*ClientMediaSession*Test*' --tests '*ClientMediaReceiver*Test*'
```

Expected: `BUILD SUCCESSFUL`，上述类全绿。说明：`ScreenShareClient` **没有独立的 JVM 测试类**，它的接线由编译器与上述会话/接收类用例覆盖；不要为它临时新建只做冒烟的空测试类。

- [ ] **Step 5：全量 + 基线与卫生**

```bash
./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest
python scripts/checks/check_test_count.py --results app/build/test-results/testDebugUnitTest app/build/test-results/testReleaseUnitTest
python scripts/checks/check_cjk.py
python scripts/checks/check_docs.py
python scripts/checks/check_strings.py
```

Expected: 双变体全绿、0 失败；`check_test_count.py` 显示 `tests` 增加且未低于基线。把实测输出写进 `test_baseline.json`。

- [ ] **Step 6：文档**

- `docs/media-architecture.md` §5.3.1：两条接收路径 + 丢片路径共用采样入口；外部追赶计入 `rx.catchup` 与延迟，但**不消费**连续预算（理由：丢片是网络类故障，不该被“解码赤字”的 HOLD 惩罚）；写明 episode 合并不刷新时间。
- `docs/task-board.md`：新增 `TB-CLIENT-007` 行与详情块。**必须改写**登记文本里的“收紧追赶阈值 + 自适应队列”：本轮范围是采样覆盖修复，阈值不收紧；写机制理由并指向 [DV-027](../../decision-board.md)（B1 之前 TCP/UDP 档几乎不进入收敛失败，B1 之后默认档会走到该路径；HOLD 滞后无界仍按未裁决处理；设备复验记录 `rx.converge.fail` 后的 `freeze` 与 `pres.drop`）。
- `docs/decision-board.md`：`DB-OBS-002` 补注“`rx.catchup*` 的口径自本批起覆盖三条路径，跨构建日志不可横比”；`DV-021` 补注指针。

- [ ] **Step 7：提交**

```bash
git add app/src/main/java/com/obby/android/localscreenshare/core/client/CatchUpSampler.java \
        app/src/main/java/com/obby/android/localscreenshare/core/client/CatchUpPolicy.java \
        app/src/main/java/com/obby/android/localscreenshare/client/ScreenShareClient.java \
        app/src/main/java/com/obby/android/localscreenshare/core/metrics \
        app/src/test/java/com/obby/android/localscreenshare/core/client \
        app/src/test/java/com/obby/android/localscreenshare/core/metrics \
        scripts/checks/test_baseline.json docs/media-architecture.md docs/task-board.md docs/decision-board.md
git commit -m "feat(client): 统一追赶采样入口并接入丢片追赶"
```

**回退**：整提交还原；回退后必须同步把文档口径改回“`rx.catchup` 只覆盖 gRPC 路径”，不得只回代码。

---

## B2 解码低延迟（三级回退 + 三态指标）

**目标**：解码器在 API 30+ 请求低延迟与实时优先级，厂商拒绝时逐级退化；新增三态指标区分“生效 / 部分生效 / 回退 / 未请求”。

**Files:**

- Create: `$MAIN/core/codec/mediacodec/LowLatencyState.java`
- Modify: `$MAIN/core/codec/mediacodec/MediaCodecDecoderBridge.java` — 新增 `LowLatencyState getLowLatencyState()`
- Modify: `$MAIN/core/codec/mediacodec/AndroidMediaCodecDecoderBridge.java` — `configure` 走 `configureWithFallback`；新增键常量、`FormatApplier`、三级回退
- Modify: `$MAIN/core/codec/h264/H264Decoder.java` — `configure` 与 `start` 整体成功后写三态 gauge；`close` 时把本解码器写过的值清零（B2 复核补充）
- Modify: `$MAIN/core/metrics/PipelineMetrics.java` — `Gauge.DECODER_LOW_LATENCY`
- Create: `$MAIN/core/metrics/DecoderMetricNames.java`
- Modify: `$MAIN/core/metrics/MetricsFormatter.java` — 客户端行打印 `dec.lowLat`（1/2 才打印）
- Create: `$TEST/core/codec/mediacodec/AndroidMediaCodecDecoderBridgeTest.java`
- Modify: `$TEST/core/support/FakeMediaCodecDecoderBridge.java` — 实现新方法 + `setLowLatencyState(...)`
- Modify: `$TEST/core/support/FakeMediaCodecBridgeFactory.java` — 新增 `setNextDecoderLowLatencyState(LowLatencyState)`，在 `createDecoder` 内套用到新建的桥；用于“桥在 `H264Decoder.configure` 之前就必须带上状态”的 gauge 用例
- Modify: `$TEST/core/codec/h264/H264DecoderTest.java` — 私有 `BudgetDecoderBridge`（约 `:701`）**必须**实现新方法；追加 5 条用例
- Modify: `$TEST/core/metrics/MetricsFormatterTest.java`
- Modify: `scripts/checks/test_baseline.json`
- Modify: `docs/performance-research.md`、`docs/task-board.md`

**Interfaces:**

```java
public enum LowLatencyState {
    /**
     * The platform is too old, so the optional keys were never sent.
     */
    NOT_REQUESTED,
    /**
     * Both optional keys were accepted.
     */
    APPLIED,
    /**
     * The low-latency key survived, the priority key was dropped.
     */
    APPLIED_WITHOUT_PRIORITY,
    /**
     * Every attempt that carried the low-latency key was rejected.
     */
    REJECTED
}

// MediaCodecDecoderBridge
LowLatencyState getLowLatencyState();

// AndroidMediaCodecDecoderBridge
private static final String LOW_LATENCY_KEY = "low-latency";
private static final int PRIORITY_REALTIME = 0;

/**
 * Applies one configuration. Flags only: never a MediaFormat, so JVM tests can drive it.
 */
interface FormatApplier {
    void configure(boolean lowLatency, boolean priority);
}

/**
 * Tries the requested optional keys in three steps and reports which ones survived. A rejected
 * attempt resets the codec before the next one; a failure unrelated to the optional keys is
 * rethrown unchanged.
 */
static LowLatencyState configureWithFallback(boolean requestLowLatency, FormatApplier applier,
                                             Runnable resetAction);
```

**Write-down rules:**

`FormatApplier` 与 `configureWithFallback` 都放在 `AndroidMediaCodecDecoderBridge` 内，包私有，便于 JVM 测试直接构造；不新增顶层类型文件。

1. 请求开关由 `configure` 计算为 `Build.VERSION.SDK_INT >= Build.VERSION_CODES.R`；键名用字符串字面量（与既有编码侧 `max-fps-to-encoder` 同策略，避免 minSdk 25 下的编译期与 lint 差异）。
2. 回退顺序固定：`false` → 调一次 `(false,false)` 返回 `NOT_REQUESTED`（不 reset）；`(true,true)` 失败 → `reset()` → `(true,false)`；仍失败 → `reset()` → `(false,false)` 且状态 `REJECTED`；最后一步也失败则原样抛出。
3. 捕获类型与编码侧一致：`IllegalArgumentException` 与 `IllegalStateException`；其余异常原样冒泡，不改类型。
4. gauge 映射：`NOT_REQUESTED` 不写（保持 0，不打印）；`APPLIED` 与 `APPLIED_WITHOUT_PRIORITY` 写 1；`REJECTED` 写 2。
5. 写入点：`H264Decoder.configure` 中 `configure` 与 `start` **都成功之后**（与 `configuredAtNs` 同处）；任一步失败都不写。`close` 时把本解码器写过的值清 0（同一指标作用域跨解码器复用，H.264 → JPEG 后不得继续打印上一解码器的值）；`NOT_REQUESTED` 从未写过，`close` 不清。

**Steps:**

- [ ] **Step 1：先写失败用例（13 条）**

`AndroidMediaCodecDecoderBridgeTest`（6 条）：

| 用例 | 断言 |
| --- | --- |
| `configure_appliesBothKeysWhenAccepted` | applier 收到 1 次 `(true,true)`、reset 0 次、返回 `APPLIED` |
| `configure_retriesWithoutPriorityWhenOnlyPriorityRejected` | 首次 `(true,true)` 抛 `IllegalArgumentException` → 第二次 `(true,false)` 成功、reset 1 次、返回 `APPLIED_WITHOUT_PRIORITY` |
| `configure_fallsBackWithoutOptionalKeysWhenLowLatencyRejected` | 前两次抛异常 → 第三次 `(false,false)` 成功、reset 2 次、返回 `REJECTED` |
| `configure_rethrowsWhenTheFinalAttemptAlsoFails` | 三次全抛 → 异常冒泡、applier 3 次、reset 2 次 |
| `configure_unrelatedFailureKeepsOriginalType` | 不请求可选键且抛 `IllegalStateException` → 原样抛出、applier 1 次、reset 0 次 |
| `configure_notRequestedNeverSendsOptionalKeys` | 不请求 → 入参恒 `(false,false)`、返回 `NOT_REQUESTED` |

`H264DecoderTest`（+5，经 `FakeMediaCodecBridgeFactory.setNextDecoderLowLatencyState(...)` 预设桥状态）：

| 用例 | 断言 |
| --- | --- |
| `configure_recordsLowLatencyApplied` | 桥报 `APPLIED` → gauge 为 1 |
| `configure_recordsPartialLowLatencyAsApplied` | 桥报 `APPLIED_WITHOUT_PRIORITY` → gauge 为 1 |
| `configure_recordsLowLatencyFallback` | 桥报 `REJECTED` → gauge 为 2 |
| `configure_startFailureLeavesGaugeUnwritten` | `start()` 抛 → 抛解码失败、gauge 仍 0 |
| `configure_notRequestedLeavesGaugeUnwritten` | 桥报 `NOT_REQUESTED` → gauge 0 |

`MetricsFormatterTest`（+2）：`decLowLat_printsWhenOneOrTwo`、`decLowLat_isOmittedForZero`。

- [ ] **Step 2：跑红测**

```bash
./gradlew :app:testDebugUnitTest --tests '*AndroidMediaCodecDecoderBridgeTest*' --tests '*H264DecoderTest*' --tests '*MetricsFormatterTest*'
```

Expected: 编译失败（枚举、方法、常量、两个桥实现缺失），`MetricsFormatterTest` 断言失败。**注意**：漏改 `H264DecoderTest.BudgetDecoderBridge` 会在这里表现为编译失败，这是预期信号，不要用反射绕过。

- [ ] **Step 3：实现**

按 Interfaces 与 Write-down rules 实现；`FakeMediaCodecDecoderBridge` 默认返回 `NOT_REQUESTED`，并提供 `setLowLatencyState(...)`。`FakeMediaCodecBridgeFactory` 增加 `setNextDecoderLowLatencyState(...)`（一次性，套用到下一次 `createDecoder` 新建的桥并随后清空），因为 `H264DecoderTest.newMeasuredDecoder` 复用的是共享工厂，桥是在 `H264Decoder.configure` 内部才被创建的——没有这个入口，三条 `configure_records*` 用例无法在 configure 之前把状态塞进去。若不想改工厂，替代方案是在 `H264DecoderTest` 内按 `BudgetBridgeFactory` 的既有范式新增一个带 metrics 的私有工厂，但**不要**同时保留两种写法。`BudgetDecoderBridge` 同样返回 `NOT_REQUESTED`（它只测预算，不关心低延迟）。`H264Decoder` 读桥的返回值并映射写 gauge；`MetricsFormatter` 在客户端行追加一个非 0 才打印的 gauge 字段，键取自新常量类。

- [ ] **Step 4：跑绿测（定向）**

```bash
./gradlew :app:testDebugUnitTest --tests '*AndroidMediaCodecDecoderBridgeTest*' --tests '*H264DecoderTest*' --tests '*MetricsFormatterTest*' --tests '*MediaCodecBridgeContractTest*'
```

- [ ] **Step 5：全量 + 双变体 lint + 基线**

```bash
./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest
./gradlew :app:lintDebug :app:lintRelease
python scripts/checks/check_test_count.py --results app/build/test-results/testDebugUnitTest app/build/test-results/testReleaseUnitTest
python scripts/checks/check_cjk.py
```

- [ ] **Step 6：文档**

- `docs/performance-research.md` §4.2 第 1 条标“**部分落地**（2026-10-23）”，明写**未含 `KEY_OPERATING_RATE`** 及原因（解码侧目标帧率需要新的描述符契约，避免与 B4 的提示重复定义）；§6 补 `dec.lowLat` 三态口径。
- `docs/task-board.md`：新增 `TB-MEDIA-006` 行与详情块；把 [TB-ENG-016](../../task-board.md#tb-eng-016) 第 1 项的措辞改为“两键 + 未含项”。

- [ ] **Step 7：提交**

```bash
git add app/src/main/java/com/obby/android/localscreenshare/core/codec/mediacodec \
        app/src/main/java/com/obby/android/localscreenshare/core/codec/h264/H264Decoder.java \
        app/src/main/java/com/obby/android/localscreenshare/core/metrics \
        app/src/test/java/com/obby/android/localscreenshare/core/codec \
        app/src/test/java/com/obby/android/localscreenshare/core/support/FakeMediaCodecDecoderBridge.java \
        app/src/test/java/com/obby/android/localscreenshare/core/metrics \
        scripts/checks/test_baseline.json docs/performance-research.md docs/task-board.md
git commit -m "feat(codec): 解码器请求低延迟参数并按三态上报"
```

**回退**：把“是否请求”恒置否（一行），保留枚举与 gauge——此时状态恒 `NOT_REQUESTED`、不打印，指标自然回到“无此能力”。

---

## B3 热余量预测降档（会话级 arm / disarm）

**目标**：用 `getThermalHeadroom(10)` 预测温升，提前进入 REDUCED / MINIMAL；预测与现状只允许“取严格档”；采样随会话与开关正确上下线。

平台返回值是**热压力比值**（`1.0` = 平台开始限流，`>1.0` = 已限流，越大越热），指标按千分比打印且**不钳制**上界；阈值仍按 0.70 / 0.85 的千分比 700 / 850。开关的打开/关闭与 arm 的分离见下方 Lifecycle rules：**没有会话时只允许一次立即采样，不得 arm 1 Hz**。

**Files:**

- Modify: `$MAIN/core/stream/ThermalThrottlePolicy.java` — 新增 `static Tier stricter(Tier, Tier)`、`static Tier tierAtSessionEnd(boolean, int)`
- Create: `$MAIN/core/stream/ThermalHeadroomPolicy.java` — 含 `static boolean isValid(float)`
- Modify: `$MAIN/service/ThermalMonitor.java` — 新增方法级 `@RequiresApi(R) float getThermalHeadroom(int forecastSeconds)`
- Modify: `$MAIN/service/ScreenShareService.java` — 字段、`armThermalHeadroomPolling()`、`disarmThermalHeadroomPolling()`、`sampleThermalHeadroomOnce()`；改 `startThermalMonitor`、`stopThermalMonitor`、`cancelScheduledWork`、`onThermalStatusChanged`、`onThermalThrottleSettingChanged`，并在 `startStreamPipeline()` 成功路径 arm
- Modify: `$MAIN/core/metrics/PipelineMetrics.java` — `Gauge.THERMAL_HEADROOM_PERMILLE`、`Gauge.THERMAL_TIER`
- Modify: `$MAIN/core/metrics/ServerMetricNames.java` — `THERMAL_HEADROOM`、`THERMAL_TIER`（达到 20 上限，Javadoc 注明）
- Modify: `$MAIN/core/metrics/MetricsFormatter.java` — 服务端行打印两个 gauge（0 不打印）
- Create: `$TEST/core/stream/ThermalHeadroomPolicyTest.java`
- Modify: `$TEST/core/stream/ThermalThrottlePolicyTest.java`
- Modify: `$TEST/core/metrics/MetricsFormatterTest.java`
- Modify: `scripts/checks/test_baseline.json`
- Modify: `docs/decision-board.md`、`docs/task-board.md`、`docs/performance-research.md`、`docs/media-architecture.md`（§4.4.1 已在 B3 范围）

**Interfaces:**

```java
// ThermalThrottlePolicy
/**
 * Two signals may only tighten the ladder, never relax it.
 */
public static Tier stricter(Tier a, Tier b);

/**
 * The tier a session boundary leaves in force: the switch and the current status only, never the
 * forecast (the caller resets it first).
 */
public static Tier tierAtSessionEnd(boolean throttleEnabled, int thermalStatus);

// ThermalHeadroomPolicy (pure, JVM testable)
public final class ThermalHeadroomPolicy {
    public static final long POLL_INTERVAL_MS = 1000L;       // at most one sample per second
    public static final int FORECAST_SECONDS = 10;
    public static final float MAX_VALID_HEADROOM = 2.0f;      // above this the ratio is garbage
    public static final int ENTER_REDUCED_PERMILLE = 700;     // pending device calibration
    public static final int ENTER_MINIMAL_PERMILLE = 850;     // pending device calibration
    public static final int EXIT_MINIMAL_PERMILLE = 780;      // pending device calibration
    public static final int EXIT_REDUCED_PERMILLE = 600;      // pending device calibration

    /**
     * Whether a reading is inside [0, MAX_VALID_HEADROOM] and not NaN; the service reuses this
     * instead of restating the domain.
     */
    public static boolean isValid(float headroom);

    /**
     * Samples one ratio, where 1.0 means the platform starts throttling and values above it mean
     * the device is already throttled.
     *
     * <p>The permille value is {@code Math.round(headroom * 1000f)}. A cast must not be used:
     * 0.70f is 0.69999998..., so a cast yields 699 and the threshold test would fail on its own.
     * NaN and ratios outside [0, MAX_VALID_HEADROOM] are ignored and leave the tier unchanged.</p>
     */
    public Tier onSample(float headroom);

    public Tier getTier();

    public void reset();
}

// ThermalMonitor (class is @RequiresApi(Q))
@RequiresApi(Build.VERSION_CODES.R)
float getThermalHeadroom(int forecastSeconds);
```

**Lifecycle rules（写死）:**

| 入口 | 动作 | 触发点 |
| --- | --- | --- |
| arm | 先 `removeCallbacks` 再 `postDelayed`（幂等）；入口条件 `SDK_INT >= 30 && isThermalThrottleEnabled() && mThermalMonitor != null && mStreamRuntime != null`（**会话运行中**），不满足直接返回 | `startStreamPipeline()` 成功之后，**每次会话**都执行，不是只在 `onCreate` |
| disarm | `removeCallbacks` + `policy.reset()` + 档位回落为 `tierAtSessionEnd(开关, 当前状态)`（即只由状态取档，无状态档则 `NONE`；开关关闭仍为 `NONE`） | `cancelScheduledWork()`（每次 teardown）与 `stopThermalMonitor()`（`onDestroy`） |
| tick body | 先重读开关；false → `applyThermalTier(NONE, "disabled")` + disarm 且**不 re-arm**；true → 采样一次再 `postDelayed` | 轮询自身 |
| 设置关闭 | 立即 `applyThermalTier(NONE, "disabled")` + disarm，不等 tick | `onThermalThrottleSettingChanged` |
| 设置打开 | 立即采样一次；`mStreamRuntime != null` 时再 arm（重排），**否则只采样这一次、不 arm** | 同上 |
| 采样 | 显式判断 `SDK_INT >= 30` 后调平台；无效样本（NaN 或越界）不改档——保留既有预测档、`thermalHeadroom` gauge 写 0（不打印）且不写档位 gauge，再与状态档取严格档；有效 → `Math.round(headroom * 1000f)` 写千分比、采样策略、与状态档取严格档、写档位 gauge（NONE=1 / REDUCED=2 / MINIMAL=3）、应用 | `sampleThermalHeadroomOnce` |

不变式：`applyThermalTier` 只改生效值、绝不写偏好；先换编码器再改显示；开关关闭后不再有任何 1 Hz 采样；**没有会话时不存在 1 Hz 定时器**（设置打开最多产生一次立即采样）。

**Steps:**

- [ ] **Step 1：先写失败用例（18 条）**

18 条全部是纯 JVM 用例。arm 的“会话运行中”条件属于服务生命周期，JVM 侧无法覆盖，改由 Step 3 的实现约束 + 设备项 ② 核对；不要为它新增冒烟测试类，也不要因此改动 18 这个数字。

`ThermalHeadroomPolicyTest`（12 条）：

| 用例 | 断言 |
| --- | --- |
| `onSample_nanKeepsCurrentTier` | 0.72 → REDUCED；NaN → 仍 REDUCED |
| `onSample_entersReducedAtThreshold` | 0.69 → NONE；0.70 → REDUCED（`0.70f` 经 `Math.round` 得 700；用 `(int)` 截断会得 699，用例会自己假失败） |
| `onSample_entersMinimalAtThreshold` | 0.84 → REDUCED；0.85 → MINIMAL（`0.85f` 同理，必须四舍五入到 850） |
| `onSample_keepsReducedAboveExit` | REDUCED 下 0.65 → REDUCED |
| `onSample_exitsReducedBelowExit` | REDUCED 下 0.59 → NONE |
| `onSample_keepsMinimalAboveExit` | MINIMAL 下 0.79 → MINIMAL |
| `onSample_downgradesMinimalBelowExit` | MINIMAL 下 0.77 → REDUCED |
| `onSample_ignoresOutOfRangeValues` | -0.1 与 2.5 不改变档位；`1.5` 是有效样本、应得 MINIMAL，只能 reset 后作正向对照，不能用来证明忽略 |
| `onSample_bandFlappingDoesNotOscillate` | 0.60–0.70 交替 10 次 → 档位变化不超过 1 次 |
| `reset_returnsToNone` | 任意档位后 reset → NONE |
| `pollIntervalRespectsOneHertz` | `POLL_INTERVAL_MS >= 1000L` |
| `forecastSecondsMatchesTheDocumentedTen` | `FORECAST_SECONDS == 10` |

`ThermalThrottlePolicyTest`（+4）：`stricter` 的 3×3 组合逐项断言（例：`stricter(MINIMAL, REDUCED) == MINIMAL`），并断言结果 ordinal 恒不小于两个入参。

`MetricsFormatterTest`（+2）：`thermalHeadroomAndTier_printWhenSampled`、`thermalGauges_areOmittedWhenZero`。

- [ ] **Step 2：跑红测**

```bash
./gradlew :app:testDebugUnitTest --tests '*ThermalHeadroomPolicyTest*' --tests '*ThermalThrottlePolicyTest*' --tests '*MetricsFormatterTest*'
```

Expected: 编译失败（策略类、`stricter`、两个 gauge 常量与键缺失）。

- [ ] **Step 3：实现**

先实现纯策略与 `stricter`，再改服务生命周期，最后接指标。`ThermalMonitor.getThermalHeadroom` 用方法级 `@RequiresApi(R)`，调用点显式版本判断——两个变体的 lint 才能一致。`ServerMetricNames` 追加后检查常量数量恰为 20。

实现时按三条写死的口径落地：① 千分比一律 `Math.round(headroom * 1000f)`，**不写 `(int)` 强制转换**；`onSample` 只接受 `[0, 2]`，NaN 与区间外样本原样忽略。② `armThermalHeadroomPolling()` 的入口条件补 `mStreamRuntime != null`；`onThermalThrottleSettingChanged()` 打开分支先 `sampleThermalHeadroomOnce()`，再**仅在有会话时** arm。③ 服务生命周期没有 JVM 用例（该服务依赖 Android 框架），这两条由代码评审与设备项 ② 核对，不要为它造只做冒烟的空测试类，也不要改动用例数。

- [ ] **Step 4：跑绿测（定向）**

```bash
./gradlew :app:testDebugUnitTest --tests '*ThermalHeadroomPolicyTest*' --tests '*ThermalThrottlePolicyTest*' --tests '*MetricsFormatterTest*'
```

- [ ] **Step 5：全量 + 双变体 lint + 基线**

```bash
./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest
./gradlew :app:lintDebug :app:lintRelease
python scripts/checks/check_test_count.py --results app/build/test-results/testDebugUnitTest app/build/test-results/testReleaseUnitTest
python scripts/checks/check_cjk.py
```

- [ ] **Step 6：文档**

- `docs/decision-board.md`：新增 `DB-POWER-003`（只收紧、1 Hz 上限、阈值待校准、开关关闭立即恢复、会话级 arm/disarm）。
- `docs/task-board.md`：新增 `TB-POWER-002` 行与详情块。
- `docs/performance-research.md` §6 触发条件 3：标“代码已落地、0.70/0.85 待设备校准”。

- [ ] **Step 7：提交**

```bash
git add app/src/main/java/com/obby/android/localscreenshare/core/stream \
        app/src/main/java/com/obby/android/localscreenshare/service/ThermalMonitor.java \
        app/src/main/java/com/obby/android/localscreenshare/service/ScreenShareService.java \
        app/src/main/java/com/obby/android/localscreenshare/core/metrics \
        app/src/test/java/com/obby/android/localscreenshare/core/stream \
        app/src/test/java/com/obby/android/localscreenshare/core/metrics \
        scripts/checks/test_baseline.json docs/decision-board.md docs/task-board.md docs/performance-research.md
git commit -m "feat(power): 热余量预测降档并随会话上下线"
```

**回退**：`onThermalStatusChanged` 的合并改回“只按状态取档”、`onThermalThrottleSettingChanged` 恢复旧行为、删除 arm/disarm 调用点；`ThermalHeadroomPolicy` 与两个 gauge 可保留（没有 arm 就不会有采样）。

---

## B4 内容帧率提示（描述符 `frame_rate` + `Surface.setFrameRate`）

**目标**：服务端在描述符里下发内容帧率，观看端在 surface 路径把它作为提示交给平台；`0` 表示清除且不重放；不改 `configHash`。

**Files:**

- Modify: `app/src/main/proto/screen_stream.proto` — `Descriptor.frame_rate = 17`
- Modify: `$MAIN/core/model/StreamDescriptor.java` — 字段、`getFrameRate()`、`Builder.frameRate(int)`、`withConfigEpoch` 复制、`computeConfigHash` **不写**
- Modify: `$MAIN/transport/ScreenStreamProtoMapper.java` — 双向映射（0 可不写）
- Modify: `$MAIN/service/ScreenShareService.java` — `buildRequestedDescriptor` 填 `.frameRate(effectiveProjectionFrameRate())`（两种编解码都填）
- Modify: `$MAIN/core/stream/StreamRuntime.java` — `buildPublishedDescriptor` 复制 `requested.getFrameRate()`
- Modify: `$MAIN/core/render/RenderTarget.java` — `default void setFrameRate(int frameRate) {}`
- Create: `$MAIN/render/FrameRatePolicy.java`
- Modify: `$MAIN/render/SurfaceRenderTarget.java` — 记录 + 应用 + `getSurface()` 重放；`surface` 改 `volatile`
- Modify: `$MAIN/render/HostRenderTarget.java` — 转发 surface 半边
- Modify: `$MAIN/render/SwitchableRenderTarget.java` — 记录 + 幂等转发 + `setDelegate` 重放
- Modify: `$MAIN/core/client/ClientMediaSession.java` — `applyDescriptor` 下发；同哈希分支防御性补发
- Modify: `$TEST/core/support/FakeRenderTarget.java` — 记录 `setFrameRate` 调用序列（供负向用例断言 0 次）
- Modify: `$TEST/core/model/StreamDescriptorTest.java`（+4）
- Modify: `$TEST/transport/ScreenStreamProtoMapperTest.java`（+4）
- Modify: `$TEST/core/client/ClientMediaSessionSurfaceTest.java`（+2，正向；该文件的私有渲染目标必须自行覆写并记录 `setFrameRate`，因为它不是 `FakeRenderTarget`）
- Modify: `$TEST/core/client/ClientMediaSessionTest.java`（+1，负向）
- Modify: `$TEST/render/SwitchableRenderTargetTest.java`（+3）
- Modify: `$TEST/render/HostRenderTargetTest.java`（+1）
- Create: `$TEST/render/FrameRatePolicyTest.java`（+6）
- Modify: `scripts/checks/test_baseline.json`
- Modify: `docs/media-architecture.md`、`docs/task-board.md`、`docs/performance-research.md`、`docs/decision-board.md`

**Interfaces:**

```java
// StreamDescriptor
public int getFrameRate();                    // 0 when unknown or cleared
public Builder frameRate(int frameRate);      // negative is normalised to 0

// RenderTarget
/**
 * Declares the content frame rate of the frames rendered into {@link #getSurface()}.
 *
 * <p>A hint only. Zero means "unknown or cleared": implementations must not assert anything on
 * the platform and must stop replaying a previously asserted value. Interface default methods are
 * supported natively at minSdk 25, so no desugaring is involved.</p>
 */
default void setFrameRate(int frameRate) {
}

// render/FrameRatePolicy (pure JVM, no Android types)
interface FrameRateApplier {
    void apply(int frameRate, boolean seamless);
}

/**
 * Applies the platform hint for one API level. Nothing is applied below API 30 or for a
 * non-positive frame rate; API 30 uses the two-argument variant, API 31+ the seamless variant.
 */
static void applyFrameRate(int frameRate, int sdkInt, FrameRateApplier applier);
```

**Semantics（写死）:**

`FrameRateApplier` 与 `applyFrameRate` 放在同一个文件 `render/FrameRatePolicy.java` 内，包私有，便于 JVM 测试直接构造；不新增第二个顶层类型文件。包私有也避免公共方法暴露包私有参数类型。

1. `SurfaceRenderTarget`：`frameRate` 与 `surface` 都改 `volatile`；`setFrameRate(fps <= 0)` 记 0、**不调平台**、`appliedFrameRate = 0`；正值在 surface 非空且有效时经策略调用平台，`RuntimeException` 吞掉。
2. `getSurface()` 创建/刷新 surface 后重放一次（覆盖“设置时还没有 surface”）；新建 surface 后先把 `appliedFrameRate` 清 0，再调用 `applyFrameRate()`，确保同值早退不会漏到新 surface。
3. `SwitchableRenderTarget`：与当前值相同则直接返回（幂等）；`setDelegate` 只在 `frameRate > 0` 时重放，0 不重放旧值。
4. `ClientMediaSession.applyDescriptor`：`setContentSize` 之后、创建解码器之前，**仅当** `isSurfaceOutputRequired()` 时下发。
5. 同 epoch + 同 hash 分支：帧率不同 → 更新描述符、下发、返回 false；帧率相同 → 维持原行为。**这是防御性代码**，当前服务端每次参数变化都推进 epoch，端到端不可复现，不得为此造设备用例。
6. 服务端两种编解码都填；客户端按 surface 输出消费（G2）。

**Steps:**

- [ ] **Step 1：先写失败用例（21 条）**

`StreamDescriptorTest`（+4）：`frameRate_doesNotChangeConfigHash`、`withConfigEpoch_keepsFrameRateAndHash`、`frameRate_negativeIsNormalisedToZero`、`frameRate_defaultsToZero`。

`ScreenStreamProtoMapperTest`（+4）：`descriptorRoundTrip_keepsFrameRate`、`descriptorWithoutFrameRate_readsZero`、`toProtoDescriptor_keepsConfigHash`、`descriptorFrameRate_doesNotAffectHashOnRoundTrip`。

`ClientMediaSessionSurfaceTest`（+2，正向；该文件已有 surface-required 的 fake codec 与构造空 surface 的手法；先给文件内私有渲染目标补一个记录式 `setFrameRate`）：

| 用例 | 断言 |
| --- | --- |
| `onDescriptor_surfaceCodecAppliesFrameRate` | 新 epoch 描述符 → 渲染目标收到描述符里的值，且解码器正常进入等待随机接入点 |
| `onDescriptor_sameHashNewFrameRateAppliesWithoutReconfigure` | 同 epoch 同 hash、仅帧率不同 → 返回 false、多发一次、解码器配置次数不变 |

`ClientMediaSessionTest`（+1，负向）：`onDescriptor_jpegDoesNotSetFrameRate`（不需要 surface 输出的 fake codec → 0 次）。

`SwitchableRenderTargetTest`（+3）：`setFrameRate_forwardsToDelegate`、`setFrameRate_sameValueIsNotForwardedTwice`、`setDelegate_replaysOnlyPositiveFrameRate`（设 0 后换宿主收不到；设 60 后收到 60）。

`HostRenderTargetTest`（+1）：`setFrameRate_reachesSurfaceHalfOnly`。

`FrameRatePolicyTest`（6 条）：

| 用例 | 断言 |
| --- | --- |
| `applyFrameRate_belowApi30DoesNothing` | sdk=29 → applier 0 次 |
| `applyFrameRate_api30UsesTwoArgumentVariant` | sdk=30 → 1 次且 seamless 为假 |
| `applyFrameRate_api31UsesSeamlessVariant` | sdk=31 → 1 次且 seamless 为真 |
| `applyFrameRate_futureApiStaysSeamless` | sdk=35 → seamless 为真 |
| `applyFrameRate_nonPositiveIsIgnored` | 0 与 -1 → 0 次 |
| `applyFrameRate_doesNotSwallowApplierFailures` | applier 抛 → 原样冒泡（吞异常是渲染目标的职责） |

- [ ] **Step 2：跑红测**

```bash
./gradlew :app:testDebugUnitTest --tests '*StreamDescriptorTest*' --tests '*ScreenStreamProtoMapperTest*' \
  --tests '*ClientMediaSessionSurfaceTest*' --tests '*ClientMediaSessionTest*' \
  --tests '*SwitchableRenderTargetTest*' --tests '*HostRenderTargetTest*' --tests '*FrameRatePolicyTest*'
```

Expected: 编译失败（新接口方法、枚举/策略类、proto 字段缺失），既有用例全绿——**正向用例必须放在 surface 测试类**，放在普通会话测试类里会因为 fixture 不需要 surface 而拿不到调用，导致“测试永远绿得没有意义”。

- [ ] **Step 3：实现**

顺序：proto → 模型与哈希（确认 `computeConfigHash` 字节流零改动）→ mapper → 服务端两处填值 → `RenderTarget` 默认方法 → 策略 → 三处实现（surface / host / switchable）→ 会话下发 → 指标无关（本棒不新增键）。

- [ ] **Step 4：跑绿测（定向）**

```bash
./gradlew :app:testDebugUnitTest --tests '*StreamDescriptorTest*' --tests '*ScreenStreamProtoMapperTest*' \
  --tests '*ClientMediaSession*Test*' --tests '*SwitchableRenderTargetTest*' --tests '*HostRenderTargetTest*' \
  --tests '*FrameRatePolicyTest*'
```

- [ ] **Step 5：全量 + 双变体 lint + 基线**

```bash
./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest
./gradlew :app:lintDebug :app:lintRelease
python scripts/checks/check_test_count.py --results app/build/test-results/testDebugUnitTest app/build/test-results/testReleaseUnitTest
python scripts/checks/check_cjk.py
```

- [ ] **Step 6：文档**

- `docs/media-architecture.md` §4.4 增“客户端内容帧率提示”：不入配置哈希、API 30 门控、服务端两种编解码都填、客户端按 surface 输出消费、0 表示清除且不重放。
- `docs/task-board.md`：新增 `TB-MEDIA-007` 行与详情块。
- `docs/performance-research.md` §4.2 第 5 条标“**部分落地**（2026-10-23）：仅 `Surface.setFrameRate` 提示，**未含 `Display.Mode` 切换**”；该行原文把两者写在一起，不得整条标已落地。
- `docs/decision-board.md`：`DV-011` 补注“内容帧率提示不等于严格限帧”。

- [ ] **Step 7：提交**

```bash
git add app/src/main/proto/screen_stream.proto \
        app/src/main/java/com/obby/android/localscreenshare/core/model/StreamDescriptor.java \
        app/src/main/java/com/obby/android/localscreenshare/transport/ScreenStreamProtoMapper.java \
        app/src/main/java/com/obby/android/localscreenshare/service/ScreenShareService.java \
        app/src/main/java/com/obby/android/localscreenshare/core/stream/StreamRuntime.java \
        app/src/main/java/com/obby/android/localscreenshare/core/render/RenderTarget.java \
        app/src/main/java/com/obby/android/localscreenshare/render \
        app/src/main/java/com/obby/android/localscreenshare/core/client/ClientMediaSession.java \
        app/src/test/java/com/obby/android/localscreenshare/core/model \
        app/src/test/java/com/obby/android/localscreenshare/transport \
        app/src/test/java/com/obby/android/localscreenshare/core/client \
        app/src/test/java/com/obby/android/localscreenshare/render \
        app/src/test/java/com/obby/android/localscreenshare/core/support/FakeRenderTarget.java \
        scripts/checks/test_baseline.json docs/media-architecture.md docs/task-board.md \
        docs/performance-research.md docs/decision-board.md
git commit -m "feat(render): 下发内容帧率提示到呈现面"
```

**回退**：两处独立开关——客户端不下发、服务端不填；字段与映射保留无害（旧服务端不填时新客户端读 0，旧客户端忽略未知字段）。不做 `Display.Mode` 切换，不影响屏幕刷新率本身。

---

## B5 单客户端码率背压（控制面反馈 + 专用执行器）

**目标**：观看端在**有积压时**上报队列深度/等待/追赶事件；服务端只在**单客户端、H.264**时自动调码率，多客户端只观测；空闲观看端零定时器。

**Files:**

- Modify: `app/src/main/proto/screen_stream.proto` — `ClientMessage.feedback = 6`、`StreamFeedback`
- Create: `$MAIN/core/model/StreamFeedback.java`
- Modify: `$MAIN/transport/ScreenStreamProtoMapper.java` — `toStreamFeedback`、`toProtoFeedback`、`dispatchClientMessage` 增加 FEEDBACK 分支
- Modify: `$MAIN/transport/ClientMessageListener.java` — `default void onFeedback(StreamFeedback feedback) {}`
- Create: `$MAIN/client/BacklogFeedback.java`
- Modify: `$MAIN/client/ScreenShareClient.java` — 事件驱动上报、`FEEDBACK_WINDOW_MS = 3000`、`stop()` 清理
- Modify: `$MAIN/settings/Preferences.java` — 隐藏键 `backpressure` 与 `isBackpressureEnabled()`（默认开启；不新增界面入口）
- Create: `$MAIN/core/stream/BackpressurePolicy.java`
- Create: `$MAIN/server/BitrateTarget.java`
- Create: `$MAIN/server/RuntimeBitrateTarget.java`
- Create: `$MAIN/server/FeedbackSink.java` — 窄接口：`offer(StreamFeedback, BitrateTarget)` 与 `close()`；handler 只依赖它
- Create: `$MAIN/server/BackpressureCoordinator.java` — 实现 `FeedbackSink`；非 `final`；提供生产工厂与可注入执行器的构造
- Modify: `$MAIN/server/StreamCallHandler.java` — 末尾参数为 `FeedbackSink` 的新构造重载（可 null，null 视同 `NONE`）；`onClientMessage` 增加 FEEDBACK 分支（同步区内只 gate，同步区外 offer）
- Modify: `$MAIN/server/ScreenShareServer.java` — **在 `setMetrics()` 内**创建协调器（捕获传入的同一 `PipelineMetrics` 实例、注入开关 supplier；若已有则先关停旧的），`stream()` 把同一实例交给 handler，`stop()` 有界关停；**禁止**在构造期创建（构造期字段仍是 `PipelineMetrics.NONE`）
- Modify: `$MAIN/core/metrics/PipelineMetrics.java`、Create `$MAIN/core/metrics/FeedbackMetricNames.java`、Modify `$MAIN/core/metrics/ServerMetricNames.java`、`$MAIN/core/metrics/MetricsFormatter.java`
- Create: `$TEST/core/model/StreamFeedbackTest.java`
- Create: `$TEST/client/BacklogFeedbackTest.java`
- Create: `$TEST/core/stream/BackpressurePolicyTest.java`
- Create: `$TEST/server/BackpressureCoordinatorTest.java`
- Create: `$TEST/server/RuntimeBitrateTargetTest.java`
- Modify: `$TEST/transport/ScreenStreamProtoMapperTest.java`（+3）
- Modify: `$TEST/server/StreamCallHandlerTest.java`（+3）
- Modify: `$TEST/core/metrics/MetricsFormatterTest.java`（+2）
- Modify: `scripts/checks/test_baseline.json`
- Modify: `docs/media-architecture.md`、`docs/decision-board.md`、`docs/task-board.md`

**Interfaces:**

```java
// core/model/StreamFeedback (read-only)
public final class StreamFeedback {
    public static final StreamFeedback EMPTY;
    public StreamFeedback(int queueDepthPeak, int catchUpEvents, int queueWaitPeakMs,
                          long clientUptimeMs);   // negative arguments throw
    public int getQueueDepthPeak();
    public int getCatchUpEvents();
    public int getQueueWaitPeakMs();
    public long getClientUptimeMs();
}

// client/BacklogFeedback (app layer, m-prefixed, zero allocation on the decode path)
public final class BacklogFeedback {
    public void onQueueDepth(int depth);     // volatile max, sampled at dequeue time
    public void onQueueWait(long waitNs);    // volatile max
    public void onCatchUp();                 // AtomicInteger increment
    public boolean hasActivity();            // peakDepth > 0 || catchUps > 0
    public StreamFeedback sampleAndReset();
}

// core/stream/BackpressurePolicy (pure)
public final class BackpressurePolicy {
    public static final int CONGESTED_DEPTH = 8;              // early warning, pending calibration
    public static final int CONGESTED_WAIT_MS = 400;          // pending calibration
    public static final int CLEAR_DEPTH = 2;                  // pending calibration
    public static final int CLEAR_WAIT_MS = 100;              // pending calibration
    public static final int STEP_PERCENT = 75;                // pending calibration
    public static final int FLOOR_PERCENT = 40;               // pending calibration
    public static final int CLEAR_REPORTS_TO_RAISE = 2;       // pending calibration
    public static final long MIN_ACTION_INTERVAL_MS = 5000L;  // pending calibration

    public enum Level { CONGESTED, HOLD, CLEAR }
    public enum Action { NONE, LOWER, RAISE, RESTORE }

    public static Level classify(StreamFeedback feedback);

    public static Action decide(Level level, Action current, int currentBps, int ceilingBps,
                                int sessionCount, boolean h264, int clearStreak,
                                long sinceActionMs);

    /**
     * LOWER: one step down, clamped at the floor. RAISE: straight to the ceiling.
     */
    public static int targetBitrate(Action action, int currentBps, int ceilingBps);
}

// server/BitrateTarget (seam so the coordinator is JVM testable)
public interface BitrateTarget {
    int getSessionCount();

    /**
     * Codec id of the published descriptor, or null while none is published.
     */
    String getCodecId();

    int getTargetBitrateBps();

    /**
     * Applies a new target; throws RuntimeException when the encoder refuses.
     */
    void applyTargetBitrate(int bitrateBps);
}

// server/FeedbackSink (the only view the handler sees)
public interface FeedbackSink {
    /**
     * Records the newest report and schedules one apply pass; never blocks, never calls a codec.
     */
    void offer(StreamFeedback feedback, BitrateTarget target);

    /**
     * Stops the executor within a bounded wait; idempotent. Offers after this call are no-ops.
     */
    void close();
}

// server/BackpressureCoordinator implements FeedbackSink; not final so a test may observe it
public class BackpressureCoordinator implements FeedbackSink {
    /**
     * No-op sink: never creates a thread, never writes metrics, never calls a codec.
     */
    public static final FeedbackSink NONE;

    /**
     * Production factory: the executor is created lazily on the first offer, as one thread named
     * "lss-backpressure". Nothing is created while no report arrives or after close().
     */
    public static BackpressureCoordinator create(PipelineMetrics metrics, TimeSource timeSource,
                                                 BooleanSupplier enabled);

    /**
     * Test seam: the caller owns the executor, so a test can enqueue and drain it by hand.
     */
    public BackpressureCoordinator(PipelineMetrics metrics, TimeSource timeSource,
                                   BooleanSupplier enabled, Executor executor);

    @Override
    public void offer(StreamFeedback feedback, BitrateTarget target);

    @Override
    public void close();
}
```

**Decision semantics（写死）:**

1. 只有 `target.getSessionCount() == 1` 才动作；否则 `NONE` + `bp.skip`。
2. 先判 `target.getCodecId() == null` → `NONE` + `bp.skip`；非 H.264 → 同样跳过。判空必须在取 codec 之前，因为发布描述符在 defer 期间与失败之后可为 null。
3. 首次 `LOWER` 前记 `ceilingBps = target.getTargetBitrateBps()`；读回的当前值不等于上次应用值 ⇒ 外部写入，清空覆盖、不 RESTORE、不重试，并记 `bp.skip`（external-write）；RESTORE 前也要做同一检测，避免覆盖用户/热降档刚写的码率（用户改设置、热降档、旋转/尺寸变化都会写回用户码率）。此行为写进 Javadoc。
4. `LOWER`：一步 ×75%，钳到 `ceiling × 40%`；目标等于当前码率（已在下限）时不调用 `applyTargetBitrate`、不记 `bp.apply`、不写动作日志，只记 `bp.skip`（at-floor）。`RAISE`：一步回到 `ceiling` 并立即结束覆盖。
5. 回升需连续 2 份 CLEAR 且距上次动作 ≥5 s；HOLD 不降档且把连续 CLEAR 计数归零。
6. `RESTORE`：多客户端、非 H.264、总闸关闭、`close()` 前置检查时，若已有覆盖且读回值仍等于上次应用值，则回 `ceiling` 并结束覆盖，同样受 5 s 间隔约束；读回值不同则按第 3 条放弃恢复。
7. `enabled.getAsBoolean()` 每次 apply 现读；false → `NONE` + `bp.skip`。
8. `applyTargetBitrate` 抛 `RuntimeException`：吞掉、状态不变、`bp.skip` 增加、失败日志最多每 60 s 一条（`WARN_INTERVAL_MS = 60_000`）。`runPass` 外层再兜底 `RuntimeException`（含总闸 supplier 与 target getter）：记 `bp.skip`（coordinator）、日志同样限流、不冒泡。
9. 每次真正执行的动作打一行日志（动作、来源值、目标值、深度、等待、追赶、客户端数），**同一动作只一行**。
10. **指标实例只来自 `setMetrics()` 注入的那个 scope**：协调器在 `setMetrics()` 内创建，`stream()` 把同一实例交给 handler；禁止在 `ScreenShareServer` 构造期创建（构造期字段是 `PipelineMetrics.NONE`，`bp.apply`/`bp.skip`/`bp.bitrate` 会被静默丢弃，JVM 用例注入真实 scope 也查不出来）。`bp.bitrate` 记录最近一次成功应用的值。
11. **执行器与最新值胜出**：`offer` 只把报告放进“最新一份”槽位并对 `AtomicBoolean scheduled` 做一次 CAS；已在途时后续 `offer` 不排队。因此连发 3 份、再 drain 一次，只会应用第 3 份一次。
12. **`NONE` 与 `close()` 之后**：`offer` 直接返回——不排程、不建线程、不写指标、不调编码器、不抛异常；`close()` 幂等，等待在途任务最多 1 s，且不会为了关停而创建线程。

**Reporting semantics（写死）:**

- 采样点：解码任务**出队时**读队列深度（与追赶采样同点，消除差一帧歧义）、记等待、追赶 episode 开始时计一次。
- `hasActivity()` 的含义是“窗口内出现过任何排队或追赶”，不是“已经拥塞”：健康流上偶尔出现一份报告是允许的，报告转干净后即停表。真正决定动作的是服务端策略的 8 / 400 ms 阈值。
- 排程：`if (!mFeedbackScheduled.get() && mBacklogFeedback.hasActivity()) { if (mFeedbackScheduled.compareAndSet(false, true)) mMainHandler.postDelayed(...); }`。
- 发送：取一份快照并重置；未连接/已停止 → `fb.skip`、清排程、返回。发送后：本份仍有活动 → 续排 3 s；否则（本份是 CLEAR）发完即停。
- `stop()`：移除回调、清排程标志。

**Steps:**

- [ ] **Step 1：先写失败用例（54 条）**

`StreamFeedbackTest`（4）：构造拒绝负值、`EMPTY` 全零、getter 回读、不可变。
`BacklogFeedbackTest`（6）：峰值深度、峰值等待换算毫秒、追赶计数、读取后重置、空快照全零、`hasActivity` 仅在有深度或追赶时为真。
`BackpressurePolicyTest`（17）：

| 用例 | 断言 |
| --- | --- |
| `classify_depthAtThresholdIsCongested` | 深度 8 → 拥塞 |
| `classify_waitAtThresholdIsCongested` | 等待 400 → 拥塞 |
| `classify_anyCatchUpIsCongested` | 追赶 1 次 → 拥塞 |
| `classify_clearNeedsAllThreeBelow` | 2 / 100 / 0 → CLEAR；3 / 100、2 / 101 → HOLD；追赶 1 → CONGESTED |
| `classify_middleBandIsHold` | 3 / 0、7 / 99、0 / 399 → HOLD |
| `decide_classifiedThresholdsGateActions` | 深度 8 与等待 400 经 `classify` 后均降档；3 / 0 经 `classify` 后无动作 |
| `decide_clearNeverLowers` | CLEAR + 无覆盖 → 无动作 |
| `decide_multipleClientsNeverAct` | 客户端数 2 → 无动作 |
| `decide_nonH264NeverActs` | 非 H.264 → 无动作 |
| `decide_congestedLowersOneStep` | 无覆盖 + 拥塞 + 间隔足够 → 降档，目标为 75% |
| `decide_holdNeitherLowersNorRaises` | HOLD + 无覆盖 → 无动作；HOLD + 有覆盖 + streak 2 → 无动作 |
| `decide_floorsAtFortyPercent` | 连续降档 → 不低于上限的 40% |
| `decide_respectsMinActionInterval` | 4999 ms → 无动作；5000 ms → 允许 |
| `decide_raiseNeedsTwoClearReports` | 连续清除 1 → 无动作；2 → 回升 |
| `decide_raiseGoesStraightToCeiling` | 当前 1.5M、上限 4M → 一步回 4M |
| `decide_restoreOnlyWhenOverridden` | 无覆盖 + 多客户端 → 无动作；有覆盖 + 多客户端 → 恢复 |
| `decide_restoreTargetsCeiling` | 恢复目标等于上限 |

`BackpressureCoordinatorTest`（16，**手动 drain 的队列执行器** + 假目标；每条用例先 `offer`，再显式 drain，再断言）：

| 用例 | 断言 |
| --- | --- |
| `offer_appliesOnlyTheNewestReport` | 连发 3 份（中间不 drain，第 3 份深度 9）→ 队列里只有 1 个任务；drain 后应用恰好 1 次且用第 3 份 |
| `offer_holdReportDoesNotLower` | 深度 3 → 不降档、`bp.apply` 为 0 |
| `offer_holdResetsClearStreak` | 降档后 CLEAR、HOLD、CLEAR 不回升；HOLD 清零 streak，再两份 CLEAR 才回上限 |
| `offer_recordsApplyAndBitrateMetrics` | 注入真实 metrics scope → `bp.apply` 加 1、`bp.bitrate` 等于生效值 |
| `offer_containsEncoderFailure` | 目标抛异常 → 不冒泡、`bp.skip` 加 1 |
| `offer_rateLimitsFailureLog` | 59 s 内连续两次失败 → 只 1 条失败日志 |
| `offer_skipsWhenDisabled` | 开关假 → 0 次应用、跳过增长；随后开关真 → 立即可用 |
| `offer_afterCloseIsNoOp` | `close()` 后 `offer` 不入队、无异常；`close()` 幂等；`NONE` 的 `offer` 不产生任何目标调用或指标写入 |
| `offer_multiClientRestoresOnce` | 有覆盖 + 客户端数 2 → 一次恢复至上限，之后不再调用 |
| `offer_externalWriteClearsOverride` | 读回值不等于上次应用值 → 不恢复、不重试 |
| `offer_externalWriteBeforeRestoreClearsOverride` | 多客户端前码率被外部改写 → 不恢复、`bp.skip` 加 1、动作日志说明 external write |
| `offer_lowerAtFloorDoesNotCallEncoderOrLog` | 已在下限再收到拥塞 → 不调编码器、`bp.apply` 不变、动作日志不变、`bp.skip` 加 1 |
| `offer_containsEnabledSupplierFailure` | 总闸 supplier 抛异常 → 不冒泡、`bp.skip` 加 1、限流失败日志 |
| `offer_containsTargetGetterFailure` | 会话数 getter 抛异常 → 不冒泡、`bp.skip` 加 1 |
| `offer_containsBitrateGetterFailure` | 码率 getter 抛异常 → 不冒泡、`bp.skip` 加 1 |
| `offer_rateLimitsCoordinatorFailureLog` | 59 s 内两次协调器异常 → 2 次 skip、只 1 条日志 |

`RuntimeBitrateTargetTest`（3）：描述符未发布时返回 null、会话数读 hub、应用码率走运行时更新参数。
`ScreenStreamProtoMapperTest`（+3）：`feedbackRoundTrip`、`feedbackDefaultsAreZero`、`feedbackIsDispatchedToListener`（同时保留“无已知载荷仍抛异常”的既有断言）。
`StreamCallHandlerTest`（+3，用测试文件内的记录式 `FeedbackSink` 观察；不需要子类化协调器）：未 attach 时反馈被忽略（sink 收到 0 次、无异常、attach 键为 null）、attach 后反馈到达（1 份、未关闭、attach 状态不变）、`close()` 后反馈被丢弃。
`MetricsFormatterTest`（+2）：`bpKeys_printWhenMeasured`、`fbKeys_omittedWhenZero`。

- [ ] **Step 2：跑红测**

```bash
./gradlew :app:testDebugUnitTest --tests '*BackpressurePolicyTest*' --tests '*BackpressureCoordinatorTest*' \
  --tests '*BacklogFeedbackTest*' --tests '*StreamFeedbackTest*' --tests '*RuntimeBitrateTargetTest*' \
  --tests '*ScreenStreamProtoMapperTest*' --tests '*StreamCallHandlerTest*' --tests '*MetricsFormatterTest*'
```

Expected: 编译失败（新增类与 proto 字段缺失）；`ScreenStreamProtoMapperTest` 的“未知载荷”既有用例会因新增分支而变化，按 `feedbackIsDispatchedToListener` 的预期调整，**不得**放宽“无已知载荷仍抛异常”。

- [ ] **Step 3：实现（按依赖顺序）**

1. proto 与模型：`StreamFeedback`（负值拒绝、不可变）。
2. mapper：两个方向的映射 + `dispatchClientMessage` 的 FEEDBACK 分支；监听器加 `default` 空实现（不加抽象方法，避免所有实现被迫修改）。
3. 客户端累加器与上报：`BacklogFeedback` 零分配；`ScreenShareClient` 在两条解码路径的出队点采样，按 Reporting semantics 排程；`stop()` 清理。
4. 纯策略：`BackpressurePolicy`（`classify` / `decide` / `targetBitrate`），Javadoc 解释“8 = 早期预警、64 = 不可恢复”。
5. 服务端：`BitrateTarget` 接缝 + `RuntimeBitrateTarget`（全部判空：描述符可空、参数非空、更新参数可能抛 `IllegalStateException`）+ `FeedbackSink` 窄接口 + `BackpressureCoordinator`（实现 `FeedbackSink`、非 `final`；生产工厂惰性建 `lss-backpressure` 单线程、测试构造注入执行器；最新值胜出 + `AtomicBoolean` 已排程；指标、限流日志、`close()` 有界关停；`NONE` 为空操作壳）。
6. handler：新构造重载（末尾 `FeedbackSink`，可 null；null 视同 `NONE`）；`onClientMessage` 在同步区内先判 `attached`，未 attach 直接忽略；已 attach 则解析成模型放局部变量，**退出同步块后** `offer`。
7. 服务端装配：把协调器创建挪进 `setMetrics()`，用传入的同一个 `PipelineMetrics` 实例（此调用在 `start()` 之前，与既有 `setMetrics` 装配顺序一致）；`stream()` 把同一实例交给 handler；`stop()` 关停协调器。协调器**不得**在 `ScreenShareServer` 构造期创建，否则捕获的是字段默认值 `PipelineMetrics.NONE`。
8. 指标：三个服务端键、两个客户端键、`bp.bitrate`。

- [ ] **Step 4：跑绿测（定向）**

```bash
./gradlew :app:testDebugUnitTest --tests '*Backpressure*' --tests '*BacklogFeedbackTest*' \
  --tests '*StreamFeedbackTest*' --tests '*RuntimeBitrateTargetTest*' --tests '*ScreenStreamProtoMapperTest*' \
  --tests '*StreamCallHandlerTest*' --tests '*MetricsFormatterTest*'
```

- [ ] **Step 5：全量 + 仪器编译 + 双变体 lint + 基线**

```bash
./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest
./gradlew :app:assembleDebugAndroidTest
./gradlew :app:lintDebug :app:lintRelease
python scripts/checks/check_test_count.py --results app/build/test-results/testDebugUnitTest app/build/test-results/testReleaseUnitTest
python scripts/checks/check_cjk.py
```

- [ ] **Step 6：文档**

- `docs/media-architecture.md` 新增 §6.5“控制面背压反馈（客户端到服务端）”：字段语义、事件驱动上报（不是恒定 3 s）、5 s 最小间隔、单客户端约束、8 与 64 的分工、外部写入清空覆盖、多客户端只观测。
- `docs/decision-board.md`：新增 `DB-MULTI-007`；`DV-012` 行补“背压首版仅单客户端，不构成重开理由”。
- `docs/task-board.md`：新增 `TB-ENG-015` 行与详情块。

- [ ] **Step 7：提交**

```bash
git add app/src/main/proto/screen_stream.proto \
        app/src/main/java/com/obby/android/localscreenshare/core/model/StreamFeedback.java \
        app/src/main/java/com/obby/android/localscreenshare/transport \
        app/src/main/java/com/obby/android/localscreenshare/client \
        app/src/main/java/com/obby/android/localscreenshare/core/stream/BackpressurePolicy.java \
        app/src/main/java/com/obby/android/localscreenshare/server \
        app/src/main/java/com/obby/android/localscreenshare/core/metrics \
        app/src/main/java/com/obby/android/localscreenshare/settings/Preferences.java \
        app/src/test/java/com/obby/android/localscreenshare/core/model \
        app/src/test/java/com/obby/android/localscreenshare/client \
        app/src/test/java/com/obby/android/localscreenshare/core/stream \
        app/src/test/java/com/obby/android/localscreenshare/server \
        app/src/test/java/com/obby/android/localscreenshare/transport \
        app/src/test/java/com/obby/android/localscreenshare/core/metrics \
        scripts/checks/test_baseline.json docs/media-architecture.md docs/decision-board.md docs/task-board.md
git commit -m "feat(server): 单客户端码率背压与观看端积压反馈"
```

**回退**：三条独立闸门——客户端不排程（删一处调用）、服务端不注入协调器（`stream()` 传 `BackpressureCoordinator.NONE` 或 null）、总闸 `backpressure` 置假。任一关闭即回旧行为；协议字段保留无害。

---

## B6 文档与看板收口（无行为改动）

**Files:**

- Modify: `docs/superpowers/plans/2026-10-23-performance-candidates.md` — 回填各棒实测测试计数与提交哈希
- Modify: `docs/decision-board.md` — `DB-CODEC-016`、`DB-POWER-003`、`DB-MULTI-007` 入总览与详情；`DV-011`、`DV-012` 补注
- Modify: `docs/task-board.md` — `TB-MEDIA-006/007`、`TB-POWER-002`、`TB-CLIENT-007`、`TB-ENG-015` 的状态与证据；`TB-ENG-016` 写明五项进度；“当前焦点”加一行
- Modify: `docs/performance-research.md` — §4.2 加“落地状态（2026-10-23）”列（第 1 条注明未含 `KEY_OPERATING_RATE` 及原因；第 5 条标“部分落地：仅 `Surface.setFrameRate`，未含 `Display.Mode`”）；§6 标注代码状态与待校准参数；§7 保持“未实测”
- Modify: `docs/media-architecture.md` — §4.4 / §5.3.1 / §6.5 / §12 失败模式表各补一行
- Modify: `docs/superpowers/specs/2026-10-23-performance-candidates-design.md` — 只回填“已落地/未落地”状态行，不改设计结论
- Modify: `scripts/checks/test_baseline.json` — 定格为最终实测计数

**Steps:**

- [ ] **Step 1：核对每棒证据**

逐棒检查提交哈希、测试计数、lint 双变体结果、文档条目是否齐全；任何一棒缺证据就保持“待办/部分完成”，**不得**写成已完成。

- [ ] **Step 2：写看板**

每个 ID 一个详情块：交付物、验证命令、实测计数、提交哈希、未完成项（尤其设备验证）。批次总项 `TB-ENG-016` 在五项全部写回代码与 JVM 证据后转 **🔶 部分完成**（设备验证未做，不能转 ✅）；只有五项设备证据都到位才转完成。

- [ ] **Step 3：跑文档与卫生检查**

```bash
python scripts/checks/check_docs.py
python scripts/checks/check_strings.py
python scripts/checks/check_cjk.py
git status --short
```

Expected: 三个脚本全绿；`git status` 只剩本次要提交的文档与基线。

- [ ] **Step 4：提交**

先看清单，再逐文件添加：

```bash
git status --short
git add docs/decision-board.md docs/task-board.md docs/performance-research.md \
        docs/media-architecture.md docs/superpowers/plans/2026-10-23-performance-candidates.md \
        docs/superpowers/specs/2026-10-23-performance-candidates-design.md \
        scripts/checks/test_baseline.json
git diff --cached --stat
git commit -m "docs(board): 性能候选批次收口与基线定格"
```

> 本棒只允许文档与 `scripts/checks/test_baseline.json` 变化。若 `git status` 出现任何 `.java`，说明前五棒漏提交，先回到对应棒补提交，不在本棒夹带。

**回退**：纯文档；未落地的棒必须写成“未实施”，不得删除条目。

---

## 每棒统一验证清单

```bash
# 1) 该棒定向用例
./gradlew :app:testDebugUnitTest --tests '<棒相关类>'

# 2) 双变体全量 JVM 测试
./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest

# 3) 测试基线守卫（只增不减、0 失败）
python scripts/checks/check_test_count.py \
  --results app/build/test-results/testDebugUnitTest app/build/test-results/testReleaseUnitTest

# 4) 源码与资源卫生
python scripts/checks/check_cjk.py
python scripts/checks/check_strings.py
python scripts/checks/check_docs.py

# 5) 总体验证（每棒末 + 最终交付）
./gradlew build :app:assembleDebugAndroidTest
```

`lintRelease` 在 B2 / B3 / B4 / B5 必须与 `lintDebug` 一起跑。`test_baseline.json` 必须在每棒提交内更新为实测值，不得先合并再补。

## 交付判据

五项都以“代码 + JVM 用例 + 指标键 + 文档条目”四件套落地；`./gradlew build :app:assembleDebugAndroidTest` 全绿；`lintDebug` 与 `lintRelease` 同时通过；`check_test_count.py` 显示 `tests` 与 `classes` 都不减且 0 失败；`dec.lowLat` 三态可区分、热压力采样随会话与开关正确上下线（无会话时零 1 Hz 定时器）、背压空闲时零定时器；且**没有任何一处**改动 `CatchUpPolicy` 阈值、`SurfaceView` 结构、多档流或 `DV-012` / `DV-027` 的裁决状态。

设备验证不在本计划内，检查点见设计文档 §8；阈值校准必须回填设计文档 §10 与对应策略类的 Javadoc。

## 附：审查修正落实对照

本表用于复审时逐条核对“审查修正没有回退”。每条给出落实位置，避免只在正文里“看起来写过”。

| 修正 | 落实位置 |
| --- | --- |
| 三态指标（0 不打印、1 生效、2 回退） | B2 Step 1 的 `MetricsFormatterTest` 两条、B2 Step 3 的映射、设计文档 §4.1 与 §7.1 |
| 热余量会话级 arm/disarm（arm 入口含“会话运行中”） | B3 生命周期表、设计文档 §4.2 与 §6.4、B3 Step 3 |
| 关闭开关立即 reset 且不 re-arm | B3 生命周期表与 `onThermalThrottleSettingChanged` |
| 丢片追赶计指标但不占连续预算 | B1 Interfaces、Step 3 第 2 条与第 5 条、设计文档 §4.3 |
| 采样器返回“结束 + 决策”两个字段 | B1 Interfaces 与 Step 3 第 1 条 |
| 外部追赶合并但不刷新时间 | B1 Interfaces 注释与用例 `onExternalCatchUp_mergesIntoOpenEpisode` |
| 三个解码桥实现全部列全 | B2 Files 中的三处、Review Focus 5 |
| seam 只用 boolean、不暴露平台格式 | B2 Interfaces 的 `FormatApplier`、Review Focus 6 |
| 三级回退顺序与状态 | B2 Write-down rules 2–3 |
| gauge 只在 configure + start 整体成功后写 | B2 Write-down rule 5 与用例 `configure_startFailureLeavesGaugeUnwritten` |
| 方法级 `@RequiresApi(R)` + 调用点显式版本判断 | B3 Files 与 Step 3 |
| 正向帧率用例放 surface 测试类、负向留普通会话测试类 | B4 Step 1 的用例位置与 Step 2 的 Expected |
| 纯 JVM `FrameRatePolicy` 钉两参/三参/异常 | B4 Interfaces 与 6 条用例 |
| `frame_rate = 0` 清除且不重放 | B4 Semantics 1–3 与 `setDelegate_replaysOnlyPositiveFrameRate` |
| 同哈希分支是防御代码、不造设备用例 | B4 Semantics 5 与 Review Focus 8 |
| `BitrateTarget` 接缝 + 生产实现 | B5 Interfaces 与 Step 3 第 5 条 |
| 总闸 `BooleanSupplier` 现读 | B5 Decision semantics 7 与 `offer_skipsWhenDisabled` |
| 未 attach 不解析、不 offer | B5 Step 3 第 6 条与 `onClientMessage_feedbackBeforeAttachIsIgnored` |
| 异常不冒泡、失败日志每 60 s 一条 | B5 Decision semantics 8 与 `offer_rateLimitsFailureLog` |
| 事件驱动上报、空闲零定时器 | B5 Reporting semantics、Review Focus 12、设备项 ⑨ |
| 上报契约保留 `fb.sent` / `fb.skip` | B5 指标文件与 `MetricsFormatterTest` |
| 深度采样点在出队时 | B5 Reporting semantics 第 1 条 |
| 8 与 64 的分工写进文档 | B5 文档步骤与设计文档 §4.5 |
| 回升一步回上限并结束覆盖 | B5 Decision semantics 4 与 `decide_raiseGoesStraightToCeiling` |
| 外部写入清空覆盖 | B5 Decision semantics 3 与 `offer_externalWriteClearsOverride` |
| 指标常量类拆域、服务端恰 20、客户端不再追加 | Global Constraints G6、B2/B5 文件表、设计文档 §7.1 |
| `KEY_OPERATING_RATE` 未含及原因 | B2 Step 6 与设计文档 §4.1、§5 |
| `frame_rate` 不入哈希 | B4 用例 `frameRate_doesNotChangeConfigHash` 与设计文档 §4.4 |
| 裸 TCP/gRPC 共用 mapper、UDP 描述符走控制面 | Global Constraints G1 |
| `lintRelease` 覆盖 B2–B5 | Global Constraints G8 与 Review Focus 9 |
| 每棒同提交更新测试基线 | 头部说明、每棒 Step 5、统一验证清单 |
| 用例/类数清点修正 | 本文 §0 与“棒次总览” |
| 看板登记措辞与裁决的三处对齐 | 本文 §0 |

### 终审必改 8 项（2026-10-23 已落实）

下表对应终审报告的 8 个必须修正项，逐条给出改动后的落位；正文与设计文档已同口径改写，本表用于复审时确认没有回退。

| # | 终审项 | 落实位置 |
| --- | --- | --- |
| 1 | B5 协调器不得在构造期绑定 `PipelineMetrics.NONE` | B5 Files 的 `ScreenShareServer` 行、Step 3 第 7 条、Decision semantics 10；设计文档 §4.5 装配行与 §6.5 装配段。改法：在 `setMetrics()` 内创建，`stream()` 把同一实例交给 handler，构造期禁止创建 |
| 2 | 协调器缺执行器接缝与观察接口 | B5 Interfaces 的 `FeedbackSink`、`create(...)`、四参构造（可注入 `Executor`）；Decision semantics 11–12；`BackpressureCoordinatorTest` 改为“手动 drain 队列执行器”，`StreamCallHandlerTest` 用记录式 `FeedbackSink`；设计文档 §4.5 执行器/关闭语义两行与 §6.5 |
| 3 | B3 arm 条件必须含“会话运行中” | B3 Lifecycle rules 的 arm 与“设置打开”两行、不变式、Step 3 第 ② 条；设计文档 §4.2 生命周期/开关打开两行、§6.4 结尾段、§8 ②、§9 新增风险行。无会话时设置打开只立即采样一次，不 arm 1 Hz |
| 4 | 浮点千分比统一 `Math.round` | B3 Interfaces 的 `onSample` Javadoc 与 `MAX_VALID_HEADROOM`、Step 1 两条阈值用例说明、Step 3 第 ① 条；设计文档 §4.2 千分比换算行。阈值仍按 0.70 / 0.85 边界（700 / 850），禁止 `(int)` 截断 |
| 5 | `thermalHeadroom` 改述为热压力千分比 | 设计文档 §4.2 开头定义与阈值行、§7.1 键表与脚注、§8 ②、§10 四行、§6.4；B3 Interfaces 注释与 Step 3。1000 = 平台开始限流、允许 >1000 且不钳制，策略只忽略 `[0, 2]` 之外的样本 |
| 6 | B2 补测试侧的桥状态入口 | B2 Files 新增 `FakeMediaCodecBridgeFactory`（`setNextDecoderLowLatencyState`）、Step 1 的 `H264DecoderTest`（+5）说明、Step 3 |
| 7 | B4 只标“部分落地” | B4 Step 6 与 B6 Files 的 `docs/performance-research.md` 行；设计文档 §9 新增风险行。仅 `Surface.setFrameRate`，未含 `Display.Mode` |
| 8 | G8 的 `tests` / `classes` 口径 | Global Constraints G8 与设计文档 §3 C8：两者都只能不减；合并测试类视为删测试，须确认零损失并在同一提交更新基线；双变体 `tests`/`classes` 必须一致 |
