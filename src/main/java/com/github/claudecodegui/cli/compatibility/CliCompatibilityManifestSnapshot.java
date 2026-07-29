package com.github.claudecodegui.cli.compatibility;

/** A validated compatibility manifest together with its trust source. */
public record CliCompatibilityManifestSnapshot(
        CliCompatibilityManifest manifest,
        CliCompatibilityManifestSource source) {
}
