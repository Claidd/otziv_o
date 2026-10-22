package com.hunt.otziv.payments.service;

import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.contractor_payments.model.ContractorCashDestinationKind;
import com.hunt.otziv.contractor_payments.model.ContractorRecipientType;
import com.hunt.otziv.contractor_payments.service.ContractorActualPaymentAttributionService;
import com.hunt.otziv.manager.service.ManagerAccessService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.payments.dto.RevokePendingOwnerApprovalRequest;
import com.hunt.otziv.payments.model.OwnerManualCardPaymentApproval;
import com.hunt.otziv.payments.model.OwnerManualCardPaymentApprovalStatus;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.repository.OwnerManualCardPaymentApprovalRepository;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.repository.SpecialistManualPaymentReviewRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Revokes only an unconsumed, erroneous owner receipt request. Never settles money. */
@Service
@RequiredArgsConstructor
public class OwnerManualCardPaymentApprovalRevocationService {

    private final OwnerManualCardPaymentApprovalRepository approvals;
    private final OrderRepository orders;
    private final PaymentLinkRepository links;
    private final SpecialistManualPaymentReviewRepository reviews;
    private final ManagerAccessService access;
    private final ManualCardRoutePolicy routePolicy;
    private final OrderPaymentIntegrityService orderPaymentIntegrity;
    private final ContractorActualPaymentAttributionService attribution;
    private final ManualCardPaymentReviewNotificationService notifications;
    private final BusinessAuditService audit;

    @Transactional(readOnly = true)
    public ApprovalView findByPaymentLink(Long paymentLinkId, Authentication authentication) {
        if (paymentLinkId == null || paymentLinkId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректный номер счёта");
        }
        OwnerManualCardPaymentApproval approval = approvals.findByPaymentLinkId(paymentLinkId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Запрос владельцу не найден"));
        access.requireOrderAccess(approval.getOrderId(), authentication);
        return ApprovalView.from(approval);
    }

    @Transactional
    public RevocationResult revokePending(Long approvalId, RevokePendingOwnerApprovalRequest request,
            Authentication authentication) {
        if (approvalId == null || approvalId <= 0 || request == null
                || request.expectedOrderId() == null || request.expectedOrderId() <= 0
                || request.expectedPaymentLinkId() == null || request.expectedPaymentLinkId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите запрос, заказ и счёт");
        }
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.isBlank() || reason.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Укажите причину отмены длиной до 500 символов");
        }
        access.requireOrderAccess(request.expectedOrderId(), authentication);
        var binding = approvals.findBindingById(approvalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Запрос владельцу не найден"));
        if (!Objects.equals(binding.getOrderId(), request.expectedOrderId())
                || !Objects.equals(binding.getPaymentLinkId(), request.expectedPaymentLinkId())) {
            throw conflict("Запрос относится к другому заказу или счёту");
        }

        // Match the callback's lock order. The scalar binding is rechecked after all locks.
        Order order = orders.findByIdForCounterUpdate(binding.getOrderId())
                .orElseThrow(() -> conflict("Заказ больше не существует"));
        List<PaymentLink> orderLinks = links.findByOrderIdForUpdate(order.getId());
        PaymentLink link = orderLinks.stream()
                .filter(value -> Objects.equals(value.getId(), binding.getPaymentLinkId()))
                .findFirst().orElseThrow(() -> conflict("Счёт больше не относится к заказу"));
        OwnerManualCardPaymentApproval approval = approvals.findByIdForUpdate(approvalId)
                .orElseThrow(() -> conflict("Запрос владельцу изменился"));
        if (!Objects.equals(approval.getOrderId(), order.getId())
                || !Objects.equals(approval.getPaymentLinkId(), link.getId())
                || !Objects.equals(approval.getOrderId(), request.expectedOrderId())
                || !Objects.equals(approval.getPaymentLinkId(), request.expectedPaymentLinkId())) {
            throw conflict("Запрос изменился во время отмены");
        }
        if (approval.getStatus() == OwnerManualCardPaymentApprovalStatus.REVOKED) {
            notifications.closeOwnerApprovalReminders(approvalId);
            return new RevocationResult(approvalId, order.getId(), link.getId(), "REVOKED", true);
        }
        if (approval.getStatus() != OwnerManualCardPaymentApprovalStatus.PENDING
                || approval.getAttemptCount() != 0 || approval.getLastAttemptAt() != null) {
            throw conflict("Подтверждение уже выполнялось или запрос закрыт; требуется отдельная сверка");
        }
        if (orderPaymentIntegrity.hasSettledPaymentEvidence(order)
                || orderLinks.stream().anyMatch(this::hasPaymentEvidence)) {
            throw conflict("По заказу уже есть признаки платежа; смена получателя требует отдельной сверки");
        }
        if (!routePolicy.isUnstartedCreatedBankRoute(link) || hasProviderAttempt(link)
                || !Objects.equals(link.getAmountKopecks(), approval.getAmountKopecks())) {
            throw conflict("Банковский маршрут уже начат или счёт изменился");
        }
        if (approval.getRecipientType() != ContractorRecipientType.OWNER
                || approval.getRecipientProfileId() != null
                || !"OWNER".equals(approval.getRecipientKey())
                || link.getManualActualRecipientFrozenAt() == null
                || link.getManualActualRecipientType() != ContractorRecipientType.OWNER
                || link.getManualActualRecipientProfileId() != null
                || link.getManualActualCashDestinationKind() != ContractorCashDestinationKind.OWNER
                || link.getManualActualTaskId() != null) {
            throw conflict("Выбор получателя уже изменился");
        }
        if (reviews.lockByPaymentLinkId(link.getId()).isPresent()) {
            throw conflict("По счёту уже есть проверка специалиста");
        }

        attribution.clearRevokedPaymentLinkRecipientIntent(link);
        String actor = authentication == null || authentication.getName() == null
                ? "" : authentication.getName().trim();
        if (actor.isBlank() || actor.length() > 150) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Неизвестен исполнитель отмены");
        }
        approval.setStatus(OwnerManualCardPaymentApprovalStatus.REVOKED);
        approval.setRevokedAt(LocalDateTime.now());
        approval.setRevokedBy(actor);
        approval.setRevocationReason(reason);
        approvals.saveAndFlush(approval);
        links.saveAndFlush(link);
        notifications.closeOwnerApprovalReminders(approvalId);
        audit.recordRequiredInCurrentTransaction(authentication, "OWNER_APPROVAL_REVOKED",
                "OWNER_MANUAL_CARD_APPROVAL", approvalId, order.getId(), null,
                "PENDING owner recipient, payment link " + link.getId(),
                "REVOKED recipient intent cleared", reason);
        return new RevocationResult(approvalId, order.getId(), link.getId(), "REVOKED", false);
    }

    private boolean hasPaymentEvidence(PaymentLink link) {
        return link.getPaidAt() != null || link.getManualConfirmedAt() != null
                || link.getConfirmedAmountKopecks() != null
                || link.getContractorEvidenceOriginalLinkId() != null
                || link.getStatus() == PaymentLinkStatus.AUTHORIZED
                || link.getStatus() == PaymentLinkStatus.CONFIRMED
                || link.getStatus() == PaymentLinkStatus.TEST_CONFIRMED
                || link.getStatus() == PaymentLinkStatus.MANUAL_REPORTED
                || link.getStatus() == PaymentLinkStatus.REVERSED
                || link.getStatus() == PaymentLinkStatus.PARTIAL_REVERSED
                || link.getStatus() == PaymentLinkStatus.REFUNDED
                || link.getStatus() == PaymentLinkStatus.PARTIAL_REFUNDED
                || link.getStatus() == PaymentLinkStatus.NEEDS_RECONCILIATION;
    }

    private boolean hasProviderAttempt(PaymentLink link) {
        return link.getInitiatedAt() != null || link.getManualReportedAt() != null
                || link.getTbankOrderId() != null || link.getPaymentUrl() != null
                || link.getSbpQrPayload() != null || link.getProviderTerminalStatus() != null
                || link.getProviderPaymentType() != null || link.getBankInitLeaseUntil() != null
                || link.getBankCancelLeaseUntil() != null;
    }

    private ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    public record RevocationResult(Long approvalId, Long orderId, Long paymentLinkId,
                                   String status, boolean alreadyRevoked) {}

    public record ApprovalView(Long approvalId, Long orderId, Long paymentLinkId,
                               String status, long amountKopecks, LocalDateTime requestedAt,
                               String requestedBy, LocalDateTime revokedAt, String revokedBy,
                               String revocationReason) {
        public static ApprovalView from(OwnerManualCardPaymentApproval approval) {
            return new ApprovalView(approval.getId(), approval.getOrderId(),
                    approval.getPaymentLinkId(), approval.getStatus().name(),
                    approval.getAmountKopecks(), approval.getRequestedAt(),
                    approval.getRequestedBy(), approval.getRevokedAt(), approval.getRevokedBy(),
                    approval.getRevocationReason());
        }
    }
}
