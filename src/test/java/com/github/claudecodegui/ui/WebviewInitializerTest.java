package com.github.claudecodegui.ui;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class WebviewInitializerTest {

    @Test
    public void bootstrapConfigurationUsesSingleDownstreamEvent() throws IOException {
        String source = readSource();

        Assert.assertTrue(source.contains("DownstreamEvent.WEBVIEW_BOOTSTRAP.value()"));
        Assert.assertTrue(source.contains("WebviewBootstrapPayloadFactory.create"));
        Assert.assertTrue(source.contains("host.getHandlerContext().dispatchEvent"));
    }

    @Test
    public void bootstrapKeepsInfrastructureJavascriptOnly() throws IOException {
        String source = readSource();

        Assert.assertTrue(source.contains("window.sendToJava"));
        Assert.assertTrue(source.contains("window.getClipboardFilePath"));
        Assert.assertFalse(source.contains("applyIdeaFontConfig"));
        Assert.assertFalse(source.contains("applyUiFontConfig"));
        Assert.assertFalse(source.contains("applyCodeFontConfig"));
        Assert.assertFalse(source.contains("applyIdeaLanguageConfig"));
        Assert.assertFalse(source.contains("applyAppearanceConfig"));
        Assert.assertFalse(source.contains("applyAvatarConfig"));
        Assert.assertFalse(source.contains("__pendingFontConfig"));
        Assert.assertFalse(source.contains("__pendingUiFontConfig"));
        Assert.assertFalse(source.contains("__pendingCodeFontConfig"));
        Assert.assertFalse(source.contains("__pendingLanguageConfig"));
        Assert.assertFalse(source.contains("__pendingAppearanceConfig"));
        Assert.assertFalse(source.contains("__pendingAvatarConfig"));
    }

    private static String readSource() throws IOException {
        return Files.readString(Path.of(
                "src/main/java/com/github/claudecodegui/ui/WebviewInitializer.java"),
                StandardCharsets.UTF_8);
    }
}
