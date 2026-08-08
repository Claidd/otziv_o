package com.hunt.otziv.common_billing.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hunt.otziv.common_billing.service.CommonBillingPublicationApprovalFailureMarker;
import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentTargetAccessPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class CommonBillingAdminControllerContractorTargetPolicyTest {

    private static final long INVOICE_ID = 95L;

    private final CommonBillingService commonBillingService = mock(CommonBillingService.class);
    private final CommonBillingPublicationApprovalFailureMarker approvalFailureMarker =
            mock(CommonBillingPublicationApprovalFailureMarker.class);
    private final ContractorPaymentTargetAccessPolicy targetAccessPolicy =
            mock(ContractorPaymentTargetAccessPolicy.class);
    private final CommonBillingAdminController controller = new CommonBillingAdminController(
            commonBillingService,
            approvalFailureMarker,
            targetAccessPolicy
    );

    @Test
    void everyInvoiceMutationAuthorizesTargetBeforeCallingBusinessService() {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"))
                .when(targetAccessPolicy)
                .requireCanManageCommonInvoice(INVOICE_ID);

        List<Executable> mutations = List.of(
                () -> controller.deleteInvoice(INVOICE_ID, null),
                () -> controller.sendInvoice(INVOICE_ID),
                () -> controller.remind(INVOICE_ID),
                () -> controller.markPaid(INVOICE_ID, null, null),
                () -> controller.retryAttention(INVOICE_ID),
                () -> controller.resolveAttention(INVOICE_ID),
                () -> controller.repairPaymentRoute(INVOICE_ID),
                () -> controller.reportManualCardPayment(INVOICE_ID, null, null),
                () -> controller.resolveTechnicalTail(INVOICE_ID),
                () -> controller.resolvePaymentSuccessNotification(INVOICE_ID),
                () -> controller.applyLatePayment(INVOICE_ID),
                () -> controller.confirmFinalPaymentCancelCheck(INVOICE_ID),
                () -> controller.confirmPaymentInitCheck(INVOICE_ID, null),
                () -> controller.markUnpaid(INVOICE_ID),
                () -> controller.markBan(INVOICE_ID, null),
                () -> controller.archiveInvoice(INVOICE_ID, null, null),
                () -> controller.markOrderPaid(INVOICE_ID, 7L, null, null),
                () -> controller.approveReviewOrders(INVOICE_ID),
                () -> controller.detachOrder(INVOICE_ID, 7L)
        );

        for (Executable mutation : mutations) {
            assertThatThrownBy(mutation::execute)
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(error -> org.assertj.core.api.Assertions.assertThat(
                                    ((ResponseStatusException) error).getStatusCode()
                            )
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        verify(targetAccessPolicy, times(mutations.size())).requireCanManageCommonInvoice(INVOICE_ID);
        verifyNoInteractions(commonBillingService, approvalFailureMarker);
    }
}
