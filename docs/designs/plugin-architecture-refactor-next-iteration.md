# 插件架构重构下一阶段技术文档

## 文档目的

本文档用于承接已完成的第一阶段架构重构成果，并明确下一阶段重构方向、模块边界、设计原则、落地步骤和验收标准。

核心目标保持不变：

- 前端只负责渲染、采集用户意图和展示后端回显数据。
- 后端负责业务规则、校验、持久化、Provider 编排、模型能力计算和协议分发。
- 新功能通过接口、注册表、策略类、适配器扩展，避免继续堆叠 `switch` / `if`。
- 重复逻辑优先组件化、模块化、服务化，保留后续扩展接口。

## 当前重构状态

已完成第一阶段可维护切片：

- Java 协议枚举已作为 upstream action / downstream event 的单一来源。
- Java 已引入 `FrontendActionDispatcher` 和 `FrontendActionHandler`。
- 旧 `MessageHandler` 可通过 `LegacyMessageHandlerAdapter` 接入新 dispatcher。
- Java Provider 已引入 `ProviderId`、`ProviderAdapter`、`ProviderRegistry`。
- `SessionProviderRouter` 已通过 Provider registry 路由 Claude / Codex。
- 模型选择结果已由后端 `DefaultModelCapabilityResolver` 计算并下发 `model.selection`。
- 前端 model registry 已通过生成的 `UPSTREAM` / `DOWNSTREAM` 常量访问协议。
- 前端 model provider 展示状态已消费后端 `model.selection`。
- Node `ai-bridge` 已加入 provider command descriptor registry。

当前仍保留的兼容路径：

- 大量 Java handler 仍是 `MessageHandler + switch(type)`。
- `SettingsHandler` 内仍聚合 model registry、appearance、配置读写等多类业务。
- 前端仍有部分 `sendBridgeEvent()` 裸字符串调用。
- 前端 hook 中仍存在部分本地业务状态和兼容性 fallback。
- `ai-bridge` provider 内部仍有 provider-local command switch，后续可继续拆小。

## 下一阶段优先级

下一阶段优先重构 `SettingsHandler`，原因如下：

1. `SettingsHandler` 是前后端业务边界最典型的遗留入口。
2. model registry 和 appearance config 已具备协议常量基础，迁移风险较低。
3. 拆出 typed handler 后，可以验证 dispatcher 在真实业务 handler 中的使用方式。
4. model registry 是后续 Provider / Model ViewModel 后端化的基础。
5. settings 逻辑继续拆分后，后续配置 schema、校验、持久化可以自然服务化。

## 目标架构切片

### 迁移前

```text
webview
  -> sendBridgeEvent("get_model_registry")
  -> sendBridgeEvent("set_model_registry")
  -> sendBridgeEvent("set_appearance_config")

Java
  SettingsHandler
    getSupportedTypes()
    handle(type, content)
      switch(type)
        get_model_registry
        set_model_registry
        reset_model_registry
        get_model_registry_schema
        set_appearance_config
```

问题：

- 一个 handler 承担多个业务用例。
- action 与 payload 解析散落在 switch 分支中。
- 业务、协议、序列化、错误派发混在一个类里。
- 新增 settings action 时容易继续修改核心 switch，违反开闭原则。

### 迁移后

```text
webview
  -> sendAction(UPSTREAM.GET_MODEL_REGISTRY)
  -> sendAction(UPSTREAM.SET_MODEL_REGISTRY)
  -> sendAction(UPSTREAM.SET_APPEARANCE_CONFIG)

Java application
  FrontendActionDispatcher
    -> GetModelRegistryActionHandler
    -> SetModelRegistryActionHandler
    -> ResetModelRegistryActionHandler
    -> GetModelRegistrySchemaActionHandler
    -> SetAppearanceConfigActionHandler

Java domain/service
  ModelRegistryService
  AppearanceConfigService
  SettingsValidationService

Java infrastructure
  settings persistence
  notification / event dispatch
```

收益：

- 每个 action 一个 handler，职责更单一。
- dispatcher 只负责路由，不理解业务。
- service 负责业务规则和持久化编排。
- 后续新增 settings 功能只新增 handler / service，不改旧分支。
- 前端继续只渲染后端返回结果。

## 模块设计

### Action Handler 层

建议新增包：

```text
src/main/java/com/github/claudecodegui/handler/settings/
```

建议新增类：

- `GetModelRegistryActionHandler`
- `SetModelRegistryActionHandler`
- `ResetModelRegistryActionHandler`
- `GetModelRegistrySchemaActionHandler`
- `SetAppearanceConfigActionHandler`

职责：

- 声明唯一 `UpstreamAction`。
- 声明 payload 类型。
- 调用后端 service。
- 将 service 返回的 ViewModel / result 派发为 `DownstreamEvent`。
- 不直接包含复杂业务计算。

### Service 层

建议新增包：

```text
src/main/java/com/github/claudecodegui/settings/
```

建议新增类：

- `ModelRegistryService`
- `ModelRegistryResult`
- `ModelRegistrySchemaResult`
- `AppearanceConfigService`
- `AppearanceConfigRequest`
- `AppearanceConfigResult`

职责：

- 封装 model registry 的读取、写入、重置、schema 生成。
- 封装 appearance config 的保存、应用事件组装。
- 统一异常与校验结果。
- 保持对现有配置文件格式的兼容。

### 兼容层

`SettingsHandler` 暂不删除，下一阶段只迁移一组 action。

迁移策略：

- 已迁移 action 注册到 `FrontendActionDispatcher`。
- 未迁移 action 继续走 `LegacyMessageHandlerAdapter`。
- 旧 downstream event 继续保留，例如 `model_registry`、`model_registry_updated`、`model_registry_schema`。
- 前端可以逐步从 `sendBridgeEvent()` 切换到 `sendAction()`。

## 推荐实施顺序

### Task 1: 为 model registry action 增加 typed handler 测试

新增测试：

```text
src/test/java/com/github/claudecodegui/handler/settings/ModelRegistryActionHandlerTest.java
```

覆盖：

- `GET_MODEL_REGISTRY` 会读取后端 registry 并派发 `MODEL_REGISTRY`。
- `SET_MODEL_REGISTRY` 会校验 payload、保存配置并派发 `MODEL_REGISTRY_UPDATED`。
- `RESET_MODEL_REGISTRY` 会恢复默认配置并派发更新事件。
- `GET_MODEL_REGISTRY_SCHEMA` 会返回 schema。
- 异常时返回统一错误事件或兼容错误 payload。

### Task 2: 抽出 `ModelRegistryService`

从 `SettingsHandler` 迁出以下逻辑：

- `serializeModelRegistry`
- `parseModelRegistryFromJson`
- model registry read / write / reset
- model registry schema 组装
- model registry error payload 组装

要求：

- service 不依赖前端。
- service 不依赖具体 action 字符串。
- service 返回明确 result 对象。
- handler 只负责将 result 转换成 downstream event。

### Task 3: 接入 `FrontendActionDispatcher`

在 handler 注册入口中注册：

- `GetModelRegistryActionHandler`
- `SetModelRegistryActionHandler`
- `ResetModelRegistryActionHandler`
- `GetModelRegistrySchemaActionHandler`

要求：

- 与 `LegacyMessageHandlerAdapter` 并存。
- 不重复注册同一个 action。
- 如果 dispatcher 已处理 action，不再落到 legacy handler。

### Task 4: 迁移 appearance config

新增测试：

```text
src/test/java/com/github/claudecodegui/handler/settings/AppearanceConfigActionHandlerTest.java
```

覆盖：

- `SET_APPEARANCE_CONFIG` 会保存配置。
- 保存后下发 `APPEARANCE_APPLY`。
- payload 解析失败时返回统一错误。

建议抽出：

- `AppearanceConfigService`
- `AppearanceConfigRequest`
- `AppearanceConfigResult`

### Task 5: 更新前端调用入口

优先替换 settings 中的裸字符串：

- `get_model_registry`
- `set_model_registry`
- `reset_model_registry`
- `get_model_registry_schema`
- `set_appearance_config`

要求：

- 使用 `sendAction(UPSTREAM.*)`。
- 使用 `subscribeEvent(DOWNSTREAM.*)`。
- 前端不新增 model registry 业务校验，只做表单体验级校验。

## 开闭原则约束

新增 settings 业务时应遵循：

```text
新增 action
  -> UpstreamAction 增加协议值
  -> 新增 payload DTO
  -> 新增 FrontendActionHandler
  -> 新增或复用 service 方法
  -> 新增 downstream event / result DTO
  -> 生成 TypeScript 协议常量
```

禁止：

- 在 `SettingsHandler` 继续追加大型 switch 分支。
- 在 React component 中计算后端业务结果。
- 在多个 hook 中复制相同 Provider / Model 判断。
- 直接写裸字符串 action/event。

## 测试策略

### Java targeted tests

建议每完成一小步运行对应测试：

```bash
rtk .\gradlew.bat test --tests com.github.claudecodegui.handler.settings.ModelRegistryActionHandlerTest
rtk .\gradlew.bat test --tests com.github.claudecodegui.handler.settings.AppearanceConfigActionHandlerTest
rtk .\gradlew.bat test --tests com.github.claudecodegui.handler.core.FrontendActionDispatcherTest
```

阶段完成后运行：

```bash
rtk .\gradlew.bat test --tests com.github.claudecodegui.protocol.ProtocolEnumCoverageTest --tests com.github.claudecodegui.handler.core.FrontendActionDispatcherTest --tests com.github.claudecodegui.handler.settings.ModelRegistryActionHandlerTest --tests com.github.claudecodegui.handler.settings.AppearanceConfigActionHandlerTest
```

### Frontend targeted tests

建议运行：

```bash
cd webview
cmd /c node_modules\.bin\vitest.cmd run src/bridge/__tests__/typed.test.ts src/components/settings/ModelRegistrySection/index.test.tsx
```

### 验证重点

- 行为兼容：旧 event payload 不破坏现有前端。
- 协议一致：Java enum 与生成 TS 常量一致。
- 权责清晰：业务逻辑在 service，handler 只编排。
- 失败路径：payload 错误、配置保存失败、schema 生成失败都有明确事件。

## 风险与缓解

- 风险：dispatcher 与 legacy adapter 重复处理同一个 action。
  - 缓解：dispatcher 注册时检测重复 action，迁移 action 从 legacy 注册清单中排除或由入口优先 dispatcher。
- 风险：前端仍依赖旧 payload 字段。
  - 缓解：保留旧 downstream event 与字段，新增字段只做扩展不破坏。
- 风险：service 抽象过度。
  - 缓解：先抽 model registry 和 appearance 两个高价值场景，不一次性迁移所有 settings。
- 风险：全量测试存在历史失败影响判断。
  - 缓解：记录 targeted tests 作为本阶段验收证据，另开任务处理 broad baseline。

## 验收标准

- `SettingsHandler` 不再直接承载 model registry action 的业务实现。
- model registry 四个 action 均有独立 `FrontendActionHandler`。
- appearance config 至少迁移 `SET_APPEARANCE_CONFIG`。
- model registry 和 appearance 的业务规则位于后端 service。
- 前端 settings 调用使用生成协议常量。
- targeted Java / frontend tests 通过。
- 未迁移 settings action 仍通过 legacy adapter 正常工作。

## 后续阶段预留

完成本阶段后，建议继续：

1. 拆分 `ModelProviderHandler`，将 Provider / Model ViewModel 组装迁入后端 service。
2. 建立完整 `ModelProviderStateViewModel`，前端只渲染后端下发状态。
3. 将 `/new`、`/resume`、`/plan`、`/context` 等命令迁入后端 `CommandRouter`。
4. 清理前端 localStorage 中影响业务结果的状态。
5. 将 `ai-bridge` provider-local switch 继续拆为 command descriptor。
