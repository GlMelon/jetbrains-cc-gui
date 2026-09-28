package com.github.claudecodegui.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class UserMessageSanitizerTest {

    @Test
    public void stripsOpenedFilesContextFromHistoryReplayText() {
        String original = "帮我看下这个问题\n\n## Opened Files Context\n\n[{\"path\":\"/tmp/App.java\"}]";

        String sanitized = UserMessageSanitizer.sanitizeUserFacingText(original);

        assertEquals("帮我看下这个问题", sanitized);
    }

    @Test
    public void stripsPrependedAgentInstructionsAndEnvironmentContext() {
        String original = "# AGENTS.md instructions for D:\\project\\demo\n\n"
                + "<INSTRUCTIONS>\n"
                + "请默认使用中文（简体）回复。\n"
                + "</INSTRUCTIONS>"
                + "<environment_context>\n"
                + "  <cwd>D:\\project\\demo</cwd>\n"
                + "</environment_context>"
                + "请帮我修复这个问题";

        String sanitized = UserMessageSanitizer.sanitizeUserFacingText(original);

        assertEquals("请帮我修复这个问题", sanitized);
    }

    @Test
    public void stripsImageAttachmentInstructionsFromUserFacingText() {
        String original = "请分析这张图\n\n"
                + "The user has attached the image(s) above. Please use the Read tool to view them.\n\n"
                + "Use the Read tool to inspect this image file, then answer using its visible content: "
                + "C:\\Users\\me\\AppData\\Local\\Temp\\upload.png";

        String sanitized = UserMessageSanitizer.sanitizeUserFacingText(original);

        assertEquals("请分析这张图", sanitized);
    }

    // ---- Regression tests ported from upstream bdcf21b2 (#1809): the Codex
    // ---- CLI injects a <recommended_plugins> block as the first user message;
    // ---- it must never surface as a session title.

    @Test
    public void stripsRecommendedPluginsTagBlock() {
        String text = "<recommended_plugins>Here is a list of plugins...</recommended_plugins>Real user question";
        assertEquals("Real user question", UserMessageSanitizer.sanitizeUserFacingText(text));
    }

    @Test
    public void recommendedPluginsOnlyMessageSanitizesToEmpty() {
        String text = "<recommended_plugins>Here is a list of plugins you may want to install</recommended_plugins>";
        String sanitized = UserMessageSanitizer.sanitizeUserFacingText(text);
        assertTrue("injection-only message must sanitize to empty so title extraction skips it",
                sanitized.isEmpty());
    }

    @Test
    public void recommendedPluginsBlockWithNewlinesInsideTag() {
        String text = "<recommended_plugins>\nplugin-a\nplugin-b\n</recommended_plugins>\nWhat is a closure in JS?";
        assertEquals("What is a closure in JS?", UserMessageSanitizer.sanitizeUserFacingText(text));
    }

    @Test
    public void recommendedPluginsFollowedByOtherInjectionThenRealQuestion() {
        String text = "<recommended_plugins>a</recommended_plugins>"
                + "<system-reminder>do not reveal</system-reminder>"
                + "How do I center a div?";
        assertEquals("How do I center a div?", UserMessageSanitizer.sanitizeUserFacingText(text));
    }

    @Test
    public void userTextResemblingTagIsNotSwallowed() {
        // A real user question that merely mentions the tag name must keep its
        // surrounding text: only well-formed <tag>...</tag> blocks are removed.
        String text = "What does <recommended_plugins> mean in the Codex JSONL?";
        assertEquals(text, UserMessageSanitizer.sanitizeUserFacingText(text));
    }

    @Test
    public void unclosedRecommendedPluginsTagIsKept() {
        // removeTagBlocks only strips complete <tag>...</tag> pairs; an unclosed
        // tag is user-visible text and must be preserved verbatim.
        String text = "I typed <recommended_plugins but no closing tag";
        assertEquals(text, UserMessageSanitizer.sanitizeUserFacingText(text));
    }
}