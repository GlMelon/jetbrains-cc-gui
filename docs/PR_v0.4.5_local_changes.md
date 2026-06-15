# PR: feature/v0.4.5 — 本地功能增强与上游 v0.4.5 合并

## 概述

本分支基于上游 `v0.4.5`（74 commits）进行了本地架构重构、UI 重设计和性能优化，涉及 **351 个文件**，净增 **+7,651 行**代码。

---

## 一、架构重构（对上游代码的核心改造）

### 1.1 DaemonBridge 进程管理重构

| 改动点 | 上游原始 | 本地调整 |
|--------|---------|---------|
| CLI 路径传递 | `DaemonBridge` 里直接读 `PropertiesComponent` 传 `CLAUDE_CODE_PATH` 环境变量 | 移除 `ClaudeCliPathHandler` 依赖，由 `EnvironmentConfigurator` 统一处理 |
| 进程终止 | `daemonProcess.destroyForcibly()` + 手动 waitFor | 委托给 `PlatformUtils.terminateProcessAndWait()` 统一封装 |
| 中断机制 | `sendAbort()` 全局中断 | 改为 `sendAbort(channelId)` **按频道精确中断**，新增 `activeRequestId` 跟踪 |
| 请求清理 | abort 时清空所有 pending | 保留非当前频道的 pending 请求 |

**涉及文件**：`provider/common/DaemonBridge.java`

### 1.2 CodexSDKBridge 重构为 Daemon 模式

上游 Codex 每次请求 fork 新进程（`CODEX_USE_STDIN=true`），本地改为长驻守护进程模式：

- 引入 `CodexDaemonCoordinator` + `CodexDaemonRequestExecutor`
- 新增 `interruptChannel(channelId)` 按 channel 精确中断
- 移除 `SANDBOX_MODE_READ_ONLY` 常量和 `serviceTier` 参数（简化签名）
- `CodemossSettingsService` 从每次 `new` 改为**单例** `getInstance()`

**涉及文件**：`provider/codex/CodexSDKBridge.java`, `provider/codex/CodexDaemonCoordinator.java`, `provider/codex/CodexDaemonRequestExecutor.java`

### 1.3 ClaudeSDKBridge 调用链简化

| 上游 | 本地调整 |
|------|---------|
| `normalizeCwdForNode(cwd)` 在发送前转换路径 | 移除，直接传递 `cwd`（让 Daemon 层统一处理） |
| `processInvoker.sendMessage(...)` 14参数长调用 | 封装为 `sendViaProcessInvoker()` / `sendViaDaemonBridge()` |
| `sendAbort()` 无参数 | 改为 `sendAbort(channelId)` 精确中断 |
| 无 invocationMode 刷新 | 新增 `refreshInvocationMode()` 兼容方法 |
| 调试信息缺失 | 新增 `[DIAG]` 日志：附件数量、daemon 状态跟踪 |

**涉及文件**：`provider/claude/ClaudeSDKBridge.java`

### 1.4 Session 层重构

- **`SessionLifecycleManager`** — 重构生命周期管理
- **`SessionSendService`** — 大幅改造发送逻辑（`-325 +325` 行）
- **`ClaudeMessageHandler`** — 消息处理重写（`+450` 行改动）
- **`CodexMessageHandler`** — Codex 消息处理重写（`+538` 行改动）
- **`MessageMerger`** / **`MessageParser`** / **`ReplayDeduplicator`** — 消息合并、解析、去重优化
- **`SessionCallbackAdapter`** / **`SessionCallbackFacade`** — 回调适配器改造
- **`SessionContextService`** — 会话上下文服务重构

---

## 二、前端 UI 重设计

### 2.1 TokenIndicator 彻底重做

上游为 SVG 双圆环进度条（14px 小圆圈），本地改为：

- **Chip 样式触发器 + 悬浮详情弹窗**
- 2×2 指标网格（input/output/cache/context）
- 使用率进度条 + 颜色等级（`level-ok` / `level-warn` / `level-high`）
- 自定义 SVG 图标（ArrowDown/ArrowUp/Database/Layers）
- `formatNumber` / `formatMaxTokensK` 人性化数字格式化

**涉及文件**：`webview/src/components/ChatInputBox/TokenIndicator.tsx`

### 2.2 Icons 组件大幅扩展

新增约 **1138 行** SVG 图标组件，用于工具栏按钮、状态指示、Token 指示器等。

**涉及文件**：`webview/src/components/Icons.tsx`

### 2.3 工具栏按钮重设计

约 **579 行** CSS 差异，对上游的选择器/按钮样式做了全面重新设计。

**涉及文件**：`webview/src/components/ChatInputBox/styles/selectors.css`

### 2.4 main.tsx 启动流程模块化

上游所有初始化逻辑内联在 `main.tsx`（约 644 行），本地拆分为独立 bootstrap 模块：

- `bootstrap/bridge.ts` — 心跳启动
- `bootstrap/scaleRecovery.ts` — 缩放恢复
- `bootstrap/fonts.ts` — 字体初始化
- `bootstrap/language.ts` — 语言初始化
- `bootstrap/pendingSlots.ts` — 挂起槽位注册

`main.tsx` 大幅精简，仅保留启动编排。

---

## 三、后端性能优化

### 3.1 单例模式改造

| 类 | 改动 |
|----|------|
| `CodemossSettingsService` | 从 `new` 改为 `getInstance()` 单例 |
| `SessionIndexManager` | 改为 Holder 内部类延迟初始化单例 |

### 3.2 进程/线程管理优化

| 类 | 改动 |
|----|------|
| `ProcessManager` | 进程管理器增强（`+207` 行） |
| `PlatformUtils` | 新增 `terminateProcessAndWait()` 等工具方法（`+197` 行） |
| `SystemNotificationService` | 大幅简化（`-413` 行），移除冗余逻辑 |

### 3.3 工具类清理

| 移除项 | 原因 |
|--------|------|
| `util/PathUtils.java` | 功能并入 `WslPathUtil` |
| `model/ConflictStrategy.java` | 不再使用 |
| `model/DeleteResult.java` | 不再使用 |
| `model/NodeDetectionResult.java` | 不再使用 |

---

## 四、新增功能（上游没有的）

| 功能 | 涉及文件 |
|------|---------|
| Tab 右键菜单图标 + 分组 | `plugin.xml`, `action/tab/CreateNewTabAction.java` |
| i18n 翻译增强 | `ClaudeCodeGuiBundle_zh.properties` 等多语言文件 |
| Token 指示器会话信息 + 上下文源 | i18n keys, `TokenIndicator.tsx` |
| 模型 `[1m]` 后缀处理 | `ModelProviderHandler.java`（`applyModelChange` 重构） |
| 常量提取重构 | `CommonConstants`, `CliConstants`, `CliJsonHelper`, `RawMessageHelper` |
| 回调处理器 | `session/CallbackHandler.java` |
| `MessageCallback` 扩展 | `provider/common/MessageCallback.java` |
| `SDKResult` 扩展 | `provider/common/SDKResult.java` |
| 附件存储服务 | `util/AttachmentStorageService.java` |
| 项目桥接注册表 | `provider/common/ProjectBridgeRegistry.java`, `SharedBridgeReferenceCounter.java` |

---

## 五、样式/布局调整

| 文件 | 改动 |
|------|------|
| `StatusPanel.less` | `+460` 行重写状态面板样式 |
| `loading.less` | `+266` 行重写加载动画 |
| `responsive.less` | `+219` 行新增响应式布局 |
| `conversation-search.less` | `+65` 行搜索样式优化 |
| `history.less` | `+65` 行历史列表调整 |
| `message.less` | `+194` 行消息卡片样式重写 |
| `buttons.css` / `toolbar.css` | 工具栏按钮样式重新设计 |
| `context-bar.css` | 上下文栏调整 |
| `enhance-prompt.css` | 提示增强样式调整 |
| 多个 `style.module.less` | 设置面板各区块样式大幅调整 |

---

## 六、前端 Hook / Context 重构

| 文件 | 改动描述 |
|------|---------|
| `useSettingsBasicActions.ts` | `+221` 行重写设置基本操作 |
| `useSettingsWindowCallbacks.ts` | `+99` 行重写设置窗口回调 |
| `SettingsSidebar/` | `+241` 行侧边栏样式重构 |
| `useMessageSender.ts` | `+108` 行消息发送逻辑重构 |
| `streamingCallbacks.ts` | `+315` 行流式回调重写 |
| `messageSync.ts` | `+128` 行消息同步逻辑改造 |
| `usageModeCallbacks.ts` | `+64` 行新增 usage 回调 |
| `messageCallbacks.ts` | `+48` 行消息回调调整 |
| `useModelProviderState.ts` | `+96` 行模型提供者状态管理扩展 |
| `useScrollBehavior.ts` | `+73` 行滚动行为优化 |
| `MessageItem.tsx` | `-312` 行精简（逻辑外提） |
| `MessageList.tsx` | `+258` 行消息列表增强 |
| `WaitingIndicator.tsx` | `+137` 行等待指示器重写 |

---

## 七、改动量统计

| 维度 | 数量 |
|------|------|
| 修改的文件 | ~351 个 |
| 新增代码（净增） | +15,249 行 |
| 删除代码（净删） | -7,598 行 |
| 净变化 | +7,651 行 |

---

## 八、合并说明

- 已合并上游 74 个 commits（task tracking、Fable 5 pricing、Codex fast mode、WSL fixes 等）
- 通过 `28c8b2e4` 修复了合并过程中丢失的本地功能（ClaudeSDKBridge、CodexSDKBridge、DaemonBridge、ClaudeMcpQueryService）
- 所有本地功能保留，构建验证通过
