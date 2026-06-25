package com.hunt.otziv.p_products.worker_flow;

import com.hunt.otziv.bad_reviews.services.BadReviewTaskService;
import com.hunt.otziv.config.settings.AppSettingService;
import com.hunt.otziv.p_products.services.service.OrderService;
import com.hunt.otziv.review_recovery.services.ReviewRecoveryTaskService;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import com.hunt.otziv.u_users.services.service.UserService;
import com.hunt.otziv.u_users.services.service.WorkerService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.security.Principal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class WorkerPublicationGateService {

    public static final String SECTION_NEW = "new";
    public static final String SECTION_CORRECT = "correct";
    public static final String SECTION_RECOVERY = "recovery";
    public static final String SECTION_PUBLISH = "publish";
    public static final String SECTION_BAD = "bad";
    public static final String SECTION_ALL = "all";

    private static final String ORDER_STATUS_NEW = "Новый";
    private static final String ORDER_STATUS_CORRECT = "Коррекция";
    private static final int FLOW_BLOCKING_UNCHANGED_DAYS = 1;
    private static final int SPECIAL_TASK_BLOCKING_OVERDUE_DAYS = 2;
    private static final Set<String> FLOW_ORDER_STATUSES = Set.of(
            ORDER_STATUS_NEW,
            ORDER_STATUS_CORRECT
    );
    private static final String FLOW_BLOCK_MESSAGE = "В разделах \"Новые\" или \"Коррекция\" есть заказы без изменений 1 день или больше. "
            + "Публикация и раздел \"Все\" откроются, когда в \"Новых\" и \"Коррекции\" не останется активных заказов. "
            + "Заказы, которые ждут клиента, переход не блокируют";
    private static final String SPECIAL_TASK_BLOCK_MESSAGE = "В разделах \"Восстановление\" или \"Плохие\" была задача с просрочкой больше 2 дней. "
            + "Публикация и раздел \"Все\" откроются, когда в восстановлении и плохих не останется активных задач на сегодня";
    private static final String SPECIAL_TASK_LOCK_SUFFIX = "special-tasks";

    private final OrderService orderService;
    private final BadReviewTaskService badReviewTaskService;
    private final ReviewRecoveryTaskService reviewRecoveryTaskService;
    private final UserService userService;
    private final WorkerService workerService;
    private final WorkerFlowLockService workerFlowLockService;
    private final AppSettingService appSettingService;

    public Optional<PublicationBlock> redirectFor(Principal principal, Authentication authentication, String requestedSection) {
        if (!isWorkerFlowRestricted(authentication) || !isPublishOrAll(requestedSection)) {
            return Optional.empty();
        }

        return evaluateWorker(principal);
    }

    public Optional<PublicationBlock> blockForPublication(Principal principal, Authentication authentication) {
        if (!isWorkerFlowRestricted(authentication)) {
            return Optional.empty();
        }

        return evaluateWorker(principal);
    }

    public void syncFromMetrics(Principal principal, Authentication authentication, int flowOrders) {
        if (!isWorkerFlowRestricted(authentication)) {
            return;
        }

        Worker worker = resolveWorker(principal);
        boolean hasFlowOrders = flowOrders > 0;
        workerFlowLockService.syncPublicationLock(
                workerFlowLockKey(worker, principal),
                workerId(worker),
                hasFlowOrders,
                hasFlowOrders && hasStaleWorkerFlowOrders(worker)
        );
    }

    private Optional<PublicationBlock> evaluateWorker(Principal principal) {
        Worker worker = resolveWorker(principal);
        Map<String, Integer> orderCounts = orderService.countActionableOrdersByStatusToWorker(worker);
        int newOrders = countStatus(orderCounts, ORDER_STATUS_NEW);
        int correctionOrders = countStatus(orderCounts, ORDER_STATUS_CORRECT);
        String lockKey = workerFlowLockKey(worker, principal);
        boolean hasFlowOrders = newOrders + correctionOrders > 0;

        if (!hasFlowOrders) {
            workerFlowLockService.syncPublicationLock(lockKey, workerId(worker), false, false);
        } else if (workerFlowLockService.syncPublicationLock(
                lockKey,
                workerId(worker),
                true,
                hasStaleWorkerFlowOrders(worker)
        )) {
            return Optional.of(new PublicationBlock(
                    newOrders > 0 ? SECTION_NEW : SECTION_CORRECT,
                    FLOW_BLOCK_MESSAGE
            ));
        }

        return specialTaskBlock(worker, principal);
    }

    private boolean hasStaleWorkerFlowOrders(Worker worker) {
        Map<String, Integer> staleCounts = orderService.countActionableOrdersByStatusToWorkerChangedOnOrBefore(
                worker,
                FLOW_ORDER_STATUSES,
                LocalDate.now().minusDays(FLOW_BLOCKING_UNCHANGED_DAYS)
        );

        return countStatus(staleCounts, ORDER_STATUS_NEW) + countStatus(staleCounts, ORDER_STATUS_CORRECT) > 0;
    }

    private Optional<PublicationBlock> specialTaskBlock(Worker worker, Principal principal) {
        if (!specialTaskGateEnabled()) {
            return Optional.empty();
        }

        LocalDate today = LocalDate.now();
        LocalDate overdueCutoff = specialTaskCutoffDate();
        int recoveryDueToday = countSpecialTasks(worker, SECTION_RECOVERY, today);
        int badDueToday = countSpecialTasks(worker, SECTION_BAD, today);
        int recoveryOverdueTrigger = countSpecialTasks(worker, SECTION_RECOVERY, overdueCutoff);
        int badOverdueTrigger = countSpecialTasks(worker, SECTION_BAD, overdueCutoff);

        boolean locked = workerFlowLockService.syncPublicationLock(
                specialTaskLockKey(worker, principal),
                workerId(worker),
                recoveryDueToday + badDueToday > 0,
                recoveryOverdueTrigger + badOverdueTrigger > 0
        );

        if (!locked) {
            return Optional.empty();
        }

        return Optional.of(new PublicationBlock(
                specialTaskRedirectSection(recoveryDueToday, badDueToday, recoveryOverdueTrigger, badOverdueTrigger),
                SPECIAL_TASK_BLOCK_MESSAGE
        ));
    }

    private String specialTaskRedirectSection(
            int recoveryDueToday,
            int badDueToday,
            int recoveryOverdueTrigger,
            int badOverdueTrigger
    ) {
        if (recoveryOverdueTrigger > 0 || recoveryDueToday > 0 && badOverdueTrigger == 0) {
            return SECTION_RECOVERY;
        }

        if (badOverdueTrigger > 0 || badDueToday > 0) {
            return SECTION_BAD;
        }

        return SECTION_RECOVERY;
    }

    private int countSpecialTasks(Worker worker, String section, LocalDate date) {
        if (SECTION_RECOVERY.equals(section)) {
            return reviewRecoveryTaskService.countDueTasksToWorker(worker, date);
        }

        if (SECTION_BAD.equals(section)) {
            return badReviewTaskService.countDueTasksToWorker(worker, date);
        }

        return 0;
    }

    private LocalDate specialTaskCutoffDate() {
        return LocalDate.now().minusDays(SPECIAL_TASK_BLOCKING_OVERDUE_DAYS + 1L);
    }

    private boolean specialTaskGateEnabled() {
        if (appSettingService.getBoolean(AppSettingService.WORKER_PUBLICATION_SPECIAL_TASK_GATE_ENABLED, false)) {
            return true;
        }

        String activateOn = appSettingService.getString(AppSettingService.WORKER_PUBLICATION_SPECIAL_TASK_GATE_ACTIVATE_ON, "");
        if (activateOn == null || activateOn.isBlank()) {
            return false;
        }

        try {
            return !LocalDate.now().isBefore(LocalDate.parse(activateOn));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private Worker resolveWorker(Principal principal) {
        String username = principal == null ? "" : principal.getName();
        User user = userService.findByUserName(username)
                .orElseThrow(() -> new IllegalStateException("Пользователь не найден"));
        return workerService.getWorkerByUserId(user.getId());
    }

    private Long workerId(Worker worker) {
        return worker == null ? null : worker.getId();
    }

    private String workerFlowLockKey(Worker worker, Principal principal) {
        if (worker != null && worker.getId() != null) {
            return "worker:" + worker.getId();
        }

        return "principal:" + (principal == null ? "" : principal.getName());
    }

    private String specialTaskLockKey(Worker worker, Principal principal) {
        return workerFlowLockKey(worker, principal) + ":" + SPECIAL_TASK_LOCK_SUFFIX;
    }

    private boolean isWorkerFlowRestricted(Authentication authentication) {
        return hasRole(authentication, "WORKER")
                && !hasRole(authentication, "ADMIN")
                && !hasRole(authentication, "OWNER")
                && !hasRole(authentication, "MANAGER");
    }

    private boolean isPublishOrAll(String section) {
        return SECTION_PUBLISH.equals(section)
                || SECTION_ALL.equals(section);
    }

    private boolean hasRole(Authentication authentication, String role) {
        if (authentication == null) {
            return false;
        }

        String authority = role.startsWith("ROLE_") ? role : "ROLE_" + role;
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
    }

    private int countStatus(Map<String, Integer> counts, String status) {
        if (counts == null || status == null) {
            return 0;
        }
        return counts.getOrDefault(status, 0);
    }

    public record PublicationBlock(String section, String message) {
    }
}
