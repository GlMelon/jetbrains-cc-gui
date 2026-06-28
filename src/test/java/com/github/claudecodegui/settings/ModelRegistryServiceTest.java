package com.github.claudecodegui.settings;

import com.github.claudecodegui.config.ModelConfig;
import com.github.claudecodegui.config.ModelConfigValidator;
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
import static org.junit.Assert.fail;

public class ModelRegistryServiceTest {
    private String originalHomeDir;

    @After
    public void tearDown() throws Exception {
        if (originalHomeDir != null) {
            setCachedHomeDirectory(originalHomeDir);
            originalHomeDir = null;
        }
    }

    @Test
    public void serializeAndParseRoundTrip() {
        ModelRegistryConfig config = new ModelRegistryConfig(List.of(
                new ModelConfig("mimo-v2.5", "claude", "sonnet", "MiMo V2.5", "mimo-v2.5",
                        "", 1_000_000, true, true)
        ));
        JsonObject serialized = ModelRegistryService.serialize(config);

        assertEquals(1, serialized.getAsJsonArray("items").size());

        ModelRegistryConfig parsed = ModelRegistryService.parse(serialized);
        assertEquals("mimo-v2.5", parsed.models().get(0).id());
        assertEquals(1_000_000, parsed.models().get(0).contextWindow());
        assertTrue(parsed.models().get(0).supports1MContext());
    }

    @Test
    public void parseNullActualModelToleratedAsEmpty() {
        JsonObject item = JsonParser.parseString(
                "{\"id\":\"x\",\"provider\":\"claude\",\"role\":\"sonnet\","
                        + "\"label\":\"X\",\"actualModel\":null,\"description\":\"\","
                        + "\"contextWindow\":200000,\"supports1MContext\":false,\"enabled\":true}"
        ).getAsJsonObject();
        JsonObject root = new JsonObject();
        root.add("items", new com.google.gson.JsonArray());
        root.getAsJsonArray("items").add(item);

        ModelRegistryConfig parsed = ModelRegistryService.parse(root);
        assertEquals("", parsed.models().get(0).actualModel());
    }

    @Test
    public void getRegistryReturnsSuccessWithDefaults() throws Exception {
        useTemporaryHomeDirectory(Files.createTempDirectory("mrs-get-home"));
        ModelRegistryService service = new ModelRegistryService(new CodemossSettingsService());

        ModelRegistryResult result = service.getRegistry();

        assertTrue(result.success());
        assertTrue(result.registry().getAsJsonArray("items").size() > 0);
    }

    @Test
    public void setRegistryRejectsConflict() throws Exception {
        useTemporaryHomeDirectory(Files.createTempDirectory("mrs-conflict-home"));
        ModelRegistryService service = new ModelRegistryService(new CodemossSettingsService());
        JsonObject payload = ModelRegistryService.serialize(new ModelRegistryConfig(List.of(
                new ModelConfig("claude-role-sonnet", "claude", "sonnet", "Hacked",
                        "evil", "", 200_000, true, true)
        )));

        ModelRegistryResult result = service.setRegistry(payload.toString());

        assertFalse(result.success());
        assertFalse(result.errors().isEmpty());
    }

    @Test
    public void setRegistryMalformedJsonRoutesThroughCatchAndSkipsPersistence() throws Exception {
        useTemporaryHomeDirectory(Files.createTempDirectory("mrs-malformed-home"));
        RecordingSettingsService recording = new RecordingSettingsService();
        ModelRegistryService service = new ModelRegistryService(recording);

        // 非 object 的合法 JSON([1,2])让 fromJson 抛 JsonSyntaxException,在 service 的 try 内被 catch。
        // catch 块 LOG.error 在测试环境(无 Application)经 DefaultLogger 抛 AssertionError(cause=原
        // JsonSyntaxException;与 ProjectConfigHandlerCodeFontConfigTest 同;原 SettingsHandler catch 块
        // 同样 LOG.error,行为完全一致)。双重断言:(1) catch(AssertionError) + cause=JsonSyntaxException
        // 直接证明 fromJson 在 try 内且被 catch 捕获——若 fromJson 被移到 try 外(回归 b3cd6c31 之前的
        // bug),抛的是纯 JsonSyntaxException(无 AssertionError 包装),catch(AssertionError) 落空致测试
        // 失败;(2) setModelRegistry 未调用锁住持久化安全不变量。
        try {
            service.setRegistry("[1,2]");
            fail("expected AssertionError from LOG.error in test mode");
        } catch (AssertionError ae) {
            assertNotNull("AssertionError should wrap the original parse exception", ae.getCause());
            assertTrue("cause should be JsonSyntaxException",
                    ae.getCause() instanceof com.google.gson.JsonSyntaxException);
        }
        assertFalse("malformed payload must not reach setModelRegistry", recording.setModelRegistryCalled);
    }

    @Test
    public void defaultSchemaHasExpectedFields() {
        JsonObject schema = ModelRegistrySchemaResult.defaultSchema().schema();
        assertEquals("模型配置中心", schema.get("title").getAsString());
        assertTrue(schema.has("providers"));
    }

    /** 捕获 setModelRegistry 是否被调用,用于断言坏 payload 不污染持久化层。 */
    private static class RecordingSettingsService extends CodemossSettingsService {
        private boolean setModelRegistryCalled = false;

        @Override
        public ModelConfigValidator.ValidationResult setModelRegistry(ModelRegistryConfig registry) {
            setModelRegistryCalled = true;
            return super.setModelRegistry(registry);
        }
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
