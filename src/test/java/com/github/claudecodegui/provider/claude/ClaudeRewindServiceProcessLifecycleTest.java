package com.github.claudecodegui.provider.claude;

import com.github.claudecodegui.cli.common.CliConstants;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Guards the official Claude CLI file-checkpoint restoration command contract.
 */
public class ClaudeRewindServiceProcessLifecycleTest {

    @Test
    public void buildCommand_usesStandaloneClaudeCliRewindArguments() {
        List<String> command = ClaudeRewindQueryService.buildCommand(
                "claude",
                "11111111-1111-4111-8111-111111111111",
                "22222222-2222-4222-8222-222222222222"
        );

        assertEquals(List.of(
                "claude",
                CliConstants.ARG_P,
                CliConstants.ARG_RESUME,
                "11111111-1111-4111-8111-111111111111",
                CliConstants.ARG_REWIND_FILES,
                "22222222-2222-4222-8222-222222222222"
        ), command);
    }
}
