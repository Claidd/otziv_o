package com.hunt.otziv.performers.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.contractor_payments.service.ContractorOrderManagerResolver;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentRuntimeSwitch;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentRolloutStateService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentBusinessClock;
import com.hunt.otziv.contractor_payments.service.ContractorRewardAttributionService;
import com.hunt.otziv.contractor_payments.service.ContractorRewardLedgerService;
import com.hunt.otziv.contractor_payments.repository.ContractorCompletionRewardMarkerRepository;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.model.OrderStatus;
import com.hunt.otziv.p_products.model.Product;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.z_zp.model.Zp;
import com.hunt.otziv.z_zp.repository.ZpRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class PerformerProductRewardZpServiceTest {

    @Mock private AppSettingService appSettingService;
    @Mock private OrderRepository orderRepository;
    @Mock private ReviewRepository reviewRepository;
    @Mock private ZpRepository zpRepository;
    @Mock private ContractorRewardAttributionService attributionService;
    @Mock private ContractorPaymentRolloutStateService rolloutStateService;
    @Mock private ContractorPaymentRuntimeSwitch runtimeSwitch;
    @Mock private ContractorRewardLedgerService ledgerService;
    @Mock private ContractorCompletionRewardMarkerRepository completionMarkerRepository;
    @Mock private ContractorPaymentBusinessClock businessClock;

    private PerformerProductRewardZpService service;
    private Order order;

    @Test
    void paidOrderAccrualAlwaysStartsAnIndependentTransaction() throws Exception {
        Transactional transactional = AnnotatedElementUtils.findMergedAnnotation(
                PerformerProductRewardZpService.class.getMethod("accrueForPaidOrder", Long.class),
                Transactional.class
        );

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @BeforeEach
    void setUp() {
        service = new PerformerProductRewardZpService(
                appSettingService,
                orderRepository,
                reviewRepository,
                zpRepository,
                attributionService,
                rolloutStateService,
                runtimeSwitch,
                ledgerService,
                completionMarkerRepository,
                businessClock,
                new ContractorOrderManagerResolver()
        );
        order = paidOrder();
        lenient().when(appSettingService.getBoolean(AppSettingService.ZP_PRODUCT_REWARD_PERCENT_ENABLED, false))
                .thenReturn(true);
        lenient().when(zpRepository.existsByOrderIdAndSourceAndActiveTrue(91L, PerformerProductRewardZpService.SOURCE))
                .thenReturn(false);
        lenient().when(orderRepository.findByIdForCounterUpdate(91L)).thenReturn(Optional.of(order));
        lenient().when(orderRepository.findByIdForOrderDto(91L)).thenReturn(Optional.of(order));
        lenient().when(reviewRepository.getAllByOrderId(91L)).thenAnswer(ignored -> order.getDetails().stream()
                .flatMap(detail -> detail.getReviews() == null
                        ? java.util.stream.Stream.<Review>empty()
                        : detail.getReviews().stream())
                .toList());
        lenient().when(zpRepository.save(any(Zp.class))).thenAnswer(invocation -> {
            Zp saved = invocation.getArgument(0);
            saved.setId(501L);
            return saved;
        });
    }

    @Test
    void legacySourceRemainsReattributableAfterLiveFlagIsOff() {
        when(runtimeSwitch.rewardAttributionLiveEnabled()).thenReturn(false);

        assertThat(service.accrueForPaidOrder(91L)).isEqualTo(1);

        ArgumentCaptor<Zp> reward = ArgumentCaptor.forClass(Zp.class);
        verify(zpRepository).save(reward.capture());
        assertThat(reward.getValue().isAttributionFinal()).isFalse();
        assertThat(reward.getValue().getAttributionSnapshot())
                .isEqualTo("v1|27,17,1000,1,0");
        verify(ledgerService).synchronizeSourcesSafely(List.of(reward.getValue()));
    }

    @Test
    void shadowRewardPersistsAttributionBeforeMutableOrderStateCanChange() {
        when(runtimeSwitch.rewardAttributionLiveEnabled()).thenReturn(false);
        User secondUser = new User();
        secondUser.setId(18L);
        secondUser.setCoefficient(new BigDecimal("0.7"));
        when(attributionService.attribute(order, order.getSum())).thenReturn(List.of(
                new ContractorRewardAttributionService.SpecialistShare(
                        order.getWorker().getUser(), 27L, new BigDecimal("60"), 6
                ),
                new ContractorRewardAttributionService.SpecialistShare(
                        secondUser, 28L, new BigDecimal("40"), 4
                )
        ));

        service.accrueForPaidOrder(91L);

        ArgumentCaptor<Zp> reward = ArgumentCaptor.forClass(Zp.class);
        verify(zpRepository).save(reward.capture());
        assertThat(reward.getValue().getAttributionSnapshot())
                .isEqualTo("v1|27,17,60,6,0;28,18,40,4,0.7");
        assertThat(reward.getValue().getUserId()).isEqualTo(17L);
        assertThat(reward.getValue().getProfessionId()).isEqualTo(27L);
    }

    @Test
    void livePaymentCallbackNeverCreatesPaidOnlyDuplicate() {
        when(runtimeSwitch.rewardAttributionLiveEnabled()).thenReturn(true);

        assertThat(service.accrueForPaidOrder(91L)).isZero();

        verify(zpRepository, never()).save(any(Zp.class));
        verify(ledgerService, never()).synchronizeSourcesSafely(any());
    }

    @Test
    void disabledCompletionRewardStillFreezesLogicalSource() {
        when(runtimeSwitch.rewardAttributionLiveEnabled()).thenReturn(true);
        when(appSettingService.getBoolean(AppSettingService.ZP_PRODUCT_REWARD_PERCENT_ENABLED, false))
                .thenReturn(false);

        assertThat(service.accrueForCompletedOrderLocked(
                order,
                LocalDate.of(2026, 8, 7),
                false
        )).isZero();

        verify(zpRepository, never()).save(any());
        ArgumentCaptor<com.hunt.otziv.contractor_payments.model.ContractorCompletionRewardMarker> marker =
                ArgumentCaptor.forClass(
                        com.hunt.otziv.contractor_payments.model.ContractorCompletionRewardMarker.class
                );
        verify(completionMarkerRepository).save(marker.capture());
        assertThat(marker.getValue().getLogicalSource())
                .isEqualTo(PerformerProductRewardZpService.COMPLETION_SOURCE);
    }

    @Test
    void orderMutexIsAcquiredBeforeCrossNodeExistsDecision() {
        when(runtimeSwitch.rewardAttributionLiveEnabled()).thenReturn(false);

        service.accrueForPaidOrder(91L);

        var order = inOrder(orderRepository, zpRepository);
        order.verify(orderRepository).findByIdForCounterUpdate(91L);
        order.verify(zpRepository).existsByOrderIdAndSourceAndActiveTrue(
                91L, PerformerProductRewardZpService.SOURCE
        );
        order.verify(zpRepository).save(any(Zp.class));
    }

    @Test
    void oneKopeckSpecialistRewardIsConservedByLargestRemainderSplit() {
        order.getDetails().getFirst().setPrice(new BigDecimal("0.10"));
        when(runtimeSwitch.rewardAttributionLiveEnabled()).thenReturn(true);
        Worker secondWorker = worker(28L, 18L, "Второй специалист");
        OrderDetails detail = order.getDetails().getFirst();
        detail.setAmount(2);
        detail.setReviews(List.of(
                publishedReview(601L, detail, order.getWorker(), BigDecimal.ONE),
                publishedReview(602L, detail, secondWorker, BigDecimal.ONE)
        ));

        LocalDate occurredOn = LocalDate.of(2026, 8, 7);
        assertThat(service.accrueForCompletedOrderLocked(order, occurredOn, false)).isEqualTo(1);

        ArgumentCaptor<Zp> rewards = ArgumentCaptor.forClass(Zp.class);
        verify(zpRepository).save(rewards.capture());
        assertThat(rewards.getValue().getSum()).isEqualByComparingTo("0.01");
        assertThat(rewards.getValue().getSource()).isEqualTo(PerformerProductRewardZpService.COMPLETION_SOURCE);
        assertThat(rewards.getValue().isAttributionFinal()).isTrue();
        assertThat(rewards.getValue().getCreated()).isEqualTo(occurredOn);
        verify(ledgerService, never()).synchronizeSourcesSafely(any());
    }

    @Test
    void completionThenPaidCallbackStillPersistsOnlyOneSource() {
        when(runtimeSwitch.rewardAttributionLiveEnabled()).thenReturn(true);
        LocalDate occurredOn = LocalDate.of(2026, 8, 7);

        assertThat(service.accrueForCompletedOrderLocked(order, occurredOn, false)).isEqualTo(1);
        assertThat(service.accrueForPaidOrder(91L)).isZero();

        ArgumentCaptor<Zp> reward = ArgumentCaptor.forClass(Zp.class);
        verify(zpRepository).save(reward.capture());
        assertThat(reward.getValue().getSource()).isEqualTo(PerformerProductRewardZpService.COMPLETION_SOURCE);
        assertThat(reward.getValue().isAttributionFinal()).isTrue();
    }

    @Test
    void completionProductManagerRewardUsesSameCompanyFallback() {
        when(runtimeSwitch.rewardAttributionLiveEnabled()).thenReturn(true);
        Product product = order.getDetails().getFirst().getProduct();
        product.setSpecialistRewardPercent(BigDecimal.ZERO);
        product.setManagerRewardPercent(new BigDecimal("10"));
        User managerUser = new User();
        managerUser.setId(19L);
        managerUser.setFio("Менеджер компании");
        Manager companyManager = new Manager();
        companyManager.setId(29L);
        companyManager.setUser(managerUser);
        Company company = new Company();
        company.setManager(companyManager);
        order.setCompany(company);

        assertThat(service.accrueForCompletedOrderLocked(
                order,
                LocalDate.of(2026, 8, 7),
                false
        )).isEqualTo(1);

        ArgumentCaptor<Zp> reward = ArgumentCaptor.forClass(Zp.class);
        verify(zpRepository).save(reward.capture());
        assertThat(reward.getValue().getUserId()).isEqualTo(19L);
        assertThat(reward.getValue().getProfessionId()).isEqualTo(29L);
        assertThat(reward.getValue().getSum()).isEqualByComparingTo("100.00");
    }

    @Test
    void ordinaryProductWorkerDoesNotReceiveRewardBearingProductBonus() {
        when(runtimeSwitch.rewardAttributionLiveEnabled()).thenReturn(true);
        Worker other = worker(28L, 18L, "Другой специалист");
        OrderDetails rewardDetail = order.getDetails().getFirst();
        Product ordinaryProduct = new Product();
        ordinaryProduct.setRequiresPerformer(false);
        OrderDetails ordinaryDetail = new OrderDetails();
        ordinaryDetail.setProduct(ordinaryProduct);
        ordinaryDetail.setPrice(new BigDecimal("500"));
        ordinaryDetail.setAmount(1);
        ordinaryDetail.setReviews(List.of(publishedReview(603L, ordinaryDetail, other, BigDecimal.ONE)));
        order.setDetails(List.of(rewardDetail, ordinaryDetail));

        service.accrueForCompletedOrderLocked(order, LocalDate.of(2026, 8, 7), false);

        ArgumentCaptor<Zp> reward = ArgumentCaptor.forClass(Zp.class);
        verify(zpRepository).save(reward.capture());
        assertThat(reward.getValue().getProfessionId()).isEqualTo(27L);
        assertThat(reward.getValue().getSum()).isEqualByComparingTo("100.00");
    }

    @Test
    void twoWorkersOnRewardBearingDetailReceivePriceWeightedDeterministicShares() {
        when(runtimeSwitch.rewardAttributionLiveEnabled()).thenReturn(true);
        Worker second = worker(28L, 18L, "Второй специалист");
        OrderDetails detail = order.getDetails().getFirst();
        detail.setAmount(2);
        detail.setReviews(List.of(
                publishedReview(604L, detail, order.getWorker(), new BigDecimal("30")),
                publishedReview(605L, detail, second, new BigDecimal("70"))
        ));

        assertThat(service.accrueForCompletedOrderLocked(
                order,
                LocalDate.of(2026, 8, 7),
                false
        )).isEqualTo(2);

        ArgumentCaptor<Zp> rewards = ArgumentCaptor.forClass(Zp.class);
        verify(zpRepository, org.mockito.Mockito.times(2)).save(rewards.capture());
        assertThat(rewards.getAllValues())
                .extracting(Zp::getProfessionId, Zp::getSum)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(27L, new BigDecimal("30.00")),
                        org.assertj.core.groups.Tuple.tuple(28L, new BigDecimal("70.00"))
                );
    }

    @Test
    void missingPublishedWorkerFailsWithoutMarkerOrReward() {
        when(runtimeSwitch.rewardAttributionLiveEnabled()).thenReturn(true);
        OrderDetails detail = order.getDetails().getFirst();
        detail.setReviews(List.of(publishedReview(606L, detail, null, BigDecimal.ONE)));

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> service.accrueForCompletedOrderLocked(order, LocalDate.of(2026, 8, 7), false)
        );

        verify(zpRepository, never()).save(any());
        verify(completionMarkerRepository, never()).save(any());
    }

    @Test
    void excessPublishedReviewsFailWithoutMarkerOrReward() {
        when(runtimeSwitch.rewardAttributionLiveEnabled()).thenReturn(true);
        OrderDetails detail = order.getDetails().getFirst();
        detail.setAmount(1);
        detail.setReviews(List.of(
                publishedReview(607L, detail, order.getWorker(), BigDecimal.ONE),
                publishedReview(608L, detail, order.getWorker(), BigDecimal.ONE)
        ));

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> service.accrueForCompletedOrderLocked(order, LocalDate.of(2026, 8, 7), false)
        );

        verify(zpRepository, never()).save(any());
        verify(completionMarkerRepository, never()).save(any());
    }

    private Order paidOrder() {
        User user = new User();
        user.setId(17L);
        user.setFio("Специалист");
        Worker worker = new Worker();
        worker.setId(27L);
        worker.setUser(user);

        Product product = new Product();
        product.setRequiresPerformer(true);
        product.setSpecialistRewardPercent(new BigDecimal("10"));
        product.setManagerRewardPercent(BigDecimal.ZERO);
        OrderDetails detail = new OrderDetails();
        detail.setProduct(product);
        detail.setPrice(new BigDecimal("1000"));
        detail.setAmount(1);
        detail.setReviews(List.of(publishedReview(600L, detail, worker, BigDecimal.ONE)));

        OrderStatus status = new OrderStatus();
        status.setTitle("Оплачено");
        Order value = new Order();
        value.setId(91L);
        value.setSum(new BigDecimal("1000"));
        value.setWorker(worker);
        value.setStatus(status);
        value.setDetails(List.of(detail));
        return value;
    }

    private Worker worker(Long workerId, Long userId, String fio) {
        User user = new User();
        user.setId(userId);
        user.setFio(fio);
        Worker worker = new Worker();
        worker.setId(workerId);
        worker.setUser(user);
        return worker;
    }

    private Review publishedReview(
            Long id,
            OrderDetails detail,
            Worker worker,
            BigDecimal price
    ) {
        return Review.builder()
                .id(id)
                .publish(true)
                .orderDetails(detail)
                .worker(worker)
                .price(price)
                .build();
    }
}
