package com.github.claudecodegui.settings;

import com.github.claudecodegui.util.PlatformUtils;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * showThinkingEnabled(思考区显示开关)按项目存储于 config.showThinking,
 * 三层回退 projectPath → default → true(默认显示思考区)。对称 getStreamingEnabled。
 *
 * <p>语义:思考区开关已从"让模型思考"(alwaysThinkingEnabled)重定义为"显示思考区"
 * (showThinkingEnabled)。模型是否思考改由 reasoning effort 控制,本开关只控推送/显示。
 */
public class CodemossSettingsServiceShowThinkingTest {
    private String originalHomeDir;

    @After
    public void tearDown() throws Exception {
        if (originalHomeDir != null) {
            setCachedHomeDirectory(originalHomeDir);
            originalHomeDir = null;
        }
    }

    @Test
    public void shouldDefaultToShowThinkingEnabledWhenConfigMissing() throws Exception {
        useTemporaryHomeDirectory(Files.createTempDirectory("show-thinking-default-home"));

        CodemossSettingsService service = new CodemossSettingsService();
        assertTrue(service.getShowThinkingEnabled("/projects/foo"));
    }

    @Test
    public void shouldReadProjectSpecificShowThinkingValue() throws Exception {
        useTemporaryHomeDirectory(Files.createTempDirectory("show-thinking-project-home"));

        CodemossSettingsService service = new CodemossSettingsService();
        service.setShowThinkingEnabled("/projects/foo", false);

        assertFalse(service.getShowThinkingEnabled("/projects/foo"));
    }

    @Test
    public void shouldFallBackToDefaultShowThinkingValue() throws Exception {
        useTemporaryHomeDirectory(Files.createTempDirectory("show-thinking-fallback-home"));

        CodemossSettingsService service = new CodemossSettingsService();
        service.setShowThinkingEnabled("/projects/foo", false);

        // 另一项目路径无 project-specific 值,回退 default(setShowThinkingEnabled 同时写 default)
        assertFalse(service.getShowThinkingEnabled("/projects/bar"));
    }

    @Test
    public void shouldRoundtripShowThinkingToggle() throws Exception {
        useTemporaryHomeDirectory(Files.createTempDirectory("show-thinking-roundtrip-home"));

        CodemossSettingsService service = new CodemossSettingsService();
        service.setShowThinkingEnabled("/projects/foo", false);
        assertFalse(service.getShowThinkingEnabled("/projects/foo"));

        service.setShowThinkingEnabled("/projects/foo", true);
        assertTrue(service.getShowThinkingEnabled("/projects/foo"));
    }

    private void useTemporaryHomeDirectory(Path tempHome) throws Exception {
        if (originalHomeDir == null) {
            originalHomeDir = getCachedHomeDirectory();
        }
        setCachedHomeDirectory(tempHome.toString());
        Files.createDirectories(tempHome.resolve(".codemoss"));
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
