package com.hunt.otziv.p_products.services;

import com.hunt.otziv.config.email.EmailService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderStatusCheckerService;
import com.hunt.otziv.p_products.services.service.OrderStatusService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.whatsapp.dto.WhatsAppSendResult;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderStatusCheckerServiceImpl implements OrderStatusCheckerService {

    private final OrderStatusService orderStatusService;
    private final TelegramService telegramService;
    private final EmailService emailService;
    private final WhatsAppService whatsAppService;
    private final OrderRepository orderRepository;

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

            log.warn("Счетчик заказа автоматически исправлен: {}", msg);

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

    @Override
    public void checkAndMarkOrderCompleted(Order order) throws Exception {
        if (order.getAmount() <= order.getCounter()) {
            String newStatus = handlePublicStatus(order);
            log.info("Счётчик достиг лимита. Статус заказа {} изменён на {}", order.getId(), newStatus);

            if (STATUS_PUBLIC.equals(newStatus)) {
                Optional.ofNullable(order.getManager())
                        .map(Manager::getUser)
                        .map(User::getTelegramChatId)
                        .ifPresent(chatId -> {
                            String message = order.getCompany().getTitle() + " опубликован.\n" +
                                    "https://o-ogo.ru/orders/all_orders?status=Опубликовано";
                            telegramService.sendMessage(chatId, message);
                        });
            }
        } else {
            log.info("Счётчик заказа {} не достиг лимита. Статус не изменён", order.getId());
        }
    }

    private String handlePublicStatus(Order order) {
        String clientId = order.getManager().getClientId();
        String groupId = order.getCompany().getGroupId();

        String message = order.getCompany().getTitle() + ". " + order.getFilial().getTitle() + "\n\n" +
                "Здравствуйте, ваш заказ выполнен, просьба оплатить.  АЛЬФА-БАНК по счету https://pay.alfabank.ru/sc/EWwpfrArNZotkqOR получатель: Сивохин И.И.  ПРИШЛИТЕ ЧЕК, пожалуйста, как оплатите) К оплате: " +
                order.getSum() + " руб.";

        if (groupId == null || groupId.isBlank()) {
            order.setStatus(orderStatusService.getOrderStatusByTitle(STATUS_PUBLIC));
            orderRepository.save(order);
            log.info("✅ Статус заказа {} установлен в '{}' без отправки в WhatsApp (отсутствует groupId)", order.getId(), STATUS_PUBLIC);
            return STATUS_PUBLIC;
        }

        return sentMessageToGroup(STATUS_PUBLIC, order, clientId, groupId, message, STATUS_TO_PAY);
    }

    private String sentMessageToGroup(String fallbackStatus, Order order, String clientId, String groupId, String message, String successStatus) {
        WhatsAppSendResult result = WhatsAppSendResult.parse(whatsAppService.sendMessageToGroup(clientId, groupId, message));

        if (result.isOk()) {
            order.setStatus(orderStatusService.getOrderStatusByTitle(successStatus));
            orderRepository.save(order);
            return successStatus;
        } else {
            log.warn("Сообщение в WhatsApp-группу не отправлено: code={}, error={}",
                    result.code(), result.displayError());
            if (hasManagerWithTelegram(order)) {
                String companyTitle = order.getDetails().getFirst().getOrder().getCompany().getTitle();
                telegramService.sendMessage(
                        order.getManager().getUser().getTelegramChatId(),
                        companyTitle + " готов - На проверку \n" +
                                "https://o-ogo.ru/orders/all_orders?status=В%20проверку"
                );
            }
            order.setStatus(orderStatusService.getOrderStatusByTitle(fallbackStatus));
            orderRepository.save(order);
            return fallbackStatus;
        }
    }

    private boolean hasManagerWithTelegram(Order order) {
        try {
            return order != null &&
                    order.getManager() != null &&
                    order.getManager().getUser() != null &&
                    order.getManager().getUser().getTelegramChatId() != null &&
                    order.getDetails() != null &&
                    !order.getDetails().isEmpty() &&
                    order.getDetails().getFirst() != null &&
                    order.getDetails().getFirst().getOrder() != null &&
                    order.getDetails().getFirst().getOrder().getCompany() != null;
        } catch (Exception e) {
            return false;
        }
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
