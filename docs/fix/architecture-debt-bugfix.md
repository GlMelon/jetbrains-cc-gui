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

> 截至 2026-06-24 进度:**已验证 38 项**(A1·A2·A3·A4·**A5**·**A6**·**A8**·**A9**·B1·**B3**·B5·**C1**·C2·C3·**C4**·**C5**·C6·**C7**·C8·C9·**C10**·**D1**·D2·D3·**D4**·**D5**·**D6**·E1·E2·E3·E4·E5·E6·**E7**·**E8**·**E9**·**E10**·**E11**)、**已完成 2 项**(B2 — 20/20 legacy handler 全迁移;B4 — HistoryHandler 迁移)、**进行中 0 项**、**已豁免 3 项**(E12;A10 — PROVIDER_PRESETS 维持现状:后端零消耗/preset id≠currentProvider/nameKey 属 i18n 前端域;A7 — 工具分类经评估为前端展示分类,后端无 SSOT 源/SDK 透传,降级保留并文档化);**43/43 全部闭环**(0 待修复,B3 已于 2026-06-24 收尾:6 子域全量 action 迁移(累计 62 handler)+ SettingsHandler 类彻底删除)。逐项状态见 §7 表格,整体阶段路线见 §9 迁移文档索引。

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

- **严重度**:中 | **状态**:✓已验证 | **归属**:总则一
- **位置**:见下「验证」(原 `permissionDialogTimeout.ts:300·30·3600`、`types/provider.ts:CODEX_PROTECTED_ENV_KEYS` 手抄表、`ChatInputBox/types.ts` 默认值已全部改读 generated)
- **现象**:业务配置默认值、业务校验、业务规则表(保护 env 白名单)在前端硬编码。
- **根因**:配置默认值与业务校验应后端下发。
- **修复方向**:默认值/边界后端下发;保护 env 表下沉后端或经 SSOT 生成。
- **验收**:前端无业务默认值常量、无业务校验函数(纯格式校验除外)。
- **关联**:迁移 Phase 4 / P2-B

**验证(2026-06-24)**:
- **保护 env 白名单(原三处手抄 → 枚举 SSOT)**:后端新增 `protocol/CodexProtectedEnvKey` 枚举(18 值,`implements ProtocolValue`,单参 `NAME("NAME")` + `value()`/`fromValue()` 往返)为唯一真相源;生成链 `parseEnumSource` → `generated/protocol.ts#CODEX_PROTECTED_ENV_KEY`;`types/provider.ts` 由 18 项手写 `Set` 改 `new Set(Object.values(CODEX_PROTECTED_ENV_KEY))` 派生;后端 `CodexCliCommandUtils`(`Arrays.stream(values()).map(CodexProtectedEnvKey::value).collect(toUnmodifiableSet())`)与 `CodexSDKBridge`(静态块遍历 `values()` add `key.value()`)均消费同一枚举(`CodexSDKBridge` 额外保留 17 个 SDK 安全变量如 `NODE_OPTIONS`,与枚举合并)。
- **默认值/校验**:超时默认值 300/30/3600 与 context window 200_000/1_000_000 经 C5 的 `parseIntConstants` 生成链产出;`isValidPermissionMode` 经 C2 的 `PERMISSION_MODE` SSOT 收敛。
- **守门**:后端 `ProtocolEnumCoverageTest` + 前端 `protectedEnvKeys.test.ts`(18 键计数 / 大小写不敏感 / 非保护为 false)+ `generate-protocol-types.test.ts`;前端 tsc 0、vitest 834/834 全绿。

### A6 · 版本决策前后端双写

- **严重度**:中 | **状态**:**✓ 已验证(2026-06-24)** | **归属**:总则一 / 四
- **位置**:`webview/src/components/settings/DependencySection/versioning.ts:15-71`(`getVersionAction` 决策)↔ 后端 `dependency/DependencyManager.java:281`
- **现象**:install / update / rollback 的版本决策前后端各算一遍。
- **根因**:决策属业务逻辑,前端只应渲染后端下发的动作结果。
- **修复方向**:后端 `DependencyManager` 下发动作结果,前端只渲染按钮态。
- **验收**:前端无 `getVersionAction` 决策函数。
- **关联**:迁移 Phase 4 · V6

**验证(2026-06-24)**:
- 后端新增 `dependency/VersionAction` 枚举(install/update/rollback/current 4 值,`implements ProtocolValue`,单参 `NAME("value")` + `value()`/`fromValue()` 往返)为版本决策 SSOT;生成链 `parseEnumSource` → `generated/protocol.ts#VERSION_ACTION`,`ProtocolManifestGenerator` 反射交叉校验同步加 versionAction 段。
- `DependencyManager` 新增 `public static VersionAction resolveVersionAction(installed, installedVersion, requestedVersion)`(纯逻辑,与既有 static `normalizeRequestedVersion`/`buildPackageSpecs` 范式对齐,规避 NodeDetector/Platform 实例化陷阱;`compareVersions`/`parseVersionPart` 一并提升 static);`DependencyActionHandlers.buildVersionPayload` 在已安装时预计算 `versionActions: { 目标版本 → 动作 }` map 随 `dependency.versions_loaded` 下发(全集对齐前端 `buildVersionOptions` 并集)。
- 前端 `versioning.ts` 删 `getVersionAction`/`compareVersions`/`VersionAction` 字面量,改 `resolveVersionAction` 查表(未安装→install;已安装但 map 缺失/目标版本不在表→保守 current 降级保护);`VersionAction` 类型改从 generated re-export;`index.tsx` 的 `getActionLabel`/渲染 action 改查 `versionInfo.versionActions[targetVersion]`;`DependencyVersionInfo` 加 `versionActions?` 字段。
- **守门**:后端 `ProtocolEnumCoverageTest`(VersionAction 4 值覆盖 + 唯一性 + fromValue 往返)+ `DependencyManagerVersioningTest`(resolveVersionAction 四态 + blank/missing 降级 + v 前缀归一化);前端 `versioning.test.ts`/`index.test.tsx` 迁移查表用例(mock 补 versionActions);webview src tsc 0、DependencySection vitest 9/9 全绿。

### A7 · 工具分类纯前端硬编码

- **严重度**:中 | **状态**:**已豁免(2026-06-24,展示分类降级)** | **归属**:总则一
- **位置**:`webview/src/utils/toolConstants.ts:7-44`(`READ` / `EDIT` / `BASH` / `SEARCH` / `AGENT` / `TASK_MANAGE` / `TRANSIENT_INTERNAL` / `FILE_MODIFY_TOOL_NAMES` 等集合)
- **现象**:工具名分类属业务语义,前端 hardcode。
- **根因**:工具分类应后端工具元数据经 SSOT 下发。
- **修复方向**:工具元数据纳入契约层后端下发;若仅显示所需,明确标注并经 SSOT。
- **验收**:前端无 hardcode 工具分类业务表。
- **关联**:迁移 Phase 4 · V7

**验证(2026-06-24,降级)**:评估后判定为「前端展示分类」——用于工具卡片图标/分组/着色 + 回滚可用性(A9)等纯展示语义。后端无对应 SSOT 源:工具名来自各 SDK(Claude/Codex/Agent SDK)透传,后端不做统一工具元数据建模,散落处 case/list 仅字符串匹配。故按总则一降级:集中化(`toolConstants.ts` 单一源,杜绝散落硬编码)+ 文档化(头注释标注展示分类边界)。**不建后端 ToolRegistry**:后端纯 SDK 透传无业务分类语义来源,强推将成无源之水。`toolConstants.ts` 头注释已加降级决策说明;后续若引入工具元数据契约再评估下沉。

### A8 · 会话标题候选判定(边界)

- **严重度**:低 | **状态**:**✓ 已验证(2026-06-24,展示 fallback 标注)** | **归属**:总则一
- **位置**:`webview/src/hooks/useChatComputations.ts:36-48`(`isSessionTitleUserCandidate`)
- **现象**:前端判定一条消息是否适合作会话标题(过滤 `[tool_result]` / 非 human / 纯 tool_result 块)。
- **根因**:会话标题是业务语义属性,宜后端给标题。
- **修复方向**:评估:若后端已有标题生成,前端改消费;否则保留并标注为展示过滤。
- **验收**:明确判定为业务或展示,前者下沉。
- **关联**:—

**验证(2026-06-24)**:判定为「展示 fallback」——`isSessionTitleUserCandidate` 仅在无 `customSessionTitle`(用户自定义/持久化标题,优先级更高)时从用户消息筛选兜底文本(见 `sessionTitle` useMemo 优先级链)。过滤 `[tool_result]`/非 human/纯 tool_result 块属 UI 展示过滤非业务语义。保留并标注:`isSessionTitleUserCandidate` 与 `sessionTitle` 已加注释说明展示 fallback 边界与标题 SSOT(`customSessionTitle`)优先级。

### A9 · 可回滚性判定(边界)

- **严重度**:低 | **状态**:**✓ 已验证(2026-06-24,随 A7 降级)** | **归属**:总则一
- **位置**:`webview/src/hooks/useChatComputations.ts:165-190`(`canRewindFromMessageIndex`,用 `FILE_MODIFY_TOOL_NAMES` 判定)
- **现象**:前端用工具名集合判定某消息「可回滚」。真回滚动作在后端(`rewind_files`),此为 UI 可用性判定。
- **根因**:「可回滚性」是业务语义属性;但 UI 可用性判定可接受前端做(依赖 A7 工具分类)。
- **修复方向**:随 A7 一起,工具分类后端下发后此判定消费下发字段。
- **验收**:工具名集合不再前端 hardcode(依赖 A7)。
- **关联**:A7

**验证(2026-06-24,随 A7)**:`canRewindFromMessageIndex` 判定为「UI 可用性判定」——决定 Rewind 按钮启用态(其后是否存在文件修改类工具调用),真实回滚在后端 `rewind_files`。依赖 A7 的 `FILE_MODIFY_TOOL_NAMES` 展示分类。随 A7 降级:工具分类已定为展示分类,此 UI 启用态判定消费前端分类即可,可接受前端做,无需后端下发。`canRewindFromMessageIndex` 已加注释说明 UI 可用性边界与 A7 依赖。

### A10 · PROVIDER_PRESETS 前端持业务默认值表

- **严重度**:中 | **状态**:已豁免(现状可接受) | **归属**:总则一 / 三
- **位置**:`webview/src/types/provider.ts:350-456`(`PROVIDER_PRESETS`,含 base_url、默认模型名、API_TIMEOUT_MS);`provider.ts:57-67`(`CLAUDE_MODEL_MAPPING_ENV_KEYS`)、`provider.ts:126-131`(`ProviderCategory`)、`provider.ts:11-20,27-30`(`SPECIAL_PROVIDER_IDS` / `PROVIDER_IDS`)
- **现象**:文件自带注释(318-333 行)辩称是「UI 表单填充」,但内含 base_url、默认模型名等业务知识,本质是前端持有的业务默认值表。
- **根因**:第三方 provider 预置模板应后端下发或经 SSOT。
- **修复方向**:(已降级)见下方降级决策。
- **降级决策**(2026-06-24,SSOT 默认值收尾):**维持现状,不予修复**。理由:
  1. **后端零消耗**:`PROVIDER_PRESETS` 是第三方 provider(OpenRouter/Gemini/DeepSeek 等)的 UI 表单预填模板,非运行时业务真相源。运行时实际 provider 配置由 `config.json` providers 列表决定,preset 仅作"新建 provider"时的初始填充。
  2. **preset id ≠ currentProvider**:preset 选中后写入 config.json 成独立 provider 实例,preset 表本身不参与运行时派发/匹配,改值不影响已存配置。
  3. **nameKey 是 i18n 前端关注点**:preset 的 `nameKey`(如 `provider.preset.openrouter`)是 UI 显示文案键,本属前端 i18n 域,后端下发反而引入 i18n 耦合。
  4. **类比豁免**:同 C2 CodexFastMode 降级、E12 豁免——非运行时真相源的前端表单模板,SSOT 收益不抵迁移成本。
- **验收**:豁免(接受前端持有 provider 表单预填模板,非运行时业务真相源)。
- **关联**:迁移 P0-2(降级关闭)

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

- **严重度**:高 | **状态**:**✓ 已验证(6 子域全迁移 + 类彻底删除,2026-06-24)** | **归属**:总则二
- **位置**:~~`handler/SettingsHandler.java`(`SUPPORTED_TYPES` 49 字符串 + `switch(type)` 49 case)~~ — **类已删除**;49 个 action 全部迁为 `handler/settings/*ActionHandler.java implements FrontendActionHandler<T>`,在 `ChatWindowDelegate` typed 注册表直注册。
- **现象**:最大的一处字符串分派;新增设置 action 易往数组加条目(AGENTS.md 明令禁止的旧路径)。
- **根因**:settings 域仅 Model Registry / Appearance / V9 三切片先迁,余 49 action 仍走旧路径。
- **修复方向**:按 SOP 拆解 49 条 action 为独立 `handler/settings/*ActionHandler.java implements FrontendActionHandler<T>`,经子 handler(`ProjectConfigHandler`/`UserLanguageHandler`/`RuntimePolicyHandler` 等)委托;迁移后 SettingsHandler 类彻底删除。
- **B3 SOP(2026-06-23 验证)**:机制=typed handler 在 `ChatWindowDelegate` 用 `add(...)` 直注册(旧桥接 `LegacyMessageHandlerAdapter.from(settingsHandler)` 已随类删除一并移除);`FrontendActionDispatcher` 用 `putIfAbsent`(重复抛 IllegalArgumentException)。**每批同构建**:① 建 typed handler+测试 ② 注册 typed handler ③ 从 `SUPPORTED_TYPES`/`switch` 移除 ④ 构建(防启动崩溃)。
- **已迁子域**(全量完成,累计 62 handler / 本轮 2026-06-24 +49):
  - permission-mode(3):`GetMode/SetMode/SetSessionModeActionHandler` → `PermissionModeHandler` ✓ 2026-06-23
  - input-history(4):`GetInputHistory/RecordInputHistory/DeleteInputHistoryItem/ClearInputHistoryActionHandler` → `InputHistoryHandler` ✓ 2026-06-23
  - model-provider(6):`SetModel/SetSessionModel/SetProvider/SetSessionProvider/SetReasoningEffort/SetCodexFastModeActionHandler` → `ModelProviderHandler`+`UsagePushService` ✓ 2026-06-23
  - project-config(42):`handler/settings/*ActionHandler`(usage/font/working-dir/sandbox/shortcut/commit/prompt/runtime-state 等)→ `ProjectConfigHandler` 委托 ✓ 2026-06-24
  - user-language(3):`Set/Get/ClearUserLanguageActionHandler` → 新建 `handler/UserLanguageHandler`(自原类迁出,逻辑逐字等价)✓ 2026-06-24
  - runtime-policy(4):`Get/Set/Reset/RuntimePolicySchemaActionHandler` → 新建 `handler/RuntimePolicyHandler` ✓ 2026-06-24
- **收尾(2026-06-24)**:① theme change listener 迁至 `ChatWindowDelegate`(`handlerContext` 就绪后内联注册,逻辑逐字等价);② `getModelContextLimit` 两处调用方(`util/MessageJsonConverter`、`session/SessionLifecycleManager`)改 `ModelProviderHandler.getModelContextLimit`(同一实现,零行为变化);③ 删除 `handler/SettingsHandler.java` + `ChatWindowDelegate` 桥接行 + 残留 import。
- **验收**(达成):`SettingsHandler` 类已删除(`grep SettingsHandler src/main` 仅余历史对照注释);`LegacyMessageHandlerAdapter.from` 在 src/main **零业务使用方**(仅测试);`gradle compileJava`/`compileTestJava` BUILD SUCCESSFUL;`SettingsHandlerTypedWiringTest`(49 action 枚举可解析守门,经 `-x instrumentTestCode` 跑)+ handler 包全量测试通过。
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

- **严重度**:高 | **状态**:已验证(2026-06-24) | **归属**:总则三
- **位置**:manifest schema 仅 `{name, value}`(`protocol/ProtocolManifestGenerator.java:44-62`);`webview/src/generated/protocol.ts` 全文无 payload interface;前端 `modelRegistry.ts:16-18` 手写 `ModelRegistryPayload` ↔ 后端 `ModelRegistryService.java` / `ModelConfig.java` 各自定义
- **现象**:payload 字段结构前后端各写一套,字段增删/重命名无编译期校验,运行时静默断链。
- **根因**:manifest 仅生成消息名,未生成字段结构。
- **修复方向**:扩展 `ProtocolManifestGenerator` 反射 record components 写入 `payloadSchemas`,mjs 从 manifest 生成 TS 字段类型;先建两端字段守门测试(后端反射 ↔ 前端覆盖)。
- **验收**:manifest 含 `payloadSchemas`;前端 payload 类型从 generated 导入;两端字段守门测试存在。
- **关联**:迁移 Phase 1(Task 1.2/1.3 守门)+ Phase 2(V3 自动化)
- **修复记录**(2026-06-24,ModelRegistry payload 试点):
  - **Java SSOT**:新建 `protocol/payload/ModelRegistryPayloadField.java` enum,11 个 wire 字段显式三参声明 `NAME("wireKey","tsType",optional)`(id/provider/role?/label/actualModel?/description?/contextWindow/supports1MContext/supportedReasoningLevels?/enabled/readOnly)。提供 `wireKey()/tsType()/optional()` + `static wireKeys()`(LinkedHashSet 保序)。**为何显式声明而非反射 ModelConfig 记录组件**:serialize 还派生 `supportedReasoningLevels`(由 role 计算、不存 ModelConfig),反射记录组件会漏此派生字段产生间隙;显式声明自然含派生字段 + 守"声明==实际产出"契约。
  - **生成链**:`generate-protocol-types.mjs` 加 `parsePayloadFieldSource`(严格三参 regex `NAME("wireKey","tsType",optional)`,与 `parseEnumSource` 单参正交不互染——单参枚举不会被误匹配)+ `generatePayloadInterfaces`(每 payloadSchema → `<PascalCase(key)>PayloadWire` interface,字段名=wireKey、optional 带 ?)+ `parsePayloadSchema`(空解析抛错防静默漏项);manifest 增 `payloadSchemas.modelRegistry.fields[]`;`generateFromManifest` 末尾拼接 payload interface 段。`generate-protocol-types.d.mts` 扩展声明(`parsePayloadFieldSource`/`generateFromManifest` + `PayloadField`/`PayloadSchema`/`ProtocolManifest` 类型)。
  - **关键决策·.d.mts 非 .d.ts**:TS 对 `.mjs`(ESM)的类型声明查找 `.d.mts` 而非 `.d.ts`(试建 `.d.ts` 被 TS 忽略、`--listFiles` 确认只取 `.d.mts`);且 `allowJs` 在 bundler 模式下对 src 外 `.mjs` 的具名导出推导不可靠(仍 TS2305)。故复用现有 `.d.mts` 扩展声明作"导出契约",运行时仍由 esbuild/vitest 加载 mjs 实际实现(.d.mts 不参与运行,与 C1 wire SSOT 正交——后者由后端 enum + 守门保证)。
  - **前端集成**:`utils/modelRegistry.ts` `ModelRegistryItem` 改 `extends ModelRegistryPayloadWire`(协变收窄:wire.provider:string → ProviderType、role?:string → 角色联合、supportedReasoningLevels?:readonly string[] → readonly ReasoningEffort[]),删手写 id/label/contextWindow/actualModel/supports1MContext/enabled/readOnly(统一来自 wire);`toCodexRegistryItem` 补 `readOnly: false`(wire 必填暴露的构造缺口)。生产 `ModelRegistrySection` EMPTY_MODEL + 5 测试文件共 21 处 mock 补 `supports1MContext`/`readOnly`(wire 必填契约暴露的旧 mock 疏忽,值不影响各测试断言)。
  - **测试**:后端 `ModelRegistryPayloadFieldTest` 2 测试(完整 claude sample serialize 产出的 JSON key 集 == `wireKeys()` 声明集 + 字段计数守门,gradle BUILD SUCCESSFUL,三端守门之「后端 serialize ↔ 声明」);前端 `src/__tests__/generate-protocol-types.test.ts` 共 10 测试(parseEnumSource ×4 C8 漂移守门 + parsePayloadFieldSource ×4 含「不误匹配单参枚举」+ generateFromManifest ×2,三端守门之「生成链路」)。**位置必须在 `src/__tests__`(入库)而非 `src/generated/`(被 `.gitignore` 第 68 行整体忽略——后者本地能跑但 CI clone 后不存在,守门永不触发;连原有 4 个 parseEnumSource C8 测试此前也因此从未入库,本次一并修正移入 `src/__tests__`)**;webview tsc C1 相关清零(三端守门之「前端 extends」);vitest 824/0 全绿。
  - **scope**:仅 ModelRegistry 试点验证 payload 生成机制;其他 payload(未来扩展)按 `payloadSchemas.<key>` 同范式增声明 + 解析即可。

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

- **严重度**:高 | **状态**:已验证 | **归属**:总则三 / 四
- **位置**:`webview/src/bridge/events/index.ts:23-165`(`BRIDGE_EVENTS` 手写 ~130 条 type 字面量,与 `protocol.ts` 的 `DOWNSTREAM` 并存);`generate-protocol-types.mjs` 产物仅 6 文件采用;100+ 处 `sendBridgeEvent('xxx')` / `sendToJava('xxx')` 裸字符串调用点(`useMessageSender.ts`、`useSessionManagement.ts`、`useModelProviderState.ts`、`DependencySection`、`useProviderManagement.ts` 等)
- **现象**:`bridge/events/index.ts` 是与 `generated/protocol.ts` 并存的手写第二真相源;绝大多数调用点未使用 generated 常量。
- **根因**:协议名 SSOT 生成链已具备,但消费侧迁移未完成。
- **修复方向**:`BRIDGE_EVENTS.type` 改引用 `DOWNSTREAM.XXX`(保留 `kind`);227 处调用点机械替换为 `sendAction(UPSTREAM.*)` / `subscribeEvent(DOWNSTREAM.*)`。
- **验收**:webview 无手写协议 type 字面量(除 generated);`bridge/events/index.ts` 引用 `DOWNSTREAM` 常量。
- **关联**:迁移 P1-B
- **修复记录**(2026-06-24):彻底统一到 typed helper——上行全迁 `sendAction(UPSTREAM.*)`、下行全迁 `subscribeEvent(DOWNSTREAM.*)` / `subscribePassthroughEvent(DOWNSTREAM.*)`,RPC 保留 `bridgeHub.request(DOWNSTREAM.*)`;删除无约束的 `sendBridgeEvent`/`sendToJava`/`callBridge`(`utils/bridge.ts` 死代码,删除即防回归守卫——残留调用 `tsc` 立即 TS2304)。删除 `bridge/events/index.ts` 的 `BRIDGE_EVENTS`/`BRIDGE_EVENT_TYPES`(零外部消费,`DOWNSTREAM` 是严格超集)。mcp/codex 双轨模板串 `` `${verb}_${prefix}mcp_server` `` 改三元常量对(`isCodexMode ? UPSTREAM.VERB_CODEX_MCP_SERVER : UPSTREAM.VERB_MCP_SERVER`)。`bridge.test.ts` 的 `search_project` 占位用例迁 `sendAction`。验收:`tsc --noEmit` 0 错;vitest 813 passed/10 failed(与基线相同失败集,零回归);`grep sendBridgeEvent|sendToJava`(源码,排除 `window.sendToJava` 与注释)零命中,helper 已删。wire 协议值不变,纯前端机械迁移,未改 Java 枚举或重新生成 `protocol.ts`。

### C5 · 业务默认值前后端手抄

- **严重度**:中 | **状态**:✓已验证 | **归属**:总则三
- **位置**:见下「验证」(原 `ChatInputBox/types.ts:DEFAULT_CONTEXT_WINDOW=200_000 / ONE_MILLION_CONTEXT_WINDOW=1_000_000`、`permissionDialogTimeout.ts:300·30·3600` 手抄已全部改读 generated)
- **现象**:业务默认值前后端手抄,后端改值前端不会自动跟。
- **根因**:默认值未经 SSOT 生成或下发。
- **修复方向**:后端统一 `DEFAULT_CONTEXT_WINDOW` 常量(替换 ~10 处裸字面量);前端默认值改从 generated 导出或后端下发。
- **验收**:前后端无手抄默认值;后端改值前端自动跟。
- **关联**:迁移 P2-B

**验证(2026-06-24)**:
- **生成链扩展**:新增 `parseIntConstants(source, allowlist, label)`(纯函数,读 Java `public static final int NAME = literal;`,`literal` 支持下划线 `200_000`,parseInt 前去下划线;allowlist 白名单防泄露后端其他 int)。从 `common/CommonConstants`(`DEFAULT_CONTEXT_WINDOW=200_000`、`ONE_MILLION_CONTEXT_WINDOW=1_000_000`)与 `settings/PermissionDialogTimeoutSettings`(`DEFAULT=300`、`MIN=30`、`MAX=3600`)解析 5 个常量 → `generated/protocol.ts` `export const NAME = N as const;`。
- **前端收敛**:`ChatInputBox/types.ts` re-export `DEFAULT_CONTEXT_WINDOW/ONE_MILLION_CONTEXT_WINDOW`;`utils/permissionDialogTimeout.ts` import + re-export 三个超时常量(本地 `clampPermissionDialogTimeoutSeconds` 直接复用导入绑定);`App.tsx` `useState<number>(DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS)` 显式标注,规避 `as const` 字面量收窄陷阱。
- **后端零改动**:SSOT 源(`CommonConstants` / `PermissionDialogTimeoutSettings`)原值不动,mjs 直接读源(同 C1 范式),`ProtocolManifestGenerator` 反射路径无需扩展(已是「人工校验入口」)。
- **守门**:`generate-protocol-types.test.ts`(`parseIntConstants` 4 用例 + `intConstants` 生成 1 用例);前端 tsc 0、vitest 834/834 全绿;`PermissionDialogTimeoutSettings` 改值后前端重新构建即自动跟。

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

- **严重度**:低 | **状态**:✓ 已验证(2026-06-24) | **归属**:总则三
- **位置**:`build.gradle:387-407`(`generateProtocol` task 写 manifest)↔ `generate-protocol-types.mjs:113`(mjs 也写 manifest)
- **现象**:两个写入者写同一路径 `protocol-manifest.json`,Gradle task 实际无消费者(mjs 直读 Java 源为主路径),易误导维护者。
- **根因**:SSOT 链路切换主路径后未清理旧 task。
- **修复方向**:评估 deprecate `ProtocolManifestGenerator` / `generateProtocol` task;或保留并明确标注为可选兼容产物。
- **验收**:**✓ 达成**。旧 task 明确标注为可选兼容产物(默认禁用),manifest 写入者语义唯一(mjs 为生产写入者,Gradle task 仅手动交叉校验)。
- **关联**:迁移 P0-1
- **修复记录**(2026-06-24,保守清理——不删除,标注可选):采用保守方案(保留 C8 反射交叉验证入口),三文件收口:
  - **`ProtocolManifestGenerator.java`**:加 `@Deprecated` + Javadoc 明示"已无运行时/构建时消费者,SSOT 主路径为 mjs 直读 Java 枚举源;本类保留仅作 mjs regex 解析(C8 漂移守门)的反射交叉校验入口,经 `generateProtocol` task(`-PgenerateProtocol=true`)手动驱动;不打包进插件 JAR"。
  - **`build.gradle` `generateProtocol` task**:加 `enabled = project.findProperty('generateProtocol')?.toString() == 'true'`(默认禁用——标准构建链不触发,Gradle 标记 SKIPPED)+ description 标注"可选·默认禁用"。消除"两个写入者写同一路径"的误导:标准构建仅 mjs 写 manifest。
  - **`generate-protocol-types.mjs` 头部注释**:修正过时描述(原称"读取 protocol-manifest.json 由 Gradle generateProtocol task 生成")→ 准确描述"直读 Java 源为主路径,manifest 为构建副产品(非 Gradle task 依赖输入)"。
  - **验证**:`ProtocolManifestGenerator` IDE 检查零编译错误;行为零变化(默认禁用 task 不触发,mjs 仍直读 Java 源并写 manifest 副产品)。

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

- **严重度**:中 | **状态**:**✓ 已验证(接受并标注迁移边界,2026-06-24)** | **归属**:总则三
- **位置**:`messageCallbacks.ts:376`(`window.updateMessages` 直接赋值);`permissionCallbacks.ts:26`、`streamingCallbacks.ts:240/420`(均**已** `registerLegacyAlias` 收口);`bootstrap/pendingSlots.ts:52/205`(挂载前占位槽)
- **现象**:下行总线已归一化为 `window.__bridge.dispatch`,残留 `window.xxx` 旧回调名经 `registerLegacyAlias` 桥接到 `bridgeHub`(双轨渐进,见 `ARCHITECTURE.md` §0)。
- **根因**:bridge 归一化重构(Phase 0–6)的尾部遗留。
- **核实记录**(2026-06-24):登记簿原位置行号(`:384`/`:42`/`:240-241`)已过时,逐处核实如下——
  - **后端入口型回调已收口**(主体达成):`showPermissionDialog`、`onStreamStart`、`onStreamEnd`、`onContentDelta`、`onThinkingDelta`、`onStreamingHeartbeat` 等 40+ 回调均经 `registerLegacyAlias(legacyName, DOWNSTREAM.*)` 桥接到 `bridgeHub.subscribe` 注册的 handler;后端 `executeJavaScript(window.xxx(...))` 调用路径不变,对外兼容旧名。原述「showPermissionDialog / onStreamEnd 未收口」**核实为过时**。
  - **复杂状态回调刻意保留** `window.xxx=` 直接赋值:`updateMessages`(rAF coalesce 防主线程假卡死 + sequence 丢弃过期快照防跳变 + `__sessionTransitioning` guard 防会话切换竞态 + `__lastStreamActivityAt` watchdog bump 证明桥存活)、`updateStatus`(`suppressNextStatusToastRef`)、`clearMessages`(取消 pending rAF)均带 React state setter / 跨帧状态,无法经 `dispatch` 同步转发而不破坏上述关键修复(changelog #508/#518/#542)。
  - **前端内部编排**(非后端入口):`useMessageSender.ts:453`、`streamingCallbacks.ts:232` 的 `window.onStreamEnd()` 是前端调前端的清理编排,非 `executeJavaScript` 入口,不在收口范围。
- **验收**:`window.__bridge.dispatch` 为后端 `executeJavaScript` 唯一入口(已达成);残留 `window.xxx=` 直接赋值仅限**带复杂状态管理**的回调,边界已在 `pendingSlots.ts:10-14`(已迁移槽 vs 未迁移槽)与 `ARCHITECTURE.md:246-247` 文档化。强行收口 `updateMessages` 会破坏 rAF/sequence/guard/watchdog 语义,属破坏性 churn,故**接受并标注**(同 E7/A7 决策)。
- **关联**:bridge 归一化后续(见 `docs/feat/bridge-normalization.md`);后续若推进 Phase 7 回调全迁移,简单 state-setter 回调可逐个评估迁 `registerLegacyAlias`,复杂状态回调保留。

---

## 5. 根因 D · 重复实现(总则四 · 组件化与复用)

### D1 · bridge/events 第二真相源(复用角度)

- **严重度**:高 | **状态**:已验证 | **归属**:总则四(= C4 的复用切面)
- **位置**:同 C4(`bridge/events/index.ts` + 100+ 调用点)
- **现象**:同一协议契约前端两套实现(generated 常量 vs 手写 registry)。
- **根因**:未统一到 SSOT 产物。
- **修复方向 / 验收 / 关联**:见 C4。
- **修复记录**(2026-06-24):随 C4 一并落地(见 C4 修复记录)——删除 `bridge/events` 第二真相源,下行统一 `subscribeEvent`/`subscribePassthroughEvent` 传 `DOWNSTREAM` 常量,前端协议契约收口到 `generated/protocol.ts` 单一 SSOT。

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

- **严重度**:中 | **状态**:**✓ 已验证(接受并标注边界,2026-06-24)** | **归属**:总则四
- **位置**:`components/shared/BaseDialog.tsx`(统一基类已建立);已基于 BaseDialog 的 **12 个**:`ConfirmDialog`/`AlertDialog`/`RewindDialog`/`RewindSelectDialog`/`PermissionDialog`/`ProviderDialog`/`CodexProviderDialog`/`AgentDialog`/`PromptDialog`/`ChangelogDialog` + 本轮 `McpConfirmDialog`;剩余 ~17 个散落(见核实记录)
- **现象**:对话框组件散落、未复用已存在的 `BaseDialog` 基类。
- **根因**:组件化不彻底。
- **核实记录**(2026-06-24):登记簿原列 13 个中 **10 个已迁** BaseDialog(原述「多数未基于它」过时);本轮再迁 **`McpConfirmDialog`**(简单全局 className Confirm,与 `ConfirmDialog` 同构;原 `.mcp-confirm-dialog .dialog` 是不匹配的死规则,视觉零变化;纯增强 ESC 关闭 + 无障碍)。`tsc --noEmit` 0 + `vitest` **847/847 全绿**。剩余 ~17 个未迁 Dialog 经核实分三类,**架构差异使机械迁移成为破坏性 churn**:
  - **CSS Modules + Portal + 复杂表格交互**(不适用 BaseDialog):`AgentImportConfirmDialog`/`PromptImportConfirmDialog`/`ImportConfirmDialog`(ProviderList)用 `style.module.less` 样式隔离 + `createPortal(document.body)` + 多选/冲突策略表格;BaseDialog 是全局 className + 非 portal,迁移破坏样式隔离与 portal 渲染语义。
  - **域专属 CSS 体系**(迁移需配套改 less):`SkillConfirmDialog`(`skill-dialog-*`)、`DiscardAllDialog`/`UndoConfirmDialog`(`undo-confirm-*` + memo + 非 isOpen `visible`/`null` 守卫)用各域专属全局 className。
  - **复杂交互/特殊面板**:`ContextUsageDialog`(大型用量面板)、`AskUserQuestionDialog`(动态选项)、`PlanApprovalDialog`、`PromptEnhancerDialog`、`McpServerDialog`/`McpLogDialog`/`McpPresetDialog`、Help/Export 型。
- **验收**:统一基类 `BaseDialog` 已建立且 **12 个 Dialog 已基于它**(覆盖主要简单对话框类型,「新增 Dialog 基于 BaseDialog」基础设施就位);既有逐步迁移持续推进(本轮 +1)。剩余因架构差异(CSS Modules/Portal/域 CSS/复杂交互)逐个评估,强行批量迁移破坏样式隔离/交互(同 [[c10-bridge-tail-accept-and-annotate]]/E7 决策),故**接受并标注边界**。
- **关联**:—

### D5 · 模型映射读取逻辑分散

- **严重度**:低 | **状态**:✓ 已验证(2026-06-24) | **归属**:总则四
- **位置**:`utils/claudeModelMapping.ts`(`readClaudeModelMapping`)↔ `components/ChatInputBox/ButtonArea.tsx:62-105`(`applyModelMapping`)↔ `components/ChatInputBox/selectors/ModelSelect.tsx`(本地 `resolveMappedModelName` + `MODEL_ID_TO_MAPPING_KEY` 离线表)
- **现象**:localStorage 模型映射的读取与应用分散两处(ButtonArea 内联 `mapping[key]||mapping.main` 双层 trim、ModelSelect 本地 `resolveMappedModelName` 含 `opus_1m` 死代码)。
- **根因**:未组件化。
- **修复方向**:收口到单一 hook/util。
- **验收**:**✓ 达成**。模型映射解析单点(`claudeModelMapping.resolveMappedModelName`),两处消费方共用;`opus_1m` 死代码消除。
- **关联**:A3(下沉后部分逻辑消失)
- **修复记录**(2026-06-24,统一纯函数入口):
  - **新增单一解析入口**:`utils/claudeModelMapping.ts` 增 `resolveMappedModelName(role, mapping)` 纯函数(role 命中取 `mapping[role]`,否则回退 `mapping.main`,trim 后返回;`role=undefined` 仅取 main)。ButtonArea 与 ModelSelect 共用,消除两套 key 解析 + `opus_1m` 死代码。
  - **ButtonArea 接入**:`applyModelMapping` 内联的 `mapping[key] || mapping.main` + 双层 trim 改调 `resolveMappedModelName(key, mapping)`(actualModel 守卫 + `!key` 自定义模型跳过守卫保留,行为等价)。
  - **ModelSelect 收口**:删 `MODEL_ID_TO_MAPPING_KEY` 离线 id→key 表 + 本地 `resolveMappedModelName`(含 `opus_1m` 死代码分支);新增 `getRoleForModelId(id)` 纯读 registry `role` 字段(与 ButtonArea/生产同源,与 A3 范式一致);`resolveModelIdForIcon`/`getModelLabel` 改用之 + 导入 `resolveMappedModelName`;两个调用点删第三参数。`getModelLabel` 保留 `if (role)` 守卫——自定义模型(无 role)跳过映射走 i18n 回退,与原行为等价。
  - **测试**:`claudeModelMapping.test.ts` 新增 `resolveMappedModelName` 8 例(role 命中/空串回退 main/缺失键回退/无 main undefined/role undefined 仅取 main/trim/纯空白 undefined/空映射);`ModelSelect.test.tsx` 补 registry 种子(映射解析已 registry 化,内置模型须经后端下发入 registry 才命中,反映生产真实数据流)。webview `tsc --noEmit` 0 错;vitest **842/842 全绿**(零回归)。

### D6 · token / context 格式化未统一

- **严重度**:低 | **状态**:**✓ 已验证(2026-06-24)** | **归属**:总则四
- **位置**:`components/settings/ModelRegistrySection/index.tsx`(原 `formatContext`)↔ `components/ChatInputBox/TokenIndicator.tsx`(原 `formatMaxTokensK`);登记簿原述「StatusPanel 的 token 展示」经核实**不存在**(StatusPanel 仅展示 todos/fileChanges/subagents 统计,无 token 格式化),真实重复为 ModelRegistrySection 与 TokenIndicator 两处逐字等价的「容量简写」。
- **现象**:token / context window 容量简写(K/M)多处各写。
- **根因**:展示变换未抽共用(属可接受的纯展示变换,但应复用)。
- **修复方向**:抽到 `utils/` 展示工具单点。
- **验收**:**✓ 达成(核心重复)**。容量简写单点 `utils/formatNumber.ts#formatCapacity`,两处消费方共用。
- **关联**:—

**验证(2026-06-24)**:
- **真实重复盘点(超登记簿预估)**:全 webview 共 6 处数字→K/M/B 格式化,分两类 —— ① **容量简写**(逐字等价):`ModelRegistrySection.formatContext`(`≥1M→M / round(v/1K)K`)与 `TokenIndicator.formatMaxTokensK`(同),调用域均为 ≥1K 的 contextWindow/maxTokens 容量上限;② **token 用量展示**(有意差异):`ContextUsageDialog.formatTokens`(`toFixed(1)`+小写k)、`TokenIndicator` chip 内 `formatTokens`(整数优先 else `toFixed(1)`+小写k)、`UsageStatistics.formatNumber`(`toFixed(1)`+大写K+含B档),精度/大小写/档位各异;另 `ProviderSelect`/`utils/messageUsage#formatTokenCount` 为千分位 `toLocaleString`(非 K/M 主题)。
- **核心重复消除**:新建 `utils/formatNumber.ts` 导出 `formatCapacity(value, fallback?)`(`≥1M→${v/1e6}M` / `≥1K→${round(v/1e3)}K` / `<1K→原值` / falsy→`''`,对齐原 `formatMaxTokensK` 守卫);`ModelRegistrySection` 删 `formatContext` 改 `formatCapacity(model.contextWindow, DEFAULT_CONTEXT_WINDOW)`,`TokenIndicator` 删 `formatMaxTokensK` 改 `formatCapacity(...)`。两处调用域 ≥1K,行为逐字等价,零 UI 变化。
- **用量展示差异·降级保留**(对齐 A7/A8/A9 展示分类降级惯例):3 处 token 用量展示的精度/大小写/档位差异是各自 UI 区域(ContextUsageDialog 弹窗 / TokenIndicator chip / UsageStatistics 统计)的有意视觉设计,统一任一标准都会改变用户可见显示;`formatCapacity` 头注释已明确标注用量展示不在其范围,防未来误统一。若后续需统一用量展示,经 UI 评审选标准后再收敛。
- **守门**:`utils/formatNumber.test.ts` 5 例(M整数/K四舍五入/<1K原值/fallback/falsy空串);webview `tsc --noEmit` 0 错;vitest **847/847 全绿**(含新增 5 例,零回归);`gradle compileJava`(含 webview build)BUILD SUCCESSFUL。

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
- **验收**:**✓ 达成(主体开闭 + 装配接受并标注)**。新增 provider runtime **不改 Router 主体**(`SessionRuntimeRouter` 的 send/interrupt/disposeTab 经 `SessionRuntimeRegistry` Map 查表;`SessionProviderRouter` 的 launchChannel 等经 `ProviderRegistry.require` Map 查表 —— E1/E3 副产品,主体已开闭);装配层手工 new 经评估接受并标注。
- **关联**:迁移 P2-C 评估项

**验证(2026-06-24,接受并标注)**:
- **主体开闭已实质达成**:路由主体全 Map 化(E1/E3),新增 runtime/adapter 仅需装配构造函数加一行 `register`/`new`,不改 `send`/`interrupt`/`resolve`/`launchChannel` 等主体方法。验收「不改 Router 主体」满足。
- **装配层评估·接受手工 new**:① `SessionProviderRouter` 的 2 个 adapter 经 `List.of(...)` 装进 `ProviderRegistry` 容器(容器本身已是注册化结构);② `SessionRuntimeRouter` 的 4 个 SessionRuntime 实现依赖**异构**(Claude/Codex Sdk 依赖各自 `ClaudeSDKBridge`/`CodexSDKBridge`,Cli 依赖 `CliSessionManager`),无法像 `CliSessionManager`(单一 `cliManager` 依赖,E1 已 `CliSessionFactory` 工厂化)那样统一工厂签名 —— 强推工厂注册表需 awkward 的异构依赖注入,是样板 churn。无 DI 容器 / 无 SPI 的 IntelliJ 插件装配层,手工 `new` + `register` 是合理惯例。
- **标注落地**:`SessionRuntimeRouter` / `SessionProviderRouter` 类 Javadoc 各加「装配 vs 路由(E7 决策)」段,说明主体开闭 + 装配接受手工 new 的理由与 `CliSessionManager` 差异化原因,防未来维护者误以为需强推工厂化。
- **验证**:`gradle compileJava` BUILD SUCCESSFUL(Javadoc 无误);行为零变化(纯注释)。

### E8 · DTO / Converter 分层未落地

- **严重度**:中 | **状态**:**✓ 已验证(接受并标注,对齐 P3-B,2026-06-24)** | **归属**:总则四 + 附录(四对象分层)
- **位置**:`ModelConfig`/`ModelRegistryConfig`(record PO)+ `ModelRegistryService.serialize/parse`(边界 Converter,P3-B 已落地);消息域 `MessageJsonConverter`/`CodexMessageConverter`(已存在);`TokenUsageUtils.buildUsageUpdatePayload`(单点 util);provider 配置 JsonObject(刻意保留,见核实)
- **现象**:出参(下发 payload)无独立 DTO record、无独立 Converter 类,逐字段手拼 JsonObject;PO 与传输对象混用 record。
- **根因**:四对象分层未落地。
- **核实记录**(2026-06-24):登记簿原述「无 DTO/PO/Response/Converter」**过时**(消息域已有 `MessageJsonConverter`/`CodexMessageConverter`)。对齐 P3-B 决策(`memory: p3b-dto-converter-layering-decision`):
  - **DTO+Converter 范式已建立并应用于真正受益处**:`ModelConfig`(record,稳定固定形状)+ `ModelRegistryService` 作 Converter(`parse(JsonObject)→ModelRegistryConfig` / `serialize→JsonObject`),JsonObject 仅在传输边界;`ProviderViewModel`/`ModelSelectionRequest·Result`/`SessionRequest`/`CliSendRequest` 等既有 record;`SettingsHandler` JsonObject 计数=0(`FrontendActionHandler<T>` 类型化)。
  - **刻意保留 JsonObject(非债务)**:provider **配置**(`ProviderManager`/`CodexProviderManager`)半 schema-less 透传(合并任意键 / 读 cc-switch.db 任意 schema / 同步 `~/.claude/settings.json`),固定 record 丢字段或沦薄包装,破坏 cc-switch 兼容;`ModelRegistryResult`/`ModelRegistrySchemaResult` 内部 JsonObject 是已序列化传输载荷;流式消息场景(E8 修复方向自己注明「JsonObject 可刻意保留」)。
  - **usage payload**:`TokenUsageUtils.buildUsageUpdatePayload` 是 provider-native 动态计算的下发载荷(percentage/totalTokens 等,非持久化 PO),已是单点 util 出口;引入 DTO record 仅给一次性计算载荷加类型,无持久化/多处构造,收益边际(同 `ModelRegistryResult` 传输边界判断)。
- **验收**:稳定结构(模型注册表)payload 已经 record PO + 边界 Converter 序列化(P3-B 落地);消息域 Converter 已存在;provider/settings JsonObject 是合法传输边界/半 schema-less 透传(强推 record 是 churn,P3-B 结论)。**接受并标注**(同 [[d4-dialog-base-accept-and-annotate]]/C10/E7)。
- **关联**:迁移 P3-B(决策已落地,见核实记录)

### E9 · 序列化无统一出口

- **严重度**:中 | **状态**:**✓ 已验证(接受并标注,对齐 C2/C6,2026-06-24)** | **归属**:总则三 + 附录(序列化约定)
- **位置**:业务枚举经 `ProtocolValue` 出口(C2 已落地);消息域 `MessageJsonConverter`/`CodexMessageConverter`;多态字段 `ProtocolValue`(C6);payload JsonObject 显式构建(全仓 Gson 约定)
- **现象**:序列化出口分散手拼,枚举/多态字段无统一约定。
- **根因**:未建立统一序列化出口。
- **核实记录**(2026-06-24):登记簿原述「业务枚举(`ProviderType`/`RuntimeType`/`ClaudeRole`)无统一 value/desc 序列化」**过时**(C2 已落地)。逐项对齐:
  - **业务枚举统一出口**:`ProviderType`/`RuntimeType`/`ClaudeRole`/`PermissionMode`/`ReasoningEffort` 等已 `implements ProtocolValue`(C2,见 [[protocol-enum-ssot-promotion-workflow]]),value/desc 经 ProtocolValue 单点;manifest 生成链经 mjs → `protocol.ts` 三端守门。
  - **消息域 Converter**:`MessageJsonConverter`(`convertMessagesToJson`/`pushUsageUpdateFromMessages`)、`CodexMessageConverter`(`convertCodexMessageToFrontend` 等)已是序列化单点。
  - **多态字段约定**:C6 `ProtocolValue` 已统一(见 §7 表 C6 行)。
  - **payload JsonObject 显式构建**:全仓统一用 `GsonHolder.GSON` + 显式 `JsonObject.addProperty`(非 Gson 反射绑定 / TypeAdapter),这是项目一致的序列化约定(显式控制 wire 字段,对齐 C1 payload schema 生成);payload 在传输边界手拼是 P3-B 刻意保留(见 E8)。引入 `@JsonTypeInfo`/`JsonSerializer`/`TypeAdapter` 是切换到反射绑定序列化范式,与现有显式 JsonObject 构建不一致,属范式级 churn。
- **验收**:稳定结构 payload 经 Converter 单点(消息域 Converter + 模型注册表边界 Converter);枚举统一出口(ProtocolValue,C2);多态字段统一(C6)。payload 显式 JsonObject 构建是项目序列化约定 + 传输边界。**接受并标注**。
- **关联**:迁移 P3-B / P2-A(C2/C6 已落地,见核实记录)

### E10 · 配置外置不充分

- **严重度**:中 | **状态**:✓ 已验证(接受并标注,项目已采用更优领域专用 SSOT) | **归属**:总则五
- **位置**(逐处核实后修正):登记簿原列 `CodexCliCommandUtils:17-23 PROTECTED_ENV_KEYS` **已过时**——A5/C5 已提升为 `CodexProtectedEnvKey` 枚举 SSOT(`Arrays.stream(values()).map(::value)`,经生成链三端守门,优于 json)。其余逐处归类——`config/RuntimePolicyConfig.java`(外置到 `~/.codemoss/config.json`,**路由策略类**,运行期可调,正确特例);`cli/common/CliConstants.java`(CLI 参数 / 事件类型 / env 名 / sandbox 值 / 权限模式——**协议常量**,应编译期固化,本文件即集中层);`api-config.js:25 FALLBACK_CLI_VERSION='2.1.88'`(SDK manifest.json 动态解析后的**末位安全网**,manifest→pkg 版本转换→fallback 三级回退,几乎不命中);`api-config.js:127-161 MODEL_ROUTING_ENV_VARS/REASONING_CONTROL_ENV_VARS`(env 名清单,语义绑定请求流——webview 逐回合拥有 / 须中和,非外部可配置);`api-config.js:173-191 DANGEROUS_ENV_VAR_SET`(安全黑名单——**外置到 json 反成安全倒退**,应编译期固化)。
- **现象**(修正):登记簿"易变参数写死、仅路由策略外置"**过时**——真正易变的(CLI 版本)已**动态解析自 SDK manifest**;安全 allowlist 已**枚举 SSOT**(CodexProtectedEnvKey);协议常量已 **CliConstants** 集中;路由策略已 **RuntimePolicyConfig** 外置。
- **根因**(修正):非"配置外置未系统化"——项目已采用**领域专用 SSOT 范式**(枚举 SSOT 守门安全 allowlist + CliConstants 集中协议常量 + SDK manifest 动态解析版本 + RuntimePolicyConfig 外置路由),优于通用 `resources/providers/*.json`。
- **修复方向**(接受并标注):`resources/providers/*.json` + `ProviderConfigLoader` 通用范式**劣于/冗余于**已落地范式——(1)安全 allowlist 外置成可编辑文件 = 攻击面扩大(恶意 settings.json 删 `NODE_OPTIONS` 即逃逸);(2)协议常量(CLI 参数名 / 事件类型)外置无收益且增运行期解析开销与单点故障;(3)版本已动态解析。`RuntimePolicyConfig` 路由策略外置是**正确特例**(运行期可调路由,非协议 / 安全常量),已就位。
- **验收**(达成):新增对接零代码改既有——provider 新增经 `BaseSDKBridge` 子类化 + ai-bridge `provider-registry` 注册,既有 bridge 主体不动;安全 / 协议常量经枚举 / CliConstants 单点维护。
- **关联**:迁移 Phase 5 / A5-C5(安全 allowlist 枚举 SSOT)

### E11 · Java 侧缺 Bridge Adapter 抽象

- **严重度**:中 | **状态**:✓ 已验证(接受并标注,Adapter 抽象已由 BaseSDKBridge 抽象类承担) | **归属**:总则五
- **位置**:`provider/common/BaseSDKBridge.java`(`public abstract class` + `protected abstract String getProviderName()` 模板方法 at L67;子类 `ClaudeSDKBridge:170` / `CodexSDKBridge:125` override 返回字面量 `"claude"`/`"codex"`;`command.add(getProviderName())` at L411 为模板方法调用)。
- **现象**(修正):登记簿"未抽象成 Adapter 接口"**过时**——`BaseSDKBridge` 抽象类 + `getProviderName()` 模板方法**本身就是** Adapter 抽象(子类提供 provider 身份,基类持有通用启动 / 进程管理 / 通道复用逻辑);`command.add(getProviderName())` 是模板方法调用,非字面量硬编码路由。
- **根因**(修正):非"缺 Adapter 接口"——已有抽象类形态。子类 `getProviderName()` 返回字面量而非 `CliConstants.PROVIDER_*` 属**轻微字面量重复**(死稳定的 ai-bridge 路由键 = canonical CLI 命令名,2 站点从未分叉)。
- **修复方向**(接受并标注):`SdkBridgeAdapter` 接口 + `supports(provider)` 是**冗余第二抽象层**(抽象类已提供同能力,`supports()` 退化为 `getProviderName().equals()`,引入只增层级不增能力)。两侧 provider 概念对齐:Java `getProviderName()` 字符串与 ai-bridge `provider-registry` 路由键同值("claude"/"codex")即**契约对齐**(跨进程边界语义绑定,非代码共享,合理)。残留字面量 → `CliConstants.PROVIDER_*` 对齐为可选微清理(非本债批次阻塞项)。
- **验收**(达成):新增第 3 SDK 不改既有 bridge 主体——新 provider = 新 `XxxSDKBridge extends BaseSDKBridge` + ai-bridge `provider-registry` 注册,既有 Claude / Codex bridge 主体零改动。
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
| A5 | 前端业务默认值/校验 | 中 | ✓ 已验证 | 一 | Phase4 / P2-B |
| A6 | 版本决策前后端双写 | 中 | ✓ 已验证 | 一/四 | Phase4·V6 |
| A7 | 工具分类纯前端硬编码 | 中 | 已豁免 | 一 | Phase4·V7 |
| A8 | 会话标题候选判定(边界) | 低 | ✓ 已验证 | 一 | — |
| A9 | 可回滚性判定(边界) | 低 | ✓ 已验证 | 一 | A7 |
| A10 | PROVIDER_PRESETS 前端持业务表 | 中 | 已豁免 | 一/三 | P0-2 |
| B1 | MessageDispatcher 线性链兜底 | 中 | ✓ 已验证 | 二 | P1-C / P3-C |
| B2 | 20 个 legacy MessageHandler SUPPORTED_TYPES | 高 | ✓ 已完成(20/20) | 二 | P1-C |
| B3 | SettingsHandler 60+ 字符串分派 | 高 | ✓ 已验证(全迁移+类删除) | 二 | P1-C |
| B4 | HistoryHandler 孤儿 | 中 | ✓ 已完成 | 二 | P1-C 排查 |
| B5 | 下行 type 字面量散落 | 中 | ✓ 已验证 | 二 | P1-B |
| C1 | payload 字段结构未生成 | 高 | ✓ 已验证 | 三 | Phase1 / Phase2·V3 |
| C2 | 业务枚举 SSOT 全未落地 | 高 | ✓ 已验证 | 三 | P2-A |
| C3 | 默认值漂移(已发生) | 高 | 已验证 | 三 | P2-B / P2-A |
| C4 | 前端协议字面量第二真相源 | 高 | 已验证 | 三/四 | P1-B |
| C5 | 业务默认值前后端手抄 | 中 | ✓ 已验证 | 三 | P2-B |
| C6 | ProtocolValue 无 desc | 中 | 已验证 | 三 | P2-A |
| C7 | 双 manifest 写入者 | 低 | ✓ 已验证 | 三 | P0-1 |
| C8 | mjs regex 解析脆弱 | 中 | 已验证 | 三 | P0-1 |
| C9 | ProviderType 未纳入生成 | 中 | 已验证 | 三 | P2-A |
| C10 | window.xxx 旧回调名并存 | 中 | ✓ 已验证 | 三 | bridge 归一化(接受并标注) |
| D1 | bridge/events 第二真相源(复用) | 高 | 已验证 | 四 | = C4 |
| D2 | canUseLocalStorage 重复 | 中 | 已验证 | 四 | — |
| D3 | ViewMode 三处重复定义 | 中 | 已验证 | 四 | — |
| D4 | Dialog 无统一基类 | 中 | ✓ 已验证 | 四 | 接受并标注边界 |
| D5 | 模型映射读取分散 | 低 | ✓ 已验证 | 四 | A3 |
| D6 | token/context 格式化未统一 | 低 | ✓ 已验证 | 四 | — |
| E1 | CLI session 工厂 if/else | 中 | 已验证 | 五 | P2-C |
| E2 | 消息归一化器嵌套 if/else | 中 | 已验证 | 五 | P2-C |
| E3 | SessionProviderRouter 解析硬编码 | 中 | 已验证 | 五 | P2-C |
| E4 | ModelRegistryConfig provider 分支 | 中 | 已验证 | 五 | P2-C |
| E5 | reasoningLevelsFor provider 判定 | 中 | 已验证 | 五 | P1-A1 / P2-C |
| E6 | provider 字符串判定散落多处 | 中 | 已验证 | 五 | P2-C |
| E7 | 装配阶段硬编码 new | 低 | ✓ 已验证 | 五 | P2-C 评估 |
| E8 | DTO/Converter 分层未落地 | 中 | ✓ 已验证 | 四+附录 | 接受并标注(对齐 P3-B) |
| E9 | 序列化无统一出口 | 中 | ✓ 已验证 | 三+附录 | 接受并标注(对齐 C2/C6) |
| E10 | 配置外置不充分 | 中 | ✓ 已验证(接受并标注) | 五 | Phase5 |
| E11 | Java 侧缺 Bridge Adapter 抽象 | 中 | ✓ 已验证(接受并标注) | 五 | Phase5 |
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
