# grok/kimi/pi 功能对齐落地记录(2026-08-27)

> 背景:三 provider 原经 ai-bridge channel 转换(channel→marker),统一重构 a5d71068 切到直 spawn
> 原生 CLI 后命令布局残缺(沿用 opencode 参数,三家原生 CLI 不识别)。本批按「完成直 spawn 路线」
> 方向修复并对齐成熟 provider 的能力面。决策纪要见会话(用户三项拍板:直 spawn/kimi 思考区暂缓/
> MCP 机制确认后再定)。

## 已落地(commit 324d2e24 + 02ab1563)

### 思考区
| provider | 状态 | 机制 |
|---|---|---|
| grok | ✅ | streaming-json `type:"thought"` 事件(增量式)→ THINKING + THINKING_DELTA |
| pi | ✅ | `--mode json` message_update.thinking_delta(delta-only 协议,一等公民)+ showThinking 门控 --thinking 级别 |
| kimi | ⛔ 有意不支持 | 官方 stream-json **不写 thinking**(走 stderr transcript 文本,无结构化边界);descriptor 不声明 REASONING_THINKING,前端无思考区。官方若未来输出再补 |

### 工具调用
- **grok**:stdout 无工具事件 → Java 版 chat_history.jsonl 尾随(`GrokToolHistoryTailer`,
  300ms 计划任务 + 字节偏移增量 + seen 去重 + resume 首见基线跳过),合成 `[MESSAGE]` 行注入
  parser;最终 drain 在 stream_end 判定前执行(onStopAuxiliary 钩子)。⚠️ Windows 下 cwd 编码
  与 JS encodeURIComponent 存在理论残留差异(奇异目录名),届时退化为工具信号缺失不崩溃。
- **kimi**:stream-json 快照行(assistant 重放增长前缀→快照合并去重;tool_calls 以 id|args key 去重;
  tool 角色行→tool_result)。
- **pi**:tool_execution_start/end 天然配对(start→pending 卡片,end→结果填充,isError 直通)。
- 前端渲染链路零改动(CodexMessageHandler + ContentBlockRenderer 本就 provider 无关)。

### 会话续接
- grok:首轮预分配 UUID `-s`,续轮 `-r`(B13 失效重试沿基类)。
- kimi:`--session <id>` 续接,id 来自 meta.session.resume_hint 回传。
- pi:`--session-id`;注意官方非交互仅支持显式 id(`-c`「最近会话」语义与插件 per-tab 显式管理不符,未用)。

### usage/token
- grok:end 事件 usage → MSG_USAGE;kimi:无独立事件(v1 无 usage 展示);
  pi:message_end.message.usage → MSG_USAGE。三家里 grok/pi 与既有 handler 兼容。

### 历史记录回显
- **grok** ✅:`~/.grok/sessions/<jsEncodeCwd>/<sid>/chat_history.jsonl`;目录名 URL 解码后与项目
  路径做前缀匹配;消息行映射 Claude 兼容块;DELETE=递归删目录。
- **kimi** ✅:$KIMI_CODE_HOME/sessions/.../state.json(cwd 用候选键探测:title/workDir 等字段名未全公开)
  + agents/main/wire.jsonl 容错 role 解析;cwd 无法归位的会话跳过不猜。
- **pi** ⛔ 暂缺:官方未提供等价外置会话归档读取面;待单独专项(session JSONL 在 ~/.pi/agent/sessions,
  格式 spec 已知但树结构组织待实测)。
- 注册:HistoryProviderRegistry.createDefault 增加 GROK/KIMI 两 adapter(capabilities=[DELETE])。
- descriptor:grok+=REASONING_THINKING+HISTORY;kimi+=HISTORY;pi+=REASONING_THINKING。

## MCP gateway 注入 —— spike 结论(决策:暂缓,长期走 ACP)

| provider | 单次调用级覆盖 flag | 结论 |
|---|---|---|
| grok | ❌ headless flags 无 mcp 覆盖;配置在 ~/.grok/config.toml(TOML) | 改用户文件需 turn 级备份恢复,崩溃残留风险不可接受 → 暂缓 |
| kimi | 部分:项目级 .kimi-code/mcp.json 同名覆盖用户级(可写 melon_gateway+同名禁真实 server) | 但会污染仓库工作区(git status 噪声)/并发会话互相踩 → 暂缓待产品决策 |
| pi | ❌ 官方故意不内置 MCP(设计原则,靠扩展) | N/A |
| **长期正解** | 两家均支持 ACP 模式且原生支持 mcpServers:`grok agent stdio` 的 session/new、`kimi acp` | ACP 集成专项时一并解决,顺带补齐 grok 图片附件(ACP content blocks) |

McpGatewayConfigWriter 维持 default→disabled 三家现状(reason string 不变)。

## 遗留清单
1. **ai-bridge 死代码清理**:grok/kimi/pi 三家 message-service+history-tools+cli-image-input.js 的
   send 路径已无调用方(channels 的 send 分支死);listModels 仍活(get_cli_models 链路),
   清理需连同 channel-manager 路由一并审视——单独批次,建议运行时验证后执行。
2. **pi 历史读取器**(上述);配套 descriptor 补 HISTORY + registry 注册一行。
3. **grok 图片附件**:headless 流式无已知附件 flag,当前静默丢弃(与旧 bridge 行为一致);ACP 路线补齐。
4. **kimi thinking**:官方限制;跟踪官方 changelog。
5. **运行时验证**:本轮为单测级验证;真机跑通三家(grok thought 流/kimi 快照合并/pi delta 组装/
   尾随工具实时卡片/历史面板两家列表+回显+删除)需 IDE 沙箱人工过一轮。
6. MarkerRunOnceCliSession 已删除;MarkerCliStreamParser 仅剩 omp/dsh channel 使用(勿误删)。

## 测试基线(定向,AGENTS §9)
- GrokCliStreamParserTest(7)/GrokToolHistoryTailerTest(6)/KimiCliStreamParserTest(8)/
  PiCliStreamParserTest(6)/CliDialectSessionSymmetryTest(3)/GrokHistoryReaderTest(4)/
  KimiHistoryReaderTest(3)/CliHistoryProviderAdaptersTest(2)/ProviderDescriptorContractTest ✓
- 附带修复两个 v0.5.4 合并遗留的测试编译错(f11b7d3b):ClaudeMessageHandlerDedupTest 重复方法、
  SessionCallbackAdapterStreamEndTest 构造器 arity。
