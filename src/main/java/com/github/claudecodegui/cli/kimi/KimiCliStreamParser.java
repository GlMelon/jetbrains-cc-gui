package com.github.claudecodegui.cli.kimi;

import com.github.claudecodegui.cli.CliSessionCallback;
import com.github.claudecodegui.cli.common.MarkerCliStreamParser;

/**
 * Kimi CLI 流解析器(委托实现)。
 * <p>
 * Kimi CLI 使用 marker 协议(对称 Kimi/Pi),委托 {@link MarkerCliStreamParser} 完成全部解析。
 * 每次发送(含 B13 失效重试)构造新实例,持有本次运行的全部可变状态。
 */
public class KimiCliStreamParser {

    private final MarkerCliStreamParser delegate;

    public KimiCliStreamParser(CliSessionCallback callback) {
        this.delegate = new MarkerCliStreamParser(callback);
    }

    /** 本次运行捕获到的 session id(从 [SESSION_ID] 标记提取),供会话层缓存与续接。 */
    public String capturedSessionId() {
        return delegate.capturedSessionId();
    }

    /** 累积的 assistant 文本(供会话层 onComplete 的 finalResult)。 */
    public String accumulatedText() {
        return delegate.accumulatedText();
    }

    public boolean hasError() {
        return delegate.hasError();
    }

    /** 本次运行是否解析到至少一个有效 marker 事件。 */
    public boolean receivedAnyEvent() {
        return delegate.receivedAnyEvent();
    }

    /** 本次运行是否已收到 [STREAM_END](会话层据此判断是否需补发 stream_end)。 */
    public boolean streamEnded() {
        return delegate.streamEnded();
    }

    public String errorDiagnostic() {
        return delegate.errorDiagnostic();
    }

    /** 逐行解析 marker 协议。每行应以 [TAG] 开头,可能携带空格分隔的 JSON 参数。 */
    public void parseLine(String line) {
        delegate.parseLine(line);
    }
}
