# CC GUI 综合优化方向（源码审查修订版）

> 审查基线：`feature/v0.4.8` 工作区，2026-07-17。
>
> 本文用于维护优化 backlog 和实施优先级，不是可直接照抄的实现说明。任何条目进入开发前，仍需形成独立设计、测试矩阵和可回滚实施计划。

---

## 0. 当前工作区实施进度（2026-07-22）

> 核查基线：`feature/v0.4.8` 当前工作区，**包含未提交改动**。本节记录当前真实实现状态；带 `TBD` 的落地日志、Phase A、基线、先导和 PoC 均不等同于完整完成。
>
> 状态口径：✅ 已按当前目标基本落地；🟡 已有阶段性实现但验收或主体能力未闭环；❌ 主体未实现。

### 0.1 P1-P4 总览

| 优先级 | 当前结论 | 完成情况 |
| --- | --- | --- |
| P1 | 🟡 大部分落地，质量门禁尚未全部闭环 | 5 项完成，3 项部分完成 |
| P2 | 🟡 基础能力已分批推进，完整功能仍有明显缺口 | 2 项完成，5 项部分完成 |
| P3 | 🔴 仅完成少量基线或局部实现 | 0 项完整完成，3 项部分完成，3 项未实现 |
| P4 | 🔴 仅有近期基础设施先导 | 0 项完整完成，2 项部分完成，2 项未实现 |

完整逐项状态、证据和下一步见 [§10 修订后的优先级](#10-修订后的优先级)。

### 0.2 当前验证快照

| 验证项 | 当前结果 | 结论 |
| --- | --- | --- |
| `webview npm run check:event-literals` | 372 个文件、141 条下行事件、0 漂移 | F2 新增事件已从 Java 枚举生成并通过协议字面量漂移检查 |
| `webview npm run check:style` / `npm run lint` | 当前分支无 `check:style` script；全量 lint 为 16 errors、88 warnings | 均为非 F2 文件的既有阻断；F2 目标文件 ESLint 通过，未在本批次扩散修复范围 |
| `webview npm run test` | 全量通过 | 包含 `SkillEditorDialog.test.tsx` 6 个用例，Vitest 与 `tsconfig.test.json` 类型检查均通过 |
| Java Settings/Version/Resolver/Health/Provider targeted tests | 通过 | 已覆盖诊断包多 entry ZIP 可读性与结构化脱敏 |
| B1 三轮 Gradle/Webview profiling | 强制 Gradle 中位数 22.577s；增量 8.002s；直接 Webview 14.593s | `docs/build-performance-baseline.md` 已固化环境、命令、原始样本、热点和复测口径 |
| `ai-bridge npm run lint` | 通过，有 warnings | 工具链先导可用 |
| `ai-bridge npm run typecheck` | 通过 | 有效 `tsconfig.json` 对全部 TS 执行 `strict` 检查，JS 通过 `// @ts-check` 渐进纳入 |
| `ai-bridge npm run test` | 421 项：420 pass、1 skipped、0 fail | JS 主体回归通过，不代表 T4 全量迁移完成 |
| F2 Skills 定向验证 | Java 23 项：21 pass、2 个 Windows symlink 条件跳过；前端 6/6；目标 TypeScript/ESLint、i18n 与协议检查通过 | handler 契约、Provider 字段、必填、容量、no-op、CRLF、缺失 body 和非法 JSON 类型等边界均已覆盖，S2-5 完成 |
| `./gradlew test` | 1640 项：1628 pass、10 skipped、2 fail | F2 测试全部通过；仅 `SemanticContextExtensionContractTest` 两个非 F2 基线用例失败（Python collector descriptor / provider language-plugin dependency），未在本批次越界修复 |

### 0.3 未完成项后续实施顺序

以下顺序是后续实现的唯一执行队列；完成一项后应同时更新本节、§10 状态和 §14 落地日志。

#### S0：立即处理的正确性与质量阻断

- [x] **S0-1 修复 F6 诊断包导出**：`DiagnosticBundleService.addJsonEntry()` 已改为直接写入 UTF-8 字节，不再提前关闭 `ZipOutputStream`；结构化递归脱敏保持 JSON 合法，并补充多 entry ZIP 可读性和脱敏测试。
- [x] **S0-2 修复 T4 TypeScript 工具链**：已增加有效 `tsconfig.json`，`typecheck` 对 TS 全量执行 `strict` 检查，并通过 `// @ts-check` 渐进检查已迁移 JS；lint/format glob 已覆盖 `.ts`。
- [x] **S0-3 清零 Webview lint errors**：当前 17 个 error 已清零（保留 90 个非阻断 warning），并建立 `.github/workflows/tests.yml` 中真实执行 `npm run lint` 的 `webview-lint` job。
- [x] **S0-4 接入协议 SSOT CI gate**：已建立独立 `protocol-ssot` job，先从 Java 枚举生成 `generated/protocol.ts`，再执行 `check:event-literals`，禁止下行事件字面量回归。
- [x] **S0-5 修复 A11Y3 流式通知**：`isUserAtBottomRef` 已从 `useScrollBehavior` 贯穿到 `MessageList`；流式内容通过 ref 供稳定 interval 读取，结束时保留最终摘要，并补 5 个定时器/生命周期测试。

#### S1：完成 P1 闭环

- [x] **S1-1 B1 build profiling**：已保存三轮 Gradle 强制/增量与直接 Webview 构建基线，固化环境、source/diff 指纹、原始样本、中位数、热点和后续复测口径。
- [x] **S1-2 T2 format/lint 全链路守门**：新增 `check:style` 统一全量 ESLint 与变更文件 Prettier；pre-commit 复用同一范围自动修复，`webview-lint` CI 从守门引入提交起增量检查并已同步开发文档。
- [x] **S1-3 A8 协议漂移守门闭环**：失败诊断已输出文件、行列、原始字面量和应替换的 `DOWNSTREAM.*` 常量，并补多文件、多命中、映射提示及退出码测试。
- [x] **S1-4 I18N 统一 gate 对齐**：CI 已切换到 `scripts/check-i18n-keys.mjs` 统一检查前端 locale 与后端 bundle；旧前端专用脚本和第二份 baseline 已移除，当前存量缺口冻结到统一 baseline。

#### S2：完成 P2 基础架构与用户能力

- [x] **S2-1 B3 typed bootstrap payload**：已定义后端权威 bootstrap DTO/schema，通过 `webview.bootstrap` 单一 typed 下行事件发送字体、语言、外观与头像快照；`WebviewInitializer` 已移除业务初始化 JavaScript 拼接。
- [x] **S2-2 F8 CLI 兼容矩阵**：已建立三 Provider compatibility manifest SSOT、provider-specific parser registry、未知/更高版本策略、Ed25519 签名远程更新、缓存防回滚与离线 fallback，并对称接入三条 CLI 探测路径。
- [x] **S2-3 A3 Settings 拆分收尾**：六个领域 Service 已改为直接依赖 `ConfigStore`，`CodemossSettingsService` 仅保留兼容 Facade；已完成逐级 migration registry、同路径进程内共享锁、原子 `update()` 与领域所有权契约验收（`19457260`）。
- [x] **S2-4 A5 IntelliJ EP 验收**：`SemanticContextProvider` 与 `ClassNavigationProvider` 均使用动态 EP，Java/Python 实现仅由可选 descriptor 加载；EP 契约、失败隔离、无插件 fallback 与 IDEA/PyCharm/WebStorm/Ultimate Plugin Verifier 矩阵均已通过。
- [x] **S2-5 F2 Skills 查看/编辑闭环**：已完成 Provider-specific 5/7 字段 schema、安全路径解析、未知 YAML/注释/顺序/Markdown body 尽量保留、SHA-256 revision 冲突、原子写/备份/写后验证/失败回滚及解析失败不覆盖；typed handler/协议枚举与 schema-driven Webview 编辑器已闭环。handler 契约及 Provider 字段、必填、容量、no-op、CRLF、缺失 body、非法 JSON 类型等边界均已覆盖。
- [ ] **S2-6 F4 历史增强闭环**：在搜索基础上实现归档 capability、HTML/PDF sanitizer 和大会话内存上限。
- [ ] **S2-7 F3 标签页持久化闭环**：实现颜色、固定状态、完整 session/provider/runtime/mode snapshot、IDE 重启恢复和不可用降级。

#### S3：推进 P3 数据驱动优化

- [ ] **S3-1 F6 完整 telemetry**：在修复诊断包后补齐采样、保留期限、隐私开关、SDK/CLI 差异及三 Provider 六路径指标覆盖。
- [ ] **S3-2 B2 multi-chunk/singlefile 决策**：基于 analyzer、JCEF 加载约束和缓存收益形成量化决策，再实施拆包或记录保留 singlefile 的验收依据。
- [ ] **S3-3 B4 WatchService**：在 `ConfigRepository` 现有 mtime + size/CAS 基础上增加 watcher、debounce、生命周期清理和外部修改测试。
- [ ] **S3-4 B6 Webview HMR**：实现开发 URL 切换、Vite dev server 加载、失败回退打包资源和 CSP 开发策略。
- [ ] **S3-5 A1 Zustand**：先量化 Context 重渲染和状态依赖，再按领域渐进迁移；不得把后端业务权威迁入前端 store。
- [ ] **S3-6 H1/H3 高级动画**：仅在存在 CSS 无法满足且有性能基线的数据后推进第三方动画库和批量 stagger。

#### S4：推进 P4 长期生态

- [ ] **S4-1 F1 第三方 Provider 契约设计**：完成 Provider EP、ABI/version、capability schema、签名/信任/沙箱及 classloader 升级卸载策略。
- [ ] **S4-2 T4 ai-bridge 全量 TypeScript**：按模块迁移现有 108 个 `.js` 文件，保持 NDJSON 契约和 421 项测试基线，禁止只改扩展名。
- [ ] **S4-3 动态 Node/Webview Provider 扩展**：实现 adapter package 发现、Node 生命周期、动态前端资源和 CSP/ABI 兼容，不再依赖约 32 个静态接触点手工修改。
- [ ] **S4-4 F5 社区评分/评论**：仅在账号、服务端、内容治理、隐私和滥用防护具备后启动。

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

**状态：暂缓，仅允许局部 PoC。（2026-07-21 评估：维持暂缓——所有当前动画需求已由 CSS 满足，framer-motion 将增加 ~189.5 kB bundle 且与 B2 体积治理方向冲突。）**

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

**状态：批量 stagger 暂缓；入场动画已落地（CSS）；出场动画已落地（2026-07-21，纯 CSS，零 bundle 增量）。**

`MessageList` 已通过 `animatedEntryKeys` 控制新消息动画。历史恢复、搜索跳转和流式更新不应逐条 stagger。

实施边界：

- 仅对单条新消息、非历史恢复、非流式 token 更新应用动画；
- 大批量恢复达到阈值后直接渲染；
- 不允许动画改变最终高度计算和 scroll anchoring；
- 删除/回退动画必须验证焦点、搜索锚点和消息虚拟化兼容性。

**出场动画落地（2026-07-21）**：

- `MessageList` 新增 `exitingMessages` 缓存 + `prevUnitMapRef`：当消息从 `visibleMessageUnits` 消失时，保留其数据 160ms（对齐 `--dlg-out: 0.16s`）继续渲染，播放 `messageFadeOut` 淡出后卸载。
- CSS `.message.animate-out` 仅 `opacity` 变化（transform 不变），严格不改变高度，保护 scroll anchoring。
- 复用 BaseDialog 的 `leaving` 状态模式（`setTimeout` 延迟卸载），无 JS 动画库依赖。
- reduced-motion 下由 `base.less` 全局策略退化为瞬时。
- 验证：bundle 仅 +1.27 kB（6,133.45→6,134.72），1112 webview 测试全绿，TypeScript 零 error。

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

**状态：已完成（2026-07-22，S2-3，`19457260`）。`ConfigRepository` migration registry、进程内共享路径锁与六领域 `ConfigStore` 所有权均已验收；`CodemossSettingsService` 保留兼容 Facade。**

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
- **S2-3 收尾（2026-07-22，`19457260`）**：新增 `ConfigStore` 应用层抽象，六个领域 Service 不再反向依赖 Facade，写入统一收口到锁内 `update()`；`ConfigRepository` 以规范化路径共享 `ReentrantLock`，完整串行化 read-modify-write，并保持外部修改 CAS、backup、quarantine 与 unknown field 透传。`DomainSettingsOwnershipContractTest` 验收唯一存储实现、构造依赖和 Facade 公共调用面。

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

**状态：✅ S2-4 已完成（2026-07-22）。**

落地：

- `SemanticContextProvider.EP_NAME` 是语义上下文 EP 的 SSOT，`ContextCollector` 每次读取动态 extension snapshot，不静态持有可选插件实例；
- 单个语义 provider 异常被隔离，无 provider 时继续平台 fallback；
- Java/Python 收集器分别只在 `java-features.xml` / `python-features.xml` 注册；
- Java PSI 类导航从 core 反射加载迁移为 core-safe `ClassNavigationProvider` 动态 EP，Java 实现仅在 `java-features.xml` 注册；
- Plugin Verifier 已覆盖 IC `243.22562.145`、PC `243.21565.199`、WS `243.21565.180`、IU `262.6228.19`，四个目标均为 `Compatible`。

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
- npm script `check:event-literals` 接入 `webview/package.json`；当前 ALLOWLIST 为空，全仓 0 漂移（147 条 DOWNSTREAM）。

后续守门要求：

- 任何新代码禁止以字符串字面量写出 `DOWNSTREAM` value，必须从 `generated/protocol.ts` 引用；2026-07-22 已建立 `protocol-ssot` CI job，先运行 `generate-protocol-types.mjs` 生成 `protocol.ts`，再执行 `check:event-literals`。`937927da` 进一步补齐 file:line:column 诊断、精确 `DOWNSTREAM.*` 替换建议、脚本错误退出码和多文件回归测试。
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

**状态：已完成（2026-07-22 当前工作区，未提交）。**

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

当前落地进度：

- 后端以 `SkillFrontmatterField` + `SkillDocumentSchema` 作为字段与 Provider schema 权威，Claude/OpenCode 使用 7 字段，Codex 使用 Agent Skills 5 字段；
- `SkillDocumentCodec` 解析并最小化重写 frontmatter，未知字段、注释、原有顺序和 Markdown body 尽量保留；
- `SkillDocumentPathPolicy` 限制 Provider 所有目录，拒绝 traversal、symlink、非 `SKILL.md` 文件与真实路径逃逸；
- 保存使用完整内容 SHA-256 revision 检测外部修改，执行原子替换、`.codemoss.bak` 备份、写后重新解析验证与失败回滚；YAML 解析失败时不进入覆盖路径；
- 上行 `get_skill_document` / `save_skill_document` 使用 `FrontendActionHandler<T>`，下行 `skill.document` / `skill.save_result` 使用 `DownstreamEvent`，前端协议常量由 Java 枚举生成；
- 前端 `SkillEditorDialog` 完全按后端字段描述渲染 text/textarea/boolean/string-list 控件，仅回传变更字段与 Markdown body，并通过 `requestId` 忽略过期响应；冲突时保留草稿并要求 Reload；
- `SkillDocumentActionHandlerContractTest` 覆盖 action、payload type 与委托契约；Service 测试补齐 Provider 不支持字段、必填、容量、no-op、CRLF、缺失 body、非法 JSON primitive/list item 等边界；
- 验收结果：Java F2 定向 23 项中 21 pass、2 个 Windows symlink 条件跳过；Webview 全量测试与类型检查通过，编辑器组件 6/6，目标 ESLint、i18n 和协议漂移检查通过；全量 `./gradlew test` 的 F2 用例全部通过，仅保留 2 个与本项无关的 `SemanticContextExtensionContractTest` 基线失败。

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

**状态：已完成 S2-2，2026-07-22，commit `e3de1b8a`。**

已落地：

- `CliVersionParser` strategy + fail-fast `CliVersionParserRegistry`，三 Provider 分别解析 labelled、前缀、prerelease/build metadata 与数字 fallback 输出；
- `cli-compatibility-manifest.json` 作为 compatibility SSOT，完整覆盖三 Provider 的 minimum/maximum/blocked/unknown/higher policy；
- `CliCompatibilityService` 在后端统一解释 compatible、blocked、unsupported、unknown 与 ahead 结论，前端不参与版本业务判断；
- 内置 manifest + Ed25519 detached signature，远程更新仅接受有效签名和不低于当前 trusted revision 的 manifest；
- 有效签名缓存、原子单文件替换、网络/签名/schema/旧 revision 失败时回退 cached remote 或 bundled manifest；
- `CliCompatibilityManifestUpdater` 在 pooled thread 中每个 IDE 进程只刷新一次，不阻塞项目启动；
- Claude/Codex/OpenCode 三条 CLI `--version` 探测路径对称接入兼容判定，契约测试守住三 Provider 与 updater 注册。

Resolver 去重 spawn 仍按原边界处理：需先形成独立生命周期与缓存收益证据，不属于本次 S2-2。

### F9：配置 schema 迁移

**状态：已完成（2026-07-22，随 S2-3 落地，`19457260`）。**

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

**落地记录（2026-07-22，`19457260`）：**

- `ConfigSchema` 以 `schemaVersion` 为插件自有配置版本 SSOT，当前版本为 2；registry 严格执行 `0 → 1 → 2` 逐级幂等迁移，并在构造期拒绝重复、缺失和非逐级 migration。
- `0 → 1` 清理旧 `version` 字段；`1 → 2` 将旧 `smitheryApiKey` 明文迁移到 `PasswordStore`，安全后端不可用时保留明文并停在版本 1，恢复后自动续迁；已有安全凭证优先，旧明文不得覆盖新值。
- 非负 JSON integer 之外的版本值和未来版本均 fail-fast，不进入 malformed quarantine；迁移过程保留 unknown fields，错误与日志不输出 secret。
- `ConfigRepository` 自动持久化已完成的部分迁移，CAS 检查早于 backup rotation；同路径 repository 共享进程内锁，领域写操作使用 `ConfigStore.update()` 避免 lost update。

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

**状态：已完成 S1-2 增量守门。**

- ESLint/Prettier/lint-staged 已接入，并保持现有格式、未做全仓 reformat；
- 2026-07-22 已清零 17 个 Webview lint error，`npm run lint` 以 0 error、90 warning、退出码 0 通过；
- `npm run check:style` 统一执行全量 ESLint，并仅对当前工作区、staged 或 CI revision range 内的变更文件执行 Prettier 检查；
- `.githooks/pre-commit` 继续用相同 glob 对 staged 文件执行 Prettier/ESLint 自动修复；`.github/workflows/tests.yml` 的 `webview-lint` job 改为执行 `npm ci` + `npm run check:style`；
- CI 使用完整 Git 历史，并以 `check-style.mjs` 的引入提交作为 adoption baseline，避免追溯阻断 501 个历史格式偏离，同时确保守门启用后的新增变更不能绕过格式检查。

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

**状态：✅ S2-1 已完成。**

不把多段 `executeJavaScript()` 机械拼成一个大脚本。推荐：

1. 页面加载时仅建立 bridge/query 基础函数；
2. `frontend_ready` 后由后端下发 backend-authoritative bootstrap payload；
3. 统一使用 `DownstreamEvent` 和 `HandlerContext.dispatchEvent(...)`；
4. payload schema 纳入 SSOT；bootstrap payload 只是起点，最终目标是后端单一来源生成全部上行/下行 payload 的字段结构与默认值，消除前后端各写解析器的漂移（承接 AGENTS.md 总则三 payload SSOT 债务）；
5. 明确幂等、顺序和单项失败隔离。

S2-1 实际落地：后端新增 `WebviewBootstrapPayload` 及字段 schema，以 `DownstreamEvent.WEBVIEW_BOOTSTRAP` 经 `HandlerContext.dispatchEvent(...)` 下发单一权威快照；Java schema 生成前端 `WebviewBootstrapPayloadWire`，前端在 `frontend_ready` 前注册订阅并一次解析 payload。`WebviewInitializer` 现只保留 JCEF bridge/query 基础设施，字体、语言、外观、头像六类 pending 字段已删除。实现提交：`fb6202b1`。

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

**状态：已落地并完成 S0-5 正确性修复（2026-07-22）。**

禁止对每个流式 token 发 aria-live 通知。当前实现采用：

- 节流；
- turn 完成摘要；
- 工具状态去重；
- 从 `useScrollBehavior.isUserAtBottomRef` 读取实时底部状态，不在 `MessageList` 硬编码业务结论；
- 高频 `latestContent` 仅更新 ref，不重建 2 秒 interval；
- 流式结束从 ref 读取最终内容并立即播报摘要；
- assertive 仅用于真正错误。

### I18N1：前端 locale

**状态：已落地（2026-07-22，前后端统一 key baseline 已进入 CI）；key coverage 自动守门，翻译质量仍人工评估。**

以 `en` 1484 个键为当前基准：

| locale | 缺失键数 |
| --- | ---: |
| zh | 38 |
| pt-BR | 304 |
| ko | 346 |
| zh-TW | 399 |
| es | 409 |
| fr | 409 |
| hi | 409 |
| ja | 409 |
| ru | 413 |

统一 gate 规则：

- `scripts/check-i18n-keys.mjs` 是唯一权威入口，前端以 `webview/src/i18n/locales/en.json` 为 SSOT；
- 各 locale 缺失键数不得高于 `scripts/i18n-baseline.json`，历史缺口只能持平或下降；
- 2026-07-22 首次接入统一 CI 时，将现有 38 个新增前端键造成的存量缺口冻结为 adoption baseline，未以机器翻译伪造完成；
- 旧 `webview/scripts/check-locale-coverage.mjs` 与 `locale-coverage-baseline.json` 已删除，避免两份 baseline 漂移；
- 本 gate 只检查 key coverage，不判断翻译质量。

### I18N2：后端 locale

**状态：已落地（2026-07-22，与前端共用统一 CI gate）。**

当前 base bundle 272 keys：

- zh：272，缺 0，作为主语言完整性硬约束，不能由 baseline 豁免；
- en：245，缺 27；
- es/fr/hi/ja/ru/zh_TW：各缺 50。

后端以 `src/main/resources/messages/ClaudeCodeGuiBundle.properties` 为 SSOT，由同一
`scripts/check-i18n-keys.mjs` 执行严格键差分与新增键回归检查；CI 的 `i18n` job 无需安装依赖即可运行。

---

## 10. 修订后的优先级

### P0：安全和数据完整性

1. S3 `NodeJsServiceCaller` 生命周期和 timeout；
2. A3/F9 共用的 `ConfigRepository` 原子写入基础；
3. S2 IntelliJ `PasswordSafe` 凭证方案；
4. F9 配置所有权和迁移机制；
5. 为配置、凭证和子进程修复补故障注入、回滚和跨平台测试。

### P1：低风险、高确定性

| # | 方向 | 状态 | 2026-07-22 当前工作区结论 | 下一步 |
| ---: | --- | --- | --- | --- |
| 1 | D1 开发文档 | ✅ | 开发环境、协议、测试、Plugin Verifier、六路径和发布说明已存在 | 按代码变化持续维护 |
| 2 | T2 lint/format | ✅ | `check:style`、pre-commit 与 `webview-lint` CI 已统一全量 ESLint + 增量 Prettier 范围；历史 501 个格式偏离不追溯阻断 | 新增/修改文件持续通过增量格式门禁 |
| 3 | A11Y 基础焦点和键盘 | ✅ | focus trap、焦点恢复、roving tabindex 与稳定流式播报均已实现 | 维护定时器、动态底部状态和卸载清理回归测试 |
| 4 | I18N baseline CI | ✅ | 前端 locale + 后端 bundle 已统一由 `check-i18n-keys.mjs` 守门，旧重复 gate 已移除 | 持续清偿 baseline 缺口并人工审查翻译质量 |
| 5 | `exportMarkdown.ts` 命名修正 | ✅ | 已更名为 `exportSessionJson.ts` | 无 |
| 6 | B1 build profiling + inputs/outputs | ✅ | `buildWebview` inputs/outputs 有效；三轮强制/增量/Webview 基线与热点已保存 | 后续优化按基线分别验证 daemon、cache 与 configuration cache |
| 7 | H2/H5/H6/H7 | ✅ | 局部 CSS 动效、Skeleton、反馈和 reduced-motion 已落地 | 仅维护回归测试 |
| 8 | A8 下行协议 SSOT | ✅ | `protocol-ssot` CI 先按 Java 枚举生成类型，再以文件、行列和 `DOWNSTREAM.*` 替换建议报告漂移；生产代码当前 0 漂移 | 持续维护协议生成与诊断回归测试 |

### P2：基础架构完善后

| # | 方向 | 状态 | 2026-07-22 当前工作区结论 | 下一步 |
| ---: | --- | --- | --- | --- |
| 1 | A3 Settings 领域拆分 | ✅ | 六个领域 Service 直接依赖 `ConfigStore`，Facade 仅保留兼容调用面；migration registry、同路径进程内锁和所有权契约已完成 | 已完成（`19457260`） |
| 2 | A5 IntelliJ EP | ✅ | 语义上下文与类导航均使用 core-safe 动态 EP；Java/Python 可选 descriptor 隔离、失败隔离、fallback 与四 IDE Verifier 矩阵均已验收 | 已完成（`dd9bc4b4`、`d0d1fd78`、`7e2eef35`） |
| 3 | F8 CLI 兼容矩阵 | ✅ | 三 Provider parser registry、compatibility manifest SSOT、未知/更高版本策略、Ed25519 签名更新、revision 防回滚、缓存与离线 fallback 已对称接入 CLI 探测 | 已完成（`e3de1b8a`） |
| 4 | F2 Skills 可视化 | ✅ | Provider-specific 5/7 字段 schema、安全读写服务、typed 协议/handler、schema-driven 编辑器、revision 冲突与回滚已闭环；handler 与输入边界测试、Webview 全量回归均已完成 | 已完成（工作区 `TBD`） |
| 5 | F4 历史增强 | 🟡 | 搜索弹窗已实现，归档及 HTML/PDF 导出未完成 | S2-6 |
| 6 | B3 typed bootstrap payload | ✅ | 后端 DTO/schema 生成前端 wire 类型，`webview.bootstrap` 单一 typed 事件下发完整快照；`WebviewInitializer` 已移除业务初始化脚本拼接 | 已完成（`fb6202b1`） |
| 7 | F3 标签页持久化 | 🟡 | 仅有顺序持久化和 Provider 降级日志，完整标签 snapshot 未完成 | S2-7 |

### P3：有基线数据后

| # | 方向 | 状态 | 2026-07-22 当前工作区结论 | 下一步 |
| ---: | --- | --- | --- | --- |
| 1 | A1 Zustand | ❌ | 未引入 Zustand，仍使用多层 React Context Provider | S3-5 |
| 2 | H1/H3 高级动画 | 🟡 | H3 入场/出场动画已有局部实现；H1 第三方动画库和批量 stagger 仍暂缓 | S3-6 |
| 3 | B2 multi-chunk/singlefile | 🟡 | 已有 visualizer 基线，但仍启用 `viteSingleFile()`，`manualChunks` 未配置 | S3-2 |
| 4 | B4 WatchService | ❌ | 当前只有 mtime + size/CAS snapshot，没有 WatchService | S3-3 |
| 5 | F6 完整 telemetry 仪表盘 | 🟡 | 指标、Dashboard、熔断器和可读诊断包已存在；采样、保留期限、隐私开关和六路径策略未完整 | S3-1 |
| 6 | B6 Webview HMR | ❌ | 没有 JCEF dev URL、Vite dev server 加载和生产资源 fallback | S3-4 |

### P4：长期生态

| # | 方向 | 状态 | 2026-07-22 当前工作区结论 | 下一步 |
| ---: | --- | --- | --- | --- |
| 1 | F1 第三方 Provider 生态 | 🟡 | capability descriptor、接入清单和六路径契约测试已形成先导；第三方 ABI/EP/安全生命周期未实现 | S4-1 |
| 2 | F5 社区评分/评论 | ❌ | 缺少账号、服务端和内容治理，当前路线明确暂缓 | S4-4 |
| 3 | T4 ai-bridge 全量 TypeScript | 🟡 | 当前约 108 个 `.js`、1 个 `.ts`；有效 `tsconfig.json` 已建立，TS 全量 `strict`、JS 以 `// @ts-check` 渐进纳入，但主体迁移仍未完成 | S4-2 |
| 4 | 动态前端/Node Provider 扩展体系 | ❌ | Node descriptor、Java 装配和 Webview 资源仍为静态构建/手工注册 | S4-3 |

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
| I18N gate CI 对齐 | `ab9383bf` | CI 切换到统一前后端 gate，刷新 adoption baseline，并删除旧前端专用脚本与重复 baseline |
| AGENTS 精简 | `1fab7035` | 去一次性债务条目/迁移编号，收敛为纯架构准则 |
| D1+D2 文档 | `0d93b525` | 开发指南 + `.githooks/pre-commit`（lint-staged，容忍 node_modules 缺失） |

验证：webview 1112 测试全绿、Java 编译 + buildWebview 通过。后续守门：`webview-lint` CI job 已按 S0-3 建立；`protocol-ssot` CI job 已按 S0-4 建立，新代码下行事件须引用 `DOWNSTREAM.*` 并通过 `check:event-literals`。

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

### 2026-07-20：A3 领域拆分第三步·CodexSandboxModeSettingsService（1 commit，feature/v0.4.8）

| 方向 | commit | 范围 |
| --- | --- | --- |
| A3 Codex 沙箱模式领域拆分 | `c58b3b46` | 提取 `CodexSandboxModeSettingsService`（2 KEY 常量 + 2 mode 常量 + 2 public + 2 private helper），CSS 2 个 public 改单行委托，2 个字面量（`codexSandboxMode`/`default`）提升为 KEY 常量；段内 ~76 行逻辑下沉 |

**延续①②模式 A 半拆**：Service 构造注入 CSS，持久化走 `css.readConfig()/writeConfig()`，Facade 2 个 public 签名不变。**结构异质性验证**：与①②不同，本领域是 per-project/default 双层 key 的 string mode（非 boolean toggle、非单段 JsonObject），读取优先级 projectPath > default > 平台默认；任何非法 mode 回退平台默认（Security F 决策，非 default 段 —— 逐字迁移保持等价）。**零核心路径耦合**：`PlatformUtils.isWindows()` 仅作平台默认值决策的值读取；调用点（CliSettings/CodexCliSession/CodexSDKBridge/ProjectConfigHandler）全经 CSS public 委托，零改动。爆炸半径 = 0。

验证：新建 `CodexSandboxModeSettingsServiceTest` 8 用例（平台默认动态期望/两种 mode 往返/无效 set 抛 IllegalArgumentException/优先级 projectPath>default/非法存储回退平台默认/null projectPath 只写 default/经 CSS 转发委托链）；全量回归零 failure 零 error（1m21s）。

**后续（独立增量）**：ModelRegistry（核心路径爆炸半径大，谨慎）/ Provider / MCP；ConfigRepository 升级 + in-process 写锁 + F9 migration registry。

### 2026-07-20：A3 领域拆分第四步·ModelRegistrySettingsService（1 commit，feature/v0.4.8）

| 方向 | commit | 范围 |
| --- | --- | --- |
| A3 模型注册表领域拆分 | `46c4f55f` | 提取 `ModelRegistrySettingsService`（1 KEY 常量 + 3 public + 6 private helper），CSS 3 public 改单行委托，字面量 `models` 提升为 KEY 常量；段内 ~151 行逻辑下沉 |

**延续①②③模式 A 半拆**：Service 构造注入 CSS，持久化走 `css.readConfig()/writeConfig()`，Facade 3 public 签名不变。**爆炸半径「看似大、实测极小」**：Explore 彻底调研确认 22 外部调用点（SessionSendService / SessionLifecycleManager / GitCommitMessageService / 三 Provider Operations / ChatWindowDelegate 等）+ 8 处测试调用全走 CSS public API，3 委托后外部 0 改动；`RecordingSettingsService` override（`ModelRegistryServiceTest`）经 Java 动态分发仍生效。**静态 `ModelRegistryService` 不合并**：它是「payload codec + handler orchestration」（serialize/parse 给前端下发 + 3 ActionHandler 实例 API），与本类「persistence + validation + merge orchestration」职责正交；`getModelRegistryJson` 继续调静态 `serialize` 以下发 `supportedReasoningLevels` 派生字段（契约 H3，否则前端 ReasoningSelect 整体隐藏）。**AI Feature 交叉依赖方案 A**：`CSS.normalizeAiFeatureClaudeModel` 调 `getModelRegistry().find()` 单点，留在 CSS 经动态分发走委托，零改动。**parseModelRegistry NPE 语义逐字保留**：写盘路径 `supports1MContext` 缺 null 守卫，与静态 `parse`（payload 路径有守卫）有细微差异；迁移期间不顺手统一（NPE 是独立 bug，§13 单一职责）。

验证：新建 `ModelRegistrySettingsServiceTest` 7 用例（只读默认/合法往返/无效拒绝/冲突拒绝/空用户层合法/H3 派生字段守门/委托链）；现有 6 类 ModelRegistry 测试（`CodemossSettingsServiceModelRegistryTest`/`ModelRegistryServiceTest`/`ModelRegistryServiceSerializeTest`/`ModelRegistryPayloadFieldTest`/`ModelRegistryActionHandlerTest`/`CodemossSettingsServicePromptEnhancerConfigTest`）零改动全绿；全量回归零 failure 零 error（1m26s）。

**后续（独立增量）**：Provider / MCP；ConfigRepository 升级 + in-process 写锁 + F9 migration registry。

### 2026-07-20：A3 领域拆分第五步·McpSettingsService（1 commit，feature/v0.4.8）

| 方向 | commit | 范围 |
| --- | --- | --- |
| A3 MCP 服务器配置领域拆分 | `97f0396c` | 提取 `McpSettingsService`（持有并构造 McpServerManager，6 public 委托；构造注入 CSS+Gson+ClaudeSettingsManager），CSS 6 public 改单行委托 + 删 mcpServerManager 字段/构造块（净减 ~20 行），边界外 getCodexMcpServerManager 不动 |

**延续①~④模式 A 半拆，形态差异**：前四步从 CSS 内联提取逻辑；MCP 逻辑早已在独立 `McpServerManager`（498 行）中，CSS 此前仅持字段 + 6 单行委托 + 构造块。本类是「持有并构造 McpServerManager 的领域入口」，构造 + 所有权从 CSS 迁入，Manager 类体不动（对称第四步不合并静态 ModelRegistryService）。**双路径存储（与①~④ config.json 单段不同）**：McpServerManager 以 `~/.claude.json` 为主存储（global + project-level `projects[path].mcpServers` 合并 + `disabledMcpServers` 过滤，存在才写不创建），仅当其不存在时 fallback `~/.codemoss/config.json#mcpServers`（经注入 configReader/configWriter = CSS readConfig/writeConfig → ConfigRepository 原子写 + CAS）；本类经 Manager 间接走两条路径，自身不碰文件 IO。**爆炸半径极小**：Explore 调研确认 CSS 是全仓唯一 `new McpServerManager` 处，12 调用点（McpServerActionHandlers 6 / RuntimeSharedConfigService 2 / McpGatewayConfigCollector 2 / CliMcpConfig 间接 2）全在 cold path（UI 配置面板 / Gateway refresh / CLI 启动期懒加载），SessionSendService / CodexSDKBridge / EnvironmentConfigurator 零反向调用（对照 Provider 域密集触 send 路径）；6 委托改 `mcpSettingsService.` 后外部 0 改动。**边界外（§F9）**：`getCodexMcpServerManager()` 返回 codex 原生 `~/.codex/config.toml` 的 CodexMcpServerManager，禁止插件擅自迁移，保持原样不经本类。

验证：新建 `McpSettingsServiceTest` 9 用例（反射注入 `PlatformUtils.cachedRealHomeDir` 隔离临时 home —— 该字段是 home SSOT，NodeDetector→WslPathUtil→getHomeDirectory 全链指向临时 home，MCP 的 ~/.claude.json 与 fallback config.json 双路径均隔离，绝不碰真实环境；`getMcpServersReturnsEmptyWhenNoConfig` 兼隔离 canary）：空列表 / upsert+get 往返（fallback config.json）/ 无 id 抛 IllegalArgumentException / delete 往返 / delete 未知返 false / validate 合法 stdio / validate 缺 name 拒绝 / getMcpServersWithProjectPath 透传 / CSS Facade 转发。全量回归 242 类 / 1618 用例零 failure 零 error（8 skipped 历史既有，1m35s）。

**后续（独立增量）**：Provider（爆炸半径大：46 方法 / 触 CodexSDKBridge+SessionSendService send 路径每条消息 / 3 native-file manager 强耦合，需先解决 ClaudeSettingsManager/CodexSettingsManager/OpenCodeSettingsManager 归属 + send 路径集成测试，谨慎）；ConfigRepository 升级独立 application service + in-process 写锁 + F9 migration registry。

### 2026-07-20：A3 领域拆分第六步·ProviderSettingsService（1 commit，feature/v0.4.8）

| 方向 | commit | 范围 |
| --- | --- | --- |
| A3 三 Provider 配置领域拆分 | `ffac99fb` | 提取 `ProviderSettingsService`（持有并构造三 ProviderManager：claude/codex/opencode，~39 public 委托 + 收口 6 个 localConfigAuthorized/runtimeAccessMode 内联方法；构造注入 CSS+Gson+ConfigPathManager+三 settings Manager），CSS 删三 ProviderManager 字段/构造块 + ~39 public 改单行委托 + 6 内联改委托（净减 ~180 行），CODEX_RUNTIME_ACCESS_* 常量留 CSS（30+ 外部引用 0 改动） |

**延续①~⑤模式 A 半拆，形态 = ⑤增强版（持有并构造 Manager 的领域入口 + 内联逻辑收口）**：三 Provider 的 CRUD/激活逻辑早已在独立的 `ProviderManager`/`CodexProviderManager`/`OpenCodeProviderManager` 中，CSS 此前仅持字段 + 单行委托 + 构造块（对称⑤ MCP）；本类持有并构造这三 Manager，类体不动（对称④⑤不合并独立实现类）。**额外收口 6 个 CSS 内联方法**（对称①~④的内联下沉）：codex/opencode 各 `localConfigAuthorized` get/set + `runtimeAccessMode` get，直接读写 `config.json` 的 `<provider>.localConfigAuthorized` 与 `current`/`providers` 段（经注入 CSS readConfig/writeConfig → ConfigRepository 原子写 + CAS）；`isCodexCliLoginAvailable`/`readCodexCliLoginAccountInfo` 的 try-catch + codexSettingsManager 委托一并迁入。**爆炸半径「看似极大、实测极小」**：send 路径密集调用点（SessionSendService:248 / CodexSDKBridge:192/462/684/1120 / EnvironmentConfigurator:498 / CodexSubscriptionQuotaService 方法引用）+ 9 处 ProviderOperations handler 全经 CSS public API，~39 委托改 `providerSettingsService.` 后外部 0 改动（对照④的 22 调用点、⑤的 12 调用点）。**3 settings Manager 共享引用非所有权转移**：ClaudeSettingsManager（另被 McpSettingsService + 多个 Claude settings 委托用）、CodexSettingsManager（另被 getCodexMcpServerManager 的 CodexMcpServerManager 用，§F9 边界）、OpenCodeSettingsManager 全留 CSS 构造，注入本类；**CODEX_RUNTIME_ACCESS_* 常量留 CSS**（30+ 引用含 CodexSubscriptionQuotaServiceTest/CodexSDKBridge/SessionSendService，0 改动，runtimeAccessMode 返回值 SSOT）。**AI Feature provider 部分留 CSS**（promptEnhancer/commitAi 的 provider override + availability 判断）：经动态分发调 getActiveCodexProvider/getActiveClaudeProvider（对称④ normalizeAiFeatureClaudeModel 方案 A），零改动。

验证：新建 `ProviderSettingsServiceTest` 11 用例（反射注入 `PlatformUtils.cachedRealHomeDir` 隔离临时 home）：隔离 canary + 三 Provider 委托 notNull / codex localConfigAuthorized 往返 / setCodexLocalConfigAuthorized 段不存在时创建骨架 / codex runtimeAccessMode 四分支（inactive 无段 / managed active provider / cli_login authorized / cli_login unauthorized）/ opencode 对称（localConfigAuthorized 往返 + runtimeAccessMode managed/inactive）/ CSS Facade 转发。全量回归 243 类 / 1629 用例零 failure 零 error（8 skipped 历史既有，1m30s）。

**后续（独立增量）**：A3 既定六个领域 Service 提取步骤已完成（①~⑥），CSS 从 ~2300 行收敛到领域 Service 矩阵；这不代表 A3 整体完成。仍需 ConfigRepository 升级独立 application service + in-process 写锁（跨线程并发 RMW）+ F9 migration registry（schemaVersion 读写闭环 + 逐级幂等迁移 + secret 脱敏），届时所有领域 Service 可统一切换模式 B（Service→repo 单向）。

---

### 2026-07-20：P2 批次（F8+F2+F4+B3+F3，5 commits，feature/v0.4.8）

| 方向 | commit | 范围 |
| --- | --- | --- |
| F8 Phase A | `02187e9c` | 三 Provider 对称化版本捕获 + 抽取 VersionComparator（CodexCliResolver/OpenCodeCliResolver verify() 返回版本串替代 boolean；ClaudeCliDetector 补 getCachedCliVersion；DependencyManager 委托 VersionComparator；新增测试 16+2+2 全绿） |
| F2 Phase A | `168ac29e` | Skills 只读查看 + typed metadata 下发（SkillService/CodexSkillService scan 补全 7 字段；前端 Skill 类型补 5 字段；SkillsSettingsSection 展开卡片渲染全部字段；i18n 补 5 键；零协议改动） |
| F4 | `ef438ba4` | 历史搜索前端 UI（新增 HistorySearchDialog：搜索输入 + Provider 选择 + 结果列表 + 点击加载；设置页按钮入口；history.less 样式；i18n 补 historySearch 段） |
| B3 Phase A | `1e382d65` | bootstrap 下发通道收敛（DownstreamEvent 新增 6 SESSION_* 事件；ChatWindowDelegate.replaySession 6 路改 dispatchEvent + legacy fallback；前端 subscriber 转发） |
| F3 Phase A | `04777209` | 标签顺序持久化 + Provider 降级日志（TabStateService.TabSessionState 加 tabOrder 字段；ClaudeChatWindow restore 加 provider 空值降级日志） |

验证：webview 108 测试 + 18 设置页测试 + 4 VersionComparator/Codex/OpenCode 测试全绿；Java 编译通过。

---

### 2026-07-20：F6 Phase 1 后端健康检测基础设施（1 commit，feature/v0.4.8）

| 方向 | commit | 范围 |
| --- | --- | --- |
| F6 Phase 1 | `4546ef64` | 后端健康检测基础设施: HealthMetric 枚举(20 指标)、HealthMetricsCollector 线程安全单例、CircuitBreaker 状态机(CLOSED→OPEN→HALF_OPEN)、DiagnosticBundleService 诊断包入口、GetHealthStatusActionHandler + ExportDiagnosticBundleActionHandler、UpstreamAction 2 个 + DownstreamEvent 2 个；ZIP 生命周期与脱敏修复见 S0-1 |
| F6 Phase 2 | `cfd27e33` | 前端仪表盘 UI: HealthDashboardSection 组件(Provider 选择 + 6 指标卡片 + 状态指示器)、设置页「Other」接入、导出诊断包按钮、i18n 20 键 |
| F6 Phase 3 | `62d09a14` | 熔断器对接发送路径: CircuitBreakerManager(per-provider×per-mode)、CliSessionManager/SessionSendService 熔断器检查 + 完成通知、测试 7 用例全绿 |
| A5 | `85937543` | ContextCollector 改 IntelliJ EP: 新增 SemanticContextProvider 接口、Java/Python 收集器实现 EP、plugin.xml 声明 extensionPoint、java-features.xml/python-features.xml 注册、反射→EP 查找 |

### 2026-07-21：P1 收尾批次（A11Y2+A11Y3+T1，3 commits，feature/v0.4.8）

| 方向 | commit | 范围 |
| --- | --- | --- |
| A11Y2 | `7b6fd922` | 键盘导航 roving tabindex: 新增 useRovingTabIndex hook(ArrowLeft/Right/Home/End+orientation)，应用到 4 个 Tab 组件(ProviderTabSection/DualViewSwitcher/AppearanceTab 抽屉/SkillMarketDialog 源 Tab) |
| A11Y3 | `09f7cde2` | 流式 aria-live 节流: 新增 useStreamAnnouncer hook(2s 节流 + turn 完成摘要)，MessageList 接入 + role="log" |
| T1 | `085c4e6f` | 覆盖率工具接入: Java JaCoCo(build.gradle 插件+jacocoTestReport)、Webview Vitest coverage v8(@vitest/coverage-v8+vitest.config.ts 配置)、ai-bridge c8(依赖+脚本)、.gitignore 排除 coverage/ |

验证：`./gradlew jacocoTestReport` 生成 HTML 报告；`npx vitest run --coverage` 生成覆盖率报告；`npx c8 node --test` 生成覆盖率报告。

---

### 2026-07-21：P4 批次（T4 Phase 1 + F1 近端，2 commits，feature/v0.4.8）

| 方向 | commit | 范围 |
| --- | --- | --- |
| T4 Phase 1 | `36e37fbc` | ai-bridge 工具链配置：ESLint flat config（@eslint/js + globals.node + eslint-config-prettier）+ Prettier + 有效 `tsconfig.json`（TS strict、JS `@ts-check` 渐进检查）+ npm scripts（lint/format/test/typecheck）；修复 api-config.test.js 路径 bug；JSDoc 与声明类型补充（provider-registry.js、mcp-gateway/transport 三文件） |
| F1 近端 | `TBD` | Java 端 ProviderCapability 枚举（9 值）+ ProviderAdapter 默认 capabilities() 方法 + 三 Provider 覆盖（Claude 全能力集、Codex/OpenCode 子集）+ ProviderRegistry 能力查询（hasCapability/capabilities/providersWithCapability）+ ProviderCapabilityContractTest（7 用例全绿） |

验证：`npm run lint` 零 error；`npm test` 420/421 通过（1 历史 skip）；`./gradlew test --tests ProviderCapabilityContractTest` 7/7 通过；`npx tsc --noEmit --project jsconfig.json` 2224 个预存 type errors（Phase 1 仅 informational，不守门）。

---

### 2026-07-21：P3/P4 剩余项批次（F1 接入清单 + 六路径契约测试 + B2 基线 + T4 Phase 2 先导，feature/v0.4.8）

| 方向 | commit | 范围 |
| --- | --- | --- |
| F1 统一接入清单 | `TBD` | 新建 `docs/provider-onboarding-checklist.md`，完整记录 Java 后端（15 处修改 + 11 新文件）、ai-bridge（1 新文件 + 1 修改）、前端（3 处修改）共 ~32 个接触点 |
| F1 六路径契约测试 | `TBD` | 新建 `ProviderSixPathContractTest.java` 11 用例：验证三 Provider 注册、六路径 SessionRuntime 注册、SkillService 注册、RuntimePolicy 配置、重复注册 fail-fast、未知 provider fail-fast |
| B2 bundle analyzer 基线 | `TBD` | 集成 `rollup-plugin-visualizer`（`ANALYZE=true` 环境变量启用），产出首次基线：`index.html` 6,133.45 kB / gzip 1,729.97 kB |
| T4 Phase 2 先导 | `36e37fbc` | 迁移 `utils/exit-strategy.js` → `.ts`（添加类型注解 + `as const` 策略类型），安装 `tsx` 作为 TypeScript 加载器，更新 `npm test` 使用 `--import tsx`；当前回归 421 项（420 pass、1 skipped） |

验证：`npm test` 当前 421 项（420 pass、1 skipped、0 fail）；`./gradlew test --tests ProviderSixPathContractTest` 11/11 通过；`ANALYZE=true npx vite build` 产出 `dist/stats.html` 1.17 MB 分析报告；`docs/provider-onboarding-checklist.md` 覆盖 32 个接触点。

---

### 2026-07-21：H1/H3 评估与 H3 出场动画落地（feature/v0.4.8）

| 方向 | commit | 范围 |
| --- | --- | --- |
| H1 评估 | — | 维持暂缓：所有当前动画需求（presence/入场/折叠/脉冲）已由 CSS 满足，framer-motion ~189.5 kB 增量与 B2 体积治理冲突，无 CSS 无法处理的需求 |
| H3 出场动画 | `TBD` | `MessageList` 新增 `exitingMessages` 缓存（消息消失时保留 160ms 播放淡出）+ `MessageItem.shouldAnimateOut` prop + CSS `.message.animate-out`（仅 opacity，保护 scroll anchoring）；复用 BaseDialog leaving 模式，无 JS 动画库 |

验证：`npx vitest run` 1112 全绿；`npx tsc -p tsconfig.json --noEmit` 零 error；`npx vite build` bundle 仅 +1.27 kB（6,133.45→6,134.72）；reduced-motion 由 base.less 全局策略退化为瞬时。

---

### 2026-07-22：S0-1 F6 诊断包正确性修复（feature/v0.4.8）

| 方向 | commit | 范围 |
| --- | --- | --- |
| S0-1 / F6 | `91c88705` | `DiagnosticBundleService` 不再关闭 entry 级 `OutputStreamWriter`，改为直接写入 UTF-8 字节并由 ZIP 流统一管理生命周期；脱敏改为递归遍历 JSON 对象/数组，敏感字段值替换为 `[REDACTED]` 且不修改源对象；新增多 entry ZIP 可读性、JSON 合法性、嵌套脱敏和非敏感字段保留测试 |

验证：`./gradlew test --tests com.github.claudecodegui.health.DiagnosticBundleServiceTest --tests com.github.claudecodegui.health.HealthMetricsCollectorTest --tests com.github.claudecodegui.health.CircuitBreakerTest --tests com.github.claudecodegui.health.CircuitBreakerManagerTest` 通过。

---

### 2026-07-22：S0-2 T4 TypeScript 工具链修复（feature/v0.4.8）

| 方向 | commit | 范围 |
| --- | --- | --- |
| S0-2 / T4 | `36e37fbc` | 新增有效 `tsconfig.json`，对 TS 全量启用 `strict`，保留 `allowJs` 并以 `// @ts-check` 渐进纳入已迁移 JS；`typecheck` 改为真实执行 `tsc --noEmit`；ESLint/Prettier/test glob 覆盖 `.ts`；补 Node/MCP 声明类型并迁移 `utils/exit-strategy.ts` |

验证：`npm run typecheck` 通过；`npm run lint` 0 error（61 个既有 warning）；`npm test` 421 项（420 pass、1 skipped、0 fail）；增量工具链文件 `prettier --check` 通过。S4-2 全量 TypeScript 迁移仍未完成。

---

### 2026-07-22：S0-3 Webview lint 阻断清零与 CI 守门（feature/v0.4.8）

| 方向 | commit | 范围 |
| --- | --- | --- |
| S0-3 / T2 | `fc9b2867` | 以最小 diff 修复 14 个文件中的 17 个 ESLint error，不执行全仓格式化；修复无效初值、`no-this-alias`、重复分支、空接口和正则规则等阻断 |
| S0-3 / T2 | `44f14abf` | 在 `.github/workflows/tests.yml` 新增独立 `webview-lint` job，固定 Node.js 22.12.0，在 `webview` 目录执行 `npm ci` 与 `npm run lint` |

验证：`npm run lint` 0 error、90 warning、退出码 0；`npx tsc -p tsconfig.json --noEmit` 通过；6 个直接相关测试文件共 125 项通过。全量 `npm run test` 的 Vitest 用例本身通过，但工作区既有 `MessageList.tsx` 定时器改动在测试环境销毁后触发 `window is not defined`，不属于本次 S0-3 提交。后续 S1-2 已在 `5a693e65` 完成 format/pre-commit/CI 全链路一致性。

---

### 2026-07-22：S0-4 协议 SSOT CI gate（feature/v0.4.8）

| 方向 | commit | 范围 |
| --- | --- | --- |
| S0-4 / A8 | `22b17f2d` | 在 `.github/workflows/tests.yml` 新增独立 `protocol-ssot` job；从 Java `UpstreamAction` / `DownstreamEvent` 等枚举源运行 `generate-protocol-types.mjs`，生成 `webview/src/generated/protocol.ts` 后执行 `npm run check:event-literals` |

验证：本地按 CI 顺序执行 `node scripts/generate-protocol-types.mjs` 与 `npm run check:event-literals` 通过；生成统计为 217 条上行 action、147 条下行事件，漂移扫描覆盖 375 个生产文件且为 0。S1-3 后续已由 `937927da` 完成文件、行列、原始字面量和 `DOWNSTREAM.*` 替换提示，并补齐退出码回归测试。

---

### 2026-07-22：S0-5 A11Y3 流式播报正确性修复（feature/v0.4.8）

| 方向 | commit | 范围 |
| --- | --- | --- |
| S0-5 / A11Y3 | `d22bc3a7` | 将 `useScrollBehavior.isUserAtBottomRef` 经 `App` / `ChatScreen` 贯穿到 `MessageList`，移除底部状态硬编码；`useStreamAnnouncer` 通过 ref 读取最新内容和实时滚动状态，内容 delta 不再重置 2 秒 interval，流式结束保留最终摘要并清理 interval、RAF 与 aria-live DOM |

验证：新增 5 个 hook 测试覆盖 interval 不重建、2 秒窗口读取最新内容、动态底部状态、最终摘要与卸载清理；`MessageList` 16 项测试、`npm run lint`（0 error / 90 warning）、`tsc --noEmit` 和全量 `npm run test` 均通过。

---

### 2026-07-22：S1-1 B1 构建 profiling 基线（feature/v0.4.8）

| 方向 | commit | 范围 |
| --- | --- | --- |
| S1-1 / B1 | `ba9a0022` | 新增 `docs/build-performance-baseline.md` 与机器可读 JSON；记录环境、source/diff 指纹、三轮命令和原始样本，并规定中位数、本地/CI 分离和后续百分比比较口径 |

验证：Gradle 强制 `buildWebview` 墙钟中位数 22.577s（`:buildWebview` 15.006s）；增量 `UP-TO-DATE` 墙钟中位数 8.002s（task 0.162s）；直接 `npm run build` 墙钟中位数 14.593s（Vite 8.950s）。热点结论为全量构建优先分析 Webview 链路，增量场景优先验证 daemon/配置开销。

---

### 2026-07-22：S1-2 T2 format/lint 全链路守门（feature/v0.4.8）

| 方向 | commit | 范围 |
| --- | --- | --- |
| S1-2 / T2 | `5a693e65` | 新增 `check-style.mjs` 与 `npm run check:style`，统一全量 ESLint + 变更文件 Prettier；`webview-lint` CI 使用事件 revision range 和 adoption baseline，pre-commit 保持同一文件范围的自动修复 |

验证：`npm run check:style -- --staged` 对新增脚本执行 Prettier 并以 0 error、90 warning 完成全量 ESLint；按 CI 环境变量以 `origin/feature/v0.4.8...HEAD` 复测时，脚本正确将 `5a693e65` 识别为 adoption baseline，不追溯检查既有 30 个已提交格式偏离文件。全仓 `npm run format:check` 仍报告 501 个历史文件，本项不通过一次性 reformat 制造巨量 diff。

---

### 2026-07-22：S1-3 A8 协议漂移诊断闭环（feature/v0.4.8）

| 方向 | commit | 范围 |
| --- | --- | --- |
| S1-3 / A8 | `937927da` | 将 `check-event-literals.mjs` 重构为可测试守门器；失败项稳定输出 `file:line:column`、原始字面量和精确 `DOWNSTREAM.*` 替换建议，脚本异常返回 2，协议漂移返回 1 |

验证：新增 5 个 Vitest 用例覆盖注释排除、行列计算、多文件多命中、替换映射、无漂移和无效协议退出码；`npm run check:event-literals` 扫描 375 个生产文件、147 条下行事件且 0 漂移；全量 `npm run test`、`tsconfig.test.json` 类型检查、目标 ESLint 与 Prettier 均通过。

---

### 2026-07-22：S1-4 I18N 统一 gate 对齐（feature/v0.4.8）

| 方向 | commit | 范围 |
| --- | --- | --- |
| S1-4 / I18N1 / I18N2 | `ab9383bf` | 将 CI `i18n` job 切换到 `scripts/check-i18n-keys.mjs`，统一检查前端 `en.json` 与后端 base bundle；删除旧前端专用 gate 和第二份 baseline，并以当前 1484 个前端 SSOT 键刷新 adoption baseline |

验证：`node scripts/check-i18n-keys.mjs --quiet` 返回 0；前端 9 个 locale 与后端 8 个 bundle 全部处于统一 baseline 内，后端 `zh` 272/272 完整；仓库除迁移说明外不再引用旧 `check-locale-coverage.mjs` 或 `locale-coverage-baseline.json`。

---

### 2026-07-22：S2-1 B3 typed bootstrap payload（feature/v0.4.8）

| 方向 | commit | 范围 |
| --- | --- | --- |
| B3（S2-1） | `fb6202b1` | 后端权威 bootstrap DTO/schema + `webview.bootstrap` 单一 typed 事件；移除 `WebviewInitializer` 业务配置脚本拼接与六类 pending 字段 |

验证：Java payload/schema 与 `WebviewInitializer` 源码守门测试通过；前端 bootstrap 订阅及协议生成器定向测试 22 项通过；全量 `npm test`、TypeScript 测试配置类型检查和 `check:event-literals` 均通过；Gradle `compileJava`、`compileTestJava`、`buildWebview` 与定向测试通过。

---

### 2026-07-22：S2-2 F8 CLI 兼容矩阵（feature/v0.4.8）

| 方向 | commit | 范围 |
| --- | --- | --- |
| F8（S2-2） | `e3de1b8a` | 新增三 Provider `CliVersionParser` strategy/registry、严格 manifest codec、后端 compatibility decision facade、Ed25519 detached-signature verifier、remote/cache/bundled repository 与 startup updater；Claude/Codex/OpenCode 三条 CLI 探测路径对称执行兼容策略 |

验证：compatibility 定向测试 17 项通过，相关 Claude/Codex/OpenCode resolver 与 `VersionComparator` 回归通过；全量 `./gradlew test` 通过。`checkstyleMain` 仅被工作区既有 17 条非 F8 违规阻断，F8 新增文件无 checkstyle 报告。

---

### 2026-07-22：S2-3 A3 Settings 拆分收尾（feature/v0.4.8）

| 方向 | commit | 范围 |
| --- | --- | --- |
| A3 / F9（S2-3） | `19457260` | 新增 `ConfigStore` 与版本化 migration registry；六个领域 Service 直接持有配置抽象，Facade 保持公共调用面；同路径共享锁串行化 read-modify-write；Smithery 明文凭证支持安全迁移、延迟恢复和防旧值覆盖 |

验证：migration registry 12 项、`ConfigRepositoryTest` 16 项、领域所有权契约 3 项及全部 Settings Service 定向测试通过；全量 `./gradlew test` 通过。`checkstyleMain` 仅被工作区既有 15 条非 A3 违规阻断，A3 文件无新增 checkstyle 报告。

---

### 2026-07-22：S2-4 A5 IntelliJ EP 验收（feature/v0.4.8）

| 方向 | commit | 范围 |
| --- | --- | --- |
| A5（S2-4） | `dd9bc4b4`、`d0d1fd78`、`7e2eef35` | 动态读取 `SemanticContextProvider` EP 并隔离 provider 失败；新增 core-safe `ClassNavigationProvider` EP，移除 core 对 Java PSI 实现的反射加载；补 optional descriptor、动态加载、无插件 fallback、类加载边界及 verifier 矩阵契约测试 |

验证：`SemanticContextExtensionContractTest` 与 `OpenClassHandlerTest` 通过；`verifyPluginStructure` 和全量 `./gradlew test` 通过。Plugin Verifier 对 IC `243.22562.145`、PC `243.21565.199`、WS `243.21565.180`、IU `262.6228.19` 均报告 `Compatible`，仅保留 4 条既有 deprecated API usage。首次 PC 验证发现 core 中 `JavaClassNavigationSupport` 的 Java PSI 类加载边界问题，迁移到可选 `ClassNavigationProvider` EP 后复验通过。`checkstyleMain` 仍被工作区既有 13 条非 A5 违规阻断，A5 文件无新增违规。

### 2026-07-22：S2-5 F2 Skills 查看/编辑闭环（feature/v0.4.8）

| 子项 | commit | 内容 |
| --- | --- | --- |
| 后端安全读写 | `TBD` | 新增 Provider-specific `SkillDocumentSchema`、`SkillDocumentCodec`、`SkillDocumentPathPolicy` 与 `SkillDocumentService`；保留未知 YAML/注释/顺序/body，实施 SHA-256 revision、原子写、备份、写后验证和失败回滚 |
| typed 协议链路 | `TBD` | 新增 `get_skill_document` / `save_skill_document` typed handlers 与 `skill.document` / `skill.save_result` 下行事件，统一通过 Java 协议枚举和 `HandlerContext.dispatchEvent()` |
| Webview 编辑器 | `TBD` | 新增 schema-driven `SkillEditorDialog`，动态渲染四类后端控件，只提交变更字段；接入 requestId 关联、冲突 Reload、IDE 打开、保存后刷新和 10 locale 键 |
| 安全与测试 | `TBD` | 覆盖 codec、路径策略、handler action/payload、Provider 字段、必填、容量、no-op、CRLF、缺失 body、非法 JSON 类型、冲突、解析失败不覆盖、备份及写后验证回滚；前端覆盖 5/7 schema、差量保存、列表转换、冲突与解析失败 |

当前验证：Java F2 定向共 23 项，21 pass、2 个 Windows symlink 条件跳过；`webview npm run test` 全量通过，`SkillEditorDialog.test.tsx` 6/6，目标 ESLint、i18n 与 `check:event-literals`（372 文件、141 条下行事件、0 漂移）通过。全量 `./gradlew test` 执行 1640 项，F2 相关测试全部通过；仅 2 个非 F2 的 `SemanticContextExtensionContractTest` 基线用例失败。当前分支缺少 `check:style` script，且全量 ESLint 仍有 16 errors / 88 warnings，均位于非 F2 文件，本批次未越界修复。

---

## 15. 已落地与阶段性成果总览

> 本节同时记录已落地成果和明确标注的 Phase A/基线/先导成果。表中出现不代表对应 P1-P4 方向已完整完成；当前完成度统一以 §0 和 §10 的 2026-07-22 核查为准。日期以代码实际提交日为准，`TBD` 表示当前工作区已有实现但尚未形成提交。

### P0 安全与数据完整性

| 方向 | 内容 | 日期 / commit |
| --- | --- | --- |
| S2 | PasswordStore 凭证地基（CredentialBackend 抽象 + 容量/降级/日志安全） | 2026-07-17 `60acb930` |
| S3 | NodeJsServiceCaller.executeNodeScript 硬化（分流 / 有界读 / 真 timeout） | 2026-07-17 `7037ae7e` |
| A3 / F9 | ConfigRepository（原子写 + ThreadLocal CAS + malformed quarantine + 多版本 backup） | 2026-07-17 `7ec33f81` |
| A3 / F9（S2-3） | `ConfigStore` 领域所有权 + `schemaVersion` 逐级 migration registry + Smithery 安全迁移/延迟恢复 + 同路径进程内并发控制 | 2026-07-22 `19457260` |
| A3（领域拆分①） | AppearanceSettingsService（外观+字体，模式 A 半拆：Service 注入 CSS，Facade 6 public 委托不变，CSS 净减 ~200 行） | 2026-07-20 `e0fd8eef` |
| A3（领域拆分②） | AiFeatureToggleSettingsService（AI 功能开关 4 toggle + Smithery key，模式 A 半拆，Facade 10 public 委托不变，零核心路径） | 2026-07-20 `4b37249b` |
| A3（领域拆分③） | CodexSandboxModeSettingsService（Codex 沙箱模式 per-project/default + 平台默认值决策，模式 A 半拆，Facade 2 public 委托不变，CSS 净减 53 行） | 2026-07-20 `c58b3b46` |
| A3（领域拆分④） | ModelRegistrySettingsService（模型注册表 effective=merge(user,只读默认)，模式 A 半拆，Facade 3 public 委托不变，静态 ModelRegistryService 不合并，CSS 净减 151 行） | 2026-07-20 `46c4f55f` |
| A3（领域拆分⑤） | McpSettingsService（MCP 服务器配置，持有并构造 McpServerManager，双路径存储 ~/.claude.json 主 + config.json fallback，模式 A 半拆，Facade 6 public 委托不变，边界外 CodexMcpServerManager 不动，CSS 净减 ~20 行） | 2026-07-20 `97f0396c` |
| A3（领域拆分⑥） | ProviderSettingsService（三 Provider claude/codex/opencode 配置，持有并构造三 ProviderManager + 收口 6 个 localConfigAuthorized/runtimeAccessMode 内联，模式 A 半拆，Facade ~39 public 委托不变，CODEX_RUNTIME_ACCESS_* 常量留 CSS，3 settings Manager 共享引用，CSS 净减 ~180 行） | 2026-07-20 `ffac99fb` |

### 协议与架构

| 方向 | 内容 | 日期 / commit |
| --- | --- | --- |
| A4 | BaseSDKBridge 核心已落地（三 Provider 继承，仅小范围债务） | 核心已落地 |
| A7 | Provider 历史 Adapter/Registry 已落地（债务清理阶段） | 核心已落地 |
| A5（S2-4） | 语义上下文与 Java 类导航动态 EP、可选 descriptor 隔离、失败隔离/fallback、四 IDE Plugin Verifier 验收 | 2026-07-22 `dd9bc4b4`、`d0d1fd78`、`7e2eef35` |
| F8（S2-2） | 三 Provider CLI compatibility manifest SSOT、parser registry、签名更新、防回滚缓存与离线 fallback | 2026-07-22 `e3de1b8a` |
| F2（S2-5） | Provider-specific schema、安全 `SKILL.md` 读写、typed 协议/handler、schema-driven Webview 编辑器与完整边界测试 | 2026-07-22 `TBD` |
| A8 | 下行事件字面量 → `DOWNSTREAM.*` SSOT + `check-event-literals.mjs` 漂移守门 | 2026-07-20 `9eb0d496` |
| A8（S0-4） | 独立 `protocol-ssot` CI job：Java 枚举生成协议类型后执行漂移检查 | 2026-07-22 `22b17f2d` |
| A8（S1-3） | 漂移失败项输出文件、行列、原始字面量和精确 `DOWNSTREAM.*` 替换建议，并覆盖多文件与退出码测试 | 2026-07-22 `937927da` |
| (AGENTS) | 精简为纯架构准则，一次性债务条目外移到独立文档 | 2026-07-20 `1fab7035` |

### P1 动效与无障碍

| 方向 | 内容 | 日期 / commit |
| --- | --- | --- |
| H2 | 思考区 `grid-template-rows 0fr↔1fr` 折叠动画 | 2026-07-20 `8edc3aad` |
| H3（出场动画） | 消息出场动画（exitingMessages 缓存 + messageFadeOut，仅 opacity 保护 scroll anchoring，零 bundle 增量） | 2026-07-21 `36e37fbc` |
| H5 | SkeletonList 骨架屏（真实请求状态驱动） | 2026-07-20 `f53a9f2c` |
| H6 | 复制成功/失败微交互 + focus-visible | 2026-07-20 `8edc3aad` |
| H7 | reduced-motion 全局策略（base.less 唯一入口） | 2026-07-20 `8edc3aad` |
| A11Y1 | Dialog 焦点管理（portal + trap/restore/inert/嵌套栈） | 2026-07-20 `c09a5388` |
| A11Y2 | 键盘导航 roving tabindex（useRovingTabIndex hook + 4 Tab 组件） | 2026-07-21 `7b6fd922` |
| A11Y3 | 流式 aria-live 节流；S0-5 修复实时底部状态、稳定 interval、最终摘要与卸载清理 | 2026-07-21 `09f7cde2`；2026-07-22 `d22bc3a7` |

### P1 构建 / 工具链 / 文档

| 方向 | 内容 | 日期 / commit |
| --- | --- | --- |
| B1 | buildWebview inputs/outputs（支持 up-to-date 跳过） | 2026-07-20 `dff5092f` |
| B1（S1-1） | 三轮 Gradle/Webview profiling 基线、热点与复测口径 | 2026-07-22 `ba9a0022` |
| B3 早期 | JCEF bootstrap 单次注入 + hide_panel 并入主 sendToJava 路由 | 2026-07-20 `1bd4708d` |
| B3（S2-1） | 后端权威 typed bootstrap DTO/schema、单一 `webview.bootstrap` 下行事件、移除业务初始化 JavaScript 拼接 | 2026-07-22 `fb6202b1` |
| T2 | ESLint flat config + Prettier + lint-staged | 2026-07-20 `8934698f` |
| T2（S0-3） | Webview lint errors 清零 + 独立 `webview-lint` CI job | 2026-07-22 `fc9b2867`, `44f14abf` |
| T2（S1-2） | `check:style` 统一本地/CI 全量 ESLint + 增量 Prettier，pre-commit 复用同一范围 | 2026-07-22 `5a693e65` |
| T3 | exportMarkdown → exportSessionJson 命名修正 | 2026-07-20 `03c7d798` |
| T1 | 覆盖率工具接入（JaCoCo + Vitest coverage v8 + c8） | 2026-07-21 `085c4e6f` |
| D1 | 开发指南（环境 / 构建 / 协议 / 三套测试 / verifier / 六路径 / 发布） | 2026-07-20 `0d93b525` |
| D2 | `.githooks/pre-commit`（lint-staged，容忍 node_modules 缺失） | 2026-07-20 `0d93b525` |

### 国际化

| 方向 | 内容 | 日期 / commit |
| --- | --- | --- |
| I18N1 | locale coverage baseline CI 守门（`check-locale-coverage.mjs` + tests.yml job） | 2026-07-17 `408bfb33` |
| I18N1 + I18N2 | 前后端统一 key baseline gate（`check-i18n-keys.mjs`，含后端 base bundle） | 2026-07-20 `f980e7ae` |
| I18N1 + I18N2（S1-4） | 统一 gate 接入 CI，删除重复前端脚本/baseline，刷新 1484-key adoption baseline | 2026-07-22 `ab9383bf` |

### 修复

| 方向 | 内容 | 日期 / commit |
| --- | --- | --- |
| — | useTypewriterStream POP_LIMIT 测试同步到实现权威值 1500（HEAD 既有红测试） | 2026-07-20 `360bdef4` |
| F6 / S0-1 | 诊断包多 entry ZIP 生命周期修复 + 结构化递归脱敏 + 可读性/脱敏测试 | 2026-07-22 `91c88705` |

### 构建与性能

| 方向 | 内容 | 日期 / commit |
| --- | --- | --- |
| B2（基线） | rollup-plugin-visualizer 接入 + 首次基线：index.html 6,133.45 kB / gzip 1,729.97 kB | 2026-07-21 `TBD` |

### P4 长期生态

| 方向 | 内容 | 日期 / commit |
| --- | --- | --- |
| T4（Phase 1） | ai-bridge 工具链先导（ESLint + Prettier + 有效 `tsconfig.json` + TS strict / JS `@ts-check` 渐进检查 + 统一测试 + 路径 bug 修复 + JSDoc） | 2026-07-21 / 2026-07-22 `36e37fbc` |
| T4（Phase 2 先导） | utils/exit-strategy.ts 迁移（类型注解 + tsx 加载器；当前 421 项：420 pass、1 skipped） | 2026-07-21 `TBD` |
| F1（近端） | ProviderCapability 枚举 + ProviderAdapter 能力声明 + ProviderRegistry 能力查询 + 契约测试（7 用例） | 2026-07-21 `TBD` |
| F1（接入清单） | docs/provider-onboarding-checklist.md（32 接触点） | 2026-07-21 `TBD` |
| F1（六路径契约测试） | ProviderSixPathContractTest（11 用例） | 2026-07-21 `TBD` |

### 合并 / 接受边界

- **B5** Mermaid 打包：已并入 B2（multi-chunk / singlefile 决策），不独立落地。

> **2026-07-22 核查修正**：F6 仍不能标记为“全部落地”——诊断包 ZIP 生命周期与结构化脱敏已由 S0-1 修复，但采样、保留期限、隐私开关与六路径指标尚未完整。P1-P4 的全部剩余工作、实施顺序和验收入口统一见 §0.3 与 §10；本节只作为成果索引，不再承担 backlog 完成状态判断。
