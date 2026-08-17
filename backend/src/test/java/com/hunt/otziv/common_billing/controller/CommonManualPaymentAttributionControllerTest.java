package com.hunt.otziv.common_billing.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hunt.otziv.common_billing.dto.CommonManualPaymentAttributionRequest;
import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.contractor_payments.service.ContractorActualPaymentAttributionFlowPolicy;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentTargetAccessPolicy;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CommonManualPaymentAttributionControllerTest {

    private final CommonBillingService commonBillingService = mock(CommonBillingService.class);
    private final ContractorPaymentTargetAccessPolicy targetAccessPolicy =
            mock(ContractorPaymentTargetAccessPolicy.class);
    private final ContractorActualPaymentAttributionFlowPolicy flowPolicy =
            mock(ContractorActualPaymentAttributionFlowPolicy.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new CommonManualPaymentAttributionController(
                commonBillingService,
                targetAccessPolicy,
                flowPolicy
        )).build();
    }

    @Test
    void modeResponseIsNoStore() throws Exception {
        when(flowPolicy.attributionRequired()).thenReturn(true);

        mockMvc.perform(get("/api/common-billing/invoices/77/manual-payment-mode"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Pragma", "no-cache"));

        verify(targetAccessPolicy).requireCanManageCommonInvoice(77L);
    }

    @Test
    void frozenTaskRouteKeepsTypedEndpointAvailableWhenGlobalFlagIsOff() throws Exception {
        when(flowPolicy.attributionRequired()).thenReturn(false);
        when(commonBillingService.hasFrozenManualTaskRoute(77L)).thenReturn(true);

        mockMvc.perform(get("/api/common-billing/invoices/77/manual-payment-options"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")));

        verify(flowPolicy).requireAttributionFlowOrFrozenTask(true);
        verify(flowPolicy, never()).requireAttributionFlow();
    }

    @Test
    void localDateTimeWithoutUtcSuffixReachesAttributedEndpointAndResponseIsNoStore() throws Exception {
        String json = """
                {
                  "idempotencyKey":"batch-77",
                  "finalAccountingAcknowledged":true,
                  "paymentReceived":true,
                  "effectiveAt":"2026-08-15T12:34:56",
                  "reason":"Клиент перевел владельцу",
                  "receiptUrl":"https://example.test/receipt/77",
                  "attributions":[{
                    "rowKey":"owner",
                    "recipientType":"OWNER",
                    "recipientProfileId":null,
                    "amountKopecks":100000
                  }]
                }
                """;

        mockMvc.perform(post("/api/common-billing/invoices/77/paid-with-attributions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Pragma", "no-cache"));

        ArgumentCaptor<CommonManualPaymentAttributionRequest> request =
                ArgumentCaptor.forClass(CommonManualPaymentAttributionRequest.class);
        verify(commonBillingService).markPaidWithAttributions(eq(77L), request.capture(), any());
        org.junit.jupiter.api.Assertions.assertEquals(
                LocalDateTime.of(2026, 8, 15, 12, 34, 56),
                request.getValue().effectiveAt()
        );
    }
}
