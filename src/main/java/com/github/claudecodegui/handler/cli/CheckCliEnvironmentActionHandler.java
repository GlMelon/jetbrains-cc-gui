package com.github.claudecodegui.handler.cli;

import com.github.claudecodegui.cli.CliEnvironmentChecker;
import com.github.claudecodegui.cli.CliEnvironmentStatus;
import com.github.claudecodegui.handler.core.FrontendActionContext;
import com.github.claudecodegui.handler.core.FrontendActionHandler;
import com.github.claudecodegui.protocol.DownstreamEvent;
import com.github.claudecodegui.protocol.UpstreamAction;
import com.github.claudecodegui.util.GsonHolder;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 处理前端CLI环境检查请求。
 * 检测本地安装的AI CLI工具状态，包括版本信息和安装路径。
 */
public class CheckCliEnvironmentActionHandler implements FrontendActionHandler<JsonObject> {

    private static final Logger LOG = Logger.getInstance(CheckCliEnvironmentActionHandler.class);

    @Override
    public UpstreamAction action() {
        return UpstreamAction.CHECK_CLI_ENVIRONMENT;
    }

    @Override
    public Class<JsonObject> payloadType() {
        return JsonObject.class;
    }

    @Override
    public void handle(JsonObject payload, FrontendActionContext context) {
        // force=true:用户手动"刷新检测/重新检测",绕过后端缓存强制全量重检;
        // 默认 false:命中 TTL 缓存直接返回(启动时常驻 UI 的首次拉取靠它秒回)。
        boolean force = payload != null && payload.has("force") && payload.get("force").getAsBoolean();

        LOG.info("[CliEnvironment] Handling CHECK_CLI_ENVIRONMENT request (force=" + force + ")");

        CompletableFuture.runAsync(() -> {
            try {
                List<CliEnvironmentStatus> statuses = CliEnvironmentChecker.getStatusesCached(force);

                // 以 CLI 工具名(如 claude/codex/opencode)为 key 的扁平 map,
                // 匹配前端 Record<string, CliEnvironmentStatus> 契约 —— 前端按 cliStatus[tool.id] 取值。
                // lastChecked 时间戳由前端在收到事件时自行生成,无需后端回传。
                JsonObject response = new JsonObject();
                for (CliEnvironmentStatus status : statuses) {
                    response.add(status.getName(), GsonHolder.GSON.toJsonTree(status));
                }

                // payload 必须经 escapeJs:callJavaScript 把参数原样拼进单引号 JS 字符串字面量(不做转义),
                // installPath 等含 Windows 反斜杠的字段经 JSON 转义为 \\,若不再 escapeJs,JS 解析会把
                // \\ 退化为 \,使前端 JSON.parse 遇到 \U 等非法转义抛错 → 卡片空白。与 AGENT_LIST 等
                // 兄弟 handler 的 escapeJs 约定对齐。
                context.handlerContext().dispatchEvent(
                    DownstreamEvent.CLI_ENVIRONMENT_STATUS.value(),
                    context.handlerContext().escapeJs(GsonHolder.GSON.toJson(response))
                );

                LOG.info("[CliEnvironment] Environment check completed, found " + statuses.size() + " tools");
            } catch (Exception e) {
                LOG.error("[CliEnvironment] Failed to check CLI environment", e);

                JsonObject errorResponse = new JsonObject();
                errorResponse.addProperty("error", e.getMessage());
                errorResponse.addProperty("timestamp", System.currentTimeMillis());

                context.handlerContext().dispatchEvent(
                    DownstreamEvent.CLI_ENVIRONMENT_STATUS.value(),
                    context.handlerContext().escapeJs(GsonHolder.GSON.toJson(errorResponse))
                );
            }
        }, AppExecutorUtil.getAppExecutorService());
    }
}
