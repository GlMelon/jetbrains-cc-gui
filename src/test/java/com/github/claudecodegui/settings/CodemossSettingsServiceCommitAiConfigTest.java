package com.github.claudecodegui.settings;

import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CodemossSettingsServiceCommitAiConfigTest {
    private String originalHomeDir;

    @After
    public void tearDown() throws Exception {
        if (originalHomeDir != null) {
            setCachedHomeDirectory(originalHomeDir);
            originalHomeDir = null;
        }
    }

    @Test
    public void shouldDefaultCommitAiToCodexWhenBothProvidersAreConfigured() throws Exception {
        Path tempHome = Files.createTempDirectory("commit-ai-default-codex-home");
        useTemporaryHomeDirectory(tempHome);
        writeConfig(tempHome, "claude-a", "codex-a");

        CodemossSettingsService service = new CodemossSettingsService();

        JsonObject config = invokeGetCommitAiConfig(service);

        assertTrue(config.get("provider").isJsonNull());
        assertEquals("claude-role-sonnet", config.getAsJsonObject("models").get("claude").getAsString());
        assertEquals("", config.getAsJsonObject("models").get("codex").getAsString());
    }

    @Test
    public void shouldDefaultCommitAiToClaudeWhenOnlyClaudeIsAvailable() throws Exception {
        Path tempHome = Files.createTempDirectory("commit-ai-default-claude-home");
        useTemporaryHomeDirectory(tempHome);
        writeConfig(tempHome, "claude-a", "");

        CodemossSettingsService service = new CodemossSettingsService();

        JsonObject config = invokeGetCommitAiConfig(service);

        assertTrue(config.get("provider").isJsonNull());
        assertEquals("claude-role-sonnet", config.getAsJsonObject("models").get("claude").getAsString());
    }

    @Test
    public void shouldPersistManualCommitAiProviderAndModels() throws Exception {
        Path tempHome = Files.createTempDirectory("commit-ai-manual-home");
        useTemporaryHomeDirectory(tempHome);
        writeConfig(tempHome, "claude-a", "codex-a");

        CodemossSettingsService service = new CodemossSettingsService();

        invokeSetCommitAiConfig(service, "claude", "claude-role-opus", "provider-catalog-model");
        JsonObject config = invokeGetCommitAiConfig(service);

        assertEquals("claude", config.get("provider").getAsString());
        assertEquals("claude-role-opus", config.getAsJsonObject("models").get("claude").getAsString());
        assertEquals("provider-catalog-model", config.getAsJsonObject("models").get("codex").getAsString());
    }

    @Test
    public void shouldKeepManualCommitAiProviderWhenUnavailable() throws Exception {
        Path tempHome = Files.createTempDirectory("commit-ai-unavailable-home");
        useTemporaryHomeDirectory(tempHome);
        writeConfig(tempHome, "", "");

        CodemossSettingsService service = new CodemossSettingsService();

        invokeSetCommitAiConfig(service, "claude", "claude-role-opus", "provider-catalog-model");
        JsonObject config = invokeGetCommitAiConfig(service);

        assertEquals("claude", config.get("provider").getAsString());
        assertTrue(config.get("effectiveProvider").isJsonNull());
        assertEquals("unavailable", config.get("resolutionSource").getAsString());
        assertFalse(config.getAsJsonObject("availability").get("claude").getAsBoolean());
        assertFalse(config.getAsJsonObject("availability").get("codex").getAsBoolean());
    }

    @Test
    public void shouldNotMutatePromptEnhancerConfigWhenSavingCommitAiConfig() throws Exception {
        Path tempHome = Files.createTempDirectory("commit-ai-isolated-home");
        useTemporaryHomeDirectory(tempHome);
        writeConfig(tempHome, "claude-a", "codex-a");

        CodemossSettingsService service = new CodemossSettingsService();
        invokeSetPromptEnhancerConfig(service, "claude", "claude-role-opus", "prompt-codex-model");

        invokeSetCommitAiConfig(service, "codex", "claude-role-fable", "commit-codex-model");

        JsonObject promptEnhancerConfig = invokeGetPromptEnhancerConfig(service);
        JsonObject commitAiConfig = invokeGetCommitAiConfig(service);

        assertEquals("claude", promptEnhancerConfig.get("provider").getAsString());
        assertEquals("claude-role-opus", promptEnhancerConfig.getAsJsonObject("models").get("claude").getAsString());
        assertEquals("prompt-codex-model", promptEnhancerConfig.getAsJsonObject("models").get("codex").getAsString());

        assertEquals("codex", commitAiConfig.get("provider").getAsString());
        assertEquals("commit-codex-model", commitAiConfig.getAsJsonObject("models").get("codex").getAsString());
        assertEquals("claude-role-fable", commitAiConfig.getAsJsonObject("models").get("claude").getAsString());
    }

    private JsonObject invokeGetCommitAiConfig(CodemossSettingsService service) throws Exception {
        Method method;
        try {
            method = CodemossSettingsService.class.getMethod("getCommitAiConfig");
        } catch (NoSuchMethodException e) {
            fail("CodemossSettingsService should expose getCommitAiConfig()");
            throw e;
        }
        return (JsonObject) method.invoke(service);
    }

    private void invokeSetCommitAiConfig(
            CodemossSettingsService service,
            String provider,
            String claudeModel,
            String codexModel
    ) throws Exception {
        invokeSetCommitAiConfig(service, provider, claudeModel, codexModel, "");
    }

    private void invokeSetCommitAiConfig(
            CodemossSettingsService service,
            String provider,
            String claudeModel,
            String codexModel,
            String opencodeModel
    ) throws Exception {
        Method method;
        try {
            method = CodemossSettingsService.class.getMethod(
                    "setCommitAiConfig",
                    String.class,
                    String.class,
                    String.class,
                    String.class
            );
        } catch (NoSuchMethodException e) {
            fail("CodemossSettingsService should expose setCommitAiConfig(provider, claudeModel, codexModel, opencodeModel)");
            throw e;
        }
        method.invoke(service, provider, claudeModel, codexModel, opencodeModel);
    }

    private JsonObject invokeGetPromptEnhancerConfig(CodemossSettingsService service) throws Exception {
        Method method = CodemossSettingsService.class.getMethod("getPromptEnhancerConfig");
        return (JsonObject) method.invoke(service);
    }

    private void invokeSetPromptEnhancerConfig(
            CodemossSettingsService service,
            String provider,
            String claudeModel,
            String codexModel
    ) throws Exception {
        Method method = CodemossSettingsService.class.getMethod(
                "setPromptEnhancerConfig",
                String.class,
                String.class,
                String.class,
                String.class
        );
        method.invoke(service, provider, claudeModel, codexModel, "");
    }

    private void useTemporaryHomeDirectory(Path tempHome) throws Exception {
        if (originalHomeDir == null) {
            originalHomeDir = getCachedHomeDirectory();
        }
        setCachedHomeDirectory(tempHome.toString());
        Files.createDirectories(tempHome.resolve(".codemoss"));
    }

    private void writeConfig(Path tempHome, String currentClaude, String currentCodex) throws Exception {
        JsonObject config = new JsonObject();

        JsonObject claude = new JsonObject();
        claude.addProperty("current", currentClaude);
        JsonObject claudeProviders = new JsonObject();
        if (currentClaude != null && !currentClaude.isEmpty()) {
            JsonObject provider = new JsonObject();
            provider.addProperty("name", "Claude A");
            provider.add("settingsConfig", new JsonObject());
            claudeProviders.add(currentClaude, provider);
        }
        claude.add("providers", claudeProviders);
        config.add("claude", claude);

        JsonObject codex = new JsonObject();
        codex.addProperty("current", currentCodex);
        codex.addProperty("localConfigAuthorized", false);
        JsonObject codexProviders = new JsonObject();
        if (currentCodex != null && !currentCodex.isEmpty()) {
            JsonObject provider = new JsonObject();
            provider.addProperty("name", "Codex A");
            provider.add("configToml", new JsonObject());
            provider.add("authJson", new JsonObject());
            codexProviders.add(currentCodex, provider);
        }
        codex.add("providers", codexProviders);
        config.add("codex", codex);

        Files.writeString(
                tempHome.resolve(".codemoss").resolve("config.json"),
                config.toString()
        );
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
