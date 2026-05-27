package com.hunt.otziv.p_products.status;

import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.mobile_push.service.MobilePushBusinessNotificationService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderStatusService;
import com.hunt.otziv.p_products.services.service.OrderTransactionService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.r_review.model.ReviewArchiveSourceReason;
import com.hunt.otziv.r_review.repository.ReviewRepository;
import com.hunt.otziv.r_review.services.ReviewArchiveService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import jakarta.ws.rs.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

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

    private final OrderRepository orderRepository;
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

    @Transactional
    public boolean changeStatusForOrder(Long orderID, String title) throws Exception {
        try {
            Order order = orderRepository.findById(orderID)
                    .orElseThrow(() -> new NotFoundException("Order not found for orderID: " + orderID));

            return switch (title) {
                case STATUS_PAYMENT -> orderTransactionService.handlePaymentStatus(order);
                case STATUS_ARCHIVE -> handleArchiveStatus(order);
                case STATUS_TO_CHECK -> handleToCheckStatus(order);
                case STATUS_IN_CHECK -> handleManualInCheckStatus(order);
                case STATUS_CORRECTION -> handleCorrectionStatus(order);
                case STATUS_PUBLIC -> handlePublicStatus(order);
                case STATUS_TO_PUBLISH -> handleToPublicStatus(order);
                case STATUS_TO_PAY -> handleManualToPayStatus(order);
                case STATUS_NOT_PAID -> handleNotPaidStatus(order);
                case STATUS_BAN -> handleBanStatus(order);
                default -> {
                    order.setStatus(orderStatusService.getOrderStatusByTitle(title));
                    orderRepository.save(order);
                    yield true;
                }
            };

        } catch (ResponseStatusException e) {
            log.warn("Смена статуса заказа отклонена: {}", e.getReason());
            throw e;
        } catch (Exception e) {
            log.error("При смене статуса произошли какие-то проблемы", e);
            throw e;
        }
    }

    private boolean handleNotPaidStatus(Order order) {
        order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_NOT_PAID));
        orderRepository.save(order);
        badReviewTaskService.createTasksForUnpaidOrder(order);
        return true;
    }

    private boolean handleBanStatus(Order order) {
        if (!STATUS_NOT_PAID.equals(safeStatusTitle(order))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Перевести заказ в Бан можно только из статуса \"Не оплачено\"");
        }

        var summary = badReviewTaskService.getSummaryForOrder(order.getId());
        if (summary == null || summary.done() <= 0 || summary.pending() > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Сначала выполните все плохие задачи заказа");
        }

        order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_BAN));
        orderCompanyStatusService.autoManageCompanyStatus(order, STATUS_BAN);
        badReviewTaskService.deleteOrderReadyReminder(order);
        orderRepository.save(order);
        return true;
    }

    private boolean handleToPublicStatus(Order order) {
        validateReviewsReadyForPublication(order);

        try {
            log.info("=== НАЧАЛО ПЕРЕВОДА ЗАКАЗА В СТАТУС 'К ПУБЛИКАЦИИ' ===");
            log.info("Заказ ID: {}, текущий статус: {}", order.getId(), safeStatusTitle(order));

            String previousOrderStatus = safeStatusTitle(order);

            order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_TO_PUBLISH));
            orderCompanyStatusService.autoManageCompanyStatus(order, STATUS_TO_PUBLISH);
            orderBotLifecycleService.assignBotsIfNeeded(order);

            List<Review> reviews = getAllReviews(order);
            if (reviews.isEmpty()) {
                log.warn("В заказе ID {} нет отзывов", order.getId());
            } else {
                orderBotLifecycleService.checkAndNotifyAboutStubBots(reviews);
            }

            orderRepository.save(order);

            log.info("=== УСПЕШНЫЙ ПЕРЕВОД ЗАКАЗА ===");
            if (STATUS_ARCHIVE.equals(previousOrderStatus)) {
                log.info("Заказ ID {} переведен в статус 'К публикации' ИЗ АРХИВА", order.getId());

                if (orderStatusNotificationService.hasWorkerWithTelegram(order)) {
                    String companyTitle = order.getCompany().getTitle();
                    telegramService.sendMessage(
                            order.getWorker().getUser().getTelegramChatId(),
                            companyTitle + ". Новый заказ из Архива. " +
                                    "\n https://o-ogo.ru/worker/new_orders"
                    );
                }
            } else {
                log.info("Заказ ID {} переведен в статус 'К публикации'", order.getId());
            }

            notifyClientAboutPublicationStarted(order, previousOrderStatus);
            return true;

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
            mobilePushBusinessNotificationService.notifyWorkerCorrection(order);
            enqueueCorrectionTelegramNotification(order);

            log.info("✅ Заказ ID {} переведен в статус 'Коррекция'", order.getId());
            return true;

        } catch (Exception e) {
            log.error("=== ОШИБКА ПРИ ПЕРЕВОДЕ ЗАКАЗА В СТАТУС 'КОРРЕКЦИЯ' ===", e);
            try {
                clearPublicationDatesForUnpublishedReviews(order);
                order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_CORRECTION));
                orderRepository.save(order);
                mobilePushBusinessNotificationService.notifyWorkerCorrection(order);
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

            Long chatId = order.getWorker().getUser().getTelegramChatId();
            String companyTitle = order.getCompany() == null ? "" : safeString(order.getCompany().getTitle());
            String comments = order.getCompany() == null ? "" : safeString(order.getCompany().getCommentsCompany());
            orderCorrectionTelegramNotifier.notifyWorkerCorrection(
                    order.getId(),
                    chatId,
                    companyTitle,
                    safeString(order.getZametka()),
                    comments
            );
            log.info("Уведомление о коррекции заказа ID {} поставлено в очередь Telegram", order.getId());
        } catch (RuntimeException e) {
            log.warn("Не удалось поставить Telegram-уведомление о коррекции заказа ID {} в очередь. Статус уже изменен.",
                    order.getId(), e);
        }
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
        try {
            log.info("=== НАЧАЛО ПЕРЕВОДА ЗАКАЗА В СТАТУС 'ПУБЛИКАЦИЯ' ===");

            orderBotLifecycleService.assignBotsIfNeeded(order);
            orderCompanyStatusService.autoManageCompanyStatus(order, STATUS_PUBLIC);

            String clientId = order.getManager() != null ? order.getManager().getClientId() : null;
            String groupId = order.getCompany() != null ? order.getCompany().getGroupId() : null;

            String message = orderPaymentMessageBuilder.publishedOrderPaymentMessage(order);

            boolean sent = orderStatusNotificationService.sendMessageToGroup(
                    STATUS_PUBLIC,
                    order,
                    clientId,
                    groupId,
                    message,
                    STATUS_TO_PAY
            );
            if (!sent) {
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

    private boolean handleManualToPayStatus(Order order) {
        log.info("=== РУЧНОЙ ПЕРЕВОД ЗАКАЗА В СТАТУС 'ВЫСТАВЛЕН СЧЕТ' ===");
        log.info("Заказ ID: {}, текущий статус: {}", order.getId(), safeStatusTitle(order));

        order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_TO_PAY));
        orderCompanyStatusService.autoManageCompanyStatus(order, STATUS_TO_PAY);
        orderRepository.save(order);

        log.info("✅ Заказ ID {} вручную переведен в статус 'Выставлен счет' без отправки сообщения клиенту", order.getId());
        return true;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
