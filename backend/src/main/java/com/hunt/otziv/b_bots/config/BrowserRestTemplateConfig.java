package com.hunt.otziv.b_bots.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class BrowserRestTemplateConfig {

    @Bean
    @Qualifier("browserRestTemplate")
    public RestTemplate browserRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(30))
                // Нам нужен именно JSON-конвертер
                .messageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }
}
