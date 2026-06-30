# MCP Gateway CLI Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在保持 Claude / Codex / OpenCode CLI one-shot 子进程并行模型不变的前提下，引入项目级长驻 MCP Gateway，避免 CLI 启动阶段被真实 MCP 初始化/重试阻塞，同时保留“模型自动识别场景并无感调用 MCP 工具”的体验。

**Architecture:** 本版只改 CLI 模式；SDK 调用模式保持 provider-native，不纳入本计划实施范围。CLI 模式统一接入本地长驻 MCP Gateway：Gateway 后台维护真实 MCP 连接、工具目录缓存、健康检查、退避重试和故障隔离；每个 CLI 子进程只加载一个本地 Gateway MCP server，并通过启动时 pin 住的 revision 使用稳定工具快照。

**Tech Stack:** IntelliJ Plugin Java、Project-level service、Node.js ai-bridge、MCP JSON-RPC、stdio/HTTP MCP transport、Claude CLI、Codex CLI、OpenCode CLI、JUnit、Node test runner。

## Global Constraints

- 本版只在 CLI 模式启用 Gateway；SDK 调用模式保持现状，不新增 SDK Gateway binding、不新增 SDK feature flag。
- CLI 模式必须继续保持每 turn 独立子进程，不能退回单 daemon 串行。
- 模型必须仍能自动选择 MCP 工具，用户不需要手动指定 `idea_mcp`、`database`、`ops`。
- Gateway 必须暴露真实工具名、description、input schema，不能只暴露泛化 `call_tool` 作为主路径。
- 一个真实 MCP 故障不能阻塞 Gateway、CLI 首包、其他 MCP server 或普通对话。
- 设置页新增、删除、更新、启停 MCP 后必须热更新 Gateway；已启动 turn 使用稳定快照，新 turn 使用新 revision。
- 普通聊天启动不得写用户真实全局配置文件，例如 `~/.claude.json`、`~/.codex/config.toml`、`~/.config/opencode/opencode.json`。
- 前端只展示 Gateway/MCP 状态，不做 MCP 路由、能力判断、工具选择、默认值计算。
- 新协议事件必须使用 `DownstreamEvent`，provider 值必须使用 `ProviderType` / `CommonConstants` SSOT。
- 所有 provider 字面量、CLI 命令名、消息类型必须遵守项目 AGENTS.md 硬编码禁止规范。
- Gateway 必须是 Project-scoped Java service，并在 Project dispose 时停止 Node Gateway，不能做全局静态单例。
- CLI wiring 必须遵循现有链路 `SessionSendService -> SessionRuntimeRouter -> CliSessionManager -> CliSessionFactory -> CliSession`，不得绕开 `SessionRuntimeRegistry` / `CliSessionFactory` 注册表。
- 每个 CLI turn 必须 pin 住启动时的 Gateway catalog revision；`tools/list` / `tools/call` 不得偷偷切到最新 revision。
- Gateway control/runtime API 必须只监听 `127.0.0.1`，并使用随机 token 鉴权；端口、PID、token 只写插件私有临时目录。
- Codex/OpenCode CLI 注入必须确保“只加载 Gateway MCP”，不能在用户原有真实 MCP 之外再追加 Gateway。
- 修改 `DownstreamEvent` 后主路径是运行 `rtk npm --prefix webview run prebuild` 或 `rtk node webview/scripts/generate-protocol-types.mjs`；`gradle generateProtocol` 默认禁用，仅作可选交叉校验，需 `-PgenerateProtocol=true`。

---

## 0. Approval Verdict

### 0.1 Decision

**结论：有条件通过。** 方案方向可行，但原文档在接入点、快照 pinning、Codex/OpenCode 配置注入、Gateway 安全、进程生命周期、协议生成命令上不完整。本文已按当前仓库实际结构修订这些必改点，后续实现必须以本修订版为准。

### 0.2 Repository Facts Verified

- 当前已经存在 `3 provider x 2 runtime`：`Claude/Codex/OpenCode` × `SDK/CLI`，路由由 `SessionRuntimeRouter` + `SessionRuntimeRegistry` 管理。
- 本版只触碰 CLI runtime / CLI session 链路；SDK runtime 仅作为架构边界存在，不做 Gateway 接入。
- CLI 聚合入口是 `CliSessionManager`，通过 `CliSessionFactory` 注册表创建 `ClaudeCliSession` / `CodexCliSession` / `OpenCodeCliSession`。
- `SessionSendService` 持有 `Project`，但现有 `SessionRuntimeRouter` / `CliSessionManager` 未接收 `Project`，因此 Gateway service 必须沿构造链注入。
- Claude CLI 已有 `CliMcpConfig` + `--mcp-config` per-tab 文件，可替换为 Gateway config。
- Codex CLI 当前 `CodexCliSession` 没有真正使用 `CliMcpConfig`，而是继承用户 `CODEX_HOME` / `config.toml`；仅用 nested `-c mcp_servers.melon_gateway...` 可能追加而非替换真实 MCP，不满足“只加载 Gateway”。
- 本地 `codex-cli 0.142.3` 的 `codex exec --help` 支持 `-c/--config`、`--profile`、`--ignore-user-config`；实现必须先证明 `mcp_servers={...}` 能整体替换，否则使用临时 `CODEX_HOME`。
- 本地 `opencode 1.17.11` 的 `opencode run --help` 没有 per-run config path 参数；可行路径是为该进程设置临时 `HOME`/`USERPROFILE`/XDG 目录并写隔离的 `opencode.json`。
- `webview/scripts/generate-protocol-types.mjs` 直接解析 Java 枚举生成 `webview/src/generated/protocol.ts`；`gradle generateProtocol` 默认不会运行。
- `NodeProcessRegistry` 当前只识别 `daemon.js` / `channel-manager.js`，新增 `mcp-gateway-server.js` 后必须同步纳入可见性与清理策略。

### 0.3 Mandatory Corrections Applied To This Plan

1. **Project-scoped injection:** 增加 `McpGatewayService` 沿 `SessionSendService -> SessionRuntimeRouter -> CliSessionManager -> factories` 注入的任务。
2. **Revision pinning:** 每个 CLI turn 的 Gateway config 必须携带 `revision`，Gateway 保存 bounded immutable catalog revisions。
3. **Secure control channel:** Gateway 控制 API 使用 loopback + random token + state file，不允许裸 HTTP 端口。
4. **Collision-free tools:** Gateway tool name 默认使用 `mcp__<sourceProvider>__<serverId>__<toolName>`；如要提供 legacy alias，必须唯一且有冲突测试。
5. **Codex/OpenCode config isolation:** Codex 不能只追加 `mcp_servers.melon_gateway`；OpenCode 不能假设存在 config flag。
6. **Lifecycle visibility:** `NodeProcessRegistry`、Project dispose、stale state file cleanup 必须纳入实施。
7. **Protocol generation:** 使用 webview prebuild / generator 主路径，不再写错误的裸 `gradle generateProtocol` 命令。
8. **OpenCode settings reality:** 当前仓库没有 OpenCode MCP settings handler；OpenCode 由 `OpenCodeConfigReader.readMcpServers()` 读取原生配置，新 turn refresh 时感知外部变化。

---

## 1. Current-State Assessment

### 1.1 Runtime Matrix

当前项目已经有 3 provider x 2 runtime 的路由结构：

- `src/main/java/com/github/claudecodegui/session/runtime/ClaudeSdkSessionRuntime.java`
- `src/main/java/com/github/claudecodegui/session/runtime/ClaudeCliSessionRuntime.java`
- `src/main/java/com/github/claudecodegui/session/runtime/CodexSdkSessionRuntime.java`
- `src/main/java/com/github/claudecodegui/session/runtime/CodexCliSessionRuntime.java`
- `src/main/java/com/github/claudecodegui/session/runtime/OpenCodeSdkSessionRuntime.java`
- `src/main/java/com/github/claudecodegui/session/runtime/OpenCodeCliSessionRuntime.java`
- `src/main/java/com/github/claudecodegui/session/runtime/SessionRuntimeRouter.java`
- `src/main/java/com/github/claudecodegui/session/runtime/SessionRuntimeRegistry.java`

这说明 Gateway 接入点应该放在 CLI runtime / CLI session 层，而不是破坏现有 provider routing。

### 1.2 SDK Mode Boundary

SDK 调用模式本版不考虑 Gateway 归一化，也不增加 SDK Gateway 任务。

现状观察：

- Claude SDK：`ai-bridge/services/claude/message-sender.js` 通过 `loadMcpServersConfigAsRecord()` 加载 MCP，并把 `mcpServers` 传入 SDK query options。
- Codex SDK：`ai-bridge/services/codex/message-service.js` 已处理 `mcp_tool_call` 事件，也有 MCP tools 查询逻辑，但发送路径没有与 Claude 完全相同的显式 `mcpServers` 注入模型。
- OpenCode SDK：`ai-bridge/services/opencode/message-service.js` 通过 `@opencode-ai/sdk` 连接长驻 `opencode serve`，MCP 行为主要由 OpenCode server/config 管理。

结论：

- SDK 模式保持原方式。
- 本计划不修改 `ClaudeSdkSessionRuntime` / `CodexSdkSessionRuntime` / `OpenCodeSdkSessionRuntime`、`ai-bridge/services/claude/message-sender.js`、`ai-bridge/services/codex/message-service.js`、`ai-bridge/services/opencode/message-service.js` 或 `OpenCodeDaemonCoordinator` 的 SDK MCP 行为。
- SDK Gateway 归一化另开设计文档与实施计划；当前文档只要求 CLI 改造不要绕过现有 runtime registry 或污染 SDK 装配。

### 1.3 CLI Mode Decision

CLI 模式统一改为 Gateway。

现状观察：

- Claude CLI：`ClaudeCliSession` 通过 `CliMcpConfig` 生成 per-tab `--mcp-config`，直接包含真实 MCP。
- Codex CLI：`CodexCliSession` 使用 `codex exec --json`；实测慢点是 session start 同步加载/连接 MCP。
- OpenCode CLI：`OpenCodeCliSession` 使用 `opencode run --format json`；OpenCode MCP 来源于 OpenCode config，仍可能受真实 MCP 启动影响。

目标：

- 三个 CLI 都只看到一个本地 Gateway MCP。
- Gateway 内部后台连接真实 MCP，并缓存工具目录。
- CLI startup 不等待全量真实 MCP 初始化。

---

## 2. Target Architecture

```text
SessionSendService(Project)
        |
        v
SessionRuntimeRouter(Project) -> CliSessionManager(Project)
        |
        v
Claude/Codex/OpenCode CliSessionFactory(McpGatewayService)
        |
        | per turn buildCliConfig(provider, tabId, cwd)
        v
CLI one-shot process
        |
        | loads exactly one local MCP: gateway-stdio-client.js
        | args/env include state file + token + pinned revision
        v
Melon MCP Gateway (Project-scoped long-lived Node process)
        |
        | supervisors, tool cache, health, backoff, revision store
        v
idea_mcp / database / ops-automation / dbx / code-generator / webstorm_mcp ...
```

### 2.1 Gateway Responsibilities

Gateway 负责：

- 读取后端下发的 provider-neutral MCP snapshot。
- 给每个真实 MCP server 创建独立 supervisor。
- 后台执行 initialize / tools/list / health check。
- 缓存每个 server 的 tool schema，并按 revision 保存不可变 catalog 快照。
- 对 CLI 暴露一个快速 MCP endpoint。
- 在 `tools/list` 中返回指定 revision 的完整工具快照。
- 在 `tools/call` 中路由到真实 MCP server/tool。
- 对单个 MCP 失败做 BACKOFF，不影响其他 server。
- 给后端返回健康状态。
- 关闭时终止所有子进程并清理 state file / temp config。

Gateway 不负责：

- 不替用户选择 MCP。
- 不把 MCP 变成必须手动选择的 profile。
- 不默认把所有工具压缩成一个泛化 `call_tool`。
- 不修改用户真实 MCP 配置。
- 不在前端实现任何 MCP 业务判断。

### 2.2 Automatic Tool Triggering

为了保留“AI 自动识别测试场景并调用 `idea_mcp`”的体验，Gateway 必须在 `tools/list` 暴露真实工具：

```text
mcp__claude__idea_mcp__run_test
mcp__claude__idea_mcp__open_file
mcp__codex__database__query
mcp__claude__ops_automation__restart_service
```

每个 tool 保留：

- `sourceProvider`：来自 `ProviderType` 的 wire 值。
- 原始 `serverId`。
- 原始 `toolName`。
- 原始 description。
- 原始 input schema。
- Gateway 内部 `routeKey`。

模型仍然根据工具描述/schema 自己决定调用哪个工具，插件不做业务意图判断。如果后续要兼容 `mcp__idea_mcp__run_test` 这类 legacy alias，只能在全 catalog 唯一时额外暴露，并必须有冲突测试。

### 2.3 Snapshot Rule

每个 CLI turn 必须使用启动时 pin 住的 Gateway 工具快照：

```text
turn A starts with catalog revision 101
settings change -> revision 102
turn A tools/list and tools/call still use revision 101
turn B starts with revision 102
```

这样避免模型上下文和运行时工具集合不一致。实现上 `McpGatewayService.buildCliConfig(...)` 必须返回 revision，并由 `gateway-stdio-client.js` 在 `tools/list` / `tools/call` 中带回 Gateway；Gateway 必须保留 bounded immutable revision store，不能让已启动 turn 自动读最新 catalog。

---

## 3. Proposed File Structure

### 3.1 Java Files

- Create `src/main/java/com/github/claudecodegui/mcp/McpGatewayConstants.java`
  - Gateway JSON key、state、env、config 文件名等 SSOT 常量；禁止散落 `melon_gateway`、`revision` 等字面量。
- Create `src/main/java/com/github/claudecodegui/mcp/McpGatewayFeatureFlags.java`
  - 读取 `mcpGateway.enabled`、`mcpGateway.cli.enabled`，默认关闭；本版不定义 `mcpGateway.sdk.*`。
- Create `src/main/java/com/github/claudecodegui/mcp/McpGatewayService.java`
  - `@Service(Service.Level.PROJECT)` + `Disposable` facade：启动 Gateway、刷新 snapshot、生成 CLI 配置、Project dispose 清理。
- Create `src/main/java/com/github/claudecodegui/mcp/McpGatewayConfigSnapshot.java`
  - revision、projectPath、server specs、configHash。
- Create `src/main/java/com/github/claudecodegui/mcp/McpGatewayServerSpec.java`
  - provider-neutral MCP server 描述。
- Create `src/main/java/com/github/claudecodegui/mcp/McpGatewayCliConfig.java`
  - provider-specific CLI 配置结果。
- Create `src/main/java/com/github/claudecodegui/mcp/McpGatewayConfigCollector.java`
  - 从 Claude/Codex/OpenCode 配置读取并归一化 enabled MCP server。
- Create `src/main/java/com/github/claudecodegui/mcp/McpGatewayConfigWriter.java`
  - 写临时 provider-specific config，仅包含 Gateway MCP。
- Create `src/main/java/com/github/claudecodegui/mcp/McpGatewayBridgeClient.java`
  - Java 到 Node Gateway control API 的 token-aware 客户端。
- Create `src/main/java/com/github/claudecodegui/mcp/McpGatewayProcessHandle.java`
  - 封装 Gateway Node 进程、stdout/stderr drain、ready latch、stop/kill。
- Modify `src/main/java/com/github/claudecodegui/session/SessionSendService.java`
  - 构造 `SessionRuntimeRouter` 时传入 `Project`。
- Modify `src/main/java/com/github/claudecodegui/session/runtime/SessionRuntimeRouter.java`
  - 接收 `Project` 或 `CliSessionManager`，创建 `CliSessionManager(project)`。
- Modify `src/main/java/com/github/claudecodegui/cli/CliSessionManager.java`
  - 构造器接收 `Project`，用 `McpGatewayService.getInstance(project)` 装配 factories；保留测试构造器。
- Modify `src/main/java/com/github/claudecodegui/cli/claude/ClaudeCliSessionFactory.java`
- Modify `src/main/java/com/github/claudecodegui/cli/codex/CodexCliSessionFactory.java`
- Modify `src/main/java/com/github/claudecodegui/cli/opencode/OpenCodeCliSessionFactory.java`
  - 注入 Gateway service，并传给各 CLI session。
- Modify `src/main/java/com/github/claudecodegui/cli/claude/ClaudeCliSession.java`
  - CLI command 使用 Gateway config，legacy direct MCP 作为 fallback。
- Modify `src/main/java/com/github/claudecodegui/cli/codex/CodexCliSession.java`
  - `codex exec` 只加载 Gateway MCP。
- Modify `src/main/java/com/github/claudecodegui/cli/opencode/OpenCodeCliSession.java`
  - `opencode run` 只加载 Gateway MCP。
- Modify `src/main/java/com/github/claudecodegui/handler/mcp/McpServerActionHandlers.java`
  - Claude MCP 设置变更后刷新 Gateway。
- Modify `src/main/java/com/github/claudecodegui/handler/codex/CodexMcpServerActionHandlers.java`
  - Codex MCP 设置变更后刷新 Gateway。
- Modify relevant OpenCode provider/config handlers only if native provider config changes can affect copied temp config
  - 当前没有 OpenCode MCP mutation handler；OpenCode MCP 外部修改由每个 new turn 的 `refreshConfig` 感知。
- Modify `src/main/java/com/github/claudecodegui/protocol/DownstreamEvent.java`
  - 增加 Gateway 健康状态事件。
- Modify `src/main/java/com/github/claudecodegui/service/NodeProcessRegistry.java`
  - 识别 `mcp-gateway-server.js` / `gateway-stdio-client.js`，并在进程面板展示 Gateway。

### 3.2 Node Files

- Create `ai-bridge/mcp-gateway-server.js`
  - Gateway long-lived process entrypoint。
- Create `ai-bridge/mcp-gateway/ipc-server.js`
  - token-aware Java control API：apply snapshot、status、refresh、stop。
- Create `ai-bridge/mcp-gateway/security.js`
  - token 校验、日志脱敏。
- Create `ai-bridge/mcp-gateway/state-file.js`
  - 读写/校验 gateway state file。
- Create `ai-bridge/mcp-gateway/framing.js`
  - MCP stdio `Content-Length` framing read/write。
- Create `ai-bridge/mcp-gateway/revision-store.js`
  - bounded immutable catalog revisions + ref/TTL 清理。
- Create `ai-bridge/mcp-gateway/mcp-server.js`
  - 暴露给 CLI 的 MCP server。
- Create `ai-bridge/mcp-gateway/tool-catalog.js`
  - revisioned cached tool catalog。
- Create `ai-bridge/mcp-gateway/server-supervisor.js`
  - 每个真实 MCP 独立状态机。
- Create `ai-bridge/mcp-gateway/tool-router.js`
  - Gateway tool call -> real server/tool。
- Create `ai-bridge/mcp-gateway/health-store.js`
  - health snapshot。
- Create `ai-bridge/mcp-gateway/transport/stdio-client.js`
  - 真实 stdio MCP client。
- Create `ai-bridge/mcp-gateway/transport/http-client.js`
  - 真实 streamable HTTP/SSE MCP client。
- Create `ai-bridge/mcp-gateway/transport/client-factory.js`
  - 按 server spec 选择 transport。
- Create `ai-bridge/mcp-gateway/gateway-stdio-client.js`
  - 被 Claude/Codex/OpenCode CLI 启动的轻量 stdio MCP server/client bridge。

---

## 4. Implementation Tasks

### Task 0: Align Gateway With Existing CLI Wiring

**Files:**

- Modify `SessionSendService.java`
- Modify `SessionRuntimeRouter.java`
- Modify `CliSessionManager.java`
- Modify three `*CliSessionFactory.java`
- Test `McpGatewayCliWiringTest.java`

**Deliverable:** Gateway service 能从 `Project` 以现有注册表链路注入到三个 CLI session，不绕过 runtime/provider routing。

**Steps:**

- [ ] 给 `SessionRuntimeRouter` 增加接收 `Project` 的构造器，并从 `SessionSendService` 传入现有 `project` 字段。
- [ ] 给 `CliSessionManager` 增加 `CliSessionManager(Project project)`，内部获取 `McpGatewayService.getInstance(project)` 并构造三 provider factories。
- [ ] 保留 `CliSessionManager(List<CliSessionFactory> factories)` 测试构造器。
- [ ] factories 通过构造器持有 `McpGatewayService`，`create(String tabId)` 继续返回对应 `CliSession`。
- [ ] 添加 source/behavior test：`SessionRuntimeRouter` 主体仍通过 `SessionRuntimeRegistry` 查表，`CliSessionManager` 主体仍通过 factory map 查表。
- [ ] 运行 `rtk .\gradlew.bat test --tests "*CliSessionManagerTest" --tests "*McpGatewayCliWiringTest"`。

**Commit:** `refactor(cli): inject project gateway through CLI factories`

### Task 1: Add Gateway DTOs

**Files:**

- Create `McpGatewayServerSpec.java`
- Create `McpGatewayConfigSnapshot.java`
- Test `McpGatewayConfigSnapshotTest.java`

**Deliverable:** 后端有 provider-neutral 的 MCP snapshot 表达。

**Steps:**

- [ ] 写 `McpGatewayServerSpec` record：`sourceProvider`、`serverId`、`enabled`、`transport`、`config`。
- [ ] 写 `McpGatewayConfigSnapshot` record：`revision`、`projectPath`、`servers`、`configHash`。
- [ ] hash 使用 `SHA-256`，输入包含 schema version、projectPath、servers JSON；**不要包含 revision**，否则同配置重复 collect 会错误产生新 hash。
- [ ] 测试空 server 列表、有 server 列表、configHash 非空、config deep copy。
- [ ] 运行 `rtk .\gradlew.bat test --tests "*McpGatewayConfigSnapshotTest"`。

**Commit:** `feat(mcp): add gateway config snapshot DTOs`

### Task 2: Collect Provider MCP Configs

**Files:**

- Create `McpGatewayConfigCollector.java`
- Test `McpGatewayConfigCollectorTest.java`

**Deliverable:** 后端能从 Claude/Codex/OpenCode 三种配置来源读取 enabled MCP 并归一化。

**Collector rules:**

- Claude 来源：复用 `McpServerManager.getMcpServersWithProjectPath(projectPath)`。
- Codex 来源：复用 `CodexMcpServerManager` / `CodexSettingsManager` 已有读取逻辑。
- OpenCode 来源：复用 `OpenCodeConfigReader.readMcpServers()`。
- `enabled=false` 的 server 不进入 active snapshot。
- transport 归一化：
  - `local` -> `stdio`
  - `stdio` -> `stdio`
  - `remote` / `http` / `sse` -> `http`
  - 有 `url` 默认 `http`
  - 其他默认 `stdio`

**Steps:**

- [ ] 写测试：三个 provider 各一个 enabled server + 一个 disabled server。
- [ ] 实现 collector 的 `collect(String projectPath)`。
- [ ] collector 保持纯读取，不自行递增 revision；`McpGatewayService` 仅在 `configHash` 变化时递增 revision。
- [ ] provider 值使用 `ProviderType`，不写裸字符串。
- [ ] 运行 `rtk .\gradlew.bat test --tests "*McpGatewayConfigCollectorTest"`。

**Commit:** `feat(mcp): collect provider MCP configs for gateway`

### Task 3: Add Node Framing, Revision Store, And Tool Catalog

**Files:**

- Create `ai-bridge/mcp-gateway/framing.js`
- Create `ai-bridge/mcp-gateway/revision-store.js`
- Create `ai-bridge/mcp-gateway/tool-catalog.js`
- Test `ai-bridge/mcp-gateway/framing.test.js`
- Test `ai-bridge/mcp-gateway/revision-store.test.js`
- Test `ai-bridge/mcp-gateway/tool-catalog.test.js`

**Deliverable:** Gateway 能读写 MCP stdio `Content-Length` JSON-RPC，按 revision 保存不可变 catalog，并把真实 MCP tools 映射为稳定 Gateway tool names。

**Rules:**

- MCP stdio framing 使用 `Content-Length: <bytes>\r\n\r\n<payload>`。
- `revision-store` 必须支持 `publishRevision` / `acquireRevision` / `releaseRevision` / TTL cleanup；unknown revision 返回 MCP error，不 fallback latest。
- Gateway tool name 默认格式：`mcp__<sourceProvider>__<serverId>__<toolName>`，避免跨 provider 同名冲突。
- route map 保存 `gatewayToolName -> { serverKey, sourceProvider, serverId, toolName }`。
- `serverKey` 语义为 `<sourceProvider>:<serverId>`，实现必须引用常量/方法，不散落分隔符字面量。
- 新 snapshot 删除不存在/disabled server 的新 revision tools；旧 revision 仅为已启动 turn 保留。
- disabled/deleted server 不出现在新 revision 的 `tools/list`。

**Steps:**

- [ ] 写 framing 测试：split chunks 能正确解析一条 Content-Length 消息，并拒绝超大 header/body。
- [ ] 写 revision 测试：publish 101/102 后，101 查询结果不受 102 影响；unknown revision 返回明确 error。
- [ ] 写测试：upsert `claude/idea_mcp/run_test` 后 listTools 返回 `mcp__claude__idea_mcp__run_test`。
- [ ] 写测试：Claude 与 Codex 同名 `idea_mcp/run_test` 不冲突。
- [ ] 写测试：新 snapshot 删除 server 后新 revision listTools 为空，旧 revision 保持到 TTL/ref 释放。
- [ ] 实现 sanitize：只允许 `[A-Za-z0-9_-]`。
- [ ] 保留原始 `description` 和 `inputSchema`。
- [ ] 运行 `rtk node --test ai-bridge/mcp-gateway/framing.test.js ai-bridge/mcp-gateway/revision-store.test.js ai-bridge/mcp-gateway/tool-catalog.test.js`。

**Commit:** `feat(mcp): add gateway framing and pinned tool catalog`

### Task 4: Add Per-Server Supervisor

**Files:**

- Create `ai-bridge/mcp-gateway/server-supervisor.js`
- Test `ai-bridge/mcp-gateway/server-supervisor.test.js`

**Deliverable:** 每个真实 MCP server 独立生命周期、健康状态、退避和调用。

**State machine:**

```text
DISABLED
STARTING
READY
DEGRADED
BACKOFF
STOPPING
DELETED
```

**State fields:**

- `serverId`
- `sourceProvider`
- `state`
- `lastError`
- `lastSuccessAt`
- `lastToolsAt`
- `failureCount`
- `backoffUntil`
- `inFlightCalls`

**Steps:**

- [ ] 写测试：refreshTools 成功后 `READY`。
- [ ] 写测试：refreshTools 失败后 `BACKOFF`，记录 `lastError`。
- [ ] 写测试：一个 supervisor 失败不影响另一个 supervisor。
- [ ] 实现指数退避：`1s -> 3s -> 10s -> 30s -> 60s`。
- [ ] 所有 request 必须有 timeout。
- [ ] 运行 `rtk node --test ai-bridge/mcp-gateway/server-supervisor.test.js`。

**Commit:** `feat(mcp): add gateway server supervisor`

### Task 5: Implement Real MCP Transport Clients

**Files:**

- Create `ai-bridge/mcp-gateway/transport/stdio-client.js`
- Create `ai-bridge/mcp-gateway/transport/http-client.js`
- Create `ai-bridge/mcp-gateway/transport/client-factory.js`
- Test `ai-bridge/mcp-gateway/transport/*.test.js`

**Deliverable:** Gateway 能连接真实 stdio 和 HTTP/SSE MCP。

**Protocol methods:**

- `initialize`
- `notifications/initialized`
- `tools/list`
- `tools/call`

**Steps:**

- [ ] 可参考 `ai-bridge/services/claude/mcp-status/mcp-protocol.js` 的 protocol version / HTTP/SSE helpers，但 Gateway 主实现不要复用旧 line-delimited stdio probe。
- [ ] stdio client 启动真实 MCP process，按 `Content-Length` framing 读写 JSON-RPC。
- [ ] HTTP client 支持 headers、bearer token env、session id。
- [ ] `listTools()` 返回 `result.tools`。
- [ ] `callTool(name,args)` 返回 MCP tool result。
- [ ] `close()` 释放 process/session。
- [ ] fake stdio MCP server 测试 tools/list 和 tools/call。
- [ ] fake HTTP MCP server 测试 tools/list 和 tools/call。
- [ ] 运行 `rtk node --test ai-bridge/mcp-gateway/transport/*.test.js`。

**Commit:** `feat(mcp): add gateway MCP transport clients`

### Task 6: Expose Gateway MCP Server to CLI

**Files:**

- Create `ai-bridge/mcp-gateway/mcp-server.js`
- Create `ai-bridge/mcp-gateway/tool-router.js`
- Create `ai-bridge/mcp-gateway/gateway-stdio-client.js`
- Test `ai-bridge/mcp-gateway/tool-router.test.js`

**Deliverable:** Claude/Codex/OpenCode CLI 启动的本地 MCP server 能快速返回 pinned tool catalog，并路由 tool calls。

**Gateway MCP methods:**

- `initialize`
- `notifications/initialized`
- `tools/list`
- `tools/call`

**Steps:**

- [ ] 写 router 测试：`mcp__claude__idea_mcp__run_test` 路由到 `claude:idea_mcp/run_test`。
- [ ] 写 router 测试：server 不可用返回 MCP error，不挂死。
- [ ] 实现 `tools/list` 只读 pinned catalog，不等待真实 MCP。
- [ ] 实现 `tools/call` 只等待目标 server，不等待其他 server。
- [ ] `gateway-stdio-client.js` 作为 CLIs 配置里的 command，从 args/env 读取 state file、token、revision，连接长驻 Gateway control/runtime。
- [ ] `gateway-stdio-client.js` 与 CLI 通信使用 Content-Length framing，并在 exit 时 release revision；异常退出由 Gateway TTL 兜底。
- [ ] 运行 `rtk node --test ai-bridge/mcp-gateway/*.test.js`。

**Commit:** `feat(mcp): expose pinned tools through gateway MCP server`

### Task 7: Add Long-Lived Gateway Process

**Files:**

- Create `ai-bridge/mcp-gateway-server.js`
- Create `ai-bridge/mcp-gateway/ipc-server.js`
- Create `ai-bridge/mcp-gateway/security.js`
- Create `ai-bridge/mcp-gateway/state-file.js`
- Create `ai-bridge/mcp-gateway/health-store.js`

**Deliverable:** 后端可以启动一个长驻 Gateway daemon，发送 snapshot，查询 status。

**Control API:**

```text
POST /snapshot
GET /status
POST /refresh
POST /refresh/:serverKey
POST /release/:revision
POST /stop
```

**Steps:**

- [ ] Gateway 启动后监听 `127.0.0.1` 随机端口。
- [ ] 启动时生成随机 token；所有 control/runtime API 必须校验 token，日志不得输出 token。
- [ ] 端口/PID/token/state 写入插件 config/cache 私有临时目录，并尽力设置 owner-only 权限。
- [ ] `/snapshot` 应用 revision：
  - 新增 server -> 创建 supervisor，后台 refresh。
  - 更新 server -> config hash 变化则重启 supervisor。
  - 删除/禁用 server -> stop supervisor，catalog 删除 tools。
- [ ] refresh 成功后更新 catalog。
- [ ] refresh 失败时，仍 enabled 且已有旧 tools 的 server 保留旧 tools 并 health 进入 `DEGRADED`/`BACKOFF`；disabled/deleted server 不保留。
- [ ] `/status` 返回所有 server health + Gateway uptime/revision。
- [ ] `/stop` graceful close 所有 supervisor，然后退出进程。
- [ ] 运行 Node tests。

**Commit:** `feat(mcp): add secure long-lived gateway process`

### Task 8: Add Java Gateway Lifecycle Service

**Files:**

- Create `McpGatewayService.java`
- Create `McpGatewayBridgeClient.java`
- Create `McpGatewayCliConfig.java`
- Test `McpGatewayServiceTest.java`

**Deliverable:** Java 层能启动 Gateway、apply snapshot、为 CLI 构造 provider-specific MCP config。

**Steps:**

- [ ] `McpGatewayService` 标注 `@Service(Service.Level.PROJECT)` 并实现 `Disposable`。
- [ ] `ensureStarted(projectPath)` 使用 `BridgePreloader.getSharedResolver().findSdkDir()` 找 ai-bridge，并用 `NodeDetector.buildNodeScriptCommand(node, scriptPath)` 启动 Node Gateway。
- [ ] `refreshConfig(projectPath)` collect snapshot；只有 `configHash` 变化时递增 revision 并 POST `/snapshot`。
- [ ] `buildCliConfig(provider, tabId, projectPath)` 确保 Gateway ready，刷新最新 snapshot，写临时 config，并返回 pinned revision。
- [ ] `dispose()` POST `/stop`，失败则 terminate process tree，删除 state/temp files。
- [ ] 失败 fallback：返回 legacy direct MCP config 可用状态，并打日志/状态事件。
- [ ] 运行 `rtk .\gradlew.bat test --tests "*McpGatewayServiceTest"`。

**Commit:** `feat(mcp): manage gateway lifecycle from Java`

### Task 9: Write Provider-Specific Gateway Configs

**Files:**

- Create `McpGatewayConfigWriter.java`
- Test `McpGatewayConfigWriterTest.java`

**Deliverable:** 为三种 CLI 写只包含 Gateway MCP 的临时配置。

**Claude config shape:**

```json
{
  "mcpServers": {
    "melon_gateway": {
      "type": "stdio",
      "command": "<command-from-NodeDetector>",
      "args": ["<args-from-NodeDetector>", "--state-file", "...", "--revision", "101"]
    }
  }
}
```

**Common Gateway command rule:**

- Java 构造 `NodeDetector.buildNodeScriptCommand(nodePath, gatewayStdioClientPath)`。
- 写入 provider config 时，`command` 是列表第一项，`args` 是其余项 + `--state-file <path>` + `--revision <revision>`。
- Windows/WSL 路径转换由 `NodeDetector` 负责。

**Codex config decision:**

- Preferred only if smoke test proves it replaces existing user `mcp_servers` rather than merging:

```toml
mcp_servers = { melon_gateway = { command = "<command>", args = ["..."], enabled = true, startup_timeout_sec = 1 } }
```

- Otherwise use temporary `CODEX_HOME`:
  - copy `auth.json`;
  - copy necessary model/provider/proxy/env config;
  - write `config.toml` with only `mcp_servers.melon_gateway`;
  - never modify real `~/.codex/config.toml`.

**OpenCode config decision:**

- Current local `opencode 1.17.11` has no per-run config path flag.
- Use temporary home/config for the opencode process:
  - set `HOME` and Windows `USERPROFILE` to temp home;
  - set `XDG_CONFIG_HOME` / `XDG_DATA_HOME` / `XDG_CACHE_HOME` / `XDG_STATE_HOME` where supported;
  - copy original `provider` / `permission` / required auth-related sections from `opencode.json`;
  - write `mcp.melon_gateway` only;
  - keep temp home per tab until session dispose to preserve OpenCode session continuation data.

**Steps:**

- [ ] 写 test 确认每个 provider 输出只包含 `melon_gateway`。
- [ ] 临时文件路径必须在插件 config/cache 目录，并使用 safe filename。
- [ ] 不写用户真实配置。
- [ ] Gateway command 必须来自 `NodeDetector.buildNodeScriptCommand(...)` 的结果，不能硬编码裸 `node`。
- [ ] `melon_gateway` 常量集中定义，不散落字面量。
- [ ] 运行 `rtk .\gradlew.bat test --tests "*McpGatewayConfigWriterTest"`。

**Commit:** `feat(mcp): write isolated provider gateway configs`

### Task 10: Wire Claude CLI

**Files:**

- Modify `ClaudeCliSession.java`
- Modify `ClaudeCliSessionFactory.java`
- Test `ClaudeCliSession` command builder tests

**Deliverable:** Claude CLI 使用 `--mcp-config <gateway-config>`，不再每 turn 直接加载全量真实 MCP。

**Steps:**

- [ ] 给 `ClaudeCliSession` 注入 `McpGatewayService`，保留 legacy constructor 供测试。
- [ ] send 前调用 `buildCliConfig(ProviderType.CLAUDE, tabId, request.cwd())`。
- [ ] `buildCommand` 改为接收 `McpGatewayCliConfig` 或等价参数，Gateway enabled + ready 时使用 Gateway config path。
- [ ] Gateway 不可用时 fallback 到现有 `CliMcpConfig`。
- [ ] 测试命令包含 `--mcp-config` 且路径为 Gateway config。
- [ ] 运行 `rtk .\gradlew.bat test --tests "*ClaudeCliSession*"`.

**Commit:** `feat(mcp): route Claude CLI through gateway`

### Task 11: Wire Codex CLI

**Files:**

- Modify `CodexCliSession.java`
- Modify `CodexCliCommandUtils.java`
- Test `CodexCliSession` command builder tests

**Deliverable:** Codex CLI 每 turn 只加载 Gateway MCP，避免启动时同步加载全量真实 MCP。

**Implementation decision:**

Preferred only if smoke test proves replacement semantics:

- 使用 `codex exec -c 'mcp_servers={melon_gateway={...}}'` 整体替换 MCP table；不要使用 nested `mcp_servers.melon_gateway...` 追加方式。

Fallback:

- 使用临时 `CODEX_HOME` 或 profile，复制必要 auth/provider/model/proxy/env 配置，只写 Gateway MCP。

**Steps:**

- [ ] 先 smoke test 当前 Codex CLI 是否可靠支持 `mcp_servers={...}` 整体替换；nested MCP `-c mcp_servers.melon_gateway...` 只能作为失败用例验证不能采用。
- [ ] 若整体替换可靠，用 `-c` overrides。
- [ ] 若不可靠，使用临时 `CODEX_HOME`/profile；必须保留 auth/provider/model/proxy/env 等必要字段。
- [ ] 不禁用 MCP 语义，只是把真实 MCP 替换为 Gateway MCP。
- [ ] 测试 command/config 中只有 `melon_gateway`。
- [ ] 运行 `rtk .\gradlew.bat test --tests "*CodexCliSession*"`.

**Commit:** `feat(mcp): route Codex CLI through gateway`

### Task 12: Wire OpenCode CLI

**Files:**

- Modify `OpenCodeCliSession.java`
- Test `OpenCodeCliSession` command builder/config tests

**Deliverable:** OpenCode CLI 每 turn 只加载 Gateway MCP。

**Implementation decision:**

- 当前本地 `opencode 1.17.11` 的 `opencode run --help` 没有 per-run config path；第一版使用临时 home/config env。
- 设置 `HOME` 和 Windows `USERPROFILE` 到 per-tab temp home，并在可用时设置 `XDG_CONFIG_HOME` / `XDG_DATA_HOME` / `XDG_CACHE_HOME` / `XDG_STATE_HOME`。
- 复制原生 `provider` / `permission` / auth 相关必要段，仅替换 `mcp` 为 Gateway；temp home 保留到 tab dispose 以支持 session continuation。

**Steps:**

- [ ] 基于本地 `opencode 1.17.11` 无 config path 的事实，实现临时 home/config 注入；如果未来新增官方 flag，再以 smoke test 切换。
- [ ] 实现 Gateway config 注入。
- [ ] 确保不修改 `~/.config/opencode/opencode.json`，并保持 `pb.redirectInput(stdinNullSink())` 不变，避免 B9 回归。
- [ ] 测试 OpenCode run command/env 指向临时 Gateway config。
- [ ] 运行 `rtk .\gradlew.bat test --tests "*OpenCodeCliSession*"`.

**Commit:** `feat(mcp): route OpenCode CLI through gateway`

### Task 13: Hot Update Gateway from Settings

**Files:**

- Modify `McpServerActionHandlers.java`
- Modify `CodexMcpServerActionHandlers.java`
- Modify relevant OpenCode provider/config handlers only if native provider config changes can affect copied temp config
- Test handler/source checks

**Deliverable:** 设置页新增、删除、更新、启停 MCP 后，Gateway 立即收到新 snapshot。

**Steps:**

- [ ] Claude 与 Codex add/update/delete/toggle 成功持久化后调用 `McpGatewayService.refreshConfig(projectPath)`。
- [ ] refresh 失败不回滚用户设置，但要记录 warning 并下发状态。
- [ ] 已启动 turn 不强制刷新 tool list；新 turn 使用新 revision。
- [ ] 添加 source-string 或 handler unit tests 覆盖所有 mutation methods。

**Commit:** `feat(mcp): refresh gateway after MCP settings changes`

### Task 14: Expose Gateway Health to UI

**Files:**

- Modify `DownstreamEvent.java`
- Regenerate `webview/src/generated/protocol.ts`
- Modify MCP settings status handler
- Optional frontend display changes

**Deliverable:** 设置页能看到 Gateway 和每个真实 MCP 的健康状态。

**Payload shape:**

```json
{
  "revision": 12,
  "servers": [
    {
      "serverId": "idea_mcp",
      "sourceProvider": "claude",
      "state": "READY",
      "lastError": null,
      "lastSuccessAt": 1710000000000,
      "failureCount": 0
    }
  ]
}
```

**Steps:**

- [ ] 增加 `MCP_GATEWAY_STATUS("mcp.gateway.status")`。
- [ ] 运行 `rtk npm --prefix webview run prebuild`，或 `rtk node webview/scripts/generate-protocol-types.mjs`。
- [ ] 如需反射交叉校验，额外运行 `rtk .\gradlew.bat generateProtocol -PgenerateProtocol=true`。
- [ ] 后端查询 Gateway `/status` 后通过 `DownstreamEvent.MCP_GATEWAY_STATUS.value()` 下发。
- [ ] 前端只渲染状态，不做业务判定。

**Commit:** `feat(protocol): expose MCP gateway health events`

### Task 15: CLI Symmetry And Lifecycle Guard

**Files:**

- Create `CliMcpGatewaySymmetryTest.java`
- Modify `NodeProcessRegistry.java`
- Test `NodeProcessRegistryHelpersTest.java`

**Deliverable:** 三个 CLI provider 都接入 Gateway，Gateway 进程可见、可清理，避免只修一个 provider 或泄漏长驻进程。

**Steps:**

- [ ] Source-string test：`ClaudeCliSession.java` 包含 `McpGatewayService`。
- [ ] Source-string test：`CodexCliSession.java` 包含 `McpGatewayService`。
- [ ] Source-string test：`OpenCodeCliSession.java` 包含 `McpGatewayService`。
- [ ] Source-string test：三者都有 Gateway fallback/diagnostic 分支。
- [ ] `NodeProcessRegistry` own-process hints 增加 `mcp-gateway-server.js` 与 `gateway-stdio-client.js`。
- [ ] `McpGatewayService.dispose()` 停止 Gateway；source/behavior test 覆盖 dispose path。

**Commit:** `test(cli): guard MCP gateway symmetry and lifecycle`

### Task 16: Manual E2E Validation

**Files:**

- Create `docs/mcp-gateway-validation.md`

**Validation matrix:**

| Provider | Runtime | Expected |
|---|---|---|
| Claude | CLI | 只加载 Gateway，模型能自动调用真实 MCP |
| Codex | CLI | 首包不被坏 MCP 阻塞，模型能自动调用真实 MCP |
| OpenCode | CLI | 只加载 Gateway，坏 MCP 不影响健康 MCP，stdin EOF 行为不回归 |

**Manual scenarios:**

- [ ] 配置一个健康 `idea_mcp` 和一个故障 HTTP 502 MCP。
- [ ] 分别启动 Claude/Codex/OpenCode CLI 对话，记录 process start、stdin close/redirect、first stdout、first content、complete。
- [ ] 发送测试相关 prompt，确认模型自动调用 `idea_mcp` 对应 Gateway tool。
- [ ] 禁用 `idea_mcp`，确认下一 turn 工具目录移除；当前 turn 不漂移。
- [ ] 重新启用 `idea_mcp`，确认后台 refresh 后下一 turn 可用。
- [ ] 删除一个 MCP，确认 Gateway 关闭 supervisor 且无进程泄漏。
- [ ] 故障 MCP BACKOFF 时，健康 MCP 调用仍成功。
- [ ] Project close 后确认 `mcp-gateway-server.js` 退出，state/temp files 清理。
- [ ] Node process panel 能看到 Gateway，并且不会把其他 IDE/JVM 的 Gateway 当作本项目可杀进程。

**Commit:** `docs(mcp): add gateway validation matrix`

---

## 5. Rollout Plan

1. 第一版加 feature flag，默认关闭：
   - `mcpGateway.enabled=false`
2. 内测打开 CLI Gateway：
   - `mcpGateway.cli.enabled=true`
3. SDK 调用模式保持现有 provider-native MCP 行为，不读取 Gateway feature flag。
4. Gateway 失败时 fallback 到 legacy direct MCP，并下发 warning。
5. 手工验证三 provider CLI 后，默认打开 CLI Gateway。
6. 观察一个版本后，再评估是否移除 legacy direct MCP fallback。
7. SDK 归一化另开设计，不混入本次 CLI 改造。

---

## 6. Testing Commands

Java targeted tests:

```bash
rtk .\gradlew.bat test --tests "*McpGateway*"
rtk .\gradlew.bat test --tests "*CliMcpGateway*"
rtk .\gradlew.bat test --tests "*ClaudeCliSession*" --tests "*CodexCliSession*" --tests "*OpenCodeCliSession*"
```

Protocol generation:

```bash
rtk npm --prefix webview run prebuild
# Optional cross-check only; Gradle task is disabled unless this property is set:
rtk .\gradlew.bat generateProtocol -PgenerateProtocol=true
```

Node targeted tests:

```bash
rtk node --test ai-bridge/mcp-gateway/*.test.js
rtk node --test ai-bridge/mcp-gateway/transport/*.test.js
```

Full validation before release:

```bash
rtk .\gradlew.bat test
rtk node --test ai-bridge/**/*.test.js
rtk npm --prefix webview run build
```

Note: `ai-bridge/package.json` currently has no `test` script, so do not use `cd ai-bridge && npm test` unless a test script is added in a separate build/test commit.

---

## 7. Risks and Mitigations

- **Gateway tool cache stale**
  - Use revision + configHash; refresh on settings mutation; stale cache marked in health payload.
- **Single MCP hangs**
  - Per-server timeout, process termination, supervisor BACKOFF; never block `tools/list`.
- **Model loses automatic tool selection**
  - Expose real tools with original descriptions/schemas; avoid generic router-only design.
- **Current turn and new config mismatch**
  - Stable per-turn snapshot; new config applies next turn.
- **Codex/OpenCode config override unreliable**
  - Use temporary config home/profile fallback and copy required auth/provider/model/proxy fields.
- **Gateway control API exposed locally**
  - Bind to `127.0.0.1`, random token auth, private state file, log redaction.
- **Tool name collision**
  - Include `sourceProvider` in Gateway tool name; optional legacy aliases only when globally unique.
- **Codex config override merges instead of replaces**
  - Smoke test replacement semantics; fallback to temporary `CODEX_HOME` that contains only Gateway MCP.
- **OpenCode lacks config override**
  - Use temporary home/config env; preserve provider/permission sections and per-tab temp state for continuation.
- **Process leak**
  - `McpGatewayService implements Disposable`; `NodeProcessRegistry` recognizes Gateway; manual validation includes Project close.
- **SDK accidental coupling**
  - 本版不修改 SDK runtime / SDK message-service；review 与 source checks 只确认 Gateway 沿 CLI factory 链路注入。
- **User config corruption**
  - Normal chat writes only plugin temp/cache files; user config changed only by explicit settings actions.
- **Provider asymmetry**
  - Add CLI symmetry source tests and manual 3 provider validation matrix.

---

## 8. Recommended Commit Sequence

1. `refactor(cli): inject project gateway through CLI factories`
2. `feat(mcp): add gateway constants and snapshot DTOs`
3. `feat(mcp): collect provider MCP configs for gateway`
4. `feat(mcp): add gateway framing and pinned tool catalog`
5. `feat(mcp): add gateway server supervisor`
6. `feat(mcp): add gateway MCP transport clients`
7. `feat(mcp): expose pinned tools through gateway MCP server`
8. `feat(mcp): add secure long-lived gateway process`
9. `feat(mcp): manage gateway lifecycle from Java`
10. `feat(mcp): write isolated provider gateway configs`
11. `feat(mcp): route Claude CLI through gateway`
12. `feat(mcp): route Codex CLI through gateway`
13. `feat(mcp): route OpenCode CLI through gateway`
14. `feat(mcp): refresh gateway after MCP settings changes`
15. `feat(protocol): expose MCP gateway health events`
16. `test(cli): guard MCP gateway symmetry and lifecycle`
17. `docs(mcp): add gateway validation matrix`

---

## 9. Final Decision

- SDK 模式：本版不考虑、不改造、不加 Gateway binding；后续如需归一化另开计划。
- CLI 模式：统一走 MCP Gateway，但必须通过现有 CLI factory/runtime 链路注入。
- 无感触发：通过 Gateway 暴露真实工具快照实现，而不是用户手动选择 MCP。
- 快照一致性：通过 per-turn revision pinning 实现，当前 turn 不随设置变化漂移。
- 故障隔离：通过 per-server supervisor、工具缓存、BACKOFF 和 health reporting 实现。
- 安全性：通过 loopback + random token + private state file 实现 Gateway 控制面保护。
- 设置实时更新：通过 configHash/revision snapshot 和 settings mutation hook 实现，新 turn 生效。
- 进程健壮性：Gateway 是 Project-scoped service，dispose 必须关闭进程并清理 temp/state。
