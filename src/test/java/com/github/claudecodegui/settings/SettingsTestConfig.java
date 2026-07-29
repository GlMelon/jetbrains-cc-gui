package com.github.claudecodegui.settings;

import com.github.claudecodegui.settings.credentials.InMemoryCredentialBackend;
import com.github.claudecodegui.settings.credentials.PasswordStore;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** Shared config-store fixture for settings domain tests. */
final class SettingsTestConfig {

    private SettingsTestConfig() {
    }

    static Fixture create() {
        PasswordStore passwordStore = new PasswordStore(new InMemoryCredentialBackend());
        Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
        ConfigPathManager pathManager = new ConfigPathManager();
        ConfigStore configStore = new ConfigRepository(
                pathManager.getConfigDir(),
                gson,
                ConfigSchema::createDefaultConfig,
                ConfigSchema.createMigrationRegistry(passwordStore)
        );
        return new Fixture(configStore, passwordStore);
    }

    record Fixture(ConfigStore configStore, PasswordStore passwordStore) {
    }
}
