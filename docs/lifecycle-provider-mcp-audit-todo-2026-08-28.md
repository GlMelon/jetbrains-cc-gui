# 插件生命周期、Provider、MCP Gateway 与对话链路整改清单

> 审计日期：2026-08-28
> 文档状态：待逐项整改
> 适用范围：Java 插件后端、React/JCEF Webview、`ai-bridge` Node 进程、全部 Provider CLI/Channel 路径
> 审计性质：源码静态审计；本轮未修改产品代码、未执行测试

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

- [ ] 给 `McpGatewayService` 增加不可逆的 disposed 状态。
- [ ] 在 `refreshConfig`、`buildCliConfig`、`ensureStarted`、`reloadGateway`、`statusJson` 和 self-heal 入口统一 fail-fast。
- [ ] 异步任务在提交前、实际执行时、昂贵阶段前后、获得锁后分别检查 Project/Service 生命周期。
- [ ] 保存预热 Future/Promise 句柄，并绑定 Project Disposable；dispose 时取消尚未执行或仍在等待的任务。
- [ ] 为 self-heal 增加 generation/process-handle 身份校验，防止旧进程的迟到回调重建新进程。
- [ ] dispose 后禁止任何路径重新设置 `processHandle` 或 `bridgeClient`。
- [ ] 增加“预热进行中关闭项目”的并发测试。
- [ ] 增加“旧进程退出回调晚于新进程创建”的 generation 测试。

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

- [ ] 将 Gateway 初始化/刷新改为 single-flight Future，避免重复 cold start。
- [ ] 将慢速 MCP catalog 加载移出覆盖整个 Service 的同步锁。
- [ ] 为发送路径设置短且有界的等待预算。
- [ ] 超过发送预算时立即使用 direct MCP 配置降级，后台预热继续进行。
- [ ] 区分 `process-starting`、`ipc-ready`、`catalog-loading`、`ready`、`degraded-direct`、`failed` 状态。
- [ ] 确保 direct 降级不覆盖或破坏后台成功完成的新 revision。
- [ ] 增加慢 MCP server、失联 server、并发首发、refresh 与 stop 竞争测试。

**完成标准：**

- 单个 MCP server 卡住时，首条对话不会被同步阻塞到 60 秒。
- 并发调用只触发一次 Gateway 初始化。
- direct 降级后，Gateway 后台恢复可在后续 turn 自动接管，且无配置回退竞态。

---

### P1-03 Provider 预初始化矩阵不完整、不显式

**当前观察矩阵：**

| Provider | 当前 resolver/相关预热 | 当前判断 | 待办 |
|---|---:|---|---|
| Claude | 未进入通用 resolver 预热 | 注释说明为有意排除 | [ ] 记录有意差异、首轮预算和失败策略 |
| Codex | 已预热 | 已覆盖 | [ ] 增加回归守卫 |
| OpenCode | 已预热 | 已覆盖 | [ ] 增加回归守卫 |
| Grok | 已预热 | 已覆盖 | [ ] 增加回归守卫 |
| Kimi | 已预热版本/通道相关信息 | ACP 门禁依赖预热 | [ ] 验证探测失败和 legacy 降级 |
| Pi | 已预热 | 已覆盖 | [ ] 增加回归守卫 |
| OMP | 未见通用 resolver 预热 | 可能属于 Channel 架构差异 | [ ] 明确是否无需预热及其健康检查 |
| DSH | 未见通用 resolver 预热 | 独立 host/Channel 路径 | [ ] 明确 host 预热、健康检查、退出策略 |

**代办：**

- [ ] 建立 Provider 预热策略接口/注册表，避免在核心流程继续追加 Provider `if/else`。
- [ ] 每个 Provider 明确声明：可执行文件探测、版本探测、通道探测、配置加载、能力协商、超时和降级策略。
- [ ] 将 Claude、OMP、DSH 的有意差异写入代码级配置或测试矩阵，不能只靠注释和人员记忆。
- [ ] 预热失败不得永久污染 detector 状态；定义可重试、冷却或按 generation 失效机制。
- [ ] 所有预热任务都必须可取消并绑定 Project 生命周期。
- [ ] 增加 8 Provider 注册完整性测试和重复注册 fail-fast 测试。

**完成标准：**

- 新增 Provider 只增加策略实现和装配项，不修改核心预热分派主体。
- 8 Provider 的预热/不预热都有显式、可测试的依据。
- 预热失败后首轮发送仍有明确降级，不会永久禁用最佳通道。

---

### P1-04 Provider 静态能力与当前 Session 实际能力不一致

**重点场景：**Kimi 静态声�� thinking 能力，但只有 ACP 通道完整支持；版本探测、协商或运行失败降级到 legacy 后，本 Session 可能没有 thinking。

**代办：**

- [ ] 区分 Provider 静态能力与 Session negotiated capability。
- [ ] 后端下发当前 Session 实际的 thinking/tool/MCP 能力。
- [ ] 下发明确降级原因，如 `version_probe_failed`、`acp_unavailable`、`legacy_fallback`。
- [ ] 前端只根据后端下发的实际能力渲染，不自行推断 Provider 能力。
- [ ] 将 negotiated capability 记录到历史元数据，保证刷新后回显语义一致。
- [ ] 为 Kimi ACP 成功、协商失败、版本不支持、运行中 ACP 崩溃降级分别补测试。
- [ ] 将同一能力模型推广到其他存在多通道/可降级 Provider。

**完成标准：**

- UI 不再承诺当前 Session 实际不可用的 thinking/tool 能力。
- 实时与历史回显使用同一份实际能力和降级原因。

---

### P1-05 实时消息与历史回显缺少统一业务块契约

**问题：**8 个 Provider 都存在 HistoryReader，但测试深度不一致。实时 parser/handler 与历史 reader 可能分别转换原始事件，存在 thinking 消失、工具永久 loading、tool result 孤立、usage/error/interrupted 丢失等风险。

**统一业务块矩阵：**

| 业务维度 | Claude | Codex | OpenCode | Grok | Kimi | Pi | OMP | DSH |
|---|---|---|---|---|---|---|---|---|
| 普通文本及顺序 | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] |
| thinking start/delta/end | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] |
| tool_use id/name/input | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] |
| tool_result 配对/错误 | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] |
| usage | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] |
| error | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] |
| interrupted/cancelled | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] |
| 空/损坏记录容错 | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] |
| 实时→刷新历史等价 | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] | [ ] |

**代办：**

- [ ] 定义后端统一的业务消息块模型，实时和历史只做各自输入适配。
- [ ] 统一 tool call identity 的生成、归一化和持久化规则。
- [ ] 对缺失 tool id、未配对 result、重复 result 下发显式状态，前端不猜测。
- [ ] 明确文本/thinking flush 与 tool_use/tool_result 的排序规则。
- [ ] 明确 EOF、非零退出、JSON 截断、重复/迟到 `stream_end` 的统一收尾规则。
- [ ] 覆盖多工具并行、结果乱序和跨 turn 迟到事件。
- [ ] 为历史加载增加 malformed record 隔离，单条损坏不得阻断整个会话回显。
- [ ] 验证当前工作树中的 `NativeCliHistoryMessages`、Grok/Kimi/Pi HistoryReader 变更。

**完成标准：**

- 同一会话实时显示与重新打开后的历史显示在块类型、顺序、状态和 usage 上等价。
- 工具卡片不会因 id 缺失/漂移永久显示 loading。
- 损坏的单条历史记录不会导致整个历史加载失败。

## 4. P2：生命周期和交互加固

### P2-01 权限/询问/计划审批临时 Alarm 未绑定 Disposable

**涉及文件：**

- `src/main/java/com/github/claudecodegui/handler/permission/PermissionActionHandlers.java`

**现状：**`showDialogWithFrontendCheck()` 每次创建独立 Swing `Alarm`，最多重试约 10 秒；成功、超时和窗口销毁时没有显式 dispose。Java Alarm 与 JavaScript `setTimeout` 还形成双重重试。

**代办：**

- [ ] 将 Alarm 绑定到窗口/handler 的父 Disposable，或复用一个生命周期受控的 Alarm。
- [ ] 成功、超时、session 切换、历史恢复、窗口 dispose 时取消对应请求。
- [ ] 回调执行前检查 context、session、turn 和 request id 是否仍有效。
- [ ] 收口 Java 与 JavaScript 两层重试，避免双重重试放大。
- [ ] 为 pending permission/ask/plan map 增加数量或超时观测。
- [ ] 增加窗口关闭、session 切换和 frontend 永不 ready 的测试。

**完成标准：**所有 pending future 和 Alarm 都有明确 owner、超时和终止路径。

---

### P2-02 DSH 操作 timeout 未取消且可能覆盖新操作状态

**涉及文件：**

- `webview/src/components/settings/DshProviderSection/index.tsx`

**现状：**启动、停止、保存等操作直接创建约 35 秒/65 秒 timeout，没有保存句柄或在 unmount 时清理。旧 timeout 还可能提前清除新操作的 `busy`。

**代办：**

- [ ] 使用 `useRef` 保存 timeout 句柄。
- [ ] 新操作开始前取消旧 timeout。
- [ ] effect cleanup/unmount 时清理 timeout。
- [ ] 引入 operation token/id，旧回调不得修改新操作状态。
- [ ] 后端响应到达时立即结束对应 operation，不依赖固定 timeout。
- [ ] 增加 unmount、连续点击、响应乱序测试。

**完成标准：**组件卸载后无迟到 state update，旧操作 timeout 不影响新操作。

---

### P2-03 Node Gateway 信号关闭语义可能不完整

**涉及文件候选：**

- `ai-bridge/mcp-gateway-server.js`
- `ai-bridge/mcp-gateway/ipc-server.js`
- `ai-bridge/mcp-gateway/server-supervisor.js`

**现状：**SIGINT/SIGTERM handler 调用 `ipc.close()` 后立即 `process.exit(0)`。若 transport/client close 含异步释放，可能被立即退出截断；Java 进程树强杀只是最终兜底。

**代办：**

- [ ] 将 shutdown 改为幂等 async 状态机。
- [ ] `IpcServer.close()` 返回可等待的 Promise。
- [ ] 等待所有 supervisor、transport 和 MCP 子进程退出。
- [ ] 设置 shutdown deadline，超时再强制退出进程树。
- [ ] 关闭期间拒绝新请求和新 supervisor 启动。
- [ ] 增加 SIGTERM 后子 MCP 进程归零的定向测试。

**完成标准：**正常信号关闭优雅释放，deadline 后仍有确定性强杀兜底。

---

### P2-04 fallback `NodeService` 不在 Disposer tree

**代办：**

- [ ] `resetInstance()` 在清除 fallback 引用前先调用 dispose。
- [ ] fallback 创建和启动长期资源时输出明确告警。
- [ ] 尽量将 fallback 注册到可用的 Disposable owner。
- [ ] 测试结束时断言 fallback 进程、sweeper 和 registry 均已清理。
- [ ] 增加 fallback 实际启动资源后 reset 的回归测试。

**完成标准：**测试/异常 bootstrap 路径不遗留后台线程或进程。

---

### P2-05 对话块状态机边界加固

**定向场景：**

- [ ] `thinking_start → thinking_delta* → text` 正常关闭 thinking。
- [ ] thinking start 后无 delta 也能正确结束。
- [ ] text 后进入 tool_use 前先 flush 文本。
- [ ] tool input 增量能在 tool_use end 时收口。
- [ ] tool_result 先到、tool_use 后到时可最终配对。
- [ ] 多工具并行、结果乱序不互相覆盖。
- [ ] tool_result 永不到时显示明确超时/中断状态，不永久 loading。
- [ ] stdout EOF、非零退出、JSON 截断都能结束 streaming 状态。
- [ ] `stream_end` 丢失时由会话层补发且只补一次。
- [ ] `stream_end` 重复/迟到不会结束新 turn。
- [ ] cancel/interrupt 会确定性终止进程树并关闭思考区、工具区 loading。
- [ ] usage 不因重复事件重复累加。

**完成标准：**所有异常结束路径都使前端退出 streaming/thinking/tool loading 状态。

## 5. P3：低风险清理与可观测性

### P3-01 Webview 初始化 timer 统一治理

**涉及文件候选：**

- `webview/src/main.tsx`
- `webview/src/hooks/useThemeInit.ts`
- `webview/src/utils/bridgeStartup.ts`

**代办：**

- [ ] 将 bridge/theme/bootstrap 重试统一纳入可取消 controller。
- [ ] 保证每类 bootstrap 只存在一个活动实例。
- [ ] `pagehide`/reload/unmount 时统一取消 timer。
- [ ] 开发模式 vConsole 延迟 timer 也纳入清理。
- [ ] 保留 `useDragSort` 当前 `AbortController` 和 preview cleanup，不退化为无法移除的裸匿名 listener。

---

### P3-02 生命周期与资源指标

- [ ] 为 Project、Session、Turn、Gateway process 和 CLI process 增加可关联的 lifecycle/generation id。
- [ ] 结构化记录 spawn、stdin close、stdout EOF、exit、terminate、rebuild、fallback、degraded。
- [ ] 记录活跃 Node/CLI/MCP 子进程数量。
- [ ] 记录 persistent registry size、淘汰次数和 rebuild cooldown 命中次数。
- [ ] 记录 pending permission/tool call/orphan tool result 数量。
- [ ] 记录 Gateway restart 次数、cold-start 耗时、catalog-ready 耗时和 direct 降级次数。
- [ ] 增加诊断命令或开发面板导出当前资源快照。

---

### P3-03 架构文档陈旧内容治理

**问题：**部分 skill 参考文档仍包含旧 `BaseSDKBridge` 或“三 Provider/SDK+CLI 六路径”描述，而根 `AGENTS.md` 和当前源码已是 8 Provider、统一 CLI/Channel 路径。

- [ ] 以根 `AGENTS.md` 和当前源码为准更新 skill references。
- [ ] 删除已退役 SDK daemon/BaseSDKBridge 作为现行架构的描述。
- [ ] 将 Provider 对称矩阵更新为 Claude、Codex、OpenCode、Grok、Kimi、Pi、OMP、DSH。
- [ ] 文档中明确 one-shot、persistent、channel/ACP 等有意架构差异及等价保护。

## 6. 推荐实施顺序

1. [ ] **阶段 A：生命周期闸门**——完成 P1-01，确保 dispose 后不会产生新资源。
2. [ ] **阶段 B：Gateway 非阻塞降级**——完成 P1-02，再验证 P2-03。
3. [ ] **阶段 C：Provider 能力和预热契约**——完成 P1-03、P1-04。
4. [ ] **阶段 D：实时/历史统一块**——完成 P1-05、P2-05。
5. [ ] **阶段 E：短时资源滞留清理**——完成 P2-01、P2-02、P2-04。
6. [ ] **阶段 F：可观测性与文档**——完成 P3 项。

每个阶段应独立提交，确保可单独 revert。功能、修复、重构、测试和文档按变更性质拆分 commit。

## 7. 定向验证清单

> 以下是候选范围。执行前根据实际改动选择最小集合，不应无差别全部执行。

### 7.1 Java 生命周期/Gateway

- [ ] `ProcessManagerRuntimeKeyTest`
- [ ] `ProcessManagerStaleChannelTest`
- [ ] `CliPersistentProcessRegistryTest`
- [ ] `McpGatewayProcessHandleTest`
- [ ] `McpGatewayServiceTest`
- [ ] 新增 project dispose during prewarm 测试
- [ ] 新增 stale onExit generation 测试
- [ ] 新增 Gateway slow catalog/direct fallback 测试

示例命令：

```powershell
.\gradlew.bat test --tests "com.github.claudecodegui.<package>.<TestClass>"
```

### 7.2 Java 对话状态/历史

- [ ] `SessionCallbackAdapterStreamEndTest`
- [ ] `StreamMessageCoalescerStreamEndHookTest`
- [ ] Kimi ACP thinking negotiation 测试
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

- [ ] `server-supervisor` 定向测试
- [ ] IPC snapshot timing 测试
- [ ] framing/http/stdio/transport 直接相关测试
- [ ] 新增 SIGTERM shutdown deadline 测试
- [ ] 新增 shutdown 后 MCP 子进程归零测试

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
