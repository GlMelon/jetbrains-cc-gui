package com.github.claudecodegui.settings;

import com.github.claudecodegui.settings.ConfigRepository.ConfigConflictException;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * AppearanceSettingsService 领域逻辑测试(A3 领域拆分第一步,docs §A3)。
 *
 * <p>覆盖 appearance(theme/fontSize/diffTheme/per-theme colors)+ uiFont + codeFont 三段的
 * default / normalize / 往返行为,以及经 CSS 委托链下 write-time CAS 仍生效。
 *
 * <p>夹具参照 {@link CodemossSettingsServiceUiFontConfigTest}:反射注入
 * {@code PlatformUtils.cachedRealHomeDir} 指向临时 home,使 {@code new CodemossSettingsService()}
 * 的 {@link ConfigRepository} 落在隔离的临时 {@code .codemoss/config.json}。
 *
 * <p><b>CAS 覆盖说明</b>:{@code setAppearanceConfig} 单方法内自带 read+write,read 会刷新
 * ThreadLocal snapshot,单线程无法在 read→write 间注入外部编辑;故 CAS 冲突用例 override
 * {@code CSS.readConfig} 在 {@code super.readConfig()}(建 snapshot)之后立即改写文件,
 * 模拟 cc-switch 在 read→write 窗口内外部编辑,验证委托链下 {@link ConfigRepository#save} 的
 * CAS 仍触发。repo 层 CAS 矩阵另由 {@link ConfigRepositoryTest} 覆盖。
 */
public class AppearanceSettingsServiceTest {
    private String originalHomeDir;
    private Path tempHome;

    @After
    public void tearDown() throws Exception {
        if (originalHomeDir != null) {
            setCachedHomeDirectory(originalHomeDir);
            originalHomeDir = null;
        }
    }

    // ==================== Appearance ====================

    @Test
    public void appearanceDefaultsWhenFileAbsent() throws Exception {
        AppearanceSettingsService svc = newService();
        JsonObject cfg = svc.getAppearanceConfig();
        assertEquals("system", cfg.get("themePreference").getAsString());
        assertEquals(2, cfg.get("fontSizeLevel").getAsInt());
        assertEquals("follow", cfg.get("diffTheme").getAsString());
        assertFalse("colors omitted by default", cfg.has("chatBgColor"));
        assertFalse("colors omitted by default", cfg.has("userMsgColor"));
    }

    @Test
    public void appearanceRoundtripPersistsValidValues() throws Exception {
        AppearanceSettingsService svc = newService();
        JsonObject raw = new JsonObject();
        raw.addProperty("themePreference", "dark");
        raw.addProperty("fontSizeLevel", 4);
        raw.addProperty("diffTheme", "editor");
        svc.setAppearanceConfig(raw);

        JsonObject cfg = svc.getAppearanceConfig();
        assertEquals("dark", cfg.get("themePreference").getAsString());
        assertEquals(4, cfg.get("fontSizeLevel").getAsInt());
        assertEquals("editor", cfg.get("diffTheme").getAsString());
    }

    @Test
    public void invalidThemeFallsBackToSystem() throws Exception {
        AppearanceSettingsService svc = newService();
        JsonObject raw = new JsonObject();
        raw.addProperty("themePreference", "neon");
        svc.setAppearanceConfig(raw);
        assertEquals("system", svc.getAppearanceConfig().get("themePreference").getAsString());
    }

    @Test
    public void invalidDiffThemeFallsBackToFollow() throws Exception {
        AppearanceSettingsService svc = newService();
        JsonObject raw = new JsonObject();
        raw.addProperty("diffTheme", "hacker");
        svc.setAppearanceConfig(raw);
        assertEquals("follow", svc.getAppearanceConfig().get("diffTheme").getAsString());
    }

    @Test
    public void outOfRangeFontSizeFallsBackToTwo() throws Exception {
        AppearanceSettingsService svc = newService();
        JsonObject raw = new JsonObject();
        raw.addProperty("fontSizeLevel", 99);
        svc.setAppearanceConfig(raw);
        assertEquals(2, svc.getAppearanceConfig().get("fontSizeLevel").getAsInt());
    }

    @Test
    public void nonNumericFontSizeFallsBackToTwo() throws Exception {
        AppearanceSettingsService svc = newService();
        JsonObject raw = new JsonObject();
        raw.addProperty("fontSizeLevel", "huge");
        svc.setAppearanceConfig(raw);
        assertEquals(2, svc.getAppearanceConfig().get("fontSizeLevel").getAsInt());
    }

    @Test
    public void invalidHexColorsAreFilteredValidKept() throws Exception {
        AppearanceSettingsService svc = newService();
        JsonObject raw = new JsonObject();
        JsonObject chatBg = new JsonObject();
        chatBg.addProperty("light", "#1A2B3C"); // valid 6-hex
        chatBg.addProperty("dark", "#abc");     // invalid (short)
        raw.add("chatBgColor", chatBg);
        svc.setAppearanceConfig(raw);

        JsonObject persisted = svc.getAppearanceConfig().getAsJsonObject("chatBgColor");
        assertEquals("#1A2B3C", persisted.get("light").getAsString());
        assertFalse("invalid hex must be filtered", persisted.has("dark"));
    }

    @Test
    public void unknownAppearanceFieldsAreDropped() throws Exception {
        AppearanceSettingsService svc = newService();
        JsonObject raw = new JsonObject();
        raw.addProperty("themePreference", "light");
        raw.addProperty("unknownField", "dropped");
        svc.setAppearanceConfig(raw);
        assertFalse(svc.getAppearanceConfig().has("unknownField"));
    }

    @Test
    public void setAppearanceConfigNullDoesNotThrowAndWritesDefaults() throws Exception {
        AppearanceSettingsService svc = newService();
        svc.setAppearanceConfig(null);
        JsonObject cfg = svc.getAppearanceConfig();
        assertEquals("system", cfg.get("themePreference").getAsString());
        assertEquals("follow", cfg.get("diffTheme").getAsString());
    }

    // ==================== Font ====================

    @Test
    public void uiFontDefaultsToFollowEditorWhenAbsent() throws Exception {
        AppearanceSettingsService svc = newService();
        JsonObject cfg = svc.getUiFontConfig();
        assertEquals("followEditor", cfg.get("mode").getAsString());
        assertFalse(cfg.has("customFontPath"));
    }

    @Test
    public void invalidUiFontModeFallsBackToFollowEditor() throws Exception {
        AppearanceSettingsService svc = newService();
        svc.setUiFontConfig("bogus", null);
        JsonObject cfg = svc.getUiFontConfig();
        assertEquals("followEditor", cfg.get("mode").getAsString());
        assertFalse(cfg.has("customFontPath"));
    }

    @Test
    public void customFileWithBlankPathOnlyPersistsMode() throws Exception {
        AppearanceSettingsService svc = newService();
        svc.setUiFontConfig("customFile", "   ");
        JsonObject cfg = svc.getUiFontConfig();
        assertEquals("customFile", cfg.get("mode").getAsString());
        assertFalse("blank path must not persist", cfg.has("customFontPath"));
    }

    @Test
    public void codeFontSymmetricDefaultsAndRoundtrip() throws Exception {
        AppearanceSettingsService svc = newService();
        // 默认
        assertEquals("followEditor", svc.getCodeFontConfig().get("mode").getAsString());
        assertFalse(svc.getCodeFontConfig().has("customFontPath"));
        // 往返
        svc.setCodeFontConfig("customFile", "/tmp/code.ttf");
        JsonObject cfg = svc.getCodeFontConfig();
        assertEquals("customFile", cfg.get("mode").getAsString());
        assertEquals("/tmp/code.ttf", cfg.get("customFontPath").getAsString());
    }

    // ==================== CAS (delegation chain) ====================

    @Test
    public void externalEditDuringSetAppearanceThrowsConflict() throws Exception {
        useTemporaryHomeDirectory(tempHome = Files.createTempDirectory("appearance-cas-home"));
        final Path configFile = tempHome.resolve(".codemoss").resolve("config.json");

        ConfigStore delegate = SettingsTestConfig.create().configStore();
        delegate.update(config -> config.addProperty("bootstrap", true));
        assertTrue("config.json should exist after bootstrap", Files.exists(configFile));

        ConfigStore externallyEditedStore = new ConfigStore() {
            @Override
            public JsonObject read() throws IOException {
                return delegate.read();
            }

            @Override
            public void write(JsonObject config) throws IOException {
                delegate.write(config);
            }

            @Override
            public void update(ConfigMutation mutation) throws IOException {
                delegate.update(config -> {
                    mutation.apply(config);
                    Files.writeString(configFile, "{\"externalEdit\":true,\"differentSize\":12345}",
                            StandardCharsets.UTF_8);
                });
            }
        };
        AppearanceSettingsService svc = new AppearanceSettingsService(externallyEditedStore);

        try {
            svc.setAppearanceConfig(new JsonObject());
            fail("expected ConfigConflictException");
        } catch (ConfigConflictException e) {
            assertTrue("message: " + e.getMessage(), e.getMessage().contains("changed externally"));
        }
    }

    // ==================== helpers ====================

    private AppearanceSettingsService newService() throws Exception {
        useTemporaryHomeDirectory(tempHome = Files.createTempDirectory("appearance-test-home"));
        return new AppearanceSettingsService(SettingsTestConfig.create().configStore());
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
