package com.github.claudecodegui.mcp;

import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Token-aware HTTP client for the loopback Gateway control API.
 */
public class McpGatewayBridgeClient {
    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String TOKEN_PREFIX = "Bearer ";
    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String CONTENT_TYPE_JSON = "application/json";

    private final Path stateFile;
    private final String token;
    private final HttpClient httpClient;

    public McpGatewayBridgeClient(Path stateFile, String token) {
        this.stateFile = stateFile;
        this.token = token;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    public boolean waitUntilReady(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (Files.exists(stateFile)) {
                    JsonObject state = readState();
                    if (state.has(McpGatewayConstants.KEY_PORT)) {
                        return true;
                    }
                }
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    public JsonObject postSnapshot(McpGatewayConfigSnapshot snapshot) throws IOException, InterruptedException {
        return post("/snapshot", snapshot.toJson());
    }

    public JsonObject status() throws IOException, InterruptedException {
        return get("/status");
    }

    public JsonObject stop() throws IOException, InterruptedException {
        return post("/stop", new JsonObject());
    }

    private JsonObject get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(5))
                .header(HEADER_AUTHORIZATION, TOKEN_PREFIX + token)
                .GET()
                .build();
        return send(request);
    }

    private JsonObject post(String path, JsonObject body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(10))
                .header(HEADER_AUTHORIZATION, TOKEN_PREFIX + token)
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(GsonHolder.GSON.toJson(body), StandardCharsets.UTF_8))
                .build();
        return send(request);
    }

    private JsonObject send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Gateway API failed: HTTP " + response.statusCode());
        }
        if (response.body() == null || response.body().isBlank()) {
            return new JsonObject();
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private URI uri(String path) throws IOException {
        JsonObject state = readState();
        int port = state.get(McpGatewayConstants.KEY_PORT).getAsInt();
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private JsonObject readState() throws IOException {
        return JsonParser.parseString(Files.readString(stateFile, StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
