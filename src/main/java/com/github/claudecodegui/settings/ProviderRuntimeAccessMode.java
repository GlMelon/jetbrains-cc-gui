package com.github.claudecodegui.settings;

import com.github.claudecodegui.protocol.ProtocolValue;

/** Provider 运行时接入状态的后端单一真相源。 */
public enum ProviderRuntimeAccessMode implements ProtocolValue {
    INACTIVE("inactive"),
    MANAGED("managed"),
    CLI_LOGIN("cli_login");

    private final String value;

    ProviderRuntimeAccessMode(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }
}
