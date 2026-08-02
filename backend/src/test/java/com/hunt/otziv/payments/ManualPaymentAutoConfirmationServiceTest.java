package com.hunt.otziv.payments;

import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.model.ManualPaymentTask;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.model.PaymentReceiptStatus;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.service.ManualPaymentAutoConfirmationService;
import com.hunt.otziv.payments.service.ManualPaymentTaskService;
import com.hunt.otziv.payments.service.PaymentSuccessNotificationDeliveryService;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mock;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManualPaymentAutoConfirmationServiceTest {

    @Mock
    private PaymentLinkRepository paymentLinkRepository;

    @Mock
    private ManualPaymentTaskService manualPaymentTaskService;
    @Mock
    private PaymentSuccessNotificationDeliveryService paymentSuccessNotificationDeliveryService;

    @Test
    void confirmsLatestManualPaymentLinkForPaidOrder() {
        ManualPaymentAutoConfirmationService service = service();
        Order order = new Order();
        order.setId(42L);
        ManualPaymentTask task = new ManualPaymentTask();
        PaymentLink link = new PaymentLink();
        link.setId(420L);
        link.setAmountKopecks(125_000L);
        link.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
        link.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        link.setManualPaymentTask(task);
        link.setLastError("old error");

        when(paymentLinkRepository.findFirstByOrder_IdAndPaymentMethodInAndStatusInOrderByCreatedAtDesc(
                eq(42L),
                any(Collection.class),
                any(Collection.class)
        )).thenReturn(Optional.of(link));

        service.confirmForPaidOrder(order);

        ArgumentCaptor<PaymentLink> captor = ArgumentCaptor.forClass(PaymentLink.class);
        verify(paymentLinkRepository).save(captor.capture());
        PaymentLink saved = captor.getValue();
        assertEquals(PaymentLinkStatus.CONFIRMED, saved.getStatus());
        assertEquals(125_000L, saved.getConfirmedAmountKopecks());
        assertEquals(PaymentReceiptStatus.PENDING, saved.getReceiptStatus());
        assertEquals("order-status:Оплачено", saved.getManualConfirmedBy());
        assertNotNull(saved.getPaidAt());
        assertNotNull(saved.getManualConfirmedAt());
        assertNull(saved.getLastError());
        verify(manualPaymentTaskService).completeIfConfirmedTargetReached(task);
        verify(paymentSuccessNotificationDeliveryService).deliverAfterCommit(420L);
    }

    @Test
    void doesNothingWhenOrderHasNoManualPaymentLink() {
        ManualPaymentAutoConfirmationService service = service();
        Order order = new Order();
        order.setId(43L);

        when(paymentLinkRepository.findFirstByOrder_IdAndPaymentMethodInAndStatusInOrderByCreatedAtDesc(
                eq(43L),
                any(Collection.class),
                any(Collection.class)
        )).thenReturn(Optional.empty());

        service.confirmForPaidOrder(order);

        verify(paymentLinkRepository, never()).save(any());
        verify(manualPaymentTaskService, never()).completeIfConfirmedTargetReached(any());
    }

    @Test
    void retiresOpenLinksAfterOrderWasMarkedPaidManually() {
        ManualPaymentAutoConfirmationService service = service();
        Order order = new Order();
        order.setId(44L);
        PaymentLink bankLink = new PaymentLink();
        bankLink.setStatus(PaymentLinkStatus.INITIATED);
        bankLink.setPaymentMethod(PaymentMethod.SBP_QR);
        PaymentLink manualLink = new PaymentLink();
        manualLink.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
        manualLink.setPaymentMethod(PaymentMethod.MANUAL_EXTERNAL_LINK);

        when(paymentLinkRepository.findByOrder_IdAndStatusIn(eq(44L), any(Collection.class)))
                .thenReturn(List.of(bankLink, manualLink));

        assertEquals(2, service.retireOpenLinksForPaidOrder(order));

        assertEquals(PaymentLinkStatus.CANCELED, bankLink.getStatus());
        assertEquals(PaymentLinkStatus.CANCELED, manualLink.getStatus());
        assertEquals("Заказ отмечен оплаченным вручную; старая ссылка закрыта", bankLink.getLastError());
        assertEquals("Заказ отмечен оплаченным вручную; старая ссылка закрыта", manualLink.getLastError());
        verify(paymentLinkRepository).saveAll(List.of(bankLink, manualLink));
    }

    @Test
    void blocksManualCloseWhenBankPaymentIsAuthorized() {
        ManualPaymentAutoConfirmationService service = service();
        Order order = new Order();
        order.setId(45L);
        PaymentLink link = new PaymentLink();
        link.setStatus(PaymentLinkStatus.AUTHORIZED);
        link.setPaymentMethod(PaymentMethod.BANK_FORM);
        link.setTbankPaymentId("8634010699");

        when(paymentLinkRepository.findByOrder_IdAndStatusIn(eq(45L), any(Collection.class)))
                .thenReturn(List.of(link));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.ensureCanCloseOrderManually(order)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals(
                "У заказа есть незавершенный T-Bank/СБП платеж. Проверьте его в журнале перед ручным закрытием.",
                exception.getReason()
        );
    }

    @Test
    void blocksManualCloseWhileProviderPaymentNeedsReconciliation() {
        ManualPaymentAutoConfirmationService service = service();
        Order order = new Order();
        order.setId(46L);
        PaymentLink link = new PaymentLink();
        link.setStatus(PaymentLinkStatus.NEEDS_RECONCILIATION);
        link.setPaymentMethod(PaymentMethod.SBP_QR);
        link.setTbankPaymentId("8634010698");
        when(paymentLinkRepository.findByOrder_IdAndStatusIn(eq(46L), any(Collection.class)))
                .thenAnswer(invocation -> {
                    Collection<PaymentLinkStatus> statuses = invocation.getArgument(1);
                    return statuses.contains(link.getStatus()) ? List.of(link) : List.of();
                });

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.ensureCanCloseOrderManually(order)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
    }

    @Test
    void blocksManualCloseWhileProviderPaymentWithoutIdNeedsReconciliation() {
        ManualPaymentAutoConfirmationService service = service();
        Order order = new Order();
        order.setId(460L);
        PaymentLink link = new PaymentLink();
        link.setStatus(PaymentLinkStatus.NEEDS_RECONCILIATION);
        link.setPaymentMethod(PaymentMethod.SBP_QR);
        when(paymentLinkRepository.findByOrder_IdAndStatusIn(eq(460L), any(Collection.class)))
                .thenAnswer(invocation -> {
                    Collection<PaymentLinkStatus> statuses = invocation.getArgument(1);
                    return statuses.contains(link.getStatus()) ? List.of(link) : List.of();
                });

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.ensureCanCloseOrderManually(order)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
    }

    @Test
    void blocksManualCloseWhenBankPaymentWasInitiatedInTbank() {
        ManualPaymentAutoConfirmationService service = service();
        Order order = new Order();
        order.setId(47L);
        PaymentLink link = new PaymentLink();
        link.setStatus(PaymentLinkStatus.INITIATED);
        link.setPaymentMethod(PaymentMethod.SBP_QR);
        link.setTbankPaymentId("8634010700");

        when(paymentLinkRepository.findByOrder_IdAndStatusIn(eq(47L), any(Collection.class)))
                .thenAnswer(invocation -> {
                    Collection<PaymentLinkStatus> statuses = invocation.getArgument(1);
                    return statuses.contains(link.getStatus()) ? List.of(link) : List.of();
                });

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.ensureCanCloseOrderManually(order)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
    }

    @Test
    void neverRetiresAnInitiatedProviderPaymentAsLocallyCanceled() {
        ManualPaymentAutoConfirmationService service = service();
        Order order = new Order();
        order.setId(48L);
        PaymentLink bankLink = new PaymentLink();
        bankLink.setStatus(PaymentLinkStatus.INITIATED);
        bankLink.setPaymentMethod(PaymentMethod.SBP_QR);
        bankLink.setTbankPaymentId("8634010701");
        when(paymentLinkRepository.findByOrder_IdAndStatusIn(eq(48L), any(Collection.class)))
                .thenReturn(List.of(bankLink));

        assertEquals(0, service.retireOpenLinksForPaidOrder(order));

        assertEquals(PaymentLinkStatus.INITIATED, bankLink.getStatus());
        verify(paymentLinkRepository, never()).saveAll(any(Collection.class));
    }

    private ManualPaymentAutoConfirmationService service() {
        return new ManualPaymentAutoConfirmationService(
                paymentLinkRepository,
                manualPaymentTaskService,
                paymentSuccessNotificationDeliveryService
        );
    }
}
