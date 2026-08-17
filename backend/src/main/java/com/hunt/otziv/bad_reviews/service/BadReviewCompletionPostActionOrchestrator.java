package com.hunt.otziv.bad_reviews.service;

import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.model.BadReviewTaskStatus;
import com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository;
import com.hunt.otziv.client_messages.service.ScheduledClientMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Starts post-commit delivery without an external send in the completion transaction. */
@Service
@Slf4j
@RequiredArgsConstructor
public class BadReviewCompletionPostActionOrchestrator {

    private final BadReviewTaskRepository taskRepository;
    private final BadReviewTaskTransactionRunner transactionRunner;
    private final ObjectProvider<ScheduledClientMessageService> scheduledMessageServiceProvider;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void deliverInvoice(Long taskId, Long expectedOrderId) {
        DeliverySeed seed = transactionRunner.required(() -> prepareSeed(taskId, expectedOrderId));
        if (seed == null) {
            return;
        }
        ScheduledClientMessageService service = scheduledMessageServiceProvider.getIfAvailable();
        if (service == null) {
            log.warn("Post-action счета не запущен: очередь недоступна, taskId={}, orderId={}", seed.taskId(), seed.orderId());
            return;
        }
        service.deliverBadReviewInvoiceImmediately(seed.taskId(), seed.orderId());
    }

    private DeliverySeed prepareSeed(Long taskId, Long expectedOrderId) {
        if (taskId == null || expectedOrderId == null) {
            return null;
        }
        BadReviewTask task = taskRepository.findByIdForMutation(taskId).orElse(null);
        Long actualOrderId = task != null && task.getOrder() != null ? task.getOrder().getId() : null;
        if (task == null || task.getStatus() != BadReviewTaskStatus.DONE || !expectedOrderId.equals(actualOrderId)) {
            log.warn("Post-action счета пропущен: задача или заказ изменились, taskId={}, expectedOrderId={}, actualOrderId={}",
                    taskId, expectedOrderId, actualOrderId);
            return null;
        }
        return new DeliverySeed(taskId, actualOrderId);
    }

    private record DeliverySeed(Long taskId, Long orderId) {
    }
}
