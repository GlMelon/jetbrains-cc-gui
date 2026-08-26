package com.github.claudecodegui.session.normalize;

import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.CliResult;
import com.github.claudecodegui.session.ClaudeSession;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MessageNormalizersTest {

    @Test
    public void factoryKeepsProviderImplementationsIndependent() {
        RecordingCallback delegate = new RecordingCallback();

        // SDK 调用模式已移除,runtime 维度已消除,按 provider 单维路由。
        assertEquals(ClaudeCliMessageNormalizer.class,
                MessageNormalizers.forProvider("claude", delegate).getClass());
        assertEquals(CodexCliMessageNormalizer.class,
                MessageNormalizers.forProvider("codex", delegate).getClass());
    }

    // B6: OpenCode 必须注册专用 normalizer,否则回退到 Claude 归一化器(协议事件错配)。
    @Test
    public void opencodeNormalizerRegistered() {
        RecordingCallback delegate = new RecordingCallback();

        assertEquals(OpenCodeMessageNormalizer.class,
                MessageNormalizers.forProvider("opencode", delegate).getClass());
    }

    @Test
    public void codexCliNormalizerSuppressesTextOnlyAssistantSnapshotsButKeepsToolUse() {
        RecordingCallback delegate = new RecordingCallback();
        MessageCallback normalizer = new CodexCliMessageNormalizer(delegate);

        normalizer.onMessage("stream_start", "");
        normalizer.onMessage("content_delta", "hello");
        normalizer.onMessage("assistant", "{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}}");
        normalizer.onMessage("assistant", "{\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"tool-1\",\"name\":\"Bash\",\"input\":{}}]}}");

        assertEquals(List.of("stream_start", "content_delta", "assistant"), delegate.types);
        assertTrue(delegate.contents.get(2).contains("\"tool_use\""));
        assertFalse(delegate.contents.stream().anyMatch(content -> content.contains("\"text\":\"hello\"")));
    }

    private static final class RecordingCallback implements MessageCallback {
        final List<String> types = new ArrayList<>();
        final List<String> contents = new ArrayList<>();

        @Override
        public void onMessage(String type, String content) {
            types.add(type);
            contents.add(content);
        }

        @Override
        public void onError(String error) {
        }

        @Override
        public void onComplete(CliResult result) {
        }

        @Override
        public void onQueueDisplayStateChanged(ClaudeSession.SessionCallback.QueueDisplayState state, int aheadCount) {
        }
    }
}
