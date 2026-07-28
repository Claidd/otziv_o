package com.hunt.otziv.workload_shadow.lock;

public interface WorkloadShadowRecalculationLease extends AutoCloseable {

    void attachRun(long runId);

    void checkpoint(String phase);

    @Override
    void close();
}
