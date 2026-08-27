package com.github.claudecodegui.cli.common;

import java.io.File;
import java.util.List;

/**
 * 无原生多模态 flag 的 CLI(grok/kimi/pi)图片附件的 prompt 注入文本构造。
 * 对称移植自 ai-bridge/utils/cli-image-input.js(buildKimiPromptWithImages /
 * buildReadPathPromptWithImages);文件本体由基类
 * {@link AbstractRunOnceCliSession} 已物化为临时磁盘文件,此处只做路径注入。
 */
public final class CliImagePromptInjections {

    private CliImagePromptInjections() {
    }

    /**
     * kimi headless prompt 注入:media 路径标签 + ReadMediaFile 指令。
     * 与 Node 版逐字符对齐(含 HTML 注释稳定分隔标记),保证与旧链路行为一致。
     */
    public static String buildKimiPromptWithImages(String text, List<File> imageFiles) {
        if (imageFiles == null || imageFiles.isEmpty()) {
            return text == null ? "" : text;
        }
        StringBuilder out = new StringBuilder(stripTrailingWhitespace(text));
        out.append("\n\n<!-- mossx:kimi-image-attachments -->\n");
        out.append("The user attached the following image file(s). ");
        out.append("You MUST call ReadMediaFile on each path below before answering any question about visual content.\n");
        for (int i = 0; i < imageFiles.size(); i++) {
            String p = imageFiles.get(i).getAbsolutePath();
            int n = i + 1;
            out.append(n).append(". ").append(p).append('\n');
            out.append("<image path=\"").append(escapeXmlAttr(p)).append("\"></image>\n");
        }
        return out.toString();
    }

    /**
     * 通用路径注入(pi 等无多模态 flag 的 CLI):[Image #N: path] 引用列表 + Read 工具指令,
     * 用户文本为空时使用图优先兜底文案(对称 JS GROK_IMAGE_ONLY_FALLBACK_TEXT)。
     */
    public static String buildReadPathPromptWithImages(String text, List<File> imageFiles) {
        if (imageFiles == null || imageFiles.isEmpty()) {
            return text == null ? "" : text;
        }
        StringBuilder refs = new StringBuilder();
        for (int i = 0; i < imageFiles.size(); i++) {
            if (i > 0) {
                refs.append('\n');
            }
            refs.append("[Image #").append(i + 1).append(": ")
                    .append(imageFiles.get(i).getAbsolutePath()).append(']');
        }
        String userText = text != null && !text.isBlank()
                ? text
                : "Please analyze the attached image(s).";
        return refs + "\n\nThe user has attached the image(s) above. Please use the Read tool to view them."
                + "\n\n" + userText;
    }

    private static String stripTrailingWhitespace(String text) {
        if (text == null) {
            return "";
        }
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    private static String escapeXmlAttr(String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
