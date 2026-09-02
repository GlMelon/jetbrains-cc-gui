package com.github.claudecodegui.cli.pi;

import com.github.claudecodegui.cli.CliSendRequest;
import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.common.AbstractRunOnceCliSession;
import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.cli.common.CliErrorFormatter;
import com.github.claudecodegui.cli.common.CliImagePromptInjections;
import com.github.claudecodegui.cli.common.CliStreamParser;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.service.lifecycle.LifecycleObservabilityService;
import com.github.claudecodegui.session.runtime.ProviderType;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pi CLI 直 spawn 会话({@code --print --mode json} 方言)。
 * <p>
 * 命令布局(对齐 pi.dev docs usage/json 事件流模式):
 * {@code pi --print --mode json "<positional>" [--model <pattern>] [--session-id <id>] [--thinking <level>]}。
 * 与基类 opencode 默认布局的有意差异(总则六记录):
 * <ul>
 *   <li>消息为末尾位置参数(pi 无 --prompt flag),--print/--mode 等价于 grok/kimi 的输出格式声明;</li>
 *   <li>{@code reasoningEffort} 全量映射 {@code --thinking}(off/minimal/low/medium/high/xhigh/max),
 *       由 showThinking 门控(对称 opencode --thinking 开关语义);</li>
 *   <li>续接用 --session-id(pi 官方 resume 是交互 -r,非交互续接只有显式 id;--continue/-c
 *       是「继续最近会话」,与插件按 tab 显式管理 sessionId 的模型不符);</li>
 *   <li>permissionMode 不映射:pi 无审批弹窗设计(工具直接执行);</li>
 *   <li>MCP gateway 注入不适用(pi 故意不内置 MCP,靠扩展,官方立场)。</li>
 * </ul>
 */
public class PiRunOnceCliSession extends AbstractRunOnceCliSession {

    private static final Set<String> MODEL_SENTINELS = Set.of(
            "__config_default__", "auto", "default", "(default)", "config-default", "config_default",
            "pi-default", "pi default");
    private static final Set<String> THINKING_LEVELS = Set.of(
            "off", "minimal", "low", "medium", "high", "xhigh", "max");

    public PiRunOnceCliSession(String tabId) {
        this(tabId, null);
    }

    public PiRunOnceCliSession(String tabId, McpGatewayService gatewayService) {
        super(ProviderType.PI, tabId, gatewayService);
    }

    public PiRunOnceCliSession(String tabId, McpGatewayService gatewayService,
                               LifecycleObservabilityService lifecycleService) {
        super(ProviderType.PI, tabId, gatewayService, lifecycleService);
    }

    @Override
    protected CliStreamParser createParser(CliSessionCallback callback) {
        return new PiCliStreamParser(callback);
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
            prompt = CliImagePromptInjections.buildReadPathPromptWithImages(prompt, attachFiles);
        }
        prompt = reformatFileLineReferences(prompt);

        List<String> cmd = new ArrayList<>();
        cmd.add(resolver().findExecutable());
        cmd.add(CliConstants.PI_ARG_PRINT);
        cmd.add(CliConstants.PI_ARG_MODE);
        cmd.add(CliConstants.PI_FORMAT_JSON);

        String modelFlag = resolveModelFlag(request.actualModel() != null && !request.actualModel().isBlank()
                ? request.actualModel() : request.model());
        if (modelFlag != null) {
            cmd.add(CliConstants.PI_ARG_MODEL);
            cmd.add(modelFlag);
        }
        if (effectiveSessionId != null && !effectiveSessionId.isBlank()) {
            cmd.add(CliConstants.PI_ARG_SESSION_ID);
            cmd.add(effectiveSessionId.trim());
        }
        // showThinking 开启时透传 thinking 级别(off 显式关闭思考,其余为强度)
        if (request.thinkingOutputEnabled()) {
            String level = resolveThinkingLevel(request.reasoningEffort());
            if (level != null) {
                cmd.add(CliConstants.PI_ARG_THINKING);
                cmd.add(level);
            }
        }

        // 消息是位置参数(pi 无 --prompt):防御性前置空格防 flag 注入
        cmd.add(safePromptArg(prompt));
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

    /**
     * Claude 风格行号引用重写:{@code @path#L1[-L2]} → {@code @path (lines 1[-2])}。
     * pi 无法解析该形态(pi 不展开 prompt 内 @-mention,token 对模型只是纯文本);
     * 重写后 mention 可解析、行号信息以 prose 保留(对称 ai-bridge utils/file-line-references.js,
     * omp 侧同款修复落在 ai-bridge omp/message-service.js)。
     * <p>{@code @} 必须位于串首或空白之后({@code (?<!\S)}):否则词中 {@code @} 会被误改写
     * ({@code user@host.com#L5}),代码 span 内引用({@code `@x/Y.java#L3`})也会被改写。
     */
    private static final Pattern LINE_REFERENCE_PATTERN =
            Pattern.compile("(?<!\\S)@([^\\s@]+)#L(\\d+)(?:-L?(\\d+))?");

    static String reformatFileLineReferences(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = LINE_REFERENCE_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String replacement = matcher.group(3) != null
                    ? "@" + matcher.group(1) + " (lines " + matcher.group(2) + "-" + matcher.group(3) + ")"
                    : "@" + matcher.group(1) + " (lines " + matcher.group(2) + ")";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** reasoningEffort → pi --thinking level(小写归一后白名单校验)。 */
    static String resolveThinkingLevel(String reasoningEffort) {
        if (reasoningEffort == null || reasoningEffort.isBlank()) {
            return null;
        }
        String normalized = reasoningEffort.trim().toLowerCase(Locale.ROOT);
        return THINKING_LEVELS.contains(normalized) ? normalized : null;
    }
}
