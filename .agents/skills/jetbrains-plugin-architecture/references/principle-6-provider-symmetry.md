---
title: 总则六·多 provider 对称性、完整性与健壮性
priority: HIGH
category: principle
tags: provider, 对称性, 健壮性
---

## 总则六：多 provider 调用对称性、完整性与健壮性

**原则**：本插件支持 8 个 AI provider（Claude / Codex / OpenCode / Grok / Kimi / Pi / OMP / DSH）。SDK（daemon）调用模式和旧的 `BaseSDKBridge` 链路已移除；当前所有 Provider 均通过 CLI 子进程路径接入，按连接模型分为 one-shot CLI、persistent CLI、channel/ACP 等实现形态。

**三项要求**：

- **对称性（Symmetry）**：Claude、Codex、OpenCode、Grok、Kimi、Pi、OMP、DSH 在各自 CLI 实现形态下，对每一类横切处理逻辑保持等价；架构差异必须源于连接/调度模型，并具备等价保护
- **完整性（Completeness）**：每类处理**必须**覆盖全部 8 个 Provider 的 CLI 路径，不得遗漏 one-shot、persistent、channel 或 ACP 实现
- **健壮性（Robustness）**：
  - **确定性取消优先**：interrupt / abort 应显式通知 Provider，并通过进程树终止兜底，不能只依赖本地包装进程退出
  - **边界与防御**：null / 空值**必须**显式处理
  - **进程生命周期完备**：stdin 写入并关闭、stdout/stderr 必须 drain、进程退出必须清理；长驻路径还必须具备超时、容量、空闲回收和防重建风暴保护

## 当前落地路径

- `CliSessionFactory` 为每个 Provider 声明路由键并创建会话，`SessionRuntimeRegistry` 按 `ProviderType` 查表；新增 Provider 只增加实现和注册，不修改分派主体。
- `AbstractRunOnceCliSession` 固化 one-shot CLI 的 spawn、stdout/stderr drain、session 续接、interrupt 和清理流程。
- `CliPersistentProcess` 与 `CliPersistentProcessRegistry` 管理可复用的 persistent CLI 进程，并以容量上限、LRU/空闲回收、generation/epoch、重建冷却和 one-shot 降级防止失控。
- `ChannelCliSession` 通过 `node channel-manager.js <provider> <action>` 接入需要 ai-bridge 协议转换的 Provider；`KimiAcpConnection` 负责 Kimi ACP 的 NDJSON/JSON-RPC framing、pending request 终止和 stdout/stderr drain。
- `McpGatewayService` 是 Project-scoped Gateway 门面，Gateway 进程不属于某个 Provider；其生命周期、generation、自愈、超时、降级和 dispose 闸门必须独立完整。
- `daemon.js` 仅作为旧进程 orphan cleanup 的识别标记，不代表当前 Provider 的 SDK daemon 调用路径，也不是新的 Provider 接入方式。

生命周期事件和关联 metadata 由 Java 后端生成，使用 Project、Session、Turn、Gateway process、CLI process 的 correlation/generation id 记录 spawn、stdin close、stdout EOF、exit、terminate、rebuild、fallback、degraded 等事件。

---

> ← 返回 [SKILL.md 索引](../SKILL.md)
