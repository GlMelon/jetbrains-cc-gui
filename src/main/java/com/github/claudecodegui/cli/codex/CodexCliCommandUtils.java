package com.github.claudecodegui.cli.codex;

import com.github.claudecodegui.cli.common.CliConstants;
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
            case CommonConstants.PERMISSION_MODE_BYPASS -> new PermissionSelection(CliConstants.CODEX_ARG_NEVER, CliConstants.SANDBOX_DANGER_FULL_ACCESS);
            case CommonConstants.PERMISSION_MODE_PLAN   -> new PermissionSelection(CliConstants.CODEX_ARG_APPROVAL_ON_REQUEST, CliConstants.SANDBOX_READ_ONLY);
            case CommonConstants.PERMISSION_MODE_ACCEPT_EDITS, CommonConstants.PERMISSION_MODE_AUTO_EDIT -> new PermissionSelection(CliConstants.CODEX_ARG_APPROVAL_ON_REQUEST, sandbox);
            default -> new PermissionSelection(CliConstants.CODEX_ARG_APPROVAL_ON_REQUEST, sandbox);
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

    record PermissionSelection(String approval, String sandbox) {}
}
