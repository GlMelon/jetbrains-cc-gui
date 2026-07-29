package com.github.claudecodegui.settings.migration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置迁移注册表。构造时验证迁移链完整且无重复，运行时只执行逐级迁移。
 */
public final class ConfigMigrationRegistry {

    private final int currentVersion;
    private final String schemaVersionKey;
    private final Map<Integer, ConfigMigration> migrations;

    public ConfigMigrationRegistry(int currentVersion, String schemaVersionKey, List<ConfigMigration> migrations) {
        if (currentVersion < 0) {
            throw new IllegalArgumentException("currentVersion must not be negative");
        }
        this.currentVersion = currentVersion;
        this.schemaVersionKey = schemaVersionKey;
        this.migrations = new LinkedHashMap<>();
        for (ConfigMigration migration : migrations) {
            if (migration.targetVersion() != migration.sourceVersion() + 1) {
                throw new IllegalArgumentException("Config migration must advance exactly one version: "
                        + migration.getClass().getName());
            }
            ConfigMigration previous = this.migrations.put(migration.sourceVersion(), migration);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate config migration source version: "
                        + migration.sourceVersion());
            }
        }
        for (int version = 0; version < currentVersion; version++) {
            if (!this.migrations.containsKey(version)) {
                throw new IllegalArgumentException("Missing config migration for source version: " + version);
            }
        }
    }

    public MigrationOutcome migrate(JsonObject config) throws IOException {
        int sourceVersion = readVersion(config);
        if (sourceVersion > currentVersion) {
            throw new UnsupportedConfigVersionException(sourceVersion, currentVersion);
        }

        int version = sourceVersion;
        boolean changed = false;
        boolean deferred = false;
        while (version < currentVersion) {
            ConfigMigration migration = migrations.get(version);
            if (!migration.migrate(config)) {
                deferred = true;
                break;
            }
            version = migration.targetVersion();
            config.addProperty(schemaVersionKey, version);
            changed = true;
        }
        return new MigrationOutcome(sourceVersion, version, changed, deferred);
    }

    public int currentVersion() {
        return currentVersion;
    }

    public void stampCurrentVersion(JsonObject config) {
        config.addProperty(schemaVersionKey, currentVersion);
    }

    private int readVersion(JsonObject config) throws InvalidConfigVersionException {
        JsonElement element = config.get(schemaVersionKey);
        if (element == null || element.isJsonNull()) {
            return 0;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new InvalidConfigVersionException("Config schema version must be a JSON integer");
        }
        try {
            BigDecimal decimal = element.getAsBigDecimal();
            int version = decimal.intValueExact();
            if (version < 0) {
                throw new InvalidConfigVersionException("Config schema version must not be negative");
            }
            return version;
        } catch (ArithmeticException | NumberFormatException e) {
            throw new InvalidConfigVersionException("Config schema version must be a JSON integer", e);
        }
    }

    /** 不含配置内容的迁移结果，日志只能记录版本元数据。 */
    public record MigrationOutcome(int sourceVersion, int targetVersion, boolean changed, boolean deferred) {
    }

    public static class InvalidConfigVersionException extends IOException {
        private static final long serialVersionUID = 1L;

        InvalidConfigVersionException(String message) {
            super(message);
        }

        InvalidConfigVersionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class UnsupportedConfigVersionException extends InvalidConfigVersionException {
        private static final long serialVersionUID = 1L;

        UnsupportedConfigVersionException(int actualVersion, int currentVersion) {
            super("Config schema version " + actualVersion + " is newer than supported version " + currentVersion);
        }
    }
}
