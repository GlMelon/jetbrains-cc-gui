package com.github.claudecodegui.cli.compatibility;

import com.github.claudecodegui.session.runtime.ProviderType;

import java.util.regex.Pattern;

/** Parses Grok CLI version output, including labelled and prerelease forms. */
public final class GrokCliVersionParser extends AbstractCliVersionParser {

    private static final Pattern PATTERN = Pattern.compile(
            "(?i)" + Pattern.quote(ProviderType.GROK.value())
                    + "[^0-9]*v?" + VERSION_CAPTURE);

    public GrokCliVersionParser() {
        super(PATTERN);
    }

    @Override
    public ProviderType provider() {
        return ProviderType.GROK;
    }
}
