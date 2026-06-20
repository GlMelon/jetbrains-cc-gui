# AGENTS.md 架构合规迁移计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 每个任务自洽可编译后即 commit。测试统一放最后批量执行。

**Goal:** 将项目全面对齐 AGENTS.md 五条总则,消除审查发现的三大根因(前端硬编码业务数据、handler 旧分派路径、SSOT 链路)及 P0–P3 全部违规点。

**Architecture:** 分三批增量推进。每批独立可交付、可编译、可测试,完成后 commit。P0 构建链路与 SSOT 收尾 → 批一·根因 A(前端业务下沉,最高杠杆)→ 批二·契约与分派(handler 迁移、枚举 SSOT、收口)→ 批三·分层与收尾(目录归域、DTO/Converter、Deprecated 标记)。

**Tech Stack:** Java 17 (IntelliJ plugin, Gradle) + React 19 / TypeScript (webview, Vite/Vitest) + JCEF 双向字符串总线 + JUnit / Vitest。

**优先级裁决依据(AGENTS.md):** 总则一(职责分离)> 总则三(SSOT)> 总则二/五(开闭/拓展)> 总则四(复用)。

---

## 批次总览

| 批 | 任务 | 消除根因 | 涉及总则 | 风险 |
|---|---|---|---|---|
| **P0-1** | SSOT 生成链路收尾(提交 mjs 直读 Java 源改动 + 清理冗余 + 加同步校验) | C | 三 | 低 |
| **P0-2** | 后端实现 provider preset 下发;前端删 `PROVIDER_PRESETS`/`AVAILABLE_PROVIDERS`/`CLAUDE_MODELS`/`CODEX_MODELS` 硬编码表 | A | 一、三、四 | 中(需后端先补下发) |
| **P1-A1** | 后端 `ModelConfig` 增 `supportedReasoningLevels` 字段,由 `ClaudeRole` 权威填充,经 `ModelRegistryService.serialize` 下发 | A | 一 | 低 |
| **P1-A2** | 前端下沉能力判定:删 `modelSupports1MContext`/`normalizeClaudeModelId`/`getModelContextWindow`/`getClaudeRoleFromModelId`;`DEFAULT_MODEL_REGISTRY` 改空;`useModelProviderState` 停止前端计算 `effectiveContextWindow`,改上送 modelId+开关由后端回推 | A 核心 | 一、四 | 中 |
| **P1-A3** | `ReasoningSelect` 改读后端下发的 `supportedReasoningLevels`,删除前端 role→级别规则 | A | 一 | 低 |
| **P1-B** | `bridge/events/index.ts` 的 `BRIDGE_EVENTS.type` 改引用 `DOWNSTREAM.XXX`(保留 `kind`);收口 `sendBridgeEvent`/`sendToJava` 为类型安全签名 | C | 三 | 中(227 处调用点) |
| **P1-C** | 按 `handler/settings/*` SOP 拆解 `SettingsHandler`(63 条)+ 其余 21 个旧 handler 为 `FrontendActionHandler<T>`(最大块,可分多子任务) | B | 二 | 高(逐个探索迁移) |
| **P2-A** | `PermissionMode`/`ReasoningEffort` 提升为 Java 枚举并纳入 `ProtocolManifestGenerator` 生成 | C | 三 | 中 |
| **P2-B** | 后端提取 `DEFAULT_CONTEXT_WINDOW` 常量统一 10 处;前端 contextWindow 默认值改从 generated 导出 | C | 三 | 低 |
| **P2-C** | 4 处开闭阀门:`CliSessionFactory` Map 注入、`MessageNormalizers` Map 查表、`SessionProviderRouter.providerId` 直查 `ProviderId.of`、`ProviderType` 评估注册化 | — | 五 | 中 |
| **P3-A** | `handler/` 顶层 14 个平铺文件归入 `handler/{domain}/` | — | 二 | 低(机械移动) |
| **P3-B** | 为高频跨端数据(model registry/provider)引入强类型 DTO + Converter,替代 Settings 层裸 `JsonObject` | — | 四 | 高(数据层重构) |
| **P3-C** | `MessageDispatcher`/`BaseMessageHandler`/`LegacyMessageHandlerAdapter` 标 `@Deprecated` | B 收尾 | 二 | 低 |
| **收尾** | 全量测试:后端 `gradlew test`(176 .test.js 基线)+ 前端 `npm test`(50 .test.mjs 基线)+ 新增测试 | — | 全 | — |

---

## 批一·P0 与根因 A(详细步骤)

### P0-1:SSOT 生成链路收尾

**Files:**
- Modify: `webview/scripts/generate-protocol-types.mjs`(已含未提交的"直读 Java 源"改动)
- Modify: `build.gradle:387-403`(`generateProtocol` task 定位)
- Modify: `webview/package.json:8`(`prebuild` 的 `--stub`)

**现状判定:** mjs 已改为优先 `existsSync(upstreamJavaPath)` → `generateManifestFromJavaSources()` 直读 Java 源,`--stub` 仅在 Java 源与 manifest 均缺失时兜底。因此生产构建实际已能生成完整 `protocol.ts`,P0 风险已大幅下降。

- [ ] 审查 mjs 三段式优先级(Java 源 > manifest > stub)逻辑健壮性,确认 `parseJavaEnumProtocol` 正则匹配 Java 枚举所有条目
- [ ] 在 mjs 末尾增加"漂移校验":若已存在 `protocol.ts`,比对生成内容与磁盘内容,不一致时打印 WARN(非 fail,避免 CI 误伤)
- [ ] `package.json` 的 `prebuild`:移除 `--stub`(Java 源恒存在,stub 路径不再可达,保留是误导)
- [ ] `build.gradle`:将 `generateProtocol` task 标注为"可选产物"(mjs 已不依赖 manifest);在 task description 注明 mjs 直读 Java 源是主路径。若 `ProtocolManifestGenerator` 无其他消费者,评估 deprecate
- [ ] 手动跑 `npm run prebuild` 确认 `protocol.ts` 与 Java 枚举逐项一致
- [ ] Commit: `refactor(protocol): SSOT 生成链路以直读 Java 源为主路径,清理 stub 误导`

### P1-A1:后端 ModelConfig 增 supportedReasoningLevels 并下发

**Files:**
- Modify: `src/main/java/com/github/claudecodegui/config/ModelConfig.java`(record 增字段)
- Modify: `src/main/java/com/github/claudecodegui/common/ClaudeRole.java`(增 `supportedReasoningLevels()` 方法,权威定义每个 role 支持的级别)
- Modify: `src/main/java/com/github/claudecodegui/settings/ModelRegistryService.java:70-93`(`serialize` 输出新字段;`parse` 接收)
- Modify: `config/ReadOnlyDefaultModels.java` / `ModelRegistryConfig.java`(默认模型构造补字段)
- Test: `test/.../ModelRegistryServiceTest.java`(断言 serialize 含 supportedReasoningLevels)

**级别权威(后端 ClaudeRole,前端不再判定):**
- sonnet: low/medium/high/xhigh/max
- opus: low/medium/high/xhigh/max
- fable: low/medium/high/xhigh/max
- haiku: low/medium/high(无 xhigh/max)

- [ ] `ClaudeRole` 增 `reasoningLevels()` 返回 `List<String>`,按上表填充
- [ ] `ModelConfig` record 增 `List<String> supportedReasoningLevels` 字段(加 9 参旧构造器保持 readOnly 默认)
- [ ] `ModelRegistryService.serialize` 输出 `supportedReasoningLevels` JsonArray;`parse` 读回
- [ ] 默认模型构造处(`ReadOnlyDefaultModels` 等)用 `ClaudeRole.fromShortName(role).reasoningLevels()` 填充
- [ ] 写测试断言 serialize 结果含字段且值正确
- [ ] Commit: `feat(model-registry): 后端权威下发 supportedReasoningLevels`

### P1-A2:前端下沉能力判定(核心)

**Files:**
- Modify: `webview/src/utils/modelRegistry.ts`(`DEFAULT_MODEL_REGISTRY` 改空 items;`ModelRegistryItem` 增 `supportedReasoningLevels`)
- Modify: `webview/src/hooks/useModelProviderState.ts:147-279`(删除前端 `effectiveContextWindow` 计算与 `1_000_000`/`200_000` 字面量;改为上送 `{model, longContextEnabled}` 由后端回推)
- Modify: `webview/src/components/ChatInputBox/types.ts`(删除 `CLAUDE_MODELS`/`CODEX_MODELS`/`modelSupports1MContext`/`normalizeClaudeModelId`/`getModelContextWindow`/`getClaudeRoleFromModelId` —— 若 P0-2 未删 `CLAUDE_MODELS` 则此处先解除依赖)
- 后端配合:新增/复用下行 `MODEL_SELECTION` 携带 `effectiveContextWindow`(后端收到 set_session_model 后回推)

- [ ] 先确认后端 `MODEL_SELECTION` / `session.runtime_state` 是否已回推 `effectiveContextWindow`;若无,后端补
- [ ] `useModelProviderState`:三个 handler(model/provider/longContext)+ registry effect 改为只上送 `modelId`+`longContextEnabled`,删除 `contextWindow` 计算与字面量
- [ ] 前端 `effectiveContextWindow` 状态改为从后端下行回填(渲染回显,合规)
- [ ] 删除前端能力判定函数;`resolveClaudeRoleForModel` 改为纯读 registry `role` 字段
- [ ] 更新对应 `.test.ts`
- [ ] Commit: `refactor(webview): 模型能力判定与 contextWindow 计算下沉后端`

### P1-A3:ReasoningSelect 改读后端字段

**Files:** `webview/src/components/ChatInputBox/selectors/ReasoningSelect.tsx:56-79`

- [ ] 删除 `role === 'opus' || role === 'fable'` 等硬编码规则
- [ ] 改为 `REASONING_LEVELS.filter(l => item.supportsReasoningLevels?.includes(l.id))`
- [ ] 更新测试
- [ ] Commit: `refactor(webview): ReasoningSelect 级别过滤改读后端下发`

---

## 批二·契约与分派(SOP)

### P1-B:bridge 契约收口(SOP)
- [ ] `bridge/events/index.ts`:每条 `{ type: 'usage.update', ... }` 的字符串字面量改为 `{ type: DOWNSTREAM.USAGE_UPDATE, ... }`(需先确认 DOWNSTREAM 常量名映射;保留 `kind`)
- [ ] 全仓 `sendBridgeEvent('xxx', ...)` / `sendToJava('xxx', ...)` 逐个改为 `sendAction(UPSTREAM.XXX, ...)`(227 处,机械替换 + tsc 校验)
- [ ] 删除 `utils/bridge.ts` 中 11 处手写字面量包装函数(或标 deprecated 逐步迁移)
- [ ] Commit 分批(按文件/模块)

### P1-C:handler 迁移到 FrontendActionHandler&lt;T&gt;(SOP,最大块)
**范例模板(已验证):** `handler/settings/GetModelRegistryActionHandler.java`
**SOP(每个旧 case 重复):**
1. 在旧 handler 的 `switch-case` 中取一个 `case "xxx":`
2. 新建 `handler/{domain}/XxxActionHandler.java implements FrontendActionHandler<Payload>`
3. `action()` 返回 `UpstreamAction.XXX`;`payloadType()` 声明;`handle()` 调用既有 service
4. 在 `ChatWindowDelegate` 注入新 handler(构造器加参数)
5. 从旧 handler 删除该 case 与对应 SUPPORTED_TYPES 条目
6. 跑测试,commit

- [ ] 先拆 `SettingsHandler`(63 条,优先级最高)
- [ ] 逐个迁移 `ProviderHandler`(20)、`McpServerHandler`/`AgentHandler`/`HistoryHandler` 等
- [ ] 每个域迁移后单独 commit
- [ ] 全部迁移后,`ClaudeChatWindow.java:729-734` 删除 `messageDispatcher.dispatch` 兜底分支

### P2-A:业务枚举提升为 Java 枚举并生成
- [ ] 新建 `protocol/PermissionMode.java`、`protocol/ReasoningEffort.java` 枚举(implements ProtocolValue)
- [ ] 纳入 `ProtocolManifestGenerator`(或扩展 mjs 解析这两枚举)
- [ ] 前端 `PermissionMode`/`ReasoningEffort` 类型改从 generated 导出
- [ ] Commit

### P2-B:默认值常量化
- [ ] 后端 `common/` 增 `DEFAULT_CONTEXT_WINDOW = 200_000` 常量,替换 10 处裸字面量
- [ ] 前端 5 处 `200_000` 改从 generated 或后端下发获取
- [ ] Commit

### P2-C:开闭阀门
- [ ] `cli/CliSessionManager.createSession` switch → `Map<String, CliSessionFactory>` 注入
- [ ] `session/normalize/MessageNormalizers.forRuntime` if/else → Map 查表
- [ ] `session/SessionProviderRouter.providerId` → `ProviderId.of(provider)` 直查 Registry
- [ ] 评估 `ProviderType` 是否注册化(若 provider 数稳定可接受枚举封闭)
- [ ] Commit

---

## 批三·分层与收尾(SOP)

### P3-A:handler 目录归域
- [ ] 14 个顶层 handler 移入 `handler/{domain}/`(`McpServerHandler`→`handler/mcp/`,`SessionHandler`→`handler/session/` 等)
- [ ] 更新 import;跑编译
- [ ] Commit

### P3-B:DTO + Converter 分层
- [ ] 为 model registry / provider 引入强类型 DTO(record)+ Converter(PO↔DTO)
- [ ] Settings 层裸 `JsonObject` 逐步替换为 DTO
- [ ] Commit

### P3-C:标记 Deprecated
- [ ] `MessageDispatcher`/`BaseMessageHandler`/`LegacyMessageHandlerAdapter` 加 `@Deprecated` + Javadoc 说明迁移路径
- [ ] Commit

### 收尾:全量测试
- [ ] 后端:`./gradlew test`(确认 176 .test.js 基线全绿)
- [ ] 前端:`cd webview && npm test`(确认 50 .test.mjs 基线全绿)
- [ ] 修复新增/迁移引入的失败
- [ ] `gradlew build` 整体构建通过

---

## Self-Review

**Spec coverage:** 审查报告 P0–P3 全部 14 条行动项均有对应任务(见批次总览)。三大根因(A→P0-2/P1-A1/A2/A3;B→P1-C/P3-C;C→P0-1/P1-B/P2-A/B)全覆盖。

**风险声明:** 本计划为多会话工程。P1-C(handler 迁移)与 P3-B(DTO 分层)体量最大,需逐个探索现有逻辑后迁移,故以 SOP 而非预写代码呈现(避免占位)。每个任务保持自洽可编译即 commit,确保任一中断点仓库处于可工作状态。
