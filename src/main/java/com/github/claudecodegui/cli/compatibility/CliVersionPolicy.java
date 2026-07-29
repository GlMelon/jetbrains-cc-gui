package com.github.claudecodegui.cli.compatibility;

/** Policy applied when a detected CLI version is outside the known compatibility matrix. */
public enum CliVersionPolicy {
    ALLOW,
    WARN_ALLOW,
    BLOCK
}
