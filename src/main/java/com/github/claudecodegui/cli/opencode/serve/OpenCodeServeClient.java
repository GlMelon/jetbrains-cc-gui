package com.github.claudecodegui.cli.opencode.serve;

import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static com.github.claudecodegui.cli.opencode.OpenCodeEventMapper.asObject;
import static com.github.claudecodegui.cli.opencode.OpenCodeEventMapper.getString;

/**
 * opencode serve HTTP/SSE 客户端(实测契约:opencode v1.18.26)。
 * <p>
 * 职责仅两件事:
 * <ol>
 *   <li>控制面 HTTP 调用:POST /session(建会话)、POST /session/{id}/prompt_async(发消息,
 *       立即返回,结果经 SSE 到达)、POST /session/{id}/abort(确定性取消)、
 *       POST /session/{id}/permissions/{pid}(权限应答);</li>
 *   <li>GET /event SSE 长连接:永久读行线程(对称 CliPersistentProcess 模式)解析
 *       {@code data: {id, type, properties}} 帧,按 {@code properties.sessionID} 解复用
 *       路由到对应轮的监听器(一个 serve 服务多 tab)。断线即当轮失败,不重连;
 *       半开(进程活着但流死)由 server.heartbeat 活性看门狗超时关流,同断线路径。</li>
 * </ol>
 * serve 无认证,baseUrl 必须绑回环({@link CliConstants#OPENCODE_SERVE_HOSTNAME}),
 * 由 {@link OpenCodeServeManager} spawn 时保证。
 */
public final class OpenCodeServeClient implements Closeable {

    private static final Logger LOG = Logger.getInstance(OpenCodeServeClient.class);

    // serve HTTP API 路径(外部契约,同文件系统约定名,保留字面量)
    private static final String PATH_EVENT = "/event";
    private static final String PATH_SESSION = "/session";
    private static final String SUFFIX_PROMPT_ASYNC = "/prompt_async";
    private static final String SUFFIX_ABORT = "/abort";
    private static final String SEGMENT_PERMISSIONS = "/permissions/";
    private static final String QUERY_DIRECTORY = "?directory=";

    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(15);

    /** SSE 帧行前缀(data 字段;event:/id:/注释行忽略)。 */
    private static final String SSE_DATA_PREFIX = "data:";

    /** HTTP 非 2xx 时抛出,携带状态码供调用方判定 session 失效(B13 等价)。 */
    public static final class ServeApiException extends IOException {
        private final int statusCode;

        public ServeApiException(int statusCode, String path, String responseBody) {
            super("opencode serve API failed: HTTP " + statusCode + " " + path
                    + (responseBody != null && !responseBody.isBlank()
                    ? " - " + abbreviate(responseBody) : ""));
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }

        /** 4xx 视为续接 session 已失效(已删库/重启后 DB 缺失等),供 B13 等价重试判定。 */
        public boolean isClientError() {
            return statusCode >= 400 && statusCode < 500;
        }
    }

    /**
     * 轮级 SSE 事件监听器(按 sessionID 注册/注销)。事件在 SSE 读行线程上同步回调,
     * 实现方不得阻塞;流关闭时对所有在册监听器回调 {@link #onStreamClosed}。
     */
    public interface TurnEventHandler {
        void onEvent(JsonObject event);

        /** SSE 流断开(进程死/网络断/主动 close):当轮失败,不重连。 */
        void onStreamClosed(String reason);
    }

    /**
     * 静态共享 HttpClient:baseUrl 按请求拼接,builder 配置无 per-实例状态;
     * Java 17 HttpClient 无 close(),每实例自带 SelectorManager 守护线程,
     * serve 反复重建场景会短暂堆积线程,故全实例共享一个。
     * 必须 HTTP/1.1:默认 HTTP_2 偏好对明文 http 会走 h2c upgrade 流程,
     * opencode serve(bun)对带 body 的 upgrade POST 不应答 → 挂死至超时
     * (本机 Java 17 + opencode 1.18.26 实测:POST /session 挂 15s 超时,
     * 强制 1.1 后 12ms 返回;SSE GET 无 body 本就走 1.1 语义,不受影响)。
     */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private final Gson gson = GsonHolder.GSON;
    private final String baseUrl;
    private final ConcurrentHashMap<String, TurnEventHandler> turnHandlers = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /** 最近一次 SSE 行到达时间(心跳即活性,看门狗据此判定半开)。 */
    private final AtomicLong lastActivityAt = new AtomicLong(System.currentTimeMillis());
    private volatile Thread sseThread;
    private volatile Thread watchdogThread;
    private volatile Stream<String> sseLines;
    /** 流关闭回调(管理器摘除死句柄用;轮级通知走 TurnEventHandler.onStreamClosed)。 */
    private volatile Runnable streamClosedListener;

    public OpenCodeServeClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** 当前在册轮监听器的只读视图(管理器关停时逐个通知用)。 */
    public Map<String, TurnEventHandler> turnHandlers() {
        return Map.copyOf(turnHandlers);
    }

    public void registerTurnHandler(String sessionId, TurnEventHandler handler) {
        if (sessionId == null || sessionId.isBlank() || handler == null) {
            throw new IllegalArgumentException("sessionId and handler required");
        }
        turnHandlers.put(sessionId, handler);
    }

    public void unregisterTurnHandler(String sessionId) {
        if (sessionId != null) {
            turnHandlers.remove(sessionId);
        }
    }

    /** 设置 SSE 流关闭回调(OpenCodeServeManager 用以摘除死句柄、下轮触发重建)。 */
    public void setStreamClosedListener(Runnable listener) {
        this.streamClosedListener = listener;
    }

    /**
     * POST /session?directory=<cwd> → 返回新 session id(ses_…)。
     * 一个 serve 可服务多目录,directory 由每次建会话时传入(非 spawn 时绑定)。
     */
    public String createSession(String directory) throws IOException, InterruptedException {
        String path = PATH_SESSION;
        if (directory != null && !directory.isBlank()) {
            path += QUERY_DIRECTORY + URLEncoder.encode(directory, StandardCharsets.UTF_8);
        }
        JsonObject response = post(path, new JsonObject());
        String id = getString(response, "id");
        if (id == null || id.isBlank()) {
            throw new IOException("opencode serve createSession returned no id: " + abbreviate(response.toString()));
        }
        return id;
    }

    /** POST /session/{id}/prompt_async:立即返回,结果经 SSE 到达。 */
    public void promptAsync(String sessionId, JsonObject body) throws IOException, InterruptedException {
        post(PATH_SESSION + "/" + sessionId + SUFFIX_PROMPT_ASYNC, body);
    }

    /** POST /session/{id}/abort:确定性取消,SSE 随后发 session.error(MessageAbortedError)。 */
    public void abort(String sessionId) throws IOException, InterruptedException {
        post(PATH_SESSION + "/" + sessionId + SUFFIX_ABORT, new JsonObject());
    }

    /** POST /session/{id}/permissions/{pid}:应答工具权限请求(response: once/always/reject)。 */
    public void respondPermission(String sessionId, String permissionId, String response)
            throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("response", response);
        post(PATH_SESSION + "/" + sessionId + SEGMENT_PERMISSIONS + permissionId, body);
    }

    // ── SSE 长连接 ────────────────────────────────────────────────────────────

    /**
     * 启动 SSE 读行线程(GET /event,无请求超时,长连接)。断线/异常/流结束即回调
     * 所有在册轮 {@link TurnEventHandler#onStreamClosed}(当轮失败,不重连)。
     * 幂等:已启动或已关闭时为空操作。
     */
    public synchronized void startEventStream() {
        if (closed.get() || sseThread != null) {
            return;
        }
        Thread thread = new Thread(this::runSseLoop, "AICG-OpenCode-Serve-SSE");
        thread.setDaemon(true);
        sseThread = thread;
        thread.start();
        Thread watchdog = new Thread(this::runStaleWatchdog, "AICG-OpenCode-Serve-SSE-Watchdog");
        watchdog.setDaemon(true);
        watchdogThread = watchdog;
        watchdog.start();
    }

    private void runSseLoop() {
        String closeReason = "SSE stream ended";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + PATH_EVENT))
                    .GET()
                    .build();
            HttpResponse<Stream<String>> response =
                    HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                closeReason = "SSE connect failed: HTTP " + response.statusCode();
                return;
            }
            lastActivityAt.set(System.currentTimeMillis());
            Stream<String> lines = response.body();
            sseLines = lines;
            StringBuilder dataBuffer = new StringBuilder();
            for (String line : (Iterable<String>) lines::iterator) {
                lastActivityAt.set(System.currentTimeMillis());
                if (closed.get()) {
                    closeReason = "client closed";
                    return;
                }
                if (line.startsWith(SSE_DATA_PREFIX)) {
                    String data = line.substring(SSE_DATA_PREFIX.length());
                    if (data.startsWith(" ")) {
                        data = data.substring(1);
                    }
                    dataBuffer.append(data);
                } else if (line.isBlank()) {
                    if (dataBuffer.length() > 0) {
                        dispatchFrame(dataBuffer.toString());
                        dataBuffer.setLength(0);
                    }
                }
                // event:/id:/注释行忽略(契约只发 data 帧)
            }
        } catch (Exception e) {
            closeReason = "SSE stream error: " + e.getMessage();
        } finally {
            sseLines = null;
            notifyStreamClosed(closeReason);
        }
    }

    /**
     * SSE 活性看门狗:/event 流约每 30s 一帧 server.heartbeat(opencode 契约,
     * 见 OpenCodeServeTurn 噪声白名单),超过 {@link CliConstants#OPENCODE_SERVE_SSE_STALE_TIMEOUT_MS}
     * 无任何 SSE 行即判定半开(TCP half-open:serve 进程活着但事件流已死)——关闭流触发
     * notifyStreamClosed,走既有「摘除句柄 + 当轮流关闭收尾 + 下次 acquire 重建」路径,
     * 不再等到 15min 轮超时才发现。空闲无轮时不误伤:健康连接心跳持续刷新活性;
     * 死连接只摘句柄,重建延迟到下次 acquire。
     */
    private void runStaleWatchdog() {
        try {
            while (!closed.get()) {
                Thread.sleep(CliConstants.OPENCODE_SERVE_SSE_WATCHDOG_INTERVAL_MS);
                Thread stream = sseThread;
                if (closed.get() || stream == null || !stream.isAlive()) {
                    return;
                }
                long idleMs = System.currentTimeMillis() - lastActivityAt.get();
                if (idleMs <= CliConstants.OPENCODE_SERVE_SSE_STALE_TIMEOUT_MS) {
                    continue;
                }
                LOG.warn("[OpenCodeServeClient] SSE stream stale (no data for " + idleMs
                        + "ms, heartbeat expected ~30s), closing half-open stream: " + baseUrl);
                Stream<String> lines = sseLines;
                if (lines != null) {
                    try {
                        lines.close();
                    } catch (Exception ignored) {
                        // 关闭路径异常尽数吞掉
                    }
                }
                // 兜底:close 未唤醒阻塞读时,interrupt 促读行线程异常退出
                stream.interrupt();
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 解析一帧 data JSON,按 properties.sessionID 路由;无在册 handler 的事件静默丢弃(噪声白名单)。 */
    private void dispatchFrame(String data) {
        JsonObject event;
        try {
            event = JsonParser.parseString(data).getAsJsonObject();
        } catch (Exception e) {
            LOG.debug("[OpenCodeServeClient] non-JSON SSE frame dropped: " + abbreviate(data));
            return;
        }
        String sessionId = getString(asObject(event, "properties"), "sessionID");
        TurnEventHandler handler = sessionId != null ? turnHandlers.get(sessionId) : null;
        if (handler == null) {
            return;
        }
        try {
            handler.onEvent(event);
        } catch (Exception e) {
            LOG.warn("[OpenCodeServeClient] turn event handler failed: session=" + sessionId, e);
        }
    }

    private void notifyStreamClosed(String reason) {
        LOG.info("[OpenCodeServeClient] SSE stream closed: " + reason);
        for (TurnEventHandler handler : turnHandlers.values()) {
            try {
                handler.onStreamClosed(reason);
            } catch (Exception e) {
                LOG.warn("[OpenCodeServeClient] onStreamClosed callback failed", e);
            }
        }
        Runnable listener = streamClosedListener;
        if (listener != null) {
            try {
                listener.run();
            } catch (Exception e) {
                LOG.warn("[OpenCodeServeClient] streamClosedListener failed", e);
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Stream<String> lines = sseLines;
        if (lines != null) {
            try {
                lines.close();
            } catch (Exception ignored) {
                // 关闭路径异常尽数吞掉
            }
        }
        Thread thread = sseThread;
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
            try {
                thread.join(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Thread watchdog = watchdogThread;
        if (watchdog != null && watchdog != Thread.currentThread()) {
            watchdog.interrupt();
        }
        turnHandlers.clear();
    }

    // ── 内部 ──────────────────────────────────────────────────────────────────

    private JsonObject post(String path, JsonObject body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(CONTROL_TIMEOUT)
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response =
                HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ServeApiException(response.statusCode(), path, response.body());
        }
        String responseBody = response.body();
        if (responseBody == null || responseBody.isBlank()) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(responseBody).getAsJsonObject();
        } catch (Exception e) {
            // abort 等接口返回 true(非 JSON object):包装返回,调用方不读字段
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("value", responseBody.trim());
            return wrapper;
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
