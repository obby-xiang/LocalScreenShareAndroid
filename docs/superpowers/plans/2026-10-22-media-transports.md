# 双媒体传输（裸 TCP + UDP/RTP 风格分包）实施计划（2026-10-22）

> 基线：HEAD `edd5c19`，1506 tests / 149 classes
> 依据：主 K3 的 6 条已定裁决；[传输方式调研](../../transport-research.md)；[命名与包结构规范](../../naming-conventions.md)；[决策看板](../../decision-board.md) 的 DB-NET-001/002、DB-OBS-001/002、DB-SEC-005
> 状态：B1 已落地（2026-10-22）；B2–B6 待实施。批次划分见第 7 章。

## 0. 现状前提

批次一（抽象层 + gRPC 包装）已落地：`core/stream/MediaTransport.java` 已定义 `sendDescriptor / sendAccessUnit / close / abort` 与四条契约；`transport/GrpcMediaTransport.java` 是第一个实现（含音频 AudioTransport、sendPong、sendApprovalPending，同一把 sendLock 串行）；`StreamHub.join(key, caps, transport)` 与 `ClientSendSession` 已只面向接口，发送侧不需为新增传输改结构。

缺口（B1 要补的硬阻塞）：接收侧仍直接吃 proto（`ClientMediaReceiver` 只接受 `ServerMessage`）；session 的 transport 是 `final` 且失败即 detach（`StreamHub.drainOne` 会顺手摘映射）——一次新媒体通道故障会连带杀掉控制面 RPC。

## 1. 七个设计题的结论

| 题 | 结论 | 理由 |
|---|---|---|
| 抽象层形态 | 不改 `MediaTransport`；新增 `MediaTransportRecovery`（恢复钩子）与 `MediaChannelListener`（接收回调）；`MediaTransportKind` 枚举 | 背压已由「阻塞 send + 有界队列」表达；接收入口与发送出口必须对称 |
| 包位置 | 纯 JVM 帧格式/重组/决策 → 新增 `core/net`；socket/TLS/协议胶水 → 现有顶层 `transport` | `core/stream` 已 19 类；避免 `core/transport` 与顶层 `transport` 同名 |
| 裸 TCP 帧头 | 16 字节：`pts(8) + flags/size(4) + epoch(2) + type/version(2)`（scrcpy 12 字节前缀 + 4 字节后缀） | 保住 scrcpy 极简头，补上本项目必需的 epoch 与帧类型 |
| 裸 TCP 加密 | 同一套 mTLS 材料在媒体端口重做 TLS 1.2/1.3 + 每 RPC 一次性 32 字节 token | 身份证明等于控制面证书；token 把 socket 绑到这一条 Stream RPC |
| UDP 加密 | AES-256-GCM 逐数据报，密钥控制面下发 | 现状媒体面是加密的，不得降级明文 |
| FEC | v1 不做，只保留 PARITY 标志位与保留字段 | XOR 约 200+300 LOC 且与 AEAD/重排窗三处交互；先拿丢片率数据 |
| 协商与回退 | HELLO 带 `media_transports`；offer/ready/close 走现有 gRPC 流；服务端在 join point 原子换 transport；回退链 UDP→TCP→gRPC 由纯决策 `MediaTransportPolicy` 驱动 | 控制面一行不动；切换点在 join point 不会重复出帧 |

## 2. 抽象层（B1 实施范围）

### 2.1 不变的部分

`MediaTransport` 一字不改（descriptor 先行、close/abort 幂等）。不加背压回调：`BoundedAccessUnitQueue`（4 MiB / 120 units / 2000 ms）就是背压面，`sendAccessUnit` 阻塞即背压；UDP 的 `DatagramSocket.send` 在内核缓冲满时同样阻塞，语义一致。

### 2.2 发送侧恢复钩子 `core/stream/MediaTransportRecovery.java`

```java
public interface MediaTransportRecovery {
    /**
     * Replaces a failed transport; null means the failure is unrecoverable.
     */
    MediaTransport onTransportFailed(MediaTransport failed, IOException cause);
}
```

`ClientSendSession` 的 `catch (IOException|RuntimeException)` 分支改为：先问 recovery；返回非 null 则不 detach——装上新 transport、`pendingDescriptor = currentDescriptor`、清队列、按 codec 策略置 `WAIT_RANDOM_ACCESS`，关闭失败 transport，`return false`；返回 null 才走原 detach + 关闭 + 重抛。

**关键**：没有它，一次 TCP/UDP 通道故障会连带杀掉控制面 RPC（drainOne → 摘映射 → handler drain 循环异常 → onError）。

新增 `swapTransport(MediaTransport next)`：返回旧 transport（调用方决定是否关闭），语义同上（重发 descriptor、清队列、等 join point）。gRPC 那个永远不关——它是控制面载体；`StreamCallHandler.close()` 已显式 `transport.close()`，所有权清楚。

`StreamHub` 增加 `swapTransportIfCurrent(key, session, next)`，与 `detachIfCurrent` 同形，防 takeover 竞态。

### 2.3 接收侧契约 `core/net/MediaChannelListener.java`

```java
public interface MediaChannelListener {
    void onDescriptor(StreamDescriptor descriptor, boolean secure);
    void onAccessUnit(EncodedAccessUnit accessUnit, long arrivalNs);
    void onChannelFailed(String reason);   // 每通道恰好一次，读线程上调用
}
```

`ClientMediaReceiver` 增加两个 raw 入口 `onDescriptor(StreamDescriptor, boolean)` / `onAccessUnit(EncodedAccessUnit, long)`，现有 `onServerMessage` 的两个 case 改成「proto → 模型 → 调 raw 入口」。计数与 `ArrivalTracker` 只有一处，新传输不复制指标代码。几何回调、secure 回调、音频分支原样保留（音频 v1 仍走 gRPC——已裁决）。

调用线程纪律不变：`ClientMediaReceiver` 仍要求单线程。`ScreenShareClient` 里的 `MediaChannelListener` 实现负责跳到 `mDecodeExecutor`（复用 `mCatchUpGeneration`），与 gRPC 路径同构。

### 2.4 迁移路径

1. B1：接口 + session swap + recovery + receiver raw 入口 → 行为零变化，全量测试仍绿。
2. B3/B5：新传输只实现 `MediaTransport` 与 `MediaChannelListener`，`StreamHub`/`StreamRuntime`/codec/队列全部不动。

## 3. 裸 TCP 媒体通道（B3 范围）

### 3.1 帧格式（16 字节头）

```text
offset size field
0      8    ptsUs        int64 BE
8      4    flags/size   uint32 BE
              bit31 CONFIG            解码配置帧（当前不单独使用）
              bit30 SYNC              关键帧
              bit29 JOIN              可加入点
              bit28 HAS_JOIN_PARTS    头后带 join 配置段
              bit27 HAS_DTS           头后带 dts（8 字节）
              bit26..0 SIZE           载荷长度，上限 8 MiB
12     2    configEpoch  uint16 BE
14     1    type         uint8        1=DESCRIPTOR 2=ACCESS_UNIT 3=ABORT(保留未用)
15     1    version      uint8        1
```

帧体：`[可选 dts(8)] [可选 joinParts] [载荷]`；`joinParts`：`u16 count`，每项 `u16 idLen + id(UTF-8) + u32 payloadLen + payload`（自写，热路径不碰 protobuf）。`DESCRIPTOR` 帧载荷 = proto `Descriptor` 序列化字节（复用 `ScreenStreamProtoMapper.toProtoDescriptor`，一 epoch 一次，不重写编解码）；AU 帧载荷 = `EncodedAccessUnit.getPayload()` 原样。

不加 magic：通道由 TLS 握手 + 56 字节 hello 帧建立；中途失步即关通道回退，不做重同步。只在可加入点带 joinParts（默认 1 s 一次）；普通帧 flag 置 0、`JoinInfo` 重建为 null——与 gRPC 路径的差异用契约测试锁定（同一 AU 两条路径 `H264CodecPolicy.isJoinPoint` 结果必须一致）。H.264 无 B 帧 `dts == pts`，v1 `HAS_DTS=0`、接收侧 `dtsUs = ptsUs`，加契约用例断言。

### 3.2 握手帧

```text
客户端 → 服务端（56 B）
0..3   magic "LSM1"
4      version = 1
5      transport = 1 (TCP)
6..7   reserved = 0
8..23  channelId (16 B)
24..55 token (32 B)

服务端 → 客户端（16 B）
0..3   magic "LSA1"
4      version = 1
5      status  0=OK 1=BAD_CHANNEL 2=BAD_TOKEN 3=PEER_MISMATCH 4=BUSY
6..15  reserved = 0
```

token 常数时间比对（`MessageDigest.isEqual`）、一次性消费；`PEER_MISMATCH`：TLS 客户端证书 device id 必须等于 ticket 记录的控制面 peer device id；ticket TTL 10 s，RPC 结束即 revoke。

### 3.3 TLS 复用

新增 `transport/MediaTlsFactory`：用 `TlsMaterial.getKeyManager()` + `PinningTrustManager(expectedDeviceId, ...)` 构造 `SSLContext`；服务端 `setNeedClientAuth(true)`；协议白名单 = `supported ∩ {TLSv1.3, TLSv1.2}`（先求交集，`setEnabledProtocols` 传不支持的会抛）；客户端 `setEndpointIdentificationAlgorithm("HTTPS")` + `TlsNames.AUTHORITY` SNI。明文调试开关 `isTlsAllowPlaintext()` 时媒体通道同样允许明文，沿用「对端 secure=false 即拒绝」（DB-SEC-002 语义不破）。强度论证：同一私钥、同一 pin 校验、同一协议白名单 ⇒ 不低于现状，外加 per-RPC token 绑定更紧。

### 3.4 端口与监听生命周期

TCP 媒体端口先试 `serverPort + 1`，失败 `bind(0)`，offer 永远带实际端口；随共享会话开关（绑定失败不失败共享，只标不可用记一行日志）；每客户端一条 channel = 一条 TLS socket + 一条读线程（`lss-media-rx-*`，只检测 EOF/协议违规），写在该客户端 drain 线程；`MediaChannelServer` 持 `lss-media-watchdog` 调度线程每 1 s 检查：`now - lastSuccessfulWrite > 5000` 且有积压 ⇒ 判通道失效 ⇒ 回退；每会话单通道，重复 offer 替换旧通道。

### 3.5 大 AU 与背压

队列先挡（`REJECTED_TOO_LARGE`）；帧头 SIZE 上限 8 MiB 纯防御；`TCP_NODELAY=true`，`SO_SNDBUF` 系统默认；队列满的丢帧语义完全沿用现状（参考帧编码清队列 resync；JPEG 保最新）；不在 transport 里做丢帧决策（DB-MULTI-005）。

## 4. UDP 媒体通道（B4/B5 范围）

### 4.1 数据报布局

```text
明文前缀（8 B，AEAD nonce 后缀）
0   8   wireSeq  uint64 BE   每方向单调递增，从 1 开始

密文（AES-256-GCM，AAD = wireSeq 的 8 字节）
0   1   v/flags      bits7..6 version=1 | bit5 CONFIG | bit4 SYNC | bit3 JOIN
                     bit2 PARITY | bit1 HAS_JOIN | bit0 reserved
1   1   fragmentIndex   uint8
2   2   fragmentCount   uint16
4   4   auId            uint32
8   8   ptsUs           int64
16  4   auSize          uint32
20  2   configEpoch     uint16
22  2   reserved        uint16   预留给 FEC
24  4   fragmentOffset  uint32
28  ..  fragment 数据

尾随 16 B  GCM tag
```

单包上限 1400 B ⇒ 分片数据 1348 B；固定开销约 3.7%。统一包体布局（pts 每片都带）。`auSize ≤ 4 MiB` ⇒ `fragmentCount ≤ 3112`。nonce = `nonceSalt(4) || wireSeq(8)`；密钥每会话随机、每方向一把。重放/迟到：`wireSeq ≤ highWater − WINDOW` 解密前直接丢。

### 4.3 重排窗与重装

`core/net/DatagramReorderWindow`：定长环形窗（默认 64 槽），判重/判过期/统计乱序丢失，无缓冲延迟。`core/net/AccessUnitReassembler`：至多 4 个在途 AU（bitmap + 单一 byte[]），每 AU 一次分配。交付规则：单片立即交付；多片收齐立即交付；未收齐且更新 auId 到达或超 hold-up ⇒ 整 AU 丢弃（绝不喂半截），`udp.auIncomplete++`。hold-up 固定 10 ms（常量 `UDP_HOLD_UP_MS`，注入 NanoClock 可测）。乱序/迟到记 `udp.late`；重复记 `udp.dup`。

### 4.4 丢包 → IDR（复用现有链路）

`core/net/DatagramLossTracker`：连续不完整 AU ≥2 或 500 ms 滑窗 ≥3 ⇒ 请求 join point（1 s 冷却）；经注入的 Runnable 调 `ScreenShareClient::requestKeyFrameThrottledFromMediaPath`（已含节流），IDR 请求链路一行不改。丢 AU 后客户端会话走现成 `ClientMediaSession.onCatchUp()`（flush + RESYNCING），投递到解码线程排在后续 AU 之前，`rx.catchup` 语义自然成立。

### 4.5 bind 流程

客户端收 offer 后向 `(控制面 peer IP, udp_port)` 发一个 authenticated bind 包（`fragmentCount=0` 哨兵 + `key_c2s`）；服务端 bind 读线程按源地址绑定 ticket、创建 `UdpMediaTransport` 开始发流，非 ticket 源的包在解密前按源地址过滤；客户端收到第一个通过校验的数据报 ⇒ READY；300 ms 无 ⇒ `FAILED(udp_bind_timeout)`。v1 无客户端→服务端 UDP 数据面（NACK 明确不做）。

### 4.6 FEC 评估结论

XOR 奇偶（每 8 片 1 校验片）：约 240 LOC + 15 例，+12.5% 带宽，只救单丢，与 AEAD nonce/AAD、重排窗、按 AU 丢弃三处交互，处理错即静默花屏。RS 手写 ≥600 LOC 直接否决。**v1 不做**，头留 PARITY 位与 2 B 保留字段；开启门槛：实测丢片率 0.2%–2% 且 `udp.auIncomplete` 与可感知卡顿相关。

## 5. 协商与回退（B2 范围）

proto 增量（全部向后兼容）：`Hello.media_transports = 7`（repeated string，空=gRPC only=旧客户端）；`ClientMessage` 增 `MediaChannelEvent media_channel_event = 5`（channel_id/type[READY/FAILED/CLOSED]/reason）；`ServerMessage` 增 `MediaOffer media_offer = 7`（channel_id/transport/host/port/secure/token/keys/nonce_salt/mtu/hold_ms）与 `MediaClose media_close = 8`。PROTOCOL_VERSION 保持 1。

`core/net/MediaTransportPolicy`（纯决策）：`first(clientOffered, serverAvailable, udpEnabled)` 与 `next(failed, ...)`；规则：客户端没声明 ⇒ GRPC；服务端隐藏键 `media_transport="grpc"`（默认 auto）⇒ GRPC（A/B 基线）；UDP 开关关闭 ⇒ 不出现 UDP 档；同档失败一次进 `alreadyFailed` 不回头。**服务端做主**，客户端只声明能力与回报状态；UDP 开关落在服务端视频设置面板（已裁决）。

切换时序：客户端 HELLO → 服务端 MEDIA_OFFER（媒体仍在 gRPC 上发，不断流）→ 客户端建通道（TLS hello / UDP bind）→ READY → 服务端 `swapTransportIfCurrent()` 原子切换 → 重发 descriptor + `requestJoinPoint()` 从 join point 续播 → 旧通道关闭。失败路径：FAILED/CLOSED/offer 超时（TCP 2 s、UDP 1 s）/看门狗 ⇒ 退化下一档再 offer；最终档 gRPC 时 swap 回 + requestJoinPoint，控制面 RPC 全程不断。`StreamCallHandler` 内部状态 `NONE/OFFERED(kind, channelId, deadline)/ACTIVE(kind)/GRPC`，超时检查放现有 drainOnce 轮询，不新增定时器。`MediaTransportRecovery` 实现：failed == 当前媒体通道 ⇒ 返回 gRPC transport + requestJoinPoint；failed == gRPC ⇒ null（致命）。

## 6. 指标与测试

新增键 13 个（单域 ≤20）：`chan`（会话行字符串字段，不进 PipelineMetrics）、`tx.frag`、`tx.chanSwitch`、`tcp.frame`/`tcp.frameDrop`、`udp.recv`/`udp.lost`/`udp.dup`/`udp.reorder`/`udp.late`、`udp.auIncomplete`/`udp.auDrop`、`udp.authFail`、`udp.idrReq`。`PipelineMetrics.Counter` 末尾追加 8 个：`DATAGRAMS_RECEIVED / DATAGRAMS_LOST / DATAGRAMS_DUPLICATE / DATAGRAMS_REORDERED / ACCESS_UNITS_INCOMPLETE / MEDIA_CHANNEL_SWITCHES / FRAGMENTS_SENT / MEDIA_AUTH_FAILURES`。零分配纪律：热路径只有 LongAdder；传输维度用每会话一个常量字符串字段表达（`chan=tcp`）。

全部可 JVM 测：`MediaFrameCodec`、`MediaChannelHandshake`、`UdpMediaPacketCodec`、`DatagramCipher`、`DatagramReorderWindow`、`AccessUnitReassembler`、`DatagramLossTracker`、`MediaTransportPolicy`、`ClientSendSession`（swap/recovery）、`MediaChannelCoordinator`、`MetricsFormatter`（`chan=`、UDP 键省略未测量项）、`TcpMediaReceiver` 回环真 TLS（先例 `TlsHandshakeEndToEndTest`）、`UdpMediaReceiver` 回环真 socket（注入 NanoClock）。

回环 E2E 必须断言：①descriptor 先于同 epoch 首个 AU；②切换期间 AU 序号不重复不倒退；③杀媒体通道后 1 s 内恢复出帧且 RPC 未断；④强制 `media_transport=grpc` 时行为与 HEAD 完全一致（回归护栏）。

## 7. 批次划分（6 批）

| 批次 | 主题 | 关键文件 | 新增测试（估） | 行为变化 | 风险 |
|---|---|---|---|---|---|
| B1 抽象层补齐与接收侧对称化 | 会话可换 transport、失败可恢复、接收侧脱 proto | 改：`ClientSendSession`、`StreamHub`、`SessionSnapshot`、`ClientMediaReceiver`、`MetricsFormatter`；新：`core/stream/MediaTransportRecovery`、`core/net/MediaChannelListener`、`core/net/MediaTransportKind` | ~12 | 无 | 低 |
| B2 协商协议与选路策略 | proto 增量 + 两侧状态机（尚无 socket，客户端一律回 FAILED 走通失败路径） | 改：proto、`ScreenStreamProtoMapper`、`ClientMessageListener`、`StreamCallHandler`、`ScreenShareClient`、`Preferences`；新：`core/net/MediaChannelTicket`、`core/net/MediaTransportPolicy`、`transport/MediaChannelOfferMapper`、`client/MediaChannelCoordinator` | ~18 | 旧端零影响 | 中 |
| B3 裸 TCP 媒体通道 | TLS 媒体监听 + 帧协议 + 通道切换（落地与翻默认分两笔提交） | 改：`ScreenShareServer`、`StreamCallHandler`、`ScreenShareClient`；新：`core/net/MediaFrameCodec`、`core/net/MediaChannelHandshake`、`transport/MediaTlsFactory`、`transport/MediaChannelServer`、`transport/TcpMediaTransport`、`transport/TcpMediaReceiver` | ~20 | 先无变化，后翻默认 | 高 |
| B4 UDP 内核（纯 JVM 不接线） | 包格式/AEAD/重排窗/重装/丢包统计 | 新：`core/net/UdpMediaPacketCodec`、`core/net/DatagramCipher`、`core/net/DatagramReorderWindow`、`core/net/AccessUnitReassembler`、`core/net/DatagramLossTracker` | ~26 | 无 | 低 |
| B5 UDP 接线与回退链 | 分片发送 + 接收 + 丢包→IDR/catch-up + 实验开关 | 改：`StreamCallHandler`、`ScreenShareClient`、`MainActivity`、视频设置布局、strings；新：`transport/UdpMediaTransport`、`transport/UdpMediaReceiver` | ~16 | UDP 档可开（默认关） | 高 |
| B6 指标、日志与文档收口 | 新键登记、`chan=` 落地、文档与裁决看板 | 改：`PipelineMetrics`、`MetricsFormatter`、五份 docs + naming-conventions §1.1（增 `core/net`）；新：`core/metrics/MediaMetricNames` | ~8 | 无 | 低 |

规模预估：+52 生产类、+100±15 tests / +12±3 classes（基线 → 约 1606/161）。

## 8. 明确不做

FEC/RS（v1 留标志位）；NACK 与任何 UDP 客户端→服务端反馈；WebRTC/SRT/QUIC；多客户端 UDP 扇出优化（每会话独立分片与加密）；拥塞控制/码率自适应（UDP 固定码率，风险记录在案）；DTLS（AES-GCM 代替）；音频随新媒体面（v1 音频继续 gRPC——已裁决）；`MediaTransport` 接口改造/背压回调；`MediaTransport` 迁包；`EncodedAccessUnit` 零拷贝访问器。

## 9. 风险表

| # | 风险 | 批次 | 缓解 |
|---|---|---|---|
| R1 | 切换丢帧/黑屏/重复出帧 | B3/B5 | join point 切换 + 重发 descriptor + requestJoinPoint；E2E 断言序号不重复不倒退 |
| R2 | 媒体通道故障连带杀控制面 RPC | B1 | MediaTransportRecovery：失败不 detach；仅 gRPC 自身失败才致命 |
| R3 | ticket/token 泄漏 | B2/B3 | handler.close 必 revoke；TTL 10 s；token 一次性 |
| R4 | 媒体端口 TLS 配置跨 API 不一致 | B3 | 协议白名单求交集；回环真 TLS 用例；失败即回退 |
| R5 | UDP 丢片误当正常丢帧 → 花屏 | B4/B5 | 丢片必丢整 AU + 必调 onCatchUp；契约用例断言 RESYNCING |
| R6 | 阻塞写卡死 drain 线程 | B3/B5 | 5 s 写停滞看门狗 + 回退；不调 SO_SNDBUF |
| R7 | 端口占用/AP 客户端隔离 | B3/B5 | 端口探测 + 临时端口 + 永远回退；日志只记一次 |
| R8 | UDP 无拥塞控制越丢越多 | B5 | 实验档默认关；udp.* 数据决定是否 FEC/降码率 |
| R9 | UDP 密钥控制面下发 → 无前向保密 | B5 | 显式记录取舍（可控局域网威胁模型）；会话结束丢密钥 |
| R10 | proto 增量让 dispatchClientMessage 抛异常 | B2 | 新增 case + default 监听方法 |
| R11 | PipelineMetrics.Counter 膨胀 | B6 | 只加 8 个追加末尾 |
| R12 | 计划文档触发 check_docs 凭据正则 | 全程 | 不写「键名 + 赋值」形态的敏感字段行 |

## 10. 命名规范遵循

新增 `core/net`（技术层网络分帧与重组），同步修订 `docs/naming-conventions.md` §1.1 列表；`core/net` 首批 10 类未超 12；依赖方向 `core/net` → JDK + core/model + core/util，`transport` → `core/*`，无反向；后缀语义：Policy/Tracker/Codec/Mapper/Coordinator/Factory/Server/Listener 均有先例，新角色名（Reassembler/ReorderWindow/DatagramCipher/Ticket/Recovery）评审时点明；`Tcp*`/`Udp*`/`Tls*` CamelCase；core/** 字段无前缀、transport 层用 mField；Javadoc 全英文多行块；新开关字符串 `media_udp_setting_label`/`media_udp_setting_note` 双语；不新建跨域大常量类（TCP 参数进 `transport/TcpMediaConstants`、UDP 进 `transport/UdpMediaConstants`）；不新增 Activity/Service。

## 11. 与既有裁决的一致性（B6 同步）

- DB-NET-001 必须按「裁决演进」改写：保留 ID + 演进表加行 + 新增 DB-NET-003 记录三分工（控制面 gRPC / 媒体面 TCP 主力 / UDP 实验档）。
- DB-SEC-005 不冲突：其否决的是会话令牌作为授权机制；本计划 token 不授予观看权限（授权仍 mTLS+TOFU+审批闸门），只做凭证绑定——写进 DB-NET-003 理由段。
- DB-OBS-002 加一句：UDP 档改用 `udp.lost`/`udp.auIncomplete`，两档读数不可混比。

## 12. 裁决记录（2026-10-22，主 K3 定，均已裁决）

1. **已裁决**：音频 v1 继续走 gRPC（若将来要走新通道，B5 扩 AudioTransport 另立批次）。
2. **已裁决**：UDP 开关只放服务端视频设置面板。
3. **已裁决**：B3 落地与翻默认分两笔提交（便于 bisect）。
4. **已裁决**：本计划落盘为本文档。
