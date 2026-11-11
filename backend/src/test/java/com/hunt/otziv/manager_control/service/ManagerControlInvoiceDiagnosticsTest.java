package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.common_billing.model.CommonBillingAccount;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.common_billing.service.CommonInvoicePublicationBlockerService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagerControlInvoiceDiagnosticsTest {
    private final CommonInvoiceRepository invoices = mock(CommonInvoiceRepository.class);
    private final CommonInvoiceOrderRepository positions = mock(CommonInvoiceOrderRepository.class);
    private final AppSettingService settings = mock(AppSettingService.class);
    private final ManagerControlInvoiceDiagnostics diagnostics = new ManagerControlInvoiceDiagnostics(
            invoices, positions, mock(CommonInvoicePublicationBlockerService.class),
            mock(PaymentLinkRepository.class), settings);

    @Test
    void historicalAuditUsesCurrentInvoiceProblemRuleRatherThanItsDeferredCard() {
        var paid = invoice(CommonInvoiceStatus.PAID, null);
        given(paid,List.of());
        assertThat(diagnostics.resolvedForAudit(paid.getId())).isTrue();
        paid.setPaymentSuccessNotificationError("delivery_failed");
        assertThat(diagnostics.resolvedForAudit(paid.getId())).isFalse();
        assertThat(diagnostics.resolvedForAudit(99999L)).isFalse();
    }

    @Test
    void oldPublicationBlockerDoesNotRemainAnAuditIssueAfterInvoiceWasSent() {
        var active = invoice(CommonInvoiceStatus.REMINDER, null);
        given(active,List.of(position("Напоминание",false,true)));
        assertThat(diagnostics.resolvedForAudit(active.getId())).isTrue();
        active.setStatus(CommonInvoiceStatus.UNPAID);
        assertThat(diagnostics.resolvedForAudit(active.getId())).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled: account disabled", "empty: no positions", "merged_into: another invoice", " MANUAL_FIX: reconciled "})
    void disabledInformationalTailWithoutUnpaidPositionsIsResolved(String error) {
        var invoice = invoice(CommonInvoiceStatus.DISABLED, error);
        given(invoice, List.of());
        assertThatCode(() -> diagnostics.requireResolved(invoice.getId())).doesNotThrowAnyException();
    }

    @Test
    void disabledInformationalTailWithAnUnpaidPositionStaysVisible() {
        var invoice = invoice(CommonInvoiceStatus.DISABLED, "disabled: account disabled");
        given(invoice, List.of(position("Архив", false, true)));
        assertThatThrownBy(() -> diagnostics.requireResolved(invoice.getId()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void notificationFailureIsNotHiddenByDisabledInformationalTail() {
        var invoice = invoice(CommonInvoiceStatus.DISABLED, "disabled: account disabled");
        invoice.setPaymentSuccessNotificationError("delivery_failed");
        given(invoice, List.of());
        assertThatThrownBy(() -> diagnostics.requireResolved(invoice.getId()))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(reason(invoice, List.of())).contains("Результат уведомления об оплате не подтвержден", "Не отправляйте повторно")
                .doesNotContain("Общий счет отключен", "delivery_failed");
    }

    @Test
    void realErrorsAndNonDisabledStatusesRemainActionable() {
        for (var status : List.of(CommonInvoiceStatus.DISABLED, CommonInvoiceStatus.PAID, CommonInvoiceStatus.ARCHIVED)) {
            var invoice = invoice(status, "payment_cancel_failed_final");
            given(invoice, List.of());
            assertThatThrownBy(() -> diagnostics.requireResolved(invoice.getId()))
                    .isInstanceOf(ResponseStatusException.class);
        }
        var active = invoice(CommonInvoiceStatus.READY, "empty: unexpected active state");
        given(active, List.of());
        assertThatThrownBy(() -> diagnostics.requireResolved(active.getId()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void partiallyPaidInvoiceExplainsArchivedCompositionWithoutSuggestingANewReminder() {
        var invoice = invoice(CommonInvoiceStatus.PARTIALLY_PAID, null);
        invoice.setAmountKopecks(450000); invoice.setPaidKopecks(150000);
        invoice.getAccount().setEnabled(false);
        var items = List.of(position("Архив", false, true), position("Архив", false, true),
                position("Оплачено", true, true));
        given(invoice, items);
        assertThat(diagnostics.commonInvoiceRequiresCompositionReview(invoice, items)).isTrue();
        assertThatThrownBy(() -> diagnostics.requireResolved(invoice.getId()))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(reason(invoice, items)).contains("архивные неоплаченные заказы: 2", "1500 руб.",
                "4500 руб.", "остаток 3000 руб.", "выключен", "повторное напоминание заблокировано")
                .doesNotContain("Нажмите «Починить»", "система пересчитает");
        assertThat(reason(invoice, items).length()).isLessThanOrEqualTo(500);
    }

    @Test
    void compositionGuardUsesCurrentActiveUnpaidArchiveRatherThanDisabledAccountAlone() {
        var invoice = invoice(CommonInvoiceStatus.PARTIALLY_PAID, null);
        invoice.getAccount().setEnabled(false);
        assertThat(diagnostics.commonInvoiceRequiresCompositionReview(invoice,
                List.of(position("На проверке", false, true), position("Архив", true, true),
                        position("Архив", false, false)))).isFalse();
        var archived = position("Архив", false, true);
        archived.setReady(true); // A stale ready flag must not bypass current archive status.
        assertThat(diagnostics.commonInvoiceRequiresCompositionReview(invoice, List.of(archived))).isTrue();
        invoice.setStatus(CommonInvoiceStatus.PAID);
        assertThat(diagnostics.commonInvoiceRequiresCompositionReview(invoice, List.of(archived))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"message_send_in_progress", "message_send_stale: previous attempt", "message_send_unknown: provider timeout"})
    void invoice115UncertainDeliveryRequiresExistingOperationCheckAndNeverManualResend(String error) {
        var invoice = invoice(CommonInvoiceStatus.NEEDS_ATTENTION, error);
        invoice.setId(115L);
        String originalError = invoice.getLastError();

        assertThat(reason(invoice, List.of())).contains("не подтвержден", "существующую отправку", "Нажмите «Проверить отправку»",
                "Новое сообщение для проверки не отправляется", "после подтверждения результата существующей отправки")
                .doesNotContain("повторите отправку", "вручную", "администратору", "message_send_", "provider", "проверка запущена");
        assertThat(invoice.getLastError()).isEqualTo(originalError);
        assertThat(invoice.getStatus()).isEqualTo(CommonInvoiceStatus.NEEDS_ATTENTION);
        verifyNoInteractions(invoices, positions);
    }

    @Test
    void invoice297SeparatesThePreviousTextErrorFromCurrentUnreadyPositions() {
        var invoice = invoice(CommonInvoiceStatus.NEEDS_ATTENTION,
                "409 CONFLICT: Нельзя отправить заказ в публикацию: текст отзыва уже опубликован ранее. "
                        + "Проблемные карточки: №1 (отзыв #200221); JDBC select review_text from reviews; java.lang.IllegalStateException");
        invoice.setId(297L);
        String recordedError = invoice.getLastError();
        var items = java.util.stream.IntStream.range(0, 7).mapToObj(index -> {
            var item = position(index < 4 ? "Опубликовано" : "На проверке", false, true);
            item.getOrder().setId(index < 4 ? 27386L + index : List.of(27390L, 27391L, 27394L).get(index - 4));
            item.setReady(index < 4);
            return item;
        }).toList();

        assertThat(reason(invoice, items)).contains("При предыдущей попытке", "отзыв #200221", "Сейчас готовы 4 из 7",
                "Она не подтверждает конфликт в текущем тексте", "#27390 «На проверке»", "#27391 «На проверке»", "#27394 «На проверке»",
                "согласование клиента", "не означает одобрение")
                .doesNotContain("409", "CONFLICT", "JDBC", "select", "IllegalStateException", "Починить", "текст отзыва уже использован");
        assertThat(reason(invoice, items).length()).isLessThanOrEqualTo(500);
        assertThat(invoice.getLastError()).isEqualTo(recordedError);
        assertThat(invoice.getStatus()).isEqualTo(CommonInvoiceStatus.NEEDS_ATTENTION);
        assertThat(items.stream().filter(CommonInvoiceOrder::isReady).count()).isEqualTo(4);
        verifyNoInteractions(invoices, positions);
    }

    @Test
    void historicalTextErrorDoesNotInventAReviewOrCountFormerMembershipAsCurrentWork() {
        var invoice = invoice(CommonInvoiceStatus.NEEDS_ATTENTION,
                "Текст отзыва уже опубликован ранее; order=200226; HTTP 409");
        var current = position("На проверке", false, true); current.getOrder().setId(27391L);
        var former = position("Архив", false, false); former.getOrder().setId(888L); former.setReady(true);

        assertThat(reason(invoice, List.of(current, former))).contains("При предыдущей попытке", "Сейчас готовы 0 из 1", "#27391 «На проверке»")
                .doesNotContain("#200226", "#888", "HTTP", "409", "конфликт устранен");
        assertThat(reason(invoice, List.of())).contains("Готовность текущих позиций нужно сверить")
                .doesNotContain("готовы 0", "отзыв #");
        verifyNoInteractions(invoices, positions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"409 CONFLICT provider stack", "JDBC SQL select invoice_id from invoices",
            "telegram_exception: java.lang.IllegalStateException secret-provider-details", "max_not_sent: HTTP 503 upstream"})
    void technicalInvoiceErrorsRemainOnlyInStoredEvidence(String error) {
        var invoice = invoice(CommonInvoiceStatus.NEEDS_ATTENTION, error);
        String explanation = reason(invoice, List.of());

        assertThat(explanation).contains("Счет").doesNotContain("409", "CONFLICT", "JDBC", "SQL", "select",
                "telegram_exception", "max_not_sent", "HTTP", "secret-provider-details", "IllegalStateException");
        assertThat(invoice.getLastError()).isEqualTo(error);
        verifyNoInteractions(invoices, positions);
    }

    @Test
    void structuredApprovalFailureKeepsBusinessDetailButDropsTechnicalFields() {
        var invoice = invoice(CommonInvoiceStatus.NEEDS_ATTENTION,
                "review_approval_failed: problem=JDBC select invoice_order where ready=false; solution=HTTP 409 CONFLICT");
        assertThat(reason(invoice, List.of())).contains("не удалось назначить даты публикации", "проверьте заказы общего счета")
                .doesNotContain("JDBC", "select", "invoice_order", "HTTP", "409");
        invoice.setLastError("review_approval_failed: problem=не хватает дат публикации; solution=проверьте график специалиста");
        assertThat(reason(invoice, List.of())).contains("не хватает дат публикации", "проверьте график специалиста");
    }

    @Test
    void bankPaperInvoiceWhoseDocumentRemindersRunOnScheduleIsNotARemark() {
        var progress = paperProgress(true);
        var invoice = issuedBankPaperInvoice(CommonInvoiceStatus.INVOICED, 5);
        given(invoice, List.of(position("Выставлен счет", false, true)));

        assertThat(diagnostics.resolvedForAudit(invoice.getId())).isTrue();
        verify(progress).remindersOnSchedule(eq(invoice.getId()), any(LocalDateTime.class));
    }

    @Test
    void bankPaperInvoiceWithFinishedOrStalledRemindersIsARemarkWithBankCheckAdvice() {
        paperProgress(false);
        var invoice = issuedBankPaperInvoice(CommonInvoiceStatus.INVOICED, 20);
        given(invoice, List.of(position("Выставлен счет", false, true)));

        assertThat(diagnostics.resolvedForAudit(invoice.getId())).isFalse();
        assertThat(reason(invoice, List.of())).contains("банковский счёт выставлен 20 дн. назад", "проверьте поступление в банке")
                .doesNotContain("Нажмите «Починить»");
    }

    @Test
    void partlyPaidBankPaperInvoiceIsNotARemarkWhileItsDocumentsAreReminded() {
        paperProgress(true);
        var invoice = issuedBankPaperInvoice(CommonInvoiceStatus.PARTIALLY_PAID, 6);
        invoice.setPaidKopecks(640000);
        given(invoice, List.of(position("Выставлен счет", false, true)));

        assertThat(diagnostics.resolvedForAudit(invoice.getId())).isTrue();
    }

    @Test
    void scheduledRemindersStopShieldingTheInvoiceOnceThePaymentOverduePeriodPasses() {
        paperProgress(true);
        var invoice = issuedBankPaperInvoice(CommonInvoiceStatus.REMINDER, 31);
        given(invoice, List.of(position("Напоминание", false, true)));

        assertThat(diagnostics.resolvedForAudit(invoice.getId())).isFalse();
    }

    @Test
    void ordinaryInvoiceKeepsItsOwnReminderClock() {
        overdueDays(30);
        var invoice = invoice(CommonInvoiceStatus.INVOICED, null);
        invoice.setUpdatedAt(LocalDateTime.now().minusDays(4));
        invoice.setSentAt(LocalDateTime.now().minusDays(4));
        invoice.setNextReminderAt(LocalDateTime.now().plusDays(1));
        given(invoice, List.of(position("Выставлен счет", false, true)));
        assertThat(diagnostics.resolvedForAudit(invoice.getId())).isTrue();

        invoice.setNextReminderAt(LocalDateTime.now().minusDays(2));
        assertThat(diagnostics.resolvedForAudit(invoice.getId())).as("a reminder two days late is stalled").isFalse();
        invoice.setNextReminderAt(null);
        assertThat(diagnostics.resolvedForAudit(invoice.getId())).isFalse();
    }

    @Test
    void longUnpaidInvoiceIsARemarkEvenWhileRemindersKeepItFresh() {
        overdueDays(30);
        var invoice = invoice(CommonInvoiceStatus.REMINDER, null);
        invoice.setSentAt(LocalDateTime.now().minusDays(40));
        invoice.setNextReminderAt(LocalDateTime.now().plusDays(1));
        invoice.setUpdatedAt(LocalDateTime.now().minusHours(3));
        given(invoice, List.of(position("Напоминание", false, true)));
        var notices = overdueNotices();
        when(notices.automaticMoveNotBefore(eq(invoice.getId()), any(LocalDateTime.class)))
                .thenReturn(Optional.of(LocalDateTime.of(2026, 11, 8, 12, 0)));

        assertThat(diagnostics.resolvedForAudit(invoice.getId())).isFalse();
        assertThat(reason(invoice, List.of())).contains("не оплачен 40 дн.", "не раньше 08.11.2026",
                "создаст плохие задачи", "уведомления в группу менеджера").doesNotContain("Нажмите «Починить»");
    }

    @Test
    void cardStartsWhenTheTwoDayNoticePeriodBegins() {
        overdueDays(30);
        var invoice = invoice(CommonInvoiceStatus.INVOICED, null);
        invoice.setNextReminderAt(LocalDateTime.now().plusDays(1));
        given(invoice, List.of(position("Выставлен счет", false, true)));

        invoice.setSentAt(LocalDateTime.now().minusDays(27));
        assertThat(diagnostics.resolvedForAudit(invoice.getId())).isTrue();
        invoice.setSentAt(LocalDateTime.now().minusDays(28).minusMinutes(1));
        assertThat(diagnostics.resolvedForAudit(invoice.getId())).isFalse();
    }

    @Test
    void aPaperInvoiceCardCountsFromTheDeliveryOfItsDocumentsAndTheExtension() {
        overdueDays(30);
        var invoice = invoice(CommonInvoiceStatus.INVOICED, null);
        invoice.setInvoicePaymentMode(com.hunt.otziv.payments.model.InvoicePaymentMode.OWNER_PAPER_INVOICE);
        invoice.setSentAt(LocalDateTime.now().minusDays(40)); // sent with ordinary details before the switch
        invoice.setPaperInvoiceIssuedAt(LocalDateTime.now().minusDays(20)); // documents delivered
        invoice.setNextReminderAt(LocalDateTime.now().plusDays(1));
        given(invoice, List.of(position("Выставлен счет", false, true)));
        var notices = overdueNotices();
        when(notices.automaticMoveNotBefore(eq(invoice.getId()), any(LocalDateTime.class)))
                .thenReturn(Optional.of(LocalDateTime.of(2026, 12, 10, 12, 0)));

        when(notices.termStart(invoice.getId())).thenReturn(Optional.of(LocalDateTime.now().minusDays(20)));
        assertThat(diagnostics.resolvedForAudit(invoice.getId())).as("delivered 20 days ago: not approaching").isTrue();

        // The start of the day 28 days ago: past the 28 full days at any hour, and 28 calendar days in the text
        // (an hour before «now minus 28 days» falls on the previous date when the test runs right after midnight).
        when(notices.termStart(invoice.getId())).thenReturn(Optional.of(java.time.LocalDate.now().minusDays(28).atStartOfDay()));
        invoice.setOverdueExtensionDays(7);
        assertThat(diagnostics.resolvedForAudit(invoice.getId())).as("a week's extension keeps it off the board").isTrue();

        invoice.setOverdueExtensionDays(0);
        assertThat(reason(invoice, List.of())).contains("не оплачен 28 дн. после доставки бумажного счёта", "не раньше 10.12.2026");
        invoice.setOverdueExtensionDays(3);
        when(notices.termStart(invoice.getId())).thenReturn(Optional.of(LocalDateTime.now().minusDays(32)));
        assertThat(reason(invoice, List.of())).contains("отсрочка 3 дн.");
    }

    @Test
    void invoiceTheAutoresponderWillNotMoveIsLeftToTheManagersDecision() {
        overdueDays(30);
        var invoice = invoice(CommonInvoiceStatus.PARTIALLY_PAID, null);
        invoice.setSentAt(LocalDateTime.now().minusDays(35));
        invoice.setAmountKopecks(500000);
        invoice.setPaidKopecks(100000);
        var notices = overdueNotices();
        when(notices.automaticMoveNotBefore(anyLong(), any(LocalDateTime.class))).thenReturn(Optional.empty());

        assertThat(reason(invoice, List.of())).contains("оплачен частично", "Автоматического перевода",
                "(есть частичная оплата)", "Решение за менеджером");
    }

    @Test
    void reminderHeldByTheAutoresponderIsWaitingNotAnUnconfirmedSend() {
        overdueDays(30);
        var invoice = queuedReminder(LocalDateTime.now().minusHours(4));
        var wait = deliveryWait();
        when(wait.autoresponderWait("invoice-message:175:op")).thenReturn(Optional.of(
                new com.hunt.otziv.common_billing.api.CommonInvoiceDeliveryWait.Wait("outside_business_window",
                        LocalDateTime.now().minusHours(4))));

        assertThat(diagnostics.resolvedForAudit(invoice.getId())).isTrue();
        assertThat(diagnostics.deliveryHeldByAutoresponder(invoice, LocalDateTime.now())).isTrue();
    }

    @Test
    void reminderHeldForMoreThanTwoDaysReachesTheManagerWithTheQueueReason() {
        overdueDays(30);
        var invoice = queuedReminder(LocalDateTime.now().minusHours(50));
        var wait = deliveryWait();
        when(wait.autoresponderWait("invoice-message:175:op")).thenReturn(Optional.of(
                new com.hunt.otziv.common_billing.api.CommonInvoiceDeliveryWait.Wait("daily_limit",
                        LocalDateTime.now().minusHours(50))));

        assertThat(diagnostics.resolvedForAudit(invoice.getId())).isFalse();
        assertThat(reason(invoice, List.of())).contains("ещё не отправлялось", "очереди автоответчика",
                "исчерпан дневной лимит").doesNotContain("не подтвержден");
    }

    @Test
    void sendInDoubtIsStillAnUnconfirmedDelivery() {
        overdueDays(30);
        var invoice = queuedReminder(LocalDateTime.now().minusHours(4));
        var wait = deliveryWait();
        when(wait.autoresponderWait("invoice-message:175:op")).thenReturn(Optional.empty());

        assertThat(diagnostics.resolvedForAudit(invoice.getId())).isFalse();
        assertThat(reason(invoice, List.of())).contains("Результат отправки сообщения по счету не подтвержден");
    }

    @Test
    void overdueInvoiceWhoseNoticeWentToTheManagerIsTheAutorespondersWork() {
        overdueDays(30);
        var invoice = invoice(CommonInvoiceStatus.REMINDER, null);
        invoice.setSentAt(LocalDateTime.now().minusDays(29));
        invoice.setOverdueNoticeAt(LocalDateTime.now().minusHours(20));
        invoice.setNextReminderAt(LocalDateTime.now().plusDays(1));
        given(invoice, List.of(position("Напоминание", false, true)));
        when(overdueNotices().automaticMoveNotBefore(eq(invoice.getId()), any(LocalDateTime.class)))
                .thenReturn(Optional.of(LocalDateTime.now().plusDays(1)));

        assertThat(diagnostics.resolvedForAudit(invoice.getId())).isTrue();
    }

    @Test
    void overdueInvoiceComesBackWhenTheAutomaticMoveIsMoreThanADayLate() {
        overdueDays(30);
        var invoice = invoice(CommonInvoiceStatus.REMINDER, null);
        invoice.setSentAt(LocalDateTime.now().minusDays(40));
        invoice.setOverdueNoticeAt(LocalDateTime.now().minusDays(5));
        invoice.setNextReminderAt(LocalDateTime.now().plusDays(1));
        given(invoice, List.of(position("Напоминание", false, true)));
        when(overdueNotices().automaticMoveNotBefore(eq(invoice.getId()), any(LocalDateTime.class)))
                .thenReturn(Optional.of(LocalDateTime.now().plusMinutes(5)));

        assertThat(diagnostics.resolvedForAudit(invoice.getId())).isFalse();
        assertThat(reason(invoice, List.of())).contains("перевод не состоялся", "не оплачен 40 дн.");
    }

    @Test
    void overdueNoticeGetsADayBeforeTheMissingNoticeReachesTheBoard() {
        overdueDays(30);
        var invoice = invoice(CommonInvoiceStatus.REMINDER, null);
        invoice.setNextReminderAt(LocalDateTime.now().plusDays(1));
        given(invoice, List.of(position("Напоминание", false, true)));
        when(overdueNotices().automaticMoveNotBefore(eq(invoice.getId()), any(LocalDateTime.class)))
                .thenReturn(Optional.of(LocalDateTime.now().plusDays(2)));

        invoice.setSentAt(LocalDateTime.now().minusDays(28).minusHours(3));
        assertThat(diagnostics.resolvedForAudit(invoice.getId())).as("the notice job runs later today").isTrue();
        invoice.setSentAt(LocalDateTime.now().minusDays(29).minusHours(1));
        assertThat(diagnostics.resolvedForAudit(invoice.getId())).isFalse();
        assertThat(reason(invoice, List.of())).contains("не ушло больше суток");
    }

    @Test
    void expiredLinkIsLeftToTheAutomaticBankCheckForAnHour() {
        var invoice = invoice(CommonInvoiceStatus.NEEDS_ATTENTION,
                com.hunt.otziv.common_billing.service.CommonInvoiceCancellationService.EXPIRED_LINK_ATTENTION_PREFIX
                        + " и отправлена на отмену");
        given(invoice, List.of(position("Напоминание", false, true)));

        invoice.setUpdatedAt(LocalDateTime.now().minusMinutes(20));
        assertThat(diagnostics.resolvedForAudit(invoice.getId())).isTrue();
        invoice.setUpdatedAt(LocalDateTime.now().minusMinutes(70));
        assertThat(diagnostics.resolvedForAudit(invoice.getId())).isFalse();
        assertThat(reason(invoice, List.of())).contains("Нажмите «Починить»", "Оплачен")
                .doesNotContain("Проблема при создании платежной ссылки");
    }

    private CommonInvoice queuedReminder(LocalDateTime dueAt) {
        var invoice = invoice(CommonInvoiceStatus.REMINDER, "message_send_in_progress");
        invoice.setSentAt(LocalDateTime.now().minusDays(10));
        invoice.setNextReminderAt(dueAt);
        invoice.setPaymentMessageOperationId("invoice-message:175:op");
        given(invoice, List.of(position("Напоминание", false, true)));
        return invoice;
    }

    private com.hunt.otziv.common_billing.api.CommonInvoiceDeliveryWait deliveryWait() {
        var wait = mock(com.hunt.otziv.common_billing.api.CommonInvoiceDeliveryWait.class);
        ReflectionTestUtils.setField(diagnostics, "deliveryWait", wait);
        return wait;
    }

    private com.hunt.otziv.common_billing.api.CommonInvoiceOverdueNotices overdueNotices() {
        var notices = mock(com.hunt.otziv.common_billing.api.CommonInvoiceOverdueNotices.class);
        ReflectionTestUtils.setField(diagnostics, "overdueNotices", notices);
        return notices;
    }

    private com.hunt.otziv.payments.api.PaperInvoiceReminderProgress paperProgress(boolean onSchedule) {
        overdueDays(30);
        var progress = mock(com.hunt.otziv.payments.api.PaperInvoiceReminderProgress.class);
        when(progress.remindersOnSchedule(anyLong(), any(LocalDateTime.class))).thenReturn(onSchedule);
        ReflectionTestUtils.setField(diagnostics, "paperReminderProgress", progress);
        return progress;
    }

    @Test
    void unpaidInvoiceWhoseBadReviewCycleRunsLeavesTheBoardAndCanBeClosed() {
        var unpaid = invoice(CommonInvoiceStatus.UNPAID, null);
        given(unpaid, List.of(position("Не оплачено", false, false)));
        var cycle = mock(com.hunt.otziv.common_billing.api.CommonInvoiceUnpaidAttention.class);
        when(cycle.attention(eq(unpaid.getId()), any())).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(diagnostics, "unpaidCycle", cycle);

        assertThat(diagnostics.resolvedForAudit(unpaid.getId())).isTrue();
        assertThatCode(() -> diagnostics.requireResolved(unpaid.getId())).doesNotThrowAnyException();
    }

    @Test
    void stalledUnpaidCycleStaysWithItsReasonAndTheCheckMarkRecordsTheDecision() {
        var unpaid = invoice(CommonInvoiceStatus.UNPAID, null);
        given(unpaid, List.of(position("Не оплачено", false, true)));
        var cycle = mock(com.hunt.otziv.common_billing.api.CommonInvoiceUnpaidAttention.class);
        when(cycle.attention(eq(unpaid.getId()), any()))
                .thenReturn(Optional.of("Почему в замечаниях: клиент не оплатил счёт, а плохие задачи просрочены: 2."));
        ReflectionTestUtils.setField(diagnostics, "unpaidCycle", cycle);

        assertThat(diagnostics.resolvedForAudit(unpaid.getId())).isFalse();
        assertThat(reason(unpaid, List.of())).contains("плохие задачи просрочены: 2")
                .doesNotContain("закрыть карточку контроля");
        assertThatCode(() -> diagnostics.requireResolved(unpaid.getId())).doesNotThrowAnyException();
    }

    @Test
    void unavailableCycleStateKeepsTheUnpaidCardVisible() {
        var unpaid = invoice(CommonInvoiceStatus.UNPAID, null);
        given(unpaid, List.of());
        var cycle = mock(com.hunt.otziv.common_billing.api.CommonInvoiceUnpaidAttention.class);
        when(cycle.attention(eq(unpaid.getId()), any())).thenThrow(new IllegalStateException("database unavailable"));
        ReflectionTestUtils.setField(diagnostics, "unpaidCycle", cycle);

        assertThat(diagnostics.resolvedForAudit(unpaid.getId())).isFalse();
        assertThat(reason(unpaid, List.of())).contains("«Не оплачено»");
    }

    private void overdueDays(int days) {
        when(settings.getInt(eq(AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_DAYS), anyInt())).thenReturn(days);
    }

    private CommonInvoice issuedBankPaperInvoice(CommonInvoiceStatus status, int sentDaysAgo) {
        var invoice = invoice(status, null);
        invoice.setInvoicePaymentMode(com.hunt.otziv.payments.model.InvoicePaymentMode.OWNER_PAPER_INVOICE);
        invoice.setSentAt(LocalDateTime.now().minusDays(sentDaysAgo));
        invoice.setPaperInvoiceIssuedAt(LocalDateTime.now().minusDays(Math.max(0, sentDaysAgo - 2)));
        invoice.setPaperInvoiceAutomationHold(true);
        invoice.setNextReminderAt(null);
        invoice.setUpdatedAt(invoice.getPaperInvoiceIssuedAt());
        invoice.setAmountKopecks(1280000);
        return invoice;
    }

    private void given(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        when(invoices.findById(invoice.getId())).thenReturn(Optional.of(invoice));
        when(positions.findByInvoiceIdWithOrders(invoice.getId())).thenReturn(items);
    }

    private String reason(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        return ReflectionTestUtils.invokeMethod(diagnostics, "commonInvoiceReason", invoice, LocalDate.now(), items, List.of());
    }

    private CommonInvoice invoice(CommonInvoiceStatus status, String error) {
        var invoice = new CommonInvoice(); invoice.setId(175L); invoice.setStatus(status);
        invoice.setLastError(error); invoice.setUpdatedAt(LocalDateTime.now());
        invoice.setAccount(new CommonBillingAccount());
        return invoice;
    }

    private CommonInvoiceOrder position(String status, boolean paid, boolean active) {
        var orderStatus = new OrderStatus(); orderStatus.setTitle(status);
        var order = new Order(); order.setStatus(orderStatus);
        var item = new CommonInvoiceOrder(); item.setOrder(order); item.setPaid(paid);
        item.setActiveMembership(active); item.setAmountKopecks(150000);
        return item;
    }
}
