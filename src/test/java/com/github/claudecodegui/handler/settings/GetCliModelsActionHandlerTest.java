package com.github.claudecodegui.handler.settings;

import com.github.claudecodegui.handler.core.FrontendActionDispatcher;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * GetCliModelsActionHandler 定向单测(恢复 1c1084b3 被删的 CliModelsHandler 语义)。
 *
 * <p>可执行面覆盖:typed wiring(action 值可路由)、不支持 provider 的同步错误回推。
 * spawn 路径依赖 IntelliJ 平台服务(AppExecutorUtil / NodeService),按
 * CodexHistoryPageActionHandlerContractTest 惯例以源码文本断言覆盖关键要素。
 */
public class GetCliModelsActionHandlerTest {

    /** 捕获回推 JS 的 fake callback。 */
    private static class CapturingCallback implements HandlerContext.JsCallback {
        final AtomicReference<String> function = new AtomicReference<>();
        final AtomicReference<String> arg = new AtomicReference<>();

        @Override
        public void callJavaScript(String functionName, String... args) {
            function.set(functionName);
            arg.set(args != null && args.length > 0 ? args[0] : null);
        }

        @Override
        public String escapeJs(String str) {
            return str;
        }
    }

    @Test
    public void actionResolvesToGetCliModels() {
        GetCliModelsActionHandler handler = new GetCliModelsActionHandler();
        assertEquals(UpstreamAction.GET_CLI_MODELS, handler.action());
        assertEquals("get_cli_models", handler.action().value());
        assertEquals(String.class, handler.payloadType());
    }

    @Test
    public void unsupportedProviderPushesErrorToSetCliModels() {
        CapturingCallback callback = new CapturingCallback();
        HandlerContext ctx = new HandlerContext(null, null, callback);
        FrontendActionDispatcher dispatcher = new FrontendActionDispatcher(
                List.<FrontendActionHandler<?>>of(new GetCliModelsActionHandler()), ctx);

        assertTrue(dispatcher.dispatch("get_cli_models", "not-a-provider"));

        assertEquals("window.setCliModels", callback.function.get());
        String payload = callback.arg.get();
        assertNotNull(payload);
        JsonObject json = GsonHolder.GSON.fromJson(payload, JsonObject.class);
        assertEquals(false, json.get("success").getAsBoolean());
        assertEquals("not-a-provider", json.get("provider").getAsString());
        assertTrue(json.get("error").getAsString().contains("Unsupported CLI provider"));
        assertEquals(0, json.getAsJsonArray("models").size());
    }

    /**
     * spawn 链路关键要素契约:七家 provider 全支持、channel-manager listModels 命令、
     * 经 window.setCliModels 回推(与前端 useCliModels 的 setCliModels 回填约定)。
     */
    @Test
    public void spawnPathContract() throws Exception {
        Path source = new File(
                "src/main/java/com/github/claudecodegui/handler/settings/GetCliModelsActionHandler.java")
                .toPath();
        String text = Files.readString(source, StandardCharsets.UTF_8);
        for (String provider : new String[]{"\"opencode\"", "\"kimi\"", "\"pi\"",
                "\"omp\"", "\"codex\"", "\"grok\"", "\"dsh\""}) {
            assertTrue("SUPPORTED_PROVIDERS must contain " + provider, text.contains(provider));
        }
        assertTrue(text.contains("channel-manager.js"));
        assertTrue(text.contains("\"listModels\""));
        assertTrue(text.contains("window.setCliModels"));
        assertTrue(text.contains("AppExecutorUtil.getAppExecutorService()"));
    }
}
