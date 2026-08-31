package com.github.claudecodegui.cli.opencode;

import com.github.claudecodegui.cli.CliSendRequest;
import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.common.AbstractRunOnceCliSession;
import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.cli.common.CliErrorFormatter;
import com.github.claudecodegui.cli.common.CliStreamParser;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.service.lifecycle.LifecycleObservabilityService;
import com.github.claudecodegui.session.runtime.ProviderType;

import java.util.List;

/**
 * OpenCode CLI 会话(NDJSON 事件流变体)。
 * <p>
 * 会话骨架(send/runOnce/进程生命周期/B13 续接重试/能力透传)全部在
 * {@link AbstractRunOnceCliSession}(合并自 grok/kimi/pi/opencode 四同构实现)。
 * 本类只保留 OpenCode 相对 marker 协议的三处真实差异:
 * <ol>
 *   <li>{@code --format json} 输出 NDJSON 事件(非 {@code [TAG]} marker),行分流在
 *       {@link #dispatchLine} 覆写:非 {@code {} 行发 MCP 降级提示并收集 diagnostic,不进解析器;</li>
 *   <li>showThinking 开启时带 {@code --thinking}(请求推理文本事件输出,见
 *       {@link OpenCodeCliStreamParser} EVENT_REASONING 分支);</li>
 *   <li>npm 包目录为 {@code "opencode-ai"}(非裸名)。</li>
 * </ol>
 * 事件解析委托 {@link OpenCodeCliStreamParser}。sessionID 从事件流顶层提取,以 {@code -s} 续接。
 */
public class OpenCodeCliSession extends AbstractRunOnceCliSession {

    public OpenCodeCliSession(String tabId) {
        this(tabId, null);
    }

    public OpenCodeCliSession(String tabId, McpGatewayService gatewayService) {
        super(ProviderType.OPENCODE, tabId, gatewayService);
    }

    public OpenCodeCliSession(String tabId, McpGatewayService gatewayService,
                              LifecycleObservabilityService lifecycleService) {
        super(ProviderType.OPENCODE, tabId, gatewayService, lifecycleService);
    }

    @Override
    protected CliStreamParser createParser(CliSessionCallback callback) {
        return new OpenCodeCliStreamParser(callback);
    }

    @Override
    protected String npmDir() {
        return "opencode-ai";
    }

    /**
     * NDJSON 行分流:非 JSON 行(启动 banner / 错误噪声)先发 MCP 连接失败降级提示
     * (每轮去重,该路径 exit0+success 本就不报错,仅补 toast),再收集到 diagnostic
     * 供错误上报,且不交解析器;JSON 事件行交解析器。
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

    /**
     * showThinking 开启时带 --thinking:让 opencode run --format json 输出 type:"reasoning" 文本事件
     * (parser EVENT_REASONING 分支据此推送思考区)。--thinking(是否输出推理文本)与 --variant
     * (推理强度)正交,故开关关闭时仍保留 variant,只不请求明文 thinking。
     * 对齐 SDK 路径(message.part.updated reasoning 文本透传)。详见 OpenCodeCliStreamParser.handleReasoning。
     */
    @Override
    protected void appendExtraRunFlags(CliSendRequest request, List<String> cmd) {
        if (request.thinkingOutputEnabled()) {
            cmd.add(CliConstants.OPENCODE_ARG_THINKING);
        }
    }
}
