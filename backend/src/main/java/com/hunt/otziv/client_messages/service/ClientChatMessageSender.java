package com.hunt.otziv.client_messages.service;

import com.hunt.otziv.c_companies.model.Company;
import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import com.hunt.otziv.client_messages.dto.ClientMessageSendResult;
import com.hunt.otziv.client_messages.dto.TelegramTransferCopyButton;
import com.hunt.otziv.maxbot.service.MaxBotClient;
import com.hunt.otziv.t_telegrambot.service.TelegramService;
import com.hunt.otziv.whatsapp.dto.WhatsAppSendResult;
import com.hunt.otziv.whatsapp.service.service.WhatsAppService;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

@Service
@Slf4j
@RequiredArgsConstructor
public class ClientChatMessageSender implements com.hunt.otziv.client_messages.api.ClientMessageDelivery {

    // Only validation/admission failures prove that dispatch never started.
    // Unknown codes, timeouts, ledger conflicts and provider exceptions retain the operation.
    private static final Set<String> KNOWN_UNSENT_CODES = Set.of(
            "company_missing", "message_empty", "chat_platform_missing", "chat_platform_unknown",
            "whatsapp_client_missing", "whatsapp_group_missing", "telegram_group_missing", "max_group_missing",
            "gateway_not_ready", "not_ready", "whatsapp_not_ready", "gateway_busy", "operation_ledger_full",
            "draining", "unauthorized", "invalid_request",
            "payload_too_large", "invalid_json", "invalid_operation_id", "invalid_operation_envelope", "max_not_configured"
    );

    public static boolean isKnownUnsent(ClientMessageSendResult result) {
        return result != null && !result.sent() && result.errorCode() != null
                && KNOWN_UNSENT_CODES.contains(result.errorCode().trim().toLowerCase(Locale.ROOT));
    }

    private final WhatsAppService whatsAppService;
    private final TelegramService telegramService;
    private final MaxBotClient maxBotClient;
    private final com.hunt.otziv.whatsapp.api.WhatsAppBusinessOperations businessOperations;
    private final ClientMessageOperationFence operationFence;
    private final PublicationProgressPreferenceService progressPreferences;

    /** Receipt-only recovery. Neither absence nor UNKNOWN authorizes a provider call. */
    public ClientMessageSendResult recordedOutcome(String operationId) {
        try {
            var local = operationFence.lookup(operationId);
            if (local.isPresent()) return ClientMessageOperationFence.result(local.orElseThrow());
            var frozen = businessOperations.findFrozen(operationId);
            if (frozen.isEmpty()) return ClientMessageOperationFence.unknown();
            var envelope = frozen.orElseThrow();
            if (!"send-group".equals(envelope.kind())) return ClientMessageOperationFence.unknown();
            var receipt = whatsAppService.getOperationStatus(envelope.clientId(), operationId);
            String expectedHash = com.hunt.otziv.whatsapp.dto.WhatsAppOperationEnvelope.groupHash(
                    envelope.clientId(), envelope.destination(), envelope.message());
            if (receipt != null && operationId.equals(receipt.operationId()) && "SUCCEEDED".equals(receipt.state())
                    && expectedHash.equals(receipt.envelopeHash()) && receipt.messageId() != null && !receipt.messageId().isBlank()) {
                return ClientMessageSendResult.sent("WhatsApp", receipt.messageId());
            }
        } catch (RuntimeException unavailable) {
            log.warn("Scheduled delivery receipt unavailable: operationId={}", operationId);
        }
        return ClientMessageOperationFence.unknown();
    }

    @Override public ClientMessageSendResult deliverWithOperationId(com.hunt.otziv.client_messages.api.ClientMessageDelivery.Target target,
            String clientId,String groupId,String message,TelegramTransferCopyButton copy,String operationId) {
        return sendWithOperationId(companySnapshot(target),clientId,groupId,message,copy,operationId);
    }
    @Override public ClientMessageSendResult deliverPublicationProgressWithOperationId(com.hunt.otziv.client_messages.api.ClientMessageDelivery.Target target,
            String clientId,String groupId,String message,boolean controls,String operationId) {
        return sendPublicationProgressWithOperationId(companySnapshot(target),clientId,groupId,message,controls,operationId);
    }
    private Company companySnapshot(com.hunt.otziv.client_messages.api.ClientMessageDelivery.Target target) {
        if(target==null)return null;
        Company company=new Company();company.setId(target.companyId());company.setTitle(target.title());company.setUrlChat(target.urlChat());
        company.setTelegramGroupChatId(target.telegramChatId());company.setMaxGroupChatId(target.maxChatId());return company;
    }

    public ClientMessageSendResult send(Company company, String clientId, String groupId, String message) {
        return send(company, clientId, groupId, message, null);
    }

    public ClientMessageSendResult send(
            Company company,
            String clientId,
            String groupId,
            String message,
            TelegramTransferCopyButton telegramCopyButton
    ) {
        return sendWithOperationId(company, clientId, groupId, message, telegramCopyButton, null);
    }

    public ClientMessageSendResult sendWithOperationId(
            Company company, String clientId, String groupId, String message,
            TelegramTransferCopyButton telegramCopyButton, String operationId
    ) {
        if (company == null) {
            return ClientMessageSendResult.failed("company_missing", "Компания не найдена");
        }
        if (!hasText(message)) {
            return ClientMessageSendResult.failed("message_empty", "Текст сообщения пустой");
        }

        ChatPlatform activePlatform = activeChatPlatform(company);
        if (operationId != null) {
            try {
                var nonIdempotentAttempt = operationFence.lookup(operationId);
                if (nonIdempotentAttempt.isPresent()) {
                    return sendWithPersistentFence(ChatPlatform.valueOf(nonIdempotentAttempt.orElseThrow().platform()),
                            company, clientId, groupId, message, telegramCopyButton, operationId);
                }
                var existing = businessOperations.findFrozen(operationId);
                if (existing.isPresent()) {
                    var frozen = existing.orElseThrow();
                    if (!"send-group".equals(frozen.kind())) {
                        return ClientMessageSendResult.failed("operation_payload_conflict", "Тип сохранённой операции не совпадает");
                    }
                    // A company may change its active chat while a previous WhatsApp attempt is unresolved.
                    activePlatform = ChatPlatform.WHATSAPP;
                    clientId = frozen.clientId();
                    groupId = frozen.destination();
                    message = frozen.message();
                    // Never attach newly selected bank details to a replay of an older invoice.
                    telegramCopyButton = businessOperations.findFrozen(operationId + ":copy")
                            .flatMap(copy -> TelegramTransferCopyButton.fromFrozenTransferNumber(copy.message()))
                            .orElse(null);
                }
            } catch (RuntimeException unavailable) {
                return ClientMessageSendResult.failed("operation_unknown", "Не удалось проверить сохранённую операцию");
            }
        }
        if (operationId != null && (activePlatform == ChatPlatform.TELEGRAM || activePlatform == ChatPlatform.MAX)) {
            return sendWithPersistentFence(activePlatform, company, clientId, groupId, message, telegramCopyButton, operationId);
        }
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

        ClientMessageSendResult primaryResult = switch (activePlatform) {
            case WHATSAPP -> hasText(groupId)
                    ? sendToWhatsApp(clientId, groupId, message, operationId)
                    : missingActiveChannel("whatsapp_group_missing", "Для WhatsApp-группы не задан groupId");
            case TELEGRAM -> company.getTelegramGroupChatId() != null
                    ? operationId == null
                        ? sendToTelegram(company.getTelegramGroupChatId(), message, telegramCopyButton)
                        : sendToTelegramOnce(company.getTelegramGroupChatId(), message, telegramCopyButton)
                    : missingActiveChannel("telegram_group_missing", "Для Telegram-группы не задан chatId");
            case MAX -> company.getMaxGroupChatId() != null
                    ? operationId == null ? sendToMax(company.getMaxGroupChatId(), message)
                        : sendToMaxOnce(company.getMaxGroupChatId(), message)
                    : missingActiveChannel("max_group_missing", "Для MAX-группы не задан chatId");
            case UNKNOWN -> missingActiveChannel("chat_platform_unknown", "Ссылка на чат не распознана или не указана");
        };
        if (primaryResult.sent()) {
            sendPlainChannelCopyMessageBestEffort(
                    activePlatform,
                    company,
                    clientId,
                    groupId,
                    telegramCopyButton,
                    operationId
            );
        }
        return primaryResult;
    }

    private ClientMessageSendResult sendWithPersistentFence(ChatPlatform platform, Company company,
            String clientId, String groupId, String message, TelegramTransferCopyButton copyButton, String operationId) {
        Long destination = platform == ChatPlatform.TELEGRAM ? company.getTelegramGroupChatId() : company.getMaxGroupChatId();
        try {
            String envelope = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(new String[]{
                    message, copyButton == null ? null : copyButton.text(), copyButton == null ? null : copyButton.copyText()});
            return operationFence.execute(operationId, platform.name(), destination == null ? null : destination.toString(), envelope, () -> {
                ClientMessageSendResult result = platform == ChatPlatform.TELEGRAM
                        ? sendToTelegramOnce(destination, message, copyButton) : sendToMaxOnce(destination, message);
                if (result.sent()) sendPlainChannelCopyMessageBestEffort(platform, company, clientId, groupId, copyButton, operationId);
                return result;
            });
        } catch (Exception unavailable) {
            return ClientMessageSendResult.failed("operation_unknown", "Не удалось подтвердить операцию; требуется сверка");
        }
    }

    public ClientMessageSendResult sendPublicationProgressWithOperationId(Company company,String clientId,String groupId,
            String message,boolean includePreferenceControls,String operationId) {
        if(company==null)return ClientMessageSendResult.failed("company_missing","Компания не найдена");
        if(!hasText(message))return ClientMessageSendResult.failed("message_empty","Текст сообщения пустой");
        if(operationId==null)return ClientMessageSendResult.failed("invalid_operation_id","Не задана операция публикации");
        try {
            var previous=operationFence.lookup(operationId);
            var frozen=businessOperations.findFrozen(operationId);
            ChatPlatform platform=previous.isPresent()?ChatPlatform.valueOf(previous.orElseThrow().platform()):
                    frozen.isPresent()?ChatPlatform.WHATSAPP:activeChatPlatform(company);
            if(platform!=ChatPlatform.TELEGRAM) {
                String plain=includePreferenceControls?progressPreferences.appendPlainOptOutHint(message):message;
                return sendWithOperationId(company,clientId,groupId,plain,null,operationId);
            }
            Long destination=company.getTelegramGroupChatId();
            String text=includePreferenceControls&&company.getId()!=null?progressPreferences.appendTelegramOptOutHint(message):message;
            String callback=includePreferenceControls&&company.getId()!=null?progressPreferences.disableCallbackData(company.getId()):null;
            String canonical=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(new String[]{text,callback,
                    callback==null?null:PublicationProgressPreferenceService.DISABLE_BUTTON_TEXT});
            return operationFence.execute(operationId,"TELEGRAM",destination==null?null:destination.toString(),canonical,()->{
                if(destination==null)return ClientMessageSendResult.failed("telegram_group_missing","Для Telegram-группы не задан chatId");
                InlineKeyboardButton button=new InlineKeyboardButton();
                button.setText(PublicationProgressPreferenceService.DISABLE_BUTTON_TEXT);button.setCallbackData(callback);
                List<List<InlineKeyboardButton>> keyboard=callback==null?List.of():List.of(List.of(button));
                var receipt=telegramService.sendMessageOnceWithInlineKeyboardMessageId(destination,text,null,keyboard);
                return receipt.filter(id->id>0).map(id->ClientMessageSendResult.sent("Telegram",id.toString()))
                        .orElseGet(()->ClientMessageSendResult.failed("operation_unknown","Публикация не подтверждена; требуется сверка"));
            });
        } catch(RuntimeException|com.fasterxml.jackson.core.JsonProcessingException unavailable) {
            return ClientMessageSendResult.failed("operation_unknown","Не удалось подтвердить отправку отчета; требуется сверка");
        }
    }

    public ClientMessageSendResult sendToPlatform(
            ClientChatPlatform platform,
            Company company,
            String clientId,
            String groupId,
            String chatId,
            String message
    ) {
        return sendToPlatformWithOperationId(platform, company, clientId, groupId, chatId, message, null);
    }

    public ClientMessageSendResult sendToPlatformWithOperationId(
            ClientChatPlatform platform, Company company, String clientId, String groupId, String chatId,
            String message, String operationId
    ) {
        if (platform == null) {
            return ClientMessageSendResult.failed("chat_platform_missing", "Канал сообщения не определен");
        }
        if (company == null) {
            return ClientMessageSendResult.failed("company_missing", "Компания не найдена");
        }
        if (!hasText(message)) {
            return ClientMessageSendResult.failed("message_empty", "Текст сообщения пустой");
        }

        return switch (platform) {
            case WHATSAPP -> sendToWhatsApp(clientId, hasText(groupId) ? groupId : chatId, message, operationId);
            case TELEGRAM -> parseLong(hasText(chatId) ? chatId : String.valueOf(company.getTelegramGroupChatId()))
                    .map(id -> operationId == null ? sendToTelegram(id, message) : sendToTelegramOnce(id, message, null))
                    .orElseGet(() -> missingActiveChannel("telegram_group_missing", "Для Telegram-группы не задан chatId"));
            case MAX -> parseLong(hasText(chatId) ? chatId : String.valueOf(company.getMaxGroupChatId()))
                    .map(id -> operationId == null ? sendToMax(id, message) : sendToMaxOnce(id, message))
                    .orElseGet(() -> missingActiveChannel("max_group_missing", "Для MAX-группы не задан chatId"));
        };
    }

    private ClientMessageSendResult sendToWhatsApp(String clientId, String groupId, String message) {
        return sendToWhatsApp(clientId, groupId, message, null);
    }

    private ClientMessageSendResult sendToWhatsApp(String clientId, String groupId, String message, String operationId) {
        if (!hasText(clientId)) {
            return ClientMessageSendResult.failed("whatsapp_client_missing", "Для менеджера не задан WhatsApp clientId");
        }

        WhatsAppSendResult result;
        try {
            if (operationId != null) {
                var frozen = businessOperations.freezeForDispatch(operationId, clientId, "send-group", groupId, message);
                clientId = frozen.clientId();
                groupId = frozen.destination();
                message = frozen.message();
            }
            result = WhatsAppSendResult.parse(operationId == null
                    ? whatsAppService.sendMessageToGroup(clientId, groupId, message)
                    : whatsAppService.sendMessageToGroup(clientId, groupId, message, operationId));
        } catch (Exception e) {
            log.warn("Ошибка отправки клиентского сообщения в WhatsApp groupId={}", groupId, e);
            return ClientMessageSendResult.failed("operation_unknown", "Результат отправки неизвестен; требуется проверка операции");
        }

        if (result.isOk()) {
            String messageId = operationId == null ? null : confirmedOperationReceipt(result.rawBody(), operationId);
            if (operationId != null && messageId == null) {
                return ClientMessageSendResult.failed("operation_unknown", "Подтверждение не соответствует операции отправки");
            }
            return ClientMessageSendResult.sent("WhatsApp", messageId);
        }

        if (!result.hasStatus("error")) {
            return ClientMessageSendResult.failed(
                    result.hasStatus("pending") ? "operation_pending" : "operation_unknown",
                    "Результат отправки не подтвержден; требуется проверка операции"
            );
        }

        return ClientMessageSendResult.failed(
                result.code() == null || result.code().isBlank() ? "whatsapp_error" : result.code(),
                "WhatsApp не отправил сообщение: " + result.displayError()
        );
    }

    private String confirmedOperationReceipt(String body, String operationId) {
        try {
            var receipt = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            var messageId = receipt.path("messageId");
            return operationId.equals(receipt.path("operationId").asText())
                    && "SUCCEEDED".equals(receipt.path("state").asText())
                    && messageId.isTextual() && !messageId.textValue().isBlank()
                    ? messageId.textValue() : null;
        } catch (Exception invalid) {
            return null;
        }
    }

    private ClientMessageSendResult sendToTelegram(Long telegramChatId, String message) {
        return sendToTelegram(telegramChatId, message, null);
    }

    /** A durable local intent must not inherit the legacy sender's timeout retries or chunk loop. */
    private ClientMessageSendResult sendToTelegramOnce(Long chatId, String message, TelegramTransferCopyButton copyButton) {
        if (message.length() > 4096) {
            return ClientMessageSendResult.failed("payload_too_large", "Для одной Telegram-операции сократите текст до 4096 символов");
        }
        List<List<InlineKeyboardButton>> keyboard = copyButton == null ? List.of()
                : List.of(List.of(new CopyTextOperationButton(copyButton.text(), copyButton.copyText())));
        try {
            Optional<Integer> messageId = telegramService.sendMessageOnceWithInlineKeyboardMessageId(chatId, message, null, keyboard);
            if (messageId.filter(id -> id > 0).isPresent()) return ClientMessageSendResult.sent("Telegram", messageId.get().toString());
        } catch (RuntimeException unconfirmed) {
            log.warn("Telegram operation has no confirmed result ({})", unconfirmed.getClass().getSimpleName());
        }
        return ClientMessageSendResult.failed("operation_unknown", "Результат отправки Telegram неизвестен; проверьте сообщение перед повтором");
    }

    /** Bot API copy_text shape for the bundled Telegram library which predates this button. */
    private static final class CopyTextOperationButton extends InlineKeyboardButton {
        @com.fasterxml.jackson.annotation.JsonProperty("copy_text")
        private final Map<String, String> copyText;
        private CopyTextOperationButton(String text, String value) {
            setText(text);
            this.copyText = Map.of("text", value);
        }
    }

    private ClientMessageSendResult sendToTelegram(
            Long telegramChatId,
            String message,
            TelegramTransferCopyButton copyButton
    ) {
        try {
            boolean sent = copyButton == null
                    ? telegramService.sendMessage(telegramChatId, message)
                    : telegramService.sendMessageWithCopyTextButton(
                            telegramChatId,
                            message,
                            copyButton.text(),
                            copyButton.copyText()
                    );
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

    private ClientMessageSendResult sendToMaxOnce(Long maxChatId, String message) {
        try {
            MaxBotClient.SendOnceResult result = maxBotClient.sendMessageToChatOnce(maxChatId, message);
            if (result != null && result.confirmed()) return ClientMessageSendResult.sent("MAX", result.messageId());
            if (result != null && result.errorCode() != null
                    && Set.of("payload_too_large", "invalid_request", "max_not_configured").contains(result.errorCode())) {
                return ClientMessageSendResult.failed(result.errorCode(), "MAX не начал отправку: проверьте текст и настройки канала");
            }
        } catch (RuntimeException unconfirmed) {
            log.warn("MAX operation has no confirmed result ({})", unconfirmed.getClass().getSimpleName());
        }
        return ClientMessageSendResult.failed("operation_unknown", "Результат отправки MAX неизвестен; проверьте сообщение перед повтором");
    }

    private ClientMessageSendResult missingActiveChannel(String code, String message) {
        return ClientMessageSendResult.failed(code, message);
    }

    private void sendPlainChannelCopyMessageBestEffort(
            ChatPlatform platform,
            Company company,
            String clientId,
            String groupId,
            TelegramTransferCopyButton copyButton,
            String operationId
    ) {
        if (copyButton == null || !hasText(copyButton.copyText())) {
            return;
        }

        ClientMessageSendResult copyResult = switch (platform) {
            case WHATSAPP -> sendToWhatsApp(clientId, groupId, copyButton.copyText(),
                    operationId == null ? null : operationId + ":copy");
            case MAX -> operationId == null ? sendToMax(company.getMaxGroupChatId(), copyButton.copyText())
                    : sendToMaxOnce(company.getMaxGroupChatId(), copyButton.copyText());
            case TELEGRAM, UNKNOWN -> null;
        };
        if (copyResult != null && !copyResult.sent()) {
            log.warn(
                    "Отдельное сообщение с платежным реквизитом не отправлено: companyId={}, platform={}, code={}",
                    company.getId(),
                    platform,
                    copyResult.errorCode()
            );
        }
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

    private Optional<Long> parseLong(String value) {
        if (!hasText(value) || "null".equalsIgnoreCase(value.trim())) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(value.trim()));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private enum ChatPlatform {
        WHATSAPP,
        TELEGRAM,
        MAX,
        UNKNOWN
    }
}
