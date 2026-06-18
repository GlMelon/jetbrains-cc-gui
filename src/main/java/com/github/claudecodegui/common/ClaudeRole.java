package com.github.claudecodegui.common;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Claude 角色模型富枚举:统一管理角色 ID、短名、家族、上下文窗口与关联环境变量键。
 * <p>
 * 消除散布在 {@code ModelRegistryConfig} / {@code ModelProviderHandler} /
 * {@code ClaudeCliModelResolver} / {@code ClaudeCliSession} 中重复的
 * {@code "claude-role-*"} 硬编码 switch 与 {@code shortName→envKey} 映射表。
 * <p>
 * <b>职责边界</b>:本枚举仅封装「写入」(envKeys → applyModelEnv)与「关联数据」。
 * 「读取」(从 {@code settings.json} 的 env JsonObject 取值)保留在各调用方,避免将
 * gson 依赖引入 common 层。各 envKeys 列表已含 fallback 顺序,调用方按序取首个非空值即可。
 *
 * @see ModelFamily
 * @see CommonConstants#ENV_ANTHROPIC_DEFAULT_OPUS_MODEL 等环境变量键
 */
public enum ClaudeRole {

    SONNET(
            "claude-role-sonnet",
            "sonnet",
            ModelFamily.SONNET,
            200_000,
            true,
            List.of(CommonConstants.ENV_ANTHROPIC_DEFAULT_SONNET_MODEL),
            List.of(CommonConstants.ENV_ANTHROPIC_DEFAULT_SONNET_MODEL_CAPABILITIES)
    ),
    OPUS(
            "claude-role-opus",
            "opus",
            ModelFamily.OPUS,
            200_000,
            true,
            List.of(CommonConstants.ENV_ANTHROPIC_DEFAULT_OPUS_MODEL),
            List.of(CommonConstants.ENV_ANTHROPIC_DEFAULT_OPUS_MODEL_CAPABILITIES)
    ),
    FABLE(
            "claude-role-fable",
            "fable",
            ModelFamily.FABLE,
            200_000,
            true,
            // Fable 回退到 Opus 通道(Fable 与 Opus 共享底层模型)
            List.of(CommonConstants.ENV_ANTHROPIC_DEFAULT_FABLE_MODEL,
                    CommonConstants.ENV_ANTHROPIC_DEFAULT_OPUS_MODEL),
            List.of(CommonConstants.ENV_ANTHROPIC_DEFAULT_FABLE_MODEL_CAPABILITIES,
                    CommonConstants.ENV_ANTHROPIC_DEFAULT_OPUS_MODEL_CAPABILITIES)
    ),
    HAIKU(
            "claude-role-haiku",
            "haiku",
            ModelFamily.HAIKU,
            200_000,
            false,
            // Haiku 走 SMALL_FAST_MODEL 通道,回退到 DEFAULT_HAIKU_MODEL
            List.of(CommonConstants.ENV_ANTHROPIC_SMALL_FAST_MODEL,
                    CommonConstants.ENV_ANTHROPIC_DEFAULT_HAIKU_MODEL),
            List.of(CommonConstants.ENV_ANTHROPIC_SMALL_FAST_MODEL_CAPABILITIES,
                    CommonConstants.ENV_ANTHROPIC_DEFAULT_HAIKU_MODEL_CAPABILITIES)
    );

    /** 角色 ID 前缀,所有 {@code claude-role-*} 模型的统一前缀。 */
    public static final String ROLE_PREFIX = "claude-role-";

    /** 通用容量后缀(如 {@code [1m]}/{@code [200k]}),用于反查时剥离。 */
    private static final Pattern CAPACITY_SUFFIX = Pattern.compile("(?i)\\s*\\[[0-9.]+[kKmM]\\]\\s*$");

    private final String roleId;
    private final String shortName;
    private final ModelFamily family;
    private final int contextWindow;
    private final boolean supports1MContext;
    private final List<String> envKeys;
    private final List<String> capsEnvKeys;

    ClaudeRole(String roleId, String shortName, ModelFamily family, int contextWindow,
               boolean supports1MContext, List<String> envKeys, List<String> capsEnvKeys) {
        this.roleId = roleId;
        this.shortName = shortName;
        this.family = family;
        this.contextWindow = contextWindow;
        this.supports1MContext = supports1MContext;
        this.envKeys = envKeys;
        this.capsEnvKeys = capsEnvKeys;
    }

    /** 角色 ID,如 {@code claude-role-sonnet}。 */
    public String roleId() {
        return roleId;
    }

    /** 短名,如 {@code sonnet}。 */
    public String shortName() {
        return shortName;
    }

    /** 所属模型家族。 */
    public ModelFamily family() {
        return family;
    }

    /** 该角色的默认上下文窗口(token 数)。 */
    public int contextWindow() {
        return contextWindow;
    }

    /** 该角色是否支持 1M 上下文窗口。 */
    public boolean supports1MContext() {
        return supports1MContext;
    }

    /**
     * 该角色对应的模型覆盖环境变量键列表(已含 fallback 顺序)。
     * <p>
     * 例如 Fable 为 {@code [DEFAULT_FABLE_MODEL, DEFAULT_OPUS_MODEL]},调用方按序取首个非空值。
     */
    public List<String> envKeys() {
        return envKeys;
    }

    /**
     * 该角色对应的能力覆盖环境变量键列表(已含 fallback 顺序,与 {@link #envKeys()} 对齐)。
     */
    public List<String> capsEnvKeys() {
        return capsEnvKeys;
    }

    /**
     * 从模型 ID 反查角色。剥离容量后缀后按角色 ID 匹配,大小写不敏感。
     *
     * @param modelId 模型 ID(可带 {@code [1m]}/{@code [200k]} 等容量后缀)
     * @return 对应角色;非 {@code claude-role-*} 模型返回 {@code null}
     */
    public static ClaudeRole fromModelId(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return null;
        }
        String normalized = CAPACITY_SUFFIX.matcher(modelId.trim()).replaceFirst("").toLowerCase(Locale.ROOT);
        for (ClaudeRole role : values()) {
            if (role.roleId.equals(normalized)) {
                return role;
            }
        }
        return null;
    }

    /**
     * 从短名反查角色,大小写不敏感。
     *
     * @param shortName 短名,如 {@code sonnet}
     * @return 对应角色;未知短名或空值返回 {@code null}
     */
    public static ClaudeRole fromShortName(String shortName) {
        if (shortName == null || shortName.isBlank()) {
            return null;
        }
        String normalized = shortName.trim().toLowerCase(Locale.ROOT);
        for (ClaudeRole role : values()) {
            if (role.shortName.equals(normalized)) {
                return role;
            }
        }
        return null;
    }

    /**
     * 将解析出的真实模型写入该角色的全部模型覆盖环境变量。
     * <p>
     * 用于 {@code ClaudeCliSession.configureRequestModelEnvironment}:当用户选择某角色时,
     * 把解析后的实际模型名同时写入主通道与 fallback 通道,确保 CLI/SDK 在所有读取路径下一致。
     *
     * @param env           待写入的环境变量 Map(为 null 时安全跳过)
     * @param resolvedModel 解析后的真实模型名(为 null 时安全跳过)
     */
    public void applyModelEnv(Map<String, String> env, String resolvedModel) {
        if (env == null || resolvedModel == null) {
            return;
        }
        for (String key : envKeys) {
            env.put(key, resolvedModel);
        }
    }
}
