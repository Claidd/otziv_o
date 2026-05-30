package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.maxbot.service.MaxBotClient;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.whatsapp.dto.WhatsAppSendResult;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class ClientChatMessageSender {

    private final WhatsAppService whatsAppService;
    private final TelegramService telegramService;
    private final MaxBotClient maxBotClient;

    public ClientMessageSendResult send(Company company, String clientId, String groupId, String message) {
        if (company == null) {
            return ClientMessageSendResult.failed("company_missing", "Компания не найдена");
        }
        if (!hasText(message)) {
            return ClientMessageSendResult.failed("message_empty", "Текст сообщения пустой");
        }

        ChatPlatform activePlatform = activeChatPlatform(company);
        log.info(
                "Client scheduled message send companyId={} company=\"{}\" platform={} whatsappClient={} whatsappGroup={} telegramGroup={} maxGroup={}",
                company.getId(),
                safeCompanyTitle(company),
                activePlatform,
                clientId,
                groupId,
                company.getTelegramGroupChatId(),
                company.getMaxGroupChatId()
        );

        return switch (activePlatform) {
            case WHATSAPP -> hasText(groupId)
                    ? sendToWhatsApp(clientId, groupId, message)
                    : missingActiveChannel("whatsapp_group_missing", "Для WhatsApp-группы не задан groupId");
            case TELEGRAM -> company.getTelegramGroupChatId() != null
                    ? sendToTelegram(company.getTelegramGroupChatId(), message)
                    : missingActiveChannel("telegram_group_missing", "Для Telegram-группы не задан chatId");
            case MAX -> company.getMaxGroupChatId() != null
                    ? sendToMax(company.getMaxGroupChatId(), message)
                    : missingActiveChannel("max_group_missing", "Для MAX-группы не задан chatId");
            case UNKNOWN -> missingActiveChannel("chat_platform_unknown", "Ссылка на чат не распознана или не указана");
        };
    }

    private ClientMessageSendResult sendToWhatsApp(String clientId, String groupId, String message) {
        if (!hasText(clientId)) {
            return ClientMessageSendResult.failed("whatsapp_client_missing", "Для менеджера не задан WhatsApp clientId");
        }

        WhatsAppSendResult result;
        try {
            result = WhatsAppSendResult.parse(whatsAppService.sendMessageToGroup(clientId, groupId, message));
        } catch (Exception e) {
            log.warn("Ошибка отправки клиентского сообщения в WhatsApp groupId={}", groupId, e);
            return ClientMessageSendResult.failed("whatsapp_exception", readableException(e));
        }

        if (result.isOk()) {
            return ClientMessageSendResult.sent("WhatsApp");
        }

        return ClientMessageSendResult.failed(
                result.code() == null || result.code().isBlank() ? "whatsapp_error" : result.code(),
                "WhatsApp не отправил сообщение: " + result.displayError()
        );
    }

    private ClientMessageSendResult sendToTelegram(Long telegramChatId, String message) {
        try {
            boolean sent = telegramService.sendMessage(telegramChatId, message);
            if (sent) {
                return ClientMessageSendResult.sent("Telegram");
            }
            return ClientMessageSendResult.failed("telegram_not_sent", "Telegram вернул отказ без подробностей");
        } catch (Exception e) {
            log.warn("Ошибка отправки клиентского сообщения в Telegram chatId={}", telegramChatId, e);
            return ClientMessageSendResult.failed("telegram_exception", readableException(e));
        }
    }

    private ClientMessageSendResult sendToMax(Long maxChatId, String message) {
        try {
            boolean sent = maxBotClient.sendMessageToChat(maxChatId, message);
            if (sent) {
                return ClientMessageSendResult.sent("MAX");
            }
            return ClientMessageSendResult.failed("max_not_sent", "MAX вернул отказ без подробностей");
        } catch (Exception e) {
            log.warn("Ошибка отправки клиентского сообщения в MAX chatId={}", maxChatId, e);
            return ClientMessageSendResult.failed("max_exception", readableException(e));
        }
    }

    private ClientMessageSendResult missingActiveChannel(String code, String message) {
        return ClientMessageSendResult.failed(code, message);
    }

    private ChatPlatform activeChatPlatform(Company company) {
        String value = company.getUrlChat();
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

    private String readableException(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private String safeCompanyTitle(Company company) {
        return company.getTitle() == null || company.getTitle().isBlank() ? "Компания" : company.getTitle();
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
