# 模型配置真实请求路由方案

## 背景

当前插件已经把 Claude 模型从具体版本模型逐步收敛到角色模型：

- `claude-role-sonnet`
- `claude-role-opus`
- `claude-role-fable`
- `claude-role-haiku`

同时，Codex 不再依赖插件内置的 GPT 具体版本列表，模型应由用户或 provider 配置提供。

但现有“模型配置”菜单仍主要承担前端模型目录作用：用户在菜单里新增 `glm5.2` 之后，聊天下拉可以显示这个模型，但真实请求仍可能由当前 provider 或本地 `~/.claude/settings.json` 中的 `ANTHROPIC_MODEL`、`ANTHROPIC_DEFAULT_*_MODEL` 决定。

这会导致一个不符合预期的行为：

```text
用户在模型配置中新增 glm5.2
  -> 聊天里看到/选择 glm5.2
  -> 当前 Claude provider 仍走小米 base URL 和 mimo-v2.5
  -> 实际请求没有按模型配置中的 glm5.2 走
```

目标是让“模型配置”不再只是展示目录，而是成为真实请求模型的来源之一。

## 目标

1. 模型配置中新增的模型必须能影响真实请求模型。
2. Claude 和 Codex 的新增表单按各自语义区分。
3. Claude 新增模型时只配置一个角色，不要求一次性填完整个 Sonnet/Opus/Fable/Haiku 映射。
4. Provider 管理仍负责 API key、base URL、provider 级默认映射。
5. 模型配置负责聊天下拉中的可选模型，以及该选项对应的真实请求模型。
6. Claude SDK 模式和 Claude CLI 模式都必须生效，不能只覆盖其中一条链路。
7. 不继续保留硬编码具体版本模型列表。
8. 不做旧具体模型兼容迁移。

## 非目标

1. 不修改 provider 的认证模型：API key、base URL 仍由 Provider 管理或本地 Claude/Codex 配置决定。
2. 不把“模型配置”直接写入 `~/.claude/settings.json` 作为默认行为。
3. 不保留原有 `CLAUDE_MODELS` / `CODEX_MODELS` 中写死的具体版本模型。
4. 不兼容旧 Claude 具体版本模型 ID，例如 `claude-sonnet-4-6`、`claude-opus-4-8`。
5. 不自动保留用户已经添加过的旧自定义模型配置；新的配置格式按当前方案重新保存。

## 核心原则

Provider 管连接，Model Registry 管选择。

- Provider 管理：
  - API key
  - base URL
  - 本地 Claude/Codex 配置入口
  - provider 级 role 默认映射

- 模型配置：
  - 聊天下拉里可选择的模型项
  - Claude 单个 role 对应的真实请求模型
  - Codex 真实请求模型
  - context window
  - 1M 支持
  - enabled 状态

## 数据结构

### Claude 模型配置项

Claude 模型配置项不再把 `id` 当作真实模型名。`id` 表示内部 role selector，`actualModel` 表示真实请求模型。

```json
{
  "id": "claude-role-sonnet",
  "provider": "claude",
  "role": "sonnet",
  "label": "GLM 5.2",
  "actualModel": "glm5.2",
  "description": "GLM 5.2 through current Claude-compatible provider",
  "contextWindow": 1000000,
  "supports1MContext": true,
  "enabled": true
}
```

字段说明：

| 字段 | 含义 |
| --- | --- |
| `provider` | 固定为 `claude` |
| `role` | `sonnet` / `opus` / `fable` / `haiku` |
| `id` | 由 `role` 派生，例如 `sonnet` 对应 `claude-role-sonnet` |
| `label` | 前端显示名 |
| `actualModel` | 真实请求模型名，例如 `glm5.2`、`mimo-v2.5` |
| `contextWindow` | 该模型的上下文窗口 |
| `supports1MContext` | 是否支持 1M 上下文 |
| `enabled` | 是否在下拉中可选 |

### Codex 模型配置项

Codex 没有 Claude 这类稳定角色层级，所以 `id` 就是真实请求模型。

```json
{
  "id": "glm5.2",
  "provider": "codex",
  "label": "GLM 5.2",
  "description": "GLM 5.2 through current OpenAI-compatible provider",
  "contextWindow": 1000000,
  "supports1MContext": true,
  "enabled": true
}
```

字段说明：

| 字段 | 含义 |
| --- | --- |
| `provider` | 固定为 `codex` |
| `id` | 真实请求模型名 |
| `label` | 前端显示名 |
| `contextWindow` | 该模型的上下文窗口 |
| `supports1MContext` | 是否支持 1M 上下文 |
| `enabled` | 是否在下拉中可选 |

## UI 设计

### 模型配置菜单

模型配置菜单的新增表单按 provider 分支展示。

#### Claude 新增表单

Claude 表单字段：

| 字段 | 控件 | 说明 |
| --- | --- | --- |
| Provider | segmented/select | `claude` |
| Role | 下拉 | `Sonnet` / `Opus` / `Fable` / `Haiku` |
| Display Name | 输入框 | 下拉显示名，例如 `GLM 5.2` |
| Actual Request Model | 输入框 | 真实请求模型，例如 `glm5.2` |
| Context Window | 数字输入 | 默认 `200000` |
| Supports 1M | checkbox/toggle | 勾选后可使用 1M |
| Enabled | checkbox/toggle | 控制是否显示在聊天下拉中 |

Claude 新增一次只填写一个 role 对应的真实模型。例如：

```text
Role: Sonnet
Display Name: GLM 5.2
Actual Request Model: glm5.2
Context Window: 1000000
Supports 1M: true
```

#### Codex 新增表单

Codex 表单字段：

| 字段 | 控件 | 说明 |
| --- | --- | --- |
| Provider | segmented/select | `codex` |
| Model ID | 输入框 | 真实请求模型，例如 `glm5.2` |
| Display Name | 输入框 | 下拉显示名 |
| Context Window | 数字输入 | 默认 `200000` |
| Supports 1M | checkbox/toggle | 勾选后可使用 1M |
| Enabled | checkbox/toggle | 控制是否显示在聊天下拉中 |

### 聊天模型下拉

Claude 下拉展示模型配置项，而不是只展示固定四个 role。

示例：

```text
GLM 5.2
Sonnet · glm5.2

MiMo Pro
Opus · mimo-v2.5-pro
```

点击 `GLM 5.2` 后，前端向后端发送：

```json
{
  "model": "claude-role-sonnet",
  "actualModel": "glm5.2",
  "contextWindow": 1000000
}
```

如果为了保持 `set_session_model` payload 简洁，也可以只发送：

```json
{
  "model": "claude-role-sonnet",
  "contextWindow": 1000000
}
```

然后由 Java 后端从 `ModelRegistryConfig` 中根据 `provider + id` 查出 `actualModel`。推荐使用后者，避免前端和后端各自持有一份实际模型解析逻辑。

## 请求路由

### Claude 总体路由

Claude 的真实请求模型解析优先级：

1. 当前模型配置项的 `actualModel`
2. 当前 provider / 本地 Claude settings 中的 role 映射
3. `ANTHROPIC_MODEL`
4. 原始 role id

也就是说，模型配置中的 `actualModel` 优先级高于 `~/.claude/settings.json` 里的 `ANTHROPIC_DEFAULT_*_MODEL`。

### Claude SDK 模式

当前 SDK 模式链路：

```text
SessionRequest.model
  -> ClaudeSDKBridge
  -> ai-bridge daemon / per-process
  -> message-sender.js
  -> mapModelIdToSdkName(model)
  -> resolveModelFromSettings(model, settings.env)
  -> setModelEnvironmentVariables(resolvedModel, model)
  -> SDK query options.model
```

调整后：

```text
SessionRequest.model = claude-role-sonnet
  -> Java 根据 ModelRegistryConfig 解析 actualModel = glm5.2
  -> SDK 请求仍使用 role 决定 short model: sonnet
  -> Node resolveModelFromSettings 优先使用 request actualModel
  -> setModelEnvironmentVariables("glm5.2", "claude-role-sonnet")
  -> SDK 实际请求 glm5.2
```

SDK 模式需要保证两个值同时存在：

- role selector：用于 `mapModelIdToSdkName()` 得到 `sonnet` / `opus` / `haiku`
- actual model：用于设置 `ANTHROPIC_DEFAULT_*_MODEL` 和 `ANTHROPIC_MODEL`

### Claude CLI 模式

当前 CLI 模式链路：

```text
SessionRequest.model
  -> ClaudeCliSession
  -> ClaudeCliModelResolver.resolveProfile(model)
  -> ClaudeCliModelResolver.resolveMapped(model, env)
  -> claude --model <resolvedModel>
```

调整后：

```text
SessionRequest.model = claude-role-sonnet
  -> Java 根据 ModelRegistryConfig 解析 actualModel = glm5.2
  -> ClaudeCliModelResolver 优先使用 actualModel
  -> claude --model glm5.2
```

CLI 模式不经过 Node `resolveModelFromSettings()`，因此必须在 Java CLI 链路中实现同样的优先级。

### Codex SDK / CLI 模式

Codex 没有 role 映射，模型配置中的 `id` 就是真实请求模型。

```text
用户选择 glm5.2
  -> SessionRequest.model = glm5.2
  -> Codex SDK/CLI 直接使用 glm5.2
```

## 后端设计

### Java 侧统一解析

新增或扩展一个模型解析函数：

```java
ResolvedModelSelection resolveModelSelection(String provider, String selectedModel)
```

返回：

```java
record ResolvedModelSelection(
    String selectedModel,
    String role,
    String actualModel,
    int contextWindow,
    boolean supports1MContext
) {}
```

Claude 示例：

```text
selectedModel = claude-role-sonnet
role = sonnet
actualModel = glm5.2
```

Codex 示例：

```text
selectedModel = glm5.2
role = null
actualModel = glm5.2
```

### SessionRequest 扩展

`SessionRequest` 需要能携带真实模型信息，避免 SDK/CLI 两条链路重复查 registry。

建议新增字段：

```java
String actualModel
```

必要时也可以新增：

```java
String modelRole
```

Claude SDK runtime 使用：

- `model`: `claude-role-sonnet`
- `actualModel`: `glm5.2`

Claude CLI runtime 使用：

- `model`: `claude-role-sonnet`
- `actualModel`: `glm5.2`

Codex 使用：

- `model`: `glm5.2`
- `actualModel`: `glm5.2`

### Node ai-bridge 扩展

Claude SDK Node 请求参数增加：

```json
{
  "model": "claude-role-sonnet",
  "actualModel": "glm5.2"
}
```

`resolveModelFromSettings()` 调整为：

```text
if actualModel is not blank:
  return actualModel with request-owned [1m] suffix handling
else:
  fallback to settings env mapping
```

这样 SDK 模式不会被本地 `~/.claude/settings.json` 中的 `ANTHROPIC_MODEL=mimo-v2.5` 覆盖。

### Claude CLI Resolver 扩展

`ClaudeCliModelResolver.resolveProfile()` 增加重载：

```java
resolveProfile(String selectedModel, String actualModel)
```

解析优先级：

1. `actualModel`
2. `ANTHROPIC_DEFAULT_*_MODEL`
3. `ANTHROPIC_MODEL`
4. `selectedModel`

能力判断仍可基于：

- role selector
- resolved actual model
- capability env override

## 配置持久化

### 默认 registry

默认 registry 只保留 Claude role 项，Codex 默认为空。

Claude 默认项可以不设置 `actualModel`，表示 fallback 到 provider 或本地 Claude settings。

```json
{
  "id": "claude-role-sonnet",
  "provider": "claude",
  "role": "sonnet",
  "label": "Sonnet",
  "actualModel": null,
  "contextWindow": 200000,
  "supports1MContext": true,
  "enabled": true
}
```

### 用户新增 registry

用户新增 Claude 项后，如果同一个 role 已存在，应有明确策略。

推荐策略：

1. 允许同一个 role 存在多个配置项。
2. 为每个配置项生成稳定唯一 `id`，例如：

```text
claude-role-sonnet:glm5.2
```

3. 发送时同时携带：

```json
{
  "model": "claude-role-sonnet:glm5.2",
  "roleModel": "claude-role-sonnet"
}
```

但这会扩大改动面。

为了第一阶段实现更稳，推荐采用更简单策略：

1. 每个 role 只保留一个模型配置项。
2. 新增同 role 时提示覆盖或编辑已有项。
3. `id` 固定为 `claude-role-sonnet` 这类 role id。

这个策略与当前 role selector 代码更贴合，改动面更小。

## 与 Provider 管理的关系

Provider 管理 UI 仍保留一次性填写完整 role 映射的能力：

| Role | Display Name | Actual Request Model |
| --- | --- | --- |
| Sonnet | `mimo` | `mimo-v2.5` |
| Opus | `mimo pro` | `mimo-v2.5-pro` |
| Fable | `fable` | `claude-fable-5` |
| Haiku | `fast` | `mimo-v2.5-fast` |

模型配置菜单只配置一个 role：

| Role | Display Name | Actual Request Model |
| --- | --- | --- |
| Sonnet | `GLM 5.2` | `glm5.2` |

优先级：

```text
模型配置 actualModel > provider role 映射 > ANTHROPIC_MODEL > role id
```

## 主要影响文件

### 前端

- `webview/src/utils/modelRegistry.ts`
  - `ModelRegistryItem` 增加 `role`、`actualModel`
  - 解析和序列化支持 Claude/Codex 不同字段
  - 默认 registry 不包含硬编码具体模型

- `webview/src/components/settings/ModelRegistrySection/index.tsx`
  - 新增表单按 Claude/Codex 分支展示
  - Claude 使用 role 下拉和 Actual Request Model
  - Codex 使用 Model ID

- `webview/src/components/ChatInputBox/selectors/ModelSelect.tsx`
  - Claude 显示 `label`、`role`、`actualModel`
  - 选中时传 role model id

- `webview/src/hooks/useModelProviderState.ts`
  - `set_session_model` payload 保持 role id
  - contextWindow 从 registry 获取

### Java

- `src/main/java/com/github/claudecodegui/config/ModelConfig.java`
  - 增加 `role`、`actualModel`

- `src/main/java/com/github/claudecodegui/config/ModelRegistryConfig.java`
  - 支持按 provider + role/id 查找实际模型

- `src/main/java/com/github/claudecodegui/handler/provider/ModelProviderHandler.java`
  - `set_session_model` 时解析 registry 中的 `actualModel`
  - context window 使用实际配置

- `src/main/java/com/github/claudecodegui/session/runtime/SessionRequest.java`
  - 增加 `actualModel`，必要时增加 `modelRole`

- `src/main/java/com/github/claudecodegui/session/SessionSendService.java`
  - 构造 `SessionRequest` 前统一解析 selected model

- `src/main/java/com/github/claudecodegui/provider/claude/ClaudeRequestParamsBuilder.java`
  - SDK Node params 增加 `actualModel`

- `src/main/java/com/github/claudecodegui/cli/claude/ClaudeCliModelResolver.java`
  - CLI 模式优先使用 registry actual model

- `src/main/java/com/github/claudecodegui/cli/claude/ClaudeCliSession.java`
  - 调用 `resolveProfile(model, actualModel)`

### Node ai-bridge

- `ai-bridge/utils/model-utils.js`
  - `resolveModelFromSettings(modelId, userEnv, actualModel)` 支持 actualModel 优先

- `ai-bridge/services/claude/message-sender.js`
  - 从 params 读取 `actualModel`
  - `setModelEnvironmentVariables(resolvedModel, model)` 保持 role selector 作为第二参数

## 测试计划

### 前端模型配置

- Claude 新增表单显示 role 下拉和 actual model 输入框。
- Codex 新增表单显示 model id 输入框，不显示 role 下拉。
- Claude 保存后 registry 包含 `role` 和 `actualModel`。
- Codex 保存后 `id` 就是真实模型。
- 禁止或覆盖同 role 的重复 Claude 配置。

### Claude SDK 模式

- registry 中 `claude-role-sonnet.actualModel=glm5.2`。
- 选择 Sonnet 后，Node 收到：

```json
{
  "model": "claude-role-sonnet",
  "actualModel": "glm5.2"
}
```

- `resolveModelFromSettings("claude-role-sonnet", env, "glm5.2")` 返回 `glm5.2`。
- 即使 env 中有 `ANTHROPIC_MODEL=mimo-v2.5`，仍返回 `glm5.2`。
- `setModelEnvironmentVariables("glm5.2", "claude-role-sonnet")` 设置 Sonnet 桶位。

### Claude CLI 模式

- registry 中 `claude-role-sonnet.actualModel=glm5.2`。
- `ClaudeCliModelResolver.resolveProfile("claude-role-sonnet", "glm5.2")` 返回 `glm5.2`。
- `ClaudeCliSession.buildCommand()` 包含：

```text
--model glm5.2
```

- 即使本地 env 中有 `ANTHROPIC_MODEL=mimo-v2.5`，模型配置 actualModel 仍优先。

### Codex

- 选择 Codex `glm5.2` 后，SDK/CLI 均直接使用 `glm5.2`。
- 不依赖 `CODEX_MODELS` 硬编码列表。

### 回归验证命令

```powershell
rtk cmd /c node_modules\.bin\vitest.cmd run webview/src/utils/modelRegistry.test.ts webview/src/components/settings/ModelRegistrySection/index.test.tsx webview/src/components/ChatInputBox/selectors/ModelSelect.test.tsx
rtk node --test ai-bridge\utils\model-utils.test.mjs
rtk .\gradlew.bat test --tests com.github.claudecodegui.cli.claude.ClaudeCliModelResolverTest --tests com.github.claudecodegui.handler.provider.ModelProviderHandlerTest --tests com.github.claudecodegui.settings.CodemossSettingsServiceModelRegistryTest
```

## 风险

### SDK/CLI 行为不一致

风险：只改 Node SDK 解析，CLI 仍按 `~/.claude/settings.json` 走。

处理：Java 侧统一解析 `actualModel`，并把结果传入 SDK/CLI 两条链路。

### role 和 actual model 混淆

风险：把 `glm5.2` 直接传给 SDK `model`，导致 SDK 桶位默认 Sonnet，Opus/Haiku 能力判断错误。

处理：保留 role selector，actual model 单独传递。

### 同 role 多配置项

风险：多个 Sonnet 配置项都使用 `claude-role-sonnet` 作为 id，无法区分。

处理：第一阶段限制同 role 唯一。后续如果需要多配置项，再引入稳定复合 id。

### 本地 settings 中 `ANTHROPIC_MODEL` 抢占

风险：用户配置了 `actualModel=glm5.2`，但本地 settings 的 `ANTHROPIC_MODEL=mimo-v2.5` 仍覆盖。

处理：actualModel 优先级高于 settings env。

## 建议结论

采用“模型配置 actualModel 优先”的方案：

- Claude：模型配置项 = role selector + actual request model。
- Codex：模型配置项 = 真实 request model。
- SDK 和 CLI 共享同一解析结果。
- Provider 管连接信息和默认映射，模型配置管聊天可选模型和请求模型覆盖。

这样用户在模型配置菜单中配置 `glm5.2` 后，只要选择该模型，无论当前是 Claude SDK 模式还是 Claude CLI 模式，都应该真实请求 `glm5.2`。
