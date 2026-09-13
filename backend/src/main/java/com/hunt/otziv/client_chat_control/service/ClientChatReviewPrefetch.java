package com.hunt.otziv.client_chat_control.service;

import com.hunt.otziv.config.settings.api.ClientChatReviewSettings;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Best-effort preparation only. The authorized action still validates and closes the card. */
@Service
@Slf4j
public class ClientChatReviewPrefetch {
    private final ClientChatReviewPrefetchSnapshot snapshots;
    private final ClientChatNoResponseAiReviewService reviews;
    private final ClientChatResolutionPolicy policy;
    private final ClientChatReviewSettings settings;
    private final Executor executor;
    private final MeterRegistry meters;
    private final Set<Long> pending = ConcurrentHashMap.newKeySet();

    public ClientChatReviewPrefetch(ClientChatReviewPrefetchSnapshot snapshots,
            ClientChatNoResponseAiReviewService reviews, ClientChatResolutionPolicy policy,
            ClientChatReviewSettings settings, @Qualifier("clientChatReviewPrefetchExecutor") Executor executor,
            MeterRegistry meters) {
        this.snapshots = snapshots;
        this.reviews = reviews;
        this.policy = policy;
        this.settings = settings;
        this.executor = executor;
        this.meters = meters;
    }

    public record Requested(Long itemId) {}

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(Requested event) {
        enqueue(event.itemId());
    }

    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT1M")
    public void recoverRecent() {
        try {
            if (!enabled()) return;
            // Bounded restart/overflow recovery; older cards retain the synchronous fallback.
            for (Long id : snapshots.recent(LocalDateTime.now().minusHours(24))) enqueue(id);
        } catch (RuntimeException exception) {
            failed("recovery-error", exception);
        }
    }

    private void enqueue(Long itemId) {
        if (itemId == null) return;
        boolean added = false;
        try {
            if (!enabled() || !pending.add(itemId)) return;
            added = true;
            executor.execute(() -> prepare(itemId));
        } catch (RejectedExecutionException exception) {
            if (added) pending.remove(itemId);
            count("queue-full");
        } catch (RuntimeException exception) {
            if (added) pending.remove(itemId);
            failed("enqueue-error", exception);
        }
    }

    private void prepare(Long itemId) {
        try {
            if (!enabled()) return;
            var source = snapshots.read(itemId).orElse(null);
            if (source == null || source.managerId() == null || source.messageId() == null
                    || source.messageAt() == null || source.messageAt().isBefore(LocalDateTime.now().minusHours(24))
                    || policy.rejectsNoResponse(policy.assess(source.messageText()))) {
                count("ineligible");
                return;
            }
            if (reviews.hasCachedReview(source.messageText())) {
                count("cached");
                return;
            }
            var sample = io.micrometer.core.instrument.Timer.start(meters);
            try {
                var review = reviews.review(source.messageText());
                count(review.checked() ? "ready" : "unavailable");
            } finally {
                sample.stop(meters.timer("otziv.client.chat.review.prefetch.duration"));
            }
        } catch (RuntimeException exception) {
            failed("prepare-error", exception);
        } finally {
            pending.remove(itemId);
        }
    }

    private boolean enabled() {
        return settings.prefetchEnabled();
    }

    private void failed(String result, RuntimeException exception) {
        // Do not include client text, provider errors or identifiers in logs or metric labels.
        log.warn("Client chat review prefetch failed; result={}, errorType={}", result, exception.getClass().getSimpleName());
        count(result);
    }

    private void count(String result) {
        meters.counter("otziv.client.chat.review.prefetch", "result", result).increment();
    }
}
