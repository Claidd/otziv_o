package com.hunt.otziv.workload_shadow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.workload_shadow.repository.WorkloadTransferOfferRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkloadTransferOfferScopeServiceTest {

    @Mock private WorkloadTransferOfferRepository repository;

    @Test
    void cancelsTheWholeExcludedSubsetWithOneRepositoryCall() {
        when(repository.cancelClaimedOffersOutsideScope(
                "lease-token",
                List.of(51L, 52L),
                WorkloadTransferOfferScopeService.OUTSIDE_SCOPE_REASON
        )).thenReturn(6);
        WorkloadTransferOfferScopeService service =
                new WorkloadTransferOfferScopeService(repository);

        int changed = service.cancelClaimedOutsideScope(
                "lease-token",
                List.of(51L, 52L)
        );

        assertThat(changed).isEqualTo(6);
        verify(repository).cancelClaimedOffersOutsideScope(
                "lease-token",
                List.of(51L, 52L),
                WorkloadTransferOfferScopeService.OUTSIDE_SCOPE_REASON
        );
    }

    @Test
    void emptySubsetDoesNotExecuteAnInvalidInQuery() {
        WorkloadTransferOfferScopeService service =
                new WorkloadTransferOfferScopeService(repository);

        assertThat(service.cancelClaimedOutsideScope(
                "lease-token",
                List.of()
        )).isZero();

        verifyNoInteractions(repository);
    }
}
