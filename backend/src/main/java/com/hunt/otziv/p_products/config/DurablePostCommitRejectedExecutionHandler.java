package com.hunt.otziv.p_products.config;

import java.util.Objects;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import lombok.extern.slf4j.Slf4j;

/**
 * Drops post-commit work without reporting a false failure to the transaction
 * caller. Every executor using this handler is backed by a durable recovery or
 * backfill path, while the rejection remains visible in logs.
 */
@Slf4j
public final class DurablePostCommitRejectedExecutionHandler implements RejectedExecutionHandler {

    private final String executorName;

    public DurablePostCommitRejectedExecutionHandler(String executorName) {
        this.executorName = Objects.requireNonNull(executorName, "executorName");
    }

    @Override
    public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
        log.error(
                "Post-commit async task rejected; durable recovery will retry: "
                        + "executor={}, shutdown={}, terminated={}, poolSize={}, active={}, queued={}",
                executorName,
                executor.isShutdown(),
                executor.isTerminated(),
                executor.getPoolSize(),
                executor.getActiveCount(),
                executor.getQueue().size()
        );
    }
}
