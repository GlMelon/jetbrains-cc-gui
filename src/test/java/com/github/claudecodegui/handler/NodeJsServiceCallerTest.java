package com.github.claudecodegui.handler;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class NodeJsServiceCallerTest {

    @Test
    public void favoritesAllowlistIncludesRemoveFavoriteForDeleteCleanup() throws Exception {
        Path source = Path.of("src/main/java/com/github/claudecodegui/handler/NodeJsServiceCaller.java");
        String text = Files.readString(source, StandardCharsets.UTF_8);

        assertTrue(text.contains("\"removeFavorite\""));
    }
}
