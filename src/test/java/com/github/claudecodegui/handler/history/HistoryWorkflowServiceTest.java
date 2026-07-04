package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.session.runtime.ProviderType;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HistoryWorkflowServiceTest {

    @Test
    public void refreshRoutesProviderAndDispatchesEnhancedJson() {
        RecordingAdapter codex = new RecordingAdapter(ProviderType.CODEX,
                "{\"success\":true,\"sessions\":[{\"sessionId\":\"s1\"}]}");
        RecordingAdapter opencode = new RecordingAdapter(ProviderType.OPENCODE, "");
        RecordingDispatchService dispatchService = new RecordingDispatchService();
        HistoryWorkflowService workflow = workflow(new HistoryProviderRegistry(List.of(codex, opencode)), dispatchService,
                () -> "D:\\repo", null, null);

        workflow.refresh(ProviderType.OPENCODE.value());

        assertEquals(0, codex.loadSessionsCalls);
        assertEquals(1, opencode.loadSessionsCalls);
        assertEquals(HistorySessionsJsonEnhancer.EMPTY_SESSIONS_JSON, dispatchService.lastJson);
    }

    @Test
    public void deleteOneDeletesThenMetadataAttachmentsCacheAndRefresh() {
        RecordingAdapter adapter = new RecordingAdapter(ProviderType.CLAUDE,
                "{\"success\":true,\"sessions\":[]}");
        adapter.deleteResult = new HistoryDeleteResult(true, 2);
        RecordingDispatchService dispatchService = new RecordingDispatchService();
        List<String> events = new ArrayList<>();
        HistoryWorkflowService workflow = workflow(new HistoryProviderRegistry(List.of(adapter)), dispatchService,
                () -> "D:\\repo",
                (provider, sessionId) -> events.add("attachments:" + provider + ":" + sessionId),
                sessionId -> events.add("metadata:" + sessionId));

        HistoryDeleteResult result = workflow.deleteOne(ProviderType.CLAUDE.value(), "s1");

        assertTrue(result.mainDeleted());
        assertEquals(1, adapter.deleteCalls);
        assertEquals(1, adapter.clearCacheCalls);
        assertEquals(1, adapter.loadSessionsCalls);
        assertEquals(List.of(
                "delete:s1:D:\\repo",
                "metadata:s1",
                "attachments:" + ProviderType.CLAUDE.value() + ":s1",
                "clear:D:\\repo",
                "load:D:\\repo"), eventsWithAdapter(adapter, events));
    }

    @Test
    public void deleteManyDeletesAllAndRefreshesOnce() {
        RecordingAdapter adapter = new RecordingAdapter(ProviderType.CODEX,
                "{\"success\":true,\"sessions\":[]}");
        adapter.deleteResult = new HistoryDeleteResult(true, 1);
        RecordingDispatchService dispatchService = new RecordingDispatchService();
        AtomicInteger metadataCount = new AtomicInteger();
        AtomicInteger attachmentCount = new AtomicInteger();
        HistoryWorkflowService workflow = workflow(new HistoryProviderRegistry(List.of(adapter)), dispatchService,
                () -> "D:\\repo",
                (provider, sessionId) -> attachmentCount.incrementAndGet(),
                sessionId -> metadataCount.incrementAndGet());

        HistoryBatchDeleteResult result = workflow.deleteMany(ProviderType.CODEX.value(), List.of("s1", "s2", "s3"));

        assertEquals(3, result.requested());
        assertEquals(3, result.mainDeleted());
        assertEquals(3, result.agentFilesDeleted());
        assertEquals(3, adapter.deleteCalls);
        assertEquals(1, adapter.clearCacheCalls);
        assertEquals(1, adapter.loadSessionsCalls);
        assertEquals(3, metadataCount.get());
        assertEquals(3, attachmentCount.get());
    }

    private static List<String> eventsWithAdapter(RecordingAdapter adapter, List<String> events) {
        List<String> combined = new ArrayList<>();
        combined.add(adapter.events.get(0));
        combined.addAll(events);
        combined.add(adapter.events.get(1));
        combined.add(adapter.events.get(2));
        return combined;
    }

    private static HistoryWorkflowService workflow(HistoryProviderRegistry registry,
                                                   RecordingDispatchService dispatchService,
                                                   java.util.function.Supplier<String> projectPathSupplier,
                                                   java.util.function.BiConsumer<String, String> attachmentCleanup,
                                                   java.util.function.Consumer<String> metadataCleanup) {
        return new HistoryWorkflowService(null, registry, new HistoryProjectPathResolver(),
                new NoOpEnhancer(), dispatchService, null, projectPathSupplier, attachmentCleanup, metadataCleanup);
    }

    private static final class NoOpEnhancer extends HistorySessionsJsonEnhancer {
        NoOpEnhancer() {
            super(null);
        }

        @Override
        String enhance(String provider, String rawJson) {
            return HistorySessionsJsonEnhancer.normalizeSessionsJson(rawJson);
        }
    }

    private static final class RecordingDispatchService extends HistoryLoadService {
        private String lastJson;
        private String lastError;

        RecordingDispatchService() {
            super(null);
        }

        @Override
        void dispatchHistoryData(String finalJson) {
            this.lastJson = finalJson;
        }

        @Override
        void dispatchHistoryDataError(String message) {
            this.lastError = message;
        }
    }

    private static final class RecordingAdapter implements HistoryProviderAdapter {
        private final ProviderType provider;
        private final String sessionsJson;
        private final List<String> events = new ArrayList<>();
        private int loadSessionsCalls;
        private int deleteCalls;
        private int clearCacheCalls;
        private HistoryDeleteResult deleteResult = HistoryDeleteResult.none();

        private RecordingAdapter(ProviderType provider, String sessionsJson) {
            this.provider = provider;
            this.sessionsJson = sessionsJson;
        }

        @Override
        public ProviderType provider() {
            return provider;
        }

        @Override
        public String loadSessionsJson(String projectPath) {
            loadSessionsCalls++;
            events.add("load:" + projectPath);
            return sessionsJson;
        }

        @Override
        public List<JsonObject> loadMessages(String sessionId, String projectPath) {
            return List.of();
        }

        @Override
        public HistoryDeleteResult deleteSession(String sessionId, String projectPath) throws IOException {
            deleteCalls++;
            events.add("delete:" + sessionId + ":" + projectPath);
            return deleteResult;
        }

        @Override
        public void clearCache(String projectPath) {
            clearCacheCalls++;
            events.add("clear:" + projectPath);
        }
    }
}
