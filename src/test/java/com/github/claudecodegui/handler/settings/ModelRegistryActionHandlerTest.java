package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.settings.ModelRegistryService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModelRegistryActionHandlerTest {
    private String originalHome;
    private List<String[]> dispatched;
    private FrontendActionContext context;
    private ModelRegistryService service;

    @Before
    public void setUp() throws Exception {
        originalHome = SettingsHandlerTestFixtures.captureHome();
        SettingsHandlerTestFixtures.useTempHome(
                Files.createTempDirectory("model-registry-handler-home"));
        dispatched = new ArrayList<>();
        CodemossSettingsService settingsService = new CodemossSettingsService();
        context = SettingsHandlerTestFixtures.recordingContext(settingsService, dispatched);
        service = new ModelRegistryService(settingsService);
    }

    @After
    public void tearDown() throws Exception {
        SettingsHandlerTestFixtures.restoreHome(originalHome);
    }

    @Test
    public void getModelRegistryDispatchesRegistryEvent() {
        new GetModelRegistryActionHandler(service).handle("", context);

        assertEquals(1, dispatched.size());
        assertEquals(DownstreamEvent.MODEL_REGISTRY.value(), dispatched.get(0)[0]);
        JsonObject payload = JsonParser.parseString(dispatched.get(0)[1]).getAsJsonObject();
        assertTrue(payload.getAsJsonArray("items").size() > 0);
    }

    @Test
    public void setModelRegistryOnSuccessDispatchesUpdatedThenRegistry() {
        JsonObject payload = ModelRegistryService.serialize(new com.github.claudecodegui.config.ModelRegistryConfig(
                java.util.List.of(new com.github.claudecodegui.config.ModelConfig(
                        "mimo-v2.5", "claude", "opus", "Mimo V2.5 Pro", "mimo-v2.5-pro",
                        "", 1_000_000, true, true))
        ));

        new SetModelRegistryActionHandler(service).handle(payload.toString(), context);

        assertEquals(2, dispatched.size());
        assertEquals(DownstreamEvent.MODEL_REGISTRY_UPDATED.value(), dispatched.get(0)[0]);
        JsonObject updated = JsonParser.parseString(dispatched.get(0)[1]).getAsJsonObject();
        assertTrue(updated.get("success").getAsBoolean());
        assertTrue(updated.has("registry"));
        assertEquals(DownstreamEvent.MODEL_REGISTRY.value(), dispatched.get(1)[0]);
    }

    @Test
    public void setModelRegistryOnConflictDispatchesUpdatedWithError() {
        JsonObject payload = ModelRegistryService.serialize(new com.github.claudecodegui.config.ModelRegistryConfig(
                java.util.List.of(new com.github.claudecodegui.config.ModelConfig(
                        "claude-role-sonnet", "claude", "sonnet", "Hacked", "evil",
                        "", 200_000, true, true))
        ));

        new SetModelRegistryActionHandler(service).handle(payload.toString(), context);

        assertEquals(1, dispatched.size());
        assertEquals(DownstreamEvent.MODEL_REGISTRY_UPDATED.value(), dispatched.get(0)[0]);
        JsonObject updated = JsonParser.parseString(dispatched.get(0)[1]).getAsJsonObject();
        assertFalse(updated.get("success").getAsBoolean());
        assertTrue(updated.getAsJsonArray("errors").size() > 0);
    }

    @Test
    public void getModelRegistrySchemaDispatchesSchemaEvent() {
        new GetModelRegistrySchemaActionHandler(service).handle("", context);

        assertEquals(1, dispatched.size());
        assertEquals(DownstreamEvent.MODEL_REGISTRY_SCHEMA.value(), dispatched.get(0)[0]);
        JsonObject schema = JsonParser.parseString(dispatched.get(0)[1]).getAsJsonObject();
        assertEquals("模型配置中心", schema.get("title").getAsString());
    }

    @Test
    public void handlersDeclareCorrectActions() {
        assertEquals(UpstreamAction.GET_MODEL_REGISTRY, new GetModelRegistryActionHandler(service).action());
        assertEquals(UpstreamAction.SET_MODEL_REGISTRY, new SetModelRegistryActionHandler(service).action());
        assertEquals(UpstreamAction.GET_MODEL_REGISTRY_SCHEMA, new GetModelRegistrySchemaActionHandler(service).action());
    }
}
