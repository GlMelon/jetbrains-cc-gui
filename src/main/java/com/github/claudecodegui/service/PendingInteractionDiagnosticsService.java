package com.github.claudecodegui.service;

import com.intellij.openapi.components.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Project-scoped aggregation of pending permission and tool interaction diagnostics. */
@Service(Service.Level.PROJECT)
public final class PendingInteractionDiagnosticsService {

    /** Immutable current counts contributed by one or more lifecycle owners. */
    public record Snapshot(
            int pendingPermissionRequests,
            int pendingToolCalls,
            int orphanToolResults
    ) {
        public Snapshot {
            pendingPermissionRequests = Math.max(0, pendingPermissionRequests);
            pendingToolCalls = Math.max(0, pendingToolCalls);
            orphanToolResults = Math.max(0, orphanToolResults);
        }

        public static Snapshot empty() {
            return new Snapshot(0, 0, 0);
        }
    }

    /** Lifecycle-bound contribution handle. Closing it removes the source from project totals. */
    public interface Source extends AutoCloseable {
        void update(Snapshot snapshot);

        @Override
        void close();
    }

    private static final Source NOOP_SOURCE = new Source() {
        @Override
        public void update(Snapshot snapshot) {
        }

        @Override
        public void close() {
        }
    };

    private final AtomicLong sourceSequence = new AtomicLong();
    private final Map<Long, Snapshot> sources = new ConcurrentHashMap<>();

    public Source registerSource() {
        long sourceId = sourceSequence.incrementAndGet();
        sources.put(sourceId, Snapshot.empty());
        return new Source() {
            private final AtomicBoolean closed = new AtomicBoolean();
            private final AtomicReference<Snapshot> current =
                    new AtomicReference<>(Snapshot.empty());

            @Override
            public void update(Snapshot snapshot) {
                if (closed.get()) {
                    return;
                }
                Snapshot safeSnapshot = snapshot == null ? Snapshot.empty() : snapshot;
                current.set(safeSnapshot);
                sources.computeIfPresent(sourceId, (ignored, previous) -> current.get());
            }

            @Override
            public void close() {
                if (closed.compareAndSet(false, true)) {
                    sources.remove(sourceId);
                }
            }
        };
    }

    public Snapshot snapshot() {
        long pendingPermissions = 0L;
        long pendingTools = 0L;
        long orphanResults = 0L;
        for (Snapshot source : sources.values()) {
            if (source == null) {
                continue;
            }
            pendingPermissions += source.pendingPermissionRequests();
            pendingTools += source.pendingToolCalls();
            orphanResults += source.orphanToolResults();
        }
        return new Snapshot(
                clampToInt(pendingPermissions),
                clampToInt(pendingTools),
                clampToInt(orphanResults));
    }

    public static Source noopSource() {
        return NOOP_SOURCE;
    }

    private static int clampToInt(long value) {
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, value);
    }
}
