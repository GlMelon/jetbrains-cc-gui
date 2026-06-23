package com.github.claudecodegui.handler.window;

import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Window action handlers container.
 * Holds shared callback interface for window-level events.
 */
public class WindowActionHandlers {

    private static final Logger LOG = Logger.getInstance(WindowActionHandlers.class);

    /**
     * Callback interface for window-level operations.
     */
    public interface Callback {
        void onHeartbeat(String content);
        void onTabLoadingChanged(boolean loading);
        void onTabStatusChanged(String status);
        void onCreateNewSession();
        void onFrontendReady();
        void onRefreshSlashCommands();
    }

    private final Callback callback;

    public WindowActionHandlers(Callback callback) {
        this.callback = callback;
    }

    // --- Response-handling methods (called by typed handlers) ---

    void handleHeartbeat(String content) {
        callback.onHeartbeat(content);
    }

    void handleTabLoadingChanged(String content) {
        try {
            JsonObject json = GsonHolder.GSON.fromJson(content, JsonObject.class);
            boolean loading = json.has("loading") && json.get("loading").getAsBoolean();
            callback.onTabLoadingChanged(loading);
        } catch (Exception e) {
            LOG.warn("[TabLoading] Failed to parse loading state: " + e.getMessage());
        }
    }

    void handleTabStatusChanged(String content) {
        try {
            JsonObject json = GsonHolder.GSON.fromJson(content, JsonObject.class);
            String statusStr = json.has("status") ? json.get("status").getAsString() : "idle";
            callback.onTabStatusChanged(statusStr);
        } catch (Exception e) {
            LOG.warn("[TabStatus] Failed to parse tab status: " + e.getMessage());
        }
    }

    void handleCreateNewSession() {
        callback.onCreateNewSession();
    }

    void handleFrontendReady() {
        callback.onFrontendReady();
    }

    void handleRefreshSlashCommands() {
        callback.onRefreshSlashCommands();
    }
}
