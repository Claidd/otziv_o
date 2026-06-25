package com.hunt.otziv.p_products.payment;

import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.services.CompanyService;
import com.hunt.otziv.client_messages.service.PaymentInvoiceRetryScheduler;
import com.hunt.otziv.p_products.deletion.OrderDeletionService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.next_order.NextOrderRequest;
import com.hunt.otziv.p_products.next_order.NextOrderRequestRepository;
import com.hunt.otziv.p_products.next_order.NextOrderRequestStatus;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderStatusService;
import com.hunt.otziv.p_products.status.OrderCompanyStatusService;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.service.PaymentLinkService;
import com.hunt.otziv.z_zp.model.PaymentCheck;
import com.hunt.otziv.z_zp.model.Zp;
import com.hunt.otziv.z_zp.repository.PaymentCheckRepository;
import com.hunt.otziv.z_zp.repository.ZpRepository;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static com.hunt.otziv.p_products.utils.OrderReviewGraph.safeStatusTitle;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderPaymentCancellationService {

    private static final String STATUS_PAYMENT = "Оплачено";
    private static final String STATUS_REMINDER = "Напоминание";
    private static final String STATUS_NEW = "Новый";
    private static final Set<PaymentLinkStatus> REAL_PAYMENT_STATUSES = Set.of(
            PaymentLinkStatus.AUTHORIZED,
            PaymentLinkStatus.TEST_CONFIRMED,
            PaymentLinkStatus.CONFIRMED,
            PaymentLinkStatus.AMOUNT_MISMATCH
    );

    private final OrderRepository orderRepository;
    private final OrderStatusService orderStatusService;
    private final OrderCompanyStatusService orderCompanyStatusService;
    private final CompanyService companyService;
    private final PaymentCheckRepository paymentCheckRepository;
    private final ZpRepository zpRepository;
    private final NextOrderRequestRepository nextOrderRequestRepository;
    private final OrderDeletionService orderDeletionService;
    private final PaymentLinkRepository paymentLinkRepository;
    private final PaymentLinkService paymentLinkService;
    private final PaymentInvoiceRetryScheduler paymentInvoiceRetryScheduler;
    private final BusinessAuditService businessAuditService;

    @Transactional
    public void cancelPayment(Long orderId, Principal principal) {
        Order order = orderRepository.findByIdForMutation(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден"));

        if (!STATUS_PAYMENT.equals(safeStatusTitle(order))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Отменить оплату можно только у заказа в статусе \"Оплачено\"");
        }
        if (paymentLinkRepository.existsByOrder_IdAndStatusIn(orderId, REAL_PAYMENT_STATUSES)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "У заказа есть подтвержденный платеж по ссылке. Для него нужна ручная сверка или возврат, а не отмена оплаты в карточке."
            );
        }

        cancelNextOrderRequest(order, principal);

        order = orderRepository.findByIdForMutation(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден после отката следующего заказа"));

        List<PaymentCheck> activeChecks = paymentCheckRepository.findByOrderIdAndActiveTrue(orderId);
        List<Zp> activeZp = zpRepository.findByOrderIdAndActiveTrue(orderId);

        BigDecimal canceledSum = activeChecks.stream()
                .map(PaymentCheck::getSum)
                .filter(sum -> sum != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int canceledAmount = activeZp.stream()
                .map(Zp::getAmount)
                .max(Comparator.naturalOrder())
                .orElse(order.getAmount());

        activeChecks.forEach(check -> check.setActive(false));
        activeZp.forEach(zp -> zp.setActive(false));
        paymentCheckRepository.saveAll(activeChecks);
        zpRepository.saveAll(activeZp);

        rollbackCompanyTotals(order, canceledSum, canceledAmount);

        String oldStatus = safeStatusTitle(order);
        order.setComplete(false);
        order.setPayDay(null);
        order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_REMINDER));
        orderRepository.save(order);
        orderCompanyStatusService.autoManageCompanyStatus(order, STATUS_REMINDER);
        restorePaymentLink(order);
        paymentInvoiceRetryScheduler.scheduleInitialInvoice(order);

        businessAuditService.recordSafely(
                "order_payment_canceled",
                "order",
                order.getId(),
                order.getId(),
                null,
                oldStatus,
                STATUS_REMINDER,
                "checks=" + activeChecks.size()
                        + ";zp=" + activeZp.size()
                        + ";sum=" + canceledSum
                        + ";amount=" + canceledAmount
                        + ";paymentLink=restored"
        );
        log.info(
                "Оплата заказа {} отменена: checks={}, zp={}, sum={}, amount={}",
                orderId,
                activeChecks.size(),
                activeZp.size(),
                canceledSum,
                canceledAmount
        );
    }

    private void cancelNextOrderRequest(Order sourceOrder, Principal principal) {
        nextOrderRequestRepository.findBySourceOrderId(sourceOrder.getId()).ifPresent(request -> {
            Order createdOrder = request.getCreatedOrder();
            if (createdOrder != null && createdOrder.getId() != null) {
                ensureCreatedOrderCanBeDeleted(createdOrder);
                boolean deleted = orderDeletionService.deleteOrder(createdOrder.getId(), principal);
                if (!deleted) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Автосозданный следующий заказ не удалось удалить");
                }
                return;
            }

            request.setStatus(NextOrderRequestStatus.CANCELED);
            request.setErrorMessage("Оплата исходного заказа " + sourceOrder.getId() + " отменена");
            nextOrderRequestRepository.save(request);
        });
    }

    private void restorePaymentLink(Order order) {
        try {
            paymentLinkService.createForOrder(order.getId());
        } catch (ResponseStatusException e) {
            throw new ResponseStatusException(
                    e.getStatusCode(),
                    "Оплата не отменена: не удалось восстановить ссылку на оплату. " + e.getReason(),
                    e
            );
        }
    }

    private void ensureCreatedOrderCanBeDeleted(Order createdOrder) {
        if (!STATUS_NEW.equals(safeStatusTitle(createdOrder))
                || createdOrder.getCounter() > 0
                || createdOrder.isComplete()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Следующий заказ #" + createdOrder.getId() + " уже в работе. Сначала разберите его вручную."
            );
        }
    }

    private void rollbackCompanyTotals(Order order, BigDecimal canceledSum, int canceledAmount) {
        if (order.getCompany() == null || order.getCompany().getId() == null) {
            return;
        }

        Company company = companyService.getCompaniesById(order.getCompany().getId());
        if (company == null) {
            return;
        }

        company.setCounterPay(Math.max(0, company.getCounterPay() - Math.max(0, canceledAmount)));
        company.setSumTotal(nonNegative(safeMoney(company.getSumTotal()).subtract(safeMoney(canceledSum))));
        companyService.save(company);
        order.setCompany(company);
    }

    private BigDecimal safeMoney(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.signum() < 0 ? BigDecimal.ZERO : value;
    }
}
