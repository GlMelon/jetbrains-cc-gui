package com.github.claudecodegui.handler;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.util.GsonHolder;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Handles native auto approval, full auto, and other permission mode get/set operations.
 */
public class PermissionModeHandler {

    private static final Logger LOG = Logger.getInstance(PermissionModeHandler.class);

    public static final String PERMISSION_MODE_PROPERTY_KEY = "claude.code.permission.mode";

    private final HandlerContext context;
    private final Gson gson = GsonHolder.GSON;

    public PermissionModeHandler(HandlerContext context) {
        this.context = context;
    }

    private String normalizeModeForCurrentProvider(String mode) {
        String normalized = mode == null ? CommonConstants.PERMISSION_MODE_DEFAULT : mode.trim();
        String provider = this.context.getCurrentProvider();
        if (provider == null || provider.isEmpty()) {
            provider = HandlerContext.DEFAULT_PROVIDER;
        }
        if (CommonConstants.PERMISSION_MODE_AUTO_EDIT.equals(normalized)) {
            normalized = ProviderType.OMP.value().equals(provider)
                    ? CommonConstants.PERMISSION_MODE_DEFAULT
                    : CommonConstants.PERMISSION_MODE_ACCEPT_EDITS;
        }
        if (CommonConstants.PERMISSION_MODE_AUTO.equals(normalized)
                && !ProviderType.CLAUDE.value().equals(provider)
                && !ProviderType.CODEX.value().equals(provider)) {
            return CommonConstants.PERMISSION_MODE_DEFAULT;
        }
        return normalized;
    }

    /**
     * Get current permission mode.
     */
    public void handleGetMode() {
        try {
            String currentMode = CommonConstants.PERMISSION_MODE_DEFAULT;  // Default value (prompt on each tool call)

            // Prefer getting from session first
            if (context.getSession() != null) {
                String sessionMode = context.getSession().getPermissionMode();
                if (sessionMode != null && !sessionMode.trim().isEmpty()) {
                    currentMode = sessionMode;
                }
            } else {
                // If session does not exist, load from persistent storage
                PropertiesComponent props = PropertiesComponent.getInstance();
                String savedMode = props.getValue(PERMISSION_MODE_PROPERTY_KEY);
                if (savedMode != null && !savedMode.trim().isEmpty()) {
                    // Keep the raw trimmed value here — the legacy autoEdit alias
                    // and per-provider fallbacks are all handled by the shared
                    // normalizeModeForCurrentProvider below, so read and write
                    // paths map the same stored value to the same effective mode.
                    currentMode = savedMode.trim();
                }
            }

            currentMode = normalizeModeForCurrentProvider(currentMode);
            final String modeToSend = currentMode;

            ApplicationManager.getApplication().invokeLater(() -> {
                context.dispatchEvent(DownstreamEvent.MODE_RECEIVED.value(), modeToSend);
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

            mode = normalizeModeForCurrentProvider(mode);

            // Check if session exists
            if (context.getSession() != null) {
                context.getSession().setPermissionMode(mode);
                String effectiveMode = context.getSession().getPermissionMode();
                if (effectiveMode == null || effectiveMode.isEmpty()) {
                    effectiveMode = CommonConstants.PERMISSION_MODE_DEFAULT;
                    context.getSession().setPermissionMode(effectiveMode);
                }

                // Save permission mode to persistent storage
                PropertiesComponent props = PropertiesComponent.getInstance();
                props.setValue(PERMISSION_MODE_PROPERTY_KEY, effectiveMode);
                LOG.info("Saved permission mode to settings: " + effectiveMode);
                com.github.claudecodegui.notifications.ClaudeNotifier.setMode(context.getProject(), effectiveMode);
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
