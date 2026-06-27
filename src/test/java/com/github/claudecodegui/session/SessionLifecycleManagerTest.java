package com.github.claudecodegui.session;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.intellij.openapi.project.Project;
import com.intellij.ui.jcef.JBCefBrowser;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SessionLifecycleManagerTest {

    @Test
    public void prepareForSessionResetClearsTabStatusBeforeReplacingSession() {
        FakeSessionHost host = new FakeSessionHost();
        host.streamCoalescer.onStreamStart();

        SessionLifecycleManager.prepareForSessionReset(host);

        assertTrue(host.invalidateSessionCallbacksCalled);
        assertTrue(host.resetTabStatusCalled);
        assertFalse(host.streamCoalescer.isStreamActive());
        assertEquals(List.of("clearMessages"), host.javaScriptCalls);
    }

    // B8: resetPersistentRuntime(Claude daemon) 只应对 Claude SDK 会话触发。
    // 旧实现用 !isClaudeCliSession 过宽,对 codex/opencode 会话误调 Claude daemon reset(污染)。
    @Test
    public void claudeDaemonResetOnlyTriggersForClaudeSdkSessions() {
        // Claude SDK → 重置 daemon
        assertTrue(SessionLifecycleManager.shouldResetClaudeDaemonFor("claude", "sdk"));
        // Claude SDK 且 invocationMode 为 null(默认 sdk 语义)→ 仍重置
        assertTrue(SessionLifecycleManager.shouldResetClaudeDaemonFor("claude", null));

        // Claude CLI → 不重置(CLI 无 daemon)
        assertFalse(SessionLifecycleManager.shouldResetClaudeDaemonFor("claude", "cli"));

        // Codex / OpenCode → 不重置(各自的 runtime,不应触碰 Claude daemon)
        assertFalse(SessionLifecycleManager.shouldResetClaudeDaemonFor("codex", "sdk"));
        assertFalse(SessionLifecycleManager.shouldResetClaudeDaemonFor("codex", "cli"));
        assertFalse(SessionLifecycleManager.shouldResetClaudeDaemonFor("opencode", "sdk"));
        assertFalse(SessionLifecycleManager.shouldResetClaudeDaemonFor("opencode", "cli"));
        assertFalse(SessionLifecycleManager.shouldResetClaudeDaemonFor("opencode", null));
    }

    private static final class FakeSessionHost implements SessionLifecycleManager.SessionHost {
        private final List<String> javaScriptCalls = new ArrayList<>();
        private final StreamMessageCoalescer streamCoalescer = new StreamMessageCoalescer(new StreamMessageCoalescer.JsCallbackTarget() {
            @Override
            public void callJavaScript(String functionName, String... args) {
            }

            @Override
            public JBCefBrowser getBrowser() {
                return null;
            }

            @Override
            public boolean isDisposed() {
                return false;
            }

            @Override
            public HandlerContext getHandlerContext() {
                return null;
            }
        });
        private boolean invalidateSessionCallbacksCalled;
        private boolean resetTabStatusCalled;

        @Override
        public Project getProject() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ClaudeSDKBridge getClaudeSDKBridge() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CodexSDKBridge getCodexSDKBridge() {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.github.claudecodegui.provider.opencode.OpenCodeSDKBridge getOpenCodeSDKBridge() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ClaudeSession getSession() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setSession(ClaudeSession session) {
            throw new UnsupportedOperationException();
        }

        @Override
        public HandlerContext getHandlerContext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public StreamMessageCoalescer getStreamCoalescer() {
            return streamCoalescer;
        }

        @Override
        public void clearPendingPermissionRequests() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearPermissionDecisionMemory() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void callJavaScript(String functionName, String... args) {
            javaScriptCalls.add(functionName);
        }

        @Override
        public boolean isDisposed() {
            return false;
        }

        @Override
        public JBCefBrowser getBrowser() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setupSessionCallbacks() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void invalidateSessionCallbacks() {
            invalidateSessionCallbacksCalled = true;
        }

        @Override
        public void setSlashCommandsFetched(boolean fetched) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setFetchedSlashCommandsCount(int count) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void resetTabStatus() {
            resetTabStatusCalled = true;
        }
    }
}
