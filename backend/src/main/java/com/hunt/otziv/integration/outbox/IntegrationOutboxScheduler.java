package com.hunt.otziv.integration.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Completely absent unless the explicit relay kill switch is enabled. */
@Component
@ConditionalOnProperty(
        prefix = "otziv.integration.outbox",
        name = "relay-enabled",
        havingValue = "true",
        matchIfMissing = false
)
class IntegrationOutboxScheduler {

    private final IntegrationOutboxRelay relay;

    IntegrationOutboxScheduler(IntegrationOutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(
            fixedDelayString = "${otziv.integration.outbox.scheduler.fixed-delay-ms:5000}",
            initialDelayString = "${otziv.integration.outbox.scheduler.initial-delay-ms:30000}"
    )
    void tick() {
        relay.runOnce();
    }
}
