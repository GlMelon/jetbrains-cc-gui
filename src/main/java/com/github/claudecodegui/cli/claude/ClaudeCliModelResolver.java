package com.github.claudecodegui.cli.claude;

import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.cli.common.CliSettings;
import com.github.claudecodegui.common.ClaudeRole;
import com.github.claudecodegui.common.CommonConstants;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Claude CLI 模型映射工具（从用户 ~/.claude/settings.json 的 env 中读取自定义映射）。
 */
final class ClaudeCliModelResolver {

    private static final Pattern ONE_M_SUFFIX = Pattern.compile("(?i)\\[1m\\]$");
    private static final String SUFFIX_1M = "[1m]";

    private ClaudeCliModelResolver() {}

    record Capabilities(
            boolean supportsEffort,
            boolean supportsPartialMessages,
            boolean supportsMcp,
            boolean supportsAddDir
    ) {
    }

    record ResolvedModel(String model, Capabilities capabilities) {
    }

    static ResolvedModel resolveProfile(String selectedModel) {
        return resolveProfile(selectedModel, null, toJsonObject(CliSettings.readClaudeCliEnvironment()));
    }

    static ResolvedModel resolveProfile(String selectedModel, JsonObject env) {
        return resolveProfile(selectedModel, null, env);
    }

    static ResolvedModel resolveProfile(String selectedModel, String actualModel) {
        return resolveProfile(selectedModel, actualModel, toJsonObject(CliSettings.readClaudeCliEnvironment()));
    }

    static ResolvedModel resolveProfile(String selectedModel, String actualModel, JsonObject env) {
        String resolvedModel = resolveMapped(selectedModel, actualModel, env);
        return new ResolvedModel(resolvedModel, resolveCapabilities(selectedModel, resolvedModel, env));
    }

    static String resolveMapped(String selectedModel, JsonObject env) {
        return resolveMapped(selectedModel, null, env);
    }

    static String resolveMapped(String selectedModel, String actualModel, JsonObject env) {
        // Check if original model has [1m] suffix (to preserve it after mapping)
        boolean has1mSuffix = ONE_M_SUFFIX.matcher(selectedModel == null ? "" : selectedModel).find();

        String actual = applyRequestSuffix(selectedModel, actualModel);
        if (actual != null) {
            return actual;
        }

        if (selectedModel == null || selectedModel.isBlank() || env == null) {
            return selectedModel;
        }

        String normalized = ONE_M_SUFFIX.matcher(selectedModel).replaceFirst("").toLowerCase();
        if (!normalized.startsWith(ClaudeRole.ROLE_PREFIX)) {
            return selectedModel;
        }

        ClaudeRole role = ClaudeRole.fromModelId(normalized);
        String mapped = null;
        if (role != null) {
            // envKeys 已含 fallback 顺序(Fable→Opus、Haiku→DEFAULT_HAIKU),取首个非空值
            for (String key : role.envKeys()) {
                mapped = readEnvValue(env, key);
                if (mapped != null) {
                    break;
                }
            }
        }

        if (mapped != null) {
            // Preserve [1m] suffix from original model if the mapped model doesn't already have it
            if (has1mSuffix && !ONE_M_SUFFIX.matcher(mapped).find()) {
                return mapped + SUFFIX_1M;
            }
            return mapped;
        }

        String mainModel = readEnvValue(env, CommonConstants.ENV_ANTHROPIC_MODEL);
        if (mainModel != null) {
            String baseMain = ONE_M_SUFFIX.matcher(mainModel).replaceFirst("");
            return has1mSuffix ? baseMain + SUFFIX_1M : baseMain;
        }
        return selectedModel;
    }

    private static String applyRequestSuffix(String selectedModel, String actualModel) {
        if (actualModel == null || actualModel.isBlank()) {
            return null;
        }
        boolean has1mSuffix = ONE_M_SUFFIX.matcher(selectedModel == null ? "" : selectedModel).find();
        String baseActual = ONE_M_SUFFIX.matcher(actualModel.trim()).replaceFirst("");
        return has1mSuffix ? baseActual + SUFFIX_1M : baseActual;
    }

    private static Capabilities resolveCapabilities(String selectedModel, String resolvedModel, JsonObject env) {
        boolean canonicalClaude = isCanonicalClaudeModel(resolvedModel);
        boolean supportsEffort = canonicalClaude;
        boolean supportsPartialMessages = true;
        boolean supportsMcp = true;
        boolean supportsAddDir = true;

        String override = readCapabilityOverride(selectedModel, resolvedModel, env);
        if (override != null) {
            supportsEffort = containsCapability(override, "effort")
                    || containsCapability(override, "reasoning_effort")
                    || containsCapability(override, "thinking");
            if (containsCapability(override, "no-effort")
                    || containsCapability(override, "no_reasoning_effort")
                    || containsCapability(override, "none")) {
                supportsEffort = false;
            }
            if (containsCapability(override, "no-mcp")) {
                supportsMcp = false;
            }
            if (containsCapability(override, "no-add-dir")
                    || containsCapability(override, "no_additional_directories")) {
                supportsAddDir = false;
            }
            if (containsCapability(override, "no-partial-messages")
                    || containsCapability(override, "no-partial")) {
                supportsPartialMessages = false;
            }
        }

        return new Capabilities(
                supportsEffort,
                supportsPartialMessages,
                supportsMcp,
                supportsAddDir
        );
    }

    private static boolean isCanonicalClaudeModel(String model) {
        if (model == null) {
            return false;
        }
        return model.trim().toLowerCase().startsWith("claude-");
    }

    private static String readCapabilityOverride(String selectedModel, String resolvedModel, JsonObject env) {
        String explicit = readEnvValue(env, CommonConstants.ENV_ANTHROPIC_MODEL_CAPABILITIES);
        if (explicit != null) {
            return explicit;
        }

        String normalized = selectedModel != null ? ONE_M_SUFFIX.matcher(selectedModel).replaceFirst("").toLowerCase() : "";
        ClaudeRole role = ClaudeRole.fromModelId(normalized);
        if (role != null) {
            // capsEnvKeys 与 envKeys 的 fallback 顺序对齐,取首个非空值
            for (String key : role.capsEnvKeys()) {
                String value = readEnvValue(env, key);
                if (value != null) {
                    return value;
                }
            }
            return null;
        }
        // 非 claude-role-* 模型(canonical claude 如 claude-sonnet-4-6)回退到 sonnet 能力覆盖
        return isCanonicalClaudeModel(resolvedModel)
                ? readEnvValue(env, CommonConstants.ENV_ANTHROPIC_DEFAULT_SONNET_MODEL_CAPABILITIES)
                : null;
    }

    private static boolean containsCapability(String capabilities, String expected) {
        if (capabilities == null || expected == null) {
            return false;
        }
        String normalizedExpected = normalizeCapabilityToken(expected);
        for (String token : capabilities.split("[,;\\s]+")) {
            if (normalizeCapabilityToken(token).equals(normalizedExpected)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeCapabilityToken(String token) {
        return token == null ? "" : token.trim().toLowerCase().replace('-', '_');
    }

    private static JsonObject toJsonObject(Map<String, String> env) {
        JsonObject json = new JsonObject();
        if (env == null) { return json; }
        env.forEach((k, v) -> {
            if (k != null && v != null) { json.addProperty(k, v); }
        });
        return json;
    }

    private static String readEnvValue(JsonObject env, String key) {
        if (env == null || key == null || !env.has(key) || env.get(key).isJsonNull()) {
            return null;
        }
        String value = env.get(key).getAsString();
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
