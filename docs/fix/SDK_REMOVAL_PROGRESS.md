# SDK 代码移除 — 完成报告

## 总览

本插件原支持多个 AI provider（Claude / Codex / OpenCode，及后续接入的 Grok / Kimi / Pi），每个核心 provider 有两种调用模式：**SDK daemon**（长期驻留的 Node 桥进程）和 **CLI 子进程**。本次工作移除了全部 SDK daemon 模式代码及其用户可见的「调用模式（InvocationMode）」选择器，仅保留 CLI 模式，且不影响 CLI 功能。

**两条移除主线**：
1. **SDK Bridge 类**（`ClaudeSDKBridge` / `CodexSDKBridge` / `OpenCodeSDKBridge` / `BaseSDKBridge`）—— 承担 Node.js 基础设施 + daemon 通信双重职责。将职责（1）提取为独立 `NodeService`，职责（2）中各辅助功能（历史/MCP/Rewind/Git Commit）独立为不依赖 Bridge 的服务，最终安全删除全部 Bridge 类。
2. **Daemon 运行时层 + InvocationMode** —— `SdkSessionRuntime`（3 个）及 `DaemonCoordinator` / `DaemonRequestExecutor` / `DaemonBridge` / `DaemonConstants` 全删；前端 SDK/CLI 调用模式选择器及其 RPC 链全删。`RuntimeType` 枚举收敛为单一值 `CLI`。

**编译验证**：`./gradlew compileJava` / `compileTestJava` 均通过；webview `tsc --noEmit` 通过。

---

## 删除清单（工作区，feature/remove-sdk-mode 分支）

### 源文件（34 个）

**SDK Bridge 核心（11）** — 文档 Phase 1–5 迁移后删除
- `provider/common/BaseSDKBridge.java`、`ProjectBridgeRegistry.java`、`SharedBridgeReferenceCounter.java`、`DaemonBridge.java`、`DaemonConstants.java`
- `provider/claude/ClaudeSDKBridge.java`、`ClaudeProcessInvoker.java`、`ClaudeStreamAdapter.java`、`ClaudeRequestParamsBuilder.java`、`ClaudeQueryExecutor.java`、`ClaudeLogSanitizer.java`
- `provider/codex/CodexSDKBridge.java`、`provider/opencode/OpenCodeSDKBridge.java`

**Daemon 运行时层（10）**
- 3 个 runtime：`session/runtime/ClaudeSdkSessionRuntime.java`、`CodexSdkSessionRuntime.java`、`OpenCodeSdkSessionRuntime.java`
- 5 个协调器/执行器：`provider/{claude,codex,opencode}/*DaemonCoordinator.java`、`provider/claude/ClaudeDaemonRequestExecutor.java`、`provider/codex/CodexDaemonRequestExecutor.java`

**InvocationMode RPC（3）** — 用户可见调用模式选择器的后端入口
- `handler/settings/GetInvocationModeActionHandler.java`、`GetSessionInvocationModeActionHandler.java`、`SetInvocationModeActionHandler.java`

**RuntimePolicy RPC（5）** — 运行时策略配置面板的后端入口（数据层保留，见 Phase 7）
- `handler/RuntimePolicyHandler.java`、`handler/settings/{Get,Set,Reset,Get...Schema}RuntimePolicyActionHandler.java`

**CLI 路径配置 RPC（3）** — 手动输入死闭环清理（详见独立记忆）
- `handler/settings/GetClaudeCliPathActionHandler.java`、`SetClaudeCliPathActionHandler.java`、`SetCliPathActionHandler.java`

**SDK 消息归一化器（2）** — CLI-only 后不可达
- `session/normalize/ClaudeSdkMessageNormalizer.java`、`CodexSdkMessageNormalizer.java`

### 测试文件（14 个）
- Bridge 相关：`ClaudeQueryExecutorProcessLifecycleTest`、`ClaudeRequestParamsBuilderTest`、`ClaudeSDKBridgeRefactorTest`、`CodexSDKBridgeEnvTest`、`CodexSDKBridgeHistoryTest`、`CodexSdkMcpGatewaySymmetryTest`、`SharedBridgeReferenceCounterTest`、`OpenCodeSDKBridgeTest`、`OpenCodeSdkMcpGatewaySymmetryTest`、`DaemonBridgeTest`
- Daemon 相关：`OpenCodeDaemonCoordinatorTest`
- 契约：`session/runtime/ProviderSixPathContractTest`（删除后 CLI 不降级保证见下文）
- CliPath：`GetClaudeCliPathActionHandlerTest`、`SetClaudeCliPathActionHandlerTest`

---

## 各阶段实现细节

### Phase 1: 提取 Node.js 基础设施为独立服务

`BaseSDKBridge` 内含 `NodeDetector` / `ProcessManager` / `EnvironmentConfigurator` / `BridgeDirectoryResolver`，被多个 Handler 经 `context.getClaudeSDKBridge()` 取用。这些与 SDK 通信无关，提取为单例 `bridge/NodeService.java`，6 个 Handler 切换到 `getNodeService()`。

**新增**：`bridge/NodeService.java`　**修改**：`HandlerContext.java` 等 7 个文件。

### Phase 2: 迁移历史读取功能

历史读取（`getSessionMessages` / `getSessionList` / `archiveSession` / `loadHistoryPage`）独立于 daemon，脱离 Bridge 继承体系：
- `ClaudeHistoryService`（走 `channel-manager.js claude getSession`）
- `CodexHistoryService`（本地文件系统 `~/.codex/sessions/`，不依赖 Node）
- `OpenCodeHistoryService`（提取 OpenCode 桥内联逻辑）

**新增**：3 个 HistoryService　**修改**：各 ProviderAdapter、`LoadCodexHistoryPageActionHandler`、`OpenCodeHistoryProviderAdapter`、`HistoryProviderRegistry`。

### Phase 3: 处理 MCP/Rewind 查询功能

MCP 状态查询与 Rewind 回滚走 per-process 子进程，不依赖 daemon 长连接：
- `ClaudeMcpService`、`CodexMcpService`（含 `PROTECTED_ENV_KEYS` / `injectCustomEnvVars`）
- `ClaudeRewindService`（门面）+ `ClaudeRewindQueryService`（原包私有类重命名）

**新增**：3 个 Service　**修改**：`McpServerActionHandlers`、`CodexMcpServerActionHandlers`、`RewindFilesActionHandler`。

### Phase 4: 迁移 Git Commit 生成

`GitCommitMessageService` 曾 `new ClaudeSDKBridge(null)` / `new CodexSDKBridge(null)` 生成 commit message。新建 `CommitMessageAiService` 直接 spawn `node channel-manager.js <provider> send`，最小 stdin JSON + stdout 行协议解析。

**新增**：`CommitMessageAiService.java`　**修改**：`GitCommitMessageService.java`。

### Phase 5: 删除 SDK Bridge 类

Phase 1–4 迁移完毕后，删除上列 11 个 Bridge 核心类，并清理 12+ 个引用文件（`HandlerContext` 简化为 3 参构造、`ChatWindowDelegate` / `SessionLifecycleManager` / `WebviewInitializer` 各 Host 接口移除 bridge getter、`NodeProcessRegistry` 移除 `safeClaudeBridge`/`safeCodexBridge` 等）。

**保留**（被 CLI 路径或独立服务使用）：`SDKResult`、`MessageCallback`、`ClaudeBridgeUtils`、`ClaudeJsonOutputExtractor`、`ClaudeSessionQueryService`、`ClaudeMcpQueryService`、`ClaudeRewindQueryService`。

### Phase 6: 删除 Daemon 运行时层

Bridge 删除后，daemon 运行时层失去宿主：
- 3 个 `SdkSessionRuntime`（Claude/Codex/OpenCode 的 daemon 会话生命周期实现）删除；CLI 侧 `CliSessionRuntime` 保留并由 `SessionRuntimeRouter`/`CliSessionManager` 路由。
- `DaemonCoordinator` / `DaemonRequestExecutor`（各 provider 的 daemon 启停与请求派发）删除。
- `DaemonBridge` / `DaemonConstants`（daemon 协议底座）删除。

清理后，运行时路由仅剩 CLI 路径；`SessionSendService` 中原 SDK 分支不可达，已简化。

### Phase 7: 移除 InvocationMode（用户调用模式选择器）

SDK 移除后，用户可见的「SDK / CLI 调用模式」选择器失去意义，整条链路移除：

**删除（前端 + RPC）**：
- 后端 3 个 RPC handler（`Get/Set/GetSession...InvocationModeActionHandler`）
- 前端 `window.updateInvocationMode` 声明（`global.d.ts`）、store mock、`runtimePolicy` 设置面板的 i18n 文案（en/zh）与 CSS（`runtimePolicy*` / `advancedToggle` / `statusBadge` 等孤儿类）

**保留（数据层 + 适配层，向后兼容）**：
- `RuntimePolicyConfig` / `EffectiveRuntimeResolver` / `CodemossSettingsService.getRuntimePolicy` / `parseRuntimePolicy` —— 仍是运行时策略的数据源（`EffectiveRuntimeResolver.resolve` 决定 provider 是否启用、默认 runtime）。
- `RuntimeType.fromInvocationMode()` / `SessionSendService.toInvocationMode()` —— 恒返回 `CLI`，保留入参仅为兼容 `MessageNormalizers.forRuntime` 等既有签名（最小变更，避免波及归一化层接口）。
- `CLAUDE_INVOCATION_MODE_KEY` —— 读取旧 `config.json` 中 `claudeInvocationMode` 字段的兼容点，判定 CLI。

> **注意**：仅删除了 **RPC 层**（前端可操作的 `get/set/reset/schema` action 及其 handler）。**数据层保留**，因 `EffectiveRuntimeResolver` 链路仍依赖 `RuntimePolicyConfig` 判定 provider 启用状态。这是「用户操作入口移除」而非「数据结构移除」。

### Phase 8: CLI 环境检查新功能

新增 `handler/cli/CheckCliEnvironmentActionHandler` —— CLI-only 后，会话启动前主动检查 CLI 环境（可执行文件存在性、版本等），通过 `escapeJs(gson.toJson(...))` 下行结果卡片（遵循下行 payload 须 escapeJs 的约定）。

### Phase 9: 本次清理批次（Task #1–#7）

对主线移除后的残留做收尾清理：

| # | 内容 | 关键文件 |
|---|------|---------|
| 1 | adapter 构造 NPE：`HandlerContext.getNodeService()` 改 `volatile`+DCL 懒加载；`*ProviderAdapter.historyService()` 懒加载 | `HandlerContext.java`、3 个 ProviderAdapter |
| 2 | 删 2 个 SDK Normalizer（CLI-only 后不可达） | `Claude/CodexSdkMessageNormalizer.java` |
| 4 | 删 RuntimePolicy RPC 死链（5 handler + `UpstreamAction`/`DownstreamEvent` 各 4 枚举） | `RuntimePolicyHandler.java` 等 5 |
| 5 | 删 `ProviderCapability.SDK_SESSION` + `launchChannel`/`interruptChannel` 接口死方法 + Router 暴露 + `ClaudeSession.dispose()`/`interrupt()` 不可达 SDK 分支 + `isCliRuntime()` 死方法 | `ProviderAdapter.java`、`SessionProviderRouter.java`、`ClaudeSession.java` |
| 6 | 清理 javadoc 对已删 SDK 桥类的悬空引用（`ClaudeSDKBridge` 等 4 类在 `src/main/java` 归零）+ `broadcastInvocationMode` 悬空引用 | 19 个文件 |
| 7 | 清理前端孤儿：`updateInvocationMode` 声明、`setInvocationMode` mock、`runtimePolicy` i18n/CSS | `global.d.ts`、`*.test.ts`、`zh/en.json`、`style.module.less` |

---

## CLI 不降级保证（`ProviderSixPathContractTest` 删除后）

`ProviderSixPathContractTest` 曾验证「provider 配置 → 运行时解析」六条路径不会意外降级。该测试删除后，由以下现存机制保证 CLI 不降级：

1. **`RuntimeType` 单值枚举**：仅 `CLI`，编译期即不可能产生非 CLI 的 `RuntimeType`。
2. **`EffectiveRuntimeResolver.resolve()`**：从 `RuntimePolicyConfig` 解析，`defaultRuntime()` 必为 `CLI`（因枚举无其它值）；provider 禁用/未知时 fail-fast 抛 `IllegalStateException` 而非静默降级。
3. **`ProviderDescriptor.builtins()`**：6 个内置 provider 均声明 `supports(RuntimeType.CLI)`；`ProviderDescriptorContractTest` / `ProviderDescriptorContractTest` 锁定该契约。
4. **`SessionProviderRouter`**：经 `ProviderRegistry.require` Map 查表，未知 provider fail-fast，取代原先「静默 fallback 到 CLAUDE」的偏离行为。

---

## 删除的 SDK Bridge 依赖关系图

```
删除前                                  删除后
┌──────────────────────────┐           ┌──────────────────────┐
│   BaseSDKBridge          │           │   NodeService         │
│   ├── NodeDetector       │  ───────► │   ├── NodeDetector    │
│   ├── ProcessManager     │           │   ├── ProcessManager  │
│   ├── EnvironmentConfig  │           │   ├── EnvironmentCfg  │
│   └── BridgeDirResolver  │           │   └── BridgeDirResolver│
├──────────────────────────┤           ├──────────────────────┤
│   ClaudeSDKBridge        │           │   ClaudeHistoryService│
│   ├── ClaudeProcessInvoker│          │   ClaudeMcpService    │
│   ├── ClaudeStreamAdapter│           │   ClaudeRewindService │
│   ├── ClaudeRequestParams│           │   CodexHistoryService │
│   ├── ClaudeQueryExecutor│           │   CodexMcpService     │
│   ├── ClaudeSessionQuery │           │   OpenCodeHistorySvc  │
│   ├── ClaudeMcpQuery     │           │   CommitMessageAiSvc  │
│   ├── ClaudeRewindQuery  │           └──────────────────────┘
│   └── ClaudeLogSanitizer │
├──────────────────────────┤           ┌──────────────────────┐
│   CodexSDKBridge         │           │  RuntimeType = CLI    │
│   OpenCodeSDKBridge      │           │  EffectiveRuntime     │
├──────────────────────────┤           │   Resolver (CLI-only) │
│   Daemon 层(已删):        │           │  CliSessionRuntime ×3 │
│   ├── SdkSessionRuntime×3│           │   (经 Router 路由)     │
│   ├── DaemonCoordinator  │           └──────────────────────┘
│   ├── DaemonRequestExec  │
│   ├── DaemonBridge       │
│   └── DaemonConstants    │
├──────────────────────────┤
│   ProjectBridgeRegistry  │
│   SharedBridgeRefCounter │
└──────────────────────────┘
```

## 响应式修复（非 SDK 相关）

SDK 移除过程中发现设置页左侧菜单的响应式问题，已同期修复：移除 `motion/react` 动画、恢复 `display:none` 隐藏方式、移除响应式 `@media` 中的 `flex-direction: column`。文件：`webview/src/components/settings/SettingsSidebar/`。

---

## 补遗：核查发现的 SDK 残留死代码清理（2026-08-11）

初版完成报告未覆盖以下 SDK 残留死代码，经逐项核查后于本次清理。这些代码在 SDK 模式移除后已无任何活跃调用方，但未随主体删除。

### McpGateway SDK 路径死代码

`McpGatewayService` 的两个 SDK 入口 `buildSdkMcpServers` / `buildSdkServeConfig` 在主代码零调用方（SDK 移除后所有调用方转 `buildCliConfig`），仅被守护测试 `SdkMcpGatewaySymmetryTest`（源码字符串匹配）引用。配套删除：

- `McpGatewayService.buildSdkMcpServers` / `buildSdkServeConfig`（两方法）
- `McpGatewaySdkBinding` record（整个文件）+ `McpGatewaySdkBindingTest`
- `McpGatewayFeatureFlags.isSdkEnabled()` + `McpGatewayConstants.FEATURE_SDK_ENABLED`
- `isGatewayActive()` 由 `isCliEnabled() || isSdkEnabled()` 收敛为 `isCliEnabled()`（SDK 移除后仅 CLI 路径）
- `SdkMcpGatewaySymmetryTest` 删除；`McpGatewayFeatureFlagsTest` 重写（去掉所有 SDK 三态组合断言）

### ai-bridge mcp-gateway-binding 死链

Java 端 `McpGatewaySdkBinding` 序列化结果本应经 stdin 注入 Node 端 `mcp-gateway-binding.js`，但全代码库 grep `mcpGatewayBinding` 在 Java 侧**零写入**——该字段从未被放入任何 stdin JSON。Node 端 `isUsable(undefined/null)` 恒 false，每次提前返回 null/no-op。删除 4 个文件并清理调用点：

- `ai-bridge/services/{claude,codex}/mcp-gateway-binding.js`
- `ai-bridge/test/services/{claude,codex}/mcp-gateway-binding.test.mjs`
- `persistent-query-service.js`：删 import，`buildGatewayMcpServers` 调用退化为直接加载真实 MCP，`gatewaySignatureMaterial(...)` → `null`
- `codex/message-service.js`：删 import + `sendMessage` 的 `mcpGatewayBinding` 参数 + `applyCodexGateway`/`codexGatewayRevision` 注入段（保留 `const codexRevision = null` 占位，供 `buildCodexThreadCacheSignature` 三处调用）
- `codex-channel.js`：删 `mcpGatewayBinding` 解构与传参
- `runtime-signature-gateway.test.mjs` / `codex-utils.test.mjs` 保留（测 `buildRuntimeSignature` / `buildCodexThreadCacheSignature` 函数契约，不依赖已删 binding 模块）

### 其他零调用死代码

- `ClaudeBridgeUtils.buildDaemonEnv`（零调用，javadoc 引用已删的 `ClaudeDaemonCoordinator`/`ClaudeDaemonRequestExecutor`）
- `CliConstants.OPENCODE_ARG_SERVE` / `OPENCODE_ARG_PORT` / `OPENCODE_ARG_HOSTNAME`（opencode serve 守护进程专用常量，守护进程层全删后零引用）

### 核查中顺带修复的既存 bug（非 SDK 残留）

- **CodexMcpService 保护集退化**：SDK 移除时 `CodexSDKBridge.PROTECTED_ENV_KEYS` 的 32 key 保护集迁移到 `CodexMcpService` 时漏接 `CodexProtectedEnvKey` 枚举的 18 个基础 key（仅保留 14 个 code-injection key）。已补回枚举遍历，恢复 32 key 对齐。
- **CliEnvironmentChecker**：`getVersion` / `getLatestVersionFromNpm` 的 `readLine()` 置于 `waitFor(10s)` 之前，stalled 流上 `readLine` 永久阻塞 → 10s 超时形同虚设；Windows 下 `npm` 未用 `npm.cmd`（ProcessBuilder 不按 PATHEXT 解析）→ `hasUpdate` 恒 false。已改为 waitFor-先于-readLine + try-with-resources + finally destroyForcibly + `PlatformUtils.isWindows() ? "npm.cmd" : "npm"`。
- **CommitMessageAiService**：同类 readLine 阻塞反模式（同步 `BufferedReader.readLine` 循环使超时不可强制）+ `actualModel` 未透传给子进程 + exit code / 致命错误行（`[SEND_ERROR]`/`[UNCAUGHT_ERROR]` 等）未识别。已改委托 `PromptEnhancerProcessRunner`（异步 stdout drain + 主线程 waitFor + exit code + 致命错误行优先），`GitCommitMessageService` 透传 `actualModel`。

### javadoc / 注释悬空引用清理

移除对已删类的文字引用（非 `{@link}` 编译级依赖，不影响 compileJava）：`BridgePreloader`、`CliConstants`、`UserPathResolver`、`OpenCodeHistorySanitizerTest`、`ProtocolEnumCoverageTest`、`ai-bridge opencode/message-service.js`、`webview provider.ts`、`CodexMcpService` 注释。

---

## 补遗二：第二轮逐项核查与残留清理（2026-08-11）

继补遗一后，对 SDK 移除主线做第二轮核查，逐项验证并清理以下残留。验证：`./gradlew compileJava compileTestJava` 通过、webview `tsc --noEmit` 通过、相关单测通过。

### Grok / Kimi / Pi 三 provider 全功能补全

Grok / Kimi / Pi 此前仅作为 CLI-only provider 标记存在，但 `SessionSendService` 未将其路由到实际协议路径——用户选择后会话无法发送。补全：三 provider 经 `CliOnlyProviderAdapter` 复用 Codex CLI 协议路径，并注册到 `SessionProviderRouter`。

- 新增 `provider/CliOnlyProviderAdapter.java`（统一 CLI-only provider 适配）
- `session/SessionSendService.java`：三 provider 路由到 Codex 协议路径
- `session/SessionProviderRouter.java`：注册三 provider

### ai-bridge daemon/SDK 残留死代码裁剪

SDK daemon 模式移除后，ai-bridge 中仍有大量仅服务于 daemon 长连接的死代码，且 `@opencode-ai/sdk` 依赖未清理。逐文件核查调用链后删除：

- **整体死文件**：`daemon.js`、`services/claude/persistent-query-service.js`、`services/claude/runtime-lifecycle.js`（+ `.1m-toggle.child.mjs`）、`services/opencode/event-mapper.js`、`services/opencode/message-service.js`，及对应 8 个测试文件
- **channel 死分支裁剪**：`channels/{claude,codex,opencode}-channel.js` 移除 daemon 专用分支
- **channel-manager system 分支**：`channel-manager.js` 移除 `system` provider 分支（含 `checkCodexSdk` 等已无意义的系统命令）；删除仅测试该分支的 `test/channel-manager.protocol.test.mjs`
- **sdk-loader 收敛**：`utils/sdk-loader.js` 移除 opencode SDK 加载（`loadOpencodeSdk` / `isOpencodeSdkAvailable`）、`getSdkStatus` / `requireSdk` / `clearSdkCache`，仅保留 Claude/Codex SDK 的一次性子进程加载；删除 `test/utils/sdk-loader.test.js`
- **依赖清理**：`package.json` 移除 `@opencode-ai/sdk` 依赖与 `test:sdk-status` 脚本

> `services/codex/message-service.js` 经核查无导入断裂：`sendMessage`（供 `CommitMessageAiService` 调用）与 `getMcpServerTools` 仍活跃，线程缓存为 sendMessage 重试路径的内部支持，保留不动。

### restart_node_daemon 全链删除

CLI 模式无常驻 daemon，「重启 daemon」功能（等价于 kill 单进程）已无独立意义，整条链路删除：

- **Java**：`UpstreamAction.RESTART_NODE_DAEMON` 枚举项、`RestartNodeDaemonActionHandler.java`（包装类整删）、`NodeProcessActionHandlers.handleRestartDaemon` + `RESTART_REFRESH_DELAY_MS` 常量、`ChatWindowDelegate` import/注册/注释、`NodeProcessActionHandlerTest` 契约用例
- **前端**：`nodeProcessCapabilities.ts` 的 `restartNodeDaemon` 函数与 `NodeProcessKillResult.restart` 字段、`NodeProcessSelect.tsx` 的 restart 按钮/确认对话框/回调（7 处）、`global.d.ts` payload 注释、3 个相关测试的 mock 与用例
- **i18n**：10 个 locale 各删 `restart` / `restartConfirmTitle` / `restartConfirm` / `restartSuccess` / `restartHint` 5 个 key（共 50 key）；`restartHint` 为块末 key，删除时同步去掉 `killHint` 尾逗号以保持 JSON 合法
- **协议 SSOT 代码生成**：Java 枚举项删除后，`webview/scripts/generate-protocol-types.mjs` 重新生成 `protocol.ts`（215 upstream，原 216 - 1），`restart_node_daemon` wire 字面量同步消失

> `RESTART_SESSION`（会话重刷，不同功能）与 i18n `restartRequired`（IDEA 重启提示）均保留。
