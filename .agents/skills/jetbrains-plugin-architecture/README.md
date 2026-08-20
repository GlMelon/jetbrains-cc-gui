# jetbrains-plugin-architecture

本项目的最高架构准则 skill。

## 结构

- `SKILL.md` — 入口索引（frontmatter + 概述 + When to Apply + 总则速览 + 文档索引）
- `references/` — 详细规则按主题拆分的独立文件
  - `principle-1..6-*.md` — 六大架构总则
  - `communication-architecture.md` / `frontend-architecture.md` / `backend-architecture.md` — 架构说明
  - `testing-guidelines.md` / `git-commit-conventions.md` / `compliance-checklist.md` — 规范与自检
  - `glossary.md` / `further-reading.md` — 术语与参考
  - `_template.md` — 新增规则模板

## 设计原则

采用 Claude Code Agent Skills 的**渐进式披露**：`SKILL.md` 保持精简，仅含触发判定与索引；细则落入 `references/`，按需加载。这与总则三（SSOT）一致：元数据只在 frontmatter 一处，不另设 `metadata.json`。

## 维护

新增规则：复制 `references/_template.md` → 命名 `<area>-<name>.md` → 在 `SKILL.md` 索引表登记。
