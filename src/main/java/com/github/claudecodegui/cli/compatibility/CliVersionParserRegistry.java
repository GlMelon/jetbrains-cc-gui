package com.github.claudecodegui.cli.compatibility;

import com.github.claudecodegui.session.runtime.ProviderType;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Fail-fast provider parser registry; adding a provider requires only a new parser registration. */
public final class CliVersionParserRegistry {

    private final Map<ProviderType, CliVersionParser> parsers = new EnumMap<>(ProviderType.class);

    public CliVersionParserRegistry(List<CliVersionParser> parserList) {
        for (CliVersionParser parser : parserList) {
            CliVersionParser previous = parsers.put(parser.provider(), parser);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate CLI version parser for " + parser.provider());
            }
        }
        for (ProviderType provider : ProviderType.values()) {
            if (!parsers.containsKey(provider)) {
                throw new IllegalArgumentException("Missing CLI version parser for " + provider);
            }
        }
    }

    public static CliVersionParserRegistry defaults() {
        return new CliVersionParserRegistry(Arrays.asList(
                new ClaudeCliVersionParser(),
                new CodexCliVersionParser(),
                new OpenCodeCliVersionParser(),
                new GrokCliVersionParser(),
                new KimiCliVersionParser(),
                new PiCliVersionParser()));
    }

    public Optional<String> parse(ProviderType provider, String rawVersion) {
        CliVersionParser parser = parsers.get(provider);
        return parser == null ? Optional.empty() : parser.parse(rawVersion);
    }
}
