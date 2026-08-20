---
title: 测试规范
category: convention
tags: 测试, 定向验证
---

## 测试规范

### 定向验证原则

验证本次改动时**只跑与改动直接相关的测试**，**禁止**每次全量跑测试套件。

### 测试命令

- **ai-bridge**（Node 内置 test runner）：`cd ai-bridge && node --import tsx --test test/<改动路径>`
- **webview**（vitest）：`cd webview && npx vitest run <改动路径>` 或 `npx vitest run -t "<测试名>"`

### 无测试覆盖的改动

改动文件无任何测试引用时，以改动孤立性 + `tsc --noEmit` typecheck 兜底即可。

---

> ← 返回 [SKILL.md 索引](../SKILL.md)
