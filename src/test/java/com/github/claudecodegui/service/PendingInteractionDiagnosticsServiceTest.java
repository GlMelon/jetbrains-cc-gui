package com.github.claudecodegui.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PendingInteractionDiagnosticsServiceTest {

    @Test
    public void snapshotAggregatesCurrentContributionsFromMultipleSources() {
        PendingInteractionDiagnosticsService service = new PendingInteractionDiagnosticsService();
        PendingInteractionDiagnosticsService.Source first = service.registerSource();
        PendingInteractionDiagnosticsService.Source second = service.registerSource();

        first.update(new PendingInteractionDiagnosticsService.Snapshot(1, 2, 3));
        second.update(new PendingInteractionDiagnosticsService.Snapshot(4, 5, 6));

        assertEquals(new PendingInteractionDiagnosticsService.Snapshot(5, 7, 9), service.snapshot());

        first.update(new PendingInteractionDiagnosticsService.Snapshot(7, 8, 9));
        assertEquals(new PendingInteractionDiagnosticsService.Snapshot(11, 13, 15), service.snapshot());
    }

    @Test
    public void sourceNormalizesNullAndNegativeSnapshots() {
        PendingInteractionDiagnosticsService service = new PendingInteractionDiagnosticsService();
        PendingInteractionDiagnosticsService.Source source = service.registerSource();

        source.update(new PendingInteractionDiagnosticsService.Snapshot(-1, -2, -3));
        assertEquals(PendingInteractionDiagnosticsService.Snapshot.empty(), service.snapshot());

        source.update(null);
        assertEquals(PendingInteractionDiagnosticsService.Snapshot.empty(), service.snapshot());
    }

    @Test
    public void closingSourceRemovesContributionAndPreventsReactivation() {
        PendingInteractionDiagnosticsService service = new PendingInteractionDiagnosticsService();
        PendingInteractionDiagnosticsService.Source source = service.registerSource();
        source.update(new PendingInteractionDiagnosticsService.Snapshot(1, 2, 3));

        source.close();
        source.close();
        source.update(new PendingInteractionDiagnosticsService.Snapshot(4, 5, 6));

        assertEquals(PendingInteractionDiagnosticsService.Snapshot.empty(), service.snapshot());
    }
}
