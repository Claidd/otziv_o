package com.hunt.otziv.notification_media.service;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.service.WorkerService;
import com.hunt.otziv.worker_performance.dto.DailyWorkProgressResponse;
import com.hunt.otziv.worker_performance.service.StaffDailyProgressService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ThematicStaffNotificationJob {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Irkutsk");
    private static final DateTimeFormatter MESSAGE_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private static final String ENABLED = "worker.thematic-notifications.enabled";
    private static final String MAX_PER_DAY = "worker.thematic-notifications.max-per-day";
    private static final String DAY_START_HOUR = "worker.thematic-notifications.day-start-hour";
    private static final String SITE_INACTIVE_HOUR = "worker.thematic-notifications.site-inactive-hour";
    private static final String PUBLICATION_HOUR = "worker.thematic-notifications.publication-hour";
    private static final String PROGRESS_HOUR = "worker.thematic-notifications.progress-hour";
    private static final String MANAGER_PROGRESS_HOUR = "manager.thematic-notifications.progress-hour";
    private static final String RETENTION_DAYS = "thematic-notifications.retention-days";

    private final WorkerService workerService;
    private final ManagerRepository managerRepository;
    private final StaffDailyProgressService staffDailyProgressService;
    private final NotificationMediaDeliveryService mediaDeliveryService;
    private final ThematicNotificationDispatchStore dispatchStore;
    private final AppSettingService appSettingService;

    @Scheduled(
            cron = "${worker.thematic-notifications.cron:0 0 11,13,16,17 * * *}",
            zone = "${worker.progress.zone:Asia/Irkutsk}"
    )
    public void dispatch() {
        if (!appSettingService.getBoolean(ENABLED, true)) {
            return;
        }
        dispatchAt(LocalDateTime.now(DEFAULT_ZONE));
    }

    void dispatchAt(LocalDateTime now) {
        LocalDate date = now.toLocalDate();
        int hour = now.getHour();
        int maxPerDay = bounded(appSettingService.getInt(MAX_PER_DAY, 2), 1, 5);
        List<Worker> workers = workerService.getAllWorkers().stream()
                .filter(Objects::nonNull)
                .filter(worker -> worker.getId() != null)
                .filter(worker -> eligibleUser(worker.getUser()))
                .toList();
        if (workers.isEmpty()) {
            return;
        }

        Map<Long, DailyWorkProgressResponse> progressByWorker =
                staffDailyProgressService.workerProgressByWorkers(workers, date);
        Map<Long, Long> publicationCounts = hour >= settingHour(PUBLICATION_HOUR, 16)
                ? dispatchStore.activePublicationCounts(
                        workers.stream().map(Worker::getId).toList(),
                        date
                )
                : Map.of();

        int workerSent = 0;
        for (Worker worker : workers) {
            DailyWorkProgressResponse progress = progressByWorker.get(worker.getId());
            if (!eligibleProgress(progress)) {
                continue;
            }
            if (dispatchWorker(worker, progress, publicationCounts.getOrDefault(worker.getId(), 0L),
                    now, maxPerDay)) {
                workerSent++;
            }
        }

        int managerSent = 0;
        if (hour >= settingHour(MANAGER_PROGRESS_HOUR, 17)) {
            managerSent = dispatchManagers(progressByWorker, date, maxPerDay);
        }

        int retentionDays = bounded(appSettingService.getInt(RETENTION_DAYS, 90), 7, 730);
        dispatchStore.deleteBefore(date.minusDays(retentionDays));
        log.info("Thematic staff notifications processed: workersSent={}, managersSent={}, date={}, hour={}",
                workerSent, managerSent, date, hour);
    }

    private boolean dispatchWorker(
            Worker worker,
            DailyWorkProgressResponse progress,
            long activePublicationCount,
            LocalDateTime now,
            int maxPerDay
    ) {
        User user = worker.getUser();
        long chatId = workerChatId(user);
        if (chatId == 0L) {
            return false;
        }
        LocalDate date = now.toLocalDate();
        boolean activeToday = progress.firstActivityAt() != null
                || progress.completed() > 0
                || loginWasToday(user, date);

        if (now.getHour() >= settingHour(SITE_INACTIVE_HOUR, 13) && !activeToday) {
            if (send(
                    NotificationMediaEventCatalog.WORKER_SITE_INACTIVE.code(),
                    user,
                    chatId,
                    date,
                    maxPerDay,
                    "👋 <b>Жека сегодня вас ещё не видел</b>\n\n"
                            + "На сегодня есть обязательная нагрузка: <b>" + progress.total() + "</b>.\n"
                            + "Откройте сайт и посмотрите, с чего удобнее начать."
            )) {
                return true;
            }
        }

        if (now.getHour() >= settingHour(DAY_START_HOUR, 11)
                && activeToday
                && progress.completed() == 0) {
            if (send(
                    NotificationMediaEventCatalog.WORKER_DAY_START.code(),
                    user,
                    chatId,
                    date,
                    maxPerDay,
                    "🦒 <b>Жека предлагает начать рабочий день</b>\n\n"
                            + "В очереди <b>" + progress.total() + "</b> обязательных задач, "
                            + "а выполненных пока нет.\nЛучше закрыть первый пункт прямо сейчас."
            )) {
                return true;
            }
        }

        long verifiedPublicationCount = Math.min(
                Math.max(0, activePublicationCount),
                Math.max(0, progress.active())
        );
        if (now.getHour() >= settingHour(PUBLICATION_HOUR, 16)
                && verifiedPublicationCount > 0) {
            if (send(
                    NotificationMediaEventCatalog.WORKER_PUBLICATION_PENDING.code(),
                    user,
                    chatId,
                    date,
                    maxPerDay,
                    "📝 <b>Публикация ждёт завершения</b>\n\n"
                            + "В разделе публикации осталось активных задач: <b>"
                            + verifiedPublicationCount + "</b>.\n"
                            + "Жека напоминает: публикация сама себя не сделает."
            )) {
                return true;
            }
        }

        if (now.getHour() >= settingHour(PROGRESS_HOUR, 17)
                && progress.percent() < 100) {
            if (send(
                    NotificationMediaEventCatalog.WORKER_PROGRESS_SLOWED.code(),
                    user,
                    chatId,
                    date,
                    maxPerDay,
                    "📈 <b>До дневной цели ещё можно успеть</b>\n\n"
                            + "Выполнено: <b>" + progress.completed() + " из " + progress.total()
                            + " (" + progress.percent() + "%)</b>.\n"
                            + "Жека предлагает выбрать один пункт и закрыть его сейчас."
            )) {
                return true;
            }
        }
        return false;
    }

    private int dispatchManagers(
            Map<Long, DailyWorkProgressResponse> progressByWorker,
            LocalDate date,
            int maxPerDay
    ) {
        List<Manager> managers = managerRepository.findAllWithUserAndImage();
        if (!managers.isEmpty()) {
            managers = managerRepository.findAllManagersWorkers(managers);
        }
        int sent = 0;
        for (Manager manager : managers) {
            User user = manager.getUser();
            if (!eligibleUser(user) || user.getId() == null) {
                continue;
            }
            long chatId = managerChatId(manager, user);
            if (chatId == 0L) {
                continue;
            }
            Collection<Worker> team = user.getWorkers() == null ? Set.of() : user.getWorkers();
            List<DailyWorkProgressResponse> progress = team.stream()
                    .filter(Objects::nonNull)
                    .map(Worker::getId)
                    .filter(Objects::nonNull)
                    .map(progressByWorker::get)
                    .filter(ThematicStaffNotificationJob::eligibleProgress)
                    .toList();
            if (progress.isEmpty() || progress.stream().allMatch(ThematicStaffNotificationJob::currentlyAtGoal)) {
                continue;
            }
            long atGoal = progress.stream().filter(ThematicStaffNotificationJob::currentlyAtGoal).count();
            int average = (int) Math.round(progress.stream()
                    .mapToInt(DailyWorkProgressResponse::percent)
                    .average()
                    .orElse(0));
            if (send(
                    NotificationMediaEventCatalog.MANAGER_TEAM_PROGRESS_SLOWED.code(),
                    user,
                    chatId,
                    date,
                    maxPerDay,
                    "📊 <b>Промежуточный прогресс команды</b>\n\n"
                            + "📅 Рабочий день: <b>" + MESSAGE_DATE_FORMATTER.format(date) + "</b>.\n"
                            + "Дневную цель выполнили: <b>" + atGoal + " из " + progress.size() + "</b>.\n"
                            + "Средний прогресс: <b>" + average + "%</b>.\n"
                            + "Жека подсказывает: сейчас ещё есть время помочь тем, кто отстаёт."
            )) {
                sent++;
            }
        }
        return sent;
    }

    private boolean send(
            String eventCode,
            User recipient,
            long chatId,
            LocalDate date,
            int maxPerDay,
            String text
    ) {
        if (!dispatchStore.claim(eventCode, recipient.getId(), date, maxPerDay)) {
            return false;
        }
        try {
            boolean sent = mediaDeliveryService.send(
                    eventCode,
                    chatId,
                    recipient.getId(),
                    text,
                    "HTML",
                    List.of()
            );
            if (sent) {
                dispatchStore.markSent(eventCode, recipient.getId(), date);
                return true;
            }
            dispatchStore.release(eventCode, recipient.getId(), date);
            return false;
        } catch (RuntimeException exception) {
            dispatchStore.release(eventCode, recipient.getId(), date);
            log.warn("Thematic notification failed eventCode={}, userId={}: {}",
                    eventCode, recipient.getId(), exception.getMessage());
            return false;
        }
    }

    private int settingHour(String key, int fallback) {
        return bounded(appSettingService.getInt(key, fallback), 0, 23);
    }

    private static int bounded(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static boolean eligibleUser(User user) {
        return user != null && user.getId() != null && user.isActive();
    }

    private static boolean eligibleProgress(DailyWorkProgressResponse progress) {
        return progress != null && progress.visible() && progress.total() > 0;
    }

    private static boolean currentlyAtGoal(DailyWorkProgressResponse progress) {
        return eligibleProgress(progress) && progress.completed() >= progress.total();
    }

    private static boolean loginWasToday(User user, LocalDate date) {
        return user.getLastLoginAt() != null
                && !user.getLastLoginAt().isBefore(date.atStartOfDay());
    }

    private static long workerChatId(User user) {
        if (user.getWorkerTelegramGroupChatId() != null) {
            return user.getWorkerTelegramGroupChatId();
        }
        return user.getTelegramChatId() == null ? 0L : user.getTelegramChatId();
    }

    private static long managerChatId(Manager manager, User user) {
        if (manager.getAuditTelegramGroupChatId() != null) {
            return manager.getAuditTelegramGroupChatId();
        }
        return user.getTelegramChatId() == null ? 0L : user.getTelegramChatId();
    }
}
