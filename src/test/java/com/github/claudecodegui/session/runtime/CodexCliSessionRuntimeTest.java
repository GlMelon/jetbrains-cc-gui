package com.github.claudecodegui.session.runtime;

import com.github.claudecodegui.cli.CliSendRequest;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * 验证 Codex service tier 从统一 SessionRequest 透传到 CLI 请求。
 */
public class CodexCliSessionRuntimeTest {

    @Test
    public void toCliSendRequestPreservesCodexServiceTier() {
        RuntimeKey key = new RuntimeKey("codex", "channel-1", "tab-1", "epoch-1");
        SessionRequest req = new SessionRequest(
                key,
                ProviderType.CODEX,
                "hello",
                "sess-1",
                "/cwd",
                List.of(),
                null,
                List.of(),
                null,
                null,
                "gpt-5.3-codex",
                null,
                "high",
                "fast",
                null,
                null,
                null,
                Boolean.TRUE,
                Map.of(),
                1L
        );

        CliSendRequest cliReq = CodexCliSessionRuntime.toCliSendRequest(req);

        assertEquals("fast", cliReq.codexServiceTier());
    }
}
