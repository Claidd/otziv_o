package com.hunt.otziv.b_bots.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "multibrowser")
@Data
public class MultiBrowserProperties {
    private String baseUrl;
    private int heartbeatIntervalSeconds = 20;
    private int heartbeatTimeoutSeconds = 75;
    private int sessionMaxSeconds = 1800;
    private int openingTimeoutSeconds = 90;
    private int stopRetrySeconds = 30;
}
