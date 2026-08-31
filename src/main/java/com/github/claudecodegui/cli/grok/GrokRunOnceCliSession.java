package com.github.claudecodegui.cli.grok;

import com.github.claudecodegui.cli.CliSendRequest;
import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.common.AbstractRunOnceCliSession;
import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.cli.common.CliErrorFormatter;
import com.github.claudecodegui.cli.common.CliStreamParser;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.service.lifecycle.LifecycleObservabilityService;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.util.PlatformUtils;
import com.intellij.openapi.diagnostic.Logger;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Grok CLI 直 spawn 会话(headless streaming-json 方言)。
 * <p>
 * 命令布局(对齐 docs.x.ai headless 模式):
 * {@code grok --output-format streaming-json --always-approve -p "<text>"
 * [-m profile] [--reasoning-effort low|medium|high] (-s <new-uuid> | -r <existing>)}。
 * 与基类 opencode 默认布局的有意差异(总则六记录):
 * <ul>
 *   <li>输出格式为 streaming-json 而非 run/--format json;</li>
 *   <li>{@code --always-approve} 无条件携带——headless 流式无审批交互通道,不带会卡死在 TUI 审批,
 *       故与 opencode 的「仅 bypass 才加 --auto」不同;</li>
 *   <li>首轮预分配 UUID 以 {@code -s} 创建命名会话(GUI 可续接),续轮以 {@code -r} 恢复;</li>
 *   <li>无 --dir(ProcessBuilder directory 已对全部 provider 等价生效);</li>
 *   <li>图片附件暂不透传(grok headless 无已知附件 flag,对齐旧 bridge 行为,后续随 ACP 接入补齐);
 *       permissionMode 不映射独立 flag(--always-approve 已覆盖 bypass 语义)。</li>
 * </ul>
 * 工具调用来自 chat_history.jsonl 尾随(stdout 无工具事件),由 {@link GrokToolHistoryTailer}
 * 在辅助监视器里周期注入 {@link GrokCliStreamParser}。
 */
public class GrokRunOnceCliSession extends AbstractRunOnceCliSession {

    private static final Logger LOG = Logger.getInstance(GrokRunOnceCliSession.class);
    private static final long TOOL_POLL_INTERVAL_MS = 300;
    /** ~/.grok/config.toml 默认 profile 名(UI 曾把上游模型 id 误存为 model 的兼容映射)。 */
    private static final String GROK_DEFAULT_PROFILE_ID = "grok";
    private static final Set<String> MODEL_SENTINELS = Set.of(
            "__config_default__", "auto", "default", "(default)", "config-default", "config_default");

    /** 本轮已生效的历史路径参数(resume 时为既有 id,首轮为预分配的新 UUID)。 */
    private volatile String runHistorySessionId;
    private volatile boolean runIsResume;
    private volatile String runEffectiveCwd;

    /** 本轮辅助监视器状态(仅 send 执行线程读写,volatile 防御 stop 与 start 交错)。 */
    private volatile GrokToolHistoryTailer activeTailer;
    private volatile GrokCliStreamParser activeParser;
    private volatile ScheduledExecutorService toolPollExecutor;
    private final AtomicBoolean auxStopped = new AtomicBoolean(true);

    public GrokRunOnceCliSession(String tabId) {
        this(tabId, null);
    }

    public GrokRunOnceCliSession(String tabId, McpGatewayService gatewayService) {
        super(ProviderType.GROK, tabId, gatewayService);
    }

    public GrokRunOnceCliSession(String tabId, McpGatewayService gatewayService,
                                 LifecycleObservabilityService lifecycleService) {
        super(ProviderType.GROK, tabId, gatewayService, lifecycleService);
    }

    @Override
    protected CliStreamParser createParser(CliSessionCallback callback) {
        return new GrokCliStreamParser(callback);
    }

    /**
     * NDJSON 行分流(镜像 OpenCodeCliSession):JSON 事件行交解析器;
     * 其余(banner/噪声)先发 MCP 降级提示再收集 diagnostic。tailer 合成的
     * [MESSAGE] marker 行不经此路径(stop/start 钩子直接调 parseLine)。
     */
    @Override
    protected void dispatchLine(String line, CliStreamParser parser, StringBuilder diagnostic) {
        if (!line.trim().startsWith("{")) {
            parser.emitMcpNoticeIfMatched(line);
            CliErrorFormatter.appendDiagnosticLine(diagnostic, line);
            return;
        }
        parser.parseLine(line);
    }

    @Override
    public List<String> buildRunCommand(CliSendRequest request, String effectiveSessionId, List<File> attachFiles) {
        runEffectiveCwd = resolveEffectiveCwd(request.cwd());
        String existingId = isUuid(effectiveSessionId) ? effectiveSessionId.trim() : null;
        runIsResume = existingId != null;
        if (runIsResume) {
            runHistorySessionId = existingId;
        } else {
            // 首轮预分配 UUID:-s 创建命名 headless 会话,后续轮即可 -r 续接。
            runHistorySessionId = UUID.randomUUID().toString();
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(resolver().findExecutable());
        cmd.add(CliConstants.GROK_ARG_OUTPUT_FORMAT);
        cmd.add(CliConstants.GROK_FORMAT_STREAMING_JSON);
        cmd.add(CliConstants.GROK_ARG_ALWAYS_APPROVE);
        cmd.add(CliConstants.GROK_ARG_PROMPT);
        cmd.add(safePromptArg(buildPromptText(request)));

        String modelFlag = resolveModelFlag(request.actualModel() != null && !request.actualModel().isBlank()
                ? request.actualModel() : request.model());
        if (modelFlag != null) {
            cmd.add(CliConstants.GROK_ARG_MODEL);
            cmd.add(modelFlag);
        }
        String effort = normalizeEffort(request.reasoningEffort());
        if (effort != null) {
            cmd.add(CliConstants.GROK_ARG_REASONING_EFFORT);
            cmd.add(effort);
        }
        if (runIsResume) {
            cmd.add(CliConstants.GROK_ARG_RESUME);
        } else {
            cmd.add(CliConstants.GROK_ARG_SESSION_ID);
        }
        cmd.add(runHistorySessionId);
        return cmd;
    }

    @Override
    protected void onStartAuxiliary(Process process, CliStreamParser parser) {
        if (!(parser instanceof GrokCliStreamParser grokParser)) {
            return;
        }
        GrokToolHistoryTailer tailer = new GrokToolHistoryTailer(
                resolveGrokHome(), runEffectiveCwd, runHistorySessionId, runIsResume);
        if (tailer.historyPath() == null) {
            return;
        }
        activeTailer = tailer;
        activeParser = grokParser;
        auxStopped.set(false);
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "grok-tool-tail-" + tabId);
            t.setDaemon(true);
            return t;
        });
        this.toolPollExecutor = executor;
        executor.scheduleAtFixedRate(() -> {
            try {
                GrokToolHistoryTailer t = activeTailer;
                if (t != null) {
                    ingestTailLines(t.pollOnce());
                }
            } catch (Exception e) {
                LOG.debug("[GrokCliSession][" + tabId + "] tool poll failed: " + e.getMessage());
            }
        }, TOOL_POLL_INTERVAL_MS, TOOL_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 停止辅助监视器并做最终 drain(await 返回后由基类调用):尾部工具结果必须
     * 先于 stream_end 注入解析器,前端才能在流关闭前看到完整工具卡片,最后
     * {@link GrokCliStreamParser#finishStream()} 收尾。幂等(start 未执行时直接跳过)。
     */
    @Override
    protected void onStopAuxiliary() {
        if (!auxStopped.compareAndSet(false, true)) {
            return;
        }
        ScheduledExecutorService executor = toolPollExecutor;
        toolPollExecutor = null;
        if (executor != null) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                    LOG.debug("[GrokCliSession][" + tabId + "] tool tail executor did not stop cleanly");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        GrokToolHistoryTailer tailer = activeTailer;
        GrokCliStreamParser parser = activeParser;
        activeTailer = null;
        activeParser = null;
        if (tailer == null || parser == null) {
            return;
        }
        try {
            ingestTailLines(tailer.pollOnce());
        } catch (Exception e) {
            LOG.debug("[GrokCliSession][" + tabId + "] final tool drain failed: " + e.getMessage());
        }
        synchronized (parser) {
            parser.finishStream();
        }
    }

    private void ingestTailLines(List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        GrokCliStreamParser parser = activeParser;
        if (parser == null) {
            return;
        }
        synchronized (parser) {
            for (String line : lines) {
                parser.parseLine(line);
            }
        }
    }

    private static String resolveEffectiveCwd(String cwd) {
        if (cwd != null && !cwd.isBlank() && new File(cwd).isDirectory()) {
            return cwd;
        }
        return PlatformUtils.getHomeDirectory();
    }

    static Path resolveGrokHome() {
        String env = System.getenv("GROK_HOME");
        if (env != null && !env.isBlank()) {
            return Path.of(env.trim());
        }
        return Path.of(PlatformUtils.getHomeDirectory(), ".grok");
    }

    /**
     * CLI {@code -m} 取值解析(对称 JS resolveGrokModelFlag):哨兵/空省略(-m 走
     * [models].default);legacy 上游 id(grok-4.5/grok-4/grok-4.5-build)重映射为默认 profile 名。
     */
    static String resolveModelFlag(String model) {
        if (model == null) {
            return null;
        }
        String trimmed = model.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (MODEL_SENTINELS.contains(lower)) {
            return null;
        }
        if (lower.equals("grok-4.5") || lower.equals("grok-4") || lower.equals("grok-4.5-build")) {
            return GROK_DEFAULT_PROFILE_ID;
        }
        return trimmed;
    }

    static String normalizeEffort(String effort) {
        if (effort == null) {
            return null;
        }
        String trimmed = effort.trim().toLowerCase(Locale.ROOT);
        return switch (trimmed) {
            case "low", "medium", "high" -> trimmed;
            default -> null;
        };
    }

    private static boolean isUuid(String value) {
        if (value == null) {
            return false;
        }
        return value.trim().matches(
                "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");
    }
}
