package com.hunt.otziv.payments.service;

import com.hunt.otziv.payments.dto.ManagerPaymentLinkResponse;
import com.hunt.otziv.payments.dto.PaymentRouteChangeContextResponse;
import com.hunt.otziv.payments.dto.PaymentRouteChangeRequest;
import com.hunt.otziv.payments.dto.PaymentRouteChangeResponse;
import com.hunt.otziv.payments.dto.PaymentRouteChangeTarget;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentRouteChangeServiceTest {

    @Mock
    private PaymentLinkService paymentLinkService;

    @Mock
    private PaymentRouteChangeNotificationWorker notificationWorker;

    @Mock
    private Authentication authentication;

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void delegatesContextLookup() {
        PaymentRouteChangeContextResponse expected = new PaymentRouteChangeContextResponse(
                21L, "Эквайринг Т-Банк", "Владелец", "CREATED", true, ""
        );
        when(paymentLinkService.paymentRouteChangeContextAuthorized(8L, authentication)).thenReturn(expected);
        PaymentRouteChangeService service = new PaymentRouteChangeService(paymentLinkService, notificationWorker);

        PaymentRouteChangeContextResponse result = service.context(8L, authentication);

        assertSame(expected, result);
    }

    @Test
    void sendsNewDetailsOnlyAfterTransactionCommit() {
        ManagerPaymentLinkResponse payment = new ManagerPaymentLinkResponse(
                "token", null, 8L, BigDecimal.valueOf(1_000), 100_000L,
                "WAITING_MANUAL_PAYMENT", "MANUAL_MOBILE_BANK", null,
                "Реквизиты", "Полный текст", "89140000000"
        );
        PaymentLinkService.PaymentRouteReplacement replacement = new PaymentLinkService.PaymentRouteReplacement(
                21L, 22L, PaymentRouteChangeTarget.EMPLOYEE_REQUISITES, payment
        );
        PaymentRouteChangeRequest request = new PaymentRouteChangeRequest(
                21L, PaymentRouteChangeTarget.EMPLOYEE_REQUISITES, true
        );
        when(paymentLinkService.replacePaymentRouteAuthorized(
                8L, 21L, PaymentRouteChangeTarget.EMPLOYEE_REQUISITES, true, null, authentication
        )).thenReturn(replacement);
        PaymentRouteChangeService service = new PaymentRouteChangeService(paymentLinkService, notificationWorker);
        TransactionSynchronizationManager.initSynchronization();

        PaymentRouteChangeResponse result = service.change(8L, request, authentication);

        verify(notificationWorker).enqueue(8L, 22L, payment);
        assertEquals(22L, result.paymentLinkId());
        assertTrue(result.clientNotificationScheduled());
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
        verify(notificationWorker).send(22L);
    }
}
