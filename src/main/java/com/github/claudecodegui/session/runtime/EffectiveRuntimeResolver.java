package com.github.claudecodegui.session.runtime;

import com.github.claudecodegui.config.ProviderRuntimePolicy;
import com.github.claudecodegui.config.RuntimePolicyConfig;

/**
 * Resolves the effective runtime exclusively from the current backend runtime policy.
 * Chat payloads and session state never override this decision.
 */
public final class EffectiveRuntimeResolver {

    private EffectiveRuntimeResolver() {}

    public record Runtime(ProviderType provider, RuntimeType runtimeType) {}

    public static Runtime resolve(String provider, RuntimePolicyConfig policy) {
        ProviderType providerType = ProviderType.fromString(provider);
        RuntimePolicyConfig effectivePolicy = policy != null
                ? policy.mergeWithDefaults()
                : RuntimePolicyConfig.getDefault();
        ProviderRuntimePolicy providerPolicy = effectivePolicy.of(providerType);
        if (providerPolicy == null || !providerPolicy.enabled()) {
            throw new IllegalStateException("Provider disabled/unknown: " + providerType.value());
        }
        RuntimeType runtimeType = providerPolicy.defaultRuntime();
        if (runtimeType == null || !providerPolicy.supported().contains(runtimeType)) {
            throw new IllegalStateException("Provider has no valid default runtime: " + providerType.value());
        }
        return new Runtime(providerType, runtimeType);
    }

    public static boolean isCliMode(String provider, RuntimePolicyConfig policy) {
        return resolve(provider, policy).runtimeType() == RuntimeType.CLI;
    }
}
