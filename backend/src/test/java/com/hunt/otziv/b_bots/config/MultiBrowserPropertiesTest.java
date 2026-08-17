package com.hunt.otziv.b_bots.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MultiBrowserPropertiesTest {

    @Test
    void directModeIgnoresAndClearsAStaleProxyValue() {
        MultiBrowserProperties properties = new MultiBrowserProperties();
        properties.setEnabled(true);
        properties.setConnectionMode(MultiBrowserProperties.ConnectionMode.DIRECT);
        properties.setProxyUrl("socks5://stale-proxy.invalid:1080");

        assertThat(properties.isConnectionConfigurationValid()).isTrue();
        assertThat(properties.connectionModeForConnect()).isEqualTo("DIRECT");
        assertThat(properties.proxyUrlForConnect()).isEmpty();
    }

    @Test
    void proxyModeRejectsAnEmptyProxyValue() {
        MultiBrowserProperties properties = new MultiBrowserProperties();
        properties.setEnabled(true);
        properties.setConnectionMode(MultiBrowserProperties.ConnectionMode.PROXY);
        properties.setProxyUrl("  ");

        assertThat(properties.isConnectionConfigurationValid()).isFalse();
        assertThatThrownBy(properties::proxyUrlForConnect)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MULTIBROWSER_PROXY_URL");
    }

    @Test
    void proxyModeReturnsANormalizedProxyValue() {
        MultiBrowserProperties properties = new MultiBrowserProperties();
        properties.setEnabled(true);
        properties.setConnectionMode(MultiBrowserProperties.ConnectionMode.PROXY);
        properties.setProxyUrl("  socks5://proxy.internal:1080  ");

        assertThat(properties.isConnectionConfigurationValid()).isTrue();
        assertThat(properties.connectionModeForConnect()).isEqualTo("PROXY");
        assertThat(properties.proxyUrlForConnect()).isEqualTo("socks5://proxy.internal:1080");
    }

    @Test
    void disabledFeatureAllowsMissingExternalConfigurationButRejectsUse() {
        MultiBrowserProperties properties = new MultiBrowserProperties();

        assertThat(properties.isCoreConfigurationValid()).isTrue();
        assertThat(properties.isConnectionConfigurationValid()).isTrue();
        assertThatThrownBy(properties::requireBaseUrl)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MULTIBROWSER_ENABLED");
    }
}
