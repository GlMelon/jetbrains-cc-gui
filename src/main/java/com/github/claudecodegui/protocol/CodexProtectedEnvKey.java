package com.github.claudecodegui.protocol;

import java.util.Arrays;
import java.util.Optional;

/**
 * Codex 受保护环境变量名枚举(SSOT,A5)。
 *
 * <p>Codex 子进程运行时受保护的环境变量名(基础集 18 个)的唯一权威定义。这些变量由
 * Codex 运行时注入,用户配置的自定义 env 不得覆盖。前端经 {@code generate-protocol-types.mjs}
 * 构建时生成 {@code CODEX_PROTECTED_ENV_KEY} 常量(用于 UI 校验
 * {@code isProtectedEnvVarKey}),消除前端 {@code types/provider.ts#CODEX_PROTECTED_ENV_KEYS}
 * 与后端 {@code cli/codex/CodexCliCommandUtils#PROTECTED_ENV_KEYS} 三份手抄的第二真相源。
 *
 * <p>值域对齐:前端 {@code CODEX_PROTECTED_ENV_KEYS}(18 个)与后端
 * {@code CodexCliCommandUtils#PROTECTED_ENV_KEYS}(18 个,此前逐字相同)。
 *
 * <p>另有 14 个 code-injection / library-injection 防护变量
 * (NODE_OPTIONS/LD_PRELOAD/PYTHONPATH/ELECTRON_RUN_AS_NODE 等)不在本枚举,由各
 * spawn 用户自定义 env 的子进程服务(CodexMcpService 等)以硬编码 Set 持有——它们
 * 与运行时无关、与 SDK 无关,任何接受用户 env 注入的子进程都须拦截,不随 SDK 移除。
 *
 * <p>⚠️ 修改此文件后需运行 {@code gradle generateProtocol} 更新前端类型。
 */
public enum CodexProtectedEnvKey implements ProtocolValue {

    CODEX_USE_STDIN("CODEX_USE_STDIN"),
    CODEX_MODEL("CODEX_MODEL"),
    CODEX_SANDBOX_MODE("CODEX_SANDBOX_MODE"),
    CODEX_SANDBOX("CODEX_SANDBOX"),
    CODEX_APPROVAL_POLICY("CODEX_APPROVAL_POLICY"),
    CODEX_CI("CODEX_CI"),
    CODEX_SANDBOX_NETWORK_DISABLED("CODEX_SANDBOX_NETWORK_DISABLED"),
    CODEX_HOME("CODEX_HOME"),
    CLAUDE_SESSION_ID("CLAUDE_SESSION_ID"),
    CLAUDE_PERMISSION_DIR("CLAUDE_PERMISSION_DIR"),
    HOME("HOME"),
    PATH("PATH"),
    TMPDIR("TMPDIR"),
    TEMP("TEMP"),
    TMP("TMP"),
    IDEA_PROJECT_PATH("IDEA_PROJECT_PATH"),
    PROJECT_PATH("PROJECT_PATH"),
    CLAUDE_USE_STDIN("CLAUDE_USE_STDIN");

    private final String value;

    CodexProtectedEnvKey(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static Optional<CodexProtectedEnvKey> fromValue(String value) {
        return Arrays.stream(values()).filter(key -> key.value.equals(value)).findFirst();
    }
}
