package com.hunt.otziv.payments.service;

import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.payments.dto.PaymentReturnManualResolutionOutcome;
import com.hunt.otziv.payments.dto.PaymentReturnManualResolutionRequest;
import com.hunt.otziv.payments.dto.PaymentReturnManualResolutionResponse;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.repository.PaymentLinkReturnOutboxRepository;
import com.hunt.otziv.z_zp.model.PaymentCheck;
import com.hunt.otziv.z_zp.repository.PaymentCheckRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class PaymentReturnManualReconciliationService {

    public static final String APPLIED_MANUALLY_CONFIRMATION = "ПОДТВЕРЖДАЮ РУЧНОЙ ОТКАТ ВОЗВРАТА #";
    public static final String ACCEPTED_NOOP_CONFIRMATION = "ПОДТВЕРЖДАЮ СВЕРКУ БЕЗ ОТКАТА #";

    private final PaymentLinkRepository paymentLinkRepository;
    private final OrderRepository orderRepository;
    private final PaymentIssueReminderService paymentIssueReminderService;
    private final BusinessAuditService businessAuditService;
    private final PaymentCheckRepository paymentCheckRepository;
    private final PaymentLinkReturnOutboxRepository returnOutboxRepository;

    @Transactional
    public PaymentReturnManualResolutionResponse resolve(
            Long paymentLinkId,
            PaymentReturnManualResolutionRequest request
    ) {
        String actor = requireActor();
        if (paymentLinkId == null || paymentLinkId <= 0) {
            throw badRequest("Платежная ссылка обязательна");
        }
        if (request == null || request.outcome() == null) {
            throw badRequest("Исход ручной сверки обязателен");
        }
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.isBlank()) {
            throw badRequest("Причина ручной сверки обязательна");
        }
        if (reason.length() > 512) {
            throw badRequest("Причина ручной сверки длиннее 512 символов");
        }
        Long orderId = paymentLinkRepository.findOrderIdById(paymentLinkId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        Order order = orderRepository.findByIdForCounterUpdate(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ платежной ссылки не найден"));
        PaymentLink link = paymentLinkRepository.findByIdForUpdate(paymentLinkId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платежная ссылка не найдена"));
        Long lockedOrderId = link.getOrder() == null ? null : link.getOrder().getId();
        if (!Objects.equals(orderId, lockedOrderId) || !Objects.equals(order.getId(), lockedOrderId)) {
            throw conflict("Платежная ссылка сменила заказ во время ручной сверки");
        }
        if (!PaymentReturnRecoveryState.isValidMarkerTuple(link)
                || PaymentReturnRecoveryState.isMarkerEmpty(link)) {
            throw conflict("Маркер возврата отсутствует или поврежден");
        }
        requireConfirmation(request.outcome(), paymentLinkId, request.confirmation());

        String requestedOutcome = request.outcome().name();
        if (PaymentReturnRecoveryState.isResolvedOutcome(link.getReturnRecoveryOutcome())) {
            if (!requestedOutcome.equals(link.getReturnRecoveryOutcome())
                    || !reason.equals(link.getReturnRecoveryResolutionReason())) {
                throw conflict("Ручная сверка уже закрыта с другим исходом или причиной");
            }
            if (request.outcome() == PaymentReturnManualResolutionOutcome.ACCEPTED_NOOP) {
                paymentIssueReminderService.resolveOrderIssueInCurrentTransaction(
                        PaymentIssueReminderService.SOURCE_PAYMENT_RETURN_RECONCILIATION,
                        link.getId()
                );
            } else {
                requeueFollowUp(link);
            }
            return response(link);
        }
        if (!PaymentReturnRecoveryState.OUTCOME_MANUAL_RECONCILIATION.equals(link.getReturnRecoveryOutcome())) {
            throw conflict("Возврат не находится в состоянии ручной сверки");
        }
        if (request.outcome() == PaymentReturnManualResolutionOutcome.APPLIED_MANUALLY) {
            requireAppliedManualPostconditions(order, link);
        } else {
            requireAcceptedNoopPostconditions(order);
        }

        String originalManualCause = link.getLastError();
        LocalDateTime resolvedAt = LocalDateTime.now();
        link.setReturnRecoveryOutcome(requestedOutcome);
        link.setReturnRecoveryResolvedAt(resolvedAt);
        link.setReturnRecoveryResolvedBy(actor);
        link.setReturnRecoveryResolutionReason(reason);
        String resolutionSummary = "payment_return_manual_resolution_resolved: outcome="
                + requestedOutcome + "; reason=" + reason;
        link.setLastError(resolutionSummary.length() <= 512
                ? resolutionSummary
                : resolutionSummary.substring(0, 512));
        paymentLinkRepository.saveAndFlush(link);

        if (request.outcome() == PaymentReturnManualResolutionOutcome.ACCEPTED_NOOP) {
            paymentIssueReminderService.resolveOrderIssueInCurrentTransaction(
                    PaymentIssueReminderService.SOURCE_PAYMENT_RETURN_RECONCILIATION,
                    link.getId()
            );
        }
        businessAuditService.recordRequiredInCurrentTransaction(
                "PAYMENT_RETURN_MANUAL_RECONCILIATION_RESOLVED",
                "PAYMENT_LINK",
                link.getId(),
                order.getId(),
                null,
                valueOrUnknown(originalManualCause),
                requestedOutcome,
                "previousOutcome=" + PaymentReturnRecoveryState.OUTCOME_MANUAL_RECONCILIATION
                        + "; originalManualCause=" + valueOrUnknown(originalManualCause)
                        + "; reason=" + reason + "; resolvedBy=" + actor
        );
        if (request.outcome() == PaymentReturnManualResolutionOutcome.APPLIED_MANUALLY) {
            requeueFollowUp(link);
        }
        return response(link);
    }

    private void requeueFollowUp(PaymentLink link) {
        if (link.getStatus() == null) {
            throw conflict("Статус платежной ссылки отсутствует; durable follow-up не создан");
        }
        long sourceVersion = link.getRowVersion() == null ? 0L : link.getRowVersion();
        returnOutboxRepository.requeue(link.getId(), sourceVersion, link.getStatus().name());
    }

    private void requireAppliedManualPostconditions(Order order, PaymentLink link) {
        String statusTitle = order.getStatus() == null || order.getStatus().getTitle() == null
                ? ""
                : order.getStatus().getTitle().trim();
        if (!"Напоминание".equals(statusTitle)) {
            throw conflict("Ручной финансовый откат не подтвержден: заказ не открыт в статусе Напоминание");
        }
        if (order.isComplete() || order.getPayDay() != null) {
            throw conflict("Ручной финансовый откат не подтвержден: заказ все еще содержит признаки оплаты");
        }
        Long checkId = link.getReturnRecoveryPaymentCheckId();
        var activeChecks = paymentCheckRepository.findByOrderIdAndActiveTrue(order.getId());
        if (activeChecks != null && !activeChecks.isEmpty()) {
            throw conflict("Ручной финансовый откат не подтвержден: у заказа остается активный чек");
        }
        if (checkId == null) {
            return;
        }
        if (checkId <= 0) {
            throw conflict("Ручной финансовый откат не подтвержден: идентификатор exact чека поврежден");
        }
        PaymentCheck check = paymentCheckRepository.findByIdForUpdate(checkId)
                .orElseThrow(() -> conflict("Ручной финансовый откат не подтвержден: exact чек не найден"));
        Long companyId = order.getCompany() == null ? null : order.getCompany().getId();
        if (check.isActive()
                || !Objects.equals(check.getOrderId(), order.getId())
                || !Objects.equals(check.getPaymentLinkId(), link.getId())
                || check.getPaidAmount() == null
                || check.getPaidAmount() < 0
                || check.getSum() == null
                || check.getSum().signum() < 0
                || companyId == null
                || !Objects.equals(check.getCompanyId(), companyId)) {
            throw conflict("Ручной финансовый откат не подтвержден: exact чек активен или не согласован с заказом");
        }
    }

    private void requireAcceptedNoopPostconditions(Order order) {
        String statusTitle = order.getStatus() == null || order.getStatus().getTitle() == null
                ? ""
                : order.getStatus().getTitle().trim();
        boolean settled = "Оплачено".equals(statusTitle)
                || (order.isComplete() && order.getPayDay() != null);
        if (!settled) {
            throw conflict("Исход без отката допустим только пока заказ остается финансово оплаченным");
        }
    }

    private String valueOrUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    private PaymentReturnManualResolutionResponse response(PaymentLink link) {
        return new PaymentReturnManualResolutionResponse(
                link.getId(),
                link.getOrder() == null ? null : link.getOrder().getId(),
                PaymentReturnManualResolutionOutcome.valueOf(link.getReturnRecoveryOutcome()),
                link.getReturnRecoveryResolvedAt(),
                link.getReturnRecoveryResolvedBy(),
                link.getReturnRecoveryResolutionReason(),
                link.getReturnRecoveryPaymentCheckId()
        );
    }

    public static String confirmationText(
            PaymentReturnManualResolutionOutcome outcome,
            Long paymentLinkId
    ) {
        String prefix = outcome == PaymentReturnManualResolutionOutcome.APPLIED_MANUALLY
                ? APPLIED_MANUALLY_CONFIRMATION
                : ACCEPTED_NOOP_CONFIRMATION;
        return prefix + paymentLinkId;
    }

    private void requireConfirmation(
            PaymentReturnManualResolutionOutcome outcome,
            Long paymentLinkId,
            String actual
    ) {
        String expected = confirmationText(outcome, paymentLinkId);
        if (actual == null || !expected.equals(actual.trim())) {
            throw badRequest("Текст подтверждения не совпадает с платежной ссылкой; ожидается: " + expected);
        }
    }

    private String requireActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Ручная сверка возврата требует авторизованного оператора");
        }
        boolean allowed = authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .filter(Objects::nonNull)
                .anyMatch(authority -> "ROLE_OWNER".equalsIgnoreCase(authority)
                        || "ROLE_ADMIN".equalsIgnoreCase(authority));
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Недостаточно прав для ручной сверки возврата");
        }
        String actor = authentication.getName().trim();
        return actor.length() <= 150 ? actor : actor.substring(0, 150);
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
