package com.hunt.otziv.common_billing.controller;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hunt.otziv.common_billing.dto.CommonManualPaymentTaskReturnRequest;
import com.hunt.otziv.common_billing.dto.CommonManualPaymentTaskReturnResponse;
import com.hunt.otziv.common_billing.service.CommonManualPaymentTaskReturnService;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CommonManualPaymentTaskReturnControllerTest {

    private final CommonManualPaymentTaskReturnService returnService =
            mock(CommonManualPaymentTaskReturnService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new CommonManualPaymentTaskReturnController(returnService)).build();
    }

    @Test
    void endpointIsRestrictedToAdminAndOwnerSoManagerCannotInvokeIt() throws Exception {
        Method method = CommonManualPaymentTaskReturnController.class.getMethod(
                "record", Long.class, CommonManualPaymentTaskReturnRequest.class,
                java.security.Principal.class);
        PreAuthorize authorization = method.getAnnotation(PreAuthorize.class);

        assertEquals("hasAnyRole('ADMIN', 'OWNER')", authorization.value());
    }

    @Test
    void exactReturnRequestUsesServerActorAndResponseIsNoStore() throws Exception {
        when(returnService.record(eq(77L), any(), eq("owner-user"))).thenReturn(
                new CommonManualPaymentTaskReturnResponse(77L, 801L, 9L, 40_000L, 40_000L, false));
        String json = """
                {
                  "attributionId":801,
                  "evidenceReference":"evidence-77",
                  "cumulativeReturnedKopecks":40000,
                  "reason":"Возврат подтвержден выпиской"
                }
                """;

        mockMvc.perform(post("/api/common-billing/invoices/77/manual-task-return")
                        .principal(() -> "owner-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Pragma", "no-cache"));

        ArgumentCaptor<CommonManualPaymentTaskReturnRequest> request =
                ArgumentCaptor.forClass(CommonManualPaymentTaskReturnRequest.class);
        verify(returnService).record(eq(77L), request.capture(), eq("owner-user"));
        assertEquals(801L, request.getValue().attributionId());
        assertEquals("evidence-77", request.getValue().evidenceReference());
        assertEquals(40_000L, request.getValue().cumulativeReturnedKopecks());
    }

    @Test
    void missingExactEvidenceIsRejectedBeforeService() throws Exception {
        mockMvc.perform(post("/api/common-billing/invoices/77/manual-task-return")
                        .principal(() -> "owner-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "attributionId":801,
                                  "cumulativeReturnedKopecks":40000,
                                  "reason":"Возврат подтвержден выпиской"
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(returnService, never()).record(any(), any(), any());
    }
}
