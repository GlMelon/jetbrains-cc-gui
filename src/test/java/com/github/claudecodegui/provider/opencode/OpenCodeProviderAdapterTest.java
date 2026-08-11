package com.github.claudecodegui.provider.opencode;

import com.github.claudecodegui.provider.ProviderId;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * OpenCodeProviderAdapter 必须 override getSessionMessages / cleanupProviderSession。
 * 历史回放委托 OpenCodeHistoryService(懒加载,首次读取才拉起 NodeService);清理逻辑保持 no-op。
 */
public class OpenCodeProviderAdapterTest {

    @Test
    public void reportsOpenCodeProviderId() {
        OpenCodeProviderAdapter adapter = new OpenCodeProviderAdapter();

        assertEquals(ProviderId.OPENCODE, adapter.providerId());
        assertEquals("OpenCode", adapter.viewModel().displayName());
    }

    @Test
    public void getSessionMessagesDelegatesToHistoryService() throws Exception {
        // OpenCodeHistoryService 构造会拉起 IntelliJ platform 服务,adapter 改为懒加载;保留 source guard
        // 以确保历史回放始终委托 historyService,不会退化为返回空列表。
        String source = java.nio.file.Files.readString(java.nio.file.Paths.get(
                "src", "main", "java", "com", "github", "claudecodegui", "provider", "opencode", "OpenCodeProviderAdapter.java"));

        assertTrue("OpenCode 历史回放必须委托 historyService,不能继续返回空列表",
                source.contains("historyService().getSessionMessages(sessionId, cwd)"));
    }

    @Test
    public void cleanupProviderSessionIsSafeNoOpWithoutThrowing() {
        OpenCodeProviderAdapter adapter = new OpenCodeProviderAdapter();

        // 无 bridge 时 no-op:OpenCode CLI per-process 模式不缓存 thread。
        adapter.cleanupProviderSession("ses_0fab6db33ffe", "/cwd");

        assertTrue("cleanupProviderSession must not throw without a bridge", true);
    }
}
