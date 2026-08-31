package com.github.claudecodegui.service.lifecycle;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class LifecycleObservabilityServiceTest {

    @Test
    public void projectLifecycleIdIsNonEmptyAndStable() {
        LifecycleObservabilityService service = new LifecycleObservabilityService(null);

        String first = service.projectLifecycleId();
        String second = service.projectLifecycleId();

        assertNotNull(first);
        assertFalse(first.isEmpty());
        assertSame(first, second);
    }

    @Test
    public void processGenerationsIncreaseMonotonically() {
        LifecycleObservabilityService service = new LifecycleObservabilityService(null);

        long first = service.nextProcessGeneration();
        long second = service.nextProcessGeneration();

        assertTrue(first > 0);
        assertTrue(second > first);
    }

    @Test
    public void metadataCarriesProcessAndCorrelationFields() {
        LifecycleObservabilityService service = new LifecycleObservabilityService(null);

        ProcessLifecycleMetadata metadata = service.metadata(
                LifecycleProcessKind.CLI_PERSISTENT, "session-epoch", 17L, 23L);

        assertEquals(LifecycleProcessKind.CLI_PERSISTENT, metadata.processKind());
        assertEquals(service.projectLifecycleId(), metadata.correlation().projectLifecycleId());
        assertEquals("session-epoch", metadata.correlation().runtimeSessionEpoch());
        assertEquals(Long.valueOf(17L), metadata.correlation().responseTurnEpoch());
        assertEquals(Long.valueOf(23L), metadata.correlation().processGeneration());
    }

    @Test
    public void recordRetainsAtMost256EventsAndEvictsOldest() {
        LifecycleObservabilityService service = new LifecycleObservabilityService(null);
        ProcessLifecycleMetadata metadata = service.metadata(
                LifecycleProcessKind.CHANNEL, "session", 1L, 2L);

        for (int i = 0; i < 256; i++) {
            assertTrue(service.record(LifecycleEventType.SPAWN, metadata, i, "detail-" + i));
        }
        assertEquals(256, service.snapshot().size());

        assertTrue(service.record(LifecycleEventType.EXIT, metadata, 256L, "detail-256"));
        List<LifecycleEvent> snapshot = service.snapshot();

        assertEquals(256, snapshot.size());
        assertEquals(1L, snapshot.get(0).pid());
        assertEquals("detail-256", snapshot.get(255).detail());
        assertEquals(LifecycleEventType.EXIT, snapshot.get(255).type());
    }

    @Test
    public void recordAcceptsNullMetadataAndDetail() {
        LifecycleObservabilityService service = new LifecycleObservabilityService(null);

        assertTrue(service.record(LifecycleEventType.DEGRADED, null, -1L, null));
        LifecycleEvent event = service.snapshot().get(0);

        assertNull(event.metadata());
        assertNull(event.detail());
        assertEquals(-1L, event.pid());
    }

    @Test
    public void snapshotIsIndependentAndUnmodifiable() {
        LifecycleObservabilityService service = new LifecycleObservabilityService(null);
        assertTrue(service.record(LifecycleEventType.SPAWN, null, 7L, "spawn"));

        List<LifecycleEvent> first = service.snapshot();
        List<LifecycleEvent> second = service.snapshot();

        assertNotSame(first, second);
        assertEquals(first, second);
        try {
            first.clear();
            fail("Expected snapshot to be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
        assertEquals(1, service.snapshot().size());
    }

    @Test
    public void disposeRejectsRecordsAndClearsSnapshot() {
        LifecycleObservabilityService service = new LifecycleObservabilityService(null);
        assertTrue(service.record(LifecycleEventType.SPAWN, null, 7L, "spawn"));

        service.dispose();

        assertTrue(service.isDisposed());
        assertFalse(service.record(LifecycleEventType.EXIT, null, 7L, "exit"));
        assertTrue(service.snapshot().isEmpty());
        assertFalse(service.record(LifecycleEventType.SPAWN, null, 8L, "late"));
    }

    @Test
    public void disposeIsIdempotent() {
        LifecycleObservabilityService service = new LifecycleObservabilityService(null);

        service.dispose();
        service.dispose();

        assertTrue(service.isDisposed());
        assertTrue(service.snapshot().isEmpty());
    }
}
