package com.github.claudecodegui.settings;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.settings.credentials.PasswordStore;
import com.github.claudecodegui.settings.migration.ConfigMigrationRegistry;
import com.github.claudecodegui.settings.migration.LegacyVersionFieldMigration;
import com.github.claudecodegui.settings.migration.SmitheryApiKeyMigration;
import com.google.gson.JsonObject;

import java.util.List;

/** 插件自有 config.json schema、默认值与迁移装配的单一真相源。 */
public final class ConfigSchema {

    public static final String SCHEMA_VERSION_KEY = "schemaVersion";
    public static final int CURRENT_VERSION = 2;
    public static final String SMITHERY_API_KEY = "smitheryApiKey";
    public static final String SMITHERY_CREDENTIAL_KEY = "codemoss.smithery.apiKey";

    private static final String LEGACY_VERSION_KEY = "version";
    private static final String CURRENT_PROVIDER_KEY = "current";
    private static final String PROVIDERS_KEY = "providers";
    private static final String LOCAL_CONFIG_AUTHORIZED_KEY = "localConfigAuthorized";

    private ConfigSchema() {
    }

    public static JsonObject createDefaultConfig() {
        JsonObject config = new JsonObject();
        config.addProperty(SCHEMA_VERSION_KEY, CURRENT_VERSION);

        JsonObject claude = new JsonObject();
        claude.addProperty(CURRENT_PROVIDER_KEY, "");
        claude.add(PROVIDERS_KEY, new JsonObject());
        config.add(CommonConstants.PROVIDER_CLAUDE, claude);

        JsonObject codex = new JsonObject();
        codex.addProperty(CURRENT_PROVIDER_KEY, "");
        codex.add(PROVIDERS_KEY, new JsonObject());
        codex.addProperty(LOCAL_CONFIG_AUTHORIZED_KEY, false);
        config.add(ProviderType.CODEX.value(), codex);
        return config;
    }

    public static ConfigMigrationRegistry createMigrationRegistry(PasswordStore passwordStore) {
        return new ConfigMigrationRegistry(
                CURRENT_VERSION,
                SCHEMA_VERSION_KEY,
                List.of(
                        new LegacyVersionFieldMigration(LEGACY_VERSION_KEY),
                        new SmitheryApiKeyMigration(SMITHERY_API_KEY, SMITHERY_CREDENTIAL_KEY, passwordStore)
                )
        );
    }
}
