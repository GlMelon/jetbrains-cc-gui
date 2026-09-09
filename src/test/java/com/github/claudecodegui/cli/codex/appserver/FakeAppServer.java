package com.github.claudecodegui.cli.codex.appserver;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 测试用假 codex app-server:经管道对接 {@link CodexAppServerClient} 的 Process 抽象。
 * <p>
 * responder 线程读取 client 发来的 JSON-RPC 请求,按 {@link RequestHandler} 决定应答:
 * 返回 {@code {result:...}} 正常应答、{@code {error:{...}}} 错误应答、null 不应答(模拟超时);
 * 测试亦可经 {@link #sendNotification} 推送通知、经 {@link #sendServerRequest} 发起审批请求、
 * 经 {@link #receivedResponses()} 断言 client 的应答。
 */
final class FakeAppServer implements AutoCloseable {

    /** 请求处理器:method → 完整响应 body({result}|{error}|null=不应答)。 */
    interface RequestHandler extends Function<String, @Nullable JsonObject> {
    }

    /** 构造 JSON-RPC 错误响应 body。 */
    static JsonObject errorResponse(String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", -32000);
        error.addProperty("message", message);
        JsonObject body = new JsonObject();
        body.add("error", error);
        return body;
    }

    /** 构造 JSON-RPC 正常响应 body。 */
    static JsonObject okResponse(JsonObject result) {
        JsonObject body = new JsonObject();
        body.add("result", result != null ? result : new JsonObject());
        return body;
    }

    private final PipedInputStream serverReads = new PipedInputStream();
    private final PipedOutputStream serverWrites = new PipedOutputStream();
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private final AtomicLong requestIds = new AtomicLong(1000);
    private final Map<Long, JsonObject> receivedResponses = new ConcurrentHashMap<>();
    private final StringBuilder receivedRequests = new StringBuilder();
    private final Thread responder;
    private volatile RequestHandler handler = m -> null;

    private final Process process = new Process() {
        @Override
        public OutputStream getOutputStream() {
            return clientWrites;
        }

        @Override
        public InputStream getInputStream() {
            return clientReads;
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            while (alive.get()) {
                Thread.sleep(10);
            }
            return 0;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            alive.set(false);
        }
    };

    /** client 写入端(server 的 stdin):client.request 的落点。 */
    private final PipedOutputStream clientWrites = new PipedOutputStream();
    /** client 读取端(server 的 stdout):本 fake 的应答与通知源。 */
    private final PipedInputStream clientReads = new PipedInputStream();

    FakeAppServer() throws Exception {
        serverReads.connect(clientWrites);
        serverWrites.connect(clientReads);
        responder = new Thread(this::respondLoop, "FakeAppServer-Responder");
        responder.setDaemon(true);
        responder.start();
    }

    CodexAppServerClient createClient() {
        return new CodexAppServerClient(process);
    }

    void setHandler(RequestHandler h) {
        this.handler = h;
    }

    /** 主动推送通知(带 threadId params)。 */
    void sendNotification(String method, JsonObject params) throws Exception {
        JsonObject notification = new JsonObject();
        notification.addProperty("method", method);
        notification.add("params", params);
        serverWrites.write((notification + "\n").getBytes(StandardCharsets.UTF_8));
        serverWrites.flush();
    }

    /** 收到的 client 应答(审批决策),按 JSON-RPC id 索引。 */
    Map<Long, JsonObject> receivedResponses() {
        return receivedResponses;
    }

    @Override
    public void close() {
        alive.set(false);
        responder.interrupt();
        try {
            serverWrites.close();
        } catch (Exception ignored) {
        }
        try {
            clientWrites.close();
        } catch (Exception ignored) {
        }
    }

    private void respondLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(serverReads, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                synchronized (receivedRequests) {
                    receivedRequests.append(line).append('\n');
                }
                JsonObject message;
                try {
                    message = JsonParser.parseString(line).getAsJsonObject();
                } catch (Exception e) {
                    continue;
                }
                if (message.has("method") && !message.get("method").isJsonNull()) {
                    handleRequest(message);
                } else if (message.has("id") && !message.get("id").isJsonNull()) {
                    receivedResponses.put(message.get("id").getAsLong(), message);
                }
            }
        } catch (Exception ignored) {
            // close() / 管道关闭即结束
        }
    }

    private void handleRequest(JsonObject request) {
        if (!request.has("id") || request.get("id").isJsonNull()) {
            return; // client 通知(initialized),无需应答
        }
        long id = request.get("id").getAsLong();
        String method = request.get("method").getAsString();
        JsonObject body;
        try {
            body = handler.apply(method);
        } catch (Exception e) {
            body = null; // handler 抛错视为不应答(模拟无响应)
        }
        if (body == null) {
            return; // 模拟超时:不应答
        }
        JsonObject response = new JsonObject();
        response.addProperty("id", id);
        if (body.has("result")) {
            response.add("result", body.get("result"));
        } else if (body.has("error")) {
            response.add("error", body.get("error"));
        }
        try {
            serverWrites.write((response + "\n").getBytes(StandardCharsets.UTF_8));
            serverWrites.flush();
        } catch (Exception ignored) {
            // 进程已关
        }
    }

    /** 下一个 server 主动请求 id(审批测试用)。 */
    long nextRequestId() {
        return requestIds.getAndIncrement();
    }

    /** server 主动请求(审批):推送给 client,返回请求 id。 */
    long sendServerRequest(String method, JsonObject params) throws Exception {
        long id = requestIds.getAndIncrement();
        JsonObject request = new JsonObject();
        request.addProperty("id", id);
        request.addProperty("method", method);
        request.add("params", params);
        serverWrites.write((request + "\n").getBytes(StandardCharsets.UTF_8));
        serverWrites.flush();
        return id;
    }
}
