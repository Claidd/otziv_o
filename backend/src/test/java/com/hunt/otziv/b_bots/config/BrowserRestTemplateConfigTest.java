package com.hunt.otziv.b_bots.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class BrowserRestTemplateConfigTest {

    @Test
    void sendsExactlyOneApiKeyHeader() {
        MultiBrowserProperties properties = new MultiBrowserProperties();
        properties.setEnabled(true);
        properties.setApiKey("test-api-key");
        RestTemplate restTemplate = new BrowserRestTemplateConfig().browserRestTemplate(properties);
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("https://browser.internal/connect"))
                .andExpect(request -> assertThat(request.getHeaders().get("X-API-Key"))
                        .containsExactly("test-api-key"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        restTemplate.postForEntity(
                "https://browser.internal/connect",
                Map.of("externalKey", "test"),
                Map.class
        );

        server.verify();
    }

    @Test
    void refusesRequestWhenApiKeyIsMissing() {
        MultiBrowserProperties properties = new MultiBrowserProperties();
        properties.setEnabled(true);
        RestTemplate restTemplate = new BrowserRestTemplateConfig().browserRestTemplate(properties);
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("https://browser.internal/connect"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> restTemplate.postForEntity(
                "https://browser.internal/connect",
                Map.of("externalKey", "test"),
                Map.class
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MULTIBROWSER_API_KEY");
    }
}
