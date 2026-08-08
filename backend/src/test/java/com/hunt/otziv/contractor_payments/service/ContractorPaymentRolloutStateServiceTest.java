package com.hunt.otziv.contractor_payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.hunt.otziv.contractor_payments.model.ContractorPaymentAccountingAuthority;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentRolloutState;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentRolloutStateRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ContractorPaymentRolloutStateServiceTest {

    @Mock
    private ContractorPaymentRolloutStateRepository repository;

    @InjectMocks
    private ContractorPaymentRolloutStateService service;

    @Test
    void freshSnapshotExposesImmutableCompletionAuthorityAndRevision() {
        LocalDate cutover = LocalDate.of(2026, 8, 1);
        ContractorPaymentRolloutState state = state(
                ContractorPaymentAccountingAuthority.COMPLETION,
                true,
                cutover,
                7L
        );
        when(repository.findById(ContractorPaymentRolloutState.SINGLETON_ID))
                .thenReturn(Optional.of(state));

        ContractorPaymentRolloutStateService.Snapshot snapshot = service.freshSnapshot();

        assertThat(snapshot.completionAccountingActive()).isTrue();
        assertThat(snapshot.legacyAccountingActive()).isFalse();
        assertThat(snapshot.routingRequested()).isTrue();
        assertThat(snapshot.attributionStartDate()).isEqualTo(cutover);
        assertThat(snapshot.revision()).isEqualTo(7L);
    }

    @Test
    void lockedRoutingCheckRequiresCompletionAuthorityDateAndRequest() {
        ContractorPaymentRolloutState completion = state(
                ContractorPaymentAccountingAuthority.COMPLETION,
                true,
                LocalDate.of(2026, 8, 1),
                1L
        );
        when(repository.findByIdForUpdate(ContractorPaymentRolloutState.SINGLETON_ID))
                .thenReturn(Optional.of(completion));

        assertThat(service.lockAndCheckRoutingRequested()).isTrue();

        ReflectionTestUtils.setField(completion, "routingRequested", false);
        assertThat(service.lockAndCheckRoutingRequested()).isFalse();
    }

    @Test
    void missingCanonicalStateFailsInsteadOfAssumingLegacy() {
        when(repository.findById(ContractorPaymentRolloutState.SINGLETON_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(service::freshSnapshot)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rollout state is missing");
    }

    @Test
    void rolloutApiContainsNoAccountingDeactivationTransition() {
        assertThat(Arrays.stream(ContractorPaymentRolloutState.class.getMethods())
                .map(java.lang.reflect.Method::getName)
                .toList())
                .contains("activateCompletionAccounting", "updateRoutingRequested")
                .doesNotContain("deactivateCompletionAccounting", "returnToLegacy", "activateLegacyAccounting");
    }

    private ContractorPaymentRolloutState state(
            ContractorPaymentAccountingAuthority authority,
            boolean routingRequested,
            LocalDate cutover,
            long revision
    ) {
        ContractorPaymentRolloutState state = new ContractorPaymentRolloutState();
        LocalDateTime now = LocalDateTime.of(2026, 8, 7, 10, 0);
        ReflectionTestUtils.setField(state, "id", ContractorPaymentRolloutState.SINGLETON_ID);
        ReflectionTestUtils.setField(state, "accountingAuthority", authority);
        ReflectionTestUtils.setField(state, "routingRequested", routingRequested);
        ReflectionTestUtils.setField(state, "attributionStartDate", cutover);
        ReflectionTestUtils.setField(state, "activatedAt", cutover == null ? null : now.minusDays(1));
        ReflectionTestUtils.setField(state, "activatedBy", cutover == null ? null : "owner");
        ReflectionTestUtils.setField(state, "updatedAt", now);
        ReflectionTestUtils.setField(state, "updatedBy", "owner");
        ReflectionTestUtils.setField(state, "rowVersion", revision);
        return state;
    }
}
