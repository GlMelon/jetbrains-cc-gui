---
title: Git 提交规范
category: convention
tags: git, 提交信息, scope
---

## Git 提交规范

### 提交信息格式

```
<type>(<scope>): <subject>

<body 可选>
```

- **一律英文**：subject 与 body 全英文
- **subject 小写起首、祈使句、末尾不加句号**
- **subject ≤ 72 字符**
- **scope 强烈建议带上**

### 类型（type）定义

| type | 含义 | 何时使用 |
|---|---|---|
| `feat` | 新功能 | 用户/前端可感知的新行为、新增协议字段下发 |
| `fix` | bug 修复 | 修复错误行为/崩溃/数据不一致 |
| `refactor` | 重构 | 既非新功能也非修 bug 的内部调整 |
| `docs` | 文档 | 仅改 `.md` / 设计文档 / 注释性说明 |
| `test` | 测试 | 新增/修复/调整测试，不改产品代码 |
| `style` | 格式 | 空白/颜色/样式微调，不影响逻辑 |
| `build` | 构建/版本 | `build.gradle` / 版本号 / checkstyle 配置 |
| `chore` | 杂项维护 | 清理无用 import / 依赖 / 配置 |
| `i18n` | 国际化 | locale 键值增删 |

### 作用域（scope）约定

- **分层**：`webview`（前端）、`ai-bridge`（Node 进程）；后端可不带 scope 或用领域名
- **领域**：`session` / `settings` / `model` / `model-registry` / `protocol` / `dialog` / `runtime` / `provider` / `bridge` / `handler`
- **横切**：`test` / `format` / `dependency` / `config` / `perf`

---

> ← 返回 [SKILL.md 索引](../SKILL.md)
