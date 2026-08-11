package com.hunt.otziv.contractor_payments.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository;
import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.model.BadReviewTaskStatus;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.contractor_payments.model.ContractorCompletionRewardMarker;
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
                new ContractorOrderManagerResolver()
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
        when(runtimeSwitch.rewardAttributionLiveEnabled()).thenReturn(true);
        when(orderRepository.findByIdForCounterUpdate(91L)).thenReturn(Optional.of(order));
        when(orderRepository.findByIdForOrderDto(91L)).thenReturn(Optional.of(order));
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
        when(zpRepository.existsByOrderIdAndSourceAndActiveTrue(
                91L,
                ContractorRewardSourceCodes.LEGACY_ORDER_MANAGER
        )).thenReturn(true);

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
        ContractorCompletionRewardMarker marker = new ContractorCompletionRewardMarker();
        marker.setOrderId(91L);
        marker.setLogicalSource(source);
        marker.setOccurredOn(LocalDate.of(2026, 8, 1));
        return marker;
    }
}
