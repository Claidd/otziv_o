package com.hunt.otziv.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.hunt.otziv.config.api.ApiExceptionHandler;
import com.hunt.otziv.config.metrics.PerformanceMetrics;
import com.hunt.otziv.manager.controller.ApiManagerBoardController;
import com.hunt.otziv.manager.dto.api.ManagerBoardResponse;
import com.hunt.otziv.manager.service.ManagerBoardService;
import com.hunt.otziv.payments.config.TbankPaymentProperties;
import com.hunt.otziv.payments.controller.PublicPaymentController;
import com.hunt.otziv.payments.dto.PublicPaymentInitResponse;
import com.hunt.otziv.payments.service.PaymentLinkService;
import com.hunt.otziv.payments.service.PaymentProfileService;
import com.hunt.otziv.payments.service.TbankRuntimeSettingsService;
import com.hunt.otziv.webhook.security.WebhookClientIpResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

/** Real controller dispatch, validation and advice with isolated application ports; no bank or DB calls. */
class ClientApiMvcContractTest {
    final PaymentLinkService payments = mock(PaymentLinkService.class);
    final ManagerBoardService boards = mock(ManagerBoardService.class);
    final JacksonJsonHttpMessageConverter converter = new JacksonJsonHttpMessageConverter();
    ClientApiContractModel contract;
    MockMvc mvc;

    @BeforeEach void setup() throws Exception {
        Path root = Path.of(System.getProperty("client.contract.root", "..")).toAbsolutePath().normalize();
        contract = new ClientApiContractModel(converter.getMapper(), root); contract.export();
        var ip = mock(WebhookClientIpResolver.class); when(ip.resolve(any())).thenReturn("127.0.0.1");
        mvc = MockMvcBuilders.standaloneSetup(
                new PublicPaymentController(payments, new TbankPaymentProperties(), mock(TbankRuntimeSettingsService.class), mock(PaymentProfileService.class), ip),
                new ApiManagerBoardController(boards, new PerformanceMetrics(new SimpleMeterRegistry())))
                .setMessageConverters(converter).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test void managerPaginationIsBoundByMvcAndActualResponseMatchesGeneratedDto() throws Exception {
        Object sample = ClientApiJacksonFixtureTest.sample(contract,
                contract.schema(converter.getMapper().constructType(ManagerBoardResponse.class), false), false, new HashSet<>());
        ManagerBoardResponse board = converter.getMapper().convertValue(sample, ManagerBoardResponse.class);
        when(boards.getBoard(eq("orders"), eq("Все"), eq(""), eq(2), eq(7), eq("desc"), isNull(), isNull(), eq(""), any(), any())).thenReturn(board);
        String json = mvc.perform(get("/api/manager/board").param("section", "orders").param("pageNumber", "2").param("pageSize", "7"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)).andReturn().getResponse().getContentAsString();
        verify(boards).getBoard(eq("orders"), eq("Все"), eq(""), eq(2), eq(7), eq("desc"), isNull(), isNull(), eq(""), any(), any());
        validate(json, "ManagerBoardResponseOutput");
    }

    @Test void actualPublicInitBindsBodyAndReturnsJacksonContractWithoutTransportRetry() throws Exception {
        when(payments.init(eq("contract-token"), eq("contract@example.invalid"), eq(true), eq(true), eq(true), eq("127.0.0.1"), eq("contract-test")))
                .thenReturn(new PublicPaymentInitResponse("https://bank.invalid/pay", "proof-1", "NEW"));
        String json = mvc.perform(post("/api/payments/public/contract-token/init").contentType(MediaType.APPLICATION_JSON)
                .header("User-Agent", "contract-test").content("{\"email\":\"contract@example.invalid\",\"offerConsent\":true,\"privacyConsent\":true,\"receiptConsent\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paymentId").value("proof-1")).andReturn().getResponse().getContentAsString();
        validate(json, "PublicPaymentInitResponseOutput");
        verify(payments, times(1)).init(anyString(), anyString(), anyBoolean(), anyBoolean(), anyBoolean(), anyString(), anyString());
    }

    @Test void invalidWriteIsRejectedByRealMvcValidationBeforePaymentPort() throws Exception {
        String json = mvc.perform(post("/api/payments/public/contract-token/init").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        validate(json, "ApiExceptionHandlerApiErrorResponseOutput"); verifyNoInteractions(payments);
    }

    @Test void domainConflictKeeps409AndStructuredErrorAndIsNotRetried() throws Exception {
        when(payments.reportManualPayment("contract-token")).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Route changed"));
        String json = mvc.perform(post("/api/payments/public/contract-token/manual-paid"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Route changed")).andReturn().getResponse().getContentAsString();
        validate(json, "ApiExceptionHandlerApiErrorResponseOutput"); verify(payments, times(1)).reportManualPayment("contract-token");
    }

    @Test void retiredPublicCapabilityReallyReturns404WithNoResponsePayload() throws Exception {
        mvc.perform(get("/api/payments/public/tbank-status")).andExpect(status().isNotFound()).andExpect(content().string(""));
        verifyNoInteractions(payments);
    }

    private void validate(String json, String schema) {
        Object actual = converter.getMapper().readValue(json, Object.class);
        ClientApiJacksonFixtureTest.validate(contract, actual, ClientApiJacksonFixtureTest.schema(contract, schema), schema);
    }
}
