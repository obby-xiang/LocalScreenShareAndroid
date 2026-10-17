# 编码框架核心契约与 JPEG 单流实施计划（修订版）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在**不接线现有投屏路径**的前提下，新增隔离的 `core` 框架：明确 RenderTarget/DecoderFactory 关系、带 `configEpoch` 的访问单元、描述符先行的媒体传输契约、静态 Codec SPI、JPEG 参考适配、单活动流运行时、可扩展 StreamHub、每客户端有界队列与客户端六状态机，并用 fake codec / fake transport / JVM 测试验证。本批次不宣称现有 JPEG/gRPC 投屏已经使用新框架。

**Architecture:** 新增 `com.obby.android.localscreenshare.core` 包，分为 `model`（纯数据）、`render`（RenderTarget 与 DecodedImage）、`codec`（静态 SPI 与注册表）、`codec.jpeg`（JPEG 参考实现）、`stream`（队列、发送会话、运行时、Hub）、`client`（客户端状态机）。解码器只通过 `RenderTarget` 输出：MediaCodec 将来使用其 `Surface`，JPEG 通过 `renderImage(DecodedImage)`；错误只通过 `DecoderException` 一条通道。每个 AU 自带 `configEpoch`，`sequence` 在 epoch 内递增。媒体传输先下发描述符，再发同 epoch 的首个 AU。

**Tech Stack:** Java 11 source/target、Android minSdk 25、compileSdk 35、AGP 8.9.0、Gradle 8.11.1、JUnit 4.13.2。仅使用现有依赖；不引入 Mockito、Kotlin、协程或新测试库。

**Spec:** [可扩展编码框架设计](../../codec-framework.md)

## Global Constraints

- 源码与目标兼容级别保持 Java 11（`sourceCompatibility JavaVersion.VERSION_11`、`targetCompatibility JavaVersion.VERSION_11`）。
- `minSdk 25`、`compileSdk 35`、`targetSdk 35` 不变；不提升最低系统版本。
- 不新增依赖；本批次 `build.gradle`、`gradle/libs.versions.toml` 零改动，测试只用 JUnit 4.13.2。
- 保持现有公开行为：JPEG 采集/压缩、gRPC `ScreenFrame`、NSD 发现、悬浮窗 UI、通知与统计行为不变；`screen_stream.proto` 不改。
- 本批次**不修改** `LssService`、`LssClientService`、`LssServer` 的数据路径，不做任何生产接线；只新增 `core` 包、JPEG 参考适配与 JVM 测试。现有投屏继续走旧路径，不能写成“已使用新框架”。
- 本批次不实现 `MediaCodec`、H.264、HEVC、AV1、RTP、WebRTC、GPU Fanout、多活动流调度、真实设备 instrumentation。
- 本批次**删除** `Packetizer`/`PacketizerFactory`/`TransportKind` 等 RTP 契约，不在本计划冻结其签名；RTP 批次再定义。
- 全过程禁止 `adb`、emulator、虚拟机、实机访问与 `connectedAndroidTest`；只允许本地 JVM/Gradle 单元测试。
- 端到端测试只使用 fake codec、fake transport、fake RenderTarget 与 JVM；任何测试不得触发 Android 设备 API 或真实网络。
- 核心生产代码禁止 `android.util.Log`、`android.os.*`、`android.graphics.*`、`android.media.*`、`System.out` 与日志框架；只允许 `android.view.Surface` 出现在 `Encoder.getInputSurface()` 与 `RenderTarget.getSurface()` 的签名中，允许 `androidx.annotation.Nullable` 注解。
- 核心生产代码避免依赖不确定的 Java 9+ 便捷 API：不使用 `List.of`、`Set.of`、`Map.of`、`List.copyOf`、`Set.copyOf`、`String.isBlank`、`Optional.isEmpty`、`Stream.toList`、`java.time`；集合使用 `ArrayList`/`LinkedHashSet`/`Collections.unmodifiable*`，时间使用 `System.currentTimeMillis()` 或注入的 `TimeSource`。
- Pro（DeepSeek v4 Pro）只写 `core` 生产代码，不编译、不运行 Gradle、不修改测试、不提交。
- 测试代码、fixtures、fakes、集成验证、编译、测试运行、文档与提交由当前侧 agent 完成。
- 文档角色只用“服务端/客户端”描述产品侧；执行角色使用“测试 agent / Pro / 验证 agent / 审查 agent / 集成 agent”。示意图如需使用 Mermaid。
- 文档不得包含本机绝对路径、用户名、账号、令牌、密钥、签名口令或任何敏感材料；只写仓库相对路径。
- 本批次范围是“隔离核心框架 + JPEG 参考适配 + fake/JVM 测试”。JPEG 只验证独立帧语义；参考帧语义由 fake contract tests 验证，**不等于真实 H.264 已验证**。H.264/MediaCodec/RTP/多流仍不在范围内。

## Review Focus

以下问题必须在对应 Task 的测试中出现，不能靠注释或“将来会做”代替：

1. **空配置描述符**：JPEG 没有 `CodecConfigPart` 时仍可构建、计算 hash、下发和进入 `STREAMING`。Task 1、3、8。
2. **未知 codec / 空 codecId**：注册表查找显式抛 `CodecNotFoundException`，空/空白 `codecId` 在构造时拒绝，不静默回退。Task 1、2、3。
3. **epoch 与 sequence**：每个 AU 自带 `configEpoch`；旧 epoch 的**普通 AU**也被新会话拒绝；`sequence` 在 epoch 内递增、新 epoch 归零；重复/过期描述符与跳号 epoch 显式处理。Task 1、5、6、7、8、9。
4. **描述符先行**：同一客户端同一 epoch，`sendDescriptor` 必须先于首个 `sendAccessUnit`；切换时先下发新描述符或缓冲新 AU，禁止首帧先于描述符。Task 5、7、9。
5. **队列策略分流**：JPEG `KEEP_LATEST_FRAME` 溢出覆盖旧帧并继续，不进入无意义 `RESYNCING`；参考帧 `DROP_UNTIL_JOIN_POINT` 在溢出或过期破坏可解码性时清空并进入 `RESYNCING`，直到下一个 joinable 点。Task 4、5、7、9。
6. **null/空 payload**：`EncodedAccessUnit` 拒绝 null 与零长度 payload；空 JPEG、截断 JPEG 不产生 AU；API 对 null 参数显式失败。Task 1、3、4、5。
7. **并发与慢发送**：Hub 广播使用快照；`drainOne` 锁内 poll、锁外 send；慢 transport 不阻塞 `offer`；detach 幂等，失败后 Hub 能移除/替换。Task 5、7、9。
8. **RenderTarget 与 Surface 路径**：解码器不得把 AU 当解码帧；MediaCodec 将来通过 RenderTarget 的 Surface 输出，JPEG 通过 `renderImage`；错误只走 `DecoderException`。Task 2、3、8。
9. **通用 fallback**：Hub 不得硬编码 JPEG；使用 `CodecRegistration.fallbackPriority` 与保存的 `ClientCapabilities` 选择显式回退；`ClientSessionKey` 含 `streamId`/`clientId`。Task 7、9。
10. **核心代码可 JVM 化**：核心生产代码不得依赖 Android 日志/OS/图形/媒体实现，不得依赖未确认的 Java 9+ API。Task 2–8 审查步骤。

## 执行分工与交接

| 角色 | 负责 | 禁止 |
| --- | --- | --- |
| 测试 agent | 先写失败测试、fixtures、fake codec / fake transport / fake render target / 可控时钟；运行红测并记录失败原因 | 不写 `core` 生产实现；不改生产代码来“让测试通过” |
| Pro（DeepSeek v4 Pro） | 严格按每个 Task 的 Interfaces 与文件清单编写生产代码；只实现核心契约与逻辑 | 不编译、不运行 Gradle、不修改测试、不提交、不扩展契约 |
| 验证 agent | 编译、运行指定测试、修复仅限导入/拼写/签名接线问题；汇报失败断言 | 不改测试断言、不改变语义、不新增依赖 |
| 审查 agent | 对照 spec 检查职责边界、公共层是否解析负载、epoch/描述符顺序/队列/并发边界；检查无 codec 特判、无 Android 实现依赖 | 不为通过审查而扩大范围；不跳过失败测试 |
| 集成 agent | 最终全量 JVM 测试、`assembleDebug`、旧路径 diff 检查、任务看板更新、文档与提交/推送 | 不执行 adb/设备/仪器测试；不改旧路径行为 |

**Handoff 规则：**

- 每个 Task 都由测试 agent 先提交失败测试；Pro 只读取测试与 Interfaces，写生产代码。
- Pro 交付后，验证 agent 运行命令。编译类错误可由验证 agent 做最小接线修复；断言失败或契约不一致必须退回 Pro，附失败测试名与实际输出。
- 审查 agent 通过前不得提交该 Task。
- 每个 Task 的提交由验证 agent 或集成 agent 执行，提交信息见各 Task 最后一步。
- 任何 Task 的语义失败由 Pro 修复后，验证 agent 必须重新跑该 Task 的红/绿测试；若修复发生在已提交 Task 之后，修复提交使用 `fix: ...`，不得把生产修复混入 E2E 测试提交。
- 端到端测试若发现核心契约缺陷，先回到 owning Task 修复并提交 `fix: ...`，再提交 E2E 测试；不得在 E2E 测试里绕过契约。
- 若 Pro 的实现需要修改测试、proto、`LssService`/`LssClientService`、`LssServer` 或新增依赖，停止并退回计划审查。

## 文件结构

新增生产代码（全部在 `app/src/main/java/com/obby/android/localscreenshare/` 下）：

```text
core/
├── model/
│   ├── StreamKey.java
│   ├── ClientSessionKey.java
│   ├── CodecConfigPart.java
│   ├── ColorInfo.java
│   ├── BitstreamFormat.java
│   ├── StreamDescriptor.java
│   ├── StreamParameters.java
│   ├── AccessUnitFlags.java
│   ├── JoinInfo.java
│   ├── EncodedAccessUnit.java
│   └── ClientState.java
├── render/
│   ├── RenderTarget.java
│   └── DecodedImage.java
├── codec/
│   ├── CodecIds.java
│   ├── CodecDescriptor.java
│   ├── CodecRegistration.java
│   ├── CodecRegistry.java
│   ├── CodecNotFoundException.java
│   ├── ConfigEpochException.java
│   ├── CodecSwitchException.java
│   ├── DecoderException.java
│   ├── Encoder.java
│   ├── EncoderFactory.java
│   ├── EncoderOutput.java
│   ├── Decoder.java
│   ├── DecoderFactory.java
│   ├── CodecPolicy.java
│   ├── OverflowAction.java
│   └── jpeg/
│       ├── JpegCodec.java
│       ├── JpegFrameValidator.java
│       ├── JpegCodecPolicy.java
│       ├── JpegEncoder.java
│       ├── JpegEncoderFactory.java
│       ├── JpegDecoder.java
│       └── JpegDecoderFactory.java
├── stream/
│   ├── TimeSource.java
│   ├── StreamQueueLimits.java
│   ├── QueueOfferResult.java
│   ├── QueuePollResult.java
│   ├── BoundedAccessUnitQueue.java
│   ├── ClientSendStats.java
│   ├── MediaTransport.java
│   ├── ClientSendSession.java
│   ├── StreamRuntime.java
│   ├── StreamRuntimeListener.java
│   ├── ClientCapabilities.java
│   ├── JoinDecision.java
│   └── StreamHub.java
└── client/
    ├── ClientMediaSession.java
    └── ClientStateListener.java
```

测试代码在 `app/src/test/java/com/obby/android/localscreenshare/core/` 下：

```text
core/model/StreamDescriptorTest.java
core/model/EncodedAccessUnitTest.java
core/model/ClientSessionKeyTest.java
core/codec/CodecRegistryTest.java
core/codec/FakeCodecContractTest.java
core/codec/jpeg/JpegCodecTest.java
core/stream/BoundedAccessUnitQueueTest.java
core/stream/ClientSendSessionTest.java
core/stream/StreamRuntimeTest.java
core/stream/StreamHubTest.java
core/client/ClientMediaSessionTest.java
core/stream/StreamFrameworkEndToEndTest.java
core/support/FakeCodec.java
core/support/FakeRenderTarget.java
core/support/FakeTransport.java
core/support/BlockingTransport.java
core/support/MutableClock.java
core/support/JpegFixtures.java
core/support/CapturingJpegEncoderFactory.java
```

JPEG fixture 使用测试常量，不引入二进制资源文件。

## Task 1: 核心数据契约

**Files:**
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/model/StreamKey.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/model/ClientSessionKey.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/model/CodecConfigPart.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/model/ColorInfo.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/model/BitstreamFormat.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/model/StreamDescriptor.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/model/StreamParameters.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/model/AccessUnitFlags.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/model/JoinInfo.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/model/EncodedAccessUnit.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/model/ClientState.java`
- Test: `app/src/test/java/com/obby/android/localscreenshare/core/model/StreamDescriptorTest.java`
- Test: `app/src/test/java/com/obby/android/localscreenshare/core/model/EncodedAccessUnitTest.java`
- Test: `app/src/test/java/com/obby/android/localscreenshare/core/model/ClientSessionKeyTest.java`

**Interfaces:**
- Consumes: 无。
- Produces:
  - `StreamKey(String sessionInstanceId, String streamId)`；`getSessionInstanceId()`、`getStreamId()`、`getValue()`；非空校验、`equals`/`hashCode`。
  - `ClientSessionKey(StreamKey streamKey, String clientId)`；`getStreamKey()`、`getClientId()`；非空校验、`equals`/`hashCode`；key 同时包含 `streamId` 与 `clientId`，为未来多流保留。
  - `CodecConfigPart(String id, byte[] payload)`；`getId()`、`getPayload()`（防御性复制）；payload 非 null 且长度大于 0。
  - `ColorInfo(@Nullable String colorSpace, @Nullable String transferFunction, boolean fullRange)`；`getColorSpace()`、`getTransferFunction()`、`isFullRange()`、`ColorInfo.UNSPECIFIED`。
  - `BitstreamFormat` 枚举：`JPEG_FRAME`、`ANNEX_B`、`AVCC`。
  - `StreamDescriptor` 不可变；字段 `streamKey`、`configEpoch`、`codecId`、`mime`、`profile`、`level`、`codedWidth`、`codedHeight`、`visibleWidth`、`visibleHeight`、`rotationDegrees`、`bitstreamFormat`、`configParts`、`colorInfo`、`configHash`。
  - `StreamDescriptor.builder(StreamKey, int configEpoch, String codecId, String mime, BitstreamFormat)`；builder 方法 `profile`、`level`、`codedSize(int,int)`、`visibleSize(int,int)`、`rotationDegrees(int)`、`configParts(List<CodecConfigPart>)`、`colorInfo(ColorInfo)`、`build()`。缺省值：`profile/level` 为 null、`visibleSize` 等于 coded size、`rotationDegrees=0`、`configParts` 为空列表、`colorInfo=ColorInfo.UNSPECIFIED`。
  - `StreamDescriptor` getters：`getStreamKey()`、`getConfigEpoch()`、`getCodecId()`、`getMime()`、`getProfile()`、`getLevel()`、`getCodedWidth()`、`getCodedHeight()`、`getVisibleWidth()`、`getVisibleHeight()`、`getRotationDegrees()`、`getBitstreamFormat()`、`getConfigParts()`、`getColorInfo()`、`getConfigHash()`、`sameDecodeConfig(StreamDescriptor)`、`withConfigEpoch(int)`。
  - `StreamParameters(int targetBitrateBps, int frameRateHint, int keyFrameIntervalMs)`；getters；`StreamParameters.DEFAULT`；`withTargetBitrate`、`withFrameRateHint`、`withKeyFrameInterval`。
  - `AccessUnitFlags.of(boolean syncPoint, boolean joinPoint, boolean config)`、`AccessUnitFlags.none()`；`isSyncPoint()`、`isJoinPoint()`、`isConfig()`。
  - `JoinInfo(int configEpoch, List<CodecConfigPart> requiredConfigParts)`；`getConfigEpoch()`、`getRequiredConfigParts()`；`requiredConfigParts` 可为空。
  - `EncodedAccessUnit(StreamKey, int configEpoch, long sequence, long ptsUs, long dtsUs, AccessUnitFlags, @Nullable JoinInfo, @Nullable Integer temporalLayerId, byte[] payload)`；getters 含 `getConfigEpoch()`、`getDtsUs()`、`hasDtsUs()`；`DTS_UNSET = Long.MIN_VALUE`；`getPayloadLength()`；payload 非 null 且长度大于 0；`configEpoch >= 1`、`sequence >= 0`、`ptsUs >= 0`、`dtsUs` 为 `DTS_UNSET` 或非负；joinPoint 为 true 时 `joinInfo` 非 null 且 `joinInfo.getConfigEpoch() == configEpoch`。**`sequence` 在每个 `(streamKey, configEpoch)` 内从 0 递增；新 epoch 的 encoder 重新从 0 开始。**
  - `ClientState` 枚举：`NEGOTIATING`、`WAIT_CONFIG`、`WAIT_RANDOM_ACCESS`、`STREAMING`、`RESYNCING`、`CLOSED`。

- [ ] **Step 1（测试 agent）：写失败测试**

`StreamDescriptorTest` 至少包含：

- `configHash_changesWhenCodecOrMimeChanges`：仅 `codecId` 或 `mime` 不同，`assertNotEquals`。
- `configHash_changesWhenConfigPartPayloadChanges`：仅配置部件字节不同，hash 不同。
- `configHash_changesWhenVisibleSizeOrRotationChanges`：仅 `visibleWidth/visibleHeight` 或旋转变化，hash 不同；`sameDecodeConfig` 为 false。本批次按主 agent 裁决把 visible size 视为新 descriptor/epoch 的一部分。
- `configHash_unchangedWhenOnlyEpochChanges`：仅 `configEpoch` 变化，hash 相同；`withConfigEpoch(2)` 保持 hash。
- `builder_rejectsInvalidRotationAndNonPositiveCodedSize`：旋转 45 度、codedWidth 0 均抛 `IllegalArgumentException`。
- `emptyConfigParts_areAllowedForJpegDescriptor`：JPEG 描述符不传配置部件，构建成功且 `getConfigParts().isEmpty()`。
- `configPartsAndPayload_areDefensivelyCopied`：修改传入/取出的数组不改变对象内部值。

`EncodedAccessUnitTest` 至少包含：

- `constructor_rejectsNullPayload`、`constructor_rejectsEmptyPayload`：均抛 `IllegalArgumentException`。
- `constructor_requiresJoinInfoWhenJoinPoint`：join flag 且 `joinInfo == null` 抛异常。
- `constructor_rejectsJoinInfoEpochMismatch`：AU epoch 1、JoinInfo epoch 2 抛 `IllegalArgumentException`。
- `constructor_rejectsNegativeSequencePtsOrDts`：分别传入负值均抛异常。
- `configEpoch_isAlwaysReadable`：普通 AU 也返回非零 `getConfigEpoch()`。
- `sequence_isNotGloballyUniqueAcrossEpochs`：epoch 1 与 epoch 2 的 AU 都可用 sequence 0 构造；测试断言字段语义，不把 sequence 当全局唯一。
- `payload_isDefensivelyCopied`。
- `dtsUnset_isReported`、`clientState_hasExactlySixStates`。

`ClientSessionKeyTest` 至少包含：

- `equalsHashCode_includeStreamAndClient`：相同 streamId/clientId 相等；任一不同则不等。
- `constructor_rejectsNullAndBlankClientId`。

- [ ] **Step 2（测试 agent）：运行红测**

Run: `./gradlew :app:testDebugUnitTest --tests "com.obby.android.localscreenshare.core.model.*" --offline --console=plain`

Expected: 编译失败，找不到模型类；记录该输出为红测证据。

- [ ] **Step 3（Pro）：只写生产实现**

实现上述模型类。`configHash` 使用 `java.security.MessageDigest` 的 SHA-256，对 `codecId`、`mime`、`profile`、`level`、coded size、**visible size**、rotation、`bitstreamFormat`、按顺序排列的 `CodecConfigPart`（id + payload hex）以及 `colorInfo` 做规范化拼接；不包含 `configEpoch`。所有集合与字节数组做防御性复制，不使用 Java 9+ 集合工厂。Pro 不运行 Gradle。

- [ ] **Step 4（验证 agent）：编译并跑绿测**

Run: `./gradlew :app:testDebugUnitTest --tests "com.obby.android.localscreenshare.core.model.*" --offline --console=plain`

Expected: `BUILD SUCCESSFUL`，三个测试类全部通过。仅导入/签名接线错误可最小修复；语义失败退回 Pro。

- [ ] **Step 5（审查 agent）：契约审查**

检查：描述符不持有 Surface/线程/编解码器；visible size 已进入 hash；AU 每个实例都有 epoch；sequence 语义是 epoch 内；payload/config part 不可变；无 Android 日志/OS 依赖。通过后提交。

- [ ] **Step 6（验证 agent）：提交**

```bash
git add app/src/main/java/com/obby/android/localscreenshare/core/model \
        app/src/test/java/com/obby/android/localscreenshare/core/model
git commit -m "feat: 添加编码核心数据契约"
```

## Task 2: RenderTarget、Codec SPI 与注册表

**Files:**
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/render/RenderTarget.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/render/DecodedImage.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/CodecIds.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/CodecDescriptor.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/CodecRegistration.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/CodecRegistry.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/CodecNotFoundException.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/ConfigEpochException.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/CodecSwitchException.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/DecoderException.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/Encoder.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/EncoderFactory.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/EncoderOutput.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/Decoder.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/DecoderFactory.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/CodecPolicy.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/OverflowAction.java`
- Test: `app/src/test/java/com/obby/android/localscreenshare/core/codec/CodecRegistryTest.java`
- Test: `app/src/test/java/com/obby/android/localscreenshare/core/codec/FakeCodecContractTest.java`
- Test support: `app/src/test/java/com/obby/android/localscreenshare/core/support/FakeCodec.java`
- Test support: `app/src/test/java/com/obby/android/localscreenshare/core/support/FakeRenderTarget.java`

**Interfaces:**
- Consumes: Task 1 的模型类型；`android.view.Surface`（仅签名，JVM 测试传 null/不调用）。
- Produces:
  - `RenderTarget`：`@Nullable Surface getSurface()`、`void renderImage(DecodedImage image)`。JPEG 使用 `renderImage`；未来 MediaCodec 使用 `getSurface()`。**不提供第二个错误回调。**
  - `DecodedImage(byte[] payload, long ptsUs, int width, int height)`；`getPayload()`（防御性复制）、`getPayloadLength()`、`getPtsUs()`、`getWidth()`、`getHeight()`。
  - `CodecIds`：`JPEG="jpeg"`、`H264="h264"`、`HEVC="hevc"`、`AV1="av1"`。本批次只注册 JPEG；其余为契约保留常量。
  - `CodecDescriptor(String codecId, String mime, BitstreamFormat bitstreamFormat, boolean surfaceInputRequired, boolean surfaceOutputRequired, boolean carriesConfigParts)`；getters `isSurfaceInputRequired()`、`isSurfaceOutputRequired()`、`hasConfigParts()`；codecId/mime 非空。
  - `CodecRegistration(CodecDescriptor descriptor, EncoderFactory encoderFactory, DecoderFactory decoderFactory, CodecPolicy policy, int fallbackPriority)`；getters；`fallbackPriority >= 0`，**数值越小越优先**；不含 Packetizer。
  - `CodecRegistry()`；`register(CodecRegistration)`（重复 codecId 抛 `IllegalStateException`）、`contains(String)`、`require(String)`（未知抛 `CodecNotFoundException`）、`getRegistrations()`（不可变列表）、`getCodecIds()`（不可变集合）。
  - `CodecNotFoundException extends RuntimeException`，getter `getCodecId()`。
  - `ConfigEpochException extends IllegalArgumentException`，构造 `(int expected, int actual)`，getters。
  - `CodecSwitchException extends RuntimeException`，构造 `(String message, Throwable cause)`。
  - `DecoderException extends RuntimeException`，构造 `(String message, Throwable cause)`；解码/渲染失败的唯一错误通道。
  - `Encoder`：`start()`、`stop()`（停止输出但保留资源）、`@Nullable Surface getInputSurface()`、`updateParameters(StreamParameters)`、`requestSyncPoint()`、`close()`（释放资源）。JPEG 返回 null；未来 MediaCodec 返回输入 Surface。
  - `EncoderFactory`：`Encoder create(StreamKey streamKey, StreamDescriptor descriptor, StreamParameters parameters, EncoderOutput output)`。**不再有 Surface 参数**；输入 Surface 由 `Encoder.getInputSurface()` 暴露。
  - `EncoderOutput`：`void onAccessUnit(EncodedAccessUnit accessUnit)`。
  - `Decoder`：`configure(StreamDescriptor descriptor)`、`decode(EncodedAccessUnit accessUnit) throws DecoderException`、`close()`。
  - `DecoderFactory`：`Decoder create(StreamKey streamKey, StreamDescriptor descriptor, RenderTarget renderTarget)`。**DecoderFactory 显式接收 RenderTarget**，不再有 `DecoderOutput`。
  - `CodecPolicy`：`OverflowAction getOverflowAction()`、`boolean isJoinPoint(EncodedAccessUnit accessUnit)`、`boolean requiresReconfigure(StreamDescriptor previous, StreamDescriptor next)`。
  - `OverflowAction` 枚举：`KEEP_LATEST_FRAME`、`DROP_UNTIL_JOIN_POINT`。

**测试支持（本 Task 创建，后续复用）：**
- `FakeRenderTarget implements RenderTarget`：`getSurface()` 返回 null；`renderImage` 把 `DecodedImage` 加入 `images`；提供 `getImages()`。
- `FakeCodec`：构造 `(String codecId, int fallbackPriority, boolean surfaceInputRequired, boolean surfaceOutputRequired)`；默认 `fake`/`video/fake`/`ANNEX_B`、无配置部件、`DROP_UNTIL_JOIN_POINT`；`registration()` 返回 `CodecRegistration`；`getLastEncoder()`、`getLastDecoder()`、`setAutoEmitJoinOnRequest(boolean)`、`getRequestCount()`、`emitNonJoin(byte[])`、`emitJoin(byte[])`、`emit(int configEpoch, boolean joinPoint, byte[])`。
- `FakeEncoder implements Encoder`：`start()`、`stop()`、`getInputSurface()` 返回 null、`updateParameters`、`requestSyncPoint()` 递增 `requestCount`，默认自动 `emitJoin`，可关闭自动发射；`close()`；`emit(...)` 用当前 descriptor epoch 与 epoch 内 sequence 构造 AU；记录 `stopped`、`closed`。
- `FakeDecoder implements Decoder`：`configure` 保存 descriptor；`decode` 记录 AU，并通过构造时传入的 `RenderTarget.renderImage` 输出 `DecodedImage`；`failNextDecode` 可让下一次 decode 抛 `DecoderException`；`close` 记录。

- [ ] **Step 1（测试 agent）：写失败测试**

`CodecRegistryTest` 至少包含：

- `newRegistry_hasNoCodecs`。
- `register_rejectsDuplicateCodecId`。
- `register_rejectsBlankCodecId`。
- `require_unknownCodecThrowsWithCodecId`：异常消息包含未知 codecId。
- `getCodecIds_isUnmodifiable`。
- `getRegistrations_preservesFallbackPriorityMetadata`：注册两个 fake，断言 getter 与顺序/内容。
- `concurrentLookups_doNotThrow`：8 个线程重复 `require("fake")`。

`FakeCodecContractTest` 至少包含：

- `fakeRenderTarget_surfaceIsNull_andCapturesImages`：`getSurface()==null`；`renderImage(new DecodedImage(...))` 后 `images.size()==1`。
- `fakeEncoder_getInputSurfaceIsNull`：非 Surface codec 的输入 Surface 为 null，便于 JVM 测试。
- `decoderFactory_receivesRenderTarget`：`FakeDecoder` 在 `decode` 时把 `DecodedImage` 送到同一个 `FakeRenderTarget`。
- `decoder_decodeFailureUsesDecoderException`：`failNextDecode` 后 `decode` 抛 `DecoderException`。
- `fallbackPriority_isMetadataOnly`：注册表本身不按 codec 名称排序；Hub 将在 Task 7 使用 priority。

- [ ] **Step 2（测试 agent）：运行红测**

Run: `./gradlew :app:testDebugUnitTest --tests "com.obby.android.localscreenshare.core.codec.*" --offline --console=plain`

Expected: 编译失败，找不到 `CodecRegistry`、`RenderTarget` 等。

- [ ] **Step 3（Pro）：只写生产实现**

实现 RenderTarget/DecodedImage、SPI 与注册表。注册表使用 `ConcurrentHashMap`；`getRegistrations()` 返回不可变副本；未知 codec 显式失败；`getInputSurface` 可以为 null；`DecoderFactory` 必须接收 `RenderTarget`；错误只通过 `DecoderException`。不引入 Packetizer/TransportKind，不引入 Android 实现。Pro 不运行 Gradle。

- [ ] **Step 4（验证 agent）：跑绿测**

Run: `./gradlew :app:testDebugUnitTest --tests "com.obby.android.localscreenshare.core.codec.*" --offline --console=plain`

Expected: `BUILD SUCCESSFUL`，两个测试类通过。

- [ ] **Step 5（审查 agent）：SPI 审查**

检查：无 Packetizer/RTP 契约；无 `android.util.Log`/`android.os`/`android.media`；Decoder 不接收 AU 当帧；`DecoderFactory` 显式接收 RenderTarget；注册表无动态发现；fallbackPriority 只是元数据，没有 codec 名称分支。通过后提交。

- [ ] **Step 6（验证 agent）：提交**

```bash
git add app/src/main/java/com/obby/android/localscreenshare/core/render \
        app/src/main/java/com/obby/android/localscreenshare/core/codec \
        app/src/test/java/com/obby/android/localscreenshare/core/codec \
        app/src/test/java/com/obby/android/localscreenshare/core/support/FakeCodec.java \
        app/src/test/java/com/obby/android/localscreenshare/core/support/FakeRenderTarget.java
git commit -m "feat: 添加 RenderTarget 与静态 Codec SPI"
```

## Task 3: JPEG 参考适配

**Files:**
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/jpeg/JpegCodec.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/jpeg/JpegFrameValidator.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/jpeg/JpegCodecPolicy.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/jpeg/JpegEncoder.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/jpeg/JpegEncoderFactory.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/jpeg/JpegDecoder.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/jpeg/JpegDecoderFactory.java`
- Modify: `app/src/main/java/com/obby/android/localscreenshare/core/codec/CodecRegistry.java`（新增 `withDefaults()`）
- Test: `app/src/test/java/com/obby/android/localscreenshare/core/codec/jpeg/JpegCodecTest.java`
- Test support: `app/src/test/java/com/obby/android/localscreenshare/core/support/JpegFixtures.java`

**Interfaces:**
- Consumes: Task 1 模型；Task 2 的 `CodecRegistration`、`CodecRegistry`、`RenderTarget`、`DecodedImage`、`DecoderException`。
- Produces:
  - `JpegCodec.MIME = "image/jpeg"`；`JpegCodec.registration()`：codecId `CodecIds.JPEG`、`JPEG_FRAME`、`surfaceInputRequired=false`、`surfaceOutputRequired=false`、`carriesConfigParts=false`、`fallbackPriority=100`、policy/encoder/decoder 为 JPEG 实现。
  - `CodecRegistry.withDefaults()`：只注册 JPEG，返回新 registry。
  - `JpegFrameValidator.isCompleteFrame(@Nullable byte[] payload)`：非 null、长度至少 4、以 `FF D8` 开头、以 `FF D9` 结尾。
  - `JpegCodecPolicy`：`KEEP_LATEST_FRAME`；`isJoinPoint` 要求 flag 与完整 payload；`requiresReconfigure` 使用 `sameDecodeConfig`。
  - `JpegEncoder(StreamKey, StreamDescriptor, StreamParameters, EncoderOutput)`；`start`、`stop`、`getInputSurface()` 返回 null、`updateParameters`、`requestSyncPoint()` no-op、`close`、`boolean offerJpegFrame(long ptsUs, byte[] jpegBytes)`。
  - `JpegEncoderFactory.create(...)`：无 Surface 参数；descriptor codecId 非 `jpeg` 抛 `IllegalArgumentException`。
  - `JpegDecoder(StreamKey, StreamDescriptor, RenderTarget)`；`configure`、`decode`（渲染 `DecodedImage`）、`close`。
  - `JpegDecoderFactory.create(StreamKey, StreamDescriptor, RenderTarget)`。

**本批次适配器边界：** JPEG 接收现有采集路径已压缩好的完整帧，不接触 `Bitmap`/`ImageReader`；像素获取迁移需要改 `LssService`，超出本批次。JPEG 的每帧独立语义通过 `KEEP_LATEST_FRAME` 与 `renderImage` 验证；参考帧语义由 fake contract tests 验证，不等于真实 H.264 已验证。

- [ ] **Step 1（测试 agent）：写 fixtures 与失败测试**

`JpegFixtures`：`VALID_FRAME = {FF D8 FF D9}`、`TRUNCATED_FRAME = {FF D8 00}`、`EMPTY_FRAME`、`validFrameWithPadding()`。

`JpegCodecTest` 至少包含：

- `registration_hasJpegDescriptorAndPolicy`：surface input/output 均为 false，fallbackPriority 100。
- `registryWithDefaults_registersJpegOnly`。
- `validator_acceptsOnlyCompleteFrames`。
- `encoder_emitsJoinPointAuWithEpochAndZeroSequence`：AU `getConfigEpoch()` 等于描述符 epoch、sequence 0、`hasDtsUs()` false、join flag、payload 正确。
- `encoder_sequenceResetsPerEncoder`：新建第二个 encoder（模拟新 epoch）后 sequence 从 0 开始。
- `encoder_dropsTruncatedAndEmptyFrames`。
- `encoder_afterStopOrClose_dropsFrames`。
- `encoder_getInputSurfaceIsNull`。
- `decoder_rendersDecodedImageToTarget`：`configure` + `decode` 后 `FakeRenderTarget.getImages()` 有 1 个，尺寸为描述符 visible size，payload 正确。
- `decoder_rejectsMismatchedEpoch`：AU epoch 与 descriptor epoch 不同，抛 `DecoderException`。
- `decoder_beforeConfigureThrowsDecoderException`。

- [ ] **Step 2（测试 agent）：运行红测**

Run: `./gradlew :app:testDebugUnitTest --tests "com.obby.android.localscreenshare.core.codec.jpeg.JpegCodecTest" --offline --console=plain`

Expected: 编译失败，找不到 JPEG 类。

- [ ] **Step 3（Pro）：只写生产实现**

实现 JPEG 适配。`JpegEncoder` 只接受完整帧，`sequence` 从 0 开始，AU 带 descriptor epoch 与 JoinInfo；`JpegDecoder` 只通过 `RenderTarget.renderImage` 输出，任何渲染异常包装为 `DecoderException`。`CodecRegistry.withDefaults()` 只注册 JPEG。Pro 不运行 Gradle。

- [ ] **Step 4（验证 agent）：跑绿测**

Run: `./gradlew :app:testDebugUnitTest --tests "com.obby.android.localscreenshare.core.codec.jpeg.JpegCodecTest" --offline --console=plain`

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 5（审查 agent）：JPEG 边界审查**

检查：公共层没有 JPEG 解析；空配置可用；截断/空帧不产生 AU；DecoderFactory 接收 RenderTarget；没有把 AU 当解码帧；没有 Android Bitmap/ImageReader/Log 依赖。通过后提交。

- [ ] **Step 6（验证 agent）：提交**

```bash
git add app/src/main/java/com/obby/android/localscreenshare/core/codec/jpeg \
        app/src/main/java/com/obby/android/localscreenshare/core/codec/CodecRegistry.java \
        app/src/test/java/com/obby/android/localscreenshare/core/codec/jpeg \
        app/src/test/java/com/obby/android/localscreenshare/core/support/JpegFixtures.java
git commit -m "feat: 添加 JPEG 参考适配"
```

## Task 4: 有界访问单元队列

**Files:**
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/stream/TimeSource.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/stream/StreamQueueLimits.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/stream/QueueOfferResult.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/stream/QueuePollResult.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/stream/BoundedAccessUnitQueue.java`
- Test: `app/src/test/java/com/obby/android/localscreenshare/core/stream/BoundedAccessUnitQueueTest.java`
- Test support: `app/src/test/java/com/obby/android/localscreenshare/core/support/MutableClock.java`

**Interfaces:**
- Consumes: Task 1 的 `EncodedAccessUnit`、`AccessUnitFlags`；Task 2 的 `OverflowAction`。
- Produces:
  - `TimeSource`：`long nowMs()`；测试用 `MutableClock implements TimeSource`（`advance(long)`、`set(long)`）。
  - `StreamQueueLimits(long maxBytes, int maxUnits, long maxAgeMs)`；getters；`StreamQueueLimits.defaults()` 使用 4 MiB、120 个 AU、2000 ms 的临时值（注释说明需实测，不是生产结论）。
  - `QueueOfferResult` 枚举：`ACCEPTED`、`ACCEPTED_KEPT_LATEST`、`ACCEPTED_AFTER_RESYNC`、`REJECTED_NOT_JOIN_POINT`、`REJECTED_TOO_LARGE`、`REJECTED_NOT_READY`、`REJECTED_EPOCH`、`REJECTED_CLOSED`。后三个由 `ClientSendSession` 返回，队列本身只产生前五个。
  - `QueuePollResult`：`@Nullable EncodedAccessUnit getAccessUnit()`、`boolean isResyncRequired()`、静态 `frame(EncodedAccessUnit)`、`resync()`、`empty()`。
  - `BoundedAccessUnitQueue(StreamQueueLimits limits, OverflowAction action)`；`offer(EncodedAccessUnit, long nowMs)`、`poll(long nowMs)`、`size()`、`bytes()`、`droppedUnits()`、`droppedBytes()`、`clear()`、`isResyncRequired()`。

**队列语义（必须由测试固定）：**
- 同时受字节数、单元数和驻留时间约束；单个 AU 超过 `maxBytes` 时返回 `REJECTED_TOO_LARGE`。
- `KEEP_LATEST_FRAME`（JPEG）：
  - 溢出时清空旧帧，保留最新完整帧，返回 `ACCEPTED_KEPT_LATEST`；**不设置 resync**。
  - 过期时丢弃过期帧，继续返回其余最新帧；**不设置 resync**。
  - 若最新帧本身超限，清空并返回 `REJECTED_TOO_LARGE`，仍不设置 resync。
- `DROP_UNTIL_JOIN_POINT`（参考帧）：
  - 溢出时清空队列并设置 resync；来者是 join point 则保留并返回 `ACCEPTED_AFTER_RESYNC`，否则返回 `REJECTED_NOT_JOIN_POINT`。
  - 任一条目过期即视为可解码性被破坏：清空队列、设置 resync，`poll` 返回 `QueuePollResult.resync()`。
  - resync 后只有 join point 能重新进入队列；`poll` 返回该 join point 时 `isResyncRequired()` 为 false，由会话在发送成功后进入 `STREAMING`。
- `clear()` 清空队列但不重置丢弃统计；队列的 resync 标志由 `clear()` 重置为 false（新 epoch 会创建新队列）。

- [ ] **Step 1（测试 agent）：写失败测试**

`BoundedAccessUnitQueueTest` 至少包含：

- `offer_rejectsNull`。
- `tooLargeAu_isRejected`：单个 payload 超限，`size()==0`。
- `keepLatestOverflow_keepsNewestWithoutResync`：limits 1 单元；先入 join，再入 join；结果 `ACCEPTED_KEPT_LATEST`、size 1、`isResyncRequired()` false。
- `keepLatestExpiry_dropsOldWithoutResync`：两个帧，推进超过 maxAge，`poll` 丢弃过期并返回最新，`isResyncRequired()` false。
- `referenceOverflow_nonJoinClearsAndRequiresResync`：action `DROP_UNTIL_JOIN_POINT`；溢出后非 join 返回 `REJECTED_NOT_JOIN_POINT`、size 0、`isResyncRequired()` true。
- `referenceOverflow_joinAcceptedAfterResync`：随后 join 返回 `ACCEPTED_AFTER_RESYNC`；`poll` 返回该 join 且 resync false。
- `referenceExpiry_clearsAndRequiresResync`：队列内有 join+非 join，推进过期，`poll` 返回 `resync()`。
- `poll_returnsFifoAndDecrementsBytes`。
- `clear_resetsSizeAndResyncFlagButKeepsDroppedCounters`。

- [ ] **Step 2（测试 agent）：运行红测**

Run: `./gradlew :app:testDebugUnitTest --tests "com.obby.android.localscreenshare.core.stream.BoundedAccessUnitQueueTest" --offline --console=plain`

Expected: 编译失败。

- [ ] **Step 3（Pro）：只写生产实现**

实现队列与 `QueuePollResult`。队列用 `ArrayDeque` 保存条目与入队时间；所有状态操作在单一锁内；不要引入 Android 或 Java 9+ 集合 API。Pro 不运行 Gradle。

- [ ] **Step 4（验证 agent）：跑绿测**

Run: `./gradlew :app:testDebugUnitTest --tests "com.obby.android.localscreenshare.core.stream.BoundedAccessUnitQueueTest" --offline --console=plain`

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 5（审查 agent）：队列审查**

检查：JPEG 溢出/过期不设置 resync；参考帧任何可解码性破坏都清空并 resync；JPEG 路径没有无意义的 `RESYNCING`；null/空 payload 不进入队列。通过后提交。

- [ ] **Step 6（验证 agent）：提交**

```bash
git add app/src/main/java/com/obby/android/localscreenshare/core/stream/TimeSource.java \
        app/src/main/java/com/obby/android/localscreenshare/core/stream/StreamQueueLimits.java \
        app/src/main/java/com/obby/android/localscreenshare/core/stream/QueueOfferResult.java \
        app/src/main/java/com/obby/android/localscreenshare/core/stream/QueuePollResult.java \
        app/src/main/java/com/obby/android/localscreenshare/core/stream/BoundedAccessUnitQueue.java \
        app/src/test/java/com/obby/android/localscreenshare/core/stream/BoundedAccessUnitQueueTest.java \
        app/src/test/java/com/obby/android/localscreenshare/core/support/MutableClock.java
git commit -m "feat: 添加有界访问单元队列"
```

## Task 5: 媒体传输与客户端发送会话

**Files:**
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/stream/ClientSendStats.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/stream/MediaTransport.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/stream/ClientSendSession.java`
- Test: `app/src/test/java/com/obby/android/localscreenshare/core/stream/ClientSendSessionTest.java`
- Test support: `app/src/test/java/com/obby/android/localscreenshare/core/support/FakeTransport.java`
- Test support: `app/src/test/java/com/obby/android/localscreenshare/core/support/BlockingTransport.java`

**Interfaces:**
- Consumes: Task 1 `ClientSessionKey`、`EncodedAccessUnit`、`StreamDescriptor`；Task 2 `CodecRegistry`、`CodecPolicy`；Task 3 JPEG；Task 4 队列/时钟/limits。
- Produces:
  - `MediaTransport`：`void sendDescriptor(StreamDescriptor descriptor) throws IOException`、`void sendAccessUnit(EncodedAccessUnit accessUnit) throws IOException`、`void close()`。**必须提供描述符下发能力。**
  - `ClientSendStats`：`getQueuedUnits()`、`getQueuedBytes()`、`getDroppedUnits()`、`getResyncCount()`、`getSentUnits()`、`getDescriptorSends()`。
  - `ClientSendSession(ClientSessionKey key, CodecRegistry registry, StreamQueueLimits limits, MediaTransport transport, TimeSource timeSource)`；`getKey()`、`getClientId()`、`getState()`、`getCurrentDescriptor()`、`boolean onDescriptor(StreamDescriptor)`、`QueueOfferResult offer(EncodedAccessUnit)`、`boolean drainOne() throws IOException`、`ClientSendStats getStats()`、`void detach()`、`boolean isDetached()`。
  - `FakeTransport implements MediaTransport`：有序事件列表 `events`（`DESCRIPTOR` / `ACCESS_UNIT`）、`failNextSend`、`isClosed()`；`sendDescriptor`/`sendAccessUnit` 记录事件。
  - `BlockingTransport extends FakeTransport`：`blockNextAccessUnitSend()` 提供 `sendStarted`/`releaseSend` 两个 latch，用于慢发送测试。

**会话语义（必须由测试固定）：**
- 初始 `WAIT_CONFIG`。`onDescriptor` 首次成功：保存描述符、创建新队列、设置 `pendingDescriptor`、进入 `WAIT_RANDOM_ACCESS`，返回 true。
- `onDescriptor` 旧 epoch 返回 false；同 epoch 同 hash 返回 false；同 epoch 不同 hash 抛 `IllegalArgumentException`；新 epoch：清空旧队列、创建新队列、设置 `pendingDescriptor`、进入 `WAIT_RANDOM_ACCESS`，返回 true。
- `offer`：detach -> `REJECTED_CLOSED`；null 抛 `IllegalArgumentException`；未收到 descriptor -> `REJECTED_NOT_READY`；AU 的 `streamKey` 或 `configEpoch` 与当前描述符不匹配 -> `REJECTED_EPOCH`，**普通 AU 也检查**；`WAIT_RANDOM_ACCESS`/`RESYNCING` 下非 join point -> `REJECTED_NOT_JOIN_POINT`。
- JPEG `ACCEPTED_KEPT_LATEST`：会话保持 `STREAMING`/当前状态，不增加 `resyncCount`。
- 参考帧溢出/过期：队列设置 resync，会话进入 `RESYNCING` 并增加 `resyncCount`；只有 join point 被发送后才回 `STREAMING`。
- `drainOne`：
  1. 用独立 `drainLock.tryLock()` 保证单 drainer；拿不到返回 false。
  2. 状态锁内取 `pendingDescriptor` 或 poll 队列（poll 在锁内），释放锁。
  3. 锁外 `sendDescriptor` 或 `sendAccessUnit`；描述符必须先于同 epoch 首个 AU。
  4. 发送成功后状态锁内更新统计/状态；若期间已 detach，则不再改状态/统计。join point 发送成功后才进入 `STREAMING`。
  5. 任何发送异常：状态锁内 detach（清空队列、关闭 transport），再抛出；Hub 在 Task 7 负责移除会话。
- 慢 send 不阻塞 `offer`：`offer` 只拿状态锁，不拿 `drainLock`；测试用 `BlockingTransport` 在另一线程验证。
- `detach` 幂等；detach 后 `offer` -> `REJECTED_CLOSED`，`drainOne` -> false；`transport.close()` 只调用一次。

- [ ] **Step 1（测试 agent）：写失败测试**

`ClientSendSessionTest` 至少包含：

- `join_descriptorSentBeforeFirstAccessUnit`：join -> `drainOne` 先产生 `DESCRIPTOR` 事件；再 offer join -> `drainOne` 产生 `ACCESS_UNIT`，事件顺序固定。
- `offerBeforeDescriptor_returnsNotReady`。
- `lateJoin_waitsForJoinPoint`：offer 非 join 返回 `REJECTED_NOT_JOIN_POINT`；offer join 后 drain 成功。
- `ordinaryOldEpochAu_rejected`：当前 epoch 2，AU epoch 1（非 join）返回 `REJECTED_EPOCH`。
- `epochChange_clearsQueueAndDescriptorPrecedesNewAu`：新 epoch 后旧队列清空；drain 先新 descriptor，再新 AU。
- `duplicateAndStaleDescriptorIgnored`、`sameEpochDifferentConfigThrows`。
- `jpegOverflow_keepsLatestAndDoesNotResync`：`KEEP_LATEST_FRAME` 下溢出，状态不变，`resyncCount` 不增加。
- `referenceOverflow_entersResyncingUntilJoin`：`DROP_UNTIL_JOIN_POINT` 下溢出，状态 `RESYNCING`、resyncCount 增加；后续 join 发送成功后回 `STREAMING`。
- `referenceExpiry_entersResyncing`：时钟推进超过 maxAge；循环 `drainOne` 直到 false，随后状态 `RESYNCING`（若首轮只发送了 pending descriptor，下一轮 poll 才触发过期）。
- `slowTransport_doesNotBlockOffer`：`BlockingTransport` 阻塞 `sendAccessUnit`；另一线程调用 `offer(join)` 在超时内完成，随后释放 latch。
- `transportFailure_detachesAndClosesOnce`：`failNextSend` 后 `drainOne` 抛 `IOException`，`isDetached()` true，transport 关闭一次。
- `detach_isIdempotentAndRejectsNewUnits`。
- `stats_reportQueuedDroppedResyncSentAndDescriptorCounts`。

- [ ] **Step 2（测试 agent）：运行红测**

Run: `./gradlew :app:testDebugUnitTest --tests "com.obby.android.localscreenshare.core.stream.ClientSendSessionTest" --offline --console=plain`

Expected: 编译失败。

- [ ] **Step 3（Pro）：只写生产实现**

实现 `MediaTransport` 与 `ClientSendSession`。严格按上述 `drainOne` 锁边界；`drainLock` 与状态锁分离；`offer` 不拿 `drainLock`；描述符在队列轮询之前发送；普通 AU 也校验 epoch。Pro 不运行 Gradle。

- [ ] **Step 4（验证 agent）：跑绿测**

Run: `./gradlew :app:testDebugUnitTest --tests "com.obby.android.localscreenshare.core.stream.ClientSendSessionTest" --offline --console=plain`

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 5（审查 agent）：会话审查**

检查：描述符先于首 AU；锁内 poll、锁外 send；慢 send 不阻塞 offer；JPEG 溢出不 resync；参考帧 resync 恢复；普通旧 epoch AU 被拒绝；失败后 transport 关闭且可被 Hub 移除。通过后提交。

- [ ] **Step 6（验证 agent）：提交**

```bash
git add app/src/main/java/com/obby/android/localscreenshare/core/stream/ClientSendStats.java \
        app/src/main/java/com/obby/android/localscreenshare/core/stream/MediaTransport.java \
        app/src/main/java/com/obby/android/localscreenshare/core/stream/ClientSendSession.java \
        app/src/test/java/com/obby/android/localscreenshare/core/stream/ClientSendSessionTest.java \
        app/src/test/java/com/obby/android/localscreenshare/core/support/FakeTransport.java \
        app/src/test/java/com/obby/android/localscreenshare/core/support/BlockingTransport.java
git commit -m "feat: 添加媒体传输与客户端发送会话"
```

## Task 6: 单活动流运行时

**Files:**
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/stream/StreamRuntime.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/stream/StreamRuntimeListener.java`
- Test: `app/src/test/java/com/obby/android/localscreenshare/core/stream/StreamRuntimeTest.java`

**Interfaces:**
- Consumes: Task 1–2 的模型/SPI；Task 2 `FakeCodec`；Task 4 `TimeSource`、`MutableClock`。
- Produces:
  - `StreamRuntimeListener`：`void onAccessUnit(EncodedAccessUnit accessUnit)`、`void onDescriptorChanged(StreamDescriptor descriptor)`。
  - `StreamRuntime(CodecRegistry, StreamKey, StreamDescriptor, StreamParameters)`；测试构造函数额外接收 `TimeSource` 与 `long joinPointMinIntervalMs`。
  - 方法：`start()`、`close()`、`isClosed()`、`getDescriptor()`、`getConfigEpoch()`、`getParameters()`、`@Nullable Surface getInputSurface()`、`updateParameters(StreamParameters)`、`boolean requestJoinPoint()`、`void switchDescriptor(StreamDescriptor next)`、`addListener(StreamRuntimeListener)`、`removeListener(StreamRuntimeListener)`。

**运行时语义（必须由测试固定）：**
- `start()` 通过 registry 创建 encoder；若 `registration.getDescriptor().isSurfaceInputRequired()` 为 true 且 `getInputSurface()==null`，抛 `IllegalStateException`；否则启动。
- `getInputSurface()` 委托当前 encoder；JPEG 为 null；未来 MediaCodec 返回输入 Surface。
- `updateParameters` 只更新参数，不推进 epoch、不重建 encoder。
- `requestJoinPoint`：合并并限频。没有 pending 请求且距上次成功 join 超过最小间隔时，设置 pending 并调用 `encoder.requestSyncPoint()`；重复请求在 pending 期间不转发。编码器输出 join point 时清 pending 并记录时间。返回值表示本次是否向 encoder 转发。
- `switchDescriptor`：只接受同一 stream、`configEpoch == 当前+1`、且解码配置变化的新描述符；重复/跳号抛 `ConfigEpochException`，未变化抛 `IllegalArgumentException`。先创建并启动新 encoder；预热失败抛 `CodecSwitchException` 并保留旧 encoder。成功后 `old.stop()`、替换、generation 递增、通知描述符、重置 join 请求并主动 `requestJoinPoint()`。旧 encoder 的迟到 AU 通过 generation + epoch 双重丢弃；旧 encoder 在新 epoch 首个 join point 广播后 `close()`。
- `onAccessUnit`：校验 streamKey、generation、AU `configEpoch == 当前`；**旧 epoch 普通 AU 也丢弃**；listener 异常逐个捕获，不阻断其他 listener。
- `close()` 幂等，关闭活动 encoder 与尚未释放的 retired encoder。

- [ ] **Step 1（测试 agent）：写失败测试**

`StreamRuntimeTest` 至少包含：

- `start_createsEncoderAndForwardsAccessUnits`。
- `getInputSurface_isNullForJpegLikeEncoder`。
- `start_surfaceRequiredButEncoderReturnsNull_throws`：用 `FakeCodec` 注册一个 `surfaceInputRequired=true` 的变体，启动抛 `IllegalStateException`。
- `updateParameters_doesNotAdvanceEpochOrRecreateEncoder`。
- `requestJoinPoint_coalescesUntilJoinPoint`：关闭 fake 自动发射；连续 3 次调用只转发 1 次；fake `emitJoin` 后再调用（推进时钟）计数为 2。
- `requestJoinPoint_isRateLimited`：开启自动发射；join 后立即请求被限频，推进时钟后成功。
- `switchDescriptor_advancesEpochAndReplacesEncoder`：旧 encoder `stopped`；新 descriptor 通知；新 join 后旧 encoder `closed`。
- `switchDescriptor_rejectsDuplicateAndSkippedEpoch`、`switchDescriptor_rejectsUnchangedDecodeConfig`。
- `switchDescriptor_prewarmFailureKeepsOldEncoder`。
- `staleEncoderOrdinaryAu_isDroppedAfterSwitch`：旧 encoder 发非 join、旧 epoch AU，listener 不收到。
- `oldEpochOrdinaryAu_isDroppedByRuntime`：当前 epoch 2，注入 epoch 1 的普通 AU，不转发。
- `newEpochSequenceStartsAtZero`：新 encoder 的 AU sequence 0，runtime 正常转发。
- `listenerException_doesNotStopOtherListeners`。
- `close_isIdempotentAndClosesActiveAndRetiredEncoders`。

- [ ] **Step 2（测试 agent）：运行红测**

Run: `./gradlew :app:testDebugUnitTest --tests "com.obby.android.localscreenshare.core.stream.StreamRuntimeTest" --offline --console=plain`

Expected: 编译失败。

- [ ] **Step 3（Pro）：只写生产实现**

实现 `StreamRuntime`。encoder generation 用 `long` 或 `AtomicLong`；join 请求合并/限频使用注入的 `TimeSource`；旧 encoder 释放按“新 epoch 首个 join point 后”执行；不使用 Android 时钟/日志。Pro 不运行 Gradle。

- [ ] **Step 4（验证 agent）：跑绿测**

Run: `./gradlew :app:testDebugUnitTest --tests "com.obby.android.localscreenshare.core.stream.StreamRuntimeTest" --offline --console=plain`

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 5（审查 agent）：运行时审查**

检查：join 请求由 runtime 合并限频；普通旧 epoch AU 被丢弃；切换预失败保旧 encoder；旧 encoder 在新 join 后才释放；`getInputSurface` 为未来 MediaCodec 留路径；无 Android 实现。通过后提交。

- [ ] **Step 6（验证 agent）：提交**

```bash
git add app/src/main/java/com/obby/android/localscreenshare/core/stream/StreamRuntime.java \
        app/src/main/java/com/obby/android/localscreenshare/core/stream/StreamRuntimeListener.java \
        app/src/test/java/com/obby/android/localscreenshare/core/stream/StreamRuntimeTest.java
git commit -m "feat: 添加单活动流运行时"
```

## Task 7: 可扩展 StreamHub

**Files:**
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/stream/ClientCapabilities.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/stream/JoinDecision.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/stream/StreamHub.java`
- Test: `app/src/test/java/com/obby/android/localscreenshare/core/stream/StreamHubTest.java`
- Test support: `app/src/test/java/com/obby/android/localscreenshare/core/support/FakeTransport.java`（已存在，扩展事件/失败能力）
- Test support: `app/src/test/java/com/obby/android/localscreenshare/core/support/MutableClock.java`（已存在）

**Interfaces:**
- Consumes: Task 1 `ClientSessionKey`；Task 2 registry/registration；Task 5 `ClientSendSession`/`MediaTransport`；Task 6 `StreamRuntime`；Task 4 `StreamQueueLimits`/`TimeSource`。
- Produces:
  - `ClientCapabilities(Set<String> codecIds)`；`supports(String)`、`getCodecIds()`；集合防御性复制。
  - `JoinDecision`：`Status` 为 `ACCEPTED`、`REQUIRES_EXPLICIT_FALLBACK`、`REJECTED`；`getSession()`、`getFallbackCodecId()`、`getReason()`；静态 `accepted(ClientSendSession)`、`requiresFallback(String, String)`、`rejected(String)`。
  - `StreamHub(CodecRegistry)`、`StreamHub(CodecRegistry, StreamQueueLimits, TimeSource)`；`register(StreamRuntime)`、`unregister(StreamKey)`、`activate(StreamKey)`、`getActiveStream()`、`JoinDecision join(ClientSessionKey, ClientCapabilities, MediaTransport)`、`void detach(ClientSessionKey)`、`boolean drainOne(ClientSessionKey) throws IOException`、`ClientSendSession getSession(ClientSessionKey)`、`int getSessionCount()`。
  - `StreamHub` 实现 `StreamRuntimeListener`，只广播活动流 AU；非活动流 AU 忽略。

**Hub 语义（必须由测试固定）：**
- 只允许一条活动流；重复注册/未知激活流/已有订阅者时切换活动流显式失败；同一 `ClientSessionKey` 重复 join 拒绝。
- `unregister` 幂等；移除 runtime listener，若注销的是活动流则 detach 其所有 session 并清空 active；不在本批次实现多流调度。
- `join`：
  1. 无活动流 -> 拒绝。
  2. key 的 streamKey 必须等于活动流；重复 key -> 拒绝。
  3. 客户端支持当前 codec -> 创建绑定 transport 的 `ClientSendSession`，调用 `onDescriptor(activeDescriptor)`，加入 session map，**随后调用 `activeRuntime.requestJoinPoint()`**；返回 accepted。新客户端 join 必须触发 runtime 的 join 请求，测试不得只靠手动 emit 让生产代码等待。
  4. 不支持当前 codec -> 用 `CodecRegistration.fallbackPriority` 与已保存的 `ClientCapabilities` 计算所有客户端共同支持的最低 priority codec；priority 相同时按 `codecId` 字典序稳定选择。存在则返回 `REQUIRES_EXPLICIT_FALLBACK`（不自动切换、不创建 session），否则拒绝。**Hub 不出现 JPEG 或任何具体 codec 名称。**
- 第二个客户端 join 时，如果第一个 join 的请求仍未完成，runtime 合并请求；测试断言 fake encoder 只收到一次 `requestSyncPoint`。
- `drainOne(key)` 调用 session.drainOne；发送失败时 `detach(key)` 后重新抛出，Hub 中不再保留该会话。
- `detach` 幂等；移除 session 与 capabilities，关闭 transport；同一 key 可在 detach 后重新 join 并绑定新 transport。
- 广播使用 session map 快照；单个 session 的 `offer` 异常不影响其他 session；不阻塞编码器。
- `onDescriptorChanged` 通知所有 session 更新描述符；session 对象与 transport 对象不重建。
- `getSession` 供测试读取；生产不会通过它修改状态。

- [ ] **Step 1（测试 agent）：写失败测试**

`StreamHubTest` 至少包含：

- `register_rejectsDuplicateAndUnregisterIsIdempotent`。
- `unregister_active_detachesSessionsAndClearsActive`：join 后注销活动流，`getSessionCount()==0`、`getActiveStream()==null`。
- `activate_requiresRegisteredStream`、`activate_differentStreamWithSessionsRejected`。
- `join_withoutActiveStreamRejected`。
- `join_triggersRuntimeRequestJoinPoint`：join 后 fake `getRequestCount()==1`，不需要测试手动 emit 来触发。
- `multipleJoins_coalesceJoinPointRequest`：关闭 fake 自动发射；A、B 先后 join；requestCount 为 1；fake `emitJoin` 后两客户端都能 drain 到该 join。
- `join_descriptorBeforeFirstAu`：关闭 fake 自动发射；join 后 `hub.drainOne(key)`，事件首项是 `DESCRIPTOR`；fake emit join 后再 drain，事件包含 `ACCESS_UNIT`，顺序固定。
- `join_duplicateSessionKeyRejected`、`join_streamKeyMismatchRejected`。
- `join_compatibleClientAttachesSession`。
- `join_unsupportedClient_rejectedWithoutChangingExisting`。
- `join_fallbackUsesRegistrationPriorityNotCodecName`：注册 fake（priority 0）、fallbackA（priority 20）、fallbackB（priority 10）；active 为 fake；现有与候选都支持 A/B；返回 fallbackB，证明 Hub 只读元数据。
- `join_fallbackDoesNotAutoSwitch`：返回 fallback 后 active descriptor/epoch 不变，现有 session 引用不变。
- `broadcast_isolatesSlowClientOverflow`：Hub limits 为 2 个 AU/128 字节；A 不 drain，B 每帧 drain；fake 发 join 后连续发非 join；A 进入 `RESYNCING`，B 全部收到；下一次 join 后 A 恢复。
- `descriptorChange_notifiesAllSessionsAndKeepsObjects`。
- `drainFailure_removesSession`：FakeTransport 抛 IOException，`hub.drainOne` 后 `getSessionCount()==0`。
- `detach_isIdempotentAndAllowsRejoinWithNewTransport`。
- `concurrentAttachDetach_doesNotThrowOrLoseSessions`：4 个线程使用各自独立的 `ClientSessionKey`，各 join/detach 100 次，最终 session count 0，异常列表为空。

- [ ] **Step 2（测试 agent）：运行红测**

Run: `./gradlew :app:testDebugUnitTest --tests "com.obby.android.localscreenshare.core.stream.StreamHubTest" --offline --console=plain`

Expected: 编译失败。

- [ ] **Step 3（Pro）：只写生产实现**

实现 Hub。session map 使用 `ConcurrentHashMap<ClientSessionKey, ClientSendSession>`；capabilities 单独 map 或在 join 时保存；fallback 遍历 registry registrations，按 priority 选最小，禁止 codec 名称常量。join accepted 后必须调用 active runtime `requestJoinPoint()`。广播用快照，异常隔离。Pro 不运行 Gradle。

- [ ] **Step 4（验证 agent）：跑绿测**

Run: `./gradlew :app:testDebugUnitTest --tests "com.obby.android.localscreenshare.core.stream.StreamHubTest" --offline --console=plain`

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 5（审查 agent）：Hub 审查**

检查：join 触发 runtime 请求；描述符先于 AU；fallback 无 JPEG 硬编码、不自动切换；SessionKey 含 streamId/clientId；慢客户端隔离；drain 失败移除；并发 attach/detach 安全。通过后提交。

- [ ] **Step 6（验证 agent）：提交**

```bash
git add app/src/main/java/com/obby/android/localscreenshare/core/stream/ClientCapabilities.java \
        app/src/main/java/com/obby/android/localscreenshare/core/stream/JoinDecision.java \
        app/src/main/java/com/obby/android/localscreenshare/core/stream/StreamHub.java \
        app/src/test/java/com/obby/android/localscreenshare/core/stream/StreamHubTest.java \
        app/src/test/java/com/obby/android/localscreenshare/core/support/FakeTransport.java \
        app/src/test/java/com/obby/android/localscreenshare/core/support/MutableClock.java
git commit -m "feat: 添加可扩展 StreamHub"
```

## Task 8: 客户端媒体状态机

**Files:**
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/client/ClientMediaSession.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/client/ClientStateListener.java`
- Test: `app/src/test/java/com/obby/android/localscreenshare/core/client/ClientMediaSessionTest.java`

**Interfaces:**
- Consumes: Task 1 `ClientSessionKey`、`ClientState`、`StreamDescriptor`、`EncodedAccessUnit`；Task 2 `CodecRegistry`、`Decoder`、`RenderTarget`；Task 3 JPEG；Task 7 `ClientCapabilities`、`FakeCodec`/`FakeRenderTarget`。
- Produces:
  - `ClientStateListener`：`void onStateChanged(ClientState previous, ClientState current)`。
  - `ClientMediaSession(ClientSessionKey key, CodecRegistry registry, ClientCapabilities capabilities, RenderTarget renderTarget, ClientStateListener listener)`；`getState()`、`getCurrentDescriptor()`、`boolean onDescriptor(StreamDescriptor)`、`boolean onAccessUnit(EncodedAccessUnit)`、`close()`、`isClosed()`。

**状态机语义（必须由测试固定）：**
- 初始 `NEGOTIATING`。
- `onDescriptor`：不支持 codec -> `CLOSED`；注册描述符要求 Surface 输出但 `renderTarget.getSurface()==null` -> `RESYNCING`（配置条件不满足，等待显示侧 attach）；首次支持 -> `WAIT_CONFIG`，创建 decoder、configure，成功 `WAIT_RANDOM_ACCESS`，失败 `RESYNCING`；同 epoch 同 hash 返回 false；旧 epoch 返回 false；同 epoch 不同 hash 抛 `IllegalArgumentException`；新 epoch -> `WAIT_CONFIG`，关闭旧 decoder、创建新 decoder、configure，成功 `WAIT_RANDOM_ACCESS`，失败 `RESYNCING`。
- `onAccessUnit`：null 抛 `IllegalArgumentException`；先比较 `au.getConfigEpoch()` 与当前描述符：
  - 旧 epoch：丢弃，状态不变；**普通 AU 也丢弃**。
  - 未来 epoch：进入 `RESYNCING`，丢弃，等待新描述符。
  - 相同 epoch：`WAIT_CONFIG` 丢弃；`WAIT_RANDOM_ACCESS` 只有 policy join point 才进入 `STREAMING` 并 decode；`STREAMING` 正常 decode；`RESYNCING` 只有 join point 才恢复 `STREAMING` 并 decode。
  - `Decoder.decode` 抛 `DecoderException` -> `RESYNCING`，返回 false。
- `close()` 幂等，关闭 decoder，进入 `CLOSED`；不关闭 RenderTarget（RenderTarget 由窗口/显示侧持有）。
- 本批次不实现客户端超时定时器、恢复 ack 或断开策略；计划与测试不得伪造。
- 每次状态变化回调 listener，previous/current 不同。

- [ ] **Step 1（测试 agent）：写失败测试**

`ClientMediaSessionTest` 至少包含：

- `newSession_isNegotiating`。
- `onDescriptor_entersWaitConfigThenWaitRandomAccess`。
- `emptyConfigJpegDescriptor_isAccepted`。
- `unsupportedCodec_closesSession`。
- `surfaceRequiredButRenderTargetHasNoSurface_entersResyncing`：fake codec `surfaceOutputRequired=true` + `FakeRenderTarget.getSurface()==null`，状态 `RESYNCING`。
- `nonJoinFrameInWaitRandomAccessIsDropped`。
- `matchingJoinPoint_transitionsToStreamingAndRendersImage`：`FakeRenderTarget.getImages()` 有 1 个。
- `staleEpochOrdinaryAu_isDroppedWithoutStateChange`：普通 AU epoch 1，当前 2。
- `futureEpochOrdinaryAu_entersResyncing`。
- `duplicateDescriptor_isIgnored`、`sameEpochDifferentConfigThrows`。
- `newDescriptor_recreatesDecoderAndWaitsForNewJoinPoint`。
- `decoderFailure_entersResyncingAndRecoversAtJoinPoint`：`FakeDecoder.failNextDecode`。
- `close_isIdempotentAndClosesDecoderButNotRenderTarget`。
- `stateListener_receivesOnlyDistinctTransitions`。

- [ ] **Step 2（测试 agent）：运行红测**

Run: `./gradlew :app:testDebugUnitTest --tests "com.obby.android.localscreenshare.core.client.ClientMediaSessionTest" --offline --console=plain`

Expected: 编译失败。

- [ ] **Step 3（Pro）：只写生产实现**

实现 `ClientMediaSession`。状态迁移集中在同步方法；decoder 通过 `DecoderFactory` 获取，传入 `RenderTarget`；错误只捕获 `DecoderException`；普通 AU 也校验 epoch；不实现超时。Pro 不运行 Gradle。

- [ ] **Step 4（验证 agent）：跑绿测**

Run: `./gradlew :app:testDebugUnitTest --tests "com.obby.android.localscreenshare.core.client.ClientMediaSessionTest" --offline --console=plain`

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 5（审查 agent）：状态机审查**

检查：六状态完整；旧 epoch 普通 AU 也丢弃；未来 epoch 进入 RESYNCING；RESYNCING 只通过同 epoch join 恢复；Surface 输出要求被检查；decoder 错误单一通道；不实现超时；RenderTarget 不被 session 关闭。通过后提交。

- [ ] **Step 6（验证 agent）：提交**

```bash
git add app/src/main/java/com/obby/android/localscreenshare/core/client \
        app/src/test/java/com/obby/android/localscreenshare/core/client
git commit -m "feat: 添加客户端媒体状态机"
```

## Task 9: JVM 端到端、旧路径兼容与任务看板更新

**Files:**
- Test: `app/src/test/java/com/obby/android/localscreenshare/core/stream/StreamFrameworkEndToEndTest.java`
- Test support: `app/src/test/java/com/obby/android/localscreenshare/core/support/CapturingJpegEncoderFactory.java`
- Modify: `app/src/test/java/com/obby/android/localscreenshare/core/support/FakeTransport.java`（事件顺序/慢发送辅助）
- Modify: `app/src/test/java/com/obby/android/localscreenshare/core/support/FakeCodec.java`（如 E2E 需要旧 encoder 引用）
- 不改生产文件；不新增生产接线。

**Interfaces:**
- Consumes: Task 1–8 全部类型；`FakeCodec`、`FakeRenderTarget`、`FakeTransport`、`BlockingTransport`、`MutableClock`、`JpegFixtures`。
- Produces:
  - `CapturingJpegEncoderFactory`：包装 `JpegEncoderFactory`，`create` 后暴露最新 `JpegEncoder`，供测试调用 `offerJpegFrame`。
  - E2E 装配：`StreamRuntime` + `StreamHub` + `ClientSendSession` + `FakeTransport` + `ClientMediaSession` + `FakeRenderTarget`。
  - E2E 的 runtime 使用 `joinPointMinIntervalMs=0`（或显式推进注入时钟），否则连续客户端 join 会被限频，测试不得把“限频”误判为“未触发 join 请求”。

**E2E drain 顺序（固定）：**
1. 先 `hub.drainOne(key)`，直到返回 false；这会先发送 descriptor 事件。
2. 按 transport `events` 顺序读取：`DESCRIPTOR` 必须出现在该 epoch 的第一个 `ACCESS_UNIT` 之前。
3. 把 `events` 中的 descriptor/AU 按顺序喂给 `ClientMediaSession.onDescriptor`/`onAccessUnit`。
4. 任何测试不得先喂 AU 再喂 descriptor，也不得通过手动 emit 替代 join 请求。

- [ ] **Step 1（测试 agent）：写端到端测试**

`StreamFrameworkEndToEndTest` 至少包含：

1. `twoClientsJoinAtDifferentTimes_joinRequestsAndDescriptorOrder`
   - 客户端 A join -> Hub 调用 runtime requestJoinPoint -> fake encoder 自动发出 join；drain A，事件首项为 descriptor，随后是 join AU。
   - 客户端 B 在 A 的 join 后 join -> Hub 触发下一次 request；drain B，事件首项为 descriptor，随后是 join AU。
   - 发射非 join 帧；A 收到所有后续帧，B 只收到其 join 之后的帧。
   - 断言两个 transport 在每个 epoch 的 descriptor 都先于首个 AU；断言 B 没有收到加入前的 AU。

2. `slowClientOverflow_doesNotBlockOtherClient`
   - Hub limits 为 2 个 AU / 128 字节；A 不 drain，B 每帧 drain。
   - fake join 后连续发非 join；A 进入 `RESYNCING`、`droppedUnits>0`；B 收到全部帧。
   - fake 再发 join，A drain 后恢复 `STREAMING`；B 不受影响。
   - 断言 Hub 广播无异常；B 的序列连续。

3. `configSwitch_keepsSessionAndTransportObjects`
   - 记录 A/B 的 `ClientSendSession` 与 `FakeTransport` 引用。
   - `runtime.switchDescriptor` 到 epoch+1、不同 visible size；Hub 通知 session；drain 两个客户端，先出现新 descriptor 事件，再出现新 epoch AU。
   - 断言 runtime/session/transport 引用不变；旧 epoch 普通 AU 不会进入新 session；客户端从 `WAIT_CONFIG`/`WAIT_RANDOM_ACCESS` 恢复到 `STREAMING`。
   - 旧 encoder 在新 join 后释放（通过 fake 的 `closed` 字段或 runtime 测试已覆盖）。

4. `fallbackUsesMetadataAndDoesNotAutoSwitch`
   - 注册 fake（priority 0）、jpeg（priority 100）；active fake；现有 A 支持 fake+jpeg，候选只支持 jpeg。
   - `hub.join` 返回 `REQUIRES_EXPLICIT_FALLBACK`，codec 为 jpeg；活动流 epoch/descriptor 不变；A 的 session 状态不变。
   - 另建候选不支持任何共同 codec，返回 `REJECTED`。

5. `duplicateEpochSwitch_isRejectedWithoutTouchingSessions`
   - 同 epoch 调用 `switchDescriptor` 抛 `ConfigEpochException`；session/transport 引用与状态不变。

6. `jpegEmptyConfig_worksEndToEnd`
   - 用 `JpegCodec.registration()` 的 descriptor/decoder/policy + `CapturingJpegEncoderFactory` 构造 registry。
   - join 触发 runtime request（JPEG `requestSyncPoint` 为 no-op，但 Hub 仍必须调用）；随后调用 `JpegEncoder.offerJpegFrame` 产生完整帧。
   - drain 先 descriptor 后 AU；`ClientMediaSession` 进入 `STREAMING`；`FakeRenderTarget` 收到一个 `DecodedImage`。

- [ ] **Step 2（测试 agent）：运行并记录红/绿**

Run: `./gradlew :app:testDebugUnitTest --tests "com.obby.android.localscreenshare.core.stream.StreamFrameworkEndToEndTest" --offline --console=plain`

Expected: 先出现失败或编译错误；补齐测试支持后，若生产契约有缺陷，记录失败测试名与断言，退回对应 Pro Task 修复并提交 `fix: ...`；修复后重新运行本命令并得到 `BUILD SUCCESSFUL`。

- [ ] **Step 3（集成 agent）：旧路径兼容检查**

Run:

```bash
git diff --name-only -- app/src/main/java/com/obby/android/localscreenshare/service \
    app/src/main/java/com/obby/android/localscreenshare/client \
    app/src/main/java/com/obby/android/localscreenshare/server \
    app/src/main/proto
```

Expected: 输出为空。若不为空，停止并说明是计划外的行为变更，不得提交。

Run:

```bash
grep -R "Bitmap\|ImageReader\|MediaCodec\|MediaProjection\|android\.graphics\|android\.media\|android\.util\.Log\|android\.os\." \
  app/src/main/java/com/obby/android/localscreenshare/core app/src/test/java/com/obby/android/localscreenshare/core || true
```

Expected: 生产 `core` 中只允许 `android.view.Surface` 出现在 `Encoder.getInputSurface()` 与 `RenderTarget.getSurface()` 签名；不允许上述实际调用。测试中出现字符串 `MediaCodec` 只允许在文档/断言名称，不允许调用 Android API。

Run:

```bash
grep -R "List\.of\|Set\.of\|Map\.of\|List\.copyOf\|Set\.copyOf\|String\.isBlank\|Optional\.isEmpty\|Stream\.toList" \
  app/src/main/java/com/obby/android/localscreenshare/core || true
```

Expected: 无输出。

- [ ] **Step 4（集成 agent）：全量 JVM 验证**

Run:

```bash
./gradlew :app:testDebugUnitTest --offline --console=plain
./gradlew :app:assembleDebug --offline --console=plain
```

Expected: 两条命令均 `BUILD SUCCESSFUL`；没有执行 `connectedAndroidTest`、adb、emulator 或设备访问。

- [ ] **Step 5（审查 agent）：全分支审查**

检查：
- 公共层没有 NAL/OBU/JPEG 解析、SPS/PPS 提取或帧类型推断。
- 没有 codec 名称分支；JPEG 差异只在 `core.codec.jpeg`；Hub fallback 只读 `fallbackPriority`。
- 每个 AU 有 `configEpoch`，普通旧 epoch AU 被拒；sequence 是 epoch 内语义。
- 描述符先于同 epoch 首 AU；transport 事件顺序测试通过。
- JPEG 溢出不 resync；参考帧溢出/过期 resync；慢 send 不阻塞 offer。
- 无 Packetizer/RTP 契约；无 MediaCodec/H264/HEVC/AV1 实现；无多流调度。
- `build.gradle`、`gradle/libs.versions.toml`、proto、`LssService`、`LssClientService`、`LssServer` 无改动。
- 核心生产代码无 Android Log/OS/图形/媒体实现依赖；无 Java 9+ 便捷 API。
- 测试只使用 fake codec/fake transport/fake RenderTarget/JVM。

- [ ] **Step 6（集成 agent）：提交 E2E 测试**

```bash
git add app/src/test/java/com/obby/android/localscreenshare/core/stream/StreamFrameworkEndToEndTest.java \
        app/src/test/java/com/obby/android/localscreenshare/core/support
git commit -m "test: 添加编码框架 JVM 端到端测试"
```

- [ ] **Step 7（集成 agent）：更新任务看板**

在本 Task 全量验证通过后，更新 `docs/task-board.md`：
- `TB-FW-001` 至 `TB-FW-008` 中实际完成且验证通过的子项，状态改为“已完成”，证据写明确切的 Gradle 命令、通过测试类与提交号。
- 未实现项（H.264/HEVC/AV1/RTP/设备验证）保持“待办”或“等待验证”，不得写成已完成。
- `TB-DOC-008` 保持“计划修订待复审”，直到用户审核；若进入执行，再改为“进行中”。
- 提交：`git add docs/task-board.md && git commit -m "docs: 更新编码框架执行状态"`。

## 自审清单

- **范围诚实性：** 本批次只实现隔离核心框架、JPEG 独立帧参考适配、fake/JVM 测试；不接线现有投屏，不宣称 H.264/MediaCodec/RTP/多流或真实设备已验证。
- **Spec 覆盖：** 覆盖静态 SPI、RenderTarget/DecoderFactory、描述符/参数、AU epoch/sequence、描述符下发顺序、可加入点、六状态、慢客户端、切换、显式 fallback；未覆盖项在各 Task 和 Global Constraints 明确排除。
- **类型闭合：** 所有测试引用的类型、方法、构造器都在 Task 的 Produces 或测试支持中定义；无 Packetizer/DecoderOutput/TransportKind 残留；`ClientSessionKey`、`QueuePollResult`、`DecodedImage` 等均有明确归属。
- **接口返回类型：** `onDescriptor`、`onAccessUnit`、`drainOne`、`requestJoinPoint`、`join` 的返回类型在各 Task 间一致；`drainOne` 的 IOException 由 Hub 捕获并移除会话。
- **执行可达性：** 每个 Task 都有红测命令、Pro 实现、绿测命令、审查和提交；E2E 的 drain 顺序和 transport 事件顺序明确；生产修复有独立 `fix:` 提交点。
- **Review Focus：** 空配置、未知 codec、epoch/sequence、描述符顺序、队列分流、null/空 payload、并发/慢发送、RenderTarget/Surface、通用 fallback、核心 JVM 化均有测试或审查步骤。
- **比例：** 计划给出接口、关键语义、测试名称与命令，不复制生产实现；具体算法留给 Pro。

## Execution Handoff

计划修订后先等待用户复审。用户已指定执行方式：Pro 写核心生产代码且不编译，当前侧 agent 写测试、fixtures、集成、验证、文档与提交。复审通过后按 Task 1 → Task 9 顺序执行；每个 Task 的失败测试必须先于 Pro 实现，且每个 Task 必须通过审查后才提交。端到端阶段若发现契约缺陷，先在 owning Task 修复并提交 `fix: ...`，不得在测试中绕过。
