package com.hunt.otziv.t_telegrambot.service;

import com.hunt.otziv.admin.services.PersonalService;
import com.hunt.otziv.client_chat_control.dto.ClientChatMessageCommand;
import com.hunt.otziv.client_chat_control.model.ClientChatDirection;
import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import com.hunt.otziv.client_chat_control.service.ClientChatMessageTrackerService;
import com.hunt.otziv.client_messages.service.PublicationProgressPreferenceService;
import com.hunt.otziv.manager_control.service.ManagerControlWorkerTaskTelegramCallbackService;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.worker_activity.service.WorkerRiskTelegramCallbackService;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.NoHttpResponseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.ChatMemberUpdated;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ForceReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

@Component
@Slf4j
public class TelegramService extends TelegramLongPollingBot {

    private static final Pattern BOT_TOKEN_PATTERN = Pattern.compile("\\d{6,}:[A-Za-z0-9_-]{20,}");
    private static final int MAX_TELEGRAM_MESSAGE_LENGTH = 3900;
    private static final int SEND_ATTEMPTS = 3;
    private static final long SEND_RETRY_DELAY_MS = 1_500L;

    private final String botUsername;
    private final boolean sendingEnabled;
    private final List<Long> adminChatIds;
    private final ObjectProvider<PersonalService> personalServiceProvider;
    private final UserService userService;
    private final TelegramGroupLinkService telegramGroupLinkService;
    private final PublicationProgressPreferenceService publicationProgressPreferenceService;
    private final ObjectProvider<WorkerRiskTelegramCallbackService> workerRiskTelegramCallbackServiceProvider;
    private final ObjectProvider<ManagerControlWorkerTaskTelegramCallbackService> managerControlWorkerTaskTelegramCallbackServiceProvider;
    private final TelegramChatMigrationService telegramChatMigrationService;
    private final ClientChatMessageTrackerService clientChatMessageTrackerService;

    public TelegramService(
            DefaultBotOptions botOptions,
            @Value("${telegram.bot.token:}") String botToken,
            @Value("${telegram.bot.username:}") String botUsername,
            @Value("${telegram.bot.sending-enabled:true}") boolean sendingEnabled,
            @Value("${telegram.admin.chat-ids:}") String adminChatIds,
            ObjectProvider<PersonalService> personalServiceProvider,
            UserService userService,
            TelegramGroupLinkService telegramGroupLinkService,
            PublicationProgressPreferenceService publicationProgressPreferenceService,
            ObjectProvider<WorkerRiskTelegramCallbackService> workerRiskTelegramCallbackServiceProvider
    ) {
        this(
                botOptions,
                botToken,
                botUsername,
                sendingEnabled,
                adminChatIds,
                personalServiceProvider,
                userService,
                telegramGroupLinkService,
                publicationProgressPreferenceService,
                workerRiskTelegramCallbackServiceProvider,
                null,
                null,
                null
        );
    }

    @Autowired
    public TelegramService(
            DefaultBotOptions botOptions,
            @Value("${telegram.bot.token:}") String botToken,
            @Value("${telegram.bot.username:}") String botUsername,
            @Value("${telegram.bot.sending-enabled:true}") boolean sendingEnabled,
            @Value("${telegram.admin.chat-ids:}") String adminChatIds,
            ObjectProvider<PersonalService> personalServiceProvider,
            UserService userService,
            TelegramGroupLinkService telegramGroupLinkService,
            PublicationProgressPreferenceService publicationProgressPreferenceService,
            ObjectProvider<WorkerRiskTelegramCallbackService> workerRiskTelegramCallbackServiceProvider,
            ObjectProvider<ManagerControlWorkerTaskTelegramCallbackService> managerControlWorkerTaskTelegramCallbackServiceProvider,
            TelegramChatMigrationService telegramChatMigrationService,
            ClientChatMessageTrackerService clientChatMessageTrackerService
    ) {
        super(botOptions, botToken);
        this.botUsername = botUsername;
        this.sendingEnabled = sendingEnabled;
        this.adminChatIds = parseAdminChatIds(adminChatIds);
        this.personalServiceProvider = personalServiceProvider;
        this.userService = userService;
        this.telegramGroupLinkService = telegramGroupLinkService;
        this.publicationProgressPreferenceService = publicationProgressPreferenceService;
        this.workerRiskTelegramCallbackServiceProvider = workerRiskTelegramCallbackServiceProvider;
        this.managerControlWorkerTaskTelegramCallbackServiceProvider = managerControlWorkerTaskTelegramCallbackServiceProvider;
        this.telegramChatMigrationService = telegramChatMigrationService;
        this.clientChatMessageTrackerService = clientChatMessageTrackerService;
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public void onUpdateReceived(Update update) {
        long startTime = System.nanoTime();
        if (update != null && update.hasMyChatMember()) {
            handleMyChatMemberUpdate(update);
            return;
        }
        if (update != null && update.hasCallbackQuery()) {
            handleCallbackQuery(update.getCallbackQuery());
            return;
        }
        if (handleChatMigrationUpdate(update)) {
            return;
        }
        if (update == null || !update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        String messageText = update.getMessage().getText();
        long chatId = update.getMessage().getChatId();

        Optional<String> groupLinkResponse = telegramGroupLinkService.handleGroupStartCommand(chatId, messageText);
        if (groupLinkResponse.isPresent()) {
            sendMessage(chatId, groupLinkResponse.get());
            return;
        }

        if (isChatIdCommand(messageText)) {
            sendMessage(chatId, "chatId: `" + chatId + "`", "Markdown");
            return;
        }

        if (!isPrivateChat(update)) {
            WorkerRiskTelegramCallbackService workerRiskTelegramCallbackService =
                    workerRiskTelegramCallbackServiceProvider == null ? null : workerRiskTelegramCallbackServiceProvider.getIfAvailable();
            Long actorTelegramId = update.getMessage().getFrom() == null ? null : update.getMessage().getFrom().getId();
            if (workerRiskTelegramCallbackService != null
                    && workerRiskTelegramCallbackService.handleWorkerGroupTextMessage(chatId, actorTelegramId, messageText)) {
                return;
            }

            ManagerControlWorkerTaskTelegramCallbackService managerControlWorkerTaskTelegramCallbackService =
                    managerControlWorkerTaskTelegramCallbackServiceProvider == null
                            ? null
                            : managerControlWorkerTaskTelegramCallbackServiceProvider.getIfAvailable();
            if (managerControlWorkerTaskTelegramCallbackService != null
                    && managerControlWorkerTaskTelegramCallbackService.handleWorkerGroupTextMessage(chatId, actorTelegramId, messageText)) {
                return;
            }

            Optional<PublicationProgressPreferenceService.PreferenceUpdate> preferenceUpdate =
                    handlePublicationPreferenceCommand(chatId, messageText);
            if (preferenceUpdate.isPresent()) {
                sendPreferenceResponse(chatId, preferenceUpdate.get());
                return;
            }
            trackTelegramGroupMessage(update);
            return;
        }

        User user = authUserInTelegramBot(chatId, messageText);
        if (user == null) {
            return;
        }

        WorkerRiskTelegramCallbackService workerRiskTelegramCallbackService =
                workerRiskTelegramCallbackServiceProvider == null ? null : workerRiskTelegramCallbackServiceProvider.getIfAvailable();
        if (workerRiskTelegramCallbackService != null
                && workerRiskTelegramCallbackService.handleWorkerTextMessage(chatId, user, messageText)) {
            return;
        }

        Long userId = user.getId();
        String role = user.getRoles().stream()
                .map(Role::getName)
                .findFirst()
                .orElse("Без роли");

        PersonalService personalService = personalServiceProvider.getObject();

        switch (messageText) {
            case "1":
                if ("ROLE_ADMIN".equals(role)) {
                    sendMessage(chatId, personalService.displayResult(personalService.getPersonalsAndCountToMap()), "HTML");
                } else if ("ROLE_OWNER".equals(role)) {
                    sendMessage(chatId, personalService.displayResult(personalService.getPersonalsAndCountToMapToOwner(userId)), "HTML");
                } else if ("ROLE_MANAGER".equals(role)) {
                    sendMessage(chatId, personalService.displayResultToManager(personalService.getPersonalsAndCountToMapToManager(userId)), "HTML");
                } else if ("ROLE_WORKER".equals(role)) {
                    sendMessage(chatId, personalService.displayResultToWorker(personalService.getPersonalsAndCountToMapToWorker(userId)), "HTML");
                } else {
                    sendMessage(chatId, "У вас нет доступа", "Markdown");
                }
                break;
            case "2":
                if ("ROLE_ADMIN".equals(role) || "ROLE_OWNER".equals(role)) {
                    sendMessage(chatId, personalService.displayResult(personalService.getPersonalsAndCountToMap()), "HTML");
                } else {
                    sendMessage(chatId, "У вас нет доступа", "Markdown");
                }
                break;
            case "3":
                sendMessage(chatId, "Выручка за месяц mia:\nНовых компаний:", "Markdown");
                break;
            default:
                sendMessage(chatId, "Выберите команду: 1 - отчёт по роли, 2 - общий отчёт", "Markdown");
                break;
        }

        checkTimeMethod("Время выполнения запроса для Telegram: ", startTime);
    }

    private void handleCallbackQuery(CallbackQuery callbackQuery) {
        log.info("Telegram callback received data='{}' from={} chat={}",
                callbackQuery == null ? null : callbackQuery.getData(),
                callbackQuery == null || callbackQuery.getFrom() == null ? null : callbackQuery.getFrom().getId(),
                callbackQuery == null || callbackQuery.getMessage() == null ? null : callbackQuery.getMessage().getChatId());

        WorkerRiskTelegramCallbackService workerRiskTelegramCallbackService =
                workerRiskTelegramCallbackServiceProvider == null ? null : workerRiskTelegramCallbackServiceProvider.getIfAvailable();
        if (workerRiskTelegramCallbackService != null) {
            Optional<String> workerRiskAnswer = workerRiskTelegramCallbackService.handle(callbackQuery);
            if (workerRiskAnswer.isPresent()) {
                log.info("Worker risk Telegram callback handled answer='{}'", workerRiskAnswer.get());
                answerCallback(callbackQuery.getId(), workerRiskAnswer.get());
                return;
            }
        }

        ManagerControlWorkerTaskTelegramCallbackService managerControlWorkerTaskTelegramCallbackService =
                managerControlWorkerTaskTelegramCallbackServiceProvider == null
                        ? null
                        : managerControlWorkerTaskTelegramCallbackServiceProvider.getIfAvailable();
        if (managerControlWorkerTaskTelegramCallbackService != null) {
            Optional<String> managerControlAnswer = managerControlWorkerTaskTelegramCallbackService.handle(callbackQuery);
            if (managerControlAnswer.isPresent()) {
                log.info("Manager control worker task Telegram callback handled answer='{}'", managerControlAnswer.get());
                answerCallback(callbackQuery.getId(), managerControlAnswer.get());
                return;
            }
        }

        if (callbackQuery == null || publicationProgressPreferenceService == null) {
            return;
        }

        Optional<PublicationProgressPreferenceService.PreferenceUpdate> update =
                publicationProgressPreferenceService.handleCallback(callbackQuery.getData());
        if (update.isEmpty()) {
            answerCallback(callbackQuery.getId(), "Команда не распознана");
            return;
        }

        Long chatId = callbackQuery.getMessage() != null ? callbackQuery.getMessage().getChatId() : null;
        if (chatId != null) {
            sendPreferenceResponse(chatId, update.get());
        }
        answerCallback(callbackQuery.getId(), update.get().enabled() ? "Оповещения включены" : "Оповещения отключены");
    }

    private Optional<PublicationProgressPreferenceService.PreferenceUpdate> handlePublicationPreferenceCommand(
            long chatId,
            String messageText
    ) {
        if (publicationProgressPreferenceService == null) {
            return Optional.empty();
        }
        Optional<PublicationProgressPreferenceService.PreferenceUpdate> update =
                publicationProgressPreferenceService.handleTelegramCommand(chatId, messageText);
        return update == null ? Optional.empty() : update;
    }

    private void trackTelegramGroupMessage(Update update) {
        if (clientChatMessageTrackerService == null || update == null || !update.hasMessage()) {
            return;
        }
        try {
            var message = update.getMessage();
            var chat = message.getChat();
            var from = message.getFrom();
            clientChatMessageTrackerService.track(new ClientChatMessageCommand(
                    ClientChatPlatform.TELEGRAM,
                    ClientChatDirection.INCOMING,
                    String.valueOf(message.getChatId()),
                    chat == null ? null : chat.getTitle(),
                    message.getMessageId() == null ? null : String.valueOf(message.getMessageId()),
                    from == null || from.getId() == null ? null : String.valueOf(from.getId()),
                    telegramSenderName(from),
                    message.getText(),
                    telegramMessageTime(message.getDate())
            ));
        } catch (Exception e) {
            log.warn("Telegram group message tracking failed chatId={}", update.getMessage().getChatId(), e);
        }
    }

    private static String telegramSenderName(org.telegram.telegrambots.meta.api.objects.User from) {
        if (from == null) {
            return null;
        }
        if (hasText(from.getUserName())) {
            return "@" + from.getUserName();
        }
        String name = ((from.getFirstName() == null ? "" : from.getFirstName()) + " "
                + (from.getLastName() == null ? "" : from.getLastName())).trim();
        return name.isBlank() ? null : name;
    }

    private static LocalDateTime telegramMessageTime(Integer unixSeconds) {
        if (unixSeconds == null) {
            return LocalDateTime.now();
        }
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(unixSeconds), ZoneId.systemDefault());
    }

    private void sendPreferenceResponse(long chatId, PublicationProgressPreferenceService.PreferenceUpdate update) {
        if (update.enabled() || update.companyId() == null || publicationProgressPreferenceService == null) {
            sendMessage(chatId, update.message());
            return;
        }
        sendMessageWithInlineButton(
                chatId,
                update.message(),
                PublicationProgressPreferenceService.ENABLE_BUTTON_TEXT,
                publicationProgressPreferenceService.enableCallbackData(update.companyId())
        );
    }

    private void handleMyChatMemberUpdate(Update update) {
        if (telegramGroupLinkService == null) {
            return;
        }

        ChatMemberUpdated memberUpdate = update.getMyChatMember();
        if (!isBotAddedToChat(memberUpdate) || memberUpdate.getChat() == null || memberUpdate.getChat().getId() == null) {
            return;
        }

        long chatId = memberUpdate.getChat().getId();
        Optional<String> response = telegramGroupLinkService.handleBotAddedToGroup(
                chatId,
                memberUpdate.getChat().getUserName(),
                memberUpdate.getChat().getTitle()
        );
        response.ifPresent(message -> sendMessage(chatId, message));
    }

    private boolean handleChatMigrationUpdate(Update update) {
        if (update == null || !update.hasMessage() || telegramChatMigrationService == null) {
            return false;
        }

        Long migrateToChatId = update.getMessage().getMigrateToChatId();
        if (migrateToChatId != null) {
            telegramChatMigrationService.migrateChatId(update.getMessage().getChatId(), migrateToChatId);
            return true;
        }

        Long migrateFromChatId = update.getMessage().getMigrateFromChatId();
        if (migrateFromChatId != null) {
            telegramChatMigrationService.migrateChatId(migrateFromChatId, update.getMessage().getChatId());
            return true;
        }

        return false;
    }

    private boolean isBotAddedToChat(ChatMemberUpdated memberUpdate) {
        if (memberUpdate == null) {
            return false;
        }

        String newStatus = statusOf(memberUpdate.getNewChatMember());
        String oldStatus = statusOf(memberUpdate.getOldChatMember());
        return isActiveBotStatus(newStatus) && !isActiveBotStatus(oldStatus);
    }

    private boolean isActiveBotStatus(String status) {
        return "member".equals(status) || "administrator".equals(status) || "creator".equals(status);
    }

    private String statusOf(ChatMember chatMember) {
        return chatMember == null ? "" : chatMember.getStatus();
    }

    public boolean sendMessage(long chatId, String text) {
        return sendMessage(chatId, text, null);
    }

    public boolean sendPublicationProgressMessage(long chatId, String text, Long companyId) {
        if (publicationProgressPreferenceService == null || companyId == null) {
            return sendMessage(chatId, text);
        }
        return sendMessageWithInlineButton(
                chatId,
                publicationProgressPreferenceService.appendTelegramOptOutHint(text),
                PublicationProgressPreferenceService.DISABLE_BUTTON_TEXT,
                publicationProgressPreferenceService.disableCallbackData(companyId)
        );
    }

    public boolean sendMessageWithInlineButton(long chatId, String text, String buttonText, String callbackData) {
        if (!sendingEnabled) {
            log.debug("Telegram-сообщение не отправлено chatId={}: отправка отключена настройкой", chatId);
            return false;
        }
        if (!looksLikeTelegramBotToken(getBotToken())) {
            log.warn("Telegram-сообщение не отправлено: TELEGRAM_BOT_TOKEN пустой или имеет неверный формат");
            return false;
        }
        if (!hasText(text)) {
            log.warn("Telegram-сообщение для {} не отправлено: текст пустой", chatId);
            return false;
        }
        if (!hasText(buttonText) || !hasText(callbackData)) {
            return sendMessage(chatId, text);
        }

        return sendSingleMessage(chatId, text, null, inlineKeyboard(buttonText, callbackData));
    }

    public boolean sendMessageWithInlineKeyboard(
            long chatId,
            String text,
            String parseMode,
            List<List<InlineKeyboardButton>> keyboard
    ) {
        if (!sendingEnabled) {
            log.debug("Telegram-сообщение не отправлено chatId={}: отправка отключена настройкой", chatId);
            return false;
        }
        if (!looksLikeTelegramBotToken(getBotToken())) {
            log.warn("Telegram-сообщение не отправлено: TELEGRAM_BOT_TOKEN пустой или имеет неверный формат");
            return false;
        }
        if (!hasText(text)) {
            log.warn("Telegram-сообщение для {} не отправлено: текст пустой", chatId);
            return false;
        }

        InlineKeyboardMarkup markup = null;
        if (keyboard != null && !keyboard.isEmpty()) {
            markup = new InlineKeyboardMarkup();
            markup.setKeyboard(keyboard);
        }
        return sendSingleMessage(chatId, text, parseMode, markup);
    }

    public boolean sendForceReplyMessage(long chatId, String text) {
        if (!sendingEnabled) {
            log.debug("Telegram-сообщение не отправлено chatId={}: отправка отключена настройкой", chatId);
            return false;
        }
        if (!looksLikeTelegramBotToken(getBotToken())) {
            log.warn("Telegram-сообщение не отправлено: TELEGRAM_BOT_TOKEN пустой или имеет неверный формат");
            return false;
        }
        if (!hasText(text)) {
            log.warn("Telegram-сообщение для {} не отправлено: текст пустой", chatId);
            return false;
        }
        ForceReplyKeyboard forceReply = new ForceReplyKeyboard();
        forceReply.setForceReply(true);
        forceReply.setSelective(false);
        return sendSingleMessage(chatId, text, null, forceReply);
    }

    public Optional<Integer> sendMessageWithInlineKeyboardMessageId(
            long chatId,
            String text,
            String parseMode,
            List<List<InlineKeyboardButton>> keyboard
    ) {
        if (!sendingEnabled) {
            log.debug("Telegram-сообщение не отправлено chatId={}: отправка отключена настройкой", chatId);
            return Optional.empty();
        }
        if (!looksLikeTelegramBotToken(getBotToken())) {
            log.warn("Telegram-сообщение не отправлено: TELEGRAM_BOT_TOKEN пустой или имеет неверный формат");
            return Optional.empty();
        }
        if (!hasText(text)) {
            log.warn("Telegram-сообщение для {} не отправлено: текст пустой", chatId);
            return Optional.empty();
        }

        InlineKeyboardMarkup markup = null;
        if (keyboard != null && !keyboard.isEmpty()) {
            markup = new InlineKeyboardMarkup();
            markup.setKeyboard(keyboard);
        }
        return sendSingleMessageResult(chatId, text, parseMode, markup)
                .map(Message::getMessageId);
    }

    public boolean editMessageText(
            long chatId,
            int messageId,
            String text,
            String parseMode,
            List<List<InlineKeyboardButton>> keyboard
    ) {
        if (!sendingEnabled || !looksLikeTelegramBotToken(getBotToken()) || !hasText(text)) {
            return false;
        }
        try {
            EditMessageText edit = new EditMessageText();
            edit.setChatId(String.valueOf(chatId));
            edit.setMessageId(messageId);
            edit.setText(text);
            edit.setDisableWebPagePreview(true);
            if (hasText(parseMode)) {
                edit.setParseMode(parseMode);
            }
            if (keyboard != null && !keyboard.isEmpty()) {
                InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
                markup.setKeyboard(keyboard);
                edit.setReplyMarkup(markup);
            }
            executeEditMessageText(edit);
            return true;
        } catch (TelegramApiException e) {
            log.warn("Не удалось обновить Telegram-сообщение chatId={} messageId={}: {}", chatId, messageId, e.getMessage());
            return false;
        }
    }

    public boolean sendMessage(long chatId, String text, String parseMode) {
        if (!sendingEnabled) {
            log.debug("Telegram-сообщение не отправлено chatId={}: отправка отключена настройкой", chatId);
            return false;
        }
        if (!looksLikeTelegramBotToken(getBotToken())) {
            log.warn("Telegram-сообщение не отправлено: TELEGRAM_BOT_TOKEN пустой или имеет неверный формат");
            return false;
        }
        if (!hasText(text)) {
            log.warn("Telegram-сообщение для {} не отправлено: текст пустой", chatId);
            return false;
        }

        boolean sent = true;
        for (String chunk : splitTelegramMessage(text)) {
            sent = sendSingleMessage(chatId, chunk, parseMode, null) && sent;
        }
        return sent;
    }

    private boolean sendSingleMessage(long chatId, String text, String parseMode) {
        return sendSingleMessage(chatId, text, parseMode, null);
    }

    private boolean sendSingleMessage(long chatId, String text, String parseMode, ReplyKeyboard replyMarkup) {
        return sendSingleMessageResult(chatId, text, parseMode, replyMarkup).isPresent();
    }

    private Optional<Message> sendSingleMessageResult(long chatId, String text, String parseMode, ReplyKeyboard replyMarkup) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        message.setDisableWebPagePreview(true);
        if (hasText(parseMode)) {
            message.setParseMode(parseMode);
        }
        if (replyMarkup != null) {
            message.setReplyMarkup(replyMarkup);
        }

        for (int attempt = 1; attempt <= SEND_ATTEMPTS; attempt++) {
            try {
                Message sentMessage = executeTelegramMessage(message);
                if (attempt > 1) {
                    log.info("Telegram-сообщение отправлено chatId={} после повтора {}", chatId, attempt);
                } else {
                    log.info("Telegram-сообщение отправлено chatId={}", chatId);
                }
                return Optional.ofNullable(sentMessage);
            } catch (TelegramApiRequestException e) {
                Optional<Long> migratedChatId = migrateToChatId(e);
                if (migratedChatId.isPresent()) {
                    return resendAfterChatMigrationResult(chatId, migratedChatId.get(), text, parseMode, replyMarkup);
                }
                if (e.getApiResponse() != null && e.getApiResponse().contains("bot was blocked by the user")) {
                    log.warn("Telegram-бот заблокирован пользователем. ChatId: {}", chatId);
                } else if (isNotFound(e)) {
                    log.warn("Telegram-сообщение не отправлено chatId={}: Telegram вернул 404. Проверьте TELEGRAM_BOT_TOKEN и proxy. Ошибка: {}", chatId, e.getMessage());
                } else {
                    log.error("Telegram API ошибка для chatId={}: {}", chatId, e.getApiResponse(), e);
                }
                return Optional.empty();
            } catch (TelegramApiException e) {
                if (handleRetryableSendException(chatId, attempt, e)) {
                    continue;
                }
                if (!isTransientNetworkException(e)) {
                    log.error("Ошибка при отправке Telegram-сообщения chatId={}: {}", chatId, e.getMessage(), e);
                }
                return Optional.empty();
            } catch (Exception e) {
                if (handleRetryableSendException(chatId, attempt, e)) {
                    continue;
                }
                if (!isTransientNetworkException(e)) {
                    log.error("Неизвестная ошибка при отправке Telegram-сообщения chatId={}: {}", chatId, e.getMessage(), e);
                }
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    public Optional<TelegramChatMigrationResult> repairMigratedChatId(long oldChatId) {
        if (telegramChatMigrationService == null) {
            return Optional.empty();
        }
        if (!sendingEnabled || !looksLikeTelegramBotToken(getBotToken())) {
            return Optional.empty();
        }

        GetChat request = new GetChat(String.valueOf(oldChatId));
        try {
            executeGetChat(request);
            return Optional.empty();
        } catch (TelegramApiRequestException e) {
            Optional<Long> newChatId = migrateToChatId(e);
            return newChatId.map(value -> telegramChatMigrationService.migrateChatId(oldChatId, value));
        } catch (TelegramApiException e) {
            log.warn("Не удалось проверить миграцию Telegram-чата chatId={}: {}", oldChatId, e.getMessage());
            return Optional.empty();
        }
    }

    private boolean resendAfterChatMigration(
            long oldChatId,
            long newChatId,
            String text,
            String parseMode,
            ReplyKeyboard replyMarkup
    ) {
        if (telegramChatMigrationService != null) {
            telegramChatMigrationService.migrateChatId(oldChatId, newChatId);
        } else {
            log.warn("Telegram chat migrated oldChatId={} newChatId={}, but migration service is unavailable", oldChatId, newChatId);
        }
        log.info("Повторяем Telegram-сообщение после миграции chatId={} -> {}", oldChatId, newChatId);
        return sendSingleMessage(newChatId, text, parseMode, replyMarkup);
    }

    private Optional<Message> resendAfterChatMigrationResult(
            long oldChatId,
            long newChatId,
            String text,
            String parseMode,
            ReplyKeyboard replyMarkup
    ) {
        if (telegramChatMigrationService != null) {
            telegramChatMigrationService.migrateChatId(oldChatId, newChatId);
        } else {
            log.warn("Telegram chat migrated oldChatId={} newChatId={}, but migration service is unavailable", oldChatId, newChatId);
        }
        log.info("Повторяем Telegram-сообщение после миграции chatId={} -> {}", oldChatId, newChatId);
        return sendSingleMessageResult(newChatId, text, parseMode, replyMarkup);
    }

    private Optional<Long> migrateToChatId(TelegramApiRequestException e) {
        if (e == null || e.getParameters() == null || e.getParameters().getMigrateToChatId() == null) {
            return Optional.empty();
        }
        return Optional.of(e.getParameters().getMigrateToChatId());
    }

    private InlineKeyboardMarkup inlineKeyboard(String buttonText, String callbackData) {
        InlineKeyboardButton button = new InlineKeyboardButton();
        button.setText(buttonText);
        button.setCallbackData(callbackData);

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(Collections.singletonList(Collections.singletonList(button)));
        return markup;
    }

    private void answerCallback(String callbackQueryId, String text) {
        if (!sendingEnabled) {
            return;
        }
        if (!hasText(callbackQueryId)) {
            return;
        }
        try {
            AnswerCallbackQuery answer = new AnswerCallbackQuery();
            answer.setCallbackQueryId(callbackQueryId);
            if (hasText(text)) {
                answer.setText(text);
            }
            executeAnswerCallback(answer);
        } catch (Exception e) {
            log.warn("Не удалось ответить на Telegram callbackQuery {}", callbackQueryId, e);
        }
    }

    Message executeTelegramMessage(SendMessage message) throws TelegramApiException {
        return execute(message);
    }

    Chat executeGetChat(GetChat request) throws TelegramApiException {
        return execute(request);
    }

    void executeAnswerCallback(AnswerCallbackQuery answer) throws TelegramApiException {
        execute(answer);
    }

    void executeEditMessageText(EditMessageText edit) throws TelegramApiException {
        execute(edit);
    }

    private boolean handleRetryableSendException(long chatId, int attempt, Exception exception) {
        if (!isTransientNetworkException(exception)) {
            return false;
        }

        if (attempt >= SEND_ATTEMPTS) {
            log.warn("Telegram-сообщение временно не отправлено chatId={} после {} попыток: {}",
                    chatId, SEND_ATTEMPTS, concise(exception));
            log.debug("Telegram send transient exception", exception);
            return false;
        }

        long delayMillis = SEND_RETRY_DELAY_MS * attempt;
        log.warn("Telegram-сообщение временно не отправлено chatId={} попытка {}/{}: {}. Повтор через {} ms",
                chatId, attempt, SEND_ATTEMPTS, concise(exception), delayMillis);
        log.debug("Telegram send transient exception", exception);
        sleepBeforeRetry(delayMillis);
        return true;
    }

    void sleepBeforeRetry(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<String> splitTelegramMessage(String text) {
        if (text.length() <= MAX_TELEGRAM_MESSAGE_LENGTH) {
            return List.of(text);
        }

        List<String> chunks = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String block : text.split("\\n\\n")) {
            String part = block + "\n\n";
            if (current.length() > 0 && current.length() + part.length() > MAX_TELEGRAM_MESSAGE_LENGTH) {
                chunks.add(current.toString().trim());
                current.setLength(0);
            }
            if (part.length() > MAX_TELEGRAM_MESSAGE_LENGTH) {
                appendLongBlockChunks(chunks, current, block);
            } else {
                current.append(part);
            }
        }
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    private static void appendLongBlockChunks(List<String> chunks, StringBuilder current, String block) {
        for (String line : block.split("\\n")) {
            String part = line + "\n";
            if (current.length() > 0 && current.length() + part.length() > MAX_TELEGRAM_MESSAGE_LENGTH) {
                chunks.add(current.toString().trim());
                current.setLength(0);
            }
            if (part.length() <= MAX_TELEGRAM_MESSAGE_LENGTH) {
                current.append(part);
                continue;
            }
            for (int start = 0; start < part.length(); start += MAX_TELEGRAM_MESSAGE_LENGTH) {
                int end = Math.min(start + MAX_TELEGRAM_MESSAGE_LENGTH, part.length());
                chunks.add(part.substring(start, end));
            }
        }
    }

    public void sendAlertToAdmins(String text) {
        if (adminChatIds.isEmpty()) {
            log.warn("Telegram-алерт не отправлен: telegram.admin.chat-ids пустой");
            return;
        }

        adminChatIds.forEach(chatId -> sendMessage(chatId, text, "Markdown"));
    }

    protected User authUserInTelegramBot(long chatId, String messageText) {
        Optional<User> optionalUserByChatId = userService.findByChatId(chatId);

        if (optionalUserByChatId.isPresent()) {
            log.info("Пользователь найден по Telegram chatId={}", chatId);
            return optionalUserByChatId.get();
        }

        Optional<User> optionalUser = userService.findByUserName(messageText);

        if (optionalUser.isPresent()) {
            User user = optionalUser.get();

            if (user.getTelegramChatId() == null) {
                user.setTelegramChatId(chatId);
                userService.save(user);
                sendMessage(chatId, "Привязка успешно выполнена! Добро пожаловать, " + user.getUsername(), "Markdown");
                log.info("Telegram chatId={} привязан к пользователю {}", chatId, user.getUsername());
            } else {
                sendMessage(chatId, "Добро пожаловать обратно, " + user.getUsername() + "!", "Markdown");
            }

            return user;
        }

        sendMessage(chatId, "Пользователь с таким username не найден. Введите свой логин:", "Markdown");
        log.info("Telegram-пользователь не найден по chatId={} и username='{}'", chatId, messageText);
        return null;
    }

    private static List<Long> parseAdminChatIds(String raw) {
        if (!hasText(raw)) {
            return List.of();
        }

        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(TelegramService::hasText)
                .map(TelegramService::parseLongOrNull)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static Long parseLongOrNull(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            log.warn("Некорректный Telegram admin chatId '{}', значение пропущено", raw);
            return null;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private boolean isChatIdCommand(String messageText) {
        if (!hasText(messageText)) {
            return false;
        }

        String command = messageText.trim().split("\\s+", 2)[0];
        String normalizedBotUsername = hasText(botUsername) ? botUsername.replaceFirst("^@", "") : "";
        return "/chatid".equalsIgnoreCase(command)
                || (hasText(normalizedBotUsername) && command.equalsIgnoreCase("/chatid@" + normalizedBotUsername));
    }

    private static boolean isPrivateChat(Update update) {
        return update != null
                && update.hasMessage()
                && update.getMessage().getChat() != null
                && Boolean.TRUE.equals(update.getMessage().getChat().isUserChat());
    }

    private static boolean looksLikeTelegramBotToken(String botToken) {
        return hasText(botToken) && BOT_TOKEN_PATTERN.matcher(botToken.trim()).matches();
    }

    private static boolean isNotFound(TelegramApiRequestException e) {
        return Integer.valueOf(404).equals(e.getErrorCode())
                || (e.getMessage() != null && e.getMessage().contains("[404]"));
    }

    private static boolean isTransientNetworkException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof ConnectTimeoutException
                    || current instanceof NoHttpResponseException
                    || current instanceof SocketException
                    || current instanceof SSLException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String concise(Throwable throwable) {
        Throwable current = throwable;
        Throwable last = throwable;
        while (current != null) {
            last = current;
            current = current.getCause();
        }

        String message = last.getMessage();
        if (!hasText(message)) {
            return last.getClass().getSimpleName();
        }
        return last.getClass().getSimpleName() + ": " + message;
    }

    private void checkTimeMethod(String text, long startTime) {
        long endTime = System.nanoTime();
        double timeElapsed = (endTime - startTime) / 1_000_000_000.0;
        log.info("{}{} сек", text, String.format("%.4f", timeElapsed));
    }
}
