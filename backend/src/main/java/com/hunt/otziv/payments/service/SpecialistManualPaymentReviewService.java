package com.hunt.otziv.payments.service;

import com.hunt.otziv.client_messages.api.ManualPaymentAutomationControl;
import com.hunt.otziv.contractor_payments.api.PaymentReviewTargetAuthorization;
import com.hunt.otziv.manager.api.PaymentReviewOrderAccess;
import com.hunt.otziv.payments.dto.ManagerManualCardPaymentResultResponse;
import com.hunt.otziv.payments.dto.SpecialistManualPaymentReviewResponse;
import com.hunt.otziv.payments.model.PaymentLink;
import com.hunt.otziv.payments.model.PaymentLinkStatus;
import com.hunt.otziv.payments.model.PaymentMethod;
import com.hunt.otziv.payments.model.SpecialistManualPaymentReview;
import com.hunt.otziv.payments.model.SpecialistManualPaymentReviewStatus;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.repository.SpecialistManualPaymentReviewRepository;
import com.hunt.otziv.t_telegrambot.api.SpecialistPaymentReviewNotice;
import com.hunt.otziv.u_users.api.SpecialistPaymentReviewDirectory;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.EnumSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** A manager's report is not an accounting receipt until the specialist's review window ends. */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpecialistManualPaymentReviewService {

    public static final String DELIVERY_ISSUE = "SPECIALIST_PAYMENT_NOTIFICATION";
    public static final String DISPUTE_ISSUE = "SPECIALIST_PAYMENT_DISPUTE";
    public static final String SETTLEMENT_ISSUE = "SPECIALIST_PAYMENT_SETTLEMENT";
    private static final java.time.Duration REVIEW_WINDOW = java.time.Duration.ofHours(24);
    private static final java.time.Duration MAX_NOTIFICATION_DELAY = java.time.Duration.ofMinutes(5);
    private static final EnumSet<SpecialistManualPaymentReviewStatus> OPEN = EnumSet.of(
            SpecialistManualPaymentReviewStatus.PENDING_DELIVERY,
            SpecialistManualPaymentReviewStatus.WAITING,
            SpecialistManualPaymentReviewStatus.SETTLING,
            SpecialistManualPaymentReviewStatus.ADMIN_SETTLING,
            SpecialistManualPaymentReviewStatus.DISPUTED,
            SpecialistManualPaymentReviewStatus.NEEDS_ATTENTION);

    private final SpecialistManualPaymentReviewRepository reviews;
    private final PaymentLinkRepository links;
    private final SpecialistPaymentReviewDirectory users;
    private final PaymentReviewOrderAccess managerAccess;
    private final ManualCardPaymentWorkflow manualPayments;
    private final ManualPaymentAutomationControl invoiceMessages;
    private final SpecialistPaymentReviewNotice telegram;
    private final PaymentIssueReminderService issues;
    private final PaymentReviewTargetAuthorization targetAccess;
    private final SpecialistManualPaymentReviewGuard reviewGuard;
    private final ManualCardRoutePolicy manualRoutePolicy;
    private final ManualPaymentConfirmationWorkflow manualConfirmation;
    private final OrderPaymentIntegrityService orderPaymentIntegrity;
    private final PaymentLinkTransactionExecutor transactions;

    /** Called inside the same transaction that locks Order then PaymentLink. */
    @Transactional(propagation = Propagation.MANDATORY)
    public SpecialistManualPaymentReview request(
            Long orderId,
            Long linkId,
            long amountKopecks,
            Long specialistProfileId,
            String recipientKey,
            String reason,
            String receiptUrl,
            String actor,
            Authentication authentication
    ) {
        orderPaymentIntegrity.lockSpecialistReviewOrderEvidence(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден"));
        managerAccess.requireOrderAccess(orderId, authentication);
        PaymentLink link = links.findByIdForUpdate(linkId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платёжный источник не найден"));
        if (!Objects.equals(link.getOrderId(), orderId)
                || link.getAmountKopecks() != amountKopecks) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Платёжный источник изменился");
        }
        SpecialistManualPaymentReview existing = reviews.lockByPaymentLinkId(linkId).orElse(null);
        if (existing != null) {
            if (existing.getStatus() == SpecialistManualPaymentReviewStatus.CONFIRMED) {
                return existing;
            }
            if (existing.getStatus() == SpecialistManualPaymentReviewStatus.PENDING_DELIVERY
                    || existing.getStatus() == SpecialistManualPaymentReviewStatus.WAITING) {
                if (!Objects.equals(existing.getRecipientProfileId(), specialistProfileId)
                        || !Objects.equals(existing.getRecipientKey(), recipientKey)
                        || existing.getAmountKopecks() != amountKopecks) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Получатель или сумма изменились; открытый запрос специалисту нельзя заменить");
                }
                return existing;
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Платёж специалисту уже оспорен или требует отдельной сверки");
        }
        reviewGuard.requireNoPendingReview(orderId);
        manualPayments.prepareSpecialistReviewRecipient(orderId, link, specialistProfileId,
                recipientKey, reason, receiptUrl, actor);
        Long managerId = users.activeUserIdByUsername(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Менеджер не найден"));
        SpecialistManualPaymentReview review = new SpecialistManualPaymentReview();
        review.setOrderId(orderId);
        review.setPaymentLinkId(linkId);
        review.setAmountKopecks(amountKopecks);
        review.setRecipientProfileId(specialistProfileId);
        review.setRecipientUserId(link.getManualActualRecipientUserId());
        review.setRecipientKey(recipientKey);
        review.setRequestedByUserId(managerId);
        review.setRequestedBy(actor);
        review.setReason(reason);
        review.setReceiptUrl(receiptUrl);
        review.setStatus(SpecialistManualPaymentReviewStatus.PENDING_DELIVERY);
        LocalDateTime requestedAt = LocalDateTime.now();
        review.setRequestedAt(requestedAt);
        review.setDeadlineAt(requestedAt.plus(REVIEW_WINDOW));
        // No further invoice reminders while the reported receipt is checked.
        // The bank session itself remains subject to provider reconciliation.
        invoiceMessages.closePaymentAutomationForManualSettlement(orderId,
                "Менеджер сообщил о переводе специалисту; ожидание 24-часовой сверки");
        links.save(link);
        return reviews.saveAndFlush(review);
    }

    @Transactional(readOnly = true)
    public List<SpecialistManualPaymentReviewResponse> openForAdministration() {
        return reviews.findByStatusInOrderByRequestedAtDesc(OPEN, PageRequest.of(0, 200))
                .stream().filter(review -> targetAccess.canManageUser(review.getRecipientUserId()))
                .map(SpecialistManualPaymentReviewResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public SpecialistManualPaymentReviewResponse readForAdministration(Long reviewId) {
        SpecialistManualPaymentReview review = reviews.findById(reviewId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Запрос не найден"));
        targetAccess.requireCanManagePaymentLink(review.getPaymentLinkId());
        targetAccess.requireCanManageUser(review.getRecipientUserId());
        return SpecialistManualPaymentReviewResponse.from(review);
    }

    @Transactional
    public void resetDeliveryAttempt(Long reviewId) {
        SpecialistManualPaymentReview review = reviews.lockById(reviewId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Запрос не найден"));
        targetAccess.requireCanManagePaymentLink(review.getPaymentLinkId());
        targetAccess.requireCanManageUser(review.getRecipientUserId());
        if (review.getStatus() != SpecialistManualPaymentReviewStatus.PENDING_DELIVERY
                || review.getRequestedAt() == null
                || !LocalDateTime.now().isBefore(review.getRequestedAt().plus(MAX_NOTIFICATION_DELAY))
                || !LocalDateTime.now().isBefore(review.getDeadlineAt())
                || (review.getLastAttemptAt() != null && review.getLastError() == null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Уведомление нельзя повторить; требуется сверка платежа");
        }
        review.setLastAttemptAt(null);
        reviews.save(review);
    }

    /** Recognizes only an already reconciled payment-link outcome; this does not mutate money. */
    @Transactional
    public SpecialistManualPaymentReviewResponse reconcileOperatorOutcome(Long reviewId) {
        SpecialistManualPaymentReview snapshot = reviews.findById(reviewId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Запрос не найден"));
        targetAccess.requireCanManagePaymentLink(snapshot.getPaymentLinkId());
        targetAccess.requireCanManageUser(snapshot.getRecipientUserId());
        boolean settledOrderEvidence = orderPaymentIntegrity.lockSpecialistReviewOrderEvidence(snapshot.getOrderId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден"));
        PaymentLink link = links.findByIdForUpdate(snapshot.getPaymentLinkId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платёжный источник не найден"));
        SpecialistManualPaymentReview review = reviews.lockById(reviewId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Запрос не найден"));
        if (review.getStatus() == SpecialistManualPaymentReviewStatus.CONFIRMED
                || review.getStatus() == SpecialistManualPaymentReviewStatus.SUPERSEDED) {
            return SpecialistManualPaymentReviewResponse.from(review);
        }
        boolean confirmedDirectManual = link.getStatus() == PaymentLinkStatus.CONFIRMED
                && Objects.equals(link.getConfirmedAmountKopecks(), review.getAmountKopecks())
                && link.getManualConfirmedAt() != null;
        boolean confirmedBankHandoff = manualRoutePolicy.isCompletedManualCardPayment(link);
        if (providerPaidForThisReview(settledOrderEvidence, link, review)) {
            supersedeAfterBankPayment(review);
        } else if ((confirmedDirectManual || confirmedBankHandoff)
                && manualRoutePolicy.isSpecialistReviewRecipient(link,
                        review.getRecipientUserId(), review.getRecipientProfileId())) {
            review.setStatus(SpecialistManualPaymentReviewStatus.CONFIRMED);
            review.setSettledAt(LocalDateTime.now());
        } else if (link.getStatus() == PaymentLinkStatus.CANCELED
                && link.getLastError() != null
                && link.getLastError().startsWith(ManualPaymentConfirmationWorkflow.MANUAL_UNPAID_CLOSED_AUDIT_PREFIX)) {
            review.setStatus(SpecialistManualPaymentReviewStatus.SUPERSEDED);
        } else {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Сначала завершите сверку поступления или отсутствия платежа в журнале банка");
        }
        review.setLastError(null);
        reviews.save(review);
        issues.resolveOrderIssueInCurrentTransaction(DELIVERY_ISSUE, reviewId);
        issues.resolveOrderIssueInCurrentTransaction(DISPUTE_ISSUE, reviewId);
        issues.resolveOrderIssueInCurrentTransaction(SETTLEMENT_ISSUE, reviewId);
        return SpecialistManualPaymentReviewResponse.from(review);
    }

    /** A bank receipt on this exact link takes precedence over the manager's transfer claim. */
    public boolean supersedeOnConfirmedBankPayment(Long reviewId) {
        return transactions.required(() -> {
            SpecialistManualPaymentReview snapshot = reviews.findById(reviewId).orElse(null);
            if (snapshot == null) {
                return false;
            }
            Boolean settledOrderEvidence = orderPaymentIntegrity.lockSpecialistReviewOrderEvidence(snapshot.getOrderId())
                    .orElse(null);
            PaymentLink link = links.findByIdForUpdate(snapshot.getPaymentLinkId()).orElse(null);
            SpecialistManualPaymentReview review = reviews.lockById(reviewId).orElse(null);
            if (settledOrderEvidence == null || link == null || review == null) {
                return false;
            }
            if (review.getStatus() == SpecialistManualPaymentReviewStatus.SUPERSEDED) {
                return true;
            }
            if (review.getStatus() == SpecialistManualPaymentReviewStatus.CONFIRMED
                    || !providerPaidForThisReview(settledOrderEvidence, link, review)) {
                return false;
            }
            supersedeAfterBankPayment(review);
            reviews.save(review);
            issues.resolveOrderIssueInCurrentTransaction(DELIVERY_ISSUE, reviewId);
            issues.resolveOrderIssueInCurrentTransaction(DISPUTE_ISSUE, reviewId);
            issues.resolveOrderIssueInCurrentTransaction(SETTLEMENT_ISSUE, reviewId);
            return true;
        });
    }

    private boolean providerPaidForThisReview(boolean settledOrderEvidence, PaymentLink link,
                                               SpecialistManualPaymentReview review) {
        if (link == null || review == null || link.getOrderId() == null) {
            return false;
        }
        PaymentMethod method = link.getPaymentMethod();
        return (method == PaymentMethod.BANK_FORM || method == PaymentMethod.SBP_QR)
                && link.getStatus() == PaymentLinkStatus.CONFIRMED
                && Objects.equals(link.getOrderId(), review.getOrderId())
                && Objects.equals(link.getId(), review.getPaymentLinkId())
                && link.getAmountKopecks() == review.getAmountKopecks()
                && Objects.equals(link.getConfirmedAmountKopecks(), review.getAmountKopecks())
                && link.getPaidAt() != null
                && link.getManualConfirmedAt() == null
                && link.getTbankPaymentId() != null && !link.getTbankPaymentId().isBlank()
                && settledOrderEvidence;
    }

    private void supersedeAfterBankPayment(SpecialistManualPaymentReview review) {
        review.setStatus(SpecialistManualPaymentReviewStatus.SUPERSEDED);
        review.setResolvedAt(LocalDateTime.now());
        review.setResolvedBy("provider");
        review.setResolutionKind("BANK_PROVIDER_CONFIRMED");
        review.setResolutionNote("Оплата подтверждена банком по исходной ссылке; ручное зачисление не требуется");
        review.setLastError(null);
    }

    /** Human-verified absence dismisses only the external-transfer claim, never a bank payment. */
    @Transactional
    public SpecialistManualPaymentReviewResponse markAbsent(
            Long reviewId, boolean statementChecked, boolean paymentAbsent,
            String note, Authentication authentication) {
        String cleanNote = note == null ? "" : note.trim();
        if (!statementChecked || !paymentAbsent || cleanNote.isBlank() || cleanNote.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Подтвердите проверку выписки специалиста, отсутствие перевода и укажите заметку");
        }
        SpecialistManualPaymentReview snapshot = reviews.findById(reviewId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Запрос не найден"));
        targetAccess.requireCanManagePaymentLink(snapshot.getPaymentLinkId());
        targetAccess.requireCanManageUser(snapshot.getRecipientUserId());
        boolean settledOrderEvidence = orderPaymentIntegrity.lockSpecialistReviewOrderEvidence(snapshot.getOrderId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Заказ не найден"));
        PaymentLink link = links.findByIdForUpdate(snapshot.getPaymentLinkId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Платёжный источник не найден"));
        SpecialistManualPaymentReview review = reviews.lockById(reviewId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Запрос не найден"));
        if (!OPEN.contains(review.getStatus())
                || review.getStatus() == SpecialistManualPaymentReviewStatus.SETTLING
                || review.getStatus() == SpecialistManualPaymentReviewStatus.ADMIN_SETTLING
                || link.getManualConfirmedAt() != null
                || manualRoutePolicy.isCompletedManualCardPayment(link)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Заявление уже закрыто или зачисление началось; проверьте источник оплаты");
        }
        if (providerPaidForThisReview(settledOrderEvidence, link, review)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Банк уже подтвердил оплату этого счёта; сначала выполните сверку банковского поступления");
        }
        // Both review dismissal and source closure share this transaction. The generic
        // close operation can proceed only after this review is no longer pending.
        review.setStatus(SpecialistManualPaymentReviewStatus.SUPERSEDED);
        review.setResolvedAt(LocalDateTime.now());
        review.setResolvedBy(authentication.getName());
        review.setResolutionKind("PAYMENT_ABSENT");
        review.setResolutionNote(cleanNote);
        review.setLastError(null);
        reviews.saveAndFlush(review);
        if (link.getPaymentMethod() == PaymentMethod.MANUAL_MOBILE_BANK
                || link.getPaymentMethod() == PaymentMethod.MANUAL_EXTERNAL_LINK) {
            if (link.getStatus() == PaymentLinkStatus.WAITING_MANUAL_PAYMENT
                    || link.getStatus() == PaymentLinkStatus.MANUAL_REPORTED) {
                manualConfirmation.closeManualAsUnpaid(link.getId(), true, true, cleanNote,
                        authentication.getName(), authentication);
            } else if (link.getStatus() != PaymentLinkStatus.CANCELED
                    && link.getStatus() != PaymentLinkStatus.EXPIRED
                    && link.getStatus() != PaymentLinkStatus.REJECTED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Сначала закройте текущую ручную платёжную инструкцию");
            }
        } else if (link.getPaymentMethod() != PaymentMethod.BANK_FORM
                && link.getPaymentMethod() != PaymentMethod.SBP_QR) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Неизвестный тип платёжного источника; нужна отдельная сверка");
        }
        issues.resolveOrderIssueInCurrentTransaction(DELIVERY_ISSUE, reviewId);
        issues.resolveOrderIssueInCurrentTransaction(DISPUTE_ISSUE, reviewId);
        issues.resolveOrderIssueInCurrentTransaction(SETTLEMENT_ISSUE, reviewId);
        return SpecialistManualPaymentReviewResponse.from(review);
    }

    /** The manager's committed report starts the clock; delivery is an additional settlement gate. */
    public void deliver(Long reviewId) {
        if (supersedeOnConfirmedBankPayment(reviewId)) {
            return;
        }
        DeliveryClaim claim = transactions.required(() -> claimDelivery(reviewId));
        if (claim == null) {
            return;
        }
        String message = "💳 Менеджер сообщил о переводе на ваши реквизиты.\n"
                + "Заказ №" + claim.orderId() + ", сумма "
                + BigDecimal.valueOf(claim.amountKopecks(), 2).stripTrailingZeros().toPlainString()
                + " ₽. Проверьте поступление. Если деньги не пришли, нажмите «Оспорить» в течение 24 часов"
                + " от отметки менеджера. Если уведомление задержится, оплату проверят вручную.";
        boolean accepted;
        try {
            accepted = telegram.sendPaymentReviewNoticeOnce(claim.chatId(), message,
                    "⚠️ Оспорить поступление", "specialist-payment-dispute:" + reviewId);
        } catch (RuntimeException failure) {
            // Telegram may have accepted the request before the connection failed. Never resend it.
            transactions.required(() -> finishDelivery(claim, null));
            return;
        }
        transactions.required(() -> finishDelivery(claim, accepted));
    }

    private DeliveryClaim claimDelivery(Long reviewId) {
        SpecialistManualPaymentReview review = reviews.lockById(reviewId).orElse(null);
        if (review == null || review.getStatus() != SpecialistManualPaymentReviewStatus.PENDING_DELIVERY) {
            return null;
        }
        // MySQL stores last_attempt_at as DATETIME(6). Keep the claim token at
        // that precision so the next transaction can match the persisted value.
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        if (review.getDeadlineAt() == null || !now.isBefore(review.getDeadlineAt())) {
            review.setStatus(SpecialistManualPaymentReviewStatus.NEEDS_ATTENTION);
            review.setLastError("За 24 часа не было подтверждено уведомление специалиста");
            reviews.save(review);
            issues.notifyOrderIssueAfterCommit(review.getOrderId(), DELIVERY_ISSUE, review.getId(),
                    "Не доставлено сообщение специалисту по заказу №" + review.getOrderId(),
                    review.getLastError() + ". Автоматическое зачисление заблокировано.");
            return null;
        }
        if (review.getRequestedAt() == null
                || !now.isBefore(review.getRequestedAt().plus(MAX_NOTIFICATION_DELAY))) {
            quarantineDelivery(review, "Уведомление задержалось более чем на 5 минут после отметки менеджера");
            return null;
        }
        if (review.getLastAttemptAt() != null) {
            if (review.getLastError() == null) {
                // The attempt was committed before the network call. A crash leaves its outcome unknown.
                if (!review.getLastAttemptAt().isAfter(now.minus(MAX_NOTIFICATION_DELAY))) {
                    quarantineDelivery(review, "Исход предыдущей отправки Telegram не определён");
                }
                return null;
            }
            if (review.getLastAttemptAt().isAfter(now.minusMinutes(1))) {
                return null;
            }
        }
        Long chatId = users.activePersonalTelegramChatId(review.getRecipientUserId()).orElse(null);
        if (chatId == null) {
            notificationFailure(review, "У специалиста нет активного личного Telegram-чата");
            return null;
        }
        review.setAttemptCount(review.getAttemptCount() + 1);
        review.setLastAttemptAt(now);
        review.setLastError(null);
        reviews.saveAndFlush(review);
        return new DeliveryClaim(review.getId(), review.getOrderId(), review.getAmountKopecks(),
                chatId, now);
    }

    private Void finishDelivery(DeliveryClaim claim, Boolean accepted) {
        SpecialistManualPaymentReview review = reviews.lockById(claim.reviewId()).orElse(null);
        if (review == null || review.getStatus() != SpecialistManualPaymentReviewStatus.PENDING_DELIVERY
                || !Objects.equals(review.getLastAttemptAt(), claim.attemptAt())) {
            return null;
        }
        if (accepted == null) {
            quarantineDelivery(review, "Исход отправки Telegram не определён; повтор заблокирован");
            return null;
        }
        if (!accepted) {
            review.setLastError("Telegram отклонил отправку уведомления");
            reviews.save(review);
            issues.notifyOrderIssueAfterCommit(review.getOrderId(), DELIVERY_ISSUE, review.getId(),
                    "Не доставлено сообщение специалисту по заказу №" + review.getOrderId(),
                    review.getLastError() + ". Отсчёт идёт с отметки менеджера; зачисления пока не будет.");
            return null;
        }
        LocalDateTime deliveredAt = LocalDateTime.now();
        review.setNotifiedAt(deliveredAt);
        if (!deliveredAt.isBefore(review.getDeadlineAt())
                || !deliveredAt.isBefore(review.getRequestedAt().plus(MAX_NOTIFICATION_DELAY))) {
            quarantineDelivery(review, "Уведомление принято Telegram с задержкой; требуется ручная сверка");
            return null;
        }
        review.setStatus(SpecialistManualPaymentReviewStatus.WAITING);
        review.setLastError(null);
        reviews.save(review);
        issues.resolveOrderIssueInCurrentTransaction(DELIVERY_ISSUE, review.getId());
        return null;
    }

    private void quarantineDelivery(SpecialistManualPaymentReview review, String reason) {
        review.setStatus(SpecialistManualPaymentReviewStatus.NEEDS_ATTENTION);
        review.setLastError(reason);
        reviews.save(review);
        issues.notifyOrderIssueAfterCommit(review.getOrderId(), DELIVERY_ISSUE, review.getId(),
                "Оплата специалисту требует ручной сверки: заказ №" + review.getOrderId(), reason);
    }

    private void notificationFailure(SpecialistManualPaymentReview review, String reason) {
        review.setLastError(reason);
        review.setAttemptCount(review.getAttemptCount() + 1);
        review.setLastAttemptAt(LocalDateTime.now());
        reviews.save(review);
        issues.notifyOrderIssueAfterCommit(review.getOrderId(), DELIVERY_ISSUE, review.getId(),
                "Не доставлено сообщение специалисту по заказу №" + review.getOrderId(),
                reason + ". Отсчёт 24 часов идёт с отметки менеджера, но без подтверждённой отправки оплата не будет зачтена автоматически.");
    }

    @Transactional
    public String dispute(Long reviewId, Long telegramUserId) {
        SpecialistManualPaymentReview review = reviews.lockById(reviewId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Запрос не найден"));
        Long recipientChatId = users.activePersonalTelegramChatId(review.getRecipientUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Получатель не найден"));
        if (telegramUserId == null || !telegramUserId.equals(recipientChatId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Оспорить поступление может только указанный специалист");
        }
        if (review.getStatus() == SpecialistManualPaymentReviewStatus.DISPUTED) {
            return "Поступление уже оспорено; владельцы и администраторы уведомлены";
        }
        LocalDateTime now = LocalDateTime.now();
        if (review.getStatus() != SpecialistManualPaymentReviewStatus.WAITING
                || review.getDeadlineAt() == null || !now.isBefore(review.getDeadlineAt())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Окно спора закрыто или платёж уже перешёл к сверке");
        }
        review.setStatus(SpecialistManualPaymentReviewStatus.DISPUTED);
        review.setDisputedAt(now);
        reviews.save(review);
        issues.notifyOrderIssueAfterCommit(review.getOrderId(), DISPUTE_ISSUE, review.getId(),
                "Специалист оспорил оплату заказа №" + review.getOrderId(),
                "Специалист сообщил, что перевод "
                        + BigDecimal.valueOf(review.getAmountKopecks(), 2).toPlainString()
                        + " ₽ не поступил. Автоматическое зачисление заблокировано; проверьте выписку и источник №"
                        + review.getPaymentLinkId() + ".");
        return "Спор сохранён. Оплата автоматически не будет зачтена";
    }

    public ManagerManualCardPaymentResultResponse result(SpecialistManualPaymentReview review) {
        if (review.getStatus() == SpecialistManualPaymentReviewStatus.CONFIRMED) {
            return ManagerManualCardPaymentResultResponse.completed(review.getOrderId(), review.getPaymentLinkId());
        }
        return ManagerManualCardPaymentResultResponse.specialistReviewPending(
                review.getOrderId(), review.getPaymentLinkId());
    }

    private record DeliveryClaim(Long reviewId, Long orderId, long amountKopecks,
                                 Long chatId, LocalDateTime attemptAt) {
    }
}
