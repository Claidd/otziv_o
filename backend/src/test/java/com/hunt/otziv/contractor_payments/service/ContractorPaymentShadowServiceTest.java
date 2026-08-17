package com.hunt.otziv.contractor_payments.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoicePaymentRefRepository;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.contractor_payments.model.ContractorActualPaymentAttribution;
import com.hunt.otziv.contractor_payments.model.ContractorActualPaymentSourceKind;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationMode;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationSourceType;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationStatus;
import com.hunt.otziv.contractor_payments.model.ContractorAllocationEventType;
import com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocationEvent;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentProfile;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.contractor_payments.model.ContractorRoutingDecisionReason;
import com.hunt.otziv.contractor_payments.repository.ContractorActualPaymentAttributionRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentAllocationEventRepository;
import com.hunt.otziv.contractor_payments.repository.ContractorPaymentProfileRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.service.ManualPaymentTaskContractorCapacityService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class ContractorPaymentShadowServiceTest {

    private final ContractorActualPaymentAttributionRepository actualPaymentAttributionRepository =
            mock(ContractorActualPaymentAttributionRepository.class);
    private final ManualPaymentTaskContractorReturnBridge taskReturnBridge =
            mock(ManualPaymentTaskContractorReturnBridge.class);
    private final ContractorPaymentAllocationRepository allocationRepository = mock(ContractorPaymentAllocationRepository.class);
    private final ContractorPaymentProfileRepository profileRepository = mock(ContractorPaymentProfileRepository.class);
    private final ContractorPaymentProfileService profileService = mock(ContractorPaymentProfileService.class);
    private final ManualPaymentTaskContractorCapacityService taskCapacityService =
            mock(ManualPaymentTaskContractorCapacityService.class);
    private final ContractorPaymentRoutingLimitService routingLimitService =
            mock(ContractorPaymentRoutingLimitService.class);
    private final ContractorPaymentAllocationEventRepository eventRepository = mock(ContractorPaymentAllocationEventRepository.class);
    private final ContractorPaymentAccountingService accountingService = new ContractorPaymentAccountingService(eventRepository);
    private final PaymentLinkRepository paymentLinkRepository = mock(PaymentLinkRepository.class);
    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final CommonInvoiceRepository commonInvoiceRepository = mock(CommonInvoiceRepository.class);
    private final CommonInvoiceOrderRepository commonInvoiceOrderRepository = mock(CommonInvoiceOrderRepository.class);
    private final CommonInvoicePaymentRefRepository commonInvoicePaymentRefRepository =
            mock(CommonInvoicePaymentRefRepository.class);
    private final AppSettingService appSettingService = mock(AppSettingService.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ContractorOrderManagerResolver orderManagerResolver =
            spy(new ContractorOrderManagerResolver());
    private final Map<Long, ContractorPaymentProfile> discoveredProfiles = new HashMap<>();
    private final ContractorPaymentShadowService service = new ContractorPaymentShadowService(
            actualPaymentAttributionRepository,
            taskReturnBridge,
            allocationRepository,
            profileRepository,
            profileService,
            taskCapacityService,
            routingLimitService,
            accountingService,
            paymentLinkRepository,
            orderRepository,
            commonInvoiceRepository,
            commonInvoiceOrderRepository,
            commonInvoicePaymentRefRepository,
            appSettingService,
            entityManager,
            userRepository,
            orderManagerResolver
    );

    @BeforeEach
    void enableShadowMode() {
        lenient().when(taskCapacityService.ordinaryAvailable(any(), any()))
                .thenAnswer(invocation -> profileService.available(
                        invocation.getArgument(0), invocation.getArgument(1)));
        when(allocationRepository.currentDatabaseTime())
                .thenReturn(LocalDateTime.of(2026, 8, 7, 12, 0));
        when(appSettingService.getBoolean(AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_ENABLED, true))
                .thenReturn(true);
        when(allocationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.lockContractorActiveFlag(anyLong())).thenReturn(Optional.of(true));
        when(userRepository.lockContractorRoleIds(anyLong(), anyString())).thenReturn(List.of(1));
        when(routingLimitService.evaluate(
                any(ContractorPaymentProfile.class),
                any(ContractorAllocationMode.class),
                anyLong()
        )).thenReturn(ContractorPaymentRoutingLimitService.RoutingLimitDecision.permitted());
        when(profileRepository.findIdByUserIdAndRole(anyLong(), any(ContractorRole.class)))
                .thenAnswer(invocation -> {
                    Optional<ContractorPaymentProfile> profile = profileRepository
                            .findByUserIdAndRoleForUpdate(
                                    invocation.getArgument(0),
                                    invocation.getArgument(1)
                            );
                    profile.ifPresent(value -> discoveredProfiles.put(value.getId(), value));
                    return profile.map(ContractorPaymentProfile::getId);
                });
        when(profileRepository.findAllByIdForUpdate(anyCollection()))
                .thenAnswer(invocation -> ((java.util.Collection<Long>) invocation.getArgument(0)).stream()
                        .map(discoveredProfiles::get)
                        .filter(java.util.Objects::nonNull)
                        .toList());
    }

    @Test
    void disabledShadowPreparationIsNoOpBeforeResolverAndPaymentEvidenceRead() {
        when(appSettingService.getBoolean(AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_ENABLED, true))
                .thenReturn(false);
        PaymentLink link = paymentLink(
                9_001L,
                order(9_002L, worker(9_003L, 9_004L), manager(9_005L, 9_006L)),
                10_000L
        );
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(9_007L);

        assertNull(service.preparePaymentLinkSource(link, LocalDateTime.now()));
        assertNull(service.prepareCommonInvoiceSource(
                invoice,
                List.of(link.getOrder()),
                link.getOrder().getManager(),
                10_000L,
                LocalDateTime.now()
        ));

        assertNull(link.getShadowRouteGeneration());
        assertNull(link.getShadowRouteOrderId());
        assertNull(invoice.getShadowRouteGeneration());
        assertNull(invoice.getShadowRouteAmountKopecks());
        verify(orderManagerResolver, never()).resolveForRouting(any(Order.class));
        verify(commonInvoicePaymentRefRepository, never()).findIdsByInvoiceIdForUpdate(anyLong());
    }

    @Test
    void shadowPreparationFailureCannotBreakLegacyPaymentLinkRoute() {
        Order order = order(9_012L, worker(9_013L, 9_014L), manager(9_015L, 9_016L));
        PaymentLink link = paymentLink(9_011L, order, 10_000L);
        doThrow(new IllegalStateException("synthetic manager identity conflict"))
                .when(orderManagerResolver).resolveForRouting(order);

        String generation = assertDoesNotThrow(() ->
                service.preparePaymentLinkSource(link, LocalDateTime.now()));

        assertNull(generation);
        assertNull(link.getShadowRouteGeneration());
        assertNull(link.getShadowRouteOrderId());
    }

    @Test
    void shadowPreparationFailureCannotPartiallyMutateOrBreakLegacyCommonRoute() {
        Order order = order(9_022L, worker(9_023L, 9_024L), manager(9_025L, 9_026L));
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(9_021L);
        when(commonInvoicePaymentRefRepository.findIdsByInvoiceIdForUpdate(9_021L))
                .thenThrow(new IllegalStateException("synthetic contractor ref failure"));

        String generation = assertDoesNotThrow(() -> service.prepareCommonInvoiceSource(
                invoice,
                List.of(order),
                order.getManager(),
                10_000L,
                LocalDateTime.now()
        ));

        assertNull(generation);
        assertNull(invoice.getShadowRouteGeneration());
        assertNull(invoice.getShadowRouteAmountKopecks());
        assertNull(invoice.getShadowRouteMembershipHash());
    }

    @Test
    void disabledShadowCallbacksReturnBeforeSourceReadsAndLocks() {
        when(appSettingService.getBoolean(AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_ENABLED, true))
                .thenReturn(false);

        assertNull(service.reserveForPaymentLinkId(9_101L, "generation"));
        assertNull(service.reserveForCommonInvoiceId(9_102L, "generation"));
        assertEquals(
                ContractorPaymentShadowService.ShadowReservationOutcome.OUT_OF_SCOPE,
                service.reserveForPaymentLinkIdOutcome(9_103L).outcome()
        );
        assertEquals(
                ContractorPaymentShadowService.ShadowReservationOutcome.OUT_OF_SCOPE,
                service.reserveForCommonInvoiceIdOutcome(9_104L).outcome()
        );

        verify(paymentLinkRepository, never()).findByIdForUpdate(anyLong());
        verify(paymentLinkRepository, never()).existsById(anyLong());
        verify(commonInvoiceOrderRepository, never()).findOrderIdsByInvoiceId(anyLong());
        verify(commonInvoiceRepository, never()).findByIdForUpdate(anyLong());
        verify(commonInvoiceRepository, never()).existsById(anyLong());
    }

    @Test
    void paymentLinkReconcileChecksTaskReturnEvenWithoutContractorAllocation() {
        PaymentLink link = new PaymentLink();
        link.setId(9_105L);
        when(paymentLinkRepository.findByIdForUpdate(9_105L)).thenReturn(Optional.of(link));

        assertEquals(0, service.reconcilePaymentLinkId(9_105L));

        verify(taskReturnBridge).recordAuthoritativePaymentLinkReturn(link);
    }

    @Test
    void routesNewInvoiceToCurrentSpecialistWhenEntireAmountFits() {
        Worker worker = worker(10L, 100L);
        Manager manager = manager(20L, 200L);
        Order order = order(30L, worker, manager);
        PaymentLink link = paymentLink(40L, order, 100_000L);
        ContractorPaymentProfile specialist = profile(1L, worker.getUser(), ContractorRole.SPECIALIST);
        specialist.setPaymentPhone("2202 2082-3839 6676");
        when(profileRepository.findByUserIdAndRoleForUpdate(100L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(specialist));
        when(profileService.available(specialist, ContractorAllocationMode.SHADOW)).thenReturn(300_000L);

        ContractorPaymentAllocation allocation = service.reserveForPaymentLink(link);

        assertEquals(ContractorRecipientType.SPECIALIST, allocation.getRecipientType());
        assertEquals(100L, allocation.getRecipientUserId());
        assertEquals(300_000L, allocation.getAvailableBeforeKopecks());
        assertEquals(10L, allocation.getCurrentWorkerId());
        assertEquals("2202208238396676", allocation.getPaymentPhoneSnapshot());
        assertEquals(
                ContractorRoutingDecisionReason.SPECIALIST_SELECTED,
                allocation.getRoutingDecisionReason()
        );
    }

    @Test
    void companyAccountPaymentModeForcesPreparedPaymentLinkToOwner() {
        Worker worker = worker(10_001L, 10_101L);
        Manager manager = manager(10_002L, 10_102L);
        Order order = order(10_003L, worker, manager);
        order.getCompany().setContractorPaymentRoutingEnabled(false);
        PaymentLink link = paymentLink(10_004L, order, 100_000L);

        String generation = service.preparePaymentLinkSource(link, LocalDateTime.now());
        order.getCompany().setContractorPaymentRoutingEnabled(true);
        ContractorPaymentAllocation allocation = service.reserveForPaymentLinkId(10_004L, generation);

        assertEquals(false, link.isShadowRouteCompanyRoutingAllowed());
        assertEquals(ContractorRecipientType.OWNER, allocation.getRecipientType());
        assertEquals(ContractorAllocationStatus.OWNER_FALLBACK, allocation.getStatus());
        assertEquals(
                ContractorRoutingDecisionReason.COMPANY_REQUIRES_OWNER_PAYMENT,
                allocation.getRoutingDecisionReason()
        );
        verify(entityManager).lock(order.getCompany(), LockModeType.PESSIMISTIC_WRITE);
        verify(profileRepository, never()).findIdByUserIdAndRole(anyLong(), any());
    }

    @Test
    void fallsThroughToManagerWhenSpecialistBalanceDoesNotFit() {
        Worker worker = worker(11L, 101L);
        Manager manager = manager(21L, 201L);
        Order order = order(31L, worker, manager);
        PaymentLink link = paymentLink(41L, order, 150_000L);
        ContractorPaymentProfile specialist = profile(2L, worker.getUser(), ContractorRole.SPECIALIST);
        ContractorPaymentProfile managerProfile = profile(3L, manager.getUser(), ContractorRole.MANAGER);
        when(profileRepository.findByUserIdAndRoleForUpdate(101L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(specialist));
        when(profileRepository.findByUserIdAndRoleForUpdate(201L, ContractorRole.MANAGER))
                .thenReturn(Optional.of(managerProfile));
        when(profileService.available(specialist, ContractorAllocationMode.SHADOW)).thenReturn(149_999L);
        when(profileService.available(managerProfile, ContractorAllocationMode.SHADOW)).thenReturn(200_000L);

        ContractorPaymentAllocation allocation = service.reserveForPaymentLink(link);

        assertEquals(ContractorRecipientType.MANAGER, allocation.getRecipientType());
        assertEquals(201L, allocation.getRecipientUserId());
        assertEquals(ContractorRoutingDecisionReason.MANAGER_SELECTED, allocation.getRoutingDecisionReason());
        assertEquals(
                ContractorRoutingDecisionReason.INSUFFICIENT_AVAILABLE_BALANCE,
                allocation.getSpecialistRejectionReason()
        );
    }

    @Test
    void shadowManagerSelectionPreservesSpecialistOperationalLimitReason() {
        Worker worker = worker(1_011L, 1_101L);
        Manager manager = manager(1_021L, 1_201L);
        ContractorPaymentProfile specialist = profile(102L, worker.getUser(), ContractorRole.SPECIALIST);
        ContractorPaymentProfile managerProfile = profile(103L, manager.getUser(), ContractorRole.MANAGER);
        when(profileRepository.findByUserIdAndRoleForUpdate(1_101L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(specialist));
        when(profileRepository.findByUserIdAndRoleForUpdate(1_201L, ContractorRole.MANAGER))
                .thenReturn(Optional.of(managerProfile));
        when(profileService.available(specialist, ContractorAllocationMode.SHADOW)).thenReturn(100_000L);
        when(profileService.available(managerProfile, ContractorAllocationMode.SHADOW)).thenReturn(100_000L);
        when(routingLimitService.evaluate(specialist, ContractorAllocationMode.SHADOW, 100_000L))
                .thenReturn(ContractorPaymentRoutingLimitService.RoutingLimitDecision.rejected(
                        ContractorRoutingDecisionReason.LIMIT_DAILY_AMOUNT_EXCEEDED
                ));

        ContractorPaymentAllocation allocation = service.reserveForPaymentLink(
                paymentLink(1_041L, order(1_031L, worker, manager), 100_000L)
        );

        assertEquals(ContractorRecipientType.MANAGER, allocation.getRecipientType());
        assertEquals(ContractorRoutingDecisionReason.MANAGER_SELECTED, allocation.getRoutingDecisionReason());
        assertEquals(
                ContractorRoutingDecisionReason.LIMIT_DAILY_AMOUNT_EXCEEDED,
                allocation.getSpecialistRejectionReason()
        );
    }

    @Test
    void locksCrossRoleCandidatesCanonicallyBeforeApplyingSpecialistPriority() {
        Worker worker = worker(91L, 900L);
        Manager manager = manager(92L, 100L);
        ContractorPaymentProfile specialist = profile(20L, worker.getUser(), ContractorRole.SPECIALIST);
        ContractorPaymentProfile managerProfile = profile(10L, manager.getUser(), ContractorRole.MANAGER);
        when(profileRepository.findByUserIdAndRoleForUpdate(900L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(specialist));
        when(profileRepository.findByUserIdAndRoleForUpdate(100L, ContractorRole.MANAGER))
                .thenReturn(Optional.of(managerProfile));
        when(profileService.available(specialist, ContractorAllocationMode.SHADOW)).thenReturn(99_999L);
        when(profileService.available(managerProfile, ContractorAllocationMode.SHADOW)).thenReturn(100_000L);

        ContractorPaymentAllocation allocation = service.reserveForPaymentLink(
                paymentLink(4_101L, order(3_101L, worker, manager), 100_000L)
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
    void delayedPaymentLinkCallbackUsesPreparationWorkerAfterOrderTransfer() {
        Worker preparedWorker = worker(301L, 3_001L);
        Worker laterWorker = worker(302L, 3_002L);
        Manager manager = manager(303L, 3_003L);
        Order order = order(304L, preparedWorker, manager);
        PaymentLink link = paymentLink(305L, order, 80_000L);
        String generation = service.preparePaymentLinkSource(
                link,
                LocalDateTime.of(2026, 8, 7, 12, 0)
        );
        order.setWorker(laterWorker);
        ContractorPaymentProfile preparedProfile = profile(
                306L,
                preparedWorker.getUser(),
                ContractorRole.SPECIALIST
        );
        when(paymentLinkRepository.findByIdForUpdate(305L)).thenReturn(Optional.of(link));
        when(profileRepository.findByUserIdAndRoleForUpdate(3_001L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(preparedProfile));
        when(profileService.available(preparedProfile, ContractorAllocationMode.SHADOW))
                .thenReturn(80_000L);

        ContractorPaymentAllocation allocation = service.reserveForPaymentLinkId(305L, generation);

        assertEquals(3_001L, allocation.getRecipientUserId());
        assertEquals(301L, allocation.getCurrentWorkerId());
        assertEquals(generation, allocation.getSourceGenerationSnapshot());
        verify(profileRepository, never())
                .findByUserIdAndRoleForUpdate(3_002L, ContractorRole.SPECIALIST);
    }

    @Test
    void paymentLinkPreparedAfterTransferUsesNewWorkerCandidate() {
        Worker first = worker(311L, 3_101L);
        Worker transferred = worker(312L, 3_102L);
        Order order = order(313L, first, null);
        order.setWorker(transferred);
        PaymentLink link = paymentLink(314L, order, 70_000L);
        String generation = service.preparePaymentLinkSource(link, LocalDateTime.now());
        ContractorPaymentProfile transferredProfile = profile(
                315L,
                transferred.getUser(),
                ContractorRole.SPECIALIST
        );
        when(paymentLinkRepository.findByIdForUpdate(314L)).thenReturn(Optional.of(link));
        when(profileRepository.findByUserIdAndRoleForUpdate(3_102L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(transferredProfile));
        when(profileService.available(transferredProfile, ContractorAllocationMode.SHADOW))
                .thenReturn(70_000L);

        ContractorPaymentAllocation allocation = service.reserveForPaymentLinkId(314L, generation);

        assertEquals(3_102L, allocation.getRecipientUserId());
        assertEquals(312L, allocation.getCurrentWorkerId());
    }

    @Test
    void preparationFreezesCompanyManagerFallbackWhenOrderManagerIsMissing() {
        Manager companyManager = manager(316L, 3_106L);
        Order order = order(317L, null, null);
        Company company = new Company();
        company.setManager(companyManager);
        order.setCompany(company);
        PaymentLink link = paymentLink(318L, order, 70_000L);

        service.preparePaymentLinkSource(link, LocalDateTime.of(2026, 8, 7, 12, 10));

        assertEquals(316L, link.getShadowRouteManagerId());
        assertEquals(3_106L, link.getShadowRouteManagerUserId());
    }

    @Test
    void delayedCommonInvoiceCallbackUsesHomogeneousPreparationWorkerAfterTransfer() {
        Worker preparedWorker = worker(321L, 3_201L);
        Worker laterWorker = worker(322L, 3_202L);
        Manager manager = manager(323L, 3_203L);
        Order order = order(324L, preparedWorker, manager);
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(325L);
        invoice.setAmountKopecks(90_000L);
        String generation = service.prepareCommonInvoiceSource(
                invoice,
                List.of(order),
                manager,
                90_000L,
                LocalDateTime.of(2026, 8, 7, 12, 5)
        );
        invoice.setPaymentRouteAmountKopecks(90_000L);
        order.setWorker(laterWorker);
        CommonInvoiceOrder membership = new CommonInvoiceOrder();
        membership.setInvoice(invoice);
        membership.setOrder(order);
        ContractorPaymentProfile preparedProfile = profile(
                326L,
                preparedWorker.getUser(),
                ContractorRole.SPECIALIST
        );
        when(commonInvoiceOrderRepository.findOrderIdsByInvoiceId(325L)).thenReturn(List.of(324L));
        when(orderRepository.findByIdForCounterUpdate(324L)).thenReturn(Optional.of(order));
        when(commonInvoiceRepository.findByIdForUpdate(325L)).thenReturn(Optional.of(invoice));
        when(commonInvoiceOrderRepository.findMembershipByInvoiceIdForRead(325L))
                .thenReturn(List.of(membership));
        when(profileRepository.findByUserIdAndRoleForUpdate(3_201L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(preparedProfile));
        when(profileService.available(preparedProfile, ContractorAllocationMode.SHADOW))
                .thenReturn(90_000L);

        ContractorPaymentAllocation allocation = service.reserveForCommonInvoiceId(325L, generation);

        assertEquals(3_201L, allocation.getRecipientUserId());
        assertEquals(321L, allocation.getCurrentWorkerId());
        assertEquals(generation, allocation.getSourceGenerationSnapshot());
        verify(profileRepository, never())
                .findByUserIdAndRoleForUpdate(3_202L, ContractorRole.SPECIALIST);
    }

    @Test
    void currentUserDeactivationFallsThroughToManagerDespiteStaleOrderGraph() {
        Worker worker = worker(16L, 106L);
        Manager manager = manager(26L, 206L);
        when(userRepository.lockContractorActiveFlag(106L)).thenReturn(Optional.of(false));
        ContractorPaymentProfile managerProfile = profile(7L, manager.getUser(), ContractorRole.MANAGER);
        when(profileRepository.findByUserIdAndRoleForUpdate(206L, ContractorRole.MANAGER))
                .thenReturn(Optional.of(managerProfile));
        when(profileService.available(managerProfile, ContractorAllocationMode.SHADOW)).thenReturn(100_000L);

        ContractorPaymentAllocation allocation = service.reserveForPaymentLink(
                paymentLink(46L, order(36L, worker, manager), 100_000L)
        );

        assertEquals(ContractorRecipientType.MANAGER, allocation.getRecipientType());
    }

    @Test
    void currentWorkerRoleRemovalFallsThroughToManagerDespiteStaleOrderGraph() {
        Worker worker = worker(161L, 1_061L);
        Role client = new Role();
        client.setName("ROLE_CLIENT");
        worker.getUser().setRoles(List.of(client));
        when(userRepository.lockContractorRoleIds(1_061L, "ROLE_WORKER")).thenReturn(List.of());
        Manager manager = manager(261L, 2_061L);
        ContractorPaymentProfile managerProfile = profile(17L, manager.getUser(), ContractorRole.MANAGER);
        when(profileRepository.findByUserIdAndRoleForUpdate(2_061L, ContractorRole.MANAGER))
                .thenReturn(Optional.of(managerProfile));
        when(profileService.available(managerProfile, ContractorAllocationMode.SHADOW)).thenReturn(100_000L);

        ContractorPaymentAllocation allocation = service.reserveForPaymentLink(
                paymentLink(461L, order(361L, worker, manager), 100_000L)
        );

        assertEquals(ContractorRecipientType.MANAGER, allocation.getRecipientType());
    }

    @Test
    void mixedCommonInvoiceSkipsSpecialistsAndChecksManager() {
        Worker first = worker(12L, 102L);
        Worker second = worker(13L, 103L);
        Manager manager = manager(22L, 202L);
        ContractorPaymentProfile managerProfile = profile(4L, manager.getUser(), ContractorRole.MANAGER);
        when(profileRepository.findByUserIdAndRoleForUpdate(202L, ContractorRole.MANAGER))
                .thenReturn(Optional.of(managerProfile));
        when(profileService.available(managerProfile, ContractorAllocationMode.SHADOW)).thenReturn(500_000L);
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(50L);

        ContractorPaymentAllocation allocation = service.reserveForCommonInvoice(
                invoice,
                List.of(order(32L, first, manager), order(33L, second, manager)),
                manager,
                250_000L
        );

        assertEquals(ContractorAllocationSourceType.COMMON_INVOICE, allocation.getSourceType());
        assertEquals(ContractorRecipientType.MANAGER, allocation.getRecipientType());
        assertNull(allocation.getCurrentWorkerId());
        assertEquals(ContractorRoutingDecisionReason.MANAGER_SELECTED, allocation.getRoutingDecisionReason());
        assertEquals(
                ContractorRoutingDecisionReason.MIXED_SPECIALISTS,
                allocation.getSpecialistRejectionReason()
        );
        verify(profileRepository, never()).findByUserIdAndRoleForUpdate(102L, ContractorRole.SPECIALIST);
        verify(profileRepository, never()).findByUserIdAndRoleForUpdate(103L, ContractorRole.SPECIALIST);
    }

    @Test
    void commonInvoiceWithAnyAccountPaymentCompanyForcesWholeInvoiceToOwner() {
        Worker worker = worker(12_001L, 12_101L);
        Manager manager = manager(12_002L, 12_102L);
        Order linkPaymentOrder = order(12_003L, worker, manager);
        Order accountPaymentOrder = order(12_004L, worker, manager);
        accountPaymentOrder.getCompany().setContractorPaymentRoutingEnabled(false);
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(12_005L);
        invoice.setAmountKopecks(200_000L);

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
    void commonInvoiceWithPriorAggregatePaymentFallsBackToOwner() {
        Worker worker = worker(331L, 3_301L);
        Manager manager = manager(332L, 3_302L);
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(333L);
        invoice.setAmountKopecks(100_000L);
        invoice.setPaidKopecks(1_000L);

        ContractorPaymentAllocation allocation = service.reserveForCommonInvoice(
                invoice,
                List.of(order(334L, worker, manager)),
                manager,
                99_000L
        );

        assertEquals(ContractorRecipientType.OWNER, allocation.getRecipientType());
        assertEquals(ContractorAllocationStatus.OWNER_FALLBACK, allocation.getStatus());
        assertEquals(
                ContractorRoutingDecisionReason.PRIOR_PAYMENT_EVIDENCE,
                allocation.getRoutingDecisionReason()
        );
        verify(profileRepository, never()).findIdByUserIdAndRole(anyLong(), any());
    }

    @Test
    void terminalPriorCommonPaymentReferenceMakesCapturedRouteOwnerOnly() {
        Worker worker = worker(341L, 3_401L);
        Manager manager = manager(342L, 3_402L);
        Order order = order(343L, worker, manager);
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(344L);
        invoice.setAmountKopecks(100_000L);
        when(commonInvoicePaymentRefRepository.findIdsByInvoiceIdForUpdate(344L))
                .thenReturn(List.of(1L));
        service.prepareCommonInvoiceSource(
                invoice,
                List.of(order),
                manager,
                100_000L,
                LocalDateTime.now()
        );

        ContractorPaymentAllocation allocation = service.reserveForCommonInvoice(
                invoice,
                List.of(order),
                manager,
                100_000L
        );

        assertEquals(false, invoice.isShadowRouteContractorEligible());
        assertEquals(ContractorRecipientType.OWNER, allocation.getRecipientType());
        assertEquals(
                ContractorRoutingDecisionReason.PRIOR_PAYMENT_EVIDENCE,
                allocation.getRoutingDecisionReason()
        );
        verify(profileRepository, never()).findIdByUserIdAndRole(anyLong(), any());
    }

    @Test
    void unpaidOrderReleasesClientReportedReservation() {
        when(appSettingService.getBoolean(AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_ENABLED, true))
                .thenReturn(false);
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(89L);
        allocation.setMode(ContractorAllocationMode.SHADOW);
        allocation.setSourceType(ContractorAllocationSourceType.PAYMENT_LINK);
        allocation.setSourceId(88L);
        allocation.setOrderId(90L);
        allocation.setStatus(ContractorAllocationStatus.CLIENT_REPORTED);
        Order currentOrder = order(90L, null, null);
        OrderStatus unpaid = new OrderStatus();
        unpaid.setTitle("Не оплачено");
        currentOrder.setStatus(unpaid);
        when(orderRepository.findByIdForCounterUpdate(90L)).thenReturn(Optional.of(currentOrder));
        when(allocationRepository.findActiveByOrderId(
                90L,
                ContractorAllocationMode.SHADOW,
                java.util.EnumSet.of(
                        ContractorAllocationStatus.RESERVED,
                        ContractorAllocationStatus.CLIENT_REPORTED,
                        ContractorAllocationStatus.PARTIALLY_CONFIRMED,
                        ContractorAllocationStatus.OWNER_FALLBACK
                )
        )).thenReturn(List.of(allocation));
        when(paymentLinkRepository.findByIdForUpdate(88L)).thenReturn(Optional.of(new PaymentLink()));
        when(allocationRepository.findByIdForUpdate(89L)).thenReturn(Optional.of(allocation));
        when(allocationRepository.findLatestIdForUpdate("SHADOW", "PAYMENT_LINK", 88L))
                .thenReturn(Optional.of(89L));

        int released = service.releaseForFinanciallyClosedOrder(90L, "Не оплачено");

        assertEquals(1, released);
        assertEquals(ContractorAllocationStatus.RELEASED_UNPAID, allocation.getStatus());
        assertEquals("Не оплачено", allocation.getReleaseReason());
    }

    @Test
    void bannedOrderReleasesEntireUnconfirmedReservation() {
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(892L);
        allocation.setMode(ContractorAllocationMode.SHADOW);
        allocation.setSourceType(ContractorAllocationSourceType.PAYMENT_LINK);
        allocation.setSourceId(882L);
        allocation.setOrderId(902L);
        allocation.setAmountKopecks(275_000L);
        allocation.setConfirmedKopecks(0L);
        allocation.setStatus(ContractorAllocationStatus.RESERVED);
        Order currentOrder = order(902L, null, null);
        OrderStatus banned = new OrderStatus();
        banned.setTitle("Бан");
        currentOrder.setStatus(banned);
        when(orderRepository.findByIdForCounterUpdate(902L)).thenReturn(Optional.of(currentOrder));
        when(allocationRepository.findActiveByOrderId(
                902L,
                ContractorAllocationMode.SHADOW,
                java.util.EnumSet.of(
                        ContractorAllocationStatus.RESERVED,
                        ContractorAllocationStatus.CLIENT_REPORTED,
                        ContractorAllocationStatus.PARTIALLY_CONFIRMED,
                        ContractorAllocationStatus.OWNER_FALLBACK
                )
        )).thenReturn(List.of(allocation));
        when(paymentLinkRepository.findByIdForUpdate(882L)).thenReturn(Optional.of(new PaymentLink()));
        when(allocationRepository.findByIdForUpdate(892L)).thenReturn(Optional.of(allocation));
        when(allocationRepository.findLatestIdForUpdate("SHADOW", "PAYMENT_LINK", 882L))
                .thenReturn(Optional.of(892L));

        int released = service.releaseForFinanciallyClosedOrder(902L, "Бан");

        assertEquals(1, released);
        assertEquals(ContractorAllocationStatus.RELEASED_UNPAID, allocation.getStatus());
        assertEquals("Бан", allocation.getReleaseReason());
        ArgumentCaptor<ContractorPaymentAllocationEvent> event =
                ArgumentCaptor.forClass(ContractorPaymentAllocationEvent.class);
        verify(eventRepository).save(event.capture());
        assertEquals(ContractorAllocationEventType.RELEASED, event.getValue().getEventType());
        assertEquals(275_000L, event.getValue().getAmountKopecks());
    }

    @Test
    void delayedUnpaidOrderCallbackDoesNothingAfterStatusWasRestored() {
        Order restored = order(901L, null, null);
        OrderStatus inWork = new OrderStatus();
        inWork.setTitle("В работе");
        restored.setStatus(inWork);
        when(orderRepository.findByIdForCounterUpdate(901L)).thenReturn(Optional.of(restored));

        int released = service.releaseForFinanciallyClosedOrder(901L, "stale callback");

        assertEquals(0, released);
        verify(allocationRepository, never()).findActiveByOrderId(
                eq(901L),
                any(ContractorAllocationMode.class),
                anyCollection()
        );
    }

    @Test
    void releasedSourceCanBeReservedAgainAsNextAttempt() {
        Worker worker = worker(15L, 105L);
        Order order = order(35L, worker, null);
        PaymentLink link = paymentLink(45L, order, 90_000L);
        ContractorPaymentAllocation released = new ContractorPaymentAllocation();
        released.setId(5L);
        released.setAttemptNo(1);
        released.setStatus(ContractorAllocationStatus.RELEASED_UNPAID);
        ContractorPaymentProfile specialist = profile(6L, worker.getUser(), ContractorRole.SPECIALIST);
        when(allocationRepository.findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(
                ContractorAllocationMode.SHADOW,
                ContractorAllocationSourceType.PAYMENT_LINK,
                45L
        )).thenReturn(Optional.of(released));
        when(profileRepository.findByUserIdAndRoleForUpdate(105L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(specialist));
        when(profileService.available(specialist, ContractorAllocationMode.SHADOW)).thenReturn(90_000L);

        ContractorPaymentAllocation retried = service.reserveForPaymentLink(link);

        assertEquals(2, retried.getAttemptNo());
        assertEquals(ContractorAllocationStatus.RESERVED, retried.getStatus());
    }

    @Test
    void commonInvoiceConfirmationSubtractsFrozenPrepaymentBaseline() {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(51L);
        invoice.setAmountKopecks(700_000L);
        invoice.setPaidKopecks(200_000L);
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        ContractorPaymentAllocation allocation = service.reserveForCommonInvoice(
                invoice, List.of(), null, 500_000L
        );
        allocation.setId(51L);
        invoice.setPaidKopecks(300_000L);
        when(allocationRepository.findCommonInvoicesForReconciliation(
                eq(ContractorAllocationMode.SHADOW),
                anyCollection(),
                anyCollection(),
                any(),
                any(),
                any()
        )).thenReturn(List.of(allocation));
        when(allocationRepository.findByIdForUpdate(51L)).thenReturn(Optional.of(allocation));
        when(commonInvoiceRepository.findById(51L)).thenReturn(Optional.of(invoice));
        when(commonInvoiceRepository.findByIdForUpdate(51L)).thenReturn(Optional.of(invoice));
        registerReconciliation(allocation);

        service.reconcilePaymentLinks();

        assertEquals(200_000L, allocation.getSourcePaidBaselineKopecks());
        assertEquals(100_000L, allocation.getConfirmedKopecks());
        assertEquals(ContractorAllocationStatus.PARTIALLY_CONFIRMED, allocation.getStatus());
    }

    @Test
    void reliableCommonInvoiceOverpaymentIsNotSilentlyCappedAtRouteAmount() {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(52L);
        invoice.setPaidKopecks(150_000L);
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(53L);
        allocation.setMode(ContractorAllocationMode.SHADOW);
        allocation.setSourceType(ContractorAllocationSourceType.COMMON_INVOICE);
        allocation.setSourceId(52L);
        allocation.setAmountKopecks(100_000L);
        allocation.setStatus(ContractorAllocationStatus.RESERVED);
        when(allocationRepository.findCommonInvoicesForReconciliation(
                eq(ContractorAllocationMode.SHADOW), anyCollection(), anyCollection(), any(), any(), any()
        )).thenReturn(List.of(allocation));
        when(allocationRepository.findByIdForUpdate(53L)).thenReturn(Optional.of(allocation));
        when(commonInvoiceRepository.findById(52L)).thenReturn(Optional.of(invoice));
        when(commonInvoiceRepository.findByIdForUpdate(52L)).thenReturn(Optional.of(invoice));
        registerReconciliation(allocation);

        service.reconcilePaymentLinks();

        assertEquals(150_000L, allocation.getConfirmedKopecks());
        assertEquals(ContractorAllocationStatus.SIMULATED_PAID, allocation.getStatus());
    }

    @Test
    void finalActualRecipientPreventsPeriodicReconcileFromConfirmingOriginalRecipientAgain() {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(5_200L);
        invoice.setPaidKopecks(150_000L);
        invoice.setStatus(CommonInvoiceStatus.PAID);
        ContractorPaymentAllocation original = new ContractorPaymentAllocation();
        original.setId(5_201L);
        original.setMode(ContractorAllocationMode.SHADOW);
        original.setSourceType(ContractorAllocationSourceType.COMMON_INVOICE);
        original.setSourceId(5_200L);
        original.setAmountKopecks(150_000L);
        original.setStatus(ContractorAllocationStatus.RESERVED);
        when(allocationRepository.findCommonInvoicesForReconciliation(
                eq(ContractorAllocationMode.SHADOW), anyCollection(), anyCollection(), any(), any(), any()
        )).thenReturn(List.of(original));
        when(allocationRepository.findByIdForUpdate(5_201L)).thenReturn(Optional.of(original));
        when(commonInvoiceRepository.findById(5_200L)).thenReturn(Optional.of(invoice));
        when(commonInvoiceRepository.findByIdForUpdate(5_200L)).thenReturn(Optional.of(invoice));
        when(actualPaymentAttributionRepository.existsBySourceKindAndSourceIdAndEvidenceId(
                ContractorActualPaymentSourceKind.COMMON_INVOICE,
                5_200L,
                null
        )).thenReturn(true);
        registerReconciliation(original);

        service.reconcilePaymentLinks();

        assertEquals(0L, original.getConfirmedKopecks());
        assertEquals(ContractorAllocationStatus.RESERVED, original.getStatus());
    }

    @Test
    void claimedCommonAllocationRepairsMissedReleaseWhenMemberOrderIsUnpaid() {
        Order member = order(54L, null, null);
        OrderStatus unpaid = new OrderStatus();
        unpaid.setTitle("Не оплачено");
        member.setStatus(unpaid);
        CommonInvoiceOrder item = new CommonInvoiceOrder();
        item.setOrder(member);
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(55L);
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(56L);
        allocation.setMode(ContractorAllocationMode.LIVE);
        allocation.setSourceType(ContractorAllocationSourceType.COMMON_INVOICE);
        allocation.setSourceId(55L);
        allocation.setAmountKopecks(75_000L);
        allocation.setStatus(ContractorAllocationStatus.RESERVED);
        when(allocationRepository.findById(56L)).thenReturn(Optional.of(allocation));
        when(commonInvoiceRepository.findByIdForUpdate(55L)).thenReturn(Optional.of(invoice));
        when(allocationRepository.findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(
                ContractorAllocationMode.LIVE,
                ContractorAllocationSourceType.COMMON_INVOICE,
                55L
        )).thenReturn(Optional.of(allocation));
        when(allocationRepository.findByIdForUpdate(56L)).thenReturn(Optional.of(allocation));
        when(allocationRepository.findLatestIdForUpdate("LIVE", "COMMON_INVOICE", 55L))
                .thenReturn(Optional.of(56L));
        when(commonInvoiceOrderRepository.findOrderIdsByInvoiceId(55L)).thenReturn(List.of(54L));
        when(orderRepository.findByIdForCounterUpdate(54L)).thenReturn(Optional.of(member));
        when(commonInvoiceOrderRepository.findMembershipByInvoiceIdForRead(55L)).thenReturn(List.of(item));
        when(commonInvoiceOrderRepository.findByInvoiceIdWithOrders(55L)).thenReturn(List.of(item));

        service.reconcileAllocationId(56L);

        assertEquals(ContractorAllocationStatus.RELEASED_UNPAID, allocation.getStatus());
        InOrder order = inOrder(commonInvoiceRepository, allocationRepository, eventRepository);
        order.verify(commonInvoiceRepository).findByIdForUpdate(55L);
        order.verify(allocationRepository).findByIdForUpdate(56L);
        order.verify(eventRepository).save(any());
    }

    @Test
    void claimedCommonAllocationRepairsMissedReleaseWhenMemberOrderIsBanned() {
        Order member = order(57L, null, null);
        OrderStatus banned = new OrderStatus();
        banned.setTitle("Бан");
        member.setStatus(banned);
        CommonInvoiceOrder item = new CommonInvoiceOrder();
        item.setOrder(member);
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(58L);
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(59L);
        allocation.setMode(ContractorAllocationMode.LIVE);
        allocation.setSourceType(ContractorAllocationSourceType.COMMON_INVOICE);
        allocation.setSourceId(58L);
        allocation.setAmountKopecks(75_000L);
        allocation.setStatus(ContractorAllocationStatus.RESERVED);
        when(allocationRepository.findById(59L)).thenReturn(Optional.of(allocation));
        when(commonInvoiceRepository.findByIdForUpdate(58L)).thenReturn(Optional.of(invoice));
        when(allocationRepository.findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(
                ContractorAllocationMode.LIVE,
                ContractorAllocationSourceType.COMMON_INVOICE,
                58L
        )).thenReturn(Optional.of(allocation));
        when(allocationRepository.findByIdForUpdate(59L)).thenReturn(Optional.of(allocation));
        when(allocationRepository.findLatestIdForUpdate("LIVE", "COMMON_INVOICE", 58L))
                .thenReturn(Optional.of(59L));
        when(commonInvoiceOrderRepository.findOrderIdsByInvoiceId(58L)).thenReturn(List.of(57L));
        when(orderRepository.findByIdForCounterUpdate(57L)).thenReturn(Optional.of(member));
        when(commonInvoiceOrderRepository.findMembershipByInvoiceIdForRead(58L)).thenReturn(List.of(item));
        when(commonInvoiceOrderRepository.findByInvoiceIdWithOrders(58L)).thenReturn(List.of(item));

        service.reconcileAllocationId(59L);

        assertEquals(ContractorAllocationStatus.RELEASED_UNPAID, allocation.getStatus());
        ArgumentCaptor<ContractorPaymentAllocationEvent> event =
                ArgumentCaptor.forClass(ContractorPaymentAllocationEvent.class);
        verify(eventRepository).save(event.capture());
        assertEquals("COMMON:ORDER_BANNED", event.getValue().getExternalRef());
    }

    @Test
    void reconciliationNeverMutatesSupersededAttemptFromStalePersistenceSnapshot() {
        ContractorPaymentProfile recipient = profile(211L, user(311L), ContractorRole.SPECIALIST);
        ContractorPaymentAllocation staleAttempt = new ContractorPaymentAllocation();
        staleAttempt.setId(212L);
        staleAttempt.setMode(ContractorAllocationMode.LIVE);
        staleAttempt.setSourceType(ContractorAllocationSourceType.PAYMENT_LINK);
        staleAttempt.setSourceId(213L);
        staleAttempt.setAttemptNo(1);
        staleAttempt.setRecipientProfile(recipient);
        staleAttempt.setAmountKopecks(50_000L);
        staleAttempt.setStatus(ContractorAllocationStatus.RESERVED);
        PaymentLink source = new PaymentLink();
        source.setId(213L);
        when(allocationRepository.findById(212L)).thenReturn(Optional.of(staleAttempt));
        when(paymentLinkRepository.findByIdForUpdate(213L)).thenReturn(Optional.of(source));
        when(paymentLinkRepository.findByIdWithOrder(213L)).thenReturn(Optional.of(source));
        when(profileRepository.findByIdForUpdate(211L)).thenReturn(Optional.of(recipient));
        when(allocationRepository.findByIdForUpdate(212L)).thenReturn(Optional.of(staleAttempt));
        when(allocationRepository.findLatestIdForUpdate("LIVE", "PAYMENT_LINK", 213L))
                .thenReturn(Optional.of(214L));

        ContractorPaymentAllocation reconciled = service.reconcileAllocationId(212L);

        assertNull(reconciled);
        assertEquals(ContractorAllocationStatus.RESERVED, staleAttempt.getStatus());
        verify(allocationRepository, never()).save(staleAttempt);
        verify(eventRepository, never()).save(any());
    }

    @Test
    void confirmedShadowAllocationRemainsReconciledForFullReturnAfterToggleOff() {
        when(appSettingService.getBoolean(AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_ENABLED, true))
                .thenReturn(false);
        LocalDateTime paidAt = LocalDateTime.of(2026, 8, 7, 10, 0);
        PaymentLink link = paymentLink(61L, order(36L, null, null), 120_000L);
        link.setStatus(PaymentLinkStatus.REFUNDED);
        link.setPaidAt(paidAt);
        link.setConfirmedAmountKopecks(120_000L);
        link.setUpdatedAt(paidAt.plusHours(2));
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(62L);
        allocation.setMode(ContractorAllocationMode.SHADOW);
        allocation.setSourceType(ContractorAllocationSourceType.PAYMENT_LINK);
        allocation.setSourceId(61L);
        allocation.setAmountKopecks(120_000L);
        allocation.setConfirmedKopecks(120_000L);
        allocation.setConfirmedAt(paidAt);
        allocation.setStatus(ContractorAllocationStatus.CONFIRMED);
        when(allocationRepository.findPaymentLinksForReconciliation(
                eq(ContractorAllocationMode.SHADOW),
                anyCollection(),
                anyCollection(),
                any(),
                any(),
                any()
        )).thenReturn(List.of(allocation));
        when(allocationRepository.findByIdForUpdate(62L)).thenReturn(Optional.of(allocation));
        when(paymentLinkRepository.findByIdWithOrder(61L)).thenReturn(Optional.of(link));
        ManualPaymentTaskContractorReturnBridge.Binding taskReturnBinding =
                mock(ManualPaymentTaskContractorReturnBridge.Binding.class);
        when(taskReturnBridge.lockPaymentLinkBinding(allocation, link)).thenReturn(taskReturnBinding);
        registerReconciliation(allocation);

        service.reconcilePaymentLinks();

        assertEquals(ContractorAllocationStatus.RETURNED, allocation.getStatus());
        assertEquals(120_000L, allocation.getReturnedKopecks());
        assertEquals(paidAt, allocation.getConfirmedAt());
        assertEquals(LocalDateTime.of(2026, 8, 7, 12, 0), allocation.getLastReconciledAt());
        verify(taskReturnBridge).recordReturn(taskReturnBinding, allocation);
    }

    @Test
    void fullProviderRefundReturnsOnlyReallocatedActualRecipientAndReplayIsNoOp() {
        LocalDateTime paidAt = LocalDateTime.of(2026, 8, 7, 10, 0);
        Order order = order(60_001L, null, null);
        PaymentLink link = paymentLink(60_002L, order, 100_000L);
        link.setStatus(PaymentLinkStatus.REFUNDED);
        link.setPaidAt(paidAt);
        link.setUpdatedAt(paidAt.plusHours(1));
        link.setConfirmedAmountKopecks(100_000L);

        ContractorPaymentProfile originalProfile = profile(
                60_003L, user(60_004L), ContractorRole.SPECIALIST);
        ContractorPaymentProfile actualProfile = profile(
                60_005L, user(60_006L), ContractorRole.MANAGER);
        ContractorPaymentAllocation original = new ContractorPaymentAllocation();
        original.setId(60_007L);
        original.setMode(ContractorAllocationMode.LIVE);
        original.setSourceType(ContractorAllocationSourceType.PAYMENT_LINK);
        original.setSourceId(link.getId());
        original.setOrderId(order.getId());
        original.setAttemptNo(1);
        original.setRecipientType(ContractorRecipientType.SPECIALIST);
        original.setRecipientProfile(originalProfile);
        original.setAmountKopecks(100_000L);
        original.setStatus(ContractorAllocationStatus.CANCELED);

        ContractorPaymentAllocation actual = new ContractorPaymentAllocation();
        actual.setId(60_008L);
        actual.setMode(ContractorAllocationMode.LIVE);
        actual.setSourceType(ContractorAllocationSourceType.ACTUAL_PAYMENT);
        actual.setSourceId(60_009L);
        actual.setOrderId(order.getId());
        actual.setAttemptNo(1);
        actual.setRecipientType(ContractorRecipientType.MANAGER);
        actual.setRecipientProfile(actualProfile);
        actual.setAmountKopecks(100_000L);
        actual.setConfirmedKopecks(100_000L);
        actual.setConfirmedAt(paidAt);
        actual.setStatus(ContractorAllocationStatus.CONFIRMED);

        ContractorActualPaymentAttribution row = paymentLinkAttribution(
                60_009L, link, original, actualProfile, ContractorRecipientType.MANAGER,
                ContractorCashDestinationKind.CONTRACTOR_PROFILE, null, 100_000L
        );
        when(actualPaymentAttributionRepository
                .findAllBySourceKindAndSourceIdOrderByEffectiveAtAscIdAsc(
                        ContractorActualPaymentSourceKind.PAYMENT_LINK, link.getId()))
                .thenReturn(List.of(row));
        when(allocationRepository.findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(
                ContractorAllocationMode.LIVE, ContractorAllocationSourceType.PAYMENT_LINK, link.getId()))
                .thenReturn(Optional.of(original));
        when(allocationRepository.findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(
                ContractorAllocationMode.LIVE, ContractorAllocationSourceType.ACTUAL_PAYMENT, row.getId()))
                .thenReturn(Optional.of(actual));
        when(profileRepository.findByIdForUpdate(originalProfile.getId())).thenReturn(Optional.of(originalProfile));
        when(profileRepository.findByIdForUpdate(actualProfile.getId())).thenReturn(Optional.of(actualProfile));
        registerReconciliation(original);
        registerReconciliation(actual);

        assertEquals(1, service.reconcilePaymentLinkId(link.getId()));
        assertEquals(0, service.reconcilePaymentLinkId(link.getId()));

        assertEquals(ContractorAllocationStatus.CANCELED, original.getStatus());
        assertEquals(0L, original.getConfirmedKopecks());
        assertEquals(0L, original.getReturnedKopecks());
        assertEquals(ContractorAllocationStatus.RETURNED, actual.getStatus());
        assertEquals(100_000L, actual.getReturnedKopecks());
        verify(taskReturnBridge, never()).lockPaymentLinkBinding(original, link);
        verify(taskReturnBridge, times(2)).recordAuthoritativePaymentLinkReturn(link);
    }

    @Test
    void taskRedirectedProfilePartialRefundMarksOnlyActualAllocationPendingViaDurableRetry() {
        LocalDateTime paidAt = LocalDateTime.of(2026, 8, 7, 10, 0);
        Order order = order(61_001L, null, null);
        PaymentLink link = paymentLink(61_002L, order, 75_000L);
        link.setStatus(PaymentLinkStatus.PARTIAL_REVERSED);
        link.setPaidAt(paidAt);
        link.setUpdatedAt(paidAt.plusHours(1));
        link.setConfirmedAmountKopecks(75_000L);

        ContractorPaymentProfile originalProfile = profile(
                61_003L, user(61_004L), ContractorRole.SPECIALIST);
        ContractorPaymentProfile redirectedProfile = profile(
                61_005L, user(61_006L), ContractorRole.MANAGER);
        ContractorPaymentAllocation original = new ContractorPaymentAllocation();
        original.setId(61_007L);
        original.setMode(ContractorAllocationMode.SHADOW);
        original.setSourceType(ContractorAllocationSourceType.PAYMENT_LINK);
        original.setSourceId(link.getId());
        original.setOrderId(order.getId());
        original.setAttemptNo(1);
        original.setRecipientType(ContractorRecipientType.SPECIALIST);
        original.setRecipientProfile(originalProfile);
        original.setAmountKopecks(75_000L);
        original.setStatus(ContractorAllocationStatus.CANCELED);

        ContractorPaymentAllocation actual = new ContractorPaymentAllocation();
        actual.setId(61_008L);
        actual.setMode(ContractorAllocationMode.SHADOW);
        actual.setSourceType(ContractorAllocationSourceType.ACTUAL_PAYMENT);
        actual.setSourceId(61_009L);
        actual.setOrderId(order.getId());
        actual.setAttemptNo(1);
        actual.setRecipientType(ContractorRecipientType.MANAGER);
        actual.setRecipientProfile(redirectedProfile);
        actual.setAmountKopecks(75_000L);
        actual.setConfirmedKopecks(75_000L);
        actual.setConfirmedAt(paidAt);
        actual.setStatus(ContractorAllocationStatus.CONFIRMED);

        ContractorActualPaymentAttribution row = paymentLinkAttribution(
                61_009L, link, original, redirectedProfile, ContractorRecipientType.MANAGER,
                ContractorCashDestinationKind.CONTRACTOR_PROFILE, null, 75_000L
        );
        when(actualPaymentAttributionRepository.findById(row.getId())).thenReturn(Optional.of(row));
        when(actualPaymentAttributionRepository
                .findAllBySourceKindAndSourceIdOrderByEffectiveAtAscIdAsc(
                        ContractorActualPaymentSourceKind.PAYMENT_LINK, link.getId()))
                .thenReturn(List.of(row));
        when(allocationRepository.findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(
                ContractorAllocationMode.SHADOW, ContractorAllocationSourceType.PAYMENT_LINK, link.getId()))
                .thenReturn(Optional.of(original));
        when(allocationRepository.findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(
                ContractorAllocationMode.SHADOW, ContractorAllocationSourceType.ACTUAL_PAYMENT, row.getId()))
                .thenReturn(Optional.of(actual));
        when(profileRepository.findByIdForUpdate(originalProfile.getId())).thenReturn(Optional.of(originalProfile));
        when(profileRepository.findByIdForUpdate(redirectedProfile.getId())).thenReturn(Optional.of(redirectedProfile));
        registerReconciliation(original);
        registerReconciliation(actual);

        ContractorPaymentAllocation reconciled = service.reconcileAllocationId(actual.getId());

        assertEquals(actual, reconciled);
        assertEquals(ContractorAllocationStatus.RETURN_AMOUNT_PENDING, actual.getStatus());
        assertEquals(0L, actual.getReturnedKopecks());
        assertEquals(ContractorAllocationStatus.CANCELED, original.getStatus());
        assertEquals(0L, original.getReturnedKopecks());
        verify(taskReturnBridge).recordAuthoritativePaymentLinkReturn(link);
    }

    @Test
    void commonInvoicePaidDecreaseSynchronizesManualTaskReturn() {
        when(appSettingService.getBoolean(AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_ENABLED, true))
                .thenReturn(false);
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(63L);
        invoice.setAmountKopecks(100_000L);
        invoice.setPaidKopecks(60_000L);
        invoice.setStatus(CommonInvoiceStatus.COLLECTING);
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(64L);
        allocation.setMode(ContractorAllocationMode.SHADOW);
        allocation.setSourceType(ContractorAllocationSourceType.COMMON_INVOICE);
        allocation.setSourceId(63L);
        allocation.setCommonInvoiceId(63L);
        allocation.setAmountKopecks(100_000L);
        allocation.setConfirmedKopecks(100_000L);
        allocation.setStatus(ContractorAllocationStatus.CONFIRMED);
        when(allocationRepository.findCommonInvoicesForReconciliation(
                eq(ContractorAllocationMode.SHADOW),
                anyCollection(),
                anyCollection(),
                any(),
                any(),
                any()
        )).thenReturn(List.of(allocation));
        when(commonInvoiceRepository.findByIdForUpdate(63L)).thenReturn(Optional.of(invoice));
        ManualPaymentTaskContractorReturnBridge.Binding taskReturnBinding =
                mock(ManualPaymentTaskContractorReturnBridge.Binding.class);
        when(taskReturnBridge.lockCommonInvoiceBinding(allocation, invoice))
                .thenReturn(taskReturnBinding);
        registerReconciliation(allocation);

        service.reconcilePaymentLinks();

        assertEquals(40_000L, allocation.getReturnedKopecks());
        assertEquals(ContractorAllocationStatus.PARTIALLY_RETURNED, allocation.getStatus());
        verify(taskReturnBridge).recordReturn(taskReturnBinding, allocation);
    }

    @Test
    void partialProviderReturnWithoutAmountDoesNotReturnWholeAllocation() {
        when(appSettingService.getBoolean(AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_ENABLED, true))
                .thenReturn(false);
        PaymentLink link = paymentLink(71L, order(37L, null, null), 80_000L);
        link.setStatus(PaymentLinkStatus.PARTIAL_REFUNDED);
        link.setConfirmedAmountKopecks(80_000L);
        link.setPaidAt(LocalDateTime.of(2026, 8, 7, 11, 0));
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(72L);
        allocation.setMode(ContractorAllocationMode.LIVE);
        allocation.setSourceType(ContractorAllocationSourceType.PAYMENT_LINK);
        allocation.setSourceId(71L);
        allocation.setAmountKopecks(80_000L);
        allocation.setConfirmedKopecks(80_000L);
        allocation.setStatus(ContractorAllocationStatus.CONFIRMED);
        when(allocationRepository.findPaymentLinksForReconciliation(
                eq(ContractorAllocationMode.LIVE),
                anyCollection(),
                anyCollection(),
                any(),
                any(),
                any()
        )).thenReturn(List.of(allocation));
        when(allocationRepository.findByIdForUpdate(72L)).thenReturn(Optional.of(allocation));
        when(paymentLinkRepository.findByIdWithOrder(71L)).thenReturn(Optional.of(link));
        registerReconciliation(allocation);

        service.reconcilePaymentLinks();

        assertEquals(ContractorAllocationStatus.RETURN_AMOUNT_PENDING, allocation.getStatus());
        assertEquals(0L, allocation.getReturnedKopecks());
    }

    @Test
    void repeatedPartialReturnObservationReopensPendingAndRecordsOnlyNewDelta() {
        LocalDateTime observedAt = LocalDateTime.of(2026, 8, 7, 12, 0);
        PaymentLink link = paymentLink(711L, order(371L, null, null), 100_000L);
        link.setStatus(PaymentLinkStatus.PARTIAL_REFUNDED);
        link.setConfirmedAmountKopecks(100_000L);
        link.setPaidAt(observedAt.minusHours(1));
        link.setRowVersion(1L);
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(712L);
        allocation.setMode(ContractorAllocationMode.LIVE);
        allocation.setSourceType(ContractorAllocationSourceType.PAYMENT_LINK);
        allocation.setSourceId(711L);
        allocation.setAmountKopecks(100_000L);
        allocation.setConfirmedKopecks(100_000L);
        allocation.setStatus(ContractorAllocationStatus.CONFIRMED);

        ReflectionTestUtils.invokeMethod(service, "applyLinkStatus", allocation, link, observedAt);
        accountingService.recordReturnTotal(
                allocation, 20_000L, observedAt, "Первая сверка", "MANUAL_RETURN_TOTAL:20000"
        );
        assertEquals(ContractorAllocationStatus.PARTIALLY_RETURNED, allocation.getStatus());

        link.setRowVersion(2L);
        ReflectionTestUtils.invokeMethod(service, "applyLinkStatus", allocation, link, observedAt.plusMinutes(5));
        assertEquals(ContractorAllocationStatus.RETURN_AMOUNT_PENDING, allocation.getStatus());
        accountingService.recordReturnTotal(
                allocation, 35_000L, observedAt.plusMinutes(5), "Вторая сверка", "MANUAL_RETURN_TOTAL:35000"
        );

        assertEquals(35_000L, allocation.getReturnedKopecks());
        assertEquals(ContractorAllocationStatus.PARTIALLY_RETURNED, allocation.getStatus());
        ArgumentCaptor<ContractorPaymentAllocationEvent> events =
                ArgumentCaptor.forClass(ContractorPaymentAllocationEvent.class);
        verify(eventRepository, org.mockito.Mockito.times(4)).save(events.capture());
        assertEquals(
                List.of(20_000L, 15_000L),
                events.getAllValues().stream()
                        .filter(event -> event.getEventType() == ContractorAllocationEventType.RETURNED)
                        .map(ContractorPaymentAllocationEvent::getAmountKopecks)
                        .toList()
        );
        assertEquals(
                List.of(
                        "LINK:RETURN_AMOUNT_PENDING:PARTIAL_REFUNDED:V:1",
                        "LINK:RETURN_AMOUNT_PENDING:PARTIAL_REFUNDED:V:2"
                ),
                events.getAllValues().stream()
                        .filter(event -> event.getEventType() == ContractorAllocationEventType.RETURN_AMOUNT_PENDING)
                        .map(ContractorPaymentAllocationEvent::getExternalRef)
                        .toList()
        );
    }

    @Test
    void schedulerRepairsMissedUnpaidReleaseFromDurableOrderStatus() {
        when(appSettingService.getBoolean(AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_ENABLED, true))
                .thenReturn(false);
        Order order = order(81L, null, null);
        OrderStatus unpaid = new OrderStatus();
        unpaid.setTitle("Не оплачено");
        order.setStatus(unpaid);
        PaymentLink link = paymentLink(82L, order, 40_000L);
        link.setStatus(PaymentLinkStatus.CREATED);
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(83L);
        allocation.setMode(ContractorAllocationMode.SHADOW);
        allocation.setSourceType(ContractorAllocationSourceType.PAYMENT_LINK);
        allocation.setSourceId(82L);
        allocation.setAmountKopecks(40_000L);
        allocation.setStatus(ContractorAllocationStatus.RESERVED);
        when(allocationRepository.findPaymentLinksForReconciliation(
                eq(ContractorAllocationMode.SHADOW),
                anyCollection(),
                anyCollection(),
                any(),
                any(),
                any()
        )).thenReturn(List.of(allocation));
        when(allocationRepository.findByIdForUpdate(83L)).thenReturn(Optional.of(allocation));
        when(paymentLinkRepository.findByIdWithOrder(82L)).thenReturn(Optional.of(link));
        registerReconciliation(allocation);

        service.reconcilePaymentLinks();

        assertEquals(ContractorAllocationStatus.RELEASED_UNPAID, allocation.getStatus());
        assertEquals("Заказ находится в статусе «Не оплачено»", allocation.getReleaseReason());
    }

    @Test
    void schedulerKeepsBadReviewPaymentCycleReservedAfterUnpaidTransition() {
        LocalDateTime unpaidAt = LocalDateTime.of(2026, 8, 7, 12, 0);
        Order order = order(811L, null, null);
        OrderStatus unpaid = new OrderStatus();
        unpaid.setTitle("Не оплачено");
        order.setStatus(unpaid);
        order.setStatusChangedAt(unpaidAt);
        PaymentLink link = paymentLink(812L, order, 40_000L);
        link.setStatus(PaymentLinkStatus.CREATED);
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(813L);
        allocation.setMode(ContractorAllocationMode.SHADOW);
        allocation.setSourceType(ContractorAllocationSourceType.PAYMENT_LINK);
        allocation.setSourceId(812L);
        allocation.setAmountKopecks(40_000L);
        allocation.setStatus(ContractorAllocationStatus.RESERVED);
        allocation.setReservedAt(unpaidAt.plusSeconds(1));
        when(allocationRepository.findPaymentLinksForReconciliation(
                eq(ContractorAllocationMode.SHADOW),
                anyCollection(),
                anyCollection(),
                any(),
                any(),
                any()
        )).thenReturn(List.of(allocation));
        when(allocationRepository.findByIdForUpdate(813L)).thenReturn(Optional.of(allocation));
        when(paymentLinkRepository.findByIdWithOrder(812L)).thenReturn(Optional.of(link));
        registerReconciliation(allocation);

        service.reconcilePaymentLinks();

        assertEquals(ContractorAllocationStatus.RESERVED, allocation.getStatus());
        assertEquals(null, allocation.getReleasedAt());
    }

    @Test
    void unpaidTransitionOnlyOwnsPaymentAttemptsReservedBeforeIt() {
        LocalDateTime unpaidAt = LocalDateTime.of(2026, 8, 7, 12, 0);
        Order order = order(814L, null, null);
        OrderStatus unpaid = new OrderStatus();
        unpaid.setTitle("Не оплачено");
        order.setStatus(unpaid);
        order.setStatusChangedAt(unpaidAt);
        ContractorPaymentAllocation originalAttempt = new ContractorPaymentAllocation();
        originalAttempt.setReservedAt(unpaidAt.minusSeconds(1));
        ContractorPaymentAllocation badReviewAttempt = new ContractorPaymentAllocation();
        badReviewAttempt.setReservedAt(unpaidAt.plusSeconds(1));

        assertEquals(true, ReflectionTestUtils.invokeMethod(
                service, "orderReleasesAllocation", order, originalAttempt
        ));
        assertEquals(false, ReflectionTestUtils.invokeMethod(
                service, "orderReleasesAllocation", order, badReviewAttempt
        ));
    }

    @Test
    void commonSuccessorReleaseUsesMemberPaymentCycleAndBanIsAlwaysTerminal() {
        LocalDateTime unpaidAt = LocalDateTime.of(2026, 8, 7, 12, 0);
        Order unpaidOrder = order(815L, null, null);
        unpaidOrder.setStatus(OrderStatus.builder().title("Не оплачено").build());
        unpaidOrder.setStatusChangedAt(unpaidAt);
        ContractorPaymentAllocation oldAttempt = new ContractorPaymentAllocation();
        oldAttempt.setStatus(ContractorAllocationStatus.RESERVED);
        oldAttempt.setReservedAt(unpaidAt.minusSeconds(1));
        ContractorPaymentAllocation successorAttempt = new ContractorPaymentAllocation();
        successorAttempt.setStatus(ContractorAllocationStatus.RESERVED);
        successorAttempt.setReservedAt(unpaidAt.plusSeconds(1));

        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(
                service, "releaseIfCommonInvoiceContainsUnpaidOrder",
                oldAttempt, List.of(unpaidOrder), unpaidAt.plusMinutes(1)
        ));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(
                service, "releaseIfCommonInvoiceContainsUnpaidOrder",
                successorAttempt, List.of(unpaidOrder), unpaidAt.plusMinutes(1)
        ));

        unpaidOrder.setStatus(OrderStatus.builder().title("Бан").build());
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(
                service, "releaseIfCommonInvoiceContainsUnpaidOrder",
                successorAttempt, List.of(unpaidOrder), unpaidAt.plusMinutes(2)
        ));
    }

    @Test
    void forcedEarlyExpiryUsesObservationTimeInsteadOfFutureDeadline() {
        when(appSettingService.getBoolean(AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_ENABLED, true))
                .thenReturn(false);
        LocalDateTime observedAt = LocalDateTime.of(2026, 8, 7, 12, 0);
        PaymentLink link = paymentLink(84L, order(85L, null, null), 40_000L);
        link.setStatus(PaymentLinkStatus.EXPIRED);
        link.setExpiresAt(observedAt.plusMonths(3));
        link.setUpdatedAt(observedAt.minusMinutes(5));
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(86L);
        allocation.setMode(ContractorAllocationMode.SHADOW);
        allocation.setSourceType(ContractorAllocationSourceType.PAYMENT_LINK);
        allocation.setSourceId(84L);
        allocation.setAmountKopecks(40_000L);
        allocation.setStatus(ContractorAllocationStatus.RESERVED);
        when(allocationRepository.findPaymentLinksForReconciliation(
                eq(ContractorAllocationMode.SHADOW), anyCollection(), anyCollection(), any(), any(), any()
        )).thenReturn(List.of(allocation));
        registerReconciliation(allocation);

        service.reconcilePaymentLinks();

        assertEquals(ContractorAllocationStatus.EXPIRED, allocation.getStatus());
        assertEquals(observedAt, allocation.getReleasedAt());
        ArgumentCaptor<ContractorPaymentAllocationEvent> event =
                ArgumentCaptor.forClass(ContractorPaymentAllocationEvent.class);
        verify(eventRepository).save(event.capture());
        assertEquals(ContractorAllocationEventType.EXPIRED, event.getValue().getEventType());
        assertEquals(observedAt, event.getValue().getEffectiveAt());
    }

    @Test
    void naturalExpiryKeepsOriginalPastDeadline() {
        LocalDateTime observedAt = LocalDateTime.of(2026, 8, 7, 12, 0);
        LocalDateTime expiredAt = observedAt.minusMinutes(1);
        PaymentLink link = paymentLink(87L, order(88L, null, null), 40_000L);
        link.setStatus(PaymentLinkStatus.EXPIRED);
        link.setExpiresAt(expiredAt);
        link.setUpdatedAt(observedAt);
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(89L);
        allocation.setMode(ContractorAllocationMode.SHADOW);
        allocation.setSourceType(ContractorAllocationSourceType.PAYMENT_LINK);
        allocation.setSourceId(87L);
        allocation.setAmountKopecks(40_000L);
        allocation.setStatus(ContractorAllocationStatus.RESERVED);
        when(allocationRepository.findPaymentLinksForReconciliation(
                eq(ContractorAllocationMode.SHADOW), anyCollection(), anyCollection(), any(), any(), any()
        )).thenReturn(List.of(allocation));
        registerReconciliation(allocation);

        service.reconcilePaymentLinks();

        assertEquals(expiredAt, allocation.getReleasedAt());
        ArgumentCaptor<ContractorPaymentAllocationEvent> event =
                ArgumentCaptor.forClass(ContractorPaymentAllocationEvent.class);
        verify(eventRepository).save(event.capture());
        assertEquals(expiredAt, event.getValue().getEffectiveAt());
    }

    @Test
    void amountMismatchUsesFactualConfirmedAmountAndPaidTimestamp() {
        LocalDateTime paidAt = LocalDateTime.of(2026, 8, 7, 15, 30);
        PaymentLink link = paymentLink(91L, order(92L, null, null), 100_000L);
        link.setStatus(PaymentLinkStatus.AMOUNT_MISMATCH);
        link.setConfirmedAmountKopecks(60_000L);
        link.setPaidAt(paidAt);
        link.setLastError(
                "prepaid_waiting_order_completion; contractor_source_confirmation; "
                        + "total=60000; confirmed_by=owner; reason=Проверена выписка"
        );
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(93L);
        allocation.setMode(ContractorAllocationMode.SHADOW);
        allocation.setSourceType(ContractorAllocationSourceType.PAYMENT_LINK);
        allocation.setSourceId(91L);
        allocation.setAmountKopecks(100_000L);
        allocation.setStatus(ContractorAllocationStatus.RESERVED);
        when(allocationRepository.findPaymentLinksForReconciliation(
                eq(ContractorAllocationMode.SHADOW),
                anyCollection(),
                anyCollection(),
                any(),
                any(),
                any()
        )).thenReturn(List.of(allocation));
        when(allocationRepository.findByIdForUpdate(93L)).thenReturn(Optional.of(allocation));
        when(paymentLinkRepository.findByIdWithOrder(91L)).thenReturn(Optional.of(link));
        registerReconciliation(allocation);

        service.reconcilePaymentLinks();

        assertEquals(60_000L, allocation.getConfirmedKopecks());
        assertEquals(ContractorAllocationStatus.PARTIALLY_CONFIRMED, allocation.getStatus());
        assertEquals(paidAt, allocation.getConfirmedAt());
        ArgumentCaptor<ContractorPaymentAllocationEvent> event =
                ArgumentCaptor.forClass(ContractorPaymentAllocationEvent.class);
        verify(eventRepository).save(event.capture());
        assertTrue(event.getValue().getReason().contains("contractor_source_confirmation"));
        assertTrue(event.getValue().getReason().contains("Проверена выписка"));
    }

    @Test
    void amountMismatchWithoutReliableAmountIsQuarantinedWithoutFakingClientReport() {
        PaymentLink link = paymentLink(94L, order(95L, null, null), 100_000L);
        link.setStatus(PaymentLinkStatus.AMOUNT_MISMATCH);
        link.setConfirmedAmountKopecks(null);
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(96L);
        allocation.setMode(ContractorAllocationMode.SHADOW);
        allocation.setSourceType(ContractorAllocationSourceType.PAYMENT_LINK);
        allocation.setSourceId(94L);
        allocation.setAmountKopecks(100_000L);
        allocation.setStatus(ContractorAllocationStatus.RESERVED);
        when(allocationRepository.findPaymentLinksForReconciliation(
                eq(ContractorAllocationMode.SHADOW), anyCollection(), anyCollection(), any(), any(), any()
        )).thenReturn(List.of(allocation));
        when(allocationRepository.findByIdForUpdate(96L)).thenReturn(Optional.of(allocation));
        when(paymentLinkRepository.findByIdWithOrder(94L)).thenReturn(Optional.of(link));
        registerReconciliation(allocation);

        assertThrows(ContractorReconciliationRequiredException.class, service::reconcilePaymentLinks);

        assertEquals(0L, allocation.getConfirmedKopecks());
        assertEquals(ContractorAllocationStatus.RESERVED, allocation.getStatus());
        assertNull(allocation.getClientReportedAt());
    }

    @Test
    void zeroAmountPaymentLinkDoesNotCreateAllocation() {
        PaymentLink link = paymentLink(97L, order(98L, worker(29L, 229L), null), 0L);

        ContractorPaymentAllocation allocation = service.reserveForPaymentLink(link);

        assertNull(allocation);
        verify(allocationRepository, never()).save(any());
    }

    @Test
    void missedUnpaidOwnerFallbackBecomesRetryableNextAttempt() {
        Worker worker = worker(30L, 230L);
        Order order = order(99L, worker, null);
        OrderStatus unpaid = new OrderStatus();
        unpaid.setTitle("Не оплачено");
        order.setStatus(unpaid);
        PaymentLink link = paymentLink(100L, order, 50_000L);
        link.setStatus(PaymentLinkStatus.CREATED);
        ContractorPaymentAllocation owner = new ContractorPaymentAllocation();
        owner.setId(101L);
        owner.setAttemptNo(1);
        owner.setMode(ContractorAllocationMode.SHADOW);
        owner.setSourceType(ContractorAllocationSourceType.PAYMENT_LINK);
        owner.setSourceId(100L);
        owner.setAmountKopecks(50_000L);
        owner.setRecipientType(ContractorRecipientType.OWNER);
        owner.setStatus(ContractorAllocationStatus.OWNER_FALLBACK);
        when(allocationRepository.findPaymentLinksForReconciliation(
                eq(ContractorAllocationMode.SHADOW), anyCollection(), anyCollection(), any(), any(), any()
        )).thenReturn(List.of(owner));
        when(allocationRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(owner));
        when(paymentLinkRepository.findByIdWithOrder(100L)).thenReturn(Optional.of(link));
        registerReconciliation(owner);

        service.reconcilePaymentLinks();
        assertEquals(ContractorAllocationStatus.RELEASED_UNPAID, owner.getStatus());

        ContractorPaymentProfile specialist = profile(21L, worker.getUser(), ContractorRole.SPECIALIST);
        when(allocationRepository.findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(
                ContractorAllocationMode.SHADOW,
                ContractorAllocationSourceType.PAYMENT_LINK,
                100L
        )).thenReturn(Optional.of(owner));
        when(profileRepository.findByUserIdAndRoleForUpdate(230L, ContractorRole.SPECIALIST))
                .thenReturn(Optional.of(specialist));
        when(profileService.available(specialist, ContractorAllocationMode.SHADOW)).thenReturn(50_000L);

        ContractorPaymentAllocation retried = service.reserveForPaymentLink(link);

        assertEquals(2, retried.getAttemptNo());
        assertEquals(ContractorAllocationStatus.RESERVED, retried.getStatus());
    }

    @Test
    void commonBackfillLocksSourceBeforeReadingLatestAttempt() {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(101L);
        invoice.setAmountKopecks(150_000L);
        invoice.setPaymentRouteAmountKopecks(90_000L);
        invoice.setShadowRouteGeneration("common-generation");
        invoice.setShadowRouteAmountKopecks(90_000L);
        invoice.setShadowRouteMembershipHash(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
        invoice.setShadowRouteContractorEligible(true);
        invoice.setShadowRoutePreparedAt(LocalDateTime.now());
        when(commonInvoiceOrderRepository.findOrderIdsByInvoiceId(101L)).thenReturn(List.of());
        when(commonInvoiceRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(invoice));
        when(commonInvoiceOrderRepository.findMembershipByInvoiceIdForRead(101L)).thenReturn(List.of());
        when(allocationRepository.findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(
                ContractorAllocationMode.SHADOW,
                ContractorAllocationSourceType.COMMON_INVOICE,
                101L
        )).thenReturn(Optional.empty());

        ContractorPaymentAllocation allocation = service.reserveForCommonInvoiceId(101L);

        InOrder lockOrder = inOrder(commonInvoiceRepository, allocationRepository);
        lockOrder.verify(commonInvoiceRepository).findByIdForUpdate(101L);
        lockOrder.verify(allocationRepository, org.mockito.Mockito.times(2))
                .findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(
                        ContractorAllocationMode.SHADOW,
                        ContractorAllocationSourceType.COMMON_INVOICE,
                        101L
                );
        assertEquals(90_000L, allocation.getAmountKopecks());
    }

    @Test
    void manualEvidenceStillConfirmsExistingLiveRouteWhenShadowIsDisabled() {
        when(appSettingService.getBoolean(AppSettingService.CONTRACTOR_PAYMENTS_SHADOW_ENABLED, true))
                .thenReturn(false);
        ContractorPaymentProfile profile = profile(20L, user(220L), ContractorRole.SPECIALIST);
        ContractorPaymentAllocation live = new ContractorPaymentAllocation();
        live.setId(102L);
        live.setMode(ContractorAllocationMode.LIVE);
        live.setSourceType(ContractorAllocationSourceType.PAYMENT_LINK);
        live.setSourceId(103L);
        live.setOrderId(105L);
        live.setAttemptNo(1);
        live.setRecipientProfile(profile);
        live.setRecipientType(ContractorRecipientType.SPECIALIST);
        live.setAmountKopecks(75_000L);
        live.setStatus(ContractorAllocationStatus.RESERVED);
        when(allocationRepository.findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(
                ContractorAllocationMode.SHADOW,
                ContractorAllocationSourceType.PAYMENT_LINK,
                103L
        )).thenReturn(Optional.empty());
        when(allocationRepository.findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(
                ContractorAllocationMode.LIVE,
                ContractorAllocationSourceType.PAYMENT_LINK,
                103L
        )).thenReturn(Optional.of(live));
        when(profileRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(profile));
        when(allocationRepository.findByIdForUpdate(102L)).thenReturn(Optional.of(live));
        when(allocationRepository.findLatestIdForUpdate("LIVE", "PAYMENT_LINK", 103L))
                .thenReturn(Optional.of(102L));
        LocalDateTime paidAt = LocalDateTime.of(2026, 8, 7, 18, 0);
        Order evidenceOrder = order(105L, null, null);
        PaymentLink original = paymentLink(103L, evidenceOrder, 75_000L);
        PaymentLink evidence = paymentLink(104L, evidenceOrder, 75_000L);
        evidence.setContractorEvidenceOriginalLinkId(103L);
        evidence.setStatus(PaymentLinkStatus.CONFIRMED);
        evidence.setPaymentMethod(PaymentMethod.MANUAL_MOBILE_BANK);
        evidence.setConfirmedAmountKopecks(75_000L);
        evidence.setPaidAt(paidAt);
        when(paymentLinkRepository.findByIdForUpdate(103L)).thenReturn(Optional.of(original));
        when(paymentLinkRepository.findByIdForUpdate(104L)).thenReturn(Optional.of(evidence));

        boolean changed = service.recordManualCardPaymentEvidence(103L, 104L, paidAt);

        assertEquals(true, changed);
        assertEquals(75_000L, live.getConfirmedKopecks());
        assertEquals(ContractorAllocationStatus.CONFIRMED, live.getStatus());
        assertEquals(paidAt, live.getConfirmedAt());
        InOrder mutexOrder = inOrder(profileRepository, allocationRepository, eventRepository);
        mutexOrder.verify(profileRepository).findByIdForUpdate(20L);
        mutexOrder.verify(allocationRepository).findByIdForUpdate(102L);
        mutexOrder.verify(eventRepository).save(any());
        verify(allocationRepository)
                .findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(
                        ContractorAllocationMode.SHADOW,
                        ContractorAllocationSourceType.PAYMENT_LINK,
                        103L
                );
    }

    @Test
    void unpaidCommonInvoiceClosesOwnerFallbackSoFutureRouteCanBeCheckedAgain() {
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(106L);
        invoice.setStatus(CommonInvoiceStatus.UNPAID);
        invoice.setContractorAllocationId(105L);
        ContractorPaymentAllocation ownerFallback = new ContractorPaymentAllocation();
        ownerFallback.setId(105L);
        ownerFallback.setMode(ContractorAllocationMode.LIVE);
        ownerFallback.setSourceType(ContractorAllocationSourceType.COMMON_INVOICE);
        ownerFallback.setSourceId(106L);
        ownerFallback.setCommonInvoiceId(106L);
        ownerFallback.setAmountKopecks(80_000L);
        ownerFallback.setStatus(ContractorAllocationStatus.OWNER_FALLBACK);
        when(allocationRepository.findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(
                ContractorAllocationMode.SHADOW,
                ContractorAllocationSourceType.COMMON_INVOICE,
                106L
        )).thenReturn(Optional.empty());
        when(allocationRepository.findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(
                ContractorAllocationMode.LIVE,
                ContractorAllocationSourceType.COMMON_INVOICE,
                106L
        )).thenReturn(Optional.of(ownerFallback));
        when(allocationRepository.findByIdForUpdate(105L)).thenReturn(Optional.of(ownerFallback));
        when(allocationRepository.findLatestIdForUpdate("LIVE", "COMMON_INVOICE", 106L))
                .thenReturn(Optional.of(105L));
        when(commonInvoiceRepository.findByIdForUpdate(106L)).thenReturn(Optional.of(invoice));

        int released = service.releaseForUnpaidCommonInvoice(106L, "Не оплачено");

        assertEquals(1, released);
        assertEquals(ContractorAllocationStatus.RELEASED_UNPAID, ownerFallback.getStatus());
        assertEquals("Не оплачено", ownerFallback.getReleaseReason());
    }

    @Test
    void delayedUnpaidCommonCallbackDoesNothingAfterInvoiceWasRestored() {
        CommonInvoice restored = new CommonInvoice();
        restored.setId(1_061L);
        restored.setStatus(CommonInvoiceStatus.COLLECTING);
        when(commonInvoiceRepository.findByIdForUpdate(1_061L)).thenReturn(Optional.of(restored));

        int released = service.releaseForUnpaidCommonInvoice(1_061L, "stale callback");

        assertEquals(0, released);
        verify(allocationRepository, never())
                .findFirstByModeAndSourceTypeAndSourceIdOrderByAttemptNoDescIdDesc(
                        any(), any(), eq(1_061L)
                );
    }

    @Test
    void manualReturnedTotalCannotMoveBackwards() {
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(107L);
        allocation.setMode(ContractorAllocationMode.LIVE);
        allocation.setConfirmedKopecks(100_000L);
        allocation.setReturnedKopecks(40_000L);
        allocation.setSourceType(ContractorAllocationSourceType.PAYMENT_LINK);
        allocation.setSourceId(108L);
        allocation.setStatus(ContractorAllocationStatus.RETURN_AMOUNT_PENDING);
        when(allocationRepository.findById(107L)).thenReturn(Optional.of(allocation));
        when(allocationRepository.findByIdForUpdate(107L)).thenReturn(Optional.of(allocation));
        when(allocationRepository.findLatestIdForUpdate("LIVE", "PAYMENT_LINK", 108L))
                .thenReturn(Optional.of(107L));
        PaymentLink lockedLink = new PaymentLink();
        lockedLink.setId(108L);
        when(paymentLinkRepository.findByIdForUpdate(108L)).thenReturn(Optional.of(lockedLink));
        when(taskReturnBridge.lockPaymentLinkBinding(allocation, lockedLink))
                .thenReturn(ManualPaymentTaskContractorReturnBridge.Binding.none(allocation));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.recordObservedReturnAmount(
                        107L,
                        30_000L,
                        LocalDateTime.now().minusMinutes(1),
                        "MANUAL:RETURN:30000",
                        "incorrect correction"
                )
        );

        assertEquals("Итоговая сумма возврата не может уменьшаться", error.getMessage());
        assertEquals(40_000L, allocation.getReturnedKopecks());
        assertEquals(ContractorAllocationStatus.RETURN_AMOUNT_PENDING, allocation.getStatus());
    }

    @Test
    void manualMonotonicCorrectionCanAdvanceAnAlreadyPartialReturn() {
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(1_070L);
        allocation.setMode(ContractorAllocationMode.LIVE);
        allocation.setConfirmedKopecks(100_000L);
        allocation.setReturnedKopecks(40_000L);
        allocation.setSourceType(ContractorAllocationSourceType.PAYMENT_LINK);
        allocation.setSourceId(1_080L);
        allocation.setStatus(ContractorAllocationStatus.PARTIALLY_RETURNED);
        when(allocationRepository.findById(1_070L)).thenReturn(Optional.of(allocation));
        when(allocationRepository.findByIdForUpdate(1_070L)).thenReturn(Optional.of(allocation));
        when(allocationRepository.findLatestIdForUpdate("LIVE", "PAYMENT_LINK", 1_080L))
                .thenReturn(Optional.of(1_070L));
        PaymentLink lockedLink = new PaymentLink();
        lockedLink.setId(1_080L);
        when(paymentLinkRepository.findByIdForUpdate(1_080L)).thenReturn(Optional.of(lockedLink));
        when(taskReturnBridge.lockPaymentLinkBinding(allocation, lockedLink))
                .thenReturn(ManualPaymentTaskContractorReturnBridge.Binding.none(allocation));

        service.recordObservedReturnAmount(
                1_070L,
                55_000L,
                LocalDateTime.now().minusMinutes(1),
                "MANUAL_RETURN_TOTAL:55000",
                "Дополнительное подтверждение возврата"
        );

        assertEquals(55_000L, allocation.getReturnedKopecks());
        assertEquals(ContractorAllocationStatus.PARTIALLY_RETURNED, allocation.getStatus());
    }

    @Test
    void actualPaymentReturnSynchronizesItsManualTaskBinding() {
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(1_071L);
        allocation.setMode(ContractorAllocationMode.LIVE);
        allocation.setAmountKopecks(100_000L);
        allocation.setConfirmedKopecks(100_000L);
        allocation.setSourceType(ContractorAllocationSourceType.ACTUAL_PAYMENT);
        allocation.setSourceId(501L);
        allocation.setStatus(ContractorAllocationStatus.CONFIRMED);
        ManualPaymentTaskContractorReturnBridge.Binding binding =
                mock(ManualPaymentTaskContractorReturnBridge.Binding.class);
        when(allocationRepository.findById(1_071L)).thenReturn(Optional.of(allocation));
        when(allocationRepository.findByIdForUpdate(1_071L)).thenReturn(Optional.of(allocation));
        when(allocationRepository.findLatestIdForUpdate("LIVE", "ACTUAL_PAYMENT", 501L))
                .thenReturn(Optional.of(1_071L));
        when(taskReturnBridge.lockActualPaymentBinding(allocation)).thenReturn(binding);

        service.recordObservedReturnAmount(
                1_071L,
                25_000L,
                LocalDateTime.now().minusMinutes(1),
                "MANUAL_RETURN_TOTAL:25000",
                "Частичный возврат фактического поступления"
        );

        assertEquals(25_000L, allocation.getReturnedKopecks());
        assertEquals(ContractorAllocationStatus.PARTIALLY_RETURNED, allocation.getStatus());
        verify(taskReturnBridge).recordReturn(binding, allocation);
    }

    @Test
    void confirmedTaskPaymentLinkSupportsPartialFullAndReplayOnReusedAllocation() {
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(1_072L);
        allocation.setMode(ContractorAllocationMode.LIVE);
        allocation.setAmountKopecks(100_000L);
        allocation.setConfirmedKopecks(100_000L);
        allocation.setSourceType(ContractorAllocationSourceType.PAYMENT_LINK);
        allocation.setSourceId(1_082L);
        allocation.setStatus(ContractorAllocationStatus.CONFIRMED);
        PaymentLink link = new PaymentLink();
        link.setId(1_082L);
        ManualPaymentTaskContractorReturnBridge.Binding binding =
                mock(ManualPaymentTaskContractorReturnBridge.Binding.class);
        when(binding.taskBound()).thenReturn(true);
        when(allocationRepository.findById(1_072L)).thenReturn(Optional.of(allocation));
        when(allocationRepository.findByIdForUpdate(1_072L)).thenReturn(Optional.of(allocation));
        when(allocationRepository.findLatestIdForUpdate("LIVE", "PAYMENT_LINK", 1_082L))
                .thenReturn(Optional.of(1_072L));
        when(paymentLinkRepository.findByIdForUpdate(1_082L)).thenReturn(Optional.of(link));
        when(taskReturnBridge.lockPaymentLinkBinding(allocation, link)).thenReturn(binding);

        service.recordObservedReturnAmount(
                1_072L, 40_000L, LocalDateTime.now().minusMinutes(2),
                "TASK_PAYMENT_LINK_RETURN:40000", "Частичный возврат");
        service.recordObservedReturnAmount(
                1_072L, 100_000L, LocalDateTime.now().minusMinutes(1),
                "TASK_PAYMENT_LINK_RETURN:100000", "Полный возврат");
        service.recordObservedReturnAmount(
                1_072L, 100_000L, LocalDateTime.now().minusMinutes(1),
                "TASK_PAYMENT_LINK_RETURN:100000:REPLAY", "Повтор полного возврата");

        assertEquals(100_000L, allocation.getReturnedKopecks());
        assertEquals(ContractorAllocationStatus.RETURNED, allocation.getStatus());
        verify(taskReturnBridge, times(3)).recordReturn(binding, allocation);
    }

    @Test
    void restoredConfirmedTaskCommonInvoiceSupportsPartialFullAndReplayInLockOrder() {
        ContractorPaymentProfile profile = new ContractorPaymentProfile();
        profile.setId(1_090L);
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(1_073L);
        allocation.setMode(ContractorAllocationMode.LIVE);
        allocation.setRecipientProfile(profile);
        allocation.setAmountKopecks(100_000L);
        allocation.setConfirmedKopecks(100_000L);
        allocation.setSourceType(ContractorAllocationSourceType.COMMON_INVOICE);
        allocation.setSourceId(1_083L);
        allocation.setStatus(ContractorAllocationStatus.CONFIRMED);
        CommonInvoice restoredInvoice = new CommonInvoice();
        restoredInvoice.setId(1_083L);
        restoredInvoice.setPaymentRouteManualSource(com.hunt.otziv.payments.model.ManualPaymentSource.MANUAL_TASK);
        restoredInvoice.setPaymentRouteManualTaskId(16L);
        ManualPaymentTaskContractorReturnBridge.Binding binding =
                mock(ManualPaymentTaskContractorReturnBridge.Binding.class);
        when(binding.taskBound()).thenReturn(true);
        when(allocationRepository.findById(1_073L)).thenReturn(Optional.of(allocation));
        when(allocationRepository.findByIdForUpdate(1_073L)).thenReturn(Optional.of(allocation));
        when(allocationRepository.findLatestIdForUpdate("LIVE", "COMMON_INVOICE", 1_083L))
                .thenReturn(Optional.of(1_073L));
        when(profileRepository.findByIdForUpdate(1_090L)).thenReturn(Optional.of(profile));
        when(commonInvoiceRepository.findByIdForUpdate(1_083L)).thenReturn(Optional.of(restoredInvoice));
        when(taskReturnBridge.lockCommonInvoiceBinding(allocation, restoredInvoice)).thenReturn(binding);

        service.recordObservedReturnAmount(
                1_073L, 25_000L, LocalDateTime.now().minusMinutes(2),
                "TASK_COMMON_RETURN:25000", "Частичный возврат");
        service.recordObservedReturnAmount(
                1_073L, 100_000L, LocalDateTime.now().minusMinutes(1),
                "TASK_COMMON_RETURN:100000", "Полный возврат");
        service.recordObservedReturnAmount(
                1_073L, 100_000L, LocalDateTime.now().minusMinutes(1),
                "TASK_COMMON_RETURN:100000:REPLAY", "Повтор полного возврата");

        assertEquals(100_000L, allocation.getReturnedKopecks());
        assertEquals(ContractorAllocationStatus.RETURNED, allocation.getStatus());
        verify(taskReturnBridge, times(3)).recordReturn(binding, allocation);
        InOrder locks = inOrder(taskReturnBridge, profileRepository, allocationRepository);
        locks.verify(taskReturnBridge).lockCommonInvoiceBinding(allocation, restoredInvoice);
        locks.verify(profileRepository).findByIdForUpdate(1_090L);
        locks.verify(allocationRepository).findByIdForUpdate(1_073L);
    }

    @Test
    void manualReturnIsRejectedForCommonInvoiceWithoutExactTaskAttribution() {
        ContractorPaymentAllocation allocation = new ContractorPaymentAllocation();
        allocation.setId(109L);
        allocation.setMode(ContractorAllocationMode.LIVE);
        allocation.setSourceType(ContractorAllocationSourceType.COMMON_INVOICE);
        allocation.setSourceId(110L);
        allocation.setConfirmedKopecks(100_000L);
        allocation.setStatus(ContractorAllocationStatus.RETURN_AMOUNT_PENDING);
        CommonInvoice invoice = new CommonInvoice();
        invoice.setId(110L);
        ManualPaymentTaskContractorReturnBridge.Binding binding =
                mock(ManualPaymentTaskContractorReturnBridge.Binding.class);
        when(allocationRepository.findById(109L)).thenReturn(Optional.of(allocation));
        when(commonInvoiceRepository.findByIdForUpdate(110L)).thenReturn(Optional.of(invoice));
        when(taskReturnBridge.lockCommonInvoiceBinding(allocation, invoice)).thenReturn(binding);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.recordObservedReturnAmount(
                        109L, 50_000L, LocalDateTime.now(), "MANUAL:COMMON", "unsupported"
                )
        );

        assertEquals(
                "Возврат подтвержденного назначения разрешен только для точного получателя платежного задания",
                error.getMessage()
        );
        assertEquals(0L, allocation.getReturnedKopecks());
        verify(allocationRepository, never()).save(any());
    }

    @Test
    void manualReturnRejectsFutureEffectiveTimestamp() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.recordObservedReturnAmount(
                        111L,
                        1L,
                        LocalDateTime.now().plusMinutes(5),
                        "MANUAL:FUTURE",
                        "future"
                )
        );

        assertEquals("Дата возврата не может быть в будущем", error.getMessage());
        verify(allocationRepository, never()).findById(111L);
    }

    @Test
    void unpaidReleaseCallbacksAlwaysStartIndependentTransactions() throws Exception {
        Transactional orderRelease = ContractorPaymentShadowService.class
                .getMethod("releaseForFinanciallyClosedOrder", Long.class, String.class)
                .getAnnotation(Transactional.class);
        Transactional commonInvoiceRelease = ContractorPaymentShadowService.class
                .getMethod("releaseForUnpaidCommonInvoice", Long.class, String.class)
                .getAnnotation(Transactional.class);
        Transactional commonInvoiceReconcile = ContractorPaymentShadowService.class
                .getMethod("reconcileCommonInvoiceId", Long.class)
                .getAnnotation(Transactional.class);

        assertEquals(Propagation.REQUIRES_NEW, orderRelease.propagation());
        assertEquals(Propagation.REQUIRES_NEW, commonInvoiceRelease.propagation());
        assertEquals(Propagation.REQUIRES_NEW, commonInvoiceReconcile.propagation());
    }

    @Test
    void archivePreflightCommitsIndependentlyWhileStrictPassJoinsArchiveTransaction() throws Exception {
        Transactional preflight = ContractorPaymentShadowService.class
                .getMethod("reconcileAllocationId", Long.class)
                .getAnnotation(Transactional.class);
        Transactional strict = ContractorPaymentShadowService.class
                .getMethod("reconcileAllocationForArchive", Long.class)
                .getAnnotation(Transactional.class);

        assertEquals(Propagation.REQUIRES_NEW, preflight.propagation());
        assertEquals(Propagation.MANDATORY, strict.propagation());
    }

    private ContractorPaymentProfile profile(Long id, User user, ContractorRole role) {
        ContractorPaymentProfile profile = new ContractorPaymentProfile();
        profile.setId(id);
        profile.setUser(user);
        profile.setRole(role);
        profile.setEnabled(true);
        profile.setRecipientName("Получатель");
        profile.setPaymentPhone("+79990000000");
        profile.setBankName("Тестовый банк");
        return profile;
    }

    private PaymentLink paymentLink(Long id, Order order, long amount) {
        PaymentLink link = new PaymentLink();
        link.setId(id);
        link.setOrder(order);
        link.setAmountKopecks(amount);
        when(paymentLinkRepository.findByIdWithOrder(id)).thenReturn(Optional.of(link));
        when(paymentLinkRepository.findByIdForUpdate(id)).thenReturn(Optional.of(link));
        if (order != null && order.getId() != null) {
            when(orderRepository.findByIdForCounterUpdate(order.getId())).thenReturn(Optional.of(order));
        }
        return link;
    }

    private void registerReconciliation(ContractorPaymentAllocation allocation) {
        when(allocationRepository.findById(allocation.getId())).thenReturn(Optional.of(allocation));
        when(allocationRepository.findByIdForUpdate(allocation.getId())).thenReturn(Optional.of(allocation));
        when(allocationRepository.findLatestIdForUpdate(
                allocation.getMode().name(),
                allocation.getSourceType().name(),
                allocation.getSourceId()
        )).thenReturn(Optional.of(allocation.getId()));
    }

    private ContractorActualPaymentAttribution paymentLinkAttribution(
            Long id,
            PaymentLink link,
            ContractorPaymentAllocation original,
            ContractorPaymentProfile actualProfile,
            ContractorRecipientType actualType,
            ContractorCashDestinationKind destination,
            Long taskId,
            long amountKopecks
    ) {
        ContractorActualPaymentAttribution row = mock(ContractorActualPaymentAttribution.class);
        when(row.getId()).thenReturn(id);
        when(row.getSourceKind()).thenReturn(ContractorActualPaymentSourceKind.PAYMENT_LINK);
        when(row.getSourceId()).thenReturn(link.getId());
        when(row.getOrderId()).thenReturn(link.getOrder() == null ? null : link.getOrder().getId());
        when(row.getOriginalAllocationId()).thenReturn(original == null ? null : original.getId());
        when(row.getAccountingMode()).thenReturn(original == null
                ? ContractorAllocationMode.LIVE : original.getMode());
        when(row.getActualCashDestinationKind()).thenReturn(destination);
        when(row.getActualRecipientType()).thenReturn(actualType);
        when(row.getActualRecipientProfileId()).thenReturn(
                actualProfile == null ? null : actualProfile.getId());
        when(row.getActualManualPaymentTaskId()).thenReturn(taskId);
        when(row.getAmountKopecks()).thenReturn(amountKopecks);
        return row;
    }

    private Order order(Long id, Worker worker, Manager manager) {
        Order order = new Order();
        order.setId(id);
        order.setWorker(worker);
        order.setManager(manager);
        Company company = new Company();
        company.setId(id + 100_000L);
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
