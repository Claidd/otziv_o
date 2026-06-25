package com.hunt.otziv.review_recovery.services;

import com.hunt.otziv.review_recovery.model.ReviewRecoveryBatch;
import com.hunt.otziv.review_recovery.model.ReviewRecoveryTask;
import com.hunt.otziv.u_users.model.Manager;
import com.hunt.otziv.u_users.model.User;
import com.hunt.otziv.u_users.model.Worker;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface ReviewRecoveryTaskService {

    List<ReviewRecoveryTask> getTasksByOrderId(Long orderId);

    ReviewRecoveryTask createTask(Long reviewId, User createdBy);

    ReviewRecoveryTask getTask(Long taskId);

    ReviewRecoveryTask updateTask(Long taskId, String recoveryText, String recoveryAnswer, LocalDate scheduledDate);

    ReviewRecoveryTask completeTask(Long taskId, User completedBy);

    ReviewRecoveryTask cancelTask(Long taskId);

    ReviewRecoveryTask changeTaskBot(Long taskId);

    ReviewRecoveryTask deactivateAndChangeTaskBot(Long taskId, Long botId);

    ReviewRecoveryBatch markClientNotified(Long batchId, User notifiedBy);

    ReviewRecoveryBatch markClientNotifiedAutomatically(Long batchId);

    boolean taskBelongsToOrder(Long taskId, Long orderId);

    boolean batchBelongsToOrder(Long batchId, Long orderId);

    Page<ReviewRecoveryTask> getDueTasksToAdmin(LocalDate date, String keyword, Pageable pageable);

    Page<ReviewRecoveryTask> getDueTasksToOwner(Collection<Manager> managers, LocalDate date, String keyword, Pageable pageable);

    Page<ReviewRecoveryTask> getDueTasksToManager(Manager manager, LocalDate date, String keyword, Pageable pageable);

    Page<ReviewRecoveryTask> getDueTasksToWorker(Worker worker, LocalDate date, String keyword, Pageable pageable);

    int countDueTasksToAdmin(LocalDate date);

    int countDueTasksToOwner(Collection<Manager> managers, LocalDate date);

    int countDueTasksToManager(Manager manager, LocalDate date);

    int countDueTasksToWorker(Worker worker, LocalDate date);

    int countCompletedBatchesToAdmin();

    int countCompletedBatchesToOwner(Collection<Manager> managers);

    int countCompletedBatchesToManager(Manager manager);

    int archiveClientNotifiedBefore(Instant cutoff, Instant archivedAt);
}
