package com.hunt.otziv.contractor_payments.service;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.client_messages.repository.ScheduledClientMessageStateRepository;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAccountingAuthority;
import com.hunt.otziv.payments.service.PaymentIssueReminderService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class ContractorPaymentRuntimeHealthMonitorTest {

    @Mock private ContractorPaymentRuntimeSwitch runtimeSwitch;
    @Mock private ContractorPaymentRolloutStateService rolloutStateService;
    @Mock private ScheduledClientMessageStateRepository messageStateRepository;
    @Mock private PaymentIssueReminderService paymentIssueReminderService;
    @Mock private ContractorPaymentBusinessClock businessClock;

    private ContractorPaymentRuntimeHealthMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new ContractorPaymentRuntimeHealthMonitor(
                runtimeSwitch,
                rolloutStateService,
                messageStateRepository,
                paymentIssueReminderService,
                businessClock
        );
        when(businessClock.now()).thenReturn(LocalDateTime.of(2026, 8, 26, 4, 0));
        when(rolloutStateService.freshSnapshot()).thenReturn(activeRollout());
    }

    @Test
    void firstHealthyObservationExpeditesOnlyPreviouslyBlockedInvoices() {
        when(runtimeSwitch.liveRoutingEnabled()).thenReturn(true);

        monitor.monitor();

        verify(messageStateRepository).expediteLiveRoutingBlockedPaymentRetries(
                LocalDateTime.of(2026, 8, 26, 4, 0)
        );
        verify(paymentIssueReminderService, never()).notifyOrderIssue(
                org.mockito.ArgumentMatchers.any(Long.class),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void requestedButBlockedCreatesVisibleOrderIssuesAndRecoveryExpeditesOnce() {
        when(runtimeSwitch.liveRoutingEnabled()).thenReturn(false, true, true);
        when(runtimeSwitch.liveRoutingBlockers()).thenReturn(List.of("Идёт восстановление"));
        when(messageStateRepository.findLiveRoutingBlockedPaymentOrderIds(
                org.mockito.ArgumentMatchers.any(Pageable.class)
        )).thenReturn(List.of(11414L));

        monitor.monitor();
        monitor.monitor();
        monitor.monitor();

        verify(paymentIssueReminderService).notifyOrderIssue(
                eq(11414L),
                eq("PAYMENT_RUNTIME_BLOCKED"),
                eq(11414L),
                contains("11414"),
                contains("Идёт восстановление")
        );
        verify(messageStateRepository).expediteLiveRoutingBlockedPaymentRetries(
                LocalDateTime.of(2026, 8, 26, 4, 0)
        );
    }

    private ContractorPaymentRolloutStateService.Snapshot activeRollout() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 26, 4, 0);
        return new ContractorPaymentRolloutStateService.Snapshot(
                ContractorPaymentAccountingAuthority.COMPLETION,
                true,
                LocalDate.of(2026, 8, 20),
                now.minusDays(6),
                "owner",
                now,
                "owner",
                7L
        );
    }
}
