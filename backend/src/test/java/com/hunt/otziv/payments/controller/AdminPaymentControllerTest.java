package com.hunt.otziv.payments.controller;

import com.hunt.otziv.contractor_payments.service.ContractorPaymentTargetAccessPolicy;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentSourceConfirmationRequest;
import com.hunt.otziv.payments.dto.AdminPaymentLinkResponse;
import com.hunt.otziv.payments.dto.CloseManualPaymentUnpaidRequest;
import com.hunt.otziv.payments.dto.ConfirmManualCardPaymentRequest;
import com.hunt.otziv.payments.dto.ReportManualCardPaymentRequest;
import com.hunt.otziv.payments.dto.ManagerManualCardPaymentResultResponse;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;

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

    @Test
    void bankProfileEndpointsKeepLegacyTbankAliases() throws Exception {
        GetMapping getMapping = AdminPaymentController.class
                .getMethod("bankProfiles")
                .getAnnotation(GetMapping.class);
        PutMapping assignmentsMapping = AdminPaymentController.class
                .getMethod(
                        "updateBankProfileAssignments",
                        com.hunt.otziv.payments.dto.UpdateManagerPaymentProfilesRequest.class
                )
                .getAnnotation(PutMapping.class);
        PutMapping policiesMapping = AdminPaymentController.class
                .getMethod(
                        "updatePaymentProfilePolicies",
                        com.hunt.otziv.payments.dto.UpdatePaymentProfilePoliciesRequest.class
                )
                .getAnnotation(PutMapping.class);

        org.junit.jupiter.api.Assertions.assertArrayEquals(
                new String[]{
                        "/api/admin/payments/bank-profiles",
                        "/api/admin/payments/tbank-profiles"
                },
                getMapping.value()
        );
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                new String[]{
                        "/api/admin/payments/bank-profiles/manager-assignments",
                        "/api/admin/payments/tbank-profiles/manager-assignments"
                },
                assignmentsMapping.value()
        );
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                new String[]{
                        "/api/admin/payments/bank-profiles/policies",
                        "/api/admin/payments/tbank-profiles/policies"
                },
                policiesMapping.value()
        );
    }

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
    void confirmOrderManualCardPaymentReturnsOnlyManagerWorkflowResult() {
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
        ManagerManualCardPaymentResultResponse expected =
                ManagerManualCardPaymentResultResponse.ownerApprovalPending(25047L, 7258L);
        when(paymentLinkService.submitManagerManualCardPaymentForOrder(
                25047L,
                request.reason(),
                null,
                null,
                null,
                null,
                "manager@example.ru",
                authentication
        )).thenReturn(expected);

        var response = controller.confirmOrderManualCardPayment(25047L, request, authentication);

        assertSame(expected, response.getBody());
        verify(paymentLinkService).submitManagerManualCardPaymentForOrder(
                25047L,
                request.reason(),
                null,
                null,
                null,
                null,
                "manager@example.ru",
                authentication
        );
    }

    @Test
    void allAdministrativeLinkMutationsConcealForbiddenTargetBeforeServiceCall() {
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
                () -> controller.confirmContractorPaymentSource(
                        901L,
                        new ContractorPaymentSourceConfirmationRequest(
                                true,
                                true,
                                10_000L,
                                LocalDateTime.now(),
                                "Проверена выписка"
                        ),
                        authentication
                )
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

        verify(contractorPaymentTargetAccessPolicy, times(8)).requireCanManagePaymentLink(901L);
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
