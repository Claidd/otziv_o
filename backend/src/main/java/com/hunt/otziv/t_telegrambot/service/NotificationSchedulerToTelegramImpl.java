package com.hunt.otziv.t_telegrambot.service;

import com.hunt.otziv.admin.dto.presonal.UserData;
import com.hunt.otziv.admin.services.PersonalService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.services.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationSchedulerToTelegramImpl implements NotificationSchedulerToTelegram{
    private static final Duration DEFAULT_CATCH_UP_WINDOW = Duration.ofMinutes(15);

    private final TelegramService telegramService;
    private final UserService userService;
    private final PersonalService personalService;
    private final TelegramReportScheduleSettingsService reportScheduleSettingsService;
    private final AtomicReference<String> lastDecisionLogKey = new AtomicReference<>("");
    private Clock clock = Clock.systemDefaultZone();

    @Value("${telegram.reports.schedule.catch-up-window:PT15M}")
    private Duration catchUpWindow = DEFAULT_CATCH_UP_WINDOW;

    @Scheduled(
            fixedDelayString = "${telegram.reports.schedule.poll-delay-ms:30000}",
            initialDelayString = "${telegram.reports.schedule.initial-delay-ms:30000}"
    )
    public void sendConfiguredDailyReports() {
        try {
            TelegramReportScheduleSettingsResponse settings = reportScheduleSettingsService.settings();
            sendConfiguredReportIfDue(settings, ReportKind.MORNING);
            sendConfiguredReportIfDue(settings, ReportKind.EVENING);
        } catch (RuntimeException exception) {
            log.error("Telegram report schedule failed", exception);
        }
    }

    public void sendDailyReport() {
        Map<String, UserData> userDataMap = personalService.getPersonalsAndCountToMap();
        sendOnlyAdminReport(794146111L, userDataMap);
        telegramService.sendMessage(794146111L, "Доброе утро! Отчёт за сегодня готов", "HTML");
    }

    public void sendDailyReportToWorkers() {
        Map<String, Long> workersTelegramIDs = userService.getAllWorkers();
        Map<String, UserData> userDataMap = personalService.getPersonalsAndCountToMap();

        sendWorkerReports(workersTelegramIDs, userDataMap);
        sendOwnerReports(userDataMap);
        sendAdminReport(workersTelegramIDs, userDataMap);
    }

    private void sendConfiguredReportIfDue(TelegramReportScheduleSettingsResponse settings, ReportKind kind) {
        ScheduledTelegramReportDecision decision = scheduledDecision(settings, kind);
        if (decision.type() == ScheduledTelegramReportDecisionType.NOT_DUE) {
            return;
        }

        if (decision.type() == ScheduledTelegramReportDecisionType.MISSED_WINDOW) {
            logMissedWindowOnce(decision, kind);
            return;
        }

        boolean claimed = kind == ReportKind.MORNING
                ? reportScheduleSettingsService.claimMorningRun(decision.runKey())
                : reportScheduleSettingsService.claimEveningRun(decision.runKey());
        if (!claimed) {
            logAlreadyClaimedOnce(decision, kind);
            return;
        }

        log.info(
                "Telegram {} report starting: runKey={} scheduledAt={} now={} deadline={}",
                kind.logName(),
                decision.runKey(),
                decision.scheduledAt(),
                decision.now(),
                decision.deadline()
        );
        if (kind == ReportKind.MORNING) {
            sendDailyReport();
        } else {
            sendDailyReportToWorkers();
        }
    }

    ScheduledTelegramReportDecision scheduledDecision(TelegramReportScheduleSettingsResponse settings, ReportKind kind) {
        ZoneId zoneId = zoneId(settings);
        return scheduledDecision(settings, kind, ZonedDateTime.now(clock).withZoneSameInstant(zoneId));
    }

    ScheduledTelegramReportDecision scheduledDecision(
            TelegramReportScheduleSettingsResponse settings,
            ReportKind kind,
            ZonedDateTime now
    ) {
        if (settings == null || !kind.enabled(settings)) {
            return ScheduledTelegramReportDecision.notDue();
        }

        try {
            String timeValue = kind.time(settings);
            if (timeValue == null || timeValue.isBlank()) {
                return ScheduledTelegramReportDecision.notDue();
            }
            ZoneId zoneId = ZoneId.of(settings.zone());
            LocalTime scheduleTime = LocalTime.parse(timeValue.trim()).truncatedTo(ChronoUnit.MINUTES);
            ZonedDateTime zonedNow = now.withZoneSameInstant(zoneId);
            ZonedDateTime scheduledAt = zonedNow.toLocalDate().atTime(scheduleTime).atZone(zoneId);
            Duration window = normalizedCatchUpWindow();
            ZonedDateTime deadline = scheduledAt.plus(window);
            String runKey = zonedNow.toLocalDate() + " " + kind.key() + " " + scheduleTime + " " + zoneId;

            if (zonedNow.isBefore(scheduledAt)) {
                return ScheduledTelegramReportDecision.notDue();
            }
            if (zonedNow.isAfter(deadline)) {
                return new ScheduledTelegramReportDecision(
                        ScheduledTelegramReportDecisionType.MISSED_WINDOW,
                        runKey,
                        scheduledAt,
                        zonedNow,
                        deadline
                );
            }

            return new ScheduledTelegramReportDecision(
                    ScheduledTelegramReportDecisionType.DUE,
                    runKey,
                    scheduledAt,
                    zonedNow,
                    deadline
            );
        } catch (DateTimeException exception) {
            log.warn("Telegram {} report has invalid runtime schedule: {}", kind.logName(), exception.getMessage());
            return ScheduledTelegramReportDecision.notDue();
        }
    }

    void setClock(Clock clock) {
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    void setCatchUpWindow(Duration catchUpWindow) {
        this.catchUpWindow = catchUpWindow;
    }

    private ZoneId zoneId(TelegramReportScheduleSettingsResponse settings) {
        if (settings == null || settings.zone() == null || settings.zone().isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(settings.zone());
        } catch (DateTimeException exception) {
            return ZoneId.systemDefault();
        }
    }

    private Duration normalizedCatchUpWindow() {
        if (catchUpWindow == null || catchUpWindow.isNegative() || catchUpWindow.isZero()) {
            return DEFAULT_CATCH_UP_WINDOW;
        }
        return catchUpWindow;
    }

    private void logMissedWindowOnce(ScheduledTelegramReportDecision decision, ReportKind kind) {
        String key = "missed:" + kind.key() + ":" + decision.runKey();
        if (!key.equals(lastDecisionLogKey.getAndSet(key))) {
            log.info(
                    "Telegram {} report skipped: scheduledAt={} now={} deadline={} catchUpWindow={}",
                    kind.logName(),
                    decision.scheduledAt(),
                    decision.now(),
                    decision.deadline(),
                    normalizedCatchUpWindow()
            );
        }
    }

    private void logAlreadyClaimedOnce(ScheduledTelegramReportDecision decision, ReportKind kind) {
        String key = "claimed:" + kind.key() + ":" + decision.runKey();
        if (!key.equals(lastDecisionLogKey.getAndSet(key))) {
            log.info(
                    "Telegram {} report skipped: runKey={} has already been claimed",
                    kind.logName(),
                    decision.runKey()
            );
        }
    }


    private void sendWorkerReports(Map<String, Long> chatIds, Map<String, UserData> userDataMap) {
        userDataMap.forEach((fio, data) -> {
            Long chatId = chatIds.get(fio);
            if (chatId != null && chatId > 0) {
                String message = generateMessageByRole(data.getRole(), fio, data, userDataMap);
                sendMessageSafe(chatId, message, fio);
            } else {
//                log.warn("У сотрудника {} chatId отсутствует", fio);
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
                String message = personalService.displayResultToTelegramAdmin(filtered);
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
    private void sendOnlyAdminReport(Long chatIds, Map<String, UserData> userDataMap) {
        if (chatIds != null && chatIds > 0) {
            String message = personalService.displayResultToTelegramAdmin(userDataMap);
            sendMessageSafe(chatIds, message, "Админ");
        } else {
            log.warn("У Админа отсутствует chatId");
        }
    }

    private void sendMessageSafe(Long chatId, String message, String who) {
        try {
            telegramService.sendMessage(chatId, message, "HTML");
        } catch (Exception e) {
            log.error("Ошибка отправки Telegram-сообщения для {}: {}", who, e.getMessage());
        }
    }

    private String generateMessageByRole(String role, String fio, UserData userData, Map<String, UserData> result) {
        switch (role) {
            case "ROLE_MANAGER":
                return "📌 <b>Ежедневная сводка</b>\n\n" +
                        "👤 <b>" + escapeHtml(fio) + "</b>\n" +
                        "Контроль: в норме все показатели 0.\n\n" +
                        "Лиды: <b>" + safeLong(userData.getLeadsNew()) + "</b>\n" +
                        "Проверка: в проверку <b>" + safeLong(userData.getOrderToCheck()) +
                        "</b>, на проверке <b>" + safeLong(userData.getOrderInCheck()) + "</b>\n" +
                        "Опубликовано: <b>" + safeLong(userData.getOrderInPublished()) + "</b>\n" +
                        "Оплата: счёт <b>" + safeLong(userData.getOrderInWaitingPay1()) +
                        "</b>, напоминание <b>" + safeLong(userData.getOrderInWaitingPay2()) +
                        "</b>, не оплачено <b>" + safeLong(userData.getOrderNoPay()) + "</b>\n" +
                        "Заказы: новые <b>" + safeLong(userData.getNewOrders()) +
                        "</b>, коррекция <b>" + safeLong(userData.getCorrectOrders()) + "</b>\n" +
                        "Выгул: <b>" + safeLong(userData.getInVigul()) +
                        "</b> | публикация: <b>" + safeLong(userData.getInPublish()) + "</b>";
            case "ROLE_WORKER":
                return "📌 <b>Ежедневная сводка</b>\n\n" +
                        "👷 <b>" + escapeHtml(fio) + "</b>\n" +
                        "Контроль: в норме все показатели 0.\n\n" +
                        "Заказы: новые <b>" + safeLong(userData.getNewOrders()) +
                        "</b>, коррекция <b>" + safeLong(userData.getCorrectOrders()) + "</b>\n" +
                        "Выгул: <b>" + safeLong(userData.getInVigul()) +
                        "</b> | публикация: <b>" + safeLong(userData.getInPublish()) + "</b>";
            default:
                return "Здравствуйте, " + escapeHtml(fio) + "!";
        }
    }

    private long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    enum ReportKind {
        MORNING("morning", "morning"),
        EVENING("evening", "evening");

        private final String key;
        private final String logName;

        ReportKind(String key, String logName) {
            this.key = key;
            this.logName = logName;
        }

        String key() {
            return key;
        }

        String logName() {
            return logName;
        }

        boolean enabled(TelegramReportScheduleSettingsResponse settings) {
            return this == MORNING ? settings.morningEnabled() : settings.eveningEnabled();
        }

        String time(TelegramReportScheduleSettingsResponse settings) {
            return this == MORNING ? settings.morningTime() : settings.eveningTime();
        }
    }

    enum ScheduledTelegramReportDecisionType {
        NOT_DUE,
        DUE,
        MISSED_WINDOW
    }

    record ScheduledTelegramReportDecision(
            ScheduledTelegramReportDecisionType type,
            String runKey,
            ZonedDateTime scheduledAt,
            ZonedDateTime now,
            ZonedDateTime deadline
    ) {
        static ScheduledTelegramReportDecision notDue() {
            return new ScheduledTelegramReportDecision(ScheduledTelegramReportDecisionType.NOT_DUE, "", null, null, null);
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
