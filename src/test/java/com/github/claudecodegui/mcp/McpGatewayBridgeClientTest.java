package com.github.claudecodegui.mcp;

import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.sun.net.httpserver.HttpServer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class McpGatewayBridgeClientTest {

    @Test
    public void waitUntilReadyRejectsStateFileWithoutReachableStatusEndpoint() throws Exception {
        Path dir = Files.createTempDirectory("mcp-gateway-ready-test");
        Path stateFile = dir.resolve("gateway-state.json");
        Files.writeString(stateFile, "{\"port\": 65530}", StandardCharsets.UTF_8);

        McpGatewayBridgeClient client = new McpGatewayBridgeClient(stateFile, "token");

        assertFalse(client.waitUntilReady(Duration.ofMillis(500)));
    }

    @Test
    public void waitUntilReadyAcceptsReachableStatusEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/status", exchange -> {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        Path dir = Files.createTempDirectory("mcp-gateway-ready-test");
        Path stateFile = dir.resolve("gateway-state.json");
        Files.writeString(stateFile,
                "{\"port\": " + server.getAddress().getPort() + "}", StandardCharsets.UTF_8);

        try {
            McpGatewayBridgeClient client = new McpGatewayBridgeClient(stateFile, "token");
            assertTrue(client.waitUntilReady(Duration.ofSeconds(2)));
        } finally {
            server.stop(0);
        }
    }
}
