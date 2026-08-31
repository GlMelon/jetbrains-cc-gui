---
name: jetbrains-plugin-architecture
description: JetBrains 插件架构开发规范 - 本项目的最高架构准则。所有 AI agent 在生成或修改代码时必须先阅读并遵循本规范。触发场景：生成/修改前后端代码、新增协议消息或 handler、对接 provider、调整目录结构或提交 Git。
license: MIT
metadata:
  author: claudecodegui
  version: "2.0.0"
---

# JetBrains 插件架构开发规范

本规范是本项目的**最高架构准则**。所有 AI agent（Claude Code / Codex / 其他）在生成或修改代码时，**必须**先阅读并遵循本规范；所有人类开发者在提交代码前，**必须**对照合规检查清单与 Git 提交规范自检。

> 本 skill 采用**渐进式披露**结构：本文件仅是入口索引，详细规则按主题拆分到 `references/` 下独立文件，按需加载。

## 项目概述

本项目是一个 JetBrains 平台插件，采用三层运行时架构：

- **后端**：IntelliJ 插件主体（Java，`src/main/java/com/github/claudecodegui/`），承载全部业务逻辑、状态权威与持久化
- **前端**：React + TypeScript webview（`webview/`），通过 JCEF 嵌入，**只负责渲染回显与输入采集**
- **ai-bridge**：独立 Node 进程（`ai-bridge/`），负责 CLI 进程管理与消息流处理

通信架构详见 [`references/communication-architecture.md`](references/communication-architecture.md)。

## When to Apply

在以下场景触发本规范：

- 生成或修改**前后端任一代码**（Java / TypeScript / Node）
- 新增**上行 action / 下行 event** 或对应 handler
- 对接或扩展 **AI provider**（Claude / Codex / OpenCode / Grok / Kimi / Pi / OMP / DSH）任一调用路径
- 调整**目录结构**、新增领域模块、新增协议枚举
- 编写或修改**测试**、撰写 **Git 提交信息**
- 代码审查 / 自检合规

## 核心架构准则速览

六大总则，按优先级排列。每条细则见对应 `references/` 文件。

| 序号 | 总则 | 优先级 | 一句话 | 细则文件 |
|---|---|---|---|---|
| 一 | 前后端职责分离 | **最高** | 前端只渲染回显/输入采集，业务逻辑一律下沉后端 | [`principle-1-frontend-backend-separation.md`](references/principle-1-frontend-backend-separation.md) |
| 二 | 开闭原则与模块解耦 | 高 | 对扩展开放、对修改关闭，单向依赖只依赖抽象 | [`principle-2-open-closed-decoupling.md`](references/principle-2-open-closed-decoupling.md) |
| 三 | 契约层单一真相源(SSOT) | 高 | 协议名/payload/枚举/默认值/常量唯一来源 | [`principle-3-contract-ssot.md`](references/principle-3-contract-ssot.md) |
| 四 | 组件化与复用 | 中 | 重复逻辑消除二义：组件化或下沉后端单点 | [`principle-4-component-reuse.md`](references/principle-4-component-reuse.md) |
| 五 | 拓展点预留 | 中 | 可能变化的能力预留扩展接口，新增实现不改既有代码 | [`principle-5-extension-points.md`](references/principle-5-extension-points.md) |
| 六 | 多 provider 对称/完整/健壮 | 高 | 8 个 Provider 的 CLI 路径等价、全覆盖、确定性取消 | [`principle-6-provider-symmetry.md`](references/principle-6-provider-symmetry.md) |

## 参考文档索引

| 主题 | 文件 |
|---|---|
| 通信架构（JCEF 总线 + 进程边界） | [`references/communication-architecture.md`](references/communication-architecture.md) |
| 前端架构（目录 / Hooks / 数据流） | [`references/frontend-architecture.md`](references/frontend-architecture.md) |
| 后端架构（协议枚举 / Handler / Provider 适配器） | [`references/backend-architecture.md`](references/backend-architecture.md) |
| 测试规范（定向验证 + 命令） | [`references/testing-guidelines.md`](references/testing-guidelines.md) |
| Git 提交规范（格式 / type / scope） | [`references/git-commit-conventions.md`](references/git-commit-conventions.md) |
| 合规检查清单（23 项架构 + Git 自检） | [`references/compliance-checklist.md`](references/compliance-checklist.md) |
| 术语表 | [`references/glossary.md`](references/glossary.md) |
| 参考资源 | [`references/further-reading.md`](references/further-reading.md) |
| 新增规则模板 | [`references/_template.md`](references/_template.md) |

## 维护约定

- 新增架构规则：复制 `references/_template.md`，命名 `<area>-<name>.md`，并在本文件「参考文档索引」或「核心架构准则速览」表登记
- 元数据**仅**存于本文件 frontmatter，不另设 `metadata.json`（遵循总则三 SSOT，避免重复）
- 修改规则时同步更新本文件速览表的一行摘要
