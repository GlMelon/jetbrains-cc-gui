package com.github.claudecodegui.session;

import com.github.claudecodegui.session.runtime.ProviderType;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;

import java.util.List;

/**
 * 会话能力弹窗 MCP 面板数据源(策略注册表范式,总则五):
 * 每个 provider 的 MCP 观测方式不同(claude/codex/opencode 读 melon gateway 状态,
 * kimi 读 CLI 落盘 wire 的 tools_discovered 事件),各实现一个 source,
 * 由 {@link SessionMcpSourceRegistry} 按 {@link #provider()} 路由,
 * {@link SessionCapabilityService} 不感知具体来源。
 */
public interface SessionMcpSource {

    /** 路由键:本 source 负责的 provider。 */
    ProviderType provider();

    /**
     * 采集该会话实际加载的 MCP 服务条目(字段契约见
     * {@link com.github.claudecodegui.protocol.payload.SessionMcpCapabilityPayloadField})。
     */
    McpPanelData collect(Project project, ClaudeSession session);

    /**
     * MCP 面板数据。{@code mcpError} 为 null 时序列化为 JsonNull(payload 契约可空字段)。
     */
    record McpPanelData(boolean available, String mcpError, List<JsonObject> items) {
        public static McpPanelData unavailable() {
            return new McpPanelData(false, null, List.of());
        }
    }
}
