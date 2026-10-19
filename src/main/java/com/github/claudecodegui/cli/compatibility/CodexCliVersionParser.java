package com.github.claudecodegui.cli.compatibility;

import com.github.claudecodegui.session.runtime.ProviderType;

import java.util.regex.Pattern;

/** Parses Codex CLI version output, including non-semver labels around the numeric version. */
public final class CodexCliVersionParser extends AbstractCliVersionParser {

    private static final Pattern PATTERN = Pattern.compile(
            "(?i)" + Pattern.quote(ProviderType.CODEX.value())
                    + "(?:-cli|\\s+cli)?[^0-9]*v?" + VERSION_CAPTURE);

    /**
     * 探测门控(对齐 upstream fe95dd8e):仅当 `codex --version` 输出真实
     * `codex-cli <version>` 行才信任该安装。半安装的 npm launcher 仍可执行
     * (exit 0),但输出兜不住该契约,不能凭此充当可用 CLI。宽松 {@link #PATTERN}
     * 继续负责历史输出的归一化解析,两者职责不同。
     */
    public static final Pattern STRICT_VERSION_LINE = Pattern.compile(
            "(?i)^\\h*" + Pattern.quote(ProviderType.CODEX.value()) + "-cli\\h+v?"
                    + VERSION_CAPTURE + "\\h*$");

    public CodexCliVersionParser() {
        super(PATTERN);
    }

    @Override
    public ProviderType provider() {
        return ProviderType.CODEX;
    }
}
