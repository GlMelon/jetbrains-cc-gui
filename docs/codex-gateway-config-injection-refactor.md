# Codex Gateway 配置注入调研：临时 CODEX_HOME 致 os error 2 与重构方案

> **状态(2026-07-02):已实施。** §6 方案落地:Codex 走原生 codex.exe + `-c` 命令行覆盖(CODEX_HOME 真实),
> OpenCode 走 `OPENCODE_CONFIG_CONTENT` env 内联 JSON(HOME/XDG 真实),均免临时 home/免文件复制。
> 范围扩至 OpenCode(原 §6.7 "本次不动" 已撤销)。端到端待 IDE 实跑确认。
>
> 调研日期：2026-07-02
> 关联代码：`src/main/java/com/github/claudecodegui/mcp/McpGatewayConfigWriter.java`、`src/main/java/com/github/claudecodegui/session/runtime/CodexCliResolver.java`、`src/main/java/com/github/claudecodegui/cli/codex/CodexCliSession.java`

---

## 1. 故障现象

Codex CLI 请求失败：

```
Codex CLI 请求失败
原因: Error: 系统找不到指定的文件。 (os error 2)
退出码: Codex CLI exited with code: 1
```

- `(os error 2)` 是 Rust `std::io::Error` 在中文 Windows 上的本地化消息（`ERROR_FILE_NOT_FOUND`），说明 `codex.exe`（Rust 二进制）**已启动成功**，运行期找不到某个文件。
- 不是 Java spawn 不到 codex（idea.log 显示 `process started` + `first stdout line buffer reached`）。
- 进程启动后约 212ms 即崩，比 MCP server spawn 还早。
- **仅当 MCP Gateway 启用时复现**；关闭 gateway（直连真实 MCP）或手动直接跑 codex 均正常。

---

## 2. 根因（已 100% 复现）

**MCP Gateway 启用时，`McpGatewayConfigWriter.writeCodex`（`McpGatewayConfigWriter.java:62-84`）创建临时 CODEX_HOME，漏复制了 `model_catalog_json` 引用的文件。**

链路：

1. gateway 模式下，codex 不能用真实 `~/.codex/config.toml`——其 `[mcp_servers.*]` 是真实 MCP，需替换成单个聚合入口 `melon_gateway`。
2. codex（设计早期假设）无命令行覆盖 `mcp_servers` 的能力，故 `writeCodex` 创建临时 CODEX_HOME：
   - 复制 `auth.json`
   - `copyCodexStableSections` 把真实 config.toml 的稳定段（含 `model_catalog_json = "cc-switch-model-catalog.json"` **相对路径引用**）文本复制进临时 config.toml
   - 追加 `[mcp_servers.melon_gateway]`
   - env 注入 `CODEX_HOME=临时目录`
3. **但只复制了"引用"（config.toml 里的字符串），没复制"文件本身"（`cc-switch-model-catalog.json`）到临时 home。**
4. codex 相对 CODEX_HOME 解析 `model_catalog_json` → 临时目录里找不到 → `os error 2` → exit 1。

铁证（实测复现，与用户报错完全一致）：

```
$ ls <临时home>/                       # 只有 auth.json + config.toml，无 cc-switch-model-catalog.json
$ echo hi | CODEX_HOME=<临时home> codex exec --json --sandbox danger-full-access -m gpt-5.5 -
Error: 系统找不到指定的文件。 (os error 2)   # exit 1
```

idea.log 真实命令行（失败 tab `8f9db524`）：
```
cmd /c D:\nodejs\codex.cmd --ask-for-approval untrusted exec --json --color never --sandbox danger-full-access -C D:/project/jetbrains-melon-cc-gui -m gpt-5.5 -, stdinBytes=572
```
其临时 home 实际路径：`C:\Users\32979\.codemoss\mcp-gateway\<hash>\cli-gateway\codex\8f9db524-...\home\`，确认缺 catalog 文件。

---

## 3. 架构问题的本质

临时 CODEX_HOME 是"模拟一套 codex home"。**只要走"复制文件"这条路，就永远可能漏**——这次是 catalog，下次可能是 `sessions/`、`history.jsonl`、用户自定义的相对路径引用。

**根本解法是不复制——让 codex 继续用真实 `~/.codex`，只把 gateway 的 MCP 配置"叠加"上去。**

---

## 4. 三 provider 的 gateway 注入机制对比

| provider | MCP 配置载体 | 命令行覆盖 mcp | gateway 注入方式 | 造临时 home？ | 同类 bug 风险 |
|----------|------------|--------------|-----------------|-------------|-------------|
| **Claude** | `~/.claude.json` / `--mcp-config <file>` | ✅ `--mcp-config` | 写额外 `mcp-gateway.json`，命令行加载 | ❌（env 空，不碰 `~/.claude`） | 无 |
| **Codex** | `$CODEX_HOME/config.toml` 的 `[mcp_servers.*]` | 见 §5 | 临时 CODEX_HOME + 临时 config.toml | ✅ | **本次故障** |
| **OpenCode** | `$XDG_CONFIG_HOME/opencode/opencode.json` | ❌（serve 启动期固化） | 临时 home + 重定向 `HOME`/`XDG_*` | ✅ | 同类隐患（但 CLI 集成尚不可用，优先级低） |

---

## 5. codex 配置覆盖能力实测结论（关键）

实测 codex 0.142.5：

- ✅ **`-p, --profile <name>`**：把 `$CODEX_HOME/<name>.config.toml` **叠加**到真实 config，CODEX_HOME 不变 → 零文件复制。profile 能注入新 server。
- ⚠️ profile 对 `mcp_servers` 是**合并语义，非整体替换**：实测 profile 只写 `melon_gateway`、不禁用真实 server 时，真实 server（`webstorm_mcp`）仍被加载。**故要禁用真实 server 必须逐个 `[mcp_servers.<name>] enabled=false`**。
- ✅ **`-c key=value`**：命令行覆盖（项目已用于 `-c model_reasoning_effort="..."`）。`-c 'mcp_servers.<name>.enabled=false'` 实测生效（webstorm 连接 error 从 3 → 0）。
- ⚠️ **`codex exec resume`（续接会话）不支持 `-p`**，但**支持 `-c`**。
- ⚠️ `-c` 带 args 数组（如 `mcp_servers.x.args=['a','b']`）经 `cmd /c codex.cmd` 包装时引号转义不可靠；**直接 spawn 原生 `codex.exe`（argv 直传）亦然——数组元素必须用 TOML literal 字符串（单引号），不能用基本字符串（双引号）**。2026-07-03 实测：codex 的 `-c` 在 TOML 解析前对 value 做一次 `\\`→`\` 反转义，基本字符串 `"D:\\project\\x.js"` 里加倍的 `\\` 被还原成 `\`，致 `"D:\project"` 出现非法转义 `\p` → TOML 解析失败 → codex 退回把整个值当字符串 → `invalid type: string, expected a sequence in mcp_servers.melon_gateway.args`。literal 字符串 `'D:\project\x.js'` 不含 `\\` 序列，预反转义是空操作，TOML 正确解析数组（exec 与 exec resume 均实测通过）。`McpGatewayConfigWriter.tomlString` 据此实现：优先 literal，含单引号/换行才回退基本字符串。
- ✅ 原生 `codex.exe` 实测可直接 spawn（`--version` 输出 `codex-cli 0.142.5`），位于 npm nested 结构：`<npm-global>/node_modules/@openai/codex/node_modules/@openai/codex-win32-x64/vendor/x86_64-pc-windows-msvc/bin/codex.exe`。

---

## 6. 推荐重构方案（待实施）

**核心：增强 resolver 直接 spawn 原生 `codex.exe` → 废弃临时 CODEX_HOME → exec+resume 统一用 `-c` 注入。**

| 维度 | 当前（临时 CODEX_HOME） | 新方案 |
|-----|---------------------|--------|
| CODEX_HOME | 临时目录（复制 base） | **真实 `~/.codex`（不动）** |
| 复制文件 | auth.json + 稳定段（漏 catalog） | **零复制** |
| 丢文件风险 | 高 | **无** |
| spawn 方式 | `cmd /c codex.cmd`（引号脆弱） | **直接 spawn 原生 `codex.exe`** |
| mcp 注入 | 临时 config.toml | **`-c mcp_servers.*` 命令行** |
| exec/resume 一致 | — | ✅ 都走 `-c` |

改动点（重构时落地）：

1. **`CodexCliResolver`**（`session/runtime/CodexCliResolver.java`）：`inferCodexNativeExecutablePath` 当前只推断 `<shim-dir>/node_modules/@openai/codex/bin/codex.exe`（一层 node_modules）。需覆盖 npm **nested** 结构：`<shim-dir>/node_modules/@openai/codex/node_modules/@openai/codex-win32-x64/vendor/x86_64-pc-windows-msvc/bin/codex.exe`。对齐 `codex.js` wrapper 的 env（`CODEX_MANAGED_BY_NPM` / `CODEX_MANAGED_PACKAGE_ROOT`）。
2. **`CodexCliCommandUtils.addCodexExecutable`**：当 resolver 返回原生 `.exe` 时，不再 `cmd /c` 包装，直接作为命令首元素。
3. **`McpGatewayConfigWriter.writeCodex` 重写**：不再造临时 home / 复制 auth / 复制稳定段；改为生成 `-c` 参数列表（逐个禁用真实 mcp server，server 名来自 `McpGatewayConfigCollector`；注入 `melon_gateway`）。
4. **`McpGatewayCliConfig`** record：从"env 注入 CODEX_HOME"改为"携带 `-c` 参数列表"。
5. **`CodexCliSession.buildCommand`**：gateway 启用时，把 `-c` 参数列表注入 `appendExecArgs` 与 `appendResumeArgs` 两路径。
6. **核查 SDK 路径**：`McpGatewayService.buildSdkMcpServers`（CODEX）当前走 Node `applyCodexGateway`（config overlay），不造临时 home，需确认是否同样受益 / 无回归。
7. **OpenCode**：同类隐患（XDG 重定向），无 `-p` 等价机制，单独开技术债，本次不动。

---

## 7. 验证步骤（重构后）

- `echo hi | codex exec -c <gateway参数> -m gpt-5.5 -` → 无 os error 2，正常回复
- `codex exec resume -c <gateway参数>` → 续接会话也走 gateway
- 插件 `runIde`：Codex provider 发送消息，gateway 启用，正常回复且 gateway 日志显示 `melon_gateway` 接管
- 单测：resolver nested 推断、`writeCodex` 生成 `-c`、`buildCommand` 注入
- 回归：`CodexCliResolverCacheTest`、`McpGatewayConfigWriterTest`、`McpGatewayServiceTest`

---

## 8. 待探索未知（重构前需确认）

- `McpGatewayConfigCollector` 能否提供所有真实 mcp server 名（用于生成 `-c enabled=false`）
- `addCodexExecutable` 当前对 `.cmd` / `.exe` 的分支逻辑
- `buildSdkMcpServers` CODEX 路径是否会回归
- 临时 CODEX_HOME 的清理逻辑（重构后删除）
