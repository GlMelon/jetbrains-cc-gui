package com.github.claudecodegui.startup;

import com.github.claudecodegui.cli.compatibility.CliCompatibilityManifestSnapshot;
import com.github.claudecodegui.cli.compatibility.CliCompatibilityService;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

/** Refreshes the signed CLI compatibility manifest once per IDE process without blocking startup. */
public final class CliCompatibilityManifestUpdater implements ProjectActivity {

    private static final Logger LOG = Logger.getInstance(CliCompatibilityManifestUpdater.class);
    private static final AtomicBoolean REFRESH_STARTED = new AtomicBoolean();

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        if (!REFRESH_STARTED.compareAndSet(false, true)) {
            return Unit.INSTANCE;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            CliCompatibilityManifestSnapshot snapshot = CliCompatibilityService.getInstance().refreshManifest();
            LOG.info("CLI compatibility manifest ready: revision=" + snapshot.manifest().revision()
                    + ", source=" + snapshot.source());
        });
        return Unit.INSTANCE;
    }
}
