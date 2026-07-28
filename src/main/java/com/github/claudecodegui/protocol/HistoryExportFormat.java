package com.github.claudecodegui.protocol;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** Backend-owned history export format contract shared with the Webview generator. */
public enum HistoryExportFormat implements ProtocolValue {
    JSON("json", ".json", "application/json;charset=utf-8", "file.saveJsonDialog"),
    HTML("html", ".html", "text/html;charset=utf-8", "file.saveHtmlDialog");

    private final String value;
    private final String fileExtension;
    private final String mimeType;
    private final String dialogTitleKey;

    HistoryExportFormat(String value, String fileExtension, String mimeType, String dialogTitleKey) {
        this.value = value;
        this.fileExtension = fileExtension;
        this.mimeType = mimeType;
        this.dialogTitleKey = dialogTitleKey;
    }

    @Override
    public String value() {
        return value;
    }

    public String fileExtension() {
        return fileExtension;
    }

    public String mimeType() {
        return mimeType;
    }

    public String dialogTitleKey() {
        return dialogTitleKey;
    }

    public boolean matchesFileName(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(fileExtension);
    }

    public static Optional<HistoryExportFormat> fromValue(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(format -> format.value.equals(value)).findFirst();
    }
}
