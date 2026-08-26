package com.github.claudecodegui.cli;

import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.CliResult;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public class CliSessionManagerTest {

    @Test
    public void interruptProviderRoutesOnlyCodexToCodexRuntime() {
        assertEquals("codex", CliSessionManager.normalizeInterruptProvider("codex"));
        assertEquals("claude", CliSessionManager.normalizeInterruptProvider("claude"));
        assertEquals("claude", CliSessionManager.normalizeInterruptProvider("custom-claude-compatible"));
        assertEquals("claude", CliSessionManager.normalizeInterruptProvider(null));
    }

    /**
     * P1a 回归守护:disposeTab 后迟到的 send 不应复活已释放的 CliSession(否则重启 CLI 子进程)。
     * <p>
     * 竞态场景:disposeTab(tabId) 清空 inFlight/sessions 与迟到 send 的 compute/computeIfAbsent 非原子,
     * 无防护时迟到 send 会经 computeIfAbsent 重建 session 并启动新子进程。disposed 标记拦截之。
     */
    @Test
    public void sendAfterDisposeDoesNotReviveSession() throws Exception {
        CountingFactory factory = new CountingFactory();
        CliSessionManager mgr = new CliSessionManager(List.of(factory));
        MessageCallback cb = new NoopCallback();

        // 首次 send:创建 1 个 session
        mgr.send(req("tab-1", "claude"), cb).join();
        assertEquals("首次 send 应创建 1 个 session", 1, factory.createCount.get());

        // 关闭 tab
        mgr.disposeTab("tab-1");

        // 迟到的 send(dispose 后到达):不应复活 session/重启子进程
        mgr.send(req("tab-1", "claude"), cb).join();

        assertEquals("dispose 后迟到的 send 不应重建 session", 1, factory.createCount.get());
    }

    private static CliSendRequest req(String tabId, String provider) {
        return new CliSendRequest(tabId, provider, "msg", null, null, List.of(), null, List.of(),
                null, null, null, null, null, null, Map.of());
    }

    private static class NoopCallback implements MessageCallback {
        @Override public void onMessage(String type, String content) {}
        @Override public void onError(String error) {}
        @Override public void onComplete(CliResult result) {}
    }

    private static class CountingFactory implements CliSessionFactory {
        final AtomicInteger createCount = new AtomicInteger();
        @Override public String provider() { return "claude"; }
        @Override public CliSession create(String tabId) {
            createCount.incrementAndGet();
            return new FakeSession();
        }
    }

    private static class FakeSession implements CliSession {
        @Override public CompletableFuture<Void> send(CliSendRequest request, CliSessionCallback callback) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public void interrupt() {}
        @Override public void dispose() {}
    }
}
