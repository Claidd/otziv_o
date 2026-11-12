package com.hunt.otziv.payments.service;

import com.hunt.otziv.client_messages.api.ManualPaymentAutomationControl;
import com.hunt.otziv.contractor_payments.dto.ContractorPaymentRequisitesSnapshot;
import com.hunt.otziv.contractor_payments.model.ContractorPaymentAllocation;
import com.hunt.otziv.contractor_payments.service.ContractorActualPaymentAttributionService;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentLiveRoutingService;
import com.hunt.otziv.manager.service.ManagerAccessService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.payments.api.UnpaidBankObservation.Source;
import com.hunt.otziv.payments.dto.ManagerPaymentLinkResponse;
import com.hunt.otziv.payments.dto.PaymentRouteChangeContextResponse;
import com.hunt.otziv.payments.dto.PaymentRouteChangeTarget;
import com.hunt.otziv.payments.model.ManualPaymentTask;
import com.hunt.otziv.payments.model.ManualPaymentSource;
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

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.hunt.otziv.payments.api.AlternatePaperReceipt alternatePaperReceipt;

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

    private final ManualPaymentAutomationControl paymentAutomation;

    @Transactional(readOnly = true)
    public PaymentRouteChangeContextResponse paymentRouteChangeContextAuthorized(Long orderId, Authentication authentication) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден"));
        managerAccessService.requireOrderAccess(orderId, authentication);
        if(order.isPaperInvoiceReviewHold()) throw new ResponseStatusException(HttpStatus.CONFLICT,"По отменённому банковскому счёту есть несверенное поступление. Смена способа оплаты заблокирована");
        List<PaymentLink> links = paymentLinkRepository.findByOrderIdInForRead(Set.of(orderId));
        PaymentLink current = currentRouteChangeLink(links).orElse(null);
        Long expectedTargetPaymentProfileId = targetPaymentProfileId(order);
        String automationBlockReason = paymentAutomation.paymentRouteReplacementBlockReason(orderId).orElse(null);
        var pendingChange = paymentLinkRepository.pendingRecipientChangeMessage(orderId);
        if (pendingChange.isPresent()) {
            return new PaymentRouteChangeContextResponse(current == null ? null : current.getId(), "Банковская ссылка", "Владелец",
                    current == null ? "WAITING_BANK" : current.getStatus().name(), false, pendingChange.get(),
                    invoicePaymentMode(order).name(), false, expectedTargetPaymentProfileId);
        }
        if (current == null) {
            InvoicePaymentMode configuredMode = invoicePaymentMode(order);
            return new PaymentRouteChangeContextResponse(null, configuredPaymentModeLabel(configuredMode), configuredPaymentModeRecipient(configuredMode), "NOT_CREATED", automationBlockReason == null, automationBlockReason == null ? "" : automationBlockReason, configuredMode.name(), false, expectedTargetPaymentProfileId);
        }
        String blockReason = routeChangeBlockReason(current);
        if (blockReason == null) blockReason = automationBlockReason;
        String currentRoute = isPaperInvoice(current) ? "Бумажный счёт владельца" : isManualPayment(current) ? "Оплата по реквизитам" : isTochkaPaymentLink(current) ? "Эквайринг · Точка Банк" : "Эквайринг · T‑Bank";
        String currentRecipient = isPaperInvoice(current) ? "Владелец" : isManualPayment(current) ? manualRouteRecipientContext(current, order) : normalize(current.getPaymentProfileName());
        if (currentRecipient.isBlank()) {
            currentRecipient = "Владелец";
        }
        return new PaymentRouteChangeContextResponse(current.getId(), currentRoute, currentRecipient, current.getStatus().name(), blockReason == null, blockReason == null ? "" : blockReason, invoicePaymentMode(order).name(), current.getPaperInvoiceIssuedAt() != null, expectedTargetPaymentProfileId, automationBlockReason == null && (bankRecipientEligible(current) || awaitingBankInit(current)));
    }

    @Transactional
    public PaymentRouteReplacement replacePaymentRouteAuthorized(Long orderId, Long expectedPaymentLinkId, PaymentRouteChangeTarget target, boolean confirmedUnpaid, Long expectedTargetPaymentProfileId, Authentication authentication) {
        return replacePaymentRouteAuthorized(orderId, expectedPaymentLinkId, target, confirmedUnpaid, expectedTargetPaymentProfileId, authentication, false, false);
    }

    @Transactional
    public PaymentRouteReplacement replacePaymentRouteAuthorized(Long orderId, Long expectedPaymentLinkId, PaymentRouteChangeTarget target, boolean confirmedUnpaid, Long expectedTargetPaymentProfileId, Authentication authentication, boolean paperInvoiceWithdrawn, boolean differentRecipient) {
        return replacePaymentRouteAuthorized(orderId, expectedPaymentLinkId, target, confirmedUnpaid, expectedTargetPaymentProfileId,
                authentication, paperInvoiceWithdrawn, differentRecipient, null);
    }

    /** @param chosenProfileId the person a manager named for the card number, or null for the automatic choice */
    @Transactional
    public PaymentRouteReplacement replacePaymentRouteAuthorized(Long orderId, Long expectedPaymentLinkId, PaymentRouteChangeTarget target, boolean confirmedUnpaid, Long expectedTargetPaymentProfileId, Authentication authentication, boolean paperInvoiceWithdrawn, boolean differentRecipient, Long chosenProfileId) {
        return replace(orderId, expectedPaymentLinkId, target, confirmedUnpaid, expectedTargetPaymentProfileId,
                authentication, paperInvoiceWithdrawn, differentRecipient, false, chosenProfileId);
    }

    /**
     * People the manager may name in the «Выдать номер карты» window of an order. The current
     * recipient is marked, so the window does not offer to issue the same requisites again.
     */
    @Transactional
    public com.hunt.otziv.payments.dto.PaymentRouteRecipientsResponse paymentRouteRecipientsAuthorized(Long orderId, Authentication authentication) {
        orderRepository.findById(orderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден"));
        managerAccessService.requireOrderAccess(orderId, authentication);
        PaymentLink current = currentRouteChangeLink(paymentLinkRepository.findByOrderIdInForRead(Set.of(orderId))).orElse(null);
        if (current == null) {
            return new com.hunt.otziv.payments.dto.PaymentRouteRecipientsResponse(orderId, null, 0L, List.of());
        }
        Long currentUserId = currentContractorRecipientUserId(current);
        var recipients = contractorPaymentLiveRoutingService.recipientOptionsForPaymentLink(current).stream()
                .map(option -> new com.hunt.otziv.payments.dto.PaymentRouteRecipientsResponse.Recipient(
                        option.profileId(), option.userId(), option.role(), option.name(),
                        option.orderSpecialist(), option.manager(), option.teamMember(), option.workedWithClient(),
                        option.availableKopecks(), option.overrunKopecks(),
                        currentUserId != null && currentUserId.equals(option.userId()), option.blockReason()))
                .toList();
        return new com.hunt.otziv.payments.dto.PaymentRouteRecipientsResponse(orderId, current.getId(), current.getAmountKopecks(),
                recipients, ownerCardOption(current));
    }

    /** The owner's card as a line of the «Выдать номер карты» window; null where the owner takes money through the bank only. */
    private com.hunt.otziv.payments.dto.PaymentRouteRecipientsResponse.OwnerCard ownerCardOption(PaymentLink current) {
        Order order = current.getOrder();
        PaymentProfile profile = paymentProfileService.selectForManager(orderManager(order));
        java.util.OptionalLong remaining = commonInvoiceRouteSelector.ownerCardRemainingKopecks(profile, LocalDateTime.now(), current.getId());
        if (remaining.isEmpty() || !commonInvoiceRouteSelector.allowLegacyManualProfileRoute()) {
            return null;
        }
        boolean issuedOnTheCard = current.getManualSource() == ManualPaymentSource.PROFILE_MONTHLY_LIMIT;
        String blockReason = issuedOnTheCard ? "клиенту уже выдана карта владельца"
                : current.getAmountKopecks() > remaining.getAsLong() ? "лимит карты на этот месяц не вмещает счёт" : null;
        return new com.hunt.otziv.payments.dto.PaymentRouteRecipientsResponse.OwnerCard(
                profile.getManualRecipientName(), remaining.getAsLong(), issuedOnTheCard, blockReason);
    }

    /** The person whose card number the client holds now; null for a bank link, a paper invoice or a damaged reserve. */
    private Long currentContractorRecipientUserId(PaymentLink current) {
        if (!isManualPayment(current)) {
            return null;
        }
        try {
            var task = taskReceiptIntegrationService.candidate(current).orElse(null);
            return contractorPaymentLiveRoutingService.issuedRecipientUserId(current.getContractorAllocationId(),
                    task == null ? null : task.accountingTargetProfileId());
        } catch (ResponseStatusException unavailable) {
            return null;
        }
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public PaymentLink lockRecipientChangeSource(long orderId, Long linkId, Authentication actor) {
        orderRepository.findByIdForCounterUpdate(orderId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден"));
        managerAccessService.requireOrderAccess(orderId, actor);
        if (linkId == null) return null;
        return paymentLinkRepository.findByIdForUpdate(linkId)
                .filter(link -> link.getOrder() != null && Objects.equals(orderId, link.getOrder().getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Ссылка заказа изменилась"));
    }

    /** Only the bank-change coordinator may complete this path after verifying the exact provider operation. */
    @Transactional
    public PaymentRouteReplacement replaceBankRoute(Long orderId, Long linkId, PaymentRouteChangeTarget target, boolean differentRecipient, Authentication actor) {
        return replaceBankRoute(orderId, linkId, target, differentRecipient, actor, null);
    }

    /** @param chosenProfileId the person a manager named while the bank link was still open, or null */
    @Transactional
    public PaymentRouteReplacement replaceBankRoute(Long orderId, Long linkId, PaymentRouteChangeTarget target, boolean differentRecipient, Authentication actor, Long chosenProfileId) {
        return replace(orderId, linkId, target, true, null, actor, false, differentRecipient, true, chosenProfileId);
    }

    boolean releaseRoutesAfterObservedReceipt(PaymentLink paidLink) {
        var order = paidLink.getOrder();
        if (orderPaymentIntegrityService.hasSettledPaymentEvidence(order)) return false;
        var replacements = paymentLinkRepository.findByOrderIdForUpdate(order.getId()).stream()
                .filter(link -> link.getId() > paidLink.getId())
                .filter(link -> !Set.of(PaymentLinkStatus.CANCELED, PaymentLinkStatus.EXPIRED, PaymentLinkStatus.REJECTED).contains(link.getStatus()))
                .toList();
        var documents = replacements.stream().filter(link -> link.getPaperBankDocumentId() != null).toList();
        if (documents.size() > 1 || replacements.stream().anyMatch(this::hasConflictingReplacement)) return false;
        if (!documents.isEmpty() && !alternatePaperReceipt.closeUnpaidDocument("ORDER",order.getId(),
                documents.getFirst().getPaperBankDocumentId(),paidLink.getAmountKopecks())) return false;
        String reason = "Получена оплата по предыдущей банковской ссылке";
        for (var link : replacements) {
            if (link.getStatus() == PaymentLinkStatus.INITIATED) link.setObserveReplacedBank(true);
            taskReceiptIntegrationService.release(link, reason);
            link.setStatus(PaymentLinkStatus.CANCELED);
            link.setExpiresAt(LocalDateTime.now());
            link.setLastError(reason);
            if (link.getContractorAllocationId() != null && link.getManualSource() != ManualPaymentSource.MANUAL_TASK)
                contractorPaymentLiveRoutingService.releaseClosedPaymentLink(link);
        }
        return true;
    }

    private boolean hasConflictingReplacement(PaymentLink link) {
        if (link.getPaperBankDocumentId() == null) {
            if (link.getStatus() == PaymentLinkStatus.INITIATED && bankRecipientEligible(link) && bankSource(link) != null)
                return false;
            return routeChangeBlockReason(link) != null;
        }
        return !Set.of(PaymentLinkStatus.CREATED, PaymentLinkStatus.WAITING_MANUAL_PAYMENT).contains(link.getStatus())
                || link.getManualReportedAt() != null || link.getManualActualRecipientFrozenAt() != null
                || link.getConfirmedAmountKopecks() != null || link.getPaidAt() != null;
    }

    private PaymentRouteReplacement replace(Long orderId, Long expectedPaymentLinkId, PaymentRouteChangeTarget target,
            boolean confirmedUnpaid, Long expectedTargetPaymentProfileId, Authentication authentication,
            boolean paperInvoiceWithdrawn, boolean differentRecipient, boolean bankChange, Long chosenProfileId) {
        if (!confirmedUnpaid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Подтвердите, что клиент еще не оплатил по прежним реквизитам");
        }
        if (target == null || (differentRecipient && target != PaymentRouteChangeTarget.EMPLOYEE_REQUISITES)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный запрос смены способа оплаты");
        }
        boolean ownerCardChosen = chosenProfileId != null && chosenProfileId == com.hunt.otziv.payments.dto.PaymentRouteChangeRequest.OWNER_CARD;
        if (chosenProfileId != null && target != PaymentRouteChangeTarget.EMPLOYEE_REQUISITES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Получателя выбирают только при выдаче номера карты");
        }
        // The manager endpoint already requires an authenticated manager and the
        // exact order assignment below. Paper documents use a fixed owner account.
        requirePaymentLinksEnabled();
        Order order = orderRepository.findByIdForCounterUpdate(orderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден"));
        managerAccessService.requireOrderAccess(orderId, authentication);
        ensureOrderNotCoveredByActiveCommonInvoice(orderId);
        if (!bankChange && paymentLinkRepository.pendingRecipientChanges(orderId) > 0)
            throw new ResponseStatusException(HttpStatus.LOCKED, "Смена получателя уже выполняется. Ожидаем ответ банка");
        if (paymentLinkRepository.pendingBadReviewBankChecks(orderId) > 0)
            throw new ResponseStatusException(HttpStatus.LOCKED, "Ожидаем перевыставления счёта после плохой задачи");
        if (order.isPaperInvoiceReviewHold()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Требуется сверка поступления по отменённому счёту");
        orderPaymentIntegrityService.assertPaymentCycleAllowed(order);
        // Match scheduler preparation lock order: Order -> message states -> links.
        // The check also fences known-unsent retries that retain old requisites.
        paymentAutomation.assertPaymentRouteReplaceable(orderId);
        LocalDateTime now = LocalDateTime.now();
        List<PaymentLink> links = paymentLinkRepository.findByOrderIdForUpdate(orderId);
        PaymentLink current = currentRouteChangeLink(links).orElse(null);
        if (bankChange) {
            PaymentLink source = links.stream().filter(link -> Objects.equals(expectedPaymentLinkId, link.getId()))
                    .findFirst().orElse(null);
            if (!bankRecipientEligible(source) || preparationWorkflow.hasCompetingBlockingPayment(source, links)
                    || (current != null && !Objects.equals(current.getId(), expectedPaymentLinkId))
                    || links.stream().anyMatch(link -> link.getId() > expectedPaymentLinkId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Ссылка уже изменилась или банк сообщил о движении денег");
            }
            current = source;
        }
        if (current == null) {
            if (differentRecipient || chosenProfileId != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Сначала выставьте счёт, чтобы сменить получателя");
            }
            if (expectedPaymentLinkId != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Активный счет уже изменился. Обновите заказ и повторите");
            }
            Manager manager = paymentProfileService.lockManagerForRouting(orderManager(order));
            PaymentProfile profile = target == PaymentRouteChangeTarget.OWNER_PAPER_INVOICE ? null : paymentProfileService.lockForRouting(paymentProfileService.selectForManager(manager));
            validateExpectedTargetPaymentProfile(target, expectedTargetPaymentProfileId, profile);
            InvoicePaymentMode mode = paymentModeForTarget(target);
            order.setInvoicePaymentMode(mode);
            order.setPaperInvoiceAutomationHold(false);
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
        String blockReason = bankChange ? null : routeChangeBlockReason(current);
        if (blockReason != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, blockReason);
        }
        if (isPaperInvoice(current) && current.getPaperInvoiceIssuedAt() != null && !paperInvoiceWithdrawn)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Проверьте выписку и подтвердите отзыв отправленного бумажного счёта");
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
        String replacementReason = isPaperInvoice(current) && current.getPaperInvoiceIssuedAt() != null
                ? "Бумажный счёт отозван после проверки выписки: " + authentication.getName()
                : "Способ оплаты изменен по просьбе клиента";
        var filter = ManualPaymentTaskRoutingFilter.NUMBER;
        if (differentRecipient) {
            var previousTask = taskReceiptIntegrationService.candidate(current).orElse(null);
            Long previousUserId = contractorPaymentLiveRoutingService.issuedRecipientUserId(current.getContractorAllocationId(),
                    previousTask == null ? null : previousTask.accountingTargetProfileId());
            filter = ManualPaymentTaskRoutingFilter.otherRecipient(previousUserId, previousTask);
        }
        taskReceiptIntegrationService.release(current, replacementReason);
        current.setStatus(PaymentLinkStatus.CANCELED);
        current.setExpiresAt(now);
        current.setLastError(replacementReason);
        paymentLinkRepository.save(current);
        if (current.getContractorAllocationId() != null && current.getManualSource() != ManualPaymentSource.MANUAL_TASK) {
            contractorPaymentLiveRoutingService.releaseClosedPaymentLink(current);
        }
        PaymentLink replacement = paymentLinkRepository.saveAndFlush(newPaymentLink(order, amountKopecks, now));
        order.setInvoicePaymentMode(paymentModeForTarget(target));
        order.setPaperInvoiceAutomationHold(false);
        orderRepository.save(order);
        if (target == PaymentRouteChangeTarget.OWNER_PAPER_INVOICE) {
            applyOwnerPaperInvoiceRoute(replacement);
        } else if (target == PaymentRouteChangeTarget.EMPLOYEE_REQUISITES) {
            Objects.requireNonNull(manager, "manager");
            Objects.requireNonNull(profile, "profile");
            // A person named by the manager bypasses both the payment task and the automatic choice. The
            // balance may be exceeded; the overrun keeps this person out of automatic routing until it recovers.
            // The owner's card is issued where the bank profile lets the owner take card transfers and this
            // month's limit of the card still holds the invoice. The old invoice is already closed above.
            // The client who already holds the owner's card is never given the same card again as «another» one.
            boolean ownerCardAvailable = current.getManualSource() != ManualPaymentSource.PROFILE_MONTHLY_LIMIT
                    && commonInvoiceRouteSelector.allowLegacyManualProfileRoute()
                    && commonInvoiceRouteSelector.shouldUseManualPayment(profile, amountKopecks, now, current.getId());
            Optional<ManualPaymentTaskRouteSnapshot> taskRoute = chosenProfileId == null && actualPaymentAttributionService.actualRecipientAccountingEnabled() ? taskReceiptIntegrationService.reserveForPaymentLink(replacement, manager.getId(), profile.getId(), filter) : Optional.empty();
            if (taskRoute.isPresent()) {
                applyManualTaskPayment(replacement, taskRoute.get());
            } else if (ownerCardChosen) {
                // The manager named the owner's card. A new invoice on it never exceeds the month's limit.
                if (!ownerCardAvailable) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Карту владельца выдать нельзя: она не разрешена для этого менеджера или лимит карты на этот месяц не вмещает счёт");
                }
                requireLiveRouteChangeEnabled();
                prepareLivePaymentLinkSourceOrFail(replacement, now);
                ContractorPaymentAllocation allocation = requireLiveRoutingAllocation(replacement,
                        contractorPaymentLiveRoutingService.reserveOwnerForPaymentLink(replacement), "route_change_owner_card_returned_null");
                applyOwnerCardRoute(order, replacement, profile, allocation);
            } else {
                requireLiveRouteChangeEnabled();
                prepareLivePaymentLinkSourceOrFail(replacement, now);
                // Automatic choice: an employee with a free reserve; the owner's card when nobody has one and
                // the card may be issued; otherwise the refusal, and the manager names a person.
                ContractorPaymentAllocation allocation = requireLiveRoutingAllocation(replacement, chosenProfileId != null
                        ? contractorPaymentLiveRoutingService.reserveChosenContractorForPaymentLink(replacement, chosenProfileId)
                        : ownerCardAvailable
                                ? contractorPaymentLiveRoutingService.reserveContractorElseOwnerForPaymentLink(replacement, filter.excludedUserId())
                                : contractorPaymentLiveRoutingService.reserveContractorForPaymentLink(replacement, filter.excludedUserId()), "route_change_contractor_returned_null");
                if (isContractorRecipient(allocation)) {
                    applyContractorPaymentRoute(replacement, allocation);
                } else if (chosenProfileId == null && ownerCardAvailable) {
                    applyOwnerCardRoute(order, replacement, profile, allocation);
                } else {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Не удалось выбрать специалиста или менеджера для оплаты");
                }
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

    /**
     * The invoice goes out with the requisites of the owner's card and uses this month's limit of the card.
     * The order returns to the ordinary choice of a recipient: «номер карты сотрудника» as an explicit mode
     * would not recognise this invoice as its own and would issue another one in its place.
     */
    private void applyOwnerCardRoute(Order order, PaymentLink replacement, PaymentProfile profile, ContractorPaymentAllocation ownerAllocation) {
        applyPaymentProfile(replacement, profile);
        commonInvoiceRouteSelector.applyManualProfilePayment(replacement, profile);
        replacement.setContractorAllocationId(ownerAllocation.getId());
        order.setInvoicePaymentMode(InvoicePaymentMode.AUTO_ROUTING);
        orderRepository.save(order);
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

    static Source recipientSource(PaymentLink link) {
        if (link == null || (!bankRecipientEligible(link) && !awaitingBankInit(link))) return null;
        return bankSource(link);
    }

    static Source repricingSource(PaymentLink link) {
        if (link.getBadReviewBankCheckAt() != null && link.getStatus() == PaymentLinkStatus.NEEDS_RECONCILIATION
                && link.getTbankPaymentId() != null && link.getPaidAt() == null && link.getConfirmedAmountKopecks() == null
                && link.getManualReportedAt() == null && link.getManualActualRecipientFrozenAt() == null
                && link.getBankCancelNonce() == null && link.getBankCancelOriginStatus() == null
                && link.getPaperBankDocumentId() == null) return bankSource(link);
        return recipientSource(link);
    }

    static Source bankSource(PaymentLink link) {
        if (link.getTbankOrderId() == null || link.getTbankTerminalKey() == null) return null;
        var order = link.getOrder();
        var profile = link.getPaymentProfile();
        return new Source("ORDER", order.getId(), link.getId(), profile == null ? null : profile.getId(),
                profile == null ? PaymentProfile.PROVIDER_TBANK : profile.normalizedProvider(), Objects.toString(link.getTbankPaymentId(), ""),
                link.getTbankOrderId(), link.getTbankTerminalKey(), link.getAmountKopecks(),
                link.getPaymentMethod() == com.hunt.otziv.payments.model.PaymentMethod.SBP_QR ? "sbp" : "card", null,
                Objects.toString(order.getManager() == null ? null : order.getManager().getId()));
    }

    public static boolean awaitingBankInit(PaymentLink link) {
        return link != null && link.getBankInitNonce() != null && !link.getBankInitNonce().isBlank()
                && (link.getTbankPaymentId() == null || link.getTbankPaymentId().isBlank())
                && link.getTbankOrderId() != null && link.getTbankTerminalKey() != null
                && link.getConfirmedAmountKopecks() == null && link.getManualActualRecipientFrozenAt() == null
                && link.getPaidAt() == null && link.getManualReportedAt() == null;
    }

    public static boolean bankRecipientEligible(PaymentLink link) {
        if (link == null || link.getTbankPaymentId() == null || link.getTbankPaymentId().isBlank()
                || link.getPaperBankDocumentId() != null || link.getManualActualRecipientFrozenAt() != null
                || link.getPaidAt() != null || link.getConfirmedAmountKopecks() != null || link.getManualReportedAt() != null
                || link.getBankCancelNonce() != null || link.getBankCancelOriginStatus() != null) return false;
        return (link.getPaymentMethod() == com.hunt.otziv.payments.model.PaymentMethod.BANK_FORM
                || link.getPaymentMethod() == com.hunt.otziv.payments.model.PaymentMethod.SBP_QR)
                && Set.of(PaymentLinkStatus.INITIATED, PaymentLinkStatus.AUTHORIZED, PaymentLinkStatus.EXPIRED, PaymentLinkStatus.CANCELED,
                          PaymentLinkStatus.REJECTED).contains(link.getStatus());
    }

    private String routeChangeBlockReason(PaymentLink link) {
        if (link != null && link.getPaperBankDocumentId() != null) {
            return "По заказу уже выпускаются банковские документы. Проверьте их оплату перед сменой способа";
        }
        if (link == null) {
            return "Активный счет не найден";
        }
        if (link.getStatus() != PaymentLinkStatus.CREATED && link.getStatus() != PaymentLinkStatus.WAITING_MANUAL_PAYMENT) {
            return "Платеж уже начат или требует сверки; менять реквизиты автоматически нельзя";
        }
        if (link.getManualActualRecipientFrozenAt() != null || hasStartedBankPayment(link) || link.getManualReportedAt() != null || link.getConfirmedAmountKopecks() != null || link.getPaidAt() != null) {
            return "По счету уже есть признаки оплаты; сначала выполните сверку";
        }

        return null;
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
