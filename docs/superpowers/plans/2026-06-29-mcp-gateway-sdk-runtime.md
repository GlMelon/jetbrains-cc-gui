# MCP Gateway SDK Runtime 方案文档

> 本文只描述 SDK 模式的 MCP 归一化方案，不包含产品代码实现。CLI 模式的 Gateway 主线方案见 `docs/superpowers/plans/2026-06-29-mcp-gateway-cli-runtime.md`。
>
> **Status (2026-06-29): Deferred research.** 当前版本实施范围明确排除 SDK 调用模式：Claude / Codex / OpenCode SDK 路径保持 provider-native MCP 行为，不接入 Gateway、不新增 SDK Gateway feature flag、不纳入 CLI Gateway 任务验收。本文件仅保留为后续 SDK 归一化的研究记录。

## 1. 背景与目标

桌面 `codex mcp.txt` 的测试结论已经说明：Codex CLI 慢点主要不是 Java 启动子进程，而是 `codex exec` 在首条 stdout 前同步加载、连接和重试真实 MCP。CLI 模式的主方案是引入长驻 `MCP Gateway`，让每次 one-shot 子进程只加载一个本地快速 MCP 层。

用户进一步要求确认 SDK 模式是否也能对齐：

- Claude / Codex / OpenCode 的 SDK 模式是否都已经支持 MCP 接入。
- SDK 模式能否也使用同一套 `MCP Gateway`，减少真实 MCP 初始化对发送链路的影响。
- 如果可以，尽量让 SDK 与 CLI 共享 MCP Gateway、工具目录缓存、健康检查、热更新和故障隔离。
- 如果某个 provider 的 SDK 绑定能力不足，不强求一刀切；但要明确差异、风险和验证点。

后续研究目标是：

- MCP 和 skills 仍然保持“无感触发”，模型根据工具名、description、input schema 自动选择工具。
- 设置页新增、删除、启停 MCP 后，SDK/CLI 都能在合理边界内热更新。
- 一个坏 MCP 不影响其他 MCP，不影响普通对话。
- 3 provider x 2 mode 的架构对称性可检查、可回归。

## 2. 官方文档依据

### 2.1 Claude Agent SDK

官方 Agent SDK MCP 文档确认：

- SDK `query()` 支持在 `options.mcpServers` 中直接传入 MCP server 配置。
- 支持 stdio、HTTP/SSE、SDK in-process MCP。
- MCP 工具命名为 `mcp__<server-name>__<tool-name>`。
- 建议用 `allowedTools` 精确允许 MCP 工具，通配符可以允许某个 server 下全部工具。
- SDK init 消息中包含 MCP server 连接状态，可用于失败诊断。

参考：

- https://code.claude.com/docs/en/agent-sdk/mcp
- https://code.claude.com/docs/en/agent-sdk/typescript

结论：Claude SDK 最适合接入 Gateway，可以在每次 query 的 `mcpServers` 中注入虚拟 MCP server，并保持原始 server 名。

### 2.2 Codex SDK

官方 Codex SDK 文档和 TypeScript README 确认：

- `@openai/codex-sdk` 是 Codex CLI wrapper，会启动 `codex` CLI，并通过 stdin/stdout 交换 JSONL events。
- `new Codex({ env })` 可以控制传给 Codex CLI 的环境变量。
- `new Codex({ config })` 可以传入 Codex CLI 配置覆盖；SDK 会把 JSON object flatten 成 dotted `--config key=value`。
- Codex MCP 配置来源是 Codex 配置中的 `mcp_servers`。

参考：

- https://developers.openai.com/codex/sdk
- https://github.com/openai/codex/blob/main/sdk/typescript/README.md
- https://developers.openai.com/codex/mcp
- https://developers.openai.com/codex/config-reference

结论：Codex SDK 可以接入 Gateway，但不是像 Claude 一样通过 `mcpServers` query option，而是通过 `new Codex({ config: { mcp_servers: ... } })` 注入虚拟 MCP 配置。需要重点实测 SDK thread cache、resume、`config` override 与真实 `~/.codex/config.toml` 的合并优先级。

### 2.3 OpenCode SDK

官方 OpenCode SDK / config / MCP 文档确认：

- `@opencode-ai/sdk` 提供 `createOpencode()`，会启动 server + client。
- `createOpencode({ config })` 可传入配置对象。
- `createOpencodeClient({ baseUrl })` 可连接已有 `opencode serve`。
- OpenCode MCP 配置在 `mcp` 字段下，支持 local 和 remote server。
- OpenCode 配置支持多层合并，`OPENCODE_CONFIG` 是自定义 config 文件，`OPENCODE_CONFIG_CONTENT` 是运行时 inline override。
- OpenCode MCP 工具会自动加入 LLM 可用工具集合。

参考：

- https://opencode.ai/docs/sdk/
- https://opencode.ai/docs/config/
- https://opencode.ai/docs/mcp-servers
- https://opencode.ai/docs/server

结论：OpenCode SDK 可以接入 Gateway，但当前插件 SDK 模式是 Java 启动 `opencode serve`，Node 侧 `createOpencodeClient({ baseUrl })` 只连接已有 server。因此 Gateway 配置必须注入到 `opencode serve` 启动配置中，而不是在每次 `session.prompt()` 时注入。

## 3. 当前项目 SDK MCP 支持现状

| Provider | 当前 SDK MCP 支持 | 当前代码位置 | 主要缺口 |
|---|---|---|---|
| Claude | 已支持。发送时加载 `mcpServers` 并传入 Claude Agent SDK options。 | `ai-bridge/services/claude/message-sender.js`, `ai-bridge/services/claude/persistent-query-service.js`, `ai-bridge/services/claude/mcp-status/config-loader.js` | 仍直接加载真实 MCP；坏 MCP 可能拖慢 init；未接 Gateway。 |
| Codex | 部分支持。工具探测复用 MCP status 逻辑；消息流已处理 `mcp_tool_call`。运行时主要依赖 Codex CLI 自己读取配置。 | `ai-bridge/services/codex/message-service.js`, `ai-bridge/services/codex/codex-event-handler.js`, `src/main/java/.../CodexSDKBridge.java` | `sendMessage()` 当前未显式把 `mcp_servers` 注入 `codexOptions.config`；需要补 Gateway binding 和 cache signature。 |
| OpenCode | 支持由 `opencode serve` 读取配置后的 MCP 自动可用。Node SDK client 连接已有 serve。 | `ai-bridge/services/opencode/message-service.js`, `src/main/java/.../OpenCodeDaemonCoordinator.java`, `src/main/java/.../OpenCodeConfigReader.java` | SDK client 没有每次 prompt 的 MCP 注入口；必须在 serve 启动时注入 Gateway config；schema revision 变更可能要重启 serve。 |

## 4. SDK 是否也接入 Gateway

后续可以评估，但当前 CLI Gateway 版本不实施。若未来要做 SDK Gateway，也不能要求三者使用同一个 SDK 注入方式。推荐研究结论：

- Gateway 进程、真实 MCP supervisor、tool catalog、health store、settings hot update 统一复用。
- SDK 与 CLI 都不再直接连接真实 MCP，而是连接 Gateway 暴露的虚拟 MCP server。
- SDK 侧按 provider binding 接入：
  - Claude SDK：per query 注入 `mcpServers`。
  - Codex SDK：`new Codex({ config })` 注入 `mcp_servers`。
  - OpenCode SDK：启动 `opencode serve` 时通过 `OPENCODE_CONFIG_CONTENT` 或 `createOpencode({ config })` 注入 `mcp`。
- 不采用“单个 `melon_gateway` server 暴露所有工具”的设计作为主路径；采用“单 Gateway 进程 + 多 virtual MCP server endpoint”。

### 为什么不用单个 `melon_gateway`

如果只暴露一个 MCP server：

```text
mcp__melon_gateway__idea_mcp_run_test
mcp__melon_gateway__database_query
```

模型看到的 server 名从 `idea_mcp`、`database` 变成 `melon_gateway`，历史提示、权限配置、provider 的工具命名规则都可能漂移。

推荐保留原 server id：

```text
mcp__idea_mcp__run_test
mcp__database__query
mcp__ops_automation__restart_service
```

内部实现仍是同一个 Gateway 进程，只是给 provider 暴露多个 virtual endpoint：

```text
idea_mcp      -> http://127.0.0.1:<port>/mcp/idea_mcp
database      -> http://127.0.0.1:<port>/mcp/database
ops_automation -> http://127.0.0.1:<port>/mcp/ops_automation
```

或 stdio adapter：

```text
node gateway-stdio-client.js --server idea_mcp --revision 102
node gateway-stdio-client.js --server database --revision 102
```

## 5. 目标架构

```text
SDK Runtime
  Claude Agent SDK     Codex SDK wrapper     OpenCode SDK client
        |                    |                      |
        | provider binding   | provider binding     | serve config binding
        v                    v                      v
  virtual MCP servers pointing to Gateway, names preserved
        |
        v
Long-lived MCP Gateway
        |
        | per-server supervisor + cached tools/list + isolated tools/call
        v
real MCP servers: idea_mcp / database / ops / dbx / code-generator / ...
```

Gateway 职责：

- 读取后端下发的 provider-neutral MCP snapshot。
- 为每个真实 MCP server 维护独立 supervisor。
- 后台 initialize、tools/list、health check。
- `tools/list` 返回缓存 schema，不等待真实 MCP。
- `tools/call` 只等待目标 server，不等待其他 server。
- 单 server 失败进入 BACKOFF，不影响其他 server。
- 设置变更时刷新 schema revision，新 turn 使用新 revision。

SDK binding 职责：

- 只把虚拟 MCP server 注入 provider SDK。
- 不参与工具选择，不做场景判断。
- 保留真实 server id、tool name、description、input schema。
- 把 schema revision 纳入会话/thread/daemon cache 签名，避免工具集变化后复用旧上下文。

## 6. Provider Binding 设计

### 6.1 Claude SDK Binding

当前路径：

- `ai-bridge/services/claude/message-sender.js`
- `ai-bridge/services/claude/persistent-query-service.js`
- `ai-bridge/services/claude/mcp-status/config-loader.js`

改造思路：

1. Java/Node 启动或发送前确保 Gateway 已启动。
2. 从 Gateway control API 获取当前 `schemaRevision` 与 virtual server list。
3. 构造 Claude Agent SDK `mcpServers`：

```javascript
{
  "idea_mcp": {
    "type": "http",
    "url": "http://127.0.0.1:<port>/mcp/idea_mcp"
  },
  "database": {
    "type": "http",
    "url": "http://127.0.0.1:<port>/mcp/database"
  }
}
```

或 stdio：

```javascript
{
  "idea_mcp": {
    "command": "node",
    "args": ["<gateway-stdio-client.js>", "--server", "idea_mcp", "--revision", "102"]
  }
}
```

4. `allowedTools` 按现有权限策略处理：
   - 如果当前插件已经有 MCP auto-approve 规则，迁移到 `mcp__<serverId>__*`。
   - 不因为接 Gateway 而扩大到 `bypassPermissions`。
5. `system init` 中 MCP server 失败状态应转成 Gateway health，而不是直接让坏真实 MCP 阻塞 query。

推荐优先 HTTP virtual endpoint，原因：

- Claude Agent SDK 文档明确支持 HTTP/SSE。
- Gateway 可以长驻，HTTP endpoint 不需要每个 server 再启动一个 stdio adapter 子进程。
- 健康检查、revision、schema 返回更容易统一。

回退方案：

- 如果 Claude SDK 对本地 HTTP MCP 行为不稳定，则使用 stdio adapter。

### 6.2 Codex SDK Binding

当前路径：

- `ai-bridge/services/codex/message-service.js`
- `ai-bridge/services/codex/codex-event-handler.js`
- `src/main/java/com/github/claudecodegui/provider/codex/CodexSDKBridge.java`

官方 SDK 注入点：

```javascript
const codex = new Codex({
  env: cliEnv,
  config: {
    mcp_servers: {
      idea_mcp: {
        command: "node",
        args: ["<gateway-stdio-client.js>", "--server", "idea_mcp", "--revision", "102"],
        startup_timeout_sec: 1
      }
    }
  }
})
```

改造思路：

1. 在 `sendMessage()` 构建 `codexOptions` 时合并 Gateway MCP config。
2. 如果已有 `serviceTier` 等逻辑写入了 `codexOptions.config`，必须 deep merge，不能覆盖。
3. `buildCodexThreadCacheSignature()` 必须加入：
   - `mcpGatewayEnabled`
   - `mcpGatewaySchemaRevision`
   - `mcpGatewayConfigHash`
4. schema revision 变化时，旧 `thread` cache 不再复用。
5. Codex SDK 是 CLI wrapper，仍可能启动 Codex CLI；但它只会加载 Gateway virtual MCP，不再直接初始化全量真实 MCP。
6. `mcp_tool_call` 事件处理不需要改变，因为工具名仍是 `mcp__<serverId>__<toolName>`。

关键风险：

- Codex SDK `config` 会 flatten 成 dotted `--config`，需要实测 nested `mcp_servers` 是否能稳定表达数组和对象。
- 如果 `config` override 与用户 `~/.codex/config.toml` 合并后无法删除真实 MCP，则需要额外隔离 `CODEX_HOME` 或临时 config profile。
- resume thread 时，如果历史上下文中的 tool set 与当前 schema revision 不一致，应优先新建 thread 或提示不可继续复用旧 thread。

优先级建议：

- P0：先用测试验证 `new Codex({ config: { mcp_servers: ... } })` 是否能让 Codex 只看到 Gateway MCP。
- P1：把 schema revision 加入 cache signature。
- P2：如发现用户全局真实 MCP 仍被加载，再引入 SDK 专用临时 `CODEX_HOME`。

### 6.3 OpenCode SDK Binding

当前路径：

- `src/main/java/com/github/claudecodegui/provider/opencode/OpenCodeDaemonCoordinator.java`
- `src/main/java/com/github/claudecodegui/provider/opencode/OpenCodeSDKBridge.java`
- `ai-bridge/services/opencode/message-service.js`
- `ai-bridge/channels/opencode-channel.js`

当前架构是：

```text
Java OpenCodeDaemonCoordinator -> opencode serve
Node message-service.js -> createOpencodeClient({ baseUrl })
```

因此 MCP Gateway 不能在每次 prompt 时注入，只能在 serve 启动配置中注入。

推荐启动配置：

```json
{
  "mcp": {
    "idea_mcp": {
      "type": "local",
      "command": ["node", "<gateway-stdio-client.js>", "--server", "idea_mcp", "--revision", "102"],
      "enabled": true,
      "timeout": 1000
    },
    "database": {
      "type": "remote",
      "url": "http://127.0.0.1:<port>/mcp/database",
      "enabled": true,
      "timeout": 1000
    }
  }
}
```

配置注入方式：

1. 如果继续由 Java 启动 `opencode serve`：
   - 启动进程时设置 `OPENCODE_CONFIG_CONTENT`。
   - inline config 只写 Gateway virtual MCP，不写真实 MCP。
   - 保留用户 provider/model/auth 等配置来源，让 OpenCode 自己按配置优先级合并。
2. 如果未来改为 Node `createOpencode({ config })` 启动：
   - 直接在 `createOpencode({ config })` 中传入 `mcp`。
   - Java 只管理 Node bridge，不直接管理 serve。

热更新策略：

- health revision 变化：不重启 `opencode serve`。
- schema revision 变化：优先重启 `opencode serve`，因为官方 SDK 文档只确认启动时 config 注入和 `config.get()`，没有确认运行时 patch config 可以热更新 MCP。
- 重启前如果当前有 in-flight session，延迟到当前 turn 完成；新 turn 使用新 serve。

关键风险：

- OpenCode MCP 工具名规则与 Claude/Codex 的 `mcp__server__tool` 不完全相同；官方文档说明 MCP tools 会作为工具自动可用，并提到 server name prefix。实现时必须通过实际事件和 `config.get()` 验证模型看到的 tool 名是否保留 server id。
- 当前 `opencode-channel.js` 的 `getMcpServerTools` 是 passthrough note，缺少直接列工具 API；Gateway 接入后，工具列表应以 Gateway catalog 为准。
- 如果用户依赖 OpenCode 的 remote MCP OAuth 自动流程，Gateway 代理真实 remote MCP 后 OAuth 行为要明确：第一版建议 Gateway 继续把真实 remote MCP 交给 OpenCode 直连或标记为 unsupported，避免代理 OAuth 状态导致授权丢失。第二版再做 Gateway OAuth token store。

## 7. SDK 与 CLI 归一化边界

可归一化部分：

| 能力 | SDK | CLI | 归一化方式 |
|---|---|---|---|
| MCP 配置采集 | 是 | 是 | Java 后端统一生成 `McpGatewayConfigSnapshot` |
| Gateway 生命周期 | 是 | 是 | Project-level `McpGatewayService` |
| 真实 MCP 连接 | 是 | 是 | Node Gateway per-server supervisor |
| 工具目录缓存 | 是 | 是 | Gateway `ToolCatalog` |
| 健康检查 | 是 | 是 | Gateway `HealthStore` + 后端下发状态 |
| 设置热更新 | 是 | 是 | settings mutation 后 refresh snapshot |
| 工具自动触发 | 是 | 是 | 保留真实 server id 和 tool schema |

不可完全归一化部分：

| 差异 | 原因 | 处理方式 |
|---|---|---|
| Claude SDK query options | Claude SDK 原生支持 `mcpServers` | Claude binding 单独实现 |
| Codex SDK config override | Codex SDK 是 CLI wrapper，通过 `config` 转 `--config` | Codex binding 单独实现并实测 |
| OpenCode SDK serve config | 插件当前连接已有 `opencode serve` | OpenCode binding 在 daemon coordinator 启动时注入 |
| SDK 会话缓存语义 | Codex thread cache、OpenCode serve daemon、Claude query/persistent query 不同 | cache signature 加 revision；OpenCode schema 变更重启 serve |

## 8. Revision 策略

需要区分两类 revision：

### 8.1 schema revision

表示工具集合或工具 schema 改变：

- MCP server 新增、删除、启用、停用。
- 某个 MCP server 的 command/url/env/header 改变。
- `tools/list` 返回的 tool name、description、input schema 改变。

处理：

- 新 turn 使用新 schema revision。
- Codex SDK thread cache signature 必须变化。
- OpenCode SDK serve 需要重启或确认可热更新后再 patch。
- Claude SDK 下一次 query options 直接注入新 revision。

### 8.2 health revision

表示健康状态变化，但工具 schema 未变：

- 某个真实 MCP 暂时 502。
- 某个 stdio process crash 后进入 BACKOFF。
- Gateway 下一次 refresh 成功。

处理：

- 不重启 SDK runtime。
- 不改变 tool list。
- UI 下发 health status。
- `tools/call` 命中失败 server 时返回可诊断 MCP error。

## 9. 设置页热更新

设置页动作包括：

- 新增 MCP。
- 删除 MCP。
- 更新 command/url/env/header。
- 启用/停用 MCP。

统一流程：

```text
Settings handler persists change
        |
        v
McpGatewayService.refreshConfig(projectPath)
        |
        v
Gateway apply snapshot
        |
        +-- new/deleted/schema changed -> schema revision++
        +-- only health changed -> health revision++
        |
        v
DownstreamEvent.MCP_GATEWAY_STATUS
```

SDK 生效规则：

- Claude SDK：下一次 query 生效。
- Codex SDK：下一次新 thread 生效；resume thread 如果 revision 不匹配则不复用 cached thread。
- OpenCode SDK：下一次 turn 前如 schema revision 不匹配，重启 `opencode serve` 后再发送。

不做：

- 不在前端判断 MCP 能力。
- 不让用户手动指定本次用哪个 MCP。
- 不因为单个 MCP 失败而全局禁用 Gateway。

## 10. 故障隔离与心跳

Gateway 每个真实 MCP server 单独维护状态机：

```text
DISABLED -> STARTING -> READY
STARTING -> BACKOFF
READY -> DEGRADED -> BACKOFF
BACKOFF -> STARTING
READY -> STOPPING -> DELETED
```

心跳策略：

- stdio MCP：周期性 `tools/list` 或轻量 ping；不支持 ping 时用 `tools/list` 带 timeout。
- HTTP/SSE MCP：周期性 `initialize`/session refresh 或 `tools/list`，按 MCP transport 能力选择。
- timeout 必须短于 SDK/CLI 首包体验目标，建议默认 1000-3000ms。
- BACKOFF 使用指数退避：1s、3s、10s、30s、60s，上限 60s。

失败策略：

- `tools/list` 对 provider 永远读缓存，不等待坏 server。
- `tools/call` 只等待目标 server。
- 目标 server 在 BACKOFF 且无可用连接时返回 MCP error，提示该 server 当前不可用。
- 健康状态通过后端事件下发 UI，但 UI 只展示，不决策。

## 11. 后续开发任务草案

以下任务不是 `2026-06-29-mcp-gateway-cli-runtime.md` 的一部分；不得在当前 CLI Gateway 版本中顺手实施。只有当 SDK Gateway 方案单独批准后，才可转写为新的 implementation plan。

### Task SDK-1：抽象 Gateway Binding 接口

目标：

- 在 Java/Node 边界定义 provider-neutral 的 SDK Gateway binding 输入输出。

建议文件：

- 新建 `src/main/java/com/github/claudecodegui/mcp/McpGatewayBindingSnapshot.java`
- 新建 `src/main/java/com/github/claudecodegui/mcp/McpGatewayRuntimeBinding.java`
- 新建 `ai-bridge/mcp-gateway/sdk-binding.js`

产物：

- `schemaRevision`
- `configHash`
- `virtualServers[]`
- `providerBindingPayload`

### Task SDK-2：Claude SDK Gateway Binding

目标：

- 替换 Claude SDK 发送路径中直接加载真实 MCP 的逻辑。
- `mcpServers` 来自 Gateway virtual servers。

修改文件：

- `ai-bridge/services/claude/message-sender.js`
- `ai-bridge/services/claude/persistent-query-service.js`
- `ai-bridge/services/claude/mcp-status/config-loader.js`

测试：

- `loadMcpServersConfigAsRecord()` legacy path 保留为 fallback。
- Gateway enabled 时 options 中 server id 不变。
- `allowedTools` 不扩大权限。

### Task SDK-3：Codex SDK Gateway Binding

目标：

- 在 `new Codex(codexOptions)` 前注入 `codexOptions.config.mcp_servers`。
- 把 Gateway revision 纳入 thread cache signature。

修改文件：

- `ai-bridge/services/codex/message-service.js`
- `ai-bridge/services/codex/message-service.test.js`

测试：

- `serviceTier` 写入 `codexOptions.config` 时，Gateway MCP deep merge 不覆盖它。
- `buildCodexThreadCacheSignature()` 对 schema revision 敏感。
- `mcp_tool_call` event mapping 不变。

### Task SDK-4：OpenCode SDK Gateway Binding

目标：

- `OpenCodeDaemonCoordinator` 启动 `opencode serve` 时注入 Gateway MCP config。
- schema revision 变化时重启 serve 或明确排队到下个 turn。

修改文件：

- `src/main/java/com/github/claudecodegui/provider/opencode/OpenCodeDaemonCoordinator.java`
- `src/main/java/com/github/claudecodegui/provider/opencode/OpenCodeSDKBridge.java`
- `ai-bridge/services/opencode/message-service.js`

测试：

- 启动命令/env 包含 `OPENCODE_CONFIG_CONTENT` 或等价临时 config。
- schema revision 变化触发 serve recycle。
- in-flight turn 不被中途强杀，除非用户 interrupt。

### Task SDK-5：统一健康事件与设置热更新

目标：

- 在未来 SDK Gateway 计划中复用 CLI Gateway 已建立的 status 下发。
- settings mutation 后，SDK binding 能消费 Gateway 最新 snapshot；CLI 行为仍以 CLI Runtime 计划为准。

修改文件：

- `src/main/java/com/github/claudecodegui/protocol/DownstreamEvent.java`
- `src/main/java/com/github/claudecodegui/handler/mcp/McpServerActionHandlers.java`
- `src/main/java/com/github/claudecodegui/handler/codex/CodexMcpServerActionHandlers.java`
- OpenCode MCP settings handler

测试：

- 3 provider 的 add/update/delete/toggle 都触发 `refreshConfig()`。
- 前端只展示状态，不参与 MCP 路由。

### Task SDK-6：3 provider x 2 mode 对称性测试

目标：

- 防止只改 Claude/Codex/OpenCode 其中一条路径。

建议测试：

- `McpGatewayRuntimeMatrixTest`
- `SdkMcpGatewayBindingTest`
- `CliMcpGatewayBindingTest`
- Node tests for `sdk-binding.js`

矩阵：

| Provider | SDK | CLI |
|---|---|---|
| Claude | Gateway virtual `mcpServers` | Gateway virtual CLI config |
| Codex | Gateway `config.mcp_servers` | Gateway CLI config / temporary `CODEX_HOME` |
| OpenCode | Gateway `OPENCODE_CONFIG_CONTENT` for serve | Gateway CLI config |

## 12. 后续分阶段发布建议

### Phase 0：只引入 Gateway 基础设施

- 不切任何 SDK 默认路径。
- Gateway 能独立加载真实 MCP、缓存工具、输出健康状态。

### Phase 1：CLI 已独立接入

- CLI one-shot 启动慢由 CLI Runtime 计划单独处理。
- SDK 仍走旧路径。

### Phase 2：Claude SDK 接入

- Claude SDK binding 风险最低。
- 如后续实施，再由新的 SDK implementation plan 定义独立 feature flag；当前 CLI Gateway 版本不新增 `mcpGateway.sdk.*`。

### Phase 3：Codex SDK 接入

- 先实测 `new Codex({ config: { mcp_servers } })`。
- 通过后再默认打开。
- 如发现全局真实 MCP 仍被加载，再做 SDK 专用 `CODEX_HOME` 隔离。

### Phase 4：OpenCode SDK 接入

- 先做 serve 启动注入。
- schema revision 变化先采用安全重启。
- 后续如官方确认运行时 config patch 可刷新 MCP，再改为热 patch。

### Phase 5：默认启用 SDK Gateway

默认启用条件：

- 3 provider SDK 手动 E2E 均通过。
- 设置页热更新稳定。
- 坏 MCP 不影响普通对话。
- 工具自动触发行为与旧全量加载一致。
- 支持 fallback 回 provider-native MCP。

## 13. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| 模型不再自动选择正确 MCP | 破坏无感触发 | 保留原 server id、tool name、description、input schema |
| Codex SDK config override 不能覆盖全局真实 MCP | 仍会慢 | 实测后必要时引入 SDK 临时 `CODEX_HOME` |
| OpenCode serve 不支持 MCP 热更新 | 设置变更不实时 | schema revision 变化时重启 serve；health revision 不重启 |
| Gateway cache 过旧 | 工具 schema 不一致 | schema revision + configHash + settings mutation refresh |
| 单 MCP hang | 普通对话或其他 MCP 卡住 | per-server timeout + BACKOFF + `tools/list` 只读缓存 |
| remote MCP OAuth 被 Gateway 代理破坏 | 授权失败 | 第一版保守处理 OAuth remote MCP；必要时保留 OpenCode native direct path |
| SDK/CLI 行为不对称 | 维护困难 | 3 provider x 2 mode source tests + E2E matrix |

## 14. 验证矩阵

| Provider | Mode | 验证点 |
|---|---|---|
| Claude | SDK | `query()` options 中只包含 Gateway virtual MCP；`mcp__idea_mcp__run_test` 可自动触发 |
| Claude | CLI | CLI 启动不等待真实 MCP；工具名不漂移 |
| Codex | SDK | `new Codex({ config })` 生效；thread cache 随 schema revision 变化 |
| Codex | CLI | 首条 stdout 不被真实 MCP 502 阻塞；`mcp_tool_call` 事件正常映射 |
| OpenCode | SDK | `opencode serve` 启动配置只含 Gateway MCP；schema 变化后新 turn 生效 |
| OpenCode | CLI | `opencode run` 只加载 Gateway MCP；坏 MCP 不影响健康 MCP |

手动场景：

1. 配置一个健康 `idea_mcp` 和一个返回 HTTP 502 的 MCP。
2. 分别用 Claude/Codex/OpenCode 的 SDK 和 CLI 发送普通对话。
3. 验证普通对话首包不被 502 MCP 拖慢。
4. 发送测试相关 prompt，验证模型自动调用 `idea_mcp`。
5. 在设置页禁用 `idea_mcp`，验证下一 turn 不再暴露该工具。
6. 重新启用 `idea_mcp`，验证下一 turn 自动恢复。
7. 删除坏 MCP，验证 Gateway supervisor 停止且无进程泄漏。

## 15. 最终建议

SDK 模式后续可以评估接入 MCP Gateway，但当前版本不做。若后续启动 SDK 归一化，应采用“统一 Gateway + provider-specific binding”的方式，而不是强行让三个 SDK 用同一种注入 API。

后续推荐形态：

- SDK 连接 Gateway virtual MCP，不直接连接真实 MCP；CLI 部分以 CLI Runtime 计划为准。
- Gateway 保留多 virtual server endpoint，保持工具名和无感触发体验。
- Claude SDK 第一批接入，Codex SDK 第二批接入，OpenCode SDK 第三批接入。
- Codex/OpenCode 接入前必须做官方 SDK 行为 smoke test，尤其是 Codex `config.mcp_servers` 覆盖和 OpenCode `OPENCODE_CONFIG_CONTENT` + serve 重启。
- 全量默认启用前保留 provider-native fallback，避免 SDK 行为变更导致用户无法使用原 MCP。
