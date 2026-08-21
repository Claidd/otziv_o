package com.hunt.otziv.performers.service;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.contractor_payments.service.ContractorCompletionRepairTransactionService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentRuntimeSwitch;
import com.hunt.otziv.p_products.status.event.OrderStatusChangedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PerformerProductRewardZpListenerTest {

    @Mock private PerformerProductRewardZpService rewardZpService;
    @Mock private ContractorPaymentRuntimeSwitch runtimeSwitch;
    @Mock private ContractorCompletionRepairTransactionService completionRepairTransactionService;
    @InjectMocks private PerformerProductRewardZpListener listener;

    @Test
    void livePaymentUsesRequiresNewRepairRunnerAfterCommit() {
        when(runtimeSwitch.rewardAttributionLiveEnabled()).thenReturn(true);

        listener.onOrderPaid(new OrderStatusChangedEvent(91L, "Выставлен счет", "Оплачено", "Оплачено"));

        verify(completionRepairTransactionService).repairOrder(91L);
        verify(rewardZpService, never()).accrueForPaidOrder(91L);
    }

    @Test
    void livePublicationUsesRequiresNewRepairRunnerAfterCommit() {
        when(runtimeSwitch.rewardAttributionLiveEnabled()).thenReturn(true);

        listener.onOrderPaid(new OrderStatusChangedEvent(92L, "Новый", "Опубликовано", "Опубликовано"));

        verify(completionRepairTransactionService).repairOrder(92L);
        verify(rewardZpService, never()).accrueForPaidOrder(92L);
    }

    @Test
    void legacyModeKeepsOriginalPaidOrderAccrual() {
        when(runtimeSwitch.rewardAttributionLiveEnabled()).thenReturn(false);

        listener.onOrderPaid(new OrderStatusChangedEvent(93L, "Выставлен счет", "Оплачено", "Оплачено"));

        verify(rewardZpService).accrueForPaidOrder(93L);
        verify(completionRepairTransactionService, never()).repairOrder(93L);
    }
}