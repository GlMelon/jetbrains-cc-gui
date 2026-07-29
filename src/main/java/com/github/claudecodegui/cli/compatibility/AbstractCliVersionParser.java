package com.github.claudecodegui.cli.compatibility;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared extraction template for provider-specific CLI version parsers. */
abstract class AbstractCliVersionParser implements CliVersionParser {

    protected static final String VERSION_CAPTURE =
            "(\\d+(?:\\.\\d+){1,3}(?:[-+][0-9a-z.-]+)?)";
    private static final Pattern NUMERIC_FALLBACK =
            Pattern.compile("(?i)(?:^|[^0-9])v?" + VERSION_CAPTURE);

    private final Pattern providerPattern;

    AbstractCliVersionParser(Pattern providerPattern) {
        this.providerPattern = providerPattern;
    }

    @Override
    public final Optional<String> parse(String rawVersion) {
        if (rawVersion == null || rawVersion.isBlank()) {
            return Optional.empty();
        }
        Optional<String> providerMatch = extract(providerPattern, rawVersion.trim());
        return providerMatch.isPresent() ? providerMatch : extract(NUMERIC_FALLBACK, rawVersion.trim());
    }

    private static Optional<String> extract(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String version = matcher.group(1);
        return version == null || version.isBlank() ? Optional.empty() : Optional.of(version);
    }
}
