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

/** Retries only the immutable manager report after its accepted Telegram notice has aged 24h. */
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
            manualPayments.confirmSpecialistReviewPayment(
                    reviewId, claim.linkId(), receivedAmountKopecks,
                    "Выписка получателя проверена: " + cleanNote,
                    claim.receiptUrl(), authentication.getName(), authentication,
                    new ManualCardPaymentWorkflow.ManualCardPaymentContext(
                            ManualCardPaymentWorkflow.ManualCardPaymentMode.OWNER_VERIFIED,
                            cleanNote),
                    claim.profileId(), claim.recipientKey());
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
                review.getReceiptUrl(), authentication.getName(), authentication.getName(), List.of());
    }

    public void settle(Long reviewId) {
        Claim claim = transactions.required(() -> claim(reviewId));
        if (claim == null) {
            return;
        }
        SecurityContext previousContext = SecurityContextHolder.getContext();
        try {
            Authentication auth = new UsernamePasswordAuthenticationToken(
                    claim.username(), null, claim.authorities());
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            manualPayments.confirmSpecialistReviewPayment(
                    reviewId, claim.linkId(), claim.amountKopecks(), "Причина менеджера: " + claim.reason(),
                    claim.receiptUrl(), claim.actor(), auth,
                    new ManualCardPaymentWorkflow.ManualCardPaymentContext(
                            ManualCardPaymentWorkflow.ManualCardPaymentMode.MANAGER_REPORTED,
                            claim.reason()),
                    claim.profileId(), claim.recipientKey());
            transactions.required(() -> {
                SpecialistManualPaymentReview review = reviews.lockById(reviewId).orElse(null);
                if (review != null && review.getStatus() == SpecialistManualPaymentReviewStatus.SETTLING) {
                    review.setStatus(SpecialistManualPaymentReviewStatus.CONFIRMED);
                    review.setSettledAt(LocalDateTime.now());
                    review.setLastError(null);
                    reviews.save(review);
                }
                return null;
            });
            issues.resolveOrderIssue(claim.orderId(),
                    SpecialistManualPaymentReviewService.SETTLEMENT_ISSUE, reviewId);
        } catch (RuntimeException failure) {
            if (!reviewReconciliation.supersedeOnConfirmedBankPayment(reviewId)) {
                recordFailure(reviewId, claim.orderId(), failure);
            }
        } finally {
            SecurityContextHolder.setContext(previousContext);
        }
    }

    private Claim claim(Long id) {
        SpecialistManualPaymentReview review = reviews.lockById(id).orElse(null);
        if (review == null || review.getNotifiedAt() == null || review.getDeadlineAt() == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(review.getDeadlineAt())) {
            return null;
        }
        boolean waiting = review.getStatus() == SpecialistManualPaymentReviewStatus.WAITING;
        boolean abandonedAttempt = review.getStatus() == SpecialistManualPaymentReviewStatus.SETTLING
                && review.getLastAttemptAt() != null
                && review.getLastAttemptAt().isBefore(now.minusMinutes(15));
        if (!waiting && !abandonedAttempt) {
            return null;
        }
        if (review.getLastAttemptAt() != null
                && review.getLastAttemptAt().isAfter(now.minusMinutes(5))) {
            return null;
        }
        SpecialistPaymentReviewDirectory.Actor requester = users.activeActor(review.getRequestedByUserId()).orElse(null);
        if (requester == null) {
            review.setStatus(SpecialistManualPaymentReviewStatus.NEEDS_ATTENTION);
            review.setLastError("Исходный менеджер больше не имеет доступа");
            reviews.save(review);
            issues.notifyOrderIssueAfterCommit(review.getOrderId(),
                    SpecialistManualPaymentReviewService.SETTLEMENT_ISSUE, review.getId(),
                    "Оплата специалисту требует сверки: заказ №" + review.getOrderId(),
                    review.getLastError());
            return null;
        }
        review.setStatus(SpecialistManualPaymentReviewStatus.SETTLING);
        review.setLastAttemptAt(now);
        review.setAttemptCount(review.getAttemptCount() + 1);
        reviews.save(review);
        return new Claim(review.getOrderId(), review.getPaymentLinkId(), review.getAmountKopecks(),
                review.getRecipientProfileId(), review.getRecipientKey(), review.getReason(),
                review.getReceiptUrl(), review.getRequestedBy(), requester.username(),
                requester.authorities());
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
                         String username, List<GrantedAuthority> authorities) {
    }
}
