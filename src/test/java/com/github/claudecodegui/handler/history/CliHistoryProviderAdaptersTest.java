package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.provider.grok.GrokHistoryReader;
import com.github.claudecodegui.provider.kimi.KimiHistoryReader;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * grok/kimi History 适配器:sessions JSON wire 形态({success,sessions[],total,sessionCount})
 * 与 loadMessages 的有界返回(对称 Codex/Claude adapter 行为)。
 */
public class CliHistoryProviderAdaptersTest {

    @Test
    public void grokAdapterProducesCodexCompatibleSessionsJson() throws IOException {
        Path root = Files.createTempDirectory("grok-adapter");
        // 与 GrokToolHistoryTailer.jsEncodeURIComponent(normalizeCwd) 等价的手工编码
        String encoded = java.net.URLEncoder.encode("C:/proj/a",
                java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        Path session = root.resolve("sessions").resolve(encoded).resolve("sid-a");
        Files.createDirectories(session);
        Files.writeString(session.resolve("chat_history.jsonl"),
                "{\"type\":\"assistant\",\"data\":\"hi\"}\n");

        GrokHistoryProviderAdapter adapter =
                new GrokHistoryProviderAdapter(new GrokHistoryReader(root));
        assertEquals(ProviderType.GROK, adapter.provider());
        assertTrue(adapter.capabilities().contains(HistoryCapability.DELETE));

        JsonObject json = com.github.claudecodegui.util.GsonHolder.GSON.fromJson(
                adapter.loadSessionsJson("c:/proj/a"), JsonObject.class);
        assertTrue(json.get("success").getAsBoolean());
        assertEquals(1, json.getAsJsonArray("sessions").size());

        HistoryMessageBatch batch = adapter.loadMessages("sid-a", "c:/proj/a",
                new HistoryMessageReadPolicy(50, 64_000));
        assertEquals(1, batch.messages().size());
        assertTrue(adapter.deleteSession("sid-a", "c:/proj/a").mainDeleted());
    }

    @Test
    public void kimiAdapterProducesCodexCompatibleSessionsJson() throws IOException {
        Path base = Files.createTempDirectory("kimi-adapter");
        Path session = base.resolve("sessions").resolve("key").resolve("session_x");
        Files.createDirectories(session.resolve("agents"));
        JsonObject state = new JsonObject();
        state.addProperty("title", "t");
        state.addProperty("workDir", "C:\\proj\\b");
        Files.writeString(session.resolve("state.json"), state.toString());

        KimiHistoryProviderAdapter adapter =
                new KimiHistoryProviderAdapter(new KimiHistoryReader(base));
        assertEquals(ProviderType.KIMI, adapter.provider());
        assertTrue(adapter.capabilities().contains(HistoryCapability.DELETE));

        JsonObject json = com.github.claudecodegui.util.GsonHolder.GSON.fromJson(
                adapter.loadSessionsJson("c:/proj/b"), JsonObject.class);
        assertTrue(json.get("success").getAsBoolean());
        assertEquals(1, json.getAsJsonArray("sessions").size());
        assertEquals(1, json.get("sessionCount").getAsInt());
    }
}
