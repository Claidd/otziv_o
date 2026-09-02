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
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.service.PaymentLinkService;
import com.hunt.otziv.payments.service.PaymentReturnRecoveryState;
import com.hunt.otziv.z_zp.model.PaymentCheck;
import com.hunt.otziv.z_zp.model.Zp;
import com.hunt.otziv.z_zp.repository.PaymentCheckRepository;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import java.util.Objects;
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
            PaymentLinkStatus.PARTIAL_REVERSED,
            PaymentLinkStatus.PARTIAL_REFUNDED,
            PaymentLinkStatus.NEEDS_RECONCILIATION,
            PaymentLinkStatus.MANUAL_REPORTED
    );

    private final OrderRepository orderRepository;
    private final OrderStatusService orderStatusService;
    private final OrderCompanyStatusService orderCompanyStatusService;
    private final CompanyService companyService;
    private final PaymentCheckRepository paymentCheckRepository;
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
        Order order = orderRepository.findByIdForCounterUpdate(orderId)
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

        List<PaymentCheck> activeChecks = paymentCheckRepository.findByOrderIdAndActiveTrue(orderId);
        PaymentCheck activeCheck = requireSingleActivePaymentCheck(order, activeChecks);
        boolean returnedSourceMarked = markExactReturnedSourceProcessed(order, activeCheck);
        List<Zp> paymentDependentZp =
                contractorRewardLedgerService.lockActiveOrderRewardsAndRequireCancellationRepresentable(orderId);

        BigDecimal canceledSum = activeCheck.getSum();
        int canceledAmount = requirePaidAmountSnapshot(activeCheck);
        requireCompanyRollback(order, canceledSum, canceledAmount);

        cancelNextOrderRequest(order, principal);

        order = orderRepository.findByIdForCounterUpdate(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден после отката следующего заказа"));
        if (!STATUS_PAYMENT.equals(safeStatusTitle(order))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Статус заказа изменился во время отмены оплаты");
        }
        Company company = requireCompanyRollback(order, canceledSum, canceledAmount);

        activeCheck.setActive(false);
        paymentCheckRepository.save(activeCheck);
        // Active payment_check rows have an FK to the paid status. Flush their
        // deactivation before changing the order to Reminder.
        orderRepository.flush();
        int deactivatedSalary = contractorCompletionRewardService.deactivateOrderPaymentAccruals(
                orderId,
                "manual_payment_cancellation"
        );

        applyCompanyRollback(order, company, canceledSum, canceledAmount);

        String oldStatus = safeStatusTitle(order);
        order.setComplete(false);
        order.setPayDay(null);
        order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_REMINDER));
        orderRepository.save(order);
        orderCompanyStatusService.autoManageCompanyStatus(order, STATUS_REMINDER);
        restorePaymentLink(order);
        paymentInvoiceRetryScheduler.scheduleInitialInvoice(order);

        businessAuditService.recordRequiredInCurrentTransaction(
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
                        + ";returnedSourceMarked=" + returnedSourceMarked
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

    private PaymentCheck requireSingleActivePaymentCheck(Order order, List<PaymentCheck> activeChecks) {
        List<PaymentCheck> checks = activeChecks == null ? List.of() : activeChecks;
        if (checks.size() != 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Отмена оплаты остановлена: активных чеков " + checks.size() + " вместо 1"
            );
        }
        PaymentCheck check = checks.getFirst();
        Long companyId = order.getCompany() == null ? null : order.getCompany().getId();
        Long statusId = order.getStatus() == null ? null : order.getStatus().getId();
        if (!check.isActive()
                || !java.util.Objects.equals(check.getOrderId(), order.getId())
                || check.getSum() == null
                || check.getSum().signum() < 0
                || companyId == null
                || !java.util.Objects.equals(check.getCompanyId(), companyId)
                || statusId == null
                || !java.util.Objects.equals(check.getPaymentStatusGuard(), statusId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Отмена оплаты остановлена: активный чек не согласован с заказом"
            );
        }
        return check;
    }

    /**
     * A provider return may arrive before its outbox worker. If this manual
     * cancellation is already applying the exact returned check, fence that
     * source in the same transaction so the later worker is a financial no-op.
     * Lock order stays order -> exact payment link, matching return recovery.
     */
    private boolean markExactReturnedSourceProcessed(Order order, PaymentCheck activeCheck) {
        Long paymentLinkId = activeCheck.getPaymentLinkId();
        if (paymentLinkId == null) {
            return false;
        }
        PaymentLink source = paymentLinkRepository.findByIdForUpdate(paymentLinkId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Отмена оплаты остановлена: exact source платежного чека не найден"
                ));
        Long sourceOrderId = source.getOrder() == null ? null : source.getOrder().getId();
        if (!Objects.equals(order.getId(), sourceOrderId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Отмена оплаты остановлена: exact source платежного чека относится к другому заказу"
            );
        }
        if (!PaymentReturnRecoveryState.isFullReturn(source.getStatus())
                || !PaymentReturnRecoveryState.hasLinkSpecificSettledEvidence(source)) {
            return false;
        }
        if (!PaymentReturnRecoveryState.isValidMarkerTuple(source)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Отмена оплаты остановлена: маркер возврата exact source поврежден"
            );
        }
        if (PaymentReturnRecoveryState.isMarkerEmpty(source)) {
            PaymentReturnRecoveryState.markProcessed(
                    source,
                    activeCheck.getId(),
                    PaymentReturnRecoveryState.OUTCOME_APPLIED
            );
            paymentLinkRepository.save(source);
            return true;
        }
        if (PaymentReturnRecoveryState.OUTCOME_APPLIED.equals(source.getReturnRecoveryOutcome())
                && Objects.equals(activeCheck.getId(), source.getReturnRecoveryPaymentCheckId())) {
            return true;
        }
        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Отмена оплаты остановлена: возврат exact source уже обработан с другим исходом"
        );
    }

    private Company requireCompanyRollback(Order order, BigDecimal canceledSum, int canceledAmount) {
        if (order.getCompany() == null || order.getCompany().getId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "У оплаченного заказа не определена компания");
        }

        Company company = companyService.getCompaniesById(order.getCompany().getId());
        if (company == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Компания оплаченного заказа не найдена");
        }
        if (canceledAmount < 0
                || company.getCounterPay() < canceledAmount
                || safeMoney(company.getSumTotal()).compareTo(canceledSum) < 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Итоги компании не согласованы с отменяемой оплатой"
            );
        }
        return company;
    }

    private int requirePaidAmountSnapshot(PaymentCheck activeCheck) {
        Integer paidAmount = activeCheck == null ? null : activeCheck.getPaidAmount();
        if (paidAmount == null || paidAmount < 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Отмена оплаты остановлена: в чеке нет достоверного снимка количества оплаченных работ"
            );
        }
        return paidAmount;
    }

    private void applyCompanyRollback(
            Order order,
            Company company,
            BigDecimal canceledSum,
            int canceledAmount
    ) {
        company.setCounterPay(company.getCounterPay() - canceledAmount);
        company.setSumTotal(safeMoney(company.getSumTotal()).subtract(canceledSum));
        companyService.save(company);
        order.setCompany(company);
    }

    private BigDecimal safeMoney(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

}
