package com.hunt.otziv.b_bots.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;

@Configuration
public class BrowserRestTemplateConfig {

    @Bean
    @Qualifier("browserRestTemplate")
    public RestTemplate browserRestTemplate(MultiBrowserProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(30));

        RestTemplate restTemplate = new RestTemplate(requestFactory);
        restTemplate.setMessageConverters(List.of(new JacksonJsonHttpMessageConverter()));
        restTemplate.getInterceptors().add((request, body, execution) -> {
            request.getHeaders().set("X-API-Key", properties.requireApiKey());
            return execution.execute(request, body);
        });
        return restTemplate;
    }
}
