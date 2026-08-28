package com.github.claudecodegui.cli.common;

/**
 * 插件注入上下文的展示侧清理工具(注入标记见 {@link CliConstants#PROMPT_OPENED_FILES} 等)。
 *
 * <p>发送链会把打开文件/引用文件/agent 角色拼进 prompt;上游 CLI(kimi 实测)把 prompt 首行
 * 落为会话标题、原文落进会话历史,注入段随之污染展示。所有「读回来给用户看」的出口
 * (实时 SESSION_TITLE、历史列表标题、历史消息回放)统一经 {@link #stripInjectedContext}
 * 还原用户真实输入,注入标记的单一权威定义仍在 {@link CliConstants}。
 */
public final class CliPromptContexts {

    /** 标题/预览截断上限,与历史 reader 侧既有行为一致(NativeCliHistoryMessages.TITLE_PREVIEW_CHARS)。 */
    private static final int PREVIEW_CHARS = 60;

    private CliPromptContexts() {
    }

    /**
     * 剥离注入上下文:取最早出现的注入标记之前的部分。无标记时原样返回;
     * 标记前为空白(极端:整条都是注入)时返回原文,避免清空。
     */
    public static String stripInjectedContext(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int cut = -1;
        for (String marker : new String[]{
                CliConstants.PROMPT_OPENED_FILES.strip(),
                CliConstants.PROMPT_REFERENCED.strip(),
                CliConstants.PROMPT_AGENT_ROLE.strip()}) {
            int i = text.indexOf(marker);
            if (i >= 0 && (cut < 0 || i < cut)) {
                cut = i;
            }
        }
        if (cut < 0) {
            return text;
        }
        String cleaned = text.substring(0, cut).trim();
        return cleaned.isEmpty() ? text : cleaned;
    }

    /** 预览截断(60 字符 + …),超长会话标题的展示统一出口。 */
    public static String truncatePreview(String text) {
        String trimmed = text == null ? "" : text.trim();
        return trimmed.length() <= PREVIEW_CHARS ? trimmed
                : trimmed.substring(0, PREVIEW_CHARS) + "…";
    }
}
