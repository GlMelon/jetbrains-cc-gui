package com.github.claudecodegui.notifications;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Pure presentation formatter for the IDE status bar widget.
 */
final class StatusBarPresentation {
    private StatusBarPresentation() {
    }

    record State(
            @Nullable String status,
            @Nullable String model,
            @Nullable String mode,
            @Nullable String agent,
            @Nullable String tokenInfo,
            @Nullable String details
    ) {
    }

    record Presentation(@NotNull String text, @NotNull String tooltip) {
    }

    static @NotNull Presentation present(@NotNull State state) {
        String status = trimToEmpty(state.status());
        String model = trimToEmpty(state.model());
        String mode = trimToEmpty(state.mode());
        String agent = trimToEmpty(state.agent());
        String tokenInfo = trimToEmpty(state.tokenInfo());
        String details = trimToEmpty(state.details());

        String statusLabel = statusLabel(status);
        String modeLabel = modeLabel(mode);

        StringBuilder text = new StringBuilder("GUI ").append(statusIcon(status));
        if (!model.isEmpty()) {
            text.append(" [").append(model).append("]");
        }
        if (!modeLabel.isEmpty()) {
            text.append(" {").append(modeLabel).append("}");
        }
        if (!statusLabel.isEmpty() && !CommonConstants.SESSION_STATUS_READY.equals(status)) {
            text.append(" ").append(statusLabel);
        }
        if (!tokenInfo.isEmpty()) {
            text.append(" ").append(tokenInfo);
        }

        StringBuilder tooltip = new StringBuilder(ClaudeCodeGuiBundle.message("status.tooltip.status", statusLabel));
        if (!model.isEmpty()) {
            tooltip.append(ClaudeCodeGuiBundle.message("status.tooltip.model", model));
        }
        if (!modeLabel.isEmpty()) {
            tooltip.append(ClaudeCodeGuiBundle.message("status.tooltip.mode", modeLabel));
        }
        if (!agent.isEmpty()) {
            tooltip.append(ClaudeCodeGuiBundle.message("status.tooltip.agent", agent));
        }
        if (!details.isEmpty()) {
            tooltip.append(ClaudeCodeGuiBundle.message("status.tooltip.details", details));
        }

        return new Presentation(text.toString(), tooltip.toString());
    }

    static @NotNull String statusIcon(@Nullable String status) {
        return switch (trimToEmpty(status)) {
            case CommonConstants.SESSION_STATUS_THINKING -> "💭";
            case CommonConstants.SESSION_STATUS_GENERATING -> "✏️";
            case CommonConstants.SESSION_STATUS_WAITING -> "⏳";
            case CommonConstants.SESSION_STATUS_SUCCESS -> "✓";
            case CommonConstants.SESSION_STATUS_ERROR -> "✗";
            default -> "🤖";
        };
    }

    static @NotNull String statusLabel(@Nullable String status) {
        String normalized = trimToEmpty(status);
        return switch (normalized) {
            case CommonConstants.SESSION_STATUS_READY -> ClaudeCodeGuiBundle.message("status.ready");
            case CommonConstants.SESSION_STATUS_THINKING -> ClaudeCodeGuiBundle.message("status.thinking");
            case CommonConstants.SESSION_STATUS_GENERATING -> ClaudeCodeGuiBundle.message("status.generating");
            case CommonConstants.SESSION_STATUS_WAITING -> ClaudeCodeGuiBundle.message("status.waiting");
            case CommonConstants.SESSION_STATUS_SUCCESS -> ClaudeCodeGuiBundle.message("status.success");
            case CommonConstants.SESSION_STATUS_ERROR -> ClaudeCodeGuiBundle.message("status.error");
            default -> normalized;
        };
    }

    static @NotNull String modeLabel(@Nullable String mode) {
        String normalized = trimToEmpty(mode);
        return switch (normalized) {
            case CommonConstants.PERMISSION_MODE_DEFAULT -> ClaudeCodeGuiBundle.message("status.mode.default");
            case CommonConstants.PERMISSION_MODE_PLAN -> ClaudeCodeGuiBundle.message("status.mode.plan");
            case CommonConstants.PERMISSION_MODE_ACCEPT_EDITS -> ClaudeCodeGuiBundle.message("status.mode.acceptEdits");
            case CommonConstants.PERMISSION_MODE_AUTO_EDIT -> ClaudeCodeGuiBundle.message("status.mode.autoEdit");
            case CommonConstants.PERMISSION_MODE_BYPASS -> ClaudeCodeGuiBundle.message("status.mode.bypassPermissions");
            default -> normalized;
        };
    }

    private static @NotNull String trimToEmpty(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
