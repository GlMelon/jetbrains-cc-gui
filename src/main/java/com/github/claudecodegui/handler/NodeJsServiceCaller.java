package com.github.claudecodegui.handler;

import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.bridge.ProcessManager;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.util.PlatformUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Handles Node.js subprocess calls for favorites and session titles services.
 * <p>
 * Extracted from HistoryHandler to encapsulate all Node.js process invocation logic
 * for favorites-service.cjs and session-titles-service.cjs.
 *
 * <h3>S3 加固(2026-07-17)</h3>
 * {@link #executeNodeScript} 原实现有三个缺陷,本轮已根治:
 * <ol>
 *   <li><b>timeout 形同虚设</b>:原代码在主线程用 {@code readLine()} 阻塞到 stdout EOF,
 *       之后才 {@code waitFor(timeout)} —— 子进程不关 stdout 时 {@code readLine} 永不返回,
 *       根本到不了 {@code waitFor},调用线程永久挂起。现改为独立读线程读取、主线程
 *       {@code waitFor(timeout)},timeout 真正生效。</li>
 *   <li><b>stderr 污染 stdout</b>:原 {@code redirectErrorStream(true)} 把 stderr 并入 stdout,
 *       破坏 JSON 解析。现 stdout/stderr 分流,stderr 由独立守护线程 drain,仅用于错误信息。</li>
 *   <li><b>无 output cap</b>:原 {@code StringBuilder} 无限增长,超大输出 OOM。现总字节 +
 *       单行字节双约束,逐字节累计超阈即 terminate + 抛异常、不保留半条消息。</li>
 * </ol>
 * 现状已具备、本轮刻意保留的保护(见 docs/comprehensive-optimization-directions.md §S3):
 * stdout 侧 UTF-8、inline script 经 {@code node -e} 不过 shell、路径反斜杠转义
 * ({@link WslPathUtil#resolveScriptPath})、函数名白名单、{@link ProcessManager} 登记/注销。
 */
public class NodeJsServiceCaller {

    /** 默认子进程超时(秒)。测试可经包级构造注入更短值。 */
    static final int DEFAULT_PROCESS_TIMEOUT_SECONDS = 30;
    /** stdout 总字节上限(1 MiB)。超阈即丢弃、terminate、抛异常,不保留半条消息。 */
    static final int MAX_OUTPUT_BYTES = 1 * 1024 * 1024;
    /** stdout 单行字节上限(64 KiB)。逐字节累计超阈即停,防超长行 OOM。 */
    static final int MAX_LINE_BYTES = 64 * 1024;
    /** 读/drain 线程 join 上限(秒):进程结束后等待读线程读完管道尾部的宽限。 */
    private static final long READER_JOIN_SECONDS = 5;

    private static final Set<String> ALLOWED_FAVORITES_FUNCTIONS = Set.of(
        "loadFavorites", "toggleFavorite", "removeFavorite"
    );

    private static final Set<String> ALLOWED_TITLES_FUNCTIONS = Set.of(
        "loadTitles", "updateTitle", "deleteTitle"
    );

    private final HandlerContext context;
    private final ProcessManager processManager;
    private final int processTimeoutSeconds;

    public NodeJsServiceCaller(HandlerContext context) {
        this(context, context.getClaudeSDKBridge().getProcessManager(), DEFAULT_PROCESS_TIMEOUT_SECONDS);
    }

    /**
     * 包级构造,供故障注入测试注入真实 {@link ProcessManager} 与短 timeout。
     * {@code context} 可为 {@code null}(测试路径只驱动 {@link #executeNodeScript},不触碰 context)。
     */
    NodeJsServiceCaller(HandlerContext context, ProcessManager processManager, int processTimeoutSeconds) {
        this.context = context;
        this.processManager = processManager;
        this.processTimeoutSeconds = processTimeoutSeconds;
    }

    /**
     * Call Node.js favorites-service.
     */
    public String callNodeJsFavoritesService(String functionName, String sessionId) throws Exception {
        validateFunctionName(functionName, ALLOWED_FAVORITES_FUNCTIONS);

        String bridgePath = context.getClaudeSDKBridge().getSdkTestDir().getAbsolutePath();
        String nodePath = context.getClaudeSDKBridge().getNodeExecutable();
        String scriptBridgePath = NodeDetector.resolveScriptPath(nodePath, bridgePath);

        String nodeScript = String.format(
            "const { %s } = require('%s/services/favorites-service.cjs'); " +
            "const result = %s(process.env.SESSION_ID); " +
            "console.log(JSON.stringify(result));",
            functionName,
            scriptBridgePath,
            functionName
        );

        ProcessBuilder pb = buildNodeProcessBuilder(nodePath, nodeScript);
        pb.environment().put("SESSION_ID", sessionId);

        return executeNodeScript(pb);
    }

    /**
     * Call Node.js session-titles-service (no-argument version, for loadTitles).
     */
    public String callNodeJsTitlesService(String functionName) throws Exception {
        validateFunctionName(functionName, ALLOWED_TITLES_FUNCTIONS);

        String bridgePath = context.getClaudeSDKBridge().getSdkTestDir().getAbsolutePath();
        String nodePath = context.getClaudeSDKBridge().getNodeExecutable();
        String scriptBridgePath = NodeDetector.resolveScriptPath(nodePath, bridgePath);

        String nodeScript = String.format(
            "const { %s } = require('%s/services/session-titles-service.cjs'); " +
            "const result = %s(); " +
            "console.log(JSON.stringify(result));",
            functionName,
            scriptBridgePath,
            functionName
        );

        ProcessBuilder pb = buildNodeProcessBuilder(nodePath, nodeScript);

        return executeNodeScript(pb);
    }

    /**
     * Call Node.js session-titles-service (with parameters, for updateTitle).
     */
    public String callNodeJsTitlesServiceWithParams(String functionName, String sessionId, String customTitle) throws Exception {
        validateFunctionName(functionName, ALLOWED_TITLES_FUNCTIONS);

        String bridgePath = context.getClaudeSDKBridge().getSdkTestDir().getAbsolutePath();
        String nodePath = context.getClaudeSDKBridge().getNodeExecutable();
        String scriptBridgePath = NodeDetector.resolveScriptPath(nodePath, bridgePath);

        String nodeScript = String.format(
            "const { %s } = require('%s/services/session-titles-service.cjs'); " +
            "const result = %s(process.env.SESSION_ID, process.env.CUSTOM_TITLE); " +
            "console.log(JSON.stringify(result));",
            functionName,
            scriptBridgePath,
            functionName
        );

        ProcessBuilder pb = buildNodeProcessBuilder(nodePath, nodeScript);
        pb.environment().put("SESSION_ID", sessionId);
        pb.environment().put("CUSTOM_TITLE", customTitle);

        return executeNodeScript(pb);
    }

    /**
     * Call Node.js session-titles-service to delete a title (single parameter version).
     */
    public String callNodeJsDeleteTitle(String sessionId) throws Exception {
        String bridgePath = context.getClaudeSDKBridge().getSdkTestDir().getAbsolutePath();
        String nodePath = context.getClaudeSDKBridge().getNodeExecutable();
        String scriptBridgePath = NodeDetector.resolveScriptPath(nodePath, bridgePath);

        String nodeScript = String.format(
            "const { deleteTitle } = require('%s/services/session-titles-service.cjs'); " +
            "const result = deleteTitle(process.env.SESSION_ID); " +
            "console.log(JSON.stringify({ success: result }));",
            scriptBridgePath
        );

        ProcessBuilder pb = buildNodeProcessBuilder(nodePath, nodeScript);
        pb.environment().put("SESSION_ID", sessionId);

        return executeNodeScript(pb);
    }

    /**
     * Build a ProcessBuilder for running a Node.js inline script.
     * Delegates to {@link NodeDetector#buildNodeInlineCommand} so WSL prefixing is centralised.
     */
    private ProcessBuilder buildNodeProcessBuilder(String nodePath, String nodeScript) {
        return new ProcessBuilder(NodeDetector.buildNodeInlineCommand(nodePath, nodeScript));
    }

    /**
     * Validate that the function name is in the allowed set to prevent injection.
     */
    private void validateFunctionName(String functionName, Set<String> allowedFunctions) {
        if (functionName == null || !allowedFunctions.contains(functionName)) {
            throw new IllegalArgumentException(
                "Invalid function name: " + functionName + ". Allowed: " + allowedFunctions
            );
        }
    }

    /**
     * Execute a Node.js subprocess with hardened lifecycle:
     * <ul>
     *   <li>stdout/stderr 分流(stderr 不污染 stdout 的 JSON);</li>
     *   <li>stdout 由独立守护线程逐字节有界读取(总字节 + 单行双 cap);</li>
     *   <li>主线程 {@code waitFor(timeout)} —— timeout 真正生效,根治 readLine 阻塞致
     *       waitFor 永不触达的历史 bug;</li>
     *   <li>超 cap 时读线程立即 {@code destroyForcibly} 打断子进程(否则子进程会因管道
     *       写阻塞而永不退出,令主线程在 {@code waitFor} 干等 timeout),主线程抛异常、
     *       不保留半条消息;</li>
     *   <li>finally 确定性 terminate + unregister。</li>
     * </ul>
     * 包级可见以便故障注入测试直接驱动(测试自行构造 {@link ProcessBuilder})。
     * <p>
     * 注意:本方法<b>不</b>调用 {@code pb.redirectErrorStream(true)} —— ProcessBuilder 默认
     * 即 stdout/stderr 分流,合并会破坏 JSON 解析。
     */
    String executeNodeScript(ProcessBuilder pb) throws Exception {
        String channelId = ProcessManager.newChannelId("node-service");
        Process process = null;
        try {
            process = pb.start();
            processManager.registerProcess(channelId, process);

            // stderr 异步 drain 到有界缓冲(仅用于错误信息);不读会导致子进程因 stderr
            // 管道写满而阻塞。stderrBuf 由 drain 线程写、主线程在 join 后读(join 建立
            // happens-before,单写者→单读者,安全)。
            StringBuilder stderrBuf = new StringBuilder();
            CapturedOutput stdout = new CapturedOutput();
            // process 经两次赋值(= null / = pb.start())非 effectively final,lambda 无法捕获;
            // 此处取 effectively final 别名 proc 供读/drain 线程捕获。
            final Process proc = process;

            Thread stdoutReader = new Thread(
                    () -> readStdoutCapped(proc, proc.getInputStream(), stdout),
                    "node-stdout-reader");
            Thread stderrDrain = new Thread(
                    () -> drainStream(proc.getErrorStream(), stderrBuf),
                    "node-stderr-drain");
            stdoutReader.setDaemon(true);
            stderrDrain.setDaemon(true);
            stdoutReader.start();
            stderrDrain.start();

            boolean finished = process.waitFor(processTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                PlatformUtils.terminateProcess(process);
                joinQuietly(stdoutReader, READER_JOIN_SECONDS);
                joinQuietly(stderrDrain, READER_JOIN_SECONDS);
                throw new Exception("Node.js process timed out after " + processTimeoutSeconds + " seconds");
            }

            // 进程已结束:等读线程读完管道尾部,再判定 cap 与退出码。
            joinQuietly(stdoutReader, READER_JOIN_SECONDS);
            joinQuietly(stderrDrain, READER_JOIN_SECONDS);

            if (stdout.overflow) {
                throw new Exception("Node.js process output exceeded size cap "
                        + "(max " + MAX_OUTPUT_BYTES + " bytes total / " + MAX_LINE_BYTES + " bytes per line)");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new Exception("Node.js process exited with code " + exitCode
                        + ": " + stderrBuf.toString().trim());
            }

            // 稳定 framing:正常路径(未超 cap)取最后一行为 JSON。
            String[] lines = stdout.builder.toString().split("\n");
            return lines.length > 0 ? lines[lines.length - 1] : "{}";
        } finally {
            if (process != null) {
                if (process.isAlive()) {
                    PlatformUtils.terminateProcess(process);
                }
                processManager.unregisterProcess(channelId, process);
            }
        }
    }

    /**
     * 逐字节有界读取 stdout:总字节({@link #MAX_OUTPUT_BYTES})与单行字节
     * ({@link #MAX_LINE_BYTES})双约束,任一超阈即置 {@code overflow}、{@code destroyForcibly}
     * 打断子进程并停止。故意不用 {@link java.io.BufferedReader#readLine()} —— 它会把超长行
     * 整段读入内存才返回,无法在超长行上防 OOM。
     * <p>
     * {@code stdout.builder} 由本(读)线程写、主线程在 join 后读(happens-before,安全)。
     */
    private static void readStdoutCapped(Process process, InputStream in, CapturedOutput stdout) {
        ByteArrayOutputStream curLine = new ByteArrayOutputStream(256);
        byte[] buf = new byte[8192];
        int totalBytes = 0;
        int n;
        try {
            while ((n = in.read(buf)) != -1) {
                for (int i = 0; i < n; i++) {
                    byte b = buf[i];
                    totalBytes++;
                    if (b == '\n') {
                        flushLine(stdout, curLine);
                        curLine.reset();
                    } else if (b != '\r') {
                        curLine.write(b);
                        if (curLine.size() > MAX_LINE_BYTES) {
                            markOverflowAndKill(process, stdout);
                            return;
                        }
                    }
                    if (totalBytes > MAX_OUTPUT_BYTES) {
                        markOverflowAndKill(process, stdout);
                        return;
                    }
                }
            }
            if (curLine.size() > 0) {
                flushLine(stdout, curLine);
            }
        } catch (IOException e) {
            // 子进程被 terminate 导致流关闭 —— 正常退出路径,不抛。
        }
    }

    /** 异步 drain stderr 到有界缓冲(仅用于错误信息),同样 cap 防 OOM。 */
    private static void drainStream(InputStream in, StringBuilder buf) {
        try (InputStream stream = in) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf8k = new byte[8192];
            int n;
            while ((n = stream.read(buf8k)) != -1) {
                baos.write(buf8k, 0, n);
                if (baos.size() > MAX_OUTPUT_BYTES) {
                    break;
                }
            }
            String s = baos.toString(StandardCharsets.UTF_8);
            if (s.length() > MAX_OUTPUT_BYTES) {
                s = s.substring(0, MAX_OUTPUT_BYTES);
            }
            buf.append(s);
        } catch (IOException e) {
            // 同上,忽略。
        }
    }

    /** cap 触发:置 overflow 并立即打断子进程,使其不再因管道写阻塞而挂住主线程的 waitFor。 */
    private static void markOverflowAndKill(Process process, CapturedOutput stdout) {
        stdout.overflow = true;
        try {
            process.destroyForcibly();
        } catch (Exception ignored) {
            // 平台感知的进程树清理由主线程 finally 兜底,此处忽略 destroy 异常。
        }
    }

    private static void flushLine(CapturedOutput stdout, ByteArrayOutputStream curLine) {
        stdout.builder.append(curLine.toString(StandardCharsets.UTF_8)).append('\n');
    }

    private static void joinQuietly(Thread t, long seconds) {
        if (t == null) {
            return;
        }
        try {
            t.join(TimeUnit.SECONDS.toMillis(seconds));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** stdout 捕获结果:累积行(带换行)+ 超阈标志。读线程写,主线程 join 后读。 */
    private static final class CapturedOutput {
        final StringBuilder builder = new StringBuilder();
        volatile boolean overflow = false;
    }
}
