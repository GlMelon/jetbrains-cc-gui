package com.github.claudecodegui.cli.compatibility;

import com.github.claudecodegui.session.runtime.ProviderType;

import java.util.regex.Pattern;

/**
 * Parses MiniMax Code CLI version output. The official launcher/npm bin is
 * {@code mcode}; {@code minimax} is kept as a defensive alias, so the pattern
 * accepts both labels before the numeric token.
 */
public final class MiniMaxCliVersionParser extends AbstractCliVersionParser {

    private static final Pattern PATTERN = Pattern.compile(
            "(?i)(?:" + Pattern.quote(ProviderType.MINIMAX.value()) + "|mcode)"
                    + "(?:-cli|\\s+cli)?[^0-9]*v?" + VERSION_CAPTURE);

    public MiniMaxCliVersionParser() {
        super(PATTERN);
    }

    @Override
    public ProviderType provider() {
        return ProviderType.MINIMAX;
    }
}
