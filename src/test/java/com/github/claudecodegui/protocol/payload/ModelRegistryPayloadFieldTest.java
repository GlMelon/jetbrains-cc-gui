package com.github.claudecodegui.protocol.payload;

import com.github.claudecodegui.common.ClaudeRole;
import com.github.claudecodegui.common.CommonConstants;
import com.github.claudecodegui.config.ModelConfig;
import com.github.claudecodegui.config.ModelRegistryConfig;
import com.github.claudecodegui.settings.ModelRegistryService;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;

/**
 * C1 后端守门:ModelRegistryPayloadField 声明的 wire 字段集必须精确等于
 * ModelRegistryService.serialize 对完整 model 实际产出的 JSON key 集。
 *
 * <p>防漂移:后端 serialize 加/删/改字段时,若未同步更新 payload 声明(反之亦然),
 * 此测试失败。serialize 的 supportedReasoningLevels 为派生字段(由 role 计算),
 * 故 sample 用 claude + 已知 role 触发其下发,使 11 个 wire key 全部产出。
 */
public class ModelRegistryPayloadFieldTest {

    @Test
    public void declaredWireKeysMatchSerializeOutput() {
        // 完整 claude sample:已知 role 触发 supportedReasoningLevels 派生下发(全 11 key)
        ModelConfig sample = new ModelConfig(
                ClaudeRole.SONNET.roleId(),
                CommonConstants.PROVIDER_CLAUDE,
                "sonnet",
                "Sonnet",
                "claude-sonnet-4-6",
                "",
                200_000,
                true,
                true,
                false);
        ModelRegistryConfig registry = new ModelRegistryConfig(List.of(sample));

        JsonObject root = ModelRegistryService.serialize(registry);
        JsonObject item = root.getAsJsonArray("items").get(0).getAsJsonObject();

        Set<String> actualKeys = new TreeSet<>(item.keySet());
        Set<String> declaredKeys = new TreeSet<>(ModelRegistryPayloadField.wireKeys());

        assertEquals(declaredKeys, actualKeys);
    }

    @Test
    public void declaredFieldCountMatchesSerialize() {
        // 显式计数守门:11 个字段(含派生 supportedReasoningLevels)。
        // 若字段数变化,此测试强制开发者确认是否同步 serialize 与生成链路。
        ModelConfig sample = new ModelConfig(
                ClaudeRole.SONNET.roleId(),
                CommonConstants.PROVIDER_CLAUDE,
                "sonnet",
                "Sonnet",
                "claude-sonnet-4-6",
                "",
                200_000,
                true,
                true,
                false);
        JsonObject root = ModelRegistryService.serialize(
                new ModelRegistryConfig(List.of(sample)));
        JsonObject item = root.getAsJsonArray("items").get(0).getAsJsonObject();

        assertEquals(item.keySet().size(), ModelRegistryPayloadField.values().length);
    }
}
