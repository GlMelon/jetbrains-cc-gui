package com.github.claudecodegui.settings;

import com.github.claudecodegui.settings.ConfigRepository.ConfigConflictException;
import com.github.claudecodegui.settings.ConfigRepository.LoadedConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A3 ConfigRepository 故障注入测试矩阵(docs/comprehensive-optimization-directions.md §A3)。
 * <p>
 * 用真实文件系统 + 临时目录注入故障,覆盖:正常往返 / 文件缺失 / malformed quarantine+backup 回退 /
 * external-edit CAS 冲突 / backup 滚动版本数 / unknown field 透传 / temp 残留清理。
 * <p>
 * 全部基于真实 IO(非 mock),验证 fsync/CAS/ATOMIC_MOVE/quarantine 的实际行为。
 */
public class ConfigRepositoryTest {

    private Path dir;
    private Gson gson;
    private ConfigRepository repo;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("configrepo-test");
        gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
        repo = new ConfigRepository(dir, gson);
    }

    @After
    public void tearDown() throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // ignore
                }
            });
        }
    }

    private Path configFile() {
        return dir.resolve("config.json");
    }

    private void writeRaw(String content) throws IOException {
        Files.writeString(configFile(), content, StandardCharsets.UTF_8);
    }

    private long size(Path p) throws IOException {
        return Files.size(p);
    }

    // ---- 正常路径 ----

    @Test
    public void loadReturnsNullWhenFileAbsent() throws Exception {
        assertNull(repo.load());
    }

    @Test
    public void saveThenLoadRoundtrips() throws Exception {
        JsonObject obj = new JsonObject();
        obj.addProperty("k", "v");
        obj.addProperty("n", 42);
        repo.save(obj);

        LoadedConfig loaded = repo.load();
        assertNotNull(loaded);
        assertEquals("v", loaded.getConfig().get("k").getAsString());
        assertEquals(42, loaded.getConfig().get("n").getAsInt());
    }

    // ---- malformed quarantine + backup 回退 ----

    @Test
    public void malformedConfigIsQuarantinedAndRestoredFromBackup() throws Exception {
        // save 两次:第二次的 rotateBackups 把首次内容备份到 .bak.1。
        // (rotateBackups 在写主文件前执行,备份"即将被覆盖的旧主文件";首次 save 无旧文件故无 .bak.1。)
        JsonObject v1 = new JsonObject();
        v1.addProperty("k", "v1");
        repo.save(v1);
        JsonObject v2 = new JsonObject();
        v2.addProperty("k", "v2");
        repo.save(v2);
        // 现状:主文件 = v2,.bak.1 = v1。

        // 损坏主文件。
        writeRaw("{ broken json !!! not parseable");

        // load 应 quarantine 主文件 + 从 .bak.1(v1)原子恢复主文件 + 返回恢复内容。
        LoadedConfig loaded = repo.load();
        assertNotNull("should restore from backup", loaded);
        assertEquals("v1", loaded.getConfig().get("k").getAsString());

        // 主文件已被恢复为合法内容(=v1),可再次正常 load。
        LoadedConfig reloaded = repo.load();
        assertNotNull(reloaded);
        assertEquals("v1", reloaded.getConfig().get("k").getAsString());

        // quarantine 文件存在(forensic)。
        try (Stream<Path> list = Files.list(dir)) {
            boolean quarantined = list.anyMatch(p -> p.getFileName().toString().startsWith("config.json.quarantine-"));
            assertTrue("quarantine file should exist", quarantined);
        }
    }

    @Test
    public void malformedConfigWithNoBackupReturnsNull() throws Exception {
        // 无任何 backup(直接写损坏文件)。
        writeRaw("{ totally broken");
        assertNull(repo.load());
        // 主文件被 quarantine(移走),不再存在。
        assertFalse(Files.exists(configFile()));
    }

    // ---- external-edit CAS 冲突 ----

    @Test
    public void externalEditBetweenReadAndWriteThrowsConflict() throws Exception {
        JsonObject obj = new JsonObject();
        obj.addProperty("k", "v");
        repo.save(obj);

        // read 建立本线程 snapshot 基线。
        repo.load();

        // 模拟 cc-switch 外部编辑:重写主文件(不同内容/size),mtime 变。
        writeRaw("{\"externalEdit\":true,\"differentSize\":123}");

        try {
            repo.save(obj);
            fail("expected ConfigConflictException");
        } catch (ConfigConflictException e) {
            assertTrue("message: " + e.getMessage(), e.getMessage().contains("changed externally"));
        }
    }

    @Test
    public void saveWithoutPriorReadSkipsCasAndSucceeds() throws Exception {
        // 不经 load 直接 save(expected snapshot == null → 跳过 CAS),应成功。
        JsonObject obj = new JsonObject();
        obj.addProperty("k", "v");
        repo.save(obj);
        assertEquals("v", repo.load().getConfig().get("k").getAsString());
    }

    // ---- backup 滚动 ----

    @Test
    public void backupRotationKeepsAtMostMaxBackups() throws Exception {
        for (int i = 0; i < ConfigRepository.MAX_BACKUPS + 5; i++) {
            JsonObject obj = new JsonObject();
            obj.addProperty("n", i);
            repo.save(obj);
        }
        for (int i = 1; i <= ConfigRepository.MAX_BACKUPS; i++) {
            assertTrue(".bak." + i + " should exist", Files.exists(dir.resolve("config.json.bak." + i)));
        }
        assertFalse(".bak.(MAX+1) should be rotated out",
                Files.exists(dir.resolve("config.json.bak." + (ConfigRepository.MAX_BACKUPS + 1))));
    }

    // ---- unknown field 透传 ----

    @Test
    public void unknownFieldsArePreservedAcrossRoundtrip() throws Exception {
        // 直接写带 unknown field 的文件(模拟外部工具写入插件未识别字段)。
        writeRaw("{\"known\":1,\"unknownField\":\"keep-me\",\"nested\":{\"x\":true}}");
        LoadedConfig loaded = repo.load();
        assertNotNull(loaded);

        // 插件修改已知字段后 save。
        loaded.getConfig().addProperty("known", 2);
        repo.save(loaded.getConfig());

        // unknown field 应仍在(整体 JsonObject 透传)。
        LoadedConfig reloaded = repo.load();
        assertEquals("keep-me", reloaded.getConfig().get("unknownField").getAsString());
        assertEquals(2, reloaded.getConfig().get("known").getAsInt());
        assertTrue(reloaded.getConfig().getAsJsonObject("nested").get("x").getAsBoolean());
    }

    // ---- temp 清理 ----

    @Test
    public void saveLeavesNoTempResidue() throws Exception {
        repo.save(new JsonObject());
        repo.save(new JsonObject());
        try (Stream<Path> list = Files.list(dir)) {
            boolean tempLeft = list.anyMatch(p -> p.getFileName().toString().endsWith(".tmp"));
            assertFalse("no .tmp residue should remain", tempLeft);
        }
    }

    @Test
    public void conflictDoesNotCorruptExistingConfig() throws Exception {
        JsonObject original = new JsonObject();
        original.addProperty("k", "original");
        repo.save(original);

        repo.load(); // snapshot 基线
        writeRaw("{\"external\":true}"); // 外部编辑

        JsonObject attempt = new JsonObject();
        attempt.addProperty("k", "attempt");
        try {
            repo.save(attempt);
            fail("expected conflict");
        } catch (ConfigConflictException expected) {
            // 现有 config 不被破坏(仍是外部编辑后的内容,而非 attempt 的一半)。
        }
        // 主文件应保持外部编辑后的完整内容(CAS 冲突时 save 不 move)。
        String content = Files.readString(configFile(), StandardCharsets.UTF_8);
        assertTrue("config not corrupted by failed save: " + content, content.contains("external"));
        assertFalse("attempt content must not be written: " + content, content.contains("attempt"));
    }

    @Test
    public void savedConfigHasValidJsonSize() throws Exception {
        JsonObject obj = new JsonObject();
        obj.addProperty("k", "v");
        repo.save(obj);
        // 非空合法 JSON 写入。
        assertTrue(size(configFile()) > 2);
    }
}
