# 生命周期审计清单与 Codex 延迟验证记录

> 验证日期：2026-09-01
> 日志来源：`build/idea-sandbox/IC-2024.3.1/log/idea.log`
> 对照文档：`docs/lifecycle-provider-mcp-audit-todo-2026-08-28.md`

## 1. 结论

1. `lifecycle-provider-mcp-audit-todo-2026-08-28.md` **尚未全部完成**，当前不能据此认定插件已经“完全无泄漏、全部 Provider 完全对称、可无风险发布”。
2. Codex 的主要等待发生在 **Codex CLI 调用远端 API/模型生成阶段**，不在 Java 请求准备、JCEF 回显、MCP Gateway 配置构建或本地进程启动阶段。
3. 已发现 Codex Fast Mode 的实现风险：当前 Codex CLI 仅能通过通用 `-c` 透传 `service_tier`，但本机模型目录没有声明 `fast`/`priority` tier；强行传入会进入异常和长时间重试路径。
4. 已修复该风险：只有当前模型目录明确声明所请求的 tier 时才注入 `service_tier`；目录缺失、损坏、模型不匹配或 tier 未声明时均保守使用 Codex 标准模式。
5. 标准模式的 7～14 秒首轮耗时主要受 Codex 服务端模型和推理配置影响。继续优化应优先评估服务端能力、用户可选的 reasoning effort，以及经过协议验证的 Codex app-server/persistent 复用；不应在没有能力声明时继续强制注入实验性 service tier。

## 2. 审计清单验证

原审计文档仍存在以下未勾选验证项：

- Java 生命周期/流结束：
  - `ProcessManagerRuntimeKeyTest`
  - `ProcessManagerStaleChannelTest`
  - `SessionCallbackAdapterStreamEndTest`
  - `StreamMessageCoalescerStreamEndHookTest`
- Provider 历史与契约：
  - Grok/Kimi/Pi `HistoryReader` 测试
  - 其余 Provider parser/message-handler/history contract 测试
- Webview：
  - `ContentBlockRenderer.test.tsx`
  - `AgentGroupBlock.test.tsx`
  - `ServerToolsPanel.test.tsx`
  - `useToolsUpdate.test.ts`
  - DSH timeout cleanup/operation token 测试
  - tool id 配对与 orphan result 测试
- 发布前最终门禁：所有条目仍未完成或未记录充分证据。
- 工作树归属与最新 diff 风险复核：仍需在正式提交/发布前确认。

因此没有修改原审计文档的勾选状态。

## 3. Codex 延迟证据

### 3.1 插件内最新请求

`idea.log` 中 2026-09-02 01:14:10～01:14:24 的 Codex 请求：

| 阶段 | 相对发送耗时 |
|---|---:|
| send task started | 0 ms |
| attachments prepared / Gateway config | 约 0 ms |
| resume selection | 约 40 ms |
| process started | 约 53～81 ms |
| prompt written | 约 54～81 ms |
| first stdout line | 约 202～364 ms |
| 首个 thinking | 约 6.6 秒 |
| 首个 content delta | 约 12.7 秒 |
| process exited | 约 14.0 秒 |

说明本地链路很快：CLI 进程在约 0.2～0.36 秒内已经产生协议输出，随后约 6 秒才进入 thinking，再约 6 秒出现可见文本。瓶颈位于 Codex CLI/API/模型阶段。

### 3.2 MCP Gateway

启动时观察到一次性配置提交耗时：

```text
[McpGatewayPerf] applySnapshot ... postMs=5998
```

配置 hash 命中后，后续 `buildCliConfig`/snapshot 路径约为 0～1 ms。启动期间还出现过 `ai-bridge directory unavailable`，但 Gateway 随后自愈并监听 `127.0.0.1:11634`。这属于启动预热竞态/一次性成本，不是每轮 Codex 固定等待的主因。

### 3.3 脱离插件的 Codex CLI

定向探测结果：

```text
codex-cli 0.151.0
标准模式：约 7.3～8.4 秒
强制 service_tier="fast"：约 161 秒，并出现 error/retry 事件
```

即使脱离插件，Codex 标准模式仍有约 7～8 秒等待，进一步证明插件 Java/JCEF 主链路不是主要瓶颈。

## 4. 已实施修复

新增：

- `src/main/java/com/github/claudecodegui/cli/codex/CodexServiceTierPolicy.java`
- `src/test/java/com/github/claudecodegui/cli/codex/CodexServiceTierPolicyTest.java`

调整：

- `CodexCliSession` 的新会话与 `exec resume THREAD_ID` 路径统一通过 capability policy 决定是否注入 `service_tier`。
- `CliSendRequest`、`SessionRequest` 与各 Provider runtime 对齐新增字段；非 Codex Provider 显式传 `null`，避免横切参数遗漏。
- Codex 历史会话使用明确 thread ID resume，并在恢复失败后拒绝反复使用同一个失效 ID。

`CodexServiceTierPolicy` 默认读取：

```text
~/.codex/cc-switch-model-catalog.json
```

只有目录同时匹配当前模型和请求 tier 时才生成：

```text
-c service_tier="fast"
```

当前本机目录中所有模型的 `service_tiers` 和 `additional_speed_tiers` 均为空，因此不会再注入 Fast Mode 覆盖。

## 5. 验证结果

已通过：

```powershell
.\gradlew.bat compileJava --no-daemon

.\gradlew.bat test `
  --tests "com.github.claudecodegui.cli.codex.CodexServiceTierPolicyTest" `
  --tests "com.github.claudecodegui.cli.codex.CodexCliSessionTest" `
  --tests "com.github.claudecodegui.session.runtime.CodexCliSessionRuntimeTest" `
  --tests "com.github.claudecodegui.cli.CliSendRequestTest" `
  --no-daemon
```

结果均为 `BUILD SUCCESSFUL`。

`checkstyleMain` 本次无法重新执行，Gradle wrapper 锁文件 `D:\GradleCache\...\gradle-8.14.5-bin.zip.lck` 返回“拒绝访问”。此前完整构建中的 Checkstyle 失败包含工作树内其他既有改动，不能全部归因于本次 Codex 修复。

## 6. 后续可优化边界

- **可做且风险较低**：保留现有阶段耗时日志，按 model/reasoning effort/service tier 统计 TTFT；在 UI/后端能力模型中只展示目录明确声明的 tier。
- **需要用户明确选择**：把 Codex reasoning effort 调低可以减少部分生成等待，但会影响推理质量，不应作为隐式默认值修改。
- **需要单独技术验证**：Codex app-server/persistent 模式可能减少 one-shot 固定开销，但当前日志显示本地进程启动仅几十到几百毫秒，理论收益有限；在协议、取消、MCP、resume 与进程树治理验证完整前不应替换现有稳定路径。
- **插件无法消除的部分**：远端排队、模型首 token 延迟、账号/endpoint 不支持 Fast Mode。
