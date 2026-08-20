---
title: 总则三·契约层单一真相源(SSOT)
priority: HIGH
category: principle
tags: SSOT, 协议, 枚举
---

## 总则三：契约层单一真相源（SSOT）

**原则**：前后端共享的一切——**协议消息名、payload 字段结构、枚举值、默认值、常量**——必须有**唯一的真相来源**。

**落地指引**：

- **协议消息名（SSOT）**：上行/下行消息名以 Java 枚举（`UpstreamAction` / `DownstreamEvent`）为唯一来源
- **payload 字段结构（SSOT）**：payload 的字段结构必须从后端**单一来源**生成或校验到前端
- **枚举值**：业务枚举必须有单一来源并生成到前端
- **序列化出口统一**：协议名统一以 `value` 为出口

---

> ← 返回 [SKILL.md 索引](../SKILL.md)
