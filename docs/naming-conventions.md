# 命名与包结构规范

> 生效日期：2026-10-20。
> 适用范围：本仓库全部新代码。存量代码按 TB-ENG-011（代码结构梳理与命名统一）的 R1–R4 批次逐步对齐，不在本规范生效时做一次性重命名。
> 根包约束：根包 `com.obby.android.localscreenshare` 不可变更；本规范只约束其子包、类型、成员与资源。
> 依据：TB-ENG-011 的盘点结论与经用户批准的调整方案。本规范是 R1–R4 重构批次与今后新代码的依据；R1–R4 已于 2026-10-20 全部落地，文中目标名即当前实现。
> 相关文档：[任务看板](task-board.md) · [决策看板](decision-board.md) · [项目现状与技术审查](project-assessment.md)

## 1. 包

### 1.1 基本形态

- 包名全小写、单数、无下划线；不得出现 `core/Codec`、`discovery/utils` 这类写法。
- 只使用两种切法：
  - **技术层**：`core/model`、`core/codec`、`core/stream`、`core/client`、`core/render`、`core/util`。
  - **领域**：`discovery`、`identity`、`settings`。
- 同一个父包下不混用两种切法：`discovery` 的子包按发现领域内的职责划分，`core` 的子包按技术层划分，二者不互相嵌套。

### 1.2 禁止新增的包

- 禁止新增 `utils`、`common`、`misc`、`helper`、`manager` 包。
- 工具类与进程级服务类进 `support`。
- 这些名字不作为包名的原因：它们没有可判定的职责边界，任何类都能放进去，依赖方向随之失效；`manager` 还会与第 2 章的后缀语义冲突，把“包”和“角色”混为一谈。

### 1.3 拆分阈值与职责表达

- 一个包内出现 **3 种及以上角色**，或 **超过 12 个类** 时，拆子包。角色按第 2 章的后缀语义判定，例如 `Provider`、`Store`、`Coordinator` 属于不同角色。
- 拆出的子包名仍按 1.1 的形态与切法命名，不新增“杂项子包”收留难以归类的类。
- 包的职责写在包内主要类的 Javadoc 首段。本项目不使用 `package-info.java`：文档化包职责时**建议但不强制**新增 `package-info.java`，保持现状即可。

### 1.4 依赖方向

允许的依赖方向只有自上而下：

```text
ui / service
    → feature（discovery / identity / server / client）
        → framework（core）
            → 叶子（support / model / settings）
```

- 依赖只能指向右侧或下方，允许跨层向下，但不得反向依赖，也不得横向依赖。
- `support` 不得 import 任何 feature 或 core 包；它只能依赖 JDK 与 Android 平台 API。
- 由方向唯一性可得：同级 feature 不得互相 import；跨 feature 共享的契约下沉到 `core/model`（纯数据）或 `support`（进程级工具）。
- `model` 指纯数据模型（当前位于 `core/model`），按叶子层的角色对待：只被依赖，不反向依赖 `core/stream`、`core/codec` 等。
- `settings` 是设置领域的叶子包，承载设置模型与设置读写入口；需要设置的 feature 依赖它，它不反向依赖 feature。

### 1.5 既定包调整（TB-ENG-011 批次执行）

| 目标包 | 迁入内容 | 说明 |
| --- | --- | --- |
| `settings` | `Preferences`、`DiscoverySettings`、`VisibilityPolicy` | 设置的持久化入口、设置模型与可见性决策同属一个领域包，不再散落在 `support` 与 `discovery`，也消除了 `settings↔discovery` 环 |
| `discovery/legacy` | 废弃 DTO | 仅保留兼容期引用；新增代码不得引用，批次结束后可删除 |
| `discovery/ui` | 列表投影（`DiscoveryListModel`） | 纯显示投影，不持有 store，也不引用 Android 视图类型 |
| `core/codec/mediacodec` | MIME 通用的 MediaCodec 桥 | 从 `core/codec/h264` 拆出与 H.264 无关的桥接；`h264` 只保留 H.264 特有逻辑（R4 已落地） |
| `core/util` | `TimeSource` 等通用工具 | framework 内部纯工具；`support` 不得依赖它（见 1.4）（R4 已落地） |
| 取消 `utils` | `IntentUtils`、`NetUtils`、`NsdResolver`、`ResourceUtils`、`ThreadUtils`、`WindowUtils` | 并入 `support`，不保留 `utils` 包 |

## 2. 类名

### 2.1 包作用域命名

- 包名已提供领域上下文时，类名不重复领域前缀：`server.ServerInfo` 而非 `LssServerInfo`，`discovery.model.DeviceInfo` 而非 `LssDeviceInfo`。
- 不使用品牌前缀。`Lss` 前缀全面废弃，由 R4 批次统一去掉；新代码不得新增任何 `Lss*` 类型。
- 类名是名词短语，行为差异用后缀表达（见 2.3），不在类名里塞入完整句子。

### 2.2 两个产品端点（固定）

| 名称 | 角色 | R2 前名称 |
| --- | --- | --- |
| `ScreenShareService` | 服务端（被看方）前台 Service | `LssService`（R2 已改名） |
| `ScreenViewerService` | 客户端（看方）前台 Service | `LssClientService`（R2 已改名） |
| `ScreenShareServer` | 服务端网络端点 | `LssServer` |
| `ScreenShareClient` | 客户端网络端点 | `LssClient` |

- 这四个名字固定，不因包调整或功能扩展而改名。
- `*Service` 只用于 Android 组件，`*Server` / `*Client` 只用于网络端点；两组不得互换。

### 2.3 后缀语义表（强制）

| 后缀 | 语义 | 本项目例子 |
| --- | --- | --- |
| `*Service` | Android 组件（`Service` 子类） | `ScreenShareService`、`ScreenViewerService` |
| `*Client` / `*Server` | 网络端点 | `ScreenShareClient`、`ScreenShareServer` |
| `*Session` | 单连接状态机 | `ClientMediaSession`、`ClientSendSession` |
| `*Runtime` | 长生命周期管线 | `StreamRuntime` |
| `*Coordinator` | 定时或状态机编排 | `NsdDiscoveryCoordinator`、`ScanCoordinator` |
| `*Provider` | 数据或能力供给 | `DiscoveryProvider`、`IdentityProvider` |
| `*Store` | 领域状态（内存表或持久映射） | `DeviceStore`、`FavoriteStore` |
| `*Storage` | 原始持久化 I/O | `FavoriteStorage`、`SharedPreferencesFavoriteStorage` |
| `*Manager` | 仅进程级单例持有者 | 无；存量“管理”类在 R 批次按实际角色改名 |
| `*Handler` | gRPC 每次调用处理器 | `StreamCallHandler` |
| `*Policy` | 纯决策 | `VisibilityPolicy`、`CodecPolicy` |
| `*Factory` | 创建 | `EncoderFactory`、`H264EncoderFactory` |
| `*Mapper` | 无状态转换 | `RegisterProtoMapper`、`ScreenStreamProtoMapper` |
| `*Registry` | 查找表 | `CodecRegistry` |
| `*Target` | 输出端 | `RenderTarget`、`SurfaceRenderTarget` |
| `*Listener` | 回调接口 | `DiscoveryProviderListener`、`ClientStateListener` |

- 后缀必须与角色一致；一个类同时承担两种角色时先拆类，不得叠加后缀（如 `XxxManagerProvider`）。
- `*Handler` 禁止用于 Android `Handler` 的包装类。gRPC 的 `*CallHandler` 变体见 7.2。
- `*Manager` 只在“持有进程级单例”时使用；“管理的东西比较多”不是使用理由。

### 2.4 缩写

- 类名中的缩写按 CamelCase 书写：`Nsd`、`Udp`、`Grpc`、`H264`、`Jpeg`，例如 `NsdDiscoveryProvider`、`GrpcMediaTransport`、`H264Encoder`、`JpegCodec`。
- 常量中的缩写全大写：`NSD_*`、`GRPC_*`、`MULTICAST_*`。
- 禁止 `NSDProvider` 式混写：同一缩写在类名与常量中必须分别遵循 CamelCase 与 UPPER_SNAKE，不能一半大写一半小写。

## 3. 方法与字段

### 3.1 方法命名

- 方法名 camelCase，以动词开头。
- boolean 返回值使用 `is`、`has`、`can`、`should` 前缀，例如 `isDiscovering()`、`hasCapability()`。
- 访问器统一为 `getX()` / `setX()`，不使用无前缀的 `x()` 或 `fetchX()` 作为属性读取。
- 方法名中的缩写遵循 CamelCase，例如 `getNsdServiceType()`、`encodeH264()`。

### 3.2 生命周期动词成对

| 配对 | 适用对象 |
| --- | --- |
| `start` / `stop` | Service、采集与编码管线、长生命周期运行时 |
| `add` / `remove` | 集合成员、设备记录 |
| `register` / `unregister` | 监听器、NSD 服务、回调 |
| `open` / `close` | 套接字、流、文件 |
| `create` / `release` | 编解码器实例、原生资源 |
| `attach` / `detach` | 渲染目标、窗口 |

- 同一个对象的开始与结束必须来自同一对动词，不得跨对组合（例如用 `start` 开、用 `close` 关）。
- 反例：`discoverServices()` 与 `stopServiceDiscovery()` 不对称，调用方无法从名字推断配对关系，见第 6 章。

### 3.3 字段、静态状态与常量

- 实例字段的 `m` 前缀按**层**约定：app 层（根包下除 `core` 外的所有包）与 `discovery/legacy` 统一使用 `m` 前缀，与存量代码保持一致；`core/**` 保持无前缀的现状，内部自洽，不做全量改写。
- 新代码跟随所在层的风格：app 层新类写 `mField`，`core` 新类写 `field`；同一文件内不得混用两种风格。
- 需要静态可变状态时，用 `InstanceHolder` 私有静态内部类持有实例，通过 `get()` 暴露；不得直接暴露可变的 `public static` 字段。
- 单例 getter 统一为 `get()`，不使用 `getInstance()`、`instance()` 等别名。
- 常量使用 `UPPER_SNAKE`，按域加前缀，例如 `NSD_SERVICE_TYPE`、`GRPC_FLOW_CONTROL_WINDOW`、`MULTICAST_PORT`。

## 4. 资源

### 4.1 前缀表

| 资源类型 | 命名规则 |
| --- | --- |
| layout | `activity_<screen>`、`item_<list>`、`widget_<component>`、`dialog_<name>` |
| id | `<screen>_<element>`；列表项 `<list>_item_<element>`；设置项 `<domain>_<setting>_setting` |
| string | `<domain>_<screen>_<purpose>`；动作用 `action_`，标签以 `_label` 结尾，确认以 `_confirm` 结尾，错误以 `_error` 结尾 |
| drawable | `ic_<name>` |
| color / dimen | `<domain>_<element>[_<variant>]` |
| 主题 | `Theme.<App>.*`、`ThemeOverlay.<App>.*`；禁用 `AppTheme` 式命名 |

- 示例：`activity_main`、`item_device`、`dialog_confirm`、`main_stop_button`、`device_list_empty_label`、`screen_share_stop_confirm`、`ic_share`。
- 主题必须使用 `Theme.<App>.*` 或 `ThemeOverlay.<App>.*` 形式；单独的 `AppTheme`、`AppThemeDark` 不作为主题名。

### 4.2 领域在前、用途在后

- 资源名一律“领域在前、用途在后”，禁止动词开头。
- 反例：`stop_screen_share_confirm` 应改为 `screen_share_stop_confirm`；动词开头的名字会让同一领域的资源在排序中被打散。
- 用途段保持稳定：同一屏幕的标签、确认、错误共享领域前缀，评审时能直接看出缺少哪个用途。

### 4.3 字符串与本地化

- 新增或改名字符串必须同步 `values-zh`；只改默认 `values` 视为未完成。
- 提交前运行 `python scripts/checks/check_strings.py`，确认默认语言与中文资源键一致。
- 字符串改名时同步更新代码引用与布局引用；不得保留只被旧代码引用的别名键。

## 5. 常量

- 单个常量类只承载一个域，最多 20 个值；超过即按域拆分，拆分后类名体现域。
- 跨域的进程内契约（Action、Extra、MessageId）归 `ServiceProtocol`，由服务端与客户端共同引用。
- 协议参数归各 feature 的 `*Constants`：发现、扫描、注册、组播、传输各自成域，不集中到一个大类。
- 原 `Constants` 巨型类已在 R3 批次按域拆分；拆分完成后不再新增跨域常量类。
- 常量使用 `UPPER_SNAKE`，并按域使用 `NSD_*`、`GRPC_*`、`MULTICAST_*` 等前缀。

## 6. 反例清单

以下反例均取自 TB-ENG-011 的盘点，每行给出一处可核对的出处。它们按批次修正，不在本规范生效时立即改名。

| 反例 | 问题 | 出处 |
| --- | --- | --- |
| `AndroidKeystoreIdentityProvider` 实现 `IdentityKeyStore`（已于 R2 修复：改名为 `AndroidKeystoreKeyStore`） | 类名是 Provider（能力供给），实际扮演 Store（持久化读写）；类名与接口角色互换 | `app/src/main/java/.../identity/AndroidKeystoreKeyStore.java:34` |
| `LssService` / `LssClientService`（已于 R2 修复：改名为 `ScreenShareService` / `ScreenViewerService`） | 服务端与客户端语义无法从名字区分：前者是服务端（被看方），后者是客户端（看方），`Lss` 前缀不携带角色信息 | `app/src/main/java/.../service/ScreenShareService.java:152`、`app/src/main/java/.../service/ScreenViewerService.java:123` |
| `DeviceListModel`（已于 R2 修复：改名为 `DiscoveryListModel`） | 是发现列表的行投影（分节、排序、空态），不是 MVVM 的 Model；放在 `discovery` 根包也看不出显示职责 | `app/src/main/java/.../discovery/ui/DiscoveryListModel.java:39` |
| `discoverServices()` / `stopServiceDiscovery()`（已于 R2 修复：对称化为 `startDiscovery()` / `stopDiscovery()`） | 同一生命周期两端的动词不对称，调用方无法靠命名推断配对 | `app/src/main/java/.../discovery/DiscoveryManager.java:295`、同文件 `:355` |
| `Constants`（已于 R3 修复：拆为 `NsdConstants`、`MulticastConstants`、`RegisterConstants`、`ScanConstants`、`GrpcConstants` 与 `ServiceProtocol`；`support/Constants` 仅余 2 个运行时值） | 56 个常量、6 个域（资源尺寸、NSD、Action/Extra、窗口类型、MessageId、gRPC 限额）、全仓库 236 处引用；任一处新增都扩大公共面 | `app/src/main/java/.../support/Constants.java:19` |
| `LssServer`、`LssClient`、`LssServerInfo`、`LssDeviceInfo` 等 13 个 `Lss*` 类型（已于 R4 修复：去掉品牌前缀） | `Lss` 是品牌前缀，不携带角色信息；包名已提供领域上下文（`server`、`client`、`discovery/model`）时仍重复领域前缀 | `app/src/main/java/.../server/ScreenShareServer.java:147`、`app/src/main/java/.../client/ScreenShareClient.java:67`、`app/src/main/java/.../discovery/model/DeviceInfo.java:35` |
| `core/codec/h264` 中的 `MediaCodec*Bridge`、`AndroidMediaCodec*Bridge`、`*Format`、`EncodedSample`、`DecodedOutput`（已于 R4 修复：迁至 `core/codec/mediacodec`） | MIME 通用的 MediaCodec 桥与 H.264 专属逻辑同包，包的职责无法从名字判断；H.264 之外的编解码器无法复用这些桥 | `app/src/main/java/.../core/codec/mediacodec/MediaCodecBridgeFactory.java:18` |
| `core/stream/TimeSource`（已于 R4 修复：迁至 `core/util`） | 通用时间源混在流管线包内，`core/stream` 之外的 9 处引用被迫依赖流管线包 | `app/src/main/java/.../core/util/TimeSource.java:11` |

R2 批次同时处理了盘点中的其余命名问题：`KeystoreIdentityProvider` → `DefaultIdentityProvider`（真正的 `IdentityProvider` 实现）、`NsdUtils` → `NsdResolver`、`support/Reference` → `MutableReference`、`discovery/VisibilityPolicy` 迁入 `settings`、`deviceStore()` / `favoriteStore()` 改为 `getDeviceStore()` / `getFavoriteStore()`，以及资源键、布局 id 与主题名的领域前置统一。

## 7. 落地与例外

### 7.1 生效与批次

- 本规范经用户批准，2026-10-20 生效。R0 批次即本文档；R1–R4 重构批次按本规范执行。
- 每个批次只做移动与改名，不夹带行为变化；每批必须通过编译、全量 JVM 测试与 CI。
- 新代码自生效日起必须符合本规范；存量代码按批次逐步对齐，批次之外不顺带做大规模改名，避免评审噪声。
- 文档中的目标名在对应批次落地前仍属目标态；不得以“规范已批准”为理由，在未完成批次里把目标名写成当前实现。

### 7.2 例外

| 例外 | 范围 | 处理 |
| --- | --- | --- |
| gRPC `*CallHandler` 命名 | `RegisterCallHandler`、`StreamCallHandler` 等 | 保留 `*CallHandler`：与 gRPC 框架自身的 `ServerCallHandler` 语义一致。这是 `*Handler` 的唯一变体豁免，不扩展出其他 `*Handler` 形式 |
| `core.codec` 与 `core.codec.jpeg` 的包环 | `CodecRegistry` 硬编码注册 `JpegCodec`，`JpegCodec` 又引用 `core.codec` 的 `CodecDescriptor` 与 `CodecRegistration` | 本次不修。修复需要改注册逻辑（把内置注册移出 `core.codec` 或改为注入），不是纯结构移动，另行处理 |
| `settings` 对 feature 常量类的依赖 | `settings/DiscoverySettings`、`settings/Preferences` 引用 `discovery` 的 `NsdConstants`、`RegisterConstants`、`MulticastConstants` | 例外允许，不视为方向违规：`*Constants` 是纯值持有者，不含行为、不持有状态，也不反向依赖 `settings`；携带逻辑的 feature 类型仍受 1.4 约束 |

### 7.3 评审顺序

1. **包**：是否新增禁止包、是否混用两种切法、是否违反依赖方向。
2. **类**：后缀与角色是否一致、是否重复包已提供的领域前缀、是否新增 `Lss` 前缀。
3. **方法**：生命周期动词是否成对、boolean 前缀是否正确、访问器是否统一。
4. **资源**：领域是否在前、是否套用 4.1 的前缀表、字符串是否同步 `values-zh`。
5. **常量**：是否单域、是否不超过 20 个值、归属到 `ServiceProtocol` 或对应 feature 的 `*Constants`。
