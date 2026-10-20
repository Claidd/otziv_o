package com.hunt.otziv.personal_reminders.service;

import com.hunt.otziv.personal_reminders.repository.PersonalReminderRepository;
import com.hunt.otziv.scheduler.service.SchedulerLeaseService;
import com.hunt.otziv.scheduler.service.SchedulerLeaseService.Lease;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentFailClosedReminderJobTest {

    @Mock SchedulerLeaseService leases;
    @Mock PersonalReminderRepository reminders;
    @Mock PaymentFailClosedReminderReconciliationService reconciliation;
    @Mock PlatformTransactionManager transactionManager;

    @Test
    void dryRunScansBothSourcesWithoutSkippingHistoricalCards() {
        Lease lease = new Lease("payment-fail-closed-reminders", "test-owner", 1L);
        when(leases.tryAcquire(eq("payment-fail-closed-reminders"), any()))
                .thenReturn(Optional.of(lease));
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenAnswer(ignored -> new SimpleTransactionStatus());
        when(reminders.findOpenSourceIdsAfter(eq("PAYMENT_FAIL_CLOSED"), eq(0L), any(Pageable.class)))
                .thenReturn(List.of(1L));
        when(reminders.findOpenSourceIdsAfter(eq("PAYMENT_FAIL_CLOSED"), eq(1L), any(Pageable.class)))
                .thenReturn(List.of());
        when(reminders.findOpenSourceIdsAfter(eq("PAYMENT_LIVE_ROUTING_FAIL_CLOSED"), eq(0L),
                any(Pageable.class))).thenReturn(List.of(2L));
        when(reminders.findOpenSourceIdsAfter(eq("PAYMENT_LIVE_ROUTING_FAIL_CLOSED"), eq(2L),
                any(Pageable.class))).thenReturn(List.of());
        when(reconciliation.reconcile(1L, true))
                .thenReturn(new PaymentFailClosedReminderReconciliationService.Outcome(true, false,
                        "dry_run_confirmed_paid_order"));
        when(reconciliation.reconcile(2L, true))
                .thenReturn(new PaymentFailClosedReminderReconciliationService.Outcome(false, false,
                        "order_not_paid"));

        new PaymentFailClosedReminderJob(leases, reminders, reconciliation, transactionManager, true)
                .reconcile();

        verify(reconciliation).reconcile(1L, true);
        verify(reconciliation).reconcile(2L, true);
        verify(leases).release(lease);
    }
}
