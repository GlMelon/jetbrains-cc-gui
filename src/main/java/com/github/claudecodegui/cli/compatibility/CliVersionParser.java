package com.github.claudecodegui.cli.compatibility;

import com.github.claudecodegui.session.runtime.ProviderType;

import java.util.Optional;

/** Provider-specific parser for human-readable CLI {@code --version} output. */
public interface CliVersionParser {

    ProviderType provider();

    Optional<String> parse(String rawVersion);
}
