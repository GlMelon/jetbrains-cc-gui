package com.github.claudecodegui.reasoning;

import com.github.claudecodegui.protocol.ReasoningEffort;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 通用 reasoning effort 降级(纯函数,无 Platform 耦合)。
 *
 * <p>在有序支持列表上取「不超过请求值的最高支持档」,供 CLI 透传前把用户档位钳制到
 * 目标 (provider, model) 的实际支持集内。强度序以 {@link ReasoningEffort} 声明顺序
 * (ordinal)为权威——该枚举的 javadoc 已锁定「声明顺序 = 强度升序」约定。
 */
public final class ReasoningEffortResolver {

    private ReasoningEffortResolver() {
    }

    /**
     * 在有序支持列表上取「不超过请求值的最高支持档」。
     *
     * <ul>
     *   <li>{@code supportedLevels} 为 null 或空(或全部不是合法枚举值)→ {@code null}
     *       (调用方不透传 / 隐藏);</li>
     *   <li>{@code requested} 为 null / blank / 非合法枚举值 → {@code null};</li>
     *   <li>{@code requested} 在列表中 → 返回归一化后的协议值;</li>
     *   <li>不在列表中 → 取 ordinal 严格小于 requested 的最高支持档;
     *       没有更低的 → 返回列表中的最低档。</li>
     * </ul>
     *
     * <p>大小写与首尾空白归一(trim + lowercase)。返回值始终是合法的协议字符串
     * ({@link ReasoningEffort#value()}),不回传调用方原始大小写。
     */
    public static String clamp(String requested, List<String> supportedLevels) {
        if (supportedLevels == null || supportedLevels.isEmpty()) {
            return null;
        }
        if (requested == null || requested.isBlank()) {
            return null;
        }
        Optional<ReasoningEffort> requestedEffort =
                ReasoningEffort.fromValue(requested.trim().toLowerCase(Locale.ROOT));
        if (requestedEffort.isEmpty()) {
            return null;
        }
        ReasoningEffort requestedLevel = requestedEffort.get();

        List<ReasoningEffort> supported = new ArrayList<>(supportedLevels.size());
        for (String level : supportedLevels) {
            if (level == null) {
                continue;
            }
            ReasoningEffort.fromValue(level.trim().toLowerCase(Locale.ROOT)).ifPresent(supported::add);
        }
        if (supported.isEmpty()) {
            return null;
        }

        if (supported.contains(requestedLevel)) {
            return requestedLevel.value();
        }
        ReasoningEffort highestBelow = null;
        ReasoningEffort lowest = supported.get(0);
        for (ReasoningEffort level : supported) {
            if (level.ordinal() < requestedLevel.ordinal()
                    && (highestBelow == null || level.ordinal() > highestBelow.ordinal())) {
                highestBelow = level;
            }
            if (level.ordinal() < lowest.ordinal()) {
                lowest = level;
            }
        }
        return (highestBelow != null ? highestBelow : lowest).value();
    }
}
