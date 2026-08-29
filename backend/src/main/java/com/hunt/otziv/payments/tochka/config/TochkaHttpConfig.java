package com.hunt.otziv.payments.tochka.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class TochkaHttpConfig {

    @Bean
    @Qualifier("tochkaRestTemplate")
    public RestTemplate tochkaRestTemplate(TochkaPaymentProperties properties) {
        properties.requireValidTimeouts();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return new RestTemplate(requestFactory);
    }
}
