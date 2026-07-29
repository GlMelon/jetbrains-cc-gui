package com.github.claudecodegui.cli.compatibility;

import com.github.claudecodegui.session.runtime.ProviderType;

import java.util.regex.Pattern;

/** Parses Codex CLI version output, including non-semver labels around the numeric version. */
public final class CodexCliVersionParser extends AbstractCliVersionParser {

    private static final Pattern PATTERN = Pattern.compile(
            "(?i)" + Pattern.quote(ProviderType.CODEX.value())
                    + "(?:-cli|\\s+cli)?[^0-9]*v?" + VERSION_CAPTURE);

    public CodexCliVersionParser() {
        super(PATTERN);
    }

    @Override
    public ProviderType provider() {
        return ProviderType.CODEX;
    }
}
