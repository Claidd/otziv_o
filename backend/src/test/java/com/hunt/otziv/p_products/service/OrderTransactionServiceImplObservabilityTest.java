package com.hunt.otziv.p_products.service;

import com.hunt.otziv.bad_reviews.dto.BadReviewTaskSummary;
import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.service.CompanyService;
import com.hunt.otziv.c_companies.service.CompanyStatusService;
import com.hunt.otziv.config.metrics.R0ObservabilityMetrics;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentRolloutStateService;
import com.hunt.otziv.contractor_payments.service.ContractorCompletionRewardService;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAccountingAuthority;
import com.hunt.otziv.gamification.service.GamificationEventService;
import com.hunt.otziv.mobile_push.service.MobilePushBusinessNotificationService;
import com.hunt.otziv.p_products.dto.OrderPaidPostCommitEvent;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.next_order.service.NextOrderFailureNotifier;
import com.hunt.otziv.p_products.next_order.service.NextOrderRequestService;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.service.OrderStatusService;
import com.hunt.otziv.z_zp.service.PaymentCheckService;
import com.hunt.otziv.z_zp.service.ZpService;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.TransactionFlow.ORDER_PAYMENT;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderTransactionServiceImplObservabilityTest {

    @Mock private CompanyService companyService;
    @Mock private ZpService zpService;
    @Mock private PaymentCheckService paymentCheckService;
    @Mock private OrderRepository orderRepository;
    @Mock private CompanyStatusService companyStatusService;
    @Mock private OrderStatusService orderStatusService;
    @Mock private BadReviewTaskService badReviewTaskService;
    @Mock private NextOrderFailureNotifier nextOrderFailureNotifier;
    @Mock private NextOrderRequestService nextOrderRequestService;
    @Mock private MobilePushBusinessNotificationService mobilePushBusinessNotificationService;
    @Mock private GamificationEventService gamificationEventService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private R0ObservabilityMetrics observabilityMetrics;
    @Mock private ContractorPaymentRolloutStateService contractorPaymentRolloutStateService;
    @Mock private ContractorCompletionRewardService contractorCompletionRewardService;

    @InjectMocks private OrderTransactionServiceImpl service;

    @Test
    void propagatesNextOrderPersistenceFailureInsteadOfReturningRollbackOnlySuccess() throws Exception {
        Company company = Company.builder()
                .id(20L)
                .counterPay(0)
                .sumTotal(BigDecimal.ZERO)
                .build();
        Order order = Order.builder()
                .id(10L)
                .company(company)
                .amount(1)
                .counter(1)
                .sum(BigDecimal.TEN)
                .complete(false)
                .build();
        company.setOrderList(Set.of(order));

        when(orderRepository.findByIdForCounterUpdate(10L)).thenReturn(Optional.of(order));
        when(badReviewTaskService.getSummaryForOrder(10L)).thenReturn(BadReviewTaskSummary.empty());
        when(zpService.save(eq(order), eq(BigDecimal.TEN), eq(1))).thenReturn(true);
        when(companyService.getCompaniesById(20L)).thenReturn(company);
        doThrow(new IllegalStateException("synthetic failure"))
                .when(nextOrderRequestService).openForPaidOrder(order);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> service.handlePaymentStatus(order, true)
        );

        assertTrue(failure.getMessage().contains("synthetic failure"));
        verify(observabilityMetrics).observeTransactionCompletion(ORDER_PAYMENT);
        verify(orderRepository).saveAndFlush(order);
        verify(nextOrderFailureNotifier, never()).notifyManager(any(), any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void completedFlagCannotSkipFirstPaymentAccounting() throws Exception {
        Company company = Company.builder()
                .id(20L)
                .counterPay(0)
                .sumTotal(BigDecimal.ZERO)
                .build();
        Order order = Order.builder()
                .id(11L)
                .company(company)
                .amount(1)
                .counter(1)
                .sum(BigDecimal.TEN)
                .complete(true)
                .build();
        company.setOrderList(Set.of(order));
        var paidStatus = com.hunt.otziv.p_products.model.OrderStatus.builder()
                .id(7L)
                .title("Оплачено")
                .build();

        when(orderRepository.findByIdForCounterUpdate(11L)).thenReturn(Optional.of(order));
        when(contractorPaymentRolloutStateService.lockAccountingAuthority())
                .thenReturn(ContractorPaymentAccountingAuthority.PAYMENT);
        when(orderStatusService.getOrderStatusByTitle("Оплачено")).thenReturn(paidStatus);
        when(badReviewTaskService.getSummaryForOrder(11L)).thenReturn(BadReviewTaskSummary.empty());
        when(companyService.getCompaniesById(20L)).thenReturn(company);

        assertTrue(service.handlePaymentStatus(order, false));

        verify(paymentCheckService).save(order, BigDecimal.TEN);
        verify(companyService).save(company);
        verify(contractorCompletionRewardService).ensureOrderPaymentAccrual(11L);
        verify(orderRepository).saveAndFlush(order);
        verify(eventPublisher).publishEvent(new OrderPaidPostCommitEvent(11L));
    }

    @Test
    void repeatedPaidCallbackDoesNotRepeatFinancialWrites() throws Exception {
        var paidStatus = com.hunt.otziv.p_products.model.OrderStatus.builder()
                .id(7L)
                .title("Оплачено")
                .build();
        Order order = Order.builder().id(12L).status(paidStatus).complete(true).build();
        when(orderRepository.findByIdForCounterUpdate(12L)).thenReturn(Optional.of(order));
        when(contractorPaymentRolloutStateService.lockAccountingAuthority())
                .thenReturn(ContractorPaymentAccountingAuthority.PAYMENT);

        assertTrue(service.handlePaymentStatus(order, true));

        verify(paymentCheckService, never()).save(any(), any());
        verify(companyService, never()).save(any(Company.class));
        verify(orderRepository, never()).saveAndFlush(any());
        verify(contractorCompletionRewardService).ensureOrderPaymentAccrual(12L);
        verify(eventPublisher, never()).publishEvent(any());
    }
}
