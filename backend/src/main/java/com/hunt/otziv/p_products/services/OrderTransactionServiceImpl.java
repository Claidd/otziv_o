package com.hunt.otziv.p_products.services;

import com.hunt.otziv.bad_reviews.dto.BadReviewTaskSummary;
import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.c_companies.services.CompanyStatusService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.next_order.NextOrderRequestService;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderStatusService;
import com.hunt.otziv.p_products.services.service.OrderTransactionService;
import com.hunt.otziv.z_zp.services.PaymentCheckService;
import com.hunt.otziv.z_zp.services.ZpService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

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
    private final NextOrderRequestService nextOrderRequestService;

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
            NextOrderRequestService nextOrderRequestService
    ) {
        this.companyService = companyService;
        this.zpService = zpService;
        this.paymentCheckService = paymentCheckService;
        this.orderRepository = orderRepository;
        this.companyStatusService = companyStatusService;
        this.orderStatusService = orderStatusService;
        this.badReviewTaskService = badReviewTaskService;
        this.nextOrderRequestService = nextOrderRequestService;
    }

    @Override
    @Transactional
    public boolean handlePaymentStatus(Order order) throws Exception {
        if (!order.isComplete() && order.getCounter() >= order.getAmount()) {
            log.info("Заказ не выполнен и счетчик достиг плана");

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

            if (zpService.save(order, payableSum, payableAmount)) {
                log.info("Сохранили ЗП");
                paymentCheckService.save(order, payableSum);
                log.info("Сохранили чек");

                Company company = companyService.getCompaniesById(order.getCompany().getId());
                company.setCounterPay(company.getCounterPay() + payableAmount);
                company.setSumTotal(safeMoney(company.getSumTotal()).add(payableSum));

                order.setComplete(true);
                order.setPayDay(LocalDate.now());

                orderRepository.save(order);
                companyService.save(checkStatusToCompany(company));
                badReviewTaskService.cancelPendingTasksForOrder(order);
                nextOrderRequestService.openForPaidOrder(order);
            } else {
                log.error("Проблемы при сохранении ЗП");
            }
        }

        order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_PAYMENT));
        orderRepository.save(order);
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
