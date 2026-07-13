package com.github.claudecodegui.settings;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.session.runtime.RuntimeType;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CodemossSettingsServiceRuntimePolicyTest {
    private static final String LEGACY_CLAUDE_MODE_KEY = "claudeInvocationMode";

    private String originalHomeDir;
    private Path temporaryHome;

    @After
    public void tearDown() throws Exception {
        if (originalHomeDir != null) {
            setCachedHomeDirectory(originalHomeDir);
            originalHomeDir = null;
        }
        temporaryHome = null;
    }

    @Test
    public void shouldExposeClaudeCliModeFromRuntimePolicy() throws Exception {
        useTemporaryHomeDirectory();
        writeConfig(configWithRuntime(RuntimeType.CLI, RuntimeType.SDK, RuntimeType.SDK));

        CodemossSettingsService service = new CodemossSettingsService();

        assertEquals(CommonConstants.INVOCATION_MODE_CLI, service.getClaudeInvocationMode());
    }

    @Test
    public void shouldPersistClaudeModeOnlyInRuntimePolicy() throws Exception {
        useTemporaryHomeDirectory();
        JsonObject config = configWithRuntime(RuntimeType.SDK, RuntimeType.SDK, RuntimeType.SDK);
        config.addProperty(LEGACY_CLAUDE_MODE_KEY, CommonConstants.INVOCATION_MODE_SDK);
        writeConfig(config);

        CodemossSettingsService service = new CodemossSettingsService();
        service.setClaudeInvocationMode(CommonConstants.INVOCATION_MODE_CLI);

        JsonObject persisted = readConfig();
        assertFalse(persisted.has(LEGACY_CLAUDE_MODE_KEY));
        assertEquals(
                RuntimeType.CLI.name(),
                persisted.getAsJsonObject("runtime")
                        .getAsJsonObject("providers")
                        .getAsJsonObject(ProviderType.CLAUDE.value())
                        .get("default")
                        .getAsString()
        );
    }

    @Test
    public void shouldMigrateLegacyClaudeCliModeWhenRuntimePolicyIsMissing() throws Exception {
        useTemporaryHomeDirectory();
        JsonObject config = new JsonObject();
        config.addProperty(LEGACY_CLAUDE_MODE_KEY, CommonConstants.INVOCATION_MODE_CLI);
        writeConfig(config);

        CodemossSettingsService service = new CodemossSettingsService();

        assertEquals(RuntimeType.CLI, service.getRuntimePolicy().of(ProviderType.CLAUDE).defaultRuntime());
    }

    @Test
    public void shouldPreferRuntimePolicyOverLegacyClaudeMode() throws Exception {
        useTemporaryHomeDirectory();
        JsonObject config = configWithRuntime(RuntimeType.SDK, RuntimeType.CLI, RuntimeType.CLI);
        config.addProperty(LEGACY_CLAUDE_MODE_KEY, CommonConstants.INVOCATION_MODE_CLI);
        writeConfig(config);

        CodemossSettingsService service = new CodemossSettingsService();

        assertEquals(RuntimeType.SDK, service.getRuntimePolicy().of(ProviderType.CLAUDE).defaultRuntime());
        assertEquals(RuntimeType.CLI, service.getRuntimePolicy().of(ProviderType.CODEX).defaultRuntime());
        assertEquals(RuntimeType.CLI, service.getRuntimePolicy().of(ProviderType.OPENCODE).defaultRuntime());
    }

    private JsonObject configWithRuntime(
            RuntimeType claudeRuntime,
            RuntimeType codexRuntime,
            RuntimeType openCodeRuntime
    ) {
        JsonObject providers = new JsonObject();
        providers.add(ProviderType.CLAUDE.value(), providerPolicy(claudeRuntime));
        providers.add(ProviderType.CODEX.value(), providerPolicy(codexRuntime));
        providers.add(ProviderType.OPENCODE.value(), providerPolicy(openCodeRuntime));

        JsonObject runtime = new JsonObject();
        runtime.add("providers", providers);

        JsonObject config = new JsonObject();
        config.add("runtime", runtime);
        return config;
    }

    private JsonObject providerPolicy(RuntimeType defaultRuntime) {
        JsonArray supported = new JsonArray();
        supported.add(RuntimeType.SDK.name());
        supported.add(RuntimeType.CLI.name());

        JsonObject policy = new JsonObject();
        policy.addProperty("enabled", true);
        policy.add("supported", supported);
        policy.addProperty("default", defaultRuntime.name());
        return policy;
    }

    private void useTemporaryHomeDirectory() throws Exception {
        temporaryHome = Files.createTempDirectory("runtime-policy-home");
        if (originalHomeDir == null) {
            originalHomeDir = getCachedHomeDirectory();
        }
        setCachedHomeDirectory(temporaryHome.toString());
        Files.createDirectories(temporaryHome.resolve(".codemoss"));
    }

    private void writeConfig(JsonObject config) throws Exception {
        Files.writeString(configPath(), new Gson().toJson(config), StandardCharsets.UTF_8);
    }

    private JsonObject readConfig() throws Exception {
        assertTrue(Files.exists(configPath()));
        return new Gson().fromJson(Files.readString(configPath(), StandardCharsets.UTF_8), JsonObject.class);
    }

    private Path configPath() {
        return temporaryHome.resolve(".codemoss").resolve("config.json");
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
