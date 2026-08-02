package com.hunt.otziv.payments.controller;

import com.hunt.otziv.payments.dto.ManagerPaymentLinkResponse;
import com.hunt.otziv.payments.service.PaymentLinkService;
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
    private Authentication authentication;

    @Test
    void delegatesToTransactionalAuthorizedCreation() {
        ManagerPaymentLinkResponse expected = org.mockito.Mockito.mock(ManagerPaymentLinkResponse.class);
        when(paymentLinkService.createForOrderAuthorized(41L, authentication)).thenReturn(expected);
        ManagerPaymentController controller = new ManagerPaymentController(paymentLinkService);

        ManagerPaymentLinkResponse response = controller.createPaymentLink(41L, authentication);

        assertSame(expected, response);
        verify(paymentLinkService).createForOrderAuthorized(41L, authentication);
    }

    @Test
    void doesNotIssueLinkWhenOrderIsOutsideCallerScope() {
        ResponseStatusException denied = new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        org.mockito.Mockito.doThrow(denied)
                .when(paymentLinkService).createForOrderAuthorized(99L, authentication);
        ManagerPaymentController controller = new ManagerPaymentController(paymentLinkService);

        assertThrows(ResponseStatusException.class, () -> controller.createPaymentLink(99L, authentication));

        verify(paymentLinkService).createForOrderAuthorized(99L, authentication);
    }
}
