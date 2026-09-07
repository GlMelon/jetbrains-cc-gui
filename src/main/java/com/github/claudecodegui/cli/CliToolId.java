package com.github.claudecodegui.cli;

/**
 * Supported headless CLI tools shown in Settings → Provider Management → CLI.
 */
public enum CliToolId {
    GROK("grok", "Grok CLI", "grok", null),
    KIMI("kimi", "Kimi CLI", "kimi", null),
    OPENCODE("opencode", "OpenCode", "opencode", null),
    PI("pi", "PI CLI", "pi", null),
    OMP("omp", "OMP CLI", "omp", null),
    DSH("dsh", "DeepSeek Harness", "dsh", null),
    // MiniMax Code:官方安装器(~/.minimax-code)与 npm 包 @minimax-ai/code 的 bin
    // 均为 `mcode`(官方文档/install 脚本核实);`minimax` 不是官方命令名,仅作防御性 alt 保留。
    MINIMAX("minimax", "MiniMax Code", "mcode", "minimax");

    private final String id;
    private final String displayName;
    private final String binaryName;
    private final String altBinaryName;

    CliToolId(String id, String displayName, String binaryName, String altBinaryName) {
        this.id = id;
        this.displayName = displayName;
        this.binaryName = binaryName;
        this.altBinaryName = altBinaryName;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getBinaryName() {
        return binaryName;
    }

    /**
     * Secondary command name to probe when the primary one is not found
     * (e.g. MiniMax Code: primary `mcode` from the official installer/npm,
     * defensive alt `minimax`).
     */
    public String getAltBinaryName() {
        return altBinaryName;
    }

    public static CliToolId fromId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase();
        for (CliToolId tool : values()) {
            if (tool.id.equals(normalized)) {
                return tool;
            }
        }
        return null;
    }
}
