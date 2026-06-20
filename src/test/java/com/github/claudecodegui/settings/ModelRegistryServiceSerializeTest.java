package com.github.claudecodegui.settings;

import com.github.claudecodegui.config.ModelConfig;
import com.github.claudecodegui.config.ModelRegistryConfig;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;

/**
 * 守门测试:ModelRegistryService.serialize 输出的 payload 字段集必须与
 * ModelConfig record 的字段逐一对齐(AGENTS.md 总则三·payload SSOT)。
 *
 * <p>后端 serialize 是模型注册表 payload 的权威字段来源;前端 ModelRegistryItem
 * 必须覆盖同一字段集。本测试固定后端 schema,防止字段漂移;
 * 前端对齐守门见 modelRegistry.test.ts 的 "parsed item covers all backend fields"。
 *
 * <p>注:本项目 testImplementation 仅声明了 JUnit 4(见 build.gradle:93),
 * 故本测试沿用同目录 ModelRegistryServiceTest.java 既有 JUnit 4 风格,
 * 而非任务模板默认的 Jupiter API。
 */
public class ModelRegistryServiceSerializeTest {

    @Test
    public void serializeEmitsExactlyTheModelConfigRecordFields() {
        ModelConfig sample = new ModelConfig(
                "claude-role-sonnet", "claude", "sonnet", "Sonnet", "glm5.2",
                "desc", 200_000, true, true, false);
        JsonObject payload = ModelRegistryService.serialize(new ModelRegistryConfig(List.of(sample)));

        JsonObject item = payload.getAsJsonArray("items").get(0).getAsJsonObject();

        Set<String> recordFields = new LinkedHashSet<>();
        for (RecordComponent rc : ModelConfig.class.getRecordComponents()) {
            recordFields.add(rc.getName());
        }
        assertEquals("serialize payload item fields must match ModelConfig record components exactly "
                        + "(AGENTS.md §3 payload SSOT)",
                recordFields, item.keySet());
    }
}
