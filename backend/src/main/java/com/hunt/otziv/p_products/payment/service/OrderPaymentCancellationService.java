package com.hunt.otziv.p_products.payment.service;

import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.c_companies.service.CompanyService;
import com.hunt.otziv.client_messages.service.PaymentInvoiceRetryScheduler;
import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.contractor_payments.service.ContractorCompletionRewardService;
import com.hunt.otziv.contractor_payments.service.ContractorRewardSourceCodes;
import com.hunt.otziv.contractor_payments.service.ContractorRewardLedgerService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentRuntimeSwitch;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAccountingAuthority;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentRolloutStateService;
import com.hunt.otziv.contractor_payments.service.ContractorRouteAssignmentGuard;
import com.hunt.otziv.p_products.deletion.service.OrderDeletionService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.next_order.model.NextOrderRequest;
import com.hunt.otziv.p_products.next_order.repository.NextOrderRequestRepository;
import com.hunt.otziv.p_products.next_order.model.NextOrderRequestStatus;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.service.OrderStatusService;
import com.hunt.otziv.p_products.status.service.OrderCompanyStatusService;
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
            PaymentLinkStatus.AMOUNT_MISMATCH,
            PaymentLinkStatus.NEEDS_RECONCILIATION,
            PaymentLinkStatus.MANUAL_REPORTED
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
    private final CommonBillingService commonBillingService;
    private final ContractorRewardLedgerService contractorRewardLedgerService;
    private final ContractorRouteAssignmentGuard contractorRouteAssignmentGuard;
    private final ContractorCompletionRewardService contractorCompletionRewardService;
    private final ContractorPaymentRolloutStateService rolloutStateService;
    private final ContractorPaymentRuntimeSwitch contractorPaymentRuntimeSwitch;

    @Transactional
    public void cancelPayment(Long orderId, Principal principal) {
        Order order = orderRepository.findByIdForMutation(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден"));

        if (!STATUS_PAYMENT.equals(safeStatusTitle(order))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Отменить оплату можно только у заказа в статусе \"Оплачено\"");
        }
        contractorRouteAssignmentGuard.requirePaymentCancellationAllowed(orderId);
        if (paymentLinkRepository.existsByOrder_IdAndStatusIn(orderId, REAL_PAYMENT_STATUSES)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "У заказа есть подтвержденный или требующий сверки платеж по ссылке. "
                            + "Для него нужна ручная сверка или возврат, а не отмена оплаты в карточке."
            );
        }
        if (commonBillingService.hasClientReportedPaymentForOrder(orderId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Клиент уже сообщил об оплате общего счета. Сначала выполните ручную сверку поступления"
            );
        }

        cancelNextOrderRequest(order, principal);

        order = orderRepository.findByIdForMutation(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден после отката следующего заказа"));

        List<PaymentCheck> activeChecks = paymentCheckRepository.findByOrderIdAndActiveTrue(orderId);
        List<Zp> activeZp = zpRepository.findByOrderIdAndActiveTrue(orderId);
        List<Zp> paymentDependentZp = List.copyOf(activeZp);
        contractorRewardLedgerService.requireCancellationRepresentable(paymentDependentZp);

        BigDecimal canceledSum = activeChecks.stream()
                .map(PaymentCheck::getSum)
                .filter(sum -> sum != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int canceledAmount = paymentDependentZp.stream()
                .map(Zp::getAmount)
                .max(Comparator.naturalOrder())
                .orElse(order.getAmount());

        activeChecks.forEach(check -> check.setActive(false));
        paymentCheckRepository.saveAll(activeChecks);
        int deactivatedSalary = contractorCompletionRewardService.deactivateOrderPaymentAccruals(
                orderId,
                "manual_payment_cancellation"
        );

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
                        + ";zp=" + paymentDependentZp.size()
                        + ";salaryDeactivated=" + deactivatedSalary
                        + ";sum=" + canceledSum
                        + ";amount=" + canceledAmount
                        + ";paymentLink=restored"
        );
        log.info(
                "Оплата заказа {} отменена: checks={}, zp={}, sum={}, amount={}",
                orderId,
                activeChecks.size(),
                paymentDependentZp.size(),
                canceledSum,
                canceledAmount
        );
    }

    private void cancelNextOrderRequest(Order sourceOrder, Principal principal) {
        nextOrderRequestRepository.findBySourceOrderId(sourceOrder.getId()).ifPresent(request -> {
            Order createdOrder = request.getCreatedOrder();
            if (createdOrder != null && createdOrder.getId() != null) {
                ensureCreatedOrderCanBeDeleted(createdOrder);
                commonBillingService.detachOrderForDeletion(createdOrder.getId());
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
