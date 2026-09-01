package com.hunt.otziv.p_products.config;

import com.hunt.otziv.p_products.next_order.config.NextOrderAutomationAsyncConfig;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PostCommitExecutorRejectionContractTest {

    @Test
    void nextOrderExecutorDropsShutdownAndSaturatedSubmissionsWithoutThrowing() {
        assertSafeRejection(() -> new NextOrderAutomationAsyncConfig().nextOrderAutomationExecutor());
    }

    @Test
    void orderPaymentExecutorDropsShutdownAndSaturatedSubmissionsWithoutThrowing() {
        assertSafeRejection(() -> new OrderPaymentPostCommitAsyncConfig().orderPaymentPostCommitExecutor());
    }

    private void assertSafeRejection(Supplier<TaskExecutor> factory) {
        ThreadPoolTaskExecutor shutdownExecutor = (ThreadPoolTaskExecutor) factory.get();
        AtomicBoolean shutdownTaskRan = new AtomicBoolean();
        shutdownExecutor.shutdown();

        assertDoesNotThrow(() -> shutdownExecutor.execute(() -> shutdownTaskRan.set(true)));
        assertFalse(shutdownTaskRan.get());

        ThreadPoolTaskExecutor saturatedExecutor = (ThreadPoolTaskExecutor) factory.get();
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        Runnable blocker = () -> {
            try {
                releaseWorkers.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        try {
            saturatedExecutor.execute(blocker);
            while (saturatedExecutor.getThreadPoolExecutor().getQueue().remainingCapacity() > 0) {
                saturatedExecutor.execute(() -> { });
            }
            while (saturatedExecutor.getPoolSize() < saturatedExecutor.getMaxPoolSize()) {
                saturatedExecutor.execute(blocker);
            }

            AtomicBoolean saturatedTaskRan = new AtomicBoolean();
            assertDoesNotThrow(() -> saturatedExecutor.execute(() -> saturatedTaskRan.set(true)));
            assertFalse(saturatedTaskRan.get());
        } finally {
            releaseWorkers.countDown();
            saturatedExecutor.shutdown();
        }
    }
}
