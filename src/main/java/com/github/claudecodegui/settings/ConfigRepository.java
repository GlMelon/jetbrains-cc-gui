package com.github.claudecodegui.settings;

import com.github.claudecodegui.settings.credentials.IntelliJPasswordSafeBackend;
import com.github.claudecodegui.settings.credentials.PasswordStore;
import com.github.claudecodegui.settings.migration.ConfigMigrationRegistry;
import com.github.claudecodegui.settings.migration.ConfigMigrationRegistry.MigrationOutcome;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 插件自有配置文件({@code ~/.codemoss/config.json})的唯一读写入口。
 *
 * <p>仓库统一提供原子写、fsync、外部修改 CAS、malformed quarantine、滚动 backup、
 * unknown field 透传、逐级 schema migration，以及覆盖同一路径所有仓库实例的进程内写锁。
 * 领域 Service 应使用 {@link #update(ConfigMutation)} 完成原子 read-modify-write。</p>
 */
public class ConfigRepository implements ConfigStore {

    private static final Logger LOG = Logger.getInstance(ConfigRepository.class);

    /** 保留的滚动 backup 份数(不含主文件)。 */
    static final int MAX_BACKUPS = 5;
    private static final String CONFIG_FILE_NAME = "config.json";
    private static final String BACKUP_BASE = "config.json.bak";
    private static final String QUARANTINE_PREFIX = "config.json.quarantine-";
    private static final String TMP_SUFFIX = ".tmp";

    /** 同一路径即使构造多个 repository，也共享同一把进程内锁。 */
    private static final ConcurrentMap<Path, ReentrantLock> PATH_LOCKS = new ConcurrentHashMap<>();

    private final Path configDir;
    private final Path configPath;
    private final Gson gson;
    private final Supplier<JsonObject> defaultConfigFactory;
    private final ConfigMigrationRegistry migrationRegistry;
    private final ReentrantLock processLock;

    /** 同线程 read 时记录文件 snapshot，供兼容 write 调用面执行外部修改 CAS。 */
    private final ThreadLocal<Snapshot> lastReadSnapshot = new ThreadLocal<>();

    public ConfigRepository(Path configDir, Gson gson) {
        this(
                configDir,
                gson,
                ConfigSchema::createDefaultConfig,
                ConfigSchema.createMigrationRegistry(new PasswordStore(new IntelliJPasswordSafeBackend()))
        );
    }

    public ConfigRepository(
            Path configDir,
            Gson gson,
            Supplier<JsonObject> defaultConfigFactory,
            ConfigMigrationRegistry migrationRegistry) {
        this.configDir = configDir;
        this.configPath = configDir.resolve(CONFIG_FILE_NAME);
        this.gson = gson;
        this.defaultConfigFactory = defaultConfigFactory;
        this.migrationRegistry = migrationRegistry;
        Path lockKey = this.configPath.toAbsolutePath().normalize();
        this.processLock = PATH_LOCKS.computeIfAbsent(lockKey, ignored -> new ReentrantLock(true));
    }

    /** 文件 snapshot:mtime + size，用作 write-time CAS 基线。 */
    private static final class Snapshot {
        final long lastModified;
        final long size;

        Snapshot(long lastModified, long size) {
            this.lastModified = lastModified;
            this.size = size;
        }

        boolean matches(long otherMtime, long otherSize) {
            return this.lastModified == otherMtime && this.size == otherSize;
        }

        static Snapshot of(Path path) throws IOException {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            return new Snapshot(attrs.lastModifiedTime().toMillis(), attrs.size());
        }
    }

    /** load 结果:解析得到的整体 JsonObject(unknown field 透传)。 */
    public static final class LoadedConfig {
        private final JsonObject config;

        LoadedConfig(JsonObject config) {
            this.config = config;
        }

        public JsonObject getConfig() {
            return config;
        }
    }

    @Override
    public JsonObject read() throws IOException {
        processLock.lock();
        try {
            LoadedConfig loaded = loadLocked();
            return loaded == null ? defaultConfigFactory.get() : loaded.getConfig();
        } finally {
            processLock.unlock();
        }
    }

    @Override
    public void write(JsonObject config) throws IOException {
        save(config);
    }

    @Override
    public void update(ConfigMutation mutation) throws IOException {
        processLock.lock();
        try {
            LoadedConfig loaded = loadLocked();
            JsonObject config = loaded == null ? defaultConfigFactory.get() : loaded.getConfig();
            mutation.apply(config);
            saveLocked(config);
        } finally {
            processLock.unlock();
        }
    }

    /**
     * 加载配置。malformed 文件会被 quarantine 并从最新 backup 恢复；合法配置会先执行逐级迁移。
     */
    public LoadedConfig load() throws IOException {
        processLock.lock();
        try {
            return loadLocked();
        } finally {
            processLock.unlock();
        }
    }

    private LoadedConfig loadLocked() throws IOException {
        if (!Files.exists(configPath)) {
            lastReadSnapshot.remove();
            return null;
        }

        JsonObject config;
        try {
            config = parse(configPath);
        } catch (Exception e) {
            return recoverMalformedConfig(e);
        }
        lastReadSnapshot.set(Snapshot.of(configPath));
        return migrateLoadedConfig(config);
    }

    private LoadedConfig recoverMalformedConfig(Exception cause) throws IOException {
        LOG.warn("[ConfigRepository] Config malformed, quarantining and attempting backup restore: "
                + cause.getClass().getSimpleName());
        Path quarantined = quarantine(configPath);
        Path backup1 = configDir.resolve(BACKUP_BASE + ".1");
        if (Files.exists(backup1)) {
            try {
                JsonObject backupConfig = parse(backup1);
                atomicRestore(backup1);
                lastReadSnapshot.set(Snapshot.of(configPath));
                LOG.warn("[ConfigRepository] Restored config from backup after quarantining "
                        + quarantined.getFileName());
                return migrateLoadedConfig(backupConfig);
            } catch (ConfigMigrationRegistry.InvalidConfigVersionException e) {
                throw e;
            } catch (Exception e) {
                LOG.warn("[ConfigRepository] Backup unreadable: " + e.getClass().getSimpleName());
            }
        }
        lastReadSnapshot.remove();
        return null;
    }

    private LoadedConfig migrateLoadedConfig(JsonObject config) throws IOException {
        MigrationOutcome outcome = migrationRegistry.migrate(config);
        if (outcome.changed()) {
            saveLocked(config, false);
            lastReadSnapshot.set(Snapshot.of(configPath));
        }
        if (outcome.sourceVersion() != outcome.targetVersion()) {
            LOG.info("[ConfigRepository] Migrated config schema " + outcome.sourceVersion()
                    + " -> " + outcome.targetVersion());
        }
        if (outcome.deferred()) {
            LOG.warn("[ConfigRepository] Deferred config schema migration at version "
                    + outcome.targetVersion() + " because a required secure backend is unavailable");
        }
        return new LoadedConfig(config);
    }

    /**
     * 原子保存:迁移 → ensureDir → temp(0600) → 写 → fsync → CAS → 滚动 backup → ATOMIC_MOVE。
     */
    public void save(JsonObject config) throws IOException {
        processLock.lock();
        try {
            saveLocked(config);
        } finally {
            processLock.unlock();
        }
    }

    private void saveLocked(JsonObject config) throws IOException {
        saveLocked(config, true);
    }

    private void saveLocked(JsonObject config, boolean migrate) throws IOException {
        if (migrate) {
            migrationRegistry.migrate(config);
        }
        Files.createDirectories(configDir);

        Path tmp = Files.createTempFile(configDir, "config.json-", TMP_SUFFIX);
        try {
            hardenFilePermissions(tmp);
            Files.writeString(tmp, gson.toJson(config), StandardCharsets.UTF_8);
            fsync(tmp);

            Snapshot expected = lastReadSnapshot.get();
            if (expected != null && Files.exists(configPath)) {
                Snapshot current = Snapshot.of(configPath);
                if (!expected.matches(current.lastModified, current.size)) {
                    throw new ConfigConflictException(
                            "Config changed externally since last read. Expected mtime=" + expected.lastModified
                                    + " size=" + expected.size + ", actual mtime=" + current.lastModified
                                    + " size=" + current.size);
                }
            }

            rotateBackups();
            try {
                Files.move(tmp, configPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                LOG.warn("[ConfigRepository] ATOMIC_MOVE unsupported, falling back to non-atomic move: "
                        + configPath);
                Files.move(tmp, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
            hardenFilePermissions(configPath);
            lastReadSnapshot.remove();
            LOG.debug("[ConfigRepository] Atomically saved config to " + configPath);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception e) {
                LOG.debug("[ConfigRepository] Failed to cleanup temp " + tmp + ": " + e.getMessage());
            }
        }
    }

    private JsonObject parse(Path path) throws IOException {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        return JsonParser.parseString(content).getAsJsonObject();
    }

    /** 把损坏的主文件移到 quarantine-{ts}.json(不删除，供 forensic)。 */
    private Path quarantine(Path configFile) {
        try {
            Path dest = configDir.resolve(QUARANTINE_PREFIX + System.currentTimeMillis() + ".json");
            Files.move(configFile, dest, StandardCopyOption.REPLACE_EXISTING);
            return dest;
        } catch (Exception e) {
            LOG.warn("[ConfigRepository] Failed to quarantine malformed config: " + e.getClass().getSimpleName());
            return configFile;
        }
    }

    /** 把 backup 原子恢复到主文件(temp+move，保证恢复也是原子的)。 */
    private void atomicRestore(Path backup) throws IOException {
        Path tmp = Files.createTempFile(configDir, "config-restore-", TMP_SUFFIX);
        try {
            Files.copy(backup, tmp, StandardCopyOption.REPLACE_EXISTING);
            hardenFilePermissions(tmp);
            try {
                Files.move(tmp, configPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
            hardenFilePermissions(configPath);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    /** 滚动 backup:删 .bak.{MAX}，.bak.{N-1}→.bak.N，…，.bak.1←config.json。 */
    private void rotateBackups() {
        try {
            Files.deleteIfExists(configDir.resolve(BACKUP_BASE + "." + MAX_BACKUPS));
            for (int i = MAX_BACKUPS - 1; i >= 1; i--) {
                Path src = configDir.resolve(BACKUP_BASE + "." + i);
                if (Files.exists(src)) {
                    Files.move(src, configDir.resolve(BACKUP_BASE + "." + (i + 1)),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (Files.exists(configPath)) {
                Path backup1 = configDir.resolve(BACKUP_BASE + ".1");
                Files.copy(configPath, backup1, StandardCopyOption.REPLACE_EXISTING);
                hardenFilePermissions(backup1);
            }
        } catch (Exception e) {
            LOG.warn("[ConfigRepository] Backup rotation failed (non-fatal): " + e.getClass().getSimpleName());
        }
    }

    private static void fsync(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    /** POSIX 收紧到 0600；Windows 无 POSIX 时依赖 home ACL。 */
    static void hardenFilePermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // 非 POSIX(Windows)无操作。
        }
    }

    /** CAS 冲突：read 与 write 之间文件被外部修改。 */
    public static class ConfigConflictException extends IOException {
        private static final long serialVersionUID = 1L;

        public ConfigConflictException(String message) {
            super(message);
        }
    }
}
