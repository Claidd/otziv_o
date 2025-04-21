package com.hunt.otziv.t_telegrambot.service;

import com.hunt.otziv.admin.dto.presonal.UserData;
import com.hunt.otziv.admin.services.PersonalService;
import com.hunt.otziv.t_telegrambot.MyTelegramBot;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationSchedulerToTelegramImpl implements NotificationSchedulerToTelegram{
    private final MyTelegramBot myTelegramBot;
    private final UserService userService;
    private final PersonalService personalService;

    // каждый день в 9:25
    @Scheduled(cron = "0 23 10 * * *")
    public void sendDailyReport() {
        myTelegramBot.sendMessage(794146111,"Доброе утро! Отчёт за сегодня готов.");
    }

    @Scheduled(cron = "0 25 9 * * *") // каждый день в 9:25
    public void sendDailyReportToWorkers() {
        Map<String, Long> workersTelegramIDs = userService.getAllWorkers();
        Map<String, UserData> userDataMap = personalService.getPersonalsAndCountToMap();

        sendWorkerReports(workersTelegramIDs, userDataMap);
        sendOwnerReports(userDataMap);
        sendAdminReport(workersTelegramIDs, userDataMap);
    }


    private void sendWorkerReports(Map<String, Long> chatIds, Map<String, UserData> userDataMap) {
        userDataMap.forEach((fio, data) -> {
            Long chatId = chatIds.get(fio);
            if (chatId != null && chatId > 0) {
                String message = generateMessageByRole(data.getRole(), fio, data, userDataMap);
                sendMessageSafe(chatId, message, fio);
            } else {
                log.warn("У сотрудника {} chatId отсутствует", fio);
            }
        });
    }

    private void sendOwnerReports(Map<String, UserData> userDataMap) {
        List<User> owners = userService.getAllOwners("ROLE_OWNER");

        for (User owner : owners) {
            Set<String> allowedFio = owner.getManagers().stream()
                    .flatMap(manager -> {
                        Stream<String> workersFio = manager.getUser().getWorkers().stream()
                                .map(w -> w.getUser().getFio());
                        return Stream.concat(Stream.of(manager.getUser().getFio()), workersFio);
                    })
                    .collect(Collectors.toSet());

            Map<String, UserData> filtered = userDataMap.entrySet().stream()
                    .filter(entry -> allowedFio.contains(entry.getKey()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            Long chatId = owner.getTelegramChatId();
            if (chatId != null && chatId > 0) {
                String message = personalService.displayResult(filtered);
                sendMessageSafe(chatId, message, owner.getFio());
            } else {
                log.info("У владельца {} chatId отсутствует", owner.getFio());
            }
        }
    }

    private void sendAdminReport(Map<String, Long> chatIds, Map<String, UserData> userDataMap) {
        Long adminChatId = chatIds.get("Админ");
        if (adminChatId != null && adminChatId > 0) {
            String message = personalService.displayResultToTelegramAdmin(userDataMap);
            sendMessageSafe(adminChatId, message, "Админ");
        } else {
            log.warn("У Админа отсутствует chatId");
        }
    }

    private void sendMessageSafe(Long chatId, String message, String who) {
        try {
            myTelegramBot.sendMessage(chatId, message);
        } catch (Exception e) {
            log.error("Ошибка отправки Telegram-сообщения для {}: {}", who, e.getMessage());
        }
    }

    private String generateMessageByRole(String role, String fio, UserData userData, Map<String, UserData> result) {
        switch (role) {
            case "ROLE_MANAGER":
                return "Ежедневная сводка: " + "\n\n" +
//                        fio + ": " + userData.getSalary()+ " руб. " +
                        "Лиды: " + userData.getLeadsNew() + "\n" +
                        "В проверку: " + userData.getOrderToCheck() + " На проверке: " + userData.getOrderInCheck()  + "\n" +
                        "Опубликовано: " + userData.getOrderInPublished() + " Выставлен счет: " + userData.getOrderInWaitingPay1() + "\n" +
                        "Напоминание: " + userData.getOrderInWaitingPay2() + " Не оплачено: " + userData.getOrderNoPay() + "\n\n"  +
                        "Новых - " + userData.getNewOrders() + " Коррекция - " + userData.getCorrectOrders() + "\n" +
                        "Выгул - " + userData.getInVigul() + " Публикация - " + userData.getInPublish();
            case "ROLE_WORKER":
                return "Ежедневная сводка: " + "\n\n" +
                        "Новых - " + userData.getNewOrders() + " Коррекция - " + userData.getCorrectOrders() + "\n" +
                        "Выгул - " + userData.getInVigul() + " Публикация - " + userData.getInPublish();
            default:
                return "Здравствуйте, " + fio + "!";
        }
    }








    // каждый день в 9:25
//    @Scheduled(cron = "0 51 23 * * *")
//    public void sendDailyReportToWorkers() {
//
//        Map<String, Long> workersTelegramID = userService.getAllWorkers();
//        Map<String, UserData> result = personalService.getPersonalsAndCountToMap();
//
//        Map<String, WorkerData> mergedMap = new HashMap<>();
////        System.out.println(" Начался метод" + workersTelegramID);
//        for (String fio : result.keySet()) {
//            mergedMap.put(fio, new WorkerData(result.get(fio), workersTelegramID.get(fio)));
//        }
//
////        System.out.println(mergedMap);
//
//        for (Map.Entry<String, WorkerData> entry : mergedMap.entrySet()) {
//            String fio = entry.getKey();
//            WorkerData workerData = entry.getValue();
//
//            Long chatId = workerData.getTelegramChatId();
//            UserData userData = workerData.getUserData();
//
//            String role = userData.getRole();
////            System.out.println(fio);
////            System.out.println(chatId);
////            System.out.println(role);
//            if (chatId != null) {
////                System.out.println(fio);
//                String textMessage = generateMessageByRole(role, fio, userData, result);
//                myTelegramBot.sendMessage(chatId, textMessage);
//            }
//        }
//        List<User> usersManagers = userService.getAllOwners("ROLE_OWNER");
//        for (User user : usersManagers) {
//            // Собираем список ФИО менеджеров и их работников, которые закреплены за user1
//            Set<String> allowedFio = user.getManagers().stream()
//                    .flatMap(manager -> {
//                        Stream<String> workersFio = manager.getUser().getWorkers().stream()
//                                .map(worker -> worker.getUser().getFio()); // Или как у тебя называется поле с ФИО
//                        return Stream.concat(Stream.of(manager.getUser().getFio()), workersFio);
//                    })
//                    .collect(Collectors.toSet());
//
//// Фильтруем Map оставляя только нужных людей
//            Map<String, UserData> filteredResult = result.entrySet().stream()
//                    .filter(entry -> allowedFio.contains(entry.getKey()))
//                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
//
//            System.out.println(user.getTelegramChatId());
//            if (user.getTelegramChatId() != null) {
//                String textMessage = personalService.displayResult(filteredResult);
////                System.out.println("Отправка владельцу " + user.getFio() + " chatId=" + user.getTelegramChatId());
//                myTelegramBot.sendMessage(user.getTelegramChatId(), textMessage);
//            } else {
//                log.info("У владельца " + user.getFio() + " chatId == null");
//            }
//        }
//
//        Long telegramId = workersTelegramID.get("Админ"); // получаем Long напрямую
//        System.out.println(telegramId);
//        if (telegramId != null) {
//            myTelegramBot.sendMessage(telegramId, personalService.displayResultToTelegramAdmin(result)); // Long уже содержит значение, преобразование не требуется
//        }
//    }











}
