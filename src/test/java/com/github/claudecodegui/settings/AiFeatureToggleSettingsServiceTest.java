package com.github.claudecodegui.settings;

import com.github.claudecodegui.util.PlatformUtils;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * AiFeatureToggleSettingsService 领域逻辑测试(A3 领域拆分第二步,docs §A3)。
 *
 * <p>覆盖 4 个 boolean toggle(commit 生成 / MCP gateway / 状态栏 widget / AI 标题生成,
 * 均默认 true)+ Smithery API key(default ""、set null/empty → remove 清理)。
 * 夹具参照 {@link AppearanceSettingsServiceTest}:反射注入 {@code PlatformUtils.cachedRealHomeDir}
 * 指向隔离临时 home。委托链(CSS 转发 → Service)由 {@link #delegationViaCssFacade} 验证。
 */
public class AiFeatureToggleSettingsServiceTest {
    private String originalHomeDir;

    @After
    public void tearDown() throws Exception {
        if (originalHomeDir != null) {
            setCachedHomeDirectory(originalHomeDir);
            originalHomeDir = null;
        }
    }

    // ==================== boolean toggles (default true) ====================

    @Test
    public void allBooleanTogglesDefaultTrueWhenFileAbsent() throws Exception {
        AiFeatureToggleSettingsService svc = newService();
        assertTrue(svc.getCommitGenerationEnabled());
        assertTrue(svc.getMcpGatewayEnabled());
        assertTrue(svc.getStatusBarWidgetEnabled());
        assertTrue(svc.getAiTitleGenerationEnabled());
    }

    @Test
    public void allBooleanTogglesRoundtrip() throws Exception {
        AiFeatureToggleSettingsService svc = newService();
        svc.setCommitGenerationEnabled(false);
        svc.setMcpGatewayEnabled(false);
        svc.setStatusBarWidgetEnabled(false);
        svc.setAiTitleGenerationEnabled(false);
        assertFalse(svc.getCommitGenerationEnabled());
        assertFalse(svc.getMcpGatewayEnabled());
        assertFalse(svc.getStatusBarWidgetEnabled());
        assertFalse(svc.getAiTitleGenerationEnabled());
        // 往返回 true,验证 false 不是「缺失」误读。
        svc.setCommitGenerationEnabled(true);
        assertTrue(svc.getCommitGenerationEnabled());
    }

    // ==================== Smithery API key ====================

    @Test
    public void smitheryApiKeyDefaultsEmptyWhenFileAbsent() throws Exception {
        AiFeatureToggleSettingsService svc = newService();
        assertEquals("", svc.getSmitheryApiKey());
    }

    @Test
    public void smitheryApiKeyRoundtrip() throws Exception {
        AiFeatureToggleSettingsService svc = newService();
        svc.setSmitheryApiKey("sk-smithery-abc123");
        assertEquals("sk-smithery-abc123", svc.getSmitheryApiKey());
    }

    @Test
    public void smitheryApiKeyNullClears() throws Exception {
        AiFeatureToggleSettingsService svc = newService();
        svc.setSmitheryApiKey("sk-smithery-abc123");
        svc.setSmitheryApiKey(null);
        assertEquals("null should clear the key", "", svc.getSmitheryApiKey());
    }

    @Test
    public void smitheryApiKeyEmptyClears() throws Exception {
        AiFeatureToggleSettingsService svc = newService();
        svc.setSmitheryApiKey("sk-smithery-abc123");
        svc.setSmitheryApiKey("");
        assertEquals("empty should clear the key", "", svc.getSmitheryApiKey());
    }

    // ==================== 委托链(CSS 转发 → Service) ====================

    @Test
    public void delegationViaCssFacade() throws Exception {
        CodemossSettingsService css = newCss();
        // CSS public 方法经动态分发走到 Service,默认值正确。
        assertTrue(css.getCommitGenerationEnabled());
        assertTrue(css.getMcpGatewayEnabled());
        assertTrue(css.getStatusBarWidgetEnabled());
        assertTrue(css.getAiTitleGenerationEnabled());
        assertEquals("", css.getSmitheryApiKey());
        // set 经 CSS 转发委托落盘,get 经转发读取。
        css.setCommitGenerationEnabled(false);
        assertFalse(css.getCommitGenerationEnabled());
    }

    // ==================== helpers ====================

    private AiFeatureToggleSettingsService newService() throws Exception {
        return new AiFeatureToggleSettingsService(newCss());
    }

    private CodemossSettingsService newCss() throws Exception {
        useTemporaryHomeDirectory(Files.createTempDirectory("ai-feature-toggle-test-home"));
        return new CodemossSettingsService();
    }

    private void useTemporaryHomeDirectory(Path home) throws Exception {
        if (originalHomeDir == null) {
            originalHomeDir = getCachedHomeDirectory();
        }
        setCachedHomeDirectory(home.toString());
        Files.createDirectories(home.resolve(".codemoss"));
    }

    private String getCachedHomeDirectory() throws Exception {
        Field field = PlatformUtils.class.getDeclaredField("cachedRealHomeDir");
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private void setCachedHomeDirectory(String homeDir) throws Exception {
        Field field = PlatformUtils.class.getDeclaredField("cachedRealHomeDir");
        field.setAccessible(true);
        field.set(null, homeDir);
    }
}
