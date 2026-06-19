package com.github.claudecodegui.settings;

import com.github.claudecodegui.config.ModelConfig;
import com.github.claudecodegui.config.ModelRegistryConfig;
import com.github.claudecodegui.util.PlatformUtils;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CodemossSettingsServiceModelRegistryTest {
    private String originalHomeDir;

    @After
    public void tearDown() throws Exception {
        if (originalHomeDir != null) {
            setCachedHomeDirectory(originalHomeDir);
            originalHomeDir = null;
        }
    }

    @Test
    public void returnsDefaultModelRegistryWhenConfigIsMissing() throws Exception {
        useTemporaryHomeDirectory(Files.createTempDirectory("model-registry-default-home"));

        ModelRegistryConfig config = new CodemossSettingsService().getModelRegistry();

        assertTrue(config.models().stream().anyMatch(model -> model.id().equals("claude-role-sonnet")));
        assertFalse(config.models().stream().anyMatch(model -> model.provider().equals("codex")));
    }

    @Test
    public void rejectsInvalidModelRegistryWithoutPersistingIt() throws Exception {
        useTemporaryHomeDirectory(Files.createTempDirectory("model-registry-invalid-home"));
        CodemossSettingsService service = new CodemossSettingsService();

        ModelRegistryConfig invalid = new ModelRegistryConfig(List.of(
                new ModelConfig("bad", "codex", "", "Bad", "", "", 200_000, true, true)
        ));

        assertFalse(service.setModelRegistry(invalid).isValid());
        assertTrue(service.getModelRegistry().models().stream().anyMatch(model -> model.id().equals("claude-role-sonnet")));
        assertFalse(service.getModelRegistry().models().stream().anyMatch(model -> model.provider().equals("codex")));
    }

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

    @Test
    public void resolvesClaudeRegistryActualModelFromSelectedRole() {
        ModelRegistryConfig config = new ModelRegistryConfig(List.of(
                new ModelConfig("claude-role-sonnet", "claude", "sonnet", "GLM 5.2",
                        "glm5.2", "", 1_000_000, true, true)
        ));

        ModelRegistryConfig.ResolvedModelSelection resolved =
                config.resolveModelSelection("claude", "claude-role-sonnet[1m]");

        assertEquals("claude-role-sonnet[1m]", resolved.selectedModel());
        assertEquals("sonnet", resolved.role());
        assertEquals("glm5.2[1m]", resolved.actualModel());
        assertEquals(1_000_000, resolved.contextWindow());
        assertTrue(resolved.supports1MContext());
    }

    @Test
    public void resolvesSelectedClaudeRegistryModelWithSharedRole() {
        ModelRegistryConfig config = new ModelRegistryConfig(List.of(
                new ModelConfig("mimo-v2.5", "claude", "sonnet", "MiMo V2.5",
                        "mimo-v2.5", "", 1_000_000, true, true),
                new ModelConfig("mimo-v2.5-pro", "claude", "sonnet", "MiMo V2.5 Pro",
                        "mimo-v2.5-pro", "", 1_000_000, true, true)
        ));

        ModelRegistryConfig.ResolvedModelSelection resolved =
                config.resolveModelSelection("claude", "mimo-v2.5-pro");

        assertEquals("mimo-v2.5-pro", resolved.selectedModel());
        assertEquals("sonnet", resolved.role());
        assertEquals("mimo-v2.5-pro", resolved.actualModel());
        assertEquals(1_000_000, resolved.contextWindow());
        assertTrue(resolved.supports1MContext());
    }

    @Test
    public void resolvesCodexRegistryIdAsActualModel() {
        ModelRegistryConfig config = new ModelRegistryConfig(List.of(
                new ModelConfig("glm5.2", "codex", "", "GLM 5.2", "", "", 1_000_000, true, true)
        ));

        ModelRegistryConfig.ResolvedModelSelection resolved =
                config.resolveModelSelection("codex", "glm5.2");

        assertEquals("glm5.2", resolved.selectedModel());
        assertEquals(null, resolved.role());
        assertEquals("glm5.2", resolved.actualModel());
    }

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
