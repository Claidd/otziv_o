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

    @Test void archiveHistoryIsReadOnlyBoundedAndDoesNotConsumeUpdates() {
        server.expect(requestTo("https://max.fixture/messages?chat_id=-91&from=1793635200000&count=100"))
                .andExpect(method(HttpMethod.GET)).andExpect(header("Authorization","fixture-token"))
                .andRespond(withSuccess("{\"messages\":[]}",MediaType.APPLICATION_JSON));
        assertThat(client.archiveHistoryPage(-91,1793635200000L).path("messages").isArray()).isTrue();
    }

    @ParameterizedTest @ValueSource(ints={401,403,429,500})
    void failedHistoryRequestIsNeverConvertedToAnEmptyCompleteHistory(int status) {
        server.expect(requestTo("https://max.fixture/messages?chat_id=-91&from=1793635200000&count=100"))
                .andRespond(withStatus(HttpStatus.valueOf(status)));
        assertThatThrownBy(()->client.archiveHistoryPage(-91,1793635200000L)).isInstanceOf(IllegalStateException.class);
    }

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

    @Test
    void documentUsesUploadedTokenAndCaptionInOneMessage() {
        server.expect(requestTo("https://max.fixture/messages?chat_id=91"))
                .andExpect(request -> {
                    var body = new ObjectMapper().readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
                    assertThat(body.path("text").asText()).isEqualTo("Offer");
                    assertThat(body.path("notify").asBoolean()).isTrue();
                    assertThat(body.path("attachments").size()).isEqualTo(1);
                    assertThat(body.path("attachments").get(0).path("type").asText()).isEqualTo("file");
                    assertThat(body.path("attachments").get(0).path("payload").path("token").asText()).isEqualTo("upload-token");
                })
                .andRespond(withSuccess("{\"message\":{\"body\":{\"mid\":\"mid.file\"}}}",MediaType.APPLICATION_JSON));
        assertThat(client.sendDocumentToChatOnce(91L,"Offer","upload-token").messageId()).isEqualTo("mid.file");
    }

    @ParameterizedTest
    @ValueSource(strings={"{\"message\":{\"body\":{\"mid\":true}}}","{\"message\":{\"body\":{\"mid\":123}}}",
            "{\"success\":false,\"message\":{\"body\":{\"mid\":\"mid.file\"}}}"})
    void invalidDocumentReceiptsStayUnknown(String body) {
        server.expect(requestTo("https://max.fixture/messages?chat_id=91")).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
        assertThat(client.sendDocumentToChatOnce(91L,"Offer","upload-token").errorCode()).isEqualTo("operation_unknown");
    }

    @Test
    void uploadUsesProviderHostAndDoesNotDiscloseBotTokenToUploadServer() {
        server.expect(requestTo("https://max.fixture/uploads?type=file")).andExpect(header("Authorization","fixture-token"))
                .andRespond(withSuccess("{\"url\":\"https://fu.oneme.ru/upload.do?token=fixture\"}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://fu.oneme.ru/upload.do?token=fixture")).andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("{\"token\":\"uploaded-file\"}",MediaType.APPLICATION_JSON));
        assertThat(client.uploadDocument(new byte[]{1,2},"offer.txt")).isEqualTo("uploaded-file");
    }

    @Test
    void untrustedUploadUrlNeverReceivesFile() {
        server.expect(requestTo("https://max.fixture/uploads?type=file"))
                .andRespond(withSuccess("{\"url\":\"https://unknown.example/upload\"}",MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.uploadDocument(new byte[]{1},"offer.txt")).isInstanceOf(IllegalStateException.class);
    }
    @Test
    void aFileStillBeingProcessedIsSentAgainAfterGrowingPauses() {
        var pauses = new java.util.ArrayList<Long>();
        client.pauseWith(ms -> pauses.add(ms));
        String notReady = "{\"code\":\"attachment.not.ready\",\"message\":\"Key: errors.process.attachment.file.not.processed\"}";
        server.expect(requestTo("https://max.fixture/messages?chat_id=91")).andRespond(withBadRequest().body(notReady).contentType(MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://max.fixture/messages?chat_id=91")).andRespond(withSuccess(notReady, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://max.fixture/messages?chat_id=91"))
                .andRespond(withSuccess("{\"message\":{\"body\":{\"mid\":\"mid.file\"}}}", MediaType.APPLICATION_JSON));
        assertThat(client.sendDocumentToChatOnce(91L, "Счёт", "upload-token").messageId()).isEqualTo("mid.file");
        assertThat(pauses).containsExactly(1000L, 2000L);
    }

    @Test
    void aFileNeverReadyEndsAsAKnownRefusalWithoutAnUncertainSend() {
        var pauses = new java.util.ArrayList<Long>();
        client.pauseWith(ms -> pauses.add(ms));
        for (int i = 0; i < 5; i++)
            server.expect(requestTo("https://max.fixture/messages?chat_id=91"))
                    .andRespond(withSuccess("{\"code\":\"attachment.not.ready\"}", MediaType.APPLICATION_JSON));
        var result = client.sendDocumentToChatOnce(91L, "Счёт", "upload-token");
        assertThat(result.errorCode()).isEqualTo(MaxBotClient.ATTACHMENT_NOT_READY);
        assertThat(pauses).containsExactly(1000L, 2000L, 4000L, 8000L);
        assertThat(com.hunt.otziv.client_messages.api.ClientMessageDelivery.isKnownUnsent(
                com.hunt.otziv.client_messages.dto.ClientMessageSendResult.failed(result.errorCode(), "x"))).isTrue();
    }

    @Test
    void anUncertainFileAnswerIsNeverRepeated() {
        client.pauseWith(ms -> { throw new AssertionError("no pause for an uncertain answer"); });
        server.expect(requestTo("https://max.fixture/messages?chat_id=91")).andRespond(withServerError());
        assertThat(client.sendDocumentToChatOnce(91L, "Счёт", "upload-token").errorCode()).isEqualTo("operation_unknown");
    }

    @Test
    void uploadAcceptsMaxMediaHostsAndKeepsTheRussianFileName() {
        server.expect(requestTo("https://max.fixture/uploads?type=file"))
                .andRespond(withSuccess("{\"url\":\"https://omu.okcdn.ru/upload.do?sig=fixture\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://omu.okcdn.ru/upload.do?sig=fixture")).andExpect(headerDoesNotExist("Authorization"))
                .andExpect(request -> {
                    String body = ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString(java.nio.charset.StandardCharsets.UTF_8);
                    assertThat(body).contains("name=\"data\"");
                    // Raw UTF-8, as browsers send it: the client sees the real Russian file name in MAX.
                    assertThat(body).contains("filename=\"Счёт на оплату — PB-2.pdf\"");
                })
                .andRespond(withSuccess("{\"fileId\":7,\"token\":\"uploaded-file\"}", MediaType.APPLICATION_JSON));
        assertThat(client.uploadDocument(new byte[]{1, 2}, "Счёт на оплату — PB-2.pdf")).isEqualTo("uploaded-file");
        assertThat(MaxBotClient.trustedUploadHost(java.net.URI.create("https://fu.oneme.ru/api/upload.do"))).isTrue();
        assertThat(MaxBotClient.trustedUploadHost(java.net.URI.create("https://oneme.ru.evil.example/upload"))).isFalse();
        assertThat(MaxBotClient.trustedUploadHost(java.net.URI.create("http://fu.oneme.ru/upload"))).isFalse();
        assertThat(MaxBotClient.trustedUploadHost(java.net.URI.create("https://user@fu.oneme.ru/upload"))).isFalse();
        assertThat(MaxBotClient.trustedUploadHost(java.net.URI.create("https://fu.oneme.ru:8443/upload"))).isFalse();
    }
}
