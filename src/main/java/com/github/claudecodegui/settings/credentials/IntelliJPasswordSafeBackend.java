package com.github.claudecodegui.settings.credentials;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.application.ApplicationManager;

/**
 * 生产凭证后端:委托 IntelliJ {@code PasswordSafe}(S2 / docs §S2)。
 *
 * <p>{@code PasswordSafe} 依据平台自动选择后端:macOS Keychain / Windows KeePass / Linux libsecret。
 * serviceName 固定 {@value #SERVICE_NAME},accountName = credentialKey,避免与第三方插件条目冲突。
 *
 * <p><b>可用性探测(聚焦核心,保守策略)</b>:用 {@link ApplicationManager#getApplication()} 是否为 null 判定——
 * 纯单测 / 非 IDE 运行 / headless CI 无 Application 时返回 {@link CredentialBackend.Availability#HEADLESS_NO_BACKEND};
 * IDE 内保守判为 {@link CredentialBackend.Availability#AVAILABLE}。DISABLED(用户显式禁用 PasswordSafe)
 * 的精细检测需平台设置读取,列为后续接线(迁移/注入项目可在 IDE 内端到端验证)。
 *
 * <p><b>测试覆盖边界</b>:真实 keychain 的 get/set 交互留集成测试(runIde)或手动验证;
 * 纯单测不覆盖本类(注入 {@code InMemoryCredentialBackend} 测 {@link PasswordStore} 逻辑)。
 * {@link #attributes(String)} 是纯函数,可单测(serviceName + accountName 映射)。
 */
public class IntelliJPasswordSafeBackend implements CredentialBackend {

    /** PasswordSafe 条目的 serviceName(所有插件凭证共用,accountName 区分具体 key)。 */
    static final String SERVICE_NAME = "codemoss";

    @Override
    public void store(String credentialKey, String password) {
        PasswordSafe.getInstance().setPassword(attributes(credentialKey), password);
    }

    @Override
    public String load(String credentialKey) {
        return PasswordSafe.getInstance().getPassword(attributes(credentialKey));
    }

    @Override
    public void remove(String credentialKey) {
        // setPassword(null) 是 PasswordSafe 删除条目的标准方式(比 remove 更广跨版本兼容)。
        PasswordSafe.getInstance().setPassword(attributes(credentialKey), null);
    }

    @Override
    public Availability probeAvailability() {
        if (ApplicationManager.getApplication() == null) {
            return Availability.HEADLESS_NO_BACKEND;
        }
        return Availability.AVAILABLE;
    }

    /** 构造 PasswordSafe 凭证属性(serviceName + accountName)。纯函数,可单测。 */
    static CredentialAttributes attributes(String credentialKey) {
        return new CredentialAttributes(SERVICE_NAME, credentialKey);
    }
}
