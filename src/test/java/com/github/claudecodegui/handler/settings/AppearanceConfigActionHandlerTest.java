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

    // 注:无法为「fromJson 畸形/类型不符 JSON → catch(Exception) 后仍回读并派发 apply」编写可靠单测。
    // Gradle test 默认 -ea,Gson 对解析失败抛 AssertionError(extends Error)而 catch(Exception) 捕获不到,
    // 与原 SettingsHandler 完全相同。生产(无 -ea)下抛 JsonSyntaxException 被捕获。等价性由 fromJson
    // 下沉到 service try 内的结构保证(与原代码 handleSetAppearanceConfig 同构)。

    @Test
    public void declaresCorrectAction() {
        assertEquals(UpstreamAction.SET_APPEARANCE_CONFIG,
                new SetAppearanceConfigActionHandler(service).action());
    }
}
