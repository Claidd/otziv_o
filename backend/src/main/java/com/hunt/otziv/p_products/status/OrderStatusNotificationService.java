package com.hunt.otziv.p_products.status;

import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderStatusService;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static com.hunt.otziv.p_products.utils.OrderReviewGraph.hasDetails;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderStatusNotificationService {

    private static final String STATUS_TO_CHECK = "В проверку";
    private static final String STATUS_PUBLIC = "Опубликовано";

    private final OrderRepository orderRepository;
    private final OrderStatusService orderStatusService;
    private final WhatsAppService whatsAppService;
    private final TelegramService telegramService;

    public boolean sendMessageToGroup(
            String title,
            Order order,
            String clientId,
            String groupId,
            String message,
            String successStatus
    ) {
        log.info("📨 Отправка сообщения в WhatsApp-группу:");
        log.info("🔹 Клиент: {}", clientId);
        log.info("🔹 Группа: {}", groupId);
        log.info("🔹 Сообщение: {}", message.replaceAll("\\s+", " ").trim());

        String result = whatsAppService.sendMessageToGroup(clientId, groupId, message);

        if (result != null && result.toLowerCase().contains("ok")) {
            order.setStatus(orderStatusService.getOrderStatusByTitle(successStatus));
            log.info("✅ Статус заказа успешно обновлён на: {}", successStatus);
        } else {
            log.warn("⚠️ Сообщение в WhatsApp-группу не прошло: {}", result);
            notifyManagerAboutFallback(title, order);
            order.setStatus(orderStatusService.getOrderStatusByTitle(title));
            log.info("🔄 Статус заказа установлен вручную: {}", title);
        }

        orderRepository.save(order);
        log.info("💾 Заказ сохранён: ID {}. Компания - {}", order.getId(),
                order.getCompany() != null ? order.getCompany().getTitle() : "null");

        return true;
    }

    public boolean hasWorkerWithTelegram(Order order) {
        try {
            return order != null
                    && order.getWorker() != null
                    && order.getWorker().getUser() != null
                    && order.getWorker().getUser().getTelegramChatId() != null
                    && hasDetails(order)
                    && order.getCompany() != null;
        } catch (Exception e) {
            return false;
        }
    }

    private void notifyManagerAboutFallback(String title, Order order) {
        String managerChatId = order.getManager() != null && order.getManager().getUser() != null
                && order.getManager().getUser().getTelegramChatId() != null
                ? String.valueOf(order.getManager().getUser().getTelegramChatId())
                : null;

        if (STATUS_TO_CHECK.equals(title) && hasManagerWithTelegram(order) && hasText(managerChatId)) {
            String url = "https://o-ogo.ru/orders/all_orders?status=В%20проверку";
            String text = companyTitle(order) + " готов - На проверку\n" + url;
            telegramService.sendMessage(Long.parseLong(managerChatId), text);
            log.info("📬 Уведомление менеджеру отправлено в Telegram: {} → В проверку", managerChatId);
        }

        if (STATUS_PUBLIC.equals(title) && hasManagerWithTelegram(order) && hasText(managerChatId)) {
            String url = "https://o-ogo.ru/orders/all_orders?status=Опубликовано";
            String text = companyTitle(order) + " Опубликован\n" + url;
            telegramService.sendMessage(Long.parseLong(managerChatId), text);
            log.info("📬 Уведомление менеджеру отправлено в Telegram: {} → Опубликовано", managerChatId);
        }
    }

    private boolean hasManagerWithTelegram(Order order) {
        try {
            return order != null
                    && order.getManager() != null
                    && order.getManager().getUser() != null
                    && order.getManager().getUser().getTelegramChatId() != null
                    && hasDetails(order)
                    && order.getCompany() != null;
        } catch (Exception e) {
            return false;
        }
    }

    private String companyTitle(Order order) {
        return order.getCompany() != null ? order.getCompany().getTitle() : "Компания";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
