---
title: 总则二·开闭原则与模块解耦
priority: HIGH
category: principle
tags: 后端, 开闭原则, 解耦
---

## 总则二：开闭原则与模块解耦（后端）

**原则**：对**扩展开放**，对**修改关闭**；模块之间**单向依赖、只依赖抽象**。

**模式**：

- **策略注册表**：定义接口 + `support(type)` 判定方法 + 由容器注入的 `List<接口>` 集合
- **模板方法 + 钩子**：抽象基类固化公共流程，子类只覆盖少数钩子方法
- **事件驱动解耦**：跨模块的副作用通过事件发布/订阅

**落地指引**：

- 新增**上行 action** 处理：**必须**实现 `handler/core/FrontendActionHandler<T>` 泛型接口
- 新增**下行事件**派发：type **必须**使用 `DownstreamEvent` 枚举常量（`.value()`）
- 新增**领域 handler**：按 `handler/{domain}/` 分目录组织，单一职责

---

> ← 返回 [SKILL.md 索引](../SKILL.md)
