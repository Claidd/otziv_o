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
    /** Optional dual-rollout secret sent to the internal worker on every request. */
    private String workerSharedSecret = "";
    private Duration workerConnectTimeout = Duration.ofSeconds(5);
    private Duration workerReadTimeout = Duration.ofSeconds(30);
    /** Maximum JSON response body accepted before Jackson deserialization. */
    private long workerMaxResponseBytes = 8L * 1024L * 1024L;
    private int confirmationDelayDays = 3;
    private int batchSize = 20;
    private int maxAttempts = 5;
    /**
     * Must comfortably exceed the worker HTTP timeout. An expired lease may be
     * reclaimed by another application node and the old token is then fenced
     * from writing a result.
     */
    private Duration processingLease = Duration.ofMinutes(5);
    private Duration processingLeaseSafetyMargin = Duration.ofSeconds(30);
    private Duration notFoundRetryDelay = Duration.ofDays(1);
    private Duration errorRetryDelay = Duration.ofHours(6);
    private String s3Folder = "external-review-checks";
    private long screenshotMaxBytes = 5L * 1024L * 1024L;
    private Duration screenshotUploadTimeout = Duration.ofSeconds(30);

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
