# 插件生命周期、Provider、MCP Gateway 与对话链路整改清单

> 审计日期：2026-08-28
> 文档状态：按阶段逐项整改中
> 适用范围：Java 插件后端、React/JCEF Webview、`ai-bridge` Node 进程、全部 Provider CLI/Channel 路径
> 审计性质：源码静态审计；整改记录持续追加，定向测试结果以各条目记录为准

## 1. 使用说明

- 使用 Markdown 复选框跟踪整改进度：`[ ]` 待处理、`[x]` 已完成。
- 每完成一项，应在对应条目下补充：修改文件、定向测试命令、测试结果、必要的有意差异说明。
- 优先按 **P1 → P2 → P3** 顺序处理；同一优先级建议按本文“推荐实施顺序”执行。
- 验证只运行与改动直接相关的测试，禁止默认执行全量测试，也禁止使用 `stash → 全量测试 → pop` 做基线对比。
- 涉及 Provider 横切能力时，必须维护“处理项 × 8 Provider”矩阵，不能只修单一 Provider 后结束。
- 涉及前后端契约时，以 Java 协议枚举和后端业务模型为 SSOT，前端只渲染后端下发的实际结论。

## 2. 审计结论基线

### 2.1 总体判断

- [x] 暂未发现正常路径下必然发生的永久 Java 线程泄漏。
- [x] 暂未发现正常路径下必然发生的永久 Node/CLI 子进程泄漏。
- [x] 暂未发现明显的无界 Gateway catalog、HTTP body 或 persistent process registry 增长路径。
- [ ] 尚不能认定插件“完全无泄漏、全部 Provider 完全对称、可无风险发布”。
- [ ] 发布前至少应完成本文所有 P1 项，并执行其对应定向测试。

### 2.2 当前已存在的主要防线

以下内容用于防止后续整改误删已有保护：

- [x] `NodeService.dispose()` 会触发 `ProcessManager.cleanupAllProcesses()`。
- [x] `ProcessManager` 已具备 channel/runtime 账本、注册前取消窗口、Windows 进程树终止和 stale channel sweeper。
- [x] `CliPersistentProcessRegistry` 已具备容量上限、LRU/空闲回收、dead process 清理、generation/epoch、防重建风暴和 one-shot 降级。
- [x] `CliSessionManager` 已用有 TTL/容量上限的 disposed-tab tombstone 防止关闭后的迟到 send 复活进程。
- [x] `ClaudeChatWindow.dispose()` 已覆盖 Alarm、tracker、coalescer、callback、watchdog、session、JCEF bridge/browser 等主要资源。
- [x] `SessionCallbackAdapter` 已有 deactivate、stream-end fallback Alarm、turn token 与迟到 `stream_end` 防护。
- [x] Grok run-once 会话会关闭自有 `ScheduledExecutorService`。
- [x] MCP Gateway Java 进程句柄会 drain stdout/stderr，并在主动停止前移除退出回调。
- [x] Node Gateway 的 revision 快照和 HTTP body 已设上限，snapshot 更新会停止被替换或删除的 supervisor。

## 3. P1：发布前优先整改

### P1-01 项目关闭与异步预热/Gateway 自愈竞态

**涉及文件候选：**

- `src/main/java/com/github/claudecodegui/startup/BridgePreloader.java`
- `src/main/java/com/github/claudecodegui/ui/WebviewInitializer.java`
- `src/main/java/com/github/claudecodegui/mcp/McpGatewayService.java`

**问题：**预热任务提交到 pooled/common executor 后没有明确绑定 Project Disposable，也没有保存统一的可取消句柄。项目可能在最长约 60 秒的 Gateway snapshot 等待中被关闭；迟到的 refresh/self-heal 仍可能持有 Project，甚至再次进入 `ensureStarted()`。

**代办：**

- [x] 给 `McpGatewayService` 增加不可逆的 disposed 状态。
- [x] 在 `refreshConfig`、`buildCliConfig`、`ensureStarted`、`reloadGateway`、`statusJson` 和 self-heal 入口统一 fail-fast。
- [x] 异步任务在提交前、实际执行时、昂贵阶段前后、获得锁后分别检查 Project/Service 生命周期。
- [x] 保存预热 Future/Promise 句柄，并绑定 Project Disposable；dispose 时取消尚未执行或仍在等待的任务。
- [x] 为 self-heal 增加 generation/process-handle 身份校验，防止旧进程的迟到回调重建新进程。
- [x] dispose 后禁止任何路径重新设置 `processHandle` 或 `bridgeClient`。
- [x] 增加“预热进行中关闭项目”的并发测试。
- [x] 增加“旧进程退出回调晚于新进程创建”的 generation 测试。

**整改记录（2026-08-29）：**

- 修改文件：`McpGatewayService.java`、`BridgePreloader.java`、`WebviewInitializer.java`，以及对应生命周期回归测试。
- `McpGatewayService` 使用不可逆 disposed 闸门、资源发布锁、process generation/handle 双重身份校验，并保存、取消 self-heal Future；用户触发的 `stopGateway()` 仍保持可恢复语义。
- 两处预热均保存 Future，在 Project/Initializer dispose 时取消；`BridgePreloader` 使用 `Disposer.tryRegister` 避免向已销毁 Project 注册子 Disposable 的竞态。
- Provider 对称性：Claude、Codex、OpenCode、Grok、Kimi、Pi、OMP、DSH 共用同一 `McpGatewayService` 门面生命周期路径，本项无 Provider 特例或有意差异。
- 定向验证：`McpGatewayServiceTest`、`McpGatewayLifecycleTest`、`McpGatewayProcessHandleTest`、`BridgePreloaderLifecycleTest`、`WebviewInitializerTest` 通过；`compileJava` 通过。

**完成标准：**

- 项目关闭后不会新建 Gateway/CLI 进程。
- 所有未完成预热任务可在可控时间内退出，不再持有 Project 强引用。
- self-heal 只作用于当前 generation 的异常退出。

---

### P1-02 MCP Gateway 全局锁导致首条发送长时间等待

**涉及文件候选：**

- `src/main/java/com/github/claudecodegui/mcp/McpGatewayService.java`
- `src/main/java/com/github/claudecodegui/mcp/McpGatewayBridgeClient.java`

**问题：**Gateway refresh/build/status/reload/stop 共用单锁，首次 snapshot 加载可能在锁内等待约 60 秒。预热未完成或某个 MCP server 很慢时，首条消息可能被阻塞。

**代办：**

- [x] 将 Gateway 初始化/刷新改为 single-flight Future，避免重复 cold start。
- [x] 将慢速 MCP catalog 加载移出覆盖整个 Service 的同步锁。
- [x] 为发送路径设置短且有界的等待预算。
- [x] 超过发送预算时立即使用 direct MCP 配置降级，后台预热继续进行。
- [x] 区分 `process-starting`、`ipc-ready`、`catalog-loading`、`ready`、`degraded-direct`、`failed` 状态。
- [x] 确保 direct 降级不覆盖或破坏后台成功完成的新 revision。
- [x] 增加慢 MCP server、失联 server、并发首发、refresh 与 stop 竞争测试。

**整改记录（2026-08-30）：**

- 修改文件：`McpGatewayService.java`、`McpGatewayLifecycleState.java`、`McpGatewayServiceTest.java`、`McpGatewayLifecycleTest.java`。
- 设计说明：使用项目级 `CompletableFuture` single-flight 合并启动/刷新；Gateway 启动、catalog collect、`postSnapshot`、status HTTP 和 self-heal 等慢操作均移到 `lock` 外；发送路径最多等待 2 秒，超时返回 `McpGatewayCliConfig.disabled(...)` 走 provider 原生 MCP 配置，后台 flight 不取消并可在后续 turn 恢复为 `ready`。
- 版本/快照保护：candidate snapshot 仅在 `postSnapshot` 成功且资源身份、operation generation 仍匹配时提交 revision；stop/dispose 会使旧 operation 失效，并同时取消 public `CompletableFuture` 与底层 executor `Future`，防止 stale refresh 覆盖新资源或 revision。
- Provider 对称性检查：8 Provider 共用同一 Java Gateway 生命周期门面；Gateway 注入当前仅由 Claude、Codex、OpenCode 的 `McpGatewayConfigWriter` 支持，Grok、Kimi、Pi、OMP、DSH 保持 direct/无注入路径，这是由实际配置适配范围决定的有意差异，不是遗漏。
- 定向验证：`gradlew.bat test --tests "com.github.claudecodegui.mcp.McpGatewayServiceTest" --tests "com.github.claudecodegui.mcp.McpGatewayLifecycleTest" --no-daemon` 通过（13 tests）；`gradlew.bat compileJava --no-daemon` 已通过。

**完成标准：**

- 单个 MCP server 卡住时，首条对话不会被同步阻塞到 60 秒。
- 并发调用只触发一次 Gateway 初始化。
- direct 降级后，Gateway 后台恢复可在后续 turn 自动接管，且无配置回退竞态。

---

### P1-03 Provider 预初始化矩阵不完整、不显式

**当前观察矩阵（整改后）：**

| Provider | 预热策略 | 策略声明 | 降级/失败语义 |
|---|---|---|---|
| Claude | 不执行通用 CLI resolver 预热 | `executableProbe=false`、`versionProbe=false` | `RETRY_ON_FIRST_USE` |
| Codex | executable/version resolver 预热 | `executableProbe=true`、`versionProbe=true` | `RETRY_ON_FIRST_USE` |
| OpenCode | executable/version resolver 预热 | `executableProbe=true`、`versionProbe=true` | `RETRY_ON_FIRST_USE` |
| Grok | executable/version resolver 预热 | `executableProbe=true`、`versionProbe=true` | `RETRY_ON_FIRST_USE` |
| Kimi | executable/version resolver 预热 | `executableProbe=true`、`versionProbe=true` | `RETRY_ON_FIRST_USE`；ACP 仍由实际 Session 门禁决定 |
| Pi | executable/version resolver 预热 | `executableProbe=true`、`versionProbe=true` | `RETRY_ON_FIRST_USE` |
| OMP | 不执行通用 CLI resolver 预热 | `channelProbe=true` | `DIRECT_CHANNEL` |
| DSH | 不执行通用 CLI resolver 预热 | `channelProbe=true`、`configurationLoad=true` | `HOST_CHANNEL` |

**代办：**

- [x] 建立 Provider 预热策略接口/注册表，避免在核心流程继续追加 Provider `if/else`。
- [x] 每个 Provider 明确声明：可执行文件探测、版本探测、通道探测、配置加载、能力协商、超时和降级策略。
- [x] 将 Claude、OMP、DSH 的有意差异写入代码级配置或测试矩阵，不能只靠注释和人员记忆。
- [x] 预热失败不得永久污染 detector 状态；定义可重试、冷却或按 generation 失效机制。
- [x] 所有预热任务都必须可取消并绑定 Project 生命周期。
- [x] 增加 8 Provider 注册完整性测试和重复注册 fail-fast 测试。

**整改记录（2026-08-30）：**

- 修改文件：
  - `src/main/java/com/github/claudecodegui/startup/BridgePreloader.java`
  - `src/main/java/com/github/claudecodegui/startup/ProviderPrewarmPolicy.java`
  - `src/main/java/com/github/claudecodegui/startup/PrewarmFallback.java`
  - `src/main/java/com/github/claudecodegui/startup/ProviderPrewarmStrategy.java`
  - `src/main/java/com/github/claudecodegui/startup/ProviderPrewarmRegistry.java`
  - `src/main/java/com/github/claudecodegui/cli/common/ProviderCliResolver.java`
  - `src/main/java/com/github/claudecodegui/session/runtime/CodexCliResolver.java`
  - `src/test/java/com/github/claudecodegui/startup/BridgePreloaderLifecycleTest.java`
  - `src/test/java/com/github/claudecodegui/startup/ProviderPrewarmRegistryTest.java`
- 设计说明：新增 `ProviderPrewarmStrategy` + `ProviderPrewarmRegistry` + `ProviderPrewarmPolicy`，以 `ProviderType.values()` 作为 8 Provider 完整矩阵的唯一全集。注册表在构造时对重复 Provider 和缺失 Provider fail-fast；`BridgePreloader` 只负责遍历策略、提交任务、超时和生命周期取消，不再维护 Provider 专用的线性分派方法。新增 Provider 只需增加策略实现和注册项。
- 有意差异：Claude、OMP、DSH 的不执行通用 resolver 预热已进入代码级 policy。OMP/DSH 的 `channelProbe`、`configurationLoad` 当前是架构差异的声明性元数据，本轮没有虚构或新增实际 host/channel 健康检查；实际 channel 初始化仍在各自 Session/Channel 路径完成。`capabilityNegotiation` 也只是矩阵字段，能力协商整改留在 P1-04。
- 失败与重试：`ProviderCliResolver` 和 `CodexCliResolver` 只缓存成功解析出的 executable/version；失败或未接受版本不会写入成功缓存，首轮调用仍可重新探测。预热 Future 取消版本探测时会强制终止探测子进程并恢复线程中断标志，避免取消留下孤立进程。
- 生命周期：每个 Provider 策略对应一个可取消 Future；预热总 Future 和 Provider 子任务均绑定 Project Disposable，项目关闭或外层取消时调用 `cancel(true)`，任务执行前后检查 project/interruption/cancelled 状态。
- 定向验证：`./gradlew.bat compileJava --no-daemon` 通过；`./gradlew.bat test --tests "com.github.claudecodegui.startup.BridgePreloaderLifecycleTest" --tests "com.github.claudecodegui.startup.ProviderPrewarmRegistryTest" --tests "com.github.claudecodegui.session.runtime.CodexCliResolverCacheTest" --tests "com.github.claudecodegui.cli.opencode.OpenCodeCliResolverTest" --no-daemon` 通过（14 tests）。

**完成标准：**

- [x] 新增 Provider 只增加策略实现和装配项，不修改核心预热分派主体。
- [x] 8 Provider 的预热/不预热都有显式、可测试的依据。
- [x] 预热失败后首轮发送仍有明确降级，不会永久禁用最佳通道。

---

### P1-04 Provider 静态能力与当前 Session 实际能力不一致

**重点场景：**Kimi 静态声�� thinking 能力，但只有 ACP 通道完整支持；版本探测、协商或运行失败降级到 legacy 后，本 Session 可能没有 thinking。

**代办：**

- [x] 区分 Provider 静态能力与 Session negotiated capability。
- [x] 后端下发当前 Session 实际的 thinking/tool/MCP 能力。
- [x] 下发明确降级原因，如 `version_probe_failed`、`acp_unavailable`、`legacy_fallback`。
- [x] 前端只根据后端下发的实际能力渲染，不自行推断 Provider 能力。
- [x] 将 negotiated capability 记录到历史元数据，保证刷新后回显语义一致。
- [x] 为 Kimi ACP 成功、协商失败、版本不支持、运行中 ACP 崩溃降级分别补测试。
- [x] 将同一能力模型推广到其他存在多通道/可降级 Provider。

**整改记录（2026-08-30）：**

- 修改文件：后端 `CliSession.java`、`CliSessionManager.java`、`SessionRuntimeRouter.java`、`SessionSendService.java`、`ClaudeSession.java`、`SessionCapabilityService.java`、`SessionCapabilityState.java`、`SessionCapabilityChannel.java`、`SessionCapabilityDegradationReason.java`、`SessionNegotiatedCapabilities.java`、`SessionCapabilityMetadataStore.java`、`SessionCapabilitiesPayloadField.java`、`HistorySessionsJsonEnhancer.java`、`HistoryWorkflowService.java`；Kimi `KimiCliSessionFactory.java`、`KimiRunOnceCliSession.java`、`KimiAcpChannelGate.java`、`KimiAcpCliSession.java`；前端 `App.tsx`、`ChatScreen.tsx`、`useSessionCapabilities.ts`、`SessionCapabilitiesDrawer.tsx`、`ChatInputBox.tsx`、`ButtonArea.tsx`、`ChatInputBoxFooter.tsx`、`ModelConfigSelect.tsx`、`ReasoningSelect.tsx`、`reasoningUtils.ts`、`types.ts`、`types/index.ts` 及对应测试文件。
- Provider/CLI 实现：Claude、Codex 以及 OpenCode/Grok/Pi/OMP/DSH 复用的通用 CLI/Channel 会话返回 `NEGOTIATED + cli` 的实际会话快照；Kimi ACP 在 `initialize`、`session/new`/`session/load` 和 `configOptions` 解析后更新能力；Kimi legacy 明确返回 `DEGRADED + kimi_legacy_stream_json`。这是同一能力模型在其他 Provider 上的推广，不虚构 OpenCode/Grok/Pi/OMP/DSH 独立协商实现。
- 状态与保守降级：`UNKNOWN`/`DISCOVERED` 的 nullable 能力字段在线上序列化为 `null`，表示尚未完成协商，前端保持既有行为；`NEGOTIATED` 表示当前通道已完成能力确认；`DEGRADED` 表示已切换到保守能力集合。Kimi 的 `version_probe_failed`、`version_unsupported`、`acp_unavailable`、`acp_negotiation_failed`、`acp_runtime_failed`、`legacy_fallback` 均通过枚举值下发，只有明确 `false` 才隐藏 thinking selector。
- 八 Provider 矩阵：

  | Provider | 当前能力来源 | thinking/tool/MCP 语义 |
  |---|---|---|
  | Claude | 通用 CLI 会话 | 当前 CLI 会话可用能力；MCP 仍由独立 Gateway/会话配置字段表达 |
  | Codex | 通用 CLI 会话 | 当前 CLI 会话可用能力；MCP 仍由独立 Gateway/会话配置字段表达 |
  | OpenCode | 通用 CLI/Channel 会话 | 复用统一 CLI 能力快照，不额外声称独立协商 |
  | Grok | 通用 CLI/Channel 会话 | 复用统一 CLI 能力快照，不额外声称独立协商 |
  | Kimi | ACP 或 legacy stream-json | ACP 按 `configOptions` 协商 thinking；legacy 明确无 thinking，运行/门禁失败保守降级 |
  | Pi | 通用 CLI/Channel 会话 | 复用统一 CLI 能力快照，不额外声称独立协商 |
  | OMP | 通用 CLI/Channel 会话 | 复用统一 CLI 能力快照；Gateway 注入能力仍按实际通道配置表达 |
  | DSH | 通用 CLI/Channel 会话 | 复用统一 CLI 能力快照；host/channel 差异不冒充 ACP 协商 |

- 历史一致性：`SessionCapabilityMetadataStore` 以 provider + sessionId 为键、带大小/条目上限和原子替换写入，`HistorySessionsJsonEnhancer` 在历史条目上回填 `sessionCapabilities`；实时能力与历史回显均复用同一 `SessionNegotiatedCapabilities` wire 结构。
- 前端门禁：`useSessionCapabilities` 严格校验 nullable wire 字段；`ModelConfigSelect`/`ReasoningSelect` 仅接收后端 `thinkingAvailable`，未知值不改变既有 UI，明确不可用时不渲染 reasoning selector；能力面板显示 state、channel、能力值和降级原因。
- 定向验证：`./gradlew.bat compileJava --no-daemon` 通过；`./gradlew.bat test --tests "com.github.claudecodegui.cli.kimi.acp.KimiAcpThinkingNegotiationTest" --tests "com.github.claudecodegui.cli.kimi.acp.KimiAcpCapabilityTest" --tests "com.github.claudecodegui.session.SessionCapabilityMetadataStoreTest" --tests "com.github.claudecodegui.handler.history.HistorySessionsJsonEnhancerTest" --tests "com.github.claudecodegui.handler.history.HistoryWorkflowServiceTest" --no-daemon` 通过（新增/相关 Java 定向测试）；`cd webview; npx vitest run src/components/ChatInputBox/reasoningUtils.test.ts` 通过（3 tests）；`npm exec -- tsc --noEmit` 通过。`ModelConfigSelect.test.tsx` 当前仍有既存的 5 个旧断言失败（翻译 key、ModelInfo 回调和上下文开关），本轮新增 session capability 断言通过，未扩大到无关修复。

**完成标准：**

- [x] UI 不再承诺当前 Session 实际不可用的 thinking/tool 能力。
- [x] 实时与历史回显使用同一份实际能力和降级原因。

---

### P1-05 实时消息与历史回显缺少统一业务块契约

**问题：**8 个 Provider 都存在 HistoryReader，但测试深度不一致。实时 parser/handler 与历史 reader 可能分别转换原始事件，存在 thinking 消失、工具永久 loading、tool result 孤立、usage/error/interrupted 丢失等风险。

**统一业务块矩阵：**

| 业务维度 | Claude | Codex | OpenCode | Grok | Kimi | Pi | OMP | DSH |
|---|---|---|---|---|---|---|---|---|
| 普通文本及顺序 | [x] | [x] | [x] | [x] | [x] | [x] | [x] | [x] |
| thinking start/delta/end | [x] | [x] | [x] | [x] | [x] | [x] | [x] | [x] |
| tool_use id/name/input | [x] | [x] | [x] | [x] | [x] | [x] | [x] | [x] |
| tool_result 配对/错误 | [x] | [x] | [x] | [x] | [x] | [x] | [x] | [x] |
| usage | [x] | [x] | [x] | [x] | [x] | [x] | [x] | [x] |
| error | [x] | [x] | [x] | [x] | [x] | [x] | [x] | [x] |
| interrupted/cancelled | [x] | [x] | [x] | [x] | [x] | [x] | [x] | [x] |
| 空/损坏记录容错 | [x] | [x] | [x] | [x] | [x] | [x] | [x] | [x] |
| 实时→刷新历史等价 | [x] | [x] | [x] | [x] | [x] | [x] | [x] | [x] |

**代办：**

- [x] 定义后端统一的业务消息块模型，实时和历史只做各自输入适配。
- [x] 统一 tool call identity 的生成、归一化和持久化规则。
- [x] 对缺失 tool id、未配对 result、重复 result 下发显式状态，前端不猜测。
- [x] 明确文本/thinking flush 与 tool_use/tool_result 的排序规则。
- [x] 明确 EOF、非零退出、JSON 截断、重复/迟到 `stream_end` 的统一收尾规则。
- [x] 覆盖多工具并行、结果乱序和跨 turn 迟到事件。
- [x] 为历史加载增加 malformed record 隔离，单条损坏不得阻断整个会话回显。
- [x] 验证当前工作树中的 `NativeCliHistoryMessages`、Grok/Kimi/Pi HistoryReader 变更。

**整改记录（2026-08-31）：**

- 后端统一契约：新增 `MessageBlockContract`、`MessageBlockToolStatus`、`MessageBlockToolIdSource`，以 Java 枚举作为前后端状态 SSOT；统一下发 `pending`、`completed`、`unpaired`、`orphaned`、`duplicate` 以及 `explicit`/`generated` identity 来源，前端仅消费后端结论。
- 实时入口：Claude 由 `ClaudeMessageHandler` 接入统一 ledger；Codex、OpenCode、Grok、Kimi、Pi、OMP、DSH 共用 `CodexMessageHandler` 接入同一契约。`stream_end`、`onError`、`onComplete`、interrupt 均执行幂等 finalize，并在 `stream_start`/新 turn 重建 ledger，避免上一 turn 状态污染下一 turn。
- 历史入口：8 Provider 均经 `HistoryProviderRegistry.loadMessages()` 的统一 choke point 执行 `normalizeHistoryMessages(...)`；`NativeCliHistoryMessages` 只适配原始块形状，不提前生成 identity 或推断生命周期，避免相同工具调用碰撞和缺失 result id 被过早固化为 orphan。
- identity/配对规则：显式 id 原样保留；缺失 tool_use id 按消息位置、块位置、工具名和 input 生成稳定且不碰撞的 id；缺失 result id 只在事件到达位置恰有一个 pending tool_use 时配对，多 pending 时不按顺序猜测并生成独立 orphan id；重复 result 标记 `duplicate`，EOF/异常收尾后未完成 tool_use 标记 `unpaired`。
- 顺序与边界：文本、thinking、tool_use、tool_result 保留输入块顺序；显式 id 支持 result 先到、tool_use 后到以及多工具结果乱序配对；顺序处理缺失 id，后续 turn 的 tool_use 不会干扰前一 turn 的唯一 pending 判断。normalizer 只改权威 `raw/message/content` 容器中的工具块，保留 usage、error、interrupted/cancelled 及其他 provider 原始字段。
- 容错：null 消息、非对象块和缺少 content array 的记录按条跳过或原样保留；各 HistoryReader 的现有逐行解析隔离继续生效，单条 malformed record 不阻断已解析会话回显。Grok/Kimi/Pi 原生历史构造与 Reader 定向测试已覆盖，Claude/Codex/OpenCode/OMP/DSH 通过统一 Registry/handler choke point 获得相同契约。
- 前端回显：协议生成器从 Java 枚举生成 `MESSAGE_BLOCK_TOOL_STATUS`/`MESSAGE_BLOCK_TOOL_ID_SOURCE`；`ContentBlockRenderer`、`MessageItem` 和所有工具块统一使用 `isToolLifecycleTerminal`。显式 `pending` 优先于 result mirror，其他后端终态停止 loading；仅旧历史无状态时回退到 result 是否存在。异步 background agent 仍以 task event/sidechain history 为完成权威，`unpaired` 仅负责确定性停止悬挂状态。
- 修改文件：后端 `MessageBlockToolStatus.java`、`MessageBlockToolIdSource.java`、`MessageBlockContract.java`、`ClaudeMessageHandler.java`、`CodexMessageHandler.java`、`HistoryProviderRegistry.java`、`NativeCliHistoryMessages.java` 及 `MessageBlockContractTest.java`；前端 `generate-protocol-types.mjs`、`types/index.ts`、`toolLifecycle.ts`、`ContentBlockRenderer.tsx`、`MessageItem.tsx`、各 `toolBlocks` 组件及对应定向测试。
- 定向验证：`./gradlew.bat test --tests "com.github.claudecodegui.session.MessageBlockContractTest" --tests "com.github.claudecodegui.session.ClaudeMessageHandlerRawConsistencyTest" --tests "com.github.claudecodegui.session.CodexMessageHandlerTest" --tests "com.github.claudecodegui.handler.history.HistoryProviderRegistryTest" --tests "com.github.claudecodegui.handler.history.HistoryWorkflowServiceTest" --tests "com.github.claudecodegui.provider.grok.GrokHistoryReaderTest" --tests "com.github.claudecodegui.provider.kimi.KimiHistoryReaderTest" --tests "com.github.claudecodegui.provider.pi.PiHistoryReaderTest" --no-daemon` 通过；`cd webview; npx vitest run src/utils/toolLifecycle.test.ts` 通过（4 tests）；`npx vitest run src/components/MessageItem/ContentBlockRenderer.test.tsx -t "ContentBlockRenderer tool lifecycle"` 通过（1 test，3 skipped）；`npx tsc --noEmit` 通过。

**完成标准：**

- [x] 同一会话实时显示与重新打开后的历史显示在块类型、顺序、状态和 usage 上等价。
- [x] 工具卡片不会因 id 缺失/漂移永久显示 loading。
- [x] 损坏的单条历史记录不会导致整个历史加载失败。

## 4. P2：生命周期和交互加固

### P2-01 权限/询问/计划审批临时 Alarm 未绑定 Disposable

**涉及文件：**

- `src/main/java/com/github/claudecodegui/handler/permission/PermissionActionHandlers.java`

**现状：**`showDialogWithFrontendCheck()` 每次创建独立 Swing `Alarm`，最多重试约 10 秒；成功、超时和窗口销毁时没有显式 dispose。Java Alarm 与 JavaScript `setTimeout` 还形成双重重试。

**代办：**

- [x] 将 Alarm 绑定到窗口/handler 的父 Disposable，或复用一个生命周期受控的 Alarm。
- [x] 成功、超时、session 切换、历史恢复、窗口 dispose 时取消对应请求。
- [x] 回调执行前检查 context、session、turn 和 request id 是否仍有效。
- [x] 收口 Java 与 JavaScript 两层重试，避免双重重试放大。
- [x] 为 pending permission/ask/plan map 增加数量或超时观测。
- [x] 增加窗口关闭、session 切换和 frontend 永不 ready 的测试。

**整改记录（2026-08-31）：**

- 修改文件：`PermissionActionHandlers.java`、`SessionLifecycleManager.java`、`ChatWindowDelegate.java`，以及 `PermissionActionHandlersTest.java`、`SessionLifecycleManagerTest.java`。
- 生命周期设计：`PermissionActionHandlers` 复用绑定自身 `Disposable` 的 Swing `Alarm`，集中追踪 frontend-ready 检查；`ChatWindowDelegate.dispose()` 主动释放 handler，避免依赖 `HandlerContext` 后置销毁。session reset 和 dispose 会递增 request generation、取消检查任务、完成并移除全部 pending permission/ask/plan future，已有 safety net 仍负责单请求超时兜底。
- 迟到回调防护：回调执行前校验 handler/context 未销毁、future 未完成、generation、request identity 和 session identity；成功、fallback、响应和清理均使用条件移除，旧请求不能误删新请求。Java Alarm 保留有界重试和一次 fallback，移除 JavaScript 内部递归 `setTimeout`。
- 观测与验证：增加 pending request 数量读取，覆盖 frontend check 清理和 dispose 幂等性；session reset 回归测试覆盖统一清理入口。定向验证：`gradlew.bat compileJava --no-daemon` 通过；`gradlew.bat test --tests "com.github.claudecodegui.handler.permission.PermissionActionHandlersTest" --tests "com.github.claudecodegui.session.SessionLifecycleManagerTest" --no-daemon` 通过（14 个测试相关任务成功）。
- Commit：
  - `51a004e3 fix(permission): bind dialog retries to handler lifecycle`
  - `fba1d1e9 test(permission): cover dialog lifecycle cleanup`

**完成标准：**所有 pending future 和 Alarm 都有明确 owner、超时和终止路径。

---

### P2-02 DSH 操作 timeout 未取消且可能覆盖新操作状态

**涉及文件：**

- `webview/src/components/settings/DshProviderSection/index.tsx`

**现状：**启动、停止、保存等操作直接创建约 35 秒/65 秒 timeout，没有保存句柄或在 unmount 时清理。旧 timeout 还可能提前清除新操作的 `busy`。

**代办：**

- [x] 使用 `useRef` 保存 timeout 句柄。
- [x] 新操作开始前取消旧 timeout。
- [x] effect cleanup/unmount 时清理 timeout。
- [x] 引入 operation token/id，旧回调不得修改新操作状态。
- [x] 后端响应到达时立即结束对应 operation，不依赖固定 timeout。
- [x] 增加 unmount、连续点击、响应乱序测试。

**整改记录（2026-08-31）：**

- 修改文件：`webview/src/components/settings/DshProviderSection/index.tsx`、`src/main/java/com/github/claudecodegui/handler/dsh/DshHostActionHandlers.java`、`webview/src/global.d.ts`，以及 `webview/test/components/settings/DshProviderSection/DshProviderSection.test.tsx`。
- 设计说明：前端以 `useRef` 持有当前 operation、operation id 和 timeout 句柄；启动新操作时先清理旧 timer，响应仅能结束匹配 operation id 的 busy 状态，组件卸载时取消 timer 并拒绝迟到 callback。后端从请求 payload 读取 `operationId`，在异步状态响应中原样回传，形成请求—响应配对；发送失败也会立即结束当前 operation。
- 定向测试：`cd webview && npx vitest run test/components/settings/DshProviderSection/DshProviderSection.test.tsx` 通过（3 tests）；`gradlew.bat compileJava --no-daemon` 通过。`tsc -p tsconfig.test.json --noEmit` 仍受既有测试/源码不同步错误影响，未扩大修复范围。
- Commit：
  - `f4313e4f fix(dsh): guard host operation timeouts`
  - `9652b171 test(dsh): cover host operation lifecycle`

**完成标准：**组件卸载后无迟到 state update，旧操作 timeout 不影响新操作。

### P2-03 Node Gateway 信号关闭语义可能不完整

**涉及文件候选：**

- `ai-bridge/mcp-gateway-server.js`
- `ai-bridge/mcp-gateway/ipc-server.js`
- `ai-bridge/mcp-gateway/server-supervisor.js`

**现状：**SIGINT/SIGTERM handler 调用 `ipc.close()` 后立即 `process.exit(0)`。若 transport/client close 含异步释放，可能被立即退出截断；Java 进程树强杀只是最终兜底。

**代办：**

- [x] 将 shutdown 改为幂等 async 状态机。
- [x] `IpcServer.close()` 返回可等待的 Promise。
- [x] 等待所有 supervisor、transport 和 MCP 子进程退出。
- [x] 设置 shutdown deadline，超时再强制退出进程树。
- [x] 关闭期间拒绝新请求和新 supervisor 启动。
- [x] 增加 SIGTERM 后子 MCP 进程归零的定向测试。

**整改记录（2026-08-30）：**

- 修改文件：`ai-bridge/mcp-gateway-server.js`、`ai-bridge/mcp-gateway/shutdown-controller.js`、`ai-bridge/mcp-gateway/ipc-server.js`、`ai-bridge/mcp-gateway/server-supervisor.js`、`ai-bridge/mcp-gateway/transport/stdio-client.js`、`ai-bridge/utils/kill-tree.js`，以及对应 Node 定向测试。
- 设计说明：信号处理收口到幂等 async shutdown controller；`IpcServer.close()` 复用同一 Promise，先关闭请求入口并以 503 拒绝新请求，再等待 supervisor 和 transport 释放。supervisor 在 stopping 状态拒绝 refresh 创建新 client，stdio client 关闭 stdin、拒绝 pending request、确定性终止进程树并等待 exit；Windows `taskkill` 非零退出时回退直接 signal，并在短延迟后重试。
- deadline 兜底：IPC shutdown 使用有界 deadline；超时后销毁残留 socket，stdio transport 使用有界等待并再次终止子进程树，避免异步 close 无限阻塞 Gateway 退出。
- 定向测试：在 `ai-bridge` 目录运行 IPC shutdown/snapshot、server-supervisor shutdown、shutdown-controller、stdio transport 和 kill-tree 测试，23 tests 全部通过；定向 ESLint 通过。
- Commit：
  - `75277bb9 fix(ai-bridge): await gateway shutdown cleanup`
  - `510b76e7 test(ai-bridge): cover gateway shutdown lifecycle`
  - `13199f6e fix(ai-bridge): ensure signal shutdown clears MCP children`
  - `86fa232a test(ai-bridge): verify signal-driven MCP child cleanup`

**完成标准：**正常信号关闭优雅释放，deadline 后仍有确定性强杀兜底。

---

### P2-04 fallback `NodeService` 不在 Disposer tree

**代办：**

- [x] `resetInstance()` 在清除 fallback 引用前先调用 dispose。
- [x] fallback 创建和启动长期资源时输出明确告警。
- [x] 尽量将 fallback 注册到可用的 Disposable owner。
- [x] 测试结束时断言 fallback 进程、sweeper 和 registry 均已清理。
- [x] 增加 fallback 实际启动资源后 reset 的回归测试。

#### 完成记录（2026-08-31）

- 修改文件：
  - `src/main/java/com/github/claudecodegui/bridge/NodeService.java`
  - `src/main/java/com/github/claudecodegui/bridge/ProcessManager.java`
  - `src/main/java/com/github/claudecodegui/util/PlatformUtils.java`
  - `src/test/java/com/github/claudecodegui/bridge/NodeServiceFallbackLifecycleTest.java`
- 设计说明：
  - fallback 使用可脱离 IntelliJ Application 构造的 `EnvironmentConfigurator`，创建 stale-channel sweeper 时输出明确告警；Application Disposable owner 可用时立即注册，先无 owner 后可用的路径也会补注册。
  - `resetInstance()` 和 platform service 接管路径统一通过 `Disposer.dispose()` 先释放 fallback；`NodeService.dispose()` 完成进程、sweeper 和 registry 清理并清除静态引用，避免测试/bootstrap 路径遗留长期资源。
  - `ProcessManager.cleanupAllProcesses()` 补充清空 `channelStartTimes`；Windows `taskkill` 超时或失败时回退 Java Process API，确保受限宿主中仍能终止直接跟踪的子进程。
- Provider 对称性检查：Claude、Codex、OpenCode、Grok、Kimi、Pi、OMP、DSH 均通过共享 `NodeService` / `ProcessManager` 生命周期基础设施受益，无 Provider 特例或有意差异。
- 定向验证：`gradlew.bat test --tests "com.github.claudecodegui.bridge.NodeServiceFallbackLifecycleTest" --no-daemon` 通过；覆盖 owner dispose 和实际 Java 子进程启动后 reset，断言进程、sweeper、registry 与 fallback 引用全部归零。
- Commit：
  - `57480f59 fix(bridge): dispose fallback NodeService resources`
  - `6be83809 test(bridge): cover fallback NodeService reset cleanup`

**完成标准：**测试/异常 bootstrap 路径不遗留后台线程或进程。

---

### P2-05 对话块状态机边界加固

**定向场景：**

- [x] `thinking_start → thinking_delta* → text` 正常关闭 thinking。
- [x] thinking start 后无 delta 也能正确结束。
- [x] text 后进入 tool_use 前先 flush 文本。
- [x] tool input 增量能在 tool_use end 时收口。
- [x] tool_result 先到、tool_use 后到时可最终配对。
- [x] 多工具并行、结果乱序不互相覆盖。
- [x] tool_result 永不到时显示明确超时/中断状态，不永久 loading。
- [x] stdout EOF、非零退出、JSON 截断都能结束 streaming 状态。
- [x] `stream_end` 丢失时由会话层补发且只补一次。
- [x] `stream_end` 重复/迟到不会结束新 turn。
- [x] cancel/interrupt 会确定性终止进程树并关闭思考区、工具区 loading。
- [x] usage 不因重复事件重复累加。

#### 完成记录（2026-08-31）

- 修改文件：
  - `src/main/java/com/github/claudecodegui/session/ClaudeMessageHandler.java`
  - `src/main/java/com/github/claudecodegui/session/CodexMessageHandler.java`
  - `src/main/java/com/github/claudecodegui/cli/kimi/acp/KimiAcpCliSession.java`
  - `src/test/java/com/github/claudecodegui/session/ClaudeMessageHandlerDedupTest.java`
  - `src/test/java/com/github/claudecodegui/session/ClaudeMessageHandlerResultUsageTest.java`
  - `src/test/java/com/github/claudecodegui/session/CodexMessageHandlerTest.java`
  - `src/test/java/com/github/claudecodegui/provider/claude/ClaudeCliStreamParserTest.java`
  - `src/test/java/com/github/claudecodegui/cli/common/CliTerminationSymmetryTest.java`
- 设计说明：
  - 将 thinking、文本、tool_use、tool_result、usage 和 `stream_end` 的异常收尾统一为幂等操作；完成、interrupt、EOF、非零退出、解析异常均关闭对应 streaming/thinking/tool loading 状态。
  - 工具块使用稳定 identity 和显式终态；增量 tool input 在结束时收口，乱序/迟到/重复结果不覆盖其他工具或新 turn，未配对工具最终标记为 `unpaired` 而不永久 loading。
  - Claude persistent、one-shot CLI、公共 run-once、channel 会话均已有进程树终止路径；Kimi ACP 的 `session/cancel` 保留长驻连接，同时增加 `CLI_INTERRUPT_FALLBACK_MS` 超时后通过 `CliProcessHandle` 强杀进程树，并以 turn id/连接/handle 校验防止迟到回调误杀新 turn。
  - 重复 usage 事件采用替换而非累加，避免同一回合重复计费或显示错误。
- Provider 对称性检查：
  - Claude：one-shot/persistent 均有 interrupt、EOF/非零退出收尾和 thinking/tool finalize。
  - Codex：one-shot 复用 `CliProcessHandle`，非零退出走 `onError + onComplete(false)`，重复 usage 不累加。
  - OpenCode：复用 `AbstractRunOnceCliSession` 的 stdout drain、非零退出和进程树 interrupt 收尾。
  - Grok：复用 `AbstractRunOnceCliSession`，工具历史尾随监视器在最终 drain 后收口。
  - Kimi：run-once 复用公共基类；ACP stdout/stderr 均 drain，`session/cancel` 有短时强杀 fallback，连接 EOF 拒绝 pending 请求。
  - Pi：复用 `AbstractRunOnceCliSession` 的 EOF、非零退出、截断和 interrupt 保护。
  - OMP：经 `ChannelCliSession` 走共享 channel 进程生命周期与进程树 interrupt。
  - DSH：经 `ChannelCliSession` 走共享 channel 进程生命周期与进程树 interrupt。
- 定向测试：
  - 命令：`./gradlew.bat test --tests "com.github.claudecodegui.session.CodexMessageHandlerTest" --tests "com.github.claudecodegui.session.ClaudeMessageHandlerDedupTest" --no-daemon`
  - 结果：通过。
  - 命令：`./gradlew.bat test --tests "com.github.claudecodegui.session.ClaudeMessageHandlerResultUsageTest.duplicateResultUsageReplacesInsteadOfAccumulating" --tests "com.github.claudecodegui.session.CodexMessageHandlerTest.duplicateUsageEventsReplaceInsteadOfAccumulating" --no-daemon`
  - 结果：通过；同测试类中另有两个既有失败与本次改动无关，未扩大修复范围。
  - 命令：`./gradlew.bat test --tests "com.github.claudecodegui.cli.kimi.acp.KimiAcpCapabilityTest" --tests "com.github.claudecodegui.cli.kimi.acp.KimiAcpThinkingNegotiationTest" --no-daemon`
  - 结果：通过。
  - 命令：`./gradlew.bat test --tests "com.github.claudecodegui.cli.common.CliTerminationSymmetryTest" --no-daemon`
  - 结果：通过；使用源码契约检查覆盖真实进程树无法安全纳入单元测试的对称性要求。
- 有意差异/豁免：
  - Kimi ACP 是长驻 JSON-RPC 连接，优先发送 `session/cancel` 以保留连接；与 one-shot provider 无条件杀进程不同。该差异源于连接模型，并由短时 fallback 强杀进程树提供等价失控保护。
  - 未启动真实 Provider 进程树做取消测试，避免测试误杀宿主/子进程；通过 `CliTerminationSymmetryTest` 对共享终止实现、8 个工厂装配和 EOF/非零退出回调做源码契约兜底。
- Commit：
  - `7c5296a0 feat(session): unify live and history tool block lifecycle`
  - `0b02dbd4 feat(webview): render backend tool lifecycle status`
  - `bde905ec docs(audit): record unified message block remediation`
  - `c5c4e8a2 fix(claude): finalize incremental tool input blocks`
  - `19ae6651 fix(session): ignore stale response turn callbacks`
  - `2f7e40ab fix(session): reconcile out-of-order tool results`
  - `d243ca9e fix(claude): flush pending text before tool blocks`
  - `3d49ed90 fix(session): finalize incomplete response streams`
  - `69644609 test(session): guard duplicate usage events`
  - `b71cd2ec fix(kimi): add deterministic acp cancel fallback`
  - `3d3cad36 test(cli): guard provider termination paths`

**完成标准：**所有异常结束路径都使前端退出 streaming/thinking/tool loading 状态。

## 5. P3：低风险清理与可观测性

### P3-01 Webview 初始化 timer 统一治理

**涉及文件候选：**

- `webview/src/main.tsx`
- `webview/src/hooks/useThemeInit.ts`
- `webview/src/utils/bridgeStartup.ts`

**代办：**

- [x] 将 bridge/theme/bootstrap 重试统一纳入可取消 controller。
- [x] 保证每类 bootstrap 只存在一个活动实例。
- [x] `pagehide`/reload/unmount 时统一取消 timer。
- [x] 开发模式 vConsole 延迟 timer 也纳入清理。
- [x] 保留 `useDragSort` 当前 `AbortController` 和 preview cleanup，不退化为无法移除的裸匿名 listener。

#### 完成记录（2026-08-31）

- 修改文件：
  - `webview/src/utils/bootstrapLifecycle.ts`
  - `webview/src/utils/bridgeStartup.ts`
  - `webview/src/main.tsx`
  - `webview/src/hooks/useThemeInit.ts`
  - `webview/src/utils/bootstrapLifecycle.test.ts`
  - `webview/src/utils/bridgeStartup.test.ts`
  - `webview/src/hooks/useThemeInit.test.ts`
- 设计说明：
  - 新增 `BootstrapLifecycleController`，按 bridge ready、IDE theme、vConsole position 三个 scope 统一持有 timer 与 cleanup；同 scope 再次启动时先取消旧实例，每个实例使用 token 隔离，旧实例的迟到 cancel/finish 不会误伤替代实例。
  - controller 统一绑定 `beforeunload`、`pagehide` 与 HMR dispose；`useThemeInit` unmount 使用实例 token 取消重试，bridge ready 成功、IDE theme 成功/耗尽重试和 vConsole 定位完成时主动 finish。
  - `main.tsx` 删除重复的本地 bridge polling，统一复用 `bridgeStartup.ts`；vConsole 动态 import 迟到时先检查 scope 是否仍有效，其 100ms 定位 timer 也由 controller 管理。
  - `useDragSort` 未修改，现有 `AbortController`、pointer listener signal 和 drag preview cleanup 保持不变。
- Provider 对称性检查：本项仅治理 Webview bootstrap 生命周期，不涉及 Provider 分派或调用路径，无 Provider 特例。
- 定向验证：
  - `cd webview && npx vitest run src/utils/bootstrapLifecycle.test.ts src/utils/bridgeStartup.test.ts src/hooks/useThemeInit.test.ts`：3 files、8 tests 全部通过。
  - `cd webview && npx tsc --noEmit`：通过。
  - 定向 ESLint（上述产品与测试文件）：通过。
- Commit：
  - `2f77857c fix(webview): bind bootstrap timers to page lifecycle`
  - `c486fda9 test(webview): cover bootstrap timer cleanup`

---

### P3-02 生命周期与资源指标

- [ ] 为 Project、Session、Turn、Gateway process 和 CLI process 增加可关联的 lifecycle/generation id。
- [ ] 结构化记录 spawn、stdin close、stdout EOF、exit、terminate、rebuild、fallback、degraded。
- [x] 记录活跃 Node/CLI/MCP 子进程数量。
- [x] 记录 persistent registry size、淘汰次数和 rebuild cooldown 命中次数。
- [ ] 记录 pending permission/tool call/orphan tool result 数量。
- [x] 记录 Gateway restart 次数、cold-start 耗时、catalog-ready 耗时和 direct 降级次数。
- [x] 增加诊断命令或开发面板导出当前资源快照。

#### 完成记录

- 修改文件：
  - 后端指标：`CliPersistentProcessRegistry.java`、`McpGatewayService.java`、`ResourceDiagnosticsService.java`、`RuntimeResourceDiagnostics.java`。
  - 快照协议：`NodeProcessActionHandlers.java`、`NodeProcessInfo.java`、`protocol/payload/NodeProcess*PayloadField.java`、`webview/scripts/generate-protocol-types.mjs`、`webview/src/utils/nodeProcessCapabilities.ts`。
  - 开发面板：`webview/src/components/ChatInputBox/selectors/NodeProcessSelect.tsx` 与 10 个 locale 文件。
  - 定向测试：`CliPersistentProcessRegistryTest.java`、`McpGatewayLifecycleTest.java`、`RuntimeResourceDiagnosticsTest.java`、`NodeProcessActionHandlersSerializationTest.java`、`generate-protocol-types.test.ts`、`NodeProcessSelect.diagnostics.test.tsx`。
- 设计说明：
  - `CliPersistentProcessRegistry.Diagnostics` 暴露 registry size、可用进程数、待重建数、淘汰次数和 rebuild cooldown 命中次数；计数器由 registry 权威路径更新。
  - `McpGatewayService.Diagnostics` 暴露 lifecycle state、last failure、process generation、活跃进程数、refresh 状态、restart 次数、cold-start/catalog-ready 耗时和 direct 降级次数。
  - Project scoped `ResourceDiagnosticsService` 统一聚合 Node、CLI、MCP 活跃进程数量及两类生命周期指标，`NodeProcessActionHandlers` 只负责将后端已聚合快照下发到现有 Node Process 面板。
  - Java `NodeProcess*PayloadField` 枚举是 payload key SSOT；generator 生成 `NODE_PROCESS_KIND`、`NodeProcess*PayloadWire` 及完整嵌套结构，前端不再手写 snapshot/process/totals 类型，也不从 `processes` 数组重新推导资源指标。
  - Gateway `lastFailure` 无失败时仍显式序列化为 `null`；snapshot 使用 `JsonObject.toString()`，避免普通 Gson `serializeNulls=false` 丢失契约字段。
  - Node Process 面板只渲染后端下发的 active process、persistent registry 和 Gateway 值；lifecycle state 与 `-1` duration 保持原值展示，不在 Webview 解释业务语义。
- Provider 对称性检查：本项只增加共享 registry/Gateway 观测、Project 级聚合和 Webview 展示，不修改 Claude、Codex、OpenCode、Grok、Kimi、Pi、OMP、DSH 的调用、取消、stdin、cwd 或进程复用路径，无 Provider 特例或有意差异。
- 定向验证：
  - `.\gradlew.bat test --tests "com.github.claudecodegui.cli.common.CliPersistentProcessRegistryTest" --tests "com.github.claudecodegui.mcp.McpGatewayLifecycleTest" --tests "com.github.claudecodegui.service.RuntimeResourceDiagnosticsTest" --tests "com.github.claudecodegui.handler.nodeprocess.NodeProcessActionHandlersSerializationTest" --no-daemon`：通过。
  - `cd webview && npx vitest run test/__tests__/generate-protocol-types.test.ts`：1 file、27 tests 全部通过。
  - `cd webview && npx vitest run test/components/ChatInputBox/selectors/NodeProcessSelect.diagnostics.test.tsx`：1 file、2 tests 全部通过。
  - `cd webview && npx tsc --noEmit`：通过。
  - 定向 ESLint（generator、Node process capability/component 及相关测试）：无 error；generator 保留 2 个既存 unused label warning，本项未扩大处理范围。
  - Node locale JSON 解析与 `config.nodeProcesses.diagnostics` 六个 key 对称性检查：10 个 locale 全部通过。
- Commit：
  - `ec38399b feat(runtime): expose persistent registry diagnostics`
  - `d78449a6 test(runtime): cover persistent registry diagnostics`
  - `1b4f541e feat(mcp): expose gateway lifecycle diagnostics`
  - `ebe02ebe test(mcp): cover gateway lifecycle diagnostics`
  - `496ec218 feat(runtime): aggregate project resource diagnostics`
  - `4017a5e4 test(runtime): cover project resource diagnostics`
  - `52c0ea03 feat(protocol): generate node process snapshot payload types`
  - `a46aec24 fix(protocol): preserve null gateway failure field`
  - `c515f058 test(protocol): cover node process payload generation`
  - `d686ae2b i18n(webview): add runtime diagnostics labels`
  - `efa5b4cc feat(webview): show runtime resource diagnostics`
  - `25b4e720 test(webview): cover runtime diagnostics rendering`

---

### P3-03 架构文档陈旧内容治理

**问题：**部分 skill 参考文档仍包含旧 `BaseSDKBridge` 或“三 Provider/SDK+CLI 六路径”描述，而根 `AGENTS.md` 和当前源码已是 8 Provider、统一 CLI/Channel 路径。

- [ ] 以根 `AGENTS.md` 和当前源码为准更新 skill references。
- [ ] 删除已退役 SDK daemon/BaseSDKBridge 作为现行架构的描述。
- [ ] 将 Provider 对称矩阵更新为 Claude、Codex、OpenCode、Grok、Kimi、Pi、OMP、DSH。
- [ ] 文档中明确 one-shot、persistent、channel/ACP 等有意架构差异及等价保护。

## 6. 推荐实施顺序

1. [x] **阶段 A：生命周期闸门**——完成 P1-01，确保 dispose 后不会产生新资源。
2. [x] **阶段 B：Gateway 非阻塞降级**——完成 P1-02，再验证 P2-03。
3. [x] **阶段 C：Provider 能力和预热契约**——完成 P1-03、P1-04。
4. [x] **阶段 D：实时/历史统一块**——完成 P1-05、P2-05。
5. [x] **阶段 E：短时资源滞留清理**——完成 P2-01、P2-02、P2-04。
6. [ ] **阶段 F：可观测性与文档**——完成 P3 项。

每个阶段应独立提交，确保可单独 revert。功能、修复、重构、测试和文档按变更性质拆分 commit。

## 7. 定向验证清单

> 以下是候选范围。执行前根据实际改动选择最小集合，不应无差别全部执行。

### 7.1 Java 生命周期/Gateway

- [ ] `ProcessManagerRuntimeKeyTest`
- [ ] `ProcessManagerStaleChannelTest`
- [x] `NodeServiceFallbackLifecycleTest`
- [x] `CliPersistentProcessRegistryTest`
- [x] `McpGatewayProcessHandleTest`
- [x] `McpGatewayServiceTest`
- [x] 新增 project dispose during prewarm 测试
- [x] 新增 stale onExit generation 测试
- [x] 新增 Gateway slow catalog/direct fallback 测试
- [x] `RuntimeResourceDiagnosticsTest`
- [x] `NodeProcessActionHandlersSerializationTest`

示例命令：

```powershell
.\gradlew.bat test --tests "com.github.claudecodegui.<package>.<TestClass>"
```

### 7.2 Java 对话状态/历史

- [ ] `SessionCallbackAdapterStreamEndTest`
- [ ] `StreamMessageCoalescerStreamEndHookTest`
- [x] Kimi ACP capability/ thinking negotiation 测试
- [ ] Grok HistoryReader 测试
- [ ] Kimi HistoryReader 测试
- [ ] Pi HistoryReader 测试
- [ ] 其余 Provider 的 parser/message handler/history contract 测试

### 7.3 Node MCP Gateway

在 `ai-bridge` 目录运行，以确保使用其本地 `tsx`：

```powershell
cd ai-bridge
node --import tsx --test test/<直接相关测试文件>
```

- [x] `server-supervisor` 定向测试
- [x] IPC snapshot timing 测试
- [x] framing/http/stdio/transport 直接相关测试
- [x] 新增 SIGTERM shutdown deadline 测试
- [x] 新增 shutdown 后 MCP 子进程归零测试

### 7.4 Webview

在 `webview` 目录运行：

```powershell
cd webview
npx vitest run <直接相关测试文件>
npx tsc --noEmit
```

- [ ] `ContentBlockRenderer.test.tsx`
- [ ] `AgentGroupBlock.test.tsx`
- [ ] `ServerToolsPanel.test.tsx`
- [ ] `useToolsUpdate.test.ts`
- [ ] 新增 DSH timeout cleanup/operation token 测试
- [ ] 新增 tool id 配对与 orphan result 测试
- [x] `bootstrapLifecycle.test.ts`
- [x] `bridgeStartup.test.ts`
- [x] `useThemeInit.test.ts`
- [x] P3-01 前端改动执行 `tsc --noEmit` 与定向 ESLint
- [x] P3-02 protocol generator 与 Node Process diagnostics 定向测试
- [x] P3-02 前端改动执行 `tsc --noEmit` 与定向 ESLint
- [ ] 无直接测试覆盖的孤立前端改动执行 `tsc --noEmit`

## 8. 每项整改完成记录模板

复制以下模板到对应条目下：

```markdown
#### 完成记录

- 修改文件：
  - `path/to/file`
- 设计说明：
  -
- Provider 对称性检查：
  - Claude：
  - Codex：
  - OpenCode：
  - Grok：
  - Kimi：
  - Pi：
  - OMP：
  - DSH：
- 定向测试：
  - 命令：`...`
  - 结果：
- 有意差异/豁免：
  - 无 / 原因：
- Commit：
  - `<hash> <english conventional commit message>`
```

## 9. 发布前最终门禁

- [ ] 所有 P1 项已完成，或存在经过 review 的明确豁免。
- [ ] 项目关闭后无新建 Gateway/CLI/MCP 子进程。
- [ ] 活跃对话中 cancel/interrupt 能确定性关闭 Provider 进程树。
- [ ] Gateway 慢启动时首条消息在规定预算内直连降级。
- [ ] 8 Provider 的预热、有意差异、actual session capability 均有测试证据。
- [ ] 8 Provider 实时→历史回显矩阵的核心块均通过。
- [ ] thinking、tool、usage、error、interrupt 在异常结束后无永久 loading。
- [ ] pending permission、Alarm、Future、timer、listener 均有 owner 和结束路径。
- [ ] 只运行了与改动直接相关的定向测试，并记录结果。
- [ ] 提交按变更性质拆分，commit message 为英文 Conventional Commits。

## 10. 当前工作树说明

本报告和清单针对 **2026-08-28 当前工作树**。审计时以下领域存在未提交改动：

- Kimi ACP 会话、连接、协议与 thinking 协商。
- Native CLI 历史消息，以及 Grok/Kimi/Pi 历史测试。
- Session Provider 路由、发送服务和 Codex 消息处理。
- Bridge/Gateway 预热与 Webview 初始化。

因此：

- [ ] 修复前先确认相关未提交改动的归属和预期，避免覆盖现有工作。
- [ ] 每项整改都应基于修改时的最新 diff 重新核对风险是否仍存在。
- [ ] 未提交工作树的修复迹象不能视为基线分支已经具备该能力。
