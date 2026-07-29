package com.github.claudecodegui.settings;

import com.github.claudecodegui.settings.ConfigRepository.ConfigConflictException;
import com.github.claudecodegui.settings.ConfigRepository.LoadedConfig;
import com.github.claudecodegui.settings.credentials.CredentialBackend.Availability;
import com.github.claudecodegui.settings.credentials.InMemoryCredentialBackend;
import com.github.claudecodegui.settings.credentials.PasswordStore;
import com.github.claudecodegui.settings.migration.ConfigMigrationRegistry.UnsupportedConfigVersionException;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

    private ConfigRepository newRepository() {
        PasswordStore passwordStore = new PasswordStore(new InMemoryCredentialBackend());
        return new ConfigRepository(
                dir,
                gson,
                ConfigSchema::createDefaultConfig,
                ConfigSchema.createMigrationRegistry(passwordStore)
        );
    }

    private void incrementRepeatedly(
            ConfigRepository repository,
            int iterations,
            CountDownLatch start) {
        try {
            start.await(30, TimeUnit.SECONDS);
            for (int i = 0; i < iterations; i++) {
                repository.update(config -> {
                    int current = config.has("counter") ? config.get("counter").getAsInt() : 0;
                    config.addProperty("counter", current + 1);
                });
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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

    // ---- schema migration lifecycle ----

    @Test
    public void futureSchemaVersionIsRejectedWithoutQuarantine() throws Exception {
        String futureConfig = "{\"schemaVersion\":" + (ConfigSchema.CURRENT_VERSION + 1)
                + ",\"unknown\":true}";
        writeRaw(futureConfig);

        try {
            repo.load();
            fail("expected unsupported future schema version");
        } catch (UnsupportedConfigVersionException expected) {
            assertTrue(expected.getMessage().contains("newer than supported"));
        }

        assertEquals(futureConfig, Files.readString(configFile(), StandardCharsets.UTF_8));
        try (Stream<Path> files = Files.list(dir)) {
            assertFalse("future versions are valid JSON and must not be quarantined",
                    files.anyMatch(path -> path.getFileName().toString().startsWith("config.json.quarantine-")));
        }
    }

    @Test
    public void deferredSecretMigrationPersistsProgressAndResumesAfterRecovery() throws Exception {
        InMemoryCredentialBackend backend = new InMemoryCredentialBackend();
        backend.setAvailability(Availability.HEADLESS_NO_BACKEND);
        PasswordStore passwordStore = new PasswordStore(backend);
        ConfigRepository migrationRepo = new ConfigRepository(
                dir,
                gson,
                ConfigSchema::createDefaultConfig,
                ConfigSchema.createMigrationRegistry(passwordStore)
        );
        writeRaw("{\"smitheryApiKey\":\"legacy-secret\",\"unknown\":true}");

        JsonObject deferred = migrationRepo.read();

        assertEquals(1, deferred.get(ConfigSchema.SCHEMA_VERSION_KEY).getAsInt());
        assertEquals("legacy-secret", deferred.get(ConfigSchema.SMITHERY_API_KEY).getAsString());
        JsonObject persistedDeferred = com.google.gson.JsonParser
                .parseString(Files.readString(configFile(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals(1, persistedDeferred.get(ConfigSchema.SCHEMA_VERSION_KEY).getAsInt());
        assertTrue(persistedDeferred.has(ConfigSchema.SMITHERY_API_KEY));

        backend.setAvailability(Availability.AVAILABLE);
        JsonObject recovered = migrationRepo.read();

        assertEquals(ConfigSchema.CURRENT_VERSION,
                recovered.get(ConfigSchema.SCHEMA_VERSION_KEY).getAsInt());
        assertFalse(recovered.has(ConfigSchema.SMITHERY_API_KEY));
        assertTrue(recovered.get("unknown").getAsBoolean());
        assertEquals("legacy-secret",
                passwordStore.loadPassword(ConfigSchema.SMITHERY_CREDENTIAL_KEY));
    }

    // ---- process-wide update serialization ----

    @Test
    public void concurrentRepositoriesDoNotLoseUpdates() throws Exception {
        ConfigRepository first = newRepository();
        ConfigRepository second = newRepository();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int updatesPerThread = 40;
        try {
            Future<?> firstTask = pool.submit(() -> incrementRepeatedly(first, updatesPerThread, start));
            Future<?> secondTask = pool.submit(() -> incrementRepeatedly(second, updatesPerThread, start));

            start.countDown();
            firstTask.get(30, TimeUnit.SECONDS);
            secondTask.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            assertTrue("executor should terminate", pool.awaitTermination(30, TimeUnit.SECONDS));
        }

        assertEquals(updatesPerThread * 2, repo.read().get("counter").getAsInt());
    }

    @Test
    public void failedMutationDoesNotChangeConfigFile() throws Exception {
        repo.update(config -> config.addProperty("counter", 1));
        String before = Files.readString(configFile(), StandardCharsets.UTF_8);

        try {
            repo.update(config -> {
                config.addProperty("counter", 2);
                throw new IOException("injected mutation failure");
            });
            fail("expected mutation failure");
        } catch (IOException expected) {
            assertEquals("injected mutation failure", expected.getMessage());
        }

        assertEquals(before, Files.readString(configFile(), StandardCharsets.UTF_8));
        assertEquals(1, repo.read().get("counter").getAsInt());
    }

    @Test
    public void casConflictDoesNotRotateBackups() throws Exception {
        JsonObject first = new JsonObject();
        first.addProperty("value", "first");
        repo.save(first);
        JsonObject second = new JsonObject();
        second.addProperty("value", "second");
        repo.save(second);
        Path backup1 = dir.resolve("config.json.bak.1");
        String backupBefore = Files.readString(backup1, StandardCharsets.UTF_8);

        repo.load();
        writeRaw("{\"externalEdit\":true,\"differentSize\":12345}");
        try {
            repo.save(second);
            fail("expected ConfigConflictException");
        } catch (ConfigConflictException expected) {
            assertTrue(expected.getMessage().contains("changed externally"));
        }

        assertEquals(backupBefore, Files.readString(backup1, StandardCharsets.UTF_8));
        assertFalse("conflict must not advance backup generations",
                Files.exists(dir.resolve("config.json.bak.2")));
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
