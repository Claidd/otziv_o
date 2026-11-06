package com.hunt.otziv.common_billing.service;

import com.hunt.otziv.common_billing.model.CommonBillingAccount;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.config.settings.api.BackgroundAutomationSettings;
import com.hunt.otziv.payments.model.InvoicePaymentMode;
import com.hunt.otziv.scheduler.service.SchedulerLeaseService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import static com.hunt.otziv.config.settings.api.BackgroundAutomationSettings.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommonInvoiceOverdueEscalationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 6, 12, 0);

    private final CommonInvoiceRepository invoices = mock(CommonInvoiceRepository.class);
    private final CommonInvoiceArchiveWorkflow workflow = mock(CommonInvoiceArchiveWorkflow.class);
    private final BackgroundAutomationSettings settings = mock(BackgroundAutomationSettings.class);
    private final CommonInvoiceOverdueEscalation escalation = new CommonInvoiceOverdueEscalation(
            invoices, workflow, settings, mock(SchedulerLeaseService.class), mock(PlatformTransactionManager.class));

    @BeforeEach
    void switchesOn() {
        when(settings.getBooleanFreshFailClosed(anyString(), anyBoolean())).thenReturn(true);
        when(settings.getStringFresh(eq(CLIENT_MESSAGES_PAUSED_UNTIL), any())).thenReturn("");
        when(settings.getStringFresh(eq(CommonInvoiceOverdueEscalation.OVERDUE_DAYS), any())).thenReturn("30");
    }

    @Test
    void everyAutoresponderSwitchMustAllowTheMove() {
        assertThat(escalation.escalationAllowed(NOW)).isTrue();
        for (String key : List.of(CLIENT_MESSAGES_WORKER_ENABLED, CLIENT_MESSAGES_PAYMENT_REMINDER_ENABLED,
                CLIENT_MESSAGES_PAYMENT_OVERDUE_ENABLED, CommonInvoiceOverdueEscalation.LIVE_ENABLED)) {
            when(settings.getBooleanFreshFailClosed(eq(key), anyBoolean())).thenReturn(false);
            assertThat(escalation.escalationAllowed(NOW)).as(key).isFalse();
            when(settings.getBooleanFreshFailClosed(eq(key), anyBoolean())).thenReturn(true);
        }
        when(settings.getStringFresh(eq(CLIENT_MESSAGES_PAUSED_UNTIL), any())).thenReturn(NOW.plusHours(1).toString());
        assertThat(escalation.escalationAllowed(NOW)).as("paused").isFalse();
        when(settings.getStringFresh(eq(CLIENT_MESSAGES_PAUSED_UNTIL), any())).thenReturn(NOW.minusHours(1).toString());
        assertThat(escalation.escalationAllowed(NOW)).as("pause over").isTrue();
        when(settings.getBooleanFreshFailClosed(eq(CLIENT_MESSAGES_WORKER_ENABLED), anyBoolean()))
                .thenThrow(new IllegalStateException("settings unavailable"));
        assertThat(escalation.escalationAllowed(NOW)).as("fail closed").isFalse();
    }

    @Test
    void noticesListOnlyAutomaticRouteInvoicesAndStopWhileSwitchedOff() {
        var due = invoice(1L, CommonInvoiceStatus.REMINDER, NOW.minusDays(40), null);
        var paper = invoice(2L, CommonInvoiceStatus.INVOICED, NOW.minusDays(40), null);
        paper.setInvoicePaymentMode(InvoicePaymentMode.OWNER_PAPER_INVOICE);
        var reported = invoice(3L, CommonInvoiceStatus.REMINDER, NOW.minusDays(40), null);
        reported.setClientReportedAt(NOW.minusDays(1));
        when(invoices.findOverdueNoticeCandidates(any(), eq(NOW.minusDays(28)), any()))
                .thenReturn(List.of(new Object[]{due, 7L}, new Object[]{paper, 7L}, new Object[]{reported, 7L}));

        var pending = escalation.pendingNotices(NOW);

        assertThat(pending).singleElement().satisfies(notice -> {
            assertThat(notice.invoiceId()).isEqualTo(1L);
            assertThat(notice.managerId()).isEqualTo(7L);
            assertThat(notice.unpaidDays()).isEqualTo(40);
            assertThat(notice.remainingKopecks()).isEqualTo(2_300_000);
            assertThat(notice.moveNotBefore()).as("long overdue: two days after the notice").isEqualTo(NOW.plusDays(2));
        });
        when(settings.getBooleanFreshFailClosed(eq(CommonInvoiceOverdueEscalation.LIVE_ENABLED), anyBoolean()))
                .thenReturn(false);
        assertThat(escalation.pendingNotices(NOW)).isEmpty();
    }

    @Test
    void noticeBelongsOnlyToTheSendingCycleThatWasListed() {
        var invoice = invoice(1L, CommonInvoiceStatus.REMINDER, NOW.minusDays(29), null);
        when(invoices.findByIdForUpdate(1L)).thenReturn(Optional.of(invoice));

        assertThat(escalation.recordNotice(1L, NOW.minusDays(5), NOW)).as("resent since listing").isFalse();
        assertThat(escalation.recordNotice(1L, invoice.getSentAt(), NOW)).isTrue();
        assertThat(invoice.getOverdueNoticeAt()).isEqualTo(NOW);
        assertThat(escalation.recordNotice(1L, invoice.getSentAt(), NOW.plusHours(1))).as("no duplicate").isFalse();
        assertThat(invoice.getOverdueNoticeAt()).isEqualTo(NOW);
    }

    @Test
    void moveWaitsForTheOverduePeriodAndTwoDaysAfterThisCyclesNotice() {
        var invoice = invoice(1L, CommonInvoiceStatus.REMINDER, NOW.minusDays(40), NOW.minusDays(2).minusMinutes(1));
        assertThat(escalation.stillDue(invoice, NOW, 30)).isTrue();

        invoice.setOverdueNoticeAt(NOW.minusDays(1));
        assertThat(escalation.stillDue(invoice, NOW, 30)).as("notice too recent").isFalse();
        invoice.setOverdueNoticeAt(NOW.minusDays(41));
        assertThat(escalation.stillDue(invoice, NOW, 30)).as("notice of an earlier cycle").isFalse();
        invoice.setOverdueNoticeAt(NOW.minusDays(3));
        invoice.setSentAt(NOW.minusDays(29));
        assertThat(escalation.stillDue(invoice, NOW, 30)).as("overdue period not over").isFalse();
        invoice.setSentAt(NOW.minusDays(40));
        invoice.setPaidKopecks(100);
        assertThat(escalation.stillDue(invoice, NOW, 30)).as("part paid stays with the manager").isFalse();
        invoice.setPaidKopecks(0);
        invoice.setStatus(CommonInvoiceStatus.PAID);
        assertThat(escalation.stillDue(invoice, NOW, 30)).as("paid meanwhile").isFalse();
        invoice.setStatus(CommonInvoiceStatus.REMINDER);
        when(settings.getStringFresh(eq(CLIENT_MESSAGES_PAUSED_UNTIL), any())).thenReturn(NOW.plusDays(1).toString());
        assertThat(escalation.stillDue(invoice, NOW, 30)).as("paused under the lock").isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void moveUsesTheManualUnpaidActionWithAFreshRecheckAndSkipsWhileSwitchedOff() {
        when(workflow.markUnpaidAfterOverdue(eq(5L), any())).thenReturn(true);
        assertThat(escalation.escalateOne(5L, NOW)).isTrue();
        ArgumentCaptor<Predicate<CommonInvoice>> recheck = ArgumentCaptor.forClass(Predicate.class);
        verify(workflow).markUnpaidAfterOverdue(eq(5L), recheck.capture());
        assertThat(recheck.getValue().test(invoice(5L, CommonInvoiceStatus.REMINDER, NOW.minusDays(40), NOW.minusDays(3))))
                .isTrue();

        reset(workflow);
        when(settings.getBooleanFreshFailClosed(eq(CLIENT_MESSAGES_PAYMENT_OVERDUE_ENABLED), anyBoolean())).thenReturn(false);
        assertThat(escalation.escalateOne(5L, NOW)).isFalse();
        verifyNoInteractions(workflow);
    }

    @Test
    void automaticMoveDateIsShownOnlyWhenTheAutoresponderWillMove() {
        var noticed = invoice(1L, CommonInvoiceStatus.REMINDER, NOW.minusDays(29), NOW.minusHours(5));
        when(invoices.findById(1L)).thenReturn(Optional.of(noticed));
        assertThat(escalation.automaticMoveNotBefore(1L, NOW)).contains(NOW.minusHours(5).plusDays(2));

        noticed.setSentAt(NOW.minusDays(10));
        noticed.setOverdueNoticeAt(null);
        assertThat(escalation.automaticMoveNotBefore(1L, NOW)).contains(NOW.minusDays(10).plusDays(30));

        noticed.setInvoicePaymentMode(InvoicePaymentMode.OWNER_PAPER_INVOICE);
        assertThat(escalation.automaticMoveNotBefore(1L, NOW)).isEmpty();
    }

    @Test
    void pausePostponesTheShownMoveAndSwitchedOffAutomationHidesIt() {
        var noticed = invoice(1L, CommonInvoiceStatus.REMINDER, NOW.minusDays(40), NOW.minusDays(3));
        when(invoices.findById(1L)).thenReturn(Optional.of(noticed));
        when(settings.getStringFresh(eq(CLIENT_MESSAGES_PAUSED_UNTIL), any())).thenReturn(NOW.plusDays(4).toString());
        assertThat(escalation.escalationAllowed(NOW)).isFalse();
        assertThat(escalation.automaticMoveNotBefore(1L, NOW)).as("postponed to the end of the pause")
                .contains(NOW.plusDays(4));

        when(settings.getStringFresh(eq(CLIENT_MESSAGES_PAUSED_UNTIL), any())).thenReturn("");
        when(settings.getBooleanFreshFailClosed(eq(CommonInvoiceOverdueEscalation.LIVE_ENABLED), anyBoolean()))
                .thenReturn(false);
        assertThat(escalation.automaticMoveNotBefore(1L, NOW)).as("live mode off: manager decision").isEmpty();
    }

    private static CommonInvoice invoice(long id, CommonInvoiceStatus status, LocalDateTime sentAt, LocalDateTime noticeAt) {
        var invoice = new CommonInvoice();
        invoice.setId(id);
        invoice.setStatus(status);
        invoice.setSentAt(sentAt);
        invoice.setOverdueNoticeAt(noticeAt);
        invoice.setAmountKopecks(2_300_000);
        var account = new CommonBillingAccount();
        account.setName("Синтетический клиент");
        invoice.setAccount(account);
        return invoice;
    }
}
