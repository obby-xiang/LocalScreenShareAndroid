# TB-CORE-001 正确性与资源清理实施计划（2026-10-22）

> 基线：1381 tests / 132 classes / 0 failures（HEAD `4120d8a`）
> 范围只做 JVM 短测试；设备/仪器/长时测试归 TB-ENG-013。

## 1. 结论摘要

- 看板原文 9 个子项中 4 个已由后续批次解决（`CallerRunsPolicy`、NSD 服务类型、服务名隐私、发现侧重连退避 + IPv6 格式），看板未拆分。
- 1 个是纯错误路径缺陷（`StreamRuntime.start()` 卡在 `CONFIGURING`），改动小、收益明确，先做。
- 4 个仍在：停止入口不幂等、生命周期所有者分裂、客户端线程池无终止等待、捕获临界区过长、投影代次缺失、悬浮窗权限撤销会崩。
- 切成 6 个可独立提交的批次，全部只含 JVM 短测试；预计新增 7 个测试类 / 约 42 条用例，基线只增不减。

## 2. 七项范围逐项核对

| # | 范围 | 真实状态 | 主要位置 |
| --- | --- | --- | --- |
| 1 | 幂等停止 | 🔶 子对象幂等，入口不幂等 | `service/ScreenShareService.java`、`service/ScreenViewerService.java` 的 `stopService()` |
| 2 | 单一生命周期所有者 | ❌ 未做（owner 分裂在 `stopService()` 与 `onDestroy()`） | 同上 |
| 3 | 线程池终止 | 🔶 服务端 gRPC 已做；客户端 4 个池无 await | `client/ScreenShareClient.java`、`server/GrpcServerShutdown.java` |
| 4 | 缩短临界区 | ❌ 未做（采集锁内做 Bitmap 拷贝 + JPEG 压缩 + 投递） | `ScreenShareService.onImageAvailable` |
| 5 | 移除 `CallerRunsPolicy` | ✅ 已完成（批次 B/C） | 全仓库已无 |
| 6 | resize 代次隔离 | 🔶 编码侧已做；服务侧代次缺失 | `core/stream/StreamRuntime.java`、`ScreenShareService` |
| 7 | 权限撤销降级 | 🔶 投影/音频已降级；悬浮窗 `addView` 未保护 → 主线程崩溃 | 两个 Service 的浮窗调用点 |
| 8 | 重连退避与上限 | ❌ 无自动重连；手动重试无退避、无上限 | `ScreenViewerService` 断连对话框 |
| 9 | IPv6 / NSD 类型 / 服务名隐私 | ✅ 已由 TB-DISC-003 完成 | `support/NetUtils.java`、`discovery/nsd/NsdConstants.java`、`identity/DeviceIdentity.java` |

细节要点：

- ①②：`stopService()` 可从广播、悬浮条确认框、`MediaProjection.Callback.onStop`、启动失败路径共 5 处进入，无 once 守卫；`onDestroy()` 注释假设"清理已由 stopService 完成"，但 `mMetricsLogRunnable` 等自续期任务在非 stopService 销毁路径下永久持有 Service 实例；`mEncoderRebuildRunnable` 只在 `onDestroy()` 移除，stop 后 300 ms 内重开共享会让旧会话的 rebuild 作用到新 runtime；采集线程裸读非 volatile 的 `mStreamRuntime`，`stopService()` 在锁外置 null，存在采集线程 NPE 窗口。
- ③：客户端 `ScreenShareClient.stop()` 对 4 个池只 `shutdownNow()` 不等待；`ScreenShareServer.stop()` 只 `clear()` 不 `close()` 活跃 handler；服务端控制池保持"graceful + 不等待"是刻意的（主线程不能等 keystore），应写成契约。
- ④：`onImageAvailable` 全程持锁做 `acquireLatestImage` + Bitmap 创建/拷贝 + JPEG 压缩 + `submitFrame`，阻塞主线程的 resize/teardown。
- ⑥：`StreamRuntime` 的 attempt generation 与 `configEpoch` 已做；服务层 reader 替换/teardown 无代次，延迟任务跨会话存活。
- ⑦：悬浮窗 `addView`/`removeViewImmediate`/`dialog.show()` 无 try，权限撤销后 `BadTokenException`/`SecurityException` 在 main looper 未捕获 → 进程崩溃；`PlaybackCaptureSource.read` 未捕 `SecurityException`（应归类 `PERMISSION_DENIED`）；投影被系统停止无用户可见提示。
- ⑧：观看端断连对话框点确定立刻重试，无延迟无上限；服务端控制面限速（Register 60 s 窗口）与发现侧 `BackoffPolicy` 已有。

## 3. 批次划分

| 批次 | 主题 | 改动文件 | 新增测试 | 风险 |
| --- | --- | --- | --- | --- |
| B1 | `StreamRuntime` 失败不再卡 `CONFIGURING` | `core/stream/StreamRuntime.java`、`core/support/FakeCodec.java` | `StreamRuntimeStartFailureTest`（~8） | 低 |
| B2 | 采集线程：代次 + 缩短临界区 | `service/ScreenShareService.java`、新增 `service/ProjectionGeneration.java`、`service/CaptureThrottlePolicy.java` | `ProjectionGenerationTest`（5）、`CaptureThrottlePolicyTest`（6） | 中高 |
| B3 | 单一生命周期所有者 + 幂等停止 | 新增 `service/TeardownCoordinator.java`、两个 Service | `TeardownCoordinatorTest`（6） | 高 |
| B4 | 线程池与线程收尾 | 新增 `support/ExecutorShutdown.java`、`client/ScreenShareClient.java`、`server/ScreenShareServer.java`、`service/ScreenShareService.java` | `ExecutorShutdownTest`（5） | 中 |
| B5 | 悬浮窗/权限撤销降级 | 新增 `support/OverlayWindow.java`、两个 Service、`PlaybackCaptureSource.java`、`AudioCaptureException.java`、`res/values*/strings.xml` | `AudioCaptureExceptionTest`（4）、`OverlayWindowContractTest`（2） | 低 |
| B6 | 重连退避与上限 | 新增 `client/ReconnectPolicy.java`、`service/ScreenViewerService.java`、`strings.xml` | `ReconnectPolicyTest`（6） | 中（UX） |

依赖顺序：B1 独立 → B2 → B3 → B4 → B5 → B6（B4/B5/B6 依赖 B3 的一次性 teardown）。

## 4. B1 详细方案（本计划首个实施批）

**缺陷**：`StreamRuntime.start()` 在锁内置 `CONFIGURING` 并分配 attempt 后，在 try 外做 `oldFailed.close()` 与 `registry.require(...)`；任一抛异常则状态停在 `CONFIGURING`、`publishedDescriptor == null`、`activeEncoder == null`，而 `retryable` 只接受 `IDLE` 或 `FAILED && 未发布` → 该 runtime 只能 `close()`。

**改动**：
1. `start()`：attempt 分配后的全部步骤（关旧编码器、`require`、工厂创建、surface 校验、`start`）收敛到失败出口 `failAttempt(attempt, encoder, cause)`：若代次仍是当前 → `FAILED`、清 `publishedDescriptor`、`deferred=false`、`activeEncoder=null`；锁外关闭编码器；只通知一次监听器；原异常重抛（`CodecNotFoundException` 保持原类型，其余包成 `EncoderException`）。成功路径语义不变。
2. `switchDescriptor()`：`registry.require` 移出状态锁，未注册 codec 包成 `CodecSwitchException`；旧编码器/descriptor/epoch 不变，可用同一 epoch 重试。
3. 第 2 提交（已裁决纳入）：`updateParameters()` 不在状态锁内调 `encoder.updateParameters`，改为"锁内取 encoder+generation → 锁外调用 → 锁内按代次提交缓存；代次已变则对新 encoder 重试一次"。语义保持"encoder 抛异常则缓存不变并上抛"。
4. 同步更新类 Javadoc 的 State machine / Locking 段落与 `@throws`。

**测试**（`core/stream/StreamRuntimeStartFailureTest`）：
- 未注册 codec：`start()` 抛 `CodecNotFoundException`；状态 `FAILED`；`getDescriptor()==null`；监听器收到 1 次 `onEncoderFailed`。
- 上一条之后 `registry.register(...)` 再 `start()` → `READY`（可重试）。
- 旧编码器 `close()` 抛异常（`FakeCodec` 加 `failNextClose()`）：状态 `FAILED` 而非 `CONFIGURING`，仍可重试。
- 已 `READY` 再 `start()` → `IllegalStateException`（行为保持）。
- `switchDescriptor` 到未注册 codec → `CodecSwitchException`，旧 descriptor/epoch 不变，同 epoch 重试成功。
- `updateParameters` 期间 `getState()` 不被阻塞（latch 控制的确定性用例）。

## 5. B2–B6 概要（细节随批次实施前再展开）

- **B2**：新增 `ProjectionGeneration`（`begin()`/`isCurrent(token)`/`invalidate()`，`AtomicLong`）与 `CaptureThrottlePolicy`（纯节流决策）；`onImageAvailable` 锁内只做身份检查+节流+acquire+像素拷贝进采集线程私有 Bitmap+close image+取代次快照，锁外做 JPEG 压缩与代次校验后投递；删除共享 `mScreenFrameBitmap`；`mStreamRuntime` 改 volatile，采集路径只用快照。行为判据：JPEG 帧率/质量/尺寸不变，H.264 路径不受影响。
- **B3**：新增 `TeardownCoordinator`（一次性、按序、异常隔离、`isDone()`）；两个 Service 的 `stopService()` 与 `onDestroy()` 都走它；teardown 显式 `removeCallbacks` 全部主线程自续期/延迟任务；`stopSelf()` 只在 `stopService()` 调。
- **B4**：新增 `support/ExecutorShutdown`（`shutdownNow` + 有界 `awaitTermination` + 保留中断标志 + 幂等）；客户端 4 池收尾总预算 ≤250 ms；`ScreenShareServer.stop()` 逐个 `close()` 活跃 handler 再 clear；服务端控制池保持不等待并写进 Javadoc。
- **B5**：新增 `support/OverlayWindow`（`attach`/`detach` 成对，吞 `RuntimeException` 返回成败）；两个服务全部浮窗与确认框改走它，失败记日志、会话继续（通知/Activity 入口保留）；`PlaybackCaptureSource.read` 捕 `SecurityException` → `PERMISSION_DENIED`；`AudioCaptureException` 新增 `classify(Throwable)`；投影被系统停止时 Toast 提示（新增双语字符串）。
- **B6**：新增 `client/ReconnectPolicy`（延迟 0/1/2/4/8 s、最多 5 次、`reset()` 于 `onConnected`）；重试按钮 `postDelayed` + 倒计时禁用 + 用尽后只剩"结束观看"；teardown 取消待执行重试。

## 6. 裁决记录（2026-10-22，主 K3 定）

1. B6 做（退避 + 上限），不退化为仅限次数。
2. B1 的 `updateParameters` 出锁提交纳入。
3. B5 投影撤销提示用 Toast（新增字符串 `values` 与 `values-zh` 同步）。
4. 看板拆分随批次提交同步：TB-CORE-001 转 🔶，子项 ⑤⑨ 与发现侧重连退避标完成并注明证据。

## 7. 风险与验证口径

| 风险 | 批次 | 缓解 |
| --- | --- | --- |
| teardown 顺序被改 → 资源提前/延后释放 | B3 | 步骤列表与现 `stopService()` 逐行对应；子调用保持幂等 |
| 锁外压缩时 reader 被替换/关闭 | B2 | 像素拷贝与 `image.close()` 留锁内；投递前校验代次；Bitmap 采集线程私有 |
| 主线程等线程池 → 卡顿/ANR | B4 | 总预算 ≤250 ms；控制池明确不等待 |
| `onDestroy` 也开始清理 → 重复释放 | B3 | once 守卫；子资源 close/detach 已幂等 |
| 浮窗失败被吞 → 无画面无提示 | B5 | 失败必记日志 + 通知入口保留 |
| 重试延迟改变交互 | B6 | 已由裁决 1 确认 |

每批统一验证（不跑设备）：清空 test-results 后 `./gradlew :app:testDebugUnitTest` 全绿且数字只增不减；`./gradlew :app:assembleDebug`；`./gradlew :app:lintDebug` 0 errors；触及资源时跑 `scripts/checks/` 三个检查脚本。不能 JVM 覆盖的点位（启动/停止循环、权限撤销、网络断开恢复等）登记 TB-ENG-013。

## 8. 明确不做

- `service` 上帝包拆分（另立任务）；JPEG 降级查看器接回（TB-ENG-006）；关窗不停会话（DV-008/TB-CORE-002）；多档流/RTP/WebRTC；音频质量与设备验证；授权审批逻辑；新增指标键；NSD/IPv6 残留核查；设备/仪器/压力/长时测试（TB-ENG-013）；任何新依赖、minSdk 变更、UI 重做、`support` 之外的新包。

## 9. 命名规范遵循

- Javadoc 一律多行块；不新增 `package-info.java`。
- 只用既有 `core/stream`、`service`、`client`、`support` 包；`support` 只依赖 JDK 与平台 API。
- 后缀语义：`TeardownCoordinator`（编排）、`CaptureThrottlePolicy`/`ReconnectPolicy`（纯决策）、`ProjectionGeneration`（代次）、`OverlayWindow`（窗口对象，`attach`/`detach` 成对）、`ExecutorShutdown`（工具）。
- `service`/`client`/`support` 字段用 `mXxx`，`core/**` 无前缀；常量 `UPPER_SNAKE`；生命周期动词成对。
- 新增字段初始化器不得触碰 Context（`LazyContextValue` 模式）。
