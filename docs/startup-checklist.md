# 启动与签名检查清单

> 用途：回答“现在要把它跑起来还缺什么”，并区分 Debug 启动、正式发布与不需要准备的项。
> 证据口径：本文只记录可核对的环境存在性、构建结果与 CI 结果；未安装 APK、未连接或修改设备，也未做服务端/客户端实机联调。设备数量与系统版本未知时不猜测。
> 检查快照：2026-10-17，仓库提交 `6babed9`。

## 结论速览

- **当前环境可以编译打包**：Gradle Wrapper、JDK、Android SDK 解析和签名配置都能完成 `assembleDebug` 与 `assembleRelease`。
- **Debug 启动不要求 release 证书**：Debug 使用 AGP 自动签名；缺的是可用的 Android 设备/模拟器和 adb 安装通道，以及设备上的运行时授权。
- **正式发布不能使用仓库内示例签名**：示例签名只够技术打包，不具备正式分发所需的私密性与升级连续性。正式发布必须换成私有 keystore 或 CI secrets。
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
| Release 构建 | 通过（仅技术验证） | `./gradlew :app:assembleRelease --offline` 成功；当前使用仓库内示例签名回退，不能用于正式分发 |
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

| 场景 | 签名来源 | 能否安装 | 能否正式分发 |
| --- | --- | --- | --- |
| Debug | AGP 自动生成的 debug 签名 | 能 | 不能 |
| 本地 Release（当前回退） | 仓库内公开示例签名材料与示例属性 | 技术上能 | **不能** |
| 正式 Release | 私有 keystore + `signing.properties`，或 CI secrets 注入 | 能 | 能，需自行保管与审计 |

当前 `app/build.gradle` 的 release 配置会优先读取根目录的 `signing.properties`；该文件已被 `.gitignore` 忽略。文件不存在时，会回退到 `gradle.properties` 中的示例签名属性。因此：

- **技术上可以打包**：本次 `assembleRelease` 成功，已有 release APK 产物。
- **不能用于正式分发**：示例材料是公开的，无法证明发布者身份，也不能作为长期升级与信任链的根；任何拿到仓库的人都能用它签出同包名 APK。
- **示例签名只适合本地构建验证**：不能上传应用商店或对外发布。

正式发布需要：

1. 在仓库外或受控位置保管私有 keystore，并做好离线备份。
2. 本地使用被忽略的 `signing.properties`（包含 keystore 路径、store 口令、key alias、key 口令），或由 CI secrets 注入同等材料。
3. 确保真实 keystore、口令和 `signing.properties` 不进入 Git 历史、日志或构建产物。
4. 固定 `applicationId` 与签名身份；后续版本必须使用同一签名密钥。
5. 在发布前验证：干净环境可构建、产物签名正确、升级安装路径可用。

CI 的 Build workflow 已通过 secrets 注入签名材料并成功构建，说明当前仓库的 CI 签名通道可用；Release workflow 是手动触发的发布流程，本次未执行。文档不复述任何签名值、口令或密钥细节。

### 覆盖安装与签名冲突

如果设备上已经安装了同 `applicationId`、但由不同密钥签名的 App：

- `adb install -r` 或 `installDebug` 可能失败，典型结果是签名不一致导致的更新不兼容。
- 不能通过“直接卸载”静默解决：卸载会清除应用数据。
- 应优先让新包使用与已安装版本相同的签名；如果确实需要换签名，必须先告知用户会清数据，由用户决定是否卸载。
- 本次没有检查任何已安装 App 的签名，也没有卸载或修改设备。

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
- Release 技术打包能力（示例签名回退）。
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
- 清除仓库中的示例签名回退对正式构建的影响，确保正式包只使用私有签名。

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
