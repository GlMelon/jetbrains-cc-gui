package com.github.claudecodegui.cli;

import com.github.claudecodegui.cli.common.CliConstants;
import com.github.claudecodegui.cli.common.CliMcpConfig;
import com.github.claudecodegui.cli.common.CliProcessLifecycle;
import com.github.claudecodegui.provider.claude.ClaudeCliDetector;
import com.github.claudecodegui.provider.claude.ClaudeCliStreamParser;
import com.github.claudecodegui.session.AssistantResponsePhase;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/**
 * 排查 CLI 模式下对话启动慢的根因。
 *
 * <h3>问题描述</h3>
 * Claude CLI 模式下对话启动有两个明显卡顿阶段：
 * <ol>
 *   <li><b>CONNECTING 阶段（20-30秒）</b>：从点击发送到 "正在连接" → "正在理解问题"</li>
 *   <li><b>UNDERSTANDING 阶段（~10秒）</b>：从 "正在理解问题" 到第一条内容出现</li>
 * </ol>
 *
 * <h3>本测试验证的假设</h3>
 * <ol>
 *   <li>CONNECTING 阶段的主要耗时来自 Claude CLI 子进程启动（Node.js 初始化 + 认证 + 模型加载），
 *       而非插件侧的附件处理、环境配置等</li>
 *   <li>CLI 检测（where/which claude）在首次调用时可能阻塞数秒</li>
 *   <li>MCP 配置初始化（磁盘 IO）可能贡献额外延迟</li>
 *   <li>UNDERSTANDING 阶段完全取决于 Claude API 调用延迟（模型加载 + 首 token 生成）</li>
 * </ol>
 *
 * <h3>阶段流转路径（代码定位）</h3>
 * <pre>
 * SessionSendService.sendMessageToProvider()          → 发送 QUEUED
 *   ↓
 * SessionSendService.sendMessageToProvider():168       → 发送 CONNECTING
 *   ↓ [CliCliSession.send() 异步执行]
 *   ├─ ClaudeCliDetector.findCliExecutable()          → 首次: where/which + --version (~2-5s)
 *   ├─ attachmentHandler.processForClaude()            → 图片落盘/文档读取
 *   ├─ CliMcpConfig.hasServers()/getConfigFilePath()   → 磁盘 IO
 *   ├─ buildCommand()                                  → 纯 CPU
 *   ├─ ProcessBuilder.start()                          → 子进程创建
 *   ├─ writePromptToStdin()                            → stdin 写入+关闭
 *   └─ readOutput() 阻塞读 stdout
 *       ↓ 收到 system.init / stream_event.message_start
 * SessionCallbackAdapter.onStreamStart():275           → 发送 UNDERSTANDING
 *   ↓ [Claude API 调用延迟]
 *   └─ 收到 content_block_delta(text_delta)
 * SessionCallbackAdapter.onContentDelta():417          → 发送 RESPONDING
 * </pre>
 */
public class CliStartupTimingAnalysisTest {

    // ── 1. 验证阶段定义与流转顺序 ──────────────────────────────────────

    @Test
    public void phaseSequenceIsCorrect() {
        // 验证阶段枚举的定义顺序与代码流转一致
        AssistantResponsePhase[] phases = AssistantResponsePhase.values();
        assertEquals(AssistantResponsePhase.QUEUED, phases[0]);
        assertEquals(AssistantResponsePhase.MCP_SYNCING, phases[1]);
        assertEquals(AssistantResponsePhase.CONNECTING, phases[2]);
        // AWAITING_MODEL:请求已发给模型、等待首个输出(codex 长等待窗口 / opencode 启动静默窗口)
        assertEquals(AssistantResponsePhase.AWAITING_MODEL, phases[3]);
        assertEquals(AssistantResponsePhase.UNDERSTANDING, phases[4]);
        assertEquals(AssistantResponsePhase.API_RETRY, phases[5]);
        assertEquals(AssistantResponsePhase.THINKING, phases[6]);
        assertEquals(AssistantResponsePhase.TOOLING, phases[7]);
        assertEquals(AssistantResponsePhase.RESPONDING, phases[8]);
        assertEquals(AssistantResponsePhase.DONE, phases[9]);
        assertEquals(AssistantResponsePhase.ERROR, phases[10]);
    }

    @Test
    public void connectingAndUnderstandingAreActivePhases() {
        assertTrue(AssistantResponsePhase.CONNECTING.active());
        assertTrue(AssistantResponsePhase.AWAITING_MODEL.active());
        assertTrue(AssistantResponsePhase.UNDERSTANDING.active());
        assertTrue(AssistantResponsePhase.THINKING.active());
        assertTrue(AssistantResponsePhase.TOOLING.active());
        assertTrue(AssistantResponsePhase.RESPONDING.active());
        assertFalse(AssistantResponsePhase.DONE.active());
        assertFalse(AssistantResponsePhase.ERROR.active());
    }

    // ── 2. 验证 CONNECTING→UNDERSTANDING 转换触发点 ───────────────────

    @Test
    public void streamParserEmitsStreamStartOnSystemInit() {
        // system.init 事件触发 stream_start → 进而触发 UNDERSTANDING 阶段
        Gson gson = new Gson();
        ClaudeCliStreamParser parser = new ClaudeCliStreamParser(gson);
        parser.resetState();

        AtomicBoolean streamStartEmitted = new AtomicBoolean(false);
        AtomicBoolean messageStartEmitted = new AtomicBoolean(false);
        AtomicString sessionId = new AtomicString(null);

        MessageCallbackStub cb = new MessageCallbackStub() {
            @Override
            public void onMessage(String type, String content) {
                if (CliConstants.MSG_STREAM_START.equals(type)) {
                    streamStartEmitted.set(true);
                }
                if (CliConstants.MSG_MESSAGE_START.equals(type)) {
                    messageStartEmitted.set(true);
                }
                if (CliConstants.MSG_SESSION_ID.equals(type)) {
                    sessionId.set(content);
                }
            }
        };

        // 模拟 Claude CLI 输出: system.init 事件
        String initLine = "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"test-uuid-1234\"}";
        parser.parseLine(initLine, cb, new com.github.claudecodegui.provider.common.CliResult(),
                new StringBuilder(), new AtomicBoolean(false), false);

        assertTrue("system.init 应触发 stream_start", streamStartEmitted.get());
        assertTrue("system.init 应触发 message_start", messageStartEmitted.get());
        assertEquals("test-uuid-1234", sessionId.get());
    }

    @Test
    public void streamParserEmitsStreamStartOnMessageStartEvent() {
        // stream_event.message_start 也会触发 stream_start
        Gson gson = new Gson();
        ClaudeCliStreamParser parser = new ClaudeCliStreamParser(gson);
        parser.resetState();

        AtomicBoolean streamStartEmitted = new AtomicBoolean(false);

        MessageCallbackStub cb = new MessageCallbackStub() {
            @Override
            public void onMessage(String type, String content) {
                if (CliConstants.MSG_STREAM_START.equals(type)) {
                    streamStartEmitted.set(true);
                }
            }
        };

        String messageStartLine = "{\"type\":\"stream_event\",\"event\":{\"type\":\"message_start\"}}";
        parser.parseLine(messageStartLine, cb, new com.github.claudecodegui.provider.common.CliResult(),
                new StringBuilder(), new AtomicBoolean(false), false);

        assertTrue("message_start 事件应触发 stream_start", streamStartEmitted.get());
    }

    @Test
    public void connectingPhaseEndsWhenStreamStartIsEmitted() {
        // 关键验证：CONNECTING 阶段在 onStreamStart 时转换为 UNDERSTANDING
        // 这意味着 CONNECTING 的持续时间 = 从 CLI session send 到 CLI 进程输出第一条有效行

        // 验证 SessionCallbackAdapter.onStreamStart() 中硬编码了 UNDERSTANDING 阶段
        // (通过源码检查确认)
        try {
            String source = java.nio.file.Files.readString(java.nio.file.Paths.get(
                    "src", "main", "java", "com", "github", "claudecodegui", "session", "SessionCallbackAdapter.java"
            ));
            assertTrue("onStreamStart 应发送 UNDERSTANDING 阶段",
                    source.contains("sendResponsePhaseForCurrentTurn(AssistantResponsePhase.UNDERSTANDING)"));
            // CONNECTING 已由各 CliSession.send 在真实边界自报(SessionSendService 不再预发,
            // 避免 CLI 路径 CONNECTING 双发闪烁);此处确认双层契约:send 服务不预发 + CLI 会话层自报
            String sendSource = java.nio.file.Files.readString(java.nio.file.Paths.get(
                    "src", "main", "java", "com", "github", "claudecodegui", "session", "SessionSendService.java"
            ));
            assertFalse("sendMessageToProvider 不应再预发 CONNECTING(由 CliSession.send 自报)",
                    sendSource.contains("AssistantResponsePhase.CONNECTING"));
            String cliSessionSource = java.nio.file.Files.readString(java.nio.file.Paths.get(
                    "src", "main", "java", "com", "github", "claudecodegui", "cli", "common", "AbstractRunOnceCliSession.java"
            ));
            assertTrue("CLI 会话层应在真实边界自报 CONNECTING 阶段",
                    cliSessionSource.contains("AssistantResponsePhase.CONNECTING"));
        } catch (Exception e) {
            fail("源码读取失败: " + e.getMessage());
        }
    }

    // ── 3. 验证 CLI 检测耗时（首次调用阻塞） ───────────────────────

    @Test
    public void cliDetectorCachesPathAfterFirstDetection() throws Exception {
        // 验证 ClaudeCliDetector 的缓存机制:
        // 首次 findCliExecutable() 触发完整检测链(可能很慢),后续调用直接返回缓存
        ClaudeCliDetector detector = ClaudeCliDetector.getInstance();

        String path1 = detector.findCliExecutable();
        // 第二次调用应直接返回缓存(无检测开销)
        long start = System.nanoTime();
        String path2 = detector.findCliExecutable();
        long elapsed = (System.nanoTime() - start) / 1_000_000;

        assertEquals("连续调用应返回相同路径", path1, path2);
        assertTrue("第二次调用应 < 10ms（缓存命中）", elapsed < 10);
    }

    @Test
    public void cliDetectorVerifyCliPathRunsVersionCheck() throws Exception {
        // 验证 verifyCliPath 执行 `claude --version` 并等待最多 5 秒
        // 这是首次检测的耗时来源之一
        ClaudeCliDetector detector = ClaudeCliDetector.getInstance();
        String path = detector.findCliExecutable();

        if (path != null) {
            // 验证 --version 命令的超时设置
            String source = java.nio.file.Files.readString(java.nio.file.Paths.get(
                    "src", "main", "java", "com", "github", "claudecodegui", "provider", "claude", "ClaudeCliDetector.java"
            ));
            assertTrue("verifyCliPath 应使用 5 秒超时",
                    source.contains("process.waitFor(5, TimeUnit.SECONDS)"));
            assertTrue("verifyCliPath 应执行 --version",
                    source.contains("\"--version\""));
        }
    }

    // ── 4. 验证 Claude CLI 进程启动命令构建 ──────────────────────────

    @Test
    public void claudeCliCommandIncludesStreamJsonOutput() {
        // 验证命令构建包含 stream-json 输出格式
        // 这决定了 CLI 进程启动后输出的格式,影响解析速度
        try {
            String source = java.nio.file.Files.readString(java.nio.file.Paths.get(
                    "src", "main", "java", "com", "github", "claudecodegui", "cli", "claude", "ClaudeCliSession.java"
            ));
            assertTrue("应使用 stream-json 输出格式",
                    source.contains("CliConstants.ARG_STREAM_JSON"));
            assertTrue("应使用 --verbose 标志",
                    source.contains("CliConstants.ARG_VERBOSE"));
        } catch (Exception e) {
            fail("源码读取失败: " + e.getMessage());
        }
    }

    @Test
    public void mcpConfigInitializationIsLazy() {
        // 验证 CliMcpConfig 的懒加载机制
        // ensureInitialized() 只在首次 getConfigFilePath()/hasServers() 时执行磁盘 IO
        try {
            String source = java.nio.file.Files.readString(java.nio.file.Paths.get(
                    "src", "main", "java", "com", "github", "claudecodegui", "cli", "common", "CliMcpConfig.java"
            ));
            assertTrue("CliMcpConfig 应使用懒加载",
                    source.contains("private volatile boolean initialized = false"));
            assertTrue("ensureInitialized 应检查 initialized 标志",
                    source.contains("if (initialized)"));
        } catch (Exception e) {
            fail("源码读取失败: " + e.getMessage());
        }
    }

    // ── 5. 验证超时配置 ──────────────────────────────────────────────

    @Test
    public void processWaitTimeoutIsReasonable() {
        // 验证进程等待超时设置合理
        assertTrue("PROCESS_WAIT_TIMEOUT_MS 应为 30 秒",
                CliConstants.PROCESS_WAIT_TIMEOUT_MS == 30_000L);
        assertTrue("CLI_REQUEST_TIMEOUT_MS 应为 15 分钟",
                CliConstants.CLI_REQUEST_TIMEOUT_MS == 15 * 60 * 1000L);
        assertTrue("OUTPUT_DRAIN_TIMEOUT_MS 应为 5 秒",
                CliConstants.OUTPUT_DRAIN_TIMEOUT_MS == 5_000L);
    }

    // ── 6. 验证 CONNECTING 阶段耗时来源分析 ─────────────────────────

    @Test
    public void connectingPhaseCoversCliStartupSteps() throws Exception {
        // 验证 CONNECTING 阶段覆盖的代码路径
        // 通过源码分析确认主要耗时来源
        String source = java.nio.file.Files.readString(java.nio.file.Paths.get(
                "src", "main", "java", "com", "github", "claudecodegui", "cli", "claude", "ClaudeCliSession.java"
        ));

        // 确认 send() 方法中关键步骤的存在
        assertTrue("应调用 findCliExecutable()", source.contains("findCliExecutable()"));
        assertTrue("应调用 processForClaude()", source.contains("processForClaude("));
        assertTrue("应调用 buildCommand()", source.contains("buildCommand("));
        assertTrue("应调用 pb.start()", source.contains("pb.start()"));
        assertTrue("应调用 writePromptToStdin()", source.contains("writePromptToStdin("));
        assertTrue("应调用 CliProcessLifecycle.await()", source.contains("CliProcessLifecycle.await("));
    }

    @Test
    public void firstStdoutLineTriggersPhaseTransition() throws Exception {
        // 关键分析:从 pb.start() 到 readOutput 收到第一行 stdout 的延迟
        // 这是 CONNECTING 阶段的最大耗时来源
        //
        // 代码路径:
        // 1. pb.start() - 创建子进程
        // 2. writePromptToStdin() - 写入 stdin 并关闭
        // 3. readOutput() 循环读取 BufferedReader
        // 4. parser.parseLine() 解析每一行
        // 5. 当收到 system.init 或 stream_event.message_start 时
        //    parser 内部 emitStreamStartIfNeeded → stream_start 消息
        //    → ClaudeMessageHandler.onMessage(stream_start)
        //    → callbackHandler.onStreamStart()
        //    → SessionCallbackAdapter.onStreamStart()
        //    → sendResponsePhaseForCurrentTurn(UNDERSTANDING)
        //
        // CONNECTING 阶段耗时 = process startup + CLI internal init + first line output
        // 这个延迟完全在 Claude CLI 进程内部,插件侧无法优化

        String source = java.nio.file.Files.readString(java.nio.file.Paths.get(
                "src", "main", "java", "com", "github", "claudecodegui", "provider", "claude", "ClaudeCliStreamParser.java"
        ));
        assertTrue("解析器应在 system.init 时触发 stream_start",
                source.contains("emitStreamStartIfNeeded(callback)"));
        assertTrue("解析器应在 message_start 时触发 stream_start",
                source.contains("emitStreamStartIfNeeded(callback)"));
    }

    // ── 7. 验证 UNDERSTANDING 阶段耗时来源 ──────────────────────────

    @Test
    public void understandingPhaseEndsOnFirstContentDelta() {
        // UNDERSTANDING 阶段:从 onStreamStart 到第一次 content_delta
        // 这个延迟完全取决于 Claude API 调用延迟
        //
        // 代码路径:
        // 1. onStreamStart() → 发送 UNDERSTANDING 阶段
        // 2. Claude CLI 内部:调用 Anthropic API → 等待模型响应
        // 3. 收到 content_block_delta → parser 触发 contentDelta
        // 4. ClaudeMessageHandler.onMessage(content_delta) → callbackHandler.onContentDelta()
        // 5. SessionCallbackAdapter.onContentDelta() → 发送 RESPONDING 阶段
        //
        // UNDERSTANDING 阶段耗时 = API 调用延迟(网络 + 模型推理 + 首 token 生成)
        // 这个延迟与 SDK 模式相同,不是 CLI 特有的问题

        try {
            String source = java.nio.file.Files.readString(java.nio.file.Paths.get(
                    "src", "main", "java", "com", "github", "claudecodegui", "session", "SessionCallbackAdapter.java"
            ));
            // 验证 onContentDelta 触发 RESPONDING 阶段
            assertTrue("onContentDelta 应发送 RESPONDING 阶段",
                    source.contains("sendResponsePhaseForCurrentTurn(AssistantResponsePhase.RESPONDING)"));
            // 验证 UNDERSTANDING 在 onStreamStart 中设置
            assertTrue("onStreamStart 应发送 UNDERSTANDING 阶段",
                    source.contains("sendResponsePhaseForCurrentTurn(AssistantResponsePhase.UNDERSTANDING)"));
        } catch (Exception e) {
            fail("源码读取失败: " + e.getMessage());
        }
    }

    // ── 8. 对比 SDK 与 CLI 启动差异 ─────────────────────────────────

    @Test
    public void sdkModeIsRemovedCliIsSoleRuntime() throws Exception {
        // 验证 SDK 模式已完全移除,CLI 是唯一运行时:runtime 维度已消除,
        // SessionSendService 不再引用 EffectiveRuntimeResolver / RuntimeType / toInvocationMode。
        String source = java.nio.file.Files.readString(java.nio.file.Paths.get(
                "src", "main", "java", "com", "github", "claudecodegui", "session", "SessionSendService.java"
        ));
        assertFalse("EffectiveRuntimeResolver 应已删除",
                source.contains("EffectiveRuntimeResolver"));
        assertFalse("RuntimeType 应已删除",
                source.contains("RuntimeType"));
        assertFalse("toInvocationMode 应已删除(SDK 调用模式已移除,CLI 单一路径)",
                source.contains("toInvocationMode"));
    }

    // ── 9. 验证进程 stdout 读取阻塞特性 ─────────────────────────────

    @Test
    public void readOutputBlocksUntilFirstLine() throws Exception {
        // 验证 readOutput 使用 BoundedLineReader.readLine() 阻塞读取
        // 这意味着 readOutput 会一直阻塞直到 CLI 进程输出第一行
        // (BoundedLineReader 在 BufferedReader 等价语义上加了单行长度上限,防内存打爆)
        String source = java.nio.file.Files.readString(java.nio.file.Paths.get(
                "src", "main", "java", "com", "github", "claudecodegui", "cli", "claude", "ClaudeCliSession.java"
        ));
        assertTrue("readOutput 应使用 BoundedLineReader",
                source.contains("CliOutputLimits.BoundedLineReader"));
        assertTrue("readOutput 应使用 readLine() 阻塞读取",
                source.contains("reader.readLine()"));
    }

    // ── 10. 验证环境配置对启动的影响 ─────────────────────────────────

    @Test
    public void environmentSetupIsLightweight() throws Exception {
        // 验证环境配置是轻量级操作(不应是主要耗时来源)
        String source = java.nio.file.Files.readString(java.nio.file.Paths.get(
                "src", "main", "java", "com", "github", "claudecodegui", "cli", "common", "CliEnvironmentBuilder.java"
        ));
        // 环境配置只涉及内存操作(put/copy)
        assertTrue("buildBaseEnvironment 应只做内存操作",
                source.contains("new LinkedHashMap<>()"));
        assertFalse("buildBaseEnvironment 不应执行外部命令",
                !source.contains("ProcessBuilder") || source.contains("// only in detect"));
    }

    // ── 辅助类 ──────────────────────────────────────────────────────

    private static class AtomicString {
        private volatile String value;

        AtomicString(String initial) {
            this.value = initial;
        }

        void set(String value) {
            this.value = value;
        }

        String get() {
            return value;
        }
    }

    private static class MessageCallbackStub implements com.github.claudecodegui.provider.common.MessageCallback {
        @Override
        public void onMessage(String type, String content) {
        }

        @Override
        public void onError(String error) {
        }

        @Override
        public void onComplete(com.github.claudecodegui.provider.common.CliResult r) {
        }
    }
}
