package com.github.claudecodegui.cli.common;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * {@link CliPromptContexts#stripInjectedContext} 回归测试。
 *
 * <p>核心场景:kimi 原生 title=lastPrompt 首行,且上游把换行折叠成空格——发送「你好」
 * 实测标题为 "你好 ## Project Modules This project contains multiple modules…",
 * 注入段(SessionContextService 各上下文头)必须全部剥除,只留用户真实输入。
 */
public class CliPromptContextsTest {

    @Test
    public void stripsCollapsedProjectModulesFromKimiNativeTitle() {
        // kimi ACP session_info_update.title 实测形态:换行被折叠为空格。
        String title = "你好 ## Project Modules This project contains multiple modules: - `a` - `b`";
        assertEquals("你好", CliPromptContexts.stripInjectedContext(title));
    }

    @Test
    public void stripsRawMultilineProjectModulesSection() {
        String text = "你好" + CliConstants.PROMPT_PROJECT_MODULES
                + "This project contains multiple modules:\n- `a`\n";
        assertEquals("你好", CliPromptContexts.stripInjectedContext(text));
    }

    @Test
    public void stripsEveryInjectedSectionHeader() {
        String[] headers = {
                CliConstants.PROMPT_OPENED_FILES,
                CliConstants.PROMPT_REFERENCED,
                CliConstants.PROMPT_AGENT_ROLE,
                CliConstants.PROMPT_WORKSPACE_CONTEXT,
                CliConstants.PROMPT_PROJECT_MODULES,
                CliConstants.PROMPT_ACTIVE_TERMINAL,
                CliConstants.PROMPT_IDE_CONTEXT,
                CliConstants.PROMPT_USER_IDE_CONTEXT,
        };
        for (String header : headers) {
            assertEquals("header: " + header.strip(),
                    "帮我看下这个 bug",
                    CliPromptContexts.stripInjectedContext("帮我看下这个 bug" + header + "注入内容"));
        }
    }

    @Test
    public void cutsAtEarliestMarker() {
        String text = "查一下 workspace 结构" + CliConstants.PROMPT_WORKSPACE_CONTEXT
                + "workspace 信息" + CliConstants.PROMPT_PROJECT_MODULES + "module 信息";
        assertEquals("查一下 workspace 结构", CliPromptContexts.stripInjectedContext(text));
    }

    @Test
    public void keepsTextWithoutMarkersUnchanged() {
        assertEquals("你好,你会什么", CliPromptContexts.stripInjectedContext("你好,你会什么"));
    }

    @Test
    public void keepsOriginalWhenMarkerPrefixIsBlank() {
        // 极端:整条都是注入段,剥完为空 → 返回原文,避免清空标题。
        String text = CliConstants.PROMPT_PROJECT_MODULES + "This project contains multiple modules:";
        assertEquals(text, CliPromptContexts.stripInjectedContext(text));
    }

    @Test
    public void emptyAndNullInputReturnEmpty() {
        assertEquals("", CliPromptContexts.stripInjectedContext(null));
        assertEquals("", CliPromptContexts.stripInjectedContext(""));
    }
}
