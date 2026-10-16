package com.hunt.otziv.payments.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.common_billing.controller.PublicCommonInvoiceController;
import com.hunt.otziv.common_billing.dto.PublicCommonInvoiceResponse;
import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.contractor_payments.controller.ContractorPaymentAdminController;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentProfileRequest;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentProfileResponse;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.contractor_payments.service.ContractorAccrualAdjustmentService;
import com.hunt.otziv.contractor_payments.service.ContractorDirectSettlementService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentProfileService;
import com.hunt.otziv.payments.config.TbankPaymentProperties;
import com.hunt.otziv.payments.dto.PublicPaymentLinkResponse;
import com.hunt.otziv.payments.service.PaymentLinkService;
import com.hunt.otziv.payments.service.PaymentProfileService;
import com.hunt.otziv.payments.service.TbankRuntimeSettingsService;
import com.hunt.otziv.webhook.security.WebhookClientIpResolver;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

class PaymentRequisitesNoStoreControllerTest {

    @Test
    void ordinaryPublicReadDisablesCachingAndClientReportIsRetired() {
        PaymentLinkService service = mock(PaymentLinkService.class);
        PublicPaymentLinkResponse readBody = mock(PublicPaymentLinkResponse.class);
        when(service.publicLink("ordinary-token")).thenReturn(readBody);
        PublicPaymentController controller = new PublicPaymentController(
                service,
                mock(TbankPaymentProperties.class),
                mock(TbankRuntimeSettingsService.class),
                mock(PaymentProfileService.class),
                mock(WebhookClientIpResolver.class)
        );

        assertNoStore(controller.paymentLink("ordinary-token"), readBody);
        assertThatThrownBy(() -> controller.reportManualPayment("ordinary-token"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("410 GONE");
        verify(service).publicLink("ordinary-token");
        verifyNoMoreInteractions(service);
    }

    @Test
    void commonPublicReadDisablesCachingAndClientReportIsRetired() {
        CommonBillingService service = mock(CommonBillingService.class);
        PublicCommonInvoiceResponse readBody = mock(PublicCommonInvoiceResponse.class);
        when(service.publicInvoice("group-token")).thenReturn(readBody);
        PublicCommonInvoiceController controller = new PublicCommonInvoiceController(service);

        assertNoStore(controller.commonInvoice("group-token"), readBody);
        assertThatThrownBy(() -> controller.reportCommonInvoicePaid("group-token"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("410 GONE");
        verify(service).publicInvoice("group-token");
        verifyNoMoreInteractions(service);
    }

    @Test
    void adminProfileReadAndUpdateResponsesDisableCaching() {
        ContractorPaymentProfileService profileService = mock(ContractorPaymentProfileService.class);
        ContractorPaymentProfileResponse profile = mock(ContractorPaymentProfileResponse.class);
        when(profile.role()).thenReturn(ContractorRole.SPECIALIST);
        when(profileService.getForUser(5L)).thenReturn(List.of(profile));
        ContractorPaymentAdminController controller = new ContractorPaymentAdminController(
                profileService,
                mock(ContractorAccrualAdjustmentService.class),
                mock(ContractorDirectSettlementService.class)
        );
        ContractorPaymentProfileRequest request = new ContractorPaymentProfileRequest(
                ContractorRole.SPECIALIST,
                0L,
                true,
                false,
                "Получатель",
                "2202208238396676",
                "Банк",
                "Комментарий",
                0L,
                ""
        );

        assertNoStore(controller.getProfiles(5L), List.of(profile));
        assertNoStore(controller.updateProfile(5L, request), profile);
        verify(profileService).update(5L, request);
    }

    private static void assertNoStore(ResponseEntity<?> response, Object expectedBody) {
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl())
                .isEqualTo(CacheControl.noStore().getHeaderValue());
        assertThat(response.getHeaders().getFirst(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
        assertThat(response.getBody()).isEqualTo(expectedBody);
    }
}
