package com.github.claudecodegui.provider.opencode;

import com.github.claudecodegui.provider.ProviderId;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * B7: OpenCodeProviderAdapter 必须 override getSessionMessages / cleanupProviderSession,
 * 否则历史回放抛 UnsupportedOperationException 阻断 loadHistorySession,清理逻辑误触其他 provider。
 * OpenCode 历史持久化尚在规划(§11/§8),当前两方法优雅降级(空列表 / no-op),不抛异常。
 */
public class OpenCodeProviderAdapterTest {

    @Test
    public void reportsOpenCodeProviderId() {
        OpenCodeProviderAdapter adapter = new OpenCodeProviderAdapter();

        assertEquals(ProviderId.OPENCODE, adapter.providerId());
        assertEquals("OpenCode", adapter.viewModel().displayName());
    }

    @Test
    public void getSessionMessagesReturnsEmptyListWithoutThrowing() {
        OpenCodeProviderAdapter adapter = new OpenCodeProviderAdapter();

        // 无 bridge 时优雅降级:返回空列表而非抛 UnsupportedOperationException(默认接口会抛)。
        List<?> messages = adapter.getSessionMessages("ses_0fab6db33ffe", "/cwd");

        assertEquals(Collections.emptyList(), messages);
    }

    @Test
    public void cleanupProviderSessionIsSafeNoOpWithoutThrowing() {
        OpenCodeProviderAdapter adapter = new OpenCodeProviderAdapter();

        // 无 bridge 时 no-op:OpenCode 不缓存 thread(serve 守护进程由 DaemonCoordinator 统一管理,见 §8.1)。
        adapter.cleanupProviderSession("ses_0fab6db33ffe", "/cwd");

        assertTrue("cleanupProviderSession must not throw without a bridge", true);
    }
}
