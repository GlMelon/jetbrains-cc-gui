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

### CLI 会话与生命周期

- 当前正式 Provider 为 Claude、Codex、OpenCode、Grok、Kimi、Pi、OMP、DSH，共 8 个；Provider 协议值和 CLI 命令由 `ProviderType` 统一提供，禁止在分派代码中硬编码字符串。
- `CliSessionFactory` 为每个 Provider 声明创建入口，`CliSessionManager` 通过注册表装配；新增 Provider 只新增 factory/runtime 实现并注册，不修改既有分派主体。
- `AbstractRunOnceCliSession` 提供 one-shot CLI 的模板流程；`CliPersistentProcess` / `CliPersistentProcessRegistry` 提供 persistent CLI 的复用、容量、空闲回收、generation/epoch 和防重建保护；`ChannelCliSession` 提供经 ai-bridge channel 的 NDJSON marker 流；`KimiAcpConnection` 提供 Kimi ACP 的 NDJSON/JSON-RPC 连接管理。
- 这些路径都必须覆盖 stdin close、stdout/stderr drain、确定性进程树终止、cwd/sessionId 等 null 边界和退出清理。one-shot 与 persistent 的连接模型可以不同，但必须具有等价的超时、终止和失控防护。
- `McpGatewayService` 是独立的 Project-scoped Gateway facade，负责 Gateway process 的启动、快照、catalog、自愈、direct fallback 和 dispose 生命周期，不把 Gateway 业务塞入 Provider 分派器。
- Java 后端的 `LifecycleObservabilityService` 生成 lifecycle metadata/event，关联 Project、Session、Turn、Gateway process 和 CLI process；前端只消费诊断快照，不自行推导生命周期业务结论。

---

> ← 返回 [SKILL.md 索引](../SKILL.md)
