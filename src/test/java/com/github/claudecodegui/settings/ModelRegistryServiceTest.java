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
import static org.junit.Assert.assertTrue;

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

        ModelRegistryResult result = service.setRegistry(payload);

        assertFalse(result.success());
        assertFalse(result.errors().isEmpty());
    }

    @Test
    public void resetRegistryReturnsResetSuccess() throws Exception {
        useTemporaryHomeDirectory(Files.createTempDirectory("mrs-reset-home"));
        ModelRegistryService service = new ModelRegistryService(new CodemossSettingsService());

        ModelRegistryResult result = service.resetRegistry();

        assertTrue(result.success());
        assertTrue(result.reset());
        assertTrue(result.registry().getAsJsonArray("items").size() > 0);
    }

    @Test
    public void defaultSchemaHasExpectedFields() {
        JsonObject schema = ModelRegistrySchemaResult.defaultSchema().schema();
        assertEquals("模型配置中心", schema.get("title").getAsString());
        assertTrue(schema.has("providers"));
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
