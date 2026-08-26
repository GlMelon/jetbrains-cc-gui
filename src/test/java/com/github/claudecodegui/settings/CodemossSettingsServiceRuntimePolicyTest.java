package com.github.claudecodegui.settings;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.session.runtime.ProviderType;
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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 路由策略读取测试。
 * <p>
 * runtime 维度已消除(SDK 调用模式已移除):策略仅剩 enabled 一维;
 * 存量 config.json 的 legacy claudeInvocationMode 键与 supported/default 字段
 * 由解析侧忽略(向后兼容)。
 */
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
    public void shouldIgnoreLegacyClaudeCliModeWhenRuntimePolicyIsMissing() throws Exception {
        // legacy claudeInvocationMode 键不再参与迁移:无 runtime 节点时直接回退默认策略。
        useTemporaryHomeDirectory();
        JsonObject config = new JsonObject();
        config.addProperty(LEGACY_CLAUDE_MODE_KEY, CommonConstants.INVOCATION_MODE_CLI);
        writeConfig(config);

        CodemossSettingsService service = new CodemossSettingsService();

        assertTrue(service.getRuntimePolicy().of(ProviderType.CLAUDE).enabled());
        assertTrue(service.getRuntimePolicy().of(ProviderType.CODEX).enabled());
    }

    @Test
    public void shouldParseEnabledAndIgnoreLegacyRuntimeFields() throws Exception {
        // 存量 config.json 的 legacy supported/default 字段被忽略,只读 enabled。
        useTemporaryHomeDirectory();
        JsonObject providers = new JsonObject();
        providers.add(ProviderType.CLAUDE.value(), legacyProviderPolicy(true));
        providers.add(ProviderType.CODEX.value(), legacyProviderPolicy(true));
        providers.add(ProviderType.OPENCODE.value(), legacyProviderPolicy(false));

        JsonObject runtime = new JsonObject();
        runtime.add("providers", providers);

        JsonObject config = new JsonObject();
        config.add("runtime", runtime);
        config.addProperty(LEGACY_CLAUDE_MODE_KEY, CommonConstants.INVOCATION_MODE_CLI);
        writeConfig(config);

        CodemossSettingsService service = new CodemossSettingsService();

        assertTrue(service.getRuntimePolicy().of(ProviderType.CLAUDE).enabled());
        assertTrue(service.getRuntimePolicy().of(ProviderType.CODEX).enabled());
        assertFalse("opencode 显式 enabled=false 应被保留",
                service.getRuntimePolicy().of(ProviderType.OPENCODE).enabled());
    }

    /** 模拟存量 config.json 的 provider 策略条目:enabled + legacy supported/default 字段。 */
    private JsonObject legacyProviderPolicy(boolean enabled) {
        JsonArray supported = new JsonArray();
        supported.add("CLI");

        JsonObject policy = new JsonObject();
        policy.addProperty("enabled", enabled);
        policy.add("supported", supported);
        policy.addProperty("default", "CLI");
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
