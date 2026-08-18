# CLI 对话启动性能优化实施计划

> **状态**：Phase 1 已落地（2026-08-18，随 daemon-mode 批次在工作区，含 fallback 冷却/版本门禁/turnId 埋点补强）；Phase 0 部分完成（诊断测试与 gateway 分段耗时埋点已有，结构化字段与 p50/p95 基线矩阵未建）；Phase 2（MCP Gateway 解耦）未动工；Phase 4 未开始。
>
> **排查基线**：2026-08-17，Windows，Claude CLI 2.1.233
>
> **适用范围**：纯 CLI 调用路径；优先解决 Claude 同一会话多轮对话的启动等待，不改变认证方式、不直调模型 API、不修改前端业务职责。
>
> **关联设计**：[`claude-daemon-mode-design.md`](./claude-daemon-mode-design.md)
>
> **阶段编号注意**：本文档的 Phase 2（MCP Gateway 解耦）/ Phase 3（fallback 门禁）/ Phase 4（provider 对齐）与关联设计文档的 Phase 2（opencode serve）/ Phase 3（codex app-server）**编号错位**——两文档仅 Phase 1（claude 长驻）同义，其余编号互不对应，交叉引用时须以内容为准。

## 1. 文档目的

这份文档把“切换为纯 CLI 后，对话开始阶段明显变慢”的排查结果转换为可执行的工程计划，供后续拆任务、实现、测试和灰度使用。

本计划不直接修改产品代码，重点明确：

1. 哪些等待属于本地 CLI 冷启动，可以通过长驻进程消除；
2. 哪些等待属于 API/prompt/cache 或 MCP 外部服务，不能通过简单复用 CLI 进程消除；
3. 需要新增哪些抽象、状态和测试；
4. 每个阶段如何验收、如何回退，以及如何避免再次引入跨会话阻塞。

## 2. 结论摘要

### 2.1 当前用户可见现象

| UI 阶段 | 典型耗时 | 实际覆盖的工作 |
|---|---:|---|
| `正在连接` / `正在启动所选 AI 运行时` | 约 20～30 秒中的前置部分 | Java 创建 CLI/Node 进程、环境准备、MCP Gateway 配置准备、CLI 初始化、首个 stdout 事件等待 |
| `正在理解问题` / `正在读取提示词和相关上下文` | 约 10 秒 | `system.init` 之后的 prompt 上传、prompt cache 建立或命中、API 首次请求、MCP/工具上下文参与和模型首事件等待 |

UI 阶段名称不能直接作为性能边界。当前实现直到 Claude CLI 输出 `system.init` 后才进入后续阶段，因此“正在理解问题”可能仍包含网络和 CLI 协议初始化等待，并不只是本地读取提示词。

### 2.2 已确认的主要根因

commit `74a1e1d6f` 移除了原有 SDK bridge 和 daemon 基础设施。当前 `ClaudeCliSession` 采用 one-shot 模式：每一轮消息重新启动 Claude CLI，再通过 `--resume` 恢复历史会话。

每轮重复支付以下固定开销：

- Node/CLI 进程创建和运行时加载；
- 认证和环境初始化；
- SessionStart hook；
- MCP 配置生成和工具注册；
- `--resume` 会话恢复；
- 首个 stdout/`system.init` 事件等待。

已有实测显示：

| 场景 | 进程启动到 `init` | `init` 后静默期 | 判断 |
|---|---:|---:|---|
| one-shot 新会话 | 约 3.4 秒 | 约 7.2 秒 | 每轮承担完整冷启动 |
| one-shot `--resume` | 约 3.2 秒 | 约 5.3 秒 | `--resume` 没有消除进程级冷启动 |
| 长驻 `stream-json` 第二轮 | 约 0.2 秒 | 约 10.3 秒 | 本地冷启动明显下降，API/prompt 等待仍在 |

因此，第一优先级不是恢复“自研 daemon + 直调 API”，而是接入 Claude CLI 官方的长驻 `stream-json` 输入模式，并保留 one-shot 作为兼容和故障恢复路径。

### 2.3 MCP Gateway 是第二个独立瓶颈

当前调用链为：

```text
ClaudeCliSession.send()
  → buildGatewayConfig()
  → McpGatewayService.buildCliConfig()
  → ensureStarted()
  → refreshConfig()
  → applySnapshot()
  → Node 侧各 MCP server initialize()
  → listTools()
```

关键超时：

```text
REUSE_PROBE_TIMEOUT = 60s
COLD_START_TIMEOUT  = 10s
SNAPSHOT_TIMEOUT    = 60s
```

Node 侧会并行刷新多个 MCP server，但每个 server 仍要顺序执行 `initialize()` 和 `listTools()`，最终通过 `Promise.allSettled()` 等待全部结果。单个慢或异常的 MCP server 可能让整个快路径等待约 15～30 秒。

此外，`buildCliConfig()` 进入 `ensureStarted()` 后又会触发 `refreshConfig()`，而 `refreshConfig()` 内部可能再次调用 `ensureStarted()`。在 Gateway 启动中或状态异常时，这条重复路径会放大等待和竞态。

## 3. 目标与非目标

### 3.1 目标

1. 同一 tab/同一会话的第二条及后续消息不再每轮重新启动 Claude CLI。
2. 长驻进程按会话隔离，不允许一个会话的慢请求阻塞其他会话。
3. 同一会话内保持消息串行，避免 stdout 响应交错、消息丢失或状态错配。
4. Claude 长驻进程异常退出后，下一条消息可以自动退回 one-shot `--resume`，不丢失会话上下文。
5. MCP Gateway 的 ready、配置快照和工具刷新解耦，避免每轮同步等待全量 MCP server。
6. 建立分阶段耗时埋点，使“本地启动”“Gateway”“API/prompt/cache”“首 token”可以单独归因。
7. 通过 feature flag 控制新路径，支持按 provider、版本、项目或用户环境快速回退。
8. 为 Codex `app-server` 和 OpenCode `serve` 保留同等生命周期抽象，后续按 provider 对齐。

### 3.2 非目标

1. 不恢复或自研一个绕过 CLI 的 API daemon。
2. 不在 ai-bridge 中重写 Claude/Codex/OpenCode 的 agent loop。
3. 不改变用户现有 CLI 登录、订阅计费、`ANTHROPIC_BASE_URL` 或外部网关配置。
4. 不承诺消除 `system.init` 后由 API prompt 上传、cache 创建和模型推理造成的全部等待。
5. 不把 MCP server 的慢响应通过无限延长超时隐藏起来；慢服务必须可观测、可降级。
6. 不让前端自行推导 provider 能力、默认值或协议语义。

## 4. 架构约束

实施时必须遵守项目 `AGENTS.md`：

- Java 后端是状态权威，webview 只负责展示和输入转发。
- CLI/第三方能力使用 Adapter/Registry/Facade 方式扩展，避免新增 provider 分支散落在核心流程。
- Java 与 ai-bridge 之间保持 NDJSON 字符串契约，不泄漏 Node 类型。
- Claude、Codex、OpenCode 的 SDK/CLI 横切处理要按 provider × mode 矩阵核对：stdin 写入并关闭、stdout drain、环境注入、cwd 回退、abort、进程清理和状态回灌不得只覆盖一条路径。
- 会话之间绝不能共享全局串行队列。允许每个会话有独立串行队列，但不同会话必须并发。
- 新增下行事件使用 `DownstreamEvent` 枚举和统一事件出口；新增协议字段必须走现有 SSOT 生成链。

## 5. 总体方案

将当前 one-shot 路径改为“双路径发送器”：

```text
send(message)
  ├─ persistent session ready
  │    └─ 写入长驻 CLI stdin，复用 stdout 事件解析
  ├─ persistent session 可恢复/正在启动
  │    └─ 按会话串行等待或触发一次性恢复
  └─ persistent session 不可用/能力不兼容
       └─ one-shot --resume fallback
```

长驻进程由 Java 侧统一管理生命周期，CLI 仍是实际执行者：

```text
ClaudeCliSession
  → PersistentCliSessionRegistry
  → PersistentCliProcess
  → claude --input-format stream-json ...
  → stdout NDJSON parser
  → SessionCallbackAdapter
```

MCP Gateway 单独采用“ready 优先、快照异步刷新、旧配置可短暂复用”的策略：

```text
ensureStarted()       # 只保证 Gateway 进程可用，不同步等待全部工具
buildCliConfig()      # 读取当前可用快照，必要时返回 stale snapshot
refreshConfigAsync()  # 后台刷新并发布新版本
```

## 6. 分阶段实施计划

### Phase 0：建立可重复的耗时基线和埋点

**目的**：在改动运行模型前，把每一轮等待拆成可比较的时间段，避免把 API 静默期误判为 CLI 冷启动。

#### 6.1 埋点范围

在 Java 侧至少记录以下时间点，字段名以现有日志/指标规范为准：

```text
turn_start
cli_spawn_start
cli_spawn_end
stdin_write_start
stdin_write_end
gateway_ensure_start
gateway_ready
gateway_snapshot_start
gateway_snapshot_end
first_stdout_line
system_init
first_assistant_event
first_text_delta
turn_end
abort_start
abort_ack
process_exit
```

每条记录必须携带：

- `provider`；
- `mode`（SDK/CLI）；
- `conversationId` 或等价会话标识的脱敏值；
- `turnId`；
- CLI 版本指纹；
- Gateway snapshot 版本；
- 是否走 persistent、one-shot 或 fallback；
- MCP server 数量及失败/超时数量；
- exit code 和异常分类。

不要记录 prompt 原文、token、认证信息或用户文件内容。

#### 6.2 基线场景

至少测量以下场景，每个场景重复 10 次并记录 p50/p95：

| 场景 | 说明 |
|---|---|
| 首次 one-shot | 新会话，干净工作区 |
| one-shot `--resume` | 同一会话第二轮 |
| 同 tab 第二轮 | 计划中的 persistent 路径对照 |
| MCP Gateway ready | Gateway 已运行、快照未变化 |
| Gateway 冷启动 | Gateway 不存在或已退出 |
| 1 个慢 MCP server | 验证是否拖住全链路 |
| 两个 tab 并发 | 验证跨会话是否互相阻塞 |
| abort 后续聊 | 验证取消和恢复 |

#### 6.3 Phase 0 完成条件

- 可以从日志直接区分 CLI 本地启动耗时和 API/prompt 静默期；
- 能定位具体 MCP server 的 initialize/listTools 耗时；
- 同一场景重复运行的时间戳顺序稳定；
- 不在日志中泄漏用户 prompt、环境变量 token 或完整路径中的敏感信息。

### Phase 1：Claude 官方 `stream-json` 长驻会话

**目的**：消除同一会话每轮重复支付的 CLI/Node 冷启动。

#### 6.4 长驻进程抽象

新增一个 provider 无关的长驻进程抽象，建议职责如下：

```text
PersistentCliProcess
  - start()
  - writeTurn(inputLine)
  - interrupt()
  - closeGracefully()
  - forceKill()
  - isAlive()
  - state()
  - processMetadata()
```

抽象只管理进程、stdin/stdout/stderr、状态和生命周期，不解析 Claude 业务事件。Claude-specific 的输入行、control request 和事件映射放在 `ClaudePersistentSendPath` 或等价 Adapter 中。

建议状态：

```text
NEW → STARTING → READY → BUSY → READY
                    ├→ STOPPING → STOPPED
                    └→ FAILED → RECOVERING → READY/STOPPED
```

状态转换必须单向可追踪，不能通过多个线程直接修改共享状态。

#### 6.5 会话 Registry 和隔离规则

新增 `CliPersistentProcessRegistry`，以以下维度建立槽位：

```text
(provider, runtimeMode, workspace/cwd, conversation/session identity)
```

规则：

1. 默认一条逻辑会话对应一个长驻 CLI 进程；
2. 不同 tab、不同 conversation、不同 cwd 不共享进程；
3. 同一会话内使用 per-session serial executor，保证一轮完成后再发下一轮；
4. registry 只负责查找、创建、回收和故障摘除，不承载 provider 事件解析；
5. 进程上限和 idle 回收必须有明确常量，达到上限时优先回收最久未使用且空闲的槽位；
6. 关闭项目、tab 或插件时释放对应槽位，JVM 退出时执行最终清理。

禁止复刻旧 SDK 的全局 `commandQueue`。并发验收必须覆盖两个会话同时发送且互不阻塞。

#### 6.6 Claude stream-json 协议处理

Claude 路径需要：

1. 启动 CLI 时进入官方 `stream-json` 输入模式；
2. stdin 保持打开，按 NDJSON 写入每轮用户消息；
3. stdout 由单独 reader 持续 drain，逐行交给既有 `ClaudeCliStreamParser` 或其可复用部分；
4. 第一轮和后续轮都通过同一个会话回调适配器回灌 Java 状态；
5. 不用每轮 `--resume`，长驻进程内的 `session_id` 作为会话连续性的事实来源；
6. 在启动参数、CLI 版本或协议指纹不兼容时，直接标记为不可用并走 one-shot；
7. stderr 独立消费或合并后安全处理，不能因 stderr 管道满阻塞 stdout。

已验证的协议事实应写入实现测试：

- `{"request":{"subtype":"interrupt"}}` 可作为 turn 中断控制消息；
- 中断回执约 1ms，进程继续存活；
- 被中断轮以 `error_during_execution` 收尾；
- 同一进程可以继续处理下一条消息；
- `--resume` 与 stream-json 组合可用，必要时可用于重建后的历史续接。

#### 6.7 发送路径和回调绑定

每一轮必须生成不可复用的 `turnId`，回调绑定至少包含：

```text
conversationId + processSlotId + turnId
```

事件处理规则：

- `system.init` 只结束 CLI 初始化等待，不代表模型已经返回；
- `assistant`/text delta 等事件只投递给对应 turn；
- `result` 是该 turn 的终结信号，释放 per-session 串行锁；
- `control_response` 只匹配对应 abort 请求；
- 进程异常退出时，所有未完成 turn 必须收到统一失败结果，不能永久挂起。

#### 6.8 Claude Phase 1 文件计划

建议新增：

```text
src/main/java/com/github/claudecodegui/cli/common/CliPersistentProcess.java
src/main/java/com/github/claudecodegui/cli/common/CliPersistentProcessRegistry.java
src/main/java/com/github/claudecodegui/cli/claude/ClaudePersistentSendPath.java
src/test/java/com/github/claudecodegui/cli/common/CliPersistentProcessRegistryTest.java
src/test/java/com/github/claudecodegui/cli/claude/ClaudePersistentSendPathTest.java
```

建议修改：

```text
src/main/java/com/github/claudecodegui/cli/claude/ClaudeCliSession.java
src/main/java/com/github/claudecodegui/cli/common/CliConstants.java
src/main/java/com/github/claudecodegui/cli/common/CliProcessLifecycle.java
```

具体类名可根据当前代码结构调整，但职责边界不能合并回一个巨型 `ClaudeCliSession`。

#### 6.9 Phase 1 完成条件

- 同一会话第二条消息“消息写入 → `system.init`” p95 < 500ms；
- 同一会话不会因为每轮进程创建而重复执行 SessionStart hook；
- 两个会话并发发送时，慢会话不阻塞快会话；
- 同一会话快速连发两条时，第二条保持等待，不交错、不丢失；
- abort 回执 p95 < 100ms，进程不被无条件杀死；
- 长驻进程被外部 kill 后，下一条消息可通过 one-shot `--resume` 恢复；
- 项目关闭、tab 关闭和插件卸载后没有残留 CLI/Node 子进程。

### Phase 2：MCP Gateway 启动、快照和刷新解耦

**目的**：避免每轮等待 Gateway 全量初始化和所有 MCP server 的工具枚举，把 MCP 慢服务从主发送路径隔离出来。

#### 6.10 拆分 Gateway 生命周期操作

将当前可能互相嵌套的流程拆为三个明确操作：

```text
ensureStarted()
  只负责 Gateway 进程存在、IPC 可连接、健康状态可判定

getCurrentSnapshot()
  读取最近一次成功快照，可标记 fresh/stale/empty

refreshSnapshotAsync()
  后台刷新各 MCP server，完成后原子发布新 snapshot
```

`buildCliConfig()` 的默认行为：

1. Gateway ready 且有可用 snapshot：立即返回当前 snapshot；
2. snapshot stale：允许在有限 TTL 内 stale-while-revalidate，同时后台刷新；
3. 首次没有 snapshot：只在确实需要 MCP 配置时执行一次有界初始化；
4. Gateway 进程正在启动：等待单个共享启动 future，不重复 `ensureStarted()`；
5. 启动或刷新失败：返回可诊断的降级状态，不能无限重试或永久阻塞发送。

#### 6.11 MCP server 刷新策略

Node `ipc-server.js` 和 transport 层需要配合：

- 每个 server 的 `initialize()` 和 `listTools()` 使用独立 deadline；
- 记录 server 名称、阶段、耗时、超时和错误分类；
- `Promise.allSettled()` 的结果要产生部分成功快照，而不是只有“全部成功/全部失败”；
- 慢 server 不应阻塞已经可用的其他 server 配置；
- 对短时间内重复刷新进行合并或去重；
- snapshot 使用版本号或生成时间，Java 侧只接受新版本；
- transport 的默认请求超时必须与上层总超时形成明确预算，不能多层叠加成不可解释的 60 秒等待。

#### 6.12 MCP Gateway 文件计划

重点检查和修改：

```text
src/main/java/com/github/claudecodegui/mcp/McpGatewayService.java
ai-bridge/mcp-gateway/ipc-server.js
ai-bridge/mcp-gateway/server-supervisor.js
ai-bridge/mcp-gateway/transport/http-client.js
ai-bridge/mcp-gateway/transport/stdio-client.js
```

建议新增或补充测试：

```text
src/test/java/com/github/claudecodegui/mcp/McpGatewayServiceTest.java
ai-bridge/test/mcp-gateway/ipc-server-snapshot-timing.test.mjs
ai-bridge/test/mcp-gateway/server-supervisor-refresh.test.mjs
```

#### 6.13 Phase 2 完成条件

- Gateway 已 ready 且 snapshot 可复用时，`buildCliConfig()` 不再同步等待全量 server refresh；
- 单个 MCP server 超时不会拖住其他已成功 server 的配置；
- 第一次 Gateway 冷启动仍有明确上限和日志，不会无限等待；
- 重复调用不会触发嵌套 `ensureStarted()`/`refreshConfig()`；
- 能从指标看出每个 MCP server 对总耗时的贡献；
- Claude 长驻第二轮不会因为 MCP snapshot 刷新重新支付全部初始化成本。

### Phase 3：one-shot fallback、故障恢复和兼容门禁

**目的**：让新路径在 CLI 版本、Windows 进程行为、MCP 异常和外部 kill 等情况下安全降级。

#### 6.14 Fallback 触发条件

以下情况直接或经过一次有限重试后退回 one-shot：

- CLI 版本不支持目标 stream-json 参数或协议指纹不匹配；
- 长驻进程启动超时、stdin 写入失败或 stdout reader 退出；
- 收到无法归属到当前 turn 的协议消息；
- 进程异常退出且未完成 turn；
- persistent 槽位达到上限且没有可回收空闲槽位；
- Windows `.cmd` 垫片或环境差异导致长驻 stdin 不稳定。

Fallback 不得悄悄吞掉原因，必须记录分类字段，并在必要时向前端回灌可读的运行状态。

#### 6.15 恢复流程

```text
persistent process failed
  → mark slot FAILED
  → fail current turn deterministically
  → remove slot from registry
  → next send starts one-shot --resume
  → one-shot success: optionally schedule persistent rebuild
  → rebuild failure: keep one-shot mode until cooldown expires
```

要求：

- 同一个坏槽位不能在每条消息上无限重启；
- 采用指数退避或固定冷却时间，并设置最大重试次数；
- fallback 期间仍保留原会话 ID/历史恢复能力；
- 进程清理必须覆盖 stdin/stdout/stderr、子进程树和 registry 引用；
- abort 时优先发送 provider 级中断，只有无法确认进程状态时才 force kill。

#### 6.16 Feature flag 和版本门禁

建议使用现有 FeatureFlags/行为菜单的三层门禁方式：

1. 默认开启 Claude persistent CLI；
2. JVM `-D` 开关可以完全关闭并恢复 one-shot；
3. CLI compatibility manifest 指纹校验失败时自动按会话降级；
4. 保留日志字段显示最终选择了 persistent、one-shot 还是 fallback；
5. 灰度期间支持仅对指定 provider/runtime 或测试环境开启。

开关名称应复用现有常量和配置 SSOT，不在 webview 另写业务默认值。

### Phase 4：Codex/OpenCode 对齐

**目的**：将长驻进程生命周期抽象复用到其他 provider，但不为了“对称”强行抹平其协议差异。

建议顺序：

1. Codex：评估并接入官方 `app-server` 生命周期、请求/响应关联和取消；
2. OpenCode：评估并接入 `serve` 的惰性 daemon、60 秒冷却和会话路由；
3. 统一 registry、进程清理、idle 回收、指标和 fallback；
4. 按 provider 分别实现 Adapter，不在公共分派器中增加 provider 字符串分支。

必须保留的架构差异：Claude 的 `stream-json`、Codex 的 `app-server` 和 OpenCode 的 `serve` 不是同一协议，不要求请求格式完全相同；要求的是生命周期、隔离、取消、清理、超时和可观测性等横切能力完整覆盖。

## 7. 状态机和并发设计

### 7.1 会话级状态

建议把“会话可用性”和“当前 turn 状态”分开：

```text
SessionRuntimeState:
  persistentState = absent | starting | ready | busy | failed | cooling_down
  activeTurn = none | turnId
  fallbackMode = disabled | available | in_use
  lastFailure = category + timestamp
```

同一会话：

- `activeTurn != none` 时，后续消息进入该会话队列；
- `result`、明确失败或 abort 收尾后释放队列；
- 队列中的消息不能使用已经失效的 process slot；
- process slot 失败时，队列后续消息可切换到 fallback，但不能同时启动两个 worker 处理同一会话。

### 7.2 进程级资源边界

每个 persistent process 必须拥有独立的：

- `Process` 句柄和子进程树清理器；
- stdin writer；
- stdout reader；
- stderr reader；
- 当前 `turnId`；
- 生命周期 future；
- 最后使用时间和 CLI 版本指纹。

禁止把多个会话的 stdin writer、stdout reader 或 abort future 放在共享静态字段中。

### 7.3 关闭顺序

关闭项目/窗口时按以下顺序：

1. 禁止新 turn 进入 registry；
2. 对 busy turn 发送 provider 级 abort 或等待有限 grace period；
3. 关闭 stdin，等待 CLI 自己退出；
4. 超时后清理整个子进程树；
5. 停止 stdout/stderr reader；
6. 从 registry 移除 slot；
7. 记录清理结果和残留检测结果。

## 8. 测试计划

### 8.1 Java 单元测试

覆盖：

- registry 按会话 key 隔离；
- 重复创建同一 key 不产生两个进程；
- 不同 key 可以并发；
- per-session 串行队列保持顺序；
- process state 的合法/非法转换；
- stdout、stderr 持续 drain；
- stdin 写入失败和进程退出能唤醒等待中的 turn；
- abort 只影响当前 turn；
- idle 回收、进程上限和关闭清理；
- CLI 指纹不兼容时走 fallback；
- fallback 恢复后不会无限重启。

建议测试类：

```text
CliPersistentProcessRegistryTest
ClaudePersistentSendPathTest
CliProcessLifecycleTest
```

### 8.2 MCP Gateway Node 测试

覆盖：

- `ensureStarted()` 与 refresh 调用合并；
- ready 状态下读取 snapshot 不触发同步全量刷新；
- 单个 server 超时产生部分成功 snapshot；
- `initialize()`/`listTools()` 阶段耗时和错误被记录；
- 重复 refresh 被去重；
- stale snapshot 可以短暂复用并在后台更新；
- Gateway 退出后能重新建立 IPC；
- refresh 超时不会阻塞主 CLI 请求无限时长。

### 8.3 集成和手工性能测试

使用固定工作区、固定 CLI 版本、固定 provider 配置，分别测试：

1. 新会话第一轮；
2. 同会话第二轮；
3. 同会话连续 5 轮；
4. 两会话并发各 3 轮；
5. 中途 abort；
6. 外部 kill persistent CLI；
7. Gateway 冷启动；
8. 一个 MCP server 延迟/不可用；
9. 项目关闭后进程检查。

每次记录：

```text
spawn_to_init
write_to_init
init_to_first_assistant
init_to_first_text
turn_total
mcp_ensure
mcp_snapshot
mcp_server_each
fallback_count
orphan_process_count
```

### 8.4 现有诊断测试

已经完成的诊断测试可作为 Phase 0 基线参考：

```text
src/test/java/com/github/claudecodegui/cli/CliStartupTimingAnalysisTest.java
ai-bridge/test/mcp-gateway/ipc-server-snapshot-timing.test.mjs
```

基线结果：Java 诊断测试 16 项通过；Node MCP snapshot timing 测试 2 项通过。后续实现只运行与改动直接相关的定向测试，不默认运行全量测试套件。

## 9. 验收指标

### 9.1 用户体验指标

| 指标 | 当前基线 | Phase 1 目标 | 备注 |
|---|---:|---:|---|
| 同会话第二轮消息写入→`system.init` | 约 3.2 秒 | p95 < 500ms | 只衡量本地 CLI 初始化段 |
| 长驻进程 abort 回执 | 未统一 | p95 < 100ms | provider control response |
| 两会话并发互相阻塞 | 存在风险 | 0 次 | 必须有自动化测试 |
| 进程残留 | 未统一 | 0 个 | 包括 Windows 子进程树 |
| fallback 后会话续接 | 需手工验证 | 100% 成功 | 在受支持 CLI 版本范围内 |

### 9.2 诊断指标

- `spawn_to_init` 与 `init_to_first_text` 分开统计；
- Gateway ready、snapshot fresh/stale、MCP server timeout 可独立统计；
- persistent 命中率、fallback 率、进程异常退出率和重建成功率可查询；
- 日志包含 `provider/mode/turnId/fallbackReason`，但不包含 prompt 和认证敏感信息。

### 9.3 不能归因给本方案的等待

如果 Phase 1 达到“写入→`system.init` < 500ms”，但 `init→first_text` 仍为 5～10 秒，应将其归类为 API/prompt/cache 或模型响应阶段，另立 prompt 瘦身、缓存命中和上下文裁剪计划，不继续在 CLI 进程生命周期上堆叠复杂度。

## 10. 风险、回退与发布策略

| 风险 | 影响 | 预防 | 回退 |
|---|---|---|---|
| CLI 版本改变 stream-json 协议 | 长驻不可用或事件错配 | 版本指纹/兼容 manifest、协议测试 | 自动 one-shot |
| Windows `.cmd` stdin 长期保持异常 | 进程挂起或无法写入 | stdout/stderr drain、子进程树清理、超时 | 当前 turn fallback，后续冷却 |
| 会话隔离实现错误 | 串话、响应错配 | `conversationId + turnId` 绑定、并发测试 | 关闭 persistent flag |
| MCP server 慢/不可用 | 配置等待增加 | 部分成功 snapshot、单 server deadline | stale/empty snapshot 或跳过 MCP |
| 长驻进程占用资源 | 内存/句柄增长 | 槽位上限、idle 回收、项目关闭清理 | 达上限后 one-shot |
| abort 只杀本地进程 | provider 端残留 turn | 优先 provider control request | 无法确认时 force kill 并标记失败 |
| 旧 SDK 行为遗留全局队列 | 跨会话阻塞 | 禁止共享 queue，代码审查和并发测试 | 回滚新增 registry 实现 |

发布建议：

1. 先以默认关闭或仅测试环境开启 Phase 1；
2. 使用耗时埋点验证 persistent 命中、fallback 和残留进程；
3. 小范围开启 Claude 长驻；
4. 确认稳定后扩大范围；
5. MCP Gateway 异步刷新独立灰度，避免两个变量同时上线无法归因；
6. 发生协议错误、残留进程或会话错配时，优先关闭 persistent flag，而不是回滚所有 CLI 改动。

## 11. 建议的实施顺序和提交拆分

每个提交只做一种性质的改动，并保证可单独编译/测试：

1. `test(cli): add startup timing baseline coverage`  
   只补基线和埋点测试，不改变运行行为。
2. `refactor(cli): add persistent process lifecycle abstraction`  
   只新增通用进程句柄、registry、生命周期测试。
3. `feat(claude): reuse stream-json process per conversation`  
   接入 Claude 长驻发送路径和 per-session 串行。
4. `fix(claude): fall back to one-shot after persistent process failure`  
   增加故障恢复、冷却和版本门禁。
5. `refactor(mcp): decouple gateway readiness from snapshot refresh`  
   拆分 Gateway ready/snapshot/async refresh。
6. `test(mcp): cover partial snapshot and refresh timeout behavior`  
   只补 MCP Gateway 的 Node/Java 定向测试。
7. `feat(provider): align persistent lifecycle for codex and opencode`  
   每个 provider 的协议 Adapter 可继续拆成独立提交。

本次计划文档本身不要求创建 Git commit。

## 12. 开发前检查清单

### Phase 0

- [ ] 确认当前工作区已有用户修改不被覆盖。
- [x] 确认日志字段不包含 prompt、token 和敏感路径。（埋点仅含耗时/turnId/provider/reason/fingerprint；fingerprint 含 cwd 与 mcp 路径，属插件自身配置路径，非敏感）
- [x] 确认基线可以区分 `spawn_to_init`、Gateway 和 API 静默期。（`[McpGatewayPerf]` ensure/refresh 分段 + `[CliTurnPerf]` system_init/first_assistant_event/first_text_delta 轮级三时间点 + `[CliPathDecision]` path/reason；p50/p95 基线矩阵仍待手工/集成测量）

### Phase 1

- [x] 长驻进程抽象不包含 Claude 业务解析。（interrupt 协议行已下放 `ClaudePersistentSendPath`，经 `CliProcessSpec.interruptLineSupplier` 注入）
- [ ] registry key 能区分 provider、cwd 和 conversation。（实际 key 为 `(tabId, provider)`，cwd 在指纹、conversation 由 tabId 近似——刻意简化，见设计文档 §4.4）
- [x] 同会话串行、跨会话并发已用测试证明。（`CliSessionManagerConcurrencyTest`）
- [x] stdin 保持打开但在关闭流程中明确关闭。
- [x] stdout/stderr 均持续 drain。（stderr 合并进 stdout 单 reader，JSON 容错丢弃噪声行）
- [x] 每一轮有唯一 `turnId`，事件不会串到其他 turn。
- [x] abort 优先走 provider control request。
- [x] 项目关闭和异常退出都清理子进程树。

### Phase 2

- [ ] `ensureStarted()` 不再隐式同步触发全量 refresh。
- [ ] snapshot 有 fresh/stale/empty 明确语义。
- [ ] MCP server 有独立 deadline 和错误分类。
- [ ] 部分成功 snapshot 可以被消费。
- [ ] 重复 refresh 已合并或去重。

### Phase 3/4

- [x] fallback 有原因、有次数、有冷却，不无限重启。（`[CliPathDecision]` path/reason 日志 + 连续失败 3 次进入 60s 冷却；注意：消息已递交 CLI 后的轮失败一律报错不重发，防 transcript 交错——对 §6.14 字面条款的自觉取舍）
- [x] CLI 版本不兼容自动降级。（`trySendPersistent` 接入 `CliCompatibilityService` 版本门禁，reason=version_incompatible）
- [ ] Claude/Codex/OpenCode 的 stdin、abort、cwd、env、清理逻辑已逐格核对。
- [x] 新协议事件使用枚举/SSOT，不新增散落字符串字面量。（`SUBTYPE_API_RETRY` 入 `CliConstants`；轮外协议行判定复用 `NORMAL_STREAM_EVENT_PREFIXES`）
- [ ] 前端只展示后端下发的阶段和能力结果。

**§6.14「无法归属的协议消息」保守落地说明**：轮外协议事件（result/assistant/system 类在无活跃轮时到达）已可观测化（WARN + 每进程限流 5 条），但**不做自动降级**——中断收尾后的迟到行属正常拖尾，自动降级的误伤率高于收益；持续出现且伴随轮异常时人工据此判断版本回退。**§6.16-5 灰度机制不做**：远端灰度配置超出插件「简易配置」定位，`-D cliPersistent.claude.enabled` 子开关已覆盖按 provider 关停。

## 13. 与既有设计文档的关系

`docs/feat/claude-daemon-mode-design.md` 是 Claude 长驻模式的详细设计和协议验证记录，已经包含长驻方案、进程隔离、验收口径和风险结论。

本文档不是替代它，而是把问题排查结果扩展成跨阶段执行计划：

- 既有设计文档回答“长驻模式应该如何设计”；
- 本文档回答“先做什么、改哪些边界、如何拆提交、如何测试、如何灰度和回滚”；
- MCP Gateway 的异步快照、基线埋点和 Phase 3 fallback 是本文档重点补充内容。

实施时应先以本文档拆任务，再回到既有设计文档核对 Claude stream-json 的具体协议细节。

## 14. 实施状态与后续待办

> 本节为 2026-08-18 落地后的剩余项索引，供后续逐项完成。已完成项的勾选状态见 §12，此处只列**未完成**与**已决不做**。

### 14.1 已完成概要

- **Phase 1 主体 + 补强**：长驻进程层、双路径发送、interrupt 下放、LRU 逐出、重建冷却、turnId、版本门禁、已递交不重发、gateway/轮级埋点。提交 `879e16eb`。
- **Phase 0 埋点**：`[McpGatewayPerf]` / `[CliTurnPerf]` / `[CliPathDecision]` / 诊断测试。提交 `879e16eb` + `7405de79`。
- **相关修复**：fable 角色映射（`a05fda0f`）、api_retry 529 提示（`7405de79`）。
- 完整提交序列：`a05fda0f..c9e5825e`（14 个主题提交），工作区已清空。

### 14.2 待完成清单

#### Phase 0：基线矩阵（前置已就绪）

| 项 | 内容 | 前置 | 建议做法 | 验收 |
|---|---|---|---|---|
| §6.2 场景矩阵 | 8 场景 × 10 次 × p50/p95 | 无（埋点已就位） | 写驱动脚本（`.ps1`/`.mjs`）发 10 轮、grep `idea.log` 的 `[CliTurnPerf]`/`[McpGatewayPerf]`/`[CliPathDecision]` 行计算分位数 | 八场景均有 p50/p95，且能区分 `spawn_to_init` / gateway / API 静默期 |

#### §9.1 量化验收（依赖 §6.2 数据）

| 项 | 指标 | 验收口径 |
|---|---|---|
| 次轮 write→init | p95 < 500ms | 同 tab 第二条消息的 `[CliTurnPerf] system_init: sinceTurnStartMs` |
| abort 回执 | p95 < 100ms | `[TabPerf] interrupt (persistent) returned in Xms` |
| fallback 续接 | 100% 成功 | 指纹漂移/进程崩溃后 `--resume` 续接无丢上下文 |

> 沙箱重建后用户侧三项实测（非代码）：同 tab 第二条（验证次轮收益）、切 fable（命令行应变 `--model glm-5.2[1M]`）、529 场景的重试提示。

#### Phase 2：MCP Gateway 解耦（数据驱动缓做）

**前置**：先用 §0 埋点采真实数据确认 gateway 是否构成瓶颈——稳态已实测 `buildCliConfig totalMs≈4ms`、冷加载 4.1s 已被预热线程移出发送路径。**若数据证明需做**，再按 §6.10–6.13 落地：

- [ ] 拆 `ensureStarted()` / `getCurrentSnapshot()` / `refreshSnapshotAsync()` 三操作
- [ ] snapshot fresh/stale/empty 语义 + 版本号 + stale-while-revalidate
- [ ] per-server `initialize/listTools` 独立 deadline + 耗时/错误分类
- [ ] `Promise.allSettled` 产生部分成功快照、增量发布（不阻塞已可用 server）
- [ ] 重复 refresh 去重合并（当前 `refreshing` 标志返回陈旧值，改为 join 在飞 promise）

> 重点文件见 §6.12；测试 `server-supervisor-refresh.test.mjs` 待补。

#### Phase 3：补尾

- [ ] 轮外协议事件**自动降级**：当前仅可观测（WARN 限流，§6.14 取舍）。若日志频繁出现轮外 `result/assistant` 行且伴随轮异常，再评估自动降级触发。
- [ ] Windows `.cmd` stdin 不稳定显式识别：当前靠杀树兜底。若 `.cmd` 下出现写入卡死，补 stdin 写入超时探测。
- [x] ~~§6.16-5 灰度机制~~（已决不做，见 §12 说明）

#### Phase 1：测试补尾

- [ ] §8.1 的 spawn 成功路径、状态转换、idle 回收单测：需可跨平台 spawn 的回 echo 程序或集成测试框架。当前靠并发隔离（`CliSessionManagerConcurrencyTest`）+ 失败契约（`CliPersistentProcessRegistryTest`）兜底。

#### Phase 4：provider 对齐（独立批次）

- [ ] Codex `app-server` 长驻对齐（设计文档 Phase 3）
- [ ] OpenCode `serve` 长驻对齐（设计文档 Phase 2，复用 SDK 时代契约）

**前置**：Phase 1 数据稳定后评估优先级——codex 检测器预热后首轮 339ms 紧迫性最低；opencode serve 的 HTTP+SSE 多会话是本职，复用既有契约路径。

### 14.3 已决不做（避免后人重新评估）

| 项 | 理由 |
|---|---|
| §6.16-5 远端灰度配置 | 超出插件「简易配置」定位；`-D cliPersistent.claude.enabled` 子开关已覆盖按 provider 关停 |
| registry key 四维 / 六态状态机 | 刻意简化为 `(tabId, provider)` + IDLE/STREAMING/DEAD，设计文档 §4.2/§4.4 背书；SDK 路径已移除，runtimeMode 维度不存在 |
| 自研 daemon + 直调 API | 认证断崖/能力断崖/会话体系断裂，设计文档附录 A 已否决 |

