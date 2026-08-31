package com.github.claudecodegui.service.lifecycle;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Project-scoped lifecycle correlation and bounded event journal.
 *
 * <p>The service is deliberately passive: callers own the process and provide
 * the correlation they actually know. It never infers a tab, session, or
 * provider from UI state. Events after project disposal are rejected.</p>
 */
@Service(Service.Level.PROJECT)
public final class LifecycleObservabilityService implements Disposable {

    private static final int MAX_EVENTS = 256;

    private final String projectLifecycleId = UUID.randomUUID().toString();
    private final AtomicLong processGeneration = new AtomicLong();
    private final AtomicBoolean disposed = new AtomicBoolean();
    private final Deque<LifecycleEvent> events = new ArrayDeque<>(MAX_EVENTS);

    public LifecycleObservabilityService(@NotNull Project project) {
    }

    public static LifecycleObservabilityService getInstance(@NotNull Project project) {
        return project.getService(LifecycleObservabilityService.class);
    }

    public String projectLifecycleId() {
        return projectLifecycleId;
    }

    /** Allocates a project-local generation for one physical process spawn. */
    public long nextProcessGeneration() {
        return processGeneration.incrementAndGet();
    }

    public ProcessLifecycleCorrelation correlation(
            @Nullable String runtimeSessionEpoch,
            @Nullable Long responseTurnEpoch,
            @Nullable Long generation
    ) {
        return new ProcessLifecycleCorrelation(
                projectLifecycleId, runtimeSessionEpoch, responseTurnEpoch, generation);
    }

    public ProcessLifecycleMetadata metadata(
            @NotNull LifecycleProcessKind processKind,
            @Nullable String runtimeSessionEpoch,
            @Nullable Long responseTurnEpoch,
            @Nullable Long generation
    ) {
        return new ProcessLifecycleMetadata(
                processKind, correlation(runtimeSessionEpoch, responseTurnEpoch, generation));
    }

    /**
     * Records an event, returning false when the project has already been disposed.
     * The ring buffer is synchronized so snapshots are immutable and bounded.
     */
    public boolean record(
            @NotNull LifecycleEventType type,
            @Nullable ProcessLifecycleMetadata metadata,
            long pid,
            @Nullable String detail
    ) {
        if (disposed.get()) {
            return false;
        }
        synchronized (events) {
            if (disposed.get()) {
                return false;
            }
            if (events.size() == MAX_EVENTS) {
                events.removeFirst();
            }
            events.addLast(new LifecycleEvent(type, System.currentTimeMillis(), pid, metadata, detail));
            return true;
        }
    }

    public List<LifecycleEvent> snapshot() {
        synchronized (events) {
            return List.copyOf(new ArrayList<>(events));
        }
    }

    public boolean isDisposed() {
        return disposed.get();
    }

    @Override
    public void dispose() {
        if (disposed.compareAndSet(false, true)) {
            synchronized (events) {
                events.clear();
            }
        }
    }
}
