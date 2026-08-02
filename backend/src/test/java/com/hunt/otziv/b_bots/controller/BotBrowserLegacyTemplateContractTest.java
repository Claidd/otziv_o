package com.hunt.otziv.b_bots.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class BotBrowserLegacyTemplateContractTest {

    @Test
    void legacyPageUsesTheLeaseContractAndDefensiveUrlPolicy() throws Exception {
        String template = new ClassPathResource(
                "templates/bots/bot_browser.html"
        ).getContentAsString(StandardCharsets.UTF_8);

        assertThat(template).contains(
                "JSON.stringify({ heartbeatSupported: true })",
                "/browser/sessions/${encodeURIComponent(id)}/${action}",
                "startHeartbeat(data.heartbeatIntervalSeconds)",
                "new URL(rawUrl)",
                "encodedControl.test(rawUrl)",
                "navigator.sendBeacon(url)"
        );
        assertThat(template).doesNotContain(
                "new URL(data.vncUrl, window.location.origin)",
                "${bot.password}"
        );
    }
}
