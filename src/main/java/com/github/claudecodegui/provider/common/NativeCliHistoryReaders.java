package com.github.claudecodegui.provider.common;

import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.provider.grok.GrokHistoryReader;
import com.github.claudecodegui.provider.kimi.KimiHistoryReader;
import com.github.claudecodegui.provider.pi.PiHistoryReader;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.List;

/**
 * 纯 CLI provider(grok / kimi / pi)「sessionId + cwd → 前端格式消息」的定位接线单点。
 *
 * <p>此前该接线只存在于 {@code SessionProviderRouter} 的装配 lambda;磁盘历史分页接入后
 * 翻页 handler({@code LoadCodexHistoryPageActionHandler})需要同一份 reader,抽到本类共用,
 * 避免两处复制后漂移(如 pi 的 findSessionFile / grok·kimi 的 findSessionDir 差异)。
 */
public final class NativeCliHistoryReaders {

    /** 前端格式历史消息读取器:(sessionId, cwd) → messages(形状与 CliOnlyProviderAdapter 的 loader 一致)。 */
    public interface FrontendMessageReader {
        List<JsonObject> read(String sessionId, String cwd);
    }

    private NativeCliHistoryReaders() {
    }

    /** 按 provider id 取 reader(grok/kimi/pi 之外的 provider 抛错——白名单在调用方先行校验)。 */
    public static FrontendMessageReader forProvider(String provider) {
        if (CommonConstants.PROVIDER_GROK.equals(provider)) {
            return grok();
        }
        if (CommonConstants.PROVIDER_KIMI.equals(provider)) {
            return kimi();
        }
        if (CommonConstants.PROVIDER_PI.equals(provider)) {
            return pi();
        }
        throw new IllegalArgumentException("No native CLI history reader for provider: " + provider);
    }

    public static FrontendMessageReader grok() {
        GrokHistoryReader reader = new GrokHistoryReader();
        return (sessionId, cwd) -> {
            Path dir = reader.findSessionDir(sessionId, cwd);
            return dir == null ? List.of() : reader.loadMessages(dir);
        };
    }

    public static FrontendMessageReader kimi() {
        KimiHistoryReader reader = new KimiHistoryReader();
        return (sessionId, cwd) -> {
            Path dir = reader.findSessionDir(sessionId, cwd);
            return dir == null ? List.of() : reader.loadMessages(dir);
        };
    }

    public static FrontendMessageReader pi() {
        PiHistoryReader reader = new PiHistoryReader();
        return (sessionId, cwd) -> {
            Path file = reader.findSessionFile(sessionId, cwd);
            return file == null ? List.of() : reader.loadMessages(file);
        };
    }
}
