package com.github.claudecodegui.cli.codex;

import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.cli.compatibility.CodexCliVersionParser;
import com.github.claudecodegui.cli.compatibility.VersionComparator;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.protocol.CodexProtectedEnvKey;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.github.claudecodegui.util.PlatformUtils;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Codex CLI 命令构建相关工具方法（独立于 SDK 和旧 adapter）。
 */
public final class CodexCliCommandUtils {

    // A5:基础保护变量 SSOT —— CodexProtectedEnvKey 枚举(与前端经生成链同源,消除手抄)
    private static final Set<String> PROTECTED_ENV_KEYS = Arrays.stream(CodexProtectedEnvKey.values())
            .map(CodexProtectedEnvKey::value)
            .collect(Collectors.toUnmodifiableSet());

    private CodexCliCommandUtils() {}

    static PermissionSelection selectPermission(String permissionMode, String configuredSandbox) {
        String sandbox = normalizeSandbox(configuredSandbox);
        return switch (permissionMode == null ? "" : permissionMode) {
            // Native auto review owns its guarded contract: workspace-write +
            // on-request + the CLI's auto_review reviewer. The configured sandbox
            // (and the Windows danger-full-access fallback) is intentionally NOT
            // applied here — the reviewer handles approval decisions inside the
            // project sandbox (aligned with ai-bridge CodexPermissionMapper).
            case CommonConstants.PERMISSION_MODE_AUTO -> new PermissionSelection(
                    CliConstants.CODEX_ARG_APPROVAL_ON_REQUEST,
                    CliConstants.SANDBOX_WORKSPACE_WRITE,
                    CliConstants.CODEX_APPROVALS_REVIEWER_AUTO_REVIEW);
            case CommonConstants.PERMISSION_MODE_BYPASS -> new PermissionSelection(
                    CliConstants.CODEX_ARG_NEVER,
                    CliConstants.SANDBOX_DANGER_FULL_ACCESS,
                    CliConstants.CODEX_APPROVALS_REVIEWER_USER);
            case CommonConstants.PERMISSION_MODE_PLAN   -> new PermissionSelection(
                    CliConstants.CODEX_ARG_APPROVAL_ON_REQUEST,
                    CliConstants.SANDBOX_READ_ONLY,
                    CliConstants.CODEX_APPROVALS_REVIEWER_USER);
            case CommonConstants.PERMISSION_MODE_ACCEPT_EDITS, CommonConstants.PERMISSION_MODE_AUTO_EDIT -> new PermissionSelection(
                    CliConstants.CODEX_ARG_APPROVAL_ON_REQUEST,
                    sandbox,
                    CliConstants.CODEX_APPROVALS_REVIEWER_USER);
            default -> new PermissionSelection(
                    CliConstants.CODEX_ARG_APPROVAL_ON_REQUEST,
                    sandbox,
                    CliConstants.CODEX_APPROVALS_REVIEWER_USER);
        };
    }

    static String normalizeSandbox(String sandbox) {
        if (CliConstants.VALID_SANDBOX_MODES.contains(sandbox)) {
            return sandbox;
        }
        return PlatformUtils.isWindows() ? CliConstants.SANDBOX_DANGER_FULL_ACCESS : CliConstants.SANDBOX_WORKSPACE_WRITE;
    }

    public static void addCodexExecutable(List<String> command, String executable) {
        String resolved = executable != null && !executable.isBlank() ? executable : ProviderType.CODEX.cliCommand();
        String lower = resolved.toLowerCase(Locale.ROOT);
        if (PlatformUtils.isWindows() && CliConstants.WINDOWS_SCRIPT_SUFFIXES.stream().anyMatch(lower::endsWith)) {
            command.add("cmd");
            command.add("/c");
            command.add(resolved);
            return;
        }
        command.add(resolved);
    }

    static void addCodexGlobalOptions(List<String> command, PermissionSelection permission) {
        command.add(CliConstants.CODEX_ARG_ASK_APPROVAL);
        command.add(permission.approval());
    }

    /**
     * 显式选择 approvals reviewer:native auto review 用 CLI 内置的 auto_review;
     * 其余模式 pin user,防止 resume 的线程继承历史配置里的 auto_review
     * (对齐 ai-bridge applyCodexApprovalsReviewerConfig)。`exec` 与 `exec resume`
     * 均支持 `-c`。
     */
    static void addApprovalsReviewerOverride(List<String> command, PermissionSelection permission) {
        command.add(CliConstants.CODEX_ARG_C_CONFIG);
        command.add(codexConfigOverride(CliConstants.CODEX_CONFIG_APPROVALS_REVIEWER, permission.approvalsReviewer()));
    }

    /**
     * native auto review 的版本门控(纯函数,对齐 ai-bridge isCodexNativeAutoReviewSupported):
     * `codex --version` 输出(如 "codex-cli 0.146.0")不可解析或低于
     * {@link CliConstants#CODEX_NATIVE_AUTO_REVIEW_MIN_VERSION} 时不支持。
     */
    static boolean isNativeAutoReviewSupported(String rawVersion) {
        return new CodexCliVersionParser().parse(rawVersion)
                .map(version -> VersionComparator.compareVersions(
                        version, CliConstants.CODEX_NATIVE_AUTO_REVIEW_MIN_VERSION) >= 0)
                .orElse(false);
    }

    static String codexConfigOverride(String key, String value) {
        return key + "=\"" + value + "\"";
    }

    static Map<String, String> sanitizeEnv(Map<String, String> env) {
        Map<String, String> result = new LinkedHashMap<>();
        if (env == null) {
            return result;
        }
        for (Map.Entry<String, String> entry : env.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.trim().isEmpty()) {
                continue;
            }
            if (PROTECTED_ENV_KEYS.contains(key.toUpperCase(Locale.ROOT))) {
                continue;
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    record PermissionSelection(String approval, String sandbox, String approvalsReviewer) {}
}
