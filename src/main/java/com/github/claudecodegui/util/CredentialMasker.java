package com.github.claudecodegui.util;

/**
 * 凭证掩码工具(安全关键:确保 API key 等敏感值绝不以明文回传前端)。
 * <p>纯静态、无 IntelliJ Platform 依赖,便于单元测试。
 */
public final class CredentialMasker {

    private CredentialMasker() {
    }

    /**
     * 掩码 API key:保留前 2 + 后 4,中间用 {@code ••••} 占位;
     * 长度 ≤ 8 的 key 全 {@code ••••}(避免短 key 被猜出);空/null 返回空串。
     *
     * @param key 原始 key
     * @return 掩码后的展示字符串,绝不包含完整 key
     */
    public static String maskApiKey(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        if (key.length() <= 8) {
            return "••••";
        }
        return key.substring(0, 2) + "••••" + key.substring(key.length() - 4);
    }
}
