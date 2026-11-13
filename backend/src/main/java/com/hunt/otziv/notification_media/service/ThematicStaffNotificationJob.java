package com.hunt.otziv.notification_media.service;

import org.springframework.dao.DataAccessException;
import java.util.EnumSet;
import com.hunt.otziv.notification_media.api.StaffPictureContext;
import com.hunt.otziv.notification_media.api.StaffMediaSignal;

import com.hunt.otziv.config.settings.service.AppSettingService;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.repository.ManagerRepository;
import com.hunt.otziv.u_users.service.WorkerService;
import com.hunt.otziv.worker_performance.dto.DailyWorkProgressResponse;
import com.hunt.otziv.worker_performance.service.StaffDailyProgressService;
import com.hunt.otziv.workload_shadow.api.MandatoryAccountBlockers;
import com.hunt.otziv.workload_shadow.api.MandatoryAccountBlockers.BlockedTask;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
    private static final String ACCOUNT_BLOCKER_ENABLED = "worker.account-blocker-notice.enabled";
    private static final String ACCOUNT_BLOCKER_DELAY_MINUTES = "worker.account-blocker-notice.delay-minutes";
    private static final String MANAGER_ACCOUNT_BLOCKER_HOUR = "manager.account-blocker-notice.hour";
    /**
     * An account blocker decides whether the day can reach 100%, so this operational notice is
     * outside the motivational per-day cap (see ThematicNotificationDispatchStore); it is still
     * sent at most once per recipient per day.
     */
    private static final int OPERATIONAL_NOTICE_CAP = Integer.MAX_VALUE;

    private final WorkerService workerService;
    private final ManagerRepository managerRepository;
    private final StaffDailyProgressService staffDailyProgressService;
    private final NotificationMediaDeliveryService mediaDeliveryService;
    private final ThematicNotificationDispatchStore dispatchStore;
    private final AppSettingService appSettingService;
    private final org.springframework.context.ApplicationEventPublisher mediaEvents;
    private final MandatoryAccountBlockers accountBlockers;
    /** Field-injected to keep the constructor of narrow tests unchanged. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.hunt.otziv.common_billing.api.CommonInvoiceOverdueNotices overdueNotices;
    private static final String COMMON_INVOICE_OVERDUE_NOTICE_ENABLED = "manager.common-invoice-overdue-notice.enabled";

    /**
     * Manager-group notice before the autoresponder moves unpaid common invoices to "Не оплачено".
     * The move waits for the recorded notice, so an invoice whose manager has no reachable chat
     * is not moved automatically and stays a Control card for the manager's decision.
     */
    @Scheduled(
            cron = "${manager.common-invoice-overdue-notice.cron:0 */15 10-19 * * *}",
            zone = "${worker.progress.zone:Asia/Irkutsk}"
    )
    public void dispatchCommonInvoiceOverdueNotices() {
        if (overdueNotices == null
                || !appSettingService.getBoolean(ENABLED, true)
                || !appSettingService.getBoolean(COMMON_INVOICE_OVERDUE_NOTICE_ENABLED, true)) {
            return;
        }
        try {
            dispatchCommonInvoiceOverdueNoticesAt(LocalDate.now(DEFAULT_ZONE), LocalDateTime.now());
        } catch (RuntimeException exception) {
            log.warn("Common invoice overdue notices skipped: {}", exception.getMessage());
        }
    }

    void dispatchCommonInvoiceOverdueNoticesAt(LocalDate date, LocalDateTime storageNow) {
        var pending = overdueNotices.pendingNotices(storageNow);
        if (pending.isEmpty()) {
            return;
        }
        Map<Long, List<com.hunt.otziv.common_billing.api.CommonInvoiceOverdueNotices.Approaching>> byManager =
                new java.util.LinkedHashMap<>();
        pending.forEach(invoice -> byManager.computeIfAbsent(invoice.managerId(), id -> new java.util.ArrayList<>())
                .add(invoice));
        String eventCode = NotificationMediaEventCatalog.MANAGER_COMMON_INVOICE_OVERDUE_SOON.code();
        int notified = 0;
        for (var entry : byManager.entrySet()) {
            Manager manager = managerRepository.findByIdWithUser(entry.getKey()).orElse(null);
            User user = manager == null ? null : manager.getUser();
            long chatId = manager == null || !eligibleUser(user) ? 0L : managerChatId(manager, user);
            if (chatId == 0L) {
                log.warn("Common invoice overdue notice has no manager chat: managerId={}, invoices={}",
                        entry.getKey(), entry.getValue().size());
                continue;
            }
            if (send(eventCode, user, chatId, date, OPERATIONAL_NOTICE_CAP,
                    CommonInvoiceOverdueNoticeText.manager(entry.getValue()))) {
                LocalDateTime noticeAt = LocalDateTime.now();
                entry.getValue().forEach(invoice ->
                        overdueNotices.recordNotice(invoice.invoiceId(), invoice.sentAt(), noticeAt));
                notified++;
            }
        }
        if (notified > 0) {
            log.info("Common invoice overdue notices sent: managers={}, invoices={}", notified, pending.size());
        }
    }

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

        MandatoryAccountBlockers.WorkloadDay shift = null;
        if (hour >= settingHour(PROGRESS_HOUR, 17)) {
            try {
                shift = accountBlockers.currentDay();
            } catch (DataAccessException unavailable) {
                log.warn("Shift hours unavailable for notification pictures", unavailable);
            }
        }
        int workerSent = 0;
        for (Worker worker : workers) {
            DailyWorkProgressResponse progress = progressByWorker.get(worker.getId());
            if (!eligibleProgress(progress)) {
                continue;
            }
            if (dispatchWorker(worker, progress, publicationCounts.getOrDefault(worker.getId(), 0L),
                    now, maxPerDay, shift)) {
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

    /**
     * Staff-only notice about today's cards that have no usable account. The day close adds them
     * to the denominator, so an unnoticed blocker costs the specialist the 100% day. The specialist
     * gets the list once per day, after the shift has started; the manager gets a short version
     * once per day from the configured midday hour if cards are still blocked.
     */
    @Scheduled(
            cron = "${worker.account-blocker-notice.cron:0 */15 * * * *}",
            zone = "${worker.progress.zone:Asia/Irkutsk}"
    )
    public void dispatchAccountBlockers() {
        if (!appSettingService.getBoolean(ENABLED, true)
                || !appSettingService.getBoolean(ACCOUNT_BLOCKER_ENABLED, true)) {
            return;
        }
        try {
            MandatoryAccountBlockers.WorkloadDay day = accountBlockers.currentDay();
            dispatchAccountBlockersAt(LocalDateTime.now(day.zone()), day);
        } catch (RuntimeException exception) {
            log.warn("Account blocker notices skipped: {}", exception.getMessage());
        }
    }

    void dispatchAccountBlockersAt(LocalDateTime now, MandatoryAccountBlockers.WorkloadDay day) {
        if (day == null || !day.blockersCountAgainstDay()) {
            return;
        }
        LocalTime time = now.toLocalTime();
        int delayMinutes = bounded(appSettingService.getInt(ACCOUNT_BLOCKER_DELAY_MINUTES, 15), 0, 240);
        // The shift window doubles as quiet hours: nothing is sent before the plan starts or after it ends.
        if (time.isBefore(day.shiftStart().plusMinutes(delayMinutes)) || !time.isBefore(day.shiftEnd())) {
            return;
        }
        LocalDate date = now.toLocalDate();
        List<Worker> workers = workerService.getAllWorkers().stream()
                .filter(Objects::nonNull)
                .filter(worker -> worker.getId() != null)
                .filter(worker -> eligibleUser(worker.getUser()))
                .toList();
        if (workers.isEmpty()) {
            return;
        }
        String workerEvent = NotificationMediaEventCatalog.WORKER_ACCOUNT_BLOCKED_TASKS.code();
        Set<Long> notifiedWorkers = dispatchStore.recipientsWithDispatch(workerEvent, date);
        List<Worker> pendingWorkers = workers.stream()
                .filter(worker -> workerChatId(worker.getUser()) != 0L)
                .filter(worker -> !notifiedWorkers.contains(worker.getUser().getId()))
                .toList();

        boolean managerPhase = now.getHour() >= settingHour(MANAGER_ACCOUNT_BLOCKER_HOUR, 13);
        List<Manager> pendingManagers = managerPhase ? pendingAccountBlockerManagers(date) : List.of();
        Set<Long> workerIds = new java.util.LinkedHashSet<>();
        pendingWorkers.forEach(worker -> workerIds.add(worker.getId()));
        Map<Long, Worker> activeWorkers = new java.util.LinkedHashMap<>();
        workers.forEach(worker -> activeWorkers.putIfAbsent(worker.getId(), worker));
        pendingManagers.forEach(manager -> teamWorkerIds(manager).stream()
                .filter(activeWorkers::containsKey)
                .forEach(workerIds::add));
        if (workerIds.isEmpty()) {
            return;
        }
        Map<Long, List<BlockedTask>> blocked = accountBlockers.blockedTasks(workerIds, now);
        if (blocked.isEmpty()) {
            return;
        }

        int workerSent = 0;
        for (Worker worker : pendingWorkers) {
            List<BlockedTask> tasks = blocked.getOrDefault(worker.getId(), List.of());
            if (!tasks.isEmpty() && send(workerEvent, worker.getUser(), workerChatId(worker.getUser()), date,
                    OPERATIONAL_NOTICE_CAP, AccountBlockerNoticeText.worker(date, tasks), blockerFacts(tasks))) {
                workerSent++;
            }
        }
        int managerSent = 0;
        for (Manager manager : pendingManagers) {
            Map<String, List<BlockedTask>> byWorker = new java.util.LinkedHashMap<>();
            teamWorkerIds(manager).stream()
                    .map(activeWorkers::get)
                    .filter(Objects::nonNull)
                    .sorted(java.util.Comparator.comparing(ThematicStaffNotificationJob::workerName,
                            String.CASE_INSENSITIVE_ORDER))
                    .forEach(worker -> {
                        List<BlockedTask> tasks = blocked.getOrDefault(worker.getId(), List.of());
                        if (!tasks.isEmpty()) {
                            String name = workerName(worker);
                            byWorker.put(byWorker.containsKey(name) ? name + " (#" + worker.getId() + ")" : name, tasks);
                        }
                    });
            if (!byWorker.isEmpty() && send(
                    NotificationMediaEventCatalog.MANAGER_WORKER_ACCOUNT_BLOCKED.code(),
                    manager.getUser(),
                    managerChatId(manager, manager.getUser()),
                    date,
                    OPERATIONAL_NOTICE_CAP,
                    AccountBlockerNoticeText.manager(date, byWorker))) {
                managerSent++;
            }
        }
        if (workerSent > 0 || managerSent > 0) {
            log.info("Account blocker notices sent: workers={}, managers={}, date={}", workerSent, managerSent, date);
        }
    }

    private List<Manager> pendingAccountBlockerManagers(LocalDate date) {
        Set<Long> notified = dispatchStore.recipientsWithDispatch(
                NotificationMediaEventCatalog.MANAGER_WORKER_ACCOUNT_BLOCKED.code(), date);
        List<Manager> managers = managerRepository.findAllWithUserAndImage();
        if (!managers.isEmpty()) {
            managers = managerRepository.findAllManagersWorkers(managers);
        }
        return managers.stream()
                .filter(Objects::nonNull)
                .filter(manager -> eligibleUser(manager.getUser()))
                .filter(manager -> managerChatId(manager, manager.getUser()) != 0L)
                .filter(manager -> !notified.contains(manager.getUser().getId()))
                .toList();
    }

    private static List<Long> teamWorkerIds(Manager manager) {
        Collection<Worker> team = manager.getUser() == null || manager.getUser().getWorkers() == null
                ? Set.of()
                : manager.getUser().getWorkers();
        return team.stream().filter(Objects::nonNull).map(Worker::getId).filter(Objects::nonNull)
                .distinct().toList();
    }

    private static String workerName(Worker worker) {
        User user = worker.getUser();
        if (user == null) {
            return "Специалист #" + worker.getId();
        }
        if (user.getFio() != null && !user.getFio().isBlank()) {
            return user.getFio().trim();
        }
        return user.getUsername() == null || user.getUsername().isBlank()
                ? "Специалист #" + worker.getId()
                : user.getUsername();
    }

    private boolean dispatchWorker(
            Worker worker,
            DailyWorkProgressResponse progress,
            long activePublicationCount,
            LocalDateTime now,
            int maxPerDay,
            MandatoryAccountBlockers.WorkloadDay shift
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
                            + "Жека предлагает выбрать один пункт и закрыть его сейчас.",
                    progressFacts(progress, now, shift)
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
            mediaEvents.publishEvent(new StaffMediaSignal(user.getId(), "MANAGER_CONTEXT", null, null, null, null));
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

    static Set<StaffPictureContext> blockerFacts(List<BlockedTask> tasks) {
        // A conditional tip on requesting help is valid for a blocked card.
        var facts = EnumSet.of(StaffPictureContext.ACCOUNT_ACCESS);
        if (!tasks.isEmpty() && tasks.stream().noneMatch(BlockedTask::recoveryAccountMissing)) {
            facts.add(StaffPictureContext.CHANGE_ACCOUNT_BUTTON);
        }
        return facts;
    }

    static Set<StaffPictureContext> progressFacts(DailyWorkProgressResponse progress, LocalDateTime now,
                                                 MandatoryAccountBlockers.WorkloadDay day) {
        var facts = EnumSet.noneOf(StaffPictureContext.class);
        if (progress.total() - progress.completed() == 1 && progress.active() == 1) facts.add(StaffPictureContext.LAST_TASK);
        if (day != null && day.shiftEnd().equals(LocalTime.of(22, 0))) {
            var local = now.atZone(DEFAULT_ZONE).withZoneSameInstant(day.zone()).toLocalTime();
            if (!local.isBefore(LocalTime.of(17, 0)) && local.isBefore(day.shiftEnd())) facts.add(StaffPictureContext.BEFORE_22);
        }
        return facts;
    }

    private boolean send(String eventCode, User recipient, long chatId, LocalDate date, int maxPerDay, String text) {
        return send(eventCode, recipient, chatId, date, maxPerDay, text, Set.of());
    }

    private boolean send(
            String eventCode,
            User recipient,
            long chatId,
            LocalDate date,
            int maxPerDay,
            String text,
            Set<StaffPictureContext> facts
    ) {
        if (!dispatchStore.claim(eventCode, recipient.getId(), date, maxPerDay)) {
            return false;
        }
        try {
            boolean sent = facts.isEmpty()
                    ? mediaDeliveryService.send(eventCode, chatId, recipient.getId(), text, "HTML", List.of())
                    : mediaDeliveryService.send(eventCode, chatId, recipient.getId(), text, "HTML", List.of(), facts);
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
