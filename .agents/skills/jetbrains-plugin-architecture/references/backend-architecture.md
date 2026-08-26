---
title: 后端架构
category: architecture
tags: 后端, 协议枚举, handler, provider
---

## 后端架构

### 协议枚举

- **UpstreamAction**：前端 → 后端的所有 action 类型
- **DownstreamEvent**：后端 → 前端的所有 event 类型

### Handler 架构

- **FrontendActionHandler<T>**：泛型接口，声明 `UpstreamAction`、`payloadType()`、`handle(T, ctx)`
- **FrontendActionDispatcher**：按 `handler.action().value()` 建 `LinkedHashMap` 路由

### Provider 适配器

- **ProviderAdapter** 接口 + **ProviderRegistry**（`Map<ProviderId, ProviderAdapter>`）
- **SessionRuntime** 接口（以 `provider()` 声明路由键）+ **SessionRuntimeRegistry**（`Map<ProviderType, SessionRuntime>` 查表）

---

> ← 返回 [SKILL.md 索引](../SKILL.md)
