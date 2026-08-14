# 上游 feature/v0.5.3 保留式合并记录

- **日期**:2026-08-14
- **范围**:将上游远程分支 `upstream/feature/v0.5.3` 合并到深度重构的 fork
- **状态**:批次 1 / 2 / 3-P0 / 3-P1 已落地并通过定向 typecheck;3-P2 排查完成,建议推迟;全部改动待提交
- **相关目录**:`ai-bridge`、`webview/src`、`src/main/java`

## 1. 合并对象与策略

### 1.1 双仓库背景

- **upstream**:`zhukunpenglinyutong` 的原始仓库,本次引入方
- **origin (fork)**:`GlMelon`,已进行 711 次深度重构提交,架构与上游已显著分叉
- **merge-base**:`ce2a86c6`
- **工作分支**:`feature/v0.5.3-upstream-sync`
- 上游独有约 103 次提交,fork 独有 711 次提交

### 1.2 合并原则(用户约束)

1. **先评估再合并**:逐项核对上游每个改动是否与 fork 已有架构契合,不符合的不迁
2. **前端能用 react-bits 替换的就用 react-bits 组件**;后端参考 fork 已有架构进行代码迁移
3. **Grok / Kimi / Pi 决策**:可以引入 history 等能力,但**只需要 CLI,不需要 SDK / ACP / DaemonBridge 那一套**;可参考已有的 claude / codex / opencode 代码改造
4. **最小改动 + 真实 bug 优先**:可选收敛让位于真实缺口;符合插件"简易配置"定位

### 1.3 双轨架构关键事实(影响迁移判断)

- **主聊天路径(交互式)**:Java `CliSession`,附件经 `CliAttachmentHandler.processForCodex` 物化磁盘 + `-f`(OPENCODE_ARG_FILE)透传
- **辅助路径(commit message / prompt enhancer 等)**:`ai-bridge` channel(channel-manager.js),注释明示"会话交互式发送走 CliSession,不经此处"
- `ai-bridge` channel 的 grok/kimi/pi 走 **args 路径**(命令行参数),非 stdin payload;Java 仅设 `CLAUDE_USE_STDIN`/`CODEX_USE_STDIN`/`OPENCODE_USE_STDIN`,不设 GROK/KIMI/PI

## 2. 批次落地记录

执行顺序:批次 1(IME)→ 批次 2(前端)→ 批次 3 后端(用户多选 P0/P1/P2,跳过 P3)。

### 2.1 批次 1 — IME 输入法

修复 Linux OSR 模式下中文/日文等 IME 合成输入的光标与候选窗错位问题。

**改动**:
- `src/main/java/com/github/claudecodegui/util/JBCefBrowserFactory.java`(改)— `configureOsrImeFix` 安装点
- `src/main/java/com/github/claudecodegui/ui/OsrImeCaretFix.java`(新)— OSR IME 合成/光标修复逻辑
- `webview/src/components/ChatInputBox/`(ChatInputBox.tsx、hooks/index.ts、useGlobalCallbacks.ts、useTextContent.ts、styles/dialogs.css)— IME 输入相关前端适配

### 2.2 批次 2 — 前端(quote / subagent / markdown)

#### 2.2.1 引用 quote 标签
- `webview/src/utils/quoteUtils.ts`(新)
- `webview/src/components/ChatInputBox/utils/quoteRegistry.ts`(新)
- `webview/src/components/ChatInputBox/hooks/useQuoteTags.ts`(新)
- 配套:`App.tsx`、`useGlobalCallbacks.ts`、`useTextContent.ts`

#### 2.2.2 subagent 子代理展示
- `webview/src/components/StatusPanel/SubagentProcessDetails.tsx`(改)
- `webview/src/components/toolBlocks/AgentGroupBlock.tsx`(改)
- `webview/src/components/toolBlocks/TaskExecutionBlock.tsx`(改)
- `webview/src/utils/subagentResult.ts`(改)、`webview/src/utils/taskNotificationMessage.ts`(新)
- `webview/src/hooks/useSubagents.ts`(改)、`useMessageSender.ts`(改)
- `webview/src/components/StatusPanel/StatusPanel.less`(改)

#### 2.2.3 markdown 渲染
- `webview/src/components/MessageItem/MessageItem.tsx`(改)
- `webview/src/styles/less/components/message.less`(改)

> markdown 流式渲染:fork 采用自研 `useTypewriterStream`(O(增量字符)),与上游 v0.5.3 的 block-memo(O(最后一块))两路互斥。上游 v0.5.3 的 627 行 markdown 重构逐机制不适用 fork,**不移植**(详见 memory `markdown-streaming-typewriter-vs-upstream-blockmemo`)。

#### 2.2.4 i18n / 类型
- `webview/src/global.d.ts`(改)
- `webview/src/i18n/locales/en.json`、`zh.json`(改)

### 2.3 批次 3-P0 — 图片附件能力

上游 `e41dc978f` 引入 Grok/Kimi/OpenCode/PI 的 CLI 图片附件。迁移时仅取 **CLI 路径**(按用户决策排除 SDK/ACP)。

**新建**:`ai-bridge/utils/cli-image-input.js`(370 行,13 导出)
- 纯 node 工具(`node:fs/promises`、`node:path`、`node:os`、`node:crypto`)
- 核心:`materializeImageAttachments`(base64→临时文件,写入 `os.tmpdir()/cc-gui-cli-images`,权限 0o700/0o600)、`buildKimiPromptWithImages`(ReadMediaFile 路径标签)、`buildReadPathPromptWithImages`(Read 工具路径引用)、`cleanupMaterializedImagePaths`

**辅助路径接入(ai-bridge channel)**:
- `ai-bridge/services/kimi/message-service.js`(改)— `sendMessage` 加 `attachments` 参数 → 物化 → kimi 式 prompt 注入 → `try/finally` 清理临时文件
- `ai-bridge/services/pi/message-service.js`(改)— 同构,用 `buildReadPathPromptWithImages`
- `ai-bridge/channels/kimi-channel.js`、`pi-channel.js`(改)— 解构 `stdinData.attachments` 透传(对齐 `codex-channel` 范例)

**关键澄清(推翻"从零搭建"假设)**:主聊天图片附件 Java 侧**早已支持** —— `CliAttachmentHandler.processForCodex` 物化磁盘文件 + `-f`(OPENCODE_ARG_FILE)透传(`KimiCliSession.java:372-379`,pi/grok 同构);前端对所有 provider 统一发裸 base64(无 data: 前缀),`CliSendRequest.attachments` 是 provider 无关统一通道。本次仅补 ai-bridge 辅助路径。

**主聊天 prompt 注入待验证,未盲目做**:claude 的 `buildPrompt` 注入 `[Image #N:path]` + "Use the Read tool" + `--add-dir`(`ClaudeCliSession.java:232-295`)是因 claude CLI **无 -f 图片参数**(走 stdin);kimi/pi/grok 用 `-f`。若 `-f` 已 vision 图片,盲目注入 Read 指令会导致模型重复读取/行为异常(有副作用)。需实际 CLI 验证 `-f` 图片行为后再定,记入 backlog。

### 2.4 批次 3-P1 — CLI 增强

上游 CLI 基础设施增强四子项,逐一核对后**仅 1 项为真缺口**:

| 子项 | 结论 | 理由 |
|---|---|---|
| ✅ grok models-service | **已迁移(真缺口)** | fork 原本无法列 grok 模型(picker 空/无 fallback) |
| ❌ cli-path +89 | fork 已实现 | `cli-path.js` 已有 `isWindowsCmdShim`、Windows `.cmd/.bat/.exe` 解析、各 provider `resolveXxxCliPath` |
| ❌ cli-spawn +24 | fork 已实现 | `cli-spawn.js` 已有 `child.on('error')`(ENOENT 提示)、`shell:isWindowsCmdShim`、SIGTERM/SIGINT/SIGHUP |
| ❌ stdin-utils +11 | 架构不同不迁 | fork grok/kimi/pi 走 args 路径,Java 不设 GROK/KIMI/PI_USE_STDIN,三元运算已与 Java 对齐 |

**grok models-service 改动**:
- `ai-bridge/services/grok/models-service.js`(新,210 行)— 读 `~/.grok/config.toml` 的 `[model."name"]` profile(Grok CLI `-m` 必须是 profile 名,非裸 catalog id)+ `models_cache.json` + 静态兜底;优先级 profiles > cache > static。fork 适配:payload 加 `provider:'grok'`(对齐 fork kimi models-service 风格),修正上游笔误 "SpaceXAI's"→"xAI's"
- `ai-bridge/channels/grok-channel.js`(改)— 加 `listModels` case + `getGrokCommandList()` 返回 `['send','listModels']`

### 2.5 批次 3-P2 — OSR fence 排查(建议推迟)

排查上游 commit `4ab851f78`(`SurfaceFrameFence` + 配套)是否需要迁移。

**结论:fork 确实存在同源 OSR 帧发布脆弱性,但建议推迟迁移。**

- `SurfaceFrameFence` 未合并(仅存 `upstream/*`,不在 HEAD 历史)
- 受影响路径:`JBCefBrowserFactory.determineOsrMode()`(164-179)—— macOS/Windows OSR 关,**Linux/Unix(IDEA 2023+)OSR 开**;Remote JCEF 深度支持(`isRemoteEnabled`、`pageGeneration`/epoch)
- fork 现有 workaround(`wasResized`+缩略图微调+`historyLoadComplete`+`WebviewWatchdog`)被上游修复文档认定为不充分;fork **无任何 `CefRenderHandler`/`OnPaint` 钩子**
- fork 唯一 OSR 移植 `OsrImeCaretFix.java`(批次 1)只修 IME 合成光标,不涉帧发布

**推迟理由**:
- 成本高:~4376 行,且 fork `ClaudeChatWindow` 深度分叉,**cherry-pick 不可行,需语义重新整合**
- 影响面窄:仅 Linux OSR / Remote-JCEF 用户;Windows/macOS(OSR 关)完全无关
- **无** fork 用户报告"首个标签页历史空白"
- 据插件"简易配置"定位 + 真实 bug 优先原则,记入 backlog;仅当实际出现该症状时作为独立批次启动

## 3. 改动文件清单(工作区,均未提交)

分支:`feature/v0.5.3-upstream-sync`

```text
# 批次 1 — IME
 M src/main/java/com/github/claudecodegui/util/JBCefBrowserFactory.java
?? src/main/java/com/github/claudecodegui/ui/OsrImeCaretFix.java
 M webview/src/components/ChatInputBox/ChatInputBox.tsx
 M webview/src/components/ChatInputBox/hooks/index.ts
 M webview/src/components/ChatInputBox/hooks/useGlobalCallbacks.ts
 M webview/src/components/ChatInputBox/hooks/useTextContent.ts
 M webview/src/components/ChatInputBox/styles/dialogs.css

# 批次 2 — 前端
?? webview/src/components/ChatInputBox/hooks/useQuoteTags.ts
?? webview/src/components/ChatInputBox/utils/quoteRegistry.ts
?? webview/src/utils/quoteUtils.ts
?? webview/src/utils/taskNotificationMessage.ts
 M webview/src/App.tsx
 M webview/src/components/MessageItem/MessageItem.tsx
 M webview/src/components/StatusPanel/StatusPanel.less
 M webview/src/components/StatusPanel/SubagentProcessDetails.tsx
 M webview/src/components/toolBlocks/AgentGroupBlock.tsx
 M webview/src/components/toolBlocks/TaskExecutionBlock.tsx
 M webview/src/global.d.ts
 M webview/src/hooks/useMessageSender.ts
 M webview/src/hooks/useSubagents.ts
 M webview/src/i18n/locales/en.json
 M webview/src/i18n/locales/zh.json
 M webview/src/styles/less/components/message.less
 M webview/src/utils/subagentResult.ts

# 批次 3-P0 — 图片附件
?? ai-bridge/utils/cli-image-input.js
 M ai-bridge/services/kimi/message-service.js
 M ai-bridge/services/pi/message-service.js
 M ai-bridge/channels/kimi-channel.js
 M ai-bridge/channels/pi-channel.js

# 批次 3-P1 — CLI 增强
?? ai-bridge/services/grok/models-service.js
 M ai-bridge/channels/grok-channel.js
```

## 4. 验证

- `ai-bridge` `tsc --noEmit`:本次改动文件(cli-image-input / kimi+pi message-service+channel / grok models-service+channel)**零错误**;14 个基线错误均与本次改动无关(按测试准则不追查预存基线)
- 测试准则:仅跑与改动直接相关的测试;无测试覆盖的文件靠 typecheck + 改动孤立性兜底

## 5. 未迁移项与 backlog

| 项 | 原因 |
|---|---|
| markdown 流式 block-memo | fork 自研 typewriter O(增量)优于上游 O(末块),两路互斥不移植 |
| cli-path / cli-spawn / stdin-utils 增强 | fork 已实现或架构不同 |
| Grok/Kimi/Pi 的 SDK/ACP/DaemonBridge | 用户明确拒绝,仅要 CLI |
| kimi/pi/grok 主聊天 prompt 注入 | 待 CLI 验证 `-f` 图片行为,有副作用未盲目做 |
| **SurfaceFrameFence(OSR fence)** | ~4376 行语义重整合 + 仅 Linux/Remote 受影响 + 无 bug 报告,**推迟**,独立批次触发 |

## 6. 待办

- [ ] 提交本批改动(批次 1 + 2 + 3-P0 + 3-P1),待用户确认
- [ ] OSR fence 迁移决策(建议推迟;若 Linux/Remote 用户报告首标签历史空白则启动独立批次)
