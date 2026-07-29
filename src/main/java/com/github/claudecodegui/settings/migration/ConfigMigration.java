package com.github.claudecodegui.settings.migration;

import com.google.gson.JsonObject;

import java.io.IOException;

/** 单个逐级配置迁移；每个实现只负责 {@code sourceVersion -> sourceVersion + 1}。 */
public interface ConfigMigration {

    int sourceVersion();

    default int targetVersion() {
        return sourceVersion() + 1;
    }

    /**
     * 幂等迁移配置。
     *
     * @return {@code true} 表示该级迁移完成；{@code false} 表示依赖暂不可用，应保留当前版本稍后重试
     */
    boolean migrate(JsonObject config) throws IOException;
}
