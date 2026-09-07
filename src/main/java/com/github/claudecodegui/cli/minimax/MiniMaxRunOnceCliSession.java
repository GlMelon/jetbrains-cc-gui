package com.github.claudecodegui.cli.minimax;

import com.github.claudecodegui.cli.CliSendRequest;
import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.CliToolId;
import com.github.claudecodegui.cli.common.AbstractRunOnceCliSession;
import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.cli.common.CliErrorFormatter;
import com.github.claudecodegui.cli.common.CliStreamParser;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.service.lifecycle.LifecycleObservabilityService;
import com.github.claudecodegui.session.runtime.ProviderType;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * MiniMax Code(mcode)CLI 直 spawn 会话({@code exec --output-format stream-json} 方言)。
 * <p>
 * 命令布局(同源移植自上游 ai-bridge/services/minimax/message-service.js,实测 mcode 0.2.x;
 * 参数面已对齐官方 exec 文档——agent.minimaxi.com/docs/cli/features):
 * {@code mcode exec --output-format stream-json --permission <policy>
 * [--model <provider/model>] [--session <id>] [--cwd <dir>] [--file <path>]... "<prompt>"}。
 * 与基类 opencode 默认布局的有意差异(总则六记录):
 * <ul>
 *   <li>子命令 + 位置参数 prompt(exec ... "<prompt>"),无 run/--format;</li>
 *   <li>permissionMode 映射 mcode --permission 策略:bypassPermissions→off、
 *       acceptEdits/autoEdit→full、其余(default/plan/auto 等)→smart;headless exec
 *       无法回答交互式审批,ask 永不使用(对称上游 resolvePermissionPolicy);</li>
 *   <li>续接用 --session <id>(事件流 exec.result 回传 sessionId);</li>
 *   <li>exec.result 结果行后 CLI 不退出:经 {@link #isCompletionMarkerLine} 钩子
 *       确定性终止进程树(对称 ai-bridge runCliStreaming.shouldTerminate);</li>
 *   <li>reasoningEffort 不映射(mcode 推理由模型驱动,上游同);</li>
 *   <li>图片附件走官方 {@code --file <path>} 原生参数(可重复使用——官方多模态入口,
 *       mcode exec 文档参数表;附件已由基类物化为本地临时文件);</li>
 *   <li>MCP gateway 注入不适用(mcode 无插件 MCP 面)。</li>
 * </ul>
 */
public class MiniMaxRunOnceCliSession extends AbstractRunOnceCliSession {

    private static final Set<String> MODEL_SENTINELS = Set.of(
            "__config_default__", "auto", "default", "(default)", "config-default", "config_default");

    public MiniMaxRunOnceCliSession(String tabId) {
        this(tabId, null);
    }

    public MiniMaxRunOnceCliSession(String tabId, McpGatewayService gatewayService) {
        super(ProviderType.MINIMAX, tabId, gatewayService);
    }

    public MiniMaxRunOnceCliSession(String tabId, McpGatewayService gatewayService,
                                    LifecycleObservabilityService lifecycleService) {
        super(ProviderType.MINIMAX, tabId, gatewayService, lifecycleService);
    }

    @Override
    protected CliStreamParser createParser(CliSessionCallback callback) {
        return new MiniMaxCliStreamParser(callback);
    }

    /** 双二进制名:主命令 {@code mcode}(官方安装器/npm 均此名),防御性回退 {@code minimax}(SSOT:CliToolId)。 */
    @Override
    protected String altCliCommand() {
        return CliToolId.MINIMAX.getAltBinaryName();
    }

    /** exec.result 结果行后 mcode 仍空转——命中即由基类确定性终止进程树。 */
    @Override
    protected boolean isCompletionMarkerLine(String line) {
        return line != null && line.contains(CliConstants.MINIMAX_COMPLETION_MARKER);
    }

    /** NDJSON 行分流(对称 Pi/OpenCode):JSON 行交解析器,噪声进 diagnostic。 */
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

        List<String> cmd = new ArrayList<>();
        cmd.add(resolver().findExecutable());
        cmd.add(CliConstants.MINIMAX_ARG_EXEC);
        cmd.add(CliConstants.MINIMAX_ARG_OUTPUT_FORMAT);
        cmd.add(CliConstants.MINIMAX_FORMAT_STREAM_JSON);
        cmd.add(CliConstants.MINIMAX_ARG_PERMISSION);
        cmd.add(resolvePermissionPolicy(request.permissionMode()));

        String modelFlag = resolveModelFlag(request.actualModel() != null && !request.actualModel().isBlank()
                ? request.actualModel() : request.model());
        if (modelFlag != null) {
            cmd.add(CliConstants.MINIMAX_ARG_MODEL);
            cmd.add(modelFlag);
        }
        if (effectiveSessionId != null && !effectiveSessionId.isBlank()) {
            cmd.add(CliConstants.MINIMAX_ARG_SESSION);
            cmd.add(effectiveSessionId.trim());
        }
        if (request.cwd() != null && !request.cwd().isBlank()) {
            cmd.add(CliConstants.MINIMAX_ARG_CWD);
            cmd.add(request.cwd());
        }
        // 附件走官方 --file 原生参数(可重复使用,mcode 官方多模态入口;
        // 不再用 prompt 注入路径指引——那是给无原生附件参数的 CLI 的 kimi/pi 方案)。
        if (attachFiles != null) {
            for (File attachFile : attachFiles) {
                cmd.add(CliConstants.MINIMAX_ARG_FILE);
                cmd.add(attachFile.getAbsolutePath());
            }
        }

        // 消息是末尾位置参数:防御性前置空格防 flag 注入(对称基类 safePromptArg)
        cmd.add(safePromptArg(prompt));
        return cmd;
    }

    /**
     * CCGUI 权限模式 → mcode --permission 策略(ask|smart|full|off)。
     * headless exec 无法回答交互式审批提示,ask 永不使用(上游 resolvePermissionPolicy 同语义)。
     */
    static String resolvePermissionPolicy(String permissionMode) {
        String value = permissionMode == null ? "" : permissionMode.trim().toLowerCase(Locale.ROOT);
        if (value.equals(CommonConstants.PERMISSION_MODE_BYPASS.toLowerCase(Locale.ROOT))
                || value.equals("bypass")
                || value.equals("dangerouslyskippedpermissions")
                || value.equals("yolo")
                || value.equals(CliConstants.MINIMAX_PERMISSION_OFF)) {
            return CliConstants.MINIMAX_PERMISSION_OFF;
        }
        if (value.equals(CommonConstants.PERMISSION_MODE_ACCEPT_EDITS.toLowerCase(Locale.ROOT))
                || value.equals(CommonConstants.PERMISSION_MODE_AUTO_EDIT.toLowerCase(Locale.ROOT))
                || value.equals("acceptall")
                || value.equals(CliConstants.MINIMAX_PERMISSION_FULL)) {
            return CliConstants.MINIMAX_PERMISSION_FULL;
        }
        return CliConstants.MINIMAX_PERMISSION_SMART;
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
