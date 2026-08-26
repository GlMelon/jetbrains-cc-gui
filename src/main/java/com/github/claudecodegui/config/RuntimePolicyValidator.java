package com.github.claudecodegui.config;

import com.github.claudecodegui.session.runtime.ProviderType;

import java.util.ArrayList;
import java.util.List;

/**
 * 路由策略配置校验器。
 * <p>
 * 错误配置不让上去，防影响插件运行。
 * 双重校验：写入时 + 启动加载时。
 */
public final class RuntimePolicyValidator {

    private RuntimePolicyValidator() {}

    /**
     * 校验结果。
     *
     * @param errors   错误列表（非空则拒绝持久化）
     * @param warnings 警告列表（可选提示）
     */
    public record ValidationResult(List<String> errors, List<String> warnings) {
        public boolean isValid() {
            return errors == null || errors.isEmpty();
        }
    }

    /**
     * 校验路由策略配置。
     *
     * @param cfg 待校验的配置
     * @return 校验结果
     */
    public static ValidationResult validate(RuntimePolicyConfig cfg) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (cfg == null) {
            errors.add("配置不能为空");
            return new ValidationResult(errors, warnings);
        }

        if (cfg.providers() == null || cfg.providers().isEmpty()) {
            errors.add("至少需要配置一个 provider");
            return new ValidationResult(errors, warnings);
        }

        // 至少需要启用一个 provider
        boolean anyEnabled = cfg.providers().values().stream()
                .anyMatch(ProviderRuntimePolicy::enabled);
        if (!anyEnabled) {
            errors.add("至少需要启用一个 provider");
        }

        // 不能删除核心 provider: claude
        if (!cfg.providers().containsKey(ProviderType.CLAUDE)) {
            errors.add("不能删除核心 provider: claude");
        }

        // 每个 provider 的策略校验
        cfg.providers().forEach((provider, policy) -> {
            if (policy == null) {
                errors.add(provider + " 的策略配置不能为空");
            }
        });

        return new ValidationResult(errors, warnings);
    }
}
