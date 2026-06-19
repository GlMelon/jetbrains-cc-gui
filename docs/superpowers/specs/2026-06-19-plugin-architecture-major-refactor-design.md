# 插件前后端大重构设计

## 背景

当前插件由三层组成:

- `webview`: React/JCEF 前端,负责聊天、设置、历史、权限弹窗等界面。
- `src/main/java`: IntelliJ 插件后端,负责协议分发、会话、设置、Provider、文件/差异等能力。
- `ai-bridge`: Node 执行层,负责调用 Claude/Codex SDK 或 CLI。

现状中前端承担了部分业务职责:slash command 解析、Provider/Model 切换策略、上下文窗口推导、Provider preset、环境变量保护列表、本地业务状态持久化等。后端也有对应规则,导致前后端规则重复、协议字符串散落、Provider 扩展需要改多处 `switch`/`if`。

本次重构目标是建立长期可维护的架构:前端只显示与收集交互,后端成为唯一业务规则源,Provider/命令/运行时通过接口扩展。

## 目标

1. 前端只渲染后端下发的 ViewModel/schema/result,不再决定业务结果。
2. 后端统一处理命令、Provider、模型能力、权限、校验、持久化和协议编排。
3. Node `ai-bridge` 只做 SDK/CLI 执行适配,不承载跨 Provider 策略。
4. 新增 Provider、命令、运行时或配置项时,优先新增实现类/注册项,不改核心分发逻辑。
5. 协议事件和 payload 类型由 Java 单一数据源生成到 TypeScript,禁止新增裸字符串协议。
6. 迁移过程分阶段完成,每阶段都可编译、可测试、可回退。

## 非目标

- 不重写整套 UI 视觉。
- 不一次性删除所有 legacy callback,先通过兼容层迁移。
- 不改变用户现有配置文件格式,除非有迁移器和兼容读取。
- 不在第一阶段新增新 Provider,先用 Claude/Codex 验证抽象。

## 目标架构

### 分层

```text
webview
  React components
  View hooks
  generated protocol types
  bridge client

java application
  FrontendActionDispatcher
  use cases
  ViewModel assemblers

java domain
  ProviderAdapter
  CommandHandler
  ModelCapabilityResolver
  RuntimePolicyResolver
  Validation services

java infrastructure
  JetBrains API adapters
  settings persistence
  daemon bridge
  file system / clipboard / diff integrations

ai-bridge
  provider command adapters
  SDK/CLI process execution
  stream normalization
```

### 依赖方向

```text
webview -> generated protocol client -> java application -> domain interfaces
                                             |
                                             v
                                      infrastructure adapters
                                             |
                                             v
                                          ai-bridge
```

前端不直接推导业务规则。Java application 层把用户意图转换为 domain 请求,再把 domain 结果组装成前端可渲染的 ViewModel。

## 后端核心抽象

### FrontendActionHandler

替代 `SUPPORTED_TYPES + switch(type)`。

```java
public interface FrontendActionHandler<T> {
    UpstreamAction action();

    Class<T> payloadType();

    void handle(T payload, FrontendActionContext context);
}
```

注册方式:

- 每个 handler 只声明一个 action。
- dispatcher 启动时构建 `Map<UpstreamAction, FrontendActionHandler<?>>`。
- action 重复注册时启动失败,避免协议冲突。
- payload 解析失败时统一下发 typed error event。

### ProviderAdapter

借鉴 `zh-park-new` 的 `DockingAdapter + support()` 模式,替代 Java/Node 中到处 `if provider == codex else claude`。

```java
public interface ProviderAdapter {
    ProviderId providerId();

    boolean supports(ProviderId providerId);

    ProviderViewModel getViewModel(ProviderContext context);

    ModelSelectionResult resolveModel(ModelSelectionRequest request);

    RuntimeRequest buildRuntimeRequest(SessionContext session, UserMessage message);

    List<SlashCommandView> getSlashCommands(CommandContext context);

    void cleanup(SessionContext session);
}
```

首批实现:

- `ClaudeProviderAdapter`
- `CodexProviderAdapter`

后续新增 Provider 只新增 adapter 并注册,不修改主流程。

### CommandHandler

slash command 不再由前端判断。前端只发送用户输入,后端解析成命令或普通消息。

```java
public interface CommandHandler {
    boolean supports(CommandInput input, CommandContext context);

    CommandResult handle(CommandInput input, CommandContext context);
}
```

首批命令:

- `NewSessionCommand`
- `ResumeCommand`
- `PlanModeCommand`
- `ContextUsageCommand`
- `PlainMessageCommand`

命令结果统一为:

- `NavigateView`
- `UpdateSession`
- `OpenDialog`
- `SendMessage`
- `ShowToast`
- `Noop`

### ModelCapabilityResolver

模型上下文窗口、`supports1MContext`、role model 映射、provider preset 默认值由后端统一计算。

```java
public interface ModelCapabilityResolver {
    ModelSelectionResult resolve(ModelSelectionRequest request);
}
```

前端不再传 `contextWindow` 作为业务判断结果。前端可以传用户显式选择,但最终 effective model/context window 由后端返回。

### ValidationService

前端可以做体验级校验,但后端是唯一权威。所有影响安全/业务结果的校验都由后端执行,并返回字段级错误。

首批后端权威校验:

- Provider env var key/value
- protected env var
- model registry
- runtime policy
- file/diff mutating path
- browser URL scheme

## 协议设计

### 单一来源

Java 侧维护:

- `UpstreamAction`
- `DownstreamEvent`
- payload DTO
- `ProtocolManifestGenerator`

TypeScript 生成:

- action/event 常量
- payload 类型
- typed `sendAction()`
- typed `bridgeHub.subscribe()`

### 规则

1. 禁止新增裸字符串 action/event。
2. 后端 handler 只能通过 `UpstreamAction` / `DownstreamEvent` 发送。
3. 前端只能通过生成的 `UPSTREAM` / `DOWNSTREAM` 常量发送和订阅。
4. legacy `window.xxx` 只保留兼容转发,迁移一个删一个。
5. CI/测试中增加协议一致性检查:Java enum、manifest、generated TS 必须一致。

## 前端重构边界

前端保留:

- 控件渲染和布局。
- 用户输入采集。
- UI 暂态:输入框草稿、弹窗开关、滚动位置、搜索展开状态。
- 体验级校验:必填、长度、明显格式错误。

前端移除:

- slash command 业务解析。
- Provider/model/context window 业务推导。
- Provider preset 作为业务源。
- protected env var 作为权威规则。
- 影响会话结果的 localStorage 持久化。
- 与后端重复的安全校验作为唯一防线。

前端接收后端下发:

- `ProviderViewModel`
- `ModelSelectorViewModel`
- `SettingsSchema`
- `ValidationResult`
- `SessionRuntimeViewModel`
- `CommandResultEvent`

## Node ai-bridge 重构边界

Node 层保留:

- SDK/CLI 调用。
- provider-specific stream normalization。
- process/env/request options 组装中的执行细节。

Node 层移出:

- 跨 Provider 策略。
- UI 命令含义。
- 前端设置业务语义。

Node dispatcher 改造:

- 从 `switch(provider)` 迁移为 `providerCommandRegistry`。
- 每个 provider channel 导出统一 descriptor。
- daemon special command 也通过 descriptor 标注 `persistentOnly` / `passthrough`。

## 迁移阶段

### Phase 1: 协议收口

- 补齐 `UpstreamAction` / `DownstreamEvent` 缺失项,包括 model registry、appearance 等实际事件。
- 生成 TS 类型并替换前端裸字符串高频入口。
- 新增 `FrontendActionHandler` 和 map dispatcher。
- 保留旧 `MessageHandler` 适配器,避免一次性迁移所有 handler。
- 测试:协议生成测试、dispatcher 重复注册测试、legacy action 兼容测试。

### Phase 2: Provider 适配器化

- 新增 `ProviderAdapter`、`ProviderRegistry`。
- Claude/Codex 先包装现有实现,不改变行为。
- `SessionProviderRouter`、`ModelProviderHandler`、`CliSessionManager` 逐步改为查 registry。
- Node `ai-bridge` 增加 command descriptor registry。
- 测试:Claude/Codex 路由矩阵、未知 provider 错误、切换清理行为。

### Phase 3: 模型选择后端化

- 后端返回 `ModelSelectorViewModel`。
- 前端 `ModelSelect` 只渲染模型列表、标签、禁用态、上下文提示。
- 删除前端对 `contextWindow`、`supports1MContext`、provider preset 默认窗口的业务计算。
- 后端 `ModelCapabilityResolver` 统一输出 effective model/context window。
- 测试:role model、自定义模型、1M context、provider preset 兼容。

### Phase 4: 命令解析后端化

- 前端提交原始文本和附件元信息。
- 后端 `CommandRouter` 决定是本地命令还是普通消息。
- `/new`、`/resume`、`/plan`、`/context` 从前端逻辑迁出。
- loading queue 改为按后端返回的 command policy 判断是否排队/立即执行。
- 测试:loading 状态下命令、Codex 下 `/plan`、Claude CLI 下 `/context`。

### Phase 5: 前端业务状态瘦身

- 模型选择、权限模式、业务输入历史、文件变更处理状态迁到后端持久化。
- 前端 localStorage 只保留 UI 偏好和可丢弃草稿。
- 后端启动/切换会话时下发完整 ViewModel。
- 测试:刷新 webview、多 tab、清缓存、跨会话恢复。

### Phase 6: legacy 清理

- 删除已迁移 `window.xxx` callback。
- 删除旧裸字符串 helper。
- 删除重复校验/重复业务推导。
- 更新架构文档和贡献约束。
- 测试:全量前端测试、Java 单测、Gradle build。

## 风险与缓解

- 风险:一次性改动过大导致协议断裂。
  - 缓解:每个 phase 保留适配层,每阶段独立测试。
- 风险:前端行为改变影响用户习惯。
  - 缓解:先保持 ViewModel 字段兼容,UI 不做大改。
- 风险:Provider adapter 抽象过度。
  - 缓解:只抽当前重复点,Claude/Codex 两个实现先跑通。
- 风险:Node daemon 行为回归。
  - 缓解:provider command descriptor 先包装现有函数,再逐步内聚。
- 风险:localStorage 迁移丢状态。
  - 缓解:后端提供一次性导入/兼容读取,前端保留只读 fallback 一个版本。

## 验收标准

- 新增 Provider 不需要修改核心 dispatcher、session router、model resolver 主流程。
- 新增前端 action/event 必须先改 Java 协议定义并生成 TS。
- 前端不再计算 effective context window。
- 前端不再决定 slash command 的业务结果。
- 业务状态刷新后以后端返回为准。
- Java 和 webview 测试覆盖协议、Provider 路由、模型选择、命令解析。

## 第一批实施清单

1. 补齐协议枚举和生成物。
2. 新建 `FrontendActionHandler` / `FrontendActionDispatcher`。
3. 为旧 `MessageHandler` 做兼容 adapter。
4. 新建 `ProviderAdapter` / `ProviderRegistry`。
5. 先迁移 `SessionProviderRouter` 到 `ProviderRegistry`。
6. 新建 `ModelCapabilityResolver`,先让后端返回现有结果。
7. 前端 `useModelProviderState` 改为接收后端 `ModelSelectorViewModel`。
8. 将 `/new`、`/resume`、`/plan`、`/context` 迁入后端 `CommandRouter`。

