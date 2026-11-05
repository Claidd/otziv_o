package com.hunt.otziv.worker_activity.service;

import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentStatus;
import com.hunt.otziv.worker_activity.repository.WorkerActivityEventRepository;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class WorkerActivityCleanupJob {

    private final WorkerActivityEventRepository eventRepository;
    private final WorkerRiskIncidentRepository incidentRepository;

    @Value("${worker.activity.retention.events-days:90}")
    private int eventRetentionDays;

    @Value("${worker.activity.retention.closed-incidents-days:365}")
    private int closedIncidentRetentionDays;

    private static final int BATCH_SIZE = 5000;
    private static final int MAX_BATCHES = 200;

    /**
     * Small committed batches: one transaction over the whole tail held locks and undo for the
     * entire retention backlog and materialized every row in the heap.
     */
    @Scheduled(cron = "${worker.activity.cleanup.cron:0 45 3 * * *}", zone = "Asia/Irkutsk")
    public void cleanup() {
        LocalDateTime eventCutoff = LocalDateTime.now().minusDays(Math.max(1, eventRetentionDays));
        LocalDateTime incidentCutoff = LocalDateTime.now().minusDays(Math.max(30, closedIncidentRetentionDays));
        long deletedEvents = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            int deleted = eventRepository.deleteBatchCreatedBefore(eventCutoff, BATCH_SIZE);
            deletedEvents += deleted;
            if (deleted < BATCH_SIZE) break;
        }
        long deletedIncidents = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            int deleted = incidentRepository.deleteClosedBatchCreatedBefore(incidentCutoff, BATCH_SIZE);
            deletedIncidents += deleted;
            if (deleted < BATCH_SIZE) break;
        }
        if (deletedEvents > 0 || deletedIncidents > 0) {
            log.info("Очистка активности специалистов: deletedEvents={}, deletedClosedIncidents={}", deletedEvents, deletedIncidents);
        }
    }
}
