package com.github.claudecodegui.util;

import com.google.gson.JsonObject;
import com.intellij.ide.ui.LafManager;
import com.intellij.ide.ui.LafManagerListener;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.ui.JBColor;

import java.awt.*;

/**
 * IDE theme configuration service.
 * Retrieves the current UI theme (light/dark) from IDEA and provides it to the Webview.
 *
 * Uses IntelliJ Platform public APIs:
 * - JBColor.isBright() - detects whether the current theme is light
 * - LafManagerListener - listens for all theme change events (including Sync with OS)
 *
 * Lifecycle: registered as an application-scoped platform service (plugin.xml
 * {@code <applicationService>}) and implements {@link Disposable}. The MessageBusConnection
 * is created with {@code this} as the parent disposable, so the platform releases it
 * automatically on plugin unload / IDE shutdown — replacing the old hand-rolled
 * {@code static} listener that leaked the connection whenever the plugin was disabled
 * without exiting the JVM. A fallback instance is used only when the application is not
 * yet resolvable (early bootstrap / isolated unit tests), mirroring
 * {@code NodeService#getInstance()}.
 *
 * References:
 * - https://github.com/JetBrains/intellij-community/blob/master/platform/util/ui/src/com/intellij/ui/JBColor.java
 * - https://plugins.jetbrains.com/docs/intellij/themes-getting-started.html
 */
public class ThemeConfigService implements Disposable {

    private static final Logger LOG = Logger.getInstance(ThemeConfigService.class);

    // Theme background color constants - centrally managed for frontend/backend consistency
    public static final Color DARK_BG_COLOR = new Color(30, 30, 30);   // #1e1e1e
    public static final Color LIGHT_BG_COLOR = Color.WHITE;             // #ffffff
    public static final String DARK_BG_HEX = "#1e1e1e";
    public static final String LIGHT_BG_HEX = "#ffffff";

    // Mutable per-service state (was static; now instance-scoped so dispose can release it).
    private volatile ThemeChangeCallback themeChangeCallback = null;
    private volatile Boolean lastKnownIsDark = null; // Cache the last known theme state for deduplication
    private volatile boolean listenerRegistered = false;

    // Fallback instance: only used before the platform service is resolvable (very early
    // bootstrap / isolated unit tests). Mirrors NodeService's fallbackInstance pattern.
    private static volatile ThemeConfigService fallbackInstance;
    private static final Object lock = new Object();

    /**
     * Public no-arg constructor: required for platform {@code applicationService}
     * registration (see plugin.xml). Mirrors {@code NodeService} /
     * {@code ConfigFileWatcherService}.
     */
    public ThemeConfigService() {
    }

    /**
     * Resolve the shared ThemeConfigService. Prefers the platform-managed application
     * service (auto-disposed on plugin unload / IDE shutdown); falls back to a lazily
     * created instance when the application is not yet resolvable (early bootstrap /
     * isolated unit tests), mirroring {@code NodeService#getInstance()}'s try/catch fallback.
     */
    public static ThemeConfigService getInstance() {
        try {
            ThemeConfigService service = ApplicationManager.getApplication().getService(ThemeConfigService.class);
            if (service != null) {
                return service;
            }
        } catch (RuntimeException ignored) {
            // ApplicationManager unavailable (isolated tests / plugin bootstrap).
        }
        synchronized (lock) {
            if (fallbackInstance == null) {
                fallbackInstance = new ThemeConfigService();
            }
            return fallbackInstance;
        }
    }

    /**
     * Callback interface for theme changes.
     */
    public interface ThemeChangeCallback {
        void onThemeChanged(JsonObject themeConfig);
    }

    // ── Static façade (preserves all existing call sites) ────────────────

    /**
     * Register a theme change listener.
     *
     * @see #registerCallback(ThemeChangeCallback)
     */
    public static void registerThemeChangeListener(ThemeChangeCallback callback) {
        getInstance().registerCallback(callback);
    }

    /**
     * Get the IDE theme configuration.
     *
     * @see #getIdeThemeConfigInternal()
     */
    public static JsonObject getIdeThemeConfig() {
        return getInstance().getIdeThemeConfigInternal();
    }

    /**
     * Get the theme configuration as a JSON string.
     *
     * @see #getIdeThemeConfigJsonInternal()
     */
    public static String getIdeThemeConfigJson() {
        return getInstance().getIdeThemeConfigJsonInternal();
    }

    /**
     * Get the Swing background color corresponding to the current IDE theme.
     *
     * @see #getBackgroundColorInternal()
     */
    public static Color getBackgroundColor() {
        return getInstance().getBackgroundColorInternal();
    }

    /**
     * Get the hex color value corresponding to the current IDE theme.
     *
     * @see #getBackgroundColorHexInternal()
     */
    public static String getBackgroundColorHex() {
        return getInstance().getBackgroundColorHexInternal();
    }

    // ── Instance implementation ──────────────────────────────────────────

    /**
     * Register a theme change listener.
     * Uses LafManagerListener to listen for all Look and Feel changes.
     *
     * The listener fires in these situations:
     * - User manually switches theme (View - Appearance - Theme)
     * - IDE follows OS theme changes (Settings - Sync with OS enabled)
     * - Toggling Sync with OS causes an actual theme change
     * - Installing or switching to a custom theme
     *
     * Notes:
     * - The listener is registered once per service instance and remains active for the
     *   service's lifecycle; the MessageBusConnection is parented to {@code this} and
     *   released by the platform on dispose (no manual disconnect needed).
     * - Each call updates the callback, supporting project close/reopen scenarios.
     */
    private void registerCallback(ThemeChangeCallback callback) {
        // Always update the callback to support project reopen scenarios
        // Even if listenerRegistered is true, the callback needs updating after project reopen
        themeChangeCallback = callback;
        LOG.info("[ThemeConfig] Theme change callback updated");

        // Register the listener only once (Application level)
        if (listenerRegistered) {
            LOG.debug("[ThemeConfig] Listener already registered, callback updated");
            return;
        }

        listenerRegistered = true;

        try {
            // Register on the Application-level MessageBus with this service as the parent
            // disposable. The platform releases the connection when this service is disposed
            // (plugin unload / IDE shutdown), so it can never leak the way the old unparented
            // static connect() did.
            ApplicationManager.getApplication().getMessageBus()
                .connect(this)
                .subscribe(LafManagerListener.TOPIC, new LafManagerListener() {
                    @Override
                    public void lookAndFeelChanged(LafManager source) {
                        LOG.info("[ThemeConfig] Look and Feel changed event received");

                        // Defer execution to ensure the UI theme is fully updated
                        // Using invokeLater ensures this runs on the next EDT cycle, when the new theme is in effect
                        ApplicationManager.getApplication().invokeLater(() -> {
                            notifyThemeChange();
                        });
                    }
                });

            LOG.info("[ThemeConfig] Theme change listener registered successfully (Application level)");
        } catch (Exception e) {
            LOG.error("[ThemeConfig] Failed to register theme change listener: " + e.getMessage(), e);
        }
    }

    /**
     * Notify the frontend of a theme change.
     * Only sends a notification when the theme actually changes, avoiding duplicate notifications and unnecessary UI updates.
     */
    private void notifyThemeChange() {
        if (themeChangeCallback == null) {
            LOG.warn("[ThemeConfig] Theme callback is null, cannot notify");
            return;
        }

        try {
            JsonObject config = getIdeThemeConfigInternal();
            boolean currentIsDark = config.get("isDark").getAsBoolean();

            // Deduplicate: skip notification if the theme state hasn't changed
            if (lastKnownIsDark != null && lastKnownIsDark == currentIsDark) {
                LOG.debug("[ThemeConfig] Theme state unchanged (isDark=" + currentIsDark + "), skipping notification");
                return;
            }

            // Update cache and notify
            lastKnownIsDark = currentIsDark;
            LOG.info("[ThemeConfig] Theme changed to: " + (currentIsDark ? "DARK" : "LIGHT") + ", notifying webview");
            themeChangeCallback.onThemeChanged(config);
        } catch (Exception e) {
            LOG.error("[ThemeConfig] Failed to notify theme change: " + e.getMessage(), e);
        }
    }

    /**
     * Get the IDE theme configuration.
     *
     * Uses the IntelliJ Platform public API JBColor.isBright().
     * JBColor.isBright() returns true for a light theme; negating it gives the dark theme state.
     *
     * @return a JsonObject containing the theme config, format: {"isDark": true/false}
     */
    private JsonObject getIdeThemeConfigInternal() {
        JsonObject config = new JsonObject();

        try {
            // Use IntelliJ's public API to detect whether the theme is dark
            // JBColor.isBright() returns true for light theme; negate to get dark theme
            boolean isDark = !JBColor.isBright();

            config.addProperty("isDark", isDark);

            LOG.debug("[ThemeConfig] Retrieved IDE theme config: isDark=" + isDark);
        } catch (Exception e) {
            // Fall back to default (dark) on exception
            config.addProperty("isDark", true);
            LOG.error("[ThemeConfig] Failed to get theme config, using default (dark): " + e.getMessage(), e);
        }

        return config;
    }

    /**
     * Get the theme configuration as a JSON string.
     * Also updates the cached theme state to ensure accurate subsequent change detection.
     *
     * @return the theme configuration as a JSON string
     */
    private String getIdeThemeConfigJsonInternal() {
        JsonObject config = getIdeThemeConfigInternal();

        // Update cache to ensure accurate subsequent change detection
        // After initial load, only actual changes will trigger notifications
        lastKnownIsDark = config.get("isDark").getAsBoolean();

        return GsonHolder.GSON.toJson(config);
    }

    /**
     * Get the Swing background color corresponding to the current IDE theme.
     * A unified method for obtaining background color, ensuring frontend/backend color consistency.
     *
     * @return the background color for the current theme (Dark: #1e1e1e, Light: #ffffff)
     */
    private Color getBackgroundColorInternal() {
        try {
            boolean isDark = getIdeThemeConfigInternal().get("isDark").getAsBoolean();
            return isDark ? DARK_BG_COLOR : LIGHT_BG_COLOR;
        } catch (Exception e) {
            LOG.warn("Failed to get theme background color, using dark as fallback: " + e.getMessage());
            return DARK_BG_COLOR;
        }
    }

    /**
     * Get the hex color value corresponding to the current IDE theme.
     * Used for injection into HTML.
     *
     * @return the background color hex value for the current theme
     */
    private String getBackgroundColorHexInternal() {
        try {
            boolean isDark = getIdeThemeConfigInternal().get("isDark").getAsBoolean();
            return isDark ? DARK_BG_HEX : LIGHT_BG_HEX;
        } catch (Exception e) {
            LOG.warn("Failed to get theme background color hex, using dark as fallback: " + e.getMessage());
            return DARK_BG_HEX;
        }
    }

    @Override
    public void dispose() {
        // The MessageBusConnection (parented to `this`) is released by the platform.
        // Drop the callback reference so it (and any Project it captures) can be GC'd.
        themeChangeCallback = null;
        LOG.info("[ThemeConfig] ThemeConfigService disposed; MessageBusConnection released by platform");
    }
}
