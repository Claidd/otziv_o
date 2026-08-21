package com.hunt.otziv.contractor_payments.service;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorCompletionRewardRepairStateRepository;
import com.hunt.otziv.p_products.model.Order;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContractorCompletionRepairTransactionServiceTest {

    @Mock private ContractorCompletionRewardService completionRewardService;
    @Mock private ContractorCompletionRewardRepairStateRepository repairStateRepository;
    @Mock private BadReviewTaskRepository badReviewTaskRepository;
    @InjectMocks private ContractorCompletionRepairTransactionService service;

    @Test
    void orderRepairAndStateCleanupShareOneOrderedOperation() {
        service.repairOrder(91L);

        var ordered = inOrder(completionRewardService, repairStateRepository);
        ordered.verify(completionRewardService).ensureOrderCompletionAccrual(91L);
        ordered.verify(repairStateRepository).deleteById(91L);
    }

    @Test
    void completedTaskRepairAndStateCleanupShareOneOrderedOperation() {
        BadReviewTask task = new BadReviewTask();
        Order order = new Order();
        order.setId(91L);
        task.setOrder(order);
        when(badReviewTaskRepository.findByIdForMutation(17L)).thenReturn(Optional.of(task));

        service.repairCompletedBadReviewTask(91L, 17L);

        var ordered = inOrder(badReviewTaskRepository, completionRewardService, repairStateRepository);
        ordered.verify(badReviewTaskRepository).findByIdForMutation(17L);
        ordered.verify(completionRewardService).ensureCompletedBadReviewTask(task);
        ordered.verify(repairStateRepository).deleteById(91L);
    }

    @Test
    void cancellationRepairAndStateCleanupShareOneOrderedOperation() {
        service.repairCanceledTask(91L, 17L);

        var ordered = inOrder(completionRewardService, repairStateRepository);
        ordered.verify(completionRewardService).adjustCanceledBadReviewTaskAccrual(91L, 17L);
        ordered.verify(repairStateRepository).deleteById(91L);
    }
}
