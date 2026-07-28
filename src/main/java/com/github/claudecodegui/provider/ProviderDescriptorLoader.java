package com.github.claudecodegui.provider;

import com.github.claudecodegui.session.runtime.RuntimeType;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 从 JSON 加载自定义 Provider 描述符 —— S4-1C+ 配置驱动的配置解析层。
 *
 * <p>预期 JSON 形态(config.json 的 {@code customProviders} 段):
 * <pre>{@code
 * "customProviders": [
 *   {
 *     "id": "gemini",
 *     "label": "Gemini",
 *     "cliCommand": "gemini",
 *     "cliCommandWindows": "gemini.cmd",
 *     "capabilities": ["CLI_SESSION", "STREAMING", "HISTORY"],
 *     "runtimes": ["CLI"]
 *   }
 * ]
 * }</pre>
 *
 * <p><b>容错</b>:缺 id 或解析失败的条目被跳过(不抛异常),保证单个坏配置不阻塞其余 Provider 加载;
 * 未知 capability / runtime 值被忽略(不致整条丢弃);label 缺省回退 id,cliCommand 缺省回退 id,
 * cliCommandWindows 缺省回退 {@code cliCommand + ".cmd"}。
 */
public final class ProviderDescriptorLoader {

    private ProviderDescriptorLoader() {
    }

    public static List<ProviderDescriptor> fromJsonArray(JsonArray array) {
        List<ProviderDescriptor> result = new ArrayList<>();
        if (array == null) {
            return result;
        }
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            try {
                ProviderDescriptor descriptor = parseDescriptor(element.getAsJsonObject());
                if (descriptor != null) {
                    result.add(descriptor);
                }
            } catch (Exception ignored) {
                // 容错:单条解析失败不影响其余
            }
        }
        return result;
    }

    private static ProviderDescriptor parseDescriptor(JsonObject obj) {
        String id = optString(obj, "id");
        if (id == null || id.isBlank()) {
            return null;
        }
        String label = optString(obj, "label");
        String cliCommand = optString(obj, "cliCommand", id);
        String cliCommandWindows = optString(obj, "cliCommandWindows", cliCommand + ".cmd");
        Set<ProviderCapability> capabilities = parseCapabilities(obj.getAsJsonArray("capabilities"));
        Set<RuntimeType> runtimes = parseRuntimes(obj.getAsJsonArray("runtimes"));
        return new ProviderDescriptor(
                id,
                label != null ? label : id,
                cliCommand,
                cliCommandWindows,
                capabilities,
                runtimes
        );
    }

    private static String optString(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsString() : null;
    }

    private static String optString(JsonObject obj, String key, String defaultValue) {
        String value = optString(obj, key);
        return value != null ? value : defaultValue;
    }

    private static Set<ProviderCapability> parseCapabilities(JsonArray array) {
        Set<ProviderCapability> capabilities = EnumSet.noneOf(ProviderCapability.class);
        if (array == null) {
            return capabilities;
        }
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive()) {
                continue;
            }
            try {
                capabilities.add(ProviderCapability.valueOf(element.getAsString()));
            } catch (IllegalArgumentException ignored) {
                // 未知 capability 值忽略
            }
        }
        return capabilities;
    }

    private static Set<RuntimeType> parseRuntimes(JsonArray array) {
        Set<RuntimeType> runtimes = EnumSet.noneOf(RuntimeType.class);
        if (array == null) {
            return runtimes;
        }
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive()) {
                continue;
            }
            try {
                runtimes.add(RuntimeType.valueOf(element.getAsString()));
            } catch (IllegalArgumentException ignored) {
                // 未知 runtime 值忽略
            }
        }
        return runtimes;
    }
}
