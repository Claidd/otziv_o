package com.hunt.otziv.contractor_payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.contractor_payments.repository.ContractorCompletionRewardMarkerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ContractorRouteAssignmentGuardTest {

    @Mock private ContractorPaymentShadowService paymentShadowService;
    @Mock private ContractorCompletionRewardMarkerRepository markerRepository;

    private ContractorRouteAssignmentGuard guard;

    @BeforeEach
    void setUp() {
        guard = new ContractorRouteAssignmentGuard(paymentShadowService, markerRepository);
    }

    @Test
    void completionMarkerBlocksPayableMutationBeforeRouteLookup() {
        when(markerRepository.existsByOrderId(41L)).thenReturn(true);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> guard.requirePayableMutationAllowed(41L)
        );

        assertThat(error.getStatusCode().value()).isEqualTo(409);
        verify(paymentShadowService, never()).hasFrozenLiveRoute(41L);
    }

    @Test
    void frozenClientRouteBlocksPayableMutationWithoutCompletionMarker() {
        when(markerRepository.existsByOrderId(42L)).thenReturn(false);
        when(paymentShadowService.hasFrozenLiveRoute(42L)).thenReturn(true);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> guard.requirePayableMutationAllowed(42L)
        );

        assertThat(error.getStatusCode().value()).isEqualTo(409);
        verify(paymentShadowService).hasFrozenLiveRoute(42L);
    }
}
