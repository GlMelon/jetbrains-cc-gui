package com.github.claudecodegui.handler.core;

/**
 * Base class for message handlers.
 * Provides common utility methods.
 */
public abstract class BaseMessageHandler implements MessageHandler {

    protected final HandlerContext context;

    public BaseMessageHandler(HandlerContext context) {
        this.context = context;
    }

    /**
     * Call a JavaScript function.
     */
    protected void callJavaScript(String functionName, String... args) {
        context.callJavaScript(functionName, args);
    }

    /**
     * 下行总线语义化入口(归一化重构)。Phase 0 双轨:内部走 window.__bridge.dispatch,
     * 行为与旧 callJavaScript("window.xxx") 等价。后续 Phase handler 逐步迁移到本方法。
     * 详见 plan: typed-booping-newt.md。
     */
    protected void dispatchEvent(String type, String payloadJson) {
        context.dispatchEvent(type, payloadJson);
    }

    /**
     * Escape a JavaScript string.
     */
    protected String escapeJs(String str) {
        return context.escapeJs(str);
    }

    /**
     * Execute JavaScript on the EDT (Event Dispatch Thread).
     */
    protected void executeJavaScript(String jsCode) {
        context.executeJavaScriptOnEDT(jsCode);
    }

    /**
     * Check whether the message type matches any of the supported types.
     */
    protected boolean matchesType(String type, String... supportedTypes) {
        for (String supported : supportedTypes) {
            if (supported.equals(type)) {
                return true;
            }
        }
        return false;
    }
}
