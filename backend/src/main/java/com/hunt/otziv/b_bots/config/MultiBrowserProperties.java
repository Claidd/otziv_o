package com.hunt.otziv.b_bots.config;

import jakarta.validation.constraints.AssertTrue;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Configuration
@ConfigurationProperties(prefix = "multibrowser")
@Validated
@Data
public class MultiBrowserProperties {
    private boolean enabled;
    private String baseUrl;
    private String apiKey;
    private ConnectionMode connectionMode = ConnectionMode.PROXY;
    private String proxyUrl;
    private int heartbeatIntervalSeconds = 20;
    private int heartbeatTimeoutSeconds = 75;
    private int sessionMaxSeconds = 1800;
    private int openingTimeoutSeconds = 90;
    private int stopRetrySeconds = 30;

    public void requireEnabled() {
        if (!enabled) {
            throw new IllegalStateException("MULTIBROWSER_ENABLED must be true");
        }
    }

    public String requireBaseUrl() {
        requireEnabled();
        return requireConfigured(baseUrl, "MULTIBROWSER_BASE_URL");
    }

    public String requireApiKey() {
        requireEnabled();
        return requireConfigured(apiKey, "MULTIBROWSER_API_KEY");
    }

    public String connectionModeForConnect() {
        requireEnabled();
        if (connectionMode == null) {
            throw new IllegalStateException("MULTIBROWSER_CONNECTION_MODE must be configured");
        }
        return connectionMode.name();
    }

    public String proxyUrlForConnect() {
        requireEnabled();
        if (connectionMode == ConnectionMode.DIRECT) return "";
        return requireConfigured(proxyUrl, "MULTIBROWSER_PROXY_URL");
    }

    @AssertTrue(message = "MULTIBROWSER_BASE_URL, MULTIBROWSER_API_KEY and MULTIBROWSER_CONNECTION_MODE must be configured when MULTIBROWSER_ENABLED=true")
    public boolean isCoreConfigurationValid() {
        return !enabled || (hasText(baseUrl) && hasText(apiKey) && connectionMode != null);
    }

    @AssertTrue(message = "MULTIBROWSER_PROXY_URL must be configured when MULTIBROWSER_CONNECTION_MODE=PROXY")
    public boolean isConnectionConfigurationValid() {
        return !enabled || connectionMode != ConnectionMode.PROXY || hasText(proxyUrl);
    }

    private String requireConfigured(String value, String environmentName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(environmentName + " must be configured");
        }
        return value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public enum ConnectionMode {
        DIRECT,
        PROXY
    }
}
