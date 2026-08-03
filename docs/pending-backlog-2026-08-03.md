# 待处理专项清单

- **生成日期**:2026-08-03
- **分支**:feature/v0.4.9
- **来源**:2026-08-03 后端架构清理会话(T1–T14)+ 2026-07-31 全面排查审计(`docs/audit-2026-07-31-findings.md`)
- **用途**:本会话已完成项已落地(见文末 §E,待提交)。本文档汇总**未完成 / 延后 / 待决策**的项,每项可独立推进,供后续单独处理。
- **状态约定**:🔴 中型重构(跨多文件,需专项)｜🟡 架构级/产品决策(需定方向)｜🔵 设计决策(有意为之,非 bug)｜⚪ 待决策(接入 or 删除)

---

## A. 功能断连 / 死壳清理(中型重构)

### A1. CodexPet(桌面宠物)功能完全坏 🔴
- **来源**:T10 / 2026-08-03 核实
- **问题**:`CodexPetHandler`(~800 行,`extends BaseMessageHandler` 退役框架)整个 main 源码**零实例化**,从未注册到 dispatcher。前端 `codexPet/petBridge.ts` 活跃发送 ~20 个 action,全部落到 `LOG.warn("Unknown message type")` 被丢弃 → 功能完全坏。
- **位置**:
  - `src/main/java/com/github/claudecodegui/handler/CodexPetHandler.java:52`(SUPPORTED_TYPES 含 get_codex_pets/get_codex_pet_config/get_petdex_catalog/install_petdex_pet 等,见 :56-67、switch :103-142)
  - 前端 `webview/src/components/codexPet/petBridge.ts:479-542`(~20 个 sendToJava)
- **修复方向**:把多 action switch(BaseMessageHandler 模式)拆成 typed handler(`FrontendActionHandler<T>`,一 handler 一 action)或多入口适配,注册到 `ChatWindowDelegate`。
- **决策点**:先确认是**预期的活跃功能**(用户依赖 → 恢复)还是**实验性半成品**(→ 删前端入口 + 死 handler 标未实现)。
- **规模**:中型(~20 action 拆分)

### A2. TokenTracker(用量仪表盘)功能完全坏 🔴
- **来源**:T11 / 2026-08-03 核实
- **问题**:`TokenTrackerHandler`(~530 行,裸类非 handler 接口)整个 main 源码**零实例化**,管理本地 HTTP server,经 `window.onTokenTrackerResponse` 回调。前端 `tokentrackerBridge.ts` 期待该回调 → 功能完全坏。
- **位置**:
  - `src/main/java/com/github/claudecodegui/handler/TokenTrackerHandler.java:44`(裸类;`:556` callJavaScript onTokenTrackerResponse)
  - 前端 `webview/src/components/UsageStatistics/tokentrackerBridge.ts`
- **修复方向**:接入 typed dispatcher;TokenTracker 是 server 生命周期 + RPC(非 action-bus 模式),迁移路径与 CodexPet 不同,需单独设计。
- **决策点**:同 A1(恢复 or 删)。
- **规模**:中型

### A3. PermissionManager 整类彻底清理 🔴
- **来源**:T14 核实 + audit P3-SEC
- **当前状态**:T14 已删 `createRequest` + `isAutoApprovedInAcceptEditsMode`(虚假保护核心)。整类仍是"死壳"——`ClaudeSession` 仍 `new` + 调 5 处,但操作空 `pendingRequests` = no-op。**真权限系统是 `PermissionService`**(文件 IPC + `PermissionDecisionStore`)+ Node 侧 `canUseTool`(`isDangerousPath`)。
- **位置**:
  - `src/main/java/com/github/claudecodegui/permission/PermissionManager.java`(剩 mode 状态机 + no-op 方法)
  - `src/main/java/com/github/claudecodegui/session/ClaudeSession.java:55`(字段)、`:236`(setOnPermissionRequestedCallback)、`:654`(清回调)、`:661-687`(mode 映射 + setPermissionMode)、`:790`(handlePermissionDecision)、`:797`(handlePermissionDecisionAlways)
- **修复方向**:删 `PermissionManager.java` + 改 `ClaudeSession`(移除字段/5 处调用/mode 映射)+ 核实上游(谁调 `ClaudeSession.handlePermissionDecision`)+ `PermissionRequest`(被 createRequest 用,删后可能随之死)。
- **认知陷阱**:看到 PermissionManager 别误以为权限生效——真闸门在 PermissionService。
- **规模**:中型(跨 PermissionManager + ClaudeSession + 上游 + PermissionRequest)

### A4. BaseMessageHandler / MessageDispatcher / LegacyMessageHandlerAdapter 死框架清理 🔴
- **来源**:T12 / AGENTS.md 第 79 行
- **问题**:旧字符串派发框架已退役、无注册项,属待清理死代码。
- **依赖**:⚠️ **必须先完成 A1**(`CodexPetHandler` 仍 `extends BaseMessageHandler`,迁移前删框架会编译失败)。
- **规模**:中型

---

## B. 审计未完成项(架构级 / 产品决策)

### B1. SEC-03 OpenCode 完全无 plugin 侧工具权限门 🟡 ✅(2026-08-03 本会话,采用方向①)
- **来源**:audit P1
- **位置**:`ai-bridge/services/opencode/message-service.js:153-157`;`cli/opencode/OpenCodeCliSession.java:178-179,201,402-404`;`event-mapper.js:273`
- **问题**:permissionMode 透传到 stdin 后被静默丢弃,tool_use 纯粹事后渲染(工具已在外部 opencode 进程执行完)。CLI 路径不加 flag,还 `pb.redirectInput(stdinNullSink())` 掐断 opencode 原生询问通道。
- **修复方向**:① UI 明示"OpenCode 工具由 opencode 原生策略管控,本插件不拦截"+停止收集 permissionMode(以免误导);或 ② 实现 tool_use→Java 弹窗→回灌(serve API,工程代价大)。
- **规模**:架构级

### B2. SEC-04 Codex 命令审批 fire-then-ask + Windows default 无沙箱 🟡 ✅(2026-08-03 本会话,采用方向①;Windows full-access 按产品权衡保留)
- **来源**:audit P1
- **位置**:`ai-bridge/services/codex/codex-event-handler.js:989,669-690`;`ai-bridge/utils/permission-mapper.js:138-181`
- **问题**:命令审批在 `item.started`(命令已启动)才问,deny 仅事后 abort(自承 "command may have already started");Windows default=`danger-full-access`。
- **⚠️ 注意**:Windows full-access 是**故意的**(`permission-mapper.js:18,121-122,133,147,176` 注释:Windows sandbox experimental,用 full-access 保证写操作工作)。改 `workspace-write` 可能破坏 Windows 写功能 —— 产品权衡,非疏忽。
- **修复方向**:① UI 标注 Codex 权限为"事后中止"降级;② Windows default 保留 `workspace-write` + 显式风险提示(须先验证 Windows 写功能不破);③ 注册 SDK 原生 approval callback。
- **规模**:架构级 / 产品决策

### B3. SEC-06 riskLevel 不挡 known runner 跑恶意包名 🟡 ✅(2026-08-03 本会话,前端包名二次确认 UI)
- **来源**:audit P2
- **位置**:`src/main/java/com/github/claudecodegui/mcp/McpCommandRiskEvaluator.java`(runner 在 npx/uvx/docker 白名单 → 无条件放行)
- **问题**:`npx -y evil-trojan-pkg`(postinstall 任意代码执行)、`uvx evil-pkg` 通过闸门。
- **修复方向**:gate 无法单独覆盖,需 UI 对包名二次确认 + 默认对非官方/低信誉包拒绝。真正修复需包信誉服务(超插件定位)。
- **规模**:超定位

---

## C. 审计设计决策项(有意为之,非 bug,记录备查)🔵

> 这些是 audit 第六批核实后确认的"有意设计 / 功能差异 / 历史债务",非 bug。记录在此备查,改动需大重构或产品决策。

### C1. 三模型 id 初始值不对称
- `useClaudeProvider.ts:12`=`'claude-role-sonnet'`,另两个 `''`。有意(Claude 有 role 常量,codex/opencode 由 registry 下发)。

### C2. permissionMode 三 provider 不对称
- claude/codex 各独立存储,opencode 无独立存储(强制 default)。功能差异(opencode 无独立模式)。

### C3. SET_PROVIDER / SET_SESSION_PROVIDER 双事件语义重叠
- `useModelProviderState.ts:211` 与 `useSessionManagement.ts:203,216`。历史债务,统一收口需大重构。

---

## D. 其他待决策死代码 ⚪

### D1. CodexExecHistoryReplay 未接入
- **来源**:memory preexisting-test-compile-errors-fixed(2026-07-31)+ 2026-08-03 复核
- **核实**(2026-08-03):`new CodexExecHistoryReplay` / `CodexExecHistoryReplay.` 在 main 源码**零匹配** → 仍未接入,有完整实现但无调用方。
- **位置**:`src/main/java/com/github/claudecodegui/handler/history/CodexExecHistoryReplay.java:26`
- **决策点**:接入(补调用方,可能是 exec 历史回放功能)或删除(若功能已废弃)。

---

## E. 已提交的改动(本会话)

> 以下已完成并已提交(2026-08-03)。

**提交记录(feature/v0.4.9)**:
| Commit | Type | Description |
|--------|------|-------------|
| `f18a7497` | refactor(webview) | update frontend components and remove dead code |
| `3f3b30ee` | test | update test files for refactored code |
| `6eda2866` | chore(resources) | update plugin icons and metadata |
| `98eea507` | refactor(ui) | update UI delegate and utility helpers |
| `f4ac6171` | refactor(service) | update skill and market service handling |
| `e129f6fd` | refactor(settings) | update settings service and model registry config |
| `01b033ef` | refactor(provider) | update provider and pricing table handling |
| `b2f857d2` | refactor(permission) | update permission service and action handlers |
| `0ef5b99e` | refactor(mcp) | clean up MCP marketplace dead code and update gateway |
| `804e7244` | refactor(handler) | consolidate handler refactoring and remove dead code |
| `8ecefee6` | refactor(session) | refactor session runtime and callback handling |
| `fa40d6ba` | refactor(protocol) | update UpstreamAction, DownstreamEvent, PermissionMode |
| `16d08254` | refactor(ai-bridge) | improve MCP gateway framing and provider services |
| `4ffdfcbf` | docs | update architecture docs and add audit findings |

**本会话(T1–T14)**:
- T1 `gateway-stdio-client` FramedReader error/end 监听(audit STAB-01)
- T2 `SetNodePathActionHandler` 补 OpenCode 对称
- T3/T4 provider 字面量收敛到 SSOT 常量
- T5 CLI interrupt 三家风格统一
- T6 AGENTS.md §2 过时描述更新
- T7 删无调用的 `CustomModelPricingHandler`
- T8 核实 `McpServerImportHandler` 前端入口
- T9 删 `McpMarketplace`(内置市场)死全链 + `install_mcp_from_market`(audit SEC-01)
- T13 删 `permission-safety.js` acceptEdits 死代码块(audit P3-SEC)
- T14 删 `PermissionManager.createRequest` + `isAutoApprovedInAcceptEditsMode` + 失效 import(audit P3-SEC)

**工作区既有修复(本会话仅核实,非本会话引入)**:
- SEC-02 Bash always-allow 改 parameter-level(`PermissionService:361-369` + `PermissionDecisionStore`)—— 本会话核实确认已修
- audit 第五/六批其余修复(SEC-05/07/08、ARCH-01/02、SKILL-01/02/03、STREAM-01..05、MCP-01/02/03 等,audit 清单已标 ✅)

**验证状态**:T9(compileTestJava + tsc --noEmit + protocol.ts 重生成)、T13(`node --check`)、T14(`compileJava` BUILD SUCCESSFUL)均通过。

**本会话后续(B1/B2/B3 安全 UI 标注,2026-08-03)**:
- B1 SEC-03 OpenCode:UI 明示 + 停收 permissionMode。前端双防御(`ButtonArea` 隐藏 ModeSelect + `handleModeSelect` opencode early-return)+ `PlaceholderSection` 明示 + ai-bridge 删 `services/opencode/message-service.js`/`channels/opencode-channel.js` 死透传。CLI L402-404 保留(防御)。10 locale i18n。
- B2 SEC-04 Codex:fire-then-ask UI 标注降级。`PermissionsSection` 加 `fireThenAskNotice` 横幅 + 纠正 `codexModes.default.tooltip` 误述(原"prompts before"→实际审批在 `item.started` 命令已启动**之后**)。Windows full-access 按产品权衡保留(方向②③ 未做)。10 locale i18n。
- B3 SEC-06 MCP 包名:前端二次确认 UI。新增 `webview/src/components/mcp/packageRunner.ts`(检测 npx/uvx/uv/pnpm/pnpx/bunx + docker/podman)+ `McpPackageConfirmDialog.tsx`(复用 BaseDialog);拦截 `McpSettingsSection` 三出口(`handleSaveServer`/`handleSelectPreset`/`handleImportServers`,单+批量)。10 locale i18n。
- 验证:webview `tsc --noEmit` EXIT 0;ai-bridge checkJs **B1 目标文件零错误**(`opencode-channel.js`/`opencode/message-service.js` 均 `// @ts-check`);`npm run build` EXIT 0(claude-chat.html 已同步);i18n gate **B3 零恶化**(所有 locale `mcp.packageConfirm.*` missing=0)。
- ⚠️ 预存非本会话:ai-bridge checkJs 预存 13 错误(`ipc-server.js`/`read-cc-switch-db.js`/`codex-utils.js`);i18n gate 预存 FAIL(stale baseline + codexPet/settings.pet 158 key 更早会话已删)。

**memory 索引**(跨会话):见 `memory/MEMORY.md`「后端架构清理(2026-08-03)」段——`mcp-marketplace-dead-chain-deleted`、`codexpet-tokentracker-disconnected-backlog`、`permission-deadcode-cleanup-2026-08-03`、`b1-b2-b3-security-ui-annotation-done`。
