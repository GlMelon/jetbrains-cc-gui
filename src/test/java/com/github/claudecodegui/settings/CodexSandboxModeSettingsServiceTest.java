package com.github.claudecodegui.settings;

import com.github.claudecodegui.util.PlatformUtils;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * CodexSandboxModeSettingsService 领域逻辑测试(A3 领域拆分第三步,docs §A3)。
 *
 * <p>覆盖 Codex 沙箱模式 per-project/default 读写:平台默认值(Windows→danger-full-access,
 * 其他→workspace-write)、两种合法 mode 往返、无效 mode set 抛 {@link IllegalArgumentException}、
 * projectPath 优先于 default、存储的非法 mode 回退 default、null projectPath 只写 default、
 * 经 CSS 委托链。
 *
 * <p>夹具参照 {@link AppearanceSettingsServiceTest}:反射注入 {@code PlatformUtils.cachedRealHomeDir}
 * 指向隔离临时 home。平台默认值期望动态算({@code PlatformUtils.isWindows()}),保证测试平台无关。
 * 优先级/非法存储用例直接 {@link Files#writeString} 预置 config.json(参照 CAS 用例的预置范式)。
 */
public class CodexSandboxModeSettingsServiceTest {
    private String originalHomeDir;
    private Path tempHome;

    @After
    public void tearDown() throws Exception {
        if (originalHomeDir != null) {
            setCachedHomeDirectory(originalHomeDir);
            originalHomeDir = null;
        }
    }

    // ==================== platform default ====================

    @Test
    public void defaultsToPlatformDefaultWhenFileAbsent() throws Exception {
        CodexSandboxModeSettingsService svc = newService();
        String expected = PlatformUtils.isWindows() ? "danger-full-access" : "workspace-write";
        assertEquals(expected, svc.getCodexSandboxMode(null));
        assertEquals(expected, svc.getCodexSandboxMode("/any/project/path"));
    }

    // ==================== valid modes roundtrip ====================

    @Test
    public void workspaceWriteRoundtrip() throws Exception {
        CodexSandboxModeSettingsService svc = newService();
        svc.setCodexSandboxMode("/proj/A", "workspace-write");
        assertEquals("workspace-write", svc.getCodexSandboxMode("/proj/A"));
    }

    @Test
    public void dangerFullAccessRoundtrip() throws Exception {
        CodexSandboxModeSettingsService svc = newService();
        svc.setCodexSandboxMode("/proj/B", "danger-full-access");
        assertEquals("danger-full-access", svc.getCodexSandboxMode("/proj/B"));
    }

    // ==================== invalid mode ====================

    @Test
    public void invalidModeSetThrows() throws Exception {
        CodexSandboxModeSettingsService svc = newService();
        try {
            svc.setCodexSandboxMode("/proj", "bogus");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue("message: " + e.getMessage(), e.getMessage().contains("Invalid Codex sandbox mode"));
        }
    }

    // ==================== precedence: projectPath > default > platform default ====================

    @Test
    public void projectPathOverridesDefault() throws Exception {
        // 直接预置 config:default=danger-full-access, /proj/A=workspace-write(构造两者不同以证优先级)。
        writePresetConfig("{\"codexSandboxMode\":{\"default\":\"danger-full-access\",\"/proj/A\":\"workspace-write\"}}");
        CodexSandboxModeSettingsService svc = newServiceOnPresetHome();
        assertEquals("workspace-write", svc.getCodexSandboxMode("/proj/A"));
        // 未配置的 projectPath 兜底到 default。
        assertEquals("danger-full-access", svc.getCodexSandboxMode("/proj/other"));
    }

    @Test
    public void invalidStoredModeFallsBackToPlatformDefault() throws Exception {
        // config:default=workspace-write(合法), /proj/A=bogus(非法)。原逻辑:任何非法 mode 回退平台默认
        // (defaultMode = Security F 平台决策),非 default 段 —— 逐字迁移保持等价。
        writePresetConfig("{\"codexSandboxMode\":{\"default\":\"workspace-write\",\"/proj/A\":\"bogus\"}}");
        CodexSandboxModeSettingsService svc = newServiceOnPresetHome();
        String expected = PlatformUtils.isWindows() ? "danger-full-access" : "workspace-write";
        assertEquals(expected, svc.getCodexSandboxMode("/proj/A"));
    }

    // ==================== null projectPath ====================

    @Test
    public void setWithNullProjectPathOnlyWritesDefault() throws Exception {
        CodexSandboxModeSettingsService svc = newService();
        svc.setCodexSandboxMode(null, "danger-full-access");
        // null projectPath 只写 default,任意 projectPath 都命中 default。
        assertEquals("danger-full-access", svc.getCodexSandboxMode("/any"));
    }

    // ==================== 委托链(CSS 转发 → Service) ====================

    @Test
    public void delegationViaCssFacade() throws Exception {
        CodemossSettingsService css = newCss();
        String expected = PlatformUtils.isWindows() ? "danger-full-access" : "workspace-write";
        assertEquals(expected, css.getCodexSandboxMode("/proj"));
        css.setCodexSandboxMode("/proj", "workspace-write");
        assertEquals("workspace-write", css.getCodexSandboxMode("/proj"));
    }

    // ==================== helpers ====================

    private CodexSandboxModeSettingsService newService() throws Exception {
        useTemporaryHomeDirectory(tempHome = Files.createTempDirectory("codex-sandbox-test-home"));
        return new CodexSandboxModeSettingsService(SettingsTestConfig.create().configStore());
    }

    private CodemossSettingsService newCss() throws Exception {
        useTemporaryHomeDirectory(tempHome = Files.createTempDirectory("codex-sandbox-css-home"));
        return new CodemossSettingsService();
    }

    /** 预置 config 后在当前 tempHome 上 new CSS(不重建 home,复用 writePresetConfig 写好的 home)。 */
    private CodexSandboxModeSettingsService newServiceOnPresetHome() throws Exception {
        return new CodexSandboxModeSettingsService(SettingsTestConfig.create().configStore());
    }

    /** 在新的临时 home 写入预设 config.json(必须在 new CSS 之前,使其 load 读到预设)。 */
    private void writePresetConfig(String json) throws Exception {
        useTemporaryHomeDirectory(tempHome = Files.createTempDirectory("codex-sandbox-preset-home"));
        Path configFile = tempHome.resolve(".codemoss").resolve("config.json");
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, json, StandardCharsets.UTF_8);
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
