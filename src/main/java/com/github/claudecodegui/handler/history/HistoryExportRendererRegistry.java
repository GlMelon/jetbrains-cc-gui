package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.protocol.HistoryExportFormat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fail-fast registry for history export renderers. */
final class HistoryExportRendererRegistry {
    private final Map<HistoryExportFormat, HistoryExportRenderer> renderers;

    HistoryExportRendererRegistry() {
        this(List.of(new JsonHistoryExportRenderer(), new HtmlHistoryExportRenderer()));
    }

    HistoryExportRendererRegistry(List<HistoryExportRenderer> renderers) {
        Map<HistoryExportFormat, HistoryExportRenderer> registered = new LinkedHashMap<>();
        for (HistoryExportRenderer renderer : renderers == null ? List.<HistoryExportRenderer>of() : renderers) {
            if (renderer == null || renderer.format() == null) {
                throw new IllegalArgumentException("History export renderer and format must be non-null");
            }
            HistoryExportRenderer previous = registered.putIfAbsent(renderer.format(), renderer);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate history export renderer: " + renderer.format().value());
            }
        }
        this.renderers = Map.copyOf(registered);
    }

    HistoryExportRenderer require(HistoryExportFormat format) {
        if (format == null) {
            throw new IllegalArgumentException("Unsupported history export format: null");
        }
        HistoryExportRenderer renderer = renderers.get(format);
        if (renderer == null) {
            throw new IllegalArgumentException("Unsupported history export format: " + format);
        }
        return renderer;
    }
}
