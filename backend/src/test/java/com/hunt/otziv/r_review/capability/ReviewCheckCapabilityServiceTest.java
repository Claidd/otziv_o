package com.hunt.otziv.r_review.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.r_review.capability.ReviewCheckCapabilityRepository.CapabilityRow;
import com.hunt.otziv.r_review.capability.ReviewCheckCapabilityService.LegacyAction;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ReviewCheckCapabilityServiceTest {

    private ReviewCheckCapabilityRepository repository;
    private ReviewCheckCapabilityService service;

    @BeforeEach
    void setUp() {
        repository = mock(ReviewCheckCapabilityRepository.class);
        SecureRandom random = mock(SecureRandom.class);
        doAnswer(invocation -> {
            byte[] bytes = invocation.getArgument(0);
            for (int index = 0; index < bytes.length; index++) {
                bytes[index] = (byte) (index + 1);
            }
            return null;
        }).when(random).nextBytes(any(byte[].class));
        service = new ReviewCheckCapabilityService(repository, new SimpleMeterRegistry(), random);
    }

    @Test
    void issuesCryptographicallySizedOpaqueTokenAndPersistsOnlyItsHash() {
        UUID orderDetailId = UUID.randomUUID();
        when(repository.insertOpaque(eq(orderDetailId), any(byte[].class), eq(3L), eq(77L), eq(14)))
                .thenReturn(row(
                        orderDetailId,
                        new byte[32],
                        3L,
                        null,
                        LocalDateTime.now().plusDays(14),
                        901L
                ));

        ReviewCheckCapabilityService.IssuedCapability issued = service.issue(
                orderDetailId,
                Set.of("VIEW", "EDIT"),
                14,
                77L
        );

        assertThat(issued.id()).isEqualTo(901L);
        assertThat(issued.token()).matches("^rc1_[A-Za-z0-9_-]{43}$");
        assertThat(issued.scopes()).containsExactlyInAnyOrder("VIEW", "EDIT");

        ArgumentCaptor<byte[]> hash = ArgumentCaptor.forClass(byte[].class);
        verify(repository).insertOpaque(eq(orderDetailId), hash.capture(), eq(3L), eq(77L), eq(14));
        assertThat(hash.getValue()).hasSize(32)
                .isEqualTo(ReviewCheckCapabilityService.sha256(issued.token()));
    }

    @Test
    void resolvesActiveTokenWithConstantTimeHashComparisonAndRequiredScope() {
        String token = "rc1_" + "A".repeat(43);
        byte[] hash = ReviewCheckCapabilityService.sha256(token);
        UUID orderDetailId = UUID.randomUUID();
        when(repository.findByTokenHashForUpdate(any(byte[].class))).thenReturn(Optional.of(row(
                orderDetailId,
                hash,
                ReviewCheckCapabilityScope.ALL_PUBLIC_MASK,
                null,
                LocalDateTime.now().plusDays(1)
        )));
        when(repository.isActiveByDatabaseClock(12L)).thenReturn(true);

        ReviewCheckCapabilityService.ResolvedCapability resolved = service.resolveAndTouch(
                token,
                ReviewCheckCapabilityScope.APPROVE,
                "approve"
        );

        assertThat(resolved.orderDetailId()).isEqualTo(orderDetailId);
        assertThat(resolved.has(ReviewCheckCapabilityScope.EDIT)).isTrue();
        verify(repository).isActiveByDatabaseClock(12L);
        verify(repository).touchIfActiveAndDue(12L);
    }

    @Test
    void invalidRevokedExpiredAndMissingScopeAllReturnTheSame404Contract() {
        String token = "rc1_" + "B".repeat(43);
        byte[] hash = ReviewCheckCapabilityService.sha256(token);
        UUID orderDetailId = UUID.randomUUID();

        assertNotFound(() -> {
            when(repository.findByTokenHashForUpdate(any(byte[].class))).thenReturn(Optional.empty());
            service.resolveAndTouch(token, ReviewCheckCapabilityScope.VIEW, "view");
        });
        assertNotFound(() -> {
            when(repository.findByTokenHashForUpdate(any(byte[].class))).thenReturn(Optional.empty());
            service.resolveAndTouch("malformed", ReviewCheckCapabilityScope.VIEW, "view");
        });
        assertNotFound(() -> {
            when(repository.findByTokenHashForUpdate(any(byte[].class))).thenReturn(Optional.of(row(
                    orderDetailId, hash, 1L, LocalDateTime.now(), LocalDateTime.now().plusDays(1)
            )));
            service.resolveAndTouch(token, ReviewCheckCapabilityScope.VIEW, "view");
        });
        assertNotFound(() -> {
            when(repository.findByTokenHashForUpdate(any(byte[].class))).thenReturn(Optional.of(row(
                    orderDetailId, hash, 1L, null, LocalDateTime.now().minusSeconds(1)
            )));
            service.resolveAndTouch(token, ReviewCheckCapabilityScope.VIEW, "view");
        });
        assertNotFound(() -> {
            when(repository.findByTokenHashForUpdate(any(byte[].class))).thenReturn(Optional.of(row(
                    orderDetailId, hash, ReviewCheckCapabilityScope.VIEW.bit(), null, LocalDateTime.now().plusDays(1)
            )));
            service.resolveAndTouch(token, ReviewCheckCapabilityScope.APPROVE, "approve");
        });
    }

    @Test
    void legacyTelemetryHashesCanonicalUuidAfterSuccessfulRouteHandling() {
        UUID orderDetailId = UUID.fromString("A1B2C3D4-1111-2222-3333-444455556666");

        service.recordLegacyUse(orderDetailId, LegacyAction.VIEW);

        verify(repository).recordLegacyUse(
                orderDetailId,
                ReviewCheckCapabilityService.sha256(orderDetailId.toString())
        );
    }

    private CapabilityRow row(
            UUID orderDetailId,
            byte[] hash,
            long scopeMask,
            LocalDateTime revokedAt,
            LocalDateTime expiresAt
    ) {
        return row(orderDetailId, hash, scopeMask, revokedAt, expiresAt, 12L);
    }

    private CapabilityRow row(
            UUID orderDetailId,
            byte[] hash,
            long scopeMask,
            LocalDateTime revokedAt,
            LocalDateTime expiresAt,
            long id
    ) {
        return new CapabilityRow(
                id,
                orderDetailId,
                hash,
                "OPAQUE",
                scopeMask,
                77L,
                expiresAt,
                revokedAt,
                null,
                null,
                null,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private void assertNotFound(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }
}
