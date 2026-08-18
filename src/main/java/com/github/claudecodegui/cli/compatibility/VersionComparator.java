package com.github.claudecodegui.cli.compatibility;

import com.intellij.openapi.diagnostic.Logger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 版本字符串比较工具。
 *
 * <p>从原 {@code dependency} 包迁出(CLI 版本门禁 {@link CliCompatibilityService} /
 * {@link CliCompatibilityManifestCodec} 使用)。纯静态版本比较与归一化,无 SDK 依赖。
 * 原 {@code resolveVersionAction}(依赖已删的 {@code VersionAction})不再保留。</p>
 */
public final class VersionComparator {

    private static final Logger LOG = Logger.getInstance(VersionComparator.class);

    /**
     * Semver-like pattern: major.minor.patch with optional pre-release suffix.
     * Only allows digits, dots, hyphens, and alphanumeric pre-release tags.
     */
    private static final Pattern SEMVER_PATTERN =
            Pattern.compile("^\\d+\\.\\d+\\.\\d+([-.][a-zA-Z0-9.]+)*$");

    private VersionComparator() {
    }

    /**
     * Compares two version strings.
     *
     * @return negative if v1 &lt; v2, 0 if equal, positive if v1 &gt; v2
     */
    public static int compareVersions(String v1, String v2) {
        if (v1 == null || v2 == null) {
            return 0;
        }

        // Strip the leading 'v' prefix
        v1 = (v1.startsWith("v") || v1.startsWith("V")) ? v1.substring(1) : v1;
        v2 = (v2.startsWith("v") || v2.startsWith("V")) ? v2.substring(1) : v2;

        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");

        int maxLen = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < maxLen; i++) {
            int num1 = i < parts1.length ? parseVersionPart(parts1[i]) : 0;
            int num2 = i < parts2.length ? parseVersionPart(parts2[i]) : 0;

            if (num1 != num2) {
                return num1 - num2;
            }
        }

        return 0;
    }

    /**
     * 归一化版本字符串:去除前导 v/V 前缀 + 空格,验证 semver 格式。
     *
     * @return 归一化后的版本(无前导 v),或 null(格式非法)
     */
    public static String normalizeRequestedVersion(String version) {
        if (version == null) {
            return null;
        }

        String trimmed = version.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        if (trimmed.startsWith("v") || trimmed.startsWith("V")) {
            trimmed = trimmed.substring(1);
        }

        if (!SEMVER_PATTERN.matcher(trimmed).matches()) {
            LOG.warn("[VersionComparator] Rejected invalid version format: " + version);
            return null;
        }

        return trimmed;
    }

    /**
     * Parses a single segment of a version string.
     */
    private static int parseVersionPart(String part) {
        // Strip non-numeric suffixes (e.g. -beta, -alpha)
        Pattern pattern = Pattern.compile("^(\\d+)");
        Matcher matcher = pattern.matcher(part);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return 0;
    }
}
