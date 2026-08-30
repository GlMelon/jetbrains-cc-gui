package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.session.SessionCapabilityMetadataStore;
import com.github.claudecodegui.session.SessionNegotiatedCapabilities;
import com.github.claudecodegui.session.runtime.ProviderType;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
        adapter.capabilities = Set.of(HistoryCapability.DELETE);
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
    public void deleteRemovesStoredSessionCapabilities() throws Exception {
        RecordingAdapter adapter = new RecordingAdapter(ProviderType.CLAUDE,
                "{\"success\":true,\"sessions\":[]}");
        adapter.capabilities = Set.of(HistoryCapability.DELETE);
        adapter.deleteResult = new HistoryDeleteResult(true, 0);
        SessionCapabilityMetadataStore store = new SessionCapabilityMetadataStore(
                Files.createTempDirectory("session-capabilities-delete").resolve("metadata.json"));
        store.save(ProviderType.CLAUDE.value(), "s1", SessionNegotiatedCapabilities.cli(true, true, false), 123L);
        HistoryWorkflowService workflow = workflow(new HistoryProviderRegistry(List.of(adapter)),
                new RecordingDispatchService(), () -> "D:\\repo", null, null, store);

        workflow.deleteWithoutRefresh(ProviderType.CLAUDE.value(), "s1");

        assertNull(store.find(ProviderType.CLAUDE.value(), "s1"));
    }

    @Test
    public void deleteManyDeletesAllAndRefreshesOnce() {
        RecordingAdapter adapter = new RecordingAdapter(ProviderType.CODEX,
                "{\"success\":true,\"sessions\":[]}");
        adapter.capabilities = Set.of(HistoryCapability.DELETE);
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

    @Test
    public void deleteRejectsProviderWithoutDeleteCapability() {
        RecordingAdapter adapter = new RecordingAdapter(ProviderType.OPENCODE,
                "{\"success\":true,\"sessions\":[]}");
        adapter.capabilities = Set.of(HistoryCapability.ARCHIVE);
        HistoryWorkflowService workflow = workflow(new HistoryProviderRegistry(List.of(adapter)),
                new RecordingDispatchService(), () -> "D:\\repo", null, null);

        HistoryDeleteResult result = workflow.deleteOne(ProviderType.OPENCODE.value(), "s1");

        assertFalse(result.mainDeleted());
        assertEquals(0, adapter.deleteCalls);
    }

    @Test
    public void archiveManyDeduplicatesAndRefreshesWithoutMetadataCleanup() {
        RecordingAdapter adapter = new RecordingAdapter(ProviderType.OPENCODE,
                "{\"success\":true,\"sessions\":[]}");
        adapter.capabilities = Set.of(HistoryCapability.ARCHIVE);
        adapter.archiveResults.put("s1", true);
        adapter.archiveResults.put("s2", true);
        AtomicInteger metadataCount = new AtomicInteger();
        AtomicInteger attachmentCount = new AtomicInteger();
        HistoryWorkflowService workflow = workflow(new HistoryProviderRegistry(List.of(adapter)),
                new RecordingDispatchService(), () -> "D:\\repo",
                (provider, sessionId) -> attachmentCount.incrementAndGet(),
                sessionId -> metadataCount.incrementAndGet());

        HistoryBatchArchiveResult result = workflow.archiveMany(
                ProviderType.OPENCODE.value(), List.of("s1", "s1", "s2"));

        assertTrue(result.success());
        assertEquals(List.of("s1", "s2"), result.requestedSessionIds());
        assertEquals(List.of("s1", "s2"), result.archivedSessionIds());
        assertEquals(List.of(), result.failedSessionIds());
        assertEquals(2, adapter.archiveCalls);
        assertEquals(1, adapter.clearCacheCalls);
        assertEquals(1, adapter.loadSessionsCalls);
        assertEquals(0, metadataCount.get());
        assertEquals(0, attachmentCount.get());
    }

    @Test
    public void archiveManyReturnsPartialResultAndContinuesAfterFailure() {
        RecordingAdapter adapter = new RecordingAdapter(ProviderType.OPENCODE,
                "{\"success\":true,\"sessions\":[]}");
        adapter.capabilities = Set.of(HistoryCapability.ARCHIVE);
        adapter.archiveResults.put("s1", true);
        adapter.archiveResults.put("s2", false);
        HistoryWorkflowService workflow = workflow(new HistoryProviderRegistry(List.of(adapter)),
                new RecordingDispatchService(), () -> "D:\\repo", null, null);

        HistoryBatchArchiveResult result = workflow.archiveMany(
                ProviderType.OPENCODE.value(), List.of("s1", "s2", " "));

        assertFalse(result.success());
        assertEquals(List.of("s1"), result.archivedSessionIds());
        assertEquals(List.of("s2", " "), result.failedSessionIds());
        assertEquals(2, adapter.archiveCalls);
        assertEquals(1, adapter.clearCacheCalls);
        assertEquals(1, adapter.loadSessionsCalls);
    }

    @Test
    public void archiveManyRejectsProviderWithoutArchiveCapability() {
        RecordingAdapter adapter = new RecordingAdapter(ProviderType.CLAUDE,
                "{\"success\":true,\"sessions\":[]}");
        adapter.capabilities = Set.of(HistoryCapability.DELETE);
        HistoryWorkflowService workflow = workflow(new HistoryProviderRegistry(List.of(adapter)),
                new RecordingDispatchService(), () -> "D:\\repo", null, null);

        HistoryBatchArchiveResult result = workflow.archiveMany(
                ProviderType.CLAUDE.value(), List.of("s1"));

        assertFalse(result.success());
        assertEquals(List.of("s1"), result.failedSessionIds());
        assertEquals(0, adapter.archiveCalls);
        assertEquals(0, adapter.clearCacheCalls);
        assertEquals(0, adapter.loadSessionsCalls);
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
        return workflow(registry, dispatchService, projectPathSupplier, attachmentCleanup, metadataCleanup,
                SessionCapabilityMetadataStore.getInstance());
    }

    private static HistoryWorkflowService workflow(HistoryProviderRegistry registry,
                                                   RecordingDispatchService dispatchService,
                                                   java.util.function.Supplier<String> projectPathSupplier,
                                                   java.util.function.BiConsumer<String, String> attachmentCleanup,
                                                   java.util.function.Consumer<String> metadataCleanup,
                                                   SessionCapabilityMetadataStore capabilityMetadataStore) {
        return new HistoryWorkflowService(null, registry, new HistoryProjectPathResolver(),
                new NoOpEnhancer(), dispatchService, null, projectPathSupplier, attachmentCleanup, metadataCleanup,
                capabilityMetadataStore);
    }

    private static final class NoOpEnhancer extends HistorySessionsJsonEnhancer {
        NoOpEnhancer() {
            super(null);
        }

        @Override
        String enhance(String provider, String rawJson, HistoryCapabilities capabilities) {
            return HistorySessionsJsonEnhancer.normalizeSessionsJson(rawJson);
        }
    }

    private static final class RecordingDispatchService extends HistoryLoadService {
        private String lastJson;

        RecordingDispatchService() {
            super(null);
        }

        @Override
        void dispatchHistoryData(String finalJson) {
            this.lastJson = finalJson;
        }

        @Override
        void dispatchHistoryDataError(String message) {
        }
    }

    private static final class RecordingAdapter implements HistoryProviderAdapter {
        private final ProviderType provider;
        private final String sessionsJson;
        private final List<String> events = new ArrayList<>();
        private final Map<String, Boolean> archiveResults = new HashMap<>();
        private Set<HistoryCapability> capabilities = Set.of();
        private int loadSessionsCalls;
        private int deleteCalls;
        private int archiveCalls;
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
        public Set<HistoryCapability> capabilities() {
            return capabilities;
        }

        @Override
        public String loadSessionsJson(String projectPath) {
            loadSessionsCalls++;
            events.add("load:" + projectPath);
            return sessionsJson;
        }

        @Override
        public HistoryMessageBatch loadMessages(
                String sessionId,
                String projectPath,
                HistoryMessageReadPolicy policy
        ) {
            return HistoryMessageBatch.empty();
        }

        @Override
        public HistoryDeleteResult deleteSession(String sessionId, String projectPath) throws IOException {
            deleteCalls++;
            events.add("delete:" + sessionId + ":" + projectPath);
            return deleteResult;
        }

        @Override
        public HistoryArchiveResult archiveSession(String sessionId, String projectPath) throws IOException {
            archiveCalls++;
            return new HistoryArchiveResult(archiveResults.getOrDefault(sessionId, false));
        }

        @Override
        public void clearCache(String projectPath) {
            clearCacheCalls++;
            events.add("clear:" + projectPath);
        }
    }
}
