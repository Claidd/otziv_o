package com.hunt.otziv.payments.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.manager.service.ManagerAccessService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.payments.dto.AdminPaymentLinkResponse;
import com.hunt.otziv.payments.dto.ManagerManualCardPaymentResultResponse;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.OwnerManualCardPaymentApproval;
import com.hunt.otziv.payments.model.OwnerManualCardPaymentApprovalStatus;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.repository.OwnerManualCardPaymentApprovalRepository;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.Role;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static com.hunt.otziv.payments.service.ManualCardPaymentWorkflow.ManualCardPaymentContext;
import static com.hunt.otziv.payments.service.ManualCardPaymentWorkflow.ManualCardPaymentMode;
import static com.hunt.otziv.payments.service.ManualCardPaymentWorkflow.OrderManualCardRoute;

/**
 * Owns manager requests and owner callbacks for manual-transfer approval.
 * Both paths lock Order -> Link -> Approval; the payment workflow receives a
 * validated snapshot after the preparation transaction has committed.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OwnerManualCardApprovalWorkflow {

    private final PaymentLinkLifecycleService lifecycleService;

    private final PaymentLinkPreparationWorkflow preparationWorkflow;

    private final ManualCardRoutePolicy manualCardRoutePolicy;

    private final ManualCardPaymentWorkflow manualCardPayments;

    private final PaymentLinkPresenter paymentPresenter;

    private final CommonInvoiceRouteSelector commonInvoiceRouteSelector;

    private final PaymentLinkRepository paymentLinkRepository;

    private final OrderRepository orderRepository;

    private final ManagerAccessService managerAccessService;

    private final ManualCardPaymentReviewNotificationService manualCardPaymentReviewNotificationService;

    private final OwnerManualCardPaymentApprovalRepository ownerManualCardPaymentApprovalRepository;

    private final PaymentLinkTransactionExecutor transactionExecutor;

    private final SecureRandom secureRandom = new SecureRandom();

    private void ensureOrderNotCoveredByActiveCommonInvoice(Long orderId) {
        preparationWorkflow.ensureOrderNotCoveredByActiveCommonInvoice(orderId);
    }

    private boolean hasOrderBinding(PaymentLink link, Long orderId) {
        return lifecycleService.hasOrderBinding(link, orderId);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ManagerManualCardPaymentResultResponse submitManagerManualCardPaymentForOrder(Long orderId, String reason, String receiptUrl, ContractorRecipientType recipientType, Long recipientProfileId, String recipientKey, String actor, Authentication authentication) {
        String cleanReason = normalize(reason);
        if (cleanReason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите причину ручной оплаты");
        }
        if (cleanReason.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Причина не должна превышать 500 символов");
        }
        String cleanReceiptUrl = validatedReceiptUrl(receiptUrl);
        if (cleanReceiptUrl.length() > 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ссылка на чек не должна превышать 1024 символа");
        }
        String cleanActor = normalize(actor);
        OrderManualCardRoute route = selectManualCardPaymentRouteForOrder(orderId, authentication);
        PaymentLink snapshot = paymentLinkRepository.findByIdWithOrder(route.linkId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        if (!isCompletedManualCardPayment(snapshot) && recipientType == ContractorRecipientType.OWNER) {
            return requestOwnerManualCardPaymentApproval(orderId, route, cleanReason, cleanReceiptUrl, recipientType, recipientProfileId, recipientKey, cleanActor, authentication);
        }
        AdminPaymentLinkResponse completed = confirmPaidByManualCardTransferInternal(route.linkId(), route.amountKopecks(), "Причина менеджера: " + cleanReason, cleanReceiptUrl, cleanActor, authentication, new ManualCardPaymentContext(ManualCardPaymentMode.MANAGER_REPORTED, cleanReason), recipientType, recipientProfileId, recipientKey);
        return ManagerManualCardPaymentResultResponse.completed(orderId, completed.id());
    }

    private ManagerManualCardPaymentResultResponse requestOwnerManualCardPaymentApproval(Long orderId, OrderManualCardRoute route, String reason, String receiptUrl, ContractorRecipientType recipientType, Long recipientProfileId, String recipientKey, String actor, Authentication authentication) {
        return transactionExecutor.required(() -> {
            Order order = orderRepository.findByIdForCounterUpdate(orderId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден"));
            managerAccessService.requireOrderAccess(orderId, authentication);
            ensureOrderNotCoveredByActiveCommonInvoice(orderId);
            PaymentLink link = paymentLinkRepository.findByIdForUpdate(route.linkId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
            if (!hasOrderBinding(link, orderId) || link.getAmountKopecks() != route.amountKopecks() || !isOwnerApprovalEligibleRoute(link)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Маршрут оплаты изменился. Запрос владельцу не отправлен.");
            }
            requireManualCardPaymentLocalEligibility(order, link, orderId);
            if (actor.isBlank() || actor.length() > 150) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный исполнитель операции");
            }
            freezeActualRecipientIntentIfRequired(order, link, recipientType, recipientProfileId, recipientKey, reason, receiptUrl, actor);
            if (link.getManualActualRecipientType() != ContractorRecipientType.OWNER || link.getManualActualRecipientProfileId() != null || link.getManualActualCashDestinationKind() != ContractorCashDestinationKind.OWNER) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Для подтверждения владельца должен быть выбран именно владелец");
            }
            String callbackToken = newOwnerApprovalToken();
            OwnerManualCardPaymentApproval approval = ownerManualCardPaymentApprovalRepository.findByPaymentLinkIdForUpdate(link.getId()).orElseGet(OwnerManualCardPaymentApproval::new);
            approval.setPaymentLinkId(link.getId());
            approval.setOrderId(orderId);
            approval.setAmountKopecks(link.getAmountKopecks());
            approval.setRecipientKey(normalize(recipientKey));
            approval.setRecipientType(ContractorRecipientType.OWNER);
            approval.setRecipientProfileId(null);
            approval.setReason(reason);
            approval.setReceiptUrl(receiptUrl);
            approval.setRequestedBy(actor);
            approval.setCallbackTokenHash(ownerApprovalTokenHash(callbackToken));
            approval.setStatus(OwnerManualCardPaymentApprovalStatus.PENDING);
            approval.setRequestedAt(LocalDateTime.now());
            approval.setAttemptCount(0);
            approval.setLastAttemptAt(null);
            approval.setLastError(null);
            approval.setApprovedByUserId(null);
            approval.setApprovedBy(null);
            approval.setApprovedAt(null);
            approval = ownerManualCardPaymentApprovalRepository.saveAndFlush(approval);
            paymentLinkRepository.save(link);
            Company company = order.getCompany();
            Manager companyManager = company == null ? null : company.getManager();
            manualCardPaymentReviewNotificationService.notifyOwnerApprovalAfterCommit(new ManualCardPaymentReviewNotificationService.OwnerApprovalRequest(approval.getId(), callbackToken, link.getId(), orderId, company == null ? null : company.getTitle(), link.getAmountKopecks(), actor, reason, link.getStatus() == null ? null : link.getStatus().name(), companyManager == null ? null : companyManager.getId(), companyManager == null ? null : companyManager.getAuditTelegramGroupChatId()));
            return ManagerManualCardPaymentResultResponse.ownerApprovalPending(orderId, link.getId());
        });
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public OwnerManualCardPaymentApprovalOutcome approveOwnerManualCardPayment(Long approvalId, String callbackToken, Long callbackChatId, User approver, Authentication authentication) {
        requireOwnerManualCardPaymentApprover(approver, authentication);
        OwnerManualCardPaymentApprovalCommand command = transactionExecutor.required(() -> prepareOwnerManualCardPaymentApproval(approvalId, callbackToken, callbackChatId, authentication));
        if (command.alreadyCompleted()) {
            return command.outcome(true);
        }
        try {
            confirmPaidByManualCardTransferInternal(command.paymentLinkId(), command.amountKopecks(), "Причина менеджера: " + command.reason(), command.receiptUrl(), command.requestedBy(), authentication, new ManualCardPaymentContext(ManualCardPaymentMode.MANAGER_REPORTED, command.reason()), command.recipientType(), command.recipientProfileId(), command.recipientKey(), true);
        } catch (RuntimeException exception) {
            transactionExecutor.requiredNoRollback(() -> {
                recordOwnerApprovalFailure(approvalId, callbackToken, exception);
                return null;
            });
            throw exception;
        }
        transactionExecutor.required(() -> {
            OwnerManualCardPaymentApproval approval = requireOwnerApproval(approvalId, callbackToken);
            if (approval.getStatus() != OwnerManualCardPaymentApprovalStatus.CONFIRMED) {
                approval.setStatus(OwnerManualCardPaymentApprovalStatus.CONFIRMED);
                approval.setApprovedByUserId(approver == null ? null : approver.getId());
                approval.setApprovedBy(approver == null ? null : limit(normalize(approver.getUsername()), 150));
                approval.setApprovedAt(LocalDateTime.now());
                approval.setLastError(null);
                ownerManualCardPaymentApprovalRepository.save(approval);
            }
            return null;
        });
        return command.outcome(false);
    }

    private void requireOwnerManualCardPaymentApprover(User approver, Authentication authentication) {
        boolean privileged = approver != null && approver.getId() != null && approver.isActive() && approver.getRoles() != null && approver.getRoles().stream().map(Role::getName).filter(Objects::nonNull).map(value -> value.trim().toUpperCase(Locale.ROOT)).anyMatch(value -> "ROLE_OWNER".equals(value) || "ROLE_ADMIN".equals(value));
        boolean sameAuthenticatedUser = authentication != null && !normalize(authentication.getName()).isBlank() && normalize(authentication.getName()).equalsIgnoreCase(normalize(approver == null ? null : approver.getUsername()));
        if (!privileged || !sameAuthenticatedUser) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Подтвердить поступление может только активный владелец или администратор");
        }
    }

    private OwnerManualCardPaymentApprovalCommand prepareOwnerManualCardPaymentApproval(Long approvalId, String callbackToken, Long callbackChatId, Authentication authentication) {
        var binding = ownerManualCardPaymentApprovalRepository.findBindingById(approvalId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Запрос подтверждения не найден"));
        requireOwnerApprovalToken(binding.getCallbackTokenHash(), callbackToken);
        // Requests take Order -> Link -> Approval. Callbacks use the same order,
        // then revalidate the scalar snapshot and secret under the final lock.
        Order order = orderRepository.findByIdForCounterUpdate(binding.getOrderId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ запроса больше не существует. Оплата не зачислена."));
        requireOwnerApprovalManagerGroup(order, callbackChatId);
        managerAccessService.requireOrderAccess(binding.getOrderId(), authentication);
        var lockedLink = paymentLinkRepository.findByIdForUpdate(binding.getPaymentLinkId());
        OwnerManualCardPaymentApproval approval = requireOwnerApproval(approvalId, callbackToken);
        if (!Objects.equals(approval.getOrderId(), binding.getOrderId()) || !Objects.equals(approval.getPaymentLinkId(), binding.getPaymentLinkId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Запрос изменился во время подтверждения. Оплата не зачислена.");
        }
        if (approval.getStatus() == OwnerManualCardPaymentApprovalStatus.CONFIRMED) {
            return OwnerManualCardPaymentApprovalCommand.from(approval, true);
        }
        if (approval.getStatus() == OwnerManualCardPaymentApprovalStatus.SUPERSEDED) {
            throw new ResponseStatusException(HttpStatus.GONE, "Запрос закрыт: заказ уже оплачен по другому счёту. Повторное зачисление не требуется");
        }
        if (approval.getStatus() != OwnerManualCardPaymentApprovalStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Запрос уже не действует");
        }
        PaymentLink link = lockedLink.orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "T-Bank-ссылка больше не существует. Оплата не зачислена."));
        if (!hasOrderBinding(link, approval.getOrderId()) || link.getAmountKopecks() != approval.getAmountKopecks() || !isOwnerApprovalEligibleRoute(link) || link.getManualActualRecipientFrozenAt() == null || link.getManualActualRecipientType() != ContractorRecipientType.OWNER || link.getManualActualRecipientProfileId() != null || link.getManualActualCashDestinationKind() != ContractorCashDestinationKind.OWNER) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Счёт или получатель изменился после запроса. Оплата не зачислена.");
        }
        approval.setAttemptCount(approval.getAttemptCount() + 1);
        approval.setLastAttemptAt(LocalDateTime.now());
        approval.setLastError(null);
        ownerManualCardPaymentApprovalRepository.save(approval);
        return OwnerManualCardPaymentApprovalCommand.from(approval, false);
    }

    private void requireOwnerApprovalManagerGroup(Order order, Long callbackChatId) {
        Company company = order == null ? null : order.getCompany();
        Manager manager = company == null ? null : company.getManager();
        Long expectedChatId = manager == null ? null : manager.getAuditTelegramGroupChatId();
        if (callbackChatId == null || callbackChatId >= 0 || expectedChatId == null || expectedChatId >= 0 || !expectedChatId.equals(callbackChatId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Подтверждение доступно только в чате назначенного компании менеджера");
        }
    }

    private OwnerManualCardPaymentApproval requireOwnerApproval(Long approvalId, String callbackToken) {
        OwnerManualCardPaymentApproval approval = ownerManualCardPaymentApprovalRepository.findByIdForUpdate(approvalId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Запрос подтверждения не найден"));
        requireOwnerApprovalToken(approval.getCallbackTokenHash(), callbackToken);
        return approval;
    }

    private void requireOwnerApprovalToken(String expectedHash, String callbackToken) {
        byte[] expected = expectedHash == null ? new byte[0] : expectedHash.getBytes(StandardCharsets.US_ASCII);
        byte[] actual = ownerApprovalTokenHash(normalize(callbackToken)).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Запрос подтверждения недействителен");
        }
    }

    private void recordOwnerApprovalFailure(Long approvalId, String callbackToken, RuntimeException exception) {
        try {
            OwnerManualCardPaymentApproval approval = requireOwnerApproval(approvalId, callbackToken);
            if (approval.getStatus() == OwnerManualCardPaymentApprovalStatus.PENDING) {
                String message = exception instanceof ResponseStatusException response ? response.getReason() : exception.getMessage();
                approval.setLastError(limit(normalize(message), 512));
                ownerManualCardPaymentApprovalRepository.save(approval);
            }
        } catch (RuntimeException auditFailure) {
            log.warn("Не удалось записать ошибку подтверждения владельца approvalId={}", approvalId, auditFailure);
        }
    }

    private String newOwnerApprovalToken() {
        byte[] token = new byte[18];
        secureRandom.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    private String ownerApprovalTokenHash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalize(token).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 недоступен", impossible);
        }
    }

    public record OwnerManualCardPaymentApprovalOutcome(Long approvalId, Long orderId, Long paymentLinkId, long amountKopecks, boolean alreadyCompleted) {
    }

    record OwnerManualCardPaymentApprovalCommand(Long approvalId, Long orderId, Long paymentLinkId, long amountKopecks, String recipientKey, ContractorRecipientType recipientType, Long recipientProfileId, String reason, String receiptUrl, String requestedBy, boolean alreadyCompleted) {

        private static OwnerManualCardPaymentApprovalCommand from(OwnerManualCardPaymentApproval approval, boolean alreadyCompleted) {
            return new OwnerManualCardPaymentApprovalCommand(approval.getId(), approval.getOrderId(), approval.getPaymentLinkId(), approval.getAmountKopecks(), approval.getRecipientKey(), approval.getRecipientType(), approval.getRecipientProfileId(), approval.getReason(), approval.getReceiptUrl(), approval.getRequestedBy(), alreadyCompleted);
        }

        private OwnerManualCardPaymentApprovalOutcome outcome(boolean replay) {
            return new OwnerManualCardPaymentApprovalOutcome(approvalId, orderId, paymentLinkId, amountKopecks, replay);
        }
    }

    private OrderManualCardRoute selectManualCardPaymentRouteForOrder(Long orderId, Authentication authentication) {
        return manualCardPayments.selectManualCardPaymentRouteForOrder(orderId, authentication);
    }

    private AdminPaymentLinkResponse confirmPaidByManualCardTransferInternal(Long linkId, Long receivedAmountKopecks, String note, String receiptUrl, String actor, Authentication authentication, ManualCardPaymentContext context, ContractorRecipientType requestedRecipientType, Long requestedRecipientProfileId, String requestedRecipientKey) {
        return manualCardPayments.confirmPaidByManualCardTransferInternal(linkId, receivedAmountKopecks, note, receiptUrl, actor, authentication, context, requestedRecipientType, requestedRecipientProfileId, requestedRecipientKey);
    }

    private AdminPaymentLinkResponse confirmPaidByManualCardTransferInternal(Long linkId, Long receivedAmountKopecks, String note, String receiptUrl, String actor, Authentication authentication, ManualCardPaymentContext context, ContractorRecipientType requestedRecipientType, Long requestedRecipientProfileId, String requestedRecipientKey, boolean signedOwnerApproval) {
        return manualCardPayments.confirmPaidByManualCardTransferInternal(linkId, receivedAmountKopecks, note, receiptUrl, actor, authentication, context, requestedRecipientType, requestedRecipientProfileId, requestedRecipientKey, signedOwnerApproval);
    }

    private void freezeActualRecipientIntentIfRequired(Order order, PaymentLink link, ContractorRecipientType requestedRecipientType, Long requestedRecipientProfileId, String requestedRecipientKey, String reason, String receiptUrl, String actor) {
        manualCardPayments.freezeActualRecipientIntentIfRequired(order, link, requestedRecipientType, requestedRecipientProfileId, requestedRecipientKey, reason, receiptUrl, actor);
    }

    /**
     * Repeated under Order + target link + every order-link lock immediately before remote Cancel.
     */
    private void requireManualCardPaymentLocalEligibility(Order order, PaymentLink link, Long orderId) {
        manualCardPayments.requireManualCardPaymentLocalEligibility(order, link, orderId);
    }

    private boolean isCompletedManualCardPayment(PaymentLink link) {
        return manualCardRoutePolicy.isCompletedManualCardPayment(link);
    }

    /**
     * Owner receipt is never self-proving: the customer can transfer to an
     * old owner account while the current client instruction points either to
     * T-Bank or to a contractor. In both cases the manager only reports the
     * receipt; an owner/admin must confirm it before the order is settled.
     */
    private boolean isOwnerApprovalEligibleRoute(PaymentLink link) {
        return manualCardRoutePolicy.isOwnerApprovalEligibleRoute(link);
    }

    private String validatedReceiptUrl(String value) {
        return manualCardPayments.validatedReceiptUrl(value);
    }

    private String normalize(String value) {
        return paymentPresenter.normalize(value);
    }

    private String limit(String value, int maxLength) {
        return commonInvoiceRouteSelector.limit(value, maxLength);
    }
}
