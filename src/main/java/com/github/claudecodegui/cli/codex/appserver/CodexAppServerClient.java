package com.github.claudecodegui.cli.codex.appserver;

import com.github.claudecodegui.cli.common.CliOutputLimits;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * codex app-server stdio JSON-RPC 2.0 客户端(NDJSON,每行一条消息,jsonrpc 头线上省略)。
 * <p>
 * 三类入站消息分派:{@code {id,result|error}} 响应 → 关联 pending future;{@code {method,params}}
 * 通知 → 按 {@code params.threadId} 路由到已注册的 {@link TurnEventHandler};
 * {@code {id,method,params}} server 主动请求(审批)→ 路由到对应 thread 的 handler,
 * 由其应答({@link #respondToServer}/{@link #respondErrorToServer}),未注册 handler 的
 * 请求以 -32601 错误应答(协议正确的「客户端不支持」,避免 turn 因等待应答挂起)。
 * <p>
 * 进程死亡(reader EOF)→ 全部 pending future 异常完成、通知全部已注册 handler、
 * 触发 {@link #setProcessDiedListener(Runnable)}(manager 摘句柄,下次 acquire 重建)。
 * 写入经 {@code synchronized(writeLine)} 串行(多线程请求 / 审批应答共用 stdin)。
 * <p>
 * 契约经 codex-cli 0.153.4 {@code app-server generate-json-schema} 校验;实测:
 * initialize 响应 ~250ms,thread/start 响应含 {@code thread.id},turn/start 响应含 {@code turn.id}。
 */
public final class CodexAppServerClient {

    /** JSON-RPC 标准错误码:method not found(对未知 server 请求的规范应答)。 */
    private static final int JSONRPC_ERROR_METHOD_NOT_FOUND = -32601;

    /** 每 thread 的事件处理器:通知 + server 主动请求 + 进程死亡。 */
    public interface TurnEventHandler {
        /** server 通知(含 method/params,按 threadId 已路由)。 */
        void onNotification(@NotNull JsonObject notification);

        /** server 主动请求(含 id/method/params):handler 必须经 client 应答,否则 turn 挂起。 */
        void onServerRequest(@NotNull JsonObject request);

        /** 进程死亡 / 流关闭:已标记中断由会话层收尾,否则当轮失败。 */
        void onProcessDied(@NotNull String reason);
    }

    private final Process process;
    private final Writer stdin;
    private final AtomicLong requestIds = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final Map<String, TurnEventHandler> turnHandlers = new ConcurrentHashMap<>();
    private volatile Runnable processDiedListener;
    private volatile boolean closed;
    private final Thread reader;

    public CodexAppServerClient(@NotNull Process process) {
        this.process = process;
        this.stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        this.reader = new Thread(this::readLoop, "AICG-Codex-AppServer-Reader");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    public void setProcessDiedListener(@NotNull Runnable listener) {
        this.processDiedListener = listener;
    }

    public void registerTurnHandler(@NotNull String threadId, @NotNull TurnEventHandler handler) {
        turnHandlers.put(threadId, handler);
    }

    public void unregisterTurnHandler(@NotNull String threadId) {
        turnHandlers.remove(threadId);
    }

    /** client → server 请求(调用方自带超时;error 响应 → future 异常完成,message 为协议错误文本)。 */
    public @NotNull CompletableFuture<JsonObject> request(@NotNull String method, @Nullable JsonObject params) {
        long id = requestIds.getAndIncrement();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put(id, future);
        JsonObject message = new JsonObject();
        message.addProperty("id", id);
        message.addProperty("method", method);
        if (params != null) {
            message.add("params", params);
        }
        try {
            writeLine(GsonHolder.GSON.toJson(message));
        } catch (Exception e) {
            pending.remove(id);
            future.completeExceptionally(e);
        }
        return future;
    }

    /** client → server 通知(无 id,无响应)。 */
    public void notify(@NotNull String method, @Nullable JsonObject params) {
        JsonObject message = new JsonObject();
        message.addProperty("method", method);
        if (params != null) {
            message.add("params", params);
        }
        writeLineQuietly(GsonHolder.GSON.toJson(message));
    }

    /** 应答 server 主动请求(审批决策等):{@code {id, result}}。 */
    public void respondToServer(long id, @NotNull JsonObject result) {
        JsonObject message = new JsonObject();
        message.addProperty("id", id);
        message.add("result", result);
        writeLineQuietly(GsonHolder.GSON.toJson(message));
    }

    /** 以 JSON-RPC 错误应答 server 主动请求(不支持的方法 / 内部失败)。 */
    public void respondErrorToServer(long id, int code, @NotNull String messageText) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", messageText);
        JsonObject message = new JsonObject();
        message.addProperty("id", id);
        message.add("error", error);
        writeLineQuietly(GsonHolder.GSON.toJson(message));
    }

    /** 关闭客户端(关 stdin 触发进程退出;进程树终止由 manager 负责)。幂等。 */
    public void close() {
        closed = true;
        try {
            stdin.close();
        } catch (Exception ignored) {
            // 关闭路径异常尽数吞掉
        }
        failPending("client closed");
    }

    // ── reader 循环 ────────────────────────────────────────────────────────────

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.length() > CliOutputLimits.MAX_LINE_BYTES) {
                    line = line.substring(0, CliOutputLimits.MAX_LINE_BYTES);
                }
                dispatch(line);
            }
        } catch (Exception ignored) {
            // 进程退出 / 流关闭即结束
        }
        onReaderEof();
    }

    private void dispatch(String line) {
        JsonObject message;
        try {
            message = GsonHolder.GSON.fromJson(line, JsonObject.class);
        } catch (Exception e) {
            return;
        }
        if (message == null) {
            return;
        }
        boolean hasId = message.has("id") && !message.get("id").isJsonNull();
        boolean hasMethod = message.has("method") && !message.get("method").isJsonNull();
        if (hasId && hasMethod) {
            dispatchServerRequest(message);
        } else if (hasMethod) {
            dispatchNotification(message);
        } else if (hasId) {
            dispatchResponse(message);
        }
    }

    private void dispatchResponse(JsonObject message) {
        long id = message.get("id").getAsLong();
        CompletableFuture<JsonObject> future = pending.remove(id);
        if (future == null) {
            return;
        }
        if (message.has("error") && !message.get("error").isJsonNull()) {
            JsonObject error = message.getAsJsonObject("error");
            String text = error.has("message") && !error.get("message").isJsonNull()
                    ? error.get("message").getAsString() : "app-server error response";
            future.completeExceptionally(new AppServerApiException(text));
        } else {
            future.complete(message.has("result") && message.get("result").isJsonObject()
                    ? message.getAsJsonObject("result") : new JsonObject());
        }
    }

    private void dispatchNotification(JsonObject message) {
        JsonObject params = message.has("params") && message.get("params").isJsonObject()
                ? message.getAsJsonObject("params") : null;
        TurnEventHandler handler = params != null && params.has("threadId")
                ? turnHandlers.get(params.get("threadId").getAsString()) : null;
        if (handler != null) {
            handler.onNotification(message);
        }
        // 未注册 thread 的通知静默丢弃(对齐 opencode SSE 帧 by-session 路由)
    }

    private void dispatchServerRequest(JsonObject message) {
        long id = message.get("id").getAsLong();
        JsonObject params = message.has("params") && message.get("params").isJsonObject()
                ? message.getAsJsonObject("params") : null;
        TurnEventHandler handler = params != null && params.has("threadId")
                ? turnHandlers.get(params.get("threadId").getAsString()) : null;
        if (handler != null) {
            handler.onServerRequest(message);
            return;
        }
        // 无 handler(该 thread 已收尾 / 异常时序):规范错误应答,避免 turn 因等待应答挂起
        respondErrorToServer(id, JSONRPC_ERROR_METHOD_NOT_FOUND, "no handler for thread");
    }

    private synchronized void writeLine(String payload) throws Exception {
        if (closed) {
            throw new IllegalStateException("app-server client closed");
        }
        stdin.write(payload);
        stdin.write('\n');
        stdin.flush();
    }

    private void writeLineQuietly(String payload) {
        try {
            writeLine(payload);
        } catch (Exception ignored) {
            // 进程已死 / 客户端已关:写入失败由 reader EOF 收尾
        }
    }

    private void onReaderEof() {
        failPending("app-server process died (stdout EOF)");
        for (TurnEventHandler handler : turnHandlers.values()) {
            try {
                handler.onProcessDied("app-server process died (stdout EOF)");
            } catch (Exception ignored) {
                // 收尾路径异常不互相阻断
            }
        }
        turnHandlers.clear();
        Runnable listener = processDiedListener;
        if (listener != null) {
            try {
                listener.run();
            } catch (Exception ignored) {
                // 同上
            }
        }
    }

    private void failPending(String reason) {
        for (CompletableFuture<JsonObject> future : pending.values()) {
            future.completeExceptionally(new AppServerApiException(reason));
        }
        pending.clear();
    }

    /** app-server JSON-RPC error 响应 / 传输失败(对称 OpenCodeServeClient.ServeApiException)。 */
    public static final class AppServerApiException extends Exception {
        public AppServerApiException(@NotNull String message) {
            super(message);
        }
    }
}
