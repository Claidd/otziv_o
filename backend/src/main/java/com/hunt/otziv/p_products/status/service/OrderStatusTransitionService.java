package com.hunt.otziv.p_products.status.service;

import com.hunt.otziv.p_products.status.event.OrderStatusChangedEvent;
import com.hunt.otziv.p_products.status.policy.OrderManualArchivePolicy;
import com.hunt.otziv.bad_reviews.service.BadReviewTaskService;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.client_messages.service.PaymentInvoiceRetryScheduler;
import com.hunt.otziv.common_billing.service.CommonBillingService;
import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.contractor_payments.service.ContractorCompletionRewardService;
import com.hunt.otziv.contractor_payments.service.ContractorRouteAssignmentGuard;
import com.hunt.otziv.contractor_payments.service.ContractorPaymentShadowService;
import com.hunt.otziv.mobile_push.service.MobilePushBusinessNotificationService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.review.service.OrderAggregateMutationLockService;
import com.hunt.otziv.p_products.service.OrderStatusService;
import com.hunt.otziv.p_products.service.OrderTransactionService;
import com.hunt.otziv.performers.service.PerformerPublicationRequestedEvent;
import com.hunt.otziv.payments.service.ManualPaymentAutoConfirmationService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.model.ReviewArchiveSourceReason;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.r_review.service.ReviewArchiveService;
import com.hunt.otziv.review_recovery.service.ReviewRecoveryGateService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import jakarta.ws.rs.NotFoundException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static com.hunt.otziv.p_products.utils.OrderReviewGraph.getAllReviews;
import static com.hunt.otziv.p_products.utils.OrderReviewGraph.getFirstDetail;
import static com.hunt.otziv.p_products.utils.OrderReviewGraph.safeStatusTitle;
import static com.hunt.otziv.p_products.utils.OrderReviewGraph.safeString;
import static com.hunt.otziv.r_review.utils.ReviewTextPolicy.isBlankOrPlaceholder;
import static com.hunt.otziv.r_review.utils.ReviewTextPolicy.isShortCommonReviewText;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderStatusTransitionService {

    private static final String STATUS_NEW = "Новый";
    private static final String STATUS_TO_CHECK = "В проверку";
    private static final String STATUS_IN_CHECK = "На проверке";
    private static final String STATUS_CORRECTION = "Коррекция";
    private static final String STATUS_TO_PUBLISH = "Публикация";
    private static final String STATUS_PAYMENT = "Оплачено";
    private static final String STATUS_PUBLIC = "Опубликовано";
    private static final String STATUS_TO_PAY = "Выставлен счет";
    private static final String STATUS_NOT_PAID = "Не оплачено";
    private static final String STATUS_ARCHIVE = "Архив";
    private static final String STATUS_BAN = "Бан";
    private static final String STATUS_REMINDER = "Напоминание";
    private static final String STATUS_WAITING_COMMON_INVOICE = "Ожидает общего счета";
    private static final Set<String> SUPPORTED_TARGET_STATUSES = Set.of(
            STATUS_NEW,
            STATUS_TO_CHECK,
            STATUS_IN_CHECK,
            STATUS_CORRECTION,
            STATUS_TO_PUBLISH,
            STATUS_PUBLIC,
            STATUS_TO_PAY,
            STATUS_REMINDER,
            STATUS_NOT_PAID,
            STATUS_PAYMENT,
            STATUS_ARCHIVE,
            STATUS_BAN
    );
    private static final Map<String, Set<String>> ALLOWED_SOURCE_STATUSES = Map.ofEntries(
            Map.entry(STATUS_NEW, Set.of(STATUS_ARCHIVE)),
            Map.entry(STATUS_TO_CHECK, Set.of(STATUS_NEW, STATUS_CORRECTION, STATUS_IN_CHECK, STATUS_ARCHIVE)),
            Map.entry(STATUS_IN_CHECK, Set.of(STATUS_TO_CHECK, STATUS_CORRECTION, STATUS_ARCHIVE)),
            Map.entry(STATUS_CORRECTION, Set.of(STATUS_TO_CHECK, STATUS_IN_CHECK, STATUS_TO_PUBLISH, STATUS_PUBLIC, STATUS_ARCHIVE)),
            Map.entry(STATUS_TO_PUBLISH, Set.of(STATUS_IN_CHECK, STATUS_CORRECTION, STATUS_ARCHIVE)),
            Map.entry(STATUS_PUBLIC, Set.of(STATUS_TO_PUBLISH)),
            Map.entry(STATUS_TO_PAY, Set.of(STATUS_PUBLIC, STATUS_REMINDER, STATUS_NOT_PAID, STATUS_WAITING_COMMON_INVOICE)),
            Map.entry(STATUS_REMINDER, Set.of(STATUS_TO_PAY)),
            Map.entry(STATUS_NOT_PAID, Set.of(STATUS_TO_PUBLISH, STATUS_PUBLIC, STATUS_TO_PAY, STATUS_REMINDER, STATUS_WAITING_COMMON_INVOICE, STATUS_PAYMENT, STATUS_BAN)),
            Map.entry(STATUS_PAYMENT, Set.of(STATUS_PUBLIC, STATUS_TO_PAY, STATUS_REMINDER, STATUS_NOT_PAID, STATUS_BAN, STATUS_WAITING_COMMON_INVOICE)),
            Map.entry(STATUS_ARCHIVE, Set.of(STATUS_TO_CHECK, STATUS_IN_CHECK, STATUS_CORRECTION, STATUS_TO_PUBLISH)),
            Map.entry(STATUS_BAN, Set.of(STATUS_NOT_PAID, STATUS_TO_PAY, STATUS_REMINDER))
    );
    private static final Set<String> COMPLETED_ORDER_REOPEN_STATUSES = Set.of(
            "Новый",
            STATUS_TO_CHECK,
            STATUS_IN_CHECK,
            STATUS_CORRECTION,
            STATUS_TO_PUBLISH,
            STATUS_PUBLIC
    );
    private static final Set<String> COMMON_BILLING_FINANCIAL_STATUSES = Set.of(
            STATUS_PAYMENT,
            STATUS_TO_PAY,
            STATUS_REMINDER,
            STATUS_NOT_PAID,
            STATUS_BAN,
            STATUS_WAITING_COMMON_INVOICE
    );

    private final OrderRepository orderRepository;
    private final OrderAggregateMutationLockService orderAggregateMutationLockService;
    private final OrderStatusService orderStatusService;
    private final OrderTransactionService orderTransactionService;
    private final BadReviewTaskService badReviewTaskService;
    private final TelegramService telegramService;
    private final OrderCompanyStatusService orderCompanyStatusService;
    private final OrderStatusNotificationService orderStatusNotificationService;
    private final OrderBotLifecycleService orderBotLifecycleService;
    private final ReviewArchiveService reviewArchiveService;
    private final ReviewRepository reviewRepository;
    private final OrderPaymentMessageBuilder orderPaymentMessageBuilder;
    private final OrderReviewCheckMessageBuilder orderReviewCheckMessageBuilder;
    private final MobilePushBusinessNotificationService mobilePushBusinessNotificationService;
    private final OrderCorrectionTelegramNotifier orderCorrectionTelegramNotifier;
    private final ManualPaymentAutoConfirmationService manualPaymentAutoConfirmationService;
    private final PaymentInvoiceRetryScheduler paymentInvoiceRetryScheduler;
    private final AppSettingService appSettingService;
    private final BusinessAuditService businessAuditService;
    private final ContractorPaymentShadowService contractorPaymentShadowService;
    private final ContractorCompletionRewardService contractorCompletionRewardService;
    private final ContractorRouteAssignmentGuard contractorRouteAssignmentGuard;
    private final ObjectProvider<CommonBillingService> commonBillingServiceProvider;
    private final ReviewRecoveryGateService recoveryGateService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public boolean changeStatusForOrder(Long orderID, String title) throws Exception {
        return changeStatusForOrderInternal(orderID, title, false, false, false);
    }

    @Transactional
    public boolean changeStatusForRestoredArchiveOrder(Long orderID, String title) throws Exception {
        return changeStatusForOrderInternal(orderID, title, false, false, true);
    }

    @Transactional
    public boolean changeStatusForPrivilegedOrder(Long orderID, String title) throws Exception {
        return changeStatusForOrderInternal(orderID, title, false, true, false);
    }

    @Transactional
    public boolean changeStatusForCommonBillingOrder(Long orderID, String title) throws Exception {
        return changeStatusForOrderInternal(orderID, title, true, false, false);
    }

    @Transactional
    public boolean changeStatusForPrivilegedCommonBillingOrder(Long orderID, String title) throws Exception {
        return changeStatusForOrderInternal(orderID, title, true, true, false);
    }

    private boolean changeStatusForOrderInternal(
            Long orderID,
            String title,
            boolean allowCommonBillingFinancialStatus,
            boolean allowBanWithPendingBadTasks,
            boolean restoredArchiveOrigin
    ) throws Exception {
        try {
            orderAggregateMutationLockService.lock(orderID);
            Order order = orderRepository.findByIdForMutation(orderID)
                    .orElseThrow(() -> new NotFoundException("Order not found for orderID: " + orderID));

            ensureSupportedTargetStatus(title);
            String oldStatus = safeStatusTitle(order);
            if (safeString(oldStatus).equals(safeString(title))) {
                recordStatusAudit(order, oldStatus, oldStatus, title, false);
                return true;
            }
            ensureCommonBillingStatusTransitionAllowed(order, title, allowCommonBillingFinancialStatus);
            ensureCompletedOrderNotReopened(order, title);
            ensureStatusTransitionAllowed(order, title);
            if (STATUS_ARCHIVE.equals(title) && !OrderManualArchivePolicy.isAllowed(order)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "В архив можно перевести заказ только из статусов \"В проверку\", \"На проверке\", \"Коррекция\" или \"Публикация\""
                );
            }
            synchronizeAndRequireCompleteCounter(order, title);
            boolean changed = switch (title) {
                case STATUS_PAYMENT -> handlePaymentStatus(order);
                case STATUS_ARCHIVE -> handleArchiveStatus(order);
                case STATUS_TO_CHECK -> handleToCheckStatus(order);
                case STATUS_IN_CHECK -> handleManualInCheckStatus(order);
                case STATUS_CORRECTION -> handleCorrectionStatus(order);
                case STATUS_PUBLIC -> handlePublicStatus(order);
                case STATUS_TO_PUBLISH -> handleToPublicStatus(order, restoredArchiveOrigin);
                case STATUS_TO_PAY -> handleManualToPayStatus(order);
                case STATUS_NOT_PAID -> handleNotPaidStatus(order);
                case STATUS_BAN -> handleBanStatus(order, allowBanWithPendingBadTasks);
                case STATUS_NEW -> handleSimpleStatus(order, STATUS_NEW);
                case STATUS_REMINDER -> handleSimpleStatus(order, STATUS_REMINDER);
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Недопустимый статус заказа");
            };
            recordStatusAudit(order, oldStatus, safeStatusTitle(order), title, changed);
            return changed;

        } catch (ResponseStatusException e) {
            log.warn("Смена статуса заказа отклонена: {}", e.getReason());
            throw e;
        } catch (Exception e) {
            log.error("При смене статуса произошли какие-то проблемы", e);
            throw e;
        }
    }

    private void ensureSupportedTargetStatus(String title) {
        if (title == null || !SUPPORTED_TARGET_STATUSES.contains(title)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Недопустимый статус заказа");
        }
    }

    private void ensureStatusTransitionAllowed(Order order, String targetStatus) {
        String currentStatus = safeStatusTitle(order);
        if (safeString(currentStatus).equals(safeString(targetStatus))) {
            return;
        }
        Set<String> allowedSources = ALLOWED_SOURCE_STATUSES.get(targetStatus);
        if (allowedSources == null || !allowedSources.contains(currentStatus)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Недопустимый переход статуса заказа: \"" + currentStatus + "\" → \"" + targetStatus + "\""
            );
        }
    }

    private void synchronizeAndRequireCompleteCounter(Order order, String targetStatus) {
        if (!STATUS_PUBLIC.equals(targetStatus) && !STATUS_PAYMENT.equals(targetStatus)) {
            return;
        }
        int actualPublished = reviewRepository.countPublishedByOrderId(order.getId());
        order.setCounter(actualPublished);
        if (order.getAmount() > actualPublished) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Нельзя перевести заказ в статус \"" + targetStatus + "\": опубликовано "
                            + actualPublished + " из " + order.getAmount() + " отзывов"
            );
        }
    }

    private boolean handleSimpleStatus(Order order, String title) {
        order.setStatus(orderStatusService.getOrderStatusByTitle(title));
        orderRepository.save(order);
        return true;
    }

    private void ensureCommonBillingStatusTransitionAllowed(
            Order order,
            String title,
            boolean allowCommonBillingFinancialStatus
    ) {
        if (allowCommonBillingFinancialStatus) {
            return;
        }

        String currentStatus = safeStatusTitle(order);
        boolean targetsFinancialStatus = COMMON_BILLING_FINANCIAL_STATUSES.contains(title);
        boolean leavesFinancialStatus = COMMON_BILLING_FINANCIAL_STATUSES.contains(currentStatus)
                && !safeString(currentStatus).equals(safeString(title));
        boolean targetsArchive = STATUS_ARCHIVE.equals(title);
        if (!targetsFinancialStatus && !leavesFinancialStatus && !targetsArchive) {
            return;
        }

        CommonBillingService commonBillingService = commonBillingServiceProvider.getIfAvailable();
        if (commonBillingService == null || !commonBillingService.isOrderInActiveCommonInvoice(order.getId())) {
            return;
        }

        if (targetsFinancialStatus) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Финансовый статус заказа внутри общего счета меняется только через общий счет"
            );
        }

        if (leavesFinancialStatus) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Заказ уже включен в активный общий счет и не может быть возвращен в рабочий статус"
            );
        }

        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Заказ внутри общего счета архивируется только вместе с общим счетом"
        );
    }

    private void ensureCompletedOrderNotReopened(Order order, String targetStatus) {
        if (order == null
                || (!order.isComplete() && order.getPayDay() == null)
                || !COMPLETED_ORDER_REOPEN_STATUSES.contains(targetStatus)
                || safeString(targetStatus).equals(safeStatusTitle(order))) {
            return;
        }

        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Завершенный оплаченный заказ нельзя вернуть в рабочий статус. "
                        + "Сначала отмените оплату штатным действием."
        );
    }

    private void recordStatusAudit(Order order, String oldStatus, String newStatus, String requestedStatus, boolean changed) {
        if (!changed || safeString(oldStatus).equals(safeString(newStatus))) {
            return;
        }
        businessAuditService.recordSafely(
                "order_status_changed",
                "order",
                order.getId(),
                order.getId(),
                null,
                oldStatus,
                newStatus,
                "requestedStatus=" + requestedStatus
        );
        eventPublisher.publishEvent(new OrderStatusChangedEvent(
                order.getId(),
                oldStatus,
                newStatus,
                requestedStatus
        ));
    }

    private boolean handleNotPaidStatus(Order order) {
        order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_NOT_PAID));
        orderRepository.save(order);
        scheduleContractorReservationRelease(
                order.getId(),
                "Заказ переведен в статус \"Не оплачено\""
        );
        badReviewTaskService.createTasksForUnpaidOrder(order);
        return true;
    }

    private void scheduleContractorReservationRelease(Long orderId, String reason) {
        if (orderId == null) {
            return;
        }
        Runnable release = () -> {
            try {
                contractorPaymentShadowService.releaseForFinanciallyClosedOrder(
                        orderId,
                        reason
                );
            } catch (RuntimeException e) {
                log.error("Не удалось освободить резерв финансово закрытого заказа {}", orderId, e);
            }
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            release.run();
                        }
                    }
            );
        } else {
            release.run();
        }
    }

    private boolean handlePaymentStatus(Order order) throws Exception {
        manualPaymentAutoConfirmationService.ensureCanCloseOrderManually(order);
        boolean updated = orderTransactionService.handlePaymentStatus(order);
        if (updated) {
            manualPaymentAutoConfirmationService.confirmForPaidOrder(order);
            manualPaymentAutoConfirmationService.retireOpenLinksForPaidOrder(order);
            paymentInvoiceRetryScheduler.cancelBadReviewAutoBan(order, "Заказ оплачен");
        }
        return updated;
    }

    private boolean handleBanStatus(Order order, boolean allowPendingBadTasks) {
        var summary = badReviewTaskService.getSummaryForOrder(order.getId());
        boolean badReviewFinalInvoiceReady = summary != null && summary.pending() == 0 && summary.done() > 0;
        String currentStatus = safeStatusTitle(order);
        boolean regularBanAllowed = STATUS_NOT_PAID.equals(currentStatus);
        boolean finalBadReviewInvoiceBanAllowed = badReviewFinalInvoiceReady
                && (STATUS_TO_PAY.equals(currentStatus) || STATUS_REMINDER.equals(currentStatus));
        if (!regularBanAllowed && !finalBadReviewInvoiceBanAllowed) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Перевести заказ в Бан можно из статуса \"Не оплачено\" или после финального счета за плохие отзывы"
            );
        }
        if (!allowPendingBadTasks && summary != null && summary.pending() > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Сначала выполните все плохие задачи заказа");
        }

        if (allowPendingBadTasks && summary != null && summary.pending() > 0) {
            badReviewTaskService.deletePendingTasksForOrder(order);
        }
        order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_BAN));
        orderCompanyStatusService.autoManageCompanyStatus(order, STATUS_BAN);
        badReviewTaskService.deleteOrderReadyReminder(order);
        orderRepository.save(order);
        paymentInvoiceRetryScheduler.cancelBadReviewAutoBan(order, "Заказ переведен в Бан");
        scheduleContractorReservationRelease(
                order.getId(),
                "Заказ переведен в статус \"Бан\""
        );
        return true;
    }

    private boolean handleToPublicStatus(Order order, boolean restoredArchiveOrigin) {
        validateReviewsReadyForPublication(order);

        try {
            log.info("=== НАЧАЛО ПЕРЕВОДА ЗАКАЗА В СТАТУС 'К ПУБЛИКАЦИИ' ===");
            log.info("Заказ ID: {}, текущий статус: {}", order.getId(), safeStatusTitle(order));

            String previousOrderStatus = safeStatusTitle(order);

            orderBotLifecycleService.assignBotsIfNeeded(order, true);

            order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_TO_PUBLISH));
            orderCompanyStatusService.autoManageCompanyStatus(order, STATUS_TO_PUBLISH);

            List<Review> reviews = getAllReviews(order);
            if (reviews.isEmpty()) {
                log.warn("В заказе ID {} нет отзывов", order.getId());
            } else {
                orderBotLifecycleService.checkAndNotifyAboutStubBots(reviews, true);
            }

            orderRepository.save(order);

            log.info("=== УСПЕШНЫЙ ПЕРЕВОД ЗАКАЗА ===");
            if (STATUS_ARCHIVE.equals(previousOrderStatus) || restoredArchiveOrigin) {
                log.info("Заказ ID {} переведен в статус 'К публикации' ИЗ АРХИВА", order.getId());
                mobilePushBusinessNotificationService.notifyWorkerArchiveReadyForPublication(order);

                if (orderStatusNotificationService.hasWorkerWithTelegram(order)) {
                    String companyTitle = order.getCompany().getTitle();
                    telegramService.sendMessage(
                            order.getWorker().getUser().getWorkerTelegramGroupChatId(),
                            companyTitle + ". Новый заказ из Архива. " +
                                    "\n https://o-ogo.ru/worker?section=publish"
                    );
                }
            } else {
                log.info("Заказ ID {} переведен в статус 'К публикации'", order.getId());
            }

            notifyClientAboutPublicationStarted(order, previousOrderStatus);
            eventPublisher.publishEvent(new PerformerPublicationRequestedEvent(order.getId()));
            return true;

        } catch (ResponseStatusException e) {
            log.warn("Перевод заказа ID {} в публикацию отклонен: {}", order.getId(), e.getReason());
            throw e;
        } catch (Exception e) {
            log.error("=== ОШИБКА ПРИ ПЕРЕВОДЕ ЗАКАЗА В СТАТУС 'К ПУБЛИКАЦИИ' ===", e);
            throw new RuntimeException("Ошибка при переводе заказа в статус 'К публикации'", e);
        }
    }

    private void notifyClientAboutPublicationStarted(Order order, String previousOrderStatus) {
        if (!STATUS_IN_CHECK.equals(previousOrderStatus)) {
            log.info("Уведомление о передаче в публикацию пропущено: заказ ID {} перешел из статуса '{}'",
                    order.getId(), previousOrderStatus);
            return;
        }
        if (!immediateClientMessagesEnabled()) {
            log.info("Уведомление о передаче в публикацию пропущено: моментальные клиентские сообщения выключены, orderId={}",
                    order.getId());
            return;
        }

        try {
            String clientId = order.getManager() != null ? order.getManager().getClientId() : null;
            String groupId = order.getCompany() != null ? order.getCompany().getGroupId() : null;
            String message = orderReviewCheckMessageBuilder.publicationStartedMessage(order);

            boolean sent = orderStatusNotificationService.sendInformationalMessageToClientChat(
                    order,
                    clientId,
                    groupId,
                    message,
                    "заказ передан в публикацию"
            );
            if (sent) {
                log.info("Уведомление клиенту о передаче заказа ID {} в публикацию отправлено", order.getId());
            } else {
                log.warn("Уведомление клиенту о передаче заказа ID {} в публикацию не отправлено", order.getId());
            }
        } catch (Exception e) {
            log.warn("Уведомление клиенту о передаче заказа ID {} в публикацию не отправлено из-за ошибки. Статус уже изменен.",
                    order.getId(), e);
        }
    }

    private boolean handleArchiveStatus(Order order) {
        log.info("=== АРХИВАЦИЯ ЗАКАЗА ID: {} ===", order.getId());

        validateReviewsReadyForArchive(order);

        saveReviewsToArchive(order);

        clearPublicationDatesForUnpublishedReviews(order);

        order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_ARCHIVE));
        orderCompanyStatusService.autoManageCompanyStatus(order, STATUS_ARCHIVE);

        orderBotLifecycleService.detachBots(order);

        orderRepository.save(order);

        log.info("=== ЗАКАЗ ID {} УСПЕШНО АРХИВИРОВАН ===", order.getId());
        return true;
    }

    private void saveReviewsToArchive(Order order) {
        List<Review> reviews = getAllReviews(order);
        if (reviews.isEmpty()) {
            return;
        }

        for (Review review : reviews) {
            if (review.getId() != null) {
                reviewArchiveService.saveNewReviewArchive(review.getId(), ReviewArchiveSourceReason.ORDER_ARCHIVED);
            }
        }
    }

    private void validateReviewsReadyForArchive(Order order) {
        validateReviewsReadyForStatus(
                order,
                "Архивация",
                "Нельзя отправить заказ в архив: заполните текст всех отзывов"
        );
    }

    private void validateReviewsReadyForCheck(Order order) {
        validateReviewsReadyForStatus(
                order,
                "Отправка на проверку",
                "Нельзя отправить заказ на проверку: заполните текст всех отзывов"
        );
        validateReviewTextsNotDuplicatedWithinOrder(
                order,
                "Отправка на проверку",
                "Нельзя отправить заказ на проверку: в заказе есть одинаковые тексты отзывов. Измените повторяющийся текст и сохраните его дискеткой."
        );
        validateReviewTextsNotPreviouslyPublished(
                order,
                "Отправка на проверку",
                "Нельзя отправить заказ на проверку: текст отзыва уже опубликован ранее. Измените текст отзыва и сохраните его дискеткой."
        );
        validateReviewTextsNotArchived(
                order,
                "Отправка на проверку",
                "Нельзя отправить заказ на проверку: текст отзыва уже есть в архиве текстов. Он может быть зарезервирован или использован ранее. Измените текст отзыва и сохраните его дискеткой."
        );
    }

    private void validateReviewsReadyForPublication(Order order) {
        validateReviewsReadyForStatus(
                order,
                "Публикация",
                "Нельзя отправить заказ в публикацию: заполните текст всех отзывов"
        );
        validateReviewTextsNotDuplicatedWithinOrder(
                order,
                "Публикация",
                "Нельзя отправить заказ в публикацию: в заказе есть одинаковые тексты отзывов. Измените повторяющийся текст и сохраните его дискеткой."
        );
        validateReviewTextsNotPreviouslyPublished(
                order,
                "Публикация",
                "Нельзя отправить заказ в публикацию: текст отзыва уже опубликован ранее. Измените текст отзыва и сохраните его дискеткой."
        );
        validateReviewTextsNotArchived(
                order,
                "Публикация",
                "Нельзя отправить заказ в публикацию: текст отзыва уже есть в архиве текстов. Он может быть зарезервирован или использован ранее. Измените текст отзыва и сохраните его дискеткой."
        );
    }

    private void validateReviewsReadyForStatus(
            Order order,
            String logActionTitle,
            String errorMessage
    ) {
        List<Review> invalidReviews = getAllReviews(order).stream()
                .filter(this::hasInvalidReviewText)
                .toList();

        if (invalidReviews.isEmpty()) {
            return;
        }

        String reviewIds = invalidReviews.stream()
                .map(review -> review.getId() == null ? "без id" : review.getId().toString())
                .collect(Collectors.joining(", "));
        log.warn("{} заказа ID {} отменена: пустые или шаблонные тексты у отзывов {}",
                logActionTitle, order.getId(), reviewIds);

        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorMessage);
    }

    private boolean hasInvalidReviewText(Review review) {
        return review == null || isBlankOrPlaceholder(review.getText());
    }

    private void validateReviewTextsNotDuplicatedWithinOrder(
            Order order,
            String logActionTitle,
            String errorMessage
    ) {
        List<Review> duplicateReviews = getAllReviews(order).stream()
                .filter(review -> review != null && !review.isPublish())
                .filter(review -> !isBlankOrPlaceholder(review.getText()))
                .collect(Collectors.groupingBy(review -> normalizedReviewText(review.getText())))
                .values()
                .stream()
                .filter(reviews -> reviews.size() > 1)
                .flatMap(List::stream)
                .toList();

        rejectIfDuplicatedReviewsFound(order, logActionTitle, errorMessage, duplicateReviews, "одинаковые тексты");
    }

    private void validateReviewTextsNotPreviouslyPublished(
            Order order,
            String logActionTitle,
            String errorMessage
    ) {
        List<Review> duplicateReviews = getAllReviews(order).stream()
                .filter(review -> review != null && !review.isPublish())
                .filter(review -> !isBlankOrPlaceholder(review.getText()))
                .filter(this::isPublishedReviewText)
                .toList();

        rejectIfDuplicatedReviewsFound(order, logActionTitle, errorMessage, duplicateReviews, "ранее опубликованные тексты");
    }

    private void validateReviewTextsNotArchived(
            Order order,
            String logActionTitle,
            String errorMessage
    ) {
        List<Review> duplicateReviews = getAllReviews(order).stream()
                .filter(review -> review != null && !review.isPublish())
                .filter(review -> !isBlankOrPlaceholder(review.getText()))
                .filter(review -> isArchivedReviewText(order, review))
                .toList();

        rejectIfDuplicatedReviewsFound(order, logActionTitle, errorMessage, duplicateReviews, "тексты из архива");
    }

    private boolean isPublishedReviewText(Review review) {
        String text = review.getText();
        if (isShortCommonReviewText(text)) {
            return false;
        }
        return reviewRepository.existsPublishedByTextExcludingReviewId(text, review.getId());
    }

    private boolean isArchivedReviewText(Order order, Review review) {
        String text = review.getText();
        if (isShortCommonReviewText(text)) {
            return false;
        }
        Long orderId = order == null ? null : order.getId();
        return reviewArchiveService.existsByTextExcludingOwnSource(text, review.getId(), orderId);
    }

    private void rejectIfDuplicatedReviewsFound(
            Order order,
            String logActionTitle,
            String errorMessage,
            List<Review> duplicateReviews,
            String reason
    ) {
        if (duplicateReviews.isEmpty()) {
            return;
        }

        String reviewIds = duplicateReviews.stream()
                .map(review -> review.getId() == null ? "без id" : review.getId().toString())
                .collect(Collectors.joining(", "));
        String cardLabels = reviewCardLabels(order, duplicateReviews);
        log.warn("{} заказа ID {} отменена: {} у отзывов {}",
                logActionTitle, order.getId(), reason, reviewIds);

        throw new ResponseStatusException(HttpStatus.CONFLICT, errorMessage + " Проблемные карточки: " + cardLabels + ".");
    }

    private String normalizedReviewText(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    private String reviewCardLabels(Order order, List<Review> reviews) {
        List<Review> allReviews = getAllReviews(order);
        return reviews.stream()
                .map(review -> reviewCardLabel(allReviews, review))
                .collect(Collectors.joining(", "));
    }

    private String reviewCardLabel(List<Review> allReviews, Review target) {
        int index = reviewIndex(allReviews, target);
        String number = index >= 0 ? "№" + (index + 1) : "№?";
        String id = target != null && target.getId() != null ? " (отзыв #" + target.getId() + ")" : "";
        return number + id;
    }

    private int reviewIndex(List<Review> allReviews, Review target) {
        if (target == null) {
            return -1;
        }

        for (int i = 0; i < allReviews.size(); i++) {
            Review review = allReviews.get(i);
            if (review == target) {
                return i;
            }
            if (review != null && review.getId() != null && review.getId().equals(target.getId())) {
                return i;
            }
        }

        return -1;
    }

    private boolean handleToCheckStatus(Order order) {
        validateReviewsReadyForCheck(order);
        clearCurrentClientWaiting(order);

        try {
            log.info("=== НАЧАЛО ПЕРЕВОДА ЗАКАЗА В СТАТУС 'НА ПРОВЕРКУ' ===");
            log.info("Заказ ID: {}, текущий статус: {}", order.getId(), safeStatusTitle(order));

            orderBotLifecycleService.assignBotsIfNeeded(order);
            orderCompanyStatusService.autoManageCompanyStatus(order, STATUS_TO_CHECK);

            String clientId = order.getManager() != null ? order.getManager().getClientId() : null;
            String groupId = order.getCompany() != null ? order.getCompany().getGroupId() : null;

            OrderDetails firstDetail = getFirstDetail(order);
            if (firstDetail == null) {
                log.warn("У заказа {} нет OrderDetails. Статус выставим без ссылки на проверку", order.getId());
                order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_TO_CHECK));
                orderRepository.save(order);
                mobilePushBusinessNotificationService.notifyManagerOrderReadyForReview(order);
                return true;
            }

            String message = orderReviewCheckMessageBuilder.reviewCheckMessage(order);

            if (!immediateClientMessagesEnabled()) {
                log.info("Отправка проверки клиенту пропущена: моментальные клиентские сообщения выключены, orderId={}",
                        order.getId());
                order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_TO_CHECK));
                orderRepository.save(order);
                mobilePushBusinessNotificationService.notifyManagerOrderReadyForReview(order);
                return true;
            }

            log.info("Отправляем сообщение клиенту для заказа ID: {}", order.getId());
            String appliedStatus = orderStatusNotificationService.sendMessageToClientChat(
                    STATUS_TO_CHECK,
                    order,
                    clientId,
                    groupId,
                    message,
                    STATUS_IN_CHECK
            );

            if (STATUS_IN_CHECK.equals(appliedStatus)) {
                log.info("✅ Заказ ID {} переведен в статус 'На проверку' (сообщение отправлено)", order.getId());
                mobilePushBusinessNotificationService.notifyManagerOrderReadyForReview(order);
                return true;
            }

            if (STATUS_TO_CHECK.equals(appliedStatus)) {
                paymentInvoiceRetryScheduler.scheduleReviewCheckRetry(order);
                log.warn("⚠️ Заказ ID {} оставлен в статусе 'В проверку': клиентское сообщение не отправлено", order.getId());
                mobilePushBusinessNotificationService.notifyManagerOrderReadyForReview(order);
                return true;
            } else {
                log.error("❌ Неожиданный статус после отправки сообщения для заказа ID {}: {}", order.getId(), appliedStatus);
                return false;
            }

        } catch (Exception e) {
            log.error("=== ОШИБКА ПРИ ПЕРЕВОДЕ ЗАКАЗА В СТАТУС 'НА ПРОВЕРКУ' ===", e);
            try {
                order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_TO_CHECK));
                orderRepository.save(order);
                log.warn("Статус заказа ID {} изменен на 'В проверку' без дополнительных действий из-за ошибки",
                        order.getId());
            } catch (Exception ex) {
                log.error("Критическая ошибка при сохранении статуса: {}", ex.getMessage());
            }
            return false;
        }
    }

    private void clearCurrentClientWaiting(Order order) {
        if (order == null || !order.isWaitingForClient()) {
            return;
        }

        // Current waiting is finished because the received texts are being sent
        // for review. Keep the persistent preference so the next repeat order
        // starts by waiting for fresh client text again.
        order.setClientTextExpected(true);
        order.setWaitingForClient(false);
        order.setWaitingForClientChangedAt(null);
        log.info("Заказ ID {} больше не ждет текст клиента: отзывы переданы на проверку", order.getId());
    }

    private boolean handleManualInCheckStatus(Order order) {
        validateReviewsReadyForCheck(order);

        log.info("=== РУЧНОЙ ПЕРЕВОД ЗАКАЗА В СТАТУС 'НА ПРОВЕРКЕ' ===");
        log.info("Заказ ID: {}, текущий статус: {}", order.getId(), safeStatusTitle(order));

        order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_IN_CHECK));
        orderCompanyStatusService.autoManageCompanyStatus(order, STATUS_IN_CHECK);
        orderRepository.save(order);

        log.info("✅ Заказ ID {} вручную переведен в статус 'На проверке' без отправки сообщения клиенту", order.getId());
        return true;
    }

    private boolean handleCorrectionStatus(Order order) {
        contractorRouteAssignmentGuard.requirePayableMutationAllowed(order == null ? null : order.getId());
        try {
            log.info("=== НАЧАЛО ПЕРЕВОДА ЗАКАЗА В СТАТУС 'КОРРЕКЦИЯ' ===");
            String currentStatus = safeStatusTitle(order);
            log.info("Заказ ID: {}, текущий статус: {}", order.getId(), currentStatus);

            if (STATUS_CORRECTION.equals(currentStatus)) {
                log.info("Заказ ID {} уже находится в статусе 'Коррекция'. Повторный перевод пропущен", order.getId());
                return true;
            }

            orderBotLifecycleService.assignBotsIfNeeded(order);
            orderCompanyStatusService.autoManageCompanyStatus(order, STATUS_CORRECTION);

            clearPublicationDatesForUnpublishedReviews(order);

            order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_CORRECTION));
            orderRepository.save(order);
            String clientCorrectionNote = clientCorrectionNote(order);
            mobilePushBusinessNotificationService.notifyWorkerCorrection(order, clientCorrectionNote);
            enqueueCorrectionTelegramNotification(order);

            log.info("✅ Заказ ID {} переведен в статус 'Коррекция'", order.getId());
            return true;

        } catch (Exception e) {
            log.error("=== ОШИБКА ПРИ ПЕРЕВОДЕ ЗАКАЗА В СТАТУС 'КОРРЕКЦИЯ' ===", e);
            try {
                clearPublicationDatesForUnpublishedReviews(order);
                order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_CORRECTION));
                orderRepository.save(order);
                mobilePushBusinessNotificationService.notifyWorkerCorrection(order, clientCorrectionNote(order));
                log.warn("Статус заказа ID {} изменен на 'Коррекция' без дополнительных действий из-за ошибки",
                        order.getId());
            } catch (Exception ex) {
                log.error("Критическая ошибка при сохранении статуса: {}", ex.getMessage());
            }
            return false;
        }
    }

    private void enqueueCorrectionTelegramNotification(Order order) {
        try {
            if (!orderStatusNotificationService.hasWorkerWithTelegram(order)) {
                return;
            }

            Long chatId = order.getWorker().getUser().getWorkerTelegramGroupChatId();
            String companyTitle = order.getCompany() == null ? "" : safeString(order.getCompany().getTitle());
            orderCorrectionTelegramNotifier.notifyWorkerCorrection(
                    order.getId(),
                    chatId,
                    companyTitle,
                    clientCorrectionNote(order)
            );
            log.info("Уведомление о коррекции заказа ID {} поставлено в очередь Telegram", order.getId());
        } catch (RuntimeException e) {
            log.warn("Не удалось поставить Telegram-уведомление о коррекции заказа ID {} в очередь. Статус уже изменен.",
                    order.getId(), e);
        }
    }

    private String clientCorrectionNote(Order order) {
        LinkedHashSet<String> notes = new LinkedHashSet<>();
        if (order != null && order.getDetails() != null) {
            for (OrderDetails detail : order.getDetails()) {
                if (detail == null) {
                    continue;
                }
                addCorrectionNote(notes, detail.getComment());
                if (detail.getReviews() == null) {
                    continue;
                }
                for (Review review : detail.getReviews()) {
                    if (review == null) {
                        continue;
                    }
                    String answer = safeString(review.getAnswer());
                    if (!answer.isBlank()) {
                        addCorrectionNote(
                                notes,
                                review.getId() == null ? answer : "Отзыв #" + review.getId() + ": " + answer
                        );
                    }
                }
            }
        }
        return limitTelegramText(String.join("\n", notes), 3000);
    }

    private void addCorrectionNote(Set<String> notes, String value) {
        String normalized = safeString(value);
        if (!normalized.isBlank()) {
            notes.add(normalized);
        }
    }

    private String limitTelegramText(String value, int maxLength) {
        String normalized = safeString(value);
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength - 1).trim() + "…";
    }

    private void clearPublicationDatesForUnpublishedReviews(Order order) {
        int clearedCount = 0;
        for (Review review : getAllReviews(order)) {
            if (!review.isPublish() && review.getPublishedDate() != null) {
                review.setPublishedDate(null);
                clearedCount++;
            }
        }

        if (clearedCount > 0) {
            log.info("Очищены даты публикации у {} неопубликованных отзывов заказа ID {}",
                    clearedCount, order.getId());
        }
    }

    private boolean handlePublicStatus(Order order) {
        if (order != null
                && order.getId() != null
                && recoveryGateService.hasActiveRecoveryTasks(order.getId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Сначала выполните все задачи восстановления отзывов"
            );
        }
        try {
            log.info("=== НАЧАЛО ПЕРЕВОДА ЗАКАЗА В СТАТУС 'ПУБЛИКАЦИЯ' ===");

            orderBotLifecycleService.assignBotsIfNeeded(order);
            orderCompanyStatusService.autoManageCompanyStatus(order, STATUS_PUBLIC);
            contractorCompletionRewardService.ensureOrderCompletionAccrualNow(order.getId());

            String clientId = order.getManager() != null ? order.getManager().getClientId() : null;
            String groupId = order.getCompany() != null ? order.getCompany().getGroupId() : null;

            if (orderPaymentMessageBuilder.shouldSkipPublishedPayment(order)) {
                order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_PUBLIC));
                orderRepository.save(order);
                mobilePushBusinessNotificationService.notifyManagerOrderPublished(order);
                log.info("Счет после публикации пропущен: заказ ID {} по продукту 'Восстановление' без суммы к оплате",
                        order.getId());
                return true;
            }

            CommonBillingService commonBillingService = commonBillingServiceProvider.getIfAvailable();
            if (commonBillingService != null && commonBillingService.completePublishedOrderIntoCommonInvoice(order)) {
                mobilePushBusinessNotificationService.notifyManagerOrderPublished(order);
                log.info("Заказ ID {} завершен внутри общего счета, одиночный счет не отправлялся", order.getId());
                return true;
            }

            if (!immediateClientMessagesEnabled()) {
                log.info("Счет после публикации не отправлен: моментальные клиентские сообщения выключены, orderId={}",
                        order.getId());
                order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_PUBLIC));
                orderRepository.save(order);
                mobilePushBusinessNotificationService.notifyManagerOrderPublished(order);
                return true;
            }

            OrderPaymentMessageBuilder.PreparedPaymentMessage paymentMessage = preparePublishedPaymentMessage(order);
            if (paymentMessage == null) {
                mobilePushBusinessNotificationService.notifyManagerOrderPublished(order);
                return true;
            }

            boolean sent = orderStatusNotificationService.sendMessageToGroup(
                    STATUS_PUBLIC,
                    order,
                    clientId,
                    groupId,
                    paymentMessage.message(),
                    STATUS_TO_PAY,
                    paymentMessage.telegramCopyTransferNumber()
            );
            if (!sent) {
                paymentInvoiceRetryScheduler.scheduleRetry(order);
                log.warn("⚠️ Заказ ID {} оставлен в статусе 'Опубликовано': счет клиенту не отправлен", order.getId());
            }
            mobilePushBusinessNotificationService.notifyManagerOrderPublished(order);
            return true;

        } catch (ResponseStatusException e) {
            log.warn("=== СЧЕТ ПРИ ПЕРЕВОДЕ В 'ОПУБЛИКОВАНО' НЕ ПОДГОТОВЛЕН: {} ===", e.getReason());
            throw e;
        } catch (Exception e) {
            log.error("=== ОШИБКА ПРИ ПЕРЕВОДЕ ЗАКАЗА В СТАТУС 'ПУБЛИКАЦИЯ' ===", e);
            throw new RuntimeException("Ошибка при переводе заказа в статус 'Публикация'", e);
        }
    }

    private OrderPaymentMessageBuilder.PreparedPaymentMessage preparePublishedPaymentMessage(Order order) {
        try {
            return orderPaymentMessageBuilder.publishedOrderPaymentMessageWithTransfer(order);
        } catch (RuntimeException e) {
            order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_PUBLIC));
            orderRepository.save(order);
            paymentInvoiceRetryScheduler.scheduleRetry(order);
            log.warn("Заказ ID {} переведен в 'Опубликовано', но счет клиенту не подготовлен. Статус не откатывается.",
                    order.getId(), e);
            return null;
        }
    }

    private boolean handleManualToPayStatus(Order order) {
        log.info("=== РУЧНОЙ ПЕРЕВОД ЗАКАЗА В СТАТУС 'ВЫСТАВЛЕН СЧЕТ' ===");
        log.info("Заказ ID: {}, текущий статус: {}", order.getId(), safeStatusTitle(order));

        var summary = badReviewTaskService.getSummaryForOrder(order.getId());
        boolean badReviewFinalInvoiceReady = summary != null && summary.pending() == 0 && summary.done() > 0;
        if (badReviewFinalInvoiceReady || (summary != null && summary.done() > 0)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "После плохих отзывов заказ остается в статусе \"Не оплачено\" до оплаты или автобана"
            );
        }

        order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_TO_PAY));
        orderCompanyStatusService.autoManageCompanyStatus(order, STATUS_TO_PAY);
        orderRepository.save(order);

        log.info("✅ Заказ ID {} вручную переведен в статус 'Выставлен счет' без отправки сообщения клиенту", order.getId());
        return true;
    }

    private boolean immediateClientMessagesEnabled() {
        return appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_IMMEDIATE_ENABLED, true);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
