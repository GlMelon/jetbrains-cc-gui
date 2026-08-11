package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.util.PlatformUtils;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;

/**
 * Shared test fixtures for settings handler tests:
 * - temporary home directory (so {@code new CodemossSettingsService()} works in plain JUnit);
 * - a {@link FrontendActionContext} whose {@code dispatchEvent} records every call.
 */
final class SettingsHandlerTestFixtures {

    private SettingsHandlerTestFixtures() {
    }

    /** Capture the current cached home dir so it can be restored in @After. */
    static String captureHome() throws Exception {
        return (String) homeField().get(null);
    }

    /** Point the cached home dir at {@code tempHome} and ensure ~/.codemoss exists. */
    static void useTempHome(Path tempHome) throws Exception {
        homeField().set(null, tempHome.toString());
        java.nio.file.Files.createDirectories(tempHome.resolve(".codemoss"));
    }

    static void restoreHome(String original) throws Exception {
        homeField().set(null, original);
    }

    /**
     * Build a {@link FrontendActionContext} that records each {@code dispatchEvent(type, payload)}
     * into {@code sink} as a {@code String[]{type, payload}}. {@code escapeJs} is a passthrough so
     * tests can assert payloads as plain JSON.
     */
    static FrontendActionContext recordingContext(CodemossSettingsService service,
                                                  List<String[]> sink) {
        HandlerContext.JsCallback cb = new HandlerContext.JsCallback() {
            @Override
            public void callJavaScript(String functionName, String... args) {
            }

            @Override
            public String escapeJs(String str) {
                return str;
            }

            @Override
            public void dispatchEvent(String type, String payloadJson) {
                sink.add(new String[]{type, payloadJson == null ? "" : payloadJson});
            }
        };
        HandlerContext ctx = new HandlerContext(null, service, cb);
        return new FrontendActionContext(ctx);
    }

    private static Field homeField() throws Exception {
        Field field = PlatformUtils.class.getDeclaredField("cachedRealHomeDir");
        field.setAccessible(true);
        return field;
    }
}
