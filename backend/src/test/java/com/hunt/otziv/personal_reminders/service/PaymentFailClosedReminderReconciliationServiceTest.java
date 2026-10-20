package com.hunt.otziv.personal_reminders.service;

import com.hunt.otziv.payments.api.PaymentIssueEvidenceOperations;
import com.hunt.otziv.payments.api.PaymentIssueEvidenceOperations.Evidence;
import com.hunt.otziv.personal_reminders.model.PersonalReminder;
import com.hunt.otziv.personal_reminders.repository.PersonalReminderRepository;
import com.hunt.otziv.personal_reminders.repository.PersonalReminderRepository.ReconciliationCandidate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentFailClosedReminderReconciliationServiceTest {

    @Mock PersonalReminderRepository reminders;
    @Mock PaymentIssueEvidenceOperations evidenceReader;
    @InjectMocks PaymentFailClosedReminderReconciliationService service;

    @Test
    void closesOnlyOneVerifiedPaidOrderCardAndKeepsItsHistory() {
        PersonalReminder card = card("PAYMENT_FAIL_CLOSED", 27_202L, 25_000L);
        candidate(1L, "PAYMENT_FAIL_CLOSED", 27_202L);
        when(evidenceReader.lockOrder(27_202L)).thenReturn(true);
        when(reminders.findOpenByIdForUpdate(1L)).thenReturn(Optional.of(card));
        when(evidenceReader.confirmedPaidOrder(27_202L))
                .thenReturn(new Evidence(true, "confirmed_paid_order", 8_477L));

        var result = service.reconcile(1L, false);

        assertTrue(result.eligible());
        assertTrue(result.closed());
        assertNotNull(card.getCompletedAt());
        verify(reminders).save(card);
    }

    @Test
    void dryRunShowsEligibilityWithoutCompletingCard() {
        PersonalReminder card = card("PAYMENT_LIVE_ROUTING_FAIL_CLOSED", 27_202L, 27_202L);
        candidate(2L, "PAYMENT_LIVE_ROUTING_FAIL_CLOSED", 27_202L);
        when(evidenceReader.lockOrder(27_202L)).thenReturn(true);
        when(reminders.findOpenByIdForUpdate(2L)).thenReturn(Optional.of(card));
        when(evidenceReader.confirmedPaidOrder(27_202L))
                .thenReturn(new Evidence(true, "confirmed_paid_order", 8_477L));

        var result = service.reconcile(2L, true);

        assertTrue(result.eligible());
        assertFalse(result.closed());
        assertNull(card.getCompletedAt());
        verify(reminders, never()).save(card);
    }

    @Test
    void unpaidOrDisputedOrderKeepsAlertOpen() {
        PersonalReminder card = card("PAYMENT_FAIL_CLOSED", 27_203L, 27_203L);
        candidate(3L, "PAYMENT_FAIL_CLOSED", 27_203L);
        when(evidenceReader.lockOrder(27_203L)).thenReturn(true);
        when(reminders.findOpenByIdForUpdate(3L)).thenReturn(Optional.of(card));
        when(evidenceReader.confirmedPaidOrder(27_203L))
                .thenReturn(new Evidence(false, "order_not_paid", null));

        var result = service.reconcile(3L, false);

        assertFalse(result.eligible());
        assertFalse(result.closed());
        assertNull(card.getCompletedAt());
        verify(reminders, never()).save(card);
    }

    @Test
    void unrelatedOrAlreadyClosedReminderNeverReadsFinancialState() {
        candidate(4L, "PAYMENT_RETURN_RECONCILIATION", 27_202L);

        var result = service.reconcile(4L, false);

        assertFalse(result.eligible());
        verifyNoInteractions(evidenceReader);
        verify(reminders, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void sharedCommonInvoiceSourceDoesNotChangeOtherOrders() {
        PersonalReminder paid = card("PAYMENT_FAIL_CLOSED", 27_202L, 100L);
        PersonalReminder unpaid = card("PAYMENT_FAIL_CLOSED", 27_203L, 100L);
        candidate(5L, "PAYMENT_FAIL_CLOSED", 27_202L);
        candidate(6L, "PAYMENT_FAIL_CLOSED", 27_203L);
        when(evidenceReader.lockOrder(27_202L)).thenReturn(true);
        when(evidenceReader.lockOrder(27_203L)).thenReturn(true);
        when(reminders.findOpenByIdForUpdate(5L)).thenReturn(Optional.of(paid));
        when(reminders.findOpenByIdForUpdate(6L)).thenReturn(Optional.of(unpaid));
        when(evidenceReader.confirmedPaidOrder(27_202L))
                .thenReturn(new Evidence(true, "confirmed_paid_order", 8_477L));
        when(evidenceReader.confirmedPaidOrder(27_203L))
                .thenReturn(new Evidence(false, "order_not_paid", null));

        service.reconcile(5L, false);
        service.reconcile(6L, false);

        assertNotNull(paid.getCompletedAt());
        assertNull(unpaid.getCompletedAt());
        verify(reminders).save(paid);
        verify(reminders, never()).save(unpaid);
    }

    @Test
    void changedSourceOrderAfterOrderLockIsNeverClosed() {
        candidate(7L, "PAYMENT_FAIL_CLOSED", 27_202L);
        when(evidenceReader.lockOrder(27_202L)).thenReturn(true);
        when(reminders.findOpenByIdForUpdate(7L))
                .thenReturn(Optional.of(card("PAYMENT_FAIL_CLOSED", 27_203L, 27_203L)));

        var result = service.reconcile(7L, false);

        assertFalse(result.eligible());
        verify(evidenceReader, never()).confirmedPaidOrder(27_202L);
        verify(reminders, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private void candidate(Long reminderId, String source, Long orderId) {
        ReconciliationCandidate candidate = mock(ReconciliationCandidate.class);
        when(candidate.getSourceType()).thenReturn(source);
        if (PaymentFailClosedReminderReconciliationService.SOURCES.contains(source)) {
            when(candidate.getSourceOrderId()).thenReturn(orderId);
        }
        when(reminders.findOpenReconciliationCandidate(reminderId)).thenReturn(Optional.of(candidate));
    }

    private PersonalReminder card(String source, Long orderId, Long sourceId) {
        PersonalReminder reminder = new PersonalReminder();
        reminder.setSourceType(source);
        reminder.setSourceOrderId(orderId);
        reminder.setSourceId(sourceId);
        return reminder;
    }
}
