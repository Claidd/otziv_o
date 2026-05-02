package com.hunt.otziv.whatsapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;

@Configuration
public class RestTemplateConfig {
    @Bean
    public RestTemplate restTemplate() {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.getInterceptors().add((request, body, execution) -> {
//            System.out.println("➡️ URL: " + request.getURI());
//            System.out.println("➡️ Method: " + request.getMethod());
//            System.out.println("➡️ Headers: " + request.getHeaders());
//            System.out.println("➡️ Body: " + new String(body, StandardCharsets.UTF_8));
            return execution.execute(request, body);
        });
        return restTemplate;
    }
}

