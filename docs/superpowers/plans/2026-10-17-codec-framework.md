# 编码框架核心契约与 JPEG 单流实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 Android 应用中落地静态 Codec SPI、核心数据契约、JPEG 参考适配、单活动流运行时、可扩展 StreamHub、每客户端有界队列与客户端六状态机，并用纯 JVM 测试证明单流、晚加入、慢客户端隔离、配置切换与旧路径兼容。

**Architecture:** 新增 `com.obby.android.localscreenshare.core` 包，分为 `model`（纯数据契约）、`codec`（静态 SPI 与注册表）、`codec.jpeg`（JPEG 参考实现）、`stream`（运行时、Hub、发送队列）、`client`（客户端媒体状态机）。服务端仍只跑一条活动流；`StreamHub` 只做注册、查找、订阅与广播，不做多流调度。本批次不接线到 `LssService` / `LssClientService` 的现有 JPEG/gRPC 数据路径，旧行为通过“不修改这些文件、不修改 proto”保证。

**Tech Stack:** Java 11 source/target、Android minSdk 25、compileSdk 35、AGP 8.9.0、Gradle 8.11.1、JUnit 4.13.2。仅使用现有依赖；不引入 Mockito、Kotlin、协程或新测试库。

**Spec:** [可扩展编码框架设计](../../codec-framework.md)

## Global Constraints

- 源码与目标兼容级别保持 Java 11（`sourceCompatibility JavaVersion.VERSION_11`、`targetCompatibility JavaVersion.VERSION_11`）。
- `minSdk 25`、`compileSdk 35`、`targetSdk 35` 不变；不提升最低系统版本。
- 不新增依赖，除非现有库确有必要；本批次必须做到 `build.gradle`、`gradle/libs.versions.toml` 零改动。
- 保持现有公开行为：JPEG 采集/压缩、gRPC `ScreenFrame`、NSD 发现、悬浮窗 UI、通知与统计行为不变；`screen_stream.proto` 不改。
- 本批次不修改 `LssService`、`LssClientService`、`LssServer` 的数据路径，不做大范围重写；只新增 `core` 包与对应 JVM 测试。
- 本批次不实现 `MediaCodec`、H.264、HEVC、AV1、RTP、WebRTC、GPU Fanout、多活动流调度、真实设备 instrumentation。
- 全过程禁止 `adb`、emulator、虚拟机、实机访问与 `connectedAndroidTest`；只允许本地 JVM/Gradle 单元测试。
- 端到端测试只使用 fake codec、fake transport 与 JVM；任何测试不得触发 Android 设备 API 或真实网络。
- Pro（DeepSeek v4 Pro）只写 `core` 生产代码，不编译、不运行 Gradle、不修改测试、不提交。
- 测试代码、fixtures、fakes、集成验证、编译、测试运行、文档与提交由当前侧 agent 完成。
- 文档角色只用“服务端/客户端”描述产品侧；执行角色使用“测试 agent / Pro / 验证 agent / 审查 agent / 集成 agent”。示意图如需使用 Mermaid。
- 文档不得包含本机绝对路径、用户名、账号、令牌、密钥、签名口令或任何敏感材料；只写仓库相对路径。
- 本批次只完成 spec 第 11 节阶段 1 的一个子集：JPEG、数据契约、单流、队列、状态机与 JVM 验证。H.264 的契约验证留到下一批次，不能写成已完成。

## Review Focus

以下六类输入或故障最容易被实现遗漏，且每类都必须在对应 Task 的测试中出现：

1. **空配置描述符**：JPEG 没有 `CodecConfigPart` 时，描述符仍可构建、计算 hash、下发和加入；不能因空列表抛异常或阻止 `STREAMING`。对应 Task 1、3、6。
2. **未知 codec / 空 codecId**：注册表查找必须显式抛 `CodecNotFoundException`，空字符串或空白 `codecId` 在构造时拒绝；不能静默回退到其他 codec。对应 Task 1、2、3。
3. **重复/过期 epoch**：重复描述符被忽略，旧 epoch 的访问单元被丢弃，跳号 epoch 被拒绝，同一 epoch 改解码配置被拒绝。对应 Task 4、5、6、7。
4. **队列溢出**：JPEG 只保留最新完整帧，参考帧策略丢弃到下一个可加入点；慢客户端不得阻塞编码器或影响其他客户端。对应 Task 4、7。
5. **null/空 payload**：`EncodedAccessUnit` 拒绝 null 与零长度 payload；空 JPEG、截断 JPEG 不产生访问单元；API 对 null 参数显式失败。对应 Task 1、3、4。
6. **并发 attach/detach**：Hub 广播使用快照，单个会话异常不影响其他会话，detach 幂等，并发加入/离开不丢会话、不抛 `ConcurrentModificationException`。对应 Task 5、7。

## 执行分工与交接

| 角色 | 负责 | 禁止 |
| --- | --- | --- |
| 测试 agent | 先写失败测试、fixtures、fake codec / fake transport / 可控时钟；运行红测并记录失败原因 | 不写 `core` 生产实现；不改生产代码来“让测试通过” |
| Pro（DeepSeek v4 Pro） | 严格按每个 Task 的 Interfaces 与文件清单编写生产代码；只实现核心契约与逻辑 | 不编译、不运行 Gradle、不修改测试、不提交、不扩展契约 |
| 验证 agent | 编译、运行指定测试、修复仅限导入/拼写/签名接线问题；汇报失败断言 | 不改测试断言、不改变语义、不新增依赖 |
| 审查 agent | 对照 spec 检查职责边界、公共层是否解析负载、epoch/队列/并发边界；检查无 codec 特判 | 不为通过审查而扩大范围；不跳过失败测试 |
| 集成 agent | 最终全量 JVM 测试、`assembleDebug`、旧路径 diff 检查、文档与提交/推送 | 不执行 adb/设备/仪器测试；不改旧路径行为 |

**Handoff 规则：**

- 每个 Task 都由测试 agent 先提交失败测试；Pro 只读取测试与 Interfaces，写生产代码。
- Pro 交付后，验证 agent 运行命令。编译类错误可由验证 agent 做最小接线修复；断言失败或契约不一致必须退回 Pro，附失败测试名与实际输出。
- 审查 agent 通过前不得提交该 Task。
- 每个 Task 的提交由验证 agent 或集成 agent 执行，提交信息见各 Task 最后一步。
- 若 Pro 的实现需要修改测试、proto、`LssService`/`LssClientService` 或新增依赖，停止并退回计划审查，不得自行扩大范围。

## 文件结构

新增生产代码（全部在 `app/src/main/java/com/obby/android/localscreenshare/` 下）：

```text
core/
├── model/
│   ├── StreamKey.java
│   ├── CodecConfigPart.java
│   ├── ColorInfo.java
│   ├── StreamDescriptor.java
│   ├── StreamParameters.java
│   ├── AccessUnitFlags.java
│   ├── JoinInfo.java
│   ├── EncodedAccessUnit.java
│   └── ClientState.java
├── codec/
│   ├── CodecIds.java
│   ├── BitstreamFormat.java
│   ├── CodecDescriptor.java
│   ├── CodecRegistration.java
│   ├── CodecRegistry.java
│   ├── CodecNotFoundException.java
│   ├── ConfigEpochException.java
│   ├── CodecSwitchException.java
│   ├── Encoder.java
│   ├── EncoderFactory.java
│   ├── EncoderOutput.java
│   ├── Decoder.java
│   ├── DecoderFactory.java
│   ├── DecoderOutput.java
│   ├── CodecPolicy.java
│   ├── OverflowAction.java
│   ├── Packetizer.java
│   ├── PacketizerFactory.java
│   ├── TransportKind.java
│   └── jpeg/
│       ├── JpegCodec.java
│       ├── JpegFrameValidator.java
│       ├── JpegCodecPolicy.java
│       ├── JpegEncoder.java
│       ├── JpegEncoderFactory.java
│       ├── JpegDecoder.java
│       └── JpegDecoderFactory.java
├── stream/
│   ├── StreamQueueLimits.java
│   ├── QueueOfferResult.java
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

测试代码在 `app/src/test/java/com/obby/android/localscreenshare/core/` 下，与生产包同名；测试辅助类放 `core/support/`。不新增 `app/src/main` 之外的资源，JPEG fixture 使用测试常量，不引入二进制资源文件。

## Task 1: 核心数据契约

**Files:**
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/model/StreamKey.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/model/CodecConfigPart.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/model/ColorInfo.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/model/StreamDescriptor.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/model/StreamParameters.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/model/AccessUnitFlags.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/model/JoinInfo.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/model/EncodedAccessUnit.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/model/ClientState.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/BitstreamFormat.java`
- Test: `app/src/test/java/com/obby/android/localscreenshare/core/model/StreamDescriptorTest.java`
- Test: `app/src/test/java/com/obby/android/localscreenshare/core/model/EncodedAccessUnitTest.java`

**Interfaces:**
- Consumes: 无。
- Produces:
  - `StreamKey(String sessionInstanceId, String streamId)`；`getSessionInstanceId()`、`getStreamId()`、`getValue()`；非空校验、`equals`/`hashCode`。
  - `CodecConfigPart(String id, byte[] payload)`；`getId()`、`getPayload()`（防御性复制）；payload 非 null 且长度大于 0。
  - `ColorInfo(@Nullable String colorSpace, @Nullable String transferFunction, boolean fullRange)`；`getColorSpace()`、`getTransferFunction()`、`isFullRange()`、`ColorInfo.UNSPECIFIED`。
  - `BitstreamFormat` 枚举：`JPEG_FRAME`、`ANNEX_B`、`AVCC`。
  - `StreamDescriptor` 不可变；字段 `streamKey`、`configEpoch`、`codecId`、`mime`、`profile`、`level`、`codedWidth`、`codedHeight`、`visibleWidth`、`visibleHeight`、`rotationDegrees`、`bitstreamFormat`、`configParts`、`colorInfo`、`configHash`。`codecId` 是静态路由键（`StreamDescriptor` 的 spec 表只列 `mime`，这里显式增加，避免公共层从 MIME 猜测 codec）；`mime` 仍保留。
  - `StreamDescriptor.builder(StreamKey, int configEpoch, String codecId, String mime, BitstreamFormat)`；builder 方法 `profile`、`level`、`codedSize(int,int)`、`visibleSize(int,int)`、`rotationDegrees(int)`、`configParts(List<CodecConfigPart>)`、`colorInfo(ColorInfo)`、`build()`。缺省值：`profile/level` 为 null、`visibleSize` 等于 coded size、`rotationDegrees=0`、`configParts` 为空列表、`colorInfo=ColorInfo.UNSPECIFIED`。
  - `StreamDescriptor` getters：`getStreamKey()`、`getConfigEpoch()`、`getCodecId()`、`getMime()`、`getProfile()`、`getLevel()`、`getCodedWidth()`、`getCodedHeight()`、`getVisibleWidth()`、`getVisibleHeight()`、`getRotationDegrees()`、`getBitstreamFormat()`、`getConfigParts()`、`getColorInfo()`、`getConfigHash()`、`sameDecodeConfig(StreamDescriptor)`、`withConfigEpoch(int)`。
  - `StreamParameters(int targetBitrateBps, int frameRateHint, int keyFrameIntervalMs)`；getters；`StreamParameters.DEFAULT`；`withTargetBitrate`、`withFrameRateHint`、`withKeyFrameInterval`。
  - `AccessUnitFlags.of(boolean syncPoint, boolean joinPoint, boolean config)`、`AccessUnitFlags.none()`；`isSyncPoint()`、`isJoinPoint()`、`isConfig()`。
  - `JoinInfo(int configEpoch, List<CodecConfigPart> requiredConfigParts)`；`getConfigEpoch()`、`getRequiredConfigParts()`。
  - `EncodedAccessUnit(StreamKey, long sequence, long ptsUs, long dtsUs, AccessUnitFlags, @Nullable JoinInfo, @Nullable Integer temporalLayerId, byte[] payload)`；getters 含 `getDtsUs()`、`hasDtsUs()`；`DTS_UNSET = Long.MIN_VALUE`；`getPayloadLength()`；payload 非 null 且长度大于 0；`sequence >= 0`、`ptsUs >= 0`、`dtsUs` 为 `DTS_UNSET` 或非负；joinPoint 为 true 时 joinInfo 必须非 null。
  - `ClientState` 枚举：`NEGOTIATING`、`WAIT_CONFIG`、`WAIT_RANDOM_ACCESS`、`STREAMING`、`RESYNCING`、`CLOSED`。

- [ ] **Step 1（测试 agent）：写失败测试**

`StreamDescriptorTest` 至少包含：

- `configHash_changesWhenCodecOrMimeChanges`：两个描述符仅 `codecId` 或 `mime` 不同，`assertNotEquals(hashA, hashB)`。
- `configHash_changesWhenConfigPartPayloadChanges`：仅配置部件字节不同，hash 不同。
- `configHash_unchangedWhenOnlyEpochOrVisibleSizeChanges`：仅 `configEpoch` 或 `visibleWidth/visibleHeight` 变化，hash 相同；`withConfigEpoch(2)` 保持 hash。
- `sameDecodeConfig_isFalseWhenRotationChanges`：仅旋转角度变化，`assertFalse(next.sameDecodeConfig(previous))`。
- `builder_rejectsInvalidRotationAndNonPositiveCodedSize`：旋转 45 度、codedWidth 0 均抛 `IllegalArgumentException`。
- `emptyConfigParts_areAllowedForJpegDescriptor`：JPEG 描述符不传配置部件，构建成功且 `getConfigParts().isEmpty()`。
- `configPartsAndPayload_areDefensivelyCopied`：修改传入/取出的数组不改变对象内部值。

`EncodedAccessUnitTest` 至少包含：

- `constructor_rejectsNullPayload`、`constructor_rejectsEmptyPayload`：均抛 `IllegalArgumentException`。
- `constructor_requiresJoinInfoWhenJoinPoint`：`AccessUnitFlags.of(true, true, false)` 且 `joinInfo == null` 抛 `IllegalArgumentException`。
- `payload_isDefensivelyCopied`：修改原数组后 `getPayload()` 不变。
- `dtsUnset_isReported`：`dtsUs = EncodedAccessUnit.DTS_UNSET` 时 `hasDtsUs()` 为 false；非负 dts 时为 true。
- `constructor_rejectsNegativeSequencePtsOrDts`：分别传入负值均抛 `IllegalArgumentException`。
- `streamKey_rejectsBlankComponents`：`sessionInstanceId` 为空、`streamId` 为空白均抛 `IllegalArgumentException`。
- `clientState_hasExactlySixStates`：按顺序断言六个枚举值。

- [ ] **Step 2（测试 agent）：运行红测**

Run: `./gradlew :app:testDebugUnitTest --tests "com.obby.android.localscreenshare.core.model.*" --offline --console=plain`

Expected: `compileDebugUnitTestJavaWithJavac` 失败，错误为找不到 `StreamDescriptor`、`EncodedAccessUnit` 等符号；记录该输出为红测证据。

- [ ] **Step 3（Pro）：只写生产实现**

实现上述九个模型类。`configHash` 使用 `java.security.MessageDigest` 的 SHA-256，对 `codecId`、`mime`、`profile`、`level`、coded size、rotation、`bitstreamFormat`、按顺序排列的 `CodecConfigPart`（id + payload hex）以及 `colorInfo` 做规范化拼接；不包含 `configEpoch` 和 visible size。所有集合与字节数组做防御性复制。Pro 不运行 Gradle。

- [ ] **Step 4（验证 agent）：编译并跑绿测**

Run: `./gradlew :app:testDebugUnitTest --tests "com.obby.android.localscreenshare.core.model.*" --offline --console=plain`

Expected: `BUILD SUCCESSFUL`，两个测试类全部通过。若只有导入/签名接线错误，验证 agent 可最小修复；语义失败退回 Pro。

- [ ] **Step 5（审查 agent）：契约审查**

检查：描述符不持有 Surface/线程/编解码器；`configHash` 不包含运行参数与 epoch；JPEG 空配置可用；payload 与配置部件不可变；`EncodedAccessUnit` 不解析负载。通过后进入提交。

- [ ] **Step 6（验证 agent）：提交**

```bash
git add app/src/main/java/com/obby/android/localscreenshare/core/model \
        app/src/main/java/com/obby/android/localscreenshare/core/codec/BitstreamFormat.java \
        app/src/test/java/com/obby/android/localscreenshare/core/model
git commit -m "feat: 添加编码核心数据契约"
```

## Task 2: 静态 Codec SPI 与注册表

**Files:**
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/CodecIds.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/CodecDescriptor.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/CodecRegistration.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/CodecRegistry.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/CodecNotFoundException.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/ConfigEpochException.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/CodecSwitchException.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/Encoder.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/EncoderFactory.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/EncoderOutput.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/Decoder.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/DecoderFactory.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/DecoderOutput.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/CodecPolicy.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/OverflowAction.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/TransportKind.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/Packetizer.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/codec/PacketizerFactory.java`
- Test: `app/src/test/java/com/obby/android/localscreenshare/core/codec/CodecRegistryTest.java`

**Interfaces:**
- Consumes: Task 1 的 `StreamKey`、`StreamDescriptor`、`StreamParameters`、`EncodedAccessUnit`、`BitstreamFormat`；`android.view.Surface`（仅签名，不在 JVM 测试中实例化）。
- Produces:
  - `CodecIds`：`public static final String JPEG = "jpeg"`、`H264 = "h264"`、`HEVC = "hevc"`、`AV1 = "av1"`。本批次只注册 JPEG；其余为契约保留常量。
  - `CodecDescriptor(String codecId, String mime, BitstreamFormat bitstreamFormat, boolean requiresSurfaceInput, boolean carriesConfigParts)`；getters；codecId/mime 非空。
  - `CodecRegistration(CodecDescriptor descriptor, EncoderFactory encoderFactory, DecoderFactory decoderFactory, CodecPolicy policy, @Nullable PacketizerFactory packetizerFactory)`；getters。
  - `CodecRegistry()`；`register(CodecRegistration)`（重复 codecId 抛 `IllegalStateException`）、`contains(String)`、`require(String)`（未知抛 `CodecNotFoundException`）、`getCodecIds()`（不可变集合）、`getPacketizer(String, TransportKind)`（未知 codec 抛 `CodecNotFoundException`，无封包器返回 `Optional.empty()`）。
  - `CodecNotFoundException extends RuntimeException`，getter `getCodecId()`。
  - `ConfigEpochException extends IllegalArgumentException`，构造参数 `(int expected, int actual)`，getters。
  - `CodecSwitchException extends RuntimeException`，构造参数 `(String message, Throwable cause)`。
  - `Encoder`：`start()`、`stop()`（停止输出但保留资源）、`updateParameters(StreamParameters)`、`requestSyncPoint()`、`close()`（释放资源）。
  - `EncoderFactory`：`Encoder create(StreamKey streamKey, StreamDescriptor descriptor, StreamParameters parameters, @Nullable Surface inputSurface, EncoderOutput output)`。
  - `EncoderOutput`：`void onAccessUnit(EncodedAccessUnit accessUnit)`。
  - `Decoder`：`configure(StreamDescriptor descriptor)`、`decode(EncodedAccessUnit accessUnit)`、`close()`。
  - `DecoderFactory`：`Decoder create(StreamKey streamKey, StreamDescriptor descriptor, DecoderOutput output)`。
  - `DecoderOutput`：`onDecodedFrame(EncodedAccessUnit accessUnit)`、`onDecoderError(Throwable error)`。
  - `CodecPolicy`：`OverflowAction getOverflowAction()`、`boolean isJoinPoint(EncodedAccessUnit accessUnit)`、`boolean requiresReconfigure(StreamDescriptor previous, StreamDescriptor next)`。
  - `OverflowAction` 枚举：`KEEP_LATEST_FRAME`、`DROP_UNTIL_JOIN_POINT`。
  - `TransportKind` 枚举：`TCP`、`GRPC_STREAM`、`RTP`。
  - `Packetizer`：`List<byte[]> packetize(EncodedAccessUnit accessUnit)`。
  - `PacketizerFactory`：`Packetizer create(CodecDescriptor descriptor, TransportKind transport)`。

- [ ] **Step 1（测试 agent）：写失败测试**

`CodecRegistryTest` 至少包含：

- `newRegistry_hasNoCodecs`：`assertTrue(registry.getCodecIds().isEmpty())`。
- `register_rejectsDuplicateCodecId`：同一 codecId 注册两次，第二次抛 `IllegalStateException`。
- `register_rejectsBlankCodecId`：`CodecDescriptor("", ...)` 抛 `IllegalArgumentException`。
- `require_unknownCodecThrowsWithCodecId`：`assertThrows(CodecNotFoundException.class, () -> registry.require("h264"))` 且异常消息包含 `h264`。
- `getPacketizer_unknownCodecThrows`、`getPacketizer_withoutPacketizerReturnsEmpty`：注册一个 `packetizerFactory == null` 的 codec，对 `TCP` 返回 `Optional.empty()`。
- `getCodecIds_isUnmodifiable`：尝试修改返回集合抛 `UnsupportedOperationException`。
- `concurrentLookups_doNotThrow`：8 个线程重复 `require("fake")`，收集异常，断言为空。

测试在本测试类内定义私有 fake，实现 `EncoderFactory`/`DecoderFactory`/`CodecPolicy`，不依赖 Android 运行时，也不依赖后续 Task 的测试支持类。

- [ ] **Step 2（测试 agent）：运行红测**

Run: `./gradlew :app:testDebugUnitTest --tests "com.obby.android.localscreenshare.core.codec.CodecRegistryTest" --offline --console=plain`

Expected: 编译失败，找不到 `CodecRegistry`、`CodecRegistration` 等符号。

- [ ] **Step 3（Pro）：只写生产实现**

实现上述 SPI 与注册表。注册表使用 `ConcurrentHashMap`；`getCodecIds()` 返回 `Collections.unmodifiableSet` 或不可变副本；未知 codec 一律显式失败，不猜测、不回退。`EncoderFactory` 保留 `@Nullable Surface` 参数，但本批次所有 JVM fake 与 JPEG 都传 null。Pro 不运行 Gradle。

- [ ] **Step 4（验证 agent）：跑绿测**

Run: `./gradlew :app:testDebugUnitTest --tests "com.obby.android.localscreenshare.core.codec.CodecRegistryTest" --offline --console=plain`

Expected: `BUILD SUCCESSFUL`，测试类全部通过。

- [ ] **Step 5（审查 agent）：扩展点审查**

检查：注册表无反射、`ServiceLoader`、动态加载；无 `if (codec == ...)` 特判；未知 codec 与空 id 显式失败；`PacketizerFactory` 只是契约，不实现 RTP；公共层不解析负载。通过后提交。

- [ ] **Step 6（验证 agent）：提交**

```bash
git add app/src/main/java/com/obby/android/localscreenshare/core/codec \
        app/src/test/java/com/obby/android/localscreenshare/core/codec
git commit -m "feat: 添加静态 Codec SPI 与注册表"
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
- Consumes: Task 1 的模型；Task 2 的 `CodecRegistration`、`CodecRegistry`、`Encoder`/`Decoder`/`CodecPolicy` 等。
- Produces:
  - `JpegCodec.MIME = "image/jpeg"`；`JpegCodec.registration()` 返回 `CodecRegistration`：codecId `CodecIds.JPEG`、bitstream `JPEG_FRAME`、`requiresSurfaceInput=false`、`carriesConfigParts=false`、policy 为 `JpegCodecPolicy`、encoder/decoder factory 为 JPEG 实现、packetizer 为 null。
  - `CodecRegistry.withDefaults()`：注册且仅注册 JPEG，返回新 registry。
  - `JpegFrameValidator.isCompleteFrame(@Nullable byte[] payload)`：payload 非 null、长度至少 4、以 `FF D8` 开头、以 `FF D9` 结尾时返回 true。
  - `JpegCodecPolicy.getOverflowAction()` 返回 `KEEP_LATEST_FRAME`；`isJoinPoint(au)` = `au.getFlags().isJoinPoint() && JpegFrameValidator.isCompleteFrame(au.getPayload())`；`requiresReconfigure(previous, next)` = `!next.sameDecodeConfig(previous)`。
  - `JpegEncoder(StreamKey, StreamDescriptor, StreamParameters, EncoderOutput)`；`start()`、`stop()`、`updateParameters`、`requestSyncPoint()`（no-op）、`close()`、`boolean offerJpegFrame(long ptsUs, byte[] jpegBytes)`。
  - `JpegEncoderFactory.create(...)`：返回 `JpegEncoder`，忽略 `inputSurface`；descriptor 的 codecId 不是 `jpeg` 时抛 `IllegalArgumentException`。
  - `JpegDecoder(StreamKey, StreamDescriptor, DecoderOutput)`；`configure`、`decode`、`close`。
  - `JpegDecoderFactory.create(...)`。

**本批次适配器边界：** `JpegEncoder` 接收现有采集路径已经压缩好的完整 JPEG 帧，不接触 `Bitmap`/`ImageReader`。把像素获取与 `Bitmap.compress` 迁入适配器需要改 `LssService`，超出本批次“不重写现有 JPEG 路径”的约束；该迁移在接线批次完成。`JpegDecoder` 通过 `DecoderOutput` 把压缩帧交给渲染侧，本批次不实现真实 `RenderTarget`。

- [ ] **Step 1（测试 agent）：写 fixtures 与失败测试**

`JpegFixtures` 提供：

- `VALID_FRAME = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9}`。
- `TRUNCATED_FRAME = new byte[]{(byte) 0xFF, (byte) 0xD8, 0x00}`。
- `EMPTY_FRAME = new byte[0]`。
- `validFrameWithPadding()`：在 EOI 前加入若干字节，仍以 `FF D9` 结尾，用于确认完整性检查不依赖固定长度。

`JpegCodecTest` 至少包含：

- `registration_hasJpegDescriptorAndPolicy`：`getCodecId().equals("jpeg")`、`getBitstreamFormat() == JPEG_FRAME`、`getPolicy() instanceof JpegCodecPolicy`。
- `registryWithDefaults_registersJpegOnly`：`getCodecIds()` 等于 `Set.of("jpeg")`。
- `validator_acceptsOnlyCompleteFrames`：valid true；truncated false；empty false；null false。
- `encoder_emitsJoinPointAccessUnitForCompleteFrame`：`start()` 后 `offerJpegFrame(1000, VALID_FRAME)` 返回 true；捕获一个 AU，断言 sequence 0、ptsUs 1000、`hasDtsUs()` 为 false、`isSyncPoint()` 与 `isJoinPoint()` 为 true、`getJoinInfo().getConfigEpoch()` 等于描述符 epoch、payload 等于 fixture。
- `encoder_dropsTruncatedAndEmptyFrames`：返回 false，且 `EncoderOutput` 未收到 AU。
- `encoder_sequenceIncrementsOnlyForEmittedFrames`：先发 truncated，再发两个 valid，sequence 为 0、1。
- `encoder_afterStop_dropsFramesButCloseStillWorks`：`stop()` 后 offer 返回 false；`close()` 不抛。
- `encoder_afterClose_dropsFrames`：`close()` 后 offer 返回 false。
- `encoder_updateParametersDoesNotEmitOrAdvanceEpoch`：调用后无新 AU，descriptor epoch 不变。
- `policy_flaggedJoinPointWithTruncatedPayloadIsNotJoinPoint`：flags 为 join，但 payload 截断，`isJoinPoint` 返回 false。
- `decoder_forwardsPayloadAfterConfigure`：`configure` + `decode` 后 `DecoderOutput.onDecodedFrame` 收到同一 AU。
- `decoder_reportsErrorBeforeConfigure`：未 configure 直接 decode，`onDecoderError` 被调用，`onDecodedFrame` 未调用。

- [ ] **Step 2（测试 agent）：运行红测**

Run: `./gradlew :app:testDebugUnitTest --tests "com.obby.android.localscreenshare.core.codec.jpeg.JpegCodecTest" --offline --console=plain`

Expected: 编译失败，找不到 JPEG 适配类。

- [ ] **Step 3（Pro）：只写生产实现**

实现 JPEG 适配。`JpegEncoder` 只在 `start()` 后、未 `close()` 时接受完整帧；`sequence` 用 `AtomicLong` 从 0 开始，仅对实际输出的 AU 递增；join point 的 `JoinInfo` 使用描述符的 `configEpoch` 与 `configParts`；`dtsUs = EncodedAccessUnit.DTS_UNSET`。JPEG 每帧都是一次独立解码单元，因此 `syncPoint=true`、`joinPoint=true`。公共层不解析 JPEG；完整性检查只存在于 `JpegFrameValidator`。在 `CodecRegistry` 中新增 `withDefaults()`，只注册 JPEG。Pro 不运行 Gradle。

- [ ] **Step 4（验证 agent）：跑绿测**

Run: `./gradlew :app:testDebugUnitTest --tests "com.obby.android.localscreenshare.core.codec.jpeg.JpegCodecTest" --offline --console=plain`

Expected: `BUILD SUCCESSFUL`，全部通过。若失败仅在导入/签名，最小修复；语义失败退回 Pro。

- [ ] **Step 5（审查 agent）：JPEG 边界审查**

检查：公共层没有 JPEG 解析；空配置可用；截断/空帧不会产生 AU；每帧独立且可加入；没有把 `secure` 或 UI 字段塞进核心模型；`withDefaults()` 没有注册 H.264。通过后提交。

- [ ] **Step 6（验证 agent）：提交**

```bash
git add app/src/main/java/com/obby/android/localscreenshare/core/codec/jpeg \
        app/src/main/java/com/obby/android/localscreenshare/core/codec/CodecRegistry.java \
        app/src/test/java/com/obby/android/localscreenshare/core/codec/jpeg \
        app/src/test/java/com/obby/android/localscreenshare/core/support/JpegFixtures.java
git commit -m "feat: 添加 JPEG 参考适配"
```

## Task 4: 有界队列与客户端发送会话

**Files:**
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/stream/StreamQueueLimits.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/stream/QueueOfferResult.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/stream/BoundedAccessUnitQueue.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/stream/ClientSendStats.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/stream/MediaTransport.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/stream/ClientSendSession.java`
- Test: `app/src/test/java/com/obby/android/localscreenshare/core/stream/BoundedAccessUnitQueueTest.java`
- Test: `app/src/test/java/com/obby/android/localscreenshare/core/stream/ClientSendSessionTest.java`
- Test support: `app/src/test/java/com/obby/android/localscreenshare/core/support/FakeCodec.java`

**Interfaces:**
- Consumes: Task 1 的 `EncodedAccessUnit`、`ClientState`；Task 2 的 `CodecRegistry`、`CodecPolicy`、`OverflowAction`；Task 3 的 JPEG 注册。
- Produces（测试支持）：`FakeCodec` 提供 `fake` codecId、`video/fake` mime、`ANNEX_B`、无配置部件、`DROP_UNTIL_JOIN_POINT` 策略；`FakeEncoder` 可手动 `emit(boolean joinPoint, byte[] payload)`，并记录 `syncRequestCount`、`closed`；工厂暴露最新 encoder 引用。
- Produces:
  - `StreamQueueLimits(long maxBytes, int maxUnits, long maxAgeMs)`；getters；`StreamQueueLimits.defaults()` 返回约 4 MiB、120 个 AU、2000 ms 的临时值（注释说明需实测，不在本批次当作生产结论）。
  - `QueueOfferResult` 枚举：`ACCEPTED`、`OVERFLOW_RESYNC`、`REJECTED_NOT_JOIN_POINT`、`REJECTED_TOO_LARGE`、`REJECTED_NOT_READY`、`REJECTED_EPOCH`、`REJECTED_CLOSED`。
  - `BoundedAccessUnitQueue(StreamQueueLimits, OverflowAction)`；`offer(EncodedAccessUnit, long nowMs)`、`poll(long nowMs)`、`size()`、`bytes()`、`droppedUnits()`、`droppedBytes()`、`clear()`。
  - `ClientSendStats`：`getQueuedUnits()`、`getQueuedBytes()`、`getDroppedUnits()`、`getResyncCount()`、`getSentUnits()`。
  - `MediaTransport`：`void send(EncodedAccessUnit accessUnit) throws IOException`、`void close()`。
  - `ClientSendSession(String clientId, StreamKey streamKey, CodecRegistry registry, StreamQueueLimits limits)`；`getClientId()`、`getState()`、`getCurrentDescriptor()`、`boolean onDescriptor(StreamDescriptor)`、`QueueOfferResult offer(EncodedAccessUnit, long nowMs)`、`EncodedAccessUnit poll(long nowMs)`、`boolean drainOne(MediaTransport, long nowMs) throws IOException`、`ClientSendStats getStats()`、`void detach()`、`boolean isDetached()`。

**队列语义（必须由测试固定）：**
- 队列同时受字节数、单元数和驻留时间约束；`poll(nowMs)` 先丢弃超过 `maxAgeMs` 的队头。
- 任何动作下单个 AU 超过 `maxBytes` 都返回 `REJECTED_TOO_LARGE`，不保留该 AU。
- `KEEP_LATEST_FRAME` 溢出：清空队列，只保留最新一个 AU；若最新 AU 本身超过 `maxBytes`，清空后返回 `REJECTED_TOO_LARGE`。
- `DROP_UNTIL_JOIN_POINT` 溢出：清空队列；来者是 join point 则保留并返回 `OVERFLOW_RESYNC`，否则返回 `REJECTED_NOT_JOIN_POINT`。
- 会话初始为 `WAIT_CONFIG`；`onDescriptor` 首次成功后进入 `WAIT_RANDOM_ACCESS`。
- 晚加入：`WAIT_RANDOM_ACCESS` 状态下只接受当前 epoch 的 join point；非 join point 返回 `REJECTED_NOT_JOIN_POINT`。
- `poll` 返回当前 epoch 的 join point 时，会话从 `WAIT_RANDOM_ACCESS` 或 `RESYNCING` 进入 `STREAMING`。
- 队列溢出返回 `OVERFLOW_RESYNC` 时，会话进入 `RESYNCING` 并累加 `resyncCount`；之后仍只接受 join point，直到 `poll` 返回 join point。
- `onDescriptor`：旧 epoch 返回 false 且忽略；同 epoch 同 hash 返回 false；同 epoch 不同 hash 抛 `IllegalArgumentException`；新 epoch 清空队列、重建队列策略、进入 `WAIT_RANDOM_ACCESS` 并返回 true。
- `detach()` 幂等；detach 后 `offer` 返回 `REJECTED_CLOSED`，`poll` 返回 null。
- `drainOne` 的 `MediaTransport.send` 抛 `IOException` 时，会话 detach 并重新抛出异常，避免坏客户端拖住 Hub。

- [ ] **Step 1（测试 agent）：写失败测试**

`BoundedAccessUnitQueueTest` 至少包含：

- `offer_rejectsNull`：抛 `IllegalArgumentException`。
- `tooLargeAu_isRejected`：单个 AU payload 大于 `maxBytes`，返回 `REJECTED_TOO_LARGE`，`size()==0`。
- `poll_returnsFifoAndDecrementsBytes`：两个 AU，poll 顺序与 `bytes()` 递减正确。
- `keepLatestOverflow_evictsAllAndKeepsNewest`：limits 为 1 个单元，先入非 join，再入 join；`size()==1`、队内为 join、返回 `OVERFLOW_RESYNC`、`droppedUnits()==1`。
- `dropUntilJoinOverflow_dropsQueuedAndRejectsNonJoin`：action 为 `DROP_UNTIL_JOIN_POINT`，溢出后非 join 返回 `REJECTED_NOT_JOIN_POINT` 且队列清空；随后 join 返回 `OVERFLOW_RESYNC` 并保留。
- `poll_dropsExpiredHeadUnits`：`maxAgeMs=100`，入队后 `poll(200)` 丢弃并返回 null，`droppedUnits()` 增加。
- `clear_resetsSizeButNotDroppedCounters`：先制造一次溢出，`clear()` 后 `size()==0`、`bytes()==0`、`droppedUnits()` 不变。

`ClientSendSessionTest` 至少包含：

- `offerBeforeDescriptor_returnsNotReady`：初始 `WAIT_CONFIG`，`offer` 返回 `REJECTED_NOT_READY`。
- `lateJoin_ignoresNonJoinPointUntilJoinPoint`：`onDescriptor` 后先 offer 非 join（返回 `REJECTED_NOT_JOIN_POINT`），再 offer join；`poll` 返回 join，状态变 `STREAMING`。
- `epochChange_clearsQueueAndWaitsAgain`：先入 join 并 poll 到 `STREAMING`；新 epoch 描述符 `onDescriptor` 返回 true，状态 `WAIT_RANDOM_ACCESS`，旧队列为空；旧 epoch 非 join 被拒绝。
- `duplicateAndStaleDescriptorIgnored`：同 epoch 同 hash 返回 false；旧 epoch 返回 false，状态不变。
- `sameEpochDifferentConfigThrows`：同 epoch 改 codedWidth，抛 `IllegalArgumentException`。
- `slowClientOverflow_returnsResyncAndKeepsNewestJpegFrame`：limits 为 1 单元；先入 join 并 poll，再入非 join 触发溢出；返回 `OVERFLOW_RESYNC`、状态 `RESYNCING`、队列保留最新 join；下一次 `poll` 回到 `STREAMING`。
- `dropUntilJoinOverflow_waitsForNextJoinPoint`：使用 fake 参考帧策略；溢出后非 join 返回 `REJECTED_NOT_JOIN_POINT`，join 到达后 `poll` 恢复 `STREAMING`。
- `detach_isIdempotentAndRejectsNewUnits`：连续 `detach()` 两次不抛；`offer` 返回 `REJECTED_CLOSED`；`poll` 返回 null。
- `transportFailure_detachesSession`：fake transport 抛 `IOException`，`drainOne` 重新抛出后 `isDetached()` 为 true。
- `stats_reportQueuedDroppedResyncAndSentCounts`：逐项断言。

测试使用 `JpegFixtures.VALID_FRAME` 与 `FakeCodec` 的参考帧策略；`FakeCodec` 在本 Task 创建，不得调用 Android `Bitmap`。

- [ ] **Step 2（测试 agent）：运行红测**

Run: `./gradlew :app:testDebugUnitTest --tests "com.obby.android.localscreenshare.core.stream.BoundedAccessUnitQueueTest" --tests "com.obby.android.localscreenshare.core.stream.ClientSendSessionTest" --offline --console=plain`

Expected: 编译失败，找不到队列与会话类。

- [ ] **Step 3（Pro）：只写生产实现**

实现 `BoundedAccessUnitQueue` 与 `ClientSendSession`。所有可变状态用单一 `synchronized` 锁保护；`poll` 的计算与状态迁移在同一临界区完成；队列内部保存 `enqueuedAtMs`，不依赖 `ptsUs` 判断驻留时间。Pro 不运行 Gradle。

- [ ] **Step 4（验证 agent）：跑绿测**

Run: `./gradlew :app:testDebugUnitTest --tests "com.obby.android.localscreenshare.core.stream.BoundedAccessUnitQueueTest" --tests "com.obby.android.localscreenshare.core.stream.ClientSendSessionTest" --offline --console=plain`

Expected: `BUILD SUCCESSFUL`，两个测试类全部通过。

- [ ] **Step 5（审查 agent）：队列与状态审查**

检查：所有发送队列有界；JPEG 溢出只保留最新完整帧；参考帧策略不丢中间帧后继续发送；晚加入不发送半截 GOP；null/空 payload 不会进入队列；单会话锁不覆盖 Hub 全局。通过后提交。

- [ ] **Step 6（验证 agent）：提交**

```bash
git add app/src/main/java/com/obby/android/localscreenshare/core/stream/StreamQueueLimits.java \
        app/src/main/java/com/obby/android/localscreenshare/core/stream/QueueOfferResult.java \
        app/src/main/java/com/obby/android/localscreenshare/core/stream/BoundedAccessUnitQueue.java \
        app/src/main/java/com/obby/android/localscreenshare/core/stream/ClientSendStats.java \
        app/src/main/java/com/obby/android/localscreenshare/core/stream/MediaTransport.java \
        app/src/main/java/com/obby/android/localscreenshare/core/stream/ClientSendSession.java \
        app/src/test/java/com/obby/android/localscreenshare/core/stream/BoundedAccessUnitQueueTest.java \
        app/src/test/java/com/obby/android/localscreenshare/core/stream/ClientSendSessionTest.java \
        app/src/test/java/com/obby/android/localscreenshare/core/support/FakeCodec.java
git commit -m "feat: 添加有界发送队列与客户端发送会话"
```

## Task 5: 单活动流运行时与 StreamHub

**Files:**
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/stream/StreamRuntime.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/stream/StreamRuntimeListener.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/stream/ClientCapabilities.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/stream/JoinDecision.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/stream/StreamHub.java`
- Test: `app/src/test/java/com/obby/android/localscreenshare/core/stream/StreamRuntimeTest.java`
- Test: `app/src/test/java/com/obby/android/localscreenshare/core/stream/StreamHubTest.java`
- Test support: `app/src/test/java/com/obby/android/localscreenshare/core/support/MutableClock.java`
- Modify: `app/src/test/java/com/obby/android/localscreenshare/core/support/FakeCodec.java`（扩展 encoder 工厂的最新实例引用）

**Interfaces:**
- Consumes: Task 1–4 的全部核心类型与测试支持 `FakeCodec`；`java.util.function.LongSupplier`；`android.view.Surface`（可为 null）。
- Produces:
  - `StreamRuntimeListener`：`void onAccessUnit(EncodedAccessUnit accessUnit)`、`void onDescriptorChanged(StreamDescriptor descriptor)`。
  - `StreamRuntime(CodecRegistry, StreamKey, StreamDescriptor, StreamParameters, @Nullable Surface)`；包内测试构造函数额外接收 `LongSupplier nowMs` 与 `long joinPointMinIntervalMs`。
  - `StreamRuntime` 方法：`start()`、`close()`、`isClosed()`、`getDescriptor()`、`getConfigEpoch()`、`getParameters()`、`updateParameters(StreamParameters)`、`boolean requestJoinPoint()`、`void switchDescriptor(StreamDescriptor next)`、`addListener(StreamRuntimeListener)`、`removeListener(StreamRuntimeListener)`。
  - `ClientCapabilities(Set<String> codecIds)`；`supports(String codecId)`、`getCodecIds()`。
  - `JoinDecision`：`getStatus()` 返回 `ACCEPTED`、`REQUIRES_EXPLICIT_FALLBACK`、`REJECTED`；`getSession()`、`getFallbackCodecId()`、`getReason()`；静态工厂 `accepted(ClientSendSession)`、`requiresFallback(String codecId, String reason)`、`rejected(String reason)`。
  - `StreamHub(CodecRegistry)`、`StreamHub(CodecRegistry, StreamQueueLimits)`；`register(StreamRuntime)`、`unregister(StreamKey)`、`activate(StreamKey)`、`getActiveStream()`、`JoinDecision join(String clientId, ClientCapabilities capabilities)`、`detach(String clientId)`、`getSessionCount()`。
  - `StreamHub` 实现 `StreamRuntimeListener`：只广播活动流的 AU；非活动流 AU 忽略。

**运行时语义（必须由测试固定）：**
- `start()` 通过 registry 创建 encoder 并启动；`close()` 幂等并关闭活动 encoder 与尚未释放的 retired encoder。
- `updateParameters` 只更新运行参数，不推进 epoch、不重建 encoder、不产生 AU。
- `requestJoinPoint` 合并并限频：在收到 join point 之前，重复请求只向 encoder 转发一次；收到 join point 后允许下一次请求；最小间隔由构造参数控制。
- `switchDescriptor`：只接受同一 `StreamKey`、`configEpoch == 当前 + 1`、且解码配置确实变化的新描述符；跳号/重复抛 `ConfigEpochException`，未变化抛 `IllegalArgumentException`。先创建并启动新 encoder，预热失败抛 `CodecSwitchException` 且保留旧 encoder；成功后调用旧 encoder 的 `stop()`、替换活动 encoder，旧 encoder 的迟到 AU 通过 generation 丢弃。旧 encoder 保持资源直到新 epoch 的首个 join point 成功广播后再 `close()`；本批次不实现客户端恢复 ack 与超时，ack/超时留到接线批次。
- `StreamHub` 只允许一条活动流；重复注册、未知激活流、已有订阅者时切换活动流都显式失败；同一 `clientId` 重复 join 也显式拒绝，不覆盖已有会话。
- `join`：无活动流拒绝；客户端支持当前 codec 则创建并返回会话；否则若 registry 有 JPEG 且“现有所有客户端 + 新客户端”都支持 JPEG，返回 `REQUIRES_EXPLICIT_FALLBACK`，不自动切换、不静默降级；其余拒绝。
- 新客户端加入不改变已有会话对象、描述符、epoch 或队列。
- 广播对每个会话独立 `offer`，慢客户端溢出只影响自身；Hub 不因单个会话异常中断其他会话。
- `StreamRuntime` 通知 listener 时逐个捕获异常，不让一个 listener 阻断其他 listener 或编码输出。
- `onDescriptorChanged` 通知所有会话并更新活动描述符，但保留会话与传输对象。

- [ ] **Step 1（测试 agent）：扩展 fake 与写失败测试**

`FakeCodec` 已由 Task 4 创建；本 Task 扩展它，至少提供：`fake` codecId、`video/fake` mime、`ANNEX_B`、无配置部件、`DROP_UNTIL_JOIN_POINT` 策略；`FakeEncoder` 可手动 `emit(boolean joinPoint, byte[] payload)`、记录 `syncRequestCount`、`stopped`、`closed`；工厂保留并暴露最新 encoder 引用（例如 `getLastEncoder()`），供端到端测试在新 epoch 发射 AU；`FakeDecoder` 记录收到的 AU；`MutableClock` 可手动推进毫秒。

`StreamRuntimeTest` 至少包含：

- `start_createsEncoderAndForwardsAccessUnits`：start 后 fake emit，listener 收到同一 AU。
- `listenerException_doesNotStopOtherListeners`：第一个 listener 抛异常，第二个仍收到 AU。
- `updateParameters_doesNotAdvanceEpochOrRecreateEncoder`：epoch 不变、encoder 实例不变、listener 未收到 AU。
- `requestJoinPoint_isMergedUntilJoinPoint`：将 `joinPointMinIntervalMs` 设为 0；连续调用 3 次，`syncRequestCount==1`；fake emit join 后再调用，计数为 2。
- `requestJoinPoint_isRateLimited`：join 后立即请求被限频；时钟推进超过间隔后请求成功。
- `switchDescriptor_advancesEpochAndReplacesEncoder`：epoch+1、codedSize 改变；新 encoder 实例、旧 encoder `stopped` 但尚未 `closed`、listener 收到新描述符。
- `oldEncoderReleasedAfterNewJoinPoint`：切换后旧 encoder 仍未 `closed`；新 encoder emit 新 epoch join 后，旧 encoder `closed` 为 true。
- `switchDescriptor_rejectsDuplicateAndSkippedEpoch`：同 epoch、epoch+2 均抛 `ConfigEpochException`，encoder 不变。
- `switchDescriptor_rejectsUnchangedDecodeConfig`：仅改 epoch、hash 相同，抛 `IllegalArgumentException`。
- `switchDescriptor_prewarmFailureKeepsOldEncoder`：fake factory 对新描述符抛异常，抛 `CodecSwitchException`，旧 encoder 未 stop/closed、旧描述符不变。
- `staleEncoderAccessUnit_isDroppedAfterSwitch`：旧 encoder 在切换后 emit，listener 不收到。
- `close_isIdempotentAndClosesEncoder`：连续 close 两次不抛，fake encoder `closed` 为 true。

`StreamHubTest` 至少包含：

- `register_rejectsDuplicateAndUnregisterIsIdempotent`。
- `activate_requiresRegisteredStream`。
- `activate_differentStreamWithSessionsRejected`：已有订阅者时不能切换活动流。
- `join_withoutActiveStreamRejected`。
- `join_withCompatibleClientAttachesSession`：状态 `WAIT_RANDOM_ACCESS`，session count 1。
- `join_incompatibleClientRejectedWithoutChangingExisting`：已有客户端对象、状态、epoch 不变，新客户端不产生 session。
- `join_incompatibleButJpegFallbackPossibleRequiresExplicitFallback`：registry 同时注册 fake 与 JPEG；现有与新客户端都支持 JPEG，返回 `REQUIRES_EXPLICIT_FALLBACK` 且 `getFallbackCodecId()` 为 `jpeg`；活动流未切换。
- `newClientDoesNotChangeExistingSessionOrDescriptor`：记录 session 引用与描述符，join 后断言相同。
- `broadcast_isolatesSlowClientOverflow`：Hub limits 为 1 个单元；A 不 drain，B 每帧 drain；广播多帧后 A `RESYNCING`、B 全部收到；Hub 不抛异常。
- `descriptorChange_notifiesAllSessionsAndKeepsObjects`：session 引用不变，状态变为 `WAIT_RANDOM_ACCESS`。
- `detach_removesOnlyTargetSession`。
- `concurrentAttachDetach_doesNotThrowOrLoseSessions`：4 个线程各 join/detach 100 次，最终 `getSessionCount()==0`，异常列表为空。

- [ ] **Step 2（测试 agent）：运行红测**

Run: `./gradlew :app:testDebugUnitTest --tests "com.obby.android.localscreenshare.core.stream.StreamRuntimeTest" --tests "com.obby.android.localscreenshare.core.stream.StreamHubTest" --offline --console=plain`

Expected: 编译失败，找不到 `StreamRuntime`、`StreamHub`、`JoinDecision` 等。

- [ ] **Step 3（Pro）：只写生产实现**

实现运行时与 Hub。`StreamRuntime` 用 generation 区分 encoder 实例；`switchDescriptor` 严格校验 epoch 与配置变化；`StreamHub` 用 `ConcurrentHashMap<String, ClientSendSession>` 保存会话、用快照遍历广播，不在 Hub 全局锁内调用 `offer`。Pro 不运行 Gradle。

- [ ] **Step 4（验证 agent）：跑绿测**

Run: `./gradlew :app:testDebugUnitTest --tests "com.obby.android.localscreenshare.core.stream.StreamRuntimeTest" --tests "com.obby.android.localscreenshare.core.stream.StreamHubTest" --offline --console=plain`

Expected: `BUILD SUCCESSFUL`，两个测试类全部通过。

- [ ] **Step 5（审查 agent）：运行时与并发审查**

检查：公共层无 codec 特判；切换预失败保留旧 encoder；旧 generation AU 不泄漏；Hub 不自动降级、不重建已有会话；广播不阻塞编码器；并发 attach/detach 无数据竞争。通过后提交。

- [ ] **Step 6（验证 agent）：提交**

```bash
git add app/src/main/java/com/obby/android/localscreenshare/core/stream/StreamRuntime.java \
        app/src/main/java/com/obby/android/localscreenshare/core/stream/StreamRuntimeListener.java \
        app/src/main/java/com/obby/android/localscreenshare/core/stream/ClientCapabilities.java \
        app/src/main/java/com/obby/android/localscreenshare/core/stream/JoinDecision.java \
        app/src/main/java/com/obby/android/localscreenshare/core/stream/StreamHub.java \
        app/src/test/java/com/obby/android/localscreenshare/core/stream/StreamRuntimeTest.java \
        app/src/test/java/com/obby/android/localscreenshare/core/stream/StreamHubTest.java \
        app/src/test/java/com/obby/android/localscreenshare/core/support/FakeCodec.java \
        app/src/test/java/com/obby/android/localscreenshare/core/support/MutableClock.java
git commit -m "feat: 添加单活动流运行时与 StreamHub"
```

## Task 6: 客户端媒体状态机

**Files:**
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/client/ClientMediaSession.java`
- Create: `app/src/main/java/com/obby/android/localscreenshare/core/client/ClientStateListener.java`
- Test: `app/src/test/java/com/obby/android/localscreenshare/core/client/ClientMediaSessionTest.java`

**Interfaces:**
- Consumes: Task 1 的 `ClientState`、`StreamDescriptor`、`EncodedAccessUnit`；Task 2 的 `CodecRegistry`、`Decoder`；Task 3 的 JPEG；Task 5 的 `ClientCapabilities`、`FakeCodec`。
- Produces:
  - `ClientStateListener`：`void onStateChanged(ClientState previous, ClientState current)`。
  - `ClientMediaSession(String clientId, CodecRegistry registry, ClientCapabilities capabilities, Consumer<EncodedAccessUnit> frameConsumer, ClientStateListener listener)`；`getState()`、`getCurrentDescriptor()`、`boolean onDescriptor(StreamDescriptor)`、`boolean onAccessUnit(EncodedAccessUnit)`、`close()`、`isClosed()`。

**状态机语义（必须由测试固定）：**
- 初始 `NEGOTIATING`。
- `onDescriptor`：不支持 codec 时进入 `CLOSED` 并返回 false；首次支持则 `NEGOTIATING → WAIT_CONFIG → WAIT_RANDOM_ACCESS`；同 epoch 同 hash 返回 false；旧 epoch 返回 false；同 epoch 不同 hash 抛 `IllegalArgumentException`；新 epoch 关闭旧 decoder、创建/配置新 decoder，进入 `WAIT_RANDOM_ACCESS`；decoder 创建/配置异常进入 `RESYNCING`。
- `onAccessUnit`：null 抛 `IllegalArgumentException`；`WAIT_CONFIG` 丢弃并返回 false；`WAIT_RANDOM_ACCESS` 只接受当前 epoch 的 join point，进入 `STREAMING` 并调用 decoder；`STREAMING` 接受当前 epoch 的 AU；旧 epoch join point 丢弃且保持 `STREAMING`；未来 epoch 进入 `RESYNCING` 并等待描述符；`RESYNCING` 只有同 epoch join point 能恢复 `STREAMING`；decoder 报错进入 `RESYNCING`。
- `close()` 幂等，进入 `CLOSED` 并关闭 decoder；`CLOSED` 下所有输入丢弃。
- 每次状态变化都回调 listener，previous/current 不相等。

- [ ] **Step 1（测试 agent）：写失败测试**

`ClientMediaSessionTest` 至少包含：

- `newSession_isNegotiating`。
- `onDescriptor_entersWaitConfigThenWaitRandomAccess`：状态监听记录 `NEGOTIATING → WAIT_CONFIG → WAIT_RANDOM_ACCESS`。
- `emptyConfigJpegDescriptor_isAccepted`：JPEG 描述符无配置部件，仍进入 `WAIT_RANDOM_ACCESS`。
- `unsupportedCodec_closesSession`：capabilities 只有 jpeg，描述符为 fake/h264，状态 `CLOSED`、返回 false。
- `nonJoinFrameInWaitRandomAccessIsDropped`：返回 false，未调用 decoder。
- `matchingJoinPoint_transitionsToStreamingAndDecodes`：返回 true，状态 `STREAMING`，`frameConsumer` 收到同一 AU。
- `staleEpochJoinPoint_isDroppedWithoutStateChange`：旧 epoch join，返回 false，状态保持 `STREAMING`。
- `futureEpochAccessUnit_entersResyncingUntilDescriptor`：未来 epoch join，状态 `RESYNCING`；仅同 epoch join 不能恢复，先收到新描述符才进入 `WAIT_RANDOM_ACCESS`。
- `duplicateDescriptor_isIgnored`：同 epoch 同 hash 返回 false，无状态变化。
- `sameEpochDifferentConfigThrows`：抛 `IllegalArgumentException`。
- `newDescriptor_recreatesDecoderAndWaitsForNewJoinPoint`：旧 decoder 关闭，新 decoder 配置，状态 `WAIT_RANDOM_ACCESS`；新 join 后 `STREAMING`。
- `decoderFailure_entersResyncingAndRecoversAtJoinPoint`：fake decoder 在 decode 时抛异常，状态 `RESYNCING`；同 epoch join 恢复 `STREAMING`。
- `close_isIdempotentAndClosesDecoder`。
- `stateListener_receivesOnlyDistinctTransitions`。

- [ ] **Step 2（测试 agent）：运行红测**

Run: `./gradlew :app:testDebugUnitTest --tests "com.obby.android.localscreenshare.core.client.ClientMediaSessionTest" --offline --console=plain`

Expected: 编译失败，找不到 `ClientMediaSession`、`ClientStateListener`。

- [ ] **Step 3（Pro）：只写生产实现**

实现 `ClientMediaSession`。状态迁移集中在单一同步方法；decoder 回调异常转为 `RESYNCING`；不得解析负载或从 MIME 猜测 profile/level；不得在状态机里写 codec 名称分支，加入判定通过 `CodecPolicy.isJoinPoint`。Pro 不运行 Gradle。

- [ ] **Step 4（验证 agent）：跑绿测**

Run: `./gradlew :app:testDebugUnitTest --tests "com.obby.android.localscreenshare.core.client.ClientMediaSessionTest" --offline --console=plain`

Expected: `BUILD SUCCESSFUL`，全部通过。

- [ ] **Step 5（审查 agent）：状态机审查**

检查：六状态完整；`RESYNCING` 只通过同 epoch join point 或新 `WAIT_CONFIG` 恢复；跨 epoch 先配置后加入；重复/过期 epoch 行为明确；无 codec 特判；无负载解析。通过后提交。

- [ ] **Step 6（验证 agent）：提交**

```bash
git add app/src/main/java/com/obby/android/localscreenshare/core/client \
        app/src/test/java/com/obby/android/localscreenshare/core/client
git commit -m "feat: 添加客户端媒体状态机"
```

## Task 7: JVM 端到端与旧路径兼容

**Files:**
- Test: `app/src/test/java/com/obby/android/localscreenshare/core/stream/StreamFrameworkEndToEndTest.java`
- Test support: `app/src/test/java/com/obby/android/localscreenshare/core/support/FakeTransport.java`
- Test support: `app/src/test/java/com/obby/android/localscreenshare/core/support/CapturingJpegEncoderFactory.java`
- Modify: `app/src/test/java/com/obby/android/localscreenshare/core/support/FakeCodec.java`（如 Task 5 的最小 fake 不足以支持端到端）
- 不改生产文件；不新增生产接线。

**Interfaces:**
- Consumes: Task 1–6 全部类型；`FakeCodec`、`MutableClock`、`JpegFixtures`。
- Produces:
  - `FakeTransport`：`List<EncodedAccessUnit> sent`、`boolean sendFailure`；`send` 记录或抛 `IOException`，`close` 幂等。
  - `CapturingJpegEncoderFactory`：包装 `JpegEncoderFactory`，`create` 后暴露最新 `JpegEncoder`，测试用它调用 `offerJpegFrame`。
  - 端到端测试基础设施：`StreamRuntime` + `StreamHub` + 两个 `ClientSendSession` + `FakeTransport` + 两个 `ClientMediaSession`。
  - 不新增生产 API；若端到端暴露契约缺陷，只允许回到对应 Task 修复并补测试，不扩大范围。

- [ ] **Step 1（测试 agent）：写端到端测试**

`StreamFrameworkEndToEndTest` 至少包含：

1. `twoClientsJoiningAtDifferentTimes_receiveOnlyJoinPointAndLaterAUs`
   - 活动流为 fake codec；客户端 A 先 join，客户端 B 在第一个 join point 之后 join。
   - 发射序列 0（非 join）、1（join）；A 收到 1，B 此时尚未加入。
   - B join 后发射 2（非 join）、3（join）、4（非 join）。
   - 排空 A/B transport，并喂给各自 `ClientMediaSession`。
   - 断言：A 收到序列 `1,2,3,4`；B 只收到 `3,4`；两者最终 `STREAMING`；B 的 transport 中不存在序列 0/1/2。

2. `slowClientOverflow_doesNotBlockOrDropFramesForOtherClient`
   - A 的队列 limits 为 2 个 AU / 128 字节；B 每帧立即 drain。
   - 发射 join，A 不 drain；连续发射非 join 直到 A `RESYNCING`，断言 A `getStats().getDroppedUnits() > 0`，B 收到全部帧。
   - 发射下一个 join，A drain 后恢复 `STREAMING`；B 仍收到该 join 及后续帧。
   - 断言 Hub 广播无异常，B 的序列连续且包含全部发射的 join。

3. `configEpochSwitch_keepsNetworkObjectsAndRecoversBothClients`
   - 记录 A/B 的 `ClientSendSession` 引用与 `FakeTransport` 引用。
   - `runtime.switchDescriptor` 到 epoch+1、不同 coded size；断言活动 runtime 对象、session 引用、transport 引用均未变化。
   - Hub 通知后，两个 `ClientSendSession` 状态为 `WAIT_RANDOM_ACCESS`；两个 `ClientMediaSession` 收到新描述符后为 `WAIT_RANDOM_ACCESS`。
   - 发射新 epoch join 并 drain，两个客户端恢复 `STREAMING`，`frameConsumer` 收到新 epoch AU；旧 epoch join 不进入新队列。

4. `unsupportedClient_doesNotDegradeExistingClient`
   - registry 同时注册 fake 与 JPEG；现有 A 只支持 fake；新客户端只支持 jpeg。
   - `hub.join` 返回 `REJECTED`；A 的 session 引用、状态、描述符 epoch 不变；没有自动切换 codec。
   - 另建场景：A 与新客户端都支持 jpeg，`hub.join` 返回 `REQUIRES_EXPLICIT_FALLBACK` 且 `getFallbackCodecId()=="jpeg"`；活动流与 A 状态仍不变。

5. `duplicateEpochSwitch_isRejectedWithoutTouchingSessions`
   - 对当前 epoch 调用 `switchDescriptor` 抛 `ConfigEpochException`；session/transport 引用与状态不变。

6. `legacyJpegDescriptorWithoutConfigParts_worksEndToEnd`
   - 用 `JpegCodec.registration()` 取得 descriptor、decoder factory 与 policy，再用 `CapturingJpegEncoderFactory` 包装 `JpegEncoderFactory`，构造 `CodecRegistration` 注册到空 `CodecRegistry`；保留最新 `JpegEncoder` 引用，以便调用 `offerJpegFrame`。
   - JPEG 描述符不配置任何 `CodecConfigPart`；一个客户端从 join point 进入 `STREAMING`，验证空配置不会阻断端到端。

- [ ] **Step 2（测试 agent）：运行并记录红/绿**

Run: `./gradlew :app:testDebugUnitTest --tests "com.obby.android.localscreenshare.core.stream.StreamFrameworkEndToEndTest" --offline --console=plain`

Expected: 先出现失败或编译错误（缺 `FakeTransport` 或端到端装配问题）；补齐测试支持类后，若生产契约有缺陷，记录失败测试名与断言，退回对应 Pro Task 修复；修复后必须重新跑本命令并得到 `BUILD SUCCESSFUL`。

- [ ] **Step 3（集成 agent）：旧路径兼容检查**

Run:

```bash
git diff --name-only -- app/src/main/java/com/obby/android/localscreenshare/service \
    app/src/main/java/com/obby/android/localscreenshare/client \
    app/src/main/java/com/obby/android/localscreenshare/server \
    app/src/main/proto
```

Expected: 输出为空。若不为空，停止并说明是计划外的行为变更，不得提交。

再运行：

```bash
grep -R "Bitmap\|ImageReader\|MediaCodec\|MediaProjection\|android\.graphics\|android\.media" \
  app/src/main/java/com/obby/android/localscreenshare/core app/src/test/java/com/obby/android/localscreenshare/core || true
```

Expected: 生产 `core` 中只允许 `android.view.Surface` 出现在 `EncoderFactory` 签名；测试中不得出现 `Bitmap`/`ImageReader`/`MediaCodec`/`MediaProjection` 的实际调用。若出现其他 Android 依赖，回到对应 Task 修正。

- [ ] **Step 4（集成 agent）：全量 JVM 验证**

Run:

```bash
./gradlew :app:testDebugUnitTest --offline --console=plain
./gradlew :app:assembleDebug --offline --console=plain
```

Expected: 两条命令均 `BUILD SUCCESSFUL`；`ExampleUnitTest` 与全部新增测试通过；没有执行 `connectedAndroidTest`、adb、emulator 或设备访问。

- [ ] **Step 5（审查 agent）：全分支审查**

检查：
- 公共层没有 NAL/OBU/JPEG 解析、SPS/PPS 提取或帧类型推断。
- 没有 `if (codec == ...)` 公共分支；JPEG 差异只在 `core.codec.jpeg`。
- 没有多活动流调度、GPU Fanout、RTP 实现、H.264/HEVC/AV1 实现。
- `build.gradle`、`gradle/libs.versions.toml`、proto、`LssService`、`LssClientService` 无改动。
- Review Focus 六类输入都有通过测试。
- 测试只使用 fake codec/fake transport/JVM。

- [ ] **Step 6（集成 agent）：提交**

```bash
git add app/src/test/java/com/obby/android/localscreenshare/core/stream/StreamFrameworkEndToEndTest.java \
        app/src/test/java/com/obby/android/localscreenshare/core/support
git commit -m "test: 添加编码框架 JVM 端到端测试"
```

## 自审清单

- **Spec 覆盖：** 本计划覆盖 spec 第 4 节静态 SPI、第 5 节描述符/参数分离、第 6 节访问单元、第 7 节可加入点与六状态、第 8 节慢客户端与有界队列、第 9 节切换的配置/epoch 语义、第 10 节 TCP/gRPC 完整访问单元契约。未覆盖 H.264/HEVC/AV1、RTP/WebRTC、GPU Fanout、真实设备验证，已在 Global Constraints 明确排除；不能把 spec 阶段 1 写成全部完成。
- **已声明边界：** 本批次 JPEG 适配器接收已压缩帧，不接管像素路径；`DecoderOutput` 代表渲染目标接口，真实 `RenderTarget` 未实现；单流不兼容时只实现“显式兼容回退”与“拒绝”，快照路径未实现；切换以新 epoch 首个 join point 作为旧 encoder 释放信号，未实现客户端恢复 ack 与超时。以上均写在各 Task 与 Global Constraints 中，不能当作已完成。
- **步骤粒度：** 每个 Task 都是“测试 agent 写红测 → Pro 写实现 → 验证 agent 跑绿测 → 审查 agent 审查 → 提交”，不把实现细节写成代码转录；测试断言与命令可直接执行。
- **类型一致性：** 后续 Task 只使用前面 `Produces` 中定义的类名、方法名与参数类型；`StreamDescriptor`、`EncodedAccessUnit`、`CodecRegistry`、`ClientSendSession`、`StreamRuntime`、`StreamHub`、`ClientMediaSession` 的签名在各 Task 间保持一致。
- **Review Focus：** 空配置、未知 codec、重复/过期 epoch、队列溢出、null/空 payload、并发 attach/detach 都分配了具体测试与 Task。
- **比例：** 计划给出接口、关键语义、测试名称与命令，不复制生产实现；具体算法留给 Pro 按签名与测试完成。

## Execution Handoff

计划保存后先等待用户审核。用户已指定执行方式：Pro 写核心生产代码且不编译，当前侧 agent 写测试、fixtures、集成、验证、文档与提交。审核通过后按 Task 1 → Task 7 顺序执行；每个 Task 的失败测试必须先于 Pro 实现，且每个 Task 必须通过审查后才提交。
