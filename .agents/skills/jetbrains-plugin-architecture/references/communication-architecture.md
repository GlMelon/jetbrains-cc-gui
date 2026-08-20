---
title: 通信架构
category: architecture
tags: JCEF, 协议, ai-bridge
---

## 通信架构

### JCEF 双向字符串总线

- **上行**（前端 → 后端）：`window.sendToJava({type, content})`，type 取值见 `protocol/UpstreamAction` 枚举
- **下行**（后端 → 前端）：`window.__bridge.dispatch(type, payload)`，type 取值见 `protocol/DownstreamEvent` 枚举

### 进程边界（Java ↔ ai-bridge）

NDJSON 字符串契约，**无 Node 类型泄漏**。后端 `BaseSDKBridge.executeStreamingCommand` 以 `node channel-manager.js <provider> <action>` 启动子进程，经 stdin 投递 JSON、读 stdout NDJSON 行通信。

ai-bridge 内部 provider 路由已遵循 Adapter 范式（`ai-bridge/channels/provider-registry.js` 用 `Map<provider, descriptor>` + `dispatch()`），是 Node 侧 Docking 正面范例。

---

> ← 返回 [SKILL.md 索引](../SKILL.md)
