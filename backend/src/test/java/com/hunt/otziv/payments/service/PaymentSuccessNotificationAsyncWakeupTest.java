package com.hunt.otziv.payments.service;

import com.hunt.otziv.whatsapp.config.ClientNotificationAsyncConfig;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class PaymentSuccessNotificationAsyncWakeupTest {
    @Configuration @EnableAsync static class AsyncTestConfig {}

    @Test
    void badReviewCompletionAlsoReturnsWhileTheBackgroundWorkerIsBlocked() throws Exception {
        var repo = mock(com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository.class);
        var transaction = mock(com.hunt.otziv.bad_reviews.service.BadReviewTaskTransactionRunner.class);
        var sender = mock(com.hunt.otziv.client_messages.service.ScheduledClientMessageService.class);
        var order = new com.hunt.otziv.p_products.model.Order(); order.setId(50L);
        var task = com.hunt.otziv.bad_reviews.model.BadReviewTask.builder().id(7L).order(order)
                .status(com.hunt.otziv.bad_reviews.model.BadReviewTaskStatus.DONE).build();
        when(repo.findByIdForMutation(7L)).thenReturn(java.util.Optional.of(task));
        when(transaction.required(any())).thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(0)).get());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var caller = Thread.currentThread();
        doAnswer(call -> {
            assertThat(Thread.currentThread()).isNotSameAs(caller);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            entered.countDown(); release.await(10, TimeUnit.SECONDS); return null;
        }).when(sender).deliverBadReviewInvoiceImmediately(7L, 50L);
        var context = new AnnotationConfigApplicationContext();
        try {
            context.register(AsyncTestConfig.class, ClientNotificationAsyncConfig.class,
                    com.hunt.otziv.bad_reviews.service.BadReviewCompletionPostActionOrchestrator.class);
            context.registerBean(com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository.class, () -> repo);
            context.registerBean(com.hunt.otziv.bad_reviews.service.BadReviewTaskTransactionRunner.class, () -> transaction);
            context.getBeanFactory().registerSingleton("scheduledClientMessageService", sender);
            context.refresh();
            context.getBean(com.hunt.otziv.bad_reviews.service.BadReviewCompletionPostActionOrchestrator.class).deliverInvoice(7L, 50L);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(release.getCount()).isEqualTo(1);
        } finally { release.countDown(); context.close(); }
    }

    @Test
    void blockedProviderRunsOffTheCallerThreadAndSaturationNeverFallsBackToCallerRuns() throws Exception {
        var delivery = mock(PaymentSuccessNotificationDeliveryService.class);
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        when(delivery.tryDeliver(anyLong())).thenAnswer(invocation -> {
            worker.set(Thread.currentThread());
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return false; // Durable retry state remains the recovery mechanism.
        });
        var context = new AnnotationConfigApplicationContext();
        try {
            context.register(AsyncTestConfig.class, ClientNotificationAsyncConfig.class, PaymentSuccessNotificationAsyncWakeup.class);
            context.registerBean(PaymentSuccessNotificationDeliveryService.class, () -> delivery);
            context.refresh();
            var wakeup = context.getBean(PaymentSuccessNotificationAsyncWakeup.class);
            wakeup.dispatch(1);
            wakeup.dispatch(2);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(worker.get()).isNotSameAs(Thread.currentThread());
            assertThat(release.getCount()).isEqualTo(1); // Caller returned while provider is still blocked.
            for (int i = 0; i < 128; i++) wakeup.dispatch(i + 3);
            assertThatThrownBy(() -> wakeup.dispatch(999)).isInstanceOf(org.springframework.core.task.TaskRejectedException.class);
            verify(delivery, times(2)).tryDeliver(anyLong());
            context.getBean(ThreadPoolTaskExecutor.class).getThreadPoolExecutor().getQueue().clear();
        } finally {
            release.countDown();
            context.close();
        }
    }
}
