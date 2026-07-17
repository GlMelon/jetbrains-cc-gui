package com.github.claudecodegui.settings;

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

/**
 * 配置文件(~/.codemoss/config.json)的原子读写仓库(A3 / docs §A3 聚焦核心范围)。
 *
 * <p>作为 {@link CodemossSettingsService} Facade 的内部实现,收口所有 config.json 的 IO:
 * <ul>
 *   <li><b>原子写入</b>:temp 文件(同目录)+ {@link FileChannel#force(boolean) fsync} +
 *       {@link StandardCopyOption#ATOMIC_MOVE}(根治裸 {@code FileWriter} 直写崩溃半写截断 JSON)。</li>
 *   <li><b>write-time CAS</b>:mtime+size snapshot 比对,检测 cc-switch / 外部编辑导致的 lost update
 *       (冲突抛 {@link ConfigConflictException},不再静默覆盖外部改动)。</li>
 *   <li><b>malformed quarantine</b>:损坏文件隔离到 {@code config.json.quarantine-<ts>.json} 供 forensic,
 *       并从最新 backup 原子恢复主文件(不再静默用 default 覆盖、彻底抹掉原配置)。</li>
 *   <li><b>多版本 backup</b>:滚动保留 {@value #MAX_BACKUPS} 份 {@code config.json.bak.<n>}(均 0600,
 *       含 secret 故收紧权限)。</li>
 *   <li><b>unknown field 透传</b>:load/save 均操作整体 {@link JsonObject},未映射字段天然保留。</li>
 * </ul>
 *
 * <p><b>Facade 不变</b>:CodemossSettingsService.readConfig/writeConfig 签名不变,43 个调用点与 5 个
 * 子 Manager 的 lambda 闭包零改动,仅内部委托本类。
 *
 * <p><b>snapshot 线程局部</b>:read 时记录 mtime+size 到 ThreadLocal,write 时比对 —— 同线程
 * read-modify-write 准确(主线程 vs cc-switch 外部编辑);跨线程 RMW 宽松跳过 CAS(A3 聚焦核心,
 * 不彻底解决 in-process 并发 RMW,那需 in-process 锁,更大改动,列为后续)。
 *
 * <p><b>本范围未含(独立立项)</b>:F9 migration registry(schemaVersion 读写闭环 + 逐级幂等迁移 +
 * secret 脱敏)、in-process 写锁。A3 是它们的前置地基。
 */
public class ConfigRepository {

    private static final Logger LOG = Logger.getInstance(ConfigRepository.class);

    /** 保留的滚动 backup 份数(不含主文件)。 */
    static final int MAX_BACKUPS = 5;
    private static final String CONFIG_FILE_NAME = "config.json";
    private static final String BACKUP_BASE = "config.json.bak";
    private static final String QUARANTINE_PREFIX = "config.json.quarantine-";
    private static final String TMP_SUFFIX = ".tmp";

    private final Path configDir;
    private final Path configPath;
    private final Gson gson;

    /** 同线程 read 时记录的文件 snapshot,供 write 时 CAS 比对。 */
    private final ThreadLocal<Snapshot> lastReadSnapshot = new ThreadLocal<>();

    public ConfigRepository(Path configDir, Gson gson) {
        this.configDir = configDir;
        this.configPath = configDir.resolve(CONFIG_FILE_NAME);
        this.gson = gson;
    }

    /** 文件 snapshot:mtime + size,用作 write-time CAS 基线。 */
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

        static Snapshot of(Path p) throws IOException {
            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
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

    /**
     * 加载配置:
     * <ul>
     *   <li>文件不存在 → 返回 {@code null}(Facade 用 default);</li>
     *   <li>解析成功 → 返回 {@link LoadedConfig},并记录 ThreadLocal snapshot;</li>
     *   <li>malformed → quarantine 主文件 + 从 {@code .bak.1} 原子恢复主文件 + 返回恢复内容
     *       (无可用 backup 则返回 null)。</li>
     * </ul>
     */
    public LoadedConfig load() throws IOException {
        if (!Files.exists(configPath)) {
            lastReadSnapshot.remove();
            return null;
        }
        try {
            JsonObject config = parse(configPath);
            lastReadSnapshot.set(Snapshot.of(configPath));
            return new LoadedConfig(config);
        } catch (Exception e) {
            LOG.warn("[ConfigRepository] Config malformed, quarantining and attempting backup restore: "
                    + e.getMessage());
            Path quarantined = quarantine(configPath);
            Path backup1 = configDir.resolve(BACKUP_BASE + ".1");
            if (Files.exists(backup1)) {
                try {
                    JsonObject backupConfig = parse(backup1);
                    // 把 backup 原子恢复到主文件,避免主文件持续处于丢失/损坏态。
                    atomicRestore(backup1);
                    lastReadSnapshot.set(Snapshot.of(configPath));
                    LOG.warn("[ConfigRepository] Restored config from " + backup1
                            + " after quarantining " + quarantined);
                    return new LoadedConfig(backupConfig);
                } catch (Exception be) {
                    LOG.warn("[ConfigRepository] Backup " + backup1 + " also unreadable: " + be.getMessage());
                }
            }
            lastReadSnapshot.remove();
            return null;
        }
    }

    /**
     * 原子保存:ensureDir → 滚动 backup → temp(0600) → 写 → fsync → CAS → ATOMIC_MOVE。
     * CAS 冲突抛 {@link ConfigConflictException};任何失败均不破坏现有 config(finally 清理 temp)。
     */
    public void save(JsonObject config) throws IOException {
        Files.createDirectories(configDir);

        // 1. 滚动 backup(写前保留历史;backup 失败不阻塞写,仅告警)。
        rotateBackups();

        // 2. temp 文件(同目录,保证 ATOMIC_MOVE 可用;createTempFile 后立即收紧权限)。
        Path tmp = Files.createTempFile(configDir, "config.json-", TMP_SUFFIX);
        try {
            hardenFilePermissions(tmp);
            Files.writeString(tmp, gson.toJson(config), StandardCharsets.UTF_8);

            // 3. fsync:确保数据落盘(move 前内核不得丢失)。
            fsync(tmp);

            // 4. write-time CAS:比对 read 时 snapshot vs 当前文件 mtime+size。
            //    expected==null 表示本线程未 read(如 createDefaultConfig 直写新文件)→ 跳过 CAS。
            Snapshot expected = lastReadSnapshot.get();
            if (expected != null && Files.exists(configPath)) {
                Snapshot current = Snapshot.of(configPath);
                if (!expected.matches(current.lastModified, current.size)) {
                    throw new ConfigConflictException(
                            "Config changed externally since last read (cc-switch / external edit). "
                                    + "Expected mtime=" + expected.lastModified + " size=" + expected.size
                                    + ", actual mtime=" + current.lastModified + " size=" + current.size);
                }
            }

            // 5. 原子 move(ATOMIC_MOVE 失败回退非原子,仅告警——跨卷 FS 无法原子)。
            try {
                Files.move(tmp, configPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                LOG.warn("[ConfigRepository] ATOMIC_MOVE unsupported, falling back to non-atomic move: "
                        + configPath);
                Files.move(tmp, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
            hardenFilePermissions(configPath); // move 可能重置权限,确保 0600。

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

    // ==================== helpers ====================

    private JsonObject parse(Path p) throws IOException {
        String content = Files.readString(p, StandardCharsets.UTF_8);
        return JsonParser.parseString(content).getAsJsonObject();
    }

    /** 把损坏的主文件移到 quarantine-{ts}.json(不删除,供 forensic)。 */
    private Path quarantine(Path configFile) {
        try {
            Path dest = configDir.resolve(QUARANTINE_PREFIX + System.currentTimeMillis() + ".json");
            Files.move(configFile, dest, StandardCopyOption.REPLACE_EXISTING);
            return dest;
        } catch (Exception e) {
            LOG.warn("[ConfigRepository] Failed to quarantine malformed config: " + e.getMessage());
            return configFile;
        }
    }

    /** 把 backup 原子恢复到主文件(temp+move,保证恢复也是原子的)。 */
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

    /** 滚动 backup:删 .bak.{MAX},.bak.{N-1}→.bak.N,…,.bak.1←config.json。 */
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
                Path bak1 = configDir.resolve(BACKUP_BASE + ".1");
                Files.copy(configPath, bak1, StandardCopyOption.REPLACE_EXISTING);
                hardenFilePermissions(bak1); // backup 含 secret,0600。
            }
        } catch (Exception e) {
            LOG.warn("[ConfigRepository] Backup rotation failed (non-fatal): " + e.getMessage());
        }
    }

    private static void fsync(Path p) throws IOException {
        try (FileChannel ch = FileChannel.open(p, StandardOpenOption.WRITE)) {
            ch.force(true); // true = 含 metadata
        }
    }

    /** POSIX 收紧到 0600(含 secret 的 config/backup/temp)。Windows 无 POSIX → no-op(home ACL 隔离)。 */
    static void hardenFilePermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // 非 POSIX(Windows)无操作。
        }
    }

    /** CAS 冲突(外部编辑检测):read 与 write 之间文件被外部改动(cc-switch / 并发写)。 */
    public static class ConfigConflictException extends IOException {
        private static final long serialVersionUID = 1L;

        public ConfigConflictException(String message) {
            super(message);
        }
    }
}
