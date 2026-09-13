package com.hunt.otziv.client_chat_control.service;

import com.hunt.otziv.config.settings.api.ClientChatReviewSettings;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.util.ArrayDeque;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClientChatReviewPrefetchTransactionTest {
    @Test
    void enqueueOccursOnlyAfterCommitAndNeverAfterRollbackOrWithoutTransaction() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var queue = context.getBean(Queue.class);
            var template = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            template.executeWithoutResult(status -> {
                context.publishEvent(new ClientChatReviewPrefetch.Requested(1L));
                assertThat(queue.tasks).isEmpty();
            });
            assertThat(queue.tasks).hasSize(1);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            queue.tasks.clear();
            template.executeWithoutResult(status -> {
                context.publishEvent(new ClientChatReviewPrefetch.Requested(2L));
                status.setRollbackOnly();
            });
            context.publishEvent(new ClientChatReviewPrefetch.Requested(3L));
            assertThat(queue.tasks).isEmpty();
        }
    }
    @Test
    void providerRunsAfterSnapshotTransactionReleasesItsConnection() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var repository = context.getBean(com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository.class);
            when(repository.findReviewSnapshot(10L, com.hunt.otziv.client_chat_control.model.ClientChatUnansweredStatus.OPEN))
                    .thenAnswer(call -> {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
                        return java.util.Optional.of(new com.hunt.otziv.client_chat_control.dto.PreparedNoResponseReview(
                                10L, 20L, "Спасибо", java.time.LocalDateTime.now(), 30L, null));
                    });
            var reviews = context.getBean(ClientChatNoResponseAiReviewService.class);
            when(reviews.review("Спасибо")).thenAnswer(call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return new ClientChatNoResponseAiReviewService.Review(true, true, "NO_RESPONSE_NEEDED", 99, "fixture", "deepseek");
            });
            new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(status ->
                    context.publishEvent(new ClientChatReviewPrefetch.Requested(10L)));
            context.getBean(Queue.class).tasks.remove().run();
            verify(reviews).review("Спасибо");
        }
    }
    static class Queue { final ArrayDeque<Runnable> tasks = new ArrayDeque<>(); }
    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean Queue queue() { return new Queue(); }
        @Bean PlatformTransactionManager transactionManager() throws Exception {
            DataSource source = mock(DataSource.class);
            when(source.getConnection()).thenAnswer(call -> {
                Connection c = mock(Connection.class); when(c.getAutoCommit()).thenReturn(true); return c;
            });
            return new DataSourceTransactionManager(source);
        }
        @Bean com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository repository() {
            return mock(com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository.class);
        }
        @Bean ClientChatReviewPrefetchSnapshot snapshots(com.hunt.otziv.client_chat_control.repository.ClientChatUnansweredItemRepository repository) {
            return new ClientChatReviewPrefetchSnapshot(repository);
        }
        @Bean ClientChatNoResponseAiReviewService reviews() { return mock(ClientChatNoResponseAiReviewService.class); }
        @Bean ClientChatReviewPrefetch prefetch(Queue queue, ClientChatReviewPrefetchSnapshot snapshots, ClientChatNoResponseAiReviewService reviews) {
            var settings = mock(ClientChatReviewSettings.class);
            when(settings.prefetchEnabled()).thenReturn(true);
            return new ClientChatReviewPrefetch(snapshots,
                    reviews, new ClientChatResolutionPolicy(),
                    settings, queue.tasks::add, new SimpleMeterRegistry());
        }
    }
}
