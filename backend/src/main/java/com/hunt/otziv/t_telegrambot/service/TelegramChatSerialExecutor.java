package com.hunt.otziv.t_telegrambot.service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runs Telegram update work off the single polling handler thread.
 *
 * <p>Work for one chat keeps the order in which Telegram delivered it; different chats run in
 * parallel. Without it, one audit answer waiting minutes for the AI check stopped the bot for
 * every chat: workers, payments and owner decisions included.
 */
@Component
@Slf4j
public class TelegramChatSerialExecutor implements DisposableBean {

    private final ExecutorService pool;
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final int maxQueued;

    public TelegramChatSerialExecutor(
            @Value("${telegram.bot.chat-workers:6}") int threads,
            @Value("${telegram.bot.chat-max-queued:500}") int maxQueued
    ) {
        AtomicInteger number = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(Math.max(1, threads), runnable -> {
            Thread thread = new Thread(runnable, "telegram-chat-" + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.maxQueued = Math.max(1, maxQueued);
    }

    /**
     * Queues the task behind earlier work of the same chat.
     *
     * @return false when the queue is full; the caller then runs the task itself
     */
    public boolean submit(long chatId, Runnable task) {
        if (queued.incrementAndGet() > maxQueued) {
            queued.decrementAndGet();
            log.warn("Telegram chat work queue is full ({}), handling chat {} inline", maxQueued, chatId);
            return false;
        }
        Runnable guarded = () -> {
            try {
                task.run();
            } catch (RuntimeException | Error failure) {
                log.error("Telegram update failed chat={}: {}", chatId, failure.getMessage(), failure);
            } finally {
                queued.decrementAndGet();
            }
        };
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] created = new CompletableFuture[1];
        try {
            tails.compute(chatId, (key, tail) -> {
                CompletableFuture<Void> previous = tail == null ? CompletableFuture.completedFuture(null) : tail;
                created[0] = previous.exceptionally(ignored -> null).thenRunAsync(guarded, pool);
                return created[0];
            });
        } catch (RuntimeException rejected) {
            queued.decrementAndGet();
            log.warn("Telegram chat work rejected for chat {}: {}", chatId, rejected.getMessage());
            return false;
        }
        // Registered outside compute(): an already finished future runs the callback immediately.
        CompletableFuture<Void> mine = created[0];
        mine.whenComplete((ignored, failure) -> tails.remove(chatId, mine));
        return true;
    }

    int pending() {
        return queued.get();
    }

    @Override
    public void destroy() throws InterruptedException {
        pool.shutdown();
        if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
            pool.shutdownNow();
        }
    }
}
