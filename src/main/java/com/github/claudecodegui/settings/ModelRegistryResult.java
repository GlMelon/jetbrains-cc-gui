package com.github.claudecodegui.settings;

import com.google.gson.JsonObject;
import java.util.List;

/**
 * Result of a model registry operation (get / set / reset).
 * Carries the serialized registry payload ({@code {items:[...]}}) on success,
 * or error messages on failure. Handlers translate this into downstream events.
 */
public final class ModelRegistryResult {
    private final boolean success;
    private final boolean reset;
    private final JsonObject registry;
    private final List<String> errors;

    private ModelRegistryResult(boolean success, boolean reset, JsonObject registry, List<String> errors) {
        this.success = success;
        this.reset = reset;
        this.registry = registry;
        this.errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static ModelRegistryResult success(JsonObject registry) {
        return new ModelRegistryResult(true, false, registry, List.of());
    }

    public static ModelRegistryResult resetSuccess(JsonObject registry) {
        return new ModelRegistryResult(true, true, registry, List.of());
    }

    public static ModelRegistryResult failure(String error) {
        return new ModelRegistryResult(false, false, null, List.of(error));
    }

    public static ModelRegistryResult failure(List<String> errors) {
        return new ModelRegistryResult(false, false, null, errors);
    }

    public boolean success() { return success; }
    public boolean reset() { return reset; }
    public JsonObject registry() { return registry; }
    public List<String> errors() { return errors; }
}
