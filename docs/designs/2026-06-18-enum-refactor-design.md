# 硬编码字符串判断 → 枚举/常量统一化重构设计

- **日期**:2026-06-18
- **分支**:feature/v0.4.6
- **状态**:待评审
- **范围**:后端 Java + ai-bridge JS + webview TS 中"逻辑判断写死的字符串值"

## 1. 背景与动机

代码中存在大量以字符串字面量进行逻辑分派的写法(如 `case "claude-role-opus"`、`if ("ready".equals(x))`)。
最严重的是 `claude-role-{sonnet|opus|fable|haiku}` 这一组"角色模型 id":**同一个 `id → 短名/家族` 的映射逻辑
被手抄了 4 份**(Java 3 处 + JS 1 处),且 `claude-role-*`、短名 `sonnet/opus/fable/haiku`、对应的
`ANTHROPIC_DEFAULT_*_MODEL` 环境变量三组关联值散落各处。新增/修改一个 role 需要同步改 6+ 个文件,
极易漏改。

本重构目标:**把这类逻辑判断值统一收敛为枚举或集中常量**,使新增 role 只改一处,并让前端通过后端
下发的注册表(而非本地硬编码)获取映射。

## 2. 现状排查结论

按"值的性质"分四类:

### A. `claude-role-*` 系列(枚举化重点,重复最严重)

同一组值散落 **6 处 Java + 2 处 JS/TS**,其中 4 套是重复的 `id→短名/家族` 映射:

| 位置 | 作用 | 问题 |
|---|---|---|
| `ModelRegistryConfig.roleFromModelId()` | id→短名 switch | 第 1 套映射 |
| `ModelProviderHandler.getClaudeRoleFromModelId()` | id→短名 switch | **与第 1 套逐字重复** |
| `ClaudeCliModelResolver.detectFamily()` | id→`ModelFamily`(private 枚举) | 第 3 套 |
| `ai-bridge/utils/model-utils.js` `getClaudeRoleFromModelId()` | id→短名 if 链 | 第 4 套(JS) |
| `ClaudeCliSession.configureRequestModelEnvironment()` | role→env var switch | 行为分派 |
| `ModelRegistryConfig.buildDefault()` | 4 个 role 作 `ModelConfig` 注册 | 数据定义 |
| `CommonConstants.DEFAULT_MODEL` | `"claude-role-sonnet"` | 默认值 |

### B. 协议/消息类型 switch(部分常量化、部分裸字符串)

`ClaudeMessageHandler`、`CodexMessageHandler`、`ClaudeCliStreamParser`、`HistoryMessageInjector`、
`CodexCliSession` 中的 `result`/`usage`/`system`/`assistant`/`user`/`error`/`stream_event`/`message_start`
等。`CommonConstants.MSG_TYPE_*` 与 `CliConstants.MSG_*` 常量已存在但未全用上,且两套**命名不一、部分重叠**。

### C. 状态值 switch

`DaemonBridge`(ready/shutdown)、`ChatWindowDelegate`(queued/processing/answering/completed,
映射到已有 `TabAnswerStatus` 枚举)、`ClaudeStatusBarWidget`(thinking/generating/.../default/plan)、
`CodexSDKBridge`(plan)。

### D. 数据映射 switch(本质是映射表)

`PromptEnhancerHandler`(几十个文件扩展名→语言名)、`LanguageConfigService`(语言码映射)。

### 附带:常量重复

- `ENV_ANTHROPIC_*`:`CommonConstants.ENV_ANTHROPIC_OPUS_MODEL` 与 `CliConstants.ENV_ANTHROPIC_DEFAULT_OPUS_MODEL`
  **同值不同名**(前者命名误导,值实为 `ANTHROPIC_DEFAULT_OPUS_MODEL`)。
- 消息类型:`CommonConstants.MSG_TYPE_USER` 与 `CliConstants.MSG_USER` 同值不同名(user/assistant/thinking 重叠)。

### 已有可复用基础设施

前端通过 `model_registry` bridge 事件从后端拉取注册表,且注册表项已携带 `role` 字段
(`ModelRegistryConfig` → `ResolvedModelSelection.role` → 下发前端)。因此"前端查询映射"的管道已存在,
主要缺口在后端 4 套重复映射 + role 短名未枚举化。

## 3. 设计原则

按"值的性质"分流,不一刀切枚举:

| 性质 | 处理方式 | 类别 |
|---|---|---|
| 多值关联 + 行为分派(id↔短名↔家族↔env) | **富枚举**(内聚关联与分派) | A |
| 纯标签/单值,多处复用,无内在关联 | **字符串常量**(复用/补齐 CommonConstants、CliConstants) | B、多数 C |
| 有限集合 + 已有目标枚举 | 给枚举加 `fromValue()` 静态查找 | C 部分(Message.Type、TabAnswerStatus) |
| 纯映射表 | **静态 Map** | D |

## 4. 核心设计:`ClaudeRole` + `ModelFamily`

### 4.1 包位置与依赖

- 新增 `com.github.claudecodegui.common.ClaudeRole`(public 枚举)。
- 将 `ClaudeCliModelResolver` 内的 private `ModelFamily` **提升为** `com.github.claudecodegui.common.ModelFamily`
  (public 枚举,保留 `OTHER` 成员),由 `ClaudeRole` 引用。
- 包依赖方向:`common` 为最底层协议常量层,被 config/handler/session/cli 共同引用;`common` 不引用任何上层包
  (已核实 `CommonConstants.java` 不引 cli)。本次新增 `cli → common` 依赖,合理且无环。

### 4.2 `ClaudeRole` 枚举定义

```java
package com.github.claudecodegui.common;

import java.util.List;

public enum ClaudeRole {
    SONNET("claude-role-sonnet", "sonnet", ModelFamily.SONNET,
            List.of(CommonConstants.ENV_ANTHROPIC_DEFAULT_SONNET_MODEL)),
    OPUS  ("claude-role-opus",   "opus",   ModelFamily.OPUS,
            List.of(CommonConstants.ENV_ANTHROPIC_DEFAULT_OPUS_MODEL)),
    FABLE ("claude-role-fable",  "fable",  ModelFamily.FABLE,
            List.of(CommonConstants.ENV_ANTHROPIC_DEFAULT_FABLE_MODEL,
                    CommonConstants.ENV_ANTHROPIC_DEFAULT_OPUS_MODEL)),   // 有序:firstNonBlank 回退
    HAIKU ("claude-role-haiku",  "haiku",  ModelFamily.HAIKU,
            List.of(CommonConstants.ENV_ANTHROPIC_SMALL_FAST_MODEL,
                    CommonConstants.ENV_ANTHROPIC_DEFAULT_HAIKU_MODEL));

    private final String roleId;        // "claude-role-sonnet"
    private final String shortName;     // "sonnet"
    private final ModelFamily family;
    private final List<String> modelEnvKeys;  // 该 role 的 ANTHROPIC 模型 env key(有序回退)

    ClaudeRole(String roleId, String shortName, ModelFamily family, List<String> modelEnvKeys) {
        this.roleId = roleId;
        this.shortName = shortName;
        this.family = family;
        this.modelEnvKeys = modelEnvKeys;
    }

    public String roleId()   { return roleId; }
    public String shortName(){ return shortName; }
    public ModelFamily family() { return family; }
    public List<String> modelEnvKeys() { return modelEnvKeys; }

    /** id → ClaudeRole;剥 [1m] 后缀、trim、忽略大小写;非 role 模型返回 null */
    public static ClaudeRole fromModelId(String modelId);

    /** 短名 → ClaudeRole;非已知短名返回 null */
    public static ClaudeRole fromShortName(String shortName);

    /** 替代 ClaudeCliSession.configureRequestModelEnvironment 的 switch */
    public void applyModelEnv(java.util.Map<String, String> env, String resolvedModel) {
        modelEnvKeys.forEach(k -> env.put(k, resolvedModel));
    }
}
```

> **读取职责**:env → model 的读取(`resolveMapped` 的 firstNonBlank 链)**不**内聚到枚举,
> 因为其依赖 gson `JsonObject` 工具;若内聚会把 gson 依赖引入 `common` 层。改为由调用方
> (`ClaudeCliModelResolver`)遍历 `role.modelEnvKeys()` 并复用现有 `readEnvValue(JsonObject, String)`
> 实现 firstNonBlank。枚举只内聚"写入"(`applyModelEnv`)与数据(`roleId`/`shortName`/`family`/`modelEnvKeys`),
> 保持 `common` 层零第三方依赖。`ClaudeCliModelResolver.resolveMapped` 改造后形如:

```java
ClaudeRole role = ClaudeRole.fromModelId(normalized);
String mapped = null;
if (role != null) {
    for (String k : role.modelEnvKeys()) {
        mapped = readEnvValue(env, k);
        if (mapped != null) break;
    }
}
```

`fromModelId` 必须精确复刻现有归一化:`trim().replaceFirst("(?i)\\[1m\\]$", "").toLowerCase()`,再按 `roleId`
精确匹配。`[1m]` 处理与 `ClaudeCliModelResolver.ONE_M_SUFFIX` 保持一致。

### 4.3 capabilities(env `_CAPABILITIES` 后缀)

`readCapabilityOverride` 用的是另一组 `_CAPABILITIES` 后缀的 env key。观察可知:
`capsEnvKey = modelEnvKey + "_CAPABILITIES"`(如 `ANTHROPIC_DEFAULT_OPUS_MODEL` → `ANTHROPIC_DEFAULT_OPUS_MODEL_CAPABILITIES`),
规律一致。因此:

- `ClaudeRole` 不再单独存 caps key,而是提供 `capsEnvKeys()` 由 `modelEnvKeys` 派生(`k + "_CAPABILITIES"`),
  或在 `ClaudeCliModelResolver` 内本地派生。**推荐派生**,避免冗余字段。
- `OTHER` 分支(非 role 的 canonical claude 模型 → 读 SONNET caps)在 `ClaudeCliModelResolver` 内单独保留:
  `ClaudeRole role = fromModelId(id); role != null ? role.readCaps(env) : (isCanonicalClaude ? sonnetCaps : null)`。

### 4.4 收敛点

- **4 套重复映射 → 1 处** `ClaudeRole.fromModelId`:
  - `ModelRegistryConfig.roleFromModelId(id)` → `ClaudeRole.fromModelId(id)` 返回 `shortName()`。
  - `ModelProviderHandler.getClaudeRoleFromModelId(id)` → **删除**,直接调 `ClaudeRole.fromModelId(id).shortName()`。
  - `ClaudeCliModelResolver.detectFamily(id)` → `ClaudeRole role = fromModelId(id); return role != null ? role.family() : ModelFamily.OTHER;`。
  - `model-utils.js` 的 `getClaudeRoleFromModelId` → 见 §6。
- `ClaudeCliSession.configureRequestModelEnvironment` 的 switch:
  ```java
  ClaudeRole role = ClaudeRole.fromModelId(selectedModel);
  (role != null ? role : ClaudeRole.SONNET).applyModelEnv(cliEnv, resolvedModel);
  ```
  (非 role 模型保持当前 default→SONNET 行为。)
- `ClaudeCliModelResolver.resolveMapped` / `readCapabilityOverride` 的 `switch(detectFamily)` 改为
  基于 `ClaudeRole` + `ModelFamily`,`ModelFamily` 分派结构保留。

## 5. 改造清单

### A 类 — claude-role(枚举化)
1. 新增 `common.ClaudeRole`;提升 `common.ModelFamily`(public)。
2. `ModelRegistryConfig`:`roleFromModelId` 改调 `ClaudeRole`;`buildDefault` 的短名字面量改用
   `ClaudeRole.X.shortName()`(或保留字面量但加注释指向枚举——**推荐用枚举**,消除最后一份硬编码)。
3. `ModelProviderHandler`:删除 `getClaudeRoleFromModelId`,调用点改用 `ClaudeRole.fromModelId(...).shortName()`;
   `resolveConfiguredClaudeModel` 的 `"fable".equals(role)` 特殊分支改为基于 `ClaudeRole` 判断。
4. `ClaudeCliModelResolver`:`detectFamily`/`resolveMapped`/`readCapabilityOverride` 委托 `ClaudeRole`。
5. `ClaudeCliSession.configureRequestModelEnvironment`:switch → `applyModelEnv`。
6. `CommonConstants.DEFAULT_MODEL` 保持(它就是合法的 role id,可加注释指向 `ClaudeRole.SONNET.roleId()`)。

### B 类 — 消息/协议类型(常量化 + 枚举 fromValue)
- `ClaudeMessageHandler`、`CodexMessageHandler`、`ClaudeCliStreamParser`、`CodexCliSession`:裸字符串
  → 引用常量。
- 缺失常量补齐(建议补到 `CommonConstants`,作为 SDK 消息类型):`message_start`、`message_end`、
  `stream_event`(若属 CLI stream-json 则留 CliConstants)、`node_log`、`slash_commands`。
- `HistoryMessageInjector`:给 `ClaudeSession.Message.Type` 加 `fromValue(String)`,删除本地 switch。
- 消息常量归一见 §7。

### C 类 — 状态值
- `ChatWindowDelegate`:给 `TabAnswerStatus` 加 `fromValue(String)`,switch 删除。
- `DaemonBridge`(`ready`/`shutdown`)、`ClaudeStatusBarWidget`(`thinking`/`generating`/`waiting`/`success`/
  `error`/`default`/`plan`)、`CodexSDKBridge`(`plan`,复用 `PERMISSION_MODE_PLAN`)→ 常量化(补到合适常量类)。

### D 类 — 语言映射(改 Map)
- `PromptEnhancerHandler`:扩展名 case → `private static final Map<String,String> EXT_LANGUAGE =
  Map.ofEntries(...)`(不可变);查询 `EXT_LANGUAGE.getOrDefault(ext, null)`。
- `LanguageConfigService`:语言码 case → 同样改 `Map`。

## 6. JS / 前端侧

- `ai-bridge/utils/model-utils.js`:`getClaudeRoleFromModelId` 收敛为单一常量模块:
  ```js
  // 与 Java com.github.claudecodegui.common.ClaudeRole 对齐;修改时两处同步
  const CLAUDE_ROLES = Object.freeze({
    'claude-role-sonnet': 'sonnet',
    'claude-role-opus':   'opus',
    'claude-role-fable':  'fable',
    'claude-role-haiku':  'haiku',
  });
  ```
  其余逻辑(`resolveModelFromSettings`、`setModelEnvironmentVariables`、`mapModelIdToSdkName`)保持行为不变,
  仅改为读该常量表。`[1m]` 剥离逻辑保持。
- webview 前端:`role` 主路径来自 `model_registry` bridge 下发(已实现);`parseClaudeRole`/`CLAUDE_MODELS`
  中残留的 role 字面量收敛为单一常量并注释对齐 Java。
- **两份真相(Java 枚举 / JS 常量)靠注释 + `model-utils.test.mjs` 锁定。** 跨语言代码生成不在本次范围。

## 7. 常量合并(激进)

权威归位与依赖方向(已核实分层):

| 常量族 | 权威位置 | 处置 |
|---|---|---|
| `ENV_ANTHROPIC_*`(含 `DEFAULT_*_MODEL`、`SMALL_FAST_MODEL`、`*_MODEL_CAPABILITIES`、`ANTHROPIC_MODEL`) | **CommonConstants**(修正命名为 `ENV_ANTHROPIC_DEFAULT_*`,补全全集) | `CliConstants` 删除这些重复定义;cli 包引用方改引 CommonConstants |
| 通用消息类型(user/assistant/tool_use/tool_result/thinking/text/image) | **CommonConstants.MSG_TYPE_***(已有) | `CliConstants` 里重复的 `MSG_USER`/`MSG_ASSISTANT`/`MSG_THINKING` 删除,cli 改引 common |
| CLI 流式专用(result/usage/stream_*/session_id/message_*/content_delta/block_reset/thinking_delta) | **CliConstants.MSG_**(原位保留) | 不动;补齐 handler 缺失引用 |
| `ClaudeRole` / `ModelFamily` | **common 包** | 新增 |

受影响引用方(纯搬家/重命名,行为等价):
- `CliConstants.ENV_ANTHROPIC_*`:cli 包内 ~30 处(`ClaudeCliSession` 7、`ClaudeCliModelResolver` 12、`CliSettings`、`ClaudeCliSessionTest`)。
- `CommonConstants.ENV_ANTHROPIC_OPUS_MODEL` 等(旧名):`ModelProviderHandler` 6 处。
- `CliConstants.MSG_USER/ASSISTANT/THINKING`:cli 包引用方。

## 8. 测试策略

**新增**
- `ClaudeRoleTest`:`fromModelId` 覆盖 `[1m]` 后缀、大小写、trim、非 role、null、空串;`fromShortName`;
  `shortName`/`family`/`modelEnvKeys`;`applyModelEnv`(各 role 写入正确的 env key);`readMapped`(回退顺序)。
- `ClaudeSession.Message.Type.fromValue`、`TabAnswerStatus.fromValue` 单测。
- D 类 Map 化后等价用例(`PromptEnhancerHandler` 扩展名映射、`LanguageConfigService`)。

**回归(必须全绿)**
- `ClaudeCliSessionTest`(env 映射不变)。
- `ClaudeCliModelResolverTest`。
- `ModelProviderHandlerTest`。
- `CodemossSettingsServiceModelRegistryTest` / `CodemossSettingsServiceCommitAiConfigTest` /
  `CodemossSettingsServicePromptEnhancerConfigTest`。
- `ModelRegistrySection`、`ModelSelect`、`modelRegistry` 前端测试。
- JS:`model-utils.test.mjs`(`getClaudeRoleFromModelId` / `resolveModelFromSettings` 行为不变)。

**门禁**:权威测试集(176 `.test.js` + 50 `.test.mjs`)全绿 + Java 全量编译。

## 9. 风险与对策

1. **激进合改动面大**(~30+ ENV 引用、若干消息常量)→ 全程纯搬家/重命名,行为等价,靠编译 + 现有测试锁定;
   分小步提交(先 ENV 合并、再消息常量、再枚举、再 D 类),每步独立可回退。
2. **`fromModelId` 归一化偏差** → 用 `ClaudeCliModelResolverTest` 现有用例 + 新增边界用例锁定。
3. **Java/JS 两份枚举真相漂移** → 注释双向标注 + `model-utils.test.mjs` 锁定;未来可考虑代码生成(范围外)。
4. **`cli → common` 新依赖** → 已核实 common 不引 cli,无循环依赖。
5. **`buildDefault` 改用枚举短名** → 确保 `ModelConfig` 的 `role` 字段值与 `ClaudeRole.X.shortName()` 完全一致
   (现有测试 `CodemossSettingsServiceModelRegistryTest` 锁定)。

## 10. 范围外(YAGNI)

- 跨语言代码生成(从 Java 枚举自动产出 JS 常量)。
- `CommonConstants` 与 `CliConstants` 的整体合并(仅合并本次涉及的 ENV 与消息类型)。
- 协议层 JSON schema 化。
- 现有"两套消息常量"以外的其他常量去重扫描。

## 11. 验收标准

1. 后端 Java 中不再有 `claude-role-*` 字面量出现在 `switch`/`if` 逻辑判断中(数据定义处除外,且引用枚举)。
2. `id→短名/家族` 映射逻辑仅存在一处(`ClaudeRole.fromModelId`);Java 与 JS 各一处且互相注释对齐。
3. `ENV_ANTHROPIC_*` 与通用消息类型常量各自只有一份权威定义。
4. D 类语言映射改为 `Map`,无 `switch case` 字面量。
5. 权威测试集全绿,Java 编译通过,无新增编译警告。
6. 新增/修改一个 role(如未来加 `claude-role-mythos`)只需改 `ClaudeRole` 枚举一处 + JS 常量一处。
