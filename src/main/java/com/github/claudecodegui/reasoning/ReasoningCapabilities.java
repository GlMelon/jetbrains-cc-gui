package com.github.claudecodegui.reasoning;

import com.github.claudecodegui.protocol.ReasoningEffort;
import com.github.claudecodegui.session.runtime.ProviderType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 内置 (provider, model) → reasoning effort 支持档位能力表(数据驱动)。
 *
 * <p>每个 provider 一个 entry = provider 默认档位 + 模型规则(前缀 → 档位)列表;
 * 模型规则按<b>最长前缀优先</b>匹配(如 {@code gpt-5.1}/{@code gpt-5.2} 先于 {@code gpt-5}
 * 生效)。键为 {@link ProviderType#value()},档位字符串一律引用 {@link ReasoningEffort#value()}
 * (总则五·硬编码禁止)。
 *
 * <p>有意不覆盖的 provider:
 * <ul>
 *   <li>{@code claude} —— 走 {@code ClaudeRole#reasoningLevels()} 的 role 派生(保持现状);</li>
 *   <li>{@code dsh} / {@code minimax} —— 不支持 effort,返回 {@code null}(不透传)。</li>
 * </ul>
 */
public final class ReasoningCapabilities {

    private ReasoningCapabilities() {
    }

    /** 通用容量后缀(如 {@code [1m]}/{@code [200k]}),匹配前剥离;对齐 ClaudeRole.CAPACITY_SUFFIX 思路。 */
    private static final Pattern CAPACITY_SUFFIX = Pattern.compile("(?i)\\s*\\[[0-9.]+[kKmM]\\]\\s*$");

    private record ModelRule(String prefix, List<String> levels) {
    }

    private record ProviderEntry(List<String> defaultLevels, List<ModelRule> rules) {
    }

    private static final Map<String, ProviderEntry> ENTRIES = buildEntries();

    private static Map<String, ProviderEntry> buildEntries() {
        Map<String, ProviderEntry> entries = new LinkedHashMap<>();
        entries.put(ProviderType.CODEX.value(), new ProviderEntry(
                levels(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
                        ReasoningEffort.XHIGH),
                List.of(
                        new ModelRule("gpt-5.1", levels(ReasoningEffort.NONE, ReasoningEffort.LOW,
                                ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)),
                        new ModelRule("gpt-5.2", levels(ReasoningEffort.LOW, ReasoningEffort.MEDIUM,
                                ReasoningEffort.HIGH, ReasoningEffort.XHIGH)),
                        new ModelRule("gpt-5", levels(ReasoningEffort.MINIMAL, ReasoningEffort.LOW,
                                ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)))));
        entries.put(ProviderType.GROK.value(), new ProviderEntry(
                levels(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
                List.of(
                        new ModelRule("grok-3-mini", levels(ReasoningEffort.LOW, ReasoningEffort.HIGH)),
                        new ModelRule("grok-4.3", levels(ReasoningEffort.NONE, ReasoningEffort.LOW,
                                ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)),
                        new ModelRule("grok-4.6", xhighCapable()),
                        new ModelRule("grok-4.7", xhighCapable()),
                        new ModelRule("grok-4.8", xhighCapable()),
                        new ModelRule("grok-4.9", xhighCapable()),
                        new ModelRule("grok-5", xhighCapable()))));
        // kimi:UI 全集,运行时 ACP 通道按模型元数据 support_efforts 动态收窄(协商已有实现)。
        entries.put(ProviderType.KIMI.value(), new ProviderEntry(
                levels(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
                        ReasoningEffort.XHIGH, ReasoningEffort.MAX),
                List.of()));
        // pi/omp:--thinking 词表 off/minimal/.../max;none 发送时映射 off(映射在 CLI 会话层,不在本类)。
        ProviderEntry piOmpEntry = new ProviderEntry(
                levels(ReasoningEffort.NONE, ReasoningEffort.MINIMAL, ReasoningEffort.LOW,
                        ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.XHIGH,
                        ReasoningEffort.MAX),
                List.of());
        entries.put(ProviderType.PI.value(), piOmpEntry);
        entries.put(ProviderType.OMP.value(), piOmpEntry);
        // opencode:variant per-model catalog 差异大,取安全默认子集。
        entries.put(ProviderType.OPENCODE.value(), new ProviderEntry(
                levels(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
                List.of()));
        return entries;
    }

    private static List<String> levels(ReasoningEffort... efforts) {
        List<String> result = new java.util.ArrayList<>(efforts.length);
        for (ReasoningEffort effort : efforts) {
            result.add(effort.value());
        }
        return List.copyOf(result);
    }

    private static List<String> xhighCapable() {
        return levels(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
                ReasoningEffort.XHIGH);
    }

    /**
     * 返回该 (provider, modelId) 的有序支持档位。
     *
     * @param provider provider 协议值(大小写不敏感);无 reasoning 能力(dsh/minimax)
     *                 或未收录 → {@code null}
     * @param modelId  模型 ID(可带容量后缀);null/未知 → provider 默认集
     * @return 有序档位列表;provider 默认集也没有 → {@code null}
     */
    public static List<String> levelsFor(String provider, String modelId) {
        if (provider == null || provider.isBlank()) {
            return null;
        }
        ProviderEntry entry = ENTRIES.get(provider.trim().toLowerCase(Locale.ROOT));
        if (entry == null) {
            return null;
        }
        if (modelId != null && !modelId.isBlank()) {
            String normalized = CAPACITY_SUFFIX.matcher(modelId.trim()).replaceFirst("")
                    .toLowerCase(Locale.ROOT);
            ModelRule best = null;
            for (ModelRule rule : entry.rules()) {
                if (normalized.startsWith(rule.prefix())
                        && (best == null || rule.prefix().length() > best.prefix().length())) {
                    best = rule;
                }
            }
            if (best != null) {
                return best.levels();
            }
        }
        return entry.defaultLevels();
    }

    /**
     * 各 provider 的默认档位集(provider value → 有序档位),供 payload 下发
     * {@code providerDefaults} 回退字段。无能力 provider(dsh/minimax)不在其中;
     * claude 亦不在(走 role 派生)。
     */
    public static Map<String, List<String>> providerDefaults() {
        Map<String, List<String>> defaults = new LinkedHashMap<>();
        for (Map.Entry<String, ProviderEntry> entry : ENTRIES.entrySet()) {
            if (!entry.getValue().defaultLevels().isEmpty()) {
                defaults.put(entry.getKey(), entry.getValue().defaultLevels());
            }
        }
        return java.util.Collections.unmodifiableMap(defaults);
    }
}
