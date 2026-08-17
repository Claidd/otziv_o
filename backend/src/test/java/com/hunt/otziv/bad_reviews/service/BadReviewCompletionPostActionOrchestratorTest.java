package com.hunt.otziv.bad_reviews.service;

import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.model.BadReviewTaskStatus;
import com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository;
import com.hunt.otziv.client_messages.service.ScheduledClientMessageService;
import com.hunt.otziv.p_products.model.Order;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BadReviewCompletionPostActionOrchestratorTest {

    @Mock BadReviewTaskRepository taskRepository;
    @Mock BadReviewTaskTransactionRunner transactionRunner;
    @Mock ObjectProvider<ScheduledClientMessageService> scheduledMessageServiceProvider;
    @Mock ScheduledClientMessageService scheduledMessageService;
    @InjectMocks BadReviewCompletionPostActionOrchestrator orchestrator;

    @Test
    void doneTaskIsRecheckedInNewTransactionBeforeDeliveryStarts() {
        Order order = new Order();
        order.setId(50L);
        BadReviewTask task = BadReviewTask.builder()
                .id(7L).order(order).status(BadReviewTaskStatus.DONE).build();
        when(transactionRunner.required(any())).thenAnswer(invocation -> {
            Supplier<?> work = invocation.getArgument(0);
            return work.get();
        });
        when(taskRepository.findByIdForMutation(7L)).thenReturn(Optional.of(task));
        when(scheduledMessageServiceProvider.getIfAvailable()).thenReturn(scheduledMessageService);

        orchestrator.deliverInvoice(7L, 50L);

        verify(transactionRunner).required(any());
        verify(scheduledMessageService).deliverBadReviewInvoiceImmediately(7L, 50L);
    }

    @Test
    void nonDoneTaskCannotTriggerDelivery() {
        Order order = new Order();
        order.setId(50L);
        BadReviewTask task = BadReviewTask.builder()
                .id(7L).order(order).status(BadReviewTaskStatus.NEW).build();
        when(transactionRunner.required(any())).thenAnswer(invocation -> {
            Supplier<?> work = invocation.getArgument(0);
            return work.get();
        });
        when(taskRepository.findByIdForMutation(7L)).thenReturn(Optional.of(task));

        orchestrator.deliverInvoice(7L, 50L);

        verify(scheduledMessageService, never()).deliverBadReviewInvoiceImmediately(any(), any());
    }
}
