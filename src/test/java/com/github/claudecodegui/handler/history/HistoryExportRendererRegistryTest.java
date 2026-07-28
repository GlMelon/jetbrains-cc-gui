package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.protocol.HistoryExportFormat;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class HistoryExportRendererRegistryTest {

    @Test
    public void routesJsonAndHtmlRenderersByFormat() {
        HistoryExportRenderer json = renderer(HistoryExportFormat.JSON);
        HistoryExportRenderer html = renderer(HistoryExportFormat.HTML);
        HistoryExportRendererRegistry registry = new HistoryExportRendererRegistry(List.of(json, html));

        assertSame(json, registry.require(HistoryExportFormat.JSON));
        assertSame(html, registry.require(HistoryExportFormat.HTML));
    }

    @Test
    public void rejectsDuplicateFormats() {
        try {
            new HistoryExportRendererRegistry(List.of(
                    renderer(HistoryExportFormat.JSON),
                    renderer(HistoryExportFormat.JSON)
            ));
            fail("Expected duplicate renderer registration to fail");
        } catch (IllegalArgumentException error) {
            assertEquals("Duplicate history export renderer: json", error.getMessage());
        }
    }

    @Test
    public void rejectsNullRenderer() {
        try {
            new HistoryExportRendererRegistry(java.util.Arrays.asList(renderer(HistoryExportFormat.JSON), null));
            fail("Expected null renderer registration to fail");
        } catch (IllegalArgumentException error) {
            assertEquals("History export renderer and format must be non-null", error.getMessage());
        }
    }

    @Test
    public void rejectsMissingAndNullFormats() {
        HistoryExportRendererRegistry registry = new HistoryExportRendererRegistry(List.of());

        assertUnsupported(registry, HistoryExportFormat.JSON, "Unsupported history export format: JSON");
        assertUnsupported(registry, null, "Unsupported history export format: null");
    }

    private static HistoryExportRenderer renderer(HistoryExportFormat format) {
        return new HistoryExportRenderer() {
            @Override
            public HistoryExportFormat format() {
                return format;
            }

            @Override
            public String render(HistoryExportDocument document) {
                return format.value();
            }
        };
    }

    private static void assertUnsupported(
            HistoryExportRendererRegistry registry,
            HistoryExportFormat format,
            String expectedMessage
    ) {
        try {
            registry.require(format);
            fail("Expected unsupported format to fail");
        } catch (IllegalArgumentException error) {
            assertEquals(expectedMessage, error.getMessage());
        }
    }
}
