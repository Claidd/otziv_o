package com.hunt.otziv.p_products.status;

import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.l_lead.services.serv.PromoTextService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderStatusService;
import com.hunt.otziv.p_products.services.service.OrderTransactionService;
import com.hunt.otziv.r_review.model.Review;
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
import static com.hunt.otziv.p_products.utils.OrderReviewGraph.safeFilialTitle;
import static com.hunt.otziv.p_products.utils.OrderReviewGraph.safeStatusTitle;
import static com.hunt.otziv.p_products.utils.OrderReviewGraph.safeString;
import static com.hunt.otziv.r_review.utils.ReviewTextPolicy.isBlankOrPlaceholder;

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
    private final PromoTextService textService;
    private final TelegramService telegramService;
    private final OrderCompanyStatusService orderCompanyStatusService;
    private final OrderStatusNotificationService orderStatusNotificationService;
    private final OrderBotLifecycleService orderBotLifecycleService;
    private final ReviewArchiveService reviewArchiveService;
    private final ReviewRepository reviewRepository;

    @Transactional
    public boolean changeStatusForOrder(Long orderID, String title) throws Exception {
        try {
            Order order = orderRepository.findById(orderID)
                    .orElseThrow(() -> new NotFoundException("Order not found for orderID: " + orderID));

            return switch (title) {
                case STATUS_PAYMENT -> orderTransactionService.handlePaymentStatus(order);
                case STATUS_ARCHIVE -> handleArchiveStatus(order);
                case STATUS_TO_CHECK -> handleToCheckStatus(order);
                case STATUS_CORRECTION -> handleCorrectionStatus(order);
                case STATUS_PUBLIC -> handlePublicStatus(order);
                case STATUS_TO_PUBLISH -> handleToPublicStatus(order);
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

            return true;

        } catch (Exception e) {
            log.error("=== ОШИБКА ПРИ ПЕРЕВОДЕ ЗАКАЗА В СТАТУС 'К ПУБЛИКАЦИИ' ===", e);
            throw new RuntimeException("Ошибка при переводе заказа в статус 'К публикации'", e);
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
                reviewArchiveService.saveNewReviewArchive(review.getId());
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
                "Нельзя отправить заказ на проверку: текст отзыва уже публиковался ранее. Измените текст отзыва и сохраните его дискеткой."
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
                "Нельзя отправить заказ в публикацию: текст отзыва уже публиковался ранее. Измените текст отзыва и сохраните его дискеткой."
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
                .filter(review -> reviewRepository.existsPublishedByTextExcludingReviewId(review.getText(), review.getId()))
                .toList();

        rejectIfDuplicatedReviewsFound(order, logActionTitle, errorMessage, duplicateReviews, "ранее опубликованные тексты");
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
        log.warn("{} заказа ID {} отменена: {} у отзывов {}",
                logActionTitle, order.getId(), reason, reviewIds);

        throw new ResponseStatusException(HttpStatus.CONFLICT, errorMessage);
    }

    private String normalizedReviewText(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
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
                return true;
            }

            String message = order.getCompany().getTitle() + ". " + safeFilialTitle(order) + "\n\n" +
                    textService.findById(5) + "\n\n" +
                    "Ссылка на проверку отзывов: https://o-ogo.ru/review/editReviews/" + firstDetail.getId();

            log.info("Отправляем сообщение клиенту для заказа ID: {}", order.getId());
            boolean result = orderStatusNotificationService.sendMessageToGroup(
                    STATUS_TO_CHECK,
                    order,
                    clientId,
                    groupId,
                    message,
                    STATUS_IN_CHECK
            );

            if (result) {
                log.info("✅ Заказ ID {} переведен в статус 'На проверку' (сообщение отправлено)", order.getId());
            } else {
                log.error("❌ Ошибка при отправке сообщения для заказа ID: {}", order.getId());
            }

            return result;

        } catch (Exception e) {
            log.error("=== ОШИБКА ПРИ ПЕРЕВОДЕ ЗАКАЗА В СТАТУС 'НА ПРОВЕРКУ' ===", e);
            try {
                order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_TO_CHECK));
                orderRepository.save(order);
                log.warn("Статус заказа ID {} изменен на 'На проверку' без дополнительных действий из-за ошибки",
                        order.getId());
            } catch (Exception ex) {
                log.error("Критическая ошибка при сохранении статуса: {}", ex.getMessage());
            }
            return false;
        }
    }

    private boolean handleCorrectionStatus(Order order) {
        try {
            log.info("=== НАЧАЛО ПЕРЕВОДА ЗАКАЗА В СТАТУС 'КОРРЕКЦИЯ' ===");
            log.info("Заказ ID: {}, текущий статус: {}", order.getId(), safeStatusTitle(order));

            orderBotLifecycleService.assignBotsIfNeeded(order);
            orderCompanyStatusService.autoManageCompanyStatus(order, STATUS_CORRECTION);

            if (orderStatusNotificationService.hasWorkerWithTelegram(order)) {
                String companyTitle = order.getCompany().getTitle();
                String comments = order.getCompany().getCommentsCompany();
                telegramService.sendMessage(
                        order.getWorker().getUser().getTelegramChatId(),
                        companyTitle + " отправлен в Коррекцию - " + safeString(order.getZametka()) + " " + safeString(comments) +
                                "\n https://o-ogo.ru/worker/correct"
                );
                log.info("Уведомление о коррекции отправлено в Telegram");
            }

            clearPublicationDatesForUnpublishedReviews(order);

            order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_CORRECTION));
            orderRepository.save(order);

            log.info("✅ Заказ ID {} переведен в статус 'Коррекция'", order.getId());
            return true;

        } catch (Exception e) {
            log.error("=== ОШИБКА ПРИ ПЕРЕВОДЕ ЗАКАЗА В СТАТУС 'КОРРЕКЦИЯ' ===", e);
            try {
                clearPublicationDatesForUnpublishedReviews(order);
                order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_CORRECTION));
                orderRepository.save(order);
                log.warn("Статус заказа ID {} изменен на 'Коррекция' без дополнительных действий из-за ошибки",
                        order.getId());
            } catch (Exception ex) {
                log.error("Критическая ошибка при сохранении статуса: {}", ex.getMessage());
            }
            return false;
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

            String message = order.getCompany().getTitle() + ". " + safeFilialTitle(order) + "\n\n" +
                    "Здравствуйте, ваш заказ выполнен, просьба оплатить. АЛЬФА-БАНК по счету " +
                    "https://pay.alfabank.ru/sc/EWwpfrArNZotkqOR получатель: Сивохин И.И. " +
                    "ПРИШЛИТЕ ЧЕК, пожалуйста, как оплатите) К оплате: " + order.getSum() + " руб.";

            return orderStatusNotificationService.sendMessageToGroup(
                    STATUS_PUBLIC,
                    order,
                    clientId,
                    groupId,
                    message,
                    STATUS_TO_PAY
            );

        } catch (Exception e) {
            log.error("=== ОШИБКА ПРИ ПЕРЕВОДЕ ЗАКАЗА В СТАТУС 'ПУБЛИКАЦИЯ' ===", e);
            throw new RuntimeException("Ошибка при переводе заказа в статус 'Публикация'", e);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
