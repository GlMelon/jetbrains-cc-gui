package com.github.claudecodegui.cli.opencode.serve;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * OpenCodeServeClient 行为测试:JDK 内置 HttpServer 模拟 opencode serve
 * (建会话 / prompt_async / abort / 权限应答 / SSE /event 帧解析与按 sessionID 解复用)。
 */
public class OpenCodeServeClientTest {

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

    private static void respondJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    public void createSessionPassesDirectoryAndReturnsId() throws Exception {
        AtomicReference<String> seenQuery = new AtomicReference<>();
        AtomicReference<String> seenMethod = new AtomicReference<>();
        server.createContext("/session", exchange -> {
            if (exchange.getRequestURI().getPath().equals("/session")) {
                seenMethod.set(exchange.getRequestMethod());
                seenQuery.set(exchange.getRequestURI().getRawQuery());
                respondJson(exchange, 200, "{\"id\":\"ses_test_1\"}");
            } else {
                respondJson(exchange, 404, "{}");
            }
        });
        OpenCodeServeClient client = new OpenCodeServeClient(baseUrl);
        try {
            String id = client.createSession("D:\\work dir\\proj");
            assertEquals("ses_test_1", id);
            assertEquals("POST", seenMethod.get());
            assertNotNull(seenQuery.get());
            assertTrue(seenQuery.get().startsWith("directory="));
            assertTrue(seenQuery.get().contains("+") || seenQuery.get().contains("%20"));
        } finally {
            client.close();
        }
    }

    @Test
    public void promptAsyncNon2xxThrowsServeApiExceptionWithStatus() throws Exception {
        server.createContext("/session", exchange -> respondJson(exchange, 404, "{\"error\":\"no session\"}"));
        OpenCodeServeClient client = new OpenCodeServeClient(baseUrl);
        try {
            client.promptAsync("ses_gone", new JsonObject());
            fail("expected ServeApiException");
        } catch (OpenCodeServeClient.ServeApiException e) {
            assertEquals(404, e.statusCode());
            assertTrue(e.isClientError());
        } finally {
            client.close();
        }
    }

    @Test
    public void abortAndPermissionRespondPostToExpectedPaths() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        server.createContext("/session", exchange -> {
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                    + " " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respondJson(exchange, 200, "true");
        });
        OpenCodeServeClient client = new OpenCodeServeClient(baseUrl);
        try {
            client.abort("ses_1");
            client.respondPermission("ses_1", "per_9", "reject");
        } finally {
            client.close();
        }
        assertEquals(2, calls.size());
        assertTrue(calls.get(0).startsWith("POST /session/ses_1/abort"));
        assertTrue(calls.get(1).startsWith("POST /session/ses_1/permissions/per_9"));
        assertTrue(calls.get(1).contains("\"response\":\"reject\""));
    }

    @Test
    public void sseStreamDispatchesFramesBySessionId() throws Exception {
        CountDownLatch sseSeen = new CountDownLatch(1);
        server.createContext("/event", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0); // chunked
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("data: {\"type\":\"message.part.delta\",\"properties\":"
                        .getBytes(StandardCharsets.UTF_8));
                out.write("{\"sessionID\":\"ses_a\",\"partID\":\"p1\",\"field\":\"text\",\"delta\":\"hi\"}}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
                // 其他 session 的事件不应路由到 ses_a 的 handler
                out.write("data: {\"type\":\"session.idle\",\"properties\":{\"sessionID\":\"ses_b\"}}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                sseSeen.countDown();
                // 保持连接打开,等待客户端 close
                try {
                    Thread.sleep(5_000L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        OpenCodeServeClient client = new OpenCodeServeClient(baseUrl);
        List<JsonObject> received = new CopyOnWriteArrayList<>();
        CountDownLatch eventLatch = new CountDownLatch(1);
        client.registerTurnHandler("ses_a", new OpenCodeServeClient.TurnEventHandler() {
            @Override
            public void onEvent(JsonObject event) {
                received.add(event);
                eventLatch.countDown();
            }

            @Override
            public void onStreamClosed(String reason) {
            }
        });
        try {
            client.startEventStream();
            assertTrue("SSE event not received in time", eventLatch.await(5, TimeUnit.SECONDS));
            assertEquals(1, received.size());
            assertEquals("message.part.delta", received.get(0).get("type").getAsString());
            assertEquals("hi", received.get(0).getAsJsonObject("properties").get("delta").getAsString());
        } finally {
            client.close();
        }
    }

    @Test
    public void sseStreamCloseNotifiesHandlers() throws Exception {
        CountDownLatch closedLatch = new CountDownLatch(1);
        AtomicReference<String> closeReason = new AtomicReference<>();
        server.createContext("/event", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            // 立即关闭流(模拟 serve 死亡 / 断线)
            exchange.close();
        });
        OpenCodeServeClient client = new OpenCodeServeClient(baseUrl);
        client.registerTurnHandler("ses_1", new OpenCodeServeClient.TurnEventHandler() {
            @Override
            public void onEvent(JsonObject event) {
            }

            @Override
            public void onStreamClosed(String reason) {
                closeReason.set(reason);
                closedLatch.countDown();
            }
        });
        try {
            client.startEventStream();
            assertTrue("onStreamClosed not called in time", closedLatch.await(5, TimeUnit.SECONDS));
            assertNotNull(closeReason.get());
        } finally {
            client.close();
        }
    }
}
