package com.github.claudecodegui.session;

import com.github.claudecodegui.mcp.McpGatewayConstants;
import com.github.claudecodegui.provider.ProviderCapability;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.protocol.payload.SessionCapabilitiesPayloadField;
import com.github.claudecodegui.protocol.payload.SessionMcpCapabilityPayloadField;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.intellij.openapi.project.Project;

import java.util.Locale;

/** Builds the backend-owned capability snapshot shown by the chat drawer. */
public final class SessionCapabilityService {
    private static final String EMPTY = "";
    private static final String ID_SEPARATOR = ":";
    private static final String MCP_STATUS_ERROR = "Unable to read MCP Gateway status";

    /**
     * 必须 serializeNulls:payload 契约中 lastError/lastSuccessAt/mcpError 是可空字段,
     * 前端校验器按 string|null/number|null 严格判型。Gson 默认(不序列化 null)会把
     * JsonNull 元素的键整个吞掉,前端收到的是 undefined 而非 null,整包被判失败
     * ("无法加载会话能力")。见 2026-08-21 排查:default Gson 输出 {"id":"x"},
     * serializeNulls 输出 {"id":"x","lastError":null}。
     */
    private static final Gson NULLS_GSON = new GsonBuilder().serializeNulls().create();

    /**
     * 能力查询路由(懒加载静态共享,构造轻量)。MCP 面板可用性必须以 adapter 层
     * {@link ProviderCapability#MCP} 声明为门禁:
     * 否则 gateway status 里有 servers 数组的部署上,无 MCP 能力的 provider
     * (grok/kimi/pi/omp/dsh)会得到「available=true + 过滤后空列表」的自相矛盾 payload
     * (2026-08-29 审计缺口)。
     */
    private static volatile SessionProviderRouter capabilityRouter;

    private static boolean providerSupportsMcp(String provider) {
        if (provider == null || provider.isEmpty()) {
            return false;
        }
        SessionProviderRouter router = capabilityRouter;
        if (router == null) {
            router = new com.github.claudecodegui.session.SessionProviderRouter();
            capabilityRouter = router;
        }
        return router.supports(provider, ProviderCapability.MCP);
    }

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
        JsonArray mcp = new JsonArray();
        String mcpError = null;
        boolean available = false;
        if (project != null && providerSupportsMcp(safe(session.getProvider()))) {
            try {
                String statusJson = McpGatewayService.getInstance(project).statusJson();
                JsonElement root = JsonParser.parseString(
                        statusJson == null ? McpGatewayConstants.EMPTY_JSON_OBJECT : statusJson
                );
                if (root.isJsonObject()) {
                    JsonElement serversElement = root.getAsJsonObject().get(McpGatewayConstants.KEY_SERVERS);
                    if (serversElement != null && serversElement.isJsonArray()) {
                        available = true;
                        for (JsonElement serverElement : serversElement.getAsJsonArray()) {
                            appendServer(mcp, serverElement, session.getProvider());
                        }
                    }
                }
            } catch (JsonParseException | IllegalStateException | UnsupportedOperationException e) {
                mcpError = MCP_STATUS_ERROR;
            } catch (RuntimeException e) {
                mcpError = MCP_STATUS_ERROR;
            }
        }

        payload.addProperty(SessionCapabilitiesPayloadField.MCP_AVAILABLE.wireKey(), available);
        if (mcpError == null) {
            payload.add(SessionCapabilitiesPayloadField.MCP_ERROR.wireKey(), JsonNull.INSTANCE);
        } else {
            payload.addProperty(SessionCapabilitiesPayloadField.MCP_ERROR.wireKey(), mcpError);
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

    private static void appendServer(JsonArray target, JsonElement element, String currentProvider) {
        if (element == null || !element.isJsonObject()) {
            return;
        }
        JsonObject server = element.getAsJsonObject();
        String provider = stringValue(server, McpGatewayConstants.KEY_SOURCE_PROVIDER);
        if (provider != null && !provider.isEmpty() && currentProvider != null
                && !currentProvider.isEmpty() && !provider.equalsIgnoreCase(currentProvider)) {
            return;
        }
        String name = stringValue(server, McpGatewayConstants.KEY_SERVER_ID);
        if (name == null || name.isEmpty()) {
            name = SessionCapabilityState.UNKNOWN.value();
        }
        JsonObject item = new JsonObject();
        item.addProperty(
                SessionMcpCapabilityPayloadField.ID.wireKey(),
                (provider == null || provider.isEmpty() ? EMPTY : provider + ID_SEPARATOR) + name
        );
        item.addProperty(SessionMcpCapabilityPayloadField.NAME.wireKey(), name);
        item.addProperty(SessionMcpCapabilityPayloadField.PROVIDER.wireKey(), safe(provider));
        item.addProperty(
                SessionMcpCapabilityPayloadField.STATE.wireKey(),
                mapState(stringValue(server, McpGatewayConstants.KEY_STATE))
        );
        copyStringOrNull(
                item,
                SessionMcpCapabilityPayloadField.LAST_ERROR.wireKey(),
                server.get(McpGatewayConstants.KEY_LAST_ERROR)
        );
        copyNumberOrNull(
                item,
                SessionMcpCapabilityPayloadField.LAST_SUCCESS_AT.wireKey(),
                server.get(McpGatewayConstants.KEY_LAST_SUCCESS_AT)
        );
        copyNumber(
                item,
                SessionMcpCapabilityPayloadField.FAILURE_COUNT.wireKey(),
                server.get(McpGatewayConstants.KEY_FAILURE_COUNT)
        );
        item.addProperty(SessionMcpCapabilityPayloadField.OBSERVED.wireKey(), true);
        target.add(item);
    }

    private static String mapState(String state) {
        return state == null || state.isEmpty()
                ? SessionCapabilityState.UNKNOWN.value()
                : state.toLowerCase(Locale.ROOT);
    }

    private static String stringValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    /**
     * 类型安全的字符串透传:gateway 健康字段一旦类型漂移(对象/数字等),原样深拷贝会
     * 让前端严格校验整包判失败("Unable to load session capabilities"),故非字符串
     * 原语降级为其文本形式,缺失/非原语置 null。
     */
    private static void copyStringOrNull(JsonObject target, String key, JsonElement value) {
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            target.add(key, value.deepCopy());
        } else if (value != null && value.isJsonPrimitive()) {
            target.addProperty(key, value.getAsString());
        } else {
            target.add(key, JsonNull.INSTANCE);
        }
    }

    /** 同 {@link #copyStringOrNull},数字版:非数字原语(如字符串化时间戳)一律置 null。 */
    private static void copyNumberOrNull(JsonObject target, String key, JsonElement value) {
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            target.add(key, value.deepCopy());
        } else {
            target.add(key, JsonNull.INSTANCE);
        }
    }

    private static void copyNumber(JsonObject target, String key, JsonElement value) {
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            target.add(key, value.deepCopy());
        } else {
            target.addProperty(key, 0);
        }
    }

    private static String safe(String value) {
        return value == null ? EMPTY : value;
    }
}
