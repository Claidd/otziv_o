package com.hunt.otziv.contractor_payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAmountLimits;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentProfileRequest;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentProfileResponse;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileAdjustmentRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorRewardLedgerRepository;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.u_users.repository.UserRepository;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.z_zp.repository.ZpRepository;
import jakarta.validation.Validation;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ContractorPaymentProfileServiceTest {

    @Mock
    private ContractorPaymentProfileRepository profileRepository;
    @Mock
    private ContractorPaymentAllocationRepository allocationRepository;
    @Mock
    private ContractorRewardLedgerRepository rewardLedgerRepository;
    @Mock
    private ContractorRewardLedgerService ledgerService;
    @Mock
    private ContractorPaymentAccountingService accountingService;
    @Mock
    private ContractorPaymentProfileAdjustmentRepository adjustmentRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ZpRepository zpRepository;
    @Mock
    private AppSettingService appSettingService;
    @Mock
    private ContractorPaymentRuntimeSwitch runtimeSwitch;
    @Mock
    private ContractorPaymentAccountingPhaseService accountingPhaseService;
    @Mock
    private BusinessAuditService businessAuditService;
    @Mock
    private ContractorPaymentTargetAccessPolicy targetAccessPolicy;
    @Mock
    private ContractorRewardInitialMonthSyncCoordinator initialMonthSyncCoordinator;

    @InjectMocks
    private ContractorPaymentProfileService service;

    private ContractorPaymentProfile profile;

    @BeforeEach
    void setUp() {
        profile = new ContractorPaymentProfile();
        profile.setId(7L);
        lenient().when(ledgerService.totalAccrued(profile)).thenReturn(300_000L);
        lenient().when(userRepository.lockContractorActiveFlag(anyLong())).thenReturn(Optional.of(true));
        lenient().when(userRepository.lockContractorRoleIds(anyLong(), anyString())).thenReturn(List.of(1));
        lenient().when(accountingPhaseService.current()).thenReturn(ContractorAllocationMode.SHADOW);
    }

    @Test
    void rejectsOpeningBalanceAboveSafeBusinessLimit() {
        User user = userWithRole(42L, "ROLE_WORKER");
        profile.setUser(user);
        profile.setRole(ContractorRole.SPECIALIST);
        when(userRepository.findByIdWithAssignments(42L)).thenReturn(Optional.of(user));
        when(profileRepository.findByUserIdAndRoleForUpdate(42L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(profile));
        ContractorPaymentProfileRequest request = new ContractorPaymentProfileRequest(
                ContractorRole.SPECIALIST,
                0L,
                false,
                false,
                null,
                null,
                null,
                null,
                100_000_000_001L,
                "Корректировка"
        );

        assertThatThrownBy(() -> service.update(42L, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("допустимый предел");
        verify(profileRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectedPrivilegedProfileReadDoesNotLookupUserOrTakeFinancialLocks() {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Пользователь не найден"))
                .when(targetAccessPolicy)
                .requireCanManageUser(42L);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.getForUser(42L)
        );

        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(targetAccessPolicy).requireCanManageUser(42L);
        verifyNoInteractions(userRepository, profileRepository);
    }

    @Test
    void shadowAvailableSubtractsSimulatedPaymentsAndActiveReservations() {
        stubAllocationTotals(ContractorAllocationMode.SHADOW, 100_000L, 50_000L);

        assertThat(service.available(profile, ContractorAllocationMode.SHADOW)).isEqualTo(150_000L);
    }

    @Test
    void liveAvailableUsesOnlyConfirmedLivePaymentsAndLiveReservations() {
        stubAllocationTotals(ContractorAllocationMode.LIVE, 80_000L, 20_000L);

        assertThat(service.available(profile, ContractorAllocationMode.LIVE)).isEqualTo(200_000L);
    }

    @Test
    void capacityUsesNetConfirmationAndOnlyOutstandingPartOfPartialReservation() {
        when(rewardLedgerRepository.sumActiveForCapacityUpdate(7L)).thenReturn(300_000L);
        when(allocationRepository.capacityTotalsForUpdate(7L, ContractorAllocationMode.LIVE.name()))
                .thenReturn(capacityTotals(40_000L, 10_000L, 70_000L));

        assertThat(service.available(profile, ContractorAllocationMode.LIVE)).isEqualTo(200_000L);
        verify(rewardLedgerRepository).sumActiveForCapacityUpdate(7L);
        verify(allocationRepository).capacityTotalsForUpdate(7L, ContractorAllocationMode.LIVE.name());
    }

    @Test
    void enabledProfileRequiresExplicitBank() {
        User user = new User();
        user.setId(12L);
        Role workerRole = new Role();
        workerRole.setName("ROLE_WORKER");
        user.setRoles(List.of(workerRole));
        profile.setUser(user);
        profile.setRole(ContractorRole.SPECIALIST);
        when(userRepository.findByIdWithAssignments(12L)).thenReturn(Optional.of(user));
        when(profileRepository.findByUserIdAndRoleForUpdate(12L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(profile));

        ContractorPaymentProfileRequest request = new ContractorPaymentProfileRequest(
                ContractorRole.SPECIALIST,
                0L,
                true,
                false,
                "Получатель",
                "+7 999 123-45-67",
                " ",
                null,
                0L,
                null
        );

        assertThatThrownBy(() -> service.update(12L, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("банк");
    }

    @Test
    void enabledProfileAcceptsCardAndStoresCanonicalTransferNumber() {
        User user = userWithRole(13L, "ROLE_WORKER");
        profile.setUser(user);
        profile.setRole(ContractorRole.SPECIALIST);
        when(userRepository.findByIdWithAssignments(13L)).thenReturn(Optional.of(user));
        when(profileRepository.findByUserIdAndRoleForUpdate(13L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(profile));
        when(profileRepository.saveAndFlush(any(ContractorPaymentProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(runtimeSwitch.status()).thenReturn(new ContractorPaymentRuntimeSwitch.RuntimeStatus(
                false, false, false, false, false, false
        ));
        ContractorPaymentProfileRequest request = new ContractorPaymentProfileRequest(
                ContractorRole.SPECIALIST,
                0L,
                true,
                false,
                "Получатель",
                "2202 2082-3839 6676",
                "Банк",
                null,
                0L,
                null
        );

        ContractorPaymentProfileResponse response = service.update(13L, request);

        assertThat(profile.getPaymentPhone()).isEqualTo("2202208238396676");
        assertThat(response.paymentPhone()).isEqualTo("2202208238396676");
    }

    @Test
    void enabledProfileRejectsValueThatIsNeitherPhoneNorCard() {
        User user = userWithRole(14L, "ROLE_WORKER");
        profile.setUser(user);
        profile.setRole(ContractorRole.SPECIALIST);
        when(userRepository.findByIdWithAssignments(14L)).thenReturn(Optional.of(user));
        when(profileRepository.findByUserIdAndRoleForUpdate(14L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(profile));
        ContractorPaymentProfileRequest request = new ContractorPaymentProfileRequest(
                ContractorRole.SPECIALIST,
                0L,
                true,
                false,
                "Получатель",
                "2202 2082 3839 667X",
                "Банк",
                null,
                0L,
                null
        );

        assertThatThrownBy(() -> service.update(14L, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("телефоном (10–15 цифр) или картой (16–19 цифр)");
        verify(profileRepository, never()).saveAndFlush(any());
    }

    @Test
    void liveCanaryCannotBeEnabledForDisabledProfile() {
        User user = userWithRole(30L, "ROLE_WORKER");
        profile.setUser(user);
        profile.setRole(ContractorRole.SPECIALIST);
        when(userRepository.findByIdWithAssignments(30L)).thenReturn(Optional.of(user));
        when(profileRepository.findByUserIdAndRoleForUpdate(30L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(profile));
        ContractorPaymentProfileRequest request = new ContractorPaymentProfileRequest(
                ContractorRole.SPECIALIST,
                0L,
                false,
                true,
                "Получатель",
                "+79990000000",
                "Банк",
                "Комментарий",
                0L,
                ""
        );

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.update(30L, request)
        );

        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(error.getReason()).contains("активного платёжного профиля");
    }

    @Test
    void firstNonZeroOpeningBalanceAlsoRequiresTraceableReason() {
        User user = userWithRole(33L, "ROLE_WORKER");
        profile.setUser(user);
        profile.setRole(ContractorRole.SPECIALIST);
        profile.setOpeningBalanceKopecks(0L);
        when(userRepository.findByIdWithAssignments(33L)).thenReturn(Optional.of(user));
        when(profileRepository.findByUserIdAndRoleForUpdate(33L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(profile));
        when(profileRepository.saveAndFlush(any(ContractorPaymentProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        ContractorPaymentProfileRequest request = new ContractorPaymentProfileRequest(
                ContractorRole.SPECIALIST,
                0L,
                false,
                false,
                null,
                null,
                null,
                null,
                10_000L,
                " "
        );

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.update(33L, request)
        );

        assertThat(error.getReason()).contains("источник и причину");
    }

    @Test
    void updatePersistsSeparateLiveCanaryFlag() {
        User user = userWithRole(31L, "ROLE_WORKER");
        profile.setUser(user);
        profile.setRole(ContractorRole.SPECIALIST);
        when(userRepository.findByIdWithAssignments(31L)).thenReturn(Optional.of(user));
        when(profileRepository.findByUserIdAndRoleForUpdate(31L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(profile));
        when(profileRepository.saveAndFlush(any(ContractorPaymentProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(runtimeSwitch.status()).thenReturn(new ContractorPaymentRuntimeSwitch.RuntimeStatus(
                false, false, false, false, false, false
        ));
        when(allocationRepository.sumOutstandingExposure(any(), any(), any()))
                .thenReturn(1_000L, 2_000L, 3_000L);
        when(accountingService.closedWithoutPaymentInPeriod(any(), any(), any(), any()))
                .thenReturn(4_000L);
        when(accountingService.closedWithoutPayment(profile, ContractorAllocationMode.SHADOW))
                .thenReturn(5_000L);
        ContractorPaymentProfileRequest request = new ContractorPaymentProfileRequest(
                ContractorRole.SPECIALIST,
                0L,
                true,
                true,
                "Получатель",
                "+79990000000",
                "Банк",
                "Комментарий",
                0L,
                ""
        );

        ContractorPaymentProfileResponse response = service.update(31L, request);

        assertThat(profile.isEnabled()).isTrue();
        assertThat(profile.isLiveEnabled()).isTrue();
        assertThat(response.liveEnabled()).isTrue();
        assertThat(response.reservedKopecks()).isEqualTo(1_000L);
        assertThat(response.clientReportedKopecks()).isEqualTo(2_000L);
        assertThat(response.partiallyConfirmedOutstandingKopecks()).isEqualTo(3_000L);
        assertThat(response.closedWithoutPaymentMonthKopecks()).isEqualTo(4_000L);
        assertThat(response.closedWithoutPaymentTotalKopecks()).isEqualTo(5_000L);
        verify(businessAuditService).recordRequiredInCurrentTransaction(
                eq("UPDATE_CONTRACTOR_PAYMENT_PROFILE"),
                eq("CONTRACTOR_PAYMENT_PROFILE"),
                eq(7L),
                eq(null),
                eq(null),
                any(),
                any(),
                any()
        );
        verify(initialMonthSyncCoordinator).request(7L);
        InOrder lockOrder = inOrder(userRepository, profileRepository);
        lockOrder.verify(userRepository).lockContractorActiveFlag(31L);
        lockOrder.verify(userRepository).lockContractorRoleIds(31L, "ROLE_WORKER");
        lockOrder.verify(userRepository).lockContractorRoleIds(31L, "ROLE_MANAGER");
        lockOrder.verify(profileRepository).findByUserIdAndRoleForUpdate(31L, ContractorRole.SPECIALIST);
    }

    @Test
    void liveRoutingCanBeDisabledAfterUserDeactivationAndRoleDriftWithIncompleteDetails() {
        User staleUser = userWithRole(39L, "ROLE_WORKER");
        staleUser.setActive(false);
        profile.setUser(staleUser);
        profile.setRole(ContractorRole.SPECIALIST);
        profile.setEnabled(true);
        profile.setLiveEnabled(true);
        when(userRepository.findByIdWithAssignments(39L)).thenReturn(Optional.of(staleUser));
        when(userRepository.lockContractorActiveFlag(39L)).thenReturn(Optional.of(false));
        when(userRepository.lockContractorRoleIds(39L, "ROLE_WORKER")).thenReturn(List.of());
        when(profileRepository.findByUserIdAndRoleForUpdate(39L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(profile));
        when(profileRepository.saveAndFlush(profile)).thenReturn(profile);
        when(runtimeSwitch.status()).thenReturn(new ContractorPaymentRuntimeSwitch.RuntimeStatus(
                false, false, false, false, false, false
        ));
        ContractorPaymentProfileRequest request = new ContractorPaymentProfileRequest(
                ContractorRole.SPECIALIST,
                0L,
                true,
                false,
                null,
                null,
                null,
                null,
                0L,
                null
        );

        ContractorPaymentProfileResponse response = service.update(39L, request);

        assertThat(response.enabled()).isTrue();
        assertThat(response.liveEnabled()).isFalse();
        assertThat(profile.isLiveEnabled()).isFalse();
        verify(businessAuditService).recordRequiredInCurrentTransaction(
                eq("UPDATE_CONTRACTOR_PAYMENT_PROFILE"),
                eq("CONTRACTOR_PAYMENT_PROFILE"),
                eq(7L),
                eq(null),
                eq(null),
                any(),
                any(),
                any()
        );
    }

    @Test
    void fullProfileDisableRemainsAvailableAfterUserDeactivationAndRoleDrift() {
        String historicalRecipient = "П".repeat(161);
        String historicalPhone = "7".repeat(33);
        String historicalBank = "Б".repeat(121);
        String historicalComment = "К".repeat(256);
        User staleUser = userWithRole(40L, "ROLE_MANAGER");
        staleUser.setActive(false);
        profile.setUser(staleUser);
        profile.setRole(ContractorRole.MANAGER);
        profile.setEnabled(true);
        profile.setLiveEnabled(true);
        profile.setRecipientName(historicalRecipient);
        profile.setPaymentPhone(historicalPhone);
        profile.setBankName(historicalBank);
        profile.setPaymentComment(historicalComment);
        when(userRepository.findByIdWithAssignments(40L)).thenReturn(Optional.of(staleUser));
        when(userRepository.lockContractorActiveFlag(40L)).thenReturn(Optional.of(false));
        when(userRepository.lockContractorRoleIds(40L, "ROLE_MANAGER")).thenReturn(List.of());
        when(profileRepository.findByUserIdAndRoleForUpdate(40L, ContractorRole.MANAGER))
                .thenReturn(Optional.of(profile));
        when(profileRepository.saveAndFlush(profile)).thenReturn(profile);
        when(runtimeSwitch.status()).thenReturn(new ContractorPaymentRuntimeSwitch.RuntimeStatus(
                false, false, false, false, false, false
        ));
        ContractorPaymentProfileRequest request = new ContractorPaymentProfileRequest(
                ContractorRole.MANAGER,
                0L,
                false,
                false,
                historicalRecipient,
                historicalPhone,
                historicalBank,
                historicalComment,
                0L,
                null
        );

        ContractorPaymentProfileResponse response = service.update(40L, request);

        assertThat(response.enabled()).isFalse();
        assertThat(response.liveEnabled()).isFalse();
        assertThat(profile.isEnabled()).isFalse();
        assertThat(profile.isLiveEnabled()).isFalse();
    }

    @Test
    void emergencyLiveOffAllowsUnchangedHistoricalBalanceAboveCurrentLimit() {
        long historicalBalance = ContractorPaymentAmountLimits.MAX_AMOUNT_KOPECKS + 1L;
        User user = userWithRole(41L, "ROLE_WORKER");
        profile.setUser(user);
        profile.setRole(ContractorRole.SPECIALIST);
        profile.setEnabled(true);
        profile.setLiveEnabled(true);
        profile.setOpeningBalanceKopecks(historicalBalance);
        when(userRepository.findByIdWithAssignments(41L)).thenReturn(Optional.of(user));
        when(profileRepository.findByUserIdAndRoleForUpdate(41L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(profile));
        when(profileRepository.saveAndFlush(profile)).thenReturn(profile);
        when(runtimeSwitch.status()).thenReturn(new ContractorPaymentRuntimeSwitch.RuntimeStatus(
                false, false, false, false, false, false
        ));
        ContractorPaymentProfileRequest request = new ContractorPaymentProfileRequest(
                ContractorRole.SPECIALIST,
                0L,
                true,
                false,
                null,
                null,
                null,
                null,
                historicalBalance,
                null
        );

        ContractorPaymentProfileResponse response = service.update(41L, request);

        assertThat(response.liveEnabled()).isFalse();
        assertThat(response.openingBalanceKopecks()).isEqualTo(historicalBalance);
    }

    @Test
    void emergencyLiveOffAllowsUnchangedHistoricalOverLimitRequisites() {
        String historicalRecipient = "И".repeat(161);
        String historicalPhone = "8".repeat(33);
        String historicalBank = "Б".repeat(121);
        String historicalComment = "К".repeat(256);
        User user = userWithRole(43L, "ROLE_WORKER");
        profile.setUser(user);
        profile.setRole(ContractorRole.SPECIALIST);
        profile.setEnabled(true);
        profile.setLiveEnabled(true);
        profile.setRecipientName("  " + historicalRecipient + "  ");
        profile.setPaymentPhone("  " + historicalPhone + "  ");
        profile.setBankName("  " + historicalBank + "  ");
        profile.setPaymentComment("  " + historicalComment + "  ");
        when(userRepository.findByIdWithAssignments(43L)).thenReturn(Optional.of(user));
        when(profileRepository.findByUserIdAndRoleForUpdate(43L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(profile));
        when(profileRepository.saveAndFlush(profile)).thenReturn(profile);
        when(runtimeSwitch.status()).thenReturn(new ContractorPaymentRuntimeSwitch.RuntimeStatus(
                false, false, false, false, false, false
        ));
        ContractorPaymentProfileRequest request = new ContractorPaymentProfileRequest(
                ContractorRole.SPECIALIST,
                0L,
                true,
                false,
                "  " + historicalRecipient + "  ",
                "  " + historicalPhone + "  ",
                "  " + historicalBank + "  ",
                "  " + historicalComment + "  ",
                0L,
                null
        );

        ContractorPaymentProfileResponse response = service.update(43L, request);

        assertThat(response.enabled()).isTrue();
        assertThat(response.liveEnabled()).isFalse();
        assertThat(response.recipientName()).isEqualTo(historicalRecipient);
        assertThat(response.paymentPhone()).isEqualTo(historicalPhone);
        assertThat(response.bankName()).isEqualTo(historicalBank);
        assertThat(response.paymentComment()).isEqualTo(historicalComment);
    }

    @Test
    void emergencyLiveOffRejectsEveryChangedOrNewOverLimitRequisite() {
        User user = userWithRole(44L, "ROLE_WORKER");
        profile.setUser(user);
        profile.setRole(ContractorRole.SPECIALIST);
        profile.setEnabled(true);
        profile.setLiveEnabled(true);
        profile.setRecipientName("Получатель");
        profile.setPaymentPhone("+79990000000");
        profile.setBankName("Банк");
        profile.setPaymentComment("Комментарий");
        when(userRepository.findByIdWithAssignments(44L)).thenReturn(Optional.of(user));
        when(profileRepository.findByUserIdAndRoleForUpdate(44L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(profile));

        assertThatThrownBy(() -> service.update(44L, new ContractorPaymentProfileRequest(
                ContractorRole.SPECIALIST, 0L, true, false,
                "И".repeat(161), "+79990000000", "Банк", "Комментарий", 0L, null
        ))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("160");
        assertThatThrownBy(() -> service.update(44L, new ContractorPaymentProfileRequest(
                ContractorRole.SPECIALIST, 0L, true, false,
                "Получатель", "7".repeat(33), "Банк", "Комментарий", 0L, null
        ))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("32");
        assertThatThrownBy(() -> service.update(44L, new ContractorPaymentProfileRequest(
                ContractorRole.SPECIALIST, 0L, true, false,
                "Получатель", "+79990000000", "Б".repeat(121), "Комментарий", 0L, null
        ))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("120");
        assertThatThrownBy(() -> service.update(44L, new ContractorPaymentProfileRequest(
                ContractorRole.SPECIALIST, 0L, true, false,
                "Получатель", "+79990000000", "Банк", "К".repeat(256), 0L, null
        ))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("255");
        verify(profileRepository, never()).saveAndFlush(any());
    }

    @Test
    void beanValidationDefersStateAwareProfileLimitsToServiceValidation() {
        ContractorPaymentProfileRequest request = new ContractorPaymentProfileRequest(
                ContractorRole.SPECIALIST,
                0L,
                true,
                false,
                "И".repeat(161),
                "7".repeat(33),
                "Б".repeat(121),
                "К".repeat(256),
                ContractorPaymentAmountLimits.MAX_AMOUNT_KOPECKS + 1L,
                null
        );

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(request)).isEmpty();
        }
    }

    @Test
    void staleActiveUserCannotReenableProfileAfterCurrentDeactivation() {
        User staleUser = userWithRole(36L, "ROLE_WORKER");
        profile.setUser(staleUser);
        profile.setRole(ContractorRole.SPECIALIST);
        when(userRepository.findByIdWithAssignments(36L)).thenReturn(Optional.of(staleUser));
        when(userRepository.lockContractorActiveFlag(36L)).thenReturn(Optional.of(false));
        ContractorPaymentProfileRequest request = new ContractorPaymentProfileRequest(
                ContractorRole.SPECIALIST,
                0L,
                true,
                false,
                "Получатель",
                "+79990000000",
                "Банк",
                null,
                0L,
                null
        );

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.update(36L, request)
        );

        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(error.getReason()).contains("неактивного пользователя");
        verify(profileRepository).findByUserIdAndRoleForUpdate(36L, ContractorRole.SPECIALIST);
    }

    @Test
    void staleWorkerRoleCannotReenableProfileAfterCurrentRoleRemoval() {
        User staleUser = userWithRole(37L, "ROLE_WORKER");
        profile.setUser(staleUser);
        profile.setRole(ContractorRole.SPECIALIST);
        when(userRepository.findByIdWithAssignments(37L)).thenReturn(Optional.of(staleUser));
        when(userRepository.lockContractorRoleIds(37L, "ROLE_WORKER")).thenReturn(List.of());
        when(profileRepository.findByUserIdAndRoleForUpdate(37L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(profile));
        ContractorPaymentProfileRequest request = new ContractorPaymentProfileRequest(
                ContractorRole.SPECIALIST,
                0L,
                true,
                false,
                "Получатель",
                "+79990000000",
                "Банк",
                null,
                0L,
                null
        );

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.update(37L, request)
        );

        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(error.getReason()).contains("без текущей роли");
        verify(profileRepository).findByUserIdAndRoleForUpdate(37L, ContractorRole.SPECIALIST);
    }

    @Test
    void removedRoleCanStillCorrectHistoricalDetailsAndOpeningBalance() {
        User historicalUser = new User();
        historicalUser.setId(38L);
        profile.setUser(historicalUser);
        profile.setRole(ContractorRole.SPECIALIST);
        profile.setEnabled(false);
        profile.setLiveEnabled(false);
        profile.setOpeningBalanceKopecks(10_000L);
        when(userRepository.findByIdWithAssignments(38L)).thenReturn(Optional.of(historicalUser));
        when(userRepository.lockContractorRoleIds(38L, "ROLE_WORKER")).thenReturn(List.of());
        when(profileRepository.findByUserIdAndRoleForUpdate(38L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(profile));
        when(profileRepository.saveAndFlush(profile)).thenReturn(profile);
        when(runtimeSwitch.status()).thenReturn(new ContractorPaymentRuntimeSwitch.RuntimeStatus(
                false, false, false, false, false, false
        ));
        ContractorPaymentProfileRequest request = new ContractorPaymentProfileRequest(
                ContractorRole.SPECIALIST,
                0L,
                false,
                false,
                "Исправленный получатель",
                "+79990000000",
                "Банк",
                "Исторический профиль",
                12_500L,
                "Исправление входящего остатка по документам"
        );

        ContractorPaymentProfileResponse response = service.update(38L, request);

        assertThat(response.openingBalanceKopecks()).isEqualTo(12_500L);
        assertThat(profile.getRecipientName()).isEqualTo("Исправленный получатель");
        assertThat(profile.isEnabled()).isFalse();
        verify(adjustmentRepository).save(any());
    }

    @Test
    void staleProfileVersionIsRejectedBeforeAnyMutation() {
        User user = userWithRole(34L, "ROLE_WORKER");
        profile.setUser(user);
        profile.setRole(ContractorRole.SPECIALIST);
        profile.setRowVersion(4L);
        profile.setRecipientName("Старое имя");
        when(userRepository.findByIdWithAssignments(34L)).thenReturn(Optional.of(user));
        when(profileRepository.findByUserIdAndRoleForUpdate(34L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(profile));
        ContractorPaymentProfileRequest request = new ContractorPaymentProfileRequest(
                ContractorRole.SPECIALIST,
                3L,
                false,
                false,
                "Новое имя",
                null,
                null,
                null,
                0L,
                null
        );

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.update(34L, request)
        );

        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(profile.getRecipientName()).isEqualTo("Старое имя");
        verify(profileRepository, never()).saveAndFlush(any());
    }

    @Test
    void removedWorkerRoleDisablesProfileAndLiveCanaryWithoutDeletingHistory() {
        User user = userWithRole(32L, "ROLE_WORKER");
        profile.setUser(user);
        profile.setRole(ContractorRole.SPECIALIST);
        profile.setEnabled(true);
        profile.setLiveEnabled(true);
        when(userRepository.findByIdWithAssignments(32L)).thenReturn(Optional.of(user));
        when(userRepository.lockContractorRoleIds(32L, "ROLE_WORKER")).thenReturn(List.of());
        when(profileRepository.findAllByUserIdForUpdate(32L)).thenReturn(List.of(profile));

        service.ensureForUser(32L);

        assertThat(profile.isEnabled()).isFalse();
        assertThat(profile.isLiveEnabled()).isFalse();
        verify(profileRepository).save(profile);
        verify(businessAuditService).recordSafely(
                eq("AUTO_DISABLE_CONTRACTOR_PAYMENT_PROFILE_ROLE_REMOVED"),
                eq("CONTRACTOR_PAYMENT_PROFILE"),
                eq(7L),
                eq(null),
                eq(null),
                any(),
                any(),
                any()
        );
    }

    @Test
    void deactivatedUserDisablesProfileAndLiveCanaryWithoutDeletingHistory() {
        User user = userWithRole(35L, "ROLE_WORKER");
        user.setActive(false);
        profile.setUser(user);
        profile.setRole(ContractorRole.SPECIALIST);
        profile.setEnabled(true);
        profile.setLiveEnabled(true);
        when(userRepository.findByIdWithAssignments(35L)).thenReturn(Optional.of(user));
        when(userRepository.lockContractorActiveFlag(35L)).thenReturn(Optional.of(false));
        when(profileRepository.findAllByUserIdForUpdate(35L)).thenReturn(List.of(profile));

        service.ensureForUser(35L);

        assertThat(profile.isEnabled()).isFalse();
        assertThat(profile.isLiveEnabled()).isFalse();
        verify(profileRepository).save(profile);
        verify(businessAuditService).recordSafely(
                eq("AUTO_DISABLE_CONTRACTOR_PAYMENT_PROFILE_USER_DEACTIVATED"),
                eq("CONTRACTOR_PAYMENT_PROFILE"),
                eq(7L),
                eq(null),
                eq(null),
                any(),
                any(),
                any()
        );
    }

    private void stubAllocationTotals(ContractorAllocationMode mode, long paid, long reserved) {
        when(rewardLedgerRepository.sumActiveForCapacityUpdate(7L)).thenReturn(300_000L);
        when(allocationRepository.capacityTotalsForUpdate(7L, mode.name()))
                .thenReturn(capacityTotals(paid, 0L, reserved));
    }

    private ContractorPaymentAllocationRepository.CapacityTotals capacityTotals(
            long confirmed,
            long returned,
            long outstanding
    ) {
        return new ContractorPaymentAllocationRepository.CapacityTotals() {
            @Override
            public Long getConfirmedKopecks() {
                return confirmed;
            }

            @Override
            public Long getReturnedKopecks() {
                return returned;
            }

            @Override
            public Long getOutstandingKopecks() {
                return outstanding;
            }
        };
    }

    private User userWithRole(Long id, String roleName) {
        Role role = new Role();
        role.setName(roleName);
        User user = new User();
        user.setId(id);
        user.setActive(true);
        user.setRoles(List.of(role));
        return user;
    }
}
