package com.github.claudecodegui.settings.migration;

import com.google.gson.JsonObject;

/** 将旧 {@code version} 元数据收口为 registry 管理的 {@code schemaVersion}。 */
public final class LegacyVersionFieldMigration implements ConfigMigration {

    private final String legacyVersionKey;

    public LegacyVersionFieldMigration(String legacyVersionKey) {
        this.legacyVersionKey = legacyVersionKey;
    }

    @Override
    public int sourceVersion() {
        return 0;
    }

    @Override
    public boolean migrate(JsonObject config) {
        config.remove(legacyVersionKey);
        return true;
    }
}
