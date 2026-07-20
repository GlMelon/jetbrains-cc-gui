# CC GUI 开发指南（D1）

> 面向新上手开发者的工程文档：环境准备、构建调试、协议代码生成、三套测试、Plugin Verifier、Provider 六路径验收、分支与发布流程。
>
> 读完本文应能：① 在本地跑起 `runIde`；② 改动 Java 枚举后知道如何让前端拿到新协议常量；③ 独立跑通 Java / Webview / ai-bridge 三套测试；④ 知道 Provider 改动要核对哪六条路径；⑤ 知道发版打 tag 的流程。
>
> 本文是 `docs/comprehensive-optimization-directions.md` 第 9 节 D1 的落地产出，架构边界以仓库根 `AGENTS.md` 为最高准则，冲突时以 `AGENTS.md` 为准。

---

## 0. 仓库结构速览

```
jetbrains-melon-cc-gui/          仓库根（ Gradle 项目根 ）
├── build.gradle                 主构建脚本（ task / 依赖 / verifier / runIde ）
├── gradle.properties            Gradle 配置
├── gradle/wrapper/              Gradle Wrapper（ 8.14.5 ）
├── settings.gradle              rootProject.name = 'idea-claude-code-gui'
├── checkstyle.xml               Checkstyle 规则（ 仅作用于 main 源码 ）
├── local.properties.example     本地 JDK / Node 路径模板（ 复制为 local.properties ）
├── sandbox-idea.properties      可选：runIde sandbox 覆盖配置
├── CHANGELOG.md                 发版变更（ patchPluginXml 会读取首节 ）
├── CONTRIBUTING.md              协作规范（ 语言 / 版本号 / 分支 ）
├── AGENTS.md                    架构准则（ 最高优先级 ）
├── src/main/java/.../claudecodegui/      Java 后端（ 业务权威 ）
├── src/main/resources/META-INF/plugin.xml 插件描述与扩展点声明
├── src/test/java/.../claudecodegui/      Java 单元测试（ JUnit 4 ）
├── webview/                     React + TypeScript 前端（ 只渲染 ）
│   ├── package.json             vite / vitest / react 19
│   ├── vitest.config.ts
│   ├── scripts/generate-protocol-types.mjs   协议 SSOT 主生成器
│   └── src/generated/protocol.ts             ⚠ 自动生成，禁手改
├── ai-bridge/                   Node 子进程（ CLI/SDK 桥接 ）
│   └── **/*.test.{js,mjs}       node:test 测试
└── docs/                        设计文档与本指南
```

三层运行时（ 详见 `AGENTS.md` 第 0 节 ）：**Java 后端**承载业务权威；**Webview** 经 JCEF 嵌入，只渲染与采集输入；**ai-bridge** 是独立 Node 进程，负责 CLI/SDK 进程管理与消息流。Java ↔ ai-bridge 之间是 NDJSON 字符串契约，**禁止 Node 类型泄漏到 Java**。

---

## 1. 环境要求

### 1.1 JDK

| 项 | 值 | 来源 |
| --- | --- | --- |
| 源码 / 目标版本 | **Java 17** | `build.gradle` `sourceCompatibility` / `targetCompatibility` / `toolchain.languageVersion = JavaLanguageVersion.of(17)` |
| 编译 `--release` | 17 | `build.gradle` `tasks.withType(JavaCompile).configureEach { options.release.set(17) }` |
| CI 跑测试用的 JDK | **JDK 21**（ temurin ） | `.github/workflows/tests.yml` / `build.yml` 均 `java-version: '21'` |
| 本地首选 | JDK 17 或更高 | `local.properties.example` 注释「must be JDK 17 or higher」 |

> 说明：构建脚本 `toolchain` 锁定 17，CI 用 21 跑测试是向上兼容验证；本地装 17 或 21 均可，**不要低于 17**。

**自定义 JDK 路径**（ 可选，`build.gradle` 会读 `local.properties` ）：

```properties
# local.properties（ 不要提交到 git ）
java.home=C:\\Program Files\\Java\\jdk-17
# 或
org.gradle.java.home=/usr/lib/jvm/java-17-openjdk
```

> 注意 `runIde` 任务**故意跳过** `javaLauncher` 覆盖（ `build.gradle` `if (it.name != 'runIde')` ），继续使用 IntelliJ 自带 JBR 以保留 JCEF 支持。自定义 `java.home` 只影响 `JavaCompile` / 其他 `JavaExec`，不影响 `runIde`。

### 1.2 Node.js

| 项 | 值 | 来源 |
| --- | --- | --- |
| CI 固定版本 | **Node 22.12.0** | `.github/workflows/tests.yml` / `build.yml` `node-version: '22.12.0'` |
| 本地最低版本 | 推荐 Node 20 LTS 或更高（ vitest 3.x / vite 7.x 的实际下限 ） | `webview/package.json` 未声明 `engines`，**待确认**是否需要写入 `engines` 字段 |
| 用途 | 构建 `webview/`（ vite ）、运行 ai-bridge、跑三套测试中的两套 | `build.gradle` `buildWebview` task |

**自定义 Node 路径**（ 可选 ）：

```properties
# local.properties
node.path=C:\\Program Files\\nodejs\\node.exe
# 或指向目录：node.path=/usr/local/bin
```

`build.gradle` 的 `buildWebview` task 会解析 `node.path`：若指向 `node` 可执行文件，则查找同目录的 `npm`；若指向目录，则依次尝试 `bin/npm` 与 `npm`，并把该目录前置到 `PATH`。

### 1.3 Gradle

| 项 | 值 | 来源 |
| --- | --- | --- |
| Gradle 版本 | **8.14.5** | `gradle/wrapper/gradle-wrapper.properties` `distributionUrl=...gradle-8.14.5-bin.zip` |
| 下载镜像 | 腾讯云（ `mirrors.cloud.tencent.com/gradle` ） | 同上 |
| Daemon | 关闭（ `org.gradle.daemon=false` ） | `gradle.properties` |
| Configuration Cache | 注释掉（ 未启用 ） | `gradle.properties` |
| 构建插件 | `org.jetbrains.intellij.platform` **2.10.5** | `build.gradle` `plugins { ... }` |
| Checkstyle | 10.12.5，配置 `checkstyle.xml`，**只扫 main 源码** | `build.gradle` `checkstyle { ... sourceSets = [project.sourceSets.main] }` |

**一律用 `./gradlew`（ Linux/macOS ）或 `gradlew.bat`（ Windows ）**，不要自行安装 Gradle，避免版本漂移。

### 1.4 目标 IDE 与兼容范围

| 项 | 值 | 来源 |
| --- | --- | --- |
| 默认目标 IDE | **IntelliJ IDEA Community 2024.3.1** | `build.gradle` `intellijIdeaCommunity('2024.3.1')` |
| 可切换 targetIde | `IC`（ 默认 ）/ `PC`（ PyCharm Community 2024.3 ）/ `PY`（ PyCharm Professional 2024.3 ）/ `RD`（ Rider 2025.3.2 ） | `build.gradle` `def targetIde = project.findProperty('targetIde') ?: 'IC'` |
| 插件兼容范围 | `sinceBuild=233`，`untilBuild=263.*` | `build.gradle` `patchPluginXml { sinceBuild / untilBuild }` |
| 插件 ID | `com.github.idea-claude-code-gui` | `src/main/resources/META-INF/plugin.xml` |
| 捆绑插件依赖 | `com.intellij.java`、`PythonCore`（ 编译期，optional 运行 ）、`org.jetbrains.plugins.terminal` | `build.gradle` `bundledPlugin(...)` / `plugin(...)` |

切换目标 IDE（ 例：PyCharm Community ）：

```bash
./gradlew runIde -PtargetIde=PC
./gradlew buildPlugin -PtargetIde=PC
```

> `targetIde=PC|PY` 会排除 `JavaContextCollector.java` / `JavaClassNavigationSupport.java`；`targetIde=RD` 额外排除 `PythonContextCollector.java`（ 见 `build.gradle` `sourceSets.main.java.exclude` ）。

---

## 2. 首次准备

```bash
# 1. 克隆后进入仓库根
cd jetbrains-melon-cc-gui

# 2.（ 可选 ）配置本地 JDK / Node 路径
cp local.properties.example local.properties
#   编辑 local.properties，按需取消 java.home / node.path 注释

# 3. 安装前端依赖
cd webview && npm install && cd ..

# 4. 安装 ai-bridge 依赖（ 本地调试与打包都需要 ）
cd ai-bridge && npm install && cd ..

# 5. 首次跑一次 IDE 沙箱（ 会自动触发 buildWebview ）
./gradlew runIde
```

> `npm install` 仅在首次或依赖变更后需要；`./gradlew runIde` 会通过 `compileJava dependsOn buildWebview` 自动触发前端构建，无需手动 `npm run build`。

---

## 3. runIde 与调试

### 3.1 命令行 runIde

```bash
./gradlew runIde                      # 默认 IC（ IDEA Community 2024.3.1 ）
./gradlew runIde -PtargetIde=PC       # PyCharm Community
./gradlew runIde -PtargetIde=PY       # PyCharm Professional
./gradlew runIde -PtargetIde=RD       # Rider
```

`runIde` 任务在 `build.gradle` 中预设了若干开发期 JVM 参数：

- `-Djcef.sandbox.enable=false`
- `-Didea.auto.reload.plugins=false`、`-Didea.dynamic.plugins.allowed=false`（ 关掉热卸载，避免改文件触发意外重载 ）
- `-Didea.is.internal=true`（ 开启内部模式 ）
- `-Didea.plugins.load.timeout=60000`
- `-Xlog` / `idea.log.debug.categories=#com.github.claudecodegui`（ 打开本插件包的 debug 日志 ）

若仓库存在 `build/stable-ai-bridge/` 目录，`runIde` 会 `systemProperty 'claude.bridge.path'` 指向它，便于用一份预解压的 ai-bridge 调试；该目录由 CI / 打包流程产出，本地可忽略。

若 `sandbox-idea.properties` 存在，`runIde` 的 `doFirst` 会把它复制到 `build/idea-sandbox/config/idea.properties`，用于覆盖沙箱 IDE 的默认配置。

### 3.2 IntelliJ IDEA 内调试

推荐方式：

1. 用 IntelliJ IDEA 打开仓库根（ `build.gradle` 会被 Gradle 导入 ）。
2. 在 **Run/Debug Configurations** 里新增 **Gradle** 任务，`Tasks` 填 `runIde`。
3. 若要断点调试插件自身 Java 代码：`runIde` 默认已是 `JavaExec`，IDE 识别后可直接在 `src/main/java/...` 中打断点并 Attach Debugger；或改用 `./gradlew runIde --debug-jvm` 让 IDE 远程附加。
4. Webview 调试：沙箱 IDE 启动后，在工具窗口里右键 JCEF 浏览器选 `DevTools`（ `runIde` 已置 `idea.is.internal=true`，DevTools 入口可见 ）。
5. `runIde` 任务已把 `standardOutput` / `errorOutput` 重定向到当前 `System.out` / `System.err`，命令行可直接看到插件日志。

### 3.3 跳过 / 强制 Webview 构建

```bash
./gradlew runIde -PskipWebview=true   # 跳过前端构建（ 前端没改、想加速时用 ）
```

`-PskipWebview=true` 会让 `buildWebview` task 的 `onlyIf` 返回 false。若用此开关，请确保 `webview/dist/` 是最新产物，否则页面是旧的。

### 3.4 VConsole

`runIde` 触发的构建会把 `VITE_ENABLE_VCONSOLE=true` 传给 vite（ `build.gradle` `enableVConsoleInWebview` ），Webview 页面右下角会出现 vConsole 按钮，用于在 JCEF 内查看前端日志与网络。`buildPlugin` 打包时该开关为 false，不会进入发行包。

---

## 4. Protocol 代码生成（ SSOT ）

> 对应 `AGENTS.md` 总则三。协议消息名、payload 字段、业务枚举一律以 Java 枚举为单一真相源，前端从 `webview/src/generated/protocol.ts` 导入，**禁止前端手写字面量**。

### 4.1 主路径：mjs 直读 Java 枚举源

**生成器**：`webview/scripts/generate-protocol-types.mjs`

**触发方式**：`webview/package.json` 的 `prebuild` 脚本（ `npm run build` 会先跑 `prebuild` ）：

```text
prebuild: node scripts/extract-version.mjs && node scripts/generate-protocol-types.mjs
build:    tsc && vite build
```

即 **任何一次 `npm run build`（ 或 `gradlew buildWebview`，二者等价，都执行 `npm run build` ）都会自动重新生成协议类型**。开发者改动 Java 枚举后，**无需手动跑 codegen**，只要重跑 `buildWebview` / `runIde` / `buildPlugin` 即可。

**Java 枚举源**（ 生成器直读以下文件，regex 解析 `NAME("value",...)` ）：

| Java 源 | 生成到 TS 的常量 |
| --- | --- |
| `src/main/java/com/github/claudecodegui/protocol/UpstreamAction.java` | `UPSTREAM` |
| `src/main/java/com/github/claudecodegui/protocol/DownstreamEvent.java` | `DOWNSTREAM` |
| `src/main/java/com/github/claudecodegui/protocol/PermissionMode.java` | `PERMISSION_MODE` |
| `src/main/java/com/github/claudecodegui/protocol/ReasoningEffort.java` | `REASONING_EFFORT` |
| `src/main/java/com/github/claudecodegui/session/runtime/ProviderType.java` | `PROVIDER_TYPE` |
| `src/main/java/com/github/claudecodegui/protocol/CodexProtectedEnvKey.java` | `CODEX_PROTECTED_ENV_KEY` |
| `src/main/java/com/github/claudecodegui/dependency/VersionAction.java` | `VERSION_ACTION` |
| `src/main/java/com/github/claudecodegui/protocol/payload/ModelRegistryPayloadField.java` | `ModelRegistryPayloadWire` 接口 |
| `src/main/java/com/github/claudecodegui/common/CommonConstants.java`（ int 白名单 ） | `DEFAULT_CONTEXT_WINDOW` / `ONE_MILLION_CONTEXT_WINDOW` / `DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS` 等 |
| `src/main/java/com/github/claudecodegui/settings/PermissionDialogTimeoutSettings.java`（ int 白名单 ） | `MIN_*` / `MAX_*` 超时常量 |

**产物**：

- `webview/src/generated/protocol.ts`（ 主产物，前端 import ）
- `webview/src/generated/protocol-manifest.json`（ 副产品，供人工校验与反射交叉验证 ）

> **禁止** 手改 `protocol.ts` / `protocol-manifest.json`，文件头有 `⚠️ AUTO-GENERATED — DO NOT EDIT MANUALLY` 标注。CI 的 webview 测试 step 会先跑 `npm run prebuild`（ 含生成器 ），确保 `protocol.ts` 与 Java 源同步。

### 4.2 兼容路径：Gradle `generateProtocol` task（ 默认禁用 ）

`build.gradle` 中保留了一个可选的反射路径 task：

```bash
./gradlew generateProtocol -PgenerateProtocol=true
```

- 入口类：`com.github.claudecodegui.protocol.ProtocolManifestGenerator`
- 默认 `enabled = false`，**标准构建链不触发**（ mjs 直读 Java 源才是 SSOT 主路径 ）
- 仅在需要反射路径交叉校验、或人工核对 manifest 中间态时显式开启
- 产出 `webview/src/generated/protocol-manifest.json`

### 4.3 前端消费侧规范

```ts
// ✅ 正确：从 generated 导入
import { UPSTREAM, DOWNSTREAM, PERMISSION_MODE } from '@/generated/protocol';

// ❌ 禁止：手写字面量
const type = 'session.send'; // 违反 AGENTS.md 总则三
```

> 第二真相源债务：`webview/src/bridge/events/index.ts` 仍手写约 130 条下行事件字面量，是 `comprehensive-optimization-directions.md` 第 4 节 A8 待收敛项，不在 D1 范围内。

---

## 5. 测试

仓库有三套独立测试，对应三个技术栈。**任务边界：以下命令仅作记录，不在本轮 D1 落地中执行。**

### 5.1 Java 单元测试（ JUnit 4 ）

```bash
./gradlew test                                    # 跑全量 Java 测试
./gradlew test --tests "com.github.claudecodegui.bridge.NodeDetectorWslTest"  # 只跑某个类
```

源码位置：`src/test/java/com/github/claudecodegui/**/*Test.java`（ 约 237 个文件 ）。

**已知陷阱**：

- **`instrumentCode` / `instrumentTestCode` 已在 `build.gradle` 中禁用**（ `tasks.named('instrumentCode') { enabled = false }` / `instrumentTestCode` 同 ）。历史上 worktree 跑 `gradlew test` 需加 `-x instrumentTestCode` 规避 stale 插桩 class 导致的假绿；现在通过 `enabled = false` 根治。记忆参考 `gradle-instrument-test-code-stale-trap`：若你恢复插桩，务必同步恢复 `-x` 习惯或保留 `enabled=false`，否则新增 `@Test` 方法可能不被发现。
- **`checkstyleTest` 已禁用**（ `tasks.named('checkstyleTest') { enabled = false }` ），Checkstyle 只扫 `main` 源码。理由：测试 fixture 的 child-process main 必须写 `System.out` 供父进程捕获，这会触发生产规则。
- **worktree 里跑 `gradlew test`**：若 worktree 是 `fresh`（ 从 `origin/main` 分出 ），没有 `webview/node_modules`，`buildWebview` 会因 `tsc` 不在 PATH 而崩；可加 `-x buildWebview` 规避纯逻辑测试场景（ 记忆参考 `gradle-test-worktree-buildwebview-trap` ）。
- **纯逻辑 vs Platform 耦合**：纯函数走单测；Platform 耦合（ JCEF / ProcessBuilder ）难纯单测时，用**源码字符串检查**兜底（ 范式见 `ClaudeSDKBridgeRefactorTest` / `OpenCodeSDKBridgeTest`，`AGENTS.md` 第 6 节有述 ）。

CI 门：`.github/workflows/tests.yml` 的 `java-linux` job 用 JDK 21 跑 `./gradlew test`，并跳过 Windows+WSL 才能跑的 `NodeDetectorWslTest`（ 由 `java-wsl` job 单独在 Windows runner 上执行 ）。

### 5.2 Webview 单元测试（ Vitest ）

```bash
cd webview
npm test                # = vitest run && tsc -p tsconfig.test.json --noEmit
# 或
npm run test
# 只跑单个文件
npx vitest run src/path/to/foo.test.tsx
```

**配置**：`webview/vitest.config.ts`，`environment: 'happy-dom'`，`setupFiles: ['./vitest-setup.ts']`，`include: ['src/**/*.test.ts', 'src/**/*.test.tsx']`，`exclude: ['../.worktrees/**']`。

源码位置：`webview/src/**/*.test.{ts,tsx}`（ 约 133 个文件 ）。

**已知陷阱（ 必读 ）**：

- **必须在 `webview/` 目录里跑**。在仓库根直接 `npx vitest` 会因找不到 `vitest.config.ts` 而出假阴性。记忆参考 `webview-vitest-cwd-trap`。
- **测试前需要 `npm run prebuild`**：`src/version/version.ts` 是 `extract-version.mjs` 从 `build.gradle` 生成的，且被 gitignore，fresh checkout 时缺失；许多测试（ 如 `WelcomeScreen.tsx` ）import 它。CI 的 webview step 会先 `npm run prebuild` 再 `npm run test`，本地手动跑测试时也要先 `npm run prebuild`（ 或 `node scripts/extract-version.mjs` ）。
- **RTK proxy**：若用内部 rtk 工具跑，`rtk vitest` 会走代理；直接 `npx vitest` 亦可。记忆参考 `webview-vitest-cwd-trap`。
- **rAF mock 同步陷阱**：写测试时若 mock `requestAnimationFrame`，不要用同步回调（ 会把 `ref = rAF(cb)` 的赋值覆盖掉 cb 内的 `ref = null` ）；改异步（ `setTimeout + runAllTimers` ）。记忆参考 `webview-vitest-raf-mock-trap`。

CI 门：`.github/workflows/tests.yml` 的 `webview` job。

### 5.3 ai-bridge 测试（ node:test ）

```bash
# 从仓库根执行！不要 cd 进 ai-bridge/（ 会破坏 api-config.test.js 的路径解析 ）
node --test "ai-bridge/**/*.test.js"                                        # 只跑 .js（ 与 CI glob 一致 ）
node --test "ai-bridge/**/*.test.js" "ai-bridge/**/*.test.mjs"              # 本地权威门：.js + .mjs 全跑
node --test ai-bridge/utils/model-utils.test.mjs                            # 单个文件
```

源码位置：`ai-bridge/**/*.test.js`（ 约 24 个 ）+ `ai-bridge/**/*.test.mjs`（ 约 14 个 ）。

**已知陷阱（ 必读 ）**：

- **必须从仓库根跑**。`ai-bridge/config/api-config.test.js` 用 `path.resolve('.') + 'ai-bridge/...'` 解析模块，`cd ai-bridge && node --test` 会让路径倍增、全部失败。记忆参考 `ai-bridge-test-cwd-from-root`（ 注意：这与 webview 的陷阱正好相反 ）。
- **CI glob 只含 `.test.js`，不含 `.test.mjs`**。`tests.yml` 第 33 行：`node --test "ai-bridge/**/*.test.js"`。历史上 `.test.mjs` 有基线失败，后已修复（ 记忆 `test-mjs-preexisting-failures` ），但 CI glob 仍未纳入 `.mjs`；本地权威门是 `.js + .mjs` 全绿（ 记忆与 `docs/designs/2026-06-18-enum-refactor-design.md` 第 263 行提及「176 `.test.js` + 50 `.test.mjs`」基线 ）。
- **Node 版本**：CI 固定 Node 22.12.0。Node 22 的 `--test` 原生支持 glob（ `"ai-bridge/**/*.test.js"` ）；低版本 Node 的 glob 行为不同，建议统一用 22.x。

### 5.4 E2E（ Playwright ）

```bash
cd webview
npm run test:e2e       # = playwright test
```

配置：`webview/package.json` `test:e2e`，依赖 `@playwright/test ^1.60.0`。仓库目前 E2E 资产极少（ `comprehensive-optimization-directions.md` 第 2.1 节统计为 1 个 ），**待确认**是否已接入 CI（ `tests.yml` / `build.yml` 均未显式 step ）。

### 5.5 测试速查表

| 套件 | 命令 | 在哪跑 | CI job |
| --- | --- | --- | --- |
| Java | `./gradlew test` | 仓库根 | `java-linux` / `java-wsl` |
| Webview | `cd webview && npm test` | `webview/` 目录 | `webview` |
| ai-bridge（ CI 等价 ） | `node --test "ai-bridge/**/*.test.js"` | 仓库根 | `ai-bridge` |
| ai-bridge（ 本地权威 ） | `node --test "ai-bridge/**/*.test.js" "ai-bridge/**/*.test.mjs"` | 仓库根 | 无（ 本地补 `.mjs` ） |
| E2E | `cd webview && npm run test:e2e` | `webview/` 目录 | **待确认** |

---

## 6. Plugin Verifier（ 二进制兼容性 ）

### 6.1 配置

`build.gradle` 已配置 `pluginVerifier()` 与 `pluginVerification { ... }`：

- **failureLevel**：`COMPATIBILITY_PROBLEMS`、`INTERNAL_API_USAGES`（ 对齐 JetBrains Marketplace 拒绝标准 ）
- **externalPrefixes**：`['com.jetbrains.python']`（ PythonCore 钉在 243.x 编译期，verifier 在 262.x EAP 上找不到该包，故标记为外部 ）
- **目标 IDE**：`ide('IU', '262.6228.19')`（ IDEA Ultimate 2026.2 EAP，曾暴露 `CefResourceHandler` / `PluginManagerCore` 问题 ）

### 6.2 运行

```bash
./gradlew verifyPlugin
```

> 任务名在 IntelliJ Platform Gradle Plugin 2.x 中为 `verifyPlugin`（ `build.gradle` 第 121 行注释「used by `verifyPlugin` task」）。**待确认**：2.10.5 是否同时暴露 `verifyPluginArtifact` 等别名；优先用 `verifyPlugin`。

何时跑：

- 改动 JCEF / Platform 内部 API 后；
- 提升 `untilBuild` 前；
- 新增对 optional plugin（ PythonCore / Java ）的引用后；
- 发版前（ 对应 `comprehensive-optimization-directions.md` 第 5 节 A5 建议 ）。

---

## 7. Provider 六路径验收

> 对应 `AGENTS.md` 总则六与 `comprehensive-optimization-directions.md` 第 11.1 节。插件支持 3 provider × 2 mode = **6 条调用路径**：

| Provider | SDK daemon | CLI subprocess |
| --- | --- | --- |
| Claude | 必测 | 必测 |
| Codex | 必测 | 必测 |
| OpenCode | 必测 | 必测 |

### 7.1 横切检查矩阵

改任何一个 provider 的任何一项处理时，**必须**对照另两个 provider 的同项实现逐格确认（ 见 `AGENTS.md` 第 6 节对照表与合规检查清单 ）：

| 横切项 | 关注点 |
| --- | --- |
| env 注入 | `CliEnvironmentBuilder.applyExtraEnv` 对称 |
| stdin 写入 + 关闭 | 防子进程阻塞读 |
| stdout / stderr drain | 防管道满阻塞 |
| interrupt / abort | **确定性取消**（ `sendAbort` / `triggerAbort` ），非仅杀本地进程 |
| cwd null → home 回退 | 三 provider CLI 均需 |
| sessionId null 防御 | 跨 provider 切换时清空（ 记忆 `cross-provider-session-id-pollution-claude-resume-crash` ） |
| baseUrl 为空 → 默认 URL | |
| provider 归一化（ 前端 ） | `normalizeProvider` 三 provider 分支齐全 |
| 调用模式快照语义 | runtime signature 驱动 |
| `frontend_ready` 状态回灌 | `handleFrontendReady` 统一入口 |

### 7.2 落地建议

- **纯函数**走单元测试；
- **Platform 耦合**（ 无法纯单测 ）用源码字符串检查兜底，范式见 `ClaudeSDKBridgeRefactorTest` / `OpenCodeSDKBridgeTest`；
- **例外**：daemon 生命周期（ Claude 长连接 + 自动重启 vs OpenCode 惰性按需 + 60s 冷却 ）属架构本质差异，**不要求**镜像 `restartAttempts` 计数器，但两者**都必须**有「防无限重试」的等价保护。

### 7.3 六路径本地验收（ 手动 checklist ）

每次改动 provider 能力后，至少在 `runIde` 里手工走一遍：

1. **SDK 模式**：切到目标 provider，发起一轮对话，确认流式渲染、token usage、工具权限弹窗、取消（ interrupt ）正常。
2. **CLI 模式**：同 provider 切到 CLI runtime，重跑上述流程。
3. **跨 provider 切换**：Claude → Codex → OpenCode → Claude 循环一次，确认 sessionId 不污染、权限模式不串。
4. **历史恢复**：三 provider 各打开一个旧会话，确认 scroll anchoring 与流式回显。

> 更完整的六路径契约测试是 `comprehensive-optimization-directions.md` 第 5 节 F1 / 第 6 节 T1 的后续工作，不在 D1 范围。

---

## 8. 分支、PR、版本与发布

### 8.1 分支策略

依据 `CONTRIBUTING.md` 第「Branch Merge Guidelines」节：

- **`main`**：主分支（ 发版分支 ）。
- **`develop`**：开发分支，**PR 应先合并到 `develop`**（ `CONTRIBUTING.md` 原文：任何 PR 合并需要先往 develop 上进行合并 ）。
- **`feature/vX.Y.Z`**：功能分支，按小版本迭代。当前仓库活跃分支为 `feature/v0.4.8`（ 见 git status ）。
- PR AI 审查报告中的中风险 / 高风险问题，必须在 PR 内修完才能合并（ `CONTRIBUTING.md` 原文 ）。

> **待确认**：实际仓库的 `develop` 分支活跃度。从 git log 与 git status 看，近期提交集中在 `feature/v0.4.8`，`develop` 的使用频度需以仓库实际分支状态为准；若 `develop` 已不活跃，以 `CONTRIBUTING.md` 文字与维护者共识为准。

### 8.2 Commit 规范

严格遵循 `AGENTS.md` 第 8 节（ Conventional Commits ）：

- **全英文**，subject 小写起首、祈使句、无句号、≤ 72 字符；
- type：`feat` / `fix` / `refactor` / `docs` / `test` / `style` / `build` / `chore` / `i18n`；
- scope：小写连字符（ `webview` / `ai-bridge` / `session` / `model-registry` / `protocol` / `handler` / `bridge` / `runtime` / `provider` / `cli-session` 等 ）；
- **按变更性质分批提交**，禁止功能 / 修复 / 重构 / 格式化混提；
- feat 与 refactor 边界：用户 / 前端可感知 → `feat`；纯内部、行为不变 → `refactor`。

提交前自检：`AGENTS.md` 第 7 节「合规检查清单总表」23 条。

### 8.3 版本号

| 来源 | 值 |
| --- | --- |
| `build.gradle` `version` | `0.4.8-Alpha2`（ 当前分支 ） |
| `CHANGELOG.md` 最新条目 | `2026年7月9日（v0.4.7）` |
| 历史 git log | `chore: bump version to 0.4.7-Alpha3` |

**两种约定并存**（ 均来自仓库自身文件，请按维护者实际执行为准 ）：

- `CONTRIBUTING.md`「Version Number Specification」：开发版本用 `vX.Y.Z-beta1`、`-beta2` … 直至 `vX.Y.Z`。
- `build.gradle` / git log 实际使用：`X.Y.Z-AlphaN`（ 如 `0.4.7-Alpha3`、`0.4.8-Alpha2` ）。

发版前升级版本号：编辑 `build.gradle` 的 `version = '...'`，并新增 `CHANGELOG.md` 顶部条目（ `patchPluginXml.changeNotes` 会从 `CHANGELOG.md` 读首节生成插件市场展示文本 ）。

### 8.4 构建 / 打包

```bash
./gradlew clean buildPlugin
# 产物：build/distributions/<pluginName>-<version>.zip
```

`buildPlugin` 已 `dependsOn packageAiBridge, checkstyleMain`，会把 `ai-bridge.zip` + `ai-bridge.hash` 注入到发行 zip 内、剔除 `searchableOptions.jar`（ 详见 `build.gradle` `buildPlugin { doLast { ... } }` ）。

CI 门：`.github/workflows/build.yml` 的 `build` job 在 ubuntu-latest 上跑 `./gradlew buildPlugin`，并把 `build/distributions/*.zip` 作为 artifact 上传。

### 8.5 发布流程

GitHub 侧（ 已自动化，见 `.github/workflows/build.yml` `release` job ）：

1. 打 tag（ 如 `git tag v0.4.7 && git push origin v0.4.7` ）；
2. `build.yml` 的 `build` job 在 tag push 时触发，产出发行 zip；
3. `release` job（ `if: startsWith(github.ref, 'refs/tags/')` ）用 `softprops/action-gh-release@v3` 自动创建 GitHub Release，附件为发行 zip，`generate_release_notes: true`。

JetBrains Marketplace 侧：

- 插件主页：`https://plugins.jetbrains.com/plugin/29342-cc-gui-claude-or-codex-`（ `README.md` 链接 ）。
- **待确认**：Marketplace 上传步骤是否完全手动。仓库内无 Marketplace 上传的自动化 workflow（ `build.yml` 只发 GitHub Release ），推测为维护者手动从 GitHub Release 下载 zip 后上传到 Marketplace；若需确认，请向维护者核实。

### 8.6 安全审计节奏

`README.md` 第 17 行原文：每个小版本发布前会做一次 `/security-review` 审计；每 10 个小版本做一次完整的 `claude-code-security` 审计。

---

## 9. 关键代码参考

索引引自 `comprehensive-optimization-directions.md` 第 12 节「关键代码参考」：

| 领域 | 路径 |
| --- | --- |
| typed action dispatcher | `src/main/java/com/github/claudecodegui/handler/core/FrontendActionDispatcher.java` |
| typed action contract | `src/main/java/com/github/claudecodegui/handler/core/FrontendActionHandler.java` |
| history registry | `src/main/java/com/github/claudecodegui/handler/history/HistoryProviderRegistry.java` |
| history adapter | `src/main/java/com/github/claudecodegui/handler/history/HistoryProviderAdapter.java` |
| settings persistence | `src/main/java/com/github/claudecodegui/settings/CodemossSettingsService.java` |
| Node service caller | `src/main/java/com/github/claudecodegui/handler/NodeJsServiceCaller.java` |
| optional plugin config | `src/main/resources/META-INF/plugin.xml` |
| Gradle Webview build | `build.gradle` |
| Webview bootstrap | `src/main/java/com/github/claudecodegui/ui/WebviewInitializer.java` |
| Mermaid import | `webview/src/components/MarkdownBlock.tsx` |
| synchronous bridge board | `webview/src/bridge/store.ts` |
| protocol generated output | `webview/src/generated/protocol.ts` |

D1 补充参考：

| 领域 | 路径 |
| --- | --- |
| 协议 SSOT 生成器 | `webview/scripts/generate-protocol-types.mjs` |
| 前端测试配置 | `webview/vitest.config.ts` / `webview/vitest-setup.ts` |
| 版本提取（ prebuild ） | `webview/scripts/extract-version.mjs` |
| 构建脚本 | `build.gradle` |
| CI（ 三套测试 + 打包 + 发布 ） | `.github/workflows/tests.yml` / `.github/workflows/build.yml` |
| 协作规范 | `CONTRIBUTING.md` |
| 架构准则 | `AGENTS.md` |
| 本地配置模板 | `local.properties.example` |

---

## 10. 常见坑位速查

| 症状 | 根因 | 解法 |
| --- | --- | --- |
| 新增 `@Test` 方法不执行（ 假绿 ） | `instrumentTestCode` 产 stale 字节码 | 保持 `instrumentTestCode.enabled=false`（ 已在 `build.gradle` 根治 ） |
| `gradlew test` 在 fresh worktree 崩 | `tsc` 不在 PATH，`buildWebview` 失败 | 加 `-x buildWebview`（ 仅纯逻辑测试场景 ） |
| 根目录 `npx vitest` 全挂 | 找不到 `vitest.config.ts` | 必须在 `webview/` 目录跑 |
| Webview 测试 import `version.ts` 失败 | `version.ts` 是 prebuild 生成、被 gitignore | 测试前先 `npm run prebuild` |
| `cd ai-bridge && node --test` 全挂 | `api-config.test.js` 依赖仓库根 cwd | 必须从仓库根跑（ 与 webview 相反 ） |
| `protocol.ts` 没更新 | 忘了重跑 `buildWebview` | 改完 Java 枚举后，`runIde` / `buildWebview` / `buildPlugin` 任一都会自动重生成 |
| `runIde` 找不到 JCEF | JBR 版本过旧（ `JCefAppConfig.isRemoteEnabled()` 缺失 ） | 升级 Boot JBR 到 b1373+（ 见 v0.4.6 CHANGELOG ） |
| 手改 `protocol.ts` 后被覆盖 | 该文件自动生成 | 改 Java 枚举源，不要改生成产物 |

---

## 11. 后续工作（ 不在 D1 范围 ）

D1 仅补开发文档。下列相关项见 `comprehensive-optimization-directions.md`：

- **D2 pre-commit hooks**：staged lint / format、pre-push 完整测试。
- **D3 本地开发脚本**：Gradle 聚合 task、PowerShell / shell wrapper、根目录 package scripts。
- **T1 测试覆盖率**：JaCoCo / Vitest coverage / c8 接入与 baseline 防倒退。
- **A8 前端协议第二真相源收敛**：`webview/src/bridge/events/index.ts` 改为引用 `DOWNSTREAM.*` + codegen 漂移检测。
- **B1 buildWebview inputs/outputs**：为 `buildWebview` 声明输入输出以支持 up-to-date 与 build cache。
