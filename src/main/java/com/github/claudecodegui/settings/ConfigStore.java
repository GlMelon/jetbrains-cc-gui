package com.github.claudecodegui.settings;

import com.google.gson.JsonObject;

import java.io.IOException;

/**
 * 插件自有 {@code config.json} 的应用层访问抽象。
 *
 * <p>领域 Service 只依赖本接口，不反向依赖 {@link CodemossSettingsService} Facade。
 * {@link #update(ConfigMutation)} 将完整 read-modify-write 放入同一个进程内临界区，
 * 避免多个领域线程读取同一旧快照后互相覆盖。</p>
 */
public interface ConfigStore {

    /** 读取完整配置；文件不存在时返回后端权威默认配置。 */
    JsonObject read() throws IOException;

    /** 保存完整配置；兼容旧 Facade 调用面，优先使用 {@link #update(ConfigMutation)}。 */
    void write(JsonObject config) throws IOException;

    /** 在单一进程内写锁中执行完整 read-modify-write。 */
    void update(ConfigMutation mutation) throws IOException;

    /** 可抛受检 IO 异常的配置修改函数。 */
    @FunctionalInterface
    interface ConfigMutation {
        void apply(JsonObject config) throws IOException;
    }
}
