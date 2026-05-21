package com.hunt.otziv.p_products.status;

import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderStatusService;
import com.hunt.otziv.maxbot.service.MaxBotClient;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.whatsapp.dto.WhatsAppSendResult;
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
    private final MaxBotClient maxBotClient;

    public boolean sendMessageToGroup(
            String title,
            Order order,
            String clientId,
            String groupId,
            String message,
            String successStatus
    ) {
        sendMessageToClientChat(title, order, clientId, groupId, message, successStatus);
        return true;
    }

    public String sendMessageToClientChat(
            String title,
            Order order,
            String clientId,
            String groupId,
            String message,
            String successStatus
    ) {
        log.info("📨 Отправка сообщения в клиентский чат:");
        log.info("🔹 Клиент WhatsApp: {}", clientId);
        log.info("🔹 Группа WhatsApp: {}", groupId);
        log.info("🔹 Группа Telegram: {}", telegramGroupChatId(order));
        log.info("🔹 Группа MAX: {}", maxGroupChatId(order));
        log.info("🔹 Сообщение: {}", message.replaceAll("\\s+", " ").trim());

        String appliedStatus;
        if (hasText(groupId)) {
            appliedStatus = sendToWhatsAppOrFallback(title, order, clientId, groupId, message, successStatus);
        } else if (telegramGroupChatId(order) != null) {
            appliedStatus = sendToTelegramOrFallback(title, order, telegramGroupChatId(order), message, successStatus);
        } else if (maxGroupChatId(order) != null) {
            appliedStatus = sendToMaxOrFallback(title, order, maxGroupChatId(order), message, successStatus);
        } else {
            log.warn("⚠️ У компании {} отсутствуют WhatsApp groupId, Telegram group chatId и MAX group chatId. Статус выставлен без отправки сообщений",
                    companyTitle(order));
            appliedStatus = applyFallbackStatus(title, order);
        }

        orderRepository.save(order);
        log.info("💾 Заказ сохранён: ID {}. Компания - {}. Статус - {}",
                order.getId(), companyTitle(order), appliedStatus);

        return appliedStatus;
    }

    private String sendToWhatsAppOrFallback(
            String title,
            Order order,
            String clientId,
            String groupId,
            String message,
            String successStatus
    ) {
        WhatsAppSendResult result = WhatsAppSendResult.parse(whatsAppService.sendMessageToGroup(clientId, groupId, message));

        if (result.isOk()) {
            return applySuccessStatus(successStatus, order, "WhatsApp");
        }

        log.warn("⚠️ Сообщение в WhatsApp-группу не прошло: code={}, error={}",
                result.code(), result.displayError());
        notifyManagerAboutFallback(title, order);
        return applyFallbackStatus(title, order);
    }

    private String sendToTelegramOrFallback(
            String title,
            Order order,
            Long telegramChatId,
            String message,
            String successStatus
    ) {
        boolean sent = telegramService.sendMessage(telegramChatId, message);
        if (sent) {
            return applySuccessStatus(successStatus, order, "Telegram");
        }

        log.warn("⚠️ Сообщение в Telegram-группу {} не отправлено", telegramChatId);
        notifyManagerAboutFallback(title, order);
        return applyFallbackStatus(title, order);
    }

    private String sendToMaxOrFallback(
            String title,
            Order order,
            Long maxChatId,
            String message,
            String successStatus
    ) {
        boolean sent = maxBotClient.sendMessageToChat(maxChatId, message);
        if (sent) {
            return applySuccessStatus(successStatus, order, "MAX");
        }

        log.warn("⚠️ Сообщение в MAX-группу {} не отправлено", maxChatId);
        notifyManagerAboutFallback(title, order);
        return applyFallbackStatus(title, order);
    }

    private String applySuccessStatus(String successStatus, Order order, String channel) {
        order.setStatus(orderStatusService.getOrderStatusByTitle(successStatus));
        log.info("✅ Сообщение клиенту отправлено через {}. Статус заказа успешно обновлён на: {}", channel, successStatus);
        return successStatus;
    }

    private String applyFallbackStatus(String title, Order order) {
        order.setStatus(orderStatusService.getOrderStatusByTitle(title));
        log.info("🔄 Статус заказа установлен вручную: {}", title);
        return title;
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

    private Long telegramGroupChatId(Order order) {
        return order != null && order.getCompany() != null ? order.getCompany().getTelegramGroupChatId() : null;
    }

    private Long maxGroupChatId(Order order) {
        return order != null && order.getCompany() != null ? order.getCompany().getMaxGroupChatId() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
