package com.github.claudecodegui.cli.kimi;

import com.github.claudecodegui.cli.CliSendRequest;
import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.common.AbstractRunOnceCliSession;
import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.cli.common.CliErrorFormatter;
import com.github.claudecodegui.cli.common.CliImagePromptInjections;
import com.github.claudecodegui.cli.common.CliStreamParser;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.session.SessionCapabilityDegradationReason;
import com.github.claudecodegui.session.SessionNegotiatedCapabilities;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Kimi Code CLI 直 spawn 会话(stream-json 方言)。
 * <p>
 * 命令布局(对齐 kimi-command 官方参考,非交互 {@code -p} 等价物):
 * {@code kimi --output-format stream-json --prompt "<text>" [--model <alias>] [--session <id>]}。
 * 与基类 opencode 默认布局的有意差异(总则六记录):
 * <ul>
 *   <li>输出格式 stream-json;消息经 --prompt 传入(位置参数是 pi 的用法);</li>
 *   <li>{@code reasoningEffort} 不透传(kimi headless 无 effort/thinking flag;官方限制
 *       thinking 不进 JSONL,思考区暂不支持);</li>
 *   <li>续接用 --session(server 分配 id,resume_hint 回传);无 -c(--continue 与之互斥且
 *       插件有显式 sessionId);</li>
 *   <li>permissionMode 不映射独立 flag:非交互模式官方固定 auto 权限(static deny 规则仍生效);
 *       bypass/default 无差异可表达;</li>
 *   <li>图片附件以 ReadMediaFile 路径标签注入 prompt(CliImagePromptInjections,
 *       对称旧 bridge 行为);</li>
 *   <li>无 --dir(ProcessBuilder directory 已对全部 provider 等价生效)。</li>
 * </ul>
 */
public class KimiRunOnceCliSession extends AbstractRunOnceCliSession {

    private static final Set<String> MODEL_SENTINELS = Set.of(
            "__config_default__", "auto", "default", "(default)", "config-default", "config_default");

    private final SessionCapabilityDegradationReason degradationReason;

    public KimiRunOnceCliSession(String tabId) {
        this(tabId, null, SessionCapabilityDegradationReason.LEGACY_FALLBACK);
    }

    public KimiRunOnceCliSession(String tabId, McpGatewayService gatewayService) {
        this(tabId, gatewayService, SessionCapabilityDegradationReason.LEGACY_FALLBACK);
    }

    public KimiRunOnceCliSession(String tabId, McpGatewayService gatewayService,
                                 SessionCapabilityDegradationReason degradationReason) {
        super(ProviderType.KIMI, tabId, gatewayService);
        this.degradationReason = degradationReason == null
                ? SessionCapabilityDegradationReason.LEGACY_FALLBACK : degradationReason;
    }

    @Override
    public SessionNegotiatedCapabilities capabilities() {
        return SessionNegotiatedCapabilities.kimiLegacy(degradationReason, false);
    }

    @Override
    protected CliStreamParser createParser(CliSessionCallback callback) {
        return new KimiCliStreamParser(callback);
    }

    /** NDJSON 行分流(镜像 OpenCodeCliSession):JSON 行交解析器,噪声走 MCP 降级提示 + diagnostic。 */
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
        String prompt = buildPromptText(request);
        if (attachFiles != null && !attachFiles.isEmpty()) {
            prompt = CliImagePromptInjections.buildKimiPromptWithImages(prompt, attachFiles);
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(resolver().findExecutable());
        cmd.add(CliConstants.KIMI_ARG_OUTPUT_FORMAT);
        cmd.add(CliConstants.KIMI_FORMAT_STREAM_JSON);
        cmd.add(CliConstants.KIMI_ARG_PROMPT);
        cmd.add(safePromptArg(prompt));

        String modelFlag = resolveModelFlag(request.actualModel() != null && !request.actualModel().isBlank()
                ? request.actualModel() : request.model());
        if (modelFlag != null) {
            cmd.add(CliConstants.KIMI_ARG_MODEL);
            cmd.add(modelFlag);
        }
        if (effectiveSessionId != null && !effectiveSessionId.isBlank()) {
            cmd.add(CliConstants.KIMI_ARG_SESSION);
            cmd.add(effectiveSessionId.trim());
        }
        return cmd;
    }

    static String resolveModelFlag(String model) {
        if (model == null) {
            return null;
        }
        String trimmed = model.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return MODEL_SENTINELS.contains(lower) ? null : trimmed;
    }
}
