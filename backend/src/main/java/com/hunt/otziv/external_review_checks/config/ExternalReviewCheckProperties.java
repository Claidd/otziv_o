package com.hunt.otziv.external_review_checks.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "external-review-check")
public class ExternalReviewCheckProperties {
    private boolean enabled = false;
    private String workerBaseUrl = "http://localhost:3097";
    private int confirmationDelayDays = 3;
    private int batchSize = 20;
    private int maxAttempts = 5;
    private Duration notFoundRetryDelay = Duration.ofDays(1);
    private Duration errorRetryDelay = Duration.ofHours(6);
    private String s3Folder = "external-review-checks";

    private final Proxy proxy = new Proxy();

    @Data
    public static class Proxy {
        private boolean enabled = false;
        private String host = "";
        private int port = 8888;
        private String username = "";
        private String password = "";
    }
}
