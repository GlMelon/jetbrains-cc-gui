package com.github.claudecodegui.handler;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.util.GsonHolder;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Handles permission mode (bypassPermissions, etc.) get/set operations.
 */
public class PermissionModeHandler {

    private static final Logger LOG = Logger.getInstance(PermissionModeHandler.class);

    static final String PERMISSION_MODE_PROPERTY_KEY = "claude.code.permission.mode";

    private final HandlerContext context;
    private final Gson gson = GsonHolder.GSON;

    public PermissionModeHandler(HandlerContext context) {
        this.context = context;
    }

    /**
     * Get current permission mode.
     */
    public void handleGetMode() {
        try {
            String currentMode = CommonConstants.PERMISSION_MODE_DEFAULT;  // Default value (prompt on each tool call)

            // Prefer getting from session first
            if (this.context.getSession() != null) {
                String sessionMode = this.context.getSession().getPermissionMode();
                if (sessionMode != null && !sessionMode.trim().isEmpty()) {
                    currentMode = sessionMode;
                }
            } else {
                // If session does not exist, load from persistent storage
                PropertiesComponent props = PropertiesComponent.getInstance();
                String savedMode = props.getValue(PERMISSION_MODE_PROPERTY_KEY);
                if (savedMode != null && !savedMode.trim().isEmpty()) {
                    currentMode = savedMode.trim();
                }
            }

            final String modeToSend = currentMode;

            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent(DownstreamEvent.MODE_RECEIVED.value(), context.escapeJs(modeToSend));
            });
        } catch (Exception e) {
            LOG.error("[PermissionModeHandler] Failed to get mode: " + e.getMessage(), e);
        }
    }

    /**
     * Handle set mode request.
     */
    public void handleSetMode(String content) {
        try {
            String mode = parseMode(content);

            // Check if session exists
            if (this.context.getSession() != null) {
                this.context.getSession().setPermissionMode(mode);

                // Save permission mode to persistent storage
                PropertiesComponent props = PropertiesComponent.getInstance();
                props.setValue(PERMISSION_MODE_PROPERTY_KEY, mode);
                LOG.info("Saved permission mode to settings: " + mode);
                com.github.claudecodegui.notifications.ClaudeNotifier.setMode(this.context.getProject(), mode);

                // Push the new mode to the live runtime so it takes effect on the
                // next tool call in the current turn, mirroring the TUI's instant
                // mode switch instead of waiting for the next user message.
                pushPermissionModeLive(mode);
            } else {
                LOG.warn("[PermissionModeHandler] WARNING: Session is null! Cannot set permission mode");
            }
        } catch (Exception e) {
            LOG.error("[PermissionModeHandler] Failed to set mode: " + e.getMessage(), e);
        }
    }

    public void handleSetSessionMode(String content) {
        try {
            String mode = parseMode(content);
            if (context.getSession() != null) {
                context.getSession().setPermissionMode(mode);
                com.github.claudecodegui.notifications.ClaudeNotifier.setMode(context.getProject(), mode);
            } else {
                LOG.warn("[PermissionModeHandler] Session is null; cannot set session permission mode");
            }
        } catch (Exception e) {
            LOG.error("[PermissionModeHandler] Failed to set session mode: " + e.getMessage(), e);
        }
    }

    private String parseMode(String content) {
        String mode = content;
        if (content != null && !content.isEmpty()) {
            try {
                JsonObject json = gson.fromJson(content, JsonObject.class);
                if (json.has("mode")) {
                    mode = json.get("mode").getAsString();
                }
            } catch (Exception e) {
                // content itself is the mode
            }
        }
        return mode;
    }
}
