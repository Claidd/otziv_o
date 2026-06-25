package com.hunt.otziv.worker_activity;

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

    @Scheduled(cron = "${worker.activity.cleanup.cron:0 45 3 * * *}", zone = "Asia/Irkutsk")
    @Transactional
    public void cleanup() {
        LocalDateTime eventCutoff = LocalDateTime.now().minusDays(Math.max(1, eventRetentionDays));
        LocalDateTime incidentCutoff = LocalDateTime.now().minusDays(Math.max(30, closedIncidentRetentionDays));
        long deletedEvents = eventRepository.deleteByCreatedAtBefore(eventCutoff);
        long deletedIncidents = incidentRepository.deleteByStatusNotAndCreatedAtBefore(
                WorkerRiskIncidentStatus.OPEN,
                incidentCutoff
        );
        if (deletedEvents > 0 || deletedIncidents > 0) {
            log.info("Очистка активности специалистов: deletedEvents={}, deletedClosedIncidents={}", deletedEvents, deletedIncidents);
        }
    }
}
