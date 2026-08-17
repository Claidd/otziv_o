package com.hunt.otziv.contractor_payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.z_zp.model.Zp;
import com.hunt.otziv.z_zp.repository.ZpRepository;
import java.util.List;
import java.util.Optional;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ContractorLegacyRewardGuardTest {

    @Mock private ZpRepository zpRepository;
    @Mock private BadReviewTaskRepository badReviewTaskRepository;
    @InjectMocks private ContractorLegacyRewardGuard guard;

    @Test
    void completionFailsClosedForNamedLegacyAggregate() {
        Zp legacy = reward(ContractorRewardSourceCodes.LEGACY_ORDER_SPECIALIST);
        when(zpRepository.findByOrderIdAndActiveTrue(91L)).thenReturn(List.of(legacy));

        assertThatThrownBy(() -> guard.requireNoActiveLegacyAggregate(91L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("ручная сверка");
    }

    @Test
    void completionFailsClosedForNullAndUnknownSources() {
        Zp unclassified = reward(null);
        Zp unknown = reward("HISTORICAL_CUSTOM_REWARD");
        when(zpRepository.findByOrderIdAndActiveTrue(91L)).thenReturn(List.of(unclassified, unknown));

        assertThatThrownBy(() -> guard.requireNoActiveLegacyAggregate(91L))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void cancellationAllowsRecognizedEarnedLegacyButRejectsUnclassified() {
        when(zpRepository.findByOrderIdAndActiveTrue(91L)).thenReturn(List.of(
                reward(ContractorRewardSourceCodes.LEGACY_ORDER_MANAGER),
                reward(ContractorRewardSourceCodes.LEGACY_ORDER_SPECIALIST)
        ));
        guard.requireCancellationClassifiable(91L);

        when(zpRepository.findByOrderIdAndActiveTrue(92L)).thenReturn(List.of(reward(null)));
        assertThatThrownBy(() -> guard.requireCancellationClassifiable(92L))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void completionRowsAreNeverMistakenForLegacy() {
        Zp completion = reward(ContractorRewardSourceCodes.ORDER_COMPLETION_SPECIALIST);
        completion.setContractorRole(ContractorRole.SPECIALIST);
        when(zpRepository.findByOrderIdAndActiveTrue(91L)).thenReturn(List.of(completion));

        guard.requireNoActiveLegacyAggregate(91L);
        guard.requireCancellationClassifiable(91L);
    }

    @Test
    void malformedOrCrossOrderCompletionLookalikesFailClosed() {
        when(zpRepository.findByOrderIdAndActiveTrue(91L)).thenReturn(List.of(
                reward("BAD_REVIEW_DONE_MANAGER:"),
                reward("BAD_REVIEW_DONE_SPECIALIST:+7"),
                reward("BAD_REVIEW_CANCEL_MANAGER:9223372036854775808")
        ));

        assertThatThrownBy(() -> guard.requireNoActiveLegacyAggregate(91L))
                .isInstanceOf(ResponseStatusException.class);

        when(zpRepository.findByOrderIdAndActiveTrue(92L)).thenReturn(List.of(
                reward("BAD_REVIEW_DONE_MANAGER:705")
        ));
        when(badReviewTaskRepository.findOrderIdById(705L)).thenReturn(Optional.of(93L));

        assertThatThrownBy(() -> guard.requireNoActiveLegacyAggregate(92L))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void exactPositiveTaskSourceIsRecognizedOnlyForItsOwnOrder() {
        Zp completion = reward("BAD_REVIEW_DONE_MANAGER:705");
        when(zpRepository.findByOrderIdAndActiveTrue(91L)).thenReturn(List.of(completion));
        when(badReviewTaskRepository.findOrderIdById(705L)).thenReturn(Optional.of(91L));

        guard.requireNoActiveLegacyAggregate(91L);
        assertThat(ContractorRewardSourceCodes.completionTaskId(completion.getSource()))
                .hasValue(705L);
    }

    @Test
    void preCutoffTaskBridgeAllowsOnlyClassifiedLegacyRowsStrictlyBeforeBoundary() {
        LocalDate boundary = LocalDate.of(2026, 8, 1);
        Zp oldManager = reward(ContractorRewardSourceCodes.LEGACY_ORDER_MANAGER);
        oldManager.setCreated(LocalDate.of(2026, 7, 31));
        Zp oldSpecialist = reward(ContractorRewardSourceCodes.LEGACY_ORDER_SPECIALIST);
        oldSpecialist.setCreated(LocalDate.of(2026, 7, 30));
        when(zpRepository.findByOrderIdAndActiveTrue(91L)).thenReturn(List.of(oldManager, oldSpecialist));

        guard.requireOnlyDatedPreCutoffLegacyAggregate(91L, boundary);

        Zp boundaryRow = reward(ContractorRewardSourceCodes.LEGACY_ORDER_MANAGER);
        boundaryRow.setCreated(boundary);
        when(zpRepository.findByOrderIdAndActiveTrue(92L)).thenReturn(List.of(boundaryRow));
        assertThatThrownBy(() -> guard.requireOnlyDatedPreCutoffLegacyAggregate(92L, boundary))
                .isInstanceOf(ResponseStatusException.class);

        Zp undated = reward(ContractorRewardSourceCodes.LEGACY_ORDER_SPECIALIST);
        when(zpRepository.findByOrderIdAndActiveTrue(93L)).thenReturn(List.of(undated));
        assertThatThrownBy(() -> guard.requireOnlyDatedPreCutoffLegacyAggregate(93L, boundary))
                .isInstanceOf(ResponseStatusException.class);
    }

    private Zp reward(String source) {
        Zp reward = new Zp();
        reward.setId(1L);
        reward.setOrderId(91L);
        reward.setActive(true);
        reward.setSource(source);
        return reward;
    }
}
