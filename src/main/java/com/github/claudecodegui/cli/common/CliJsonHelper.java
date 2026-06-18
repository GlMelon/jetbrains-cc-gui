package com.github.claudecodegui.cli.common;

import java.util.List;

/**
 * CLI 包共享通用工具方法。
 */
public final class CliJsonHelper {

    private CliJsonHelper() {
    }

    /**
     * 检测关键词列表中是否有任何一个被目标字符串包含（大小写不敏感）。
     */
    public static boolean containsAnyKeyword(String target, List<String> keywords) {
        if (target == null || keywords == null) {
            return false;
        }
        String lower = target.toLowerCase();
        return keywords.stream().anyMatch(lower::contains);
    }
}
