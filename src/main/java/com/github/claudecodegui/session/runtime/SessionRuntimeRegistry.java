package com.github.claudecodegui.session.runtime;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 手动注册表，替代 Spring List&lt;Strategy&gt; 自动注入。
 * <p>
 * 内部使用 Map&lt;ProviderType, SessionRuntime&gt; 查表(runtime 维度已消除,provider 单维路由)。
 * 在 Router 构造函数中 new 实现并 register()。
 * 加新 provider 只需新增一个实现类 + 一行注册，路由代码不变。
 */
public class SessionRuntimeRegistry {

    private final Map<ProviderType, SessionRuntime> runtimes = new ConcurrentHashMap<>();

    /**
     * 注册一个 runtime 实现（按 {@link SessionRuntime#provider()} 作键）。
     * 重复注册抛异常（fail-fast，开发期暴露装配错误）。
     */
    public void register(SessionRuntime r) {
        if (runtimes.putIfAbsent(r.provider(), r) != null) {
            throw new IllegalStateException(
                    "Duplicate runtime registered: " + r.provider());
        }
    }

    /**
     * 按 provider 解析对应的 runtime 实现。
     * 未知键抛异常（fail-fast）。
     */
    public SessionRuntime resolve(ProviderType provider) {
        SessionRuntime rt = runtimes.get(provider);
        if (rt == null) {
            throw new IllegalStateException(
                    "No runtime registered for " + provider);
        }
        return rt;
    }

    /**
     * 返回所有已注册的 runtime 实现。
     */
    public Collection<SessionRuntime> all() {
        return runtimes.values();
    }
}
