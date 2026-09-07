package com.hunt.otziv.maxbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class MaxBotClientTest {
    private RestTemplate transport;
    private MockRestServiceServer server;
    private MaxBotClient client;
    private static final String ENDPOINT = "https://max.fixture/messages?chat_id=91&disable_link_preview=true";

    @BeforeEach
    void setUp() {
        transport = new RestTemplate();
        server = MockRestServiceServer.bindTo(transport).build();
        client = new MaxBotClient(transport, new ObjectMapper(), "fixture-token", "https://max.fixture/");
    }

    @AfterEach
    void verifyRequests() { server.verify(); }

    @Test
    void singleAttemptPreservesPayloadAndRequiresCreatedMessageReceipt() {
        String text = "x".repeat(3900);
        server.expect(requestTo(ENDPOINT)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "fixture-token"))
                .andExpect(request -> {
                    var body = new ObjectMapper().readTree(
                            ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
                    assertThat(body.size()).isEqualTo(2);
                    assertThat(body.path("text").textValue()).isEqualTo(text);
                    assertThat(body.path("notify").booleanValue()).isTrue();
                })
                .andRespond(withSuccess("{\"message\":{\"body\":{\"mid\":\"mid.fixture_123-A\"}}}", MediaType.APPLICATION_JSON));
        var result = client.sendMessageToChatOnce(91L, text);
        assertThat(result.confirmed()).isTrue();
        assertThat(result.messageId()).isEqualTo("mid.fixture_123-A");
        assertThat(result.errorCode()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "null", "not-json", "{\"success\":true}",
            "{\"message\":{\"body\":{\"mid\":null}}}", "{\"message\":{\"body\":{\"mid\":0}}}",
            "{\"message\":{\"body\":{\"mid\":\"0\"}}}", "{\"message\":{\"body\":{\"mid\":\"-1\"}}}",
            "{\"message\":{\"body\":{\"mid\":\" \"}}}", "{\"message\":{\"body\":{\"mid\":\" id \"}}}",
            "{\"message\":{\"body\":{\"mid\":true}}}", "{\"message_id\":81}",
            "{\"success\":false,\"message\":{\"body\":{\"mid\":\"id\"}}}",
            "{\"error\":\"rejected\",\"message\":{\"body\":{\"mid\":\"id\"}}}"})
    void httpSuccessWithoutValidPositiveReceiptRemainsUnknownAndNeverRetries(String body) {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        var result = client.sendMessageToChatOnce(91L, "fixture");
        assertThat(result.confirmed()).isFalse();
        assertThat(result.messageId()).isNull();
        assertThat(result.errorCode()).isEqualTo("operation_unknown");
    }

    @Test
    void timeoutRemainsUnknownAfterExactlyOneRequest() {
        server.expect(requestTo(ENDPOINT)).andRespond(withException(new IOException("fixture timeout after write")));
        assertThat(client.sendMessageToChatOnce(91L, "fixture").errorCode()).isEqualTo("operation_unknown");
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 429, 500, 503})
    void httpFailureHasNoApplicationRetry(int status) {
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.valueOf(status)));
        assertThat(client.sendMessageToChatOnce(91L, "fixture").errorCode()).isEqualTo("operation_unknown");
    }

    @Test
    void oversizeIsRejectedBeforeAnyChunkOrHttpAttempt() {
        assertThat(client.sendMessageToChatOnce(91L, "x".repeat(3901)).errorCode()).isEqualTo("payload_too_large");
    }

    @Test
    void invalidLocalInputAndMissingTokenNeverReachTransport() {
        assertThat(client.sendMessageToChatOnce(null, "fixture").errorCode()).isEqualTo("invalid_request");
        assertThat(client.sendMessageToChatOnce(91L, " \n ").errorCode()).isEqualTo("invalid_request");
        MaxBotClient unconfigured = new MaxBotClient(transport, new ObjectMapper(), "", "https://max.fixture");
        assertThat(unconfigured.sendMessageToChatOnce(91L, "fixture").errorCode()).isEqualTo("max_not_configured");
    }

    @Test
    void legacyEntryRetainsItsChunkedDeliveryContract() {
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThat(client.sendMessageToChat(91L, "x".repeat(3901))).isFalse();
    }

    @Test
    void legacyShortMessageKeepsHttpSuccessCompatibility() {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThat(client.sendMessageToChat(91L, "fixture")).isTrue();
    }
}
