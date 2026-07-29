package com.github.claudecodegui.settings.migration;

import com.github.claudecodegui.settings.credentials.CredentialBackend.Availability;
import com.github.claudecodegui.settings.credentials.PasswordStore;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;

/** 将插件自有 Smithery API key 从明文 config.json 迁移到 PasswordSafe。 */
public final class SmitheryApiKeyMigration implements ConfigMigration {

    private final String configKey;
    private final String credentialKey;
    private final PasswordStore passwordStore;

    public SmitheryApiKeyMigration(String configKey, String credentialKey, PasswordStore passwordStore) {
        this.configKey = configKey;
        this.credentialKey = credentialKey;
        this.passwordStore = passwordStore;
    }

    @Override
    public int sourceVersion() {
        return 1;
    }

    @Override
    public boolean migrate(JsonObject config) throws IOException {
        JsonElement element = config.get(configKey);
        if (element == null || element.isJsonNull()) {
            config.remove(configKey);
            return true;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IOException("Smithery API key must be a JSON string");
        }

        String secret = element.getAsString();
        if (secret.isEmpty()) {
            config.remove(configKey);
            return true;
        }
        if (passwordStore.getAvailability() != Availability.AVAILABLE) {
            return false;
        }

        if (passwordStore.loadPassword(credentialKey) == null) {
            passwordStore.storePassword(credentialKey, secret);
        }
        config.remove(configKey);
        return true;
    }
}
