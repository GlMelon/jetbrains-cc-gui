# Architecture Compliance Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把现有前后端代码逐步迁移到符合 `AGENTS.md` 架构准则的状态——前端只渲染、业务下沉后端、payload 契约单一真相源(SSOT)、后端开闭原则派发。

**Architecture:** 分 5 个阶段。Phase 1(本计划详细展开)聚焦**模型注册表 payload 契约的默认值对齐与两端字段守门**——最小风险、立即可执行,落地 AGENTS.md 总则三(SSOT)。Phase 2-5 为已分解蓝图(派发器统一、前端业务下沉、对接 Docking 化),各自后续展开为独立可执行计划。

**Tech Stack:** 后端 Java 17 + IntelliJ Platform + Gson + JUnit 5;前端 React 19 + TypeScript + Vite + vitest;契约链路 Java 枚举 → `ProtocolManifestGenerator` → `generate-protocol-types.mjs` → `protocol.ts`。

**依据:** `AGENTS.md` 总则一(职责分离)、总则三(SSOT)、第 6 节合规检查清单第 7、8 条。

---

## Phase 0 · 范围、原则与全局约束

### 迁移对象(本次排查确认的违规点)

| # | 违规点 | 前端位置 | 后端权威位置 | 归属总则 |
|---|---|---|---|---|
| V1 | 模型注册表双真相源 | `webview/src/components/ChatInputBox/types.ts:416-451` `CLAUDE_MODELS`/`CODEX_MODELS`;`webview/src/utils/modelRegistry.ts:22-34` `DEFAULT_MODEL_REGISTRY` | `config/ReadOnlyDefaultModels.java:30-47` `compute()` | 一/四 |
| V2 | contextWindow 默认值双写不一致 | `modelRegistry.ts:237`(`<=0` 跳过/丢弃) | `settings/ModelRegistryService.java:110-112`(默认 `200_000`) | 三 |
| V3 | payload 解析器双写 | `modelRegistry.ts:222-262` `parseModelRegistryPayload` | `ModelRegistryService.java:96-123` `parse`/`serialize` | 三 |
| V4 | 1M context 能力判定在前端 | `types.ts:275-306` `modelSupports1MContext` | `config/ModelConfig.supports1MContext` 字段(`ReadOnlyDefaultModels.java:99`) | 一 |
| V5 | 模型 role 归一化在前端 | `types.ts:383-410`;`modelRegistry.ts:102-138` | `common/ClaudeRole.java` | 一 |
| V6 | 版本决策前后端双写 | `webview/src/components/settings/DependencySection/versioning.ts:15-71` | `dependency/DependencyManager.java:281` | 一/四 |
| V7 | 工具分类纯前端硬编码 | `webview/src/utils/toolConstants.ts` | 后端无对应 | 一 |
| V8 | 枚举值前端手写 | `types.ts:181,186,487,502-533`(`PermissionMode`/`CodexFastMode`/`ReasoningEffort`/`REASONING_LEVELS`/`AVAILABLE_PROVIDERS`) | 散落各 handler | 三 |
| V9 | 旧派发违反 OCP | `handler/core/MessageDispatcher.java` + `handler/SettingsHandler.java:35-100` `SUPPORTED_TYPES` 字符串数组 | 新 `handler/core/FrontendActionDispatcher.java`(符合 OCP) | 二 |

### 阶段划分与优先级(依 AGENTS.md 总则优先级)

- **Phase 1(本计划展开)**:V2 + V3 的守门部分——payload 默认值对齐 + 两端字段契约守门。建立 SSOT 基础设施。
- **Phase 2(蓝图)**:V3 自动化(V3 守门的自动生成机制)+ V9(派发器统一)。
- **Phase 3(蓝图)**:V1 + V4 + V5(前端模型业务下沉)。
- **Phase 4(蓝图)**:V6 + V7 + V8(其他前端业务下沉 + 枚举 SSOT)。
- **Phase 5(蓝图)**:第三方对接 Docking 化(Adapter + `support()` 路由)。

### 全局验收标准

1. `./gradlew test` 全绿(后端)。
2. `cd webview && npm test` 全绿(前端 vitest + tsc)。
3. 每个阶段产出可独立部署、可回滚的提交,不破坏既有功能。

### 全局风险与回滚

- **风险**:前端业务逻辑被多处调用(role 归一化、1M 判定、`[1m]` 后缀),下沉改动面大。故 Phase 3/4 需逐函数迁移,每步有测试覆盖。
- **回滚**:每 Task 单独 commit,任意阶段失败可 `git revert` 该 commit 回滚,不影响其他阶段。

---

## Phase 1 · 模型注册表 payload 默认值对齐与字段守门(可立即执行)

### Task 1.1: 前端 parseModelRegistryPayload 的 contextWindow 默认值与后端对齐

**Files:**
- Modify: `webview/src/utils/modelRegistry.ts:234-239`(函数 `parseModelRegistryPayload`)
- Test: `webview/src/utils/modelRegistry.test.ts`(新增用例)

- [ ] **Step 1: 写失败测试**

在 `webview/src/utils/modelRegistry.test.ts` 的 `describe('modelRegistry', ...)` 内、`it('rejects empty or malformed payloads', ...)` 之前,新增:

```ts
  it('defaults contextWindow to 200000 when absent, aligning with backend', () => {
    const parsed = parseModelRegistryPayload({
      items: [
        { id: 'mimo', provider: 'claude', label: 'Mimo' },
      ],
    });
    expect(parsed?.items[0].contextWindow).toBe(200_000);
  });

  it('defaults contextWindow to 200000 when non-positive, aligning with backend', () => {
    const parsed = parseModelRegistryPayload({
      items: [
        { id: 'mimo', provider: 'claude', label: 'Mimo', contextWindow: 0 },
      ],
    });
    expect(parsed?.items[0].contextWindow).toBe(200_000);
  });
```

- [ ] **Step 2: 跑测试,确认失败**

Run: `cd webview && npx vitest run src/utils/modelRegistry.test.ts`
Expected: 两个新用例 FAIL。原因:当前 `parseModelRegistryPayload` 对无 `contextWindow` 的 item 执行 `continue`(丢弃),`parsed` 为 `null`,断言 `parsed?.items[0].contextWindow` 为 `undefined` ≠ `200_000`。

- [ ] **Step 3: 修改实现,使默认值与后端对齐**

修改 `webview/src/utils/modelRegistry.ts` 中 `parseModelRegistryPayload` 的解析段。将:

```ts
      const id = typeof obj.id === 'string' ? obj.id.trim() : '';
      const provider = obj.provider === 'codex' ? 'codex' : obj.provider === 'claude' ? 'claude' : null;
      const contextWindow = typeof obj.contextWindow === 'number' ? obj.contextWindow : undefined;
      if (!id || !provider || !contextWindow || contextWindow <= 0) {
        continue;
      }
```

替换为(缺失或非正 → 默认 `200_000`,与 `ModelRegistryService.parse` 对齐;不再因缺 `contextWindow` 丢弃 item):

```ts
      const id = typeof obj.id === 'string' ? obj.id.trim() : '';
      const provider = obj.provider === 'codex' ? 'codex' : obj.provider === 'claude' ? 'claude' : null;
      const rawContextWindow = typeof obj.contextWindow === 'number' ? obj.contextWindow : undefined;
      if (!id || !provider) {
        continue;
      }
      const contextWindow = rawContextWindow !== undefined && rawContextWindow > 0
        ? rawContextWindow
        : 200_000;
```

- [ ] **Step 4: 跑测试,确认全绿**

Run: `cd webview && npx vitest run src/utils/modelRegistry.test.ts`
Expected: PASS(全部用例,含 2 个新用例)。注意:既有用例 `it('rejects empty or malformed payloads')` 中 `{ id: '', provider: 'claude' }` 仍返回 `null`(因 `id` 为空被 `continue`,items 为空),不受影响。

- [ ] **Step 5: 提交**

```bash
git add webview/src/utils/modelRegistry.ts webview/src/utils/modelRegistry.test.ts
git commit -m "fix: align frontend model registry contextWindow default with backend (200000)"
```

---

### Task 1.2: 后端 serialize payload 字段守门测试

**Files:**
- Create: `src/test/java/com/github/claudecodegui/settings/ModelRegistryServiceSerializeTest.java`

**目的:** 固定 `ModelRegistryService.serialize` 输出的 payload 字段集 == `ModelConfig` record 字段(反射)。这是模型注册表 payload 的**后端权威 schema**,前端必须对齐。守门既有契约——测试应立即通过;若失败说明 serialize 已漂移。

- [ ] **Step 1: 创建测试文件**

文件 `src/test/java/com/github/claudecodegui/settings/ModelRegistryServiceSerializeTest.java`:

```java
package com.github.claudecodegui.settings;

import com.github.claudecodegui.config.ModelConfig;
import com.github.claudecodegui.config.ModelRegistryConfig;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 守门测试:ModelRegistryService.serialize 输出的 payload 字段集必须与
 * ModelConfig record 的字段逐一对齐(AGENTS.md 总则三·payload SSOT)。
 *
 * <p>后端 serialize 是模型注册表 payload 的权威字段来源;前端 ModelRegistryItem
 * 必须覆盖同一字段集。本测试固定后端 schema,防止字段漂移;
 * 前端对齐守门见 modelRegistry.test.ts 的 "parsed item covers all backend fields"。
 */
class ModelRegistryServiceSerializeTest {

    @Test
    void serializeEmitsExactlyTheModelConfigRecordFields() {
        ModelConfig sample = new ModelConfig(
                "claude-role-sonnet", "claude", "sonnet", "Sonnet", "glm5.2",
                "desc", 200_000, true, true, false);
        JsonObject payload = ModelRegistryService.serialize(new ModelRegistryConfig(List.of(sample)));

        JsonObject item = payload.getAsJsonArray("items").get(0).getAsJsonObject();

        Set<String> recordFields = new LinkedHashSet<>();
        for (RecordComponent rc : ModelConfig.class.getRecordComponents()) {
            recordFields.add(rc.getName());
        }
        assertEquals(recordFields, item.keySet(),
                "serialize payload item fields must match ModelConfig record components exactly "
                        + "(AGENTS.md §3 payload SSOT)");
    }
}
```

- [ ] **Step 2: 跑测试,确认通过**

Run: `./gradlew test --tests "com.github.claudecodegui.settings.ModelRegistryServiceSerializeTest"`
Expected: PASS。`ModelConfig` record 的 10 个字段(`id, provider, role, label, actualModel, description, contextWindow, supports1MContext, enabled, readOnly`)与 `serialize` 输出的键完全一致。

- [ ] **Step 3: 提交**

```bash
git add src/test/java/com/github/claudecodegui/settings/ModelRegistryServiceSerializeTest.java
git commit -m "test: guard ModelRegistryService payload fields against ModelConfig drift"
```

---

### Task 1.3: 前端 ModelRegistryItem 字段覆盖守门测试

**Files:**
- Modify: `webview/src/utils/modelRegistry.test.ts`(新增守门用例)

**目的:** 断言前端解析出的 item 覆盖后端 `ModelConfig` 的全部字段。与 Task 1.2 的后端反射守门配合——后端固定 serialize 字段、前端固定解析覆盖,任一端字段漂移都会被捕获。

- [ ] **Step 1: 新增守门用例**

在 `webview/src/utils/modelRegistry.test.ts` 的 `describe('modelRegistry', ...)` 内追加:

```ts
  it('parsed item covers all backend ModelConfig fields (payload SSOT guard)', () => {
    // 与后端 com.github.claudecodegui.config.ModelConfig record 字段逐一对齐。
    // 后端守门:ModelRegistryServiceSerializeTest.serializeEmitsExactlyTheModelConfigRecordFields
    const BACKEND_MODEL_CONFIG_FIELDS = [
      'id', 'provider', 'role', 'label', 'actualModel',
      'description', 'contextWindow', 'supports1MContext', 'enabled', 'readOnly',
    ] as const;

    const parsed = parseModelRegistryPayload({
      items: [
        {
          id: 'mimo-v2.5', provider: 'claude', role: 'sonnet', label: 'MiMo',
          actualModel: 'mimo-v2.5', description: 'desc', contextWindow: 1_000_000,
          supports1MContext: true, enabled: true, readOnly: false,
        },
      ],
    });

    const parsedKeys = Object.keys(parsed!.items[0]);
    for (const field of BACKEND_MODEL_CONFIG_FIELDS) {
      expect(parsedKeys, `parsed item missing backend field: ${field}`).toContain(field);
    }
  });
```

- [ ] **Step 2: 跑测试,确认通过**

Run: `cd webview && npx vitest run src/utils/modelRegistry.test.ts`
Expected: PASS。`parseModelRegistryPayload` 输出的 item 含全部 10 个后端字段键。

- [ ] **Step 3: 提交**

```bash
git add webview/src/utils/modelRegistry.test.ts
git commit -m "test: guard frontend ModelRegistryItem covers backend ModelConfig fields"
```

---

### Phase 1 验收

- [ ] `cd webview && npm test` 全绿(vitest + tsc 类型检查)。
- [ ] `./gradlew test` 全绿。
- [ ] 3 个 commit 已提交,各自可独立 revert。
- [ ] 至此 payload 默认值两端一致(V2 修复),字段集两端守门(V3 守门部分建立)。

---

## Phase 2-5 · 蓝图(后续各自展开为独立可执行计划)

> 以下阶段风险/改动面高于 Phase 1,需在各自执行前先用 writing-plans 展开为 bite-sized 任务。每阶段遵循同一约束:先建守门测试 → 改实现 → 全绿 → 提交。

### Phase 2 · payload schema 自动化 + 派发器统一(V3 自动生成 + V9)

**目标:**
- V3 自动化:扩展 `ProtocolManifestGenerator`(反射 `ModelConfig` record components 写入 manifest 的 `payloadSchemas`)+ `generate-protocol-types.mjs`(从 manifest 生成 TS 字段常量),取代 Task 1.3 的手写字段清单,实现真正的 SSOT。需评估 mjs 当前"优先从 Java 源 regex 生成"与"读 manifest"的优先级调整,避免破坏 `prebuild --stub` 流程。
- V9 派发器统一:把 `SettingsHandler` 仍走 `MessageDispatcher` 字符串数组 / `SUPPORTED_TYPES` 的 action,逐个迁移为实现 `FrontendActionHandler<T>`。每迁移一类 action:先写该 handler 的单测 → 迁移 → 从 `SUPPORTED_TYPES` 移除该条 → 验证 `FrontendActionDispatcher` 重复检测通过。

**关键文件:** `protocol/ProtocolManifestGenerator.java`、`webview/scripts/generate-protocol-types.mjs`、`handler/core/FrontendActionHandler.java`、`handler/core/FrontendActionDispatcher.java`、`handler/SettingsHandler.java`、`handler/core/MessageDispatcher.java`。

**验收:** manifest 经反射生成的字段常量 == Task 1.2 后端反射字段;`MessageDispatcher` 仅剩无法迁移的残留(目标:为空或标注废弃);全绿。

**回滚:** 每 action / 每生成步骤单独 commit。

### Phase 3 · 前端模型业务下沉(V1 + V4 + V5)

**目标:** 消除前端模型双真相源与前端业务计算。
- V1:删除 `DEFAULT_MODEL_REGISTRY`/`CLAUDE_MODELS` 作为业务真相的用法;后端 `MODEL_REGISTRY` 事件到达前前端显示 loading(而非 hardcode fallback)。`CLAUDE_MODELS` 若仅作纯展示常量需明确标注,否则改为从 registry 派生。
- V4:删除前端 `modelSupports1MContext` 决策树;后端 `ModelConfig.supports1MContext` 已是权威,前端只读字段。
- V5:删除前端 `getClaudeRoleFromModelId`/`normalizeClaudeModelId`/`resolveClaudeRoleForModel` 的业务归一化;后端下发 `role` 字段,前端只透传。

**关键文件:** `webview/src/components/ChatInputBox/types.ts`、`webview/src/utils/modelRegistry.ts`、`webview/src/components/ChatInputBox/ModelSelect.tsx`、`webview/src/utils/claudeModelMapping.ts`、后端 `common/ClaudeRole.java`、`config/ReadOnlyDefaultModels.java`。

**验收:** webview 不再 import 业务计算函数;模型能力/role/1M 判定均来自后端下发字段;既有 `modelRegistry.test.ts`/`useModelStatePersistence.test.ts` 调整后全绿。

**风险:** 调用点广,需逐函数迁移并更新调用方测试。

### Phase 4 · 其他前端业务下沉 + 枚举 SSOT(V6 + V7 + V8)

**目标:**
- V6:删除前端 `versioning.ts` 的 `getVersionAction` 决策;后端 `DependencyManager` 下发动作结果,前端只渲染按钮态。
- V7:`toolConstants.ts` 工具分类若为显示所需,纳入契约层(后端工具元数据经 SSOT 下发),不纯前端 hardcode。
- V8:`PermissionMode`/`ReasoningEffort`/`CodexFastMode`/`REASONING_LEVELS`/`AVAILABLE_PROVIDERS` 等枚举,经 Phase 2 的枚举 SSOT 机制生成到前端,不手写联合类型字面量。

**关键文件:** `webview/src/components/settings/DependencySection/versioning.ts`、`webview/src/utils/toolConstants.ts`、`webview/src/components/ChatInputBox/types.ts`、后端 `dependency/DependencyManager.java` 及枚举定义。

**验收:** 前端无业务决策函数、无 hardcode 业务枚举;合规检查清单第 1、2、3、10 条全通过。

### Phase 5 · 第三方对接 Docking 化

**目标:** 任何对接外部系统/CLI/第三方能力的代码,从 `if/else` 硬编码分支迁移为 Adapter 接口 + `support(type)` 路由 + 配置外置(AGENTS.md 总则五 + 附录 A「Docking 三层通用化」)。

**前置:** 需先排查当前是否存在外部对接硬编码分支(本次排查未深入 `ai-bridge` 与 CLI 对接细节)。执行前先做一次针对性排查,识别硬编码分支点,再展开任务。

**验收:** 新增第三方对接零代码改动既有分派;合规检查清单第 11、12 条通过。

---

## 自审(writing-plans skill 要求)

1. **Spec 覆盖**:AGENTS.md 总则三(SSOT)→ Phase 1 Task 1.1/1.2/1.3 + Phase 2。总则一(职责分离)→ Phase 3/4。总则二(开闭)→ Phase 2(V9)。总则五(拓展点)→ Phase 5。总则四(复用)→ 各阶段消除双写。覆盖完整。
2. **占位符扫描**:Phase 1 三任务均有真实可运行代码与精确命令;Phase 2-5 明确标注为"蓝图,执行前需用 writing-plans 展开",非占位符而是有意的阶段边界。
3. **类型一致性**:`ModelConfig` 10 字段在 Task 1.2(反射)、Task 1.3(手写清单)一致;`parseModelRegistryPayload` 修改保留既有返回结构 `ModelRegistryPayload`。
