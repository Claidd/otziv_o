package com.hunt.otziv.contractor_payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.model.ContractorRewardLedgerEntry;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorRewardLedgerRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorRewardSyncMarkerRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.z_zp.model.Zp;
import com.hunt.otziv.z_zp.repository.ZpRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import jakarta.persistence.LockModeType;

@ExtendWith(MockitoExtension.class)
class ContractorRewardLedgerServiceTest {

    @Mock private ZpRepository zpRepository;
    @Mock private ContractorRewardLedgerRepository ledgerRepository;
    @Mock private ContractorRewardSyncMarkerRepository markerRepository;
    @Mock private ContractorPaymentProfileRepository profileRepository;
    @Mock private ContractorRewardAttributionService attributionService;
    @Mock private OrderRepository orderRepository;
    @Mock private UserRepository userRepository;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private ContractorPaymentBusinessClock businessClock;

    private ContractorRewardLedgerService service;

    @BeforeEach
    void setUp() {
        service = new ContractorRewardLedgerService(
                zpRepository,
                ledgerRepository,
                markerRepository,
                profileRepository,
                attributionService,
                orderRepository,
                userRepository,
                transactionManager,
                businessClock
        );
    }

    @Test
    void lateCommittedLowerIdIsProcessedAndSourcePoolIsSplitExactly() {
        User firstUser = user(101L);
        User secondUser = user(102L);
        ContractorPaymentProfile firstProfile = profile(1L, firstUser);
        ContractorPaymentProfile secondProfile = profile(2L, secondUser);
        Order order = new Order();
        order.setId(77L);
        Zp reward = reward(41L, 77L, new BigDecimal("100.01"));

        // The repair query is marker-based, so an id lower than an already
        // processed high watermark remains eligible after an out-of-order commit.
        when(zpRepository.findContractorRewardsNeedingGlobalRepair(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(reward));
        when(zpRepository.findByIdForContractorLedgerUpdate(41L)).thenReturn(Optional.of(reward));
        when(orderRepository.findByIdForOrderDto(77L)).thenReturn(Optional.of(order));
        when(attributionService.attributeRecordedWork(order)).thenReturn(List.of(
                new ContractorRewardAttributionService.SpecialistShare(
                        firstUser, 11L, new BigDecimal("60.00"), 6
                ),
                new ContractorRewardAttributionService.SpecialistShare(
                        secondUser, 12L, new BigDecimal("40.00"), 4
                )
        ));
        when(profileRepository.findByUserIdAndRoleForUpdate(101L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(firstProfile));
        when(profileRepository.findByUserIdAndRoleForUpdate(102L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(secondProfile));
        when(profileRepository.findByUserIdAndRole(101L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(firstProfile));
        when(profileRepository.findByUserIdAndRole(102L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(secondProfile));
        when(ledgerRepository.findAllBySourceZpId(41L)).thenReturn(List.of());
        when(ledgerRepository.findBySourceZpIdAndProfileIdAndAttributionKey(
                any(), any(), org.mockito.ArgumentMatchers.anyLong()
        ))
                .thenReturn(Optional.empty());

        service.synchronize(firstProfile);

        ArgumentCaptor<ContractorRewardLedgerEntry> entries =
                ArgumentCaptor.forClass(ContractorRewardLedgerEntry.class);
        org.mockito.Mockito.verify(ledgerRepository, org.mockito.Mockito.times(2)).save(entries.capture());
        assertThat(entries.getAllValues())
                .extracting(ContractorRewardLedgerEntry::getAmountKopecks)
                .containsExactlyInAnyOrder(6_001L, 4_000L);
        assertThat(entries.getAllValues().stream().mapToLong(ContractorRewardLedgerEntry::getAmountKopecks).sum())
                .isEqualTo(10_001L);
        org.mockito.Mockito.verify(markerRepository).save(any());
        var mutexOrder = org.mockito.Mockito.inOrder(profileRepository, ledgerRepository);
        mutexOrder.verify(profileRepository).findAllByIdForUpdate(Set.of(1L, 2L));
        mutexOrder.verify(ledgerRepository).save(any(ContractorRewardLedgerEntry.class));
    }

    @Test
    void persistedFinalAttributionIsNotResplitAfterRuntimeToggleOff() {
        User user = user(201L);
        ContractorPaymentProfile profile = profile(21L, user);
        Zp reward = reward(51L, 88L, new BigDecimal("55.00"));
        reward.setUserId(201L);
        reward.setProfessionId(31L);
        reward.setAttributionFinal(true);
        when(zpRepository.findContractorRewardsNeedingGlobalRepair(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(reward));
        when(zpRepository.findByIdForContractorLedgerUpdate(51L)).thenReturn(Optional.of(reward));
        when(profileRepository.findByUserIdAndRoleForUpdate(201L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(profile));
        when(profileRepository.findByUserIdAndRole(201L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(profile));
        when(ledgerRepository.findAllBySourceZpId(51L)).thenReturn(List.of());
        when(ledgerRepository.findBySourceZpIdAndProfileIdAndAttributionKey(
                any(), any(), org.mockito.ArgumentMatchers.anyLong()
        )).thenReturn(Optional.empty());

        service.synchronize(profile);

        ArgumentCaptor<ContractorRewardLedgerEntry> entry =
                ArgumentCaptor.forClass(ContractorRewardLedgerEntry.class);
        org.mockito.Mockito.verify(ledgerRepository).save(entry.capture());
        assertThat(entry.getValue().getAmountKopecks()).isEqualTo(5_500L);
        assertThat(entry.getValue().getAttributedWorkerId()).isEqualTo(31L);
        org.mockito.Mockito.verifyNoInteractions(attributionService, orderRepository);
    }

    @Test
    void legacySplitUsesPersistedCoefficientSnapshotEvenAfterUsersChange() {
        User firstUser = user(301L);
        firstUser.setCoefficient(new BigDecimal("0.99"));
        User secondUser = user(302L);
        secondUser.setCoefficient(new BigDecimal("0.01"));
        ContractorPaymentProfile firstProfile = profile(41L, firstUser);
        ContractorPaymentProfile secondProfile = profile(42L, secondUser);
        Zp reward = reward(61L, 99L, new BigDecimal("50.00"));
        reward.setRewardBasis(new BigDecimal("100.01"));
        reward.setAttributionSnapshot("v1|11,301,60,6,0.5;12,302,40,4,0.8");
        when(zpRepository.findContractorRewardsNeedingGlobalRepair(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(reward));
        when(zpRepository.findByIdForContractorLedgerUpdate(61L)).thenReturn(Optional.of(reward));
        when(profileRepository.findByUserIdAndRoleForUpdate(301L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(firstProfile));
        when(profileRepository.findByUserIdAndRoleForUpdate(302L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(secondProfile));
        when(profileRepository.findByUserIdAndRole(301L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(firstProfile));
        when(profileRepository.findByUserIdAndRole(302L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(secondProfile));
        when(ledgerRepository.findAllBySourceZpId(61L)).thenReturn(List.of());
        when(ledgerRepository.findBySourceZpIdAndProfileIdAndAttributionKey(
                any(), any(), org.mockito.ArgumentMatchers.anyLong()
        )).thenReturn(Optional.empty());

        service.synchronize(firstProfile);

        ArgumentCaptor<ContractorRewardLedgerEntry> entries =
                ArgumentCaptor.forClass(ContractorRewardLedgerEntry.class);
        org.mockito.Mockito.verify(ledgerRepository, org.mockito.Mockito.times(2)).save(entries.capture());
        assertThat(entries.getAllValues())
                .extracting(ContractorRewardLedgerEntry::getAmountKopecks)
                .containsExactlyInAnyOrder(3_001L, 3_200L);
        org.mockito.Mockito.verifyNoInteractions(attributionService, orderRepository);
    }

    @Test
    void failedInitialSyncRepairKeepsPersistedPerformerSplitAfterOrderChanges() {
        User firstUser = user(401L);
        User secondUser = user(402L);
        ContractorPaymentProfile firstProfile = profile(51L, firstUser);
        ContractorPaymentProfile secondProfile = profile(52L, secondUser);
        Zp reward = reward(71L, 109L, new BigDecimal("100.01"));
        reward.setSource(com.hunt.otziv.performers.service.PerformerProductRewardZpService.SOURCE);
        reward.setAttributionSnapshot("v1|21,401,60,6,0.5;22,402,40,4,0.8");
        when(zpRepository.findContractorRewardsNeedingGlobalRepair(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(reward));
        when(zpRepository.findByIdForContractorLedgerUpdate(71L)).thenReturn(Optional.of(reward));
        when(profileRepository.findByUserIdAndRoleForUpdate(401L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(firstProfile));
        when(profileRepository.findByUserIdAndRoleForUpdate(402L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(secondProfile));
        when(profileRepository.findByUserIdAndRole(401L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(firstProfile));
        when(profileRepository.findByUserIdAndRole(402L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(secondProfile));
        when(ledgerRepository.findAllBySourceZpId(71L)).thenReturn(List.of());
        when(ledgerRepository.findBySourceZpIdAndProfileIdAndAttributionKey(
                any(), any(), org.mockito.ArgumentMatchers.anyLong()
        )).thenReturn(Optional.empty());

        service.synchronize(firstProfile);

        ArgumentCaptor<ContractorRewardLedgerEntry> entries =
                ArgumentCaptor.forClass(ContractorRewardLedgerEntry.class);
        org.mockito.Mockito.verify(ledgerRepository, org.mockito.Mockito.times(2)).save(entries.capture());
        assertThat(entries.getAllValues())
                .extracting(
                        entry -> entry.getProfile().getId(),
                        ContractorRewardLedgerEntry::getAttributedWorkerId,
                        ContractorRewardLedgerEntry::getAmountKopecks
                )
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(51L, 21L, 6_001L),
                        org.assertj.core.groups.Tuple.tuple(52L, 22L, 4_000L)
                );
        // The repair behaves as if order workers/work were already changed:
        // neither mutable source is consulted at all.
        org.mockito.Mockito.verifyNoInteractions(attributionService, orderRepository);
    }

    @Test
    void unknownFinalSourceCannotCreateDebtThroughDirectShortcut() {
        ContractorPaymentProfile profile = profile(61L, user(501L));
        Zp reward = reward(73L, 110L, new BigDecimal("25.00"));
        reward.setUserId(501L);
        reward.setProfessionId(601L);
        reward.setAttributionFinal(true);
        reward.setSource("UNKNOWN_REWARD_SOURCE");
        when(zpRepository.findContractorRewardsNeedingGlobalRepair(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(reward));
        when(zpRepository.findByIdForContractorLedgerUpdate(73L)).thenReturn(Optional.of(reward));
        assertThatThrownBy(() -> service.synchronize(profile))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("source-role pair is incompatible");

        org.mockito.Mockito.verify(ledgerRepository, org.mockito.Mockito.never())
                .save(any(ContractorRewardLedgerEntry.class));
        org.mockito.Mockito.verify(markerRepository, org.mockito.Mockito.never()).save(any());
        org.mockito.Mockito.verifyNoInteractions(
                profileRepository,
                attributionService,
                orderRepository,
                userRepository
        );
    }

    @Test
    void roleIncompatibleFinalSourceCannotCreateManagerDebt() {
        ContractorPaymentProfile profile = profile(62L, user(502L));
        Zp reward = reward(74L, 111L, new BigDecimal("30.00"));
        reward.setUserId(502L);
        reward.setProfessionId(602L);
        reward.setContractorRole(ContractorRole.MANAGER);
        reward.setAttributionFinal(true);
        reward.setSource(ContractorRewardSourceCodes.LEGACY_ORDER_SPECIALIST);
        when(zpRepository.findContractorRewardsNeedingGlobalRepair(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(reward));
        when(zpRepository.findByIdForContractorLedgerUpdate(74L)).thenReturn(Optional.of(reward));
        assertThatThrownBy(() -> service.synchronize(profile))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("source-role pair is incompatible");

        org.mockito.Mockito.verify(ledgerRepository, org.mockito.Mockito.never())
                .save(any(ContractorRewardLedgerEntry.class));
        org.mockito.Mockito.verify(markerRepository, org.mockito.Mockito.never()).save(any());
        org.mockito.Mockito.verifyNoInteractions(
                profileRepository,
                attributionService,
                orderRepository,
                userRepository
        );
    }

    @Test
    void sourceMutexRepositoryContractIsPessimisticWrite() throws Exception {
        Lock lock = ZpRepository.class
                .getMethod("findByIdForContractorLedgerUpdate", Long.class)
                .getAnnotation(Lock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void cancellationPreflightLocksSourcesBeforeProfilesInSortedUserOrder() {
        Zp highUserReward = reward(91L, 801L, new BigDecimal("20.00"));
        highUserReward.setUserId(202L);
        Zp lowUserReward = reward(92L, 801L, new BigDecimal("30.00"));
        lowUserReward.setUserId(101L);
        Zp secondLowUserReward = reward(93L, 801L, new BigDecimal("40.00"));
        secondLowUserReward.setUserId(101L);
        List<Zp> activeRewards = List.of(highUserReward, lowUserReward, secondLowUserReward);

        ContractorPaymentProfile lowUserProfile = profile(31L, user(101L));
        ContractorPaymentProfile highUserProfile = profile(32L, user(202L));
        when(zpRepository.findActiveByOrderIdForContractorLedgerUpdate(801L))
                .thenReturn(activeRewards);
        when(profileRepository.findAllByUserIdForUpdate(101L))
                .thenReturn(List.of(lowUserProfile));
        when(profileRepository.findAllByUserIdForUpdate(202L))
                .thenReturn(List.of(highUserProfile));

        List<Zp> result = service
                .lockActiveOrderRewardsAndRequireCancellationRepresentable(801L);

        assertThat(result).containsExactlyElementsOf(activeRewards);
        InOrder locks = inOrder(zpRepository, profileRepository);
        locks.verify(zpRepository).findActiveByOrderIdForContractorLedgerUpdate(801L);
        locks.verify(profileRepository).findAllByUserIdForUpdate(101L);
        locks.verify(profileRepository).findAllByUserIdForUpdate(202L);
        org.mockito.Mockito.verify(profileRepository, org.mockito.Mockito.times(1))
                .findAllByUserIdForUpdate(101L);
    }

    @Test
    void cancellationPreflightLocksAllProfilesBeforeRejectingPreCutoverReward() {
        Zp lowUserReward = reward(101L, 802L, new BigDecimal("20.00"));
        lowUserReward.setUserId(101L);
        Zp highUserReward = reward(102L, 802L, new BigDecimal("30.00"));
        highUserReward.setUserId(202L);

        ContractorPaymentProfile lowUserProfile = profile(41L, user(101L));
        ContractorPaymentProfile highUserProfile = profile(42L, user(202L));
        highUserProfile.setTrackingStartZpId(102L);
        when(zpRepository.findActiveByOrderIdForContractorLedgerUpdate(802L))
                .thenReturn(List.of(lowUserReward, highUserReward));
        when(profileRepository.findAllByUserIdForUpdate(101L))
                .thenReturn(List.of(lowUserProfile));
        when(profileRepository.findAllByUserIdForUpdate(202L))
                .thenReturn(List.of(highUserProfile));

        assertThatThrownBy(() -> service
                .lockActiveOrderRewardsAndRequireCancellationRepresentable(802L))
                .hasMessageContaining("переходящий остаток");

        InOrder locks = inOrder(zpRepository, profileRepository);
        locks.verify(zpRepository).findActiveByOrderIdForContractorLedgerUpdate(802L);
        locks.verify(profileRepository).findAllByUserIdForUpdate(101L);
        locks.verify(profileRepository).findAllByUserIdForUpdate(202L);
    }

    @Test
    void safeSynchronizationWaitsForSourceCommitThenUsesIndependentTransaction() {
        Zp reward = reward(77L, 701L, new BigDecimal("10.00"));
        when(transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());
        when(zpRepository.findById(77L)).thenReturn(Optional.empty());
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.synchronizeSourcesSafely(List.of(reward));

            org.mockito.Mockito.verifyNoInteractions(transactionManager);
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);

            TransactionSynchronizationManager.setActualTransactionActive(false);
            synchronizations.getFirst().afterCommit();

            org.mockito.Mockito.verify(transactionManager).getTransaction(any());
            org.mockito.Mockito.verify(transactionManager).commit(any());
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void completionBatchLocksSourcesThenProfilesCanonicallyAndKeepsNegativeAdjustment() {
        User managerUser = user(701L);
        User specialistUser = user(702L);
        ContractorPaymentProfile managerProfile = profile(22L, managerUser);
        managerProfile.setRole(ContractorRole.MANAGER);
        ContractorPaymentProfile specialistProfile = profile(11L, specialistUser);

        Zp manager = reward(82L, 901L, new BigDecimal("25.00"));
        manager.setSource(ContractorRewardSourceCodes.ORDER_COMPLETION_MANAGER);
        manager.setContractorRole(ContractorRole.MANAGER);
        manager.setUserId(701L);
        manager.setProfessionId(801L);
        manager.setAttributionFinal(true);

        Zp correction = reward(71L, 901L, new BigDecimal("-10.00"));
        correction.setSource(ContractorRewardSourceCodes.badReviewCancelSpecialist(991L));
        correction.setUserId(702L);
        correction.setProfessionId(802L);
        correction.setAmount(-1);
        correction.setAttributionFinal(true);

        when(zpRepository.findByIdForContractorLedgerUpdate(71L)).thenReturn(Optional.of(correction));
        when(zpRepository.findByIdForContractorLedgerUpdate(82L)).thenReturn(Optional.of(manager));
        when(profileRepository.findIdByUserIdAndRole(701L, ContractorRole.MANAGER))
                .thenReturn(Optional.of(22L));
        when(profileRepository.findIdByUserIdAndRole(702L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(11L));
        when(profileRepository.findAllByIdForUpdate(Set.of(11L, 22L)))
                .thenReturn(List.of(specialistProfile, managerProfile));
        when(profileRepository.findByUserIdAndRole(701L, ContractorRole.MANAGER))
                .thenReturn(Optional.of(managerProfile));
        when(profileRepository.findByUserIdAndRole(702L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(specialistProfile));
        when(ledgerRepository.findAllBySourceZpId(any())).thenReturn(List.of());
        when(ledgerRepository.findBySourceZpIdAndProfileIdAndAttributionKey(
                any(), any(), org.mockito.ArgumentMatchers.anyLong()
        )).thenReturn(Optional.empty());

        service.synchronizeCompletionSourcesCanonical(List.of(manager, correction));

        InOrder locks = inOrder(zpRepository, profileRepository, ledgerRepository);
        locks.verify(zpRepository).findByIdForContractorLedgerUpdate(71L);
        locks.verify(zpRepository).findByIdForContractorLedgerUpdate(82L);
        locks.verify(profileRepository).findAllByIdForUpdate(Set.of(11L, 22L));
        locks.verify(ledgerRepository).save(any(ContractorRewardLedgerEntry.class));

        ArgumentCaptor<ContractorRewardLedgerEntry> entries =
                ArgumentCaptor.forClass(ContractorRewardLedgerEntry.class);
        org.mockito.Mockito.verify(ledgerRepository, org.mockito.Mockito.times(2)).save(entries.capture());
        assertThat(entries.getAllValues())
                .extracting(ContractorRewardLedgerEntry::getAmountKopecks)
                .containsExactlyInAnyOrder(-1_000L, 2_500L);
    }

    private Zp reward(Long id, Long orderId, BigDecimal sum) {
        Zp reward = new Zp();
        reward.setId(id);
        reward.setOrderId(orderId);
        reward.setSum(sum);
        reward.setAmount(10);
        reward.setActive(true);
        reward.setSource("ORDER_SPECIALIST_REWARD");
        reward.setContractorRole(ContractorRole.SPECIALIST);
        reward.setCreated(LocalDate.of(2026, 8, 7));
        reward.setUpdatedAt(LocalDateTime.of(2026, 8, 7, 10, 0));
        return reward;
    }

    private ContractorPaymentProfile profile(Long id, User user) {
        ContractorPaymentProfile profile = new ContractorPaymentProfile();
        profile.setId(id);
        profile.setUser(user);
        profile.setRole(ContractorRole.SPECIALIST);
        profile.setTrackingStartZpId(0L);
        return profile;
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
