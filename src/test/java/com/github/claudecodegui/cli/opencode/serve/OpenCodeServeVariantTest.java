package com.github.claudecodegui.cli.opencode.serve;

import com.github.claudecodegui.cli.CliSendRequest;
import com.github.claudecodegui.common.CommonConstants;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * OpenCodeServeSession variant 解析验证(模型可用 variant 动态查询,GET /provider 按 model 缓存,
 * 失败回退内置表;官方调研 2026-09-07「serve 通道实现模型可用 variant 动态查询」)。
 */
public class OpenCodeServeVariantTest {

    private HttpServer server;
    private String baseUrl;

    @Before
    public void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void tearDown() {
        server.stop(0);
    }

    private void serveProviderCatalog(String body) {
        server.createContext("/provider", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    private static CliSendRequest request(String model, String effort) {
        return new CliSendRequest(
                "tab-1", CommonConstants.PROVIDER_OPENCODE, "hi",
                null, "/work", List.of(), new JsonObject(), List.of(),
                null, CommonConstants.PERMISSION_MODE_DEFAULT, model,
                model, effort, null, Boolean.TRUE, java.util.Map.of()
        );
    }

    @Test
    public void promptBodyCarriesVariantFromDynamicCatalog() {
        serveProviderCatalog("{\"all\":[{\"id\":\"openglm\",\"models\":{"
                + "\"glm-5.2\":{\"variants\":{\"high\":{},\"max\":{}}}}}],\"default\":{},\"connected\":[]}");
        OpenCodeServeClient client = new OpenCodeServeClient(baseUrl);
        try {
            OpenCodeServeSession session = new OpenCodeServeSession("tab-1", client, null);
            // 目录含 max:max 直发(one-shot 无目录时钳到 high);xhigh 按 floor 语义钳到 high
            JsonObject body = session.buildPromptBody(client, request("openglm/glm-5.2", "max"), List.of());
            assertEquals("max", body.get("variant").getAsString());
            JsonObject xhighBody = session.buildPromptBody(client, request("openglm/glm-5.2", "xhigh"), List.of());
            assertEquals("high", xhighBody.get("variant").getAsString());
            assertEquals("openglm", body.getAsJsonObject("model").get("providerID").getAsString());
        } finally {
            client.close();
        }
    }

    @Test
    public void promptBodyOmitsVariantWhenModelHasNoVariants() {
        serveProviderCatalog("{\"all\":[{\"id\":\"openai\",\"models\":{"
                + "\"gpt-5\":{\"reasoning\":{}}}}],\"default\":{},\"connected\":[]}");
        OpenCodeServeClient client = new OpenCodeServeClient(baseUrl);
        try {
            OpenCodeServeSession session = new OpenCodeServeSession("tab-1", client, null);
            // 目录明确无 variants:携带任意 variant 都会 fail-fast,不携带
            JsonObject body = session.buildPromptBody(client, request("openai/gpt-5", "high"), List.of());
            assertNull(body.get("variant"));
        } finally {
            client.close();
        }
    }

    @Test
    public void promptBodyFallsBackToBuiltinTableWhenCatalogQueryFails() {
        // /provider 返回 500:查询失败不缓存,回退内置安全子集 [low,medium,high]
        server.createContext("/provider", exchange -> {
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        OpenCodeServeClient client = new OpenCodeServeClient(baseUrl);
        try {
            OpenCodeServeSession session = new OpenCodeServeSession("tab-1", client, null);
            JsonObject body = session.buildPromptBody(client, request("openglm/glm-5.2", "xhigh"), List.of());
            assertEquals("high", body.get("variant").getAsString());
        } finally {
            client.close();
        }
    }

    @Test
    public void promptBodyOmitsVariantWhenModelNotInProviderForm() {
        serveProviderCatalog("{\"all\":[],\"default\":{},\"connected\":[]}");
        OpenCodeServeClient client = new OpenCodeServeClient(baseUrl);
        try {
            OpenCodeServeSession session = new OpenCodeServeSession("tab-1", client, null);
            // 无 '/' 双段:动态目录不适用,回退内置表
            JsonObject body = session.buildPromptBody(client, request("glm-5.2", "high"), List.of());
            assertEquals("high", body.get("variant").getAsString());
            // medium 语义 = 默认档,两种路径均省略 variant
            JsonObject mediumBody = session.buildPromptBody(client, request("glm-5.2", "medium"), List.of());
            assertFalse(mediumBody.has("variant"));
        } finally {
            client.close();
        }
    }

    @Test
    public void parseModelVariantIdsHandlesMissingShapes() {
        assertTrue(OpenCodeServeClient.parseModelVariantIds(null, "openglm/glm-5.2").isEmpty());
        assertTrue(OpenCodeServeClient.parseModelVariantIds(new JsonObject(), "openglm/glm-5.2").isEmpty());
        // 无 '/' / 空段 → 空目录
        assertTrue(OpenCodeServeClient.parseModelVariantIds(new JsonObject(), "glm-5.2").isEmpty());
        assertTrue(OpenCodeServeClient.parseModelVariantIds(new JsonObject(), null).isEmpty());
    }

    @Test
    public void parseModelVariantIdsReadsVariantsKeys() {
        JsonObject response = com.google.gson.JsonParser.parseString("""
                {"all":[{"id":"openai","models":{"gpt/5":{"variants":{"low":{},"high":{}}}}}]}
                """).getAsJsonObject();
        // 模型 id 含后续 '/':首 '/' 拆 provider,余下整体为 modelID(与 splitModelRef 同语义)
        assertEquals(List.of("low", "high"),
                OpenCodeServeClient.parseModelVariantIds(response, "openai/gpt/5"));
    }
}
