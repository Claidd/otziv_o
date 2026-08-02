package com.hunt.otziv.whatsapp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class RestTemplateConfigTest {

    @Test
    void configuredGatewaySecretIsAddedToGatewayRequests() {
        WhatsAppProperties properties = new WhatsAppProperties();
        properties.setGatewaySharedSecret("test-only-gateway-secret");
        RestTemplate restTemplate = new RestTemplateConfig().whatsAppRestTemplate(
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                false,
                "",
                8888,
                properties
        );
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("http://whatsapp_lika:3000/groups"))
                .andExpect(header(
                        RestTemplateConfig.INTERNAL_AUTH_HEADER,
                        "test-only-gateway-secret"
                ))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(restTemplate.getForObject(
                "http://whatsapp_lika:3000/groups",
                String.class
        )).isEqualTo("{}");
        server.verify();
    }
}
