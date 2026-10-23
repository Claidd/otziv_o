package com.hunt.otziv.payments.service;

import com.hunt.otziv.contractor_payments.api.PaymentReviewTargetAuthorization;
import com.hunt.otziv.payments.dto.SpecialistManualPaymentReviewResponse;
import com.hunt.otziv.payments.model.SpecialistManualPaymentReview;
import com.hunt.otziv.payments.model.SpecialistManualPaymentReviewStatus;
import com.hunt.otziv.payments.repository.SpecialistManualPaymentReviewRepository;
import com.hunt.otziv.u_users.api.SpecialistPaymentReviewDirectory;
import org.springframework.security.core.GrantedAuthority;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Posts an authorised receipt immediately; the deadline only finalises its dispute metadata. */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpecialistManualPaymentReviewSettlementService {

    private final SpecialistManualPaymentReviewRepository reviews;
    private final PaymentLinkTransactionExecutor transactions;
    private final ManualCardPaymentWorkflow manualPayments;
    private final SpecialistPaymentReviewDirectory users;
    private final PaymentIssueReminderService issues;
    private final PaymentReviewTargetAuthorization targetAccess;
    private final SpecialistManualPaymentReviewService reviewReconciliation;

    /** Owner/admin evidence can resolve a dispute before the automatic deadline. */
    public SpecialistManualPaymentReviewResponse verifyReceived(
            Long reviewId, boolean statementChecked, boolean paymentReceived,
            Long receivedAmountKopecks, String note, Authentication authentication) {
        String cleanNote = note == null ? "" : note.trim();
        if (!statementChecked || !paymentReceived || receivedAmountKopecks == null
                || receivedAmountKopecks <= 0 || cleanNote.isBlank() || cleanNote.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Подтвердите выписку, поступление, точную сумму и заметку");
        }
        Claim claim = transactions.required(() -> claimVerified(
                reviewId, receivedAmountKopecks, cleanNote, authentication));
        if (claim == null) {
            return reviews.findById(reviewId).map(SpecialistManualPaymentReviewResponse::from)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Запрос не найден"));
        }
        try {
            if (!claim.alreadyCredited()) manualPayments.confirmRecipientReviewPayment(
                    reviewId, claim.linkId(), receivedAmountKopecks,
                    "Выписка получателя проверена: " + cleanNote,
                    claim.receiptUrl(), authentication.getName(), authentication,
                    new ManualCardPaymentWorkflow.ManualCardPaymentContext(
                            ManualCardPaymentWorkflow.ManualCardPaymentMode.OWNER_VERIFIED,
                            claim.reason()),
                    claim.recipientType(), claim.profileId(), claim.recipientKey());
            SpecialistManualPaymentReviewResponse response = transactions.required(() -> {
                SpecialistManualPaymentReview review = reviews.lockById(reviewId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Запрос не найден"));
                if (review.getStatus() != SpecialistManualPaymentReviewStatus.ADMIN_SETTLING) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Состояние сверки изменилось");
                }
                review.setStatus(SpecialistManualPaymentReviewStatus.CONFIRMED);
                review.setSettledAt(LocalDateTime.now());
                review.setResolvedAt(LocalDateTime.now());
                review.setLastError(null);
                reviews.save(review);
                issues.resolveOrderIssueInCurrentTransaction(
                        SpecialistManualPaymentReviewService.DELIVERY_ISSUE, reviewId);
                issues.resolveOrderIssueInCurrentTransaction(
                        SpecialistManualPaymentReviewService.DISPUTE_ISSUE, reviewId);
                issues.resolveOrderIssueInCurrentTransaction(
                        SpecialistManualPaymentReviewService.SETTLEMENT_ISSUE, reviewId);
                return SpecialistManualPaymentReviewResponse.from(review);
            });
            return response;
        } catch (RuntimeException failure) {
            if (reviewReconciliation.supersedeOnConfirmedBankPayment(reviewId)) {
                return reviews.findById(reviewId).map(SpecialistManualPaymentReviewResponse::from)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Запрос не найден"));
            }
            transactions.required(() -> {
                SpecialistManualPaymentReview review = reviews.lockById(reviewId).orElse(null);
                if (review != null && review.getStatus() == SpecialistManualPaymentReviewStatus.ADMIN_SETTLING) {
                    review.setStatus(SpecialistManualPaymentReviewStatus.NEEDS_ATTENTION);
                    review.setLastError(limit(failure.getMessage(), 512));
                    reviews.save(review);
                    issues.notifyOrderIssueAfterCommit(review.getOrderId(),
                            SpecialistManualPaymentReviewService.SETTLEMENT_ISSUE, reviewId,
                            "Оплата специалисту требует сверки: заказ №" + review.getOrderId(),
                            "Ручная проверка не завершена: " + review.getLastError());
                }
                return null;
            });
            throw failure;
        }
    }

    private Claim claimVerified(Long reviewId, long receivedAmountKopecks,
                                String note, Authentication authentication) {
        SpecialistManualPaymentReview review = reviews.lockById(reviewId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Запрос не найден"));
        targetAccess.requireCanManagePaymentLink(review.getPaymentLinkId());
        targetAccess.requireCanManageUser(review.getRecipientUserId());
        if (review.getAmountKopecks() != receivedAmountKopecks) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Сумма отличается от запроса менеджера");
        }
        if (review.getStatus() == SpecialistManualPaymentReviewStatus.CONFIRMED) {
            return null;
        }
        boolean staleAdminAttempt = review.getStatus() == SpecialistManualPaymentReviewStatus.ADMIN_SETTLING
                && review.getLastAttemptAt() != null
                && review.getLastAttemptAt().isBefore(LocalDateTime.now().minusMinutes(15));
        if (review.getStatus() != SpecialistManualPaymentReviewStatus.DISPUTED
                && review.getStatus() != SpecialistManualPaymentReviewStatus.NEEDS_ATTENTION
                && review.getStatus() != SpecialistManualPaymentReviewStatus.WAITING
                && !staleAdminAttempt) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Запрос нельзя подтвердить в текущем состоянии");
        }
        review.setStatus(SpecialistManualPaymentReviewStatus.ADMIN_SETTLING);
        review.setLastAttemptAt(LocalDateTime.now());
        review.setAttemptCount(review.getAttemptCount() + 1);
        review.setResolutionKind("ADMIN_VERIFIED_RECEIPT");
        review.setResolutionNote(note);
        review.setResolvedBy(authentication.getName());
        reviews.save(review);
        return new Claim(review.getOrderId(), review.getPaymentLinkId(), review.getAmountKopecks(),
                review.getRecipientProfileId(), review.getRecipientKey(), review.getReason(),
                review.getReceiptUrl(), authentication.getName(), List.of(), review.getRecipientType(), review.getCreditedAt() != null);
    }

    /** Idempotent finalisation: never calls a money-posting workflow. */
    public void settle(Long reviewId) {
        transactions.required(() -> {
            SpecialistManualPaymentReview review = reviews.lockById(reviewId).orElse(null);
            if (review == null || review.getCreditedAt() == null || review.getDeadlineAt() == null
                    || LocalDateTime.now().isBefore(review.getDeadlineAt()) || review.getDisputedAt() != null
                    || review.getStatus() == SpecialistManualPaymentReviewStatus.DISPUTED
                    || review.getStatus() == SpecialistManualPaymentReviewStatus.ADMIN_SETTLING
                    || review.getStatus() == SpecialistManualPaymentReviewStatus.CONFIRMED
                    || review.getStatus() == SpecialistManualPaymentReviewStatus.SUPERSEDED) return null;
            review.setStatus(SpecialistManualPaymentReviewStatus.CONFIRMED);
            review.setSettledAt(LocalDateTime.now());
            review.setResolutionKind("NO_OBJECTION_24H");
            review.setResolvedAt(LocalDateTime.now());
            review.setResolvedBy("system");
            review.setResolutionNote("За 24 часа от отметки менеджера спор не зарегистрирован; банковская проверка не выполнялась");
            reviews.save(review);
            issues.resolveOrderIssueInCurrentTransaction(SpecialistManualPaymentReviewService.SETTLEMENT_ISSUE, reviewId);
            return null;
        });
    }

    /** Used by new submissions and narrowly eligible legacy manager reports, never by bank callbacks. */
    public void creditNow(Long reviewId) {
        if (reviewReconciliation.supersedeOnConfirmedBankPayment(reviewId)) return;
        Claim claim = transactions.required(() -> claimImmediate(reviewId));
        if (claim == null) return;
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                claim.actor(), null, claim.authorities());
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        try {
            manualPayments.confirmRecipientReviewPayment(reviewId, claim.linkId(), claim.amountKopecks(),
                    "Причина менеджера: " + claim.reason(), claim.receiptUrl(), claim.actor(), authentication,
                    new ManualCardPaymentWorkflow.ManualCardPaymentContext(
                            ManualCardPaymentWorkflow.ManualCardPaymentMode.MANAGER_REPORTED, claim.reason()),
                    claim.recipientType(), claim.profileId(), claim.recipientKey());
            if (!transactions.required(() -> finishImmediate(reviewId))) {
                throw new IllegalStateException("Финансовая транзакция не сохранила подтверждение зачисления");
            }
        } catch (RuntimeException failure) {
            // A failure after commit must not turn a proven receipt into an unpaid retry.
            if (transactions.required(() -> finishImmediate(reviewId))) return;
            if (reviewReconciliation.supersedeOnConfirmedBankPayment(reviewId)) return;
            recordFailure(reviewId, claim.orderId(), failure);
            throw failure;
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private Claim claimImmediate(Long reviewId) {
        SpecialistManualPaymentReview review = reviews.lockById(reviewId).orElse(null);
        if (review == null) return null;
        if (review.getCreditedAt() != null) {
            finishImmediate(reviewId);
            return null;
        }
        boolean notificationOnly = review.getStatus() == SpecialistManualPaymentReviewStatus.NEEDS_ATTENTION
                && review.getLastError() != null
                && (review.getLastError().startsWith("Уведомление")
                    || review.getLastError().startsWith("Исход отправки Telegram")
                    || review.getLastError().startsWith("Исход предыдущей отправки Telegram")
                    || review.getLastError().startsWith("За 24 часа не было подтверждено уведомление"));
        boolean staleClaim = review.getStatus() == SpecialistManualPaymentReviewStatus.SETTLING
                && review.getCreditClaimedAt() != null
                && review.getCreditClaimedAt().isBefore(LocalDateTime.now().minusMinutes(15));
        if (review.getDisputedAt() != null || review.getResolvedAt() != null
                || review.getRequestedAt() == null || review.getDeadlineAt() == null
                || review.getAmountKopecks() <= 0 || review.getRecipientType() == null
                || review.getRecipientKey() == null || review.getRecipientKey().isBlank()
                || (review.getStatus() != SpecialistManualPaymentReviewStatus.PENDING_DELIVERY
                    && review.getStatus() != SpecialistManualPaymentReviewStatus.WAITING
                    && !notificationOnly && !staleClaim)) return null;
        SpecialistPaymentReviewDirectory.Actor original = users.activeActor(review.getRequestedByUserId()).orElse(null);
        if (original == null) {
            review.setStatus(SpecialistManualPaymentReviewStatus.NEEDS_ATTENTION);
            review.setLastError("Исходный менеджер больше не имеет активного доступа; требуется ручная сверка");
            reviews.save(review);
            return null;
        }
        review.setStatus(SpecialistManualPaymentReviewStatus.SETTLING);
        review.setCreditClaimedAt(LocalDateTime.now());
        review.setAttemptCount(review.getAttemptCount() + 1);
        reviews.saveAndFlush(review);
        return new Claim(review.getOrderId(), review.getPaymentLinkId(), review.getAmountKopecks(),
                review.getRecipientProfileId(), review.getRecipientKey(), review.getReason(), review.getReceiptUrl(),
                original.username(), original.authorities(), review.getRecipientType(), false);
    }

    private boolean finishImmediate(Long reviewId) {
        SpecialistManualPaymentReview review = reviews.lockById(reviewId).orElse(null);
        if (review == null || review.getCreditedAt() == null) return false;
        if (review.getStatus() == SpecialistManualPaymentReviewStatus.SETTLING) {
            review.setStatus(review.getNotifiedAt() == null
                    ? SpecialistManualPaymentReviewStatus.PENDING_DELIVERY : SpecialistManualPaymentReviewStatus.WAITING);
            // Existing ambiguous/delayed notification remains quarantined; it is never resent.
            if (review.getNotifiedAt() == null && review.getLastError() != null) {
                review.setStatus(SpecialistManualPaymentReviewStatus.NEEDS_ATTENTION);
            }
            reviews.save(review);
        }
        return true;
    }

    private void recordFailure(Long reviewId, Long orderId, RuntimeException failure) {
        try {
            transactions.required(() -> {
                SpecialistManualPaymentReview review = reviews.lockById(reviewId).orElse(null);
                if (review == null || review.getStatus() != SpecialistManualPaymentReviewStatus.SETTLING) {
                    return null;
                }
                String reason = failure instanceof ResponseStatusException status
                        ? status.getReason() : failure.getMessage();
                review.setLastError(limit(reason == null ? failure.getClass().getSimpleName() : reason, 512));
                boolean retryable = failure instanceof ResponseStatusException status
                        && status.getStatusCode().is5xxServerError();
                if (retryable && review.getAttemptCount() < 3) {
                    review.setStatus(SpecialistManualPaymentReviewStatus.WAITING);
                } else {
                    review.setStatus(SpecialistManualPaymentReviewStatus.NEEDS_ATTENTION);
                    issues.notifyOrderIssueAfterCommit(orderId,
                            SpecialistManualPaymentReviewService.SETTLEMENT_ISSUE, reviewId,
                            "Оплата специалисту требует сверки: заказ №" + orderId,
                            "Автоматическое зачисление остановлено. " + review.getLastError());
                }
                reviews.save(review);
                return null;
            });
        } catch (RuntimeException recordingFailure) {
            log.error("Cannot record specialist payment review failure reviewId={}", reviewId,
                    recordingFailure);
        }
    }

    private String limit(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private record Claim(Long orderId, Long linkId, long amountKopecks, Long profileId,
                         String recipientKey, String reason, String receiptUrl, String actor,
                         List<GrantedAuthority> authorities, String recipientType, boolean alreadyCredited) {
    }
}
