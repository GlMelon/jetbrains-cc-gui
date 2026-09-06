package com.github.claudecodegui.cli.opencode.serve;

import com.github.claudecodegui.cli.CliSendRequest;
import com.github.claudecodegui.cli.CliSession;
import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.common.CommonConstants;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * OpenCodeServeSession 降级语义测试(JDK HttpServer 模拟 serve,经测试 seam 注入 client
 * 与 one-shot 兜底替身):失败收尾按「prompt 是否已递交」分派,不按异常类型。
 * <ul>
 *   <li>prompt 递交前失败(createSession 连接中断 IO / 4xx)→ 降级 one-shot(重发安全);</li>
 *   <li>promptAsync 自身失败(递交结果未知)→ 不降级,直接报错(防双发)。</li>
 * </ul>
 */
public class OpenCodeServeSessionFallbackTest {

    private HttpServer server;
    private String baseUrl;

    @Before
    public void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void tearDown() {
        server.stop(0);
    }

    private static final class RecordingCallback implements CliSessionCallback {
        String error;
        Boolean completeSuccess;

        @Override
        public void onMessage(String type, String content) {
        }

        @Override
        public void onError(String error) {
            this.error = error;
        }

        @Override
        public void onComplete(boolean success, String fullContent, String error) {
            completeSuccess = success;
        }
    }

    /** one-shot 兜底替身:记录是否被调用,并以成功收尾当轮。 */
    private static final class RecordingFallback implements CliSession {
        final AtomicBoolean sendCalled = new AtomicBoolean(false);

        @Override
        public CompletableFuture<Void> send(CliSendRequest request, CliSessionCallback callback) {
            sendCalled.set(true);
            callback.onComplete(true, "fallback-ok", null);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void interrupt() {
        }

        @Override
        public void dispose() {
        }
    }

    private static CliSendRequest newRequest() {
        return new CliSendRequest("tab-1", CommonConstants.PROVIDER_OPENCODE, "hi",
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static void respondJson(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    public void createSessionIoFailureDegradesToOneShot() throws Exception {
        // /session 连接被服务端直接关闭(无响应)→ client 抛 IOException(非 ServeApiException)。
        // 修复前:落入 generic 分支直接 onError;修复后:prompt 未递交,降级 one-shot。
        server.createContext("/session", exchange -> exchange.close());
        RecordingFallback fallback = new RecordingFallback();
        RecordingCallback cb = new RecordingCallback();
        OpenCodeServeSession session = new OpenCodeServeSession(
                "tab-1", new OpenCodeServeClient(baseUrl), fallback);
        try {
            session.send(newRequest(), cb).get(10, TimeUnit.SECONDS);
            assertTrue("pre-submit IOException should degrade to one-shot", fallback.sendCalled.get());
            assertNull(cb.error);
            assertEquals(Boolean.TRUE, cb.completeSuccess);
        } finally {
            session.dispose();
        }
    }

    @Test
    public void createSessionHttpErrorDegradesToOneShot() throws Exception {
        // 递交前 ServeApiException(4xx/5xx):prompt 未到达 serve,同样降级
        server.createContext("/session", exchange -> respondJson(exchange, 503, "{}"));
        RecordingFallback fallback = new RecordingFallback();
        RecordingCallback cb = new RecordingCallback();
        OpenCodeServeSession session = new OpenCodeServeSession(
                "tab-1", new OpenCodeServeClient(baseUrl), fallback);
        try {
            session.send(newRequest(), cb).get(10, TimeUnit.SECONDS);
            assertTrue(fallback.sendCalled.get());
            assertNull(cb.error);
            assertEquals(Boolean.TRUE, cb.completeSuccess);
        } finally {
            session.dispose();
        }
    }

    @Test
    public void promptAsyncFailureDoesNotDegrade() throws Exception {
        // 建会话成功,prompt_async 返回 500:递交结果未知,不降级(防双发),直接报错
        server.createContext("/session", exchange -> {
            if (exchange.getRequestURI().getPath().equals("/session")) {
                respondJson(exchange, 200, "{\"id\":\"ses_1\"}");
            } else {
                respondJson(exchange, 500, "{\"error\":\"boom\"}");
            }
        });
        RecordingFallback fallback = new RecordingFallback();
        RecordingCallback cb = new RecordingCallback();
        OpenCodeServeSession session = new OpenCodeServeSession(
                "tab-1", new OpenCodeServeClient(baseUrl), fallback);
        try {
            session.send(newRequest(), cb).get(10, TimeUnit.SECONDS);
            assertFalse("promptAsync failure must not degrade (double-submit risk)", fallback.sendCalled.get());
            assertNotNull(cb.error);
            assertEquals(Boolean.FALSE, cb.completeSuccess);
        } finally {
            session.dispose();
        }
    }
}
