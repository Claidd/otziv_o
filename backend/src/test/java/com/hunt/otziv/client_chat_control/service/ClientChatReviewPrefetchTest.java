package com.hunt.otziv.client_chat_control.service;

import com.hunt.otziv.client_chat_control.dto.PreparedNoResponseReview;
import com.hunt.otziv.config.settings.api.ClientChatReviewSettings;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClientChatReviewPrefetchTest {
    private final ClientChatReviewPrefetchSnapshot snapshots = mock(ClientChatReviewPrefetchSnapshot.class);
    private final ClientChatNoResponseAiReviewService reviews = mock(ClientChatNoResponseAiReviewService.class);
    private final ClientChatReviewSettings settings = mock(ClientChatReviewSettings.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();

    private ClientChatReviewPrefetch service(Executor executor) {
        when(settings.prefetchEnabled()).thenReturn(true);
        return new ClientChatReviewPrefetch(snapshots, reviews, new ClientChatResolutionPolicy(), settings, executor, meters);
    }

    private PreparedNoResponseReview source(String text) {
        return new PreparedNoResponseReview(1L, 2L, text, LocalDateTime.now(), 3L, null);
    }

    @Test
    void deduplicatesQueuedWorkAndReadsLatestMessageOnlyWhenWorkerRuns() {
        var prefetch = service(queue::add);
        prefetch.afterCommit(new ClientChatReviewPrefetch.Requested(1L));
        prefetch.afterCommit(new ClientChatReviewPrefetch.Requested(1L));
        assertThat(queue).hasSize(1);
        verifyNoInteractions(snapshots, reviews);
        when(snapshots.read(1L)).thenReturn(Optional.of(source("Спасибо большое")));
        when(reviews.review("Спасибо большое")).thenReturn(new ClientChatNoResponseAiReviewService.Review(true, true, "NO_RESPONSE_NEEDED", 99, "fixture", "deepseek"));
        queue.remove().run();
        verify(reviews).review("Спасибо большое");
        prefetch.afterCommit(new ClientChatReviewPrefetch.Requested(1L));
        assertThat(queue).hasSize(1);
    }

    @Test
    void skipsClosedCardsQuestionsOldMessagesAndUnassignedSources() {
        var prefetch = service(queue::add);
        var now = LocalDateTime.now();
        var sources = java.util.List.of(Optional.<PreparedNoResponseReview>empty(),
                Optional.of(source("Когда начнете?")),
                Optional.of(new PreparedNoResponseReview(1L, 2L, "Спасибо", now.minusDays(2), 3L, null)),
                Optional.of(new PreparedNoResponseReview(1L, 2L, "Спасибо", now, null, null)));
        for (var value : sources) {
            when(snapshots.read(1L)).thenReturn(value);
            prefetch.afterCommit(new ClientChatReviewPrefetch.Requested(1L));
            queue.remove().run();
        }
        verifyNoInteractions(reviews);
    }

    @Test
    void providerFailureReleasesDeduplicationAndDoesNotEscapeWorker() {
        var prefetch = service(queue::add);
        when(snapshots.read(1L)).thenReturn(Optional.of(source("Спасибо")));
        when(reviews.review("Спасибо")).thenThrow(new IllegalStateException("fixture"));
        prefetch.afterCommit(new ClientChatReviewPrefetch.Requested(1L));
        assertThatCode(() -> queue.remove().run()).doesNotThrowAnyException();
        prefetch.afterCommit(new ClientChatReviewPrefetch.Requested(1L));
        assertThat(queue).hasSize(1);
        assertThat(meters.get("otziv.client.chat.review.prefetch").tag("result", "prepare-error").counter().count()).isEqualTo(1);
    }

    @Test
    void saturatedQueueNeverRunsProviderOnCallerAndCanRecoverLater() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var prefetch = service(task -> { if (calls.getAndIncrement() == 0) throw new RejectedExecutionException(); queue.add(task); });
        assertThatCode(() -> prefetch.afterCommit(new ClientChatReviewPrefetch.Requested(1L))).doesNotThrowAnyException();
        verifyNoInteractions(snapshots, reviews);
        prefetch.afterCommit(new ClientChatReviewPrefetch.Requested(1L));
        assertThat(queue).hasSize(1);
    }

    @Test
    void settingFailureCannotTurnSuccessfulMessageCommitIntoRequestFailure() {
        var prefetch = service(queue::add);
        when(settings.prefetchEnabled()).thenThrow(new IllegalStateException("fixture"));
        assertThatCode(() -> prefetch.afterCommit(new ClientChatReviewPrefetch.Requested(1L))).doesNotThrowAnyException();
        assertThat(queue).isEmpty();
    }

    @Test
    void disablingAfterEnqueueStopsProviderAndCachedEvidenceNeedsNoExternalCall() {
        var prefetch = service(queue::add);
        prefetch.afterCommit(new ClientChatReviewPrefetch.Requested(1L));
        when(settings.prefetchEnabled()).thenReturn(false);
        queue.remove().run();
        verifyNoInteractions(snapshots, reviews);
        when(settings.prefetchEnabled()).thenReturn(true);
        when(snapshots.read(1L)).thenReturn(Optional.of(source("Спасибо")));
        when(reviews.hasCachedReview("Спасибо")).thenReturn(true);
        prefetch.afterCommit(new ClientChatReviewPrefetch.Requested(1L));
        queue.remove().run();
        verify(reviews, never()).review(anyString());
    }
}
