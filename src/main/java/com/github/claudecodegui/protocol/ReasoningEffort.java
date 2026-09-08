package com.github.claudecodegui.protocol;

import java.util.Arrays;
import java.util.Optional;

/**
 * 推理强度(reasoning effort)业务枚举(SSOT)。
 *
 * <p>协议线上传输的 reasoning effort 值的唯一权威定义。前端 TypeScript 类型由本枚举在
 * 构建时经 {@code generate-protocol-types.mjs} 自动生成(产物
 * {@code webview/src/generated/protocol.ts})。
 *
 * <p>值域 7 档(none/minimal/low/medium/high/xhigh/max)= 各 provider CLI 词表并集
 * (Claude 5 档 low…max;Codex 增 minimal;Grok/Pi/OMP 增 none)。<b>声明顺序 = 强度升序,
 * ordinal 即强度序</b>({@code ReasoningEffortResolver#clamp} 的降级比较依赖此约定)。
 * 实际展示为按 provider/model 的<b>子集过滤</b>:Claude 走 {@code ClaudeRole#reasoningLevels()}
 * 的 role 派生;其余 provider 由 {@code ReasoningCapabilities#levelsFor} 按 (provider, model)
 * 派生并经 {@code ModelRegistryService} 下发。本枚举只承载全集值域与类型 SSOT,与
 * {@code PermissionMode} 的展示/校验解耦模式一致。
 *
 * <p>默认值 {@code high} 由 {@link com.github.claudecodegui.common.CommonConstants#DEFAULT_REASONING_EFFORT}
 * 承载(C3 已将散落的 "medium" 兜底收敛至此),与本枚举 {@link #HIGH} 语义一致——后续可进一步
 * 由枚举直接承载默认(见 C3 修复记录"随 C2 枚举化后由枚举承载默认")。
 *
 * <p>⚠️ 修改此文件后需运行 {@code gradle generateProtocol} 更新前端类型。
 */
public enum ReasoningEffort implements ProtocolValue {

    NONE("none"),
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh"),
    MAX("max"),
    ;

    private final String value;

    ReasoningEffort(String value) {
        this.value = value;
    }

    /** 协议线上实际传输的字符串值 */
    @Override
    public String value() {
        return value;
    }

    public static Optional<ReasoningEffort> fromValue(String value) {
        return Arrays.stream(values()).filter(effort -> effort.value.equals(value)).findFirst();
    }
}
