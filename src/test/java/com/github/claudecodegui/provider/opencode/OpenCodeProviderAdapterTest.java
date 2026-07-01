package com.github.claudecodegui.provider.opencode;

import com.github.claudecodegui.provider.ProviderId;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * B7: OpenCodeProviderAdapter 必须 override getSessionMessages / cleanupProviderSession。
 * 历史回放委托 OpenCodeSDKBridge 读取本地 SQLite 历史；清理逻辑保持 no-op。
 */
public class OpenCodeProviderAdapterTest {

    @Test
    public void reportsOpenCodeProviderId() {
        OpenCodeProviderAdapter adapter = new OpenCodeProviderAdapter();

        assertEquals(ProviderId.OPENCODE, adapter.providerId());
        assertEquals("OpenCode", adapter.viewModel().displayName());
    }

    @Test
    public void getSessionMessagesDelegatesToBridge() throws Exception {
        // OpenCodeSDKBridge construction touches IntelliJ Platform services; keep this as a source guard.
        String source = java.nio.file.Files.readString(java.nio.file.Paths.get(
                "src", "main", "java", "com", "github", "claudecodegui", "provider", "opencode", "OpenCodeProviderAdapter.java"));

        assertTrue("OpenCode 历史回放必须委托 bridge,不能继续返回空列表",
                source.contains("return requireBridge().getSessionMessages(sessionId, cwd);"));
        assertTrue("无 bridge 时必须 fail-fast,避免误以为空历史",
                source.contains("throw new IllegalStateException(\"OpenCode SDK bridge is required for session routing\")"));
    }

    @Test
    public void cleanupProviderSessionIsSafeNoOpWithoutThrowing() {
        OpenCodeProviderAdapter adapter = new OpenCodeProviderAdapter();

        // 无 bridge 时 no-op:OpenCode 不缓存 thread(serve 守护进程由 DaemonCoordinator 统一管理,见 §8.1)。
        adapter.cleanupProviderSession("ses_0fab6db33ffe", "/cwd");

        assertTrue("cleanupProviderSession must not throw without a bridge", true);
    }
}
