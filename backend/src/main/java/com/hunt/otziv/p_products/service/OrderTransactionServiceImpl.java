package com.hunt.otziv.p_products.service;

import com.hunt.otziv.bad_reviews.dto.BadReviewTaskSummary;
import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.service.CompanyService;
import com.hunt.otziv.c_companies.service.CompanyStatusService;
import com.hunt.otziv.config.metrics.R0ObservabilityMetrics;
import com.hunt.otziv.contractor_payments.service.ContractorCompletionRewardService;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAccountingAuthority;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentRolloutStateService;
import com.hunt.otziv.gamification.service.GamificationEventService;
import com.hunt.otziv.mobile_push.service.MobilePushBusinessNotificationService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.next_order.service.NextOrderFailureNotifier;
import com.hunt.otziv.p_products.next_order.service.NextOrderRequestService;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.service.OrderStatusService;
import com.hunt.otziv.p_products.service.OrderTransactionService;
import com.hunt.otziv.z_zp.service.PaymentCheckService;
import com.hunt.otziv.z_zp.service.ZpService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.CaughtFailureStage.OPEN_NEXT_ORDER;
import static com.hunt.otziv.config.metrics.R0ObservabilityMetrics.TransactionFlow.ORDER_PAYMENT;

@Service
@Slf4j
public class OrderTransactionServiceImpl implements OrderTransactionService {

    private final CompanyService companyService;
    private final ZpService zpService;
    private final PaymentCheckService paymentCheckService;
    private final OrderRepository orderRepository;
    private final CompanyStatusService companyStatusService;
    private final OrderStatusService orderStatusService;
    private final BadReviewTaskService badReviewTaskService;
    private final NextOrderFailureNotifier nextOrderFailureNotifier;
    private final NextOrderRequestService nextOrderRequestService;
    private final MobilePushBusinessNotificationService mobilePushBusinessNotificationService;
    private final GamificationEventService gamificationEventService;
    private final R0ObservabilityMetrics observabilityMetrics;
    private final ContractorPaymentRolloutStateService contractorPaymentRolloutStateService;
    private final ContractorCompletionRewardService contractorCompletionRewardService;

    public static final String STATUS_PAYMENT = "Оплачено";
    public static final String STATUS_COMPANY_IN_NEW_ORDER = "Новый заказ";

    public OrderTransactionServiceImpl(
            CompanyService companyService,
            ZpService zpService,
            PaymentCheckService paymentCheckService,
            OrderRepository orderRepository,
            CompanyStatusService companyStatusService,
            OrderStatusService orderStatusService,
            BadReviewTaskService badReviewTaskService,
            NextOrderFailureNotifier nextOrderFailureNotifier,
            NextOrderRequestService nextOrderRequestService,
            MobilePushBusinessNotificationService mobilePushBusinessNotificationService,
            GamificationEventService gamificationEventService,
            R0ObservabilityMetrics observabilityMetrics,
            ContractorPaymentRolloutStateService contractorPaymentRolloutStateService,
            ContractorCompletionRewardService contractorCompletionRewardService
    ) {
        this.companyService = companyService;
        this.zpService = zpService;
        this.paymentCheckService = paymentCheckService;
        this.orderRepository = orderRepository;
        this.companyStatusService = companyStatusService;
        this.orderStatusService = orderStatusService;
        this.badReviewTaskService = badReviewTaskService;
        this.nextOrderFailureNotifier = nextOrderFailureNotifier;
        this.nextOrderRequestService = nextOrderRequestService;
        this.mobilePushBusinessNotificationService = mobilePushBusinessNotificationService;
        this.gamificationEventService = gamificationEventService;
        this.observabilityMetrics = observabilityMetrics;
        this.contractorPaymentRolloutStateService = contractorPaymentRolloutStateService;
        this.contractorCompletionRewardService = contractorCompletionRewardService;
    }

    @Override
    @Transactional
    public boolean handlePaymentStatus(Order order) throws Exception {
        return handlePaymentStatus(order, true);
    }

    @Override
    @Transactional
    public boolean handlePaymentStatus(Order order, boolean createNextOrder) throws Exception {
        observabilityMetrics.observeTransactionCompletion(ORDER_PAYMENT);
        if (order == null || order.getId() == null) {
            throw new IllegalArgumentException("Для зачисления оплаты нужен заказ с ID");
        }
        order = orderRepository.findByIdForCounterUpdate(order.getId())
                .orElseThrow(() -> new IllegalStateException("Заказ исчез во время зачисления оплаты"));
        boolean wasAlreadyPaid = order != null
                && order.getStatus() != null
                && STATUS_PAYMENT.equals(order.getStatus().getTitle());
        ContractorPaymentAccountingAuthority accountingAuthority =
                contractorPaymentRolloutStateService.lockAccountingAuthority();
        boolean paymentAccounting = accountingAuthority != null && accountingAuthority.paymentBased();

        if (!wasAlreadyPaid) {
            if (!paymentAccounting && order.getCounter() < order.getAmount()) {
                throw new IllegalStateException("Нельзя оплатить заказ до фактического выполнения всех работ");
            }
            log.info("Первичная фиксация оплаты заказа {}", order.getId());

            BadReviewTaskSummary badReviewSummary = badReviewTaskService.getSummaryForOrder(order.getId());
            BigDecimal baseSum = safeMoney(order.getSum());
            BigDecimal payableSum = baseSum.add(badReviewSummary.doneSum());
            int payableAmount = order.getAmount() + badReviewSummary.done();
            log.info(
                    "Оплата заказа {}: основной заказ {} руб./{} шт., плохие выполнены {} на {} руб., ожидают отмены {}, итого {} руб./{} шт.",
                    order.getId(),
                    baseSum,
                    order.getAmount(),
                    badReviewSummary.done(),
                    badReviewSummary.doneSum(),
                    badReviewSummary.pending(),
                    payableSum,
                    payableAmount
            );

            // Salary rows are protected at database level: an active order
            // accrual may only be inserted while the order is visibly paid.
            // The whole method is transactional, so any later failure rolls
            // this status change back together with the financial writes.
            order.setComplete(true);
            order.setPayDay(LocalDate.now());
            order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_PAYMENT));
            orderRepository.saveAndFlush(order);

            boolean rewardsReady = paymentAccounting
                    || zpService.save(order, payableSum, payableAmount);

            if (rewardsReady) {
                log.info(paymentAccounting
                        ? "Подготовили оплату для канонического начисления"
                        : "Сохранили начисления");
                paymentCheckService.save(order, payableSum);
                log.info("Сохранили чек");

                Company company = companyService.getCompaniesById(order.getCompany().getId());
                company.setCounterPay(company.getCounterPay() + payableAmount);
                company.setSumTotal(safeMoney(company.getSumTotal()).add(payableSum));

                companyService.save(checkStatusToCompany(company));
                badReviewTaskService.cancelPendingTasksForOrder(order);
                if (createNextOrder) {
                    try {
                        nextOrderRequestService.openForPaidOrder(order);
                    } catch (RuntimeException e) {
                        observabilityMetrics.recordCaughtFailure(ORDER_PAYMENT, OPEN_NEXT_ORDER);
                        log.warn("Не удалось открыть заявку на следующий заказ после оплаты заказа {}", order.getId(), e);
                        nextOrderFailureNotifier.notifyManager(order, null, "оплата обычного заказа", e);
                    }
                }
            } else {
                log.error("Проблемы при сохранении начислений");
                throw new IllegalStateException("Не удалось создать начисления по оплаченному заказу");
            }
        }

        // A repeated provider callback or a repeated manual confirmation is a
        // reconciliation request, not a second financial operation. The first
        // transition above writes the check and company totals atomically;
        // later calls may only restore a missing canonical salary row.
        if (paymentAccounting) {
            // The database guard accepts an active order salary only after the
            // paid status is visible. Any reward failure rolls this entire
            // payment transaction back, including status/check/company totals.
            contractorCompletionRewardService.ensureOrderPaymentAccrual(order.getId());
        }
        if (!wasAlreadyPaid) {
            gamificationEventService.recordOrderPaid(order);
            mobilePushBusinessNotificationService.notifyOwnersOrderPaid(order);
        }
        return true;
    }

    private BigDecimal safeMoney(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    @Transactional
    public Company checkStatusToCompany(Company company){
        int result = 0;
        for (Order order1 : company.getOrderList()) {
            if (!order1.isComplete()) {
                result = 1;
                break;
            }
        }
        if (result == 0){
            company.setStatus(companyStatusService.getStatusByTitle(STATUS_COMPANY_IN_NEW_ORDER));
        }
        return company;
    }
}
