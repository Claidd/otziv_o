package com.hunt.otziv.contractor_payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationSourceType;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationStatus;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocationEvent;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.contractor_payments.model.ContractorRoutingDecisionReason;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationEventRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.ManualPaymentSource;
import com.hunt.otziv.payments.model.ManualPaymentType;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.service.ManualPaymentTaskContractorCapacityService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ContractorPaymentLiveRoutingServiceTest {

    @Mock
    private ContractorPaymentRuntimeSwitch runtimeSwitch;
    @Mock
    private ContractorPaymentAllocationRepository allocationRepository;
    @Mock
    private ContractorPaymentProfileRepository profileRepository;
    @Mock
    private ContractorPaymentProfileService profileService;
    @Mock
    private ContractorPaymentRoutingLimitService routingLimitService;
    @Mock
    private ContractorPaymentAllocationEventRepository eventRepository;
    @Mock
    private CommonInvoiceRepository commonInvoiceRepository;
    @Mock
    private ContractorPaymentRolloutStateService rolloutStateService;
    @Mock
    private ContractorPaymentAccountingPhaseService accountingPhaseService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ManualPaymentTaskContractorCapacityService taskCapacityService;

    private ContractorPaymentLiveRoutingService service;
    private final Map<Long, ContractorPaymentProfile> discoveredProfiles = new HashMap<>();

    @BeforeEach
    void setUp() {
        ContractorPaymentAccountingService accountingService =
                new ContractorPaymentAccountingService(eventRepository);
        service = new ContractorPaymentLiveRoutingService(
                runtimeSwitch,
                allocationRepository,
                profileRepository,
                profileService,
                routingLimitService,
                accountingService,
                commonInvoiceRepository,
                rolloutStateService,
                accountingPhaseService,
                userRepository,
                new ContractorOrderManagerResolver(),
                taskCapacityService
        );
        lenient().when(taskCapacityService.ordinaryAvailable(any(), any()))
                .thenAnswer(invocation -> profileService.available(
                        invocation.getArgument(0), invocation.getArgument(1)));
        lenient().when(rolloutStateService.lockAndCheckRoutingRequested()).thenReturn(true);
        lenient().when(userRepository.lockContractorActiveFlag(anyLong())).thenReturn(Optional.of(true));
        lenient().when(userRepository.lockContractorRoleIds(anyLong(), anyString())).thenReturn(List.of(1));
        lenient().when(routingLimitService.evaluate(
                any(ContractorPaymentProfile.class),
                any(ContractorAllocationMode.class),
                anyLong()
        )).thenReturn(ContractorPaymentRoutingLimitService.RoutingLimitDecision.permitted());
        lenient().when(profileRepository.findIdByUserIdAndRole(anyLong(), any(ContractorRole.class)))
                .thenAnswer(invocation -> {
                    Optional<ContractorPaymentProfile> profile = profileRepository
                            .findByUserIdAndRoleForUpdate(
                                    invocation.getArgument(0),
                                    invocation.getArgument(1)
                            );
                    profile.ifPresent(value -> discoveredProfiles.put(value.getId(), value));
                    return profile.map(ContractorPaymentProfile::getId);
                });
        lenient().when(profileRepository.findAllByIdForUpdate(any()))
                .thenAnswer(invocation -> ((java.util.Collection<Long>) invocation.getArgument(0)).stream()
                        .map(discoveredProfiles::get)
                        .filter(java.util.Objects::nonNull)
                        .toList());
        AtomicLong ids = new AtomicLong(100L);
        lenient().when(allocationRepository.save(any(ContractorPaymentAllocation.class))).thenAnswer(invocation -> {
            ContractorPaymentAllocation allocation = invocation.getArgument(0);
            if (allocation.getId() == null) {
                allocation.setId(ids.getAndIncrement());
            }
            return allocation;
        });
    }

    @Test
    void routesWholePaymentToCurrentSpecialistAndFreezesRecipientSnapshot() {
        when(runtimeSwitch.liveRoutingEnabled()).thenReturn(true);
        Worker worker = worker(10L, 110L);
        Manager manager = manager(20L, 120L);
        ContractorPaymentProfile specialist = profile(1L, worker.getUser(), ContractorRole.SPECIALIST);
        specialist.setRecipientName("Иван Исполнитель");
        specialist.setPaymentPhone("2202 2082-3839 6676");
        specialist.setBankName("Банк специалиста");
        specialist.setPaymentComment("За услуги");
        when(profileRepository.findByUserIdAndRoleForUpdate(110L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(specialist));
        when(profileService.available(specialist, ContractorAllocationMode.LIVE)).thenReturn(150_000L);

        ContractorPaymentAllocation allocation = service.reserveForPaymentLink(
                paymentLink(40L, order(30L, worker, manager), 100_000L)
        );

        assertEquals(ContractorRecipientType.SPECIALIST, allocation.getRecipientType());
        assertEquals(110L, allocation.getRecipientUserId());
        assertEquals(10L, allocation.getCurrentWorkerId());
        assertEquals(20L, allocation.getCurrentManagerId());
        assertEquals("Иван Исполнитель", allocation.getRecipientNameSnapshot());
        assertEquals("2202208238396676", allocation.getPaymentPhoneSnapshot());
        assertEquals("Банк специалиста", allocation.getBankNameSnapshot());
        assertEquals("За услуги", allocation.getPaymentCommentSnapshot());
        assertEquals(150_000L, allocation.getAvailableBeforeKopecks());
        assertEquals(ContractorAllocationStatus.RESERVED, allocation.getStatus());
        InOrder currentEligibilityLocks = inOrder(
                rolloutStateService,
                accountingPhaseService,
                userRepository,
                profileRepository
        );
        currentEligibilityLocks.verify(rolloutStateService).lockAndCheckRoutingRequested();
        currentEligibilityLocks.verify(accountingPhaseService).lockAndPromoteForLiveRoute();
        currentEligibilityLocks.verify(userRepository).lockContractorActiveFlag(110L);
        currentEligibilityLocks.verify(userRepository).lockContractorActiveFlag(120L);
        currentEligibilityLocks.verify(userRepository).lockContractorRoleIds(110L, "ROLE_WORKER");
        currentEligibilityLocks.verify(userRepository).lockContractorRoleIds(120L, "ROLE_MANAGER");
        currentEligibilityLocks.verify(profileRepository).findAllByIdForUpdate(List.of(1L));
    }

    @Test
    void ordinaryLiveRouteFailsClosedWhenImmutableSourceSnapshotIsMissing() {
        when(runtimeSwitch.liveRoutingEnabled()).thenReturn(true);
        Worker worker = worker(501L, 601L);
        Manager manager = manager(502L, 602L);
        PaymentLink link = paymentLink(503L, order(504L, worker, manager), 100_000L);
        link.setShadowRouteGeneration(null);
        link.setShadowRoutePreparedAt(null);

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> service.reserveForPaymentLink(link)
        );

        assertEquals(HttpStatus.CONFLICT, failure.getStatusCode());
        assertTrue(failure.getReason().contains("payment_link_source_snapshot_not_ready"));
        verify(profileRepository, never()).findAllByIdForUpdate(any());
        verify(allocationRepository, never()).save(any());
    }

    @Test
    void companyAccountPaymentModeForcesPaymentLinkToOwner() {
        when(runtimeSwitch.liveRoutingEnabled()).thenReturn(true);
        Worker worker = worker(10_001L, 10_101L);
        Manager manager = manager(10_002L, 10_102L);
        Order order = order(10_003L, worker, manager);
        order.getCompany().setContractorPaymentRoutingEnabled(false);

        ContractorPaymentAllocation allocation = service.reserveForPaymentLink(
                paymentLink(10_004L, order, 100_000L)
        );

        assertEquals(ContractorRecipientType.OWNER, allocation.getRecipientType());
        assertEquals(ContractorAllocationStatus.OWNER_FALLBACK, allocation.getStatus());
        assertEquals(
                ContractorRoutingDecisionReason.COMPANY_REQUIRES_OWNER_PAYMENT,
                allocation.getRoutingDecisionReason()
        );
        verify(profileRepository, never()).findIdByUserIdAndRole(anyLong(), any());
    }

    @Test
    void explicitOwnerRouteDoesNotConsumeAvailableSpecialistLimit() {
        when(runtimeSwitch.liveRoutingEnabled()).thenReturn(true);
        Worker worker = worker(20_001L, 20_101L);
        Manager manager = manager(20_002L, 20_102L);

        ContractorPaymentAllocation allocation = service.reserveOwnerForPaymentLink(
                paymentLink(20_004L, order(20_005L, worker, manager), 100_000L)
        );

        assertEquals(ContractorRecipientType.OWNER, allocation.getRecipientType());
        assertEquals(ContractorAllocationStatus.OWNER_FALLBACK, allocation.getStatus());
        assertEquals(
                ContractorRoutingDecisionReason.CLIENT_REQUEST_OWNER_TBANK,
                allocation.getRoutingDecisionReason()
        );
        verify(profileRepository, never()).findByUserIdAndRoleForUpdate(anyLong(), any());
        verify(profileService, never()).available(any(), any());
    }

    @Test
    void explicitEmployeeRouteFailsClosedInsteadOfFallingBackToOwner() {
        when(runtimeSwitch.liveRoutingEnabled()).thenReturn(true);
        Worker worker = worker(21_001L, 21_101L);
        Manager manager = manager(21_002L, 21_102L);
        Order order = order(21_003L, worker, manager);
        order.getCompany().setContractorPaymentRoutingEnabled(false);

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> service.reserveContractorForPaymentLink(paymentLink(21_004L, order, 100_000L))
        );

        assertEquals(HttpStatus.CONFLICT, failure.getStatusCode());
        assertTrue(failure.getReason().contains("специалист"));
        verify(allocationRepository, never()).save(any());
    }

    @Test
    void fallsThroughToManagerOnlyWhenSpecialistDoesNotFit() {
        when(runtimeSwitch.liveRoutingEnabled()).thenReturn(true);
        Worker worker = worker(11L, 111L);
        Manager manager = manager(21L, 121L);
        ContractorPaymentProfile specialist = profile(2L, worker.getUser(), ContractorRole.SPECIALIST);
        ContractorPaymentProfile managerProfile = profile(3L, manager.getUser(), ContractorRole.MANAGER);
        when(profileRepository.findByUserIdAndRoleForUpdate(111L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(specialist));
        when(profileRepository.findByUserIdAndRoleForUpdate(121L, ContractorRole.MANAGER))
                .thenReturn(Optional.of(managerProfile));
        when(profileService.available(specialist, ContractorAllocationMode.LIVE)).thenReturn(99_999L);
        when(profileService.available(managerProfile, ContractorAllocationMode.LIVE)).thenReturn(100_000L);

        ContractorPaymentAllocation allocation = service.reserveForPaymentLink(
                paymentLink(41L, order(31L, worker, manager), 100_000L)
        );

        assertEquals(ContractorRecipientType.MANAGER, allocation.getRecipientType());
        assertEquals(121L, allocation.getRecipientUserId());
        assertEquals(ContractorRoutingDecisionReason.MANAGER_SELECTED, allocation.getRoutingDecisionReason());
        assertEquals(
                ContractorRoutingDecisionReason.INSUFFICIENT_AVAILABLE_BALANCE,
                allocation.getSpecialistRejectionReason()
        );
    }

    @Test
    void disabledSpecialistLiveRoutingFallsThroughToEnabledManager() {
        when(runtimeSwitch.liveRoutingEnabled()).thenReturn(true);
        Worker worker = worker(12L, 112L);
        Manager manager = manager(22L, 122L);
        ContractorPaymentProfile specialist = profile(12L, worker.getUser(), ContractorRole.SPECIALIST);
        specialist.setLiveEnabled(false);
        ContractorPaymentProfile managerProfile = profile(13L, manager.getUser(), ContractorRole.MANAGER);
        when(profileRepository.findByUserIdAndRoleForUpdate(112L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(specialist));
        when(profileRepository.findByUserIdAndRoleForUpdate(122L, ContractorRole.MANAGER))
                .thenReturn(Optional.of(managerProfile));
        when(profileService.available(managerProfile, ContractorAllocationMode.LIVE)).thenReturn(100_000L);

        ContractorPaymentAllocation allocation = service.reserveForPaymentLink(
                paymentLink(142L, order(132L, worker, manager), 100_000L)
        );

        assertEquals(ContractorRecipientType.MANAGER, allocation.getRecipientType());
        assertEquals(122L, allocation.getRecipientUserId());
        assertEquals(ContractorRoutingDecisionReason.MANAGER_SELECTED, allocation.getRoutingDecisionReason());
        assertEquals(
                ContractorRoutingDecisionReason.LIVE_PROFILE_DISABLED,
                allocation.getSpecialistRejectionReason()
        );
        verify(profileService, never()).available(specialist, ContractorAllocationMode.LIVE);
    }

    @Test
    void malformedSpecialistTransferNumberFallsThroughToManager() {
        when(runtimeSwitch.liveRoutingEnabled()).thenReturn(true);
        Worker worker = worker(14L, 114L);
        Manager manager = manager(24L, 124L);
        ContractorPaymentProfile specialist = profile(16L, worker.getUser(), ContractorRole.SPECIALIST);
        specialist.setPaymentPhone("2202 2082 3839 667X");
        ContractorPaymentProfile managerProfile = profile(17L, manager.getUser(), ContractorRole.MANAGER);
        when(profileRepository.findByUserIdAndRoleForUpdate(114L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(specialist));
        when(profileRepository.findByUserIdAndRoleForUpdate(124L, ContractorRole.MANAGER))
                .thenReturn(Optional.of(managerProfile));
        when(profileService.available(managerProfile, ContractorAllocationMode.LIVE)).thenReturn(100_000L);

        ContractorPaymentAllocation allocation = service.reserveForPaymentLink(
                paymentLink(144L, order(134L, worker, manager), 100_000L)
        );

        assertEquals(ContractorRecipientType.MANAGER, allocation.getRecipientType());
        assertEquals(
                ContractorRoutingDecisionReason.RECIPIENT_DETAILS_INCOMPLETE,
                allocation.getSpecialistRejectionReason()
        );
        verify(profileService, never()).available(specialist, ContractorAllocationMode.LIVE);
    }

    @Test
    void commonInvoiceWithDisabledSpecialistFallsThroughToEnabledManager() {
        when(runtimeSwitch.liveRoutingEnabled()).thenReturn(true);
        Worker worker = worker(16L, 116L);
        Manager manager = manager(26L, 126L);
        ContractorPaymentProfile specialist = profile(21L, worker.getUser(), ContractorRole.SPECIALIST);
        specialist.setLiveEnabled(false);
        ContractorPaymentProfile managerProfile = profile(22L, manager.getUser(), ContractorRole.MANAGER);
        when(profileRepository.findByUserIdAndRoleForUpdate(116L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(specialist));
        when(profileRepository.findByUserIdAndRoleForUpdate(126L, ContractorRole.MANAGER))
                .thenReturn(Optional.of(managerProfile));
        when(profileService.available(managerProfile, ContractorAllocationMode.LIVE)).thenReturn(100_000L);

        ContractorPaymentAllocation allocation = service.reserveForCommonInvoice(
                invoice(146L, 100_000L, 0L),
                List.of(order(136L, worker, manager)),
                manager,
                100_000L
        );

        assertEquals(ContractorAllocationSourceType.COMMON_INVOICE, allocation.getSourceType());
        assertEquals(ContractorRecipientType.MANAGER, allocation.getRecipientType());
        assertEquals(126L, allocation.getRecipientUserId());
        assertEquals(ContractorRoutingDecisionReason.MANAGER_SELECTED, allocation.getRoutingDecisionReason());
        assertEquals(
                ContractorRoutingDecisionReason.LIVE_PROFILE_DISABLED,
                allocation.getSpecialistRejectionReason()
        );
        verify(profileService, never()).available(specialist, ContractorAllocationMode.LIVE);
    }

    @Test
    void disabledManagerLiveRoutingFallsBackToOwnerAfterSpecialistDoesNotFit() {
        when(runtimeSwitch.liveRoutingEnabled()).thenReturn(true);
        Worker worker = worker(13L, 113L);
        Manager manager = manager(23L, 123L);
        ContractorPaymentProfile specialist = profile(14L, worker.getUser(), ContractorRole.SPECIALIST);
        ContractorPaymentProfile managerProfile = profile(15L, manager.getUser(), ContractorRole.MANAGER);
        managerProfile.setLiveEnabled(false);
        when(profileRepository.findByUserIdAndRoleForUpdate(113L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(specialist));
        when(profileRepository.findByUserIdAndRoleForUpdate(123L, ContractorRole.MANAGER))
                .thenReturn(Optional.of(managerProfile));
        when(profileService.available(specialist, ContractorAllocationMode.LIVE)).thenReturn(99_999L);

        ContractorPaymentAllocation allocation = service.reserveForPaymentLink(
                paymentLink(143L, order(133L, worker, manager), 100_000L)
        );

        assertEquals(ContractorRecipientType.OWNER, allocation.getRecipientType());
        assertEquals(ContractorAllocationStatus.OWNER_FALLBACK, allocation.getStatus());
        assertEquals(ContractorRoutingDecisionReason.LIVE_PROFILE_DISABLED, allocation.getRoutingDecisionReason());
        assertEquals(
                ContractorRoutingDecisionReason.INSUFFICIENT_AVAILABLE_BALANCE,
                allocation.getSpecialistRejectionReason()
        );
        assertEquals(
                ContractorRoutingDecisionReason.LIVE_PROFILE_DISABLED,
                allocation.getManagerRejectionReason()
        );
        verify(profileService, never()).available(managerProfile, ContractorAllocationMode.LIVE);
    }

    @Test
    void insufficientSpecialistAndManagerBalancesFallBackToOwner() {
        when(runtimeSwitch.liveRoutingEnabled()).thenReturn(true);
        Worker worker = worker(15L, 115L);
        Manager manager = manager(25L, 125L);
        ContractorPaymentProfile specialist = profile(18L, worker.getUser(), ContractorRole.SPECIALIST);
        ContractorPaymentProfile managerProfile = profile(19L, manager.getUser(), ContractorRole.MANAGER);
        when(profileRepository.findByUserIdAndRoleForUpdate(115L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(specialist));
        when(profileRepository.findByUserIdAndRoleForUpdate(125L, ContractorRole.MANAGER))
                .thenReturn(Optional.of(managerProfile));
        when(profileService.available(specialist, ContractorAllocationMode.LIVE)).thenReturn(99_999L);
        when(profileService.available(managerProfile, ContractorAllocationMode.LIVE)).thenReturn(99_999L);

        ContractorPaymentAllocation allocation = service.reserveForPaymentLink(
                paymentLink(145L, order(135L, worker, manager), 100_000L)
        );

        assertEquals(ContractorRecipientType.OWNER, allocation.getRecipientType());
        assertEquals(ContractorAllocationStatus.OWNER_FALLBACK, allocation.getStatus());
        assertEquals(
                ContractorRoutingDecisionReason.INSUFFICIENT_AVAILABLE_BALANCE,
                allocation.getRoutingDecisionReason()
        );
        assertEquals(
                ContractorRoutingDecisionReason.INSUFFICIENT_AVAILABLE_BALANCE,
                allocation.getSpecialistRejectionReason()
        );
        assertEquals(
                ContractorRoutingDecisionReason.INSUFFICIENT_AVAILABLE_BALANCE,
                allocation.getManagerRejectionReason()
        );
    }

    @Test
    void commonInvoiceWithoutImmutableSourceSnapshotFailsClosedBeforeAllocation() {
        when(runtimeSwitch.liveRoutingEnabled()).thenReturn(true);
        CommonInvoice invoice = invoice(14_001L, 100_000L, 0L);
        invoice.setShadowRouteGeneration(null);
        invoice.setShadowRouteAmountKopecks(null);
        invoice.setShadowRoutePreparedAt(null);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.reserveForCommonInvoice(
                        invoice,
                        List.of(order(
                                14_002L,
                                worker(14_003L, 14_004L),
                                manager(14_005L, 14_006L)
                        )),
                        null,
                        100_000L
                )
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertTrue(error.getReason().contains("common_invoice_source_snapshot_not_ready"));
        verify(allocationRepository, never()).save(any());
    }

    @Test
    void bothLiveRoutingTogglesDisabledFallBackDirectlyToOwner() {
        when(runtimeSwitch.liveRoutingEnabled()).thenReturn(true);
        Worker worker = worker(14L, 114L);
        Manager manager = manager(24L, 124L);
        ContractorPaymentProfile specialist = profile(16L, worker.getUser(), ContractorRole.SPECIALIST);
        specialist.setLiveEnabled(false);
        ContractorPaymentProfile managerProfile = profile(17L, manager.getUser(), ContractorRole.MANAGER);
        managerProfile.setLiveEnabled(false);
        when(profileRepository.findByUserIdAndRoleForUpdate(114L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(specialist));
        when(profileRepository.findByUserIdAndRoleForUpdate(124L, ContractorRole.MANAGER))
                .thenReturn(Optional.of(managerProfile));

        CommonInvoice invoice = invoice(144L, 100_000L, 0L);
        ContractorPaymentAllocation allocation = service.reserveForCommonInvoice(
                invoice,
                List.of(order(134L, worker, manager)),
                manager,
                100_000L
        );

        assertEquals(ContractorAllocationSourceType.COMMON_INVOICE, allocation.getSourceType());
        assertEquals(ContractorRecipientType.OWNER, allocation.getRecipientType());
        assertEquals(ContractorAllocationStatus.OWNER_FALLBACK, allocation.getStatus());
        assertNull(allocation.getRecipientProfile());
        assertNull(allocation.getRecipientUserId());
        assertEquals(ContractorRoutingDecisionReason.LIVE_PROFILE_DISABLED, allocation.getRoutingDecisionReason());
        assertEquals(
                ContractorRoutingDecisionReason.LIVE_PROFILE_DISABLED,
                allocation.getSpecialistRejectionReason()
        );
        assertEquals(
                ContractorRoutingDecisionReason.LIVE_PROFILE_DISABLED,
                allocation.getManagerRejectionReason()
        );
        verify(profileService, never()).available(any(ContractorPaymentProfile.class), any(ContractorAllocationMode.class));
    }

    @Test
    void paymentLinkWithBothLiveRoutingTogglesDisabledFallsBackToOwner() {
        when(runtimeSwitch.liveRoutingEnabled()).thenReturn(true);
        Worker worker = worker(17L, 117L);
        Manager manager = manager(27L, 127L);
        ContractorPaymentProfile specialist = profile(23L, worker.getUser(), ContractorRole.SPECIALIST);
        specialist.setLiveEnabled(false);
        ContractorPaymentProfile managerProfile = profile(24L, manager.getUser(), ContractorRole.MANAGER);
        managerProfile.setLiveEnabled(false);
        when(profileRepository.findByUserIdAndRoleForUpdate(117L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(specialist));
        when(profileRepository.findByUserIdAndRoleForUpdate(127L, ContractorRole.MANAGER))
                .thenReturn(Optional.of(managerProfile));

        ContractorPaymentAllocation allocation = service.reserveForPaymentLink(
                paymentLink(147L, order(137L, worker, manager), 100_000L)
        );

        assertEquals(ContractorAllocationSourceType.PAYMENT_LINK, allocation.getSourceType());
        assertEquals(ContractorRecipientType.OWNER, allocation.getRecipientType());
        assertEquals(ContractorAllocationStatus.OWNER_FALLBACK, allocation.getStatus());
        assertNull(allocation.getRecipientProfile());
        assertNull(allocation.getRecipientUserId());
        assertEquals(ContractorRoutingDecisionReason.LIVE_PROFILE_DISABLED, allocation.getRoutingDecisionReason());
        assertEquals(
                ContractorRoutingDecisionReason.LIVE_PROFILE_DISABLED,
                allocation.getSpecialistRejectionReason()
        );
        assertEquals(
                ContractorRoutingDecisionReason.LIVE_PROFILE_DISABLED,
                allocation.getManagerRejectionReason()
        );
    }

    @Test
    void routesToCompanyManagerWhenCurrentOrderHasNoExplicitManager() {
        when(runtimeSwitch.liveRoutingEnabled()).thenReturn(true);
        Manager companyManager = manager(22L, 122L);
        ContractorPaymentProfile managerProfile = profile(4L, companyManager.getUser(), ContractorRole.MANAGER);
        when(profileRepository.findByUserIdAndRoleForUpdate(122L, ContractorRole.MANAGER))
                .thenReturn(Optional.of(managerProfile));
        when(profileService.available(managerProfile, ContractorAllocationMode.LIVE)).thenReturn(100_000L);
        Order order = order(32L, null, null);
        Company company = new Company();
        company.setManager(companyManager);
        order.setCompany(company);

        ContractorPaymentAllocation allocation = service.reserveForPaymentLink(
                paymentLink(42L, order, 100_000L)
        );

        assertEquals(ContractorRecipientType.MANAGER, allocation.getRecipientType());
        assertEquals(122L, allocation.getRecipientUserId());
        assertEquals(22L, allocation.getCurrentManagerId());
        assertEquals(ContractorRoutingDecisionReason.MANAGER_SELECTED, allocation.getRoutingDecisionReason());
    }

    @Test
    void fallsThroughToManagerWhenSpecialistOperationalLimitIsExceeded() {
        when(runtimeSwitch.liveRoutingEnabled()).thenReturn(true);
        Worker worker = worker(1_011L, 1_111L);
        Manager manager = manager(1_021L, 1_121L);
        ContractorPaymentProfile specialist = profile(102L, worker.getUser(), ContractorRole.SPECIALIST);
        ContractorPaymentProfile managerProfile = profile(103L, manager.getUser(), ContractorRole.MANAGER);
        when(profileRepository.findByUserIdAndRoleForUpdate(1_111L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(specialist));
        when(profileRepository.findByUserIdAndRoleForUpdate(1_121L, ContractorRole.MANAGER))
                .thenReturn(Optional.of(managerProfile));
        when(profileService.available(specialist, ContractorAllocationMode.LIVE)).thenReturn(100_000L);
        when(profileService.available(managerProfile, ContractorAllocationMode.LIVE)).thenReturn(100_000L);
        when(routingLimitService.evaluate(specialist, ContractorAllocationMode.LIVE, 100_000L))
                .thenReturn(ContractorPaymentRoutingLimitService.RoutingLimitDecision.rejected(
                        ContractorRoutingDecisionReason.LIMIT_SINGLE_INVOICE_EXCEEDED
                ));

        ContractorPaymentAllocation allocation = service.reserveForPaymentLink(
                paymentLink(1_041L, order(1_031L, worker, manager), 100_000L)
        );

        assertEquals(ContractorRecipientType.MANAGER, allocation.getRecipientType());
        assertEquals(1_121L, allocation.getRecipientUserId());
        assertEquals(ContractorRoutingDecisionReason.MANAGER_SELECTED, allocation.getRoutingDecisionReason());
        assertEquals(
                ContractorRoutingDecisionReason.LIMIT_SINGLE_INVOICE_EXCEEDED,
                allocation.getSpecialistRejectionReason()
        );
        verify(routingLimitService).evaluate(specialist, ContractorAllocationMode.LIVE, 100_000L);
        verify(routingLimitService).evaluate(managerProfile, ContractorAllocationMode.LIVE, 100_000L);
    }

    @Test
    void fallsBackToOwnerWhenBothCandidateOperationalLimitsAreExceeded() {
        when(runtimeSwitch.liveRoutingEnabled()).thenReturn(true);
        Worker worker = worker(2_011L, 2_111L);
        Manager manager = manager(2_021L, 2_121L);
        ContractorPaymentProfile specialist = profile(202L, worker.getUser(), ContractorRole.SPECIALIST);
        ContractorPaymentProfile managerProfile = profile(203L, manager.getUser(), ContractorRole.MANAGER);
        when(profileRepository.findByUserIdAndRoleForUpdate(2_111L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(specialist));
        when(profileRepository.findByUserIdAndRoleForUpdate(2_121L, ContractorRole.MANAGER))
                .thenReturn(Optional.of(managerProfile));
        when(profileService.available(specialist, ContractorAllocationMode.LIVE)).thenReturn(100_000L);
        when(profileService.available(managerProfile, ContractorAllocationMode.LIVE)).thenReturn(100_000L);
        when(routingLimitService.evaluate(specialist, ContractorAllocationMode.LIVE, 100_000L))
                .thenReturn(ContractorPaymentRoutingLimitService.RoutingLimitDecision.rejected(
                        ContractorRoutingDecisionReason.LIMIT_DAILY_AMOUNT_EXCEEDED
                ));
        when(routingLimitService.evaluate(managerProfile, ContractorAllocationMode.LIVE, 100_000L))
                .thenReturn(ContractorPaymentRoutingLimitService.RoutingLimitDecision.rejected(
                        ContractorRoutingDecisionReason.LIMIT_DAILY_COUNT_EXCEEDED
                ));

        ContractorPaymentAllocation allocation = service.reserveForPaymentLink(
                paymentLink(2_041L, order(2_031L, worker, manager), 100_000L)
        );

        assertEquals(ContractorRecipientType.OWNER, allocation.getRecipientType());
        assertEquals(ContractorAllocationStatus.OWNER_FALLBACK, allocation.getStatus());
        assertNull(allocation.getRecipientProfile());
        assertEquals(
                ContractorRoutingDecisionReason.LIMIT_DAILY_COUNT_EXCEEDED,
                allocation.getRoutingDecisionReason()
        );
        assertEquals(
                ContractorRoutingDecisionReason.LIMIT_DAILY_AMOUNT_EXCEEDED,
                allocation.getSpecialistRejectionReason()
        );
        assertEquals(
                ContractorRoutingDecisionReason.LIMIT_DAILY_COUNT_EXCEEDED,
                allocation.getManagerRejectionReason()
        );
    }

    @Test
    void locksCrossRoleCandidatesCanonicallyBeforeApplyingSpecialistPriority() {
        when(runtimeSwitch.liveRoutingEnabled()).thenReturn(true);
        Worker worker = worker(91L, 900L);
        Manager manager = manager(92L, 100L);
        ContractorPaymentProfile specialist = profile(20L, worker.getUser(), ContractorRole.SPECIALIST);
        ContractorPaymentProfile managerProfile = profile(10L, manager.getUser(), ContractorRole.MANAGER);
        when(profileRepository.findByUserIdAndRoleForUpdate(900L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(specialist));
        when(profileRepository.findByUserIdAndRoleForUpdate(100L, ContractorRole.MANAGER))
                .thenReturn(Optional.of(managerProfile));
        when(profileService.available(specialist, ContractorAllocationMode.LIVE)).thenReturn(99_999L);
        when(profileService.available(managerProfile, ContractorAllocationMode.LIVE)).thenReturn(100_000L);

        ContractorPaymentAllocation allocation = service.reserveForPaymentLink(
                paymentLink(4_100L, order(3_100L, worker, manager), 100_000L)
        );

        assertEquals(ContractorRecipientType.MANAGER, allocation.getRecipientType());
        InOrder locks = inOrder(userRepository, profileRepository);
        locks.verify(userRepository).lockContractorActiveFlag(100L);
        locks.verify(userRepository).lockContractorActiveFlag(900L);
        locks.verify(userRepository).lockContractorRoleIds(100L, "ROLE_MANAGER");
        locks.verify(userRepository).lockContractorRoleIds(900L, "ROLE_WORKER");
        locks.verify(profileRepository).findAllByIdForUpdate(List.of(10L, 20L));
    }

    @Test
    void currentUserDeactivationFallsThroughToManagerDespiteStaleOrderGraph() {
        when(runtimeSwitch.liveRoutingEnabled()).thenReturn(true);
        Worker worker = worker(18L, 118L);
        Manager manager = manager(28L, 128L);
        when(userRepository.lockContractorActiveFlag(118L)).thenReturn(Optional.of(false));
        ContractorPaymentProfile managerProfile = profile(7L, manager.getUser(), ContractorRole.MANAGER);
        when(profileRepository.findByUserIdAndRoleForUpdate(128L, ContractorRole.MANAGER))
                .thenReturn(Optional.of(managerProfile));
        when(profileService.available(managerProfile, ContractorAllocationMode.LIVE)).thenReturn(100_000L);

        ContractorPaymentAllocation allocation = service.reserveForPaymentLink(
                paymentLink(46L, order(39L, worker, manager), 100_000L)
        );

        assertEquals(ContractorRecipientType.MANAGER, allocation.getRecipientType());
    }

    @Test
    void currentWorkerRoleRemovalFallsThroughToManagerDespiteStaleOrderGraph() {
        when(runtimeSwitch.liveRoutingEnabled()).thenReturn(true);
        Worker worker = worker(181L, 1_181L);
        Role client = new Role();
        client.setName("ROLE_CLIENT");
        worker.getUser().setRoles(List.of(client));
        when(userRepository.lockContractorRoleIds(1_181L, "ROLE_WORKER")).thenReturn(List.of());
        Manager manager = manager(281L, 1_281L);
        ContractorPaymentProfile managerProfile = profile(71L, manager.getUser(), ContractorRole.MANAGER);
        when(profileRepository.findByUserIdAndRoleForUpdate(1_281L, ContractorRole.MANAGER))
                .thenReturn(Optional.of(managerProfile));
        when(profileService.available(managerProfile, ContractorAllocationMode.LIVE)).thenReturn(100_000L);

        ContractorPaymentAllocation allocation = service.reserveForPaymentLink(
                paymentLink(461L, order(391L, worker, manager), 100_000L)
        );

        assertEquals(ContractorRecipientType.MANAGER, allocation.getRecipientType());
    }

    @Test
    void shadowOnlySpecialistIsSkippedByLiveChain() {
        when(runtimeSwitch.liveRoutingEnabled()).thenReturn(true);
        Worker worker = worker(19L, 119L);
        Manager manager = manager(29L, 129L);
        ContractorPaymentProfile shadowOnly = profile(8L, worker.getUser(), ContractorRole.SPECIALIST);
        shadowOnly.setLiveEnabled(false);
        ContractorPaymentProfile managerProfile = profile(9L, manager.getUser(), ContractorRole.MANAGER);
        when(profileRepository.findByUserIdAndRoleForUpdate(119L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(shadowOnly));
        when(profileRepository.findByUserIdAndRoleForUpdate(129L, ContractorRole.MANAGER))
                .thenReturn(Optional.of(managerProfile));
        when(profileService.available(managerProfile, ContractorAllocationMode.LIVE)).thenReturn(100_000L);

        ContractorPaymentAllocation allocation = service.reserveForPaymentLink(
                paymentLink(47L, order(40L, worker, manager), 100_000L)
        );

        assertEquals(ContractorRecipientType.MANAGER, allocation.getRecipientType());
        verify(profileService, never()).available(shadowOnly, ContractorAllocationMode.LIVE);
    }

    @Test
    void mixedCommonInvoiceSkipsSpecialistsAndUsesManager() {
        when(runtimeSwitch.liveRoutingEnabled()).thenReturn(true);
        Worker first = worker(12L, 112L);
        Worker second = worker(13L, 113L);
        Manager manager = manager(22L, 122L);
        ContractorPaymentProfile managerProfile = profile(4L, manager.getUser(), ContractorRole.MANAGER);
        when(profileRepository.findByUserIdAndRoleForUpdate(122L, ContractorRole.MANAGER))
                .thenReturn(Optional.of(managerProfile));
        when(profileService.available(managerProfile, ContractorAllocationMode.LIVE)).thenReturn(300_000L);
        CommonInvoice invoice = invoice(50L, 300_000L, 0L);

        ContractorPaymentAllocation allocation = service.reserveForCommonInvoice(
                invoice,
                List.of(order(32L, first, manager), order(33L, second, manager)),
                manager,
                300_000L
        );

        assertEquals(ContractorRecipientType.MANAGER, allocation.getRecipientType());
        assertNull(allocation.getCurrentWorkerId());
        assertEquals(ContractorRoutingDecisionReason.MANAGER_SELECTED, allocation.getRoutingDecisionReason());
        assertEquals(
                ContractorRoutingDecisionReason.MIXED_SPECIALISTS,
                allocation.getSpecialistRejectionReason()
        );
        verify(profileRepository, never()).findByUserIdAndRoleForUpdate(112L, ContractorRole.SPECIALIST);
        verify(profileRepository, never()).findByUserIdAndRoleForUpdate(113L, ContractorRole.SPECIALIST);
    }

    @Test
    void commonInvoiceWithAnyAccountPaymentCompanyForcesWholeInvoiceToOwner() {
        when(runtimeSwitch.liveRoutingEnabled()).thenReturn(true);
        Worker worker = worker(12_001L, 12_101L);
        Manager manager = manager(12_002L, 12_102L);
        Order linkPaymentOrder = order(12_003L, worker, manager);
        Order accountPaymentOrder = order(12_004L, worker, manager);
        accountPaymentOrder.getCompany().setContractorPaymentRoutingEnabled(false);

        CommonInvoice invoice = invoice(12_005L, 200_000L, 0L);
        invoice.setShadowRouteCompanyRoutingAllowed(false);
        ContractorPaymentAllocation allocation = service.reserveForCommonInvoice(
                invoice,
                List.of(linkPaymentOrder, accountPaymentOrder),
                manager,
                200_000L
        );

        assertEquals(ContractorRecipientType.OWNER, allocation.getRecipientType());
        assertEquals(
                ContractorRoutingDecisionReason.COMPANY_REQUIRES_OWNER_PAYMENT,
                allocation.getRoutingDecisionReason()
        );
        verify(profileRepository, never()).findIdByUserIdAndRole(anyLong(), any());
    }

    @Test
    void commonInvoiceWithPriorPaymentFallsBackToOwnerBecauseAggregateCannotProveRecipient() {
        when(runtimeSwitch.liveRoutingEnabled()).thenReturn(true);
        Worker worker = worker(14L, 114L);
        Manager manager = manager(24L, 124L);
        CommonInvoice invoice = invoice(51L, 300_000L, 100_000L);
        invoice.setShadowRouteContractorEligible(false);

        ContractorPaymentAllocation allocation = service.reserveForCommonInvoice(
                invoice,
                List.of(order(34L, worker, manager)),
                manager,
                200_000L
        );

        assertEquals(ContractorRecipientType.OWNER, allocation.getRecipientType());
        assertEquals(ContractorAllocationStatus.OWNER_FALLBACK, allocation.getStatus());
        assertEquals(
                ContractorRoutingDecisionReason.PRIOR_PAYMENT_EVIDENCE,
                allocation.getRoutingDecisionReason()
        );
        assertEquals(100_000L, allocation.getSourcePaidBaselineKopecks());
        assertEquals(200_000L, allocation.getAmountKopecks());
        verify(profileRepository, never()).findIdByUserIdAndRole(anyLong(), any());
        verify(accountingPhaseService).lockAndPromoteForLiveRoute();
    }

    @ParameterizedTest
    @EnumSource(value = ContractorAllocationStatus.class, names = {"RELEASED_UNPAID", "EXPIRED"})
    void createsNextAttemptForReleasedOrExpiredSource(ContractorAllocationStatus previousStatus) {
        when(runtimeSwitch.liveRoutingEnabled()).thenReturn(true);
        Worker worker = worker(15L, 115L);
        Manager manager = manager(25L, 125L);
        ContractorPaymentProfile specialist = profile(6L, worker.getUser(), ContractorRole.SPECIALIST);
        ContractorPaymentAllocation previous = new ContractorPaymentAllocation();
        previous.setId(90L);
        previous.setMode(ContractorAllocationMode.LIVE);
        previous.setSourceType(ContractorAllocationSourceType.PAYMENT_LINK);
        previous.setSourceId(42L);
        previous.setAttemptNo(1);
        previous.setStatus(previousStatus);
        when(allocationRepository.findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(
                ContractorAllocationMode.LIVE,
                ContractorAllocationSourceType.PAYMENT_LINK,
                42L
        )).thenReturn(Optional.of(previous));
        when(profileRepository.findByUserIdAndRoleForUpdate(115L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(specialist));
        when(profileService.available(specialist, ContractorAllocationMode.LIVE)).thenReturn(100_000L);

        ContractorPaymentAllocation retried = service.reserveForPaymentLink(
                paymentLink(42L, order(35L, worker, manager), 100_000L)
        );

        assertEquals(2, retried.getAttemptNo());
        assertEquals(ContractorAllocationStatus.RESERVED, retried.getStatus());
        assertEquals(ContractorRecipientType.SPECIALIST, retried.getRecipientType());
    }

    @Test
    void explicitCommonContractorReplacementCreatesNextAttemptAfterCanceledRoute() {
        when(runtimeSwitch.liveRoutingEnabled()).thenReturn(true);
        Worker worker = worker(35L, 135L);
        Manager manager = manager(45L, 145L);
        ContractorPaymentProfile specialist = profile(56L, worker.getUser(), ContractorRole.SPECIALIST);
        CommonInvoice invoice = invoice(142L, 100_000L, 0L);
        ContractorPaymentAllocation previous = commonAllocation(
                190L,
                invoice.getId(),
                ContractorAllocationStatus.CANCELED
        );
        previous.setAttemptNo(1);
        when(allocationRepository.findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(
                ContractorAllocationMode.LIVE,
                ContractorAllocationSourceType.COMMON_INVOICE,
                invoice.getId()
        )).thenReturn(Optional.of(previous));
        when(profileRepository.findByUserIdAndRoleForUpdate(135L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(specialist));
        when(profileService.available(specialist, ContractorAllocationMode.LIVE)).thenReturn(100_000L);

        ContractorPaymentAllocation replacement = service.reserveContractorForCommonInvoice(
                invoice,
                List.of(order(135L, worker, manager)),
                manager,
                100_000L
        );

        assertEquals(2, replacement.getAttemptNo());
        assertEquals(ContractorAllocationStatus.RESERVED, replacement.getStatus());
        assertEquals(ContractorRecipientType.SPECIALIST, replacement.getRecipientType());
        assertEquals(135L, replacement.getRecipientUserId());
    }

    @Test
    void explicitCommonOwnerReplacementDoesNotSelectEligibleContractor() {
        when(runtimeSwitch.liveRoutingEnabled()).thenReturn(true);
        Worker worker = worker(36L, 136L);
        Manager manager = manager(46L, 146L);
        CommonInvoice invoice = invoice(143L, 100_000L, 0L);

        ContractorPaymentAllocation replacement = service.reserveOwnerForCommonInvoice(
                invoice,
                List.of(order(136L, worker, manager)),
                manager,
                100_000L
        );

        assertEquals(ContractorAllocationStatus.OWNER_FALLBACK, replacement.getStatus());
        assertEquals(ContractorRecipientType.OWNER, replacement.getRecipientType());
        assertEquals(
                ContractorRoutingDecisionReason.CLIENT_REQUEST_OWNER_TBANK,
                replacement.getRoutingDecisionReason()
        );
        verify(profileRepository, never()).findIdByUserIdAndRole(anyLong(), any());
    }

    @Test
    void explicitCommonContractorReplacementFailsClosedWhenNoEmployeeCanAcceptAmount() {
        when(runtimeSwitch.liveRoutingEnabled()).thenReturn(true);
        Worker worker = worker(37L, 137L);
        Manager manager = manager(47L, 147L);
        ContractorPaymentProfile specialist = profile(57L, worker.getUser(), ContractorRole.SPECIALIST);
        ContractorPaymentProfile managerProfile = profile(58L, manager.getUser(), ContractorRole.MANAGER);
        CommonInvoice invoice = invoice(144L, 100_000L, 0L);
        when(profileRepository.findByUserIdAndRoleForUpdate(137L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(specialist));
        when(profileRepository.findByUserIdAndRoleForUpdate(147L, ContractorRole.MANAGER))
                .thenReturn(Optional.of(managerProfile));
        when(profileService.available(specialist, ContractorAllocationMode.LIVE)).thenReturn(99_999L);
        when(profileService.available(managerProfile, ContractorAllocationMode.LIVE)).thenReturn(99_999L);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.reserveContractorForCommonInvoice(
                        invoice,
                        List.of(order(137L, worker, manager)),
                        manager,
                        100_000L
                )
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(allocationRepository, never()).save(any());
    }

    @Test
    void commonRouteReplacementRejectsClientReportedReservationBeforeRelease() {
        CommonInvoice invoice = invoice(145L, 100_000L, 0L);
        ContractorPaymentAllocation allocation = commonAllocation(
                191L,
                invoice.getId(),
                ContractorAllocationStatus.CLIENT_REPORTED
        );
        invoice.setContractorAllocationId(allocation.getId());
        when(allocationRepository.findRecipientProfileIdById(allocation.getId())).thenReturn(Optional.empty());
        when(allocationRepository.findByIdForUpdate(allocation.getId())).thenReturn(Optional.of(allocation));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.releaseCommonInvoiceRouteForReplacement(
                        invoice,
                        "Смена способа оплаты",
                        "LIVE_COMMON:ROUTE_REPLACED"
                )
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals(ContractorAllocationStatus.CLIENT_REPORTED, allocation.getStatus());
        verify(eventRepository, never()).save(any());
    }

    @Test
    void commonRouteReplacementClosesOwnerFallbackBeforeEmployeeRouteIsCreated() {
        CommonInvoice invoice = invoice(146L, 100_000L, 0L);
        ContractorPaymentAllocation allocation = commonAllocation(
                192L,
                invoice.getId(),
                ContractorAllocationStatus.OWNER_FALLBACK
        );
        invoice.setContractorAllocationId(allocation.getId());
        when(allocationRepository.findRecipientProfileIdById(allocation.getId())).thenReturn(Optional.empty());
        when(allocationRepository.findByIdForUpdate(allocation.getId())).thenReturn(Optional.of(allocation));

        boolean released = service.releaseCommonInvoiceRouteForReplacement(
                invoice,
                "Смена способа оплаты",
                "LIVE_COMMON:ROUTE_REPLACED"
        );

        assertTrue(released);
        assertEquals(ContractorAllocationStatus.CANCELED, allocation.getStatus());
        verify(eventRepository).save(any(ContractorPaymentAllocationEvent.class));
        verify(allocationRepository).save(allocation);
    }

    @Test
    void returnsExistingNonRetryableAttemptWithoutChangingFrozenDecision() {
        when(runtimeSwitch.liveRoutingEnabled()).thenReturn(true);
        ContractorPaymentAllocation existing = new ContractorPaymentAllocation();
        existing.setStatus(ContractorAllocationStatus.RESERVED);
        when(allocationRepository.findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(
                ContractorAllocationMode.LIVE,
                ContractorAllocationSourceType.PAYMENT_LINK,
                43L
        )).thenReturn(Optional.of(existing));

        ContractorPaymentAllocation result = service.reserveForPaymentLink(
                paymentLink(43L, order(36L, worker(16L, 116L), manager(26L, 126L)), 100_000L)
        );

        assertSame(existing, result);
        verify(profileRepository, never()).findByUserIdAndRoleForUpdate(any(), any());
        verify(allocationRepository, never()).save(existing);
    }

    @Test
    void globalOffIgnoresPerProfileEligibilityAndCreatesNoLiveDecision() {
        when(runtimeSwitch.liveRoutingEnabled()).thenReturn(false);

        ContractorPaymentAllocation result = service.reserveForPaymentLink(
                paymentLink(44L, order(37L, worker(17L, 117L), manager(27L, 127L)), 100_000L)
        );

        assertNull(result);
        verify(allocationRepository, never())
                .findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(any(), any(), any());
        verify(profileRepository, never()).findByUserIdAndRoleForUpdate(any(), any());
        verify(rolloutStateService, never()).lockAndCheckRoutingRequested();
        verify(userRepository, never()).lockContractorActiveFlag(anyLong());
        verify(profileService, never()).available(any(ContractorPaymentProfile.class), any(ContractorAllocationMode.class));
    }

    @Test
    void concurrentRoutingPauseWinsUnderRolloutLockBeforeAccountingPhasePromotion() {
        when(runtimeSwitch.liveRoutingEnabled()).thenReturn(true);
        when(rolloutStateService.lockAndCheckRoutingRequested()).thenReturn(false);

        ContractorPaymentAllocation result = service.reserveForPaymentLink(
                paymentLink(4_044L, order(4_037L, worker(4_017L, 4_117L), manager(4_027L, 4_127L)), 100_000L)
        );

        assertNull(result);
        verify(rolloutStateService).lockAndCheckRoutingRequested();
        verify(accountingPhaseService, never()).lockAndPromoteForLiveRoute();
        verify(profileRepository, never()).findByUserIdAndRoleForUpdate(any(), any());
        verify(allocationRepository, never()).save(any());
    }

    @Test
    void emergencyStopStillReleasesOutstandingPartOfFrozenLiveRoute() {
        ContractorPaymentProfile recipient = profile(90L, user(190L), ContractorRole.SPECIALIST);
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(91L);
        allocation.setMode(ContractorAllocationMode.LIVE);
        allocation.setSourceType(ContractorAllocationSourceType.PAYMENT_LINK);
        allocation.setSourceId(45L);
        allocation.setAmountKopecks(100_000L);
        allocation.setConfirmedKopecks(40_000L);
        allocation.setStatus(ContractorAllocationStatus.PARTIALLY_CONFIRMED);
        allocation.setRecipientProfile(recipient);
        when(allocationRepository.findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(
                ContractorAllocationMode.LIVE,
                ContractorAllocationSourceType.PAYMENT_LINK,
                45L
        )).thenReturn(Optional.of(allocation));
        when(allocationRepository.findRecipientProfileIdById(91L)).thenReturn(Optional.of(90L));
        when(profileRepository.findByIdForUpdate(90L)).thenReturn(Optional.of(recipient));
        when(allocationRepository.findByIdForUpdate(91L)).thenReturn(Optional.of(allocation));
        PaymentLink link = paymentLink(45L, order(38L, null, null), 100_000L);
        link.setStatus(PaymentLinkStatus.EXPIRED);
        LocalDateTime expiredAt = LocalDateTime.now().minusMinutes(1);
        link.setExpiresAt(expiredAt);

        assertTrue(service.releaseClosedPaymentLink(link));

        assertEquals(ContractorAllocationStatus.EXPIRED, allocation.getStatus());
        assertEquals(40_000L, allocation.getConfirmedKopecks());
        assertEquals(expiredAt, allocation.getReleasedAt());
        verify(runtimeSwitch, never()).liveRoutingEnabled();
        InOrder locks = inOrder(allocationRepository, profileRepository);
        locks.verify(allocationRepository).findRecipientProfileIdById(91L);
        locks.verify(profileRepository).findByIdForUpdate(90L);
        locks.verify(allocationRepository).findByIdForUpdate(91L);
    }

    @Test
    void forcedEarlyLiveExpiryNeverUsesFutureDeadline() {
        ContractorPaymentProfile recipient = profile(91L, user(191L), ContractorRole.SPECIALIST);
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(92L);
        allocation.setMode(ContractorAllocationMode.LIVE);
        allocation.setSourceType(ContractorAllocationSourceType.PAYMENT_LINK);
        allocation.setSourceId(46L);
        allocation.setAmountKopecks(100_000L);
        allocation.setStatus(ContractorAllocationStatus.RESERVED);
        allocation.setRecipientProfile(recipient);
        when(allocationRepository.findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(
                ContractorAllocationMode.LIVE,
                ContractorAllocationSourceType.PAYMENT_LINK,
                46L
        )).thenReturn(Optional.of(allocation));
        when(allocationRepository.findRecipientProfileIdById(92L)).thenReturn(Optional.of(91L));
        when(profileRepository.findByIdForUpdate(91L)).thenReturn(Optional.of(recipient));
        when(allocationRepository.findByIdForUpdate(92L)).thenReturn(Optional.of(allocation));
        PaymentLink link = paymentLink(46L, order(39L, null, null), 100_000L);
        link.setStatus(PaymentLinkStatus.EXPIRED);
        link.setExpiresAt(LocalDateTime.now().plusMonths(3));
        link.setUpdatedAt(LocalDateTime.now().minusMinutes(5));
        LocalDateTime beforeRelease = LocalDateTime.now();

        assertTrue(service.releaseClosedPaymentLink(link));

        LocalDateTime afterRelease = LocalDateTime.now();
        assertTrue(!allocation.getReleasedAt().isBefore(beforeRelease));
        assertTrue(!allocation.getReleasedAt().isAfter(afterRelease));
        assertTrue(allocation.getReleasedAt().isBefore(link.getExpiresAt()));
        ArgumentCaptor<ContractorPaymentAllocationEvent> event =
                ArgumentCaptor.forClass(ContractorPaymentAllocationEvent.class);
        verify(eventRepository).save(event.capture());
        assertEquals(allocation.getReleasedAt(), event.getValue().getEffectiveAt());
    }

    @ParameterizedTest
    @EnumSource(
            value = ContractorAllocationStatus.class,
            names = {"RELEASED_UNPAID", "PARTIALLY_RETURNED", "RETURNED"}
    )
    void terminalCommonRouteBlocksAutomaticReissueWithoutAttemptBoundEvidence(
            ContractorAllocationStatus status
    ) {
        ContractorPaymentAllocation allocation = commonAllocation(92L, 52L, status);
        stubFrozenCommonSource(52L, 92L);
        when(allocationRepository.findByIdForUpdate(92L)).thenReturn(Optional.of(allocation));

        assertEquals(
                ContractorPaymentLiveRoutingService.FrozenCommonRouteAction.BLOCK_RECONCILIATION,
                service.frozenCommonRouteAction(52L, 92L)
        );
    }

    @Test
    void unknownPartialReturnAmountBlocksCommonRouteUntilReconciled() {
        ContractorPaymentAllocation allocation = commonAllocation(
                93L,
                53L,
                ContractorAllocationStatus.RETURN_AMOUNT_PENDING
        );
        stubFrozenCommonSource(53L, 93L);
        when(allocationRepository.findByIdForUpdate(93L)).thenReturn(Optional.of(allocation));

        assertEquals(
                ContractorPaymentLiveRoutingService.FrozenCommonRouteAction.BLOCK_RECONCILIATION,
                service.frozenCommonRouteAction(53L, 93L)
        );
    }

    @Test
    void currentOwnerFallbackRemainsFrozenUntilItsSourceIsExplicitlyClosed() {
        ContractorPaymentAllocation allocation = commonAllocation(
                94L,
                54L,
                ContractorAllocationStatus.OWNER_FALLBACK
        );
        stubFrozenCommonSource(54L, 94L);
        when(allocationRepository.findByIdForUpdate(94L)).thenReturn(Optional.of(allocation));

        assertEquals(
                ContractorPaymentLiveRoutingService.FrozenCommonRouteAction.KEEP,
                service.frozenCommonRouteAction(54L, 94L)
        );
    }

    @Test
    void commonRoutePreviewUsesOnlyNonLockingReads() {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(55L);
        invoice.setContractorAllocationId(95L);
        ContractorPaymentAllocation allocation = commonAllocation(
                95L,
                55L,
                ContractorAllocationStatus.OWNER_FALLBACK
        );
        when(commonInvoiceRepository.findById(55L)).thenReturn(Optional.of(invoice));
        when(allocationRepository.findById(95L)).thenReturn(Optional.of(allocation));

        assertEquals(
                ContractorPaymentLiveRoutingService.FrozenCommonRouteAction.KEEP,
                service.previewFrozenCommonRouteAction(55L, 95L)
        );

        verify(commonInvoiceRepository, never()).findByIdForUpdate(anyLong());
        verify(allocationRepository, never()).findByIdForUpdate(anyLong());
        verify(profileRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void commonClientReportIsIdempotentAndKeepsMoneyUnconfirmedAndReserved() {
        ContractorPaymentProfile recipient = profile(15L, user(215L), ContractorRole.SPECIALIST);
        ContractorPaymentAllocation allocation = reportAllocation(
                95L, 60L, recipient, ContractorRecipientType.SPECIALIST,
                ContractorAllocationStatus.RESERVED
        );
        CommonInvoice invoice = reportableInvoice(60L, 95L, "public-token");
        stubCommonReportRoute("public-token", invoice, allocation, recipient);

        LocalDateTime first = service.recordCommonClientReported("public-token");
        LocalDateTime second = service.recordCommonClientReported("public-token");

        assertNotNull(first);
        assertEquals(first, second);
        assertEquals(first, invoice.getClientReportedAt());
        assertEquals(ContractorAllocationStatus.CLIENT_REPORTED, allocation.getStatus());
        assertEquals(0L, allocation.getConfirmedKopecks());
        assertEquals(100_000L, allocation.getAmountKopecks());
        verify(eventRepository, times(1)).save(any());
        InOrder locks = inOrder(allocationRepository, profileRepository, commonInvoiceRepository);
        locks.verify(commonInvoiceRepository).findByIdForUpdate(60L);
        locks.verify(allocationRepository).findRecipientProfileIdById(95L);
        locks.verify(profileRepository).findByIdForUpdate(15L);
        locks.verify(allocationRepository).findByIdForUpdate(95L);
    }

    @Test
    void exactCommonConfirmationRequiresTheImmutableGenerationAndAmountSnapshot() {
        ContractorPaymentProfile recipient = profile(151L, user(251L), ContractorRole.SPECIALIST);
        ContractorPaymentAllocation allocation = reportAllocation(
                195L, 160L, recipient, ContractorRecipientType.SPECIALIST,
                ContractorAllocationStatus.RESERVED
        );
        CommonInvoice invoice = reportableInvoice(160L, 195L, "exact-source-token");
        when(commonInvoiceRepository.findByIdForUpdate(160L)).thenReturn(Optional.of(invoice));
        when(allocationRepository.findRecipientProfileIdById(195L)).thenReturn(Optional.of(151L));
        when(profileRepository.findByIdForUpdate(151L)).thenReturn(Optional.of(recipient));
        when(allocationRepository.findByIdForUpdate(195L)).thenReturn(Optional.of(allocation));

        assertSame(allocation, service.validatedCommonConfirmationSource(160L, 195L));

        invoice.setShadowRouteGeneration("different-generation");
        ResponseStatusException mismatch = assertThrows(
                ResponseStatusException.class,
                () -> service.validatedCommonConfirmationSource(160L, 195L)
        );
        assertEquals(HttpStatus.CONFLICT, mismatch.getStatusCode());
    }

    @ParameterizedTest
    @EnumSource(value = ContractorAllocationStatus.class, names = {
            "RELEASED_UNPAID", "EXPIRED", "CANCELED", "PARTIALLY_CONFIRMED", "CONFIRMED", "RETURNED"
    })
    void commonClientReportNeverResurrectsTerminalAllocation(ContractorAllocationStatus status) {
        ContractorPaymentProfile recipient = profile(16L, user(216L), ContractorRole.SPECIALIST);
        ContractorPaymentAllocation allocation = reportAllocation(
                96L, 61L, recipient, ContractorRecipientType.SPECIALIST, status
        );
        CommonInvoice invoice = reportableInvoice(61L, 96L, "terminal-token");
        stubCommonReportRoute("terminal-token", invoice, allocation, recipient);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.recordCommonClientReported("terminal-token")
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals(status, allocation.getStatus());
        verify(eventRepository, never()).save(any());
    }

    @Test
    void commonClientReportRejectsOwnerFallbackWithoutRecipientProfile() {
        ContractorPaymentAllocation allocation = reportAllocation(
                97L, 62L, null, ContractorRecipientType.OWNER,
                ContractorAllocationStatus.OWNER_FALLBACK
        );
        CommonInvoice invoice = reportableInvoice(62L, 97L, "owner-token");
        stubCommonReportRoute("owner-token", invoice, allocation, null);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.recordCommonClientReported("owner-token")
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(eventRepository, never()).save(any());
    }

    @Test
    void commonClientReportRejectsStaleFrozenAllocationBinding() {
        ContractorPaymentProfile recipient = profile(17L, user(217L), ContractorRole.SPECIALIST);
        ContractorPaymentAllocation allocation = reportAllocation(
                98L, 63L, recipient, ContractorRecipientType.SPECIALIST,
                ContractorAllocationStatus.RESERVED
        );
        CommonInvoice invoice = reportableInvoice(63L, 999L, "stale-token");
        stubCommonReportRoute("stale-token", invoice, allocation, recipient);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.recordCommonClientReported("stale-token")
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals(ContractorAllocationStatus.RESERVED, allocation.getStatus());
    }

    @Test
    void commonClientReportRejectsNonPayableInvoice() {
        ContractorPaymentProfile recipient = profile(18L, user(218L), ContractorRole.SPECIALIST);
        ContractorPaymentAllocation allocation = reportAllocation(
                99L, 64L, recipient, ContractorRecipientType.SPECIALIST,
                ContractorAllocationStatus.RESERVED
        );
        CommonInvoice invoice = reportableInvoice(64L, 99L, "closed-token");
        invoice.setStatus(CommonInvoiceStatus.UNPAID);
        stubCommonReportRoute("closed-token", invoice, allocation, recipient);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.recordCommonClientReported("closed-token")
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(eventRepository, never()).save(any());
    }

    @Test
    void partiallyConfirmedCommonRouteIsNotAdvertisedAsClientReportable() {
        ContractorPaymentProfile recipient = profile(19L, user(219L), ContractorRole.SPECIALIST);
        ContractorPaymentAllocation allocation = reportAllocation(
                100L, 65L, recipient, ContractorRecipientType.SPECIALIST,
                ContractorAllocationStatus.PARTIALLY_CONFIRMED
        );
        CommonInvoice invoice = reportableInvoice(65L, 100L, "partial-token");
        when(allocationRepository.findById(100L)).thenReturn(Optional.of(allocation));

        assertFalse(service.isCommonClientReportable(invoice));
    }

    @Test
    void changedCommonInvoiceGenerationIsNotAdvertisedAsClientReportable() {
        ContractorPaymentProfile recipient = profile(191L, user(291L), ContractorRole.SPECIALIST);
        ContractorPaymentAllocation allocation = reportAllocation(
                1001L, 651L, recipient, ContractorRecipientType.SPECIALIST,
                ContractorAllocationStatus.RESERVED
        );
        CommonInvoice invoice = reportableInvoice(651L, 1001L, "changed-reportable-token");
        invoice.setAmountKopecks(120_000L);
        when(allocationRepository.findById(1001L)).thenReturn(Optional.of(allocation));

        assertFalse(service.isCommonClientReportable(invoice));
    }

    @Test
    void commonClientReportRejectsChangedInvoiceGeneration() {
        ContractorPaymentProfile recipient = profile(192L, user(292L), ContractorRole.SPECIALIST);
        ContractorPaymentAllocation allocation = reportAllocation(
                1002L, 652L, recipient, ContractorRecipientType.SPECIALIST,
                ContractorAllocationStatus.RESERVED
        );
        CommonInvoice invoice = reportableInvoice(652L, 1002L, "changed-report-token");
        invoice.setAmountKopecks(120_000L);
        stubCommonReportRoute("changed-report-token", invoice, allocation, recipient);

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.recordCommonClientReported("changed-report-token")
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals(ContractorAllocationStatus.RESERVED, allocation.getStatus());
        verify(eventRepository, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = ContractorAllocationStatus.class, names = {
            "CONFIRMED", "RELEASED_UNPAID", "EXPIRED", "CANCELED",
            "PARTIALLY_RETURNED", "RETURN_AMOUNT_PENDING", "RETURNED"
    })
    void terminalCommonAttemptNeverExposesContractorRequisites(
            ContractorAllocationStatus status
    ) {
        ContractorPaymentProfile recipient = profile(20L, user(220L), ContractorRole.SPECIALIST);
        ContractorPaymentAllocation allocation = reportAllocation(
                101L, 66L, recipient, ContractorRecipientType.SPECIALIST, status
        );
        CommonInvoice invoice = reportableInvoice(66L, 101L, "hidden-token");
        when(allocationRepository.findById(101L)).thenReturn(Optional.of(allocation));

        assertFalse(service.isCommonContractorRequisitesVisible(invoice));
    }

    @ParameterizedTest
    @EnumSource(value = ContractorAllocationStatus.class, names = {
            "RESERVED", "CLIENT_REPORTED", "PARTIALLY_CONFIRMED"
    })
    void currentPayableCommonAttemptMayExposeContractorRequisites(
            ContractorAllocationStatus status
    ) {
        ContractorPaymentProfile recipient = profile(21L, user(221L), ContractorRole.SPECIALIST);
        ContractorPaymentAllocation allocation = reportAllocation(
                102L, 67L, recipient, ContractorRecipientType.SPECIALIST, status
        );
        CommonInvoice invoice = reportableInvoice(67L, 102L, "visible-token");
        if (status == ContractorAllocationStatus.PARTIALLY_CONFIRMED) {
            allocation.setConfirmedKopecks(40_000L);
            invoice.setPaidKopecks(40_000L);
        }
        when(allocationRepository.findById(102L)).thenReturn(Optional.of(allocation));

        assertTrue(service.isCommonContractorRequisitesVisible(invoice));
    }

    @Test
    void changedCommonInvoiceAmountCannotReuseFrozenContractorGeneration() {
        ContractorPaymentProfile recipient = profile(22L, user(222L), ContractorRole.SPECIALIST);
        ContractorPaymentAllocation allocation = reportAllocation(
                103L, 68L, recipient, ContractorRecipientType.SPECIALIST,
                ContractorAllocationStatus.RESERVED
        );
        CommonInvoice invoice = reportableInvoice(68L, 103L, "changed-token");
        invoice.setAmountKopecks(120_000L);
        when(allocationRepository.findById(103L)).thenReturn(Optional.of(allocation));

        assertFalse(service.isCommonContractorRequisitesVisible(invoice, 120_000L));
    }

    @Test
    void releasedStandaloneAllocationIsNeverAdvertisedByStillPayableLink() {
        Worker worker = worker(801L, 901L);
        PaymentLink link = paymentLink(802L, order(803L, worker, null), 100_000L);
        link.setContractorAllocationId(804L);
        link.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
        link.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        link.setManualPaymentType(ManualPaymentType.MOBILE_BANK);
        link.setManualSource(ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE);
        link.setReservedAmountKopecks(100_000L);
        link.setShadowRouteGeneration("link-generation-802");
        link.setShadowRouteOrderId(803L);
        link.setShadowRouteAmountKopecks(100_000L);
        ContractorPaymentProfile recipient = profile(805L, worker.getUser(), ContractorRole.SPECIALIST);
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(804L);
        allocation.setMode(ContractorAllocationMode.LIVE);
        allocation.setSourceType(ContractorAllocationSourceType.PAYMENT_LINK);
        allocation.setSourceId(802L);
        allocation.setOrderId(803L);
        allocation.setRecipientProfile(recipient);
        allocation.setRecipientType(ContractorRecipientType.SPECIALIST);
        allocation.setRecipientUserId(worker.getUser().getId());
        allocation.setSourceGenerationSnapshot("link-generation-802");
        allocation.setRecipientNameSnapshot("Получатель");
        allocation.setPaymentPhoneSnapshot("+79990000000");
        allocation.setBankNameSnapshot("Тестовый банк");
        allocation.setPaymentCommentSnapshot("");
        allocation.setAmountKopecks(100_000L);
        allocation.setStatus(ContractorAllocationStatus.RELEASED_UNPAID);
        when(allocationRepository.findById(804L)).thenReturn(Optional.of(allocation));

        assertFalse(service.isPaymentLinkContractorRequisitesVisible(link));
    }

    @Test
    void activeStandaloneRouteReturnsOnlyEncryptedAllocationSnapshot() {
        Worker worker = worker(811L, 911L);
        PaymentLink link = paymentLink(812L, order(813L, worker, null), 100_000L);
        link.setContractorAllocationId(814L);
        link.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
        link.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        link.setManualPaymentType(ManualPaymentType.MOBILE_BANK);
        link.setManualSource(ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE);
        link.setReservedAmountKopecks(100_000L);
        link.setShadowRouteGeneration("link-generation-812");
        link.setShadowRouteOrderId(813L);
        link.setShadowRouteAmountKopecks(100_000L);
        assertNull(link.getManualPhone());
        assertNull(link.getManualRecipientName());

        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(814L);
        allocation.setMode(ContractorAllocationMode.LIVE);
        allocation.setSourceType(ContractorAllocationSourceType.PAYMENT_LINK);
        allocation.setSourceId(812L);
        allocation.setSourceGenerationSnapshot("link-generation-812");
        allocation.setOrderId(813L);
        allocation.setRecipientProfile(profile(815L, worker.getUser(), ContractorRole.SPECIALIST));
        allocation.setRecipientType(ContractorRecipientType.SPECIALIST);
        allocation.setRecipientUserId(worker.getUser().getId());
        allocation.setRecipientNameSnapshot("Получатель из шифрованного snapshot");
        allocation.setPaymentPhoneSnapshot("+79990000042");
        allocation.setBankNameSnapshot("Snapshot банк");
        allocation.setPaymentCommentSnapshot("Snapshot комментарий");
        allocation.setAmountKopecks(100_000L);
        allocation.setStatus(ContractorAllocationStatus.RESERVED);
        when(allocationRepository.findById(814L)).thenReturn(Optional.of(allocation));
        when(allocationRepository.findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(
                ContractorAllocationMode.LIVE,
                ContractorAllocationSourceType.PAYMENT_LINK,
                812L
        )).thenReturn(Optional.of(allocation));

        var snapshot = service.activePaymentLinkRequisites(link).orElseThrow();

        assertEquals("Получатель из шифрованного snapshot", snapshot.recipientName());
        assertEquals("+79990000042", snapshot.paymentPhone());
        assertEquals("Snapshot банк", snapshot.bankName());
        assertEquals("Snapshot комментарий", snapshot.paymentComment());
    }

    @Test
    void activeStandaloneRouteRedactsGenerationMismatch() {
        Worker worker = worker(821L, 921L);
        PaymentLink link = paymentLink(822L, order(823L, worker, null), 100_000L);
        link.setContractorAllocationId(824L);
        link.setStatus(PaymentLinkStatus.WAITING_MANUAL_PAYMENT);
        link.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        link.setManualPaymentType(ManualPaymentType.MOBILE_BANK);
        link.setManualSource(ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE);
        link.setReservedAmountKopecks(100_000L);
        link.setShadowRouteGeneration("source-generation");
        link.setShadowRouteOrderId(823L);
        link.setShadowRouteAmountKopecks(100_000L);

        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(824L);
        allocation.setMode(ContractorAllocationMode.LIVE);
        allocation.setSourceType(ContractorAllocationSourceType.PAYMENT_LINK);
        allocation.setSourceId(822L);
        allocation.setSourceGenerationSnapshot("different-generation");
        allocation.setOrderId(823L);
        allocation.setRecipientProfile(profile(825L, worker.getUser(), ContractorRole.SPECIALIST));
        allocation.setRecipientType(ContractorRecipientType.SPECIALIST);
        allocation.setRecipientNameSnapshot("Не показывать");
        allocation.setPaymentPhoneSnapshot("+79990000099");
        allocation.setAmountKopecks(100_000L);
        allocation.setStatus(ContractorAllocationStatus.RESERVED);
        when(allocationRepository.findById(824L)).thenReturn(Optional.of(allocation));

        assertTrue(service.activePaymentLinkRequisites(link).isEmpty());
        verify(allocationRepository, never())
                .findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(
                        ContractorAllocationMode.LIVE,
                        ContractorAllocationSourceType.PAYMENT_LINK,
                        822L
                );
    }

    private void stubCommonReportRoute(
            String token,
            CommonInvoice invoice,
            ContractorPaymentAllocation allocation,
            ContractorPaymentProfile recipient
    ) {
        CommonInvoiceRepository.ContractorRouteRef ref = mock(CommonInvoiceRepository.ContractorRouteRef.class);
        when(ref.getInvoiceId()).thenReturn(invoice.getId());
        when(ref.getAllocationId()).thenReturn(allocation.getId());
        when(commonInvoiceRepository.findContractorRouteRefByToken(token)).thenReturn(Optional.of(ref));
        if (recipient != null) {
            lenient().when(allocationRepository.findRecipientProfileIdById(allocation.getId()))
                    .thenReturn(Optional.of(recipient.getId()));
            lenient().when(profileRepository.findByIdForUpdate(recipient.getId())).thenReturn(Optional.of(recipient));
        }
        lenient().when(allocationRepository.findByIdForUpdate(allocation.getId())).thenReturn(Optional.of(allocation));
        when(commonInvoiceRepository.findByIdForUpdate(invoice.getId())).thenReturn(Optional.of(invoice));
        lenient().when(commonInvoiceRepository.save(any(CommonInvoice.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void stubFrozenCommonSource(Long invoiceId, Long allocationId) {
        CommonInvoice invoice = invoice(invoiceId, 100_000L, 0L);
        invoice.setContractorAllocationId(allocationId);
        when(commonInvoiceRepository.findByIdForUpdate(invoiceId)).thenReturn(Optional.of(invoice));
    }

    private ContractorPaymentAllocation reportAllocation(
            Long id,
            Long invoiceId,
            ContractorPaymentProfile recipient,
            ContractorRecipientType recipientType,
            ContractorAllocationStatus status
    ) {
        ContractorPaymentAllocation allocation = commonAllocation(id, invoiceId, status);
        allocation.setAttemptNo(1);
        allocation.setRecipientProfile(recipient);
        allocation.setRecipientType(recipientType);
        allocation.setAmountKopecks(100_000L);
        allocation.setSourceGenerationSnapshot("generation-" + invoiceId);
        if (recipient != null && recipient.getUser() != null) {
            allocation.setRecipientUserId(recipient.getUser().getId());
            allocation.setRecipientNameSnapshot("Получатель");
            allocation.setPaymentPhoneSnapshot("+79990000000");
            allocation.setBankNameSnapshot("Тестовый банк");
            allocation.setPaymentCommentSnapshot("Комментарий");
        }
        lenient().when(allocationRepository
                        .findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(
                                ContractorAllocationMode.LIVE,
                                ContractorAllocationSourceType.COMMON_INVOICE,
                                invoiceId
                        ))
                .thenReturn(Optional.of(allocation));
        return allocation;
    }

    private CommonInvoice reportableInvoice(Long id, Long allocationId, String token) {
        CommonInvoice invoice = invoice(id, 100_000L, 0L);
        invoice.setToken(token);
        invoice.setStatus(CommonInvoiceStatus.READY);
        invoice.setContractorAllocationId(allocationId);
        invoice.setPaymentRouteType("MANUAL_MOBILE_BANK");
        invoice.setPaymentRouteManualSource(ManualPaymentSource.CONTRACTOR_PAYMENT_PROFILE);
        invoice.setPaymentRouteManualType(ManualPaymentType.MOBILE_BANK);
        invoice.setPaymentRouteAmountKopecks(100_000L);
        invoice.setShadowRouteGeneration("generation-" + id);
        invoice.setShadowRouteAmountKopecks(100_000L);
        return invoice;
    }

    private ContractorPaymentProfile profile(Long id, User user, ContractorRole role) {
        ContractorPaymentProfile profile = new ContractorPaymentProfile();
        profile.setId(id);
        profile.setUser(user);
        profile.setRole(role);
        profile.setEnabled(true);
        profile.setLiveEnabled(true);
        profile.setRecipientName("Получатель");
        profile.setPaymentPhone("+79990000000");
        profile.setBankName("Тестовый банк");
        return profile;
    }

    private PaymentLink paymentLink(Long id, Order order, long amountKopecks) {
        PaymentLink link = new PaymentLink();
        link.setId(id);
        link.setOrder(order);
        link.setAmountKopecks(amountKopecks);
        link.setShadowRouteGeneration("generation-" + id);
        link.setShadowRouteOrderId(order == null ? null : order.getId());
        link.setShadowRouteAmountKopecks(amountKopecks);
        link.setShadowRouteCompanyRoutingAllowed(order != null
                && order.getCompany() != null
                && order.getCompany().isContractorPaymentRoutingEnabled());
        link.setShadowRoutePreparedAt(LocalDateTime.of(2026, 8, 7, 10, 0));
        return link;
    }

    private CommonInvoice invoice(Long id, long amountKopecks, long paidKopecks) {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(id);
        invoice.setAmountKopecks(amountKopecks);
        invoice.setPaidKopecks(paidKopecks);
        invoice.setShadowRouteGeneration("generation-" + id);
        invoice.setShadowRouteAmountKopecks(Math.max(0L, amountKopecks - paidKopecks));
        invoice.setShadowRoutePreparedAt(LocalDateTime.of(2026, 8, 24, 20, 0));
        invoice.setShadowRouteContractorEligible(true);
        invoice.setShadowRouteCompanyRoutingAllowed(true);
        return invoice;
    }

    private ContractorPaymentAllocation commonAllocation(
            Long id,
            Long invoiceId,
            ContractorAllocationStatus status
    ) {
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(id);
        allocation.setMode(ContractorAllocationMode.LIVE);
        allocation.setSourceType(ContractorAllocationSourceType.COMMON_INVOICE);
        allocation.setSourceId(invoiceId);
        allocation.setCommonInvoiceId(invoiceId);
        allocation.setStatus(status);
        return allocation;
    }

    private Order order(Long id, Worker worker, Manager manager) {
        Order order = new Order();
        order.setId(id);
        order.setWorker(worker);
        order.setManager(manager);
        Company company = new Company();
        company.setContractorPaymentRoutingEnabled(true);
        order.setCompany(company);
        return order;
    }

    private Worker worker(Long id, Long userId) {
        Worker worker = new Worker();
        worker.setId(id);
        worker.setUser(user(userId));
        return worker;
    }

    private Manager manager(Long id, Long userId) {
        Manager manager = new Manager();
        manager.setId(id);
        manager.setUser(user(userId));
        return manager;
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setActive(true);
        Role worker = new Role();
        worker.setName("ROLE_WORKER");
        Role manager = new Role();
        manager.setName("ROLE_MANAGER");
        user.setRoles(List.of(worker, manager));
        return user;
    }
}
