package com.github.claudecodegui.cli.kimi.acp;

import com.github.claudecodegui.cli.common.CliOutputLimits;
import com.github.claudecodegui.cli.common.CliProcessLifecycle;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * kimi ACP 进程连接:NDJSON framing + JSON-RPC 2.0 id 匹配(无业务语义)。
 *
 * <p>三个核心职责:
 * <ol>
 *   <li><b>stdout drain</b>:逐行读 NDJSON(用 {@link CliOutputLimits.BoundedLineReader} 处理超长行),
 *       每行调 {@link #route(String)} 分发;drain 线程结束时(进程关闭/EOF)reject 全部 pending
 *       (使 interrupt 杀进程后阻塞中的 request 立即失败,而非等超时);</li>
 *   <li><b>stderr drain</b>:累积到有界 ring buffer(8KB,异常退出时喂 errorDiagnostic),
 *       **与 stdout 分离**——ACP stdout 是纯 NDJSON 协议流,混入 stderr 日志会破坏解析
 *       (与 {@link com.github.claudecodegui.cli.common.AbstractRunOnceCliSession} 合并 stderr 的
 *       headless 一次性流设计的关键差异);</li>
 *   <li><b>JSON-RPC id 匹配</b>:request() 分配 id 写 NDJSON 请求 + 注册 pending future;
 *       route() 按消息形态分发——server 请求(method+id 无 result)交 responder 回应、
 *       响应(id+result/error)complete pending future、通知(method 无 id)转 lineSink(即 parser)。</li>
 * </ol>
 *
 * <p>不需要 Content-Length 分帧(ACP 用 NDJSON,非 LSP)。协议逻辑参照 upstream 历史上的
 * {@code ai-bridge/services/grok/grok-acp-client.js}({@code git show upstream/main} 可取;
 * 本地 ACP 链已按「能原生尽原生」决策删除,仅 kimi 保留 Java ACP)。
 */
final class KimiAcpConnection {

    private static final Logger LOG = Logger.getInstance(KimiAcpConnection.class);

    /** stderr 有界累积上限(异常退出诊断用)。 */
    private static final int STDERR_BUFFER_LIMIT = 8 * 1024;
    /** 优雅关闭:stdin EOF 后等待进程自然退出的上限,超时强杀。 */
    private static final long GRACEFUL_CLOSE_TIMEOUT_MS = 2_000L;

    /** server→client 请求的回应器(返回 result JsonObject,由 route 用 id 回写;抛异常或返回 null 则兜底 cancelled)。 */
    @FunctionalInterface
    interface ServerRequestResponder {
        JsonObject respond(String method, JsonObject params);
    }

    interface LifecycleCallbacks {
        void onStdinClose();

        void onStdoutEof();

        void onExit(int exitCode);

        void onTerminate();
    }

    private final Process process;
    /** 通知行转发目标(即当前 turn 的 parser)。长驻复用时每 turn 经 {@link #rebindLineSink} 换新。 */
    private volatile Consumer<String> lineSink;
    /** server→client 请求回应器。暖池接管时经 {@link #rebindResponder} 换绑到会话实例。 */
    private volatile ServerRequestResponder responder;
    private final Consumer<String> stderrSink;
    private final LifecycleCallbacks lifecycleCallbacks;

    private final AtomicLong nextId = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final Object stdinLock = new Object();
    private volatile boolean closed = false;
    private final AtomicBoolean stdinClosed = new AtomicBoolean();
    private final AtomicBoolean stdoutEof = new AtomicBoolean();
    private final AtomicBoolean exitObserved = new AtomicBoolean();
    private final AtomicBoolean terminated = new AtomicBoolean();
    private final StringBuilder stderrBuffer = new StringBuilder();

    KimiAcpConnection(Process process,
                      Consumer<String> lineSink,
                      ServerRequestResponder responder,
                      Consumer<String> stderrSink) {
        this(process, lineSink, responder, stderrSink, null);
    }

    KimiAcpConnection(Process process,
                      Consumer<String> lineSink,
                      ServerRequestResponder responder,
                      Consumer<String> stderrSink,
                      LifecycleCallbacks lifecycleCallbacks) {
        this.process = process;
        this.lineSink = lineSink;
        this.responder = responder;
        this.stderrSink = stderrSink;
        this.lifecycleCallbacks = lifecycleCallbacks;
        process.onExit().whenComplete((ignored, error) -> notifyExit());
    }

    /** 启动 stdout/stderr drain 线程。 */
    void start() {
        Thread stdoutThread = new Thread(this::drainStdout, "kimi-acp-stdout-" + process.pid());
        stdoutThread.setDaemon(true);
        stdoutThread.start();

        Thread stderrThread = new Thread(this::drainStderr, "kimi-acp-stderr-" + process.pid());
        stderrThread.setDaemon(true);
        stderrThread.start();
    }

    /**
     * 长驻复用:把通知行转发目标换绑到当前 turn 的 parser。
     * <p>
     * drain 线程每行读最新 volatile 引用,换绑即时生效。不换绑的后果:第二个 turn 起
     * 所有 {@code session/update} 仍路由进首 turn 的 parser(其 callback 指向已结束的
     * turn 的 handler),新 turn 的 parser 收不到任何事件 → {@code receivedAnyEvent}=false
     * 误报「静默空失败」,且内容经旧 handler 以非流式全量路径错乱渲染。
     */
    void rebindLineSink(Consumer<String> sink) {
        this.lineSink = sink;
    }

    /**
     * 暖池接管:把 server→client 请求回应器换绑到会话实例(暖连接停泊阶段用
     * 兜底 responder,不会有 prompt 触发的 permission 请求;adopt 后必须换绑,
     * 否则 AskUserQuestion 类请求得不到会话侧处理)。
     */
    void rebindResponder(ServerRequestResponder newResponder) {
        this.responder = newResponder;
    }

    /**
     * 发送 JSON-RPC 请求并等待响应。
     *
     * @param method   RPC method 名
     * @param params   params 对象(可为 null)
     * @param timeoutMs 超时毫秒(超时抛 {@link AcpTimeoutException})
     * @return result JsonObject
     * @throws AcpTimeoutException 超时
     * @throws AcpRpcException     server 返回 error 响应
     */
    JsonObject request(String method, JsonObject params, long timeoutMs) throws Exception {
        long id = nextId.getAndIncrement();
        JsonObject envelope = new JsonObject();
        envelope.addProperty("jsonrpc", KimiAcpProtocol.JSONRPC_VERSION);
        envelope.addProperty("id", id);
        envelope.addProperty("method", method);
        if (params != null) {
            envelope.add("params", params);
        }
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put(id, future);
        writeLine(envelope.toString());
        future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .whenComplete((r, e) -> pending.remove(id));
        try {
            return future.get();
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof AcpTimeoutException at) {
                throw at;
            }
            if (cause instanceof AcpRpcException ar) {
                throw ar;
            }
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw ee;
        }
    }

    /** 回应 server→client 请求(写 result)。 */
    void respondToServer(long id, JsonObject result) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("jsonrpc", KimiAcpProtocol.JSONRPC_VERSION);
        envelope.addProperty("id", id);
        envelope.add("result", result != null ? result : new JsonObject());
        writeLine(envelope.toString());
    }

    /** 回应 server→client 请求的 error。 */
    void respondErrorToServer(long id, int code, String message) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("jsonrpc", KimiAcpProtocol.JSONRPC_VERSION);
        envelope.addProperty("id", id);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message != null ? message : "error");
        envelope.add("error", error);
        writeLine(envelope.toString());
    }

    /** 累积的 stderr 诊断文本(异常退出时用)。 */
    String stderrDiagnostic() {
        return stderrBuffer.toString();
    }

    /**
     * 关闭连接:先 close stdin(触发优雅 EOF),等待进程退出(最多 {@link #GRACEFUL_CLOSE_TIMEOUT_MS}),
     * 超时走 {@link CliProcessLifecycle#terminate} 兜底。最后 reject 全部 pending。
     */
    /** 长驻连接是否仍可复用(未关闭且进程存活)。 */
    boolean isAlive() {
        return !closed && process != null && process.isAlive();
    }

    /**
     * 发送 session/cancel notification(无 id,不要求响应)。
     * kimi 实测:发此 notification 后进行中的 turn 以 stopReason="cancelled" 结束,
     * 进程保持(不杀)——用于长驻 interrupt 的优雅取消。
     */
    void sendSessionCancel(String sessionId) {
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);
        JsonObject envelope = new JsonObject();
        envelope.addProperty("jsonrpc", "2.0");
        envelope.addProperty("method", "session/cancel");
        envelope.add("params", params);
        writeLine(envelope.toString());
    }

    void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            synchronized (stdinLock) {
                OutputStream os = process.getOutputStream();
                if (os != null) {
                    os.close();
                }
            }
            notifyStdinClose();
        } catch (Exception ignored) {
            // 进程可能已退出
        }
        try {
            if (!process.waitFor(GRACEFUL_CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                notifyTerminate();
                CliProcessLifecycle.terminate(process);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            notifyTerminate();
            CliProcessLifecycle.terminate(process);
        }
        rejectAllPending("connection closed");
    }

    /** 优雅关闭失败的兜底(interrupt 时杀进程树后,让阻塞中的 request 立即失败)。 */
    void abortActiveRequests() {
        rejectAllPending("aborted");
    }

    /** 标记外部进程树终止，保持每个物理进程只发一次 TERMINATE。 */
    void markTerminated() {
        notifyTerminate();
    }

    // ── 内部 ─────────────────────────────────────────────────────────────────

    private void drainStdout() {
        try (CliOutputLimits.BoundedLineReader reader =
                     new CliOutputLimits.BoundedLineReader(process.getInputStream())) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    route(line);
                } catch (Exception e) {
                    LOG.warn("[KimiAcp] route failed for line (len=" + line.length() + ")", e);
                }
            }
        } catch (Exception e) {
            if (!closed) {
                LOG.warn("[KimiAcp] stdout drain ended", e);
            }
        } finally {
            notifyStdoutEof();
            rejectAllPending("stdout closed");
        }
    }

    private void drainStderr() {
        try {
            byte[] buf = new byte[8192];
            int n;
            java.io.InputStream err = process.getErrorStream();
            while ((n = err.read(buf)) != -1) {
                String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                appendStderr(chunk);
                if (stderrSink != null) {
                    // 按行喂 stderrSink(诊断日志)
                    for (String l : chunk.split("\\R")) {
                        if (!l.isBlank()) {
                            stderrSink.accept(l);
                        }
                    }
                }
            }
        } catch (Exception e) {
            if (!closed) {
                LOG.debug("[KimiAcp] stderr drain ended", e);
            }
        }
    }

    private void appendStderr(String chunk) {
        synchronized (stderrBuffer) {
            stderrBuffer.append(chunk);
            int overflow = stderrBuffer.length() - STDERR_BUFFER_LIMIT;
            if (overflow > 0) {
                stderrBuffer.delete(0, overflow);
            }
        }
    }

    private void route(String line) {
        JsonObject msg;
        try {
            msg = JsonParser.parseString(line).getAsJsonObject();
        } catch (Exception e) {
            // 非法 JSON 行(可能是 stderr 误入 stdout 或进程噪声),不致死
            if (stderrSink != null) {
                stderrSink.accept("[non-json-stdout] " + line);
            }
            return;
        }

        boolean hasMethod = msg.has("method");
        boolean hasId = msg.has("id") && !msg.get("id").isJsonNull();
        boolean hasResult = msg.has("result");
        boolean hasError = msg.has("error");

        // ① server→client 请求:有 method + 有 id + 无 result/error
        if (hasMethod && hasId && !hasResult && !hasError) {
            long id = msg.get("id").getAsLong();
            String method = msg.get("method").getAsString();
            JsonObject params = msg.has("params") && msg.get("params").isJsonObject()
                    ? msg.getAsJsonObject("params") : new JsonObject();
            try {
                JsonObject result = responder.respond(method, params);
                respondToServer(id, result != null ? result : new JsonObject());
            } catch (Exception e) {
                LOG.warn("[KimiAcp] server request responder failed: " + method, e);
                // 兜底:权限请求等必须回应否则 turn 挂死,这里回 cancelled outcome
                respondToServer(id, cancelledOutcome());
            }
            return;
        }

        // ② 响应:有 id + 有 result 或 error → complete pending
        if (hasId && (hasResult || hasError)) {
            long id = msg.get("id").getAsLong();
            CompletableFuture<JsonObject> f = pending.remove(id);
            if (f == null) {
                // 未知响应(可能超时已移除),忽略
                return;
            }
            if (hasError) {
                JsonObject err = msg.getAsJsonObject("error");
                int code = err.has("code") ? err.get("code").getAsInt() : -1;
                String message = err.has("message") ? err.get("message").getAsString() : "unknown error";
                f.completeExceptionally(new AcpRpcException(code, message));
            } else {
                f.complete(msg.getAsJsonObject("result"));
            }
            return;
        }

        // ③ 通知:有 method 无 id → 转 lineSink(即 parser.parseLine)
        if (hasMethod) {
            if (lineSink != null) {
                lineSink.accept(line);
            }
            return;
        }

        // 未知形态,忽略
    }

    private void writeLine(String json) {
        synchronized (stdinLock) {
            try {
                OutputStream os = process.getOutputStream();
                if (os == null) {
                    throw new IllegalStateException("process stdin unavailable");
                }
                PrintWriter writer = new PrintWriter(new java.io.OutputStreamWriter(os, StandardCharsets.UTF_8));
                writer.write(json);
                writer.write('\n');
                writer.flush();
            } catch (Exception e) {
                throw new RuntimeException("failed to write ACP line: " + e.getMessage(), e);
            }
        }
    }

    private void rejectAllPending(String reason) {
        for (CompletableFuture<JsonObject> f : pending.values()) {
            f.completeExceptionally(new AcpConnectionClosedException(reason));
        }
        pending.clear();
    }

    private void notifyStdinClose() {
        if (stdinClosed.compareAndSet(false, true) && lifecycleCallbacks != null) {
            lifecycleCallbacks.onStdinClose();
        }
    }

    private void notifyStdoutEof() {
        if (stdoutEof.compareAndSet(false, true) && lifecycleCallbacks != null) {
            lifecycleCallbacks.onStdoutEof();
        }
    }

    private void notifyExit() {
        if (exitObserved.compareAndSet(false, true) && lifecycleCallbacks != null) {
            int exitCode = -1;
            try {
                exitCode = process.exitValue();
            } catch (IllegalThreadStateException ignored) {
                // onExit normally guarantees termination; keep diagnostics defensive.
            }
            lifecycleCallbacks.onExit(exitCode);
        }
    }

    private void notifyTerminate() {
        if (terminated.compareAndSet(false, true) && lifecycleCallbacks != null) {
            lifecycleCallbacks.onTerminate();
        }
    }

    private static JsonObject cancelledOutcome() {
        JsonObject outcome = new JsonObject();
        JsonObject inner = new JsonObject();
        inner.addProperty("outcome", "cancelled");
        outcome.add("outcome", inner);
        return outcome;
    }

    // ── 异常 ─────────────────────────────────────────────────────────────────

    static final class AcpTimeoutException extends RuntimeException {
        static final String CODE = "ACP_TIMEOUT";

        AcpTimeoutException(String message) {
            super(message);
        }

        String code() {
            return CODE;
        }
    }

    static final class AcpRpcException extends RuntimeException {
        final int code;

        AcpRpcException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** 连接关闭/进程退出导致 request 无法完成(interrupt 杀进程后让阻塞 request 立即失败)。 */
    static final class AcpConnectionClosedException extends RuntimeException {
        AcpConnectionClosedException(String reason) {
            super(reason);
        }
    }
}
