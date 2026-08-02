package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_messages.model.ClientMessageScenario;
import com.hunt.otziv.client_messages.model.ClientMessageTargetType;
import com.hunt.otziv.client_messages.model.ScheduledClientMessageState;
import com.hunt.otziv.client_messages.model.ScheduledMessageStateStatus;
import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryBatch;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClientMessageSafetySchedulingTest {

    @Mock
    private ScheduledClientMessageStateRepository stateRepository;
    @Mock
    private AppSettingService appSettingService;
    @Mock
    private ClientMessageSlotPlanner slotPlanner;

    @Test
    void paymentSchedulerDoesNotRearmUncertainState() {
        LocalDateTime changedAt = LocalDateTime.of(2026, 8, 1, 14, 0);
        Order order = order(24753L, 100L, changedAt);
        ScheduledClientMessageState state = state(
                5819L,
                ClientMessageScenario.PAYMENT_INVOICE_RETRY,
                "order:24753:2026-08-01T14:00",
                "state_transaction_outcome_uncertain"
        );
        when(slotPlanner.nextAllowedAt(any(LocalDateTime.class), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(stateRepository.findByScenarioAndTargetKeyForUpdate(
                ClientMessageScenario.PAYMENT_INVOICE_RETRY,
                state.getTargetKey()
        )).thenReturn(Optional.of(state));

        new PaymentInvoiceRetryScheduler(stateRepository, appSettingService, slotPlanner).scheduleRetry(order);

        assertNull(state.getNextAttemptAt());
        assertEquals("state_transaction_outcome_uncertain", state.getLastErrorCode());
        verify(stateRepository, never()).save(any(ScheduledClientMessageState.class));
    }

    @Test
    void reviewRecoverySchedulerDoesNotRearmInProgressState() {
        Order order = order(24754L, 101L, LocalDateTime.of(2026, 8, 1, 14, 1));
        ReviewRecoveryBatch batch = ReviewRecoveryBatch.builder()
                .id(55L)
                .order(order)
                .build();
        ScheduledClientMessageState state = state(
                5820L,
                ClientMessageScenario.REVIEW_RECOVERY_NOTICE,
                "review-recovery:batch:55",
                "state_transaction_in_progress"
        );
        when(appSettingService.getBoolean(
                AppSettingService.CLIENT_MESSAGES_REVIEW_RECOVERY_NOTICE_ENABLED,
                true
        )).thenReturn(true);
        when(slotPlanner.nextAllowedAt(any(LocalDateTime.class), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(stateRepository.findByScenarioAndTargetKeyForUpdate(
                ClientMessageScenario.REVIEW_RECOVERY_NOTICE,
                state.getTargetKey()
        )).thenReturn(Optional.of(state));

        boolean scheduled = new ReviewRecoveryNoticeScheduler(
                stateRepository,
                appSettingService,
                slotPlanner
        ).scheduleNotice(batch);

        assertFalse(scheduled);
        assertNull(state.getNextAttemptAt());
        assertEquals("state_transaction_in_progress", state.getLastErrorCode());
        verify(stateRepository, never()).save(any(ScheduledClientMessageState.class));
    }

    private Order order(Long orderId, Long companyId, LocalDateTime changedAt) {
        Company company = new Company();
        company.setId(companyId);
        Order order = new Order();
        order.setId(orderId);
        order.setCompany(company);
        order.setStatusChangedAt(changedAt);
        return order;
    }

    private ScheduledClientMessageState state(
            Long stateId,
            ClientMessageScenario scenario,
            String targetKey,
            String errorCode
    ) {
        return ScheduledClientMessageState.builder()
                .id(stateId)
                .scenario(scenario)
                .targetType(ClientMessageTargetType.ORDER)
                .targetKey(targetKey)
                .status(ScheduledMessageStateStatus.ACTIVE)
                .lastErrorCode(errorCode)
                .nextAttemptAt(null)
                .build();
    }
}
