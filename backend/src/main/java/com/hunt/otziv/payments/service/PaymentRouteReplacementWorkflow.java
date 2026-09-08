package com.hunt.otziv.payments.service;

import com.hunt.otziv.contractor_payments.dto.ContractorPaymentRequisitesSnapshot;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.contractor_payments.service.ContractorActualPaymentAttributionService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentLiveRoutingService;
import com.hunt.otziv.manager.service.ManagerAccessService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.payments.dto.ManagerPaymentLinkResponse;
import com.hunt.otziv.payments.dto.PaymentRouteChangeContextResponse;
import com.hunt.otziv.payments.dto.PaymentRouteChangeTarget;
import com.hunt.otziv.payments.model.ManualPaymentTask;
import com.hunt.otziv.payments.dto.ManualPaymentTaskRouteSnapshot;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.InvoicePaymentMode;
import com.hunt.otziv.payments.model.PaymentProfile;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.u_users.model.Manager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Slf4j
@RequiredArgsConstructor
/** Authorizes and replaces one unpaid order payment route under the existing order/link lock scope. */
public class PaymentRouteReplacementWorkflow {

    private final PaymentLinkAmountPolicy amountPolicy;

    private final PaymentLinkLifecycleService lifecycleService;

    private final PaymentLinkPreparationWorkflow preparationWorkflow;

    private final PaymentLinkPresenter paymentPresenter;

    private final CommonInvoiceRouteSelector commonInvoiceRouteSelector;

    private final PaymentBankObservationService bankObservations;

    private final PaymentLinkRepository paymentLinkRepository;

    private final OrderRepository orderRepository;

    private final PaymentProfileService paymentProfileService;

    private final ManualPaymentTaskReceiptIntegrationService taskReceiptIntegrationService;

    private final OrderPaymentIntegrityService orderPaymentIntegrityService;

    private final ManagerAccessService managerAccessService;

    private final ContractorPaymentLiveRoutingService contractorPaymentLiveRoutingService;

    private final ContractorActualPaymentAttributionService actualPaymentAttributionService;

    @Transactional(readOnly = true)
    public PaymentRouteChangeContextResponse paymentRouteChangeContextAuthorized(Long orderId, Authentication authentication) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден"));
        managerAccessService.requireOrderAccess(orderId, authentication);
        List<PaymentLink> links = paymentLinkRepository.findByOrderIdInForRead(Set.of(orderId));
        PaymentLink current = currentRouteChangeLink(links).orElse(null);
        Long expectedTargetPaymentProfileId = targetPaymentProfileId(order);
        if (current == null) {
            InvoicePaymentMode configuredMode = invoicePaymentMode(order);
            return new PaymentRouteChangeContextResponse(null, configuredPaymentModeLabel(configuredMode), configuredPaymentModeRecipient(configuredMode), "NOT_CREATED", true, "", configuredMode.name(), false, expectedTargetPaymentProfileId);
        }
        String blockReason = routeChangeBlockReason(current);
        String currentRoute = isPaperInvoice(current) ? "Бумажный счёт владельца" : isManualPayment(current) ? "Оплата по реквизитам" : isTochkaPaymentLink(current) ? "Эквайринг · Точка Банк" : "Эквайринг · T‑Bank";
        String currentRecipient = isPaperInvoice(current) ? "Владелец" : isManualPayment(current) ? manualRouteRecipientContext(current, order) : normalize(current.getPaymentProfileName());
        if (currentRecipient.isBlank()) {
            currentRecipient = "Владелец";
        }
        return new PaymentRouteChangeContextResponse(current.getId(), currentRoute, currentRecipient, current.getStatus().name(), blockReason == null, blockReason == null ? "" : blockReason, invoicePaymentMode(order).name(), current.getPaperInvoiceIssuedAt() != null, expectedTargetPaymentProfileId);
    }

    @Transactional
    public PaymentRouteReplacement replacePaymentRouteAuthorized(Long orderId, Long expectedPaymentLinkId, PaymentRouteChangeTarget target, boolean confirmedUnpaid, Long expectedTargetPaymentProfileId, Authentication authentication) {
        if (!confirmedUnpaid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Подтвердите, что клиент еще не оплатил по прежним реквизитам");
        }
        if (target == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный запрос смены способа оплаты");
        }
        if (target == PaymentRouteChangeTarget.OWNER_PAPER_INVOICE) {
            requireOwnerOrAdmin(authentication);
        }
        requirePaymentLinksEnabled();
        Order order = orderRepository.findByIdForCounterUpdate(orderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден"));
        managerAccessService.requireOrderAccess(orderId, authentication);
        ensureOrderNotCoveredByActiveCommonInvoice(orderId);
        orderPaymentIntegrityService.assertPaymentCycleAllowed(order);
        LocalDateTime now = LocalDateTime.now();
        List<PaymentLink> links = paymentLinkRepository.findByOrderIdForUpdate(orderId);
        PaymentLink current = currentRouteChangeLink(links).orElse(null);
        if (current == null) {
            if (expectedPaymentLinkId != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Активный счет уже изменился. Обновите заказ и повторите");
            }
            Manager manager = paymentProfileService.lockManagerForRouting(orderManager(order));
            PaymentProfile profile = target == PaymentRouteChangeTarget.OWNER_PAPER_INVOICE ? null : paymentProfileService.lockForRouting(paymentProfileService.selectForManager(manager));
            validateExpectedTargetPaymentProfile(target, expectedTargetPaymentProfileId, profile);
            InvoicePaymentMode mode = paymentModeForTarget(target);
            order.setInvoicePaymentMode(mode);
            orderRepository.save(order);
            log.info("Invoice payment mode configured before invoice creation: orderId={}, mode={}, actor={}", orderId, mode, authentication == null ? "system" : authentication.getName());
            return new PaymentRouteReplacement(null, null, target, null);
        }
        if (expectedPaymentLinkId == null || expectedPaymentLinkId <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Укажите актуальный счет перед сменой способа оплаты");
        }
        if (!expectedPaymentLinkId.equals(current.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Способ оплаты уже изменен другим пользователем. Обновите заказ");
        }
        String blockReason = routeChangeBlockReason(current);
        if (blockReason != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, blockReason);
        }
        long amountKopecks = amountKopecks(payableSum(order));
        if (amountKopecks <= 0 || amountKopecks != current.getAmountKopecks()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Сумма заказа изменилась. Сначала обновите счет обычным способом");
        }
        // Keep the global mutation order aligned with prepareForOrder:
        // Order -> PaymentLinks -> Manager -> PaymentProfile.  Besides avoiding
        // a deadlock, this freezes the recipient only after the link CAS and
        // payment-evidence checks have succeeded.
        Manager manager = paymentProfileService.lockManagerForRouting(orderManager(order));
        PaymentProfile profile = target == PaymentRouteChangeTarget.OWNER_PAPER_INVOICE ? null : paymentProfileService.lockForRouting(paymentProfileService.selectForManager(manager));
        validateExpectedTargetPaymentProfile(target, expectedTargetPaymentProfileId, profile);
        String replacementReason = "Способ оплаты изменен по просьбе клиента";
        taskReceiptIntegrationService.release(current, replacementReason);
        current.setStatus(PaymentLinkStatus.CANCELED);
        current.setExpiresAt(now);
        current.setLastError(replacementReason);
        paymentLinkRepository.save(current);
        if (current.getContractorAllocationId() != null) {
            contractorPaymentLiveRoutingService.releaseClosedPaymentLink(current);
        }
        PaymentLink replacement = paymentLinkRepository.saveAndFlush(newPaymentLink(order, amountKopecks, now));
        order.setInvoicePaymentMode(paymentModeForTarget(target));
        orderRepository.save(order);
        if (target == PaymentRouteChangeTarget.OWNER_PAPER_INVOICE) {
            applyOwnerPaperInvoiceRoute(replacement);
        } else if (target == PaymentRouteChangeTarget.EMPLOYEE_REQUISITES) {
            Objects.requireNonNull(manager, "manager");
            Objects.requireNonNull(profile, "profile");
            Optional<ManualPaymentTaskRouteSnapshot> taskRoute = actualPaymentAttributionService.actualRecipientAccountingEnabled() ? taskReceiptIntegrationService.reserveForPaymentLink(replacement, manager.getId(), profile.getId()) : Optional.empty();
            if (taskRoute.isPresent()) {
                applyManualTaskPayment(replacement, taskRoute.get());
            } else {
                requireLiveRouteChangeEnabled();
                prepareLivePaymentLinkSourceOrFail(replacement, now);
                ContractorPaymentAllocation allocation = requireLiveRoutingAllocation(replacement, contractorPaymentLiveRoutingService.reserveContractorForPaymentLink(replacement), "route_change_contractor_returned_null");
                if (!isContractorRecipient(allocation)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Не удалось выбрать специалиста или менеджера для оплаты");
                }
                applyContractorPaymentRoute(replacement, allocation);
            }
        } else {
            Objects.requireNonNull(profile, "profile");
            requireLiveRouteChangeEnabled();
            prepareLivePaymentLinkSourceOrFail(replacement, now);
            ContractorPaymentAllocation allocation = requireLiveRoutingAllocation(replacement, contractorPaymentLiveRoutingService.reserveOwnerForPaymentLink(replacement), "route_change_owner_returned_null");
            applyPaymentProfile(replacement, profile);
            applyBankPaymentRoute(replacement);
            replacement.setContractorAllocationId(allocation.getId());
        }
        replacement = paymentLinkRepository.save(replacement);
        current.setLastError(limit(replacementReason + "; новый счет #" + replacement.getId(), 512));
        paymentLinkRepository.save(current);
        ManagerPaymentLinkResponse response = toManagerResponseWithShadowRoute(replacement);
        log.info("Payment route replaced: orderId={}, oldLinkId={}, newLinkId={}, target={}, actor={}", orderId, current.getId(), replacement.getId(), target, authentication == null ? "system" : authentication.getName());
        return new PaymentRouteReplacement(current.getId(), replacement.getId(), target, response);
    }

    private void validateExpectedTargetPaymentProfile(PaymentRouteChangeTarget target, Long expectedTargetPaymentProfileId, PaymentProfile profile) {
        if (target != PaymentRouteChangeTarget.OWNER_TBANK) {
            return;
        }
        if (expectedTargetPaymentProfileId == null || expectedTargetPaymentProfileId <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Обновите заказ и подтвердите актуального получателя банковского платежа");
        }
        if (profile == null || !Objects.equals(expectedTargetPaymentProfileId, profile.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Получатель банковского платежа уже изменился. Обновите заказ и повторите");
        }
    }

    private void requireLiveRouteChangeEnabled() {
        preparationWorkflow.requireLiveRouteChangeEnabled();
    }

    private Optional<PaymentLink> currentRouteChangeLink(List<PaymentLink> links) {
        return preparationWorkflow.currentRouteChangeLink(links);
    }

    private String routeChangeBlockReason(PaymentLink link) {
        if (link == null) {
            return "Активный счет не найден";
        }
        if (link.getStatus() != PaymentLinkStatus.CREATED && link.getStatus() != PaymentLinkStatus.WAITING_MANUAL_PAYMENT) {
            return "Платеж уже начат или требует сверки; менять реквизиты автоматически нельзя";
        }
        if (hasStartedBankPayment(link) || link.getManualReportedAt() != null || link.getConfirmedAmountKopecks() != null || link.getPaidAt() != null) {
            return "По счету уже есть признаки оплаты; сначала выполните сверку";
        }
        if (isPaperInvoice(link) && link.getPaperInvoiceIssuedAt() != null) {
            return "Бумажный счёт уже отправлен клиенту; менять способ оплаты автоматически нельзя";
        }
        return null;
    }

    private void requireOwnerOrAdmin(Authentication authentication) {
        preparationWorkflow.requireOwnerOrAdmin(authentication);
    }

    public record PaymentRouteReplacement(Long previousPaymentLinkId, Long paymentLinkId, PaymentRouteChangeTarget target, ManagerPaymentLinkResponse response) {
    }

    private ManagerPaymentLinkResponse toManagerResponseWithShadowRoute(PaymentLink link) {
        return preparationWorkflow.toManagerResponseWithShadowRoute(link);
    }

    /**
     * Rebuilds delivery text from the frozen payment-link/allocation route.
     * Decrypted contractor requisites therefore never need to be copied into
     * the notification outbox in plaintext.
     */
    @Transactional(readOnly = true)
    public ManagerPaymentLinkResponse paymentRouteChangeNotificationDetails(Long paymentLinkId) {
        if (paymentLinkId == null || paymentLinkId <= 0) {
            return null;
        }
        return paymentLinkRepository.findById(paymentLinkId).map(this::toManagerResponse).orElse(null);
    }

    private void applyOwnerPaperInvoiceRoute(PaymentLink link) {
        preparationWorkflow.applyOwnerPaperInvoiceRoute(link);
    }

    private InvoicePaymentMode invoicePaymentMode(Order order) {
        return preparationWorkflow.invoicePaymentMode(order);
    }

    InvoicePaymentMode paymentModeForTarget(PaymentRouteChangeTarget target) {
        return switch(target) {
            case EMPLOYEE_REQUISITES ->
                InvoicePaymentMode.EMPLOYEE_REQUISITES;
            case OWNER_TBANK ->
                InvoicePaymentMode.OWNER_TBANK;
            case OWNER_PAPER_INVOICE ->
                InvoicePaymentMode.OWNER_PAPER_INVOICE;
        };
    }

    private String configuredPaymentModeLabel(InvoicePaymentMode mode) {
        return switch(mode) {
            case EMPLOYEE_REQUISITES ->
                "Реквизиты специалиста/менеджера";
            case OWNER_TBANK ->
                "Эквайринг банка владельца";
            case OWNER_PAPER_INVOICE ->
                "Бумажный счёт владельца";
            case AUTO_ROUTING ->
                "Автоматическое распределение";
        };
    }

    private String configuredPaymentModeRecipient(InvoicePaymentMode mode) {
        return switch(mode) {
            case EMPLOYEE_REQUISITES ->
                "Специалист/менеджер";
            case OWNER_TBANK, OWNER_PAPER_INVOICE ->
                "Владелец";
            case AUTO_ROUTING ->
                "";
        };
    }

    private boolean isPaperInvoice(PaymentLink link) {
        return paymentPresenter.isPaperInvoice(link);
    }

    private void ensureOrderNotCoveredByActiveCommonInvoice(Long orderId) {
        preparationWorkflow.ensureOrderNotCoveredByActiveCommonInvoice(orderId);
    }

    private void requirePaymentLinksEnabled() {
        preparationWorkflow.requirePaymentLinksEnabled();
    }

    private PaymentLink newPaymentLink(Order order, long amountKopecks, LocalDateTime now) {
        return preparationWorkflow.newPaymentLink(order, amountKopecks, now);
    }

    private void applyBankPaymentRoute(PaymentLink link) {
        commonInvoiceRouteSelector.applyBankPaymentRoute(link);
    }

    private boolean isFrozenContractorRoute(PaymentLink link) {
        return commonInvoiceRouteSelector.isFrozenContractorRoute(link);
    }

    private boolean isContractorRecipient(ContractorPaymentAllocation allocation) {
        return preparationWorkflow.isContractorRecipient(allocation);
    }

    private void prepareLivePaymentLinkSourceOrFail(PaymentLink link, LocalDateTime now) {
        preparationWorkflow.prepareLivePaymentLinkSourceOrFail(link, now);
    }

    private ContractorPaymentAllocation requireLiveRoutingAllocation(PaymentLink link, ContractorPaymentAllocation allocation, String reason) {
        return preparationWorkflow.requireLiveRoutingAllocation(link, allocation, reason);
    }

    private void applyContractorPaymentRoute(PaymentLink link, ContractorPaymentAllocation allocation) {
        preparationWorkflow.applyContractorPaymentRoute(link, allocation);
    }

    private boolean hasStartedBankPayment(PaymentLink link) {
        return lifecycleService.hasStartedBankPayment(link);
    }

    private boolean isTochkaPaymentLink(PaymentLink link) {
        return paymentPresenter.isTochkaPaymentLink(link);
    }

    private ManagerPaymentLinkResponse toManagerResponse(PaymentLink link) {
        return paymentPresenter.toManagerResponse(link);
    }

    private Manager orderManager(Order order) {
        return bankObservations.orderManager(order);
    }

    private Long targetPaymentProfileId(Order order) {
        PaymentProfile profile = paymentProfileService.selectForManagerForNewRoute(orderManager(order));
        return profile == null ? null : profile.getId();
    }

    private String manualRecipientName(String value) {
        return commonInvoiceRouteSelector.manualRecipientName(value);
    }

    private String manualRecipientName(PaymentLink link) {
        return commonInvoiceRouteSelector.manualRecipientName(link);
    }

    private String manualRouteRecipientContext(PaymentLink link, Order order) {
        String specialist = orderSpecialistName(order);
        String recipient = isFrozenContractorRoute(link) ? contractorPaymentLiveRoutingService.activePaymentLinkRequisites(link).map(ContractorPaymentRequisitesSnapshot::recipientName).map(this::normalize).orElse("") : normalize(link == null ? null : link.getManualRecipientName());
        if (recipient.isBlank()) {
            recipient = specialist;
        }
        if (recipient.isBlank()) {
            recipient = manualRecipientName(link);
        }
        if (specialist.isBlank()) {
            return "Получатель: " + recipient;
        }
        if (recipient.equalsIgnoreCase(specialist)) {
            return "Получатель и специалист: " + recipient;
        }
        return "Получатель: " + recipient + " · Специалист: " + specialist;
    }

    private String orderSpecialistName(Order order) {
        return paymentPresenter.orderSpecialistName(order);
    }

    private void applyManualTaskPayment(PaymentLink link, ManualPaymentTask task) {
        preparationWorkflow.applyManualTaskPayment(link, task);
    }

    private void applyManualTaskPayment(PaymentLink link, ManualPaymentTaskRouteSnapshot snapshot) {
        preparationWorkflow.applyManualTaskPayment(link, snapshot);
    }

    private void applyPaymentProfile(PaymentLink link, PaymentProfile profile) {
        commonInvoiceRouteSelector.applyPaymentProfile(link, profile);
    }

    private BigDecimal payableSum(Order order) {
        return amountPolicy.payableSum(order);
    }

    private long amountKopecks(BigDecimal amount) {
        return amountPolicy.amountKopecks(amount);
    }

    private boolean isManualPayment(PaymentLink link) {
        return paymentPresenter.isManualPayment(link);
    }

    private String normalize(String value) {
        return paymentPresenter.normalize(value);
    }

    private String limit(String value, int maxLength) {
        return commonInvoiceRouteSelector.limit(value, maxLength);
    }
}
