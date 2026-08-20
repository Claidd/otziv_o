package com.hunt.otziv.contractor_payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.z_zp.model.Zp;
import com.hunt.otziv.z_zp.repository.ZpRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class ContractorRewardInitialMonthSyncServiceTest {

    private static final LocalDate AUGUST_START = LocalDate.of(2026, 8, 1);

    @Mock private ContractorPaymentProfileRepository profileRepository;
    @Mock private ZpRepository zpRepository;
    @Mock private ContractorRewardLedgerService ledgerService;
    @Mock private BusinessAuditService businessAuditService;

    private ContractorRewardInitialMonthSyncService service;

    @BeforeEach
    void setUp() {
        ContractorPaymentBusinessClock businessClock = new ContractorPaymentBusinessClock(
                Clock.fixed(Instant.parse("2026-08-10T05:00:00Z"), ZoneId.of("UTC")),
                ZoneId.of("Asia/Irkutsk")
        );
        service = new ContractorRewardInitialMonthSyncService(
                profileRepository,
                zpRepository,
                ledgerService,
                businessClock,
                businessAuditService
        );
    }

    @Test
    void specialistActivationClassifiesNullRowAndForceImportsAlreadyTypedRow() {
        ContractorPaymentProfile profile = profile(ContractorRole.SPECIALIST);
        Zp first = reward(81L, "5000.00", true);
        Zp second = reward(95L, "3280.00", true);
        Zp afterBoundary = reward(101L, "777.00", true);
        second.setSource(ContractorRewardSourceCodes.LEGACY_PERFORMER_PRODUCT);
        second.setContractorRole(ContractorRole.SPECIALIST);
        second.setAttributionFinal(true);
        when(profileRepository.findById(7L)).thenReturn(Optional.of(profile));
        when(profileRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(profile));
        when(zpRepository.findLegacySpecialistRewardIdsInPeriod(5L, AUGUST_START, AUGUST_START.plusMonths(1)))
                .thenReturn(List.of(95L, 101L, 81L, 95L));
        when(zpRepository.findByIdForContractorLedgerUpdate(81L)).thenReturn(Optional.of(first));
        when(zpRepository.findByIdForContractorLedgerUpdate(95L)).thenReturn(Optional.of(second));
        when(zpRepository.countEligibleLegacySpecialistRewardForSync(81L)).thenReturn(1L);
        when(zpRepository.countEligibleLegacySpecialistRewardForSync(95L)).thenReturn(1L);

        assertThat(service.synchronizeProfile(7L)).isTrue();

        assertThat(first.getContractorRole()).isEqualTo(ContractorRole.SPECIALIST);
        assertThat(second.getContractorRole()).isEqualTo(ContractorRole.SPECIALIST);
        assertThat(first.getSource()).isEqualTo(ContractorRewardSourceCodes.LEGACY_ORDER_SPECIALIST);
        assertThat(second.getSource()).isEqualTo(ContractorRewardSourceCodes.LEGACY_PERFORMER_PRODUCT);
        assertThat(first.isAttributionFinal()).isTrue();
        assertThat(second.isAttributionFinal()).isTrue();
        assertThat(afterBoundary.getContractorRole()).isNull();
        assertThat(afterBoundary.getSource()).isNull();
        assertThat(afterBoundary.isAttributionFinal()).isFalse();
        assertThat(second.isActive()).isTrue();
        assertThat(profile.getTrackingStartZpId()).isEqualTo(80L);
        assertThat(profile.getTrackingStartedAt()).isEqualTo(AUGUST_START.atStartOfDay());
        assertThat(profile.getOpeningBalanceKopecks()).isZero();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<Zp>> imported = ArgumentCaptor.forClass(Iterable.class);
        verify(ledgerService).forceSynchronizeDirectSourcesForLockedProfile(
                imported.capture(), org.mockito.ArgumentMatchers.same(profile)
        );
        assertThat(imported.getValue()).containsExactly(first, second);
        verify(zpRepository, never()).findByIdForContractorLedgerUpdate(101L);
        verify(zpRepository).save(first);
        verify(zpRepository, never()).save(second);
        verify(zpRepository, never()).save(afterBoundary);
        verify(businessAuditService).recordRequiredInCurrentTransaction(
                any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void managerActivationUsesManagerOwnershipAndDirectSource() {
        ContractorPaymentProfile profile = profile(ContractorRole.MANAGER);
        Zp reward = reward(72L, "1200.00", true);
        when(profileRepository.findById(7L)).thenReturn(Optional.of(profile));
        when(profileRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(profile));
        when(zpRepository.findLegacyManagerRewardIdsInPeriod(5L, AUGUST_START, AUGUST_START.plusMonths(1)))
                .thenReturn(List.of(72L));
        when(zpRepository.findByIdForContractorLedgerUpdate(72L)).thenReturn(Optional.of(reward));
        when(zpRepository.countEligibleLegacyManagerRewardForSync(72L)).thenReturn(1L);

        assertThat(service.synchronizeProfile(7L)).isTrue();

        assertThat(reward.getContractorRole()).isEqualTo(ContractorRole.MANAGER);
        assertThat(reward.getSource()).isEqualTo(ContractorRewardSourceCodes.LEGACY_ORDER_MANAGER);
        assertThat(profile.getTrackingStartZpId()).isEqualTo(71L);
        verify(zpRepository, never()).findLegacySpecialistRewardIdsInPeriod(any(), any(), any());
        verify(ledgerService).forceSynchronizeDirectSourcesForLockedProfile(
                any(), org.mockito.ArgumentMatchers.same(profile)
        );
    }

    @Test
    void repeatedRunIsIdempotentAfterMonthCoverageWasCompleted() {
        ContractorPaymentProfile profile = profile(ContractorRole.SPECIALIST);
        profile.setTrackingStartedAt(AUGUST_START.atStartOfDay());
        when(profileRepository.findById(7L)).thenReturn(Optional.of(profile));

        assertThat(service.synchronizeProfile(7L)).isFalse();

        verifyNoInteractions(zpRepository, ledgerService, businessAuditService);
        verify(profileRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void disabledProfileDoesNotImportAnything() {
        ContractorPaymentProfile profile = profile(ContractorRole.SPECIALIST);
        profile.setEnabled(false);
        when(profileRepository.findById(7L)).thenReturn(Optional.of(profile));

        assertThat(service.synchronizeProfile(7L)).isFalse();

        verifyNoInteractions(zpRepository, ledgerService, businessAuditService);
    }

    @Test
    void sourceThatBecameInactiveAfterDiscoveryIsNotConverted() {
        ContractorPaymentProfile profile = profile(ContractorRole.SPECIALIST);
        Zp reward = reward(81L, "5000.00", false);
        when(profileRepository.findById(7L)).thenReturn(Optional.of(profile));
        when(profileRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(profile));
        when(zpRepository.findLegacySpecialistRewardIdsInPeriod(5L, AUGUST_START, AUGUST_START.plusMonths(1)))
                .thenReturn(List.of(81L));
        when(zpRepository.findByIdForContractorLedgerUpdate(81L)).thenReturn(Optional.of(reward));

        assertThatThrownBy(() -> service.synchronizeProfile(7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sourceId=81");

        assertThat(reward.getContractorRole()).isNull();
        assertThat(reward.getSource()).isNull();
        verify(zpRepository, never()).save(any());
        verifyNoInteractions(ledgerService, businessAuditService);
    }

    @Test
    void unknownSourceSelectedBeforeLockIsNotConverted() {
        ContractorPaymentProfile profile = profile(ContractorRole.MANAGER);
        Zp reward = reward(72L, "1200.00", true);
        reward.setSource("UNKNOWN_REWARD");
        when(profileRepository.findById(7L)).thenReturn(Optional.of(profile));
        when(profileRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(profile));
        when(zpRepository.findLegacyManagerRewardIdsInPeriod(5L, AUGUST_START, AUGUST_START.plusMonths(1)))
                .thenReturn(List.of(72L));
        when(zpRepository.findByIdForContractorLedgerUpdate(72L)).thenReturn(Optional.of(reward));

        assertThatThrownBy(() -> service.synchronizeProfile(7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sourceId=72");

        assertThat(reward.getContractorRole()).isNull();
        assertThat(reward.getSource()).isEqualTo("UNKNOWN_REWARD");
        verify(zpRepository, never()).save(any());
        verifyNoInteractions(ledgerService, businessAuditService);
    }

    @Test
    void sourceWhoseOwnerChangedAfterDiscoveryIsNotSilentlySkippedOrConverted() {
        ContractorPaymentProfile profile = profile(ContractorRole.SPECIALIST);
        Zp reward = reward(82L, "900.00", true);
        reward.setUserId(999L);
        when(profileRepository.findById(7L)).thenReturn(Optional.of(profile));
        when(profileRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(profile));
        when(zpRepository.findLegacySpecialistRewardIdsInPeriod(5L, AUGUST_START, AUGUST_START.plusMonths(1)))
                .thenReturn(List.of(82L));
        when(zpRepository.findByIdForContractorLedgerUpdate(82L)).thenReturn(Optional.of(reward));

        assertThatThrownBy(() -> service.synchronizeProfile(7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sourceId=82");

        assertThat(reward.getContractorRole()).isNull();
        assertThat(reward.getSource()).isNull();
        verify(zpRepository, never()).save(any());
        verifyNoInteractions(ledgerService, businessAuditService);
    }

    @Test
    void profileRoleChangedAfterDiscoveryFailsClosed() {
        ContractorPaymentProfile discovered = profile(ContractorRole.SPECIALIST);
        ContractorPaymentProfile locked = profile(ContractorRole.MANAGER);
        when(profileRepository.findById(7L)).thenReturn(Optional.of(discovered));
        when(profileRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(locked));
        when(zpRepository.findLegacySpecialistRewardIdsInPeriod(5L, AUGUST_START, AUGUST_START.plusMonths(1)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.synchronizeProfile(7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("profileId=7");

        verify(zpRepository, never()).save(any());
        verifyNoInteractions(ledgerService, businessAuditService);
    }

    @Test
    void afterCommitImportAlwaysUsesAnIndependentTransaction() throws NoSuchMethodException {
        Transactional transactional = ContractorRewardInitialMonthSyncService.class
                .getMethod("synchronizeProfile", Long.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    private ContractorPaymentProfile profile(ContractorRole role) {
        User user = new User();
        user.setId(5L);
        ContractorPaymentProfile profile = new ContractorPaymentProfile();
        profile.setId(7L);
        profile.setUser(user);
        profile.setRole(role);
        profile.setEnabled(true);
        profile.setOpeningBalanceKopecks(0L);
        profile.setTrackingStartedAt(LocalDateTime.of(2026, 8, 8, 12, 0));
        profile.setTrackingStartZpId(100L);
        profile.setLedgerSyncZpId(100L);
        return profile;
    }

    private Zp reward(Long id, String rubles, boolean active) {
        Zp reward = new Zp();
        reward.setId(id);
        reward.setUserId(5L);
        reward.setProfessionId(3L);
        reward.setOrderId(900L + id);
        reward.setCreated(LocalDate.of(2026, 8, 4));
        reward.setSum(new BigDecimal(rubles));
        reward.setAmount(2);
        reward.setActive(active);
        return reward;
    }
}
