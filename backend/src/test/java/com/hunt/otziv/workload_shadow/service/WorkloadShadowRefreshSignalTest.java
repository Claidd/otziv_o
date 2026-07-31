package com.hunt.otziv.workload_shadow.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorkloadShadowRefreshSignalTest {

    @Test
    void changesDuringRefreshRemainDirtyForTheNextRun() {
        WorkloadShadowRefreshSignal signal = new WorkloadShadowRefreshSignal();
        assertTrue(signal.isProjectionStale());

        WorkloadShadowRefreshSignal.RefreshToken first = signal.beginRefresh();
        signal.completeRefresh(first);
        assertFalse(signal.isProjectionStale());

        WorkloadShadowRefreshSignal.RefreshToken second = signal.beginRefresh();
        signal.markDirty();
        signal.completeRefresh(second);
        assertTrue(signal.isProjectionStale());

        WorkloadShadowRefreshSignal.RefreshToken third = signal.beginRefresh();
        signal.completeRefresh(third);
        assertFalse(signal.isProjectionStale());
    }

    @Test
    void failedRefreshAlwaysLeavesProjectionDirty() {
        WorkloadShadowRefreshSignal signal = new WorkloadShadowRefreshSignal();
        WorkloadShadowRefreshSignal.RefreshToken token = signal.beginRefresh();
        signal.completeRefresh(token);
        assertFalse(signal.isDirty());

        signal.beginRefresh();
        signal.failRefresh();

        assertTrue(signal.isDirty());
        assertTrue(signal.isProjectionStale());
    }
}
