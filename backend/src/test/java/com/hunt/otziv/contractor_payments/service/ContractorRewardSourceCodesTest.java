package com.hunt.otziv.contractor_payments.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hunt.otziv.contractor_payments.model.ContractorRole;
import org.junit.jupiter.api.Test;

class ContractorRewardSourceCodesTest {

    @Test
    void ledgerSourceCompatibilityIsStrictlyRoleScoped() {
        assertThat(ContractorRewardSourceCodes.isLedgerSourceCompatible(
                ContractorRewardSourceCodes.LEGACY_ORDER_MANAGER,
                ContractorRole.MANAGER
        )).isTrue();
        assertThat(ContractorRewardSourceCodes.isLedgerSourceCompatible(
                ContractorRewardSourceCodes.LEGACY_ORDER_MANAGER,
                ContractorRole.SPECIALIST
        )).isFalse();
        assertThat(ContractorRewardSourceCodes.isLedgerSourceCompatible(
                ContractorRewardSourceCodes.ORDER_COMPLETION_SPECIALIST,
                ContractorRole.SPECIALIST
        )).isTrue();
        assertThat(ContractorRewardSourceCodes.isLedgerSourceCompatible(
                ContractorRewardSourceCodes.ORDER_COMPLETION_SPECIALIST,
                ContractorRole.MANAGER
        )).isFalse();

        assertThat(ContractorRewardSourceCodes.isLedgerSourceCompatible(
                ContractorRewardSourceCodes.LEGACY_PERFORMER_PRODUCT,
                ContractorRole.MANAGER
        )).isTrue();
        assertThat(ContractorRewardSourceCodes.isLedgerSourceCompatible(
                ContractorRewardSourceCodes.LEGACY_PERFORMER_PRODUCT,
                ContractorRole.SPECIALIST
        )).isTrue();
    }

    @Test
    void taskSourcesRequireAValidIdAndMatchingRole() {
        String managerSource = ContractorRewardSourceCodes.badReviewManager(19L);
        String specialistCancellation = ContractorRewardSourceCodes.badReviewCancelSpecialist(20L);

        assertThat(ContractorRewardSourceCodes.isLedgerSourceCompatible(
                managerSource,
                ContractorRole.MANAGER
        )).isTrue();
        assertThat(ContractorRewardSourceCodes.isLedgerSourceCompatible(
                managerSource,
                ContractorRole.SPECIALIST
        )).isFalse();
        assertThat(ContractorRewardSourceCodes.isLedgerSourceCompatible(
                specialistCancellation,
                ContractorRole.SPECIALIST
        )).isTrue();
        assertThat(ContractorRewardSourceCodes.isLedgerSourceCompatible(
                "BAD_REVIEW_DONE_MANAGER:01",
                ContractorRole.MANAGER
        )).isFalse();
        assertThat(ContractorRewardSourceCodes.isLedgerSourceCompatible(
                "BAD_REVIEW_DONE_MANAGER:not-a-number",
                ContractorRole.MANAGER
        )).isFalse();
    }

    @Test
    void unknownOrMissingSourceRolePairsNeverBecomeDebt() {
        assertThat(ContractorRewardSourceCodes.isLedgerSourceCompatible(
                "UNKNOWN_REWARD_SOURCE",
                ContractorRole.SPECIALIST
        )).isFalse();
        assertThat(ContractorRewardSourceCodes.isLedgerSourceCompatible(
                "",
                ContractorRole.MANAGER
        )).isFalse();
        assertThat(ContractorRewardSourceCodes.isLedgerSourceCompatible(
                null,
                ContractorRole.SPECIALIST
        )).isFalse();
        assertThat(ContractorRewardSourceCodes.isLedgerSourceCompatible(
                ContractorRewardSourceCodes.LEGACY_ORDER_MANAGER,
                null
        )).isFalse();
    }
}
