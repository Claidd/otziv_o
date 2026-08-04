package com.hunt.otziv.payments.controller;

import com.hunt.otziv.payments.dto.AdminPaymentLinkResponse;
import com.hunt.otziv.payments.dto.CloseManualPaymentUnpaidRequest;
import com.hunt.otziv.payments.dto.ConfirmManualCardPaymentRequest;
import com.hunt.otziv.payments.service.ManualPaymentTaskService;
import com.hunt.otziv.payments.service.PaymentLinkService;
import com.hunt.otziv.payments.service.PaymentProfileService;
import com.hunt.otziv.payments.service.TbankRuntimeSettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminPaymentControllerTest {

    @Mock
    private PaymentLinkService paymentLinkService;

    @Mock
    private PaymentProfileService paymentProfileService;

    @Mock
    private TbankRuntimeSettingsService runtimeSettingsService;

    @Mock
    private ManualPaymentTaskService manualPaymentTaskService;

    @Mock
    private Authentication authentication;

    @Test
    void orderManualCardFallbackIsRestrictedToOwnerAndAdmin() throws Exception {
        PreAuthorize authorization = AdminPaymentController.class
                .getMethod(
                        "confirmOrderManualCardPayment",
                        Long.class,
                        ConfirmManualCardPaymentRequest.class,
                        Authentication.class
                )
                .getAnnotation(PreAuthorize.class);

        assertEquals("hasAnyRole('ADMIN', 'OWNER')", authorization.value());
    }

    @Test
    void closeManualPaymentAsUnpaidPassesExplicitAssertionsNoteAndActor() {
        AdminPaymentController controller = new AdminPaymentController(
                paymentLinkService,
                paymentProfileService,
                runtimeSettingsService,
                manualPaymentTaskService
        );
        CloseManualPaymentUnpaidRequest request = new CloseManualPaymentUnpaidRequest(
                true,
                true,
                "Выписка карты получателя проверена, перевода нет"
        );
        AdminPaymentLinkResponse expected = org.mockito.Mockito.mock(AdminPaymentLinkResponse.class);
        when(authentication.getName()).thenReturn("owner@example.ru");
        when(paymentLinkService.closeManualAsUnpaid(
                3885L,
                true,
                true,
                request.note(),
                "owner@example.ru",
                authentication
        )).thenReturn(expected);

        AdminPaymentLinkResponse response = controller.closeManualPaymentAsUnpaid(
                3885L,
                request,
                authentication
        );

        assertSame(expected, response);
        verify(paymentLinkService).closeManualAsUnpaid(
                3885L,
                true,
                true,
                request.note(),
                "owner@example.ru",
                authentication
        );
    }

    @Test
    void confirmOrderManualCardPaymentPassesOrderEvidenceAndDoesNotReturnProviderData() {
        AdminPaymentController controller = new AdminPaymentController(
                paymentLinkService,
                paymentProfileService,
                runtimeSettingsService,
                manualPaymentTaskService
        );
        ConfirmManualCardPaymentRequest request = new ConfirmManualCardPaymentRequest(
                true,
                true,
                100_000L,
                "04.08 20:40, карта *1234, перевод за заказ",
                null
        );
        when(authentication.getName()).thenReturn("manager@example.ru");

        controller.confirmOrderManualCardPayment(25047L, request, authentication);

        verify(paymentLinkService).confirmPaidByManualCardTransferForOrder(
                25047L,
                true,
                true,
                100_000L,
                request.note(),
                null,
                "manager@example.ru",
                authentication
        );
    }
}
