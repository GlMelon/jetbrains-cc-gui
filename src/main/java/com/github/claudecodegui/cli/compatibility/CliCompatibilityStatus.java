package com.github.claudecodegui.cli.compatibility;

/** Result category returned by the backend CLI compatibility evaluator. */
public enum CliCompatibilityStatus {
    COMPATIBLE,
    UNSUPPORTED,
    BLOCKED,
    UNKNOWN_ALLOWED,
    UNKNOWN_BLOCKED,
    AHEAD_ALLOWED,
    AHEAD_BLOCKED
}
