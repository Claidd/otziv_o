package com.hunt.otziv.payments.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hunt.otziv.payments.service.PaymentLinkService;
import com.hunt.otziv.payments.tochka.service.TochkaWebhookVerificationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TochkaWebhookControllerTest {

    private static final String RAW_JWT = "header.payload.signature";

    private PaymentLinkService paymentLinkService;
    private TochkaWebhookController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        paymentLinkService = mock(PaymentLinkService.class);
        controller = new TochkaWebhookController(paymentLinkService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void acceptsOnlyTextPlainDelegatesExactBodyAndAcknowledgesAfterSuccess() throws Exception {
        mockMvc.perform(post("/api/payments/tochka/webhook")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(RAW_JWT))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("OK"));

        verify(paymentLinkService).handleTochkaWebhook(RAW_JWT);
    }

    @Test
    void rejectsNonTextContentWithoutCallingPaymentService() throws Exception {
        mockMvc.perform(post("/api/payments/tochka/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jwt\":\"" + RAW_JWT + "\"}"))
                .andExpect(status().isUnsupportedMediaType());

        verify(paymentLinkService, never()).handleTochkaWebhook(
                org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    void propagatesVerificationFailureInsteadOfAcknowledgingWebhook() {
        TochkaWebhookVerificationException failure =
                new TochkaWebhookVerificationException("invalid signed webhook");
        doThrow(failure).when(paymentLinkService).handleTochkaWebhook(RAW_JWT);

        assertThatThrownBy(() -> controller.webhook(RAW_JWT))
                .isSameAs(failure);

        verify(paymentLinkService).handleTochkaWebhook(RAW_JWT);
    }
}
