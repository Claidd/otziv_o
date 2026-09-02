package com.hunt.otziv.z_zp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.service.CompanyService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.z_zp.model.PaymentCheck;
import com.hunt.otziv.z_zp.repository.PaymentCheckRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentCheckServiceImplTest {

    @Mock private PaymentCheckRepository paymentCheckRepository;
    @Mock private CompanyService companyService;
    @InjectMocks private PaymentCheckServiceImpl service;

    @Test
    void activeCheckCarriesPaidStatusGuardAndActualWorker() {
        Order order = paidOrder();
        when(paymentCheckRepository.findByOrderIdAndActiveTrue(41L)).thenReturn(List.of());

        assertTrue(service.save(order, new BigDecimal("1250.00"), 6));

        ArgumentCaptor<PaymentCheck> captor = ArgumentCaptor.forClass(PaymentCheck.class);
        verify(paymentCheckRepository).save(captor.capture());
        assertEquals(41L, captor.getValue().getOrderId());
        assertEquals(7L, captor.getValue().getPaymentStatusGuard());
        assertEquals(13L, captor.getValue().getManagerId());
        assertEquals(29L, captor.getValue().getWorkerId());
        assertEquals(6, captor.getValue().getPaidAmount());
        assertTrue(captor.getValue().isActive());
    }

    @Test
    void activeCheckCannotBeCreatedWithoutActualWorkerIdentity() {
        Order order = paidOrder();
        order.setWorker(null);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.save(order, BigDecimal.TEN)
        );

        assertTrue(error.getMessage().contains("Не удалось сохранить чек"));
        assertTrue(error.getCause().getMessage().contains("не определен исполнитель"));
        verifyNoInteractions(paymentCheckRepository);
        verifyNoInteractions(companyService);
    }

    @Test
    void identicalActiveCheckMakesRepeatedPaymentCallbackIdempotent() {
        Order order = paidOrder();
        PaymentCheck existing = PaymentCheck.builder()
                .orderId(41L)
                .sum(new BigDecimal("1250.00"))
                .paidAmount(6)
                .companyId(5L)
                .managerId(13L)
                .workerId(29L)
                .paymentStatusGuard(7L)
                .active(true)
                .build();
        when(paymentCheckRepository.findByOrderIdAndActiveTrue(41L)).thenReturn(List.of(existing));

        assertTrue(service.save(order, new BigDecimal("1250.00"), 6));

        verify(paymentCheckRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void sameAmountWithWrongWorkerIsNotAcceptedAsIdempotentPayment() {
        Order order = paidOrder();
        PaymentCheck existing = PaymentCheck.builder()
                .orderId(41L)
                .sum(new BigDecimal("1250.00"))
                .paidAmount(6)
                .companyId(5L)
                .managerId(13L)
                .workerId(13L)
                .paymentStatusGuard(7L)
                .active(true)
                .build();
        when(paymentCheckRepository.findByOrderIdAndActiveTrue(41L)).thenReturn(List.of(existing));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.save(order, new BigDecimal("1250.00"), 6)
        );

        assertTrue(error.getCause().getMessage().contains("не совпадает с текущим финансовым фактом"));
        verify(paymentCheckRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void inactiveHistoricalCheckDoesNotBlockNewActiveCheckAfterRepayment() {
        Order repaidOrder = paidOrder();
        when(paymentCheckRepository.findByOrderIdAndActiveTrue(41L)).thenReturn(List.of());

        assertTrue(service.save(repaidOrder, new BigDecimal("900.00"), 4));

        ArgumentCaptor<PaymentCheck> captor = ArgumentCaptor.forClass(PaymentCheck.class);
        verify(paymentCheckRepository).findByOrderIdAndActiveTrue(41L);
        verify(paymentCheckRepository).save(captor.capture());
        assertEquals(new BigDecimal("900.00"), captor.getValue().getSum());
        assertEquals(29L, captor.getValue().getWorkerId());
        assertEquals(4, captor.getValue().getPaidAmount());
        assertTrue(captor.getValue().isActive());
    }

    @Test
    void repeatedPaymentWithDifferentPaidAmountIsRejected() {
        Order order = paidOrder();
        PaymentCheck existing = PaymentCheck.builder()
                .orderId(41L)
                .sum(new BigDecimal("1250.00"))
                .paidAmount(5)
                .companyId(5L)
                .managerId(13L)
                .workerId(29L)
                .paymentStatusGuard(7L)
                .active(true)
                .build();
        when(paymentCheckRepository.findByOrderIdAndActiveTrue(41L)).thenReturn(List.of(existing));

        assertThrows(
                IllegalStateException.class,
                () -> service.save(order, new BigDecimal("1250.00"), 6)
        );

        verify(paymentCheckRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void activeCheckMustAlreadyBelongToTheConfirmingPaymentLink() {
        PaymentCheck existing = PaymentCheck.builder()
                .id(77L)
                .orderId(41L)
                .paymentLinkId(501L)
                .active(true)
                .build();
        when(paymentCheckRepository.findByOrderIdAndActiveTrue(41L)).thenReturn(List.of(existing));

        service.assertActiveCheckBoundToPaymentLink(41L, 501L);

        assertEquals(501L, existing.getPaymentLinkId());
        assertThrows(
                IllegalStateException.class,
                () -> service.assertActiveCheckBoundToPaymentLink(41L, 502L)
        );
    }

    @Test
    void lateCallbackNeverBackfillsAnUnboundActiveCheck() {
        PaymentCheck existing = PaymentCheck.builder().id(77L).orderId(41L).active(true).build();
        when(paymentCheckRepository.findByOrderIdAndActiveTrue(41L)).thenReturn(List.of(existing));

        assertThrows(
                IllegalStateException.class,
                () -> service.assertActiveCheckBoundToPaymentLink(41L, 501L)
        );

        assertEquals(null, existing.getPaymentLinkId());
        verify(paymentCheckRepository, never()).save(existing);
    }

    @Test
    void paymentLinkSourceIsCapturedOnlyWhileCreatingTheCheck() throws Exception {
        Order order = paidOrder();
        when(paymentCheckRepository.findByOrderIdAndActiveTrue(41L)).thenReturn(List.of());

        assertTrue(PaymentCheckSourceContext.withPaymentLink(
                501L,
                () -> service.save(order, new BigDecimal("1250.00"), 6)
        ));

        ArgumentCaptor<PaymentCheck> captor = ArgumentCaptor.forClass(PaymentCheck.class);
        verify(paymentCheckRepository).save(captor.capture());
        assertEquals(501L, captor.getValue().getPaymentLinkId());
        assertEquals(null, PaymentCheckSourceContext.currentPaymentLinkId());
    }

    @Test
    void unpaidOrderCannotCreateActiveCheck() {
        Order order = paidOrder();
        order.setStatus(OrderStatus.builder().id(2L).title("Не оплачено").build());

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.save(order, BigDecimal.TEN)
        );

        assertTrue(error.getMessage().contains("Не удалось сохранить чек"));
    }

    private Order paidOrder() {
        User user = new User();
        user.setId(13L);
        Manager manager = new Manager();
        manager.setUser(user);
        User workerUser = new User();
        workerUser.setId(29L);
        Worker worker = new Worker();
        worker.setUser(workerUser);
        return Order.builder()
                .id(41L)
                .company(Company.builder().id(5L).title("Компания").build())
                .manager(manager)
                .worker(worker)
                .status(OrderStatus.builder().id(7L).title("Оплачено").build())
                .build();
    }
}
