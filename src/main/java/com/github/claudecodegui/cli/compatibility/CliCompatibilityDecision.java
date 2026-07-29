package com.github.claudecodegui.cli.compatibility;

import com.github.claudecodegui.session.runtime.ProviderType;

/** Immutable backend decision for one provider CLI version. */
public record CliCompatibilityDecision(
        ProviderType provider,
        String rawVersion,
        String normalizedVersion,
        CliCompatibilityStatus status,
        boolean allowed,
        boolean warning,
        long manifestRevision,
        CliCompatibilityManifestSource manifestSource) {
}
