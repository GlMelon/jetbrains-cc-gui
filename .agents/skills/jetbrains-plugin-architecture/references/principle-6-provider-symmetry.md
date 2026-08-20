---
title: 总则六·多 provider 对称性、完整性与健壮性
priority: HIGH
category: principle
tags: provider, 对称性, 健壮性
---

## 总则六：多 provider 调用对称性、完整性与健壮性

**原则**：本插件支持 3 个 AI provider（Claude / Codex / OpenCode），每个有 2 种调用模式（SDK daemon / CLI 子进程），共 **6 条调用路径**。

**三项要求**：

- **对称性（Symmetry）**：Claude / Codex / OpenCode 在 SDK 与 CLI 两模式下，对每一类处理逻辑等价
- **完整性（Completeness）**：每类处理**必须**覆盖全部 provider × mode 组合
- **健壮性（Robustness）**：
  - **确定性取消优先**：interrupt / abort 应显式通知 provider 取消
  - **边界与防御**：null / 空值**必须**显式处理
  - **进程生命周期完备**：stdin 写入并关闭、stdout 必须 drain、进程退出必须清理

---

> ← 返回 [SKILL.md 索引](../SKILL.md)
