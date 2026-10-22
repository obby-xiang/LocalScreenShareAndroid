# 启动与签名检查清单

> 用途：回答“现在要把它跑起来还缺什么”，并区分 Debug 启动、正式发布与不需要准备的项。
> 证据口径：本文只记录可核对的环境存在性、构建结果与 CI 结果；未安装 APK、未连接或修改设备，也未做服务端/客户端实机联调。设备数量与系统版本未知时不猜测。
> 检查快照：2026-10-17，仓库提交 `6babed9`。

## 结论速览

- **当前环境可以编译打包**：Gradle Wrapper、JDK、Android SDK 解析和签名配置都能完成 `assembleDebug` 与 `assembleRelease`。
- **Debug 启动不要求 release 证书**：Debug 使用 AGP 自动签名；缺的是可用的 Android 设备/模拟器和 adb 安装通道，以及设备上的运行时授权。
- **正式发布只认私有签名**：仓库不含任何签名材料；无材料时 release 产出 `app-release-unsigned.apk`，不能安装也不能分发。正式发布必须使用仓库外的私有 keystore（本地 `signing.properties` 或 CI secrets），并由用户手动 dispatch 发布。
- **单设备只能做启动与界面检查**：完整服务端/客户端流程至少需要两台设备、同一局域网和可用的 NSD/mDNS。
- **仪器测试只编译不运行**：CI 覆盖 `:app:assembleDebugAndroidTest`，`connectedDebugAndroidTest` 从未执行；`H264MediaCodecSmokeTest` 的失败不等于实现缺陷（见 D 节）。

## A. 当前环境与构建

### 本次检查结果

| 项目 | 结果 | 说明 |
| --- | --- | --- |
| Gradle Wrapper | 已具备 | `gradlew`、`gradlew.bat` 与 wrapper jar 齐全；Gradle 8.11.1 |
| JDK | 可用，但不是 JDK 17 | PATH 上为 JDK 21；Gradle 8.11.1 已用该 JVM 启动成功。未发现独立 JDK 17，`JAVA_HOME` 未设置。AGP 8.9 的最低要求是 JDK 17，JDK 21 满足下限；若需要严格复现 CI 的 JDK 17 环境，需另装或显式指定，但不阻塞当前构建 |
| Android SDK | 已具备，未读取路径 | `local.properties` 存在（内容未读取）；`ANDROID_HOME`/`ANDROID_SDK_ROOT` 未设置。构建成功说明 SDK 解析、API 35 与所需构建工具可用 |
| 编译/目标版本 | 已具备 | `compileSdk 35`、`targetSdk 35`、`minSdk 25` |
| Debug 构建 | 通过 | `./gradlew :app:assembleDebug --offline` 成功；AGP 的 `validateSigningDebug` 通过 |
| Release 构建 | 通过 | `./gradlew :app:assembleRelease` 成功（R8 已开启）；本机无签名材料时产物为 `app-release-unsigned.apk`，有材料时为本机私有签名 |
| 此前全量构建与 CI | 成功 | 本机已有 debug/release APK 与测试/检查报告；最近一次远端 Build CI 成功（run `39036432781`） |
| Android 设备 | **未知** | `adb` 不在 PATH，本次未检查设备数量与状态；没有执行安装 |
| adb | 不在 PATH | 未确认 SDK 内 platform-tools 是否存在；不读取 `local.properties` 的内容 |
| NDK / CMake | 不需要 | 仓库无 native 构建文件，无 `externalNativeBuild` |
| Firebase / google-services | 不需要 | 仓库无相关插件、配置文件或引用 |

### 构建时不需要手工准备的东西

- 不需要单独安装 Gradle；使用仓库自带 Wrapper。
- 不需要 NDK 或 CMake。
- 不需要 Firebase 配置或 `google-services.json`。
- 不需要为 Debug 准备 release keystore。
- protobuf/gRPC 代码生成由 Gradle 插件完成；首次构建需要能访问依赖仓库，之后可离线构建。

## B. Debug 安装与启动

### 当前已经具备

- 可编译的 Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。
- AGP 自动 Debug 签名；不需要手工配置证书。
- 服务端与客户端在同一个 App 内；启动入口是主界面，不依赖账号或云端服务。
- Manifest 已声明当前功能真正需要的权限。

### Debug 启动仍需

| 条件 | 要求 | 说明 |
| --- | --- | --- |
| Android 设备或模拟器 | 至少一台，API 25+ | 本次未确认是否有可用设备；`adb` 当前不在 PATH |
| 安装通道 | `adb` 可用并能识别设备 | 可用 `./gradlew installDebug` 或 `adb install -r`；本次未执行安装 |
| 通知权限 | Android 13+ 运行时授权 | App 在开始共享/观看前请求；拒绝会阻止继续 |
| 悬浮窗权限 | 特殊应用权限，需到系统设置授权 | App 在开始共享/观看前检查并引导；客户端显示悬浮窗需要 |
| MediaProjection 授权 | 每次开始屏幕共享时由系统弹窗确认 | 不是普通 Manifest 权限；服务端启动采集时必需 |
| 局域网与 NSD/mDNS | 两台设备在同一网络，且允许组播/服务发现 | 访客网络、AP 隔离或企业网络可能阻断发现 |
| 两台设备（完整流程） | 一台服务端、一台客户端 | 单设备只能检查启动、权限和界面，不能完成端到端共享 |

Android 14+ 对 `mediaProjection` 前台服务有额外限制：应在用户可见时取得授权并启动服务。当前实现从授权结果回调启动服务，符合这一基本路径，但仍需在目标系统上实机确认。

### 当前真实需要的权限

| 权限 / 能力 | 类型 | 当前用途 |
| --- | --- | --- |
| `INTERNET` | 普通权限 | 局域网 TCP/gRPC 传输 |
| `ACCESS_NETWORK_STATE` | 普通权限 | 网络状态 |
| `ACCESS_WIFI_STATE` | 普通权限 | Wi-Fi 状态 |
| `FOREGROUND_SERVICE` | 普通权限 | 服务端/客户端前台服务 |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | 普通权限 | 服务端屏幕采集前台服务 |
| `FOREGROUND_SERVICE_REMOTE_MESSAGING` | 普通权限 | 客户端会话前台服务 |
| `POST_NOTIFICATIONS` | Android 13+ 运行时权限 | 前台服务通知 |
| `SYSTEM_ALERT_WINDOW` | 特殊应用权限 | 客户端悬浮窗 |
| MediaProjection 授权 | 系统逐次授权 | 屏幕采集，不是 Manifest 权限 |

当前代码没有音频传输与远程反控，因此**不需要** `RECORD_AUDIO`、音频捕获权限或无障碍服务权限。NSD 使用系统 `NsdManager`，当前 Manifest 没有额外的组播权限声明。

## C. Release 与正式发布签名

### Debug / Release / 正式发布

| 场景 | 签名来源 | 产物 | 能否安装 | 能否正式分发 |
| --- | --- | --- | --- | --- |
| Debug | AGP 自动生成的 debug 签名 | `app-debug.apk` | 能 | 不能 |
| 本地 Release（无材料） | 不签名 | `app-release-unsigned.apk` | 不能（系统拒绝未签名包） | **不能** |
| 本地 Release（有材料） | 根目录 `signing.properties` 指向的私有 keystore | `app-release.apk` | 能 | 能，需自行保管与审计 |
| 正式 Release（CI） | 四个 secrets 注入的私有 keystore | `app-release.apk` | 能 | 能，发布只能由用户手动 dispatch |

`app/build.gradle` 的读取顺序是：**根目录 `signing.properties` → 四个 `-Psigning.*` 属性 → 都没有则不签名**。四个值（store 文件、store 口令、key alias、key 口令）缺任意一个都按“无材料”处理，只打一条 `logger.warn`（不打印任何值），release 产物变成 `app-release-unsigned.apk`。

- 仓库不再携带任何签名材料：`gradle.properties` 没有示例签名属性，`signing/example.jks` 已移出仓库，`.gitignore` 覆盖 `*.jks`、`*.keystore`、`signing/` 与 `signing.properties`。
- 无材料时的 unsigned 产物只适合本地检查 R8 与打包是否通过，**不能安装、不能分发**。
- 需要“缺材料就让构建失败”的场景（CI 非 PR 构建与发布）用 `-PrequireSigning=true`：配置期直接抛 `GradleException`，错误文案是 `release signing material is required`，不会悄悄产出 unsigned 包。

### 私有 keystore 的生成与保管

> 以下只描述流程，不包含任何口令、别名或路径取值；agent 不生成、不读取、不记录密钥材料。

1. 在仓库外（受控目录或离线介质）生成私有 keystore，例如 `keytool -genkeypair -v -keystore <仓库外路径>.jks -alias <别名> -keyalg RSA -keysize 4096 -validity 10000`；口令由用户自己设定并保管。
2. **离线双备份**：keystore 文件与口令分开保存在两处以上离线介质；只要还有一份备份就能继续发布，全部丢失则无法再发布同包名更新。
3. **不轮换原则**：`applicationId` 与签名密钥一经发布即固定；轮换等于换 App（已安装用户无法覆盖升级），只有确认放弃存量升级时才考虑。
4. 不要把 keystore、口令或导出的 base64 写进仓库、Issue、聊天记录或日志。

### 本地接线（`signing.properties`）

在仓库根目录创建被 gitignore 的 `signing.properties`（键名固定，值由用户填写，不提交）：

| 键 | 含义 |
| --- | --- |
| `storeFile` | keystore 路径（相对仓库根或绝对路径） |
| `storePassword` | keystore 口令 |
| `keyAlias` | 密钥别名 |
| `keyPassword` | 密钥口令 |

### CI 接线（四个 secrets）

Build workflow 的非 PR 构建（以及手动 dispatch 的 Release workflow）从以下 secrets 取材料，解码到 `$RUNNER_TEMP/keystore.jks`（不落工作区），构建后以 `if: always()` 删除：

| Secret | 内容 |
| --- | --- |
| `SIGNING_STORE_BASE64` | keystore 文件的 base64 编码 |
| `SIGNING_STORE_PASSWORD` | keystore 口令 |
| `SIGNING_KEY_ALIAS` | 密钥别名 |
| `SIGNING_KEY_PASSWORD` | 密钥口令 |

非 PR 构建带 `-PrequireSigning=true`：secrets 缺失时 job 在配置期失败，不会产出 unsigned 包。PR 构建不读 secrets，`assembleRelease` 产出 unsigned APK，只用于验证 R8 与打包链路。

### 发布流程（只能用户手动执行）

- `release.yml` 只有 `workflow_dispatch`，输入 tag 必须是 `vX.Y.Z`。
- 发布前断言：产物里必须存在且只存在签名版 `app-release.apk`，出现 `*-unsigned.apk` 直接失败；`gh release create` 的 `--target` 绑定当前 `GITHUB_SHA`。
- **agent 不触发 `release.yml`、不读取密钥、不执行发布**；发布由用户在自己的会话里手动 dispatch。
- 发布前建议本地对产物执行 `apksigner verify --verbose <apk>` 确认签名有效（不在仓库记录证书指纹值）。

### 覆盖安装验证

| 场景 | 步骤 | 预期 |
| --- | --- | --- |
| 同签名覆盖安装 | 先装旧版（同一签名），再 `adb install -r` 新版签名包 | 成功，应用数据保留；启动后信任 / 授权 / 收藏仍在 |
| 异签名覆盖安装 | 安装与已装版本不同密钥签名、同 `applicationId` 的包 | 失败，`INSTALL_FAILED_UPDATE_INCOMPATIBLE`；不得用“直接卸载”静默处理（卸载会清数据），必须由用户决定 |

这两条属于设备点位，登记在[任务看板](task-board.md) TB-ENG-013 的统一回归清单；本文只写步骤与预期，不代表已验证。

## D. 仪器测试（androidTest）

> 口径核对：2026-10-22。CI **只编译**仪器测试、不运行；设备执行按“逐棒授权”处理——只有用户明确发起时才可执行。

### 已具备什么

- `app/src/androidTest` 有两个类：`ExampleInstrumentedTest`（模板）与 `core/codec/h264/H264MediaCodecSmokeTest`（MediaCodec 桥冒烟）。
- 编译已进 CI：Build workflow 的 `build` job 执行 `./gradlew :app:assembleDebugAndroidTest`，androidTest 源码与生产类不匹配时 CI 直接失败。
- 没有 CI 模拟器 job（已裁决）：仪器测试不在 CI 门禁内，也不作为任何条目的完成证据。

### 命令

```bash
# 只编译仪器测试 APK（CI 已覆盖，不需要设备）
./gradlew :app:assembleDebugAndroidTest

# 在已连接设备/模拟器上运行（需要 adb 可用、API 25+，且用户明确授权）
./gradlew :app:connectedDebugAndroidTest
```

### `H264MediaCodecSmokeTest` 的边界

- 它验证的是“能创建 / 配置 / 释放”：AVC 编解码器出现在系统列表、`H264Codec.androidRegistration()` 的描述符正确、编码器能拿到输入 Surface、解码器能配置并幂等释放。
- 解码器配置时 CSD 传 `null`（不伪造 SPS/PPS 字节）。部分设备与软解实现会因此直接拒绝配置，**失败不等于实现缺陷**。
- 通过也不证明真实出画、帧率、SPS/PPS 时序或硬解路径可用；这些点位归[任务看板](task-board.md)中 TB-ENG-013 的统一回归。
- 手动运行的结果只作为排查线索，不构成本仓库任何条目的完成证据。

### 结果与日志位置

| 输出 | 路径 |
| --- | --- |
| 原始结果（XML 与二进制） | `app/build/outputs/androidTest-results/` |
| HTML 报告 | `app/build/reports/androidTests/` |
| 失败时的设备日志 | `logcat`（自行抓取；文档不记录设备标识） |

## E. 清单

### 当前已经具备

- Gradle Wrapper 8.11.1，可直接执行构建。
- 可用的 JDK（当前为 JDK 21）与 Android SDK 解析。
- API 35 编译/目标配置与 API 25 最低兼容配置。
- Debug 自动签名与可用的 Debug APK 构建。
- Release 技术打包能力（有材料签名 / 无材料 unsigned 两条路径）。
- 当前功能所需的 Manifest 权限。
- 远端 Build CI 成功记录。

### Debug 启动仍需

- 一台 API 25+ 的 Android 设备或模拟器。
- 可用的 adb 与安装通道；本次未确认设备数量。
- 设备上的通知权限、悬浮窗权限与 MediaProjection 授权。
- 完整服务端/客户端验证需要两台设备、同一局域网与可用的 NSD/mDNS。

### 正式发布仍需

- 私有 keystore 与安全的保管/备份流程。
- 本地 `signing.properties` 或 CI secrets 注入。
- 与已安装版本一致的签名身份与升级验证。
- 正式发布流程、版本号与商店/分发渠道检查。
- 同签名覆盖安装与异签名拒绝的实测（步骤见 C 节，设备执行归 TB-ENG-013）。

### 不需要

- 手工安装 Gradle。
- NDK、CMake 或 native 工具链。
- Firebase / `google-services.json`。
- 为 Debug 准备 release 证书。
- 当前版本不需要音频或无障碍/反控权限。

## 建议命令

以下命令只作为操作参考，本次未执行安装：

```bash
# 编译 Debug
./gradlew assembleDebug

# 安装到已连接设备（需要 adb 与设备）
./gradlew installDebug

# 单元测试与静态检查
./gradlew testDebugUnitTest lintDebug

# 本地 Release 打包（签名来源见上文）
./gradlew assembleRelease

# 编译仪器测试 APK（CI 已覆盖，不需要设备）
./gradlew assembleDebugAndroidTest

# 在受权设备上运行仪器测试（需要 adb 与设备，见 D 节边界说明）
./gradlew connectedDebugAndroidTest

# 查看设备是否可用（只报告有无/数量，不记录标识）
adb devices
```

Windows 环境可将 `./gradlew` 换成 `gradlew.bat`。

## 未知与未验证

- 当前是否有可用 Android 设备、设备数量与系统版本：未知；`adb` 不在 PATH，未检查。
- Android SDK 的具体路径与 platform-tools 内容：未读取；构建成功只能证明构建所需 SDK 可用。
- 设备上的权限弹窗、悬浮窗显示、MediaProjection 采集与 NSD 发现：未实机验证。
- 两台设备之间的端到端共享、API 25 行为和不同厂商限制：未验证。
- 已安装 App 的签名与覆盖安装结果：未检查。
- `connectedDebugAndroidTest` 从未在设备或模拟器上执行，两个仪器测试（`ExampleInstrumentedTest`、`H264MediaCodecSmokeTest`）的实际结果未知。
- Release workflow 的实际发布：未执行。
