# kimi ACP 通道接入落地记录(2026-08-28)

> 背景:kimi 思考区此前定论「stream-json 通道层面不可实现」——npm `@moonshot-ai/kimi-code`
> 的 `-p --output-format stream-json` 非交互模式官方不写 thinking(不进 JSONL 也不落 stderr),
> 前端思考开关被迫对 kimi 禁用(`common.thinkingKimiUnsupported`,10 locale)。
> 本批接入 kimi 自带的 `kimi acp` 子命令(ACP server over stdio),该通道下思考内容有
> 正规一等公民通道(`agent_thought_chunk`),并顺带落地工具卡/续接/标题/图片全能力面。
> 关联:[native-cli-provider-parity-2026-08-27.md](native-cli-provider-parity-2026-08-27.md) 遗留 #4「kimi thinking」。

## 一、实测确认的协议事实(0.38.0,4 轮 + S0-2,全部本机验证)

| 实测点 | 结果 |
|---|---|
| `agent_thought_chunk` | ✅ 纯增量 delta(`content:{type:'text',text}`),28~33 chunk/turn |
| thinking 配置 | `session/new` 返回 configOptions 含 `id:'thinking',category:'thought_level'`;**默认 off**,须 `session/set_config_option{configId:'thinking',value}` 显式开启(0.29.0+) |
| `agent_message_chunk` | ✅ 增量正文 |
| `tool_call` / `tool_call_update` | ✅ kimi 用第一条 delta 懒创建(status pending→in_progress→completed);**`tool_call_update.content` 为 REPLACE 语义(累积替换非追加)**;toolCallId 形如 `"<turnId>:<rawId>"` |
| `session/request_permission` | auto mode 不发;AskUserQuestion 等会发,带 id **必须回应否则挂死 turn**;optionId 实为 `approve_once`/`approve_always`/`reject`(非协议枚举名) |
| `session/cancel` | 作 request(带 id)→ `-32601 Method not found`;作 **notification(无 id)→ 生效**:进行中 turn 以 `stopReason:"cancelled"` 结束,**进程保持**——长驻 interrupt 的优雅基础 |
| `session/load` | 跨进程续接成功(返回 configOptions 快照);0.38 v2 引擎下当前不重放历史 chunk(但门控仍防御保留) |
| `session_info_update` | `{title}`(0.38 实测有;upstream main 分支源码反而没有——以实测为准) |
| `promptCapabilities.image` | true → 图片走正规 ACP content blocks |
| framing | NDJSON(`\n` 分隔单行 JSON);stdout 纯协议流;stderr 仅日志;`stopReason:"end_turn"`;未登录 `-32000` |
| 磁盘 session | `$KIMI_CODE_HOME/sessions/wd_<cwd-hash>/session_<uuid>/state.json`,与 ACP sessionId 同构 |
| **MCP mcpServers** | ⛔ **v1/v2 引擎均不生效**(见 §五) |

## 二、用户决策(2026-08-27 拍板)

1. **架构落点 = Java direct ACP**:新建 Java 侧 ACP 客户端直 spawn `kimi acp`,延续
   324d2e24 direct-spawn 架构与「按本地架构迁移适配」总则;协议逻辑以 upstream
   `grok-acp-client.js`(1145 行,`git show upstream/main:...` 可取)为参照。
   ~~将来 grok 切 ACP 走同一模式保持三家对称。~~
   **⚠️ 2026-08-28 决策反转(能原生尽原生)**:经官方文档核实,grok streaming-json 官方带
   thinking(`{"type":"thought"}` 事件,本地 `GrokCliStreamParser` 已接通)、pi/opencode/claude/
   codex 结构化 JSON 通道全部官方支持 thinking 且已接通——「stream-json 不支持思考」实际只有
   kimi 一家,ACP 仅保留给 kimi。同日删除 ai-bridge ACP 链与死代码:`grok-acp-client.js`/
   `acp-terminal-host.js`/`cli-ask.js`(全仓库零调用的死代码,0670d06d 恢复它实为给死代码
   修断链)/`grok-utils.js` 及其 3 个测试文件;omp/dsh 思考区走原生 marker 通道补
   `[THINKING_DELTA]` 解析接通(MarkerCliStreamParser + marker-protocol.js)。
2. **范围全选**:思考区/工具卡/续接/前端开关放开/descriptor 反转 + 图片 ACP blocks +
   effort 档位映射 + 会话标题 + MCP 注入(后经 S0-2 证伪,见 §五)。
3. **cli-ask.js 断链顺带修**(独立 commit):`grok-acp-client.js` 在 merge 重构中丢失
   (本地从未进 git,仅 upstream 存在),导致 `cli-ask.js:31` 悬空 import
   (node 实测 ERR_MODULE_NOT_FOUND,grok 一次性问答路径已死)。

## 三、实现结构(commit b2207572 + 4a6610f3)

### 核心:`cli/kimi/acp/` 包 5 文件

| 文件 | 职责 | 关键点 |
|---|---|---|
| `KimiAcpProtocol` | 协议常量 SSOT | method 名/update 变体/config id/错误码 -32000 |
| `KimiAcpConnection` | 进程+NDJSON 帧+JSON-RPC id 匹配 | **stderr 必须 `redirectErrorStream(false)`**(stdout 是纯协议流);server 请求 route 层自管 id 回应;畸形行不致死;`isAlive()`/`sendSessionCancel()` 供长驻 |
| `KimiAcpStreamParser` | update→MSG 映射+重放门控 | implements CliStreamParser;thought→thinkingStart+thinkingDelta(对齐 GrokCliStreamParser.handleThought);tool_call 懒创建去重;tool_call_update REPLACE→completed/failed 才发 tool_result;`live` 默认 false(load 重放全丢弃),`beginLiveTurn()` 后放行 |
| `KimiAcpCliSession` | ACP 会话(长驻) | **直实现 CliSession 不继承基类**(基类 runOnce 硬编码 `redirectInput(NUL)` 无法写握手 stdin;`ChannelCliSession` 已立非继承先例);握手 initialize→session/new\|load→set_config(thinking);B13 重试;权限兜底 cancelled;catch LinkageError 防穿透 |
| `KimiAcpChannelGate` | 通道门禁 | `-DkimiAcp.enabled`(默认 true)+ `evaluateFeature`;**任何异常/规则缺失→false 走 legacy,绝不抛**(Holder clinit 教训) |

### 门禁与路由

- manifest kimi 加 `features.acp{minimumSupported:"0.9.0",maximumTested:"0.38.0"}`;
  `ProviderRule.features` 字段(nullable 向后兼容)+ `FeatureRule` record;
  codec validateRule 校验(存在才验边界);`CliCompatibilityService.evaluateFeature`
  (**规则缺失返回 false 不抛**——与 evaluate 的 throw IllegalStateException 刻意对立);
- `KimiCliSessionFactory` 双通道路由:门禁合格→`KimiAcpCliSession`,否则 legacy
  `KimiRunOnceCliSession`(stream-json,保留,对称性测试要求);
- `CliSessionManager` kimi factory 传 gateway(Project 构造器)+ `MSG_SESSION_TITLE` 入
  `NON_CONTENT_MESSAGE_TYPES` 黑名单(防标题事件污染「静默空成功」判定)。

### 外围接入

- `ProviderDescriptor.kimi()` 加 `REASONING_THINKING`(能力声明按通道最优承诺;
  该 capability 无前端下发链,纯后端 SSOT+契约测试);
- `ConfigSelect.tsx` 删 kimi 思考开关禁用(4 处分支);10 locale 删
  `thinkingKimiUnsupported`;`CodexMessageHandler` 接 `MSG_SESSION_TITLE` →
  `DownstreamEvent.SESSION_TITLE`(payload `{sessionId,title}` 与 CliSessionTitleService
  一致,前端零改动);`SessionSendService` 无需改(kimi 走 sendToCodexProtocolProvider,
  不挂 Haiku 标题 whenComplete,无双标题冲突);
- 图片:`CliAttachmentHandler.processForAcp()`(复用 resolveBase64,data 优先否则读
  localPath 字节,失败 LOG.warn 跳过)→ ACP image content blocks(base64 直传,
  无需磁盘物化);`CliImagePromptInjections.buildKimiPromptWithImages` 仅留 legacy。

## 四、长驻进程(commit 4a6610f3)

ACP 协议设计即「一次 initialize,多 session/prompt」;run-once 每 turn spawn
(~1-2s node 冷启)是反 ACP 用法。KimiAcpCliSession 长驻:

- **复用**:`persistentConn`+`persistentSessionId` 存活 → 跳过 spawn+握手,直接 prompt
  (省 ~1-2s/turn);`KimiAcpConnection.isAlive()` 判活(未 closed 且 process.isAlive);
- **interrupt = session/cancel notification**(不杀进程):prompt 以 stopReason=cancelled
  正常返回(不抛异常)→ `finalizeTurn` 检 `wasInterrupted()` → onInterrupted;
  **session/cancel 只取消 turn 不破坏 session** → 不 clearPersistent,下 turn 直接复用
  (ACP 优雅中断的核心收益);
- **出错重建**:prompt RPC 异常/超时/握手失败 → `clearPersistent()` + close;
  prompt 正常完成(keepAlive)不 close,进程保持;
- 进程死后重建 = 新 `session/new`(ACP session 是进程内内存状态,进程死即丢;
  磁盘 state.json 供历史面板,非 ACP 续接源)。

## 五、MCP 注入定论:不可修,disabled 为最终方案

S0-2 终决验证(标记文件法:mini MCP server 收到 initialize 即写标记文件):

| 引擎 | 结果 |
|---|---|
| v2(0.33+ 默认) | session/new 带 mcpServers 不报错但 **server 从未被联系**(标记文件未创建)——印证 acp-adapter 源码注释「v2 引擎在 session create/resume 上无 caller mcpServers 通道(left to a future ACP-specific design)」 |
| v1(`KIMI_CODE_LEGACY_FLAG=1`) | session/new 直接 `-32602 Invalid params`(flag 未真正回退 v1,或 v1 亦不接受该格式) |

结论:**kimi 0.38 ACP 无可用 MCP 注入路径,上游限制,非临时状态**。
`McpGatewayConfigWriter` kimi 保持 disabled(default 分支,注释已记定论);
kimi 用内建工具(Bash/Read 等实测正常)。禁止退回写用户 config.toml
(claude/codex/opencode 均零临时 home 零用户配置改写,不为 kimi 破例)。

## 六、断链修复(commit 0670d06d,独立)

`grok-acp-client.js`+`acp-terminal-host.js` 从 upstream/main 恢复(依赖闭包已验证:
grok-utils/permission-ipc/cli-image-input 均本地存在,0 sdk-loader 引用)。
**不恢复** `commit-message.js`/`sdk-loader.js`:upstream 版重度依赖 SDK
(ensureAnthropicSdk/loadClaudeSdk/loadCodexSdk),违反「SDK 永久移除」铁律;
本地 commit 功能已走 `Java CommitMessageAiService → channel-manager.js`。
顺带删孤儿 `commit-message.test.js`(untracked,测已废弃的 SDK 路径决策)。

## 七、manifest 签名(恢复远程更新链路)

生成新 Ed25519 密钥对:公钥写入 `CliCompatibilityManifestRepository.PUBLIC_KEY_BASE64`
与 `scripts/sign-cli-compatibility-manifest.mjs` 的 `EMBEDDED_PUBLIC_KEY_BASE64`
(**两处必须同步**);私钥存本机 `~/.claude-code-gui/cli-compat-signing-key`
(64 字节 base64 PKCS8 DER,勿提交);重签 `.sig`。
bundled manifest 本地加载**不验签**(仅远程更新验 .sig),故签名缺失不阻塞本地开发。

## 八、测试与验证

- `KimiAcpStreamParserTest`(7 用例):thought 首条 thinkingStart+delta / message_delta /
  tool_call 懒创建去重 / tool_call_update REPLACE 仅 terminal 态发 tool_result /
  failed→is_error / **live 门控(默认丢弃,beginLiveTurn 放行)** / title 受门控 / 忽略变体;
- `KimiCliStreamParserTest.noThinkingIsEverEmitted` 反转为
  `reasoningContentFieldRemainsIgnoredOnLegacyChannel`(legacy 契约:reasoning_content 仍忽略);
- `ProviderDescriptorContractTest` kimi 断言反转(加 REASONING_THINKING);
- `CliCompatibilityProviderMatrixTest` 加 `evaluateFeature` 4 边界 + legacy 无 features 容错;
- `ConfigSelect.thinkingSwitch.test.tsx` kimi 反转为 enabled;
- **`scripts/verify-kimi-acp.mjs`**:端到端协议验证脚本(21 断言,纯 node spawn 真实
  kimi acp,不依赖 JVM/IDE)——initialize/session-new/thinking-set/thought-chunk/
  tool-call/REPLACE/title/session-load;kimi 升级后跑它回归协议契约,断言失败即需
  同步调整 KimiAcpStreamParser/KimiAcpProtocol。

## 九、运维

- 逃生开关:`-DkimiAcp.enabled=false` → 全部回 legacy stream-json;
- 未登录:session 相关请求 -32000 → 报错文案「kimi 未登录,请在终端运行 kimi 登录后重试」;
- 版本门禁:kimi < 0.9.0 无 ACP(features.acp floor)→ legacy;0.9.0~0.38.0 合格;
  更高版本按 higherVersionPolicy(WARN_ALLOW)放行。

## 十、遗留与后续

1. **MCP 注入**:等 kimi 上游在 v2 引擎补 ACP mcpServers 通道(跟踪 changelog);
2. **长驻进程面板**:进程面板 CLI_SESSION 目前 claude-only,kimi ACP 长驻进程未纳入
   (轻量自管,未接 CliPersistentProcessRegistry——其 marker 协议与 ACP JSON-RPC 不同构);
3. **真机 IDE 沙箱人工过一轮**:思考区流式/工具卡实时卡片/续接/标题/图片/未登录文案/
   -D 回退(verify 脚本已覆盖协议层,IDE 集成层仍需人工)。

## 相关 commit

- `0670d06d` fix(ai-bridge): restore grok-acp-client/acp-terminal-host clobbered by channel-path retirement
- `b2207572` feat(kimi): ACP channel for thinking/tool/title (bypasses stream-json thinking limit)
- `4a6610f3` feat(kimi-acp): persistent process + session/cancel interrupt + e2e verify script
