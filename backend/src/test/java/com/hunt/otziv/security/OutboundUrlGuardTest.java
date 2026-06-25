package com.hunt.otziv.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutboundUrlGuardTest {

    private final OutboundUrlGuard guard = new OutboundUrlGuard();

    @Test
    void rejectsLocalAndPrivateHttpTargets() {
        assertThat(guard.isAllowed("http://localhost:8080")).isFalse();
        assertThat(guard.isAllowed("http://127.0.0.1:8080")).isFalse();
        assertThat(guard.isAllowed("http://10.0.0.10")).isFalse();
        assertThat(guard.isAllowed("http://100.64.0.1")).isFalse();
        assertThat(guard.isAllowed("http://192.168.1.10")).isFalse();
        assertThat(guard.isAllowed("http://198.18.0.1")).isFalse();
        assertThat(guard.isAllowed("http://169.254.169.254/latest/meta-data")).isFalse();
        assertThat(guard.isAllowed("http://[::1]/")).isFalse();
        assertThat(guard.isAllowed("http://[fd00::1]/")).isFalse();
    }

    @Test
    void rejectsNonHttpAndUserInfoUrls() {
        assertThat(guard.isAllowed("file:///etc/passwd")).isFalse();
        assertThat(guard.isAllowed("https://user:password@93.184.216.34")).isFalse();
    }

    @Test
    void allowsPublicHttpTargets() {
        assertThat(guard.isAllowed("https://93.184.216.34")).isTrue();
    }
}
