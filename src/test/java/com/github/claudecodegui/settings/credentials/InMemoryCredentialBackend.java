package com.github.claudecodegui.settings.credentials;

import com.github.claudecodegui.settings.credentials.CredentialBackend.Availability;

import java.util.HashMap;
import java.util.Map;

/**
 * 测试用内存凭证后端(S2 PasswordStore 故障注入测试)。
 *
 * <p>纯内存 Map fake,可注入 {@link Availability} 模拟 headless / disabled / 正常态,
 * 无需 Application 上下文或真实 keychain。覆盖 PasswordStore 门面逻辑(容量边界 / 显式降级 /
 * 日志安全)而不依赖 IntelliJPasswordSafeBackend 的平台耦合。
 */
public class InMemoryCredentialBackend implements CredentialBackend {

    private final Map<String, String> store = new HashMap<>();
    private Availability availability = Availability.AVAILABLE;

    public void setAvailability(Availability availability) {
        this.availability = availability;
    }

    @Override
    public void store(String credentialKey, String password) {
        store.put(credentialKey, password);
    }

    @Override
    public String load(String credentialKey) {
        return store.get(credentialKey);
    }

    @Override
    public void remove(String credentialKey) {
        store.remove(credentialKey);
    }

    @Override
    public Availability probeAvailability() {
        return availability;
    }
}
