package com.github.claudecodegui.notifications;

import com.github.claudecodegui.config.ModelRegistryConfig;
import com.github.claudecodegui.model.selection.ModelSelectionResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves the model string that the status bar should display.
 * <p>
 * The status bar is only a presentation surface: model role/alias resolution is
 * performed by the backend model capability resolver, then this class selects
 * the best already-resolved field to show.
 */
public final class StatusBarModelResolver {
    private StatusBarModelResolver() {
    }

    public static @NotNull String displayModel(@Nullable ModelSelectionResult selection) {
        if (selection == null) {
            return "";
        }
        String actual = strip(selection.resolvedActualModel());
        if (!actual.isEmpty()) {
            return actual;
        }
        String stored = strip(selection.storedModel());
        if (!stored.isEmpty()) {
            return stored;
        }
        return strip(selection.selectedModel());
    }

    public static @NotNull String strip(@Nullable String model) {
        return ModelRegistryConfig.stripCapacitySuffix(model);
    }
}
