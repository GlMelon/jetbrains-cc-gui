package com.github.claudecodegui.session;

import com.github.claudecodegui.mcp.McpGatewayConstants;
import com.github.claudecodegui.protocol.payload.SessionMcpCapabilityPayloadField;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Locale;

/**
 * 会话 MCP 面板条目的字段组装 helper(自 GatewaySessionMcpSource 原私有方法整体搬入,
 * 行为不变),供 gateway 系数据源(GatewaySessionMcpSource / KimiWireSessionMcpSource)
 * 共用,避免同一套类型安全透传逻辑多份漂移(总则四)。
 */
final class SessionMcpItemCodec {

    private static final String EMPTY = "";
    private static final String ID_SEPARATOR = ":";

    private SessionMcpItemCodec() {
    }

    /**
     * gateway statusJson servers 元素 → 面板条目。
     * 不过滤 sourceProvider:全部 gateway 注入型 provider(claude/codex/opencode/kimi)
     * 都经同一个 melon_gateway 聚合端点接入,会话实际加载的是整个 catalog,
     * 面板据此展示全量(条目的 provider 字段保留来源 provider 供展示)。
     */
    static void appendServer(List<JsonObject> target, JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return;
        }
        JsonObject server = element.getAsJsonObject();
        String provider = stringValue(server, McpGatewayConstants.KEY_SOURCE_PROVIDER);
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

    static String mapState(String state) {
        return state == null || state.isEmpty()
                ? SessionCapabilityState.UNKNOWN.value()
                : state.toLowerCase(Locale.ROOT);
    }

    static String stringValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    /**
     * 类型安全的字符串透传:gateway 健康字段一旦类型漂移(对象/数字等),原样深拷贝会
     * 让前端严格校验整包判失败("Unable to load session capabilities"),故非字符串
     * 原语降级为其文本形式,缺失/非原语置 null。
     */
    static void copyStringOrNull(JsonObject target, String key, JsonElement value) {
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            target.add(key, value.deepCopy());
        } else if (value != null && value.isJsonPrimitive()) {
            target.addProperty(key, value.getAsString());
        } else {
            target.add(key, JsonNull.INSTANCE);
        }
    }

    /** 同 {@link #copyStringOrNull},数字版:非数字原语(如字符串化时间戳)一律置 null。 */
    static void copyNumberOrNull(JsonObject target, String key, JsonElement value) {
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            target.add(key, value.deepCopy());
        } else {
            target.add(key, JsonNull.INSTANCE);
        }
    }

    static void copyNumber(JsonObject target, String key, JsonElement value) {
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
