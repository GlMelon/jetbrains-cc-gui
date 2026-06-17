package com.github.claudecodegui.settings;

import com.github.claudecodegui.config.ModelConfig;
import com.github.claudecodegui.config.ModelRegistryConfig;
import com.github.claudecodegui.util.PlatformUtils;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CodemossSettingsServiceModelRegistryTest {
    private String originalHomeDir;

    @After
    public void tearDown() throws Exception {
        if (originalHomeDir != null) {
            setCachedHomeDirectory(originalHomeDir);
            originalHomeDir = null;
        }
    }

    @Test
    public void returnsDefaultModelRegistryWhenConfigIsMissing() throws Exception {
        useTemporaryHomeDirectory(Files.createTempDirectory("model-registry-default-home"));

        ModelRegistryConfig config = new CodemossSettingsService().getModelRegistry();

        assertTrue(config.models().stream().anyMatch(model -> model.id().equals("claude-sonnet-4-6")));
        assertTrue(config.models().stream().anyMatch(model -> model.id().equals("gpt-5.5")));
    }

    @Test
    public void rejectsInvalidModelRegistryWithoutPersistingIt() throws Exception {
        useTemporaryHomeDirectory(Files.createTempDirectory("model-registry-invalid-home"));
        CodemossSettingsService service = new CodemossSettingsService();

        ModelRegistryConfig invalid = new ModelRegistryConfig(List.of(
                new ModelConfig("bad", "codex", "Bad", "", 200_000, true, true)
        ));

        assertFalse(service.setModelRegistry(invalid).isValid());
        assertTrue(service.getModelRegistry().models().stream().anyMatch(model -> model.id().equals("gpt-5.5")));
    }

    @Test
    public void persistsValidCustomModelRegistry() throws Exception {
        useTemporaryHomeDirectory(Files.createTempDirectory("model-registry-persist-home"));
        CodemossSettingsService service = new CodemossSettingsService();
        ModelRegistryConfig config = new ModelRegistryConfig(List.of(
                new ModelConfig("mimo-v2.5-pro", "claude", "Mimo V2.5 Pro", "", 1_000_000, true, true)
        ));

        assertTrue(service.setModelRegistry(config).isValid());

        ModelConfig saved = service.getModelRegistry().models().get(0);
        assertEquals("mimo-v2.5-pro", saved.id());
        assertEquals(1_000_000, saved.contextWindow());
    }

    private void useTemporaryHomeDirectory(Path tempHome) throws Exception {
        if (originalHomeDir == null) {
            originalHomeDir = getCachedHomeDirectory();
        }
        setCachedHomeDirectory(tempHome.toString());
        Files.createDirectories(tempHome.resolve(".codemoss"));
    }

    private String getCachedHomeDirectory() throws Exception {
        Field field = PlatformUtils.class.getDeclaredField("cachedRealHomeDir");
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private void setCachedHomeDirectory(String homeDir) throws Exception {
        Field field = PlatformUtils.class.getDeclaredField("cachedRealHomeDir");
        field.setAccessible(true);
        field.set(null, homeDir);
    }
}
