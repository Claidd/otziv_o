package com.hunt.otziv.manager_daily_summary.service;

import com.hunt.otziv.worker_activity.model.WorkerRiskExplanationQuality;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncident;
import com.hunt.otziv.worker_activity.model.WorkerRiskIncidentStatus;
import com.hunt.otziv.worker_activity.repository.WorkerRiskIncidentRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagerRiskDailyReportSectionServiceTest {

    @Mock
    private WorkerRiskIncidentRepository repository;

    @Test
    void reportShowsShortOrIncompleteAnswersAsConcreteCases() {
        LocalDate date = LocalDate.of(2026, 7, 25);
        WorkerRiskIncident incident = new WorkerRiskIncident();
        incident.setId(77L);
        incident.setAssignedManagerId(10L);
        incident.setStatus(WorkerRiskIncidentStatus.OPEN);
        incident.setCreatedAt(LocalDateTime.of(2026, 7, 25, 12, 0));
        incident.setTitle("Почему карточка закрыта без выполнения?");
        incident.setWorkerExplanation("Проверим");
        incident.setExplanationQuality(WorkerRiskExplanationQuality.PARTIAL);
        incident.setExplanationQualityReason("Ответ слишком общий и не объясняет выполненные действия");
        incident.setResponseDueAt(LocalDateTime.of(2026, 7, 25, 15, 0));

        when(repository.findPerformanceIncidentsByAssignedManagerId(
                eq(List.of(10L)),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq(WorkerRiskIncidentStatus.OPEN)
        )).thenReturn(List.of(incident));

        String report = new ManagerRiskDailyReportSectionService(repository).format(10L, date);

        assertTrue(report.contains("Пояснения: по существу 0 · неполные 1"));
        assertTrue(report.contains("«Проверим»"));
        assertTrue(report.contains("Ответ слишком общий"));
        assertTrue(report.contains("Примеры для разбора"));
        assertTrue(report.contains("Не принимайте «Хорошо» и «Проверим»"));
    }
}
