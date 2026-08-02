package com.hunt.otziv.payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.payments.repository.PaymentSuccessNotificationRetryClaimRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentSuccessNotificationRetryClaimServiceTest {

    private static final long LINK_ID = 42L;
    private static final String TOKEN = "00000000-0000-0000-0000-000000000042";

    private final PaymentSuccessNotificationRetryClaimRepository repository =
            org.mockito.Mockito.mock(PaymentSuccessNotificationRetryClaimRepository.class);
    private final PaymentSuccessNotificationRetryClaimService service =
            new PaymentSuccessNotificationRetryClaimService(
                    repository,
                    Duration.ofMinutes(2),
                    () -> TOKEN,
                    "node-a"
            );

    @Test
    void returnsOwnedClaimOnlyWhenLinkIsStillEligible() {
        when(repository.lockRetryEligiblePaymentLink(LINK_ID)).thenReturn(true);
        when(repository.tryAcquire(LINK_ID, TOKEN, "node-a", Duration.ofMinutes(2)))
                .thenReturn(true);

        Optional<PaymentSuccessNotificationRetryClaimService.Claim> claim =
                service.tryClaim(LINK_ID);

        assertThat(claim).contains(new PaymentSuccessNotificationRetryClaimService.Claim(
                LINK_ID,
                TOKEN
        ));
    }

    @Test
    void skipsLeaseOwnedByAnotherReplica() {
        when(repository.lockRetryEligiblePaymentLink(LINK_ID)).thenReturn(true);
        when(repository.tryAcquire(LINK_ID, TOKEN, "node-a", Duration.ofMinutes(2)))
                .thenReturn(false);

        assertThat(service.tryClaim(LINK_ID)).isEmpty();

        verify(repository).lockRetryEligiblePaymentLink(LINK_ID);
    }

    @Test
    void skipsCandidateLockedOrChangedByAnotherPaymentTransaction() {
        when(repository.lockRetryEligiblePaymentLink(LINK_ID)).thenReturn(false);

        assertThat(service.tryClaim(LINK_ID)).isEmpty();

        verify(repository, never()).tryAcquire(
                LINK_ID,
                TOKEN,
                "node-a",
                Duration.ofMinutes(2)
        );
    }

    @Test
    void successfulFinalizationIsFencedAndReleasesClaim() {
        var claim = new PaymentSuccessNotificationRetryClaimService.Claim(LINK_ID, TOKEN);
        when(repository.lockPaymentLinkForFinalization(LINK_ID)).thenReturn(true);
        when(repository.lockOwnedClaim(LINK_ID, TOKEN)).thenReturn(true);
        when(repository.markSucceeded(LINK_ID)).thenReturn(true);

        assertThat(service.markSucceeded(claim)).isTrue();

        verify(repository).release(LINK_ID, TOKEN);
    }

    @Test
    void staleOwnerCannotMutatePaymentLink() {
        var claim = new PaymentSuccessNotificationRetryClaimService.Claim(LINK_ID, TOKEN);
        when(repository.lockPaymentLinkForFinalization(LINK_ID)).thenReturn(true);
        when(repository.lockOwnedClaim(LINK_ID, TOKEN)).thenReturn(false);

        assertThat(service.markFailed(claim, "temporary_error")).isFalse();

        verify(repository).lockPaymentLinkForFinalization(LINK_ID);
        verify(repository).lockOwnedClaim(LINK_ID, TOKEN);
        verify(repository, never()).markFailed(LINK_ID, "temporary_error");
        verify(repository, never()).release(LINK_ID, TOKEN);
    }

    @Test
    void expiredOwnerCannotFinalizeWithoutAReclaim() {
        var claim = new PaymentSuccessNotificationRetryClaimService.Claim(LINK_ID, TOKEN);
        when(repository.lockPaymentLinkForFinalization(LINK_ID)).thenReturn(true);
        when(repository.lockOwnedClaim(LINK_ID, TOKEN)).thenReturn(false);

        assertThat(service.markSucceeded(claim)).isFalse();

        verify(repository, never()).markSucceeded(LINK_ID);
        verify(repository, never()).release(LINK_ID, TOKEN);
    }

    @Test
    void rejectsUnboundedLeaseAndInvalidGeneratedToken() {
        assertThatThrownBy(() -> new PaymentSuccessNotificationRetryClaimService(
                repository,
                Duration.ofHours(1),
                UUID.randomUUID()::toString,
                "node-a"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lease");

        PaymentSuccessNotificationRetryClaimService invalidTokenService =
                new PaymentSuccessNotificationRetryClaimService(
                        repository,
                        Duration.ofMinutes(2),
                        () -> "not-a-uuid",
                        "node-a"
                );
        assertThatThrownBy(() -> invalidTokenService.tryClaim(LINK_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UUID");
    }
}
