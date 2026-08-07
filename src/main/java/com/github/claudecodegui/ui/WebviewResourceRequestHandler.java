package com.github.claudecodegui.ui;

import com.intellij.openapi.diagnostic.Logger;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefCallback;
import org.cef.handler.CefLoadHandler;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.handler.CefResourceHandler;
import org.cef.handler.CefResourceHandlerAdapter;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.handler.CefResourceRequestHandlerAdapter;
import org.cef.misc.BoolRef;
import org.cef.misc.IntRef;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.IntFunction;

/**
 * Serves the bundled Vite webview from a stable same-origin HTTPS URL.
 *
 * <p>The handler keeps the frontend as normal HTML/CSS/ES modules instead of
 * serializing the complete application through {@code JBCefBrowser.loadHTML}.
 * This removes the single-string size limit and lets Chromium load lazy chunks
 * through the same custom origin.</p>
 */
final class WebviewResourceRequestHandler extends CefRequestHandlerAdapter {

    static final String WEBVIEW_ORIGIN = "https://cc-gui-webview.local";

    private static final Logger LOG = Logger.getInstance(WebviewResourceRequestHandler.class);
    private static final String WEBVIEW_HOST = "cc-gui-webview.local";
    private static final String RESOURCE_ROOT = "/webview";
    private static final String INDEX_PATH = "/index.html";
    private static final String PAGE_GENERATION_PARAMETER = "pageGeneration";
    private static final String DEFAULT_MIME_TYPE = "application/octet-stream";

    private final Class<?> resourceClass;
    private final IntFunction<String> indexHtmlProvider;
    private final CefResourceRequestHandler resourceRequestHandler = new CefResourceRequestHandlerAdapter() {
        @Override
        public CefResourceHandler getResourceHandler(
                CefBrowser browser,
                CefFrame frame,
                CefRequest request
        ) {
            return new WebviewResourceHandler(request.getURL());
        }
    };

    WebviewResourceRequestHandler(
            @NotNull Class<?> resourceClass,
            @NotNull IntFunction<String> indexHtmlProvider
    ) {
        this.resourceClass = resourceClass;
        this.indexHtmlProvider = indexHtmlProvider;
    }

    static String indexUrl(int pageGeneration) {
        return WEBVIEW_ORIGIN + INDEX_PATH + "?" + PAGE_GENERATION_PARAMETER + "=" + pageGeneration;
    }

    static boolean isWebviewUrl(@Nullable String url) {
        URI uri = parseUri(url);
        return uri != null
                && "https".equalsIgnoreCase(uri.getScheme())
                && WEBVIEW_HOST.equalsIgnoreCase(uri.getHost())
                && uri.getPort() == -1
                && uri.getUserInfo() == null;
    }

    @Nullable
    static String resourcePath(@Nullable String url) {
        URI uri = parseUri(url);
        if (uri == null || !isWebviewUrl(url)) {
            return null;
        }

        String path = uri.getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return INDEX_PATH;
        }
        if (!path.startsWith("/") || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) {
            return null;
        }

        String[] segments = path.split("/", -1);
        for (String segment : segments) {
            if (".".equals(segment) || "..".equals(segment)) {
                return null;
            }
        }
        return path;
    }

    static int pageGeneration(@Nullable String url) {
        URI uri = parseUri(url);
        if (uri == null || !isWebviewUrl(url) || uri.getRawQuery() == null) {
            return -1;
        }

        for (String entry : uri.getRawQuery().split("&")) {
            int separator = entry.indexOf('=');
            String rawKey = separator >= 0 ? entry.substring(0, separator) : entry;
            if (!PAGE_GENERATION_PARAMETER.equals(decodeQueryValue(rawKey))) {
                continue;
            }
            String rawValue = separator >= 0 ? entry.substring(separator + 1) : "";
            try {
                return Integer.parseInt(decodeQueryValue(rawValue));
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    static String mimeTypeForPath(@NotNull String path) {
        String lowerPath = path.toLowerCase(Locale.ROOT);
        if (lowerPath.endsWith(".html")) {
            return "text/html";
        }
        if (lowerPath.endsWith(".js") || lowerPath.endsWith(".mjs")) {
            return "application/javascript";
        }
        if (lowerPath.endsWith(".css")) {
            return "text/css";
        }
        if (lowerPath.endsWith(".json")) {
            return "application/json";
        }
        if (lowerPath.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (lowerPath.endsWith(".png")) {
            return "image/png";
        }
        if (lowerPath.endsWith(".jpg") || lowerPath.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lowerPath.endsWith(".webp")) {
            return "image/webp";
        }
        if (lowerPath.endsWith(".woff2")) {
            return "font/woff2";
        }
        if (lowerPath.endsWith(".woff")) {
            return "font/woff";
        }
        if (lowerPath.endsWith(".ttf")) {
            return "font/ttf";
        }
        return DEFAULT_MIME_TYPE;
    }

    @Override
    public CefResourceRequestHandler getResourceRequestHandler(
            CefBrowser browser,
            CefFrame frame,
            CefRequest request,
            boolean isNavigation,
            boolean isDownload,
            String requestInitiator,
            BoolRef disableDefaultHandling
    ) {
        if (!isWebviewUrl(request.getURL())) {
            return null;
        }

        disableDefaultHandling.set(true);
        return resourceRequestHandler;
    }

    @Nullable
    private static URI parseUri(@Nullable String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        try {
            return URI.create(url);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String decodeQueryValue(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private final class WebviewResourceHandler extends CefResourceHandlerAdapter {
        private final String url;
        private InputStream stream;
        private ResourceResponse resource;

        private WebviewResourceHandler(String url) {
            this.url = url;
        }

        @Override
        public boolean processRequest(CefRequest request, CefCallback callback) {
            resource = resolveResource(url);
            if (resource == null) {
                LOG.warn("[WebviewResource] Resource not found or rejected: " + url);
                callback.Continue();
                return true;
            }

            stream = new ByteArrayInputStream(resource.content());
            callback.Continue();
            return true;
        }

        @Override
        public void getResponseHeaders(CefResponse response, IntRef responseLength, StringRef redirectUrl) {
            if (resource == null) {
                response.setStatus(404);
                response.setStatusText("Not Found");
                response.setError(CefLoadHandler.ErrorCode.ERR_FILE_NOT_FOUND);
                responseLength.set(0);
                return;
            }

            response.setStatus(200);
            response.setStatusText("OK");
            response.setMimeType(resource.mimeType());
            response.setHeaderByName("Cache-Control", resource.cacheControl(), true);
            response.setHeaderByName("X-Content-Type-Options", "nosniff", true);
            responseLength.set(resource.content().length);
        }

        @Override
        public boolean readResponse(byte[] dataOut, int bytesToRead, IntRef bytesRead, CefCallback callback) {
            if (stream == null) {
                bytesRead.set(0);
                return false;
            }

            try {
                int bytesReadNow = stream.read(dataOut, 0, Math.min(bytesToRead, dataOut.length));
                if (bytesReadNow > 0) {
                    bytesRead.set(bytesReadNow);
                    return true;
                }
            } catch (IOException e) {
                LOG.warn("[WebviewResource] Failed while streaming resource: " + url, e);
            }

            closeStream();
            bytesRead.set(0);
            return false;
        }

        @Override
        public void cancel() {
            closeStream();
        }

        @Nullable
        private ResourceResponse resolveResource(String resourceUrl) {
            String path = resourcePath(resourceUrl);
            if (path == null) {
                return null;
            }

            try {
                if (INDEX_PATH.equals(path)) {
                    int generation = pageGeneration(resourceUrl);
                    if (generation < 0) {
                        return null;
                    }
                    byte[] content = indexHtmlProvider.apply(generation).getBytes(StandardCharsets.UTF_8);
                    return new ResourceResponse(content, mimeTypeForPath(path), "no-store");
                }

                try (InputStream input = resourceClass.getResourceAsStream(RESOURCE_ROOT + path)) {
                    if (input == null) {
                        return null;
                    }
                    return new ResourceResponse(
                            input.readAllBytes(),
                            mimeTypeForPath(path),
                            "private, max-age=31536000, immutable"
                    );
                }
            } catch (Exception e) {
                LOG.warn("[WebviewResource] Failed to resolve resource: " + resourceUrl, e);
                return null;
            }
        }

        private void closeStream() {
            if (stream == null) {
                return;
            }
            try {
                stream.close();
            } catch (IOException e) {
                LOG.debug("[WebviewResource] Failed to close resource stream: " + e.getMessage());
            } finally {
                stream = null;
            }
        }
    }

    private record ResourceResponse(byte[] content, String mimeType, String cacheControl) {
    }
}
