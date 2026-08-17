package com.hunt.otziv.contractor_payments.service;

import static org.mockito.Mockito.inOrder;

import com.hunt.otziv.contractor_payments.repository.ContractorCompletionRewardRepairStateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContractorCompletionRepairTransactionServiceTest {

    @Mock private ContractorCompletionRewardService completionRewardService;
    @Mock private ContractorCompletionRewardRepairStateRepository repairStateRepository;
    @InjectMocks private ContractorCompletionRepairTransactionService service;

    @Test
    void orderRepairAndStateCleanupShareOneOrderedOperation() {
        service.repairOrder(91L);

        var ordered = inOrder(completionRewardService, repairStateRepository);
        ordered.verify(completionRewardService).ensureOrderCompletionAccrual(91L);
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
