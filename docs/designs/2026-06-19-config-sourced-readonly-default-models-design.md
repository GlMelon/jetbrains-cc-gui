# 配置文件驱动的只读默认模型 设计

- 日期：2026-06-19
- 分支：feature/v0.4.6
- 状态：方案已确认(2026-06-19 评审修订为「仅 Codex 只读默认」)
- 修订记录：初版拟将 Claude 4 role 与 Codex 一并标只读;评审发现会破坏现有 role→actualModel 定制功能(且 Claude 本就不会崩),故收敛为**只对 Codex 注入只读默认**,Claude 保持现状(4 role 持久化、可编辑)。

## 1. 背景与问题

切换到 Codex provider 时，模型选择器 `ModelSelect` 崩溃：

```
TypeError: Cannot read properties of undefined (reading 'id')
    at ModelSelect (...)
```

### 根因（已定位）

1. **直接崩溃点**——`webview/src/components/ChatInputBox/selectors/ModelSelect.tsx:134`：
   ```ts
   const currentModel = exactSelectedModel || models.find(...) || models[0];
   ```
   当 `models` 为空数组时 `models[0] === undefined`，随后第 269/273 行访问 `currentModel.id` 抛错。

2. **为何 Codex 会传空数组**——`webview/src/components/ChatInputBox/ButtonArea.tsx:85-89` 存在与 Claude 不对称的逻辑：
   ```ts
   if (currentProvider === 'codex') {
     return registryModels;                    // 无兜底，可能为 []
   }
   // Claude 分支：registryModels.length > 0 ? registryModels : CLAUDE_MODELS  ← 永不为空
   ```
   Claude 有内置兜底常量 `CLAUDE_MODELS`（4 个 role），Codex 无任何兜底。

3. **为何 Codex 没有内置模型是有意设计**——`webview/src/components/ChatInputBox/types.ts:446` `CODEX_MODELS = []`，且有显式测试 `ModelSelect.test.tsx:140` 断言其为空（"Codex 不再内置具体 GPT 版本清单"）。原因：Codex 模型名强依赖用户的 API 提供商（OpenAI / Azure / 第三方），硬编码会发错模型名。

4. **后端 registry 默认也只含 Claude**——`ModelRegistryConfig.buildDefault()` 仅 4 个 Claude role；`getModelsForProvider('codex')` 在用户未配置 Codex 模型时返回 `[]`。

### 目标

- 模型列表对 Codex **不再为空、不再崩溃**(Claude 本就有 4 role 兜底,不在范围内)。
- **仅 Codex**:从 `~/.codex/config.toml` 的 `model=` 读取默认模型,作为**只读**条目注入 registry:可查看,不可编辑/删除/停用。
- Claude 保持现状:4 role 仍由持久化默认提供,可编辑(保留 role→actualModel 定制能力,后端承重抽象不动)。
- 用户自定义模型仍可在设置页增删改。

### 非目标

- 不重新引入硬编码的 Codex GPT 版本清单（与现有设计冲突）。
- 不改造 Claude role→env 解析链路（`resolveConfiguredClaudeModelFromSettings` 保持现状）。
- 不重构 Codex Provider 对话框的 `customModels` / `modelCatalog` 机制（见 §8 已知交互）。
- 不改动 Gemini / OpenCode 等未启用 provider。

## 2. 架构：registry 用户层 + 只读 Codex 默认(运行时叠加)

模型注册表 = 持久化用户层 + 运行时只读 Codex 默认(叠加):

| 层 | 来源 | 持久化 | 可编辑性 |
|---|---|---|---|
| **只读默认(仅 Codex)** | 运行时从 `~/.codex/config.toml` 的 `model=` 计算 | **否**(每次现算) | 只读 |
| **用户自定义** | `CodemossSettingsService` 持久化(含 Claude 4 role 默认 + 用户增删的 Claude/Codex 模型) | 是 | 完全可编辑 |

`getModelRegistry()` 返回 `merge(用户层, 只读Codex默认)`,只读项带 `readOnly=true`。**只读默认不进持久化**——磁盘 `config.toml` 改动后,下次拉取即生效(权威源)。

> 评审修订:Claude 4 role **不**进只读层(仍由持久化默认提供、可编辑),避免破坏 role→actualModel 定制。

## 3. 数据模型

### 后端 `ModelConfig` record（`config/ModelConfig.java`）

新增字段：
- `boolean readOnly`（默认 `false`）

`readOnly=true` 即等价于"来自 CLI 配置文件"。不另设 `source` 字段（v1 冗余，YAGNI）。

**主要涟漪点**：构造器签名、`normalized()`、`ModelConfigValidator`、测试 fixture。所有现有构造调用补 `false` 默认。

### 序列化 JSON 形状（`SettingsHandler.serializeModelRegistry` / 前端 `parseModelRegistryPayload`）

每项增发：
```json
{ "id": "...", "provider": "...", ..., "readOnly": true }
```

### 去重规则

- 键:`provider:id`(provider 小写、id 剥容量后缀 `[1m]`/`[xx k|m]` 后小写)。
- **用户层优先,只读填补空缺**:只读 Codex 默认仅在用户层无同键项时追加;用户层已有同键 Codex 模型 → 保留用户的(可编辑),只读默认跳过并 `LOG.debug`。
- 目的:避免重复条目;磁盘 `model=` 在用户未配置同名 Codex 模型时以只读形式可见。

### reset 语义变化

`resetModelRegistry()` 删除持久化键 = **清空用户层**。只读默认照常由 `getModelRegistry()` 叠加 → 用户可见的 4 个 Claude role 仍在，语义从"持久化默认"转为"运行时只读默认"。无数据风险。

## 4. 后端改动

| 文件 / 方法 | 改动 |
|---|---|
| `config/ModelConfig.java` | 新增 `readOnly` 字段 + 构造器/`normalized()` 适配(无 `source` 字段,YAGNI)。 |
| 新增 `config/ReadOnlyDefaultModels.java` | `List<ModelConfig> compute()`:**仅 Codex** = `CodexConfigReader.readCurrentModel()` 解析结果,有则 1 项(`readOnly=true`、contextWindow 默认 200_000、supports1M=false);无 `model=` 或文件缺失返回空列表。**不含 Claude role**(评审修订)。 |
| 新增 `provider/codex/CodexConfigReader.java`（或扩 `EnvironmentConfigurator`） | 读 `~/.codex/config.toml`，正则提取 `^\s*model\s*=\s*["']([^"']+)["']`（同前端 `extractCodexCurrentModel`）。缺失/无匹配返回 `Optional.empty()`。 |
| `settings/CodemossSettingsService.getModelRegistry()`（:1683） | 读持久化用户层(缺失/无效回退 `getDefault()`=4 Claude role)后,叠加只读 Codex 默认(按去重规则)。**调用点不改名**（`ModelProviderHandler`、`SettingsHandler` 三处序列化自动拿到含只读默认的 registry）。 |
| `CodemossSettingsService.setModelRegistry()`（:1706） | 持久化前剥离 `readOnly==true` 项（防前端注入只读项落盘），仅存用户层。 |
| `CodemossSettingsService.parseModelRegistry()`（:1740）/ `SettingsHandler.parseModelRegistryFromJson()`（:561） | 入站强制 `readOnly=false`（后端权威，不接受前端标记）。 |
| `handler/SettingsHandler.serializeModelRegistry()`（:537） + `CodemossSettingsService.serializeModelRegistry()`（:1768） | 逐项增发 `readOnly`(两套序列化均改)。 |
| `config/ModelConfigValidator.java` | **无需改动**。校验仅作用于持久化用户层;Claude 4 role 恒在该层(`getDefault()` 兜底) → 非空与「至少一个启用」恒满足。只读默认由代码构造、不进持久化,不参与校验。 |

### 调用点零改动说明

`ModelProviderHandler.applyModelChange`（:126-136）与 `SettingsHandler` 三处序列化均调用 `getModelRegistry()`；改为内部合并后，它们自动拿到 effective registry，**无需改名或改签名**。Claude role 的 `find()` / `resolveModelSelection()` 因 effective registry 仍含 4 role 而行为不变。

## 5. 前端改动

| 文件 | 改动 |
|---|---|
| `utils/modelRegistry.ts` | `ModelRegistryItem` 增 `readOnly?: boolean`；`parseModelRegistryPayload` 读取该字段。 |
| `components/ChatInputBox/selectors/ModelSelect.tsx`（:134） | **防崩守卫**：`models` 为空时 `currentModel` 走安全占位分支，渲染"未配置模型"按钮 + tooltip，**绝不访问 `.id`**。无条件修复崩溃点。 |
| `components/settings/ModelRegistrySection/index.tsx` | 只读行（`readOnly===true`）：隐藏 Edit（`codicon-edit`）/ Delete（`codicon-trash`）按钮、隐藏 enabled 开关、加锁标注"只读 / 来自配置文件"。`removeModel` / `saveEditing` 提交 `set_model_registry` 时剥离只读项（双保险，配合后端剥离）。 |
| `components/ChatInputBox/ButtonArea.tsx`（:85-89） | Codex 分支保持现状——只读默认已由后端注入 registry，`getModelsForProvider('codex')` 自然含之；空时由 ModelSelect 守卫兜底。无需加 Codex 兜底常量。 |
| `components/ChatInputBox/types.ts` | `CODEX_MODELS = []` 保持不变（不重新引入硬编码，符合既有测试）。 |

## 6. 数据流

```
App 启动
  → 前端 requestModelRegistry()
  → 后端 handleGetModelRegistry()
  → serialize( getModelRegistry() = merge(只读默认, 持久化用户层) )   // 带 readOnly
  → dispatch model_registry

用户在设置页增删改
  → set_model_registry(前端已剥离只读项)
  → 后端 setModelRegistry 再剥离只读 + 校验 + 持久化用户层
  → 重新 getModelRegistry() 合并
  → dispatch model_registry_updated + model_registry

用户在磁盘手改 ~/.codex/config.toml 或 ~/.claude/settings.json
  → 下次 getModelRegistry() 现算只读默认 → 自动反映
```

## 7. 错误处理

- Codex `config.toml` 缺失 / 无 `model=`：无 Codex 只读默认，`LOG.debug`；UI 由 ModelSelect 守卫显示"未配置模型"。
- Claude `settings.json` 缺失：4 role 仍在（role 是常量，`actualModel` 留空，显示名走现有 `claude-model-mapping` 回退）。
- 合并 / 解析异常：吞异常 + `LOG.warn` + 继续返回用户层（或空 registry），**绝不抛到渲染层**。
- 前端收到无 `readOnly` 字段的旧 payload：按 `false` 处理（向后兼容）。

## 8. 已知交互（本次不重构）

Codex Provider 设置对话框（`useCodexProviderManagement` / `createCodexCatalogModels`）现有的 `customModels` / `modelCatalog` / `configToml` 同步机制（`syncCodexProviderCatalogToRegistry`）继续保留，用于用户在 Provider 对话框里策划的**可编辑** Codex 模型清单。

- 本次新增的只读默认（磁盘 `model=`）与该机制可能对同一模型产生重叠 → 由 §3 去重规则处理（只读优先）。
- 极端情况：用户既在磁盘 config.toml 设了 `model=`，又在 Provider 对话框 customModels 里加了同名模型 → 显示为只读（不可编辑）。本次接受此行为，未来可视反馈再细化。

## 9. 迁移

- 现有用户持久化 registry 中的 4 个 Claude role(来自旧 `getDefault()`)保持原样、仍可编辑(评审修订后不再收敛为只读)。无数据丢失、无行为回归。
- 持久化层无 Codex 模型 + 磁盘 `~/.codex/config.toml` 有 `model=` → 升级后 getModelRegistry 额外注入 1 个只读 Codex 默认。
- 旧前端收到带 `readOnly` 的新 payload:忽略该字段,行为退化为"可编辑"——仅影响 UI 操作权限,不影响功能。

## 10. 测试

### 后端
- `ReadOnlyDefaultModels.compute()`:**仅 Codex**:样例 config.toml 解析 1 项 `readOnly=true`;无 `model=` 或文件缺失返回空列表;不含 Claude role。
- `CodexConfigReader.readCurrentModel()`：匹配 `model = "x"`、`model='x'`、带注释/空白；缺失文件返回 empty。
- `CodemossSettingsService.getModelRegistry()`:用户层无该 Codex id 时注入只读默认(`readOnly=true`);用户层已有同 id → 保留用户项(用户优先),只读默认跳过;Claude 4 role 不受影响。
- `setModelRegistry()`：剥离 `readOnly=true` 不落盘；持久化用户层仅含可编辑项。
- `resetModelRegistry()`：清空用户层后 `getModelRegistry()` 仍返回 4 role(持久化默认,可编辑) + 只读 Codex 默认(若有)。
- `parseModelRegistryFromJson()`：入站 `readOnly` 被强制 `false`。

### 前端
- `ModelSelect`：`models=[]` 时渲染占位、不抛错（**崩溃回归测试**）；`models` 非空时行为不变。
- `ModelRegistrySection`：只读行无 Edit/Delete 按钮、无 enabled 开关、显示只读标注；`removeModel`/`saveEditing` 提交 payload 不含只读项。
- `parseModelRegistryPayload`：正确读取 `readOnly`；缺字段时默认 `false`。

### 权威门
现有 176 个 `.test.js` + 50 个 `.test.mjs` 保持全绿；`ModelSelect.test.tsx:140`（`CODEX_MODELS` 为空）继续通过。

## 11. 实施顺序（概要，详细计划交 writing-plans）

1. 后端 `ModelConfig` 加字段 + `ReadOnlyDefaultModels` + `CodexConfigReader` + 合并/剥离逻辑 + 序列化字段（含单测）。
2. 前端类型扩展 + `ModelSelect` 防崩守卫 + `ModelRegistrySection` 只读渲染（含单测）。
3. 端到端验证：未配置 Codex 不崩、配置后只读默认出现、磁盘改动生效、reset 行为正确。
