package com.github.claudecodegui.common;

/**
 * Claude 模型家族枚举。
 * <p>
 * 用于在不需要具体角色(如 capabilities fallback、family 级分派)的场景下表达模型归属。
 * {@link #OTHER} 表示非 {@code claude-role-*} 角色的模型(可能是 canonical claude 模型名,如
 * {@code claude-sonnet-4-6},也可能是第三方模型)。
 * <p>
 * 角色到家族的映射由 {@link ClaudeRole#family()} 提供;从模型 ID 反查时,非角色模型统一归为 {@link #OTHER}。
 */
public enum ModelFamily {
    OPUS,
    FABLE,
    HAIKU,
    SONNET,
    /** 非 claude-role-* 角色模型(可能是 canonical claude 或第三方)。 */
    OTHER;

    /**
     * 从模型 ID 推断家族。{@code claude-role-*} 角色模型映射到对应家族,其余归为 {@link #OTHER}。
     *
     * @param modelId 模型 ID(可带 [1m]/[200k] 等容量后缀,大小写不敏感)
     * @return 对应家族,永不返回 null(非角色返回 OTHER)
     */
    public static ModelFamily fromModelId(String modelId) {
        ClaudeRole role = ClaudeRole.fromModelId(modelId);
        return role != null ? role.family() : OTHER;
    }
}
