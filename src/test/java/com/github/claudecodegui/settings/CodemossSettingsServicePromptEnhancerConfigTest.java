package com.github.claudecodegui.settings;

import com.github.claudecodegui.config.ModelConfig;
import com.github.claudecodegui.config.ModelRegistryConfig;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CodemossSettingsServicePromptEnhancerConfigTest {
    private String originalHomeDir;

    @After
    public void tearDown() throws Exception {
        if (originalHomeDir != null) {
            setCachedHomeDirectory(originalHomeDir);
            originalHomeDir = null;
        }
    }

    @Test
    public void shouldDefaultToCodexWhenBothProvidersAreConfiguredAndInstalled() throws Exception {
        Path tempHome = Files.createTempDirectory("prompt-enhancer-default-codex-home");
        useTemporaryHomeDirectory(tempHome);
        writeConfig(tempHome, "claude-a", "codex-a");
        installSdk(tempHome, "claude-sdk", "@anthropic-ai/claude-agent-sdk", "0.2.88");
        installSdk(tempHome, "codex-sdk", "@openai/codex-sdk", "0.117.0");

        CodemossSettingsService service = new CodemossSettingsService();

        JsonObject config = invokeGetPromptEnhancerConfig(service);

        assertTrue(config.get("provider").isJsonNull());
        assertEquals("claude-role-sonnet", config.getAsJsonObject("models").get("claude").getAsString());
        assertEquals("", config.getAsJsonObject("models").get("codex").getAsString());
    }

    @Test
    public void shouldDefaultToClaudeWhenOnlyClaudeIsAvailable() throws Exception {
        Path tempHome = Files.createTempDirectory("prompt-enhancer-default-claude-home");
        useTemporaryHomeDirectory(tempHome);
        writeConfig(tempHome, "claude-a", "");
        installSdk(tempHome, "claude-sdk", "@anthropic-ai/claude-agent-sdk", "0.2.88");

        CodemossSettingsService service = new CodemossSettingsService();

        JsonObject config = invokeGetPromptEnhancerConfig(service);

        assertTrue(config.get("provider").isJsonNull());
        assertEquals("claude-role-sonnet", config.getAsJsonObject("models").get("claude").getAsString());
    }

    @Test
    public void shouldPersistManualProviderAndProviderSpecificModels() throws Exception {
        Path tempHome = Files.createTempDirectory("prompt-enhancer-manual-home");
        useTemporaryHomeDirectory(tempHome);
        writeConfig(tempHome, "claude-a", "codex-a");
        installSdk(tempHome, "claude-sdk", "@anthropic-ai/claude-agent-sdk", "0.2.88");
        installSdk(tempHome, "codex-sdk", "@openai/codex-sdk", "0.117.0");

        CodemossSettingsService service = new CodemossSettingsService();

        invokeSetPromptEnhancerConfig(service, "claude", "claude-role-opus", "provider-catalog-model");
        JsonObject config = invokeGetPromptEnhancerConfig(service);

        assertEquals("claude", config.get("provider").getAsString());
        assertEquals("claude-role-opus", config.getAsJsonObject("models").get("claude").getAsString());
        assertEquals("provider-catalog-model", config.getAsJsonObject("models").get("codex").getAsString());
    }

    @Test
    public void shouldReturnUnavailableWhenManualProviderIsNotAvailable() throws Exception {
        Path tempHome = Files.createTempDirectory("prompt-enhancer-unavailable-home");
        useTemporaryHomeDirectory(tempHome);
        writeConfig(tempHome, "", "");

        CodemossSettingsService service = new CodemossSettingsService();

        invokeSetPromptEnhancerConfig(service, "claude", "claude-role-opus", "provider-catalog-model");
        JsonObject config = invokeGetPromptEnhancerConfig(service);

        assertEquals("claude", config.get("provider").getAsString());
        assertTrue(config.get("effectiveProvider").isJsonNull());
        assertEquals("unavailable", config.get("resolutionSource").getAsString());
        assertFalse(config.getAsJsonObject("availability").get("claude").getAsBoolean());
        assertFalse(config.getAsJsonObject("availability").get("codex").getAsBoolean());
    }

    @Test
    public void shouldNormalizeLegacyCanonicalClaudeIdToRoleId() throws Exception {
        Path tempHome = Files.createTempDirectory("prompt-enhancer-canonical-home");
        useTemporaryHomeDirectory(tempHome);
        // 历史遗留:旧版本 promptEnhancer 持久化了 canonical id(claude-sonnet-4-6),而非 role id。
        // registry 内置 Claude 模型用 role id(claude-role-sonnet),两者 id 不一致会触发前端
        // AiFeatureProviderModelPanel 的兜底 prepend,在下拉里多出一个幽灵项。读取时应归一化成 role id。
        writePromptEnhancerConfig(tempHome, "claude", "claude-sonnet-4-6", "gpt-5.4");
        installSdk(tempHome, "claude-sdk", "@anthropic-ai/claude-agent-sdk", "0.2.88");

        CodemossSettingsService service = new CodemossSettingsService();

        JsonObject config = invokeGetPromptEnhancerConfig(service);

        assertEquals("claude", config.get("provider").getAsString());
        assertEquals("claude-role-sonnet", config.getAsJsonObject("models").get("claude").getAsString());
        assertEquals("gpt-5.4", config.getAsJsonObject("models").get("codex").getAsString());
    }

    @Test
    public void shouldKeepRoleIdUnchangedForClaudeModel() throws Exception {
        Path tempHome = Files.createTempDirectory("prompt-enhancer-role-id-home");
        useTemporaryHomeDirectory(tempHome);
        // role id 已与 registry id 体系一致,归一化不应改动它。
        writePromptEnhancerConfig(tempHome, "claude", "claude-role-opus", "gpt-5.4");
        installSdk(tempHome, "claude-sdk", "@anthropic-ai/claude-agent-sdk", "0.2.88");

        CodemossSettingsService service = new CodemossSettingsService();

        JsonObject config = invokeGetPromptEnhancerConfig(service);

        assertEquals("claude-role-opus", config.getAsJsonObject("models").get("claude").getAsString());
    }

    @Test
    public void shouldNormalizeCanonicalClaudeIdToRoleIdOnWrite() throws Exception {
        Path tempHome = Files.createTempDirectory("prompt-enhancer-write-canonical-home");
        useTemporaryHomeDirectory(tempHome);
        writeConfig(tempHome, "claude-a", "");
        installSdk(tempHome, "claude-sdk", "@anthropic-ai/claude-agent-sdk", "0.2.88");

        CodemossSettingsService service = new CodemossSettingsService();

        // 前端/外部传入 canonical id(claude-sonnet-4-6)写入。写入路径也应归一化为 role id,
        // 让 config.json 持久化的即为 role id(SSOT 干净),与读取路径归一化对称——避免任何绕过
        // getNormalizedAiFeatureModels 读取兜底的代码路径(直接读 config.json)拿到 canonical。
        invokeSetPromptEnhancerConfig(service, "claude", "claude-sonnet-4-6", "gpt-5.4");

        // 直接读 config.json(不经 getPromptEnhancerConfig 的读取归一化)验证写盘内容
        String rawConfig = Files.readString(tempHome.resolve(".codemoss").resolve("config.json"));
        JsonObject root = JsonParser.parseString(rawConfig).getAsJsonObject();
        String storedClaude = root.getAsJsonObject("promptEnhancer")
                .getAsJsonObject("models")
                .get("claude").getAsString();
        assertEquals("claude-role-sonnet", storedClaude);
    }

    @Test
    public void shouldNotNormalizeUserCustomClaudeModelRegisteredInRegistry() throws Exception {
        Path tempHome = Files.createTempDirectory("prompt-enhancer-custom-home");
        useTemporaryHomeDirectory(tempHome);
        // 用户自定义了一个与官方 canonical 同前缀(claude-sonnet-*)的模型,并已注册到 registry。
        // 安全网:它在 registry 中,绝不能被误归一化成 claude-role-sonnet。
        writePromptEnhancerConfig(tempHome, "claude", "claude-sonnet-myfinetune", "gpt-5.4");
        installSdk(tempHome, "claude-sdk", "@anthropic-ai/claude-agent-sdk", "0.2.88");

        CodemossSettingsService service = new CodemossSettingsService();
        ModelConfig customModel = new ModelConfig(
                "claude-sonnet-myfinetune",
                "claude",
                "sonnet",
                "My Finetune",
                "claude-sonnet-myfinetune",
                "user custom",
                200_000,
                false,
                true
        );
        service.setModelRegistry(new ModelRegistryConfig(List.of(customModel)));

        JsonObject config = invokeGetPromptEnhancerConfig(service);

        assertEquals("claude-sonnet-myfinetune", config.getAsJsonObject("models").get("claude").getAsString());
    }

    private JsonObject invokeGetPromptEnhancerConfig(CodemossSettingsService service) throws Exception {
        Method method;
        try {
            method = CodemossSettingsService.class.getMethod("getPromptEnhancerConfig");
        } catch (NoSuchMethodException e) {
            fail("CodemossSettingsService should expose getPromptEnhancerConfig()");
            throw e;
        }
        return (JsonObject) method.invoke(service);
    }

    private void invokeSetPromptEnhancerConfig(
            CodemossSettingsService service,
            String provider,
            String claudeModel,
            String codexModel
    ) throws Exception {
        Method method;
        try {
            method = CodemossSettingsService.class.getMethod(
                    "setPromptEnhancerConfig",
                    String.class,
                    String.class,
                    String.class
            );
        } catch (NoSuchMethodException e) {
            fail("CodemossSettingsService should expose setPromptEnhancerConfig(provider, claudeModel, codexModel)");
            throw e;
        }
        method.invoke(service, provider, claudeModel, codexModel);
    }

    private void useTemporaryHomeDirectory(Path tempHome) throws Exception {
        if (originalHomeDir == null) {
            originalHomeDir = getCachedHomeDirectory();
        }
        setCachedHomeDirectory(tempHome.toString());
        Files.createDirectories(tempHome.resolve(".codemoss"));
    }

    private void writeConfig(Path tempHome, String currentClaude, String currentCodex) throws Exception {
        Files.writeString(
                tempHome.resolve(".codemoss").resolve("config.json"),
                buildBaseConfig(currentClaude, currentCodex).toString()
        );
    }

    /**
     * 写入带 promptEnhancer 块的 config.json(基础 provider 配置 + 指定的 promptEnhancer 模型记忆)。
     * 用于测试历史遗留 canonical id 的归一化等场景。
     */
    private void writePromptEnhancerConfig(Path tempHome, String provider, String claudeModel, String codexModel) throws Exception {
        JsonObject config = buildBaseConfig("claude-a", "");
        JsonObject promptEnhancer = new JsonObject();
        promptEnhancer.addProperty("provider", provider);
        JsonObject models = new JsonObject();
        models.addProperty("claude", claudeModel);
        models.addProperty("codex", codexModel);
        promptEnhancer.add("models", models);
        config.add("promptEnhancer", promptEnhancer);
        Files.writeString(
                tempHome.resolve(".codemoss").resolve("config.json"),
                config.toString()
        );
    }

    private JsonObject buildBaseConfig(String currentClaude, String currentCodex) {
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

        return config;
    }

    private void installSdk(Path tempHome, String sdkId, String npmPackage, String version) throws Exception {
        Path packageDir = tempHome.resolve(".codemoss")
                .resolve("dependencies")
                .resolve(sdkId)
                .resolve("node_modules");

        for (String segment : npmPackage.split("/")) {
            packageDir = packageDir.resolve(segment);
        }

        Files.createDirectories(packageDir);
        JsonObject pkgJson = new JsonObject();
        pkgJson.addProperty("name", npmPackage);
        pkgJson.addProperty("version", version);
        Files.writeString(packageDir.resolve("package.json"), pkgJson.toString());
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
