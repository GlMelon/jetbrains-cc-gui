package com.github.claudecodegui.protocol;

import com.github.claudecodegui.session.runtime.ProviderType;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 构建时工具:将协议枚举序列化为 JSON manifest。
 *
 * @deprecated 已无运行时/构建时消费者。SSOT 主路径为 mjs 直读 Java 枚举源
 * ({@code webview/scripts/generate-protocol-types.mjs}),标准构建链不依赖本类
 * 产出的 manifest。本类保留仅作 mjs regex 解析(C8 漂移守门)的反射交叉校验入口,
 * 经 Gradle task {@code generateProtocol}({@code -PgenerateProtocol=true})手动驱动。
 * 待 C8 收敛为反射主路径后再评估删除。不打包进插件 JAR。
 *
 * <p>输出格式:
 * <pre>{@code
 * {
 *   "upstream": [
 *     { "name": "SEND_MESSAGE", "value": "send_message" },
 *     ...
 *   ],
 *   "downstream": [
 *     { "name": "USAGE_UPDATE", "value": "usage.update" },
 *     ...
 *   ]
 * }
 * }</pre>
 */
@Deprecated
public final class ProtocolManifestGenerator {

    private ProtocolManifestGenerator() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: ProtocolManifestGenerator <outputPath>");
            System.exit(1);
        }

        Map<String, Object> manifest = new LinkedHashMap<>();

        List<Map<String, String>> upstream = new ArrayList<>();
        for (UpstreamAction action : UpstreamAction.values()) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("name", action.name());
            entry.put("value", action.value());
            upstream.add(entry);
        }
        manifest.put("upstream", upstream);

        List<Map<String, String>> downstream = new ArrayList<>();
        for (DownstreamEvent event : DownstreamEvent.values()) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("name", event.name());
            entry.put("value", event.value());
            downstream.add(entry);
        }
        manifest.put("downstream", downstream);

        List<Map<String, String>> permissionMode = new ArrayList<>();
        for (PermissionMode mode : PermissionMode.values()) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("name", mode.name());
            entry.put("value", mode.value());
            permissionMode.add(entry);
        }
        manifest.put("permissionMode", permissionMode);

        List<Map<String, String>> reasoningEffort = new ArrayList<>();
        for (ReasoningEffort effort : ReasoningEffort.values()) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("name", effort.name());
            entry.put("value", effort.value());
            reasoningEffort.add(entry);
        }
        manifest.put("reasoningEffort", reasoningEffort);

        List<Map<String, String>> providerType = new ArrayList<>();
        for (ProviderType type : ProviderType.values()) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("name", type.name());
            entry.put("value", type.value());
            providerType.add(entry);
        }
        manifest.put("providerType", providerType);

        List<Map<String, String>> codexProtectedEnvKey = new ArrayList<>();
        for (CodexProtectedEnvKey key : CodexProtectedEnvKey.values()) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("name", key.name());
            entry.put("value", key.value());
            codexProtectedEnvKey.add(entry);
        }
        manifest.put("codexProtectedEnvKey", codexProtectedEnvKey);

        File output = new File(args[0]);
        output.getParentFile().mkdirs();
        try (Writer w = new OutputStreamWriter(
                new FileOutputStream(output), StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(manifest, w);
        }

        System.out.println("[ProtocolManifestGenerator] Generated: " + output.getAbsolutePath()
                + " (" + upstream.size() + " upstream, " + downstream.size() + " downstream, "
                + permissionMode.size() + " permissionMode, " + reasoningEffort.size() + " reasoningEffort, "
                + providerType.size() + " providerType, " + codexProtectedEnvKey.size() + " codexProtectedEnvKey)");
    }
}
