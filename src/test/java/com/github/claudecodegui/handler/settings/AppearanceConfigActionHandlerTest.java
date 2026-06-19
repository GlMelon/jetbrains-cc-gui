package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.settings.AppearanceConfigService;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AppearanceConfigActionHandlerTest {
    private String originalHome;
    private List<String[]> dispatched;
    private FrontendActionContext context;
    private AppearanceConfigService service;

    @Before
    public void setUp() throws Exception {
        originalHome = SettingsHandlerTestFixtures.captureHome();
        SettingsHandlerTestFixtures.useTempHome(
                Files.createTempDirectory("appearance-handler-home"));
        dispatched = new ArrayList<>();
        CodemossSettingsService settingsService = new CodemossSettingsService();
        context = SettingsHandlerTestFixtures.recordingContext(settingsService, dispatched);
        service = new AppearanceConfigService(settingsService);
    }

    @After
    public void tearDown() throws Exception {
        SettingsHandlerTestFixtures.restoreHome(originalHome);
    }

    @Test
    public void setAppearanceConfigDispatchesApplyEvent() {
        JsonObject payload = new JsonObject();
        payload.addProperty("themePreference", "dark");
        payload.addProperty("fontSizeLevel", 2);

        new SetAppearanceConfigActionHandler(service).handle(payload.toString(), context);

        assertEquals(1, dispatched.size());
        assertEquals(DownstreamEvent.APPEARANCE_APPLY.value(), dispatched.get(0)[0]);
        JsonObject applied = JsonParser.parseString(dispatched.get(0)[1]).getAsJsonObject();
        assertEquals("dark", applied.get("themePreference").getAsString());
    }

    @Test
    public void declaresCorrectAction() {
        assertEquals(UpstreamAction.SET_APPEARANCE_CONFIG,
                new SetAppearanceConfigActionHandler(service).action());
    }
}
