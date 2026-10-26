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
    private final ManagerControlInvoiceDiagnostics diagnostics = new ManagerControlInvoiceDiagnostics(
            invoices, positions, mock(CommonInvoicePublicationBlockerService.class),
            mock(PaymentLinkRepository.class), mock(AppSettingService.class));

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
