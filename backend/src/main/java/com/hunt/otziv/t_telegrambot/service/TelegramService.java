package com.hunt.otziv.t_telegrambot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hunt.otziv.admin.services.PersonalService;
import com.hunt.otziv.client_chat_control.dto.ClientChatMessageCommand;
import com.hunt.otziv.client_chat_control.model.ClientChatDirection;
import com.hunt.otziv.client_chat_control.model.ClientChatPlatform;
import com.hunt.otziv.client_chat_control.service.ClientChatMessageTrackerService;
import com.hunt.otziv.client_messages.service.PublicationProgressPreferenceService;
import com.hunt.otziv.manager_control.service.ManagerControlWorkerTaskTelegramCallbackService;
import com.hunt.otziv.manager_daily_summary.service.ManagerReportReviewTelegramService;
import com.hunt.otziv.performers.service.PerformerTelegramCallbackService;
import com.hunt.otziv.performers.service.PerformerTelegramLinkService;
import com.hunt.otziv.t_telegrambot.dto.TelegramChatMigrationResult;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.worker_activity.service.WorkerRiskTelegramCallbackService;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
    private static final int MAX_TELEGRAM_RICH_MESSAGE_LENGTH = 32_000;
    private static final int SEND_ATTEMPTS = 3;
    private static final long SEND_RETRY_DELAY_MS = 1_500L;
    private static final ObjectMapper TELEGRAM_JSON = new ObjectMapper();

    private final String botUsername;
    private final boolean sendingEnabled;
    private final List<Long> adminChatIds;
    private final ObjectProvider<PersonalService> personalServiceProvider;
    private final UserService userService;
    private final TelegramGroupLinkService telegramGroupLinkService;
    private final PublicationProgressPreferenceService publicationProgressPreferenceService;
    private final ObjectProvider<PerformerTelegramLinkService> performerTelegramLinkServiceProvider;
    private final ObjectProvider<PerformerTelegramCallbackService> performerTelegramCallbackServiceProvider;
    private final ObjectProvider<WorkerRiskTelegramCallbackService> workerRiskTelegramCallbackServiceProvider;
    private final ObjectProvider<ManagerControlWorkerTaskTelegramCallbackService> managerControlWorkerTaskTelegramCallbackServiceProvider;
    private final ObjectProvider<ManagerReportReviewTelegramService> managerReportReviewTelegramServiceProvider;
    private final TelegramChatMigrationService telegramChatMigrationService;
    private final ClientChatMessageTrackerService clientChatMessageTrackerService;
    private final HttpClient richMessageHttpClient;

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
                null,
                null,
                workerRiskTelegramCallbackServiceProvider,
                null,
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
            ObjectProvider<PerformerTelegramLinkService> performerTelegramLinkServiceProvider,
            ObjectProvider<PerformerTelegramCallbackService> performerTelegramCallbackServiceProvider,
            ObjectProvider<WorkerRiskTelegramCallbackService> workerRiskTelegramCallbackServiceProvider,
            ObjectProvider<ManagerControlWorkerTaskTelegramCallbackService> managerControlWorkerTaskTelegramCallbackServiceProvider,
            ObjectProvider<ManagerReportReviewTelegramService> managerReportReviewTelegramServiceProvider,
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
        this.performerTelegramLinkServiceProvider = performerTelegramLinkServiceProvider;
        this.performerTelegramCallbackServiceProvider = performerTelegramCallbackServiceProvider;
        this.workerRiskTelegramCallbackServiceProvider = workerRiskTelegramCallbackServiceProvider;
        this.managerControlWorkerTaskTelegramCallbackServiceProvider = managerControlWorkerTaskTelegramCallbackServiceProvider;
        this.managerReportReviewTelegramServiceProvider = managerReportReviewTelegramServiceProvider;
        this.telegramChatMigrationService = telegramChatMigrationService;
        this.clientChatMessageTrackerService = clientChatMessageTrackerService;
        this.richMessageHttpClient = richMessageHttpClient(botOptions);
    }

    public TelegramService(
            DefaultBotOptions botOptions,
            String botToken,
            String botUsername,
            boolean sendingEnabled,
            String adminChatIds,
            ObjectProvider<PersonalService> personalServiceProvider,
            UserService userService,
            TelegramGroupLinkService telegramGroupLinkService,
            PublicationProgressPreferenceService publicationProgressPreferenceService,
            ObjectProvider<PerformerTelegramLinkService> performerTelegramLinkServiceProvider,
            ObjectProvider<PerformerTelegramCallbackService> performerTelegramCallbackServiceProvider,
            ObjectProvider<WorkerRiskTelegramCallbackService> workerRiskTelegramCallbackServiceProvider,
            ObjectProvider<ManagerControlWorkerTaskTelegramCallbackService> managerControlWorkerTaskTelegramCallbackServiceProvider,
            TelegramChatMigrationService telegramChatMigrationService,
            ClientChatMessageTrackerService clientChatMessageTrackerService
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
                performerTelegramLinkServiceProvider,
                performerTelegramCallbackServiceProvider,
                workerRiskTelegramCallbackServiceProvider,
                managerControlWorkerTaskTelegramCallbackServiceProvider,
                null,
                telegramChatMigrationService,
                clientChatMessageTrackerService
        );
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

        PerformerTelegramLinkService performerTelegramLinkService =
                performerTelegramLinkServiceProvider == null ? null : performerTelegramLinkServiceProvider.getIfAvailable();
        if (performerTelegramLinkService != null) {
            Optional<String> performerLinkResponse = performerTelegramLinkService.handleStartCommand(chatId, messageText);
            if (performerLinkResponse.isPresent()) {
                sendMessage(chatId, performerLinkResponse.get());
                return;
            }
        }

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
            Long actorTelegramId = update.getMessage().getFrom() == null
                    ? null
                    : update.getMessage().getFrom().getId();
            Message replyToMessage = update.getMessage().getReplyToMessage();
            Integer replyToMessageId = replyToMessage == null ? null : replyToMessage.getMessageId();
            ManagerReportReviewTelegramService managerReportReviewTelegramService =
                    managerReportReviewTelegramServiceProvider == null
                            ? null
                            : managerReportReviewTelegramServiceProvider.getIfAvailable();
            if (managerReportReviewTelegramService != null) {
                Optional<String> groupCommandResponse =
                        managerReportReviewTelegramService.handleGroupCommand(
                                chatId,
                                actorTelegramId,
                                messageText
                        );
                if (groupCommandResponse.isPresent()) {
                    sendMessage(chatId, groupCommandResponse.get(), "HTML");
                    return;
                }
                if (managerReportReviewTelegramService.handleGroupTextMessage(
                        chatId,
                        actorTelegramId,
                        messageText,
                        replyToMessageId
                )) {
                    return;
                }
            }

            WorkerRiskTelegramCallbackService workerRiskTelegramCallbackService =
                    workerRiskTelegramCallbackServiceProvider == null ? null : workerRiskTelegramCallbackServiceProvider.getIfAvailable();
            String replyToMessageText = telegramMessageText(replyToMessage);
            boolean replyToBotMessage = replyToMessage != null
                    && replyToMessage.getFrom() != null
                    && Boolean.TRUE.equals(replyToMessage.getFrom().getIsBot());
            if (workerRiskTelegramCallbackService != null
                    && workerRiskTelegramCallbackService.handleWorkerGroupTextMessage(
                    chatId, actorTelegramId, replyToMessageText, replyToBotMessage, messageText)) {
                return;
            }

            ManagerControlWorkerTaskTelegramCallbackService managerControlWorkerTaskTelegramCallbackService =
                    managerControlWorkerTaskTelegramCallbackServiceProvider == null
                            ? null
                            : managerControlWorkerTaskTelegramCallbackServiceProvider.getIfAvailable();
            if (managerControlWorkerTaskTelegramCallbackService != null
                    && managerControlWorkerTaskTelegramCallbackService.handleWorkerGroupTextMessage(
                    chatId, actorTelegramId, replyToMessageText, replyToBotMessage, messageText)) {
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

        ManagerReportReviewTelegramService managerReportReviewTelegramService =
                managerReportReviewTelegramServiceProvider == null
                        ? null
                        : managerReportReviewTelegramServiceProvider.getIfAvailable();
        Message managerReplyTo = update.getMessage().getReplyToMessage();
        Integer managerReplyToMessageId =
                managerReplyTo == null ? null : managerReplyTo.getMessageId();
        if (managerReportReviewTelegramService != null
                && managerReportReviewTelegramService.handleTextMessage(
                chatId,
                user,
                messageText,
                managerReplyToMessageId
        )) {
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

        PerformerTelegramCallbackService performerTelegramCallbackService =
                performerTelegramCallbackServiceProvider == null ? null : performerTelegramCallbackServiceProvider.getIfAvailable();
        if (performerTelegramCallbackService != null) {
            Optional<String> performerAnswer = performerTelegramCallbackService.handle(callbackQuery);
            if (performerAnswer.isPresent()) {
                answerCallback(callbackQuery.getId(), performerAnswer.get());
                return;
            }
        }

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

        ManagerReportReviewTelegramService managerReportReviewTelegramService =
                managerReportReviewTelegramServiceProvider == null
                        ? null
                        : managerReportReviewTelegramServiceProvider.getIfAvailable();
        if (managerReportReviewTelegramService != null) {
            Optional<String> reviewAnswer = managerReportReviewTelegramService.handle(callbackQuery);
            if (reviewAnswer.isPresent()) {
                log.info("Manager report review Telegram callback handled answer='{}'", reviewAnswer.get());
                answerCallback(callbackQuery.getId(), reviewAnswer.get());
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

    private String telegramMessageText(Message message) {
        if (message == null) {
            return "";
        }
        if (message.hasText()) {
            return message.getText();
        }
        return message.getCaption() == null ? "" : message.getCaption();
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
                    telegramMessageText(message),
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
        return sendForceReplyMessageId(chatId, text).isPresent();
    }

    public Optional<Integer> sendForceReplyMessageId(long chatId, String text) {
        return sendForceReplyMessageId(chatId, text, false);
    }

    public Optional<Integer> sendProtectedForceReplyMessageId(long chatId, String text) {
        return sendForceReplyMessageId(chatId, text, true);
    }

    private Optional<Integer> sendForceReplyMessageId(long chatId, String text, boolean protectContent) {
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
        ForceReplyKeyboard forceReply = new ForceReplyKeyboard();
        forceReply.setForceReply(true);
        forceReply.setSelective(false);
        return sendSingleMessageResult(chatId, text, null, forceReply, protectContent)
                .map(Message::getMessageId);
    }

    public boolean sendSelectiveForceReplyMessage(long chatId, long targetTelegramUserId, String text) {
        if (!sendingEnabled) {
            log.debug("Telegram-сообщение не отправлено chatId={}: отправка отключена настройкой", chatId);
            return false;
        }
        if (!looksLikeTelegramBotToken(getBotToken())) {
            log.warn("Telegram-сообщение не отправлено: TELEGRAM_BOT_TOKEN пустой или имеет неверный формат");
            return false;
        }
        if (!hasText(text) || targetTelegramUserId <= 0) {
            log.warn("Адресный Telegram-запрос для {} не отправлен: текст пустой или пользователь не определён", chatId);
            return false;
        }

        ForceReplyKeyboard forceReply = new ForceReplyKeyboard();
        forceReply.setForceReply(true);
        forceReply.setSelective(true);
        String targetedText = "<a href=\"tg://user?id=" + targetTelegramUserId + "\">Специалист</a>\n"
                + escapeTelegramHtml(text);
        return sendSingleMessage(chatId, targetedText, "HTML", forceReply);
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

    public Optional<Integer> sendProtectedMessageWithInlineKeyboardMessageId(
            long chatId,
            String text,
            String parseMode,
            List<List<InlineKeyboardButton>> keyboard
    ) {
        if (!sendingEnabled || !looksLikeTelegramBotToken(getBotToken()) || !hasText(text)) {
            return Optional.empty();
        }
        InlineKeyboardMarkup markup = null;
        if (keyboard != null && !keyboard.isEmpty()) {
            markup = new InlineKeyboardMarkup();
            markup.setKeyboard(keyboard);
        }
        return sendSingleMessageResult(chatId, text, parseMode, markup, true)
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

    /**
     * Sends a Bot API 10.2 rich message directly because the currently used Java
     * Telegram library predates sendRichMessage. Rich messages support native
     * headings, lists, tables and collapsible details and have a 32K text limit.
     */
    public boolean sendRichMessage(long chatId, String html) {
        return sendRichMessageWithInlineKeyboard(chatId, html, List.of());
    }

    public boolean sendRichMessageWithInlineKeyboard(
            long chatId,
            String html,
            List<List<InlineKeyboardButton>> keyboard
    ) {
        return sendRichMessageWithInlineKeyboardMessageId(chatId, html, keyboard).isPresent();
    }

    public Optional<Integer> sendRichMessageWithInlineKeyboardMessageId(
            long chatId,
            String html,
            List<List<InlineKeyboardButton>> keyboard
    ) {
        return sendRichMessageWithInlineKeyboardMessageId(chatId, html, keyboard, false);
    }

    public Optional<Integer> sendProtectedRichMessageWithInlineKeyboardMessageId(
            long chatId,
            String html,
            List<List<InlineKeyboardButton>> keyboard
    ) {
        return sendRichMessageWithInlineKeyboardMessageId(chatId, html, keyboard, true);
    }

    private Optional<Integer> sendRichMessageWithInlineKeyboardMessageId(
            long chatId,
            String html,
            List<List<InlineKeyboardButton>> keyboard,
            boolean protectContent
    ) {
        if (!sendingEnabled) {
            log.debug("Rich Telegram-сообщение не отправлено chatId={}: отправка отключена", chatId);
            return Optional.empty();
        }
        if (!looksLikeTelegramBotToken(getBotToken())) {
            log.warn("Rich Telegram-сообщение не отправлено: TELEGRAM_BOT_TOKEN отсутствует");
            return Optional.empty();
        }
        if (!hasText(html) || html.length() > MAX_TELEGRAM_RICH_MESSAGE_LENGTH) {
            log.warn("Rich Telegram-сообщение для {} не отправлено: длина {} символов",
                    chatId, html == null ? 0 : html.length());
            return Optional.empty();
        }

        try {
            Map<String, Object> payloadData = new java.util.LinkedHashMap<>();
            payloadData.put("chat_id", String.valueOf(chatId));
            payloadData.put("protect_content", protectContent);
            payloadData.put("rich_message", Map.of(
                    "html", html,
                    "skip_entity_detection", true
            ));
            if (keyboard != null && !keyboard.isEmpty()) {
                payloadData.put("reply_markup", Map.of(
                        "inline_keyboard",
                        keyboard.stream()
                                .map(row -> row.stream()
                                        .map(button -> Map.of(
                                                "text", button.getText(),
                                                "callback_data", button.getCallbackData()
                                        ))
                                        .toList())
                                .toList()
                ));
            }
            String payload = TELEGRAM_JSON.writeValueAsString(payloadData);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.telegram.org/bot" + getBotToken() + "/sendRichMessage"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            for (int attempt = 1; attempt <= SEND_ATTEMPTS; attempt++) {
                try {
                    HttpResponse<String> response = richMessageHttpClient.send(
                            request,
                            HttpResponse.BodyHandlers.ofString()
                    );
                    JsonNode body = TELEGRAM_JSON.readTree(response.body());
                    if (response.statusCode() >= 200
                            && response.statusCode() < 300
                            && body.path("ok").asBoolean(false)) {
                        log.info("Rich Telegram-аудит отправлен chatId={}", chatId);
                        int messageId = body.path("result").path("message_id").asInt(0);
                        return messageId > 0 ? Optional.of(messageId) : Optional.empty();
                    }
                    String description = body.path("description").asText("HTTP " + response.statusCode());
                    log.warn("Rich Telegram-аудит не принят chatId={} попытка {}/{}: {}",
                            chatId, attempt, SEND_ATTEMPTS, description);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                } catch (Exception exception) {
                    log.warn("Rich Telegram-аудит временно не отправлен chatId={} попытка {}/{}: {}",
                            chatId, attempt, SEND_ATTEMPTS, safeRichSendError(exception));
                }
                if (attempt < SEND_ATTEMPTS) {
                    sleepBeforeRetry(SEND_RETRY_DELAY_MS * attempt);
                }
            }
        } catch (Exception exception) {
            log.warn("Не удалось подготовить rich Telegram-аудит chatId={}: {}",
                    chatId, safeRichSendError(exception));
        }
        return Optional.empty();
    }

    public boolean editRichMessage(
            long chatId,
            int messageId,
            String html,
            List<List<InlineKeyboardButton>> keyboard
    ) {
        if (!sendingEnabled || !looksLikeTelegramBotToken(getBotToken())
                || !hasText(html) || html.length() > MAX_TELEGRAM_RICH_MESSAGE_LENGTH) {
            return false;
        }
        try {
            Map<String, Object> payloadData = new java.util.LinkedHashMap<>();
            payloadData.put("chat_id", String.valueOf(chatId));
            payloadData.put("message_id", messageId);
            payloadData.put("rich_message", Map.of(
                    "html", html,
                    "skip_entity_detection", true
            ));
            if (keyboard != null && !keyboard.isEmpty()) {
                payloadData.put("reply_markup", Map.of(
                        "inline_keyboard",
                        keyboard.stream()
                                .map(row -> row.stream()
                                        .map(button -> Map.of(
                                                "text", button.getText(),
                                                "callback_data", button.getCallbackData()
                                        ))
                                        .toList())
                                .toList()
                ));
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.telegram.org/bot" + getBotToken() + "/editMessageText"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(TELEGRAM_JSON.writeValueAsString(payloadData)))
                    .build();
            HttpResponse<String> response = richMessageHttpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );
            JsonNode body = TELEGRAM_JSON.readTree(response.body());
            if (response.statusCode() >= 200 && response.statusCode() < 300
                    && body.path("ok").asBoolean(false)) {
                return true;
            }
            log.warn("Rich Telegram-сообщение не обновлено chatId={} messageId={}: {}",
                    chatId, messageId, body.path("description").asText("HTTP " + response.statusCode()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            log.warn("Не удалось обновить rich Telegram-сообщение chatId={} messageId={}: {}",
                    chatId, messageId, safeRichSendError(exception));
        }
        return false;
    }

    private HttpClient richMessageHttpClient(DefaultBotOptions botOptions) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10));
        if (botOptions != null
                && botOptions.getProxyType() == DefaultBotOptions.ProxyType.HTTP
                && hasText(botOptions.getProxyHost())
                && botOptions.getProxyPort() > 0) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(
                    botOptions.getProxyHost(),
                    botOptions.getProxyPort()
            )));
        }
        return builder.build();
    }

    private String safeRichSendError(Exception exception) {
        String message = concise(exception);
        String token = getBotToken();
        if (hasText(token)) {
            message = message.replace(token, "[bot-token]");
        }
        return message;
    }

    private boolean sendSingleMessage(long chatId, String text, String parseMode) {
        return sendSingleMessage(chatId, text, parseMode, null);
    }

    private boolean sendSingleMessage(long chatId, String text, String parseMode, ReplyKeyboard replyMarkup) {
        return sendSingleMessageResult(chatId, text, parseMode, replyMarkup).isPresent();
    }

    private Optional<Message> sendSingleMessageResult(long chatId, String text, String parseMode, ReplyKeyboard replyMarkup) {
        return sendSingleMessageResult(chatId, text, parseMode, replyMarkup, false);
    }

    private Optional<Message> sendSingleMessageResult(
            long chatId,
            String text,
            String parseMode,
            ReplyKeyboard replyMarkup,
            boolean protectContent
    ) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        message.setDisableWebPagePreview(true);
        message.setProtectContent(protectContent);
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
                    return resendAfterChatMigrationResult(
                            chatId,
                            migratedChatId.get(),
                            text,
                            parseMode,
                            replyMarkup,
                            protectContent
                    );
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
        return resendAfterChatMigrationResult(
                oldChatId,
                newChatId,
                text,
                parseMode,
                replyMarkup,
                false
        );
    }

    private Optional<Message> resendAfterChatMigrationResult(
            long oldChatId,
            long newChatId,
            String text,
            String parseMode,
            ReplyKeyboard replyMarkup,
            boolean protectContent
    ) {
        if (telegramChatMigrationService != null) {
            telegramChatMigrationService.migrateChatId(oldChatId, newChatId);
        } else {
            log.warn("Telegram chat migrated oldChatId={} newChatId={}, but migration service is unavailable", oldChatId, newChatId);
        }
        log.info("Повторяем Telegram-сообщение после миграции chatId={} -> {}", oldChatId, newChatId);
        return sendSingleMessageResult(newChatId, text, parseMode, replyMarkup, protectContent);
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

    private static String escapeTelegramHtml(String value) {
        return value == null
                ? ""
                : value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
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
