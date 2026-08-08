package com.hunt.otziv.payments.controller;

import com.hunt.otziv.contractor_payments.service.ContractorPaymentTargetAccessPolicy;
import com.hunt.otziv.payments.dto.AdminPaymentLinkResponse;
import com.hunt.otziv.payments.dto.CloseManualPaymentUnpaidRequest;
import com.hunt.otziv.payments.dto.ConfirmManualCardPaymentRequest;
import com.hunt.otziv.payments.dto.ReportManualCardPaymentRequest;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
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
    private ContractorPaymentTargetAccessPolicy contractorPaymentTargetAccessPolicy;

    @Mock
    private Authentication authentication;

    @Test
    void orderManualCardReportIsAvailableToManagersOwnersAndAdmins() throws Exception {
        PreAuthorize authorization = AdminPaymentController.class
                .getMethod(
                        "confirmOrderManualCardPayment",
                        Long.class,
                        ReportManualCardPaymentRequest.class,
                        Authentication.class
                )
                .getAnnotation(PreAuthorize.class);

        assertEquals("hasAnyRole('ADMIN', 'OWNER', 'MANAGER')", authorization.value());
    }

    @Test
    void closeManualPaymentAsUnpaidPassesExplicitAssertionsNoteAndActor() {
        AdminPaymentController controller = new AdminPaymentController(
                paymentLinkService,
                paymentProfileService,
                runtimeSettingsService,
                manualPaymentTaskService,
                contractorPaymentTargetAccessPolicy
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
    void confirmOrderManualCardPaymentPassesOnlyManagerReasonAndDoesNotReturnProviderData() {
        AdminPaymentController controller = new AdminPaymentController(
                paymentLinkService,
                paymentProfileService,
                runtimeSettingsService,
                manualPaymentTaskService,
                contractorPaymentTargetAccessPolicy
        );
        ReportManualCardPaymentRequest request = new ReportManualCardPaymentRequest(
                "Клиент оплатил переводом по номеру телефона"
        );
        when(authentication.getName()).thenReturn("manager@example.ru");

        controller.confirmOrderManualCardPayment(25047L, request, authentication);

        verify(paymentLinkService).reportPaidByManualCardTransferForOrder(
                25047L,
                request.reason(),
                "manager@example.ru",
                authentication
        );
    }

    @Test
    void allSevenAdministrativeLinkMutationsConcealForbiddenTargetBeforeServiceCall() {
        AdminPaymentController controller = new AdminPaymentController(
                paymentLinkService,
                paymentProfileService,
                runtimeSettingsService,
                manualPaymentTaskService,
                contractorPaymentTargetAccessPolicy
        );
        ResponseStatusException concealed = new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Платежная ссылка не найдена"
        );
        doThrow(concealed).when(contractorPaymentTargetAccessPolicy).requireCanManagePaymentLink(901L);

        assertEquals(HttpStatus.NOT_FOUND, assertThrows(
                ResponseStatusException.class,
                () -> controller.cancelTbankPayment(901L)
        ).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(
                ResponseStatusException.class,
                () -> controller.confirmManualCardPayment(901L, null, authentication)
        ).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(
                ResponseStatusException.class,
                () -> controller.resolveAmbiguousTbankInit(901L, null, authentication)
        ).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(
                ResponseStatusException.class,
                () -> controller.confirmManualPayment(901L, authentication)
        ).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(
                ResponseStatusException.class,
                () -> controller.closeManualPaymentAsUnpaid(901L, null, authentication)
        ).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(
                ResponseStatusException.class,
                () -> controller.markManualPaymentReceipt(901L, authentication)
        ).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(
                ResponseStatusException.class,
                () -> controller.markLegacyManualPaymentReceiptNotRequired(901L, authentication)
        ).getStatusCode());

        verify(contractorPaymentTargetAccessPolicy, times(7)).requireCanManagePaymentLink(901L);
        verifyNoInteractions(paymentLinkService);
    }

    @Test
    void globalArchiveRunIsDeniedBeforeDryRunOrLiveServiceCall() {
        AdminPaymentController controller = new AdminPaymentController(
                paymentLinkService,
                paymentProfileService,
                runtimeSettingsService,
                manualPaymentTaskService,
                contractorPaymentTargetAccessPolicy
        );
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Доступ запрещён"))
                .when(contractorPaymentTargetAccessPolicy).requireCanManageAllPaymentLinks();

        assertEquals(HttpStatus.FORBIDDEN, assertThrows(
                ResponseStatusException.class,
                () -> controller.archiveClosedPaymentLinks(true, 10)
        ).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(
                ResponseStatusException.class,
                () -> controller.archiveClosedPaymentLinks(false, 10)
        ).getStatusCode());

        verify(contractorPaymentTargetAccessPolicy, times(2)).requireCanManageAllPaymentLinks();
        verifyNoInteractions(paymentLinkService);
    }
}
