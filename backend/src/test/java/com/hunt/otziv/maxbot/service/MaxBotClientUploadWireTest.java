package com.hunt.otziv.maxbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The bytes on the wire, through the same request factory as production, not a mock request. */
class MaxBotClientUploadWireTest {
    private HttpServer server;
    private final AtomicReference<Headers> headers = new AtomicReference<>();
    private final AtomicReference<byte[]> body = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String answer = "{\"fileId\":7,\"token\":\"uploaded-file\"}";
    private MaxBotClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            headers.set(exchange.getRequestHeaders());
            body.set(exchange.getRequestBody().readAllBytes());
            byte[] reply = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", status == 200 ? "application/json" : "text/html");
            exchange.sendResponseHeaders(status, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        client = new MaxBotClient(new RestTemplate(new SimpleClientHttpRequestFactory()), new ObjectMapper(), "fixture-token", "https://max.fixture");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private URI uploadUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/upload.do?sig=fixture");
    }

    @Test
    void theFileGoesAsOneSizedMultipartPartLikeTheDocumentedCurlUpload() {
        byte[] pdf = "%PDF-1.4 fixture".getBytes(StandardCharsets.US_ASCII);

        String reply = client.postFile(uploadUrl(), pdf, "Счёт на оплату — PB-2-34954893.pdf");

        assertThat(reply).contains("uploaded-file");
        Headers sent = headers.get();
        assertThat(sent.getFirst("Transfer-encoding")).isNull();
        assertThat(sent.getFirst("Content-length")).isEqualTo(String.valueOf(body.get().length));
        assertThat(sent.getFirst("Accept")).isEqualTo("*/*");
        assertThat(sent.getFirst("Authorization")).isNull();
        assertThat(sent.getFirst("User-agent")).isEqualTo("OtzivMaxBot/1.0");
        String contentType = sent.getFirst("Content-type");
        assertThat(contentType).startsWith("multipart/form-data;boundary=");
        String boundary = contentType.substring(contentType.indexOf("boundary=") + 9);

        var expected = new ByteArrayOutputStream();
        expected.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"data\"; "
                + "filename=\"Счёт на оплату — PB-2-34954893.pdf\"\r\nContent-Type: application/pdf\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        expected.writeBytes(pdf);
        expected.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        assertThat(body.get()).isEqualTo(expected.toByteArray());
    }

    @Test
    void aNameCannotBreakThePartHeaderAndAnUnknownTypeIsSentAsBytes() {
        client.postFile(uploadUrl(), new byte[]{1, 2, 3}, "a\"b\r\nX-Injected: 1.bin");

        String text = new String(body.get(), StandardCharsets.UTF_8);
        assertThat(text).contains("filename=\"a_b__X-Injected: 1.bin\"").doesNotContain("\r\nX-Injected");
        assertThat(text).contains("Content-Type: application/octet-stream");
    }

    @Test
    void aRefusalKeepsAShortHintFromTheErrorPageWithoutTheSignedLink() {
        status = 406;
        answer = "<html><body><h1>Not Acceptable</h1><p>Unsupported upload, see https://fu.oneme.ru/api/upload.do?sig=secret</p></body></html>";

        assertThatThrownBy(() -> client.postFile(uploadUrl(), new byte[]{1}, "x.pdf"))
                .isInstanceOf(HttpClientErrorException.class);
        String hint = MaxBotClient.safeSnippet(answer);
        assertThat(hint).isEqualTo("Not Acceptable Unsupported upload, see URL");
        assertThat(MaxBotClient.safeSnippet("")).isEqualTo("-");
    }
}
