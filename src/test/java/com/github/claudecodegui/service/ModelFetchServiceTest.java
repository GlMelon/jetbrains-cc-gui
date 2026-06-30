package com.github.claudecodegui.service;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link ModelFetchService} 纯函数测试 —— 候选 URL 构造。
 *
 * <p>用例移植自 cc-switch {@code model_fetch.rs:238-449} 的 build_models_url_candidates
 * 决策表,确保两端行为等价。
 */
public class ModelFetchServiceTest {

    // ===== modelsUrlOverride 分支 =====

    @Test
    public void overrideNonEmpty_returnsSingletonOverride() {
        assertEquals(
            List.of("https://api.deepseek.com/models"),
            ModelFetchService.buildModelsUrlCandidates("https://ignored", false, "https://api.deepseek.com/models"));
    }

    @Test
    public void overrideBlank_fallsBackToBaseUrl() {
        // override trim 后为空 → 走 baseUrl 分支
        assertEquals(
            List.of("https://api.x.com/v1/models"),
            ModelFetchService.buildModelsUrlCandidates("https://api.x.com", false, "   "));
    }

    @Test
    public void overrideNull_fallsBackToBaseUrl() {
        assertEquals(
            List.of("https://api.x.com/v1/models"),
            ModelFetchService.buildModelsUrlCandidates("https://api.x.com", false, null));
    }

    // ===== baseUrl 空校验 =====

    @Test(expected = IllegalArgumentException.class)
    public void baseUrlBlank_throws() {
        ModelFetchService.buildModelsUrlCandidates("   ", false, null);
    }

    // ===== 普通根域名 =====

    @Test
    public void plainRoot_appendsV1Models() {
        assertEquals(
            List.of("https://api.siliconflow.cn/v1/models"),
            ModelFetchService.buildModelsUrlCandidates("https://api.siliconflow.cn", false, null));
    }

    @Test
    public void trailingSlash_isTrimmed() {
        assertEquals(
            List.of("https://api.x.com/v1/models"),
            ModelFetchService.buildModelsUrlCandidates("https://api.x.com/", false, null));
    }

    // ===== 版本段结尾 =====

    @Test
    public void endsWithV1_modelsOnly_noDuplicate() {
        // /v1 结尾:只产 /v1/models(1 条,不重复 /v1/v1/models)
        assertEquals(
            List.of("https://x/v1/models"),
            ModelFetchService.buildModelsUrlCandidates("https://x/v1", false, null));
    }

    @Test
    public void endsWithOtherVersion_modelsFirst_thenV1Fallback() {
        // 非 /v1 的版本段(如 /v4):/models 在前,/v1/models 兜底在后(2 条)
        assertEquals(
            List.of("https://x/paas/v4/models", "https://x/paas/v4/v1/models"),
            ModelFetchService.buildModelsUrlCandidates("https://x/paas/v4", false, null));
    }

    // ===== 兼容后缀剥离 =====

    @Test
    public void compatSuffixAnthropic_originalStrippedAndRootless() {
        // 命中 /anthropic:原样 .../anthropic/v1/models + 剥离后 /v1/models + 剥离后 /models
        assertEquals(
            List.of(
                "https://api.deepseek.com/anthropic/v1/models",
                "https://api.deepseek.com/v1/models",
                "https://api.deepseek.com/models"),
            ModelFetchService.buildModelsUrlCandidates("https://api.deepseek.com/anthropic", false, null));
    }

    @Test
    public void compatSuffixApiAnthropic_longestFirst() {
        // /api/anthropic 优先于 /anthropic 剥离,得到根域名候选
        List<String> r = ModelFetchService.buildModelsUrlCandidates("https://open.bigmodel.cn/api/anthropic", false, null);
        assertEquals("https://open.bigmodel.cn/api/anthropic/v1/models", r.get(0));
        assertTrue(r.contains("https://open.bigmodel.cn/v1/models"));
        assertTrue(r.contains("https://open.bigmodel.cn/models"));
    }

    @Test
    public void compatSuffixClaude_stripsCorrectly() {
        List<String> r = ModelFetchService.buildModelsUrlCandidates("https://right.codes/claude", false, null);
        assertTrue(r.contains("https://right.codes/claude/v1/models"));
        assertTrue(r.contains("https://right.codes/v1/models"));
        assertTrue(r.contains("https://right.codes/models"));
    }

    // ===== isFullUrl 分支 =====

    @Test
    public void fullUrlWithV1Path_derivesModels() {
        assertEquals(
            List.of("https://x/v1/models"),
            ModelFetchService.buildModelsUrlCandidates("https://x/v1/chat/completions", true, null));
    }

    @Test(expected = IllegalArgumentException.class)
    public void fullUrlCannotDerive_throws() {
        ModelFetchService.buildModelsUrlCandidates("garbage", true, null);
    }

    // ===== 去重 =====

    @Test
    public void candidatesAreDeduplicated() {
        List<String> r = ModelFetchService.buildModelsUrlCandidates("https://x/v1", false, null);
        long distinct = r.stream().distinct().count();
        assertEquals(distinct, r.size());
    }

    // ===== parseModelIds 解析(OpenAI 兼容三种结构)=====

    @Test
    public void parseModelIds_openAiDataStructure() {
        assertEquals(
            List.of("gpt-4", "gpt-3.5-turbo"),
            ModelFetchService.parseModelIds("{\"data\":[{\"id\":\"gpt-4\",\"owned_by\":\"openai\"},{\"id\":\"gpt-3.5-turbo\"}]}"));
    }

    @Test
    public void parseModelIds_modelsFieldStructure() {
        assertEquals(
            List.of("model-a"),
            ModelFetchService.parseModelIds("{\"models\":[{\"id\":\"model-a\"}]}"));
    }

    @Test
    public void parseModelIds_bareArray() {
        assertEquals(
            List.of("x", "y"),
            ModelFetchService.parseModelIds("[{\"id\":\"x\"},{\"id\":\"y\"}]"));
    }

    @Test
    public void parseModelIds_emptyData() {
        assertEquals(List.of(), ModelFetchService.parseModelIds("{\"data\":[]}"));
    }

    @Test
    public void parseModelIds_garbageReturnsEmpty() {
        assertEquals(List.of(), ModelFetchService.parseModelIds("garbage"));
        assertEquals(List.of(), ModelFetchService.parseModelIds(""));
        assertEquals(List.of(), ModelFetchService.parseModelIds(null));
    }

    @Test
    public void parseModelIds_deduplicates() {
        assertEquals(
            List.of("a", "b"),
            ModelFetchService.parseModelIds("{\"data\":[{\"id\":\"a\"},{\"id\":\"a\"},{\"id\":\"b\"}]}"));
    }

    @Test
    public void parseModelIds_skipsEntriesWithoutId() {
        assertEquals(
            List.of("ok"),
            ModelFetchService.parseModelIds("{\"data\":[{\"name\":\"no-id\"},{\"id\":\"ok\"}]}"));
    }
}
