package com.github.claudecodegui.cli.compatibility;

/** Identifies which trusted manifest copy produced a compatibility decision. */
public enum CliCompatibilityManifestSource {
    BUNDLED,
    CACHED_REMOTE,
    REMOTE
}
