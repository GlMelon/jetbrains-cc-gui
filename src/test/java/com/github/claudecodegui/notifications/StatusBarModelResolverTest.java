package com.github.claudecodegui.notifications;

import com.github.claudecodegui.model.selection.ModelSelectionResult;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class StatusBarModelResolverTest {
    @Test
    public void displayModelPrefersResolvedActualModel() {
        ModelSelectionResult selection = new ModelSelectionResult(
                "provider",
                "claude-role-sonnet",
                "claude-role-sonnet [1m]",
                "anthropic/claude-sonnet-4.5 [1m]",
                1_000_000,
                1_000_000,
                true
        );

        assertEquals("anthropic/claude-sonnet-4.5", StatusBarModelResolver.displayModel(selection));
    }

    @Test
    public void displayModelFallsBackToStoredThenSelectedModel() {
        ModelSelectionResult storedSelection = new ModelSelectionResult(
                "provider",
                "selected-model",
                "stored-model [1M]",
                "",
                200_000,
                200_000,
                false
        );
        ModelSelectionResult selectedSelection = new ModelSelectionResult(
                "provider",
                "selected-model [1m]",
                "",
                "",
                200_000,
                200_000,
                false
        );

        assertEquals("stored-model", StatusBarModelResolver.displayModel(storedSelection));
        assertEquals("selected-model", StatusBarModelResolver.displayModel(selectedSelection));
    }

    @Test
    public void displayModelHandlesMissingSelection() {
        assertEquals("", StatusBarModelResolver.displayModel(null));
    }
}
