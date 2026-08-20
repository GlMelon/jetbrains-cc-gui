package com.github.claudecodegui.util;

import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * System notification service.
 * Provides IDE native notifications for task completion.
 *
 * <p>Registered as an application-level service via {@code @Service(Service.Level.APP)}.
 * The platform manages instantiation; callers resolve the singleton through {@link #getInstance()}.
 *
 * @author melon
 */
@Service(Service.Level.APP)
public class SystemNotificationService {

    private static final Logger LOG = Logger.getInstance(SystemNotificationService.class);

    private static final int MAX_MESSAGE_LENGTH = 220;
    // Tightened so titles fit on a single 14pt-bold line in WINDOW_WIDTH for both
    // CJK and Latin scripts, avoiding HTML JLabel height-measurement issues
    // that can clip wrapped titles inside BoxLayout.
    private static final int MAX_TITLE_LENGTH = 35;
    private static final int MESSAGE_ELLIPSIS_TAIL = 3;

    /**
     * Public no-arg constructor: required for platform {@code applicationService} registration.
     */
    public SystemNotificationService() {
    }

    /**
     * Resolve the shared SystemNotificationService instance.
     * Prefers the platform-managed application service; falls back to a lazily created
     * instance for edge cases (early bootstrap / isolated unit tests).
     */
    public static SystemNotificationService getInstance() {
        try {
            SystemNotificationService service =
                    ApplicationManager.getApplication().getService(SystemNotificationService.class);
            if (service != null) {
                return service;
            }
        } catch (RuntimeException ignored) {
            // ApplicationManager unavailable (isolated tests / plugin bootstrap).
        }
        return Holder.INSTANCE;
    }

    /**
     * Fallback instance for edge cases where the platform service is not resolvable.
     */
    private static final class Holder {
        private static final SystemNotificationService INSTANCE = new SystemNotificationService();

        private Holder() {
        }
    }

    /**
     * Show visual notification window with a fallback title.
     *
     * @param project project
     * @param message message
     */
    public void showVisualNotificationToast(@NotNull Project project, String message) {
        showVisualNotificationToast(project, null, message);
    }

    /**
     * Show visual notification window with an explicit dynamic title.
     * When {@code title} is null/blank, the i18n default
     * {@code notifier.taskComplete.title} is used.
     *
     * @param project project
     * @param title title
     * @param message message
     */
    public void showVisualNotificationToast(@NotNull Project project, @Nullable String title, String message) {
        showVisualNotificationToast(project, title, message, isTaskCompletionEnabled());
    }

    /**
     * Show the AskUserQuestion reminder toast. Gated by the
     * {@code askUserQuestionNotificationEnabled} setting (opt-in, default false).
     * Reuses the same slide-in toast window as the task-completion notification so
     * the visual effect matches it exactly.
     */
    public void showAskUserQuestionReminderToast(@NotNull Project project) {
        showVisualNotificationToast(project,
            ClaudeCodeGuiBundle.message("notifier.askUserQuestion.title"),
            ClaudeCodeGuiBundle.message("notifier.askUserQuestion.message"),
            isAskUserQuestionEnabled());
    }

    /**
     * Core toast renderer. The {@code enabled} flag is resolved by the caller against
     * the appropriate setting so a single notification feature's opt-in gate cannot
     * accidentally drive another feature's toast.
     */
    private void showVisualNotificationToast(@NotNull Project project, @Nullable String title,
                                             String message, boolean enabled) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) {
                return;
            }
            try {
                if (!enabled) {
                    return;
                }
                String resolvedTitle = sanitizeTitle(title);
                String resolvedMessage = sanitizeMessage(message);

                showIdeNativeNotification(project, resolvedTitle, resolvedMessage);
            } catch (Exception e) {
                LOG.warn("[SystemNotification] Failed to show visual notification: " + e.getMessage(), e);
            }
        });
    }

    private boolean isTaskCompletionEnabled() {
        try {
            return new CodemossSettingsService().getTaskCompletionNotificationEnabled();
        } catch (Exception e) {
            LOG.debug("[SystemNotification] Failed to read task completion flag, defaulting to false: " + e.getMessage());
            return false;
        }
    }

    private boolean isAskUserQuestionEnabled() {
        try {
            return new CodemossSettingsService().getAskUserQuestionNotificationEnabled();
        } catch (Exception e) {
            LOG.debug("[SystemNotification] Failed to read ask user question flag, defaulting to false: " + e.getMessage());
            return false;
        }
    }

    /**
     * Truncate by code points (so emoji and surrogate pairs stay intact).
     *
     * @param raw raw
     * @return string
     */
    private String sanitizeMessage(String raw) {
        return truncate(raw == null ? "" : raw, MAX_MESSAGE_LENGTH);
    }

    /**
     * Resolve the toast title: use the caller-provided title when non-blank,
     * otherwise fall back to the i18n default. The returned string is truncated.
     *
     * @param raw raw
     * @return string
     */
    private String sanitizeTitle(@Nullable String raw) {
        String chosen = (raw == null || raw.trim().isEmpty())
            ? ClaudeCodeGuiBundle.message("notifier.taskComplete.title")
            : raw.trim();
        return truncate(chosen, MAX_TITLE_LENGTH);
    }

    /**
     * Truncate text to max length (by code points).
     *
     * @param text text
     * @param maxLength max length
     * @return truncated string
     */
    private static String truncate(String text, int maxLength) {
        int cpCount = text.codePointCount(0, text.length());
        if (cpCount > maxLength) {
            int end = text.offsetByCodePoints(0, maxLength - MESSAGE_ELLIPSIS_TAIL);
            return text.substring(0, end) + "...";
        }
        return text;
    }

    /**
     * Show Ide Native Notification
     *
     * @param project project
     * @param title title
     * @param message message
     */
    private void showIdeNativeNotification(@NotNull Project project, String title, String message) {
        Notification notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("AI Code GUI Notifications")
            .createNotification(title, message, NotificationType.INFORMATION);
        notification.notify(project);
    }
}
