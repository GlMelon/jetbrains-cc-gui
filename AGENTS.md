# AGENTS.md — 架构开发规范

> 本文件是本项目的**最高架构准则**。所有 AI agent(Claude Code / Codex / 其他)在生成或修改代码时,**必须**先阅读并遵循本文档;所有人类开发者在提交代码前,**必须**对照第 7 节「合规检查清单」与第 8 节「Git 提交规范」自检。违反任何总则的改动,需在 PR 描述中明确说明理由并获得 review 通过。

---

## 0. 文档定位与适用范围

**适用对象**:本项目是一个 JetBrains 平台插件,采用三层运行时:

- **后端**:IntelliJ 插件主体(Java,`src/main/java/com/github/claudecodegui/`),承载全部业务逻辑、状态权威与持久化。
- **前端**:React + TypeScript webview(`webview/`),通过 JCEF 嵌入,**只负责渲染回显与输入采集**。
- **ai-bridge**:独立 Node 进程(`ai-bridge/`),负责 CLI 进程管理与消息流处理。

**通信**:JCEF 双向字符串总线。

- **上行**(前端 → 后端):`window.sendToJava({type, content})`,type 取值见 `protocol/UpstreamAction` 枚举。
- **下行**(后端 → 前端):`window.__bridge.dispatch(type, payload)`,type 取值见 `protocol/DownstreamEvent` 枚举。

**进程边界(Java ↔ ai-bridge)**:NDJSON 字符串契约,**无 Node 类型泄漏**。后端 `BaseSDKBridge.executeStreamingCommand` 以 `node channel-manager.js <provider> <action>` 启动子进程,经 stdin 投递 JSON、读 stdout NDJSON 行通信。ai-bridge 内部 provider 路由已遵循 Adapter 范式(`ai-bridge/channels/provider-registry.js` 用 `Map<provider, descriptor>` + `dispatch()`),是 Node 侧 Docking 正面范例。**期望(当前债务)**:Java 侧 `BaseSDKBridge` 之上应补 `SdkBridgeAdapter` 接口 + `supports(provider)`,与 ai-bridge provider registry 概念对齐;现状 provider 路由靠子类硬编码 `getProviderName()` 返回字面量,新增第 3 个 SDK 需新建子类并改多处装配(见附录 C)。

**受众**:AI agent(生成代码时强制遵循)+ 人类开发者(CR/PR 对照)。

**定位**:本文是长期稳定的**架构准则**,不含一次性违规清单或迁移路线(那些另行成文)。准则的抽象层级为「核心模式名 + 当前项目落地指引」,借鉴成熟工程范式(策略注册表、模板方法、事件解耦、多态序列化、配置驱动对接等)的**思想**,但**不绑定**任何特定框架(Spring / Atom / Jackson)的具体类名——本插件不依赖它们。

**优先级**:总则一(职责分离)> 总则三(SSOT)> 总则二 / 五(开闭 / 拓展)> 总则六(对称 / 完整 / 健壮)> 总则四(复用)。冲突时按此顺序裁决。

---

## 1. 总则一 · 前后端职责分离(最高优先级)

### 原则

前端**只做**:渲染回显、纯 UI 状态(展开 / 折叠 / 聚焦等)、输入采集与转发、**无可业务语义**的展示变换(如时间格式化、数字千分位)。

一切**业务逻辑一律下沉后端**,包括但不限于:

- 数据计算与聚合
- 能力判定(某模型是否支持 X、某功能是否可用)
- 数据归一化与映射(模型 ID → 角色、原始值 → 业务值)
- 决策(该执行 install / update / rollback 哪个动作)
- 权限与模式判断
- 校验(业务规则校验,非纯格式校验)
- 配置默认值
- 协议语义解释(CLI 消息中字段的业务含义)

### 为什么

前端承担业务逻辑必然导致:① 同一逻辑前后端双写、默认值漂移;② 业务规则变更需改两处,易漏;③ 前端成为隐式状态权威,调试困难;④ 前端 bundle 膨胀、首屏变慢。

### 落地指引(本项目)

- webview **禁止** import 或 hardcode 任何业务数据表(模型清单、provider 列表、工具分类、reasoning 等级等)。这类数据必须由后端通过下行事件下发**完整、已计算好**的结果。
- webview **禁止**在前端实现能力判定函数、归一化函数、决策函数。后端计算好,前端只读取布尔 / 枚举字段。
- 前端只持有「为渲染所必需的最小展示状态」,不持有可由后端推导出的业务结论。

### 合规检查清单

- [ ] 本次新增 / 修改的代码,是否存在「前端在做数据计算 / 判定 / 归一化 / 决策 / 校验 / 配置默认值」?若有,是否已下沉后端?
- [ ] webview 是否新增了 hardcode 的业务数据表或常量?若有,是否应改为后端下发?
- [ ] 前端是否在解释 CLI / 协议字段的业务语义?若是,是否应交后端解析后再下发?

---

## 2. 总则二 · 开闭原则与模块解耦(后端)

### 原则

对**扩展开放**,对**修改关闭**:新增能力应通过新增实现完成,而不是修改既有代码的核心分派逻辑。模块之间**单向依赖、只依赖抽象**(接口),不依赖具体实现。

### 模式

- **策略注册表**:定义接口 + `support(type)` 判定方法 + 由容器注入的 `List<接口>` 集合,运行时遍历集合按 `support` 路由。新增实现只需新增一个类并注册,不改分派器。
- **模板方法 + 钩子**:抽象基类固化公共流程,子类只覆盖少数钩子方法。
- **事件驱动解耦**:跨模块的副作用(通知、推送、级联更新)通过事件发布 / 订阅,发布方与监听方互不感知。

### 落地指引(本项目)

- 新增**上行 action** 处理:**必须**实现 `handler/core/FrontendActionHandler<T>` 泛型接口(声明 `UpstreamAction`、`payloadType()`、`handle(T, ctx)`),由 `FrontendActionDispatcher` 注册派发。**禁止**向 `MessageDispatcher` 的线性遍历链或 `SettingsHandler.SUPPORTED_TYPES` 这类**字符串数组**里追加条目——那是违反开闭原则的旧路径。
  - **装配机制(本插件无 Spring)**:`FrontendActionDispatcher` 构造器接收 `List<FrontendActionHandler<?>>`,由 `ChatWindowDelegate.initializeHandlers()` 手工 `new` 并注入;Dispatcher 内部按 `handler.action().value()` 建 `LinkedHashMap<String, FrontendActionHandler<?>>` 路由,**重复注册即抛 `IllegalArgumentException`**。新增 typed handler 只需在装配列表加一行,不改分派主体。接口靠 `action()` 返回值声明支持范围,**无独立 `support()` 方法**。
  - **过渡适配器**:`LegacyMessageHandlerAdapter` 把旧 `MessageHandler.getSupportedTypes()` 的字符串原地适配进 typed 注册表(`SettingsHandler` 经此桥接),是双轨期关键桥接,**禁止误删**。双轨期上行派发:typed 通道优先命中即短路,否则回退 `MessageDispatcher` 线性链(`ClaudeChatWindow.handleMessage`)。
- 新增**下行事件**派发:type **必须**使用 `DownstreamEvent` 枚举常量(`.value()`),**禁止**散落字符串字面量(如 `"theme.changed"`、`"language.apply"`);派发**统一**经 `HandlerContext.dispatchEvent(type, payloadJson)`,**禁止**直接 `callJavaScript("window.xxx")`。现状:`SettingsHandler` 等 legacy handler 仍混用字面量,属迁移中债务(见附录 C)。
- 新增**领域 handler**:按 `handler/{domain}/` 分目录组织,单一职责,一个 handler 只处理一个领域。
- 新增**第三方 / 外部能力对接**:用 Adapter 接口 + `support()` 路由 + 配置外置(见总则五)。
- 跨模块协作优先走事件或注入对方 Service 接口,**不得**直接深入对方的内部实现类。

### 合规检查清单

- [ ] 新增 action 处理是否实现了 `FrontendActionHandler<T>`?有没有偷懒往字符串数组 / `matchesType` 里加分支?
- [ ] 新增能力是否修改了既有分派器 / 核心类的主体逻辑?若修改了,能否改为新增实现?
- [ ] 模块依赖是否只指向接口?有没有跨领域直接 `new` 或依赖具体实现类?

---

## 3. 总则三 · 契约层单一真相源(SSOT)

### 原则

前后端共享的一切——**协议消息名、payload 字段结构、枚举值、默认值、常量**——必须有**唯一的真相来源**,禁止两端各自手写。

### 为什么

双写必然漂移:字段默认值前端「`<=0` 跳过」、后端「默认 `200000`」,解析器两套,bug 难定位。SSOT 是前后端契约可信的唯一保证。

### 落地指引(本项目)

- **协议消息名(SSOT,已具备)**:上行 / 下行消息名以 Java 枚举(`UpstreamAction` / `DownstreamEvent`)为唯一来源。生成主路径:前端 `prebuild` 钩子触发 `webview/scripts/generate-protocol-types.mjs`,**直读 Java 枚举源**(regex 解析 `NAME("value")`)同步写出 `webview/src/generated/protocol.ts` 与 `protocol-manifest.json`。`ProtocolManifestGenerator`(Gradle `generateProtocol` task)的 manifest 为**可选兼容产物,非主路径**,评估 deprecate。**禁止**在前端手写协议字符串字面量。
  - **消费侧规范**:前端**必须**统一从 `webview/src/generated/protocol.ts` 导入 `UPSTREAM` / `DOWNSTREAM` 常量。现状债务:`webview/src/bridge/events/index.ts` 的 Central Event Registry 仍手写 ~130 条字面量,是与 `protocol.ts` 并存的**第二真相源**,须改造为引用 `DOWNSTREAM.*`(见迁移计划 P1-B)。
- **payload 字段结构(SSOT,必须补齐)**:payload 的字段结构必须从后端**单一来源**生成或校验到前端(扩展上述 manifest,或后端产出 JSON Schema → 生成 TS 类型)。**禁止**前后端各写一套解析器 / 默认值。默认值规则两端必须一致,**以后端为准**。当前 manifest schema 仅 `{name, value}`、payload 字段未生成,属待补债务(见迁移计划 Phase 2)。
- **枚举值**:业务枚举(权限模式、推理等级、provider 类型等)必须有单一来源并生成到前端,**禁止**前端手写联合类型字面量。当前 `PermissionMode`/`ReasoningEffort`/`CodexFastMode`/`ProviderType` 均前端手写、后端散落字符串常量,属待补债务(见迁移计划 P2-A)。
- **序列化出口统一**:协议名已统一 `value` 出口(`ProtocolValue` 接口 + manifest 生成前端);**`desc` 描述与多态字段统一约定为规划项**(当前 `ProtocolValue` 仅声明 `value()`,见迁移计划 P2-A)。前后端不各自重新解释协议语义。

### 合规检查清单

- [ ] 新增 / 修改的协议消息,是否两端都从同一来源更新?有没有前端手写字面量?
- [ ] payload 结构是否单一生成?前后端有没有各写解析器、各写默认值?
- [ ] 新增枚举,是否单一来源生成到前端?

---

## 4. 总则四 · 组件化与复用

### 原则

重复或相似的逻辑**必须**消除二义:要么组件化 / 模块化抽取共用,要么下沉为后端单点。类型、枚举、常量、校验规则单一来源。

### 判定标准

- **纯展示变换**(与业务无关,如日期格式化、文本截断)→ 可前端组件化 / 工具化复用。
- **业务逻辑的重复**(如前后端各算了一遍同一结论)→ **一律下沉后端**,前端只消费后端结果。绝不「前端也实现一份做 fallback」——fallback 应来自后端下发,而非前端 hardcode。

### 落地指引(本项目)

- 发现前后端重复时,先问「这是展示还是业务」。业务 → 后端单点;展示 → 前端共用组件 / 工具。
- 跨端共用的纯类型 / 常量,走 SSOT 生成(见总则三),不复制。
- 后端内部重复,抽公共 Service / 基类 / Converter。

### 合规检查清单

- [ ] 是否存在前后端各实现一遍的同一逻辑?
- [ ] 新增的常量 / 类型 / 校验,是否复用了已有定义而非复制?

---

## 5. 总则五 · 拓展点预留

### 原则

所有**可能变化的能力**——模型、provider、工具分类、第三方对接、CLI 类型——必须预留**扩展接口**,新增实现不改既有代码。面向接口编程,把「变化点」隔离在接口背后。

### 模式(Docking 通用化思想)

借鉴成熟的「对接通用化」三层结构:

1. **门面(Service)**:对外提供统一入口,内部做路由分发,调用方不感知具体实现。
2. **适配器(Adapter 接口)**:每个外部能力一个实现,用 `support(type)` 声明自己能处理哪种类型。
3. **执行器 + 配置外置**:把易变的部分(URL、参数模板、签名方式)外置为配置(JSON 等),新增对接尽量零代码、只加配置。

### 落地指引(本项目)

- 任何对接外部系统 / CLI / 第三方能力的代码,**禁止**用 `if (type == X) ... else if (type == Y)` 硬编码分支。必须定义 Adapter 接口 + `support()` 路由 + 注入集合。
- 易变的协议参数(URL、token 获取、字段映射)外置为配置文件,而非写死在代码里。
- 设计新模块时,先识别「哪些点将来会变」,为它们留接口。
- **已落地范例(可参照)**:`SessionRuntime` 接口 + `default supports(ProviderType, RuntimeType)` + `SessionRuntimeRegistry`(`Map<Key, SessionRuntime>` 查表,路由代码零 if/else);`ProviderAdapter` 接口 + `ProviderRegistry`(`Map<ProviderId, ProviderAdapter>`,fail-fast 重复检测);`ModelConfig` record(配置驱动模型清单);`RuntimePolicyConfig`(外置到 `~/.codemoss/config.json` 的配置外置范例)。**注意**:装配阶段(`SessionRuntimeRouter` / `SessionProviderRouter` 构造函数)仍是手工 `new` + `register`,**路由开闭但装配未完全开闭**——新增 provider 仍需改装配构造函数,后续可考虑注册化(见附录 C 债务)。

### 合规检查清单

- [ ] 新增的外部能力对接,是否用了 if / else 硬编码分支?能否改为 Adapter + support 路由?
- [ ] 是否预留了扩展接口?新增同类能力是否需要改既有代码?

### 硬编码禁止规范(强制)

**原则**:代码中**禁止**出现硬编码的字符串值(除开文件系统约定的可执行文件名、文件路径等),所有字符串值必须引用 SSOT 常量或枚举。

**强制要求**:
1. **Provider 协议值**:必须使用 `ProviderType.X.value()` 或 `CommonConstants.PROVIDER_X`,禁止使用 `"claude"` / `"codex"` / `"opencode"` 字面量。
2. **CLI 可执行文件名**:必须使用 `ProviderType.X.cliCommandForPlatform()`,禁止使用 `"codex.cmd"` / `"opencode"` 等字面量。
3. **JSON 配置键名**:必须使用 `ProviderType.X.value()` 或 `CommonConstants.PROVIDER_X`,禁止使用 `"codex"` / `"opencode"` 等字面量。
4. **消息类型常量**:必须使用 `CliConstants.MSG_*` 或 `CommonConstants.MSG_*`,禁止使用 `"session_id"` / `"stream_start"` 等字面量。
5. **重复常量定义**:禁止在多个类中重复定义相同含义的常量,必须统一引用 SSOT(`CommonConstants` / `ProviderType` / `CliConstants`)。
6. **switch case 字符串**:switch case 中的字符串值必须使用常量引用,禁止使用字面量。

**违规判定**:
- 代码中出现 `"codex"` / `"opencode"` / `"claude"` 等 provider 字面量(除注释外)
- switch case 中出现硬编码字符串而非常量引用
- 多个类中定义相同含义的常量(如 `PROVIDER_CODEX = "codex"` 在多处重复定义)

**例外情况**:
- 测试文件中的硬编码数据(用于构造测试数据)
- 文件系统约定的可执行文件名后缀(如 `.exe` / `.bat`)可保留字面量

---

## 6. 总则六 · 多 provider 调用对称性、完整性与健壮性

### 原则

本插件支持 3 个 AI provider(Claude / Codex / OpenCode),每个有 2 种调用模式(SDK daemon / CLI 子进程),共 **6 条调用路径**。每一类横切处理(env 注入、stdin 写入关闭、interrupt / abort 取消、cwd 回退、provider 归一化、调用模式快照、frontend_ready 状态回灌)必须在 **6 条路径上对称、完整、健壮**。新增或修改任何 provider 的某项处理时,**必须**对照另两个 provider 的同项实现,确认三者等价或记录有意差异。

### 三项要求

- **对称性(Symmetry)**:Claude / Codex / OpenCode 在 SDK 与 CLI 两模式下,对每一类处理逻辑等价。改一处**必须**同步核对其余两处;新增 provider **必须**继承已有 provider 的全部处理项,不得「补一个漏一个」。
- **完整性(Completeness)**:每类处理**必须**覆盖全部 provider × mode 组合,不得遗漏某条路径。变更时列出「处理项 × provider × mode」矩阵逐格确认;新增处理项时,3 provider × 2 mode 同步落地。
- **健壮性(Robustness)**:
  - **确定性取消优先**:interrupt / abort 应显式通知 provider 取消(Claude / Codex 的 `sendAbort`、OpenCode 的 `abortSession`),而非仅杀本地进程、依赖客户端断开的非确定副作用。
  - **边界与防御**:null / 空值**必须**显式处理(cwd 为 null → home 回退、sessionId 为 null 防御、baseUrl 为空 → 默认 URL 回退、stdin 字段 null → 空串)。
  - **进程生命周期完备**:stdin 写入并关闭(防子进程阻塞读)、stdout 必须 drain(防管道满阻塞)、进程退出必须清理。

### 为什么

历史多次因某条路径遗漏某项处理而引入隐蔽 bug,且大多**只在插件实际调用方式下暴露**(直跑 CLI 正常,编译期与单元测试抓不到):

- **B9**:OpenCode CLI 漏关 stdin → opencode 阻塞读永远打开的管道(Claude / Codex 写 + 关 stdin 规避,OpenCode 漏关)。
- **interrupt**:OpenCode SDK 仅杀进程(依赖客户端断开)→ serve 可能继续生成,token 泄漏 + 会话状态不一致(Claude / Codex 主动 `sendAbort` 确定性取消)。
- **cwd**:OpenCode CLI 漏 home 回退 → 启动失败(Claude / Codex 有回退)。
- **归一化**:前端 `normalizeProvider` 漏 opencode 分支 → 选 OpenCode 后下行 provider 被归一为 claude。
- **快照**:Claude send 路径副作用回写 snapshot,破坏纯快照语义(Codex / OpenCode 不回写)。

共同特征:**单 provider 单路径的遗漏**。本准则把「6 路径等价」从隐性约定提升为强制检查项。

### 落地指引(本项目)

**已对齐的横切处理对照表**(新增 / 改动 provider 能力时以此为基准逐项核对):

| 处理项 | Claude | Codex | OpenCode | 遗漏教训 |
|---|---|---|---|---|
| stdin 写入 + 关闭 | ✓ | ✓ | ✓(`ENV_*_USE_STDIN`) | 漏关 → 阻塞读 |
| extraEnv 注入(CLI) | ✓ `CliEnvironmentBuilder.applyExtraEnv` | ✓ | ✓ | CLI 漏注入,不对称 SDK |
| interrupt 主动取消 | ✓ `sendAbort` | ✓ `sendAbort` | ✓ `triggerAbort` | 仅杀进程 → 非确定 |
| cwd null → home 回退(CLI) | ✓ | ✓ | ✓ | 漏回退 → 启动失败 |
| provider 归一化(前端) | ✓ | ✓ | ✓ `normalizeProvider` | 漏分支 → 选后退 claude |
| 调用模式快照语义 | ✓ 纯快照 | ✓ | ✓ | send 副作用回写破坏语义 |
| frontend_ready 状态回灌 | ✓ | ✓ | ✓ | 漏下发 → 新标签默认值回归 |

**新增 / 修改 provider 能力的检查流程**:

1. 列出本次改动涉及的横切处理项;
2. 对每项,查 Claude + Codex 的同项实现作为参照;
3. 确认目标 provider 的实现与参照等价,或记录有意差异及理由;
4. 补 TDD:纯函数走单元测试;Platform 耦合、无法纯单测的代码用**源码字符串检查**兜底(对称 `ClaudeSDKBridgeRefactorTest` / `OpenCodeSDKBridgeTest` 范式)。

**例外(架构本质差异,非不对称)**:daemon 生命周期——Claude 是长连接 daemon + 自动重启循环(`MAX_RESTART_ATTEMPTS`);OpenCode 是惰性按需 daemon(`opencode serve`,60s 冷却)。架构模式不同,**不要求**镜像 `restartAttempts` 计数器,但两者**都必须**具备「防无限重试」的等价保护。判定某差异是否属此例外:差异源于「连接 / 调度模型本身不同」而非「实现遗漏」,且已有等价保护机制。

### 合规检查清单

- [ ] 改动涉及某 provider 的某项处理时,是否对照了另两个 provider 的同项实现?是否等价或已记录有意差异?
- [ ] 该处理项是否覆盖了全部 provider × mode 组合?有无遗漏某条路径?
- [ ] interrupt / abort 是否确定性取消(显式通知 provider),而非仅杀本地进程?
- [ ] null / 空值边界(stdin / cwd / sessionId / baseUrl)是否显式处理?
- [ ] Platform 耦合、无法纯单测的处理,是否用源码字符串检查兜底?

---

## 7. 合规检查清单总表(CR / PR 对照)

提交前逐条自检,全部通过(或已注明豁免理由)方可提交:

| # | 检查点 | 归属总则 |
|---|---|---|
| 1 | 本次改动有无「前端做业务计算 / 判定 / 归一化 / 决策 / 校验 / 配置默认值」?有则下沉后端 | 一 |
| 2 | webview 有无新增 hardcode 业务数据表 / 常量?有则改后端下发 | 一 |
| 3 | 前端有无解释协议字段业务语义?有则交后端 | 一 |
| 4 | 新增 action 是否实现 `FrontendActionHandler<T>`?有无往字符串数组加分支 | 二 |
| 5 | 新增能力是否修改了既有分派器主体?能否改为新增实现 | 二 |
| 6 | 模块依赖是否只指向接口?有无跨领域依赖具体实现 | 二 |
| 7 | 协议名 / payload / 枚举是否两端从单一来源更新?有无前端手写字面量 | 三 |
| 8 | payload 是否单一生成?有无前后端各写解析器 / 默认值 | 三 |
| 9 | 有无前后端各实现一遍的重复逻辑?业务重复是否已下沉 | 四 |
| 10 | 新增常量 / 类型 / 校验是否复用已有定义 | 四 |
| 11 | 外部能力对接是否用了 if / else 硬编码?能否改 Adapter + support 路由 | 五 |
| 12 | 新增同类能力是否需要改既有代码?是否预留了扩展接口 | 五 |
| 13 | 下行事件是否使用 `DownstreamEvent` 枚举常量?有无散落字面量 | 二 |
| 14 | 协议 type 是否从 `generated/protocol.ts` 导入?有无手写字面量 | 三 |
| 15 | 代码中是否出现 provider 字面量(`"codex"` / `"opencode"` / `"claude"`)?是否使用 `ProviderType.X.value()` | 五 |
| 16 | CLI 可执行文件名是否使用 `ProviderType.X.cliCommandForPlatform()`?有无硬编码字面量 | 五 |
| 17 | JSON 配置键名是否使用 `ProviderType.X.value()`?有无硬编码字面量 | 五 |
| 18 | switch case 中的字符串值是否使用常量引用?有无硬编码字面量 | 五 |
| 19 | 是否存在重复常量定义?是否统一引用 SSOT | 四 |
| 20 | 改动某 provider 某项处理时,是否对照另两 provider 同项实现?是否等价或已记录差异 | 六 |
| 21 | 该处理项是否覆盖全部 provider × mode 组合?有无遗漏某条路径 | 六 |
| 22 | interrupt / abort 是否确定性取消(显式通知 provider),而非仅杀本地进程 | 六 |
| 23 | null / 空值边界(stdin / cwd / sessionId / baseUrl)是否显式处理 | 六 |

---

## 8. Git 提交规范

### 原则:按变更性质分批提交

一次开发往往同时产生新功能、bug 修复、重构等多种变更。**禁止**把多种性质的改动塞进单个 commit。**必须**按变更性质拆分为多个独立 commit,每个 commit 只做一件事,且满足:

- 该 commit 内的改动**可被单独 revert**,而不连带破坏其他改动;
- 该 commit 单独通过编译 / 测试,**不引入中间不可用状态**;
- commit 描述**准确反映**该批改动的性质与范围。

### 为什么

混合 commit 会导致:① 回归发生后无法用 `git bisect` 精确定位是哪一类改动引入;② revert 时连带无关改动,扩大爆炸半径;③ PR review 粒度过粗,问题难被发现;④ cherry-pick 到其他分支时被迫带入无关代码。分批提交是代码考古(blame / bisect / revert)可信的前提。

### 8.1 提交信息格式(Conventional Commits)

所有提交信息**必须使用英文**,遵循 Conventional Commits:

```
<type>(<scope>): <subject>

<body 可选>
```

- **一律英文**:subject 与 body 全英文。中文仅允许出现在迁移登记簿编号 / V9 切片等**追溯锚点**的括注(如 `(C7)`、`(V9 OCP slice 1/3)`),正文叙述一律英文。
- **subject 小写起首、祈使句、末尾不加句号**:`show provider icons`,**而非** `Shows provider icons.`。
- **subject ≤ 72 字符**(硬上限);超长内容移入 body。
- **scope 强烈建议带上**,标明改动落地的模块(见 7.4)。

### 8.2 类型(type)定义

| type | 含义 | 何时使用 | 本仓实例 |
|---|---|---|---|
| `feat` | 新功能 | 用户 / 前端可感知的新行为、新增协议字段下发 | `feat(model-registry): backend merge + strip + new-conflict check` |
| `fix` | bug 修复 | 修复错误行为 / 崩溃 / 数据不一致 | `fix(session): prevent cross-turn stale streamEnd via turn token` |
| `refactor` | 重构 | 既非新功能也非修 bug 的内部调整,外部行为不变 | `refactor(dialog): migrate McpConfirmDialog to BaseDialog` |
| `docs` | 文档 | 仅改 `.md` / 设计文档 / 注释性说明 | `docs(arch-debt): sync registry to 43/43 closed` |
| `test` | 测试 | 新增 / 修复 / 调整测试,不改产品代码 | `test: guard frontend ModelRegistryItem covers backend ModelConfig fields` |
| `style` | 格式 | 空白 / 颜色 / 样式微调,不影响逻辑 | `style(webview): tune title bar background between tab bar and chat` |
| `build` | 构建 / 版本 | `build.gradle` / 版本号 / checkstyle 配置 | `build: bump version to 0.4.6-Alpha1` |
| `chore` | 杂项维护 | 清理无用 import / 依赖 / 配置 | `chore(backend): remove unused imports` |
| `i18n` | 国际化 | locale 键值增删 | `i18n: add chat.noModelConfigured key across all locales` |

> **feat 与 refactor 的边界**:凡对外部协议 / 用户可见行为有新增的,即使实现上是「下沉 / 收口」,也用 `feat`(如下发新字段);纯内部搬运、行为零变化的用 `refactor`。拿不准时问「用户 / 前端能感知到变化吗」——能 → `feat`,不能 → `refactor`。

### 8.3 作用域(scope)约定

scope 标明改动落地的模块,**小写、连字符、单词或紧凑词组,不得含空格**。本仓常用 scope(非穷举,沿用历史):

- **分层**:`webview`(前端)、`ai-bridge`(Node 进程);后端可不带 scope 或用领域名。
- **领域**:`session` / `settings` / `model` / `model-registry` / `protocol` / `dialog` / `runtime` / `provider` / `bridge` / `handler`。
- **横切**:`test` / `format` / `dependency` / `config` / `arch-debt`。

> **历史遗留**:早期提交出现过带空格的 scope 如 `(cli session)`、`(model-registry-section)`,后续**一律改用连字符**统一为 `(cli-session)`。

### 8.4 迁移编号与多步骤切片的标注

涉及架构迁移登记簿或 V9 切片的提交,**在 subject 或 body 标注编号**,便于追溯:

- **登记簿项**:`(C7)` / `(D4)` / `(A6)` 等放 subject 末尾括注,对应附录 C 索引的迁移文档。
- **V9 多切片**:`V9 OCP slice 1/3`、`2/3`、`3/3`,标明本 commit 是该序列第几步,便于按步 review 与回退。

> 此类编号是**追溯锚点**,不替代类型判断——仍要按 feat / fix / refactor 正确归类。

### 8.5 分批提交示例

假设一次开发同时做了:① 后端新增 `supportedReasoningLevels` 下发(新功能);② 顺手修了模型 id 归一化的一处 bug;③ 把某段重复格式化代码抽成公共函数。**正确做法**是拆成三个 commit:

```
feat(model-registry): push backend-supported reasoning levels to frontend
fix(settings): normalize legacy canonical Claude model id to role id on read
refactor(format): extract shared capacity formatting into formatCapacity
```

**错误做法**是合成一个 `feat: model improvements` 把三者塞在一起,导致后续无法独立 revert 或 bisect。

### 8.6 历史中应避免的反模式

下列模式曾在本仓早期出现,**后续提交禁止再犯**:

- **无 type 前缀**:如 `Replace hardcoded strings with centralized constants and enums`、`Fix stream coalescer alarm disposal` → 必须补 `refactor:` / `fix:` 前缀。
- **subject 首字母大写 + 句号**:如 `Refine dialog and chat input styling` → 改为 `refactor(webview): refine dialog and chat input styling`。
- **中英文混用的 subject / body**:如 `refactor: A5/C5 业务默认值收口后端 SSOT(CodexProtectedEnvKey 枚举 ...)` → 改为全英文 `refactor: sink business defaults to backend SSOT (CodexProtectedEnvKey enum)`。

### 合规检查清单

- [ ] 每个 commit 是否只含**单一性质**的改动?有无功能 / 修复 / 重构混提?
- [ ] 提交信息是否**全英文**?subject 是否小写起首、无句号、≤ 72 字符?
- [ ] type 是否选对(`feat` / `fix` / `refactor` 边界是否清晰)?
- [ ] scope 是否标注、是否小写连字符、无空格?
- [ ] 涉及迁移的,是否标注了登记簿编号 / V9 切片序号?
- [ ] 是否存在「一个超大 commit 裹挟多种性质改动」的情况?能否再拆?

---

## 附录 A · 成熟模式参考

下列范式已在工业级项目中验证(参考 zh-park-new),其**思想**适用于本插件,但**实现**需按本插件技术栈(Java + TS + JCEF 总线)等价落地,不照搬原项目的 Spring / Jackson / Atom 具体类:

| 范式 | 思想 | 本插件等价做法 |
|---|---|---|
| 策略注册表 | 接口 + `support()` + 注入集合路由,新增不改分派 | `FrontendActionHandler<T>` + `FrontendActionDispatcher` |
| 模板方法 + 钩子 | 基类固化流程,子类填钩子 | 抽象基类 + protected 钩子方法 |
| 事件驱动解耦 | 发布 / 订阅,发布方不感知监听方 | 后端事件总线 / 回调注册 |
| Docking 三层通用化 | 门面 → Adapter → 执行器 + 配置外置 | 任何外部对接走此三层 |
| 序列化约定(统一枚举 + 多态字段) | 枚举 value / desc 统一、多态字段走统一约定,业务侧不写自定义序列化器 | 协议名已统一 `value` 出口(`ProtocolValue` + manifest 生成);**`desc` 与多态字段统一为规划项**;payload 走 SSOT 生成(见总则三) |
| 四对象分层(PO / DTO / Form / Query + Converter) | 持久化 / 响应 / 写入 / 查询对象分离,层间用 Converter 转换 | **当前仅少量 `*Request` record,DTO / PO / Response / Converter 尚未落地**;Settings 层仍以 `JsonObject` 半 schema-less 手拼(流式消息场景刻意保留,稳定结构可 DTO 化) |

---

## 附录 B · 术语表

- **SSOT**(Single Source of Truth,单一真相源):某份契约 / 数据只有一个权威来源,其他地方都从它派生。
- **OCP**(Open-Closed Principle,开闭原则):对扩展开放,对修改关闭。
- **Adapter / support 路由**:适配器接口 + 一个「我能否处理此类型」的判定方法,集合注入后按判定分派。
- **门面(Facade)**:对外统一入口,内部路由到具体实现。
- **上行 / 下行**:上行 = 前端 → 后端(`sendToJava` / `UpstreamAction`);下行 = 后端 → 前端(`dispatch` / `DownstreamEvent`)。
- **payload**:协议消息携带的数据体,区别于消息名(type)。

---

*本准则源自一次完整的前后端架构排查。如需查阅排查中发现的具体违规点与重构建议,见附录 C 索引的迁移路线文档。准则本身的修订,需经架构 review。*
