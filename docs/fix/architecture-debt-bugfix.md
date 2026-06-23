# 架构债务缺陷登记簿(Architecture Debt Bugfix Registry)

> **创建日期**:2026-06-22
> **来源**:一次覆盖四域的前后端架构排查(后端分派体系 / 前端职责分离 / 协议 SSOT 链路 / Docking·分层·序列化)
> **关联准则**:[`AGENTS.md`](../../AGENTS.md) 附录 C · 落地进度概览
> **维护方式**:随修复进度更新各条「状态」字段;新增债务追加新条目,不删除历史条目(改为「已修复 / 已验证」留痕)。

---

## 0. 文档定位与使用说明

### 定位

本文档是**可逐条跟踪、逐条闭环**的缺陷登记簿。与已有迁移文档分工:

| 文档 | 视角 | 职责 |
|---|---|---|
| **本文(缺陷登记簿)** | 缺陷条目 | 登记 what / where / why / 验收;每条可独立跟踪状态 |
| 迁移文档(见 §9 索引) | 阶段路线 | 整体阶段划分、SOP、代码级步骤 |
| `AGENTS.md` | 架构准则 | 长期稳定的五条总则与合规清单 |

**去重原则**:本文只登记债务与验收标准;修复的详细步骤、阶段排序、代码 diff 见迁移文档(每条「关联」字段指向)。

### 字段说明

每条债务含:`编号` · `严重度` · `状态` · `位置(文件:行号)` · `现象` · `根因` · `修复方向` · `验收` · `归属总则` · `关联迁移任务`。

### 严重度

- **高**:违反最高优先级总则(总则一·职责分离)、或已导致前后端默认值漂移、或形成第二真相源。
- **中**:违反次级总则(开闭 / SSOT / 拓展),需整改但无即时运行时风险。
- **低**:边界判定(可商榷)、或现状可接受的过渡残留。

### 状态流转

`待修复` → `进行中` → `已修复` → `已验证` →(可选)`已豁免`(注明理由)。

---

## 1. 概览统计

| 根因 | 归属总则 | 条目数 | 高 | 中 | 低 |
|---|---|---|---|---|---|
| A · 前端业务未下沉 | 一 | 10 | 6 | 3 | 1 |
| B · handler 旧分派路径 | 二 | 5 | 2 | 3 | 0 |
| C · SSOT 链路缺口 | 三 | 10 | 5 | 5 | 0 |
| D · 重复实现 | 四 | 6 | 1 | 4 | 1 |
| E · 对接未 Docking 化 / 分层 / 序列化 | 二/五 + 附录 | 12 | 1 | 8 | 3 |
| **合计** | — | **43** | **15** | **23** | **5** |

> 截至 2026-06-23 进度:**已验证 19 项**(A1·A2·A3·A4·B1·B5·C2·C3·C6·C8·C9·D2·D3·E1·E2·E3·E4·E5·E6)、**已完成 2 项**(B2 — 20/20 legacy handler 全迁移;B4 — HistoryHandler 迁移)、**进行中 1 项**(B3 — SettingsHandler 13/~60 已迁移 permission-mode+input-history+model-provider 三子域)、**已豁免 1 项**(E12);其余 20 项待修复(22/43 已动)。逐项状态见 §7 表格,整体阶段路线见 §9 迁移文档索引。

---

## 2. 根因 A · 前端业务未下沉(总则一 · 职责分离)

### A1 · 前端模型注册表双真相源

- **严重度**:高 | **状态**:**✓ 已验证(Slice 1,2026-06-23)** | **归属**:总则一 / 四
- **位置**:`webview/src/components/ChatInputBox/types.ts:388-418`(`CLAUDE_MODELS` / `CODEX_MODELS`);`webview/src/utils/modelRegistry.ts:22-32`(`DEFAULT_MODEL_REGISTRY`);`types.ts:335-340`(`CLAUDE_ROLE_MODEL_IDS`)
- **现象**:前端持有一份与后端 `config/ReadOnlyDefaultModels.java:30-47` 并行的模型真相副本,并作 fallback。
- **根因**:违反「禁止 hardcode 业务数据表」「绝不前端也实现一份做 fallback」。
- **修复方向**:后端 `MODEL_REGISTRY` 下发为唯一来源;前端空 registry 时显示 loading,不回退本地表。
- **验收**:**✓ 达成**。webview 已无 `CLAUDE_MODELS` / `CODEX_MODELS` / `AVAILABLE_MODELS` / `DEFAULT_MODEL_REGISTRY`(全部删除,非保留为展示常量);`currentRegistry` 初始为空,空 registry 不回退本地表。
- **修复记录(Slice 1,2026-06-23)**:删 `types.ts` 的 `CLAUDE_MODELS`/`CODEX_MODELS`/`AVAILABLE_MODELS`、`modelRegistry.ts` 的 `DEFAULT_MODEL_REGISTRY`(`currentRegistry` 初始改 `{ items: [] }`);`ButtonArea.tsx`(L91/95)、`ModelSelect.tsx`(`DEFAULT_MODEL_MAP` + `AVAILABLE_MODELS` 默认参数 → `[]`)、`AiFeatureProviderModelPanel/index.tsx` 删本地表 fallback;`useClaudeProvider` 初始值改 `CLAUDE_ROLE_MODEL_IDS.sonnet`(`useState<string>` 显式标注避免 `as const` 字面量类型收窄导致的 4 处 tsc 错误)。
- **测试**:`tsc --noEmit` 0 错误;webview vitest stash 基线对比 **0 新增失败**(基线 11 failed 全为预先存在回归:groupBlocks×4 / codexQuota×3 / useDialogManagement / useScrollBehavior / useWindowCallbacks,与模型域无关);模型域全绿(ModelSelect 12 / modelRegistry 26 / AiFeatureProviderModelPanel 4);删除 2 个引用已删常量的失效用例(`CLAUDE_MODELS.map` / `CODEX_MODELS`)。
- **范围说明**:本 Slice 仅止血双真相源;A2(能力判定)/A3(归一化·映射·`[1m]`)/A4(effectiveContextWindow)随 A1-A4 全下沉,分别在 Slice 2/3 推进。
- **关联**:迁移 P0-2 / P1-A2

### A2 · 前端能力判定函数(模型能力)

- **严重度**:高 | **状态**:**✓ 已验证(Slice 3,2026-06-23)** | **归属**:总则一
- **位置**:`types.ts:274-299`(`modelSupports1MContext`,按 id 字符串 `!includes('haiku')` 判定);`modelRegistry.ts:27`、`modelRegistry.ts:217`(阈值推断);`ReasoningSelect.tsx:57,70-77`(按 role `opus|fable|sonnet` 判定 reasoning)
- **现象**:前端实现「某模型是否支持 1M / adaptive thinking」的能力判定。
- **根因**:能力判定属业务逻辑,应后端计算后下发布尔/枚举字段。
- **修复方向**:后端 `ModelConfig.supports1MContext` 已是权威;新增 `supportedReasoningLevels` 由 `ClaudeRole` 权威填充并下发;前端只读字段。
- **验收**:**✓ 达成**。webview 已删 `modelSupports1MContext`(`types.ts`);`ReasoningSelect` 删 role→级别硬编码,改读后端下发 `supportedReasoningLevels`;所有 1M 支持判定读 registry `item.supports1MContext`。
- **修复记录(Slice 3,2026-06-23)**:
  - **协议层(`modelRegistry.ts`)**:`ModelRegistryItem` 增 `supportedReasoningLevels?: readonly ReasoningEffort[]`;`parseModelRegistryPayload` 经 `parseReasoningLevels`(校验合法级别)解析;新增 `getModelSupportedReasoningLevels(id)` helper 纯读 registry。
  - **ReasoningSelect(能力判定下沉)**:删前端 `opus|fable|sonnet`/`haiku 隐藏` 硬编码;`availableLevels` 改 filter 后端下发 `supportedReasoningLevels`(sonnet/opus/fable=5 档含 xhigh+max,haiku=3 档);未配 role 的自定义模型不下发 → 隐藏。
  - **1M 支持判定下沉**:`types.ts` 删 `modelSupports1MContext`(claude- 非 haiku + contextWindow>=1M 字符串/数值推断);`useModelProviderState`/`useMessageSender`/`LongContextToggle` 全部调用点改读 registry `item.supports1MContext`。
  - **行为变化(可接受,后端权威)**:`sonnet` 现支持 5 档(含 xhigh,原前端硬编码 4 档无 xhigh);`haiku` 现 3 档可见(原前端隐藏);未在 registry 的 claude id 改保守 false(原规则1 任意 claude- 非 haiku 返回 true)。能力真相统一在后端 `ClaudeRole.reasoningLevels()` + `ModelConfig.supports1MContext`。
- **测试**:`tsc --noEmit` 0 错误;webview vitest **813 passed / 10 failed**(10 failed 全为预先存在基线:codexQuota×3 / groupBlocks×4 / useDialogManagement / useScrollBehavior / useWindowCallbacks,与模型域无关);模型域全绿(modelRegistry / ReasoningSelect 5 / ModelSelect 3 / useModelProviderState / useMessageSender.context 10,后者补 registry `claude-opus-4-7` supports1MContext=true 反映后端权威下发);后端 `ModelRegistryServiceSerializeTest` guard 放行派生字段 `supportedReasoningLevels`(serialize 下发),`compileTestJava` 通过。
- **关联**:迁移 P1-A1 / P1-A2 / P1-A3

### A3 · 前端模型归一化 / 映射 / 协议语义解释

- **严重度**:高 | **状态**:**✓ 已验证(Slice 2,2026-06-23)** | **归属**:总则一
- **位置**:`modelRegistry.ts:102-111`(`resolveClaudeModelId`)、`modelRegistry.ts:123-138`(`resolveClaudeRoleForModel`);`types.ts:344-362`(`getClaudeRoleFromModelId`)、`types.ts:364-371`(`normalizeClaudeModelId`,未知模型归一为 `claude-role-sonnet`)、`types.ts:315-323`(`apply1MContextSuffix`,前端构造 `[1m]` 后缀);`ButtonArea.tsx:62-105`(`applyModelMapping`)
- **现象**:前端做模型 ID→role 映射、ID 归一化、`[1m]` 协议后缀构造——均为业务/协议语义。
- **根因**:数据归一化与映射、协议语义解释一律下沉后端。
- **修复方向**:后端下发 `role` 字段,前端只透传;`[1m]` 后缀由后端解释/构造。
- **验收**:**✓ 达成**。webview 已删 `getClaudeRoleFromModelId` / `normalizeClaudeModelId` / `apply1MContextSuffix` / `has1MContextSuffix`;`resolveClaudeRoleForModel` 纯读 registry `role`;`[1m]` 后缀构造移至后端 `ModelRegistryConfig.apply1MSuffix`。
- **修复记录(Slice 2,2026-06-23)**:
  - **归一化/role 映射下沉(阶段 2.1)**:`types.ts` 删 `getClaudeRoleFromModelId`(id→role 内置推导)/`normalizeClaudeModelId`(未知 id 归一 sonnet);`modelRegistry.ts` `resolveClaudeModelId` 改纯 strip [1m]、`resolveClaudeRoleForModel` 改纯读 registry `role`(未加载返回 null,不再内置回退);`ButtonArea.tsx` `applyModelMapping` 改读 `registryModel.role`;`ModelSelect.tsx` / `useModelProviderState.ts` 删归一化调用。
  - **[1m] 协议后缀下沉(阶段 2.2)**:`types.ts` 删 `apply1MContextSuffix`(前端构造 [1m])+ `has1MContextSuffix`(死代码);`useMessageSender.ts` `/context` 改上送 `{model: stripped, longContextEnabled}` 意图;后端 `GetContextUsageActionHandler` 新增 `parseLongContextEnabled` + 经 `ModelRegistryConfig.apply1MSuffix(model, longContextEnabled)` 权威追加 [1m](范式对齐 `set_session_model` / `ModelProviderHandler` 的 longContextEnabled 意图通道)。
  - **行为变化(可接受)**:`resolveClaudeModelId` 未知 id 原样保留(不再归一 sonnet);`resolveClaudeRoleForModel` registry 未加载返回 null。风险极小(MODEL_SELECTION 下发回填 role)。
- **测试**:`tsc --noEmit` 0 错误(buildWebview `tsc && vite build` 同步成功,双重确认);webview vitest **819 passed / 10 failed**(10 failed 全为预先存在基线:codexQuota×3 / groupBlocks×4 / useDialogManagement / useScrollBehavior / useWindowCallbacks,与模型域无关);模型域全绿(modelRegistry 26 / ModelSelect 11 / ReasoningSelect 4 / ButtonArea 1 / useModelStatePersistence 3 / useMessageSender.context 10);后端 `compileTestJava` 通过,`GetContextUsageActionHandlerTest` 新增 4 个 `parseLongContextEnabled` 契约用例(true/false/缺失/空与非法)。
- **范围说明**:A3 验收(归一化+映射+[1m] 下沉)全部达成。`modelSupports1MContext`(能力判定)属 A2,已于 Slice 3 下沉删除——`useMessageSender` 等调用点改读 registry `item.supports1MContext`。
- **关联**:迁移 P1-A2

### A4 · useModelProviderState 前端计算 effectiveContextWindow(双轨矛盾)

- **严重度**:高 | **状态**:**✓ 已验证(Slice 2 + Slice 3,2026-06-23)** | **归属**:总则一
- **位置**:`webview/src/hooks/useModelProviderState.ts:121-123, 149-161, 213-264`
- **现象**:一边订阅 `DOWNSTREAM.MODEL_SELECTION`(合规),一边又在前端用 `modelSupports1MContext` 计算 `supports1M` / `effectiveContextWindow` 决定下发内容,双轨且自相矛盾。
- **根因**:effective context window 是业务计算结果,不应前端推导。
- **修复方向**:三个 handler 只上送 `{model, longContextEnabled}`,由后端回推 `effectiveContextWindow` 经 `MODEL_SELECTION` 下发回填。
- **验收**:**✓ 达成**。`useModelProviderState` 已无 `1_000_000` / `200_000` 字面量与 contextWindow 计算(`ONE_MILLION_CONTEXT_WINDOW` import 已删);三个 handler + registry 变更 effect 只上送意图;`MODEL_SELECTION` 订阅 `longContextEnabled` 改纯读后端权威 `supportsLongContext` 布尔(不再前端判 `effectiveContextWindow === 1M`)。
- **修复记录(Slice 2 + 3,2026-06-23)**:Slice 2 三个 handler(`handleModelSelect`/`handleProviderSelect`/`handleLongContextChange`)改上送 `{model, longContextEnabled}` 意图,effectiveContextWindow 交后端 `DefaultModelCapabilityResolver` 计算;Slice 3 删 `modelSupports1MContext` 4 处调用点(改读 registry `item.supports1MContext`),`MODEL_SELECTION` 订阅改 `setLongContextEnabled(selection.supportsLongContext === true)`(后端 `ModelProviderHandler:191` 下发权威布尔),消除前端 1M 数值推断。
- **测试**:同 A2(webview vitest 813/10 基线;`useModelProviderState` 全绿)。
- **关联**:迁移 P1-A2

### A5 · 前端业务默认值 / 校验

- **严重度**:中 | **状态**:待修复 | **归属**:总则一
- **位置**:`webview/src/utils/permissionDialogTimeout.ts:1-24`(`DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS=300` + `clampPermissionDialogTimeoutSeconds` 30–3600);`types.ts:245-247`(`isValidPermissionMode`);`types/provider.ts:211-230`(`CODEX_PROTECTED_ENV_KEYS`,注释自承需与 `CodexSDKBridge.java` 同步)
- **现象**:业务配置默认值、业务校验、业务规则表(保护 env 白名单)在前端硬编码。
- **根因**:配置默认值与业务校验应后端下发。
- **修复方向**:默认值/边界后端下发;保护 env 表下沉后端或经 SSOT 生成。
- **验收**:前端无业务默认值常量、无业务校验函数(纯格式校验除外)。
- **关联**:迁移 Phase 4 / P2-B

### A6 · 版本决策前后端双写

- **严重度**:中 | **状态**:待修复 | **归属**:总则一 / 四
- **位置**:`webview/src/components/settings/DependencySection/versioning.ts:15-71`(`getVersionAction` 决策)↔ 后端 `dependency/DependencyManager.java:281`
- **现象**:install / update / rollback 的版本决策前后端各算一遍。
- **根因**:决策属业务逻辑,前端只应渲染后端下发的动作结果。
- **修复方向**:后端 `DependencyManager` 下发动作结果,前端只渲染按钮态。
- **验收**:前端无 `getVersionAction` 决策函数。
- **关联**:迁移 Phase 4 · V6

### A7 · 工具分类纯前端硬编码

- **严重度**:中 | **状态**:待修复 | **归属**:总则一
- **位置**:`webview/src/utils/toolConstants.ts:7-44`(`READ` / `EDIT` / `BASH` / `SEARCH` / `AGENT` / `TASK_MANAGE` / `TRANSIENT_INTERNAL` / `FILE_MODIFY_TOOL_NAMES` 等集合)
- **现象**:工具名分类属业务语义,前端 hardcode。
- **根因**:工具分类应后端工具元数据经 SSOT 下发。
- **修复方向**:工具元数据纳入契约层后端下发;若仅显示所需,明确标注并经 SSOT。
- **验收**:前端无 hardcode 工具分类业务表。
- **关联**:迁移 Phase 4 · V7

### A8 · 会话标题候选判定(边界)

- **严重度**:低 | **状态**:待修复(可商榷)| **归属**:总则一
- **位置**:`webview/src/hooks/useChatComputations.ts:36-48`(`isSessionTitleUserCandidate`)
- **现象**:前端判定一条消息是否适合作会话标题(过滤 `[tool_result]` / 非 human / 纯 tool_result 块)。
- **根因**:会话标题是业务语义属性,宜后端给标题。
- **修复方向**:评估:若后端已有标题生成,前端改消费;否则保留并标注为展示过滤。
- **验收**:明确判定为业务或展示,前者下沉。
- **关联**:—

### A9 · 可回滚性判定(边界)

- **严重度**:低 | **状态**:待修复(可商榷)| **归属**:总则一
- **位置**:`webview/src/hooks/useChatComputations.ts:165-190`(`canRewindFromMessageIndex`,用 `FILE_MODIFY_TOOL_NAMES` 判定)
- **现象**:前端用工具名集合判定某消息「可回滚」。真回滚动作在后端(`rewind_files`),此为 UI 可用性判定。
- **根因**:「可回滚性」是业务语义属性;但 UI 可用性判定可接受前端做(依赖 A7 工具分类)。
- **修复方向**:随 A7 一起,工具分类后端下发后此判定消费下发字段。
- **验收**:工具名集合不再前端 hardcode(依赖 A7)。
- **关联**:A7

### A10 · PROVIDER_PRESETS 前端持业务默认值表

- **严重度**:中 | **状态**:待修复 | **归属**:总则一 / 三
- **位置**:`webview/src/types/provider.ts:350-456`(`PROVIDER_PRESETS`,含 base_url、默认模型名、API_TIMEOUT_MS);`provider.ts:57-67`(`CLAUDE_MODEL_MAPPING_ENV_KEYS`)、`provider.ts:126-131`(`ProviderCategory`)、`provider.ts:11-20,27-30`(`SPECIAL_PROVIDER_IDS` / `PROVIDER_IDS`)
- **现象**:文件自带注释(318-333 行)辩称是「UI 表单填充」,但内含 base_url、默认模型名等业务知识,本质是前端持有的业务默认值表。
- **根因**:第三方 provider 预置模板应后端下发或经 SSOT。
- **修复方向**:后端实现 provider preset 下发;前端删 `PROVIDER_PRESETS` / `AVAILABLE_PROVIDERS` 硬编码表。
- **验收**:前端无 provider base_url / 默认模型名业务知识。
- **关联**:迁移 P0-2

---

## 3. 根因 B · handler 旧分派路径违反 OCP(总则二 · 开闭)

### B1 · MessageDispatcher 线性遍历链仍在用(typed 通道兜底)

- **严重度**:中 | **状态**:已验证 | **归属**:总则二
- **位置**:`handler/core/MessageDispatcher.java:10-63`(`List<MessageHandler>` + `for` 循环);入口 `ClaudeChatWindow.java:732`
- **现象**:typed 通道未命中即回退到线性遍历的旧 dispatcher,违反 OCP。
- **根因**:双轨期过渡残留;18 个 legacy handler 尚未迁移。
- **修复方向**:随 B2 / B3 迁移完成后,删除 `messageDispatcher.dispatch` 兜底分支,`MessageDispatcher` 标 `@Deprecated`。
- **验收**:`ClaudeChatWindow.handleMessage` 仅剩 typed 通道;`MessageDispatcher` 无业务调用。
- **关联**:迁移 P1-C / P3-C
- **修复记录**(2026-06-23,B1):
  - **前提已满足**:B2(20/20 legacy handler 全迁移)+ B4(HistoryHandler)完成后,`MessageDispatcher` 注册 **0 个 handler**;`ClaudeChatWindow:731` 的 legacy 兜底 `dispatch` 永远返回 `false`,为纯死代码。B3 剩余 settings action 走 `LegacyMessageHandlerAdapter` 桥接到 typed dispatcher,不依赖 legacy `MessageDispatcher`,故 B1 与 B3 是否完成无关。
  - **兜底删除**:`ClaudeChatWindow#handleMessage` 删 legacy `if (messageDispatcher.dispatch(...))` 兜底分支,仅剩 typed `frontendActionDispatcher` 单通道;同步删 `dispose()` 中的 `messageDispatcher.clear()`、`messageDispatcher` 字段、`setMessageDispatcher` setter。
  - **装配清理**:`ChatWindowDelegate` 删 `new MessageDispatcher()` / `host.setMessageDispatcher(...)` / `DelegateHost.setMessageDispatcher` 接口声明;"Registered N message handlers" 日志改统计 typed handler 数(`typedHandlers.size()`)。
  - **类删除(优于原建议)**:原修复方向为「标 `@Deprecated`」,但全仓清理后 `MessageDispatcher` 类**零引用**(无 main/test/resources/Adapter 依赖),**直接删除整个类**比 `@Deprecated` 更彻底消除死代码,完全满足验收「无业务调用」。`MessageHandler` 接口 / `BaseMessageHandler` / `SettingsHandler` 保留(B3 桥接依赖 `MessageHandler` 接口)。
  - **验证**:`gradle compileJava` 通过;`compileTestJava` 通过(证明无测试引用 `MessageDispatcher`,删除安全);全仓 grep `MessageDispatcher`(排除 `FrontendActionDispatcher`)零残留。`instrumentTestCode` 失败(`D:\tools\jdk17\Packages does not exist`)为预先存在的 JDK 路径环境问题(IntelliJ 插件 gradle 插桩任务),与 B1 无关。行为零变化(删的是永远返回 false 的死路径)。

### B2 · 20 个 legacy MessageHandler 用 SUPPORTED_TYPES 字符串数组 + switch(type)

- **严重度**:高 | **状态**:**✓ 已完成(20/20)**,ClipboardHandler + TabHandler + RewindHandler + ContextHandler + PromptEnhancerHandler + FileExportHandler + UndoFileHandler + PermissionHandler + SessionHandler + WindowEventHandler + McpServerHandler + CodexMcpServerHandler + AgentHandler + SkillHandler + PromptHandler + DependencyHandler + NodeProcessHandler + FileHandler + DiffHandler + ProviderHandler ✓ 2026-06-23) | **归属**:总则二
- **位置**(均含 `private static final String[] SUPPORTED_TYPES` + `switch(type)`):
  - ~~`handler/SessionHandler.java:40`~~ ✓ 已迁移(`handler/session/{SendMessage,SendMessageWithAttachments,InterruptSession,RestartSession}ActionHandler` + `SessionActionHandlers` 容器,2026-06-22)、~~`handler/WindowEventHandler.java:17`~~ ✓ 已迁移(`handler/window/{Heartbeat,TabLoadingChanged,TabStatusChanged,CreateNewSession,FrontendReady,RefreshSlashCommands}ActionHandler` + `WindowActionHandlers` 容器,2026-06-22)
  - ~~`handler/McpServerHandler.java:26`~~ ✓ 已迁移(`handler/mcp/{GetMcpServers,GetMcpServerStatus,GetMcpServerTools,AddMcpServer,UpdateMcpServer,DeleteMcpServer,ToggleMcpServer,ValidateMcpServer}ActionHandler` + `McpServerActionHandlers` 容器,2026-06-22)、~~`handler/CodexMcpServerHandler.java:27`~~ ✓ 已迁移(`handler/codex/{GetCodexMcpServers,GetCodexMcpServerStatus,GetCodexMcpServerTools,AddCodexMcpServer,UpdateCodexMcpServer,DeleteCodexMcpServer,ToggleCodexMcpServer,ValidateCodexMcpServer}ActionHandler` + `CodexMcpServerActionHandlers` 容器,2026-06-22)
  - ~~`handler/AgentHandler.java:37`~~ ✓ 已迁移(`handler/agent/{GetAgents,AddAgent,UpdateAgent,DeleteAgent,GetSelectedAgent,SetSelectedAgent,ExportAgents,ImportAgentsFile,SaveImportedAgents}ActionHandler` + `AgentActionHandlers` 容器,2026-06-22)、~~`handler/SkillHandler.java:37`~~ ✓ 已迁移(`handler/skill/{GetAllSkills,ImportSkill,DeleteSkill,OpenSkill,ToggleSkill}ActionHandler` + `SkillActionHandlers` 容器,2026-06-22)、~~`handler/PromptHandler.java:45`~~ ✓ 已迁移(`handler/prompt/{GetPrompts,GetProjectInfo,AddPrompt,UpdatePrompt,DeletePrompt,ExportPrompts,ImportPromptsFile,SaveImportedPrompts}ActionHandler` + `PromptActionHandlers` 容器,2026-06-22)、~~`handler/PromptEnhancerHandler.java:56`~~ ✓ 已迁移(`handler/enhance/EnhancePromptActionHandler`,2026-06-22)
  - ~~`handler/DependencyHandler.java:32`~~ ✓ 已迁移(`handler/dependency/{GetDependencyStatus,InstallDependency,UninstallDependency,UpdateDependency,CheckDependencyUpdates,GetDependencyVersions,CheckNodeEnvironment}ActionHandler` + `DependencyActionHandlers` 容器,2026-06-22)、~~`handler/NodeProcessHandler.java:36`~~ ✓ 已迁移(`handler/nodeprocess/{GetNodeProcesses,KillNodeProcess,KillAllOrphans,RestartNodeDaemon}ActionHandler` + `NodeProcessActionHandlers` 容器,2026-06-23)
  - ~~`handler/file/FileHandler.java:33`~~ ✓ 已迁移(`handler/file/{ListFiles,OpenFile,OpenBrowser,OpenClass,GetLinkifyCapabilities,ResolveFilePath}ActionHandler` + `FileActionHandlers` 容器(含 static FileSet/FileListRequest/createFileObject/getRelativePath/addVirtualFile,4 个 collector 引用前缀同步改),2026-06-23)、~~`handler/file/FileExportHandler.java:29`~~ ✓ 已迁移(`handler/file/{SaveMarkdown,SaveJson}ActionHandler`,2026-06-22)、~~`handler/file/UndoFileHandler.java:31`~~ ✓ 已迁移(`handler/file/{UndoFileChanges,UndoAllFileChanges}ActionHandler`,2026-06-22)
  - ~~`handler/diff/DiffHandler.java`~~ ✓ 已迁移(`handler/diff/{RefreshFile,ShowDiff,ShowMultiEditDiff,ShowEditPreviewDiff,ShowEditFullDiff,ShowEditableDiff,ShowInteractiveDiff}ActionHandler` + `DiffActionHandlers` 容器(承载 DiffRequestDispatcher 责任链组装),2026-06-23)、~~`handler/ClipboardHandler.java:22`~~ ✓ 已迁移(`handler/clipboard/{Read,Write}ClipboardActionHandler`,2026-06-22)、~~`handler/TabHandler.java:27`~~ ✓ 已迁移(`handler/tab/CreateNewTabActionHandler`,2026-06-22)、~~`handler/RewindHandler.java:24`~~ ✓ 已迁移(`handler/rewind/RewindFilesActionHandler`,2026-06-22)、~~`handler/ContextHandler.java:22`~~ ✓ 已迁移(`handler/context/GetContextUsageActionHandler`,2026-06-22)
  - ~~`handler/PermissionHandler.java:34`~~ ✓ 已迁移(`handler/permission/{PermissionDecision,AskUserQuestionResponse,PlanApprovalResponse}ActionHandler` + `PermissionActionHandlers` 容器,2026-06-22)、~~`handler/provider/ProviderHandler.java:13`~~ ✓ 已迁移(`handler/provider/{GetProviders,GetCurrentClaudeConfig,GetThinkingEnabled,SetThinkingEnabled,AddProvider,UpdateProvider,DeleteProvider,SwitchProvider,GetActiveProvider,PreviewCcSwitchImport,OpenFileChooserForCcSwitch,SaveImportedProviders,SortProviders,GetCodexProviders,GetCurrentCodexConfig,AddCodexProvider,UpdateCodexProvider,DeleteCodexProvider,SwitchCodexProvider,RevokeCodexLocalConfigAuthorization,GetActiveCodexProvider,SortCodexProviders}ActionHandler` + `ProviderActionHandlers` 容器(薄委托 4 operation 类:Claude/Codex/ImportExport/Ordering),2026-06-23)
  - 装配:typed 区 `ui/ChatWindowDelegate.java:323-338`(`typedHandlers.add(...)`,B2 迁移落点)+ legacy 区 `342-356`(`registerHandler(...)` 逐个挂载,剩余未迁类)
- **现象**:上行 action 分派依赖字符串数组 + switch,新增 action 需改既有 handler,违反开闭。
- **根因**:尚未迁移到 `FrontendActionHandler<T>`(V9 范式)。
- **修复方向**:按 `handler/{domain}/XxxActionHandler.java implements FrontendActionHandler<T>` SOP 逐个迁移(范例:`handler/settings/GetModelRegistryActionHandler.java`;B2 已迁范例:`handler/clipboard/{Read,Write}ClipboardActionHandler`、`handler/tab/CreateNewTabActionHandler`、`handler/rewind/RewindFilesActionHandler`),从 `SUPPORTED_TYPES` 移除条目。
- **验收**:18 个 handler 全部迁移;`SUPPORTED_TYPES` / `switch(type)` 字符串分派清零。
- **关联**:迁移 P1-C(最大块,可分多子任务)

### B3 · SettingsHandler SUPPORTED_TYPES 60+ 字符串 + switch(type)

- **严重度**:高 | **状态**:进行中(分批迁移,13/~60 ✓ 三个子域已完成 2026-06-23) | **归属**:总则二
- **位置**:`handler/SettingsHandler.java:32-92`(`SUPPORTED_TYPES` ~60 字符串)、`SettingsHandler.java:122-319`(`switch(type)` 60+ case);经 `LegacyMessageHandlerAdapter.from(...)` 桥接进 typed 注册表(`ChatWindowDelegate.java:338`)
- **现象**:最大的一处字符串分派;新增设置 action 易往数组加条目(AGENTS.md 明令禁止的旧路径)。
- **根因**:settings 域迁移仅完成 Model Registry / Appearance / V9 三切片,余 ~50 action 仍走旧路径。
- **修复方向**:按 SOP 拆解 63 条 action 为独立 `handler/settings/*ActionHandler.java`,每条迁移后从 `SUPPORTED_TYPES` 移除。
- **B3 SOP(2026-06-23 验证)**:机制=`LegacyMessageHandlerAdapter.from(settingsHandler)` 对每个 SUPPORTED_TYPE 用 `UpstreamAction.fromValue()` 查 enum 包装为 LegacyActionHandler;`FrontendActionDispatcher` 用 `putIfAbsent`(重复抛 IllegalArgumentException)。**故每批必须同构建**:① 建 typed handler+测试 ② ChatWindowDelegate 在 `addAll(LegacyMessageHandlerAdapter.from(...))` **之前** add typed handler ③ 同步从 `SUPPORTED_TYPES` 移除字符串 + 移除 switch case + 移除已无引用的字段/构造初始化。④ 构建(防启动崩溃)。
- **已迁子域**(13/60):
  - permission-mode(3):`GetMode/SetMode/SetSessionModeActionHandler` 直接持有 `PermissionModeHandler` ✓ 2026-06-23
  - input-history(4):`GetInputHistory/RecordInputHistory/DeleteInputHistoryItem/ClearInputHistoryActionHandler` 直接持有 `InputHistoryHandler` ✓ 2026-06-23
  - model-provider(6):`SetModel/SetSessionModel/SetProvider/SetSessionProvider/SetReasoningEffort/SetCodexFastModeActionHandler` 经 `ModelProviderHandler`+`UsagePushService` 委托 ✓ 2026-06-23
- **待迁子域分组**:project-config(~40,最大)、user-language(3,内联)、runtime-policy(4,内联)。
- **验收**:`SettingsHandler.SUPPORTED_TYPES` 为空或仅剩标注废弃残留;`LegacyMessageHandlerAdapter` 无业务使用方。
- **关联**:迁移 P1-C(优先级最高)

### B4 · HistoryHandler 孤儿(实现 MessageHandler 但未注册)

- **严重度**:中 | **状态**:**✓ 已完成(迁移为 typed handler,2026-06-23)** | **归属**:总则二
- **位置**:`handler/history/HistoryHandler.java:17`(实现 `MessageHandler`,11 个 SUPPORTED_TYPES);`ui/ChatWindowDelegate.java:388`(`messageDispatcher.registerHandler(historyHandler)`)
- **现象**:~~该 handler 实现了 `MessageHandler` 接口,却在 `ChatWindowDelegate` 单独创建、未注册到任何 dispatcher~~ — **前提错误**。经核实,HistoryHandler 在 `ChatWindowDelegate.java:388` 已正确注册到 MessageDispatcher。
- **根因**:登记时行号引用有误(原文称 :382,实际 :388),且遗漏了注册调用。
- **修复**:已按 B2 范式迁移为 `handler/history/{LoadHistoryData,LoadSession,DeleteSession,DeleteSessions,ExportSession,ToggleFavorite,UpdateTitle,DeleteTitle,DeepSearchHistory,LoadSubagentSession,ConvertToCliSession}ActionHandler` + `HistoryActionHandlers` 容器(承载 7 service + currentProvider 共享状态 + SessionLoadCallback 接口)。附带清理 no-op 死代码:`DelegateHost.setHistoryHandler` 接口声明 + `ClaudeChatWindow` no-op 实现 + `ChatWindowDelegate` 调用全部移除(原注释 "No-op: handler is set but no longer consumed" 已坐实为死代码)。
- **验收**:~~HistoryHandler 的使用路径明确~~ — 已明确:正常注册在 MessageDispatcher,非孤儿。
- **关联**:归入 B2 legacy handler 迁移候选

### B5 · 下行 type 字面量散落(未用 DownstreamEvent 枚举)

- **严重度**:中 | **状态**:已验证 | **归属**:总则二
- **位置**:`handler/SettingsHandler.java:111,353,374,390,393` 等 `dispatchEvent("theme.changed" / "language.apply" / "runtime_policy" / "runtime_policy_error", ...)`;其余 legacy handler 类似
- **现象**:下行事件 type 以字符串字面量散落,与 `DownstreamEvent` 枚举 SSOT 脱钩。
- **根因**:下行规范(AGENTS.md 总则二新增条款)此前缺失,handler 习惯手写字面量。
- **修复方向**:全部改为 `ctx.dispatchEvent(DownstreamEvent.XXX.value(), ...)`;新派 typed handler 已是范例(`SetAppearanceConfigActionHandler.java:34`)。
- **验收**:后端无 `dispatchEvent("裸字符串"...)`;下行 type 全部来自 `DownstreamEvent` 枚举。
- **实际迁移**(2026-06-23 闭环):27+ handler 文件的全部 `dispatchEvent` 直调、`pushJson`/`respondWithJson`、间接 helper(`sendErrorResult`/`handleBooleanToggle`/`applyAiProviderConfig`/三元 `eventType`)的字面量 type 统一改为 `DownstreamEvent.X.value()`(`ProjectConfigHandler` 32 处最多);验收 grep `dispatchEvent("` 与 `(pushJson|respondWithJson)("` 均 0,`compileJava` 通过。
- **关联**:迁移 P1-B(后端侧)

---

## 4. 根因 C · SSOT 链路缺口(总则三 · 契约单一真相源)

### C1 · payload 字段结构完全未生成(前后端各写)

- **严重度**:高 | **状态**:待修复 | **归属**:总则三
- **位置**:manifest schema 仅 `{name, value}`(`protocol/ProtocolManifestGenerator.java:44-62`);`webview/src/generated/protocol.ts` 全文无 payload interface;前端 `modelRegistry.ts:16-18` 手写 `ModelRegistryPayload` ↔ 后端 `ModelRegistryService.java` / `ModelConfig.java` 各自定义
- **现象**:payload 字段结构前后端各写一套,字段增删/重命名无编译期校验,运行时静默断链。
- **根因**:manifest 仅生成消息名,未生成字段结构。
- **修复方向**:扩展 `ProtocolManifestGenerator` 反射 record components 写入 `payloadSchemas`,mjs 从 manifest 生成 TS 字段类型;先建两端字段守门测试(后端反射 ↔ 前端覆盖)。
- **验收**:manifest 含 `payloadSchemas`;前端 payload 类型从 generated 导入;两端字段守门测试存在。
- **关联**:迁移 Phase 1(Task 1.2/1.3 守门)+ Phase 2(V3 自动化)

### C2 · 业务枚举 SSOT 全未落地(前端手写、后端散落字符串)

- **严重度**:高 | **状态**:已验证(PermissionMode / ReasoningEffort / ProviderType ✓;CodexFastMode 经调查降级为「无 bug · 现状可接受」) | **归属**:总则三
- **位置**:
  - `PermissionMode` ✅(2026-06-22 子切片已落地):新建 `protocol/PermissionMode.java`(5 值含 `autoEdit` 别名,`implements ProtocolValue`);前端 `types.ts` re-export generated 类型,`VALID_PERMISSION_MODE_IDS` 改从 SSOT `PERMISSION_MODE` 派生(原从展示列表 `AVAILABLE_MODES` 4 值派生,漏 `autoEdit` → 后端下发时状态丢失 bug 已修)。`CliConstants.PERM_*` 散落常量待后续收敛。
  - `ReasoningEffort` ✅(2026-06-22 子切片已落地):新建 `protocol/ReasoningEffort.java`(5 值 low/medium/high/xhigh/max,全集 = Claude API,`implements ProtocolValue`);前端 `types.ts` re-export generated 类型,`useModelStatePersistence.ts` `REASONING_VALUES` 改从 SSOT `REASONING_EFFORT` 派生(原手写 5 档与 `ClaudeRole.java:127` 重复)。默认 high 与 `CommonConstants.DEFAULT_REASONING_EFFORT`(C3)对齐。`ClaudeRole.reasoningLevels()` 裸 `List.of` 待后续收敛为枚举引用。
  - `CodexFastMode` ⚠️ 经调查降级(2026-06-22,**无 bug**,系误读):后端 `SessionSendService.normalizeRequestedCodexServiceTier`(`SessionSendService.java:460-503`)的「6 个字符串」实为**输入别名归一**(`priority`→fast;`standard`/`default`/`none`→normal/null),**非协议值域**;协议线上值域只有 `normal`/`fast` 两档,前端 `CodexFastMode` 完全覆盖;且为前端→后端单向(`useCodexProvider`→`set_codex_fast_mode`,localStorage 持久化无后端回显下发),不存在 PermissionMode `autoEdit` 那类「后端下发前端不认」的丢值形态。原「值域 ⊊」描述系误读,降级为现状可接受。
  - `ProviderType` ✅(2026-06-22 子切片已落地):`session/runtime/ProviderType.java` 改 `implements ProtocolValue` + 带参构造 `CLAUDE("claude")`/`CODEX("codex")` + `value()`/`fromValue()`(保留 `fromString` 宽容解析供 3 路由调用方,`toLowerCase` 委托 `value()` 兼容);纳入 manifest 生成 + mjs 产出 `PROVIDER_TYPE`;前端 `types/provider.ts` re-export `ProviderType` + `PROVIDER_IDS` 改 SSOT 派生(消除手写第二真相源);核心消费点 `modelRegistry.ts`/`aiFeatureConfig.ts` 引用 generated。
- **现象**:三大业务枚举 + ProviderType 全部前端手写联合类型,后端无枚举(散落字符串常量),值域不一致。
- **根因**:枚举 SSOT 机制未覆盖业务枚举。
- **修复方向**:新建 `protocol/PermissionMode.java`、`protocol/ReasoningEffort.java` 枚举(`implements ProtocolValue`),纳入生成;前端类型改从 generated 导出。(worktree 中已有未合入的尝试可参考)
- **验收**:前端无手写业务枚举联合;前后端值域一致。
- **关联**:迁移 P2-A
- **修复记录**(2026-06-22,PermissionMode 子切片):
  - **Java SSOT**:新建 `protocol/PermissionMode.java`(`DEFAULT/ACCEPT_EDITS/PLAN/BYPASS_PERMISSIONS/AUTO_EDIT`,`AUTO_EDIT` 是 `ACCEPT_EDITS` 历史别名,值域对齐 `SessionState#VALID_PERMISSION_MODES`),`implements ProtocolValue` 提供 `value()`/`fromValue()`。
  - **生成链**:`ProtocolManifestGenerator` 反射 `PermissionMode` 写 manifest;`generate-protocol-types.mjs` emit `PERMISSION_MODE` 常量 + 派生 `PermissionMode` 类型;两处日志均报 permissionMode 计数。
  - **前端集成**:`types.ts` 删手写联合改 re-export generated(遵循 `import type` 建本地绑定 + `export type` 无 `from` 陷阱,见 [[protocol-enum-ssot-promotion-workflow]]);`VALID_PERMISSION_MODE_IDS` 改 `Object.values(PERMISSION_MODE)`(5 值),与展示列表 `AVAILABLE_MODES`(4 值,不含 `autoEdit`)**解耦**——`autoEdit` 后端可下发,UI 不展示但校验必须接受;`ModeSelect.getModeIcon` 加 `autoEdit` case 显示 acceptEdits 图标。
  - **测试**:`ProtocolEnumCoverageTest` 加值域覆盖 + `fromValue` 往返 + 唯一性断言(gradle BUILD SUCCESSFUL);前端新增 `permissionMode.test.ts` 3 测试锁定 `autoEdit` 合法(回归防护原"状态丢失"bug),vitest 全绿,0 新失败(基线 ButtonArea/codexQuota 失败不变)。
  - **剩余**(PermissionMode 视角):CodexFastMode / ProviderType / ReasoningEffort 当时未修,ReasoningEffort 已于同日补齐(见下)。
- **修复记录**(2026-06-22,ReasoningEffort 子切片):
  - **Java SSOT**:新建 `protocol/ReasoningEffort.java`(`LOW/MEDIUM/HIGH/XHIGH/MAX`,全集 5 档 = Claude API,`implements ProtocolValue` 提供 `value()`/`fromValue()`);默认 `HIGH` 与 `CommonConstants.DEFAULT_REASONING_EFFORT`(C3)语义一致。
  - **生成链**:`ProtocolManifestGenerator` 反射 `ReasoningEffort`;mjs emit `REASONING_EFFORT` 常量 + 派生类型;两处日志报 reasoningEffort 计数(gradle 输出 "5 reasoningEffort")。
  - **前端集成**:`types.ts` 删手写联合改 re-export generated;`useModelStatePersistence.ts` `REASONING_VALUES` 改 `Object.values(REASONING_EFFORT)`(消除与 `ClaudeRole.java:127` 的重复第二真相源)。展示过滤(`ReasoningSelect` 按 role/provider 子集)与校验全集解耦,同 PermissionMode 模式。
  - **测试**:`ProtocolEnumCoverageTest` 加值域 + 往返 + 唯一性(gradle BUILD SUCCESSFUL);前端新增 `reasoningEffort.test.ts` 2 测试锁定全集 5 档,vitest 全绿 0 新失败(基线 ButtonArea/codexQuota 不变)。
  - **剩余**:`ClaudeRole.reasoningLevels()` 裸 `List.of` 待收敛为枚举引用(涉及 API 返回类型 `List<String>`,留后续);`CliConstants.PERM_*` 散落常量待后续收敛。

- **修复记录**(2026-06-22,ProviderType 子切片,C2 剩余 + C9):
  - **Java SSOT**:`session/runtime/ProviderType.java` 由无参枚举 `CLAUDE, CODEX` 改带参构造 `CLAUDE("claude")`/`CODEX("codex")` + `implements ProtocolValue`;新增 `value()`(对齐 `CommonConstants.PROVIDER_CLAUDE/PROVIDER_CODEX`)+ `fromValue(String)→Optional` 严格往返(范式对齐 PermissionMode/ReasoningEffort)。**保留 `fromString` 不变**(3 个调用方 `SessionSendService:77`/`SettingsHandler:503`/`EffectiveRuntimeResolver:40` 依赖其 null/未知→CLAUDE 的宽容路由语义);`toLowerCase()` 改委托 `value()` 兼容(grep 确认无调用方,留待后续清理)。保留在 `session.runtime` 包(SSOT 语义不要求物理位置,移动引发大规模 import churn)。
  - **生成链**:`ProtocolManifestGenerator` 加 `providerType` 段 + 日志计数;`generate-protocol-types.mjs` 加 `providerTypeJavaPath` / 解析 / `PROVIDER_TYPE` 常量 + 派生 `ProviderType` 类型 / stub / 存在性检查 / 日志计数。mjs 严格 regex 要求带参枚举,故 ProviderType 必须由无参改带参——这是本切片的核心改动点。
  - **前端集成**:`types/provider.ts` re-export `ProviderType`(`export type { ProviderType } from '../generated/protocol'`)+ `PROVIDER_IDS = PROVIDER_TYPE`(消除与 `CommonConstants.PROVIDER_*` 重复的手写第二真相源,值结构不变不破坏消费方);核心真相源消费点 `utils/modelRegistry.ts`(`ModelRegistryItem.provider`)+ `types/aiFeatureConfig.ts`(`AiFeatureProvider`)改引用 generated `ProviderType`。局部别名(`RuntimeProviderSelect` `ProviderKind`、`RuntimePolicySection` `ProviderKey`)与带 `string` 兜底的宽类型(settings 组件 `'claude'|'codex'|string`)本轮保留——展示/宽类型不影响 SSOT 闭环,留后续。
  - **测试**:`ProtocolEnumCoverageTest` 加 `providerTypeCoversProtocolValues`(值域)+ `providerTypeFromValueRoundTrip`(往返+empty)+ `protocolValuesAreUniqueWithinEachDirection` 加 ProviderType(gradle BUILD SUCCESSFUL,含 `EffectiveRuntimeResolverTest` 等使用方全量编译);前端新增 `types/providerType.test.ts` 2 测试锁定 `PROVIDER_TYPE = [claude, codex]`;webview tsc 0 error;vitest **stash 对比验证 0 新失败**(11 基线失败 ButtonArea/codexQuota/groupBlocks/useDialogManagement/useScrollBehavior/useWindowCallbacks 全部预先存在,与本切片无关)。

- **调查记录**(2026-06-22,CodexFastMode 降级,非修复):
  - 经 systematic-debugging 根因调查(Phase 1–3)确认 CodexFastMode **无真实 bug**:① 协议线上值域只有 `normal`/`fast` 两档(前端 `useCodexProvider` 发送值,后端 `CODEX_FAST_SERVICE_TIER="fast"` / null);② `normalizeRequestedCodexServiceTier` 的 6 个字符串是**输入别名归一**(`fast`/`priority`→`fast`;`normal`/`standard`/`default`/`none`→null),属防御性解析,非协议值域;③ codexFastMode 为前端→后端单向(localStorage 持久化,`ModelProviderHandler.handleSetCodexFastMode` 只接收不回显),无后端→前端下发路径,不存在 PermissionMode `autoEdit` 那类丢值形态。
  - 决策:修正本条「值域 ⊊」误读描述,降级为「现状可接受」,**不做枚举化 churn**(符合 systematic-debugging「不修不存在的问题」与既有「强推是 churn」判断)。若未来后端新增回显下发或扩展值域,再评估 SSOT 化。

### C3 · 默认值漂移(已发生)

- **严重度**:高 | **状态**:已验证 | **归属**:总则三
- **位置**:`provider/codex/CodexSDKBridge.java:461,666`(reasoning effort 默认 `"medium"`)↔ `common/CommonConstants.java:59`(`DEFAULT_REASONING_EFFORT="high"`)
- **现象**:后端内部两套 reasoning effort 默认值互相矛盾——双写最直接的代价。
- **根因**:默认值无单一来源。
- **修复方向**:后端提取统一 `DEFAULT_REASONING_EFFORT` 常量,替换两处;随 C2 枚举化后由枚举承载默认。
- **验收**:后端 reasoning effort 默认值单一;测试覆盖。
- **关联**:迁移 P2-B / P2-A
- **修复记录**(2026-06-22):提取 `CodexSDKBridge.resolveReasoningEffort(String)` 私有静态方法,L461/L666 两处兜底由独立 `"medium"` 字面量统一改为引用 `CommonConstants.DEFAULT_REASONING_EFFORT`;新增 `CodexSDKBridgeEnvTest` 两条守门测试(null→SSOT 默认、常量值固定 high),均通过。原 5 个 EnvTest 平台 NPE 为预先存在(gradle 裸跑 `ApplicationManager.getApplication()` 为 null),与本次无关。

### C4 · 前端协议字面量第二真相源(Central Event Registry + 调用点)

- **严重度**:高 | **状态**:待修复 | **归属**:总则三 / 四
- **位置**:`webview/src/bridge/events/index.ts:23-165`(`BRIDGE_EVENTS` 手写 ~130 条 type 字面量,与 `protocol.ts` 的 `DOWNSTREAM` 并存);`generate-protocol-types.mjs` 产物仅 6 文件采用;100+ 处 `sendBridgeEvent('xxx')` / `sendToJava('xxx')` 裸字符串调用点(`useMessageSender.ts`、`useSessionManagement.ts`、`useModelProviderState.ts`、`DependencySection`、`useProviderManagement.ts` 等)
- **现象**:`bridge/events/index.ts` 是与 `generated/protocol.ts` 并存的手写第二真相源;绝大多数调用点未使用 generated 常量。
- **根因**:协议名 SSOT 生成链已具备,但消费侧迁移未完成。
- **修复方向**:`BRIDGE_EVENTS.type` 改引用 `DOWNSTREAM.XXX`(保留 `kind`);227 处调用点机械替换为 `sendAction(UPSTREAM.*)` / `subscribeEvent(DOWNSTREAM.*)`。
- **验收**:webview 无手写协议 type 字面量(除 generated);`bridge/events/index.ts` 引用 `DOWNSTREAM` 常量。
- **关联**:迁移 P1-B

### C5 · 业务默认值前后端手抄

- **严重度**:中 | **状态**:待修复 | **归属**:总则三
- **位置**:`DEFAULT_CONTEXT_WINDOW`(后端 `CommonConstants.java:70=200_000` ↔ 前端 `types.ts:377` 手抄,注释自称"与后端对齐 SSOT"但实为手抄);`DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS`(后端 `PermissionDialogTimeoutSettings.java:12=300` ↔ 前端 `permissionDialogTimeout.ts:1`);`ONE_MILLION_CONTEXT_WINDOW` 同类
- **现象**:业务默认值前后端手抄,后端改值前端不会自动跟。
- **根因**:默认值未经 SSOT 生成或下发。
- **修复方向**:后端统一 `DEFAULT_CONTEXT_WINDOW` 常量(替换 ~10 处裸字面量);前端默认值改从 generated 导出或后端下发。
- **验收**:前后端无手抄默认值;后端改值前端自动跟。
- **关联**:迁移 P2-B

### C6 · ProtocolValue 无 desc(desc 约定空挂)

- **严重度**:中 | **状态**:已验证 | **归属**:总则三
- **位置**:`protocol/ProtocolValue.java:3-5`(仅 `String value()`,无 `desc()`)
- **现象**:AGENTS.md 附录"枚举 value/desc 统一"中的 desc 部分空挂,枚举语义无机器可读描述。
- **根因**:接口未声明 desc。
- **修复方向**:随 C2 业务枚举枚举化,为 `ProtocolValue` 增 `desc()`(或降级 AGENTS.md 该约定)。
- **验收**:desc 有来源或约定明确降级。
- **关联**:迁移 P2-A
- **修复记录**(2026-06-22):为 `ProtocolValue` 增 `default String desc()` 返回空串(非 null)。选 default 而非抽象方法——现有 `UpstreamAction`/`DownstreamEvent` 为单参 value 枚举(各几十个常量),抽象方法会强制全改,default 则零改动即合规,符合开闭。为未来 C2 业务枚举(PermissionMode/ReasoningEffort)铺路:它们覆盖 `desc()` 返回实际描述。新增 `ProtocolValueTest` 4 条守门(default 返回 ""、非 null、value 契约不变、可覆盖),全绿;全项目编译通过。

### C7 · 双 manifest 写入者(Gradle task 无消费者)

- **严重度**:低 | **状态**:待修复 | **归属**:总则三
- **位置**:`build.gradle:387-407`(`generateProtocol` task 写 manifest)↔ `generate-protocol-types.mjs:113`(mjs 也写 manifest)
- **现象**:两个写入者写同一路径 `protocol-manifest.json`,Gradle task 实际无消费者(mjs 直读 Java 源为主路径),易误导维护者。
- **根因**:SSOT 链路切换主路径后未清理旧 task。
- **修复方向**:评估 deprecate `ProtocolManifestGenerator` / `generateProtocol` task;或保留并明确标注为可选兼容产物。
- **验收**:manifest 写入者唯一或旧 task 明确标注可选。
- **关联**:迁移 P0-1

### C8 · mjs regex 解析脆弱(静默漏项风险)

- **严重度**:中 | **状态**:已验证 | **归属**:总则三
- **位置**:`webview/scripts/generate-protocol-types.mjs`(`parseEnumSource` L72,严格 entryPattern L74 + 宽松漂移启发 L82)
- **现象**:依赖 `NAME("value"),` 格式;Java 源若改成多参枚举(如未来加 desc)会静默漏解析,而非报错。
- **根因**:regex 解析比反射脆弱。
- **修复方向**:加漂移校验(生成内容与磁盘比对不一致 WARN);长期考虑走反射(manifest)为主路径。
- **验收**:枚举格式变化时生成器有显式告警。
- **关联**:迁移 P0-1
- **修复记录**(2026-06-22):提取纯函数 `parseEnumSource(source, label)` 并 export;严格 entryPattern 之外加宽松启发(`/^\s*[A-Z][A-Z0-9_]*\(["']/gm`,覆盖单参/多参),两者计数不一致时 `console.warn` 显式 `DRIFT WARNING`;主路径收口到 `main()` + `isMain` 守卫(`process.argv[1] === fileURLToPath(import.meta.url)`),使 import 无副作用、可单测;配 `generate-protocol-types.d.mts` 类型声明。测试 `src/generated/generate-protocol-types.test.ts` 4 例(单参解析/全单参不误报/多参触发 DRIFT WARN/空源不抛)全绿;生成器对当前 190 upstream + 124 downstream 单参格式零误报。注:验收采用"宽松启发计数 vs 严格 regex 计数"漂移检测(非文档原述"生成内容与磁盘比对"),因后者需文件级 diff 基础设施,启发计数更轻且直接捕获"格式偏离"根因。

### C9 · ProviderType 枚举未纳入 manifest 生成

- **严重度**:中 | **状态**:已验证 | **归属**:总则三
- **位置**:`session/runtime/ProviderType.java:9`(枚举 `CLAUDE`/`CODEX` 存在,但 value 经 `toLowerCase()` 返回 `CommonConstants.PROVIDER_*` 字符串);`ProtocolManifestGenerator.java:46-62` 只遍历 Upstream/Downstream
- **现象**:已枚举化的业务类型仍未享受生成红利,前端 `provider: 'claude'|'codex'` 在 `modelRegistry.ts:8` 内联手写。
- **根因**:生成器未覆盖业务枚举。
- **修复方向**:随 C2 一起,扩展生成器支持业务枚举。
- **验收**:`ProviderType` 经生成到前端。
- **关联**:迁移 P2-A
- **修复记录**(2026-06-22):随 C2 ProviderType 子切片一并落地(详见 C2 修复记录)。`ProviderType` 改 `implements ProtocolValue` + 带参构造(满足 mjs 严格 regex)+ `value()`/`fromValue()`;`ProtocolManifestGenerator` 加 `providerType` 段;mjs 产出 `PROVIDER_TYPE` 常量 + `ProviderType` 类型;前端 `types/provider.ts` re-export + `PROVIDER_IDS` SSOT 派生。前端 `provider: 'claude'|'codex'` 手写联合由 generated `ProviderType` 替代。

### C10 · window.xxx 旧回调名与归一化总线并存

- **严重度**:中 | **状态**:待修复 | **归属**:总则三
- **位置**:`hooks/windowCallbacks/registerCallbacks/messageCallbacks.ts:384`(`window.updateMessages`)、`permissionCallbacks.ts:42`(`window.showPermissionDialog`)、`streamingCallbacks.ts:240-241`(`window.onStreamEnd`);`bootstrap/pendingSlots.ts:52,205`(裸名 `'updateMessages'` / `'showPermissionDialog'`)
- **现象**:下行总线已归一化为 `window.__bridge.dispatch`,但旧 `window.xxx` 回调名仍并存,属过渡残留。
- **根因**:bridge 归一化重构(Phase 0–6)的尾部遗留。
- **修复方向**:收口为统一 dispatch;旧别名经 `registerLegacyAlias` 过渡后移除。
- **验收**:无 `window.xxx` 直接赋值回调;统一经 dispatch。
- **关联**:bridge 归一化后续(见 `docs/feat/bridge-normalization.md`)

---

## 5. 根因 D · 重复实现(总则四 · 组件化与复用)

### D1 · bridge/events 第二真相源(复用角度)

- **严重度**:高 | **状态**:待修复 | **归属**:总则四(= C4 的复用切面)
- **位置**:同 C4(`bridge/events/index.ts` + 100+ 调用点)
- **现象**:同一协议契约前端两套实现(generated 常量 vs 手写 registry)。
- **根因**:未统一到 SSOT 产物。
- **修复方向 / 验收 / 关联**:见 C4。

### D2 · canUseLocalStorage 逐字重复

- **严重度**:中 | **状态**:已验证 | **归属**:总则四
- **位置**:`components/ChatInputBox/hooks/inputHistoryStorage.ts:99` ↔ `hooks/useAttachmentPersistence.ts:14`(两处逐字相同)
- **现象**:同一工具函数两处复制。
- **根因**:未抽取共用。
- **修复方向**:抽到 `utils/` 单点,两处 import。
- **验收**:全仓 `canUseLocalStorage` 唯一定义。
- **关联**:—
- **修复记录**(2026-06-22):核实发现两处实现**并非逐字相同**——`inputHistoryStorage.ts` 为存在性检测(`typeof window && !!localStorage`),`useAttachmentPersistence.ts` 为写入测试(`setItem`/`removeItem`)。统一为更正确的"写入测试"实现(可识别 Safari 隐私模式),新建 `utils/storageAvailability.ts` 作唯一真相源;`inputHistoryStorage` 改 import + re-export(保持 `useInputHistory` 下游不破),`useAttachmentPersistence` 改 import 删本地;新增 `storageAvailability.test.ts` 3 条守门。grep 确认全仓唯一定义,相关 4 文件 19 测试全绿。

### D3 · ViewMode 联合类型三处重复定义

- **严重度**:中 | **状态**:已验证 | **归属**:总则四
- **位置**:`hooks/useModelProviderState.ts:16`、`hooks/useScrollBehavior.ts:7`、`hooks/useSessionManagement.ts:8`(均 `type ViewMode = 'chat'|'history'|'settings'`)
- **现象**:同一类型三处手写。
- **根因**:未抽到公共 types。
- **修复方向**:抽到公共 types 单点定义并 export。
- **验收**:ViewMode 唯一定义。
- **关联**:—
- **修复记录**(2026-06-22):主源原在 `useModelProviderState.ts:16`(export),另两处局部重复。收敛至 `types/index.ts`(公共聚合点,已含 ToolInput 等);`useModelProviderState` 改 `import type + export type`(re-export,保 `hooks/index`、`useMessageSender`、`UIStateContext` 下游不破),`useScrollBehavior`/`useSessionManagement` 合并进既有 `../types` import。`grep type ViewMode =` 仅命中 `types/index.ts`;`tsc --noEmit` 通过;hooks+contexts 284 测试通过(另 3 个失败经 stash 基线确认预先存在,与 ViewMode 无关)。

### D4 · Dialog 无统一基类(13 个散落)

- **严重度**:中 | **状态**:待修复 | **归属**:总则四
- **位置**:`components/` 根下 `ConfirmDialog` / `AlertDialog` / `RewindDialog` / `RewindSelectDialog` / `PermissionDialog` / `AskUserQuestionDialog` / `PlanApprovalDialog` / `ProviderDialog` / `CodexProviderDialog` / `AgentDialog` / `PromptDialog` / `ChangelogDialog` / `ContextUsageDialog`;已有 `components/shared/BaseDialog.tsx` 但多数未基于它
- **现象**:对话框组件散落、未复用已存在的 `BaseDialog` 基类。
- **根因**:组件化不彻底。
- **修复方向**:统一基于 `BaseDialog` 收口对话框族。
- **验收**:新增 Dialog 基于 `BaseDialog`;既有逐步迁移。
- **关联**:—

### D5 · 模型映射读取逻辑分散

- **严重度**:低 | **状态**:待修复 | **归属**:总则四
- **位置**:`utils/claudeModelMapping.ts`(`readClaudeModelMapping`)↔ `components/ChatInputBox/ButtonArea.tsx:62-105`(`applyModelMapping`)
- **现象**:localStorage 模型映射的读取与应用分散两处。
- **根因**:未组件化。
- **修复方向**:收口到单一 hook/util。
- **验收**:模型映射读写单点。
- **关联**:A3(下沉后部分逻辑消失)

### D6 · token / context 格式化未统一

- **严重度**:低 | **状态**:待修复 | **归属**:总则四
- **位置**:`components/settings/ModelRegistrySection/index.tsx:308`(`formatContext` 本地实现)↔ `StatusPanel` 的 token 展示
- **现象**:token / context window 格式化两处各写。
- **根因**:展示变换未抽共用(属可接受的纯展示变换,但应复用)。
- **修复方向**:抽到 `utils/` 展示工具单点。
- **验收**:token 格式化唯一实现。
- **关联**:—

---

## 6. 根因 E · 对接未 Docking 化 / 分层 / 序列化(总则二 / 五 + 附录)

### E1 · CLI session 工厂 if/else 硬编码

- **严重度**:中 | **状态**:已验证 | **归属**:总则五
- **位置**:`cli/CliSessionManager.java:70-74`(`switch(provider){ case CLAUDE->new ClaudeCliSession; case CODEX->new CodexCliSession }`);`CliSessionManager.java:131-134`(`normalizeInterruptProvider` switch)
- **现象**:CLI session 创建按 provider switch,新增 provider 需改本类。
- **根因**:未用 Factory + Registry 范式(可复用 `SessionRuntimeRegistry` 模式)。
- **修复方向**:抽 `CliSessionFactory` 接口 + `supports(provider)` + `Map` 注入路由。
- **验收**:`CliSessionManager.createSession` 无 provider switch。
- **关联**:迁移 P2-C
- **修复记录**(2026-06-22,E1):
  - **工厂化**:`createSession` 的 provider switch 替换为 `CliSessionFactory` 接口 + `Map<provider, CliSessionFactory>` 注册表查表。新建 `cli/CliSessionFactory.java`(接口:`provider()` + `create(tabId)`)+ `cli/claude/ClaudeCliSessionFactory.java` / `cli/codex/CodexCliSessionFactory.java` 两实现(范式对齐 `SessionRuntime`:provider() 路由键 + 注册表 Map)。`CliSessionManager` 加无参构造(默认装配两工厂)+ `List<CliSessionFactory>` 注入构造(重复 provider fail-fast);`createSession` 改 `factories.get(provider)` 查表,未知 provider 抛 `IllegalArgumentException`(取代 switch default)。新增 CLI provider 只需新增 Factory 实现 + 装配一行,路由主体不变。
  - **normalizeInterruptProvider**:删手写 switch,委托 `ProviderType.fromString(provider).toLowerCase()`(fromString: null/未知→CLAUDE, codex→CODEX)。语义与原 switch **完全一致** —— `CliSessionManagerTest` 4 断言(codex→codex / claude→claude / custom-claude-compatible→claude / null→claude)逐项等价。
  - **测试**:`CliSessionManagerTest` 全绿(gradle);session+cli 全包基线对比(stash E1/E2/E3 改动)证明 0 新增失败(10 个预先存在失败:平台 `ApplicationManager` null NPE + 消息解析 AssertionError,与本切片无关)。

### E2 · 消息归一化器 provider×runtime 嵌套 if/else

- **严重度**:中 | **状态**:已验证 | **归属**:总则五
- **位置**:`session/normalize/MessageNormalizers.java:13-22`(二级嵌套,provider×runtime 4 分支全硬编码)
- **现象**:消息归一化按 provider×runtime 硬编码分支。
- **根因**:未用 `MessageNormalizer` 接口 + `supports(provider,runtime)` + Map 路由。
- **修复方向**:抽接口 + supports + 注入 Map。
- **验收**:`MessageNormalizers` 无 provider/runtime 字面量分支。
- **关联**:迁移 P2-C
- **修复记录**(2026-06-22,E2):
  - **工厂注册表**:`MessageNormalizers.forRuntime` 的 provider×runtime 二级嵌套 if/else 替换为 `MessageNormalizerFactory` 接口 + 静态注册表。新建 `session/normalize/MessageNormalizerFactory.java`(package-private 接口:`provider()` + `runtime()` + `supports(p,r)` 默认精确匹配 + `create(delegate)`);`MessageNormalizers` 持 `List<MessageNormalizerFactory> FACTORIES`(4 entry,构造用方法引用 `ClaudeCliMessageNormalizer::new` 等),`forRuntime` 经 `resolve` 查表。
  - **回退语义保持**:原 if/else 有隐式 fallback(未知 provider→claude、未知 runtime→sdk)。`resolve` 用 **effectiveProvider + effectiveRuntime 两维度独立归一**(各自 `knownProvider`/`knownRuntime` 判定后回退 DEFAULT)—— **刻意不用层叠 match fallback**,否则 `codex+未知runtime` 会错误回退到 Claude(现状是 CodexSdk,provider 优先)。10 场景逐一验证与原 if/else 等价。
  - **常量收口**:`"cli"`/`"sdk"`/`"claude"`/`"codex"` 字面量改引用 `CommonConstants.INVOCATION_MODE_CLI/SDK` + `PROVIDER_CLAUDE/CODEX`(SSOT)。
  - **测试**:`MessageNormalizersTest` 4 组合(claude/codex × cli/sdk)精确匹配全绿;基线对比 0 新增失败(见 E1 记录)。

### E3 · SessionProviderRouter provider id 解析硬编码

- **严重度**:中 | **状态**:已验证 | **归属**:总则五
- **位置**:`session/SessionProviderRouter.java:53-58`(`if(PROVIDER_CODEX.equals) return CODEX; return CLAUDE`)
- **现象**:手写 provider id 解析,已有 `ProviderType.fromString` / `ProviderId.of` 未用。
- **根因**:私有方法重复造轮子。
- **修复方向**:直查 `ProviderId.of` / `ProviderType.fromString`,删私有方法。
- **验收**:`SessionProviderRouter` 无手写 provider 解析。
- **关联**:迁移 P2-C
- **修复记录**(2026-06-22,E3):
  - 删私有方法 `providerId(String)`(手写 `if(PROVIDER_CODEX.equals)return CODEX;return CLAUDE`),`adapter()` 改 `providerRegistry.require(ProviderId.of(provider))`。复用已有 `ProviderId.of`(内部 trim/lowercase 归一,与 `ProviderRegistry` 设计一致)。
  - **行为改进(fail-fast)**:原私有方法对未知 provider 静默 fallback 到 CLAUDE(偏离 `ProviderRegistry.require` 的 fail-fast 设计);改用 `ProviderId.of` 后未知 provider 由 `require` 抛 `IllegalArgumentException`,暴露装配错误而非静默用错 adapter。生产调用方(`ClaudeSession`)的 provider 来自 session state(合法 claude/codex),无回归。
  - **测试**:`SessionProviderRouterTest` + `SessionProviderRouterProviderRegistryTest` 全绿(均只传合法 provider,无 fallback 断言,不受 fail-fast 改进影响);移除已无引用的 `CommonConstants` import。基线对比 0 新增失败。

### E4 · ModelRegistryConfig 模型选择 provider 分支

- **严重度**:中 | **状态**:已验证 | **归属**:总则五
- **位置**:`config/ModelRegistryConfig.java:57-83`(`resolveModelSelection` 用 `if(PROVIDER_CODEX.equals)` 分 Codex/Claude 两套返回)
- **现象**:模型选择解析按 provider 硬编码分支。
- **根因**:未抽 per-provider 策略。
- **修复方向**:抽 `ModelSelectionStrategy` 接口 + per-provider impl + supports。
- **验收**:`resolveModelSelection` 无 provider 字面量分支。
- **关联**:迁移 P2-C / 能力抽象
- **修复记录**(2026-06-22,E4):
  - **策略注册表**:`resolveModelSelection` 的 `if(PROVIDER_CODEX.equals)` 两套返回分支替换为 `ModelSelectionStrategy` 嵌套接口(package-private:`provider()` + `resolveRole(model,baseSelected)` + `resolveActualModel(model,selected,baseSelected)`)+ 静态 `Map<String, ModelSelectionStrategy> STRATEGIES`(`Map.of(PROVIDER_CLAUDE, claudeStrategy(), PROVIDER_CODEX, codexStrategy())`,两匿名工厂实现)。主体改 `STRATEGIES.get(normalizedProvider)` 查表,未知 provider 抛 `IllegalStateException`。
  - **等价性(逐分支)**:claude `resolveActualModel` 复刻 `applyRequestCapacity` + blank→null 语义;codex `resolveRole` 返 null、`resolveActualModel` 复刻 `actualModel().isBlank() ? baseSelected : actual`。normalizeProvider 改 `ProviderType.fromString(provider).toLowerCase()`(≡ 原 `PROVIDER_CODEX.equalsIgnoreCase ? CODEX : CLAUDE`)。
  - **测试**:config/settings/session/util/skill 五包基线对比(stash E4-E6 改动前后)证明 0 新增失败(13 个预先存在失败:平台 `ApplicationManager` null NPE + 消息解析/序列化守门/Windows 路径策略 AssertionError;`ModelRegistryServiceSerializeTest` 失败为 supportedReasoningLevels 派生字段与 ModelConfig record components 不匹配,预先存在,详见 E5)。

### E5 · ModelRegistryService reasoningLevelsFor provider 判定

- **严重度**:中 | **状态**:已验证 | **归属**:总则五
- **位置**:`settings/ModelRegistryService.java:114`(`if(!"claude".equalsIgnoreCase(model.provider()))` 判定是否下发 reasoningLevels)
- **现象**:能力派生按 provider 字符串判定。
- **根因**:未抽 `ModelCapabilityProvider` 接口。
- **修复方向**:抽接口,Claude impl 提供 reasoningLevels,Codex impl 返回 null(随 A2 一起)。
- **验收**:`reasoningLevelsFor` 无 provider 字面量。
- **关联**:迁移 P1-A1 / P2-C
- **修复记录**(2026-06-22,E5):
  - **能力注册表**:`reasoningLevelsFor` 的 `!"claude".equalsIgnoreCase(model.provider())` 字面量判定替换为 `ModelCapabilityProvider` 嵌套接口(package-private:`provider()` + `reasoningLevels(model)`)+ 静态 `Map<String, ModelCapabilityProvider> CAPABILITY_PROVIDERS`(仅注册 claude 匿名实现:`ClaudeRole.fromShortName(role)` + `role.reasoningLevels()`)。
  - **等价性陷阱(刻意不用 fromString)**:查表用 `model.provider().toLowerCase(Locale.ROOT)` **精确匹配** PROVIDER_CLAUDE,**非** `ProviderType.fromString` —— 因 `fromString("custom")→CLAUDE` 会把未知 provider 误判为 claude 下发 reasoningLevels,破坏原「非 claude→无 reasoning」语义。codex/未知 provider 查表得 null → 返回 null(serialize 跳过字段),与原 `equalsIgnoreCase` 逐场景等价。
  - **测试**:五包基线对比 0 新增失败(同 E4)。注:`ModelRegistryServiceSerializeTest.serializeEmitsExactlyTheModelConfigRecordFields` 失败为预先存在(serialize 注入 supportedReasoningLevels 派生字段 vs ModelConfig record components 不匹配),与 E5 改实现路径无关(输出语义不变)。

### E6 · provider 字符串判定散落多处(会话生命周期 / 编排 / token / slash)

- **严重度**:中 | **状态**:已验证 | **归属**:总则五
- **位置**:`session/SessionLifecycleManager.java:333,407,417,509`(`"codex".equalsIgnoreCase(provider)` / `"claude".equals(...)`)、`session/SessionMessageOrchestrator.java:78`(跳过 UUID 同步)、`util/TokenUsageUtils.java:36`(token 累加公式)、`skill/SlashCommandRegistry.java:169`(slash 命令 isCodex 判定)
- **现象**:provider 专属逻辑散落多处 if/else,新增 provider 需改多处。
- **根因**:provider 专属逻辑未收口到 `ProviderAdapter` 能力方法。
- **修复方向**:抽能力方法(如 `boolean supportsPostSendUuidSync()`、`UsageTokenExtractor`、per-provider command set)放 `ProviderAdapter`,走枚举判定。
- **验收**:上述文件无 `"codex"`/`"claude"` 字面量分支。
- **关联**:迁移 P2-C
- **修复记录**(2026-06-22,E6):
  - **枚举判定收口**(满足「无原始 codex/claude 字面量分支」验收,对新增 provider 友好):4 文件 provider 字符串判定改 `ProviderType` 枚举判断,逐处等价 ——
    - `SessionLifecycleManager`:L333 `"codex".equalsIgnoreCase(provider)` → `ProviderType.fromString(provider) == ProviderType.CODEX`;L407/417/509 三处 `"claude".equals(session.getProvider())` → `ProviderType.CLAUDE.value().equals(...)`。
    - `SessionMessageOrchestrator`:L78 `"codex".equals(state.getProvider())` → `ProviderType.CODEX.value().equals(...)`。
    - `TokenUsageUtils`:L36 `"codex".equals(provider)` → `ProviderType.CODEX.value().equals(...)`。
    - `SlashCommandRegistry`:L169 `"codex".equalsIgnoreCase(provider)` → `ProviderType.fromString(provider) == ProviderType.CODEX`。
  - **精确判定 vs fromString 区分**(避免 E5 同款陷阱):精确 claude 判断用 `ProviderType.CLAUDE.value().equals(...)`(未知 provider 不误判);仅「codex 与否」的宽松判断用 `fromString(...) == CODEX`(未知→CLAUDE 不影响 codex 分支)。
  - **设计权衡**:未改 `SessionLifecycleManager:462`(`CommonConstants.PROVIDER_CODEX.equals` —— 已是常量非原始字面量);完整能力方法路由(`supportsPostSendUuidSync` / `UsageTokenExtractor` 注入 `ProviderAdapter`)跨 4 文件需大规模注入重构,且静态 utils(`TokenUsageUtils` / `SlashCommandRegistry`)无法注入 adapter,留待 E10 Bridge Adapter。
  - **测试**:五包基线对比 0 新增失败(同 E4)。

### E7 · 装配阶段硬编码 new(路由开闭但装配未开闭)

- **严重度**:低 | **状态**:待修复 | **归属**:总则五
- **位置**:`session/runtime/SessionRuntimeRouter.java:26-34`(手写 4 行 `registry.register(new XxxSessionRuntime(...))`)、`session/SessionProviderRouter.java:22-27`(手写 2 行)、`cli/CliSessionManager`(同类)
- **现象**:路由代码已 Map 化(开闭),但装配构造函数仍手工 new + register,新增实现需改装配。
- **根因**:无 Spring 自动注入,装配未注册化。
- **修复方向**:评估注册化(如 SPI / 静态注册表),或接受手工装配并文档化。
- **验收**:新增 provider runtime 不改 Router 主体(或明确接受并标注)。
- **关联**:迁移 P2-C 评估项

### E8 · DTO / Converter 分层未落地

- **严重度**:中 | **状态**:待修复 | **归属**:总则四 + 附录(四对象分层)
- **位置**:仅 5 个 `*Request` record(`CliSendRequest` / `SessionRequest` / `ModelSelectionRequest` / `InteractiveDiffRequest` / `PermissionRequest`),**无 DTO / PO / Response / Converter**;下发 payload 全手拼 `JsonObject`(`settings/ModelRegistryService.java:72-105`、`provider/common/BaseSDKBridge.java:170-179`、`util/TokenUsageUtils.java:49-`)
- **现象**:出参(下发 payload)无 DTO、无 Converter,逐字段手拼 JsonObject;PO 与传输对象混用 record。
- **根因**:四对象分层未落地。
- **修复方向**:为稳定结构(模型注册表 / provider 列表 / usage)引入 DTO record + Converter(PO↔DTO);流式消息场景 JsonObject 可刻意保留。
- **验收**:稳定结构 payload 经 DTO + Converter 序列化,单点出口。
- **关联**:迁移 P3-B

### E9 · 序列化无统一出口

- **严重度**:中 | **状态**:待修复 | **归属**:总则三 + 附录(序列化约定)
- **位置**:全仓无 `@JsonTypeInfo` / `JsonSerializer` / `TypeAdapter`(`grep` 零命中);所有出口 JSON 手拼 `JsonObject.addProperty`,散布 service / handler / util 各层;业务枚举(`ProviderType` / `RuntimeType` / `ClaudeRole`)无统一 value/desc 序列化
- **现象**:序列化出口分散手拼,枚举/多态字段无统一约定。
- **根因**:未建立统一序列化出口。
- **修复方向**:随 E8 引入 Converter 收敛;业务枚举走 `ProtocolValue` 出口(随 C2);多态字段约定统一(随 C6)。
- **验收**:稳定结构 payload 序列化经 Converter 单点;枚举统一出口。
- **关联**:迁移 P3-B / P2-A

### E10 · 配置外置不充分

- **严重度**:中 | **状态**:待修复 | **归属**:总则五
- **位置**:仅 `config/RuntimePolicyConfig.java`(外置到 `~/.codemoss/config.json`,合规范例);其余写死——`cli/common/CliConstants.java`、`cli/codex/CodexCliCommandUtils.java:17-23,30-34`(`PROTECTED_ENV_KEYS` / sandbox 映射)、`ai-bridge/config/api-config.js:25`(`FALLBACK_CLI_VERSION='2.1.88'`)、`api-config.js:127-161`(`MODEL_ROUTING_ENV_VARS` / `REASONING_CONTROL_ENV_VARS` / `DANGEROUS_ENV_VAR_SET`)
- **现象**:URL / CLI 命令模板 / env 白名单 / fallback 版本等易变参数写死代码,仅路由策略外置。
- **根因**:配置外置未系统化。
- **修复方向**:易变参数抽到 `resources/providers/*.json`,配套 `ProviderConfigLoader`;URL/token 借道第三方 settings.json 的做法保留但文档化。
- **验收**:新增对接零代码改既有(只加配置)。
- **关联**:迁移 Phase 5

### E11 · Java 侧缺 Bridge Adapter 抽象

- **严重度**:中 | **状态**:待修复 | **归属**:总则五
- **位置**:`provider/common/BaseSDKBridge.java`(子类 `ClaudeSDKBridge` / `CodexSDKBridge` 靠 `getProviderName()` 返回 `"claude"`/`"codex"` 字面量路由,`command.add(getProviderName())` at L411)
- **现象**:Java 侧对 ai-bridge 的调用未抽象成 Adapter 接口,provider 路由靠子类硬编码;ai-bridge 内部反而已有 `provider-registry`(`channels/provider-registry.js`),两侧 provider 概念未对齐。
- **根因**:缺 `SdkBridgeAdapter` 接口 + supports。
- **修复方向**:`BaseSDKBridge` 之上加 `SdkBridgeAdapter` 接口 + `supports(provider)`,与 ai-bridge provider registry 概念对齐,消除双写风险。
- **验收**:新增第 3 个 SDK 不改既有 bridge 主体。
- **关联**:迁移 Phase 5 / AGENTS.md 第 0 节进程边界

### E12 · ai-bridge 内部 Claude daemon 特殊化(可接受)

- **严重度**:低 | **状态**:已豁免(现状可接受)| **归属**:总则五
- **位置**:`ai-bridge/daemon.js`(Claude persistent daemon 命令特殊化)、系统命令在 provider registry 之外
- **现象**:ai-bridge 内 Claude daemon 命令特殊化处理,未完全走 provider-registry。
- **根因**:channel 命令处理器内的 provider-local switch 属 provider adapter 内部,非跨 provider 路由(`plugin-architecture-refactor-status.md` 已标注可接受)。
- **修复方向**:保持现状;后续可将 daemon 命令拆为更小的 command descriptor。
- **验收**:—(豁免,记录在案)
- **关联**:`docs/designs/plugin-architecture-refactor-status.md`

---

## 7. 状态汇总表(全条目一览)

| 编号 | 标题 | 严重度 | 状态 | 归属 | 关联迁移 |
|---|---|---|---|---|---|
| A1 | 前端模型注册表双真相源 | 高 | ✓ 已验证 | 一/四 | P0-2 / P1-A2 |
| A2 | 前端能力判定函数 | 高 | ✓ 已验证 | 一 | P1-A1/A2/A3 |
| A3 | 前端模型归一化/映射/协议语义 | 高 | ✓ 已验证 | 一 | P1-A2 |
| A4 | useModelProviderState 前端计算 contextWindow | 高 | ✓ 已验证 | 一 | P1-A2 |
| A5 | 前端业务默认值/校验 | 中 | 待修复 | 一 | Phase4 / P2-B |
| A6 | 版本决策前后端双写 | 中 | 待修复 | 一/四 | Phase4·V6 |
| A7 | 工具分类纯前端硬编码 | 中 | 待修复 | 一 | Phase4·V7 |
| A8 | 会话标题候选判定(边界) | 低 | 待修复 | 一 | — |
| A9 | 可回滚性判定(边界) | 低 | 待修复 | 一 | A7 |
| A10 | PROVIDER_PRESETS 前端持业务表 | 中 | 待修复 | 一/三 | P0-2 |
| B1 | MessageDispatcher 线性链兜底 | 中 | ✓ 已验证 | 二 | P1-C / P3-C |
| B2 | 20 个 legacy MessageHandler SUPPORTED_TYPES | 高 | ✓ 已完成(20/20) | 二 | P1-C |
| B3 | SettingsHandler 60+ 字符串分派 | 高 | 进行中(13/~60) | 二 | P1-C |
| B4 | HistoryHandler 孤儿 | 中 | ✓ 已完成 | 二 | P1-C 排查 |
| B5 | 下行 type 字面量散落 | 中 | ✓ 已验证 | 二 | P1-B |
| C1 | payload 字段结构未生成 | 高 | 待修复 | 三 | Phase1 / Phase2·V3 |
| C2 | 业务枚举 SSOT 全未落地 | 高 | ✓ 已验证 | 三 | P2-A |
| C3 | 默认值漂移(已发生) | 高 | 已验证 | 三 | P2-B / P2-A |
| C4 | 前端协议字面量第二真相源 | 高 | 待修复 | 三/四 | P1-B |
| C5 | 业务默认值前后端手抄 | 中 | 待修复 | 三 | P2-B |
| C6 | ProtocolValue 无 desc | 中 | 已验证 | 三 | P2-A |
| C7 | 双 manifest 写入者 | 低 | 待修复 | 三 | P0-1 |
| C8 | mjs regex 解析脆弱 | 中 | 已验证 | 三 | P0-1 |
| C9 | ProviderType 未纳入生成 | 中 | 已验证 | 三 | P2-A |
| C10 | window.xxx 旧回调名并存 | 中 | 待修复 | 三 | bridge 归一化 |
| D1 | bridge/events 第二真相源(复用) | 高 | 待修复 | 四 | = C4 |
| D2 | canUseLocalStorage 重复 | 中 | 已验证 | 四 | — |
| D3 | ViewMode 三处重复定义 | 中 | 已验证 | 四 | — |
| D4 | Dialog 无统一基类 | 中 | 待修复 | 四 | — |
| D5 | 模型映射读取分散 | 低 | 待修复 | 四 | A3 |
| D6 | token/context 格式化未统一 | 低 | 待修复 | 四 | — |
| E1 | CLI session 工厂 if/else | 中 | 已验证 | 五 | P2-C |
| E2 | 消息归一化器嵌套 if/else | 中 | 已验证 | 五 | P2-C |
| E3 | SessionProviderRouter 解析硬编码 | 中 | 已验证 | 五 | P2-C |
| E4 | ModelRegistryConfig provider 分支 | 中 | 已验证 | 五 | P2-C |
| E5 | reasoningLevelsFor provider 判定 | 中 | 已验证 | 五 | P1-A1 / P2-C |
| E6 | provider 字符串判定散落多处 | 中 | 已验证 | 五 | P2-C |
| E7 | 装配阶段硬编码 new | 低 | 待修复 | 五 | P2-C 评估 |
| E8 | DTO/Converter 分层未落地 | 中 | 待修复 | 四+附录 | P3-B |
| E9 | 序列化无统一出口 | 中 | 待修复 | 三+附录 | P3-B / P2-A |
| E10 | 配置外置不充分 | 中 | 待修复 | 五 | Phase5 |
| E11 | Java 侧缺 Bridge Adapter 抽象 | 中 | 待修复 | 五 | Phase5 |
| E12 | ai-bridge daemon 特殊化 | 低 | 已豁免 | 五 | refactor-status |

---

## 8. 修复优先级建议

依 AGENTS.md 总则优先级(一 > 三 > 二/五 > 四)与风险/杠杆:

1. **第一波(止血 · 默认值与真相源)**:C3(默认值漂移已发生)、C4/D1(第二真相源)、A1(模型双真相源)——防止运行时静默断链与默认值继续漂移。
2. **第二波(SSOT 基础设施)**:C1(payload 守门 + 自动化)、C2(业务枚举枚举化)、C5/C6(默认值/desc)——建立契约可信基石。
3. **第三波(前端业务下沉)**:A2/A3/A4(模型能力与归一化)、A6/A7(版本/工具分类)——最高杠杆,消除前端业务双写。
4. **第四波(后端开闭)**:B3(SettingsHandler)、B2(18 个 handler)、B5(下行字面量)——最大工作量,按 SOP 逐个迁移。
5. **第五波(Docking / 分层 / 收尾)**:E1–E6(if/else 路由化)、E8/E9(DTO+Converter+序列化)、E10/E11(配置外置 + Bridge Adapter)、D2–D6(复用清理)。

> 各波次的代码级步骤、阶段验收、回滚策略见 §9 迁移文档。

---

## 9. 迁移文档索引(整改路线 · 已存在)

- [`docs/superpowers/plans/2026-06-21-agents-architecture-migration.md`](../superpowers/plans/2026-06-21-agents-architecture-migration.md) — 总迁移计划(P0–P3,根因 A 前端业务 / B 旧分派 / C SSOT 全覆盖)
- [`docs/superpowers/plans/2026-06-20-architecture-compliance-migration.md`](../superpowers/plans/2026-06-20-architecture-compliance-migration.md) — SSOT 与前端业务下沉五阶段(V1–V9 违规点对照)
- [`docs/designs/plugin-architecture-refactor-status.md`](../designs/plugin-architecture-refactor-status.md) — 重构状态快照(前端 / 后端 / Node 三边界现状)
- V9 派发器迁移三切片(已完成的范例):
  - [`docs/superpowers/plans/2026-06-21-v9-dispatcher-codex-quota-slice.md`](../superpowers/plans/2026-06-21-v9-dispatcher-codex-quota-slice.md)
  - [`docs/superpowers/plans/2026-06-21-v9-dispatcher-claude-cli-path-slice.md`](../superpowers/plans/2026-06-21-v9-dispatcher-claude-cli-path-slice.md)
  - [`docs/superpowers/plans/2026-06-21-v9-dispatcher-node-path-slice.md`](../superpowers/plans/2026-06-21-v9-dispatcher-node-path-slice.md)
- [`docs/feat/bridge-normalization.md`](../feat/bridge-normalization.md) — 下行总线归一化(与 C10 相关)

---

*本登记簿源自 2026-06-22 的四域架构排查。条目的「修复方向」与「验收」为契约级描述,代码实现见 §9 迁移文档。新增债务请追加新条目并更新 §1 统计与 §7 汇总表。*
