package com.github.claudecodegui.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WebviewResourceRequestHandlerTest {

    @Test
    public void buildsStableIndexUrlWithPageGeneration() {
        assertEquals(
                "https://cc-gui-webview.local/index.html?pageGeneration=42",
                WebviewResourceRequestHandler.indexUrl(42)
        );
    }

    @Test
    public void acceptsOnlyTheWebviewOrigin() {
        assertTrue(WebviewResourceRequestHandler.isWebviewUrl(
                "https://cc-gui-webview.local/index.html?pageGeneration=1"));
        assertFalse(WebviewResourceRequestHandler.isWebviewUrl("http://cc-gui-webview.local/index.html"));
        assertFalse(WebviewResourceRequestHandler.isWebviewUrl("https://example.com/index.html"));
        assertFalse(WebviewResourceRequestHandler.isWebviewUrl(
                "https://user@cc-gui-webview.local/index.html"));
        assertFalse(WebviewResourceRequestHandler.isWebviewUrl(
                "https://cc-gui-webview.local:8443/index.html"));
    }

    @Test
    public void normalizesAndRejectsUnsafeResourcePaths() {
        assertEquals(
                "/index.html",
                WebviewResourceRequestHandler.resourcePath("https://cc-gui-webview.local/")
        );
        assertEquals(
                "/assets/index-abc.js",
                WebviewResourceRequestHandler.resourcePath(
                        "https://cc-gui-webview.local/assets/index-abc.js")
        );
        assertNull(WebviewResourceRequestHandler.resourcePath(
                "https://cc-gui-webview.local/../secret"));
        assertNull(WebviewResourceRequestHandler.resourcePath(
                "https://cc-gui-webview.local/%2e%2e/secret"));
        assertNull(WebviewResourceRequestHandler.resourcePath(
                "https://cc-gui-webview.local/%5csecret"));
    }

    @Test
    public void parsesPageGenerationDefensively() {
        assertEquals(17, WebviewResourceRequestHandler.pageGeneration(
                "https://cc-gui-webview.local/index.html?pageGeneration=17"));
        assertEquals(-1, WebviewResourceRequestHandler.pageGeneration(
                "https://cc-gui-webview.local/index.html"));
        assertEquals(-1, WebviewResourceRequestHandler.pageGeneration(
                "https://cc-gui-webview.local/index.html?pageGeneration=bad"));
        assertEquals(-1, WebviewResourceRequestHandler.pageGeneration(
                "https://example.com/index.html?pageGeneration=17"));
    }

    @Test
    public void resolvesCommonWebviewMimeTypes() {
        assertEquals("text/html", WebviewResourceRequestHandler.mimeTypeForPath("/index.html"));
        assertEquals("application/javascript", WebviewResourceRequestHandler.mimeTypeForPath("/assets/index.js"));
        assertEquals("text/css", WebviewResourceRequestHandler.mimeTypeForPath("/assets/index.css"));
        assertEquals("font/woff2", WebviewResourceRequestHandler.mimeTypeForPath("/assets/icon.woff2"));
        assertEquals("application/octet-stream", WebviewResourceRequestHandler.mimeTypeForPath("/assets/data.bin"));
    }
}
