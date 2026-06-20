package com.github.claudecodegui.config;

import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.cli.common.CliSettings;
import com.github.claudecodegui.common.ClaudeRole;
import com.github.claudecodegui.common.CommonConstants;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 运行时计算的只读默认模型:从 CLI 配置文件读取真实模型,作为不可编辑/删除/停用的保留项
 * 叠加到 registry 用户层之上。
 *
 * <p>Claude 4 role 从 {@code ~/.claude/settings.json} 的 env 块解析 actualModel
 * (经 {@link CliSettings#readClaudeCliEnvironment()});Codex 默认从
 * {@code ~/.codex/config.toml} 的 {@code model=} 读取(经
 * {@link CliSettings#readCodexCliEnvironment()} → {@link CliConstants#ENV_CODEX_MODEL})。
 * 只读默认不进持久化:磁盘配置改动后下次 {@code getModelRegistry()} 即生效。
 */
public final class ReadOnlyDefaultModels {
    private ReadOnlyDefaultModels() {
    }

    /** 现算只读默认:从 CLI 配置文件读取(无配置则 role actualModel 为空、无 Codex)。 */
    public static List<ModelConfig> compute() {
        return compute(CliSettings.readClaudeCliEnvironment(), CliSettings.readCodexCliEnvironment());
    }

    /**
     * 注入式计算(便于单测):claudeEnv / codexEnv 由调用方提供。
     */
    public static List<ModelConfig> compute(Map<String, String> claudeEnv, Map<String, String> codexEnv) {
        List<ModelConfig> defaults = new ArrayList<>();
        for (ClaudeRole role : ClaudeRole.values()) {
            defaults.add(roleDefault(role, resolveFirstNonBlank(role.envKeys(), claudeEnv)));
        }
        String codexModel = codexEnv.get(CliConstants.ENV_CODEX_MODEL);
        if (codexModel != null && !codexModel.isBlank()) {
            defaults.add(codexDefault(codexModel.trim()));
        }
        return defaults;
    }

    /**
     * 将只读默认叠加到用户层(读真实配置文件)。
     * <ul>
     *   <li>Claude role 键({@code claude-role-*}):保留键,只读恒胜,用户层同键项被跳过。</li>
     *   <li>Codex / 其他键:用户优先(替换同键只读项),否则只读填补空缺。</li>
     * </ul>
     */
    public static ModelRegistryConfig mergeWithReadOnlyDefaults(ModelRegistryConfig userLayer) {
        return mergeWithReadOnlyDefaults(userLayer, compute());
    }

    /** 注入式合并(便于单测):只读默认列表由调用方提供。 */
    public static ModelRegistryConfig mergeWithReadOnlyDefaults(ModelRegistryConfig userLayer,
                                                                List<ModelConfig> readOnly) {
        List<ModelConfig> result = new ArrayList<>(readOnly);
        Set<String> readOnlyKeys = new HashSet<>();
        for (ModelConfig ro : readOnly) {
            readOnlyKeys.add(dedupKey(ro.provider(), ro.id()));
        }
        for (ModelConfig user : userLayer.models()) {
            boolean isReservedRole = CommonConstants.PROVIDER_CLAUDE.equals(user.provider())
                    && ClaudeRole.fromModelId(user.id()) != null;
            if (isReservedRole) {
                continue; // role 保留键:只读恒胜,跳过用户层(去重覆盖,不删磁盘)
            }
            String key = dedupKey(user.provider(), user.id());
            if (readOnlyKeys.contains(key)) {
                result.removeIf(m -> dedupKey(m.provider(), m.id()).equals(key)); // codex 用户优先
            }
            result.add(user);
        }
        return new ModelRegistryConfig(result);
    }

    /** 去重键:provider 小写 + ":" + id 剥容量后缀后小写(与 find/resolveModelSelection 语义一致)。 */
    public static String dedupKey(String provider, String id) {
        String normalizedProvider = provider == null ? "" : provider.toLowerCase(Locale.ROOT);
        String baseId = ModelRegistryConfig.stripCapacitySuffix(id).toLowerCase(Locale.ROOT);
        return normalizedProvider + ":" + baseId;
    }

    private static ModelConfig roleDefault(ClaudeRole role, String actualModel) {
        return new ModelConfig(
                role.roleId(),
                CommonConstants.PROVIDER_CLAUDE,
                role.shortName(),
                capitalize(role.shortName()),
                actualModel,
                capitalize(role.shortName()) + " role · 来自 ~/.claude/settings.json",
                role.contextWindow(),
                role.supports1MContext(),
                true,   // enabled
                true    // readOnly
        );
    }

    private static ModelConfig codexDefault(String codexModel) {
        return new ModelConfig(
                codexModel,
                CommonConstants.PROVIDER_CODEX,
                "",
                codexModel,
                "",
                "只读 · 来自 ~/.codex/config.toml",
                CommonConstants.DEFAULT_CONTEXT_WINDOW,
                false,
                true,   // enabled
                true    // readOnly
        );
    }

    private static String resolveFirstNonBlank(List<String> keys, Map<String, String> env) {
        for (String key : keys) {
            String value = env.get(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }
}
