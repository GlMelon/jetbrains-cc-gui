package com.github.claudecodegui.settings.credentials;

import com.github.claudecodegui.settings.credentials.CredentialBackend.Availability;
import com.intellij.openapi.diagnostic.Logger;

import java.nio.charset.StandardCharsets;

/**
 * 凭证安全存储门面(S2 / docs §S2 聚焦核心范围)。
 *
 * <p>收口插件自有 secret(provider API key / token / OAuth refresh token / smitheryApiKey 等)的存取,
 * 通过注入的 {@link CredentialBackend} 委托到 IntelliJ {@code PasswordSafe}(而非明文 config.json)。
 *
 * <p>门面职责(可单测的核心逻辑,与后端解耦):
 * <ul>
 *   <li><b>容量边界</b>:单值超 {@link #MAX_CREDENTIAL_BYTES} 抛 {@link CredentialTooLargeException}
 *       (§S2:PasswordSafe 单值有大小上限,Windows KeePass / headless 尤甚;OAuth refresh token
 *       或大块 JSON 凭证需分割存储或回退文件+0600,本门面先 fail-fast,分割策略列为后续)。</li>
 *   <li><b>显式降级</b>:后端 {@link Availability} 非 {@link Availability#AVAILABLE} 时 store 抛
 *       {@link CredentialStoreUnavailableException}(§S2:headless CI / 无 keychain 不静默降级为不安全存储)。
 *       读路径(load)不抛——缺失 key 等价于"未配置",不应阻塞调用方;迁移/注入项目可在调用侧先
 *       {@link #getAvailability()} 显式处理降级。</li>
 *   <li><b>日志安全</b>:绝不记录 secret 值,仅记录 credentialKey + 操作状态(stored/removed/loaded-miss)
 *       + 字节数。</li>
 *   <li><b>credential key 规范</b>:必须以 {@link #CREDENTIAL_KEY_PREFIX} 开头({@code codemoss.}),
 *       防止与第三方插件 PasswordSafe 条目冲突,也便于脱敏扫描识别。</li>
 * </ul>
 *
 * <p><b>聚焦核心范围</b>:本类是 S2 地基(零调用面)——API 契约 + 容量/降级/日志安全 + 故障注入测试。
 * 明文配置迁移(config.json → PasswordStore 一次性迁移 smitheryApiKey 等)、六路径 env 注入改造
 * (provider 子进程启动从 PasswordStore 读 secret 注入)、clear/logout UI、backup/诊断包 secret 清理
 * 均列为后续独立项目接线。
 *
 * @see CredentialBackend 后端抽象
 * @see IntelliJPasswordSafeBackend 生产实现
 */
public class PasswordStore {

    private static final Logger LOG = Logger.getInstance(PasswordStore.class);

    /** 单凭证值的字节上限(UTF-8)。保守值,覆盖绝大多数 API key;OAuth refresh token / 大 JSON 超限 fail-fast。 */
    public static final int MAX_CREDENTIAL_BYTES = 8 * 1024;

    /** credential 逻辑键前缀规范(强制,防与第三方插件条目冲突 + 便于脱敏扫描)。 */
    public static final String CREDENTIAL_KEY_PREFIX = "codemoss.";

    private final CredentialBackend backend;

    public PasswordStore(CredentialBackend backend) {
        this.backend = backend;
    }

    /**
     * 存储凭证。{@code password==null} 或空串清除(等价 {@link #removePassword})。
     *
     * @throws CredentialTooLargeException 凭证超 {@link #MAX_CREDENTIAL_BYTES}(unchecked)
     * @throws CredentialStoreUnavailableException 后端不可用 headless / disabled(unchecked)
     */
    public void storePassword(String credentialKey, String password) {
        validateKey(credentialKey);
        if (password == null || password.isEmpty()) {
            removePassword(credentialKey);
            return;
        }
        ensureAvailable(credentialKey);
        int bytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_CREDENTIAL_BYTES) {
            throw new CredentialTooLargeException(credentialKey, bytes, MAX_CREDENTIAL_BYTES);
        }
        backend.store(credentialKey, password);
        // 安全:只记 key + 字节数,绝不记 password 值。
        LOG.info("[PasswordStore] Credential stored: " + credentialKey + " (" + bytes + " bytes)");
    }

    /**
     * 读取凭证。
     *
     * @return 凭证明文;不存在或后端不可用时返回 {@code null}(后端不可用按缺失处理,
     *         读路径不抛降级异常——迁移/注入项目可在调用侧先 getAvailability 显式处理)
     */
    public String loadPassword(String credentialKey) {
        validateKey(credentialKey);
        if (backend.probeAvailability() != Availability.AVAILABLE) {
            LOG.warn("[PasswordStore] Backend unavailable, treating load as missing: " + credentialKey);
            return null;
        }
        return backend.load(credentialKey);
    }

    /** 删除凭证(不存在 no-op)。删除幂等,后端不可用时仍尝试(不阻塞)。 */
    public void removePassword(String credentialKey) {
        validateKey(credentialKey);
        backend.remove(credentialKey);
        LOG.info("[PasswordStore] Credential removed: " + credentialKey);
    }

    /** 后端可用性(供迁移/注入项目在批量操作前显式降级提示)。 */
    public Availability getAvailability() {
        return backend.probeAvailability();
    }

    private void ensureAvailable(String credentialKey) {
        Availability avail = backend.probeAvailability();
        if (avail != Availability.AVAILABLE) {
            throw new CredentialStoreUnavailableException(credentialKey, avail);
        }
    }

    private static void validateKey(String credentialKey) {
        if (credentialKey == null || !credentialKey.startsWith(CREDENTIAL_KEY_PREFIX)) {
            throw new IllegalArgumentException(
                    "credentialKey must start with '" + CREDENTIAL_KEY_PREFIX + "': " + credentialKey);
        }
    }

    /** 凭证超容量上限(§S2 容量边界)。unchecked:调用方按需 catch,或让 fail-fast 冒泡。 */
    public static class CredentialTooLargeException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String credentialKey;
        private final int actualBytes;
        private final int limitBytes;

        public CredentialTooLargeException(String credentialKey, int actualBytes, int limitBytes) {
            super("Credential '" + credentialKey + "' is " + actualBytes + " bytes, exceeds limit "
                    + limitBytes + " bytes (consider chunked storage or file+0600 fallback)");
            this.credentialKey = credentialKey;
            this.actualBytes = actualBytes;
            this.limitBytes = limitBytes;
        }

        public String getCredentialKey() {
            return credentialKey;
        }

        public int getActualBytes() {
            return actualBytes;
        }

        public int getLimitBytes() {
            return limitBytes;
        }
    }

    /** 后端不可用(§S2 显式降级:headless / disabled,不静默走不安全存储)。unchecked。 */
    public static class CredentialStoreUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String credentialKey;
        private final Availability availability;

        public CredentialStoreUnavailableException(String credentialKey, Availability availability) {
            super("Credential backend unavailable (" + availability + ") for '" + credentialKey
                    + "': refusing silent insecure fallback");
            this.credentialKey = credentialKey;
            this.availability = availability;
        }

        public String getCredentialKey() {
            return credentialKey;
        }

        public Availability getAvailability() {
            return availability;
        }
    }
}
