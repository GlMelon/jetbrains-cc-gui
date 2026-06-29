package com.github.claudecodegui.session;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.config.ModelConfig;
import com.github.claudecodegui.config.ModelRegistryConfig;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;

/**
 * Project-scoped defaults for fresh chat tabs/sessions.
 *
 * <p>These values are not per-tab history state. They represent the user's most
 * recent runtime intent so a newly created tab starts with the same provider and
 * that provider's last selected model.
 */
public final class SessionRuntimeDefaults {

    private static final String PROVIDER_KEY = "claudecodegui.session.default.provider";
    private static final String MODEL_KEY_PREFIX = "claudecodegui.session.default.model.";

    private SessionRuntimeDefaults() {
    }

    public static void rememberProvider(Project project, String provider) {
        if (project == null || project.isDisposed()) {
            return;
        }
        String normalized = normalizeProvider(provider);
        if (normalized == null) {
            return;
        }
        PropertiesComponent.getInstance(project).setValue(PROVIDER_KEY, normalized);
    }

    public static void rememberModel(Project project, String provider, String model) {
        if (project == null || project.isDisposed()) {
            return;
        }
        String normalizedProvider = normalizeProvider(provider);
        String normalizedModel = normalizeModel(model);
        if (normalizedProvider == null || normalizedModel == null) {
            return;
        }
        PropertiesComponent.getInstance(project).setValue(modelKey(normalizedProvider), normalizedModel);
    }

    public static String readProvider(Project project) {
        if (project == null || project.isDisposed()) {
            return CommonConstants.DEFAULT_PROVIDER;
        }
        String provider = normalizeProvider(PropertiesComponent.getInstance(project).getValue(PROVIDER_KEY));
        return provider != null ? provider : CommonConstants.DEFAULT_PROVIDER;
    }

    public static String readModel(Project project, String provider) {
        if (project == null || project.isDisposed()) {
            return null;
        }
        String normalizedProvider = normalizeProvider(provider);
        if (normalizedProvider == null) {
            return null;
        }
        return normalizeModel(PropertiesComponent.getInstance(project).getValue(modelKey(normalizedProvider)));
    }

    public static void applyToSession(Project project, ClaudeSession session) {
        applyToSession(project, session, null);
    }

    public static void applyToSession(Project project, ClaudeSession session, ModelRegistryConfig registry) {
        if (session == null) {
            return;
        }
        String provider = readProvider(project);
        session.setProvider(provider);
        String model = readModel(project, provider);
        if (model == null && registry != null) {
            model = firstEnabledModelForProvider(registry, provider);
        }
        if (model == null && CommonConstants.PROVIDER_OPENCODE.equals(provider)) {
            // Let opencode use its own configured default instead of inheriting Claude's role model.
            model = "";
        }
        if (model != null) {
            session.setModel(model);
        }
    }

    private static String firstEnabledModelForProvider(ModelRegistryConfig registry, String provider) {
        return registry.models()
                .stream()
                .filter(ModelConfig::enabled)
                .filter(model -> provider.equals(model.provider()))
                .map(ModelConfig::id)
                .filter(model -> model != null && !model.trim().isEmpty())
                .findFirst()
                .orElse(null);
    }

    private static String modelKey(String provider) {
        return MODEL_KEY_PREFIX + provider;
    }

    private static String normalizeProvider(String provider) {
        if (provider == null) {
            return null;
        }
        String trimmed = provider.trim();
        return SessionState.VALID_PROVIDERS.contains(trimmed) ? trimmed : null;
    }

    private static String normalizeModel(String model) {
        if (model == null) {
            return null;
        }
        String trimmed = model.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
