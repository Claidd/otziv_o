package com.hunt.otziv.p_products.status;

import com.hunt.otziv.client_messages.PublicationProgressPreferenceService;
import com.hunt.otziv.p_products.model.Order;
import com.hunt.otziv.p_products.repository.OrderRepository;
import com.hunt.otziv.p_products.services.service.OrderStatusService;
import com.hunt.otziv.maxbot.service.MaxBotClient;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.whatsapp.dto.WhatsAppSendResult;
import com.hunt.otziv.whatsapp.service.WhatsAppAuthAlertService;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

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
    private final WhatsAppAuthAlertService whatsAppAuthAlertService;
    private final PublicationProgressPreferenceService publicationProgressPreferenceService;

    public boolean sendMessageToGroup(
            String title,
            Order order,
            String clientId,
            String groupId,
            String message,
            String successStatus
    ) {
        String appliedStatus = sendMessageToClientChat(title, order, clientId, groupId, message, successStatus);
        return Objects.equals(appliedStatus, successStatus);
    }

    public boolean sendProgressMessageToClientChat(
            Order order,
            String clientId,
            String groupId,
            String message
    ) {
        log.info("📨 Отправка короткого отчёта в клиентский чат");
        String sentChannel = sendProgressToActiveClientChat(order, clientId, groupId, message);
        if (sentChannel != null) {
            log.info("✅ Короткий отчёт клиенту отправлен через {}", sentChannel);
            return true;
        }

        log.warn("⚠️ Короткий отчёт компании {} не отправлен в активный клиентский мессенджер",
                companyTitle(order));
        return false;
    }

    private String sendProgressToActiveClientChat(
            Order order,
            String clientId,
            String groupId,
            String message
    ) {
        ChatPlatform activePlatform = activeChatPlatform(order);
        Long telegramChatId = telegramGroupChatId(order);
        Long maxChatId = maxGroupChatId(order);
        Long companyId = order != null && order.getCompany() != null ? order.getCompany().getId() : null;

        return switch (activePlatform) {
            case WHATSAPP -> hasText(groupId)
                    ? sendToWhatsApp(order, clientId, groupId, publicationProgressPreferenceService.appendPlainOptOutHint(message))
                    : missingActiveChannel("WhatsApp", order);
            case TELEGRAM -> telegramChatId != null
                    ? sendProgressToTelegram(telegramChatId, companyId, message)
                    : missingActiveChannel("Telegram", order);
            case MAX -> maxChatId != null
                    ? sendToMax(maxChatId, publicationProgressPreferenceService.appendPlainOptOutHint(message))
                    : missingActiveChannel("MAX", order);
            case UNKNOWN -> missingActiveChannel("неизвестный мессенджер", order);
        };
    }

    public boolean sendInformationalMessageToClientChat(
            Order order,
            String clientId,
            String groupId,
            String message,
            String actionTitle
    ) {
        log.info("📨 Отправка клиентского уведомления: {}", actionTitle);
        String sentChannel = sendToActiveClientChat(order, clientId, groupId, message);
        if (sentChannel != null) {
            log.info("✅ Клиентское уведомление \"{}\" отправлено через {}", actionTitle, sentChannel);
            return true;
        }

        log.warn("⚠️ Клиентское уведомление \"{}\" для компании {} не отправлено в активный клиентский мессенджер",
                actionTitle, companyTitle(order));
        return false;
    }

    public String sendMessageToClientChat(
            String title,
            Order order,
            String clientId,
            String groupId,
            String message,
            String successStatus
    ) {
        String sentChannel = sendToActiveClientChat(order, clientId, groupId, message);

        String appliedStatus;
        if (sentChannel != null) {
            appliedStatus = applySuccessStatus(successStatus, order, sentChannel);
        } else {
            log.warn("⚠️ Сообщение компании {} не отправлено в активный клиентский мессенджер. Статус останется: {}",
                    companyTitle(order), title);
            notifyManagerAboutFallback(title, order);
            appliedStatus = applyFallbackStatus(title, order);
        }

        orderRepository.save(order);
        log.info("💾 Заказ сохранён: ID {}. Компания - {}. Статус - {}",
                order.getId(), companyTitle(order), appliedStatus);

        return appliedStatus;
    }

    private String sendToActiveClientChat(
            Order order,
            String clientId,
            String groupId,
            String message
    ) {
        log.info("📨 Отправка сообщения в клиентский чат:");
        log.info("🔹 Клиент WhatsApp: {}", clientId);
        log.info("🔹 Группа WhatsApp: {}", groupId);
        Long telegramChatId = telegramGroupChatId(order);
        Long maxChatId = maxGroupChatId(order);
        log.info("🔹 Группа Telegram: {}", telegramChatId);
        log.info("🔹 Группа MAX: {}", maxChatId);
        log.info("🔹 Сообщение: {}", message.replaceAll("\\s+", " ").trim());

        ChatPlatform activePlatform = activeChatPlatform(order);
        log.info("🔹 Активный канал по ссылке: {}", activePlatform);

        String sentChannel = switch (activePlatform) {
            case WHATSAPP -> hasText(groupId) ? sendToWhatsApp(order, clientId, groupId, message) : missingActiveChannel("WhatsApp", order);
            case TELEGRAM -> telegramChatId != null ? sendToTelegram(telegramChatId, message) : missingActiveChannel("Telegram", order);
            case MAX -> maxChatId != null ? sendToMax(maxChatId, message) : missingActiveChannel("MAX", order);
            case UNKNOWN -> missingActiveChannel("неизвестный мессенджер", order);
        };
        return sentChannel;
    }

    private String missingActiveChannel(String channel, Order order) {
        log.warn("⚠️ Активный канал {} для компании {} не готов: нет подходящего chatId или ссылка не распознана",
                channel, companyTitle(order));
        return null;
    }

    private String sendToWhatsApp(
            Order order,
            String clientId,
            String groupId,
            String message
    ) {
        WhatsAppSendResult result;
        try {
            result = WhatsAppSendResult.parse(whatsAppService.sendMessageToGroup(clientId, groupId, message));
        } catch (Exception e) {
            log.warn("⚠️ Ошибка при отправке сообщения в WhatsApp-группу {}", groupId, e);
            return null;
        }

        if (result.isOk()) {
            whatsAppAuthAlertService.notifyRecovered(
                    clientId,
                    "моментальная отправка клиенту",
                    LocalDateTime.now().withNano(0),
                    order == null || order.getManager() == null ? List.of() : List.of(order.getManager())
            );
            return "WhatsApp";
        }

        log.warn("⚠️ Сообщение в WhatsApp-группу не прошло: code={}, error={}",
                result.code(), result.displayError());
        if (isWhatsAppAuthUnavailable(result.code(), result.displayError())) {
            notifyManagerAboutWhatsAppAuthIssue(order, clientId, result.code(), result.displayError());
        }
        return null;
    }

    private boolean isWhatsAppAuthUnavailable(String code, String readable) {
        String normalized = ((code == null ? "" : code) + " " + (readable == null ? "" : readable))
                .toLowerCase(Locale.ROOT);
        return normalized.contains("authenticated=false")
                || normalized.contains("\"authenticated\":false")
                || normalized.contains("\"authenticated\": false")
                || normalized.contains("\"state\":\"qr\"")
                || normalized.contains("\"state\": \"qr\"")
                || normalized.contains("\"hasqr\":true")
                || normalized.contains("\"hasqr\": true")
                || normalized.contains("scan it")
                || normalized.contains("не авториз");
    }

    private void notifyManagerAboutWhatsAppAuthIssue(Order order, String clientId, String code, String readable) {
        whatsAppAuthAlertService.notifyAuthIssue(
                clientId,
                companyTitle(order),
                "моментальная отправка клиенту",
                code,
                readable,
                LocalDateTime.now().withNano(0),
                null,
                order == null || order.getManager() == null ? List.of() : List.of(order.getManager())
        );
    }

    private String sendToTelegram(
            Long telegramChatId,
            String message
    ) {
        boolean sent;
        try {
            sent = telegramService.sendMessage(telegramChatId, message);
        } catch (Exception e) {
            log.warn("⚠️ Ошибка при отправке сообщения в Telegram-группу {}", telegramChatId, e);
            return null;
        }

        if (sent) {
            return "Telegram";
        }

        log.warn("⚠️ Сообщение в Telegram-группу {} не отправлено", telegramChatId);
        return null;
    }

    private String sendProgressToTelegram(
            Long telegramChatId,
            Long companyId,
            String message
    ) {
        boolean sent;
        try {
            sent = telegramService.sendPublicationProgressMessage(telegramChatId, message, companyId);
        } catch (Exception e) {
            log.warn("⚠️ Ошибка при отправке короткого отчёта в Telegram-группу {}", telegramChatId, e);
            return null;
        }

        if (sent) {
            return "Telegram";
        }

        log.warn("⚠️ Короткий отчёт в Telegram-группу {} не отправлен", telegramChatId);
        return null;
    }

    private String sendToMax(
            Long maxChatId,
            String message
    ) {
        boolean sent;
        try {
            sent = maxBotClient.sendMessageToChat(maxChatId, message);
        } catch (Exception e) {
            log.warn("⚠️ Ошибка при отправке сообщения в MAX-группу {}", maxChatId, e);
            return null;
        }

        if (sent) {
            return "MAX";
        }

        log.warn("⚠️ Сообщение в MAX-группу {} не отправлено", maxChatId);
        return null;
    }

    private String applySuccessStatus(String successStatus, Order order, String channel) {
        order.setStatus(orderStatusService.getOrderStatusByTitle(successStatus));
        log.info("✅ Сообщение клиенту отправлено через {}. Статус заказа успешно обновлён на: {}", channel, successStatus);
        return successStatus;
    }

    private String applyFallbackStatus(String title, Order order) {
        order.setStatus(orderStatusService.getOrderStatusByTitle(title));
        log.info("🔄 Статус заказа оставлен/установлен без клиентской отправки: {}", title);
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
        try {
            Long managerChatId = managerTelegramChatId(order);
            if (managerChatId == null || !hasDetails(order) || order == null || order.getCompany() == null) {
                return;
            }

            if (STATUS_TO_CHECK.equals(title)) {
                String url = "https://o-ogo.ru/orders/all_orders?status=В%20проверку";
                String text = companyTitle(order) + " готов - На проверку\n" + url;
                telegramService.sendMessage(managerChatId, text);
                log.info("📬 Уведомление менеджеру отправлено в Telegram: {} → В проверку", managerChatId);
            }

            if (STATUS_PUBLIC.equals(title)) {
                String url = "https://o-ogo.ru/orders/all_orders?status=Опубликовано";
                String text = companyTitle(order) + " Опубликован\n" + url;
                telegramService.sendMessage(managerChatId, text);
                log.info("📬 Уведомление менеджеру отправлено в Telegram: {} → Опубликовано", managerChatId);
            }
        } catch (Exception e) {
            log.warn("Fallback-уведомление менеджеру не отправлено. Статус заказа продолжит меняться, orderId={}",
                    order != null ? order.getId() : null, e);
        }
    }

    private Long managerTelegramChatId(Order order) {
        try {
            return order != null && order.getManager() != null && order.getManager().getUser() != null
                    ? order.getManager().getUser().getTelegramChatId()
                    : null;
        } catch (Exception e) {
            log.warn("Не удалось прочитать Telegram chatId менеджера для fallback-уведомления, orderId={}",
                    order != null ? order.getId() : null, e);
            return null;
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

    private ChatPlatform activeChatPlatform(Order order) {
        if (order == null || order.getCompany() == null) {
            return ChatPlatform.UNKNOWN;
        }

        String value = order.getCompany().getUrlChat();
        if (!hasText(value)) {
            return ChatPlatform.UNKNOWN;
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.matches("^(?:https?://)?chat\\.whatsapp\\.com/.+")) {
            return ChatPlatform.WHATSAPP;
        }
        if (normalized.matches("^(?:https?://)?(?:t\\.me|telegram\\.me|telegram\\.dog)/.+")
                || normalized.startsWith("tg://resolve?")) {
            return ChatPlatform.TELEGRAM;
        }
        if (normalized.matches("^(?:https?://)?(?:web\\.)?max\\.ru/.+")) {
            return ChatPlatform.MAX;
        }

        return ChatPlatform.UNKNOWN;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private enum ChatPlatform {
        WHATSAPP,
        TELEGRAM,
        MAX,
        UNKNOWN
    }
}
