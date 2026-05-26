package com.hunt.otziv.p_products.services;

import com.hunt.otziv.config.email.EmailService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderStatusCheckerService;
import com.hunt.otziv.p_products.status.OrderPaymentMessageBuilder;
import com.hunt.otziv.p_products.status.OrderStatusNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderStatusCheckerServiceImpl implements OrderStatusCheckerService {

    private final EmailService emailService;
    private final OrderRepository orderRepository;
    private final OrderStatusNotificationService orderStatusNotificationService;
    private final OrderPaymentMessageBuilder orderPaymentMessageBuilder;

    private static final String STATUS_PUBLIC = "Опубликовано";
    public static final String STATUS_TO_PAY = "Выставлен счет";

    @Override
    public void validateCounterConsistency(Order order, int actualPublished) {
        if (order == null) {
            return;
        }

        if (order.getCounter() != actualPublished) {
            int previousCounter = order.getCounter();
            order.setCounter(actualPublished);
            orderRepository.save(order);

            String msg = String.format("Компания: %s. Заказ № %d. Работник: %s. Было: %d. Стало: %d",
                    safeCompanyTitle(order),
                    order.getId(),
                    safeWorkerName(order),
                    previousCounter,
                    actualPublished);

            if (isExpectedSingleReviewChange(previousCounter, actualPublished)) {
                log.info("Счетчик заказа синхронизирован после изменения публикации: {}", msg);
                return;
            }

            log.warn("Счетчик заказа автоматически исправлен после расхождения: {}", msg);

            try {
                emailService.sendSimpleEmail(
                        "2.12nps@mail.ru",
                        "Исправлен счетчик заказа",
                        "Счетчик заказа был автоматически исправлен. " + msg
                );
            } catch (Exception e) {
                log.warn("Не удалось отправить уведомление об исправлении счетчика заказа {}", order.getId(), e);
            }
        }
    }

    private boolean isExpectedSingleReviewChange(int previousCounter, int actualPublished) {
        return Math.abs(actualPublished - previousCounter) == 1;
    }

    @Override
    public void checkAndMarkOrderCompleted(Order order) throws Exception {
        if (order.getAmount() <= order.getCounter()) {
            String newStatus = handlePublicStatus(order);
            log.info("Счётчик достиг лимита. Статус заказа {} изменён на {}", order.getId(), newStatus);
        } else {
            log.info("Счётчик заказа {} не достиг лимита. Статус не изменён", order.getId());
        }
    }

    private String handlePublicStatus(Order order) {
        String clientId = order.getManager().getClientId();
        String groupId = order.getCompany().getGroupId();

        String message = orderPaymentMessageBuilder.publishedOrderPaymentMessage(order);

        return orderStatusNotificationService.sendMessageToClientChat(
                STATUS_PUBLIC,
                order,
                clientId,
                groupId,
                message,
                STATUS_TO_PAY
        );
    }

    private String safeCompanyTitle(Order order) {
        return Optional.ofNullable(order.getCompany())
                .map(company -> company.getTitle())
                .orElse("Не указана");
    }

    private String safeWorkerName(Order order) {
        return Optional.ofNullable(order.getWorker())
                .map(worker -> worker.getUser())
                .map(user -> user.getFio())
                .orElse("Не указан");
    }
}
