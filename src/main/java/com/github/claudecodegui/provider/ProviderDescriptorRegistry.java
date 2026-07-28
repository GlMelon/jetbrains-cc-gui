package com.github.claudecodegui.provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Provider 描述符注册表 —— S4-1C+ 配置驱动扩展的查询入口。
 *
 * <p>聚合 {@link ProviderDescriptor#builtins()}(三内置 Provider,与既有装配等价)与运行时加载的
 * 自定义 Provider(配置驱动扩展)。自定义 Provider 按 {@code providerId} 覆盖同 id 的内置项或新增,
 * 使 Provider 集合可在运行时扩展,而无需修改 Java 代码或引入第三方 classloader。
 *
 * <p>本注册表是<b>描述符层</b>的单一查询入口(对称 {@link ProviderRegistry} 的 adapter 层查询);
 * 后续通用 SessionRuntime(由描述符的 CLI 命令模板驱动)消费本表,实现配置驱动的 Provider 装配。
 */
public class ProviderDescriptorRegistry {

    private final Map<String, ProviderDescriptor> descriptors;

    public ProviderDescriptorRegistry() {
        this(List.of());
    }

    /**
     * @param custom 自定义 Provider 描述符;按 {@code providerId} 覆盖同名内置项或新增。允许 null/空。
     */
    public ProviderDescriptorRegistry(List<ProviderDescriptor> custom) {
        this.descriptors = new LinkedHashMap<>();
        for (ProviderDescriptor builtin : ProviderDescriptor.builtins()) {
            descriptors.put(builtin.providerId(), builtin);
        }
        if (custom != null) {
            for (ProviderDescriptor descriptor : custom) {
                descriptors.put(descriptor.providerId(), descriptor);
            }
        }
    }

    public boolean has(String providerId) {
        return descriptors.containsKey(normalize(providerId));
    }

    /** 返回指定 Provider 描述符;未知 id 返回 {@code null}(能力探测式查询,不抛异常)。 */
    public ProviderDescriptor get(String providerId) {
        return descriptors.get(normalize(providerId));
    }

    /** 全部 Provider 描述符(内置 + 自定义,按插入顺序:先内置后自定义)。 */
    public List<ProviderDescriptor> all() {
        return List.copyOf(descriptors.values());
    }

    /** 声明支持指定能力的全部 Provider(按注册顺序)。 */
    public List<ProviderDescriptor> withCapability(ProviderCapability capability) {
        return descriptors.values().stream()
                .filter(d -> d.supports(capability))
                .toList();
    }

    private static String normalize(String providerId) {
        return providerId == null ? "" : providerId.trim().toLowerCase(Locale.ROOT);
    }
}
