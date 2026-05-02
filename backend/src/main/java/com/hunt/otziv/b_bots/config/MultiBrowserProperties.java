package com.hunt.otziv.b_bots.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "multibrowser")
@Data
public class MultiBrowserProperties {
    private String baseUrl;
}
