package com.hunt.otziv.manager_control.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.common_billing.model.CommonInvoice;
import com.hunt.otziv.common_billing.model.CommonInvoiceOrder;
import com.hunt.otziv.common_billing.model.CommonInvoiceStatus;
import com.hunt.otziv.common_billing.repository.CommonInvoiceOrderRepository;
import com.hunt.otziv.common_billing.repository.CommonInvoiceRepository;
import com.hunt.otziv.common_billing.service.CommonPaymentInitFailureClassifier;
import com.hunt.otziv.common_billing.service.CommonInvoicePublicationBlockerService;
import com.hunt.otziv.manager_control.dto.ManagerControlConcreteItemResponse;
import com.hunt.otziv.manager_control.model.ManagerDailyControlItemStatus;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.payments.model.InvoicePaymentMode;
import com.hunt.otziv.payments.repository.PaymentLinkRepository;
import com.hunt.otziv.payments.service.StandaloneBankPaymentPolicy;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Reads invoice control problems and explains the permitted recovery actions. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ManagerControlInvoiceDiagnostics {
    private final CommonInvoiceRepository commonInvoiceRepository;
    private final CommonInvoiceOrderRepository commonInvoiceOrderRepository;
    private final CommonInvoicePublicationBlockerService commonInvoicePublicationBlockerService;
    private final PaymentLinkRepository paymentLinkRepository;
    private final AppSettingService appSettingService;
    /** Field-injected so the constructor and its tests stay unchanged. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.hunt.otziv.common_billing.api.ArchivedCommonInvoiceRevival archivedInvoiceRevival;
    /** Field-injected like the revival seam; absent in narrow tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.hunt.otziv.payments.api.PaperInvoiceReminderProgress paperReminderProgress;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.hunt.otziv.common_billing.api.CommonInvoiceOverdueNotices overdueNotices;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.hunt.otziv.common_billing.api.CommonInvoiceDeliveryWait deliveryWait;
    private static final int OVERDUE_NOTICE_DAYS = com.hunt.otziv.common_billing.api.CommonInvoiceOverdueNotices.NOTICE_DAYS;
    /** A reminder the autoresponder holds longer than this has stopped being ordinary waiting. */
    private static final int AUTORESPONDER_WAIT_LIMIT_HOURS = 48;
    /** The daily manager notice and the escalation run on their own; past this they are late. */
    private static final int OVERDUE_AUTOMATION_GRACE_HOURS = 24;
    /** The expired-link check repeats every 10 minutes; past this it could not close the old link. */
    private static final int EXPIRED_LINK_SELF_REPAIR_MINUTES = 60;

    private static final int COMMON_INVOICE_STALE_DAYS = 3;
    /** A due reminder may wait for business windows and the shared limit; a day late means stalled. */
    private static final int PAYMENT_REMINDER_GRACE_HOURS = 24;
    private static final int DEFAULT_PAYMENT_OVERDUE_DAYS = 30;
    private static final Set<CommonInvoiceStatus> PAYMENT_REMINDER_STATUSES = Set.of(
            CommonInvoiceStatus.INVOICED,
            CommonInvoiceStatus.REMINDER,
            CommonInvoiceStatus.PARTIALLY_PAID
    );
    private static final Pattern HISTORICAL_REVIEW_ID = Pattern.compile(
            "(?iu)(?:отзыв\\s*#|\\breview\\s+#?)\\s*([1-9][0-9]{0,18})(?![0-9])");

    private static final int PAPER_INVOICE_DELIVERY_SLA_HOURS = 24;

    private static final int COMMON_INVOICE_PUBLICATION_BLOCKER_HOURS =
            CommonInvoicePublicationBlockerService.ATTENTION_AFTER_HOURS;

    private static final Set<CommonInvoiceStatus> PAPER_INVOICE_DELIVERY_OPEN_STATUSES = Set.of(
            CommonInvoiceStatus.INVOICED,
            CommonInvoiceStatus.REMINDER,
            CommonInvoiceStatus.PARTIALLY_PAID
    );

    private static final Set<CommonInvoiceStatus> COMMON_INVOICE_CRITICAL_STATUSES = Set.of(
            CommonInvoiceStatus.NEEDS_ATTENTION,
            CommonInvoiceStatus.UNPAID,
            CommonInvoiceStatus.BAN
    );

    private static final Set<CommonInvoiceStatus> COMMON_INVOICE_STALE_STATUSES = Set.of(
            CommonInvoiceStatus.COLLECTING,
            CommonInvoiceStatus.READY,
            CommonInvoiceStatus.INVOICED,
            CommonInvoiceStatus.REMINDER,
            CommonInvoiceStatus.PARTIALLY_PAID
    );

    /**
     * Same attribution as the manager's invoice list: billing account, invoice company or any
     * order of the invoice. A missing invoice is left to the caller's not-found handling.
     */
    boolean isInvoiceAssignedTo(Long invoiceId, Long managerId) {
        if (invoiceId == null || managerId == null) {
            return false;
        }
        CommonInvoice invoice = commonInvoiceRepository.findByIdWithAccount(invoiceId).orElse(null);
        if (invoice == null) {
            return true;
        }
        var account = invoice.getAccount();
        if (account != null) {
            if (account.getManager() != null && managerId.equals(account.getManager().getId())) {
                return true;
            }
            var company = account.getInvoiceCompany();
            if (company != null && company.getManager() != null && managerId.equals(company.getManager().getId())) {
                return true;
            }
        }
        return commonInvoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId).stream()
                .map(CommonInvoiceOrder::getOrder)
                .anyMatch(order -> order != null && order.getManager() != null && managerId.equals(order.getManager().getId()));
    }

    String specialistName(Long invoiceId) {
        if (invoiceId == null) {
            return "";
        }
        try {
            List<String> names = commonInvoiceOrderRepository.findByInvoiceIdWithOrders(invoiceId).stream()
                    .map(CommonInvoiceOrder::getOrder)
                    .map(order -> order == null || order.getWorker() == null ? null : order.getWorker().getUser())
                    .map(this::userDisplayName)
                    .filter(name -> !name.isBlank())
                    .distinct()
                    .toList();
            if (names.size() == 1) {
                return names.getFirst();
            }
            if (names.size() > 1) {
                return names.size() + " специалистов";
            }
            return "";
        } catch (RuntimeException exception) {
            log.warn("Не удалось получить специалистов общего счета invoiceId={}: {}", invoiceId, exception.getMessage());
            return "";
        }
    }

    long countActions(Manager manager, Set<Long> excludedInvoiceIds) {
        if (excludedInvoiceIds == null || excludedInvoiceIds.isEmpty()) {
            return countActions(manager);
        }
        return findActionInvoices(manager).stream()
                .filter(invoice -> !excludedInvoiceIds.contains(invoice.getId()))
                .count();
    }

    long countActions(Manager manager) {
        // The query is a superset: reminder progress of bank paper documents is checked in Java.
        return findActionInvoices(manager).size();
    }

    private List<CommonInvoice> findActionInvoices(Manager manager) {
        LocalDateTime now = LocalDateTime.now();
        java.util.Map<Long, CommonInvoice> byId = new java.util.LinkedHashMap<>();
        findActionInvoiceCandidates(manager).stream()
                .filter(invoice -> !paymentRemindersOnSchedule(invoice, now))
                .filter(invoice -> !handledAutomatically(invoice, now))
                .forEach(invoice -> byId.putIfAbsent(invoice.getId(), invoice));
        // Reminders refresh an ordinary invoice every few days, so the stale rule never sees it.
        commonInvoiceRepository.findManagerOverdueApproachingInvoices(
                        manager, PAYMENT_REMINDER_STATUSES, overdueApproachingSince(now), PageRequest.of(0, 10_000))
                .stream()
                .filter(invoice -> !overdueOwnedByAutoresponder(invoice, now))
                .filter(invoice -> !handledAutomatically(invoice, now))
                .forEach(invoice -> byId.putIfAbsent(invoice.getId(), invoice));
        return List.copyOf(byId.values());
    }

    /** Sent before this moment: the manager notice period before the automatic move has begun. */
    private LocalDateTime overdueApproachingSince(LocalDateTime now) {
        return (now == null ? LocalDateTime.now() : now).minusDays(Math.max(1, paymentOverdueDays() - OVERDUE_NOTICE_DAYS));
    }

    private boolean overdueApproaching(CommonInvoice invoice, LocalDateTime now) {
        return invoice != null
                && PAYMENT_REMINDER_STATUSES.contains(invoice.getStatus())
                && invoice.getSentAt() != null
                && !invoice.getSentAt().isAfter(overdueApproachingSince(now));
    }

    private List<CommonInvoice> findActionInvoiceCandidates(Manager manager) {
        return commonInvoiceRepository.findManagerControlInvoices(
                manager,
                COMMON_INVOICE_CRITICAL_STATUSES,
                effectiveCommonInvoiceStaleStatuses(),
                CommonInvoiceStatus.PARTIALLY_PAID,
                CommonInvoiceStatus.COLLECTING,
                LocalDateTime.now().minusDays(COMMON_INVOICE_STALE_DAYS),
                LocalDateTime.now().minusHours(COMMON_INVOICE_PUBLICATION_BLOCKER_HOURS),
                InvoicePaymentMode.OWNER_PAPER_INVOICE,
                PAPER_INVOICE_DELIVERY_OPEN_STATUSES,
                LocalDateTime.now().minusHours(PAPER_INVOICE_DELIVERY_SLA_HOURS),
                PageRequest.of(0, 10_000)
        );
    }

    void requireResolved(Long invoiceId) {
        if (invoiceId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "У карточки общего счета нет ID счета");
        }
        CommonInvoice invoice = commonInvoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Общий счет не найден"));
        if (!isCommonInvoiceManagerControlProblem(invoice, LocalDateTime.now())) {
            return;
        }
        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Общий счет все еще требует внимания: статус «"
                        + commonInvoiceStatusLabel(invoice.getStatus())
                        + "». Обновите счет в правой панели или используйте «Починить», если кнопка доступна."
        );
    }

    /** Reuse the live board's business rule for historical audit cards as well. */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public boolean resolvedForAudit(Long invoiceId) {
        return invoiceId != null && commonInvoiceRepository.findById(invoiceId)
                .filter(invoice -> !isCommonInvoiceManagerControlProblem(invoice, LocalDateTime.now())).isPresent();
    }

    private boolean isCommonInvoiceManagerControlProblem(CommonInvoice invoice, LocalDateTime now) {
        if (invoice == null) {
            return false;
        }
        if (handledAutomatically(invoice, now)) {
            return false;
        }
        List<CommonInvoiceOrder> invoiceItems = null;
        if (hasText(invoice.getLastError()) && !deliveryHeldByAutoresponder(invoice, now)) {
            if (!commonInvoiceTechnicalTailRepairable(invoice)) {
                return true;
            }
            invoiceItems = commonInvoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
            if (invoiceItems.stream().anyMatch(item -> !item.isPaid())) {
                return true;
            }
        }
        if (hasText(invoice.getPaymentSuccessNotificationError())) {
            return true;
        }
        CommonInvoiceStatus status = invoice.getStatus();
        if (COMMON_INVOICE_CRITICAL_STATUSES.contains(status)) {
            return true;
        }
        if (overdueApproaching(invoice, now) && !overdueOwnedByAutoresponder(invoice, now)) {
            return true;
        }
        if (paymentRemindersOnSchedule(invoice, now)) {
            return false;
        }
        if (status == CommonInvoiceStatus.PARTIALLY_PAID
                && (invoice.getSentAt() == null || invoice.getNextReminderAt() == null)) {
            return true;
        }
        if (isPaperInvoiceDeliveryOverdue(invoice, now)) {
            return true;
        }
        if (invoiceItems == null) {
            invoiceItems = commonInvoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        }
        if (status == CommonInvoiceStatus.COLLECTING
                && commonInvoicePublicationBlockerService.hasOverdueBlockers(invoiceItems, now)) {
            return true;
        }
        LocalDateTime updatedAt = invoice.getUpdatedAt();
        LocalDateTime staleBefore = (now == null ? LocalDateTime.now() : now).minusDays(COMMON_INVOICE_STALE_DAYS);
        if (!effectiveCommonInvoiceStaleStatuses().contains(status)
                || updatedAt == null
                || updatedAt.isAfter(staleBefore)) {
            return false;
        }
        if (status != CommonInvoiceStatus.PARTIALLY_PAID
                && status != CommonInvoiceStatus.COLLECTING) {
            return true;
        }
        return invoiceItems.stream()
                .noneMatch(item -> item != null && !item.isReady());
    }

    /**
     * An issued, unpaid invoice whose automatic payment reminders run on schedule is the
     * autoresponder's work, as for single orders with a healthy payment queue. It returns to the
     * board when reminders finish or stall, or when the payment overdue period has passed.
     */
    boolean paymentRemindersOnSchedule(CommonInvoice invoice, LocalDateTime now) {
        LocalDateTime checkTime = now == null ? LocalDateTime.now() : now;
        if (invoice == null || invoice.getId() == null
                || !PAYMENT_REMINDER_STATUSES.contains(invoice.getStatus())
                || (hasText(invoice.getLastError()) && !deliveryHeldByAutoresponder(invoice, checkTime))
                || hasText(invoice.getPaymentSuccessNotificationError())
                || invoice.getSentAt() == null) {
            return false;
        }
        if (overdueApproaching(invoice, checkTime) && !overdueOwnedByAutoresponder(invoice, checkTime)) {
            return false;
        }
        boolean paperInvoice = invoice.getInvoicePaymentMode() == InvoicePaymentMode.OWNER_PAPER_INVOICE;
        if (paperInvoice && invoice.getPaperInvoiceIssuedAt() == null) {
            return false;
        }
        LocalDateTime staleReminderBefore = checkTime.minusHours(PAYMENT_REMINDER_GRACE_HOURS);
        if (invoice.isPaperInvoiceAutomationHold()) {
            // Bank paper documents own the reminder clock while the invoice clock is paused.
            return paperInvoice
                    && !invoice.isPaperInvoiceReviewHold()
                    && paperReminderProgress != null
                    && paperReminderProgress.remindersOnSchedule(invoice.getId(), staleReminderBefore);
        }
        return invoice.getNextReminderAt() != null && invoice.getNextReminderAt().isAfter(staleReminderBefore);
    }

    /**
     * Situations the system resolves on its own: the card appears only when it could not. An
     * expired T-Bank link parks the invoice while the old bank session is closed; the check repeats
     * every 10 minutes and returns the invoice to work, so only a link still open after an hour is
     * the manager's.
     */
    boolean handledAutomatically(CommonInvoice invoice, LocalDateTime now) {
        LocalDateTime checkTime = now == null ? LocalDateTime.now() : now;
        return commonInvoiceExpiredLinkAttention(invoice)
                && invoice.getUpdatedAt() != null
                && invoice.getUpdatedAt().isAfter(checkTime.minusMinutes(EXPIRED_LINK_SELF_REPAIR_MINUTES));
    }

    boolean commonInvoiceExpiredLinkAttention(CommonInvoice invoice) {
        return invoice != null
                && invoice.getStatus() == CommonInvoiceStatus.NEEDS_ATTENTION
                && safe(invoice.getLastError()).startsWith(
                com.hunt.otziv.common_billing.service.CommonInvoiceCancellationService.EXPIRED_LINK_ATTENTION_PREFIX);
    }

    /**
     * A reminder waiting in the queue for the autoresponder's business window, daily limit, interval
     * or pause was never sent and goes out on its own: that is waiting, not a delivery in doubt.
     * Only a wait longer than {@link #AUTORESPONDER_WAIT_LIMIT_HOURS} reaches the manager.
     */
    boolean deliveryHeldByAutoresponder(CommonInvoice invoice, LocalDateTime now) {
        return autoresponderWait(invoice)
                .filter(wait -> wait.queuedAt() == null || wait.queuedAt().isAfter(
                        (now == null ? LocalDateTime.now() : now).minusHours(AUTORESPONDER_WAIT_LIMIT_HOURS)))
                .isPresent();
    }

    private java.util.Optional<com.hunt.otziv.common_billing.api.CommonInvoiceDeliveryWait.Wait> autoresponderWait(CommonInvoice invoice) {
        if (deliveryWait == null || invoice == null || !hasText(invoice.getPaymentMessageOperationId())) {
            return java.util.Optional.empty();
        }
        String error = safe(invoice.getLastError()).trim().toLowerCase(Locale.ROOT);
        if (!error.equals("message_send_in_progress") && !error.equals("payment_route_changed_message_in_progress")) {
            return java.util.Optional.empty();
        }
        try {
            return deliveryWait.autoresponderWait(invoice.getPaymentMessageOperationId());
        } catch (RuntimeException unavailable) {
            log.debug("Common invoice {} queue state unavailable: {}", invoice.getId(), unavailable.getMessage());
            return java.util.Optional.empty();
        }
    }

    /**
     * Once the manager's group has the overdue notice and the autoresponder is going to move the
     * invoice to "Не оплачено" itself, the invoice is the autoresponder's work. It reaches the board
     * when the move will not happen (manager's decision), when the notice did not go out within a
     * day of the notice period starting, or when the move is more than a day late.
     */
    boolean overdueOwnedByAutoresponder(CommonInvoice invoice, LocalDateTime now) {
        if (overdueNotices == null || invoice == null || invoice.getId() == null || invoice.getSentAt() == null) {
            return false;
        }
        LocalDateTime checkTime = now == null ? LocalDateTime.now() : now;
        if (deliveryHeldByAutoresponder(invoice, checkTime)) {
            // The queued reminder goes first; the overdue rules see the invoice again once it is sent.
            return true;
        }
        if (hasText(invoice.getLastError()) || hasText(invoice.getPaymentSuccessNotificationError())) {
            return false;
        }
        if (overdueNotices.automaticMoveNotBefore(invoice.getId(), checkTime).isEmpty()) {
            return false;
        }
        LocalDateTime noticeAt = invoice.getOverdueNoticeAt();
        if (noticeAt == null || noticeAt.isBefore(invoice.getSentAt())) {
            LocalDateTime noticePeriodStart = invoice.getSentAt().plusDays(Math.max(1, paymentOverdueDays() - OVERDUE_NOTICE_DAYS));
            return noticePeriodStart.plusHours(OVERDUE_AUTOMATION_GRACE_HOURS).isAfter(checkTime);
        }
        return expectedOverdueMove(invoice, noticeAt).plusHours(OVERDUE_AUTOMATION_GRACE_HOURS).isAfter(checkTime);
    }

    private LocalDateTime expectedOverdueMove(CommonInvoice invoice, LocalDateTime noticeAt) {
        LocalDateTime overdueAt = invoice.getSentAt().plusDays(paymentOverdueDays());
        LocalDateTime afterNotice = noticeAt.plusDays(OVERDUE_NOTICE_DAYS);
        return overdueAt.isAfter(afterNotice) ? overdueAt : afterNotice;
    }

    static String autoresponderHoldLabel(String code) {
        return switch (code == null ? "" : code) {
            case "outside_business_window" -> "вне рабочего окна";
            case "daily_limit" -> "исчерпан дневной лимит";
            case "rate_limited" -> "интервал между сообщениями";
            case "automation_paused" -> "автоответчик на паузе или выключен";
            case "live_disabled" -> "отправка клиентских сообщений выключена";
            default -> "ещё не отправлялось";
        };
    }

    private String commonInvoiceOverdueReason(CommonInvoice invoice, LocalDate today) {
        long unpaidDays = daysSince(invoice.getSentAt().toLocalDate(), today);
        String head = "Почему в замечаниях: общий счёт не оплачен " + unpaidDays + " дн. после выставления"
                + (invoice.getPaidKopecks() > 0 ? " (оплачен частично)" : "") + ". ";
        java.util.Optional<LocalDateTime> move = overdueNotices == null || invoice.getId() == null
                ? java.util.Optional.empty()
                : overdueNotices.automaticMoveNotBefore(invoice.getId(), LocalDateTime.now());
        if (move.isPresent()) {
            LocalDateTime noticeAt = invoice.getOverdueNoticeAt();
            boolean noticed = noticeAt != null && !noticeAt.isBefore(invoice.getSentAt());
            String stalled = !noticed
                    ? "Уведомление о просрочке в группу менеджера не ушло больше суток — проверьте группу менеджера и автоответчик. "
                    : expectedOverdueMove(invoice, noticeAt).plusHours(OVERDUE_AUTOMATION_GRACE_HOURS).isBefore(LocalDateTime.now())
                    ? "Автоответчик должен был перевести счёт в «Не оплачено» "
                    + expectedOverdueMove(invoice, noticeAt).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                    + ", но перевод не состоялся — проверьте паузу и переключатели автоответчика. "
                    : "";
            return head + stalled + "Если оплаты не будет, автоответчик переведёт его в «Не оплачено» и создаст плохие задачи "
                    + "не раньше " + move.get().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                    + " — через " + OVERDUE_NOTICE_DAYS + " дня после уведомления в группу менеджера. "
                    + "Свяжитесь с клиентом; если оплата пришла, отметьте её в «Деталях» счёта.";
        }
        return head + "Автоматического перевода в «Не оплачено» для этого счёта не будет"
                + (invoice.getInvoicePaymentMode() == InvoicePaymentMode.OWNER_PAPER_INVOICE ? " (банковский счёт)"
                : invoice.getPaidKopecks() > 0 ? " (есть частичная оплата)"
                : invoice.getClientReportedAt() != null ? " (клиент сообщил об оплате)" : "")
                + ". Решение за менеджером: свяжитесь с клиентом, отметьте оплату или переведите счёт "
                + "в «Не оплачено» вручную в «Деталях».";
    }

    private int paymentOverdueDays() {
        int configured = appSettingService.getInt(
                AppSettingService.CLIENT_MESSAGES_PAYMENT_OVERDUE_DAYS, DEFAULT_PAYMENT_OVERDUE_DAYS);
        // Same reading as CommonInvoiceOverdueEscalation, so the card and the move agree on the date.
        if (configured <= 0) {
            return DEFAULT_PAYMENT_OVERDUE_DAYS;
        }
        return Math.max(OVERDUE_NOTICE_DAYS + 1, Math.min(365, configured));
    }

    private boolean pausedForBankPaperDocuments(CommonInvoice invoice) {
        return invoice != null
                && invoice.getInvoicePaymentMode() == InvoicePaymentMode.OWNER_PAPER_INVOICE
                && invoice.isPaperInvoiceAutomationHold()
                && invoice.getPaperInvoiceIssuedAt() != null;
    }

    private boolean isPaperInvoiceDeliveryOverdue(CommonInvoice invoice, LocalDateTime now) {
        if (invoice == null
                || invoice.getInvoicePaymentMode() != InvoicePaymentMode.OWNER_PAPER_INVOICE
                || invoice.getSentAt() == null
                || invoice.getPaperInvoiceIssuedAt() != null
                || !PAPER_INVOICE_DELIVERY_OPEN_STATUSES.contains(invoice.getStatus())) {
            return false;
        }
        LocalDateTime deliveryDeadline = invoice.getSentAt().plusHours(PAPER_INVOICE_DELIVERY_SLA_HOURS);
        LocalDateTime checkTime = now == null ? LocalDateTime.now() : now;
        return !deliveryDeadline.isAfter(checkTime);
    }

    List<ManagerControlConcreteItemResponse> examples(Manager manager, LocalDate today, int limit, Set<Long> excludedInvoiceIds) {
        return findActionInvoices(manager).stream()
                .filter(invoice -> !excludedInvoiceIds.contains(invoice.getId()))
                .limit(limit)
                .map(invoice -> commonInvoiceExample(invoice, today))
                .toList();
    }

    private Set<CommonInvoiceStatus> effectiveCommonInvoiceStaleStatuses() {
        if (appSettingService.getBoolean(AppSettingService.MANAGER_CONTROL_COLLECTING_STALE_ENABLED, true)) {
            return COMMON_INVOICE_STALE_STATUSES;
        }
        return Set.of(
                CommonInvoiceStatus.READY,
                CommonInvoiceStatus.INVOICED,
                CommonInvoiceStatus.REMINDER,
                CommonInvoiceStatus.PARTIALLY_PAID
        );
    }

    private ManagerControlConcreteItemResponse commonInvoiceExample(CommonInvoice invoice, LocalDate today) {
        String accountName = invoice.getAccount() == null ? "" : safe(invoice.getAccount().getName());
        long remainingKopecks = Math.max(0, invoice.getAmountKopecks() - invoice.getPaidKopecks());
        List<CommonInvoiceOrder> items = commonInvoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        List<CommonInvoiceOrder> publicationBlockers = commonInvoicePublicationBlockerService.overdueBlockers(
                items,
                LocalDateTime.now()
        );
        LocalDateTime attentionStartedAt = publicationBlockers.stream()
                .map(CommonInvoiceOrder::getPublicationBlockerSince)
                .filter(Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .orElse(invoice.getUpdatedAt());
        if (isPaperInvoiceDeliveryOverdue(invoice, LocalDateTime.now())) {
            attentionStartedAt = invoice.getSentAt();
        } else if (overdueApproaching(invoice, LocalDateTime.now()) && !hasText(invoice.getLastError())) {
            attentionStartedAt = invoice.getSentAt().plusDays(Math.max(1, paymentOverdueDays() - OVERDUE_NOTICE_DAYS));
        }
        return new ManagerControlConcreteItemResponse(
                null,
                "COMMON_INVOICE",
                invoice.getId(),
                safe(invoice.getTitle()).isBlank() ? "Общий счет #" + invoice.getId() : invoice.getTitle(),
                commonInvoiceSubtitle(accountName, invoice.getAmountKopecks(), remainingKopecks),
                isPaperInvoiceDeliveryOverdue(invoice, LocalDateTime.now())
                        ? "Нужно отправить счёт"
                        : publicationBlockers.isEmpty()
                        ? commonInvoiceStatusLabel(invoice.getStatus())
                        : "Требует внимания · блокеров " + publicationBlockers.size(),
                attentionStartedAt == null ? null : daysSince(attentionStartedAt.toLocalDate(), today),
                commonInvoiceReason(invoice, today, items, publicationBlockers),
                "/admin/common-billing?invoiceId=" + invoice.getId(),
                null,
                null,
                null,
                null,
                ManagerDailyControlItemStatus.OPEN.name(),
                null,
                null,
                null,
                null,
                null
        ).withSla(attentionStartedAt, null, null, null);
    }

    private String commonInvoiceSubtitle(String accountName, long amountKopecks, long remainingKopecks) {
        List<String> parts = new ArrayList<>();
        if (!accountName.isBlank()) {
            parts.add(accountName);
        }
        parts.add("сумма " + rubles(amountKopecks));
        if (remainingKopecks > 0) {
            parts.add("остаток " + rubles(remainingKopecks));
        }
        return String.join(" · ", parts);
    }

    private String commonInvoiceReason(
            CommonInvoice invoice,
            LocalDate today,
            List<CommonInvoiceOrder> items,
            List<CommonInvoiceOrder> publicationBlockers
    ) {
        if (commonInvoiceRequiresCompositionReview(invoice, items)) {
            long archivedUnpaid = archivedUnpaidPositions(items).size();
            return "В составе частично оплаченного счета остались архивные неоплаченные заказы: "
                    + archivedUnpaid + ". Учтено оплаты " + rubles(invoice.getPaidKopecks())
                    + " из " + rubles(invoice.getAmountKopecks())
                    + "; по сохраненному составу остаток "
                    + rubles(Math.max(0, invoice.getAmountKopecks() - invoice.getPaidKopecks()))
                    + (invoice.getAccount() != null && !invoice.getAccount().isEnabled()
                    ? ". Общий счет компании выключен" : "")
                    + ". Откройте «Счет» и сверьте состав и остаток с ответственным. "
                    + "До сверки повторное напоминание заблокировано; оплата и архивные заказы автоматически не меняются.";
        }
        String lastError = safe(invoice.getLastError());
        String notificationError = safe(invoice.getPaymentSuccessNotificationError());
        if (!notificationError.isBlank() && commonInvoiceTechnicalTailRepairable(invoice)
                && (items == null || items.stream().noneMatch(item -> !item.isPaid()))) {
            return commonInvoicePaymentNotificationReason(invoice, notificationError);
        }
        if (!lastError.isBlank()) {
            return commonInvoiceLastErrorReason(invoice, lastError, items);
        }
        if (!notificationError.isBlank()) {
            return commonInvoicePaymentNotificationReason(invoice, notificationError);
        }
        CommonInvoiceStatus status = invoice.getStatus();
        if (status == CommonInvoiceStatus.NEEDS_ATTENTION) {
            return "Счет требует ручного разбора. Рекомендация: откройте «Счет», проверьте позиции и выберите подходящее действие в правой панели.";
        }
        if (status == CommonInvoiceStatus.UNPAID) {
            return "Счет переведен в «Не оплачено». Рекомендация: проверьте, нужно ли вернуть позиции в работу или закрыть карточку контроля.";
        }
        if (status == CommonInvoiceStatus.BAN) {
            return "Счет в бане. Рекомендация: проверьте причину блокировки в карточке счета.";
        }
        if (isPaperInvoiceDeliveryOverdue(invoice, LocalDateTime.now())) {
            return "Почему в замечаниях: клиент уведомлён о завершении работ, но отправка бумажного счёта "
                    + "не подтверждена более 24 часов. Отправьте документ в клиентский чат, затем откройте "
                    + "«Детали» и нажмите «Счёт отправлен клиенту». До подтверждения клиентские напоминания "
                    + "и отметка оплаты заблокированы.";
        }
        if (overdueApproaching(invoice, LocalDateTime.now())) {
            return commonInvoiceOverdueReason(invoice, today);
        }
        if (publicationBlockers != null && !publicationBlockers.isEmpty()) {
            String blockers = publicationBlockers.stream()
                    .limit(5)
                    .map(item -> {
                        Order order = item.getOrder();
                        String orderId = order == null || order.getId() == null ? "?" : String.valueOf(order.getId());
                        long hours = item.getPublicationBlockerSince() == null
                                ? 0
                                : Math.max(0, Duration.between(item.getPublicationBlockerSince(), LocalDateTime.now()).toHours());
                        String inStatusSince = order == null || order.getStatusChangedAt() == null
                                ? ""
                                : ", в статусе с " + order.getStatusChangedAt().format(
                                        java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm"));
                        return "#" + orderId + " «" + orderStatusTitle(order) + "» (держит сбор "
                                + hours + " ч." + inStatusSince + ")";
                    })
                    .collect(Collectors.joining(", "));
            long publicationOrLater = (items == null ? List.<CommonInvoiceOrder>of() : items).stream()
                    .map(CommonInvoiceOrder::getOrder)
                    .filter(commonInvoicePublicationBlockerService::isPublicationOrLater)
                    .count();
            return "Почему в замечаниях: в общем счете уже есть " + publicationOrLater
                    + " заказ(а) в «Публикации» или выше, но допубликационные позиции держат сбор счёта более 48 часов "
                    + "(отсчёт идёт с выхода первого заказа в «Публикацию», смена статуса внутри проверки его не сбрасывает). "
                    + "Блокеры: " + blockers
                    + ". Проверьте доставку напоминаний и состояние заказов. Состав счета автоматически не меняется.";
        }
        long ageDays = invoice.getUpdatedAt() == null ? 0 : daysSince(invoice.getUpdatedAt().toLocalDate(), today);
        if (status == CommonInvoiceStatus.COLLECTING) {
            long ready = items.stream().filter(CommonInvoiceOrder::isReady).count();
            String waitingStatuses = items.stream()
                    .filter(item -> !item.isReady())
                    .map(CommonInvoiceOrder::getOrder)
                    .filter(Objects::nonNull)
                    .map(this::orderStatusTitle)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .limit(4)
                    .collect(Collectors.joining(", "));
            return "Почему в замечаниях: общий счет уже " + ageDays
                    + " дн. находится в «Сборе» и еще не выставлен клиенту. Готово заказов: "
                    + ready + "/" + items.size()
                    + (waitingStatuses.isBlank() ? "" : "; не готовы статусы: " + waitingStatuses)
                    + ". Нажмите «Починить»: система пересчитает позиции и отправит счет, если все заказы действительно готовы.";
        }
        if (pausedForBankPaperDocuments(invoice)) {
            long sentDays = invoice.getSentAt() == null ? ageDays : daysSince(invoice.getSentAt().toLocalDate(), today);
            return "Почему в замечаниях: банковский счёт выставлен " + sentDays + " дн. назад, оплата не поступила, "
                    + "а напоминания по нему завершены, остановлены или их результат неизвестен. "
                    + "Откройте «Детали» счёта, проверьте поступление в банке и состояние напоминаний; "
                    + "повторную отправку делайте только после этой проверки.";
        }
        return "Счет завис в статусе «" + commonInvoiceStatusLabel(status) + "» " + ageDays
                + " дн. Нажмите «Починить»: система проверит текущий шаг и выполнит безопасное продолжение.";
    }

    private String commonInvoiceLastErrorReason(
            CommonInvoice invoice,
            String rawError,
            List<CommonInvoiceOrder> items
    ) {
        String error = safe(rawError).toLowerCase(Locale.ROOT);
        var heldTooLong = autoresponderWait(invoice);
        if (heldTooLong.isPresent()) {
            return "Напоминание по счёту ещё не отправлялось: больше " + (AUTORESPONDER_WAIT_LIMIT_HOURS / 24)
                    + " суток оно ждёт в очереди автоответчика (" + autoresponderHoldLabel(heldTooLong.get().reasonCode())
                    + "). Проверьте паузу, рабочие окна и дневной лимит автоответчика. Повторно вручную не отправляйте: "
                    + "сообщение уйдёт само, как только автоответчик его пропустит.";
        }
        if (error.startsWith("message_send_stale") || error.startsWith("message_send_in_progress")
                || error.startsWith("message_send_unknown")) {
            return "Результат отправки сообщения по счету не подтвержден. Нажмите «Проверить отправку»: система "
                    + "сверит существующую отправку с квитанцией и историей нужного чата. Новое сообщение для проверки не отправляется. "
                    + "Замечание закрывается после подтверждения "
                    + "результата существующей отправки и устранения причины замечания по счету.";
        }
        if (error.contains("текст") && error.contains("уже опубликован ранее")) {
            // last_error is evidence of an earlier attempt, not a validation of today's review text.
            List<CommonInvoiceOrder> current = (items == null ? List.<CommonInvoiceOrder>of() : items).stream()
                    .filter(Objects::nonNull).filter(CommonInvoiceOrder::isActiveMembership).toList();
            List<CommonInvoiceOrder> unready = current.stream().filter(item -> !item.isReady()).toList();
            var reviewId = HISTORICAL_REVIEW_ID.matcher(rawError);
            String source = reviewId.find() ? " (отзыв #" + reviewId.group(1) + ")" : "";
            String progress = current.isEmpty() ? " Готовность текущих позиций нужно сверить."
                    : " Сейчас готовы " + (current.size() - unready.size()) + " из " + current.size() + " позиций.";
            String waiting = unready.stream().limit(3).map(item -> {
                Order order = item.getOrder();
                String status = orderStatusTitle(order);
                return "#" + (order == null || order.getId() == null ? "?" : order.getId())
                        + " «" + (status.isBlank() ? "без статуса" : status) + "»";
            }).collect(Collectors.joining(", "));
            if (!waiting.isBlank()) progress += " Не готовы: " + waiting
                    + (unready.size() > 3 ? " и ещё " + (unready.size() - 3) : "") + ".";
            return limit("При предыдущей попытке одобрения сохранена ошибка повторного текста" + source
                    + ". Она не подтверждает конфликт в текущем тексте." + progress
                    + " Сверьте сохраненный текст и согласование клиента в исходных заказах. "
                    + "Исправление текста само по себе не означает одобрение или готовность к счету.", 500);
        }
        if (error.startsWith("manual_fix:") && error.contains("moved_to_invoice_")) {
            String targetInvoice = valueAfter(error, "moved_to_invoice_");
            return "Заказ уже перенесен в другой общий счет"
                    + (targetInvoice.isBlank() ? "" : " #" + targetInvoice)
                    + ". Это технический хвост старого счета. Рекомендация: нажмите «Починить», чтобы скрыть старую карточку из контроля.";
        }
        if (error.startsWith("merged_into:")) {
            String targetInvoice = valueAfter(error, "common_invoice_");
            return "Этот общий счет объединен с другим счетом"
                    + (targetInvoice.isBlank() ? "" : " #" + targetInvoice)
                    + ". Рекомендация: нажмите «Починить», чтобы скрыть старую карточку из контроля.";
        }
        if (error.startsWith("empty:")) {
            return "В общем счете больше нет заказов. Рекомендация: нажмите «Починить», чтобы убрать пустой счет из контроля.";
        }
        if (error.startsWith("disabled:")) {
            return "Общий счет отключен. Рекомендация: нажмите «Починить», если в нем не осталось неоплаченных позиций.";
        }
        if (error.startsWith("whatsapp_group_missing") || error.contains("whatsapp-групп")) {
            return commonInvoiceWhatsappGroupMissingReason(invoice, false);
        }
        if (error.startsWith("auto_send_disabled")) {
            return "Автоматическая отправка клиентских сообщений выключена. Рекомендация: включите моментальные сообщения или обработайте счет вручную.";
        }
        if (commonInvoiceMessageSendRepairable(invoice)) {
            return "Сервис сообщений не подтвердил отправку общего счета. Откройте «Счет», проверьте привязку чата "
                    + "и результат существующей операции. До подтверждения отсутствия доставки не отправляйте повторно; "
                    + "если результат недоступен, передайте проверку администратору.";
        }
        if (commonInvoiceExpiredLinkAttention(invoice)) {
            return "Старая ссылка T-Bank истекла, и банк больше часа не подтверждает, что оплаты по ней нет. "
                    + "Нажмите «Починить»: система спросит банк ещё раз и, если оплаты не было, вернёт счёт в работу. "
                    + "Если клиент уже заплатил напрямую, отметьте оплату кнопкой «Оплачен» после возврата счёта в работу.";
        }
        if (error.startsWith("payment_init")) {
            if (commonInvoiceUnsentTlsInitRepairable(invoice)) {
                if (commonInvoiceHasCompetingStandalonePayment(items)) {
                    return "Создание платежной ссылки остановилось на проверке сертификата, но у одного из заказов "
                            + "есть отдельный незавершенный платеж. Откройте «Счет» и сначала сверьте этот платеж вручную.";
                }
                return "Создание платежной ссылки остановилось на проверке сертификата до отправки запроса в T-Bank. "
                        + "Сертификат уже доступен текущему backend. Рекомендация: нажмите «Починить», "
                        + "чтобы безопасно удалить незавершенную попытку и повторно отправить счет.";
            }
            return "Проблема при создании платежной ссылки T-Bank. Рекомендация: откройте «Счет» и сверьте состояние платежа в банке.";
        }
        if (error.startsWith("standalone_payment_route_conflict")) {
            return "У заказа внутри общего счета осталась отдельная платежная ссылка. Нажмите «Починить»: "
                    + "система сверит начатые платежи, зачтет подтвержденные оплаты и закроет только ссылки без "
                    + "банковских или ручных признаков оплаты. При неоднозначном состоянии автоматическая починка остановится.";
        }
        if (error.startsWith("close_failed")) {
            return "Оплата получена, но часть заказов не закрылась. Рекомендация: исправьте заказы и повторите действие в карточке счета.";
        }
        if (error.startsWith("next_order_failed")) {
            return "Платеж закрыт, но следующие заказы не создались. Рекомендация: нажмите «Починить», чтобы повторить создание следующих заказов.";
        }
        if (error.startsWith("review_approval_failed:")) {
            String problem = businessErrorDetail(errorField(rawError, "problem"));
            String solution = businessErrorDetail(errorField(rawError, "solution"));
            return "Массовое одобрение остановлено без частичных изменений. Проблема: "
                    + (problem.isBlank() ? "не удалось назначить даты публикации" : problem)
                    + ". Решение: "
                    + (solution.isBlank() ? "проверьте заказы общего счета" : solution)
                    + ". После исправления нажмите «Починить», чтобы безопасно повторить одобрение.";
        }
        if (error.startsWith("payable_change_requires_supplement")) {
            if (commonInvoiceArchivedCycleRevived(invoice)) {
                return "Общий счёт был закрыт в архив, а его заказы позже вернули в работу без восстановления счёта; "
                        + "после правки отзывов счёт ушёл на ручную проверку. Оплат, отправок и платёжных ссылок по нему нет. "
                        + "Нажмите «Починить», чтобы вернуть счёт в сбор: заказы и сообщения клиенту не изменятся.";
            }
            return "Сумма заказа изменилась после того, как счёт уже был зафиксирован (отправлен, оплачивается или закрыт). "
                    + "Нужна сверка с администратором: доплата отдельным счётом или исправление состава. "
                    + "Автоматически счёт не меняется.";
        }
        if (commonInvoiceTechnicalTailRepairable(invoice)) {
            return "У общего счета остался технический хвост. Рекомендация: нажмите «Починить», чтобы скрыть старую карточку из контроля.";
        }
        return "Обработка общего счета остановилась; точная причина требует проверки. Откройте «Счет», "
                + "сверьте состояние его позиций и последней операции с администратором. Не повторяйте финансовое "
                + "действие или отправку, пока не проверен результат предыдущей попытки.";
    }

    private String commonInvoicePaymentNotificationReason(CommonInvoice invoice, String rawError) {
        String error = safe(rawError).toLowerCase(Locale.ROOT);
        if (error.startsWith("immediate_messages_disabled")) {
            return "Уведомление об оплате не отправлено: моментальные клиентские сообщения выключены. Рекомендация: включите отправку или нажмите «Починить», чтобы закрыть эту ошибку.";
        }
        if (error.startsWith("whatsapp_group_missing") || error.contains("groupid")) {
            return commonInvoiceWhatsappGroupMissingReason(invoice, true);
        }
        return "Результат уведомления об оплате не подтвержден. Проверьте существующее сообщение в чате и запись "
                + "о доставке в «Счете»; если результат недоступен, передайте проверку администратору. "
                + "Не отправляйте повторно до сверки; сама ошибка уведомления не означает отсутствие оплаты.";
    }

    private String commonInvoiceWhatsappGroupMissingReason(CommonInvoice invoice, boolean paymentNotification) {
        CommonInvoiceChatBinding binding = commonInvoiceChatBinding(invoice);
        Company primaryCompany = binding.primaryCompany();
        Company linkedCompanyWithGroup = binding.linkedCompanyWithGroup();
        String primaryName = safe(primaryCompany == null ? null : primaryCompany.getTitle());
        String prefix = paymentNotification
                ? "Уведомление об оплате не отправлено"
                : "Сообщение общего счета не отправлено";

        if (hasText(primaryCompany == null ? null : primaryCompany.getGroupId())) {
            return prefix + ": у компании"
                    + (primaryName.isBlank() ? "" : " «" + primaryName + "»")
                    + " сейчас уже привязана WhatsApp-группа, но в общем счете осталась старая ошибка связи. "
                    + "Рекомендация: повторите отправку из «Счета» или нажмите «Починить», если сообщение уже отправлено вручную или больше не нужно.";
        }

        if (linkedCompanyWithGroup != null) {
            String linkedName = safe(linkedCompanyWithGroup.getTitle());
            return prefix + ": у главной компании общего счета"
                    + (primaryName.isBlank() ? "" : " «" + primaryName + "»")
                    + " не привязана WhatsApp-группа. У связанной компании"
                    + (linkedName.isBlank() ? "" : " «" + linkedName + "»")
                    + " группа привязана, но общий счет отправляется через главную компанию. "
                    + "Рекомендация: привяжите WhatsApp-группу главной компании к боту или смените главную компанию счета"
                    + (paymentNotification ? ", либо нажмите «Починить», если уведомление уже не нужно." : ".");
        }

        return prefix + ": WhatsApp-группа главной компании общего счета не привязана к боту. "
                + "Рекомендация: откройте «Счет», затем заказ/компанию и привяжите WhatsApp-группу к боту"
                + (paymentNotification ? ", либо нажмите «Починить», если уведомление уже не нужно." : ".");
    }

    private CommonInvoiceChatBinding commonInvoiceChatBinding(CommonInvoice invoice) {
        List<CommonInvoiceOrder> items = invoice == null || invoice.getId() == null
                ? List.of()
                : commonInvoiceOrderRepository.findByInvoiceIdWithOrders(invoice.getId());
        Company primaryCompany = commonInvoicePrimaryChatCompany(invoice, items);
        Long primaryCompanyId = primaryCompany == null ? null : primaryCompany.getId();
        Company linkedCompanyWithGroup = items.stream()
                .map(CommonInvoiceOrder::getOrder)
                .filter(Objects::nonNull)
                .map(Order::getCompany)
                .filter(Objects::nonNull)
                .filter(company -> company.getId() != null && !Objects.equals(company.getId(), primaryCompanyId))
                .filter(company -> hasText(company.getGroupId()))
                .findFirst()
                .orElse(null);
        return new CommonInvoiceChatBinding(primaryCompany, linkedCompanyWithGroup);
    }

    private Company commonInvoicePrimaryChatCompany(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        if (invoice == null) {
            return null;
        }
        if (invoice.getAccount() != null && invoice.getAccount().getInvoiceCompany() != null) {
            return invoice.getAccount().getInvoiceCompany();
        }
        return (items == null ? List.<CommonInvoiceOrder>of() : items).stream()
                .map(CommonInvoiceOrder::getOrder)
                .filter(Objects::nonNull)
                .map(Order::getCompany)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    boolean commonInvoiceArchivedCycleRevived(CommonInvoice invoice) {
        return archivedInvoiceRevival != null && invoice != null && invoice.getId() != null
                && invoice.getStatus() == CommonInvoiceStatus.NEEDS_ATTENTION
                && archivedInvoiceRevival.revivable(invoice.getId());
    }

    boolean commonInvoiceTechnicalTailRepairable(CommonInvoice invoice) {
        String error = safe(invoice == null ? null : invoice.getLastError()).toLowerCase(Locale.ROOT);
        return invoice != null
                && invoice.getStatus() == CommonInvoiceStatus.DISABLED
                && (error.startsWith("disabled:")
                || error.startsWith("empty:")
                || error.startsWith("merged_into:")
                || error.startsWith("manual_fix:"));
    }

    boolean commonInvoiceRequiresCompositionReview(CommonInvoice invoice, List<CommonInvoiceOrder> items) {
        return invoice != null
                && invoice.getStatus() == CommonInvoiceStatus.PARTIALLY_PAID
                && !archivedUnpaidPositions(items).isEmpty();
    }

    private List<CommonInvoiceOrder> archivedUnpaidPositions(List<CommonInvoiceOrder> items) {
        return (items == null ? List.<CommonInvoiceOrder>of() : items).stream()
                .filter(Objects::nonNull)
                .filter(CommonInvoiceOrder::isActiveMembership)
                .filter(item -> !item.isPaid())
                .filter(item -> "Архив".equals(orderStatusTitle(item.getOrder())))
                .toList();
    }

    boolean commonInvoiceWhatsappGroupTailRepairable(CommonInvoice invoice) {
        String error = safe(invoice == null ? null : invoice.getLastError()).toLowerCase(Locale.ROOT);
        if (invoice == null || !(error.startsWith("whatsapp_group_missing") || error.contains("whatsapp-групп"))) {
            return false;
        }
        CommonInvoiceChatBinding binding = commonInvoiceChatBinding(invoice);
        return hasText(binding.primaryCompany() == null
                ? null
                : binding.primaryCompany().getGroupId());
    }

    boolean commonInvoiceMessageSendRepairable(CommonInvoice invoice) {
        String error = safe(invoice == null ? null : invoice.getLastError()).toLowerCase(Locale.ROOT);
        if (invoice == null || error.isBlank()) {
            return false;
        }
        return error.startsWith("telegram_not_sent")
                || error.startsWith("telegram_exception")
                || error.startsWith("telegram_group_missing")
                || error.startsWith("max_not_sent")
                || error.startsWith("max_exception")
                || error.startsWith("max_group_missing");
    }

    boolean commonInvoiceUnsentTlsInitRepairable(CommonInvoice invoice) {
        return invoice != null
                && invoice.getStatus() == CommonInvoiceStatus.NEEDS_ATTENTION
                && safe(invoice.getTbankOrderId()).isBlank()
                && safe(invoice.getTbankPaymentId()).isBlank()
                && safe(invoice.getTbankTerminalKey()).isBlank()
                && invoice.getTbankPaymentAmountKopecks() == null
                && invoice.getTbankPaymentCreatedAt() == null
                && safe(invoice.getPaymentUrl()).isBlank()
                && CommonPaymentInitFailureClassifier.isPersistedTlsBeforeHttpFailure(invoice.getLastError());
    }

    private boolean commonInvoiceHasCompetingStandalonePayment(List<CommonInvoiceOrder> items) {
        List<Long> orderIds = (items == null ? List.<CommonInvoiceOrder>of() : items).stream()
                .map(CommonInvoiceOrder::getOrder)
                .filter(Objects::nonNull)
                .map(Order::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return !orderIds.isEmpty()
                && paymentLinkRepository.findByOrderIdInForRead(orderIds).stream()
                .anyMatch(StandaloneBankPaymentPolicy::blocksCommonInvoiceTlsRecovery);
    }

    boolean commonInvoiceStandaloneRouteRepairable(CommonInvoice invoice) {
        return invoice != null
                && invoice.getStatus() == CommonInvoiceStatus.NEEDS_ATTENTION
                && safe(invoice.getLastError()).toLowerCase(Locale.ROOT)
                .startsWith("standalone_payment_route_conflict");
    }

    boolean commonInvoicePaymentNotificationRepairable(CommonInvoice invoice) {
        return !safe(invoice == null ? null : invoice.getPaymentSuccessNotificationError()).isBlank();
    }

    boolean commonInvoiceNextOrderRepairable(CommonInvoice invoice) {
        String error = safe(invoice == null ? null : invoice.getLastError()).toLowerCase(Locale.ROOT);
        return invoice != null
                && invoice.getStatus() == CommonInvoiceStatus.NEEDS_ATTENTION
                && error.startsWith("next_order_failed");
    }

    boolean commonInvoiceReviewApprovalRepairable(CommonInvoice invoice) {
        return invoice != null
                && invoice.getStatus() == CommonInvoiceStatus.NEEDS_ATTENTION
                && safe(invoice.getLastError()).toLowerCase(Locale.ROOT).startsWith("review_approval_failed:");
    }

    private String errorField(String rawError, String field) {
        String source = safe(rawError);
        String marker = field + "=";
        int start = source.toLowerCase(Locale.ROOT).indexOf(marker.toLowerCase(Locale.ROOT));
        if (start < 0) {
            return "";
        }
        start += marker.length();
        int end = source.indexOf(';', start);
        return safe(end < 0 ? source.substring(start) : source.substring(start, end));
    }

    /** Keep known business explanations, never expose a provider/SQL exception as manager instructions. */
    private String businessErrorDetail(String value) {
        String text = safe(value);
        if (!text.matches("(?s).*[А-Яа-яЁё].*") || text.matches("(?is).*(?:\\b(?:http|jdbc|sql|select|insert|update|delete|conflict)\\b"
                + "|[a-z][a-z0-9]*_[a-z0-9_]+|[a-z0-9_$]+\\.[a-z0-9_$.]+|exception|https?://|[{}]).*")) return "";
        return limit(text, 180);
    }

    private String valueAfter(String value, String marker) {
        int index = safe(value).indexOf(marker);
        if (index < 0) {
            return "";
        }
        String suffix = value.substring(index + marker.length()).trim();
        int end = 0;
        while (end < suffix.length() && Character.isDigit(suffix.charAt(end))) {
            end++;
        }
        return end == 0 ? "" : suffix.substring(0, end);
    }

    String commonInvoiceStatusLabel(CommonInvoiceStatus status) {
        if (status == null) {
            return "Без статуса";
        }
        return switch (status) {
            case COLLECTING -> "Сбор";
            case READY -> "Готов к счету";
            case INVOICED -> "Выставлен счет";
            case REMINDER -> "Напоминание";
            case PARTIALLY_PAID -> "Частично оплачен";
            case NEEDS_ATTENTION -> "Требует внимания";
            case PAID -> "Оплачен";
            case UNPAID -> "Не оплачен";
            case BAN -> "Бан";
            case ARCHIVED -> "Архив";
            case DISABLED -> "Отключен";
        };
    }

    private String rubles(long kopecks) {
        return (kopecks / 100) + " руб.";
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private boolean hasText(String value) {
        return !safe(value).isBlank();
    }

    private long daysSince(LocalDate date, LocalDate today) {
        return date == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(date, today));
    }

    private String orderStatusTitle(Order order) {
        return order == null || order.getStatus() == null ? "" : safe(order.getStatus().getTitle());
    }

    private String userDisplayName(User user) {
        String fio = safe(user == null ? null : user.getFio());
        if (!fio.isBlank()) {
            return fio;
        }
        String username = safe(user == null ? null : user.getUsername());
        return username.isBlank() ? "Специалист #" + (user == null ? "-" : user.getId()) : username;
    }

    private record CommonInvoiceChatBinding(Company primaryCompany, Company linkedCompanyWithGroup) {
    }
}
