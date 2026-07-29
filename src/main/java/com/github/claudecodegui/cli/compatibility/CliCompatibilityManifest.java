package com.github.claudecodegui.cli.compatibility;

import java.util.List;
import java.util.Map;

/** JSON-backed SSOT for all provider CLI compatibility policies. */
public record CliCompatibilityManifest(
        int schemaVersion,
        long revision,
        String generatedAt,
        Map<String, ProviderRule> providers) {

    /** Compatibility policy for one provider. */
    public record ProviderRule(
            String minimumSupported,
            String maximumTested,
            List<String> blockedVersions,
            CliVersionPolicy unknownVersionPolicy,
            CliVersionPolicy higherVersionPolicy) {
    }
}
