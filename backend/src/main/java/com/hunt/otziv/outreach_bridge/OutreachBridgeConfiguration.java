package com.hunt.otziv.outreach_bridge;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutreachBridgeProperties.class)
public class OutreachBridgeConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "outreach-bridge", name = "enabled", havingValue = "true")
    SmartInitializingSingleton outreachBridgePropertiesValidator(OutreachBridgeProperties properties) {
        return properties::validate;
    }
}
