package com.github.claudecodegui.cli.compatibility;

import com.github.claudecodegui.session.runtime.ProviderType;

import java.util.regex.Pattern;

/** Parses Claude Code CLI version output, including labelled and prefixed forms. */
public final class ClaudeCliVersionParser extends AbstractCliVersionParser {

    private static final Pattern PATTERN = Pattern.compile(
            "(?i)" + Pattern.quote(ProviderType.CLAUDE.value())
                    + "(?:\\s+code)?[^0-9]*v?" + VERSION_CAPTURE);

    public ClaudeCliVersionParser() {
        super(PATTERN);
    }

    @Override
    public ProviderType provider() {
        return ProviderType.CLAUDE;
    }
}
