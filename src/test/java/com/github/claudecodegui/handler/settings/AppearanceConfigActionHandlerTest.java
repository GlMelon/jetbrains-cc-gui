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

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AppearanceConfigActionHandlerTest {
    private String originalHome;
    private List<String[]> dispatched;
    private FrontendActionContext context;
    private AppearanceConfigService service;
    private RecordingSettingsService settingsService;

    @Before
    public void setUp() throws Exception {
        originalHome = SettingsHandlerTestFixtures.captureHome();
        SettingsHandlerTestFixtures.useTempHome(
                Files.createTempDirectory("appearance-handler-home"));
        dispatched = new ArrayList<>();
        settingsService = new RecordingSettingsService();
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
    public void setAppearanceConfigMalformedJsonRoutesThroughCatchAndSkipsPersistence() {
        // 非 object 的合法 JSON([1,2])让 fromJson 抛 JsonSyntaxException,在 service try 内被 catch。
        // catch 块 LOG.error 在测试环境(无 Application)经 DefaultLogger 抛 AssertionError(cause=原
        // JsonSyntaxException;与 ProjectConfigHandlerCodeFontConfigTest 同;原 SettingsHandler catch 块
        // 同样 LOG.error,行为一致)。双重断言:(1) catch(AssertionError) + cause=JsonSyntaxException 直接
        // 证明 fromJson 在 try 内被 catch——若移到 try 外(回归 bug),抛纯 JsonSyntaxException 致
        // catch(AssertionError) 落空、测试失败;(2) setAppearanceConfig 未调用锁住持久化安全不变量。
        try {
            new SetAppearanceConfigActionHandler(service).handle("[1,2]", context);
            fail("expected AssertionError from LOG.error in test mode");
        } catch (AssertionError ae) {
            assertNotNull("AssertionError should wrap the original parse exception", ae.getCause());
            assertTrue("cause should be JsonSyntaxException",
                    ae.getCause() instanceof com.google.gson.JsonSyntaxException);
        }
        assertFalse("malformed payload must not reach setAppearanceConfig",
                settingsService.setAppearanceConfigCalled);
    }

    @Test
    public void declaresCorrectAction() {
        assertEquals(UpstreamAction.SET_APPEARANCE_CONFIG,
                new SetAppearanceConfigActionHandler(service).action());
    }

    /** 捕获 setAppearanceConfig 是否被调用,用于断言坏 payload 不污染持久化层。 */
    private static class RecordingSettingsService extends CodemossSettingsService {
        private boolean setAppearanceConfigCalled = false;

        @Override
        public void setAppearanceConfig(JsonObject rawConfig) throws IOException {
            setAppearanceConfigCalled = true;
            super.setAppearanceConfig(rawConfig);
        }
    }
}
