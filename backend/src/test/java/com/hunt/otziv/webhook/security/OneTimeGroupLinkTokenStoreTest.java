package com.hunt.otziv.webhook.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OneTimeGroupLinkTokenStoreTest {

    private static final String SECRET = "dedicated-group-link-secret-at-least-32-bytes";

    @Test
    void tokenIsOpaqueStableUntilUseAndAtomicallyConsumedOnce() {
        OneTimeGroupLinkTokenStore store = new OneTimeGroupLinkTokenStore(Duration.ofMinutes(5));
        String first = store.issue("telegram-company", 42L, SECRET);
        String second = store.issue("telegram-company", 42L, SECRET);

        assertThat(first).isEqualTo(second).doesNotContain("42");
        assertThat(store.consume(first, "telegram-company", SECRET)).contains(42L);
        assertThat(store.consume(first, "telegram-company", SECRET)).isEmpty();
        assertThat(store.consume(first + "x", "telegram-company", SECRET)).isEmpty();
    }
}
