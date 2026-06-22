package com.github.claudecodegui.handler.enhance;

import com.github.claudecodegui.protocol.UpstreamAction;
import org.junit.Test;

import static org.junit.Assert.*;

public class EnhancePromptActionHandlerTest {

    private final EnhancePromptActionHandler handler = new EnhancePromptActionHandler();

    @Test
    public void actionIsEnhancePrompt() {
        assertEquals(UpstreamAction.ENHANCE_PROMPT, handler.action());
    }

    @Test
    public void payloadTypeIsString() {
        assertEquals(String.class, handler.payloadType());
    }

    /**
     * 回归锚点：确保 action 字符串与旧 handler 的 getSupportedTypes 返回值一致。
     * 迁移后旧 handler 将被删除，此测试保证前端发送的 "enhance_prompt" 不会断。
     */
    @Test
    public void actionValueMatchesLegacyStringType() {
        assertEquals("enhance_prompt", handler.action().value());
    }

    // ── getLanguageFromExtension 纯函数测试 ──

    @Test
    public void knownExtensionsMapToCorrectLanguage() {
        assertEquals("java", EnhancePromptActionHandler.getLanguageFromExtension("java"));
        assertEquals("kotlin", EnhancePromptActionHandler.getLanguageFromExtension("kt"));
        assertEquals("typescript", EnhancePromptActionHandler.getLanguageFromExtension("ts"));
        assertEquals("python", EnhancePromptActionHandler.getLanguageFromExtension("py"));
        assertEquals("rust", EnhancePromptActionHandler.getLanguageFromExtension("rs"));
        assertEquals("go", EnhancePromptActionHandler.getLanguageFromExtension("go"));
    }

    @Test
    public void unknownExtensionFallsBackToText() {
        assertEquals("text", EnhancePromptActionHandler.getLanguageFromExtension("xyz"));
        assertEquals("text", EnhancePromptActionHandler.getLanguageFromExtension("unknown"));
    }

    @Test
    public void nullExtensionReturnsText() {
        assertEquals("text", EnhancePromptActionHandler.getLanguageFromExtension(null));
    }

    @Test
    public void extensionIsCaseInsensitive() {
        assertEquals("java", EnhancePromptActionHandler.getLanguageFromExtension("JAVA"));
        assertEquals("python", EnhancePromptActionHandler.getLanguageFromExtension("PY"));
    }
}
