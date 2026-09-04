package com.github.claudecodegui.session;

import com.github.claudecodegui.protocol.payload.SessionCapabilitiesPayloadField;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;

/** Builds the backend-owned capability snapshot shown by the chat drawer. */
public final class SessionCapabilityService {
    private static final String EMPTY = "";

    /**
     * 必须 serializeNulls:payload 契约中 lastError/lastSuccessAt/mcpError 是可空字段,
     * 前端校验器按 string|null/number|null 严格判型。Gson 默认(不序列化 null)会把
     * JsonNull 元素的键整个吞掉,前端收到的是 undefined 而非 null,整包被判失败
     * ("无法加载会话能力")。见 2026-08-21 排查:default Gson 输出 {"id":"x"},
     * serializeNulls 输出 {"id":"x","lastError":null}。
     */
    private static final Gson NULLS_GSON = new GsonBuilder().serializeNulls().create();

    private SessionCapabilityService() {
    }

    public static String build(Project project, ClaudeSession session) {
        long observedAt = System.currentTimeMillis();
        JsonObject payload = new JsonObject();
        if (session == null) {
            addEmptySession(payload, observedAt);
            return NULLS_GSON.toJson(payload);
        }


        String provider = safe(session.getProvider());
        String sessionId = safe(session.getSessionId());
        SessionNegotiatedCapabilities negotiatedCapabilities = session.getSessionCapabilities();
        payload.addProperty(SessionCapabilitiesPayloadField.SESSION_ID.wireKey(), sessionId);
        payload.addProperty(
                SessionCapabilitiesPayloadField.RUNTIME_EPOCH.wireKey(),
                safe(session.getRuntimeSessionEpoch())
        );
        payload.addProperty(SessionCapabilitiesPayloadField.PROVIDER.wireKey(), provider);
        payload.addProperty(SessionCapabilitiesPayloadField.OBSERVED_AT.wireKey(), observedAt);
        addNegotiatedCapabilities(payload, negotiatedCapabilities);
        SessionCapabilityMetadataStore.getInstance().save(
                provider,
                sessionId,
                negotiatedCapabilities,
                observedAt
        );
        // MCP 面板按 provider 路由到注册的观测源(总则五·策略注册表):
        // claude/codex/opencode 读 gateway 状态,kimi 读会话 wire 的 tools_discovered 事件;
        // 未注册的 provider(grok/pi/omp/dsh)面板不可用,空数组降级。
        SessionMcpSource source = SessionMcpSourceRegistry.forProvider(provider);
        SessionMcpSource.McpPanelData mcpData = source == null
                ? SessionMcpSource.McpPanelData.unavailable()
                : source.collect(project, session);
        JsonArray mcp = new JsonArray();
        mcpData.items().forEach(mcp::add);

        payload.addProperty(SessionCapabilitiesPayloadField.MCP_AVAILABLE.wireKey(), mcpData.available());
        if (mcpData.mcpError() == null) {
            payload.add(SessionCapabilitiesPayloadField.MCP_ERROR.wireKey(), JsonNull.INSTANCE);
        } else {
            payload.addProperty(SessionCapabilitiesPayloadField.MCP_ERROR.wireKey(), mcpData.mcpError());
        }
        payload.add(SessionCapabilitiesPayloadField.MCP.wireKey(), mcp);
        payload.add(SessionCapabilitiesPayloadField.SKILLS.wireKey(), session.getSkillSnapshot().toJson());
        return NULLS_GSON.toJson(payload);
    }

    private static void addEmptySession(JsonObject payload, long observedAt) {
        payload.addProperty(SessionCapabilitiesPayloadField.SESSION_ID.wireKey(), EMPTY);
        payload.addProperty(SessionCapabilitiesPayloadField.RUNTIME_EPOCH.wireKey(), EMPTY);
        payload.addProperty(SessionCapabilitiesPayloadField.PROVIDER.wireKey(), EMPTY);
        payload.addProperty(SessionCapabilitiesPayloadField.OBSERVED_AT.wireKey(), observedAt);
        addNegotiatedCapabilities(payload, SessionNegotiatedCapabilities.unknown());
        payload.addProperty(SessionCapabilitiesPayloadField.MCP_AVAILABLE.wireKey(), false);
        payload.add(SessionCapabilitiesPayloadField.MCP_ERROR.wireKey(), JsonNull.INSTANCE);
        payload.add(SessionCapabilitiesPayloadField.MCP.wireKey(), new JsonArray());
        payload.add(SessionCapabilitiesPayloadField.SKILLS.wireKey(), new JsonArray());
    }

    private static void addNegotiatedCapabilities(JsonObject payload, SessionNegotiatedCapabilities capabilities) {
        SessionNegotiatedCapabilities effective = capabilities == null
                ? SessionNegotiatedCapabilities.unknown() : capabilities;
        JsonObject json = effective.toJson();
        payload.add(SessionCapabilitiesPayloadField.STATE.wireKey(), json.get(SessionCapabilitiesPayloadField.STATE.wireKey()));
        payload.add(SessionCapabilitiesPayloadField.CHANNEL.wireKey(), json.get(SessionCapabilitiesPayloadField.CHANNEL.wireKey()));
        payload.add(SessionCapabilitiesPayloadField.THINKING_AVAILABLE.wireKey(),
                json.get(SessionCapabilitiesPayloadField.THINKING_AVAILABLE.wireKey()));
        payload.add(SessionCapabilitiesPayloadField.TOOLS_AVAILABLE.wireKey(),
                json.get(SessionCapabilitiesPayloadField.TOOLS_AVAILABLE.wireKey()));
        payload.add(SessionCapabilitiesPayloadField.SESSION_MCP_AVAILABLE.wireKey(),
                json.get(SessionCapabilitiesPayloadField.SESSION_MCP_AVAILABLE.wireKey()));
        payload.add(SessionCapabilitiesPayloadField.DEGRADED.wireKey(), json.get(SessionCapabilitiesPayloadField.DEGRADED.wireKey()));
        payload.add(SessionCapabilitiesPayloadField.DEGRADATION_REASON.wireKey(),
                json.get(SessionCapabilitiesPayloadField.DEGRADATION_REASON.wireKey()));
    }

    private static String safe(String value) {
        return value == null ? EMPTY : value;
    }
}
