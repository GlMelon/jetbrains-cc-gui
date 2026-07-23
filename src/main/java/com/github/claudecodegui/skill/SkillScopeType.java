package com.github.claudecodegui.skill;

import java.util.Arrays;
import java.util.Optional;

/** Provider-specific skill scopes used by the backend path policy. */
public enum SkillScopeType {
    GLOBAL("global"),
    LOCAL("local"),
    USER("user"),
    REPO("repo");

    private final String value;

    SkillScopeType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<SkillScopeType> fromValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(scope -> scope.value.equalsIgnoreCase(value.trim()))
                .findFirst();
    }
}
