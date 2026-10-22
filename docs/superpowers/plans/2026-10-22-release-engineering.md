# TB-ENG-001 测试与发布工程 + TB-ENG-002 发布签名加固 联合实施计划（2026-10-22）

> 基线：1443 tests / 144 classes（HEAD `767b75e`）
> 设备/仪器/长时项归 TB-ENG-013。任何 agent 不生成、不读取、不记录密钥材料，不触发 `release.yml`。

## 1. 结论摘要

- TB-ENG-001 三项欠账可在不改业务代码的前提下完成：① `minifyEnabled false` → 开 R8 并补 keep；② CI 无独立测试步骤 → 拆出 test job（单测双变体 + lint 双变体 + 计数守卫）；③ androidTest 只保证可编译与本地可跑链路，不引入 CI 模拟器 job。
- R8 最大风险是**静默数据丢失**：`TlsPinStore`/`AuthorizationStore`/`FavoriteStore` 三处 Gson 落盘模型以字段名为 JSON 键，解析失败被吞按空数据处理 → 字段被混淆后升级用户会静默丢信任/授权/收藏。这是 keep 规则的第一归属。
- TB-ENG-002 核心是示例签名与正式签名隔离：`gradle.properties` 内置示例口令、`signing/example.jks` 随仓库分发、缺 `signing.properties` 时回退示例材料 → 任何人可签同包名 APK。
- 4 个批次：CI 独立测试 → R8/keep → androidTest 链路 → 签名分离。新增 1 个 JVM 契约测试类（6 条）+ 1 个计数守卫脚本；基线 1443/144 → 1449/145。

## 2. 现状核对（要点）

- release：`minifyEnabled false`；`proguardFiles` 已配置但从未生效；`app/proguard-rules.pro` 只有模板注释。
- 签名：优先根目录 `signing.properties`（已 gitignore），否则回退 `gradle.properties` 的示例 `signing.*`；`signing/example.jks` 在仓库内；`.gitignore` 未覆盖 `*.jks`/`*.keystore`/`signing/`。
- CI：`build.yml` 把测试/lint/打包混在一条 `./gradlew build` 里，失败无法归因；`checks.yml` 只有三项 Python 检查；`release.yml` 仅手动 dispatch，其产物路径与 build 上传结构不匹配、`--target "$GITHUB_BASE_REF"` 在 dispatch 下为空、未区分 signed/unsigned。
- 反射面：业务代码无反射；Gson 落盘模型三处（授权/信任/收藏）；gRPC 的 Channel/Server/NameResolver/LoadBalancer provider 走 ServiceLoader 需显式 keep；protobuf-javalite 生成消息字段需保留；Parcelable/枚举由 AGP 默认规则覆盖；`SourceFile,LineNumberTable` 默认未保留需补。
- androidTest：仅 2 文件；`H264MediaCodecSmokeTest` 是"只编译"契约（null CSD 在部分设备失败，失败≠实现缺陷），不进 CI 门禁。

## 3. 批次划分

| 批次 | 主题 | 主要文件 | 新增 | 风险 |
| --- | --- | --- | --- | --- |
| B1 | CI 独立测试 job + 测试计数守卫 | `.github/workflows/build.yml`、新增 `scripts/checks/check_test_count.py` 与 `test_baseline.json` | 脚本守卫 | 低 |
| B2 | 启用 R8 与 keep 规则 | `app/build.gradle`、`app/proguard-rules.pro`、`BuildEngineeringContractTest` | +3 JVM | 高（运行时，归 TB-ENG-013 冒烟） |
| B3 | androidTest 工程链路 | `docs/startup-checklist.md`、`H264MediaCodecSmokeTest` Javadoc | 无 | 低 |
| B4 | 签名分离与发布流程加固 | `app/build.gradle`、`gradle.properties`、`.gitignore`、`build.yml`、`release.yml`、`docs/startup-checklist.md`、`BuildEngineeringContractTest`、`signing/example.jks`（git rm） | +3 JVM | 中 |

顺序 B1 → B2 → B3 → B4。

## 4. B1 详细方案（首个实施批）

1. `build.yml` 拆两个 job：
   - `test`（无 secrets，PR 也跑）：`./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest` → `python scripts/checks/check_test_count.py` → `./gradlew :app:lintDebug :app:lintRelease`；`always()` 上传 `app/build/test-results/`、`app/build/reports/`。
   - `build`（保留 job id，`release.yml` 继续依赖）：PR 跑 `:app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest`；非 PR 先由 secrets 生成 keystore（仍落工作区，B4 再改 `$RUNNER_TEMP`）再同上。上传产物只含 `app/build/outputs/apk/**`。
   - 两 job 均 `permissions: contents: read`；`timeout-minutes` 提到 25。
2. 新增 `scripts/checks/check_test_count.py`（仅标准库）：
   - 参数 `--results`（默认 `app/build/test-results/testDebugUnitTest`）、`--baseline`（默认 `scripts/checks/test_baseline.json`）；
   - 汇总 `<testsuite tests= failures= errors= skipped=>`；断言 failures=errors=skipped=0；
   - current < baseline（tests 或 classes）→ 非零退出并打印差值；只增时提示"请在同一提交更新 baseline"；
   - `test_baseline.json` 初始 `{"tests": 1443, "classes": 144}`。
3. 不跑 `connectedAndroidTest`；`assembleDebugAndroidTest` 保留在 build job。

验证：`./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest`；`python scripts/checks/check_test_count.py`；故意把 baseline 调大 1 确认非零退出后改回。CI 配置语法靠 review + 真实 push 验证。

## 5. B2–B4 概要

- **B2**：`minifyEnabled true`、显式 `shrinkResources false`；`proguard-rules.pro` 写入带归属注释的规则（`SourceFile,LineNumberTable`；Gson 三处 DTO 五个类；`GeneratedMessageLite` 字段；gRPC 四个 provider）；`-dontwarn` 只在出现对应警告时按归属添加；新增 `BuildEngineeringContractTest` 3 条（minify 开启、Gson keep 在、gRPC/protobuf keep 在）。验证：`assembleRelease`（无材料 → unsigned，R8 必须成功）、`testReleaseUnitTest`、`lintRelease`；读 `mapping/release/configuration.txt` 登记每条规则来源（库自带 vs 本文件）。设备冒烟 11 点位（冷启动、发现、TLS 首连、信任/授权/收藏持久化、共享观看一轮、音频、通知按钮、覆盖安装同/异签名、mapping 留存）归 TB-ENG-013。
- **B3**：`docs/startup-checklist.md` 新增仪器测试章节（编译/运行命令、smoke 边界、报告位置）；`H264MediaCodecSmokeTest` Javadoc 改为"CI 只编译；受权设备手动运行不构成完成证据"。不新增 CI 模拟器 job。
- **B4**：签名条件配置（`signing.properties` → `-Psigning.*` → 都没有则 release 不签名产出 unsigned；新增 `-PrequireSigning=true` 无材料即配置期失败）；`gradle.properties` 删示例 `signing.*` 四行；`.gitignore` 加 `*.jks`/`*.keystore`/`signing/`；`git rm signing/example.jks`（已裁决由 coder 执行，不读取内容）；`build.yml` 的 keystore 改落 `$RUNNER_TEMP`、构建后 `if: always()` 删除、非 PR 传 `-PrequireSigning=true`、产物只传 `apk/**`；`release.yml` 修产物断言（存在且仅存在签名版 `app-release.apk`）、`--target "$GITHUB_SHA"`、tag 格式校验；`docs/startup-checklist.md` §C 重写（Debug/本地 Release/正式 Release 三行矩阵 + 密钥保管备份轮换原则 + 键名清单 + 覆盖安装验证 + "agent 不读密钥不触发发布"）；`BuildEngineeringContractTest` +3（gradle.properties 无签名口令、gitignore 覆盖、requireSigning 存在且不再引用 example.jks）。
- 签名推荐形态：本地 `signing.properties`（仓库外 keystore）+ CI 四个 secrets（`SIGNING_STORE_BASE64`/`SIGNING_STORE_PASSWORD`/`SIGNING_KEY_ALIAS`/`SIGNING_KEY_PASSWORD`），密钥由用户生成并离线双备份；给用户的手动步骤清单（生成/备份/本地接线/CI 接线/发布前 `apksigner verify`/覆盖安装验证/不轮换原则）写进 `startup-checklist.md`。

## 6. 裁决记录（2026-10-22，主 K3 定）

1. CI 拆 test/build 两 job（并行、失败归因清晰）。
2. 测试计数守卫接受，baseline 文件随每批手工更新。
3. CI 模拟器仪器 job 不实施。
4. `signing/example.jks` 由 coder 直接 `git rm`（示例材料，git 历史可溯）。
5. `mapping.txt` 不上传 CI 产物（本地构建时用户自行保留）。
6. 版本号 `-P` 注入不做。

## 7. 风险与边界

| 风险 | 批次 | 缓解 |
| --- | --- | --- |
| CI YAML 无 JVM 覆盖 | B1/B4 | review + 真实 push 验证；`build` job id 保持 |
| R8 静默降级 | B2 | keep 归属逐条登记；11 个设备冒烟点位归 TB-ENG-013；修复只许加 keep/dontwarn |
| Gson 数据丢失 | B2 | 五个 DTO 类 keep 字段名；升级持久化点位单列 |
| 条件签名写错 → unsigned 发布 | B4 | `-PrequireSigning` + `release.yml` 产物断言双保险 |
| 计数守卫摩擦 | B1 | 提示语写清同一提交更新 baseline |

明确不做：不生成/读取/记录密钥；不触发 release.yml；不建新依赖与 CI 模拟器 action；不开 `shrinkResources`；不改 `applicationId`/`versionCode`/`versionName`；不改业务代码；不修既有 146 条 lint warnings；不在设备跑 connectedAndroidTest；不做 Play App Signing/商店上架；mapping.txt 不上传；不清理无关死代码。

## 8. 验证口径

每批：清空 test-results 后全量 `:app:testDebugUnitTest` 0 failures（数字只增不减）；`./gradlew build :app:assembleDebugAndroidTest`；`:app:lintDebug`（B2 起加 `:app:lintRelease`）0 errors；B2 起加 `:app:assembleRelease` 成功（无材料时产物为 unsigned 属预期）；文档/脚本改动跑三项 checks。

## 9. B2 落地证据（2026-10-22）

### 9.1 规则来源核对（`app/build/outputs/mapping/release/configuration.txt`）

参与本次 shrink 的规则段共 26 个来源文件加 1 个 `<unknown>` 尾段（728 行）。与本批相关的归属如下：

| 规则 | 来源 | 说明 |
| --- | --- | --- |
| `-keepattributes SourceFile,LineNumberTable`、`-renamesourcefileattribute SourceFile` | `app/proguard-rules.pro` | AGP 默认文件不保留这两项，只有本项目规则能提供 |
| `-keepattributes Signature` | `app/proguard-rules.pro`（显式重申） | AGP 默认 `proguard-android-optimize.txt` 与 gson 的 `META-INF/proguard/gson.pro` 都已保留；本文件重复一条，使五份落盘 DTO 的泛型要求不依赖另外两个文件的现状 |
| 五个 Gson DTO 的 `-keep class … { <fields>; }` | `app/proguard-rules.pro` | gson 自带规则只覆盖 `@SerializedName`、`TypeToken` 与 `TypeAdapter` 家族，不保留无注解 DTO 的字段名 |
| `-keep class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }` | `app/proguard-rules.pro` | 26 个来源段中没有 protobuf 自带规则 |
| 四个 gRPC provider 的 `-keep class … { *; }` | `app/proguard-rules.pro` | 同上，没有 grpc 自带规则段；ServiceLoader 按类名装载，类名与无参构造必须保留 |
| `-dontwarn org.codehaus.mojo.animal_sniffer.*` | okio 的 `META-INF/proguard/okio.pro` | 候选警告之一已由库覆盖，本项目未写 `-dontwarn` |
| Kotlin 运行时相关（`coroutines.pro` 两份、`okio.pro`、`androidx-annotations.pro`、各 AndroidX AAR 的 `proguard.txt`） | 传递依赖 | 与本批规则无关，仅登记以免把库规则误算成项目规则 |

`-dontwarn`：首轮 `:app:assembleRelease` 的 R8 输出 **0 条 `Missing class` 警告**，因此本文件未添加任何 `-dontwarn`。计划里列出的四个候选（`animal_sniffer`、`j2objc`、`javax.annotation`、`jetty.alpn`）中，只有 `animal_sniffer` 被覆盖且已由 okio 提供，其余本次未出现。

### 9.2 混淆结果抽查（`mapping.txt`、`seeds.txt`、`usage.txt`）

- `mapping.txt`：`AuthorizationStore$Document`、`AuthorizationStore$EntryRecord`、`TlsPinStore$Document`、`TlsPinStore$PinRecord`、`FavoriteStore$FavoriteRecord` 五个类名均为 identity 映射（左侧原名、右侧同名）；字段没有出现在重命名表里，即保持原名。
- `seeds.txt`：五个 DTO 的全部字段按原名被 keep，例如 `TlsPinStore$PinRecord: java.lang.String fingerprint`、`AuthorizationStore$EntryRecord: java.lang.String deviceId`、`FavoriteStore$FavoriteRecord: boolean customAlias`；生成的 protobuf 字段（`AccessUnit`、`Capabilities`、`Descriptor` 等的 `_` 结尾字段）同样保留；四个 provider 与 `GeneratedMessageLite` 的字段也在列。
- `usage.txt`：五个 DTO 没有任何字段被删除；被删的只有 `*Store$InstanceHolder`、仅测试用到的 `FavoriteStore` 构造重载与被合并的枚举，与本批规则无关。
- 混淆后仅方法被改名（例如 R8 合成的 `-$$Nest$fgetdeviceId` 访问器变成 `a`），这是预期行为：Gson 只读字段名，不读访问器。

### 9.3 本批验证

- `:app:assembleRelease`（本地存在 `signing.properties`，产物为已签名；无材料环境应由 B4 产出 unsigned）：BUILD SUCCESSFUL，无 R8 警告。
- 全量 **1446 tests / 145 classes / 0 failures / 0 errors / 0 skipped**（B1 后 1443/144，+3 条 / +1 类），`scripts/checks/test_baseline.json` 同批更新为 1446/145。
- `./gradlew build :app:assembleDebugAndroidTest` 通过；`:app:lintDebug` 0 errors / 146 warnings（与基线同数）、`:app:lintRelease` 0 errors / 136 warnings。
- 设备侧 11 个点位（冷启动、发现、TLS 首连、信任持久化、授权持久化、收藏与位置持久化、共享观看一轮、音频一轮、通知按钮/审批弹窗、覆盖安装同/异签名、mapping 留存）归 TB-ENG-013。
