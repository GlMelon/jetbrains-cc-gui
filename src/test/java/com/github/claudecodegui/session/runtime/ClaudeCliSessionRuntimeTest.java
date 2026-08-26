package com.github.claudecodegui.session.runtime;

import com.github.claudecodegui.cli.CliSendRequest;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * 验证 ClaudeCliSessionRuntime adapter 正确透传 SessionRequest 字段到 CliSendRequest。
 * <p>
 * 回归守护：fileTagPaths 曾在 adapter 重构(provider×runtime 双轨)时误传 {@code List.of()},
 * 致 {@code ClaudeCliSession.buildPrompt} 跳过 PROMPT_REFERENCED 拼接,Claude CLI 模式丢失引用文件列表。
 */
public class ClaudeCliSessionRuntimeTest {

    @Test
    public void toCliSendRequestPreservesFileTagPaths() {
        RuntimeKey key = new RuntimeKey("claude", "channel-1", "tab-1", "epoch-1");
        List<String> fileTagPaths = List.of("src/a.ts", "src/b.ts");
        SessionRequest req = new SessionRequest(
                key,
                ProviderType.CLAUDE,
                "hello",
                "sess-1",
                "/cwd",
                List.of(),
                null,
                fileTagPaths,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        CliSendRequest cliReq = ClaudeCliSessionRuntime.toCliSendRequest(req);

        assertEquals(fileTagPaths, cliReq.fileTagPaths());
    }
}
