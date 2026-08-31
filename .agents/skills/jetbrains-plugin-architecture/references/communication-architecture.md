---
title: 通信架构
category: architecture
tags: JCEF, 协议, ai-bridge
---

## 通信架构

### JCEF 双向字符串总线

- **上行**（前端 → 后端）：`window.sendToJava({type, content})`，type 取值见 `protocol/UpstreamAction` 枚举
- **下行**（后端 → 前端）：`window.__bridge.dispatch(type, payload)`，type 取值见 `protocol/DownstreamEvent` 枚举

### 进程边界（Java ↔ CLI / ai-bridge）

NDJSON 字符串契约，**无 Node 类型泄漏**。Java 后端的 CLI 会话层（`ChannelCliSession`、`AbstractRunOnceCliSession`、`CliPersistentProcess`，按 Provider 经 `CliSessionFactory` 装配）统一负责子进程生命周期。需要 ai-bridge 协议转换的路径以 `node channel-manager.js <provider> <action>` 启动子进程，经 stdin 投递 JSON、读取 stdout NDJSON 行；one-shot 与 persistent 路径则按各 Provider 的 CLI 协议执行，但都必须遵守 stdin close、stdout/stderr drain、确定性 interrupt/terminate 和退出清理约束。

旧的 SDK（daemon）调用模式与 `BaseSDKBridge.executeStreamingCommand` 已移除，不应作为当前架构或新增 Provider 的参考。

ai-bridge 内部 provider 路由已遵循 Adapter 范式（`ai-bridge/channels/provider-registry.js` 用 `Map<provider, descriptor>` + `dispatch()`），是 Node 侧 Docking 正面范例。

MCP Gateway 是独立的 Project-scoped 基础设施：`McpGatewayService` 负责 Gateway process、catalog snapshot、revision、self-heal、direct fallback 与 dispose 闸门；Gateway 不等同于 Provider CLI 会话。`daemon.js` 仅保留用于识别和清理旧遗留 Node 进程，不能据此推导存在 SDK daemon 调用路径。

生命周期 correlation/generation metadata 和结构化事件由 Java 后端生成，再通过诊断快照或协议下发；Webview 只渲染后端已经计算好的结果。

---

> ← 返回 [SKILL.md 索引](../SKILL.md)
