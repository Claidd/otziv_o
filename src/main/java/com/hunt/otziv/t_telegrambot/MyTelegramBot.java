package com.hunt.otziv.t_telegrambot;
import com.hunt.otziv.admin.services.PersonalService;
import com.hunt.otziv.u_users.model.Role;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Optional;


@Component
@Slf4j
@RequiredArgsConstructor
public class MyTelegramBot extends TelegramLongPollingBot {
    @Lazy
    private final PersonalService personalService;
    private final UserService userService;

    @Value("${telegram.bot.token}")
    private String botToken;

    @Value("${telegram.bot.username}")
    private String botUsername;

    @Override

    public void onUpdateReceived(Update update) {
        long startTime = System.nanoTime();
        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();


            User user = authUserInTelegramBot(chatId, messageText);
            Long userId = null;
            String username = null;
            String role = null;

            if (user != null) {
                userId = user.getId();
                username = user.getUsername();
                role = user.getRoles().stream()
                        .map(Role::getName)
                        .findFirst()
                        .orElse("Без роли");

                // Обработка сообщения
//                System.out.println("Received message from chat ID: " + chatId);
//                System.out.println("Message text: " + messageText);
//                System.out.println("username: " + username);
//                System.out.println("role: " + role);
            } else {
                return; // если пользователя нет — дальше не идём
            }

            switch (messageText) {
                case "1":
                    // Отправляем сообщение
                    if (role.equals("ROLE_ADMIN")) {
                        sendMessage(chatId,personalService.displayResult(personalService.getPersonalsAndCountToMap()), "Markdown");
//                        sendMessage(chatId,personalService.displayResult(personalService.getPersonalsAndCountToMapToOwner(userId)));
//                        sendMessage(chatId,personalService.displayResultToManager(personalService.getPersonalsAndCountToMapToManager(userId)));
                        break;
                    }
                    if (role.equals("ROLE_OWNER")) {
                        sendMessage(chatId,personalService.displayResult(personalService.getPersonalsAndCountToMapToOwner(userId)), "Markdown");
                        break;
                    }
                    if (role.equals("ROLE_MANAGER")) {
                        sendMessage(chatId,personalService.displayResultToManager(personalService.getPersonalsAndCountToMapToManager(userId)), "Markdown");
                        break;
                    }
                    if (role.equals("ROLE_WORKER")) {
                        sendMessage(chatId,personalService.displayResultToWorker(personalService.getPersonalsAndCountToMapToWorker(userId)), "Markdown");
                        break;
                    }

                    else {
                        sendMessage(chatId, " У вас нет доступа", "Markdown");
                    }
                    break;

                case ("2"):
                    if (role.equals("ROLE_ADMIN")) {
                         sendMessage(chatId, personalService.displayResult(personalService.getPersonalsAndCountToMap()), "Markdown");
                        break;
                    }
                    if (role.equals("ROLE_OWNER")) {
                        sendMessage(chatId, personalService.displayResult(personalService.getPersonalsAndCountToMap()), "Markdown");
                        break;
                }
                    else {
                        sendMessage(chatId, " У вас нет доступа", "Markdown");
                    }

                case ("3"):
//                    stat = personalService.getStats(localDate, userService.findByUserName("mia").orElseThrow(), "ROLE_OWNER");
                    sendMessage(chatId, "Выручка за месяц mia: " + "\n" +
                            "Новых компаний: ",
                            "Markdown");
                    break;

                default:
                    sendMessage(chatId, "You said not need symbol: " + messageText, "Markdown");
                    break;
            }
            checkTimeMethod("Время выполнения Запроса для телеграмм отработал за: ",startTime);

            // Пример отправки ответа
//            sendMessage(chatId, "You said: " + messageText);
        }
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    public void sendMessage(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    public void sendMessage(long chatId, String text, String parseMode) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        message.setParseMode(parseMode); // Важно: добавляем режим разметки
        message.setDisableWebPagePreview(true); // (опционально) скрыть превью ссылок

        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error("❌ Ошибка при отправке сообщения в Telegram: {}", e.getMessage(), e);
        }
    }




    protected User authUserInTelegramBot(long chatId, String messageText) {
        // Ищем по chatId
        Optional<User> optionalUserByChatId = userService.findByChatId(chatId);

        if (optionalUserByChatId.isPresent()) {
            log.info("Пользователь по chat ID Не найден: " + chatId);
            return optionalUserByChatId.get();
        }

        // Ищем по username
        Optional<User> optionalUser = userService.findByUserName(messageText);

        if (optionalUser.isPresent()) {
            User user = optionalUser.get();

            if (user.getTelegramChatId() == null) {
                user.setTelegramChatId(chatId);
                userService.save(user);
                sendMessage(chatId, "Привязка успешно выполнена! Добро пожаловать, " + user.getUsername(), "Markdown");
                log.info("Привязка успешно выполнена! Добро пожаловать, " + user.getUsername());
            } else {
                sendMessage(chatId, "Добро пожаловать обратно, " + user.getUsername() + "!", "Markdown");
                log.info("Добро пожаловать обратно, " + user.getUsername() + "!");
            }

            return user;

        } else {
            sendMessage(chatId, "Пользователь с таким username не найден. Введите свой логин:", "Markdown");
            log.info("Пользователь с таким username не найден. Введите свой логин:");
            return null;
        }
    }
    private void checkTimeMethod(String text, long startTime){
        long endTime = System.nanoTime();
        double timeElapsed = (endTime - startTime) / 1_000_000_000.0;
        System.out.printf(text + "%.4f сек%n", timeElapsed);
    }

}
