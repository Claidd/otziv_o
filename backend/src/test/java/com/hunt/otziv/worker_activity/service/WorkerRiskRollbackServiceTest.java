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
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkerRiskRollbackServiceTest {

    @Mock
    private WorkerRiskIncidentRepository incidentRepository;

    @Mock
    private BadReviewTaskRepository badReviewTaskRepository;

    @Mock
    private ReviewRecoveryTaskRepository recoveryTaskRepository;

    @Mock
    private ReviewRecoveryBatchRepository recoveryBatchRepository;

    @Mock
    private PersonalReminderService personalReminderService;

    @Mock
    private BusinessAuditService businessAuditService;

    private WorkerRiskRollbackService service;

    @BeforeEach
    void setUp() {
        service = new WorkerRiskRollbackService(
                incidentRepository,
                badReviewTaskRepository,
                recoveryTaskRepository,
                recoveryBatchRepository,
                personalReminderService,
                businessAuditService
        );
        when(incidentRepository.save(any(WorkerRiskIncident.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void rollbackBadTaskCompleteReturnsTaskToNew() {
        WorkerRiskIncident incident = incident("BAD_TASK_COMPLETE", 10L);
        BadReviewTask task = new BadReviewTask();
        task.setId(10L);
        task.setStatus(BadReviewTaskStatus.DONE);
        task.setCompletedDate(LocalDate.now());
        when(badReviewTaskRepository.findByIdForMutation(10L)).thenReturn(Optional.of(task));

        WorkerRiskIncident result = service.rollback(incident, actor());

        assertEquals(BadReviewTaskStatus.NEW, task.getStatus());
        assertEquals(null, task.getCompletedDate());
        assertEquals(WorkerRiskRollbackStatus.APPLIED, result.getRollbackStatus());
        assertNotNull(result.getRolledBackAt());
        verify(badReviewTaskRepository).save(task);
    }

    @Test
    void rollbackRecoveryTaskCompleteReopensCompletedBatch() {
        WorkerRiskIncident incident = incident("RECOVERY_TASK_COMPLETE", 20L);
        ReviewRecoveryBatch batch = new ReviewRecoveryBatch();
        batch.setId(30L);
        batch.setStatus(ReviewRecoveryBatchStatus.COMPLETED);

        ReviewRecoveryTask task = new ReviewRecoveryTask();
        task.setId(20L);
        task.setStatus(ReviewRecoveryTaskStatus.DONE);
        task.setCompletedDate(LocalDate.now());
        task.setCompletedBy(actor());
        task.setBatch(batch);
        when(recoveryTaskRepository.findById(20L)).thenReturn(Optional.of(task));

        WorkerRiskIncident result = service.rollback(incident, actor());

        assertEquals(ReviewRecoveryTaskStatus.PLANNED, task.getStatus());
        assertEquals(null, task.getCompletedDate());
        assertEquals(null, task.getCompletedBy());
        assertEquals(ReviewRecoveryBatchStatus.OPEN, batch.getStatus());
        assertEquals(WorkerRiskRollbackStatus.APPLIED, result.getRollbackStatus());
        verify(recoveryTaskRepository).save(task);
        verify(recoveryBatchRepository).save(batch);
    }

    @Test
    void unsupportedActionIsMarkedNotApplicable() {
        WorkerRiskIncident incident = incident("REVIEW_PUBLISH", 40L);

        WorkerRiskIncident result = service.rollback(incident, actor());

        assertEquals(WorkerRiskRollbackStatus.NOT_APPLICABLE, result.getRollbackStatus());
    }

    private WorkerRiskIncident incident(String action, Long entityId) {
        WorkerRiskIncident incident = new WorkerRiskIncident();
        incident.setId(77L);
        incident.setStatus(WorkerRiskIncidentStatus.VIOLATION);
        incident.setAction(action);
        incident.setEntityId(entityId);
        incident.setOrderId(100L);
        incident.setReviewId(200L);
        return incident;
    }

    private User actor() {
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        return user;
    }
}
