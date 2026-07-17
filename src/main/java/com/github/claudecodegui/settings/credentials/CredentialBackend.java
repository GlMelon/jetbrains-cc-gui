package com.github.claudecodegui.settings.credentials;

/**
 * 凭证后端抽象(S2 / docs §S2 聚焦核心范围)。
 *
 * <p>把 IntelliJ {@code PasswordSafe} 的实际存取从 {@link PasswordStore} 门面解耦:生产用
 * {@link IntelliJPasswordSafeBackend}(委托平台 PasswordSafe),测试用 {@code InMemoryCredentialBackend}
 * (test 源集,纯内存 fake,注入可用性状态覆盖故障矩阵,无需 Application 上下文或真实 keychain)。
 *
 * <p>聚焦核心:本接口只定义 store/load/remove/probeAvailability 契约;credential key 规范、
 * 容量边界与可用性降级的判定逻辑在 {@link PasswordStore} 门面(可单测),后端实现保持薄委托。
 *
 * <p>本范围未含(独立立项):project/global scope 区分(暂只 GLOBAL)、明文配置迁移、
 * 六路径 env 注入改造、backup/诊断包 secret 清理、DISABLED 状态的精细检测。
 */
public interface CredentialBackend {

    /** 后端可用性(§S2 显式降级:headless CI / 无系统 keychain 时 PasswordSafe 可能不可用)。 */
    enum Availability {
        /** 后端可用,store/load 正常。 */
        AVAILABLE,
        /** 用户在设置中显式禁用 PasswordSafe。 */
        DISABLED,
        /** 无可用原生后端(headless CI / 服务器 Linux 无 libsecret / 纯单测无 Application)。 */
        HEADLESS_NO_BACKEND
    }

    /**
     * 持久化凭证(覆盖已有值)。后端实现须保证 secret 值不写入日志。
     *
     * @param credentialKey 凭证逻辑键(规范 {@code codemoss.<domain>.<kind>},如 {@code codemoss.smithery.apiKey})
     * @param password      凭证明文;{@code null} 等价于 {@link #remove(String)}
     */
    void store(String credentialKey, String password);

    /**
     * 读取凭证。
     *
     * @param credentialKey 凭证逻辑键
     * @return 凭证明文;不存在时返回 {@code null}
     */
    String load(String credentialKey);

    /** 删除凭证(不存在时 no-op)。 */
    void remove(String credentialKey);

    /**
     * 探测后端可用性(门面在 store 前据此显式降级)。
     * 实现应轻量(不触发原生 keychain 交互)。
     */
    Availability probeAvailability();
}
