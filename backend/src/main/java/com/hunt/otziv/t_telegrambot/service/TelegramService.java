package com.hunt.otziv.t_telegrambot.service;

import com.hunt.otziv.admin.services.PersonalService;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.NoHttpResponseException;
import org.apache.http.conn.ConnectTimeoutException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.ChatMemberUpdated;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

import javax.net.ssl.SSLException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
@Slf4j
public class TelegramService extends TelegramLongPollingBot {

    private static final Pattern BOT_TOKEN_PATTERN = Pattern.compile("\\d{6,}:[A-Za-z0-9_-]{20,}");
    private static final int MAX_TELEGRAM_MESSAGE_LENGTH = 3900;
    private static final int SEND_ATTEMPTS = 3;
    private static final long SEND_RETRY_DELAY_MS = 1_500L;

    private final String botUsername;
    private final List<Long> adminChatIds;
    private final ObjectProvider<PersonalService> personalServiceProvider;
    private final UserService userService;
    private final TelegramGroupLinkService telegramGroupLinkService;

    public TelegramService(
            DefaultBotOptions botOptions,
            @Value("${telegram.bot.token:}") String botToken,
            @Value("${telegram.bot.username:}") String botUsername,
            @Value("${telegram.admin.chat-ids:}") String adminChatIds,
            ObjectProvider<PersonalService> personalServiceProvider,
            UserService userService,
            TelegramGroupLinkService telegramGroupLinkService
    ) {
        super(botOptions, botToken);
        this.botUsername = botUsername;
        this.adminChatIds = parseAdminChatIds(adminChatIds);
        this.personalServiceProvider = personalServiceProvider;
        this.userService = userService;
        this.telegramGroupLinkService = telegramGroupLinkService;
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

        User user = authUserInTelegramBot(chatId, messageText);
        if (user == null) {
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

    public boolean sendMessage(long chatId, String text, String parseMode) {
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
            sent = sendSingleMessage(chatId, chunk, parseMode) && sent;
        }
        return sent;
    }

    private boolean sendSingleMessage(long chatId, String text, String parseMode) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        message.setDisableWebPagePreview(true);
        if (hasText(parseMode)) {
            message.setParseMode(parseMode);
        }

        for (int attempt = 1; attempt <= SEND_ATTEMPTS; attempt++) {
            try {
                executeTelegramMessage(message);
                if (attempt > 1) {
                    log.info("Telegram-сообщение отправлено chatId={} после повтора {}", chatId, attempt);
                } else {
                    log.info("Telegram-сообщение отправлено chatId={}", chatId);
                }
                return true;
            } catch (TelegramApiRequestException e) {
                if (e.getApiResponse() != null && e.getApiResponse().contains("bot was blocked by the user")) {
                    log.warn("Telegram-бот заблокирован пользователем. ChatId: {}", chatId);
                } else if (isNotFound(e)) {
                    log.warn("Telegram-сообщение не отправлено chatId={}: Telegram вернул 404. Проверьте TELEGRAM_BOT_TOKEN и proxy. Ошибка: {}", chatId, e.getMessage());
                } else {
                    log.error("Telegram API ошибка для chatId={}: {}", chatId, e.getApiResponse(), e);
                }
                return false;
            } catch (TelegramApiException e) {
                if (handleRetryableSendException(chatId, attempt, e)) {
                    continue;
                }
                if (!isTransientNetworkException(e)) {
                    log.error("Ошибка при отправке Telegram-сообщения chatId={}: {}", chatId, e.getMessage(), e);
                }
                return false;
            } catch (Exception e) {
                if (handleRetryableSendException(chatId, attempt, e)) {
                    continue;
                }
                if (!isTransientNetworkException(e)) {
                    log.error("Неизвестная ошибка при отправке Telegram-сообщения chatId={}: {}", chatId, e.getMessage(), e);
                }
                return false;
            }
        }
        return false;
    }

    void executeTelegramMessage(SendMessage message) throws TelegramApiException {
        execute(message);
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
