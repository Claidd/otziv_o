package com.hunt.otziv.contractor_payments.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hunt.otziv.contractor_payments.dto.ContractorReturnAmountRequest;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentQueueHealthService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentShadowService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentTargetAccessPolicy;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentVisibilityService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ContractorPaymentJournalAdminControllerTest {

    private final ContractorPaymentVisibilityService visibilityService =
            mock(ContractorPaymentVisibilityService.class);
    private final ContractorPaymentShadowService shadowService = mock(ContractorPaymentShadowService.class);
    private final ContractorPaymentQueueHealthService healthService =
            mock(ContractorPaymentQueueHealthService.class);
    private final ContractorPaymentTargetAccessPolicy targetAccessPolicy =
            mock(ContractorPaymentTargetAccessPolicy.class);
    private final ContractorPaymentJournalAdminController controller =
            new ContractorPaymentJournalAdminController(
                    visibilityService,
                    shadowService,
                    healthService,
                    targetAccessPolicy
            );

    @Test
    void returnedAmountCannotBypassRecipientTargetPolicyThroughAllocationId() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"))
                .when(targetAccessPolicy).requireCanManageAllocationRecipient(91L);
        ContractorReturnAmountRequest request = new ContractorReturnAmountRequest(
                1_000L,
                LocalDateTime.now().minusMinutes(1),
                "Возврат"
        );

        assertThatThrownBy(() -> controller.recordReturnedAmount(91L, request))
                .isInstanceOf(ResponseStatusException.class);

        verify(targetAccessPolicy).requireCanManageAllocationRecipient(91L);
        verify(shadowService, never()).recordObservedReturnAmount(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }
}
