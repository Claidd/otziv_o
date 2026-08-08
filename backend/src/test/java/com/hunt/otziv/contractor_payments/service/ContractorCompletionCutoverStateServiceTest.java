package com.hunt.otziv.contractor_payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.contractor_payments.model.ContractorCompletionCutoverState;
import com.hunt.otziv.contractor_payments.repository.ContractorCompletionCutoverStateRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContractorCompletionCutoverStateServiceTest {

    @Mock private ContractorCompletionCutoverStateRepository repository;
    @Mock private ContractorPaymentBusinessClock businessClock;

    private ContractorCompletionCutoverStateService service;

    @BeforeEach
    void setUp() {
        service = new ContractorCompletionCutoverStateService(repository, businessClock);
    }

    @Test
    void firstValidActivationPersistsSingletonBoundary() {
        LocalDate start = LocalDate.of(2026, 8, 1);
        LocalDateTime now = LocalDateTime.of(2026, 8, 7, 12, 0);
        ContractorCompletionCutoverState persisted = state(start);
        when(businessClock.today()).thenReturn(LocalDate.of(2026, 8, 7));
        when(businessClock.now()).thenReturn(now);
        when(repository.findById(ContractorCompletionCutoverState.SINGLETON_ID))
                .thenReturn(Optional.empty(), Optional.of(persisted));

        assertThat(service.lockOrVerify(start)).contains(start);

        verify(repository).insertSingletonIfAbsent(start, now);
    }

    @Test
    void laterSettingCannotMovePersistedBoundary() {
        LocalDate locked = LocalDate.of(2026, 8, 1);
        LocalDate changed = LocalDate.of(2026, 9, 1);
        when(businessClock.today()).thenReturn(LocalDate.of(2026, 10, 1));
        when(repository.findById(ContractorCompletionCutoverState.SINGLETON_ID))
                .thenReturn(Optional.of(state(locked)));

        assertThat(service.lockOrVerify(changed)).isEmpty();

        verify(repository, never()).insertSingletonIfAbsent(any(), any());
    }

    @Test
    void midMonthBoundaryIsRejectedBeforePersistentStateIsRead() {
        assertThat(service.lockOrVerify(LocalDate.of(2026, 8, 7))).isEmpty();

        verifyNoInteractions(repository);
    }

    @Test
    void futureBoundaryIsRejectedBeforePersistentStateIsRead() {
        when(businessClock.today()).thenReturn(LocalDate.of(2026, 8, 7));

        assertThat(service.lockOrVerify(LocalDate.of(2026, 9, 1))).isEmpty();

        verifyNoInteractions(repository);
    }

    private ContractorCompletionCutoverState state(LocalDate start) {
        ContractorCompletionCutoverState state = new ContractorCompletionCutoverState();
        state.setId(ContractorCompletionCutoverState.SINGLETON_ID);
        state.setAttributionStartDate(start);
        state.setLockedAt(LocalDateTime.of(2026, 8, 7, 12, 0));
        return state;
    }
}
