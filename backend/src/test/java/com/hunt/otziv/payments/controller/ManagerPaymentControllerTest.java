package com.hunt.otziv.payments.controller;

import com.hunt.otziv.payments.dto.ManagerPaymentLinkResponse;
import com.hunt.otziv.payments.dto.PaymentRouteChangeContextResponse;
import com.hunt.otziv.payments.dto.PaymentRouteChangeRequest;
import com.hunt.otziv.payments.dto.PaymentRouteChangeResponse;
import com.hunt.otziv.payments.dto.PaymentRouteChangeTarget;
import com.hunt.otziv.payments.service.PaymentLinkService;
import com.hunt.otziv.payments.service.PaymentRouteChangeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagerPaymentControllerTest {

    @Mock
    private PaymentLinkService paymentLinkService;

    @Mock
    private PaymentRouteChangeService paymentRouteChangeService;

    @Mock
    private Authentication authentication;

    @Test
    void delegatesToTransactionalAuthorizedCreation() {
        ManagerPaymentLinkResponse expected = org.mockito.Mockito.mock(ManagerPaymentLinkResponse.class);
        when(paymentLinkService.createForOrderAuthorized(41L, authentication)).thenReturn(expected);
        ManagerPaymentController controller = new ManagerPaymentController(paymentLinkService, paymentRouteChangeService);

        ManagerPaymentLinkResponse response = controller.createPaymentLink(41L, authentication);

        assertSame(expected, response);
        verify(paymentLinkService).createForOrderAuthorized(41L, authentication);
    }

    @Test
    void doesNotIssueLinkWhenOrderIsOutsideCallerScope() {
        ResponseStatusException denied = new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        org.mockito.Mockito.doThrow(denied)
                .when(paymentLinkService).createForOrderAuthorized(99L, authentication);
        ManagerPaymentController controller = new ManagerPaymentController(paymentLinkService, paymentRouteChangeService);

        assertThrows(ResponseStatusException.class, () -> controller.createPaymentLink(99L, authentication));

        verify(paymentLinkService).createForOrderAuthorized(99L, authentication);
    }

    @Test
    void delegatesPaymentRouteContext() {
        PaymentRouteChangeContextResponse expected = new PaymentRouteChangeContextResponse(
                51L, "Оплата по реквизитам", "Специалист", "CREATED", true, ""
        );
        when(paymentRouteChangeService.context(41L, authentication)).thenReturn(expected);
        ManagerPaymentController controller = new ManagerPaymentController(paymentLinkService, paymentRouteChangeService);

        PaymentRouteChangeContextResponse response = controller.paymentRouteChangeContext(41L, authentication);

        assertSame(expected, response);
        verify(paymentRouteChangeService).context(41L, authentication);
    }

    @Test
    void delegatesConfirmedPaymentRouteChange() {
        PaymentRouteChangeRequest request = new PaymentRouteChangeRequest(
                51L, PaymentRouteChangeTarget.OWNER_TBANK, true
        );
        PaymentRouteChangeResponse expected = new PaymentRouteChangeResponse(
                51L, 52L, PaymentRouteChangeTarget.OWNER_TBANK, true,
                org.mockito.Mockito.mock(ManagerPaymentLinkResponse.class)
        );
        when(paymentRouteChangeService.change(41L, request, authentication)).thenReturn(expected);
        ManagerPaymentController controller = new ManagerPaymentController(paymentLinkService, paymentRouteChangeService);

        PaymentRouteChangeResponse response = controller.changePaymentRoute(41L, request, authentication);

        assertSame(expected, response);
        verify(paymentRouteChangeService).change(41L, request, authentication);
    }
}
