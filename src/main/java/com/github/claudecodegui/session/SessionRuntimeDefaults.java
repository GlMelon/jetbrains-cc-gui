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
        String model = resolveProviderModel(project, provider, registry);
        if (model == null && CommonConstants.PROVIDER_OPENCODE.equals(provider)) {
            // Let opencode use its own configured default instead of inheriting Claude's role model.
            model = "";
        }
        if (model != null) {
            session.setModel(model);
        }
    }

    /**
     * 解析 provider 的会话默认模型:粘性记录优先,但须通过 registry 归属校验——
     * 历史 provider 切换路径曾把旧 provider 的模型记到新 provider 名下(见
     * ModelProviderHandler#alignSessionModelToProvider),重启后以 provider/model
     * 错配对回灌前端,输入区显示新 provider 而欢迎页 logo 按 modelId 误判显示旧
     * 供应商图标。校验不过或无记录时回退 registry 中该 provider 首个启用模型。
     */
    public static String resolveProviderModel(Project project, String provider, ModelRegistryConfig registry) {
        String normalizedProvider = normalizeProvider(provider);
        if (normalizedProvider == null) {
            return null;
        }
        String model = readModel(project, normalizedProvider);
        if (isForeignModel(model, normalizedProvider, registry)) {
            model = null;
        }
        if (model == null && registry != null) {
            model = firstEnabledModelForProvider(registry, normalizedProvider);
        }
        return model;
    }

    /**
     * 判断粘性模型是否不属于该 provider(用于丢弃历史污染)。registry 为空时返回 false。
     * registry 收录该 provider 时以 find(provider, model) 为准(容忍容量后缀)。
     *
     * <p>CLI-only provider(grok/kimi/pi/omp/dsh)无 registry 条目,无法正向验证归属,
     * 但粘性模型若命中 registry 中【其他】provider 的条目(如 kimi 键里存了 claude-*,
     * 由历史切换路径的错配对固化而来),仍判为外来丢弃——这是存量污染唯一的自愈通道;
     * 真 CLI 模型不在任何 registry,保持原值不受影响。
     */
    private static boolean isForeignModel(String model, String provider, ModelRegistryConfig registry) {
        if (registry == null || model == null) {
            return false;
        }
        if (registry.find(provider, model).isPresent()) {
            return false;
        }
        boolean registryCoversProvider = registry.models().stream()
                .anyMatch(candidate -> provider.equals(candidate.provider()) && candidate.enabled());
        if (registryCoversProvider) {
            return true;
        }
        return registry.find(model).isPresent();
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
