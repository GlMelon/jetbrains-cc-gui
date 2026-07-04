package com.github.claudecodegui.mcp;

import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient.Redirect;
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
                        try {
                            if (isAlive()) {
                                return true;
                            }
                        } catch (Exception ignored) {
                            // state file can appear before the HTTP server is actually reachable
                        }
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

    private boolean isAlive() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri("/status"))
                .timeout(Duration.ofSeconds(1))
                .header(HEADER_AUTHORIZATION, TOKEN_PREFIX + token)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }

    /** /snapshot 同步等待时间:首次 POST 触发 Node 侧 applySnapshot 同步等所有 MCP server 的
     *  initialize+listTools(首屏冷加载无任何缓存,实测 >10s,见 idea.log 2026-07-02 HttpTimeoutException)。
     *  放大到 60s 覆盖典型 MCP 集合的首次加载;配合 McpGatewayService.applySnapshot "post 成功才提交"
     *  语义,极端超时也会下次自动重推。区别于 stop(/stop 只通知退出,应快速返回)。 */
    private static final Duration SNAPSHOT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);

    public JsonObject postSnapshot(McpGatewayConfigSnapshot snapshot) throws IOException, InterruptedException {
        return post("/snapshot", snapshot.toJson(), SNAPSHOT_TIMEOUT);
    }

    public JsonObject status() throws IOException, InterruptedException {
        return get("/status");
    }

    public JsonObject stop() throws IOException, InterruptedException {
        return post("/stop", new JsonObject(), STOP_TIMEOUT);
    }

    private JsonObject get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(5))
                .header(HEADER_AUTHORIZATION, TOKEN_PREFIX + token)
                .GET()
                .build();
        return send(request);
    }

    private JsonObject post(String path, JsonObject body, Duration timeout) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .timeout(timeout)
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
