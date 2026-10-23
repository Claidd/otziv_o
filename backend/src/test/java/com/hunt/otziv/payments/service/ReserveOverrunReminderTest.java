package com.hunt.otziv.payments.service;

import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReserveOverrunReminderTest {
    @Mock PaymentLinkRepository paymentLinkRepository;
    @Mock PaymentIssueReminderService reminders;
    @Mock BusinessAuditService audit;
    @Mock JdbcTemplate jdbc;
    @InjectMocks PaymentAccountingReconciliationService service;

    @Test
    void validReceivedMoneyAndPendingExposureAreExplainedWithoutClosingActualExcess() {
        capacities(List.of(capacity(16L, 8_623_500, 8_060_000, 565_000, 1_500)));

        assertThat(service.reconcileReserveOverruns()).isEqualTo(1);

        var text = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(reminders).notifyOrderIssue(eq(26649L), eq("CONTRACTOR_RESERVE_OVERRUN"), eq(16L),
                eq("Ожидаемые переводы: проверьте остаток"), text.capture());
        assertThat(text.getValue()).contains("Специалист Виктория М. (профиль №16)",
                "Начислено: 86235 ₽", "подтверждённые поступления: 80600 ₽",
                "ожидаемые переводы: 5650 ₽", "переводов: 15 ₽",
                "Полученное начисления не превышает",
                "Верно подтверждённые поступления менять не нужно", "на следующей штатной сверке")
                .doesNotContain("заморожены").hasSizeLessThanOrEqualTo(650);
        verify(reminders, never()).completeOrderIssue(any(), any(), any());
        verifyNoInteractions(paymentLinkRepository, audit);
    }

    @Test
    void onlyProvenZeroExcessCompletesAllOldOrderCardsWhileCurrentAndMissingProfilesRemainOpen() {
        capacities(List.of(capacity(16L, 8_623_500, 8_060_000, 565_000, 1_500),
                capacity(17L, 100_000, 90_000, 10_000, 0)));
        doReturn(List.of(new PaymentAccountingReconciliationService.ReserveReminderSource(16L, 26649L),
                new PaymentAccountingReconciliationService.ReserveReminderSource(17L, 27000L),
                new PaymentAccountingReconciliationService.ReserveReminderSource(17L, 27001L),
                new PaymentAccountingReconciliationService.ReserveReminderSource(18L, 28000L)))
                .when(jdbc).query(contains("FROM personal_reminders"), any(RowMapper.class));

        assertThat(service.reconcileReserveOverruns()).isEqualTo(1);

        verify(reminders).completeOrderIssue(27000L, "CONTRACTOR_RESERVE_OVERRUN", 17L);
        verify(reminders).completeOrderIssue(27001L, "CONTRACTOR_RESERVE_OVERRUN", 17L);
        verify(reminders, never()).completeOrderIssue(eq(26649L), any(), any());
        verify(reminders, never()).completeOrderIssue(eq(28000L), any(), any());
        verifyNoInteractions(paymentLinkRepository, audit);
    }

    @Test
    void failedCapacityReadCannotCompleteAnyWarning() {
        doThrow(new IllegalStateException("unavailable"))
                .when(jdbc).query(contains("FROM contractor_payment_profiles"), any(RowMapper.class));

        assertThrows(IllegalStateException.class, service::reconcileReserveOverruns);

        verifyNoInteractions(reminders, paymentLinkRepository, audit);
    }

    @Test
    void actualConfirmedExcessIsNotDescribedAsOnlyFutureTransfers() {
        capacities(List.of(capacity(16L, 100_000, 110_000, 20_000, 30_000)));

        service.reconcileReserveOverruns();

        verify(reminders).notifyOrderIssue(eq(26649L), eq("CONTRACTOR_RESERVE_OVERRUN"), eq(16L),
                anyString(), argThat(text -> text.contains("уже превышают начисления на 100 ₽")
                        && !text.contains("превышение возникнет, только если")));
        verify(reminders, never()).completeOrderIssue(any(), any(), any());
    }

    private void capacities(List<PaymentAccountingReconciliationService.ReserveOverrun> rows) {
        doReturn(rows).when(jdbc).query(contains("FROM contractor_payment_profiles"), any(RowMapper.class));
    }

    private PaymentAccountingReconciliationService.ReserveOverrun capacity(
            long id, long accrued, long paid, long outstanding, long overrun) {
        return new PaymentAccountingReconciliationService.ReserveOverrun(
                id, "Виктория М.", "SPECIALIST", 26649L, accrued, paid, outstanding, overrun);
    }
}
