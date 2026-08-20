---
title: 术语表
category: reference
tags: 术语, SSOT, OCP
---

## 术语表

- **SSOT**（Single Source of Truth，单一真相源）：某份契约/数据只有一个权威来源，其他地方都从它派生
- **OCP**（Open-Closed Principle，开闭原则）：对扩展开放，对修改关闭
- **Adapter / support 路由**：适配器接口 + 一个「我能否处理此类型」的判定方法，集合注入后按判定分派
- **门面（Facade）**：对外统一入口，内部路由到具体实现
- **上行/下行**：上行 = 前端 → 后端（`sendToJava` / `UpstreamAction`）；下行 = 后端 → 前端（`dispatch` / `DownstreamEvent`）
- **payload**：协议消息携带的数据体，区别于消息名（type）

---

> ← 返回 [SKILL.md 索引](../SKILL.md)
