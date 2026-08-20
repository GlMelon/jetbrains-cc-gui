---
title: 总则五·拓展点预留
priority: MEDIUM
category: principle
tags: 扩展接口, 门面, 适配器
---

## 总则五：拓展点预留

**原则**：所有**可能变化的能力**必须预留**扩展接口**，新增实现不改既有代码。

**模式（Docking 通用化思想）**：

1. **门面（Service）**：对外提供统一入口，内部做路由分发
2. **适配器（Adapter 接口）**：每个外部能力一个实现，用 `support(type)` 声明自己能处理哪种类型
3. **执行器 + 配置外置**：把易变的部分外置为配置

**硬编码禁止规范**：

- **Provider 协议值**：必须使用 `ProviderType.X.value()` 或 `CommonConstants.PROVIDER_X`
- **CLI 可执行文件名**：必须使用 `ProviderType.X.cliCommandForPlatform()`
- **JSON 配置键名**：必须使用 `ProviderType.X.value()` 或 `CommonConstants.PROVIDER_X`
- **消息类型常量**：必须使用 `CliConstants.MSG_*` 或 `CommonConstants.MSG_*`

---

> ← 返回 [SKILL.md 索引](../SKILL.md)
