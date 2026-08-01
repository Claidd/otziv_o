package com.hunt.otziv.integration.outbox;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IntegrationOutboxProperties.class)
class IntegrationOutboxConfiguration {
}
