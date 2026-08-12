package com.hunt.otziv.b_bots.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Configuration
@ConfigurationProperties(prefix = "multibrowser")
@Validated
@Data
public class MultiBrowserProperties {
    @NotBlank
    private String baseUrl;
    @NotBlank
    private String apiKey;
    @NotNull
    private ConnectionMode connectionMode = ConnectionMode.PROXY;
    private String proxyUrl;
    private int heartbeatIntervalSeconds = 20;
    private int heartbeatTimeoutSeconds = 75;
    private int sessionMaxSeconds = 1800;
    private int openingTimeoutSeconds = 90;
    private int stopRetrySeconds = 30;

    public String requireApiKey() {
        return requireConfigured(apiKey, "MULTIBROWSER_API_KEY");
    }

    public String connectionModeForConnect() {
        if (connectionMode == null) {
            throw new IllegalStateException("MULTIBROWSER_CONNECTION_MODE must be configured");
        }
        return connectionMode.name();
    }

    public String proxyUrlForConnect() {
        if (connectionMode == ConnectionMode.DIRECT) return "";
        return requireConfigured(proxyUrl, "MULTIBROWSER_PROXY_URL");
    }

    @AssertTrue(message = "MULTIBROWSER_PROXY_URL must be configured when MULTIBROWSER_CONNECTION_MODE=PROXY")
    public boolean isConnectionConfigurationValid() {
        return connectionMode != ConnectionMode.PROXY || (proxyUrl != null && !proxyUrl.isBlank());
    }

    private String requireConfigured(String value, String environmentName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(environmentName + " must be configured");
        }
        return value.trim();
    }

    public enum ConnectionMode {
        DIRECT,
        PROXY
    }
}
