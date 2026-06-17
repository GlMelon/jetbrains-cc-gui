package com.github.claudecodegui.config;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModelConfigValidatorTest {

    @Test
    public void defaultModelRegistryIsValid() {
        assertTrue(ModelRegistryConfig.getDefault().validate().isValid());
    }

    @Test
    public void duplicateModelIdsWithinSameProviderAreRejected() {
        List<ModelConfig> models = new ArrayList<>(ModelRegistryConfig.getDefault().models());
        models.add(new ModelConfig("gpt-5.5", "codex", "Duplicate", "", 1_000_000, true, true));

        ModelConfigValidator.ValidationResult result =
                ModelConfigValidator.validate(new ModelRegistryConfig(models));

        assertFalse(result.isValid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("duplicate")));
    }

    @Test
    public void oneMillionSupportRequiresLargeEnoughContextWindow() {
        ModelRegistryConfig config = new ModelRegistryConfig(List.of(
                new ModelConfig("bad-1m", "codex", "Bad 1M", "", 200_000, true, true)
        ));

        ModelConfigValidator.ValidationResult result = ModelConfigValidator.validate(config);

        assertFalse(result.isValid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("supports1MContext")));
    }
}
