package com.hunt.otziv.payments;

import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.repository.PaymentAccountingMismatchView;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.service.PaymentAccountingReconciliationService;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.service.UserService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentAccountingReconciliationServiceTest {

    @Mock
    private PaymentLinkRepository paymentLinkRepository;
    @Mock
    private PersonalReminderService personalReminderService;
    @Mock
    private UserService userService;
    @Mock
    private BusinessAuditService businessAuditService;
    @Mock
    private PaymentAccountingMismatchView mismatch;
    @InjectMocks
    private PaymentAccountingReconciliationService service;

    @Test
    void flagsLatestPaymentAndCreatesOnlyMissingReminder() {
        when(mismatch.getOrderId()).thenReturn(24378L);
        when(mismatch.getConfirmedKopecks()).thenReturn(BigDecimal.valueOf(200_000));
        when(mismatch.getCheckKopecks()).thenReturn(BigDecimal.valueOf(100_000));
        when(paymentLinkRepository.findAccountingMismatches(any())).thenReturn(List.of(mismatch));

        PaymentLink older = link(1L, LocalDateTime.now().minusDays(2));
        PaymentLink latest = link(2L, LocalDateTime.now().minusDays(1));
        when(paymentLinkRepository.findByOrder_IdAndStatusIn(eq(24378L), any()))
                .thenReturn(List.of(older, latest));

        User owner = user(10L);
        User admin = user(11L);
        when(userService.getAllOwners("ROLE_OWNER")).thenReturn(List.of(owner));
        when(userService.getAllOwners("ROLE_ADMIN")).thenReturn(List.of(admin));
        when(personalReminderService.hasOpenSystemReminder(
                owner,
                PaymentAccountingReconciliationService.REMINDER_SOURCE,
                24378L
        )).thenReturn(true);

        service.reconcile();

        assertTrue(latest.getLastError().startsWith(PaymentAccountingReconciliationService.ERROR_PREFIX));
        verify(paymentLinkRepository).save(latest);
        verify(personalReminderService, never()).createSystemReminderDueNow(
                eq(owner), anyString(), anyString(), anyString(), any(), any()
        );
        verify(personalReminderService).createSystemReminderDueNow(
                eq(admin), anyString(), anyString(),
                eq(PaymentAccountingReconciliationService.REMINDER_SOURCE),
                eq(24378L), eq(24378L)
        );
        verify(businessAuditService).recordSafely(
                eq("PAYMENT_ACCOUNTING_MISMATCH_DETECTED"),
                eq("PAYMENT_LINK"),
                eq(24378L),
                eq(24378L),
                eq(null),
                eq(100_000L),
                eq(200_000L),
                anyString()
        );
    }

    private PaymentLink link(Long id, LocalDateTime paidAt) {
        PaymentLink link = new PaymentLink();
        link.setId(id);
        link.setStatus(PaymentLinkStatus.CONFIRMED);
        link.setPaidAt(paidAt);
        return link;
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setActive(true);
        return user;
    }
}
