package com.github.claudecodegui.handler.history;

/** Escapes untrusted history content before it enters the static HTML export template. */
final class HistoryHtmlSanitizer {
    private HistoryHtmlSanitizer() {
    }

    static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(current);
            }
        }
        return escaped.toString();
    }
}
