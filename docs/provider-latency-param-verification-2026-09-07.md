# 四 Provider 插件链路延迟实测与参数传递排查报告

> 排查日期：2026-09-07
> 测试环境：本机 Windows,claude 2.1.251 / codex-cli 0.151.0 / opencode 1.18.26 / kimi 0.41.0
> 方法说明：未改动任何产品代码；probe 脚本为临时产物，位于 `build/perf-probe/`(git 忽略)。
> 关联文档：`docs/lifecycle-provider-mcp-codex-latency-verification-2026-09-01.md`

## 0. 结论摘要

1. 四家 provider 聊天主路径的**插件侧本地开销均在 1 秒量级**(kimi ACP / opencode serve 稳态接近 0),可见等待的 80% 以上是端点 / 模型 / 网络耗时，属 AI 侧，插件无法消除。
2. 参数传递验证：claude / codex / opencode 的模型、思考程度、权限模式均正确传递并生效；**发现两处缺口**:kimi ACP 通道完全不传 model（插件选模型无效）,opencode serve 在 actualModel 无 `/` 时静默丢失模型选择。
3. 值得做的插件侧优化只有两项：**claude 路径 MCP 聚合对齐 codex（收益最大）** 与 **kimi ACP 补传 model（正确性修复）**；其余均为毫秒级或小概率场景。

> **修复状态（2026-09-07 当日落地）**:§3 / §5 列出的插件侧可处理项已全部修复，见 [§8 修复落地情况](#8-修复落地情况2026-09-07)。

## 1. 测试方法

探索确认实际架构：**claude / codex / opencode / kimi 的聊天主路径都是 Java 直接 spawn 各 CLI 二进制**,不经 ai-bridge channel-manager(channel-manager 只承担 omp/dsh 会话、claude/codex 的 commit message one-shot、历史读取等辅助命令)。

因此按各 provider 会话类的 `buildCommand` 逻辑 1:1 复刻插件命令行（含 Windows cmd shim、stdin 投递关闭、serve HTTP/SSE 通道、ACP JSON-RPC 通道）,对 stdout 逐行打相对时间戳测量。prompt 统一用极短生成（"reply ok" / "hi")，聚焦链路耗时而非生成长度。

probe 脚本（`build/perf-probe/`):

| 脚本 | 复刻对象 |
|---|---|
| `timed-run.mjs` | one-shot 通用（cmd.exe /d /s /c 包裹，stdin 写完即关，对齐插件行为） |
| `claude-persistent-probe.mjs` | `ClaudePersistentSendPath` stream-json 双向长驻模式，单进程两轮 |
| `opencode-serve-probe.mjs` | `OpenCodeServeManager` + `OpenCodeServeSession`:spawn serve → TCP 轮询就绪 → POST /session → GET /event SSE → POST prompt_async |
| `kimi-acp-probe.mjs` | `KimiAcpCliSession`:spawn `kimi acp` → initialize → session/new → set_config_option(thinking) → session/prompt |

## 2. 实测耗时

| Provider | 通道 | 本地链路开销 | 首个模型输出 | 总耗时 | 备注 |
|---|---|---|---|---|---|
| claude | one-shot 冷启 | spawn→首行 0.4~1.2s,init 0.7~3.7s | 4.2s(无 MCP)/ 20.9s(带 MCP 冷) | 4.8s(裸)~ 21.2s(冷) | `duration_api_ms` 17.2s 全在 API 侧 |
| claude | 长驻稳态(turn2) | ≈0(复用进程) | 6.3s | 6.3s | 省掉 spawn+init；本轮 init 遇 MCP 冷启花了 15s,方差大 |
| codex | one-shot | thread.started 0.52s,turn.started 0.68s | 端点故障未测得 | — | 2026-09-01 文档(端点健康时):thinking 6.6s、content 12.7s、总 ~14s |
| opencode | one-shot | **首个事件 10.7s**(MCP 冷启) | 12.8s | 17.2s | 输入 62720 tokens(MCP 工具 schema) |
| opencode | **serve(插件默认)** | spawn→ready **0.69s**,session 建好 1.06s | prompt→step-start 10.8s | 14.0s | 稳态轮次 spawn 开销为 0 |
| kimi | **ACP(插件默认)** | spawn+initialize 0.63s,session/new 0.85s | prompt→首 chunk 3.6s | **5.8s(全场最快)** | 暖池+连接复用，稳态再省 ~0.85s |
| kimi | legacy stream-json | 首行 0.6s | 7.2s(无流式 delta,整段返回) | 7.5s | |

补充说明：

- claude one-shot 首轮 21.2s 中,`duration_api_ms` = 17.2s,input_tokens = 56977——绝大部分是用户自配 6 个 MCP server 的工具 schema + superpowers 插件 SessionStart hook 注入的上下文。
- codex 测试当日 Galaxy 端点(`https://gpt.eacase.de5.net`)流式响应解码失败("stream disconnected before completion: error decoding response body"),CLI 以 ~200s/次退避重连，总耗时无法测得；9/1 文档已证明端点健康时插件本地链路仅 0.2~0.36s。
- 本机网络环境：到各端点 RTT 约 2~3s(curl 实测 bigmodel 200/3.0s,Galaxy 451/6.0s,api.kimi.com 根路径超时但 ACP 实际可用)。

## 3. 参数传递验证(模型 / 思考程度 / 权限模式)

| Provider | 模型 | 思考程度 | 权限模式 |
|---|---|---|---|
| claude | ✅ init 与 message_start 回显 `glm-5.3-flash` | ✅ `--effort high` 出现 thinking 块、`low` 时 thinking_tokens=0;**注意**:`ClaudeCliModelResolver.supportsEffort` 只对 canonical `claude-` 模型放行，当前 glm 模型下插件实际**不会传** `--effort`(probe 为手动添加，CLI 接受且生效) | ✅ init 回显 `bypassPermissions` |
| codex | ✅ rollout turn_context `model: gpt-6-astra` | ✅ `effort: "high"` + `collaboration_mode.settings.reasoning_effort: "high"` + `summary: "auto"` | ✅ `approval_policy: never` + `sandbox_policy: danger-full-access` |
| opencode | ✅ serve body `{providerID, modelID}` 204 接受;⚠️ actualModel 无 `/` 时**静默丢失模型选择**(`OpenCodeServeSession.java:343-351`) | ✅ `--variant high` → step_finish `reasoning: 66` tokens | ✅ one-shot `--auto`;serve bypass→`always`(AGENTS.md 总则六记录的有意差异) |
| kimi | ⚠️ **ACP 通道完全不传 model**(`KimiAcpCliSession` 全程无 `request.model()` 使用),插件选模型对 ACP 无效；实测 ACP `session/new` 返回 `model` configOption(k3 / K2.7 / K2.7 Highspeed / K3-256k 四档),**插件侧可低成本补齐**;legacy 通道 `--model` 正常 | ✅ `set_config_option thinking=high` 被接受(词表 low/high/max,与插件 `mapThinkingEffort` 一致) | ➖ kimi headless 固定 auto/yolo,插件不映射(设计如此) |

codex 参数验证方式：端点故障无法跑完整轮次，改从 `~/.codex/sessions/**/rollout-*.jsonl` 的 turn_context 记录确认实际生效配置。

## 4. 耗时归因

- **主要瓶颈全部在 AI 侧**：端点 RTT、模型 TTFT、大上下文输入(5.7 万~6.3 万 tokens,来自用户自配 MCP 工具 schema)、端点故障重连。
- **插件本地链路已经很薄**:Java 侧请求准备 ≈0~80ms(MCP gateway 快照有 configHash 缓存),spawn→首行 0.4~1.2s(cmd + node 冷启),opencode serve 0.7s 就绪，kimi ACP 0.85s 完成握手。
- 前端回显节流(SessionCallbackAdapter delta 33ms、StreamMessageCoalescer streaming 下限 150ms)相对秒级延迟可忽略。

## 5. 插件侧可优化空间(按收益排序，本次均未改代码)

1. **claude 的 MCP 冷启是最大插件侧杠杆**。插件不传 `--strict-mcp-config`,用户 settings.json 里的 6 个 npx/本地 MCP server 每次冷启动全量连接(实测 init 波动 0.7s~15s)且贡献 5.7 万 token 上下文拖慢 API。codex 路径已做"逐个 `-c mcp_servers.<id>.enabled=false` 禁用真实 server + melon_gateway 聚合"(`McpGatewayConfigWriter.buildCodexOverrideArgs`),**claude 路径未做等价禁用**(`McpGatewayConfigWriter.writeClaude` 只写 gateway 入口，真实 server 仍从 settings.json 加载)。对齐后冷启动与每轮 token 开销均可显著下降，但属行为变更，需评估对用户既有 MCP 直连习惯的影响。
2. **kimi ACP 不传 model**——正确性缺口 + 唯一明确的"参数未传递"问题；ACP 已暴露 model configOption(`session/set_config_option`,configId="model"),补齐成本极低。
3. **kimi ACP 每轮必发同步 `set_config_option` RPC**(10s 超时)即使 thinking 档位未变(`KimiAcpCliSession.java:640-656`)——可加"档位未变跳过"判断，收益小。
4. **opencode serve `acquire` 为 synchronized 且锁内 spawn + TCP 轮询**(`OpenCodeServeManager.java:99-129`,上限 5s),多 tab 并发首轮互相阻塞——单 tab 无感。
5. **codex 无进程复用**,每轮 cold spawn 约 0.5~0.7s;按 9/1 文档结论，app-server/persistent 复用理论收益也就这几百毫秒，优先级低。一致性小项:`CodexCliSession` 只用 `request.model()`,忽略 `SessionSendService` 已解析的 `actualModel`(`CodexCliSession.java:1297`、`:1362`)。
6. **claude 长驻指纹漂移降级**:切模型/权限/思考档位 → 当轮退化 one-shot 冷启 + 后台重建(`ClaudePersistentSendPath.buildFingerprint` :93-105),高频切换用户每轮多付 3~15s。属设计取舍。
7. opencode serve model 拆分：actualModel 无 `/` 时静默不传 model(见第 3 节),可改为显式回退或告警。

## 6. 插件解决不了的(AI 侧，不处理)

- codex Galaxy 端点当前流式解码失败 + 200s 重连退避(测试当日 codex 不可用的直接原因；插件已把 Reconnecting 判为致命错误即时终止上报,`CodexCliSession.isFatalCodexError` :515-526，不会挂到 15 分钟超时);
- bigmodel 代理在大上下文(5.7 万 tokens)下的 TTFT(首 token 4~17s);
- opencode 模型侧 8~11s 推理延迟;kimi 3.6s 首 chunk;
- 本机到各端点 2~3s 的网络 RTT。

## 7. 已知测量误差与边界

- 各 provider 只测了 1~3 次，样本小；MCP 冷/暖、端点负载导致秒级方差(claude init 实测 0.7s~15s)。
- probe 未注入插件的 MCP gateway(claude `--mcp-config` / codex gateway `-c` 覆盖 / opencode `OPENCODE_CONFIG_CONTENT`),gateway 相关开销以 9/1 文档的 `[McpGatewayPerf]` 日志为准(命中缓存后 0~1ms)。
- 未在真实 IDE 内跑插件(sandbox 日志目录已清空),Java/JCEF 侧分段耗时引用既有日志点与 9/1 文档证据。

## 8. 修复落地情况(2026-09-07)

§3 / §5 列出的插件侧可处理项当日全部修复，按 AGENTS.md §8 逐项独立 commit:

| 文档条目 | 状态 | commit | 方案 |
|---|---|---|---|
| §3/§5.2 kimi ACP 不传 model | ✅ 已修复 | `727178e7` fix(kimi) | 解析 session/new 响应 configOptions 中 `configId="model"` 目录,`negotiateModelValue` 协商(actualModel 优先、哨兵值过滤、忽略大小写、词表外不发)后 `session/set_config_option` 下发;`KIMI_MODEL_SENTINELS` 抽至 `CliConstants` 两通道共用 |
| §5.3 kimi ACP 每轮必发 thinking RPC | ✅ 已修复 | `b7b136d3` fix(kimi) | `shouldApplyThinkingConfig` + `lastAppliedThinking` 去重,档位未变 / 已等于目录 currentValue 时跳过同步 RPC |
| §5.5 codex 忽略 actualModel | ✅ 已修复 | `335f5e29` fix(codex) | exec / resume 两路径 `-m` 及 service-tier 检查改用 `firstNonBlank(actualModel, model)`,对齐其他 CLI 会话 |
| §3/§5.7 opencode serve model 无 `/` 静默丢失 | ✅ 已修复 | `e51dee26` fix(opencode) | 抽 `splitModelRef`,拆分失败显式 LOG.warn(serve API 要求双段,无安全回退,行为仍为省略) |
| §5.1 claude MCP 聚合未对齐 codex | ✅ 已修复(行为变更) | `aa3c52b3` feat(claude) | gateway 启用时加 `--strict-mcp-config`(one-shot + 长驻两路径),settings.json 真实 server 不再直连、只经 melon_gateway 聚合;官方 CLI reference 确认该 flag 语义 |
| §5.4 opencode serve acquire 锁内 spawn | ✅ 已修复 | `3b89284f` perf(opencode) | spawn 移出 synchronized,`spawnFuture` 去重共享 + 锁外 join + 锁内复核安装;`teardownGeneration` 防 terminate/dispose 与 in-flight spawn 竞态 |
| §3 claude `supportsEffort` 只对 canonical 放行 | ✅ 已修复 | `b84e099d` fix(claude) | 官方 CLI reference 确认 `--effort` 为会话级 flag("Available levels depend on the model",CLI 侧协商,无模型名限制);删除 canonical 前缀门控,`no-effort` capabilities 覆盖保留为逃逸口 |

**未处理(文档自身定性)**:

- §5.5 codex 无进程复用——doc 评估收益仅几百毫秒，优先级低;
- §5.6 claude 长驻指纹漂移降级——设计取舍;
- §6 全部(AI 侧:端点故障、TTFT、网络 RTT)——插件无法解决。

**验证**:定向测试 295 个(`KimiAcp*` / `CodexCliSessionTest` / `OpenCodeServe*` / `McpGateway*` / `ClaudeCli*`)通过;6 个失败经 HEAD 基线 worktree 对照确认为预先存在的环境性失败(ai-bridge 单测环境解包报错 + `McpGatewayLifecycleTest` 一项),与本次改动无关。commit `727178e7`(人工切分的中间态)已在独立 worktree 验证可单独编译。
