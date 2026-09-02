# 生命周期、8 Provider 预热与 Codex 延迟排查总结

> 排查日期：2026-09-02
> 工作区：`D:\project\jetbrains-melon-cc-gui`
> 日志目录：`build/idea-sandbox/IC-2024.3.1/log`
> 关联审计清单：`docs/lifecycle-provider-mcp-audit-todo-2026-08-28.md`

## 1. 结论摘要

本轮重点范围是：

1. 项目关闭 / IDE 关闭时，所有 Java 启动的 Node、Provider CLI、MCP/Gateway 子进程是否能够退出，并且关闭后不会被迟到任务重新创建。
2. Claude、Codex、OpenCode、Grok、Kimi、Pi、OMP、DSH 共 8 个 Provider 是否都有明确的启动预热策略。
3. 解释 Codex 对话首响应明显慢于 Claude、Kimi、OpenCode 的原因，并实施可以在插件侧安全完成的优化。

结论如下：

- **项目关闭和 IDE 关闭的代码治理已补齐主要缺口。** `ProcessManager` 现在拥有 channel、runtime、auxiliary 三类进程账本，具备不可逆 `disposed` 生命周期闸门、迟到注册拦截、stdin EOF、stdout/stderr drain、Windows 进程树终止和清理快照锁保护。`NodeService.dispose()` 负责 IDE / 插件级的全局兜底清理；`SessionRuntimeRouter` 已注册到 `Project` Disposable，项目关闭时会释放该项目的 `CliSessionManager` 和 CLI sessions。
- **8 个 Provider 已全部进入预热注册表。** Resolver 型 Provider 会预热可执行文件 / 版本探测；没有通用 resolver 的 OMP、DSH 使用 `channel-manager.js` 探针；所有策略均由 `ProviderType.values()` 做完整性校验，缺失或重复注册会 fail-fast；预热任务并行执行并受统一总 deadline 和 Project 生命周期控制。
- **现有日志不能证明本次 Grok 修复后的 8 个 Provider 已全部成功。** 日志中的两次 Grok timeout 发生在异步 drain 修复之前。修复后需要重新启动 IDEA，观察新的 `[BridgePreloader] Provider prewarm completed: GROK` 记录。
- **Codex 慢的主要原因不是插件端固定等待。** 日志显示 Codex CLI 启动约 53–81ms，首条 stdout 约 202–364ms，但首次 thinking 约 6.6s、首段可见文本约 12.7s、总耗时约 14s。瓶颈主要位于 Codex CLI/API/模型推理链路。插件侧已移除“模型未声明能力时仍强制注入 `service_tier=fast`”这一潜在降级 / 重试因素，但这不会把模型推理时间变成即时响应。
- **审计清单不是“全部完成”状态。** 与本轮核心范围直接相关的 P1-01、P1-03 代码整改已完成；但原审计清单仍保留若干与本轮无关的对话历史、Webview、额外测试和发布门禁条目。按照用户要求，本轮不处理测试文件，也不将旧日志中的 Grok timeout 当成修复后的证据。

## 2. 日志验证

### 2.1 8 Provider 预热日志

`idea.log` 中可确认以下 Provider 曾完成预热：

- `CLAUDE`
- `OMP`
- `DSH`
- `CODEX`
- `OPENCODE`
- `KIMI`
- `PI`

日志中两次启动批次均出现：

```text
2026-09-01 13:31:44 ... Provider prewarm timed out: GROK
2026-09-02 00:56:51 ... Provider prewarm timed out: GROK
```

这两条记录对应旧版 `ProviderCliResolver` 的同步 `readLine()` 路径：当 Windows shim 不输出换行且进程不退出时，`readLine()` 会先于外层 `waitFor(timeout)` 无限阻塞。因此旧日志只能证明 Grok 在旧实现中失败，不能证明当前修复后的实现仍失败。

### 2.2 项目关闭日志

日志中可见项目关闭和会话释放顺序：

```text
2026-09-02 10:08:22 ... Project closing, disposing chat windows
2026-09-02 10:08:22 ... Session dispose returned
2026-09-02 10:08:24 ... ProcessManager - Cleaning up all active processes...
2026-09-02 10:08:24 ... ProcessManager - Cleanup complete. Terminated 0 processes.
```

这说明当前一次关闭过程中，ToolWindow / Session dispose 已返回，随后执行了全局 ProcessManager 清理；当时账本中没有剩余需要终止的进程。该日志是“关闭路径正常返回、清理路径被执行”的证据，但不能单独覆盖“关闭瞬间仍有子进程、迟到异步注册和进程树残留”全部并发场景，后者由源码中的生命周期闸门、账本和终止逻辑保证。

## 3. 项目关闭与 IDE 关闭的资源边界

### 3.1 Project close

`SessionSendService` 创建 `SessionRuntimeRouter` 后，通过 `Disposer.tryRegister(project, runtimeRouter)` 将 router 绑定到项目生命周期。若项目已经处于销毁竞态，注册失败时立即调用 `runtimeRouter.dispose()`。

因此，Project close 会释放该项目拥有的：

- `CliSessionManager`；
- 该 manager 下的 CLI session；
- 各 Provider runtime 关联的会话资源；
- 对应的 Provider CLI 子进程。

项目级 dispose **不会**调用全局 `ProcessManager.cleanupAllProcesses()`，避免关闭一个项目时误杀其他项目的资源。

### 3.2 IDE / 插件 unload

`NodeService` 作为 application-scoped service 的 dispose owner，在 IDE 关闭或插件卸载时调用：

```java
processManager.cleanupAllProcesses();
```

该路径是全局兜底，会清理所有仍登记在 `ProcessManager` 中的 channel、runtime 和 auxiliary 子进程。

## 4. ProcessManager 子进程治理

当前实现包含三类账本：

| 账本 | 典型资源 | 生命周期用途 |
|---|---|---|
| channel | channel-manager / Provider channel 进程 | 跟踪按 channel 建立的 CLI 通道 |
| runtime | Provider session runtime 进程 | 跟踪会话级 Provider CLI 进程 |
| auxiliary | 版本探测、8 Provider channel prewarm 等短时探针 | 防止预热和检测进程脱离统一清理 |

关键保护如下：

- **不可逆 disposed 闸门**：清理开始后，新的 channel/runtime/auxiliary 注册会被拒绝；迟到创建的进程会立即终止，不会重新进入账本。
- **并发快照保护**：清理账本快照与注册使用同一生命周期锁，避免“清理已完成后，异步线程又完成注册”的窗口。
- **stdin EOF**：所有预热 / 探测以及相关 CLI 路径在不需要继续输入时关闭 stdin，避免子进程阻塞等待输入。
- **stdout/stderr drain**：异步消费两个输出管道，避免子进程因管道写满而阻塞；不在外层超时等待前同步调用可能无限阻塞的 `readLine()`。
- **Windows 进程树终止**：使用项目既有 `ProcessManager.terminateProcess` / `PlatformUtils.terminateProcessAndWait` 路径终止整棵进程树，并处理 `conhost.exe` 清理，避免只杀包装进程后留下 Provider / Node 子进程。
- **进程退出清理**：正常退出、超时、中断和异常路径均尝试终止、等待、回收 drain 线程并 unregister auxiliary 记录。
- **stale sweeper**：对异常残留的 channel 记录进行周期性兜底清理。

## 5. 8 Provider 预热矩阵

| Provider | 当前预热实现 | 预热内容 | 超时 / 失败策略 |
|---|---|---|---|
| Claude | `ClaudeCliDetector.getInstance().findCliExecutable()` | Claude CLI 可执行文件探测 | `RETRY_ON_FIRST_USE` |
| Codex | `CodexCliResolver.findExecutable()` | Codex 可执行文件 / 版本探测 | `RETRY_ON_FIRST_USE` |
| OpenCode | `OpenCodeCliResolver.findExecutable()` | OpenCode 可执行文件 / 版本探测 | `RETRY_ON_FIRST_USE` |
| Grok | `ProviderCliResolver(ProviderType.GROK, ...).findExecutable()` | Grok 可执行文件 / 版本探测 | `RETRY_ON_FIRST_USE`；当前实现已改为异步 drain |
| Kimi | `ProviderCliResolver(ProviderType.KIMI, ...).findExecutable()` | Kimi 可执行文件 / 版本探测 | `RETRY_ON_FIRST_USE`；ACP 能力仍以实际 Session 协商为准 |
| Pi | `ProviderCliResolver(ProviderType.PI, ...).findExecutable()` | Pi 可执行文件 / 版本探测 | `RETRY_ON_FIRST_USE` |
| OMP | `channel-manager.js OMP listModels` | channel-manager 通道探针 / 模型列表入口 | `DIRECT_CHANNEL` |
| DSH | `channel-manager.js DSH status` | channel-manager 通道状态探针 | `HOST_CHANNEL` |

实现要点：

- `ProviderPrewarmRegistry` 以 `ProviderType.values()` 作为 Provider 全集；构造时检查重复和缺失。
- 预热策略通过接口 / 注册表装配，核心 `BridgePreloader` 不再维护 Provider 专用 if/else 分派。
- 所有预热任务并行提交。
- `BridgePreloader` 使用所有策略 timeout 的最大值计算统一总 deadline，避免按任务列表顺序等待而产生 timeout 累加。
- OMP / DSH 的 channel 探针通过 `ProviderChannelPrewarm` 启动 `channel-manager.js`，设置环境、关闭 stdin、drain stdout/stderr，并在取消 / 超时 / 异常时终止进程树。
- 预热失败不会写入成功 detector cache，首轮实际调用仍可重试并使用明确 fallback。

## 6. Grok 预热超时根因与修复

旧实现的关键问题是：

```java
reader.readLine();
process.waitFor(5, TimeUnit.SECONDS);
```

当 Grok 的 Windows shim 只写入不带换行的版本内容，或保持进程存活而不立即退出时，`readLine()` 会无限等待，外层 5 秒 timeout 根本无法生效。

当前 `ProviderCliResolver` 已调整为：

1. stdout 独立 daemon 线程持续 drain，只记录第一行版本内容；
2. stderr 独立 daemon 线程持续 drain，防止错误管道背压；
3. 主线程直接执行有界 `process.waitFor(5, TimeUnit.SECONDS)`；
4. 超时、中断和 finally 都调用 `PlatformUtils.terminateProcessAndWait(...)`；
5. drain 线程最多 join 500ms；
6. auxiliary process 最终 unregister。

因此，当前 Grok 路径的取消和超时是可控的，不会再次被同步 `readLine()` 卡死。仍需下一次实际启动日志验证 Grok 是否能在预算内正常退出。

## 7. Codex 延迟分析与已实施优化

### 7.1 日志时间分解

对 `idea.log` 中 Codex 会话的测量结果：

| 阶段 | 观测耗时 |
|---|---:|
| Java 启动 Codex CLI | 约 53–81ms |
| CLI 首条 stdout | 约 202–364ms |
| 首次 thinking | 约 6.6s |
| 首段可见文本 | 约 12.7s |
| 进程总耗时 | 约 14s |

这个时间分布表明：插件没有在发送前固定 sleep 十几秒；CLI 已经很快启动并产出 stdout，长时间主要发生在服务端请求 / 模型推理 / thinking 到可见文本之间。

### 7.2 代码侧优化

新增 `CodexServiceTierPolicy`，读取模型能力目录，仅当选中模型明确声明请求的 service tier 时才允许注入配置：

```text
service_tier=fast
```

此前即使模型能力未声明，也可能强制注入 `fast`。这种不兼容配置可能造成 CLI/API 降级、重试或等待。现在改为“能力明确声明才注入，否则忽略并记录日志”，避免因为错误能力假设放大延迟。

该优化是安全的 fail-closed 策略，但它不是模型推理加速器：如果 Codex 后端本身需要较长 thinking 时间，仍会保留该耗时。

### 7.3 能否继续优化

可以继续做，但下一步重点不应是继续增加本地线程或修改 JCEF，而应基于新的结构化时间点日志区分：

- CLI 进程 spawn 到首个 `thread/turn` 事件；
- 请求发送到 `thinking` 开始；
- `thinking` 到首个 `message` / `text_delta`；
- MCP catalog / tool 注入是否延迟首 token；
- Codex 模型、reasoning effort、service tier 是否对应不同后端耗时。

当前已完成的插件侧优化包括消除错误 service tier 注入、避免重复 resume / session 参数造成重试风险、以及保证 MCP Gateway 慢启动不会阻塞首条消息超过有限预算。没有足够日志证据时，不建议通过降低超时、强制切换模型或关闭 thinking 作为默认修复，因为这些会改变用户语义或掩盖后端耗时。

## 8. 与原审计清单的完成度判断

### 本轮核心范围

| 范围 | 判断 | 依据 |
|---|---|---|
| Project close 后释放项目 CLI 子进程 | 已完成代码整改 | `SessionRuntimeRouter` 注册 Project Disposable，释放 `CliSessionManager` |
| IDE / 插件关闭全局清理 | 已完成代码整改 | `NodeService.dispose()` 调用 `ProcessManager.cleanupAllProcesses()` |
| 关闭后禁止迟到进程注册 / 复活 | 已完成代码整改 | `ProcessManager.disposed` 闸门与锁保护 |
| 8 Provider 预热策略完整注册 | 已完成代码整改 | `ProviderType.values()` 完整性校验覆盖 8 个 Provider |
| 预热任务可取消且可清理 | 已完成代码整改 | Project Disposable、Future cancel、auxiliary 账本、进程树终止 |
| Grok 预热不被无限 `readLine()` 阻塞 | 已完成代码整改，待运行日志复核 | 异步 stdout/stderr drain + 有界 wait |
| 8 Provider 实际运行均成功 | 尚未完成证据闭环 | 旧日志仍记录 Grok timeout，修复后尚无新一轮日志 |

### 原清单整体状态

不能将 `lifecycle-provider-mcp-audit-todo-2026-08-28.md` 判定为“所有条目全部完成”。截至本次排查，该文件仍明确保留：

- 若干未执行或与本轮无关的 Java 对话状态 / HistoryReader 测试项；
- 若干未执行的 Webview 测试项；
- 发布前最终门禁中的未勾选项；
- 当前工作树未提交改动归属复核等审计性条目。

按用户要求，本轮**不处理测试文件、不恢复测试改动、不为了清除无关未完成项而扩大修改范围**。本总结仅确认本轮两项核心工程任务的代码整改和日志边界。

## 9. 验证记录

已执行：

```powershell
.\\gradlew.bat compileJava --no-daemon
git diff --check
```

结果：

- `compileJava`：`BUILD SUCCESSFUL`；
- `git diff --check`：通过；存在 Git 对 CRLF/LF 转换的 warning，但没有 whitespace error。

测试文件按用户要求未处理，也未将测试文件作为本轮整改目标。建议后续只做一次新的 IDEA 启动 / 关闭实测，重点确认：

1. 8 条 `Provider prewarm completed` 是否包含 `GROK`；
2. 项目关闭时是否仍出现新的 `spawn` / `register` 日志；
3. IDE 退出后 Windows 进程列表中是否残留 Node、Provider CLI、`conhost.exe`。

## 10. 涉及的核心代码文件

- `src/main/java/com/github/claudecodegui/bridge/ProcessManager.java`
- `src/main/java/com/github/claudecodegui/bridge/NodeService.java`
- `src/main/java/com/github/claudecodegui/session/SessionSendService.java`
- `src/main/java/com/github/claudecodegui/session/runtime/SessionRuntimeRouter.java`
- `src/main/java/com/github/claudecodegui/startup/BridgePreloader.java`
- `src/main/java/com/github/claudecodegui/startup/ProviderPrewarmRegistry.java`
- `src/main/java/com/github/claudecodegui/startup/ProviderChannelPrewarm.java`
- `src/main/java/com/github/claudecodegui/cli/common/ProviderCliResolver.java`
- `src/main/java/com/github/claudecodegui/cli/codex/CodexServiceTierPolicy.java`

> 说明：工作树中还存在其他既有未提交改动；本轮没有重置、覆盖或清理这些改动，也没有修改测试文件。
