package com.github.claudecodegui.settings;

import com.github.claudecodegui.config.ModelConfig;
import com.github.claudecodegui.config.ModelRegistryConfig;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * ModelRegistrySettingsService 领域逻辑测试(A3 领域拆分第四步,docs §A3)。
 *
 * <p>覆盖 effective registry 读写委托:文件缺失时返回含只读默认、合法用户层往返、
 * 无效模型被校验拒绝、与只读默认键冲突被拒绝、空用户层合法(只读默认保证 enabled)、
 * {@code getModelRegistryJson} 下发 {@code supportedReasoningLevels} 派生字段(H3 守门)、
 * 经 CSS 委托链。
 *
 * <p>夹具参照 {@link CodemossSettingsServiceModelRegistryTest}:反射注入
 * {@code PlatformUtils.cachedRealHomeDir} 指向隔离临时 home,使 ConfigRepository 落在隔离的
 * {@code .codemoss/config.json}。合法/非法模型构造借鉴该测试(已验证 validator 行为),
 * 但本类聚焦 Service 直调 API + 委托链语义不漂移,不重复 resolveModelSelection /
 * Codex config.toml 只读覆盖等 record 级 / CSS 级深层矩阵(由 CSS 测试与 config 包测试守门)。
 */
public class ModelRegistrySettingsServiceTest {
    private String originalHomeDir;

    @After
    public void tearDown() throws Exception {
        if (originalHomeDir != null) {
            setCachedHomeDirectory(originalHomeDir);
            originalHomeDir = null;
        }
    }

    // ==================== effective registry = merge(user layer, read-only defaults) ====================

    @Test
    public void getModelRegistryIncludesReadOnlyDefaultsWhenFileAbsent() throws Exception {
        useTemporaryHomeDirectory(Files.createTempDirectory("model-registry-svc-default-home"));

        ModelRegistrySettingsService svc = new ModelRegistrySettingsService(SettingsTestConfig.create().configStore());

        ModelRegistryConfig registry = svc.getModelRegistry();
        // 文件缺失 → 用户层空 → effective 仅含只读默认(Claude 4 roles from ~/.claude/settings.json)。
        assertTrue(registry.models().stream().anyMatch(model -> model.id().equals("claude-role-sonnet")));
        assertTrue(registry.models().stream()
                .filter(model -> model.id().equals("claude-role-sonnet"))
                .allMatch(ModelConfig::readOnly));
    }

    @Test
    public void setModelRegistryPersistsValidUserLayerModel() throws Exception {
        useTemporaryHomeDirectory(Files.createTempDirectory("model-registry-svc-persist-home"));
        ModelRegistrySettingsService svc = new ModelRegistrySettingsService(SettingsTestConfig.create().configStore());
        // 非 role 自定义 Claude 模型(id=actualModel);id 非 claude-role-xxx 不与只读默认键冲突。
        ModelRegistryConfig config = new ModelRegistryConfig(List.of(
                new ModelConfig("mimo-v2.5-pro", "claude", "opus",
                        "Mimo V2.5 Pro", "mimo-v2.5-pro", "", 1_000_000, true, true)
        ));

        assertTrue(svc.setModelRegistry(config).isValid());

        ModelConfig saved = svc.getModelRegistry().models().stream()
                .filter(model -> "mimo-v2.5-pro".equals(model.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("persisted custom model not found in effective registry"));
        assertEquals("mimo-v2.5-pro", saved.actualModel());
        assertEquals(1_000_000, saved.contextWindow());
        assertFalse(saved.readOnly()); // 用户自定义项可编辑
    }

    @Test
    public void setModelRegistryRejectsInvalidModelWithoutPersisting() throws Exception {
        useTemporaryHomeDirectory(Files.createTempDirectory("model-registry-svc-invalid-home"));
        ModelRegistrySettingsService svc = new ModelRegistrySettingsService(SettingsTestConfig.create().configStore());
        ModelRegistryConfig invalid = new ModelRegistryConfig(List.of(
                new ModelConfig("bad", "codex", "", "Bad", "", "", 200_000, true, true)
        ));

        assertFalse(svc.setModelRegistry(invalid).isValid());
        // 未落盘:effective 仍仅含只读默认,无非法 codex 项。
        assertFalse(svc.getModelRegistry().models().stream()
                .anyMatch(model -> model.provider().equals("codex")));
    }

    @Test
    public void setModelRegistryRejectsNewConflictWithReadOnlyRole() throws Exception {
        useTemporaryHomeDirectory(Files.createTempDirectory("model-registry-svc-conflict-home"));
        ModelRegistrySettingsService svc = new ModelRegistrySettingsService(SettingsTestConfig.create().configStore());
        // 新增与只读默认 claude-role-sonnet 同键的项应被拒绝(checkNoNewConflictsWithReadOnly)。
        ModelRegistryConfig conflicting = new ModelRegistryConfig(List.of(
                new ModelConfig("claude-role-sonnet", "claude", "sonnet",
                        "Hacked", "evil", "", 200_000, true, true)
        ));

        assertFalse(svc.setModelRegistry(conflicting).isValid());
        // 未落盘:只读默认未被篡改。
        assertFalse(svc.getModelRegistry().models().stream()
                .anyMatch(model -> "evil".equals(model.actualModel())));
    }

    @Test
    public void setModelRegistryAcceptsEmptyUserLayerBecauseReadOnlyGuaranteesEnabled() throws Exception {
        useTemporaryHomeDirectory(Files.createTempDirectory("model-registry-svc-empty-home"));
        ModelRegistrySettingsService svc = new ModelRegistrySettingsService(SettingsTestConfig.create().configStore());

        // 空用户层合法:effective = merge(空, 只读默认),只读 roles 保证 ≥1 enabled。
        assertTrue(svc.setModelRegistry(new ModelRegistryConfig(List.of())).isValid());
        assertTrue(svc.getModelRegistry().models().stream()
                .anyMatch(model -> model.id().equals("claude-role-sonnet")));
    }

    // ==================== H3 守门:getModelRegistryJson 下发派生字段 ====================

    @Test
    public void getModelRegistryJsonEmitsSupportedReasoningLevelsForClaudeRoles() throws Exception {
        useTemporaryHomeDirectory(Files.createTempDirectory("model-registry-svc-json-home"));
        ModelRegistrySettingsService svc = new ModelRegistrySettingsService(SettingsTestConfig.create().configStore());

        // 下发路径必须复用静态 ModelRegistryService.serialize 以下发 supportedReasoningLevels 派生字段,
        // 否则前端 ReasoningSelect 拿不到档位会整体隐藏(H3:双序列化路径字段漂移)。
        String json = svc.getModelRegistryJson();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        JsonObject sonnetItem = null;
        for (var elem : root.getAsJsonArray("items")) {
            JsonObject item = elem.getAsJsonObject();
            if ("claude-role-sonnet".equals(item.get("id").getAsString())) {
                sonnetItem = item;
                break;
            }
        }
        assertNotNull("claude-role-sonnet 默认项必须存在于 getModelRegistryJson 下发载荷", sonnetItem);
        assertTrue("getModelRegistryJson 必须下发 supportedReasoningLevels 派生字段",
                sonnetItem.has("supportedReasoningLevels"));
        assertTrue("claude-role-sonnet 的 supportedReasoningLevels 应为非空数组",
                sonnetItem.get("supportedReasoningLevels").isJsonArray()
                        && !sonnetItem.get("supportedReasoningLevels").getAsJsonArray().isEmpty());
    }

    // ==================== 委托链(CSS 转发 → Service) ====================

    @Test
    public void delegationViaCssFacade() throws Exception {
        useTemporaryHomeDirectory(Files.createTempDirectory("model-registry-svc-css-home"));
        CodemossSettingsService css = new CodemossSettingsService();

        // CSS 3 public 转发委托,语义与直调 Service 一致(22 外部调用点经 CSS public 走委托)。
        assertTrue(css.getModelRegistry().models().stream()
                .anyMatch(model -> model.id().equals("claude-role-sonnet")));

        ModelRegistryConfig config = new ModelRegistryConfig(List.of(
                new ModelConfig("mimo-v2.5-pro", "claude", "opus",
                        "Mimo V2.5 Pro", "mimo-v2.5-pro", "", 1_000_000, true, true)
        ));
        assertTrue(css.setModelRegistry(config).isValid());

        // getModelRegistryJson 经 CSS 转发仍下发派生字段。
        JsonObject root = JsonParser.parseString(css.getModelRegistryJson()).getAsJsonObject();
        boolean found = false;
        for (var elem : root.getAsJsonArray("items")) {
            JsonObject item = elem.getAsJsonObject();
            if ("mimo-v2.5-pro".equals(item.get("id").getAsString())) {
                found = true;
                break;
            }
        }
        assertTrue("经 CSS 转发 setModelRegistry 后,自定义模型应在 getModelRegistryJson 中可见", found);
    }

    // ==================== helpers ====================

    private void useTemporaryHomeDirectory(Path tempHome) throws Exception {
        if (originalHomeDir == null) {
            originalHomeDir = getCachedHomeDirectory();
        }
        setCachedHomeDirectory(tempHome.toString());
        Files.createDirectories(tempHome.resolve(".codemoss"));
    }

    private String getCachedHomeDirectory() throws Exception {
        Field field = PlatformUtils.class.getDeclaredField("cachedRealHomeDir");
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private void setCachedHomeDirectory(String homeDir) throws Exception {
        Field field = PlatformUtils.class.getDeclaredField("cachedRealHomeDir");
        field.setAccessible(true);
        field.set(null, homeDir);
    }
}
