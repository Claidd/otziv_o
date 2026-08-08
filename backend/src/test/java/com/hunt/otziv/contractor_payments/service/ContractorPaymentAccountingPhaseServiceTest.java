package com.hunt.otziv.contractor_payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAccountingPhase;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAccountingPhaseRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ContractorPaymentAccountingPhaseServiceTest {

    @Mock
    private ContractorPaymentAccountingPhaseRepository repository;

    @InjectMocks
    private ContractorPaymentAccountingPhaseService service;

    @Test
    void firstLiveRoutePromotesTheLockedSingleton() {
        ContractorPaymentAccountingPhase state = state(ContractorAllocationMode.SHADOW);
        when(repository.findByIdForUpdate(ContractorPaymentAccountingPhase.SINGLETON_ID))
                .thenReturn(Optional.of(state));
        when(repository.saveAndFlush(state)).thenReturn(state);

        assertThat(service.lockAndPromoteForLiveRoute()).isEqualTo(ContractorAllocationMode.LIVE);
        assertThat(state.getPhase()).isEqualTo(ContractorAllocationMode.LIVE);
        verify(repository).saveAndFlush(state);
    }

    @Test
    void livePhaseIsIrreversibleAndDoesNotRewriteAuditRow() {
        ContractorPaymentAccountingPhase state = state(ContractorAllocationMode.LIVE);
        when(repository.findByIdForUpdate(ContractorPaymentAccountingPhase.SINGLETON_ID))
                .thenReturn(Optional.of(state));

        assertThat(service.lockAndPromoteForLiveRoute()).isEqualTo(ContractorAllocationMode.LIVE);
        assertThat(service.lockCurrent()).isEqualTo(ContractorAllocationMode.LIVE);
        verify(repository, never()).saveAndFlush(state);
    }

    @Test
    void reportingReadsDurablePhaseInsteadOfInferringFromAllocations() {
        ContractorPaymentAccountingPhase state = state(ContractorAllocationMode.SHADOW);
        when(repository.findById(ContractorPaymentAccountingPhase.SINGLETON_ID))
                .thenReturn(Optional.of(state));

        assertThat(service.current()).isEqualTo(ContractorAllocationMode.SHADOW);
    }

    private ContractorPaymentAccountingPhase state(ContractorAllocationMode mode) {
        ContractorPaymentAccountingPhase state = new ContractorPaymentAccountingPhase();
        ReflectionTestUtils.setField(state, "id", ContractorPaymentAccountingPhase.SINGLETON_ID);
        ReflectionTestUtils.setField(state, "phase", mode);
        ReflectionTestUtils.setField(state, "updatedAt", LocalDateTime.now().minusDays(1));
        ReflectionTestUtils.setField(state, "updatedBy", "MIGRATION");
        return state;
    }
}
