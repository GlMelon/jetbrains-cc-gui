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
            CliVersionPolicy higherVersionPolicy,
            /** Optional per-feature version gates (nullable when absent = feature not gated).
             * Key = feature id (e.g. {@code "acp"}); presence declares the feature is available
             * and the version bounds within which it is supported. Absent map = no feature gates. */
            Map<String, FeatureRule> features) {

        /** Convenience accessor tolerating a null map (legacy manifests without features). */
        public FeatureRule feature(String id) {
            return features == null ? null : features.get(id);
        }
    }

    /** Version gate for a single optional feature (e.g. ACP channel support). */
    public record FeatureRule(
            String minimumSupported,
            String maximumTested) {
    }
}
