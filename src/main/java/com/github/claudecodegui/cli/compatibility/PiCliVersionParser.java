package com.github.claudecodegui.cli.compatibility;

import com.github.claudecodegui.session.runtime.ProviderType;

import java.util.regex.Pattern;

/** Parses Pi CLI version output, including labelled and prerelease forms. */
public final class PiCliVersionParser extends AbstractCliVersionParser {

    private static final Pattern PATTERN = Pattern.compile(
            "(?i)" + Pattern.quote(ProviderType.PI.value())
                    + "[^0-9]*v?" + VERSION_CAPTURE);

    public PiCliVersionParser() {
        super(PATTERN);
    }

    @Override
    public ProviderType provider() {
        return ProviderType.PI;
    }
}
