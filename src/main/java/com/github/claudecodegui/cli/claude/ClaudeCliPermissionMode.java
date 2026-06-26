package com.github.claudecodegui.cli.claude;

import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.common.CommonConstants;

import java.util.List;
import java.util.Set;

/**
 * Applies the user-selected permission mode to Claude CLI commands.
 */
final class ClaudeCliPermissionMode {

    private static final Set<String> VALID_MODES = CliConstants.VALID_PERMISSION_MODES;

    private ClaudeCliPermissionMode() {
    }

    static void apply(List<String> command, String permissionMode) {
        if (CommonConstants.PERMISSION_MODE_BYPASS.equals(permissionMode)) {
            command.add(CliConstants.ARG_DANGEROUS_SKIP);
            return;
        }

        String mode = (permissionMode != null && !permissionMode.isBlank() && VALID_MODES.contains(permissionMode))
                ? permissionMode : CommonConstants.PERMISSION_MODE_ACCEPT_EDITS;

        command.add(CliConstants.ARG_PERMISSION_MODE);
        command.add(mode);
    }
}
