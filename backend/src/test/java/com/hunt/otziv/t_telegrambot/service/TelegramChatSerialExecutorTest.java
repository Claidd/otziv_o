package com.hunt.otziv.t_telegrambot.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TelegramChatSerialExecutorTest {

    private final TelegramChatSerialExecutor executor = new TelegramChatSerialExecutor(4, 100);

    @AfterEach
    void shutDown() throws InterruptedException {
        executor.destroy();
    }

    @Test
    void slowWorkOfOneChatDoesNotHoldOtherChats() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch otherChatDone = new CountDownLatch(1);

        assertThat(executor.submit(1L, () -> await(release))).isTrue();
        assertThat(executor.submit(2L, otherChatDone::countDown)).isTrue();

        assertThat(otherChatDone.await(5, TimeUnit.SECONDS)).isTrue();
        release.countDown();
    }

    @Test
    void workOfOneChatKeepsTelegramOrderEvenAfterAFailure() throws InterruptedException {
        List<Integer> order = new CopyOnWriteArrayList<>();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);

        executor.submit(7L, () -> {
            await(release);
            order.add(1);
        });
        executor.submit(7L, () -> {
            order.add(2);
            throw new IllegalStateException("handler failed");
        });
        executor.submit(7L, () -> {
            order.add(3);
            done.countDown();
        });
        release.countDown();

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(order).containsExactly(1, 2, 3);
    }

    @Test
    void fullQueueHandsTheWorkBackToTheCaller() throws InterruptedException {
        TelegramChatSerialExecutor small = new TelegramChatSerialExecutor(1, 1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            assertThat(small.submit(1L, () -> await(release))).isTrue();
            assertThat(small.submit(2L, () -> { })).isFalse();
        } finally {
            release.countDown();
            small.destroy();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
