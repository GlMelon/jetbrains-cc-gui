package com.github.claudecodegui.cli.common;

/**
 * CLI 流解析器统一契约,由 {@link AbstractRunOnceCliSession} 持有。
 * <p>
 * 实现者:{@link MarkerCliStreamParser}(marker 协议,omp/dsh 经 channel 模式)、
 * {@code OpenCodeCliStreamParser}(NDJSON 事件流)、
 * {@code GrokCliStreamParser}(streaming-json+marker 合成)、
 * {@code KimiCliStreamParser}/{@code PiCliStreamParser}(各自 JSON 事件方言)。
 * 每次发送构造新实例,持有本次运行的全部可变状态(非线程安全)。
 */
public interface CliStreamParser {

    /** 本次运行捕获到的 session id(从事件流提取),供会话层缓存与续接。 */
    String capturedSessionId();

    /** 累积的 assistant 文本(供会话层 onComplete 的 finalResult)。 */
    String accumulatedText();

    boolean hasError();

    /** 本次运行是否解析到至少一个有效事件。 */
    boolean receivedAnyEvent();

    /** 本次运行是否已收到流结束标记(会话层据此判断是否需补发 stream_end)。 */
    boolean streamEnded();

    String errorDiagnostic();

    /** 逐行解析事件流(marker 标记或 JSON 事件,由实现定义)。 */
    void parseLine(String line);

    /**
     * MCP 连接失败的非阻塞降级提示检测(仅 NDJSON 解析器实现;
     * marker 协议无此路径,默认不匹配)。
     */
    default boolean emitMcpNoticeIfMatched(String text) {
        return false;
    }
}
