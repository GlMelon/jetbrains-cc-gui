package com.github.claudecodegui.mcp;

import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * Immutable MCP Gateway catalog input.
 */
public record McpGatewayConfigSnapshot(
        long revision,
        String projectPath,
        List<McpGatewayServerSpec> servers,
        String configHash
) {
    public McpGatewayConfigSnapshot {
        servers = servers != null ? List.copyOf(servers) : List.of();
        configHash = configHash != null ? configHash : computeHash(projectPath, servers);
    }

    public static McpGatewayConfigSnapshot create(long revision, String projectPath,
                                                  List<McpGatewayServerSpec> servers) {
        return new McpGatewayConfigSnapshot(revision, projectPath, servers, computeHash(projectPath, servers));
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty(McpGatewayConstants.KEY_SCHEMA_VERSION, McpGatewayConstants.SNAPSHOT_SCHEMA_VERSION);
        obj.addProperty(McpGatewayConstants.KEY_REVISION, revision);
        obj.addProperty(McpGatewayConstants.KEY_PROJECT_PATH, projectPath);
        obj.addProperty(McpGatewayConstants.KEY_CONFIG_HASH, configHash);
        JsonArray arr = new JsonArray();
        for (McpGatewayServerSpec server : servers) {
            arr.add(server.toJson());
        }
        obj.add(McpGatewayConstants.KEY_SERVERS, arr);
        return obj;
    }

    public static String computeHash(String projectPath, List<McpGatewayServerSpec> servers) {
        JsonObject canonical = new JsonObject();
        canonical.addProperty(McpGatewayConstants.KEY_SCHEMA_VERSION, McpGatewayConstants.SNAPSHOT_SCHEMA_VERSION);
        canonical.addProperty(McpGatewayConstants.KEY_PROJECT_PATH, projectPath);
        JsonArray arr = new JsonArray();
        if (servers != null) {
            servers.stream()
                    .sorted((a, b) -> (a.sourceProvider() + ":" + a.serverId())
                            .compareTo(b.sourceProvider() + ":" + b.serverId()))
                    .forEach(server -> arr.add(server.toJson()));
        }
        canonical.add(McpGatewayConstants.KEY_SERVERS, arr);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(GsonHolder.GSON.toJson(canonical).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute MCP Gateway config hash", e);
        }
    }
}
