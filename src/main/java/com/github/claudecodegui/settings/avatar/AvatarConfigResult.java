package com.github.claudecodegui.settings.avatar;

public final class AvatarConfigResult {
    private final String configJson;

    public AvatarConfigResult(String configJson) {
        this.configJson = configJson;
    }

    public String configJson() {
        return configJson;
    }
}
