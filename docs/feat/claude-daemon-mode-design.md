# CLI 长驻会话模式设计(消除每轮冷启动)

> **目标**: 同一会话的多轮对话不再每轮重复支付 CLI 进程冷启动开销;后台静默加载,用户无感等待。
> **路线**: 直接接入各家 CLI 的**官方长驻机制**(stream-json 交互模式 / serve / app-server),不自研 daemon、不直调 API。
> **本文档取代本文件早前的「自研 daemon 池 + 直调各家 API」方案**,否决理由见附录 A。
> **会话隔离红线**: 会话之间绝不共享串行队列——旧 SDK daemon 模式因全局 `commandQueue` 串行链导致"第一个会话未完成堵塞后续所有会话"(已考古确认,见 §2.3),本方案从架构上杜绝复刻。

---

## 1. 实测数据与结论(2026-08-17,Windows,claude CLI 2.1.233,glm-5.3 端点)

| 测试 | 进程启动→init | init→API 静默期 | 说明 |
|------|:---:|:---:|------|
| one-shot 新会话 | **3.4s**(含 SessionStart hook ~1s) | 7.2s | input_tokens ≈ 60k(干净目录) |
| one-shot `--resume` | **3.2s** | 5.3s | **前置开销与新会话几乎相同——每轮都在重复付** |
| **长驻次轮**(stream-json input) | **0.2s** | 10.3s | 消息写入→init 即回 |

结论:

1. **CLI 进程级冷启动 ≈ 3.4s/轮**,构成 = Node 进程启动 + SessionStart hook (~1s) + 工具注册/配置加载。长驻模式下降至 0.2s(**-94%**),且 hook、工具加载、CLAUDE.md 解析每会话只发生一次。
2. **init 之后的 5~10s 静默期是 API 侧 prompt 上传 + cache 创建**(60k+ tokens;项目内含 CLAUDE.md/AGENTS.md 会更大)。这部分**不在进程内、任何本地方案都消除不了**,只能靠 prompt 瘦身与 API 端 prompt cache 命中缓解。本文档目标只针对第 1 项。
3. 前端「正在连接/启动 AI 运行时」阶段 ≈ 插件前置(检测/配置,已预热后 <1s)+ CLI 3.4s;「正在理解问题」阶段 ≈ API 静默期 + 思考期。长驻化后前者近乎归零,后者不变。

**协议验证(同日完成,详见 §4.3 与 §7)**: ① turn 中断 control_request 存在且 1ms 回执、进程存活同 session 续聊;② `--resume` 与 stream-json 组合可用、历史上下文接续正确;③ 同一长驻进程多轮自动共享同一 session_id(长驻模式下会话天然延续,无需每轮 `--resume`);④ CLI 启动后先等 stdin、init 在首条消息之后才发(spawn 即就绪,无需等 init)。

---

## 2. 推荐方案总览

### 2.1 各 provider 官方长驻机制(均已在开发机确认存在)

| Provider | 官方机制 | 版本确认 | 会话隔离模型 |
|----------|---------|---------|-------------|
| claude | `claude --input-format stream-json --output-format stream-json --verbose` 交互式长驻(Agent SDK 的底层机制) | 2.1.233 ✅ 实测 | **每 tab 一个进程**:会话 = 进程,物理隔离 |
| opencode | `opencode serve --port 0`(官方 headless HTTP+SSE server,多会话并发是本职) | 1.18.18 ✅ | 服务级多会话,session id 协议层路由 |
| grok / kimi / pi | 与 opencode 同构,`serve` 机制直接复用 | ✅ | 同上 |
| codex | `codex app-server`(官方 experimental daemon,含 `daemon` 子命令) | 0.147.0 ✅ | JSON-RPC 多会话,conversationId 路由 |

### 2.2 为什么不自研 daemon 层

- **不自建协议**: 三家官方机制现成,Java 直接与长驻 CLI 进程/服务对话;无需 NDJSON 自定义协议、无需 `ConversationManager` 内存会话。
- **transcript 体系原生保留**: 会话文件仍由各家 CLI 写磁盘,历史列表(ClaudeHistoryService)、会话续接(`--resume`)、崩溃恢复全部继承现状。这是相对旧 daemon 方案的决定性优势(旧方案内存会话与 CLI transcript 是两套平行体系)。
- **不自建队列**: 见下。

### 2.3 历史教训:旧 SDK daemon 堵塞根因(必须写进设计,防止复刻)

被删的 `ai-bridge/daemon.js`(commit `5a3fc72f4`)第 777 行:

```javascript
commandQueue = commandQueue
  .then(async () => { ... return processRequest(request); })  // 全局串行链
```

所有 provider 所有会话的请求在**一条全局 Promise 链**上串行——会话 A 的流没吐完,会话 B 的请求挂在链上。这是当年"第一个会话未完成堵塞后续会话"的确切机制,是 daemon 层的实现选择,**不是 Node.js 或协议的限制**。

**红线**: 本方案任何一层(Java 侧 / ai-bridge 侧)都**不允许出现跨会话的串行队列**。唯一的串行边界是 `CliSessionManager` 现有的 **per-tab inFlight 链**(`CliSessionManager.java:102-136`)——同 tab 内消息天然排队(这是正确语义:同一会话本就该顺序执行),跨 tab 完全并行。

---

## 3. 核心设计原则

### 3.1 会话隔离模型

- **claude: tab = 进程**。每个 tab 每个 provider 至多一个长驻 CLI 进程。进程边界即会话边界:一个 tab 卡死、崩溃、被杀,物理上不可能影响其他 tab。tab 关闭 = 进程关闭。
- **codex / opencode 系: 官方多会话协议**。会话 id 在协议层路由,并发是官方设计目标;插件侧只做连接复用,不做请求排队。
- 进程数上限保护: 单项目 CLI_SESSION 进程上限 **8 个**,超限后新 tab 自动降级 one-shot(不报错、不打扰)。

### 3.2 静默加载策略(用户无感)

总原则: **一切预热/重建/回收都在后台线程发生,用户的当前消息永远有立即响应的路径(one-shot fallback),绝不为等待长驻进程就绪而阻塞。**

| 时机 | 行为 | 用户感知 |
|------|------|---------|
| 首条消息(tab 内) | 立即 spawn 长驻进程并写入消息 | 一次性 ~3.4s,与现状相同,**不劣化** |
| 后续消息 | 同进程 stdin 续写 | 冷启动 0.2s,近乎即时 |
| 参数变化(model/permission) | 当前消息走 one-shot(新参数);**同时后台按新参数重建长驻进程** | 与现状相同;下一条消息恢复长驻 |
| 长驻进程崩溃 | 当前消息走 one-shot `--resume`(sessionId 已知);后台静默重建 | 无感,会话不丢(transcript 在磁盘) |
| abort | control_request interrupt 中断当前轮(§4.3,V1 已验证);**进程保留、session_id 保留**,下一条消息直接续聊;异常态(3s 无回应)才退杀进程+重建 | 优于现状(现状每轮重启进程) |
| 空闲回收(idle 30min) | 静默优雅关闭;下条消息 one-shot + 后台重建 | 无感 |
| 可选增强(Phase 1.5) | 新建 tab / 输入框获得焦点时**预 spawn**(用户还没打完字,进程已 ready) | 首条消息也 0 等待 |

后台重建统一走 `CliSessionExecutor` 池,不占 EDT,不占 ForkJoinPool.commonPool。

### 3.3 明确不做的事

- ❌ 不直调各家 API(认证断崖/能力断崖,附录 A)
- ❌ 不自建 NDJSON daemon 协议、不做内存会话管理
- ❌ 不做跨会话串行队列
- ❌ 不为"所有 provider 全量预热"而在项目启动时 spawn 6 个进程(按 tab 实际使用的 provider 按需长驻)

---

## 4. Phase 1 详细设计: claude 长驻会话

### 4.1 进程命令行与消息协议

**spawn 参数**(沿用现有 `ClaudeCliSession.buildCommand` 的非一次性参数,去掉 prompt 位置参数;**保留 `-p`**——help 标注 input-format 仅在 `--print` 下生效,实测 `-p` + stream-json 长驻组合全链路可用,interrupt/resume 均在此模式下验证通过):

```
claude -p
  --input-format stream-json
  --output-format stream-json
  --verbose
  --include-partial-messages      # 模型支持时,与现状一致(注意:参数名是 messages,不是 events)
  [--model <model>]               # 沿用 resolveProfile
  [--permission-mode ...]         # 沿用 ClaudeCliPermissionMode.apply
  [--add-dir ...]                 # 沿用附件目录逻辑
  [--resume <sessionId>]          # 续接已有会话时(见 §4.6)
  [--mcp-config <path>]           # gateway 模式沿用 buildGatewayConfig
```

环境变量构建完全复用现有 `CliEnvironmentBuilder` 链路(base env + CLI settings + model env + permission env)。

**上行(Java → CLI, stdin 每行一条)**:

```json
{"type":"user","message":{"role":"user","content":[{"type":"text","text":"用户输入"}]}}
```

附件图片映射为 content blocks 的 `{"type":"image","source":{...}}`(沿用现有 `CliAttachmentHandler.ContentBlock` 产出)。

**下行(CLI → Java, stdout 每行一条)**: 事件格式与现有 one-shot stream-json **完全相同**(system/assistant/result)——现有解析器直接复用。核心差异只有一点:

> **轮完成信号从「进程退出」改为「`result` 事件(`subtype: success`)」**。每轮 turn 结束 CLI 输出 result 行但进程不退出,等待下一条 stdin 消息(实测确认:次轮消息写入后 0.2s 内 CLI 重发 init 事件并开始处理;验证实测中消息写入→init 仅 17ms~111ms)。

**时序要点**(实测确认): CLI 启动后**先等 stdin 再发 init**——init 事件在收到第一条 user 消息**之后**才发出。因此长驻进程 spawn 后即就绪,首条消息随时可写,不需要等 init 才可发送。

**control 协议**(实测确认,2.1.233): init 事件的 `capabilities` 数组宣告 `["interrupt_receipt_v1","interrupt_cancel_queued_v1","msg_lifecycle_v1"]`。stdin 上除 user 消息外还可写 control_request 行(格式见 §4.3),CLI 以 control_response 行应答。

**优雅关闭**: 关闭 stdin(EOF)→ CLI 自然退出(实测确认)→ 5s 未退则 `terminateProcessTree` 兜底(复用 Windows 父死孤儿清理基建)。

### 4.2 Java 侧类设计

```
src/main/java/.../cli/common/
  CliPersistentProcess.java        # 新增:provider 无关的长驻进程句柄
                                   #   Process + 行读取线程 + stdin writer
                                   #   按"轮"关联 callback;超时/异常上抛
  CliPersistentProcessRegistry.java # 新增:tabId+provider → CliPersistentProcess
                                   #   进程数上限、空闲回收调度、Disposable
src/main/java/.../cli/claude/
  ClaudePersistentSendPath.java    # 新增:长驻发送路径(与 one-shot 并列的分支)
```

**CliPersistentProcess 职责边界**(关键:它不是 daemon,没有 method 分发,没有会话管理):

```java
public final class CliPersistentProcess {
    // 生命周期
    boolean start(List<String> cmd, Map<String,String> env, String cwd, long readyTimeoutMs);
    void closeGracefully();               // stdin EOF → 等 5s → kill 兜底
    boolean isAlive();

    // 轮协议:一行进,事件流出到 callback,以 result 行结束本轮
    TurnHandle startTurn(String stdinLine, CliSessionCallback callback);
    void interruptTurn();                 // abort:发 control_request interrupt,进程保留(见 §4.3)

    // 进程面板元数据(注册到 NodeProcessRegistry 用)
    PersistentProcessInfo describe();     // pid/provider/tabId/sessionId/state(STARTING/IDLE/STREAMING)/startedAt
}
```

- 读行线程解析后**逐事件分发给当前轮的 callback**——分发即回归调,无缓冲无排队。
- `TurnHandle` 上带 `CompletableFuture<SDKResult>`,由 result 行完成;超时沿用 `CliConstants.CLI_REQUEST_TIMEOUT_MS`。
- **同一进程同一时刻只允许一个活跃轮**(CLI 交互模型本就如此);第二个请求到达时若上一轮未结束,由上层 per-tab inFlight 链保证不会发生——`CliPersistentProcess` 内再加一道断言防御(抛错优于静默交错)。

**ClaudeCliSession 改造**(最小侵入):

```java
public CompletableFuture<SDKResult> send(CliSendRequest request, CliSessionCallback callback) {
    CliPersistentProcess proc = registry.acquire(tabId, CLAUDE, request);  // 命中或按需/后台启动
    if (proc != null && proc.isAlive() && signatureMatches(proc, request)) {
        return proc.startTurn(buildUserMessageLine(request), callback);    // 长驻路径
    }
    // fallback: 现有 one-shot 路径原样保留(参数变化/进程未就绪/崩溃后)
    registry.rebuildInBackground(tabId, CLAUDE, request);                  // 静默重建,不阻塞当前消息
    return executeOneShotProcess(request, callback);
}
```

现有 one-shot 的全部逻辑(附件、prompt 构建、`--resume` 失败重置 sessionId、诊断日志)不动,作为永久 fallback 路径存在。

### 4.3 abort(V1 验证通过,定稿:进程保留式中断)

stream-json input 模式存在**不杀进程的 turn 中断协议**(实测确认,2026-08-17,claude 2.1.233 + glm-5.2)。协议从 CLI 二进制内嵌 SDK 代码考古获得并端到端验证:

**中断请求**(stdin 写入,与 user 消息同为单行 JSON):

```json
{"type":"control_request","request_id":"<id>","request":{"subtype":"interrupt"}}
```

- 注意 `subtype` 必须嵌在 `request` 对象内——顶层放 `method` 或平铺 `subtype` 均会触发 CLI 解析报错(`TypeError: evaluating 'e.request.subtype'`)。
- 可选 `"cancel_queued":true` 于 request 内(需 `interrupt_cancel_queued_v1` 能力,2.1.233 已宣告):同时取消排队中的消息,回执 `cancelled` 字段列出被取消项。常规 abort 不带此参数。

**中断回执与轮收尾**(实测时序,轮进行 6.6s 时发 interrupt):

```
T+1ms    control_response: {"subtype":"success","request_id":"...","response":{"still_queued":[]}}
         (still_queued = 中断后仍存活的排队 user 消息 uuid 数组,interrupt_receipt_v1 能力)
T+6ms    result 事件: subtype=error_during_execution, is_error=true, duration_ms=已运行时长
         (流即刻停止,进程不退出)
```

**进程存活与续聊**(实测): interrupt 后进程 pid 不变;写入下一条 user 消息 → CLI 重发 init(**同一 session_id**,上下文延续)→ 正常完成下一轮。**无需重建、无需 `--resume`**。

**实现**: `interruptTurn()` = 写 control_request 行 + 等待 result 事件(轮 future 以 `error_during_execution` 完成即视为中断成功);前端 abort 语义映射为该 result。兜底: interrupt 写入后 3s 无 result 回应(异常态)→ 退回杀进程树 + 槽位 dirty + `--resume` 静默重建(原方案作为异常兜底保留)。

### 4.4 参数指纹与进程重建

借鉴旧 daemon 的 runtimeSignature 概念但大幅收窄:指纹仅包含 **provider + model + permission-mode + cwd + add-dirs + mcp-config 路径**。指纹不匹配时不复用旧进程:

1. 当前消息走 one-shot(新参数);
2. 后台关闭旧进程、按新指纹重建;
3. 后续消息恢复长驻。

切换 provider = 不同槽位(`tabId + provider` 二级键,与 `CliSessionManager.sessions` 结构对齐),互不影响。

### 4.5 空闲回收

- `CliPersistentProcessRegistry` 持有每进程 `lastActiveAt`,`ScheduledExecutorService` 每 5min 扫描:
  - idle > 30min → `closeGracelessly()`(静默);
  - 进程已死 → 移除槽位。
- 回收不追求激进:一个空闲 claude 进程 ~100-200MB,30min 内大概率还会用;回收后下条消息有 one-shot 兜底,用户无感。
- 项目关闭(`Disposable.dispose`)→ 全部优雅关闭 + `cleanupChildProcesses` 兜底。

### 4.6 与现有体系的兼容

| 现有能力 | 长驻模式下的表现 |
|---------|----------------|
| 历史会话列表(ClaudeHistoryService 读 CLI transcript) | **自动兼容**: 长驻会话的 transcript 由 CLI 原生写盘,与 one-shot 无异 |
| 会话续接(tab 重开加载历史 → 发消息) | spawn 时带 `--resume <sessionId>`(**V2 验证通过**: `--resume` 与 stream-json input 组合可用——INIT 的 session_id 与目标完全一致,历史上下文接续正确;消息写入→init 仅 111ms) |
| 会话标题(CliSessionTitleService) | 沿用 sessionId,无变化 |
| `--resume` 失败重置 sessionId 自愈(ClaudeCliSession.java:686-711) | 长驻路径同样捕获 resume 失败事件 → 杀进程重建新会话,逻辑对齐 |
| MCP gateway 注入(buildGatewayConfig) | spawn 参数透传,每轮无需重传(gateway 本身已常驻) |
| CliConcurrencyDiag 诊断日志 | 长驻路径增加等价埋点(startTurn/事件到达/轮完成耗时) |

---

## 5. Node 进程管理面板集成(只读展示)

用户要求: **长驻 CLI 进程在现有输入区设置的 Node 进程管理中可见,但只允许查看,不允许删除/杀死。**

### 5.1 数据层

- `NodeProcessInfo` 的 kind 枚举新增 **`CLI_SESSION`**(与现有 `DAEMON | CHANNEL | ORPHAN` 并列;前端 `nodeProcessCapabilities.ts` 的 `NodeProcessKind` 同步扩展,protocol.ts 经 mjs 生成流收敛)。
- `NodeProcessRegistry.snapshot()` 合并新数据源 `CliPersistentProcessRegistry.describeAll()`,字段映射:

```
kind=CLI_SESSION, provider, pid, startedAt, uptimeMs,
sessionId, tabName(复用 resolveTabName), channelId=tabId,
activeRequestCount(0/1=IDLE/STREAMING), orphan=false
```

- **orphan 扫描排除**: `OWNED_PROCESS_HINTS` 匹配逻辑中,CLI_SESSION 进程已由 registry 追踪,天然非 orphan;`killAllOrphans` 不会触及。

### 5.2 kill 双层防护

1. **后端闸门**(真正防线): `NodeProcessRegistry.killByPid(pid)` 在现有 ownership 检查之后追加 kind 检查——pid 属于 CLI_SESSION → 返回 false 并下发 killResult `{success:false, error:"cli_session_protected"}`;`terminateTrackedPid` 保持 private,不新增任何绕过路径。
2. **前端 UI**: `NodeProcessSelect` 中 `CLI_SESSION` 分组渲染查看行(provider/tab 名/sessionId 缩写/uptime/状态点),**不渲染 Trash 按钮**;分组头标注"自动管理"。

### 5.3 i18n

新增 key(`nodeProcess.*` 命名空间): 分组标题、状态标签(IDLE/STREAMING)、保护提示文案,10 locale 对称(en 为 SSOT,coverage gate 守门)。

---

## 6. Phase 2/3 概要

### Phase 2: opencode serve(grok/kimi/pi 同构复用)

- `opencode serve --port 0` 单实例常驻(`--port 0` 自动分配,parseServingPort 已有先例),HTTP+SSE 多会话——SDK 时代项目已接过此契约(opencode real api contract),回归已验证路径。
- 会话并发由服务端保证;插件侧**只做 HTTP 客户端连接池,不做任何排队**。
- Node 进程面板: serve 进程以 `kind=DAEMON` 归类(opencode 系共用一个),同样只读保护。

### Phase 3: codex app-server

- `codex app-server` JSON-RPC over stdio,experimental——**锁版本验证协议稳定性后再接入**;conversationId 多会话路由。
- 在 Phase 1/2 数据(长驻收益 vs one-shot 基线)出来后评估优先级;codex 检测器预热已把首轮从 3494ms 降到 339ms,紧迫性最低。

---

## 7. 风险与待验证项

| # | 事项 | 等级 | 处置 |
|---|------|------|------|
| V1 | ~~stream-json input 模式的 turn 中断控制消息是否存在~~ **已验证 ✅(2026-08-17)**: `{"request":{"subtype":"interrupt"}}` 格式可用,1ms 回执,被中断轮以 `error_during_execution` result 收尾,进程存活同 session_id 续聊(详见 §4.3) | 中 | 已定稿为进程保留式中断;杀进程仅作异常兜底 |
| V2 | ~~`--resume` 与 stream-json input 组合是否可用~~ **已验证 ✅(2026-08-17)**: 组合可用,INIT session_id 完全一致,历史上下文接续正确("reply with exactly: ok" 会话续问正确答出 "ok") | 中 | 历史续接场景同样走长驻,无需 one-shot 降级 |
| V3 | 长驻进程 stderr 混流(JSON 解析容错) | 低 | redirectErrorStream 现状已有容错;长驻读行线程复用同一容错策略 |
| V4 | CLI 版本演进破坏交互模式(非官方承诺级 API) | 中 | compatibility manifest 已有版本门控机制,纳入 `CliCompatibilityManifestUpdater`;指纹校验失败自动 one-shot |
| V5 | Windows .cmd 垫片下的 stdin 长期保持 | 低 | 实测已在 Windows 完成(本文 §1 数据即 Windows 实测);`cleanupChildProcesses` 兜底孤儿 |
| — | API 静默期(5~10s)依旧存在 | 说明 | 非本方案目标;prompt 瘦身与 cache 命中另行优化 |

**回退开关**: 行为菜单加三层门禁开关(对齐 gateway 行为开关先例),默认开启,`-D` 可关停回 one-shot 纯路径。

---

## 8. 分阶段验收标准

- **Phase 1 (claude)**
  - [ ] 同 tab 第 2 条消息起:消息写入→init 事件 < 500ms(实测基线 0.2s)
  - [ ] 两个 tab 并发对话互不阻塞(会话隔离核心验收;对照旧 daemon 堵塞场景)
  - [ ] 同 tab 快速连发两条:第二条等待第一条完成(per-tab 串行,不交错不丢)
  - [ ] 长驻进程被外部 kill → 下条消息 one-shot `--resume` 成功续聊,后台重建完成
  - [ ] 历史列表可见长驻会话;tab 重开可续接
  - [ ] abort → control_response 回执 < 100ms;被中断轮 result=error_during_execution;**同进程**下条消息正常续聊(session_id 不变);进程面板不出现孤儿
  - [ ] Node 进程面板显示 CLI_SESSION 分组;kill 按钮 UI 不渲染;后端 kill 返回 cli_session_protected
  - [ ] 项目关闭零残留进程
- **Phase 2/3**: 对齐上述口径,按 provider 补充协议级验收。

---

## 9. 文件清单

### 新增

| 文件 | 说明 |
|------|------|
| `src/main/java/.../cli/common/CliPersistentProcess.java` | 长驻进程句柄(轮协议/优雅关闭/元数据) |
| `src/main/java/.../cli/common/CliPersistentProcessRegistry.java` | 槽位管理/指纹匹配/空闲回收/上限 |
| `src/main/java/.../cli/claude/ClaudePersistentSendPath.java` | claude 长驻发送路径(user 消息行构建/事件分发) |
| `src/test/java/.../cli/common/CliPersistentProcessRegistryTest.java` | 槽位/指纹/回收/上限单测 |
| `src/test/java/.../handler/nodeprocess/NodeProcessKillGuardTest.java` | CLI_SESSION kill 防护单测 |

### 修改

| 文件 | 改动 |
|------|------|
| `ClaudeCliSession.java` | send() 双路径分流(长驻/one-shot),长驻事件解析复用 |
| `CliConstants.java` | 长驻超时/回收/上限常量 |
| `NodeProcessRegistry.java` | snapshot 合并 CLI_SESSION;killByPid kind 防护 |
| `NodeProcessActionHandlers.java` | kill 结果透传保护错误码 |
| `webview/.../nodeProcessCapabilities.ts` + `NodeProcessSelect.tsx` | kind 扩展 + 只读分组渲染 |
| `webview/src/generated/protocol.ts` | enum 生成流(mjs)同步 |
| 10 locale json | nodeProcess.* 新 key |
| 行为菜单 + FeatureFlags | 长驻开关(三层 -D 门禁,默认开) |

---

## 附录 A: 原「自研 daemon + 直调 API」方案否决理由摘要

1. **认证断崖**: 主流用户走 CLI login 订阅认证(Claude Pro/Max OAuth、ChatGPT 订阅),无直调 API 的 key;`ANTHROPIC_BASE_URL` 网关用户也不覆盖。直调官方端点等于改变产品计费前提。
2. **能力断崖**: 直调 Messages API 无 tool use/权限/文件操作;"Phase 3 补回 tool use" = 在 ai-bridge 重写整个 agent loop,与「不引入 SDK」约束自相矛盾,撞插件「简易配置」定位红线。
3. **会话体系断裂**: 内存 ConversationManager 与 CLI transcript 双轨,历史列表/续接/崩溃恢复全部断裂;"Java 侧也保留历史"只保 UI 展示不保可续写上下文。
4. **立项前提过时**: "one-shot 每轮 20-30s" 为 SDK 时代数字;检测器预热与 MCP gateway 常驻落地后,每轮真实冷启动仅 ~3.4s(§1 实测)。
5. **内部矛盾**: 全量预热 6 daemon vs 风险表"最多 2-3 个";idle 回收后用户重吃冷启动;`parallelStream` 阻塞 commonPool。

*实测环境: Windows 10 / Git Bash / claude 2.1.233 / glm-5.3 via Claude Code 兼容端点 / 2026-08-17。*
