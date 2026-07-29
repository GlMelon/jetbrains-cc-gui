package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Dispatches already prepared history-list JSON to the frontend.
 */
class HistoryLoadService {

    private static final Logger LOG = Logger.getInstance(HistoryLoadService.class);

    private final HandlerContext context;

    HistoryLoadService(HandlerContext context) {
        this.context = context;
    }

    HistoryLoadService(HandlerContext context, Object ignoredLegacyDependency) {
        this(context);
    }

    void handleLoadHistoryData(String provider) {
        LOG.warn("[HistoryHandler] Legacy history reload requested without workflow owner: " + provider);
    }

    void dispatchHistoryData(String finalJson) {
        String safeJson = HistorySessionsJsonEnhancer.normalizeSessionsJson(finalJson);
        String base64Json = Base64.getEncoder().encodeToString(safeJson.getBytes(StandardCharsets.UTF_8));
        LOG.info("[HistoryHandler] Base64 编码完成，长度: " + base64Json.length());

        ApplicationManager.getApplication().invokeLater(() -> {
            String jsCode = "console.log('[Backend->Frontend] Starting to inject history data');" +
                    "if (window.setHistoryData) { " +
                    "  try { " +
                    "    var base64Str = '" + base64Json + "'; " +
                    "    console.log('[Backend->Frontend] Base64 length:', base64Str.length); " +
                    "    var binaryStr = atob(base64Str); " +
                    "    var bytes = new Uint8Array(binaryStr.length); " +
                    "    for (var i = 0; i < binaryStr.length; i++) { bytes[i] = binaryStr.charCodeAt(i); } " +
                    "    var jsonStr = new TextDecoder('utf-8').decode(bytes); " +
                    "    console.log('[Backend->Frontend] Decoded JSON length:', jsonStr.length); " +
                    "    var data = JSON.parse(jsonStr); " +
                    "    console.log('[Backend->Frontend] Parsed data, sessions:', data.sessions ? data.sessions.length : 0); " +
                    "    window.setHistoryData(data); " +
                    "    console.log('[Backend->Frontend] setHistoryData called successfully'); " +
                    "  } catch(e) { " +
                    "    console.error('[Backend->Frontend] Failed to parse/set history data:', e); " +
                    "    window.setHistoryData({ success: false, error: '解析历史数据失败: ' + e.message }); " +
                    "  } " +
                    "} else { " +
                    "  console.error('[Backend->Frontend] setHistoryData not available!'); " +
                    "}";

            context.executeJavaScriptOnEDT(jsCode);
            LOG.info("[HistoryHandler] JavaScript 代码已注入");
        });
    }

    void dispatchHistoryDataError(String message) {
        ApplicationManager.getApplication().invokeLater(() -> {
            String errorMsg = context.escapeJs(message != null ? message : "未知错误");
            String jsCode = "if (window.setHistoryData) { " +
                    "  window.setHistoryData({ success: false, error: '" + errorMsg + "' }); " +
                    "}";
            context.executeJavaScriptOnEDT(jsCode);
        });
    }
}
