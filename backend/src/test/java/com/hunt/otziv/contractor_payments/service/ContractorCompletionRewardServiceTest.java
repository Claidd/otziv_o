package com.hunt.otziv.contractor_payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository;
import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.model.BadReviewTaskStatus;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.contractor_payments.model.ContractorCompletionRewardMarker;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAccountingAuthority;
import com.hunt.otziv.contractor_payments.model.ContractorRole;
import com.hunt.otziv.contractor_payments.repository.ContractorCompletionRewardMarkerRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.performers.service.PerformerProductRewardZpService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryGateService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.z_zp.model.Zp;
import com.hunt.otziv.z_zp.repository.ZpRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ContractorCompletionRewardServiceTest {

    @Mock private ContractorPaymentRuntimeSwitch runtimeSwitch;
    @Mock private OrderRepository orderRepository;
    @Mock private BadReviewTaskRepository badReviewTaskRepository;
    @Mock private ReviewRepository reviewRepository;
    @Mock private ReviewRecoveryGateService recoveryGateService;
    @Mock private ZpRepository zpRepository;
    @Mock private ContractorCompletionRewardMarkerRepository markerRepository;
    @Mock private ContractorRewardAttributionService attributionService;
    @Mock private ContractorRewardLedgerService ledgerService;
    @Mock private PerformerProductRewardZpService productRewardService;
    @Mock private ContractorPaymentBusinessClock businessClock;
    @Mock private ContractorPaymentRolloutStateService rolloutStateService;
    @Mock private ContractorLegacyRewardGuard legacyRewardGuard;
    @Mock private ContractorLegacyRewardReconciliationService legacyRewardReconciliationService;
    @Mock private ContractorPaymentProfileService profileService;

    private ContractorCompletionRewardService service;
    private Order order;

    @BeforeEach
    void setUp() {
        service = new ContractorCompletionRewardService(
                runtimeSwitch,
                orderRepository,
                badReviewTaskRepository,
                reviewRepository,
                recoveryGateService,
                zpRepository,
                markerRepository,
                attributionService,
                ledgerService,
                productRewardService,
                businessClock,
                new ContractorOrderManagerResolver(),
                rolloutStateService,
                legacyRewardGuard,
                legacyRewardReconciliationService,
                profileService
        );
        order = new Order();
        order.setId(91L);
        order.setAmount(1);
        order.setSum(new BigDecimal("1000.00"));
        User specialist = new User();
        specialist.setId(17L);
        specialist.setFio("Специалист");
        Worker worker = new Worker();
        worker.setId(27L);
        worker.setUser(specialist);
        order.setWorker(worker);
        User managerUser = new User();
        managerUser.setId(18L);
        managerUser.setFio("Менеджер заказа");
        managerUser.setCoefficient(new BigDecimal("0.10"));
        Manager manager = new Manager();
        manager.setId(28L);
        manager.setUser(managerUser);
        order.setManager(manager);
        Review published = Review.builder()
                .id(501L)
                .publish(true)
                .publishedDate(LocalDate.of(2026, 7, 31))
                .build();
        when(rolloutStateService.lockAccountingAuthority())
                .thenReturn(ContractorPaymentAccountingAuthority.COMPLETION);
        when(orderRepository.findByIdForCounterUpdate(91L)).thenReturn(Optional.of(order));
        org.mockito.Mockito.lenient().when(orderRepository.findByIdForOrderDto(91L))
                .thenReturn(Optional.of(order));
        org.mockito.Mockito.lenient().when(reviewRepository.countPublishedByOrderId(91L)).thenReturn(1);
        org.mockito.Mockito.lenient().when(reviewRepository.getAllByOrderId(91L)).thenReturn(List.of(published));
        org.mockito.Mockito.lenient().when(runtimeSwitch.completionAttributionStartDate())
                .thenReturn(Optional.of(LocalDate.of(2026, 8, 1)));
        org.mockito.Mockito.lenient().when(attributionService.attributeCompletedBaseWork(order))
                .thenReturn(List.of(new ContractorRewardAttributionService.SpecialistShare(
                        specialist,
                        worker.getId(),
                        order.getSum(),
                        1
                )));
    }

    @Test
    void paymentDoesNotReopenLegacyBridgeAfterPostCutoverCompletionWasFrozen() {
        LocalDate completedOn = LocalDate.of(2026, 8, 2);
        when(markerRepository.findByOrderIdAndLogicalSource(any(), any()))
                .thenAnswer(invocation -> {
                    String source = invocation.getArgument(1);
                    ContractorCompletionRewardMarker marker = new ContractorCompletionRewardMarker();
                    marker.setOrderId(91L);
                    marker.setLogicalSource(source);
                    marker.setOccurredOn(completedOn);
                    return Optional.of(marker);
                });
        Zp managerReward = new Zp();
        managerReward.setSource(ContractorRewardSourceCodes.ORDER_COMPLETION_MANAGER);
        Zp specialistReward = new Zp();
        specialistReward.setSource(ContractorRewardSourceCodes.ORDER_COMPLETION_SPECIALIST);
        when(zpRepository.findByOrderIdAndActiveTrue(91L))
                .thenReturn(List.of(managerReward, specialistReward));

        assertThat(service.ensureOrderPaymentAccrual(91L)).isZero();

        verify(zpRepository, never()).save(any(Zp.class));
        verify(productRewardService, never()).accrueForPreCutoffPaymentLocked(any(), any(), any());
        verify(ledgerService).synchronizeCompletionSourcesCanonical(
                List.of(managerReward, specialistReward)
        );
    }
    @Test
    void preCutoffWorkFreezesLogicalSourcesWithoutInspectingOrComplementingPartialLegacy() {
        service.ensureOrderCompletionAccrual(91L);

        ArgumentCaptor<ContractorCompletionRewardMarker> markers =
                ArgumentCaptor.forClass(ContractorCompletionRewardMarker.class);
        verify(markerRepository, org.mockito.Mockito.times(3)).save(markers.capture());
        assertThat(markers.getAllValues())
                .extracting(ContractorCompletionRewardMarker::getLogicalSource)
                .containsExactlyInAnyOrder(
                        ContractorRewardSourceCodes.ORDER_COMPLETION_MANAGER,
                        ContractorRewardSourceCodes.ORDER_COMPLETION_SPECIALIST,
                        ContractorRewardSourceCodes.PERFORMER_PRODUCT_COMPLETION
                );
        verify(zpRepository, never()).save(any(Zp.class));
        verify(ledgerService, never()).synchronizeCompletionSourcesCanonical(any());
        verify(productRewardService, never()).accrueForCompletedOrderLocked(any(), any(), anyBoolean());
        verify(zpRepository, never()).existsByOrderIdAndSourceAndActiveTrue(
                91L,
                ContractorRewardSourceCodes.LEGACY_ORDER_MANAGER
        );
    }

    @Test
    void manualEvidenceOverridesFuturePlannedReviewDateAndCreatesNoNewBaseRows() {
        when(legacyRewardReconciliationService.authoritativeCompletedOn(
                91L, LocalDate.of(2026, 8, 1)
        )).thenReturn(Optional.of(LocalDate.of(2026, 7, 31)));

        assertThat(service.ensureOrderCompletionAccrual(91L)).isZero();

        verify(zpRepository, never()).save(any(Zp.class));
        verify(markerRepository, org.mockito.Mockito.times(3)).save(any());
        verify(productRewardService, never()).accrueForCompletedOrderLocked(any(), any(), anyBoolean());
    }

    @Test
    void manualEvidenceSuppliesNullPublishedDateAndFreezesPreCutoffBase() {
        when(legacyRewardReconciliationService.authoritativeCompletedOn(
                91L, LocalDate.of(2026, 8, 1)
        )).thenReturn(Optional.of(LocalDate.of(2026, 6, 21)));

        assertThat(service.ensureOrderCompletionAccrual(91L)).isZero();

        verify(zpRepository, never()).save(any(Zp.class));
        verify(markerRepository, org.mockito.Mockito.times(3)).save(any());
    }

    @Test
    void preCutoffUnpaidWorkAccruesBaseAndPreCutoffDoneTaskOnceWhenPaymentArrivesAfterCutover() {
        order.getWorker().getUser().setCoefficient(new BigDecimal("0.30"));
        BadReviewTask preCutoffTask = BadReviewTask.builder()
                .id(701L)
                .order(order)
                .status(BadReviewTaskStatus.DONE)
                .price(new BigDecimal("500.00"))
                .completedDate(LocalDate.of(2026, 7, 31))
                .worker(order.getWorker())
                .build();
        when(badReviewTaskRepository.findAllByOrderIdAndStatus(91L, BadReviewTaskStatus.DONE))
                .thenReturn(List.of(preCutoffTask));
        when(businessClock.today()).thenReturn(LocalDate.of(2026, 8, 10));
        Zp existing = new Zp();
        existing.setActive(true);
        when(zpRepository.findFirstByOrderIdAndSourceAndContractorRoleAndProfessionId(
                91L,
                ContractorRewardSourceCodes.LEGACY_ORDER_MANAGER,
                ContractorRole.MANAGER,
                28L
        )).thenReturn(Optional.empty(), Optional.of(existing));
        when(zpRepository.findFirstByOrderIdAndSourceAndContractorRoleAndProfessionId(
                91L,
                ContractorRewardSourceCodes.LEGACY_ORDER_SPECIALIST,
                ContractorRole.SPECIALIST,
                27L
        )).thenReturn(Optional.empty(), Optional.of(existing));
        when(productRewardService.accrueForPreCutoffPaymentLocked(
                order,
                order.getManager(),
                LocalDate.of(2026, 8, 10)
        )).thenReturn(1, 0);

        assertThat(service.ensureOrderPaymentAccrual(91L)).isEqualTo(3);
        assertThat(service.ensureOrderPaymentAccrual(91L)).isZero();

        ArgumentCaptor<Zp> rewards = ArgumentCaptor.forClass(Zp.class);
        verify(zpRepository, org.mockito.Mockito.times(2)).save(rewards.capture());
        assertThat(rewards.getAllValues())
                .extracting(
                        Zp::getSource,
                        Zp::getContractorRole,
                        Zp::getProfessionId,
                        Zp::getSum,
                        Zp::getRewardBasis,
                        Zp::getAmount,
                        Zp::getCreated
                )
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                ContractorRewardSourceCodes.LEGACY_ORDER_MANAGER,
                                ContractorRole.MANAGER,
                                28L,
                                new BigDecimal("150.00"),
                                new BigDecimal("1500.00"),
                                2,
                                LocalDate.of(2026, 8, 10)
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                ContractorRewardSourceCodes.LEGACY_ORDER_SPECIALIST,
                                ContractorRole.SPECIALIST,
                                27L,
                                new BigDecimal("450.00"),
                                new BigDecimal("1500.00"),
                                2,
                                LocalDate.of(2026, 8, 10)
                        )
                );
        verify(productRewardService, org.mockito.Mockito.times(2)).accrueForPreCutoffPaymentLocked(
                order,
                order.getManager(),
                LocalDate.of(2026, 8, 10)
        );
    }

    @Test
    void postCutoffDoneTaskStaysOutsideLegacyPaymentBridgeAggregate() {
        order.getWorker().getUser().setCoefficient(new BigDecimal("0.30"));
        BadReviewTask postCutoffTask = BadReviewTask.builder()
                .id(702L)
                .order(order)
                .status(BadReviewTaskStatus.DONE)
                .price(new BigDecimal("500.00"))
                .completedDate(LocalDate.of(2026, 8, 2))
                .worker(order.getWorker())
                .build();
        when(badReviewTaskRepository.findAllByOrderIdAndStatus(91L, BadReviewTaskStatus.DONE))
                .thenReturn(List.of(postCutoffTask));
        when(businessClock.today()).thenReturn(LocalDate.of(2026, 8, 10));

        assertThat(service.ensureOrderPaymentAccrual(91L)).isEqualTo(4);

        ArgumentCaptor<Zp> rewards = ArgumentCaptor.forClass(Zp.class);
        verify(zpRepository, org.mockito.Mockito.times(4)).save(rewards.capture());
        assertThat(rewards.getAllValues())
                .extracting(Zp::getSource)
                .containsExactlyInAnyOrder(
                        ContractorRewardSourceCodes.LEGACY_ORDER_MANAGER,
                        ContractorRewardSourceCodes.LEGACY_ORDER_SPECIALIST,
                        ContractorRewardSourceCodes.badReviewManager(702L),
                        ContractorRewardSourceCodes.badReviewSpecialist(702L)
                );
        assertThat(rewards.getAllValues())
                .filteredOn(reward -> ContractorRewardSourceCodes.LEGACY_ORDER_MANAGER.equals(reward.getSource())
                        || ContractorRewardSourceCodes.LEGACY_ORDER_SPECIALIST.equals(reward.getSource()))
                .allSatisfy(reward -> {
                    assertThat(reward.getRewardBasis()).isEqualByComparingTo("1000.00");
                    assertThat(reward.getAmount()).isEqualTo(1);
                });
        assertThat(rewards.getAllValues())
                .filteredOn(reward -> reward.getSource().startsWith("BAD_REVIEW_DONE_"))
                .allSatisfy(reward -> assertThat(reward.getRewardBasis()).isEqualByComparingTo("500.00"));
    }

    @Test
    void preCutoffPaymentBridgeFailsBeforeWritingWhenImmutableSpecialistAttributionIsMissing() {
        Worker reassignedWorker = new Worker();
        reassignedWorker.setId(99L);
        User reassignedUser = new User();
        reassignedUser.setId(199L);
        reassignedUser.setFio("Новый специалист карточки");
        reassignedUser.setCoefficient(new BigDecimal("0.90"));
        reassignedWorker.setUser(reassignedUser);
        order.setWorker(reassignedWorker);
        when(attributionService.attributeCompletedBaseWork(order))
                .thenThrow(new ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT,
                        "immutable attribution is missing"
                ));

        assertThrows(ResponseStatusException.class, () -> service.ensureOrderPaymentAccrual(91L));

        verify(zpRepository, never()).save(any(Zp.class));
        verify(markerRepository, never()).save(any());
        verify(productRewardService, never()).accrueForPreCutoffPaymentLocked(any(), any(), any());
    }

    @Test
    void paidStatusWithoutPublishedWorkCannotEnterPreCutoffPaymentBridge() {
        OrderStatus paid = new OrderStatus();
        paid.setTitle("Оплачено");
        order.setStatus(paid);
        when(reviewRepository.countPublishedByOrderId(91L)).thenReturn(0);

        assertThrows(ResponseStatusException.class, () -> service.ensureOrderPaymentAccrual(91L));

        verify(legacyRewardGuard, never()).requireNoUnclassifiedActiveRows(any());
        verify(zpRepository, never()).save(any(Zp.class));
        verify(markerRepository, never()).save(any());
        verify(productRewardService, never()).accrueForPreCutoffPaymentLocked(any(), any(), any());
    }

    @Test
    void orderMutexIsLockedBeforeAccountingAuthorityToMatchMutationPaths() {
        service.ensureOrderCompletionAccrual(91L);

        var ordered = inOrder(rolloutStateService, orderRepository);
        ordered.verify(orderRepository).findByIdForCounterUpdate(91L);
        ordered.verify(rolloutStateService).lockAccountingAuthority();
    }

    @Test
    void preCutoffOrderStillAttributesPostCutoffTaskToFrozenOrderManager() {
        BadReviewTask task = BadReviewTask.builder()
                .id(703L)
                .order(order)
                .status(BadReviewTaskStatus.DONE)
                .price(new BigDecimal("500.00"))
                .completedDate(LocalDate.of(2026, 8, 1))
                .worker(order.getWorker())
                .build();
        when(badReviewTaskRepository.findAllByOrderIdAndStatus(91L, BadReviewTaskStatus.DONE))
                .thenReturn(List.of(task));

        service.ensureOrderCompletionAccrual(91L);

        ArgumentCaptor<Zp> rewards = ArgumentCaptor.forClass(Zp.class);
        verify(zpRepository, org.mockito.Mockito.atLeastOnce()).save(rewards.capture());
        assertThat(rewards.getAllValues()).anySatisfy(reward -> {
            assertThat(reward.getSource()).isEqualTo(ContractorRewardSourceCodes.badReviewManager(703L));
            assertThat(reward.getProfessionId()).isEqualTo(order.getManager().getId());
            assertThat(reward.getUserId()).isEqualTo(order.getManager().getUser().getId());
        });
    }

    @Test
    void preCutoffBaseWithPostCutoffTaskAcceptsOnlyDatedLegacyAggregateAndAccruesTaskOnce() {
        order.getWorker().getUser().setCoefficient(new BigDecimal("0.30"));
        BadReviewTask task = BadReviewTask.builder()
                .id(704L)
                .order(order)
                .status(BadReviewTaskStatus.DONE)
                .price(new BigDecimal("500.00"))
                .completedDate(LocalDate.of(2026, 8, 1))
                .worker(order.getWorker())
                .build();
        when(markerRepository.findByOrderIdAndLogicalSource(
                91L,
                ContractorRewardSourceCodes.badReviewDoneMarker(704L)
        )).thenReturn(
                Optional.empty(),
                Optional.empty(),
                Optional.of(marker(ContractorRewardSourceCodes.badReviewDoneMarker(704L)))
        );

        assertThat(service.ensureCompletedBadReviewTask(task)).isEqualTo(2);
        assertThat(service.ensureCompletedBadReviewTask(task)).isZero();

        verify(legacyRewardGuard, org.mockito.Mockito.times(2))
                .requireOnlyDatedPreCutoffLegacyAggregate(91L, LocalDate.of(2026, 8, 1));
        ArgumentCaptor<Zp> rewards = ArgumentCaptor.forClass(Zp.class);
        verify(zpRepository, org.mockito.Mockito.times(2)).save(rewards.capture());
        assertThat(rewards.getAllValues())
                .extracting(Zp::getSource)
                .containsExactlyInAnyOrder(
                        ContractorRewardSourceCodes.badReviewManager(704L),
                        ContractorRewardSourceCodes.badReviewSpecialist(704L)
                );
    }

    @Test
    void preCutoffBaseWithBoundaryOrUndatedLegacyRowBlocksPostCutoffTaskBeforeAnyWrite() {
        BadReviewTask task = BadReviewTask.builder()
                .id(705L)
                .order(order)
                .status(BadReviewTaskStatus.DONE)
                .price(new BigDecimal("500.00"))
                .completedDate(LocalDate.of(2026, 8, 1))
                .worker(order.getWorker())
                .build();
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "legacy date"))
                .when(legacyRewardGuard)
                .requireOnlyDatedPreCutoffLegacyAggregate(91L, LocalDate.of(2026, 8, 1));

        assertThrows(ResponseStatusException.class, () -> service.ensureCompletedBadReviewTask(task));

        verify(markerRepository, never()).save(any());
        verify(zpRepository, never()).save(any(Zp.class));
        verify(ledgerService, never()).synchronizeCompletionSourcesCanonical(any());
    }

    @Test
    void undatedPublishedBaseCannotUseDatedLegacyExceptionForPostCutoffTask() {
        Review undated = boundaryReview();
        undated.setPublishedDate(null);
        when(reviewRepository.getAllByOrderId(91L)).thenReturn(List.of(undated));
        BadReviewTask task = BadReviewTask.builder()
                .id(706L)
                .order(order)
                .status(BadReviewTaskStatus.DONE)
                .price(new BigDecimal("500.00"))
                .completedDate(LocalDate.of(2026, 8, 1))
                .worker(order.getWorker())
                .build();
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "unclassified base"))
                .when(legacyRewardGuard).requireNoActiveLegacyAggregate(91L);

        assertThrows(ResponseStatusException.class, () -> service.ensureCompletedBadReviewTask(task));

        verify(legacyRewardGuard, never()).requireOnlyDatedPreCutoffLegacyAggregate(any(), any());
        verify(markerRepository, never()).save(any());
        verify(zpRepository, never()).save(any(Zp.class));
    }

    @Test
    void postCutoffBaseAlwaysPassesThroughLegacyGuardBeforeWriting() {
        Review boundaryReview = Review.builder()
                .id(505L)
                .publish(true)
                .publishedDate(LocalDate.of(2026, 8, 1))
                .build();
        when(reviewRepository.getAllByOrderId(91L)).thenReturn(List.of(boundaryReview));
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "unclassified"))
                .when(legacyRewardGuard).requireNoActiveLegacyAggregate(91L);

        assertThrows(ResponseStatusException.class, () -> service.ensureOrderCompletionAccrual(91L));

        verify(markerRepository, never()).save(any());
        verify(zpRepository, never()).save(any(Zp.class));
    }

    @Test
    void paymentCancellationFailsBeforeMutationForUnclassifiedHistoricalReward() {
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "unclassified"))
                .when(legacyRewardGuard).requireCancellationClassifiable(91L);

        assertThrows(
                ResponseStatusException.class,
                () -> service.migrateLegacyRewardsBeforePaymentCancellation(91L)
        );

        verify(markerRepository, never()).save(any());
        verify(zpRepository, never()).save(any(Zp.class));
        verify(ledgerService, never()).synchronizeCompletionSourcesCanonical(any());
    }

    @Test
    void directPostCutoffTaskCompletionRejectsLegacyAggregateBeforeAnyWrite() {
        when(reviewRepository.getAllByOrderId(91L)).thenReturn(List.of(boundaryReview()));
        BadReviewTask task = BadReviewTask.builder()
                .id(705L)
                .order(order)
                .status(BadReviewTaskStatus.DONE)
                .price(new BigDecimal("500.00"))
                .completedDate(LocalDate.of(2026, 8, 1))
                .worker(order.getWorker())
                .build();
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "legacy"))
                .when(legacyRewardGuard).requireNoActiveLegacyAggregate(91L);

        assertThrows(ResponseStatusException.class, () -> service.ensureCompletedBadReviewTask(task));

        verify(markerRepository, never()).save(any());
        verify(zpRepository, never()).save(any(Zp.class));
        verify(ledgerService, never()).synchronizeCompletionSourcesCanonical(any());
    }

    @Test
    void cancellationDoesNotFreezePostCutoffTaskIntoPreCutoffLegacyAggregate() {
        BadReviewTask task = BadReviewTask.builder()
                .id(706L)
                .order(order)
                .status(BadReviewTaskStatus.DONE)
                .price(new BigDecimal("500.00"))
                .completedDate(LocalDate.of(2026, 8, 1))
                .worker(order.getWorker())
                .build();
        when(zpRepository.existsByOrderIdAndSourceAndActiveTrue(
                91L,
                ContractorRewardSourceCodes.LEGACY_ORDER_MANAGER
        )).thenReturn(true);
        when(badReviewTaskRepository.findAllByOrderIdAndStatus(91L, BadReviewTaskStatus.DONE))
                .thenReturn(List.of(task));

        assertThrows(
                ResponseStatusException.class,
                () -> service.migrateLegacyRewardsBeforePaymentCancellation(91L)
        );

        verify(markerRepository, never()).save(any());
        verify(zpRepository, never()).save(any(Zp.class));
        verify(ledgerService, never()).synchronizeCompletionSourcesCanonical(any());
    }

    @Test
    void canceledPreCutoffTaskMarkerWithoutOriginalRewardAdjustsOpeningBalance() {
        User specialistUser = order.getWorker().getUser();
        specialistUser.setCoefficient(new BigDecimal("0.30"));
        BadReviewTask task = BadReviewTask.builder()
                .id(707L)
                .order(order)
                .status(BadReviewTaskStatus.CANCELED)
                .price(new BigDecimal("500.00"))
                .completedDate(LocalDate.of(2026, 7, 31))
                .worker(order.getWorker())
                .build();
        ContractorCompletionRewardMarker done = marker(
                ContractorRewardSourceCodes.badReviewDoneMarker(707L),
                LocalDate.of(2026, 7, 31)
        );
        when(markerRepository.findByOrderIdAndLogicalSource(
                91L,
                ContractorRewardSourceCodes.badReviewDoneMarker(707L)
        )).thenReturn(Optional.of(done));
        when(markerRepository.findByOrderIdAndLogicalSource(
                91L,
                ContractorRewardSourceCodes.badReviewCancelMarker(707L)
        )).thenReturn(Optional.empty());
        when(badReviewTaskRepository.findByIdForMutation(707L)).thenReturn(Optional.of(task));
        when(businessClock.today()).thenReturn(LocalDate.of(2026, 8, 21));
        when(profileService.applySystemOpeningBalanceDelta(
                18L,
                ContractorRole.MANAGER,
                -5_000L,
                "Автокорректировка переходящего остатка: плохая задача #707 удалена из счета заказа #91"
        )).thenReturn(-5_000L);
        when(profileService.applySystemOpeningBalanceDelta(
                17L,
                ContractorRole.SPECIALIST,
                -15_000L,
                "Автокорректировка переходящего остатка: плохая задача #707 удалена из счета заказа #91"
        )).thenReturn(-15_000L);

        assertThat(service.adjustCanceledBadReviewTaskAccrual(91L, 707L)).isEqualTo(2);

        verify(profileService).applySystemOpeningBalanceDelta(
                18L,
                ContractorRole.MANAGER,
                -5_000L,
                "Автокорректировка переходящего остатка: плохая задача #707 удалена из счета заказа #91"
        );
        verify(profileService).applySystemOpeningBalanceDelta(
                17L,
                ContractorRole.SPECIALIST,
                -15_000L,
                "Автокорректировка переходящего остатка: плохая задача #707 удалена из счета заказа #91"
        );
        ArgumentCaptor<ContractorCompletionRewardMarker> markerCaptor =
                ArgumentCaptor.forClass(ContractorCompletionRewardMarker.class);
        verify(markerRepository).save(markerCaptor.capture());
        assertThat(markerCaptor.getValue().getLogicalSource())
                .isEqualTo(ContractorRewardSourceCodes.badReviewCancelMarker(707L));
        assertThat(markerCaptor.getValue().getOccurredOn()).isEqualTo(LocalDate.of(2026, 8, 21));
        verify(ledgerService, never()).synchronizeCompletionSourcesCanonical(any());
        verify(zpRepository, never()).save(any(Zp.class));
    }

    @Test
    void canceledPostCutoffTaskMarkerWithoutOriginalRewardFailsClosed() {
        ContractorCompletionRewardMarker done = marker(
                ContractorRewardSourceCodes.badReviewDoneMarker(707L)
        );
        when(markerRepository.findByOrderIdAndLogicalSource(
                91L,
                ContractorRewardSourceCodes.badReviewDoneMarker(707L)
        )).thenReturn(Optional.of(done));
        when(markerRepository.findByOrderIdAndLogicalSource(
                91L,
                ContractorRewardSourceCodes.badReviewCancelMarker(707L)
        )).thenReturn(Optional.empty());

        assertThrows(
                ResponseStatusException.class,
                () -> service.adjustCanceledBadReviewTaskAccrual(91L, 707L)
        );

        verify(markerRepository, never()).save(any());
        verify(zpRepository, never()).save(any(Zp.class));
        verify(ledgerService, never()).synchronizeCompletionSourcesCanonical(any());
    }

    @Test
    void boundaryDateIsInclusiveAndUsesCompletionPath() {
        Review boundaryReview = Review.builder()
                .id(502L)
                .publish(true)
                .publishedDate(LocalDate.of(2026, 8, 1))
                .build();
        when(reviewRepository.getAllByOrderId(91L)).thenReturn(List.of(boundaryReview));

        service.ensureOrderCompletionAccrual(91L);

        verify(productRewardService).accrueForCompletedOrderLocked(
                order,
                order.getManager(),
                LocalDate.of(2026, 8, 1),
                false
        );
    }

    @Test
    void synchronousCompletionUsesCompanyManagerFallbackForEveryManagerSource() {
        Manager companyManager = manager(29L, 19L);
        Company company = new Company();
        company.setManager(companyManager);
        order.setManager(null);
        order.setCompany(company);
        order.setDetails(List.of(detail("1000.00")));
        when(businessClock.today()).thenReturn(LocalDate.of(2026, 8, 7));

        service.ensureOrderCompletionAccrualNow(91L);

        ArgumentCaptor<Zp> rewards = ArgumentCaptor.forClass(Zp.class);
        verify(zpRepository, org.mockito.Mockito.atLeastOnce()).save(rewards.capture());
        assertThat(rewards.getAllValues()).anySatisfy(reward -> {
            assertThat(reward.getContractorRole()).isEqualTo(ContractorRole.MANAGER);
            assertThat(reward.getUserId()).isEqualTo(19L);
            assertThat(reward.getProfessionId()).isEqualTo(29L);
        });
        verify(productRewardService).accrueForCompletedOrderLocked(
                order,
                companyManager,
                LocalDate.of(2026, 8, 7),
                false
        );
    }

    @Test
    void historicalRepairDoesNotUseMutableCompanyManagerAndWritesNoMarker() {
        Company company = new Company();
        company.setManager(manager(29L, 19L));
        order.setManager(null);
        order.setCompany(company);
        when(reviewRepository.getAllByOrderId(91L)).thenReturn(List.of(boundaryReview()));

        assertThrows(ResponseStatusException.class, () -> service.ensureOrderCompletionAccrual(91L));

        verify(markerRepository, never()).save(any());
        verify(zpRepository, never()).save(any());
        verify(productRewardService, never()).validateCompletedOrderEvidence(any());
    }

    @Test
    void immutableCompleteRerunIgnoresChangedManagerAndReviewEvidence() {
        order.setManager(null);
        Company company = new Company();
        company.setManager(manager(null, null));
        order.setCompany(company);
        when(markerRepository.findByOrderIdAndLogicalSource(
                91L,
                ContractorRewardSourceCodes.ORDER_COMPLETION_MANAGER
        )).thenReturn(Optional.of(marker(ContractorRewardSourceCodes.ORDER_COMPLETION_MANAGER)));
        when(markerRepository.findByOrderIdAndLogicalSource(
                91L,
                ContractorRewardSourceCodes.ORDER_COMPLETION_SPECIALIST
        )).thenReturn(Optional.of(marker(ContractorRewardSourceCodes.ORDER_COMPLETION_SPECIALIST)));
        when(markerRepository.findByOrderIdAndLogicalSource(
                91L,
                ContractorRewardSourceCodes.PERFORMER_PRODUCT_COMPLETION
        )).thenReturn(Optional.of(marker(ContractorRewardSourceCodes.PERFORMER_PRODUCT_COMPLETION)));

        assertThat(service.ensureOrderCompletionAccrual(91L)).isZero();

        verify(reviewRepository, never()).countPublishedByOrderId(91L);
        verify(attributionService, never()).attributeCompletedBaseWork(any());
        verify(productRewardService, never()).validateCompletedOrderEvidence(any());
        verify(markerRepository, never()).save(any());
        verify(zpRepository, never()).save(any());
    }

    @Test
    void positiveDetailsWithNullCanonicalSumFailBeforeAnyMarker() {
        OrderDetails detail = new OrderDetails();
        detail.setPrice(new BigDecimal("1000.00"));
        order.setDetails(List.of(detail));
        order.setSum(null);
        Review boundaryReview = Review.builder()
                .id(502L)
                .publish(true)
                .publishedDate(LocalDate.of(2026, 8, 1))
                .build();
        when(reviewRepository.getAllByOrderId(91L)).thenReturn(List.of(boundaryReview));

        assertThrows(ResponseStatusException.class, () -> service.ensureOrderCompletionAccrual(91L));

        verify(markerRepository, never()).save(any());
        verify(zpRepository, never()).save(any());
    }

    @Test
    void noDetailsWithNullCanonicalSumFailBeforeAnyMarker() {
        order.setDetails(List.of());
        order.setSum(null);
        when(reviewRepository.getAllByOrderId(91L)).thenReturn(List.of(boundaryReview()));

        assertThrows(ResponseStatusException.class, () -> service.ensureOrderCompletionAccrual(91L));

        verify(markerRepository, never()).save(any());
        verify(zpRepository, never()).save(any());
        verify(productRewardService, never()).accrueForCompletedOrderLocked(any(), any(), anyBoolean());
    }

    @Test
    void zeroPricedDetailsWithZeroCanonicalSumFailBeforeAnyMarker() {
        order.setDetails(List.of(detail("0.00")));
        order.setSum(BigDecimal.ZERO);
        when(reviewRepository.getAllByOrderId(91L)).thenReturn(List.of(boundaryReview()));

        assertThrows(ResponseStatusException.class, () -> service.ensureOrderCompletionAccrual(91L));

        verify(markerRepository, never()).save(any());
        verify(zpRepository, never()).save(any());
        verify(productRewardService, never()).accrueForCompletedOrderLocked(any(), any(), anyBoolean());
    }

    @Test
    void positiveDetailsWithZeroCanonicalSumFailBeforeAnyMarker() {
        order.setDetails(List.of(detail("1000.00")));
        order.setSum(BigDecimal.ZERO);
        when(reviewRepository.getAllByOrderId(91L)).thenReturn(List.of(boundaryReview()));

        assertThrows(ResponseStatusException.class, () -> service.ensureOrderCompletionAccrual(91L));

        verify(markerRepository, never()).save(any());
        verify(zpRepository, never()).save(any());
    }

    @Test
    void staleCanonicalSumFailsBeforeAnyMarker() {
        order.setDetails(List.of(detail("1000.00")));
        order.setSum(new BigDecimal("900.00"));
        when(reviewRepository.getAllByOrderId(91L)).thenReturn(List.of(boundaryReview()));

        assertThrows(ResponseStatusException.class, () -> service.ensureOrderCompletionAccrual(91L));

        verify(markerRepository, never()).save(any());
        verify(zpRepository, never()).save(any());
    }

    @Test
    void postCutoffPartialLegacyIsQuarantinedWithoutNewRowsOrMarkers() {
        order.setDetails(List.of(detail("1000.00")));
        when(reviewRepository.getAllByOrderId(91L)).thenReturn(List.of(boundaryReview()));
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "legacy"))
                .when(legacyRewardGuard).requireNoActiveLegacyAggregate(91L);

        assertThrows(ResponseStatusException.class, () -> service.ensureOrderCompletionAccrual(91L));

        verify(markerRepository, never()).save(any());
        verify(zpRepository, never()).save(any());
        verify(productRewardService, never()).accrueForCompletedOrderLocked(any(), any(), anyBoolean());
    }

    @Test
    void completedBadReviewTaskWithoutDateFailsBeforeAnyMarkerOrReward() {
        order.setDetails(List.of(detail("1000.00")));
        when(reviewRepository.getAllByOrderId(91L)).thenReturn(List.of(boundaryReview()));
        BadReviewTask task = BadReviewTask.builder()
                .id(701L)
                .order(order)
                .status(BadReviewTaskStatus.DONE)
                .price(new BigDecimal("500.00"))
                .completedDate(null)
                .build();
        when(badReviewTaskRepository.findAllByOrderIdAndStatus(91L, BadReviewTaskStatus.DONE))
                .thenReturn(List.of(task));

        assertThrows(ResponseStatusException.class, () -> service.ensureOrderCompletionAccrual(91L));

        verify(markerRepository, never()).save(any());
        verify(zpRepository, never()).save(any());
        verify(productRewardService, never()).accrueForCompletedOrderLocked(any(), any(), anyBoolean());
    }

    @Test
    void nonPositiveAmountInCompletionStatusFailsBeforeAnyMarkerOrReward() {
        OrderStatus status = new OrderStatus();
        status.setTitle("Опубликовано");
        order.setStatus(status);
        order.setStatusChangedAt(LocalDateTime.of(2026, 8, 1, 12, 0));
        order.setAmount(0);

        assertThrows(ResponseStatusException.class, () -> service.ensureOrderCompletionAccrual(91L));

        verify(markerRepository, never()).save(any());
        verify(zpRepository, never()).save(any());
        verify(productRewardService, never()).accrueForCompletedOrderLocked(any(), any(), anyBoolean());
    }

    @Test
    void publishedCountAboveAmountFailsBeforeAnyMarkerOrReward() {
        when(reviewRepository.countPublishedByOrderId(91L)).thenReturn(2);

        assertThrows(ResponseStatusException.class, () -> service.ensureOrderCompletionAccrual(91L));

        verify(markerRepository, never()).save(any());
        verify(zpRepository, never()).save(any());
        verify(productRewardService, never()).accrueForCompletedOrderLocked(any(), any(), anyBoolean());
    }

    @Test
    void publishedReviewWithoutImmutableWorkerFailsBeforeAnyMarkerOrReward() {
        when(reviewRepository.getAllByOrderId(91L)).thenReturn(List.of(boundaryReview()));
        when(attributionService.attributeCompletedBaseWork(order))
                .thenThrow(new ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT,
                        "missing worker"
                ));

        assertThrows(ResponseStatusException.class, () -> service.ensureOrderCompletionAccrual(91L));

        verify(markerRepository, never()).save(any());
        verify(zpRepository, never()).save(any());
        verify(productRewardService, never()).accrueForCompletedOrderLocked(any(), any(), anyBoolean());
    }

    @Test
    void mixedDatedAndUndatedPublishedReviewsFailBeforeAnyMarkerOrReward() {
        order.setAmount(2);
        when(reviewRepository.countPublishedByOrderId(91L)).thenReturn(2);
        Review undated = boundaryReview();
        undated.setId(503L);
        undated.setPublishedDate(null);
        when(reviewRepository.getAllByOrderId(91L)).thenReturn(List.of(boundaryReview(), undated));
        when(attributionService.attributeCompletedBaseWork(order))
                .thenThrow(new ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT,
                        "mixed dates"
                ));

        assertThrows(ResponseStatusException.class, () -> service.ensureOrderCompletionAccrual(91L));

        verify(markerRepository, never()).save(any());
        verify(zpRepository, never()).save(any());
        verify(productRewardService, never()).accrueForCompletedOrderLocked(any(), any(), anyBoolean());
    }

    @Test
    void completedBadReviewTaskWithoutWorkerFailsBeforeAnyMarkerOrReward() {
        when(reviewRepository.getAllByOrderId(91L)).thenReturn(List.of(boundaryReview()));
        BadReviewTask task = BadReviewTask.builder()
                .id(702L)
                .order(order)
                .status(BadReviewTaskStatus.DONE)
                .price(new BigDecimal("500.00"))
                .completedDate(LocalDate.of(2026, 8, 1))
                .worker(null)
                .build();
        when(badReviewTaskRepository.findAllByOrderIdAndStatus(91L, BadReviewTaskStatus.DONE))
                .thenReturn(List.of(task));

        assertThrows(ResponseStatusException.class, () -> service.ensureOrderCompletionAccrual(91L));

        verify(markerRepository, never()).save(any());
        verify(zpRepository, never()).save(any());
        verify(productRewardService, never()).accrueForCompletedOrderLocked(any(), any(), anyBoolean());
    }

    private OrderDetails detail(String price) {
        OrderDetails detail = new OrderDetails();
        detail.setPrice(new BigDecimal(price));
        return detail;
    }

    private Review boundaryReview() {
        return Review.builder()
                .id(502L)
                .publish(true)
                .publishedDate(LocalDate.of(2026, 8, 1))
                .build();
    }

    private Manager manager(Long managerId, Long userId) {
        User user = new User();
        user.setId(userId);
        user.setFio("Менеджер компании");
        user.setCoefficient(new BigDecimal("0.10"));
        Manager manager = new Manager();
        manager.setId(managerId);
        manager.setUser(user);
        return manager;
    }

    private ContractorCompletionRewardMarker marker(String source) {
        return marker(source, LocalDate.of(2026, 8, 1));
    }

    private ContractorCompletionRewardMarker marker(String source, LocalDate occurredOn) {
        ContractorCompletionRewardMarker marker = new ContractorCompletionRewardMarker();
        marker.setOrderId(91L);
        marker.setLogicalSource(source);
        marker.setOccurredOn(occurredOn);
        return marker;
    }
}
