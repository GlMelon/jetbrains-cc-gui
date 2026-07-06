package com.github.claudecodegui.cli.common;

import com.github.claudecodegui.settings.ConfigPathManager;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * CliSettings mtime 失效缓存测试。
 * <p>一轮 Claude send 调 readClaudeCliEnvironment() 3 次、Codex 1 次,每次重读 cli-settings.json +
 * ~/.claude/settings.json(或 config.toml + auth.json) + JSON parse。缓存以"文件 mtime 未变 + path 未变"为
 * 命中条件,用户改 config 下一轮 send 立即生效(mtime 变 → 失效);跨 tempHome 因 path 变自动失效,
 * 对 CliSettingsIsolationTest 透明。范式见 FileSystemCollector 的 gitignore mtime 缓存。
 */
public class CliSettingsCacheTest {
    private String originalHomeDir;

    @After
    public void tearDown() throws Exception {
        if (originalHomeDir != null) {
            setCachedHomeDirectory(originalHomeDir);
            originalHomeDir = null;
        }
        CliSettings.__clearCacheForTests();
    }

    @Test
    public void readClaudeEnvCachesOnUnchangedMtime() throws Exception {
        Path tempHome = Files.createTempDirectory("cli-cache-unchanged");
        useTemporaryHomeDirectory(tempHome);
        Path cliSettingsPath = new ConfigPathManager().getCliSettingsFilePath();
        Files.createDirectories(cliSettingsPath.getParent());
        Files.writeString(cliSettingsPath, "{\"claudeEnv\":{\"MODEL\":\"v1\"}}", StandardCharsets.UTF_8);
        long mtime = Files.getLastModifiedTime(cliSettingsPath).toMillis();

        JsonObject env1 = CliSettings.readClaudeEnv();
        assertEquals("v1", env1.get("MODEL").getAsString());

        // 改内容但保持 mtime → 缓存命中,应返回旧值
        Files.writeString(cliSettingsPath, "{\"claudeEnv\":{\"MODEL\":\"v2\"}}", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(cliSettingsPath, FileTime.fromMillis(mtime));

        JsonObject env2 = CliSettings.readClaudeEnv();
        assertEquals("mtime 未变,缓存应命中返回旧值 v1", "v1", env2.get("MODEL").getAsString());
    }

    @Test
    public void readClaudeEnvRefetchesOnMtimeChange() throws Exception {
        Path tempHome = Files.createTempDirectory("cli-cache-changed");
        useTemporaryHomeDirectory(tempHome);
        Path cliSettingsPath = new ConfigPathManager().getCliSettingsFilePath();
        Files.createDirectories(cliSettingsPath.getParent());
        Files.writeString(cliSettingsPath, "{\"claudeEnv\":{\"MODEL\":\"v1\"}}", StandardCharsets.UTF_8);

        CliSettings.readClaudeEnv();

        // 改内容 + 强制新 mtime
        Files.writeString(cliSettingsPath, "{\"claudeEnv\":{\"MODEL\":\"v2\"}}", StandardCharsets.UTF_8);
        long currentMtime = Files.getLastModifiedTime(cliSettingsPath).toMillis();
        Files.setLastModifiedTime(cliSettingsPath, FileTime.fromMillis(currentMtime + 5000));

        JsonObject env2 = CliSettings.readClaudeEnv();
        assertEquals("mtime 变,缓存应失效重读 v2", "v2", env2.get("MODEL").getAsString());
    }

    @Test
    public void readClaudeCliEnvironmentCachesUntilHomeSettingsMtimeChanges() throws Exception {
        Path tempHome = Files.createTempDirectory("cli-claude-env-cache");
        useTemporaryHomeDirectory(tempHome);
        Path cliSettingsPath = new ConfigPathManager().getCliSettingsFilePath();
        Files.createDirectories(cliSettingsPath.getParent());
        Files.writeString(cliSettingsPath, "{\"claudeEnv\":{\"A\":\"1\"}}", StandardCharsets.UTF_8);
        Path settingsPath = tempHome.resolve(".claude").resolve("settings.json");
        Files.createDirectories(settingsPath.getParent());
        Files.writeString(settingsPath, "{\"env\":{\"B\":\"2\"}}", StandardCharsets.UTF_8);
        long homeMtime = Files.getLastModifiedTime(settingsPath).toMillis();

        Map<String, String> env1 = CliSettings.readClaudeCliEnvironment();
        assertEquals("1", env1.get("A"));
        assertEquals("2", env1.get("B"));

        // 改 home settings.json 内容但保持 mtime → 整体缓存应命中
        Files.writeString(settingsPath, "{\"env\":{\"B\":\"changed\"}}", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(settingsPath, FileTime.fromMillis(homeMtime));

        Map<String, String> env2 = CliSettings.readClaudeCliEnvironment();
        assertEquals("home mtime 未变,整体缓存命中应返回旧值 2", "2", env2.get("B"));

        // 改 home settings.json mtime → 缓存失效
        Files.setLastModifiedTime(settingsPath, FileTime.fromMillis(homeMtime + 5000));
        Map<String, String> env3 = CliSettings.readClaudeCliEnvironment();
        assertEquals("home mtime 变,缓存应失效重读 changed", "changed", env3.get("B"));
    }

    @Test
    public void readCodexCliEnvironmentCachesUntilTomlMtimeChanges() throws Exception {
        Path tempHome = Files.createTempDirectory("cli-codex-env-cache");
        useTemporaryHomeDirectory(tempHome);
        Path codexDir = tempHome.resolve(".codex");
        Files.createDirectories(codexDir);
        Path tomlPath = codexDir.resolve("config.toml");
        Files.writeString(tomlPath,
                "model = \"gpt-5.5\"\n\n[env]\nMYTESTKEY = \"v1\"\n",
                StandardCharsets.UTF_8);
        long tomlMtime = Files.getLastModifiedTime(tomlPath).toMillis();
        Path authPath = codexDir.resolve("auth.json");
        Files.writeString(authPath, "{\"OPENAI_API_KEY\":\"sk\"}", StandardCharsets.UTF_8);

        Map<String, String> env1 = CliSettings.readCodexCliEnvironment();
        assertEquals("v1", env1.get("MYTESTKEY"));

        // 改 toml 内容但保持 mtime → 缓存命中
        Files.writeString(tomlPath,
                "model = \"gpt-5.5\"\n\n[env]\nMYTESTKEY = \"v2\"\n",
                StandardCharsets.UTF_8);
        Files.setLastModifiedTime(tomlPath, FileTime.fromMillis(tomlMtime));

        Map<String, String> env2 = CliSettings.readCodexCliEnvironment();
        assertEquals("toml mtime 未变,缓存应命中返回旧值 v1", "v1", env2.get("MYTESTKEY"));

        // 改 toml mtime → 失效
        Files.setLastModifiedTime(tomlPath, FileTime.fromMillis(tomlMtime + 5000));
        Map<String, String> env3 = CliSettings.readCodexCliEnvironment();
        assertEquals("toml mtime 变,缓存应失效重读 v2", "v2", env3.get("MYTESTKEY"));
    }

    @Test
    public void cacheInvalidatesAcrossHomeDirectories() throws Exception {
        // 缓存 key 含 path,跨 tempHome 即便 mtime 巧合相同也必须失效(对 CliSettingsIsolationTest 透明)
        Path home1 = Files.createTempDirectory("cli-cache-home1");
        useTemporaryHomeDirectory(home1);
        Path cliSettingsPath = new ConfigPathManager().getCliSettingsFilePath();
        Files.createDirectories(cliSettingsPath.getParent());
        Files.writeString(cliSettingsPath, "{\"claudeEnv\":{\"MODEL\":\"home1\"}}", StandardCharsets.UTF_8);
        long mtime1 = Files.getLastModifiedTime(cliSettingsPath).toMillis();

        CliSettings.readClaudeEnv();

        // 切到 home2,path 不同,即便 mtime 故意相同也必须失效
        Path home2 = Files.createTempDirectory("cli-cache-home2");
        useTemporaryHomeDirectory(home2);
        Path cliSettingsPath2 = new ConfigPathManager().getCliSettingsFilePath();
        Files.createDirectories(cliSettingsPath2.getParent());
        Files.writeString(cliSettingsPath2, "{\"claudeEnv\":{\"MODEL\":\"home2\"}}", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(cliSettingsPath2, FileTime.fromMillis(mtime1));

        JsonObject env = CliSettings.readClaudeEnv();
        assertEquals("path 变,缓存必须失效重读 home2", "home2", env.get("MODEL").getAsString());
    }

    private void useTemporaryHomeDirectory(Path tempHome) throws Exception {
        if (originalHomeDir == null) {
            originalHomeDir = getCachedHomeDirectory();
        }
        setCachedHomeDirectory(tempHome.toString());
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
