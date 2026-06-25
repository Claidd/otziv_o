package com.hunt.otziv.worker_activity.service;

import com.hunt.otziv.bad_reviews.model.BadReviewTask;
import com.hunt.otziv.bad_reviews.model.BadReviewTaskStatus;
import com.hunt.otziv.bad_reviews.repository.BadReviewTaskRepository;
import com.hunt.otziv.business_audit.service.BusinessAuditService;
import com.hunt.otziv.personal_reminders.service.PersonalReminderService;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryBatch;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryBatchStatus;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTask;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTaskStatus;
import com.hunt.otziv.review_recovery.repository.ReviewRecoveryBatchRepository;
import com.hunt.otziv.review_recovery.repository.ReviewRecoveryTaskRepository;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentStatus;
import com.hunt.otziv.worker_activity.model.WorkerRiskRollbackStatus;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkerRiskRollbackService {

    private final WorkerRiskIncidentRepository incidentRepository;
    private final BadReviewTaskRepository badReviewTaskRepository;
    private final ReviewRecoveryTaskRepository recoveryTaskRepository;
    private final ReviewRecoveryBatchRepository recoveryBatchRepository;
    private final PersonalReminderService personalReminderService;
    private final BusinessAuditService businessAuditService;

    @Transactional
    public WorkerRiskIncident rollback(WorkerRiskIncident incident, User actor) {
        if (incident.getStatus() != WorkerRiskIncidentStatus.VIOLATION) {
            throw new IllegalStateException("Откат доступен только для подтвержденных нарушений");
        }
        if (incident.getRollbackStatus() != null) {
            return incident;
        }

        RollbackOutcome outcome = switch (safeAction(incident)) {
            case "BAD_TASK_COMPLETE" -> rollbackBadTask(incident);
            case "RECOVERY_TASK_COMPLETE" -> rollbackRecoveryTask(incident);
            default -> RollbackOutcome.notApplicable("Для этого типа действия автоматический возврат пока не поддерживается");
        };

        incident.setRollbackStatus(outcome.status());
        incident.setRollbackMessage(outcome.message());
        incident.setRolledBackAt(LocalDateTime.now());
        incident.setRolledBackByUserId(actor == null ? null : actor.getId());
        incident.setRolledBackByUsername(actor == null ? null : actor.getUsername());
        return incidentRepository.save(incident);
    }

    private RollbackOutcome rollbackBadTask(WorkerRiskIncident incident) {
        Long taskId = incident.getEntityId();
        if (taskId == null) {
            return RollbackOutcome.notApplicable("Не найден id плохой задачи в инциденте");
        }

        BadReviewTask task = badReviewTaskRepository.findByIdForMutation(taskId).orElse(null);
        if (task == null) {
            return RollbackOutcome.notApplicable("Плохая задача уже не найдена");
        }
        if (task.getStatus() == BadReviewTaskStatus.NEW) {
            return RollbackOutcome.applied("Плохая задача уже была в работе, дополнительный возврат не требовался");
        }
        if (task.getStatus() != BadReviewTaskStatus.DONE) {
            return RollbackOutcome.notApplicable("Плохая задача не в статусе выполнено: " + task.getStatus());
        }

        task.setStatus(BadReviewTaskStatus.NEW);
        task.setCompletedDate(null);
        badReviewTaskRepository.save(task);
        deleteBadTaskCompletionReminders(task);
        audit(incident, "bad_review_task", taskId, "DONE", "NEW",
                "Worker risk rollback: bad task returned to work");
        return RollbackOutcome.applied(
                "Плохая задача возвращена в работу. Если после ложного закрытия клиенту уже ушел счет, проверьте оплату/переписку вручную."
        );
    }

    private RollbackOutcome rollbackRecoveryTask(WorkerRiskIncident incident) {
        Long taskId = incident.getEntityId();
        if (taskId == null) {
            return RollbackOutcome.notApplicable("Не найден id задачи восстановления в инциденте");
        }

        ReviewRecoveryTask task = recoveryTaskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return RollbackOutcome.notApplicable("Задача восстановления уже не найдена");
        }
        if (task.getStatus() == ReviewRecoveryTaskStatus.PLANNED) {
            return RollbackOutcome.applied("Задача восстановления уже была в работе, дополнительный возврат не требовался");
        }
        if (task.getStatus() != ReviewRecoveryTaskStatus.DONE) {
            return RollbackOutcome.notApplicable("Задача восстановления не в статусе выполнено: " + task.getStatus());
        }

        ReviewRecoveryBatch batch = task.getBatch();
        if (batch != null && (batch.getStatus() == ReviewRecoveryBatchStatus.CLIENT_NOTIFIED
                || batch.getStatus() == ReviewRecoveryBatchStatus.ARCHIVED)) {
            return RollbackOutcome.notApplicable(
                    "Пачка восстановления уже уведомлена клиенту или архивирована. Нужен ручной разбор."
            );
        }

        task.setStatus(ReviewRecoveryTaskStatus.PLANNED);
        task.setCompletedDate(null);
        task.setCompletedBy(null);
        recoveryTaskRepository.save(task);

        if (batch != null && batch.getStatus() == ReviewRecoveryBatchStatus.COMPLETED) {
            batch.setStatus(ReviewRecoveryBatchStatus.OPEN);
            batch.setCompletedAt(null);
            batch.setClientNotifiedAt(null);
            batch.setClientNotifiedBy(null);
            recoveryBatchRepository.save(batch);
            deleteRecoveryCompletionReminder(batch);
        }

        audit(incident, "recovery_task", taskId, "DONE", "PLANNED",
                "Worker risk rollback: recovery task returned to work");
        return RollbackOutcome.applied("Задача восстановления возвращена в работу");
    }

    private void deleteBadTaskCompletionReminders(BadReviewTask task) {
        User managerUser = task.getOrder() != null && task.getOrder().getManager() != null
                ? task.getOrder().getManager().getUser()
                : null;
        if (managerUser == null) {
            return;
        }
        personalReminderService.deleteSystemReminderBySource(
                managerUser,
                PersonalReminderService.SOURCE_BAD_REVIEW_TASK,
                task.getId()
        );
        Long orderId = task.getOrder() == null ? null : task.getOrder().getId();
        personalReminderService.deleteSystemReminderBySource(
                managerUser,
                PersonalReminderService.SOURCE_BAD_REVIEW_ORDER_READY,
                orderId
        );
    }

    private void deleteRecoveryCompletionReminder(ReviewRecoveryBatch batch) {
        User managerUser = batch.getManager() == null ? null : batch.getManager().getUser();
        if (managerUser == null) {
            return;
        }
        personalReminderService.deleteSystemReminderBySource(
                managerUser,
                PersonalReminderService.SOURCE_REVIEW_RECOVERY_BATCH,
                batch.getId()
        );
        Long orderId = batch.getOrder() == null ? null : batch.getOrder().getId();
        if (orderId != null) {
            personalReminderService.deleteSystemRemindersByTitlePrefixAndTextFragment(
                    managerUser,
                    "Восстановление завершено",
                    "#" + orderId
            );
        }
    }

    private void audit(
            WorkerRiskIncident incident,
            String entityType,
            Long entityId,
            String oldValue,
            String newValue,
            String details
    ) {
        businessAuditService.recordSafely(
                "worker_risk_rollback",
                entityType,
                entityId,
                incident.getOrderId(),
                incident.getReviewId(),
                oldValue,
                newValue,
                details + ", incidentId=" + incident.getId()
        );
    }

    private String safeAction(WorkerRiskIncident incident) {
        return incident.getAction() == null ? "" : incident.getAction();
    }

    private record RollbackOutcome(WorkerRiskRollbackStatus status, String message) {
        static RollbackOutcome applied(String message) {
            return new RollbackOutcome(WorkerRiskRollbackStatus.APPLIED, message);
        }

        static RollbackOutcome notApplicable(String message) {
            return new RollbackOutcome(WorkerRiskRollbackStatus.NOT_APPLICABLE, message);
        }
    }
}
