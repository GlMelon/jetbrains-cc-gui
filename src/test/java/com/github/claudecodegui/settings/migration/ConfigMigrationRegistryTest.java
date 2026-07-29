package com.github.claudecodegui.settings.migration;

import com.github.claudecodegui.settings.ConfigSchema;
import com.github.claudecodegui.settings.credentials.CredentialBackend.Availability;
import com.github.claudecodegui.settings.credentials.InMemoryCredentialBackend;
import com.github.claudecodegui.settings.credentials.PasswordStore;
import com.github.claudecodegui.settings.migration.ConfigMigrationRegistry.InvalidConfigVersionException;
import com.github.claudecodegui.settings.migration.ConfigMigrationRegistry.MigrationOutcome;
import com.github.claudecodegui.settings.migration.ConfigMigrationRegistry.UnsupportedConfigVersionException;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Config migration registry chain, validation, idempotency, and secure-secret migration tests. */
public class ConfigMigrationRegistryTest {

    @Test
    public void migrationsRunSequentiallyAndOnlyOnce() throws Exception {
        List<Integer> executed = new ArrayList<>();
        ConfigMigrationRegistry registry = new ConfigMigrationRegistry(
                2,
                ConfigSchema.SCHEMA_VERSION_KEY,
                List.of(recordingMigration(0, executed), recordingMigration(1, executed))
        );
        JsonObject config = new JsonObject();

        MigrationOutcome first = registry.migrate(config);
        MigrationOutcome second = registry.migrate(config);

        assertEquals(List.of(0, 1), executed);
        assertEquals(0, first.sourceVersion());
        assertEquals(2, first.targetVersion());
        assertTrue(first.changed());
        assertFalse(first.deferred());
        assertEquals(2, second.sourceVersion());
        assertEquals(2, second.targetVersion());
        assertFalse(second.changed());
        assertFalse(second.deferred());
    }

    @Test
    public void duplicateSourceVersionFailsFast() {
        try {
            new ConfigMigrationRegistry(
                    1,
                    ConfigSchema.SCHEMA_VERSION_KEY,
                    List.of(recordingMigration(0, new ArrayList<>()), recordingMigration(0, new ArrayList<>()))
            );
            fail("expected duplicate migration rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Duplicate"));
        }
    }

    @Test
    public void missingMigrationFailsFast() {
        try {
            new ConfigMigrationRegistry(
                    2,
                    ConfigSchema.SCHEMA_VERSION_KEY,
                    List.of(recordingMigration(0, new ArrayList<>()))
            );
            fail("expected missing migration rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Missing"));
        }
    }

    @Test
    public void migrationMustAdvanceExactlyOneVersion() {
        ConfigMigration invalid = new ConfigMigration() {
            @Override
            public int sourceVersion() {
                return 0;
            }

            @Override
            public int targetVersion() {
                return 2;
            }

            @Override
            public boolean migrate(JsonObject config) {
                return true;
            }
        };

        try {
            new ConfigMigrationRegistry(1, ConfigSchema.SCHEMA_VERSION_KEY, List.of(invalid));
            fail("expected non-sequential migration rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("exactly one version"));
        }
    }

    @Test
    public void futureVersionIsRejectedWithoutMutation() throws Exception {
        ConfigMigrationRegistry registry = registryWithNoOpChain();
        JsonObject config = new JsonObject();
        config.addProperty(ConfigSchema.SCHEMA_VERSION_KEY, ConfigSchema.CURRENT_VERSION + 1);
        config.addProperty("unknown", "preserve");
        String before = config.toString();

        try {
            registry.migrate(config);
            fail("expected future version rejection");
        } catch (UnsupportedConfigVersionException expected) {
            assertTrue(expected.getMessage().contains("newer than supported"));
        }

        assertEquals(before, config.toString());
    }

    @Test
    public void invalidSchemaVersionsAreRejected() throws Exception {
        assertInvalidVersion("{\"schemaVersion\":-1}");
        assertInvalidVersion("{\"schemaVersion\":1.5}");
        assertInvalidVersion("{\"schemaVersion\":\"1\"}");
        assertInvalidVersion("{\"schemaVersion\":true}");
        assertInvalidVersion("{\"schemaVersion\":2147483648}");
    }

    @Test
    public void deferredMigrationStopsAtLastCompletedVersion() throws Exception {
        ConfigMigrationRegistry registry = new ConfigMigrationRegistry(
                2,
                ConfigSchema.SCHEMA_VERSION_KEY,
                List.of(recordingMigration(0, new ArrayList<>()), deferredMigration(1))
        );
        JsonObject config = new JsonObject();

        MigrationOutcome outcome = registry.migrate(config);

        assertEquals(0, outcome.sourceVersion());
        assertEquals(1, outcome.targetVersion());
        assertTrue(outcome.changed());
        assertTrue(outcome.deferred());
        assertEquals(1, config.get(ConfigSchema.SCHEMA_VERSION_KEY).getAsInt());
    }

    @Test
    public void unknownFieldsSurviveMigration() throws Exception {
        ConfigMigrationRegistry registry = registryWithNoOpChain();
        JsonObject config = new JsonObject();
        config.addProperty("unknown", "preserve");
        JsonObject nested = new JsonObject();
        nested.addProperty("value", 42);
        config.add("nested", nested);

        registry.migrate(config);

        assertEquals("preserve", config.get("unknown").getAsString());
        assertEquals(42, config.getAsJsonObject("nested").get("value").getAsInt());
        assertEquals(ConfigSchema.CURRENT_VERSION,
                config.get(ConfigSchema.SCHEMA_VERSION_KEY).getAsInt());
    }

    @Test
    public void smitherySecretMovesToPasswordStoreWithoutTouchingProviderApiKeys() throws Exception {
        InMemoryCredentialBackend backend = new InMemoryCredentialBackend();
        PasswordStore passwordStore = new PasswordStore(backend);
        ConfigMigrationRegistry registry = ConfigSchema.createMigrationRegistry(passwordStore);
        JsonObject config = new JsonObject();
        config.addProperty("version", "legacy");
        config.addProperty(ConfigSchema.SMITHERY_API_KEY, "smithery-secret");
        JsonObject provider = new JsonObject();
        provider.addProperty("apiKey", "provider-secret");
        config.add("providerNative", provider);

        MigrationOutcome outcome = registry.migrate(config);

        assertEquals(ConfigSchema.CURRENT_VERSION, outcome.targetVersion());
        assertFalse(outcome.deferred());
        assertFalse(config.has("version"));
        assertFalse(config.has(ConfigSchema.SMITHERY_API_KEY));
        assertEquals("smithery-secret", passwordStore.loadPassword(ConfigSchema.SMITHERY_CREDENTIAL_KEY));
        assertEquals("provider-secret", config.getAsJsonObject("providerNative").get("apiKey").getAsString());
    }

    @Test
    public void existingSecureSmitherySecretWinsOverLegacyPlaintext() throws Exception {
        InMemoryCredentialBackend backend = new InMemoryCredentialBackend();
        PasswordStore passwordStore = new PasswordStore(backend);
        passwordStore.storePassword(ConfigSchema.SMITHERY_CREDENTIAL_KEY, "new-secure-secret");
        ConfigMigrationRegistry registry = ConfigSchema.createMigrationRegistry(passwordStore);
        JsonObject config = new JsonObject();
        config.addProperty(ConfigSchema.SMITHERY_API_KEY, "old-plaintext-secret");

        registry.migrate(config);

        assertFalse(config.has(ConfigSchema.SMITHERY_API_KEY));
        assertEquals("new-secure-secret",
                passwordStore.loadPassword(ConfigSchema.SMITHERY_CREDENTIAL_KEY));
    }


    @Test
    public void smitheryMigrationDefersUntilSecureBackendRecovers() throws Exception {
        InMemoryCredentialBackend backend = new InMemoryCredentialBackend();
        backend.setAvailability(Availability.HEADLESS_NO_BACKEND);
        PasswordStore passwordStore = new PasswordStore(backend);
        ConfigMigrationRegistry registry = ConfigSchema.createMigrationRegistry(passwordStore);
        JsonObject config = new JsonObject();
        config.addProperty(ConfigSchema.SMITHERY_API_KEY, "smithery-secret");

        MigrationOutcome deferred = registry.migrate(config);

        assertEquals(1, deferred.targetVersion());
        assertTrue(deferred.deferred());
        assertEquals("smithery-secret", config.get(ConfigSchema.SMITHERY_API_KEY).getAsString());
        assertNull(passwordStore.loadPassword(ConfigSchema.SMITHERY_CREDENTIAL_KEY));

        backend.setAvailability(Availability.AVAILABLE);
        MigrationOutcome recovered = registry.migrate(config);

        assertEquals(ConfigSchema.CURRENT_VERSION, recovered.targetVersion());
        assertFalse(recovered.deferred());
        assertFalse(config.has(ConfigSchema.SMITHERY_API_KEY));
        assertEquals("smithery-secret", passwordStore.loadPassword(ConfigSchema.SMITHERY_CREDENTIAL_KEY));
    }

    @Test
    public void smitherySecretMustBeAStringWithoutLeakingItsValue() throws Exception {
        InMemoryCredentialBackend backend = new InMemoryCredentialBackend();
        ConfigMigrationRegistry registry = ConfigSchema.createMigrationRegistry(new PasswordStore(backend));
        JsonObject config = new JsonObject();
        config.add(ConfigSchema.SMITHERY_API_KEY, new JsonObject());

        try {
            registry.migrate(config);
            fail("expected invalid Smithery key rejection");
        } catch (IOException expected) {
            assertEquals("Smithery API key must be a JSON string", expected.getMessage());
        }
        assertTrue(config.has(ConfigSchema.SMITHERY_API_KEY));
        assertEquals(1, config.get(ConfigSchema.SCHEMA_VERSION_KEY).getAsInt());
    }

    private ConfigMigrationRegistry registryWithNoOpChain() {
        return new ConfigMigrationRegistry(
                ConfigSchema.CURRENT_VERSION,
                ConfigSchema.SCHEMA_VERSION_KEY,
                List.of(recordingMigration(0, new ArrayList<>()), recordingMigration(1, new ArrayList<>()))
        );
    }

    private void assertInvalidVersion(String json) throws Exception {
        JsonObject config = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        String before = config.toString();
        try {
            registryWithNoOpChain().migrate(config);
            fail("expected invalid version rejection for " + json);
        } catch (InvalidConfigVersionException expected) {
            assertTrue(expected.getMessage().contains("schema version"));
        }
        assertEquals(before, config.toString());
    }

    private ConfigMigration recordingMigration(int sourceVersion, List<Integer> executed) {
        return new ConfigMigration() {
            @Override
            public int sourceVersion() {
                return sourceVersion;
            }

            @Override
            public boolean migrate(JsonObject config) {
                executed.add(sourceVersion);
                config.addProperty("migration" + sourceVersion, true);
                return true;
            }
        };
    }

    private ConfigMigration deferredMigration(int sourceVersion) {
        return new ConfigMigration() {
            @Override
            public int sourceVersion() {
                return sourceVersion;
            }

            @Override
            public boolean migrate(JsonObject config) {
                return false;
            }
        };
    }
}
