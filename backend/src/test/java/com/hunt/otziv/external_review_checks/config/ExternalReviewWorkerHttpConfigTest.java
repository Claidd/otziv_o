package com.hunt.otziv.external_review_checks.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

class ExternalReviewWorkerHttpConfigTest {

    @Test
    void dedicatedClientAndLeaseUseTheSameBoundedTimeoutPolicy() {
        ExternalReviewCheckProperties properties = new ExternalReviewCheckProperties();
        properties.setWorkerConnectTimeout(Duration.ofSeconds(7));
        properties.setWorkerReadTimeout(Duration.ofSeconds(11));
        properties.setScreenshotUploadTimeout(Duration.ofSeconds(13));
        properties.setProcessingLeaseSafetyMargin(Duration.ofSeconds(17));
        properties.setProcessingLease(Duration.ofSeconds(1));

        RestTemplate restTemplate = new ExternalReviewWorkerHttpConfig()
                .externalReviewWorkerRestTemplate(properties);

        SimpleClientHttpRequestFactory requestFactory = simpleRequestFactory(restTemplate);
        assertThat(requestFactory)
                .isInstanceOf(SimpleClientHttpRequestFactory.class);
        assertThat(ReflectionTestUtils.getField(
                requestFactory,
                "connectTimeout"
        )).isEqualTo(7_000);
        assertThat(ReflectionTestUtils.getField(
                requestFactory,
                "readTimeout"
        )).isEqualTo(11_000);
        assertThat(ExternalReviewTimeoutPolicy.processingLease(properties))
                .isEqualTo(Duration.ofSeconds(48));
    }

    @Test
    void configuredProxyIsUsedAndAuthenticatedOnlyByTheDedicatedClient() {
        ExternalReviewCheckProperties properties = new ExternalReviewCheckProperties();
        properties.getProxy().setEnabled(true);
        properties.getProxy().setHost("proxy.internal");
        properties.getProxy().setPort(3128);
        properties.getProxy().setUsername("worker");
        properties.getProxy().setPassword("secret");

        RestTemplate restTemplate = new ExternalReviewWorkerHttpConfig()
                .externalReviewWorkerRestTemplate(properties);

        Proxy proxy = (Proxy) ReflectionTestUtils.getField(
                simpleRequestFactory(restTemplate),
                "proxy"
        );
        assertThat(proxy).isNotNull();
        assertThat(proxy.type()).isEqualTo(Proxy.Type.HTTP);

        String expectedAuthorization = "Basic " + Base64.getEncoder().encodeToString(
                "worker:secret".getBytes(StandardCharsets.UTF_8)
        );
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("http://worker.test/health"))
                .andExpect(header("Proxy-Authorization", expectedAuthorization))
                .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

        assertThat(restTemplate.getForObject("http://worker.test/health", String.class))
                .isEqualTo("ok");
        server.verify();
    }

    @Test
    void responseBodyIsRejectedBeforeDeserializationWhenItExceedsLimit() {
        ExternalReviewCheckProperties properties = new ExternalReviewCheckProperties();
        properties.setWorkerMaxResponseBytes(1_024);
        RestTemplate restTemplate = new ExternalReviewWorkerHttpConfig()
                .externalReviewWorkerRestTemplate(properties);
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("http://worker.test/oversized"))
                .andRespond(withSuccess("x".repeat(1_025), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> restTemplate.getForObject(
                "http://worker.test/oversized",
                String.class
        )).isInstanceOf(RestClientException.class)
                .hasRootCauseInstanceOf(java.io.IOException.class);
        server.verify();
    }

    @Test
    void configuredWorkerSecretIsAddedToEveryDedicatedClientRequest() {
        ExternalReviewCheckProperties properties = new ExternalReviewCheckProperties();
        properties.setWorkerSharedSecret("test-only-internal-secret");
        RestTemplate restTemplate = new ExternalReviewWorkerHttpConfig()
                .externalReviewWorkerRestTemplate(properties);
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("http://worker.test/health"))
                .andExpect(header(
                        ExternalReviewWorkerHttpConfig.INTERNAL_AUTH_HEADER,
                        "test-only-internal-secret"
                ))
                .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

        assertThat(restTemplate.getForObject("http://worker.test/health", String.class))
                .isEqualTo("ok");
        server.verify();
    }

    private SimpleClientHttpRequestFactory simpleRequestFactory(RestTemplate restTemplate) {
        Object current = restTemplate.getRequestFactory();
        for (int depth = 0; depth < 4 && !(current instanceof SimpleClientHttpRequestFactory); depth++) {
            current = ReflectionTestUtils.getField(current, "requestFactory");
        }
        assertThat(current).isInstanceOf(SimpleClientHttpRequestFactory.class);
        return (SimpleClientHttpRequestFactory) current;
    }
}
