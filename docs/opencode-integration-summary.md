# OpenCode Provider 集成总结

## 1. 背景与目标

在现有 Claude 和 Codex 双 provider 架构基础上，新增第三个 AI provider —— OpenCode（[anomalyco/opencode](https://github.com/anomalyco/opencode)），支持 SDK 和 CLI 双调用模式。

## 2. 架构设计

### 2.1 路由矩阵

```
             │   SDK (Node.js daemon)                │   CLI (一次性子进程)
─────────────┼───────────────────────────────────────┼──────────────────────────
   CLAUDE    │ ClaudeSdkSessionRuntime               │ ClaudeCliSessionRuntime
─────────────┼───────────────────────────────────────┼──────────────────────────
   CODEX     │ CodexSdkSessionRuntime                │ CodexCliSessionRuntime
─────────────┼───────────────────────────────────────┼──────────────────────────
   OPENCODE  │ OpenCodeSdkSessionRuntime             │ OpenCodeCliSessionRuntime
```

### 2.2 调用链路

**SDK 模式**：
```
OpenCodeSdkSessionRuntime.send()
  → OpenCodeSDKBridge.sendMessage()
    → BaseSDKBridge.executeStreamingCommand()
      → [node, channel-manager.js, opencode, send]
        → opencode-channel.js → @opencode-ai/sdk → opencode serve (HTTP API)
```

**CLI 模式**：
```
OpenCodeCliSessionRuntime.send()
  → CliSessionManager.send()
    → OpenCodeCliSessionFactory.create()
      → OpenCodeCliSession.send()
        → [opencode, api, v2.session.prompt, -d, {json}]
```

## 3. 文件变更清单

### 3.1 新建文件（12 个）

| 文件 | 说明 |
|------|------|
| `src/.../cli/opencode/OpenCodeCliResolver.java` | 查找 opencode 可执行文件路径 |
| `src/.../cli/opencode/OpenCodeCliSessionFactory.java` | CLI 会话工厂，路由键 `"opencode"` |
| `src/.../cli/opencode/OpenCodeCliSession.java` | CLI 会话实现，调用 `opencode api` 命令 |
| `src/.../provider/opencode/OpenCodeSDKBridge.java` | SDK bridge，继承 BaseSDKBridge |
| `src/.../provider/opencode/OpenCodeDaemonCoordinator.java` | 管理 `opencode serve` 进程生命周期 |
| `src/.../provider/opencode/OpenCodeStreamAdapter.java` | 解析 OpenCode SSE 事件流 |
| `src/.../provider/opencode/OpenCodeProviderAdapter.java` | Provider 适配器，实现 ProviderAdapter |
| `src/.../session/runtime/OpenCodeSdkSessionRuntime.java` | SDK runtime adapter |
| `src/.../session/runtime/OpenCodeCliSessionRuntime.java` | CLI runtime adapter |
| `ai-bridge/channels/opencode-channel.js` | Node.js bridge channel 描述符 |

### 3.2 修改文件（18 个）

| 文件 | 修改内容 |
|------|----------|
| `CommonConstants.java` | +`PROVIDER_OPENCODE = "opencode"` |
| `ProviderType.java` | +`OPENCODE("opencode")` 枚举值，`fromString()` 增加 case |
| `ProviderId.java` | +`OPENCODE` 静态常量 |
| `CliConstants.java` | +OpenCode CLI 参数和 API 端点常量 |
| `CliSessionManager.java` | +注册 `OpenCodeCliSessionFactory` |
| `SessionRuntimeRouter.java` | +注册 `OpenCodeSdkSessionRuntime` 和 `OpenCodeCliSessionRuntime` |
| `EffectiveRuntimeResolver.java` | +OpenCode 路由逻辑（与 Codex 对称） |
| `RuntimePolicyConfig.java` | +默认配置包含 `OPENCODE: enabled=true, supported={SDK, CLI}` |
| `SessionSendService.java` | +`OpenCodeSDKBridge` 参数，传递给 `SessionRuntimeRouter` |
| `SessionProviderRouter.java` | +`OpenCodeProviderAdapter` |
| `ProjectBridgeRegistry.java` | +`OpenCodeSDKBridge` 字段 |
| `ClaudeSession.java` | +`OpenCodeSDKBridge` 构造函数重载 |
| `ClaudeChatWindow.java` | +提取 `openCodeSDKBridge`，传递给 `ClaudeSession` |
| `SessionLifecycleManager.java` | +`SessionHost.getOpenCodeSDKBridge()`，传递给 `ClaudeSession` |
| `provider-registry.js` | +注册 `opencodeChannelDescriptor` |
| `RuntimePolicySection.tsx` | +OpenCode provider 策略卡片 |
| `useModelProviderState.ts` | +OpenCode provider 切换支持 |
| `aiFeatureConfig.ts` | +opencode 类型定义 |
| `promptEnhancer.ts` | +opencode 默认配置 |
| `AiFeatureProviderModelPanel/index.tsx` | +opencode provider 选项 |

## 4. 关键实现细节

### 4.1 枚举/常量层

```java
// ProviderType.java
public enum ProviderType implements ProtocolValue {
    CLAUDE("claude"),
    CODEX("codex"),
    OPENCODE("opencode"),  // 新增
    ;
}

// CommonConstants.java
public static final String PROVIDER_OPENCODE = "opencode";
```

### 4.2 CLI 层

```java
// OpenCodeCliSessionFactory.java
public class OpenCodeCliSessionFactory implements CliSessionFactory {
    @Override
    public String provider() { return CliConstants.PROVIDER_OPENCODE; }
    
    @Override
    public CliSession create(String tabId) { return new OpenCodeCliSession(tabId); }
}

// OpenCodeCliSession.java - 核心调用
// 首次: opencode api v2.session.create
// 后续: opencode api v2.session.prompt -d '{"parts":[{"type":"text","text":"..."}]}'
```

### 4.3 SDK Bridge 层

```java
// OpenCodeSDKBridge.java
public class OpenCodeSDKBridge extends BaseSDKBridge {
    @Override
    protected String getProviderName() { return CommonConstants.PROVIDER_OPENCODE; }
    
    public CompletableFuture<SDKResult> sendMessage(
            String channelId, String message, String sessionId,
            String cwd, String model, MessageCallback callback) {
        List<String> command = buildSendCommand();  // [node, channel-manager.js, opencode, send]
        String stdinJson = buildSendStdinJson(message, sessionId, cwd, model);
        return executeStreamingCommand(channelId, command, stdinJson, cwd, callback);
    }
}
```

### 4.4 SessionRuntime 层

```java
// OpenCodeSdkSessionRuntime.java
public class OpenCodeSdkSessionRuntime implements SessionRuntime {
    @Override
    public ProviderType provider() { return ProviderType.OPENCODE; }
    
    @Override
    public RuntimeType runtimeType() { return RuntimeType.SDK; }
    
    @Override
    public CompletableFuture<SDKResult> send(SessionRequest req, MessageCallback callback) {
        return bridge.sendMessage(
                req.key().channelId(), req.message(), req.sessionId(),
                req.cwd(), req.model(), callback);
    }
}
```

### 4.5 路由策略

```java
// RuntimePolicyConfig.java - 默认配置
private static RuntimePolicyConfig buildDefault() {
    var m = new LinkedHashMap<ProviderType, ProviderRuntimePolicy>();
    m.put(ProviderType.CLAUDE, new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK));
    m.put(ProviderType.CODEX, new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK));
    m.put(ProviderType.OPENCODE, new ProviderRuntimePolicy(true, Set.of(RuntimeType.SDK, RuntimeType.CLI), RuntimeType.SDK));
    return new RuntimePolicyConfig(m);
}
```

## 5. 已知问题与待办

### 5.1 AGENTS.md 合规性

| 总则 | 状态 | 说明 |
|------|------|------|
| 一·职责分离 | ⚠️ | 前端硬编码 provider 列表 `['claude', 'codex', 'opencode']`，应改为后端下发 |
| 二·开闭原则 | ✅ | SessionRuntime + Registry 模式 |
| 三·SSOT | ✅ | ProviderType 枚举自动生成前端类型 |
| 四·复用 | ✅ | 复用 BaseSDKBridge、CliSessionManager |
| 五·拓展点 | ✅ | Registry 注册表模式 |

### 5.2 前端硬编码问题

当前 `RuntimePolicySection.tsx` 和 `AiFeatureProviderModelPanel/index.tsx` 硬编码了 provider 列表：

```tsx
// 违反 AGENTS.md 总则一
{(['claude', 'codex', 'opencode'] as ProviderKey[]).map((provider) => (
```

**正确做法**：后端通过 `DOWNSTREAM` 事件下发可用 provider 列表，前端动态渲染。

### 5.3 修复记录

| 问题 | 修复 |
|------|------|
| OpenCodeSdkSessionRuntime 未注册 | ClaudeChatWindow/SessionLifecycleManager 传递 openCodeSDKBridge |
| openCodeSDKBridge 为 null 导致 NPE | SessionRuntimeRouter 条件注册 `if (openCodeSDKBridge != null)` |
| 前端硬编码 provider 列表 | 暂保留，待后续改为后端下发（AGENTS.md 总则一违规） |

### 5.4 编译状态

- ✅ `./gradlew compileJava` 通过（排除预先存在的 ClaudeCliModelResolver/ClaudeCliSession 错误）
- ✅ `./gradlew buildWebview` 通过
- ⚠️ 测试失败为预先存在的基线（feature branch 上已有 33 个）

## 6. 验证方法

1. **编译验证**：`./gradlew compileJava`
2. **前端构建**：`./gradlew buildWebview`
3. **功能验证**：
   - 设置面板显示 OpenCode provider
   - 可切换 SDK/CLI 模式
   - 通过 OpenCode 发送消息收到响应
4. **路由验证**：`SessionRuntimeRouter` 注册 6 个 runtime（3 provider × 2 runtime）

## 7. SDK 依赖管理集成

### 7.1 背景

为保持架构一致性，OpenCode SDK 需要像 Claude SDK 和 Codex SDK 一样，在设置界面的「SDK 依赖」菜单中支持安装、卸载和更新操作。

### 7.2 修改文件

| 文件 | 修改内容 |
|------|----------|
| `SdkDefinition.java` | +`OPENCODE_SDK` 枚举值（id: `opencode-sdk`, npm包: `@opencode-ai/sdk`） |
| `SdkDefinition.fromProvider()` | +OpenCode provider 到 SDK 的映射 |
| `dependency.ts` | +`'opencode-sdk'` 到 `SdkId` 类型；+OpenCode SDK 到 `SDK_DEFINITIONS` 数组 |
| `DependencySection/index.tsx` | +OpenCode SDK 配置；+`loadingVersions` 初始状态 |
| `zh.json` | +`opencodeSdkName` 和 `opencodeSdkDescription` 翻译 |
| `en.json` | +对应的英文翻译 |
| `index.test.tsx` | +OpenCode SDK 的 mock 数据和测试断言 |

### 7.3 关键实现

**后端 SdkDefinition 枚举**：
```java
OPENCODE_SDK(
    "opencode-sdk",
    "OpenCode SDK",
    "@opencode-ai/sdk",
    "latest",
    Collections.emptyList(),
    Arrays.asList("0.1.0", "0.0.9", "0.0.8"),
    "OpenCode AI 提供商所需。"
),
```

**Provider 映射**：
```java
public static SdkDefinition fromProvider(String provider) {
    if (CommonConstants.PROVIDER_OPENCODE.equalsIgnoreCase(provider)) {
        return OPENCODE_SDK;
    }
    // ...
}
```

**前端类型扩展**：
```typescript
export type SdkId = 'claude-sdk' | 'codex-sdk' | 'opencode-sdk';
```

**前端 SDK 定义**：
```typescript
{
  id: 'opencode-sdk' as SdkId,
  nameKey: 'settings.dependency.opencodeSdkName',
  description: 'settings.dependency.opencodeSdkDescription',
  relatedProviders: ['opencode'],
},
```

### 7.4 国际化

**中文 (zh.json)**：
```json
"opencodeSdkName": "OpenCode SDK",
"opencodeSdkDescription": "OpenCode AI 功能所需。包含 OpenCode SDK。",
```

**英文 (en.json)**：
```json
"opencodeSdkName": "OpenCode SDK",
"opencodeSdkDescription": "Required for OpenCode AI features. Includes OpenCode SDK.",
```

### 7.5 验证

- ✅ TypeScript 编译通过
- ✅ DependencySection 测试全部通过（9/9）
- ✅ 设置界面显示三个 SDK：Claude Code SDK、Codex SDK、OpenCode SDK

## 8. 设置页面 OpenCode 支持（2026-06-26）

### 8.1 背景

后端 OpenCode 的 provider/adapter/session/runtime 实现已完整，但设置页面的 4 个关键菜单缺少对 OpenCode 的 UI 支持：

1. **供应商管理** — `ProviderTabSection` 只有 Claude/Codex 两个标签页
2. **模型配置** — `ModelRegistrySection` 过滤器只有 `all/claude/codex`
3. **增强提示词** — `AiFeatureProviderModelPanel` 已列出 opencode 选项，但后端未计算其可用性
4. **Commit AI** — 同上

### 8.2 修改文件清单

#### 后端（Java）

| 文件 | 修改内容 |
|------|----------|
| `CodemossSettingsService.java` | `buildAiFeatureAvailability()` 新增 opencode 可用性计算；`isAiFeatureProviderAvailable()` 新增 opencode 分支（检查 `opencode-sdk` 安装状态）；`getAiFeatureConfig()` 读取 `opencodeAvailable`；`resolveAiFeatureProvider()` 扩展为三路降级；`normalizeAiFeatureProvider()` 接受 opencode；`createAiFeatureModels()` / `getNormalizedAiFeatureModels()` 支持 opencode 模型持久化 |
| `ProjectConfigHandler.java` | `AiProviderSetter` 接口扩展为 4 参数（含 `opencodeModel`）；`applyAiProviderConfig()` 读取 `models.opencode` 字段 |

#### 前端（TypeScript/React）

| 文件 | 修改内容 |
|------|----------|
| `ProviderTabSection/index.tsx` | 新增第三个 OpenCode 标签页按钮和面板区域 |
| `OpenCodeProviderSection/index.tsx` | **新建** — OpenCode 供应商状态面板组件 |
| `OpenCodeProviderSection/style.module.less` | **新建** — OpenCode 供应商面板样式 |
| `ModelRegistrySection/index.tsx` | 过滤器新增 `opencode` 选项；编辑器支持 opencode provider；保存逻辑兼容 opencode（复用 Claude 模型格式） |
| `useSettingsBasicActions.ts` | 重置降级逻辑新增 opencode（codex → claude → opencode） |
| `zh.json` | 新增 `settings.providerTab.opencode`、`settings.openCodeProvider.*`、`settings.basic.promptEnhancer.provider.opencode` 翻译 |
| `en.json` | 新增对应英文翻译 |

### 8.3 关键实现细节

#### 后端：OpenCode 可用性计算

```java
// CodemossSettingsService.java
private JsonObject buildAiFeatureAvailability() {
    JsonObject availability = new JsonObject();
    availability.addProperty(CommonConstants.PROVIDER_CLAUDE, isAiFeatureProviderAvailable(CommonConstants.PROVIDER_CLAUDE));
    availability.addProperty(CommonConstants.PROVIDER_CODEX, isAiFeatureProviderAvailable(CommonConstants.PROVIDER_CODEX));
    availability.addProperty(CommonConstants.PROVIDER_OPENCODE, isAiFeatureProviderAvailable(CommonConstants.PROVIDER_OPENCODE));
    return availability;
}

private boolean isAiFeatureProviderAvailable(String provider) {
    DependencyManager dependencyManager = new DependencyManager();
    if (CommonConstants.PROVIDER_OPENCODE.equals(provider)) {
        return dependencyManager.isInstalled("opencode-sdk");
    }
    // ...
}
```

#### 后端：三路降级解析

```java
// resolveAiFeatureProvider() - 优先级: codex > claude > opencode
if (manualProvider != null) {
    boolean manualProviderAvailable = CommonConstants.PROVIDER_OPENCODE.equals(manualProvider)
            ? opencodeAvailable
            : CommonConstants.PROVIDER_CODEX.equals(manualProvider)
                    ? codexAvailable
                    : claudeAvailable;
    // ...
}
if (codexAvailable) { return codex; }
if (claudeAvailable) { return claude; }
if (opencodeAvailable) { return opencode; }
return unavailable;
```

#### 前端：ProviderTabSection 三标签页

```tsx
// ProviderTabSection/index.tsx
const [activeTab, setActiveTab] = useState<'claude' | 'codex' | 'opencode'>(
  () => currentProvider === 'codex' ? 'codex' : currentProvider === 'opencode' ? 'opencode' : 'claude'
);

// 三个标签按钮: Claude / Codex / OpenCode
// 三个面板区域: ProviderManageSection / CodexProviderSection / OpenCodeProviderSection
```

#### 前端：ModelRegistrySection opencode 支持

```tsx
// 过滤器新增 opencode
{(['all', 'claude', 'codex', 'opencode'] as const).map((provider) => (
  <button ...>{provider}</button>
))}

// 编辑器: opencode 复用 Claude 模型格式（role + actualModel）
const useClaudeFormat = isClaude || isOpenCode;
```

### 8.4 验证

- ✅ `./gradlew compileJava` 编译通过
- ✅ TypeScript 类型检查通过（`npx tsc --noEmit`）
- ✅ 设置相关测试全部通过（55/55）
- ✅ 前端构建通过（`vite build`）

### 8.5 后续待办

| 事项 | 优先级 | 说明 |
|------|--------|------|
| OpenCodeProviderSection 增强 | P2 | 当前为静态状态面板，后续可增加守护进程状态实时监控、端口配置、启停控制等 |
| 前端硬编码 provider 列表 | P3 | 与 §5.2 相同问题，待后端下发可用 provider 列表 |
| OpenCode 模型 registry 预置 | P3 | 当前 opencode 模型列表依赖后端 registry 下发，可考虑预置常用模型 |
