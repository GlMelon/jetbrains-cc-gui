package com.github.claudecodegui.session.runtime;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 手动注册表，替代 Spring List&lt;Strategy&gt; 自动注入。
 * <p>
 * 内部使用 Map&lt;(ProviderType, RuntimeType), SessionRuntime&gt; 查表。
 * 在 Router 构造函数中 new 4 个实现并 register()。
 * 加新 provider/runtime 只需新增一个实现类 + 一行注册，路由代码不变。
 */
public class SessionRuntimeRegistry {

    private record Key(ProviderType provider, RuntimeType runtime) {}

    private final Map<Key, SessionRuntime> runtimes = new ConcurrentHashMap<>();

    /**
     * 注册一个 runtime 实现。重复注册抛异常（fail-fast，开发期暴露装配错误）。
     */
    public void register(SessionRuntime r) {
        Key key = new Key(r.provider(), r.runtimeType());
        if (runtimes.putIfAbsent(key, r) != null) {
            throw new IllegalStateException(
                    "Duplicate runtime registered: " + r.provider() + "/" + r.runtimeType());
        }
    }

    /**
     * 按 (provider, runtime) 解析对应的 runtime 实现。
     * 未知键抛异常（fail-fast）。
     */
    public SessionRuntime resolve(ProviderType provider, RuntimeType runtime) {
        SessionRuntime rt = runtimes.get(new Key(provider, runtime));
        if (rt == null) {
            throw new IllegalStateException(
                    "No runtime registered for " + provider + "/" + runtime);
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
