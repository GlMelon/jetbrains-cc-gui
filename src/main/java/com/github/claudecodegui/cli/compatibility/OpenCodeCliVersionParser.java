package com.github.claudecodegui.cli.compatibility;

import com.github.claudecodegui.session.runtime.ProviderType;

import java.util.regex.Pattern;

/** Parses OpenCode CLI version output, including labelled and prerelease forms. */
public final class OpenCodeCliVersionParser extends AbstractCliVersionParser {

    private static final Pattern PATTERN = Pattern.compile(
            "(?i)" + Pattern.quote(ProviderType.OPENCODE.value())
                    + "[^0-9]*v?" + VERSION_CAPTURE);

    public OpenCodeCliVersionParser() {
        super(PATTERN);
    }

    @Override
    public ProviderType provider() {
        return ProviderType.OPENCODE;
    }
}
