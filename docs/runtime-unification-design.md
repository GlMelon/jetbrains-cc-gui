# SDK/CLI Runtime 入口统一化与路由策略配置化 — 设计文档

| 项目 | 内容 |
|---|---|
| 日期 | 2026-06-17 |
| 状态 | 已批准（待实施） |
| 分支 | refactor/v0.4.6-deprecated-cleanup |
| 范围 | runtime 入口统一化（范围 B）+ 路由策略配置化（A 类硬编码） |
| 参考 | `D:\project\zh-park-new\...\module\work`（多态/抽象/模板方法/策略注册风格） |
| 关联 | 下行总线归一化重构（独立，已于阶段 0 提交至本分支） |

---

## 1. 背景与动机

插件后端当前区分 **SDK 模式**（通过 Node.js daemon/ai-bridge 与 Claude/Codex 交互）与 **CLI 模式**（一次性启动 `claude -p` / `codex exec` 子进程）。两者构成 `provider（claude/codex）× runtime（sdk/cli）` 的二维组合，但代码层面**入口未统一、抽象不对称、路由硬编码分散**，导致维护与扩展困难。

本设计参考 `work` 模块的面向对象风格（泛型入口接口 + 模板方法抽象基类 + `support()` 策略 + 容器注册路由 + 枚举路由键），在不引入 Spring 的前提下（本项目为 IntelliJ 插件），用**手动注册表 + 枚举路由**实现等效的"入口统一化、具体实现交由各自实现类"。

## 2. 目标与非目标

### 目标
1. **入口统一化**：`SessionRuntimeRouter` 收敛为单一 `send(SessionRequest, MessageCallback)`，按 `(ProviderType, RuntimeType)` 路由到 4 个实现类之一；删除现有 `sendClaude`/`sendCodex` 双方法 + if/else。
2. **runtime 层抽象**：新增 `SessionRuntime` 统一契约接口，SDK/CLI 各 2 个共 4 个实现，各自走自己的实现链、互不影响。
3. **CLI 侧建立多态**：新增 `CliSession` 接口，`ClaudeCliSession`/`CodexCliSession` 实现之，`CliSessionManager` 面向接口（选项 2，彻底）。
4. **路由策略配置化**：把"Codex 永远 CLI"等 A 类路由硬编码，变成 `config.json` 里用户可改的 provider×runtime 矩阵，初始默认严格等于当前行为（零行为变化）。
5. **配置防错**：配置校验层拒绝错误配置，防止坏配置影响插件运行；前端仅作入口，CRUD/校验/持久化统一在后端。

### 非目标（明确不做）
- **不改回调双轨**：`MessageCallback`（SDK）与 `CliSessionCallback`（CLI）暂不收敛（属于范围 C，后续）。
- **不做 SDK↔CLI 自动降级**：模式由 effective runtime 决定，不自动切换。
- **不动模型配置（B 类）**：模型清单/能力表/1M 规则/散落 `DEFAULT_MODEL` 的配置化，作为**独立的"模型配置中心" spec**，本次不实施（见第 11 节）。
- **不动下行 normalizer 骨架**：`session/normalize/` 由并行进行的下行总线重构负责。

## 3. 现状分析（痛点清单）

| # | 痛点 | 现状位置 |
|---|---|---|
| P1 | CLI 侧两 session 无接口 | `ClaudeCliSession`/`CodexCliSession` 是并列具体类（~1800 行重复），无 `CliSession` 接口 |
| P2 | 入口未统一 | `SessionRuntimeRouter.sendClaude()`/`sendCodex()` 双方法 + if/else 分发 |
| P3 | 路由硬编码分散 | "是否 CLI" 在 `SessionSendService`、`SessionHandler.isCliModeActive`、`SessionRuntimeRouter` 三处各自计算 |
| P4 | "Codex 永远 CLI" 硬编码 | `SessionHandler.isCliModeActive`: `if (CODEX) return true;` |
| P5 | 请求 DTO 三套 | `CliRequest`、`CliSendRequest`、SDK 散参数 不一致 |
| P6 | provider 常量多处重复 | `CommonConstants`/`CliConstants`/`HandlerContext`/`SdkDefinition`/`SessionState.VALID_PROVIDERS` |
| P7 | provider×runtime 路由不可配 | 完全靠散落 if/switch 字符串比较，无注册表 |
| P8 | 前端模型清单前端硬编码 | `webview/src/types` 的 `AVAILABLE_MODELS` + localStorage 映射，后端不提供（留作独立 spec） |

**已有可复用的汇聚点**（本次不动，作为统一基础）：`MessageCallback`（协议汇聚）、`SDKResult`（统一结果）、`RuntimeKey`（会话实例身份键）、`CliSendRequest`（CLI 统一请求）、`BaseSDKBridge`（SDK 侧已有模板方法抽象）。

## 4. 参考：work 模块风格映射

| work 模块 | 本设计对应 |
|---|---|
| `BaseCommonOrderService<RESULT,FORM,REQ>` 泛型入口接口 | `SessionRuntime` 接口 |
| `support(WorkType)` 策略判定 | `supports(ProviderType, RuntimeType)` |
| `getWorkType()` 抽象钩子 | `provider()` + `runtimeType()` |
| `WorkType` 枚举路由键 | `ProviderType` + `RuntimeType` 双枚举 |
| Spring `List<Strategy>` 自动注入 + `stream().filter(support)` | **手动 `SessionRuntimeRegistry`** + 枚举键查表（本项目无 Spring） |
| `OrderForm` 请求 DTO | `SessionRequest` 统一请求 |

**关键差异**：本项目为 IntelliJ 插件，**无 Spring 容器**——不能复刻 `@Component` 自动收集 + 构造器 `List<Strategy>` 注入。改用：Router 构造函数内 `new` 4 个实现并 `register()`，Registry 内部 `Map<Key, SessionRuntime>` 查表。加新 provider/runtime 仍只需新增一个实现类 + 一行注册，路由代码不变。

## 5. 已确认的关键决策（决策记录）

| 决策点 | 选择 | 理由 |
|---|---|---|
| 与下行总线重构协调 | **先提交再开始** | 边界清晰，95 文件提交后干净工作树动工 |
| 统一范围 | **B：统一 runtime 层** | 顶层 SessionRuntime 接口 + 4 实现 + Router 单一入口，最贴合"入口统一化" |
| 落地方式 | **方案 B：统一 + CLI 接口** | 入口统一 + CliSession 接口让最该抽象的并列类先建立多态，性价比最高 |
| CliSession 落地 | **选项 2：彻底** | manager 面向接口容器，CLI 侧完全自治，符合"SDK/CLI 两种独立模式互不影响" |
| 硬编码配置化拆分 | **A：拆分** | 路由配置化纳入本次；模型配置中心独立 spec |
| 路由策略粒度 | provider 三字段（enabled/supported/default） | 初始默认=当前行为 |
| 前端角色 | **仅入口** | CRUD/校验/持久化统一后端；前端展示+触发 |

## 6. 详细设计

### 6.1 架构总览（统一后）

```
SessionSendService / SessionHandler          ← 上层，只面向统一接口
        │   router.send(SessionRequest, MessageCallback)
        ▼
SessionRuntimeRouter                         ← 单一入口（取代 sendClaude/sendCodex + if/else）
        │   EffectiveRuntimeResolver.resolve(...)  →  registry.resolve(provider, runtimeType)
        ▼
SessionRuntimeRegistry                       ← 手动注册表（替代 Spring List<Strategy>）
        │   Map<(ProviderType, RuntimeType), SessionRuntime>
        ▼
interface SessionRuntime                     ← work 风格统一契约
   · provider() / runtimeType()              ── 路由键，类比 getWorkType()
   · supports(provider, runtimeType)         ── 类比 support(WorkType)
   · send(SessionRequest, MessageCallback)
   · interrupt(tabId) / disposeTab(tabId)
   │
   ├─ ClaudeSdkSessionRuntime  ──wrap──► ClaudeSDKBridge  (已有 BaseSDKBridge)
   ├─ CodexSdkSessionRuntime   ──wrap──► CodexSDKBridge
   ├─ ClaudeCliSessionRuntime  ──wrap──► ClaudeCliSession (implements CliSession)
   └─ CodexCliSessionRuntime   ──wrap──► CodexCliSession  (implements CliSession)
```

SDK 路径与 CLI 路径在 adapter 层即分叉，各自走完整实现链，最后汇聚到统一 `MessageCallback` 处理链（本次不动回调）。

### 6.2 核心抽象（新增）

```java
// 1. runtime 维度枚举（路由键之一）
public enum RuntimeType {
    SDK, CLI;
    public static RuntimeType fromInvocationMode(String mode) {
        return CommonConstants.INVOCATION_MODE_CLI.equals(mode) ? CLI : SDK;
    }
}

// 2. provider 维度枚举（路由键之二）
public enum ProviderType {
    CLAUDE, CODEX;
    public static ProviderType fromString(String provider) { /* 兼容现有字符串常量 */ }
}

// 3. 统一请求（合并三套 DTO）
public record SessionRequest(
    RuntimeKey key, ProviderType provider, RuntimeType runtimeType,
    String message, String sessionId, String cwd,
    List<ClaudeSession.Attachment> attachments, JsonObject openedFiles, List<String> fileTagPaths,
    String agentPrompt, String permissionMode, String model, String reasoningEffort,
    String permissionSessionId, Boolean streaming, Map<String, String> env
) { /* 紧凑构造器校验非空 */ }

// 4. 统一契约接口
public interface SessionRuntime {
    ProviderType provider();
    RuntimeType runtimeType();
    default boolean supports(ProviderType p, RuntimeType r) {
        return provider() == p && runtimeType() == r;
    }
    CompletableFuture<SDKResult> send(SessionRequest req, MessageCallback callback);
    void interrupt(String tabId);
    default void disposeTab(String tabId) {}
}

// 5. CLI 多态接口
public interface CliSession {
    CompletableFuture<SDKResult> send(CliSendRequest req, MessageCallback callback);
    void interrupt();
    void dispose();
}
```

**设计要点**：
- `RuntimeKey`（会话实例身份：channelId/tabId/epoch）与 `(ProviderType, RuntimeType)`（路由维度）**分离**，不混淆。
- 枚举通过 `fromInvocationMode`/`fromString` 桥接现有字符串常量，**前端协议零改动**。
- `CliSession` 方法签名以两个 CLI session **现有公共方法**为准对齐（实施时精确化），确保仅加 `implements`、不改内部逻辑。

### 6.3 路由机制

```java
public class SessionRuntimeRegistry {
    private final Map<Key, SessionRuntime> runtimes = new ConcurrentHashMap<>();
    private record Key(ProviderType provider, RuntimeType runtime) {}

    public void register(SessionRuntime r) {           // 重复注册抛异常
        if (runtimes.putIfAbsent(new Key(r.provider(), r.runtimeType()), r) != null)
            throw new IllegalStateException("Duplicate runtime: " + r.provider() + "/" + r.runtimeType());
    }
    public SessionRuntime resolve(ProviderType p, RuntimeType r) {   // 未知键抛异常，fail-fast
        SessionRuntime rt = runtimes.get(new Key(p, r));
        if (rt == null) throw new IllegalStateException("No runtime for " + p + "/" + r);
        return rt;
    }
    public Collection<SessionRuntime> all() { return runtimes.values(); }
}

public class SessionRuntimeRouter {
    private final SessionRuntimeRegistry registry;

    public SessionRuntimeRouter(ClaudeSDKBridge claudeBridge, CodexSDKBridge codexBridge, CliSessionManager cliManager) {
        this.registry = new SessionRuntimeRegistry();
        registry.register(new ClaudeSdkSessionRuntime(claudeBridge));
        registry.register(new CodexSdkSessionRuntime(codexBridge));
        registry.register(new ClaudeCliSessionRuntime(cliManager));
        registry.register(new CodexCliSessionRuntime(cliManager));
    }

    public CompletableFuture<SDKResult> send(SessionRequest req, MessageCallback cb) {
        return registry.resolve(req.provider(), req.runtimeType()).send(req, cb);
    }
    public void interrupt(ProviderType p, RuntimeType r, String tabId) { registry.resolve(p, r).interrupt(tabId); }
    public void disposeTab(String tabId) { registry.all().forEach(r -> r.disposeTab(tabId)); }
}
```

### 6.4 四个实现类（thin adapter，零侵入现有实现）

| 实现类 | `provider()` | `runtimeType()` | `send()` 转发到 |
|---|---|---|---|
| `ClaudeSdkSessionRuntime` | CLAUDE | SDK | `ClaudeSDKBridge.sendMessage(...)`（参数照搬现 `SdkSessionRuntime.sendClaude`） |
| `CodexSdkSessionRuntime` | CODEX | SDK | `CodexSDKBridge.sendMessageWithDaemonPreferred(...)` |
| `ClaudeCliSessionRuntime` | CLAUDE | CLI | `cliManager.send(toCliSendRequest(req,"claude"), cb)` |
| `CodexCliSessionRuntime` | CODEX | CLI | `cliManager.send(toCliSendRequest(req,"codex"), cb)` |

每个 adapter 含一个小映射方法（`SessionRequest → CliSendRequest` 或拆字段给 bridge）。**SDK bridge、CLI session、CliSessionManager 内部零改动**。

### 6.5 CliSession 接口 + manager 改造（选项 2，彻底）

- `ClaudeCliSession`/`CodexCliSession` 各加 `implements CliSession`。
- `CliSessionManager` 内部 `claudeSessions`/`codexSessions` 双 Map **合并**为面向接口的容器（如 `Map<tabId, Map<ProviderType, CliSession>>`），按 provider 解析 `CliSession`。
- `CliSessionManager` 保留其核心价值（per-tab 串行化 `inFlight` compute、回调 `adapt()` 互转、session 生命周期、interrupt/disposeTab）——这些是**并发与资源管理职责，不进 Registry**。两个 CliSessionRuntime 共用它、靠 provider 字段区分。

### 6.6 EffectiveRuntimeResolver（收口三处分散计算）

```java
public class EffectiveRuntimeResolver {
    public static Runtime resolve(ProviderType provider, SessionState state,
                                  String requestedMode, RuntimePolicyConfig policy) {
        ProviderRuntimePolicy p = policy.of(provider);
        if (p == null || !p.enabled())
            throw new IllegalStateException("Provider disabled/unknown: " + provider);
        if (provider == CLAUDE) {
            String mode = firstNonBlank(state.claudeInvocationMode(), requestedMode,
                                        CodemossSettingsService.getClaudeInvocationMode());
            RuntimeType rt = RuntimeType.fromInvocationMode(mode);
            return new Runtime(CLAUDE, p.supported().contains(rt) ? rt : p.defaultRuntime());
        }
        // Codex: 不再硬编码"永远CLI"，由 policy.codex.default 决定（默认 CLI）
        RuntimeType rt = requestedMode != null && p.supported().contains(RuntimeType.fromInvocationMode(requestedMode))
                ? RuntimeType.fromInvocationMode(requestedMode) : p.defaultRuntime();
        return new Runtime(CODEX, rt);
    }
    public record Runtime(ProviderType provider, RuntimeType runtimeType) {}
}
```
`SessionSendService` 与 `SessionHandler` 均调用它，**消除 P3 的三处重复计算**。`SessionHandler.isCliModeActive` 中 `if (CODEX) return true` 这条硬编码**删除**，由 `policy.codex.default=CLI` 表达。

### 6.7 数据流全链路

```
前端 → SessionHandler.handleSend()                 [前置校验: Node/SDK 安装等，复用现有]
       ▼
SessionSendService.send(...)
   ① EffectiveRuntimeResolver.resolve(provider, state, requestedMode, policy) → (ProviderType, RuntimeType)
   ② 构造 SessionRequest(provider, runtimeType, ...)
       ▼
SessionRuntimeRouter.send(SessionRequest, MessageCallback)
   registry.resolve(provider, runtimeType)
       ▼
┌─────── SDK 路径(完全独立) ────────┐ ┌──── CLI 路径(完全独立) ────┐
ClaudeSdk/CodexSdkSessionRuntime       ClaudeCli/CodexCliSessionRuntime
   │ bridge.sendMessage(...)              │ toCliSendRequest(req)
   ▼                                      ▼
ClaudeSDKBridge / CodexSDKBridge       CliSessionManager.send(req, cb)
(daemon.js)                              │ adapt(cb)→CliSessionCallback; 面向 CliSession 接口 resolve
   │                                      ▼
   │                                   ClaudeCliSession / CodexCliSession (进程: claude -p / codex exec)
   │                                      │ CliSessionCallback → adapt回 → MessageCallback
   └────────── 汇聚到统一 MessageCallback 处理链 ──────────┘
                    ClaudeMessageHandler / CodexMessageHandler → 前端
```

### 6.8 SDK/CLI 隔离四原则（落实"两种独立模式互不影响"）

1. **实现隔离**：4 个 runtime 各持自身依赖（SDK 持 bridge、CLI 持 manager），无共享可变状态。
2. **故障隔离**：SDK daemon 崩溃 ≠ CLI 进程异常，反之亦然；某 runtime 抛错由 Router 包装上报，不波及其他实例。
3. **资源隔离**：SDK daemon 进程（DaemonBridge）与 CLI 一次性 ProcessBuilder 进程（CliProcessHandle）生命周期各自独立。
4. **配置隔离**：SDK 走 `bridge/` 包（ai-bridge、`CLAUDE_CODE_PATH`）；CLI 走 `CliSettings`/`CliEnvironmentBuilder`；两套配置互不读取。

### 6.9 错误处理（沿用现状，不引入新体系）

- `Router.resolve()` 找不到 runtime → `IllegalStateException`（装配错误，fail-fast，开发期暴露）。
- 各 runtime `send()` 异常 → 沿用现有路径：CLI 经 `CliErrorFormatter` → `CliSessionCallback.onError` → adapt → `MessageCallback.onError`；SDK 经 bridge 现有错误处理。
- **不做 SDK↔CLI 自动降级**（模式由 effective runtime 决定，不自动切换）。

### 6.10 路由策略配置化 + 校验层 + 前端仅入口

**数据结构**：
```java
public record ProviderRuntimePolicy(boolean enabled, Set<RuntimeType> supported, RuntimeType defaultRuntime) {}

public class RuntimePolicyConfig {
    private Map<ProviderType, ProviderRuntimePolicy> providers;
    private static final RuntimePolicyConfig DEFAULT = buildDefault();
    private static RuntimePolicyConfig buildDefault() {       // 初始默认 = 当前硬编码行为
        var m = new LinkedHashMap<ProviderType, ProviderRuntimePolicy>();
        m.put(CLAUDE, new ProviderRuntimePolicy(true, Set.of(SDK, CLI), SDK));  // 默认 SDK，可切 CLI
        m.put(CODEX,  new ProviderRuntimePolicy(true, Set.of(CLI),    CLI));    // 仅 CLI（原"永远 CLI"）
        return new RuntimePolicyConfig(m);
    }
}
```

**存储**：`CodemossSettingsService` 新增 `runtime` 节点，复用现有 `readConfig/writeConfig`（`~/.codemoss/config.json`）：
```json
{ "runtime": { "providers": {
    "claude": {"enabled": true, "supported": ["SDK","CLI"], "default": "SDK"},
    "codex":  {"enabled": true, "supported": ["CLI"],       "default": "CLI"}
}}}
```
配置缺失/损坏时回退 `DEFAULT`（旧 config.json 升级零行为变化）。

**校验层** `RuntimePolicyValidator`（错误配置不让上去，防影响插件运行）：
```java
public static ValidationResult validate(RuntimePolicyConfig cfg) {
    List<String> errors = new ArrayList<>(), warnings = new ArrayList<>();
    if (cfg.providers().values().stream().noneMatch(ProviderRuntimePolicy::enabled))
        errors.add("至少需要启用一个 provider");
    cfg.providers().forEach((p, pol) -> {
        if (pol.enabled() && pol.supported().isEmpty()) errors.add(p + " 已启用但未配置 runtime");
        if (pol.enabled() && !pol.supported().contains(pol.defaultRuntime()))
            errors.add(p + " 默认 runtime 不在支持列表内");
    });
    if (!cfg.providers().containsKey(ProviderType.CLAUDE)) errors.add("不能删除核心 provider: claude");
    return new ValidationResult(errors, warnings);   // errors 非空 → 拒绝持久化
}
```
- **写入时**：`set_runtime_policy` 先 `validate`，errors 非空→拒绝、返回明细、不落盘。
- **启动加载时**：读后 `validate`，损坏→回退 `DEFAULT` + 记日志（历史坏配置不阻断启动）。

**前端仅入口**：
- 新增 `get_runtime_policy_schema`：后端返回每字段名称/类型/合法值/说明文案，前端据此渲染表单与提示（"明确提醒如何配置"）。
- 前端三个入口：`get_runtime_policy`（读）、`set_runtime_policy`（写，后端校验+落盘）、`reset_runtime_policy`（重置默认）。前端**不自行存储、不自行校验**。

**影响边界**：
| 改造 | 保持不动 |
|---|---|
| `isCliModeActive` 的 Codex 永远 CLI → 读 policy | Codex 的 `accessMode`（inactive/managed/cli_login 登录方式） |
| `SessionState.VALID_PROVIDERS` 白名单 → 从 `policy.providers()` 派生 | `getClaudeInvocationMode` 用户已配字段（作为 Claude 三级解析一环保留） |
| 新增 RuntimePolicy 存储 + resolver 读取 | 模型（B 类，独立 spec）、下行 normalizer |

### 6.11 通用配置原则（贯穿两个 spec）

> 前端只是入口（展示 + 触发 CRUD）；读取/新增/修改/删除/校验/持久化**全部统一在后端**；配置项初始化默认值（=当前行为）但允许自由增删改；每项配置带说明告诉用户怎么配；配置校验拒绝错误配置，防止坏配置影响插件运行。

此模式（前端入口 + 后端 CRUD + validator + schema 说明 + 默认值）将在**模型配置中心（独立 spec）原样复用**。

## 7. 测试策略

| 层 | 测试内容 |
|---|---|
| **单元** | `Registry` register/resolve（重复注册抛异常、未知键抛异常）；`EffectiveRuntimeResolver`（三级优先、Codex 默认 CLI、unsupported 回退 default）；`RuntimePolicyValidator`（各类错误配置被拒、DEFAULT 通过）；4 个 adapter `send` 转发（mock bridge/manager，断言参数透传） |
| **功能等价** | 每阶段后，SDK/CLI 双模式 send 行为与重构前逐字节一致（ClaudeSdk→bridge 参数、ClaudeCli→`CliSendRequest` 字段、Codex accessMode 路由不变） |
| **配置** | config.json 缺失→回退 DEFAULT；损坏→回退 DEFAULT+日志；`set` 拒绝非法配置不落盘；删除核心 provider 被拒 |

## 8. 文件清单

**新增（后端 14）**：
- `session/runtime/RuntimeType.java`、`ProviderType.java`、`SessionRequest.java`、`SessionRuntime.java`、`SessionRuntimeRegistry.java`、`EffectiveRuntimeResolver.java`
- `session/runtime/ClaudeSdkSessionRuntime.java`、`CodexSdkSessionRuntime.java`、`ClaudeCliSessionRuntime.java`、`CodexCliSessionRuntime.java`
- `cli/CliSession.java`
- `config/RuntimePolicyConfig.java`、`ProviderRuntimePolicy.java`、`RuntimePolicyValidator.java`

**改造**：
- `session/runtime/SessionRuntimeRouter.java`（单一 send + Registry 装配）
- `session/SessionSendService.java`（构造 SessionRequest + resolver）
- `cli/CliSessionManager.java`（面向 CliSession 接口容器）
- `cli/claude/ClaudeCliSession.java`、`cli/codex/CodexCliSession.java`（implements CliSession）
- `handler/SessionHandler.java`（isCliModeActive 改用 resolver，删 Codex 硬编码）
- `settings/CodemossSettingsService.java`（runtime policy 读写）
- `handler/SettingsHandler.java` 或 `ProjectConfigHandler.java`（get/set/reset_runtime_policy + schema 消息）

**退役（阶段 4 删）**：`session/runtime/SdkSessionRuntime.java`、`session/runtime/CliRequest.java`、`CommonConstants`/`HandlerContext` 重复的 provider 常量

**前端（webview，仅入口，最小）**：runtime policy 配置入口组件（读 schema 展示 + 触发 get/set/reset，不自行存校验）

**不动**：`BaseSDKBridge`/`ClaudeSDKBridge`/`CodexSDKBridge`、`MessageCallback`/`CliSessionCallback`/`SDKResult`、`bridge/` 包、所有模型相关（B 类）

## 9. 落地节奏（5 阶段，每阶段功能等价可独立验证）

| 阶段 | 内容 | 风险 |
|---|---|---|
| **0（前置）** | 先提交下行总线重构的 95 文件 → 干净工作树 | — |
| **1** | 核心抽象骨架：枚举/SessionRequest/SessionRuntime/Registry + 4 adapter + Router 单一 send（旧 sendClaude/sendCodex 暂委托新 send 过渡） | 低 |
| **2** | `CliSession` 接口 + manager 面向接口（选项 2）+ `EffectiveRuntimeResolver` 收口三处分散 | 低 |
| **3** | 路由策略配置化：RuntimePolicyConfig + config.json 存储 + Validator + handler API + 前端入口 | 低 |
| **4** | 清理退役（删旧路径/硬编码）+ 补全测试 | 低 |

## 10. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 重构破坏现有 SDK/CLI 发送行为 | 每阶段功能等价测试；adapter 参数照搬、零侵入现有实现；旧方法过渡期委托新 send |
| 与未提交的下行总线重构冲突 | 阶段 0 先提交；下行重构改回调迁移、不改 runtime 顶层结构，正交 |
| 坏配置影响插件运行 | RuntimePolicyValidator 双重校验（写入+启动加载）；损坏回退 DEFAULT |
| CliSessionManager 面向接口改造引入并发回归 | 保留串行化逻辑，仅改容器类型；选项 2 在阶段 2 集中验证 |

## 11. 不在本次范围：模型配置中心（独立 spec）

本次仅做 runtime 入口统一 + 路由策略配置化。模型相关 B 类硬编码作为**独立的"模型配置中心" spec**，复用本 spec 第 6.11 节的通用配置原则：

**范围（待独立 spec 展开）**：
- 统一散落的模型元数据：前端 `webview/src/types` 的 `AVAILABLE_MODELS` + localStorage 映射；后端 `ModelProviderHandler.MODEL_CONTEXT_LIMITS`（26 模型容量表）、`ClaudeCliModelResolver.detectFamily`+能力表、`CodexUsageAggregator.MODEL_PRICING/ALIASES/PREFIXES`、5 处散落 `DEFAULT_MODEL`、`[1m]` 规则。
- 后端成为模型清单**唯一数据源** `ModelRegistry`，前端 `ModelSelect.tsx` 改为从后端拉取（`onAddModel` 调后端）。
- 模型 CRUD + 校验 + schema 说明 + 默认值，全部后端处理。

## 12. 开放问题（实施时确认）

1. `CliSession` 接口方法签名：以 `ClaudeCliSession`/`CodexCliSession` 现有公共方法精确对齐（实施阶段 2 确认）。
2. `RuntimePolicyConfig` 在 config.json 的确切节点路径与版本迁移策略（阶段 3 确认）。
3. 前端 runtime policy 入口组件的具体放置位置（设置面板内何处，阶段 3 与前端结构对齐）。
