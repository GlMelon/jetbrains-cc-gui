package com.github.claudecodegui.cli.common;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * grok/kimi/pi 直 spawn 方言会话对称性源码字符串检查(Platform 耦合无法纯单测的兜底,
 * 对称 {@code CliMcpGatewaySymmetryTest} 范式):
 * 三个方言会话必须都覆写 {@code buildRunCommand}(原生参数布局)与
 * {@code dispatchLine}(NDJSON 行分流 + MCP 降级提示 + diagnostic),
 * 并绑定各自方言解析器——防止任一 provider 静默回退 opencode 默认布局。
 */
public class CliDialectSessionSymmetryTest {

    private static final List<String[]> DIALECT_SESSIONS = List.of(
            new String[]{"grok/GrokRunOnceCliSession.java",
                    "GROK_FORMAT_STREAMING_JSON", "GrokCliStreamParser"},
            new String[]{"kimi/KimiRunOnceCliSession.java",
                    "KIMI_FORMAT_STREAM_JSON", "KimiCliStreamParser"},
            new String[]{"pi/PiRunOnceCliSession.java",
                    "PI_FORMAT_JSON", "PiCliStreamParser"}
    );

    private static String readSource(String relative) throws IOException {
        Path path = Path.of("src/main/java/com/github/claudecodegui/cli", relative);
        assertTrue("源文件必须存在: " + path.toAbsolutePath(), Files.isRegularFile(path));
        return Files.readString(path);
    }

    @Test
    public void everyDialectOverridesBuildRunCommandAndDispatchLine() throws IOException {
        for (String[] entry : DIALECT_SESSIONS) {
            String source = readSource(entry[0]);
            assertTrue(entry[0] + " 必须覆写 buildRunCommand(原生 CLI 参数布局)",
                    source.contains("@Override") && source.contains("public List<String> buildRunCommand"));
            assertTrue(entry[0] + " 必须覆写 dispatchLine(NDJSON 分流 + MCP 降级提示)",
                    source.contains("protected void dispatchLine")
                            && source.contains("emitMcpNoticeIfMatched"));
            assertTrue(entry[0] + " 必须绑定自有方言解析器(" + entry[2] + ")",
                    source.contains("createParser") && source.contains(entry[2]));
            assertTrue(entry[0] + " 必须引用自家输出格式常量(" + entry[1] + ")",
                    source.contains(entry[1]));
        }
    }

    @Test
    public void baseClassExposesAuxiliaryHooksForDialects() throws IOException {
        String base = readSource("common/AbstractRunOnceCliSession.java");
        assertTrue("基类必须有 onStartAuxiliary 钩子(grok 工具尾随依赖)",
                base.contains("protected void onStartAuxiliary(Process process, CliStreamParser parser)"));
        assertTrue("基类必须在 await 后调用 onStopAuxiliary(最终 drain 先于结果判定)",
                base.contains("onStopAuxiliary();"));
        assertTrue("buildPromptText 必须开放给方言子类复用",
                base.contains("protected String buildPromptText(CliSendRequest request)"));
        // 历史点击续接:send 必须优先消费 request.sessionId()(仅看实例字段会在
        // 历史回load/插件重启后静默新开会话,2026-08-29 审计缺口)
        assertTrue("基类 send 必须优先消费 request.sessionId()(历史续接链)",
                base.contains("request.sessionId()"));
        assertTrue("safePromptArg 必须开放给方言子类复用",
                base.contains("protected static String safePromptArg(String text)"));
        assertTrue("resolver() 必须开放给方言子类复用",
                base.contains("protected ProviderCliResolver resolver()"));
    }

    @Test
    public void markerRunOnceCliSessionMustStayRetired() {
        assertTrue("MarkerRunOnceCliSession 已退役:直 spawn 方言各自绑定解析器,"
                        + "marker 协议仅剩 omp/dsh channel 路径(MarkerCliStreamParser)。",
                !Files.isRegularFile(Path.of(
                        "src/main/java/com/github/claudecodegui/cli/common/MarkerRunOnceCliSession.java")));
    }
}
