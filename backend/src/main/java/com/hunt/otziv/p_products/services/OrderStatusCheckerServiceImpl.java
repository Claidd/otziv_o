package com.hunt.otziv.p_products.services;

import com.hunt.otziv.config.email.EmailService;
import com.hunt.otziv.client_messages.PaymentInvoiceRetryScheduler;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderStatusCheckerService;
import com.hunt.otziv.p_products.services.service.OrderStatusService;
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
    private final PaymentInvoiceRetryScheduler paymentInvoiceRetryScheduler;
    private final OrderStatusService orderStatusService;
    private final AppSettingService appSettingService;

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

        if (orderPaymentMessageBuilder.shouldSkipPublishedPayment(order)) {
            order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_PUBLIC));
            orderRepository.save(order);
            log.info("Счет после публикации пропущен: заказ {} по продукту 'Восстановление' без суммы к оплате",
                    order.getId());
            return STATUS_PUBLIC;
        }

        if (!immediateClientMessagesEnabled()) {
            order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_PUBLIC));
            orderRepository.save(order);
            log.info("Счет после публикации не отправлен: моментальные клиентские сообщения выключены, orderId={}",
                    order.getId());
            return STATUS_PUBLIC;
        }

        String message = preparePublishedPaymentMessage(order);
        if (message == null) {
            return STATUS_PUBLIC;
        }

        String appliedStatus = orderStatusNotificationService.sendMessageToClientChat(
                STATUS_PUBLIC,
                order,
                clientId,
                groupId,
                message,
                STATUS_TO_PAY
        );
        if (!STATUS_TO_PAY.equals(appliedStatus)) {
            paymentInvoiceRetryScheduler.scheduleRetry(order);
        }
        return appliedStatus;
    }

    private String preparePublishedPaymentMessage(Order order) {
        try {
            return orderPaymentMessageBuilder.publishedOrderPaymentMessage(order);
        } catch (RuntimeException e) {
            order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_PUBLIC));
            orderRepository.save(order);
            paymentInvoiceRetryScheduler.scheduleRetry(order);
            log.warn("Заказ {} опубликован, но счет клиенту не подготовлен. Публикация не откатывается.",
                    order.getId(), e);
            return null;
        }
    }

    private boolean immediateClientMessagesEnabled() {
        return appSettingService.getBoolean(AppSettingService.CLIENT_MESSAGES_IMMEDIATE_ENABLED, true);
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
