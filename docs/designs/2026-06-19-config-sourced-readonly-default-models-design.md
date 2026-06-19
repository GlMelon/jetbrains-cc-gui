# 配置文件驱动的只读默认模型 设计

- 日期：2026-06-19
- 分支：feature/v0.4.6
- 状态：方案已确认(2026-06-19 评审定稿为「Claude 4 role + Codex 均为只读默认,后端合并 + 提供商切换自动刷新 + 新增冲突校验」)
- 修订记录：
  - 初版(Option A)：仅对 Codex 注入只读默认,Claude 保持 4 role 可编辑。
  - **评审推翻 Option A**(用户挑战)："Claude 有 4 role 保底"是脆弱前提——4 role 本就可编辑/删除,删空后同样会崩;且 role→actualModel 的注册表定制与真实请求链路(`ClaudeCliModelResolver` 直接读 `settings.json`)并不耦合。故收敛为 **Option C**：4 role 与 Codex 一并收敛为只读默认,actualModel 由后端从配置文件读取,提供商切换后自动重新读取。
  - 增量优化(用户)：新增自定义模型时,去重校验也必须覆盖只读默认键,冲突即拒绝新增。

## 1. 背景与问题

切换到 Codex provider 时,模型选择器 `ModelSelect` 崩溃：

```
TypeError: Cannot read properties of undefined (reading 'id')
    at ModelSelect (...)
```

### 根因（已定位）

1. **直接崩溃点**——`webview/src/components/ChatInputBox/selectors/ModelSelect.tsx:134`：
   ```ts
   const currentModel = exactSelectedModel || models.find(...) || models[0];
   ```
   当 `models` 为空数组时 `models[0] === undefined`,随后第 269/273/277 行访问 `currentModel.id` 抛错。

2. **为何 Codex 会传空数组**——`webview/src/components/ChatInputBox/ButtonArea.tsx:85-89` 存在与 Claude 不对称的逻辑：Codex 分支 `return registryModels;` 无兜底,可能为 `[]`；Claude 分支有内置兜底常量 `CLAUDE_MODELS`(4 个 role)。

3. **为何 Codex 无内置模型是有意设计**——`types.ts:446` `CODEX_MODELS = []`,且有测试 `ModelSelect.test.tsx:140` 断言其为空。Codex 模型名强依赖用户的 API 提供商,硬编码会发错模型名。

4. **后端 registry 默认只含 Claude 4 role**——`ModelRegistryConfig.buildDefault()` 仅 4 个 Claude role；`getModelsForProvider('codex')` 在用户未配置时返回 `[]`。

5. **Claude "保底" 的脆弱性(推翻 Option A 的关键)**——4 role 来自持久化默认 `getDefault()`,**可被编辑/删除**。一旦用户在设置页删空 Claude 模型,Claude 分支同样传空 → 同一个崩溃。把 Claude 排除在外并不能真正修复问题。

6. **role→actualModel 注册表定制与请求链路解耦**——Claude 请求时模型解析走 `ClaudeCliModelResolver.resolveMapped`,直接读 `~/.claude/settings.json` 的 env 块(经 `CliSettings.readClaudeCliEnvironment()`),**不读注册表的 `actualModel`**。注册表 `actualModel` 仅用于显示(`ButtonArea:64` 短路)。因此把 4 role 收敛为只读、actualModel 改由后端从 settings.json 读取,**不会丢失任何定制能力**——用户改模型走"供应商管理 → settings.json"这条真实链路,而非注册表编辑器。

### 目标

- 模型列表对 Codex 与 Claude **都不再为空、不再崩溃**：4 role + Codex 默认全部由只读默认提供,前端无从删空。
- **Claude 4 role + Codex 默认**：actualModel 由后端从配置文件读取(Claude ← `~/.claude/settings.json` env；Codex ← `~/.codex/config.toml` 的 `model=`),作为**只读**条目注入 registry：可查看,不可编辑/删除/停用。
- **后端合并 + 自动刷新**：配置读取与合并全部在后端完成；前端只显示。提供商切换(供应商管理重新授权)写完 settings.json 后,后端自动重新读取并推送 `model_registry`,前端被动刷新。
- **新增冲突校验**：新增自定义模型时,其去重键不得与只读默认键冲突；冲突则拒绝新增(前端即时拦截 + 后端权威兜底)。
- 用户自定义(非 role)模型仍可在设置页增删改。

### 非目标

- 不重新引入硬编码的 Codex GPT 版本清单(与现有设计冲突)。
- 不改造 `ClaudeCliModelResolver` 请求解析链路(它本就读 settings.json,保持现状)。
- 不重构 Codex Provider 对话框的 `customModels` / `modelCatalog` 机制(见 §8 已知交互)。
- 不改动 Gemini / OpenCode 等未启用 provider。
- 不引入文件监听器(watchService)；刷新由"提供商切换写完配置"这一显式事件驱动(见 §6)。

## 2. 架构：registry 用户层 + 只读默认(后端运行时合并)

模型注册表 = 持久化用户层 + 运行时只读默认,**合并发生在后端**：

| 层 | 来源 | 持久化 | 可编辑性 |
|---|---|---|---|
| **只读默认** | 运行时计算：Claude 4 role(从 settings.json env 解析 actualModel) + Codex(从 config.toml `model=`) | **否**(每次现算) | 只读 |
| **用户自定义** | `CodemossSettingsService` 持久化(用户增删的**非 role** Claude/Codex 模型) | 是 | 完全可编辑 |

`getModelRegistry()` 返回 `ReadOnlyDefaultModels.mergeWithReadOnlyDefaults(用户层)`,只读项带 `readOnly=true`。**只读默认不进持久化**——磁盘配置改动 + 提供商切换后,下次现算即生效(权威源)。

### 合并 / 去重规则(不对称)

去重键 = `provider:id`,其中 id 经 `ModelRegistryConfig.stripCapacitySuffix` 剥容量后缀后小写(与 `find()` / `resolveModelSelection` 语义一致)。

- **Claude role 键(`claude-role-*`)= 保留键,只读恒胜**：用户层若残留同键项(legacy),直接跳过(去重覆盖,**不删磁盘**)。role 不可被用户层覆盖。
- **Codex / 其他键 = 用户优先,只读填补空缺**：用户层有同键 Codex 模型 → 保留用户的(可编辑),只读默认被替换；用户层无 → 追加只读默认。
  - 配合 §4 冲突校验,**新增** Codex 模型若与只读默认冲突会被拒绝；故"用户优先"分支仅对 legacy 持久化数据生效(grandfather)。

> 设计意图：role 是保留命名空间,任何途径都不能占用；Codex 默认是"未配置时的兜底",用户既有的同名模型保留可见可编辑,但不再允许新建同名。

### `getDefault()` 的角色变化

`ModelRegistryConfig.getDefault()`(返回 4 role,`actualModel=""`)**保留但降级为 legacy 兜底**：
- `getModelRegistry()` 不再以 `getDefault()` 作为"缺失/无效"回退——改为 `mergeWithReadOnlyDefaults(空用户层)`,其结果本身就是 4 个只读 role(+ Codex 默认)。
- 保留 `getDefault()` 是为了：(a) 不破坏其它潜在调用方；(b) 新用户首次 `setModelRegistry` 校验通过(只读 role 保证 effective 非空)。
- 前端 `DEFAULT_MODEL_REGISTRY`(`modelRegistry.ts:21`)独立于后端 `getDefault()`,本次不改(仅作后端首次推送前的占位)。

## 3. 数据模型

### 后端 `ModelConfig` record（`config/ModelConfig.java`）

新增第 10 个字段：
- `boolean readOnly`（默认 `false`）

`readOnly=true` 即等价于"来自 CLI 配置文件"。不另设 `source` 字段(YAGNI)。

**涟漪点**：规范构造器变为 10 参；并保留一个 9 参便利构造器(委托规范构造器、`readOnly=false`)——现有所有 9 参 `new ModelConfig(...)` 调用(解析路径、`roleConfig`、测试 fixture)无需改动即兼容。仅 `normalized()` 改用 10 参规范构造器以透传 `readOnly`(否则归一化会丢失只读标记);解析路径走 9 参便利构造器天然强制 `readOnly=false`(后端权威,不接受前端标记只读)。只读构造(`ReadOnlyDefaultModels`)显式用 10 参 + `true`。

### 序列化 JSON 形状（`SettingsHandler.serializeModelRegistry` / `CodemossSettingsService.serializeModelRegistry` / 前端 `parseModelRegistryPayload`）

每项增发：
```json
{ "id": "...", "provider": "...", ..., "enabled": true, "readOnly": true }
```

### reset 语义变化

`resetModelRegistry()` 删除持久化键 = **清空用户层**。只读默认照常由 `getModelRegistry()` 叠加 → 用户可见的 4 个 Claude role(+ Codex 默认)仍在,语义从"持久化默认"转为"运行时只读默认"。无数据风险。

## 4. 后端改动

| 文件 / 方法 | 改动 |
|---|---|
| `config/ModelConfig.java` | 新增 `readOnly` 字段(第 10 参)；`normalized()` 透传。 |
| **新增 `config/ReadOnlyDefaultModels.java`** | 见下"只读默认计算器"。 |
| `settings/CodemossSettingsService.getModelRegistry()`（:1683） | 改为 `mergeWithReadOnlyDefaults(readPersistedUserLayer())`；`readPersistedUserLayer()` 为**裸读磁盘**(缺失/无效返回空,不走 `getDefault()`)。 |
| `CodemossSettingsService.setModelRegistry()`（:1706） | ①`stripReadOnly` 剥离入站只读项；②**冲突校验** `checkNoNewConflictsWithReadOnly`(新增项不得占用只读键)；③`validate(mergeWithReadOnlyDefaults(userOnly))`——校验作用于 **effective**(只读 role 保证"至少一个启用",故用户层可为空)；④持久化 `userOnly`。 |
| **新增 `CodemossSettingsService.getModelRegistryJson()`** | `public`,返回 `serializeModelRegistry(getModelRegistry()).toString()`。供 `ClaudeProviderOperations` 切换后推送刷新(复用私有序列化器,无需改可见性的序列化器本体)。 |
| `CodemossSettingsService.parseModelRegistry()`（:1740）/ `SettingsHandler.parseModelRegistryFromJson()`（:561） | 入站强制 `readOnly=false`(10 参构造补末参 `false`,后端权威)。 |
| `CodemossSettingsService.serializeModelRegistry()`（:1768）/ `SettingsHandler.serializeModelRegistry()`（:537） | 逐项增发 `readOnly`。 |
| `handler/provider/ClaudeProviderOperations.java`（:313 / :344 / :360 三处 `invokeLater`） | 在 `applyActiveProviderToClaudeSettings()` 写完 settings.json 后,于各 `invokeLater` 块追加 `context.dispatchEvent("model_registry", context.escapeJs(context.getSettingsService().getModelRegistryJson()))`。前端已订阅 `model_registry`,被动刷新。 |
| `config/ModelConfigValidator.java` | **无需改动**。校验目标改为 effective(见 setModelRegistry ③),只读 role 恒在其中 → 非空与「至少一个启用」恒满足。 |

### 只读默认计算器 `ReadOnlyDefaultModels`

```
compute():
  claudeEnv = CliSettings.readClaudeCliEnvironment()        // 已有:合并 ~/.claude/settings.json env
  for role in ClaudeRole.values():
    actualModel = role.envKeys() 中首个非空值(无则 "")
    → ModelConfig(role.roleId(), PROVIDER_CLAUDE, role.shortName(),
                  capitalize(role.shortName()), actualModel,
                  "<Role> role · 来自 ~/.claude/settings.json",
                  role.contextWindow(), role.supports1MContext(),
                  enabled=true, readOnly=true)
  codexEnv = CliSettings.readCodexCliEnvironment()          // 已有:读 ~/.codex/config.toml
  codexModel = codexEnv.get(CliConstants.ENV_CODEX_MODEL)   // 已由 readCodexCliEnvironment 提取
  if codexModel 非空:
    → ModelConfig(codexModel, PROVIDER_CODEX, "", codexModel, "",
                  "只读 · 来自 ~/.codex/config.toml", 200_000, false,
                  enabled=true, readOnly=true)

mergeWithReadOnlyDefaults(userLayer):
  result = compute()  // 只读默认在前
  readOnlyKeys = result 的去重键集合
  for user in userLayer.models():
    key = dedupKey(user)
    if user.id 以 "claude-role-" 开头(claude provider): 跳过(role 保留键,只读恒胜)
    else if key ∈ readOnlyKeys: 移除 result 中同键只读项,追加 user(codex 用户优先)
    else: 追加 user
  return new ModelRegistryConfig(result)

dedupKey(provider, id): stripCapacitySuffix(id).toLowerCase() + ":" 前缀 provider 小写
```

**复用而非新建**：不新增 `CodexConfigReader`——`CliSettings.readCodexCliEnvironment()`（:126-135）已通过 `CodexSettingsManager.readConfigToml()` + `TOML_KEY_MODEL` 把模型放入 `ENV_CODEX_MODEL`；Claude 端 `readClaudeCliEnvironment()` 已合并 settings.json env。两段均为已测试代码。

### 冲突校验 `checkNoNewConflictsWithReadOnly(userOnly)`

```
currentKeys  = readPersistedUserLayer() 的去重键  // 当前磁盘用户层(裸读)
readOnlyKeys = compute() 的去重键
for m in userOnly:
  key = dedupKey(m)
  if key ∈ readOnlyKeys 且 key ∉ currentKeys:   // 仅拦截"新增"冲突
    error("模型 " + m.id() + " 与配置文件默认模型冲突,无法新增")
```

**仅拦截新增(legacy 放行)**：与"只读覆盖、不删磁盘"一致——磁盘上既存的同键 legacy 项保留(合并时 role 被跳过 / codex 被用户覆盖),不阻塞用户其它无关保存；只有"本次新引入"的冲突才拒绝。这避免了"老用户因 legacy role 残留而无法保存任何改动"的回归。

### 调用点零改动说明

`ModelProviderHandler.applyModelChange` 与 `SettingsHandler` 三处序列化均调用 `getModelRegistry()`；改为内部合并后,它们自动拿到 effective registry,**无需改名或改签名**。`find()` / `resolveModelSelection()` 因 effective registry 含 4 role 而行为不变(`find()` 按 enabled 过滤,只读 role `enabled=true`)。

## 5. 前端改动

| 文件 | 改动 |
|---|---|
| `utils/modelRegistry.ts` | `ModelRegistryItem` 增 `readOnly?: boolean`；`parseModelRegistryPayload` 读取 `readOnly: obj.readOnly === true`。**无需新增订阅**——已 `bridgeHub.subscribe('model_registry')`（:47）,后端推送即自动刷新。 |
| `components/ChatInputBox/selectors/ModelSelect.tsx`（:130-135 / :265-279） | **防崩守卫(无条件修复崩溃点)**：`hasModels = models.length > 0`；`resolvedModel = hasModels ? (...||models[0]) : null`。按钮在 `resolvedModel` 为 null 时渲染"未配置模型"占位、`disabled={!hasModels}`、**绝不访问 `.id`**。 |
| `components/settings/ModelRegistrySection/index.tsx` | ①只读行(`readOnly===true`)隐藏 Edit/Delete 按钮、改显锁标(`codicon-lock`)；②`persistRegistry`（:85）/`removeModel` 提交前 `filter(i => !i.readOnly)` 剥离只读项(双保险,配合后端剥离)；③`saveEditing`（:120）现有去重已覆盖只读项(`registry.items` 含合并后的只读默认)——冲突时 toast,无需额外逻辑。 |
| `components/ChatInputBox/ButtonArea.tsx`（:85-89） | Codex 分支保持现状——只读默认已由后端注入,`getModelsForProvider('codex')` 自然含之；极端空时由 ModelSelect 守卫兜底。 |
| `components/ChatInputBox/types.ts` | `CODEX_MODELS = []` 保持不变。 |

## 6. 数据流

```
App 启动
  → 前端 requestModelRegistry()
  → 后端 handleGetModelRegistry()
  → serialize( getModelRegistry() = mergeWithReadOnlyDefaults(用户层) )   // 带 readOnly
  → dispatch model_registry

用户在设置页增删改(非 role 自定义模型)
  → set_model_registry(前端已 filter 只读项)
  → 后端 setModelRegistry: stripReadOnly → 冲突校验 → validate(effective) → 持久化 userOnly
  → 重新 getModelRegistry() 合并
  → dispatch model_registry_updated + model_registry

用户在供应商管理重新授权 / 切换 Claude provider
  → ClaudeProviderOperations.applyActiveProviderToClaudeSettings() 写 ~/.claude/settings.json
  → invokeLater 块追加 dispatch model_registry( getModelRegistryJson() 现算 )
  → 前端被动刷新(只读 role actualModel 反映新 settings.json)
  (磁盘手改 config.toml / settings.json → 下次 getModelRegistry() 现算即生效)
```

## 7. 错误处理

- 合并 / 解析 / 配置读取异常：吞异常 + `LOG.warn` + 回退到"只读默认单独"(用户层按空处理),**绝不抛到渲染层**。
- settings.json / config.toml 缺失：4 role 仍在(actualModel 留空,显示名走 role 标签)；Codex 默认缺省不注入。
- 前端收到无 `readOnly` 字段的旧 payload：按 `false` 处理(向后兼容)。
- 冲突校验失败：`setModelRegistry` 返回带 errors 的 invalid `ValidationResult` → `handleSetModelRegistry` 推 `model_registry_updated{success:false, errors}` → 前端 toast(errors join)。

## 8. 已知交互（本次不重构）

Codex Provider 设置对话框(`useCodexProviderManagement` / `createCodexCatalogModels`)现有的 `customModels` / `modelCatalog` / `configToml` 同步机制继续保留,用于用户在 Provider 对话框里策划的**可编辑** Codex 模型清单。

- 本次新增的只读默认(磁盘 `model=`)与该机制可能对同一模型重叠 → 由 §2 去重规则处理。
- legacy：用户既有同名 Codex 模型 → 用户优先(可见可编辑)；新增同名 → 冲突校验拒绝。

## 9. 迁移

- 现有用户持久化 registry 中残留的 4 个 Claude role(来自旧 `getDefault()` 持久化)：**保留在磁盘、不删**(用户选定"只读覆盖,不删磁盘")。合并时 role 保留键只读恒胜 → 显示只读版本；用户层残留被跳过。下次保存无关改动不被阻塞(冲突校验仅拦截新增)。
- 持久化层无 Codex 模型 + 磁盘 config.toml 有 `model=` → 升级后 getModelRegistry 注入 1 个只读 Codex 默认。
- 旧前端收到带 `readOnly` 的新 payload：忽略该字段,行为退化为"可编辑"——仅影响 UI 操作权限,不影响功能。

## 10. 测试

### 后端
- `ReadOnlyDefaultModels.compute()`：4 role(actualModel 来自 settings.json env,无配置则空)+ Codex(来自 config.toml,无则无)；全部 `readOnly=true`。
- `mergeWithReadOnlyDefaults()`：role 保留键只读恒胜(用户层 claude-role-* 被跳过)；Codex 用户优先(legacy 同名替换只读)、否则填补；纯自定义模型原样追加。
- `getModelRegistry()`：缺失/无效配置 → 仍返回 4 只读 role(非 getDefault 路径)。
- `setModelRegistry()`：剥离只读不落盘；**冲突校验**拒绝新增 claude-role-* / 与 config.toml 同名 Codex；legacy 同键放行；用户层为空时仍 valid(只读 role 保证 effective 非空)。
- `resetModelRegistry()`：清空用户层后 getModelRegistry 仍返回 4 只读 role(+ Codex 默认)。
- `parseModelRegistryFromJson()` / `parseModelRegistry()`：入站 `readOnly` 被强制 `false`。

### 前端
- `ModelSelect`：`models=[]` 时渲染占位、不抛错(**崩溃回归测试**);`models` 非空时行为不变。
- `ModelRegistrySection`：只读行无 Edit/Delete、显锁标；`persistRegistry`/`removeModel` 提交 payload 不含只读项;`saveEditing` 与只读默认冲突 → toast。
- `parseModelRegistryPayload`：正确读取 `readOnly`;缺字段默认 `false`。

### 权威门
现有 176 个 `.test.js` + 50 个 `.test.mjs` 保持全绿;`ModelSelect.test.tsx:140`(`CODEX_MODELS` 为空)继续通过。

### 现有测试改写(必然结果)
- `CodemossSettingsServiceModelRegistryTest`:构造器调用全部走 9 参便利构造器(无需补参);仅 `persistsValidCustomModelRegistry` 由持久化 `claude-role-opus` 改为持久化**非 role 自定义模型**(id=`mimo-v2.5-pro`、role=opus),并用 find-by-id 取值(合并后 index 0 不再保证是用户项)——冲突校验会拒绝 role 键。

## 11. 实施顺序（概要,详细计划交 writing-plans）

1. 后端 `ModelConfig` 加 `readOnly` + `ReadOnlyDefaultModels`(复用 `CliSettings`)+ 合并/剥离/冲突校验 + `getModelRegistryJson` + 序列化字段(含单测)。
2. `ClaudeProviderOperations` 三处切换路径注入 `model_registry` 推送。
3. 前端类型扩展 + `ModelSelect` 防崩守卫 + `ModelRegistrySection` 只读渲染 + 剥离只读提交(含单测)。
4. 改写现有后端测试 fixture;端到端验证:未配置不崩、配置后只读默认出现、切换 provider 后 actualModel 自动刷新、reset 行为正确、新增冲突被拒。
