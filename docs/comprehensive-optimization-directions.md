# CC GUI 综合优化方向（源码审查修订版）

> 审查基线：`feature/v0.4.8` 工作区，2026-07-17。
>
> 本文用于维护优化 backlog 和实施优先级，不是可直接照抄的实现说明。任何条目进入开发前，仍需形成独立设计、测试矩阵和可回滚实施计划。

---

## 1. 文档定位

### 1.1 目标

本文结合当前插件实际代码，评估并维护以下方向：

- 动效与交互体验；
- Java 后端、React Webview、ai-bridge 架构；
- Provider、历史、Skills、MCP 等功能扩展；
- 安全、配置持久化和子进程生命周期；
- 构建性能、测试、可访问性和国际化。

每个方向必须同时满足：

1. **可行性**：符合 JetBrains Plugin、JCEF、Node 子进程和当前构建形态；
2. **健壮性**：具备边界处理、失败隔离、回滚、并发一致性和生命周期清理；
3. **完整性**：覆盖协议 SSOT、三 Provider 六路径、测试和验收标准；
4. **架构合规**：遵守仓库根目录 `AGENTS.md`。

### 1.2 强制架构边界

以下约束高于本文中的任何优化建议：

- Webview 只负责渲染、纯 UI 状态和输入采集；业务计算、能力判定、归一化、默认值和持久化必须在 Java 后端。
- 新增上行 action 必须实现 `FrontendActionHandler<T>`，声明 `UpstreamAction` 和 `payloadType()`。
- 下行事件必须使用 `DownstreamEvent`，统一经 `HandlerContext.dispatchEvent(...)`。
- 前端协议名称必须从 `webview/src/generated/protocol.ts` 导入。
- Provider、协议、配置键和消息类型不得使用散落字符串字面量。
- 外部能力使用 Adapter/Registry；不得在核心路由中堆叠 Provider `if/else`。
- Claude、Codex、OpenCode × SDK、CLI 共六条路径的横切行为必须对称或记录有意差异。

### 1.3 状态定义

| 状态 | 含义 |
| --- | --- |
| 可直接推进 | 现状与方案基本准确，可进入独立设计和实施 |
| 修订后可行 | 方向有价值，但必须按本文修订方案实施 |
| 暂缓 | 缺少性能、产品或兼容性数据，不应进入近期路线 |
| 重写 | 原方案存在架构、事实或安全错误，不得按原文实施 |
| 已落地/转债务 | 核心能力已经存在，仅保留具体债务清理 |

---

## 2. 当前代码基线

### 2.1 规模快照

本节数字是 2026-07-17 工作区静态统计，不作为长期固定指标：

| 项目 | 数量/体积 |
| --- | ---: |
| Java main 文件 | 659 |
| Java test 文件 | 237 |
| Webview `.tsx` 文件 | 214 |
| Webview hooks | 71 |
| Webview 测试文件 | 133 |
| ai-bridge 测试文件 | 37 |
| E2E 测试文件 | 1 |
| 超过 800 行的 Java 类 | 17 |
| `@keyframes` 定义 | 70，分布于 20 个文件 |
| `animations.less` 外的 keyframes | 50 |
| `requestAnimationFrame` 引用 | 47，分布于 25 个文件 |
| `webview/dist/index.html` | 7,148,471 字节，约 6.82 MiB |

> 测试文件数量只反映测试资产构成，不能代表代码覆盖率。

### 2.2 当前正面架构基线

以下能力已经落地，后续优化必须复用而不是重复新建：

- `FrontendActionHandler<T>` + `FrontendActionDispatcher` typed action registry；
- `UpstreamAction` / `DownstreamEvent` 协议枚举；
- Java 枚举直读生成 `webview/src/generated/protocol.ts`；
- `ProviderRegistry`、`SessionRuntimeRegistry`；
- `BaseSDKBridge` 及三 Provider 子类；
- `HistoryProviderAdapter` + `HistoryProviderRegistry`；
- `bridgeState` 同步黑板；
- Mermaid lazy singleton 动态导入；
- Claude/Codex/OpenCode 历史读取、删除和 sanitizer 基础能力。

---

## 3. 动效优化方向

### H1：第三方动画库

**状态：暂缓，仅允许局部 PoC。**

#### 现状

项目主要使用 CSS 动画和原生 DOM API。`BaseDialog` 的延迟卸载、消息入场和部分列表动画存在手工状态，但图标旋转、按钮反馈和折叠过渡并不需要 JS 动画库。

#### 风险

- 增加 Webview bundle；
- JCEF 中 layout animation 可能影响主线程和流式消息 FPS；
- 可能干扰消息滚动锚定和历史恢复定位；
- 与 B2 单文件体积治理目标冲突。

#### 修订方案

- CSS 可以完成的 spinner、opacity、transform、折叠动画继续使用 CSS；
- 仅对弹窗 presence 或确有移除动画需求的局部组件做 PoC；
- PoC 必须比较 bundle 差分、JCEF 首屏、流式 FPS 和 long task；
- 必须支持 `prefers-reduced-motion`；
- 未通过性能门槛前不得全局引入。

### H2：思考区展开/折叠动画

**状态：修订后可行。**

- 对 `thinking-section-content` 评估 CSS grid、`max-height` 或现有折叠组件模式；
- 不得只因 `task.less` 有示例就机械迁移；
- 验证未知高度、长 Markdown、嵌套代码块、折叠后滚动位置；
- reduced-motion 下应禁用或显著缩短过渡；
- 不引入第三方动画库。

### H3：消息列表入场/出场动画

**状态：暂缓批量 stagger，保留轻量单条入场。**

`MessageList` 已通过 `animatedEntryKeys` 控制新消息动画。历史恢复、搜索跳转和流式更新不应逐条 stagger。

实施边界：

- 仅对单条新消息、非历史恢复、非流式 token 更新应用动画；
- 大批量恢复达到阈值后直接渲染；
- 不允许动画改变最终高度计算和 scroll anchoring；
- 删除/回退动画必须验证焦点、搜索锚点和消息虚拟化兼容性。

### H4：重复 keyframes 治理

**状态：修订后可行。**

实测约 70 个 keyframes，而不是原统计的 69 个。重名包括 `spin`、`status-spin`、`fadeIn`、`slideIn`、`pulse` 等，但重名不等于动画体完全相同。

修订方案：

- 只抽取名称、语义和动画体都相同且跨组件复用的 keyframes；
- 公共动画使用项目级前缀，避免全局命名碰撞；
- 组件私有动画继续保留在组件样式中；
- 不将 50 个组件动画机械搬入 `animations.less`；
- 增加 reduced-motion 总体策略。

### H5：骨架屏

**状态：修订后可行。**

适用场景限于首次加载且有可感知延迟的列表或页面。加载、空数据、错误和重试状态必须由真实后端事件或请求状态驱动。

禁止：

- 用无限 skeleton 掩盖请求未返回；
- 在本地瞬时数据上制造额外等待；
- Webview 自行推导业务加载结论。

### H6：微交互

**状态：可直接推进，需逐项小批量实施。**

优先范围：复制成功反馈、发送状态、错误反馈、焦点状态。优先 CSS，不统一引入 JS 动画库，并为所有持续动画提供 reduced-motion 降级。

### H7：reduced-motion 全局策略

**状态：可直接推进，作为 H1–H6 的横切前提。**

H2/H4/H6 均要求 reduced-motion 降级，若各组件各自实现易不一致。应先建立全局策略再逐项落地：

- 在 `animations.less` 或等价位置定义统一的 `@media (prefers-reduced-motion: reduce)` 收口规则，统一缩短或禁用过渡；
- 持续动画（spinner、pulse）在 reduced-motion 下静止或以静态替代；
- 入场/折叠动画在 reduced-motion 下退化为瞬时；
- 全局策略落地后再处理 H1–H6 的局部 PoC，避免重复实现；
- 该策略是 A11Y 验收门槛（第 11.7 节）的前置依赖。

---

## 4. 架构优化方向

### A1：React 状态管理

**状态：暂缓全量 Zustand 迁移，先测量和局部治理。**

#### 事实修正

- `contexts/` 有 6 个相关文件，但 `main.tsx` 顶层实际不是固定 6 层 Provider；
- `bridgeState` 是为跨回调竞态设计的同步黑板，必须保留；
- 当前没有 React Profiler、render count 或交互延迟基线证明 Context 是首要瓶颈。

#### 前置治理

1. 稳定 Context value；
2. 拆分读写 Context；
3. 使用 selector、memo 和 transient ref；
4. 对流式消息场景采集 render count 和长任务；
5. 只有测量证明存在系统性瓶颈后，再按 Context 逐个迁移。

迁移期间禁止 React Context 与 Zustand 双向同步形成两个状态权威。

### A2：FrontendActionHandler 样板治理

**状态：原 Lambda Map 方案重写。**

当前 `FrontendActionDispatcher` 已经使用 `Map<String, FrontendActionHandler<?>>`，并具备 typed payload、重复注册检测和装配自检。问题是包装器样板数量，而不是缺少 Map。

禁止以下方案：

```text
handlerRegistry.register("history.get", ctx -> historyHandlers.get(ctx));
```

该方案违反协议 SSOT，并丢失 `UpstreamAction` 和 `payloadType()`。

可选实施路径：

1. **推荐：codegen**
   - 从后端 action 定义生成 typed handler 或注册代码；
   - 生成结果仍实现 `FrontendActionHandler<T>`；
   - 注册必须使用 `UpstreamAction`，不得写字符串。
2. **typed factory**
   - factory 参数至少包含 `UpstreamAction`、`Class<T>` 和 typed lambda；
   - 返回标准 `FrontendActionHandler<T>`；
   - 保留 `verifyAllRegistered()`。

验收条件：协议生成一致、payload 类型测试、重复 action fail-fast、装配完整性测试全部通过。

### A3：CodemossSettingsService 拆分

**状态：已落地(2026-07-17,聚焦核心范围);ConfigRepository 地基+Facade 委托落地,F9 migration registry 与 in-process 写锁列为后续独立立项。**

不能先把 2373 行类机械拆成多个都能直接读写同一 JSON 的 Service，否则会产生 lost update。

#### 推荐结构

1. `ConfigRepository`
   - 单一配置读写入口；
   - 原子写入、锁/版本检查、备份和回滚；
   - unknown field preservation；
   - malformed config quarantine；
   - 外部编辑冲突检测。
2. 领域 Service
   - `ProviderSettingsService`
   - `AppearanceService`
   - `ModelRegistryService`
   - `McpSettingsService`
3. 兼容 Facade
   - 在迁移期保留 `CodemossSettingsService` 公共调用面；
   - Facade 是必要兼容边界，不是可选项。

**实施前提（现状核实）**：当前 `CodemossSettingsService.writeConfig` 是裸 `FileWriter` 直写，既不原子也无冲突检测（同模块 `CodexSettingsManager`/`OpenCodeSettingsManager` 已有 temp+`ATOMIC_MOVE` 范式可借鉴）；`readConfig` 每次 fresh 读、无缓存（为 cc-switch 外部修改即时性）。因此 ConfigRepository 改造须**同时**引入原子写入与 mtime 比对，二者共用同一属性快照抽象——不要误以为只是「加一个冲突检查」。冲突检测不能依赖 watcher（见 B4 暂缓 WatchService），必须走 write-time CAS。

`CodexMcpServerManager` 和 `ProviderManager` 应分别立项，不要求与 A3 同一提交一次拆完。

**落地记录(2026-07-17,聚焦核心范围):**

- 新建 `ConfigRepository`(单一配置读写入口,作为 `CodemossSettingsService` Facade 的内部实现):
  - **原子写入**:temp 文件(同目录,保证 ATOMIC_MOVE 可用)+ `FileChannel.force(true)` fsync + `ATOMIC_MOVE`(跨卷 FS 回退非原子仅告警),根治裸 `FileWriter` 直写崩溃半写截断 JSON。
  - **write-time CAS**:read 时记录 mtime+size snapshot(ThreadLocal),write 时比对,检测 cc-switch / 外部编辑 lost update(冲突抛 `ConfigConflictException`,不再静默覆盖);snapshot 线程局部——同线程 read-modify-write 准确(主线程 vs 外部编辑),跨线程 RMW 宽松跳过 CAS(彻底解决 in-process 并发 RMW 需 in-process 锁,列为后续独立立项,非本范围)。
  - **malformed quarantine**:损坏主文件隔离到 `config.json.quarantine-<ts>.json`(供 forensic,不删除),并从最新 backup `.bak.1` 原子恢复主文件(temp+move,保证恢复也是原子的),不再静默用 default 覆盖、彻底抹掉原配置;无可用 backup 返回 null。
  - **多版本 backup**:滚动保留 `MAX_BACKUPS=5` 份 `config.json.bak.<n>`,主文件 + backup + temp 均 0600(含 provider API key/token 等 secret 故收紧权限;Windows 无 POSIX → no-op,靠 home 目录 ACL 隔离)。
  - **unknown field 透传**:load/save 均操作整体 `JsonObject`,未映射字段天然保留(Gson 透传),兼容外部工具写入插件未识别字段。
- **Facade 不变**:`CodemossSettingsService.readConfig/writeConfig` 签名不变,内部委托 `ConfigRepository`(readConfig 在 loaded==null 时回 `createDefaultConfig()`),43 个调用点与 5 个子 Manager 的 lambda 闭包零改动;删除原 `backupConfig()`/`hardenFilePermissions()`(职责已收口到 ConfigRepository),清理 9 个随之失效的 import。
- **测试**:`ConfigRepositoryTest` 11 用例(真实文件系统 + 临时目录注入故障,非 mock):正常往返 / 文件缺失返 null / malformed quarantine+backup 回退 / external-edit CAS 冲突 / 无 backup 时 quarantine 返 null / backup 滚动版本数 / unknown field 透传 / temp 残留清理 / 冲突不破坏现有 config / 无 read 直写跳过 CAS。全量回归 236 类/1556 用例零 failure 零 error(8 skipped 历史既有)。
- **本范围未含(独立立项)**:F9 migration registry(schemaVersion 读写闭环 + 逐级幂等迁移 + secret 脱敏);in-process 写锁(跨线程并发 RMW);ProviderSettingsService/AppearanceService/ModelRegistryService/McpSettingsService 领域拆分(Facade 已就位,领域拆分可增量进行,非 A3 地基前置)。

### A4：BaseSDKBridge 收尾

**状态：核心能力已落地，仅保留小范围债务。**

**架构澄清（承接 AGENTS.md）**：AGENTS.md 第 20 行期望的 `SdkBridgeAdapter` 字面命名未落地，但其抽象意图（`BaseSDKBridge` 之上的 Adapter + 路由）已由 `ProviderAdapter`（包装 SDK bridge）+ `ProviderRegistry`（Map 查表）+ `SessionRuntime.supports(ProviderType, RuntimeType)`（谓词路由）共同满足，AGENTS.md 第 164 行已列为「已落地范例」；`BaseSDKBridge.getProviderName()` 仅用于命令构造与日志，不参与路由判断。新增第 4 个 SDK provider 仍需改约 6 处装配点（`ProjectBridgeRegistry` + `SessionProviderRouter`/`SessionRuntimeRouter` 构造函数 + `ClaudeSession` + `SessionSendService` + ai-bridge `getDefaultProviderRegistry`），此为 AGENTS.md 第 164 行 E7 决策主动接受并标注的装配惯例，非待修复。

三 Provider 已继承 `BaseSDKBridge`。后续只处理：

- 更新注释和文档；
- 下沉经证明真正同质的生命周期逻辑；
- 保留 `configureProviderEnv` 和 `processOutputLine` 的 Provider 差异；
- 不以“新增 Provider 只需三个方法”描述完整接入成本。

任何改动必须执行 Claude/Codex/OpenCode × SDK/CLI 六路径矩阵。Provider 接入还涉及 runtime、CLI、settings、history、protocol、ai-bridge 和 frontend。

### A5：ContextCollector 改为 IntelliJ EP

**状态：修订后可行。**

推荐：

- 定义只依赖 IntelliJ Platform 基础类型的 core-safe interface/EP；
- 在 `plugin.xml` 声明扩展点；
- Java 实现在 `java-features.xml` 注册；
- Python 实现在 `python-features.xml` 注册；
- 保留无语言插件时的平台 fallback；
- 使用 Plugin Verifier，并在 IDEA、PyCharm、WebStorm 等组合验证类加载。

“编译器可检查类存在性”不足以覆盖 optional plugin 和 classloader 生命周期。

### A6：PlatformUtils 拆分

**状态：修订后可行。**

项目已经存在 `util/PathUtils` 和 `bridge/ProcessManager`，禁止再创建同名概念。

实施前先按调用图划分：

- 平台检测；
- 进程终止；
- WSL/Windows 路径转换；
- 环境读取。

如目标是可测试，应抽可注入 facade/interface；单纯移动 static 方法不能提升可测试性。必须验证 Windows、WSL、macOS、Linux 的进程树终止和路径行为。

### A7：Provider 历史服务

**状态：核心 Adapter/Registry 已落地，转为债务清理。**

当前已有：

- `HistoryProviderAdapter`；
- `HistoryProviderRegistry`；
- Claude/Codex/OpenCode 三个 adapter；
- 会话读取、消息读取、删除和缓存契约。

后续工作：

- 改善 `createDefault()` 手工装配；
- 明确搜索、归档、删除等 capability；
- 为三 Provider 补对称契约测试；
- 记录各 Provider 存储格式的有意差异；
- 不再新建同义 `HistoryBackend`。

### A8：前端协议第二真相源收敛

**状态：已落地（漂移守门 + 散落字面量收敛），2026-07-17；承接 AGENTS.md 点名债务。**

> 事实更新：原方案描述的 `webview/src/bridge/events/index.ts` Central Event Registry 已在 commit `017f84dc`（2026-06-24，C4/D1 协议字面量收敛 SSOT）整体删除，原“手写约 130 条字面量”的第二真相源已不复存在。本项 A8 的实际剩余工作因此收窄为**漂移守门 + 收敛残存散落字面量**，均已落地。

已落地（2026-07-17）：

- 新增漂移检测脚本 `webview/scripts/check-event-literals.mjs`：扫描 `webview/src` 生产代码，凡字面量等于某 `DOWNSTREAM` value 即报告并以非零码退出，CI 可直接 gate；零外部依赖，剥离注释避免误报，带 ALLOWLIST 审计机制。
- `runtimeProviderCapabilities.ts`（注册中心职能）6 条、`DependencySection/index.tsx`（消费侧 dispatch）2 条 dispatch 字面量 → `DOWNSTREAM.*` 引用，导出形状与 value 逐字不变。
- npm script `check:event-literals` 接入 `webview/package.json`；当前 ALLOWLIST 为空，全仓 0 漂移（139 条 DOWNSTREAM）。

后续守门要求：

- 任何新代码禁止以字符串字面量写出 `DOWNSTREAM` value，必须从 `generated/protocol.ts` 引用；`check:event-literals` 应进入 CI（建议与 `prebuild` 同阶段：先生成 `protocol.ts` 再检测）。
- 本项是 SSOT 收敛，不是死代码清理，与 T3 的 ts-prune allowlist 配置分离。
- 不与 A2 混淆：本项针对下行事件名（`DownstreamEvent`），A2 针对上行 action（`UpstreamAction` + `payloadType()`）。

---

## 5. 功能扩展方向

### F1：Provider 扩展体系

**状态：长期愿景，当前 ServiceLoader SPI 方案重写。**

单个 Java SPI 无法覆盖 Java、ai-bridge、静态 Webview、协议、设置 UI、Node 资源和 SDK/CLI 双模式。

近期目标：

- 完善内部 `ProviderRegistry` 和 `SessionRuntimeRegistry`；
- 建立 Provider capability descriptor；
- 收口手工装配；
- 建立统一接入清单和六路径契约测试。

长期如开放第三方 Provider，必须先设计：

- IntelliJ Extension Point；
- 协议 ABI/version；
- Node adapter packaging；
- 前端资源加载和 CSP；
- capability schema；
- 签名、信任、沙箱；
- classloader、卸载和升级兼容。

在这些问题解决前，不承诺“第三方 Provider 零代码接入”。

### F2：Skills 可视化查看/编辑

**状态：修订后可行。**

后端负责解析 `SKILL.md` frontmatter，并以 typed metadata 下发；前端不得自行解释业务字段。

必须覆盖：

- 现有 `name`、`description`、`license`、`compatibility`、`allowed-tools`、`user-invocable`、`paths`；
- Provider-specific schema；
- 未知 YAML 字段保留；
- Markdown body、注释和顺序尽量保留；
- 原子写入、备份、回滚；
- path traversal 和 symlink 防护；
- 外部编辑冲突；
- YAML 解析失败时绝不覆盖原文件。

Mermaid 只读预览不是首期必需能力。

### F3：多会话标签页增强

**状态：修订后可行，工作量中高。**

多标签由 IntelliJ ToolWindow `ContentManager` 承载，业务权威位于 Java 后端。设置页拖拽 hook 不能直接作为标签持久化方案。

需设计：

- 标签顺序、颜色和固定状态；
- session、provider、runtime/mode snapshot；
- IDE 重启恢复；
- session 不存在或 Provider 不可用时的降级；
- 前端仅渲染后端下发的标签模型。

### F4：对话历史增强

**状态：修订后可行。**

优先：

- 修正 `exportMarkdown.ts` 名实不符；
- 在现有 Provider history adapter 上扩展搜索/归档能力；
- 为 HTML/PDF 导出设计 sanitizer 和大会话内存上限；
- 按 Provider 定义归档 capability，不假设三者存储行为一致。

归档主要解决存储和组织问题，不应描述为“内存友好”。

### F5：MCP 市场增强

**状态：部分可行，安全基础先行。**

一键安装必须具备：

- marketplace 来源信任；
- hash/signature；
- 命令和参数注入防护；
- Provider MCP 格式 adapter；
- schema validation；
- secret redaction；
- 原子配置写入和 rollback。

社区评分/评论需要账号、服务端和内容治理，与当前不引入团队后端的产品定位冲突，暂不进入近期路线。

### F6：健康检测仪表盘

**状态：修订后可行，需独立架构设计。**

前置：先盘点后端现有日志与可观测性资产，能复用的指标/日志通道优先复用（总则四），避免从零新建与既有重复的 telemetry 管道。

在 UI 前先定义 telemetry：

- 指标 SSOT；
- 采样和保留期限；
- 用户隐私和开关；
- SDK/CLI 指标差异；
- daemon restart backoff、circuit breaker、防重启风暴；
- 三 Provider 六路径覆盖。

诊断包必须脱敏 token、API key、路径、prompt、Provider 配置和环境变量。

### F7：AI 单测生成

**状态：修订后可行。**

可复用 `QuickFixService` 的代码提取、Diff 和 `WriteCommandAction` 流程，但还需：

- Java/Kotlin/Python adapter；
- PSI 和 test source root 检测；
- 测试框架检测；
- package、类名和文件命名；
- 已有测试文件合并；
- 覆盖确认；
- 格式化和 import optimize。

首期应只支持一个语言/测试框架组合，验证后扩展。

### F8：CLI 版本与兼容管理

**状态：修订后可行。**

复用现有 Resolver 和版本比较，但不得在核心逻辑按版本堆叠字符串分支。

推荐：

- Provider-specific version detector/parser strategy；
- 兼容矩阵 SSOT；
- 随插件内置 manifest，远程更新需签名且有离线 fallback；
- 支持非 semver、未知版本和高于已知版本的策略；
- 三 Provider 对称探测；
- Resolver 生命周期和缓存明确后再讨论去重 spawn。

### F9：配置 schema 迁移

**状态：方向必要，原方案重写。**

#### 配置所有权

- `~/.codemoss/config.json`：插件自有，可写 `schemaVersion`；
- Claude/Codex/OpenCode 原生配置：第三方所有，禁止擅自写入插件 schema 字段。

#### 插件自有配置要求

- migration registry；
- 逐级且幂等迁移；
- unknown field preservation；
- atomic temp write + move；
- flush/fsync；
- 文件锁或 revision CAS；
- malformed quarantine；
- backup/rollback；
- external edit conflict detection；
- 迁移日志不得包含 secret。

Provider 原生配置的迁移状态记录在插件 sidecar metadata，不污染第三方配置。

---

## 6. 技术债务

### T1：测试覆盖率

**状态：统计方法重写。**

不得再用测试文件数量除以测试文件总数表示覆盖率。建立：

- Java：JaCoCo；
- Webview：Vitest coverage；
- ai-bridge：c8/Node coverage；
- E2E：关键用户链路矩阵。

覆盖目标按风险制定，优先：

- Provider 六路径；
- 配置迁移和回滚；
- 子进程 timeout/abort；
- typed protocol；
- 历史恢复和滚动；
- MCP 安装安全。

Mockito 不是目标；优先抽接口、纯函数和平台边界。

覆盖率防倒退：

- 接入 JaCoCo / Vitest coverage / c8 后，立即冻结当前数值为 baseline，新增代码不得使整体 coverage 低于 baseline（CI gate）；
- 设分支覆盖量化目标，前述优先项（Provider 六路径、配置迁移与回滚、子进程 timeout/abort、typed protocol、历史恢复、MCP 安装安全）设最低分支覆盖下限；
- 区分新增文件覆盖率门禁与全量 baseline，避免历史债务阻塞新代码合入；
- 覆盖率工具接入本身作为独立 commit，不与功能改动混提（遵守第 13 节实施纪律）。

### T2：前端代码风格

**状态：可直接推进。**

- 引入 ESLint/Prettier 时先匹配现有格式；
- 不做一次全仓格式化；
- lint、format、行为修改分 commit；
- staged 快速检查进入 pre-commit，完整检查进入 pre-push/CI。

### T3：死代码/死 CSS

**状态：修订后可行。**

- `java-features.xml`、`python-features.xml` 是 optional dependency config-file，不是普通死文件；
- `ts-prune`/`unimported` 必须配置 bridge callbacks、Vite entry、IntelliJ XML、反射和 optional plugin allowlist；
- keyframes 按 H4 规则处理；
- `exportMarkdown.ts` 重命名为符合实际 JSON 导出的名称，或补真正 Markdown 导出。

### T4：ai-bridge TypeScript 化

**状态：分阶段推进。**

1. 先增加 JSDoc、`checkJs`、ESLint 和统一 Node test；
2. 再按 Provider/channel/service 边界逐步迁移；
3. 保持 Java ↔ Node NDJSON 字符串契约；
4. 禁止 Node 类型泄漏到 Java；
5. 每步保持现有 37 个 ai-bridge 测试和协议兼容。

### T5：超大 Java 类

**状态：可行，但不按行数排期。**

17 个超过 800 行的类是候选，不代表必须一次拆分。排序指标：

- fan-in/fan-out；
- change coupling；
- 缺陷密度；
- cyclomatic/cognitive complexity；
- Provider 对称风险；
- 是否位于热路径。

每次只拆一个可独立验证、可独立 revert 的职责。

---

## 7. 安全增强

### S1：Prompt Injection 与不可信内容

**状态：原关键词阻断方案重写。**

用户输入“忽略之前的指令”不等于安全攻击。关键词和 jailbreak 模板只能作为可选风险信号，不能作为强制安全边界。

真正的安全模型应覆盖：

- 用户输入、仓库文件、网页、MCP/tool output、命令输出的 provenance；
- 不可信内容与系统指令结构隔离；
- 高风险工具调用确认；
- least privilege；
- secret scanning/redaction；
- 命令、网络和文件写入门禁；
- 审计日志。

### S2：凭证安全

**状态：已落地(2026-07-17,聚焦核心范围);PasswordStore 地基落地,明文迁移与六路径 env 注入改造列为后续独立立项。**

插件自有 `~/.codemoss/config.json` 当前会保存 Provider key/token，并仅做 POSIX `0600` best-effort。Java 插件优先使用 IntelliJ `PasswordSafe`，不引入 Node `keyring/libsecret` 作为主路径。

必须设计：

- credential ID；
- project/global scope；
- 明文配置迁移；
- CLI/SDK 子进程 env 注入；
- clear/logout；
- backup 和诊断包中 secret 清理；
- 安全存储不可用时的显式降级；
- credential 容量边界：`PasswordSafe` 单值有大小上限（Windows KeePass、headless 无 keychain 环境尤其敏感），OAuth refresh token 或大块 JSON 凭证需设计分割存储或回退到文件 + 0600 的降级路径；
- headless CI / 无系统 keychain 环境下 `PasswordSafe` 可能降级为不安全存储，须显式检测并提示；
- Provider 原生 OAuth/token 文件不由插件擅自迁移。

**落地记录(2026-07-17,聚焦核心范围):**

- **现状核实**:全仓零 PasswordSafe 使用(S2 从零引入);provider apiKey 多在 Claude/OpenCode 原生配置(Claude `settings.json` env 块、OpenCode provider 段)由插件读+注入 env 但不拥有(§F9/§S2 明确禁止插件擅自迁移原生配置);插件自有 `config.json` 仅存少量 secret(如 `smitheryApiKey` @ `CodemossSettingsService:1319`);env 注入分散在 3 SDK bridge 的 `configureProviderEnv`(Claude:235/Codex:159/OpenCode:91)+ 3 CLI session 的 `pb.environment()`+ `EnvironmentConfigurator`/`CliEnvironmentBuilder`/`injectCustomEnvars`(六路径,爆炸半径大)。
- 新建 `com.github.claudecodegui.settings.credentials` 包(S2 地基,零调用面):
  - **`CredentialBackend` 接口**:抽象 PasswordSafe 存取(store/load/remove/probeAvailability)+ 嵌套 `Availability` 枚举(AVAILABLE/DISABLED/HEADLESS_NO_BACKEND)。解耦门面逻辑与平台后端,使 PasswordStore 可纯单测(项目零平台测试基类,PasswordSafe 需 Application 上下文 + 原生 keychain,纯 JUnit 跑不了真实后端)。
  - **`IntelliJPasswordSafeBackend`**(生产,薄委托):委托 `PasswordSafe.getInstance()`,serviceName="codemoss" + accountName=credentialKey;可用性探测用 `ApplicationManager.getApplication()` 判定(纯单测/headless CI 无 Application → HEADLESS_NO_BACKEND;IDE 内 → AVAILABLE)。DISABLED 精细检测列为后续接线。真实 keychain 交互留集成测试(runIde),纯单测不覆盖(注入 InMemoryCredentialBackend 测 PasswordStore 逻辑);`attributes(credentialKey)` 纯函数可单测。
  - **`PasswordStore` 门面**(可单测核心逻辑):① **容量边界** `MAX_CREDENTIAL_BYTES=8KiB`,超限抛 `CredentialTooLargeException`(§S2 PasswordSafe 单值上限,Windows KeePass/headless 尤甚;OAuth refresh token/大 JSON fail-fast,分割存储或 file+0600 回退列为后续);② **显式降级** 后端非 AVAILABLE 时 store 抛 `CredentialStoreUnavailableException`(§S2 headless CI/无 keychain 不静默走不安全存储);读路径 load 不抛(缺失 key 等价"未配置",不阻塞调用方,迁移/注入项目可在调用侧先 `getAvailability()` 显式处理);③ **日志安全** 绝不记 secret 值,仅记 credentialKey + 字节数 + 操作状态;④ **credential key 规范** 强制 `codemoss.` 前缀(防第三方插件 PasswordSafe 条目冲突 + 便于脱敏扫描)。两个异常为 unchecked(RuntimeException),调用方按需 catch,API 签名干净。
- **测试**:`PasswordStoreTest` 17 用例(注入 `InMemoryCredentialBackend` fake,故障注入矩阵,纯 JUnit 无需 Application/keychain):正常往返 / 缺失返 null / 覆盖写 / null-空清除 / 删除幂等 / 容量超限 fail-fast / 恰好等于上限 / 多字节 UTF-8 按字节计量 / headless 降级抛 / disabled 降级抛 / 读路径不抛降级 / availability 透传 / key 前缀校验 / null key 拒绝 / 降级不脏写后端 / 超限不脏写后端。全量回归 237 类/1573 用例零 failure 零 error(8 skipped 历史既有)。
- **本范围未含(独立立项)**:① 明文配置迁移(`config.json` → PasswordStore 一次性迁移 smitheryApiKey 等插件自有 secret);② 六路径 env 注入改造(provider 子进程启动从 PasswordStore 读 secret 注入,爆炸半径大,三 Provider × SDK/CLI);③ clear/logout UI;④ backup/诊断包 secret 清理;⑤ DISABLED 精细检测;⑥ project/global scope 区分(暂只 GLOBAL);⑦ OAuth refresh token/大 JSON 分割存储或 file+0600 回退。Provider 原生 OAuth/token 文件不由插件擅自迁移(§F9 配置所有权)。

### S3：NodeJsServiceCaller

**状态：已落地(2026-07-17,聚焦核心范围);dispatcher script 与 ProcessManager hang watchdog 列为后续独立立项。**

当前真实风险不只是 `String.format`，而是同步读取 stdout 到 EOF 后才执行 `waitFor(timeout)`，子进程不关闭 stdout 时 timeout 无法生效。

现状已具备的保护（无需重复处理）：stdout 读侧已显式 `StandardCharsets.UTF_8`，Node 写侧默认 UTF-8，Windows cp936 不进入链路——**无需任何编码处理**（勿把 Python `PYTHONIOENCODING` 痛点误套到 Node）；inline script 经 `node -e` 不过 shell，路径反斜杠已转义（`WslPathUtil.resolveScriptPath`）。

修订方案：

- 使用固定 Node dispatcher script；
- 参数通过 env/stdin NDJSON；
- stdout/stderr **分流**异步 drain（当前 `redirectErrorStream(true)` 合并 stderr 会污染 stdout 的 JSON 解析，必须分离，stderr 仅用于错误信息）；
- timeout 与读取并发（根治 `readLine` 阻塞到 EOF 致 `waitFor` 永不触达）；
- output size cap：双约束——总字节上限 + 单行长度上限，任一超阈即 terminateProcess 并抛异常，不保留半条消息；
- 稳定 framing：仅在未超 cap 的正常路径定义消息边界；
- finally 确定性 terminate + unregister，并补 ProcessManager hang watchdog（当前无后台看门狗，卡死线程会永久挂起）；
- 测试永不退出、持续输出、超大输出、路径含引号和异常退出（当前 `NodeJsServiceCallerTest` 仅 1 个静态文本断言，边界覆盖几乎为零）。

**落地记录(2026-07-17,聚焦核心范围):**

- `executeNodeScript` 重写:stdout/stderr 分流(移除 `redirectErrorStream(true)`)、独立读线程逐字节有界读取(总字节 1 MiB + 单行 64 KiB 双 cap)、主线程 `waitFor(timeout)` 使 timeout 真正生效(根治 readLine 阻塞到 EOF 致 waitFor 永不触达)、超 cap 时读线程 `destroyForcibly` 打断子进程(否则子进程阻塞写管道致 waitFor 干等 timeout)、`PROCESS_TIMEOUT_SECONDS` 改为可注入(测试 2s / 生产 30s)、finally 确定性 terminate + unregister。
- `NodeJsServiceCallerTest` 补齐故障注入矩阵(7 用例全绿,node 不可用时 `assumeTrue` 跳过):永不退出 / 超大单行 / 超大总量 / 非零退出 + stderr 分流 / stderr 不污染 stdout / 正常 framing / 白名单静态断言。关键时序证据:永不退出 2.25s(= 2s timeout + ε)、超大单行 cap 0.19s(快速失败,未干等 30s)。
- 现状已具备、本轮刻意保留:UTF-8、`node -e` 不过 shell、路径转义(`WslPathUtil.resolveScriptPath`)、函数名白名单、ProcessManager 登记/注销。
- **本范围未含(各自独立立项)**:① 固定 dispatcher script + env/stdin NDJSON(当前仍 inline script,功能等价,dispatcher 为可选收敛);② ProcessManager hang watchdog(独立爆炸半径,与 S3 解耦)。路径含引号由命令构造层 `WslPathUtil` 已转义,归 `WslPathUtilTest` 覆盖,不在 `executeNodeScript` 层重复。
- 验证:全量 Java 测试 235 类 / 1545 用例零 failure 零 error(8 skipped 历史既有)。

---

## 8. 构建与性能

### B1：Gradle 增量与缓存

**状态：修订后可行，先做基准。**

`buildWebview` 是 `Exec` task，未声明 inputs/outputs，且 `compileJava` 依赖它。仅开启 daemon/cache/parallel 不能保证预期收益。

顺序：

1. Gradle profile/build scan；
2. 为 `buildWebview` 声明输入输出；
3. 设计 npm install/build 的 up-to-date 和 cache；
4. 区分本地和 CI profile；
5. 逐项启用 daemon、build cache、configuration cache 并验证兼容性。

取消“3–5 分钟必然降到 30–60 秒”的无基准承诺。

### B2：Webview 体积和 singlefile 决策

**状态：原减量方案重写，并与 B5 合并决策。**

事实：

- `index.html` 约 6.82 MiB；
- Mermaid 已动态导入，但 singlefile 会内联动态 chunk；
- highlight.js 已使用 core 和显式语言注册；
- `@lobehub/icons` 使用不止 5 个图标；
- `node_modules` 体积不等于 bundle 可减少量；
- `vconsole` 是否进入生产包取决于 build-time import/define，不取决于 dependencies/devDependencies 分类。

实施前必须使用 bundle analyzer 和构建差分。若放弃 singlefile，还需解决：

- JCEF resource URL；
- CSP；
- offline loading；
- cache invalidation；
- 插件升级清理；
- chunk 加载失败 fallback。

不预设 2–3 MiB 目标，目标由测量结果确定。

### B3：Webview bootstrap 注入

**状态：修订后可行。**

不把多段 `executeJavaScript()` 机械拼成一个大脚本。推荐：

1. 页面加载时仅建立 bridge/query 基础函数；
2. `frontend_ready` 后由后端下发 backend-authoritative bootstrap payload；
3. 统一使用 `DownstreamEvent` 和 `HandlerContext.dispatchEvent(...)`；
4. payload schema 纳入 SSOT；bootstrap payload 只是起点，最终目标是后端单一来源生成全部上行/下行 payload 的字段结构与默认值，消除前后端各写解析器的漂移（承接 AGENTS.md 总则三 payload SSOT 债务）；
5. 明确幂等、顺序和单项失败隔离。

收益必须通过 JCEF tracing/profile 验证。

### B4：config.json 读取缓存

**状态：暂缓 WatchService，先测量热路径。**

当前不缓存是为了让 cc-switch 外部修改立即生效。没有证据证明约 20ms IO 是主要瓶颈。

低风险路径：

- mtime + size snapshot（同一原语在无 watcher 期间亦支撑 A3 的写入冲突检测，二者共用 `ConfigRepository` 的属性快照抽象）；
- 非发送热路径短 TTL；
- 插件写后主动失效；
- 发送前强制 fresh read。

如采用 WatchService，必须处理 atomic replace、重复事件、overflow、delete/recreate、watcher dispose、IDE shutdown 和文件系统差异。

### B5：Mermaid 打包治理

**状态：已并入 B2。**

Mermaid 已动态导入。体积是否下降由 singlefile/multi-chunk 架构决定，不再作为独立“改动态 import”任务。

### B6：Webview HMR

**状态：修订后可行，工作量高。**

**前置条件：可行性未经验证。** JCEF 对 Vite dev server 的 CSP、`file://` 与 `http://` 混合资源、HMR WebSocket 在 Chromium Embedded 安全限制下是否可用尚无定论。进入设计前必须先做最小 PoC 验证 HMR 链路在 JCEF 内可跑通，否则整个方向可能不可行；PoC 未通过前不得进入 P3 排期。

开发模式可评估 `VITE_DEV_SERVER_URL`，但需完整设计：

- dev URL switching；
- JCEF bridge/query 注入；
- CSP；
- dev server 不可用时回退 packaged HTML；
- 端口冲突和多 IDE 实例；
- browser reload/dispose；
- 生产路径隔离。

---

## 9. 开发体验、可访问性与国际化

### D1：开发文档

**状态：可直接推进。**

补充：

- JDK、Node、Gradle 环境；
- `runIde` 和调试；
- protocol codegen；
- Java/Webview/ai-bridge 测试；
- Plugin Verifier；
- Provider 六路径验收；
- 分支、PR、版本和发布流程。

### D2：pre-commit hooks

**状态：修订后可行。**

- pre-commit：staged lint/format、轻量 type check；
- pre-push/CI：完整 Vitest、Gradle test、checkstyle、ai-bridge test；
- hook 可以本地跳过，但 CI 不可跳过；
- 使用 `vitest --changed` 前先验证基线和受影响测试语义。

### D3：本地开发脚本

**状态：现状描述修正后可行。**

`compileJava` 已依赖 `buildWebview`，因此 `runIde` 会触发 Webview 构建，不需要手工固定执行整条命令。

优先提供：

- Gradle 聚合 task；
- PowerShell 脚本；
- 对应 shell wrapper；
- 根目录 package scripts。

不把 Makefile/Justfile 作为 Windows 开发者的强制依赖。

### A11Y1：Dialog 焦点管理

**状态：修订后可行。**

`BaseDialog` 已有部分 `role="dialog"`、`aria-modal`、Escape 行为。仍需：

- focus trap；
- initial focus；
- close 后恢复触发元素；
- background inert；
- nested dialog/portal；
- 必需 accessible name；
- close button `aria-label`；
- 检查 role 放置层级。

仅添加 focus trap 不等于完整 WCAG 合规。

### A11Y2：键盘导航

**状态：修订后可行。**

Tabs 需实现：

- roving tabindex；
- ArrowLeft/Right；
- Home/End；
- orientation；
- manual/automatic activation；
- tab/tabpanel 的 id、`aria-controls`、`aria-labelledby`。

Dropdown 按实际 combobox/listbox pattern 实现；不得为所有组件机械添加 `aria-activedescendant`。

### A11Y3：屏幕阅读器实时区域

**状态：修订后可行。**

禁止对每个流式 token 发 aria-live 通知。采用：

- 节流；
- turn 完成摘要；
- 工具状态去重；
- 仅在用户不在底部或焦点不在当前内容时通知新消息；
- assertive 仅用于真正错误。

### I18N1：前端 locale

**状态：已落地(2026-07-17,baseline CI 守门);key coverage 守门落地,翻译质量仍人工评估。**

以 `en` 1446 个键为当前基准：

| locale | 缺失键数 |
| --- | ---: |
| zh | 0，另有 9 个额外键 |
| pt-BR | 266 |
| ko | 308 |
| es | 371 |
| fr | 371 |
| hi | 371 |
| ja | 371 |
| ru | 375 |
| zh-TW | 361 |

不完整 locale 是 8 个，不是 7 个。

CI 策略：

- 主语言要求完整；
- 新增 key 不得降低历史 coverage baseline；
- 历史缺口分批清偿；
- 区分 key coverage 和翻译质量，不以机器填充冒充完成。

**落地记录(2026-07-17):**

- 新建 `webview/scripts/check-locale-coverage.mjs`(locale coverage baseline 守门脚本,与 `check-event-literals.mjs` 同目录同风格):
  - `en.json` 为基准 SSOT,递归 flatten 嵌套 JSON 为点号路径键集合;对其余 locale 计算 missing(en 有 locale 无)与 extra(locale 有 en 无)。
  - baseline(`locale-coverage-baseline.json`,首次 `--init` 快照当前实际缺失数):记录每 locale 允许的最大缺失键数。当前快照 en=1446 keys,各 locale 缺失数与本节统计表完全吻合(es/fr/hi/ja=371, ko=308, pt-BR=266, ru=375, zh=0, zh-TW=361)——互相印证 docs 统计与代码现状一致。
  - **CI fail 条件**:① 任何 locale 实际缺失 > baseline(coverage 下降 = 新增 en 键未翻译);② baseline 缺某 locale 条目。多余键(extra)仅报告不 fail(疑似拼写错/废弃键,人工判断)。
  - **历史缺口分批清偿**:开发者补齐某 locale 翻译后缺失数下降,手动收紧 baseline.json 对应值(baseline 单调下降体现清偿进度)。
  - 用法:`--init` 重建 baseline / `--verbose` 打印缺失键列表 / 默认守门退出码 0/1 便于 CI gate。
- **CI 接入**:`.github/workflows/tests.yml` 新增 `i18n` job(纯 Node 读 JSON,无需 npm ci),push/PR 触发,与其他测试 job 并行独立。
- **范围边界**:本脚本只守 key coverage(键有无),不评翻译质量(机器填充能过 key 检查但质量低,仍需人工/后续工具评估,符合本节"不以机器填充冒充完成")。

### I18N2：后端 locale

**状态：方向正确，统计修正。**

当前 base bundle 约 272 keys：

- zh：272，缺 0；
- en：245，缺 27；
- es/fr/hi/ja/ru/zh_TW：约 222，各缺约 50。

以后端 base bundle 为 SSOT，增加严格键差分和新增键回归检查。

---

## 10. 修订后的优先级

### P0：安全和数据完整性

1. S3 `NodeJsServiceCaller` 生命周期和 timeout；
2. A3/F9 共用的 `ConfigRepository` 原子写入基础；
3. S2 IntelliJ `PasswordSafe` 凭证方案；
4. F9 配置所有权和迁移机制；
5. 为配置、凭证和子进程修复补故障注入、回滚和跨平台测试。

### P1：低风险、高确定性

1. D1 开发文档；
2. T2 lint/format 分阶段接入；
3. A11Y 基础焦点和键盘修复；
4. I18N baseline CI；
5. `exportMarkdown.ts` 命名修正；
6. B1 build profiling + `buildWebview` inputs/outputs；
7. H2/H5/H6/H7 局部 CSS 与 reduced-motion 全局策略；
8. A8 前端协议第二真相源收敛（events/index.ts → `DOWNSTREAM.*` + codegen 漂移检测）。

### P2：基础架构完善后

1. A3 Settings 领域拆分；
2. A5 IntelliJ EP；
3. F8 CLI 兼容矩阵；
4. F2 Skills 可视化；
5. F4 历史增强；
6. B3 typed bootstrap payload；
7. F3 标签页持久化。

### P3：有基线数据后

1. A1 Zustand；
2. H1/H3 高级动画；
3. B2 multi-chunk/singlefile 调整；
4. B4 WatchService；
5. F6 完整 telemetry 仪表盘；
6. B6 Webview HMR。

### P4：长期生态

1. F1 第三方 Provider 生态；
2. F5 社区评分/评论；
3. T4 ai-bridge 全量 TypeScript；
4. 动态前端/Node Provider 扩展体系。

---

## 11. 统一验收门槛

### 11.1 Provider 六路径

| Provider | SDK daemon | CLI subprocess |
| --- | ---: | ---: |
| Claude | 必测 | 必测 |
| Codex | 必测 | 必测 |
| OpenCode | 必测 | 必测 |

横切检查：env、stdin 关闭、stdout/stderr drain、abort、cwd、sessionId、baseUrl、runtime snapshot、frontend_ready。

### 11.2 配置和迁移

必须覆盖：

- atomicity；
- idempotency；
- unknown fields；
- malformed JSON/TOML；
- external edits；
- backup/rollback；
- concurrent read-modify-write；
- Windows/POSIX；
- secret 不进入普通日志、备份和诊断包。

### 11.3 协议

- `UpstreamAction` / `DownstreamEvent`；
- generated protocol constants；
- typed payload；
- 重复注册 fail-fast；
- 禁止新增协议字符串字面量。

### 11.4 性能

任何性能收益声明必须包含基线和差分：

- bundle analyzer；
- raw/gzip/brotli；
- JCEF first paint；
- 首次可交互；
- streaming FPS 和 long task；
- 历史恢复和 scroll anchoring；
- Gradle clean/warm build 分位数。

### 11.5 测试

- Java：JaCoCo；
- Webview：Vitest coverage；
- ai-bridge：c8；
- 关键用户流 E2E；
- 重点看 branch coverage 和失败路径，不使用测试文件数作为覆盖率。

### 11.6 安全

- trust boundary/provenance；
- tool 权限门禁；
- secret redaction；
- 命令参数注入；
- subprocess timeout/output cap；
- marketplace signature/hash；
- 配置回滚。

### 11.7 可访问性

- axe 自动检查；
- 键盘手工矩阵；
- focus restore；
- nested dialog；
- reduced-motion；
- NVDA/VoiceOver smoke test；
- 流式 aria-live 节流。

---

## 12. 关键代码参考

| 领域 | 路径 |
| --- | --- |
| typed action dispatcher | `src/main/java/com/github/claudecodegui/handler/core/FrontendActionDispatcher.java` |
| typed action contract | `src/main/java/com/github/claudecodegui/handler/core/FrontendActionHandler.java` |
| history registry | `src/main/java/com/github/claudecodegui/handler/history/HistoryProviderRegistry.java` |
| history adapter | `src/main/java/com/github/claudecodegui/handler/history/HistoryProviderAdapter.java` |
| settings persistence | `src/main/java/com/github/claudecodegui/settings/CodemossSettingsService.java` |
| Node service caller | `src/main/java/com/github/claudecodegui/handler/NodeJsServiceCaller.java` |
| optional plugin config | `src/main/resources/META-INF/plugin.xml` |
| Gradle Webview build | `build.gradle` |
| Webview bootstrap | `src/main/java/com/github/claudecodegui/ui/WebviewInitializer.java` |
| Mermaid import | `webview/src/components/MarkdownBlock.tsx` |
| synchronous bridge board | `webview/src/bridge/store.ts` |
| protocol generated output | `webview/src/generated/protocol.ts` |

---

## 13. 实施纪律

- 每个方向进入开发前必须独立立项，不把功能、修复、重构和格式化混为一个提交；
- 先建立基线，再承诺性能收益；
- 先定义回滚，再修改用户配置；
- 先复用现有 Adapter/Registry，再考虑新建同义抽象；
- 对 Platform 耦合且难以纯单测的逻辑，补源码结构检查、Plugin Verifier 或 IDE 集成测试；
- 文档中的规模数据应标注统计日期，不作为永久事实。

---

## 14. 落地日志

> Append-only 批次记录，反映 backlog 推进现状；各方向的「状态」行保留原始判定，不回溯改写，落地以本节为准。

### 2026-07-20：P1 动效/无障碍/工具链批次（12 commits，feature/v0.4.8）

| 方向 | commit | 范围 |
| --- | --- | --- |
| useTypewriterStream fix | `360bdef4` | 同步 POP_LIMIT 测试到实现权威值 1500（HEAD 既有红测试，3ff72337 引入遗留） |
| A8 协议收敛 | `9eb0d496` | runtimeProviderCapabilities 6 条 + DependencySection 2 条 dispatch 字面量 → `DOWNSTREAM.*`；新增 `check-event-literals.mjs` 漂移守门 |
| B1 gradle | `dff5092f` | `buildWebview` 声明 inputs/outputs（src/scripts/configs → dist），支持 up-to-date 跳过 |
| T3 rename | `03c7d798` | `exportMarkdown.ts` → `exportSessionJson.ts`（名实不符修正） |
| A11Y1 焦点 | `c09a5388` | `useDialogFocus`（trap/restore/inert/嵌套栈）+ BaseDialog portal 化 + 4 dialog 测试 adapt |
| H5 骨架屏 | `f53a9f2c` | `SkeletonList` 组件 + mcp.less 样式 + McpSettingsSection 接入（真实状态驱动） |
| H2+H6+H7 动效 | `8edc3aad` | H2 grid 折叠 + H6 复制微交互 + H7 reduced-motion 全局收口（base.less 唯一入口） |
| B3 JCEF | `1bd4708d` | bootstrap 单次注入 + hide_panel 并入主 sendToJava 路由（减每标签一 JBCefJSQuery） |
| T2 工具链 | `8934698f` | ESLint flat config + Prettier + lint-staged（匹配现有格式，未全仓 reformat） |
| I18N1+I18N2 gate | `f980e7ae` | `scripts/check-i18n-keys.mjs` 前端 en + 后端 base bundle 统一 baseline 守门 |
| AGENTS 精简 | `1fab7035` | 去一次性债务条目/迁移编号，收敛为纯架构准则 |
| D1+D2 文档 | `0d93b525` | 开发指南 + `.githooks/pre-commit`（lint-staged，容忍 node_modules 缺失） |

验证：webview 1112 测试全绿、Java 编译 + buildWebview 通过。后续守门：新代码下行事件须引用 `DOWNSTREAM.*`（`check-event-literals`）；新前端 lint 经 pre-commit / CI webview-lint job 兜底。

**本批未含（独立立项）**：T1 覆盖率工具接入（JaCoCo/Vitest coverage/c8）、A1 Zustand 迁移、B2 bundle 体积治理、B6 HMR、F2 Skills 可视化、F8 CLI 兼容矩阵等 P2/P3 项；A11Y2 键盘导航（roving tabindex）、A11Y3 流式 aria-live 节流亦未启动。

---

### 2026-07-20：A3 领域拆分第一步·AppearanceSettingsService（1 commit，feature/v0.4.8）

| 方向 | commit | 范围 |
| --- | --- | --- |
| A3 外观+字体领域拆分 | `e0fd8eef` | 提取 `AppearanceSettingsService`（14 常量+6 public+10 private helper），CSS 6 个 public 方法改单行委托 + static `getAppearanceConfigJson` 不动，删除迁移走的常量/private/失效 import（`FontConfigService`/`java.util.Set`）；CSS 净减 ~200 行（2322→~2120） |

**模式 A（Service 注入 CSS 半拆，非直连 ConfigRepository）**：文件缺失时 `CSS.readConfig()` 返回 `createDefaultConfig()` 全局骨架（version/claude/codex），Service 在其上注入 appearance 段，行为与历史逐字等价；直连 repo 会丢全局默认段（行为漂移）。模式 B（Service→ConfigRepository 单向）留待 ConfigRepository 升级为独立 application service 时所有领域 Service 统一迁移。对称既有 `ModelRegistryService`（构造注入 CSS）范式。

验证：新建 `AppearanceSettingsServiceTest` 14 用例（appearance default/normalize/越界 fontSize/非数字 fontSize/hex 过滤/未知字段丢弃/`setAppearanceConfig(null)`、uiFont+codeFont 对称默认与往返、经 CSS `readConfig` override 注入外部写验证委托链下 CAS 仍触发 `ConfigConflictException`）；既有 4 测试（反射签名/`RecordingSettingsService` override 计数/坏 payload `[1,2]` apply 内 catch）经 Java 动态分发保持绿；全量回归零 failure 零 error。

**后续（独立增量）**：ModelRegistry（被 `SessionSendService`/`SessionLifecycleManager`/`GitCommitMessageService`/三 Provider Operations 核心路径调用，爆炸半径大）、Provider/MCP 领域拆分；ConfigRepository 升级独立 application service + in-process 写锁 + F9 migration registry。

---

### 2026-07-20：A3 领域拆分第二步·AiFeatureToggleSettingsService（1 commit，feature/v0.4.8）

| 方向 | commit | 范围 |
| --- | --- | --- |
| A3 AI 功能开关领域拆分 | `4b37249b` | 提取 `AiFeatureToggleSettingsService`（5 KEY 常量 + 10 public），CSS 10 个 public 改单行委托，5 个字面量提升为 KEY 常量；段内 ~138 行逻辑下沉 |

**延续第一步模式 A 半拆**：Service 构造注入 CSS，持久化走 `css.readConfig()/writeConfig()`，Facade 10 个 public 签名不变。**零核心路径耦合**：5 对方法（commit 生成 / MCP gateway / 状态栏 widget / AI 标题生成 4 个 boolean toggle + Smithery API key）都是 readConfig+校验+writeConfig 三件套，不触 Provider/Registry/MCP；`getSmitheryApiKey`/`getMcpGatewayEnabled` 被 MCP market / FeatureFlags 当值消费，不反向调用核心路径。爆炸半径 = 0。

验证：新建 `AiFeatureToggleSettingsServiceTest` 7 用例（4 toggle 默认 true/往返、smitheryApiKey 默认空/往返/null 清除/empty 清除、经 CSS 转发委托链验证）；全量回归零 failure 零 error。候选调研（12 个低耦合领域逐一评估）排除 Prompt Enhancer（helper 链调 Provider Management + Model Registry + DependencyManager，爆炸半径远大于第一步）、Custom Pricing（读侧绕 readConfig 散落 `CustomPricingProvider`，领域不闭合）。

**后续（独立增量）**：#2 Codex Sandbox Mode（76 行，2 public+2 helper+2 常量，结构最像 Appearance+Font，作结构异质性补强）、ModelRegistry/Provider/MCP；ConfigRepository 升级 + in-process 写锁 + F9 migration registry。

---

## 15. 已完成方向总览

> 汇总本文档状态为「已落地 / 核心已落地」的全部方向，按领域分组并附 commit。各方向原始判定与落地细节见 §3–§9 对应章节；日期以代码实际提交日为准（与 §3–§9 的标记日可能不同）。

### P0 安全与数据完整性

| 方向 | 内容 | 日期 / commit |
| --- | --- | --- |
| S2 | PasswordStore 凭证地基（CredentialBackend 抽象 + 容量/降级/日志安全） | 2026-07-17 `60acb930` |
| S3 | NodeJsServiceCaller.executeNodeScript 硬化（分流 / 有界读 / 真 timeout） | 2026-07-17 `7037ae7e` |
| A3 / F9 | ConfigRepository（原子写 + ThreadLocal CAS + malformed quarantine + 多版本 backup） | 2026-07-17 `7ec33f81` |
| A3（领域拆分①） | AppearanceSettingsService（外观+字体，模式 A 半拆：Service 注入 CSS，Facade 6 public 委托不变，CSS 净减 ~200 行） | 2026-07-20 `e0fd8eef` |
| A3（领域拆分②） | AiFeatureToggleSettingsService（AI 功能开关 4 toggle + Smithery key，模式 A 半拆，Facade 10 public 委托不变，零核心路径） | 2026-07-20 `4b37249b` |

### 协议与架构

| 方向 | 内容 | 日期 / commit |
| --- | --- | --- |
| A4 | BaseSDKBridge 核心已落地（三 Provider 继承，仅小范围债务） | 核心已落地 |
| A7 | Provider 历史 Adapter/Registry 已落地（债务清理阶段） | 核心已落地 |
| A8 | 下行事件字面量 → `DOWNSTREAM.*` SSOT + `check-event-literals.mjs` 漂移守门 | 2026-07-20 `9eb0d496` |
| (AGENTS) | 精简为纯架构准则，一次性债务条目外移到独立文档 | 2026-07-20 `1fab7035` |

### P1 动效与无障碍

| 方向 | 内容 | 日期 / commit |
| --- | --- | --- |
| H2 | 思考区 `grid-template-rows 0fr↔1fr` 折叠动画 | 2026-07-20 `8edc3aad` |
| H5 | SkeletonList 骨架屏（真实请求状态驱动） | 2026-07-20 `f53a9f2c` |
| H6 | 复制成功/失败微交互 + focus-visible | 2026-07-20 `8edc3aad` |
| H7 | reduced-motion 全局策略（base.less 唯一入口） | 2026-07-20 `8edc3aad` |
| A11Y1 | Dialog 焦点管理（portal + trap/restore/inert/嵌套栈） | 2026-07-20 `c09a5388` |

### P1 构建 / 工具链 / 文档

| 方向 | 内容 | 日期 / commit |
| --- | --- | --- |
| B1 | buildWebview inputs/outputs（支持 up-to-date 跳过） | 2026-07-20 `dff5092f` |
| B3 早期 | JCEF bootstrap 单次注入 + hide_panel 并入主 sendToJava 路由 | 2026-07-20 `1bd4708d` |
| T2 | ESLint flat config + Prettier + lint-staged | 2026-07-20 `8934698f` |
| T3 | exportMarkdown → exportSessionJson 命名修正 | 2026-07-20 `03c7d798` |
| D1 | 开发指南（环境 / 构建 / 协议 / 三套测试 / verifier / 六路径 / 发布） | 2026-07-20 `0d93b525` |
| D2 | `.githooks/pre-commit`（lint-staged，容忍 node_modules 缺失） | 2026-07-20 `0d93b525` |

### 国际化

| 方向 | 内容 | 日期 / commit |
| --- | --- | --- |
| I18N1 | locale coverage baseline CI 守门（`check-locale-coverage.mjs` + tests.yml job） | 2026-07-17 `408bfb33` |
| I18N1 + I18N2 | 前后端统一 key baseline gate（`check-i18n-keys.mjs`，含后端 base bundle） | 2026-07-20 `f980e7ae` |

### 修复

| 方向 | 内容 | 日期 / commit |
| --- | --- | --- |
| — | useTypewriterStream POP_LIMIT 测试同步到实现权威值 1500（HEAD 既有红测试） | 2026-07-20 `360bdef4` |

### 合并 / 接受边界

- **B5** Mermaid 打包：已并入 B2（multi-chunk / singlefile 决策），不独立落地。

> **剩余 backlog**：P1 剩 T1 覆盖率工具接入（JaCoCo / Vitest coverage / c8）、A11Y2 键盘导航（roving tabindex）、A11Y3 流式 aria-live 节流；P2 A3 领域 Service 拆分（①外观+字体 `e0fd8eef`、②AI 功能开关 `4b37249b` 已落地，剩 Codex Sandbox Mode / ModelRegistry / Provider / MCP）/ A5 IntelliJ EP / F8 CLI 兼容矩阵 / F2 Skills 可视化 / F4 历史增强 / B3 typed bootstrap payload / F3 标签持久化（详见 §10）。

