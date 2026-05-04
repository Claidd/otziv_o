package com.hunt.otziv.p_products.status;

import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.l_lead.services.serv.PromoTextService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.model.OrderDetails;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderStatusService;
import com.hunt.otziv.p_products.services.service.OrderTransactionService;
import com.hunt.otziv.r_review.model.Review;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import jakarta.ws.rs.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.hunt.otziv.p_products.utils.OrderReviewGraph.getAllReviews;
import static com.hunt.otziv.p_products.utils.OrderReviewGraph.getFirstDetail;
import static com.hunt.otziv.p_products.utils.OrderReviewGraph.safeFilialTitle;
import static com.hunt.otziv.p_products.utils.OrderReviewGraph.safeStatusTitle;
import static com.hunt.otziv.p_products.utils.OrderReviewGraph.safeString;

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

    private final OrderRepository orderRepository;
    private final OrderStatusService orderStatusService;
    private final OrderTransactionService orderTransactionService;
    private final BadReviewTaskService badReviewTaskService;
    private final PromoTextService textService;
    private final TelegramService telegramService;
    private final OrderCompanyStatusService orderCompanyStatusService;
    private final OrderStatusNotificationService orderStatusNotificationService;
    private final OrderBotLifecycleService orderBotLifecycleService;

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
                default -> {
                    order.setStatus(orderStatusService.getOrderStatusByTitle(title));
                    orderRepository.save(order);
                    yield true;
                }
            };

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

    private boolean handleToPublicStatus(Order order) {
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

        order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_ARCHIVE));
        orderCompanyStatusService.autoManageCompanyStatus(order, STATUS_ARCHIVE);

        orderBotLifecycleService.detachBots(order);

        orderRepository.save(order);

        log.info("=== ЗАКАЗ ID {} УСПЕШНО АРХИВИРОВАН ===", order.getId());
        return true;
    }

    private boolean handleToCheckStatus(Order order) {
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

            if (!hasText(groupId)) {
                log.warn("⚠️ У компании {} отсутствует groupId. Статус выставлен без отправки сообщений",
                        order.getCompany().getTitle());
                order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_TO_CHECK));
                orderRepository.save(order);
                log.info("✅ Заказ ID {} переведен в статус 'На проверку' (без отправки WhatsApp)", order.getId());
                return true;
            }

            log.info("Отправляем сообщение в WhatsApp для заказа ID: {}", order.getId());
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

            order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_CORRECTION));
            orderRepository.save(order);

            log.info("✅ Заказ ID {} переведен в статус 'Коррекция'", order.getId());
            return true;

        } catch (Exception e) {
            log.error("=== ОШИБКА ПРИ ПЕРЕВОДЕ ЗАКАЗА В СТАТУС 'КОРРЕКЦИЯ' ===", e);
            try {
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

            if (!hasText(groupId)) {
                order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_PUBLIC));
                orderRepository.save(order);
                log.info("✅ Статус заказа {} установлен в '{}' без отправки в WhatsApp (отсутствует groupId)",
                        order.getId(), STATUS_PUBLIC);
                return true;
            }

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
