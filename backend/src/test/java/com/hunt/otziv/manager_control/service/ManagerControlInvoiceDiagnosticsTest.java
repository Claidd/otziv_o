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
        assertThat(reason(invoice, List.of())).contains("Ошибка уведомления об оплате", "delivery_failed")
                .doesNotContain("Общий счет отключен");
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
