package com.github.claudecodegui.session;

import com.github.claudecodegui.mcp.McpGatewayConstants;
import com.github.claudecodegui.mcp.McpGatewayService;
import com.github.claudecodegui.protocol.payload.SessionMcpCapabilityPayloadField;
import com.github.claudecodegui.provider.ProviderCapability;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.intellij.openapi.project.Project;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * gateway 注入型 provider(claude/codex/opencode)的 MCP 面板数据源:
 * 读 {@link McpGatewayService#statusJson()} 的 servers 数组并按 sourceProvider 过滤,
 * 即经 gateway 实际加载的 MCP 服务集。行为自 SessionCapabilityService 原 gateway 段整体搬入,
 * 对三个 provider 完全不变。
 */
public final class GatewaySessionMcpSource implements SessionMcpSource {

    private static final String EMPTY = "";
    private static final String ID_SEPARATOR = ":";
    private static final String MCP_STATUS_ERROR = "Unable to read MCP Gateway status";

    /**
     * 能力查询路由(懒加载静态共享,构造轻量)。MCP 面板可用性必须以 adapter 层
     * {@link ProviderCapability#MCP} 声明为门禁:
     * 否则 gateway status 里有 servers 数组的部署上,无 MCP 能力的 provider
     * 会得到「available=true + 过滤后空列表」的自相矛盾 payload(2026-08-29 审计缺口)。
     */
    private static volatile SessionProviderRouter capabilityRouter;

    private final ProviderType provider;

    public GatewaySessionMcpSource(ProviderType provider) {
        this.provider = provider;
    }

    @Override
    public ProviderType provider() {
        return provider;
    }

    @Override
    public McpPanelData collect(Project project, ClaudeSession session) {
        if (project == null || session == null || !providerSupportsMcp()) {
            return McpPanelData.unavailable();
        }
        List<JsonObject> items = new ArrayList<>();
        String mcpError = null;
        boolean available = false;
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
                        appendServer(items, serverElement, session.getProvider());
                    }
                }
            }
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException e) {
            mcpError = MCP_STATUS_ERROR;
        } catch (RuntimeException e) {
            mcpError = MCP_STATUS_ERROR;
        }
        return new McpPanelData(available, mcpError, items);
    }

    private boolean providerSupportsMcp() {
        SessionProviderRouter router = capabilityRouter;
        if (router == null) {
            router = new SessionProviderRouter();
            capabilityRouter = router;
        }
        return router.supports(provider.value(), ProviderCapability.MCP);
    }

    private static void appendServer(List<JsonObject> target, JsonElement element, String currentProvider) {
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
