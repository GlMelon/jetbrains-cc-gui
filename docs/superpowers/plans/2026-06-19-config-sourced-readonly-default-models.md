# 配置文件驱动的只读默认模型 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Claude 4 role + Codex 默认模型全部由后端从配置文件(`~/.claude/settings.json` / `~/.codex/config.toml`)读取并以只读默认叠加进 registry,前端只显示;切换 Claude provider 后自动重读 settings.json 并推送刷新;新增自定义模型时与只读默认键冲突即拒绝。彻底修复 Codex 切换时空数组导致 `ModelSelect` 崩溃。

**Architecture:** 后端新增 `ReadOnlyDefaultModels`(复用 `CliSettings` 读配置),`getModelRegistry()` 返回"用户层 + 只读默认"的合并结果;`setModelRegistry()` 剥离只读项 → 冲突校验(仅拦截新增)→ 校验 effective → 持久化用户层;`ClaudeProviderOperations` 三处切换路径写完 settings.json 后推送 `model_registry`。前端 `ModelSelect` 加空模型守卫,`ModelRegistrySection` 只读行不可编辑、提交前剥离只读项。

**Tech Stack:** Java 17 records + JUnit 4 + Gradle;React + TypeScript + Vitest + @testing-library/react。后端权威,前端只显示。

**设计依据:** `docs/designs/2026-06-19-config-sourced-readonly-default-models-design.md`(Option C)。

---

## File Structure

**后端(Java)**
- 修改 `src/main/java/com/github/claudecodegui/config/ModelConfig.java` — 加 `readOnly` 字段(10 参规范构造器 + 9 参便利构造器),`normalized()` 透传。
- 新建 `src/main/java/com/github/claudecodegui/config/ReadOnlyDefaultModels.java` — 只读默认计算 + 合并 + 去重键。
- 修改 `src/main/java/com/github/claudecodegui/settings/CodemossSettingsService.java` — `getModelRegistry`/`setModelRegistry` 重写 + 新增 `readPersistedUserLayer`/`stripReadOnly`/`checkNoNewConflictsWithReadOnly`/`getModelRegistryJson` + 序列化加 `readOnly`。
- 修改 `src/main/java/com/github/claudecodegui/handler/SettingsHandler.java` — `serializeModelRegistry` 加 `readOnly`。
- 修改 `src/main/java/com/github/claudecodegui/handler/provider/ClaudeProviderOperations.java` — 三处切换路径推送 `model_registry`。
- 新建 `src/test/java/com/github/claudecodegui/config/ModelConfigTest.java`
- 新建 `src/test/java/com/github/claudecodegui/config/ReadOnlyDefaultModelsTest.java`
- 修改 `src/test/java/com/github/claudecodegui/settings/CodemossSettingsServiceModelRegistryTest.java` — 改写 `persistsValidCustomModelRegistry` + 新增冲突/空用户层用例。

**前端(Webview)**
- 修改 `webview/src/utils/modelRegistry.ts` — `ModelRegistryItem.readOnly` + 解析。
- 修改 `webview/src/components/ChatInputBox/selectors/ModelSelect.tsx` — 空模型守卫。
- 修改 `webview/src/components/ChatInputBox/selectors/ModelSelect.test.tsx` — 空模型回归测试。
- 修改 `webview/src/components/settings/ModelRegistrySection/index.tsx` — 只读渲染 + 剥离只读提交。
- 新建 `webview/src/utils/modelRegistry.test.ts` — readOnly 解析测试。
- 新建 `webview/src/components/settings/ModelRegistrySection/index.test.tsx` — 只读行 + 冲突测试。

---

### Task 1: `ModelConfig` 增加 `readOnly` 字段

**Files:**
- Modify: `src/main/java/com/github/claudecodegui/config/ModelConfig.java`
- Test: `src/test/java/com/github/claudecodegui/config/ModelConfigTest.java`

- [ ] **Step 1: 写失败测试**

Create `src/test/java/com/github/claudecodegui/config/ModelConfigTest.java`:
```java
package com.github.claudecodegui.config;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModelConfigTest {
    @Test
    public void nineArgConstructorDefaultsReadOnlyFalse() {
        ModelConfig model = new ModelConfig("mimo", "claude", "sonnet", "Mimo",
                "mimo", "", 200_000, true, true);
        assertFalse(model.readOnly());
    }

    @Test
    public void tenArgConstructorPreservesReadOnly() {
        ModelConfig model = new ModelConfig("mimo", "claude", "sonnet", "Mimo",
                "mimo", "", 200_000, true, true, true);
        assertTrue(model.readOnly());
    }

    @Test
    public void normalizedPreservesReadOnlyFlag() {
        ModelConfig model = new ModelConfig("mimo", "claude", "sonnet", "Mimo",
                "mimo", "", 200_000, true, true, true);
        assertTrue(model.normalized().readOnly());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests "com.github.claudecodegui.config.ModelConfigTest"`
Expected: 编译失败 — `ModelConfig` 无 `readOnly()` / 无 10 参构造器。

- [ ] **Step 3: 实现 `readOnly` 字段**

Replace the entire `src/main/java/com/github/claudecodegui/config/ModelConfig.java` content with:
```java
package com.github.claudecodegui.config;

/**
 * Single model entry in the configurable model registry.
 *
 * <p>{@code readOnly=true} 表示该项来自 CLI 配置文件(settings.json / config.toml),
 * 由后端运行时计算,不可被用户层编辑/删除/停用,也不进持久化。
 */
public record ModelConfig(
        String id,
        String provider,
        String role,
        String label,
        String actualModel,
        String description,
        int contextWindow,
        boolean supports1MContext,
        boolean enabled,
        boolean readOnly
) {
    /** 9 参便利构造器:委托规范构造器,readOnly 默认 false(后端权威:解析/持久化路径用此)。 */
    public ModelConfig(String id, String provider, String role, String label, String actualModel,
                       String description, int contextWindow, boolean supports1MContext, boolean enabled) {
        this(id, provider, role, label, actualModel, description,
                contextWindow, supports1MContext, enabled, false);
    }

    public ModelConfig normalized() {
        String normalizedId = id == null ? "" : id.trim();
        String normalizedProvider = provider == null ? "" : provider.trim().toLowerCase();
        String normalizedRole = role == null ? "" : role.trim().toLowerCase();
        String normalizedLabel = label == null || label.trim().isEmpty() ? normalizedId : label.trim();
        String normalizedActualModel = actualModel == null || actualModel.trim().isEmpty()
                ? ""
                : actualModel.trim();
        String normalizedDescription = description == null || description.trim().isEmpty() ? "" : description.trim();
        return new ModelConfig(
                normalizedId,
                normalizedProvider,
                normalizedRole,
                normalizedLabel,
                normalizedActualModel,
                normalizedDescription,
                contextWindow,
                supports1MContext,
                enabled,
                readOnly
        );
    }
}
```

- [ ] **Step 4: 运行测试确认通过 + 全量编译**

Run: `./gradlew test --tests "com.github.claudecodegui.config.ModelConfigTest" compileJava compileTestJava`
Expected: 3 个测试 PASS;全量编译通过(现有 9 参调用因便利构造器而兼容)。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/github/claudecodegui/config/ModelConfig.java src/test/java/com/github/claudecodegui/config/ModelConfigTest.java
git commit -m "feat(model-config): add readOnly field with 9-arg convenience constructor"
```

---

### Task 2: 新建 `ReadOnlyDefaultModels`(只读默认计算 + 合并)

**Files:**
- Create: `src/main/java/com/github/claudecodegui/config/ReadOnlyDefaultModels.java`
- Test: `src/test/java/com/github/claudecodegui/config/ReadOnlyDefaultModelsTest.java`

- [ ] **Step 1: 写失败测试**

Create `src/test/java/com/github/claudecodegui/config/ReadOnlyDefaultModelsTest.java`:
```java
package com.github.claudecodegui.config;

import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.common.ClaudeRole;
import com.github.claudecodegui.common.CommonConstants;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReadOnlyDefaultModelsTest {
    @Test
    public void computeReturnsFourReadOnlyRolesWithoutCodexWhenNoConfig() {
        List<ModelConfig> defaults = ReadOnlyDefaultModels.compute(Map.of(), Map.of());

        assertEquals(4, defaults.size());
        for (ModelConfig model : defaults) {
            assertEquals(CommonConstants.PROVIDER_CLAUDE, model.provider());
            assertTrue(model.readOnly());
            assertTrue(model.enabled());
            assertEquals("", model.actualModel()); // 无 settings.json 配置
        }
        assertTrue(defaults.stream().anyMatch(m -> m.id().equals(ClaudeRole.SONNET.roleId())));
    }

    @Test
    public void computeResolvesClaudeActualModelFromEnv() {
        Map<String, String> claudeEnv = Map.of(
                CommonConstants.ENV_ANTHROPIC_DEFAULT_SONNET_MODEL, "mimo-v2.5");
        List<ModelConfig> defaults = ReadOnlyDefaultModels.compute(claudeEnv, Map.of());

        ModelConfig sonnet = defaults.stream()
                .filter(m -> m.id().equals(ClaudeRole.SONNET.roleId())).findFirst().orElseThrow();
        assertEquals("mimo-v2.5", sonnet.actualModel());
    }

    @Test
    public void computeIncludesCodexReadOnlyWhenModelPresent() {
        Map<String, String> codexEnv = Map.of(CliConstants.ENV_CODEX_MODEL, "gpt-5");
        List<ModelConfig> defaults = ReadOnlyDefaultModels.compute(Map.of(), codexEnv);

        ModelConfig codex = defaults.stream()
                .filter(m -> CommonConstants.PROVIDER_CODEX.equals(m.provider())).findFirst().orElseThrow();
        assertEquals("gpt-5", codex.id());
        assertTrue(codex.readOnly());
        assertEquals(5, defaults.size()); // 4 roles + 1 codex
    }

    @Test
    public void mergeReservesRoleKeysReadOnlyAlwaysWins() {
        ModelConfig readOnlySonnet = new ModelConfig(ClaudeRole.SONNET.roleId(),
                CommonConstants.PROVIDER_CLAUDE, "sonnet", "Sonnet", "", "",
                200_000, true, true, true);
        ModelConfig userSonnetOverride = new ModelConfig(ClaudeRole.SONNET.roleId(),
                CommonConstants.PROVIDER_CLAUDE, "sonnet", "Hacked", "evil", "",
                200_000, true, true, false);
        ModelRegistryConfig userLayer = new ModelRegistryConfig(List.of(userSonnetOverride));

        ModelRegistryConfig merged = ReadOnlyDefaultModels.mergeWithReadOnlyDefaults(
                userLayer, List.of(readOnlySonnet));

        // 用户层 claude-role-sonnet 被跳过,只读恒胜
        assertEquals(1, merged.models().size());
        assertEquals("", merged.models().get(0).actualModel());
        assertTrue(merged.models().get(0).readOnly());
    }

    @Test
    public void mergeCodexUserWinsAndCustomAppended() {
        ModelConfig readOnlyCodex = new ModelConfig("gpt-5", CommonConstants.PROVIDER_CODEX,
                "", "GPT-5", "", "", 200_000, false, true, true);
        ModelConfig userCodexSameKey = new ModelConfig("gpt-5[1m]", CommonConstants.PROVIDER_CODEX,
                "", "My GPT-5", "", "", 1_000_000, true, true, false);
        ModelConfig userCustom = new ModelConfig("mimo-v2.5", CommonConstants.PROVIDER_CLAUDE,
                "sonnet", "Mimo", "mimo-v2.5", "", 1_000_000, true, true, false);
        ModelRegistryConfig userLayer = new ModelRegistryConfig(List.of(userCodexSameKey, userCustom));

        ModelRegistryConfig merged = ReadOnlyDefaultModels.mergeWithReadOnlyDefaults(
                userLayer, List.of(readOnlyCodex));

        // codex:用户优先(替换只读,可编辑);custom 原样追加
        ModelConfig codex = merged.models().stream()
                .filter(m -> CommonConstants.PROVIDER_CODEX.equals(m.provider())).findFirst().orElseThrow();
        assertFalse(codex.readOnly());
        assertEquals("My GPT-5", codex.label());
        assertTrue(merged.models().stream().anyMatch(m -> "mimo-v2.5".equals(m.id())));
    }

    @Test
    public void dedupKeyStripsCapacitySuffixAndLowercases() {
        assertEquals("codex:gpt-5", ReadOnlyDefaultModels.dedupKey("codex", "GPT-5[1m]"));
        assertEquals("claude:claude-role-sonnet",
                ReadOnlyDefaultModels.dedupKey("claude", "claude-role-sonnet"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests "com.github.claudecodegui.config.ReadOnlyDefaultModelsTest"`
Expected: 编译失败 — `ReadOnlyDefaultModels` 不存在。

- [ ] **Step 3: 实现 `ReadOnlyDefaultModels`**

Create `src/main/java/com/github/claudecodegui/config/ReadOnlyDefaultModels.java`:
```java
package com.github.claudecodegui.config;

import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.cli.common.CliSettings;
import com.github.claudecodegui.common.ClaudeRole;
import com.github.claudecodegui.common.CommonConstants;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 运行时计算的只读默认模型:从 CLI 配置文件读取真实模型,作为不可编辑/删除/停用的保留项
 * 叠加到 registry 用户层之上。
 *
 * <p>Claude 4 role 从 {@code ~/.claude/settings.json} 的 env 块解析 actualModel
 * (经 {@link CliSettings#readClaudeCliEnvironment()});Codex 默认从
 * {@code ~/.codex/config.toml} 的 {@code model=} 读取(经
 * {@link CliSettings#readCodexCliEnvironment()} → {@link CliConstants#ENV_CODEX_MODEL})。
 * 只读默认不进持久化:磁盘配置改动后下次 {@code getModelRegistry()} 即生效。
 */
public final class ReadOnlyDefaultModels {
    private ReadOnlyDefaultModels() {
    }

    /** 现算只读默认:从 CLI 配置文件读取(无配置则 role actualModel 为空、无 Codex)。 */
    public static List<ModelConfig> compute() {
        return compute(CliSettings.readClaudeCliEnvironment(), CliSettings.readCodexCliEnvironment());
    }

    /**
     * 注入式计算(便于单测):claudeEnv / codexEnv 由调用方提供。
     */
    public static List<ModelConfig> compute(Map<String, String> claudeEnv, Map<String, String> codexEnv) {
        List<ModelConfig> defaults = new ArrayList<>();
        for (ClaudeRole role : ClaudeRole.values()) {
            defaults.add(roleDefault(role, resolveFirstNonBlank(role.envKeys(), claudeEnv)));
        }
        String codexModel = codexEnv.get(CliConstants.ENV_CODEX_MODEL);
        if (codexModel != null && !codexModel.isBlank()) {
            defaults.add(codexDefault(codexModel.trim()));
        }
        return defaults;
    }

    /**
     * 将只读默认叠加到用户层(读真实配置文件)。
     * <ul>
     *   <li>Claude role 键({@code claude-role-*}):保留键,只读恒胜,用户层同键项被跳过。</li>
     *   <li>Codex / 其他键:用户优先(替换同键只读项),否则只读填补空缺。</li>
     * </ul>
     */
    public static ModelRegistryConfig mergeWithReadOnlyDefaults(ModelRegistryConfig userLayer) {
        return mergeWithReadOnlyDefaults(userLayer, compute());
    }

    /** 注入式合并(便于单测):只读默认列表由调用方提供。 */
    public static ModelRegistryConfig mergeWithReadOnlyDefaults(ModelRegistryConfig userLayer,
                                                                List<ModelConfig> readOnly) {
        List<ModelConfig> result = new ArrayList<>(readOnly);
        Set<String> readOnlyKeys = new HashSet<>();
        for (ModelConfig ro : readOnly) {
            readOnlyKeys.add(dedupKey(ro.provider(), ro.id()));
        }
        for (ModelConfig user : userLayer.models()) {
            boolean isReservedRole = CommonConstants.PROVIDER_CLAUDE.equals(user.provider())
                    && ClaudeRole.fromModelId(user.id()) != null;
            if (isReservedRole) {
                continue; // role 保留键:只读恒胜,跳过用户层(去重覆盖,不删磁盘)
            }
            String key = dedupKey(user.provider(), user.id());
            if (readOnlyKeys.contains(key)) {
                result.removeIf(m -> dedupKey(m.provider(), m.id()).equals(key)); // codex 用户优先
            }
            result.add(user);
        }
        return new ModelRegistryConfig(result);
    }

    /** 去重键:provider 小写 + ":" + id 剥容量后缀后小写(与 find/resolveModelSelection 语义一致)。 */
    public static String dedupKey(String provider, String id) {
        String normalizedProvider = provider == null ? "" : provider.toLowerCase(Locale.ROOT);
        String baseId = ModelRegistryConfig.stripCapacitySuffix(id).toLowerCase(Locale.ROOT);
        return normalizedProvider + ":" + baseId;
    }

    private static ModelConfig roleDefault(ClaudeRole role, String actualModel) {
        return new ModelConfig(
                role.roleId(),
                CommonConstants.PROVIDER_CLAUDE,
                role.shortName(),
                capitalize(role.shortName()),
                actualModel,
                capitalize(role.shortName()) + " role · 来自 ~/.claude/settings.json",
                role.contextWindow(),
                role.supports1MContext(),
                true,   // enabled
                true    // readOnly
        );
    }

    private static ModelConfig codexDefault(String codexModel) {
        return new ModelConfig(
                codexModel,
                CommonConstants.PROVIDER_CODEX,
                "",
                codexModel,
                "",
                "只读 · 来自 ~/.codex/config.toml",
                200_000,
                false,
                true,   // enabled
                true    // readOnly
        );
    }

    private static String resolveFirstNonBlank(List<String> keys, Map<String, String> env) {
        for (String key : keys) {
            String value = env.get(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew test --tests "com.github.claudecodegui.config.ReadOnlyDefaultModelsTest"`
Expected: 6 个测试 PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/github/claudecodegui/config/ReadOnlyDefaultModels.java src/test/java/com/github/claudecodegui/config/ReadOnlyDefaultModelsTest.java
git commit -m "feat(model-registry): add ReadOnlyDefaultModels (config-sourced readonly defaults + merge)"
```

---

### Task 3: `CodemossSettingsService` 合并 / 剥离 / 冲突校验 / 刷新序列化

**Files:**
- Modify: `src/main/java/com/github/claudecodegui/settings/CodemossSettingsService.java`（getModelRegistry :1683 / setModelRegistry :1706 / serializeModelRegistry :1768 / 新增方法)
- Test: `src/test/java/com/github/claudecodegui/settings/CodemossSettingsServiceModelRegistryTest.java`

- [ ] **Step 1: 写失败测试(新增用例)**

Append to `CodemossSettingsServiceModelRegistryTest.java`(在 `resolvesCodexRegistryIdAsActualModel` 测试之后、`useTemporaryHomeDirectory` 辅助方法之前):
```java
    @Test
    public void getModelRegistryMarksDefaultRolesReadOnly() throws Exception {
        useTemporaryHomeDirectory(Files.createTempDirectory("model-registry-readonly-home"));
        CodemossSettingsService service = new CodemossSettingsService();

        ModelRegistryConfig registry = service.getModelRegistry();

        assertTrue(registry.models().stream().anyMatch(model -> model.id().equals("claude-role-sonnet")));
        assertTrue(registry.models().stream()
                .filter(model -> model.id().equals("claude-role-sonnet"))
                .allMatch(ModelConfig::readOnly));
    }

    @Test
    public void setModelRegistryRejectsNewConflictWithReadOnlyRole() throws Exception {
        useTemporaryHomeDirectory(Files.createTempDirectory("model-registry-conflict-home"));
        CodemossSettingsService service = new CodemossSettingsService();
        ModelRegistryConfig conflicting = new ModelRegistryConfig(List.of(
                new ModelConfig("claude-role-sonnet", "claude", "sonnet",
                        "Hacked", "evil", "", 200_000, true, true)
        ));

        assertFalse(service.setModelRegistry(conflicting).isValid());
        // 未落盘:用户层仍为空 → getModelRegistry 只剩只读默认
        assertFalse(service.getModelRegistry().models().stream()
                .anyMatch(model -> "evil".equals(model.actualModel())));
    }

    @Test
    public void setModelRegistryAcceptsEmptyUserLayerBecauseReadOnlyGuaranteesEnabled() throws Exception {
        useTemporaryHomeDirectory(Files.createTempDirectory("model-registry-empty-home"));
        CodemossSettingsService service = new CodemossSettingsService();

        assertTrue(service.setModelRegistry(new ModelRegistryConfig(List.of())).isValid());
        assertTrue(service.getModelRegistry().models().stream()
                .anyMatch(model -> model.id().equals("claude-role-sonnet")));
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests "com.github.claudecodegui.settings.CodemossSettingsServiceModelRegistryTest"`
Expected: 新增 3 个用例失败(getModelRegistry 仍返回非 readOnly;冲突未拒绝;空用户层因"at least one enabled"被拒)。

- [ ] **Step 3: 改写 `getModelRegistry()`**

In `CodemossSettingsService.java`, replace the existing `getModelRegistry()` method（:1683-1701)with:
```java
    /**
     * Read the effective model registry = merge(persisted user layer, read-only defaults).
     * Read-only defaults (Claude 4 roles from settings.json + Codex from config.toml) are
     * computed at runtime and never persisted.
     */
    public ModelRegistryConfig getModelRegistry() {
        try {
            return ReadOnlyDefaultModels.mergeWithReadOnlyDefaults(readPersistedUserLayer());
        } catch (Exception e) {
            LOG.warn("[CodemossSettings] Failed to read model registry, using read-only defaults: " + e.getMessage());
            return ReadOnlyDefaultModels.mergeWithReadOnlyDefaults(new ModelRegistryConfig(java.util.List.of()));
        }
    }
```

Add the import near the other `com.github.claudecodegui.config.*` imports at the top of the file:
```java
import com.github.claudecodegui.config.ReadOnlyDefaultModels;
```

- [ ] **Step 4: 改写 `setModelRegistry()` + 新增辅助方法**

Replace the existing `setModelRegistry()` method（:1706-1724)with:
```java
    /**
     * Save the user-layer model registry. Read-only items are stripped (never persisted).
     * New entries conflicting with read-only default keys are rejected; validation runs on
     * the effective registry (user layer + read-only defaults) so the read-only roles
     * guarantee "at least one enabled" — an empty user layer is therefore valid.
     */
    public ModelConfigValidator.ValidationResult setModelRegistry(ModelRegistryConfig registry) {
        ModelRegistryConfig userOnly = stripReadOnly(registry);
        ModelConfigValidator.ValidationResult conflict = checkNoNewConflictsWithReadOnly(userOnly);
        if (!conflict.isValid()) {
            LOG.warn("[CodemossSettings] Model registry conflicts with read-only defaults, not saving: "
                    + conflict.errors());
            return conflict;
        }
        ModelConfigValidator.ValidationResult validation =
                ModelConfigValidator.validate(ReadOnlyDefaultModels.mergeWithReadOnlyDefaults(userOnly));
        if (!validation.isValid()) {
            LOG.warn("[CodemossSettings] Model registry validation failed, not saving: " + validation.errors());
            return validation;
        }
        try {
            JsonObject config = readConfig();
            config.add(MODEL_REGISTRY_KEY, serializeModelRegistry(userOnly));
            writeConfig(config);
            LOG.info("[CodemossSettings] Saved model registry");
            return validation;
        } catch (Exception e) {
            LOG.error("[CodemossSettings] Failed to save model registry: " + e.getMessage());
            var errors = new java.util.ArrayList<String>();
            errors.add("保存失败: " + e.getMessage());
            return new ModelConfigValidator.ValidationResult(errors, java.util.List.of());
        }
    }
```

Add these new methods immediately after `resetModelRegistry()`（:1738 之后):
```java
    /**
     * Read the raw persisted user layer without read-only defaults and without the
     * getDefault() fallback. Missing/invalid config returns an empty user layer.
     */
    private ModelRegistryConfig readPersistedUserLayer() {
        try {
            JsonObject config = readConfig();
            if (!config.has(MODEL_REGISTRY_KEY) || !config.get(MODEL_REGISTRY_KEY).isJsonObject()) {
                return new ModelRegistryConfig(java.util.List.of());
            }
            ModelRegistryConfig parsed = parseModelRegistry(config.getAsJsonObject(MODEL_REGISTRY_KEY));
            return stripReadOnly(parsed); // 防御:磁盘上不应残留只读项
        } catch (Exception e) {
            return new ModelRegistryConfig(java.util.List.of());
        }
    }

    /** 剥离 readOnly=true 项(后端权威:只读默认永不进持久化)。 */
    private static ModelRegistryConfig stripReadOnly(ModelRegistryConfig registry) {
        java.util.List<ModelConfig> userOnly = new java.util.ArrayList<>();
        for (ModelConfig model : registry.models()) {
            if (!model.readOnly()) {
                userOnly.add(model);
            }
        }
        return new ModelRegistryConfig(userOnly);
    }

    /**
     * 仅拦截"新增"冲突:用户层中、与只读默认键相同、且当前磁盘用户层不存在的项。
     * legacy 同键项放行(合并时 role 被跳过 / codex 被用户覆盖),避免阻塞无关保存。
     */
    private ModelConfigValidator.ValidationResult checkNoNewConflictsWithReadOnly(ModelRegistryConfig incoming) {
        java.util.Set<String> currentKeys = new java.util.HashSet<>();
        for (ModelConfig model : readPersistedUserLayer().models()) {
            currentKeys.add(ReadOnlyDefaultModels.dedupKey(model.provider(), model.id()));
        }
        java.util.Set<String> readOnlyKeys = new java.util.HashSet<>();
        for (ModelConfig model : ReadOnlyDefaultModels.compute()) {
            readOnlyKeys.add(ReadOnlyDefaultModels.dedupKey(model.provider(), model.id()));
        }
        java.util.List<String> errors = new java.util.ArrayList<>();
        for (ModelConfig model : incoming.models()) {
            String key = ReadOnlyDefaultModels.dedupKey(model.provider(), model.id());
            if (readOnlyKeys.contains(key) && !currentKeys.contains(key)) {
                errors.add("模型 " + model.id() + " 与配置文件默认模型冲突,无法新增");
            }
        }
        return errors.isEmpty()
                ? new ModelConfigValidator.ValidationResult(java.util.List.of(), java.util.List.of())
                : new ModelConfigValidator.ValidationResult(errors, java.util.List.of());
    }

    /** 序列化当前 effective registry 为 JSON 字符串,供提供商切换后推送刷新。 */
    public String getModelRegistryJson() {
        return serializeModelRegistry(getModelRegistry()).toString();
    }
```

- [ ] **Step 5: 序列化加 `readOnly`**

In `serializeModelRegistry()`（:1768-1794),在 `obj.addProperty("enabled", model.enabled());` 之后插入一行:
```java
            obj.addProperty("readOnly", model.readOnly());
```

- [ ] **Step 6: 运行测试确认通过(部分)**

Run: `./gradlew test --tests "com.github.claudecodegui.settings.CodemossSettingsServiceModelRegistryTest"`
Expected: 新增 3 用例 PASS;`returnsDefaultModelRegistryWhenConfigIsMissing` / `rejectsInvalidModelRegistryWithoutPersistingIt` 仍 PASS。
注意:`persistsValidCustomModelRegistry` 此时**会失败**(冲突校验拒绝 claude-role-opus)——由 Task 8 Step 1 改写修复,勿在此停滞。

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/github/claudecodegui/settings/CodemossSettingsService.java src/test/java/com/github/claudecodegui/settings/CodemossSettingsServiceModelRegistryTest.java
git commit -m "feat(model-registry): backend merge + strip + new-conflict check + refresh serializer"
```

---

### Task 4: `SettingsHandler` 序列化加 `readOnly`

**Files:**
- Modify: `src/main/java/com/github/claudecodegui/handler/SettingsHandler.java`（serializeModelRegistry :537)

- [ ] **Step 1: 加 `readOnly` 字段**

In `SettingsHandler.serializeModelRegistry()`（:537-559),在 `obj.addProperty("enabled", model.enabled());`（:554)之后插入一行:
```java
            obj.addProperty("readOnly", model.readOnly());
```

> `parseModelRegistryFromJson()`（:561)走 9 参构造器(`readOnly` 默认 false),无需改动——后端权威,入站只读标记被忽略。

- [ ] **Step 2: 编译确认**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/github/claudecodegui/handler/SettingsHandler.java
git commit -m "feat(settings-handler): serialize readOnly flag in model registry"
```

---

### Task 5: `ClaudeProviderOperations` 切换后推送 `model_registry` 刷新

**Files:**
- Modify: `src/main/java/com/github/claudecodegui/handler/provider/ClaudeProviderOperations.java`（:313 / :344 / :360 三处 `invokeLater`)

注入内容(三处相同,均为在对应 `invokeLater` 块的 `handleGetActiveProvider();` 之后追加):
```java
                    context.dispatchEvent("model_registry",
                            context.escapeJs(context.getSettingsService().getModelRegistryJson()));
```

- [ ] **Step 1: LOCAL 路径注入**

LOCAL 路径 `invokeLater` 块（:313-318)改为:
```java
                ApplicationManager.getApplication().invokeLater(() -> {
                    context.dispatchEvent("toast.switch_success",
                            context.escapeJs(com.github.claudecodegui.i18n.ClaudeCodeGuiBundle.message("toast.localProviderSwitchSuccess")));
                    handleGetProviders();
                    handleGetActiveProvider();
                    context.dispatchEvent("model_registry",
                            context.escapeJs(context.getSettingsService().getModelRegistryJson()));
                });
```

- [ ] **Step 2: CLI_LOGIN 路径注入**

CLI_LOGIN 路径 `invokeLater` 块（:344-353)在 `handleGetActiveProvider();` 之后、`if (accountEmail != null)` 之前插入同样两行,使块变为:
```java
                ApplicationManager.getApplication().invokeLater(() -> {
                    context.dispatchEvent("toast.switch_success",
                            context.escapeJs(com.github.claudecodegui.i18n.ClaudeCodeGuiBundle.message("toast.cliLoginSwitchSuccess")));
                    handleGetProviders();
                    handleGetActiveProvider();
                    context.dispatchEvent("model_registry",
                            context.escapeJs(context.getSettingsService().getModelRegistryJson()));
                    if (accountEmail != null) {
                        context.dispatchEvent("provider.cli_login_account",
                                context.escapeJs(accountEmail));
                    }
                });
```

- [ ] **Step 3: 常规路径注入**

常规路径 `invokeLater` 块（:360-366)改为:
```java
            ApplicationManager.getApplication().invokeLater(() -> {
                String successMsg = com.github.claudecodegui.i18n.ClaudeCodeGuiBundle.message("toast.providerSwitchSuccess")
                        + com.github.claudecodegui.i18n.ClaudeCodeGuiBundle.message("provider.switchSyncClaude");
                context.dispatchEvent("toast.switch_success", context.escapeJs(successMsg));
                handleGetProviders();
                handleGetActiveProvider();
                context.dispatchEvent("model_registry",
                        context.escapeJs(context.getSettingsService().getModelRegistryJson()));
            });
```

- [ ] **Step 4: 编译确认**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/github/claudecodegui/handler/provider/ClaudeProviderOperations.java
git commit -m "feat(provider): push refreshed model_registry after Claude provider switch"
```

---

### Task 6: 前端 `modelRegistry.ts` 增加 `readOnly`

**Files:**
- Modify: `webview/src/utils/modelRegistry.ts`（ModelRegistryItem :7 / parseModelRegistryPayload :244)
- Test: `webview/src/utils/modelRegistry.test.ts`

- [ ] **Step 1: 写失败测试**

Create `webview/src/utils/modelRegistry.test.ts`:
```ts
import { describe, it, expect } from 'vitest';
import { parseModelRegistryPayload } from './modelRegistry';

describe('parseModelRegistryPayload', () => {
  it('reads readOnly flag when true', () => {
    const payload = JSON.stringify({
      items: [
        {
          id: 'claude-role-sonnet',
          provider: 'claude',
          label: 'Sonnet',
          contextWindow: 200000,
          readOnly: true,
        },
      ],
    });
    const parsed = parseModelRegistryPayload(payload);
    expect(parsed?.items[0].readOnly).toBe(true);
  });

  it('defaults readOnly to false when absent', () => {
    const payload = JSON.stringify({
      items: [
        { id: 'mimo', provider: 'claude', label: 'Mimo', contextWindow: 200000 },
      ],
    });
    const parsed = parseModelRegistryPayload(payload);
    expect(parsed?.items[0].readOnly).toBe(false);
  });
});
```

- [ ] **Step 2: 运行测试确认失败**

Run (in `webview/`): `npx vitest run src/utils/modelRegistry.test.ts`
Expected: FAIL — `readOnly` 为 `undefined`(非 `true`/`false`)。

- [ ] **Step 3: 实现**

In `webview/src/utils/modelRegistry.ts`:

(a) `ModelRegistryItem` 接口（:7-13)在 `enabled?: boolean;` 之后新增:
```ts
  readOnly?: boolean;
```

(b) `parseModelRegistryPayload` 内 push 的对象（:244-254),在 `enabled: obj.enabled !== false,` 之后新增一行:
```ts
        readOnly: obj.readOnly === true,
```

- [ ] **Step 4: 运行测试确认通过**

Run (in `webview/`): `npx vitest run src/utils/modelRegistry.test.ts`
Expected: 2 个测试 PASS。

- [ ] **Step 5: 提交**

```bash
git add webview/src/utils/modelRegistry.ts webview/src/utils/modelRegistry.test.ts
git commit -m "feat(webview): parse readOnly flag in model registry payload"
```

---

### Task 7: `ModelSelect` 空模型守卫(修复崩溃点)

**Files:**
- Modify: `webview/src/components/ChatInputBox/selectors/ModelSelect.tsx`（:130-135 / :265-279)
- Test: `webview/src/components/ChatInputBox/selectors/ModelSelect.test.tsx`

- [ ] **Step 1: 写失败测试(崩溃回归)**

Append to `ModelSelect.test.tsx` 的 `describe('ModelSelect', ...)` 块内（`Codex 不再内置...` 测试之后):
```tsx
  it('models 为空时不崩溃,渲染未配置占位', () => {
    render(
      <ModelSelect
        value=""
        onChange={vi.fn()}
        models={[]}
        currentProvider="codex"
      />,
    );

    const button = screen.getByRole('button');
    expect(button).toBeDisabled();
    expect(button.textContent).toContain('chat.noModelConfigured');
  });
```

- [ ] **Step 2: 运行测试确认失败**

Run (in `webview/`): `npx vitest run src/components/ChatInputBox/selectors/ModelSelect.test.tsx`
Expected: FAIL — `TypeError: Cannot read properties of undefined (reading 'id')`(原崩溃)。

- [ ] **Step 3: 实现守卫**

In `ModelSelect.tsx`:

(a) 替换 :130-135 的 `currentModel` 计算为可空解析:
```tsx
  // Strip [1m] suffix for finding the model in the list
  const strippedValue = strip1MContextSuffix(value);
  const normalizedValue = currentProvider === 'claude' ? normalizeClaudeModelId(strippedValue) : strippedValue;
  const hasModels = models.length > 0;
  const exactSelectedModel = models.find(m => m.id === strippedValue);
  const resolvedModel: ModelInfo | null = hasModels
    ? (exactSelectedModel || models.find(m => m.id === normalizedValue) || models[0])
    : null;
  const modelMapping = readClaudeModelMapping();
```

(b) 替换 :265-279 的 `<button>` 为带守卫的渲染:
```tsx
      <button
        ref={buttonRef}
        className="selector-button"
        onClick={handleToggle}
        disabled={!hasModels}
        title={resolvedModel
          ? t('chat.currentModel', { model: getModelLabel(resolvedModel, true) })
          : t('chat.noModelConfigured', 'No model configured')}
      >
        {resolvedModel ? (
          <>
            <ProviderModelIcon
              providerId={currentProvider}
              modelId={resolveModelIdForIcon(resolvedModel.id, modelMapping, MODEL_ID_TO_MAPPING_KEY)}
              size={12}
              colored
            />
            <span className="selector-button-text">{getModelLabel(resolvedModel, true)}</span>
          </>
        ) : (
          <span className="selector-button-text">{t('chat.noModelConfigured', 'No model configured')}</span>
        )}
        <span className={`codicon codicon-chevron-${isOpen ? 'up' : 'down'}`} style={CHEVRON_ICON_STYLE} />
      </button>
```

> `isSelectedModel`（:137-148)与下拉项渲染均基于 `models` 数组迭代,空数组时自然为空,无需改动。

- [ ] **Step 4: 运行测试确认通过**

Run (in `webview/`): `npx vitest run src/components/ChatInputBox/selectors/ModelSelect.test.tsx`
Expected: 全部 PASS(含新增空模型用例)。

- [ ] **Step 5: 提交**

```bash
git add webview/src/components/ChatInputBox/selectors/ModelSelect.tsx webview/src/components/ChatInputBox/selectors/ModelSelect.test.tsx
git commit -m "fix(model-select): guard against empty models to prevent crash"
```

---

### Task 8: `ModelRegistrySection` 只读渲染 + 剥离只读提交 + 改写现有测试

**Files:**
- Modify: `webview/src/components/settings/ModelRegistrySection/index.tsx`（persistRegistry :85 / rowActions :277)
- Modify: `src/test/java/com/github/claudecodegui/settings/CodemossSettingsServiceModelRegistryTest.java`（persistsValidCustomModelRegistry :54)
- Create: `webview/src/components/settings/ModelRegistrySection/index.test.tsx`

- [ ] **Step 1: 改写后端现有测试 `persistsValidCustomModelRegistry`**

In `CodemossSettingsServiceModelRegistryTest.java`, replace the whole `persistsValidCustomModelRegistry` method（:53-68)with:
```java
    @Test
    public void persistsValidCustomModelRegistry() throws Exception {
        useTemporaryHomeDirectory(Files.createTempDirectory("model-registry-persist-home"));
        CodemossSettingsService service = new CodemossSettingsService();
        // 非 role 自定义 Claude 模型(id=actualModel);role 键已被冲突校验保留为只读,不可持久化。
        ModelRegistryConfig config = new ModelRegistryConfig(List.of(
                new ModelConfig("mimo-v2.5-pro", "claude", "opus",
                        "Mimo V2.5 Pro", "mimo-v2.5-pro", "", 1_000_000, true, true)
        ));

        assertTrue(service.setModelRegistry(config).isValid());

        ModelConfig saved = service.getModelRegistry().models().stream()
                .filter(model -> "mimo-v2.5-pro".equals(model.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("persisted custom model not found in effective registry"));
        assertEquals("mimo-v2.5-pro", saved.actualModel());
        assertEquals(1_000_000, saved.contextWindow());
        assertFalse(saved.readOnly()); // 用户自定义项可编辑
    }
```

> `assertFalse` 已在文件顶部 import（:15)。

- [ ] **Step 2: 写前端只读渲染失败测试**

Create `webview/src/components/settings/ModelRegistrySection/index.test.tsx`:
```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import ModelRegistrySection from './index';
import { __setModelRegistryForTests } from '../../../utils/modelRegistry';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (_key: string, fallback?: string) => fallback ?? _key }),
}));

const mockAddToast = vi.fn();

describe('ModelRegistrySection', () => {
  beforeEach(() => {
    mockAddToast.mockClear();
  });

  it('只读行不渲染 Edit/Delete 按钮,改显锁标', () => {
    __setModelRegistryForTests({
      items: [
        {
          id: 'claude-role-sonnet',
          provider: 'claude',
          role: 'sonnet',
          label: 'Sonnet',
          contextWindow: 200000,
          readOnly: true,
          enabled: true,
        },
        {
          id: 'mimo',
          provider: 'claude',
          role: 'sonnet',
          label: 'Mimo',
          contextWindow: 200000,
          readOnly: false,
          enabled: true,
        },
      ],
    });

    render(<ModelRegistrySection addToast={mockAddToast} />);

    // 只读行只有锁标,无 edit/trash
    expect(screen.queryAllByTitle('Edit')).toHaveLength(0);
    expect(screen.queryAllByTitle('Delete')).toHaveLength(0);
    expect(screen.getAllByTitle('Read-only').length).toBeGreaterThan(0);
  });

  it('可编辑行渲染 Edit/Delete 按钮', () => {
    __setModelRegistryForTests({
      items: [
        {
          id: 'mimo',
          provider: 'claude',
          role: 'sonnet',
          label: 'Mimo',
          contextWindow: 200000,
          readOnly: false,
          enabled: true,
        },
      ],
    });

    render(<ModelRegistrySection addToast={mockAddToast} />);

    expect(screen.getByTitle('Edit')).toBeInTheDocument();
    expect(screen.getByTitle('Delete')).toBeInTheDocument();
  });
});
```

- [ ] **Step 3: 运行测试确认失败**

Run (in `webview/`): `npx vitest run src/components/settings/ModelRegistrySection/index.test.tsx`
Expected: FAIL — 只读行仍渲染 Edit/Delete,无锁标。

同时确认后端:
Run: `./gradlew test --tests "com.github.claudecodegui.settings.CodemossSettingsServiceModelRegistryTest"`
Expected: `persistsValidCustomModelRegistry` 现在 PASS(Task 3 起曾失败,此处修复)。

- [ ] **Step 4: 实现只读渲染 + 剥离只读提交**

In `ModelRegistrySection/index.tsx`:

(a) 替换 `persistRegistry`（:85-88)为剥离只读后再提交:
```tsx
  const persistRegistry = useCallback((nextRegistry: ModelRegistryPayload) => {
    const userOnly = { items: nextRegistry.items.filter((item) => !item.readOnly) };
    setRegistry(nextRegistry);
    sendBridgeEvent('set_model_registry', JSON.stringify(userOnly));
  }, []);
```

(b) 替换 `rowActions` 单元格（:277-284)为只读判定:
```tsx
            <div className={styles.rowActions}>
              {model.readOnly ? (
                <span className={`${styles.iconButton} codicon codicon-lock`} aria-hidden="true"
                      title={t('settings.models.readonly', 'Read-only')} />
              ) : (
                <>
                  <button className={styles.iconButton} onClick={() => startEdit(model)} title={t('common.edit', 'Edit')}>
                    <span className="codicon codicon-edit" aria-hidden="true" />
                  </button>
                  <button className={styles.iconButtonDanger} onClick={() => removeModel(model)} title={t('common.delete', 'Delete')}>
                    <span className="codicon codicon-trash" aria-hidden="true" />
                  </button>
                </>
              )}
            </div>
```

> 测试用 `getByTitle('Edit'/'Delete'/'Read-only')` 匹配 `title` 属性;该 mock 下 `t(key, fallback)` 返回 fallback(`'Edit'`/`'Delete'`/`'Read-only'`)。

- [ ] **Step 5: 运行测试确认通过**

Run (in `webview/`): `npx vitest run src/components/settings/ModelRegistrySection/index.test.tsx`
Expected: 2 个测试 PASS。

- [ ] **Step 6: 提交**

```bash
git add webview/src/components/settings/ModelRegistrySection/index.tsx webview/src/components/settings/ModelRegistrySection/index.test.tsx src/test/java/com/github/claudecodegui/settings/CodemossSettingsServiceModelRegistryTest.java
git commit -m "feat(model-registry-section): render read-only rows as locked + strip readonly on persist"
```

---

### Task 9: 全量回归 + 端到端验证

**Files:** 无(仅运行 + 手测)

- [ ] **Step 1: 后端全量测试**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL;`CodemossSettingsServiceModelRegistryTest`(7 用例)、`ModelConfigTest`(3)、`ReadOnlyDefaultModelsTest`(6)全绿;原有用例无回归。

- [ ] **Step 2: 前端权威门**

Run (in `webview/`): `npm test`
Expected: 176 个 `.test.js` + 50 个 `.test.mjs` 全绿;`ModelSelect.test.tsx`(`CODEX_MODELS` 为空 :141)继续通过;新增 `modelRegistry.test.ts` / `ModelRegistrySection/index.test.tsx` 通过。

- [ ] **Step 3: 端到端手测(IDE 内)**

1. 未配置 Codex:切换到 Codex provider → 模型选择器显示"未配置模型"占位、按钮 disabled、**不崩溃**。
2. 配置 Codex:`~/.codex/config.toml` 写 `model = "gpt-5"` → 刷新 → 设置页出现只读 `gpt-5`(锁标、无 Edit/Delete);ChatInputBox 选择器出现 `gpt-5`。
3. Claude actualModel:`~/.claude/settings.json` env 设 `ANTHROPIC_DEFAULT_SONNET_MODEL=mimo` → 设置页只读 Sonnet 行显示 mimo。
4. 提供商切换刷新:在供应商管理重新授权/切换 Claude provider → **无需手动刷新**,只读 role 的 actualModel 自动反映新 settings.json。
5. 新增冲突拦截:设置页新增 Claude 模型 actualModel 填 `claude-role-sonnet`(或新增 Codex 与 config.toml 同名)→ 提交被拒(toast:与配置文件默认模型冲突)。
6. reset:点 Reset → 用户层清空,4 个只读 role(+ Codex 默认)仍在。

- [ ] **Step 4: 最终确认**

```bash
git status   # 确认无遗漏改动
```

---

## Self-Review

**1. Spec coverage:**
- §1 崩溃修复 → Task 7(空模型守卫)。
- §2 后端合并 + 不对称去重 → Task 2(mergeWithReadOnlyDefaults + dedupKey)、Task 3(getModelRegistry)。
- §3 readOnly 字段 + 序列化 → Task 1(ModelConfig)、Task 3/4(序列化 readOnly)、Task 6(前端解析)。
- §4 只读默认计算器(复用 CliSettings)→ Task 2。
- §4 冲突校验(仅拦截新增)→ Task 3(checkNoNewConflictsWithReadOnly)+ 测试。
- §4 validate(effective)使空用户层合法 → Task 3 + 测试。
- §4 getModelRegistryJson 刷新序列化 → Task 3 + Task 5(三处推送)。
- §4 调用点零改动 → 未改 ModelProviderHandler/resolveModelSelection,符合。
- §5 前端守卫 + 只读渲染 + 剥离提交 → Task 6/7/8。
- §6 数据流(提供商切换自动刷新)→ Task 5。
- §9 迁移(legacy 放行)→ Task 3 冲突校验仅拦截新增;`persistsValidCustomModelRegistry` 改非 role。
- §10 测试改写 → Task 8 Step 1。

**2. Placeholder scan:** 无 TBD/TODO;每个代码步均含完整代码;后端用例给出完整测试体;前端用例给出完整 render + 断言。

**3. Type consistency:**
- `ModelConfig` 10 参(末参 readOnly)在 Task 1 定义,Task 2 roleDefault/codexDefault、Task 3 序列化、Task 8 测试均用一致签名。
- `ReadOnlyDefaultModels.compute()` / `mergeWithReadOnlyDefaults()` / `dedupKey()` 在 Task 2 定义,Task 3 `getModelRegistry`/`setModelRegistry`/`checkNoNewConflictsWithReadOnly`/`getModelRegistryJson` 引用一致。
- 前端 `ModelRegistryItem.readOnly?: boolean`(Task 6)与 `ModelRegistrySection` `model.readOnly`(Task 8)一致;`ModelSelect` 仅用 `hasModels` 守卫,不依赖该字段。
- `getModelRegistryJson()`(Task 3)与 `ClaudeProviderOperations`(Task 5)调用签名一致。
- `ValidationResult(List, List)` 构造在 Task 3 冲突校验使用,与 `ModelConfigValidator` 签名一致。

**4. 风险点(已在任务内化解):**
- Task 3 Step 6 明确 `persistsValidCustomModelRegistry` 暂时失败,由 Task 8 Step 1 修复——勿在 Task 3 停下。
- merge 中 `result.removeIf` 操作的是 `new ArrayList<>(readOnly)` 可变副本,安全。
- 前端测试 `__setModelRegistryForTests`(modelRegistry.ts:139 已存在)注入 registry;`getByTitle` 匹配 `title` 属性,渲染已对齐。
