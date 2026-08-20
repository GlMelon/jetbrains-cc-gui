---
title: 合规检查清单
category: checklist
tags: 自检, 架构, git
---

## 合规检查清单

### 架构准则检查

1. [ ] 本次改动有无「前端做业务计算/判定/归一化/决策/校验/配置默认值」？有则下沉后端
2. [ ] webview 有无新增 hardcode 业务数据表/常量？有则改后端下发
3. [ ] 前端有无解释协议字段业务语义？有则交后端
4. [ ] 新增 action 是否实现 `FrontendActionHandler<T>`？有无往字符串数组加分支
5. [ ] 新增能力是否修改了既有分派器主体？能否改为新增实现
6. [ ] 模块依赖是否只指向接口？有无跨领域依赖具体实现
7. [ ] 协议名/payload/枚举是否两端从单一来源更新？有无前端手写字面量
8. [ ] payload 是否单一生成？有无前后端各写解析器/默认值
9. [ ] 有无前后端各实现一遍的重复逻辑？业务重复是否已下沉
10. [ ] 新增常量/类型/校验是否复用已有定义
11. [ ] 外部能力对接是否用了 if/else 硬编码？能否改 Adapter + support 路由
12. [ ] 新增同类能力是否需要改既有代码？是否预留了扩展接口
13. [ ] 下行事件是否使用 `DownstreamEvent` 枚举常量？有无散落字面量
14. [ ] 协议 type 是否从 `generated/protocol.ts` 导入？有无手写字面量
15. [ ] 代码中是否出现 provider 字面量？是否使用 `ProviderType.X.value()`
16. [ ] CLI 可执行文件名是否使用 `ProviderType.X.cliCommandForPlatform()`？有无硬编码字面量
17. [ ] JSON 配置键名是否使用 `ProviderType.X.value()`？有无硬编码字面量
18. [ ] switch case 中的字符串值是否使用常量引用？有无硬编码字面量
19. [ ] 是否存在重复常量定义？是否统一引用 SSOT
20. [ ] 改动某 provider 某项处理时，是否对照另两 provider 同项实现？是否等价或已记录差异
21. [ ] 该处理项是否覆盖全部 provider × mode 组合？有无遗漏某条路径
22. [ ] interrupt/abort 是否确定性取消（显式通知 provider），而非仅杀本地进程
23. [ ] null/空值边界（stdin/cwd/sessionId/baseUrl）是否显式处理

### Git 提交检查

- [ ] 每个 commit 是否只含**单一性质**的改动？有无功能/修复/重构混提？
- [ ] 提交信息是否**全英文**？subject 是否小写起首、无句号、≤ 72 字符？
- [ ] type 是否选对（`feat` / `fix` / `refactor` 边界是否清晰）？
- [ ] scope 是否标注、是否小写连字符、无空格？
- [ ] 是否存在「一个超大 commit 裹挟多种性质改动」的情况？能否再拆？

---

> ← 返回 [SKILL.md 索引](../SKILL.md)
