# Claude / Codex 模型配置角色化方案

## 背景

当前插件在前端内置了大量具体模型 ID，例如：

- Claude: `claude-sonnet-4-6`、`claude-opus-4-8`、`claude-haiku-4-5`
- Codex: `gpt-5.5`、`gpt-5.4`、`gpt-5.2-codex`

这会带来两个问题：

1. 模型版本变化后，插件需要频繁新增、发布、同步内置列表。
2. 对第三方 API 或自定义 provider 来说，用户真正关心的是“Sonnet/Opus/Haiku 这个角色应该请求哪个实际模型”，而不是插件内置的 Claude 官方版本号。

参考 `cc-switch` 的设计，Claude 更适合抽象成固定角色层级，Codex 更适合由 provider 自己维护模型目录。

## 目标

将模型配置从“插件内置具体版本模型 ID”调整为：

- Claude 使用稳定角色 ID：`Sonnet`、`Opus`、`Fable`、`Haiku`
- Codex 使用 provider 的模型目录或当前配置模型，不再依赖插件内置 GPT 版本清单
- 保留旧配置兼容，避免现有会话、设置、历史配置失效

## 非目标

- 不在本次方案中重做 provider 管理整体 UI。
- 不强制删除用户已经添加的自定义模型。
- 不改变 Claude / Codex SDK 的调用方式，只改变插件内部模型选择和映射层。

## Claude 设计

### 角色 ID

前端下拉展示固定角色：

| 展示名 | 内部 ID | 映射 env |
| --- | --- | --- |
| Sonnet | `claude-role-sonnet` | `ANTHROPIC_DEFAULT_SONNET_MODEL` |
| Opus | `claude-role-opus` | `ANTHROPIC_DEFAULT_OPUS_MODEL` |
| Fable | `claude-role-fable` | `ANTHROPIC_DEFAULT_FABLE_MODEL` |
| Haiku | `claude-role-haiku` | `ANTHROPIC_DEFAULT_HAIKU_MODEL` |

不建议直接把内部 ID 写成裸 `Sonnet` / `Opus`，因为裸字符串可能和真实 provider 模型名、展示名或历史数据冲突。

### 后端解析规则

`resolveModelFromSettings(modelId, env)` 改为优先识别角色 ID：

1. `claude-role-fable`
   - 优先 `ANTHROPIC_DEFAULT_FABLE_MODEL`
   - 未配置时 fallback 到 `ANTHROPIC_DEFAULT_OPUS_MODEL`
   - 再 fallback 到 `ANTHROPIC_MODEL`
2. `claude-role-opus`
   - `ANTHROPIC_DEFAULT_OPUS_MODEL`
   - `ANTHROPIC_MODEL`
3. `claude-role-sonnet`
   - `ANTHROPIC_DEFAULT_SONNET_MODEL`
   - `ANTHROPIC_MODEL`
4. `claude-role-haiku`
   - `ANTHROPIC_DEFAULT_HAIKU_MODEL`
   - `ANTHROPIC_MODEL`
5. 非角色 ID
   - 认为是显式自定义模型，默认不被 `ANTHROPIC_MODEL` 或角色映射覆盖。

### 旧模型兼容

旧值归一化规则：

| 旧值模式 | 新 ID |
| --- | --- |
| 包含 `sonnet` | `claude-role-sonnet` |
| 包含 `opus` | `claude-role-opus` |
| 包含 `fable` | `claude-role-fable` |
| 包含 `haiku` | `claude-role-haiku` |
| 其他自定义 Claude 模型名 | 原样保留 |

这层兼容应放在公共工具函数里，例如：

- 前端：`normalizeClaudeRoleModelId(modelId)`
- 后端：`getClaudeRoleFromModelId(modelId)`

这样旧会话中的 `claude-sonnet-4-6` 仍能映射到 Sonnet 角色。

## Claude UI 设计

模型下拉：

- 默认只显示 `Sonnet`、`Opus`、`Fable`、`Haiku`
- 如果对应角色配置了实际请求模型，则副标题或标签显示实际模型，例如：
  - `Sonnet · glm5.2`
  - `Opus · mimo-v2.5-pro`

Provider 配置表单：

| Role | Display Name | Actual Request Model |
| --- | --- | --- |
| Sonnet | `glm5.2` | `glm5.2` |
| Opus | `mimo pro` | `mimo-v2.5-pro` |
| Fable | `fable` | `claude-fable-5` |
| Haiku | `fast` | `glm5.2-flash` |

保存到 settings/env：

```json
{
  "env": {
    "ANTHROPIC_DEFAULT_SONNET_MODEL_NAME": "glm5.2",
    "ANTHROPIC_DEFAULT_SONNET_MODEL": "glm5.2",
    "ANTHROPIC_DEFAULT_OPUS_MODEL_NAME": "mimo pro",
    "ANTHROPIC_DEFAULT_OPUS_MODEL": "mimo-v2.5-pro",
    "ANTHROPIC_DEFAULT_FABLE_MODEL_NAME": "fable",
    "ANTHROPIC_DEFAULT_FABLE_MODEL": "claude-fable-5",
    "ANTHROPIC_DEFAULT_HAIKU_MODEL_NAME": "fast",
    "ANTHROPIC_DEFAULT_HAIKU_MODEL": "glm5.2-flash"
  }
}
```

## Codex 设计

Codex 不建议抽象成固定 `Sonnet/Opus` 这类角色，因为 Codex/OpenAI-compatible provider 通常没有稳定角色层级。更适合采用 provider model catalog：

1. 优先读取当前 Codex provider 的 `config.toml` 中的 `model`。
2. 如果 provider 配置里有 `modelCatalog`，下拉显示 catalog。
3. 如果 provider 没有 catalog，下拉至少显示当前 `model`。
4. 用户可以手动新增模型，或通过 `/models` 拉取模型列表写入 catalog。
5. 插件不再内置 `gpt-5.5`、`gpt-5.4` 等作为长期默认列表。

推荐 catalog 数据结构：

```json
[
  {
    "model": "mimo-v2.5",
    "displayName": "MiMo v2.5",
    "contextWindow": 262144
  },
  {
    "model": "glm-5.2",
    "displayName": "GLM 5.2",
    "contextWindow": 200000
  }
]
```

Codex 下拉中的选中值直接是 catalog 里的 `model`，因为 Codex 没有 Claude 那种 SDK 角色 env 映射。

## 数据流

### Claude

```text
用户选择 Sonnet
  -> 前端保存/发送 claude-role-sonnet
  -> 后端读取 settings.env
  -> resolveModelFromSettings("claude-role-sonnet", env)
  -> 得到 ANTHROPIC_DEFAULT_SONNET_MODEL 对应的真实模型
  -> SDK/API 请求真实模型
```

### Codex

```text
用户选择 catalog 中的模型
  -> 前端发送真实 model，例如 mimo-v2.5
  -> 后端 Codex thread options 使用该 model
  -> SDK/API 请求该 model
```

## 迁移策略

### 第一阶段：引入角色 ID 和兼容层

- 新增 Claude role model 常量。
- 修改 Claude 下拉默认模型。
- 后端支持 `claude-role-*`。
- 旧 `claude-sonnet-*` 等仍可解析。
- 保留当前模型注册表，不立即删除，降低风险。

### 第二阶段：Provider 表单对齐角色映射

- Claude provider 表单增加/完善 `Fable` 行。
- `Display Name` 与 `Actual Request Model` 分开保存。
- 本地 `settings.json` / provider env 写入 `ANTHROPIC_DEFAULT_*_MODEL`。

### 第三阶段：Codex catalog 化

- Codex provider 配置支持 `modelCatalog`。
- 下拉模型从当前 provider catalog 读取。
- 无 catalog 时只显示当前配置模型。
- `/models` 拉取结果可写入 catalog。

### 第四阶段：清理硬编码列表

- 移除或降级 `CLAUDE_MODELS` / `CODEX_MODELS` 中具体版本模型的默认用途。
- 只保留兼容映射、测试样例或 fallback。
- 更新 prompt enhancer、commit AI 等使用模型配置的地方，避免继续默认写死 `gpt-5.5`、`claude-sonnet-4-6`。

## 主要影响文件

### 前端

- `webview/src/components/ChatInputBox/types.ts`
  - 定义 Claude role models。
  - 添加旧 ID 归一化函数。
  - 调整默认模型。

- `webview/src/components/ChatInputBox/ButtonArea.tsx`
  - Claude 模型列表改为 role models。
  - 不再依赖具体 Claude 版本 ID 映射。

- `webview/src/components/ChatInputBox/selectors/ModelSelect.tsx`
  - 展示角色名和实际请求模型。
  - 支持 Codex catalog 模型。

- `webview/src/utils/modelRegistry.ts`
  - 调整默认 registry。
  - Codex 优先使用 provider catalog/current model。

- `webview/src/components/ProviderDialog.tsx`
  - Claude 增加 Fable role。
  - 明确展示名和实际请求模型。

- `webview/src/components/CodexProviderDialog.tsx`
  - 支持 `modelCatalog` 编辑/读取。

### 后端

- `ai-bridge/utils/model-utils.js`
  - 支持 `claude-role-*`。
  - 添加 Fable fallback。
  - 保留旧模型 ID 兼容。

- `ai-bridge/config/api-config.js`
  - 确认 webview controlled settings override 不再依赖具体 Claude 版本。

- `ai-bridge/services/codex/message-service.js`
  - 确认 Codex model 直接使用 catalog 真实 model。

### 配置和默认值

- `webview/src/types/aiFeatureConfig.ts`
- `ai-bridge/services/prompt-enhancer.js`
- commit AI / prompt enhancer 相关设置

这些地方应从写死模型改为：

- Claude 默认 `claude-role-sonnet`
- Codex 默认读取当前 provider model，无法读取时再使用空值或 provider fallback

## 测试计划

### Claude 后端解析

新增或调整 `ai-bridge/utils/model-utils.test.mjs`：

- `claude-role-sonnet` 解析到 `ANTHROPIC_DEFAULT_SONNET_MODEL`
- `claude-role-opus` 解析到 `ANTHROPIC_DEFAULT_OPUS_MODEL`
- `claude-role-haiku` 解析到 `ANTHROPIC_DEFAULT_HAIKU_MODEL`
- `claude-role-fable` 优先 Fable，未配置时 fallback Opus
- 旧 `claude-sonnet-4-6` 仍解析为 Sonnet role
- 显式自定义模型 `glm5.2` 不被全局 env 覆盖

### Claude 前端选择

新增或调整：

- `ButtonArea.test.tsx`
- `ModelSelect.test.tsx`

覆盖：

- 下拉显示 `Sonnet / Opus / Fable / Haiku`
- 配置了 `ANTHROPIC_DEFAULT_SONNET_MODEL=glm5.2` 时显示 `Sonnet · glm5.2`
- 点击 Sonnet 发送 `claude-role-sonnet`
- 旧选中值 `claude-sonnet-4-6` 能归一显示为 Sonnet

### Codex catalog

新增或调整：

- `modelRegistry.test.ts`
- Codex provider dialog 测试

覆盖：

- provider 有 catalog 时下拉使用 catalog。
- provider 无 catalog 时至少显示当前配置 model。
- 新增/拉取模型写入 catalog。
- 不再要求默认列表包含 `gpt-5.5`。

### 回归验证

推荐命令：

```powershell
rtk node --test ai-bridge\utils\model-utils.test.mjs
rtk cmd /c node_modules\.bin\vitest.cmd run src/components/ChatInputBox/ButtonArea.test.tsx src/components/ChatInputBox/selectors/ModelSelect.test.tsx src/utils/modelRegistry.test.ts
rtk cmd /c node_modules\.bin\vitest.cmd run src/components/ProviderDialog.test.tsx
```

## 风险和处理

### 风险：历史会话保存的是旧具体模型 ID

处理：所有读取入口先归一化旧 ID，不直接要求用户迁移。

### 风险：某些逻辑依赖具体 Claude 版本判断能力

例如 reasoning effort、1M context、vision。

处理：改成按 role 或 provider 配置判断，不再按版本字符串判断。

### 风险：Codex 官方 provider 没有 catalog

处理：官方 provider 使用当前 `config.toml` 的 `model`；如果为空，UI 提示未配置，而不是内置猜测最新 GPT 模型。

### 风险：用户已有自定义模型列表

处理：不删除旧自定义模型，迁移后作为 provider catalog 或自定义真实模型保留。

## 建议结论

建议采用：

- Claude：固定 role selector + settings/env 角色映射
- Codex：provider model catalog + 当前配置模型
- 旧模型 ID：只做兼容层，不再作为主数据模型

这样后续新模型发布时，用户只需要更新 provider 配置或拉取 `/models`，插件不需要为了模型 ID 频繁发布版本。
