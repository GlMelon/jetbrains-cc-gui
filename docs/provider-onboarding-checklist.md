# Provider 接入统一清单 (F1)

> 本文档是 `docs/comprehensive-optimization-directions.md` §5 F1 的配套接入清单，记录新增一个 Provider 需要修改的全部接触点。
>
> 当前三 Provider（Claude、Codex、OpenCode）已落地，本文档基于 OpenCode（第 3 个 Provider）的接入模式整理。

---

## 1. 概览

新增一个 Provider 大约需要修改 **~30 个文件**，其中约 **15 个新文件**，约 **15 个现有文件修改**。

### 1.1 各层接触点分布

| 层 | 新文件 | 修改文件 | 合计 |
| --- | ---: | ---: | ---: |
| Java 后端 | 11 | 15 | 26 |
| ai-bridge (Node) | 1 | 2 | 3 |
| Frontend (Webview) | 1 | 2 | 3 |
| **总计** | **13** | **19** | **32** |

### 1.2 六路径契约测试矩阵

| Provider | SDK daemon | CLI subprocess |
| --- | ---: | ---: |
| Claude | 必测 | 必测 |
| Codex | 必测 | 必测 |
| OpenCode | 必测 | 必测 |

横切检查：env、stdin 关闭、stdout/stderr drain、abort、cwd、sessionId、baseUrl、runtime snapshot、frontend_ready。

---

## 2. Java 后端接触点

### 2.1 协议层（3 处修改）

| # | 文件 | 操作 |
| --- | --- | --- |
| 1 | `common/CommonConstants.java` | 添加 `PROVIDER_XXX` 字符串常量 |
| 2 | `session/runtime/ProviderType.java` | 添加枚举值（value, displayLabel, cliCommand, cliCommandWindows） |
| 3 | `provider/ProviderId.java` | 添加 `public static final ProviderId XXX` |

> **注意**：修改 `ProviderType.java` 后需运行 `gradle generateProtocol` 更新前端 `protocol.ts`。

### 2.2 Provider 能力描述层（2 个新文件 + 1 处修改）

| # | 文件 | 操作 |
| --- | --- | --- |
| 4 | `provider/xxx/XXXProviderAdapter.java` | **新文件**：实现 `ProviderAdapter`，声明 `capabilities()` |
| 5 | `provider/xxx/XXXSDKBridge.java` | **新文件**：继承 `BaseSDKBridge`，实现 SDK 通信 |
| 6 | `provider/ProviderAdapter.java` | 已有接口，新增 Adapter 实现即可 |

### 2.3 Session 运行时层（2 个新文件 + 2 处修改）

| # | 文件 | 操作 |
| --- | --- | --- |
| 7 | `session/runtime/XXXSdkSessionRuntime.java` | **新文件**：实现 `SessionRuntime`（SDK 模式） |
| 8 | `session/runtime/XXXCliSessionRuntime.java` | **新文件**：实现 `SessionRuntime`（CLI 模式） |
| 9 | `session/SessionProviderRouter.java` | 在 `buildAdapterList` 中添加一行 |
| 10 | `session/runtime/SessionRuntimeRouter.java` | 注册两个 `SessionRuntime`（SDK + CLI） |

### 2.4 CLI 会话层（3 个新文件 + 1 处修改）

| # | 文件 | 操作 |
| --- | --- | --- |
| 11 | `cli/xxx/XXXCliSessionFactory.java` | **新文件**：实现 `CliSessionFactory` |
| 12 | `cli/xxx/XXXCliSession.java` | **新文件**：CLI 会话实现 |
| 13 | `cli/xxx/XXXCliStreamParser.java` | **新文件**：CLI 流解析器 |
| 14 | `cli/CliSessionManager.java` | 添加 `XXXCliSessionFactory` 到两个构造器重载 |

### 2.5 Bridge 装配层（1 处修改）

| # | 文件 | 操作 |
| --- | --- | --- |
| 15 | `provider/common/ProjectBridgeRegistry.java` | 添加 bridge 字段到 `SharedBridges` + getter + 构造器参数 |

### 2.6 Handler Context 层（1 处修改）

| # | 文件 | 操作 |
| --- | --- | --- |
| 16 | `handler/core/HandlerContext.java` | 添加 bridge 字段 + 构造器参数 + getter（可选向后兼容重载） |

### 2.7 设置/配置层（2 个新文件 + 2 处修改）

| # | 文件 | 操作 |
| --- | --- | --- |
| 17 | `settings/XXXProviderManager.java` | **新文件**：Provider CRUD 管理器 |
| 18 | `settings/XXXSettingsManager.java` | **新文件**：原生配置文件管理器 |
| 19 | `settings/ProviderSettingsService.java` | 添加 `XXXProviderManager` 字段 + 构造器 + 委托方法 |
| 20 | `settings/CodemossSettingsService.java` | 添加 `XXXSettingsManager` 构造 + getter + 委托 |

### 2.8 Action Handler 层（1 个新文件 + 2 处修改）

| # | 文件 | 操作 |
| --- | --- | --- |
| 21 | `handler/provider/XXXProviderOperations.java` | **新文件**：CRUD/switch/revoke 操作处理程序 |
| 22 | `handler/provider/ProviderActionHandlers.java` | 添加 `XXXProviderOperations` 字段 + 构造器 + 委托方法 |
| 23 | `handler/provider/ProviderOrderingService.java` | 添加 `XXXProviderOperations` 参数 + 排序方法 |

### 2.9 路由策略层（1 处修改）

| # | 文件 | 操作 |
| --- | --- | --- |
| 24 | `config/RuntimePolicyConfig.java` | 在 `buildDefault()` 中添加策略条目 |

### 2.10 历史层（1 个新文件 + 1 处修改）

| # | 文件 | 操作 |
| --- | --- | --- |
| 25 | `handler/history/XXXHistoryProviderAdapter.java` | **新文件**：实现 `HistoryProviderAdapter` |
| 26 | `handler/history/HistoryProviderRegistry.java` | 在 `createDefault` 工厂中添加一行 |

### 2.11 Skills 层（1 个新文件 + 1 处修改）

| # | 文件 | 操作 |
| --- | --- | --- |
| 27 | `skill/XXXSkillProvider.java` | **新文件**：实现 `UnifiedSkillService` |
| 28 | `skill/UnifiedSkillServiceRegistry.java` | 添加单例 + 映射条目 |

### 2.12 协议枚举层（2 处修改）

| # | 文件 | 操作 |
| --- | --- | --- |
| 29 | `protocol/DownstreamEvent.java` | 添加 `PROVIDER_XXX_LIST`、`PROVIDER_ACTIVE_XXX`、`PROVIDER_XXX_CONFIG` |
| 30 | `protocol/UpstreamAction.java` | 添加 XXX 特定的 action 值 |

### 2.13 消息归一化层（1 个新文件 + 1 处修改）

| # | 文件 | 操作 |
| --- | --- | --- |
| 31 | `session/normalize/XXXMessageNormalizer.java` | **新文件**：消息规范化器 |
| 32 | `session/normalize/MessageNormalizers.java` | 添加规范化器映射 |

---

## 3. ai-bridge (Node) 接触点

### 3.1 Channel 描述符（1 个新文件）

| # | 文件 | 操作 |
| --- | --- | --- |
| 33 | `channels/xxx-channel.js` | **新文件**：实现 `ChannelDescriptor` 接口（provider、commands、handle） |

### 3.2 Registry 注册（1 处修改）

| # | 文件 | 操作 |
| --- | --- | --- |
| 34 | `channels/provider-registry.js` | 在 `getDefaultProviderRegistry` 列表中添加新 channel descriptor |

---

## 4. 前端 (Webview) 接触点

### 4.1 协议类型（自动生成）

- `webview/src/generated/protocol.ts` 由 `gradle generateProtocol` 自动生成，无需手动修改

### 4.2 国际化（1 处修改）

| # | 文件 | 操作 |
| --- | --- | --- |
| 35 | `webview/src/locales/zh.json` | 添加 Provider 显示名称、设置页文案 |
| 36 | `webview/src/locales/en.json` | 同上 |

### 4.3 设置页 UI（按需）

| # | 文件 | 操作 |
| --- | --- | --- |
| 37 | `webview/src/components/settings/` | 按需添加 Provider 专属设置面板 |

---

## 5. 验收标准

### 5.1 编译检查

- `./gradlew build` 编译通过
- `npm run lint` 零 error（ai-bridge）
- `npx tsc --noEmit` 零 error（webview）

### 5.2 六路径契约测试

| 测试 | 验证点 |
| --- | --- |
| `ProviderSixPathContractTest` | 所有 Provider 在 Registry 中注册，六路径完整 |
| 现有 `ProviderCapabilityContractTest` | 能力声明正确 |
| 现有 `ProviderActionHandlerContractTest` | Action handler 注册正确 |

### 5.3 全量测试

- `./gradlew test` — Java 全量测试零 failure
- `npm test` — ai-bridge 全量测试零 failure
- `npx vitest run` — Webview 全量测试零 failure

### 5.4 协议兼容

- Java ↔ Node NDJSON 字符串契约不变
- 前端 `protocol.ts` 由 Java 枚举自动生成，无手写第二真相源
- Provider 协议值（`provider` 字段）与 `ProviderType` 枚举值一致

---

## 6. 参考文件

| 文件 | 说明 |
| --- | --- |
| `docs/comprehensive-optimization-directions.md` §5 F1 | Provider 扩展体系文档 |
| `docs/model-registry-real-routing-plan.md` | 模型配置路由相关 |
| `AGENTS.md` | 架构准则（E7 决策接受手工装配） |
| `src/main/java/.../provider/` | Provider Adapter 实现 |
| `src/main/java/.../session/runtime/` | Session Runtime 实现 |
| `ai-bridge/channels/` | ai-bridge Channel 描述符 |