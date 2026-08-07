package com.github.claudecodegui.cli.compatibility;

import com.github.claudecodegui.session.runtime.ProviderType;

import java.util.regex.Pattern;

/** Parses Kimi CLI version output, including labelled and prerelease forms. */
public final class KimiCliVersionParser extends AbstractCliVersionParser {

    private static final Pattern PATTERN = Pattern.compile(
            "(?i)" + Pattern.quote(ProviderType.KIMI.value())
                    + "[^0-9]*v?" + VERSION_CAPTURE);

    public KimiCliVersionParser() {
        super(PATTERN);
    }

    @Override
    public ProviderType provider() {
        return ProviderType.KIMI;
    }
}
