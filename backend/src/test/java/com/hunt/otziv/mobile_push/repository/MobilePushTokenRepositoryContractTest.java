package com.hunt.otziv.mobile_push.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MobilePushTokenRepositoryContractTest {

    @Test
    void deliveryQueryRequiresActiveUserNonRevokedTokenAndMatchingEpoch() throws Exception {
        Query query = MobilePushTokenRepository.class
                .getMethod("findDeliverableByUserId", Long.class)
                .getAnnotation(Query.class);
        String jpql = normalize(query.value());

        assertTrue(jpql.contains("owner.active = true"));
        assertTrue(jpql.contains("token.active = true"));
        assertTrue(jpql.contains("token.revokedAt IS NULL"));
        assertTrue(jpql.contains("token.revokedReason IS NULL"));
        assertTrue(jpql.contains("token.authEpoch = owner.authEpoch"));
    }

    @Test
    void singleTokenRevokeIsObjectBoundAndStateIdempotent() throws Exception {
        Query query = MobilePushTokenRepository.class
                .getMethod(
                        "revokeActiveOwnedToken",
                        Long.class,
                        String.class,
                        java.time.Instant.class,
                        String.class,
                        Long.class
                )
                .getAnnotation(Query.class);
        String jpql = normalize(query.value());

        assertTrue(jpql.contains("token.user.id = :userId"));
        assertTrue(jpql.contains("token.token = :tokenValue"));
        assertTrue(jpql.contains("token.active = true"));
    }

    private String normalize(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }
}
