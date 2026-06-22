package com.github.claudecodegui.protocol;

import java.util.Arrays;
import java.util.Optional;

/**
 * 权限模式业务枚举(SSOT)。
 *
 * <p>协议线上传输的权限模式值的唯一权威定义。前端 TypeScript 类型由本枚举在构建时
 * 经 {@code generate-protocol-types.mjs} 自动生成(产物 {@code webview/src/generated/protocol.ts})。
 *
 * <p>值域对齐 {@code session/SessionState#VALID_PERMISSION_MODES}(session 权威校验入口,
 * 见 {@code SessionState#isValidPermissionMode}):default / acceptEdits / plan /
 * bypassPermissions / autoEdit。
 *
 * <p>{@code AUTO_EDIT} 是 {@code ACCEPT_EDITS} 的历史别名,语义同 ACCEPT_EDITS
 * ({@code session/ClaudeSession} 与 {@code cli/codex/CodexCliCommandUtils} 均按 ACCEPT_EDITS
 * 处理)。保留以兼容协议线上可能出现的旧值;前端 UI 展示列表({@code AVAILABLE_MODES})
 * 可不含此别名,但类型与校验必须覆盖。
 *
 * <p>与 {@code permission/PermissionManager.PermissionMode}(内部状态机:
 * DEFAULT/ACCEPT_EDITS/ALLOW_ALL/DENY_ALL)是<b>不同概念</b>,不可复用——后者是会话内部
 * 权限决策状态,多 ALLOW_ALL/DENY_ALL,且无 plan/autoEdit。
 *
 * <p>⚠️ 修改此文件后需运行 {@code gradle generateProtocol} 更新前端类型。
 */
public enum PermissionMode implements ProtocolValue {

    DEFAULT("default"),
    ACCEPT_EDITS("acceptEdits"),
    PLAN("plan"),
    BYPASS_PERMISSIONS("bypassPermissions"),
    /**
     * acceptEdits 的历史别名,语义同 ACCEPT_EDITS(ClaudeSession/Codex 均按 ACCEPT_EDITS 处理)
     */
    AUTO_EDIT("autoEdit");

    private final String value;

    PermissionMode(String value) {
        this.value = value;
    }

    /**
     * 协议线上实际传输的字符串值
     */
    @Override
    public String value() {
        return value;
    }

    public static Optional<PermissionMode> fromValue(String value) {
        return Arrays.stream(values()).filter(mode -> mode.value.equals(value)).findFirst();
    }
}
