package com.hunt.otziv.payments;

import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.repository.PaymentAccountingMismatchView;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.service.PaymentAccountingReconciliationService;
import com.hunt.otziv.payments.service.PaymentIssueReminderService;
import java.math.BigDecimal;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentAccountingReconciliationServiceTest {

    @Mock
    private PaymentLinkRepository paymentLinkRepository;
    @Mock
    private PaymentIssueReminderService paymentIssueReminderService;
    @Mock
    private BusinessAuditService businessAuditService;
    @Mock
    private JdbcTemplate jdbcTemplate;
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

        service.reconcile();

        assertTrue(latest.getLastError().startsWith(PaymentAccountingReconciliationService.ERROR_PREFIX));
        verify(paymentLinkRepository).save(latest);
        verify(paymentIssueReminderService).notifyOrderIssue(
                eq(24378L),
                eq(PaymentAccountingReconciliationService.REMINDER_SOURCE),
                eq(24378L),
                eq("Нужна сверка оплаты заказа №24378"),
                contains("сумма активных чеков")
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

    @Test
    void orderFactQueryUsesAuditedReconciliationsWithoutChangingCashEvidence() throws Exception {
        Field field = PaymentAccountingReconciliationService.class
                .getDeclaredField("ORDER_FACT_MISMATCH_SQL");
        field.setAccessible(true);
        String sql = (String) field.get(null);

        assertThat(sql)
                .contains("FROM order_payment_reconciliations")
                .contains("SUM(adjustment_kopecks)")
                .contains("facts.reconciled_evidence_kopecks <> facts.payable_kopecks")
                .contains("facts.cash_link_count > 1 AND facts.reconciliation_count = 0");
    }

    private PaymentLink link(Long id, LocalDateTime paidAt) {
        PaymentLink link = new PaymentLink();
        link.setId(id);
        link.setStatus(PaymentLinkStatus.CONFIRMED);
        link.setPaidAt(paidAt);
        return link;
    }
}
